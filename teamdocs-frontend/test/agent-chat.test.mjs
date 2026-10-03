import test from 'node:test'
import assert from 'node:assert/strict'
import { registerHooks } from 'node:module'
import { createRenderer } from 'vue'
import { handlers } from './helpers/agent-api.mjs'

// Resolve the app's aliases without loading Axios, the router or a DOM.
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (context.parentURL?.endsWith('/src/composables/useAgentChat.js')) {
    if (specifier === '@/api/agent') return { url: new URL('./helpers/agent-api.mjs', import.meta.url).href, shortCircuit: true }
    if (specifier.startsWith('@/utils/')) return { url: new URL(`../src/${specifier.slice(2)}.js`, import.meta.url).href, shortCircuit: true }
  }
  return next(specifier, context)
} })
const { useAgentChat } = await import('../src/composables/useAgentChat.js')
hooks.deregister()
const flush = () => new Promise((resolve) => setImmediate(resolve))
const page = (records = []) => ({ records, pages: 1 })
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const view = (status = 'RUNNING') => ({ id: 10, sessionId: 3, status, tools: [], answer: null })

function fixture(t, overrides = {}) {
  const calls = { starts: [], streams: [], reads: 0, cancels: 0, deletes: [] }
  Object.assign(handlers, {
    listAgentSessions: async () => page([{ id: 3, title: 'Private session' }]),
    createAgentSession: async () => ({ id: 3, title: 'New session' }),
    deleteAgentSession: async (...args) => { calls.deletes.push(args) },
    listAgentMessages: async () => page(),
    startAgentRun: async (...args) => { calls.starts.push(args); return { runId: 10 } },
    getAgentRun: async () => { calls.reads++; return view() },
    cancelAgentRun: async () => { calls.cancels++; return view('CANCELLED') },
    observeAgentRun: (scope, signal, onEvent) => new Promise((resolve, reject) => {
      calls.streams.push({ scope, signal, onEvent, resolve, reject })
      signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
    }),
    ...overrides
  })
  let chat
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
  const app = renderer.createApp({ setup() { chat = useAgentChat(() => {}); return () => null } })
  app.mount({})
  let mounted = true
  /** 清理测试页面及其临时状态。 */
  const unmount = () => { if (mounted) { mounted = false; app.unmount() } }
  t.after(unmount)
  return { chat, calls, unmount }
}

async function start(chat) { await chat.setContext(1, 3); chat.draft.value = 'Question'; await chat.send(); await flush() }

test('switching space drops late history and list responses', async (t) => {
  const history = deferred(), sessions = deferred()
  const { chat } = fixture(t, { listAgentMessages: () => history.promise, listAgentSessions: (space) => space === '1' ? sessions.promise : Promise.resolve(page()) })
  const old = chat.setContext(1, 3)
  await chat.setContext(2, '')
  history.resolve(page([{ id: 1, role: 'USER', text: 'Private question' }]))
  sessions.resolve(page([{ id: 3, title: 'Private session' }]))
  await old; await flush()
  assert.deepEqual(chat.visibleMessages.value, [])
  assert.deepEqual(chat.sessions.value, [])
  assert.equal(chat.spaceId.value, '2')
})

test('switching context during session creation never submits the old question', async (t) => {
  const session = deferred()
  const { chat, calls } = fixture(t, { createAgentSession: () => session.promise })
  await chat.setContext(1, ''); chat.draft.value = 'Private question'
  const sending = chat.send()
  await chat.setContext(2, '')
  session.resolve({ id: 3 })
  await sending
  assert.equal(calls.starts.length, 0)
  assert.equal(chat.sessionId.value, '')
  assert.equal(chat.submitting.value, false)
})

