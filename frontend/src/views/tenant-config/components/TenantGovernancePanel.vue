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
        <i class="module-icon" aria-hidden="true">{{ item.icon }}</i>
        <span class="nav-copy"><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
        <span class="origin">Oracle 官方</span>
      </button>
    </nav>
    <div class="worker-label">OCIWorker 保护能力</div>
    <button type="button" :class="['worker-nav-item', { active: activeSection === 'traffic' }]" @click="activeSection = 'traffic'">
      <i class="module-icon worker-icon" aria-hidden="true">TX</i>
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
  { key: 'domains', label: '域管理', description: '创建、编辑与管理身份域', icon: 'ID' },
  { key: 'quotas', label: '限额策略', description: 'Oracle官方资源配额', icon: 'QT' },
  { key: 'organization', label: '组织管理', description: '创建子租户与邀请租户', icon: 'ORG' },
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
.governance-nav { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; margin: 16px 0 14px; }
.governance-nav-item { display: flex; min-width: 0; align-items: center; gap: 11px; padding: 14px; border: 1px solid #263550; border-radius: 11px; color: #edf2ff; background: #0c1527; cursor: pointer; text-align: left; transition: border-color .16s ease, background .16s ease, transform .16s ease; }
.governance-nav-item:hover { border-color: color-mix(in srgb, var(--primary) 45%, var(--border)); color: var(--text-main); }
.governance-nav-item:active { transform: translateY(1px); }
.governance-nav-item.active { border-color: #756dff; color: #edf2ff; background: rgba(117,109,255,.1); box-shadow: none; }
.module-icon { display: grid; width: 29px; height: 29px; flex: 0 0 29px; place-items: center; border-radius: 8px; background: #182540; color: #aeb9d0; font-size: 10px; font-style: normal; font-weight: 800; }
.origin { margin-left: auto; padding: 3px 6px; border: 1px solid rgba(117,109,255,.2); border-radius: 999px; color: #687995; font-size: 8px; white-space: nowrap; }
.worker-label { margin: -2px 0 7px 2px; padding-top: 12px; border-top: 1px solid #1d2a42; color: #687995; font-size: 10px; font-weight: 700; }
.worker-nav-item { display: flex; width: 100%; min-width: 0; align-items: center; gap: 11px; margin-bottom: 14px; padding: 14px; border: 1px solid rgba(71,211,155,.22); border-radius: 11px; color: #edf2ff; background: linear-gradient(90deg,rgba(71,211,155,.055),rgba(12,21,39,.55)); cursor: pointer; text-align: left; }
.worker-nav-item.active { border-color: #756dff; color: #edf2ff; background: rgba(117,109,255,.1); }
.worker-icon { background: rgba(71,211,155,.1); color: #47d39b; }
.worker-badge { margin-left: auto; padding: 3px 6px; border: 1px solid rgba(71,211,155,.22); border-radius: 999px; color: #47d39b; font-size: 8px; }
.nav-copy { min-width: 0; }
.nav-copy strong, .nav-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nav-copy strong { color: inherit; font-size: 13px; }
.nav-copy small { margin-top: 3px; color: #97a5bf; font-size: 10px; }
.governance-content { min-width: 0; min-height: 260px; padding: 17px; border: 1px solid #263550; border-radius: 13px; background: rgba(12,21,39,.55); }
@media (max-width: 768px) {
  .governance-nav { grid-template-columns: 1fr; }
  .governance-nav-item { padding: 14px; }
  .nav-copy strong { text-align: left; }
  .nav-copy small { display: block; }
  .governance-content { padding: 17px; overflow: hidden; }
}
</style>
