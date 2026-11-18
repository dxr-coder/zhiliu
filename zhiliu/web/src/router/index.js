/**
 * 路由
 * 全部懒加载；/admin/* 需要 admin 角色（ensureUserLoaded 拉取 /auth/me 后校验）
 * meta.noTransition 排除 /chat 的页面过渡（工作区不滑动）
 * meta.title 驱动 afterEach 更新 document.title（页签标题随路由切换）
 * 数据流：守卫读 localStorage token + useAuth 缓存的用户信息
 */
import { createRouter, createWebHistory } from 'vue-router'
import { ensureUserLoaded } from '../composables/useAuth'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue'), meta: { public: true, title: '登录' } },
  { path: '/', redirect: '/chat' },
  {
    path: '/chat',
    component: () => import('../views/Chat.vue'),
    meta: { noTransition: true, title: '对话' },
  },
  { path: '/office', component: () => import('../views/Office.vue'), meta: { title: '工作台' } },
  { path: '/tickets', redirect: '/office' },
  { path: '/tickets/:id', redirect: '/office' },
  { path: '/transfer', redirect: '/office' },
  { path: '/assets', redirect: '/office' },
  {
    path: '/admin',
    component: () => import('../components/layout/AdminLayout.vue'),
    meta: { requiresAdmin: true },
    children: [
      { path: '', component: () => import('../views/admin/Dashboard.vue'), meta: { title: '概览' } },
      { path: 'users', component: () => import('../views/admin/Users.vue'), meta: { title: '用户管理' } },
      { path: 'kb', component: () => import('../views/admin/KnowledgeBase.vue'), meta: { title: '知识库' } },
      { path: 'audit', component: () => import('../views/admin/AuditLogs.vue'), meta: { title: '审计日志' } },
      { path: 'abtest', component: () => import('../views/admin/ABTest.vue'), meta: { title: 'A/B 测试' } },
      { path: 'eval', component: () => import('../views/admin/Eval.vue'), meta: { title: 'RAG 评测' } },
      { path: 'config', component: () => import('../views/admin/Config.vue'), meta: { title: '配置中心' } },
    ],
  },
  { path: '/:pathMatch(.*)*', component: () => import('../views/NotFound.vue'), meta: { public: true, title: '页面未找到' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

// 页签标题：最深一层 matched 的 meta.title 优先，兜底知流
router.afterEach((to) => {
  const title = [...to.matched].reverse().find((r) => r.meta.title)?.meta.title
  document.title = title ? `${title} · 知流` : '知流 · 企业智能服务台'
})

router.beforeEach(async (to) => {
  const token = localStorage.getItem('token')

  // 公开页面
  if (to.meta.public) {
    // 已登录访问 /login → 回 chat
    if (token && to.path === '/login') return '/chat'
    return true
  }

  // 未登录 → 登录页
  if (!token) return '/login'

  // admin 路由：Python role_name=admin 或 Java java_user.roles 含 admin
  if (to.matched.some((r) => r.meta.requiresAdmin)) {
    const user = await ensureUserLoaded()
    const pyAdmin = user && user.role_name === 'admin'
    let javaAdmin = false
    try {
      const ju = JSON.parse(localStorage.getItem('java_user') || 'null')
      javaAdmin = !!(ju && Array.isArray(ju.roles) && ju.roles.includes('admin'))
    } catch { /* ignore */ }
    if (!pyAdmin && !javaAdmin) {
      // 有 Python 会话但不是 admin；或无会话
      if (!user && !localStorage.getItem('java_token')) return '/login'
      if (!pyAdmin && !javaAdmin) return '/chat'
    }
  }

  return true
})

export default router
