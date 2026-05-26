<template>
  <div class="vcn-item byoip-panel" :class="{ 'byoip-panel--collapsed': collapsed }">
    <div class="vcn-item-header">
      <div class="byoip-panel-title">
        <i class="ri-global-line" style="font-size: 18px; color: var(--primary)"></i>
        <span style="font-weight: 700">BYOIP 自有 IP</span>
      </div>
      <a-space>
        <a-button size="small" :loading="loading" @click="onRefresh">刷新</a-button>
        <a-button size="small" type="text" class="byoip-collapse-btn" @click="toggleCollapsed">
          <template v-if="collapsed">展开 <DownOutlined /></template>
          <template v-else>收起 <UpOutlined /></template>
        </a-button>
      </a-space>
    </div>

    <div v-show="!collapsed" class="byoip-panel-body">
    <a-spin :spinning="loading">
      <a-tabs v-model:activeKey="tab" size="small">
        <a-tab-pane key="help" tab="流程说明">
          <a-alert type="info" show-icon style="margin-bottom: 12px">
            <template #message>按 Oracle 官方 BYOIP 流程操作（当前区域生效）</template>
            <template #description>
              <ul class="byoip-help-list">
                <li v-for="(s, i) in helpSteps" :key="i">{{ s }}</li>
              </ul>
            </template>
          </a-alert>
          <a-descriptions :column="1" size="small" bordered>
            <a-descriptions-item label="Oracle BGP ASN">{{ helpMeta.oracleBgpAsn }}（商业云）</a-descriptions-item>
            <a-descriptions-item label="ASN 说明">{{ helpMeta.oracleBgpAsnNote }}</a-descriptions-item>
            <a-descriptions-item label="IPv4 网段">{{ helpMeta.ipv4CidrLimits }}</a-descriptions-item>
            <a-descriptions-item label="IPv6 前缀">{{ helpMeta.ipv6PrefixLimits }}</a-descriptions-item>
            <a-descriptions-item label="账户上限">约 {{ helpMeta.maxRangesPerTenancy }} 个网段/前缀</a-descriptions-item>
            <a-descriptions-item label="Free Tier">{{ helpMeta.freeTierSupported ? '支持' : '不支持' }}</a-descriptions-item>
            <a-descriptions-item label="校验周期">{{ helpMeta.validationDays }}</a-descriptions-item>
          </a-descriptions>
          <p class="vcn-panel-hint" style="margin-top: 10px">
            RIR 验证须添加 OCITOKEN 并创建 ROA；详见
            <a :href="helpMeta.docUrl" target="_blank" rel="noopener">Oracle BYOIP 文档</a>。
          </p>
        </a-tab-pane>

        <a-tab-pane key="ranges" tab="BYOIP 网段">
          <div class="op-row">
            <a-button type="primary" size="small" @click="openImportModal">导入网段</a-button>
          </div>
          <a-table
            :data-source="ranges"
            :columns="rangeColumns"
            row-key="id"
            size="small"
            :pagination="false"
            :scroll="{ x: 900 }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'cidr'">
                {{ record.cidrBlock || record.ipv6CidrBlock }}
                <a-tag size="small">{{ record.ipVersion }}</a-tag>
              </template>
              <template v-else-if="column.key === 'state'">
                <a-tag :color="stateColor(record.lifecycleState)">{{ record.lifecycleState }}</a-tag>
                <span v-if="record.lifecycleDetails" class="vcn-ip-meta">{{ record.lifecycleDetails }}</span>
              </template>
              <template v-else-if="column.key === 'action'">
                <a-dropdown>
                  <a-button size="small">操作 <DownOutlined /></a-button>
                  <template #overlay>
                    <a-menu @click="(info: { key: string | number }) => onRangeAction(String(info.key), record)">
                      <a-menu-item key="token">验证 Token / ROA</a-menu-item>
                      <a-menu-item key="validate">完成导入校验</a-menu-item>
                      <a-menu-item key="advertise">BGP 宣告</a-menu-item>
                      <a-menu-item key="withdraw">撤回宣告</a-menu-item>
                      <a-menu-item key="allocated">已分配子网</a-menu-item>
                      <a-menu-item v-if="record.ipVersion === 'IPV6'" key="ipv6vcn">分配到 VCN (IPv6)</a-menu-item>
                      <a-menu-item key="rename">重命名</a-menu-item>
                      <a-menu-item key="moveCompartment">移动到区间</a-menu-item>
                      <a-menu-divider />
                      <a-menu-item key="delete" danger>删除网段</a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </template>
            </template>
          </a-table>
          <a-empty v-if="!loading && ranges.length === 0" description="暂无 BYOIP 网段" />
        </a-tab-pane>

        <a-tab-pane key="pools" tab="公网 IP 池">
          <div class="op-row">
            <a-button type="primary" size="small" @click="openCreatePoolModal">创建 IP 池</a-button>
            <a-button size="small" @click="openAddCapacityModal">BYOIP 子网加入池</a-button>
          </div>
          <a-table :data-source="pools" :columns="poolColumns" row-key="id" size="small" :pagination="false">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'cidrs'">
                <a-tag v-for="c in (record.cidrBlocks || [])" :key="c" size="small">{{ c }}</a-tag>
              </template>
              <template v-else-if="column.key === 'action'">
                <a-space>
                  <a-button size="small" @click="openRenamePool(record)">重命名</a-button>
                  <a-popconfirm title="确定删除此 IP 池？须先移除池中 CIDR 且无占用 IP" @confirm="doDeletePool(record.id)">
                    <a-button size="small" danger>删除</a-button>
                  </a-popconfirm>
                </a-space>
              </template>
            </template>
          </a-table>
          <a-empty v-if="!loading && pools.length === 0" description="暂无公网 IP 池" />
        </a-tab-pane>

        <a-tab-pane key="ips" tab="BYOIP 公网 IP">
          <div class="op-row">
            <a-button type="primary" size="small" :disabled="poolOptions.length === 0" @click="openCreateIpModal">
              从 IP 池创建
            </a-button>
          </div>
          <div v-for="rip in publicIps" :key="rip.id" class="vcn-ip-row">
            <a-typography-text copyable>{{ rip.ipAddress }}</a-typography-text>
            <a-tag :color="rip.isAssigned ? 'green' : 'default'">{{ rip.isAssigned ? '已绑定' : '未绑定' }}</a-tag>
            <a-popconfirm v-if="!rip.isAssigned" title="确定删除？" @confirm="doDeletePublicIp(rip.id)">
              <a-button type="link" danger size="small">删除</a-button>
            </a-popconfirm>
            <a-button v-if="rip.isAssigned" type="link" size="small" @click="doUnassignPublicIp(rip.id)">解绑</a-button>
          </div>
          <a-empty v-if="!loading && publicIps.length === 0" description="暂无从 BYOIP 池创建的公网 IP" />
        </a-tab-pane>
      </a-tabs>
    </a-spin>
    </div>

    <!-- 导入 BYOIP -->
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="importVisible" title="导入 BYOIP 网段" :confirm-loading="submitting" @ok="submitImport">
      <a-form layout="vertical">
        <a-form-item label="名称"><a-input v-model:value="importForm.displayName" /></a-form-item>
        <a-form-item label="类型" required>
          <a-radio-group v-model:value="importForm.ipVersion">
            <a-radio value="IPV4">IPv4 CIDR</a-radio>
            <a-radio value="IPV6">IPv6 前缀</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="importForm.ipVersion === 'IPV4'" label="IPv4 CIDR" required>
          <a-input v-model:value="importForm.cidrBlock" placeholder="203.0.113.0/24（/24～/8）" />
        </a-form-item>
        <a-form-item v-else label="IPv6 前缀" required>
          <a-input v-model:value="importForm.ipv6CidrBlock" placeholder="2001:db8::/48（/48 或更大）" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 验证 Token -->
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="tokenVisible" title="RIR 验证 Token" :footer="null" width="640px">
      <a-alert type="warning" show-icon message="须将此串添加到 RIR，并创建 ROA 授权 Oracle ASN" style="margin-bottom: 12px" />
      <p class="vcn-panel-hint">提交给 RIR 的完整格式（非 API 原始 token）：</p>
      <a-typography-paragraph copyable>{{ tokenDetail.ociValidationToken }}</a-typography-paragraph>
      <a-descriptions :column="1" size="small" bordered>
        <a-descriptions-item label="原始 Token">{{ tokenDetail.validationToken }}</a-descriptions-item>
        <a-descriptions-item label="状态">{{ tokenDetail.lifecycleState }} / {{ tokenDetail.lifecycleDetails }}</a-descriptions-item>
      </a-descriptions>
    </a-modal>

    <!-- 已分配子网 -->
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="allocatedVisible" title="已分配到公网 IP 池的子网" :footer="null" width="720px">
      <a-table :data-source="allocatedRanges" :columns="allocatedColumns" row-key="cidrBlock" size="small" :pagination="false">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a-popconfirm
              title="从池中移除此 CIDR？须无占用 IP"
              @confirm="doRemoveCapacity(record)"
            >
              <a-button type="link" danger size="small">移除</a-button>
            </a-popconfirm>
          </template>
        </template>
      </a-table>
    </a-modal>

    <!-- 创建 IP 池 -->
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="createPoolVisible" title="创建公网 IP 池" :confirm-loading="submitting" @ok="submitCreatePool">
      <a-form layout="vertical">
        <a-form-item label="名称"><a-input v-model:value="poolForm.displayName" /></a-form-item>
      </a-form>
    </a-modal>

    <!-- 子网加入池 -->
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="addCapVisible" title="BYOIP 子网加入公网 IP 池" :confirm-loading="submitting" @ok="submitAddCapacity">
      <a-form layout="vertical">
        <a-form-item label="BYOIP 网段" required>
          <a-select v-model:value="capForm.byoipRangeId" :options="ipv4RangeOptions" show-search option-filter-prop="label" />
        </a-form-item>
        <a-form-item label="公网 IP 池" required>
          <a-select v-model:value="capForm.publicIpPoolId" :options="poolOptions" show-search option-filter-prop="label" />
        </a-form-item>
        <a-form-item label="子网 CIDR" required>
          <a-input v-model:value="capForm.cidrBlock" placeholder="可为整段或子范围，如 203.0.113.0/26" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 从池创建 IP -->
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="createIpVisible" title="从 BYOIP 池创建 Reserved 公网 IP" :confirm-loading="submitting" @ok="submitCreateIp">
      <a-form layout="vertical">
        <a-form-item label="公网 IP 池" required>
          <a-select v-model:value="ipForm.publicIpPoolId" :options="poolOptions" show-search />
        </a-form-item>
        <a-form-item label="名称（可选）"><a-input v-model:value="ipForm.displayName" /></a-form-item>
      </a-form>
    </a-modal>

    <!-- 重命名 -->
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="renameVisible" :title="renameTarget === 'range' ? '重命名 BYOIP 网段' : '重命名公网 IP 池'" :confirm-loading="submitting" @ok="submitRename">
      <a-input v-model:value="renameName" placeholder="新名称" />
    </a-modal>

    <!-- 移动 BYOIP 到区间 -->
    <a-modal :mask-closable="false" :keyboard="false"
      v-model:open="moveCompartmentVisible"
      title="移动 BYOIP 网段到区间"
      :confirm-loading="submitting"
      @ok="submitMoveCompartment"
    >
      <p v-if="moveCompartmentRangeLabel" class="vcn-panel-hint">网段：{{ moveCompartmentRangeLabel }}</p>
      <a-alert
        type="warning"
        show-icon
        message="同一地址段不能同时在多个区间各导入一份；移动后该网段下的 IP 池与公网 IP 归属随区间变化。"
        style="margin-bottom: 12px"
      />
      <a-form layout="vertical">
        <a-form-item label="目标区间" required>
          <a-select
            v-model:value="moveCompartmentTargetId"
            placeholder="选择目标 Compartment"
            :options="moveCompartmentOptions"
            :loading="moveCompartmentLoading"
            show-search
            option-filter-prop="label"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- IPv6 分配到 VCN -->
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="ipv6VcnVisible" title="BYOIPv6 分配到 VCN" :confirm-loading="submitting" @ok="submitIpv6Vcn">
      <a-form layout="vertical">
        <a-form-item label="目标 VCN" required>
          <a-select v-model:value="ipv6Form.vcnId" :options="vcnOptions" show-search option-filter-prop="label" :loading="vcnLoading" />
        </a-form-item>
        <a-form-item label="IPv6 子网前缀" required>
          <a-input v-model:value="ipv6Form.ipv6CidrBlock" placeholder="/64 或更大子前缀" />
        </a-form-item>
        <p class="vcn-panel-hint">IPv6 BYOIP 不使用公网 IP 池；分配后请在 VCN 子网启用 IPv6 并绑定实例。</p>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { DownOutlined, UpOutlined } from '@ant-design/icons-vue'
