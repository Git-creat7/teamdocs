<template>
  <div class="scope-picker">
    <!-- 紧凑工具栏触发按钮 -->
    <div class="scope-trigger-group">
      <button
        type="button"
        class="scope-chip"
        :class="{ 'is-active': !!modelValue }"
        :disabled="disabled"
        :title="triggerTitle"
        @click="openDialog"
      >
        <Target v-if="!modelValue" :size="13" class="scope-chip-icon" />
        <Folder v-else-if="modelValue.kind === 'folder'" :size="13" class="scope-chip-icon active-icon" />
        <FileText v-else :size="13" class="scope-chip-icon active-icon" />
        <span class="scope-chip-label">问答范围：</span>
        <span class="scope-chip-name">{{ modelValue?.name || '整个空间' }}</span>
        <ChevronDown :size="12" class="scope-chip-arrow" />
      </button>

      <button
        v-if="modelValue"
        type="button"
        class="scope-clear-btn"
        :disabled="disabled"
        title="清除限定，恢复向整个空间提问"
        @click="clearScope"
      >
        <X :size="12" />
        <span>清除</span>
      </button>
    </div>

    <!-- 弹窗：选择问答范围 -->
    <el-dialog
      v-model="open"
      title="选择问答范围"
      width="min(520px, 94vw)"
      destroy-on-close
      append-to-body
      class="scope-dialog"
    >
      <!-- 提示信息说明 -->
      <div class="scope-dialog-tip" role="note">
        <Info :size="15" class="tip-icon" />
        <p class="tip-text">
          选择一个或多个文件（带复选框），或选择单个文件夹。限定范围时不使用历史回答或外部工具。
        </p>
      </div>

      <!-- 目录导航栏 -->
      <div class="scope-dialog-nav">
        <button
          type="button"
          class="nav-btn"
          :disabled="busy || !path.length"
          title="返回上一级"
          @click="back"
        >
          <ArrowLeft :size="13" />
          <span>上级目录</span>
        </button>

        <div class="nav-breadcrumbs">
          <button
            type="button"
            class="breadcrumb-item"
            :class="{ active: !path.length }"
            @click="goToRoot"
          >
            <FolderOpen :size="13" />
            <span>空间根目录</span>
          </button>
          <template v-for="p in path" :key="p.id">
            <span class="breadcrumb-separator">/</span>
            <span class="breadcrumb-item active" :title="p.name">{{ p.name }}</span>
          </template>
        </div>

        <button
          v-if="path.length"
          type="button"
          class="nav-choose-folder-btn"
          :disabled="disabled"
          title="单选此文件夹作为问答范围"
          @click="chooseFolder(path.at(-1))"
        >
          <Check :size="12" />
          <span>选择此文件夹</span>
        </button>
      </div>

      <!-- 列表内容区 -->
      <div class="scope-dialog-body">
        <div v-if="error" class="scope-status-state error" role="alert">
          <AlertCircle :size="16" />
          <span>{{ error }}</span>
          <button type="button" class="retry-link" @click="load">重试</button>
        </div>

        <div v-else-if="busy" class="scope-status-state loading">
          <LoaderCircle :size="18" class="spin-icon" />
          <span>正在读取目录…</span>
        </div>

        <div v-else-if="!items.length" class="scope-status-state empty">
          <FolderOpen :size="22" class="empty-icon" />
          <span>当前目录为空</span>
        </div>

        <ul v-else class="scope-item-list">
          <li
            v-for="item in items"
            :key="`${item.kind}:${item.id}`"
            class="scope-item-row"
            :class="{ 'is-active': isItemActive(item) }"
          >
            <!-- 文件夹：单选 -->
            <template v-if="item.kind === 'folder'">
              <button
                type="button"
                class="item-main-btn"
                :disabled="disabled"
                :title="`进入文件夹：${item.name}`"
                @click="enter(item)"
              >
                <Folder :size="15" class="item-icon folder-icon" />
                <span class="item-title">{{ item.name }}</span>
                <span class="item-tag">文件夹</span>
                <ChevronRight :size="13" class="item-chevron" />
              </button>

              <button
                type="button"
                class="item-select-folder-btn"
                :disabled="disabled"
                :title="`单选此文件夹：${item.name}`"
                @click="chooseFolder(item)"
              >
                选择
              </button>
            </template>

            <!-- 文件：带复选框，支持多选 -->
            <template v-else>
              <label
                class="item-file-label"
                :title="`勾选文件：${item.name}`"
              >
                <input
                  type="checkbox"
                  class="file-checkbox"
                  :disabled="disabled"
                  :checked="isFileChecked(item)"
                  @change="toggleFile(item)"
                />
                <FileText :size="15" class="item-icon file-icon" />
                <span class="item-title">{{ item.name }}</span>
                <span class="item-tag">文档</span>
              </label>
            </template>
          </li>
        </ul>
      </div>

      <!-- 底部提示与配额 -->
      <div class="scope-dialog-info">
        <span v-if="selectedFiles.length" class="selected-count-badge">
          已勾选 {{ selectedFiles.length }} 个文件
        </span>
        <small class="quota-note">每层最多显示200个文件和200个文件夹。</small>
      </div>

      <!-- 弹窗底部操作 -->
      <template #footer>
        <div class="scope-dialog-footer">
          <button
            v-if="modelValue || selectedFiles.length"
            type="button"
            class="footer-reset-btn"
            @click="clearScope"
          >
            重置为整个空间
          </button>
          <div class="footer-spacer"></div>
          <button
            v-if="selectedFiles.length"
            type="button"
            class="footer-confirm-btn"
            @click="confirmFiles"
          >
            确定选择（已选 {{ selectedFiles.length }} 个文件）
          </button>
          <button
            type="button"
            class="footer-close-btn"
            @click="open = false"
          >
            关闭
          </button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import {
  Folder,
  FolderOpen,
  FileText,
  ChevronDown,
  ChevronRight,
  ArrowLeft,
  Check,
  X,
  Info,
  AlertCircle,
  LoaderCircle,
  Target
} from 'lucide-vue-next'
import { scopeOptions } from '@/api/documentTools'

