<template>
  <ElPopover
    :visible="open"
    :virtual-ref="anchor"
    virtual-triggering
    trigger="click"
    placement="top-start"
    :width="360"
    :persistent="false"
    :popper-style="{ maxWidth: 'calc(100vw - 32px)' }"
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
            <small>{{ source.imageSource ? (source.imageLabel || '图像描述（模型生成）') : (citationLocation(source) || '文档摘录') }}</small>
            <blockquote>{{ source.excerpt }}</blockquote>
            <template v-if="source.imageSource">
              <p class="evidence-image-note">以上为模型转译的图像描述，不是原文字符坐标；请结合原图核对。</p>
              <button type="button" class="evidence-open" :disabled="imageLoading[source.id]" :aria-busy="!!imageLoading[source.id]" @click="showImage(source)">
                {{ imageLoading[source.id] ? '正在核验原图…' : '查看原图' }}
              </button>
              <p v-if="imageErrors[source.id]" class="evidence-image-error" role="alert">{{ imageErrors[source.id] }}</p>
              <img v-if="imageUrls[source.id]" class="evidence-image" :src="imageUrls[source.id]" :alt="`${source.documentName || '来源文档'}原图`" @error="onImageError(source.id, $event)" />
            </template>
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
import { previewChunkImageApi } from '@/api/document'
import { accessLost } from '@/utils/agentErrors'

const props = defineProps({
  open: Boolean,
  spaceId: { type: [String, Number], default: null },
  loading: Boolean,
  anchor: { type: Object, default: null },
  number: { type: Number, default: 1 },
  sources: { type: Array, default: () => [] }
})
const emit = defineEmits(['close', 'preview', 'invalid'])
const panel = ref(null)
const imageLoading = ref({})
const imageErrors = ref({})
const imageUrls = ref({})
const imageRequests = new Map()
let disposed = false

/** 取消单张原图请求并释放地址。 */
function clearImage(id) {
  imageRequests.get(id)?.abort()
  imageRequests.delete(id)

  if (imageUrls.value[id]) URL.revokeObjectURL(imageUrls.value[id])

  delete imageUrls.value[id]
  delete imageLoading.value[id]
  delete imageErrors.value[id]
}

/** 关闭或切换来源时清空全部图片。 */
function clearImages() {
  for (const id of new Set([...imageRequests.keys(), ...Object.keys(imageUrls.value)])) clearImage(id)

  imageLoading.value = {}
  imageErrors.value = {}
  imageUrls.value = {}
}

/** 每次点击均重新鉴权，只展示后端返回的图片。 */
async function showImage(source) {
  if (disposed || !props.open || props.loading || !source.imageSource || !props.sources.includes(source)) return

  const id = source.id

  clearImage(id)

  const controller = new AbortController()

  imageRequests.set(id, controller)
  imageLoading.value[id] = true

  const current = () => !disposed && props.open && !props.loading && !controller.signal.aborted && imageRequests.get(id) === controller

  try {
    const blob = await previewChunkImageApi(props.spaceId, source.documentId, source.chunkId, source.parseVersion, controller.signal)

    if (!current()) return

    imageUrls.value[id] = URL.createObjectURL(blob)
  } catch (cause) {
    if (!current() || cause.name === 'AbortError' || cause.code === 'ERR_CANCELED') return

    const invalid = accessLost(cause) || cause.code === 'SOURCE_CHANGED' || [403, 404, 409, 410].includes(cause.response?.status)

    if (invalid) clearImages()

    imageErrors.value[id] = invalid ? '原图已更新或不可访问，请重新核验来源。'
      : `原图加载失败，请重试。${cause.message ? `（${cause.message}）` : ''}`

    if (invalid) emit('invalid', cause)
  } finally {
    if (imageRequests.get(id) === controller) {
      imageRequests.delete(id)
      delete imageLoading.value[id]
    }
  }
}

/** 解码失败时释放当前图片，忽略旧节点的事件。 */
function onImageError(id, event) {
  if (!imageUrls.value[id] || event.target?.getAttribute('src') !== imageUrls.value[id]) return

  clearImage(id)
  imageErrors.value[id] = '原图无法显示，请重试。'
}

watch([() => props.open, () => props.loading, () => props.spaceId, () => props.sources], clearImages, { deep: true, flush: 'sync' })

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

  if (!disposed && props.open) panel.value?.focus({ preventScroll: true })
}, { immediate: true })

onMounted(() => {
  document.addEventListener('pointerdown', onPointerDown, true)
  document.addEventListener('keydown', onKeyDown, true)
})

onBeforeUnmount(() => {
  disposed = true
  clearImages()
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

.evidence-open:disabled { cursor: wait; opacity: .65; }

.evidence-image { display: block; max-width: 100%; height: auto; margin-top: 10px; border-radius: 4px; }

.evidence-image-note, .evidence-image-error { margin: 8px 0 0; font-size: .75rem; line-height: 1.6; overflow-wrap: anywhere; }

.evidence-image-note { color: var(--app-text-muted, #64748b); }

.evidence-image-error { color: var(--el-color-danger, #b91c1c); }

button:focus-visible { outline: 2px solid var(--app-accent, #4f46e5); outline-offset: 2px; }
</style>
