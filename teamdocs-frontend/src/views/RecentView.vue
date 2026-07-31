<template>
  <div class="recent-page">
    <div class="recent-container">
      <div class="page-head anim-item" style="--delay: 0">
        <h1 class="page-title">最近浏览</h1>
        <p class="page-sub">最多保留最近 20 条浏览记录</p>
      </div>

      <div v-loading="loading" class="recent-panel anim-item stagger-rows" style="--delay: 1">
        <EmptyState
          v-if="!loading && docs.length === 0"
          :icon="History"
          title="最近还没有浏览过文档"
          description="打开任意文档后，会在这里留下入口"
        />

        <div v-for="doc in docs" :key="doc.documentId" class="recent-row" @click="openRecentDoc(doc)">
          <FileIcon :ext="getFileExt(doc.name, doc.fileType)" :size="30" />
          <div class="row-main">
            <span class="row-name" :title="doc.name">{{ doc.name }}</span>
            <span class="row-meta">
              {{ doc.spaceName }} · {{ formatBytes(doc.fileSize) }}
            </span>
          </div>
          <span class="row-time">{{ formatDateTime(doc.lastViewedAt) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { History } from 'lucide-vue-next'
import { getRecentDocumentsApi } from '@/api/user'
import EmptyState from '@/components/EmptyState.vue'
import FileIcon from '@/components/FileIcon.vue'
import { formatBytes, formatDateTime, getFileExt, getFileTypeColor } from '@/utils/format'
import { useDocumentNavigation } from '@/composables/useDocumentNavigation'

const { openDocument } = useDocumentNavigation()
const loading = ref(true)
const docs = ref([])

onMounted(async () => {
  try {
    docs.value = await getRecentDocumentsApi()
  } catch (err) {
    docs.value = []
  } finally {
    loading.value = false
  }
})

function openRecentDoc(doc) {
  openDocument({
    spaceId: doc.spaceId,
    documentId: doc.documentId
  })
}
</script>

<style scoped>
.recent-page {
  flex: 1;
  overflow-y: auto;
  padding: 2rem 2.5rem 3rem;
}

.recent-container {
  max-width: 880px;
  margin: 0 auto;
}

.page-head {
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

.recent-panel {
  background: var(--app-panel);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  overflow: hidden;
  min-height: 200px;
}

.recent-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 18px;
  cursor: pointer;
  transition: background-color var(--dur-fast) var(--ease-standard);
}

.recent-row + .recent-row {
  border-top: 1px solid var(--app-border-soft);
}

.recent-row:hover {
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

.row-time {
  font-size: 0.78rem;
  color: var(--app-text-faint);
  flex-shrink: 0;
}

@media (max-width: 768px) {
  .recent-page { padding: 1.1rem 1rem 2rem; }
  .row-time { display: none; }
}
</style>
