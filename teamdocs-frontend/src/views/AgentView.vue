<template>
  <section class="agent-root" aria-label="文档检索问答">
    <!-- 顶部紧凑工具条：空间切换 + 导航 + 会话状态与操作 -->
    <header class="agent-topbar">
      <div class="topbar-left">
        <span class="topbar-label">空间:</span>
        <select
          :value="chat.spaceId.value"
          class="topbar-space-select"
          aria-label="选择问答空间"
          @change="changeSpace($event.target.value)"
        >
          <option v-if="!spaces.length" :value="chat.spaceId.value">当前空间</option>
          <option v-for="space in spaces" :key="space.id" :value="String(space.id)">{{ space.name }}</option>
        </select>

        <RouterLink class="topbar-file-link" :to="`/spaces/${chat.spaceId.value}`" title="前往空间文件列表">
          <FolderOpen :size="13" />
          <span>文件</span>
        </RouterLink>

        <span class="topbar-divider" aria-hidden="true"></span>

        <span class="topbar-session-badge">{{ chat.sessionId.value ? '会话' : '新对话' }}</span>
        <span class="topbar-session-title" :title="currentSessionTitle">{{ currentSessionTitle }}</span>
      </div>

      <div class="topbar-right">
        <button
          type="button"
          class="topbar-btn"
          title="新建对话"
          @click="handleNewConversation"
        >
          <Plus :size="13" />
          <span>新对话</span>
        </button>

        <button
          type="button"
          :class="['topbar-btn', { active: rightSidebarOpen }]"
          :title="rightSidebarOpen ? '收起历史列表' : '展开历史列表'"
          @click="toggleRightSidebar"
        >
          <PanelRightClose v-if="rightSidebarOpen" :size="13" />
          <PanelRightOpen v-else :size="13" />
          <span>历史</span>
          <span v-if="chat.sessions.value.length" class="topbar-count">{{ chat.sessions.value.length }}</span>
        </button>

        <button
          v-if="chat.sessionId.value"
          type="button"
          class="topbar-btn btn-danger"
          :disabled="cannotDeleteSession(chat.sessionId.value)"
          :title="chat.running.value ? '请先停止当前回答' : '删除当前会话'"
          @click="deleteConversation(sessionOptions.find((session) => String(session.id) === chat.sessionId.value))"
        >
          <LoaderCircle v-if="chat.deletingCurrent.value" :size="13" class="agent-spin" />
          <Trash2 v-else :size="13" />
        </button>
      </div>
    </header>

    <!-- 主工作区：中间对话区 + 右侧历史侧栏 -->
    <div class="agent-body">
      <!-- 移动端侧栏遮罩 -->
      <div
        v-if="rightSidebarOpen && isMobile"
        class="agent-backdrop"
        @click="rightSidebarOpen = false"
      ></div>

      <!-- 中间主对话视口 -->
      <div class="agent-main">
        <!-- 顶部通知横幅：横跨工作区顶部，替代底部跳出的状态提示 -->
        <div v-if="showNetworkBanner" :class="['agent-top-banner', isNetworkError ? 'is-error' : 'is-warning']" role="status">
          <div class="banner-body">
            <component :is="isNetworkError ? AlertCircle : WifiOff" :size="15" class="banner-icon" />
            <span class="banner-text">{{ networkBannerText }}</span>
          </div>
          <div class="banner-actions">
            <button
              v-if="chat.connection.value === 'disconnected' || isReconnectingManual"
              type="button"
              class="banner-action-btn"
              :disabled="isReconnectingManual"
              @click="handleManualReconnect"
            >
              <LoaderCircle v-if="isReconnectingManual" :size="12" class="agent-spin" />
              <span>{{ isReconnectingManual ? '正在重连' : '立即重连' }}</span>
            </button>
            <button
              v-if="chat.pendingRequest.value && !chat.running.value && !chat.denied.value"
              type="button"
              class="banner-action-btn"
              :disabled="chat.submitting.value"
              @click="chat.send(true)"
            >
              重试提交
            </button>
            <button
              v-if="chat.denied.value"
              type="button"
              class="banner-action-btn"
              @click="chat.refresh"
            >
              刷新
            </button>
          </div>
        </div>

        <div ref="transcript" class="agent-transcript" @scroll="onScroll">
          <div v-if="chat.loadingHistory.value && !chat.visibleMessages.value.length" class="agent-loading" aria-label="加载对话中">
            <span></span><span></span><span></span>
          </div>

          <!-- 空白引导态：克制干练，去 AI 浮夸风 -->
          <div v-else-if="!chat.visibleMessages.value.length && !chat.denied.value" class="agent-empty">
            <div class="empty-header">
              <FileText :size="22" class="empty-icon" />
              <h3>空间文档知识检索</h3>
              <p>向当前空间已就绪文档提问，回答自动附带切片证据来源。</p>
            </div>
            <div class="empty-suggestions">
              <button type="button" @click="fillQuestion('阅读当前空间的资料，概括关键内容并引用来源。')">
                <Search :size="13" />
                <span>概括当前空间的核心资料内容</span>
                <ArrowUpRight :size="12" />
              </button>
              <button type="button" @click="fillQuestion('梳理当前空间中的架构设计与核心模块说明。')">
                <Search :size="13" />
                <span>梳理空间内的架构与模块设计</span>
                <ArrowUpRight :size="12" />
              </button>
            </div>
          </div>

          <!-- 消息流列表 -->
          <div v-else class="agent-feed">
            <button
              v-if="chat.historyPage.value < chat.historyPages.value"
              type="button"
              class="load-older-btn"
              :disabled="chat.loadingHistory.value"
              @click="loadOlder"
            >
              加载更早记录
            </button>

            <div role="log" aria-label="问答历史" aria-live="polite">
              <div
                v-for="message in chat.visibleMessages.value"
                :key="messageKey(message)"
                :class="['agent-turn', message.role === 'USER' ? 'is-user' : 'is-assistant']"
              >
                <!-- 头像 -->
                <div class="turn-avatar">
                  <el-avatar
                    v-if="message.role === 'USER'"
                    :size="36"
                    :src="userInfo?.avatar || undefined"
                    class="chat-avatar user-avatar"
                  >
                    {{ (userInfo?.nickname || userInfo?.username || '我').charAt(0).toUpperCase() }}
                  </el-avatar>
                  <div v-else class="chat-avatar bot-avatar" aria-hidden="true">
                    <Bot :size="20" />
                  </div>
                </div>

                <!-- 消息主体 -->
                <div class="turn-body">
                  <div class="message-meta">
                    <span class="sender-name">{{ message.role === 'USER' ? (userInfo?.nickname || userInfo?.username || '你') : '文档助手' }}</span>
                    <ShieldCheck v-if="message.masked" :size="13" class="shield-icon" title="隐私脱敏内容" />
                  </div>

                  <AgentReasoningPanel
                    v-if="message.role === 'ASSISTANT'"
                    :message="message"
                    :run-key="messageKey(message)"
                  />
                  <AgentProcessingState
                    v-if="message.role === 'ASSISTANT' && (!hasReasoning(message) || chat.snapshot.value?.tools?.length || chat.snapshot.value?.errorCode) && !message.masked && String(message.runId) === chat.activeRunId.value"
                    :run="chat.snapshot.value"
                  />

                  <article v-if="message.role !== 'ASSISTANT' || message.text?.trim()" :class="['agent-message', message.role === 'USER' ? 'is-user' : 'is-assistant', { 'is-masked': message.masked }]">
                    <!-- 消息正文 -->
                    <div class="message-content">
                      <div
                        v-if="message.role === 'ASSISTANT'"
                        class="agent-markdown"
                        v-html="renderMarkdown(message.text, message.masked ? [] : message.citations)"
                        @click="handleMessageContentClick($event, message.runId)"
                      ></div>
                      <div v-else class="user-bubble-body">
                        <!-- 提问关联的引用文件/目录 -->
                        <div v-if="hasMessageScope(message)" class="user-scope-refs">
                          <!-- 文件夹引用 -->
                          <div v-if="message.scope.kind === 'folder'" class="scope-ref-badge folder-badge" :title="`限定文件夹：${message.scope.name}`">
                            <Folder :size="12" class="badge-icon folder-icon" />
                            <span class="badge-text">{{ message.scope.name }}</span>
                          </div>

                          <!-- 单个文件引用 -->
                          <div v-else-if="!message.scope.files || message.scope.files.length <= 1" class="scope-ref-badge file-badge" :title="`引用文件：${getScopeSingleFileName(message.scope)}`">
                            <FileText :size="12" class="badge-icon file-icon" />
                            <span class="badge-text">{{ getScopeSingleFileName(message.scope) }}</span>
                          </div>

                          <!-- 多个文件引用：支持展开/收起 -->
                          <div v-else class="scope-ref-multibox">
                            <button
                              type="button"
                              class="scope-multibox-header"
                              :aria-expanded="isScopeExpanded(message.runId)"
                              :title="isScopeExpanded(message.runId) ? '点击收起已引用的文件列表' : '点击展开查看引用的文件列表'"
                              @click="toggleScopeExpand(message.runId)"
                            >
                              <FileText :size="12" class="badge-icon file-icon" />
                              <span class="multibox-title">已引用 {{ message.scope.files.length }} 个文件</span>
                              <ChevronUp v-if="isScopeExpanded(message.runId)" :size="12" class="expand-icon" />
                              <ChevronDown v-else :size="12" class="expand-icon" />
                            </button>

                            <!-- 展开的多个文件列表 -->
                            <div v-show="isScopeExpanded(message.runId)" class="scope-multibox-list">
                              <span
                                v-for="file in message.scope.files"
                                :key="file.id"
                                class="scope-multibox-item"
                                :title="file.name"
                              >
                                <FileText :size="11" class="item-icon" />
                                <span class="item-name">{{ file.name }}</span>
                              </span>
                            </div>
                          </div>
                        </div>

                        <p class="user-text">{{ message.text }}</p>
                      </div>
                    </div>

                    <ChatAttachmentList v-if="message.role === 'USER' && message.runId && !message.masked"
                      :key="`${chat.spaceId.value}:${message.runId}`" :space-id="chat.spaceId.value" :run-id="message.runId" />
                    <!-- 同一文件的引用合并展示，正文编号与后端来源保持不变 -->
                    <div v-if="!message.masked && message.citations?.length" class="citation-list" aria-label="参考来源">
                      <button
                        v-for="group in groupAgentCitations(message.citations)"
                        :key="group.key"
                        type="button"
                        class="citation-chip"
                        :disabled="chat.deletingCurrent.value"
                        :title="group.title"
                        :aria-label="`打开文档来源 ${group.number}：${group.documentName}，新窗口预览`"
                        @click="openCitation(message.runId, group.sources[0])"
                      >
                        <span class="chip-id">[{{ group.number }}]</span>
                        <span class="chip-doc" :title="group.documentName">{{ group.documentName }}</span>
                        <span v-if="group.location" class="chip-pos">{{ group.location }}</span>
                        <ArrowUpRight :size="12" class="chip-icon" />
                      </button>
                    </div>
                    <AnswerFeedback v-if="message.role === 'ASSISTANT' && !message.masked && message.runStatus === 'SUCCEEDED'"
                      :key="`${chat.spaceId.value}:${message.runId}`" :space-id="chat.spaceId.value" :run-id="message.runId" />
                  </article>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 悬浮跳到底部 -->
        <button v-if="hasNewContent" type="button" class="btn-scroll-bottom" @click="scrollToEnd">
          <span>查看最新</span>
          <ArrowDown :size="12" />
        </button>

        <!-- 紧凑单行提问框 (Composer) -->
        <div class="agent-composer" @dragover.prevent @drop.prevent="dropAttachments" @paste="pasteAttachments">
          <!-- 字数超出直接提示 -->
          <div v-if="chat.draft.value.length > 2000" class="composer-alert" role="alert">
            <AlertCircle :size="13" />
            <span>提问字数超出 2000 字上限（当前已达 {{ chat.draft.value.length }} 字），请精简后再提问</span>
          </div>

          <!-- 问答范围选择栏：与输入框居中对齐 -->
          <div class="composer-scope-bar">
            <AgentScopePicker :key="chat.spaceId.value" :space-id="chat.spaceId.value" v-model="chat.scope.value"
              :disabled="chat.running.value || chat.submitting.value || chat.denied.value" />
          </div>

          <!-- 附件报错提示 -->
          <div v-if="chat.attachmentError.value" class="composer-alert" role="alert">
            <AlertCircle :size="13" />
            <span>{{ chat.attachmentError.value }}</span>
          </div>

          <!-- 已添加附件预览条（与输入框居中对齐） -->
          <div v-if="chat.attachments.value.length" class="composer-attachments-preview">
            <span
              v-for="item in chat.attachments.value"
              :key="item.id"
              class="attachment-chip"
              :title="item.name"
            >
              <img v-if="item.previewUrl" :src="item.previewUrl" :alt="item.name" class="attachment-thumbnail" />
              <FileText v-else :size="13" class="attachment-chip-icon" />
              <span class="attachment-chip-name">{{ item.name }}</span>
              <button
                type="button"
                class="attachment-remove-btn"
                :disabled="chat.uploading.value || chat.submitting.value || chat.running.value"
                :aria-label="`移除附件 ${item.name}`"
                @click="chat.removeAttachment(item)"
              >
                <X :size="11" />
              </button>
            </span>
            <span class="attachment-hint">附件仅用于本次提问，不加入知识库</span>
          </div>

          <form class="composer-box" @submit.prevent="handleSend">
            <textarea
              id="agent-question"
              ref="input"
              v-model="chat.draft.value"
              rows="1"
              :disabled="chat.submitting.value || chat.denied.value || chat.deletingCurrent.value"
              placeholder="向空间知识库提问... (Enter 发送，Shift+Enter 换行，支持拖入/粘贴文件)"
              @keydown="handleKeydown"
              @input="adjustTextareaHeight"
            ></textarea>

            <div class="composer-actions">
              <!-- 附件图标按钮：放置在发送/停止按钮左侧 -->
              <label
                class="composer-btn btn-attachment"
                :class="{ 'is-disabled': chat.uploading.value || chat.running.value || chat.submitting.value || chat.denied.value }"
                :title="chat.uploading.value ? '附件上传中…' : '添加图片 / 文件（支持点击、拖入或粘贴）'"
              >
                <LoaderCircle v-if="chat.uploading.value" :size="15" class="agent-spin" />
                <Paperclip v-else :size="15" />
                <input
                  type="file"
                  multiple
                  accept=".png,.jpg,.jpeg,.webp,.pdf,.docx,.xlsx,.pptx,.csv,.tsv,.txt,.md,.json,.jsonl"
                  :disabled="chat.uploading.value || chat.running.value || chat.submitting.value || chat.denied.value"
                  class="sr-only-input"
                  @change="pickAttachments"
                />
              </label>

              <!-- 停止生成按钮 -->
              <button
                v-if="chat.running.value"
                type="button"
                class="composer-btn btn-stop"
                :disabled="chat.cancelling.value"
                title="停止生成"
                @click="chat.cancel"
              >
                <Square :size="13" />
                <span>停止</span>
              </button>

              <!-- 发送提问按钮 -->
              <button
                v-else
                type="submit"
                class="composer-btn btn-send"
                :disabled="(!chat.draft.value.trim() && !chat.attachments.value.length) || chat.uploading.value || chat.denied.value || chat.deletingCurrent.value || chat.submitting.value || chat.draft.value.length > 2000"
                title="发送提问 (Enter)"
              >
                <LoaderCircle v-if="chat.submitting.value" :size="15" class="agent-spin" />
                <ArrowUp v-else :size="16" />
              </button>
            </div>
          </form>
        </div>
      </div>

      <!-- 右侧可收起会话历史列表 -->
      <aside
        :class="['agent-sidebar', { 'is-collapsed': !rightSidebarOpen }]"
        aria-label="历史会话侧栏"
      >
        <div class="sidebar-content" v-show="rightSidebarOpen">
          <div class="sidebar-header">
            <span class="sidebar-heading">历史会话</span>
            <button
              type="button"
              class="icon-btn"
              title="收起侧栏"
              @click="rightSidebarOpen = false"
            >
              <PanelRightClose :size="14" />
            </button>
          </div>

          <div class="sidebar-action">
            <button
              type="button"
              class="btn-new-chat"
              @click="handleNewConversation"
            >
              <Plus :size="13" />
              <span>新建会话</span>
            </button>
          </div>

          <div class="sidebar-list-wrap">
            <p v-if="chat.sessionError.value" class="sidebar-error" role="status">
              {{ chat.sessionError.value }}
              <button type="button" @click="chat.loadSessions()">重试</button>
            </p>

            <div v-if="chat.loadingSessions.value && !chat.sessions.value.length" class="sidebar-skeleton" aria-label="加载中">
              <span v-for="n in 4" :key="n"></span>
            </div>

            <nav v-else class="sidebar-list" aria-label="会话列表">
              <div v-for="session in chat.sessions.value" :key="session.id" class="sidebar-row">
                <button
                  type="button"
                  :class="['session-item', { active: String(session.id) === chat.sessionId.value }]"
                  :aria-current="String(session.id) === chat.sessionId.value ? 'page' : undefined"
                  @click="handleSelectSession(session.id)"
                >
                  <span class="session-name" :title="session.title">{{ session.title }}</span>
                  <span class="session-date" :title="session.createdAt">{{ sessionDate(session.createdAt) }}</span>
                </button>
                <button
                  type="button"
                  class="session-del-btn"
                  :aria-label="`删除会话：${session.title}`"
                  :disabled="cannotDeleteSession(session.id)"
                  :title="String(session.id) === chat.sessionId.value && chat.running.value ? '请先停止回答' : '删除会话'"
                  @click="deleteConversation(session)"
                >
                  <LoaderCircle v-if="chat.deletingSessionId.value === String(session.id)" :size="13" class="agent-spin" />
                  <Trash2 v-else :size="13" />
                </button>
              </div>
            </nav>

            <p v-if="!chat.loadingSessions.value && !chat.sessions.value.length && !chat.sessionError.value" class="sidebar-empty">
              当前空间无历史会话
            </p>

            <button
              v-if="chat.sessionPage.value < chat.sessionPages.value"
              type="button"
              class="btn-load-more"
              :disabled="chat.loadingSessions.value"
              @click="chat.loadSessions(true)"
            >
              加载更多
            </button>
          </div>
        </div>
      </aside>
    </div>
    <AgentCitationEvidence
      :key="`${chat.spaceId.value}:${chat.sessionId.value}`"
      :space-id="chat.spaceId.value"
      :open="citationEvidence.open"
      :loading="citationEvidence.loading"
      :anchor="citationAnchor"
      :number="citationEvidence.number"
      :sources="citationEvidence.sources"
      @close="closeCitationEvidence()"
      @preview="previewEvidence"
      @invalid="invalidateCitationEvidence"
    />
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessageBox, ElMessage } from 'element-plus'
import {
  AlertCircle,
  ArrowDown,
  ArrowUp,
  ArrowUpRight,
  Bot,
  ChevronDown,
  ChevronUp,
  FileText,
  Folder,
  FolderOpen,
  LoaderCircle,
  PanelRightClose,
  PanelRightOpen,
  Paperclip,
  Plus,
  Search,
  ShieldCheck,
  Square,
  Trash2,
  WifiOff,
  X
} from 'lucide-vue-next'
import { useSpacesStore, useUserStore } from '@/stores'
import { useAgentChat } from '@/composables/useAgentChat'
import ChatAttachmentList from '@/components/ChatAttachmentList.vue'
import AgentScopePicker from '@/components/AgentScopePicker.vue'
import AnswerFeedback from '@/components/AnswerFeedback.vue'
import AgentProcessingState from '@/components/AgentProcessingState.vue'
import AgentReasoningPanel from '@/components/AgentReasoningPanel.vue'
import AgentCitationEvidence from '@/components/AgentCitationEvidence.vue'
import { isSendKey } from '@/utils/agentStream'
import { renderMarkdown } from '@/utils/markdown'
import { groupAgentCitations, findMessageCitation } from '@/utils/agentCitations'
import 'highlight.js/styles/atom-one-dark.css'

