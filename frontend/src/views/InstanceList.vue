<template>
  <div>
    <!-- 顶部工具栏 -->
    <div class="instance-toolbar">
      <div class="toolbar-left">
        <a-input-search
          v-model:value="searchKeyword"
          placeholder="搜索租户（名称/区域）"
          style="width: 300px"
          allow-clear
        />
      </div>
      <div class="toolbar-right">
        <a-segmented v-model:value="tenantViewMode" size="small" :options="[{ label: '卡片', value: 'card' }, { label: '列表', value: 'table' }]" />
        <a-button @click="loadAllTenants(true)" :loading="globalLoading">
          <template #icon><ReloadOutlined /></template>刷新
        </a-button>
      </div>
    </div>

    <InstanceTenantEntryPanel
      :filtered-tenants="filteredTenants"
      :grouped-tenants="groupedTenants"
      :has-groups="hasGroups"
      :tenant-view-mode="tenantViewMode"
      :active-tenant-id="activeTenantId"
      :is-mobile="isMobile"
      :global-loading="globalLoading"
      :active-group-keys="activeGroupKeys"
      :active-l2-keys="activeL2Keys"
      :tenant-virtual-list-max-height="tenantVirtualListMaxHeight"
      :tenant-virtual-reset-key="tenantVirtualResetKey"
      :tenant-data-key="tenantDataKey"
      :should-virtualize-tenant-cards="shouldVirtualizeTenantCards"
      :is-floating-tenant-source="isFloatingTenantSource"
      :tenant-plan-tag-style="tenantPlanTagStyle"
      :tenant-plan-tag-color="tenantPlanTagColor"
      :format-tenant-plan-type="formatTenantPlanType"
      :group-tenant-count="groupTenantCount"
      :is-group-panel-open="isGroupPanelOpen"
      :is-l2-panel-open="isL2PanelOpen"
      @select-tenant="selectTenant"
      @open-vcn="openVcnPanel"
      @open-storage="openStoragePanel"
      @open-quick-task="openQuickTask"
      @collapse-change="onCollapseChange"
      @l2-collapse-change="onL2CollapseChange"
    />
    <!-- 实例管理抽屉 -->
    <a-drawer :keyboard="false"
      v-model:open="instancePanelVisible"
      :width="instancePanelWidth"
      :mask-closable="false"
      :mask-style="tenantWorkspaceMaskStyle"
      destroy-on-close
      :wrap-class-name="instancePanelWrapClass"
      :body-style="{ padding: isMobile ? '10px' : '16px' }"
    >
      <template #title>
        <div v-if="activeTenantData" class="instance-drawer-title">
          <i class="ri-server-line" style="margin-right: 8px; color: var(--primary)"></i>
          <span class="drawer-username">{{ activeTenantData.tenant.username }}</span>
          <a-tag v-if="!isMobile" color="blue" style="margin-left: 8px">{{ instancePanelRegion || activeTenantData.tenant.ociRegion }}</a-tag>
          <a-badge :count="activeTenantData.instances.length" :show-zero="true" class="oci-group-count-badge" style="margin-left: 8px" />
        </div>
      </template>
      <template #extra>
        <div v-if="activeTenantData" class="panel-actions">
          <a-button size="small" @click.stop="refreshActiveTenantInstances" :loading="activeTenantData.loading">
            <template #icon><ReloadOutlined /></template>{{ isMobile ? '' : '刷新' }}
          </a-button>
        </div>
      </template>
      <InstanceDrawerListPanel
        v-if="activeTenantData"
        :tenant-data="activeTenantData"
        v-model:region="instancePanelRegion"
        :region-options="instanceRegionOptions"
        :region-loading="instanceSubscribedRegionsLoading"
        :is-mobile="isMobile"
        :state-color-map="stateColorMap"
        :action-loading="actionLoading"
        :virtual-card-min="VIRTUAL_CARD_MIN"
        :mobile-virtual-max-height="instanceMobileVirtualMaxHeight"
        :virtual-reset-key="instanceVirtualResetKey"
        :item-key="instanceRecordKey"
        @refresh="refreshActiveTenantInstances"
        @region-change="onInstancePanelRegionUserChange"
        @open-detail="handleInstanceListOpenDetail"
        @menu-click="handleInstanceListMenuClick"
      />
    </a-drawer>

    <!-- 快捷开机任务弹窗 -->
    <QuickTaskModal
      v-model:open="quickTaskVisible"
      v-model:dense-io-tier-key="quickDenseIoTierKey"
      :loading="quickTaskLoading"
      :tenant="quickTaskTenant"
      :form="quickTaskForm"
      :shapes="quickTaskShapes"
      :shapes-loading="quickTaskShapesLoading"
      :popup-container="quickTaskPopupContainer"
      :bm-locked="quickTaskBmLocked"
      :saved-root-password="quickTaskSavedRootPassword"
      :saved-ssh-public-key="quickTaskSavedSshPublicKey"
      :credential-loading="quickTaskCredentialLoading"
      :shape-limits="quickTaskShapeLimits"
      :ocpu-label="quickTaskOcpuLabel"
      :memory-label="quickTaskMemoryLabel"
      :dense-io-tiers="quickDenseIoTiers"
      :format-dense-io-tier-label="formatDenseIoTierLabel"
      :dense-io-flex-tier-key="denseIoFlexTierKey"
      :is-mobile="isMobile"
      @confirm="handleQuickTask"
      @credential-missing="warnQuickTaskCredentialMissing"
      @update-ocpus="updateQuickTaskOcpus"
      @update-memory="updateQuickTaskMemory"
      @clamp-resources="clampQuickTaskResources"
      @snap-boot-vpus="snapQuickTaskBootVpus"
    />

    <InstanceDetailDrawerShell
      ref="detailDrawerShellRef"
      v-model:open="drawerVisible"
      v-model:active-tab="activeTab"
      :tenant="currentTenant"
      :instance="currentInstance"
      :is-mobile="isMobile"
      :current-detail-region="currentDetailRegion"
      :state-color-map="stateColorMap"
      :action-loading="actionLoading"
      :change-ip-loading="changeIpLoading"
      :instance-info-loading="instanceInfoLoading"
      :show-force-a2-to-a1-button="showForceA2ToA1Button"
      :console-loading="consoleLoading"
      :console-data="consoleData"
      :on-stop-instance="stopCurrentDetailInstance"
      @tab-change="onTabChange"
      @refresh-info="refreshInstanceInfo"
      @edit-instance="openEditInstance"
      @instance-action="handleCurrentInstanceAction"
      @change-ip="handleChangeIp"
      @terminate="openCurrentTerminateVerify"
      @security-overlay-active-change="securityOverlayActive = $event"
      @boot-volume-overlay-active-change="bootVolumeOverlayActive = $event"
      @boot-volume-updated="onBootVolumeUpdated"
      @block-storage-overlay-active-change="blockStorageOverlayActive = $event"
      @open-vcn-manager="openDetailVcnManager"
      @traffic-overlay-active-change="trafficOverlayActive = $event"
      @focus-shape-panel="activeTab = 'shape'"
      @shape-instance-updated="handleShapeEditInstanceUpdated"
      @reload-instance-list="scheduleCurrentTenantInstanceReload"
      @open-force-a2-to-a1="openForceA2ToA1Modal"
      @create-console="handleCreateConsole"
      @open-console="openConsoleWebSSH"
      @delete-console="handleDeleteConsole"
    />


    <InstanceEditModal
      v-if="editInstanceVisible || editInstanceLoading"
      v-model:open="editInstanceVisible"
      v-model:display-name="editInstanceForm.displayName"
      :instance="currentInstance"
      :loading="editInstanceLoading"
      :is-mobile="isMobile"
      :on-confirm="handleEditInstance"
    />

    <ForceA2ConfirmModal
      v-if="forceA2ModalVisible"
      v-model:open="forceA2ModalVisible"
      v-model:trial="forceA2Q.trial"
      v-model:a2-shape="forceA2Q.a2Shape"
      v-model:risk="forceA2Q.risk"
      :loading="forceA2Loading"
      :all-yes="forceA2AllYes"
      :is-mobile="isMobile"
      :on-confirm="handleForceA2ToA1Confirm"
      @cancel="resetForceA2Modal"
    />

    <TerminateVerifyModal
      v-if="verifyModalVisible"
      v-model:open="verifyModalVisible"
      v-model:code="verifyCode"
      v-model:delete-boot-volume="deleteBootVolume"
      :loading="verifyLoading"
      :sending="verifySending"
      :on-confirm="handleTerminateWithCode"
      :on-resend="resendTerminateVerifyCode"
    />

    <TenantVcnPanel
      ref="tenantVcnPanelRef"
      v-model:open="vcnVisible"
      :tenant="vcnTenant"
      :width="instancePanelWidth"
      :mask-style="tenantWorkspaceMaskStyle"
      :wrap-class-name="vcnPanelWrapClass"
      @open-vcn-manager="openTenantVcnManager"
      @editing-overlay-change="handleTenantVcnEditingOverlayChange"
      @reserved-ip-changed="handleTenantVcnReservedIpChanged"
    />

    <VcnManager
      v-model:open="vcnManagerOpen"
      :user-id="vcnManagerUserId"
      :vcn="vcnManagerVcn"
      :oci-region="vcnManagerOciRegion"
      :initial-tab="vcnManagerInitialTab"
      :target-resource-id="vcnManagerTargetResourceId"
      @changed="onVcnManagerChanged"
      @editing-overlay-change="handleVcnManagerEditingOverlayChange"
    />

    <StorageManager
      v-model:open="storageManagerOpen"
      :user-id="storageManagerUserId"
      :tenant-name="storageManagerTenantName"
      :default-region="storageManagerDefaultRegion"
      @editing-overlay-change="handleStorageManagerEditingOverlayChange"
    />

    <div v-show="floatingTenantCardVisible">
      <InstanceFloatingTenantCard
        :visible="floatingTenantCardVisible"
        :card="floatingTenantCard"
        :card-style="floatingTenantCardStyle"
        :workspace-kind="tenantWorkspaceKind"
        :action-items="floatingTenantActionItems"
        :tenant-plan-tag-style="tenantPlanTagStyle"
        :tenant-plan-tag-color="tenantPlanTagColor"
        :format-tenant-plan-type="formatTenantPlanType"
        :floating-tenant-button-type="floatingTenantButtonType"
        @action-click="handleFloatingTenantAction"
      />
    </div>

    <div class="tenant-page-float-actions" aria-label="页面快捷操作">
      <a-tooltip
        v-if="hasGroups"
        placement="left"
        :title="allInstanceGroupsExpanded ? '收起所有一级分组与子分组' : '展开所有一级分组与子分组'"
      >
        <a-button type="default" shape="circle" class="float-action-btn" @click="toggleAllInstanceGroups">
          <template #icon>
            <MenuUnfoldOutlined v-if="allInstanceGroupsExpanded" />
            <MenuFoldOutlined v-else />
          </template>
        </a-button>
      </a-tooltip>
      <a-tooltip placement="left" title="返回页面顶部">
        <a-button type="default" shape="circle" class="float-action-btn" @click="scrollInstancePageTop">
          <template #icon><VerticalAlignTopOutlined /></template>
        </a-button>
      </a-tooltip>
    </div>

  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'InstanceList' })

