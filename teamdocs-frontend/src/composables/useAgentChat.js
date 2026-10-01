import { ref, computed, onBeforeUnmount } from 'vue'
import * as api from '@/api/agent'
import { isTerminalRun, sameAgentContext, abortableDelay } from '@/utils/agentStream'
import { agentError, accessLost } from '@/utils/agentErrors'

export function useAgentChat(onSessionCreated) {
  const spaceId = ref('')
  const sessionId = ref('')
  const activeRunId = ref('')
  const sessions = ref([])
  const messages = ref([])
  const snapshot = ref(null)
  const draft = ref('')
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
    if (pendingQuestion.value && !rows.some((row) => row.role === 'USER' && String(row.runId) === String(pendingQuestion.value.runId))) rows.push(pendingQuestion.value)
    if (snapshot.value?.answer && !rows.some((row) => row.role === 'ASSISTANT' && String(row.runId) === activeRunId.value)) rows.push(snapshot.value.answer)
    return rows
  })

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
    if (changedSpace) stopDeleting()
    generation++
    requests.abort()
    requests = new AbortController()
    stopObserving()
    spaceId.value = nextSpace
    sessionId.value = nextSession
    activeRunId.value = ''
    messages.value = []
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
      messages.value = [...new Map(rows.map((row) => [String(row.id), row])).values()].sort((a, b) => Number(a.id) - Number(b.id))
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
    snapshot.value = value
    messages.value = messages.value.map((message) => message.role === 'ASSISTANT' && String(message.runId) === String(value.id) && value.answer ? value.answer : message)
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

  async function send(retry = false) {
    if (submitting.value || running.value || denied.value || deletingCurrent.value) return
    const question = retry ? pendingRequest.value?.question : draft.value.trim()
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
      if (!pendingRequest.value || pendingRequest.value.question !== question) {
        pendingRequest.value = { clientRequestId: globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`, question }
      }
      const result = await api.startAgentRun(expected.spaceId, expected.sessionId, pendingRequest.value, requests.signal)
      if (!current(expected)) return
      stopObserving()
      activeRunId.value = String(result.runId)
      snapshot.value = null
      pendingQuestion.value = { id: `pending-${result.runId}`, runId: result.runId, role: 'USER', text: question, masked: false, citations: [] }
      draft.value = ''
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
      const value = await api.getAgentRun(expected.spaceId, runId, requests.signal)
      if (!current(expected)) throw new DOMException('Aborted', 'AbortError')
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
    disposed = true
    stopDeleting()
    generation++
    requests.abort()
    listController.abort()
    stopObserving()
  })

  return { spaceId, sessionId, activeRunId, sessions, snapshot, draft, error, sessionError, connection, submitting,
    cancelling, deletingSessionId, deletingCurrent, loadingHistory, loadingSessions, denied, pendingRequest, sessionPage, sessionPages, historyPage, historyPages,
    running, visibleMessages, setContext, loadSessions, loadMessages, deleteSession, send, cancel, reconnect, refresh, verifyCitations, verifyCitation }
}
