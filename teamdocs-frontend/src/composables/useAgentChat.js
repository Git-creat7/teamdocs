import { ref, computed, onBeforeUnmount } from 'vue'
import * as api from '@/api/agent'
import { uploadChatAttachment, deleteChatAttachment } from '@/api/chatAttachments'
import { isTerminalRun, sameAgentContext, abortableDelay } from '@/utils/agentStream'
import { agentError, accessLost } from '@/utils/agentErrors'

const SCOPE_STORAGE_KEY = 'teamdocs_agent_scopes'
const fallbackStorage = new Map()

function getStorage() {
  try {
    if (typeof localStorage !== 'undefined') return localStorage
  } catch {}
  return {
    getItem(key) { return fallbackStorage.get(key) || null },
    setItem(key, val) { fallbackStorage.set(key, String(val)) },
    removeItem(key) { fallbackStorage.delete(key) }
  }
}

function readStoredScopes() {
  try {
    const storage = getStorage()
    const raw = storage.getItem(SCOPE_STORAGE_KEY)
    if (!raw) return {}
    const data = JSON.parse(raw)
    return data && typeof data === 'object' ? data : {}
  } catch {
    return {}
  }
}

function persistScope(runId, scopeData) {
  if (!runId || !scopeData) return
  try {
    const storage = getStorage()
    const scopes = readStoredScopes()
    scopes[String(runId)] = scopeData
    const keys = Object.keys(scopes)
    if (keys.length > 500) {
      for (const key of keys.slice(0, keys.length - 500)) {
        delete scopes[key]
      }
    }
    storage.setItem(SCOPE_STORAGE_KEY, JSON.stringify(scopes))
  } catch {}
}

function getStoredScope(runId) {
  if (!runId) return null
  const scopes = readStoredScopes()
  return scopes[String(runId)] || null
}

