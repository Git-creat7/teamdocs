<template>
  <div class="detail-root">
    <!-- 置顶的文档卡 -->
    <div class="doc-hero">
      <el-button v-if="showBackButton" class="back-btn" text @click="$emit('close')">
        <el-icon><ArrowLeft /></el-icon>
        返回文件树
      </el-button>

      <div class="doc-hero-main">
        <FileIcon :ext="getFileExt(doc.name, doc.fileType)" :size="36" />
        <div class="doc-hero-info">
          <h3 class="doc-hero-name" :title="doc.name">{{ doc.name }}</h3>
          <div class="doc-hero-meta">
            <span>{{ formatBytes(doc.fileSize) }}</span>
            <span class="meta-dot">·</span>
            <span>更新于 {{ formatDateTime(doc.updatedAt || doc.createdAt) }}</span>
            <template v-if="tags.length">
              <span class="meta-dot">·</span>
              <span
                v-for="tagName in tags"
                :key="tagName"
                class="hero-tag-chip"
                :style="tagStyle(tagName)"
              >
                {{ tagName }}
              </span>
            </template>
          </div>
        </div>
        <div class="doc-hero-actions">
          <el-button type="primary" plain size="small" class="doc-action-btn" @click="$emit('download', doc)">
            <el-icon><Download /></el-icon>
            下载
          </el-button>
          <el-button size="small" class="doc-action-btn" @click="$emit('rename', doc)">
            <el-icon><Pencil /></el-icon>
            重命名
          </el-button>
          <el-button size="small" class="doc-action-btn" @click="$emit('tags', doc)">
            <el-icon><Tag /></el-icon>
            标签
          </el-button>
          <el-button size="small" class="doc-action-btn" @click="$emit('move', doc)">
            <el-icon><FolderInput /></el-icon>
            移动到
          </el-button>
        </div>
      </div>
    </div>

    <!-- 内容区：Tabs -->
    <div class="detail-tabs-container">
      <el-tabs v-model="internalTab" class="detail-tabs">
        <el-tab-pane label="预览" name="preview">
          <div class="preview-wrapper" v-if="internalTab === 'preview'">
            <div class="preview-actions">
              <el-button link :icon="FullScreen" @click="openPreviewInNewTab">在新标签页打开全屏</el-button>
            </div>
            <div class="preview-section-body">
              <DocumentPreview :space-id="spaceId" :document-id="doc.id" />
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="评论" name="comments">
          <div class="comments-area" v-if="internalTab === 'comments'">
            <div class="comments-head">
              <span class="comments-title">评论</span>
              <span v-if="comments.length" class="comments-count">{{ visibleCount }}</span>
            </div>

            <div v-loading="loading" class="comment-list">
              <div v-if="earliestLoadedPage > 1" class="load-earlier-row">
                <el-button link size="small" @click="loadEarlier">加载更早的评论</el-button>
              </div>

              <EmptyState
                v-if="!loading && comments.length === 0"
                :icon="MessageSquare"
                title="还没有评论"
                description="来说两句，@ 你的伙伴一起讨论这份文档"
              />

              <div v-for="c in comments" :key="c.id" class="comment-item" tabindex="0" :data-comment-id="c.id">
                <template v-if="c.deleted === 1">
                  <span class="sys-avatar">
                    <UserRound :size="15" :stroke-width="2" />
                  </span>
                  <div class="comment-main">
                    <div class="comment-head">
                      <span class="comment-author sys-author">系统消息</span>
                      <span class="comment-time">{{ formatDateTime(c.createdAt) }}</span>
                    </div>
                    <p class="comment-content deleted">该评论已被删除</p>
                  </div>
                </template>

                <template v-else>
                  <el-avatar
                    :size="32"
                    class="comment-avatar"
                    :style="{ background: avatarColor(c.userName) }"
                  >
                    {{ (c.userName || 'U').charAt(0).toUpperCase() }}
                  </el-avatar>
                  <div class="comment-main">
                    <div class="comment-head">
                      <span class="comment-author">{{ c.userName }}</span>
                      <span class="comment-time">{{ formatDateTime(c.createdAt) }}</span>
                    </div>

                    <div
                      v-if="c.replyToId"
                      class="reply-quote reply-quote--link"
                      @click="scrollToComment(c.replyToId)"
                    >
                      回复 <span class="reply-at">@{{ c.replyToUserName || '已注销用户' }}</span>
                    </div>

                    <p class="comment-content">{{ c.content }}</p>

                    <div class="comment-actions">
                      <el-button link size="small" @click="replyTarget = c">回复</el-button>
                      <el-button
                        v-if="canDelete(c)"
                        link
                        type="danger"
                        size="small"
                        @click="handleDelete(c)"
                      >
                        删除
                      </el-button>
                    </div>
                  </div>
                </template>
              </div>
            </div>

            <div class="comment-editor">
              <div v-if="replyTarget" class="replying-bar">
                <span class="replying-text">回复 <span class="reply-at">@{{ replyTarget.userName }}</span></span>
                <el-button link size="small" @click="replyTarget = null">取消</el-button>
              </div>
              <el-input
                v-model="content"
                type="textarea"
                :rows="3"
                maxlength="1000"
                resize="none"
                :placeholder="replyTarget ? `回复 @${replyTarget.userName}...` : '写下你的评论...'"
                @keydown.enter.ctrl.prevent="handleSubmit"
              />
              <div class="editor-footer">
                <span class="editor-hint">Ctrl + Enter 发送</span>
                <el-button
                  type="primary"
                  size="small"
                  :loading="submitting"
                  :disabled="!content.trim()"
                  @click="handleSubmit"
                >
                  发 送
                </el-button>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Download, FolderInput, MessageSquare, Pencil, Tag, UserRound } from 'lucide-vue-next'