import {
  getByoipHelp,
  listByoipRanges,
  getByoipRange,
  createByoipRange,
  updateByoipRange,
  deleteByoipRange,
  validateByoipRange,
  advertiseByoipRange,
  withdrawByoipRange,
  changeByoipRangeCompartment,
  listByoipAllocatedRanges,
  listPublicIpPools,
  createPublicIpPool,
  updatePublicIpPool,
  deletePublicIpPool,
  addPublicIpPoolCapacity,
  removePublicIpPoolCapacity,
  createByoipPublicIp,
  listByoipPublicIps,
  assignByoipv6ToVcn,
} from '../api/byoip'
import { deleteReservedIp, unassignReservedIp } from '../api/instance'
import { getVcns } from '../api/instance'
import { listCompartmentPicker } from '../api/compartment'

const props = defineProps<{
  userId: string
  region?: string
}>()

const emit = defineEmits<{ changed: [] }>()

const BYOIP_COLLAPSED_KEY = 'ociworker.byoipPanel.collapsed'

function readCollapsed(): boolean {
  try {
    const v = localStorage.getItem(BYOIP_COLLAPSED_KEY)
    if (v === null) return true
    return v === '1' || v === 'true'
  } catch {
    return true
  }
}

const collapsed = ref(readCollapsed())