export function useAgentChat(onSessionCreated) {
  const spaceId = ref('')
  const sessionId = ref('')
  const activeRunId = ref('')
  const sessions = ref([])

  const messages = ref([])
  const snapshot = ref(null)
  const reasoningByRun = ref(new Map())
  const scopeByRun = ref(new Map(Object.entries(readStoredScopes())))
  const draft = ref('')
  const attachments = ref([])
  const uploading = ref(false)
  const attachmentError = ref('')
  const attachmentPreviews = new Set()
  const clearAttachments = () => {
    for (const url of attachmentPreviews) URL.revokeObjectURL(url)
    attachmentPreviews.clear()
    attachments.value = []
  }
  const scope = ref(null)
  const pendingQuestion = ref(null)
  const pendingRequest = ref(null)

  const error = ref('')
  const sessionError = ref('')
  const connection = ref('idle')
  const submitting = ref(false)
  const cancelling = ref(false)
  const deletingSessionId = ref('')
  const loadingHistory = ref(false)
  const loadingSessions = ref(false)
  const denied = ref(false)

  const sessionPage = ref(1)
  const sessionPages = ref(1)
  const historyPage = ref(1)
  const historyPages = ref(1)

  let generation = 0
  let listRequest = 0
  let historyRequest = 0
  let historyRefreshRequested = false
  let streamRevision = 0
  let deletionRequest = 0
  let deletionController = null
  let disposed = false
  let requests = new AbortController()
  let listController = new AbortController()
  let streamController = null

  const ticket = () => ({ generation, spaceId: spaceId.value, sessionId: sessionId.value })

  const current = (value) => !disposed && value.generation === generation && value.spaceId === spaceId.value && value.sessionId === sessionId.value

  const running = computed(() => !!activeRunId.value && (!snapshot.value || !isTerminalRun(snapshot.value.status)))

  const deletingCurrent = computed(() => !!deletingSessionId.value && deletingSessionId.value === sessionId.value)
  const visibleMessages = computed(() => {
    const rows = [...messages.value]

    for (const [runId, reasoning] of reasoningByRun.value) {
      if (!reasoning || rows.some((row) => row.role === 'ASSISTANT' && String(row.runId) === runId)) continue

      const questionIndex = rows.findIndex((row) => row.role === 'USER' && String(row.runId) === runId)

      // 缓存不能把已掉出当前历史页的旧轮次重新追加到末尾。
      if (questionIndex < 0) continue

      const message = { id: `pending-answer-${runId}`, runId, role: 'ASSISTANT', text: '', masked: false, citations: [] }

      rows.splice(questionIndex + 1, 0, message)
    }

    if (pendingQuestion.value && !rows.some((row) => row.role === 'USER' && String(row.runId) === String(pendingQuestion.value.runId))) {
      const answerIndex = rows.findIndex((row) => row.role === 'ASSISTANT' && String(row.runId) === String(pendingQuestion.value.runId))

      rows.splice(answerIndex < 0 ? rows.length : answerIndex, 0, pendingQuestion.value)
    }

    if (activeRunId.value) {
      const index = rows.findIndex((row) => row.role === 'ASSISTANT' && String(row.runId) === activeRunId.value)
      const saved = rows[index]
      const answer = saved?.masked ? saved : snapshot.value?.answer || saved || {
        id: `pending-answer-${activeRunId.value}`, runId: activeRunId.value, role: 'ASSISTANT', text: '', masked: false, citations: []
      }
      const message = withReasoning(answer, snapshot.value || { status: answer.runStatus || 'QUEUED' })

      if (index < 0) rows.push(message)
      else rows[index] = message
    }

    return rows.map((message) => withReasoning(message))
  })

  /** 只展示本页面收到且未被遮蔽的思考。 */
  function withReasoning(message, run = null) {
    const key = String(message.runId)
    const blocked = message.role === 'ASSISTANT' && (message.masked || (reasoningByRun.value.has(key) && reasoningByRun.value.get(key) === null))
    const cached = message.role === 'ASSISTANT' && !blocked ? reasoningByRun.value.get(key) : null
    const cachedScope = message.role === 'USER' ? (message.scope || scopeByRun.value.get(key) || getStoredScope(key) || null) : null

    return {
      ...message,
      ...(cachedScope ? { scope: cachedScope } : {}),
      ...(blocked ? { masked: true, text: message.masked ? message.text : '资料已更新或不可访问，原回答已隐藏。', citations: [] } : {}),
      reasoningContent: cached?.reasoningContent ?? null,
      reasoningDurationMs: cached?.reasoningDurationMs ?? null,
      reasoningTruncated: cached?.reasoningTruncated ?? false,
      runStatus: run?.status ?? cached?.runStatus ?? message.runStatus ?? null
    }
  }

  /** 暂存完整思考快照，null 标记已被权威答复遮蔽。 */
  function rememberReasoning(value) {
    const key = String(value.id)

    if (value.answer?.masked) {
      reasoningByRun.value.set(key, null)

      return
    }

    if (reasoningByRun.value.has(key) && reasoningByRun.value.get(key) === null) return

    const previous = reasoningByRun.value.get(key)
    const content = value.reasoningContent ?? value.answer?.reasoningContent
    const received = typeof content === 'string' && !!content.trim()

    if (!received && !previous) return

    reasoningByRun.value.set(key, {
      reasoningContent: received ? content : previous.reasoningContent,
      reasoningDurationMs: received
        ? (value.reasoningDurationMs !== undefined ? value.reasoningDurationMs : value.answer?.reasoningDurationMs ?? null)
        : previous?.reasoningDurationMs ?? null,
      reasoningTruncated: received ? value.reasoningTruncated ?? value.answer?.reasoningTruncated ?? false : previous.reasoningTruncated,
      runStatus: value.status
    })
  }

  /** 同步清除快照中的脱敏思考投影。 */
  function normalizeRun(value) {
    const blocked = value.answer?.masked || (reasoningByRun.value.has(String(value.id)) && reasoningByRun.value.get(String(value.id)) === null)

    return {
      ...value,
      ...(blocked ? { reasoningContent: null, reasoningDurationMs: null, reasoningTruncated: false } : {}),
      answer: value.answer ? withReasoning({ ...value.answer, runId: value.answer.runId ?? value.id }, value) : null
    }
  }

  function stopObserving() {
    streamRevision++
    streamController?.abort()
    streamController = null
    connection.value = 'idle'
  }

  function stopDeleting() {
    deletionRequest++
    deletionController?.abort()
    deletionController = null
    deletingSessionId.value = ''
  }

  function loseAccess(cause) {
    stopDeleting()
    generation++
    requests.abort()
    requests = new AbortController()
    listRequest++
    listController.abort()
    listController = new AbortController()
    stopObserving()
    sessions.value = []
    sessionError.value = ''
    loadingSessions.value = false
    cancelling.value = false
    messages.value = []
    reasoningByRun.value.clear()
    scopeByRun.value.clear()
    snapshot.value = null
    pendingQuestion.value = null
    pendingRequest.value = null
    activeRunId.value = ''
    draft.value = ''
    submitting.value = false
    loadingHistory.value = false
    denied.value = true
    error.value = agentError(cause)
  }

  async function loadSessions(append = false) {
    if (denied.value) return

    const expectedSpace = spaceId.value
    const requestId = ++listRequest
    const page = append ? sessionPage.value + 1 : 1

    loadingSessions.value = true
    sessionError.value = ''

    try {
      const result = await api.listAgentSessions(expectedSpace, page, listController.signal)

      if (disposed || expectedSpace !== spaceId.value || requestId !== listRequest) return

      const rows = append ? [...sessions.value, ...result.records] : result.records

      sessions.value = [...new Map(rows.map((row) => [String(row.id), row])).values()]
      sessionPage.value = page
      sessionPages.value = result.pages
    } catch (cause) {
      if (!disposed && expectedSpace === spaceId.value && requestId === listRequest && cause.name !== 'AbortError' && cause.code !== 'ERR_CANCELED') {
        if (accessLost(cause)) loseAccess(cause)
        else sessionError.value = agentError(cause)
      }
    } finally {
      if (expectedSpace === spaceId.value && requestId === listRequest) loadingSessions.value = false
    }
  }

  async function setContext(space, session, force = false) {
    const nextSpace = String(space || '')
    const nextSession = String(session || '')

    if (!force && nextSpace === spaceId.value && nextSession === sessionId.value) return

    const changedSpace = nextSpace !== spaceId.value
    if (changedSpace) {
      scope.value = null
      clearAttachments()
      attachmentError.value = ''
    }

    if (changedSpace) stopDeleting()

    generation++
    requests.abort()
    requests = new AbortController()
    stopObserving()
    spaceId.value = nextSpace
    sessionId.value = nextSession
    activeRunId.value = ''
    messages.value = []
    reasoningByRun.value.clear()
    const stored = readStoredScopes()
    for (const [k, v] of Object.entries(stored)) {
      if (!scopeByRun.value.has(k)) scopeByRun.value.set(k, v)
    }
    snapshot.value = null
    pendingQuestion.value = null
    pendingRequest.value = null
    draft.value = ''
    error.value = ''
    denied.value = false
    submitting.value = false
    cancelling.value = false
    loadingHistory.value = false
    historyPage.value = 1
    historyPages.value = 1
    historyRefreshRequested = false

    if (changedSpace || force) {
      listController.abort()
      listController = new AbortController()
      sessions.value = []

      if (/^[1-9]\d*$/.test(nextSpace)) void loadSessions()
    }

    if (!/^[1-9]\d*$/.test(nextSpace) || (nextSession && !/^[1-9]\d*$/.test(nextSession))) {
      denied.value = true
      error.value = '空间或会话地址无效，请从侧栏重新进入。'

      return
    }

    if (nextSession) await loadMessages()
  }

  async function deleteSession(id) {
    if (disposed || denied.value || deletingSessionId.value) return false

    const target = String(id)
    const expectedSpace = spaceId.value

    if (target === sessionId.value && (running.value || submitting.value)) {
      error.value = '请先等待提交完成或停止当前回答，再删除会话。'

      return false
    }

    const requestId = ++deletionRequest
    const controller = new AbortController()

    deletionController = controller
    deletingSessionId.value = target
    error.value = ''

    let deleted = false

    if (target === sessionId.value) {
      generation++
      requests.abort()
      requests = new AbortController()
      stopObserving()
      loadingHistory.value = false
      historyRefreshRequested = false
    }

    const valid = () => !disposed && expectedSpace === spaceId.value && requestId === deletionRequest

    try {
      await api.deleteAgentSession(expectedSpace, target, controller.signal)

      if (!valid()) return false

      listRequest++
      sessions.value = sessions.value.filter((row) => String(row.id) !== target)

      if (sessionId.value === target) await setContext(expectedSpace, '')

      void loadSessions()
      deleted = true

      return true
    } catch (cause) {
      if (!valid() || cause.name === 'AbortError' || cause.code === 'ERR_CANCELED') return false

      if (accessLost(cause) && !/会话不存在/.test(cause.message || '')) loseAccess(cause)
      else error.value = agentError(cause)

      return false
    } finally {
      if (requestId === deletionRequest) {
        deletingSessionId.value = ''
        deletionController = null

        if (!deleted && !denied.value && sessionId.value === target) void loadMessages()
      }
    }
  }

  async function loadMessages(append = false) {
    if (!sessionId.value || deletingCurrent.value) return

    if (loadingHistory.value) {
      if (!append) historyRefreshRequested = true

      return
    }

    const expected = ticket()
    const requestId = ++historyRequest
    const page = append ? historyPage.value + 1 : 1

    loadingHistory.value = true

    try {
      const result = await api.listAgentMessages(expected.spaceId, expected.sessionId, page, requests.signal)

      if (!current(expected) || requestId !== historyRequest) return

      const rows = append ? [...messages.value, ...result.records] : result.records

      for (const row of result.records) {
        if (row.role === 'ASSISTANT' && row.masked) reasoningByRun.value.set(String(row.runId), null)
      }

      messages.value = [...new Map(rows.map((row) => [String(row.id), withReasoning(row)])).values()].sort((a, b) => Number(a.id) - Number(b.id))

      const maskedAnswer = messages.value.find((row) => row.role === 'ASSISTANT' && String(row.runId) === activeRunId.value && row.masked)

      if (snapshot.value && maskedAnswer) snapshot.value = normalizeRun({ ...snapshot.value, answer: maskedAnswer })

      historyPage.value = page
      historyPages.value = result.pages

      if (pendingQuestion.value && messages.value.some((row) => row.role === 'USER' && String(row.runId) === String(pendingQuestion.value.runId))) pendingQuestion.value = null

      if (!activeRunId.value) {
        const latest = [...messages.value].reverse().find((row) => row.role === 'USER' && row.runId)

        if (latest) {
          activeRunId.value = String(latest.runId)
          void reconnect()
        }
      }
    } catch (cause) {
      if (!current(expected) || cause.name === 'AbortError' || cause.code === 'ERR_CANCELED') return

      if (accessLost(cause)) loseAccess(cause)
      else error.value = agentError(cause)
    } finally {
      if (current(expected) && requestId === historyRequest) {
        loadingHistory.value = false

        if (historyRefreshRequested) { historyRefreshRequested = false; void loadMessages() }
      }
    }
  }

  function applySnapshot(value) {
    if (String(value.id) !== activeRunId.value || String(value.sessionId) !== sessionId.value) return

    rememberReasoning(value)
    snapshot.value = normalizeRun(value)

    if (snapshot.value.answer?.id != null) {
      const index = messages.value.findIndex((message) => message.role === 'ASSISTANT' && String(message.runId) === activeRunId.value)

      if (index < 0) messages.value = [...messages.value, snapshot.value.answer]
      else messages.value = messages.value.map((message, position) => position === index ? snapshot.value.answer : message)
    }

    if (isTerminalRun(value.status)) {
      connection.value = 'idle'
      void loadMessages()
    }
  }

  async function observe() {
    stopObserving()

    const expected = ticket()
    const scope = { spaceId: expected.spaceId, sessionId: expected.sessionId, runId: activeRunId.value }
    const revision = ++streamRevision
    const controller = new AbortController()

    streamController = controller

    const valid = () => current(expected) && revision === streamRevision && String(scope.runId) === activeRunId.value

    let attempt = 0

    while (valid() && !controller.signal.aborted) {
      connection.value = attempt ? 'reconnecting' : 'connecting'

      try {
        await api.observeAgentRun(scope, controller.signal, (event) => {
          if (!valid() || !sameAgentContext(scope, event)) return false

          if (event.type === 'stream_error') throw new Error(event.errorCode || 'STREAM_UNAVAILABLE')

          connection.value = 'connected'

          if (event.snapshot) applySnapshot(event.snapshot)

          return running.value
        })

        if (!valid() || !running.value) return

        throw new Error('实时连接已断开')
      } catch (cause) {
        if (!valid() || cause.name === 'AbortError') return

        if (accessLost(cause)) { loseAccess(cause); return }

        connection.value = 'reconnecting'

        try {
          const value = await api.getAgentRun(scope.spaceId, scope.runId, controller.signal)

          if (!valid()) return

          applySnapshot(value)

          if (!running.value) return
        } catch (readError) {
          if (!valid() || readError.name === 'AbortError' || readError.code === 'ERR_CANCELED') return

          if (accessLost(readError)) { loseAccess(readError); return }
        }

        if (++attempt >= 5) {
          connection.value = 'disconnected'
          error.value = '连接暂时无法恢复，后台运行不会重复提交。可点击重新连接查看结果。'

          return
        }

        try { await abortableDelay(Math.min(5000, 1000 * 2 ** (attempt - 1)), controller.signal) }
        catch { return }
      }
    }
  }

  async function reconnect() {
    if (!activeRunId.value || denied.value || deletingCurrent.value) return

    stopObserving()

    const expected = ticket()
    const run = activeRunId.value

    connection.value = 'connecting'
    error.value = ''

    try {
      const value = await api.getAgentRun(expected.spaceId, run, requests.signal)

      if (!current(expected) || run !== activeRunId.value) return

      applySnapshot(value)

      if (running.value) void observe()
    } catch (cause) {
      if (!current(expected) || cause.name === 'AbortError' || cause.code === 'ERR_CANCELED') return

      if (accessLost(cause)) loseAccess(cause)
      else void observe()
    }
  }

  async function uploadFiles(files) {
    if (uploading.value || submitting.value || running.value || denied.value || deletingCurrent.value) return
    const selected = Array.from(files || [])
    if (attachments.value.length + selected.length > 4) { attachmentError.value = '每次最多4个附件'; return }
    const allowed = /\.(png|jpe?g|webp|pdf|docx|xlsx|pptx|csv|tsv|txt|md|json|jsonl)$/i
    if (selected.some(file => !allowed.test(file.name) || !file.size || file.size > 5 * 1024 * 1024)) {
      attachmentError.value = '支持图片、PDF、Word、Excel、PPT、文本与 CSV；每个附件不能为空且不超过5MB'
      return
    }
    const expected = ticket(), signal = requests.signal
    uploading.value = true; attachmentError.value = ''
    try {
      for (const file of selected) {
        const item = await uploadChatAttachment(expected.spaceId, file, signal)
        if (!current(expected)) return
        if (file instanceof Blob && file.type.startsWith('image/') && URL.createObjectURL) {
          item.previewUrl = URL.createObjectURL(file)
          attachmentPreviews.add(item.previewUrl)
        }
        attachments.value.push(item)
      }
    } catch (failure) { if (current(expected)) attachmentError.value = failure.message || '附件上传失败' }
    finally { uploading.value = false }
  }

  async function removeAttachment(item) {
    if (uploading.value || submitting.value || running.value) return
    const expected = ticket()
    uploading.value = true
    try {
      await deleteChatAttachment(expected.spaceId, item.id, requests.signal)
      if (current(expected)) {
        if (item.previewUrl) { URL.revokeObjectURL(item.previewUrl); attachmentPreviews.delete(item.previewUrl) }
        attachments.value = attachments.value.filter(row => row.id !== item.id)
      }
    } catch (failure) { if (current(expected)) attachmentError.value = failure.message || '附件删除失败' }
    finally { uploading.value = false }
  }

  async function send(retry = false) {
    if (submitting.value || uploading.value || running.value || denied.value || deletingCurrent.value) return

    const question = retry ? pendingRequest.value?.question : (draft.value.trim() || (attachments.value.length ? '请阅读并说明所附文件的内容。' : ''))

    if (!question || question.length > 2000) return

    submitting.value = true
    error.value = ''

    let expected = ticket()

    try {
      if (!sessionId.value) {
        const session = await api.createAgentSession(spaceId.value, question.slice(0, 40), requests.signal)

        if (!current(expected)) return

        sessionId.value = String(session.id)
        sessions.value = [session, ...sessions.value.filter((row) => String(row.id) !== String(session.id))]
        expected = ticket()
        onSessionCreated(sessionId.value)
        void loadSessions()
      }

      const selection = !scope.value ? {} : scope.value.kind === 'folder'
        ? { folderId: Number(scope.value.id) }
        : scope.value.files?.length
          ? {
              documentIds: [...new Set(scope.value.files.map(file => Number(file.id)))].sort((a, b) => a - b)
            }
          : { documentId: Number(scope.value.id) }
      const attachmentIds = attachments.value.map(item => item.id)
      if (!pendingRequest.value || pendingRequest.value.question !== question || (!retry &&
          (pendingRequest.value.documentId !== selection.documentId || pendingRequest.value.folderId !== selection.folderId
            || JSON.stringify(pendingRequest.value.documentIds) !== JSON.stringify(selection.documentIds)
            || JSON.stringify(pendingRequest.value.attachmentIds || []) !== JSON.stringify(attachmentIds)))) {
        pendingRequest.value = { clientRequestId: globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`, question, ...selection, ...(attachmentIds.length ? { attachmentIds } : {}) }
      }

      const activeScope = scope.value ? JSON.parse(JSON.stringify(scope.value)) : null

      const result = await api.startAgentRun(expected.spaceId, expected.sessionId, pendingRequest.value, requests.signal)

      if (!current(expected)) return

      stopObserving()
      activeRunId.value = String(result.runId)
      snapshot.value = null
      if (activeScope) {
        scopeByRun.value.set(String(result.runId), activeScope)
        persistScope(result.runId, activeScope)
      }
      pendingQuestion.value = { id: `pending-${result.runId}`, runId: result.runId, role: 'USER', text: question, masked: false, citations: [], scope: activeScope }
      draft.value = ''
      clearAttachments()
      pendingRequest.value = null
      void loadMessages()
      void observe()
    } catch (cause) {
      if (!current(expected) || cause.name === 'AbortError' || cause.code === 'ERR_CANCELED') return

      if (accessLost(cause)) loseAccess(cause)
      else error.value = agentError(cause)
    } finally { if (current(expected)) submitting.value = false }
  }

  async function cancel() {
    if (!activeRunId.value || cancelling.value) return

    const expected = ticket()
    const run = activeRunId.value

    cancelling.value = true

    try {
      const value = await api.cancelAgentRun(expected.spaceId, run, requests.signal)

      if (!current(expected) || run !== activeRunId.value) return

      applySnapshot(value)

      if (isTerminalRun(value.status)) stopObserving()
    } catch (cause) {
      if (!current(expected) || cause.code === 'ERR_CANCELED') return

      if (accessLost(cause)) loseAccess(cause)
      else error.value = '停止请求尚未确认，请重试。'
    } finally { if (current(expected)) cancelling.value = false }
  }

  async function verifyCitations(runId, sourceIds) {
    const expected = ticket()

    try {
      const result = await api.getAgentRun(expected.spaceId, runId, requests.signal)

      if (!current(expected)) throw new DOMException('Aborted', 'AbortError')

      if (!result.answer) reasoningByRun.value.set(String(runId), null)

      rememberReasoning(result)

      const value = normalizeRun(result)

      messages.value = messages.value.filter((row) => row.role !== 'ASSISTANT' || String(row.runId) !== String(runId) || value.answer)
        .map((row) => row.role === 'ASSISTANT' && String(row.runId) === String(runId) ? value.answer : row)

      if (String(runId) === activeRunId.value) snapshot.value = value

      const requested = [...new Set(sourceIds)]
      const available = value.answer && !value.answer.masked ? value.answer.citations : []
      const citations = requested.map((id) => available.find((source) => source.id === id))

      if (!requested.length || citations.some((source) => !source)) {
        throw new Error('资料已更新或不可访问，原回答已隐藏。')
      }

      return citations
    } catch (cause) {
      if (current(expected)) {
        if (accessLost(cause)) loseAccess(cause)
        else error.value = agentError(cause)
      }

      throw cause
    }
  }

  async function verifyCitation(runId, sourceId) {
    const [source] = await verifyCitations(runId, [sourceId])

    return source
  }

  function refresh() {
    if (denied.value) return setContext(spaceId.value, sessionId.value, true)

    void loadMessages()

    if (activeRunId.value && !running.value) void reconnect()
  }

  onBeforeUnmount(() => {
    clearAttachments()
    disposed = true
    reasoningByRun.value.clear()
    scopeByRun.value.clear()
    messages.value = []
    snapshot.value = null
    pendingQuestion.value = null
    activeRunId.value = ''
    stopDeleting()
    generation++
    requests.abort()
    listController.abort()
    stopObserving()
  })

  return {
    attachments, uploading, attachmentError, uploadFiles, removeAttachment,
    scope,
    spaceId,
    sessionId,
    activeRunId,
    sessions,
    snapshot,
    draft,
    error,
    sessionError,
    connection,
    submitting,
    cancelling,
    deletingSessionId,
    deletingCurrent,
    loadingHistory,
    loadingSessions,
    denied,
    pendingRequest,
    sessionPage,
    sessionPages,
    historyPage,
    historyPages,
    running,
    visibleMessages,
    setContext,
    loadSessions,
    loadMessages,
    deleteSession,
    send,
    cancel,
    reconnect,
    refresh,
    verifyCitations,
    verifyCitation
  }
}
