import { createRouter, createWebHistory } from 'vue-router'

const LoginView = () => import('@/views/LoginView.vue')

const AppShell = () => import('@/layouts/AppShell.vue')

const HomeView = () => import('@/views/HomeView.vue')

const RecentView = () => import('@/views/RecentView.vue')

const ActivityView = () => import('@/views/ActivityView.vue')

const TrashView = () => import('@/views/TrashView.vue')


const SettingsView = () => import('@/views/SettingsView.vue')

const AgentView = () => import('@/views/AgentView.vue')

const SpaceWorkbenchView = () => import('@/views/SpaceWorkbenchView.vue')

const DocumentPreviewPage = () => import('@/views/DocumentPreviewPage.vue')

const routes = [
  // 根路径直接进入登录页，登录后的工作台仍使用 /home。
  { path: '/', redirect: '/login' },
  {
    path: '/login',
    name: 'Login',
    component: LoginView,
    meta: { requiresAuth: false }
  },
  {
    path: '/',
    component: AppShell,
    meta: { requiresAuth: true },
    children: [
      { path: 'home', name: 'Home', component: HomeView },
      { path: 'recent', name: 'Recent', component: RecentView },
      { path: 'activities', name: 'Activities', component: ActivityView },
      { path: 'trash', name: 'Trash', component: TrashView },
      { path: 'settings', name: 'Settings', component: SettingsView },
      // 兼容旧路径
      { path: 'spaces', redirect: '/home' },
      { path: 'spaces/:spaceId', name: 'SpaceWorkbench', component: SpaceWorkbenchView },
      { path: 'spaces/:spaceId/agent/:sessionId?', name: 'SpaceAgent', component: AgentView }
    ]
  },
  {
    path: '/preview/:spaceId/:documentId',
    name: 'DocumentPreview',
    component: DocumentPreviewPage,
    meta: { requiresAuth: true }
  },
  { path: '/:pathMatch(.*)*', redirect: '/home' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局路由前置守卫：未登录时不可访问工作台。
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('teamdocs_token')
  const requiresAuth = to.matched.some((r) => r.meta.requiresAuth)

  if (requiresAuth && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