const route = useRoute()
const router = useRouter()
const { spaces } = storeToRefs(useSpacesStore())
const { userInfo } = storeToRefs(useUserStore())
const transcript = ref(null)
const input = ref(null)
const following = ref(true)
const hasNewContent = ref(false)

const citationAnchor = shallowRef(null)
const citationEvidence = ref({ open: false, loading: false, number: 1, runId: '', sourceIds: [], sources: [] })
let citationRequest = 0

let disposed = false
let reconnectTimer = null
const copyTimers = new Set()
const expandedScopeRuns = ref(new Set())

function hasMessageScope(message) {
  return Boolean(
    message.role === 'USER' &&
    message.scope &&
    (message.scope.name || message.scope.files?.length)
  )
}

function getScopeSingleFileName(scope) {
  return scope?.files?.[0]?.name || scope?.name || '文档'
}

function isScopeExpanded(runId) {
  return expandedScopeRuns.value.has(String(runId))
}

function toggleScopeExpand(runId) {
  const key = String(runId)
  if (expandedScopeRuns.value.has(key)) {
    expandedScopeRuns.value.delete(key)
  } else {
    expandedScopeRuns.value.add(key)
  }
}

const isMobile = ref(typeof window !== 'undefined' && window.innerWidth <= 820)
const rightSidebarOpen = ref(typeof window !== 'undefined' && window.innerWidth > 960)
const isReconnectingManual = ref(false)

