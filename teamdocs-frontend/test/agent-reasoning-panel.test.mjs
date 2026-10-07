import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { registerHooks } from 'node:module'
import { createSSRApp, createRenderer, h, nextTick, ref } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { parse, compileScript } from '@vue/compiler-sfc'

const componentUrl = new URL('../src/components/AgentReasoningPanel.vue', import.meta.url)
const source = await readFile(componentUrl, 'utf8')
const { descriptor } = parse(source, { filename: componentUrl.pathname })
const compiled = compileScript(descriptor, { id: 'agent-reasoning-panel-test', inlineTemplate: true })
const hooks = registerHooks({
  load(url, context, next) {
    if (url === componentUrl.href) return { format: 'module', source: compiled.content, shortCircuit: true }

    return next(url, context)
  }
})
const { default: AgentReasoningPanel } = await import(componentUrl.href)

hooks.deregister()

/** 创建带真实思考的消息。 */
function message(overrides = {}) {
  return { id: 2, runId: 10, role: 'ASSISTANT', text: '', masked: false, reasoningContent: '核对资料\n再确认来源 [C1]', reasoningDurationMs: null, reasoningTruncated: false, runStatus: 'RUNNING', ...overrides }
}

/** 渲染单条消息的思考卡片。 */
function render(value) {
  return renderToString(createSSRApp(AgentReasoningPanel, { message: value, runKey: '1:3:10:ASSISTANT' }))
}

/** 查找自定义渲染树中的节点。 */
function find(element, predicate) {
  return predicate(element) ? element : element.children.map((child) => find(child, predicate)).find(Boolean)
}

/** 挂载可更新消息的思考卡片。 */
function fixture(t, initial = message()) {
  const node = (tag, text = '') => ({ tag, text, children: [], props: {}, parent: null })

  const renderer = createRenderer({
    createElement: node,
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
    remove(child) {
      if (child.parent) child.parent.children = child.parent.children.filter((item) => item !== child)
    },
    parentNode: (element) => element.parent,
    nextSibling: (element) => element.parent?.children[element.parent.children.indexOf(element) + 1] || null
  })
  const value = ref(initial)
  const runKey = ref('1:3:10:ASSISTANT')
  const app = renderer.createApp({ setup: () => () => h(AgentReasoningPanel, { message: value.value, runKey: runKey.value }) })
  const root = node('root')

  app.mount(root)
  t.after(() => app.unmount())

  return { value, runKey, root, button: () => find(root, (element) => element.tag === 'button'), body: () => find(root, (element) => element.props.class === 'reasoning-body') }
}

test('live reasoning expands immediately and uses accessible native controls', async () => {
  const html = await render(message())

  assert.match(html, /<button[^>]*type="button"/)
  assert.match(html, /aria-expanded="true"/)
  assert.match(html, /aria-controls="agent-reasoning-1%3A3%3A10%3AASSISTANT"/)
  assert.match(html, /aria-describedby=/)
  assert.match(html, /正在深度思考/)
  assert.match(html, /reasoning-indicator live/)
  assert.match(html, /reasoning-cursor/)
  assert.match(html, /接收思考输出的可观测耗时，并非模型内部计算耗时/)
  assert.match(html, /思考内容仅保留在当前页面，刷新或切换会话后清空/)
})

test('full snapshots show actual text without replay, HTML execution or citation links', async (t) => {
  const panel = fixture(t)
  const button = panel.button()

  panel.value.value = message({ reasoningContent: '<script>alert(1)</script>\n<img src=x onerror=alert(2)> [C1]' })
  await nextTick()

  assert.equal(panel.button(), button)

  const text = find(panel.root, (element) => element.tag === 'span' && element.text.startsWith('<script>'))

  assert.equal(text.text, panel.value.value.reasoningContent)
  assert.equal(find(panel.root, (element) => ['script', 'img', 'a'].includes(element.tag)), undefined)

  const html = await render(panel.value.value)

  assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/)
  assert.match(html, /\[C1\]/)
  assert.doesNotMatch(html, /<script|<img|<a\b|data-citation|innerHTML/)
})

test('manual collapse survives incremental snapshots and completion folds only once', async (t) => {
  const panel = fixture(t)

  assert.equal(panel.button().props['aria-expanded'], true)
  panel.button().props.onClick()
  await nextTick()

  assert.equal(panel.body().props.hidden, true)
  panel.value.value = message({ reasoningContent: '新的完整思考内容', reasoningDurationMs: 100 })
  await nextTick()

  assert.equal(panel.button().props['aria-expanded'], false)
  panel.button().props.onClick()
  await nextTick()
  panel.value.value = message({ runStatus: 'SUCCEEDED', text: '最终回答', reasoningDurationMs: 1500 })
  await nextTick()

  assert.equal(panel.button().props['aria-expanded'], false)
  panel.button().props.onClick()
  await nextTick()

  const button = panel.button()

  panel.value.value = { ...panel.value.value, id: 20, citations: [] }
  await nextTick()

  assert.equal(panel.button(), button)
  assert.equal(panel.button().props['aria-expanded'], true)
  assert.equal(panel.body().props.hidden, false)
})

test('a new run or context resets expansion without inheriting manual state', async (t) => {
  const panel = fixture(t)

  panel.button().props.onClick()
  await nextTick()
  panel.runKey.value = '1:3:11:ASSISTANT'
  panel.value.value = message({ runId: 11 })
  await nextTick()

  assert.equal(panel.button().props['aria-expanded'], true)
  panel.runKey.value = '2:3:11:ASSISTANT'
  panel.value.value = message({ runId: 11, runStatus: 'SUCCEEDED' })
  await nextTick()

  assert.equal(panel.button().props['aria-expanded'], false)
})

