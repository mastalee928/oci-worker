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
      v-if="!isMobile"
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
    <a-spin v-else :spinning="loading">
      <a-empty v-if="!loading && rules.length === 0" description="暂无防火墙规则" />
      <div v-for="record in rules" :key="record.id" class="mobile-card">
        <div class="mobile-card-header">
          <a class="mobile-card-title cf-rule-name-link" @click="openEditModal(record)">
            {{ ruleDisplayName(record.description, record.expression) }}
          </a>
          <a-tag :color="record.paused ? 'default' : 'success'">
            {{ record.paused ? '已禁用' : '活动' }}
          </a-tag>
        </div>
        <div class="mobile-card-body">
          <div class="mobile-card-row">
            <span class="label">匹配</span>
            <span class="value">{{ humanizeExpression(record.expression) }}</span>
          </div>
          <div class="mobile-card-row">
            <span class="label">操作</span>
            <span class="value">{{ firewallActionLabel(record.action) }}</span>
          </div>
          <div class="mobile-card-row">
            <span class="label">24h 事件</span>
            <span class="value">{{ formatEvents24h(record.events24h) }}</span>
          </div>
        </div>
        <a-space wrap class="mobile-card-actions">
          <a-button size="small" @click="openEditModal(record)">编辑</a-button>
          <a-button
            size="small"
            :loading="!!pauseLoadingMap[record.id]"
            @click="toggleRulePaused(record)"
          >
            {{ record.paused ? '启用' : '禁用' }}
          </a-button>
          <a-button
            size="small"
            danger
            :loading="!!deleteLoadingMap[record.id]"
            @click="confirmDelete(record)"
          >
            删除
          </a-button>
        </a-space>
      </div>
    </a-spin>
    <p class="cf-hint">防火墙规则（Ingress）。支持可视化构建或手写 Wirefilter 表达式。</p>

    <a-modal
      v-model:open="createModalVisible"
      :mask-closable="false"
      :keyboard="false"
      :title="editingRuleId ? '编辑防火墙规则' : '添加防火墙规则'"
      :confirm-loading="saveLoading"
      :width="isMobile ? 'calc(100vw - 32px)' : 920"
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
          <template v-if="form.mode === 'visual'">
            <div class="cf-builder">
              <p class="cf-section-label">当传入请求匹配时…</p>
              <a-row :gutter="8" class="cf-builder-head" align="middle">
                <a-col :span="3" />
                <a-col :xs="24" :sm="7">字段</a-col>
                <a-col :xs="24" :sm="7">运算符</a-col>
                <a-col :xs="24" :sm="7">值</a-col>
              </a-row>
              <div
                v-for="(row, idx) in form.rows"
                :key="row.key"
                class="cf-builder-row"
              >
                <div class="cf-builder-rail" :class="{ 'cf-builder-rail--first': idx === 0 }">
                  <div v-if="idx > 0" class="cf-builder-connector">
                    <button
                      type="button"
                      class="cf-builder-join-tag"
                      @click="toggleRowJoin(idx)"
                    >
                      {{ row.prevJoin === 'or' ? 'Or' : 'And' }}
                    </button>
                  </div>
                </div>
                <div class="cf-builder-fields">
                  <FirewallClauseFields :clause="row" compact />
                </div>
                <div class="cf-builder-actions">
                  <a-button size="small" class="cf-builder-add-btn" @click="addRowAfter(idx, 'and')">And</a-button>
                  <a-button
                    v-if="idx === form.rows.length - 1"
                    size="small"
                    class="cf-builder-add-btn"
                    @click="addRowAfter(idx, 'or')"
                  >
                    Or
                  </a-button>
                  <a-button
                    v-if="form.rows.length > 1"
                    type="text"
                    size="small"
                    class="cf-builder-del-btn"
                    aria-label="删除"
                    @click="removeRow(idx)"
                  >
                    ×
                  </a-button>
                </div>
              </div>
            </div>
            <div class="cf-expr-preview">
              <pre class="cf-expr-preview-body">{{ displayPreviewExpression }}</pre>
              <div class="cf-expr-preview-foot">
                <a-button type="link" size="small" class="cf-expr-link" @click="setFormMode('advanced')">
                  编辑表达式
                </a-button>
                <span class="cf-expr-len">{{ previewExpression.length }} / 4000 characters</span>
              </div>
            </div>
          </template>

          <template v-else>
            <p class="cf-section-label">当传入请求匹配时…</p>
            <div class="cf-expr-panel">
              <textarea
                v-model="form.expression"
                class="cf-expr-panel-input"
                placeholder='如 (http.host eq "example.com" and http.request.uri.path contains "/admin")'
                spellcheck="false"
              />
              <div class="cf-expr-panel-foot">
                <a-button type="link" size="small" class="cf-expr-link" @click="setFormMode('visual')">
                  使用可视化构建
                </a-button>
                <span class="cf-expr-len">{{ form.expression.length }} / 4000 characters</span>
              </div>
            </div>
          </template>
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
import { useIsMobile } from '../../composables/useIsMobile'
import {
  listCfFirewallRules,
  createCfFirewallRule,
  updateCfFirewallRule,
  deleteCfFirewallRule,
  setCfFirewallRulePaused,
  getCfSecurityProtection,
  setCfSecurityProtection,
} from '../../api/cloudflare'
import FirewallClauseFields from './CfFirewallClauseFields.vue'
import {
  compileFirewallFlatRows,
  humanizeExpression,
  firewallActionLabel,
  ruleDisplayName,
  parseFirewallFlatRows,
  prettyPrintFirewallExpression,
  normalizeFirewallExpression,
  type FirewallJoin,
  type VisualClauseForm,
} from './cfFirewallExpression'

