import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile, access } from 'node:fs/promises'
import { parse, compileScript } from '@vue/compiler-sfc'

for (const name of ['AgentScopePicker', 'AnswerFeedback', 'DocumentIndexPanel']) {
  test(`${name} compiles and cancels stale requests on disposal`, async () => {
    const source = await readFile(new URL(`../src/components/${name}.vue`, import.meta.url), 'utf8')
    const { descriptor } = parse(source)
    assert.doesNotThrow(() => compileScript(descriptor, { id: name, inlineTemplate: true }))
    assert.match(source, /onBeforeUnmount\(/)
    assert.match(source, /signal.aborted/)
    assert.doesNotMatch(source, /v-html/)
  })
}

test('tag route, API and controls are removed while index and feedback entries are present', async () => {
  await assert.rejects(access(new URL('../src/api/tag.js', import.meta.url)))
  const router = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')
  assert.doesNotMatch(router, /TagManageView|path: 'tags'/)
  const workspace = await readFile(new URL('../src/views/SpaceWorkbenchView.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(workspace, /useDocTags|TagManagerDialog|DocumentTagsPopover|@\/api\/tag/)
  const detail = await readFile(new URL('../src/components/DocumentDetailPanel.vue', import.meta.url), 'utf8')
  assert.match(detail, /DocumentIndexPanel/)
  const agent = await readFile(new URL('../src/views/AgentView.vue', import.meta.url), 'utf8')
  assert.match(agent, /AgentScopePicker/)
  assert.match(agent, /AnswerFeedback/)
})
