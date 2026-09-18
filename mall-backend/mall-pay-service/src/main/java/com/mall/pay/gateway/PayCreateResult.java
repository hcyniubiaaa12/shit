package com.mall.pay.gateway;

/**
 * 创建支付的结果。
 *
 * @param payNo    本系统支付单号
 * @param orderNo  业务订单号
 * @param amount   支付金额（元，两位小数）
 * @param payUrl   支付跳转地址：沙箱=支付宝网关 URL；Mock=本系统演示支付页
 * @param realChannel true=真实沙箱渠道（前端应跳转 payUrl 由支付宝收银台收款）
 *                    false=演示渠道（前端展示"模拟支付成功/失败"按钮）
 */
public record PayCreateResult(String payNo,
                              String orderNo,
                              String amount,
                              String payUrl,
                              boolean realChannel) {
}
