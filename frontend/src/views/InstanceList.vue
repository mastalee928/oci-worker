<template>
  <div>
    <!-- 顶部工具栏 -->
    <div class="instance-toolbar">
      <div class="toolbar-left">
        <a-input-search
          v-model:value="searchKeyword"
          placeholder="搜索租户（名称/区域）"
          style="width: 300px"
          allow-clear
        />
      </div>
      <div class="toolbar-right">
        <a-segmented v-model:value="tenantViewMode" size="small" :options="[{ label: '卡片', value: 'card' }, { label: '列表', value: 'table' }]" />
        <a-button @click="loadAllTenants(true)" :loading="globalLoading">
          <template #icon><ReloadOutlined /></template>刷新
        </a-button>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-if="filteredTenants.length === 0 && !globalLoading" style="margin-top: 60px">
      <a-empty description="无租户数据" />
    </div>

    <!-- 分组折叠展示 -->
    <template v-else-if="hasGroups">
      <a-collapse v-model:activeKey="activeGroupKeys" @change="onCollapseChange" class="group-collapse">
        <a-collapse-panel v-for="g1 in groupedTenants" :key="g1.key" :collapsible="groupTenantCount(g1) === 0 ? 'disabled' : undefined">
          <template #header>
            <span class="group-header-label">{{ g1.label }}</span>
            <a-badge :count="groupTenantCount(g1)" :show-zero="true" class="oci-group-count-badge" style="margin-left: 8px" />
          </template>
          <template v-if="isGroupPanelOpen(g1.key)">
          <!-- 二级分组 -->
          <template v-if="g1.children && g1.children.length > 0">
            <a-collapse v-model:activeKey="activeL2Keys" @change="onL2CollapseChange" class="group-collapse-l2">
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
                      <div class="tc-actions"><a-button type="primary" block @click="selectTenant(td)" :loading="td.loading"><i class="ri-server-line" style="margin-right:6px"></i>实例管理</a-button><a-button block @click="openVcnPanel(td.tenant)"><i class="ri-share-line" style="margin-right:6px"></i>虚拟云网络</a-button><a-button block @click="openStoragePanel(td.tenant)"><i class="ri-database-2-line" style="margin-right:6px"></i>存储</a-button><a-button block @click="openQuickTask(td.tenant)"><i class="ri-play-circle-line" style="margin-right:6px"></i>快捷开机</a-button></div>
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
                      <a-button type="primary" size="small" @click="selectTenant(td)" :loading="td.loading">实例管理</a-button>
                      <a-button size="small" @click="openVcnPanel(td.tenant)">VCN</a-button>
                      <a-button size="small" @click="openStoragePanel(td.tenant)">存储</a-button>
                      <a-button size="small" @click="openQuickTask(td.tenant)">快捷开机</a-button>
                    </a-space>
                    <div v-else class="gtr-actions gtr-actions-mobile">
                      <a-button type="primary" size="small" @click="selectTenant(td)" :loading="td.loading">实例管理</a-button>
                      <a-dropdown placement="bottomRight" :trigger="['click']">
                        <a-button size="small">更多 <DownOutlined /></a-button>
                        <template #overlay>
                          <a-menu>
                            <a-menu-item key="vcn" @click="openVcnPanel(td.tenant)">VCN</a-menu-item>
                            <a-menu-item key="storage" @click="openStoragePanel(td.tenant)">存储</a-menu-item>
                            <a-menu-item key="quick" @click="openQuickTask(td.tenant)">快捷开机</a-menu-item>
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
              <component :is="tenantViewMode === 'card' ? 'div' : 'div'">
                <div v-if="tenantViewMode === 'card'" class="tenant-grid">
                  <template v-for="td in g1.tenants" :key="td.tenant.id">
                    <div class="tenant-card" :data-tenant-id="td.tenant.id" :class="{ 'tenant-card-active': activeTenantId === td.tenant.id, 'tenant-card-floating-source': isFloatingTenantSource(td.tenant) }">
                      <div class="tc-header"><i class="ri-cloud-line tc-icon"></i><div class="tc-info"><div class="tc-name">{{ td.tenant.username }}</div><div class="tc-region">{{ td.tenant.ociRegion }}</div></div></div>
                      <div class="tc-tags"><a-tag v-if="td.tenant.planType" :color="tenantPlanTagColor(td.tenant.planType)" :style="tenantPlanTagStyle(td.tenant.planType)" size="small">{{ formatTenantPlanType(td.tenant.planType) }}</a-tag><a-tag v-if="td.tenant.tenantName" size="small" color="blue">{{ td.tenant.tenantName }}</a-tag></div>
                      <div class="tc-actions"><a-button type="primary" block @click="selectTenant(td)" :loading="td.loading"><i class="ri-server-line" style="margin-right:6px"></i>实例管理</a-button><a-button block @click="openVcnPanel(td.tenant)"><i class="ri-share-line" style="margin-right:6px"></i>虚拟云网络</a-button><a-button block @click="openStoragePanel(td.tenant)"><i class="ri-database-2-line" style="margin-right:6px"></i>存储</a-button><a-button block @click="openQuickTask(td.tenant)"><i class="ri-play-circle-line" style="margin-right:6px"></i>快捷开机</a-button></div>
                    </div>
                  </template>
                </div>
                <div v-else>
                  <component :is="'div'" v-for="td in g1.tenants" :key="td.tenant.id" class="group-table-row" :class="{ 'tenant-row-active': td.tenant.id === activeTenantId }">
                    <div class="gtr-main">
                      <div class="gtr-ident">
                        <span class="gtr-name">{{ td.tenant.username }}</span>
                        <span v-if="td.tenant.tenantName" class="gtr-tenantnm">{{ td.tenant.tenantName }}</span>
                      </div>
                      <span class="gtr-region"><a-tag>{{ td.tenant.ociRegion }}</a-tag></span>
                    </div>
                    <a-space v-if="!isMobile" class="gtr-actions" size="small" wrap>
                      <a-button type="primary" size="small" @click="selectTenant(td)" :loading="td.loading">实例管理</a-button>
                      <a-button size="small" @click="openVcnPanel(td.tenant)">VCN</a-button>
                      <a-button size="small" @click="openStoragePanel(td.tenant)">存储</a-button>
                      <a-button size="small" @click="openQuickTask(td.tenant)">快捷开机</a-button>
                    </a-space>
                    <div v-else class="gtr-actions gtr-actions-mobile">
                      <a-button type="primary" size="small" @click="selectTenant(td)" :loading="td.loading">实例管理</a-button>
                      <a-dropdown placement="bottomRight" :trigger="['click']">
                        <a-button size="small">更多 <DownOutlined /></a-button>
                        <template #overlay>
                          <a-menu>
                            <a-menu-item key="vcn" @click="openVcnPanel(td.tenant)">VCN</a-menu-item>
                            <a-menu-item key="storage" @click="openStoragePanel(td.tenant)">存储</a-menu-item>
                            <a-menu-item key="quick" @click="openQuickTask(td.tenant)">快捷开机</a-menu-item>
                          </a-menu>
                        </template>
                      </a-dropdown>
                    </div>
                  </component>
                </div>
              </component>
            </div>
          </template>
          <!-- 无二级分组时直接平铺 -->
          <template v-else>
            <div v-if="tenantViewMode === 'card'" class="tenant-grid">
              <template v-for="td in g1.tenants" :key="td.tenant.id">
                <div class="tenant-card" :data-tenant-id="td.tenant.id" :class="{ 'tenant-card-active': activeTenantId === td.tenant.id, 'tenant-card-floating-source': isFloatingTenantSource(td.tenant) }">
                  <div class="tc-header"><i class="ri-cloud-line tc-icon"></i><div class="tc-info"><div class="tc-name">{{ td.tenant.username }}</div><div class="tc-region">{{ td.tenant.ociRegion }}</div></div></div>
                  <div class="tc-tags"><a-tag v-if="td.tenant.planType" :color="tenantPlanTagColor(td.tenant.planType)" :style="tenantPlanTagStyle(td.tenant.planType)" size="small">{{ formatTenantPlanType(td.tenant.planType) }}</a-tag><a-tag v-if="td.tenant.tenantName" size="small" color="blue">{{ td.tenant.tenantName }}</a-tag></div>
                  <div class="tc-actions"><a-button type="primary" block @click="selectTenant(td)" :loading="td.loading"><i class="ri-server-line" style="margin-right:6px"></i>实例管理</a-button><a-button block @click="openVcnPanel(td.tenant)"><i class="ri-share-line" style="margin-right:6px"></i>虚拟云网络</a-button><a-button block @click="openStoragePanel(td.tenant)"><i class="ri-database-2-line" style="margin-right:6px"></i>存储</a-button><a-button block @click="openQuickTask(td.tenant)"><i class="ri-play-circle-line" style="margin-right:6px"></i>快捷开机</a-button></div>
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
                  <a-button type="primary" size="small" @click="selectTenant(td)" :loading="td.loading">实例管理</a-button>
                  <a-button size="small" @click="openVcnPanel(td.tenant)">VCN</a-button>
                  <a-button size="small" @click="openStoragePanel(td.tenant)">存储</a-button>
                  <a-button size="small" @click="openQuickTask(td.tenant)">快捷开机</a-button>
                </a-space>
                <div v-else class="gtr-actions gtr-actions-mobile">
                  <a-button type="primary" size="small" @click="selectTenant(td)" :loading="td.loading">实例管理</a-button>
                  <a-dropdown placement="bottomRight" :trigger="['click']">
                    <a-button size="small">更多 <DownOutlined /></a-button>
                    <template #overlay>
                      <a-menu>
                        <a-menu-item key="vcn" @click="openVcnPanel(td.tenant)">VCN</a-menu-item>
                        <a-menu-item key="storage" @click="openStoragePanel(td.tenant)">存储</a-menu-item>
                        <a-menu-item key="quick" @click="openQuickTask(td.tenant)">快捷开机</a-menu-item>
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

    <!-- 无分组时保持原有卡片/列表展示 -->
    <template v-else>
    <!-- 租户卡片视图 -->
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
              <a-button type="primary" block @click="selectTenant(td)" :loading="td.loading">
                <i class="ri-server-line" style="margin-right: 6px"></i>实例管理
              </a-button>
              <a-button block @click="openVcnPanel(td.tenant)">
                <i class="ri-share-line" style="margin-right: 6px"></i>虚拟云网络
              </a-button>
              <a-button block @click="openStoragePanel(td.tenant)">
                <i class="ri-database-2-line" style="margin-right: 6px"></i>存储
              </a-button>
              <a-button block @click="openQuickTask(td.tenant)">
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
            <a-button type="primary" block @click="selectTenant(td)" :loading="td.loading">
              <i class="ri-server-line" style="margin-right: 6px"></i>实例管理
            </a-button>
            <a-button block @click="openVcnPanel(td.tenant)">
              <i class="ri-share-line" style="margin-right: 6px"></i>虚拟云网络
            </a-button>
            <a-button block @click="openStoragePanel(td.tenant)">
              <i class="ri-database-2-line" style="margin-right: 6px"></i>存储
            </a-button>
            <a-button block @click="openQuickTask(td.tenant)">
              <i class="ri-play-circle-line" style="margin-right: 6px"></i>快捷开机
            </a-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 租户列表视图 -->
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
              <a-button type="primary" size="small" @click="selectTenant(record)" :loading="record.loading">实例管理</a-button>
              <a-button size="small" @click="openVcnPanel(record.tenant)">VCN</a-button>
              <a-button size="small" @click="openStoragePanel(record.tenant)">存储</a-button>
              <a-button size="small" @click="openQuickTask(record.tenant)">快捷开机</a-button>
            </a-space>
          </template>
        </a-table-column>
      </a-table>
    </div>
    </template>

    <!-- 实例管理抽屉 -->
    <a-drawer :keyboard="false"
      v-model:open="instancePanelVisible"
      :width="instancePanelWidth"
      :mask-closable="false"
      :mask-style="tenantWorkspaceMaskStyle"
      destroy-on-close
      :wrap-class-name="instancePanelWrapClass"
      :body-style="{ padding: isMobile ? '10px' : '16px' }"
    >
      <template #title>
        <div v-if="activeTenantData" class="instance-drawer-title">
          <i class="ri-server-line" style="margin-right: 8px; color: var(--primary)"></i>
          <span class="drawer-username">{{ activeTenantData.tenant.username }}</span>
          <a-tag v-if="!isMobile" color="blue" style="margin-left: 8px">{{ instancePanelRegion || activeTenantData.tenant.ociRegion }}</a-tag>
          <a-badge :count="activeTenantData.instances.length" :show-zero="true" class="oci-group-count-badge" style="margin-left: 8px" />
        </div>
      </template>
      <template #extra>
        <div v-if="activeTenantData" class="panel-actions">
          <a-button size="small" @click.stop="refreshActiveTenantInstances" :loading="activeTenantData.loading">
            <template #icon><ReloadOutlined /></template>{{ isMobile ? '' : '刷新' }}
          </a-button>
        </div>
      </template>
      <div v-if="activeTenantData" class="instance-panel">
        <div class="instance-panel-toolbar">
          <span class="instance-panel-toolbar-label">Region</span>
          <a-select
            v-model:value="instancePanelRegion"
            class="instance-panel-region-select"
            :options="instanceRegionOptions"
            :loading="instanceSubscribedRegionsLoading"
            show-search
            option-filter-prop="label"
            placeholder="选择区域"
            @change="onInstancePanelRegionUserChange"
          />
          <a-button size="small" :loading="activeTenantData.loading" @click="refreshActiveTenantInstances">
            <template #icon><ReloadOutlined /></template>刷新
          </a-button>
          <span v-if="instanceSubscribedRegionsLoading" class="instance-panel-region-hint">正在同步订阅区域…</span>
        </div>

        <a-spin :spinning="activeTenantData.loading">
          <a-empty v-if="!activeTenantData.loading && activeTenantData.instances.length === 0" description="暂无实例" />

          <!-- 移动端：卡片流 -->
          <div v-else-if="isMobile" class="instance-mobile-list">
            <VirtualTenantCardList
              v-if="activeTenantData.instances.length > VIRTUAL_CARD_MIN"
              :items="activeTenantData.instances"
              :item-key="instanceRecordKey"
              :estimate-size="176"
              :max-height="instanceMobileVirtualMaxHeight"
              :reset-key="instanceVirtualResetKey"
            >
              <template #item="{ item: record }">
                <div class="instance-mobile-card">
                  <div class="imc-header">
                    <span class="imc-name" :title="record.name">{{ record.name }}</span>
                    <a-badge :status="stateColorMap[record.state] || 'default'" :text="record.state" />
                  </div>
                  <div class="imc-body">
                    <div class="imc-row">
                      <span class="imc-label">规格</span>
                      <div class="imc-value-group">
                        <span class="imc-value-main">{{ record.ocpus }}C / {{ record.memoryInGBs }}G</span>
                        <span class="imc-value-sub">{{ record.shape }}</span>
                      </div>
                    </div>
                    <div class="imc-row">
                      <span class="imc-label">公网 IP</span>
                      <a-typography-text v-if="record.publicIp" copyable class="ip-copy imc-value-main">{{ record.publicIp }}</a-typography-text>
                      <span v-else class="imc-value-sub">—</span>
                    </div>
                  </div>
                  <div class="imc-footer">
                    <a-button type="link" size="small" @click="openDetail(activeTenantData!.tenant, record)">
                      <i class="ri-information-line" style="margin-right: 4px"></i>详情
                    </a-button>
                    <a-dropdown :trigger="['click']">
                      <a-button type="link" size="small" class="instance-action-trigger" :loading="actionLoading[record.instanceId]">
                        实例操作
                        <DownOutlined style="font-size: 10px; margin-left: 2px" />
                      </a-button>
                      <template #overlay>
                        <a-menu class="instance-action-menu" @click="(info: any) => onInstanceMenuClick(record, info.key)">
                          <a-menu-item key="START">
                            <i class="ri-play-fill" style="color: #52c41a; margin-right: 8px"></i>启动
                          </a-menu-item>
                          <a-menu-item key="SOFTRESET" :disabled="record.state !== 'RUNNING'">
                            <i class="ri-restart-line" style="color: #faad14; margin-right: 8px"></i>重启
                          </a-menu-item>
                          <a-menu-item key="RESET" :disabled="record.state !== 'RUNNING'">
                            <i class="ri-shut-down-line" style="color: #ff7a45; margin-right: 8px"></i>断电重启
                          </a-menu-item>
                          <a-menu-item key="SOFTSTOP" :disabled="record.state !== 'RUNNING'">
                            <i class="ri-stop-fill" style="color: #8c8c8c; margin-right: 8px"></i>暂停
                          </a-menu-item>
                          <a-menu-divider />
                          <a-menu-item key="TERMINATE" danger>
                            <i class="ri-close-circle-line" style="color: #ff4d4f; margin-right: 8px"></i>终止
                          </a-menu-item>
                        </a-menu>
                      </template>
                    </a-dropdown>
                  </div>
                </div>
              </template>
            </VirtualTenantCardList>
            <template v-else>
            <div v-for="record in activeTenantData.instances" :key="record.instanceId" class="instance-mobile-card">
              <div class="imc-header">
                <span class="imc-name" :title="record.name">{{ record.name }}</span>
                <a-badge :status="stateColorMap[record.state] || 'default'" :text="record.state" />
              </div>
              <div class="imc-body">
                <div class="imc-row">
                  <span class="imc-label">规格</span>
                  <div class="imc-value-group">
                    <span class="imc-value-main">{{ record.ocpus }}C / {{ record.memoryInGBs }}G</span>
                    <span class="imc-value-sub">{{ record.shape }}</span>
                  </div>
                </div>
                <div class="imc-row">
                  <span class="imc-label">公网 IP</span>
                  <a-typography-text v-if="record.publicIp" copyable class="ip-copy imc-value-main">{{ record.publicIp }}</a-typography-text>
                  <span v-else class="imc-value-sub">—</span>
                </div>
              </div>
              <div class="imc-footer">
                <a-button type="link" size="small" @click="openDetail(activeTenantData!.tenant, record)">
                  <i class="ri-information-line" style="margin-right: 4px"></i>详情
                </a-button>
                <a-dropdown :trigger="['click']">
                  <a-button type="link" size="small" class="instance-action-trigger" :loading="actionLoading[record.instanceId]">
                    实例操作
                    <DownOutlined style="font-size: 10px; margin-left: 2px" />
                  </a-button>
                  <template #overlay>
                    <a-menu class="instance-action-menu" @click="(info: any) => onInstanceMenuClick(record, info.key)">
                      <a-menu-item key="START">
                        <i class="ri-play-fill" style="color: #52c41a; margin-right: 8px"></i>启动
                      </a-menu-item>
                      <a-menu-item key="SOFTRESET" :disabled="record.state !== 'RUNNING'">
                        <i class="ri-restart-line" style="color: #faad14; margin-right: 8px"></i>重启
                      </a-menu-item>
                      <a-menu-item key="RESET" :disabled="record.state !== 'RUNNING'">
                        <i class="ri-shut-down-line" style="color: #ff7a45; margin-right: 8px"></i>断电重启
                      </a-menu-item>
                      <a-menu-item key="SOFTSTOP" :disabled="record.state !== 'RUNNING'">
                        <i class="ri-stop-fill" style="color: #8c8c8c; margin-right: 8px"></i>暂停
                      </a-menu-item>
                      <a-menu-divider />
                      <a-menu-item key="TERMINATE" danger>
                        <i class="ri-close-circle-line" style="color: #ff4d4f; margin-right: 8px"></i>终止
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </div>
            </div>
            </template>
          </div>

          <!-- 桌面端：表格 -->
          <a-table v-else :columns="columns" :data-source="activeTenantData.instances" :loading="activeTenantData.loading"
            row-key="instanceId" size="middle" :pagination="false">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'name'">
                <a-tooltip :title="record.name" placement="topLeft">
                  <span class="instance-name-cell">{{ record.name }}</span>
                </a-tooltip>
              </template>
              <template v-if="column.key === 'state'">
                <a-badge :status="stateColorMap[record.state] || 'default'" :text="record.state" />
              </template>
              <template v-if="column.key === 'shape'">
                <div class="shape-cell">
                  <div class="shape-main">{{ record.ocpus }}C / {{ record.memoryInGBs }}G</div>
                  <div class="shape-sub" :title="record.shape">{{ record.shape }}</div>
                </div>
              </template>
              <template v-if="column.key === 'publicIp'">
                <a-typography-text v-if="record.publicIp" copyable class="ip-copy">{{ record.publicIp }}</a-typography-text>
                <span v-else style="color: var(--text-sub)">—</span>
              </template>
              <template v-if="column.key === 'action'">
                <a-space :size="2">
                  <a-button type="link" size="small" @click="openDetail(activeTenantData!.tenant, record)">详情</a-button>
                  <a-dropdown :trigger="['click']">
                    <a-button type="link" size="small" class="instance-action-trigger" :loading="actionLoading[record.instanceId]">
                      实例操作
                      <DownOutlined style="font-size: 10px; margin-left: 2px" />
                    </a-button>
                    <template #overlay>
                      <a-menu class="instance-action-menu" @click="(info: any) => onInstanceMenuClick(record, info.key)">
                        <a-menu-item key="START">
                          <i class="ri-play-fill" style="color: #52c41a; margin-right: 8px"></i>启动
                        </a-menu-item>
                        <a-menu-item key="SOFTRESET" :disabled="record.state !== 'RUNNING'">
                          <i class="ri-restart-line" style="color: #faad14; margin-right: 8px"></i>重启
                        </a-menu-item>
                        <a-menu-item key="RESET" :disabled="record.state !== 'RUNNING'">
                          <i class="ri-shut-down-line" style="color: #ff7a45; margin-right: 8px"></i>断电重启
                        </a-menu-item>
                        <a-menu-item key="SOFTSTOP" :disabled="record.state !== 'RUNNING'">
                          <i class="ri-stop-fill" style="color: #8c8c8c; margin-right: 8px"></i>暂停
                        </a-menu-item>
                        <a-menu-divider />
                        <a-menu-item key="TERMINATE" danger>
                          <i class="ri-close-circle-line" style="color: #ff4d4f; margin-right: 8px"></i>终止
                        </a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                </a-space>
              </template>
            </template>
          </a-table>
        </a-spin>
      </div>
    </a-drawer>

    <!-- 快捷开机任务弹窗 -->
    <a-modal
      :keyboard="false"
      v-model:open="quickTaskVisible"
      title="快捷开机任务"
      :width="isMobile ? '100%' : 600"
      :z-index="QUICK_TASK_MODAL_Z_INDEX"
      wrap-class-name="quick-task-modal-wrap"
      @ok="handleQuickTask"
      :confirm-loading="quickTaskLoading"
      :mask-closable="false"
      centered
    >
      <div style="margin-bottom: 12px">
        <a-tag color="blue">{{ quickTaskTenant?.username }}</a-tag>
        <span style="color: var(--text-sub); font-size: 12px; margin-left: 8px">租户配置区域：{{ quickTaskTenant?.ociRegion || '—' }}</span>
      </div>
      <a-form :model="quickTaskForm" layout="vertical">
        <a-form-item label="目标区域（开机任务）">
          <a-select
            v-model:value="quickTaskForm.ociRegion"
            placeholder="选择目标区域"
            :show-search="false"
            :options="ociRegionSelectOptions"
            :get-popup-container="quickTaskPopupContainer"
          />
        </a-form-item>
        <ShapeSeriesPicker
          v-model:architecture="quickTaskForm.architecture"
          :shapes="quickTaskShapes"
          :loading="quickTaskShapesLoading"
          :hint="quickTaskShapes.length ? `已从 OCI 加载 ${quickTaskShapes.length} 个可用 Shape（随目标区域变化）` : ''"
          :is-mobile="isMobile"
          :get-popup-container="quickTaskPopupContainer"
        />
        <a-form-item label="操作系统">
          <a-select v-model:value="quickTaskForm.operationSystem" :get-popup-container="quickTaskPopupContainer">
            <a-select-option value="Ubuntu">Ubuntu（最新版）</a-select-option>
            <a-select-option value="Ubuntu 24.04">Ubuntu 24.04 LTS</a-select-option>
            <a-select-option value="Ubuntu 22.04">Ubuntu 22.04 LTS</a-select-option>
            <a-select-option value="Ubuntu 20.04">Ubuntu 20.04 LTS</a-select-option>
            <a-select-option value="Oracle Linux">Oracle Linux</a-select-option>
            <a-select-option value="CentOS">CentOS</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-if="quickDenseIoTiers?.length" label="DenseIO 档位">
          <a-select v-model:value="quickDenseIoTierKey" style="width: 100%" :get-popup-container="quickTaskPopupContainer">
            <a-select-option v-for="t in quickDenseIoTiers" :key="denseIoFlexTierKey(t)" :value="denseIoFlexTierKey(t)">
              {{ formatDenseIoTierLabel(t) }}
            </a-select-option>
          </a-select>
          <div style="color: var(--text-sub); font-size: 12px; margin-top: 4px">
            本地 NVMe 与网络带宽随档位由 OCI 自动配置，与控制台一致
          </div>
        </a-form-item>
        <a-row v-if="!quickDenseIoTiers?.length" :gutter="12">
          <a-col :xs="12" :sm="12">
            <a-form-item :label="quickTaskOcpuLabel">
              <a-input-number
                :value="quickTaskForm.ocpus"
                :min="quickTaskShapeLimits.minOcpus"
                :max="quickTaskShapeLimits.maxOcpus"
                :disabled="quickTaskBmLocked"
                style="width: 100%"
                @update:value="updateQuickTaskOcpus"
                @blur="clampQuickTaskResources"
              />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="12">
            <a-form-item :label="quickTaskMemoryLabel">
              <a-input-number
                :value="quickTaskForm.memory"
                :min="quickTaskShapeLimits.minMemory"
                :max="quickTaskShapeLimits.maxMemory"
                :disabled="quickTaskBmLocked"
                style="width: 100%"
                @update:value="updateQuickTaskMemory"
                @blur="clampQuickTaskResources"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :xs="12" :sm="12">
            <a-form-item label="磁盘 (GB)">
              <a-input-number v-model:value="quickTaskForm.disk" :min="47" :max="200" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="12">
            <a-form-item label="VPUs/GB">
              <a-input-number
                v-model:value="quickTaskForm.vpusPerGB"
                :min="BOOT_VOLUME_VPUS_MIN"
                :max="BOOT_VOLUME_VPUS_MAX"
                :step="BOOT_VOLUME_VPUS_STEP"
                style="width: 100%"
                @blur="snapQuickTaskBootVpus"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <div class="quick-login-options-row">
          <div>
            <a-form-item label="数量">
              <a-input-number v-model:value="quickTaskForm.createNumbers" :min="1" :max="5" style="width: 100%" />
            </a-form-item>
          </div>
          <div>
            <a-form-item label="间隔 (秒)">
              <a-input-number v-model:value="quickTaskForm.interval" :min="10" :max="600" style="width: 100%" />
            </a-form-item>
          </div>
          <div>
            <TaskLoginSelector
              v-model:root-password="quickTaskForm.rootPassword"
              v-model:login-mode="quickTaskForm.loginMode"
              v-model:ssh-public-key="quickTaskForm.sshPublicKey"
              :saved-root-password="quickTaskSavedRootPassword"
              :saved-ssh-public-key="quickTaskSavedSshPublicKey"
              placeholder="留空=随机生成"
              @missing="warnQuickTaskCredentialMissing"
            />
          </div>
        </div>
        <div style="display: flex; align-items: center; gap: 32px; margin-bottom: 16px">
          <span style="display: inline-flex; align-items: center; gap: 8px">
            <a-switch v-model:checked="quickTaskForm.assignPublicIp" />
            <span>公网IP</span>
          </span>
          <span style="display: inline-flex; align-items: center; gap: 8px">
            <a-switch v-model:checked="quickTaskForm.assignIpv6" />
            <span>IPv6</span>
          </span>
        </div>
        <a-form-item label="自定义开机脚本">
          <a-textarea v-model:value="quickTaskForm.customScript" placeholder="可选，留空不执行" :auto-size="{ minRows: 2, maxRows: 5 }" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 实例详情抽屉 -->
    <a-drawer :keyboard="false"
      v-model:open="drawerVisible"
      :title="currentInstance?.name || '实例详情'"
      :width="isMobile ? '100%' : 780"
      placement="right"
      :mask-closable="false"
    >
      <a-tabs v-model:activeKey="activeTab" @change="onTabChange">
        <a-tab-pane key="info" tab="基本信息">
          <a-button size="small" @click="refreshInstanceInfo" :loading="instanceInfoLoading" style="margin-bottom: 12px">
            刷新实例信息
          </a-button>
          <a-descriptions :column="1" bordered size="small" v-if="currentInstance">
            <a-descriptions-item label="实例名称">
              {{ currentInstance.name }}
              <a-button type="link" size="small" @click="openEditInstance" style="margin-left: 8px">
                <template #icon><EditOutlined /></template>修改
              </a-button>
            </a-descriptions-item>
            <a-descriptions-item label="实例 ID">
              <a-typography-text copyable style="font-size: 12px">{{ currentInstance.instanceId }}</a-typography-text>
            </a-descriptions-item>
            <a-descriptions-item label="Region">{{ currentInstance.region }}</a-descriptions-item>
            <a-descriptions-item label="Shape">{{ currentInstance.shape }}</a-descriptions-item>
            <a-descriptions-item label="配置">{{ currentInstance.ocpus }} OCPU / {{ currentInstance.memoryInGBs }} GB</a-descriptions-item>
            <a-descriptions-item label="区间 (Compartment)">{{ currentInstance.compartmentName || '—' }}</a-descriptions-item>
            <a-descriptions-item label="状态">
              <a-badge :status="stateColorMap[currentInstance.state] || 'default'" :text="currentInstance.state" />
            </a-descriptions-item>
            <a-descriptions-item label="创建日期">
              {{ formatInstanceCreatedDate(currentInstance.timeCreated) }}
            </a-descriptions-item>
          </a-descriptions>

          <a-divider orientation="left">网络信息</a-divider>
          <InstanceNetworkDetailPanel
            ref="networkDetailPanelRef"
            :tenant="currentTenant"
            :instance="currentInstance"
            :active="activeTab === 'info'"
            :region="currentDetailRegion"
            :compartment-id="currentInstance?.compartmentId"
          />

          <a-divider />
          <a-space>
            <a-popconfirm v-if="currentInstance?.state === 'STOPPED'" title="确定启动？" @confirm="handleAction(currentTenant!, currentInstance!, 'START')">
              <a-button type="primary" :loading="actionLoading[currentInstance?.instanceId]">启动</a-button>
            </a-popconfirm>
            <a-popconfirm v-if="currentInstance?.state === 'RUNNING'" title="确定停止？" @confirm="handleAction(currentTenant!, currentInstance!, 'STOP')">
              <a-button :loading="actionLoading[currentInstance?.instanceId]">停止</a-button>
            </a-popconfirm>
            <a-popconfirm v-if="currentInstance?.state === 'RUNNING'" title="确定重启？" @confirm="handleAction(currentTenant!, currentInstance!, 'RESET')">
              <a-button :loading="actionLoading[currentInstance?.instanceId]">重启</a-button>
            </a-popconfirm>
            <a-popconfirm title="确定换 IP？" @confirm="handleChangeIp">
              <a-button :loading="changeIpLoading" :disabled="currentInstance?.state !== 'RUNNING'">换 IP</a-button>
            </a-popconfirm>
            <a-button danger @click="openTerminateVerify(currentTenant!, currentInstance!)">终止</a-button>
          </a-space>
        </a-tab-pane>

        <a-tab-pane key="security" tab="安全列表">
          <InstanceSecurityPanel
            ref="securityPanelRef"
            :tenant="currentTenant"
            :instance="currentInstance"
            :is-mobile="isMobile"
            :active="activeTab === 'security'"
            :region="currentDetailRegion"
            :compartment-id="currentInstance?.compartmentId"
            @overlay-active-change="securityOverlayActive = $event"
          />
        </a-tab-pane>

        <a-tab-pane key="volume" tab="引导卷">
          <BootVolumePanel
            ref="bootVolumePanelRef"
            :tenant="currentTenant"
            :instance="currentInstance"
            :is-mobile="isMobile"
            :active="activeTab === 'volume'"
            :region="currentDetailRegion"
            @overlay-active-change="bootVolumeOverlayActive = $event"
            @boot-volume-updated="onBootVolumeUpdated"
          />
        </a-tab-pane>

        <a-tab-pane key="blockVolume" tab="块存储">
          <BlockStoragePanel
            ref="blockStoragePanelRef"
            :tenant="currentTenant"
            :instance="currentInstance"
            :is-mobile="isMobile"
            :active="activeTab === 'blockVolume'"
            :region="currentDetailRegion"
            :on-stop-instance="stopCurrentDetailInstance"
            @overlay-active-change="blockStorageOverlayActive = $event"
          />
        </a-tab-pane>

        <a-tab-pane key="network" tab="网络">
          <InstanceNetworkPanel
            ref="networkPanelRef"
            :tenant="currentTenant"
            :instance="currentInstance"
            :is-mobile="isMobile"
            :region="currentDetailRegion"
            @open-vcn-manager="openDetailVcnManager"
          />
        </a-tab-pane>

        <a-tab-pane key="traffic" tab="流量统计">
          <InstanceTrafficPanel
            ref="trafficPanelRef"
            :tenant="currentTenant"
            :instance="currentInstance"
            :is-mobile="isMobile"
            :active="activeTab === 'traffic'"
            :region="currentDetailRegion"
            @overlay-active-change="trafficOverlayActive = $event"
          />
        </a-tab-pane>

        <a-tab-pane key="shape" tab="形状编辑">
          <a-alert type="info" show-icon style="margin-bottom: 16px">
            <template #message>
              列表按当前实例镜像与可用域从 OCI ListShapes 拉取；Flex 规格须落在 API 返回的 OCPU/内存上下限内。提交后 OCI 可能自动停止并重启实例以生效。
            </template>
          </a-alert>
          <a-alert
            v-if="shapeEditTask"
            ref="shapeEditTaskAlertRef"
            :type="shapeEditTaskAlertType"
            show-icon
            style="margin-bottom: 16px"
          >
            <template #message>
              <div class="shape-edit-task-head">
                <span>{{ shapeEditTaskTitle }}</span>
                <a-space v-if="shapeEditTaskActive" size="small" wrap>
                  <a-button size="small" :loading="shapeEditTaskActionLoading" @click="handleToggleShapeEditTaskPause">
                    <template #icon>
                      <PlayCircleOutlined v-if="shapeEditTaskPaused" />
                      <PauseCircleOutlined v-else />
                    </template>
                    {{ shapeEditTaskPaused ? '恢复' : '暂停' }}
                  </a-button>
                  <a-button size="small" danger :loading="shapeEditTaskActionLoading" @click="handleStopShapeEditTask">
                    <template #icon><StopOutlined /></template>
                    停止
                  </a-button>
                </a-space>
              </div>
            </template>
            <template #description>
              <a-progress
                :percent="shapeEditTaskPercent"
                :status="shapeEditTaskProgressStatus"
                size="small"
                style="margin-top: 8px"
              />
              <div class="shape-edit-task-message">{{ shapeEditTaskMessage }}</div>
            </template>
          </a-alert>
          <a-spin :spinning="shapeEditLoading">
            <template v-if="currentInstance">
              <a-descriptions :column="1" bordered size="small" style="margin-bottom: 16px">
                <a-descriptions-item label="当前 Shape">{{ currentInstance.shape }}</a-descriptions-item>
                <a-descriptions-item label="当前配置">
                  {{ currentInstance.ocpus ?? '—' }} OCPU / {{ currentInstance.memoryInGBs ?? '—' }} GB
                </a-descriptions-item>
                <a-descriptions-item label="状态">
                  <a-badge :status="stateColorMap[currentInstance.state] || 'default'" :text="currentInstance.state" />
                </a-descriptions-item>
              </a-descriptions>
              <a-form layout="vertical" class="shape-edit-form">
                <a-form-item label="目标 Shape">
                  <a-select
                    v-model:value="shapeForm.shape"
                    show-search
                    :filter-option="filterShapeOption"
                    placeholder="选择兼容形状"
                    :options="shapeEditSelectOptions"
                    :disabled="shapeEditTaskActive"
                    @change="onShapeFormShapeChange"
                  />
                </a-form-item>
                <template v-if="shapeEditSelectedMeta?.isFlexible">
                  <a-row :gutter="12">
                    <a-col :span="12">
                      <a-form-item :label="shapeOcpuLabel">
                        <a-input-number
                          v-model:value="shapeForm.ocpus"
                          :min="shapeOcpuMin"
                          :max="shapeOcpuMax"
                          :step="1"
                          :disabled="shapeEditTaskActive"
                          style="width: 100%"
                        />
                      </a-form-item>
                    </a-col>
                    <a-col :span="12">
                      <a-form-item :label="shapeMemoryLabel">
                        <a-input-number
                          v-model:value="shapeForm.memoryInGBs"
                          :min="shapeMemoryMin"
                          :max="shapeMemoryMax"
                          :step="1"
                          :disabled="shapeEditTaskActive"
                          style="width: 100%"
                        />
                      </a-form-item>
                    </a-col>
                  </a-row>
                </template>
                <a-alert
                  v-else-if="shapeEditSelectedMeta"
                  type="warning"
                  show-icon
                  message="当前为固定规格 Shape，仅可更换形状系列，不能单独调整 OCPU/内存。"
                  style="margin-bottom: 12px"
                />
                <a-space wrap>
                  <a-button type="primary" :loading="shapeEditSaving" :disabled="shapeEditTaskActive || !shapeForm.shape" @click="handleApplyShapeEdit">
                    应用形状变更
                  </a-button>
                  <a-button :disabled="shapeEditLoading" @click="loadShapeEditOptions(true)">刷新 Shape 列表</a-button>
                </a-space>
              </a-form>
            </template>
          </a-spin>
        </a-tab-pane>

        <a-tab-pane key="console" tab="串行控制台">
          <a-alert type="info" show-icon style="margin-bottom: 16px">
            <template #message>用于实例网络异常时的紧急救援，通过 OCI 内部通道连接实例串口</template>
          </a-alert>

          <template v-if="!consoleData">
            <a-button type="primary" @click="handleCreateConsole" :loading="consoleLoading">
              <i class="ri-terminal-line" style="margin-right: 6px"></i>创建控制台连接
            </a-button>
            <div style="margin-top: 8px; color: var(--text-sub); font-size: 12px">
              创建后会生成一个一键连接链接，可直接进入串口终端
            </div>
          </template>

          <template v-else>
            <a-descriptions :column="1" bordered size="small">
              <a-descriptions-item label="连接状态">
                <a-badge status="success" text="已就绪" />
              </a-descriptions-item>
              <a-descriptions-item label="一键连接">
                <a-button type="primary" @click="openConsoleWebSSH">
                  <i class="ri-external-link-line" style="margin-right: 6px"></i>打开串行控制台
                </a-button>
              </a-descriptions-item>
              <a-descriptions-item label="SSH 命令">
                <a-typography-text copyable :content="consoleData.sshCommand" style="font-size: 11px; word-break: break-all">
                  {{ consoleData.sshCommand?.substring(0, 80) }}...
                </a-typography-text>
              </a-descriptions-item>
            </a-descriptions>
            <div style="margin-top: 12px">
              <a-popconfirm title="确定断开控制台连接？" @confirm="handleDeleteConsole">
                <a-button danger :loading="consoleLoading">断开连接</a-button>
              </a-popconfirm>
            </div>
            <div style="margin-top: 8px; color: var(--text-sub); font-size: 12px">
              提示：断开后临时用户将自动清理。进入控制台后按 Ctrl+] 或 ~. 退出。
            </div>
          </template>
        </a-tab-pane>
      </a-tabs>
      <template v-if="activeTab === 'shape' && showForceA2ToA1Button" #footer>
        <div class="instance-drawer-shape-footer">
          <a-button danger @click="openForceA2ToA1Modal">A2强改A1</a-button>
        </div>
      </template>
    </a-drawer>


    <!-- 新建预留IP弹窗 -->
    <a-modal :keyboard="false" v-model:open="createRipVisible" title="新建预留 IP" @ok="handleCreateReservedIp"
      :confirm-loading="createRipLoading" :mask-closable="false">
      <a-form layout="vertical">
        <a-form-item label="名称（可选）">
          <a-input v-model:value="createRipName" placeholder="reserved-ip" />
        </a-form-item>
        <div style="color: #999; font-size: 12px">创建一个未绑定的预留 IP。创建后可在列表中绑定到实例。</div>
      </a-form>
    </a-modal>

    <!-- 修改实例弹窗 -->
    <a-modal :keyboard="false" v-model:open="editInstanceVisible" title="修改实例" @ok="handleEditInstance"
      :confirm-loading="editInstanceLoading" :mask-closable="false" :width="isMobile ? '100%' : 480">
      <a-form layout="vertical" v-if="currentInstance">
        <a-form-item label="实例名称">
          <a-input v-model:value="editInstanceForm.displayName" placeholder="输入新名称" />
        </a-form-item>
        <div style="color: #999; font-size: 12px">调整 Shape / OCPU / 内存请使用详情抽屉中的「形状编辑」页签。</div>
      </a-form>
    </a-modal>

    <ForceA2ConfirmModal
      v-if="forceA2ModalVisible"
      v-model:open="forceA2ModalVisible"
      v-model:trial="forceA2Q.trial"
      v-model:a2-shape="forceA2Q.a2Shape"
      v-model:risk="forceA2Q.risk"
      :loading="forceA2Loading"
      :all-yes="forceA2AllYes"
      :is-mobile="isMobile"
      :on-confirm="handleForceA2ToA1Confirm"
      @cancel="resetForceA2Modal"
    />

    <TerminateVerifyModal
      v-if="verifyModalVisible"
      v-model:open="verifyModalVisible"
      v-model:code="verifyCode"
      v-model:delete-boot-volume="deleteBootVolume"
      :loading="verifyLoading"
      :sending="verifySending"
      :on-confirm="handleTerminateWithCode"
      :on-resend="resendTerminateVerifyCode"
    />

    <!-- 虚拟云网络抽屉 -->
    <a-drawer :keyboard="false" v-model:open="vcnVisible" :title="'虚拟云网络 — ' + (vcnTenant?.username || '')"
      :width="instancePanelWidth" :mask-closable="false" :mask-style="tenantWorkspaceMaskStyle" destroy-on-close
      :wrap-class-name="vcnPanelWrapClass">
      <div v-if="vcnTenant" class="vcn-panel-toolbar">
        <span class="instance-panel-toolbar-label">Region</span>
        <a-select
          v-model:value="vcnPanelRegion"
          class="instance-panel-region-select"
          :options="vcnRegionOptions"
          :loading="vcnSubscribedRegionsLoading"
          show-search
          option-filter-prop="label"
          placeholder="选择区域"
          @change="onVcnPanelRegionUserChange"
        />
        <span v-if="vcnSubscribedRegionsLoading" class="instance-panel-region-hint">正在同步订阅区域…</span>
      </div>
      <a-spin :spinning="vcnListLoading">
        <a-empty v-if="!vcnListLoading && vcnList.length === 0" description="无 VCN 数据" />
        <div v-else>
          <div v-for="vcn in vcnList" :key="vcn.id" class="vcn-item">
            <div class="vcn-item-header">
              <div style="display: flex; align-items: center; gap: 8px">
                <i class="ri-share-line" style="font-size: 18px; color: var(--primary)"></i>
                <span style="font-weight: 700">{{ vcn.displayName }}</span>
              </div>
              <a-space>
                <a-tag color="purple">{{ vcn.compartmentName }}</a-tag>
                <a-button size="small" type="primary" @click="openVcnManager(vcnTenant?.id, vcn)">管理</a-button>
              </a-space>
            </div>
            <div class="vcn-item-body">
              <div class="vcn-info-row"><span class="info-label">CIDR</span><span>{{ (vcn.cidrBlocks || []).join(', ') }}</span></div>
              <div class="vcn-info-row"><span class="info-label">状态</span><a-badge :status="vcn.state === 'AVAILABLE' ? 'success' : 'default'" :text="vcn.state" /></div>
            </div>
            <div v-if="vcn.subnets && vcn.subnets.length > 0" style="margin-top: 8px">
              <div style="font-size: 12px; color: var(--text-sub); margin-bottom: 4px">子网：</div>
              <div v-for="sub in vcn.subnets" :key="sub.id" class="vcn-subnet-row">
                <span>{{ sub.displayName }}</span>
                <a-tag size="small">{{ sub.cidrBlock }}</a-tag>
                <a-tag :color="sub.isPublic ? 'green' : 'default'" size="small">{{ sub.isPublic ? '公有' : '私有' }}</a-tag>
              </div>
            </div>
          </div>
          <div class="vcn-item">
            <div class="vcn-item-header">
              <div style="display: flex; align-items: center; gap: 8px">
                <i class="ri-map-pin-line" style="font-size: 18px; color: var(--primary)"></i>
                <span style="font-weight: 700">预留 IP</span>
              </div>
              <a-space>
                <a-button size="small" :loading="reservedIpListLoading" @click="loadReservedIps">刷新</a-button>
                <a-button size="small" type="primary" @click="showCreateReservedIpModal">新建</a-button>
              </a-space>
            </div>
            <a-spin :spinning="reservedIpListLoading">
              <a-empty v-if="!reservedIpListLoading && ociReservedIps.length === 0" description="暂无预留 IP" />
              <div v-for="rip in ociReservedIps" :key="rip.id" class="vcn-ip-row">
                <a-typography-text copyable>{{ rip.ipAddress }}</a-typography-text>
                <a-tag :color="rip.isAssigned ? 'green' : 'default'">{{ rip.isAssigned ? '已绑定' : '未绑定' }}</a-tag>
                <span v-if="rip.assignedInstanceName" class="vcn-ip-meta">{{ rip.assignedInstanceName }}</span>
                <a-popconfirm v-if="!rip.isAssigned" title="确定删除？" @confirm="handleDeleteReservedIp(rip.id)">
                  <a-button type="link" danger size="small">删除</a-button>
                </a-popconfirm>
                <a-button v-if="rip.isAssigned" type="link" size="small" @click="handleUnassignReservedIp(rip.id)">解绑</a-button>
              </div>
            </a-spin>
            <p class="vcn-panel-hint">OCI 分配的预留公网 IP，可绑定到实例。</p>
          </div>

          <ByoipPanel
            v-if="vcnTenant"
            :user-id="vcnTenant.id"
            :region="vcnPanelRegion"
            @changed="onByoipChanged"
            @editing-overlay-change="handleByoipEditingOverlayChange"
          />
        </div>
      </a-spin>
    </a-drawer>

    <VcnManager
      v-model:open="vcnManagerOpen"
      :user-id="vcnManagerUserId"
      :vcn="vcnManagerVcn"
      :oci-region="vcnManagerOciRegion"
      @changed="onVcnManagerChanged"
      @editing-overlay-change="handleVcnManagerEditingOverlayChange"
    />

    <StorageManager
      v-model:open="storageManagerOpen"
      :user-id="storageManagerUserId"
      :tenant-name="storageManagerTenantName"
      :default-region="storageManagerDefaultRegion"
      @editing-overlay-change="handleStorageManagerEditingOverlayChange"
    />

    <div
      v-if="floatingTenantCardVisible"
      class="tenant-floating-card"
      :class="{
        'tenant-floating-card-rolling': floatingTenantCard.phase === 'rolling',
        'tenant-floating-card-docked': floatingTenantCard.phase === 'docked',
      }"
      :style="floatingTenantCardStyle"
    >
      <div class="tenant-floating-flight">
        <div class="tenant-floating-roll">
          <div class="tenant-floating-card-face">
            <div class="tc-header">
              <i class="ri-cloud-line tc-icon"></i>
              <div class="tc-info">
                <div class="tc-name">{{ floatingTenantCard.username }}</div>
                <div class="tc-region">{{ floatingTenantCard.region }}</div>
              </div>
            </div>
            <div class="tc-tags">
              <a-tag v-if="floatingTenantCard.planType" :color="tenantPlanTagColor(floatingTenantCard.planType)" :style="tenantPlanTagStyle(floatingTenantCard.planType)" size="small">{{ formatTenantPlanType(floatingTenantCard.planType) }}</a-tag>
              <a-tag v-if="floatingTenantCard.tenantName" size="small" color="blue">{{ floatingTenantCard.tenantName }}</a-tag>
            </div>
            <div class="tc-actions">
              <a-button
                v-for="item in floatingTenantActionItems"
                :key="item.key"
                block
                :type="floatingTenantButtonType(item.key)"
                class="tenant-floating-action"
                :class="{ 'tenant-floating-action-active': item.key === tenantWorkspaceKind }"
                :disabled="floatingTenantCard.phase === 'rolling'"
                @click.stop="handleFloatingTenantAction(item.key)"
              >
                <i :class="item.icon"></i>
                <span>{{ item.label }}</span>
              </a-button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="tenant-page-float-actions" aria-label="页面快捷操作">
      <a-tooltip
        v-if="hasGroups"
        placement="left"
        :title="allInstanceGroupsExpanded ? '收起所有一级分组与子分组' : '展开所有一级分组与子分组'"
      >
        <a-button type="default" shape="circle" class="float-action-btn" @click="toggleAllInstanceGroups">
          <template #icon>
            <MenuUnfoldOutlined v-if="allInstanceGroupsExpanded" />
            <MenuFoldOutlined v-else />
          </template>
        </a-button>
      </a-tooltip>
      <a-tooltip placement="left" title="返回页面顶部">
        <a-button type="default" shape="circle" class="float-action-btn" @click="scrollInstancePageTop">
          <template #icon><VerticalAlignTopOutlined /></template>
        </a-button>
      </a-tooltip>
    </div>

  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'InstanceList' })

