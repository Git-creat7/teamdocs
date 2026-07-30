/**
 * 团队动态文案：操作名 → 渲染配置
 *   short: 有资源名时的短动词  full: 拿不到名字时的完整句 (无宾语也通顺)
 *   style: 资源名展示形态 doc=可点击文档链接 / strong=加粗名词 / quote=评论内容摘要
 *   suffix: 资源名后缀 (如 "邀请了 demo 加入")
 * 名字来源：documentName (JOIN 到的最新文档名) 优先，其次 resource_name 入参快照
 */
const ACTIVITY_META = {
  '上传文档': { short: '上传了', full: '上传了文档', style: 'doc' },
  '重命名文档': { short: '重命名了', full: '重命名了文档', style: 'doc' },
  '删除文档': { short: '删除了', full: '删除了文档', style: 'doc' },
  '移动文档': { short: '移动了', full: '移动了文档', style: 'doc' },
  '恢复文档': { short: '恢复了', full: '恢复了文档', style: 'doc' },
  '彻底删除文档': { short: '彻底删除了', full: '彻底删除了文档', style: 'strong' },
  '为文件添加标签': { short: '给', full: '为文档添加了标签', style: 'doc', suffix: '打了标签' },
  '从文件移除标签': { short: '移除了', full: '移除了文档标签', style: 'doc', suffix: '的标签' },
  '添加评论': { short: '在“', full: '发表了评论', style: 'doc', suffix: '”发表了评论' },
  '删除评论': { short: '删除了评论', full: '删除了评论' },
  '创建文件夹': { short: '创建了文件夹', full: '创建了文件夹', style: 'strong' },
  '重命名文件夹': { short: '重命名了文件夹', full: '重命名了文件夹', style: 'strong' },
  '删除文件夹': { short: '删除了文件夹', full: '删除了文件夹' },
  '移动文件夹': { short: '移动了文件夹', full: '移动了文件夹' },
  '创建空间': { short: '创建了空间', full: '创建了空间', style: 'strong' },
  '删除空间': { short: '删除了空间', full: '删除了空间' },
  '更新空间': { short: '更新了空间信息', full: '更新了空间信息' },
  '添加空间成员': { short: '邀请了', full: '邀请了新成员', style: 'strong', suffix: '加入' },
  '移除空间成员': { short: '移除了成员', full: '移除了成员' },
  '修改空间成员角色': { short: '调整了成员角色', full: '调整了成员角色' },
  '创建标签': { short: '创建了标签', full: '创建了标签', style: 'strong' },
  '删除标签': { short: '删除了标签', full: '删除了标签' }
}

const FALLBACK_META = {}
const NON_OPENABLE_DOCUMENT_OPERATIONS = new Set(['删除文档', '彻底删除文档'])

export function activityMeta(act) {
  return ACTIVITY_META[act.operationName] || FALLBACK_META
}

export function activityName(act) {
  const meta = activityMeta(act)
  if (!meta.style) return ''
  if (act.operationName === '添加评论') {
    return act.resourceType === 'DOCUMENT' ? (act.resourceName || act.documentName || '') : ''
  }
  return act.documentName || act.resourceName || ''
}

export function activityVerb(act) {
  const meta = activityMeta(act)
  if (!meta.short) return act.operationName
  return activityName(act) ? meta.short : meta.full
}

export function canOpenActivityDocument(act) {
  return activityMeta(act).style === 'doc'
    && act?.resourceType === 'DOCUMENT'
    && Number(act?.resourceId) > 0
    && !NON_OPENABLE_DOCUMENT_OPERATIONS.has(act.operationName)
}

export function truncateText(s, max = 40) {
  const str = String(s || '')
  return str.length > max ? str.slice(0, max) + '…' : str
}
