import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { defineAppAsyncComponent, isStaleChunkError, reloadOnceForUpdatedAssets } from '../utils/asyncComponent'
import Login from '../views/Login.vue'
import Setup from '../views/Setup.vue'
import {
  clearPanelSession,
  isPanelSessionValidated,
  markPanelSessionValidated,
  setPanelAccount,
  setWebSshTokenCookie,
} from '../utils/session'

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
    // 登录页是未认证用户的首屏入口，直接打入主包，避免先闪出“登录页面加载中”。
    component: Login,
    meta: { title: '登录', public: true },
  },
  {
    path: '/setup',
    name: 'Setup',
    // 初始化页同样必须首屏可用，不经过异步组件骨架。
    component: Setup,
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

type StoredSessionState = 'valid' | 'invalid' | 'unavailable'

let validatingToken = ''
let validationPromise: Promise<StoredSessionState> | null = null

/**
 * 首次打开面板时验证浏览器中残留的 token。不能依赖页面 API 的 401 再跳登录，
 * 否则主布局和仪表盘会先被挂载，造成未登录用户短暂看到后台页面。
 */
async function validateStoredSession(token: string): Promise<StoredSessionState> {
  if (isPanelSessionValidated(token)) return 'valid'
  if (validationPromise && validatingToken === token) return validationPromise

  validatingToken = token
  validationPromise = (async () => {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 8000)
    try {
      const authorization = token.startsWith('Bearer ') ? token : `Bearer ${token}`
      const response = await fetch('/api/auth/account', {
        method: 'GET',
        headers: { Authorization: authorization },
        credentials: 'include',
        cache: 'no-store',
        signal: controller.signal,
      })
      const body = await response.json().catch(() => null)
      if (response.status === 401 || body?.code === 401) return 'invalid'
      if (response.ok && body?.code === 0) {
        const account = String(body?.data?.account || '').trim()
        if (account) setPanelAccount(account)
        markPanelSessionValidated(token)
        return 'valid'
      }
      return 'unavailable'
    } catch {
      // 服务启动或网络短暂异常时保留现有会话，让后续 API 按原逻辑处理，避免误登出。
      return 'unavailable'
    } finally {
      window.clearTimeout(timeout)
    }
  })()

  try {
    return await validationPromise
  } finally {
    if (validatingToken === token) {
      validatingToken = ''
      validationPromise = null
    }
  }
}

router.beforeEach(async (to) => {
  const token = (localStorage.getItem('token') || '').trim()
  if (to.meta.public) return true
  if (!token) return { path: '/login', replace: true }

  setWebSshTokenCookie(token)

  // 登录或初始化接口刚签发的 token 已由 setLoginSession 标记，避免重复串行验证。
  if (isPanelSessionValidated(token)) return true

  const state = await validateStoredSession(token)
  if (state === 'invalid') {
    clearPanelSession()
    return { path: '/login', replace: true }
  }
  // 服务不可用或校验结果不明确时同样不挂载后台；保留 token，避免瞬时故障误清会话。
  if (state === 'unavailable') return { path: '/login', replace: true }
  return true
})

router.onError((error) => {
  if (isStaleChunkError(error)) {
    reloadOnceForUpdatedAssets()
  }
})

export default router
