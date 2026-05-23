<template>
  <div class="cf-security-tab">
    <a-card title="站点防护" size="small" class="cf-shield-card" :loading="shieldLoading">
      <a-form layout="vertical" class="cf-shield-form">
        <a-form-item label="安全级别">
          <a-select
            v-model:value="shield.security_level"
            :options="securityLevelOptions"
            :disabled="shieldSaving"
            @change="saveShield('security_level', shield.security_level)"
          />
          <p v-if="shield.security_level === 'under_attack'" class="cf-shield-warn">
            全站访问者将看到浏览器验证页（俗称三秒盾），请仅在遭受攻击时使用。
          </p>
        </a-form-item>
        <a-form-item v-if="shield.bot_fight_mode != null" label="Bot Fight Mode">
          <a-switch
            :checked="shield.bot_fight_mode === 'on'"
            :loading="shieldSaving"
            @change="(v: boolean) => saveShield('bot_fight_mode', v ? 'on' : 'off')"
          />
        </a-form-item>
        <a-form-item v-if="shield.browser_check != null" label="浏览器完整性检查">
          <a-switch
            :checked="shield.browser_check === 'on'"
            :loading="shieldSaving"
            @change="(v: boolean) => saveShield('browser_check', v ? 'on' : 'off')"
          />
        </a-form-item>
      </a-form>
    </a-card>

    <a-divider orientation="left">防火墙规则</a-divider>

    <div class="cf-toolbar">
      <a-space wrap>
        <a-button type="primary" @click="openCreateModal">添加规则</a-button>
        <a-button :loading="loading || shieldLoading" @click="loadAll">刷新</a-button>
      </a-space>
    </div>
    <a-table
      :columns="columns"
      :data-source="rules"
      :loading="loading"
      row-key="id"
      size="middle"
      :pagination="false"
      :scroll="{ x: 960 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <div class="cf-rule-name">
            <a class="cf-rule-name-link" @click="openEditModal(record)">
              {{ ruleDisplayName(record.description, record.expression) }}
            </a>
            <a-tag :color="record.paused ? 'default' : 'success'" class="cf-rule-status">
              {{ record.paused ? '已禁用' : '活动' }}
            </a-tag>
          </div>
        </template>
        <template v-else-if="column.key === 'match'">
          <span class="cf-match" :title="record.expression">
            {{ humanizeExpression(record.expression) }}
          </span>
        </template>
        <template v-else-if="column.key === 'action'">
          {{ firewallActionLabel(record.action) }}
        </template>
        <template v-else-if="column.key === 'events24h'">
          <span class="cf-events">{{ formatEvents24h(record.events24h) }}</span>
        </template>
        <template v-else-if="column.key === 'ops'">
          <a-space size="small" class="cf-rule-ops">
            <a-button type="link" size="small" @click="openEditModal(record)">编辑</a-button>
            <a-button
              type="link"
              size="small"
              :loading="!!pauseLoadingMap[record.id]"
              @click="toggleRulePaused(record)"
            >
              {{ record.paused ? '启用' : '禁用' }}
            </a-button>
            <a-button
              type="link"
              size="small"
              danger
              :loading="!!deleteLoadingMap[record.id]"
              @click="confirmDelete(record)"
            >
              删除
            </a-button>
          </a-space>
        </template>
      </template>
    </a-table>
    <p class="cf-hint">防火墙规则（Ingress）。支持可视化构建或手写 Wirefilter 表达式。</p>

    <a-modal
      v-model:open="createModalVisible"
      :title="editingRuleId ? '编辑防火墙规则' : '添加防火墙规则'"
      :confirm-loading="saveLoading"
      width="680px"
      @ok="submitSave"
    >
      <a-form layout="vertical">
        <a-form-item label="名称">
          <a-input v-model:value="form.description" placeholder="规则名称" allow-clear />
        </a-form-item>
        <a-form-item label="操作" required>
          <a-select v-model:value="form.action" :options="actionOptions" />
        </a-form-item>

        <a-form-item label="匹配条件" required>
          <div class="cf-mode-bar">
            <a-radio-group v-model:value="form.mode" size="small" @change="onModeChange">
              <a-radio-button value="visual">可视化</a-radio-button>
              <a-radio-button value="advanced">编辑表达式</a-radio-button>
            </a-radio-group>
          </div>

          <template v-if="form.mode === 'visual'">
            <p class="cf-section-label">当传入请求匹配时…</p>
            <a-row :gutter="8" align="middle">
              <a-col :span="7">
                <div class="cf-mini-label">字段</div>
                <a-select
                  v-model:value="form.fieldId"
                  :options="fieldOptions"
                  style="width: 100%"
                  @change="onFieldChange"
                />
              </a-col>
              <a-col :span="7">
                <div class="cf-mini-label">运算符</div>
                <a-select
                  v-model:value="form.operator"
                  :options="operatorOptions"
                  style="width: 100%"
                />
              </a-col>
              <a-col :span="10">
                <div class="cf-mini-label">值</div>
                <a-switch
                  v-if="selectedField?.type === 'bool'"
                  v-model:checked="form.boolValue"
                  checked-children="是 (HTTPS)"
                  un-checked-children="否"
                />
                <a-input
                  v-else
                  v-model:value="form.value"
                  :placeholder="selectedField?.placeholder || '输入匹配值'"
                  allow-clear
                />
              </a-col>
            </a-row>
            <p v-if="selectedField?.valueHint" class="cf-value-hint">{{ selectedField.valueHint }}</p>
            <div class="cf-expr-preview">
              <div class="cf-expr-preview-head">
                <span>表达式预览</span>
                <span class="cf-expr-len">{{ previewExpression.length }} / 4000</span>
              </div>
              <pre class="cf-expr-preview-body">{{ previewExpression || '（请完善字段、运算符与值）' }}</pre>
            </div>
          </template>

          <a-textarea
            v-else
            v-model:value="form.expression"
            placeholder='如 (http.request.uri.path contains "/admin")'
            :rows="4"
          />
        </a-form-item>

        <a-form-item :label="editingRuleId ? '状态' : '创建后状态'">
          <a-switch v-model:checked="form.enabled" checked-children="启用" un-checked-children="禁用" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, watch, computed } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  listCfFirewallRules,
  createCfFirewallRule,
  updateCfFirewallRule,
  deleteCfFirewallRule,
  setCfFirewallRulePaused,
  getCfSecurityProtection,
  setCfSecurityProtection,
} from '../../api/cloudflare'
import {
  FIREWALL_FIELDS,
  compileFirewallClause,
  defaultOperatorForField,
  operatorsForField,
  humanizeExpression,
  firewallActionLabel,
  ruleDisplayName,
  type FieldDef,
  type OperatorId,
} from './cfFirewallExpression'

