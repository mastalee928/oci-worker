<template>
  <div class="domain-admin-panel">
    <template v-if="detailDomain">
      <div class="detail-heading">
        <a-button type="text" @click="closeDetail"><i class="ri-arrow-left-line"></i> 返回域列表</a-button>
        <div>
          <strong>{{ detailDomain.displayName || '未命名域' }}</strong>
          <a-tag :color="detailDomain.lifecycleState === 'ACTIVE' ? 'success' : 'default'">{{ detailDomain.lifecycleState || '未知状态' }}</a-tag>
        </div>
      </div>
      <a-spin :spinning="detailLoading">
        <dl class="domain-detail-list">
          <div><dt>OCID</dt><dd><a-typography-text v-if="detailDomain.domainId" copyable>{{ detailDomain.domainId }}</a-typography-text><span v-else>-</span></dd></div>
          <div><dt>域类型</dt><dd>{{ licenseTypeLabel(detailDomain.licenseType) }}</dd></div>
          <div><dt>说明</dt><dd><a-typography-text v-if="detailDomain.description" copyable>{{ detailDomain.description }}</a-typography-text><span v-else>-</span></dd></div>
          <div><dt>创建日期</dt><dd>{{ formatDate(detailDomain.timeCreated) }}</dd></div>
          <div><dt>登录时是否显示域</dt><dd>{{ detailDomain.isHiddenOnLogin === true ? '否' : detailDomain.isHiddenOnLogin === false ? '是' : '-' }}</dd></div>
          <div><dt>域 URL</dt><dd><a-typography-text v-if="detailDomain.url" copyable>{{ detailDomain.url }}</a-typography-text><span v-else>-</span></dd></div>
          <div><dt>区域 URL</dt><dd><a-typography-text v-if="detailDomain.homeRegionUrl" copyable>{{ detailDomain.homeRegionUrl }}</a-typography-text><span v-else>-</span></dd></div>
          <div><dt>状态</dt><dd><a-tag :color="detailDomain.lifecycleState === 'ACTIVE' ? 'success' : 'default'">{{ detailDomain.lifecycleState || '-' }}</a-tag></dd></div>
        </dl>
      </a-spin>
    </template>

    <template v-else>
    <div class="panel-toolbar">
      <a-button type="primary" @click="openCreate">创建域</a-button>
    </div>

    <a-table v-if="!isMobile" :data-source="domains" :columns="columns" :pagination="false" row-key="domainId" size="small">
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
            <a-button type="link" size="small" @click="openDetail(record)">详细信息</a-button>
            <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
            <a-button type="link" size="small" @click="openLicenseChange(record)">更改类型</a-button>
            <a-button type="link" size="small" danger :disabled="isDefault(record)" @click="openDelete(record)">删除</a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <div v-else class="domain-mobile-list">
      <article v-for="domain in domains" :key="domain.domainId" class="domain-mobile-card">
        <header>
          <div class="domain-mobile-title">
            <strong>{{ domain.displayName || '未命名域' }}</strong>
            <a-tag v-if="isDefault(domain)" color="blue">默认域</a-tag>
          </div>
          <a-tag :color="domain.lifecycleState === 'ACTIVE' ? 'success' : 'default'">{{ domain.lifecycleState || '未知状态' }}</a-tag>
        </header>
        <dl>
          <div><dt>域类型</dt><dd>{{ licenseTypeLabel(domain.licenseType) }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatDate(domain.timeCreated) }}</dd></div>
        </dl>
        <footer>
          <a-button size="small" @click="openDetail(domain)">详细信息</a-button>
          <a-button size="small" @click="openEdit(domain)">编辑</a-button>
          <a-button size="small" @click="openLicenseChange(domain)">更改类型</a-button>
          <a-button size="small" danger :disabled="isDefault(domain)" @click="openDelete(domain)">删除</a-button>
        </footer>
      </article>
    </div>

    <a-empty v-if="!domains.length" description="暂无 Identity Domain" />
    <a-alert v-if="lastWorkRequestId" type="success" show-icon style="margin-top: 12px" message="操作已提交">
      <template #description>Work Request ID：<a-typography-text copyable>{{ lastWorkRequestId }}</a-typography-text></template>
    </a-alert>
    </template>

    <a-modal
      v-model:open="formVisible"
      :title="formMode === 'create' ? '创建 Identity Domain' : '编辑 Identity Domain'"
      :confirm-loading="submitting"
      :mask-closable="false"
      :closable="!submitting"
      :keyboard="!submitting"
      :cancel-button-props="{ disabled: submitting }"
      :width="520"
      :body-style="formModalBodyStyle"
      centered
      wrap-class-name="identity-domain-form-modal"
      @ok="submitForm"
    >
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

    <a-modal v-model:open="createVerifyVisible" title="安全验证 — 创建 Identity Domain" ok-text="确认创建" :confirm-loading="submitting" :mask-closable="false" :closable="!submitting" :keyboard="false" :cancel-button-props="{ disabled: submitting }" @ok="submitCreateVerified">
      <a-alert type="warning" show-icon message="验证码已发送至 Telegram" style="margin-bottom: 12px" />
      <a-input v-model:value="createVerifyCode" maxlength="6" inputmode="numeric" placeholder="请输入 6 位 TG 验证码" size="large" @pressEnter="submitCreateVerified" />
      <div class="verify-actions">
        <span>验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="verifyCodeSending" @click="sendCreateVerifyCode">重新发送</a-button>
      </div>
    </a-modal>

    <a-modal v-model:open="licenseVerifyVisible" title="安全验证 — 更改域类型" ok-text="继续" :confirm-loading="submitting" :mask-closable="false" :closable="!submitting" :keyboard="false" :cancel-button-props="{ disabled: submitting }" @ok="submitLicenseChangeVerified">
      <a-alert type="warning" show-icon message="验证码已发送至 Telegram" style="margin-bottom: 12px" />
      <a-input v-model:value="licenseVerifyCode" maxlength="6" inputmode="numeric" placeholder="请输入 6 位 TG 验证码" size="large" @pressEnter="submitLicenseChangeVerified" />
      <div class="verify-actions">
        <span>验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="verifyCodeSending" @click="sendLicenseChangeVerifyCode">重新发送</a-button>
      </div>
    </a-modal>

    <a-modal v-model:open="licenseChangeVisible" title="更改域类型" ok-text="确认更改" :confirm-loading="submitting" :mask-closable="false" :closable="!submitting" :keyboard="false" :cancel-button-props="{ disabled: submitting }" @ok="submitLicenseChange">
      <a-form layout="vertical">
        <a-form-item label="当前域"><a-input :value="licenseChangeTarget?.displayName || '-'" disabled /></a-form-item>
        <a-form-item label="当前类型"><a-input :value="licenseTypeLabel(licenseChangeTarget?.licenseType)" disabled /></a-form-item>
        <a-form-item label="目标类型" required>
          <a-select v-model:value="licenseChangeValue" :options="licenseChangeOptions" placeholder="请选择目标类型" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="deleteVisible" title="删除 Identity Domain" ok-text="确认删除" ok-type="danger" :confirm-loading="submitting" :mask-closable="false" :closable="!submitting" :keyboard="false" :cancel-button-props="{ disabled: submitting }" @ok="submitDelete">
      <a-alert type="error" show-icon message="删除属于危险异步操作，默认域不可删除。" style="margin-bottom: 12px" />
      <p>目标域：{{ deleteTarget?.displayName }}</p>
      <a-input v-model:value="deleteVerifyCode" maxlength="6" placeholder="请输入 TG 验证码" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { changeIdentityDomainLicenseType, createIdentityDomain, deleteIdentityDomain, getIdentityDomainDetail, listAllowedIdentityDomainLicenseChanges, listAllowedIdentityDomainLicenseTypes, unlockIdentityDomainCreate, unlockIdentityDomainLicenseChange, updateIdentityDomain } from '../../../api/tenant'