import { View, FullScreen } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import FileIcon from '@/components/FileIcon.vue'
import DocumentPreview from '@/components/DocumentPreview.vue'
import { listCommentsApi, addCommentApi, deleteCommentApi } from '@/api/comment'
import { formatBytes, formatDateTime, getFileExt, getFileTypeColor } from '@/utils/format'
import { tagStyle } from '@/utils/tagColors'

const props = defineProps({
  spaceId: { type: [Number, String], required: true },
  doc: { type: Object, required: true },
  tags: { type: Array, default: () => [] },
  myRole: { type: String, default: '' },
  currentUserId: { type: [Number, String], default: null },
  activeTab: { type: String, default: 'preview' },
  showBackButton: { type: Boolean, default: true }
})

const emit = defineEmits(['close', 'download', 'rename', 'tags', 'move', 'update:activeTab'])

const router = useRouter()
const PAGE_SIZE = 100

const internalTab = ref(props.activeTab)

watch(() => props.activeTab, (val) => {
  if (val && val !== internalTab.value) {
    internalTab.value = val
  }
}, { immediate: true })

watch(internalTab, (val) => {
  emit('update:activeTab', val)
  if (val === 'comments' && comments.value.length === 0 && !loading.value) {
    loadComments()
  }
})

function openPreviewInNewTab() {
  const href = router.resolve({
    name: 'DocumentPreview',
    params: { spaceId: props.spaceId, documentId: props.doc.id }
  }).href
  window.open(href, '_blank')
}

const loading = ref(false)
const comments = ref([])
const content = ref('')
const submitting = ref(false)
const replyTarget = ref(null)
const earliestLoadedPage = ref(1)

// 草稿管理 (sessionStorage)
const draftKey = computed(() => `draft_${props.currentUserId}_${props.spaceId}_${props.doc?.id}`)

watch(() => props.doc?.id, (id) => {
  if (id) {
    const saved = sessionStorage.getItem(draftKey.value)
    content.value = saved || ''
    if (internalTab.value === 'comments') {
      loadComments()
    }
  }
}, { immediate: true })

watch(content, (val) => {
  if (val) {
    sessionStorage.setItem(draftKey.value, val)
  } else {
    sessionStorage.removeItem(draftKey.value)
  }
})

function canDelete(comment) {
  if (props.myRole === 'OWNER' || props.myRole === 'ADMIN') return true
  return comment.userId === props.currentUserId
}

// 计数只算未删除的评论 (对标图: "评论 8" 是有效评论数)
const visibleCount = computed(() => comments.value.filter((c) => c.deleted !== 1).length)

