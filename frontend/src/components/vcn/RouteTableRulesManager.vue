<template>
  <a-modal
    :open="open"
    :title="modalTitle"
    :width="1120"
    :z-index="VCN_MANAGER_MODAL_Z_INDEX"
    :wrap-class-name="VCN_MANAGER_MODAL_WRAP_CLASS"
    :mask-closable="false"
    :keyboard="false"
    centered
    ok-text="保存更改"
    cancel-text="关闭"
    :confirm-loading="saving"
    @ok="saveRules"
    @cancel="close"
  >
    <a-spin :spinning="loading">
      <div class="rt-manager">
        <div class="rt-toolbar">
          <a-input
            v-model:value="keyword"
            class="rt-search"
            allow-clear
            placeholder="搜索目的地 / 目标 / 描述"
          />
          <a-select
            v-model:value="selectedCompartmentId"
            class="rt-compartment"
            :loading="optionsLoading"
            placeholder="目标区间"
            show-search
            option-filter-prop="title"
            @change="onCompartmentChange"
          >
            <a-select-option
              v-for="item in compartments"
              :key="item.id"
              :value="item.id"
              :title="`${item.displayName || item.name || item.id} ${item.id}`"
            >
              {{ item.displayName || item.name || shortId(item.id) }}
            </a-select-option>
          </a-select>
          <a-button size="small" @click="reload" :loading="loading || optionsLoading">刷新</a-button>
          <a-button type="primary" size="small" @click="openAddRule">添加路由规则</a-button>
          <a-tag v-if="dirty" color="gold">未保存</a-tag>
        </div>

        <a-alert
          v-if="warnings.length"
          class="rt-warning"
          type="warning"
          show-icon
          :message="warningText"
        />

        <a-table
          size="small"
          :data-source="filteredRules"
          :columns="ruleColumns"
          :pagination="false"
          :scroll="{ x: 920 }"
          row-key="_key"
        >
          <template #bodyCell="{ column, record, index }">
            <template v-if="column.key === 'destination'">
              <div class="rt-main-text">{{ record.destination || '—' }}</div>
              <div class="rt-sub-text">{{ destinationTypeLabel(record.destinationType) }}</div>
            </template>
            <template v-else-if="column.key === 'target'">
              <div class="rt-target-cell">
                <a-tag class="rt-target-tag">{{ targetTypeLabel(targetTypeOfRule(record)) }}</a-tag>
                <div class="rt-target-texts">
                  <div class="rt-main-text">{{ targetDisplayName(record.networkEntityId) }}</div>
                  <div class="rt-sub-text">{{ shortId(record.networkEntityId) }}</div>
                </div>
              </div>
            </template>
            <template v-else-if="column.key === 'routeType'">
              {{ routeTypeLabel(record.routeType) }}
            </template>
            <template v-else-if="column.key === 'description'">
              <span class="rt-description">{{ record.description || '—' }}</span>
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space :size="4">
                <a-button type="link" size="small" @click="openEditRule(record)">编辑</a-button>
                <a-popconfirm
                  title="确定删除这条路由规则？"
                  :z-index="VCN_MANAGER_CONFIRM_MODAL_Z_INDEX"
                  @confirm="removeRule(index)"
                >
                  <a-button type="link" danger size="small">删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </div>
    </a-spin>

    <a-modal
      v-model:open="ruleEditorOpen"
      :title="editingRuleKey ? '编辑路由规则' : '添加路由规则'"
      :width="760"
      :z-index="VCN_MANAGER_CONFIRM_MODAL_Z_INDEX"
      :wrap-class-name="VCN_MANAGER_CONFIRM_MODAL_WRAP_CLASS"
      :mask-closable="false"
      :keyboard="false"
      centered
      ok-text="确定"
      cancel-text="取消"
      @ok="saveRuleDraft"
    >
      <a-form layout="vertical" class="rt-rule-form">
        <div class="rt-form-grid">
          <a-form-item label="目标类型" required>
            <a-select v-model:value="ruleForm.targetType" @change="onTargetTypeChange">
              <a-select-option v-for="item in targetTypeOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </a-select-option>
            </a-select>
          </a-form-item>

          <a-form-item label="目的地类型" required>
            <a-select v-model:value="ruleForm.destinationType" disabled>
              <a-select-option value="CIDR_BLOCK">CIDR 块</a-select-option>
              <a-select-option value="SERVICE_CIDR_BLOCK">服务</a-select-option>
            </a-select>
          </a-form-item>
        </div>

        <a-form-item v-if="ruleForm.destinationType === 'SERVICE_CIDR_BLOCK'" label="目的地服务" required>
          <a-select
            v-model:value="ruleForm.destination"
            :loading="optionsLoading"
            placeholder="选择 Oracle 服务"
            show-search
            option-filter-prop="title"
          >
            <a-select-option
              v-for="item in serviceOptions"
              :key="item.cidrBlock || item.id"
              :value="item.cidrBlock"
              :title="`${item.displayName || item.name || ''} ${item.cidrBlock || ''}`"
            >
              <div class="rt-option-title">{{ item.displayName || item.name || item.cidrBlock }}</div>
              <div class="rt-option-sub">{{ item.cidrBlock }}</div>
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-else label="目的地 CIDR 块" required>
          <a-input v-model:value="ruleForm.destination" placeholder="如 0.0.0.0/0 或 10.0.0.0/16" />
        </a-form-item>

        <div class="rt-form-grid">
          <a-form-item label="目标区间" required>
            <a-select
              v-model:value="selectedCompartmentId"
              :loading="optionsLoading"
              show-search
              option-filter-prop="title"
              @change="onCompartmentChange"
            >
              <a-select-option
                v-for="item in compartments"
                :key="item.id"
                :value="item.id"
                :title="`${item.displayName || item.name || item.id} ${item.id}`"
              >
                {{ item.displayName || item.name || shortId(item.id) }}
              </a-select-option>
            </a-select>
          </a-form-item>

          <a-form-item label="目标资源" required>
            <a-input
              v-if="ruleForm.manualTarget"
              v-model:value="ruleForm.networkEntityId"
              placeholder="ocid1..."
            />
            <a-select
              v-else
              v-model:value="ruleForm.networkEntityId"
              :loading="optionsLoading"
              placeholder="选择目标资源"
              show-search
              option-filter-prop="title"
              :not-found-content="optionsLoading ? '加载中...' : '当前区间没有该类型目标'"
            >
              <a-select-option
                v-for="item in currentTargetItems"
                :key="item.id"
                :value="item.id"
                :title="targetSearchText(item)"
              >
                <div class="rt-option-title">
                  {{ targetOptionTitle(item) }}
                  <a-tag v-if="item.type === 'privateIp' && item.skipSourceDestCheck === false" color="warning">
                    源/目的检查未关闭
                  </a-tag>
                </div>
                <div class="rt-option-sub">{{ targetOptionSubtitle(item) }}</div>
              </a-select-option>
            </a-select>
            <button type="button" class="rt-manual-toggle" @click="ruleForm.manualTarget = !ruleForm.manualTarget">
              {{ ruleForm.manualTarget ? '从候选资源选择' : '手动输入 OCID' }}
            </button>
          </a-form-item>
        </div>

        <a-alert
          v-if="selectedTarget?.type === 'privateIp' && selectedTarget.skipSourceDestCheck === false"
          type="warning"
          show-icon
          message="该 Private IP 所属 VNIC 尚未关闭源/目的检查，按 OCI 官方要求可能导致路由黑洞。"
        />

        <a-form-item label="描述">
          <a-textarea v-model:value="ruleForm.description" :rows="3" :maxlength="255" show-count />
        </a-form-item>
      </a-form>
    </a-modal>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { getRouteRuleOptions, getRouteTable, updateRouteTable } from '../../api/vcn'
