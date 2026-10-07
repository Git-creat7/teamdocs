<template>
  <div class="memory-settings" :aria-busy="loading || busy">
    <header class="memory-heading">
      <h2>用户记忆</h2>
      <p>记住你的长期偏好，在不同会话和空间中使用。仅你可以查看和管理。</p>
    </header>

    <el-skeleton v-if="loading && !loaded" :rows="3" animated />
    <div v-if="error" class="memory-error" role="alert">
      <span>{{ error }}</span>
      <el-button size="small" :disabled="pending" @click="memory.load">重新加载</el-button>
    </div>

    <template v-if="loaded">
      <div class="memory-toggle">
        <div>
          <label id="memory-enabled-label">启用记忆</label>
          <p id="memory-enabled-help">自动提取你明确表达的长期个人信息和回答偏好；关闭后停止读取和更新，但保留已存内容。</p>
        </div>
        <el-switch :model-value="state.enabled" :disabled="pending"
          aria-label="启用用户记忆" aria-labelledby="memory-enabled-label" aria-describedby="memory-enabled-help" @change="changeEnabled" />
      </div>

      <p class="memory-notice">开启后，你的相关发言和已有记忆会发送到管理员配置的 AI 模型。文档、检索结果和项目资料不用于生成全局记忆。请勿提交密码或敏感个人信息。</p>

      <div class="memory-toolbar">
        <h3>已保存的记忆 <span>{{ state.items.length }}</span></h3>
        <div class="memory-actions">
          <el-button size="small" :disabled="pending || !!editingKey" @click="memory.load">刷新</el-button>
          <el-button size="small" type="danger" plain :disabled="pending || !state.items.length" @click="clearAll">清空</el-button>
        </div>
      </div>

      <div v-if="!state.items.length" class="memory-empty">
        <p>还没有保存的记忆</p>
        <span>{{ state.enabled ? '例如在对话中说“以后回答简洁一点”。后台保存完成后，后续提问即可使用。' : '开启后，会从新的对话中识别长期信息，不会扫描旧聊天。' }}</span>
      </div>
      <ul v-else class="memory-list">
        <li v-for="item in state.items" :key="item.key" class="memory-item">
          <div class="memory-item-head">
            <h4>{{ labels[item.key] || '个人偏好' }}</h4>
            <div v-if="editingKey !== item.key" class="memory-actions">
              <el-button text size="small" :disabled="pending || !!editingKey" :aria-label="`修改${labels[item.key] || '记忆'}`" @click="startEdit(item)">修改</el-button>
              <el-button text size="small" type="danger" :disabled="pending || !!editingKey" :aria-label="`删除${labels[item.key] || '记忆'}`" @click="removeItem(item)">删除</el-button>
            </div>
          </div>
          <form v-if="editingKey === item.key" class="memory-edit" @submit.prevent="saveEdit">
            <el-input v-model="draft" :maxlength="160" show-word-limit :disabled="pending"
              :aria-label="`修改${labels[item.key] || '记忆'}`" />
            <div class="memory-actions">
              <el-button :disabled="pending" @click="editingKey = ''">取消</el-button>
              <el-button type="primary" native-type="submit" :loading="busy" :disabled="pending || !draft.trim()">保存</el-button>
            </div>
          </form>
          <p v-else class="memory-value">{{ item.value }}</p>
          <small>{{ item.sourceRunId ? '来自你的对话表达' : '由你手动修正' }} · {{ formatDate(item.updatedAt) }}</small>
        </li>
      </ul>
      <p class="memory-footnote">删除会话不会删除已保存的记忆。清空不会关闭功能；旧任务不会恢复已删除内容，但你以后重新表达的信息仍可能被记住。</p>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserMemory } from '@/composables/useUserMemory'

const memory = useUserMemory()
const { state, loaded, loading, busy, error } = memory
const editingKey = ref('')
const draft = ref('')
const confirming = ref(false)
const pending = computed(() => loading.value || busy.value || confirming.value)
const labels = {
  answer_language: '回答语言', answer_length: '回答详略', answer_format: '回答格式',
  explanation_depth: '讲解深度', code_language: '代码示例语言', preferred_name: '称呼',
  occupation: '职业', technical_background: '技术背景', learning_goal: '学习目标'
}

