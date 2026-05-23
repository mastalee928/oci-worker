<template>
  <div class="cf-security-tab">
    <div class="cf-toolbar">
      <a-space wrap>
        <a-button type="primary" @click="openCreateModal">添加规则</a-button>
        <a-button :loading="loading" @click="load">刷新</a-button>
      </a-space>
    </div>
    <a-table
      :columns="columns"
      :data-source="rules"
      :loading="loading"
      row-key="id"
      size="middle"
      :scroll="{ x: 900 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'paused'">
          <a-switch
            :checked="!record.paused"
            :loading="!!pauseLoadingMap[record.id]"
            checked-children="启用"
            un-checked-children="暂停"
            @change="(checked: boolean) => onRulePausedChange(record, !checked)"
          />
        </template>
        <template v-else-if="column.key === 'expression'">
          <span class="cf-expr" :title="record.expression">{{ record.expression || '—' }}</span>
        </template>
      </template>
    </a-table>
    <p class="cf-hint">防火墙规则（Ingress）。支持可视化构建或手写 Wirefilter 表达式。</p>

    <a-modal
      v-model:open="createModalVisible"
      title="添加防火墙规则"
      :confirm-loading="saveLoading"
      width="680px"
      @ok="submitCreate"
    >
      <a-form layout="vertical">
        <a-form-item label="描述">
          <a-input v-model:value="form.description" placeholder="规则说明" allow-clear />
        </a-form-item>
        <a-form-item label="动作" required>
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

        <a-form-item label="创建后状态">
          <a-switch v-model:checked="form.enabled" checked-children="启用" un-checked-children="暂停" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, watch, computed } from 'vue'
import { message } from 'ant-design-vue'
import {
  listCfFirewallRules,
  createCfFirewallRule,
  setCfFirewallRulePaused,
} from '../../api/cloudflare'
import {
  FIREWALL_FIELDS,
  compileFirewallClause,
  defaultOperatorForField,
  operatorsForField,
  type FieldDef,
  type OperatorId,
} from './cfFirewallExpression'

interface FirewallRule {
  id: string
  description?: string
  action?: string
  paused?: boolean
  priority?: number
  expression?: string
}

const props = defineProps<{ zoneId?: string }>()

const loading = ref(false)
const saveLoading = ref(false)
const createModalVisible = ref(false)
const rules = ref<FirewallRule[]>([])
const pauseLoadingMap = ref<Record<string, boolean>>({})

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
  { title: '描述', dataIndex: 'description', ellipsis: true, width: 140 },
  { title: '动作', dataIndex: 'action', width: 100 },
  { title: '表达式', key: 'expression', ellipsis: true },
  { title: '优先级', dataIndex: 'priority', width: 72 },
  { title: '状态', key: 'paused', width: 100 },
  { title: 'ID', dataIndex: 'id', ellipsis: true, width: 120 },
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
  form.description = ''
  form.action = 'block'
  form.mode = 'visual'
  form.expression = ''
  form.enabled = true
  resetVisualForm()
  createModalVisible.value = true
}

function resolveExpression(): string {
  if (form.mode === 'advanced') {
    return form.expression.trim()
  }
  return previewExpression.value
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

async function submitCreate() {
  if (!props.zoneId) return
  const expression = resolveExpression()
  if (!expression) {
    message.warning(form.mode === 'visual' ? '请完善匹配条件' : '请填写过滤表达式')
    return
  }
  saveLoading.value = true
  try {
    await createCfFirewallRule({
      zoneId: props.zoneId,
      action: form.action,
      expression,
      description: form.description.trim() || undefined,
      paused: !form.enabled,
    })
    message.success('规则已创建')
    createModalVisible.value = false
    await load()
  } finally {
    saveLoading.value = false
  }
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
    message.success(paused ? '规则已暂停' : '规则已启用')
  } catch {
    await load()
  } finally {
    pauseLoadingMap.value[record.id] = false
  }
}

watch(() => props.zoneId, () => load(), { immediate: true })
</script>

<style scoped>
.cf-toolbar { margin-bottom: 16px; }
.cf-hint { margin-top: 12px; font-size: 12px; color: var(--text-sub); }
.cf-expr {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
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
