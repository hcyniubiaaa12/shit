package com.mall.pay.gateway;

import java.math.BigDecimal;

/**
 * 渠道主动查单结果（{@link AlipayGatewayClient#queryPay}）。
 *
 * <p>为什么需要主动查单：异步通知要求 {@code notify_url} 公网可达，本机开发（localhost）
 * 收不到；生产环境也可能因抖动/丢包让通知延迟甚至丢失。支付系统的通行做法是
 * <b>"通知为主、查单为辅"</b>——通知负责实时入账，查单负责把"钱已到、系统还不知道"的空窗补上。
 *
 * @param outTradeNo  本系统支付单号（渠道侧 {@code out_trade_no}）
 * @param tradeNo     渠道交易流水号（未支付时为 null）
 * @param tradeStatus 渠道交易状态：支付宝为 {@code WAIT_BUYER_PAY} / {@code TRADE_SUCCESS} /
 *                    {@code TRADE_FINISHED} / {@code TRADE_CLOSED}
 * @param totalAmount 渠道实付金额（未支付时为 null）—— 入账前必须与支付单金额核对
 * @param exists      渠道侧是否存在该笔交易（false = 用户还没在收银台付钱）
 */
public record PayQueryResult(String outTradeNo,
                             String tradeNo,
                             String tradeStatus,
                             BigDecimal totalAmount,
                             boolean exists) {

    private static final String TRADE_SUCCESS = "TRADE_SUCCESS";
    private static final String TRADE_FINISHED = "TRADE_FINISHED";

    /** 渠道确认已支付（只有这两个成功态代表钱真的到账） */
    public boolean paid() {
        return exists
                && (TRADE_SUCCESS.equals(tradeStatus) || TRADE_FINISHED.equals(tradeStatus))
                && totalAmount != null;
    }

    /** 未支付 / 交易不存在 */
    public static PayQueryResult notPaid(String outTradeNo, String tradeStatus) {
        return new PayQueryResult(outTradeNo, null, tradeStatus, null, false);
    }
}