interface FirewallRule {
  id: string
  description?: string
  action?: string
  paused?: boolean
  expression?: string
  events24h?: number
}

const props = defineProps<{ zoneId?: string }>()

const loading = ref(false)
const shieldLoading = ref(false)
const shieldSaving = ref(false)
const saveLoading = ref(false)
const createModalVisible = ref(false)
const editingRuleId = ref<string | null>(null)
const rules = ref<FirewallRule[]>([])
const pauseLoadingMap = ref<Record<string, boolean>>({})
const deleteLoadingMap = ref<Record<string, boolean>>({})

const shield = reactive({
  security_level: 'medium' as string,
  bot_fight_mode: null as string | null,
  browser_check: null as string | null,
})

const securityLevelOptions = [
  { value: 'essentially_off', label: '基本关闭' },
  { value: 'low', label: '低' },
  { value: 'medium', label: '中（默认）' },
  { value: 'high', label: '高' },
  { value: 'under_attack', label: '我正在遭受攻击（三秒盾）' },
]

const form = reactive({
  description: '',
  action: 'block',
  mode: 'visual' as 'visual' | 'advanced',
  fieldId: 'uri_path',
  operator: 'wildcard' as OperatorId,
  value: '',
  boolValue: true,
  expression: '',
  enabled: true,
})

