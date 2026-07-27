import request from '@/utils/request'
import { asList } from '@/utils/normalize'

/**
 * 获取我的空间列表 (注意路径为单数 /space/list)
 * @returns {Promise<Array>} Space[]
 */
export function listMySpacesApi() {
  return request.get('/space/list').then(asList)
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

/**
 * 更新空间信息 (OWNER/ADMIN)
 * @param {number|string} spaceId
 * @param {Object} data - { name, description }
 */
export function updateSpaceApi(spaceId, data) {
  return request.put(`/space/${spaceId}`, data)
}

/**
 * 删除空间 (软删除，仅 OWNER)
 * @param {number|string} spaceId
 */
export function deleteSpaceApi(spaceId) {
  return request.delete(`/space/${spaceId}`)
}

/**
 * 获取空间成员列表
 * @param {number|string} spaceId
 * @returns {Promise<Array>} SpaceMemberVO[] - { id, spaceId, userId, username, avatar, role, joinedAt }
 */
export function listMembersApi(spaceId) {
  return request.get(`/space/${spaceId}/members`).then(asList)
}

/**
 * 添加空间成员 (OWNER/ADMIN，不能直接添加 OWNER)
 * @param {number|string} spaceId
 * @param {Object} data - { username, role: 'ADMIN' | 'MEMBER' }
 */
export function addMemberApi(spaceId, data) {
  return request.post(`/space/${spaceId}/members`, data)
}

/**
 * 移除空间成员 (OWNER/ADMIN；ADMIN 不能移除 ADMIN，OWNER 不可被移除)
 * @param {number|string} spaceId
 * @param {number|string} userId
 */
export function removeMemberApi(spaceId, userId) {
  return request.delete(`/space/${spaceId}/members/${userId}`)
}

/**
 * 修改成员角色 (仅 OWNER；不能设为 OWNER，不能改自己)
 * @param {number|string} spaceId
 * @param {number|string} userId
 * @param {string} role - 'ADMIN' | 'MEMBER'
 */
export function updateMemberRoleApi(spaceId, userId, role) {
  return request.put(`/space/${spaceId}/members/${userId}`, { role })
}
