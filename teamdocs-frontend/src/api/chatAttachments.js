import request from '@/utils/request'
const root = space => `/spaces/${encodeURIComponent(space)}/agent`
export function uploadChatAttachment(space, file, signal) {
  const form = new FormData()
  form.append('file', file)
  return request.post(`${root(space)}/attachments`, form, { signal, timeout: 60000, silent: true })
}
export const deleteChatAttachment = (space, id, signal) => request.delete(`${root(space)}/attachments/${encodeURIComponent(id)}`, { signal, silent: true })
export const listChatAttachments = (space, run, signal) => request.get(`${root(space)}/runs/${encodeURIComponent(run)}/attachments`, { signal, silent: true })
export const downloadChatAttachment = (space, id, signal) => request.get(`${root(space)}/attachments/${encodeURIComponent(id)}`, { responseType: 'blob', signal, silent: true })
