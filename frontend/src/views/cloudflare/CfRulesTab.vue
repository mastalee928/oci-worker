<template>
  <div class="cf-rules-tab">
    <div class="cf-toolbar">
      <a-button :loading="loading" @click="load">刷新</a-button>
    </div>
    <a-table
      v-if="!isMobile"
      :columns="columns"
      :data-source="rules"
      :loading="loading"
      row-key="rowKey"
      size="middle"
      :scroll="{ x: 960 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'enabled'">
          <a-tag :color="record.enabled !== false ? 'success' : 'default'">
            {{ record.enabled !== false ? '启用' : '禁用' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'phase'">
          {{ formatPhase(record.phase) }}
        </template>
      </template>
    </a-table>
    <a-spin v-else :spinning="loading">
      <a-empty v-if="!loading && rules.length === 0" description="暂无规则" />
      <div v-for="record in rules" :key="record.rowKey" class="mobile-card">
        <div class="mobile-card-header">
          <span class="mobile-card-title">{{ record.description || formatPhase(record.phase) }}</span>
          <a-tag :color="record.enabled !== false ? 'success' : 'default'">
            {{ record.enabled !== false ? '启用' : '禁用' }}
          </a-tag>
        </div>
        <div class="mobile-card-body">
          <div class="mobile-card-row">
            <span class="label">阶段</span>
            <span class="value">{{ formatPhase(record.phase) }}</span>
          </div>
          <div v-if="record.action" class="mobile-card-row">
            <span class="label">动作</span>
            <span class="value">{{ record.action }}</span>
          </div>
          <div v-if="record.expression" class="mobile-card-row mobile-card-row-stack">
            <span class="label">表达式</span>
            <span class="value">{{ record.expression }}</span>
          </div>
        </div>
      </div>
    </a-spin>
    <p class="cf-hint">
      使用 Cloudflare Rulesets（兼容账户 API 令牌 cfat_）。旧版 Page Rules 接口不支持 cfat_，故不在此展示。
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { listCfZoneRules } from '../../api/cloudflare'
import { useIsMobile } from '../../composables/useIsMobile'

const props = defineProps<{ zoneId?: string }>()
const { isMobile } = useIsMobile()

const loading = ref(false)
const rules = ref<any[]>([])

const columns = [
  { title: '阶段', key: 'phase', width: 140 },
  { title: '描述', dataIndex: 'description', ellipsis: true },
  { title: '表达式', dataIndex: 'expression', ellipsis: true },
  { title: '动作', dataIndex: 'action', width: 120 },
  { title: '状态', key: 'enabled', width: 80 },
]

const phaseLabels: Record<string, string> = {
  http_request_dynamic_redirect: '重定向',
  http_request_transform: '转换',
  http_request_cache_settings: '缓存',
  http_request_firewall_custom: '防火墙',
  http_config_settings: '配置',
  http_response_headers_transform: '响应头',
}

function formatPhase(phase?: string) {
  if (!phase) return '—'
  return phaseLabels[phase] || phase
}

async function load() {
  if (!props.zoneId) return
  loading.value = true
  try {
    const res = await listCfZoneRules({ zoneId: props.zoneId }, true)
    const list = res.data || []
    rules.value = list.map((r: any, idx: number) => ({
      ...r,
      rowKey: r.id || r.ref || `${r.rulesetId}-${idx}`,
    }))
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
.mobile-card-row-stack {
  flex-direction: column;
  align-items: stretch;
}
.mobile-card-row-stack .value { text-align: left; }
.mobile-card-row .label { color: var(--text-sub); flex-shrink: 0; }
.mobile-card-row .value { text-align: right; word-break: break-all; }
</style>
