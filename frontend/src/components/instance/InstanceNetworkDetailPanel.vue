<template>
  <a-spin :spinning="netDetailLoading">
    <a-button size="small" @click="loadNetworkDetail" :loading="netDetailLoading" style="margin-bottom: 12px">
      刷新网络信息
    </a-button>
    <template v-if="networkDetail">
      <div v-for="(vnic, vi) in networkDetail.vnics" :key="vi" style="margin-bottom: 16px">
        <a-descriptions :column="1" bordered size="small">
          <a-descriptions-item v-for="(ipd, idx) in getPrimaryIps(vnic)" :key="'p' + idx" label="主IP">
            <div>
              <template v-if="ipd.publicIpAddress">
                公网IP<a-tag :color="ipd.publicIpLifetime === 'RESERVED' ? 'green' : 'orange'" style="margin: 0 6px">{{ ipd.publicIpLifetime === 'RESERVED' ? '预留' : '临时' }}</a-tag><a-typography-text copyable>{{ ipd.publicIpAddress }}</a-typography-text>
                <span style="color: #999; margin-left: 6px">( {{ ipd.privateIpAddress }} )</span>
                <a-popconfirm title="确定删除该公网IP？" @confirm="handleDeletePublicIp(ipd)">
                  <a-button type="link" danger size="small">删除</a-button>
                </a-popconfirm>
              </template>
              <template v-else>
                内网IP: <a-typography-text copyable>{{ ipd.privateIpAddress }}</a-typography-text>
                <span style="color: #999; margin-left: 6px">（无公网IP）</span>
                <a-button type="link" size="small" @click="handleAssignEphemeralIp(ipd)" :loading="ephemeralIpLoading[ipd.privateIpId]">附加 IPv4</a-button>
              </template>
            </div>
          </a-descriptions-item>
          <a-descriptions-item label="辅助IP">
            <template v-if="getSecondaryIps(vnic).length > 0">
              <div v-for="(ipd, idx) in getSecondaryIps(vnic)" :key="'s' + idx" style="margin-bottom: 4px">
                <template v-if="ipd.publicIpAddress">
                  公网IP<a-tag :color="ipd.publicIpLifetime === 'RESERVED' ? 'green' : 'orange'" style="margin: 0 6px">{{ ipd.publicIpLifetime === 'RESERVED' ? '预留' : '临时' }}</a-tag><a-typography-text copyable>{{ ipd.publicIpAddress }}</a-typography-text>
                  <span style="color: #999; margin-left: 6px">( {{ ipd.privateIpAddress }} )</span>
                  <a-popconfirm title="将同时删除公网IP和内网IP，确定？" @confirm="handleDeleteSecondaryIp(ipd)">
                    <a-button type="link" danger size="small">删除</a-button>
                  </a-popconfirm>
                </template>
                <template v-else>
                  内网IP: <a-typography-text copyable>{{ ipd.privateIpAddress }}</a-typography-text>
                  <a-popconfirm title="确定删除该辅助IP？" @confirm="handleDeleteSecondaryIp(ipd)">
                    <a-button type="link" danger size="small">删除</a-button>
                  </a-popconfirm>
                </template>
              </div>
            </template>
            <template v-else>
              <span style="color: #999">无</span>
              <a-button type="link" size="small" @click="handleAddAuxIp" :loading="auxIpLoading" style="margin-left: 8px">添加辅助IP</a-button>
            </template>
          </a-descriptions-item>
          <a-descriptions-item label="IPv6">
            <template v-if="vnic.ipv6List && vnic.ipv6List.length > 0">
              <div v-for="(ip6, i6) in vnic.ipv6List" :key="ip6.ipv6Id || i6" style="margin-bottom: 4px">
                <a-typography-text copyable>{{ ip6.ipAddress }}</a-typography-text>
                <a-popconfirm title="确定取消分配该 IPv6？" @confirm="handleRemoveIpv6(ip6)">
                  <a-button type="link" danger size="small" :loading="ipv6RemoveLoading[ip6.ipv6Id]">取消分配</a-button>
                </a-popconfirm>
              </div>
              <div>
                <a-button type="link" size="small" @click="handleAddIpv6(vnic)" :loading="ipv6AddLoading[vnicLoadingKey(vnic)]">
                  分配 IPv6
                </a-button>
              </div>
            </template>
            <span v-else style="color: #999">
              无
              <a-button type="link" size="small" @click="handleAddIpv6(vnic)" :loading="ipv6AddLoading[vnicLoadingKey(vnic)]">添加 IPv6</a-button>
            </span>
          </a-descriptions-item>
        </a-descriptions>
      </div>
    </template>
  </a-spin>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  addIpv6,
  assignEphemeralIp,
  assignReservedIp,
  createReservedIp,
  deletePublicIp,
  deleteSecondaryIp,
  getInstanceNetworkDetail,
  getSecurityRules,
  listReservedIps,
  removeIpv6,
} from '../../api/instance'
import { INSTANCE_CONFIRM_MODAL_WRAP_CLASS, INSTANCE_CONFIRM_MODAL_Z_INDEX } from '../../utils/overlayZIndex'

