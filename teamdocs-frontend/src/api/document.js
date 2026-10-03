import request from '@/utils/request'
import { asPage } from '@/utils/normalize'

/**
 * 获取指定文件夹下的文档分页列表 (根目录 folderId 默认为 0)
 * 返回规整后的 { records, total, current, size, pages }
 * @param {number|string} spaceId
 * @param {number|string} folderId
 * @param {number} current
 * @param {number} size
 */
export function listDocumentsApi(spaceId, folderId = 0, current = 1, size = 50) {
  return request.get(`/spaces/${spaceId}/documents`, {
    params: { folderId, current, size }
  }).then(asPage)
}

/**
 * 上传文档
 * 注意：使用 FormData 时绝对不要手动设置 'Content-Type: multipart/form-data'，交给浏览器自动带 boundary
 * @param {number|string} spaceId 
 * @param {number|string} folderId 
 * @param {File} file 
 */
export function uploadDocumentApi(spaceId, folderId, file) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('folderId', folderId)
  // 100MB 文件需先传后端再写入 MinIO，单独放宽到 5 分钟。
  return request.post(`/spaces/${spaceId}/documents/upload`, formData, {
    timeout: 300000
  })
}

/**
 * 获取文档下载/预览预签名 URL 字符串
 * @param {number|string} spaceId
 * @param {number|string} documentId
 */
export function downloadDocumentApi(spaceId, documentId) {
  return request.get(`/spaces/${spaceId}/documents/${documentId}/download`)
}

/**
 * 获取文档在线预览所需的元数据与预签名 URL
 * 独立于下载接口：预览走 /preview，绝不复用 /download
 * @param {number|string} spaceId
 * @param {number|string} documentId
 * @returns {Promise<Object>} { documentId, name, fileType, fileSize, url }
 */
export function previewDocumentApi(spaceId, documentId) {
  return request.get(`/spaces/${spaceId}/documents/${documentId}/preview`)
}

/** 经后端鉴权读取分块对应原图，不使用持久化的预签名 URL。 */
export async function previewChunkImageApi(spaceId, documentId, chunkId, parseVersion, signal) {
  let blob
  try {
    blob = await request.get(`/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/chunks/${encodeURIComponent(chunkId)}/image`, {
      params: { parseVersion }, responseType: 'blob', signal, silent: true
    })
  } catch (cause) {
    if (cause.name === 'AbortError' || cause.code === 'ERR_CANCELED') throw cause
    const detail = await imageFailure(cause.response?.data)
    if (detail.msg) cause.message = detail.msg
    const status = cause.response?.status
    cause.code = detail.errorCode || (status === 401 ? 'ACCESS_REVOKED'
      : [403, 404, 409, 410].includes(status) ? 'SOURCE_CHANGED' : 'IMAGE_UNAVAILABLE')
    throw cause
  }
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(blob?.type) || !blob.size || blob.size > 4 * 1024 * 1024) {
    const detail = await imageFailure(blob)
    const error = new Error(detail.msg || '原图加载失败，请重试')
    error.code = detail.errorCode || 'IMAGE_UNAVAILABLE'
    throw error
  }
  return blob
}

/** 只读取有界 JSON 错误，不把繁忙或网络异常误判为来源失效。 */
async function imageFailure(body) {
  if (body?.type?.includes('json') && body.size <= 65536) {
    try { return JSON.parse(await body.text()) } catch { return {} }
  }
  return body && typeof body === 'object' && typeof body.msg === 'string' ? body : {}
}

/**
 * 文档详情 (含标签列表；后端同时记入最近浏览)
 * @param {number|string} spaceId
 * @param {number|string} documentId
 * @returns {Promise<Object>} DocumentDetailVO - { id, spaceId, folderId, name, fileType, fileSize, description, uploadBy, createdAt, updatedAt, tags: Tag[] }
 */
export function getDocumentDetailApi(spaceId, documentId) {
  return request.get(`/spaces/${spaceId}/documents/${documentId}`)
}

export function reparseDocumentApi(spaceId, documentId) {
  return request.post(`/spaces/${spaceId}/documents/${documentId}/reparse`)
}

/**
 * 重命名文档 (后端接收 body 为 { newName })
 * @param {number|string} spaceId 
 * @param {number|string} documentId 
 * @param {string} newName 
 */
export function renameDocumentApi(spaceId, documentId, newName) {
  return request.put(`/spaces/${spaceId}/documents/${documentId}/rename`, { newName })
}

/**
 * 删除文档 (软删除)
 * @param {number|string} spaceId
 * @param {number|string} documentId
 */
export function deleteDocumentApi(spaceId, documentId) {
  return request.delete(`/spaces/${spaceId}/documents/${documentId}`)
}

/**
 * 移动文档到目标文件夹 (0 表示根目录)
 * @param {number|string} spaceId
 * @param {number|string} documentId
 * @param {number} targetFolderId
 */
export function moveDocumentApi(spaceId, documentId, targetFolderId) {
  return request.put(`/spaces/${spaceId}/documents/${documentId}/move`, { targetFolderId })
}

/**
 * 全文搜索文档 (MySQL FULLTEXT，匹配文档名/描述/标签名)
 * @param {number|string} spaceId
 * @param {string} keyword
 * @param {number} current
 * @param {number} size
 * @returns {Promise<Object>} PageResult<Document>
 */
export function searchDocumentsApi(spaceId, keyword, current = 1, size = 100) {
  return request.get(`/spaces/${spaceId}/documents/search`, {
    params: { keyword, current, size }
  }).then(asPage)
}

/**
 * 回收站分页列表
 * @param {number|string} spaceId
 * @param {number} current
 * @param {number} size
 * @returns {Promise<Object>} PageResult<Document>
 */
export function listTrashedDocumentsApi(spaceId, current = 1, size = 50) {
  return request.get(`/spaces/${spaceId}/documents/trash`, {
    params: { current, size }
  }).then(asPage)
}

/**
 * 从回收站恢复文档
 * 不传 targetFolderId 时恢复到原目录，原目录已删则回退到根目录
 * @param {number|string} spaceId
 * @param {number|string} documentId
 * @param {number|null} targetFolderId
 */
export function restoreDocumentApi(spaceId, documentId, targetFolderId = null) {
  const body = targetFolderId === null ? {} : { targetFolderId }
  return request.put(`/spaces/${spaceId}/documents/${documentId}/restore`, body)
}

/**
 * 彻底删除回收站文档 (同时删除 MinIO 对象，不可恢复)
 * @param {number|string} spaceId
 * @param {number|string} documentId
 */
export function purgeDocumentApi(spaceId, documentId) {
  return request.delete(`/spaces/${spaceId}/documents/${documentId}/purge`)
}