function handleResize() {
  const mobile = window.innerWidth <= 820

  if (mobile !== isMobile.value) {
    isMobile.value = mobile

    if (mobile) rightSidebarOpen.value = false
  }
}

const chat = useAgentChat((sessionId) => router.replace({ name: 'SpaceAgent', params: { spaceId: chat.spaceId.value, sessionId } }))

const sessionOptions = computed(() => chat.sessionId.value && !chat.sessions.value.some((row) => String(row.id) === chat.sessionId.value)
  ? [...chat.sessions.value, { id: chat.sessionId.value, title: '当前会话' }] : chat.sessions.value)

const currentSessionTitle = computed(() => {
  if (!chat.sessionId.value) return '新对话'

  const current = chat.sessions.value.find((s) => String(s.id) === chat.sessionId.value)

  return current?.title || '当前会话'
})

const showNetworkBanner = computed(() => {
  return Boolean(chat.error.value)
    || chat.connection.value === 'reconnecting'
    || chat.connection.value === 'disconnected'
    || (isReconnectingManual.value && chat.connection.value === 'connecting')
})

const isNetworkError = computed(() => {
  return Boolean(chat.error.value) || chat.connection.value === 'disconnected'
})

const networkBannerText = computed(() => {
  if (chat.error.value) return chat.error.value

  if (isReconnectingManual.value && chat.connection.value === 'connecting') return '正在尝试重新连接网络…'

  if (chat.connection.value === 'reconnecting') return '网络连接中断，正在自动重连中… 后台回答不会重复提交'

  if (chat.connection.value === 'disconnected') return '网络连接已断开，回答仍在后台继续生成。'

  return ''
})