function toggleCollapsed() {
  collapsed.value = !collapsed.value
}

function onRefresh() {
  loadAll()
}

const tab = ref('help')
const loading = ref(false)
const submitting = ref(false)
const ranges = ref<any[]>([])
const pools = ref<any[]>([])
const publicIps = ref<any[]>([])
const helpSteps = ref<string[]>([])
const helpMeta = reactive<Record<string, any>>({
  oracleBgpAsn: 31898,
  oracleBgpAsnNote: '',
  ipv4CidrLimits: '',
  ipv6PrefixLimits: '',
  maxRangesPerTenancy: 20,
  freeTierSupported: false,
  validationDays: '',
  docUrl: 'https://docs.oracle.com/en-us/iaas/Content/Network/Concepts/BYOIP.htm',
})

const regionParam = () => ({ region: props.region?.trim() || undefined })

const rangeColumns = [
  { title: '名称', dataIndex: 'displayName', width: 120, ellipsis: true },
  { title: '网段', key: 'cidr', ellipsis: true },
  { title: '状态', key: 'state', width: 200 },
  { title: '操作', key: 'action', width: 100 },
]
const poolColumns = [
  { title: '名称', dataIndex: 'displayName', ellipsis: true },
  { title: 'CIDR', key: 'cidrs' },
  { title: '操作', key: 'action', width: 140 },
]
const allocatedColumns = [
  { title: 'CIDR', dataIndex: 'cidrBlock' },
  { title: 'IP 池 ID', dataIndex: 'publicIpPoolId', ellipsis: true },
  { title: '操作', key: 'action', width: 80 },
]
const currentAllocatedRangeId = ref('')