interface FirewallRule {
  id: string
  description?: string
  action?: string
  paused?: boolean
  expression?: string
  events24h?: number
}

type BuilderRow = VisualClauseForm & { key: string; prevJoin?: FirewallJoin }

function newKey() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

const props = defineProps<{ zoneId?: string }>()
const { isMobile } = useIsMobile()

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
  rows: [] as BuilderRow[],
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

const previewExpression = computed(() => compileFirewallFlatRows(rowsToFlat()) || '')

const displayPreviewExpression = computed(() =>
  previewExpression.value ? prettyPrintFirewallExpression(previewExpression.value) : '',
)

function formatEvents24h(n?: number) {
  if (n === undefined || n === null) return '—'
  return String(n)
}

function createClauseRow(partial?: VisualClauseForm): BuilderRow {
  return {
    key: newKey(),
    fieldId: partial?.fieldId ?? '',
    operator: partial?.operator ?? 'eq',
    value: partial?.value ?? '',
    boolValue: partial?.boolValue ?? true,
  }
}

function rowsToFlat() {
  return form.rows.map((row, idx) => ({
    clause: {
      fieldId: row.fieldId,
      operator: row.operator,
      value: row.value,
      boolValue: row.boolValue,
    },
    prevJoin: idx === 0 ? undefined : row.prevJoin,
  }))
}

function applyFlatRows(flat: ReturnType<typeof parseFirewallFlatRows>) {
  if (!flat?.length) return
  form.rows = flat.map((item, idx) => ({
    key: newKey(),
    ...item.clause,
    prevJoin: idx === 0 ? undefined : item.prevJoin,
  }))
}

function addRowAfter(idx: number, join: FirewallJoin) {
  form.rows.splice(idx + 1, 0, { ...createClauseRow(), prevJoin: join })
}

function toggleRowJoin(idx: number) {
  if (idx <= 0) return
  const row = form.rows[idx]
  if (!row) return
  row.prevJoin = row.prevJoin === 'or' ? 'and' : 'or'
}

function removeRow(idx: number) {
  if (form.rows.length <= 1) return
  const wasFirst = idx === 0
  form.rows.splice(idx, 1)
  if (wasFirst && form.rows[0]) form.rows[0].prevJoin = undefined
}

function resetVisualForm() {
  form.rows = [createClauseRow()]
}

function ensureVisualHasRow() {
  if (form.mode === 'visual' && form.rows.length === 0) resetVisualForm()
}

function tryLoadVisualFromExpression(expr: string): boolean {
  const parsed = parseFirewallFlatRows(expr)
  if (!parsed) return false
  applyFlatRows(parsed)
  return true
}

function setFormMode(next: 'visual' | 'advanced') {
  if (next === form.mode) return
  if (next === 'advanced') {
    const src = previewExpression.value || form.expression.trim()
    if (src) form.expression = prettyPrintFirewallExpression(src)
    form.mode = 'advanced'
    return
  }
  const src = form.expression.trim()
  if (!src) {
    if (!form.rows.length) resetVisualForm()
    form.mode = 'visual'
    return
  }
  if (tryLoadVisualFromExpression(src)) {
    form.mode = 'visual'
  } else {
    message.warning('该表达式含 NOT 或嵌套组，请继续使用编辑表达式')
  }
}

function openCreateModal() {
  editingRuleId.value = null
  form.description = ''
  form.action = 'block'
  form.expression = ''
  form.enabled = true
  resetVisualForm()
  form.mode = 'visual'
  createModalVisible.value = true
}

function openEditModal(record: FirewallRule) {
  editingRuleId.value = record.id
  form.description = record.description || ''
  form.action = record.action || 'block'
  form.expression = record.expression || ''
  form.enabled = !record.paused
  if (form.expression) {
    form.expression = prettyPrintFirewallExpression(form.expression)
  }
  if (record.expression && tryLoadVisualFromExpression(record.expression)) {
    form.mode = 'visual'
  } else {
    form.mode = 'advanced'
    resetVisualForm()
  }
  createModalVisible.value = true
}

