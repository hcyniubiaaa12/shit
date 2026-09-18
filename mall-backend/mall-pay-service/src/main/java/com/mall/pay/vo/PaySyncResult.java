package com.mall.pay.vo;

/**
 * 主动查单补偿的结果（{@code POST /api/pay/sync/{orderNo}}）。
 *
 * <p>为什么不只返回一个 boolean：查单会遇到三种结局，前端要能分别提示 ——
 * <ul>
 *   <li>{@code paid=true}：渠道确认已付款，已入账（订单进入待发货）</li>
 *   <li>{@code refunded=true}：本地支付单已关闭（订单被取消/超时）但用户仍付了钱，
 *       系统已把这笔"悬挂款"原路退回</li>
 *   <li>两者都 false：渠道侧还没付款（或查单被节流），继续等</li>
 * </ul>
 *
 * @param paid      是否已支付并入账
 * @param refunded  是否已把"订单已取消但用户付款"的悬挂款原路退回
 * @param payStatus 支付单本地状态：0 待支付 / 1 成功 / 2 失败 / 3 关闭
 */
public record PaySyncResult(boolean paid, boolean refunded, Integer payStatus) {

    public static PaySyncResult of(boolean paid, boolean refunded, Integer payStatus) {
        return new PaySyncResult(paid, refunded, payStatus);
    }

    /** 渠道侧还没付款：什么都不用做，继续轮询 */
    public static PaySyncResult pending(Integer payStatus) {
        return new PaySyncResult(false, false, payStatus);
    }
}
