import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { registerHooks } from 'node:module'
import { createRenderer, h, nextTick, ref } from 'vue'
import { parse, compileScript } from '@vue/compiler-sfc'

const componentUrl = new URL('../src/components/AgentCitationEvidence.vue', import.meta.url)
const source = await readFile(componentUrl, 'utf8')
const { descriptor } = parse(source, { filename: componentUrl.pathname })
const compiled = compileScript(descriptor, { id: 'agent-citation-evidence-test', inlineTemplate: true })
const apiUrl = `data:text/javascript,${encodeURIComponent('export const handlers = {}; export function previewChunkImageApi(...args) { return handlers.previewChunkImageApi(...args) }')}`
const popoverUrl = `data:text/javascript,${encodeURIComponent("export const ElPopover = { inheritAttrs: false, props: ['visible'], setup(props, { slots }) { return () => props.visible ? slots.default?.() : null } }")}`
const hooks = registerHooks({
  resolve(specifier, context, next) {
    if (context.parentURL === componentUrl.href) {
      if (specifier === '@/api/document') return { url: apiUrl, shortCircuit: true }

      if (specifier === 'element-plus') return { url: popoverUrl, shortCircuit: true }

      if (specifier.startsWith('@/utils/')) return { url: new URL(`../src/${specifier.slice(2)}.js`, import.meta.url).href, shortCircuit: true }
    }

    return next(specifier, context)
  },
  load(url, context, next) {
    if (url === componentUrl.href) return { format: 'module', source: compiled.content, shortCircuit: true }

    return next(url, context)
  }
})
const { default: AgentCitationEvidence } = await import(componentUrl.href)
const { handlers } = await import(apiUrl)

hooks.deregister()

/** 创建可控制先后顺序的图片请求。 */
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })

  return { promise, resolve, reject }
}

/** 创建后端提供的图片引用。 */
function citation(overrides = {}) {
  return { id: 'C1', documentId: 10, chunkId: 101, parseVersion: 2, documentName: '图表.pdf', imageSource: true, imageLabel: '图像描述（模型生成）', excerpt: '模型转译描述', imageUrl: 'https://model.invalid/untrusted.png', ...overrides }
}

/** 查找渲染树中的全部匹配节点。 */
function findAll(element, predicate) {
  return [...(predicate(element) ? [element] : []), ...element.children.flatMap((child) => findAll(child, predicate))]
}

/** 挂载真实组件，只替换网络、弹层外壳与浏览器资源。 */
function fixture(t, initial = {}) {
  const previousDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
  const listeners = new Map()

  globalThis.document = {
    addEventListener(type, listener) { listeners.set(type, listener) },
    removeEventListener(type, listener) { if (listeners.get(type) === listener) listeners.delete(type) }
  }

  const created = [], revoked = [], calls = [], invalid = []

  t.mock.method(URL, 'createObjectURL', (blob) => { const url = `blob:test-${created.length + 1}`; created.push({ url, blob }); return url })
  t.mock.method(URL, 'revokeObjectURL', (url) => revoked.push(url))
  handlers.previewChunkImageApi = (...args) => {
    const pending = deferred()

    calls.push({ args, pending })

    return pending.promise
  }

  const node = (tag, text = '') => ({
    tag, text, children: [], props: {}, parent: null, focused: false,
    focus() { this.focused = true },
    getAttribute(key) { return this.props[key] },
    contains(target) { return this === target || this.children.some((child) => child.contains(target)) }
  })

  const renderer = createRenderer({
    createElement: (tag) => node(tag),
    createText: (text) => node('#text', text),
    createComment: () => node('#comment'),
    setText(element, text) { element.text = text },
    setElementText(element, text) { element.text = text; element.children = [] },
    patchProp(element, key, previous, value) { element.props[key] = value },
    insert(child, parent, anchor) {
      if (child.parent) child.parent.children = child.parent.children.filter((item) => item !== child)

      const index = anchor ? parent.children.indexOf(anchor) : -1

      if (index < 0) parent.children.push(child)
      else parent.children.splice(index, 0, child)

      child.parent = parent
    },
    remove(child) { if (child.parent) child.parent.children = child.parent.children.filter((item) => item !== child); child.parent = null },
    parentNode: (element) => element.parent,
    nextSibling: (element) => element.parent?.children[element.parent.children.indexOf(element) + 1] || null
  })
  const props = ref({ open: true, spaceId: '1', loading: false, sources: [citation()], ...initial })
  const contextKey = ref('1:3')
  const app = renderer.createApp({ setup: () => () => h(AgentCitationEvidence, {
    key: contextKey.value,
    ...props.value,
    onClose() { props.value.open = false },
    onInvalid(cause) { invalid.push(cause); props.value.open = false }
  }) })
  const root = node('root')

  app.mount(root)

  let mounted = true

  const unmount = () => { if (mounted) { mounted = false; app.unmount() } }

  t.after(() => {
    unmount()

    if (previousDocument) Object.defineProperty(globalThis, 'document', previousDocument)
    else delete globalThis.document
  })

  return {
    props, contextKey, root, calls, created, revoked, listeners, invalid, unmount,
    buttons: () => findAll(root, (element) => element.tag === 'button' && element.props['aria-busy'] !== undefined),
    images: () => findAll(root, (element) => element.tag === 'img'),
    errors: () => findAll(root, (element) => element.props.role === 'alert')
  }
}

