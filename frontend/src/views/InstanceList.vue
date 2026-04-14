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
            <a-button v-if="record.state === 'STOPPED'" type="link" size="small"
              :loading="actionLoading[record.instanceId]"
              @click="handleAction(record, 'START')">启动</a-button>
            <a-button v-if="record.state === 'RUNNING'" type="link" size="small"
              :loading="actionLoading[record.instanceId]"
              @click="handleAction(record, 'STOP')">停止</a-button>
            <a-button v-if="record.state === 'RUNNING'" type="link" size="small"
              :loading="actionLoading[record.instanceId]"
              @click="handleAction(record, 'RESET')">重启</a-button>
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
      width="720"
      placement="right"
    >
      <a-tabs v-model:activeKey="activeTab">
        <!-- 基本信息 -->
        <a-tab-pane key="info" tab="基本信息">
          <a-descriptions :column="1" bordered size="small" v-if="currentInstance">
            <a-descriptions-item label="实例名称">{{ currentInstance.name }}</a-descriptions-item>
            <a-descriptions-item label="实例 ID">
              <a-typography-text copyable style="font-size: 12px">{{ currentInstance.instanceId }}</a-typography-text>
            </a-descriptions-item>
            <a-descriptions-item label="Region">{{ currentInstance.region }}</a-descriptions-item>
            <a-descriptions-item label="Shape">{{ currentInstance.shape }}</a-descriptions-item>
            <a-descriptions-item label="配置">{{ currentInstance.ocpus }} OCPU / {{ currentInstance.memoryInGBs }} GB</a-descriptions-item>
            <a-descriptions-item label="公网 IP">
              <a-typography-text copyable v-if="currentInstance.publicIp">{{ currentInstance.publicIp }}</a-typography-text>
              <span v-else style="color: #999">无</span>
            </a-descriptions-item>
            <a-descriptions-item label="状态">
              <a-badge :status="stateColorMap[currentInstance.state] || 'default'" :text="currentInstance.state" />
            </a-descriptions-item>
          </a-descriptions>
          <a-divider />
          <a-space>
            <a-button v-if="currentInstance?.state === 'STOPPED'" type="primary"
              :loading="actionLoading[currentInstance?.instanceId]"
              @click="handleAction(currentInstance!, 'START')">启动</a-button>
            <a-button v-if="currentInstance?.state === 'RUNNING'"
              :loading="actionLoading[currentInstance?.instanceId]"
              @click="handleAction(currentInstance!, 'STOP')">停止</a-button>
            <a-button v-if="currentInstance?.state === 'RUNNING'"
              :loading="actionLoading[currentInstance?.instanceId]"
              @click="handleAction(currentInstance!, 'RESET')">重启</a-button>
            <a-button @click="handleChangeIp" :loading="changeIpLoading"
              :disabled="currentInstance?.state !== 'RUNNING'">换 IP</a-button>
            <a-popconfirm title="确定终止实例？" @confirm="handleTerminate(currentInstance!)">
              <a-button danger>终止</a-button>
            </a-popconfirm>
          </a-space>
        </a-tab-pane>

        <!-- 安全列表 -->
        <a-tab-pane key="security" tab="安全列表">
          <div style="margin-bottom: 12px">
            <a-space>
              <a-button @click="loadSecurityRules" :loading="secLoading">加载规则</a-button>
              <a-popconfirm title="确定一键放行所有端口？" @confirm="handleReleaseAll">
                <a-button type="primary" danger :loading="releaseLoading">一键放行</a-button>
              </a-popconfirm>
            </a-space>
          </div>
          <a-tabs size="small">
            <a-tab-pane key="ingress" tab="入站规则">
              <a-table :data-source="ingressRules" :columns="secColumns" size="small" :pagination="false" row-key="id" />
            </a-tab-pane>
            <a-tab-pane key="egress" tab="出站规则">
              <a-table :data-source="egressRules" :columns="secColumns" size="small" :pagination="false" row-key="id" />
            </a-tab-pane>
          </a-tabs>
        </a-tab-pane>

        <!-- 引导卷 -->
        <a-tab-pane key="volume" tab="引导卷">
          <a-button @click="loadBootVolumes" :loading="volLoading" style="margin-bottom: 12px">加载引导卷</a-button>
          <a-table :data-source="bootVolumes" :columns="volColumns" size="small" :pagination="false" row-key="id" />
        </a-tab-pane>

        <!-- 网络 -->
        <a-tab-pane key="network" tab="网络">
          <a-button @click="loadVcns" :loading="vcnLoading" style="margin-bottom: 12px">加载 VCN</a-button>
          <a-table :data-source="vcns" :columns="vcnColumns" size="small" :pagination="false" row-key="id" />
        </a-tab-pane>

        <!-- 流量统计 -->
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
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import {
  getInstanceList, updateInstanceState, terminateInstance,
  getSecurityRules, releaseAllPorts, getBootVolumes, getVcns,
  getTrafficData, changeIp,
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
  { title: '操作', key: 'action', width: 280 },
]

const secColumns = [
  { title: '协议', dataIndex: 'protocol', key: 'protocol', width: 80 },
  { title: '来源/目的', dataIndex: 'source', key: 'source' },
  { title: '端口范围', dataIndex: 'portRange', key: 'portRange', width: 120 },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
]

const volColumns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName' },
  { title: '大小 (GB)', dataIndex: 'sizeInGBs', key: 'sizeInGBs', width: 100 },
  { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
]

const vcnColumns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName' },
  { title: 'CIDR', dataIndex: 'cidrBlock', key: 'cidrBlock', width: 160 },
  { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
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

async function loadSecurityRules() {
  if (!currentInstance.value) return
  secLoading.value = true
  try {
    const res = await getSecurityRules({ id: selectedTenant.value, instanceId: currentInstance.value.instanceId })
    ingressRules.value = res.data?.ingress || []
    egressRules.value = res.data?.egress || []
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

async function loadBootVolumes() {
  volLoading.value = true
  try {
    const res = await getBootVolumes({ id: selectedTenant.value })
    bootVolumes.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载引导卷失败')
  } finally {
    volLoading.value = false
  }
}

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
