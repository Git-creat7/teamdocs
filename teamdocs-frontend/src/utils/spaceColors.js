/**
 * 空间身份色：按 space.id 哈希取固定色，与标签色 (tagColors) 同一哲学——
 * 颜色跟随实体本身而不是列表下标，增删空间不会让其他空间集体变色。
 */
const DOT_COLORS = ['#3b82f6', '#8b5cf6', '#22c55e', '#f59e0b', '#06b6d4', '#f43f5e']

const ICON_PALETTES = [
  { bg: '#eff6ff', text: '#3b82f6' },
  { bg: '#f5f3ff', text: '#8b5cf6' },
  { bg: '#f0fdf4', text: '#22c55e' },
  { bg: '#fefce8', text: '#eab308' },
  { bg: '#ecfeff', text: '#06b6d4' },
  { bg: '#fff1f2', text: '#f43f5e' }
]

function hashIndex(id, mod) {
  const s = String(id ?? '')
  let h = 0
  for (let i = 0; i < s.length; i++) {
    h = (h * 31 + s.charCodeAt(i)) >>> 0
  }
  return h % mod
}

/** 侧栏小圆点背景色 */
export function spaceDotColor(spaceId) {
  return DOT_COLORS[hashIndex(spaceId, DOT_COLORS.length)]
}

/** 卡片图标底/前景色 */
export function spaceIconPalette(spaceId) {
  return ICON_PALETTES[hashIndex(spaceId, ICON_PALETTES.length)]
}