import { sendVerifyCode } from '../../../api/system'
import { useIsMobile } from '../../../composables/useIsMobile'

const props = defineProps<{ tenantId: string; domains: any[]; defaultHomeRegion?: string }>()
const emit = defineEmits<{ (e: 'refresh'): void }>()
const { isMobile } = useIsMobile()
const columns = [
  { title: '显示名称', key: 'name', width: 220 },
  { title: '域类型', dataIndex: 'licenseType', key: 'licenseType', width: 180 },
  { title: '状态', key: 'state', width: 120 },
  { title: '创建时间', dataIndex: 'timeCreated', key: 'timeCreated', width: 190, customRender: ({ text }: any) => formatDate(text) },
  { title: '操作', key: 'actions', width: 310, fixed: 'right' },
]
const licenseOptions = ref<any[]>([])
const licenseTypesLoading = ref(false)
const formVisible = ref(false)
const deleteVisible = ref(false)
const createVerifyVisible = ref(false)
const createVerifyCode = ref('')
const createAccessToken = ref('')
const detailDomain = ref<any>(null)
const detailLoading = ref(false)
const licenseVerifyVisible = ref(false)
const licenseVerifyCode = ref('')
const licenseChangeVisible = ref(false)
const licenseChangeTarget = ref<any>(null)
const licenseChangeAccessToken = ref('')
const licenseChangeOptions = ref<any[]>([])
const licenseChangeValue = ref('')
const verifyCodeSending = ref(false)
const submitting = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const editDomainId = ref('')
const deleteTarget = ref<any>(null)
const deleteVerifyCode = ref('')
const lastWorkRequestId = ref('')
const form = reactive<any>({ displayName: '', description: '', licenseType: 'FREE', homeRegion: '', createAdmin: true, useEmailAsUserName: true, adminFirstName: '', adminLastName: '', adminUserName: '', adminEmail: '', isHiddenOnLogin: false })
const formModalBodyStyle = { maxHeight: 'calc(100dvh - 180px)', overflowY: 'auto' as const }

