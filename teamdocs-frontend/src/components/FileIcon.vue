<template>
  <img
    v-if="iconSrc"
    class="file-icon"
    :src="iconSrc"
    :style="{ width: size + 'px', height: size + 'px' }"
    alt=""
  />
  <span
    v-else
    class="file-icon-fallback"
    :style="{ backgroundColor: fallbackColor, fontSize: size >= 30 ? '0.7rem' : '0.62rem' }"
  >
    {{ ext }}
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { getFileTypeColor } from '@/utils/format'

// iconfont 文件格式图标集 (assets/fileicons/*.svg)，构建期收集为 URL 映射
const icons = import.meta.glob('@/assets/fileicons/*.svg', { eager: true, import: 'default', query: '?url' })

const ICON_MAP = {}
for (const [path, url] of Object.entries(icons)) {
  const name = path.split('/').pop().replace('.svg', '').toUpperCase()
  ICON_MAP[name] = url
}

// 常见扩展名归一到图标集里的命名
const ALIAS = {
  DOC: 'DOCX',
  XLS: 'XLSX',
  PPT: 'PPTX',
  JPEG: 'JPG',
  YML: 'YAML',
  HTM: 'HTML',
  MARKDOWN: 'MD'
}

const props = defineProps({
  ext: { type: String, required: true },
  size: { type: Number, default: 30 }
})

const iconSrc = computed(() => {
  const key = String(props.ext || '').toUpperCase()
  return ICON_MAP[key] || ICON_MAP[ALIAS[key]] || null
})

const fallbackColor = computed(() => getFileTypeColor(String(props.ext || '').toUpperCase()))
</script>

<style scoped>
.file-icon {
  display: block;
  flex-shrink: 0;
}

.file-icon-fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 30px;
  padding: 2px 5px;
  border-radius: 4px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: #ffffff;
  flex-shrink: 0;
}
</style>
