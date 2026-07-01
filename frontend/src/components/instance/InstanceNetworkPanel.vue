<template>
  <div class="instance-network-panel">
    <div class="network-toolbar">
      <a-button size="small" @click="loadInstanceNetwork(true)" :loading="networkLoading">
        刷新网络
      </a-button>
    </div>

    <a-spin :spinning="networkLoading">
      <a-empty v-if="!networkLoading && vnics.length === 0" description="暂无网络信息" />

      <template v-else>
        <section v-if="primaryVnic" class="vnic-section">
          <h3 class="vnic-title">Primary VNIC</h3>
          <div class="primary-grid">
            <div class="network-card">
              <h4 class="network-card-title">VNIC 信息</h4>
              <div class="network-rows">
                <NetworkRow label="VNIC 名称">{{ primaryVnic.displayName || '—' }}</NetworkRow>
                <NetworkRow label="状态">
                  <a-badge :status="primaryVnic.lifecycleState === 'AVAILABLE' ? 'success' : 'default'" :text="primaryVnic.lifecycleState || '—'" />
                </NetworkRow>
                <NetworkRow label="VCN">
                  <ResourceLink v-if="primaryVnic.vcn?.id || primaryVnic.vcnId" :text="vcnLabel(primaryVnic)" @click="openVcn(primaryVnic)" />
                  <span v-else class="muted">—</span>
                </NetworkRow>
                <NetworkRow label="子网">
                  <ResourceLink v-if="primaryVnic.subnetId" :text="subnetLabel(primaryVnic)" @click="openSubnet(primaryVnic)" />
                  <span v-else class="muted">—</span>
                </NetworkRow>
                <NetworkRow label="跳过源/目的地检查">{{ yesNo(primaryVnic.skipSourceDestCheck) }}</NetworkRow>
                <NetworkRow label="MAC 地址">{{ primaryVnic.macAddress || '—' }}</NetworkRow>
                <NetworkRow label="VLAN 标记">{{ primaryVnic.vlanId || '—' }}</NetworkRow>
                <NetworkRow label="Private DNS 记录">{{ primaryVnic.hostnameLabel ? '启用' : '禁用' }}</NetworkRow>
                <NetworkRow label="Hostname">{{ primaryVnic.hostnameLabel || '—' }}</NetworkRow>
                <NetworkRow label="区间">
                  <span>{{ props.instance?.compartmentName || shortOcid(primaryVnic.compartmentId) || '—' }}</span>
                </NetworkRow>
                <NetworkRow label="OCID">
                  <CopyText v-if="primaryVnic.vnicId" :text="primaryVnic.vnicId" />
                  <span v-else class="muted">—</span>
                </NetworkRow>
                <NetworkRow label="创建时间">{{ formatUtc(primaryVnic.timeCreated) }}</NetworkRow>
                <NetworkRow label="路由表">
                  <ResourceLink v-if="primaryVnic.routeTableId" :text="routeTableLabel(primaryVnic)" @click="openRouteTable(primaryVnic)" />
                  <span v-else class="muted">—</span>
                </NetworkRow>
              </div>
            </div>

            <div class="network-card">
              <h4 class="network-card-title">主要 IP 信息</h4>
              <div class="network-rows">
                <NetworkRow label="专用 IP 地址">
                  <CopyText v-if="primaryIpAddress(primaryVnic)" :text="primaryIpAddress(primaryVnic)" />
                  <span v-else class="muted">—</span>
                </NetworkRow>
                <NetworkRow label="专用 IP OCID">
                  <CopyText v-if="primaryIp(primaryVnic)?.privateIpId" :text="primaryIp(primaryVnic).privateIpId" />
                  <span v-else class="muted">—</span>
                </NetworkRow>
                <NetworkRow label="已分配">{{ formatUtc(primaryIp(primaryVnic)?.timeCreated) }}</NetworkRow>
                <NetworkRow label="路由表">
                  <ResourceLink v-if="primaryIpRouteTableId(primaryVnic)" :text="primaryIpRouteTableLabel(primaryVnic)" @click="openPrimaryIpRouteTable(primaryVnic)" />
                  <span v-else class="muted">—</span>
                </NetworkRow>
                <NetworkRow label="网络安全组">
                  <template v-if="nsgIds(primaryVnic).length">
                    <a-tag v-for="nsgId in nsgIds(primaryVnic)" :key="nsgId" class="nsg-tag">
                      <CopyText :text="nsgId" />
                    </a-tag>
                  </template>
                  <span v-else class="muted">—</span>
                </NetworkRow>
                <NetworkRow label="全限定域名">{{ primaryIp(primaryVnic)?.fqdn || primaryVnic.internalFqdn || '—' }}</NetworkRow>
                <NetworkRow label="公共 IP 地址">
                  <template v-if="publicIpv4(primaryVnic)">
                    <CopyText :text="publicIpv4(primaryVnic)" />
                    <a-tag :color="publicIpTagColor(primaryVnic)" class="ip-tag">{{ publicIpLifetimeLabel(primaryVnic) }}</a-tag>
                  </template>
                  <span v-else class="muted">—</span>
                </NetworkRow>
                <NetworkRow label="公共 IP OCID">
                  <CopyText v-if="primaryIp(primaryVnic)?.publicIpId" :text="primaryIp(primaryVnic).publicIpId" />
                  <span v-else class="muted">—</span>
                </NetworkRow>
              </div>
            </div>
          </div>
        </section>

        <section v-if="attachedVnics.length" class="vnic-section attached-section">
          <h3 class="vnic-title">附加的 VNIC</h3>
          <a-table
            v-if="!isMobile"
            :data-source="attachedVnics"
            :columns="attachedColumns"
            :pagination="false"
            size="small"
            row-key="vnicId"
            :scroll="{ x: 820 }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'displayName'">
                <div class="cell-title">{{ record.displayName || '—' }}</div>
                <CopyText v-if="record.vnicId" :text="record.vnicId" />
              </template>
              <template v-else-if="column.key === 'network'">
                <div class="stacked-cell">
                  <div>
                    <span class="stacked-label">VCN</span>
                    <ResourceLink v-if="record.vcn?.id || record.vcnId" :text="vcnLabel(record)" @click="openVcn(record)" />
                    <span v-else class="muted">—</span>
                  </div>
                  <div>
                    <span class="stacked-label">子网</span>
                    <ResourceLink v-if="record.subnetId" :text="subnetLabel(record)" @click="openSubnet(record)" />
                    <span v-else class="muted">—</span>
                  </div>
                </div>
              </template>
              <template v-else-if="column.key === 'ip'">
                <div class="stacked-cell">
                  <div>
                    <span class="stacked-label">专用</span>
                    <CopyText v-if="primaryIpAddress(record)" :text="primaryIpAddress(record)" />
                    <span v-else class="muted">—</span>
                  </div>
                  <div>
                    <span class="stacked-label">公共</span>
                    <CopyText v-if="publicIpv4(record)" :text="publicIpv4(record)" />
                    <span v-else class="muted">—</span>
                  </div>
                </div>
              </template>
              <template v-else-if="column.key === 'routeTable'">
                <ResourceLink v-if="record.routeTableId" :text="routeTableLabel(record)" @click="openRouteTable(record)" />
                <span v-else class="muted">—</span>
              </template>
              <template v-else-if="column.key === 'state'">
                <a-badge :status="record.lifecycleState === 'AVAILABLE' ? 'success' : 'default'" :text="record.lifecycleState || '—'" />
              </template>
              <template v-else-if="column.key === 'action'">
                <a-space size="small">
                  <a-button size="small" @click="openSubnet(record)" :disabled="!record.subnetId">子网</a-button>
                  <a-button size="small" @click="openRouteTable(record)" :disabled="!record.routeTableId">路由表</a-button>
                </a-space>
              </template>
            </template>
          </a-table>

          <template v-else>
            <div v-for="vnic in attachedVnics" :key="vnic.vnicId || vnic.subnetId" class="mobile-card">
              <div class="mobile-card-header">
                <span class="mobile-card-title">{{ vnic.displayName || 'VNIC' }}</span>
                <a-badge :status="vnic.lifecycleState === 'AVAILABLE' ? 'success' : 'default'" :text="vnic.lifecycleState || '—'" />
              </div>
              <div class="mobile-card-body">
                <div class="mobile-card-row"><span class="label">专用 IP</span><span class="value">{{ primaryIpAddress(vnic) || '—' }}</span></div>
                <div class="mobile-card-row"><span class="label">公共 IP</span><span class="value">{{ publicIpv4(vnic) || '—' }}</span></div>
                <div class="mobile-card-row">
                  <span class="label">VCN</span>
                  <ResourceLink v-if="vnic.vcn?.id || vnic.vcnId" :text="vcnLabel(vnic)" @click="openVcn(vnic)" />
                  <span v-else class="value">—</span>
                </div>
                <div class="mobile-card-row">
                  <span class="label">子网</span>
                  <ResourceLink v-if="vnic.subnetId" :text="subnetLabel(vnic)" @click="openSubnet(vnic)" />
                  <span v-else class="value">—</span>
                </div>
                <div class="mobile-card-row">
                  <span class="label">路由表</span>
                  <ResourceLink v-if="vnic.routeTableId" :text="routeTableLabel(vnic)" @click="openRouteTable(vnic)" />
                  <span v-else class="value">—</span>
                </div>
              </div>
            </div>
          </template>
        </section>
      </template>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, ref, resolveComponent, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getInstanceNetworkDetail } from '../../api/instance'

