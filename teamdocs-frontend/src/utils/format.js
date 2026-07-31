/**
 * 通用格式化工具 (新组件共用；旧视图内的同名函数保持原样不动)
 */

export function formatBytes(bytes) {
  if (bytes === 0 || !bytes) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i]
}

/**
 * 相对时间：刚刚 / N分钟前 / N小时前 / 昨天 HH:mm / YYYY-MM-DD
 */
export function formatRelativeTime(dateStr) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return '-'
  const now = new Date()
  const diff = now - d
  if (diff < 60 * 1000) return '刚刚'
  if (diff < 60 * 60 * 1000) return `${Math.floor(diff / 60000)}分钟前`

  const pad = (n) => String(n).padStart(2, '0')
  const sameDay = d.toDateString() === now.toDateString()
  if (sameDay) return `${Math.floor(diff / 3600000)}小时前`

  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (d.toDateString() === yesterday.toDateString()) {
    return `昨天 ${pad(d.getHours())}:${pad(d.getMinutes())}`
  }
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function formatDateTime(dateStr) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return '-'
  const year = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hours = String(d.getHours()).padStart(2, '0')
  const minutes = String(d.getMinutes()).padStart(2, '0')
  return `${year}/${month}/${day} ${hours}:${minutes}`
}

export function getFileExt(filename, fileType) {
  if (filename && filename.includes('.')) {
    return filename.split('.').pop().toUpperCase()
  }
  if (fileType) {
    return fileType.toUpperCase()
  }
  return 'FILE'
}

/**
 * 长文件名中间省略：保住开头和结尾 (含扩展名)
 */
export function middleEllipsis(name, max = 40) {
  const s = String(name || '')
  if (s.length <= max) return s
  const tail = s.slice(-12)
  const head = s.slice(0, Math.max(1, max - 13))
  return `${head}…${tail}`
}

export function getFileTypeColor(ext) {
  const map = {
    PDF: '#ef4444',
    DOCX: '#2563eb',
    DOC: '#2563eb',
    XLSX: '#16a34a',
    XLS: '#16a34a',
    MD: '#0f172a',
    TXT: '#64748b',
    PNG: '#d97706',
    JPG: '#d97706',
    JPEG: '#d97706',
    GIF: '#d97706',
    WEBP: '#d97706',
    ZIP: '#8b5cf6',
    RAR: '#8b5cf6',
    MP4: '#ec4899'
  }
  return map[ext] || '#64748b'
}