import { ref, reactive, computed, nextTick, onMounted, onActivated, onUnmounted, watch } from 'vue'
import {
  ReloadOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  VerticalAlignTopOutlined,
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import {
  getInstanceList,
  updateInstance,
} from '../api/instance'
import { getTenantGroups } from '../api/tenant'
import { useTenantCatalogStore } from '../stores/tenantCatalog'
import { defineAppAsyncComponent } from '../utils/asyncComponent'

const VcnManager = defineAppAsyncComponent(() => import('./VcnManager.vue'))
const StorageManager = defineAppAsyncComponent(() => import('./StorageManager.vue'))
const ForceA2ConfirmModal = defineAppAsyncComponent(() => import('../components/instance/ForceA2ConfirmModal.vue'))
const TerminateVerifyModal = defineAppAsyncComponent(() => import('../components/instance/TerminateVerifyModal.vue'))
const InstanceDetailDrawerShell = defineAppAsyncComponent(() => import('../components/instance/InstanceDetailDrawerShell.vue'))
const InstanceEditModal = defineAppAsyncComponent(() => import('../components/instance/InstanceEditModal.vue'))
const InstanceDrawerListPanel = defineAppAsyncComponent(() => import('../components/instance/InstanceDrawerListPanel.vue'))
const TenantVcnPanel = defineAppAsyncComponent(() => import('../components/instance/TenantVcnPanel.vue'))
const QuickTaskModal = defineAppAsyncComponent(() => import('../components/instance/QuickTaskModal.vue'))
const InstanceTenantEntryPanel = defineAppAsyncComponent(() => import('../components/instance/InstanceTenantEntryPanel.vue'))
const InstanceFloatingTenantCard = defineAppAsyncComponent(() => import('../components/instance/InstanceFloatingTenantCard.vue'))
import { listStorageRegions } from '../api/storage'
import { useQuickTask } from '../composables/useQuickTask'
import { useForceA2ToA1 } from '../composables/useForceA2ToA1'
import { useInstanceActions } from '../composables/useInstanceActions'
import { useInstanceConsole } from '../composables/useInstanceConsole'
import { useTerminateInstanceVerify } from '../composables/useTerminateInstanceVerify'
import {
  useTenantWorkspaceDock,
  type TenantWorkspaceOpenOptions,
} from '../composables/useTenantWorkspaceDock'
import { useTenantWorkspacePanels } from '../composables/useTenantWorkspacePanels'
import { isAllGroupsExpanded } from '../composables/groupExpandToggle'
import {
  formatTenantPlanType,
  isFreeTierPlan,
  tenantPlanTagColor,
} from '../utils/tenantPlan'
import { appQueryCache, createListSignature } from '../utils/queryCache'

const catalog = useTenantCatalogStore()
const VIRTUAL_CARD_MIN = 12
const INSTANCE_LIST_CACHE_TTL_MS = 60_000
let instanceListActivatedOnce = false

interface LoadTenantInstancesOptions {
  force?: boolean
  notify?: boolean
}

function isGroupPanelOpen(key: string) {
  return activeGroupKeys.value.includes(key)
}

function isL2PanelOpen(key: string) {
  return activeL2Keys.value.includes(key)
}

function tenantPlanTagStyle(plan: unknown): Record<string, string> | undefined {
  if (!isFreeTierPlan(plan)) return undefined
  return {
    color: 'var(--tenant-free-tier-color)',
    background: 'var(--tenant-free-tier-bg)',
    borderColor: 'var(--tenant-free-tier-border)',
    boxShadow: 'var(--tenant-free-tier-shadow)',
    backdropFilter: 'blur(10px) saturate(140%)',
    WebkitBackdropFilter: 'blur(10px) saturate(140%)',
  }
}

interface TenantData {
  tenant: any
  instances: any[]
  loading: boolean
  collapsed: boolean
}

const stateColorMap: Record<string, string> = {
  RUNNING: 'success', STOPPED: 'error', STARTING: 'processing',
  STOPPING: 'warning', TERMINATED: 'default',
}

const isMobile = ref(window.innerWidth < 768)
const viewportHeight = ref(window.innerHeight)
function checkMobile() {
  viewportHeight.value = window.innerHeight
  isMobile.value = window.innerWidth < 768
  if (isMobile.value) {
    hideFloatingTenantCard()
    return
  }
  const tenant = resolveFloatingTenantFromWorkspace()
  if (tenant) refreshFloatingTenantCard(tenant)
}

const tenantViewMode = ref<'card' | 'table'>('card')
const searchKeyword = ref('')
const globalLoading = ref(false)
const tenantDataList = ref<TenantData[]>([])

const currentTenant = ref<any>(null)
const currentInstance = ref<any>(null)

const filteredTenants = computed(() => {
  if (!searchKeyword.value) return tenantDataList.value
  const kw = searchKeyword.value.toLowerCase()
  return tenantDataList.value.filter(td =>
    (td.tenant.username || '').toLowerCase().includes(kw) ||
    (td.tenant.ociRegion || '').toLowerCase().includes(kw) ||
    (td.tenant.tenantName || '').toLowerCase().includes(kw)
  )
})

const tenantVirtualListMaxHeight = computed(() => Math.max(460, Math.min(760, viewportHeight.value - 180)))
function shouldVirtualizeTenantCards(count: number) {
  return count > VIRTUAL_CARD_MIN
}
function tenantDataKey(item: unknown, index: number) {
  return String((item as TenantData)?.tenant?.id ?? index)
}
const tenantVirtualResetKey = computed(() =>
  `${tenantViewMode.value}|${searchKeyword.value}|${createListSignature(filteredTenants.value, (td) => td.tenant.id)}`,
)

interface GroupNode {
  label: string
  key: string
  children?: GroupNode[]
  tenants: TenantData[]
}
const groupData = computed(() => catalog.groupData)

const COLLAPSE_KEY = 'instanceList.groupCollapse.v2'

interface CollapsePersist {
  l1: string[]
  l2: string[]
}

function migrateFlatCollapseKeys(flat: string[]): CollapsePersist {
  const l1: string[] = []
  const l2: string[] = []
  for (const k of flat) {
    if (String(k).includes('/')) l2.push(String(k))
    else l1.push(String(k))
  }
  return { l1, l2 }
}

function loadCollapseState(): CollapsePersist {
  try {
    const raw = JSON.parse(localStorage.getItem(COLLAPSE_KEY) || 'null')
    if (raw && Array.isArray(raw.l1)) {
      return { l1: raw.l1, l2: Array.isArray(raw.l2) ? raw.l2 : [] }
    }
    if (Array.isArray(raw)) {
      return migrateFlatCollapseKeys(raw)
    }
  } catch { /* ignore */ }
  try {
    const legacy = JSON.parse(localStorage.getItem('instanceList.groupCollapse') || '[]')
    if (Array.isArray(legacy) && legacy.length) {
      return migrateFlatCollapseKeys(legacy)
    }
  } catch { /* ignore */ }
  return { l1: [], l2: [] }
}

function saveCollapseState() {
  try {
    const payload: CollapsePersist = { l1: activeGroupKeys.value, l2: activeL2Keys.value }
    localStorage.setItem(COLLAPSE_KEY, JSON.stringify(payload))
  } catch { /* ignore */ }
}

const initialCollapse = loadCollapseState()
const activeGroupKeys = ref<string[]>(initialCollapse.l1)
const activeL2Keys = ref<string[]>(initialCollapse.l2)

function onCollapseChange(keys: string | string[]) {
  activeGroupKeys.value = Array.isArray(keys) ? keys.map(String) : [String(keys)]
  saveCollapseState()
}

function onL2CollapseChange(keys: string | string[]) {
  activeL2Keys.value = Array.isArray(keys) ? keys.map(String) : [String(keys)]
  saveCollapseState()
}

function collectL1ExpandableKeys(nodes: GroupNode[]): string[] {
  return nodes.filter((g) => groupTenantCount(g) > 0).map((g) => g.key)
}

function collectL2ExpandableKeys(nodes: GroupNode[]): string[] {
  const keys: string[] = []
  for (const g of nodes) {
    if (!g.children) continue
    for (const c of g.children) {
      if (c.tenants.length > 0) keys.push(c.key)
    }
  }
  return keys
}

const allInstanceGroupsExpanded = computed(() => {
  const l1Keys = collectL1ExpandableKeys(groupedTenants.value)
  const l2Keys = collectL2ExpandableKeys(groupedTenants.value)
  return isAllGroupsExpanded(activeGroupKeys.value, l1Keys) && isAllGroupsExpanded(activeL2Keys.value, l2Keys)
})

function toggleAllInstanceGroups() {
  const l1Keys = collectL1ExpandableKeys(groupedTenants.value)
  const l2Keys = collectL2ExpandableKeys(groupedTenants.value)
  if (allInstanceGroupsExpanded.value) {
    activeGroupKeys.value = []
    activeL2Keys.value = []
  } else {
    activeGroupKeys.value = [...l1Keys]
    activeL2Keys.value = [...l2Keys]
  }
  saveCollapseState()
}

function scrollInstancePageTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

const groupedTenants = computed<GroupNode[]>(() => {
  const all = filteredTenants.value
  const gd = groupData.value
  const l1Map = new Map<string, TenantData[]>()
  for (const td of all) {
    const g1 = td.tenant.groupLevel1 || '未分组'
    const list = l1Map.get(g1) || []
    list.push(td)
    l1Map.set(g1, list)
  }
  for (const g1 of gd.level1) {
    if (!l1Map.has(g1)) l1Map.set(g1, [])
  }
  const orderedKeys: string[] = []
  for (const g1 of gd.level1) { if (l1Map.has(g1) && !orderedKeys.includes(g1)) orderedKeys.push(g1) }
  for (const k of l1Map.keys()) { if (!orderedKeys.includes(k)) orderedKeys.push(k) }

  const nodes: GroupNode[] = []
  for (const l1 of orderedKeys) {
    const items = l1Map.get(l1) || []
    const withL2 = items.filter(td => !!td.tenant.groupLevel2)
    const withoutL2 = items.filter(td => !td.tenant.groupLevel2)
    const l2Map = new Map<string, TenantData[]>()
    for (const td of withL2) {
      const list = l2Map.get(td.tenant.groupLevel2) || []
      list.push(td)
      l2Map.set(td.tenant.groupLevel2, list)
    }
    const l2Names = gd.level2[l1] || []
    for (const l2 of l2Names) { if (!l2Map.has(l2)) l2Map.set(l2, []) }
    const children: GroupNode[] = []
    for (const [l2, l2Items] of l2Map) {
      children.push({ label: l2, key: `${l1}/${l2}`, tenants: l2Items })
    }
    nodes.push({ label: l1, key: l1, children: children.length > 0 ? children : undefined, tenants: withoutL2 })
  }
  return nodes
})

const hasGroups = computed(() => {
  const gd = groupData.value
  return gd.level1.length > 0 && !(gd.level1.length === 1 && gd.level1[0] === '未分组')
})

function groupTenantCount(g: GroupNode): number {
  return g.tenants.length + (g.children?.reduce((s, c) => s + c.tenants.length, 0) || 0)
}

async function loadGroups() {
  try {
    await catalog.ensureGroups({ force: false })
  } catch {}
}

watch(searchKeyword, (kw) => {
  if (kw && hasGroups.value) {
    const matchedKeys: string[] = []
    for (const g of groupedTenants.value) {
      if (groupTenantCount(g) > 0) matchedKeys.push(g.key)
    }
    activeGroupKeys.value = matchedKeys
    saveCollapseState()
  }
})

const activeTenantData = computed(() => {
  if (!activeTenantId.value) return null
  return tenantDataList.value.find(td => String(td.tenant.id || '') === activeTenantId.value) || null
})
const instanceMobileVirtualMaxHeight = computed(() => Math.max(360, Math.min(680, viewportHeight.value - 220)))
const instanceVirtualResetKey = computed(() =>
  `${activeTenantId.value}|${instancePanelRegion.value}|${createListSignature(activeTenantData.value?.instances || [], (r: any) => r.instanceId)}`,
)
function instanceRecordKey(item: unknown, index: number) {
  return String((item as any)?.instanceId ?? index)
}
const instancePanelWidth = computed(() => (isMobile.value ? '100%' : 'clamp(960px, 68vw, 1280px)'))
const vcnManagerEditingOverlayActive = ref(false)
const storageManagerEditingOverlayActive = ref(false)
const byoipEditingOverlayActive = ref(false)
const instanceManagerEditingOverlayActive = ref(false)

function resolveFloatingTenantFromWorkspace() {
  if (floatingTenantCard.tenant) return floatingTenantCard.tenant
  if (tenantWorkspaceKind.value === 'instance') {
    return activeTenantData.value?.tenant || currentTenant.value || null
  }
  if (tenantWorkspaceKind.value === 'vcn') {
    return vcnTenant.value || currentTenant.value || null
  }
  if (tenantWorkspaceKind.value === 'storage') {
    const tenant = findTenantDataById(storageManagerUserId.value)?.tenant
    if (tenant) return tenant
    if (storageManagerUserId.value) {
      return {
        id: storageManagerUserId.value,
        username: storageManagerTenantName.value || '租户',
        tenantName: storageManagerTenantName.value || '',
        ociRegion: storageManagerDefaultRegion.value || '',
      }
    }
  }
  return null
}

function findTenantDataById(tenantId: string) {
  return tenantDataList.value.find((td) => td.tenant?.id === tenantId) || null
}

const instancePanelRegion = ref('')
const instanceRegionOptions = ref<{ label: string; value: string }[]>([])
const instanceSubscribedRegionsLoading = ref(false)

function panelRegionMemKey(prefix: string, tenant: any) {
  return `${prefix}:${tenant?.id || ''}`
}

function loadPanelRegionFromLs(prefix: string, tenant: any, fallback: string) {
  try {
    const v = localStorage.getItem(panelRegionMemKey(prefix, tenant)) || ''
    return v || fallback || ''
  } catch {
    return fallback || ''
  }
}

function savePanelRegionLs(prefix: string, tenant: any, region: string) {
  try {
    if (tenant?.id) localStorage.setItem(panelRegionMemKey(prefix, tenant), region || '')
  } catch {}
}

function instanceListRegion(td: TenantData) {
  return (instancePanelRegion.value?.trim() || td.tenant.ociRegion || '').trim()
}

function instanceListCacheKey(td: TenantData, region: string) {
  return ['instanceList', 'instances', td.tenant.id || '', region || ''] as const
}

function getInstanceListCache(td: TenantData, region: string) {
  const key = instanceListCacheKey(td, region)
  const rows = appQueryCache.get<any[]>(key)
  if (!rows) return null
  return {
    rows,
    fetchedAt: appQueryCache.getUpdatedAt(key),
  }
}

function detailOciRegion(): string | undefined {
  const r = currentInstance.value?.region
  return r && String(r).trim() ? String(r).trim() : undefined
}

function instanceDetailRegionParam(): { region?: string } {
  const r =
    (detailOciRegion() || '').trim() ||
    (currentTenant.value?.ociRegion && String(currentTenant.value.ociRegion).trim()) ||
    ''
  return r ? { region: r } : {}
}

function instanceDetailScopeParam(): { region?: string; compartmentId?: string } {
  const base = instanceDetailRegionParam()
  const compartmentId = currentInstance.value?.compartmentId
  const cid = compartmentId && String(compartmentId).trim() ? String(compartmentId).trim() : ''
  return cid ? { ...base, compartmentId: cid } : base
}

async function prefetchSubscribedRegions(
  userId: string,
  current: string,
  assign: (ids: string[]) => void,
  loadingRef: { value: boolean },
) {
  if (!userId) return
  loadingRef.value = true
  try {
    const res = await listStorageRegions({ id: userId })
    const raw = (res.data || []) as string[]
    const ids = [...new Set(raw)].sort()
    if (ids.length === 0) {
      assign(current ? [current] : [])
      return
    }
    if (current && !ids.includes(current)) ids.unshift(current)
    assign(ids)
  } catch {
    assign(current ? [current] : [])
  } finally {
    loadingRef.value = false
  }
}

async function selectTenant(td: TenantData, options: TenantWorkspaceOpenOptions = {}) {
  const tenantId = openInstanceWorkspace(td.tenant, options)
  const def = td.tenant.ociRegion || ''
  instancePanelRegion.value = loadPanelRegionFromLs('instancePanel.region', td.tenant, def) || def
  instanceRegionOptions.value = instancePanelRegion.value
    ? [{ label: instancePanelRegion.value, value: instancePanelRegion.value }]
    : []
  savePanelRegionLs('instancePanel.region', td.tenant, instancePanelRegion.value)
  await loadTenantInstances(td)
  if (activeTenantId.value !== tenantId) return
  await prefetchSubscribedRegions(
    tenantId,
    instancePanelRegion.value,
    (ids) => {
      if (activeTenantId.value !== tenantId) return
      instanceRegionOptions.value = ids.map((x) => ({ label: x, value: x }))
    },
    instanceSubscribedRegionsLoading,
  )
}

function refreshActiveTenantInstances() {
  const td = activeTenantData.value
  if (!td) return
  void loadTenantInstances(td, true)
}

function onInstancePanelRegionUserChange() {
  const td = activeTenantData.value
  if (!td?.tenant) return
  savePanelRegionLs('instancePanel.region', td.tenant, instancePanelRegion.value || '')
  loadTenantInstances(td)
}

const drawerVisible = ref(false)
const activeTab = ref('info')
const detailDrawerShellRef = ref<any>(null)
type DetailDrawerShellMethod =
  | 'resetAllPanels'
  | 'stopShapeSilently'
  | 'loadShapeOptions'
  | 'loadNetworkVcns'
  | 'loadBlockVolumes'
  | 'loadNetworkDetail'

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
const bootVolumeOverlayActive = ref(false)
const blockStorageOverlayActive = ref(false)
const trafficOverlayActive = ref(false)
const securityOverlayActive = ref(false)
const currentDetailRegion = computed(() => instanceDetailRegionParam().region)

const editInstanceVisible = ref(false)
const editInstanceLoading = ref(false)
const editInstanceForm = reactive({ displayName: '' })

function handleShapeEditInstanceUpdated(result?: Record<string, any>) {
  if (!result || !currentInstance.value || !currentTenant.value) return
  const inst = currentInstance.value
  if (result.shape) inst.shape = result.shape
  if (result.ocpus != null) inst.ocpus = result.ocpus
  if (result.memoryInGBs != null) inst.memoryInGBs = result.memoryInGBs
  if (result.name) inst.name = result.name
}

function scheduleCurrentTenantInstanceReload() {
  const tenantId = currentTenant.value?.id
  if (!tenantId) return
  const td = tenantDataList.value.find(t => t.tenant.id === tenantId)
  if (td) scheduleReload(() => loadTenantInstances(td, { force: true }), 3000)
}

const {
  showForceA2ToA1Button,
  forceA2ModalVisible,
  forceA2Loading,
  forceA2Q,
  forceA2AllYes,
  resetForceA2Modal,
  openForceA2ToA1Modal,
  handleForceA2ToA1Confirm,
} = useForceA2ToA1({
  getTenant: () => currentTenant.value,
  getInstance: () => currentInstance.value,
  resolveRegionParam: instanceDetailRegionParam,
  refreshShapeOptions: () => callDetailDrawerShell('loadShapeOptions'),
  reloadCurrentTenantInstances: scheduleCurrentTenantInstanceReload,
})

const {
  quickTaskVisible,
  quickTaskLoading,
  quickTaskTenant,
  quickTaskShapes,
  quickTaskShapesLoading,
  quickTaskForm,
  quickTaskPopupContainer,
  quickTaskBmLocked,
  quickTaskSavedRootPassword,
  quickTaskSavedSshPublicKey,
  quickTaskCredentialLoading,
  quickTaskShapeLimits,
  quickTaskOcpuLabel,
  quickTaskMemoryLabel,
  quickDenseIoTiers,
  quickDenseIoTierKey,
  formatDenseIoTierLabel,
  denseIoFlexTierKey,
  openQuickTask,
  warnQuickTaskCredentialMissing,
  updateQuickTaskOcpus,
  updateQuickTaskMemory,
  clampQuickTaskResources,
  snapQuickTaskBootVpus,
  handleQuickTask,
} = useQuickTask()

const {
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
} = useTenantWorkspaceDock({
  isMobile,
  overlayActive: {
    vcnManagerEditingOverlayActive,
    storageManagerEditingOverlayActive,
    byoipEditingOverlayActive,
    instanceManagerEditingOverlayActive,
    quickTaskVisible,
  },
  onFloatingAction: {
    quick: openQuickTask,
    instance: (tenant) => {
      const td = findTenantDataById(String(tenant?.id || ''))
      if (td) void selectTenant(td, { dockSwitch: true })
    },
    vcn: (tenant) => {
      void openVcnPanel(tenant, { dockSwitch: true })
    },
    storage: (tenant) => {
      openStoragePanel(tenant, { dockSwitch: true })
    },
  },
})

const {
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
} = useTenantWorkspacePanels({
  isMobile,
  hasActiveTenant: () => !!activeTenantData.value,
  tenantWorkspaceKind,
  tenantWorkspaceTransitioning,
  beginTenantWorkspace,
  scheduleTenantWorkspaceOpen,
  clearFloatingTenantCard,
  onVcnBeforeOpen: (tenant) => {
    currentTenant.value = tenant
    void nextTick(() => tenantVcnPanelRef.value?.loadPanel?.())
  },
})

const {
  consoleLoading,
  consoleData,
  clearConsoleData,
  handleCreateConsole,
  openConsoleWebSSH,
  handleDeleteConsole,
} = useInstanceConsole({
  getTenant: () => currentTenant.value,
  getInstance: () => currentInstance.value,
  resolveRegionParam: instanceDetailRegionParam,
})

const tenantVcnPanelRef = ref<any>(null)

const vcnManagerOpen = ref(false)
const vcnManagerUserId = ref('')
const vcnManagerVcn = ref<any>(null)
const vcnManagerOciRegion = ref('')
const vcnManagerInitialTab = ref<'subnet' | 'rt' | ''>('')
const vcnManagerTargetResourceId = ref('')
function handleVcnManagerEditingOverlayChange(active: boolean) {
  vcnManagerEditingOverlayActive.value = active
}
function handleTenantVcnEditingOverlayChange(active: boolean) {
  byoipEditingOverlayActive.value = active
}
function openVcnManager(tenantId: string, vcn: any, region?: string, options: { tab?: 'subnet' | 'rt'; resourceId?: string } = {}) {
  vcnManagerUserId.value = tenantId
  vcnManagerVcn.value = vcn
  vcnManagerInitialTab.value = options.tab || ''
  vcnManagerTargetResourceId.value = options.resourceId || ''
  const fromVcn = vcn?.region && String(vcn.region).trim()
  const fromPanel = region && String(region).trim()
  const fromInstance = currentInstance.value?.region && String(currentInstance.value.region).trim()
  vcnManagerOciRegion.value =
    fromVcn ||
    fromPanel ||
    fromInstance ||
    (currentTenant.value?.ociRegion && String(currentTenant.value.ociRegion).trim()) ||
    ''
  vcnManagerOpen.value = true
}

function openTenantVcnManager(payload: { tenantId: string; vcn: any; region?: string }) {
  openVcnManager(payload.tenantId, payload.vcn, payload.region)
}

function openDetailVcnManager(payload: any) {
  if (!currentTenant.value) return
  if (payload?.vcn) {
    openVcnManager(currentTenant.value.id, payload.vcn, payload.vcn?.region, {
      tab: payload.tab,
      resourceId: payload.resourceId,
    })
    return
  }
  openVcnManager(currentTenant.value.id, payload)
}

watch(vcnManagerOpen, open => {
  if (!open) {
    vcnManagerEditingOverlayActive.value = false
    vcnManagerInitialTab.value = ''
    vcnManagerTargetResourceId.value = ''
  }
})

async function onVcnManagerChanged() {
  if (vcnVisible.value && vcnTenant.value) {
    await tenantVcnPanelRef.value?.loadVcns?.(true)
  } else {
    void callDetailDrawerShell('loadNetworkVcns', [true])
  }
}

function handleStorageManagerEditingOverlayChange(active: boolean) {
  storageManagerEditingOverlayActive.value = active
}
watch(vcnVisible, (open) => {
  if (!open) byoipEditingOverlayActive.value = false
  if (!open) handleVcnWorkspaceClosed()
})
watch(storageManagerOpen, (open) => {
  if (!open) storageManagerEditingOverlayActive.value = false
  if (!open) handleStorageWorkspaceClosed()
})
function openStoragePanel(tenant: any, options: TenantWorkspaceOpenOptions = {}) {
  openStorageWorkspace(tenant, options)
}

function openVcnPanel(tenant: any, options: TenantWorkspaceOpenOptions = {}) {
  openVcnWorkspace(tenant, options)
}

function handleTenantVcnReservedIpChanged() {
  void callDetailDrawerShell('loadNetworkDetail')
}

async function loadAllTenants(force = false) {
  globalLoading.value = true
  try {
    await catalog.ensureTenants({ force })
    const records = catalog.tenants
    const existingMap = new Map(tenantDataList.value.map(td => [td.tenant.id, td]))
    tenantDataList.value = records.map((t: any) => {
      const existing = existingMap.get(t.id)
      return existing ? { ...existing, tenant: t } : { tenant: t, instances: [], loading: false, collapsed: false }
    })
  } catch (e: any) {
    message.error(e?.message || '加载租户失败')
  } finally {
    globalLoading.value = false
  }
}

async function loadTenantInstances(td: TenantData, options: LoadTenantInstancesOptions | boolean = {}) {
  const opts = typeof options === 'boolean'
    ? { force: options, notify: options }
    : options
  const force = opts.force === true
  const notify = opts.notify === true
  const reg = instanceListRegion(td)
  const cached = getInstanceListCache(td, reg)

  if (cached) {
    td.instances = cached.rows
    if (!force && Date.now() - cached.fetchedAt < INSTANCE_LIST_CACHE_TTL_MS) {
      return
    }
  }

  td.loading = true
  try {
    const rows = await appQueryCache.fetch(
      instanceListCacheKey(td, reg),
      async () => {
        const res = await getInstanceList({ id: td.tenant.id, region: reg, force })
        return res.data || []
      },
      { staleMs: INSTANCE_LIST_CACHE_TTL_MS, force },
    )

    const sameVisibleRegion = activeTenantId.value !== td.tenant.id || instanceListRegion(td) === reg
    if (!sameVisibleRegion) return

    td.instances = rows
    if (currentTenant.value?.id === td.tenant.id && currentInstance.value?.instanceId) {
      const fresh = rows.find((i: any) => i.instanceId === currentInstance.value.instanceId)
      if (fresh) currentInstance.value = { ...currentInstance.value, ...fresh }
    }
    if (notify) message.success('实例列表已刷新')
  } catch (e: any) {
    if (notify) {
      message.error(e?.message || '刷新实例列表失败')
    } else if (!cached) {
      td.instances = []
    }
  } finally {
    td.loading = false
  }
}

function onTabChange(key: string) {
  if (key === 'shape') {
    void nextTick(() => callDetailDrawerShell('loadShapeOptions'))
  }
}

function handleInstanceListOpenDetail(record: any) {
  if (!activeTenantData.value) return
  openDetail(activeTenantData.value.tenant, record)
}

function handleInstanceListMenuClick(payload: { record: any; key: string }) {
  onInstanceMenuClick(payload.record, payload.key)
}

function openDetail(tenant: any, record: any) {
  void callDetailDrawerShell('stopShapeSilently', [], 0)
  currentTenant.value = tenant
  currentInstance.value = record
  activeTab.value = 'info'
  void callDetailDrawerShell('resetAllPanels')
  bootVolumeOverlayActive.value = false
  blockStorageOverlayActive.value = false
  trafficOverlayActive.value = false
  securityOverlayActive.value = false
  clearConsoleData()
  drawerVisible.value = true
}

function openCurrentTerminateVerify() {
  if (!currentTenant.value || !currentInstance.value) return
  void openTerminateVerify(currentTenant.value, currentInstance.value)
}

function onBootVolumeUpdated() {
  if (activeTab.value === 'blockVolume') void callDetailDrawerShell('loadBlockVolumes')
}

const pendingTimers = new Set<any>()
function scheduleReload(fn: () => void, delay: number) {
  const t = setTimeout(() => {
    pendingTimers.delete(t)
    try { fn() } catch {}
  }, delay)
  pendingTimers.add(t)
}

const {
  verifyModalVisible,
  verifyCode,
  verifyLoading,
  verifySending,
  deleteBootVolume,
  openTerminateVerify,
  resendTerminateVerifyCode,
  handleTerminateWithCode,
} = useTerminateInstanceVerify({
  currentTenant,
  currentInstance,
  resolveRegionParam: instanceDetailRegionParam,
  onTerminated: (tenant) => {
    drawerVisible.value = false
    const td = tenantDataList.value.find(t => t.tenant.id === tenant.id)
    if (td) scheduleReload(() => loadTenantInstances(td, { force: true }), 3000)
  },
})
const instanceManagerConfirmOverlayActive = ref(false)
const {
  actionLoading,
  changeIpLoading,
  instanceInfoLoading,
  onInstanceMenuClick,
  handleCurrentInstanceAction,
  stopCurrentDetailInstance,
  handleChangeIp,
  refreshInstanceInfo,
} = useInstanceActions({
  getActiveTenantData: () => activeTenantData.value,
  getCurrentTenant: () => currentTenant.value,
  getCurrentInstance: () => currentInstance.value,
  setCurrentInstance: (instance) => {
    currentInstance.value = instance
  },
  findTenantDataById,
  getInstancePanelRegion: () => instancePanelRegion.value,
  resolveDetailRegionParam: instanceDetailRegionParam,
  resolveDetailScopeParam: instanceDetailScopeParam,
  scheduleReload,
  loadTenantInstances,
  loadNetworkDetail: () => callDetailDrawerShell('loadNetworkDetail'),
  openTerminateVerify,
  setConfirmOverlayActive: (active) => {
    instanceManagerConfirmOverlayActive.value = active
  },
})
const instanceManagerModalOverlayActive = computed(() =>
  trafficOverlayActive.value ||
  securityOverlayActive.value ||
  bootVolumeOverlayActive.value ||
  blockStorageOverlayActive.value ||
  editInstanceVisible.value ||
  forceA2ModalVisible.value ||
  verifyModalVisible.value ||
  instanceManagerConfirmOverlayActive.value,
)

watch(
  instanceManagerModalOverlayActive,
  (active) => {
    instanceManagerEditingOverlayActive.value = active
  },
  { immediate: true },
)

function openEditInstance() {
  if (!currentInstance.value) return
  editInstanceForm.displayName = currentInstance.value.name || ''
  editInstanceVisible.value = true
}

async function handleEditInstance() {
  if (!currentInstance.value || !currentTenant.value) return
  const displayName = editInstanceForm.displayName.trim()
  if (!displayName || displayName === currentInstance.value.name) {
    message.info('请输入新的实例名称')
    return
  }
  editInstanceLoading.value = true
  try {
    const res = await updateInstance({
      id: currentTenant.value.id,
      instanceId: currentInstance.value.instanceId,
      displayName,
      ...instanceDetailRegionParam(),
    })
    message.success('实例名称已更新')
    if (res.data?.name) currentInstance.value.name = res.data.name
    editInstanceVisible.value = false
    const td = tenantDataList.value.find(t => t.tenant.id === currentTenant.value.id)
    if (td) loadTenantInstances(td, { force: true })
  } catch (e: any) {
    message.error(e?.message || '修改实例失败')
  } finally {
    editInstanceLoading.value = false
  }
}

onMounted(() => {
  void loadGroups()
  void loadAllTenants()
  window.addEventListener('resize', checkMobile)
})
onActivated(() => {
  if (!instanceListActivatedOnce) {
    instanceListActivatedOnce = true
    return
  }
  void loadGroups()
  void loadAllTenants(false)
})
onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
  cleanupTenantWorkspaceDock()
  void callDetailDrawerShell('stopShapeSilently', [], 0)
  pendingTimers.forEach((t: any) => clearTimeout(t))
  pendingTimers.clear()
})
</script>

