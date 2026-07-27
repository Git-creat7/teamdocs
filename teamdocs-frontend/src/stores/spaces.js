import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listMySpacesApi } from '@/api/space'

export const useSpacesStore = defineStore('spaces', () => {
  const spaces = ref([])
  const loading = ref(false)

  async function refresh() {
    loading.value = true
    try {
      spaces.value = await listMySpacesApi()
    } catch (err) {
      spaces.value = []
    } finally {
      loading.value = false
    }
  }

  function $reset() {
    spaces.value = []
    loading.value = false
  }

  return { spaces, loading, refresh, $reset }
})