const poolOptions = computed(() =>
  pools.value.map(p => ({ value: p.id, label: `${p.displayName || p.id} (${(p.cidrBlocks || []).join(', ')})` })),
)
const ipv4RangeOptions = computed(() =>
  ranges.value
    .filter(r => r.ipVersion === 'IPV4')
    .map(r => ({ value: r.id, label: `${r.displayName || r.cidrBlock} (${r.cidrBlock})` })),
)

function stateColor(state?: string) {
  if (!state) return 'default'
  const s = state.toUpperCase()
  if (s === 'ACTIVE' || s === 'AVAILABLE') return 'success'
  if (s === 'UPDATING' || s === 'PROVISIONING') return 'processing'
  if (s === 'FAILED' || s === 'DELETED') return 'error'
  return 'default'
}

async function loadHelp() {
  try {
    const res = await getByoipHelp()
    const d = res.data || {}
    helpSteps.value = d.steps || []
    Object.assign(helpMeta, d)
  } catch { /* ignore */ }
}

async function loadAll() {
  if (!props.userId) return
  loading.value = true
  try {
    const p = { id: props.userId, ...regionParam() }
    const [rRes, pRes, ipRes] = await Promise.all([
      listByoipRanges(p),
      listPublicIpPools(p),
      listByoipPublicIps(p),
    ])
    ranges.value = rRes.data || []
    pools.value = pRes.data || []
    publicIps.value = ipRes.data || []
  } catch (e: any) {
    message.error(e?.message || '加载 BYOIP 失败')
  } finally {
    loading.value = false
  }
}