const props = defineProps({
  spaceId: [String, Number],
  modelValue: Object,
  disabled: Boolean
})

const emit = defineEmits(['update:modelValue'])

const open = ref(false)
const path = ref([])
const items = ref([])
const busy = ref(false)
const error = ref('')
const selectedFiles = ref([])
let controller

const triggerTitle = computed(() => {
  if (!props.modelValue) return '设置问答范围（默认整个空间）'
  if (props.modelValue.files?.length > 1) {
    return `当前限定范围：${props.modelValue.files.map((f) => f.name).join('、')}，点击更换`
  }
  return `当前限定范围：${props.modelValue.name}，点击更换`
})

async function load() {
  controller?.abort()
  const request = controller = new AbortController()
  busy.value = true
  error.value = ''
  try {
    const rows = await scopeOptions(props.spaceId, path.value.at(-1)?.id || 0, request.signal)
    if (!request.signal.aborted) {
      items.value = Array.isArray(rows) ? rows : []
    }
  } catch (e) {
    if (!request.signal.aborted) {
      error.value = e.message || '目录读取失败'
    }
  } finally {
    if (!request.signal.aborted) {
      busy.value = false
    }
  }
}

function openDialog() {
  if (props.disabled) return
  open.value = true
}

function enter(item) {
  path.value.push(item)
  load()
}

function back() {
  path.value.pop()
  load()
}

function goToRoot() {
  if (!path.value.length) return
  path.value = []
  load()
}

function isFileChecked(item) {
  return selectedFiles.value.some((f) => String(f.id) === String(item.id))
}

function toggleFile(item) {
  const index = selectedFiles.value.findIndex((f) => String(f.id) === String(item.id))
  if (index >= 0) {
    selectedFiles.value.splice(index, 1)
  } else {
    selectedFiles.value.push({ id: item.id, name: item.name, kind: 'document' })
  }
}

function chooseFolder(folder) {
  selectedFiles.value = []
  emit('update:modelValue', { kind: 'folder', id: folder.id, name: folder.name, files: [] })
  open.value = false
}

function confirmFiles() {
  if (!selectedFiles.value.length) {
    emit('update:modelValue', null)
  } else if (selectedFiles.value.length === 1) {
    const file = selectedFiles.value[0]
    emit('update:modelValue', {
      kind: 'document',
      id: file.id,
      name: file.name,
      files: [file]
    })
  } else {
    emit('update:modelValue', {
      kind: 'document',
      id: selectedFiles.value[0].id,
      name: `已选 ${selectedFiles.value.length} 个文件`,
      files: [...selectedFiles.value]
    })
  }
  open.value = false
}

function clearScope() {
  selectedFiles.value = []
  emit('update:modelValue', null)
  open.value = false
}

function isItemActive(item) {
  if (item.kind === 'folder') {
    return (
      props.modelValue &&
      props.modelValue.kind === 'folder' &&
      String(props.modelValue.id) === String(item.id)
    )
  }
  return isFileChecked(item)
}

