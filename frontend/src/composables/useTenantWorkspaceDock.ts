import { computed, reactive, ref, type Ref } from 'vue'

export type TenantWorkspaceKind = 'instance' | 'vcn' | 'storage'
export type FloatingTenantCardPhase = 'idle' | 'rolling' | 'docked'
export type FloatingTenantActionKey = TenantWorkspaceKind | 'quick'
export type TenantWorkspaceOpenOptions = { dockSwitch?: boolean }

export interface TenantWorkspaceTenant {
  id?: string | number
  username?: string
  tenantName?: string
  ociRegion?: string
  planType?: string
}

interface FloatingTenantCardState {
  phase: FloatingTenantCardPhase
  tenant: TenantWorkspaceTenant | null
  tenantId: string
  username: string
  tenantName: string
  region: string
  planType: string
  left: string
  top: string
  width: string
  height: string
  dx: string
  dy: string
}

interface UseTenantWorkspaceDockOptions {
  isMobile: Ref<boolean>
  overlayActive: {
    vcnManagerEditingOverlayActive: Ref<boolean>
    storageManagerEditingOverlayActive: Ref<boolean>
    byoipEditingOverlayActive: Ref<boolean>
    instanceManagerEditingOverlayActive: Ref<boolean>
    quickTaskVisible: Ref<boolean>
  }
  onFloatingAction: {
    quick: (tenant: TenantWorkspaceTenant) => void
    instance: (tenant: TenantWorkspaceTenant) => void
    vcn: (tenant: TenantWorkspaceTenant) => void
    storage: (tenant: TenantWorkspaceTenant) => void
  }
}

const TENANT_FLOAT_DURATION_MS = 760
const TENANT_DRAWER_DELAY_MS = 220

