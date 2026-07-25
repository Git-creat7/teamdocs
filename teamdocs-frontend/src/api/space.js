import request from '@/utils/request'

/**
 * 获取我的空间列表 (注意路径为单数 /space/list)
 * @returns {Promise<Array>} Space[]
 */
export function listMySpacesApi() {
  return request.get('/space/list')
}

/**
 * 创建空间
 * @param {Object} data - { name, description }
 */
export function createSpaceApi(data) {
  return request.post('/space', data)
}

/**
 * 获取单个空间详情
 * @param {number|string} spaceId 
 */
export function getSpaceDetailApi(spaceId) {
  return request.get(`/space/${spaceId}`)
}