import {
  VCN_MANAGER_CONFIRM_MODAL_WRAP_CLASS,
  VCN_MANAGER_CONFIRM_MODAL_Z_INDEX,
  VCN_MANAGER_MODAL_WRAP_CLASS,
  VCN_MANAGER_MODAL_Z_INDEX,
} from '../../utils/overlayZIndex'

type RouteRule = {
  _key: string
  destination: string
  destinationType: string
  networkEntityId: string
  description?: string
  routeType?: string
}

type RouteTarget = {
  id: string
  type: string
  displayName?: string
  compartmentId?: string
  compartmentName?: string
  lifecycleState?: string
  ipAddress?: string
  privateIpAddress?: string
  isPrimary?: boolean
  hostnameLabel?: string
  subnetId?: string
  subnetName?: string
  subnetCidrBlock?: string
  vnicId?: string
  vnicDisplayName?: string
  skipSourceDestCheck?: boolean | null
  isEnabled?: boolean
  blockTraffic?: boolean
  natIp?: string
  peeringStatus?: string
  peerAdvertisedCidr?: string
}

type CompartmentOption = { id: string; name?: string; displayName?: string }
type ServiceOption = { id: string; name?: string; displayName?: string; cidrBlock: string; description?: string }
type TargetGroup = { type: string; label: string; items: RouteTarget[] }

