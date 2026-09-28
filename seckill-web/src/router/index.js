import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  { path: '/', name: 'channel', component: () => import('../views/Channel.vue') },
  { path: '/activity/:id', name: 'activityDetail', component: () => import('../views/ActivityDetail.vue') },
  { path: '/seckill/result', name: 'seckillResult', component: () => import('../views/SeckillResult.vue') },
  { path: '/orders', name: 'orders', component: () => import('../views/Orders.vue'), meta: { requiresAuth: true } },
  { path: '/wallet', name: 'wallet', component: () => import('../views/Wallet.vue'), meta: { requiresAuth: true } },
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  { path: '/admin/activity', name: 'adminActivity', component: () => import('../views/AdminActivity.vue') },
  { path: '/admin/log', name: 'adminLog', component: () => import('../views/AdminLog.vue') }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  if (to.meta.requiresAuth) {
    const auth = useAuthStore()
    if (!auth.token) return { name: 'login', query: { redirect: to.fullPath } }
  }
})

export default router