watch(collapsed, (v) => {
  try {
    localStorage.setItem(BYOIP_COLLAPSED_KEY, v ? '1' : '0')
  } catch { /* ignore */ }
  if (!v && props.userId) {
    loadAll()
  }
})

watch(() => [props.userId, props.region], () => {
  if (props.userId) {
    loadHelp()
    if (!collapsed.value) {
      loadAll()
    }
  }
}, { immediate: true })

// --- 网段 ---
const importVisible = ref(false)
const importForm = reactive({ displayName: '', ipVersion: 'IPV4' as 'IPV4' | 'IPV6', cidrBlock: '', ipv6CidrBlock: '' })

function openImportModal() {
  importForm.displayName = ''
  importForm.ipVersion = 'IPV4'
  importForm.cidrBlock = ''
  importForm.ipv6CidrBlock = ''
  importVisible.value = true
}

async function submitImport() {
  submitting.value = true
  try {
    const res = await createByoipRange({
      id: props.userId,
      displayName: importForm.displayName || undefined,
      cidrBlock: importForm.ipVersion === 'IPV4' ? importForm.cidrBlock : undefined,
      ipv6CidrBlock: importForm.ipVersion === 'IPV6' ? importForm.ipv6CidrBlock : undefined,
      ...regionParam(),
    })
    message.success('已创建导入请求')
    importVisible.value = false
    await loadAll()
    if (res.data?.ociValidationToken) {
      tokenDetail.value = res.data
      tokenVisible.value = true
    }
  } catch (e: any) {
    message.error(e?.message || '导入失败')
    return Promise.reject()
  } finally {
    submitting.value = false
  }
}

const tokenVisible = ref(false)
const tokenDetail = ref<any>({})
const allocatedVisible = ref(false)
const allocatedRanges = ref<any[]>([])