// 点击回复引用定位到原评论：未加载则先往前翻页，再滚动 + 高亮
async function scrollToComment(commentId) {
  if (!commentId) return

  // 原评论在当前列表中不存在 → 往前加载更早的评论直到命中或没有更早的
  while (!comments.value.some((c) => c.id === commentId) && earliestLoadedPage.value > 1) {
    await loadEarlier()
  }

  const el = document.querySelector(`[data-comment-id="${commentId}"]`)
  if (!el) return

  el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  el.classList.add('comment-item--highlight')
  setTimeout(() => el.classList.remove('comment-item--highlight'), 2000)
}

// 按用户名哈希取头像底色，同名同色 (对标图各人头像颜色不同)
const AVATAR_COLORS = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899', '#14b8a6']

function avatarColor(name) {
  const s = String(name || '')
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return AVATAR_COLORS[h % AVATAR_COLORS.length]
}

async function loadComments() {
  const reqId = props.doc.id
  loading.value = true
  replyTarget.value = null
  try {
    const first = await listCommentsApi(props.spaceId, reqId, 1, PAGE_SIZE)
    if (props.doc.id !== reqId) return // 请求响应后校验ID
    if (first.pages <= 1) {
      comments.value = first.records
      earliestLoadedPage.value = 1
    } else {
      const last = await listCommentsApi(props.spaceId, reqId, first.pages, PAGE_SIZE)
      if (props.doc.id !== reqId) return
      comments.value = last.records
      earliestLoadedPage.value = first.pages
    }
  } catch (err) {
    if (props.doc.id !== reqId) return
    comments.value = []
    earliestLoadedPage.value = 1
  } finally {
    if (props.doc.id === reqId) loading.value = false
  }
}

async function loadEarlier() {
  const reqId = props.doc.id
  const prev = earliestLoadedPage.value - 1
  if (prev < 1) return
  try {
    const page = await listCommentsApi(props.spaceId, reqId, prev, PAGE_SIZE)
    if (props.doc.id !== reqId) return
    comments.value = [...page.records, ...comments.value]
    earliestLoadedPage.value = prev
  } catch (err) {
    // 拦截器处理
  }
}

async function handleSubmit() {
  const text = content.value.trim()
  if (!text || submitting.value) return
  const reqId = props.doc.id
  submitting.value = true
  try {
    await addCommentApi(props.spaceId, reqId, {
      content: text,
      replyToId: replyTarget.value?.id ?? null
    })
    if (props.doc.id !== reqId) return
    content.value = ''
    sessionStorage.removeItem(draftKey.value)
    replyTarget.value = null
    await loadComments()
  } catch (err) {
    // 拦截器处理
  } finally {
    if (props.doc.id === reqId) submitting.value = false
  }
}

function handleDelete(comment) {
  ElMessageBox.confirm('确定删除这条评论吗？', '删除评论', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await deleteCommentApi(props.spaceId, props.doc.id, comment.id)
      ElMessage.success('评论已删除')
      await loadComments()
    } catch (err) {
      // 拦截器处理
    }
  }).catch(() => {})
}
</script>

<style scoped>
.detail-root {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 详情各区域占满右侧面板，优先把横向空间留给文档预览。 */
.detail-root > * {
  box-sizing: border-box;
  width: 100%;
}

/* 置顶文档卡 */
.doc-hero {
  padding: 0.6rem 1.4rem 0.8rem;
  border-bottom: 1px solid var(--app-border-soft);
  background: linear-gradient(180deg, #fafbff 0%, #ffffff 100%);
  flex-shrink: 0;
}

.back-btn {
  color: var(--app-text-muted);
  padding: 2px 6px;
  margin-bottom: 0.3rem;
  margin-left: -6px;
  font-size: 0.82rem;
}

.back-btn:hover {
  color: var(--app-accent);
}

.doc-hero-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.doc-hero-ext {
  font-size: 0.72rem;
  font-weight: 700;
  color: #ffffff;
  padding: 6px 8px;
  border-radius: 8px;
  letter-spacing: 0.5px;
  min-width: 38px;
  text-align: center;
  flex-shrink: 0;
}

.doc-hero-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.doc-hero-name {
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--app-text);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.doc-hero-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 0.75rem;
  color: var(--app-text-faint);
}

.meta-dot {
  color: var(--app-text-faint);
}

.hero-tag-chip {
  font-size: 0.68rem;
  font-weight: 500;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid transparent;
  white-space: nowrap;
}

.doc-hero-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.doc-action-btn {
  margin-left: 0 !important;
  border-radius: 8px;
}

/* Tabs 布局容器 */
.detail-tabs-container {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.detail-tabs {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.detail-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0;
}
.detail-tabs :deep(.el-tab-pane) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.detail-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0;
  background: var(--app-panel);
}

.detail-tabs :deep(.el-tabs__nav-scroll) {
  box-sizing: border-box;
  padding: 0 16px;
}

/* 预览 Tab 内容 */
.preview-wrapper {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  margin: 12px 16px 16px;
  border: 1px solid var(--app-border-soft);
  border-radius: 8px;
  overflow: hidden;
}

.preview-actions {
  display: flex;
  justify-content: flex-end;
  padding: 8px 16px;
  background: var(--app-panel);
  border-bottom: 1px solid var(--app-border-soft);
}

.preview-section-body {
  flex: 1;
  min-height: 0;
  background: var(--app-bg);
}

/* 评论区 */
.comments-area {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 1rem 0;
}

.comments-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 0.5rem;
  flex-shrink: 0;
}

