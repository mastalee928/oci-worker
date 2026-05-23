<template>
  <CfZoneLayout
    v-model:zone-id="zoneId"
    v-model:active-menu="activeMenu"
    :cf-configured="cfConfigured"
    @create-zone="openCreateZoneModal"
  >
    <CfZoneOverview
      v-if="activeMenu === 'overview'"
      :zone-id="zoneId"
      @update:zone-id="zoneId = $event"
    />
    <CfDnsTab
      v-else-if="activeMenu === 'dns' && zoneId"
      :zone-id="zoneId"
      :cf-configured="cfConfigured"
      @update:zone-id="zoneId = $event"
    />
    <CfEmailTab
      v-else-if="activeMenu === 'email' && zoneId"
      :zone-id="zoneId"
      :cf-configured="cfConfigured"
    />
    <CfSslTab v-else-if="activeMenu === 'ssl' && zoneId" :zone-id="zoneId" />
    <CfSecurityTab v-else-if="activeMenu === 'security' && zoneId" :zone-id="zoneId" />
    <CfCacheTab v-else-if="activeMenu === 'cache' && zoneId" :zone-id="zoneId" />
    <CfWorkersRoutesTab v-else-if="activeMenu === 'workers-routes' && zoneId" :zone-id="zoneId" />
    <CfRulesTab v-else-if="activeMenu === 'rules' && zoneId" :zone-id="zoneId" />
  </CfZoneLayout>

  <a-modal
    v-model:open="createZoneVisible"
    title="添加区域"
    :confirm-loading="createZoneLoading"
    @ok="submitCreateZone"
  >
    <a-form layout="vertical">
      <a-form-item label="域名" required>
        <a-input v-model:value="createZoneName" placeholder="example.com" @pressEnter="submitCreateZone" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import CfZoneLayout, { type CfZoneMenuKey } from './CfZoneLayout.vue'
import CfZoneOverview from './CfZoneOverview.vue'
import CfDnsTab from './CfDnsTab.vue'
import CfEmailTab from './CfEmailTab.vue'
import CfSslTab from './CfSslTab.vue'
import CfSecurityTab from './CfSecurityTab.vue'
import CfCacheTab from './CfCacheTab.vue'
import CfWorkersRoutesTab from './CfWorkersRoutesTab.vue'
import CfRulesTab from './CfRulesTab.vue'
import { createCfZone } from '../../api/cloudflare'

defineProps<{ cfConfigured: boolean }>()

const zoneId = ref<string | undefined>(undefined)
const activeMenu = ref<CfZoneMenuKey>('overview')

const createZoneVisible = ref(false)
const createZoneLoading = ref(false)
const createZoneName = ref('')

function openCreateZoneModal() {
  createZoneName.value = ''
  createZoneVisible.value = true
}

async function submitCreateZone() {
  const name = createZoneName.value.trim()
  if (!name) {
    message.warning('请输入域名')
    return
  }
  createZoneLoading.value = true
  try {
    const res = await createCfZone({ name })
    message.success('区域已创建')
    createZoneVisible.value = false
    if (res.data?.id) {
      zoneId.value = res.data.id
      activeMenu.value = 'overview'
    }
  } finally {
    createZoneLoading.value = false
  }
}
</script>
