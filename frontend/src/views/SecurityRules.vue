<template>
  <div>
    <a-alert message="选择租户和实例后，可管理其安全列表规则" type="info" show-icon style="margin-bottom: 16px" />
    <div class="table-toolbar">
      <a-space>
        <a-select v-model:value="selectedTenant" placeholder="选择租户" style="width: 280px" show-search
          option-filter-prop="label" @change="onTenantChange">
          <a-select-option v-for="t in tenants" :key="t.id" :value="t.id" :label="t.username">
            {{ t.username }} ({{ t.ociRegion }})
          </a-select-option>
        </a-select>
        <a-button type="primary" :disabled="!selectedTenant" @click="loadRules">
          <template #icon><ReloadOutlined /></template>加载安全规则
        </a-button>
        <a-button type="primary" ghost :disabled="!selectedTenant" @click="handleReleaseAll">
          一键放行所有端口
        </a-button>
      </a-space>
    </div>

    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="ingress" tab="入站规则">
        <a-table :columns="ruleColumns" :data-source="ingressRules" :loading="loading" row-key="id" size="small" />
      </a-tab-pane>
      <a-tab-pane key="egress" tab="出站规则">
        <a-table :columns="ruleColumns" :data-source="egressRules" :loading="loading" row-key="id" size="small" />
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { getTenantList } from '../api/tenant'

const ruleColumns = [
  { title: '协议', dataIndex: 'protocol', width: 80 },
  { title: '来源/目的', dataIndex: 'source', ellipsis: true },
  { title: '端口范围', dataIndex: 'portRange', width: 120 },
  { title: '描述', dataIndex: 'description', ellipsis: true },
  { title: '操作', key: 'action', width: 80 },
]

const tenants = ref<any[]>([])
const selectedTenant = ref('')
const activeTab = ref('ingress')
const loading = ref(false)
const ingressRules = ref<any[]>([])
const egressRules = ref<any[]>([])

async function loadTenants() {
  try {
    const res = await getTenantList({ current: 1, size: 1000 })
    tenants.value = res.data.records || []
  } catch { /* ignore */ }
}

function onTenantChange() {
  ingressRules.value = []
  egressRules.value = []
}

async function loadRules() {
  if (!selectedTenant.value) return
  loading.value = true
  try {
    // Will be implemented in phase 5
    message.info('安全规则加载功能将在后续版本实现')
  } finally {
    loading.value = false
  }
}

async function handleReleaseAll() {
  message.info('一键放行功能将在后续版本实现')
}

onMounted(() => loadTenants())
</script>

<style scoped>
.table-toolbar { margin-bottom: 16px; }
</style>
