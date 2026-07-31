import { createRouter, createWebHistory } from 'vue-router'

const LandingView = () => import('@/views/LandingView.vue')
const LoginView = () => import('@/views/LoginView.vue')
const AppShell = () => import('@/layouts/AppShell.vue')
const HomeView = () => import('@/views/HomeView.vue')
const RecentView = () => import('@/views/RecentView.vue')
const ActivityView = () => import('@/views/ActivityView.vue')
const TrashView = () => import('@/views/TrashView.vue')
const TagManageView = () => import('@/views/TagManageView.vue')
const SettingsView = () => import('@/views/SettingsView.vue')
const SpaceWorkbenchView = () => import('@/views/SpaceWorkbenchView.vue')
const DocumentPreviewPage = () => import('@/views/DocumentPreviewPage.vue')

const routes = [
  // 官网落地页：登录墙外的产品门面，与下方 AppShell 同挂 '/'，
  // 同路径先注册者优先，精确访问 '/' 命中这里
  {
    path: '/',
    name: 'Landing',
    component: LandingView,
    meta: { requiresAuth: false }
  },
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
      { path: 'tags', name: 'Tags', component: TagManageView },
      { path: 'trash', name: 'Trash', component: TrashView },
      { path: 'settings', name: 'Settings', component: SettingsView },
      // 兼容旧路径
      { path: 'spaces', redirect: '/home' },
      { path: 'spaces/:spaceId', name: 'SpaceWorkbench', component: SpaceWorkbenchView }
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

// 全局路由前置守卫 (落地页 '/' 对已登录用户也开放，方便随时回门面看)
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('teamdocs_token')
  const requiresAuth = to.matched.some((r) => r.meta.requiresAuth)

  if (requiresAuth && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    next('/home')
  } else {
    next()
  }
})

export default router
