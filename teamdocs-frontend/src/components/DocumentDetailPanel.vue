<template>
  <div class="detail-root">
    <!-- 置顶的文档卡 -->
    <div class="doc-hero">
      <el-button class="back-btn" text @click="$emit('close')">
        <el-icon><ArrowLeft /></el-icon>
        返回列表
      </el-button>

      <div class="doc-hero-main">
        <FileIcon :ext="getFileExt(doc.name, doc.fileType)" :size="46" />
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
        <el-button type="primary" plain class="download-btn" @click="$emit('download', doc)">
          <el-icon><Download /></el-icon>
          下 载
        </el-button>
      </div>
    </div>

    <!-- 评论区占满剩余空间 -->
    <div class="comments-area">
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

        <div v-for="c in comments" :key="c.id" class="comment-item">
          <!-- 已删除：系统消息式占位 (对标图) -->
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

          <!-- 正常评论 -->
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

              <!-- 回复引用块 (对标图：左竖线引用样式) -->
              <div v-if="c.replyToId" class="reply-quote">
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

      <!-- 输入区 (对标图：回复条在输入框上方一行，右侧取消) -->
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
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Download, MessageSquare, UserRound } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import FileIcon from '@/components/FileIcon.vue'
import { listCommentsApi, addCommentApi, deleteCommentApi } from '@/api/comment'
import { formatBytes, formatDateTime, getFileExt, getFileTypeColor } from '@/utils/format'
import { tagStyle } from '@/utils/tagColors'

const props = defineProps({
  spaceId: { type: [Number, String], required: true },
  doc: { type: Object, required: true },
  tags: { type: Array, default: () => [] },
  myRole: { type: String, default: '' },
  currentUserId: { type: [Number, String], default: null }
})

defineEmits(['close', 'download'])

const PAGE_SIZE = 100

const loading = ref(false)
const comments = ref([])
const content = ref('')
const submitting = ref(false)
const replyTarget = ref(null)
const earliestLoadedPage = ref(1)

watch(() => props.doc?.id, (id) => {
  if (id) loadComments()
}, { immediate: true })

function canDelete(comment) {
  if (props.myRole === 'OWNER' || props.myRole === 'ADMIN') return true
  return comment.userId === props.currentUserId
}

// 计数只算未删除的评论 (对标图: "评论 8" 是有效评论数)
const visibleCount = computed(() => comments.value.filter((c) => c.deleted !== 1).length)

// 按用户名哈希取头像底色，同名同色 (对标图各人头像颜色不同)
const AVATAR_COLORS = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899', '#14b8a6']

function avatarColor(name) {
  const s = String(name || '')
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return AVATAR_COLORS[h % AVATAR_COLORS.length]
}

async function loadComments() {
  loading.value = true
  content.value = ''
  replyTarget.value = null
  try {
    // 后端按时间正序分页，最新在最后一页：先取第 1 页拿总页数
    const first = await listCommentsApi(props.spaceId, props.doc.id, 1, PAGE_SIZE)
    if (first.pages <= 1) {
      comments.value = first.records
      earliestLoadedPage.value = 1
    } else {
      const last = await listCommentsApi(props.spaceId, props.doc.id, first.pages, PAGE_SIZE)
      comments.value = last.records
      earliestLoadedPage.value = first.pages
    }
  } catch (err) {
    comments.value = []
    earliestLoadedPage.value = 1
  } finally {
    loading.value = false
  }
}

async function loadEarlier() {
  const prev = earliestLoadedPage.value - 1
  if (prev < 1) return
  try {
    const page = await listCommentsApi(props.spaceId, props.doc.id, prev, PAGE_SIZE)
    comments.value = [...page.records, ...comments.value]
    earliestLoadedPage.value = prev
  } catch (err) {
    // 拦截器处理
  }
}

async function handleSubmit() {
  const text = content.value.trim()
  if (!text || submitting.value) return
  submitting.value = true
  try {
    await addCommentApi(props.spaceId, props.doc.id, {
      content: text,
      replyToId: replyTarget.value?.id ?? null
    })
    content.value = ''
    replyTarget.value = null
    await loadComments()
  } catch (err) {
    // 拦截器处理
  } finally {
    submitting.value = false
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

/* 置顶文档卡 */
.doc-hero {
  padding: 0.9rem 1.4rem 1.1rem;
  border-bottom: 1px solid var(--app-border-soft);
  background: linear-gradient(180deg, #fafbff 0%, #ffffff 100%);
  flex-shrink: 0;
}

.back-btn {
  color: var(--app-text-muted);
  padding: 4px 8px;
  margin-bottom: 0.5rem;
  margin-left: -8px;
}

.back-btn:hover {
  color: var(--app-accent);
}

.doc-hero-main {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.doc-hero-ext {
  font-size: 0.78rem;
  font-weight: 700;
  color: #ffffff;
  padding: 8px 10px;
  border-radius: 10px;
  letter-spacing: 0.5px;
  min-width: 44px;
  text-align: center;
  flex-shrink: 0;
}

.doc-hero-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.doc-hero-name {
  font-size: 1.05rem;
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
  font-size: 0.78rem;
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

.download-btn {
  flex-shrink: 0;
  border-radius: 8px;
}

/* 评论区 */
.comments-area {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0.9rem 1.4rem 1.1rem;
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

  .doc-hero-main {
    flex-wrap: wrap;
    gap: 10px;
  }

  .download-btn {
    width: 100%;
    justify-content: center;
  }

  .comments-area { padding: 0.7rem 0.9rem 0.9rem; }
  .editor-hint { display: none; }
}
</style>