const props = withDefaults(defineProps<{
  open: boolean
  userId: string
  vcn: any
  routeTable: any
  ociRegion?: string
}>(), { ociRegion: '' })

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'saved'): void
}>()

const loading = ref(false)
const optionsLoading = ref(false)
const saving = ref(false)
const dirty = ref(false)
const keyword = ref('')
const selectedCompartmentId = ref('')
const rules = ref<RouteRule[]>([])
const options = ref<any>({})
const rememberedTargets = ref<Record<string, RouteTarget & { targetLabel?: string }>>({})
const ruleEditorOpen = ref(false)
const editingRuleKey = ref('')
const loadedContextKey = ref('')

const ruleForm = reactive({
  targetType: 'internetGateway',
  destinationType: 'CIDR_BLOCK',
  destination: '0.0.0.0/0',
  networkEntityId: '',
  description: '',
  manualTarget: false,
})

const targetTypeOptions = [
  { value: 'drg', label: '动态路由网关' },
  { value: 'internetGateway', label: 'Internet 网关' },
  { value: 'localPeeringGateway', label: '本地对等连接网关' },
  { value: 'natGateway', label: 'NAT 网关' },
  { value: 'privateIp', label: '专用 IP' },
  { value: 'serviceGateway', label: '服务网关' },
]

const ruleColumns = [
  { title: '目的地', key: 'destination', width: 220 },
  { title: '目标', key: 'target', width: 340 },
  { title: '路由类型', key: 'routeType', width: 100 },
  { title: '描述', key: 'description', ellipsis: true },
  { title: '操作', key: 'action', width: 110 },
]

const modalTitle = computed(() => `路由规则 - ${props.routeTable?.displayName || ''}`)
const compartments = computed<CompartmentOption[]>(() => options.value?.compartments || [])
const serviceOptions = computed<ServiceOption[]>(() => (options.value?.services || []).filter((item: ServiceOption) => item?.cidrBlock))
const warnings = computed<any[]>(() => options.value?.warnings || [])
const warningText = computed(() => warnings.value.map((item) => `${item.label || item.type}: ${item.message || '加载失败'}`).join('；'))
const targetGroups = computed<TargetGroup[]>(() => options.value?.targetGroups || [])
const targetGroupMap = computed(() => {
  const map: Record<string, RouteTarget[]> = {}
  for (const group of targetGroups.value) map[group.type] = group.items || []
  return map
})
const targetIndex = computed(() => {
  const map = new Map<string, RouteTarget & { targetLabel?: string }>()
  for (const item of Object.values(rememberedTargets.value)) {
    if (item?.id) map.set(item.id, item)
  }
  for (const group of targetGroups.value) {
    for (const item of group.items || []) {
      map.set(item.id, { ...item, targetLabel: group.label })
    }
  }
  return map
})
const currentTargetItems = computed(() => targetGroupMap.value[ruleForm.targetType] || [])
const selectedTarget = computed(() => targetIndex.value.get(ruleForm.networkEntityId))
const filteredRules = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return rules.value
  return rules.value.filter((rule) => {
    const target = targetIndex.value.get(rule.networkEntityId)
    return [
      rule.destination,
      rule.destinationType,
      rule.description,
      rule.networkEntityId,
      target?.displayName,
      target?.ipAddress,
      target?.subnetName,
      target?.targetLabel,
    ].some((item) => String(item || '').toLowerCase().includes(q))
  })
})

watch(
  () => [props.open, props.userId, props.vcn?.id, props.routeTable?.id, props.ociRegion] as const,
  ([open]) => {
    if (open && props.userId && props.vcn?.id && props.routeTable?.id) {
      const nextContextKey = `${props.userId}|${props.ociRegion || ''}|${props.vcn.id}`
      if (nextContextKey !== loadedContextKey.value) {
        loadedContextKey.value = nextContextKey
        selectedCompartmentId.value = ''
        rememberedTargets.value = {}
        keyword.value = ''
        dirty.value = false
        ruleEditorOpen.value = false
      }
      void reload()
    }
  },
  { immediate: true },
)

