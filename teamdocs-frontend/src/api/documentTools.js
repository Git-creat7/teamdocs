import request from '@/utils/request'
const root = space => `/spaces/${encodeURIComponent(space)}`
export const scopeOptions = (space, folderId, signal) => request.get(`${root(space)}/agent/scope-options`, { params: { folderId }, signal, silent: true })
export const getFeedback = (space, run, signal) => request.get(`${root(space)}/agent/runs/${encodeURIComponent(run)}/feedback`, { signal, silent: true })
export const saveFeedback = (space, run, data, signal) => request.put(`${root(space)}/agent/runs/${encodeURIComponent(run)}/feedback`, data, { signal, silent: true })
export const indexStatus = (space, doc, signal) => request.get(`${root(space)}/documents/${encodeURIComponent(doc)}/index`, { signal, silent: true })
export const repairIndex = (space, doc, target, signal) => request.post(`${root(space)}/documents/${encodeURIComponent(doc)}/index/${target}/repair`, {}, { signal, silent: true, timeout: 30000 })
