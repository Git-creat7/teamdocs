<template>
  <div class="doc-preview">
    <!-- 加载中 -->
    <div v-if="state === 'loading'" class="doc-preview__status">
      <el-icon class="is-loading" :size="26"><Loading /></el-icon>
      <p>正在加载预览…</p>
    </div>

    <!-- 接口请求失败 -->
    <div v-else-if="state === 'apiError'" class="doc-preview__status">
      <el-icon :size="30" color="var(--el-color-danger)"><CircleCloseFilled /></el-icon>
      <p>预览加载失败</p>
      <el-button @click="load">重试</el-button>
    </div>

    <!-- 预览渲染失败 / 不支持的格式：展示元信息 + 下载 -->
    <div v-else-if="state === 'renderError' || state === 'unsupported'" class="doc-preview__status">
      <el-icon :size="30" color="var(--el-color-warning)"><WarningFilled /></el-icon>
      <p v-if="state === 'unsupported'">该文件格式暂不支持在线预览</p>
      <p v-else>预览渲染失败</p>
      <div class="doc-preview__meta" v-if="meta">
        <div class="doc-preview__meta-name">{{ meta.name }}</div>
        <div class="doc-preview__meta-sub">
          <span>{{ meta.fileType || '未知类型' }}</span>
          <span v-if="meta.fileSize">· {{ formatFileSize(meta.fileSize) }}</span>
        </div>
      </div>
      <el-button
        type="primary"
        :icon="Download"
        :loading="downloading"
        @click="download"
      >
        下载文件
      </el-button>
    </div>

    <!-- 正常渲染 -->
    <file-viewer
      v-else-if="state === 'ready' && meta"
      ref="viewerRef"
      class="doc-preview__viewer"
      :url="meta.url"
      :options="viewerOptions"
      @load-complete="onLoadComplete"
    />
  </div>
</template>

<script setup>
import { ref, computed, onErrorCaptured, watch } from 'vue'
import { Loading, Download, WarningFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import litePreset from '@file-viewer/preset-lite'
import officePreset from '@file-viewer/preset-office'
import { downloadDocumentApi, previewDocumentApi } from '@/api/document'
import { formatBytes as formatFileSize } from '@/utils/format'

const props = defineProps({
  spaceId: { type: [Number, String], required: true },
  documentId: { type: [Number, String], required: true }
})
const emit = defineEmits(['loaded'])

const state = ref('loading') // loading | apiError | unsupported | renderError | ready
const meta = ref(null)
const viewerRef = ref(null)
const downloading = ref(false)

// 预览类别判定：优先用后端 fileType (MIME)，回退到文件名扩展名
const IMAGE_EXT = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg']
const PDF_EXT = ['pdf']
const TEXT_EXT = ['txt', 'md', 'markdown', 'csv', 'log', 'json', 'xml', 'yaml', 'yml',
  'js', 'ts', 'html', 'css', 'java', 'py', 'go', 'sql']
const OFFICE_EXT = ['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx']

function resolveCategory(m) {
  const mime = String(m.fileType || '').toLowerCase()
  const ext = String(m.name || '').split('.').pop().toLowerCase()
  if (mime.startsWith('image/') || IMAGE_EXT.includes(ext)) return 'image'
  if (mime === 'application/pdf' || PDF_EXT.includes(ext)) return 'pdf'
  if (mime.startsWith('text/') || TEXT_EXT.includes(ext)) return 'text'
  if (OFFICE_EXT.includes(ext) || mime.includes('officedocument') || mime.includes('ms-')) return 'office'
  return 'unsupported'
}

// preset-lite 负责 图片/文本/代码，preset-office 负责 PDF/Office
const viewerOptions = computed(() => ({
  preset: [litePreset, officePreset],
  rendererMode: 'replace',
  theme: document.documentElement.classList.contains('dark') ? 'dark' : 'light',
  toolbar: {
    beforeDownload: async () => {
      await download()
      return false
    }
  },
  // 隐藏 PDF 左侧页面导航/目录/缩略图
  sidebar: false,
  outline: false,
  thumbnails: false,
  nav: false,
  pageNav: false,
  pageNavigation: false,
  sidebarView: false,
  sidebarVisible: false,
  hideSidebar: true,
  hideOutline: true,
  hideThumbnails: true,
  hideNav: true,
  hidePageNav: true,
  hidePageNavigation: true,
  hideSidebarView: true,
  hideSidebarVisible: true,
  sidebarOpen: false,
  outlineOpen: false,
  thumbnailsOpen: false,
  navOpen: false,
  pageNavOpen: false,
  pageNavigationOpen: false,
  sidebarViewOpen: false,
  sidebarVisibleOpen: false,
  enableSidebar: false,
  enableOutline: false,
  enableThumbnails: false,
  enableNav: false,
  enablePageNav: false,
  enablePageNavigation: false,
  enableSidebarView: false,
  enableSidebarVisible: false,
  showSidebar: false,
  showOutline: false,
  showThumbnails: false,
  showNav: false,
  showPageNav: false,
  showPageNavigation: false,
  showSidebarView: false,
  showSidebarVisible: false
}))

async function load() {
  state.value = 'loading'
  meta.value = null
  try {
    const data = await previewDocumentApi(props.spaceId, props.documentId)
    meta.value = data
    emit('loaded', data)
    state.value = resolveCategory(data) === 'unsupported' ? 'unsupported' : 'ready'
  } catch (e) {
    // request.js 已统一 ElMessage 报错，这里只切换 UI 状态
    state.value = 'apiError'
  }
}

function onLoadComplete(payload) {
  // 部分渲染器会在 payload 里带失败标记；若失败则回退到元信息+下载
  if (payload && payload.success === false) state.value = 'renderError'
}

async function download() {
  if (downloading.value) return
  downloading.value = true
  try {
    const url = await downloadDocumentApi(props.spaceId, props.documentId)
    if (!url) return
    const a = document.createElement('a')
    a.href = url
    a.target = '_blank'
    a.rel = 'noopener noreferrer'
    document.body.appendChild(a)
    a.click()
    a.remove()
  } catch (err) {
    // 请求错误由全局拦截器统一提示
  } finally {
    downloading.value = false
  }
}

// 捕获 file-viewer 子树的渲染异常，避免白屏
onErrorCaptured(() => {
  if (state.value === 'ready') state.value = 'renderError'
  return false
})

watch(() => [props.spaceId, props.documentId], load, { immediate: true })
</script>

<style scoped>
.doc-preview {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.doc-preview__viewer {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
}
.doc-preview__status {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 32px;
  color: var(--el-text-color-regular);
}
.doc-preview__meta {
  text-align: center;
}
.doc-preview__meta-name {
  font-weight: 600;
  word-break: break-all;
}
.doc-preview__meta-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: flex;
  gap: 4px;
  justify-content: center;
}
</style>