function baseParams() {
  const region = props.ociRegion?.trim()
  return region ? { id: props.userId, region } : { id: props.userId }
}

function close() {
  if (saving.value) return
  if (dirty.value) {
    Modal.confirm({
      title: '关闭路由规则编辑？',
      content: '当前路由规则有未保存的更改，关闭后这些更改不会写入 OCI。',
      okText: '关闭',
      cancelText: '继续编辑',
      zIndex: VCN_MANAGER_CONFIRM_MODAL_Z_INDEX,
      wrapClassName: VCN_MANAGER_CONFIRM_MODAL_WRAP_CLASS,
      onOk: () => emit('update:open', false),
    })
    return
  }
  emit('update:open', false)
}

async function reload() {
  if (!props.routeTable?.id || !props.vcn?.id) return
  loading.value = true
  try {
    const [detail, opts] = await Promise.all([
      getRouteTable({ ...baseParams(), rtId: props.routeTable.id }),
      getRouteRuleOptions({
        ...baseParams(),
        vcnId: props.vcn.id,
        targetCompartmentId: selectedCompartmentId.value || undefined,
        force: true,
      }),
    ])
    options.value = opts.data || {}
    rememberTargetsFromOptions()
    selectedCompartmentId.value = options.value.selectedCompartmentId || options.value.defaultCompartmentId || selectedCompartmentId.value
    rules.value = ((detail.data?.routeRules || []) as any[]).map(normalizeRule)
    dirty.value = false
  } catch (e: any) {
    message.error(e?.message || '加载路由规则失败')
  } finally {
    loading.value = false
  }
}

async function loadOptions(force = false) {
  if (!props.vcn?.id) return
  optionsLoading.value = true
  try {
    const r = await getRouteRuleOptions({
      ...baseParams(),
      vcnId: props.vcn.id,
      targetCompartmentId: selectedCompartmentId.value || undefined,
      force,
    })
    options.value = r.data || {}
    rememberTargetsFromOptions()
    selectedCompartmentId.value = options.value.selectedCompartmentId || selectedCompartmentId.value || options.value.defaultCompartmentId || ''
  } catch (e: any) {
    message.error(e?.message || '加载路由目标失败')
  } finally {
    optionsLoading.value = false
  }
}

async function onCompartmentChange() {
  await loadOptions(true)
  if (ruleEditorOpen.value && !ruleForm.manualTarget) {
    const first = currentTargetItems.value[0]
    ruleForm.networkEntityId = first?.id || ''
  }
}

function rememberTargetsFromOptions() {
  const next = { ...rememberedTargets.value }
  for (const group of (options.value?.targetGroups || []) as TargetGroup[]) {
    for (const item of group.items || []) {
      if (item?.id) next[item.id] = { ...item, targetLabel: group.label }
    }
  }
  rememberedTargets.value = next
}

function normalizeRule(rule: any, index: number): RouteRule {
  const networkEntityId = String(rule.networkEntityId || '')
  const targetType = inferTargetType(networkEntityId)
  return {
    _key: `rt_${index}_${Date.now()}_${Math.random()}`,
    destination: String(rule.destination || rule.cidrBlock || ''),
    destinationType: String(rule.destinationType || (targetType === 'serviceGateway' ? 'SERVICE_CIDR_BLOCK' : 'CIDR_BLOCK')),
    networkEntityId,
    description: rule.description || '',
    routeType: rule.routeType || 'STATIC',
  }
}

function openAddRule() {
  const targetType = firstAvailableTargetType()
  Object.assign(ruleForm, {
    targetType,
    destinationType: targetType === 'serviceGateway' ? 'SERVICE_CIDR_BLOCK' : 'CIDR_BLOCK',
    destination: targetType === 'serviceGateway' ? firstServiceDestination() : '0.0.0.0/0',
    networkEntityId: (targetGroupMap.value[targetType] || [])[0]?.id || '',
    description: '',
    manualTarget: false,
  })
  editingRuleKey.value = ''
  ruleEditorOpen.value = true
}

