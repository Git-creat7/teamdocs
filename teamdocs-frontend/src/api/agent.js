import request, { handleUnauthorized } from '@/utils/request'
import { readAgentEvents } from '@/utils/agentStream'

const root = (spaceId) => `/spaces/${encodeURIComponent(spaceId)}/agent`
const options = (signal) => ({ silent: true, signal })

export const listAgentSessions = (spaceId, page, signal) => request.get(`${root(spaceId)}/sessions`, { ...options(signal), params: { current: page, size: 20 } })
export const createAgentSession = (spaceId, title, signal) => request.post(`${root(spaceId)}/sessions`, { title }, options(signal))
export const deleteAgentSession = (spaceId, sessionId, signal) => request.delete(`${root(spaceId)}/sessions/${encodeURIComponent(sessionId)}`, options(signal))
export const listAgentMessages = (spaceId, sessionId, page, signal) => request.get(`${root(spaceId)}/sessions/${encodeURIComponent(sessionId)}/messages`, { ...options(signal), params: { current: page, size: 20 } })
export const startAgentRun = (spaceId, sessionId, body, signal) => request.post(`${root(spaceId)}/sessions/${encodeURIComponent(sessionId)}/runs`, body, options(signal))
export const getAgentRun = (spaceId, runId, signal) => request.get(`${root(spaceId)}/runs/${encodeURIComponent(runId)}`, options(signal))
export const cancelAgentRun = (spaceId, runId, signal) => request.post(`${root(spaceId)}/runs/${encodeURIComponent(runId)}/cancel`, {}, options(signal))

export async function observeAgentRun(scope, signal, onEvent) {
  const token = localStorage.getItem('teamdocs_token')
  const response = await fetch(`/api${root(scope.spaceId)}/runs/${encodeURIComponent(scope.runId)}/events`, {
    headers: { Accept: 'text/event-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    cache: 'no-store', signal
  })
  if (response.status === 401) {
    handleUnauthorized()
    const error = new Error('登录已失效，请重新登录')
    error.status = 401
    throw error
  }
  if (!response.ok || !response.headers.get('content-type')?.includes('text/event-stream')) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.msg || '实时连接暂不可用')
  }
  return readAgentEvents(response.body, { scope, signal, onEvent })
}
