<template>
  <section class="quota-panel">
    <header class="toolbar">
      <div>
        <h3>Oracle 配额保护</h3>
        <p>限制后续资源创建，不会删除或停止现有资源。</p>
      </div>
      <div class="toolbar-actions">
        <span :class="['status', { enabled: overview.enabled }]">{{ overview.enabled ? '保护已启用' : '尚未启用' }}</span>
        <a-button v-if="overview.enabled" danger :loading="submitting" @click="beginDisable">关闭保护</a-button>
        <a-button type="primary" :disabled="overview.nameConflict || (overview.enabled && !overview.policyEditable)" @click="openConfig">{{ overview.enabled ? '调整保护' : '配置保护' }}</a-button>
      </div>
    </header>

    <a-spin :spinning="loading">
      <div class="metrics">
        <article><span>保护状态</span><strong>{{ overview.enabled ? statusLabel(overview.policy?.status) : '未启用' }}</strong></article>
        <article><span>保护方案</span><strong>{{ profileLabel(overview.profile) }}</strong></article>
        <article><span>已配置项目</span><strong>{{ configuredCount }} 项</strong></article>
        <article><span>账户配额</span><strong>{{ overview.accountLimitsComplete ? '已读取' : '使用官方模板' }}</strong></article>
      </div>
      <a-alert v-if="!overview.accountLimitsComplete" type="warning" show-icon message="部分账户配额未能读取，配置范围将使用 Oracle 官方模板。" />
      <a-alert v-if="overview.nameConflict" type="error" show-icon message="存在同名的非 OCIWorker 配额策略，请先在 Oracle 控制台重命名。" class="panel-alert" />
      <a-alert v-if="overview.policyIssue" type="warning" show-icon :message="overview.policyIssue" class="panel-alert" />
      <div class="summary-grid">
        <article v-for="item in summaryResources" :key="item.key">
          <span>{{ item.label }}</span>
          <strong>{{ valueLabel(item, currentValues[item.key]) }}</strong>
        </article>
      </div>
      <a-empty v-if="!overview.enabled && !loading" description="尚未创建 Oracle 配额保护" />
    </a-spin>

    <a-modal v-model:open="configVisible" title="配置 Oracle 配额保护" width="760px" :mask-closable="false" :keyboard="false" :footer="null" wrap-class-name="quota-config-modal">
      <div class="profiles">
        <button v-for="item in profiles" :key="item.key" type="button" :class="['profile', { active: form.profile === item.key }]" @click="selectProfile(item.key)">
          <b v-if="item.key === 'BASIC'">推荐</b><strong>{{ item.label }}</strong><span>{{ item.description }}</span>
        </button>
      </div>

      <div class="resource-list">
        <article v-for="item in overview.resources" :key="item.key" class="resource-row">
          <a-switch v-if="form.profile === 'CUSTOM'" v-model:checked="form.enabled[item.key]" size="small" />
          <span v-else class="preset-state">{{ form.enabled[item.key] ? '限制' : '不限制' }}</span>
          <div class="resource-name">
            <strong>{{ item.label }}</strong>
            <span>{{ item.accountLimit == null ? '当前账户配额未读取' : `当前账户配额：${item.accountLimit} ${item.unit}` }}</span>
          </div>
          <a-input-number v-model:value="form.values[item.key]" :min="item.min" :max="item.max" :step="item.step" :disabled="form.profile !== 'CUSTOM' || !form.enabled[item.key]" />
          <span class="unit">{{ item.unit }}</span>
        </article>
      </div>
      <p class="note">限制生效后只影响后续资源创建；已有实例、网络和存储不会被删除或停止。</p>
      <footer class="dialog-actions">
        <a-button @click="configVisible=false">取消</a-button>
        <a-button type="primary" :loading="submitting || codeSending" @click="beginSave">TG 验证并{{ overview.enabled ? '保存' : '启用' }}</a-button>
      </footer>
    </a-modal>

    <a-modal v-model:open="verifyVisible" :title="verifyTitle" ok-text="确认" :confirm-loading="submitting" :mask-closable="false" :keyboard="false" @ok="submitVerified">
      <a-alert type="warning" show-icon message="验证码已发送至 Telegram，有效期 5 分钟" class="verify-alert" />
      <a-input v-model:value="verifyCode" maxlength="6" inputmode="numeric" placeholder="请输入 6 位验证码" />
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { disableQuotaProtection, getQuotaProtectionOverview, saveQuotaProtection } from '../../../api/tenant'
import { sendVerifyCode } from '../../../api/system'

