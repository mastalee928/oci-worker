<template>
  <div>
    <div class="table-toolbar">
      <a-space>
        <a-select v-model:value="selectedTenant" placeholder="选择租户查看实例" style="width: 300px" show-search
          option-filter-prop="label" @change="loadInstances">
          <a-select-option v-for="t in tenants" :key="t.id" :value="t.id" :label="t.username">
            {{ t.username }} ({{ t.ociRegion }})
          </a-select-option>
        </a-select>
        <a-button @click="loadInstances" :disabled="!selectedTenant" :loading="loading">
          <template #icon><ReloadOutlined /></template>刷新
        </a-button>
      </a-space>
    </div>

    <a-empty v-if="!selectedTenant" description="请先选择租户" style="margin-top: 80px" />

    <a-table v-else :columns="columns" :data-source="instances" :loading="loading" row-key="instanceId" size="middle">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'state'">
          <a-badge :status="stateColorMap[record.state] || 'default'" :text="record.state" />
        </template>
        <template v-if="column.key === 'shape'">
          <a-tooltip :title="`${record.ocpus} OCPU / ${record.memoryInGBs} GB`">
            <a-tag>{{ record.shape }}</a-tag>
          </a-tooltip>
        </template>
        <template v-if="column.key === 'action'">
          <a-space>
            <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
            <a-popconfirm v-if="record.state === 'STOPPED'" title="确定启动实例？" @confirm="handleAction(record, 'START')">
              <a-button type="link" size="small" :loading="actionLoading[record.instanceId]">启动</a-button>
            </a-popconfirm>
            <a-popconfirm v-if="record.state === 'RUNNING'" title="确定停止实例？" @confirm="handleAction(record, 'STOP')">
              <a-button type="link" size="small" :loading="actionLoading[record.instanceId]">停止</a-button>
            </a-popconfirm>
            <a-popconfirm v-if="record.state === 'RUNNING'" title="确定重启实例？" @confirm="handleAction(record, 'RESET')">
              <a-button type="link" size="small" :loading="actionLoading[record.instanceId]">重启</a-button>
            </a-popconfirm>
            <a-popconfirm title="确定终止实例？此操作不可逆！" @confirm="handleTerminate(record)">
              <a-button type="link" danger size="small">终止</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 实例详情抽屉 -->
    <a-drawer
      v-model:open="drawerVisible"
      :title="currentInstance?.name || '实例详情'"
      width="780"
      placement="right"
      :mask-closable="false"
    >
      <a-tabs v-model:activeKey="activeTab">
        <a-tab-pane key="info" tab="基本信息">
          <a-descriptions :column="1" bordered size="small" v-if="currentInstance">
            <a-descriptions-item label="实例名称">{{ currentInstance.name }}</a-descriptions-item>
            <a-descriptions-item label="实例 ID">
              <a-typography-text copyable style="font-size: 12px">{{ currentInstance.instanceId }}</a-typography-text>
            </a-descriptions-item>
            <a-descriptions-item label="Region">{{ currentInstance.region }}</a-descriptions-item>
            <a-descriptions-item label="Shape">{{ currentInstance.shape }}</a-descriptions-item>
            <a-descriptions-item label="配置">{{ currentInstance.ocpus }} OCPU / {{ currentInstance.memoryInGBs }} GB</a-descriptions-item>
            <a-descriptions-item label="状态">
              <a-badge :status="stateColorMap[currentInstance.state] || 'default'" :text="currentInstance.state" />
            </a-descriptions-item>
          </a-descriptions>

          <!-- 网络详情 -->
          <a-divider orientation="left">网络信息</a-divider>
          <a-button size="small" @click="loadNetworkDetail" :loading="netDetailLoading" style="margin-bottom: 12px">
            {{ networkDetail ? '刷新网络信息' : '加载网络信息' }}
          </a-button>

          <template v-if="networkDetail">
            <div v-for="(vnic, vi) in networkDetail.vnics" :key="vi" style="margin-bottom: 16px">
              <a-descriptions :column="1" bordered size="small" :title="'VNIC: ' + (vnic.displayName || vnic.vnicId)">
                <a-descriptions-item v-for="(ipd, idx) in vnic.ipDetails" :key="idx"
                  :label="ipd.isPrimary ? '主IP' : '辅助IP'">
                  <div style="display: flex; align-items: center; flex-wrap: wrap; gap: 8px;">
                    <template v-if="ipd.publicIpAddress">
                      <span>公网IP</span>
                      <a-tag :color="ipd.publicIpLifetime === 'RESERVED' ? 'green' : 'orange'">
                        {{ ipd.publicIpLifetime === 'RESERVED' ? '预留' : '临时' }}
                      </a-tag>
                      <a-typography-text copyable>{{ ipd.publicIpAddress }}</a-typography-text>
                      <span style="color: #999">( {{ ipd.privateIpAddress }} )</span>
                    </template>
                    <template v-else>
                      <span>内网IP:</span>
                      <a-typography-text copyable>{{ ipd.privateIpAddress }}</a-typography-text>
                      <span style="color: #999">（无公网IP）</span>
                    </template>
                  </div>
                </a-descriptions-item>
                <a-descriptions-item label="IPv6 地址">
                  <template v-if="vnic.ipv6Addresses && vnic.ipv6Addresses.length > 0">
                    <div v-for="(ip6, i6) in vnic.ipv6Addresses" :key="i6">
                      <a-typography-text copyable>{{ ip6 }}</a-typography-text>
                    </div>
                  </template>
                  <span v-else style="color: #999">
                    无
                    <a-button type="link" size="small" @click="handleAddIpv6" :loading="ipv6Loading">添加 IPv6</a-button>
                  </span>
                </a-descriptions-item>
              </a-descriptions>
            </div>
          </template>

          <a-divider />
          <a-space>
            <a-popconfirm v-if="currentInstance?.state === 'STOPPED'" title="确定启动？" @confirm="handleAction(currentInstance!, 'START')">
              <a-button type="primary" :loading="actionLoading[currentInstance?.instanceId]">启动</a-button>
            </a-popconfirm>
            <a-popconfirm v-if="currentInstance?.state === 'RUNNING'" title="确定停止？" @confirm="handleAction(currentInstance!, 'STOP')">
              <a-button :loading="actionLoading[currentInstance?.instanceId]">停止</a-button>
            </a-popconfirm>
            <a-popconfirm v-if="currentInstance?.state === 'RUNNING'" title="确定重启？" @confirm="handleAction(currentInstance!, 'RESET')">
              <a-button :loading="actionLoading[currentInstance?.instanceId]">重启</a-button>
            </a-popconfirm>
            <a-popconfirm title="确定换 IP？" @confirm="handleChangeIp">
              <a-button :loading="changeIpLoading" :disabled="currentInstance?.state !== 'RUNNING'">换 IP</a-button>
            </a-popconfirm>
            <a-popconfirm title="确定终止实例？此操作不可逆！" @confirm="handleTerminate(currentInstance!)">
              <a-button danger>终止</a-button>
            </a-popconfirm>
          </a-space>
        </a-tab-pane>

        <a-tab-pane key="security" tab="安全列表">
          <div style="margin-bottom: 12px">
            <a-space>
              <a-button @click="loadSecurityRules" :loading="secLoading">加载规则</a-button>
              <a-button type="primary" @click="showAddRuleModal">添加规则</a-button>
              <a-popconfirm title="确定一键放行所有端口？" @confirm="handleReleaseAll">
                <a-button type="primary" danger :loading="releaseLoading">一键放行</a-button>
              </a-popconfirm>
            </a-space>
          </div>
          <a-tabs size="small">
            <a-tab-pane key="ingress" tab="入站规则">
              <a-table :data-source="ingressRules" :columns="secColumns" size="small" :pagination="false" />
            </a-tab-pane>
            <a-tab-pane key="egress" tab="出站规则">
              <a-table :data-source="egressRules" :columns="secColumns" size="small" :pagination="false" />
            </a-tab-pane>
          </a-tabs>
        </a-tab-pane>

        <a-tab-pane key="volume" tab="引导卷">
          <a-button @click="loadBootVolumes" :loading="volLoading" style="margin-bottom: 12px">加载引导卷</a-button>
          <a-table :data-source="bootVolumes" :columns="volColumns" size="small" :pagination="false" row-key="id">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'volAction'">
                <a-button type="link" size="small" @click="openEditVolume(record)">编辑</a-button>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="reservedIp" tab="预留IP">
          <div style="margin-bottom: 12px">
            <a-space>
              <a-button @click="loadReservedIps" :loading="reservedIpListLoading">加载预留IP</a-button>
              <a-button type="primary" @click="showCreateReservedIpModal">新建预留IP</a-button>
            </a-space>
          </div>
          <a-table :data-source="reservedIps" :columns="reservedIpColumns" size="small" :pagination="false" row-key="id">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'ripStatus'">
                <a-tag :color="record.isAssigned ? 'green' : 'default'">
                  {{ record.isAssigned ? '已绑定' : '未绑定' }}
                </a-tag>
              </template>
              <template v-if="column.key === 'ripAction'">
                <a-space>
                  <a-button v-if="!record.isAssigned && currentInstance" type="link" size="small"
                    @click="handleAssignReservedIp(record.id)">
                    绑定到当前实例
                  </a-button>
                  <a-button v-if="record.isAssigned" type="link" size="small"
                    @click="handleUnassignReservedIp(record.id)">
                    解绑
                  </a-button>
                  <a-popconfirm title="确定删除此预留IP？" @confirm="handleDeleteReservedIp(record.id)">
                    <a-button type="link" danger size="small" :disabled="record.isAssigned">删除</a-button>
                  </a-popconfirm>
                </a-space>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="network" tab="网络">
          <a-button @click="loadVcns" :loading="vcnLoading" style="margin-bottom: 12px">加载 VCN</a-button>
          <a-table :data-source="vcns" :columns="vcnColumns" size="small" :pagination="false" row-key="id" />
        </a-tab-pane>

        <a-tab-pane key="traffic" tab="流量统计">
          <a-space style="margin-bottom: 12px">
            <a-select v-model:value="trafficMinutes" style="width: 140px">
              <a-select-option :value="60">最近 1 小时</a-select-option>
              <a-select-option :value="360">最近 6 小时</a-select-option>
              <a-select-option :value="1440">最近 24 小时</a-select-option>
            </a-select>
            <a-button @click="loadTraffic" :loading="trafficLoading">查询</a-button>
          </a-space>
          <a-empty v-if="!trafficData" description="点击查询加载流量数据" />
          <a-descriptions v-else :column="1" bordered size="small">
            <a-descriptions-item label="入站流量">{{ formatBytes(trafficData.inbound) }}</a-descriptions-item>
            <a-descriptions-item label="出站流量">{{ formatBytes(trafficData.outbound) }}</a-descriptions-item>
          </a-descriptions>
        </a-tab-pane>
      </a-tabs>
    </a-drawer>

    <!-- 添加安全规则弹窗 -->
    <a-modal v-model:open="addRuleVisible" title="添加安全规则" @ok="handleAddRule"
      :confirm-loading="addRuleLoading" :mask-closable="false">
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
        <a-form-item label="端口范围" v-if="ruleForm.protocol === 'TCP' || ruleForm.protocol === 'UDP'">
          <a-space>
            <a-input-number v-model:value="ruleForm.portMin" placeholder="起始端口" :min="1" :max="65535" style="width: 140px" />
            <span>-</span>
            <a-input-number v-model:value="ruleForm.portMax" placeholder="结束端口" :min="1" :max="65535" style="width: 140px" />
          </a-space>
        </a-form-item>
        <a-form-item label="描述">
          <a-input v-model:value="ruleForm.description" placeholder="可选" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 编辑引导卷弹窗 -->
    <a-modal v-model:open="editVolVisible" title="编辑引导卷" @ok="handleEditVolume"
      :confirm-loading="editVolLoading" :mask-closable="false">
      <a-form layout="vertical">
        <a-form-item label="名称">
          <a-input v-model:value="editVolForm.displayName" />
        </a-form-item>
        <a-form-item label="大小 (GB)">
          <a-input-number v-model:value="editVolForm.sizeInGBs" :min="50" :max="32768" style="width: 100%" />
          <div style="color: #999; font-size: 12px; margin-top: 4px">只能增大，不能缩小。最小 50 GB</div>
        </a-form-item>
        <a-form-item label="性能 (VPUs/GB)">
          <a-select v-model:value="editVolForm.vpusPerGB">
            <a-select-option :value="0">最低成本 (0)</a-select-option>
            <a-select-option :value="10">均衡 (10)</a-select-option>
            <a-select-option :value="20">较高性能 (20)</a-select-option>
            <a-select-option :value="30">高性能 (30)</a-select-option>
            <a-select-option :value="120">超高性能 (120)</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 新建预留IP弹窗 -->
    <a-modal v-model:open="createRipVisible" title="新建预留IP" @ok="handleCreateReservedIp"
      :confirm-loading="createRipLoading" :mask-closable="false">
      <a-form layout="vertical">
        <a-form-item label="名称（可选）">
          <a-input v-model:value="createRipName" placeholder="reserved-ip" />
        </a-form-item>
        <div style="color: #999; font-size: 12px">
          创建一个未绑定的预留IP。创建后可在列表中绑定到实例。
        </div>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import {
  getInstanceList, updateInstanceState, terminateInstance,
  getSecurityRules, releaseAllPorts, addSecurityRule,
  getBootVolumes, updateBootVolume, getVcns,
  getTrafficData, changeIp,
  getInstanceNetworkDetail, addIpv6,
  createReservedIp, listReservedIps, deleteReservedIp,
  assignReservedIp, unassignReservedIp,
} from '../api/instance'
import { getTenantList } from '../api/tenant'

