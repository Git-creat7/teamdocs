<template>
  <div v-if="items.length || error" class="chat-attachments">
    <button v-for="item in items" :key="item.id" type="button" :disabled="busy" @click="download(item)">{{ item.name }}</button>
    <small v-if="error" role="alert">{{ error }}</small>
  </div>
</template>
<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { listChatAttachments, downloadChatAttachment } from '@/api/chatAttachments'
const props = defineProps({ spaceId: [String, Number], runId: [String, Number] })
const items = ref([]), error = ref(''), busy = ref(false)
let controller
watch(() => [props.spaceId, props.runId], async () => {
  controller?.abort(); const request = controller = new AbortController()
  items.value = []; error.value = ''; busy.value = false
  try { const rows = await listChatAttachments(props.spaceId, props.runId, request.signal); if (!request.signal.aborted) items.value = rows }
  catch (e) { if (!request.signal.aborted) error.value = e.message || '附件读取失败' }
}, { immediate: true })
async function download(item) {
  if (busy.value) return
  const request = controller; busy.value = true
  try {
    const blob = await downloadChatAttachment(props.spaceId, item.id, request.signal)
    if (request.signal.aborted) return
    const url = URL.createObjectURL(blob), link = document.createElement('a')
    link.href = url; link.download = item.name; link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) { if (!request.signal.aborted) error.value = e.message || '附件下载失败' }
  finally { if (!request.signal.aborted) busy.value = false }
}
onBeforeUnmount(() => controller?.abort())
</script>
<style scoped>
.chat-attachments { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
button { background: var(--app-hover); border: 1px solid var(--app-border); border-radius: 6px; padding: 6px 10px; cursor: pointer; color: var(--app-text); overflow-wrap: anywhere; }
small { color: var(--el-color-danger); }
</style>