test('uncertain submission reuses the same idempotency key', async (t) => {
  const bodies = []
  const { chat } = fixture(t, { startAgentRun: async (space, session, body) => {
    bodies.push(body)
    if (bodies.length === 1) throw new Error('Network failure')
    return { runId: 10 }
  } })
  await start(chat)
  assert.equal(chat.pendingRequest.value.question, 'Question')
  await chat.send(true)
  assert.equal(bodies.length, 2)
  assert.equal(bodies[0].clientRequestId, bodies[1].clientRequestId)
  assert.equal(chat.pendingRequest.value, null)
})

test('stream disconnect fetches the committed result without resubmitting', async (t) => {
  const { chat, calls } = fixture(t, { getAgentRun: async () => view('SUCCEEDED') })
  await start(chat)
  calls.streams[0].reject(new Error('Disconnected'))
  await flush()
  assert.equal(chat.snapshot.value.status, 'SUCCEEDED')
  assert.equal(chat.running.value, false)
  assert.equal(calls.starts.length, 1)
})

test('cancel closes observation and ignores late answer events', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)
  await chat.cancel()
  assert.equal(calls.cancels, 1)
  assert.equal(calls.streams[0].signal.aborted, true)
  assert.equal(calls.streams[0].onEvent({ spaceId: 1, sessionId: 3, runId: 10, snapshot: view('SUCCEEDED') }), false)
  assert.equal(chat.snapshot.value.status, 'CANCELLED')
})

test('switching space aborts observation and drops late stream events', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)
  await chat.setContext(2, '')
  assert.equal(calls.streams[0].signal.aborted, true)
  assert.equal(calls.streams[0].onEvent({ spaceId: 1, sessionId: 3, runId: 10, snapshot: view('SUCCEEDED') }), false)
  assert.equal(chat.snapshot.value, null)
  assert.equal(chat.activeRunId.value, '')
})

test('access revocation clears session titles and invalidates pending list requests', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)
  assert.equal(chat.sessions.value.length, 1)
  const late = deferred()
  handlers.listAgentSessions = () => late.promise
  const listing = chat.loadSessions()
  calls.streams[0].reject(new Error('ACCESS_REVOKED'))
  await flush()
  assert.equal(chat.denied.value, true)
  assert.deepEqual(chat.sessions.value, [])
  assert.equal(chat.loadingSessions.value, false)
  late.resolve(page([{ id: 3, title: 'Private session' }]))
  await listing
  assert.deepEqual(chat.sessions.value, [])
  assert.deepEqual(chat.visibleMessages.value, [])
})

test('unauthorized session listing disables the composer and clears old titles', async (t) => {
  const { chat } = fixture(t)
  await chat.setContext(1, ''); await flush()
  handlers.listAgentSessions = async () => { const error = new Error('Unauthorized'); error.response = { status: 401 }; throw error }
  await chat.loadSessions()
  assert.equal(chat.denied.value, true)
  assert.deepEqual(chat.sessions.value, [])
})


test('deleting the selected session clears its conversation and refreshes the list', async (t) => {
  const { chat, calls } = fixture(t, {
    listAgentMessages: async () => page([{ id: 1, role: 'USER', text: 'Old question' }])
  })
  await chat.setContext(1, 3)
  await flush()
  chat.draft.value = 'Old draft'
  handlers.listAgentSessions = async () => page()

  assert.equal(await chat.deleteSession(3), true)
  await flush()

  assert.equal(calls.deletes.length, 1)
  assert.deepEqual(calls.deletes[0].slice(0, 2), ['1', '3'])
  assert.equal(chat.sessionId.value, '')
  assert.equal(chat.draft.value, '')
  assert.deepEqual(chat.visibleMessages.value, [])
  assert.deepEqual(chat.sessions.value, [])
  assert.equal(chat.deletingSessionId.value, '')
})

test('deleting another session preserves the current conversation and draft', async (t) => {
  const { chat } = fixture(t)
  await chat.setContext(1, 3)
  chat.draft.value = 'Keep this draft'

  assert.equal(await chat.deleteSession(4), true)

  assert.equal(chat.sessionId.value, '3')
  assert.equal(chat.draft.value, 'Keep this draft')
})

