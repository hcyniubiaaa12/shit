<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelOrder, getMyOrders, receiveOrder, STATUS_TEXT, type OrderVO } from '@/api/order'
import { requestRefund } from '@/api/pay'
import CountDown from '@/components/CountDown.vue'

const TABS = [
  { key: 'all', label: '全部', value: undefined as number | undefined },
  { key: '0', label: '待支付', value: 0 },
  { key: '1', label: '已支付', value: 1 },
  { key: '2', label: '已发货', value: 2 },
  { key: '3', label: '已收货', value: 3 },
  { key: '4', label: '已取消', value: 4 },
]

const orders = ref<OrderVO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const status = ref<number | undefined>(undefined)
/** 搜索关键字：订单号 / 商品名 */
const keyword = ref('')
/** 下单时间排序：desc 从新到旧（默认）/ asc 从旧到新 */
const sort = ref<'asc' | 'desc'>('desc')
/** el-tabs 选中项（与 status 对应，string 类型便于绑定） */
const activeKey = ref('all')
const loading = ref(false)
/** 各状态数量（顶部统计栏；undefined 为全部） */
const counts = ref<Record<number, number>>({})

/** 规格 JSON → 可读文本 */
function formatSpec(specJson?: string): string {
  if (!specJson) return '-'
  try {
    const spec: Record<string, string> = JSON.parse(specJson)
    const parts = Object.entries(spec).map(([k, v]) => `${k}：${v}`)
    return parts.length ? parts.join('；') : '-'
  } catch {
    return specJson
  }
}