test('completed page-local reasoning is collapsed and preserves manual expansion on refresh', async (t) => {
  const panel = fixture(t, message({ runStatus: 'SUCCEEDED', reasoningDurationMs: 2300 }))

  assert.equal(panel.button().props['aria-expanded'], false)
  panel.button().props.onClick()
  await nextTick()
  panel.value.value = message({ runStatus: 'SUCCEEDED', reasoningDurationMs: 2300 })
  await nextTick()

  assert.equal(panel.button().props['aria-expanded'], true)

  const html = await render(panel.value.value)

  assert.match(html, /已深度思考（耗时 2\.3 秒）/)
  assert.doesNotMatch(html, /reasoning-cursor|reasoning-indicator live/)
})

test('each cached card uses its own status rather than the current run', async () => {
  const html = await renderToString(createSSRApp({
    render: () => h('div', [
      h(AgentReasoningPanel, { message: message({ runStatus: 'SUCCEEDED', reasoningDurationMs: 1000 }), runKey: '1:3:10' }),
      h(AgentReasoningPanel, { message: message({ runId: 11 }), runKey: '1:3:11' })
    ])
  }))

  assert.equal((html.match(/aria-expanded="true"/g) || []).length, 1)
  assert.equal((html.match(/aria-expanded="false"/g) || []).length, 1)
  assert.match(html, /已深度思考（耗时 1\.0 秒）/)
  assert.match(html, /正在深度思考/)
})

test('missing, blank and masked reasoning never creates a thinking card', async () => {
  for (const value of [{ text: '旧回答' }, message({ reasoningContent: null }), message({ reasoningContent: ' \n ' }), message({ masked: true })]) {
    const html = await render(value)

    assert.doesNotMatch(html, /agent-reasoning|核对资料|已深度思考|正在深度思考/)
  }
})

test('reasoning arriving after the placeholder still defaults to expanded', async (t) => {
  const panel = fixture(t, message({ reasoningContent: null }))

  assert.equal(panel.button(), undefined)
  panel.value.value = message()
  await nextTick()

  assert.equal(panel.button().props['aria-expanded'], true)
  panel.value.value = message({ masked: true })
  await nextTick()

  assert.equal(panel.button(), undefined)
  assert.equal(find(panel.root, (element) => element.text.includes('核对资料')), undefined)
})

test('cancelled and failed reasoning stops animation and automatically collapses', async (t) => {
  const panel = fixture(t)

  for (const [status, title] of [['CANCELLED', '思考已停止'], ['FAILED', '思考已中断']]) {
    panel.runKey.value = `1:3:${status}`
    panel.value.value = message()
    await nextTick()
    panel.value.value = message({ runStatus: status })
    await nextTick()

    assert.equal(panel.button().props['aria-expanded'], false)

    const html = await render(panel.value.value)

    assert.match(html, new RegExp(title))
    assert.doesNotMatch(html, /reasoning-cursor|reasoning-indicator live|已深度思考/)
  }
})

test('unknown durations stay unknown, zero is sub-tenth and truncation remains visible', async () => {
  const unknown = await render(message({ runStatus: 'SUCCEEDED', reasoningDurationMs: null }))

  assert.match(unknown, /class="reasoning-title">已深度思考<\/span>/)
  assert.doesNotMatch(unknown, /已深度思考（耗时/)

  const zero = await render(message({ runStatus: 'SUCCEEDED', reasoningDurationMs: 0, reasoningTruncated: true }))

  assert.match(zero, /已深度思考（耗时 少于 0\.1 秒）/)
  assert.match(zero, /思考内容已截断，仅展示已保留的部分/)
  assert.ok(zero.indexOf('reasoning-truncated') < zero.indexOf('class="reasoning-body"'))
})

test('a newer unknown duration removes the old timing label without replacing the control', async (t) => {
  const panel = fixture(t, message({ runStatus: 'SUCCEEDED', reasoningDurationMs: 2300 }))
  const button = panel.button()

  panel.button().props.onClick()
  await nextTick()
  panel.value.value = message({ runStatus: 'SUCCEEDED', reasoningDurationMs: null })
  await nextTick()

  assert.equal(panel.button(), button)
  assert.equal(panel.button().props['aria-expanded'], true)
  assert.equal(find(panel.root, (element) => element.props.class === 'reasoning-title').text, '已深度思考')
})

test('styles wrap long plain text and respect reduced motion', () => {
  assert.match(source, /white-space: pre-wrap/)
  assert.match(source, /overflow-wrap: anywhere/)
  assert.match(source, /min-width: 0/)
  assert.match(source, /prefers-reduced-motion: reduce/)
  assert.match(source, /animation: none/)
  assert.match(source, /:focus-visible/)
})

test('the view uses scoped stable keys and no separate pending-answer branch', async () => {
  const view = await readFile(new URL('../src/views/AgentView.vue', import.meta.url), 'utf8')

  assert.match(view, /:key="messageKey\(message\)"/)
  assert.match(view, /:run-key="messageKey\(message\)"/)
  assert.match(view, /chat\.spaceId\.value.*chat\.sessionId\.value.*message\.runId \?\? message\.id/)
  assert.doesNotMatch(view, /showPendingProcess/)
  assert.match(view, /\(!hasReasoning\(message\) \|\| chat\.snapshot\.value\?\.tools\?\.length \|\| chat\.snapshot\.value\?\.errorCode\)/)
  assert.match(view, /v-if="message\.role !== 'ASSISTANT' \|\| message\.text\?\.trim\(\)"/)
  assert.match(view, /message\.reasoningContent\?\.length/)
  assert.match(view, /if \(following\.value\) scrollToEnd\(\)\s*else hasNewContent\.value = true/)
})