const actionOptions = [
  { value: 'block', label: '阻止 (block)' },
  { value: 'challenge', label: '质询 (challenge)' },
  { value: 'js_challenge', label: 'JS 质询' },
  { value: 'managed_challenge', label: '托管质询' },
  { value: 'allow', label: '允许 (allow)' },
  { value: 'log', label: '记录 (log)' },
  { value: 'bypass', label: '绕过 (bypass)' },
]

const columns = [
  { title: '名称', key: 'name', width: 180 },
  { title: '匹配条件', key: 'match', ellipsis: true },
  { title: '操作', key: 'action', width: 96 },
  { title: '过去 24 小时的事件', key: 'events24h', width: 140, align: 'center' as const },
  { title: '', key: 'ops', width: 168, fixed: 'right' as const },
]

const fieldOptions = FIREWALL_FIELDS.map(f => ({ value: f.id, label: f.label }))

const selectedField = computed((): FieldDef | undefined =>
  FIREWALL_FIELDS.find(f => f.id === form.fieldId))

const operatorOptions = computed(() =>
  operatorsForField(selectedField.value).map(o => ({ value: o.id, label: o.label })))

const previewExpression = computed(() => {
  const field = selectedField.value
  if (!field) return ''
  const raw = field.type === 'bool' ? (form.boolValue ? 'true' : 'false') : form.value
  return compileFirewallClause(field, form.operator, raw) || ''
})

function formatEvents24h(n?: number) {
  if (n === undefined || n === null) return '—'
  return String(n)
}

function onFieldChange() {
  const field = selectedField.value
  if (!field) return
  form.operator = defaultOperatorForField(field)
  form.value = ''
  form.boolValue = true
}

function onModeChange() {
  if (form.mode === 'advanced' && previewExpression.value) {
    form.expression = previewExpression.value
  }
}

function resetVisualForm() {
  form.fieldId = 'uri_path'
  form.operator = 'wildcard'
  form.value = ''
  form.boolValue = true
}

function openCreateModal() {
  editingRuleId.value = null
  form.description = ''
  form.action = 'block'
  form.mode = 'visual'
  form.expression = ''
  form.enabled = true
  resetVisualForm()
  createModalVisible.value = true
}

function openEditModal(record: FirewallRule) {
  editingRuleId.value = record.id
  form.description = record.description || ''
  form.action = record.action || 'block'
  form.mode = 'advanced'
  form.expression = record.expression || ''
  form.enabled = !record.paused
  createModalVisible.value = true
}

function resolveExpression(): string {
  if (form.mode === 'advanced') {
    return form.expression.trim()
  }
  return previewExpression.value
}

function applyShieldData(data: Record<string, unknown> | null) {
  if (!data) return
  if (data.security_level != null) shield.security_level = String(data.security_level)
  shield.bot_fight_mode = data.bot_fight_mode != null ? String(data.bot_fight_mode) : null
  shield.browser_check = data.browser_check != null ? String(data.browser_check) : null
}

async function loadShield() {
  if (!props.zoneId) return
  shieldLoading.value = true
  try {
    const res = await getCfSecurityProtection({ zoneId: props.zoneId })
    applyShieldData(res.data)
  } finally {
    shieldLoading.value = false
  }
}

async function saveShield(settingId: string, value: unknown) {
  if (!props.zoneId || shieldSaving.value) return
  shieldSaving.value = true
  try {
    const res = await setCfSecurityProtection({ zoneId: props.zoneId, settingId, value })
    applyShieldData(res.data)
    message.success('防护设置已保存')
  } finally {
    shieldSaving.value = false
  }
}