async function load() {
  loading.value = true
  try {
    const res = await getMyOrders({
      status: status.value,
      keyword: keyword.value.trim() || undefined,
      sort: sort.value,
      page: page.value,
      size: pageSize.value,
    })
    orders.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

/** 顶部统计栏：并行取各状态数量（统计全部订单，不受搜索条件影响） */
async function loadCounts() {
  try {
    const entries = await Promise.all(
      TABS.map(async (t) => {
        const res = await getMyOrders({ status: t.value, page: 1, size: 1 })
        return [t.value ?? -1, res.total] as [number, number]
      }),
    )
    counts.value = Object.fromEntries(entries)
  } catch {
    /* 统计失败不影响列表 */
  }
}

/** 搜索（订单号/商品名）：回到第 1 页重查 */
function onSearch() {
  page.value = 1
  load()
}

/** 切换排序：回到第 1 页重查 */
function onSortChange() {
  page.value = 1
  load()
}

/** 切换每页条数：回到第 1 页重查 */
function onSizeChange(size: number) {
  pageSize.value = size
  page.value = 1
  load()
}

function pickTab(key: string) {
  activeKey.value = key
  status.value = key === 'all' ? undefined : Number(key)
  page.value = 1
  load()
}

/** el-tabs 切换回调 */
function onTabChange(name: string | number) {
  pickTab(String(name))
}

async function cancel(order: OrderVO) {
  await ElMessageBox.confirm(`取消订单 ${order.orderNo}？`, '提醒', { type: 'warning' })
  await cancelOrder(order.orderNo)
  ElMessage.success('订单已取消（库存已回补，支付单已关闭）')
  load()
}

async function receive(order: OrderVO) {
  await ElMessageBox.confirm(`确认收货：${order.orderNo}？`, '确认', { type: 'success' })
  await receiveOrder(order.orderNo)
  ElMessage.success('确认收货成功')
  load()
}

async function refund(order: OrderVO) {
  const { value } = await ElMessageBox.prompt('请输入退款原因', '申请退款', { inputValue: '' })
  await requestRefund(order.orderNo, value ?? '用户申请')
  ElMessage.success('退款成功（演示渠道即时到账），订单已退款')
  load()
}

/** 待支付倒计时到 0：重查列表（真实取消由后端 MQ 收口；若 MQ 尚未消费则该行不再显示倒计时） */
function onExpired() {
  setTimeout(load, 2000)
}

onMounted(() => {
  load()
  loadCounts()
})
</script>

<template>
  <div class="orders">
    <header class="head">
      <router-link class="brand" to="/"><span class="brand-logo">MX</span><span class="brand-sub">我的订单</span></router-link>
      <router-link class="nav-link" to="/">← 返回首页</router-link>
      <router-link class="nav-link" to="/cart">购物车</router-link>
    </header>

    <section class="filter">
      <el-input
        v-model="keyword"
        class="filter-search"
        placeholder="搜索订单号 / 商品名"
        clearable
        @keyup.enter="onSearch"
        @clear="onSearch"
      >
        <template #append>
          <el-button @click="onSearch">搜索</el-button>
        </template>
      </el-input>
      <el-select v-model="sort" class="filter-sort" @change="onSortChange">
        <el-option label="下单时间：从新到旧" value="desc" />
        <el-option label="下单时间：从旧到新" value="asc" />
      </el-select>
    </section>

    <section class="stats">
      <div
        v-for="t in TABS"
        :key="t.key"
        class="stat"
        :class="{ active: activeKey === t.key }"
        @click="pickTab(t.key)"
      >
        <span class="stat-num md-num">{{ t.value === undefined ? total : counts[t.value] ?? 0 }}</span>
        <span class="stat-label">{{ t.label }}</span>
      </div>
    </section>

    <el-tabs v-model="activeKey" class="tabs" @tab-change="onTabChange">
      <el-tab-pane v-for="t in TABS" :key="t.key" :name="t.key" :label="t.label" />
    </el-tabs>

    <el-skeleton v-if="loading" :rows="6" animated />
    <template v-else>
      <article v-for="o in orders" :key="o.orderNo" class="order">
        <header class="order-head">
          <span class="order-no md-num">订单 {{ o.orderNo }}</span>
          <span class="order-status-text">{{ STATUS_TEXT[o.status] ?? `状态 ${o.status}` }}</span>
          <span
            v-if="o.status === 0 && Number(o.remainSeconds) > 0"
            class="order-cd"
          >支付剩余 <CountDown :seconds="Number(o.remainSeconds)" @expired="onExpired" /></span>
          <span class="order-time md-num">下单 {{ o.createTime?.replace('T', ' ').slice(0, 16) }}</span>
        </header>
        <section class="order-body">
          <div class="order-goods">
            <p v-for="(it, i) in o.items" :key="i" class="item">
              <span class="item-img">
                <img v-if="it.mainImg" :src="it.mainImg" :alt="it.title" />
                <span v-else class="item-img-fallback">无图</span>
              </span>
              <span class="item-title">{{ it.title }}</span>
              <span class="item-spec">{{ formatSpec(it.specJson) }}</span>
              <span class="md-num item-qty">×{{ it.count }}</span>
              <span class="md-num item-amount">¥ {{ it.amount.toFixed(2) }}</span>
            </p>
          </div>
          <aside class="order-side">
            <p class="side-row"><span class="side-label">金额</span><span class="md-num side-val">合计 ¥{{ o.totalAmount.toFixed(2) }} / 实付 ¥{{ o.payAmount.toFixed(2) }}</span></p>
            <p class="side-row"><span class="side-label">收货</span><span class="side-val">{{ o.receiverName }} · {{ o.receiverPhone.replace(/^(\d{3})\d+(\d{4})$/, '$1****$2') }}</span></p>
            <p class="side-row"><span class="side-label">地址</span><span class="side-val addr">{{ o.receiverAddress }}</span></p>
            <p v-if="o.trackingNo" class="side-row"><span class="side-label">物流</span><span class="side-val"><span class="md-num">{{ o.logisticsCompany }}</span> {{ o.trackingNo }}</span></p>
            <p v-if="o.sendTime" class="side-row"><span class="side-label">发货</span><span class="side-val md-num">{{ o.sendTime.replace('T', ' ').slice(0, 16) }}</span></p>
            <p v-if="o.receiveTime" class="side-row"><span class="side-label">签收</span><span class="side-val md-num">{{ o.receiveTime.replace('T', ' ').slice(0, 16) }}</span></p>
          </aside>
        </section>
        <footer class="order-foot">
          <span class="foot-total">共 {{ o.items.reduce((s, it) => s + it.count, 0) }} 件商品</span>
          <span class="foot-status">实付 <b class="md-num">¥ {{ o.payAmount.toFixed(2) }}</b></span>
          <div class="foot-actions">
            <el-button v-if="o.status === 0" size="small" type="primary" @click="$router.push(`/pay/${o.orderNo}`)">
              去支付
            </el-button>
            <el-button v-if="o.status === 0" size="small" @click="cancel(o)">取消订单</el-button>
            <el-button v-if="o.status === 1 || o.status === 2" size="small" type="warning" @click="refund(o)">
              申请退款
            </el-button>
            <el-button v-if="o.status === 2" size="small" type="success" @click="receive(o)">确认收货</el-button>
          </div>
        </footer>
      </article>

      <el-empty v-if="!orders.length" description="暂无订单" />
      <el-pagination
        v-if="total > 0"
        v-model:current-page="page"
        class="pager"
        layout="sizes, prev, pager, next, total"
        :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        @current-change="load"
        @size-change="onSizeChange"
      />
    </template>
  </div>
</template>

<style scoped>
.orders {
  max-width: 1440px;
  margin: 0 auto;
  padding: 24px 24px 64px;
}
.head {
  display: flex;
  align-items: center;
  gap: 18px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--mx-line);
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
}
.brand-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 8px;
  background: var(--mx-red);
  color: #fff;
  font-weight: 800;
  font-size: 17px;
}
.brand-sub {
  font-size: 15px;
  font-weight: 600;
  color: var(--mx-ink);
}
.nav-link {
  font-size: 14px;
  color: var(--mx-ink-2);
}
.nav-link:hover {
  color: var(--mx-red);
}

