<template>
  <div v-if="filteredTenants.length === 0 && !globalLoading" style="margin-top: 60px">
    <a-empty description="无租户数据" />
  </div>

  <InstanceTenantGroupedList
    v-else-if="hasGroups"
    :groups="groupedTenants"
    :view-mode="tenantViewMode"
    :active-group-keys="activeGroupKeys"
    :active-l2-keys="activeL2Keys"
    :active-tenant-id="activeTenantId"
    :is-mobile="isMobile"
    :group-tenant-count="groupTenantCount"
    :is-tenant-loading="isTenantLoading"
    @select-tenant="$emit('select-tenant', $event)"
    @open-vcn="$emit('open-vcn', $event)"
    @open-storage="$emit('open-storage', $event)"
    @open-quick-task="$emit('open-quick-task', $event)"
    @collapse-change="$emit('collapse-change', $event)"
    @l2-collapse-change="$emit('l2-collapse-change', $event)"
  >
    <template #card-list="{ tenants }">
      <div class="tenant-grid">
        <div
          v-for="td in tenants"
          :key="td.tenant.id"
          class="tenant-card"
          :data-tenant-id="td.tenant.id"
          :class="{
            'tenant-card-active': activeTenantId === td.tenant.id,
            'tenant-card-floating-source': isFloatingTenantSource(td.tenant),
          }"
        >
          <div class="tc-header"><i class="ri-cloud-line tc-icon"></i><div class="tc-info"><div class="tc-name">{{ td.tenant.username }}</div><div class="tc-region">{{ td.tenant.ociRegion }}</div></div></div>
          <div class="tc-tags"><a-tag v-if="td.tenant.planType" :color="tenantPlanTagColor(td.tenant.planType)" :style="tenantPlanTagStyle(td.tenant.planType)" size="small">{{ formatTenantPlanType(td.tenant.planType) }}</a-tag><a-tag v-if="td.tenant.tenantName" size="small" color="blue">{{ td.tenant.tenantName }}</a-tag></div>
          <div class="tc-actions"><a-button type="primary" block @click="$emit('select-tenant', td)" :loading="isTenantLoading(td)"><i class="ri-server-line" style="margin-right:6px"></i>实例管理</a-button><a-button block @click="$emit('open-vcn', td.tenant)"><i class="ri-share-line" style="margin-right:6px"></i>虚拟云网络</a-button><a-button block @click="$emit('open-storage', td.tenant)"><i class="ri-database-2-line" style="margin-right:6px"></i>存储</a-button><a-button block @click="$emit('open-quick-task', td.tenant)"><i class="ri-play-circle-line" style="margin-right:6px"></i>快捷开机</a-button></div>
        </div>
      </div>
    </template>
  </InstanceTenantGroupedList>

  <template v-else>
    <div v-if="tenantViewMode === 'card'">
      <VirtualTenantGridList
        v-if="!isMobile && shouldVirtualizeTenantCards(filteredTenants.length)"
        :items="filteredTenants"
        :item-key="tenantDataKey"
        :reset-key="tenantVirtualResetKey"
        :max-height="tenantVirtualListMaxHeight"
      >
        <template #item="{ item: td }">
          <div
            class="tenant-card"
            :data-tenant-id="td.tenant.id"
            :class="{ 'tenant-card-active': activeTenantId === td.tenant.id, 'tenant-card-floating-source': isFloatingTenantSource(td.tenant) }"
          >
            <div class="tc-header">
              <i class="ri-cloud-line tc-icon"></i>
              <div class="tc-info">
                <div class="tc-name">{{ td.tenant.username }}</div>
                <div class="tc-region">{{ td.tenant.ociRegion }}</div>
              </div>
            </div>
            <div class="tc-tags">
              <a-tag v-if="td.tenant.planType" :color="tenantPlanTagColor(td.tenant.planType)" :style="tenantPlanTagStyle(td.tenant.planType)" size="small">{{ formatTenantPlanType(td.tenant.planType) }}</a-tag>
              <a-tag v-if="td.tenant.tenantName" size="small" color="blue">{{ td.tenant.tenantName }}</a-tag>
            </div>
            <div class="tc-actions">
            <a-button type="primary" block @click="$emit('select-tenant', td)" :loading="isTenantLoading(td)">
                <i class="ri-server-line" style="margin-right: 6px"></i>实例管理
              </a-button>
              <a-button block @click="$emit('open-vcn', td.tenant)">
                <i class="ri-share-line" style="margin-right: 6px"></i>虚拟云网络
              </a-button>
              <a-button block @click="$emit('open-storage', td.tenant)">
                <i class="ri-database-2-line" style="margin-right: 6px"></i>存储
              </a-button>
              <a-button block @click="$emit('open-quick-task', td.tenant)">
                <i class="ri-play-circle-line" style="margin-right: 6px"></i>快捷开机
              </a-button>
            </div>
          </div>
        </template>
      </VirtualTenantGridList>
      <div v-else class="tenant-grid">
        <div v-for="td in filteredTenants" :key="td.tenant.id"
          class="tenant-card" :data-tenant-id="td.tenant.id" :class="{ 'tenant-card-active': activeTenantId === td.tenant.id, 'tenant-card-floating-source': isFloatingTenantSource(td.tenant) }">
          <div class="tc-header">
            <i class="ri-cloud-line tc-icon"></i>
            <div class="tc-info">
              <div class="tc-name">{{ td.tenant.username }}</div>
              <div class="tc-region">{{ td.tenant.ociRegion }}</div>
            </div>
          </div>
          <div class="tc-tags">
            <a-tag v-if="td.tenant.planType" :color="tenantPlanTagColor(td.tenant.planType)" :style="tenantPlanTagStyle(td.tenant.planType)" size="small">{{ formatTenantPlanType(td.tenant.planType) }}</a-tag>
            <a-tag v-if="td.tenant.tenantName" size="small" color="blue">{{ td.tenant.tenantName }}</a-tag>
          </div>
          <div class="tc-actions">
              <a-button type="primary" block @click="$emit('select-tenant', td)" :loading="isTenantLoading(td)">
              <i class="ri-server-line" style="margin-right: 6px"></i>实例管理
            </a-button>
            <a-button block @click="$emit('open-vcn', td.tenant)">
              <i class="ri-share-line" style="margin-right: 6px"></i>虚拟云网络
            </a-button>
            <a-button block @click="$emit('open-storage', td.tenant)">
              <i class="ri-database-2-line" style="margin-right: 6px"></i>存储
            </a-button>
            <a-button block @click="$emit('open-quick-task', td.tenant)">
              <i class="ri-play-circle-line" style="margin-right: 6px"></i>快捷开机
            </a-button>
          </div>
        </div>
      </div>
    </div>

    <div v-else class="tenant-table-wrap">
      <a-table :data-source="filteredTenants" :row-key="(r: any) => r.tenant.id" size="middle" :pagination="false"
        :row-class-name="(record: any) => record.tenant.id === activeTenantId ? 'tenant-row-active' : ''">
        <a-table-column title="名称" data-index="tenant.username" key="username" :ellipsis="true">
          <template #default="{ record }">
            <div style="display: flex; align-items: center; gap: 8px; min-width: 0">
              <i class="ri-cloud-line" style="font-size: 18px; color: var(--primary); flex-shrink: 0"></i>
              <span style="font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{{ record.tenant.username }}</span>
            </div>
          </template>
        </a-table-column>
        <a-table-column title="租户名" key="tenantName" :width="200" :ellipsis="true">
          <template #default="{ record }">
            <a-tooltip v-if="record.tenant.tenantName" :title="record.tenant.tenantName">
              <span>{{ record.tenant.tenantName }}</span>
            </a-tooltip>
            <span v-else style="color: var(--text-sub)">—</span>
          </template>
        </a-table-column>
        <a-table-column title="区域" key="region" :width="150" align="left">
          <template #default="{ record }">
            <a-tag>{{ record.tenant.ociRegion }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="类型" key="planType" :width="90" align="left">
          <template #default="{ record }">
            <a-tag v-if="record.tenant.planType" :color="tenantPlanTagColor(record.tenant.planType)" :style="tenantPlanTagStyle(record.tenant.planType)">{{ formatTenantPlanType(record.tenant.planType) }}</a-tag>
            <span v-else style="color: var(--text-sub)">—</span>
          </template>
        </a-table-column>
        <a-table-column title="操作" key="action" :width="260" align="right">
          <template #default="{ record }">
            <a-space>
              <a-button type="primary" size="small" @click="$emit('select-tenant', record)" :loading="isTenantLoading(record)">实例管理</a-button>
              <a-button size="small" @click="$emit('open-vcn', record.tenant)">VCN</a-button>
              <a-button size="small" @click="$emit('open-storage', record.tenant)">存储</a-button>
              <a-button size="small" @click="$emit('open-quick-task', record.tenant)">快捷开机</a-button>
            </a-space>
          </template>
        </a-table-column>
      </a-table>
    </div>
  </template>
</template>

<script setup lang="ts">
import { DownOutlined } from '@ant-design/icons-vue'
import VirtualTenantGridList from '../tenant/VirtualTenantGridList.vue'
import InstanceTenantGroupedList from './InstanceTenantGroupedList.vue'

defineOptions({ name: 'InstanceTenantEntryPanel' })

interface TenantData {
  tenant: any
  instances: any[]
  loading: boolean
  collapsed: boolean
}

interface GroupNode {
  label: string
  key: string
  children?: GroupNode[]
  tenants: TenantData[]
}

const props = defineProps<{
  filteredTenants: TenantData[]
  groupedTenants: GroupNode[]
  hasGroups: boolean
  tenantViewMode: 'card' | 'table'
  activeTenantId: string
  isMobile: boolean
  globalLoading: boolean
  activeGroupKeys: string[]
  activeL2Keys: string[]
  tenantVirtualListMaxHeight: number
  tenantVirtualResetKey: string
  tenantDataKey: (item: unknown, index: number) => string
  shouldVirtualizeTenantCards: (count: number) => boolean
  isFloatingTenantSource: (tenant: any) => boolean
  tenantPlanTagStyle: (plan: unknown) => Record<string, string> | undefined
  tenantPlanTagColor: (plan: unknown) => string
  formatTenantPlanType: (plan: unknown) => string
  groupTenantCount: (group: GroupNode) => number
}>()

function isTenantLoading(td: TenantData) {
  return td.loading && String(td.tenant?.id || '') === String(props.activeTenantId || '')
}

defineEmits<{
  (e: 'select-tenant', tenantData: TenantData): void
  (e: 'open-vcn', tenant: any): void
  (e: 'open-storage', tenant: any): void
  (e: 'open-quick-task', tenant: any): void
  (e: 'collapse-change', keys: string | string[]): void
  (e: 'l2-collapse-change', keys: string | string[]): void
}>()
</script>

<style scoped>
.tenant-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}
.tenant-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 20px;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: var(--shadow-card);
  transition: all 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  height: 100%;
  content-visibility: auto;
  contain-intrinsic-size: 260px;
}
.tenant-card::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 3px;
  background: linear-gradient(90deg, var(--primary), #8b5cf6);
  transform: scaleX(0);
  transition: transform 0.3s;
  transform-origin: left;
}
.tenant-card:hover::before { transform: scaleX(1); }
.tenant-card:hover {
  border-color: rgba(129, 140, 248, 0.5);
  transform: translateY(-3px);
  box-shadow: 0 8px 24px -6px rgba(99, 102, 241, 0.25);
}
.tenant-card-active {
  border-color: var(--primary) !important;
  box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.2), var(--shadow-card);
}
.tenant-card-active::before { transform: scaleX(1); }
.tenant-card-floating-source {
  background: var(--tenant-floating-placeholder-bg) !important;
  border-color: var(--tenant-floating-placeholder-border) !important;
  box-shadow: inset 0 0 0 1px var(--tenant-floating-placeholder-border) !important;
  transform: none !important;
  pointer-events: none;
}
.tenant-card-floating-source::before {
  opacity: 0;
  transform: scaleX(0) !important;
}
.tenant-card-floating-source > * {
  opacity: 0;
}
.tc-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.tc-icon {
  font-size: 28px;
  color: var(--primary);
  flex-shrink: 0;
}
.tc-info { min-width: 0; flex: 1; }
.tc-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-main);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tc-region {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 2px;
}
.tc-tags {
  display: flex;
  gap: 6px;
  margin-bottom: 14px;
  flex-wrap: wrap;
  min-height: 22px;
}
.tc-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: auto;
}
.tenant-table-wrap {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 12px;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: var(--shadow-card);
  margin-bottom: 24px;
  overflow-x: auto;
}
.tenant-table-wrap :deep(.tenant-row-active) {
  background: rgba(99, 102, 241, 0.08) !important;
}

@media (max-width: 768px) {
  .tenant-grid { grid-template-columns: 1fr 1fr; gap: 10px; }
  .tenant-card { padding: 14px; border-radius: 12px; }
  .tc-icon { font-size: 22px; }
  .tc-name { font-size: 13px; }
}
</style>