async function handleManualReconnect() {
  isReconnectingManual.value = true

  try {
    await chat.reconnect()
  } finally {
    if (!disposed) {
      clearTimeout(reconnectTimer)
      reconnectTimer = setTimeout(() => {
        isReconnectingManual.value = false
      }, 1200)
    }
  }
}

/** 按空间、会话和轮次保持消息节点稳定。 */
function messageKey(message) {
  return `${chat.spaceId.value}:${chat.sessionId.value}:${message.runId ?? message.id}:${message.role}`
}

/** 仅把真实且未脱敏的思考作为过程展示。 */
function hasReasoning(message) {
  return !message.masked && typeof message.reasoningContent === 'string' && !!message.reasoningContent.trim()
}

watch(() => chat.visibleMessages.value, (messages) => {
  if (!citationEvidence.value.open) return

  const valid = citationEvidence.value.sourceIds.every((id, index) => {
    const source = findMessageCitation(messages, citationEvidence.value.runId, id)

    if (!source) return false

    if (citationEvidence.value.loading) return true

    const previous = citationEvidence.value.sources[index]

    return previous && ['documentId', 'chunkId', 'parseVersion', 'imageSource', 'imageLabel', 'excerpt'].every((key) => source[key] === previous[key])
  })

  if (!valid) closeCitationEvidence(false)
})

watch(() => [route.params.spaceId, route.params.sessionId], ([spaceId, sessionId]) => {
  closeCitationEvidence(false)
  following.value = true
  hasNewContent.value = false
  void chat.setContext(spaceId, sessionId)
}, { immediate: true })

watch(() => [chat.visibleMessages.value.length, chat.visibleMessages.value.map((message) => `${message.text?.length || 0}:${message.reasoningContent?.length || 0}`).join(','), chat.snapshot.value?.status, chat.snapshot.value?.toolCalls, chat.snapshot.value?.tools?.map((tool) => tool.status).join(',')], async () => {
  const spaceId = chat.spaceId.value
  const sessionId = chat.sessionId.value

  await nextTick()

  if (disposed || spaceId !== chat.spaceId.value || sessionId !== chat.sessionId.value) return

  if (following.value) scrollToEnd()
  else hasNewContent.value = true
})

function changeSpace(spaceId) { router.push({ name: 'SpaceAgent', params: { spaceId } }) }

function selectSession(sessionId) { router.push({ name: 'SpaceAgent', params: { spaceId: chat.spaceId.value, sessionId: sessionId || undefined } }) }

function newConversation() {
  if (!chat.sessionId.value) void chat.setContext(chat.spaceId.value, '', true)
  else selectSession('')
}

function handleNewConversation() {
  newConversation()

  if (isMobile.value) rightSidebarOpen.value = false
}

function handleSelectSession(sessionId) {
  selectSession(sessionId)

  if (isMobile.value) rightSidebarOpen.value = false
}

function toggleRightSidebar() {
  rightSidebarOpen.value = !rightSidebarOpen.value
}

function cannotDeleteSession(sessionId) {
  return Boolean(chat.deletingSessionId.value)
    || (String(sessionId) === chat.sessionId.value && (chat.running.value || chat.submitting.value))
}

