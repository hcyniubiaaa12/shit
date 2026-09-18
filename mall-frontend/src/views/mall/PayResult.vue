<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { syncPay } from '@/api/pay'

/**
 * 支付宝同步跳转落地页（mall.alipay.return-url 指向本路由）。
 *
 * 注意：同步跳转只代表"用户从支付宝回来了"，**不代表已入账**。
 * 真正入账以支付宝异步通知（/api/pay/alipay/notify，验签后）为准；
 * 本机开发没有公网回调地址，通知到不了，所以这里调 `syncPay` 让后端主动查单补偿，
 * 而不是直接根据 URL 参数判成功。
 */
const route = useRoute()
// 后端把 orderNo 挂在自己的 return_url 上带回来；只拿到 out_trade_no（=支付单号）时后端也认
const orderNo = ref(String(route.query.orderNo ?? route.query.out_trade_no ?? ''))
const status = ref<'checking' | 'paid' | 'waiting' | 'refunded' | 'cancelled'>('checking')
let timer: ReturnType<typeof setInterval> | undefined
let ticks = 0

async function check() {
  if (!orderNo.value) {
    status.value = 'waiting'
    return
  }
  try {
    const res = await syncPay(orderNo.value)
    if (res.paid) {
      status.value = 'paid'
      clearInterval(timer)
      return
    }
    if (res.refunded) {
      // 订单已取消但用户仍完成了付款：系统已原路退回，别再让用户干等
      status.value = 'refunded'
      clearInterval(timer)
      return
    }
    if (res.payStatus === 3) {
      // 支付单已关闭且渠道没收到钱：订单已取消，付款不可能再入账
      status.value = 'cancelled'
      clearInterval(timer)
      return
    }
  } catch {
    /* 忽略瞬时错误，继续轮询 */
  }
  status.value = 'waiting'
}

onMounted(() => {
  void check()
  timer = setInterval(() => {
    ticks += 1
    void check()
    if (ticks >= 30) clearInterval(timer)
  }, 2000)
})

onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="result">
    <section class="panel">
      <p class="eyebrow">MALL-X / 支付结果</p>
      <template v-if="status === 'paid'">
        <h1 class="title ok">支付成功</h1>
        <p class="desc">订单已进入商家发货流程，可前往订单列表查看。</p>
      </template>
      <template v-else-if="status === 'checking'">
        <h1 class="title">正在确认</h1>
        <p class="desc">正在向支付宝确认支付结果……</p>
      </template>
      <template v-else-if="status === 'refunded'">
        <h1 class="title">订单已取消，款项已退回</h1>
        <p class="desc">
          这笔订单已被取消，但你的付款仍然成功。
          系统已把款项<b>原路退回</b>沙箱买家账户（可在支付宝沙箱账单里看到），无需人工处理。
          系统同时已关闭该笔渠道交易，不会再次扣款。
        </p>
      </template>
      <template v-else-if="status === 'cancelled'">
        <h1 class="title">订单已取消</h1>
        <p class="desc">
          这笔订单已被取消，支付宝侧交易也已关闭，<b>没有收到这笔付款</b>。
          如需购买请重新下单。
        </p>
      </template>
      <template v-else>
        <h1 class="title">尚未确认到账</h1>
        <p class="desc">
          如果已完成付款，支付宝侧确认通常几秒内返回；本页会持续自动查单。
          可稍后刷新订单列表查看；若长时间未到账，请确认沙箱账号确实付款成功，
          或查看 pay-service 日志里的支付宝查单结果。
        </p>
      </template>
      <p v-if="orderNo" class="md-num order">订单号 {{ orderNo }}</p>
      <div class="actions">
        <router-link to="/orders"><el-button type="primary">查看我的订单</el-button></router-link>
        <router-link to="/"><el-button>返回首页</el-button></router-link>
      </div>
    </section>
  </div>
</template>

<style scoped>
.result {
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
  margin: 0 0 12px;
  font-family: var(--md-font-display);
  font-size: 26px;
  font-weight: 700;
}
.title.ok {
  color: var(--md-color-primary);
}
.desc {
  font-size: 13px;
  color: var(--md-color-ink-sub);
  line-height: 1.8;
}
.order {
  font-size: 13px;
  color: var(--md-color-ink-sub);
  margin: 12px 0 20px;
}
.actions {
  display: flex;
  gap: 12px;
}
</style>
