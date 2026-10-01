import test from 'node:test'
import assert from 'node:assert/strict'
import { groupAgentCitations, findMessageCitation } from '../src/utils/agentCitations.js'

const source = (id, chunkIndex, changes = {}) => ({
  id, documentId: 10, parseVersion: 1, documentName: 'Java-28届.pdf',
  chunkId: 100 + chunkIndex, chunkIndex, pageNumber: 1, ...changes
})

test('multiple chunks from one document produce one card without renumbering citations', () => {
  const sources = Object.freeze([source('C1', 0), source('C2', 1), source('C3', 2)].map(Object.freeze))
  const groups = groupAgentCitations(sources)
  assert.equal(groups.length, 1)
  assert.equal(groups[0].documentName, 'Java-28届.pdf')
  assert.deepEqual(groups[0].sourceIds, ['C1', 'C2', 'C3'])
  assert.equal(groups[0].location, '第 1 页 · 3 个片段')
  assert.equal(groups[0].sources[1], sources[1])
})

test('same filenames and different parsing versions are never incorrectly merged', () => {
  const groups = groupAgentCitations([
    source('C1', 0), source('C2', 1, { documentId: 11 }), source('C3', 2, { parseVersion: 2 })
  ])
  assert.equal(groups.length, 3)
  assert.equal(new Set(groups.map((group) => group.key)).size, 3)
})

test('page summaries preserve gaps and never invent unavailable page or chunk positions', () => {
  assert.equal(groupAgentCitations([source('C1', 0), source('C2', 3, { pageNumber: 3 })])[0].location, '第 1、3 页 · 2 个片段')
  assert.equal(groupAgentCitations([source('C1', 0, { pageNumber: null })])[0].location, '片段 1')
  assert.equal(groupAgentCitations([source('C1', null, { pageNumber: null })])[0].location, '')
  assert.match(groupAgentCitations([source('C1', 0), source('C2', 1, { pageNumber: null })])[0].location, /已知页码/)
})

test('duplicate IDs do not duplicate badges and an empty answer has no source cards', () => {
  const citation = source('C1', 0)
  assert.deepEqual(groupAgentCitations([citation, citation])[0].sourceIds, ['C1'])
  assert.deepEqual(groupAgentCitations([]), [])
})

test('inline citation clicks resolve the assistant message, not the preceding user message', () => {
  const citation = source('C2', 1)
  const messages = [
    { runId: 1, role: 'USER', citations: [] },
    { runId: 1, role: 'ASSISTANT', citations: [citation] },
    { runId: 2, role: 'ASSISTANT', citations: [source('C2', 0, { documentId: 20 })] }
  ]
  assert.equal(findMessageCitation(messages, '1', 'C2'), citation)
  assert.equal(findMessageCitation(messages, 3, 'C2'), undefined)
  assert.equal(findMessageCitation([{ ...messages[1], masked: true }], 1, 'C2'), undefined)
})