import { ref, reactive, computed, nextTick, onMounted, onActivated, onUnmounted, watch, defineAsyncComponent } from 'vue'
import {
  ReloadOutlined,
  EditOutlined,
  DownOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  VerticalAlignTopOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  StopOutlined,
} from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import {
  getInstanceList, updateInstanceState, terminateInstance,
  getVcns,
  changeIp,
  createReservedIp, listReservedIps, deleteReservedIp,
  assignReservedIp, unassignReservedIp,
  updateInstance,
  getShapeEditTaskStatus,
  pauseShapeEditTask,
  resumeShapeEditTask,
  stopShapeEditTask,
  createConsoleConnection, deleteConsoleConnection,
  getShapesForInstance,
  forceA2ToA1,
  type ShapeEditTaskStatus,
} from '../api/instance'
import { getTenantGroups } from '../api/tenant'
import { useTenantCatalogStore } from '../stores/tenantCatalog'

const VcnManager = defineAsyncComponent(() => import('./VcnManager.vue'))
const StorageManager = defineAsyncComponent(() => import('./StorageManager.vue'))
const ByoipPanel = defineAsyncComponent(() => import('./ByoipPanel.vue'))
const ForceA2ConfirmModal = defineAsyncComponent(() => import('../components/instance/ForceA2ConfirmModal.vue'))
const TerminateVerifyModal = defineAsyncComponent(() => import('../components/instance/TerminateVerifyModal.vue'))
const BootVolumePanel = defineAsyncComponent(() => import('../components/instance/BootVolumePanel.vue'))
const BlockStoragePanel = defineAsyncComponent(() => import('../components/instance/BlockStoragePanel.vue'))
const InstanceTrafficPanel = defineAsyncComponent(() => import('../components/instance/InstanceTrafficPanel.vue'))
const InstanceSecurityPanel = defineAsyncComponent(() => import('../components/instance/InstanceSecurityPanel.vue'))
const InstanceNetworkPanel = defineAsyncComponent(() => import('../components/instance/InstanceNetworkPanel.vue'))
const InstanceNetworkDetailPanel = defineAsyncComponent(() => import('../components/instance/InstanceNetworkDetailPanel.vue'))
import { sendVerifyCode } from '../api/system'
import { listStorageRegions } from '../api/storage'
import {
  ociRegionSelectOptions,
} from '../utils/ociRegionCatalog'
import {
  getFlexShapeSpec,
  resolveShapeEditFlexLimits,
  formatShapeResourceRangeLabel,
} from '../constants/ociBmShapeSpecs'
import { useQuickTask } from '../composables/useQuickTask'
import { isAllGroupsExpanded } from '../composables/groupExpandToggle'
import ShapeSeriesPicker from '../components/ShapeSeriesPicker.vue'
import TaskLoginSelector from '../components/TaskLoginSelector.vue'
import VirtualTenantCardList from '../components/tenant/VirtualTenantCardList.vue'
import VirtualTenantGridList from '../components/tenant/VirtualTenantGridList.vue'
import {
  BOOT_VOLUME_VPUS_MAX,
  BOOT_VOLUME_VPUS_MIN,
  BOOT_VOLUME_VPUS_STEP,
} from '../utils/bootVolume'
import {
  formatTenantPlanType,
  isFreeTierPlan,
  tenantPlanTagColor,
} from '../utils/tenantPlan'
import { appQueryCache, createListSignature } from '../utils/queryCache'
import {
  INSTANCE_CONFIRM_MODAL_WRAP_CLASS,
  INSTANCE_CONFIRM_MODAL_Z_INDEX,
  QUICK_TASK_MODAL_Z_INDEX,
} from '../utils/overlayZIndex'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'

