<template>
  <div v-if="progress.visible" class="agent-activity">
    <details
      v-if="progress.hasDetails"
      :open="expanded"
      aria-label="处理过程"
      @toggle="expanded = $event.target.open"
    >
      <summary class="activity-summary">
        <span :class="['agent-run-indicator', { live: progress.active }]" aria-hidden="true"></span>
        <span class="activity-label" role="status" aria-live="polite" aria-atomic="true">{{ progress.label }}</span>
        <ChevronDown :size="13" class="activity-arrow" aria-hidden="true" />
      </summary>

      <div class="activity-details-panel">
        <ol v-if="progress.steps.length" class="agent-tool-list" aria-label="处理步骤详情">
          <li v-for="step in progress.steps" :key="step.sequence">
            <Check v-if="step.state === 'succeeded'" :size="14" class="tool-status-icon is-ok" aria-hidden="true" />
            <LoaderCircle v-else-if="step.state === 'running'" :size="14" class="agent-progress-spin tool-status-icon is-running" aria-hidden="true" />
            <Square v-else-if="step.state === 'stopped'" :size="14" class="tool-status-icon" aria-hidden="true" />
            <AlertCircle v-else :size="14" class="tool-status-icon is-err" aria-hidden="true" />

            <span class="tool-desc">
              <span class="tool-action-name">{{ step.label }}</span>
            </span>
          </li>
        </ol>

        <p v-if="progress.waitingMessage" class="activity-waiting" role="status" aria-live="polite">
          <LoaderCircle :size="14" class="agent-progress-spin" aria-hidden="true" />
          <span>{{ progress.waitingMessage }}</span>
        </p>

        <details v-if="progress.steps.length" class="activity-technical">
          <summary>技术详情</summary>
          <p class="activity-count">工具调用 {{ run?.toolCalls ?? progress.steps.length }} 次</p>
          <ul class="activity-metrics" aria-label="工具技术详情">
            <li v-for="step in progress.steps" :key="step.sequence">
              <span>{{ step.label }}</span>
              <span v-if="step.state === 'succeeded' && step.resultCount !== null">{{ step.resultCount }} 条结果</span>
              <span>{{ durationLabel(step) }}</span>
            </li>
          </ul>
        </details>

        <p v-if="progress.emptyRead" class="agent-parse-note">本次未读到可用正文，可前往文件列表检查解析状态。</p>
        <p v-if="run?.errorCode" class="agent-run-reason">{{ agentError(run?.errorCode) }}</p>
      </div>
    </details>

    <div v-else class="activity-summary is-passive" role="status" aria-live="polite" aria-atomic="true">
      <span :class="['agent-run-indicator', { live: progress.active }]" aria-hidden="true"></span>
      <span class="activity-label">{{ progress.label }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { AlertCircle, Check, ChevronDown, LoaderCircle, Square } from 'lucide-vue-next'
import { getAgentProgress } from '@/utils/agentProgress'
import { agentError } from '@/utils/agentErrors'

const props = defineProps({ run: { type: Object, default: null } })

const progress = computed(() => getAgentProgress(props.run))

const expanded = ref(false)

// 运行期间默认展开，允许手动收起；结束或切换运行后重置。
watch([() => props.run?.id, () => progress.value.active], ([, active]) => {
  expanded.value = active
}, { immediate: true })

function durationLabel(step) {
  if (step.state === 'running') return '进行中'

  if (step.state === 'stopped') return '已停止'

  if (step.state !== 'succeeded') return '未完成'

  return `${Math.max(0.1, (step.durationMs || 0) / 1000).toFixed(1)} 秒`
}
</script>

<style scoped>
/* 工具调用折叠器 */
.agent-activity {
  margin-top: 10px;
}

.activity-summary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 5px;
  background: var(--app-panel-soft);
  border: 1px solid var(--app-border);
  font-size: .8125rem;
  color: var(--app-text-muted);
  cursor: pointer;
  list-style: none;
}

.activity-summary::-webkit-details-marker { display: none; }

.agent-run-indicator {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--app-text-muted);
}

.agent-run-indicator.live {
  background: #10b981;
  animation: agent-indicator-pulse 1.6s ease-in-out infinite;
}

@keyframes agent-indicator-pulse {
  50% { opacity: 0.35; }
}

.activity-label {
  font-weight: 500;
  color: var(--app-text-2);
}

.activity-arrow {
  transition: transform .15s ease;
}

details[open] > .activity-summary .activity-arrow {
  transform: rotate(180deg);
}

.activity-details-panel {
  margin-top: 6px;
  padding: 8px 12px;
  background: var(--app-panel-soft);
  border: 1px solid var(--app-border);
  border-radius: 6px;
}

.agent-tool-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.agent-tool-list li {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: .8125rem;
}

.tool-status-icon {
  flex-shrink: 0;
}

.tool-status-icon.is-ok { color: #10b981; }

.tool-status-icon.is-running { color: var(--app-accent); }

.tool-status-icon.is-err { color: #ef4444; }

.tool-action-name { color: var(--app-text-2); }

.agent-parse-note, .agent-run-reason {
  margin: 6px 0 0;
  font-size: .8125rem;
}

.agent-run-reason { color: #ef4444; }

.agent-parse-note { color: var(--app-text-muted); }

.activity-summary.is-passive { cursor: default; }

.activity-summary:focus-visible { outline: 2px solid var(--app-accent); outline-offset: 3px; }

.activity-count { margin: 0 0 8px; font-size: .75rem; color: var(--app-text-muted); }

.tool-desc { flex: 1; min-width: 0; display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }

.agent-progress-spin {
  display: inline-block;
  transform-box: fill-box;
  transform-origin: center;
  animation: agent-progress-spin 1s linear infinite;
}

@keyframes agent-progress-spin {
  0% { transform: rotate(0deg); }

  100% { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .agent-run-indicator.live { animation: none; }

  .activity-summary, .activity-arrow { transition: none; }
}

.activity-waiting {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 10px 0 0;
  color: var(--app-text-muted);
  font-size: .8125rem;
  line-height: 1.6;
}

.activity-waiting:first-child { margin-top: 0; }

.activity-waiting svg { flex-shrink: 0; margin-top: 3px; }

.activity-technical {
  margin-top: 12px;
  border-top: 1px solid var(--app-border);
  padding-top: 8px;
  color: var(--app-text-muted);
  font-size: .75rem;
}

.activity-technical > summary { cursor: pointer; padding: 4px 0; }

.activity-technical .activity-count { margin: 8px 0; }

.activity-metrics { display: grid; gap: 6px; list-style: none; margin: 0; padding: 0; }

.activity-metrics li { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; }

.activity-metrics li > span:first-child { flex: 1; min-width: 120px; }
</style>
