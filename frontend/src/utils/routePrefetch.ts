import { isStaleChunkError, reloadOnceForUpdatedAssets } from './asyncComponent'

const routeImporters: Record<string, () => Promise<unknown>> = {
  dashboard: () => import('../views/Dashboard.vue'),
  tenant: () => import('../views/TenantConfig.vue'),
  instance: () => import('../views/InstanceList.vue'),
  'scheduled-ip': () => import('../views/ScheduledIp.vue'),
  'instance-guard': () => import('../views/InstanceGuard.vue'),
  task: () => import('../views/TaskManager.vue'),
  log: () => import('../views/LogViewer.vue'),
  'oracle-ai': () => import('../views/OracleAI.vue'),
  alidns: () => import('../views/AliDNS.vue'),
  cloudflare: () => import('../views/Cloudflare.vue'),
  webssh: () => import('../views/WebSSH.vue'),
  settings: () => import('../views/SystemSettings.vue'),
}

const prefetchedRoutes = new Set<string>()

export function prefetchRouteChunk(menuKey: string) {
  const fn = routeImporters[menuKey]
  if (!fn || prefetchedRoutes.has(menuKey)) return
  prefetchedRoutes.add(menuKey)
  void fn().catch((error) => {
    prefetchedRoutes.delete(menuKey)
    if (isStaleChunkError(error)) reloadOnceForUpdatedAssets()
  })
}

export function prefetchMainRoutesIdle() {
  const prefetchPrimary = () => {
    for (const key of ['dashboard', 'tenant', 'instance']) {
      prefetchRouteChunk(key)
    }
    scheduleIdle(prefetchSecondary, 6000, 1200)
  }
  const prefetchSecondary = () => {
    for (const key of ['task', 'log', 'webssh']) {
      prefetchRouteChunk(key)
    }
  }
  scheduleIdle(prefetchPrimary, 4000, 800)
}

function scheduleIdle(task: () => void, timeout: number, fallbackDelay: number) {
  if (typeof requestIdleCallback === 'function') {
    requestIdleCallback(task, { timeout })
  } else {
    setTimeout(task, fallbackDelay)
  }
}