const props = defineProps<{
  tenant: any | null
  instance: any | null
  active: boolean
  region?: string
  compartmentId?: string
}>()

const networkDetail = ref<any>(null)
const netDetailLoading = ref(false)
const ipv6AddLoading = ref<Record<string, boolean>>({})
const ipv6RemoveLoading = ref<Record<string, boolean>>({})
const ephemeralIpLoading = ref<Record<string, boolean>>({})
const auxIpLoading = ref(false)
let networkLoadSeq = 0

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
  netDetailLoading.value = false
  ipv6AddLoading.value = {}
  ipv6RemoveLoading.value = {}
  ephemeralIpLoading.value = {}
  auxIpLoading.value = false
  networkDetail.value = null
}

async function loadNetworkDetail() {
  if (!props.instance || !props.tenant) return
  const requestId = ++networkLoadSeq
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const targetKey = currentTargetKey()
  const scope = scopeParam()
  netDetailLoading.value = true
  try {
    const res = await getInstanceNetworkDetail({
      id: tenantId,
      instanceId,
      ...scope,
    })
    if (requestId !== networkLoadSeq || !sameTarget(targetKey)) return
    networkDetail.value = res.data || null
  } catch (e: any) {
    if (requestId === networkLoadSeq && sameTarget(targetKey)) message.error(e?.message || '加载网络详情失败')
  } finally {
    if (requestId === networkLoadSeq && sameTarget(targetKey)) netDetailLoading.value = false
  }
}

async function handleAddIpv6(vnic?: any) {
  if (!props.instance || !props.tenant) return
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const vnicId = vnicLoadingKey(vnic)
  const targetKey = currentTargetKey()
  const scope = scopeParam()
  ipv6AddLoading.value[vnicId] = true
  try {
    const res = await addIpv6({
      id: tenantId,
      instanceId,
      vnicId: vnic?.vnicId,
      ...scope,
    })
    if (!sameTarget(targetKey)) return
    message.success('IPv6 已分配: ' + (res.data?.ipv6Address || ''))
    void loadNetworkDetail()
    await checkIpv6SecurityHealth(tenantId, instanceId, scope, targetKey)
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '分配 IPv6 失败')
  } finally {
    if (sameTarget(targetKey)) ipv6AddLoading.value[vnicId] = false
  }
}

async function handleRemoveIpv6(ip6: any) {
  if (!props.tenant) return
  const tenantId = props.tenant.id
  const ipv6Id = ip6?.ipv6Id
  if (!ipv6Id) {
    message.error('缺少 ipv6Id，无法取消分配')
    return
  }
  const targetKey = currentTargetKey()
  const scope = regionParam()
  ipv6RemoveLoading.value[ipv6Id] = true
  try {
    await removeIpv6({ id: tenantId, ipv6Id, ...scope })
    if (!sameTarget(targetKey)) return
    message.success('IPv6 已取消分配')
    void loadNetworkDetail()
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '取消分配 IPv6 失败')
  } finally {
    if (sameTarget(targetKey)) ipv6RemoveLoading.value[ipv6Id] = false
  }
}

function isIpv6Cidr(cidr: string | undefined) {
  if (!cidr) return false
  return cidr.includes(':')
}

async function checkIpv6SecurityHealth(
  tenantId: string,
  instanceId: string,
  scope: { region?: string; compartmentId?: string },
  targetKey: string,
) {
  try {
    const res = await getSecurityRules({
      id: tenantId,
      instanceId,
      ...scope,
    })
    if (!sameTarget(targetKey)) return
    const data = res.data || []
    const ingress = data.filter((r: any) => r.direction === 'ingress')
    const egress = data.filter((r: any) => r.direction === 'egress')

    const hasIpv6Ingress = ingress.some((r: any) => isIpv6Cidr(r.source))
    const hasIpv6Egress = egress.some((r: any) => isIpv6Cidr(r.source))

    if (hasIpv6Ingress && hasIpv6Egress) return

    const missing: string[] = []
    if (!hasIpv6Ingress) missing.push('IPv6 入站规则')
    if (!hasIpv6Egress) missing.push('IPv6 出站规则')

    Modal.warning({
      title: 'IPv6 连通性自检提醒',
      content: `检测到当前安全列表缺少 ${missing.join('、')}，可能出现“有 IPv6 地址但不通”。建议前往“安全列表”补充对应 IPv6 规则（如 ::/0）。`,
      okText: '知道了',
      zIndex: INSTANCE_CONFIRM_MODAL_Z_INDEX,
      wrapClassName: INSTANCE_CONFIRM_MODAL_WRAP_CLASS,
    })
  } catch {
    // 自检失败不影响 IPv6 分配结果。
  }
}