async function deleteConversation(session) {
  if (!session || cannotDeleteSession(session.id)) return

  const spaceId = chat.spaceId.value
  const sessionId = String(session.id)

  try {
    await ElMessageBox.confirm(
      `确定删除“${session.title}”吗？会话记录将删除，文档不受影响。`,
      '删除会话',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }

  if (spaceId !== chat.spaceId.value) return

  const deleted = await chat.deleteSession(sessionId)

  if (!deleted) return

  if (String(route.params.spaceId) === spaceId && String(route.params.sessionId || '') === sessionId) {
    await router.replace({ name: 'SpaceAgent', params: { spaceId } })
    await nextTick()
    input.value?.focus()
  }
}

function fillQuestion(question) {
  chat.draft.value = question
  input.value?.focus()
  nextTick(() => adjustTextareaHeight())
}

function adjustTextareaHeight() {
  const el = input.value

  if (!el) return

  el.style.height = 'auto'
  el.style.height = `${Math.min(Math.max(el.scrollHeight, 26), 130)}px`
}

watch(() => chat.draft.value, () => {
  nextTick(() => adjustTextareaHeight())
})

function pickAttachments(event) {
  void chat.uploadFiles(event.target.files)
  event.target.value = ''
}

function dropAttachments(event) {
  void chat.uploadFiles(event.dataTransfer?.files)
}

function pasteAttachments(event) {
  const files = Array.from(event.clipboardData?.files || [])
  if (files.length) { event.preventDefault(); void chat.uploadFiles(files) }
}

function handleSend() {
  if (chat.draft.value.length > 2000) {
    ElMessage.warning('提问字数超出 2000 字上限，请精简后再提问')

    return
  }

  if (!chat.running.value) void chat.send()
}

function handleKeydown(event) {
  if (!isSendKey(event)) return

  event.preventDefault()
  handleSend()
}

function onScroll() {
  const el = transcript.value

  if (el) following.value = el.scrollHeight - el.clientHeight - el.scrollTop < 60

  if (following.value) hasNewContent.value = false
}

function scrollToEnd() {
  if (transcript.value) transcript.value.scrollTop = transcript.value.scrollHeight

  following.value = true
  hasNewContent.value = false
}

async function loadOlder() {
  const spaceId = chat.spaceId.value
  const sessionId = chat.sessionId.value
  const el = transcript.value
  const before = el?.scrollHeight || 0
  const top = el?.scrollTop || 0

  following.value = false
  await chat.loadMessages(true)
  await nextTick()

  if (disposed || spaceId !== chat.spaceId.value || sessionId !== chat.sessionId.value) return

  if (el) el.scrollTop = top + el.scrollHeight - before

  hasNewContent.value = false
}

async function openCitation(runId, source) {
  if (chat.deletingCurrent.value) return

  const popup = window.open('about:blank', '_blank')

  if (popup) popup.opener = null

  try {
    const current = await chat.verifyCitation(runId, source.id)
    const target = router.resolve({ name: 'DocumentPreview', params: { spaceId: chat.spaceId.value, documentId: current.documentId } })

    if (popup) popup.location.replace(target.href)
    else router.push(target.fullPath)
  } catch { popup?.close() }
}

function closeCitationEvidence(restoreFocus = true) {
  citationRequest++

  const anchor = citationAnchor.value

  citationEvidence.value = { open: false, loading: false, number: 1, runId: '', sourceIds: [], sources: [] }
  citationAnchor.value = null

  if (restoreFocus && anchor?.isConnected) anchor.focus({ preventScroll: true })
}

async function handleMessageContentClick(event, runId) {
  const copyBtn = event.target.closest?.('.code-copy-btn')

  if (copyBtn && event.currentTarget.contains(copyBtn)) {
    event.preventDefault()

    const code = decodeURIComponent(copyBtn.dataset.code || '')

    if (code) {
      try {
        await navigator.clipboard.writeText(code)

        if (disposed || !copyBtn.isConnected) return

        const originalText = copyBtn.textContent

        copyBtn.textContent = '已复制'
        copyBtn.classList.add('copied')

        const timer = setTimeout(() => {
          copyTimers.delete(timer)
          copyBtn.textContent = originalText
          copyBtn.classList.remove('copied')
        }, 1500)

        copyTimers.add(timer)
      } catch {
        ElMessage.error('复制失败')
      }
    }

    return
  }

  const badge = event.target.closest?.('button[data-citation-ids]')

  if (!badge || !event.currentTarget.contains(badge) || chat.deletingCurrent.value) return

  event.preventDefault()

  const ids = [...new Set((badge.dataset.citationIds || '').split(','))]

  if (!ids.length || ids.some((id) => !/^C\d+$/.test(id))) return

  const message = chat.visibleMessages.value.find((item) => item.role === 'ASSISTANT' && String(item.runId) === String(runId))
  const group = groupAgentCitations(message?.citations).find((item) => ids.every((id) => item.sourceIds.includes(id)))

  if (!group || message.masked) return

  const requestId = ++citationRequest

  citationAnchor.value = badge
  citationEvidence.value = { open: true, loading: true, number: group.number, runId, sourceIds: ids, sources: [] }

  try {
    const sources = await chat.verifyCitations(runId, ids)

    if (requestId !== citationRequest) return

    citationEvidence.value = { ...citationEvidence.value, loading: false, sources }
  } catch {
    if (requestId === citationRequest) closeCitationEvidence(false)
  }
}

/** 图片失权或过期时关闭旧证据并重新核验回答。 */
function invalidateCitationEvidence() {
  closeCitationEvidence(false)
  void chat.refresh()
}

function previewEvidence(source) {
  const runId = citationEvidence.value.runId

  closeCitationEvidence(false)
  void openCitation(runId, source)
}

function sessionDate(value) {
  if (!value) return ''

  const date = new Date(value)

  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

function onFocus() { void chat.refresh() }

onMounted(() => {
  window.addEventListener('focus', onFocus)
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  disposed = true
  clearTimeout(reconnectTimer)

  for (const timer of copyTimers) clearTimeout(timer)

  copyTimers.clear()
  closeCitationEvidence(false)
  window.removeEventListener('focus', onFocus)
  window.removeEventListener('resize', handleResize)
})
</script>

<style scoped>
/* 彻底隐藏所有区域上下及左右滚动条，同时保留滚轮与触控滑动能力 */
.agent-root,
.agent-root *,
.agent-body,
.agent-main,
.agent-transcript,
.agent-feed,
.agent-sidebar,
.sidebar-content,
.sidebar-list-wrap,
.composer-box textarea,
.agent-markdown :deep(pre),
.agent-markdown :deep(table) {
  scrollbar-width: none !important;
  -ms-overflow-style: none !important;
}

.agent-root::-webkit-scrollbar,
.agent-root *::-webkit-scrollbar,
.agent-body::-webkit-scrollbar,
.agent-main::-webkit-scrollbar,
.agent-transcript::-webkit-scrollbar,
.agent-feed::-webkit-scrollbar,
.agent-sidebar::-webkit-scrollbar,
.sidebar-content::-webkit-scrollbar,
.sidebar-list-wrap::-webkit-scrollbar,
.composer-box textarea::-webkit-scrollbar,
.agent-markdown :deep(pre)::-webkit-scrollbar,
.agent-markdown :deep(table)::-webkit-scrollbar {
  display: none !important;
  width: 0 !important;
  height: 0 !important;
}

/* 根容器：满高，无大外边距浪费 */
.agent-root {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  color: var(--app-text);
  background: var(--app-panel);
}

/* 顶部紧凑工具条 (44px) */
.agent-topbar {
  height: 44px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 16px;
  border-bottom: 1px solid var(--app-border);
  background: var(--app-panel-soft);
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}

.topbar-label {
  font-size: .75rem;
  color: var(--app-text-muted);
}

.topbar-space-select {
  height: 28px;
  border: 1px solid var(--app-border);
  border-radius: 5px;
  background: var(--app-panel);
  color: var(--app-text);
  padding: 0 6px;
  font: inherit;
  font-size: .8125rem;
  outline: none;
}

.topbar-file-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--app-border);
  border-radius: 5px;
  background: var(--app-panel);
  color: var(--app-text-2);
  font-size: .75rem;
  text-decoration: none;
}

.topbar-file-link:hover {
  background: var(--app-hover);
  color: var(--app-text);
}

.topbar-divider {
  width: 1px;
  height: 14px;
  background: var(--app-border);
  margin: 0 2px;
}

.topbar-session-badge {
  padding: 1px 5px;
  border-radius: 3px;
  font-size: .7rem;
  font-weight: 500;
  background: var(--app-hover);
  color: var(--app-text-muted);
  flex-shrink: 0;
}

.topbar-session-title {
  font-size: .8125rem;
  font-weight: 600;
  color: var(--app-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.topbar-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--app-border);
  border-radius: 5px;
  background: var(--app-panel);
  color: var(--app-text-2);
  font-size: .75rem;
  cursor: pointer;
  transition: all .12s ease;
}