async function load() {
  if (!props.zoneId) return
  loading.value = true
  try {
    const res = await listCfFirewallRules({ zoneId: props.zoneId })
    rules.value = res.data || []
  } catch {
    rules.value = []
  } finally {
    loading.value = false
  }
}

async function loadAll() {
  await Promise.all([loadShield(), load()])
}

async function submitSave() {
  if (!props.zoneId) return
  const expression = resolveExpression()
  if (!expression) {
    message.warning(form.mode === 'visual' ? '请完善匹配条件' : '请填写过滤表达式')
    return
  }
  saveLoading.value = true
  try {
    if (editingRuleId.value) {
      await updateCfFirewallRule({
        zoneId: props.zoneId,
        ruleId: editingRuleId.value,
        action: form.action,
        expression,
        description: form.description.trim() || undefined,
        paused: !form.enabled,
      })
      message.success('规则已更新')
    } else {
      await createCfFirewallRule({
        zoneId: props.zoneId,
        action: form.action,
        expression,
        description: form.description.trim() || undefined,
        paused: !form.enabled,
      })
      message.success('规则已创建')
    }
    createModalVisible.value = false
    await load()
  } finally {
    saveLoading.value = false
  }
}

async function toggleRulePaused(record: FirewallRule) {
  await onRulePausedChange(record, !record.paused)
}

async function onRulePausedChange(record: FirewallRule, paused: boolean) {
  if (!props.zoneId || !record.id) return
  pauseLoadingMap.value[record.id] = true
  try {
    await setCfFirewallRulePaused({
      zoneId: props.zoneId,
      ruleId: record.id,
      paused,
    })
    record.paused = paused
    message.success(paused ? '规则已禁用' : '规则已启用')
  } catch {
    await load()
  } finally {
    pauseLoadingMap.value[record.id] = false
  }
}

function confirmDelete(record: FirewallRule) {
  if (!props.zoneId || !record.id) return
  Modal.confirm({
    title: '删除防火墙规则',
    content: `确定删除「${ruleDisplayName(record.description, record.expression)}」？此操作不可撤销。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      deleteLoadingMap.value[record.id] = true
      try {
        await deleteCfFirewallRule({ zoneId: props.zoneId!, ruleId: record.id })
        message.success('规则已删除')
        await load()
      } finally {
        deleteLoadingMap.value[record.id] = false
      }
    },
  })
}

watch(() => props.zoneId, () => loadAll(), { immediate: true })
</script>

<style scoped>
.cf-shield-card { margin-bottom: 16px; }
.cf-shield-form { max-width: 520px; }
.cf-shield-warn {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--warning, #faad14);
}
.cf-toolbar { margin-bottom: 16px; }
.cf-hint { margin-top: 12px; font-size: 12px; color: var(--text-sub); }
.cf-rule-name {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}
.cf-rule-name-link {
  color: var(--primary, #1677ff);
  cursor: pointer;
}
.cf-rule-name-link:hover { text-decoration: underline; }
.cf-rule-status { margin: 0; }
.cf-match { font-size: 13px; line-height: 1.4; }
.cf-events {
  font-variant-numeric: tabular-nums;
  color: var(--text-main);
}
.cf-rule-ops :deep(.ant-btn-link) { padding: 0 4px; }
.cf-mode-bar { margin-bottom: 12px; }
.cf-section-label {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--text-main);
}
.cf-mini-label {
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--text-sub);
}
.cf-value-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-sub);
}
.cf-expr-preview {
  margin-top: 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-sub, rgba(127, 127, 127, 0.08));
}
.cf-expr-preview-head {
  display: flex;
  justify-content: space-between;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--text-sub);
  border-bottom: 1px solid var(--border);
}
.cf-expr-len { font-family: ui-monospace, monospace; }
.cf-expr-preview-body {
  margin: 0;
  padding: 10px;
  font-family: ui-monospace, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-main);
}
</style>
