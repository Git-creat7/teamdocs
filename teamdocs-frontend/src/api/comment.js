import request from '@/utils/request'
import { asPage } from '@/utils/normalize'

/**
 * 获取文档评论分页列表 (按时间正序)
 * @param {number|string} spaceId
 * @param {number|string} documentId
 * @param {number} current
 * @param {number} size
 * @returns {Promise<Object>} 规整后的 PageResult<CommentVO>
 */
export function listCommentsApi(spaceId, documentId, current = 1, size = 100) {
  return request.get(`/spaces/${spaceId}/documents/${documentId}/comments`, {
    params: { current, size }
  }).then(asPage)
}

/**
 * 发表评论 / 回复评论
 * @param {number|string} spaceId
 * @param {number|string} documentId
 * @param {Object} data - { content, replyToId? }
 */
export function addCommentApi(spaceId, documentId, data) {
  return request.post(`/spaces/${spaceId}/documents/${documentId}/comments`, data)
}

/**
 * 删除评论 (软删除，列表中保留占位)
 * @param {number|string} spaceId
 * @param {number|string} documentId
 * @param {number|string} commentId
 */
export function deleteCommentApi(spaceId, documentId, commentId) {
  return request.delete(`/spaces/${spaceId}/documents/${documentId}/comments/${commentId}`)
}
