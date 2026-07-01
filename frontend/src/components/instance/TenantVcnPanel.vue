<template>
  <a-modal
    :keyboard="false"
    v-model:open="createRipVisible"
    title="新建预留 IP"
    @ok="handleCreateReservedIp"
    :confirm-loading="createRipLoading"
    :mask-closable="false"
  >
    <a-form layout="vertical">
      <a-form-item label="名称（可选）">
        <a-input v-model:value="createRipName" placeholder="reserved-ip" />
      </a-form-item>
      <div style="color: #999; font-size: 12px">创建一个未绑定的预留 IP。创建后可在列表中绑定到实例。</div>
    </a-form>
  </a-modal>

  <a-drawer
    :keyboard="false"
    v-model:open="drawerOpen"
    :title="'虚拟云网络 — ' + (tenant?.username || '')"
    :width="width"
    :mask-closable="false"
    :mask-style="maskStyle"
    destroy-on-close
    :wrap-class-name="wrapClassName"
  >
    <div v-if="tenant" class="vcn-panel-toolbar">
      <span class="instance-panel-toolbar-label">Region</span>
      <a-select
        v-model:value="vcnPanelRegion"
        class="instance-panel-region-select"
        :options="vcnRegionOptions"
        :loading="vcnSubscribedRegionsLoading"
        show-search
        option-filter-prop="label"
        placeholder="选择区域"
        @change="onVcnPanelRegionUserChange"
      />
      <span v-if="vcnSubscribedRegionsLoading" class="instance-panel-region-hint">正在同步订阅区域…</span>
    </div>

    <a-spin :spinning="vcnListLoading">
      <a-empty v-if="!vcnListLoading && vcnList.length === 0" description="无 VCN 数据" />
      <div v-else>
        <div v-for="vcn in vcnList" :key="vcn.id" class="vcn-item">
          <div class="vcn-item-header">
            <div style="display: flex; align-items: center; gap: 8px">
              <i class="ri-share-line" style="font-size: 18px; color: var(--primary)"></i>
              <span style="font-weight: 700">{{ vcn.displayName }}</span>
            </div>
            <a-space>
              <a-tag color="purple">{{ vcn.compartmentName }}</a-tag>
              <a-button size="small" type="primary" @click="emitOpenVcnManager(vcn)">管理</a-button>
            </a-space>
          </div>
          <div class="vcn-item-body">
            <div class="vcn-info-row">
              <span class="info-label">CIDR</span>
              <span>{{ (vcn.cidrBlocks || []).join(', ') }}</span>
            </div>
            <div class="vcn-info-row">
              <span class="info-label">状态</span>
              <a-badge :status="vcn.state === 'AVAILABLE' ? 'success' : 'default'" :text="vcn.state" />
            </div>
          </div>
          <div v-if="vcn.subnets && vcn.subnets.length > 0" style="margin-top: 8px">
            <div style="font-size: 12px; color: var(--text-sub); margin-bottom: 4px">子网：</div>
            <div v-for="sub in vcn.subnets" :key="sub.id" class="vcn-subnet-row">
              <span>{{ sub.displayName }}</span>
              <a-tag size="small">{{ sub.cidrBlock }}</a-tag>
              <a-tag :color="sub.isPublic ? 'green' : 'default'" size="small">{{ sub.isPublic ? '公有' : '私有' }}</a-tag>
            </div>
          </div>
        </div>

        <div class="vcn-item">
          <div class="vcn-item-header">
            <div style="display: flex; align-items: center; gap: 8px">
              <i class="ri-map-pin-line" style="font-size: 18px; color: var(--primary)"></i>
              <span style="font-weight: 700">预留 IP</span>
            </div>
            <a-space>
              <a-button size="small" :loading="reservedIpListLoading" @click="loadReservedIps(true)">刷新</a-button>
              <a-button size="small" type="primary" @click="showCreateReservedIpModal">新建</a-button>
            </a-space>
          </div>
          <a-spin :spinning="reservedIpListLoading">
            <a-empty v-if="!reservedIpListLoading && ociReservedIps.length === 0" description="暂无预留 IP" />
            <div v-for="rip in ociReservedIps" :key="rip.id" class="vcn-ip-row">
              <a-typography-text copyable>{{ rip.ipAddress }}</a-typography-text>
              <a-tag :color="rip.isAssigned ? 'green' : 'default'">{{ rip.isAssigned ? '已绑定' : '未绑定' }}</a-tag>
              <span v-if="rip.assignedInstanceName" class="vcn-ip-meta">{{ rip.assignedInstanceName }}</span>
              <a-popconfirm v-if="!rip.isAssigned" title="确定删除？" @confirm="handleDeleteReservedIp(rip.id)">
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
              <a-button v-if="rip.isAssigned" type="link" size="small" @click="handleUnassignReservedIp(rip.id)">解绑</a-button>
            </div>
          </a-spin>
          <p class="vcn-panel-hint">OCI 分配的预留公网 IP，可绑定到实例。</p>
        </div>

        <ByoipPanel
          v-if="tenant"
          :user-id="tenant.id"
          :region="vcnPanelRegion"
          @changed="loadReservedIps(true)"
          @editing-overlay-change="handleByoipEditingOverlayChange"
        />
      </div>
    </a-spin>
  </a-drawer>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  createReservedIp,
  deleteReservedIp,
  getVcns,
  listReservedIps,
  unassignReservedIp,
} from '../../api/instance'
import { listStorageRegions } from '../../api/storage'

