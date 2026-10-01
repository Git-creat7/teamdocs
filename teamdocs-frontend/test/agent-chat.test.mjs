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
  t.after(() => app.unmount())
  return { chat, calls }
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
