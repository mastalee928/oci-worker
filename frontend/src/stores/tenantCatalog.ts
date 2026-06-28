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

const PAGE_SIZE = 100
const STALE_MS = 60_000
const TENANT_LIST_KEY = ['tenantCatalog', 'tenants'] as const
const TENANT_GROUPS_KEY = ['tenantCatalog', 'groups'] as const

export const useTenantCatalogStore = defineStore('tenantCatalog', () => {
  const tenants = ref<TenantRecord[]>([])
  const groupData = ref<GroupData>({ level1: [], level2: {} })
  const tenantsLoading = ref(false)
  const groupsLoading = ref(false)
  const tenantsError = ref<string | null>(null)
  let tenantsFetchedAt = 0
  let groupsFetchedAt = 0
  let fetchTenantsPromise: Promise<void> | null = null

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

  /** 拉取全量租户（分页合并）；force 或过期后重新请求 */
  async function ensureTenants(options?: { force?: boolean; keyword?: string; silent?: boolean }) {
    const force = options?.force === true
    const keyword = options?.keyword?.trim() || ''
    const silent = options?.silent === true

    if (!force && !keyword && tenants.value.length > 0 && appQueryCache.isFresh(TENANT_LIST_KEY, STALE_MS)) {
      return
    }

    if (fetchTenantsPromise && !force && !keyword) {
      await fetchTenantsPromise
      return
    }

    if (!silent) tenantsLoading.value = true
    tenantsError.value = null

    const run = async () => {
      try {
        const list = keyword
          ? await fetchAllTenantPages(keyword)
          : await appQueryCache.fetch(TENANT_LIST_KEY, () => fetchAllTenantPages(), { staleMs: STALE_MS, force })
        if (!keyword) {
          tenants.value = list
          tenantsFetchedAt = appQueryCache.getUpdatedAt(TENANT_LIST_KEY) || Date.now()
        }
        return list
      } catch (e: any) {
        tenantsError.value = e?.message || '加载租户失败'
        throw e
      } finally {
        if (!silent) tenantsLoading.value = false
        fetchTenantsPromise = null
      }
    }

    if (!keyword) {
      fetchTenantsPromise = run().then(() => undefined)
      await fetchTenantsPromise
    } else {
      return await run()
    }
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
      groupsFetchedAt = appQueryCache.getUpdatedAt(TENANT_GROUPS_KEY) || Date.now()
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
    tenantsFetchedAt = 0
    groupsFetchedAt = 0
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
    ensureGroups,
    refreshCatalog,
    invalidate,
    patchTenant,
    removeTenantsFromCache,
    fetchAllTenantPages,
  }
})
