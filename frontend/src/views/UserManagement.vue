<template>
  <div>
    <div class="table-toolbar">
      <a-space>
        <a-button @click="$router.push('/tenant')">
          <template #icon><ArrowLeftOutlined /></template>返回租户列表
        </a-button>
        <a-tag color="blue" v-if="tenantInfo">{{ tenantInfo.username }} ({{ tenantInfo.ociRegion }})</a-tag>
        <a-button type="primary" @click="showCreateModal">
          <template #icon><PlusOutlined /></template>新增用户
        </a-button>
        <a-button @click="loadUsers" :loading="loading">
          <template #icon><ReloadOutlined /></template>刷新
        </a-button>
      </a-space>
    </div>

    <a-table :columns="columns" :data-source="users" :loading="loading" row-key="id" size="middle">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'state'">
          <a-badge :status="record.state === 'ACTIVE' ? 'success' : 'default'" :text="record.state" />
        </template>
        <template v-if="column.key === 'isMfaActivated'">
          <a-tag :color="record.isMfaActivated ? 'green' : 'default'">{{ record.isMfaActivated ? '已启用' : '未启用' }}</a-tag>
        </template>
        <template v-if="column.key === 'action'">
          <a-space wrap>
            <a-popconfirm title="确定重置密码？" @confirm="handleResetPassword(record)">
              <a-button type="link" size="small" :loading="actionLoading[record.id + '_pw']">重置密码</a-button>
            </a-popconfirm>
            <a-popconfirm title="确定清理 MFA？" @confirm="handleClearMfa(record)">
              <a-button type="link" size="small" :loading="actionLoading[record.id + '_mfa']">清理 MFA</a-button>
            </a-popconfirm>
            <a-button type="link" size="small" :loading="actionLoading[record.id + '_addg']"
              @click="handleAddToAdmin(record)">加入管理员组</a-button>
            <a-button type="link" danger size="small" :loading="actionLoading[record.id + '_rmg']"
              @click="handleRemoveFromAdmin(record)">移出管理员组</a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 新增用户弹窗 -->
    <a-modal v-model:open="createVisible" title="新增用户" @ok="handleCreate" :confirm-loading="createLoading" :mask-closable="false">
      <a-form :model="createForm" layout="vertical">
        <a-form-item label="用户名" required>
          <a-input v-model:value="createForm.userName" placeholder="登录用户名" />
        </a-form-item>
        <a-form-item label="邮箱">
          <a-input v-model:value="createForm.email" placeholder="user@example.com" />
        </a-form-item>
        <a-form-item label="加入管理员组">
          <a-switch v-model:checked="createForm.addToAdminGroup" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 密码结果弹窗 -->
    <a-modal v-model:open="pwdResultVisible" title="新密码" :footer="null" :mask-closable="false">
      <a-alert type="success" show-icon>
        <template #message>密码已重置</template>
        <template #description>
          <a-typography-paragraph copyable :content="pwdResult" style="font-size: 18px; font-weight: bold; margin: 8px 0 0">
            {{ pwdResult }}
          </a-typography-paragraph>
          <div style="color: #999; margin-top: 8px">请立即复制保存，关闭后无法再次查看</div>
        </template>
      </a-alert>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeftOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { listUsers, createUser, resetPassword, clearMfa, addToAdmin, removeFromAdmin } from '../api/user'
import { getTenantList } from '../api/tenant'

const route = useRoute()
const tenantId = route.params.tenantId as string

const loading = ref(false)
const users = ref<any[]>([])
const tenantInfo = ref<any>(null)
const actionLoading = reactive<Record<string, boolean>>({})

const createVisible = ref(false)
const createLoading = ref(false)
const createForm = reactive({ userName: '', email: '', addToAdminGroup: false })

const pwdResultVisible = ref(false)
const pwdResult = ref('')

const columns = [
  { title: '用户名', dataIndex: 'name', key: 'name' },
  { title: '邮箱', dataIndex: 'email', key: 'email', ellipsis: true },
  { title: '状态', key: 'state', width: 100 },
  { title: 'MFA', key: 'isMfaActivated', width: 100 },
  { title: '创建时间', dataIndex: 'timeCreated', key: 'timeCreated', width: 200, ellipsis: true },
  { title: '操作', key: 'action', width: 320 },
]

async function loadTenantInfo() {
  try {
    const res = await getTenantList({ current: 1, size: 1000 })
    const records = res.data.records || []
    tenantInfo.value = records.find((t: any) => t.id === tenantId)
  } catch {}
}

async function loadUsers() {
  loading.value = true
  try {
    const res = await listUsers({ tenantId })
    users.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载用户列表失败')
  } finally {
    loading.value = false
  }
}

function showCreateModal() {
  createForm.userName = ''
  createForm.email = ''
  createForm.addToAdminGroup = false
  createVisible.value = true
}

async function handleCreate() {
  if (!createForm.userName) {
    message.warning('请填写用户名')
    return
  }
  createLoading.value = true
  try {
    await createUser({ tenantId, ...createForm })
    message.success('用户创建成功')
    createVisible.value = false
    loadUsers()
  } catch (e: any) {
    message.error(e?.message || '创建失败')
  } finally {
    createLoading.value = false
  }
}

async function handleResetPassword(record: any) {
  actionLoading[record.id + '_pw'] = true
  try {
    const res = await resetPassword({ tenantId, userId: record.id })
    pwdResult.value = res.data || ''
    pwdResultVisible.value = true
  } catch (e: any) {
    message.error(e?.message || '重置密码失败')
  } finally {
    actionLoading[record.id + '_pw'] = false
  }
}

async function handleClearMfa(record: any) {
  actionLoading[record.id + '_mfa'] = true
  try {
    await clearMfa({ tenantId, userId: record.id })
    message.success('MFA 已清理')
    loadUsers()
  } catch (e: any) {
    message.error(e?.message || '清理 MFA 失败')
  } finally {
    actionLoading[record.id + '_mfa'] = false
  }
}

async function handleAddToAdmin(record: any) {
  actionLoading[record.id + '_addg'] = true
  try {
    await addToAdmin({ tenantId, userId: record.id })
    message.success('已加入管理员组')
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    actionLoading[record.id + '_addg'] = false
  }
}

async function handleRemoveFromAdmin(record: any) {
  actionLoading[record.id + '_rmg'] = true
  try {
    await removeFromAdmin({ tenantId, userId: record.id })
    message.success('已移出管理员组')
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    actionLoading[record.id + '_rmg'] = false
  }
}

onMounted(() => {
  loadTenantInfo()
  loadUsers()
})
</script>

<style scoped>
.table-toolbar { margin-bottom: 16px; }
@media (max-width: 768px) {
  .table-toolbar :deep(.ant-space) {
    flex-wrap: wrap;
    width: 100%;
    gap: 8px !important;
  }
}
</style>