const NetworkRow = defineComponent({
  props: {
    label: { type: String, required: true },
  },
  setup(props, { slots }) {
    return () => h('div', { class: 'network-row' }, [
      h('div', { class: 'network-label' }, props.label),
      h('div', { class: 'network-value' }, slots.default?.()),
    ])
  },
})

const ResourceLink = defineComponent({
  props: {
    text: { type: String, required: true },
  },
  emits: ['click'],
  setup(props, { emit }) {
    return () => h('button', { class: 'resource-link', type: 'button', onClick: () => emit('click') }, props.text)
  },
})

const CopyText = defineComponent({
  props: {
    text: { type: String, required: true },
  },
  setup(props) {
    const TypographyText = resolveComponent('a-typography-text')
    return () => h(TypographyText, { copyable: { text: props.text }, class: 'copy-text' }, () => shortOcid(props.text))
  },
})

const props = defineProps<{
  tenant: any | null
  instance: any | null
  isMobile: boolean
  active: boolean
  region?: string
  compartmentId?: string
}>()

const emit = defineEmits<{
  (e: 'open-vcn-manager', payload: { vcn: any; tab?: 'subnet' | 'rt'; resourceId?: string }): void
}>()

const attachedColumns = [
  { title: '名称', key: 'displayName', width: 170 },
  { title: '网络', key: 'network', width: 220 },
  { title: 'IP', key: 'ip', width: 190 },
  { title: '路由表', key: 'routeTable', width: 170 },
  { title: '状态', key: 'state', width: 120 },
  { title: '操作', key: 'action', width: 140 },
]