dayjs.extend(utc)

const catalog = useTenantCatalogStore()
const VIRTUAL_CARD_MIN = 12
const INSTANCE_LIST_CACHE_TTL_MS = 60_000
let instanceListActivatedOnce = false

interface LoadTenantInstancesOptions {
  force?: boolean
  notify?: boolean
}

function isGroupPanelOpen(key: string) {
  return activeGroupKeys.value.includes(key)
}

function isL2PanelOpen(key: string) {
  return activeL2Keys.value.includes(key)
}

function formatInstanceCreatedDate(v: unknown): string {
  if (v == null || v === '') return '—'
  if (typeof v !== 'string' && typeof v !== 'number' && !(v instanceof Date)) return '—'
  const d = dayjs.utc(v)
  if (!d.isValid()) return '—'
  const y = d.year()
  const m = String(d.month() + 1).padStart(2, '0')
  const day = String(d.date()).padStart(2, '0')
  return `${y}年${m}月${day}日`
}

function tenantPlanTagStyle(plan: unknown): Record<string, string> | undefined {
  if (!isFreeTierPlan(plan)) return undefined
  return {
    color: 'var(--tenant-free-tier-color)',
    background: 'var(--tenant-free-tier-bg)',
    borderColor: 'var(--tenant-free-tier-border)',
    boxShadow: 'var(--tenant-free-tier-shadow)',
    backdropFilter: 'blur(10px) saturate(140%)',
    WebkitBackdropFilter: 'blur(10px) saturate(140%)',
  }
}

