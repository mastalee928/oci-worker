import { computed, ref, type Ref } from 'vue'
import type {
  TenantWorkspaceKind,
  TenantWorkspaceOpenOptions,
  TenantWorkspaceTenant,
} from './useTenantWorkspaceDock'

interface UseTenantWorkspacePanelsOptions {
  isMobile: Ref<boolean>
  hasActiveTenant: () => boolean
  tenantWorkspaceKind: Ref<TenantWorkspaceKind | null>
  tenantWorkspaceTransitioning: Ref<boolean>
  beginTenantWorkspace: (
    kind: TenantWorkspaceKind,
    tenant: TenantWorkspaceTenant,
    options?: TenantWorkspaceOpenOptions,
  ) => void
  scheduleTenantWorkspaceOpen: (openPanel: () => void) => void
  clearFloatingTenantCard: () => void
  onVcnBeforeOpen?: (tenant: TenantWorkspaceTenant) => void
  onInstanceWorkspaceClosed?: (tenantId: string) => void
}

export function useTenantWorkspacePanels(options: UseTenantWorkspacePanelsOptions) {
  const activeTenantId = ref('')
  const instancePanelOpen = ref(false)
  const vcnVisible = ref(false)
  const vcnTenant = ref<TenantWorkspaceTenant | null>(null)
  const storageManagerOpen = ref(false)
  const storageManagerUserId = ref('')
  const storageManagerTenantName = ref('')
  const storageManagerDefaultRegion = ref('')

  function closeInstanceWorkspace() {
    const tenantId = activeTenantId.value
    instancePanelOpen.value = false
    activeTenantId.value = ''
    if (tenantId) options.onInstanceWorkspaceClosed?.(tenantId)
  }

  const instancePanelVisible = computed({
    get: () => instancePanelOpen.value && options.hasActiveTenant(),
    set: (val: boolean) => {
      if (!val) {
        closeInstanceWorkspace()
        clearWorkspaceIfIdle('instance')
      }
    },
  })

  function clearWorkspaceIfIdle(kind: TenantWorkspaceKind) {
    if (options.tenantWorkspaceTransitioning.value) return
    if (options.tenantWorkspaceKind.value === kind) {
      options.tenantWorkspaceKind.value = null
      options.clearFloatingTenantCard()
    }
  }

  function closeTenantWorkspacePanels(except: TenantWorkspaceKind) {
    if (except !== 'instance') {
      closeInstanceWorkspace()
    }
    if (except !== 'vcn') vcnVisible.value = false
    if (except !== 'storage') storageManagerOpen.value = false
  }

  function openInstanceWorkspace(tenant: TenantWorkspaceTenant, openOptions: TenantWorkspaceOpenOptions = {}) {
    const tenantId = String(tenant?.id || '')
    const previousTenantId = activeTenantId.value
    if (previousTenantId && previousTenantId !== tenantId) {
      options.onInstanceWorkspaceClosed?.(previousTenantId)
    }
    options.beginTenantWorkspace('instance', tenant, openOptions)
    closeTenantWorkspacePanels('instance')
    activeTenantId.value = tenantId
    if (!options.isMobile.value) instancePanelOpen.value = false
    options.scheduleTenantWorkspaceOpen(() => {
      if (activeTenantId.value === tenantId && options.tenantWorkspaceKind.value === 'instance') {
        instancePanelOpen.value = true
      }
    })
    return tenantId
  }

  function openVcnWorkspace(tenant: TenantWorkspaceTenant, openOptions: TenantWorkspaceOpenOptions = {}) {
    const tenantId = String(tenant?.id || '')
    options.beginTenantWorkspace('vcn', tenant, openOptions)
    closeTenantWorkspacePanels('vcn')
    vcnTenant.value = tenant
    options.onVcnBeforeOpen?.(tenant)
    if (!options.isMobile.value) vcnVisible.value = false
    options.scheduleTenantWorkspaceOpen(() => {
      if (options.tenantWorkspaceKind.value === 'vcn' && String(vcnTenant.value?.id || '') === tenantId) {
        vcnVisible.value = true
      }
    })
  }

  function openStorageWorkspace(tenant: TenantWorkspaceTenant, openOptions: TenantWorkspaceOpenOptions = {}) {
    const tenantId = String(tenant?.id || '')
    options.beginTenantWorkspace('storage', tenant, openOptions)
    closeTenantWorkspacePanels('storage')
    storageManagerUserId.value = tenantId
    storageManagerTenantName.value = tenant.username || tenant.tenantName || ''
    storageManagerDefaultRegion.value = tenant.ociRegion || ''
    if (!options.isMobile.value) storageManagerOpen.value = false
    options.scheduleTenantWorkspaceOpen(() => {
      if (options.tenantWorkspaceKind.value === 'storage' && storageManagerUserId.value === tenantId) {
        storageManagerOpen.value = true
      }
    })
  }

  function handleVcnWorkspaceClosed() {
    clearWorkspaceIfIdle('vcn')
  }

  function handleStorageWorkspaceClosed() {
    clearWorkspaceIfIdle('storage')
  }

  return {
    activeTenantId,
    instancePanelVisible,
    vcnVisible,
    vcnTenant,
    storageManagerOpen,
    storageManagerUserId,
    storageManagerTenantName,
    storageManagerDefaultRegion,
    openInstanceWorkspace,
    openVcnWorkspace,
    openStorageWorkspace,
    handleVcnWorkspaceClosed,
    handleStorageWorkspaceClosed,
  }
}
