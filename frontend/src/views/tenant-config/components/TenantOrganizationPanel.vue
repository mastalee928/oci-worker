<template>
  <section class="organization-panel">
    <div class="toolbar">
      <a-button @click="beginAction('inviteOrganizationTenancy')">邀请租户</a-button>
      <a-button type="primary" @click="beginAction('createChildTenancy')">创建新租户</a-button>
    </div>

    <a-spin :spinning="loading">
      <a-alert v-if="loadError" type="error" show-icon :message="loadError" />
      <a-empty v-else-if="!tenancies.length" description="当前没有组织租户" />
      <a-table v-else-if="!isMobile" :columns="columns" :data-source="tenancies" :pagination="false" row-key="tenancyId" size="small">
        <template #bodyCell="{ column, record }"><a-tag v-if="column.key === 'status'" :color="record.status === 'ACTIVE' ? 'success' : 'default'">{{ tenancyStatusLabel(record.status) }}</a-tag></template>
      </a-table>
      <div v-else class="mobile-list">
        <article v-for="item in tenancies" :key="item.tenancyId">
          <header><strong>{{ item.name || '未命名租户' }}</strong><a-tag :color="item.status === 'ACTIVE' ? 'success' : 'default'">{{ tenancyStatusLabel(item.status) }}</a-tag></header>
          <dl><div><dt>类型</dt><dd>{{ roleLabel(item.role) }}</dd></div><div><dt>租户 OCID</dt><dd>{{ item.tenancyId || '-' }}</dd></div><div><dt>加入时间</dt><dd>{{ formatDate(item.timeJoined) }}</dd></div></dl>
        </article>
      </div>
    </a-spin>

    <section v-if="tasks.length" class="task-section">
      <div class="task-heading"><strong>最近任务</strong><a-button type="link" :loading="taskLoading" @click="loadTasks">刷新状态</a-button></div>
      <div class="task-list"><article v-for="task in tasks" :key="task.id"><div><strong>{{ operationLabel(task.operationType) }}</strong><span>{{ task.targetName || '-' }}</span></div><a-tag :color="taskColor(task.status)">{{ taskStatusLabel(task.status) }}</a-tag><p v-if="task.errorMessage">{{ task.errorMessage }}</p></article></div>
    </section>

    <a-modal v-model:open="verifyVisible" :title="activeAction === 'createChildTenancy' ? '安全验证 — 创建子租户' : '安全验证 — 邀请租户'" ok-text="继续" :confirm-loading="submitting" :mask-closable="false" :closable="!submitting" :cancel-button-props="{ disabled: submitting }" :keyboard="false" @ok="verifyAction">
      <a-alert type="warning" show-icon :message="accessToken ? '安全验证已通过，点击继续重试读取区域' : '验证码已发送至 Telegram'" style="margin-bottom:12px" />
      <a-input v-model:value="verifyCode" maxlength="6" inputmode="numeric" placeholder="请输入 6 位 TG 验证码" size="large" @pressEnter="verifyAction" />
      <div class="verify-actions"><span>验证码有效期 5 分钟</span><a-button type="link" size="small" :loading="verifySending" @click="sendActionCode">重新发送</a-button></div>
    </a-modal>

    <a-modal v-model:open="createVisible" title="创建新租户" ok-text="下一步" :confirm-loading="submitting" :ok-button-props="{ disabled: regionLoading || !createForm.homeRegion }" :mask-closable="false" :closable="!submitting" :cancel-button-props="{ disabled: submitting }" :keyboard="!submitting" :width="560" @cancel="clearAuthorization" @ok="openCreateSummary">
      <a-form layout="vertical">
        <a-form-item label="租户名称" required><a-input v-model:value="createForm.tenancyName" placeholder="小写字母开头，仅包含小写字母和数字" /></a-form-item>
        <a-form-item label="主区域" required><a-select v-model:value="createForm.homeRegion" :options="regionOptions" :loading="regionLoading" :placeholder="regionLoading ? '正在读取可用区域…' : '请选择主区域'" /></a-form-item>
        <a-form-item label="管理员电子邮件" required><a-input v-model:value="createForm.adminEmail" type="email" /></a-form-item>
        <a-form-item label="确认管理员电子邮件" required><a-input v-model:value="createForm.confirmEmail" type="email" /></a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="summaryVisible" title="确认创建租户" ok-text="创建租户" :confirm-loading="submitting" :mask-closable="false" :closable="!submitting" :cancel-button-props="{ disabled: submitting }" :keyboard="!submitting" @ok="submitCreate">
      <dl class="summary-list"><div><dt>租户名称</dt><dd>{{ createForm.tenancyName }}</dd></div><div><dt>主区域</dt><dd>{{ createForm.homeRegion }}</dd></div><div><dt>管理员电子邮件</dt><dd>{{ createForm.adminEmail }}</dd></div><div><dt>订阅</dt><dd>自动使用当前组织默认订阅</dd></div><div><dt>组织监管</dt><dd>不启用</dd></div></dl>
    </a-modal>

    <a-modal v-model:open="inviteVisible" title="邀请租户" ok-text="发送邀请" :confirm-loading="submitting" :mask-closable="false" :closable="!submitting" :cancel-button-props="{ disabled: submitting }" :keyboard="!submitting" :width="560" @cancel="clearAuthorization" @ok="submitInvite">
      <a-form layout="vertical">
        <a-form-item label="邀请名称" required><a-input v-model:value="inviteForm.displayName" /></a-form-item>
        <a-form-item label="接收方租户 OCID" required><a-input v-model:value="inviteForm.recipientTenancyId" /></a-form-item>
        <a-form-item label="接收方电子邮件" required><a-input v-model:value="inviteForm.recipientEmailAddress" type="email" /></a-form-item>
        <a-form-item label="确认接收方电子邮件" required><a-input v-model:value="inviteForm.confirmEmail" type="email" /></a-form-item>
      </a-form>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { createOrganizationChild, getOrganizationCreateOptions, getOrganizationOverview, inviteOrganizationTenancy, refreshOrganizationTasks, unlockOrganizationAction } from '../../../api/tenant'
