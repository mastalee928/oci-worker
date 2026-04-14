<template>
  <div>
    <div class="table-toolbar">
      <a-space>
        <a-input-search v-model:value="searchText" placeholder="搜索租户" allow-clear @search="loadData" style="width: 250px" />
        <a-button type="primary" @click="showAddModal">
          <template #icon><PlusOutlined /></template>新增配置
        </a-button>
        <a-button danger :disabled="!selectedRowKeys.length" @click="handleBatchDelete">
          批量删除
        </a-button>
      </a-space>
    </div>

    <a-table
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :row-selection="{ selectedRowKeys, onChange: onSelectChange }"
      :pagination="pagination"
      row-key="id"
      @change="handleTableChange"
      size="middle"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'ociRegion'">
          <a-tag color="blue">{{ record.ociRegion }}</a-tag>
        </template>
        <template v-if="column.key === 'taskStatus'">
          <a-badge v-if="record.hasRunningTask" status="processing" text="执行开机任务中" />
          <span v-else style="color: #999">无开机任务</span>
        </template>
        <template v-if="column.key === 'planType'">
          <a-tag :color="record.planType === 'PAYG' ? 'green' : record.planType === 'FREE' ? 'orange' : 'default'">{{ record.planType || '获取中...' }}</a-tag>
        </template>
        <template v-if="column.key === 'action'">
          <a-space>
            <a-button type="link" size="small" @click="showEditModal(record)">编辑</a-button>
            <a-button type="link" size="small" @click="goUserManagement(record)">用户管理</a-button>
            <a-popconfirm title="确定删除?" @confirm="handleDelete(record.id)">
              <a-button type="link" danger size="small">删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 新增/编辑弹窗（内嵌快速导入） -->
    <a-modal v-model:open="modalVisible" :title="editingId ? '编辑配置' : '新增配置'" :width="isMobile ? '100%' : 680" @ok="handleSubmit" :confirm-loading="submitLoading" :mask-closable="false">
      <a-form :model="formState" layout="vertical">
        <!-- 快速导入区域（仅新增时显示） -->
        <a-collapse v-if="!editingId" :bordered="false" :active-key="['import']" style="margin-bottom: 16px; background: #f6f8fa; border-radius: 8px">
          <a-collapse-panel key="import" header="⚡ 快速导入 — 粘贴 OCI 配置自动填充">
            <a-textarea
              v-model:value="importText"
              :rows="6"
              placeholder="粘贴 OCI 配置内容，例如：
