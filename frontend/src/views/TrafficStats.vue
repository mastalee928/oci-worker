<template>
  <div>
    <div class="table-toolbar">
      <a-space>
        <a-select v-model:value="selectedTenant" placeholder="选择租户" style="width: 280px" show-search
          option-filter-prop="label">
          <a-select-option v-for="t in tenants" :key="t.id" :value="t.id" :label="t.username">
            {{ t.username }} ({{ t.ociRegion }})
          </a-select-option>
        </a-select>
        <a-button type="primary" :disabled="!selectedTenant">加载流量数据</a-button>
      </a-space>
    </div>
    <div ref="chartRef" style="width: 100%; height: 400px" />
    <a-empty v-if="!selectedTenant" description="请先选择租户查看流量统计" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getTenantList } from '../api/tenant'

const tenants = ref<any[]>([])
const selectedTenant = ref('')
const chartRef = ref<HTMLElement>()

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
