import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope } from 'vue'
import { registerHooks } from 'node:module'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from '@vue/compiler-sfc'
import { handlers, requests } from './helpers/user-memory-api.mjs'

const hooks = registerHooks({ resolve(specifier, context, next) {
  if ((specifier === '@/api/userMemory' && context.parentURL?.endsWith('/useUserMemory.js')) ||
      (specifier === '@/utils/request' && context.parentURL?.endsWith('/api/userMemory.js'))) {
    return { url: new URL('./helpers/user-memory-api.mjs', import.meta.url).href, shortCircuit: true }
  }
  return next(specifier, context)
} })
const { useUserMemory } = await import('../src/composables/useUserMemory.js')
const api = await import('../src/api/userMemory.js')
hooks.deregister()

const view = (enabled = false, version = 0, items = []) => ({ enabled, version, items })
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes }); return { promise, resolve } }

function fixture(t) {
  Object.assign(handlers, {
    get: async () => view(), enable: async enabled => view(enabled, 1),
    edit: async () => view(true, 2), remove: async () => view(true, 2), clear: async () => view(true, 2)
  })
  const scope = effectScope()
  const memory = scope.run(useUserMemory)
  t.after(() => scope.stop())
  return { memory, scope }
}

test('memory remains disabled until explicitly enabled and the server confirms', async t => {
  const { memory } = fixture(t)
  assert.equal(await memory.setEnabled(true), false)
  await memory.load()
  const pending = deferred()
  handlers.enable = () => pending.promise
  const saving = memory.setEnabled(true)
  assert.equal(memory.state.value.enabled, false)
  pending.resolve(view(true, 1))
  assert.equal(await saving, true)
  assert.equal(memory.state.value.enabled, true)
})

test('mutations use the latest user version and duplicate submissions are blocked', async t => {
  const { memory } = fixture(t)
  handlers.get = async () => view(true, 7)
  await memory.load()
  const pending = deferred()
  const calls = []
  handlers.edit = (...args) => { calls.push(args); return pending.promise }
  const saving = memory.edit('code_language', 'Java')
  assert.equal(await memory.clear(), false)
  assert.deepEqual(calls[0].slice(0, 3), ['code_language', 'Java', 7])
  pending.resolve(view(true, 8))
  await saving
  handlers.clear = async version => { assert.equal(version, 8); return view(true, 9) }
  assert.equal(await memory.clear(), true)
})

test('failed saves preserve the existing memory and expose a retryable error', async t => {
  const { memory } = fixture(t)
  handlers.get = async () => view(true, 2, [{ key: 'code_language', value: 'Java' }])
  await memory.load()
  handlers.edit = async () => { throw new Error('记忆设置已变化，请刷新后重试') }
  assert.equal(await memory.edit('code_language', 'Python'), false)
  assert.equal(memory.state.value.items[0].value, 'Java')
  assert.match(memory.error.value, /刷新/)
  assert.equal(memory.busy.value, false)
})

test('unmount cancels reads and drops late private responses', async t => {
  const { memory, scope } = fixture(t)
  const pending = deferred()
  let signal
  handlers.get = value => { signal = value; return pending.promise }
  const loading = memory.load()
  scope.stop()
  assert.equal(signal.aborted, true)
  pending.resolve(view(true, 1, [{ value: 'private' }]))
  await loading
  assert.deepEqual(memory.state.value, view())
})

test('a newer refresh wins even when the old response arrives last', async t => {
  const { memory } = fixture(t)
  const pending = deferred()
  handlers.get = () => pending.promise
  const old = memory.load()
  handlers.get = async () => view(true, 2)
  await memory.load()
  pending.resolve(view(false, 1))
  await old
  assert.equal(memory.state.value.version, 2)
})

test('API scope is always the current user, with encoded item keys and cancellation', async () => {
  requests.length = 0
  const signal = new AbortController().signal
  await api.getUserMemoryApi(signal)
  await api.setUserMemoryEnabledApi(true, 1, signal)
  await api.editUserMemoryApi('a/b', 'Java', 2, signal)
  await api.deleteUserMemoryApi('code_language', 3, signal)
  await api.clearUserMemoryApi(4, signal)
  assert.equal(requests[2].args[0], '/user/memory/items/a%2Fb')
  assert.deepEqual(requests[1].args[1], { enabled: true, version: 1 })
  assert.deepEqual(requests[4].args[1].params, { version: 4 })
  for (const request of requests) {
    const options = request.args.at(-1)
    assert.equal(options.signal, signal)
    assert.equal(options.silent, true)
    assert.equal(JSON.stringify(request.args).includes('userId'), false)
  }
})

test('settings use native form submit, escaped text, consent and account-scoped lifecycle', async () => {
  const source = await readFile(new URL('../src/components/UserMemorySettings.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  assert.doesNotThrow(() => compileScript(descriptor, { id: 'user-memory-test', inlineTemplate: true }))
  assert.match(descriptor.template.content, /@submit\.prevent="saveEdit"/)
  assert.match(descriptor.template.content, /native-type="submit"/)
  assert.match(descriptor.template.content, /\{\{ item\.value \}\}/)
  assert.doesNotMatch(descriptor.template.content, /v-html/)
  assert.match(source, /开启后，相关发言与已有记忆会发送/)
  assert.match(source, /role="alert"/)
  assert.match(source, /aria-label="启用用户记忆"/)
  const settings = await readFile(new URL('../src/views/SettingsView.vue', import.meta.url), 'utf8')
  assert.match(settings, /UserMemorySettings :key="userInfo\?\.userId"/)
})
