import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { title: '登录', public: true },
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
        path: 'security',
        name: 'SecurityRules',
        component: () => import('../views/SecurityRules.vue'),
        meta: { title: '安全列表', icon: 'SafetyOutlined' },
      },
      {
        path: 'network',
        name: 'NetworkManager',
        component: () => import('../views/NetworkManager.vue'),
        meta: { title: '网络管理', icon: 'GlobalOutlined' },
      },
      {
        path: 'volume',
        name: 'VolumeManager',
        component: () => import('../views/VolumeManager.vue'),
        meta: { title: '引导卷', icon: 'DatabaseOutlined' },
      },
      {
        path: 'traffic',
        name: 'TrafficStats',
        component: () => import('../views/TrafficStats.vue'),
        meta: { title: '流量统计', icon: 'LineChartOutlined' },
      },
      {
        path: 'cloudflare',
        name: 'CloudflareDns',
        component: () => import('../views/CloudflareDns.vue'),
        meta: { title: 'Cloudflare', icon: 'CloudOutlined' },
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
      {
        path: 'backup',
        name: 'BackupRestore',
        component: () => import('../views/BackupRestore.vue'),
        meta: { title: '备份恢复', icon: 'SaveOutlined' },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('token')
  if (!to.meta.public && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