const networkLoading = ref(false)
const networkDetail = ref<any>(null)
let networkLoadSeq = 0

const vnics = computed(() => {
  const list = networkDetail.value?.vnics
  return Array.isArray(list) ? list : []
})

const primaryVnic = computed(() => vnics.value.find((vnic: any) => vnic.isPrimary === true) || vnics.value[0] || null)
const attachedVnics = computed(() => {
  const primaryKey = primaryVnic.value?.vnicId
  return vnics.value.filter((vnic: any) => vnic !== primaryVnic.value && (!primaryKey || vnic.vnicId !== primaryKey))
})

function regionParam(): { region?: string } {
  const r =
    (props.region && String(props.region).trim()) ||
    (props.instance?.region && String(props.instance.region).trim()) ||
    (props.tenant?.ociRegion && String(props.tenant.ociRegion).trim()) ||
    ''
  return r ? { region: r } : {}
}

function scopeParam(): { region?: string; compartmentId?: string } {
  const base = regionParam()
  const cid =
    (props.compartmentId && String(props.compartmentId).trim()) ||
    (props.instance?.compartmentId && String(props.instance.compartmentId).trim()) ||
    ''
  return cid ? { ...base, compartmentId: cid } : base
}

function currentTargetKey() {
  const scope = scopeParam()
  return [
    props.tenant?.id || '',
    props.instance?.instanceId || '',
    scope.region || '',
    scope.compartmentId || '',
  ].join('|')
}