const ByoipPanel = defineAsyncComponent(() => import('../../views/ByoipPanel.vue'))

const props = defineProps<{
  open: boolean
  tenant: any | null
  width: string | number
  maskStyle?: Record<string, any>
  wrapClassName?: string
}>()

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void
  (e: 'open-vcn-manager', payload: { tenantId: string; vcn: any; region?: string }): void
  (e: 'editing-overlay-change', active: boolean): void
  (e: 'reserved-ip-changed'): void
}>()

const drawerOpen = computed({
  get: () => props.open,
  set: (val: boolean) => emit('update:open', val),
})

const vcnPanelRegion = ref('')
const vcnRegionOptions = ref<{ label: string; value: string }[]>([])
const vcnSubscribedRegionsLoading = ref(false)
const vcnListLoading = ref(false)
const vcnList = ref<any[]>([])
const reservedIps = ref<any[]>([])
const reservedIpListLoading = ref(false)
const createRipVisible = ref(false)
const createRipLoading = ref(false)
const createRipName = ref('')
const byoipOverlayActive = ref(false)
let vcnLoadSeq = 0
let reservedIpLoadSeq = 0
let regionLoadSeq = 0
let vcnLoadedTargetKey = ''

const ociReservedIps = computed(() => reservedIps.value.filter((r: any) => !r.publicIpPoolId))

function panelRegionMemKey(tenant: any) {
  return `vcnPanel.region:${tenant?.id || ''}`
}

function loadPanelRegionFromLs(tenant: any, fallback: string) {
  try {
    const v = localStorage.getItem(panelRegionMemKey(tenant)) || ''
    return v || fallback || ''
  } catch {
    // 浏览器禁用 localStorage 时退回租户默认区域。
    return fallback || ''
  }
}

function savePanelRegionLs(tenant: any, region: string) {
  try {
    if (tenant?.id) localStorage.setItem(panelRegionMemKey(tenant), region || '')
  } catch {
    // 区域记忆失败不影响当前面板使用。
  }
}

function regionParam(): { region?: string } {
  const r = (vcnPanelRegion.value?.trim() || props.tenant?.ociRegion || '').trim()
  return r ? { region: r } : {}
}

function ensureRegionOption(region: string) {
  const current = region?.trim()
  if (!current) return
  if (vcnRegionOptions.value.some((item) => item.value === current)) return
  vcnRegionOptions.value = [{ label: current, value: current }, ...vcnRegionOptions.value]
}

function currentTargetKey() {
  const scope = regionParam()
  return [
    props.tenant?.id || '',
    scope.region || '',
  ].join('|')
}

function sameTarget(targetKey: string) {
  return currentTargetKey() === targetKey
}

