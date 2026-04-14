<template>
  <div>
    <div class="table-toolbar">
      <a-space>
        <a-button type="primary" @click="showCreateModal">
          <template #icon><PlusOutlined /></template>创建开机任务
        </a-button>
        <a-button @click="loadData" :loading="loading">
          <template #icon><ReloadOutlined /></template>刷新
        </a-button>
      </a-space>
    </div>

    <a-table :columns="columns" :data-source="tableData" :loading="loading" :pagination="pagination"
      row-key="id" @change="handleTableChange" size="middle">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'architecture'">
          <a-tag :color="record.architecture === 'ARM' ? 'green' : 'blue'">{{ record.architecture }}</a-tag>
        </template>
        <template v-if="column.key === 'status'">
          <a-badge :status="record.status === 'RUNNING' ? 'processing' : record.status === 'COMPLETED' ? 'success' : 'error'"
            :text="statusMap[record.status] || record.status" />
        </template>
        <template v-if="column.key === 'config'">
          {{ record.ocpus }}C / {{ record.memory }}G / {{ record.disk }}GB
        </template>
        <template v-if="column.key === 'action'">
          <a-popconfirm v-if="record.status === 'RUNNING'" title="确定停止任务?" @confirm="handleStop(record)">
            <a-button type="link" danger size="small" :loading="stopLoading[record.id]">停止</a-button>
          </a-popconfirm>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="createVisible" title="创建开机任务" width="600px" @ok="handleCreate"
      :confirm-loading="createLoading" :mask-closable="false">
      <a-form :model="createForm" layout="vertical">
        <a-form-item label="选择租户" required>
          <a-select v-model:value="createForm.userId" placeholder="选择租户" show-search option-filter-prop="label"
            @change="onTenantChange">
            <a-select-option v-for="t in tenants" :key="t.id" :value="t.id" :label="t.username">
              {{ t.username }} ({{ t.ociRegion }})
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="机器规格 (Shape)">
          <a-select v-model:value="createForm.architecture" placeholder="选择 Shape" :loading="shapesLoading">
            <a-select-option value="ARM">ARM (A1.Flex)</a-select-option>
            <a-select-option value="AMD">AMD (E2.1.Micro)</a-select-option>
            <a-select-option v-for="s in availableShapes" :key="s.shape" :value="s.shape">
              {{ s.shape }} ({{ s.processorDescription || '' }})
            </a-select-option>
          </a-select>
          <div v-if="availableShapes.length" style="color: #888; font-size: 12px; margin-top: 4px">
            查询到 {{ availableShapes.length }} 个可用 Shape
          </div>
        </a-form-item>
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="操作系统">
              <a-select v-model:value="createForm.operationSystem">
                <a-select-option value="Ubuntu">Ubuntu</a-select-option>
                <a-select-option value="Oracle Linux">Oracle Linux</a-select-option>
                <a-select-option value="CentOS">CentOS</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="OCPU 数量">
              <a-input-number v-model:value="createForm.ocpus" :min="1" :max="4" :step="1" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="内存 (GB)">
              <a-input-number v-model:value="createForm.memory" :min="1" :max="24" :step="1" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="磁盘 (GB)">
              <a-input-number v-model:value="createForm.disk" :min="47" :max="200" :step="1" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="开机数量">
              <a-input-number v-model:value="createForm.createNumbers" :min="1" :max="5" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="重试间隔 (秒)">
              <a-input-number v-model:value="createForm.interval" :min="10" :max="600" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="Root 密码">
              <a-input-password v-model:value="createForm.rootPassword" placeholder="留空=随机生成" />
              <a-button type="link" size="small" @click="generateRandomPwd" style="padding: 0">随机生成</a-button>
            </a-form-item>
          </a-col>
        </a-row>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { getTaskList, createTask, stopTask } from '../api/task'
import { getTenantList } from '../api/tenant'
import { getAvailableShapes } from '../api/instance'

