import { isStaleChunkError, reloadOnceForUpdatedAssets } from './asyncComponent'

const routeImporters: Record<string, () => Promise<unknown>> = {
  dashboard: () => import('../views/Dashboard.vue'),
  tenant: () => import('../views/TenantConfig.vue'),
  instance: () => import('../views/InstanceList.vue'),
  'scheduled-ip': () => import('../views/ScheduledIp.vue'),
  task: () => import('../views/TaskManager.vue'),
  log: () => import('../views/LogViewer.vue'),
  'oracle-ai': () => import('../views/OracleAI.vue'),
  cloudflare: () => import('../views/Cloudflare.vue'),
  settings: () => import('../views/SystemSettings.vue'),
}

export function prefetchRouteChunk(menuKey: string) {
  const fn = routeImporters[menuKey]
  if (!fn) return
  void fn().catch((error) => {
    if (isStaleChunkError(error)) reloadOnceForUpdatedAssets()
  })
}

export function prefetchMainRoutesIdle() {
  const run = () => {
    for (const key of ['dashboard', 'tenant', 'instance']) {
      prefetchRouteChunk(key)
    }
  }
  if (typeof requestIdleCallback === 'function') {
    requestIdleCallback(run, { timeout: 4000 })
  } else {
    setTimeout(run, 800)
  }
}