function resetPanelState() {
  vcnLoadSeq += 1
  reservedIpLoadSeq += 1
  regionLoadSeq += 1
  vcnListLoading.value = false
  reservedIpListLoading.value = false
  vcnSubscribedRegionsLoading.value = false
  vcnLoadedTargetKey = ''
  vcnList.value = []
  reservedIps.value = []
  vcnRegionOptions.value = []
  vcnPanelRegion.value = ''
  createRipVisible.value = false
  createRipLoading.value = false
  createRipName.value = ''
  byoipOverlayActive.value = false
  emitEditingOverlay()
}

function ensureRegion() {
  const tenant = props.tenant
  if (!tenant) return ''
  const def = tenant.ociRegion || ''
  if (!vcnPanelRegion.value) {
    vcnPanelRegion.value = loadPanelRegionFromLs(tenant, def) || def
  }
  ensureRegionOption(vcnPanelRegion.value)
  savePanelRegionLs(tenant, vcnPanelRegion.value)
  return vcnPanelRegion.value
}

async function prefetchSubscribedRegions() {
  const tenant = props.tenant
  if (!tenant?.id) return
  const requestId = ++regionLoadSeq
  const tenantId = tenant.id
  const current = vcnPanelRegion.value
  vcnSubscribedRegionsLoading.value = true
  const sameTenant = () => props.tenant?.id === tenantId
  try {
    const res = await listStorageRegions({ id: tenantId })
    if (requestId !== regionLoadSeq || !sameTenant()) return
    const raw = (res.data || []) as string[]
    const ids = [...new Set(raw)].sort()
    const selected = vcnPanelRegion.value || current
    if (ids.length === 0) {
      vcnRegionOptions.value = selected ? [{ label: selected, value: selected }] : []
      return
    }
    if (selected && !ids.includes(selected)) ids.unshift(selected)
    vcnRegionOptions.value = ids.map((x) => ({ label: x, value: x }))
  } catch {
    if (requestId === regionLoadSeq && sameTenant()) {
      const selected = vcnPanelRegion.value || current
      vcnRegionOptions.value = selected ? [{ label: selected, value: selected }] : []
    }
  } finally {
    if (requestId === regionLoadSeq && sameTenant()) vcnSubscribedRegionsLoading.value = false
  }
}

async function loadVcns(force = false) {
  if (!props.tenant) return
  if (vcnListLoading.value && !force) return
  ensureRegion()
  const requestId = ++vcnLoadSeq
  const tenantId = props.tenant.id
  const targetKey = currentTargetKey()
  vcnListLoading.value = true
  try {
    const res = await getVcns({
      id: tenantId,
      ...regionParam(),
      force,
    })
    if (requestId !== vcnLoadSeq || !sameTarget(targetKey)) return
    vcnList.value = res.data || []
    vcnLoadedTargetKey = targetKey
  } catch (e: any) {
    if (requestId === vcnLoadSeq && sameTarget(targetKey)) {
      vcnLoadedTargetKey = ''
      message.error(e?.message || '加载 VCN 失败')
    }
  } finally {
    if (requestId === vcnLoadSeq && sameTarget(targetKey)) vcnListLoading.value = false
  }
}

async function loadReservedIps(force = false) {
  if (!props.tenant) return
  if (reservedIpListLoading.value && !force) return
  ensureRegion()
  const requestId = ++reservedIpLoadSeq
  const tenantId = props.tenant.id
  const targetKey = currentTargetKey()
  reservedIpListLoading.value = true
  try {
    const res = await listReservedIps({
      id: tenantId,
      ...regionParam(),
    })
    if (requestId !== reservedIpLoadSeq || !sameTarget(targetKey)) return
    reservedIps.value = res.data || []
  } catch (e: any) {
    if (requestId === reservedIpLoadSeq && sameTarget(targetKey)) message.error(e?.message || '加载预留 IP 失败')
  } finally {
    if (requestId === reservedIpLoadSeq && sameTarget(targetKey)) reservedIpListLoading.value = false
  }
}

async function loadPanel(force = false) {
  if (!props.tenant) return
  ensureRegion()
  void prefetchSubscribedRegions()
  void loadReservedIps(force)
  await loadVcns(force)
}

async function onVcnPanelRegionUserChange() {
  if (!props.tenant) return
  savePanelRegionLs(props.tenant, vcnPanelRegion.value || '')
  vcnLoadedTargetKey = ''
  vcnList.value = []
  reservedIps.value = []
  void loadReservedIps(true)
  await loadVcns(true)
}

