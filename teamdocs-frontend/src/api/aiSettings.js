import request from '@/utils/request'

const options = signal => ({ signal, silent: true, timeout: 35000 })

export const getMyModelApi = signal => request.get('/user/ai/model', options(signal))
export const saveMyModelApi = (data, signal) => request.put('/user/ai/model', data, options(signal))
export const testMyModelApi = (data, signal) => request.post('/user/ai/model/test', data, options(signal))
export const disableMyModelApi = (version, signal) => request.post('/user/ai/model/disable', { version }, options(signal))
export const getAiDependenciesApi = signal => request.get('/user/ai/dependencies', options(signal))
export const testAiDependencyApi = (id, signal) => request.post(`/user/ai/dependencies/${encodeURIComponent(id)}/test`, {}, options(signal))
