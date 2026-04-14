<template>
  <div>
    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="vcn" tab="VCN 管理">
        <div class="table-toolbar">
          <a-select v-model:value="selectedTenant" placeholder="选择租户" style="width: 280px" show-search
            option-filter-prop="label">
            <a-select-option v-for="t in tenants" :key="t.id" :value="t.id" :label="t.username">
              {{ t.username }} ({{ t.ociRegion }})
            </a-select-option>
          </a-select>
        </div>
        <a-empty v-if="!selectedTenant" description="请先选择租户" />
      </a-tab-pane>
      <a-tab-pane key="ip" tab="IP 更换">
        <a-alert message="根据 CIDR 网段更换实例公网 IP，可配合 Cloudflare DNS 自动更新" type="info" show-icon style="margin-bottom: 16px" />
        <a-empty description="IP 更换功能将在后续版本实现" />
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getTenantList } from '../api/tenant'

const activeTab = ref('vcn')
const tenants = ref<any[]>([])
const selectedTenant = ref('')

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
