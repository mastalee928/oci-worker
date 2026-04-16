<template>
  <div>
    <div class="table-toolbar">
      <a-space>
        <a-button @click="$router.push('/tenant')">
          <template #icon><ArrowLeftOutlined /></template>返回租户列表
        </a-button>
        <a-tag color="blue" v-if="tenantInfo">{{ tenantInfo.username }} ({{ tenantInfo.ociRegion }})</a-tag>
        <a-button type="primary" @click="openVerifyAction('createUser', null, showCreateModal)">
          <template #icon><PlusOutlined /></template>新增用户
        </a-button>
        <a-button @click="loadUsers" :loading="loading">
          <template #icon><ReloadOutlined /></template>刷新
        </a-button>
      </a-space>
    </div>

    <a-table v-if="!isMobile" :columns="columns" :data-source="users" :loading="loading" row-key="id" size="middle">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'state'">
          <a-badge :status="record.state === 'ACTIVE' ? 'success' : 'error'" :text="record.state === 'ACTIVE' ? '正常' : '已禁用'" />
        </template>
        <template v-if="column.key === 'isMfaActivated'">
          <a-tag :color="record.isMfaActivated ? 'green' : 'default'">{{ record.isMfaActivated ? '已启用' : '未启用' }}</a-tag>
        </template>
        <template v-if="column.key === 'action'">
          <a-dropdown :trigger="['click']">
            <a-button size="small" :loading="!!currentActionLoading[record.id]">
              操作 <i class="ri-arrow-down-s-line" style="margin-left: 4px"></i>
            </a-button>
            <template #overlay>
              <a-menu @click="({ key }: any) => handleMenuAction(key, record)">
                <a-menu-item key="resetPassword"><i class="ri-lock-password-line menu-icon"></i>重置密码</a-menu-item>
                <a-menu-item key="editUser"><i class="ri-edit-line menu-icon"></i>修改信息</a-menu-item>
                <a-menu-divider />
                <a-menu-item key="addToAdmin"><i class="ri-shield-user-line menu-icon"></i>加入管理员组</a-menu-item>
                <a-menu-item key="removeFromAdmin"><i class="ri-shield-cross-line menu-icon"></i>移出管理员组</a-menu-item>
                <a-menu-divider />
                <a-menu-item key="listMfa"><i class="ri-smartphone-line menu-icon"></i>MFA 设备列表</a-menu-item>
                <a-menu-item key="clearMfa"><i class="ri-delete-bin-line menu-icon"></i>清理 MFA</a-menu-item>
                <a-menu-divider />
                <a-menu-item v-if="record.state === 'ACTIVE'" key="disableUser" class="danger-item">
                  <i class="ri-forbid-line menu-icon"></i>禁用用户
                </a-menu-item>
                <a-menu-item v-else key="enableUser">
                  <i class="ri-checkbox-circle-line menu-icon"></i>启用用户
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </template>
      </template>
    </a-table>

    <!-- 移动端卡片列表 -->
    <a-spin v-else :spinning="loading">
      <a-empty v-if="!loading && users.length === 0" description="暂无用户" />
      <div v-for="u in users" :key="u.id" class="mobile-card">
        <div class="mobile-card-header">
          <span class="mobile-card-title">{{ u.name }}</span>
          <a-dropdown :trigger="['click']">
            <a-button size="small" :loading="!!currentActionLoading[u.id]">
              操作 <i class="ri-arrow-down-s-line" style="margin-left: 4px"></i>
            </a-button>
            <template #overlay>
              <a-menu @click="({ key }: any) => handleMenuAction(key, u)">
                <a-menu-item key="resetPassword"><i class="ri-lock-password-line menu-icon"></i>重置密码</a-menu-item>
                <a-menu-item key="editUser"><i class="ri-edit-line menu-icon"></i>修改信息</a-menu-item>
                <a-menu-divider />
                <a-menu-item key="addToAdmin"><i class="ri-shield-user-line menu-icon"></i>加入管理员组</a-menu-item>
                <a-menu-item key="removeFromAdmin"><i class="ri-shield-cross-line menu-icon"></i>移出管理员组</a-menu-item>
                <a-menu-divider />
                <a-menu-item key="listMfa"><i class="ri-smartphone-line menu-icon"></i>MFA 设备列表</a-menu-item>
                <a-menu-item key="clearMfa"><i class="ri-delete-bin-line menu-icon"></i>清理 MFA</a-menu-item>
                <a-menu-divider />
                <a-menu-item v-if="u.state === 'ACTIVE'" key="disableUser" class="danger-item"><i class="ri-forbid-line menu-icon"></i>禁用用户</a-menu-item>
                <a-menu-item v-else key="enableUser"><i class="ri-checkbox-circle-line menu-icon"></i>启用用户</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>
        <div class="mobile-card-body">
          <div class="mobile-card-row"><span class="label">邮箱</span><span class="value">{{ u.email || '—' }}</span></div>
          <div class="mobile-card-row">
            <span class="label">状态</span>
            <a-badge :status="u.state === 'ACTIVE' ? 'success' : 'error'" :text="u.state === 'ACTIVE' ? '正常' : '已禁用'" />
          </div>
          <div class="mobile-card-row">
            <span class="label">MFA</span>
            <a-tag :color="u.isMfaActivated ? 'green' : 'default'" style="margin: 0">{{ u.isMfaActivated ? '已启用' : '未启用' }}</a-tag>
          </div>
        </div>
      </div>
    </a-spin>

    <!-- TG 验证码弹窗（统一） -->
    <a-modal v-model:open="verifyVisible" :title="'安全验证 — ' + verifyActionLabel" :width="isMobile ? '100%' : 520" :mask-closable="false"
      @ok="handleVerifyConfirm" :confirm-loading="verifyConfirmLoading" ok-text="确认" cancel-text="取消">
      <div style="margin-bottom: 16px; color: var(--text-sub)">验证码已发送至 Telegram，请查收后输入：</div>
      <a-input v-model:value="verifyCode" placeholder="请输入6位验证码" :maxlength="6" size="large"
        @press-enter="handleVerifyConfirm" />
      <div style="margin-top: 12px; text-align: right">
        <a-button type="link" size="small" :loading="verifySending" @click="resendVerifyCode">重新发送</a-button>
      </div>
    </a-modal>

    <!-- 新增用户弹窗 -->
    <a-modal v-model:open="createVisible" title="新增用户" :width="isMobile ? '100%' : 520" @ok="handleCreate" :confirm-loading="createLoading" :mask-closable="false">
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
          <div style="color: var(--text-sub); margin-top: 8px">请立即复制保存，关闭后无法再次查看</div>
        </template>
      </a-alert>
    </a-modal>

    <!-- 修改用户信息弹窗 -->
    <a-modal v-model:open="editVisible" title="修改用户信息" :width="isMobile ? '100%' : 520" @ok="handleUpdateUser" :confirm-loading="editLoading" :mask-closable="false">
      <a-form :model="editForm" layout="vertical">
        <a-form-item label="用户名">
          <a-input :value="editingUser?.name" disabled />
        </a-form-item>
        <a-form-item label="描述 / 备注">
          <a-input v-model:value="editForm.userName" placeholder="修改描述" />
        </a-form-item>
        <a-form-item label="邮箱">
          <a-input v-model:value="editForm.email" placeholder="修改邮箱" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- MFA 设备列表弹窗 -->
    <a-modal v-model:open="mfaVisible" title="MFA 设备列表" :footer="null" :width="isMobile ? '100%' : 500">
      <a-spin :spinning="mfaLoading">
        <a-empty v-if="!mfaLoading && mfaDevices.length === 0" description="无 MFA 设备" />
        <a-list v-else :data-source="mfaDevices" size="small">
          <template #renderItem="{ item }">
            <a-list-item>
              <a-list-item-meta>
                <template #title>
                  <span>设备 ID: {{ item.id?.substring(item.id.lastIndexOf('.') + 1) }}</span>
                </template>
                <template #description>
                  <a-space>
                    <a-tag :color="item.isActivated ? 'green' : 'default'">{{ item.isActivated ? '已激活' : '未激活' }}</a-tag>
                    <span style="color: var(--text-sub); font-size: 12px">{{ item.timeCreated }}</span>
                  </a-space>
                </template>
              </a-list-item-meta>
            </a-list-item>
          </template>
        </a-list>
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeftOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import {
  listUsers, createUser, resetPassword, clearMfa,
  addToAdmin, removeFromAdmin, updateUser, updateUserState, listMfaDevices,
} from '../api/user'
import { getTenantList } from '../api/tenant'
import { sendVerifyCode, getTgStatus } from '../api/system'

