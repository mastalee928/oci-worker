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
            <a-select v-model:value="form.licenseType" :options="licenseOptions" />
          </a-form-item>
          <a-form-item label="主区域"><a-input v-model:value="form.homeRegion" placeholder="留空时由 OCI 按租户配置处理" /></a-form-item>
          <a-divider orientation="left">域管理员（可选）</a-divider>
          <a-form-item label="名字"><a-input v-model:value="form.adminFirstName" /></a-form-item>
          <a-form-item label="姓氏"><a-input v-model:value="form.adminLastName" /></a-form-item>
          <a-form-item label="用户名 / 电子邮件"><a-input v-model:value="form.adminUserName" /></a-form-item>
          <a-form-item label="TG 验证码" required><a-input v-model:value="form.verifyCode" maxlength="6" /></a-form-item>
        </template>
        <a-form-item label="在登录页隐藏"><a-switch v-model:checked="form.isHiddenOnLogin" /></a-form-item>
      </a-form>
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
import { createIdentityDomain, deleteIdentityDomain, updateIdentityDomain } from '../../../api/tenant'

const props = defineProps<{ tenantId: string; domains: any[] }>()
const emit = defineEmits<{ (e: 'refresh'): void }>()
const columns = [
  { title: '显示名称', key: 'name', width: 220 },
  { title: '类型', dataIndex: 'type', key: 'type', width: 160 },
  { title: '状态', key: 'state', width: 120 },
  { title: '域 OCID', dataIndex: 'domainId', key: 'domainId', ellipsis: true },
  { title: '操作', key: 'actions', width: 130, fixed: 'right' },
]
const licenseOptions = [
  { label: '免费', value: 'FREE' },
  { label: 'Oracle 应用程序高级版', value: 'APP_SILO' },
  { label: '高级版', value: 'PREMIUM' },
  { label: '外部用户', value: 'EXTERNAL_USER' },
  { label: 'External Active User', value: 'EXTERNAL_ACTIVE_USER' },
]
const formVisible = ref(false)
const deleteVisible = ref(false)
const submitting = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const editDomainId = ref('')
const deleteTarget = ref<any>(null)
const deleteVerifyCode = ref('')
const lastWorkRequestId = ref('')
const form = reactive<any>({ displayName: '', description: '', licenseType: 'FREE', homeRegion: '', adminFirstName: '', adminLastName: '', adminUserName: '', verifyCode: '', isHiddenOnLogin: false })

function isDefault(domain: any) { return String(domain?.displayName || '').toLowerCase() === 'default' }
function resetForm() { Object.assign(form, { displayName: '', description: '', licenseType: 'FREE', homeRegion: '', adminFirstName: '', adminLastName: '', adminUserName: '', verifyCode: '', isHiddenOnLogin: false }) }
function openCreate() { formMode.value = 'create'; editDomainId.value = ''; resetForm(); formVisible.value = true }
function openEdit(domain: any) { formMode.value = 'edit'; editDomainId.value = domain.domainId; resetForm(); Object.assign(form, { displayName: domain.displayName || '', description: domain.description || '', isHiddenOnLogin: !!domain.isHiddenOnLogin }); formVisible.value = true }
function openDelete(domain: any) { deleteTarget.value = domain; deleteVerifyCode.value = ''; deleteVisible.value = true }

async function submitForm() {
  if (!form.displayName?.trim() || (formMode.value === 'create' && (!form.description?.trim() || !form.verifyCode?.trim()))) { message.warning('请填写必填项'); return }
  submitting.value = true
  try {
    const payload = { id: props.tenantId, ...form }
    const res = formMode.value === 'create' ? await createIdentityDomain(payload) : await updateIdentityDomain({ ...payload, domainId: editDomainId.value })
    lastWorkRequestId.value = res.data?.workRequestId || ''
    message.success('操作已提交')
    formVisible.value = false
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
@media (max-width: 768px) { .panel-toolbar { align-items: stretch; flex-direction: column; } }
</style>