onMounted(memory.load)

async function confirm(message, title) {
  confirming.value = true
  try {
    await ElMessageBox.confirm(message, title, { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' })
    return true
  } catch { return false }
  finally { confirming.value = false }
}

async function changeEnabled(enabled) {
  if (pending.value) return
  if (enabled && !await confirm('开启后，相关发言与已有记忆会发送至当前配置的 AI 模型进行提取和使用。仅保存个人长期信息与偏好，是否开启？', '开启用户记忆')) return
  if (await memory.setEnabled(enabled)) ElMessage.success(enabled ? '记忆已开启' : '记忆已关闭，已存内容保留')
}

function startEdit(item) {
  editingKey.value = item.key
  draft.value = item.value
}

async function saveEdit() {
  if (pending.value || !draft.value.trim()) return
  if (await memory.edit(editingKey.value, draft.value.trim())) {
    editingKey.value = ''
    ElMessage.success('记忆已更新')
  }
}

async function removeItem(item) {
  if (pending.value || !await confirm('删除后，后续提问不再使用这条记忆，旧后台任务也不会恢复它。', '删除记忆')) return
  if (await memory.remove(item.key)) ElMessage.success('记忆已删除')
}

async function clearAll() {
  if (pending.value || !await confirm('清空全部已存记忆？这不会关闭功能，也不会删除聊天记录。', '清空记忆')) return
  if (await memory.clear()) {
    editingKey.value = ''
    ElMessage.success('记忆已清空')
  }
}

function formatDate(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN')
}
</script>

<style scoped>
.memory-settings {
  color: var(--app-text);
}

.memory-heading {
  margin-bottom: 24px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--app-border);
}

.memory-heading h2 {
  margin: 0 0 6px;
  font-size: 1.08rem;
}

.memory-heading p, .memory-toggle p, .memory-notice, .memory-footnote {
  color: var(--app-text-muted);
  font-size: 0.84rem;
  line-height: 1.65;
}

.memory-heading p {
  margin: 0;
}

.memory-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.memory-toggle label {
  font-size: 0.9rem;
  font-weight: 600;
}

.memory-toggle p {
  margin: 6px 0 0;
}

.memory-notice {
  margin: 20px 0 28px;
  padding: 12px 16px;
  background: var(--app-hover);
  border-radius: 7px;
}

.memory-toolbar, .memory-item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.memory-toolbar h3 {
  margin: 0;
  font-size: 0.9rem;
}

.memory-toolbar h3 span {
  margin-left: 6px;
  color: var(--app-text-muted);
  font-weight: 400;
}

.memory-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.memory-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.memory-list {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
}

.memory-item {
  padding: 16px 0;
  border-bottom: 1px solid var(--app-border);
}

.memory-item h4 {
  margin: 0;
  font-size: 0.86rem;
  font-weight: 600;
}

.memory-value {
  margin: 8px 0;
  line-height: 1.65;
  font-size: 0.88rem;
  overflow-wrap: anywhere;
}

.memory-item small {
  color: var(--app-text-muted);
  font-size: 0.75rem;
}

.memory-edit {
  display: grid;
  gap: 12px;
  margin: 12px 0;
}

.memory-edit .memory-actions {
  justify-content: flex-end;
}

.memory-empty {
  padding: 32px 0;
}

.memory-empty p {
  margin: 0 0 8px;
  font-size: 0.9rem;
}

.memory-empty span {
  color: var(--app-text-muted);
  font-size: 0.84rem;
  line-height: 1.65;
}

.memory-footnote {
  margin-top: 24px;
  font-size: 0.78rem;
}

.memory-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
  color: var(--el-color-danger);
  font-size: 0.85rem;
}

@media (max-width: 520px) {
  .memory-toggle {
  gap: 12px;
}

  .memory-toolbar {
  align-items: flex-start;
  flex-wrap: wrap;
}

}
</style>
