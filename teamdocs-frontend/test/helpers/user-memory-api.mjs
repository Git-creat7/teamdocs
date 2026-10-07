export const handlers = {}
export const getUserMemoryApi = (...args) => handlers.get(...args)
export const setUserMemoryEnabledApi = (...args) => handlers.enable(...args)
export const editUserMemoryApi = (...args) => handlers.edit(...args)
export const deleteUserMemoryApi = (...args) => handlers.remove(...args)
export const clearUserMemoryApi = (...args) => handlers.clear(...args)
export const requests = []
export default Object.fromEntries(['get', 'put', 'delete'].map(method => [method, (...args) => { requests.push({ method, args }); return Promise.resolve({}) }]))
