import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getTenantList, getTenantGroups } from '../api/tenant'
import { appQueryCache } from '../utils/queryCache'

export interface TenantRecord {
  id: string
  username: string
  tenantName?: string
  ociTenantId?: string
  ociUserId?: string
  ociFingerprint?: string
  ociRegion?: string
  ociKeyPath?: string
  planType?: string
  groupLevel1?: string
  groupLevel2?: string
  createTime?: string
  taskStatus?: string
  hasRunningTask?: boolean
}

export interface GroupData {
  level1: string[]
  level2: Record<string, string[]>
}

const PAGE_SIZE = 500
const STALE_MS = 60_000
const TENANT_LIST_KEY = ['tenantCatalog', 'tenants'] as const
const TENANT_GROUPS_KEY = ['tenantCatalog', 'groups'] as const

export const useTenantCatalogStore = defineStore('tenantCatalog', () => {
  const tenants = ref<TenantRecord[]>([])
  const groupData = ref<GroupData>({ level1: [], level2: {} })
  const tenantsLoading = ref(false)
  const groupsLoading = ref(false)
  const tenantsError = ref<string | null>(null)
  let fetchTenantsReadyPromise: Promise<void> | null = null
  let fetchTenantsCompletePromise: Promise<void> | null = null
  let tenantFetchGeneration = 0

  const tenantById = computed(() => {
    const m = new Map<string, TenantRecord>()
    for (const t of tenants.value) m.set(t.id, t)
    return m
  })

  async function fetchAllTenantPages(keyword?: string): Promise<TenantRecord[]> {
    const all: TenantRecord[] = []
    let current = 1
    let total = Number.POSITIVE_INFINITY
    while (all.length < total) {
      const res = await getTenantList({
        current,
        size: PAGE_SIZE,
        keyword: keyword?.trim() || undefined,
      })
      const page = res.data
      const records = (page?.records || []) as TenantRecord[]
      total = page?.total ?? records.length
      all.push(...records)
      if (records.length < PAGE_SIZE || all.length >= total) break
      current += 1
    }
    return all
  }

  function mergeFirstTenantPage(current: TenantRecord[], incoming: TenantRecord[]) {
    const incomingIds = new Set(incoming.map((tenant) => tenant.id))
    return [...incoming, ...current.filter((tenant) => !incomingIds.has(tenant.id))]
  }

  function appendTenantPage(current: TenantRecord[], incoming: TenantRecord[]) {
    const incomingById = new Map(incoming.map((tenant) => [tenant.id, tenant]))
    const currentIds = new Set(current.map((tenant) => tenant.id))
    return [
      ...current.map((tenant) => incomingById.get(tenant.id) || tenant),
      ...incoming.filter((tenant) => !currentIds.has(tenant.id)),
    ]
  }

  function startProgressiveTenantFetch(silent: boolean) {
    const generation = ++tenantFetchGeneration
    let firstPageSettled = false
    let resolveReady!: () => void
    let rejectReady!: (reason?: unknown) => void
    const ready = new Promise<void>((resolve, reject) => {
      resolveReady = resolve
      rejectReady = reject
    })

    const complete = (async () => {
      try {
        const firstResponse = await getTenantList({ current: 1, size: PAGE_SIZE })
        if (generation !== tenantFetchGeneration) {
          firstPageSettled = true
          resolveReady()
          return
        }

        const firstPage = firstResponse.data
        const firstRecords = (firstPage?.records || []) as TenantRecord[]
        const total = Number(firstPage?.total ?? firstRecords.length)
        const all = [...firstRecords]
        tenants.value = mergeFirstTenantPage(tenants.value, firstRecords)
        tenantsError.value = null
        firstPageSettled = true
        resolveReady()
        if (!silent) tenantsLoading.value = false

        let current = 2
        while (all.length < total) {
          const response = await getTenantList({ current, size: PAGE_SIZE })
          if (generation !== tenantFetchGeneration) return
          const records = (response.data?.records || []) as TenantRecord[]
          if (records.length === 0) break
          all.push(...records)
          tenants.value = appendTenantPage(tenants.value, records)
          if (records.length < PAGE_SIZE) break
          current += 1
        }

        if (generation !== tenantFetchGeneration) return
        tenants.value = all
        appQueryCache.set(TENANT_LIST_KEY, all, STALE_MS)
      } catch (e: any) {
        if (generation !== tenantFetchGeneration) {
          if (!firstPageSettled) resolveReady()
          return
        }
        tenantsError.value = e?.message || '加载租户失败'
        if (!firstPageSettled) rejectReady(e)
      } finally {
        if (generation === tenantFetchGeneration) {
          tenantsLoading.value = false
          fetchTenantsReadyPromise = null
          fetchTenantsCompletePromise = null
        }
      }
    })()

    fetchTenantsReadyPromise = ready
    fetchTenantsCompletePromise = complete
    void complete
    return ready
  }

  /** 等待当前渐进分页全部结束；若期间被强制刷新替换，则继续等待最新一轮。 */
  async function waitForTenantsComplete() {
    while (fetchTenantsCompletePromise) {
      const pending = fetchTenantsCompletePromise
      await pending
      if (fetchTenantsCompletePromise === pending) return
    }
  }

  /** 拉取全量租户（分页合并）；force 或过期后重新请求 */
  async function ensureTenants(options?: { force?: boolean; keyword?: string; silent?: boolean }) {
    const force = options?.force === true
    const keyword = options?.keyword?.trim() || ''
    const silent = options?.silent === true

    if (keyword) return await fetchAllTenantPages(keyword)
    if (force) appQueryCache.invalidate(TENANT_LIST_KEY)

    const cached = appQueryCache.get<TenantRecord[]>(TENANT_LIST_KEY)
    if (tenants.value.length === 0 && cached?.length) {
      tenants.value = cached
    }
    if (!force && tenants.value.length > 0 && appQueryCache.isFresh(TENANT_LIST_KEY, STALE_MS)) return

    if (fetchTenantsReadyPromise && !force) {
      await fetchTenantsReadyPromise
      return
    }

    if (!silent && tenants.value.length === 0) tenantsLoading.value = true
    tenantsError.value = null
    await startProgressiveTenantFetch(silent)
  }

  async function ensureGroups(options?: { force?: boolean; silent?: boolean }) {
    const force = options?.force === true
    const silent = options?.silent === true
    if (!force && groupData.value.level1.length > 0 && appQueryCache.isFresh(TENANT_GROUPS_KEY, STALE_MS)) {
      return
    }
    if (!silent) groupsLoading.value = true
    try {
      const res = await appQueryCache.fetch(TENANT_GROUPS_KEY, () => getTenantGroups(), { staleMs: STALE_MS, force })
      groupData.value = res.data || { level1: [], level2: {} }
    } finally {
      if (!silent) groupsLoading.value = false
    }
  }

  async function refreshCatalog(options?: { force?: boolean }) {
    await Promise.all([
      ensureTenants({ force: options?.force ?? true }),
      ensureGroups({ force: options?.force ?? true }),
    ])
  }

  function invalidate() {
    tenantFetchGeneration += 1
    fetchTenantsReadyPromise = null
    fetchTenantsCompletePromise = null
    tenantsLoading.value = false
    appQueryCache.invalidate(['tenantCatalog'])
  }

  function patchTenant(id: string, patch: Partial<TenantRecord>) {
    const idx = tenants.value.findIndex((t) => t.id === id)
    if (idx >= 0) {
      tenants.value[idx] = { ...tenants.value[idx], ...patch }
    }
  }

  function removeTenantsFromCache(ids: string[]) {
    const set = new Set(ids)
    tenants.value = tenants.value.filter((t) => !set.has(t.id))
  }

  return {
    tenants,
    groupData,
    tenantsLoading,
    groupsLoading,
    tenantsError,
    tenantById,
    ensureTenants,
    waitForTenantsComplete,
    ensureGroups,
    refreshCatalog,
    invalidate,
    patchTenant,
    removeTenantsFromCache,
    fetchAllTenantPages,
  }
})
