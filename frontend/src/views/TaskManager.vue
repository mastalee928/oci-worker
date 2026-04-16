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
        <a-input-search v-model:value="searchKeyword" placeholder="搜索租户/区域/架构..."
          style="width: 260px" allow-clear @search="handleSearch" enter-button="搜索" />
        <a-select v-model:value="filterStatus" placeholder="状态筛选" style="width: 130px"
          allow-clear @change="handleSearch">
          <a-select-option value="">全部</a-select-option>
          <a-select-option value="RUNNING">运行中</a-select-option>
          <a-select-option value="STOPPED">已停止</a-select-option>
          <a-select-option value="COMPLETED">已完成</a-select-option>
          <a-select-option value="FAILED">已失败</a-select-option>
        </a-select>
        <a-button :disabled="!selectedRowKeys.length" :loading="batchLoading"
          @click="handleBatchStop" danger>
          批量暂停
        </a-button>
        <a-button :disabled="!selectedRowKeys.length" :loading="batchLoading"
          type="primary" @click="handleBatchResume">
          批量启动
        </a-button>
      </a-space>
    </div>

    <a-table v-if="!isMobile" :columns="columns" :data-source="tableData" :loading="loading" :pagination="pagination"
      row-key="id" @change="handleTableChange" size="middle"
      :row-selection="{ selectedRowKeys, onChange: (keys: string[]) => selectedRowKeys = keys }"
      :row-class-name="(record: any) => record.status !== 'RUNNING' ? 'row-inactive' : ''">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'architecture'">
          <a-tag :color="record.architecture === 'ARM' ? 'green' : 'blue'">{{ record.architecture }}</a-tag>
        </template>
        <template v-if="column.key === 'progress'">
          <span style="font-weight: 600">{{ record.successCount || 0 }}</span>
          <span style="color: var(--text-sub)"> / {{ record.createNumbers }}</span>
        </template>
        <template v-if="column.key === 'status'">
          <a-badge :status="badgeStatusMap[record.status] || 'default'"
            :text="statusMap[record.status] || record.status" />
        </template>
        <template v-if="column.key === 'config'">
          {{ record.ocpus }}C / {{ record.memory }}G / {{ record.disk }}GB
        </template>
        <template v-if="column.key === 'action'">
          <a-space>
            <a-popconfirm v-if="record.status === 'RUNNING'" title="确定停止任务?" @confirm="handleStop(record)">
              <a-button type="link" danger size="small" :loading="actionLoading[record.id]">停止</a-button>
            </a-popconfirm>
            <a-popconfirm v-if="record.status === 'STOPPED'" title="确定恢复任务?" @confirm="handleResume(record)">
              <a-button type="link" size="small" :loading="actionLoading[record.id]">继续</a-button>
            </a-popconfirm>
            <a-popconfirm v-if="record.status !== 'RUNNING'" title="确定删除此记录?" @confirm="handleDelete(record)">
              <a-button type="link" danger size="small" :loading="actionLoading[record.id]">删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 移动端卡片列表 -->
    <a-spin v-else :spinning="loading">
      <a-empty v-if="!loading && tableData.length === 0" description="暂无任务" />
      <div v-for="task in tableData" :key="task.id" class="mobile-card"
        :style="{ opacity: task.status !== 'RUNNING' ? 0.65 : 1 }">
        <div class="mobile-card-header">
          <span class="mobile-card-title">
            <a-tag :color="task.architecture === 'ARM' ? 'green' : 'blue'" style="margin-right: 6px">{{ task.architecture }}</a-tag>
            {{ task.username }}
          </span>
          <a-badge :status="badgeStatusMap[task.status] || 'default'" :text="statusMap[task.status] || task.status" />
        </div>
        <div class="mobile-card-body">
          <div class="mobile-card-row"><span class="label">区域</span><span class="value">{{ task.ociRegion }}</span></div>
          <div class="mobile-card-row"><span class="label">配置</span><span class="value">{{ task.ocpus }}C / {{ task.memory }}G / {{ task.disk }}GB</span></div>
          <div class="mobile-card-row"><span class="label">进度</span><span class="value">{{ task.successCount || 0 }} / {{ task.createNumbers }}</span></div>
          <div class="mobile-card-row"><span class="label">间隔</span><span class="value">{{ task.intervalSeconds }}s</span></div>
          <div class="mobile-card-row"><span class="label">尝试</span><span class="value">{{ task.attemptCount }} 次</span></div>
          <div class="mobile-card-row"><span class="label">创建</span><span class="value">{{ task.createTime }}</span></div>
        </div>
        <div class="mobile-card-actions">
          <a-popconfirm v-if="task.status === 'RUNNING'" title="确定停止？" @confirm="handleStop(task)">
            <a-button type="link" danger size="small" :loading="actionLoading[task.id]">停止</a-button>
          </a-popconfirm>
          <a-popconfirm v-if="task.status === 'STOPPED'" title="确定恢复？" @confirm="handleResume(task)">
            <a-button type="link" size="small" :loading="actionLoading[task.id]">继续</a-button>
          </a-popconfirm>
          <a-popconfirm v-if="task.status !== 'RUNNING'" title="确定删除？" @confirm="handleDelete(task)">
            <a-button type="link" danger size="small" :loading="actionLoading[task.id]">删除</a-button>
          </a-popconfirm>
        </div>
      </div>
    </a-spin>

    <a-modal v-model:open="createVisible" title="创建开机任务" :width="isMobile ? '100%' : 600" @ok="handleCreate"
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
        <a-form-item label="操作系统">
          <a-select v-model:value="createForm.operationSystem">
            <a-select-option value="Ubuntu">Ubuntu（最新版）</a-select-option>
            <a-select-option value="Ubuntu 24.04">Ubuntu 24.04 LTS</a-select-option>
            <a-select-option value="Ubuntu 22.04">Ubuntu 22.04 LTS</a-select-option>
            <a-select-option value="Ubuntu 20.04">Ubuntu 20.04 LTS</a-select-option>
            <a-select-option value="Oracle Linux">Oracle Linux</a-select-option>
            <a-select-option value="CentOS">CentOS</a-select-option>
          </a-select>
        </a-form-item>
        <a-row :gutter="12">
          <a-col :xs="12" :sm="8">
            <a-form-item label="OCPU 数量">
              <a-input-number v-model:value="createForm.ocpus" :min="1" :max="4" :step="1" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="8">
            <a-form-item label="内存 (GB)">
              <a-input-number v-model:value="createForm.memory" :min="1" :max="24" :step="1" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="8">
            <a-form-item label="磁盘 (GB)">
              <a-input-number v-model:value="createForm.disk" :min="47" :max="200" :step="1" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :xs="12" :sm="8">
            <a-form-item label="开机数量">
              <a-input-number v-model:value="createForm.createNumbers" :min="1" :max="5" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="8">
            <a-form-item label="重试间隔 (秒)">
              <a-input-number v-model:value="createForm.interval" :min="10" :max="600" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="8">
            <a-form-item label="Root 密码">
              <a-input-password v-model:value="createForm.rootPassword" placeholder="留空=随机生成" />
              <a-button type="link" size="small" @click="generateRandomPwd" style="padding: 0">随机生成</a-button>
            </a-form-item>
          </a-col>
        </a-row>
        <div style="display: flex; align-items: center; gap: 32px; margin-bottom: 16px">
          <span style="display: inline-flex; align-items: center; gap: 8px">
            <a-switch v-model:checked="createForm.assignPublicIp" />
            <span>公网IP</span>
          </span>
          <span style="display: inline-flex; align-items: center; gap: 8px">
            <a-switch v-model:checked="createForm.assignIpv6" />
            <span>IPv6</span>
          </span>
        </div>
        <a-form-item label="自定义开机脚本（cloud-init）">
          <a-textarea v-model:value="createForm.customScript" placeholder="开机后自动执行的 Shell 脚本，留空则不执行&#10;&#10;示例：&#10;apt update && apt install -y docker.io&#10;ufw disable" :auto-size="{ minRows: 3, maxRows: 8 }" />
          <div style="color: var(--text-sub); font-size: 12px; margin-top: 4px">
            脚本会追加在密码设置之后执行，以 root 身份运行
          </div>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import { getTaskList, createTask, stopTask, hasRunningTask, resumeTask, deleteTask, batchStopTask, batchResumeTask } from '../api/task'