watch(open, (value) => {
  if (value) {
    if (props.modelValue?.kind === 'document') {
      selectedFiles.value = props.modelValue.files?.length
        ? [...props.modelValue.files]
        : [{ id: props.modelValue.id, name: props.modelValue.name, kind: 'document' }]
    } else {
      selectedFiles.value = []
    }
    if (props.spaceId) {
      load()
    }
  }
})

watch(
  () => props.spaceId,
  () => {
    controller?.abort()
    open.value = false
    path.value = []
    items.value = []
    selectedFiles.value = []
    emit('update:modelValue', null)
  }
)

onBeforeUnmount(() => controller?.abort())
</script>

<style scoped>
.scope-picker {
  display: inline-flex;
  align-items: center;
}

.scope-trigger-group {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.scope-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 10px;
  border-radius: 6px;
  border: 1px solid var(--app-border, #e2e8f0);
  background: var(--app-surface, #ffffff);
  color: var(--app-text, #1e293b);
  font-size: 0.8125rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  user-select: none;
}

.scope-chip:hover:not(:disabled) {
  background: var(--app-hover, #f8fafc);
  border-color: var(--app-border-strong, #cbd5e1);
}

.scope-chip:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.scope-chip.is-active {
  border-color: var(--el-color-primary-light-5, #93c5fd);
  background: var(--el-color-primary-light-9, #eff6ff);
  color: var(--el-color-primary, #2563eb);
}

.scope-chip-icon {
  color: var(--app-text-muted, #64748b);
  flex-shrink: 0;
}

.scope-chip.is-active .scope-chip-icon {
  color: var(--el-color-primary, #2563eb);
}

.scope-chip-label {
  color: var(--app-text-muted, #64748b);
  font-size: 0.75rem;
}

.scope-chip.is-active .scope-chip-label {
  color: var(--el-color-primary, #2563eb);
}

.scope-chip-name {
  font-weight: 600;
  max-width: 170px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scope-chip-arrow {
  color: var(--app-text-muted, #64748b);
  margin-left: 2px;
}

.scope-clear-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 26px;
  padding: 0 7px;
  border-radius: 4px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--app-text-muted, #64748b);
  font-size: 0.75rem;
  cursor: pointer;
  transition: all 0.15s ease;
}

.scope-clear-btn:hover:not(:disabled) {
  color: var(--el-color-danger, #ef4444);
  background: var(--el-color-danger-light-9, #fef2f2);
  border-color: var(--el-color-danger-light-7, #fecaca);
}

.scope-clear-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* 弹窗内容样式 */
.scope-dialog-tip {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--app-hover, #f1f5f9);
  border: 1px solid var(--app-border, #e2e8f0);
  margin-bottom: 12px;
}

.tip-icon {
  color: var(--el-color-primary, #2563eb);
  flex-shrink: 0;
  margin-top: 2px;
}

.tip-text {
  margin: 0;
  font-size: 0.8125rem;
  line-height: 1.45;
  color: var(--app-text-muted, #475569);
}

.scope-dialog-nav {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  background: var(--app-surface, #ffffff);
  border: 1px solid var(--app-border, #e2e8f0);
  margin-bottom: 10px;
}

.nav-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: 4px;
  border: 1px solid var(--app-border, #e2e8f0);
  background: var(--app-surface, #ffffff);
  font-size: 0.75rem;
  color: var(--app-text, #1e293b);
  cursor: pointer;
  transition: all 0.12s;
  flex-shrink: 0;
}

.nav-btn:hover:not(:disabled) {
  background: var(--app-hover, #f8fafc);
  border-color: var(--app-border-strong, #cbd5e1);
}

.nav-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.nav-breadcrumbs {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.breadcrumb-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 0.8125rem;
  color: var(--app-text, #1e293b);
  background: transparent;
  border: none;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 140px;
  padding: 2px 4px;
  border-radius: 3px;
}

.breadcrumb-item:hover {
  background: var(--app-hover, #f1f5f9);
}

.breadcrumb-item.active {
  font-weight: 600;
  color: var(--app-text, #1e293b);
}

.breadcrumb-separator {
  color: var(--app-text-muted, #94a3b8);
  font-size: 0.75rem;
}

.nav-choose-folder-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: 4px;
  background: var(--el-color-primary-light-9, #eff6ff);
  border: 1px solid var(--el-color-primary-light-7, #bfdbfe);
  color: var(--el-color-primary, #2563eb);
  font-size: 0.75rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.12s;
  flex-shrink: 0;
}

.nav-choose-folder-btn:hover:not(:disabled) {
  background: var(--el-color-primary, #2563eb);
  color: #ffffff;
}

.nav-choose-folder-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.scope-dialog-body {
  min-height: 200px;
  max-height: 270px;
  overflow-y: auto;
  border: 1px solid var(--app-border, #e2e8f0);
  border-radius: 6px;
  background: var(--app-surface, #ffffff);
}

.scope-status-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 180px;
  font-size: 0.8125rem;
  color: var(--app-text-muted, #64748b);
}

.scope-status-state.error {
  color: var(--el-color-danger, #ef4444);
}

.spin-icon {
  animation: spin 1s linear infinite;
  color: var(--el-color-primary, #2563eb);
}

.empty-icon {
  color: var(--app-text-muted, #94a3b8);
}

.retry-link {
  border: none;
  background: none;
  color: var(--el-color-primary, #2563eb);
  font-size: 0.75rem;
  cursor: pointer;
  text-decoration: underline;
}

.scope-item-list {
  list-style: none;
  margin: 0;
  padding: 4px;
}

.scope-item-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 4px;
  border-radius: 4px;
  transition: background 0.12s;
}

.scope-item-row:hover {
  background: var(--app-hover, #f8fafc);
}

.scope-item-row.is-active {
  background: var(--el-color-primary-light-9, #eff6ff);
}

.item-main-btn {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 6px 8px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  border-radius: 4px;
}

.item-file-label {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 6px 8px;
  cursor: pointer;
  border-radius: 4px;
  user-select: none;
}

.file-checkbox {
  width: 15px;
  height: 15px;
  cursor: pointer;
  accent-color: var(--el-color-primary, #2563eb);
  margin: 0;
  flex-shrink: 0;
}

.item-icon {
  flex-shrink: 0;
}

.folder-icon {
  color: #eab308;
}

.file-icon {
  color: var(--app-text-muted, #64748b);
}

.item-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.8125rem;
  color: var(--app-text, #1e293b);
}

.item-tag {
  font-size: 0.6875rem;
  color: var(--app-text-muted, #64748b);
  background: var(--app-hover, #f1f5f9);
  padding: 1px 5px;
  border-radius: 3px;
  flex-shrink: 0;
}

.item-chevron {
  color: var(--app-text-muted, #94a3b8);
  flex-shrink: 0;
}

.item-select-folder-btn {
  display: inline-flex;
  align-items: center;
  padding: 2px 7px;
  font-size: 0.75rem;
  border-radius: 4px;
  border: 1px solid var(--app-border, #e2e8f0);
  background: var(--app-surface, #ffffff);
  color: var(--app-text, #1e293b);
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.12s;
}

.item-select-folder-btn:hover:not(:disabled) {
  border-color: var(--el-color-primary, #2563eb);
  color: var(--el-color-primary, #2563eb);
  background: var(--el-color-primary-light-9, #eff6ff);
}

.item-select-folder-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.scope-dialog-info {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.selected-count-badge {
  font-size: 0.75rem;
  color: var(--el-color-primary, #2563eb);
  font-weight: 500;
}

.quota-note {
  font-size: 0.75rem;
  color: var(--app-text-muted, #94a3b8);
}

.scope-dialog-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  width: 100%;
}

.footer-reset-btn {
  padding: 5px 12px;
  border-radius: 5px;
  border: 1px solid var(--app-border, #e2e8f0);
  background: transparent;
  font-size: 0.8125rem;
  color: var(--app-text-muted, #64748b);
  cursor: pointer;
  transition: all 0.12s;
}

.footer-reset-btn:hover {
  color: var(--el-color-danger, #ef4444);
  border-color: var(--el-color-danger-light-7, #fecaca);
  background: var(--el-color-danger-light-9, #fef2f2);
}

.footer-spacer {
  flex: 1;
}

.footer-confirm-btn {
  padding: 5px 14px;
  border-radius: 5px;
  border: 1px solid var(--el-color-primary, #2563eb);
  background: var(--el-color-primary, #2563eb);
  color: #ffffff;
  font-size: 0.8125rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.12s;
}

.footer-confirm-btn:hover {
  opacity: 0.9;
}

.footer-close-btn {
  padding: 5px 14px;
  border-radius: 5px;
  border: 1px solid var(--app-border, #e2e8f0);
  background: var(--app-surface, #ffffff);
  font-size: 0.8125rem;
  color: var(--app-text, #1e293b);
  cursor: pointer;
  transition: all 0.12s;
}

.footer-close-btn:hover {
  background: var(--app-hover, #f1f5f9);
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
