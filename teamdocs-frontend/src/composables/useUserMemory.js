import { onScopeDispose, ref } from 'vue'
import {
  getUserMemoryApi, setUserMemoryEnabledApi, editUserMemoryApi,
  deleteUserMemoryApi, clearUserMemoryApi
} from '@/api/userMemory'

export function useUserMemory() {
  const state = ref({ enabled: false, version: 0, items: [] })
  const loaded = ref(false)
  const busy = ref(false)
  const loading = ref(false)
  const error = ref('')
  let disposed = false
  let requestId = 0
  let controller = null

  async function request(action, mutation = false) {
    if (disposed || busy.value || (mutation && (!loaded.value || loading.value))) return false
    controller?.abort()
    controller = new AbortController()
    const signal = controller.signal
    const id = ++requestId
    error.value = ''
    if (mutation) busy.value = true
    else loading.value = true

    try {
      const result = await action(signal)
      if (disposed || id !== requestId || signal.aborted) return false
      state.value = result
      loaded.value = true
      return true
    } catch (failure) {
      if (!disposed && id === requestId && !signal.aborted) {
        error.value = failure?.message || '记忆暂时不可用，请重试。'
      }
      return false
    } finally {
      if (!disposed && id === requestId) {
        busy.value = false
        loading.value = false
      }
    }
  }

  onScopeDispose(() => {
    disposed = true
    requestId++
    controller?.abort()
    state.value = { enabled: false, version: 0, items: [] }
  })

  return {
    state, loaded, busy, loading, error,
    load: () => request(getUserMemoryApi),
    setEnabled: enabled => request(signal => setUserMemoryEnabledApi(enabled, state.value.version, signal), true),
    edit: (key, content) => request(signal => editUserMemoryApi(key, content, state.value.version, signal), true),
    remove: key => request(signal => deleteUserMemoryApi(key, state.value.version, signal), true),
    clear: () => request(signal => clearUserMemoryApi(state.value.version, signal), true)
  }
}