import { getTenantList } from '../api/tenant'
import { getAvailableShapes } from '../api/instance'

const statusMap: Record<string, string> = {
  RUNNING: '运行中', STOPPED: '已停止', COMPLETED: '已完成', FAILED: '已失败',
}
const badgeStatusMap: Record<string, string> = {
  RUNNING: 'processing', STOPPED: 'default', COMPLETED: 'success', FAILED: 'error',
}

const columns = [
  { title: '租户', dataIndex: 'username', key: 'username' },
  { title: 'Region', dataIndex: 'ociRegion', key: 'ociRegion', width: 140 },
  { title: '架构', dataIndex: 'architecture', key: 'architecture', width: 80 },
  { title: '配置', key: 'config', width: 160 },
  { title: '进度', key: 'progress', width: 90 },
  { title: '间隔(s)', dataIndex: 'intervalSeconds', key: 'intervalSeconds', width: 80 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '尝试次数', dataIndex: 'attemptCount', key: 'attemptCount', width: 90 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 160 },
]

const selectedRowKeys = ref<string[]>([])
const batchLoading = ref(false)
const loading = ref(false)
const createLoading = ref(false)
const shapesLoading = ref(false)
const tableData = ref<any[]>([])
const tenants = ref<any[]>([])
const availableShapes = ref<any[]>([])
const createVisible = ref(false)
const searchKeyword = ref('')
const filterStatus = ref('')
const pagination = reactive({ current: 1, pageSize: 10, total: 0 })
const actionLoading = reactive<Record<string, boolean>>({})
const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }
onMounted(() => window.addEventListener('resize', checkMobile))
onUnmounted(() => window.removeEventListener('resize', checkMobile))