/** 完成指定的受权图片响应。 */
async function complete(panel, request, index = panel.calls.length - 1) {
  panel.calls[index].pending.resolve(new Blob(['image'], { type: 'image/png' }))
  await request
  await nextTick()
}

test('opening and reopening an image always authorizes again and never loads a model URL', async (t) => {
  const panel = fixture(t)

  await nextTick()

  assert.equal(panel.calls.length, 0)
  assert.deepEqual(panel.images(), [])

  const first = panel.buttons()[0].props.onClick()

  await nextTick()

  assert.equal(panel.buttons()[0].props.disabled, true)
  assert.deepEqual(panel.calls[0].args.slice(0, 4), ['1', 10, 101, 2])
  assert.ok(panel.calls[0].args[4] instanceof AbortSignal)
  await complete(panel, first)

  assert.equal(panel.images()[0].props.src, 'blob:test-1')
  assert.equal(panel.buttons()[0].props.disabled, undefined)
  panel.props.value.open = false
  await nextTick()

  assert.deepEqual(panel.revoked, ['blob:test-1'])
  panel.props.value.open = true
  await nextTick()

  assert.deepEqual(panel.images(), [])

  const second = panel.buttons()[0].props.onClick()

  await complete(panel, second)

  assert.equal(panel.calls.length, 2)
  assert.notEqual(panel.calls[0].args[4], panel.calls[1].args[4])
  assert.equal(panel.images()[0].props.src, 'blob:test-2')
  assert.match(findAll(panel.root, (element) => element.props.class === 'evidence-image-note')[0].text, /模型转译.*不是原文字符坐标/)
})

test('a repeated image request aborts its predecessor and ignores late success and failure', async (t) => {
  const panel = fixture(t)
  const click = panel.buttons()[0].props.onClick
  const first = click()
  const second = click()

  assert.equal(panel.calls[0].args[4].aborted, true)
  await complete(panel, second, 1)
  await complete(panel, first, 0)

  assert.equal(panel.created.length, 1)
  assert.equal(panel.images()[0].props.src, 'blob:test-1')

  const third = panel.buttons()[0].props.onClick()

  assert.deepEqual(panel.revoked, ['blob:test-1'])

  const fourth = panel.buttons()[0].props.onClick()

  panel.calls[2].pending.reject(Object.assign(new Error('Unauthorized'), { response: { status: 401 } }))
  await third

  assert.deepEqual(panel.invalid, [])
  await complete(panel, fourth, 3)

  assert.equal(panel.images()[0].props.src, 'blob:test-2')
})

