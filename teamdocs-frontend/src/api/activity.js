import request from '@/utils/request'
import { asList } from '@/utils/normalize'

/**
 * 团队动态：我所在空间的最近操作记录
 * @param {number} limit - 条数上限 (后端封顶 50)
 * @param {number} [spaceId] - 只看某个空间，不传为全部空间
 */
export function getActivitiesApi(limit = 20, spaceId = undefined) {
  return request.get('/activities', { params: { limit, spaceId } }).then(asList)
}
