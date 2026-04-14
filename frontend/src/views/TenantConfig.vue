<template>
  <div>
    <div class="table-toolbar">
      <a-space>
        <a-input-search v-model:value="searchText" placeholder="搜索租户" allow-clear @search="loadData" style="width: 250px" />
        <a-button type="primary" @click="showAddModal">
          <template #icon><PlusOutlined /></template>新增配置
        </a-button>
        <a-button @click="showQuickImport">
          <template #icon><ThunderboltOutlined /></template>快速导入
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
        <template v-if="column.key === 'planType'">
          <a-tag :color="record.planType === 'PAYG' ? 'green' : 'orange'">{{ record.planType || '未知' }}</a-tag>
        </template>
        <template v-if="column.key === 'action'">
          <a-space>
            <a-button type="link" size="small" @click="showEditModal(record)">编辑</a-button>
            <a-popconfirm title="确定删除?" @confirm="handleDelete(record.id)">
              <a-button type="link" danger size="small">删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 快速导入弹窗 -->
    <a-modal v-model:open="quickImportVisible" title="⚡ 快速导入配置" width="640px" :footer="null">
      <a-alert message="粘贴你的 OCI 配置，支持以下格式" type="info" show-icon style="margin-bottom: 16px">
        <template #description>
          <pre style="margin: 8px 0 0; font-size: 12px; color: #666">[Profile-Name]
user=ocid1.user.oc1...
fingerprint=a5:48:75:06...
tenancy=ocid1.tenancy.oc1...
region=ap-tokyo-1
key_file=~/.oci/oci_api_key.pem</pre>
        </template>
      </a-alert>
      <a-textarea
        v-model:value="importText"
        :rows="8"
        placeholder="粘贴 OCI 配置内容..."
        style="font-family: monospace"
      />
      <div style="margin-top: 16px; text-align: right">
        <a-space>
          <a-button @click="quickImportVisible = false">取消</a-button>
          <a-button type="primary" @click="parseAndFill">
            <template #icon><ThunderboltOutlined /></template>解析配置
          </a-button>
        </a-space>
      </div>
    </a-modal>

    <!-- 新增/编辑弹窗 -->
    <a-modal v-model:open="modalVisible" :title="editingId ? '编辑配置' : '新增配置'" width="640px" @ok="handleSubmit" :confirm-loading="submitLoading">
      <a-form :model="formState" layout="vertical">
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
          <a-upload :before-upload="handleUpload" :max-count="1" accept=".pem">
            <a-button><UploadOutlined />选择 PEM 文件</a-button>
          </a-upload>
          <span v-if="formState.ociKeyPath" style="color: #888; font-size: 12px; margin-top: 4px; display: block">
            已有密钥：{{ formState.ociKeyPath }}
          </span>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { PlusOutlined, UploadOutlined, ThunderboltOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import { getTenantList, addTenant, updateTenant, removeTenant, uploadKey } from '../api/tenant'

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
  { title: 'Fingerprint', dataIndex: 'ociFingerprint', key: 'ociFingerprint', ellipsis: true },
  { title: '账户类型', dataIndex: 'planType', key: 'planType', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 140 },
]

const loading = ref(false)
const submitLoading = ref(false)
const tableData = ref<any[]>([])
const searchText = ref('')
const selectedRowKeys = ref<string[]>([])
const modalVisible = ref(false)
const editingId = ref('')
const pagination = reactive({ current: 1, pageSize: 10, total: 0 })
const quickImportVisible = ref(false)
const importText = ref('')

const formState = reactive({
  username: '', ociTenantId: '', ociUserId: '',
  ociFingerprint: '', ociRegion: '', ociKeyPath: '',
})

let pendingFile: File | null = null

function parseOciConfig(text: string) {
  const results: Array<{
    name: string; user: string; fingerprint: string;
    tenancy: string; region: string;
  }> = []

  const lines = text.split('\n').map(l => l.trim()).filter(l => l)
  let current: any = null

  for (const line of lines) {
    const sectionMatch = line.match(/^\[(.+)\]$/)
    if (sectionMatch) {
      if (current) results.push(current)
      current = { name: sectionMatch[1], user: '', fingerprint: '', tenancy: '', region: '' }
      continue
    }

    if (!current) {
      current = { name: '', user: '', fingerprint: '', tenancy: '', region: '' }
    }

    const kvMatch = line.match(/^(\w+)\s*=\s*(.+)$/)
    if (kvMatch) {
      const [, key, value] = kvMatch
      const v = value.trim()
      switch (key.toLowerCase()) {
        case 'user': current.user = v; break
        case 'fingerprint': current.fingerprint = v; break
        case 'tenancy': current.tenancy = v; break
        case 'region': current.region = v; break
      }
    }
  }
  if (current) results.push(current)
  return results
}

function showQuickImport() {
  importText.value = ''
  quickImportVisible.value = true
}

function parseAndFill() {
  if (!importText.value.trim()) {
    message.warning('请粘贴 OCI 配置内容')
    return
  }

  const configs = parseOciConfig(importText.value)
  if (configs.length === 0) {
    message.error('未能解析出有效配置，请检查格式')
    return
  }

  if (configs.length === 1) {
    const c = configs[0]
    editingId.value = ''
    Object.assign(formState, {
      username: c.name || '',
      ociTenantId: c.tenancy,
      ociUserId: c.user,
      ociFingerprint: c.fingerprint,
      ociRegion: c.region,
      ociKeyPath: '',
    })
    pendingFile = null
    quickImportVisible.value = false
    modalVisible.value = true
    message.success('已解析 1 条配置，请上传私钥后提交')
  } else {
    const c = configs[0]
    editingId.value = ''
    Object.assign(formState, {
      username: c.name || '',
      ociTenantId: c.tenancy,
      ociUserId: c.user,
      ociFingerprint: c.fingerprint,
      ociRegion: c.region,
      ociKeyPath: '',
    })
    pendingFile = null
    quickImportVisible.value = false
    modalVisible.value = true
    message.success(`已解析 ${configs.length} 条配置，当前显示第 1 条，请逐条上传私钥后提交`)
  }
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
  } catch { /* ignore */ } finally {
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

function showAddModal() {
  editingId.value = ''
  Object.assign(formState, { username: '', ociTenantId: '', ociUserId: '', ociFingerprint: '', ociRegion: '', ociKeyPath: '' })
  pendingFile = null
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
  modalVisible.value = true
}

function handleUpload(file: File) {
  pendingFile = file
  return false
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
  } catch { /* ignore */ } finally {
    submitLoading.value = false
  }
}

async function handleDelete(id: string) {
  await removeTenant({ idList: [id] })
  message.success('删除成功')
  loadData()
}

function handleBatchDelete() {
  Modal.confirm({
    title: '确认批量删除？',
    content: `将删除 ${selectedRowKeys.value.length} 条配置`,
    async onOk() {
      await removeTenant({ idList: selectedRowKeys.value })
      message.success('删除成功')
      selectedRowKeys.value = []
      loadData()
    },
  })
}

onMounted(() => loadData())
</script>

<style scoped>
.table-toolbar {
  margin-bottom: 16px;
  display: flex;
  justify-content: space-between;
}
</style>
