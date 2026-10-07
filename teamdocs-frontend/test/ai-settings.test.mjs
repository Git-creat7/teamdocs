import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope, nextTick } from 'vue'
import { registerHooks } from 'node:module'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from '@vue/compiler-sfc'
import { handlers } from './helpers/ai-settings-api.mjs'

const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier === '@/api/aiSettings' && context.parentURL?.endsWith('/useAiSettings.js')) {
    return { url: new URL('./helpers/ai-settings-api.mjs', import.meta.url).href, shortCircuit: true }
  }
  return next(specifier, context)
} })
const { useAiSettings } = await import('../src/composables/useAiSettings.js')
hooks.deregister()
const empty = { enabled: false, version: 0, baseUrl: '', modelName: '', hasKey: false, encryptionReady: true }
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes }); return { promise, resolve } }
function fixture(t) {
  let probes = 0
  Object.assign(handlers, { model: async () => ({ ...empty }), dependencies: async () => [],
    test: async () => { probes++; return { status: 'HEALTHY' } }, probe: async () => { probes++; return { status: 'HEALTHY' } },
    save: async () => ({ ...empty, hasKey: true, version: 1 }), disable: async () => ({ ...empty, version: 2 }) })
  const scope = effectScope()
  const settings = scope.run(useAiSettings)
  t.after(() => scope.stop())
  return { settings, scope, probes: () => probes }
}

test('loading and refreshing service status do not send probe requests', async t => {
  const { settings, probes } = fixture(t)
  await settings.load()
  await settings.refresh()
  assert.equal(settings.loaded.value, true)
  assert.equal(probes(), 0)
})

test('saving clears plaintext keys and preserves server version without local storage', async t => {
  const { settings } = fixture(t)
  await settings.load()
  settings.form.apiKey = 'test-secret'
  handlers.save = async value => { assert.equal(value.apiKey, 'test-secret'); assert.equal(value.version, 0); return { ...empty, hasKey: true, version: 1 } }
  assert.equal(await settings.save(), true)
  assert.equal(settings.form.apiKey, '')
  assert.equal(settings.model.value.hasKey, true)
  assert.equal(settings.form.version, 1)
})

test('testing a draft does not enable or save it', async t => {
  const { settings } = fixture(t)
  await settings.load()
  settings.form.enabled = true
  handlers.save = () => { throw new Error('must not save') }
  await settings.testModel()
  assert.equal(settings.model.value.enabled, false)
  assert.equal(settings.testResult.value.status, 'HEALTHY')
})

test('changing credentials clears the result of the previous draft test', async t => {
  const { settings } = fixture(t)
  await settings.load()
  await settings.testModel()
  assert.equal(settings.testResult.value.status, 'HEALTHY')
  settings.form.modelName = 'another-model'
  await nextTick()
  assert.equal(settings.testResult.value, null)
})

test('duplicate submissions are blocked and account disposal discards late private results', async t => {
  const { settings, scope } = fixture(t)
  const pending = deferred()
  let signal
  handlers.model = value => { signal = value; return pending.promise }
  const loading = settings.load()
  await settings.testModel()
  scope.stop()
  assert.equal(signal.aborted, true)
  pending.resolve({ ...empty, baseUrl: 'private', hasKey: true })
  await loading
  assert.equal(settings.model.value, null)
  assert.equal(settings.form.apiKey, '')
})

test('UI confirms external calls and uses native Enter submission with password inputs', async () => {
  const source = await readFile(new URL('../src/components/AiSettings.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  assert.doesNotThrow(() => compileScript(descriptor, { id: 'ai-settings', inlineTemplate: true }))
  assert.match(source, /@submit\.prevent="save"/)
  assert.match(source, /native-type="submit"/)
  assert.match(source, /type="password"/)
  assert.match(source, /可能产生少量模型用量/)
  assert.doesNotMatch(source, /v-html|localStorage/)
  const settings = await readFile(new URL('../src/views/SettingsView.vue', import.meta.url), 'utf8')
  assert.match(settings, /AiSettings :key="userInfo\?\.userId"/)
})
