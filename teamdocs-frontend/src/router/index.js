import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '@/views/LoginView.vue'
import SpaceListView from '@/views/SpaceListView.vue'
import SpaceDetailView from '@/views/SpaceDetailView.vue'

const routes = [
  {
    path: '/',
    redirect: '/spaces'
  },
  {
    path: '/login',
    name: 'Login',
    component: LoginView,
    meta: { requiresAuth: false }
  },
  {
    path: '/spaces',
    name: 'SpaceList',
    component: SpaceListView,
    meta: { requiresAuth: true }
  },
  {
    path: '/spaces/:spaceId',
    name: 'SpaceDetail',
    component: SpaceDetailView,
    meta: { requiresAuth: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局路由前置守卫
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('teamdocs_token')

  if (to.meta.requiresAuth && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    next('/spaces')
  } else {
    next()
  }
})

export default router
