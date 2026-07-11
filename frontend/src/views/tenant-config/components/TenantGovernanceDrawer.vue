<template>
  <a-drawer
    v-model:open="openModel"
    :title="`管理 — ${tenant?.username || ''}`"
    :width="isMobile ? '100%' : 960"
    :mask-closable="false"
    :keyboard="false"
    destroy-on-close
  >
    <a-tabs v-model:activeKey="activeTab">
      <a-tab-pane key="domains" tab="域管理">
        <TenantDomainAdminPanel
          v-if="tenant?.id"
          :tenant-id="String(tenant.id)"
          :domains="domains"
          @refresh="loadDomains"
        />
      </a-tab-pane>
      <a-tab-pane key="quotas" tab="限额策略">
        <TenantQuotaPolicyPanel />
      </a-tab-pane>
      <a-tab-pane key="organization" tab="组织管理">
        <TenantOrganizationPanel />
      </a-tab-pane>
    </a-tabs>
  </a-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { listIdentityDomains } from '../../../api/user'
import { defineAppAsyncComponent } from '../../../utils/asyncComponent'

const TenantDomainAdminPanel = defineAppAsyncComponent(() => import('./TenantDomainAdminPanel.vue'), { loading: 'none' })
const TenantQuotaPolicyPanel = defineAppAsyncComponent(() => import('./TenantQuotaPolicyPanel.vue'), { loading: 'none' })
const TenantOrganizationPanel = defineAppAsyncComponent(() => import('./TenantOrganizationPanel.vue'), { loading: 'none' })
const props = defineProps<{ open: boolean; tenant: any | null }>()
const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>()
const activeTab = ref('domains')
const domains = ref<any[]>([])
const isMobile = computed(() => window.innerWidth <= 768)
const openModel = computed({ get: () => props.open, set: value => emit('update:open', value) })

async function loadDomains() {
  if (!props.tenant?.id) return
  try {
    const res = await listIdentityDomains({ tenantId: String(props.tenant.id) })
    domains.value = (res.data || []).map((item: any) => ({ ...item, domainId: item.domainId || item.id }))
  } catch (error: any) {
    domains.value = []
    message.error(error?.message || '读取 Identity Domain 失败')
  }
}

watch(() => props.open, value => { if (value) { activeTab.value = 'domains'; loadDomains() } })
</script>
