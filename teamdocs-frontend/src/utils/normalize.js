/**
 * 响应规整：把"防御性解包"收敛到 API 层，组件层拿到的永远是规整结构。
 */

/** 列表接口 → 保证返回数组 */
export function asList(res) {
  return Array.isArray(res) ? res : []
}

/** 分页接口 → 保证返回 { records, total, current, size, pages } 且字段可用 */
export function asPage(res) {
  const records = Array.isArray(res?.records) ? res.records : []
  return {
    records,
    total: Number(res?.total) || records.length,
    current: Math.max(1, Number(res?.current) || 1),
    size: Math.max(1, Number(res?.size) || records.length || 1),
    pages: Math.max(1, Number(res?.pages) || 1)
  }
}
