<template>
  <section class="index-panel">
    <div class="index-heading"><h3>索引管理</h3><el-button size="small" :disabled="busy" @click="load">刷新状态</el-button></div>
    <p v-if="error" role="alert">{{ error }}</p>
    <el-skeleton v-if="busy && !state" :rows="3" />
    <template v-if="state">
      <p>正文解析：{{ state.parseStatus }} · {{ state.chunks }} 个分块</p>
      <div class="index-row"><div><strong>Elasticsearch · {{ label(state.elasticsearch.status) }}</strong><p>{{ state.elasticsearch.detail }} · 已索引 {{ state.elasticsearch.indexedChunks }} 块</p></div>
        <el-button v-if="state.canRepair" size="small" :disabled="busy || state.parseStatus !== 'READY' || state.elasticsearch.status === 'DISABLED'" @click="repair('es')">重新同步</el-button>
      </div>
      <div class="index-row"><div><strong>Milvus · {{ label(state.vectorStatus) }}</strong><p>{{ state.vectorDetail }}</p></div>
        <el-button v-if="state.canRepair" size="small" :disabled="busy || state.parseStatus !== 'READY' || state.vectorStatus === 'DISABLED'" @click="repair('vector')">重试同步</el-button>
      </div>
      <p class="hint">ES 检查当前版本分块数量；Milvus 显示持久化同步任务状态。修复只针对当前文档，不清空其他文档或全局索引。</p>
      <p v-if="!state.canRepair" class="hint">只有空间所有者和管理员可以修复索引。</p>
    </template>
  </section>
</template>
<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { ElMessageBox } from 'element-plus'
import { indexStatus, repairIndex } from '@/api/documentTools'
const props = defineProps({ spaceId: [String, Number], documentId: [String, Number] })
const state = ref(null), busy = ref(false), error = ref('')
let controller
async function load() {
  controller?.abort(); const request = controller = new AbortController(); busy.value = true; error.value = ''
  try { const result = await indexStatus(props.spaceId, props.documentId, request.signal); if (!request.signal.aborted) state.value = result }
  catch (e) { if (!request.signal.aborted) error.value = e.message || '索引状态查询失败' }
  finally { if (!request.signal.aborted) busy.value = false }
}
async function repair(target) {
  if (busy.value) return
  const request = controller; busy.value = true
  try {
    await ElMessageBox.confirm(target === 'vector' ? '重新向量化会产生模型用量，确认重试当前文档？' : '确认重新同步当前文档的 ES 索引？', '索引修复', { confirmButtonText: '确认', cancelButtonText: '取消' })
    if (request.signal.aborted) return
    const result = await repairIndex(props.spaceId, props.documentId, target, request.signal)
    if (!request.signal.aborted) { state.value = result; error.value = '' }
  } catch (e) { if (!request.signal.aborted && e !== 'cancel' && e !== 'close') error.value = e.message || '修复失败，请重试' }
  finally { if (!request.signal.aborted) busy.value = false }
}
function label(value) { return ({SYNCED:'已同步',OUTDATED:'待更新',MISSING:'未建立',ERROR:'异常',DISABLED:'未启用',WAITING_PARSE:'等待解析',DONE:'任务完成',PENDING:'待处理',FAILED:'失败'})[value] || value }
watch(() => [props.spaceId, props.documentId], () => { state.value = null; load() }, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>
<style scoped>
.index-panel {
  padding: 20px;
}

.index-heading, .index-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.index-row {
  border-bottom: 1px solid var(--app-border);
  padding: 16px 0;
}

h3 {
  font-size: 16px;
}

p, strong {
  font-size: 13px;
  line-height: 1.6;
}

.hint {
  color: var(--app-text-muted);
}

@media (max-width: 520px) { .index-row { flex-wrap: wrap; } }
</style>
