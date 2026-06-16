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
        <template v-if="column.key === 'domainName'">
          <a-tag color="blue">{{ formatUserDomain(record) }}</a-tag>
        </template>
        <template v-if="column.key === 'lastSuccessfulLoginTime'">
          <span :title="String(record.lastSuccessfulLoginTime ?? '')">{{ formatUserTime(record.lastSuccessfulLoginTime) }}</span>
        </template>
        <template v-if="column.key === 'timeCreated'">
          <span :title="String(record.timeCreated ?? '')">{{ formatUserTime(record.timeCreated) }}</span>
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
                <a-menu-item key="editCapabilities"><i class="ri-user-settings-line menu-icon"></i>编辑用户权限</a-menu-item>
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
                <a-menu-item key="editCapabilities"><i class="ri-user-settings-line menu-icon"></i>编辑用户权限</a-menu-item>
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
          <div class="mobile-card-row"><span class="label">域</span><span class="value">{{ formatUserDomain(u) }}</span></div>
          <div class="mobile-card-row"><span class="label">上次登录</span><span class="value">{{ formatUserTime(u.lastSuccessfulLoginTime) }}</span></div>
          <div class="mobile-card-row"><span class="label">创建时间</span><span class="value">{{ formatUserTime(u.timeCreated) }}</span></div>
        </div>
      </div>
    </a-spin>

    <!-- TG 验证码弹窗（统一）；destroy-on-close 避免与后续业务弹窗叠层时残留焦点/误触 -->
    <a-modal :keyboard="false" v-model:open="verifyVisible" :title="'安全验证 — ' + verifyActionLabel" :width="isMobile ? '100%' : 520" :mask-closable="false" destroy-on-close
      @ok="handleVerifyConfirm" :confirm-loading="verifyConfirmLoading" ok-text="确认" cancel-text="取消">
      <div style="margin-bottom: 16px; color: var(--text-sub)">验证码已发送至 Telegram，请查收后输入：</div>
      <a-input v-model:value="verifyCode" placeholder="请输入6位验证码" :maxlength="6" size="large"
        @press-enter="handleVerifyConfirm" />
      <div style="margin-top: 12px; text-align: right">
        <a-button type="link" size="small" :loading="verifySending" @click="resendVerifyCode">重新发送</a-button>
      </div>
    </a-modal>

    <!-- 新增用户弹窗 -->
    <a-modal :keyboard="false" v-model:open="createVisible" title="新增用户" :width="isMobile ? '100%' : 520" @ok="handleCreate" :confirm-loading="createLoading" :mask-closable="false">
      <a-form :model="createForm" layout="vertical">
        <a-form-item label="Identity Domain">
          <a-select
            v-model:value="createForm.domainId"
            :loading="identityDomainsLoading"
            placeholder="加载域列表…"
            show-search
            allow-clear
            style="width: 100%"
            :filter-option="filterDomainOption"
            :get-popup-container="domainSelectPopupContainer"
          >
            <a-select-option
              v-for="d in identityDomains"
              :key="d.id"
              :value="d.id"
              :label="`${d.displayName || d.id} (${d.type || '—'})`"
            >
              {{ d.displayName || d.id }} ({{ d.type || '—' }})
            </a-select-option>
          </a-select>
          <div v-if="identityDomainsLoadError" style="margin-top: 6px; color: var(--text-sub); font-size: 12px">
            域列表加载失败，将使用经典 Identity API 创建（不指定域）。
          </div>
          <div v-else-if="!identityDomainsLoading && identityDomains.length === 0" style="margin-top: 6px; color: var(--text-sub); font-size: 12px">
            未返回域列表（例如未启用 Identity Domains 的区域），将使用经典 Identity API。
          </div>
        </a-form-item>
        <a-form-item label="用户名" required>
          <a-input v-model:value="createForm.userName" placeholder="登录用户名" />
        </a-form-item>
        <a-form-item label="邮箱">
          <a-input v-model:value="createForm.email" placeholder="user@example.com" />
        </a-form-item>
        <a-form-item v-if="createUseDefaultDomain" label="加入管理员组">
          <a-switch v-model:checked="createForm.addToAdminGroup" />
        </a-form-item>
        <a-form-item v-else label="加入用户组">
          <a-select
            v-model:value="createForm.groupIds"
            mode="multiple"
            placeholder="选择要加入的组（可多选）"
            style="width: 100%"
            show-search
            :loading="createDomainGroupsLoading"
            :options="createDomainGroupOptions"
            :filter-option="filterGroupSelectOption"
            :disabled="!createForm.domainId || createDomainGroupsLoading"
          />
          <div v-if="createForm.domainId && !createDomainGroupsLoading && createDomainGroups.length === 0" style="margin-top: 6px; color: var(--text-sub); font-size: 12px">
            该域下未返回可分配的组，创建后可在 OCI 控制台为该用户分配组。
          </div>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 密码结果弹窗 -->
    <a-modal :keyboard="false" v-model:open="pwdResultVisible" title="新密码" :footer="null" :mask-closable="false">
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

    <!-- 编辑用户权限（控制台 Capabilities） -->
    <a-modal :keyboard="false"
      v-model:open="capabilitiesVisible"
      title="编辑用户权限"
      :width="isMobile ? '100%' : 520"
      @ok="handleUpdateCapabilities"
      :confirm-loading="capabilitiesSaving"
      :mask-closable="false"
    >
      <a-spin :spinning="capabilitiesLoading">
        <div v-if="capabilitiesUser" style="margin-bottom: 12px; color: var(--text-sub)">
          用户：<b>{{ capabilitiesUser.name }}</b>
        </div>
        <a-form layout="vertical">
          <a-form-item v-for="item in CAPABILITY_ITEMS" :key="item.key" :label="item.label">
            <a-switch v-model:checked="capabilitiesForm[item.key]" />
          </a-form-item>
        </a-form>
      </a-spin>
    </a-modal>

    <!-- 修改用户信息弹窗 -->
    <a-modal :keyboard="false" v-model:open="editVisible" title="修改用户信息" :width="isMobile ? '100%' : 560" @ok="handleUpdateUser" :confirm-loading="editLoading" :mask-closable="false">
      <a-spin :spinning="editGroupsLoading">
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
          <a-form-item label="所属用户组">
            <a-select
              v-model:value="editForm.groupIds"
              mode="multiple"
              placeholder="选择用户组（可多选）"
              style="width: 100%"
              show-search
              :filter-option="filterGroupSelectOption"
              :options="tenantGroupOptions"
              :disabled="editGroupsLoading"
            />
            <div v-if="editCurrentGroupNames.length" style="margin-top: 8px; color: var(--text-sub); font-size: 12px">
              当前所属：{{ editCurrentGroupNames.join('、') }}
            </div>
            <div style="margin-top: 6px; color: var(--text-sub); font-size: 12px">
              保存时将按所选组同步加入/移出（与 OCI IAM 组成员关系一致）。
            </div>
          </a-form-item>
        </a-form>
      </a-spin>
    </a-modal>

    <!-- MFA 设备列表弹窗 -->
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="mfaVisible" title="MFA 设备列表" :footer="null" :width="isMobile ? '100%' : 500">
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
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeftOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import {
  listUsers, listIdentityDomains, createUser, resetPassword, clearMfa,
  addToAdmin, removeFromAdmin, updateUser, updateUserState, listMfaDevices,
  getUserCapabilities, updateUserCapabilities, listGroups, getUserGroups, listDomainGroups,
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
const createForm = reactive({
  domainId: '' as string,
  userName: '',
  email: '',
  addToAdminGroup: false,
  groupIds: [] as string[],
})
const createDomainGroups = ref<{ id: string; name: string; description?: string }[]>([])
const createDomainGroupsLoading = ref(false)

const createUseDefaultDomain = computed(() => {
  const id = createForm.domainId
  if (!id) return true
  const d = identityDomains.value.find((x: any) => x.id === id)
  return !d || d.displayName === 'Default'
})

const createDomainGroupOptions = computed(() =>
  createDomainGroups.value.map((g) => ({
    value: g.id,
    label: g.description ? `${g.name} — ${g.description}` : g.name,
  })),
)

const identityDomains = ref<any[]>([])
const identityDomainsLoading = ref(false)
const identityDomainsLoadError = ref(false)

function filterDomainOption(input: string, option: any) {
  const label = String(option?.label ?? '')
  return label.toLowerCase().includes(input.toLowerCase())
}

/** 将域下拉拉到当前 Modal 内，避免选项点击穿透到其它弹窗的「确定」触发验证码校验 */
function domainSelectPopupContainer(trigger: HTMLElement) {
  return (trigger.closest('.ant-modal-content') as HTMLElement) || document.body
}

function pickDefaultDomainId(): string {
  const list = identityDomains.value
  const def = list.find((x: any) => x.displayName === 'Default')
  return def?.id ?? list[0]?.id ?? ''
}

async function prefetchDomainsForCreate() {
  identityDomainsLoading.value = true
  identityDomainsLoadError.value = false
  try {
    const res = await listIdentityDomains({ tenantId })
    identityDomains.value = res.data || []
  } catch {
    identityDomainsLoadError.value = true
    identityDomains.value = []
  } finally {
    identityDomainsLoading.value = false
  }
}

watch(identityDomains, (list) => {
  if (!createVisible.value || !list?.length) return
  if (!createForm.domainId) {
    createForm.domainId = pickDefaultDomainId()
  }
})

watch(
  () => createForm.domainId,
  async (domainId) => {
    if (!createVisible.value) return
    createForm.groupIds = []
    createForm.addToAdminGroup = false
    if (!domainId || createUseDefaultDomain.value) {
      createDomainGroups.value = []
      return
    }
    createDomainGroupsLoading.value = true
    try {
      const res = await listDomainGroups({ tenantId, domainId })
      const all = (res.data || []) as { id: string; name: string; description?: string }[]
      createDomainGroups.value = all.map((g) => ({
        id: g.id,
        name: g.name || g.id,
        description: g.description,
      }))
    } catch (e: any) {
      createDomainGroups.value = []
      message.error(e?.message || '加载域内用户组失败')
    } finally {
      createDomainGroupsLoading.value = false
    }
  },
)

const pwdResultVisible = ref(false)
const pwdResult = ref('')

const editVisible = ref(false)
const editLoading = ref(false)
const editGroupsLoading = ref(false)
const editingUser = ref<any>(null)
const editForm = reactive({ userName: '', email: '', groupIds: [] as string[] })
const tenantGroups = ref<{ id: string; name: string; description?: string }[]>([])
const editCurrentGroupNames = ref<string[]>([])

/** OCI 内置全员组：不在此界面展示或编辑，与控制台 Groups 习惯一致 */
function isHiddenIamGroupName(name: string | undefined | null): boolean {
  return (name || '').trim().toLowerCase() === 'all domain users'
}

const tenantGroupOptions = computed(() =>
  tenantGroups.value.map((g) => ({
    value: g.id,
    label: g.description ? `${g.name} — ${g.description}` : g.name,
  })),
)

function filterGroupSelectOption(input: string, option: { label?: string }) {
  return String(option?.label ?? '').toLowerCase().includes(input.toLowerCase())
}

const capabilitiesVisible = ref(false)
const capabilitiesLoading = ref(false)
const capabilitiesSaving = ref(false)
const capabilitiesUser = ref<any>(null)
const capabilitiesForm = reactive<Record<string, boolean>>({})
let pendingCapabilitiesVerifyCode = ''

function initCapabilitiesForm(data?: Record<string, boolean>) {
  for (const item of CAPABILITY_ITEMS) {
    capabilitiesForm[item.key] = !!data?.[item.key]
  }
}

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

const CAPABILITY_ITEMS = [
  { key: 'canUseConsolePassword', label: '控制台本地密码 (Local password)' },
  { key: 'canUseApiKeys', label: 'API 密钥 (API keys)' },
  { key: 'canUseAuthTokens', label: '认证令牌 (Auth token)' },
  { key: 'canUseSmtpCredentials', label: 'SMTP 凭证 (SMTP credentials)' },
  { key: 'canUseCustomerSecretKeys', label: '客户秘密密钥 (Customer secret keys)' },
  { key: 'canUseOAuth2ClientCredentials', label: 'OAuth 2.0 客户端凭证' },
  { key: 'canUseDbCredentials', label: '数据库密码 (Database passwords)' },
] as const

const ACTION_LABELS: Record<string, string> = {
  createUser: '新增用户',
  updateUser: '修改信息',
  updateUserCapabilities: '编辑用户权限',
  removeFromAdmin: '移出管理员组',
  clearMfa: '清理 MFA',
  disableUser: '禁用用户',
}

const NEEDS_VERIFY = new Set(Object.keys(ACTION_LABELS))

function formatUserTime(v: unknown): string {
  if (v == null || v === '') return '—'
  const s = String(v).trim()
  const d = dayjs(s)
  if (!d.isValid()) return s
  return d.format('YYYY-MM-DD HH:mm:ss')
}

function formatUserDomain(record: any): string {
  const name = String(record?.domainName || 'Default').trim()
  const type = String(record?.domainType || '').trim()
  if (!type || type === 'DEFAULT') return name
  return `${name} / ${type}`
}

const columns = [
  { title: '用户名', dataIndex: 'name', key: 'name' },
  { title: '域', key: 'domainName', width: 180, ellipsis: true },
  { title: '邮箱', dataIndex: 'email', key: 'email', ellipsis: true },
  { title: '状态', key: 'state', width: 100 },
  { title: 'MFA', key: 'isMfaActivated', width: 100 },
  { title: '上次登录时间', key: 'lastSuccessfulLoginTime', width: 176 },
  { title: '创建时间', key: 'timeCreated', width: 176 },
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

  if (actionKey === 'createUser') {
    prefetchDomainsForCreate()
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
  const code = verifyCode.value
  const cb = verifyCallback.value
  const action = verifyActionKey.value
  try {
    // 新增用户：先关掉验证码弹窗再打开「新增用户」，避免与 Select 下拉叠层导致误触「确认」再次校验验证码
    if (action === 'createUser' && cb) {
      verifyVisible.value = false
      verifyCode.value = ''
      await cb(code)
    } else {
      if (cb) {
        await cb(code)
      }
      verifyVisible.value = false
      verifyCode.value = ''
    }
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
    case 'editCapabilities': openVerifyAction('updateUserCapabilities', record, (code) => openCapabilitiesWithCode(record, code)); break
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
  editForm.groupIds = []
  editCurrentGroupNames.value = []
  tenantGroups.value = []
  editVisible.value = true
  editGroupsLoading.value = true
  try {
    const [allRes, userRes] = await Promise.all([
      listGroups({ tenantId }),
      getUserGroups({ tenantId, userId: record.id }),
    ])
    const all = (allRes.data || []) as { id: string; name: string; description?: string }[]
    tenantGroups.value = all
      .filter((g) => !isHiddenIamGroupName(g.name))
      .map((g) => ({
        id: g.id,
        name: g.name || g.id,
        description: g.description,
      }))
    const memberships = (userRes.data || []) as { groupId: string; name?: string }[]
    const visibleMemberships = memberships.filter((m) => !isHiddenIamGroupName(m.name))
    editForm.groupIds = visibleMemberships.map((m) => m.groupId).filter(Boolean)
    editCurrentGroupNames.value = visibleMemberships.map((m) => m.name || m.groupId).filter(Boolean)
  } catch (e: any) {
    message.error(e?.message || '加载用户组失败')
    editVisible.value = false
    pendingEditVerifyCode = ''
  } finally {
    editGroupsLoading.value = false
  }
}

async function openCapabilitiesWithCode(record: any, code: string) {
  pendingCapabilitiesVerifyCode = code
  capabilitiesUser.value = record
  initCapabilitiesForm()
  capabilitiesVisible.value = true
  capabilitiesLoading.value = true
  try {
    const res = await getUserCapabilities({ tenantId, userId: record.id })
    initCapabilitiesForm(res.data as Record<string, boolean>)
  } catch (e: any) {
    message.error(e?.message || '加载用户权限失败')
    capabilitiesVisible.value = false
    pendingCapabilitiesVerifyCode = ''
  } finally {
    capabilitiesLoading.value = false
  }
}

async function handleUpdateCapabilities() {
  if (!capabilitiesUser.value?.id || !pendingCapabilitiesVerifyCode) {
    message.warning('请先完成 Telegram 验证')
    return
  }
  capabilitiesSaving.value = true
  try {
    const capabilities: Record<string, boolean> = {}
    for (const item of CAPABILITY_ITEMS) {
      capabilities[item.key] = !!capabilitiesForm[item.key]
    }
    await updateUserCapabilities({
      tenantId,
      userId: capabilitiesUser.value.id,
      verifyCode: pendingCapabilitiesVerifyCode,
      capabilities,
    })
    message.success('用户权限已更新')
    capabilitiesVisible.value = false
    pendingCapabilitiesVerifyCode = ''
    capabilitiesUser.value = null
  } catch (e: any) {
    message.error(e?.message || '更新用户权限失败')
  } finally {
    capabilitiesSaving.value = false
  }
}

async function handleUpdateUser() {
  editLoading.value = true
  try {
    await updateUser({
      tenantId,
      userId: editingUser.value.id,
      userName: editForm.userName,
      email: editForm.email,
      groupIds: [...editForm.groupIds],
      verifyCode: pendingEditVerifyCode,
    })
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

async function showCreateModal(code: string) {
  createForm.domainId = pickDefaultDomainId()
  createForm.userName = ''
  createForm.email = ''
  createForm.addToAdminGroup = false
  createForm.groupIds = []
  createDomainGroups.value = []
  pendingCreateVerifyCode = code
  createVisible.value = true
  if (createForm.domainId && !createUseDefaultDomain.value) {
    createDomainGroupsLoading.value = true
    try {
      const res = await listDomainGroups({ tenantId, domainId: createForm.domainId })
      const all = (res.data || []) as { id: string; name: string; description?: string }[]
      createDomainGroups.value = all.map((g) => ({
        id: g.id,
        name: g.name || g.id,
        description: g.description,
      }))
    } catch {
      createDomainGroups.value = []
    } finally {
      createDomainGroupsLoading.value = false
    }
  }
}

let pendingCreateVerifyCode = ''

async function handleCreate() {
  if (!createForm.userName) { message.warning('请填写用户名'); return }
  createLoading.value = true
  try {
    const payload: Record<string, any> = {
      tenantId,
      userName: createForm.userName,
      email: createForm.email,
      verifyCode: pendingCreateVerifyCode,
    }
    if (createForm.domainId) {
      payload.domainId = createForm.domainId
    }
    if (createUseDefaultDomain.value) {
      payload.addToAdminGroup = createForm.addToAdminGroup
    } else if (createForm.groupIds.length) {
      payload.groupIds = [...createForm.groupIds]
    }
    await createUser(payload)
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