const stateColorMap: Record<string, string> = {
  RUNNING: 'success', STOPPED: 'error', STARTING: 'processing',
  STOPPING: 'warning', TERMINATED: 'default',
}

const columns = [
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: 'Region', dataIndex: 'region', key: 'region', width: 140 },
  { title: 'Shape', key: 'shape', width: 200 },
  { title: '公网 IP', dataIndex: 'publicIp', key: 'publicIp', width: 140 },
  { title: '状态', dataIndex: 'state', key: 'state', width: 100 },
  { title: '操作', key: 'action', width: 300 },
]

const secColumns = [
  { title: '协议', dataIndex: 'protocol', key: 'protocol', width: 80,
    customRender: ({ text }: any) => {
      const map: Record<string, string> = { '6': 'TCP', '17': 'UDP', '1': 'ICMP', '58': 'ICMPv6', 'all': '全部' }
      return map[text] || text
    }
  },
  { title: '来源/目的', dataIndex: 'source', key: 'source' },
  { title: '端口范围', dataIndex: 'portRange', key: 'portRange', width: 120 },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
]

const volColumns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName' },
  { title: '大小 (GB)', dataIndex: 'sizeInGBs', key: 'sizeInGBs', width: 100 },
  { title: '性能 (VPUs/GB)', dataIndex: 'vpusPerGB', key: 'vpusPerGB', width: 130 },
  { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
  { title: '操作', key: 'volAction', width: 80 },
]

