import request from '@/utils/request'

/**
 * 获取指定文件夹下的文档分页列表 (根目录 folderId 默认为 0)
 * 返回后端 PageResult 结构：{ records, total, current, size, pages }
 * @param {number|string} spaceId 
 * @param {number|string} folderId 
 * @param {number} current 
 * @param {number} size 
 */
export function listDocumentsApi(spaceId, folderId = 0, current = 1, size = 50) {
  return request.get(`/spaces/${spaceId}/documents`, {
    params: { folderId, current, size }
  })
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
  // 上传经后端写 MinIO，单独放宽超时，避免被全局 10s 掐断
  return request.post(`/spaces/${spaceId}/documents/upload`, formData, {
    timeout: 120000
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
