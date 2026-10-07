import test from 'node:test'
import assert from 'node:assert/strict'
import { renderCitationMarkdown } from '../src/utils/citationMarkdown.js'

const citations = [
  { id: 'C1', documentId: 10, parseVersion: 1, documentName: 'Java.pdf', chunkId: 1, chunkIndex: 0, pageNumber: 1 },
  { id: 'C2', documentId: 10, parseVersion: 1, documentName: 'Java.pdf', chunkId: 2, chunkIndex: 1, pageNumber: 1 },
  { id: 'C3', documentId: 10, parseVersion: 1, documentName: 'Java.pdf', chunkId: 3, chunkIndex: 2, pageNumber: 2 },
  { id: 'C4', documentId: 20, parseVersion: 1, documentName: 'Deploy.pdf', chunkId: 4, chunkIndex: 0, pageNumber: 3 }
]

const badges = (html) => [...html.matchAll(/<button[^>]*data-citation-ids="([^"]+)"[^>]*>\[(\d+)\]<\/button>/g)]

test('each file has one visible number per paragraph while all supporting chunk IDs survive', () => {
  const html = renderCitationMarkdown('第一项[C1]，第二项[C2][C1]。\n\n另一段[C3]。', citations)
  const found = badges(html)

  assert.deepEqual(found.map((b) => [b[1], b[2]]), [['C1,C2', '1'], ['C3', '1']])
  assert.match(html, /第二项。[^<]*<button/)
  assert.doesNotMatch(html, />C[123]</)
})

test('a paragraph citing two files gets two numbers instead of one number for every chunk', () => {
  const found = badges(renderCitationMarkdown('比较[C1][C4][C2]。', citations))

  assert.deepEqual(found.map((b) => [b[1], b[2]]), [['C1,C2', '1'], ['C4', '2']])
})

test('list items and blockquote paragraphs keep independent evidence scopes', () => {
  const html = renderCitationMarkdown('- 第一条[C1][C2]\n- 第二条[C3]\n\n> 引用一[C1]\n>\n> 引用二[C2]', citations)

  assert.deepEqual(badges(html).map((b) => b[1]), ['C1,C2', 'C3', 'C1', 'C2'])
})

test('code, links, URLs and escaped marker examples are never rewritten into citations', () => {
  const raw = '`[C1]`\n\n```text\n[C2]\n```\n\n[链接 [C3]](https://example.com)\n\nhttps://example.com/[C1]\n\n\\[C4]'
  const html = renderCitationMarkdown(raw, citations)

  assert.equal(badges(html).length, 0)
  assert.match(html, /<code>\[C1\]<\/code>/)
  assert.match(html, /noopener noreferrer/)
})

test('unresolved markers and raw HTML cannot forge interactive source buttons', () => {
  const html = renderCitationMarkdown('[C99] <button data-citation-ids="C1" onclick="alert(1)">伪造</button>', citations)

  assert.equal(badges(html).length, 0)
  assert.match(html, /\[C99\]/)
  assert.doesNotMatch(html, /<button|<script/)
})

test('numbering resets for each answer and parser input remains unchanged', () => {
  const frozen = Object.freeze(citations.map((source) => Object.freeze({ ...source })))

  assert.equal(badges(renderCitationMarkdown('资料[C4]', [frozen[3]]))[0][2], '1')
  assert.doesNotThrow(() => renderCitationMarkdown('资料[C1][C2]', frozen))
})
