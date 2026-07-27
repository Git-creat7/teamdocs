/**
 * 标签配色：按标签名哈希取固定色，同名标签在任何页面颜色一致
 */
const PALETTES = [
  { bg: '#eff6ff', text: '#2563eb', border: '#bfdbfe' },
  { bg: '#f0fdf4', text: '#16a34a', border: '#bbf7d0' },
  { bg: '#fffbeb', text: '#b45309', border: '#fde68a' },
  { bg: '#fdf2f8', text: '#db2777', border: '#fbcfe8' },
  { bg: '#f5f3ff', text: '#7c3aed', border: '#ddd6fe' },
  { bg: '#ecfeff', text: '#0891b2', border: '#a5f3fc' },
  { bg: '#fff1f2', text: '#e11d48', border: '#fecdd3' },
  { bg: '#f0fdfa', text: '#0d9488', border: '#99f6e4' }
]

export function tagPalette(name) {
  const s = String(name || '')
  let h = 0
  for (let i = 0; i < s.length; i++) {
    h = (h * 31 + s.charCodeAt(i)) >>> 0
  }
  return PALETTES[h % PALETTES.length]
}

export function tagStyle(name) {
  const p = tagPalette(name)
  return {
    backgroundColor: p.bg,
    color: p.text,
    borderColor: p.border
  }
}
