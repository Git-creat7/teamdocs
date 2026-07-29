<template>
  <div class="preview-page">
    <header class="preview-page__bar">
      <span class="preview-page__name" :title="title">{{ title || '文档预览' }}</span>
    </header>
    <main class="preview-page__body">
      <DocumentPreview :space-id="spaceId" :document-id="documentId" @loaded="onLoaded" />
    </main>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import DocumentPreview from '@/components/DocumentPreview.vue'

const route = useRoute()
const spaceId = route.params.spaceId
const documentId = route.params.documentId
const title = ref('')

function onLoaded(meta) {
  title.value = meta?.name || ''
  if (meta?.name) document.title = `${meta.name} - 预览`
}
</script>

<style scoped>
.preview-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.preview-page__bar {
  flex: 0 0 auto;
  height: 48px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  border-bottom: 1px solid var(--el-border-color);
  background: var(--el-bg-color);
}
.preview-page__name {
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.preview-page__body {
  flex: 1 1 auto;
  min-height: 0;
}
</style>