test('failed deletion leaves the selected session available to retry', async (t) => {
  const { chat } = fixture(t, {
    deleteAgentSession: async () => { throw new Error('任务正在结束，请稍后再删除会话') }
  })
  await chat.setContext(1, 3)
  chat.draft.value = 'Keep this draft'

  assert.equal(await chat.deleteSession(3), false)

  assert.equal(chat.sessionId.value, '3')
  assert.equal(chat.draft.value, 'Keep this draft')
  assert.match(chat.error.value, /稍后再删除/)
  assert.equal(chat.deletingSessionId.value, '')
})

test('switching space aborts deletion and ignores its late result', async (t) => {
  const pending = deferred()
  let signal
  const { chat } = fixture(t, {
    deleteAgentSession: (space, session, requestSignal) => { signal = requestSignal; return pending.promise }
  })
  await chat.setContext(1, 3)
  const deleting = chat.deleteSession(3)

  await chat.setContext(2, '')
  chat.draft.value = 'Another space'
  assert.equal(signal.aborted, true)
  pending.resolve()

  assert.equal(await deleting, false)
  assert.equal(chat.spaceId.value, '2')
  assert.equal(chat.draft.value, 'Another space')
  assert.equal(chat.deletingSessionId.value, '')
})

test('a late deletion in the same space never clears a newly selected session', async (t) => {
  const pending = deferred()
  const { chat } = fixture(t, { deleteAgentSession: () => pending.promise })
  await chat.setContext(1, 3)
  const deleting = chat.deleteSession(3)

  await chat.setContext(1, 4)
  chat.draft.value = 'New session draft'
  pending.resolve()

  assert.equal(await deleting, true)
  assert.equal(chat.sessionId.value, '4')
  assert.equal(chat.draft.value, 'New session draft')
})

test('the active current run must be stopped before deleting its session', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)

  assert.equal(await chat.deleteSession(3), false)
  assert.equal(calls.deletes.length, 0)
  assert.equal(chat.running.value, true)
  assert.match(chat.error.value, /停止/)
})


test('paragraph evidence is revalidated together with one request', async (t) => {
  const sources = [{ id: 'C1', excerpt: 'first' }, { id: 'C2', excerpt: 'second' }]
  let reads = 0
  const { chat } = fixture(t, { getAgentRun: async () => {
    reads++
    return { ...view('SUCCEEDED'), answer: { id: 2, runId: 10, role: 'ASSISTANT', text: 'answer', masked: false, citations: sources } }
  } })
  await chat.setContext(1, 3)

  assert.deepEqual(await chat.verifyCitations(10, ['C2', 'C1']), [sources[1], sources[0]])
  assert.equal(reads, 1)
})

test('masked or missing paragraph evidence never returns cached snippets', async (t) => {
  const { chat } = fixture(t, { getAgentRun: async () => ({ ...view('SUCCEEDED'), answer: { masked: true, citations: [] } }) })
  await chat.setContext(1, 3)
  await assert.rejects(chat.verifyCitations(10, ['C1']), /已更新或不可访问/)

  handlers.getAgentRun = async () => ({ ...view('SUCCEEDED'), answer: { masked: false, citations: [{ id: 'C1' }] } })
  await assert.rejects(chat.verifyCitations(10, ['C1', 'C99']), /已更新或不可访问/)
})

test('late evidence responses are discarded after switching space', async (t) => {
  const pending = deferred()
  const { chat } = fixture(t, { getAgentRun: () => pending.promise })
  await chat.setContext(1, 3)
  const checking = chat.verifyCitations(10, ['C1'])
  await chat.setContext(2, '')
  pending.resolve({ ...view('SUCCEEDED'), answer: { masked: false, citations: [{ id: 'C1', excerpt: 'old space' }] } })

  await assert.rejects(checking, { name: 'AbortError' })
  assert.deepEqual(chat.visibleMessages.value, [])
})