interface TenantData {
  tenant: any
  instances: any[]
  loading: boolean
  collapsed: boolean
}

const stateColorMap: Record<string, string> = {
  RUNNING: 'success', STOPPED: 'error', STARTING: 'processing',
  STOPPING: 'warning', TERMINATED: 'default',
}

const columns = [
  { title: '名称', dataIndex: 'name', key: 'name', width: 180, ellipsis: true },
  { title: '规格', key: 'shape', width: 180 },
  { title: '公网 IP', dataIndex: 'publicIp', key: 'publicIp', width: 150 },
  { title: '状态', dataIndex: 'state', key: 'state', width: 110 },
  { title: '操作', key: 'action', width: 180 },
]

const isMobile = ref(window.innerWidth < 768)
const viewportHeight = ref(window.innerHeight)
function checkMobile() {
  viewportHeight.value = window.innerHeight
  isMobile.value = window.innerWidth < 768
  if (isMobile.value) {
    if (floatingTenantCard.phase !== 'idle') floatingTenantCard.phase = 'idle'
    return
  }
  const tenant = resolveFloatingTenantFromWorkspace()
  if (tenant) refreshFloatingTenantCard(tenant)
}

const tenantViewMode = ref<'card' | 'table'>('card')
const searchKeyword = ref('')
const globalLoading = ref(false)
const tenantDataList = ref<TenantData[]>([])
const actionLoading = reactive<Record<string, boolean>>({})
const activeTenantId = ref('')

const currentTenant = ref<any>(null)
const currentInstance = ref<any>(null)

const filteredTenants = computed(() => {
  if (!searchKeyword.value) return tenantDataList.value
  const kw = searchKeyword.value.toLowerCase()
  return tenantDataList.value.filter(td =>
    (td.tenant.username || '').toLowerCase().includes(kw) ||
    (td.tenant.ociRegion || '').toLowerCase().includes(kw) ||
    (td.tenant.tenantName || '').toLowerCase().includes(kw)
  )
})

const tenantVirtualListMaxHeight = computed(() => Math.max(460, Math.min(760, viewportHeight.value - 180)))
function shouldVirtualizeTenantCards(count: number) {
  return count > VIRTUAL_CARD_MIN
}
function tenantDataKey(item: unknown, index: number) {
  return String((item as TenantData)?.tenant?.id ?? index)
}
const tenantVirtualResetKey = computed(() =>
  `${tenantViewMode.value}|${searchKeyword.value}|${createListSignature(filteredTenants.value, (td) => td.tenant.id)}`,
)

interface GroupNode {
  label: string
  key: string
  children?: GroupNode[]
  tenants: TenantData[]
}
const groupData = computed(() => catalog.groupData)

const COLLAPSE_KEY = 'instanceList.groupCollapse.v2'

interface CollapsePersist {
  l1: string[]
  l2: string[]
}

function migrateFlatCollapseKeys(flat: string[]): CollapsePersist {
  const l1: string[] = []
  const l2: string[] = []
  for (const k of flat) {
    if (String(k).includes('/')) l2.push(String(k))
    else l1.push(String(k))
  }
  return { l1, l2 }
}

function loadCollapseState(): CollapsePersist {
  try {
    const raw = JSON.parse(localStorage.getItem(COLLAPSE_KEY) || 'null')
    if (raw && Array.isArray(raw.l1)) {
      return { l1: raw.l1, l2: Array.isArray(raw.l2) ? raw.l2 : [] }
    }
    if (Array.isArray(raw)) {
      return migrateFlatCollapseKeys(raw)
    }
  } catch { /* ignore */ }
  try {
    const legacy = JSON.parse(localStorage.getItem('instanceList.groupCollapse') || '[]')
    if (Array.isArray(legacy) && legacy.length) {
      return migrateFlatCollapseKeys(legacy)
    }
  } catch { /* ignore */ }
  return { l1: [], l2: [] }
}

function saveCollapseState() {
  try {
    const payload: CollapsePersist = { l1: activeGroupKeys.value, l2: activeL2Keys.value }
    localStorage.setItem(COLLAPSE_KEY, JSON.stringify(payload))
  } catch { /* ignore */ }
}

const initialCollapse = loadCollapseState()
const activeGroupKeys = ref<string[]>(initialCollapse.l1)
const activeL2Keys = ref<string[]>(initialCollapse.l2)

function onCollapseChange(keys: string | string[]) {
  activeGroupKeys.value = Array.isArray(keys) ? keys.map(String) : [String(keys)]
  saveCollapseState()
}

function onL2CollapseChange(keys: string | string[]) {
  activeL2Keys.value = Array.isArray(keys) ? keys.map(String) : [String(keys)]
  saveCollapseState()
}

function collectL1ExpandableKeys(nodes: GroupNode[]): string[] {
  return nodes.filter((g) => groupTenantCount(g) > 0).map((g) => g.key)
}

function collectL2ExpandableKeys(nodes: GroupNode[]): string[] {
  const keys: string[] = []
  for (const g of nodes) {
    if (!g.children) continue
    for (const c of g.children) {
      if (c.tenants.length > 0) keys.push(c.key)
    }
  }
  return keys
}

const allInstanceGroupsExpanded = computed(() => {
  const l1Keys = collectL1ExpandableKeys(groupedTenants.value)
  const l2Keys = collectL2ExpandableKeys(groupedTenants.value)
  return isAllGroupsExpanded(activeGroupKeys.value, l1Keys) && isAllGroupsExpanded(activeL2Keys.value, l2Keys)
})

function toggleAllInstanceGroups() {
  const l1Keys = collectL1ExpandableKeys(groupedTenants.value)
  const l2Keys = collectL2ExpandableKeys(groupedTenants.value)
  if (allInstanceGroupsExpanded.value) {
    activeGroupKeys.value = []
    activeL2Keys.value = []
  } else {
    activeGroupKeys.value = [...l1Keys]
    activeL2Keys.value = [...l2Keys]
  }
  saveCollapseState()
}

function scrollInstancePageTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

const groupedTenants = computed<GroupNode[]>(() => {
  const all = filteredTenants.value
  const gd = groupData.value
  const l1Map = new Map<string, TenantData[]>()
  for (const td of all) {
    const g1 = td.tenant.groupLevel1 || '未分组'
    const list = l1Map.get(g1) || []
    list.push(td)
    l1Map.set(g1, list)
  }
  for (const g1 of gd.level1) {
    if (!l1Map.has(g1)) l1Map.set(g1, [])
  }
  const orderedKeys: string[] = []
  for (const g1 of gd.level1) { if (l1Map.has(g1) && !orderedKeys.includes(g1)) orderedKeys.push(g1) }
  for (const k of l1Map.keys()) { if (!orderedKeys.includes(k)) orderedKeys.push(k) }

  const nodes: GroupNode[] = []
  for (const l1 of orderedKeys) {
    const items = l1Map.get(l1) || []
    const withL2 = items.filter(td => !!td.tenant.groupLevel2)
    const withoutL2 = items.filter(td => !td.tenant.groupLevel2)
    const l2Map = new Map<string, TenantData[]>()
    for (const td of withL2) {
      const list = l2Map.get(td.tenant.groupLevel2) || []
      list.push(td)
      l2Map.set(td.tenant.groupLevel2, list)
    }
    const l2Names = gd.level2[l1] || []
    for (const l2 of l2Names) { if (!l2Map.has(l2)) l2Map.set(l2, []) }
    const children: GroupNode[] = []
    for (const [l2, l2Items] of l2Map) {
      children.push({ label: l2, key: `${l1}/${l2}`, tenants: l2Items })
    }
    nodes.push({ label: l1, key: l1, children: children.length > 0 ? children : undefined, tenants: withoutL2 })
  }
  return nodes
})

const hasGroups = computed(() => {
  const gd = groupData.value
  return gd.level1.length > 0 && !(gd.level1.length === 1 && gd.level1[0] === '未分组')
})

function groupTenantCount(g: GroupNode): number {
  return g.tenants.length + (g.children?.reduce((s, c) => s + c.tenants.length, 0) || 0)
}

async function loadGroups() {
  try {
    await catalog.ensureGroups({ force: false })
  } catch {}
}

watch(searchKeyword, (kw) => {
  if (kw && hasGroups.value) {
    const matchedKeys: string[] = []
    for (const g of groupedTenants.value) {
      if (groupTenantCount(g) > 0) matchedKeys.push(g.key)
    }
    activeGroupKeys.value = matchedKeys
    saveCollapseState()
  }
})

const activeTenantData = computed(() => {
  if (!activeTenantId.value) return null
  return tenantDataList.value.find(td => td.tenant.id === activeTenantId.value) || null
})
const instanceMobileVirtualMaxHeight = computed(() => Math.max(360, Math.min(680, viewportHeight.value - 220)))
const instanceVirtualResetKey = computed(() =>
  `${activeTenantId.value}|${instancePanelRegion.value}|${createListSignature(activeTenantData.value?.instances || [], (r: any) => r.instanceId)}`,
)
function instanceRecordKey(item: unknown, index: number) {
  return String((item as any)?.instanceId ?? index)
}
const instancePanelVisible = computed({
  get: () => instancePanelOpen.value && !!activeTenantData.value,
  set: (val: boolean) => {
    if (!val) {
      instancePanelOpen.value = false
      activeTenantId.value = ''
      if (!tenantWorkspaceTransitioning.value) {
        if (tenantWorkspaceKind.value === 'instance') tenantWorkspaceKind.value = null
        clearFloatingTenantCard()
      }
    }
  },
})
type TenantWorkspaceKind = 'instance' | 'vcn' | 'storage'
const instancePanelOpen = ref(false)
const tenantWorkspaceKind = ref<TenantWorkspaceKind | null>(null)
const tenantWorkspaceTransitioning = ref(false)
const instancePanelWidth = computed(() => (isMobile.value ? '100%' : 'clamp(960px, 68vw, 1280px)'))
const tenantWorkspaceMaskStyle = computed(() =>
  isMobile.value
    ? undefined
    : {
        background: 'var(--tenant-workspace-mask-bg, rgba(15, 23, 42, 0.34))',
        backdropFilter: 'blur(8px)',
        WebkitBackdropFilter: 'blur(8px)',
      },
)
const instancePanelWrapClass = computed(() =>
  `instance-manager-drawer tenant-workspace-drawer tenant-workspace-${tenantWorkspaceKind.value || 'idle'}`,
)
const vcnPanelWrapClass = computed(() =>
  `vcn-panel-drawer tenant-workspace-drawer tenant-workspace-${tenantWorkspaceKind.value || 'idle'}`,
)

