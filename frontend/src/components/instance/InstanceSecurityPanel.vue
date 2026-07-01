<template>
  <div class="security-panel">
    <div class="mobile-toolbar" style="margin-bottom: 12px">
      <a-button @click="loadSecurityRules" :loading="secLoading">加载规则</a-button>
      <a-button type="primary" @click="showAddRuleModal">添加规则</a-button>
      <a-popconfirm title="确定一键放行所有端口？（VCN 未启用 IPv6 时仅放行 IPv4）" @confirm="handleReleaseAll">
        <a-button type="primary" danger :loading="releaseLoading">一键放行</a-button>
      </a-popconfirm>
      <a-popconfirm title="将替换为纯TCP预设；VCN 未启用 IPv6 时仅 IPv4（TCP+ICMP），启用时含 ICMPv6。其他规则将被删除" @confirm="handleOciPreset">
        <a-button :loading="presetLoading">纯TCP放行</a-button>
      </a-popconfirm>
    </div>

    <a-tabs size="small">
      <a-tab-pane key="ingress" tab="入站规则">
        <a-table v-if="!isMobile" :data-source="ingressRules" :columns="secColumns" size="small" :pagination="false">
          <template #bodyCell="{ column, index }">
            <template v-if="column.key === 'secAction'">
              <a-popconfirm title="确定删除该规则？" @confirm="handleDeleteSecurityRule('ingress', index)">
                <a-button type="link" danger size="small" :loading="deleteRuleLoading">删除</a-button>
              </a-popconfirm>
            </template>
          </template>
        </a-table>
        <template v-else>
          <a-empty v-if="ingressRules.length === 0" description="无入站规则" />
          <div v-for="(rule, idx) in ingressRules" :key="idx" class="mobile-card">
            <div class="mobile-card-header">
              <span class="mobile-card-title">{{ protoMap[rule.protocol] || rule.protocol }}</span>
              <a-popconfirm title="确定删除？" @confirm="handleDeleteSecurityRule('ingress', idx)">
                <a-button type="link" danger size="small" :loading="deleteRuleLoading">删除</a-button>
              </a-popconfirm>
            </div>
            <div class="mobile-card-body">
              <div class="mobile-card-row"><span class="label">来源</span><span class="value">{{ rule.source }}</span></div>
              <div class="mobile-card-row"><span class="label">端口</span><span class="value">{{ rule.portRange }}</span></div>
              <div class="mobile-card-row" v-if="rule.description"><span class="label">描述</span><span class="value">{{ rule.description }}</span></div>
            </div>
          </div>
        </template>
      </a-tab-pane>

      <a-tab-pane key="egress" tab="出站规则">
        <a-table v-if="!isMobile" :data-source="egressRules" :columns="secColumns" size="small" :pagination="false">
          <template #bodyCell="{ column, index }">
            <template v-if="column.key === 'secAction'">
              <a-popconfirm title="确定删除该规则？" @confirm="handleDeleteSecurityRule('egress', index)">
                <a-button type="link" danger size="small" :loading="deleteRuleLoading">删除</a-button>
              </a-popconfirm>
            </template>
          </template>
        </a-table>
        <template v-else>
          <a-empty v-if="egressRules.length === 0" description="无出站规则" />
          <div v-for="(rule, idx) in egressRules" :key="idx" class="mobile-card">
            <div class="mobile-card-header">
              <span class="mobile-card-title">{{ protoMap[rule.protocol] || rule.protocol }}</span>
              <a-popconfirm title="确定删除？" @confirm="handleDeleteSecurityRule('egress', idx)">
                <a-button type="link" danger size="small" :loading="deleteRuleLoading">删除</a-button>
              </a-popconfirm>
            </div>
            <div class="mobile-card-body">
              <div class="mobile-card-row"><span class="label">目的</span><span class="value">{{ rule.source }}</span></div>
              <div class="mobile-card-row"><span class="label">端口</span><span class="value">{{ rule.portRange }}</span></div>
              <div class="mobile-card-row" v-if="rule.description"><span class="label">描述</span><span class="value">{{ rule.description }}</span></div>
            </div>
          </div>
        </template>
      </a-tab-pane>
    </a-tabs>

    <a-modal
      :keyboard="false"
      v-model:open="addRuleVisible"
      title="添加安全规则"
      @ok="handleAddRule"
      :confirm-loading="addRuleLoading"
      :mask-closable="false"
      :width="isMobile ? '100%' : 480"
    >
      <a-form layout="vertical">
        <a-form-item label="方向">
          <a-radio-group v-model:value="ruleForm.direction">
            <a-radio value="ingress">入站</a-radio>
            <a-radio value="egress">出站</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="协议">
          <a-select v-model:value="ruleForm.protocol">
            <a-select-option value="TCP">TCP</a-select-option>
            <a-select-option value="UDP">UDP</a-select-option>
            <a-select-option value="ICMP">ICMP</a-select-option>
            <a-select-option value="ICMPV6">ICMPv6</a-select-option>
            <a-select-option value="ALL">全部协议</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="来源/目的 CIDR">
          <a-input v-model:value="ruleForm.source" placeholder="0.0.0.0/0" />
        </a-form-item>
        <a-form-item label="端口范围" v-if="usesPortRange">
          <a-space>
            <a-input-number v-model:value="ruleForm.portMin" placeholder="留空=全部" :min="1" :max="65535" style="width: 140px" />
            <span>-</span>
            <a-input-number v-model:value="ruleForm.portMax" placeholder="留空=全部" :min="1" :max="65535" style="width: 140px" />
          </a-space>
        </a-form-item>
        <a-form-item label="描述">
          <a-input v-model:value="ruleForm.description" placeholder="可选" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  addSecurityRule,
  deleteSecurityRule,
  getSecurityRules,
  releaseAllPorts,
  releaseOciPreset,
} from '../../api/instance'

