import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { registerHooks } from 'node:module'
import { createSSRApp, createRenderer, h, nextTick, ref } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { parse, compileScript } from '@vue/compiler-sfc'

const componentUrl = new URL('../src/components/AgentProcessingState.vue', import.meta.url)
const source = await readFile(componentUrl, 'utf8')
const { descriptor } = parse(source, { filename: componentUrl.pathname })
const compiled = compileScript(descriptor, { id: 'agent-processing-state-test', inlineTemplate: true })
const hooks = registerHooks({
  resolve(specifier, context, next) {
    if (context.parentURL === componentUrl.href && specifier.startsWith('@/')) {
      return { url: new URL(`../src/${specifier.slice(2)}.js`, import.meta.url).href, shortCircuit: true }
    }

    return next(specifier, context)
  },
  load(url, context, next) {
    if (url === componentUrl.href) return { format: 'module', source: compiled.content, shortCircuit: true }

    return next(url, context)
  }
})
const { default: AgentProcessingState } = await import(componentUrl.href)

hooks.deregister()

const render = (run) => renderToString(createSSRApp(AgentProcessingState, { run }))

const tool = (sequence, toolName, status = 'RUNNING') => ({ sequence, toolName, status, durationMs: 120, resultSummary: 'records=2' })

test('active process is expanded but technical counters remain collapsed', async () => {
  const html = await render({ status: 'RUNNING', toolCalls: 2, tools: [tool(1, 'search_documents', 'SUCCEEDED'), tool(2, 'read_document_chunks')] })

  assert.match(html, /<details[\s>]/)
  assert.match(html, /<details[^>]*\bopen(?:\s|=|>)/)
  assert.match(html, /<details class="activity-technical"[^>]*>/)
  assert.doesNotMatch(html, /<details class="activity-technical"[^>]*\bopen(?:\s|=|>)/)

  const summary = html.match(/<summary[^>]*>([\s\S]*?)<\/summary>/)[1]

  assert.match(summary, /正在阅读文档内容/)
  assert.doesNotMatch(summary, /次|秒|条结果/)
  assert.match(html, /工具调用\s*2\s*次/)
  assert.match(html, /0\.1 秒/)
})

test('a completed zero-tool reply leaves no retrieval panel', async () => {
  const html = await render({ status: 'SUCCEEDED', toolCalls: 0, tools: [] })

  assert.doesNotMatch(html, /agent-activity|检索|工具调用|<details/)
})

test('succeeded tools display a static checkmark and only running tools have the spin class', async () => {
  const html = await render({
    status: 'RUNNING',
    toolCalls: 2,
    tools: [tool(1, 'read_document_chunks', 'SUCCEEDED'), tool(2, 'search_documents', 'RUNNING')]
  })

  assert.match(html, /<svg[^>]*class="[^"]*tool-status-icon\s+is-ok[^"]*"/)
  assert.match(html, /<svg[^>]*class="[^"]*agent-progress-spin\s+tool-status-icon\s+is-running[^"]*"/)
})

test('cancelled steps stop animating and never display raw reasoning or answer text', async () => {
  const html = await render({
    status: 'CANCELLED', toolCalls: 1, tools: [tool(1, 'read_document_chunks')], errorCode: 'USER_CANCELLED',
    reasoning: 'PRIVATE-REASONING', answer: { text: 'UNVALIDATED-ANSWER' }
  })

  assert.match(html, /正文读取已停止/)
  assert.doesNotMatch(html, /agent-progress-spin|is-running|PRIVATE-REASONING|UNVALIDATED-ANSWER/)
})


test('the first model call has an expanded explanation instead of a pretend thinking label', async () => {
  const html = await render({ id: 1, status: 'RUNNING', modelCalls: 1, toolCalls: 0, tools: [] })

  assert.match(html, /<details[^>]*\bopen(?:\s|=|>)/)
  assert.match(html, /等待模型响应/)
  assert.match(html, /模型尚未返回内容/)
  assert.doesNotMatch(html, /正在理解|正在思考|工具调用/)
})

test('completed tool work is collapsed and can be inspected without replaying a running stage', async () => {
  const html = await render({ id: 1, status: 'SUCCEEDED', modelCalls: 2, toolCalls: 1, tools: [tool(1, 'search_documents', 'SUCCEEDED')] })

  assert.doesNotMatch(html, /<details[^>]*\bopen(?:\s|=|>)/)
  assert.match(html, /已完成文档查找/)
  assert.doesNotMatch(html, /等待模型响应|agent-progress-spin/)
})


test('manual collapse survives stream updates, then resets for a new run', async (t) => {
  const node = (tag) => ({ tag, children: [], props: {}, parent: null })

  const renderer = createRenderer({
    createElement: node,
    createText: () => node('#text'),
    createComment: () => node('#comment'),
    setText() {},
    setElementText() {},
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
  const run = ref({ id: 1, status: 'RUNNING', tools: [] })
  const app = renderer.createApp({ setup: () => () => h(AgentProcessingState, { run: run.value }) })
  const root = node('root')

  app.mount(root)
  t.after(() => app.unmount())

  const find = (element) => element.props['aria-label'] === '处理过程'
    ? element : element.children.map(find).find(Boolean)

  assert.equal(find(root).props.open, true)
  find(root).props.onToggle({ target: { open: false } })
  await nextTick()

  assert.equal(find(root).props.open, false)

  run.value = { ...run.value, tools: [tool(1, 'search_documents')] }
  await nextTick()

  assert.equal(find(root).props.open, false)

  run.value = { ...run.value, status: 'SUCCEEDED' }
  await nextTick()

  assert.equal(find(root).props.open, false)

  run.value = { id: 2, status: 'RUNNING', tools: [] }
  await nextTick()

  assert.equal(find(root).props.open, true)
})
