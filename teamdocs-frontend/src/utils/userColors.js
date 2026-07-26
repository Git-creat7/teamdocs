/**
 * 用户头像底色：按用户名哈希取色，同名恒同色。
 * 与 DocumentDetailPanel 评论区的取色保持同一套色板与哈希。
 */
const AVATAR_COLORS = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899', '#14b8a6']

export function avatarColor(name) {
  const s = String(name || '')
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return AVATAR_COLORS[h % AVATAR_COLORS.length]
}
