import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/setup',
    name: 'Setup',
    component: () => import('../views/Setup.vue'),
    meta: { title: '初始化设置', public: true },
  },
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '首页', icon: 'DashboardOutlined' },
      },
      {
        path: 'tenant',
        name: 'TenantConfig',
        component: () => import('../views/TenantConfig.vue'),
        meta: { title: '租户配置', icon: 'UserOutlined' },
      },
      {
        path: 'tenant/:tenantId/users',
        name: 'UserManagement',
        component: () => import('../views/UserManagement.vue'),
        meta: { title: '用户管理', icon: 'TeamOutlined', hidden: true },
      },
      {
        path: 'instance',
        name: 'InstanceList',
        component: () => import('../views/InstanceList.vue'),
        meta: { title: '实例管理', icon: 'CloudServerOutlined' },
      },
      {
        path: 'task',
        name: 'TaskManager',
        component: () => import('../views/TaskManager.vue'),
        meta: { title: '开机任务', icon: 'ThunderboltOutlined' },
      },
      {
        path: 'log',
        name: 'LogViewer',
        component: () => import('../views/LogViewer.vue'),
        meta: { title: '日志查看', icon: 'FileTextOutlined' },
      },
      {
        path: 'settings',
        name: 'SystemSettings',
        component: () => import('../views/SystemSettings.vue'),
        meta: { title: '系统设置', icon: 'SettingOutlined' },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.beforeEach(async (to, _from, next) => {
  const token = localStorage.getItem('token')
  if (!to.meta.public && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
