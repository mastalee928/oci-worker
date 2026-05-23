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
    <p class="cf-hint">防火墙规则（Ingress）。表达式使用 Cloudflare Wirefilter 语法。</p>

    <a-modal
      v-model:open="createModalVisible"
      title="添加防火墙规则"
      :confirm-loading="saveLoading"
      width="560px"
      @ok="submitCreate"
    >
      <a-form layout="vertical">
        <a-form-item label="描述">
          <a-input v-model:value="form.description" placeholder="规则说明" allow-clear />
        </a-form-item>
        <a-form-item label="动作" required>
          <a-select v-model:value="form.action" :options="actionOptions" />
        </a-form-item>
        <a-form-item label="过滤表达式" required>
          <a-textarea
            v-model:value="form.expression"
            placeholder='如 (http.request.uri.path contains "/admin") 或 (ip.geoip.country in {"CN"})'
            :rows="3"
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
import { ref, reactive, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  listCfFirewallRules,
  createCfFirewallRule,
  setCfFirewallRulePaused,
} from '../../api/cloudflare'

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

function openCreateModal() {
  form.description = ''
  form.action = 'block'
  form.expression = ''
  form.enabled = true
  createModalVisible.value = true
}

async function submitCreate() {
  if (!props.zoneId) return
  if (!form.expression.trim()) {
    message.warning('请填写过滤表达式')
    return
  }
  saveLoading.value = true
  try {
    await createCfFirewallRule({
      zoneId: props.zoneId,
      action: form.action,
      expression: form.expression.trim(),
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
</style>