.comments-title {
  font-size: 0.92rem;
  font-weight: 700;
  color: var(--app-text);
}

.comments-count {
  font-size: 0.72rem;
  font-weight: 600;
  color: var(--app-accent);
  background: var(--app-accent-weak);
  border-radius: 999px;
  padding: 1px 8px;
}

.comment-list {
  flex: 1;
  min-height: 120px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-right: 4px;
}

.load-earlier-row {
  display: flex;
  justify-content: center;
  padding: 2px 0 6px;
}

.comment-item {
  display: flex;
  gap: 10px;
  padding: 10px 8px;
  border-radius: 10px;
}

.comment-item:hover {
  background-color: var(--app-hover-soft);
}

.comment-avatar {
  color: #ffffff;
  font-weight: 600;
  flex-shrink: 0;
}

/* 系统消息占位头像 (对标图：无色描边小人) */
.sys-avatar {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--app-text-faint);
  flex-shrink: 0;
}

.sys-author {
  color: var(--app-text-2);
}

.comment-main {
  flex: 1;
  min-width: 0;
}

.comment-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 2px;
}

.comment-author {
  font-size: 0.83rem;
  font-weight: 600;
  color: var(--app-text-2);
}

.comment-time {
  font-size: 0.72rem;
  color: var(--app-text-faint);
}

/* 回复引用块：左竖线引用样式 (对标图) */
.reply-quote {
  font-size: 0.78rem;
  color: var(--app-text-muted);
  border-left: 3px solid var(--app-border);
  padding: 2px 10px;
  margin: 2px 0 6px;
}

/* 可点击定位到原评论的引用块 */
.reply-quote--link {
  cursor: pointer;
  transition: color 0.15s;
}

.reply-quote--link:hover {
  color: var(--app-accent);
}

/* 定位高亮：短暂闪烁后自动消退 */
.comment-item--highlight {
  background-color: var(--app-highlight);
  transition: background-color 0.3s;
}

.reply-at {
  color: var(--app-accent);
  font-weight: 500;
}

.comment-content {
  font-size: 0.88rem;
  color: var(--app-text);
  line-height: 1.55;
  margin: 0;
  word-break: break-word;
  white-space: pre-wrap;
}

.comment-content.deleted {
  color: var(--app-text-faint);
  font-style: italic;
}

.comment-actions {
  margin-top: 2px;
  display: flex;
  gap: 4px;
}

.comment-editor {
  border-top: 1px solid var(--app-border-soft);
  padding-top: 0.75rem;
  flex-shrink: 0;
}

/* 回复条：无底色一行，左文案右取消 (对标图) */
.replying-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 2px 8px;
}

.replying-text {
  font-size: 0.85rem;
  color: var(--app-text-2);
}

.editor-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.editor-hint {
  font-size: 0.72rem;
  color: var(--app-text-faint);
}

@media (max-width: 768px) {
  .doc-hero { padding: 0.7rem 0.9rem 0.9rem; }

  .detail-tabs :deep(.el-tabs__nav-scroll) { padding: 0 12px; }
  .preview-wrapper { margin: 8px; }

  .doc-hero-main {
    flex-wrap: wrap;
    gap: 10px;
  }

  .doc-hero-actions {
    width: 100%;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .doc-action-btn {
    width: 100%;
    justify-content: center;
  }

  .comments-area { padding: 0.7rem 0.9rem 0.9rem; }
  .editor-hint { display: none; }
}
</style>