const props = defineProps<{ tenantId: string }>()
const loading = ref(false), submitting = ref(false), codeSending = ref(false), configVisible = ref(false), verifyVisible = ref(false)
const verifyCode = ref(''), verifyTitle = ref('安全验证'), pendingAction = ref<'save'|'disable'>('save')
const overview = reactive<any>({ enabled: false, profile: 'BASIC', policy: null, resources: [], values: {}, accountLimitsComplete: false, policyEditable: true, nameConflict: false })
const form = reactive<{ profile: string; values: Record<string, number>; enabled: Record<string, boolean> }>({ profile: 'BASIC', values: {}, enabled: {} })
const profiles = [
  { key: 'BASIC', label: '基础保护', description: '按常见 Always Free 额度限制主要资源。' },
  { key: 'STRICT', label: '严格保护', description: '在基础保护上禁止常见付费网络资源。' },
  { key: 'CUSTOM', label: '自定义', description: '逐项调整需要限制的资源上限。' },
]
const currentValues = computed<Record<string, number>>(() => overview.values || {})
const configuredCount = computed(() => Object.keys(currentValues.value).length)
const summaryResources = computed(() => (overview.resources || []).filter((item:any) => ['a1Ocpu','a1Memory','e2Micro','blockStorage','vcn','reservedPublicIp'].includes(item.key)))

function apply(data:any) { Object.assign(overview, data || {}) }
async function load() {
  loading.value = true
  try { apply((await getQuotaProtectionOverview({ id: props.tenantId })).data) }
  catch (e:any) { message.error(e?.message || '读取 Oracle 配额保护失败') }
  finally { loading.value = false }
}
function openConfig() {
  form.profile = overview.profile || 'BASIC'
  form.values = { ...(overview.values || {}) }
  form.enabled = Object.fromEntries((overview.resources || []).map((item:any) => [item.key, Object.prototype.hasOwnProperty.call(form.values, item.key)]))
  if (!Object.keys(form.values).length) selectProfile('BASIC')
  configVisible.value = true
}
function selectProfile(profile:string) {
  form.profile = profile
  if (profile === 'CUSTOM') return
  const field = profile === 'STRICT' ? 'strict' : 'basic'
  const enabledField = profile === 'STRICT' ? 'strictEnabled' : 'basicEnabled'
  form.values = Object.fromEntries((overview.resources || []).map((item:any) => [item.key, Number(item[field] ?? 0)]))
  form.enabled = Object.fromEntries((overview.resources || []).map((item:any) => [item.key, !!item[enabledField]]))
}
async function requestCode(action:string, text:string) {
  verifyCode.value = ''
  await sendVerifyCode(action, { contextKey: props.tenantId, contextText: text })
  verifyVisible.value = true
}
async function beginSave() {
  if (codeSending.value) return
  pendingAction.value = 'save'
  verifyTitle.value = `安全验证 — ${overview.enabled ? '修改' : '启用'} Oracle 配额保护`
  configVisible.value = false
  codeSending.value = true
  try { await requestCode('quotaProtectionSave', overview.enabled ? '修改 Oracle 配额保护' : '启用 Oracle 配额保护') }
  catch (e:any) { configVisible.value = true; message.error(e?.message || '发送验证码失败') }
  finally { codeSending.value = false }
}
async function beginDisable() {
  if (codeSending.value) return
  pendingAction.value = 'disable'
  verifyTitle.value = '安全验证 — 关闭 Oracle 配额保护'
  codeSending.value = true
  try { await requestCode('quotaProtectionDisable', '关闭 Oracle 配额保护') }
  catch (e:any) { message.error(e?.message || '发送验证码失败') }
  finally { codeSending.value = false }
}
async function submitVerified() {
  if (!/^\d{6}$/.test(verifyCode.value)) { message.warning('请输入 6 位验证码'); return }
  submitting.value = true
  try {
    const response = pendingAction.value === 'disable'
      ? await disableQuotaProtection({ id: props.tenantId, verifyCode: verifyCode.value })
      : await saveQuotaProtection({ id: props.tenantId, profile: form.profile,
          values: Object.fromEntries(Object.entries(form.values).filter(([key]) => form.enabled[key])), verifyCode: verifyCode.value })
    apply(response.data)
    verifyVisible.value = false
    message.success(pendingAction.value === 'disable' ? 'Oracle 配额保护已关闭' : 'Oracle 配额保护已保存')
  } catch (e:any) { message.error(e?.message || '操作失败') }
  finally { submitting.value = false }
}
function profileLabel(value:string) { return profiles.find(item => item.key === value)?.label || '自定义' }
function statusLabel(value:string) { return ({ ACTIVE: '已生效', CREATING: '创建中', UPDATING: '更新中', DELETING: '关闭中', FAILED: '失败' } as any)[value] || value || '已启用' }
function valueLabel(item:any, value:any) { return value == null ? '未设置' : Number(value) === 0 ? '禁止创建' : `${value} ${item.unit}` }
onMounted(load)
</script>