import { sendVerifyCode } from '../../../api/system'
import { useIsMobile } from '../../../composables/useIsMobile'
import { getOciRegionDisplayName } from '../../../utils/ociRegionCatalog'

const props = defineProps<{ tenantId?: string }>()
const { isMobile } = useIsMobile()
const loading = ref(false), taskLoading = ref(false), submitting = ref(false), verifySending = ref(false), regionLoading = ref(false)
const tenancies = ref<any[]>([]), tasks = ref<any[]>([])
const verifyVisible = ref(false), createVisible = ref(false), inviteVisible = ref(false), summaryVisible = ref(false)
const loadError = ref('')
const verifyCode = ref(''), accessToken = ref('')
const activeAction = ref<'createChildTenancy' | 'inviteOrganizationTenancy'>('createChildTenancy')
const regionOptions = ref<any[]>([])
const createForm = reactive({ tenancyName: '', homeRegion: '', adminEmail: '', confirmEmail: '' })
const inviteForm = reactive({ displayName: '', recipientTenancyId: '', recipientEmailAddress: '', confirmEmail: '' })
const columns = [
  { title: '租户名称', dataIndex: 'name', key: 'name' },
  { title: '类型', dataIndex: 'role', key: 'role', customRender: ({ text }: any) => roleLabel(text), width: 120 },
  { title: '租户 OCID', dataIndex: 'tenancyId', key: 'tenancyId', ellipsis: true },
  { title: '状态', dataIndex: 'status', key: 'status', width: 120 },
  { title: '加入时间', dataIndex: 'timeJoined', key: 'timeJoined', customRender: ({ text }: any) => formatDate(text), width: 190 },
]
const emailOk = (v: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)
function roleLabel(v: any) { return ({ PARENT: '父租户', CHILD: '子租户', NONE: '未加入' } as any)[String(v || '').toUpperCase()] || v || '-' }
function formatDate(v: any) { if (!v) return '-'; const d = new Date(v); return Number.isNaN(d.getTime()) ? String(v) : d.toLocaleString('zh-CN', { hour12: false }) }
function operationLabel(v: string) { return v === 'CREATE_CHILD_TENANCY' ? '创建子租户' : '邀请租户' }
function taskStatusLabel(v: string) { return ({ ACCEPTED: '已受理', IN_PROGRESS: '进行中', SUCCEEDED: '已完成', FAILED: '失败', CANCELED: '已取消', CANCELING: '取消中' } as any)[v] || v || '-' }
function taskColor(v: string) { return v === 'SUCCEEDED' ? 'success' : v === 'FAILED' ? 'error' : v === 'IN_PROGRESS' ? 'processing' : 'default' }
function clearAuthorization() { accessToken.value = '' }
function tenancyStatusLabel(v: string) { return ({ ACTIVE: '活动', INACTIVE: '未激活', CREATING: '创建中', DELETING: '删除中', DELETED: '已删除' } as any)[v] || v || '-' }
function regionOptionLabel(regionId: string) { const name = getOciRegionDisplayName(regionId); return name === regionId ? regionId : `${name}（${regionId}）` }
function resetForms() { Object.assign(createForm, { tenancyName: '', homeRegion: '', adminEmail: '', confirmEmail: '' }); Object.assign(inviteForm, { displayName: '', recipientTenancyId: '', recipientEmailAddress: '', confirmEmail: '' }) }

