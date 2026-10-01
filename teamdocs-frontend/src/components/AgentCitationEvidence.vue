<template>
  <ElPopover
    :visible="open"
    :virtual-ref="anchor"
    virtual-triggering
    trigger="click"
    placement="top-start"
    :width="360"
    :persistent="false"
    :popper-style="{ maxWidth: 'calc(100vw - 24px)' }"
  >
    <div ref="panel" class="citation-evidence" role="dialog" aria-label="引用摘录" tabindex="-1">
      <header class="evidence-header">
        <strong>来源 [{{ number }}]</strong>
        <button type="button" class="evidence-close" aria-label="关闭引用摘录" @click="$emit('close')"><X :size="16" /></button>
      </header>

      <p v-if="loading" class="evidence-loading" role="status">正在核验来源…</p>
      <template v-else>
        <p class="evidence-document">{{ sources[0]?.documentName }}</p>
        <div class="evidence-excerpts">
          <section v-for="source in sources" :key="source.id" class="evidence-excerpt">
            <small>{{ citationLocation(source) || '文档摘录' }}</small>
            <blockquote>{{ source.excerpt }}</blockquote>
          </section>
        </div>
        <button v-if="sources.length" type="button" class="evidence-open" @click="$emit('preview', sources[0])">
          打开文档 <ArrowUpRight :size="14" />
        </button>
      </template>
    </div>
  </ElPopover>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElPopover } from 'element-plus'
import { ArrowUpRight, X } from 'lucide-vue-next'
import { citationLocation } from '@/utils/agentCitations'

const props = defineProps({
  open: Boolean,
  loading: Boolean,
  anchor: { type: Object, default: null },
  number: { type: Number, default: 1 },
  sources: { type: Array, default: () => [] }
})
const emit = defineEmits(['close', 'preview'])
const panel = ref(null)

function onPointerDown(event) {
  if (!props.open || props.anchor?.contains(event.target) || panel.value?.contains(event.target)) return
  emit('close')
}

function onKeyDown(event) {
  if (!props.open || event.key !== 'Escape') return
  event.preventDefault()
  event.stopPropagation()
  emit('close')
}

watch(() => props.open, async (open) => {
  if (!open) return
  await nextTick()
  panel.value?.focus({ preventScroll: true })
})

onMounted(() => {
  document.addEventListener('pointerdown', onPointerDown, true)
  document.addEventListener('keydown', onKeyDown, true)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onPointerDown, true)
  document.removeEventListener('keydown', onKeyDown, true)
})
</script>

<style scoped>
.citation-evidence { color: var(--app-text, #1f2937); outline: none; }
.evidence-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.evidence-header strong { font-size: .8125rem; }
.evidence-close { display: grid; place-items: center; width: 32px; height: 32px; border: 0; border-radius: 4px; background: transparent; color: inherit; cursor: pointer; }
.evidence-close:hover { background: var(--app-hover, #f1f5f9); }
.evidence-document { margin: 4px 0 12px; font-size: .8125rem; font-weight: 500; overflow-wrap: anywhere; }
.evidence-loading { margin: 12px 0; color: var(--app-text-muted, #64748b); }
.evidence-excerpts { max-height: min(320px, 50vh); overflow-y: auto; }
.evidence-excerpt + .evidence-excerpt { margin-top: 14px; }
.evidence-excerpt small { color: var(--app-text-muted, #64748b); }
.evidence-excerpt blockquote { margin: 5px 0 0; padding-left: 10px; border-left: 2px solid var(--app-border, #e2e8f0); font-size: .8125rem; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
.evidence-open { display: inline-flex; align-items: center; gap: 4px; min-height: 36px; margin-top: 12px; padding: 0; border: 0; background: transparent; color: var(--app-accent, #4f46e5); font: inherit; font-size: .8125rem; cursor: pointer; }
button:focus-visible { outline: 2px solid var(--app-accent, #4f46e5); outline-offset: 2px; }
</style>