<style scoped>
:global(:root) {
  --tenant-free-tier-color: rgba(255, 255, 255, 0.94);
  --tenant-free-tier-bg: rgba(255, 255, 255, 0.1);
  --tenant-free-tier-border: rgba(255, 255, 255, 0.16);
  --tenant-free-tier-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
}
:global([data-theme="light"]) {
  --tenant-free-tier-color: rgba(15, 23, 42, 0.88);
  --tenant-free-tier-bg: rgba(15, 23, 42, 0.06);
  --tenant-free-tier-border: rgba(15, 23, 42, 0.12);
  --tenant-free-tier-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);
}
.instance-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  flex-wrap: wrap;
  gap: 12px;
}
.toolbar-left, .toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
:global(:root) {
  --tenant-workspace-mask-bg: rgba(2, 6, 23, 0.28);
  --tenant-floating-card-bg: rgba(30, 41, 59, 0.78);
  --tenant-floating-card-border: rgba(165, 180, 252, 0.42);
  --tenant-floating-card-shadow: 0 26px 60px rgba(0, 0, 0, 0.48), 0 0 34px rgba(129, 140, 248, 0.16);
  --tenant-floating-placeholder-bg: rgba(15, 23, 42, 0.16);
  --tenant-floating-placeholder-border: rgba(129, 140, 248, 0.22);
}
:global([data-theme="light"]) {
  --tenant-workspace-mask-bg: rgba(15, 23, 42, 0.22);
  --tenant-floating-card-bg: rgba(255, 255, 255, 0.94);
  --tenant-floating-card-border: rgba(99, 102, 241, 0.3);
  --tenant-floating-card-shadow: 0 24px 52px rgba(15, 23, 42, 0.18), 0 0 22px rgba(99, 102, 241, 0.12);
  --tenant-floating-placeholder-bg: rgba(255, 255, 255, 0.22);
  --tenant-floating-placeholder-border: rgba(99, 102, 241, 0.18);
}
.instance-drawer-title {
  display: flex;
  align-items: center;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-main);
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
}
.drawer-username {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.instance-manager-drawer :deep(.ant-drawer-header-title) {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}
.instance-manager-drawer :deep(.ant-drawer-extra) {
  flex-shrink: 0;
}
.panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.region-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-right: 4px;
}
.region-switch-label {
  color: var(--text-sub);
  font-size: 12px;
  white-space: nowrap;
}

@media (max-width: 768px) {
  .instance-toolbar { flex-direction: column; align-items: stretch; }
  .toolbar-left, .toolbar-right { width: 100%; flex-wrap: wrap; }
  .toolbar-left :deep(.ant-input-search) { width: 100% !important; flex: 1 1 100%; }
  .toolbar-right { justify-content: space-between; }
  .panel-actions {
    gap: 4px;
  }
  .panel-actions .region-switch {
    margin-right: 0;
  }
}

.instance-manager-drawer :deep(.ant-drawer-body) {
  scrollbar-width: thin;
  padding-top: 10px;
}
.instance-manager-drawer :deep(.ant-drawer-header) {
  padding: 12px 16px;
}
/* 移动端：抽屉头部名称省略 */
.drawer-username {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
  vertical-align: middle;
}

/* 移动端：抽屉内容区顶部的区域 meta */
.mobile-drawer-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px dashed var(--border);
}
.mobile-region-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 200px;
}

.tenant-page-float-actions {
  position: fixed;
  right: 20px;
  bottom: 24px;
  z-index: 100;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: none;
}
.tenant-page-float-actions > * {
  pointer-events: auto;
}
.float-action-btn {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.18);
}
</style>