async function loadCreateOptions() {
  if (!props.tenantId || !accessToken.value) throw new Error('操作授权已失效，请重新完成 TG 验证')
  regionLoading.value = true
  regionOptions.value = []
  createForm.homeRegion = ''
  try {
    const response = await getOrganizationCreateOptions({ id: props.tenantId, accessToken: accessToken.value })
    regionOptions.value = (response.data?.regions || []).map((value: string) => ({ label: regionOptionLabel(value), value }))
    createForm.homeRegion = regionOptions.value.some(option => option.value === response.data?.defaultRegion)
      ? response.data.defaultRegion
      : regionOptions.value[0]?.value || ''
  } catch (e: any) {
    message.error(e?.message || '读取可用区域失败')
  } finally {
    regionLoading.value = false
  }
}

async function loadOverview() { if (!props.tenantId) return; loading.value = true; loadError.value = ''; try { const r = await getOrganizationOverview({ id: props.tenantId }); tenancies.value = r.data?.tenancies || []; tasks.value = r.data?.tasks || [] } catch (e: any) { loadError.value = e?.message || '读取组织信息失败' } finally { loading.value = false } }
async function loadTasks() { if (!props.tenantId) return; taskLoading.value = true; try { const r = await refreshOrganizationTasks({ id: props.tenantId }); tasks.value = r.data || []; if (tasks.value.some(t => t.status === 'SUCCEEDED')) await loadOverview() } catch (e: any) { message.error(e?.message || '刷新任务状态失败') } finally { taskLoading.value = false } }
async function sendActionCode() { if (!props.tenantId) return; verifySending.value = true; try { await sendVerifyCode(activeAction.value, { contextKey: props.tenantId, contextText: activeAction.value === 'createChildTenancy' ? '创建子租户' : '邀请租户' }); message.success('验证码已发送') } catch (e: any) { message.error(e?.message || '发送验证码失败') } finally { verifySending.value = false } }
async function beginAction(action: typeof activeAction.value) { if (!props.tenantId) return; activeAction.value = action; verifyCode.value = ''; accessToken.value = ''; resetForms(); verifyVisible.value = true; await sendActionCode() }
async function verifyAction() { if (!props.tenantId || (!accessToken.value && !/^\d{6}$/.test(verifyCode.value))) { message.warning('请输入 6 位 TG 验证码'); return } submitting.value = true; let unlocked = !!accessToken.value; try { if (!accessToken.value) { const r = await unlockOrganizationAction({ id: props.tenantId, action: activeAction.value, verifyCode: verifyCode.value }); accessToken.value = r.data?.accessToken || ''; if (!accessToken.value) throw new Error('未获得操作授权'); unlocked = true } verifyVisible.value = false; if (activeAction.value === 'createChildTenancy') { createVisible.value = true; void loadCreateOptions() } else inviteVisible.value = true } catch (e: any) { const authorizationExpired = String(e?.message || '').includes('授权已失效'); if (!unlocked || authorizationExpired) accessToken.value = ''; verifyVisible.value = true; message.error(e?.message || '安全验证失败') } finally { submitting.value = false } }
function openCreateSummary() { if (!/^[a-z][a-z0-9]{0,29}$/.test(createForm.tenancyName) || !createForm.homeRegion || !emailOk(createForm.adminEmail) || createForm.adminEmail !== createForm.confirmEmail) { message.warning('请检查租户名称、主区域和管理员电子邮件'); return } createVisible.value = false; summaryVisible.value = true }
async function submitCreate() { if (!props.tenantId) return; submitting.value = true; try { const r = await createOrganizationChild({ id: props.tenantId, accessToken: accessToken.value, tenancyName: createForm.tenancyName, homeRegion: createForm.homeRegion, adminEmail: createForm.adminEmail }); summaryVisible.value = false; accessToken.value = ''; r.data?.trackingSaved === false ? message.warning(`操作已提交，但本地任务记录保存失败。Work Request ID：${r.data?.workRequestId}`) : message.success('创建租户任务已提交'); await loadOverview() } catch (e: any) { summaryVisible.value = false; accessToken.value = ''; message.error(e?.message || '创建子租户失败，请重新验证') } finally { submitting.value = false } }
async function submitInvite() { if (!props.tenantId) return; if (!inviteForm.displayName.trim() || !inviteForm.recipientTenancyId.startsWith('ocid1.tenancy.') || !emailOk(inviteForm.recipientEmailAddress) || inviteForm.recipientEmailAddress !== inviteForm.confirmEmail) { message.warning('请检查邀请名称、租户 OCID 和电子邮件'); return } submitting.value = true; try { const r = await inviteOrganizationTenancy({ id: props.tenantId, accessToken: accessToken.value, displayName: inviteForm.displayName, recipientTenancyId: inviteForm.recipientTenancyId, recipientEmailAddress: inviteForm.recipientEmailAddress }); inviteVisible.value = false; accessToken.value = ''; r.data?.trackingSaved === false ? message.warning(`邀请已提交，但本地任务记录保存失败。Work Request ID：${r.data?.workRequestId}`) : message.success('租户邀请已提交'); await loadOverview() } catch (e: any) { inviteVisible.value = false; accessToken.value = ''; message.error(e?.message || '邀请租户失败，请重新验证') } finally { submitting.value = false } }
onMounted(async () => { await loadOverview(); if (tasks.value.some(t => !['SUCCEEDED', 'FAILED', 'CANCELED'].includes(t.status))) await loadTasks() })
</script>

