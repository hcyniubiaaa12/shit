package com.mall.pay.gateway;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Mock 渠道：无密钥闭环演示。
 *
 * <p>行为与真实渠道保持一致（同样的接口、同样的回调入口），差别只在于：
 * <ul>
 *   <li>创建支付返回本系统的演示支付页，而不是支付宝收银台</li>
 *   <li>退款同步成功，不经过支付宝</li>
 * </ul>
 * 因此 Mock 与沙箱可以随时切换，业务代码、回调链路、幂等逻辑完全复用。
 *
 * <p>由 {@link GatewayConfiguration} 装配（沙箱未启用时生效）。
 */
@Slf4j
public class MockAlipayClient implements AlipayGatewayClient {

    /** 演示支付页地址（前端路由 /pay/:orderNo），用户在此页手动触发"模拟支付成功/失败" */
    private final String demoPayBase;

    public MockAlipayClient(String demoPayBase) {
        this.demoPayBase = demoPayBase == null ? "" : demoPayBase.replaceAll("/+$", "");
    }

    @Override
    public PayCreateResult createPay(String payNo, String orderNo, String subject, BigDecimal amount) {
        String amountStr = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        log.info("[MOCK渠道] 创建支付: payNo={}, orderNo={}, amount={}", payNo, orderNo, amountStr);
        String url = demoPayBase + "/pay/" + orderNo;
        return new PayCreateResult(payNo, orderNo, amountStr, url, false);
    }

    @Override
    public void closePay(String outTradeNo) {
        log.info("[MOCK渠道] 关闭支付: outTradeNo={}", outTradeNo);
    }

    /**
     * Mock 渠道没有"渠道侧"可查：本地演示的到账由
     * {@code POST /api/pay/mock/callback} 直接触发同一入账链路。
     */
    @Override
    public PayQueryResult queryPay(String outTradeNo) {
        log.info("[MOCK渠道] 主动查单: outTradeNo={}（演示渠道无渠道侧状态，恒为未支付）", outTradeNo);
        return PayQueryResult.notPaid(outTradeNo, "MOCK");
    }

    @Override
    public String refundPay(String outTradeNo, String outRequestNo, BigDecimal amount, String reason) {
        log.info("[MOCK渠道] 退款成功: outTradeNo={}, outRequestNo={}, amount={}",
                outTradeNo, outRequestNo, amount);
        return "MOCK-REFUND-" + outRequestNo;
    }

    @Override
    public int channelCode() {
        return 0;
    }

    @Override
    public boolean isRealChannel() {
        return false;
    }
}
