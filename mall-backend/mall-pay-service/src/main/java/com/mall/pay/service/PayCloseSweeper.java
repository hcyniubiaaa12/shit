package com.mall.pay.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.pay.entity.PayInfo;
import com.mall.pay.mapper.PayInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 关单补偿定时任务：把"本地已关闭、渠道交易却还开着"的支付单收口。
 *
 * <p>为什么需要它：订单取消链路（order-service 的用户取消 / 30 分钟超时取消）只直写本地
 * {@code pay_info.status=3}（单库演示约定），<b>没有调用支付宝关单</b>，而项目里也没有
 * 服务间 HTTP 调用可以承载这一步。后果是：用户的收银台页面只要还开着，取消之后仍然能付款成功，
 * 于是钱付了、订单没了（实测踩过）。本任务定时扫描这类支付单，逐笔交给
 * {@link PayService#reconcileClosedPay}：
 *
 * <ul>
 *   <li>渠道未支付 → 调 {@code alipay.trade.close} 关掉交易</li>
 *   <li>渠道已支付 → 悬挂款原路退回</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>只扫最近关闭的</b>（默认 10 分钟窗口）+ 每轮限 20 笔：避免启动或首次运行时
 *       把历史数据全捞一遍、瞬间打爆渠道接口</li>
 *   <li><b>逐笔 try/catch</b>：一笔失败不影响其他笔，且不打对账标记，下一轮自动重试</li>
 *   <li><b>多实例安全</b>：对账标记在 Redis 里（见 {@code PayService#reconciledBefore}），
 *       同一支付单只会被真正处理一次</li>
 *   <li><b>兜底定位</b>：同步关渠道交易的正解是取消订单时调
 *       {@code POST /api/pay/close/{orderNo}}（Feign/MQ 编排，见迭代记录遗留项），
 *       本任务是不依赖跨服务改动的等效保障</li>
 * </ul>
 *
 * <p>扫描间隔可用 {@code mall.pay.close-sweep-interval-ms} 配置（默认 60000ms）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayCloseSweeper {

    /** 只扫最近关闭的支付单（小时）。放宽到 6 小时是为了让服务重启后能自动补上此前漏掉的对账 */
    private static final long WINDOW_HOURS = 6;

    /** 单轮候选查询上限（真正处理多少由 {@link #BATCH} 与 Redis 对账标记共同决定） */
    private static final int SCAN_LIMIT = 200;

    /** 单轮最多真正对账多少笔：限流，避免瞬时打爆渠道接口 */
    private static final int BATCH = 20;

    private final PayInfoMapper payInfoMapper;
    private final PayService payService;

    @Scheduled(fixedDelayString = "${mall.pay.close-sweep-interval-ms:60000}")
    public void sweep() {
        List<PayInfo> candidates = payInfoMapper.selectList(new LambdaQueryWrapper<PayInfo>()
                // 已关闭 + 从未记录过渠道流水（真付成功过就会有 trade_no，说明已入账或已退款）
                .eq(PayInfo::getStatus, 3)
                .isNull(PayInfo::getTradeNo)
                .isNotNull(PayInfo::getOutTradeNo)
                .gt(PayInfo::getUpdateTime, LocalDateTime.now().minusHours(WINDOW_HOURS))
                .orderByDesc(PayInfo::getId)
                .last("LIMIT " + SCAN_LIMIT));
        if (candidates.isEmpty()) {
            return;
        }
        int handled = 0;
        for (PayInfo pay : candidates) {
            if (handled >= BATCH) {
                break;
            }
            // 已对账过的直接跳过：候选集里它们会一直存在（标记在 Redis 里，SQL 过滤不到），
            // 不过滤就会把名额全占了，新关闭的单永远轮不到
            if (payService.isReconciled(pay.getPayNo())) {
                continue;
            }
            try {
                payService.reconcileClosedPay(pay);
                handled++;
            } catch (Exception e) {
                // 单笔失败不影响其他笔；没打对账标记，下一轮会自动重试
                log.warn("[PAY] 关单补偿单笔失败（下一轮重试）: payNo={}, err={}", pay.getPayNo(), e.getMessage());
            }
        }
        if (handled > 0) {
            log.info("[PAY] 关单补偿完成一轮: 处理 {} 笔（候选 {} 笔）", handled, candidates.size());
        }
    }
}