<style scoped>
.organization-panel{min-width:0}.toolbar{display:flex;justify-content:flex-end;gap:8px;margin-bottom:12px}.verify-actions{display:flex;align-items:center;justify-content:space-between;margin-top:8px;color:var(--text-sub);font-size:12px}.summary-list{margin:0}.summary-list div{display:grid;grid-template-columns:150px minmax(0,1fr);gap:12px;padding:10px 0;border-bottom:1px solid var(--border)}.summary-list div:last-child{border-bottom:0}.summary-list dt{color:var(--text-sub)}.summary-list dd{min-width:0;margin:0;overflow-wrap:anywhere}.mobile-list,.task-list{display:grid;gap:9px}.mobile-list article,.task-list article{min-width:0;padding:12px;border:1px solid var(--border);border-radius:10px;background:var(--input-bg)}.mobile-list header,.task-list article,.task-heading{display:flex;align-items:center;justify-content:space-between;gap:8px}.mobile-list dl{margin:10px 0 0}.mobile-list dl div{display:grid;grid-template-columns:72px minmax(0,1fr);gap:8px;padding:4px 0}.mobile-list dt{color:var(--text-sub)}.mobile-list dd{min-width:0;margin:0;overflow-wrap:anywhere;text-align:right}.task-section{margin-top:16px}.task-heading{margin-bottom:8px}.task-list article{flex-wrap:wrap}.task-list article>div{display:grid}.task-list span,.task-list p{color:var(--text-sub);font-size:12px}.task-list p{width:100%;margin:4px 0 0;overflow-wrap:anywhere}@media(max-width:768px){.toolbar{align-items:stretch;flex-direction:column}.toolbar :deep(.ant-btn){width:100%}.summary-list div{grid-template-columns:1fr;gap:3px}}
</style>
