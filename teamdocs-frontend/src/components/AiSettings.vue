<template>
  <div class="ai-settings" :aria-busy="busy">
    <header class="section-heading">
      <h2>AI 服务</h2>
      <p>配置自己的问答模型，查看共享依赖的运行状态。</p>
    </header>
    <el-skeleton v-if="!loaded && busy" :rows="4" animated />
    <div v-if="error" role="alert" class="error-message">
      {{ error }} <el-button size="small" :disabled="busy" @click="settings.load">重新加载</el-button>
    </div>

    <template v-if="loaded">
      <h3>我的问答模型</h3>
      <p class="hint">当前使用：{{ model.enabled ? '个人配置' : '系统默认' }}。个人配置只影响你的问答和用户记忆提取，文档索引仍使用系统模型。</p>
      <el-alert v-if="!model.encryptionReady" type="warning" :closable="false"
        title="管理员尚未配置模型凭据加密主密钥，暂不能保存个人配置。系统默认模型不受影响。" />
      <form class="model-form" @submit.prevent="save">
        <label for="ai-base-url">Base URL</label>
        <el-input id="ai-base-url" v-model="form.baseUrl" :disabled="busy" maxlength="500"
          placeholder="https://api.example.com/v1" autocomplete="off" />
        <span class="hint">仅支持公网 HTTPS 默认端口。填写 API 基础地址，不含 /chat/completions、查询参数或密钥。</span>
        <label for="ai-model-name">模型名称</label>
        <el-input id="ai-model-name" v-model="form.modelName" :disabled="busy" maxlength="100"
          placeholder="填写供应商提供的完整模型标识" autocomplete="off" />
        <label for="ai-api-key">API Key</label>
        <el-input id="ai-api-key" v-model="form.apiKey" type="password" show-password :disabled="busy" maxlength="4096"
          :placeholder="model.hasKey ? '已保存；留空保留原密钥（更换地址也会沿用）' : '仅用于你自己的模型请求'" autocomplete="new-password" />
        <div class="toggle-row">
          <span id="ai-enable-label">使用个人模型</span>
          <el-switch v-model="form.enabled" :disabled="busy" aria-label="使用个人模型" />
        </div>
        <p class="notice">启用后，你的问题、历史对话、已检索的文档内容及用户记忆可能发送给此供应商。请使用有权接收这些资料的服务。失败时不会自动切回系统模型；设置更新只影响新提问。</p>
        <div class="actions">
          <el-button v-if="model.enabled" :disabled="busy" @click="disable">改用系统默认</el-button>
          <el-button :disabled="busy || !valid" @click="testModel">测试连通性</el-button>
          <el-button type="primary" native-type="submit" :disabled="busy || !valid || !model.encryptionReady" :loading="busy">保存</el-button>
        </div>
        <p v-if="testResult" role="status" class="probe-result">
          测试本次填写的配置：{{ statusText(testResult.status) }} · {{ testResult.durationMs }} ms<br>{{ testResult.detail }}
        </p>
      </form>

      <header class="dependencies-heading">
        <h3>依赖状态</h3>
        <el-button size="small" :disabled="busy" @click="settings.refresh">刷新状态</el-button>
      </header>
      <p class="hint">打开页面或刷新不会调用外部服务。探测结果缓存5分钟；未检测不代表异常。检测完成后可立即重试；模型检测会产生少量用量。</p>
      <ul class="dependency-list">
        <li v-for="item in dependencies" :key="item.id">
          <div class="dependency-head">
            <strong>{{ item.name }}</strong>
            <span class="status" :class="item.probe.status.toLowerCase()">{{ statusText(item.probe.status) }}</span>
            <el-button size="small" :disabled="busy || !item.configured" @click="testDependency(item)">检测</el-button>
          </div>
          <p>{{ item.probe.detail }}</p>
          <small v-if="item.probe.checkedAt">{{ dateText(item.probe.checkedAt) }} · {{ item.probe.durationMs }} ms</small>
          <p v-if="item.runtime" class="runtime" :class="{ degraded: item.runtime.status === 'DEGRADED' }">
            实际运行：{{ item.runtime.detail }}（{{ dateText(item.runtime.checkedAt) }}）
          </p>
          <small v-else>暂无最近一小时的实际调用记录；探测成功不代表这次问答已使用该服务。</small>
        </li>
      </ul>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAiSettings } from '@/composables/useAiSettings'

