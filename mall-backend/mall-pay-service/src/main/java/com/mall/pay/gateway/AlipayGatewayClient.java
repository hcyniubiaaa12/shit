package com.mall.pay.gateway;

import java.math.BigDecimal;

/**
 * 支付渠道抽象（支付创建/关闭/退款）。
 *
 * <p>实现：
 * <ul>
 *   <li>{@link AlipaySandboxClient} —— 支付宝开放平台沙箱真实接入（配置齐全且
 *       {@code mall.alipay.enabled=true} 时生效）</li>
 *   <li>{@link MockAlipayClient} —— 本地演示兜底（沙箱未配置时的默认实现）</li>
 * </ul>
 *
 * <p>两个实现共用同一套回调处理链路（{@code PayService.handleCallback}），
 * 差异只在于「谁来证明这笔钱真的到账了」：Mock 由演示接口直接触发，
 * 沙箱由支付宝异步通知 + RSA2 验签证明。
 */
public interface AlipayGatewayClient {

    /**
     * 创建支付：返回支付凭证。
     * 沙箱返回支付宝网关跳转 URL；Mock 返回本系统的演示支付页 URL。
     */
    PayCreateResult createPay(String payNo, String orderNo, String subject, BigDecimal amount);

    /** 关闭支付（订单超时取消时调用，避免用户再对已取消订单付款） */
    void closePay(String outTradeNo);

    /**
     * 主动查单：向渠道确认这笔支付单在渠道侧的真实状态。
     *
     * <p>用途是"通知为主、查单为辅"里的辅：异步通知要求回调地址公网可达，
     * 本机开发收不到、生产也可能延迟丢包，靠它才能确认钱到底到没到。
     *
     * @param outTradeNo 本系统支付单号（渠道侧 out_trade_no）
     * @return 查单结果；{@link PayQueryResult#paid()} 为 true 才可入账
     * @throws com.mall.common.BizException 渠道调用本身失败（网络/配置/签名错误）
     */
    PayQueryResult queryPay(String outTradeNo);

    /**
     * 渠道退款。
     * outRequestNo 为幂等标识：同单重试传相同值，渠道只退一次。
     *
     * @return 渠道退款流水号
     * @throws com.mall.common.BizException 渠道返回失败时抛出（含支付宝错误码/描述）
     */
    String refundPay(String outTradeNo, String outRequestNo, BigDecimal amount, String reason);

    /** 渠道标识（0 支付宝沙箱），写入 pay_info.channel */
    int channelCode();

    /** 是否真实渠道：前端据此决定"跳转支付宝"还是"显示演示按钮" */
    boolean isRealChannel();
}