function isDefault(domain: any) {
  return String(domain?.type || '').toUpperCase() === 'DEFAULT'
    || String(domain?.displayName || '').toLowerCase() === 'default'
}
function licenseTypeLabel(value: any) { return value ? String(value) : '-' }
function formatDate(value: any) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false })
}
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

async function openDetail(domain: any) {
  detailDomain.value = { ...domain }
  detailLoading.value = true
  try {
    const res = await getIdentityDomainDetail({ id: props.tenantId, domainId: domain.domainId })
    detailDomain.value = res.data || { ...domain }
  } catch (error: any) {
    detailDomain.value = null
    message.error(error?.message || '读取域详细信息失败')
  } finally { detailLoading.value = false }
}

function closeDetail() { detailDomain.value = null }

async function openLicenseChange(domain: any) {
  licenseChangeTarget.value = domain
  licenseVerifyCode.value = ''
  licenseChangeAccessToken.value = ''
  licenseChangeOptions.value = []
  licenseChangeValue.value = ''
  licenseVerifyVisible.value = true
  await sendLicenseChangeVerifyCode()
}

async function sendLicenseChangeVerifyCode() {
  if (!licenseChangeTarget.value?.domainId) { message.warning('未选择目标域'); return }
  verifyCodeSending.value = true
  try {
    await sendVerifyCode('identityDomainLicenseChange', {
      contextKey: `${props.tenantId}:${licenseChangeTarget.value.domainId}`,
      contextText: licenseChangeTarget.value.displayName || licenseChangeTarget.value.domainId,
    })
    message.success('验证码已发送')
  }
  catch (error: any) { message.error(error?.message || '发送验证码失败') }
  finally { verifyCodeSending.value = false }
}

async function submitLicenseChangeVerified() {
  if (!licenseChangeTarget.value?.domainId) { message.warning('未选择目标域'); return }
  if (!/^\d{6}$/.test(licenseVerifyCode.value.trim())) { message.warning('请输入 6 位 TG 验证码'); return }
  submitting.value = true
  try {
    const unlockRes = await unlockIdentityDomainLicenseChange({
      id: props.tenantId,
      domainId: licenseChangeTarget.value.domainId,
      verifyCode: licenseVerifyCode.value.trim(),
    })
    licenseChangeAccessToken.value = unlockRes.data?.accessToken || ''
    if (!licenseChangeAccessToken.value) throw new Error('未获得更改域类型授权')
    const typesRes = await listAllowedIdentityDomainLicenseChanges({
      id: props.tenantId,
      domainId: licenseChangeTarget.value.domainId,
      accessToken: licenseChangeAccessToken.value,
    })
    const currentType = String(licenseChangeTarget.value.licenseType || '')
    licenseChangeOptions.value = (typesRes.data || [])
      .filter((item: any) => String(item.licenseType || '').toLowerCase() !== currentType.toLowerCase())
      .map((item: any) => ({ label: item.name || item.licenseType, value: item.licenseType, title: item.description }))
    if (!licenseChangeOptions.value.length) throw new Error('当前域没有可更改的目标类型')
    licenseChangeValue.value = licenseChangeOptions.value[0].value
    licenseVerifyVisible.value = false
    licenseChangeVisible.value = true
  } catch (error: any) {
    licenseChangeAccessToken.value = ''
    message.error(error?.message || '验证或读取可用域类型失败')
  } finally { submitting.value = false }
}