type FloatingTenantCardPhase = 'idle' | 'rolling' | 'docked'
type FloatingTenantActionKey = TenantWorkspaceKind | 'quick'
const floatingTenantActionItems: { key: FloatingTenantActionKey; label: string; icon: string }[] = [
  { key: 'instance', label: '实例管理', icon: 'ri-server-line' },
  { key: 'vcn', label: '虚拟云网络', icon: 'ri-share-line' },
  { key: 'storage', label: '存储', icon: 'ri-database-2-line' },
  { key: 'quick', label: '快捷开机', icon: 'ri-play-circle-line' },
]
const floatingTenantCard = reactive<{
  phase: FloatingTenantCardPhase
  tenant: any | null
  tenantId: string
  username: string
  tenantName: string
  region: string
  planType: string
  left: string
  top: string
  width: string
  height: string
  dx: string
  dy: string
}>({
  phase: 'idle',
  tenant: null,
  tenantId: '',
  username: '',
  tenantName: '',
  region: '',
  planType: '',
  left: '0px',
  top: '0px',
  width: '260px',
  height: '220px',
  dx: '0px',
  dy: '0px',
})
const vcnManagerEditingOverlayActive = ref(false)
const storageManagerEditingOverlayActive = ref(false)
const byoipEditingOverlayActive = ref(false)
const instanceManagerEditingOverlayActive = ref(false)
const floatingTenantCardVisible = computed(
  () =>
    !isMobile.value &&
    !vcnManagerEditingOverlayActive.value &&
    !storageManagerEditingOverlayActive.value &&
    !byoipEditingOverlayActive.value &&
    !instanceManagerEditingOverlayActive.value &&
    !quickTaskVisible.value &&
    floatingTenantCard.phase !== 'idle' &&
    !!floatingTenantCard.tenantId,
)
const floatingTenantCardStyle = computed<Record<string, string>>(() => ({
  left: floatingTenantCard.left,
  top: floatingTenantCard.top,
  width: floatingTenantCard.width,
  height: floatingTenantCard.height,
  '--tenant-float-dx': floatingTenantCard.dx,
  '--tenant-float-dy': floatingTenantCard.dy,
  '--tenant-float-duration': `${TENANT_FLOAT_DURATION_MS}ms`,
}))
const TENANT_FLOAT_DURATION_MS = 760
const TENANT_DRAWER_DELAY_MS = 220
let tenantFloatTimer: ReturnType<typeof setTimeout> | null = null
let tenantWorkspaceOpenTimer: ReturnType<typeof setTimeout> | null = null

type TenantWorkspaceOpenOptions = { dockSwitch?: boolean }

function beginTenantWorkspace(kind: TenantWorkspaceKind, tenant: any, options: TenantWorkspaceOpenOptions = {}) {
  tenantWorkspaceKind.value = kind
  if (isMobile.value) return
  if (options.dockSwitch) {
    tenantWorkspaceTransitioning.value = true
    refreshFloatingTenantCard(tenant)
  } else {
    tenantWorkspaceTransitioning.value = startFloatingTenantCard(tenant)
  }
}

function scheduleTenantWorkspaceOpen(openPanel: () => void) {
  if (tenantWorkspaceOpenTimer) window.clearTimeout(tenantWorkspaceOpenTimer)
  if (isMobile.value) {
    openPanel()
    return
  }
  if (!tenantWorkspaceTransitioning.value) {
    openPanel()
    return
  }
  tenantWorkspaceOpenTimer = window.setTimeout(() => {
    tenantWorkspaceOpenTimer = null
    openPanel()
    window.setTimeout(() => {
      tenantWorkspaceTransitioning.value = false
    }, 120)
  }, TENANT_DRAWER_DELAY_MS)
}

function isFloatingTenantSource(tenant: any) {
  const tenantId = String(tenant?.id || '')
  return !isMobile.value && floatingTenantCard.phase !== 'idle' && floatingTenantCard.tenantId === tenantId
}

function closeTenantWorkspacePanels(except: TenantWorkspaceKind) {
  if (except !== 'instance') {
    instancePanelOpen.value = false
    activeTenantId.value = ''
  }
  if (except !== 'vcn') vcnVisible.value = false
  if (except !== 'storage') storageManagerOpen.value = false
}

function findTenantCardElement(tenantId: string) {
  if (typeof document === 'undefined' || !tenantId) return null
  const cards = Array.from(document.querySelectorAll<HTMLElement>('.tenant-card[data-tenant-id]'))
  return cards.find((card) => card.dataset.tenantId === tenantId) || null
}

function desktopWorkspaceWidthPx() {
  if (typeof window === 'undefined') return 960
  return Math.min(1280, Math.max(960, window.innerWidth * 0.68))
}

function calculateFloatingTenantRect(sourceRect?: DOMRect | null) {
  if (typeof window === 'undefined') return null
  const drawerWidth = desktopWorkspaceWidthPx()
  const drawerLeft = window.innerWidth - drawerWidth
  const roomy = drawerLeft >= 320
  const gap = roomy ? 24 : 12
  const minWidth = roomy ? 220 : 168
  const availableWidth = Math.max(0, drawerLeft - gap * 2)
  const baseWidth = sourceRect?.width || Number.parseFloat(floatingTenantCard.width) || 260
  const baseHeight = sourceRect?.height || Number.parseFloat(floatingTenantCard.height) || 220
  const fallbackWidth = Math.max(minWidth, Math.min(220, window.innerWidth - gap * 2))
  const width = Math.min(roomy ? 320 : 220, Math.max(minWidth, Math.min(baseWidth, availableWidth || fallbackWidth)))
  const height = Math.max(180, Math.min(baseHeight, window.innerHeight - 96))
  const maxLeft = Math.max(gap, window.innerWidth - width - gap)
  const left = Math.max(gap, Math.min(drawerLeft - width - gap, maxLeft))
  const preferredTop = sourceRect ? sourceRect.top - 90 : Number.parseFloat(floatingTenantCard.top) || 88
  const top = Math.max(72, Math.min(window.innerHeight - height - gap, preferredTop))
  return { left, top, width, height }
}

function assignFloatingTenantCard(tenant: any, rect?: DOMRect | null, phase: FloatingTenantCardPhase = 'docked') {
  const dockRect = calculateFloatingTenantRect(rect)
  Object.assign(floatingTenantCard, {
    phase: dockRect ? phase : 'idle',
    tenant,
    tenantId: String(tenant?.id || ''),
    username: tenant?.username || tenant?.tenantName || '租户',
    tenantName: tenant?.tenantName || '',
    region: tenant?.ociRegion || '',
    planType: tenant?.planType || '',
    left: `${dockRect?.left || 0}px`,
    top: `${dockRect?.top || 0}px`,
    width: `${dockRect?.width || 260}px`,
    height: `${dockRect?.height || 220}px`,
    dx: '0px',
    dy: '0px',
  })
  return dockRect
}

function floatingTenantButtonType(action: FloatingTenantActionKey) {
  return action !== 'quick' && tenantWorkspaceKind.value === action ? 'primary' : 'default'
}

function refreshFloatingTenantCard(tenant = floatingTenantCard.tenant) {
  if (!tenant || isMobile.value) return
  const source = findTenantCardElement(String(tenant?.id || ''))
  assignFloatingTenantCard(tenant, source?.getBoundingClientRect() || null, 'docked')
}

function resolveFloatingTenantFromWorkspace() {
  if (floatingTenantCard.tenant) return floatingTenantCard.tenant
  if (tenantWorkspaceKind.value === 'instance') {
    return activeTenantData.value?.tenant || currentTenant.value || null
  }
  if (tenantWorkspaceKind.value === 'vcn') {
    return vcnTenant.value || currentTenant.value || null
  }
  if (tenantWorkspaceKind.value === 'storage') {
    const tenant = findTenantDataById(storageManagerUserId.value)?.tenant
    if (tenant) return tenant
    if (storageManagerUserId.value) {
      return {
        id: storageManagerUserId.value,
        username: storageManagerTenantName.value || '租户',
        tenantName: storageManagerTenantName.value || '',
        ociRegion: storageManagerDefaultRegion.value || '',
      }
    }
  }
  return null
}

function clearFloatingTenantCard() {
  Object.assign(floatingTenantCard, {
    phase: 'idle',
    tenant: null,
    tenantId: '',
    dx: '0px',
    dy: '0px',
  })
}

function findTenantDataById(tenantId: string) {
  return tenantDataList.value.find((td) => td.tenant?.id === tenantId) || null
}

function switchFloatingTenantPanel(kind: TenantWorkspaceKind) {
  const tenant = floatingTenantCard.tenant
  if (!tenant || isMobile.value) return
  if (tenantWorkspaceKind.value === kind) return
  if (kind === 'instance') {
    const td = findTenantDataById(String(tenant.id || ''))
    if (td) void selectTenant(td, { dockSwitch: true })
    return
  }
  if (kind === 'vcn') {
    void openVcnPanel(tenant, { dockSwitch: true })
    return
  }
  openStoragePanel(tenant, { dockSwitch: true })
}

function handleFloatingTenantAction(action: FloatingTenantActionKey) {
  if (floatingTenantCard.phase === 'rolling') return
  const tenant = floatingTenantCard.tenant
  if (!tenant) return
  if (action === 'quick') {
    openQuickTask(tenant)
    return
  }
  switchFloatingTenantPanel(action)
}

function startFloatingTenantCard(tenant: any) {
  const source = findTenantCardElement(String(tenant?.id || ''))
  if (!source || typeof window === 'undefined') return false
  const rect = source.getBoundingClientRect()
  if (rect.width <= 0 || rect.height <= 0) return false
  const dockRect = calculateFloatingTenantRect(rect)
  if (!dockRect) return false
  const targetLeft = dockRect?.left ?? Math.max(24, rect.left)
  const targetTop = dockRect?.top ?? Math.max(72, Math.min(window.innerHeight - rect.height - 24, rect.top - 90))
  const dx = targetLeft - rect.left
  const dy = targetTop - rect.top

  if (tenantFloatTimer) window.clearTimeout(tenantFloatTimer)
  Object.assign(floatingTenantCard, {
    phase: 'idle',
    tenant,
    tenantId: String(tenant?.id || ''),
    username: tenant?.username || tenant?.tenantName || '租户',
    tenantName: tenant?.tenantName || '',
    region: tenant?.ociRegion || '',
    planType: tenant?.planType || '',
    left: `${rect.left}px`,
    top: `${rect.top}px`,
    width: `${rect.width}px`,
    height: `${rect.height}px`,
    dx: `${dx}px`,
    dy: `${dy}px`,
  })
  requestAnimationFrame(() => {
    floatingTenantCard.phase = 'rolling'
    tenantFloatTimer = window.setTimeout(() => {
      if (floatingTenantCard.tenantId === String(tenant?.id || '') && dockRect) {
        Object.assign(floatingTenantCard, {
          phase: 'docked',
          left: `${dockRect.left}px`,
          top: `${dockRect.top}px`,
          width: `${dockRect.width}px`,
          height: `${dockRect.height}px`,
          dx: '0px',
          dy: '0px',
        })
      }
      tenantFloatTimer = null
    }, TENANT_FLOAT_DURATION_MS)
  })
  return true
}

const instancePanelRegion = ref('')
const instanceRegionOptions = ref<{ label: string; value: string }[]>([])
const instanceSubscribedRegionsLoading = ref(false)

const vcnPanelRegion = ref('')
const vcnRegionOptions = ref<{ label: string; value: string }[]>([])
const vcnSubscribedRegionsLoading = ref(false)

function panelRegionMemKey(prefix: string, tenant: any) {
  return `${prefix}:${tenant?.id || ''}`
}

function loadPanelRegionFromLs(prefix: string, tenant: any, fallback: string) {
  try {
    const v = localStorage.getItem(panelRegionMemKey(prefix, tenant)) || ''
    return v || fallback || ''
  } catch {
    return fallback || ''
  }
}

function savePanelRegionLs(prefix: string, tenant: any, region: string) {
  try {
    if (tenant?.id) localStorage.setItem(panelRegionMemKey(prefix, tenant), region || '')
  } catch {}
}

function instanceListRegion(td: TenantData) {
  return (instancePanelRegion.value?.trim() || td.tenant.ociRegion || '').trim()
}

function instanceListCacheKey(td: TenantData, region: string) {
  return ['instanceList', 'instances', td.tenant.id || '', region || ''] as const
}

function getInstanceListCache(td: TenantData, region: string) {
  const key = instanceListCacheKey(td, region)
  const rows = appQueryCache.get<any[]>(key)
  if (!rows) return null
  return {
    rows,
    fetchedAt: appQueryCache.getUpdatedAt(key),
  }
}

function detailOciRegion(): string | undefined {
  const r = currentInstance.value?.region
  return r && String(r).trim() ? String(r).trim() : undefined
}

function instanceDetailRegionParam(): { region?: string } {
  const r =
    (detailOciRegion() || '').trim() ||
    (currentTenant.value?.ociRegion && String(currentTenant.value.ociRegion).trim()) ||
    ''
  return r ? { region: r } : {}
}

function instanceDetailScopeParam(): { region?: string; compartmentId?: string } {
  const base = instanceDetailRegionParam()
  const compartmentId = currentInstance.value?.compartmentId
  const cid = compartmentId && String(compartmentId).trim() ? String(compartmentId).trim() : ''
  return cid ? { ...base, compartmentId: cid } : base
}

function vcnReservedIpRegionParam(): { region?: string } {
  const r = (vcnPanelRegion.value?.trim() || vcnTenant.value?.ociRegion || '').trim()
  return r ? { region: r } : {}
}

async function prefetchSubscribedRegions(
  userId: string,
  current: string,
  assign: (ids: string[]) => void,
  loadingRef: { value: boolean },
) {
  if (!userId) return
  loadingRef.value = true
  try {
    const res = await listStorageRegions({ id: userId })
    const raw = (res.data || []) as string[]
    const ids = [...new Set(raw)].sort()
    if (ids.length === 0) {
      assign(current ? [current] : [])
      return
    }
    if (current && !ids.includes(current)) ids.unshift(current)
    assign(ids)
  } catch {
    assign(current ? [current] : [])
  } finally {
    loadingRef.value = false
  }
}

async function selectTenant(td: TenantData, options: TenantWorkspaceOpenOptions = {}) {
  const tenantId = td.tenant.id
  beginTenantWorkspace('instance', td.tenant, options)
  closeTenantWorkspacePanels('instance')
  activeTenantId.value = td.tenant.id
  if (!isMobile.value) instancePanelOpen.value = false
  scheduleTenantWorkspaceOpen(() => {
    if (activeTenantId.value === tenantId && tenantWorkspaceKind.value === 'instance') {
      instancePanelOpen.value = true
    }
  })
  const def = td.tenant.ociRegion || ''
  instancePanelRegion.value = loadPanelRegionFromLs('instancePanel.region', td.tenant, def) || def
  instanceRegionOptions.value = instancePanelRegion.value
    ? [{ label: instancePanelRegion.value, value: instancePanelRegion.value }]
    : []
  savePanelRegionLs('instancePanel.region', td.tenant, instancePanelRegion.value)
  await loadTenantInstances(td)
  if (activeTenantId.value !== tenantId) return
  await prefetchSubscribedRegions(
    tenantId,
    instancePanelRegion.value,
    (ids) => {
      if (activeTenantId.value !== tenantId) return
      instanceRegionOptions.value = ids.map((x) => ({ label: x, value: x }))
    },
    instanceSubscribedRegionsLoading,
  )
}

function refreshActiveTenantInstances() {
  const td = activeTenantData.value
  if (!td) return
  void loadTenantInstances(td, true)
}

function onInstancePanelRegionUserChange() {
  const td = activeTenantData.value
  if (!td?.tenant) return
  savePanelRegionLs('instancePanel.region', td.tenant, instancePanelRegion.value || '')
  loadTenantInstances(td)
}

const drawerVisible = ref(false)
const activeTab = ref('info')
const bootVolumePanelRef = ref<any>(null)
const blockStoragePanelRef = ref<any>(null)
const trafficPanelRef = ref<any>(null)
const securityPanelRef = ref<any>(null)
const networkPanelRef = ref<any>(null)
const networkDetailPanelRef = ref<any>(null)
const bootVolumeOverlayActive = ref(false)
const blockStorageOverlayActive = ref(false)
const trafficOverlayActive = ref(false)
const securityOverlayActive = ref(false)
const currentDetailRegion = computed(() => instanceDetailRegionParam().region)

const changeIpLoading = ref(false)

const instanceInfoLoading = ref(false)

const reservedIps = ref<any[]>([])
const reservedIpListLoading = ref(false)
const ociReservedIps = computed(() => reservedIps.value.filter((r: any) => !r.publicIpPoolId))
const createRipVisible = ref(false)
const createRipLoading = ref(false)
const createRipName = ref('')