/** 创建包含思考投影的完整快照。 */
function reasoningView(status = 'RUNNING', content = '先核对资料') {
  const reasoning = { reasoningContent: content, reasoningDurationMs: null, reasoningTruncated: false }
  return {
    ...view(status), ...reasoning,
    answer: { id: status === 'RUNNING' ? null : 2, runId: 10, role: 'ASSISTANT', text: '', masked: false, citations: [], reasoningContent: null, reasoningDurationMs: null, reasoningTruncated: false, runStatus: status }
  }
}

/** 投递当前运行的完整思考快照。 */
function emitReasoning(calls, snapshot, index = calls.streams.length - 1) {
  return calls.streams[index].onEvent({ type: 'reasoning_updated', spaceId: 1, sessionId: 3, runId: 10, snapshot })
}

/** 读取当前轮次唯一的助手消息。 */
function assistant(chat) {
  return chat.visibleMessages.value.find((row) => row.role === 'ASSISTANT' && String(row.runId) === chat.activeRunId.value)
}

test('one stable assistant row spans waiting, full reasoning snapshots and final history', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)
  const key = `${assistant(chat).runId}:${assistant(chat).role}`
  assert.equal(assistant(chat).text, '')
  assert.equal(assistant(chat).reasoningContent, null)
  assert.deepEqual(assistant(chat).citations, [])

  emitReasoning(calls, reasoningView())
  assert.equal(`${assistant(chat).runId}:${assistant(chat).role}`, key)
  assert.equal(assistant(chat).id, null)
  assert.equal(assistant(chat).text, '')
  assert.equal(assistant(chat).reasoningContent, '先核对资料')
  assert.deepEqual(chat.visibleMessages.value.map((row) => row.role), ['USER', 'ASSISTANT'])

  const updated = reasoningView('RUNNING', '先核对资料，再检查来源 [C1]')
  emitReasoning(calls, updated)
  emitReasoning(calls, updated)
  assert.equal(assistant(chat).reasoningContent, updated.reasoningContent)
  assert.equal(chat.visibleMessages.value.filter((row) => row.role === 'ASSISTANT').length, 1)

  const final = reasoningView('SUCCEEDED', updated.reasoningContent)
  final.answer.text = '已验证的最终回答'
  final.reasoningDurationMs = 1250
  handlers.listAgentMessages = async () => page([{ id: 1, runId: 10, role: 'USER', text: 'Question' }, final.answer])
  assert.equal(emitReasoning(calls, final), false)
  await flush()
  assert.equal(`${assistant(chat).runId}:${assistant(chat).role}`, key)
  assert.equal(assistant(chat).text, '已验证的最终回答')
  assert.equal(assistant(chat).reasoningDurationMs, 1250)
  assert.equal(assistant(chat).runStatus, 'SUCCEEDED')
  assert.equal(chat.visibleMessages.value.length, 2)
})

test('reconnecting replaces reasoning snapshots without duplicate messages or submissions', async (t) => {
  const { chat, calls } = fixture(t, { getAgentRun: async () => reasoningView('RUNNING', '重连后的完整思考') })
  await start(chat)
  emitReasoning(calls, reasoningView())
  await chat.reconnect()
  await flush()
  assert.equal(calls.streams[0].signal.aborted, true)
  assert.equal(emitReasoning(calls, reasoningView('RUNNING', '过期内容'), 0), false)
  assert.equal(assistant(chat).reasoningContent, '重连后的完整思考')
  emitReasoning(calls, reasoningView('RUNNING', '重连后的完整思考'))
  assert.equal(assistant(chat).reasoningContent, '重连后的完整思考')
  assert.equal(chat.visibleMessages.value.filter((row) => row.role === 'ASSISTANT').length, 1)
  assert.equal(calls.starts.length, 1)
})

