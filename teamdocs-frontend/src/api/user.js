import request from '@/utils/request'

/**
 * 用户登录
 * @param {Object} data - { username, password }
 * @returns {Promise<{ token: string, user: Object } | string>}
 */
export function loginApi(data) {
  return request.post('/user/login', data)
}

/**
 * 用户注册
 * @param {Object} data - { username, password }
 */
export function registerApi(data) {
  return request.post('/user/register', data)
}

/**
 * 获取当前登录用户 Profile 信息
 */
export function getUserInfoApi() {
  return request.get('/user/info')
}

/**
 * 更新个人资料 (昵称、邮箱)
 * @param {Object} data - { nickname, email }
 */
export function updateProfileApi(data) {
  return request.put('/user/profile', data)
}

/**
 * 修改密码
 * @param {Object} data - { oldPassword, newPassword }
 */
export function changePasswordApi(data) {
  return request.put('/user/password', data)
}

/**
 * 上传个人头像
 * 注意：传入 FormData 时不要手动指定 'Content-Type'，让 Axios/浏览器自动附加 boundary
 * @param {File} file 
 */
export function updateAvatarApi(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/user/avatar', formData)
}

/**
 * 退出登录 (通知后端撤销 JWT Token)
 */
export function logoutApi() {
  return request.post('/user/logout')
}
