import request from '@/utils/request'

/**
 * 获取指定父目录下的子文件夹列表 (根目录 parentId 默认为 0)
 * @param {number|string} spaceId 
 * @param {number|string} parentId 
 */
export function listSubFoldersApi(spaceId, parentId = 0) {
  return request.get(`/spaces/${spaceId}/folders`, {
    params: { parentId }
  })
}

/**
 * 创建文件夹
 * @param {number|string} spaceId 
 * @param {Object} data - { name, parentId }
 */
export function createFolderApi(spaceId, data) {
  return request.post(`/spaces/${spaceId}/folders`, data)
}

/**
 * 重命名文件夹 (后端接收 body 为 { newName })
 * @param {number|string} spaceId 
 * @param {number|string} folderId 
 * @param {string} newName 
 */
export function renameFolderApi(spaceId, folderId, newName) {
  return request.put(`/spaces/${spaceId}/folders/${folderId}`, { newName })
}

/**
 * 删除文件夹
 * @param {number|string} spaceId 
 * @param {number|string} folderId 
 */
export function deleteFolderApi(spaceId, folderId) {
  return request.delete(`/spaces/${spaceId}/folders/${folderId}`)
}
