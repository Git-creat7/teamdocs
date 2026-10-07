import { onScopeDispose, reactive, ref, watch } from 'vue'
import { getMyModelApi, saveMyModelApi, testMyModelApi, disableMyModelApi, getAiDependenciesApi, testAiDependencyApi } from '@/api/aiSettings'

export function useAiSettings() {
  const model = ref(null)
  const form = reactive({ enabled: false, version: 0, baseUrl: '', modelName: '', apiKey: '' })
  const dependencies = ref([])
  const busy = ref(false)
  const loaded = ref(false)
  const error = ref('')
  const testResult = ref(null)
  watch(() => [form.baseUrl, form.modelName, form.apiKey], () => { testResult.value = null })

  let controller = null
  let disposed = false

  async function execute(action) {
    if (busy.value || disposed) return false
    busy.value = true
    error.value = ''
    controller = new AbortController()
    try {
      const value = await action(controller.signal)
      return !disposed && !controller.signal.aborted ? value : false
    } catch (failure) {
      if (!disposed && !controller.signal.aborted) error.value = failure?.message || '操作失败，请重试。'
      return false
    } finally { if (!disposed) busy.value = false }
  }

  function setModel(value) {
    model.value = value
    Object.assign(form, { enabled: value.enabled, version: value.version, baseUrl: value.baseUrl, modelName: value.modelName, apiKey: '' })
    loaded.value = true
  }

  async function load() {
    const value = await execute(signal => Promise.all([getMyModelApi(signal), getAiDependenciesApi(signal)]))
    if (value) { setModel(value[0]); dependencies.value = value[1]; testResult.value = null }
  }

  async function save() {
    const value = await execute(signal => saveMyModelApi({ ...form }, signal))
    if (!value) return false
    setModel(value)
    testResult.value = null
    await refresh()
    return true
  }

  async function disable() {
    const value = await execute(signal => disableMyModelApi(model.value.version, signal))
    if (value) { setModel(value); await refresh() }
    return Boolean(value)
  }

  async function refresh() {
    const value = await execute(getAiDependenciesApi)
    if (value) dependencies.value = value
  }

  async function testModel() {
    const value = await execute(signal => testMyModelApi({ ...form }, signal))
    if (value) testResult.value = value
  }

  async function testDependency(id) {
    const value = await execute(signal => testAiDependencyApi(id, signal))
    if (value) {
      const item = dependencies.value.find(item => item.id === id)
      if (item) item.probe = value
    }
  }

  onScopeDispose(() => {
    disposed = true
    controller?.abort()
    form.apiKey = ''
    model.value = null
    dependencies.value = []
  })

  return { model, form, dependencies, loaded, busy, error, testResult, load, save, disable, refresh, testModel, testDependency }
}
