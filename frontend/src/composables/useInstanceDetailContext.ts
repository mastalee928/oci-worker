import { computed, nextTick, ref } from 'vue'

type DetailDrawerShellMethod =
  | 'resetAllPanels'
  | 'stopShapeSilently'
  | 'loadShapeOptions'
  | 'loadNetworkVcns'
  | 'loadBlockVolumes'
  | 'loadNetworkDetail'

interface UseInstanceDetailContextOptions {
  getActiveTenantData: () => any | null
  resolveDetailRegionParam: () => { region?: string }
  clearDetailOverlays: () => void
  clearConsoleData: () => void
}

export function useInstanceDetailContext(options: UseInstanceDetailContextOptions) {
  const currentTenant = ref<any>(null)
  const currentInstance = ref<any>(null)
  const drawerVisible = ref(false)
  const activeTab = ref('info')
  const detailDrawerShellRef = ref<any>(null)
  const currentDetailRegion = computed(() => options.resolveDetailRegionParam().region)

  async function callDetailDrawerShell(method: DetailDrawerShellMethod, args: any[] = [], retries = 6) {
    for (let attempt = 0; attempt <= retries; attempt += 1) {
      const fn = detailDrawerShellRef.value?.[method]
      if (typeof fn === 'function') return fn(...args)
      if (attempt >= retries) return undefined
      await nextTick()
      await new Promise(resolve => window.setTimeout(resolve, 80))
    }
    return undefined
  }

  function onTabChange(key: string) {
    if (key === 'shape') {
      void nextTick(() => callDetailDrawerShell('loadShapeOptions'))
    }
  }

  function openDetail(tenant: any, record: any) {
    if (!tenant || !record) return
    void callDetailDrawerShell('stopShapeSilently', [], 0)
    currentTenant.value = tenant
    currentInstance.value = record
    activeTab.value = 'info'
    void callDetailDrawerShell('resetAllPanels')
    options.clearDetailOverlays()
    options.clearConsoleData()
    drawerVisible.value = true
  }

  function handleInstanceListOpenDetail(record: any) {
    const td = options.getActiveTenantData()
    if (!td) return
    openDetail(td.tenant, record)
  }

  function closeDrawer() {
    drawerVisible.value = false
  }

  return {
    currentTenant,
    currentInstance,
    drawerVisible,
    activeTab,
    detailDrawerShellRef,
    currentDetailRegion,
    callDetailDrawerShell,
    onTabChange,
    handleInstanceListOpenDetail,
    closeDrawer,
  }
}