async function onRangeAction(key: string, record: any) {
  const id = record.id
  const p = { id: props.userId, byoipRangeId: id, ...regionParam() }
  if (key === 'token') {
    try {
      const res = await getByoipRange({ id: props.userId, byoipRangeId: id, ...regionParam() })
      tokenDetail.value = res.data || record
      tokenVisible.value = true
    } catch (e: any) {
      message.error(e?.message || '获取详情失败')
    }
    return
  }
  if (key === 'validate') {
    try {
      await validateByoipRange(p)
      message.success('已提交完成导入校验（Oracle 与 RIR 验证，最长约 10 个工作日）')
      loadAll()
    } catch (e: any) { message.error(e?.message || '失败') }
    return
  }
  if (key === 'advertise') {
    try {
      await advertiseByoipRange(p)
      message.success('已请求 BGP 宣告（须 PROVISIONED 状态）')
      loadAll()
    } catch (e: any) { message.error(e?.message || '失败') }
    return
  }
  if (key === 'withdraw') {
    try {
      await withdrawByoipRange(p)
      message.success('已请求撤回宣告')
      loadAll()
    } catch (e: any) { message.error(e?.message || '失败') }
    return
  }
  if (key === 'allocated') {
    try {
      const res = await listByoipAllocatedRanges(p)
      allocatedRanges.value = res.data || []
      currentAllocatedRangeId.value = id
      allocatedVisible.value = true
    } catch (e: any) { message.error(e?.message || '失败') }
    return
  }
  if (key === 'ipv6vcn') {
    ipv6Form.byoipRangeId = id
    ipv6Form.ipv6CidrBlock = record.ipv6CidrBlock || ''
    loadVcnOptions()
    ipv6VcnVisible.value = true
    return
  }
  if (key === 'rename') {
    renameTarget.value = 'range'
    renameId.value = id
    renameName.value = record.displayName || ''
    renameVisible.value = true
    return
  }
  if (key === 'moveCompartment') {
    moveCompartmentRangeId.value = id
    moveCompartmentCurrentCompartmentId.value = record.compartmentId || ''
    moveCompartmentRangeLabel.value =
      `${record.displayName || ''} · ${record.cidrBlock || record.ipv6CidrBlock || id}`.trim()
    moveCompartmentTargetId.value = undefined
    moveCompartmentVisible.value = true
    loadMoveCompartmentOptions()
    return
  }
  if (key === 'delete') {
    Modal.confirm({
      title: '删除 BYOIP 网段',
      content: '须先移除 IP 池中的子网且无占用；删除后需重新导入。',
      okType: 'danger',
      onOk: async () => {
        await deleteByoipRange(p)
        message.success('已删除')
        loadAll()
      },
    })
  }
}

// --- IP 池 ---
const createPoolVisible = ref(false)
const poolForm = reactive({ displayName: '' })
const addCapVisible = ref(false)
const capForm = reactive({ byoipRangeId: '', publicIpPoolId: '', cidrBlock: '' })

function openCreatePoolModal() {
  poolForm.displayName = ''
  createPoolVisible.value = true
}

async function submitCreatePool() {
  submitting.value = true
  try {
    await createPublicIpPool({ id: props.userId, displayName: poolForm.displayName || undefined, ...regionParam() })
    message.success('IP 池已创建')
    createPoolVisible.value = false
    loadAll()
  } catch (e: any) {
    message.error(e?.message || '失败')
    return Promise.reject()
  } finally {
    submitting.value = false
  }
}

function openAddCapacityModal() {
  capForm.byoipRangeId = ipv4RangeOptions.value[0]?.value || ''
  capForm.publicIpPoolId = poolOptions.value[0]?.value || ''
  capForm.cidrBlock = ''
  addCapVisible.value = true
}

async function submitAddCapacity() {
  if (!capForm.byoipRangeId || !capForm.publicIpPoolId || !capForm.cidrBlock) {
    message.warning('请填写完整')
    return Promise.reject()
  }
  submitting.value = true
  try {
    await addPublicIpPoolCapacity({
      id: props.userId,
      byoipRangeId: capForm.byoipRangeId,
      publicIpPoolId: capForm.publicIpPoolId,
      cidrBlock: capForm.cidrBlock,
      ...regionParam(),
    })
    message.success('已加入 IP 池')
    addCapVisible.value = false
    loadAll()
  } catch (e: any) {
    message.error(e?.message || '失败')
    return Promise.reject()
  } finally {
    submitting.value = false
  }
}

