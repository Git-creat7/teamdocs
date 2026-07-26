<template>
  <div class="trash-page">
    <div class="trash-container">
      <div class="page-head anim-item" style="--delay: 0">
        <div>
          <h1 class="page-title">回收站</h1>
          <p class="page-sub">已删除的文档保留在这里，可恢复或彻底删除</p>
        </div>
        <el-select
          v-model="activeSpaceId"
          class="space-select"
          placeholder="选择空间"
          @change="loadTrash"
        >
          <el-option
            v-for="space in spaces"
            :key="space.id"
            :label="space.name"
            :value="space.id"
          />
        </el-select>
      </div>

      <div v-loading="loading" class="trash-panel anim-item stagger-rows" style="--delay: 1">
        <EmptyState
          v-if="!loading && items.length === 0"
          :icon="Trash2"
          title="回收站是空的"
          description="删除的文档会先保留在这里，可随时恢复"
        />

        <div v-for="doc in items" :key="doc.id" class="trash-row">
          <FileIcon :ext="getFileExt(doc.name, doc.fileType)" :size="30" />
          <div class="row-main">
            <span class="row-name" :title="doc.name">{{ doc.name }}</span>
            <span class="row-meta">
              {{ formatBytes(doc.fileSize) }} · 删除于 {{ formatDateTime(doc.updatedAt) }}
            </span>
          </div>
          <div class="row-actions">
            <el-button size="small" @click="handleRestore(doc)">恢复</el-button>
            <el-button size="small" type="danger" plain @click="handlePurge(doc)">
              彻底删除
            </el-button>
          </div>
        </div>

        <div v-if="total > items.length" class="trash-more">
          <el-button link size="small" @click="loadMore">
            加载更多 (已显示 {{ items.length }} / {{ total }})
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Trash2 } from 'lucide-vue-next'
import { listTrashedDocumentsApi, restoreDocumentApi, purgeDocumentApi } from '@/api/document'
import EmptyState from '@/components/EmptyState.vue'
import FileIcon from '@/components/FileIcon.vue'
import { storeToRefs } from 'pinia'
import { useSpacesStore } from '@/stores'
import { formatBytes, formatDateTime, getFileExt, getFileTypeColor } from '@/utils/format'

const route = useRoute()
const spacesStore = useSpacesStore()
const { spaces } = storeToRefs(spacesStore)
const refreshSpaces = spacesStore.refresh

const activeSpaceId = ref(null)
const loading = ref(false)
const items = ref([])
const total = ref(0)
const current = ref(1)
const PAGE_SIZE = 50

onMounted(async () => {
  // 先定 query 里的空间，再兜底空间列表首个，全部定下来后只发一次请求
  const fromQuery = Number(route.query.spaceId)
  if (!isNaN(fromQuery) && fromQuery > 0) {
    activeSpaceId.value = fromQuery
  }
  if (spaces.value.length === 0) {
    await refreshSpaces()
  }
  if (!activeSpaceId.value && spaces.value.length > 0) {
    activeSpaceId.value = spaces.value[0].id
  }
  if (activeSpaceId.value) {
    loadTrash()
  }
})

async function loadTrash() {
  if (!activeSpaceId.value) return
  loading.value = true
  current.value = 1
  try {
    const page = await listTrashedDocumentsApi(activeSpaceId.value, 1, PAGE_SIZE)
    items.value = page.records
    total.value = page.total
  } catch (err) {
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  const next = current.value + 1
  try {
    const page = await listTrashedDocumentsApi(activeSpaceId.value, next, PAGE_SIZE)
    items.value = [...items.value, ...page.records]
    current.value = next
  } catch (err) {
    // 拦截器处理
  }
}

async function handleRestore(doc) {
  try {
    await restoreDocumentApi(activeSpaceId.value, doc.id)
    ElMessage.success(`"${doc.name}" 已恢复`)
    await loadTrash()
  } catch (err) {
    // 拦截器处理
  }
}

function handlePurge(doc) {
  ElMessageBox.confirm(
    `彻底删除后文件无法找回，确定删除 "${doc.name}" 吗？`,
    '彻底删除',
    { confirmButtonText: '彻底删除', cancelButtonText: '取消', type: 'error' }
  ).then(async () => {
    try {
      await purgeDocumentApi(activeSpaceId.value, doc.id)
      ElMessage.success('文件已彻底删除')
      await loadTrash()
    } catch (err) {
      // 拦截器处理
    }
  }).catch(() => {})
}
</script>

<style scoped>
.trash-page {
  flex: 1;
  overflow-y: auto;
  padding: 2rem 2.5rem 3rem;
}

.trash-container {
  max-width: 880px;
  margin: 0 auto;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 1.5rem;
}

.page-title {
  font-size: 1.4rem;
  font-weight: 700;
  color: var(--app-text);
  margin: 0 0 0.3rem;
}

.page-sub {
  font-size: 0.85rem;
  color: var(--app-text-faint);
  margin: 0;
}

.space-select {
  width: 200px;
  flex-shrink: 0;
}

.trash-panel {
  background: var(--app-panel);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  overflow: hidden;
  min-height: 200px;
}

.trash-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 18px;
}

.trash-row + .trash-row {
  border-top: 1px solid var(--app-border-soft);
}

.trash-row:hover {
  background-color: var(--app-hover-soft);
}

.ext-badge {
  font-size: 0.65rem;
  font-weight: 700;
  color: #ffffff;
  padding: 2px 5px;
  border-radius: 4px;
  letter-spacing: 0.5px;
  min-width: 30px;
  text-align: center;
  flex-shrink: 0;
}

.row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.row-name {
  font-size: 0.9rem;
  font-weight: 500;
  color: var(--app-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-meta {
  font-size: 0.75rem;
  color: var(--app-text-faint);
}

.row-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.trash-more {
  display: flex;
  justify-content: center;
  padding: 10px 0;
}

@media (max-width: 768px) {
  .trash-page { padding: 1.1rem 1rem 2rem; }

  .page-head {
    flex-direction: column;
    align-items: stretch;
  }

  .space-select { width: 100%; }

  .trash-row {
    flex-wrap: wrap;
  }

  .row-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
