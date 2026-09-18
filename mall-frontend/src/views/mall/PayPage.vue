<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createPay, getPayStatus, mockPayCallback, syncPay, type PayCreateResult } from '@/api/pay'
import { getMyOrders } from '@/api/order'

const route = useRoute()
const router = useRouter()
const orderNo = ref(String(route.params.orderNo ?? ''))
const voucher = ref<PayCreateResult | null>(null)
const loading = ref(true)
const paying = ref(false)
const paid = ref(false)
const failed = ref(false)
/** 订单在下单 30 分钟内未支付被取消（含用户手动取消）——此时渠道交易会被关闭或原路退回 */
const cancelled = ref(false)
let timer: ReturnType<typeof setInterval> | undefined
/** 是否真实支付宝沙箱渠道（决定展示"去支付宝付款"还是"模拟支付"按钮） */
const realChannel = computed(() => voucher.value?.realChannel === true)

async function load() {
  loading.value = true
  try {
    // 每次进入支付页都重新取一次渠道凭证：支付宝跳转 URL 可重复使用，Mock 幂等
    voucher.value = await createPay(orderNo.value)
    loading.value = false
    // 真实渠道：用户去支付宝付款，这里同时开始轮询，付款成功后自动收口
    if (realChannel.value) {
      pollStatus()
    }
  } catch {
    ElMessage.error('创建支付单失败')
    loading.value = false
  }
}

/** 跳转支付宝收银台（新窗口打开，原页面保留用于轮询支付结果） */
function gotoAlipay() {
  const url = voucher.value?.payUrl
  if (!url) {
    ElMessage.error('未获取到支付宝支付地址')
    return
  }
  paying.value = true
  window.open(url, '_blank', 'noopener')
}

/** 演示渠道：模拟渠道回调（成功/失败），随后轮询支付状态 */
async function mockCallback(success: boolean) {
  await mockPayCallback(voucher.value?.payNo ?? '', 'MOCK-TRADE-' + Date.now(), success ? await getAmount() : 1)
  paid.value = success
  failed.value = !success
  if (success) {
    await pollStatus()
  }
  paying.value = false
}

/** 真实应支付金额（订单快照），金额核对非空即必须与支付单一致 */
async function getAmount() {
  try {
    const res = await getMyOrders({ page: 1, size: 50 })
    const o = res.records.find((r) => r.orderNo === orderNo.value)
    if (o) return Number(o.payAmount)
  } catch {
    /* 查询失败走演示金额 1（将被后端拒收，符合预期） */
  }
  return 1
}

/**
 * 轮询支付状态：优先用 `syncPay`（让后端主动向支付宝查单并补偿入账）。
 *
 * 只查本地状态的 `getPayStatus` 在没有公网回调地址时永远不会变 —— 支付宝的异步通知
 * 到不了 localhost，所以这里必须走"通知为主、查单为辅"里的查单。
 * 后端对同一支付单有节流（数秒一次），1s 轮询不会打爆渠道。
 */
async function pollStatus() {
  clearInterval(timer)
  let ticks = 0
  timer = setInterval(async () => {
    ticks += 1
    try {
      const res = await syncPay(orderNo.value)
      if (res.paid) {
        clearInterval(timer)
        paid.value = true
        ElMessage.success('支付成功，订单已进入商家发货流程')
        router.replace('/orders')
      } else if (res.payStatus === 3) {
        // 支付单已关闭（订单被取消）：若已付款则后端已原路退回，别让用户继续在收银台付款
        clearInterval(timer)
        cancelled.value = true
        ElMessage.warning(res.refunded ? '该订单已取消，付款已原路退回' : '该订单已取消，请勿继续付款')
      }
    } catch {
      /* 网络抖动/查单瞬时失败忽略，继续轮询 */
    }
    // 沙箱付款耗时不定，轮询 10 分钟；期间用户可手动刷新
    if (ticks >= 600) clearInterval(timer)
  }, 1000)
}

