<template>
  <div class="cf-security-tab">
    <div class="cf-toolbar">
      <a-button :loading="loading" @click="load">刷新</a-button>
    </div>
    <a-table
      :columns="columns"
      :data-source="rules"
      :loading="loading"
      row-key="id"
      size="middle"
      :scroll="{ x: 720 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'paused'">
          <a-tag :color="record.paused ? 'default' : 'success'">{{ record.paused ? '暂停' : '启用' }}</a-tag>
        </template>
      </template>
    </a-table>
    <p class="cf-hint">防火墙规则（Ingress）。复杂 WAF 规则集请在 Cloudflare 控制台编辑。</p>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { listCfFirewallRules } from '../../api/cloudflare'

const props = defineProps<{ zoneId?: string }>()

const loading = ref(false)
const rules = ref<any[]>([])

const columns = [
  { title: '描述', dataIndex: 'description', ellipsis: true },
  { title: '动作', dataIndex: 'action', width: 100 },
  { title: '优先级', dataIndex: 'priority', width: 80 },
  { title: '状态', key: 'paused', width: 80 },
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

watch(() => props.zoneId, () => load(), { immediate: true })
</script>

<style scoped>
.cf-toolbar { margin-bottom: 16px; }
.cf-hint { margin-top: 12px; font-size: 12px; color: var(--text-sub); }
</style>