const createForm = reactive({
  userId: '', architecture: 'ARM', operationSystem: 'Ubuntu',
  ocpus: 1, memory: 6, disk: 50, createNumbers: 1, interval: 60, rootPassword: '',
  customScript: '', assignPublicIp: true, assignIpv6: false,
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

function handleSearch() {
  pagination.current = 1
  loadData()
}

async function loadData() {
  loading.value = true
  try {
    const res = await getTaskList({
      current: pagination.current,
      size: pagination.pageSize,
      keyword: searchKeyword.value || undefined,
      status: filterStatus.value || undefined,
    })
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
    customScript: '', assignPublicIp: true, assignIpv6: false,
  })
  createVisible.value = true
}

async function handleCreate() {
  if (!createForm.userId) { message.warning('请选择租户'); return }
  if (!createForm.rootPassword) generateRandomPwd()

  try {
    const checkRes = await hasRunningTask({ userId: createForm.userId })
    if (checkRes.data === true) {
      Modal.confirm({
        title: '重复任务提醒',
        content: '该账户已有正在运行的开机任务，是否仍要重复提交？',
        okText: '继续创建',
        cancelText: '取消',
        onOk: () => doCreate(),
      })
      return
    }
  } catch {}

  doCreate()
}

async function doCreate() {
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
  actionLoading[record.id] = true
  try {
    await stopTask({ taskId: record.id, userId: record.userId })
    message.success('任务已停止')
    loadData()
  } catch (e: any) {
    message.error(e?.message || '停止任务失败')
  } finally {
    actionLoading[record.id] = false
  }
}

async function handleResume(record: any) {
  actionLoading[record.id] = true
  try {
    await resumeTask({ taskId: record.id })
    message.success('任务已恢复运行')
    loadData()
  } catch (e: any) {
    message.error(e?.message || '恢复任务失败')
  } finally {
    actionLoading[record.id] = false
  }
}

async function handleBatchStop() {
  if (!selectedRowKeys.value.length) return
  Modal.confirm({
    title: '批量暂停',
    content: `确定暂停选中的 ${selectedRowKeys.value.length} 个任务？`,
    async onOk() {
      batchLoading.value = true
      try {
        const res = await batchStopTask({ taskIds: selectedRowKeys.value })
        message.success(`已暂停 ${res.data} 个任务`)
        selectedRowKeys.value = []
        loadData()
      } catch (e: any) {
        message.error(e?.message || '批量暂停失败')
      } finally {
        batchLoading.value = false
      }
    }
  })
}

async function handleBatchResume() {
  if (!selectedRowKeys.value.length) return
  Modal.confirm({
    title: '批量启动',
    content: `确定启动选中的 ${selectedRowKeys.value.length} 个任务？`,
    async onOk() {
      batchLoading.value = true
      try {
        const res = await batchResumeTask({ taskIds: selectedRowKeys.value })
        message.success(`已启动 ${res.data} 个任务`)
        selectedRowKeys.value = []
        loadData()
      } catch (e: any) {
        message.error(e?.message || '批量启动失败')
      } finally {
        batchLoading.value = false
      }
    }
  })
}

async function handleDelete(record: any) {
  actionLoading[record.id] = true
  try {
    await deleteTask({ taskId: record.id })
    message.success('记录已删除')
    loadData()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  } finally {
    actionLoading[record.id] = false
  }
}

onMounted(() => loadData())
</script>

<style scoped>
.table-toolbar { margin-bottom: 16px; transition: var(--trans); }
:deep(.row-inactive td) {
  color: var(--text-sub) !important;
}
@media (max-width: 768px) {
  .table-toolbar :deep(.ant-space) {
    flex-wrap: wrap;
    width: 100%;
    gap: 8px !important;
  }
  .table-toolbar :deep(.ant-space-item) {
    flex: 1 1 auto;
    min-width: 0;
  }
  .table-toolbar :deep(.ant-input-search) {
    width: 100% !important;
  }
  .table-toolbar :deep(.ant-select) {
    width: 100% !important;
  }
  .table-toolbar :deep(.ant-btn) {
    width: auto;
  }
}
</style>