const vcnColumns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName' },
  { title: 'CIDR', dataIndex: 'cidrBlock', key: 'cidrBlock', width: 160 },
  { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
]

const reservedIpColumns = [
  { title: 'IP 地址', dataIndex: 'ipAddress', key: 'ipAddress', width: 150 },
  { title: '名称', dataIndex: 'displayName', key: 'displayName' },
  { title: '绑定状态', key: 'ripStatus', width: 100 },
  { title: '操作', key: 'ripAction', width: 250 },
]

const loading = ref(false)
const instances = ref<any[]>([])
const tenants = ref<any[]>([])
const selectedTenant = ref('')
const actionLoading = reactive<Record<string, boolean>>({})

const drawerVisible = ref(false)
const activeTab = ref('info')
const currentInstance = ref<any>(null)

const secLoading = ref(false)
const releaseLoading = ref(false)
const ingressRules = ref<any[]>([])
const egressRules = ref<any[]>([])

const volLoading = ref(false)
const bootVolumes = ref<any[]>([])

const vcnLoading = ref(false)
const vcns = ref<any[]>([])

const trafficLoading = ref(false)
const trafficMinutes = ref(60)
const trafficData = ref<any>(null)
const changeIpLoading = ref(false)

const netDetailLoading = ref(false)
const networkDetail = ref<any>(null)
const ipv6Loading = ref(false)

