<template>
  <div>
    <div class="table-toolbar">
      <a-select v-model:value="selectedTenant" placeholder="选择租户查看引导卷" style="width: 300px" show-search
        option-filter-prop="label">
        <a-select-option v-for="t in tenants" :key="t.id" :value="t.id" :label="t.username">
          {{ t.username }} ({{ t.ociRegion }})
        </a-select-option>
      </a-select>
    </div>
    <a-empty v-if="!selectedTenant" description="请先选择租户" />
    <a-table v-else :columns="columns" :data-source="volumes" :loading="loading" row-key="id" size="middle">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'sizeInGBs'">
          {{ record.sizeInGBs }} GB
        </template>
        <template v-if="column.key === 'state'">
          <a-badge :status="record.state === 'AVAILABLE' ? 'success' : 'processing'" :text="record.state" />
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getTenantList } from '../api/tenant'

const columns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName' },
  { title: '大小', dataIndex: 'sizeInGBs', key: 'sizeInGBs', width: 100 },
  { title: '状态', dataIndex: 'state', key: 'state', width: 120 },
  { title: '创建时间', dataIndex: 'timeCreated', key: 'timeCreated', width: 180 },
  { title: '操作', key: 'action', width: 160 },
]

const tenants = ref<any[]>([])
const selectedTenant = ref('')
const loading = ref(false)
const volumes = ref<any[]>([])

onMounted(async () => {
  try {
    const res = await getTenantList({ current: 1, size: 1000 })
    tenants.value = res.data.records || []
  } catch { /* ignore */ }
})
</script>

<style scoped>
.table-toolbar { margin-bottom: 16px; }
</style>
