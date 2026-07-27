import { defineStore } from 'pinia'
import { ref } from 'vue'

const KEY = 'teamdocs_theme'
const VALID = ['day', 'night', 'coffee', 'sakura', 'cyberpunk']
// Element Plus 自带暗色变量按 html.dark 生效，night/cyberpunk 挂上
const DARK_THEMES = new Set(['night', 'cyberpunk'])

/**
 * 全局主题：登录页选择的主题延续到整个应用。
 * 注意：登出时有意不重置——主题是设备偏好，不是账号数据。
 */
export const useThemeStore = defineStore('theme', () => {
  const saved = localStorage.getItem(KEY)
  const theme = ref(VALID.includes(saved) ? saved : 'day')

  function apply(t) {
    document.documentElement.dataset.theme = t
    document.documentElement.classList.toggle('dark', DARK_THEMES.has(t))
  }

  function setTheme(t) {
    if (!VALID.includes(t)) return
    theme.value = t
    localStorage.setItem(KEY, t)
    apply(t)
  }

  // store 创建即生效 (main.js 启动时实例化，深链直进 /home 也能拿到主题)
  apply(theme.value)

  return { theme, setTheme }
})
