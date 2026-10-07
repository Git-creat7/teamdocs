import request from '@/utils/request'

const options = signal => ({ signal, silent: true })

export function getUserMemoryApi(signal) {
  return request.get('/user/memory', options(signal))
}

export function setUserMemoryEnabledApi(enabled, version, signal) {
  return request.put('/user/memory/settings', { enabled, version }, options(signal))
}

export function editUserMemoryApi(key, content, version, signal) {
  return request.put(`/user/memory/items/${encodeURIComponent(key)}`, { content, version }, options(signal))
}

export function deleteUserMemoryApi(key, version, signal) {
  return request.delete(`/user/memory/items/${encodeURIComponent(key)}`, { ...options(signal), params: { version } })
}

export function clearUserMemoryApi(version, signal) {
  return request.delete('/user/memory', { ...options(signal), params: { version } })
}