for (const [label, change] of [
  ['close', (props) => { props.open = false }],
  ['source verification', (props) => { props.loading = true }],
  ['space switch', (props) => { props.spaceId = '2' }],
  ['source replacement', (props) => { props.sources = [citation({ id: 'C2' })] }],
  ['source removal', (props) => { props.sources = [] }],
  ['document change', (props) => { props.sources[0].documentId = 11 }],
  ['chunk change', (props) => { props.sources[0].chunkId = 102 }],
  ['version change', (props) => { props.sources[0].parseVersion = 3 }],
  ['image source revoked', (props) => { props.sources[0].imageSource = false }]
]) {
  test(`${label} aborts pending images, revokes displayed URLs and rejects late responses`, async (t) => {
    const panel = fixture(t, { sources: [citation(), citation({ id: 'C2', chunkId: 102 })] })
    const first = panel.buttons()[0].props.onClick()

    await complete(panel, first)

    const pending = panel.buttons()[1].props.onClick()

    change(panel.props.value)
    await nextTick()

    assert.equal(panel.calls[1].args[4].aborted, true)
    assert.deepEqual(panel.revoked, ['blob:test-1'])
    await complete(panel, pending)

    assert.equal(panel.created.length, 1)
    assert.deepEqual(panel.images(), [])
  })
}

for (const cause of [
  Object.assign(new Error('Unauthorized'), { response: { status: 401 } }),
  Object.assign(new Error('Forbidden'), { response: { status: 403 } }),
  Object.assign(new Error('Not found'), { response: { status: 404 } }),
  Object.assign(new Error('原图已更新或不可访问'), { code: 'SOURCE_CHANGED' })
]) {
  test(`${cause.message} hides all old images and closes invalid evidence`, async (t) => {
    const panel = fixture(t, { sources: [citation(), citation({ id: 'C2', chunkId: 102 }), citation({ id: 'C3', chunkId: 103 })] })
    const first = panel.buttons()[0].props.onClick()

    await complete(panel, first)

    const pending = panel.buttons()[1].props.onClick()
    const denied = panel.buttons()[2].props.onClick()

    panel.calls[2].pending.reject(cause)
    await denied
    await nextTick()

    assert.deepEqual(panel.invalid, [cause])
    assert.equal(panel.props.value.open, false)
    assert.equal(panel.calls[1].args[4].aborted, true)
    assert.deepEqual(panel.revoked, ['blob:test-1'])
    await complete(panel, pending, 1)

    assert.deepEqual(panel.images(), [])
    assert.equal(panel.created.length, 1)
  })
}

test('a response from a closed preview cannot refill the reopened preview', async (t) => {
  const panel = fixture(t)
  const old = panel.buttons()[0].props.onClick()

  panel.props.value.open = false
  await nextTick()

  assert.equal(panel.calls[0].args[4].aborted, true)
  panel.props.value.open = true
  await nextTick()
  await complete(panel, old, 0)

  assert.deepEqual(panel.images(), [])
  assert.equal(panel.created.length, 0)

  const current = panel.buttons()[0].props.onClick()

  await complete(panel, current, 1)

  assert.equal(panel.images()[0].props.src, 'blob:test-1')
})

test('switching sessions disposes the old preview even when citation IDs are reused', async (t) => {
  const panel = fixture(t, { sources: [citation(), citation({ id: 'C2' })] })
  const first = panel.buttons()[0].props.onClick()

  await complete(panel, first)

  const old = panel.buttons()[1].props.onClick()

  panel.contextKey.value = '1:4'
  await nextTick()

  assert.equal(panel.calls[1].args[4].aborted, true)
  assert.deepEqual(panel.revoked, ['blob:test-1'])
  await complete(panel, old, 1)

  assert.deepEqual(panel.images(), [])
  assert.equal(panel.created.length, 1)

  const current = panel.buttons()[0].props.onClick()

  await complete(panel, current, 2)

  assert.equal(panel.images()[0].props.src, 'blob:test-2')
})

