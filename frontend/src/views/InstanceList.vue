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
        <a-button @click="loadInstances" :disabled="!selectedTenant">
          <template #icon><ReloadOutlined /></template>刷新
        </a-button>
      </a-space>
    </div>

    <a-table :columns="columns" :data-source="instances" :loading="loading" row-key="instanceId" size="middle">
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
            <a-button v-if="record.state === 'STOPPED'" type="link" size="small"
              @click="handleAction(record, 'START')">启动</a-button>
            <a-button v-if="record.state === 'RUNNING'" type="link" size="small"
              @click="handleAction(record, 'STOP')">停止</a-button>
            <a-button v-if="record.state === 'RUNNING'" type="link" size="small"
              @click="handleAction(record, 'RESET')">重启</a-button>
            <a-popconfirm title="确定终止实例？此操作不可逆！" @confirm="handleTerminate(record)">
              <a-button type="link" danger size="small">终止</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { getInstanceList, updateInstanceState, terminateInstance } from '../api/instance'
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
  { title: '操作', key: 'action', width: 200 },
]

const loading = ref(false)
const instances = ref<any[]>([])
const tenants = ref<any[]>([])
const selectedTenant = ref('')

async function loadTenants() {
  try {
    const res = await getTenantList({ current: 1, size: 1000 })
    tenants.value = res.data.records || []
  } catch { /* ignore */ }
}

async function loadInstances() {
  if (!selectedTenant.value) return
  loading.value = true
  try {
    const res = await getInstanceList({ id: selectedTenant.value })
    instances.value = res.data || []
  } catch { /* ignore */ } finally {
    loading.value = false
  }
}

async function handleAction(record: any, action: string) {
  try {
    await updateInstanceState({ id: selectedTenant.value, instanceId: record.instanceId, action })
    message.success('操作成功')
    setTimeout(loadInstances, 2000)
  } catch { /* ignore */ }
}

async function handleTerminate(record: any) {
  try {
    await terminateInstance({ id: selectedTenant.value, instanceId: record.instanceId })
    message.success('实例已终止')
    setTimeout(loadInstances, 3000)
  } catch { /* ignore */ }
}

onMounted(() => loadTenants())
</script>

<style scoped>
.table-toolbar { margin-bottom: 16px; }
</style>