function resolveExpression(): string {
  if (form.mode === 'advanced') return normalizeFirewallExpression(form.expression)
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

watch(createModalVisible, visible => {
  if (visible) ensureVisualHasRow()
})
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
.cf-builder { margin-top: 4px; }
.cf-section-label {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
}
.cf-advanced-head { margin-bottom: 8px; text-align: right; }
.cf-expr-link { padding: 0; height: auto; }
.cf-builder-head {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-sub);
  margin-bottom: 8px;
}
.cf-builder-row {
  display: flex;
  align-items: stretch;
  gap: 8px;
  margin-bottom: 0;
  position: relative;
}
.cf-builder-rail {
  width: 56px;
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  position: relative;
}
.cf-builder-rail--first { visibility: hidden; }
.cf-builder-rail:not(.cf-builder-rail--first)::before {
  content: '';
  position: absolute;
  top: -12px;
  bottom: 50%;
  left: 50%;
  width: 2px;
  margin-left: -1px;
  background: #c5cad1;
}
.cf-builder-connector {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 48px;
  position: relative;
  z-index: 1;
}
.cf-builder-join-tag {
  font-size: 12px;
  font-weight: 600;
  padding: 4px 12px;
  border-radius: 4px;
  border: 1px solid #c5cad1;
  background: #fff;
  color: var(--text-main);
  white-space: nowrap;
  cursor: pointer;
  line-height: 1.2;
}
.cf-builder-join-tag:hover {
  border-color: var(--primary, #1677ff);
  color: var(--primary, #1677ff);
}
.cf-builder-fields { flex: 1; min-width: 0; padding: 8px 0; }
.cf-builder-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 0;
  min-width: 88px;
}
.cf-builder-add-btn.ant-btn {
  font-size: 12px;
  padding: 0 10px;
  height: 28px;
  border-radius: 4px;
  background: #fff;
}
.cf-builder-del-btn.ant-btn {
  font-size: 18px;
  line-height: 1;
  padding: 0 4px;
  height: 28px;
  color: var(--text-sub);
}
.cf-builder-del-btn.ant-btn:hover { color: #ff4d4f; }
.cf-join-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.cf-clause-block {
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px dashed var(--border);
}
.cf-clause-block--group {
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card, rgba(0, 0, 0, 0.02));
}
.cf-clause-block--nested {
  border-bottom: none;
  padding-bottom: 0;
  margin-bottom: 8px;
}
.cf-or-group-label,
.cf-not-group-label {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-main);
}
.cf-not-group {
  margin-top: 4px;
  padding: 10px 12px;
  border: 1px dashed var(--border);
  border-radius: 8px;
  background: var(--bg-sub, rgba(127, 127, 127, 0.06));
}
.cf-not-group--nested {
  margin-top: 8px;
}
.cf-join-bar--inner {
  margin-bottom: 8px;
}
.cf-or-group-label {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--text-sub);
}
.cf-remove-group {
  margin-top: 8px;
  padding-left: 0;
}
.cf-clause-block:last-of-type { border-bottom: none; }
.cf-clause-join {
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--primary, #1677ff);
}
.cf-clause-del { text-align: right; }
.cf-add-clause { margin-bottom: 12px; }
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
  margin-top: 16px;
  border: 1px solid #d9dde3;
  border-radius: 4px;
  background: #eceef1;
  overflow: hidden;
}
.cf-expr-panel {
  border: 1px solid #d9dde3;
  border-radius: 4px;
  background: #eceef1;
  overflow: hidden;
}
.cf-expr-panel-input {
  display: block;
  width: 100%;
  min-height: 160px;
  padding: 12px 14px;
  border: none;
  outline: none;
  resize: vertical;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  line-height: 1.55;
  color: #1f2937;
  background: #eceef1;
  caret-color: #1f2937;
  box-shadow: none;
}
.cf-expr-panel-input::placeholder { color: #9aa3af; }
.cf-expr-panel-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  border-top: 1px solid #d9dde3;
  background: #e4e7ec;
}
.cf-expr-preview-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
  border-bottom: 1px solid #d9dde3;
  background: #e4e7ec;
}
.cf-expr-preview-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  border-top: 1px solid #d9dde3;
  background: #e4e7ec;
}
.cf-expr-len { font-size: 12px; color: #6b7280; font-family: ui-monospace, monospace; }
.cf-expr-len--solo { margin: 6px 0 0; text-align: right; }
.cf-expr-preview-body {
  margin: 0;
  padding: 12px 14px;
  min-height: 72px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  color: #1f2937;
  user-select: text;
  background: #eceef1;
}
.cf-expr-editor :deep(textarea.ant-input) {
  font-family: ui-monospace, monospace;
  font-size: 12px;
  color: var(--text-main) !important;
  background: var(--input-bg) !important;
  caret-color: var(--text-main) !important;
}
.cf-expr-editor :deep(textarea.ant-input:focus) {
  caret-color: var(--primary) !important;
}
.mobile-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}
.mobile-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.mobile-card-title { font-weight: 600; word-break: break-word; }
.mobile-card-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 4px;
}
.mobile-card-row .label { color: var(--text-sub); flex-shrink: 0; }
.mobile-card-row .value { text-align: right; word-break: break-all; }
.mobile-card-actions { margin-top: 8px; }
@media (max-width: 767px) {
  .cf-join-bar {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .cf-clause-del { text-align: left; margin-top: 4px; }
}
</style>