test('temporary preview capacity errors keep other images visible and allow retry', async (t) => {
  const panel = fixture(t, { sources: [citation(), citation({ id: 'C2', chunkId: 102 })] })
  const first = panel.buttons()[0].props.onClick()

  await complete(panel, first)

  const busy = panel.buttons()[1].props.onClick()

  panel.calls[1].pending.reject(Object.assign(new Error('图片预览繁忙，请稍后再试'), {
    code: 'IMAGE_BUSY', response: { status: 429 }
  }))
  await busy
  await nextTick()

  assert.equal(panel.props.value.open, true)
  assert.deepEqual(panel.invalid, [])
  assert.equal(panel.images().length, 1)
  assert.deepEqual(panel.revoked, [])
  assert.match(panel.errors()[0].text, /繁忙/)

  const retry = panel.buttons()[1].props.onClick()

  await complete(panel, retry)

  assert.equal(panel.images().length, 2)
})

test('network and decoding failures provide retry without retaining the old image', async (t) => {
  const panel = fixture(t)
  const first = panel.buttons()[0].props.onClick()

  panel.calls[0].pending.reject(new Error('Network failure'))
  await first
  await nextTick()

  assert.match(panel.errors()[0].text, /加载失败/)

  const second = panel.buttons()[0].props.onClick()

  await complete(panel, second)

  assert.deepEqual(panel.errors(), [])

  const oldImage = panel.images()[0]

  oldImage.props.onError({ target: oldImage })
  await nextTick()

  assert.deepEqual(panel.images(), [])
  assert.deepEqual(panel.revoked, ['blob:test-1'])
  assert.match(panel.errors()[0].text, /无法显示/)

  const third = panel.buttons()[0].props.onClick()

  await complete(panel, third)
  oldImage.props.onError({ target: oldImage })
  await nextTick()

  assert.equal(panel.images()[0].props.src, 'blob:test-2')
})

test('unmount cancels pending images, releases URLs and removes keyboard and pointer listeners', async (t) => {
  const panel = fixture(t, { sources: [citation(), citation({ id: 'C2' })] })
  const first = panel.buttons()[0].props.onClick()

  await complete(panel, first)

  const pending = panel.buttons()[1].props.onClick()

  assert.equal(panel.listeners.size, 2)
  panel.unmount()

  assert.equal(panel.listeners.size, 0)
  assert.equal(panel.calls[1].args[4].aborted, true)
  assert.deepEqual(panel.revoked, ['blob:test-1'])
  await complete(panel, pending)

  assert.equal(panel.created.length, 1)
})

test('keyboard dismissal and narrow layouts preserve accessible evidence controls', async (t) => {
  const panel = fixture(t)

  await nextTick()

  const dialog = findAll(panel.root, (element) => element.props.role === 'dialog')[0]

  assert.equal(dialog.focused, true)
  assert.equal(panel.buttons()[0].props.type, 'button')

  let prevented = false, stopped = false

  panel.listeners.get('keydown')({ key: 'Escape', preventDefault() { prevented = true }, stopPropagation() { stopped = true } })
  await nextTick()

  assert.equal(panel.props.value.open, false)
  assert.equal(prevented && stopped, true)
  assert.match(source, /maxWidth: 'calc\(100vw - 32px\)'/)
  assert.match(source, /\.evidence-image \{[^}]*max-width: 100%/)
  assert.match(source, /:focus-visible/)
})

test('the view binds image previews to the current space and session and invalidates changed sources', async () => {
  const view = await readFile(new URL('../src/views/AgentView.vue', import.meta.url), 'utf8')

  assert.match(view, /<AgentCitationEvidence\s+:key="`\$\{chat\.spaceId\.value\}:\$\{chat\.sessionId\.value\}`"/)
  assert.match(view, /:space-id="chat\.spaceId\.value"/)
  assert.match(view, /@invalid="invalidateCitationEvidence"/)
  assert.match(view, /'documentId', 'chunkId', 'parseVersion', 'imageSource'/)
  assert.match(view, /if \(requestId !== citationRequest\) return/)
})