function emitOpenVcnManager(vcn: any) {
  if (!props.tenant?.id) return
  emit('open-vcn-manager', {
    tenantId: props.tenant.id,
    vcn,
    region: vcnPanelRegion.value || props.tenant.ociRegion || '',
  })
}

function showCreateReservedIpModal() {
  createRipName.value = ''
  createRipVisible.value = true
}

async function handleCreateReservedIp() {
  if (!props.tenant) return
  const targetKey = currentTargetKey()
  createRipLoading.value = true
  try {
    const res = await createReservedIp({
      id: props.tenant.id,
      displayName: createRipName.value || undefined,
      ...regionParam(),
    })
    if (!sameTarget(targetKey)) return
    message.success('预留IP已创建: ' + (res.data?.ipAddress || ''))
    createRipVisible.value = false
    void loadReservedIps(true)
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '创建预留IP失败')
  } finally {
    if (sameTarget(targetKey)) createRipLoading.value = false
  }
}

async function handleDeleteReservedIp(publicIpId: string) {
  if (!props.tenant) return
  const targetKey = currentTargetKey()
  try {
    await deleteReservedIp({
      id: props.tenant.id,
      publicIpId,
      ...regionParam(),
    })
    if (!sameTarget(targetKey)) return
    message.success('预留IP已删除')
    void loadReservedIps(true)
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '删除预留IP失败')
  }
}

async function handleUnassignReservedIp(publicIpId: string) {
  if (!props.tenant) return
  const targetKey = currentTargetKey()
  try {
    await unassignReservedIp({
      id: props.tenant.id,
      publicIpId,
      ...regionParam(),
    })
    if (!sameTarget(targetKey)) return
    message.success('预留IP已解绑')
    void loadReservedIps(true)
    emit('reserved-ip-changed')
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '解绑失败')
  }
}

function handleByoipEditingOverlayChange(active: boolean) {
  byoipOverlayActive.value = active
  emitEditingOverlay()
}

function emitEditingOverlay() {
  emit('editing-overlay-change', createRipVisible.value || byoipOverlayActive.value)
}

watch(createRipVisible, emitEditingOverlay)

watch(
  () => props.tenant?.id,
  () => {
    resetPanelState()
    if (props.open) void loadPanel()
  },
)

watch(
  () => props.open,
  (open) => {
    if (!open) {
      createRipVisible.value = false
      byoipOverlayActive.value = false
      emitEditingOverlay()
      return
    }
    if (vcnLoadedTargetKey !== currentTargetKey() && !vcnListLoading.value) void loadPanel()
  },
  { immediate: true },
)

defineExpose({
  loadPanel,
  loadVcns,
  loadReservedIps,
  reset: resetPanelState,
})
</script>

<style scoped>
.vcn-panel-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin: 0 0 14px;
  padding: 10px 12px;
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: 10px;
}
.instance-panel-toolbar-label {
  color: var(--text-sub);
  font-size: 12px;
  white-space: nowrap;
}
.instance-panel-region-select {
  min-width: 200px;
  flex: 1 1 220px;
  max-width: 100%;
}
.instance-panel-region-hint {
  font-size: 12px;
  color: var(--text-sub);
  line-height: 1.3;
}
.info-label {
  color: var(--text-sub);
  flex-shrink: 0;
}
.vcn-item {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 12px;
}
.vcn-item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.vcn-item-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}
.vcn-info-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.vcn-subnet-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  font-size: 12px;
}
.vcn-ip-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 6px 0;
  font-size: 13px;
}
.vcn-ip-meta {
  color: var(--text-sub);
  font-size: 12px;
}
.vcn-panel-hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--text-sub);
  line-height: 1.5;
}
@media (min-width: 769px) {
  .instance-panel-region-hint {
    flex: 0 0 auto;
    margin-left: auto;
  }
}
@media (max-width: 768px) {
  .vcn-panel-toolbar {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
  }
  .instance-panel-region-select {
    width: 100% !important;
    min-width: 0;
    flex: none;
    max-width: none;
  }
  .instance-panel-region-hint {
    margin-left: 0;
    width: 100%;
  }
}
</style>
