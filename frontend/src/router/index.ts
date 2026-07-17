import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { defineAppAsyncComponent, isStaleChunkError, reloadOnceForUpdatedAssets } from '../utils/asyncComponent'
import { setWebSshTokenCookie } from '../utils/session'

type RouteSkeletonVariant = 'panel' | 'cards' | 'table' | 'detail' | 'list' | 'compact'

function appRoute(
  loader: () => Promise<any>,
  title: string,
  loadingVariant: RouteSkeletonVariant = 'panel',
) {
  return defineAppAsyncComponent(loader, {
    loadingText: `${title}加载中`,
    loadingDescription: '页面框架已就绪，正在准备本地功能模块',
    loadingDelay: 0,
    loadingVariant,
  })
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: appRoute(() => import('../views/Login.vue'), '登录页面', 'compact'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/setup',
    name: 'Setup',
    component: appRoute(() => import('../views/Setup.vue'), '初始化页面', 'compact'),
    meta: { title: '初始化设置', public: true },
  },
  {
    path: '/',
    component: appRoute(() => import('../layouts/MainLayout.vue'), '主界面'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: appRoute(() => import('../views/Dashboard.vue'), '仪表盘', 'cards'),
        meta: { title: '首页', icon: 'DashboardOutlined' },
      },
      {
        path: 'tenant',
        name: 'TenantConfig',
        component: appRoute(() => import('../views/TenantConfig.vue'), '租户配置', 'table'),
        meta: { title: '租户配置', icon: 'UserOutlined' },
      },
      {
        path: 'tenant/:tenantId/users',
        name: 'UserManagement',
        component: appRoute(() => import('../views/UserManagement.vue'), '用户管理', 'table'),
        meta: { title: '用户管理', icon: 'TeamOutlined', hidden: true },
      },
      {
        path: 'instance',
        name: 'InstanceList',
        component: appRoute(() => import('../views/InstanceList.vue'), '实例管理', 'cards'),
        meta: { title: '实例管理', icon: 'CloudServerOutlined' },
      },
      {
        path: 'scheduled-ip',
        name: 'ScheduledIp',
        component: appRoute(() => import('../views/ScheduledIp.vue'), '定时换 IP', 'table'),
        meta: { title: '定时换 IP', icon: 'ClockCircleOutlined' },
      },
      {
        path: 'task',
        name: 'TaskManager',
        component: appRoute(() => import('../views/TaskManager.vue'), '开机任务', 'table'),
        meta: { title: '开机任务', icon: 'ThunderboltOutlined' },
      },
      {
        path: 'log',
        name: 'LogViewer',
        component: appRoute(() => import('../views/LogViewer.vue'), '日志查看', 'list'),
        meta: { title: '日志查看', icon: 'FileTextOutlined' },
      },
      {
        path: 'oracle-ai',
        name: 'OracleAI',
        component: appRoute(() => import('../views/OracleAI.vue'), 'Oracle AI', 'cards'),
        meta: { title: 'Oracle AI', icon: 'ApiOutlined' },
      },
      {
        path: 'alidns',
        name: 'AliDNS',
        component: appRoute(() => import('../views/AliDNS.vue'), '阿里云 DNS', 'table'),
        meta: { title: '阿里云DNS', icon: 'GlobalOutlined' },
      },
      {
        path: 'cloudflare',
        name: 'Cloudflare',
        component: appRoute(() => import('../views/Cloudflare.vue'), 'Cloudflare', 'cards'),
        meta: { title: 'Cloudflare', icon: 'CloudOutlined' },
      },
      {
        path: 'webssh',
        name: 'WebSSH',
        component: appRoute(() => import('../views/WebSSH.vue'), 'WebSSH', 'compact'),
        meta: { title: 'WebSSH', icon: 'CodeOutlined' },
      },
      {
        path: 'settings',
        name: 'SystemSettings',
        component: appRoute(() => import('../views/SystemSettings.vue'), '系统设置', 'panel'),
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
  const token = (localStorage.getItem('token') || '').trim()
  if (!to.meta.public && !token) {
    next('/login')
  } else {
    if (token) setWebSshTokenCookie(token)
    next()
  }
})

router.onError((error) => {
  if (isStaleChunkError(error)) {
    reloadOnceForUpdatedAssets()
  }
})

export default router