.topbar-btn:hover {
  background: var(--app-hover);
  color: var(--app-text);
}

.topbar-btn.active {
  background: var(--app-hover);
  border-color: var(--app-border-strong, #cbd5e1);
  color: var(--app-text);
}

.topbar-btn.btn-danger:hover:not(:disabled) {
  color: var(--el-color-danger);
  border-color: var(--el-color-danger-light-7);
}

.topbar-count {
  font-size: .7rem;
  color: var(--app-text-muted);
}

/* 主体工作区 */
.agent-body {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
}

/* 中间主对话视口 */
.agent-main {
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 消息滚动区：占满主区宽度，极大提升横向空间利用率 */
.agent-transcript {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  overscroll-behavior: contain;
  padding: 18px 30px;
}

.agent-feed {
  width: 100%;
  max-width: 1056px;
  margin: 0 auto;
}

/* 对话轮次布局：左右头像自然排开，兼具空间质感与信息密度 (+10% 缩放) */
.agent-turn {
  display: flex;
  gap: 14px;
  margin-bottom: 26px;
}

.agent-turn.is-user {
  flex-direction: row-reverse;
}

.turn-avatar {
  flex-shrink: 0;
  padding-top: 2px;
}

.chat-avatar {
  width: 36px;
  height: 36px;
  border-radius: 9px;
  box-sizing: border-box;
}

.user-avatar {
  font-size: .875rem;
  font-weight: 600;
  background: var(--app-accent);
  color: #fff;
  border-radius: 50%;
}

.bot-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--app-panel-soft);
  border: 1px solid var(--app-border);
  color: var(--app-accent);
  border-radius: 9px;
}

.turn-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.agent-turn.is-user .turn-body {
  align-items: flex-end;
}

.agent-turn.is-assistant .turn-body {
  align-items: flex-start;
}

.message-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: .8125rem;
  font-weight: 600;
  color: var(--app-text-muted);
  margin-bottom: 7px;
}

.is-user .message-meta {
  justify-content: flex-end;
}

.is-assistant .message-meta .sender-name {
  color: var(--app-text);
}

.shield-icon {
  color: var(--app-accent);
}

/* 消息卡片 */
.agent-message {
  width: 100%;
}

.agent-message.is-user {
  width: fit-content;
  max-width: min(790px, 85%);
}

.agent-message.is-user .message-content {
  background: var(--app-panel-soft);
  border: 1px solid var(--app-border);
  border-radius: 13px 2px 13px 13px;
  padding: 9px 16px;
}

.user-bubble-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.user-scope-refs {
  margin-bottom: 2px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.scope-ref-badge {
  display: inline-flex;
  align-items: center;
  align-self: flex-start;
  gap: 5px;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: .75rem;
  font-weight: 500;
  background: var(--app-panel);
  border: 1px solid var(--app-border);
  color: var(--app-text-2);
  max-width: 100%;
}

.scope-ref-badge .badge-icon {
  flex-shrink: 0;
}

.scope-ref-badge .folder-icon {
  color: var(--el-color-warning, #d97706);
}

.scope-ref-badge .file-icon {
  color: var(--app-accent, #2563eb);
}

.scope-ref-badge .badge-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 260px;
}

.scope-ref-multibox {
  display: flex;
  flex-direction: column;
  align-self: flex-start;
  border-radius: 6px;
  border: 1px solid var(--app-border);
  background: var(--app-panel);
  overflow: hidden;
  max-width: 100%;
}

.scope-multibox-header {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  border: none;
  background: transparent;
  color: var(--app-text-2);
  font-size: .75rem;
  font-weight: 500;
  cursor: pointer;
  text-align: left;
  transition: background .12s, color .12s;
  width: 100%;
}

.scope-multibox-header:hover {
  background: var(--app-hover);
  color: var(--app-text);
}

.scope-multibox-header .multibox-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scope-multibox-header .expand-icon {
  flex-shrink: 0;
  color: var(--app-text-muted);
}

.scope-multibox-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 8px 6px;
  border-top: 1px solid var(--app-border);
  background: var(--app-panel-soft);
  max-height: 180px;
  overflow-y: auto;
}

.scope-multibox-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 4px;
  border-radius: 3px;
  font-size: .715rem;
  color: var(--app-text-muted);
  transition: background .1s, color .1s;
}

.scope-multibox-item:hover {
  background: var(--app-hover);
  color: var(--app-text);
}

.scope-multibox-item .item-icon {
  flex-shrink: 0;
  color: var(--app-accent, #2563eb);
}

.scope-multibox-item .item-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 260px;
}

.user-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: .9625rem;
  line-height: 1.65;
  color: var(--app-text);
}

/* 助手回复排版 (+10% 缩放) */
.agent-message.is-assistant .message-content {
  padding: 2px 0 4px;
}

.agent-markdown {
  font-size: 1rem;
  line-height: 1.75;
  color: var(--app-text);
  word-break: break-word;
}

.agent-markdown :deep(> *:first-child) { margin-top: 0; }

.agent-markdown :deep(> *:last-child) { margin-bottom: 0; }

.agent-markdown :deep(p) { margin: 0 0 12px 0; }

.agent-markdown :deep(h1),
.agent-markdown :deep(h2),
.agent-markdown :deep(h3),
.agent-markdown :deep(h4) {
  margin: 16px 0 10px 0;
  font-weight: 600;
  line-height: 1.35;
  color: var(--app-text);
}

.agent-markdown :deep(h1) { font-size: 1.25rem; }

.agent-markdown :deep(h2) { font-size: 1.15rem; }

.agent-markdown :deep(h3) { font-size: 1.05rem; }

.agent-markdown :deep(h4) { font-size: .98rem; }

.agent-markdown :deep(ul),
.agent-markdown :deep(ol) {
  margin: 8px 0 12px 0;
  padding-left: 22px;
}

.agent-markdown :deep(li) {
  margin-bottom: 4px;
  line-height: 1.7;
}

.agent-markdown :deep(li > p) { margin: 0; }