function openEditRule(rule: RouteRule) {
  const target = targetIndex.value.get(rule.networkEntityId)
  const targetType = target?.type || inferTargetType(rule.networkEntityId)
  Object.assign(ruleForm, {
    targetType,
    destinationType: rule.destinationType || (targetType === 'serviceGateway' ? 'SERVICE_CIDR_BLOCK' : 'CIDR_BLOCK'),
    destination: rule.destination || '',
    networkEntityId: rule.networkEntityId || '',
    description: rule.description || '',
    manualTarget: !target,
  })
  editingRuleKey.value = rule._key
  ruleEditorOpen.value = true
}

function onTargetTypeChange() {
  ruleForm.destinationType = ruleForm.targetType === 'serviceGateway' ? 'SERVICE_CIDR_BLOCK' : 'CIDR_BLOCK'
  if (ruleForm.targetType === 'serviceGateway') {
    ruleForm.destination = firstServiceDestination()
  } else if (!ruleForm.destination || ruleForm.destinationType === 'SERVICE_CIDR_BLOCK') {
    ruleForm.destination = '0.0.0.0/0'
  }
  if (!ruleForm.manualTarget) {
    ruleForm.networkEntityId = currentTargetItems.value[0]?.id || ''
  }
}

function saveRuleDraft() {
  const draft: RouteRule = {
    _key: editingRuleKey.value || `rt_${Date.now()}_${Math.random()}`,
    destination: ruleForm.destination.trim(),
    destinationType: ruleForm.destinationType,
    networkEntityId: ruleForm.networkEntityId.trim(),
    description: ruleForm.description.trim(),
    routeType: 'STATIC',
  }
  const validation = validateRule(draft)
  if (validation) return message.warning(validation)

  if (selectedTarget.value?.type === 'privateIp' && selectedTarget.value.skipSourceDestCheck === false) {
    Modal.confirm({
      title: '确认保存 Private IP 路由？',
      content: '该 Private IP 所属 VNIC 尚未关闭源/目的检查，可能导致流量黑洞。确认继续保存这条规则吗？',
      zIndex: VCN_MANAGER_CONFIRM_MODAL_Z_INDEX,
      wrapClassName: VCN_MANAGER_CONFIRM_MODAL_WRAP_CLASS,
      onOk: () => commitRuleDraft(draft),
    })
    return
  }
  commitRuleDraft(draft)
}

function commitRuleDraft(draft: RouteRule) {
  const index = rules.value.findIndex((item) => item._key === draft._key)
  if (index >= 0) rules.value.splice(index, 1, draft)
  else rules.value.push(draft)
  dirty.value = true
  ruleEditorOpen.value = false
}

function removeRule(indexInFiltered: number) {
  const row = filteredRules.value[indexInFiltered]
  if (!row) return
  const index = rules.value.findIndex((item) => item._key === row._key)
  if (index >= 0) {
    rules.value.splice(index, 1)
    dirty.value = true
  }
}

async function saveRules() {
  for (const rule of rules.value) {
    const validation = validateRule(rule)
    if (validation) return message.warning(validation)
  }
  saving.value = true
  try {
    await updateRouteTable({
      ...baseParams(),
      rtId: props.routeTable.id,
      routeRules: rules.value.map((rule) => ({
        destination: rule.destination,
        destinationType: rule.destinationType,
        networkEntityId: rule.networkEntityId,
        description: rule.description,
        routeType: rule.routeType || 'STATIC',
      })),
    })
    message.success('路由规则已保存')
    dirty.value = false
    emit('saved')
    emit('update:open', false)
  } catch (e: any) {
    message.error(e?.message || '保存路由规则失败')
  } finally {
    saving.value = false
  }
}

function validateRule(rule: RouteRule) {
  if (!rule.destination) return '请填写目的地'
  if (!rule.networkEntityId) return '请选择或填写目标资源'
  const targetType = targetTypeOfRule(rule)
  if (targetType === 'serviceGateway' && rule.destinationType !== 'SERVICE_CIDR_BLOCK') return '服务网关必须使用服务目的地'
  if (targetType !== 'serviceGateway' && rule.destinationType === 'SERVICE_CIDR_BLOCK') return '只有服务网关可以使用服务目的地'
  if (rule.destinationType === 'CIDR_BLOCK' && !isCidrLike(rule.destination)) return '目的地 CIDR 块格式不正确'
  if (rule.destinationType === 'SERVICE_CIDR_BLOCK' && !rule.destination) return '请选择目的地服务'
  if (!rule.networkEntityId.startsWith('ocid1.')) return '目标资源必须是 OCI OCID'
  return ''
}

