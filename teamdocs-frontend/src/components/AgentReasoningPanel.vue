<template>
  <section v-if="hasReasoning" class="agent-reasoning" aria-label="助手思考过程">
    <button
      type="button"
      class="reasoning-toggle"
      :aria-expanded="expanded"
      :aria-controls="contentId"
      :aria-describedby="`${contentId}-note`"
      :title="durationNote"
      @click="toggleExpanded"
    >
      <span :class="['reasoning-indicator', { live: active }]" aria-hidden="true"></span>
      <span class="reasoning-title">{{ title }}</span>
      <ChevronDown :size="14" :class="['reasoning-arrow', { expanded }]" aria-hidden="true" />
    </button>
    <span :id="`${contentId}-note`" class="reasoning-sr-only">{{ durationNote }}</span>
    <p v-if="message.reasoningTruncated" class="reasoning-truncated">思考内容已截断，仅展示已保留的部分。</p>
    <div :id="contentId" ref="bodyRef" :hidden="!expanded" class="reasoning-body">
      <p class="reasoning-content"><span>{{ displayedContent }}</span><span v-if="active" class="reasoning-cursor" aria-hidden="true"></span></p>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch, onUnmounted } from 'vue'
import { ChevronDown } from 'lucide-vue-next'

const props = defineProps({
  message: { type: Object, required: true },
  runKey: { type: String, required: true }
})
const durationNote = '耗时为接收思考输出的可观测耗时，并非模型内部计算耗时。思考内容仅保留在当前页面，刷新或切换会话后清空。'

const hasReasoning = computed(() => !props.message.masked && typeof props.message.reasoningContent === 'string' && !!props.message.reasoningContent.trim())
const active = computed(() => ['QUEUED', 'RUNNING'].includes(props.message.runStatus))
const contentId = computed(() => `agent-reasoning-${encodeURIComponent(props.runKey)}`)

const expanded = ref(false)
const bodyRef = ref(null)
let foldedAtEnd = false

const title = computed(() => {
  if (active.value) return '正在深度思考'

  if (props.message.runStatus === 'CANCELLED') return '思考已停止'

  if (props.message.runStatus && props.message.runStatus !== 'SUCCEEDED') return '思考已中断'

  const duration = props.message.reasoningDurationMs

  if (typeof duration !== 'number' || !Number.isFinite(duration) || duration < 0) return '已深度思考'

  return `已深度思考（耗时 ${duration < 100 ? '少于 0.1' : (duration / 1000).toFixed(1)} 秒）`
})

// 每轮只在首次结束时折叠，快照更新不覆盖手动选择。
watch([() => props.runKey, active], ([key, running], previous) => {
  if (!previous || key !== previous[0]) {
    expanded.value = running
    foldedAtEnd = !running
  } else if (!running && !foldedAtEnd) {
    expanded.value = false
    foldedAtEnd = true
  }
}, { immediate: true })

/** 切换当前回答的思考内容。 */
function toggleExpanded() {
  expanded.value = !expanded.value
}

// 连续时间自适应插值算法：帧率无关、平滑追赶、彻底消除速度台阶式顿挫
const targetContent = computed(() => props.message.reasoningContent || '')

const displayedContent = ref('')
let floatPos = 0
let lastTime = 0
let rafId = null

function canSmoothAnimate() {
  if (typeof window === 'undefined' || typeof window.requestAnimationFrame !== 'function') return false

  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false

  return active.value
}

function updateDisplayed(immediate = false) {
  const target = targetContent.value

  if (immediate || !canSmoothAnimate()) {
    if (rafId !== null) {
      cancelAnimationFrame(rafId)
      rafId = null
    }

    displayedContent.value = target
    floatPos = target.length
    lastTime = 0

    return
  }

  if (rafId !== null) return

  function step(now) {
    rafId = null

    if (!lastTime) lastTime = now

    const dt = Math.min(Math.max(now - lastTime, 1), 100)

    lastTime = now

    const dest = targetContent.value
    const current = displayedContent.value

    if (!canSmoothAnimate() || !dest.startsWith(current)) {
      displayedContent.value = dest
      floatPos = dest.length
      lastTime = 0

      return
    }

    const diff = dest.length - floatPos

    if (diff <= 0) {
      lastTime = 0

      return
    }

    // 速率平滑衰减模型：动态追踪剩余字符，保证无论网络按何种节奏吐字，字符均如流水般匀速平滑吐出
    const rate = Math.max(0.035, diff / 80)

    floatPos = Math.min(dest.length, floatPos + rate * dt)

    const nextLen = Math.floor(floatPos)

    if (nextLen > current.length) {
      displayedContent.value = dest.slice(0, nextLen)

      if (bodyRef.value && active.value) {
        bodyRef.value.scrollTop = bodyRef.value.scrollHeight
      }
    }

    if (floatPos < dest.length) {
      rafId = requestAnimationFrame(step)
    } else {
      lastTime = 0
    }
  }

  rafId = requestAnimationFrame(step)
}

watch(targetContent, () => {
  updateDisplayed(false)
}, { immediate: true })

watch(active, (running) => {
  if (!running) {
    updateDisplayed(true)
  }
})

onUnmounted(() => {
  if (rafId !== null && typeof cancelAnimationFrame === 'function') {
    cancelAnimationFrame(rafId)
    rafId = null
  }
})
</script>

<style scoped>
.agent-reasoning {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  margin: 2px 0 12px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-panel-soft);
  color: var(--app-text-2);
}

.reasoning-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 44px;
  padding: 10px 12px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: .8125rem;
  text-align: left;
  cursor: pointer;
}

.reasoning-toggle:hover { background: var(--app-hover); }

.reasoning-toggle:focus-visible { outline: 2px solid var(--app-accent); outline-offset: 2px; }

.reasoning-title { flex: 1; min-width: 0; overflow-wrap: anywhere; }

.reasoning-indicator { flex-shrink: 0; width: 6px; height: 6px; border-radius: 50%; background: var(--app-text-muted); }

.reasoning-indicator.live { background: var(--app-accent); animation: reasoning-breathe 1.6s ease-in-out infinite; }

.reasoning-arrow { flex-shrink: 0; transition: transform .15s ease; }

.reasoning-arrow.expanded { transform: rotate(180deg); }

.reasoning-body {
  padding: 0 12px 12px;
  max-height: 380px;
  overflow-y: auto;
}

.reasoning-body[hidden] { display: none; }

.reasoning-content {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: .8125rem;
  line-height: 1.4;
  letter-spacing: 0.01em;
}

.reasoning-cursor { display: inline-block; width: 2px; height: 1em; margin-left: 3px; vertical-align: -.1em; background: var(--app-accent); animation: reasoning-breathe 1.6s ease-in-out infinite; }

.reasoning-truncated { margin: 0; padding: 0 12px 10px; font-size: .75rem; line-height: 1.5; color: var(--app-text-muted); }

.reasoning-sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }

@keyframes reasoning-breathe { 50% { opacity: .35; } }

@media (prefers-reduced-motion: reduce) {
  .reasoning-indicator.live, .reasoning-cursor { animation: none; }

  .reasoning-arrow { transition: none; }
}
</style>
