import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from '@vue/compiler-sfc'

const forms = [
  { file: 'layouts/AppShell.vue', handler: 'handleCreateSpace', ref: 'createFormRef', model: 'createForm', busy: 'creatingSpace', visible: 'createSpaceVisible' },
  { file: 'views/HomeView.vue', handler: 'handleSubmit', ref: 'editFormRef', model: 'editForm', busy: 'submitting', visible: 'editVisible' },
  { file: 'views/SpaceWorkbenchView.vue', handler: 'handleCreateFolder', ref: 'createFolderFormRef', model: 'createFolderForm', busy: 'submittingFolder', visible: 'createFolderDialogVisible' }
]

function descendants(node) {
  return [node, ...(node.children || []).flatMap(descendants)]
}

async function loadForm(spec) {
  const source = await readFile(new URL(`../src/${spec.file}`, import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const compiled = compileScript(descriptor, { id: 'creation-form-test' })
  const declaration = compiled.scriptSetupAst.find(node =>
    node.type === 'FunctionDeclaration' && node.id.name === spec.handler
  )
  const handler = descriptor.scriptSetup.content.slice(declaration.start, declaration.end)
  const form = descendants(descriptor.template.ast).find(node =>
    node.tag === 'el-form' && node.props.some(prop => prop.name === 'ref' && prop.value?.content === spec.ref)
  )

  return { handler, form }
}

function setup(spec, handler, { validate = async () => true, request = async () => {} } = {}) {
  const calls = []
  const notifications = []
  const model = { name: ' 测试名称 ', description: ' 测试描述 ' }
  const busy = { value: false }
  const visible = { value: true }

  const api = method => async (...args) => {
    calls.push({ method, args })
    await request()
  }

  const context = {
    [spec.ref]: { value: { validate } },
    [spec.model]: model,
    [spec.busy]: busy,
    [spec.visible]: visible,
    editingSpace: { value: null },
    spaceId: { value: 20 },
    currentFolderId: { value: 7 },
    createSpaceApi: api('createSpace'),
    updateSpaceApi: api('updateSpace'),
    createFolderApi: api('createFolder'),
    ElMessage: { success: message => notifications.push(message) },
    refreshSpaces: async () => {},
    loadCurrentFolderContent: async () => {},
    refreshTreeFolder: async () => {}
  }
  const submit = new Function(...Object.keys(context), `return (${handler})`)(...Object.values(context))

  return { submit, calls, notifications, model, busy, visible, context }
}

for (const spec of forms) {
  const { handler, form } = await loadForm(spec)

  test(`${spec.file}: native form submission prevents navigation and uses the button handler`, () => {
    const submit = form.props.find(prop => prop.name === 'on' && prop.arg?.content === 'submit')

    assert.ok(submit, 'Enter must not fall through to browser navigation')
    assert.equal(submit.exp.content, spec.handler)
    assert.ok(submit.modifiers.some(modifier => modifier.content === 'prevent'))

    for (const node of descendants(form)) {
      assert.ok(!(node.props || []).some(prop =>
        prop.name === 'on' && ['keydown', 'keyup'].includes(prop.arg?.content)
      ), 'use native submission instead of intercepting IME or textarea Enter')
    }
  })

  test(`${spec.file}: successful submission sends the name once and closes the dialog`, async () => {
    const state = setup(spec, handler)

    await state.submit()

    assert.equal(state.calls.length, 1)
    assert.equal(state.calls[0].args.at(-1).name, '测试名称')
    assert.equal(state.visible.value, false)
    assert.equal(state.busy.value, false)
    assert.equal(state.notifications.length, 1)
  })

  test(`${spec.file}: invalid input stays in the dialog and makes no request`, async () => {
    const state = setup(spec, handler, { validate: async () => { throw new Error('invalid name') } })

    await state.submit()

    assert.equal(state.calls.length, 0)
    assert.equal(state.model.name, ' 测试名称 ')
    assert.equal(state.visible.value, true)
    assert.equal(state.busy.value, false)
  })

  test(`${spec.file}: failed request retains input and allows retry`, async () => {
    let fail = true
    const state = setup(spec, handler, { request: async () => { if (fail) throw new Error('unavailable') } })

    await state.submit()

    assert.equal(state.model.name, ' 测试名称 ')
    assert.equal(state.visible.value, true)
    assert.equal(state.busy.value, false)
    assert.equal(state.notifications.length, 0)
    fail = false
    await state.submit()

    assert.equal(state.calls.length, 2)
    assert.equal(state.visible.value, false)
  })

  for (const pending of ['validation', 'request']) {
    test(`${spec.file}: repeated submission during ${pending} makes only one request`, async () => {
      const gate = Promise.withResolvers()
      const options = pending === 'validation' ? { validate: () => gate.promise } : { request: () => gate.promise }
      const state = setup(spec, handler, options)
      const first = state.submit()

      await Promise.resolve()

      const busyBeforeSecondSubmit = state.busy.value
      const second = state.submit()

      gate.resolve(true)
      await Promise.all([first, second])

      assert.equal(busyBeforeSecondSubmit, true)
      assert.equal(state.calls.length, 1)
      assert.equal(state.busy.value, false)
    })
  }
}

test('the Home space form still updates an existing space instead of creating another', async () => {
  const spec = forms[1]
  const { handler } = await loadForm(spec)
  const state = setup(spec, handler)

  state.context.editingSpace.value = { id: 42 }
  await state.submit()

  assert.deepEqual(state.calls, [{ method: 'updateSpace', args: [42, { name: '测试名称', description: '测试描述' }] }])
})
