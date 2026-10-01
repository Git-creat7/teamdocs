import test from 'node:test'
import assert from 'node:assert/strict'
import { getAgentProgress } from '../src/utils/agentProgress.js'

const tool = (toolName, status = 'RUNNING', resultSummary = '') => ({
  sequence: 1, toolName, status, resultSummary, durationMs: 120
})
const run = (status, tools = [], extra = {}) => ({ id: 1, status, tools, toolCalls: tools.length, ...extra })

test('waiting and zero-tool replies never pretend to search documents', () => {
  for (const value of [null, run('QUEUED'), run('RUNNING')]) {
    const progress = getAgentProgress(value)
    assert.equal(progress.visible, true)
    assert.equal(progress.active, true)
    assert.equal(progress.hasDetails, true)
    assert.doesNotMatch(progress.label, /检索|查找|阅读/)
  }
  assert.equal(getAgentProgress(run('SUCCEEDED')).visible, false)
})

test('active labels follow the actual tool, not a fabricated sequence', () => {
  for (const [name, label] of [
    ['search_documents', '正在查找相关文档…'],
    ['search_document_chunks', '正在检索相关内容…'],
    ['read_document_chunks', '正在阅读文档内容…']
  ]) {
    const progress = getAgentProgress(run('RUNNING', [tool(name)]))
    assert.equal(progress.label, label)
    assert.equal(progress.steps[0].state, 'running')
    assert.equal(progress.hasDetails, true)
  }
})

test('a finished tool returns to reply preparation and zero results are explicit', () => {
  const progress = getAgentProgress(run('RUNNING', [tool('search_document_chunks', 'SUCCEEDED', 'records=0')]))
  assert.equal(progress.label, '等待后续响应…')
  assert.equal(progress.steps[0].label, '未找到匹配的正文内容')
  assert.equal(progress.steps[0].resultCount, 0)
  assert.equal(progress.steps[0].state, 'succeeded')

  const read = getAgentProgress(run('SUCCEEDED', [tool('read_document_chunks', 'SUCCEEDED', 'records=0')]))
  assert.equal(read.emptyRead, true)
  assert.equal(read.active, false)
  assert.equal(read.visible, true)
})

test('cancellation and failure never leave a tool spinning', () => {
  for (const status of ['CANCELLED', 'TIMED_OUT', 'FAILED']) {
    const progress = getAgentProgress(run(status, [tool('read_document_chunks')], { errorCode: 'RUN_TIMEOUT' }))
    assert.equal(progress.active, false)
    assert.notEqual(progress.steps[0].state, 'running')
    assert.doesNotMatch(progress.steps[0].label, /正在/)
  }
  assert.equal(getAgentProgress(run('CANCELLED', [tool('read_document_chunks')])).label, '已停止')
})

test('completed conversations keep details without exposing counts in the headline', () => {
  const progress = getAgentProgress(run('SUCCEEDED', [tool('search_documents', 'SUCCEEDED', 'records=3')]))
  assert.equal(progress.label, '处理完成')
  assert.equal(progress.hasDetails, true)
  assert.doesNotMatch(progress.label, /次|秒|条/)
})

test('processing state ignores raw model reasoning and untrusted tool names', () => {
  const progress = getAgentProgress(run('RUNNING', [tool('PRIVATE-REASONING')], { reasoning: 'PRIVATE-REASONING' }))
  assert.equal(progress.label, '正在处理资料…')
  assert.equal(JSON.stringify(progress).includes('PRIVATE-REASONING'), false)
})

test('derivation does not mutate the committed snapshot', () => {
  const value = Object.freeze(run('RUNNING', Object.freeze([Object.freeze(tool('search_documents'))])))
  assert.doesNotThrow(() => getAgentProgress(value))
})

test('prototype names cannot masquerade as supported tools', () => {
  for (const name of ['__proto__', 'constructor', 'toString']) {
    assert.equal(getAgentProgress(run('RUNNING', [tool(name)])).label, '正在处理资料…')
  }
})


test('waiting describes observed request state without inventing model reasoning', () => {
  const preparing = getAgentProgress(run('RUNNING', [], { modelCalls: 0 }))
  assert.equal(preparing.label, '正在准备模型请求…')

  const waiting = getAgentProgress(run('RUNNING', [], { modelCalls: 1 }))
  assert.equal(waiting.label, '等待模型响应…')
  assert.match(waiting.waitingMessage, /模型尚未返回内容/)
  assert.doesNotMatch(waiting.waitingMessage, /已检索|已阅读/)
  assert.equal(getAgentProgress(run('CANCELLED')).waitingMessage, '')
})
