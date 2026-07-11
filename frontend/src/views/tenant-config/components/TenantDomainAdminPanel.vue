<template>
  <div class="domain-admin-panel">
    <div class="panel-toolbar">
      <a-alert type="info" show-icon message="创建、编辑和删除均由 OCI IAM Identity Domains 控制面异步执行；提交后请保留 Work Request ID。" />
      <a-button type="primary" @click="openCreate">创建域</a-button>
    </div>

    <a-table :data-source="domains" :columns="columns" :pagination="false" row-key="domainId" size="small" :scroll="{ x: 760 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <a-space>
            <span>{{ record.displayName || '—' }}</span>
            <a-tag v-if="isDefault(record)" color="blue">默认域</a-tag>
          </a-space>
        </template>
        <template v-else-if="column.key === 'state'">
          <a-tag :color="record.lifecycleState === 'ACTIVE' ? 'success' : 'default'">{{ record.lifecycleState || '—' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-space>
            <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
            <a-button type="link" size="small" danger :disabled="isDefault(record)" @click="openDelete(record)">删除</a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-empty v-if="!domains.length" description="暂无 Identity Domain" />
    <a-alert v-if="lastWorkRequestId" type="success" show-icon style="margin-top: 12px" message="操作已提交">
      <template #description>Work Request ID：<a-typography-text copyable>{{ lastWorkRequestId }}</a-typography-text></template>
    </a-alert>

    <a-modal v-model:open="formVisible" :title="formMode === 'create' ? '创建 Identity Domain' : '编辑 Identity Domain'" :confirm-loading="submitting" @ok="submitForm">
      <a-form layout="vertical">
        <a-form-item label="显示名称" required><a-input v-model:value="form.displayName" /></a-form-item>
        <a-form-item label="说明" :required="formMode === 'create'"><a-textarea v-model:value="form.description" :rows="3" /></a-form-item>
        <template v-if="formMode === 'create'">
          <a-form-item label="域类型" required>
            <a-select v-model:value="form.licenseType" :options="licenseOptions" :loading="licenseTypesLoading" />
          </a-form-item>
          <a-form-item label="主区域" required><a-input v-model:value="form.homeRegion" placeholder="例如：ap-singapore-1" /></a-form-item>
          <a-divider orientation="left">域管理员</a-divider>
          <a-form-item label="创建此域的管理用户">
            <a-switch v-model:checked="form.createAdmin" />
          </a-form-item>
          <template v-if="form.createAdmin">
            <a-form-item label="管理员的名字"><a-input v-model:value="form.adminFirstName" /></a-form-item>
            <a-form-item label="管理员的姓氏" required><a-input v-model:value="form.adminLastName" /></a-form-item>
            <a-form-item :label="form.useEmailAsUserName ? '管理员的用户名 / 电子邮件' : '管理员的用户名'" required>
              <a-input v-model:value="form.adminUserName" />
            </a-form-item>
            <a-form-item v-if="!form.useEmailAsUserName" label="管理员的电子邮件" required>
              <a-input v-model:value="form.adminEmail" type="email" />
            </a-form-item>
            <a-form-item label="将电子邮件地址用作用户名">
              <a-switch v-model:checked="form.useEmailAsUserName" @change="syncAdminEmail" />
            </a-form-item>
          </template>
        </template>
        <a-form-item label="在登录页隐藏"><a-switch v-model:checked="form.isHiddenOnLogin" /></a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="createVerifyVisible" title="安全验证 — 创建 Identity Domain" ok-text="确认创建" :confirm-loading="submitting" @ok="submitCreateVerified">
      <a-alert type="warning" show-icon message="验证码已发送至 Telegram" description="创建域是异步管理操作，验证通过后才会提交到 Oracle。" style="margin-bottom: 12px" />
      <a-input v-model:value="createVerifyCode" maxlength="6" inputmode="numeric" placeholder="请输入 6 位 TG 验证码" size="large" @pressEnter="submitCreateVerified" />
      <div class="verify-actions">
        <span>验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="verifyCodeSending" @click="sendCreateVerifyCode">重新发送</a-button>
      </div>
    </a-modal>

    <a-modal v-model:open="deleteVisible" title="删除 Identity Domain" ok-text="确认删除" ok-type="danger" :confirm-loading="submitting" @ok="submitDelete">
      <a-alert type="error" show-icon message="删除属于危险异步操作，默认域不可删除。" style="margin-bottom: 12px" />
      <p>目标域：{{ deleteTarget?.displayName }}</p>
      <a-input v-model:value="deleteVerifyCode" maxlength="6" placeholder="请输入 TG 验证码" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { createIdentityDomain, deleteIdentityDomain, listAllowedIdentityDomainLicenseTypes, unlockIdentityDomainCreate, updateIdentityDomain } from '../../../api/tenant'
import { sendVerifyCode } from '../../../api/system'

const props = defineProps<{ tenantId: string; domains: any[]; defaultHomeRegion?: string }>()
const emit = defineEmits<{ (e: 'refresh'): void }>()
const columns = [
  { title: '显示名称', key: 'name', width: 220 },
  { title: '类型', dataIndex: 'type', key: 'type', width: 160 },
  { title: '状态', key: 'state', width: 120 },
  { title: '域 OCID', dataIndex: 'domainId', key: 'domainId', ellipsis: true },
  { title: '操作', key: 'actions', width: 130, fixed: 'right' },
]
const licenseOptions = ref<any[]>([])
const licenseTypesLoading = ref(false)
const formVisible = ref(false)
const deleteVisible = ref(false)
const createVerifyVisible = ref(false)
const createVerifyCode = ref('')
const createAccessToken = ref('')
const verifyCodeSending = ref(false)
const submitting = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const editDomainId = ref('')
const deleteTarget = ref<any>(null)
const deleteVerifyCode = ref('')
const lastWorkRequestId = ref('')
const form = reactive<any>({ displayName: '', description: '', licenseType: 'FREE', homeRegion: '', createAdmin: true, useEmailAsUserName: true, adminFirstName: '', adminLastName: '', adminUserName: '', adminEmail: '', isHiddenOnLogin: false })

function isDefault(domain: any) { return String(domain?.displayName || '').toLowerCase() === 'default' }
function resetForm() { Object.assign(form, { displayName: '', description: '', licenseType: '', homeRegion: props.defaultHomeRegion || '', createAdmin: true, useEmailAsUserName: true, adminFirstName: '', adminLastName: '', adminUserName: '', adminEmail: '', isHiddenOnLogin: false }) }
async function loadAllowedLicenseTypes() {
  licenseTypesLoading.value = true
  try {
    const res = await listAllowedIdentityDomainLicenseTypes({ id: props.tenantId })
    const rows = res.data || []
    licenseOptions.value = rows.map((item: any) => ({ label: item.name || item.licenseType, value: item.licenseType, title: item.description }))
    if (!licenseOptions.value.length) throw new Error('Oracle 未返回当前租户允许的域类型')
    if (!licenseOptions.value.some(item => item.value === form.licenseType)) form.licenseType = licenseOptions.value[0]?.value || ''
  } catch (error: any) {
    licenseOptions.value = []
    form.licenseType = ''
    message.error(error?.message || '读取租户允许的域类型失败，暂时无法创建域')
  } finally { licenseTypesLoading.value = false }
}
async function openCreate() {
  formMode.value = 'create'
  editDomainId.value = ''
  createVerifyCode.value = ''
  createAccessToken.value = ''
  createVerifyVisible.value = true
  await sendCreateVerifyCode()
}
function openEdit(domain: any) { formMode.value = 'edit'; editDomainId.value = domain.domainId; resetForm(); Object.assign(form, { displayName: domain.displayName || '', description: domain.description || '', isHiddenOnLogin: !!domain.isHiddenOnLogin }); formVisible.value = true }
function openDelete(domain: any) { deleteTarget.value = domain; deleteVerifyCode.value = ''; deleteVisible.value = true }

function syncAdminEmail() {
  if (form.useEmailAsUserName) form.adminEmail = form.adminUserName
}

function validateCreateForm() {
  if (!form.displayName?.trim() || !form.homeRegion?.trim() || !form.licenseType) { message.warning('请填写显示名称、主区域并选择域类型'); return false }
  if (form.createAdmin && (!form.adminLastName?.trim() || !form.adminUserName?.trim() || (!form.useEmailAsUserName && !form.adminEmail?.trim()))) {
    message.warning('请填写管理员的姓氏、用户名和电子邮件')
    return false
  }
  return true
}

async function sendCreateVerifyCode() {
  verifyCodeSending.value = true
  try { await sendVerifyCode('identityDomainCreate'); message.success('验证码已发送') }
  catch (error: any) { message.error(error?.message || '发送验证码失败') }
  finally { verifyCodeSending.value = false }
}

async function submitForm() {
  if (!form.displayName?.trim()) { message.warning('请填写显示名称'); return }
  if (formMode.value === 'create') {
    if (!validateCreateForm()) return
    await submitCreate()
    return
  }
  submitting.value = true
  try {
    const res = await updateIdentityDomain({ id: props.tenantId, displayName: form.displayName, description: form.description, isHiddenOnLogin: form.isHiddenOnLogin, domainId: editDomainId.value })
    lastWorkRequestId.value = res.data?.workRequestId || ''
    message.success('操作已提交')
    formVisible.value = false
    emit('refresh')
  } finally { submitting.value = false }
}

async function submitCreateVerified() {
  if (!/^\d{6}$/.test(createVerifyCode.value.trim())) { message.warning('请输入 6 位 TG 验证码'); return }
  submitting.value = true
  try {
    const res = await unlockIdentityDomainCreate({ verifyCode: createVerifyCode.value.trim() })
    createAccessToken.value = res.data?.accessToken || ''
    if (!createAccessToken.value) throw new Error('未获得创建域授权')
    createVerifyVisible.value = false
    resetForm()
    formVisible.value = true
    await loadAllowedLicenseTypes()
    message.success('验证通过，请填写创建域信息')
  } finally { submitting.value = false }
}

async function submitCreate() {
  if (!createAccessToken.value) { message.warning('创建授权已失效，请重新点击创建域完成验证'); formVisible.value = false; return }
  submitting.value = true
  try {
    const payload: Record<string, any> = {
      id: props.tenantId,
      accessToken: createAccessToken.value,
      displayName: form.displayName,
      description: form.description,
      licenseType: form.licenseType,
      homeRegion: form.homeRegion,
      isHiddenOnLogin: form.isHiddenOnLogin,
    }
    if (form.createAdmin) Object.assign(payload, {
      adminFirstName: form.adminFirstName,
      adminLastName: form.adminLastName,
      adminUserName: form.adminUserName,
      adminEmail: form.useEmailAsUserName ? form.adminUserName : form.adminEmail,
      isPrimaryEmailRequired: true,
    })
    const res = await createIdentityDomain(payload)
    lastWorkRequestId.value = res.data?.workRequestId || ''
    message.success('创建域任务已提交')
    formVisible.value = false
    createAccessToken.value = ''
    emit('refresh')
  } finally { submitting.value = false }
}

async function submitDelete() {
  if (!deleteVerifyCode.value.trim()) { message.warning('请输入 TG 验证码'); return }
  submitting.value = true
  try {
    const res = await deleteIdentityDomain({ id: props.tenantId, domainId: deleteTarget.value.domainId, verifyCode: deleteVerifyCode.value })
    lastWorkRequestId.value = res.data?.workRequestId || ''
    message.success('删除任务已提交')
    deleteVisible.value = false
    emit('refresh')
  } finally { submitting.value = false }
}
</script>

<style scoped>
.panel-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.panel-toolbar :deep(.ant-alert) { flex: 1; }
.verify-actions { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; color: var(--text-sub); font-size: 12px; }
@media (max-width: 768px) { .panel-toolbar { align-items: stretch; flex-direction: column; } }
</style>