export function useTenantWorkspaceDock(options: UseTenantWorkspaceDockOptions) {
  const tenantWorkspaceKind = ref<TenantWorkspaceKind | null>(null)
  const tenantWorkspaceTransitioning = ref(false)
  const floatingTenantCard = reactive<FloatingTenantCardState>({
    phase: 'idle',
    tenant: null,
    tenantId: '',
    username: '',
    tenantName: '',
    region: '',
    planType: '',
    left: '0px',
    top: '0px',
    width: '260px',
    height: '220px',
    dx: '0px',
    dy: '0px',
  })

  const tenantWorkspaceMaskStyle = computed(() =>
    options.isMobile.value
      ? undefined
      : {
          background: 'var(--tenant-workspace-mask-bg, rgba(15, 23, 42, 0.34))',
          backdropFilter: 'blur(8px)',
          WebkitBackdropFilter: 'blur(8px)',
        },
  )
  const instancePanelWrapClass = computed(() =>
    `instance-manager-drawer tenant-workspace-drawer tenant-workspace-${tenantWorkspaceKind.value || 'idle'}`,
  )
  const vcnPanelWrapClass = computed(() =>
    `vcn-panel-drawer tenant-workspace-drawer tenant-workspace-${tenantWorkspaceKind.value || 'idle'}`,
  )

  const floatingTenantActionItems: { key: FloatingTenantActionKey; label: string; icon: string }[] = [
    { key: 'instance', label: '实例管理', icon: 'ri-server-line' },
    { key: 'vcn', label: '虚拟云网络', icon: 'ri-share-line' },
    { key: 'storage', label: '存储', icon: 'ri-database-2-line' },
    { key: 'quick', label: '快捷开机', icon: 'ri-play-circle-line' },
  ]

  const floatingTenantCardVisible = computed(
    () =>
      !options.isMobile.value &&
      !options.overlayActive.vcnManagerEditingOverlayActive.value &&
      !options.overlayActive.storageManagerEditingOverlayActive.value &&
      !options.overlayActive.byoipEditingOverlayActive.value &&
      !options.overlayActive.instanceManagerEditingOverlayActive.value &&
      !options.overlayActive.quickTaskVisible.value &&
      floatingTenantCard.phase !== 'idle' &&
      !!floatingTenantCard.tenantId,
  )

  const floatingTenantCardStyle = computed<Record<string, string>>(() => ({
    left: floatingTenantCard.left,
    top: floatingTenantCard.top,
    width: floatingTenantCard.width,
    height: floatingTenantCard.height,
    '--tenant-float-dx': floatingTenantCard.dx,
    '--tenant-float-dy': floatingTenantCard.dy,
    '--tenant-float-duration': `${TENANT_FLOAT_DURATION_MS}ms`,
  }))

  let tenantFloatTimer: ReturnType<typeof setTimeout> | null = null
  let tenantWorkspaceOpenTimer: ReturnType<typeof setTimeout> | null = null
  let tenantWorkspaceTransitionResetTimer: ReturnType<typeof setTimeout> | null = null
  let tenantFloatRaf: ReturnType<typeof requestAnimationFrame> | null = null

  function clearTenantWorkspaceOpenTimers() {
    if (tenantWorkspaceOpenTimer !== null) window.clearTimeout(tenantWorkspaceOpenTimer)
    if (tenantWorkspaceTransitionResetTimer !== null) window.clearTimeout(tenantWorkspaceTransitionResetTimer)
    tenantWorkspaceOpenTimer = null
    tenantWorkspaceTransitionResetTimer = null
  }

  function clearTenantFloatAnimation() {
    if (tenantFloatTimer !== null) window.clearTimeout(tenantFloatTimer)
    if (tenantFloatRaf !== null) window.cancelAnimationFrame(tenantFloatRaf)
    tenantFloatTimer = null
    tenantFloatRaf = null
  }

  function beginTenantWorkspace(
    kind: TenantWorkspaceKind,
    tenant: TenantWorkspaceTenant,
    openOptions: TenantWorkspaceOpenOptions = {},
  ) {
    tenantWorkspaceKind.value = kind
    if (options.isMobile.value) return
    if (openOptions.dockSwitch) {
      tenantWorkspaceTransitioning.value = true
      refreshFloatingTenantCard(tenant)
    } else {
      tenantWorkspaceTransitioning.value = startFloatingTenantCard(tenant)
    }
  }

  function scheduleTenantWorkspaceOpen(openPanel: () => void) {
    clearTenantWorkspaceOpenTimers()
    if (options.isMobile.value) {
      openPanel()
      return
    }
    if (!tenantWorkspaceTransitioning.value) {
      openPanel()
      return
    }
    tenantWorkspaceOpenTimer = window.setTimeout(() => {
      tenantWorkspaceOpenTimer = null
      openPanel()
      tenantWorkspaceTransitionResetTimer = window.setTimeout(() => {
        tenantWorkspaceTransitionResetTimer = null
        tenantWorkspaceTransitioning.value = false
      }, 120)
    }, TENANT_DRAWER_DELAY_MS)
  }

  function isFloatingTenantSource(tenant: TenantWorkspaceTenant) {
    const tenantId = String(tenant?.id || '')
    return !options.isMobile.value && floatingTenantCard.phase !== 'idle' && floatingTenantCard.tenantId === tenantId
  }

  function findTenantCardElement(tenantId: string) {
    if (typeof document === 'undefined' || !tenantId) return null
    const cards = Array.from(document.querySelectorAll<HTMLElement>('.tenant-card[data-tenant-id]'))
    return cards.find((card) => card.dataset.tenantId === tenantId) || null
  }

  function desktopWorkspaceWidthPx() {
    if (typeof window === 'undefined') return 960
    return Math.min(1280, Math.max(960, window.innerWidth * 0.68))
  }

  function calculateFloatingTenantRect(sourceRect?: DOMRect | null) {
    if (typeof window === 'undefined') return null
    const drawerWidth = desktopWorkspaceWidthPx()
    const drawerLeft = window.innerWidth - drawerWidth
    const roomy = drawerLeft >= 320
    const gap = roomy ? 24 : 12
    const minWidth = roomy ? 220 : 168
    const availableWidth = Math.max(0, drawerLeft - gap * 2)
    const baseWidth = sourceRect?.width || Number.parseFloat(floatingTenantCard.width) || 260
    const baseHeight = sourceRect?.height || Number.parseFloat(floatingTenantCard.height) || 220
    const fallbackWidth = Math.max(minWidth, Math.min(220, window.innerWidth - gap * 2))
    const width = Math.min(roomy ? 320 : 220, Math.max(minWidth, Math.min(baseWidth, availableWidth || fallbackWidth)))
    const height = Math.max(180, Math.min(baseHeight, window.innerHeight - 96))
    const maxLeft = Math.max(gap, window.innerWidth - width - gap)
    const left = Math.max(gap, Math.min(drawerLeft - width - gap, maxLeft))
    const preferredTop = sourceRect ? sourceRect.top - 90 : Number.parseFloat(floatingTenantCard.top) || 88
    const top = Math.max(72, Math.min(window.innerHeight - height - gap, preferredTop))
    return { left, top, width, height }
  }

  function assignFloatingTenantCard(
    tenant: TenantWorkspaceTenant,
    rect?: DOMRect | null,
    phase: FloatingTenantCardPhase = 'docked',
  ) {
    const dockRect = calculateFloatingTenantRect(rect)
    Object.assign(floatingTenantCard, {
      phase: dockRect ? phase : 'idle',
      tenant,
      tenantId: String(tenant?.id || ''),
      username: tenant?.username || tenant?.tenantName || '租户',
      tenantName: tenant?.tenantName || '',
      region: tenant?.ociRegion || '',
      planType: tenant?.planType || '',
      left: `${dockRect?.left || 0}px`,
      top: `${dockRect?.top || 0}px`,
      width: `${dockRect?.width || 260}px`,
      height: `${dockRect?.height || 220}px`,
      dx: '0px',
      dy: '0px',
    })
    return dockRect
  }

  function floatingTenantButtonType(action: FloatingTenantActionKey) {
    return action !== 'quick' && tenantWorkspaceKind.value === action ? 'primary' : 'default'
  }

  function refreshFloatingTenantCard(tenant = floatingTenantCard.tenant) {
    if (!tenant || options.isMobile.value) return
    const source = findTenantCardElement(String(tenant?.id || ''))
    assignFloatingTenantCard(tenant, source?.getBoundingClientRect() || null, 'docked')
  }

  function hideFloatingTenantCard() {
    if (floatingTenantCard.phase !== 'idle') floatingTenantCard.phase = 'idle'
  }

  function clearFloatingTenantCard() {
    Object.assign(floatingTenantCard, {
      phase: 'idle',
      tenant: null,
      tenantId: '',
      dx: '0px',
      dy: '0px',
    })
  }

  function handleFloatingTenantAction(action: FloatingTenantActionKey) {
    if (floatingTenantCard.phase === 'rolling') return
    const tenant = floatingTenantCard.tenant
    if (!tenant) return
    if (action === 'quick') {
      options.onFloatingAction.quick(tenant)
      return
    }
    if (tenantWorkspaceKind.value === action) return
    options.onFloatingAction[action](tenant)
  }

  function startFloatingTenantCard(tenant: TenantWorkspaceTenant) {
    const source = findTenantCardElement(String(tenant?.id || ''))
    if (!source || typeof window === 'undefined') return false
    const rect = source.getBoundingClientRect()
    if (rect.width <= 0 || rect.height <= 0) return false
    const dockRect = calculateFloatingTenantRect(rect)
    if (!dockRect) return false
    const targetLeft = dockRect?.left ?? Math.max(24, rect.left)
    const targetTop = dockRect?.top ?? Math.max(72, Math.min(window.innerHeight - rect.height - 24, rect.top - 90))
    const dx = targetLeft - rect.left
    const dy = targetTop - rect.top

    clearTenantFloatAnimation()
    Object.assign(floatingTenantCard, {
      phase: 'idle',
      tenant,
      tenantId: String(tenant?.id || ''),
      username: tenant?.username || tenant?.tenantName || '租户',
      tenantName: tenant?.tenantName || '',
      region: tenant?.ociRegion || '',
      planType: tenant?.planType || '',
      left: `${rect.left}px`,
      top: `${rect.top}px`,
      width: `${rect.width}px`,
      height: `${rect.height}px`,
      dx: `${dx}px`,
      dy: `${dy}px`,
    })
    tenantFloatRaf = requestAnimationFrame(() => {
      tenantFloatRaf = null
      floatingTenantCard.phase = 'rolling'
      tenantFloatTimer = window.setTimeout(() => {
        if (floatingTenantCard.tenantId === String(tenant?.id || '') && dockRect) {
          Object.assign(floatingTenantCard, {
            phase: 'docked',
            left: `${dockRect.left}px`,
            top: `${dockRect.top}px`,
            width: `${dockRect.width}px`,
            height: `${dockRect.height}px`,
            dx: '0px',
            dy: '0px',
          })
        }
        tenantFloatTimer = null
      }, TENANT_FLOAT_DURATION_MS)
    })
    return true
  }

  function cleanupTenantWorkspaceDock() {
    clearTenantFloatAnimation()
    clearTenantWorkspaceOpenTimers()
  }

  return {
    tenantWorkspaceKind,
    tenantWorkspaceTransitioning,
    tenantWorkspaceMaskStyle,
    instancePanelWrapClass,
    vcnPanelWrapClass,
    floatingTenantActionItems,
    floatingTenantCard,
    floatingTenantCardVisible,
    floatingTenantCardStyle,
    beginTenantWorkspace,
    scheduleTenantWorkspaceOpen,
    isFloatingTenantSource,
    floatingTenantButtonType,
    refreshFloatingTenantCard,
    hideFloatingTenantCard,
    clearFloatingTenantCard,
    handleFloatingTenantAction,
    cleanupTenantWorkspaceDock,
  }
}