const addRuleVisible = ref(false)
const addRuleLoading = ref(false)
const ruleForm = reactive({
  direction: 'ingress',
  protocol: 'TCP',
  source: '0.0.0.0/0',
  portMin: null as number | null,
  portMax: null as number | null,
  description: '',
})

const editVolVisible = ref(false)
const editVolLoading = ref(false)
const editVolForm = reactive({
  bootVolumeId: '',
  displayName: '',
  sizeInGBs: 50,
  vpusPerGB: 10,
})

const reservedIps = ref<any[]>([])
const reservedIpListLoading = ref(false)
const createRipVisible = ref(false)
const createRipLoading = ref(false)
const createRipName = ref('')

function formatBytes(bytes: number) {
  if (!bytes || bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return (bytes / Math.pow(1024, i)).toFixed(2) + ' ' + units[i]
}

async function loadTenants() {
  try {
    const res = await getTenantList({ current: 1, size: 1000 })
    tenants.value = res.data.records || []
  } catch (e: any) {
    message.error(e?.message || '加载租户失败')
  }
}

async function loadInstances() {
  if (!selectedTenant.value) return
  loading.value = true
  try {
    const res = await getInstanceList({ id: selectedTenant.value })
    instances.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载实例失败')
  } finally {
    loading.value = false
  }
}

function openDetail(record: any) {
  currentInstance.value = record
  activeTab.value = 'info'
  ingressRules.value = []
  egressRules.value = []
  bootVolumes.value = []
  vcns.value = []
  trafficData.value = null
  networkDetail.value = null
  reservedIps.value = []
  drawerVisible.value = true
}

async function handleAction(record: any, action: string) {
  actionLoading[record.instanceId] = true
  try {
    await updateInstanceState({ id: selectedTenant.value, instanceId: record.instanceId, action })
    message.success('操作已提交')
    setTimeout(loadInstances, 3000)
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    actionLoading[record.instanceId] = false
  }
}

async function handleTerminate(record: any) {
  try {
    await terminateInstance({ id: selectedTenant.value, instanceId: record.instanceId })
    message.success('实例已终止')
    drawerVisible.value = false
    setTimeout(loadInstances, 3000)
  } catch (e: any) {
    message.error(e?.message || '终止失败')
  }
}

async function handleChangeIp() {
  if (!currentInstance.value) return
  changeIpLoading.value = true
  try {
    await changeIp({ id: selectedTenant.value, instanceId: currentInstance.value.instanceId })
    message.success('换 IP 请求已提交')
    setTimeout(loadInstances, 5000)
  } catch (e: any) {
    message.error(e?.message || '换 IP 失败')
  } finally {
    changeIpLoading.value = false
  }
}

// --- 网络详情 ---
async function loadNetworkDetail() {
  if (!currentInstance.value) return
  netDetailLoading.value = true
  try {
    const res = await getInstanceNetworkDetail({ id: selectedTenant.value, instanceId: currentInstance.value.instanceId })
    networkDetail.value = res.data || null
  } catch (e: any) {
    message.error(e?.message || '加载网络详情失败')
  } finally {
    netDetailLoading.value = false
  }
}

async function handleAddIpv6() {
  if (!currentInstance.value) return
  ipv6Loading.value = true
  try {
    const res = await addIpv6({ id: selectedTenant.value, instanceId: currentInstance.value.instanceId })
    message.success('IPv6 已添加: ' + (res.data?.ipv6Address || ''))
    loadNetworkDetail()
  } catch (e: any) {
    message.error(e?.message || '添加 IPv6 失败')
  } finally {
    ipv6Loading.value = false
  }
}

// --- 安全列表 ---
async function loadSecurityRules() {
  if (!currentInstance.value) return
  secLoading.value = true
  try {
    const res = await getSecurityRules({ id: selectedTenant.value, instanceId: currentInstance.value.instanceId })
    const data = res.data || []
    ingressRules.value = data.filter((r: any) => r.direction === 'ingress')
    egressRules.value = data.filter((r: any) => r.direction === 'egress')
  } catch (e: any) {
    message.error(e?.message || '加载安全规则失败')
  } finally {
    secLoading.value = false
  }
}

async function handleReleaseAll() {
  if (!currentInstance.value) return
  releaseLoading.value = true
  try {
    await releaseAllPorts({ id: selectedTenant.value, instanceId: currentInstance.value.instanceId })
    message.success('已放行所有端口')
    loadSecurityRules()
  } catch (e: any) {
    message.error(e?.message || '放行失败')
  } finally {
    releaseLoading.value = false
  }
}

function showAddRuleModal() {
  ruleForm.direction = 'ingress'
  ruleForm.protocol = 'TCP'
  ruleForm.source = '0.0.0.0/0'
  ruleForm.portMin = null
  ruleForm.portMax = null
  ruleForm.description = ''
  addRuleVisible.value = true
}

async function handleAddRule() {
  if (!currentInstance.value) return
  if ((ruleForm.protocol === 'TCP' || ruleForm.protocol === 'UDP') && (!ruleForm.portMin || !ruleForm.portMax)) {
    message.warning('TCP/UDP 协议需要填写端口范围')
    return
  }
  addRuleLoading.value = true
  try {
    await addSecurityRule({
      id: selectedTenant.value,
      instanceId: currentInstance.value.instanceId,
      direction: ruleForm.direction,
      protocol: ruleForm.protocol,
      source: ruleForm.source,
      portMin: ruleForm.portMin?.toString(),
      portMax: ruleForm.portMax?.toString(),
      description: ruleForm.description,
    })
    message.success('规则已添加')
    addRuleVisible.value = false
    loadSecurityRules()
  } catch (e: any) {
    message.error(e?.message || '添加规则失败')
  } finally {
    addRuleLoading.value = false
  }
}

// --- 引导卷 ---
async function loadBootVolumes() {
  if (!currentInstance.value) return
  volLoading.value = true
  try {
    const res = await getBootVolumes({ id: selectedTenant.value, instanceId: currentInstance.value.instanceId })
    bootVolumes.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载引导卷失败')
  } finally {
    volLoading.value = false
  }
}

function openEditVolume(record: any) {
  editVolForm.bootVolumeId = record.id
  editVolForm.displayName = record.displayName
  editVolForm.sizeInGBs = record.sizeInGBs
  editVolForm.vpusPerGB = record.vpusPerGB ?? 10
  editVolVisible.value = true
}

async function handleEditVolume() {
  editVolLoading.value = true
  try {
    await updateBootVolume({
      id: selectedTenant.value,
      bootVolumeId: editVolForm.bootVolumeId,
      displayName: editVolForm.displayName,
      sizeInGBs: editVolForm.sizeInGBs,
      vpusPerGB: editVolForm.vpusPerGB,
    })
    message.success('引导卷已更新')
    editVolVisible.value = false
    loadBootVolumes()
  } catch (e: any) {
    message.error(e?.message || '更新引导卷失败')
  } finally {
    editVolLoading.value = false
  }
}

// --- 预留IP ---
function showCreateReservedIpModal() {
  createRipName.value = ''
  createRipVisible.value = true
}

async function handleCreateReservedIp() {
  createRipLoading.value = true
  try {
    const res = await createReservedIp({ id: selectedTenant.value, displayName: createRipName.value || undefined })
    message.success('预留IP已创建: ' + (res.data?.ipAddress || ''))
    createRipVisible.value = false
    loadReservedIps()
  } catch (e: any) {
    message.error(e?.message || '创建预留IP失败')
  } finally {
    createRipLoading.value = false
  }
}

async function loadReservedIps() {
  reservedIpListLoading.value = true
  try {
    const res = await listReservedIps({ id: selectedTenant.value })
    reservedIps.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载预留IP失败')
  } finally {
    reservedIpListLoading.value = false
  }
}

async function handleDeleteReservedIp(publicIpId: string) {
  try {
    await deleteReservedIp({ id: selectedTenant.value, publicIpId })
    message.success('预留IP已删除')
    loadReservedIps()
  } catch (e: any) {
    message.error(e?.message || '删除预留IP失败')
  }
}

async function handleAssignReservedIp(publicIpId: string) {
  if (!currentInstance.value) return
  try {
    await assignReservedIp({ id: selectedTenant.value, publicIpId, instanceId: currentInstance.value.instanceId })
    message.success('预留IP已绑定到当前实例')
    loadReservedIps()
    loadNetworkDetail()
  } catch (e: any) {
    message.error(e?.message || '绑定预留IP失败')
  }
}

async function handleUnassignReservedIp(publicIpId: string) {
  try {
    await unassignReservedIp({ id: selectedTenant.value, publicIpId })
    message.success('预留IP已解绑')
    loadReservedIps()
    loadNetworkDetail()
  } catch (e: any) {
    message.error(e?.message || '解绑预留IP失败')
  }
}

// --- VCN / 流量 ---
async function loadVcns() {
  vcnLoading.value = true
  try {
    const res = await getVcns({ id: selectedTenant.value })
    vcns.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载 VCN 失败')
  } finally {
    vcnLoading.value = false
  }
}

async function loadTraffic() {
  if (!currentInstance.value) return
  trafficLoading.value = true
  try {
    const res = await getTrafficData({
      id: selectedTenant.value,
      instanceId: currentInstance.value.instanceId,
      minutes: trafficMinutes.value,
    })
    trafficData.value = res.data || { inbound: 0, outbound: 0 }
  } catch (e: any) {
    message.error(e?.message || '加载流量数据失败')
  } finally {
    trafficLoading.value = false
  }
}

onMounted(() => loadTenants())
</script>

<style scoped>
.table-toolbar { margin-bottom: 16px; }
</style>