test('history without page-local reasoning never shows a previous run thinking', async (t) => {
  const old = { ...reasoningView('SUCCEEDED').answer, id: 1, runId: 9, text: '历史回答' }
  const { chat, calls } = fixture(t, { listAgentMessages: async () => page([old]) })
  await start(chat)
  emitReasoning(calls, reasoningView('RUNNING', '当前轮次思考'))
  await chat.loadMessages()
  const history = chat.visibleMessages.value.find((row) => row.runId === 9)
  assert.equal(history.reasoningContent, null)
  assert.equal(history.reasoningDurationMs, null)
  assert.equal(history.reasoningTruncated, false)
  assert.equal(history.runStatus, 'SUCCEEDED')
  assert.equal(assistant(chat).reasoningContent, '当前轮次思考')
  assert.equal(assistant(chat).runStatus, 'RUNNING')
})

test('cancel keeps received reasoning but stops and rejects late stream content', async (t) => {
  const { chat, calls } = fixture(t, { cancelAgentRun: async () => reasoningView('CANCELLED', '停止前的思考') })
  await start(chat)
  emitReasoning(calls, reasoningView('RUNNING', '停止前的思考'))
  await chat.cancel()
  assert.equal(assistant(chat).runStatus, 'CANCELLED')
  assert.equal(assistant(chat).reasoningContent, '停止前的思考')
  assert.equal(chat.running.value, false)
  assert.equal(calls.streams[0].signal.aborted, true)
  assert.equal(emitReasoning(calls, reasoningView('SUCCEEDED', '迟到的思考')), false)
  assert.equal(assistant(chat).reasoningContent, '停止前的思考')
  await chat.setContext(2, '')
  assert.deepEqual(chat.visibleMessages.value, [])
  assert.equal(chat.snapshot.value, null)
})

test('access loss clears reasoning and ignores late history and SSE responses', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)
  emitReasoning(calls, reasoningView('RUNNING', '私有思考'))
  const pending = deferred()
  handlers.listAgentMessages = () => pending.promise
  const loading = chat.loadMessages()
  calls.streams[0].reject(new Error('ACCESS_REVOKED'))
  await flush()
  assert.equal(chat.denied.value, true)
  assert.equal(chat.snapshot.value, null)
  assert.deepEqual(chat.visibleMessages.value, [])
  pending.resolve(page([reasoningView('SUCCEEDED', '迟到的私有思考').answer]))
  await loading
  assert.equal(emitReasoning(calls, reasoningView()), false)
  assert.deepEqual(chat.visibleMessages.value, [])
  handlers.listAgentMessages = async () => page([reasoningView('SUCCEEDED', null).answer])
  await chat.setContext(1, 3, true)
  assert.equal(chat.visibleMessages.value[0].reasoningContent, null)
  assert.equal(chat.visibleMessages.value[0].reasoningDurationMs, null)
})

test('masked snapshots and history clear reasoning and timing projections', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)
  emitReasoning(calls, reasoningView())
  const masked = reasoningView('SUCCEEDED', '必须清除的思考')
  masked.reasoningDurationMs = 1000
  masked.reasoningTruncated = true
  Object.assign(masked.answer, { masked: true, reasoningDurationMs: 1000, reasoningTruncated: true })
  emitReasoning(calls, masked)
  await flush()
  assert.equal(assistant(chat).reasoningContent, null)
  assert.equal(assistant(chat).reasoningDurationMs, null)
  assert.equal(assistant(chat).reasoningTruncated, false)
  assert.equal(chat.snapshot.value.reasoningContent, null)
  assert.equal(chat.snapshot.value.reasoningDurationMs, null)
  assert.equal(chat.snapshot.value.answer.reasoningContent, null)

  handlers.listAgentMessages = async () => page([masked.answer])
  await chat.loadMessages()
  assert.equal(assistant(chat).reasoningContent, null)
  assert.equal(assistant(chat).reasoningDurationMs, null)
})

