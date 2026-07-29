import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import router from './router'
import { useThemeStore } from '@/stores/theme'
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

// 启动即应用持久化主题 (login 选择的主题全站生效)
useThemeStore()

app.mount('#app')