const editInstanceVisible = ref(false)
const editInstanceLoading = ref(false)
const editInstanceForm = reactive({ displayName: '' })

const shapeEditLoading = ref(false)
const shapeEditSaving = ref(false)
const shapeEditTask = ref<ShapeEditTaskStatus | null>(null)
const shapeEditTaskActionLoading = ref(false)
const shapeEditTaskAlertRef = ref<any>(null)
let shapeEditTaskPollTimer: ReturnType<typeof setInterval> | null = null
const shapeEditOptions = ref<any[]>([])
const shapeForm = reactive({ shape: '' as string, ocpus: 1 as number, memoryInGBs: 6 as number })

const shapeEditSelectOptions = computed(() =>
  shapeEditOptions.value.map((s: any) => ({
    value: s.shape,
    label: `${s.shape}${s.processorDescription ? ` — ${s.processorDescription}` : ''}`,
  })),
)

const shapeEditSelectedMeta = computed(() =>
  shapeEditOptions.value.find((s: any) => s.shape === shapeForm.shape) ?? null,
)

const shapeEditFlexLimits = computed(() =>
  resolveShapeEditFlexLimits(shapeForm.shape, shapeEditSelectedMeta.value),
)
const shapeOcpuMin = computed(() => shapeEditFlexLimits.value.minOcpus)
const shapeOcpuMax = computed(() => shapeEditFlexLimits.value.maxOcpus)
const shapeMemoryMin = computed(() => shapeEditFlexLimits.value.minMemory)
const shapeMemoryMax = computed(() => shapeEditFlexLimits.value.maxMemory)
const shapeOcpuLabel = computed(() =>
  formatShapeResourceRangeLabel('OCPU', shapeOcpuMin.value, shapeOcpuMax.value),
)
const shapeMemoryLabel = computed(() =>
  formatShapeResourceRangeLabel('内存 GB', shapeMemoryMin.value, shapeMemoryMax.value),
)
const shapeEditTaskActive = computed(() => !!shapeEditTask.value && !shapeEditTask.value.terminal)
const shapeEditTaskPaused = computed(() => shapeEditTask.value?.status === 'PAUSED')
const shapeEditTaskPercent = computed(() => {
  const task = shapeEditTask.value
  if (!task) return 0
  if (task.status === 'SUCCESS') return 100
  if (task.status === 'FAILED' || task.status === 'STOPPED') return 100
  if (!task.maxRetries) return 0
  return Math.min(99, Math.round((task.retryCount / task.maxRetries) * 100))
})
const shapeEditTaskProgressStatus = computed(() => {
  const status = shapeEditTask.value?.status
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED' || status === 'STOPPED') return 'exception'
  return 'active'
})
const shapeEditTaskTitle = computed(() => {
  const status = shapeEditTask.value?.status
  if (status === 'SUCCESS') return '形状变更成功'
  if (status === 'FAILED') return '形状变更失败'
  if (status === 'STOPPED') return '后台重试已停止'
  if (status === 'PAUSED') return '后台重试已暂停'
  return '检测到缺货，后台自动重试中'
})
const shapeEditTaskAlertType = computed(() => {
  const status = shapeEditTask.value?.status
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED' || status === 'STOPPED') return 'error'
  return 'warning'
})
const shapeEditTaskMessage = computed(() => {
  const task = shapeEditTask.value
  if (!task) return ''
  if (task.status === 'RUNNING') return `重试中 (第 ${task.retryCount} 次)`
  if (task.status === 'PAUSED') return '已暂停'
  if (task.status === 'FAILED') return task.message || '失败'
  return task.message || '等待中...'
})

const showForceA2ToA1Button = computed(
  () => currentInstance.value?.shape === 'VM.Standard.A2.Flex',
)

function clampShapeNum(v: number, min?: number | null, max?: number | null) {
  let n = v
  if (min != null && n < min) n = min
  if (max != null && n > max) n = max
  return n
}

function filterShapeOption(input: string, option: any) {
  const label = option?.label ?? option?.value ?? ''
  return String(label).toLowerCase().includes(input.toLowerCase())
}

function onShapeFormShapeChange() {
  const meta = shapeEditSelectedMeta.value
  if (!meta?.isFlexible) return
  const lim = resolveShapeEditFlexLimits(shapeForm.shape, meta)
  const inst = currentInstance.value
  const flexDefault = getFlexShapeSpec(shapeForm.shape)
  if (inst?.shape === shapeForm.shape) {
    const o = inst.ocpus ?? flexDefault?.ocpus ?? lim.minOcpus
    const m = inst.memoryInGBs ?? flexDefault?.memory ?? lim.minMemory
    shapeForm.ocpus = clampShapeNum(Number(o) || 1, lim.minOcpus, lim.maxOcpus)
    shapeForm.memoryInGBs = clampShapeNum(Number(m) || 6, lim.minMemory, lim.maxMemory)
  } else if (flexDefault) {
    shapeForm.ocpus = flexDefault.ocpus
    shapeForm.memoryInGBs = flexDefault.memory
  } else {
    shapeForm.ocpus = clampShapeNum(Number(meta.ocpuMin) || 1, lim.minOcpus, lim.maxOcpus)
    shapeForm.memoryInGBs = clampShapeNum(Number(meta.memoryMinInGBs) || 6, lim.minMemory, lim.maxMemory)
  }
}

async function loadShapeEditOptions(force = false) {
  if (!currentInstance.value || !currentTenant.value) return
  shapeEditLoading.value = true
  try {
    const res = await getShapesForInstance({
      id: currentTenant.value.id,
      instanceId: currentInstance.value.instanceId,
      ...instanceDetailRegionParam(),
      force,
    })
    shapeEditOptions.value = res.data || []
    const cur = currentInstance.value.shape
    shapeForm.shape = shapeEditOptions.value.some((s: any) => s.shape === cur) ? cur : (shapeEditOptions.value[0]?.shape ?? '')
    shapeForm.ocpus = currentInstance.value.ocpus ?? 1
    shapeForm.memoryInGBs = currentInstance.value.memoryInGBs ?? 6
    onShapeFormShapeChange()
  } catch (e: any) {
    message.error(e?.message || '加载 Shape 列表失败')
  } finally {
    shapeEditLoading.value = false
  }
}

function isShapeEditTaskStatusData(data: any): data is ShapeEditTaskStatus {
  return !!data && typeof data.taskId === 'string' && typeof data.status === 'string'
}

function stopShapeEditTaskPolling() {
  if (shapeEditTaskPollTimer != null) {
    clearInterval(shapeEditTaskPollTimer)
    shapeEditTaskPollTimer = null
  }
}

function startShapeEditTaskPolling(taskId: string) {
  if (!taskId) return
  stopShapeEditTaskPolling()
  shapeEditTaskPollTimer = setInterval(() => {
    void pollShapeEditTask(taskId)
  }, 2000)
}

function revealShapeEditTaskAlert() {
  activeTab.value = 'shape'
  void nextTick(() => {
    const el = shapeEditTaskAlertRef.value?.$el ?? shapeEditTaskAlertRef.value
    el?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' })
  })
}

function applyShapeEditResult(result?: Record<string, any>) {
  if (!result || !currentInstance.value || !currentTenant.value) return
  const inst = currentInstance.value
  if (result.shape) inst.shape = result.shape
  if (result.ocpus != null) inst.ocpus = result.ocpus
  if (result.memoryInGBs != null) inst.memoryInGBs = result.memoryInGBs
  if (result.name) inst.name = result.name
  void loadShapeEditOptions()
  const td = tenantDataList.value.find(t => t.tenant.id === currentTenant.value.id)
  if (td) scheduleReload(() => loadTenantInstances(td, { force: true }), 3000)
}

function handleShapeEditTaskStatus(status: ShapeEditTaskStatus) {
  const previousStatus = shapeEditTask.value?.status
  const previousTaskId = shapeEditTask.value?.taskId
  shapeEditTask.value = status
  if (previousTaskId !== status.taskId) {
    revealShapeEditTaskAlert()
  }
  if (!status.terminal) return

  stopShapeEditTaskPolling()
  if (previousStatus === status.status) return

  if (status.status === 'SUCCESS') {
    applyShapeEditResult(status.result)
    message.success('形状变更成功')
  } else if (status.status === 'FAILED') {
    message.error(status.message || '形状变更失败')
  } else if (status.status === 'STOPPED') {
    message.warning(status.message || '后台重试已停止')
  }
}

async function pollShapeEditTask(taskId: string) {
  try {
    const res = await getShapeEditTaskStatus(taskId)
    if (!isShapeEditTaskStatusData(res.data)) return
    if (shapeEditTask.value?.taskId && shapeEditTask.value.taskId !== taskId) return
    handleShapeEditTaskStatus(res.data)
  } catch (e: any) {
    stopShapeEditTaskPolling()
    if (shapeEditTask.value && !shapeEditTask.value.terminal) {
      shapeEditTask.value = {
        ...shapeEditTask.value,
        status: 'FAILED',
        pending: false,
        terminal: true,
        message: e?.message || '后台任务状态查询失败',
      }
    }
    message.error(e?.message || '后台任务状态查询失败')
  }
}

async function handleToggleShapeEditTaskPause() {
  const task = shapeEditTask.value
  if (!task || task.terminal) return
  shapeEditTaskActionLoading.value = true
  try {
    const res = shapeEditTaskPaused.value
      ? await resumeShapeEditTask(task.taskId)
      : await pauseShapeEditTask(task.taskId)
    if (isShapeEditTaskStatusData(res.data)) {
      handleShapeEditTaskStatus(res.data)
      if (!res.data.terminal && !shapeEditTaskPollTimer) {
        startShapeEditTaskPolling(res.data.taskId)
      }
    }
    message.success(shapeEditTaskPaused.value ? '后台重试已暂停' : '后台重试已恢复')
  } catch (e: any) {
    message.error(e?.message || '操作后台任务失败')
  } finally {
    shapeEditTaskActionLoading.value = false
  }
}

async function handleStopShapeEditTask() {
  const task = shapeEditTask.value
  if (!task || task.terminal) return
  shapeEditTaskActionLoading.value = true
  try {
    const res = await stopShapeEditTask(task.taskId)
    if (isShapeEditTaskStatusData(res.data)) {
      handleShapeEditTaskStatus(res.data)
    }
  } catch (e: any) {
    message.error(e?.message || '停止后台任务失败')
  } finally {
    shapeEditTaskActionLoading.value = false
  }
}

function stopCurrentShapeEditTaskWithKeepalive() {
  const task = shapeEditTask.value
  if (!task || task.terminal) return
  try {
    const token = localStorage.getItem('token')?.trim()
    const headers: Record<string, string> = {}
    if (token) headers.Authorization = token.startsWith('Bearer ') ? token : `Bearer ${token}`
    void fetch(`/api/oci/instance/shapeEditTask/${encodeURIComponent(task.taskId)}/stop`, {
      method: 'POST',
      keepalive: true,
      credentials: 'include',
      headers,
    })
  } catch {}
}

function handleShapeEditBeforeUnload() {
  stopCurrentShapeEditTaskWithKeepalive()
}

async function stopCurrentShapeEditTaskSilently() {
  const task = shapeEditTask.value
  if (!task || task.terminal) return
  stopShapeEditTaskPolling()
  try {
    await stopShapeEditTask(task.taskId)
  } catch {}
}

async function handleApplyShapeEdit() {
  if (!currentInstance.value || !currentTenant.value || !shapeForm.shape) return
  const meta = shapeEditSelectedMeta.value
  const inst = currentInstance.value
  const payload: Record<string, unknown> = {
    id: currentTenant.value.id,
    instanceId: inst.instanceId,
    ...instanceDetailRegionParam(),
  }
  let changed = false
  if (shapeForm.shape !== inst.shape) {
    payload.shape = shapeForm.shape
    changed = true
  }
  if (meta?.isFlexible) {
    const ocpuChanged = shapeForm.ocpus !== inst.ocpus
    const memChanged = shapeForm.memoryInGBs !== inst.memoryInGBs
    if (changed || ocpuChanged || memChanged) {
      payload.ocpus = shapeForm.ocpus
      payload.memoryInGBs = shapeForm.memoryInGBs
      changed = true
    }
  } else if (!changed) {
    message.info('未检测到变更')
    return
  }
  if (!changed) {
    message.info('未检测到变更')
    return
  }
  shapeEditSaving.value = true
  try {
    const res = await updateInstance(payload as any)
    if (isShapeEditTaskStatusData(res.data)) {
      handleShapeEditTaskStatus(res.data)
      if (!res.data.terminal) {
        startShapeEditTaskPolling(res.data.taskId)
      }
      message.warning(res.data.message || '检测到缺货，将在后台自动重试')
      return
    }
    message.success('形状变更已提交')
    if (res.data?.shape) inst.shape = res.data.shape
    if (res.data?.ocpus != null) inst.ocpus = res.data.ocpus
    if (res.data?.memoryInGBs != null) inst.memoryInGBs = res.data.memoryInGBs
    if (res.data?.name) inst.name = res.data.name
    const td = tenantDataList.value.find(t => t.tenant.id === currentTenant.value.id)
    if (td) scheduleReload(() => loadTenantInstances(td, { force: true }), 3000)
  } catch (e: any) {
    message.error(e?.message || '形状变更失败')
  } finally {
    shapeEditSaving.value = false
  }
}

const forceA2ModalVisible = ref(false)
const forceA2Loading = ref(false)
const forceA2Q = reactive({
  trial: undefined as boolean | undefined,
  a2Shape: undefined as boolean | undefined,
  risk: undefined as boolean | undefined,
})
const forceA2AllYes = computed(
  () => forceA2Q.trial === true && forceA2Q.a2Shape === true && forceA2Q.risk === true,
)

function resetForceA2Modal() {
  forceA2Q.trial = undefined
  forceA2Q.a2Shape = undefined
  forceA2Q.risk = undefined
}

function openForceA2ToA1Modal() {
  if (!currentInstance.value) return
  resetForceA2Modal()
  forceA2ModalVisible.value = true
}

async function handleForceA2ToA1Confirm() {
  if (!forceA2AllYes.value) {
    message.warning('请三项均选择「是」后再执行')
    return Promise.reject()
  }
  if (!currentInstance.value || !currentTenant.value) return Promise.reject()
  forceA2Loading.value = true
  try {
    const res = await forceA2ToA1({
      id: currentTenant.value.id,
      instanceId: currentInstance.value.instanceId,
      ...instanceDetailRegionParam(),
    })
    message.success('已成功转为A1，稍后刷新页面后在基本信息中查看')
    const inst = currentInstance.value
    if (res.data?.shape) inst.shape = res.data.shape
    if (res.data?.ocpus != null) inst.ocpus = res.data.ocpus
    if (res.data?.memoryInGBs != null) inst.memoryInGBs = res.data.memoryInGBs
    forceA2ModalVisible.value = false
    resetForceA2Modal()
    await loadShapeEditOptions()
    const td = tenantDataList.value.find(t => t.tenant.id === currentTenant.value.id)
    if (td) scheduleReload(() => loadTenantInstances(td, { force: true }), 3000)
  } catch (e: any) {
    const msg = String(e?.message || '')
    if (msg.includes('当前实例 Shape 不是') && msg.includes('请检查当前 Shape')) {
      Modal.error({ title: '无法执行强改', content: msg, okText: '知道了' })
    } else {
      message.error('本次更改失败，您可再次尝试！')
    }
    return Promise.reject()
  } finally {
    forceA2Loading.value = false
  }
}

const {
  quickTaskVisible,
  quickTaskLoading,
  quickTaskTenant,
  quickTaskShapes,
  quickTaskShapesLoading,
  quickTaskForm,
  quickTaskPopupContainer,
  quickTaskBmLocked,
  quickTaskSavedRootPassword,
  quickTaskSavedSshPublicKey,
  quickTaskShapeLimits,
  quickTaskOcpuLabel,
  quickTaskMemoryLabel,
  quickDenseIoTiers,
  quickDenseIoTierKey,
  formatDenseIoTierLabel,
  denseIoFlexTierKey,
  openQuickTask,
  warnQuickTaskCredentialMissing,
  updateQuickTaskOcpus,
  updateQuickTaskMemory,
  clampQuickTaskResources,
  snapQuickTaskBootVpus,
  handleQuickTask,
} = useQuickTask()

const consoleLoading = ref(false)
const consoleData = ref<any>(null)