const route = useRoute()
const tenantId = route.params.tenantId as string

const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }

const loading = ref(false)
const users = ref<any[]>([])
const tenantInfo = ref<any>(null)
const currentActionLoading = reactive<Record<string, boolean>>({})

const createVisible = ref(false)
const createLoading = ref(false)
const createForm = reactive({ userName: '', email: '', addToAdminGroup: false })

const pwdResultVisible = ref(false)
const pwdResult = ref('')

const editVisible = ref(false)
const editLoading = ref(false)
const editingUser = ref<any>(null)
const editForm = reactive({ userName: '', email: '' })

const mfaVisible = ref(false)
const mfaLoading = ref(false)
const mfaDevices = ref<any[]>([])

const verifyVisible = ref(false)
const verifyCode = ref('')
const verifySending = ref(false)
const verifyConfirmLoading = ref(false)
const verifyActionKey = ref('')
const verifyActionLabel = ref('')
const verifyTargetRecord = ref<any>(null)
const verifyCallback = ref<((code: string) => Promise<void>) | null>(null)

const ACTION_LABELS: Record<string, string> = {
  createUser: '新增用户',
  updateUser: '修改信息',
  removeFromAdmin: '移出管理员组',
  clearMfa: '清理 MFA',
  disableUser: '禁用用户',
}