[Profile-Name]
user=ocid1.user.oc1...
fingerprint=a5:48:75:06...
tenancy=ocid1.tenancy.oc1...
region=ap-tokyo-1"
              style="font-family: monospace; font-size: 12px"
            />
            <a-button type="primary" size="small" style="margin-top: 8px" @click="parseAndFill">
              <template #icon><ThunderboltOutlined /></template>解析并填充
            </a-button>
          </a-collapse-panel>
        </a-collapse>

        <a-form-item label="自定义名称" required>
          <a-input v-model:value="formState.username" placeholder="例：我的甲骨文1号" />
        </a-form-item>
        <a-form-item label="Tenant OCID" required>
          <a-input v-model:value="formState.ociTenantId" placeholder="ocid1.tenancy.oc1.." />
        </a-form-item>
        <a-form-item label="User OCID" required>
          <a-input v-model:value="formState.ociUserId" placeholder="ocid1.user.oc1.." />
        </a-form-item>
        <a-form-item label="Fingerprint" required>
          <a-input v-model:value="formState.ociFingerprint" placeholder="xx:xx:xx:..." />
        </a-form-item>
        <a-form-item label="Region" required>
          <a-select v-model:value="formState.ociRegion" placeholder="选择区域" show-search>
            <a-select-option v-for="r in regions" :key="r" :value="r">{{ r }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="私钥文件 (.pem)">
          <a-upload-dragger
            :before-upload="handleUpload"
            :max-count="1"
            accept=".pem"
            :file-list="fileList"
            @remove="handleRemoveFile"
          >
            <p class="ant-upload-drag-icon"><InboxOutlined /></p>
            <p class="ant-upload-text">点击或拖拽 PEM 文件到此处上传</p>
          </a-upload-dragger>
          <span v-if="formState.ociKeyPath && !fileList.length" style="color: #888; font-size: 12px; margin-top: 4px; display: block">
            已有密钥：{{ formState.ociKeyPath }}
          </span>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { PlusOutlined, ThunderboltOutlined, InboxOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import type { UploadFile } from 'ant-design-vue'
import { getTenantList, addTenant, updateTenant, removeTenant, uploadKey } from '../api/tenant'

const router = useRouter()

const regions = [
  'us-ashburn-1', 'us-phoenix-1', 'us-sanjose-1', 'us-chicago-1',
  'ca-toronto-1', 'ca-montreal-1',
  'eu-frankfurt-1', 'eu-zurich-1', 'eu-amsterdam-1', 'eu-marseille-1',
  'eu-stockholm-1', 'eu-milan-1', 'eu-paris-1', 'eu-madrid-1',
  'uk-london-1', 'uk-cardiff-1',
  'ap-tokyo-1', 'ap-osaka-1', 'ap-seoul-1', 'ap-chuncheon-1',
  'ap-mumbai-1', 'ap-hyderabad-1', 'ap-singapore-1',
  'ap-sydney-1', 'ap-melbourne-1',
  'sa-saopaulo-1', 'sa-vinhedo-1', 'sa-santiago-1',
  'me-jeddah-1', 'me-dubai-1', 'me-abudhabi-1',
  'af-johannesburg-1',
  'il-jerusalem-1',
  'mx-queretaro-1', 'mx-monterrey-1',
  'us-saltlake-2', 'us-langley-1', 'us-luke-1', 'us-gov-ashburn-1', 'us-gov-chicago-1', 'us-gov-phoenix-1',
]

const columns = [
  { title: '名称', dataIndex: 'username', key: 'username', ellipsis: true },
  { title: 'Region', dataIndex: 'ociRegion', key: 'ociRegion', width: 160 },
  { title: '开机任务', key: 'taskStatus', width: 140 },
  { title: '账户类型', dataIndex: 'planType', key: 'planType', width: 130 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 220 },
]

const loading = ref(false)
const submitLoading = ref(false)
const tableData = ref<any[]>([])
const searchText = ref('')
const selectedRowKeys = ref<string[]>([])
const modalVisible = ref(false)
const editingId = ref('')
const pagination = reactive({ current: 1, pageSize: 10, total: 0 })
const importText = ref('')
const fileList = ref<UploadFile[]>([])

const formState = reactive({
  username: '', ociTenantId: '', ociUserId: '',
  ociFingerprint: '', ociRegion: '', ociKeyPath: '',
})

let pendingFile: File | null = null
const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }

function parseAndFill() {
  if (!importText.value.trim()) {
    message.warning('请粘贴 OCI 配置内容')
    return
  }
  const lines = importText.value.split('\n').map(l => l.trim()).filter(l => l)
  let name = ''
  const fields: Record<string, string> = {}

  for (const line of lines) {
    const sec = line.match(/^\[(.+)\]$/)
    if (sec) { name = sec[1]; continue }
    const kv = line.match(/^(\w+)\s*=\s*(.+)$/)
    if (kv) fields[kv[1].toLowerCase()] = kv[2].trim()
  }

  if (!fields['user'] && !fields['tenancy'] && !fields['fingerprint']) {
    message.error('未能解析出有效配置，请检查格式')
    return
  }

  formState.username = name || formState.username
  formState.ociUserId = fields['user'] || formState.ociUserId
  formState.ociTenantId = fields['tenancy'] || formState.ociTenantId
  formState.ociFingerprint = fields['fingerprint'] || formState.ociFingerprint
  formState.ociRegion = fields['region'] || formState.ociRegion
  message.success('已解析并填充，请上传私钥后提交')
}

async function loadData() {
  loading.value = true
  try {
    const res = await getTenantList({
      current: pagination.current,
      size: pagination.pageSize,
      keyword: searchText.value,
    })
    tableData.value = res.data.records || []
    pagination.total = res.data.total || 0
  } catch (e: any) {
    message.error(e?.message || '加载租户列表失败')
  } finally {
    loading.value = false
  }
}

function handleTableChange(pag: any) {
  pagination.current = pag.current
  pagination.pageSize = pag.pageSize
  loadData()
}

function onSelectChange(keys: string[]) {
  selectedRowKeys.value = keys
}

function resetForm() {
  Object.assign(formState, { username: '', ociTenantId: '', ociUserId: '', ociFingerprint: '', ociRegion: '', ociKeyPath: '' })
  pendingFile = null
  fileList.value = []
  importText.value = ''
}

function showAddModal() {
  editingId.value = ''
  resetForm()
  modalVisible.value = true
}

function showEditModal(record: any) {
  editingId.value = record.id
  Object.assign(formState, {
    username: record.username,
    ociTenantId: record.ociTenantId,
    ociUserId: record.ociUserId,
    ociFingerprint: record.ociFingerprint,
    ociRegion: record.ociRegion,
    ociKeyPath: record.ociKeyPath,
  })
  pendingFile = null
  fileList.value = []
  importText.value = ''
  modalVisible.value = true
}

function goUserManagement(record: any) {
  router.push(`/tenant/${record.id}/users`)
}

function handleUpload(file: File) {
  pendingFile = file
  fileList.value = [{ uid: '-1', name: file.name, status: 'done' } as UploadFile]
  return false
}

function handleRemoveFile() {
  pendingFile = null
  fileList.value = []
}

async function handleSubmit() {
  if (!formState.username || !formState.ociTenantId || !formState.ociUserId || !formState.ociFingerprint || !formState.ociRegion) {
    message.warning('请填写所有必填项')
    return
  }

  submitLoading.value = true
  try {
    let keyPath = formState.ociKeyPath
    if (pendingFile) {
      const fd = new FormData()
      fd.append('file', pendingFile)
      const uploadRes = await uploadKey(fd)
      keyPath = uploadRes.data
    }

    if (!keyPath && !editingId.value) {
      message.warning('请上传私钥文件')
      submitLoading.value = false
      return
    }

    const data = { ...formState, ociKeyPath: keyPath }
    if (editingId.value) {
      await updateTenant({ id: editingId.value, ...data })
      message.success('更新成功')
    } else {
      await addTenant(data)
      message.success('添加成功')
    }
    modalVisible.value = false
    loadData()
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    submitLoading.value = false
  }
}

async function handleDelete(id: string) {
  try {
    await removeTenant({ idList: [id] })
    message.success('删除成功')
    loadData()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  }
}

function handleBatchDelete() {
  Modal.confirm({
    title: '确认批量删除？',
    content: `将删除 ${selectedRowKeys.value.length} 条配置`,
    async onOk() {
      try {
        await removeTenant({ idList: selectedRowKeys.value })
        message.success('删除成功')
        selectedRowKeys.value = []
        loadData()
      } catch (e: any) {
        message.error(e?.message || '删除失败')
      }
    },
  })
}

onMounted(() => {
  loadData()
  window.addEventListener('resize', checkMobile)
})
onUnmounted(() => window.removeEventListener('resize', checkMobile))
</script>

<style scoped>
.table-toolbar {
  margin-bottom: 16px;
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  transition: var(--trans);
}
/* 快速导入折叠区：覆盖内联浅色背景，随主题切换 */
:deep(.ant-collapse) {
  background: var(--bg-card) !important;
  border: 1px solid var(--border) !important;
  border-radius: var(--radius-sm) !important;
  box-shadow: var(--shadow-card) !important;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: var(--trans);
}
@media (max-width: 768px) {
  .table-toolbar {
    flex-direction: column;
  }
  .table-toolbar :deep(.ant-space) {
    flex-wrap: wrap;
    width: 100%;
    gap: 8px !important;
  }
  .table-toolbar :deep(.ant-input-search) {
    width: 100% !important;
  }
}
</style>
