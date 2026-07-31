import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import router from './router'
import FileViewer from '@file-viewer/vue3'
import '@file-viewer/vue3/dist/file-viewer3.css'
import '@/assets/styles/layout.css'

const app = createApp(App)

app.use(createPinia())
app.use(ElementPlus, {
  locale: zhCn
})
app.use(router)
app.use(FileViewer)

// 主题功能已移除，启动时清理旧版本留下的主题状态并固定浅色模式。
localStorage.removeItem('teamdocs_theme')
document.documentElement.removeAttribute('data-theme')
document.documentElement.classList.remove('dark')

app.mount('#app')