.agent-markdown :deep(blockquote) {
  margin: 10px 0 12px 0;
  padding: 8px 14px;
  border-left: 3px solid var(--app-border-strong, #94a3b8);
  background: var(--app-panel-soft);
  color: var(--app-text-2);
  font-size: .925rem;
}

.agent-markdown :deep(code) {
  padding: 2px 6px;
  border-radius: 4px;
  font-family: ui-monospace, Menlo, Monaco, Consolas, monospace;
  font-size: .85em;
  background: var(--app-hover);
  color: var(--app-text);
}

/* 代码块容器：深色现代化编辑器风格，圆角微阴影 */
.agent-markdown :deep(.code-block-wrapper) {
  margin: 14px 0 18px 0;
  border-radius: 9px;
  overflow: hidden;
  background: #282c34;
  border: 1px solid #3e4451;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
}

/* 代码块顶栏：左侧三点装饰，右上方显示语言类型与复制按钮 */
.agent-markdown :deep(.code-block-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 14px;
  background: #21252b;
  border-bottom: 1px solid #333842;
  user-select: none;
}

.agent-markdown :deep(.code-dots) {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.agent-markdown :deep(.code-dots .dot) {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}

.agent-markdown :deep(.code-dots .dot-red) { background: #ff5f56; }

.agent-markdown :deep(.code-dots .dot-yellow) { background: #ffbd2e; }

.agent-markdown :deep(.code-dots .dot-green) { background: #27c93f; }

.agent-markdown :deep(.code-header-right) {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 右上方语言类型标签 */
.agent-markdown :deep(.code-block-lang) {
  font-size: .78rem;
  font-weight: 600;
  font-family: ui-monospace, Menlo, Monaco, Consolas, monospace;
  color: #abb2bf;
  letter-spacing: .5px;
  text-transform: uppercase;
}

/* 复制代码按钮 */
.agent-markdown :deep(.code-copy-btn) {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 9px;
  border-radius: 4px;
  border: 1px solid #4b5263;
  background: #282c34;
  color: #abb2bf;
  font-size: .76rem;
  cursor: pointer;
  transition: all .15s ease;
}

.agent-markdown :deep(.code-copy-btn:hover) {
  background: #3e4451;
  color: #ffffff;
  border-color: #5c6370;
}

.agent-markdown :deep(.code-copy-btn.copied) {
  color: #98c379;
  border-color: #98c379;
}

/* 代码正文 pre */
.agent-markdown :deep(pre) {
  margin: 0;
  padding: 0;
  background: transparent;
  overflow-x: auto;
}

.agent-markdown :deep(pre code.hljs) {
  display: block;
  padding: 13px 16px;
  background: transparent;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: .915rem;
  line-height: 1.6;
  color: #abb2bf;
}

.agent-markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 12px 0;
  font-size: .875rem;
}

.agent-markdown :deep(th),
.agent-markdown :deep(td) {
  padding: 7px 12px;
  border: 1px solid var(--app-border);
  text-align: left;
}

.agent-markdown :deep(th) {
  background: var(--app-panel-soft);
  font-weight: 600;
}

.agent-markdown :deep(a) {
  color: var(--app-accent);
  text-decoration: underline;
}

/* 行内可点击引用徽章 [C1]：极简学术角标 */
.agent-markdown :deep(.inline-citation-badge) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  vertical-align: 1px;
  margin: 0 2px;
  padding: 0 5px;
  height: 18px;
  font-size: .75rem;
  font-weight: 600;
  line-height: 1;
  color: var(--app-accent);
  background: var(--app-panel-soft);
  border: 1px solid var(--app-border);
  border-radius: 4px;
  cursor: pointer;
  transition: all .1s ease;
}

.agent-markdown :deep(.inline-citation-badge:hover) {
  background: var(--app-accent);
  color: #ffffff;
  border-color: var(--app-accent);
}

/* 来源卡片列表：紧凑条形列表 */
.citation-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.citation-chip {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  max-width: 100%;
  gap: 6px;
  min-height: 28px;
  padding: 4px 10px;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  background: var(--app-panel-soft);
  color: var(--app-text-2);
  font-size: .8125rem;
  cursor: pointer;
  transition: background .12s, border-color .12s;
}

.citation-chip:hover {
  background: var(--app-hover);
  border-color: var(--app-border-strong, #94a3b8);
}

.chip-id {
  overflow-wrap: anywhere;
  max-width: 100%;
  font-weight: 700;
  color: var(--app-accent);
}

.chip-doc {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
  color: var(--app-text);
}

.chip-pos {
  color: var(--app-text-muted);
  font-size: .75rem;
}

.chip-icon {
  color: var(--app-text-muted);
}

/* 空态设计：横向网格展开，充分利用横向空间 */
.agent-empty {
  max-width: 820px;
  margin: 60px auto 0;
  padding: 0 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.empty-header {
  margin-bottom: 20px;
}

.empty-icon {
  color: var(--app-text-muted);
  margin-bottom: 10px;
}

.empty-header h3 {
  margin: 0 0 6px 0;
  font-size: 1.15rem;
  font-weight: 600;
  color: var(--app-text);
}

.empty-header p {
  margin: 0;
  font-size: .875rem;
  color: var(--app-text-muted);
}

.empty-suggestions {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 10px;
  width: 100%;
}

.empty-suggestions button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  background: var(--app-panel);
  color: var(--app-text-2);
  font-size: .875rem;
  cursor: pointer;
  transition: all .12s ease;
}

.empty-suggestions button > span {
  flex: 1;
  text-align: left;
}

.empty-suggestions button:hover {
  background: var(--app-hover);
  border-color: var(--app-border-strong, #94a3b8);
  color: var(--app-text);
}

/* 现代化紧凑聊天输入框：单行横向排版，高度自适应，占满横向宽度 */
.agent-composer {
  padding: 10px 30px 14px;
  background: var(--app-panel);
  flex-shrink: 0;
}

.composer-alert {
  max-width: 1056px;
  margin: 0 auto 6px auto;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border-radius: 6px;
  background: var(--el-color-danger-light-9, #fef2f2);
  border: 1px solid var(--el-color-danger-light-7, #fecaca);
  color: var(--el-color-danger, #ef4444);
  font-size: .8125rem;
}

.composer-scope-bar {
  max-width: 1056px;
  margin: 0 auto 6px auto;
  display: flex;
  align-items: center;
}

.composer-attachments-preview {
  max-width: 1056px;
  margin: 0 auto 8px auto;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.attachment-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-radius: 6px;
  border: 1px solid var(--app-border);
  background: var(--app-panel);
  font-size: .75rem;
  color: var(--app-text);
  max-width: 240px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.attachment-thumbnail {
  width: 20px;
  height: 20px;
  object-fit: cover;
  border-radius: 3px;
  flex-shrink: 0;
}

.attachment-chip-icon {
  flex-shrink: 0;
  color: var(--app-text-muted);
}

.attachment-chip-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: .75rem;
}

.attachment-remove-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--app-text-muted);
  cursor: pointer;
  padding: 0;
  margin-left: 2px;
  flex-shrink: 0;
  transition: all .12s;
}

.attachment-remove-btn:hover:not(:disabled) {
  background: var(--app-hover);
  color: var(--el-color-danger, #ef4444);
}

.attachment-hint {
  font-size: .72rem;
  color: var(--app-text-muted);
}

.composer-box {
  width: 100%;
  max-width: 1056px;
  margin: 0 auto;
  border: 1px solid var(--app-border);
  border-radius: 9px;
  background: var(--app-panel);
  padding: 7px 10px 7px 14px;
  box-sizing: border-box;
  display: flex;
  align-items: flex-end;
  gap: 8px;
  transition: border-color .15s, box-shadow .15s;
}

.composer-box:focus-within {
  border-color: var(--app-border-strong, #94a3b8);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.composer-box textarea {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  resize: none;
  min-height: 26px;
  max-height: 130px;
  font: inherit;
  font-size: .95rem;
  line-height: 1.5;
  color: var(--app-text);
  padding: 2px 0;
  margin: 0;
  box-sizing: border-box;
}

.composer-box textarea::placeholder {
  color: var(--app-text-muted);
}

.composer-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: 6px;
}

.composer-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 7px;
  border: 1px solid var(--app-border);
  background: var(--app-panel-soft);
  color: var(--app-text-2);
  cursor: pointer;
  transition: all .12s ease;
  padding: 0;
}

.composer-btn:hover:not(:disabled) {
  background: var(--app-hover);
  color: var(--app-text);
  border-color: var(--app-border-strong, #94a3b8);
}

.composer-btn:disabled {
  opacity: .5;
  cursor: not-allowed;
}

.composer-btn.btn-attachment {
  cursor: pointer;
  position: relative;
}

.composer-btn.btn-attachment.is-disabled {
  opacity: .5;
  cursor: not-allowed;
  pointer-events: none;
}

.sr-only-input {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.composer-btn.btn-send:not(:disabled) {
  background: var(--app-accent);
  border-color: var(--app-accent);
  color: #fff;
}

.composer-btn.btn-stop {
  width: auto;
  padding: 0 10px;
  height: 32px;
  gap: 4px;
  font-size: .8rem;
  background: #ef4444;
  border-color: #ef4444;
  color: #fff;
}

/* 顶部通知横幅：横跨视口顶部，不破坏底部输入区 */
.agent-top-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 18px;
  gap: 12px;
  font-size: .8125rem;
  line-height: 1.4;
  border-bottom: 1px solid var(--app-border);
  flex-shrink: 0;
  transition: all .2s ease;
  z-index: 10;
}

.agent-top-banner.is-warning {
  background: var(--el-color-warning-light-9, #fffbeb);
  border-bottom-color: var(--el-color-warning-light-7, #fde68a);
  color: var(--el-color-warning-dark-2, #b45309);
}

.agent-top-banner.is-error {
  background: var(--el-color-danger-light-9, #fef2f2);
  border-bottom-color: var(--el-color-danger-light-7, #fecaca);
  color: var(--el-color-danger-dark-2, #b91c1c);
}

.agent-top-banner .banner-body {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.agent-top-banner .banner-icon {
  flex-shrink: 0;
}

.agent-top-banner .banner-text {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-top-banner .banner-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.agent-top-banner .banner-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: 4px;
  font-size: .75rem;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid currentColor;
  background: transparent;
  color: inherit;
  transition: background .15s, opacity .15s;
}

.agent-top-banner .banner-action-btn:hover:not(:disabled) {
  background: rgba(0, 0, 0, 0.06);
}

.agent-top-banner .banner-action-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 右侧历史侧栏 (240px) */
.agent-sidebar {
  width: 240px;
  flex-shrink: 0;
  border-left: 1px solid var(--app-border);
  background: var(--app-panel-soft);
  display: flex;
  flex-direction: column;
  transition: width .2s ease;
  overflow: hidden;
}

.agent-sidebar.is-collapsed {
  width: 0;
  min-width: 0;
  border-left-color: transparent;
  pointer-events: none;
}

.sidebar-content {
  width: 240px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px 0 12px;
  border-bottom: 1px solid var(--app-border);
}

.sidebar-heading {
  font-size: .75rem;
  font-weight: 600;
  color: var(--app-text-muted);
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--app-text-muted);
  cursor: pointer;
}

.icon-btn:hover {
  background: var(--app-hover);
  color: var(--app-text);
}

.sidebar-action {
  padding: 8px 10px 4px;
}

.btn-new-chat {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  width: 100%;
  height: 28px;
  border: 1px dashed var(--app-border);
  border-radius: 5px;
  background: var(--app-panel);
  color: var(--app-text-2);
  font-size: .75rem;
  cursor: pointer;
  transition: all .12s;
}

.btn-new-chat:hover {
  border-color: var(--app-border-strong, #94a3b8);
  color: var(--app-text);
  background: var(--app-hover);
}

.sidebar-list-wrap {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sidebar-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sidebar-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 24px;
  align-items: center;
  border-radius: 5px;
  transition: background .1s;
}

.session-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 6px 8px;
  border: 0;
  border-radius: 5px;
  text-align: left;
  background: transparent;
  color: var(--app-text-2);
  font-size: .75rem;
  cursor: pointer;
}

.session-item:hover {
  background: var(--app-hover);
  color: var(--app-text);
}

.session-item.active {
  background: var(--app-hover);
  color: var(--app-text);
  font-weight: 600;
}

.session-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-date {
  font-size: .7rem;
  color: var(--app-text-muted);
  font-weight: normal;
}

.session-del-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 28px;
  border: 0;
  background: transparent;
  color: var(--app-text-muted);
  cursor: pointer;
  opacity: 0;
}

.sidebar-row:hover .session-del-btn,
.sidebar-row:focus-within .session-del-btn {
  opacity: 1;
}

.session-del-btn:hover:not(:disabled) {
  color: var(--el-color-danger);
}

.sidebar-empty {
  font-size: .75rem;
  text-align: center;
  margin: 16px 0;
  color: var(--app-text-muted);
}

.btn-load-more {
  border: 0;
  background: transparent;
  color: var(--app-text-muted);
  font-size: .75rem;
  margin-top: 4px;
  cursor: pointer;
}

.btn-load-more:hover {
  color: var(--app-text);
}

.load-older-btn {
  display: block;
  margin: 0 auto 14px;
  border: 0;
  background: transparent;
  color: var(--app-text-muted);
  font-size: .75rem;
  cursor: pointer;
}

.load-older-btn:hover {
  color: var(--app-text);
}

.btn-scroll-bottom {
  align-self: center;
  flex-shrink: 0;
  margin: 4px 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--app-border);
  border-radius: 13px;
  color: var(--app-text-2);
  background: var(--app-panel);
  font-size: .75rem;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  cursor: pointer;
}

.agent-loading { max-width: 600px; margin: 20px auto; }

.agent-loading span, .sidebar-skeleton span { display: block; height: 12px; border-radius: 3px; background: var(--app-hover); margin: 10px 0; }

.agent-loading span:nth-child(2) { width: 70%; }

.agent-loading span:nth-child(3) { width: 45%; }

.sidebar-skeleton { padding: 0 8px; }

.agent-spin { animation: agent-spin 1s linear infinite; }

@keyframes agent-spin { to { transform: rotate(360deg); } }

/* 移动端遮罩 */
.agent-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.24);
  z-index: 15;
}

@media (max-width: 820px) {
  .agent-sidebar {
    position: absolute;
    right: 0;
    top: 0;
    bottom: 0;
    z-index: 20;
    box-shadow: -2px 0 12px rgba(0, 0, 0, 0.08);
  }
}

@media (max-width: 600px) {
  .agent-topbar { padding: 0 10px; }

  .topbar-session-title { max-width: 100px; }

  .topbar-btn span { display: none; }

  .agent-transcript { padding: 12px 14px; }

  .agent-composer { padding: 6px 12px 10px; }

  .composer-hint { display: none; }

  .agent-message.is-user { max-width: 90%; }

  .chip-doc { max-width: 130px; }
}

@media (prefers-reduced-motion: reduce) {
  .agent-spin { animation: none; }

  .agent-root * { transition: none !important; }
}
</style>
