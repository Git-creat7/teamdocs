import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

// 防并发 401 重复弹窗与跳转锁
let is401Notifying = false

// 请求拦截器
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('teamdocs_token')
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截器
request.interceptors.response.use(
  (response) => {
    const res = response.data
    // 后端约定的统一响应结构：{ code, msg, data }
    if (res && typeof res.code !== 'undefined') {
      if (res.code === 1) {
        return res.data
      } else {
        const errMsg = res.msg || '业务操作失败'
        ElMessage.error(errMsg)
        return Promise.reject(new Error(errMsg))
      }
    }
    return res
  },
  (error) => {
    if (error.response && error.response.status === 401) {
      localStorage.removeItem('teamdocs_token')
      if (!is401Notifying) {
        is401Notifying = true
        ElMessage.error('登录状态已失效，请重新登录')
        if (router.currentRoute.value.path !== '/login') {
          router.replace('/login')
        }
        setTimeout(() => {
          is401Notifying = false
        }, 1500)
      }
    } else {
      const msg = error.response?.data?.msg || error.message || '网络请求失败，请检查后端服务'
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  }
)

export default request
