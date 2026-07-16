<template>
  <div v-if="filteredTenants.length === 0 && !globalLoading" style="margin-top: 60px">
    <a-empty description="无租户数据" />
  </div>

  <template v-else-if="hasGroups">
    <a-collapse
      :active-key="activeGroupKeys"
      class="group-collapse"
      :class="{ 'group-collapse-list': tenantViewMode === 'table' }"
      @change="$emit('collapse-change', $event)"
    >
      <a-collapse-panel v-for="g1 in groupedTenants" :key="g1.key" :collapsible="groupTenantCount(g1) === 0 ? 'disabled' : undefined">
        <template #header>
          <span class="group-header-label">{{ g1.label }}</span>
          <a-badge :count="groupTenantCount(g1)" :show-zero="true" class="oci-group-count-badge" style="margin-left: 8px" />
        </template>
        <template v-if="isGroupPanelOpen(g1.key)">
          <template v-if="g1.children && g1.children.length > 0">
            <a-collapse
              :active-key="activeL2Keys"
              class="group-collapse-l2"
              :class="{ 'group-collapse-l2-list': tenantViewMode === 'table' }"
              @change="$emit('l2-collapse-change', $event)"
            >
              <a-collapse-panel v-for="l2 in g1.children" :key="l2.key" :collapsible="l2.tenants.length === 0 ? 'disabled' : undefined">
                <template #header>
                  <span class="group-header-label">{{ l2.label }}</span>
                  <a-badge :count="l2.tenants.length" :show-zero="true" class="oci-group-count-badge" style="margin-left: 8px" />
                </template>
                <template v-if="isL2PanelOpen(l2.key)">
                  <div v-if="tenantViewMode === 'card'" class="tenant-grid">
                    <template v-for="td in l2.tenants" :key="td.tenant.id">
                      <div class="tenant-card" :data-tenant-id="td.tenant.id" :class="{ 'tenant-card-active': activeTenantId === td.tenant.id, 'tenant-card-floating-source': isFloatingTenantSource(td.tenant) }">
                        <div class="tc-header"><i class="ri-cloud-line tc-icon"></i><div class="tc-info"><div class="tc-name">{{ td.tenant.username }}</div><div class="tc-region">{{ td.tenant.ociRegion }}</div></div></div>
                        <div class="tc-tags"><a-tag v-if="td.tenant.planType" :color="tenantPlanTagColor(td.tenant.planType)" :style="tenantPlanTagStyle(td.tenant.planType)" size="small">{{ formatTenantPlanType(td.tenant.planType) }}</a-tag><a-tag v-if="td.tenant.tenantName" size="small" color="blue">{{ td.tenant.tenantName }}</a-tag></div>
                        <div class="tc-actions"><a-button type="primary" block @click="$emit('select-tenant', td)" :loading="isTenantLoading(td)"><i class="ri-server-line" style="margin-right:6px"></i>实例管理</a-button><a-button block @click="$emit('open-vcn', td.tenant)"><i class="ri-share-line" style="margin-right:6px"></i>虚拟云网络</a-button><a-button block @click="$emit('open-storage', td.tenant)"><i class="ri-database-2-line" style="margin-right:6px"></i>存储</a-button><a-button block @click="$emit('open-quick-task', td.tenant)"><i class="ri-play-circle-line" style="margin-right:6px"></i>快捷开机</a-button></div>
                      </div>
                    </template>
                  </div>
                  <div v-else>
                    <div v-for="td in l2.tenants" :key="td.tenant.id" class="group-table-row" :class="{ 'tenant-row-active': td.tenant.id === activeTenantId }">
                      <div class="gtr-main">
                        <div class="gtr-ident">
                          <span class="gtr-name">{{ td.tenant.username }}</span>
                          <span v-if="td.tenant.tenantName" class="gtr-tenantnm">{{ td.tenant.tenantName }}</span>
                        </div>
                        <span class="gtr-region"><a-tag>{{ td.tenant.ociRegion }}</a-tag></span>
                      </div>
                      <a-space v-if="!isMobile" class="gtr-actions" size="small" wrap>
                        <a-button type="primary" size="small" @click="$emit('select-tenant', td)" :loading="isTenantLoading(td)">实例管理</a-button>
                        <a-button size="small" @click="$emit('open-vcn', td.tenant)">VCN</a-button>
                        <a-button size="small" @click="$emit('open-storage', td.tenant)">存储</a-button>
                        <a-button size="small" @click="$emit('open-quick-task', td.tenant)">快捷开机</a-button>
                      </a-space>
                      <div v-else class="gtr-actions gtr-actions-mobile">
                        <a-button type="primary" size="small" @click="$emit('select-tenant', td)" :loading="isTenantLoading(td)">实例管理</a-button>
                        <a-dropdown placement="bottomRight" :trigger="['click']">
                          <a-button size="small">更多 <DownOutlined /></a-button>
                          <template #overlay>
                            <a-menu>
                              <a-menu-item key="vcn" @click="$emit('open-vcn', td.tenant)">VCN</a-menu-item>
                              <a-menu-item key="storage" @click="$emit('open-storage', td.tenant)">存储</a-menu-item>
                              <a-menu-item key="quick" @click="$emit('open-quick-task', td.tenant)">快捷开机</a-menu-item>
                            </a-menu>
                          </template>
                        </a-dropdown>
                      </div>
                    </div>
                  </div>
                </template>
              </a-collapse-panel>
            </a-collapse>
            <div v-if="g1.tenants.length > 0" class="group-section">
              <div>
                <div v-if="tenantViewMode === 'card'" class="tenant-grid">
                  <template v-for="td in g1.tenants" :key="td.tenant.id">
                    <div class="tenant-card" :data-tenant-id="td.tenant.id" :class="{ 'tenant-card-active': activeTenantId === td.tenant.id, 'tenant-card-floating-source': isFloatingTenantSource(td.tenant) }">
                      <div class="tc-header"><i class="ri-cloud-line tc-icon"></i><div class="tc-info"><div class="tc-name">{{ td.tenant.username }}</div><div class="tc-region">{{ td.tenant.ociRegion }}</div></div></div>
                      <div class="tc-tags"><a-tag v-if="td.tenant.planType" :color="tenantPlanTagColor(td.tenant.planType)" :style="tenantPlanTagStyle(td.tenant.planType)" size="small">{{ formatTenantPlanType(td.tenant.planType) }}</a-tag><a-tag v-if="td.tenant.tenantName" size="small" color="blue">{{ td.tenant.tenantName }}</a-tag></div>
                      <div class="tc-actions"><a-button type="primary" block @click="$emit('select-tenant', td)" :loading="isTenantLoading(td)"><i class="ri-server-line" style="margin-right:6px"></i>实例管理</a-button><a-button block @click="$emit('open-vcn', td.tenant)"><i class="ri-share-line" style="margin-right:6px"></i>虚拟云网络</a-button><a-button block @click="$emit('open-storage', td.tenant)"><i class="ri-database-2-line" style="margin-right:6px"></i>存储</a-button><a-button block @click="$emit('open-quick-task', td.tenant)"><i class="ri-play-circle-line" style="margin-right:6px"></i>快捷开机</a-button></div>
                    </div>
                  </template>
                </div>
                <div v-else>
                  <div v-for="td in g1.tenants" :key="td.tenant.id" class="group-table-row" :class="{ 'tenant-row-active': td.tenant.id === activeTenantId }">
                    <div class="gtr-main">
                      <div class="gtr-ident">
                        <span class="gtr-name">{{ td.tenant.username }}</span>
                        <span v-if="td.tenant.tenantName" class="gtr-tenantnm">{{ td.tenant.tenantName }}</span>
                      </div>
                      <span class="gtr-region"><a-tag>{{ td.tenant.ociRegion }}</a-tag></span>
                    </div>
                    <a-space v-if="!isMobile" class="gtr-actions" size="small" wrap>
                      <a-button type="primary" size="small" @click="$emit('select-tenant', td)" :loading="isTenantLoading(td)">实例管理</a-button>
                      <a-button size="small" @click="$emit('open-vcn', td.tenant)">VCN</a-button>
                      <a-button size="small" @click="$emit('open-storage', td.tenant)">存储</a-button>
                      <a-button size="small" @click="$emit('open-quick-task', td.tenant)">快捷开机</a-button>
                    </a-space>
                    <div v-else class="gtr-actions gtr-actions-mobile">
                      <a-button type="primary" size="small" @click="$emit('select-tenant', td)" :loading="isTenantLoading(td)">实例管理</a-button>
                      <a-dropdown placement="bottomRight" :trigger="['click']">
                        <a-button size="small">更多 <DownOutlined /></a-button>
                        <template #overlay>
                          <a-menu>
                            <a-menu-item key="vcn" @click="$emit('open-vcn', td.tenant)">VCN</a-menu-item>
                            <a-menu-item key="storage" @click="$emit('open-storage', td.tenant)">存储</a-menu-item>
                            <a-menu-item key="quick" @click="$emit('open-quick-task', td.tenant)">快捷开机</a-menu-item>
                          </a-menu>
                        </template>
                      </a-dropdown>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </template>
          <template v-else>
            <div v-if="tenantViewMode === 'card'" class="tenant-grid">
              <template v-for="td in g1.tenants" :key="td.tenant.id">
                <div class="tenant-card" :data-tenant-id="td.tenant.id" :class="{ 'tenant-card-active': activeTenantId === td.tenant.id, 'tenant-card-floating-source': isFloatingTenantSource(td.tenant) }">
                  <div class="tc-header"><i class="ri-cloud-line tc-icon"></i><div class="tc-info"><div class="tc-name">{{ td.tenant.username }}</div><div class="tc-region">{{ td.tenant.ociRegion }}</div></div></div>
                  <div class="tc-tags"><a-tag v-if="td.tenant.planType" :color="tenantPlanTagColor(td.tenant.planType)" :style="tenantPlanTagStyle(td.tenant.planType)" size="small">{{ formatTenantPlanType(td.tenant.planType) }}</a-tag><a-tag v-if="td.tenant.tenantName" size="small" color="blue">{{ td.tenant.tenantName }}</a-tag></div>
                  <div class="tc-actions"><a-button type="primary" block @click="$emit('select-tenant', td)" :loading="isTenantLoading(td)"><i class="ri-server-line" style="margin-right:6px"></i>实例管理</a-button><a-button block @click="$emit('open-vcn', td.tenant)"><i class="ri-share-line" style="margin-right:6px"></i>虚拟云网络</a-button><a-button block @click="$emit('open-storage', td.tenant)"><i class="ri-database-2-line" style="margin-right:6px"></i>存储</a-button><a-button block @click="$emit('open-quick-task', td.tenant)"><i class="ri-play-circle-line" style="margin-right:6px"></i>快捷开机</a-button></div>
                </div>
              </template>
            </div>
            <div v-else>
              <div v-for="td in g1.tenants" :key="td.tenant.id" class="group-table-row" :class="{ 'tenant-row-active': td.tenant.id === activeTenantId }">
                <div class="gtr-main">
                  <div class="gtr-ident">
                    <span class="gtr-name">{{ td.tenant.username }}</span>
                    <span v-if="td.tenant.tenantName" class="gtr-tenantnm">{{ td.tenant.tenantName }}</span>
                  </div>
                  <span class="gtr-region"><a-tag>{{ td.tenant.ociRegion }}</a-tag></span>
                </div>
                <a-space v-if="!isMobile" class="gtr-actions" size="small" wrap>
                  <a-button type="primary" size="small" @click="$emit('select-tenant', td)" :loading="isTenantLoading(td)">实例管理</a-button>
                  <a-button size="small" @click="$emit('open-vcn', td.tenant)">VCN</a-button>
                  <a-button size="small" @click="$emit('open-storage', td.tenant)">存储</a-button>
                  <a-button size="small" @click="$emit('open-quick-task', td.tenant)">快捷开机</a-button>
                </a-space>
                <div v-else class="gtr-actions gtr-actions-mobile">
                  <a-button type="primary" size="small" @click="$emit('select-tenant', td)" :loading="isTenantLoading(td)">实例管理</a-button>
                  <a-dropdown placement="bottomRight" :trigger="['click']">
                    <a-button size="small">更多 <DownOutlined /></a-button>
                    <template #overlay>
                      <a-menu>
                        <a-menu-item key="vcn" @click="$emit('open-vcn', td.tenant)">VCN</a-menu-item>
                        <a-menu-item key="storage" @click="$emit('open-storage', td.tenant)">存储</a-menu-item>
                        <a-menu-item key="quick" @click="$emit('open-quick-task', td.tenant)">快捷开机</a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                </div>
              </div>
            </div>
          </template>
        </template>
      </a-collapse-panel>
    </a-collapse>
  </template>

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
  isGroupPanelOpen: (key: string) => boolean
  isL2PanelOpen: (key: string) => boolean
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
.group-collapse { margin-bottom: 16px; }
.group-collapse :deep(.ant-collapse-header) { font-weight: 600; font-size: 14px; }
.group-collapse-list {
  border: 0;
  background: transparent;
}
.group-collapse-list :deep(> .ant-collapse-item) {
  margin-bottom: 12px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 14px;
  background: color-mix(in srgb, var(--bg-card) 88%, transparent);
  box-shadow: 0 8px 24px -22px color-mix(in srgb, var(--text-main) 35%, transparent);
  transition: border-color .18s ease, box-shadow .18s ease, background-color .18s ease;
}
.group-collapse-list :deep(> .ant-collapse-item:hover) {
  border-color: color-mix(in srgb, var(--primary) 34%, var(--border));
  box-shadow: 0 12px 28px -22px color-mix(in srgb, var(--primary) 42%, transparent);
}
.group-collapse-list :deep(> .ant-collapse-item > .ant-collapse-header) {
  min-height: 56px;
  padding: 14px 16px !important;
  align-items: center !important;
}
.group-collapse-list :deep(> .ant-collapse-item > .ant-collapse-header .ant-collapse-expand-icon) {
  padding-inline-end: 8px !important;
  color: var(--text-sub);
}
.group-collapse-list :deep(> .ant-collapse-item > .ant-collapse-content) {
  border-top: 1px solid color-mix(in srgb, var(--border) 76%, transparent);
  background: transparent;
}
.group-collapse-list :deep(> .ant-collapse-item > .ant-collapse-content > .ant-collapse-content-box) {
  padding: 14px 16px 16px !important;
}
.group-collapse-list > :deep(.ant-collapse-item > .ant-collapse-header .group-header-label)::before {
  content: '';
  width: 9px;
  height: 9px;
  margin-right: 9px;
  border-radius: 50%;
  display: inline-block;
  background: var(--primary);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--primary) 12%, transparent);
  vertical-align: 1px;
}
.group-collapse-l2 {
  margin-top: 10px;
  background: transparent;
}
.group-collapse-l2 :deep(.ant-collapse-item) {
  background: color-mix(in srgb, var(--panel-bg) 78%, transparent);
  border: 1px solid var(--border);
  border-radius: 12px;
  overflow: hidden;
}
.group-collapse-l2 :deep(.ant-collapse-item + .ant-collapse-item) { margin-top: 8px; }
.group-collapse-l2 :deep(.ant-collapse-header) { font-weight: 500; font-size: 13px; padding-left: 12px !important; }
.group-collapse-l2 :deep(.ant-collapse-content-box) { padding-top: 12px !important; }
.group-collapse-l2-list {
  margin-top: 0;
  border: 0;
}
.group-collapse-l2-list :deep(.ant-collapse-item) {
  border-radius: 12px;
  background: color-mix(in srgb, var(--panel-bg) 80%, transparent);
}
.group-collapse-l2-list :deep(.ant-collapse-header) {
  min-height: 48px;
  padding: 12px 14px !important;
  align-items: center !important;
}
.group-collapse-l2-list :deep(.ant-collapse-content) {
  border-top: 1px solid color-mix(in srgb, var(--border) 72%, transparent);
  background: transparent;
}
.group-collapse-l2-list :deep(.ant-collapse-content-box) {
  padding: 4px 12px 8px !important;
}
.group-header-label { vertical-align: middle; }
.group-collapse-l2 .tenant-grid { margin-bottom: 18px; }
.group-collapse-l2 + .group-section { margin-top: 12px; }
.group-section { margin-bottom: 8px; }
.group-table-row {
  min-height: 62px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  column-gap: 12px;
  align-items: center;
  content-visibility: auto;
  contain-intrinsic-size: 64px;
  transition: background-color .16s ease, box-shadow .16s ease;
}
.group-table-row:hover {
  background: color-mix(in srgb, var(--primary-light) 58%, transparent);
  box-shadow: inset 3px 0 0 color-mix(in srgb, var(--primary) 72%, transparent);
}
.group-table-row.tenant-row-active {
  background: color-mix(in srgb, var(--primary-light) 82%, transparent);
  box-shadow: inset 3px 0 0 var(--primary);
}
.group-table-row:last-child { border-bottom: none; }
.gtr-main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
}
.gtr-ident {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}
.gtr-name {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.gtr-tenantnm {
  font-size: 12px;
  color: var(--text-sub);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gtr-region {
  flex-shrink: 0;
}
.gtr-region :deep(.ant-tag) {
  margin-inline-end: 0;
  border-color: color-mix(in srgb, var(--border) 82%, transparent);
  border-radius: 999px;
  background: color-mix(in srgb, var(--input-bg) 82%, transparent);
  color: var(--text-sub);
  font-size: 11px;
}
.gtr-actions {
  justify-self: end;
}
.gtr-actions :deep(.ant-btn) {
  min-height: 30px;
  border-radius: 9px;
  font-weight: 500;
  box-shadow: none;
  transition: border-color .16s ease, background-color .16s ease, color .16s ease, transform .12s ease;
}
.gtr-actions :deep(.ant-btn:not(.ant-btn-primary)) {
  border-color: color-mix(in srgb, var(--border) 88%, transparent);
  background: color-mix(in srgb, var(--bg-card) 68%, transparent);
  color: var(--text-main);
}
.gtr-actions :deep(.ant-btn:not(.ant-btn-primary):hover) {
  border-color: color-mix(in srgb, var(--primary) 45%, var(--border));
  background: var(--primary-light);
  color: var(--primary);
}
.gtr-actions :deep(.ant-btn-primary) {
  box-shadow: 0 5px 12px -8px color-mix(in srgb, var(--primary) 68%, transparent);
}
.gtr-actions :deep(.ant-btn:active) {
  transform: translateY(1px);
}
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
  .group-table-row {
    grid-template-columns: 1fr;
    align-items: stretch;
    row-gap: 10px;
    padding: 12px;
  }
  .gtr-main {
    flex-wrap: wrap;
  }
  .gtr-name {
    white-space: normal;
    word-break: break-word;
  }
  .gtr-tenantnm {
    white-space: normal;
    word-break: break-word;
  }
  .gtr-actions-mobile {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
  }
  .gtr-actions-mobile .ant-btn-primary {
    flex: 1;
    min-width: 0;
  }
}
</style>
