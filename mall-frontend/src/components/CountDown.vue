<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 倒计时展示组件（纯展示，不发起任何取消动作）。
 *
 * props.seconds 为服务端算好的剩余秒数（服务端时间基准），挂载时取一次本地
 * 截止时刻，之后每秒用真实时钟重算剩余 —— 相比"计数器递减"天然免疫 setInterval
 * 计时漂移；后台标签页 timer 被浏览器节流，回到前台由 visibilitychange 立即
 * 重算，不会出现"挂后台回来还剩很多"的假象。
 *
 * 到 0 触发一次 expired 事件。真实取消以后端 MQ 消费端状态机为准，
 * 父组件应在 expired 后重查订单/支付状态收口 UI。
 * 注意：请以最终值挂载（父侧用 v-if 等数据就绪后再渲染），不支持中途改秒数。
 */
const props = defineProps<{ seconds: number }>()
const emit = defineEmits<{ (e: 'expired'): void }>()

/** 截止时刻（本地毫秒时间戳）：剩余秒数只在挂载时校准一次 */
const deadline = Date.now() + props.seconds * 1000
const left = ref(props.seconds)
let timer: ReturnType<typeof setInterval> | undefined
let fired = false

function tick() {
  left.value = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
  if (left.value <= 0) {
    stop()
    if (!fired) {
      fired = true
      emit('expired')
    }
  }
}

function stop() {
  if (timer) {
    clearInterval(timer)
    timer = undefined
  }
}

function onVisible() {
  if (document.visibilityState === 'visible') tick()
}

onMounted(() => {
  tick()
  timer = setInterval(tick, 1000)
  document.addEventListener('visibilitychange', onVisible)
})
onBeforeUnmount(() => {
  stop()
  document.removeEventListener('visibilitychange', onVisible)
})

const text = computed(() => {
  const m = Math.floor(left.value / 60)
  const s = left.value % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
})
</script>

<template>
  <span class="cd md-num" :class="{ urgent: left <= 300 }">{{ text }}</span>
</template>

<style scoped>
.cd {
  font-weight: 600;
  color: inherit;
}
.cd.urgent {
  color: #e0342c;
}
</style>
