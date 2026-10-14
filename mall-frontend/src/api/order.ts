import http from './http'

/** 订单接口：用户（/api/order）、商家（/api/merchant/orders）、管理员（/api/admin/orders） */
export interface OrderItem {
  skuId: number
  productId: number
  title: string
  specJson: string
  price: number
  count: number
  amount: number
  /** 商品主图（后端实时关联 product.main_img；商品已删除时为空） */
  mainImg?: string
}

export interface OrderVO {
  id: number
  orderNo: string
  merchantId: number
  totalAmount: number
  payAmount: number
  freak?: number
  status: number
  payTime?: string
  cancelTime?: string
  receiverName: string
  receiverPhone: string
  receiverAddress: string
  logisticsCompany?: string
  trackingNo?: string
  sendTime?: string
  receiveTime?: string
  createTime: string
  /** 待支付剩余秒数（仅 status=0 返回，服务端时间基准；0 表示已到期，取消由后端 MQ 收口）。后端 Long 全局序列化为字符串，实际可能是 "1590" */
  remainSeconds?: number | string
  items: OrderItem[]
}

interface Page<T> {
  records: T[]
  total: number
  current: number
  size: number
}

export interface CreateOrderPayload {
  reqId: string
  items: { skuId: number; count: number }[]
  receiverName: string
  receiverPhone: string
  receiverAddress: string
  fromCart?: boolean
}

export const STATUS_TEXT: Record<number, string> = {
  0: '待支付',
  1: '已支付',
  2: '已发货',
  3: '已收货',
  4: '已取消',
  5: '退款中',
  6: '已退款',
}

// 用户侧
export const createOrder = (payload: CreateOrderPayload) =>
  http.post<never, string[]>('/order/create', payload)
/** 我的订单查询：status 状态筛选、keyword 订单号/商品名搜索、sort 下单时间排序 */
export interface MyOrderQuery {
  status?: number
  keyword?: string
  /** 下单时间排序：desc 倒序（默认）/ asc 正序 */
  sort?: 'asc' | 'desc'
  page?: number
  size?: number
}

export const getMyOrders = (query: MyOrderQuery = {}) =>
  http.get<never, Page<OrderVO>>('/order/list', { params: query })
/** 订单详情（含待支付剩余秒数 remainSeconds） */
export const getOrderDetail = (orderNo: string) => http.get<never, OrderVO>(`/order/${orderNo}`)
export const cancelOrder = (orderNo: string) => http.put(`/order/${orderNo}/cancel`)
export const receiveOrder = (orderNo: string) => http.put(`/order/${orderNo}/receive`)

/** 商家/平台订单查询：status 状态筛选、keyword 订单号/收货人/商品名搜索 */
export interface AdminOrderQuery {
  status?: number
  keyword?: string
  page?: number
  size?: number
}

// 商家侧
export const getMerchantOrders = (query: AdminOrderQuery = {}) =>
  http.get<never, Page<OrderVO>>('/merchant/orders', { params: query })
export const shipOrder = (orderNo: string, logisticsCompany: string, trackingNo: string) =>
  http.put(`/merchant/orders/${orderNo}/ship`, { logisticsCompany, trackingNo })

// 管理员侧
export const getAdminOrders = (query: AdminOrderQuery = {}) =>
  http.get<never, Page<OrderVO>>('/admin/orders', { params: query })
export const adminShipOrder = (orderNo: string, logisticsCompany: string, trackingNo: string) =>
  http.put(`/admin/orders/${orderNo}/ship`, { logisticsCompany, trackingNo })
