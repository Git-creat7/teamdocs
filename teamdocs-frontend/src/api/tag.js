import request from '@/utils/request'
import { asList, asPage } from '@/utils/normalize'

/**
 * 获取空间下全部标签
 * @param {number|string} spaceId
 * @returns {Promise<Array>} Tag[]
 */
export function listTagsApi(spaceId) {
  return request.get(`/spaces/${spaceId}/tags`).then(asList)
}

/**
 * 创建标签 (OWNER/ADMIN)
 * @param {number|string} spaceId
 * @param {string} name
 */
export function createTagApi(spaceId, name) {
  return request.post(`/spaces/${spaceId}/tags`, { name })
}

/**
 * 删除标签 (OWNER/ADMIN)
 * @param {number|string} spaceId
 * @param {number|string} tagId
 */
export function deleteTagApi(spaceId, tagId) {
  return request.delete(`/spaces/${spaceId}/tags/${tagId}`)
}

/**
 * 重命名标签 (OWNER/ADMIN)
 * 注意：后端接收的是 query 参数 newName，不是 body
 * @param {number|string} spaceId
 * @param {number|string} tagId
 * @param {string} newName
 */
export function renameTagApi(spaceId, tagId, newName) {
  return request.put(`/spaces/${spaceId}/tags/${tagId}`, null, {
    params: { newName }
  })
}

/**
 * 给文档打标签
 * @param {number|string} spaceId
 * @param {number|string} documentId
 * @param {number|string} tagId
 */
export function addTagToDocumentApi(spaceId, documentId, tagId) {
  return request.post(`/spaces/${spaceId}/documents/${documentId}/tags/${tagId}`)
}

/**
 * 从文档移除标签
 * @param {number|string} spaceId
 * @param {number|string} documentId
 * @param {number|string} tagId
 */
export function removeTagFromDocumentApi(spaceId, documentId, tagId) {
  return request.delete(`/spaces/${spaceId}/documents/${documentId}/tags/${tagId}`)
}

/**
 * 批量查多文档标签 (单次上限 200 个 id)
 * 后端另有单文档版 GET /documents/{id}/tags，前端统一走批量，单查暂无调用方
 * @param {number|string} spaceId
 * @param {Array<number>} documentIds
 * @returns {Promise<Object>} { [docId]: Tag[] } — 请求过但无标签的 id 也会带空数组
 */
export function listDocumentTagsBatchApi(spaceId, documentIds) {
  return request.get(`/spaces/${spaceId}/documents/tags`, {
    params: { documentIds: documentIds.join(',') }
  }).then((res) => (res && typeof res === 'object' ? res : {}))
}

/**
 * 按标签筛选文档 (分页)
 * @param {number|string} spaceId
 * @param {number|string} tagId
 * @param {number} current
 * @param {number} size
 * @returns {Promise<Object>} PageResult<Document>
 */
export function listDocumentsByTagApi(spaceId, tagId, current = 1, size = 100) {
  return request.get(`/spaces/${spaceId}/tags/${tagId}/documents`, {
    params: { current, size }
  }).then(asPage)
}