test('masked history overrides an older readable snapshot and evidence revalidation clears reasoning', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)
  emitReasoning(calls, reasoningView())
  const masked = { ...reasoningView().answer, id: 2, masked: true, reasoningDurationMs: 1200 }
  handlers.listAgentMessages = async () => page([masked])
  await chat.loadMessages()
  assert.equal(assistant(chat).reasoningContent, null)
  assert.equal(chat.snapshot.value.reasoningContent, null)
  assert.equal(chat.snapshot.value.answer.reasoningDurationMs, null)

  handlers.getAgentRun = async () => ({ ...reasoningView('SUCCEEDED'), answer: masked })
  await assert.rejects(chat.verifyCitations(10, ['C1']), /已更新或不可访问/)
  assert.equal(assistant(chat).reasoningContent, null)
  assert.equal(chat.snapshot.value.reasoningDurationMs, null)
})

test('legacy messages and runs without reasoning stay compatible without invented answers', async (t) => {
  const old = { id: 1, runId: 9, role: 'ASSISTANT', text: '旧接口回答', citations: [] }
  const { chat, calls } = fixture(t, { listAgentMessages: async () => page([old]) })
  await start(chat)
  assert.equal(chat.visibleMessages.value[0].reasoningContent, null)
  assert.equal(chat.visibleMessages.value[0].reasoningDurationMs, null)
  emitReasoning(calls, view('SUCCEEDED'))
  await flush()
  assert.equal(assistant(chat).text, '')
  assert.equal(assistant(chat).reasoningContent, null)
  assert.equal(assistant(chat).reasoningDurationMs, null)
  assert.equal(assistant(chat).runStatus, 'SUCCEEDED')
  assert.equal(chat.running.value, false)
})

test('page-local reasoning survives drained terminal snapshots, history refresh and the next run', async (t) => {
  const final = reasoningView('SUCCEEDED', null)
  final.answer.text = '完成后的回答'
  const { chat, calls } = fixture(t, { getAgentRun: async () => final })
  await start(chat)
  const live = reasoningView('RUNNING', '仅本页面保存的思考')
  live.reasoningDurationMs = 0
  live.reasoningTruncated = true
  emitReasoning(calls, live)
  handlers.listAgentMessages = async () => page([final.answer])
  emitReasoning(calls, final)
  await flush()
  await chat.loadMessages()
  await chat.reconnect()
  await flush()
  assert.equal(assistant(chat).reasoningContent, live.reasoningContent)
  assert.equal(assistant(chat).reasoningDurationMs, 0)
  assert.equal(assistant(chat).reasoningTruncated, true)
  assert.equal(assistant(chat).runStatus, 'SUCCEEDED')

  handlers.startAgentRun = async () => ({ runId: 11 })
  chat.draft.value = '下一个问题'
  await chat.send()
  await flush()
  const previous = chat.visibleMessages.value.find((row) => row.role === 'ASSISTANT' && row.runId === 10)
  assert.equal(previous.reasoningContent, live.reasoningContent)
  assert.equal(previous.runStatus, 'SUCCEEDED')
  assert.equal(assistant(chat).reasoningContent, null)
  assert.equal(chat.activeRunId.value, '11')

  handlers.listAgentMessages = async () => page([{ ...final.answer, masked: true }])
  await chat.loadMessages()
  assert.equal(chat.visibleMessages.value.find((row) => row.runId === 10 && row.role === 'ASSISTANT').reasoningContent, null)
  handlers.listAgentMessages = async () => page([final.answer])
  await chat.loadMessages()
  assert.equal(chat.visibleMessages.value.find((row) => row.runId === 10 && row.role === 'ASSISTANT').reasoningContent, null)
})

test('switching sessions clears the page cache before returning to the same history', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)
  emitReasoning(calls, reasoningView('RUNNING', '离开会话前的思考'))
  const history = reasoningView('SUCCEEDED', null).answer
  handlers.listAgentMessages = async (space, session) => session === '3' ? page([history]) : page()
  await chat.setContext(1, 4)
  assert.deepEqual(chat.visibleMessages.value, [])
  assert.equal(emitReasoning(calls, reasoningView('RUNNING', '旧会话迟到内容')), false)
  await chat.setContext(1, 3)
  assert.equal(chat.visibleMessages.value[0].reasoningContent, null)
  assert.equal(chat.visibleMessages.value[0].reasoningDurationMs, null)
})