const NEEDS_VERIFY = new Set(Object.keys(ACTION_LABELS))

const columns = [
  { title: '用户名', dataIndex: 'name', key: 'name' },
  { title: '邮箱', dataIndex: 'email', key: 'email', ellipsis: true },
  { title: '状态', key: 'state', width: 100 },
  { title: 'MFA', key: 'isMfaActivated', width: 100 },
  { title: '创建时间', dataIndex: 'timeCreated', key: 'timeCreated', width: 200, ellipsis: true },
  { title: '操作', key: 'action', width: 100 },
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

async function openVerifyAction(actionKey: string, record: any, callback: (code: string) => Promise<void> | void) {
  try {
    const tgRes = await getTgStatus()
    if (!tgRes.data) {
      message.error('未绑定 Telegram，无法执行此操作。请先在系统设置中配置 TG Bot。')
      return
    }
  } catch {
    message.error('检查 TG 状态失败')
    return
  }

  verifySending.value = true
  try {
    await sendVerifyCode(actionKey)
    message.success('验证码已发送至 Telegram')
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
    verifySending.value = false
    return
  }
  verifySending.value = false

  verifyActionKey.value = actionKey
  verifyActionLabel.value = ACTION_LABELS[actionKey] || actionKey
  verifyTargetRecord.value = record
  verifyCallback.value = callback as (code: string) => Promise<void>
  verifyCode.value = ''
  verifyVisible.value = true
}

async function resendVerifyCode() {
  verifySending.value = true
  try {
    await sendVerifyCode(verifyActionKey.value)
    message.success('验证码已重新发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    verifySending.value = false
  }
}

async function handleVerifyConfirm() {
  if (!verifyCode.value || verifyCode.value.length !== 6) {
    message.warning('请输入6位验证码')
    return
  }
  verifyConfirmLoading.value = true
  try {
    if (verifyCallback.value) {
      await verifyCallback.value(verifyCode.value)
    }
    verifyVisible.value = false
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    verifyConfirmLoading.value = false
  }
}

function handleMenuAction(key: string, record: any) {
  switch (key) {
    case 'resetPassword': confirmAction('确定重置该用户密码？', () => handleResetPassword(record)); break
    case 'editUser': openVerifyAction('updateUser', record, (code) => openEditUserWithCode(record, code)); break
    case 'addToAdmin': confirmAction('确定加入管理员组？', () => handleAddToAdmin(record)); break
    case 'removeFromAdmin': openVerifyAction('removeFromAdmin', record, (code) => handleRemoveFromAdminWithCode(record, code)); break
    case 'listMfa': handleListMfa(record); break
    case 'clearMfa': openVerifyAction('clearMfa', record, (code) => handleClearMfaWithCode(record, code)); break
    case 'disableUser': openVerifyAction('disableUser', record, (code) => handleDisableWithCode(record, code)); break
    case 'enableUser': confirmAction('确定启用该用户？', () => handleToggleState(record, false)); break
  }
}

function confirmAction(title: string, onOk: () => void) {
  Modal.confirm({ title, onOk })
}

async function handleResetPassword(record: any) {
  currentActionLoading[record.id] = true
  try {
    const res = await resetPassword({ tenantId, userId: record.id })
    pwdResult.value = res.data || ''
    pwdResultVisible.value = true
  } catch (e: any) {
    message.error(e?.message || '重置密码失败')
  } finally {
    currentActionLoading[record.id] = false
  }
}

let pendingEditVerifyCode = ''

async function openEditUserWithCode(record: any, code: string) {
  pendingEditVerifyCode = code
  editingUser.value = record
  editForm.userName = record.description || ''
  editForm.email = record.email || ''
  editVisible.value = true
}

async function handleUpdateUser() {
  editLoading.value = true
  try {
    await updateUser({ tenantId, userId: editingUser.value.id, ...editForm, verifyCode: pendingEditVerifyCode })
    message.success('用户信息已更新')
    editVisible.value = false
    pendingEditVerifyCode = ''
    loadUsers()
  } catch (e: any) {
    message.error(e?.message || '更新失败')
  } finally {
    editLoading.value = false
  }
}

function showCreateModal(code: string) {
  createForm.userName = ''
  createForm.email = ''
  createForm.addToAdminGroup = false
  pendingCreateVerifyCode = code
  createVisible.value = true
}

let pendingCreateVerifyCode = ''

async function handleCreate() {
  if (!createForm.userName) { message.warning('请填写用户名'); return }
  createLoading.value = true
  try {
    await createUser({ tenantId, ...createForm, verifyCode: pendingCreateVerifyCode })
    message.success('用户创建成功')
    createVisible.value = false
    pendingCreateVerifyCode = ''
    loadUsers()
  } catch (e: any) {
    message.error(e?.message || '创建失败')
  } finally {
    createLoading.value = false
  }
}

async function handleClearMfaWithCode(record: any, code: string) {
  currentActionLoading[record.id] = true
  try {
    await clearMfa({ tenantId, userId: record.id, verifyCode: code })
    message.success('MFA 已清理')
    loadUsers()
  } catch (e: any) {
    message.error(e?.message || '清理 MFA 失败')
  } finally {
    currentActionLoading[record.id] = false
  }
}

async function handleRemoveFromAdminWithCode(record: any, code: string) {
  currentActionLoading[record.id] = true
  try {
    await removeFromAdmin({ tenantId, userId: record.id, verifyCode: code })
    message.success('已移出管理员组')
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    currentActionLoading[record.id] = false
  }
}

async function handleDisableWithCode(record: any, code: string) {
  currentActionLoading[record.id] = true
  try {
    await updateUserState({ tenantId, userId: record.id, blocked: true, verifyCode: code })
    message.success('用户已禁用')
    loadUsers()
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    currentActionLoading[record.id] = false
  }
}

async function handleAddToAdmin(record: any) {
  currentActionLoading[record.id] = true
  try {
    await addToAdmin({ tenantId, userId: record.id })
    message.success('已加入管理员组')
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    currentActionLoading[record.id] = false
  }
}

async function handleToggleState(record: any, blocked: boolean) {
  currentActionLoading[record.id] = true
  try {
    await updateUserState({ tenantId, userId: record.id, blocked })
    message.success(blocked ? '用户已禁用' : '用户已启用')
    loadUsers()
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    currentActionLoading[record.id] = false
  }
}

async function handleListMfa(record: any) {
  mfaDevices.value = []
  mfaLoading.value = true
  mfaVisible.value = true
  try {
    const res = await listMfaDevices({ tenantId, userId: record.id })
    mfaDevices.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '获取 MFA 设备失败')
  } finally {
    mfaLoading.value = false
  }
}

onMounted(() => {
  loadTenantInfo()
  loadUsers()
  window.addEventListener('resize', checkMobile)
})
onUnmounted(() => window.removeEventListener('resize', checkMobile))
</script>

<style scoped>
.table-toolbar {
  margin-bottom: 16px;
  transition: var(--trans);
}
.menu-icon {
  margin-right: 8px;
  font-size: 16px;
  vertical-align: -0.15em;
}
.danger-item {
  color: var(--danger) !important;
}
:deep(.ant-modal .ant-alert-description > div:last-child) {
  color: var(--text-sub) !important;
}
@media (max-width: 768px) {
  .table-toolbar :deep(.ant-space) {
    flex-wrap: wrap;
    width: 100%;
    gap: 8px !important;
  }
}
</style>