<style scoped>
.quota-panel{min-width:0;overflow:hidden}.toolbar{display:flex;justify-content:space-between;align-items:flex-start;gap:14px}.toolbar h3{margin:0;font-size:16px}.toolbar p{margin:5px 0 0;color:var(--text-sub);font-size:12px}.toolbar-actions{display:flex;align-items:center;gap:8px}.status{color:var(--text-sub);font-size:11px}.status.enabled{color:#34d399}.panel-alert{margin-top:10px}.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin:14px 0}.metrics article,.summary-grid article{min-width:0;padding:11px;border:1px solid var(--border);border-radius:9px;background:var(--input-bg)}.metrics span,.summary-grid span{display:block;color:var(--text-sub);font-size:10px}.metrics strong,.summary-grid strong{display:block;margin-top:5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px}.summary-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin:12px 0}.profiles{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}.profile{position:relative;min-width:0;padding:12px;border:1px solid var(--border);border-radius:10px;color:var(--text-main);background:var(--input-bg);cursor:pointer;text-align:left}.profile.active{border-color:var(--primary);background:color-mix(in srgb,var(--primary) 9%,transparent)}.profile b{position:absolute;top:7px;right:8px;color:var(--primary);font-size:9px}.profile strong,.profile span{display:block}.profile span{margin-top:4px;color:var(--text-sub);font-size:10px;line-height:1.45}.resource-list{display:grid;gap:7px;margin-top:14px}.resource-row{display:grid;grid-template-columns:62px minmax(180px,1fr) 130px 38px;align-items:center;gap:10px;padding:10px;border:1px solid var(--border);border-radius:9px;background:var(--input-bg)}.preset-state{color:var(--text-sub);font-size:10px}.resource-name{min-width:0}.resource-name strong,.resource-name span{display:block}.resource-name strong{font-size:12px}.resource-name span{margin-top:3px;color:var(--text-sub);font-size:10px}.unit{color:var(--text-sub);font-size:11px}.note{margin:12px 0 0;padding:10px;border:1px solid var(--border);border-radius:8px;color:var(--text-sub);font-size:10px}.dialog-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:14px}.verify-alert{margin-bottom:12px}@media(max-width:768px){.toolbar{flex-direction:column}.toolbar-actions{width:100%;flex-wrap:wrap}.metrics{grid-template-columns:1fr 1fr}.summary-grid,.profiles{grid-template-columns:1fr}.resource-row{grid-template-columns:52px minmax(0,1fr) 90px 26px}.resource-name span{white-space:normal}}
@media(max-width:768px){
  :global(.quota-config-modal .ant-modal){max-width:100%;margin:0;padding:0}
  :global(.quota-config-modal .ant-modal-content){min-height:100dvh;border-radius:0}
  :global(.quota-config-modal .ant-modal-body){overflow-x:hidden}
}
</style>