const settings = useAiSettings()
const { model, form, dependencies, loaded, busy: requesting, error, testResult } = settings
const confirming = ref(false)
const busy = computed(() => requesting.value || confirming.value)
const valid = computed(() => form.baseUrl.trim() && form.modelName.trim() && (form.apiKey || model.value?.hasKey))
onMounted(settings.load)

async function consent(message, title) {
  if (busy.value) return false
  confirming.value = true
  try {
    await ElMessageBox.confirm(message, title, { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' })
    return true
  } catch { return false }
  finally { confirming.value = false }
}

async function save() {
  if (busy.value || !valid.value) return
  if (form.enabled && !await consent('确认将你的问答、检索资料和记忆发送给填写的模型供应商？仅影响你自己的新提问。', '启用个人模型')) return
  if (await settings.save()) ElMessage.success('模型配置已保存')
}

async function disable() {
  if (await consent('新提问将使用系统默认模型，已有运行不变。已加密保存的个人配置仍保留。', '改用系统默认')) {
    if (await settings.disable()) ElMessage.success('已改用系统默认模型')
  }
}

async function testModel() {
  if (!await consent('将用当前填写的配置发送最小测试请求，可能产生少量模型用量。测试不会保存或启用配置。', '测试连通性')) return
  await settings.testModel()
}

async function testDependency(item) {
  if (!await consent(item.usageCost ? '此检测会产生少量模型用量，不发送业务文档。是否继续？' : '将向已配置的共享服务发送只读检测请求，不修改索引或数据。是否继续？', `检测 ${item.name}`)) return
  await settings.testDependency(item.id)
}

function statusText(value) {
  return { HEALTHY: '正常', UNTESTED: '未检测', NOT_CONFIGURED: '未配置 / 未启用', ERROR: '异常', INCOMPATIBLE: '不兼容' }[value] || value
}
function dateText(value) { return new Date(value).toLocaleString('zh-CN') }
</script>

<style scoped>
.ai-settings {
  color: var(--app-text);
}

.section-heading {
  padding-bottom: 18px;
  border-bottom: 1px solid var(--app-border);
  margin-bottom: 24px;
}

h2 {
  margin: 0 0 8px;
  font-size: 1.08rem;
}

h3 {
  margin: 0;
  font-size: 0.95rem;
}

p {
  font-size: 0.85rem;
  line-height: 1.65;
}

.section-heading p, .hint, small {
  color: var(--app-text-muted);
}

.model-form {
  display: grid;
  gap: 10px;
  margin: 20px 0 32px;
}

.model-form label {
  font-size: 0.85rem;
  margin-top: 8px;
}

.hint, small {
  font-size: 0.78rem;
  line-height: 1.65;
}

.toggle-row, .dependencies-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.toggle-row {
  margin-top: 12px;
  font-size: 0.85rem;
}

.notice {
  background: var(--app-hover);
  padding: 12px 16px;
  border-radius: 7px;
}

.actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}

.actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.dependency-list {
  list-style: none;
  padding: 0;
  margin: 16px 0;
}

.dependency-list li {
  border-bottom: 1px solid var(--app-border);
  padding: 18px 0;
}

.dependency-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dependency-head strong {
  margin-right: auto;
  font-size: 0.85rem;
  overflow-wrap: anywhere;
}

.dependency-list p {
  margin: 8px 0;
}

.status {
  font-size: 0.78rem;
  color: var(--app-text-muted);
}

.healthy {
  color: #15803d;
}

.error, .incompatible, .degraded, .error-message {
  color: #b91c1c;
}

.probe-result {
  padding: 12px 0;
}

.runtime {
  font-size: 0.78rem;
}

@media(max-width: 520px) {
  .dependency-head {
  flex-wrap: wrap;
}

  .actions {
  justify-content: flex-start;
}

}
</style>
