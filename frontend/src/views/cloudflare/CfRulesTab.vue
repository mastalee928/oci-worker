<template>
  <div class="cf-rules-tab">
    <div class="cf-toolbar">
      <a-button :loading="loading" @click="load">刷新</a-button>
    </div>
    <a-table
      :columns="columns"
      :data-source="rules"
      :loading="loading"
      row-key="id"
      size="middle"
      :scroll="{ x: 800 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <a-tag :color="record.status === 'active' ? 'success' : 'default'">{{ record.status }}</a-tag>
        </template>
        <template v-else-if="column.key === 'targets'">
          {{ formatTargets(record.targets) }}
        </template>
        <template v-else-if="column.key === 'actions'">
          {{ formatActions(record.actions) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-popconfirm title="确定删除此 Page Rule？" @confirm="handleDelete(record.id)">
            <a-button type="link" danger size="small">删除</a-button>
          </a-popconfirm>
        </template>
      </template>
    </a-table>
    <p class="cf-hint">Page Rules（旧版规则）。新建复杂规则请使用 Cloudflare 控制台 Rules 产品。</p>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { listCfPageRules, deleteCfPageRule } from '../../api/cloudflare'

const props = defineProps<{ zoneId?: string }>()

const loading = ref(false)
const rules = ref<any[]>([])

const columns = [
  { title: '优先级', dataIndex: 'priority', width: 80 },
  { title: '状态', key: 'status', width: 90 },
  { title: '匹配', key: 'targets', ellipsis: true },
  { title: '动作', key: 'actions', ellipsis: true },
  { title: '操作', key: 'action', width: 80 },
]

function formatTargets(targets: unknown) {
  if (!Array.isArray(targets) || targets.length === 0) return '—'
  const t = targets[0] as { constraint?: { value?: string } }
  return t?.constraint?.value || JSON.stringify(targets[0])
}

function formatActions(actions: unknown) {
  if (!Array.isArray(actions) || actions.length === 0) return '—'
  return actions.map((a: { id?: string }) => a.id || '').filter(Boolean).join(', ') || '—'
}

async function load() {
  if (!props.zoneId) return
  loading.value = true
  try {
    const res = await listCfPageRules({ zoneId: props.zoneId })
    rules.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function handleDelete(ruleId: string) {
  if (!props.zoneId) return
  await deleteCfPageRule({ zoneId: props.zoneId, ruleId })
  message.success('已删除')
  await load()
}

watch(() => props.zoneId, () => load(), { immediate: true })
</script>

<style scoped>
.cf-toolbar { margin-bottom: 16px; }
.cf-hint { margin-top: 12px; font-size: 12px; color: var(--text-sub); }
</style>
