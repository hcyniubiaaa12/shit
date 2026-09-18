import http from './http'

/** 支付接口（/api/pay；支付单创建、Mock 回调、状态查询、退款）+ 管理员退款记录 */
export interface RefundRecord {
  id: number
  refundNo: string
  payNo: string
  orderNo: string
  amount: number
  reason?: string
  status: number
  handleTime?: string
  createTime: string
}

/**
 * 创建支付单的返回凭证。
 * realChannel=false 时是本地演示渠道（Mock），前端展示"模拟支付"按钮；
 * realChannel=true 时是真实支付宝沙箱，前端应跳转 payUrl 到支付宝收银台。
 */
export interface PayCreateResult {
  payNo: string
  orderNo: string
  /** 金额字符串（两位小数），避免浮点误差 */
  amount: string | null
  /** 支付跳转地址：沙箱=支付宝网关；Mock=本系统演示页 */
  payUrl: string | null
  realChannel: boolean
}

export const createPay = (orderNo: string) =>
  http.post<never, PayCreateResult>('/pay/create', { orderNo })

/** 查询支付凭证（不重复下单） */
export const getPayInfo = (orderNo: string) => http.get<never, PayCreateResult | null>(`/pay/info/${orderNo}`)

/** 演示用：模拟渠道回调（真实沙箱由支付宝异步通知 /pay/alipay/notify 触发） */
export const mockPayCallback = (payNo: string, tradeNo: string, amount: number) =>
  http.post('/pay/mock/callback', { payNo, tradeNo, amount })

export const getPayStatus = (orderNo: string) => http.get<never, boolean>(`/pay/status/${orderNo}`)

/**
 * 主动查单补偿的返回。
 * paid=true：渠道确认已付款并已入账；refunded=true：订单已取消但用户仍付了款，系统已原路退回。
 */
export interface PaySyncResult {
  paid: boolean
  refunded: boolean
  payStatus: number | null
}

/**
 * 主动查单补偿：让后端向支付宝确认这笔支付单是否已支付（已支付则立即入账）。
 *
 * 异步通知要求 notify-base 是支付宝能访问到的公网地址，本机开发收不到通知，
 * 所以支付页/支付结果页轮询本接口，才能在本地跑通"付款 → 订单已支付"闭环。
 * 后端内部有节流（同一支付单数秒内只查一次渠道）+ 完整幂等，可安全高频调用。
 *
 * @param no 订单号或支付单号（支付宝同步跳转回来带的是支付单号）
 */
export const syncPay = (no: string) => http.post<never, PaySyncResult>(`/pay/sync/${no}`)

export const requestRefund = (orderNo: string, reason: string) =>
  http.post<never, string>('/pay/refund', { orderNo, reason })

export const getRefunds = (status?: number, page = 1, size = 10) =>
  http.get<never, { records: RefundRecord[]; total: number; current: number; size: number }>('/admin/refunds', {
    params: { status, page, size },
  })