function openRenamePool(record: any) {
  renameTarget.value = 'pool'
  renameId.value = record.id
  renameName.value = record.displayName || ''
  renameVisible.value = true
}

const renameVisible = ref(false)
const renameTarget = ref<'range' | 'pool'>('range')
const renameId = ref('')
const renameName = ref('')

const moveCompartmentVisible = ref(false)
const moveCompartmentLoading = ref(false)
const moveCompartmentRangeId = ref('')
const moveCompartmentCurrentCompartmentId = ref('')
const moveCompartmentRangeLabel = ref('')
const moveCompartmentTargetId = ref<string | undefined>(undefined)
const moveCompartmentOptions = ref<{ label: string; value: string }[]>([])

async function loadMoveCompartmentOptions() {
  if (!props.userId) return
  moveCompartmentLoading.value = true
  try {
    const res = await listCompartmentPicker({ id: props.userId })
    const items = (res.data?.items || []) as { id: string; pathLabel?: string; name?: string }[]
    const exclude = moveCompartmentCurrentCompartmentId.value?.trim()
    moveCompartmentOptions.value = items
      .filter(c => !exclude || c.id !== exclude)
      .map(c => ({
        label: c.pathLabel || c.name || c.id,
        value: c.id,
      }))
  } catch (e: any) {
    moveCompartmentOptions.value = []
    message.error(e?.message || '加载区间列表失败')
  } finally {
    moveCompartmentLoading.value = false
  }
}

async function submitMoveCompartment() {
  if (!moveCompartmentRangeId.value || !moveCompartmentTargetId.value) {
    message.warning('请选择目标区间')
    return Promise.reject()
  }
  submitting.value = true
  try {
    await changeByoipRangeCompartment({
      id: props.userId,
      byoipRangeId: moveCompartmentRangeId.value,
      compartmentId: moveCompartmentTargetId.value,
      ...regionParam(),
    })
    message.success('BYOIP 网段已移动到新区间')
    moveCompartmentVisible.value = false
    loadAll()
  } catch (e: any) {
    message.error(e?.message || '移动失败')
    return Promise.reject()
  } finally {
    submitting.value = false
  }
}

async function submitRename() {
  submitting.value = true
  try {
    if (renameTarget.value === 'range') {
      await updateByoipRange({
        id: props.userId,
        byoipRangeId: renameId.value,
        displayName: renameName.value,
        ...regionParam(),
      })
    } else {
      await updatePublicIpPool({
        id: props.userId,
        publicIpPoolId: renameId.value,
        displayName: renameName.value,
        ...regionParam(),
      })
    }
    message.success('已更新')
    renameVisible.value = false
    loadAll()
  } catch (e: any) {
    message.error(e?.message || '失败')
    return Promise.reject()
  } finally {
    submitting.value = false
  }
}

async function doRemoveCapacity(record: any) {
  const poolId = record.publicIpPoolId
  if (!poolId || !record.cidrBlock) {
    message.warning('缺少池或 CIDR 信息')
    return
  }
  try {
    await removePublicIpPoolCapacity({
      id: props.userId,
      publicIpPoolId: poolId,
      cidrBlock: record.cidrBlock,
      ...regionParam(),
    })
    message.success('已从 IP 池移除')
    const res = await listByoipAllocatedRanges({
      id: props.userId,
      byoipRangeId: currentAllocatedRangeId.value,
      ...regionParam(),
    })
    allocatedRanges.value = res.data || []
    loadAll()
  } catch (e: any) {
    message.error(e?.message || '失败')
  }
}

async function doDeletePool(poolId: string) {
  try {
    await deletePublicIpPool({ id: props.userId, publicIpPoolId: poolId, ...regionParam() })
    message.success('已删除')
    loadAll()
  } catch (e: any) { message.error(e?.message || '失败') }
}

// --- 公网 IP ---
const createIpVisible = ref(false)
const ipForm = reactive({ publicIpPoolId: '', displayName: '' })