test('unmount and a fresh page never carry over the previous reasoning cache', async (t) => {
  const first = fixture(t)
  await start(first.chat)
  emitReasoning(first.calls, reasoningView('RUNNING', '刷新前的思考'))
  first.unmount()
  assert.deepEqual(first.chat.visibleMessages.value, [])
  assert.equal(first.chat.snapshot.value, null)
  assert.equal(emitReasoning(first.calls, reasoningView()), false)

  const second = fixture(t, { listAgentMessages: async () => page([reasoningView('SUCCEEDED', null).answer]) })
  await second.chat.setContext(1, 3)
  assert.equal(second.chat.visibleMessages.value[0].reasoningContent, null)
  assert.equal(second.chat.visibleMessages.value[0].reasoningDurationMs, null)
})

test('a masked response blocks later readable snapshots from reviving reasoning', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)
  emitReasoning(calls, reasoningView('RUNNING', '遮蔽前的思考'))
  const masked = reasoningView('RUNNING', '不可再显示的思考')
  masked.answer.masked = true
  emitReasoning(calls, masked)
  emitReasoning(calls, reasoningView('RUNNING', '迟到的可读快照'))
  assert.equal(assistant(chat).reasoningContent, null)
  assert.equal(assistant(chat).reasoningDurationMs, null)
  assert.equal(chat.snapshot.value.reasoningContent, null)
  assert.equal(chat.snapshot.value.answer.reasoningContent, null)
})

test('a stopped temporary answer remains inspectable locally without fabricating a saved reply', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)
  emitReasoning(calls, reasoningView('RUNNING', '停止前已收到的思考'))
  handlers.listAgentMessages = async () => page([{ id: 1, runId: 10, role: 'USER', text: 'Question' }])
  await chat.cancel()
  handlers.startAgentRun = async () => ({ runId: 11 })
  chat.draft.value = '继续提问'
  await chat.send()
  await flush()
  const stopped = chat.visibleMessages.value.find((row) => row.role === 'ASSISTANT' && String(row.runId) === '10')
  assert.equal(stopped.reasoningContent, '停止前已收到的思考')
  assert.equal(stopped.runStatus, 'CANCELLED')
  assert.equal(stopped.text, '')
  assert.deepEqual(stopped.citations, [])
  assert.equal(assistant(chat).reasoningContent, null)
})

test('explicitly unknown snapshot timing never restores cached or answer timing', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)
  const timed = reasoningView()
  timed.reasoningDurationMs = 1200
  emitReasoning(calls, timed)
  assert.equal(assistant(chat).reasoningDurationMs, 1200)

  const unknown = reasoningView('RUNNING', '新的完整思考快照')
  unknown.answer.reasoningDurationMs = 1200
  emitReasoning(calls, unknown)
  assert.equal(assistant(chat).reasoningDurationMs, null)
  emitReasoning(calls, reasoningView('SUCCEEDED', null))
  await flush()
  assert.equal(assistant(chat).reasoningContent, unknown.reasoningContent)
  assert.equal(assistant(chat).reasoningDurationMs, null)
})

test('legacy answer timing is used only when the top-level timing field is absent', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)
  const legacy = reasoningView()
  delete legacy.reasoningDurationMs
  legacy.answer.reasoningDurationMs = 450
  emitReasoning(calls, legacy)
  assert.equal(assistant(chat).reasoningDurationMs, 450)
})