const props = defineProps<{
  tenant: any | null
  instance: any | null
  isMobile: boolean
  active: boolean
  region?: string
  compartmentId?: string
}>()

const emit = defineEmits<{
  (e: 'overlay-active-change', active: boolean): void
}>()

const protoMap: Record<string, string> = { '6': 'TCP', '17': 'UDP', '1': 'ICMP', '58': 'ICMPv6', all: '全部', ALL: '全部' }

const secColumns = [
  {
    title: '协议',
    dataIndex: 'protocol',
    key: 'protocol',
    width: 80,
    customRender: ({ text }: any) => protoMap[text] || text,
  },
  { title: '来源/目的', dataIndex: 'source', key: 'source' },
  { title: '端口范围', dataIndex: 'portRange', key: 'portRange', width: 120 },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '操作', key: 'secAction', width: 80 },
]

const secLoading = ref(false)
const releaseLoading = ref(false)
const presetLoading = ref(false)
const deleteRuleLoading = ref(false)
const addRuleVisible = ref(false)
const addRuleLoading = ref(false)
const ingressRules = ref<any[]>([])
const egressRules = ref<any[]>([])
const securityLoaded = ref(false)
const ruleForm = reactive({
  direction: 'ingress',
  protocol: 'TCP',
  source: '0.0.0.0/0',
  portMin: null as number | null,
  portMax: null as number | null,
  description: '',
})
const hasOpenModal = computed(() => addRuleVisible.value)
const usesPortRange = computed(() => ruleForm.protocol === 'TCP' || ruleForm.protocol === 'UDP')
let securityLoadSeq = 0

function scopeParam(): { region?: string; compartmentId?: string } {
  const r =
    (props.region && String(props.region).trim()) ||
    (props.instance?.region && String(props.instance.region).trim()) ||
    (props.tenant?.ociRegion && String(props.tenant.ociRegion).trim()) ||
    ''
  const cid =
    (props.compartmentId && String(props.compartmentId).trim()) ||
    (props.instance?.compartmentId && String(props.instance.compartmentId).trim()) ||
    ''
  return {
    ...(r ? { region: r } : {}),
    ...(cid ? { compartmentId: cid } : {}),
  }
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

function resetRuleForm() {
  Object.assign(ruleForm, {
    direction: 'ingress',
    protocol: 'TCP',
    source: '0.0.0.0/0',
    portMin: null,
    portMax: null,
    description: '',
  })
}

function reset() {
  securityLoadSeq += 1
  secLoading.value = false
  releaseLoading.value = false
  presetLoading.value = false
  deleteRuleLoading.value = false
  addRuleLoading.value = false
  addRuleVisible.value = false
  ingressRules.value = []
  egressRules.value = []
  securityLoaded.value = false
  resetRuleForm()
}

async function loadSecurityRules() {
  if (!props.instance || !props.tenant) return
  const requestId = ++securityLoadSeq
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const targetKey = currentTargetKey()
  const scope = scopeParam()
  secLoading.value = true
  try {
    const res = await getSecurityRules({
      id: tenantId,
      instanceId,
      ...scope,
    })
    if (requestId !== securityLoadSeq || !sameTarget(targetKey)) return
    const data = res.data || []
    ingressRules.value = data.filter((r: any) => r.direction === 'ingress')
    egressRules.value = data.filter((r: any) => r.direction === 'egress')
    securityLoaded.value = true
  } catch (e: any) {
    if (requestId === securityLoadSeq) message.error(e?.message || '加载安全规则失败')
  } finally {
    if (requestId === securityLoadSeq) secLoading.value = false
  }
}

function securityReleaseSuccessMessage(ipv6RulesApplied: unknown, allPorts: boolean) {
  if (ipv6RulesApplied === false) {
    return allPorts
      ? '已放行所有端口（该 VCN 未启用 IPv6，已仅放行 IPv4）'
      : '已应用纯 TCP 预设（该 VCN 未启用 IPv6，已仅应用 IPv4 规则）'
  }
  return allPorts ? '已放行所有端口（含 IPv4/IPv6）' : '已应用纯 TCP 预设规则（含 IPv4/IPv6）'
}

async function handleReleaseAll() {
  if (!props.instance || !props.tenant) return
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const targetKey = currentTargetKey()
  const scope = scopeParam()
  releaseLoading.value = true
  try {
    const res = await releaseAllPorts({
      id: tenantId,
      instanceId,
      ...scope,
    })
    if (!sameTarget(targetKey)) return
    message.success(securityReleaseSuccessMessage(res.data?.ipv6RulesApplied, true))
    void loadSecurityRules()
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '放行失败')
  } finally {
    if (sameTarget(targetKey)) releaseLoading.value = false
  }
}

