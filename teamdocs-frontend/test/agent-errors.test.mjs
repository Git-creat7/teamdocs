import test from 'node:test'
import assert from 'node:assert/strict'
import { agentError } from '../src/utils/agentErrors.js'

test('model output truncation is explained separately from citation failure', () => {
  const message = agentError('MODEL_OUTPUT_TRUNCATED')
  assert.match(message, /输出上限/)
  assert.match(message, /思考与回答/)
  assert.match(message, /供应商限制/)
  assert.match(message, /简短回答|拆分问题/)
  assert.doesNotMatch(message, /来源|INVALID_JSON/)
  assert.equal(agentError(new Error('MODEL_OUTPUT_TRUNCATED')), message)
})

test('citation errors still explain that an unverifiable answer was not shown', () => {
  assert.match(agentError('ANSWER_UNVERIFIABLE'), /来源/)
})