const statusMap: Record<string, string> = {
  RUNNING: '运行中', STOPPED: '已停止', COMPLETED: '已完成', FAILED: '已失败',
}

const columns = [
  { title: '租户', dataIndex: 'username', key: 'username' },
  { title: 'Region', dataIndex: 'ociRegion', key: 'ociRegion', width: 140 },
  { title: '架构', dataIndex: 'architecture', key: 'architecture', width: 80 },
  { title: '配置', key: 'config', width: 160 },
  { title: '数量', dataIndex: 'createNumbers', key: 'createNumbers', width: 60 },
  { title: '间隔(s)', dataIndex: 'intervalSeconds', key: 'intervalSeconds', width: 80 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '尝试次数', dataIndex: 'attemptCount', key: 'attemptCount', width: 90 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 80 },
]

const loading = ref(false)
const createLoading = ref(false)
const shapesLoading = ref(false)
const tableData = ref<any[]>([])
const tenants = ref<any[]>([])
const availableShapes = ref<any[]>([])
const createVisible = ref(false)
const pagination = reactive({ current: 1, pageSize: 10, total: 0 })
const stopLoading = reactive<Record<string, boolean>>({})

const createForm = reactive({
  userId: '', architecture: 'ARM', operationSystem: 'Ubuntu',
  ocpus: 1, memory: 6, disk: 50, createNumbers: 1, interval: 60, rootPassword: '',
})

function generateRandomPwd() {
  const chars = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%'
  let pwd = ''
  for (let i = 0; i < 16; i++) pwd += chars[Math.floor(Math.random() * chars.length)]
  createForm.rootPassword = pwd
  message.success('已生成随机密码')
}

async function onTenantChange(tenantId: string) {
  if (!tenantId) { availableShapes.value = []; return }
  shapesLoading.value = true
  try {
    const res = await getAvailableShapes({ id: tenantId })
    availableShapes.value = (res.data || []).filter((s: any) => s.shape !== 'VM.Standard.A1.Flex' && s.shape !== 'VM.Standard.E2.1.Micro')
  } catch {
    availableShapes.value = []
  } finally {
    shapesLoading.value = false
  }
}

async function loadData() {
  loading.value = true
  try {
    const res = await getTaskList({ current: pagination.current, size: pagination.pageSize })
    tableData.value = res.data.records || []
    pagination.total = res.data.total || 0
  } catch (e: any) {
    message.error(e?.message || '加载任务列表失败')
  } finally {
    loading.value = false
  }
}

async function loadTenants() {
  try {
    const res = await getTenantList({ current: 1, size: 1000 })
    tenants.value = res.data.records || []
  } catch (e: any) {
    message.error(e?.message || '加载租户失败')
  }
}

function handleTableChange(pag: any) {
  pagination.current = pag.current
  loadData()
}

function showCreateModal() {
  loadTenants()
  availableShapes.value = []
  Object.assign(createForm, {
    userId: '', architecture: 'ARM', operationSystem: 'Ubuntu',
    ocpus: 1, memory: 6, disk: 50, createNumbers: 1, interval: 60, rootPassword: '',
  })
  createVisible.value = true
}

async function handleCreate() {
  if (!createForm.userId) { message.warning('请选择租户'); return }
  if (!createForm.rootPassword) generateRandomPwd()
  createLoading.value = true
  try {
    await createTask(createForm)
    message.success('任务创建成功')
    createVisible.value = false
    loadData()
  } catch (e: any) {
    message.error(e?.message || '创建任务失败')
  } finally {
    createLoading.value = false
  }
}

async function handleStop(record: any) {
  stopLoading[record.id] = true
  try {
    await stopTask({ taskId: record.id, userId: record.userId })
    message.success('任务已停止')
    loadData()
  } catch (e: any) {
    message.error(e?.message || '停止任务失败')
  } finally {
    stopLoading[record.id] = false
  }
}

onMounted(() => loadData())
</script>

<style scoped>
.table-toolbar { margin-bottom: 16px; }
</style>