/* 搜索 + 排序 */
.filter {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}
.filter-search {
  max-width: 360px;
}
.filter-sort {
  width: 190px;
}

/* 顶部统计栏 */
.stats {
  display: flex;
  gap: 10px;
  margin: 16px 0;
  flex-wrap: wrap;
}
.stat {
  flex: 1;
  min-width: 120px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 14px 10px;
  border: 1px solid var(--mx-line);
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition: all 0.15s ease;
}
.stat:hover {
  border-color: var(--mx-red);
}
.stat.active {
  border-color: var(--mx-red);
  background: #fff7f7;
  box-shadow: 0 0 0 1px var(--mx-red);
}
.stat-num {
  font-size: 22px;
  font-weight: 700;
  color: var(--mx-red);
}
.stat-label {
  font-size: 12px;
  color: var(--mx-ink-2);
}

.tabs {
  margin-bottom: 8px;
}
.order {
  border: 1px solid var(--mx-line);
  border-radius: 10px;
  margin-bottom: 16px;
  overflow: hidden;
  background: #fff;
}
.order-head {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 18px;
  background: var(--mx-bg-2);
  font-size: 13px;
}
.order-no {
  font-weight: 600;
  color: var(--mx-ink);
}
.order-status-text {
  color: var(--mx-red);
  font-weight: 600;
}
.order-cd {
  font-size: 12px;
  color: var(--mx-ink-2);
  padding: 2px 8px;
  border: 1px solid var(--mx-line);
  border-radius: 10px;
  background: #fff;
}
.order-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--mx-ink-2);
}

/* 两栏：商品 + 信息 */
.order-body {
  display: grid;
  grid-template-columns: 1fr 260px;
  gap: 0;
}
.order-goods {
  padding: 8px 18px;
  border-right: 1px solid var(--mx-line);
}
.item {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 10px 0;
  font-size: 14px;
  color: var(--mx-ink);
}
.item-img {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 6px;
  overflow: hidden;
  background: var(--mx-bg-2);
  flex-shrink: 0;
}
.item-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.item-img-fallback {
  font-size: 11px;
  color: var(--mx-ink-2);
}
.item-title {
  min-width: 140px;
}
.item-spec {
  font-size: 12px;
  color: var(--mx-ink-2);
  min-width: 100px;
}
.item-qty {
  color: var(--mx-ink-2);
  min-width: 34px;
  text-align: right;
}
.item-amount {
  min-width: 90px;
  text-align: right;
  font-weight: 600;
  color: var(--mx-ink);
}
.order-side {
  padding: 10px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: #fffdfa;
}
.side-row {
  display: flex;
  gap: 8px;
  margin: 0;
  font-size: 13px;
}
.side-label {
  flex-shrink: 0;
  color: var(--mx-ink-2);
}
.side-val {
  color: var(--mx-ink);
}
.side-val.addr {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.order-foot {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 18px;
  border-top: 1px solid var(--mx-line);
  background: var(--mx-bg-2);
}
.foot-total {
  font-size: 12px;
  color: var(--mx-ink-2);
}
.foot-status {
  margin-left: auto;
  font-size: 13px;
  color: var(--mx-ink-2);
}
.foot-status b {
  color: var(--mx-red);
  font-size: 18px;
}
.foot-actions {
  display: flex;
  gap: 8px;
}
.pager {
  justify-content: center;
  margin-top: 10px;
}
@media (max-width: 860px) {
  .order-body {
    grid-template-columns: 1fr;
  }
  .order-goods {
    border-right: none;
  }
}
</style>