function openCreateIpModal() {
  ipForm.publicIpPoolId = poolOptions.value[0]?.value || ''
  ipForm.displayName = ''
  createIpVisible.value = true
}

async function submitCreateIp() {
  if (!ipForm.publicIpPoolId) {
    message.warning('请选择 IP 池')
    return Promise.reject()
  }
  submitting.value = true
  try {
    const res = await createByoipPublicIp({
      id: props.userId,
      publicIpPoolId: ipForm.publicIpPoolId,
      displayName: ipForm.displayName || undefined,
      ...regionParam(),
    })
    message.success('已创建: ' + (res.data?.ipAddress || ''))
    createIpVisible.value = false
    loadAll()
    emit('changed')
  } catch (e: any) {
    message.error(e?.message || '失败')
    return Promise.reject()
  } finally {
    submitting.value = false
  }
}

async function doDeletePublicIp(publicIpId: string) {
  try {
    await deleteReservedIp({ id: props.userId, publicIpId, ...regionParam() })
    message.success('已删除')
    loadAll()
    emit('changed')
  } catch (e: any) { message.error(e?.message || '失败') }
}

async function doUnassignPublicIp(publicIpId: string) {
  try {
    await unassignReservedIp({ id: props.userId, publicIpId, ...regionParam() })
    message.success('已解绑')
    loadAll()
    emit('changed')
  } catch (e: any) { message.error(e?.message || '失败') }
}

// --- IPv6 VCN ---
const ipv6VcnVisible = ref(false)
const ipv6Form = reactive({ byoipRangeId: '', vcnId: '', ipv6CidrBlock: '' })
const vcnOptions = ref<{ value: string; label: string }[]>([])
const vcnLoading = ref(false)

async function loadVcnOptions() {
  vcnLoading.value = true
  try {
    const res = await getVcns({ id: props.userId, ...regionParam() })
    vcnOptions.value = (res.data || []).map((v: any) => ({
      value: v.id,
      label: `${v.displayName} (${(v.cidrBlocks || [v.cidrBlock]).filter(Boolean).join(', ')})`,
    }))
  } finally {
    vcnLoading.value = false
  }
}

async function submitIpv6Vcn() {
  if (!ipv6Form.vcnId || !ipv6Form.byoipRangeId || !ipv6Form.ipv6CidrBlock) {
    message.warning('请填写完整')
    return Promise.reject()
  }
  submitting.value = true
  try {
    await assignByoipv6ToVcn({
      id: props.userId,
      vcnId: ipv6Form.vcnId,
      byoipRangeId: ipv6Form.byoipRangeId,
      ipv6CidrBlock: ipv6Form.ipv6CidrBlock,
      ...regionParam(),
    })
    message.success('BYOIPv6 已分配到 VCN')
    ipv6VcnVisible.value = false
    loadAll()
  } catch (e: any) {
    message.error(e?.message || '失败')
    return Promise.reject()
  } finally {
    submitting.value = false
  }
}

defineExpose({ loadAll })
</script>

<style scoped>
.byoip-panel {
  margin-top: 12px;
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 12px;
}
.byoip-panel--collapsed {
  padding-bottom: 16px;
}
.vcn-item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.byoip-panel--collapsed .vcn-item-header {
  margin-bottom: 0;
}
.byoip-panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.byoip-collapse-btn {
  padding-inline: 4px;
  color: var(--text-sub);
}
.byoip-collapse-btn:hover {
  color: var(--primary);
}
.op-row { margin-bottom: 10px; display: flex; gap: 8px; flex-wrap: wrap; }
.byoip-help-list { margin: 0; padding-left: 18px; font-size: 13px; }
.vcn-panel-hint { margin: 0 0 10px; font-size: 12px; color: var(--text-sub); line-height: 1.5; }
.vcn-ip-row {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  padding: 6px 0; font-size: 13px;
}
.vcn-ip-meta { color: var(--text-sub); font-size: 12px; }
</style>