function sameTarget(targetKey: string) {
  return currentTargetKey() === targetKey
}

function reset() {
  networkLoadSeq += 1
  networkLoading.value = false
  networkDetail.value = null
}

async function loadInstanceNetwork(force = false) {
  if (!props.tenant || !props.instance) return
  const requestId = ++networkLoadSeq
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const targetKey = currentTargetKey()
  const scope = scopeParam()
  networkLoading.value = true
  try {
    const res = await getInstanceNetworkDetail({
      id: tenantId,
      instanceId,
      ...scope,
      force,
    })
    if (requestId !== networkLoadSeq || !sameTarget(targetKey)) return
    networkDetail.value = res.data || { vnics: [] }
  } catch (e: any) {
    if (requestId === networkLoadSeq && sameTarget(targetKey)) message.error(e?.message || '加载实例网络失败')
  } finally {
    if (requestId === networkLoadSeq && sameTarget(targetKey)) networkLoading.value = false
  }
}

function primaryIp(vnic: any) {
  const ips = Array.isArray(vnic?.ipDetails) ? vnic.ipDetails : []
  return ips.find((ip: any) => ip?.isPrimary) || ips[0] || null
}

function publicIpv4(vnic: any) {
  return primaryIp(vnic)?.publicIpAddress || vnic?.publicIp || ''
}

function primaryIpAddress(vnic: any) {
  return primaryIp(vnic)?.privateIpAddress || vnic?.privateIp || ''
}

function primaryIpRouteTableId(vnic: any) {
  return primaryIp(vnic)?.routeTableId || vnic?.routeTableId || ''
}

function primaryIpRouteTableLabel(vnic: any) {
  const ip = primaryIp(vnic)
  return ip?.routeTable?.displayName || ip?.routeTableDisplayName || routeTableLabel(vnic)
}

function vcnLabel(vnic: any) {
  return vnic?.vcn?.displayName || vnic?.vcnDisplayName || vnic?.vcnId || '—'
}

function subnetLabel(vnic: any) {
  return vnic?.subnet?.displayName || vnic?.subnetDisplayName || vnic?.subnetId || '—'
}

function routeTableLabel(vnic: any) {
  return vnic?.routeTable?.displayName || vnic?.routeTableDisplayName || vnic?.routeTableId || '—'
}

function nsgIds(vnic: any) {
  return Array.isArray(vnic?.nsgIds) ? vnic.nsgIds.filter(Boolean) : []
}

function yesNo(value: any) {
  if (value === true) return '是'
  if (value === false) return '否'
  return '—'
}

function publicIpLifetimeLabel(vnic: any) {
  const lifetime = primaryIp(vnic)?.publicIpLifetime
  if (lifetime === 'RESERVED') return '预留'
  if (lifetime === 'EPHEMERAL') return '临时'
  return lifetime || '—'
}

function publicIpTagColor(vnic: any) {
  return primaryIp(vnic)?.publicIpLifetime === 'RESERVED' ? 'green' : 'orange'
}

function shortOcid(value: string) {
  if (!value || value.length <= 28) return value
  return `${value.slice(0, 12)}...${value.slice(-10)}`
}

