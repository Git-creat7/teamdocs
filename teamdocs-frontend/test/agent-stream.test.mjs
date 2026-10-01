import test from 'node:test'
import assert from 'node:assert/strict'
import { readAgentEvents, sameAgentContext, isSendKey, abortableDelay } from '../src/utils/agentStream.js'

const scope = { spaceId: 1, sessionId: 2, runId: 3 }
const event = (sequence, changes = {}) => ({ ...scope, sequence, snapshot: { status: 'RUNNING' }, ...changes })
const frame = (value, type = 'snapshot', newline = '\n') => `id:${value.sequence}${newline}event: ${type}${newline}data: ${JSON.stringify(value)}${newline}${newline}`
const body = (bytes) => new ReadableStream({ start(controller) { for (const part of bytes) controller.enqueue(part); controller.close() } })

test('UTF-8, CRLF and frames split at every byte are decoded without corruption', async () => {
  const payload = frame(event(1, { text: '上线前先备份😀' }), 'tool_started', '\r\n') + ': heartbeat\r\n\r\n' + frame(event(2), 'run_finished')
  const bytes = new TextEncoder().encode(payload)
  const received = []
  await readAgentEvents(body(Array.from(bytes, (byte) => new Uint8Array([byte]))), { scope, onEvent: (value) => received.push(value) })
  assert.equal(received.length, 2)
  assert.equal(received[0].text, '上线前先备份😀')
  assert.equal(received[1].type, 'run_finished')
})

test('other spaces, sessions, runs and duplicate/out-of-order frames are ignored', async () => {
  const payload = [event(99, { spaceId: 9 }), event(99, { sessionId: 9 }), event(99, { runId: 9 }), event(2), event(2), event(1), event(3)].map((value) => frame(value)).join('')
  const received = []
  await readAgentEvents(body([new TextEncoder().encode(payload)]), { scope, onEvent: (value) => received.push(value.sequence) })
  assert.deepEqual(received, [2, 3])
  assert.equal(sameAgentContext(scope, { spaceId: '1', sessionId: '2', runId: '3' }), true)
})

test('a new connection resets sequence; multiline JSON data is supported', async () => {
  for (let attempt = 0; attempt < 2; attempt++) {
    let count = 0
    const payload = 'event: snapshot\ndata: {"spaceId":1,"sessionId":2,\ndata: "runId":3,"sequence":1}\n\n'
    await readAgentEvents(body([new TextEncoder().encode(payload)]), { scope, onEvent: () => { count++ } })
    assert.equal(count, 1)
  }
})

test('consumer termination and aborted requests do not deliver late callbacks', async () => {
  const received = []
  await readAgentEvents(body([new TextEncoder().encode(frame(event(1)) + frame(event(2)))]), { scope, onEvent: (value) => { received.push(value.sequence); return false } })
  assert.deepEqual(received, [1])
  const controller = new AbortController()
  controller.abort()
  await assert.rejects(readAgentEvents(body([]), { scope, signal: controller.signal, onEvent: () => assert.fail('late callback') }), { name: 'AbortError' })
  await assert.rejects(abortableDelay(1000, controller.signal), { name: 'AbortError' })
})

test('Chinese IME confirmation and Shift+Enter never submit a question', () => {
  assert.equal(isSendKey({ key: 'Enter', isComposing: true }), false)
  assert.equal(isSendKey({ key: 'Enter', keyCode: 229 }), false)
  assert.equal(isSendKey({ key: 'Enter', shiftKey: true }), false)
  assert.equal(isSendKey({ key: 'Enter', isComposing: false, keyCode: 13 }), true)
})

test('unbounded or invalid stream payloads are rejected', async () => {
  await assert.rejects(readAgentEvents(body([new TextEncoder().encode('x'.repeat(262145))]), { scope, onEvent() {} }), /大小限制/)
  await assert.rejects(readAgentEvents(body([new TextEncoder().encode('data: not json\n\n')]), { scope, onEvent() {} }), SyntaxError)
})
