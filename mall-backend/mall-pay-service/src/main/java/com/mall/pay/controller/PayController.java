package com.mall.pay.controller;

import com.mall.common.MallConstants;
import com.mall.common.Result;
import com.mall.pay.entity.PayInfo;
import com.mall.pay.gateway.PayCreateResult;
import com.mall.pay.service.PayService;
import com.mall.pay.vo.PaySyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户支付接口（经网关访问，网关已验证 JWT type=0）。
 *
 * <p>渠道说明：
 * <ul>
 *   <li>沙箱模式（{@code mall.alipay.enabled=true}）：{@code /create} 返回支付宝收银台 URL，
 *       用户付款后支付宝回调 {@code /alipay/notify}（验签后入账）</li>
 *   <li>Mock 模式（默认）：{@code /create} 返回演示页 URL，由 {@code /mock/callback} 触发同一入账链路</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class PayController {

    private final PayService payService;

    /**
     * 创建支付单：返回支付凭证（payNo + 支付跳转 URL + 是否真实渠道）。
     */
    @PostMapping("/create")
    public Result<PayCreateResult> create(@RequestHeader(MallConstants.HEADER_USER_ID) Long userId,
                                          @RequestBody Map<String, String> body) {
        return Result.ok(payService.create(userId, body.get("orderNo")));
    }

    /**
     * 查询支付凭证（前端刷新支付页时用）。
     * 注意：不返回可跳转 URL —— 待支付时前端应再调一次 {@code /create} 取渠道凭证，
     * 避免这里凭空拼出一个无效的支付宝地址。
     */
    @GetMapping("/info/{orderNo}")
    public Result<PayCreateResult> info(@PathVariable String orderNo) {
        PayInfo pay = payService.findByOrderNo(orderNo);
        if (pay == null) {
            return Result.ok(null);
        }
        return Result.ok(new PayCreateResult(pay.getPayNo(), orderNo,
                pay.getAmount() == null ? null : pay.getAmount().toPlainString(),
                null, payService.isRealChannel()));
    }

    /**
     * 演示用模拟回调（Mock 渠道；沙箱模式请用 /alipay/notify）。
     * 入参 {payNo, tradeNo, amount}：amount 与支付单不一致则拒绝入账（金额核对）。
     */
    @PostMapping("/mock/callback")
    public Result<Void> mockCallback(@RequestBody Map<String, String> body) {
        payService.handleCallback(body.get("payNo"), body.get("tradeNo"), "amount=" + body.get("amount"));
        return Result.ok();
    }

    /**
     * 支付宝异步通知（沙箱/生产通用入口）。
     *
     * <p>关键约束：
     * <ol>
     *   <li>必须是 {@code application/x-www-form-urlencoded} 的原始参数，用 Map 全量接收</li>
     *   <li>应答体必须是纯文本 {@code success}（支付宝据此判断是否重试），
     *       不能包成统一 JSON 响应体</li>
     *   <li>处理顺序：验签 → 状态校验 → 金额核对 → 幂等入账（见 PayService.handleAlipayNotify）</li>
     * </ol>
     */
    @PostMapping(value = "/alipay/notify", produces = "text/plain;charset=UTF-8")
    public String alipayNotify(@RequestParam Map<String, String> params) {
        // 避免把签名等敏感参数整体打日志，仅记录关键字段
        log.info("[PAY] 收到支付宝异步通知: outTradeNo={}, tradeStatus={}",
                params.get("out_trade_no"), params.get("trade_status"));
        try {
            boolean ok = payService.handleAlipayNotify(new HashMap<>(params));
            return ok ? "success" : "failure";
        } catch (Exception e) {
            log.error("[PAY] 支付宝异步通知处理异常", e);
            return "failure";
        }
    }

    @GetMapping("/status/{orderNo}")
    public Result<Boolean> status(@PathVariable String orderNo) {
        return Result.ok(payService.isPaid(orderNo));
    }

    /**
     * 主动查单补偿：让后端向支付宝确认这笔支付单是否已支付，已支付则立即入账。
     *
     * <p>为什么前端要调它而不是只轮询 {@code /status}：异步通知要求 {@code notify-base}
     * 是支付宝能访问到的**公网地址**，本机开发（localhost）收不到通知，
     * 只靠通知订单会一直停在"待支付"。支付页与同步跳转落地页轮询本接口，
     * 就能在无公网地址的情况下跑通"付款 → 订单已支付"闭环；
     * 生产环境它也是通知丢失/延迟时的对账兜底（内部有节流 + 完整幂等）。
     *
     * @param orderNo 订单号<b>或</b>支付单号（支付宝同步跳转回传的是支付单号）
     */
    @PostMapping("/sync/{orderNo}")
    public Result<PaySyncResult> sync(@PathVariable String orderNo) {
        return Result.ok(payService.syncPayStatus(orderNo));
    }

    /** 退款申请（沙箱走 alipay.trade.refund；Mock 即时成功） */
    @PostMapping("/refund")
    public Result<String> refund(@RequestHeader(MallConstants.HEADER_USER_ID) Long userId,
                                 @RequestBody Map<String, String> body) {
        return Result.ok(payService.refund(userId, body.get("orderNo"), body.get("reason")));
    }

    /**
     * 关闭渠道支付单（订单取消/超时取消时调用；幂等）。
     *
     * <p>用途：订单取消后立刻关掉支付宝侧交易，用户手里那个还开着的收银台页面就付不了了。
     * 定时任务 {@code PayCloseSweeper} 是兜底（默认每分钟扫一次），这里是"当场关掉"的入口，
     * 供 order-service 编排调用（Feign/MQ，见迭代记录遗留项）或运维手工调用。
     *
     * @param orderNo 订单号或支付单号
     */
    @PostMapping("/close/{orderNo}")
    public Result<Void> close(@PathVariable String orderNo) {
        payService.closePay(orderNo);
        return Result.ok();
    }
}
