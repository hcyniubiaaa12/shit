package com.mall.order.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.BizException;
import com.mall.common.MallConstants;
import com.mall.order.dto.CreateOrderRequest;
import com.mall.order.entity.*;
import com.mall.order.mapper.*;
import com.mall.order.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 订单核心：下单（跨店拆单 + 库存）、取消、发货、确认收货。
 *
 * 库存链路（单库演示环境约定，见 database-design §4"库存链路（同步方案）"）：
 *   ① Redis Lua 原子预扣 stock:{skuId}（防超卖的缓存挡板）
 *   ② 事务内：创建订单 + 同库条件扣 DB（UPDATE ... WHERE stock >= N，天然幂等）
 *   ③ 任一步失败：回滚事务 + Lua 回加 Redis；超时取消由延迟消息触发 DB+Lua 双向回补
 * 生产拆分时应将 ② 移至 product-service 本地事务/消息编排（本服务直连 sku 属同库演示约定）。
 *
 * 取消链路（同库直写 pay_info，见 database-design §4"取消链路（最小实现）"）：
 *   条件更新 0→4 → 关 pay_info（仅待支付可关）→ 双向回补库存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    /** 超时取消消息 */
    public static final String TIMEOUT_TOPIC = "mall-order-timeout-topic";

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final CartMapper cartMapper;
    private final SkuOrderMapper skuOrderMapper;
    private final ProductMerchantMapper productMerchantMapper;
    private final ProductTitleMapper productTitleMapper;
    private final ProductImgMapper productImgMapper;
    private final PayInfoMapper payInfoMapper;

    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;

    /** 两个 Lua 脚本为同类型 bean：字段注入 + @Qualifier 按名区分（Lombok 构造器不携带 @Qualifier） */
    @Autowired
    @Qualifier("stockDeductScript")
    private DefaultRedisScript<Long> stockDeductScript;

    @Autowired
    @Qualifier("stockRefundScript")
    private DefaultRedisScript<Long> stockRefundScript;

    @Value("${mall.order.timeout-delay-level}")
    private int timeoutDelayLevel;

    /** 支付超时时长（分钟）：与延迟等级对应（level 16 = 30m），供订单接口计算前端倒计时剩余秒数 */
    @Value("${mall.order.pay-timeout-minutes:30}")
    private long payTimeoutMinutes;

    private static final DateTimeFormatter ORDER_NO_FMT = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    // -----------------------------------------------------
    // 下单
    // -----------------------------------------------------

    /**
     * 下单（Sentinel 保护：demo 下 service 侧默认不加载 QPS 规则——由网关 order-create-api 限流
     * 生效；annotation 用于演示 block/fallback 兜底；接入 Dashboard 后可在服务侧下发流控规则）。
     */
    @SentinelResource(value = "orderCreate", blockHandler = "createBlocked", fallback = "createFallback")
    @Transactional
    public List<String> create(Long userId, CreateOrderRequest req) {
        // 下单幂等：Redis 请求键，重放直接失败
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(MallConstants.REDIS_ORDER_REQ_KEY + userId + ":" + req.getReqId(), "1", Duration.ofMinutes(5));
        if (Boolean.FALSE.equals(first)) {
            throw new BizException("请勿重复提交订单");
        }
        // 去重合并同 SKU
        Map<Long, Integer> countMap = new LinkedHashMap<>();
        for (CreateOrderRequest.Item item : req.getItems()) {
            countMap.merge(item.getSkuId(), item.getCount(), Integer::sum);
        }
        // 1. 加载 SKU（含价格、库存、商品归属）
        List<SkuOrder> skus = skuOrderMapper.selectActiveBatch(new ArrayList<>(countMap.keySet()));
        if (skus.size() != countMap.size()) {
            throw new BizException("部分商品已下架，请刷新购物车");
        }
        Map<Long, SkuOrder> skuById = skus.stream().collect(Collectors.toMap(SkuOrder::getId, s -> s));
        List<ProductMerchant> owned = productMerchantMapper.selectByKeys(
                skus.stream().map(SkuOrder::getProductId).distinct().toList());
        Map<Long, Long> productMerchant = owned.stream().collect(Collectors.toMap(ProductMerchant::getId, ProductMerchant::getMerchantId));

        // 2. 按商家拆单
        Map<Long, List<SkuOrder>> byMerchant = skus.stream().collect(Collectors.groupingBy(s -> productMerchant.get(s.getProductId())));

        // 3. 逐单创建：Redis 预扣 -> 建单 -> DB 条件扣库存
        List<String> orderNos = new ArrayList<>();
        for (Map.Entry<Long, List<SkuOrder>> entry : byMerchant.entrySet()) {
            List<OrderItem> items = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (SkuOrder sku : entry.getValue()) {
                int count = countMap.get(sku.getId());
                BigDecimal amount = sku.getPrice().multiply(BigDecimal.valueOf(count));
                total = total.add(amount);
                OrderItem item = new OrderItem();
                item.setOrderId(null); // 订单插入后再回填
                item.setSkuId(sku.getId());
                item.setProductId(sku.getProductId());
                item.setTitle(productTitleMapper.selectTitle(sku.getProductId()));
                item.setSpecJson(sku.getSpecJson());
                item.setPrice(sku.getPrice());
                item.setCount(count);
                item.setAmount(amount);
                items.add(item);
            }
            // 预扣 Redis（条件：key 存在且充足）
            for (SkuOrder sku : entry.getValue()) {
                Long left = redisTemplate.execute(stockDeductScript,
                        List.of(MallConstants.REDIS_STOCK_KEY + sku.getId()), String.valueOf(countMap.get(sku.getId())));
                if (left == null || left == -1L) {
                    throw new BizException("库存暂不可用，请稍后重试");
                }
                if (left == -2L) {
                    throw new BizException("库存不足：" + sku.getSpecJson());
                }
            }
            // 创建订单
            OrderDO order = new OrderDO();
            order.setOrderNo(genOrderNo(userId));
            order.setUserId(userId);
            order.setMerchantId(entry.getKey());
            order.setTotalAmount(total);
            order.setPayAmount(total);
            order.setFreight(BigDecimal.ZERO);
            order.setStatus(0);
            order.setReceiverName(req.getReceiverName());
            order.setReceiverPhone(req.getReceiverPhone());
            order.setReceiverAddress(req.getReceiverAddress());
            orderMapper.insert(order);
            for (OrderItem item : items) {
                item.setOrderId(order.getId());
                item.setOrderNo(order.getOrderNo());
                orderItemMapper.insert(item);
            }
            // DB 条件扣减（同事务；0 行 -> 库存不足回滚）
            for (SkuOrder sku : entry.getValue()) {
                int n = skuOrderMapper.deductStock(sku.getId(), countMap.get(sku.getId()));
                if (n == 0) {
                    throw new BizException("库存不足：" + sku.getSpecJson());
                }
            }
            orderNos.add(order.getOrderNo());
        }

        // 4. 清除已下单购物车行
        if (Boolean.TRUE.equals(req.getFromCart())) {
            List<Long> skuIds = req.getItems().stream().map(CreateOrderRequest.Item::getSkuId).toList();
            cartMapper.delete(new LambdaQueryWrapper<Cart>()
                    .eq(Cart::getUserId, userId)
                    .in(Cart::getSkuId, skuIds));
        }

        // 5. 超时取消延迟消息（消费端做状态校验，重复消费幂等）
        // 健壮性：broker 不可用时仅停用"超时自动取消"，不阻断下单主链路（手动取消仍可用；
        // broker 侧修复：确认 docker/broker.conf 挂载生效、brokerIP1 配宿主地址）
        for (String orderNo : orderNos) {
            org.springframework.messaging.Message<String> msg =
                    org.springframework.messaging.support.MessageBuilder.withPayload(orderNo).build();
            try {
                rocketMQTemplate.syncSend(TIMEOUT_TOPIC, msg, 5000, timeoutDelayLevel);
            } catch (Exception e) {
                log.warn("[超时取消] 延迟消息发送失败（超时取消暂不可用）: orderNo={} cause={}", orderNo, e.getMessage());
            }
        }
        return orderNos;
    }

    // -----------------------------------------------------
    // 取消（手动 / 超时，均走条件更新，幂等）
    // -----------------------------------------------------

    @Transactional
    public void cancel(String orderNo, Long userIdOrNull) {
        OrderDO order = orderMapper.selectOne(new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException("订单不存在");
        }
        if (userIdOrNull != null && !order.getUserId().equals(userIdOrNull)) {
            throw new BizException(403, "无权操作该订单");
        }
        // 幂等：非"待支付"（已支付/已取消/已发货）直接静默返回（超时消费重放安全）
        if (order.getStatus() != null && order.getStatus() != 0) {
            return;
        }
        // 状态机：仅待支付可取消（0 -> 4）
        int updated = orderMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getOrderNo, orderNo)
                .eq(OrderDO::getStatus, 0)
                .set(OrderDO::getStatus, 4)
                .set(OrderDO::getCancelTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException("订单状态已变化，不能取消");
        }
        // 关支付单（仅待支付可关，此处与取消同事务）
        payInfoMapper.closePending(orderNo);
        // 双向回补库存
        refundStockOfOrder(orderNo);
    }

    // -----------------------------------------------------
    // 发货 / 确认收货
    // -----------------------------------------------------

    public void ship(Long merchantId, String orderNo, String logisticsCompany, String trackingNo) {
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<OrderDO> lw =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<OrderDO>()
                        .eq(OrderDO::getOrderNo, orderNo)
                        .eq(OrderDO::getStatus, 1)
                        .set(OrderDO::getStatus, 2)
                        .set(OrderDO::getSendTime, LocalDateTime.now())
                        .set(OrderDO::getLogisticsCompany, logisticsCompany)
                        .set(OrderDO::getTrackingNo, trackingNo)
                        .set(OrderDO::getLastStatus, 1);
        if (merchantId != null) {
            lw.eq(OrderDO::getMerchantId, merchantId);
        }
        int updated = orderMapper.update(null, lw);
        if (updated == 0) {
            throw new BizException("订单不存在或当前状态不能发货");
        }
    }

    public void confirmReceive(Long userId, String orderNo) {
        int updated = orderMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getOrderNo, orderNo)
                .eq(OrderDO::getUserId, userId)
                .eq(OrderDO::getStatus, 2)
                .set(OrderDO::getStatus, 3)
                .set(OrderDO::getReceiveTime, LocalDateTime.now())
                .set(OrderDO::getLastStatus, 2));
        if (updated == 0) {
            throw new BizException("订单不存在或当前状态不能确认收货");
        }
    }

    // -----------------------------------------------------
    // 查询
    // -----------------------------------------------------

    /**
     * 我的订单：status 状态筛选；keyword 匹配订单号 / 收货人 / 商品名；
     * sort=asc 按下单时间正序，其余（默认）倒序。
     */
    public Page<OrderVO> pageByUser(Long userId, Integer status, String keyword, String sort, int page, int size) {
        LambdaQueryWrapper<OrderDO> lw = new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getUserId, userId)
                .eq(status != null, OrderDO::getStatus, status);
        applyKeyword(lw, keyword);
        lw.orderBy(true, "asc".equalsIgnoreCase(sort), OrderDO::getCreateTime);
        return toVOPage(orderMapper.selectPage(new Page<>(page, size), lw));
    }

    /** 商家订单（仅本店）：status 状态筛选；keyword 匹配订单号 / 收货人 / 商品名 */
    public Page<OrderVO> pageByMerchant(Long merchantId, Integer status, String keyword, int page, int size) {
        LambdaQueryWrapper<OrderDO> lw = new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getMerchantId, merchantId)
                .eq(status != null, OrderDO::getStatus, status);
        applyKeyword(lw, keyword);
        lw.orderByDesc(OrderDO::getCreateTime);
        return toVOPage(orderMapper.selectPage(new Page<>(page, size), lw));
    }

    /** 平台订单（全局）：status 状态筛选；keyword 匹配订单号 / 收货人 / 商品名 */
    public Page<OrderVO> adminPage(Integer status, String keyword, int page, int size) {
        LambdaQueryWrapper<OrderDO> lw = new LambdaQueryWrapper<OrderDO>()
                .eq(status != null, OrderDO::getStatus, status);
        applyKeyword(lw, keyword);
        lw.orderByDesc(OrderDO::getCreateTime);
        return toVOPage(orderMapper.selectPage(new Page<>(page, size), lw));
    }

    /** 订单搜索公共条件：订单号 / 收货人 / 商品名（明细快照 title）任一命中 */
    private void applyKeyword(LambdaQueryWrapper<OrderDO> lw, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String kw = keyword.trim();
        List<String> matchedOrderNos = orderItemMapper.selectOrderNosByTitleLike(kw);
        lw.and(w -> {
            w.like(OrderDO::getOrderNo, kw).or().like(OrderDO::getReceiverName, kw);
            if (!matchedOrderNos.isEmpty()) {
                w.or().in(OrderDO::getOrderNo, matchedOrderNos);
            }
        });
    }

    public OrderVO detail(String orderNo) {
        OrderDO o = orderMapper.selectOne(new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getOrderNo, orderNo));
        if (o == null) {
            throw new BizException("订单不存在");
        }
        return toVO(o);
    }

    // -----------------------------------------------------
    // Sentinel 兜底（blockHandler 限流熔断/fallback 异常降级）
    // -----------------------------------------------------

    public List<String> createBlocked(Long userId, CreateOrderRequest req, Throwable ex) {
        throw new BizException("下单请求过于频繁，请稍后再试");
    }

    public List<String> createFallback(Long userId, CreateOrderRequest req, Throwable ex) {
        if (ex instanceof BizException) {
            throw (BizException) ex;
        }
        throw new BizException("下单服务繁忙，请稍后再试");
    }

    // -----------------------------------------------------
    // 私有
    // -----------------------------------------------------

    private void refundStockOfOrder(String orderNo) {
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderNo, orderNo));
        for (OrderItem item : items) {
            skuOrderMapper.refundStock(item.getSkuId(), item.getCount());
            redisTemplate.execute(stockRefundScript,
                    List.of(MallConstants.REDIS_STOCK_KEY + item.getSkuId()), String.valueOf(item.getCount()));
        }
    }

    private Page<OrderVO> toVOPage(Page<OrderDO> p) {
        List<OrderVO> vos = p.getRecords().stream().map(this::toVO).collect(Collectors.toList());
        Page<OrderVO> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        result.setRecords(vos);
        return result;
    }

    private OrderVO toVO(OrderDO o) {
        OrderVO vo = new OrderVO();
        vo.setId(o.getId());
        vo.setOrderNo(o.getOrderNo());
        vo.setUserId(o.getUserId());
        vo.setMerchantId(o.getMerchantId());
        vo.setTotalAmount(o.getTotalAmount());
        vo.setPayAmount(o.getPayAmount());
        vo.setFreight(o.getFreight());
        vo.setStatus(o.getStatus());
        vo.setPayTime(o.getPayTime());
        vo.setCancelTime(o.getCancelTime());
        vo.setRemark(o.getRemark());
        vo.setReceiverName(o.getReceiverName());
        vo.setReceiverPhone(o.getReceiverPhone());
        vo.setReceiverAddress(o.getReceiverAddress());
        vo.setLogisticsCompany(o.getLogisticsCompany());
        vo.setTrackingNo(o.getTrackingNo());
        vo.setSendTime(o.getSendTime());
        vo.setReceiveTime(o.getReceiveTime());
        vo.setCreateTime(o.getCreateTime());
        // 待支付订单附剩余秒数（服务端时间基准）；已到期取 0，非待支付不返回（null）
        if (o.getStatus() != null && o.getStatus() == 0 && o.getCreateTime() != null) {
            long left = payTimeoutMinutes * 60 - Duration.between(o.getCreateTime(), LocalDateTime.now()).getSeconds();
            vo.setRemainSeconds(Math.max(0, left));
        }
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderNo, o.getOrderNo()));
        fillItemImg(items);
        vo.setItems(items);
        return vo;
    }

    /** 订单明细补商品主图：实时读 product.main_img（商品已删除则该行为空，前端显示占位） */
    private void fillItemImg(List<OrderItem> items) {
        if (items.isEmpty()) {
            return;
        }
        List<Long> productIds = items.stream().map(OrderItem::getProductId).distinct().toList();
        Map<Long, String> imgById = productImgMapper.selectImgByKeys(productIds).stream()
                .filter(p -> p.getMainImg() != null)
                .collect(Collectors.toMap(ProductImg::getId, ProductImg::getMainImg, (a, b) -> a));
        items.forEach(i -> i.setMainImg(imgById.get(i.getProductId())));
    }

    private String genOrderNo(Long userId) {
        StringBuilder sb = new StringBuilder(LocalDateTime.now().format(ORDER_NO_FMT));
        String uid = String.valueOf(userId);
        sb.append(uid.length() >= 6 ? uid.substring(uid.length() - 6) : uid);
        sb.append(String.format("%04d", ThreadLocalRandom.current().nextInt(10000)));
        return sb.toString();
    }

}