async function handleAssignEphemeralIp(ipd: any) {
  if (!props.instance || !props.tenant) return
  if (!ipd?.privateIpId) {
    message.error('缺少 privateIpId，无法分配公网IP')
    return
  }
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const privateIpId = ipd.privateIpId
  const targetKey = currentTargetKey()
  const scope = scopeParam()
  ephemeralIpLoading.value[privateIpId] = true
  try {
    await assignEphemeralIp({
      id: tenantId,
      instanceId,
      privateIpId,
      ...scope,
    })
    if (!sameTarget(targetKey)) return
    message.success('公网 IPv4 已分配')
    void loadNetworkDetail()
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '分配公网IP失败')
  } finally {
    if (sameTarget(targetKey)) ephemeralIpLoading.value[privateIpId] = false
  }
}

async function handleDeletePublicIp(ipd: any) {
  if (!props.tenant) return
  if (!ipd?.privateIpId) {
    message.error('缺少 privateIpId，无法删除公网IP')
    return
  }
  const tenantId = props.tenant.id
  const targetKey = currentTargetKey()
  const scope = regionParam()
  try {
    await deletePublicIp({
      id: tenantId,
      privateIpId: ipd.privateIpId,
      ...scope,
    })
    if (!sameTarget(targetKey)) return
    message.success('公网IP已删除')
    void loadNetworkDetail()
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '删除公网IP失败')
  }
}

async function handleDeleteSecondaryIp(ipd: any) {
  if (!props.tenant) return
  if (!ipd?.privateIpId) {
    message.error('缺少 privateIpId，无法删除辅助IP')
    return
  }
  const tenantId = props.tenant.id
  const targetKey = currentTargetKey()
  const scope = regionParam()
  try {
    await deleteSecondaryIp({
      id: tenantId,
      privateIpId: ipd.privateIpId,
      ...scope,
    })
    if (!sameTarget(targetKey)) return
    message.success('辅助IP已删除')
    void loadNetworkDetail()
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '删除辅助IP失败')
  }
}

function getPrimaryIps(vnic: any) {
  return (vnic.ipDetails || []).filter((ip: any) => ip.isPrimary)
}

function getSecondaryIps(vnic: any) {
  return (vnic.ipDetails || []).filter((ip: any) => !ip.isPrimary)
}

function vnicLoadingKey(vnic: any) {
  return vnic?.vnicId || 'default'
}

async function handleAddAuxIp() {
  if (!props.tenant || !props.instance) return
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const targetKey = currentTargetKey()
  const scope = scopeParam()
  const regionScope = regionParam()
  auxIpLoading.value = true
  try {
    let ipId = ''
    let ipAddr = ''

    try {
      const listRes = await listReservedIps({
        id: tenantId,
        ...regionScope,
      })
      const unbound = (listRes.data || []).find((ip: any) => !ip.isAssigned)
      if (unbound) {
        ipId = unbound.id
        ipAddr = unbound.ipAddress
        if (sameTarget(targetKey)) message.info('使用已有预留IP: ' + ipAddr)
      }
    } catch {
      // 查询可复用预留 IP 失败时继续创建新的预留 IP。
    }

    if (!ipId) {
      const res = await createReservedIp({
        id: tenantId,
        displayName: 'aux-' + Date.now(),
        ...regionScope,
      })
      ipId = res.data?.publicIpId || res.data?.id
      ipAddr = res.data?.ipAddress || ''
      if (!ipId) throw new Error('创建预留IP失败')
      if (sameTarget(targetKey)) message.success('预留IP已创建: ' + ipAddr)
    }

    await assignReservedIp({
      id: tenantId,
      publicIpId: ipId,
      instanceId,
      ...scope,
    })
    if (!sameTarget(targetKey)) return
    message.success('辅助IP已附加到实例: ' + ipAddr)
    void loadNetworkDetail()
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '添加辅助IP失败')
  } finally {
    if (sameTarget(targetKey)) auxIpLoading.value = false
  }
}

watch(
  () => [props.tenant?.id, props.instance?.instanceId, props.region, props.compartmentId],
  () => {
    reset()
    if (props.active) void loadNetworkDetail()
  },
)

watch(
  () => props.active,
  (active) => {
    if (active && !networkDetail.value) void loadNetworkDetail()
  },
  { immediate: true },
)

defineExpose({
  loadNetworkDetail,
  reset,
})
</script>