async function handleCreateConsole() {
  if (!currentInstance.value || !currentTenant.value) return
  consoleLoading.value = true
  try {
    const res = await createConsoleConnection({
      id: currentTenant.value.id,
      instanceId: currentInstance.value.instanceId,
      ...instanceDetailRegionParam(),
    })
    consoleData.value = res.data
    message.success('控制台连接已创建')
    openConsoleWebSSH()
  } catch (e: any) {
    message.error(e?.message || '创建控制台连接失败')
  } finally {
    consoleLoading.value = false
  }
}

function openConsoleWebSSH() {
  if (!consoleData.value?.connectionId) return
  const label = currentInstance.value?.displayName || currentInstance.value?.instanceId || 'Serial Console'
  const params = new URLSearchParams({
    console: '1',
    connectionId: consoleData.value.connectionId,
    label,
  })
  if (currentTenant.value?.id != null) params.set('userId', String(currentTenant.value.id))
  if (currentInstance.value?.instanceId) params.set('instanceId', currentInstance.value.instanceId)
  const region = instanceDetailRegionParam().region
  if (region) params.set('region', region)
  if (currentInstance.value?.state) params.set('state', currentInstance.value.state)
  window.open('/webssh/index.html#' + params.toString(), '_blank')
}

async function handleDeleteConsole() {
  if (!consoleData.value || !currentTenant.value) return
  consoleLoading.value = true
  try {
    await deleteConsoleConnection({
      id: currentTenant.value.id,
      connectionId: consoleData.value.connectionId,
      ...instanceDetailRegionParam(),
    })
    consoleData.value = null
    message.success('控制台连接已断开')
  } catch (e: any) {
    message.error(e?.message || '断开连接失败')
  } finally {
    consoleLoading.value = false
  }
}

const vcnVisible = ref(false)
const vcnListLoading = ref(false)
const vcnTenant = ref<any>(null)
const vcnList = ref<any[]>([])

const vcnManagerOpen = ref(false)
const vcnManagerUserId = ref('')
const vcnManagerVcn = ref<any>(null)
const vcnManagerOciRegion = ref('')
function handleVcnManagerEditingOverlayChange(active: boolean) {
  vcnManagerEditingOverlayActive.value = active
}
function handleByoipEditingOverlayChange(active: boolean) {
  byoipEditingOverlayActive.value = active
}
function openVcnManager(tenantId: string, vcn: any) {
  vcnManagerUserId.value = tenantId
  vcnManagerVcn.value = vcn
  const fromVcn = vcn?.region && String(vcn.region).trim()
  const fromInstance = currentInstance.value?.region && String(currentInstance.value.region).trim()
  vcnManagerOciRegion.value =
    fromVcn ||
    fromInstance ||
    (vcnVisible.value ? (vcnPanelRegion.value?.trim() || '') : '') ||
    (currentTenant.value?.ociRegion && String(currentTenant.value.ociRegion).trim()) ||
    ''
  vcnManagerOpen.value = true
}

function openDetailVcnManager(vcn: any) {
  if (!currentTenant.value) return
  openVcnManager(currentTenant.value.id, vcn)
}

watch(vcnManagerOpen, open => {
  if (!open) vcnManagerEditingOverlayActive.value = false
})

async function onVcnManagerChanged() {
  if (!vcnVisible.value || !vcnTenant.value) {
    networkPanelRef.value?.loadVcns?.(true)
    return
  }
  vcnListLoading.value = true
  try {
    const reg = (vcnPanelRegion.value?.trim() || vcnTenant.value.ociRegion || '').trim()
    const res = await getVcns({ id: vcnTenant.value.id, region: reg, force: true })
    vcnList.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '刷新 VCN 列表失败')
  } finally {
    vcnListLoading.value = false
  }
}

const storageManagerOpen = ref(false)
const storageManagerUserId = ref('')
const storageManagerTenantName = ref('')
const storageManagerDefaultRegion = ref('')
function handleStorageManagerEditingOverlayChange(active: boolean) {
  storageManagerEditingOverlayActive.value = active
}
watch(vcnVisible, (open) => {
  if (!open) byoipEditingOverlayActive.value = false
  if (!open && tenantWorkspaceKind.value === 'vcn' && !tenantWorkspaceTransitioning.value) {
    tenantWorkspaceKind.value = null
    clearFloatingTenantCard()
  }
})
watch(storageManagerOpen, (open) => {
  if (!open) storageManagerEditingOverlayActive.value = false
  if (!open && tenantWorkspaceKind.value === 'storage' && !tenantWorkspaceTransitioning.value) {
    tenantWorkspaceKind.value = null
    clearFloatingTenantCard()
  }
})
function openStoragePanel(tenant: any, options: TenantWorkspaceOpenOptions = {}) {
  beginTenantWorkspace('storage', tenant, options)
  closeTenantWorkspacePanels('storage')
  storageManagerUserId.value = tenant.id
  storageManagerTenantName.value = tenant.username || tenant.tenantName || ''
  storageManagerDefaultRegion.value = tenant.ociRegion || ''
  if (!isMobile.value) storageManagerOpen.value = false
  scheduleTenantWorkspaceOpen(() => {
    if (tenantWorkspaceKind.value === 'storage' && storageManagerUserId.value === tenant.id) {
      storageManagerOpen.value = true
    }
  })
}

async function openVcnPanel(tenant: any, options: TenantWorkspaceOpenOptions = {}) {
  beginTenantWorkspace('vcn', tenant, options)
  closeTenantWorkspacePanels('vcn')
  vcnTenant.value = tenant
  vcnList.value = []
  reservedIps.value = []
  currentTenant.value = tenant
  const def = tenant.ociRegion || ''
  vcnPanelRegion.value = loadPanelRegionFromLs('vcnPanel.region', tenant, def) || def
  vcnRegionOptions.value = vcnPanelRegion.value
    ? [{ label: vcnPanelRegion.value, value: vcnPanelRegion.value }]
    : []
  savePanelRegionLs('vcnPanel.region', tenant, vcnPanelRegion.value)
  if (!isMobile.value) vcnVisible.value = false
  scheduleTenantWorkspaceOpen(() => {
    if (tenantWorkspaceKind.value === 'vcn' && vcnTenant.value?.id === tenant.id) {
      vcnVisible.value = true
    }
  })
  vcnListLoading.value = true
  try {
    const reg = (vcnPanelRegion.value?.trim() || tenant.ociRegion || '').trim()
    const res = await getVcns({ id: tenant.id, region: reg })
    vcnList.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载 VCN 失败')
  } finally {
    vcnListLoading.value = false
  }
  void prefetchSubscribedRegions(
    tenant.id,
    vcnPanelRegion.value,
    (ids) => {
      vcnRegionOptions.value = ids.map((x) => ({ label: x, value: x }))
    },
    vcnSubscribedRegionsLoading,
  )
  loadReservedIps()
}

async function onVcnPanelRegionUserChange() {
  if (!vcnVisible.value || !vcnTenant.value) return
  savePanelRegionLs('vcnPanel.region', vcnTenant.value, vcnPanelRegion.value || '')
  vcnListLoading.value = true
  try {
    const reg = (vcnPanelRegion.value?.trim() || vcnTenant.value.ociRegion || '').trim()
    const res = await getVcns({ id: vcnTenant.value.id, region: reg, force: true })
    vcnList.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载 VCN 失败')
  } finally {
    vcnListLoading.value = false
  }
  loadReservedIps()
}

function onByoipChanged() {
  loadReservedIps()
}

async function loadAllTenants(force = false) {
  globalLoading.value = true
  try {
    await catalog.ensureTenants({ force })
    const records = catalog.tenants
    const existingMap = new Map(tenantDataList.value.map(td => [td.tenant.id, td]))
    tenantDataList.value = records.map((t: any) => {
      const existing = existingMap.get(t.id)
      return existing ? { ...existing, tenant: t } : { tenant: t, instances: [], loading: false, collapsed: false }
    })
  } catch (e: any) {
    message.error(e?.message || '加载租户失败')
  } finally {
    globalLoading.value = false
  }
}

async function loadTenantInstances(td: TenantData, options: LoadTenantInstancesOptions | boolean = {}) {
  const opts = typeof options === 'boolean'
    ? { force: options, notify: options }
    : options
  const force = opts.force === true
  const notify = opts.notify === true
  const reg = instanceListRegion(td)
  const cached = getInstanceListCache(td, reg)

  if (cached) {
    td.instances = cached.rows
    if (!force && Date.now() - cached.fetchedAt < INSTANCE_LIST_CACHE_TTL_MS) {
      return
    }
  }

  td.loading = true
  try {
    const rows = await appQueryCache.fetch(
      instanceListCacheKey(td, reg),
      async () => {
        const res = await getInstanceList({ id: td.tenant.id, region: reg, force })
        return res.data || []
      },
      { staleMs: INSTANCE_LIST_CACHE_TTL_MS, force },
    )

    const sameVisibleRegion = activeTenantId.value !== td.tenant.id || instanceListRegion(td) === reg
    if (!sameVisibleRegion) return

    td.instances = rows
    if (currentTenant.value?.id === td.tenant.id && currentInstance.value?.instanceId) {
      const fresh = rows.find((i: any) => i.instanceId === currentInstance.value.instanceId)
      if (fresh) currentInstance.value = { ...currentInstance.value, ...fresh }
    }
    if (notify) message.success('实例列表已刷新')
  } catch (e: any) {
    if (notify) {
      message.error(e?.message || '刷新实例列表失败')
    } else if (!cached) {
      td.instances = []
    }
  } finally {
    td.loading = false
  }
}

function onTabChange(key: string) {
  if (key === 'shape') loadShapeEditOptions()
}

function openDetail(tenant: any, record: any) {
  void stopCurrentShapeEditTaskSilently()
  shapeEditTask.value = null
  currentTenant.value = tenant
  currentInstance.value = record
  activeTab.value = 'info'
  bootVolumePanelRef.value?.reset?.()
  blockStoragePanelRef.value?.reset?.()
  trafficPanelRef.value?.reset?.()
  securityPanelRef.value?.reset?.()
  networkPanelRef.value?.reset?.()
  networkDetailPanelRef.value?.reset?.()
  bootVolumeOverlayActive.value = false
  blockStorageOverlayActive.value = false
  trafficOverlayActive.value = false
  securityOverlayActive.value = false
  consoleData.value = null
  shapeEditOptions.value = []
  shapeForm.shape = ''
  drawerVisible.value = true
}

async function handleAction(tenant: any, record: any, action: string) {
  actionLoading[record.instanceId] = true
  try {
    const reg =
      (record.region && String(record.region).trim()) ||
      (instancePanelRegion.value?.trim() || tenant.ociRegion || '').trim()
    await updateInstanceState({ id: tenant.id, instanceId: record.instanceId, action, region: reg })
    message.success('操作已提交')
    const td = tenantDataList.value.find(t => t.tenant.id === tenant.id)
    if (td) scheduleReload(() => loadTenantInstances(td, { force: true }), 3000)
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    actionLoading[record.instanceId] = false
  }
}

const INSTANCE_ACTION_LABELS: Record<string, string> = {
  START: '启动',
  SOFTRESET: '重启',
  RESET: '断电重启',
  SOFTSTOP: '暂停',
}

function onInstanceMenuClick(record: any, key: string) {
  if (!activeTenantData.value) return
  const tenant = activeTenantData.value.tenant
  if (key === 'TERMINATE') {
    openTerminateVerify(tenant, record)
    return
  }
  const label = INSTANCE_ACTION_LABELS[key] || key
  const danger = key === 'RESET' || key === 'SOFTSTOP'
  instanceManagerConfirmOverlayActive.value = true
  Modal.confirm({
    title: `确定${label}实例？`,
    content: `目标实例：${record.name || record.instanceId}`,
    okText: '确定',
    okButtonProps: danger ? { danger: true } : undefined,
    cancelText: '取消',
    zIndex: INSTANCE_CONFIRM_MODAL_Z_INDEX,
    wrapClassName: INSTANCE_CONFIRM_MODAL_WRAP_CLASS,
    onOk: () => handleAction(tenant, record, key),
    afterClose: () => {
      instanceManagerConfirmOverlayActive.value = false
    },
  })
}

function stopCurrentDetailInstance() {
  if (!currentTenant.value || !currentInstance.value) return
  return handleAction(currentTenant.value, currentInstance.value, 'STOP')
}

function onBootVolumeUpdated() {
  if (activeTab.value === 'blockVolume') blockStoragePanelRef.value?.loadBlockVolumes?.()
}

const pendingTimers = new Set<any>()
function scheduleReload(fn: () => void, delay: number) {
  const t = setTimeout(() => {
    pendingTimers.delete(t)
    try { fn() } catch {}
  }, delay)
  pendingTimers.add(t)
}

const verifyModalVisible = ref(false)
const verifyCode = ref('')
const verifyLoading = ref(false)
const verifySending = ref(false)
const deleteBootVolume = ref(true)
const instanceManagerConfirmOverlayActive = ref(false)
const instanceManagerModalOverlayActive = computed(() =>
  trafficOverlayActive.value ||
  securityOverlayActive.value ||
  bootVolumeOverlayActive.value ||
  blockStorageOverlayActive.value ||
  createRipVisible.value ||
  editInstanceVisible.value ||
  forceA2ModalVisible.value ||
  verifyModalVisible.value ||
  instanceManagerConfirmOverlayActive.value,
)

watch(
  instanceManagerModalOverlayActive,
  (active) => {
    instanceManagerEditingOverlayActive.value = active
  },
  { immediate: true },
)

async function openTerminateVerify(tenant: any, record: any) {
  currentTenant.value = tenant
  currentInstance.value = record
  verifyCode.value = ''
  deleteBootVolume.value = true
  verifySending.value = true
  try {
    await sendVerifyCode('terminate')
    message.success('验证码已发送至 Telegram')
    verifyModalVisible.value = true
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
  } finally {
    verifySending.value = false
  }
}