/** 手动刷新：先做一次查单补偿，再取一次本地状态（用户点"我已付款"时用） */
async function refreshStatus() {
  try {
    const res = await syncPay(orderNo.value)
    if (res.paid) {
      clearInterval(timer)
      paid.value = true
      ElMessage.success('支付成功，订单已进入商家发货流程')
      router.replace('/orders')
      return
    }
    if (res.payStatus === 3) {
      clearInterval(timer)
      cancelled.value = true
      ElMessage.warning(res.refunded ? '该订单已取消，付款已原路退回' : '该订单已取消，请勿继续付款')
      return
    }
  } catch {
    /* 落到下面的本地状态查询 */
  }
  paid.value = await getPayStatus(orderNo.value)
}

onMounted(load)
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="pay">
    <section class="panel">
      <p class="eyebrow md-num">MALL-X / 支付</p>
      <h1 class="title">支付中</h1>
      <p v-if="loading" class="loading">创建支付单……</p>
      <template v-else>
        <p class="md-num payno">支付单号 {{ voucher?.payNo }}</p>
        <p class="md-num orderno">订单 {{ orderNo }}</p>
        <p v-if="voucher?.amount" class="amount">应付 ¥{{ voucher?.amount }}</p>

        <!-- 真实支付宝沙箱渠道 -->
        <template v-if="realChannel">
          <p class="hint">
            已接入支付宝沙箱。<b>点击下方按钮在新窗口打开支付宝收银台</b>，
            用沙箱买家账号付款。付款完成后本页会自动向支付宝查单确认（异步通知需要公网
            <code>notify-base</code>，本机开发靠主动查单兜底），确认到账即自动跳转订单页。
          </p>
          <div class="actions">
            <el-button type="primary" size="large" :disabled="paid" @click="gotoAlipay">前往支付宝付款</el-button>
            <el-button size="large" :disabled="paid" @click="refreshStatus">我已付款，刷新状态</el-button>
            <router-link class="back" to="/orders">返回订单列表</router-link>
          </div>
          <p class="polling">正在自动检测支付结果……</p>
        </template>

        <!-- 本地演示渠道（Mock） -->
        <template v-else>
          <p class="hint">
            当前为演示渠道（Mock）：点击下方按钮模拟支付宝沙箱回调。
            配置真实沙箱密钥后将自动切换为支付宝收银台（见 docs/支付宝沙箱接入指南.md）。
          </p>
          <div class="actions">
            <el-button type="primary" size="large" :disabled="paid" @click="mockCallback(true)">模拟支付成功</el-button>
            <el-button size="large" :disabled="failed" @click="mockCallback(false)">模拟支付失败</el-button>
            <router-link class="back" to="/orders">返回订单列表</router-link>
          </div>
        </template>

        <el-alert v-if="paid" type="success" title="支付成功，已同步订单状态与商家入账" :closable="false" />
        <el-alert v-if="cancelled" type="warning" title="该订单已取消：渠道交易已关闭，若已付款则已原路退回" :closable="false" />
        <el-alert v-if="failed" type="error" title="支付失败（回调金额不一致被拒）" :closable="false" />
      </template>
    </section>
  </div>
</template>

<style scoped>
.pay {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--md-color-bg-tint);
}
.panel {
  width: 100%;
  max-width: 520px;
  background: #fff;
  border: 1px solid var(--md-color-line);
  border-radius: var(--md-radius);
  padding: 40px;
}
.eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.18em;
  color: var(--md-color-ink-sub);
}
.title {
  margin: 0 0 18px;
  font-family: var(--md-font-display);
  font-size: 26px;
  font-weight: 700;
}
.loading {
  color: var(--md-color-ink-sub);
}
.payno {
  font-weight: 600;
  color: var(--md-color-primary);
}
.orderno {
  color: var(--md-color-ink-sub);
  font-size: 13px;
  margin: 4px 0 12px;
}
.amount {
  font-family: var(--md-font-display);
  font-size: 30px;
  font-weight: 700;
  margin: 8px 0 16px;
}
.hint {
  font-size: 13px;
  color: var(--md-color-ink-sub);
  line-height: 1.7;
}
.actions {
  display: flex;
  gap: 12px;
  margin: 18px 0;
  flex-wrap: wrap;
}
.back {
  display: inline-block;
  font-size: 13px;
  color: var(--md-color-ink-sub);
  align-self: center;
}
.polling {
  font-size: 12px;
  color: var(--md-color-ink-sub);
  margin: 0 0 12px;
}
</style>