test('a masked run cannot revive text, citations or images through a late readable response', async (t) => {
  const { chat, calls } = fixture(t)
  await start(chat)
  const pending = deferred()
  handlers.getAgentRun = () => pending.promise
  const checking = chat.verifyCitations(10, ['C1'])
  const masked = reasoningView('RUNNING', '不可公开的思考')
  masked.answer.masked = true
  emitReasoning(calls, masked)

  const readable = reasoningView('RUNNING', '迟到思考')
  readable.answer.text = '不能恢复的回答'
  readable.answer.citations = [{ id: 'C1', imageSource: true, excerpt: '私有图片描述' }]
  pending.resolve(readable)
  await assert.rejects(checking, /已更新或不可访问/)
  emitReasoning(calls, readable)
  assert.equal(assistant(chat).masked, true)
  assert.doesNotMatch(assistant(chat).text, /不能恢复/)
  assert.deepEqual(assistant(chat).citations, [])
  assert.equal(assistant(chat).reasoningContent, null)
  assert.equal(chat.snapshot.value.answer.masked, true)
})

test('late evidence and reasoning responses after leaving the page cannot refill memory', async (t) => {
  const { chat, calls, unmount } = fixture(t)
  await start(chat)
  emitReasoning(calls, reasoningView())
  const pending = deferred()
  handlers.getAgentRun = () => pending.promise
  const checking = chat.verifyCitations(10, ['C1'])
  unmount()
  const late = reasoningView('SUCCEEDED', '页面离开后的思考')
  late.answer.citations = [{ id: 'C1', imageSource: true }]
  pending.resolve(late)
  await assert.rejects(checking, { name: 'AbortError' })
  assert.deepEqual(chat.visibleMessages.value, [])
  assert.equal(chat.snapshot.value, null)
})

test('reasoning from older pages is shown only when that conversation page is loaded', async (t) => {
  const { chat, calls } = fixture(t)
  const history = []
  let nextRun = 10
  handlers.startAgentRun = async () => ({ runId: nextRun })
  handlers.listAgentMessages = async (space, session, current = 1) => ({
    ...page(current === 1 ? history.slice(-20).reverse() : history.slice(0, -20).reverse()),
    pages: Math.max(1, Math.ceil(history.length / 20))
  })
  await chat.setContext(1, 3)
  for (let index = 0; index < 11; index++) {
    nextRun = 10 + index
    chat.draft.value = `问题${index}`
    await chat.send()
    await flush()
    const final = reasoningView('SUCCEEDED', `思考${index}`)
    final.id = nextRun
    Object.assign(final.answer, { id: 2 * index + 2, runId: nextRun, text: `回答${index}` })
    history.push({ id: 2 * index + 1, runId: nextRun, role: 'USER', text: `问题${index}` }, final.answer)
    calls.streams.at(-1).onEvent({ type: 'run_finished', spaceId: 1, sessionId: 3, runId: nextRun, snapshot: final })
    await flush()
  }
  assert.equal(chat.visibleMessages.value.length, 20)
  assert.equal(chat.visibleMessages.value.some((row) => Number(row.runId) === 10), false)
  assert.equal(Number(chat.visibleMessages.value.at(-1).runId), 20)
  await chat.loadMessages(true)
  const old = chat.visibleMessages.value.find((row) => row.role === 'ASSISTANT' && Number(row.runId) === 10)
  assert.equal(old.reasoningContent, '思考0')
  assert.equal(Number(chat.visibleMessages.value.at(-1).runId), 20)
})

test('history fields alone never populate the transient reasoning cache', async (t) => {
  const old = { ...reasoningView('SUCCEEDED').answer, reasoningContent: '旧历史字段', reasoningDurationMs: 1000, reasoningTruncated: true }
  const { chat } = fixture(t, { listAgentMessages: async () => page([old]) })
  await chat.setContext(1, 3)
  assert.equal(chat.visibleMessages.value[0].reasoningContent, null)
  assert.equal(chat.visibleMessages.value[0].reasoningDurationMs, null)
  assert.equal(chat.visibleMessages.value[0].reasoningTruncated, false)
})
