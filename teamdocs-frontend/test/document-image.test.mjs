import test from 'node:test'
import assert from 'node:assert/strict'
import { registerHooks } from 'node:module'

const documentUrl = new URL('../src/api/document.js', import.meta.url)
const requestUrl = `data:text/javascript,${encodeURIComponent('export const handlers = {}; export default { get(...args) { return handlers.get(...args) } }')}`
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (context.parentURL === documentUrl.href) {
    if (specifier === '@/utils/request') return { url: requestUrl, shortCircuit: true }
    if (specifier === '@/utils/normalize') return { url: new URL('../src/utils/normalize.js', import.meta.url).href, shortCircuit: true }
  }
  return next(specifier, context)
} })
const { previewChunkImageApi } = await import(documentUrl.href)
const { handlers } = await import(requestUrl)
hooks.deregister()

/** 捕获图片请求，不连接后端。 */
function fixture(blob) {
  const calls = []
  handlers.get = async (...args) => { calls.push(args); return blob }
  return calls
}

test('chunk images use the authenticated request client with version, blob type and cancellation signal', async () => {
  const blob = new Blob(['image'], { type: 'image/png' })
  const calls = fixture(blob)
  const controller = new AbortController()
  assert.equal(await previewChunkImageApi('1', 10, 101, 2, controller.signal), blob)
  assert.deepEqual(calls, [[
    '/spaces/1/documents/10/chunks/101/image',
    { params: { parseVersion: 2 }, responseType: 'blob', signal: controller.signal, silent: true }
  ]])
})

test('every image open issues a fresh authorized request instead of reusing a URL or cached blob', async () => {
  const blob = new Blob(['image'], { type: 'image/jpeg' })
  const calls = fixture(blob)
  const first = new AbortController(), second = new AbortController()
  await previewChunkImageApi(1, 10, 101, 2, first.signal)
  await previewChunkImageApi(1, 10, 101, 2, second.signal)
  assert.equal(calls.length, 2)
  assert.equal(calls[0][1].signal, first.signal)
  assert.equal(calls[1][1].signal, second.signal)
})

test('identifiers cannot inject a URL path or query into the image endpoint', async () => {
  const calls = fixture(new Blob(['image'], { type: 'image/webp' }))
  await previewChunkImageApi('1/2', '10?url=other', '101#image', 2)
  assert.equal(calls[0][0], '/spaces/1%2F2/documents/10%3Furl%3Dother/chunks/101%23image/image')
  assert.deepEqual(calls[0][1].params, { parseVersion: 2 })
})

test('only bounded PNG, JPEG and WebP blobs are accepted', async () => {
  for (const type of ['image/png', 'image/jpeg', 'image/webp']) {
    const blob = new Blob(['image'], { type })
    fixture(blob)
    assert.equal(await previewChunkImageApi(1, 10, 101, 2), blob)
  }
  const boundary = new Blob([new Uint8Array(4 * 1024 * 1024)], { type: 'image/png' })
  fixture(boundary)
  assert.equal(await previewChunkImageApi(1, 10, 101, 2), boundary)
  for (const blob of [
    new Blob([], { type: 'image/png' }),
    new Blob([new Uint8Array(4 * 1024 * 1024 + 1)], { type: 'image/png' }),
    new Blob(['<svg onload="alert(1)"></svg>'], { type: 'image/svg+xml' }),
    new Blob(['<img src="https://model.invalid/image">'], { type: 'text/html' }),
    new Blob(['gif'], { type: 'image/gif' }),
    'https://model.invalid/untrusted.png',
    null
  ]) {
    fixture(blob)
    await assert.rejects(previewChunkImageApi(1, 10, 101, 2), { code: 'IMAGE_UNAVAILABLE', message: '原图加载失败，请重试' })
  }
})

test('JSON business errors and malformed responses never become image object URLs', async () => {
  fixture(new Blob([JSON.stringify({ code: 0, msg: '原图已更新或不可访问', errorCode: 'SOURCE_CHANGED' })], { type: 'application/json' }))
  await assert.rejects(previewChunkImageApi(1, 10, 101, 2), { code: 'SOURCE_CHANGED', message: '原图已更新或不可访问' })
  fixture(new Blob(['not JSON'], { type: 'application/json' }))
  await assert.rejects(previewChunkImageApi(1, 10, 101, 2), { code: 'IMAGE_UNAVAILABLE', message: '原图加载失败，请重试' })
})

test('temporary busy responses remain retryable rather than invalidating the source', async () => {
  const detail = { code: 0, msg: '图片预览繁忙，请稍后再试', errorCode: 'IMAGE_BUSY' }
  fixture(new Blob([JSON.stringify(detail)], { type: 'application/json' }))
  await assert.rejects(previewChunkImageApi(1, 10, 101, 2), { code: 'IMAGE_BUSY', message: detail.msg })
  handlers.get = async () => { throw Object.assign(new Error('HTTP 429'), {
    response: { status: 429, data: new Blob([JSON.stringify(detail)], { type: 'application/json' }) }
  }) }
  await assert.rejects(previewChunkImageApi(1, 10, 101, 2), { code: 'IMAGE_BUSY', message: detail.msg })
  fixture(new Blob([JSON.stringify({ code: 0, msg: detail.msg })], { type: 'application/json' }))
  await assert.rejects(previewChunkImageApi(1, 10, 101, 2), { code: 'IMAGE_UNAVAILABLE' })
})

test('HTTP authentication and source failures retain their status for the evidence panel', async () => {
  for (const status of [401, 403, 404, 409, 410]) {
    const error = Object.assign(new Error('Rejected'), { response: { status } })
    handlers.get = async () => { throw error }
    await assert.rejects(previewChunkImageApi(1, 10, 101, 2), (cause) => cause === error && cause.response.status === status)
  }
})

test('closing a pending image propagates cancellation to the authorized request', async () => {
  handlers.get = (url, { signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(Object.assign(new Error('Cancelled'), { code: 'ERR_CANCELED' })), { once: true })
  })
  const controller = new AbortController()
  const pending = previewChunkImageApi(1, 10, 101, 2, controller.signal)
  controller.abort()
  await assert.rejects(pending, { code: 'ERR_CANCELED' })
})