async function handleOciPreset() {
  if (!props.instance || !props.tenant) return
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const targetKey = currentTargetKey()
  const scope = scopeParam()
  presetLoading.value = true
  try {
    const res = await releaseOciPreset({
      id: tenantId,
      instanceId,
      ...scope,
    })
    if (!sameTarget(targetKey)) return
    message.success(securityReleaseSuccessMessage(res.data?.ipv6RulesApplied, false))
    void loadSecurityRules()
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '应用预设失败')
  } finally {
    if (sameTarget(targetKey)) presetLoading.value = false
  }
}

function showAddRuleModal() {
  resetRuleForm()
  addRuleVisible.value = true
}

async function handleAddRule() {
  if (!props.instance || !props.tenant) return
  const source = String(ruleForm.source || '').trim()
  if (!source) {
    message.warning('请输入来源/目的 CIDR')
    return
  }

  let portMin = ruleForm.portMin
  let portMax = ruleForm.portMax
  if (usesPortRange.value) {
    if (!portMin && !portMax) {
      portMin = 1
      portMax = 65535
      ruleForm.portMin = portMin
      ruleForm.portMax = portMax
    } else if (!portMin || !portMax) {
      message.warning('请填写完整的端口范围，或留空表示全部端口')
      return
    } else if (portMin > portMax) {
      message.warning('起始端口不能大于结束端口')
      return
    }
  }

  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const targetKey = currentTargetKey()
  const scope = scopeParam()
  addRuleLoading.value = true
  try {
    const payload: Record<string, unknown> = {
      id: tenantId,
      instanceId,
      direction: ruleForm.direction,
      protocol: ruleForm.protocol,
      source,
      description: String(ruleForm.description || '').trim(),
      ...scope,
    }
    if (usesPortRange.value) {
      payload.portMin = portMin?.toString()
      payload.portMax = portMax?.toString()
    }
    await addSecurityRule(payload)
    if (!sameTarget(targetKey)) return
    message.success('规则已添加')
    addRuleVisible.value = false
    void loadSecurityRules()
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '添加规则失败')
  } finally {
    if (sameTarget(targetKey)) addRuleLoading.value = false
  }
}

async function handleDeleteSecurityRule(direction: string, ruleIndex: number) {
  if (!props.instance || !props.tenant) return
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const targetKey = currentTargetKey()
  const scope = scopeParam()
  deleteRuleLoading.value = true
  try {
    await deleteSecurityRule({
      id: tenantId,
      instanceId,
      direction,
      ruleIndex,
      ...scope,
    })
    if (!sameTarget(targetKey)) return
    message.success('规则已删除')
    void loadSecurityRules()
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '删除规则失败')
  } finally {
    if (sameTarget(targetKey)) deleteRuleLoading.value = false
  }
}

watch(
  () => ruleForm.protocol,
  () => {
    if (!usesPortRange.value) {
      ruleForm.portMin = null
      ruleForm.portMax = null
    }
  },
)

watch(
  () => [props.tenant?.id, props.instance?.instanceId, props.region, props.compartmentId],
  () => {
    reset()
    if (props.active) void loadSecurityRules()
  },
)

watch(
  () => props.active,
  (active) => {
    if (active && !securityLoaded.value) void loadSecurityRules()
  },
  { immediate: true },
)

watch(hasOpenModal, (active) => emit('overlay-active-change', active), { immediate: true })

onBeforeUnmount(() => {
  emit('overlay-active-change', false)
})

defineExpose({
  loadSecurityRules,
  reset,
  hasOpenModal,
})
</script>

<style scoped>
.mobile-card {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
}
.mobile-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
.mobile-card-title {
  color: var(--text-main);
  font-weight: 600;
  min-width: 0;
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
  color: var(--text-sub);
  white-space: nowrap;
}
.mobile-card-row .value {
  color: var(--text-main);
  min-width: 0;
  text-align: right;
  overflow-wrap: anywhere;
}
</style>