function isCidrLike(value: string) {
  return /^(\d{1,3}\.){3}\d{1,3}\/\d{1,2}$/.test(value) || /^[0-9a-f:]+\/\d{1,3}$/i.test(value)
}

function firstAvailableTargetType() {
  return targetTypeOptions.find((item) => (targetGroupMap.value[item.value] || []).length)?.value || 'internetGateway'
}

function firstServiceDestination() {
  return serviceOptions.value[0]?.cidrBlock || ''
}

function inferTargetType(id: string) {
  if (!id) return 'internetGateway'
  if (id.includes('.drg.')) return 'drg'
  if (id.includes('.internetgateway.')) return 'internetGateway'
  if (id.includes('.localpeeringgateway.')) return 'localPeeringGateway'
  if (id.includes('.natgateway.')) return 'natGateway'
  if (id.includes('.privateip.')) return 'privateIp'
  if (id.includes('.servicegateway.')) return 'serviceGateway'
  return 'internetGateway'
}

function targetTypeOfRule(rule: RouteRule) {
  return targetIndex.value.get(rule.networkEntityId)?.type || inferTargetType(rule.networkEntityId)
}

function targetTypeLabel(type: string) {
  return targetTypeOptions.find((item) => item.value === type)?.label || type || '未知目标'
}

function destinationTypeLabel(type: string) {
  return type === 'SERVICE_CIDR_BLOCK' ? '服务' : 'CIDR 块'
}

function routeTypeLabel(type?: string) {
  return type === 'LOCAL' ? '本地' : '静态'
}

function targetDisplayName(id: string) {
  const target = targetIndex.value.get(id)
  return target ? targetOptionTitle(target) : shortId(id)
}

function targetOptionTitle(item: RouteTarget) {
  if (item.type === 'privateIp') {
    const primary = item.isPrimary ? '主 IP' : '辅助 IP'
    return `${item.ipAddress || item.privateIpAddress || item.displayName || item.id} (${primary})`
  }
  return item.displayName || shortId(item.id)
}

function targetOptionSubtitle(item: RouteTarget) {
  if (item.type === 'privateIp') {
    const parts = [
      item.subnetName ? `子网 ${item.subnetName}` : '',
      item.vnicDisplayName ? `VNIC ${item.vnicDisplayName}` : '',
      item.skipSourceDestCheck === true ? '已关闭源/目的检查' : '',
    ].filter(Boolean)
    return parts.join(' / ') || shortId(item.id)
  }
  const state = item.lifecycleState ? `状态 ${item.lifecycleState}` : ''
  const extra = item.type === 'internetGateway' && item.isEnabled === false ? '未启用' : ''
  return [item.compartmentName, state, extra].filter(Boolean).join(' / ') || shortId(item.id)
}

function targetSearchText(item: RouteTarget) {
  return [
    item.displayName,
    item.id,
    item.ipAddress,
    item.privateIpAddress,
    item.subnetName,
    item.vnicDisplayName,
    item.compartmentName,
  ].filter(Boolean).join(' ')
}

function shortId(id?: string) {
  if (!id) return '—'
  if (id.length <= 24) return id
  return `${id.slice(0, 12)}...${id.slice(-8)}`
}
</script>

<style scoped>
.rt-manager {
  min-height: 280px;
}

.rt-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.rt-search {
  width: 240px;
}

.rt-compartment {
  width: 240px;
}

.rt-warning {
  margin-bottom: 10px;
}

.rt-target-cell {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}

.rt-target-tag {
  flex: 0 0 auto;
  margin-top: 1px;
}

.rt-target-texts {
  min-width: 0;
}

.rt-main-text {
  color: var(--text-main);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rt-sub-text {
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rt-description {
  color: var(--text-main);
}

.rt-rule-form {
  margin-top: 4px;
}

.rt-form-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
}

.rt-option-title {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: var(--text-main);
}

.rt-option-sub {
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rt-manual-toggle {
  margin-top: 6px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--primary);
  cursor: pointer;
  font-size: 12px;
  line-height: 1;
}

.rt-manual-toggle:hover {
  color: var(--primary-hover);
}

@media (max-width: 720px) {
  .rt-search,
  .rt-compartment {
    width: 100%;
  }

  .rt-form-grid {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>
