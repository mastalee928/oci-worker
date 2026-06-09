<template>
  <div class="alidns-page">
    <a-alert
      v-if="!configured"
      type="warning"
      show-icon
      style="margin-bottom: 16px"
      message="尚未配置阿里云DNS"
      description="请先在「系统设置」→「阿里云DNS」填写 AccessKey ID 和 AccessKey Secret，并点击测试连接。"
    />

    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="domains" tab="域名列表">
        <div style="margin-bottom:12px;display:flex;gap:8px;align-items:center">
          <a-button type="primary" @click="loadDomains(1)"><i class="ri-refresh-line"></i> 刷新</a-button>
          <span style="color:#888">共 {{ total }} 个域名</span>
        </div>
        <a-table
          :columns="domainColumns"
          :data-source="domains"
          :pagination="false"
          :loading="loading"
          row-key="domainId"
          size="small"
        />
        <div style="margin-top:12px;text-align:right">
          <a-pagination
            v-model:current="page"
            :page-size="perPage"
            :total="total"
            @change="onPageChange"
            show-size-changer
          />
        </div>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getAliDNSAccountConfig, listAliDNSDomains } from '../api/alidns'

defineOptions({ name: 'AliDNS' })

const activeTab = ref('domains')
const configured = ref(false)
const domains = ref<any[]>([])
const loading = ref(false)
const page = ref(1)
const perPage = ref(20)
const total = ref(0)

const domainColumns = [
  { title: '域名', dataIndex: 'domainName', key: 'domainName' },
  { title: 'PunyCode', dataIndex: 'punyCode', key: 'punyCode' },
  { title: '记录数', dataIndex: 'recordCount', key: 'recordCount' },
]

async function loadConfig() {
  try {
    const res = await getAliDNSAccountConfig()
    configured.value = res.data?.configured === true
  } catch {
    configured.value = false
  }
}

async function loadDomains(p: number) {
  loading.value = true
  try {
    const res = await listAliDNSDomains(p, perPage.value)
    domains.value = res.data || []
    total.value = domains.value.length
    page.value = p
  } catch (e: any) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

function onPageChange(p: number) {
  loadDomains(p)
}

onMounted(async () => {
  await loadConfig()
  if (configured.value) await loadDomains(1)
})
</script>

<style scoped>
.alidns-page { padding: 8px 0; }
</style>