async function submitLicenseChange() {
  if (!licenseChangeTarget.value?.domainId || !licenseChangeValue.value || !licenseChangeAccessToken.value) {
    message.warning('请选择目标类型并重新完成验证')
    return
  }
  submitting.value = true
  try {
    const res = await changeIdentityDomainLicenseType({
      id: props.tenantId,
      domainId: licenseChangeTarget.value.domainId,
      licenseType: licenseChangeValue.value,
      accessToken: licenseChangeAccessToken.value,
    })
    lastWorkRequestId.value = res.data?.workRequestId || ''
    licenseChangeVisible.value = false
    licenseChangeAccessToken.value = ''
    message.success('域类型更改任务已提交')
    emit('refresh')
  } catch (error: any) {
    licenseChangeVisible.value = false
    licenseChangeAccessToken.value = ''
    licenseChangeValue.value = ''
    message.error(error?.message || '更改域类型失败，请重新完成安全验证')
  } finally { submitting.value = false }
}

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
.panel-toolbar { display: flex; justify-content: flex-end; margin-bottom: 12px; }
.detail-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.detail-heading > div { display: flex; min-width: 0; align-items: center; gap: 8px; }
.detail-heading strong { overflow: hidden; color: var(--text-main); font-size: 17px; text-overflow: ellipsis; white-space: nowrap; }
.domain-detail-list { margin: 0; border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
.domain-detail-list > div { display: grid; grid-template-columns: 180px minmax(0, 1fr); min-width: 0; border-bottom: 1px solid var(--border); }
.domain-detail-list > div:last-child { border-bottom: 0; }
.domain-detail-list dt, .domain-detail-list dd { min-width: 0; margin: 0; padding: 12px 14px; }
.domain-detail-list dt { color: var(--text-sub); background: color-mix(in srgb, var(--input-bg) 78%, transparent); font-size: 13px; }
.domain-detail-list dd { overflow-wrap: anywhere; color: var(--text-main); font-size: 13px; }
.domain-detail-list :deep(.ant-typography) { color: inherit; overflow-wrap: anywhere; }
.verify-actions { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; color: var(--text-sub); font-size: 12px; }
.domain-mobile-list { display: grid; gap: 9px; }
.domain-mobile-card { min-width: 0; padding: 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--input-bg); }
.domain-mobile-card header, .domain-mobile-card footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.domain-mobile-title { display: flex; min-width: 0; align-items: center; gap: 6px; }
.domain-mobile-title strong { overflow: hidden; color: var(--text-main); font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.domain-mobile-card dl { margin: 11px 0; }
.domain-mobile-card dl div { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 8px; padding: 4px 0; }
.domain-mobile-card dt { color: var(--text-sub); font-size: 12px; }
.domain-mobile-card dd { min-width: 0; margin: 0; overflow-wrap: anywhere; color: var(--text-main); font-size: 12px; text-align: right; }
.domain-mobile-card footer { flex-wrap: wrap; justify-content: flex-end; }
@media (max-width: 768px) {
  .panel-toolbar { align-items: stretch; flex-direction: column; }
  .panel-toolbar :deep(.ant-btn) { width: 100%; }
  .verify-actions { align-items: flex-start; flex-direction: column; }
  .detail-heading { align-items: flex-start; flex-direction: column; }
  .domain-detail-list > div { grid-template-columns: 1fr; }
  .domain-detail-list dt { padding-bottom: 4px; }
  .domain-detail-list dd { padding-top: 4px; }
}
</style>
