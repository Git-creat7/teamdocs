import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getUserInfoApi } from '@/api/user'

export const useUserStore = defineStore('user', () => {
  const userInfo = ref(null)
  const loading = ref(false)

  async function refresh() {
    if (loading.value) return
    loading.value = true
    try {
      userInfo.value = await getUserInfoApi()
    } catch (err) {
      // 401 由拦截器统一处理
    } finally {
      loading.value = false
    }
  }

  function setUser(data) {
    userInfo.value = data
  }

  function $reset() {
    userInfo.value = null
    loading.value = false
  }

  return { userInfo, loading, refresh, setUser, $reset }
})