function formatUtc(value: string | undefined) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())} UTC`
}

function vcnPayload(vnic: any) {
  const vcn = vnic?.vcn || {}
  const id = vcn.id || vnic?.vcnId
  if (!id) return null
  return {
    ...vcn,
    id,
    displayName: vcn.displayName || vnic?.vcnDisplayName || id,
    cidrBlock: vcn.cidrBlock || vnic?.vcnCidrBlock,
    lifecycleState: vcn.lifecycleState || vnic?.vcnLifecycleState,
    region: vcn.region || scopeParam().region,
  }
}

function openVcn(vnic: any) {
  const vcn = vcnPayload(vnic)
  if (!vcn) return
  emit('open-vcn-manager', { vcn, tab: 'subnet' })
}

function openSubnet(vnic: any) {
  const vcn = vcnPayload(vnic)
  if (!vcn || !vnic?.subnetId) return
  emit('open-vcn-manager', { vcn, tab: 'subnet', resourceId: vnic.subnetId })
}

function openRouteTable(vnic: any) {
  const vcn = vcnPayload(vnic)
  if (!vcn || !vnic?.routeTableId) return
  emit('open-vcn-manager', { vcn, tab: 'rt', resourceId: vnic.routeTableId })
}

function openPrimaryIpRouteTable(vnic: any) {
  const vcn = vcnPayload(vnic)
  const routeTableId = primaryIpRouteTableId(vnic)
  if (!vcn || !routeTableId) return
  emit('open-vcn-manager', { vcn, tab: 'rt', resourceId: routeTableId })
}

watch(
  () => [props.tenant?.id, props.instance?.instanceId, props.region, props.compartmentId],
  () => {
    reset()
    if (props.active) void loadInstanceNetwork()
  },
)

watch(
  () => props.active,
  (active) => {
    if (active && !networkDetail.value) void loadInstanceNetwork()
  },
  { immediate: true },
)

defineExpose({
  loadInstanceNetwork,
  loadVcns: loadInstanceNetwork,
  reset,
})
</script>

<style scoped>
.instance-network-panel {
  color: var(--text-main);
}
.network-toolbar {
  margin-bottom: 12px;
}
.vnic-section {
  margin-bottom: 22px;
}
.vnic-title {
  margin: 0 0 14px;
  color: var(--text-main);
  font-size: 20px;
  font-weight: 700;
  line-height: 1.25;
}
.primary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
}
.network-card-title {
  margin: 0 0 10px;
  color: var(--text-main);
  font-size: 16px;
  font-weight: 700;
}
.network-rows {
  border-top: 1px solid var(--border);
}
.network-row {
  display: grid;
  grid-template-columns: minmax(118px, 180px) minmax(0, 1fr);
  gap: 14px;
  min-height: 48px;
  padding: 11px 0;
  border-bottom: 1px solid var(--border);
}
.network-label {
  color: var(--text-main);
  font-size: 13px;
  font-weight: 600;
  line-height: 24px;
}
.network-value {
  min-width: 0;
  color: var(--text-main);
  font-size: 13px;
  line-height: 24px;
  overflow-wrap: anywhere;
}
.resource-link {
  display: inline;
  max-width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: #1677ff;
  line-height: 24px;
  text-align: left;
  cursor: pointer;
  overflow-wrap: anywhere;
}
:global([data-theme='dark']) .resource-link {
  color: #8ea2ff;
}
.resource-link:hover {
  text-decoration: underline;
}
.muted {
  color: var(--text-sub);
}
.nsg-tag,
.ip-tag {
  margin-left: 6px;
  margin-bottom: 4px;
}
.copy-text {
  display: inline-flex;
  max-width: 100%;
}
.copy-text-value {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: top;
}
.cell-title {
  color: var(--text-main);
  font-weight: 600;
}
.attached-section {
  margin-top: 24px;
}
.mobile-card {
  margin-bottom: 10px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-sidebar);
}
.mobile-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
.mobile-card-title {
  min-width: 0;
  color: var(--text-main);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mobile-card-body {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.mobile-card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
}
.mobile-card-row .label {
  flex: 0 0 auto;
  color: var(--text-sub);
}
.mobile-card-row .value {
  min-width: 0;
  color: var(--text-main);
  text-align: right;
  overflow-wrap: anywhere;
}
@media (max-width: 900px) {
  .primary-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 768px) {
  .vnic-title {
    font-size: 18px;
  }
  .network-row {
    grid-template-columns: 1fr;
    gap: 2px;
    min-height: 0;
    padding: 9px 0;
  }
  .network-label,
  .network-value {
    line-height: 20px;
  }
}
</style>
