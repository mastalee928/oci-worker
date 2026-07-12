<template>
  <section class="governance-shell">
    <nav class="governance-nav" aria-label="租户治理模块">
      <button
        v-for="item in navItems"
        :key="item.key"
        type="button"
        :class="['governance-nav-item', { active: activeSection === item.key }]"
        @click="activeSection = item.key"
      >
        <i :class="item.icon" aria-hidden="true"></i>
        <span class="nav-copy"><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
      </button>
    </nav>
    <div class="worker-label">OCIWorker 保护能力</div>
    <button type="button" :class="['worker-nav-item', { active: activeSection === 'traffic' }]" @click="activeSection = 'traffic'">
      <i class="ri-exchange-2-line" aria-hidden="true"></i>
      <span class="nav-copy"><strong>流量保护</strong><small>监控流量并自动处置</small></span>
      <span class="worker-badge">OCIWorker</span>
    </button>

    <div class="governance-content">
      <TenantDomainAdminPanel
        v-if="activeSection === 'domains' && tenant?.id"
        :tenant-id="String(tenant.id)"
        :default-home-region="String(tenant.ociRegion || '')"
        :domains="domains"
        @refresh="loadDomains"
      />
      <TenantQuotaPolicyPanel v-else-if="activeSection === 'quotas' && tenant?.id" :tenant-id="String(tenant.id)" />
      <TenantOrganizationPanel v-else-if="activeSection === 'organization' && tenant?.id" :tenant-id="String(tenant.id)" />
      <TenantTrafficProtectionPanel v-else-if="activeSection === 'traffic' && tenant?.id" :tenant-id="String(tenant.id)" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { listIdentityDomains } from '../../../api/user'
import { defineAppAsyncComponent } from '../../../utils/asyncComponent'

const TenantDomainAdminPanel = defineAppAsyncComponent(() => import('./TenantDomainAdminPanel.vue'), { loadingText: '正在载入域管理', loadingDescription: '读取当前租户身份域' })
const TenantQuotaPolicyPanel = defineAppAsyncComponent(() => import('./TenantQuotaPolicyPanel.vue'), { loadingText: '正在载入限额策略', loadingDescription: '读取 Oracle 配额保护状态' })
const TenantOrganizationPanel = defineAppAsyncComponent(() => import('./TenantOrganizationPanel.vue'), { loadingText: '正在载入组织管理', loadingDescription: '读取当前租户组织信息' })
const TenantTrafficProtectionPanel = defineAppAsyncComponent(() => import('./TenantTrafficProtectionPanel.vue'), { loadingText: '正在载入流量保护', loadingDescription: '读取月度估算流量与保护状态' })
const props = defineProps<{ tenant: any | null }>()
const activeSection = ref('domains')
const domains = ref<any[]>([])
const navItems = [
  { key: 'domains', label: '域管理', description: '创建、编辑与管理身份域', icon: 'ri-shield-user-line' },
  { key: 'quotas', label: '限额策略', description: '查看与管理租户配额策略', icon: 'ri-speed-up-line' },
  { key: 'organization', label: '组织管理', description: '创建子租户与邀请租户', icon: 'ri-node-tree' },
]

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

watch(() => props.tenant?.id, () => { activeSection.value = 'domains'; void loadDomains() }, { immediate: true })
</script>

<style scoped>
.governance-shell { min-width: 0; }
.governance-nav { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 14px; }
.governance-nav-item { display: flex; min-width: 0; align-items: center; gap: 11px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 10px; color: var(--text-sub); background: transparent; cursor: pointer; text-align: left; transition: border-color .16s ease, background .16s ease, color .16s ease, transform .16s ease; }
.governance-nav-item:hover { border-color: color-mix(in srgb, var(--primary) 45%, var(--border)); color: var(--text-main); }
.governance-nav-item:active { transform: translateY(1px); }
.governance-nav-item.active { border-color: color-mix(in srgb, var(--primary) 58%, var(--border)); color: var(--primary); background: color-mix(in srgb, var(--primary) 9%, transparent); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--primary) 14%, transparent); }
.governance-nav-item i { flex: 0 0 auto; font-size: 20px; }
.worker-label { margin: 0 0 6px 2px; padding-top: 10px; border-top: 1px solid var(--border); color: var(--text-sub); font-size: 10px; font-weight: 600; }
.worker-nav-item { display: flex; width: 100%; min-width: 0; align-items: center; gap: 11px; margin-bottom: 14px; padding: 12px 14px; border: 1px solid color-mix(in srgb, #34d399 26%, var(--border)); border-radius: 10px; color: var(--text-sub); background: color-mix(in srgb, #34d399 5%, transparent); cursor: pointer; text-align: left; }
.worker-nav-item.active { border-color: color-mix(in srgb, var(--primary) 58%, var(--border)); color: var(--primary); background: color-mix(in srgb, var(--primary) 9%, transparent); }
.worker-nav-item i { color: #34d399; font-size: 20px; }
.worker-badge { margin-left: auto; padding: 2px 7px; border: 1px solid color-mix(in srgb, #34d399 35%, var(--border)); border-radius: 999px; color: #34d399; font-size: 9px; }
.nav-copy { min-width: 0; }
.nav-copy strong, .nav-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nav-copy strong { color: inherit; font-size: 13px; }
.nav-copy small { margin-top: 2px; color: var(--text-sub); font-size: 11px; }
.governance-content { min-width: 0; padding: 15px; border: 1px solid var(--border); border-radius: 12px; background: color-mix(in srgb, var(--input-bg) 62%, transparent); }
@media (max-width: 768px) {
  .governance-nav { gap: 6px; }
  .governance-nav-item { justify-content: center; padding: 10px 5px; }
  .governance-nav-item i { font-size: 18px; }
  .nav-copy strong { font-size: 12px; text-align: center; }
  .nav-copy small { display: none; }
  .governance-content { padding: 10px; overflow: hidden; }
}
</style>