async function resendVerifyCode(action: string) {
  verifySending.value = true
  try {
    await sendVerifyCode(action)
    message.success('验证码已重新发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    verifySending.value = false
  }
}

function resendTerminateVerifyCode() {
  return resendVerifyCode('terminate')
}

async function handleTerminateWithCode() {
  if (!verifyCode.value || verifyCode.value.length !== 6) {
    message.warning('请输入6位验证码')
    return
  }
  verifyLoading.value = true
  try {
    await terminateInstance({
      id: currentTenant.value.id,
      instanceId: currentInstance.value.instanceId,
      verifyCode: verifyCode.value,
      preserveBootVolume: !deleteBootVolume.value,
      ...instanceDetailRegionParam(),
    })
    message.success('实例已终止')
    verifyModalVisible.value = false
    drawerVisible.value = false
    const td = tenantDataList.value.find(t => t.tenant.id === currentTenant.value.id)
    if (td) scheduleReload(() => loadTenantInstances(td, { force: true }), 3000)
  } catch (e: any) {
    message.error(e?.message || '终止失败')
  } finally {
    verifyLoading.value = false
  }
}

async function handleChangeIp() {
  if (!currentInstance.value || !currentTenant.value) return
  changeIpLoading.value = true
  try {
    await changeIp({
      id: currentTenant.value.id,
      instanceId: currentInstance.value.instanceId,
      ...instanceDetailScopeParam(),
    })
    message.success('换 IP 请求已提交')
    scheduleReload(() => networkDetailPanelRef.value?.loadNetworkDetail?.(), 3000)
  } catch (e: any) {
    message.error(e?.message || '换 IP 失败')
  } finally {
    changeIpLoading.value = false
  }
}

async function refreshInstanceInfo() {
  if (!currentInstance.value || !currentTenant.value) return
  const instanceId = currentInstance.value.instanceId
  instanceInfoLoading.value = true
  try {
    const res = await getInstanceList({
      id: currentTenant.value.id,
      ...instanceDetailRegionParam(),
      force: true,
    })
    const fresh = (res.data || []).find((i: any) => i.instanceId === instanceId)
    if (!fresh) {
      message.warning('实例不存在或已终止')
      return
    }
    currentInstance.value = { ...currentInstance.value, ...fresh }
    const td = tenantDataList.value.find(t => t.tenant.id === currentTenant.value.id)
    if (td) {
      const idx = td.instances.findIndex((i: any) => i.instanceId === instanceId)
      if (idx >= 0) td.instances[idx] = { ...td.instances[idx], ...fresh }
    }
    message.success('实例信息已刷新')
  } catch (e: any) {
    message.error(e?.message || '刷新实例信息失败')
  } finally {
    instanceInfoLoading.value = false
  }
}

function showCreateReservedIpModal() { createRipName.value = ''; createRipVisible.value = true }

async function handleCreateReservedIp() {
  createRipLoading.value = true
  try {
    const res = await createReservedIp({
      id: currentTenant.value.id,
      displayName: createRipName.value || undefined,
      ...vcnReservedIpRegionParam(),
    })
    message.success('预留IP已创建: ' + (res.data?.ipAddress || ''))
    createRipVisible.value = false
    loadReservedIps()
  } catch (e: any) {
    message.error(e?.message || '创建预留IP失败')
  } finally {
    createRipLoading.value = false
  }
}

async function loadReservedIps() {
  if (!currentTenant.value) return
  reservedIpListLoading.value = true
  try {
    const res = await listReservedIps({
      id: currentTenant.value.id,
      ...vcnReservedIpRegionParam(),
    })
    reservedIps.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载预留 IP 失败')
  } finally {
    reservedIpListLoading.value = false
  }
}

async function handleDeleteReservedIp(publicIpId: string) {
  try {
    await deleteReservedIp({
      id: currentTenant.value.id,
      publicIpId,
      ...vcnReservedIpRegionParam(),
    })
    message.success('预留IP已删除')
    loadReservedIps()
  } catch (e: any) { message.error(e?.message || '删除预留IP失败') }
}

async function handleAssignReservedIp(publicIpId: string) {
  if (!currentInstance.value) return
  try {
    await assignReservedIp({
      id: currentTenant.value.id,
      publicIpId,
      instanceId: currentInstance.value.instanceId,
      ...instanceDetailScopeParam(),
    })
    message.success('预留IP已绑定')
    loadReservedIps()
    networkDetailPanelRef.value?.loadNetworkDetail?.()
  } catch (e: any) { message.error(e?.message || '绑定失败') }
}

async function handleUnassignReservedIp(publicIpId: string) {
  try {
    await unassignReservedIp({
      id: currentTenant.value.id,
      publicIpId,
      ...vcnReservedIpRegionParam(),
    })
    message.success('预留IP已解绑')
    loadReservedIps()
    networkDetailPanelRef.value?.loadNetworkDetail?.()
  } catch (e: any) { message.error(e?.message || '解绑失败') }
}

function openEditInstance() {
  if (!currentInstance.value) return
  editInstanceForm.displayName = currentInstance.value.name || ''
  editInstanceVisible.value = true
}

async function handleEditInstance() {
  if (!currentInstance.value || !currentTenant.value) return
  if (!editInstanceForm.displayName || editInstanceForm.displayName === currentInstance.value.name) {
    message.info('请输入新的实例名称')
    return
  }
  editInstanceLoading.value = true
  try {
    const res = await updateInstance({
      id: currentTenant.value.id,
      instanceId: currentInstance.value.instanceId,
      displayName: editInstanceForm.displayName,
      ...instanceDetailRegionParam(),
    })
    message.success('实例名称已更新')
    if (res.data?.name) currentInstance.value.name = res.data.name
    editInstanceVisible.value = false
    const td = tenantDataList.value.find(t => t.tenant.id === currentTenant.value.id)
    if (td) loadTenantInstances(td, { force: true })
  } catch (e: any) {
    message.error(e?.message || '修改实例失败')
  } finally {
    editInstanceLoading.value = false
  }
}

onMounted(() => {
  void loadGroups()
  void loadAllTenants()
  window.addEventListener('resize', checkMobile)
  window.addEventListener('beforeunload', handleShapeEditBeforeUnload)
})
onActivated(() => {
  if (!instanceListActivatedOnce) {
    instanceListActivatedOnce = true
    return
  }
  void loadGroups()
  void loadAllTenants(false)
})
onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
  window.removeEventListener('beforeunload', handleShapeEditBeforeUnload)
  if (tenantFloatTimer) window.clearTimeout(tenantFloatTimer)
  if (tenantWorkspaceOpenTimer) window.clearTimeout(tenantWorkspaceOpenTimer)
  void stopCurrentShapeEditTaskSilently()
  stopShapeEditTaskPolling()
  pendingTimers.forEach((t: any) => clearTimeout(t))
  pendingTimers.clear()
})
</script>

<style scoped>
.quick-login-options-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1.15fr;
  gap: 12px;
  align-items: start;
}

:global(:root) {
  --tenant-free-tier-color: rgba(255, 255, 255, 0.94);
  --tenant-free-tier-bg: rgba(255, 255, 255, 0.1);
  --tenant-free-tier-border: rgba(255, 255, 255, 0.16);
  --tenant-free-tier-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
}
:global([data-theme="light"]) {
  --tenant-free-tier-color: rgba(15, 23, 42, 0.88);
  --tenant-free-tier-bg: rgba(15, 23, 42, 0.06);
  --tenant-free-tier-border: rgba(15, 23, 42, 0.12);
  --tenant-free-tier-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);
}
.instance-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  flex-wrap: wrap;
  gap: 12px;
}
.toolbar-left, .toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.shape-edit-task-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.shape-edit-task-message {
  margin-top: 6px;
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.5;
}
.group-collapse { margin-bottom: 16px; }
.group-collapse :deep(.ant-collapse-header) { font-weight: 600; font-size: 14px; }
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
.group-header-label { vertical-align: middle; }
.group-collapse-l2 .tenant-grid { margin-bottom: 18px; }
.group-collapse-l2 + .group-section { margin-top: 12px; }
.group-section { margin-bottom: 8px; }
.group-table-row {
  padding: 8px 12px;
  border-bottom: 1px solid var(--border);
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  column-gap: 12px;
  align-items: center;
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
.gtr-actions {
  justify-self: end;
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
}
.tenant-card {
  content-visibility: auto;
  contain-intrinsic-size: 260px;
}
.group-table-row {
  content-visibility: auto;
  contain-intrinsic-size: 64px;
}
.instance-mobile-card {
  content-visibility: auto;
  contain-intrinsic-size: 180px;
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
:global(:root) {
  --tenant-workspace-mask-bg: rgba(2, 6, 23, 0.28);
  --tenant-floating-card-bg: rgba(30, 41, 59, 0.78);
  --tenant-floating-card-border: rgba(165, 180, 252, 0.42);
  --tenant-floating-card-shadow: 0 26px 60px rgba(0, 0, 0, 0.48), 0 0 34px rgba(129, 140, 248, 0.16);
  --tenant-floating-placeholder-bg: rgba(15, 23, 42, 0.16);
  --tenant-floating-placeholder-border: rgba(129, 140, 248, 0.22);
}
:global([data-theme="light"]) {
  --tenant-workspace-mask-bg: rgba(15, 23, 42, 0.22);
  --tenant-floating-card-bg: rgba(255, 255, 255, 0.94);
  --tenant-floating-card-border: rgba(99, 102, 241, 0.3);
  --tenant-floating-card-shadow: 0 24px 52px rgba(15, 23, 42, 0.18), 0 0 22px rgba(99, 102, 241, 0.12);
  --tenant-floating-placeholder-bg: rgba(255, 255, 255, 0.22);
  --tenant-floating-placeholder-border: rgba(99, 102, 241, 0.18);
}
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
.tenant-floating-card {
  position: fixed;
  z-index: var(--oci-z-tenant-floating-card);
  perspective: 1200px;
  perspective-origin: center center;
  transform-style: preserve-3d;
  contain: layout style;
  overflow: visible;
  pointer-events: auto;
  transform: translateZ(0);
}
.tenant-floating-card-rolling {
  z-index: var(--oci-z-tenant-floating-card-rolling);
  pointer-events: none;
  will-change: transform;
  animation: tenantFloatingFlight var(--tenant-float-duration) cubic-bezier(0.18, 0.82, 0.22, 1) forwards;
}
.tenant-floating-card-docked {
  animation: tenantFloatingSettle 160ms ease-out both;
}
.tenant-floating-flight,
.tenant-floating-roll {
  height: 100%;
  overflow: visible;
  transform-style: preserve-3d;
}
.tenant-floating-card-rolling .tenant-floating-flight {
  will-change: transform;
  animation: tenantFloatingLift var(--tenant-float-duration) cubic-bezier(0.18, 0.82, 0.22, 1) forwards;
}
.tenant-floating-roll {
  position: relative;
  will-change: transform;
}
.tenant-floating-card-rolling .tenant-floating-roll {
  animation: tenantFloatingRoll var(--tenant-float-duration) linear forwards;
}
.tenant-floating-card-face {
  position: relative;
  height: 100%;
  padding: 18px;
  border: 1px solid var(--tenant-floating-card-border);
  border-radius: 16px;
  background: var(--bg-card);
  background: var(--tenant-floating-card-bg);
  box-shadow: var(--tenant-floating-card-shadow);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  transform: translateZ(1px);
  backface-visibility: hidden;
  -webkit-backface-visibility: hidden;
  will-change: transform;
}
.tenant-floating-card-face::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(90deg, var(--primary), #8b5cf6);
}
.tenant-floating-roll::after {
  content: '';
  position: absolute;
  inset: 0;
  border: 1px solid var(--tenant-floating-card-border);
  border-radius: 16px;
  background:
    radial-gradient(circle at 22% 18%, rgba(129, 140, 248, 0.18), transparent 30%),
    var(--tenant-floating-card-bg);
  box-shadow: var(--tenant-floating-card-shadow);
  transform: rotateY(180deg) translateZ(0.5px);
  backface-visibility: hidden;
  -webkit-backface-visibility: hidden;
}
.tenant-floating-card-docked .tenant-floating-roll::after {
  display: none;
}
.tenant-floating-card-face :deep(.tenant-floating-action) {
  height: 34px;
  border-radius: 8px;
  font-weight: 600;
}
.tenant-floating-card-face :deep(.tenant-floating-action i) {
  margin-right: 6px;
  font-size: 15px;
}
.tenant-floating-card-face :deep(.tenant-floating-action.ant-btn-primary) {
  box-shadow: 0 8px 18px rgba(79, 70, 229, 0.26);
}
@keyframes tenantFloatingFlight {
  from { transform: translate3d(0, 0, 0); }
  to { transform: translate3d(var(--tenant-float-dx), var(--tenant-float-dy), 0); }
}
@keyframes tenantFloatingLift {
  0% { transform: translate3d(0, 0, 0) scale(1); }
  18% { transform: translate3d(0, -18px, 0) scale(1.035); }
  72% { transform: translate3d(0, -7px, 0) scale(1.012); }
  100% { transform: translate3d(0, 0, 0) scale(1); }
}
@keyframes tenantFloatingRoll {
  from { transform: rotate3d(0.08, 1, 0.02, 0deg); }
  to { transform: rotate3d(0.08, 1, 0.02, 360deg); }
}
@keyframes tenantFloatingSettle {
  from {
    transform: translateY(-3px) scale(0.992);
  }
  to {
    transform: translateY(0) scale(1);
  }
}
@media (prefers-reduced-motion: reduce) {
  .tenant-floating-card-rolling,
  .tenant-floating-card-rolling .tenant-floating-flight,
  .tenant-floating-card-rolling .tenant-floating-roll,
  .tenant-floating-card-docked {
    animation-duration: 1ms !important;
  }
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
.instance-panel {
  background: transparent;
  border: none;
  border-radius: 0;
  padding: 0;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  box-shadow: none;
}
.instance-panel-toolbar,
.vcn-panel-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-bottom: 14px;
  padding: 10px 12px;
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: 10px;
}
.vcn-panel-toolbar {
  margin-top: 0;
}
.instance-panel-toolbar-label {
  color: var(--text-sub);
  font-size: 12px;
  white-space: nowrap;
}
.instance-panel-region-select {
  min-width: 200px;
  flex: 1 1 220px;
  max-width: 100%;
}
.instance-panel-region-hint {
  font-size: 12px;
  color: var(--text-sub);
  line-height: 1.3;
}
.mobile-card {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
}
.mobile-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
.mobile-card-title {
  color: var(--text-main);
  font-weight: 600;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mobile-card-body {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.mobile-card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
}
.mobile-card-row .label {
  color: var(--text-sub);
  flex: 0 0 auto;
}
.mobile-card-row .value {
  color: var(--text-main);
  min-width: 0;
  text-align: right;
  overflow-wrap: anywhere;
}
@media (min-width: 769px) {
  .instance-panel-region-hint {
    flex: 0 0 auto;
    margin-left: auto;
  }
}
.instance-drawer-title {
  display: flex;
  align-items: center;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-main);
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
}
.drawer-username {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.instance-manager-drawer :deep(.ant-drawer-header-title) {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}
.instance-manager-drawer :deep(.ant-drawer-extra) {
  flex-shrink: 0;
}
.panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.region-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-right: 4px;
}
.region-switch-label {
  color: var(--text-sub);
  font-size: 12px;
  white-space: nowrap;
}
.info-label { color: var(--text-sub); flex-shrink: 0; }

.vcn-item {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 12px;
}
.vcn-item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.vcn-item-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}
.vcn-info-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.vcn-subnet-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  font-size: 12px;
}
.vcn-ip-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 6px 0;
  font-size: 13px;
}
.vcn-ip-meta {
  color: var(--text-sub);
  font-size: 12px;
}
.vcn-panel-hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--text-sub);
  line-height: 1.5;
}
.vcn-byoip-block {
  margin-top: 10px;
}
.vcn-byoip-label {
  font-size: 12px;
  color: var(--text-sub);
  margin-bottom: 4px;
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
  .quick-login-options-row {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }
  .quick-login-options-row > div:nth-child(3) {
    grid-column: 1 / -1;
  }
  .instance-toolbar { flex-direction: column; align-items: stretch; }
  .toolbar-left, .toolbar-right { width: 100%; flex-wrap: wrap; }
  .toolbar-left :deep(.ant-input-search) { width: 100% !important; flex: 1 1 100%; }
  .toolbar-right { justify-content: space-between; }
  .tenant-grid { grid-template-columns: 1fr 1fr; gap: 10px; }
  .tenant-card { padding: 14px; border-radius: 12px; }
  .tc-icon { font-size: 22px; }
  .tc-name { font-size: 13px; }
  .instance-panel-toolbar,
  .vcn-panel-toolbar {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
  }
  .instance-panel-region-select {
    width: 100% !important;
    min-width: 0;
    flex: none;
    max-width: none;
  }
  .instance-panel-region-hint {
    margin-left: 0;
    width: 100%;
  }
  .panel-actions {
    gap: 4px;
  }
  .panel-actions .region-switch {
    margin-right: 0;
  }
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

.instance-manager-drawer :deep(.ant-drawer-body) {
  scrollbar-width: thin;
  padding-top: 10px;
}
.instance-manager-drawer :deep(.ant-drawer-header) {
  padding: 12px 16px;
}
.instance-action-menu {
  min-width: 150px;
}
.instance-action-menu .ri-play-fill,
.instance-action-menu .ri-restart-line,
.instance-action-menu .ri-shut-down-line,
.instance-action-menu .ri-stop-fill,
.instance-action-menu .ri-close-circle-line {
  font-size: 14px;
  vertical-align: -2px;
}
.instance-name-cell {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
  font-weight: 600;
}
.shape-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}
.shape-main {
  font-weight: 600;
  color: var(--text-main);
  font-size: 13px;
}
.shape-sub {
  font-size: 11px;
  color: var(--text-sub);
  font-family: 'JetBrains Mono', 'SF Mono', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 160px;
}
.instance-drawer-shape-footer {
  display: flex;
  justify-content: flex-end;
}
.ip-copy :deep(.ant-typography-copy) {
  margin-inline-start: 4px;
}
.instance-action-trigger {
  padding-inline: 4px;
}

/* 移动端：抽屉头部名称省略 */
.drawer-username {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
  vertical-align: middle;
}

/* 移动端：抽屉内容区顶部的区域 meta */
.mobile-drawer-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px dashed var(--border);
}
.mobile-region-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 200px;
}

/* 移动端：实例卡片流 */
.instance-mobile-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.instance-mobile-card {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.imc-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border);
}
.imc-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-main);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}
.imc-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.imc-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 13px;
}
.imc-label {
  color: var(--text-sub);
  flex-shrink: 0;
  width: 60px;
  padding-top: 2px;
}
.imc-value-group {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}
.imc-value-main {
  color: var(--text-main);
  font-weight: 600;
}
.imc-value-sub {
  font-size: 11px;
  color: var(--text-sub);
  font-family: 'JetBrains Mono', 'SF Mono', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.imc-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 6px;
  border-top: 1px solid var(--border);
}
.imc-footer :deep(.ant-btn-link) {
  font-size: 14px;
  height: 32px;
  padding-inline: 10px;
}
.tenant-page-float-actions {
  position: fixed;
  right: 20px;
  bottom: 24px;
  z-index: 100;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: none;
}
.tenant-page-float-actions > * {
  pointer-events: auto;
}
.float-action-btn {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.18);
}
</style>
