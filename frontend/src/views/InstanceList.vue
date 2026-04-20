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
        <a-button @click="loadAllTenants" :loading="globalLoading">
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
            <a-badge :count="groupTenantCount(g1)" :number-style="{ backgroundColor: 'var(--primary)', fontSize: '11px' }" :show-zero="true" style="margin-left: 8px" />
          </template>
          <!-- 二级分组 -->
          <template v-if="g1.children && g1.children.length > 0">
            <div v-if="g1.tenants.length > 0" class="group-section">
              <component :is="tenantViewMode === 'card' ? 'div' : 'div'">
                <div v-if="tenantViewMode === 'card'" class="tenant-grid">
                  <template v-for="td in g1.tenants" :key="td.tenant.id">
                    <div class="tenant-card" :class="{ 'tenant-card-active': activeTenantId === td.tenant.id }">
                      <div class="tc-header"><i class="ri-cloud-line tc-icon"></i><div class="tc-info"><div class="tc-name">{{ td.tenant.username }}</div><div class="tc-region">{{ td.tenant.ociRegion }}</div></div></div>
                      <div class="tc-tags"><a-tag v-if="td.tenant.planType" :color="td.tenant.planType === 'FREE' ? 'default' : 'green'" size="small">{{ td.tenant.planType }}</a-tag><a-tag v-if="td.tenant.tenantName" size="small" color="blue">{{ td.tenant.tenantName }}</a-tag></div>
                      <div class="tc-actions"><a-button type="primary" block @click="selectTenant(td)" :loading="td.loading"><i class="ri-server-line" style="margin-right:6px"></i>实例管理</a-button><a-button block @click="openVcnPanel(td.tenant)"><i class="ri-share-line" style="margin-right:6px"></i>虚拟云网络</a-button><a-button block @click="openVolumePanel(td.tenant)"><i class="ri-hard-drive-2-line" style="margin-right:6px"></i>卷组</a-button><a-button block @click="openQuickTask(td.tenant)"><i class="ri-play-circle-line" style="margin-right:6px"></i>快捷开机</a-button></div>
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
                      <a-button size="small" @click="openVolumePanel(td.tenant)">卷组</a-button>
                      <a-button size="small" @click="openQuickTask(td.tenant)">快捷开机</a-button>
                    </a-space>
                    <div v-else class="gtr-actions gtr-actions-mobile">
                      <a-button type="primary" size="small" @click="selectTenant(td)" :loading="td.loading">实例管理</a-button>
                      <a-dropdown placement="bottomRight" :trigger="['click']">
                        <a-button size="small">更多 <DownOutlined /></a-button>
                        <template #overlay>
                          <a-menu>
                            <a-menu-item key="vcn" @click="openVcnPanel(td.tenant)">VCN</a-menu-item>
                            <a-menu-item key="vol" @click="openVolumePanel(td.tenant)">卷组</a-menu-item>
                            <a-menu-item key="quick" @click="openQuickTask(td.tenant)">快捷开机</a-menu-item>
                          </a-menu>
                        </template>
                      </a-dropdown>
                    </div>
                  </component>
                </div>
              </component>
            </div>
            <a-collapse class="group-collapse-l2">
              <a-collapse-panel v-for="l2 in g1.children" :key="l2.key" :collapsible="l2.tenants.length === 0 ? 'disabled' : undefined">
                <template #header>
                  <span class="group-header-label">{{ l2.label }}</span>
                  <a-badge :count="l2.tenants.length" :number-style="{ backgroundColor: 'var(--primary)', fontSize: '11px' }" :show-zero="true" style="margin-left: 8px" />
                </template>
                <div v-if="tenantViewMode === 'card'" class="tenant-grid">
                  <template v-for="td in l2.tenants" :key="td.tenant.id">
                    <div class="tenant-card" :class="{ 'tenant-card-active': activeTenantId === td.tenant.id }">
                      <div class="tc-header"><i class="ri-cloud-line tc-icon"></i><div class="tc-info"><div class="tc-name">{{ td.tenant.username }}</div><div class="tc-region">{{ td.tenant.ociRegion }}</div></div></div>
                      <div class="tc-tags"><a-tag v-if="td.tenant.planType" :color="td.tenant.planType === 'FREE' ? 'default' : 'green'" size="small">{{ td.tenant.planType }}</a-tag><a-tag v-if="td.tenant.tenantName" size="small" color="blue">{{ td.tenant.tenantName }}</a-tag></div>
                      <div class="tc-actions"><a-button type="primary" block @click="selectTenant(td)" :loading="td.loading"><i class="ri-server-line" style="margin-right:6px"></i>实例管理</a-button><a-button block @click="openVcnPanel(td.tenant)"><i class="ri-share-line" style="margin-right:6px"></i>虚拟云网络</a-button><a-button block @click="openVolumePanel(td.tenant)"><i class="ri-hard-drive-2-line" style="margin-right:6px"></i>卷组</a-button><a-button block @click="openQuickTask(td.tenant)"><i class="ri-play-circle-line" style="margin-right:6px"></i>快捷开机</a-button></div>
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
                      <a-button size="small" @click="openVolumePanel(td.tenant)">卷组</a-button>
                      <a-button size="small" @click="openQuickTask(td.tenant)">快捷开机</a-button>
                    </a-space>
                    <div v-else class="gtr-actions gtr-actions-mobile">
                      <a-button type="primary" size="small" @click="selectTenant(td)" :loading="td.loading">实例管理</a-button>
                      <a-dropdown placement="bottomRight" :trigger="['click']">
                        <a-button size="small">更多 <DownOutlined /></a-button>
                        <template #overlay>
                          <a-menu>
                            <a-menu-item key="vcn" @click="openVcnPanel(td.tenant)">VCN</a-menu-item>
                            <a-menu-item key="vol" @click="openVolumePanel(td.tenant)">卷组</a-menu-item>
                            <a-menu-item key="quick" @click="openQuickTask(td.tenant)">快捷开机</a-menu-item>
                          </a-menu>
                        </template>
                      </a-dropdown>
                    </div>
                  </div>
                </div>
              </a-collapse-panel>
            </a-collapse>
          </template>
          <!-- 无二级分组时直接平铺 -->
          <template v-else>
            <div v-if="tenantViewMode === 'card'" class="tenant-grid">
              <template v-for="td in g1.tenants" :key="td.tenant.id">
                <div class="tenant-card" :class="{ 'tenant-card-active': activeTenantId === td.tenant.id }">
                  <div class="tc-header"><i class="ri-cloud-line tc-icon"></i><div class="tc-info"><div class="tc-name">{{ td.tenant.username }}</div><div class="tc-region">{{ td.tenant.ociRegion }}</div></div></div>
                  <div class="tc-tags"><a-tag v-if="td.tenant.planType" :color="td.tenant.planType === 'FREE' ? 'default' : 'green'" size="small">{{ td.tenant.planType }}</a-tag><a-tag v-if="td.tenant.tenantName" size="small" color="blue">{{ td.tenant.tenantName }}</a-tag></div>
                  <div class="tc-actions"><a-button type="primary" block @click="selectTenant(td)" :loading="td.loading"><i class="ri-server-line" style="margin-right:6px"></i>实例管理</a-button><a-button block @click="openVcnPanel(td.tenant)"><i class="ri-share-line" style="margin-right:6px"></i>虚拟云网络</a-button><a-button block @click="openVolumePanel(td.tenant)"><i class="ri-hard-drive-2-line" style="margin-right:6px"></i>卷组</a-button><a-button block @click="openQuickTask(td.tenant)"><i class="ri-play-circle-line" style="margin-right:6px"></i>快捷开机</a-button></div>
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
                  <a-button size="small" @click="openVolumePanel(td.tenant)">卷组</a-button>
                  <a-button size="small" @click="openQuickTask(td.tenant)">快捷开机</a-button>
                </a-space>
                <div v-else class="gtr-actions gtr-actions-mobile">
                  <a-button type="primary" size="small" @click="selectTenant(td)" :loading="td.loading">实例管理</a-button>
                  <a-dropdown placement="bottomRight" :trigger="['click']">
                    <a-button size="small">更多 <DownOutlined /></a-button>
                    <template #overlay>
                      <a-menu>
                        <a-menu-item key="vcn" @click="openVcnPanel(td.tenant)">VCN</a-menu-item>
                        <a-menu-item key="vol" @click="openVolumePanel(td.tenant)">卷组</a-menu-item>
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
    </template>

    <!-- 无分组时保持原有卡片/列表展示 -->
    <template v-else>
    <!-- 租户卡片视图 -->
    <div v-if="tenantViewMode === 'card'" class="tenant-grid">
      <div v-for="td in filteredTenants" :key="td.tenant.id"
        class="tenant-card" :class="{ 'tenant-card-active': activeTenantId === td.tenant.id }">
        <div class="tc-header">
          <i class="ri-cloud-line tc-icon"></i>
          <div class="tc-info">
            <div class="tc-name">{{ td.tenant.username }}</div>
            <div class="tc-region">{{ td.tenant.ociRegion }}</div>
          </div>
        </div>
        <div class="tc-tags">
          <a-tag v-if="td.tenant.planType" :color="td.tenant.planType === 'FREE' ? 'default' : 'green'" size="small">{{ td.tenant.planType }}</a-tag>
          <a-tag v-if="td.tenant.tenantName" size="small" color="blue">{{ td.tenant.tenantName }}</a-tag>
        </div>
        <div class="tc-actions">
          <a-button type="primary" block @click="selectTenant(td)" :loading="td.loading">
            <i class="ri-server-line" style="margin-right: 6px"></i>实例管理
          </a-button>
          <a-button block @click="openVcnPanel(td.tenant)">
            <i class="ri-share-line" style="margin-right: 6px"></i>虚拟云网络
          </a-button>
          <a-button block @click="openVolumePanel(td.tenant)">
            <i class="ri-hard-drive-2-line" style="margin-right: 6px"></i>卷组
          </a-button>
          <a-button block @click="openQuickTask(td.tenant)">
            <i class="ri-play-circle-line" style="margin-right: 6px"></i>快捷开机
          </a-button>
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
            <a-tag v-if="record.tenant.planType" :color="record.tenant.planType === 'FREE' ? 'default' : 'green'">{{ record.tenant.planType }}</a-tag>
            <span v-else style="color: var(--text-sub)">—</span>
          </template>
        </a-table-column>
        <a-table-column title="操作" key="action" :width="260" align="right">
          <template #default="{ record }">
            <a-space>
              <a-button type="primary" size="small" @click="selectTenant(record)" :loading="record.loading">实例管理</a-button>
              <a-button size="small" @click="openVcnPanel(record.tenant)">VCN</a-button>
              <a-button size="small" @click="openVolumePanel(record.tenant)">卷组</a-button>
              <a-button size="small" @click="openQuickTask(record.tenant)">快捷开机</a-button>
            </a-space>
          </template>
        </a-table-column>
      </a-table>
    </div>
    </template>

    <!-- 实例管理抽屉 -->
    <a-drawer
      v-model:open="instancePanelVisible"
      :width="instancePanelWidth"
      :mask-closable="false"
      destroy-on-close
      wrap-class-name="instance-manager-drawer"
      :body-style="{ padding: isMobile ? '10px' : '16px' }"
    >
      <template #title>
        <div v-if="activeTenantData" class="instance-drawer-title">
          <i class="ri-server-line" style="margin-right: 8px; color: var(--primary)"></i>
          <span class="drawer-username">{{ activeTenantData.tenant.username }}</span>
          <a-tag v-if="!isMobile" color="blue" style="margin-left: 8px">{{ activeTenantData.tenant.ociRegion }}</a-tag>
          <a-badge :count="activeTenantData.instances.length" :number-style="{ backgroundColor: 'var(--primary)' }" :show-zero="true" style="margin-left: 8px" />
        </div>
      </template>
      <template #extra>
        <div v-if="activeTenantData" class="panel-actions">
          <div v-if="!isMobile" class="region-switch" v-show="regionTenantOptions.length > 1">
            <span class="region-switch-label">区域</span>
            <a-select
              v-model:value="activeTenantId"
              size="small"
              style="width: 170px"
              @change="handleRegionSwitch"
            >
              <a-select-option v-for="opt in regionTenantOptions" :key="opt.id" :value="opt.id">
                {{ opt.regionLabel }}
              </a-select-option>
            </a-select>
          </div>
          <a-button size="small" @click="loadTenantInstances(activeTenantData)" :loading="activeTenantData.loading">
            <template #icon><ReloadOutlined /></template>{{ isMobile ? '' : '刷新' }}
          </a-button>
        </div>
      </template>
      <div v-if="activeTenantData" class="instance-panel">
        <!-- 移动端：区域切换条 + 当前区域 tag -->
        <div v-if="isMobile" class="mobile-drawer-meta">
          <a-tag color="blue">{{ activeTenantData.tenant.ociRegion }}</a-tag>
          <div v-if="regionTenantOptions.length > 1" class="mobile-region-bar">
            <span class="region-switch-label">区域</span>
            <a-select
              v-model:value="activeTenantId"
              size="small"
              style="flex: 1"
              @change="handleRegionSwitch"
            >
              <a-select-option v-for="opt in regionTenantOptions" :key="opt.id" :value="opt.id">
                {{ opt.regionLabel }}
              </a-select-option>
            </a-select>
          </div>
        </div>

        <a-spin :spinning="activeTenantData.loading">
          <a-empty v-if="!activeTenantData.loading && activeTenantData.instances.length === 0" description="暂无实例" />

          <!-- 移动端：卡片流 -->
          <div v-else-if="isMobile" class="instance-mobile-list">
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
    <a-modal v-model:open="quickTaskVisible" title="快捷开机任务" :width="isMobile ? '100%' : 600"
      @ok="handleQuickTask" :confirm-loading="quickTaskLoading" :mask-closable="false">
      <div style="margin-bottom: 12px">
        <a-tag color="blue">{{ quickTaskTenant?.username }}</a-tag>
        <a-tag>{{ quickTaskTenant?.ociRegion }}</a-tag>
      </div>
      <a-form :model="quickTaskForm" layout="vertical">
        <a-form-item label="机器规格">
          <a-select v-model:value="quickTaskForm.architecture">
            <a-select-option value="ARM">ARM (A1.Flex)</a-select-option>
            <a-select-option value="AMD">AMD (E2.1.Micro)</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="操作系统">
          <a-select v-model:value="quickTaskForm.operationSystem">
            <a-select-option value="Ubuntu">Ubuntu（最新版）</a-select-option>
            <a-select-option value="Ubuntu 24.04">Ubuntu 24.04 LTS</a-select-option>
            <a-select-option value="Ubuntu 22.04">Ubuntu 22.04 LTS</a-select-option>
            <a-select-option value="Ubuntu 20.04">Ubuntu 20.04 LTS</a-select-option>
            <a-select-option value="Oracle Linux">Oracle Linux</a-select-option>
            <a-select-option value="CentOS">CentOS</a-select-option>
          </a-select>
        </a-form-item>
        <a-row :gutter="12">
          <a-col :xs="12" :sm="8">
            <a-form-item label="OCPU">
              <a-input-number v-model:value="quickTaskForm.ocpus" :min="1" :max="4" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="8">
            <a-form-item label="内存 (GB)">
              <a-input-number v-model:value="quickTaskForm.memory" :min="1" :max="24" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="8">
            <a-form-item label="磁盘 (GB)">
              <a-input-number v-model:value="quickTaskForm.disk" :min="47" :max="200" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :xs="12" :sm="8">
            <a-form-item label="数量">
              <a-input-number v-model:value="quickTaskForm.createNumbers" :min="1" :max="5" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="8">
            <a-form-item label="间隔 (秒)">
              <a-input-number v-model:value="quickTaskForm.interval" :min="10" :max="600" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="8">
            <a-form-item label="Root 密码">
              <a-input-password v-model:value="quickTaskForm.rootPassword" placeholder="留空=随机" />
            </a-form-item>
          </a-col>
        </a-row>
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
    <a-drawer
      v-model:open="drawerVisible"
      :title="currentInstance?.name || '实例详情'"
      :width="isMobile ? '100%' : 780"
      placement="right"
      :mask-closable="false"
    >
      <a-tabs v-model:activeKey="activeTab" @change="onTabChange">
        <a-tab-pane key="info" tab="基本信息">
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
          </a-descriptions>

          <a-divider orientation="left">网络信息</a-divider>
          <a-spin :spinning="netDetailLoading">
            <a-button size="small" @click="loadNetworkDetail" :loading="netDetailLoading" style="margin-bottom: 12px">
              刷新网络信息
            </a-button>
            <template v-if="networkDetail">
              <div v-for="(vnic, vi) in networkDetail.vnics" :key="vi" style="margin-bottom: 16px">
                <a-descriptions :column="1" bordered size="small">
                  <a-descriptions-item v-for="(ipd, idx) in getPrimaryIps(vnic)" :key="'p'+idx" label="主IP">
                    <div>
                      <template v-if="ipd.publicIpAddress">
                        公网IP<a-tag :color="ipd.publicIpLifetime === 'RESERVED' ? 'green' : 'orange'" style="margin: 0 6px">{{ ipd.publicIpLifetime === 'RESERVED' ? '预留' : '临时' }}</a-tag><a-typography-text copyable>{{ ipd.publicIpAddress }}</a-typography-text>
                        <span style="color: #999; margin-left: 6px">( {{ ipd.privateIpAddress }} )</span>
                        <a-popconfirm title="确定删除该公网IP？" @confirm="handleDeletePublicIp(ipd)">
                          <a-button type="link" danger size="small">删除</a-button>
                        </a-popconfirm>
                      </template>
                      <template v-else>
                        内网IP: <a-typography-text copyable>{{ ipd.privateIpAddress }}</a-typography-text>
                        <span style="color: #999; margin-left: 6px">（无公网IP）</span>
                        <a-button type="link" size="small" @click="handleAssignEphemeralIp(ipd)" :loading="ephemeralIpLoading">附加 IPv4</a-button>
                      </template>
                    </div>
                  </a-descriptions-item>
                  <a-descriptions-item label="辅助IP">
                    <template v-if="getSecondaryIps(vnic).length > 0">
                      <div v-for="(ipd, idx) in getSecondaryIps(vnic)" :key="'s'+idx" style="margin-bottom: 4px">
                        <template v-if="ipd.publicIpAddress">
                          公网IP<a-tag :color="ipd.publicIpLifetime === 'RESERVED' ? 'green' : 'orange'" style="margin: 0 6px">{{ ipd.publicIpLifetime === 'RESERVED' ? '预留' : '临时' }}</a-tag><a-typography-text copyable>{{ ipd.publicIpAddress }}</a-typography-text>
                          <span style="color: #999; margin-left: 6px">( {{ ipd.privateIpAddress }} )</span>
                          <a-popconfirm title="将同时删除公网IP和内网IP，确定？" @confirm="handleDeleteSecondaryIp(ipd)">
                            <a-button type="link" danger size="small">删除</a-button>
                          </a-popconfirm>
                        </template>
                        <template v-else>
                          内网IP: <a-typography-text copyable>{{ ipd.privateIpAddress }}</a-typography-text>
                          <a-popconfirm title="确定删除该辅助IP？" @confirm="handleDeleteSecondaryIp(ipd)">
                            <a-button type="link" danger size="small">删除</a-button>
                          </a-popconfirm>
                        </template>
                      </div>
                    </template>
                    <template v-else>
                      <span style="color: #999">无</span>
                      <a-button type="link" size="small" @click="handleAddAuxIp" :loading="auxIpLoading" style="margin-left: 8px">添加辅助IP</a-button>
                    </template>
                  </a-descriptions-item>
                  <a-descriptions-item label="IPv6">
                    <template v-if="vnic.ipv6List && vnic.ipv6List.length > 0">
                      <div v-for="(ip6, i6) in vnic.ipv6List" :key="ip6.ipv6Id || i6" style="margin-bottom: 4px">
                        <a-typography-text copyable>{{ ip6.ipAddress }}</a-typography-text>
                        <a-popconfirm title="确定取消分配该 IPv6？" @confirm="handleRemoveIpv6(ip6)">
                          <a-button type="link" danger size="small" :loading="ipv6RemoveLoading[ip6.ipv6Id]">取消分配</a-button>
                        </a-popconfirm>
                      </div>
                      <div>
                        <a-button type="link" size="small" @click="handleAddIpv6(vnic)" :loading="ipv6AddLoading[vnic.vnicId]">
                          分配 IPv6
                        </a-button>
                      </div>
                    </template>
                    <span v-else style="color: #999">
                      无
                      <a-button type="link" size="small" @click="handleAddIpv6(vnic)" :loading="ipv6AddLoading[vnic.vnicId]">添加 IPv6</a-button>
                    </span>
                  </a-descriptions-item>
                </a-descriptions>
              </div>
            </template>
          </a-spin>

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
          <div class="mobile-toolbar" style="margin-bottom: 12px">
            <a-button @click="loadSecurityRules" :loading="secLoading">加载规则</a-button>
            <a-button type="primary" @click="showAddRuleModal">添加规则</a-button>
            <a-popconfirm title="确定一键放行所有端口？" @confirm="handleReleaseAll">
              <a-button type="primary" danger :loading="releaseLoading">一键放行</a-button>
            </a-popconfirm>
            <a-popconfirm title="将替换为纯TCP预设规则（TCP+ICMP+ICMPv6），其他规则将被删除" @confirm="handleOciPreset">
              <a-button :loading="presetLoading">纯TCP放行</a-button>
            </a-popconfirm>
          </div>
          <a-tabs size="small">
            <a-tab-pane key="ingress" tab="入站规则">
              <a-table v-if="!isMobile" :data-source="ingressRules" :columns="secColumns" size="small" :pagination="false">
                <template #bodyCell="{ column, index }">
                  <template v-if="column.key === 'secAction'">
                    <a-popconfirm title="确定删除该规则？" @confirm="handleDeleteSecurityRule('ingress', index)">
                      <a-button type="link" danger size="small" :loading="deleteRuleLoading">删除</a-button>
                    </a-popconfirm>
                  </template>
                </template>
              </a-table>
              <template v-else>
                <a-empty v-if="ingressRules.length === 0" description="无入站规则" />
                <div v-for="(rule, idx) in ingressRules" :key="idx" class="mobile-card">
                  <div class="mobile-card-header">
                    <span class="mobile-card-title">{{ protoMap[rule.protocol] || rule.protocol }}</span>
                    <a-popconfirm title="确定删除？" @confirm="handleDeleteSecurityRule('ingress', idx)">
                      <a-button type="link" danger size="small" :loading="deleteRuleLoading">删除</a-button>
                    </a-popconfirm>
                  </div>
                  <div class="mobile-card-body">
                    <div class="mobile-card-row"><span class="label">来源</span><span class="value">{{ rule.source }}</span></div>
                    <div class="mobile-card-row"><span class="label">端口</span><span class="value">{{ rule.portRange }}</span></div>
                    <div class="mobile-card-row" v-if="rule.description"><span class="label">描述</span><span class="value">{{ rule.description }}</span></div>
                  </div>
                </div>
              </template>
            </a-tab-pane>
            <a-tab-pane key="egress" tab="出站规则">
              <a-table v-if="!isMobile" :data-source="egressRules" :columns="secColumns" size="small" :pagination="false">
                <template #bodyCell="{ column, index }">
                  <template v-if="column.key === 'secAction'">
                    <a-popconfirm title="确定删除该规则？" @confirm="handleDeleteSecurityRule('egress', index)">
                      <a-button type="link" danger size="small" :loading="deleteRuleLoading">删除</a-button>
                    </a-popconfirm>
                  </template>
                </template>
              </a-table>
              <template v-else>
                <a-empty v-if="egressRules.length === 0" description="无出站规则" />
                <div v-for="(rule, idx) in egressRules" :key="idx" class="mobile-card">
                  <div class="mobile-card-header">
                    <span class="mobile-card-title">{{ protoMap[rule.protocol] || rule.protocol }}</span>
                    <a-popconfirm title="确定删除？" @confirm="handleDeleteSecurityRule('egress', idx)">
                      <a-button type="link" danger size="small" :loading="deleteRuleLoading">删除</a-button>
                    </a-popconfirm>
                  </div>
                  <div class="mobile-card-body">
                    <div class="mobile-card-row"><span class="label">目的</span><span class="value">{{ rule.source }}</span></div>
                    <div class="mobile-card-row"><span class="label">端口</span><span class="value">{{ rule.portRange }}</span></div>
                    <div class="mobile-card-row" v-if="rule.description"><span class="label">描述</span><span class="value">{{ rule.description }}</span></div>
                  </div>
                </div>
              </template>
            </a-tab-pane>
          </a-tabs>
        </a-tab-pane>

        <a-tab-pane key="volume" tab="引导卷">
          <a-button @click="loadBootVolumes" :loading="volLoading" style="margin-bottom: 12px">加载引导卷</a-button>
          <a-table v-if="!isMobile" :data-source="bootVolumes" :columns="volColumns" size="small" :pagination="false" row-key="id">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'volAction'">
                <a-button type="link" size="small" @click="openEditVolume(record)">编辑</a-button>
              </template>
            </template>
          </a-table>
          <template v-else>
            <a-empty v-if="bootVolumes.length === 0" description="暂无引导卷" />
            <div v-for="vol in bootVolumes" :key="vol.id" class="mobile-card">
              <div class="mobile-card-header">
                <span class="mobile-card-title">{{ vol.displayName }}</span>
                <a-button type="link" size="small" @click="openEditVolume(vol)">编辑</a-button>
              </div>
              <div class="mobile-card-body">
                <div class="mobile-card-row"><span class="label">大小</span><span class="value">{{ vol.sizeInGBs }} GB</span></div>
                <div class="mobile-card-row"><span class="label">性能</span><span class="value">{{ vol.vpusPerGB }} VPUs/GB</span></div>
                <div class="mobile-card-row"><span class="label">状态</span><span class="value">{{ vol.lifecycleState }}</span></div>
              </div>
            </div>
          </template>
          <div v-if="bootVolumes.length > 0" style="margin-top: 20px">
            <div style="font-size: 13px; color: var(--text-sub); margin-bottom: 10px">快捷预设（性能 120 VPUs/GB）</div>
            <a-space wrap>
              <a-popconfirm v-for="size in [50, 100, 150, 200]" :key="size"
                :title="`确定将引导卷调整为 ${size} GB / 120 VPUs？`"
                @confirm="applyVolumePreset(size)">
                <a-button :loading="editVolLoading">{{ size }} GB</a-button>
              </a-popconfirm>
            </a-space>
          </div>
        </a-tab-pane>

        <a-tab-pane key="allVolumes" tab="卷管理">
          <a-button @click="loadAllVolumes" :loading="allVolLoading" style="margin-bottom: 12px">加载卷列表</a-button>
          <a-tabs v-model:activeKey="volSubTab" size="small">
            <a-tab-pane key="BOOT" tab="引导卷">
              <a-table :data-source="volumesByType('BOOT')" :columns="allVolColumns" size="small" :pagination="false" row-key="id">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'delAction'">
                    <a-button type="link" danger size="small" @click="openDeleteVolume(record)">删除</a-button>
                  </template>
                </template>
              </a-table>
            </a-tab-pane>
            <a-tab-pane key="BLOCK" tab="块存储卷">
              <a-table :data-source="volumesByType('BLOCK')" :columns="allVolColumns" size="small" :pagination="false" row-key="id">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'delAction'">
                    <a-button type="link" danger size="small" @click="openDeleteVolume(record)">删除</a-button>
                  </template>
                </template>
              </a-table>
            </a-tab-pane>
            <a-tab-pane key="BOOT_BACKUP" tab="引导卷备份">
              <a-table :data-source="volumesByType('BOOT_BACKUP')" :columns="allVolColumns" size="small" :pagination="false" row-key="id">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'delAction'">
                    <a-button type="link" danger size="small" @click="openDeleteVolume(record)">删除</a-button>
                  </template>
                </template>
              </a-table>
            </a-tab-pane>
            <a-tab-pane key="BLOCK_BACKUP" tab="块存储备份">
              <a-table :data-source="volumesByType('BLOCK_BACKUP')" :columns="allVolColumns" size="small" :pagination="false" row-key="id">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'delAction'">
                    <a-button type="link" danger size="small" @click="openDeleteVolume(record)">删除</a-button>
                  </template>
                </template>
              </a-table>
            </a-tab-pane>
          </a-tabs>
        </a-tab-pane>

        <a-tab-pane key="network" tab="网络">
          <a-button @click="loadVcns" :loading="vcnLoading" style="margin-bottom: 12px">加载 VCN</a-button>
          <a-table v-if="!isMobile" :data-source="vcns" :columns="vcnColumns" size="small" :pagination="false" row-key="id">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'vcnAction'">
                <a-button size="small" type="primary" @click="openVcnManager(currentTenant?.id, record)">管理</a-button>
              </template>
            </template>
          </a-table>
          <template v-else>
            <a-empty v-if="vcns.length === 0" description="暂无 VCN" />
            <div v-for="v in vcns" :key="v.id" class="mobile-card">
              <div class="mobile-card-header">
                <span class="mobile-card-title">{{ v.displayName }}</span>
              </div>
              <div class="mobile-card-body">
                <div class="mobile-card-row"><span class="label">CIDR</span><span class="value">{{ v.cidrBlock }}</span></div>
                <div class="mobile-card-row"><span class="label">状态</span><span class="value">{{ v.lifecycleState }}</span></div>
              </div>
              <div style="margin-top: 8px">
                <a-button block size="small" type="primary" @click="openVcnManager(currentTenant?.id, v)">管理</a-button>
              </div>
            </div>
          </template>
        </a-tab-pane>

        <a-tab-pane key="traffic" tab="流量统计">
          <a-space style="margin-bottom: 12px">
            <a-select v-model:value="trafficMinutes" style="width: 140px">
              <a-select-option :value="60">最近 1 小时</a-select-option>
              <a-select-option :value="360">最近 6 小时</a-select-option>
              <a-select-option :value="1440">最近 24 小时</a-select-option>
            </a-select>
            <a-button @click="loadTraffic" :loading="trafficLoading">查询</a-button>
          </a-space>
          <a-empty v-if="!trafficData" description="点击查询加载流量数据" />
          <a-descriptions v-else :column="1" bordered size="small">
            <a-descriptions-item label="入站流量">{{ formatBytes(trafficData.inbound) }}</a-descriptions-item>
            <a-descriptions-item label="出站流量">{{ formatBytes(trafficData.outbound) }}</a-descriptions-item>
          </a-descriptions>
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
              创建后会生成一个一键连接链接，通过 WebSSH 直接进入串口终端
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
    </a-drawer>

    <!-- 添加安全规则弹窗 -->
    <a-modal v-model:open="addRuleVisible" title="添加安全规则" @ok="handleAddRule"
      :confirm-loading="addRuleLoading" :mask-closable="false">
      <a-form layout="vertical">
        <a-form-item label="方向">
          <a-radio-group v-model:value="ruleForm.direction">
            <a-radio value="ingress">入站</a-radio>
            <a-radio value="egress">出站</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="协议">
          <a-select v-model:value="ruleForm.protocol">
            <a-select-option value="TCP">TCP</a-select-option>
            <a-select-option value="UDP">UDP</a-select-option>
            <a-select-option value="ICMP">ICMP</a-select-option>
            <a-select-option value="ICMPV6">ICMPv6</a-select-option>
            <a-select-option value="ALL">全部协议</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="来源/目的 CIDR">
          <a-input v-model:value="ruleForm.source" placeholder="0.0.0.0/0" />
        </a-form-item>
        <a-form-item label="端口范围" v-if="ruleForm.protocol === 'TCP' || ruleForm.protocol === 'UDP'">
          <a-space>
            <a-input-number v-model:value="ruleForm.portMin" placeholder="留空=全部" :min="1" :max="65535" style="width: 140px" />
            <span>-</span>
            <a-input-number v-model:value="ruleForm.portMax" placeholder="留空=全部" :min="1" :max="65535" style="width: 140px" />
          </a-space>
        </a-form-item>
        <a-form-item label="描述">
          <a-input v-model:value="ruleForm.description" placeholder="可选" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 编辑引导卷弹窗 -->
    <a-modal v-model:open="editVolVisible" title="编辑引导卷" @ok="handleEditVolume"
      :confirm-loading="editVolLoading" :mask-closable="false">
      <a-form layout="vertical">
        <a-form-item label="名称">
          <a-input v-model:value="editVolForm.displayName" />
        </a-form-item>
        <a-form-item label="大小 (GB)">
          <a-input-number v-model:value="editVolForm.sizeInGBs" :min="50" :max="32768" style="width: 100%" />
          <div style="color: #999; font-size: 12px; margin-top: 4px">只能增大，不能缩小。最小 50 GB</div>
        </a-form-item>
        <a-form-item label="性能 (VPUs/GB)">
          <a-select v-model:value="editVolForm.vpusPerGB">
            <a-select-option :value="0">最低成本 (0)</a-select-option>
            <a-select-option :value="10">均衡 (10)</a-select-option>
            <a-select-option :value="20">较高性能 (20)</a-select-option>
            <a-select-option :value="30">高性能 (30)</a-select-option>
            <a-select-option :value="120">超高性能 (120)</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 新建预留IP弹窗 -->
    <a-modal v-model:open="createRipVisible" title="新建预留IP" @ok="handleCreateReservedIp"
      :confirm-loading="createRipLoading" :mask-closable="false">
      <a-form layout="vertical">
        <a-form-item label="名称（可选）">
          <a-input v-model:value="createRipName" placeholder="reserved-ip" />
        </a-form-item>
        <div style="color: #999; font-size: 12px">创建一个未绑定的预留IP。创建后可在列表中绑定到实例。</div>
      </a-form>
    </a-modal>

    <!-- 修改实例弹窗 -->
    <a-modal v-model:open="editInstanceVisible" title="修改实例" @ok="handleEditInstance"
      :confirm-loading="editInstanceLoading" :mask-closable="false" :width="isMobile ? '100%' : 480">
      <a-form layout="vertical" v-if="currentInstance">
        <a-form-item label="实例名称">
          <a-input v-model:value="editInstanceForm.displayName" placeholder="输入新名称" />
        </a-form-item>
        <template v-if="isFlexShape">
          <a-divider orientation="left" plain>配置调整（Flex Shape）</a-divider>
          <a-row :gutter="12">
            <a-col :span="12">
              <a-form-item label="OCPU 数量">
                <a-input-number v-model:value="editInstanceForm.ocpus" :min="1" :max="80" :step="1" style="width: 100%" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="内存 (GB)">
                <a-input-number v-model:value="editInstanceForm.memoryInGBs" :min="1" :max="512" :step="1" style="width: 100%" />
              </a-form-item>
            </a-col>
          </a-row>
          <div style="color: #999; font-size: 12px">仅 Flex 类型支持调整 OCPU 和内存。修改后实例可能需要重启生效。</div>
        </template>
        <div v-else style="color: #999; font-size: 12px; margin-top: 8px">当前 Shape（{{ currentInstance.shape }}）为固定规格，不支持在线调整。</div>
      </a-form>
    </a-modal>

    <!-- 终止实例验证码弹窗 -->
    <a-modal v-model:open="verifyModalVisible" title="安全验证 — 终止实例" :width="400"
      @ok="handleTerminateWithCode" :confirm-loading="verifyLoading" ok-text="确认终止" ok-type="primary"
      :ok-button-props="{ danger: true }">
      <a-alert type="warning" show-icon style="margin-bottom: 16px">
        <template #message>终止实例不可逆，验证码已发送至 Telegram</template>
      </a-alert>
      <a-input v-model:value="verifyCode" placeholder="请输入6位验证码" size="large" :maxlength="6" allow-clear />
      <div style="margin-top: 12px">
        <a-checkbox v-model:checked="deleteBootVolume">同时删除引导卷（不可恢复）</a-checkbox>
      </div>
      <div style="margin-top: 12px; display: flex; justify-content: space-between; align-items: center">
        <span style="color: var(--text-sub); font-size: 12px">验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="verifySending" @click="resendVerifyCode('terminate')">重新发送</a-button>
      </div>
    </a-modal>

    <!-- 删除卷验证码弹窗 -->
    <a-modal v-model:open="deleteVolModalVisible" title="安全验证 — 删除卷" :width="400"
      @ok="handleDeleteVolume" :confirm-loading="deleteVolVerifyLoading" ok-text="确认删除" ok-type="primary"
      :ok-button-props="{ danger: true }">
      <a-alert type="warning" show-icon style="margin-bottom: 16px">
        <template #message>删除卷不可恢复，验证码已发送至 Telegram</template>
      </a-alert>
      <div style="margin-bottom: 12px; color: var(--text-main)">
        <b>{{ deleteVolTarget?.displayName }}</b>
        <a-tag style="margin-left: 8px">{{ deleteVolTarget?.type }}</a-tag>
        <span v-if="deleteVolTarget?.sizeInGBs" style="margin-left: 8px">{{ deleteVolTarget?.sizeInGBs }} GB</span>
      </div>
      <a-input v-model:value="deleteVolCode" placeholder="请输入6位验证码" size="large" :maxlength="6" allow-clear />
      <div style="margin-top: 12px; display: flex; justify-content: space-between; align-items: center">
        <span style="color: var(--text-sub); font-size: 12px">验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="verifySending" @click="resendVerifyCode('deleteVolume')">重新发送</a-button>
      </div>
    </a-modal>

    <!-- 虚拟云网络抽屉 -->
    <a-drawer v-model:open="vcnVisible" :title="'虚拟云网络 — ' + (vcnTenant?.username || '')"
      :width="isMobile ? '100%' : 960" :mask-closable="false" destroy-on-close>
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
          <a-divider orientation="left" plain>预留IP</a-divider>
          <a-spin :spinning="reservedIpListLoading">
            <template v-if="reservedIps.length > 0">
              <div v-for="rip in reservedIps" :key="rip.id" style="margin-bottom: 6px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap">
                <a-typography-text copyable>{{ rip.ipAddress }}</a-typography-text>
                <a-tag :color="rip.isAssigned ? 'green' : 'default'">{{ rip.isAssigned ? '已绑定' : '未绑定' }}</a-tag>
                <span v-if="rip.assignedInstanceName" style="color: var(--text-sub); font-size: 12px">{{ rip.assignedInstanceName }}</span>
                <a-popconfirm v-if="!rip.isAssigned" title="确定删除？" @confirm="handleDeleteReservedIp(rip.id)">
                  <a-button type="link" danger size="small">删除</a-button>
                </a-popconfirm>
                <a-button v-if="rip.isAssigned" type="link" size="small" @click="handleUnassignReservedIp(rip.id)">解绑</a-button>
              </div>
            </template>
            <span v-else style="color: #999; font-size: 13px">暂无预留IP</span>
          </a-spin>
          <a-button type="link" size="small" @click="showCreateReservedIpModal" style="margin-top: 4px">
            <i class="ri-add-line" style="margin-right: 4px"></i>新建预留IP
          </a-button>
        </div>
      </a-spin>
    </a-drawer>

    <VcnManager
      v-model:open="vcnManagerOpen"
      :user-id="vcnManagerUserId"
      :vcn="vcnManagerVcn"
      @changed="loadVcns"
    />

    <VolumeManager
      v-model:open="volumeManagerOpen"
      :user-id="volumeManagerUserId"
      :tenant-name="volumeManagerTenantName"
    />

  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { ReloadOutlined, EditOutlined, DownOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import {
  getInstanceList, updateInstanceState, terminateInstance,
  getSecurityRules, releaseAllPorts, releaseOciPreset, addSecurityRule, deleteSecurityRule,
  getBootVolumes, updateBootVolume, getVcns,
  getTrafficData, changeIp,
  getInstanceNetworkDetail, addIpv6, removeIpv6,
  createReservedIp, listReservedIps, deleteReservedIp,
  assignReservedIp, unassignReservedIp,
  updateInstance,
  assignEphemeralIp, deletePublicIp, deleteSecondaryIp,
  createConsoleConnection, deleteConsoleConnection,
} from '../api/instance'
import { getTenantList, getTenantGroups } from '../api/tenant'
import { listAllVolumes, deleteVolume } from '../api/volume'
import VcnManager from './VcnManager.vue'
import VolumeManager from './VolumeManager.vue'
import { createTask, hasRunningTask } from '../api/task'
import { sendVerifyCode } from '../api/system'

interface TenantData {
  tenant: any
  instances: any[]
  loading: boolean
  collapsed: boolean
}

interface RegionTenantOption {
  id: string
  region: string
  regionLabel: string
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

const protoMap: Record<string, string> = { '6': 'TCP', '17': 'UDP', '1': 'ICMP', '58': 'ICMPv6', 'all': '全部' }

const secColumns = [
  { title: '协议', dataIndex: 'protocol', key: 'protocol', width: 80,
    customRender: ({ text }: any) => {
      const map: Record<string, string> = { '6': 'TCP', '17': 'UDP', '1': 'ICMP', '58': 'ICMPv6', 'all': '全部' }
      return map[text] || text
    }
  },
  { title: '来源/目的', dataIndex: 'source', key: 'source' },
  { title: '端口范围', dataIndex: 'portRange', key: 'portRange', width: 120 },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '操作', key: 'secAction', width: 80 },
]
const volColumns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName' },
  { title: '大小 (GB)', dataIndex: 'sizeInGBs', key: 'sizeInGBs', width: 100 },
  { title: '性能', dataIndex: 'vpusPerGB', key: 'vpusPerGB', width: 130 },
  { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
  { title: '操作', key: 'volAction', width: 80 },
]
const vcnColumns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName' },
  { title: 'CIDR', dataIndex: 'cidrBlock', key: 'cidrBlock', width: 160 },
  { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
  { title: '操作', key: 'vcnAction', width: 100 },
]

const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }

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

interface GroupNode {
  label: string
  key: string
  children?: GroupNode[]
  tenants: TenantData[]
}
const groupData = ref<{ level1: string[]; level2: Record<string, string[]> }>({ level1: [], level2: {} })

const COLLAPSE_KEY = 'instanceList.groupCollapse'
function loadCollapseState(): string[] {
  try { return JSON.parse(localStorage.getItem(COLLAPSE_KEY) || '[]') } catch { return [] }
}
function saveCollapseState(keys: string[]) {
  try { localStorage.setItem(COLLAPSE_KEY, JSON.stringify(keys)) } catch {}
}
const activeGroupKeys = ref<string[]>(loadCollapseState())
function onCollapseChange(keys: string | string[]) {
  const arr = Array.isArray(keys) ? keys : [keys]
  activeGroupKeys.value = arr
  saveCollapseState(arr)
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
    const res = await getTenantGroups()
    groupData.value = res.data || { level1: [], level2: {} }
  } catch {}
}

watch(searchKeyword, (kw) => {
  if (kw && hasGroups.value) {
    const matchedKeys: string[] = []
    for (const g of groupedTenants.value) {
      if (groupTenantCount(g) > 0) matchedKeys.push(g.key)
    }
    activeGroupKeys.value = matchedKeys
    saveCollapseState(matchedKeys)
  }
})

const activeTenantData = computed(() => {
  if (!activeTenantId.value) return null
  return tenantDataList.value.find(td => td.tenant.id === activeTenantId.value) || null
})
const regionTenantOptions = ref<RegionTenantOption[]>([])
const instancePanelVisible = computed({
  get: () => !!activeTenantData.value,
  set: (val: boolean) => {
    if (!val) activeTenantId.value = ''
  },
})
const instancePanelWidth = computed(() => (isMobile.value ? '100%' : 960))

function getRegionMemoryKey(tenant: any) {
  const tenantId = tenant?.ociTenantId || 'unknown-tenant'
  const userId = tenant?.ociUserId || 'unknown-user'
  return `instance-region:${tenantId}:${userId}`
}

function savePreferredRegion(tenant: any, region: string) {
  try {
    localStorage.setItem(getRegionMemoryKey(tenant), region || '')
  } catch {}
}

function getPreferredRegion(tenant: any) {
  try {
    return localStorage.getItem(getRegionMemoryKey(tenant)) || ''
  } catch {
    return ''
  }
}

function selectTenant(td: TenantData) {
  const sameAccountTenants = getSameAccountTenants(td.tenant)
  regionTenantOptions.value = sameAccountTenants.map(item => ({
    id: item.tenant.id,
    region: item.tenant.ociRegion,
    regionLabel: item.tenant.ociRegion || '未设置',
  }))

  const preferredRegion = getPreferredRegion(td.tenant)
  const targetTenant = sameAccountTenants.find(item => item.tenant.ociRegion === preferredRegion) || td

  activeTenantId.value = targetTenant.tenant.id
  savePreferredRegion(targetTenant.tenant, targetTenant.tenant.ociRegion || '')
  loadTenantInstances(targetTenant)
}

function getSameAccountTenants(tenant: any) {
  const sameAccount = tenantDataList.value.filter(item =>
    item.tenant.ociTenantId === tenant.ociTenantId &&
    item.tenant.ociUserId === tenant.ociUserId,
  )
  return sameAccount.sort((a, b) =>
    (a.tenant.ociRegion || '').localeCompare(b.tenant.ociRegion || ''),
  )
}

async function handleRegionSwitch(targetTenantId: string) {
  const td = tenantDataList.value.find(item => item.tenant.id === targetTenantId)
  if (!td) return
  savePreferredRegion(td.tenant, td.tenant.ociRegion || '')
  await loadTenantInstances(td)
}

const drawerVisible = ref(false)
const activeTab = ref('info')

const secLoading = ref(false)
const releaseLoading = ref(false)
const presetLoading = ref(false)
const ingressRules = ref<any[]>([])
const egressRules = ref<any[]>([])

const volLoading = ref(false)
const bootVolumes = ref<any[]>([])
const vcnLoading = ref(false)
const vcns = ref<any[]>([])

const allVolLoading = ref(false)
const allVolumes = ref<any[]>([])
const volSubTab = ref('BOOT')
const allVolColumns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName', ellipsis: true },
  { title: '大小 (GB)', dataIndex: 'sizeInGBs', key: 'sizeInGBs', width: 100 },
  { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
  { title: '创建时间', dataIndex: 'timeCreated', key: 'timeCreated', width: 180 },
  { title: '操作', key: 'delAction', width: 80 },
]
function volumesByType(type: string) {
  return allVolumes.value.filter(v => v.type === type)
}
async function loadAllVolumes() {
  if (!currentTenant.value) return
  allVolLoading.value = true
  try {
    const res = await listAllVolumes({ id: currentTenant.value.id })
    allVolumes.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载卷列表失败')
  } finally {
    allVolLoading.value = false
  }
}

const deleteVolModalVisible = ref(false)
const deleteVolTarget = ref<any>(null)
const deleteVolCode = ref('')
const deleteVolVerifyLoading = ref(false)

async function openDeleteVolume(record: any) {
  deleteVolTarget.value = record
  deleteVolCode.value = ''
  verifySending.value = true
  try {
    await sendVerifyCode('deleteVolume')
    message.success('验证码已发送至 Telegram')
    deleteVolModalVisible.value = true
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
  } finally {
    verifySending.value = false
  }
}
async function handleDeleteVolume() {
  if (!deleteVolCode.value || deleteVolCode.value.length !== 6) {
    message.warning('请输入6位验证码'); return
  }
  deleteVolVerifyLoading.value = true
  try {
    await deleteVolume({
      id: currentTenant.value.id,
      type: deleteVolTarget.value.type,
      volumeId: deleteVolTarget.value.id,
      verifyCode: deleteVolCode.value,
    })
    message.success('卷已删除')
    deleteVolModalVisible.value = false
    loadAllVolumes()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  } finally {
    deleteVolVerifyLoading.value = false
  }
}

const trafficLoading = ref(false)
const trafficMinutes = ref(60)
const trafficData = ref<any>(null)
const changeIpLoading = ref(false)

const netDetailLoading = ref(false)
const networkDetail = ref<any>(null)
const ipv6AddLoading = ref<Record<string, boolean>>({})
const ipv6RemoveLoading = ref<Record<string, boolean>>({})
const ephemeralIpLoading = ref(false)

const deleteRuleLoading = ref(false)
const addRuleVisible = ref(false)
const addRuleLoading = ref(false)
const ruleForm = reactive({ direction: 'ingress', protocol: 'TCP', source: '0.0.0.0/0', portMin: null as number | null, portMax: null as number | null, description: '' })

const editVolVisible = ref(false)
const editVolLoading = ref(false)
const editVolForm = reactive({ bootVolumeId: '', displayName: '', sizeInGBs: 50, vpusPerGB: 10 })

const reservedIps = ref<any[]>([])
const reservedIpListLoading = ref(false)
const createRipVisible = ref(false)
const createRipLoading = ref(false)
const createRipName = ref('')

const editInstanceVisible = ref(false)
const editInstanceLoading = ref(false)
const editInstanceForm = reactive({ displayName: '', ocpus: 1, memoryInGBs: 6 })
const isFlexShape = computed(() => currentInstance.value?.shape?.includes('Flex') ?? false)

const quickTaskVisible = ref(false)
const quickTaskLoading = ref(false)
const quickTaskTenant = ref<any>(null)
const quickTaskForm = reactive({
  architecture: 'ARM', operationSystem: 'Ubuntu',
  ocpus: 1, memory: 6, disk: 50, createNumbers: 1, interval: 60, rootPassword: '', customScript: '',
  assignPublicIp: true, assignIpv6: false,
})

const consoleLoading = ref(false)
const consoleData = ref<any>(null)

async function handleCreateConsole() {
  if (!currentInstance.value || !currentTenant.value) return
  consoleLoading.value = true
  try {
    const res = await createConsoleConnection({ id: currentTenant.value.id, instanceId: currentInstance.value.instanceId })
    consoleData.value = res.data
    message.success('控制台连接已创建')
  } catch (e: any) {
    message.error(e?.message || '创建控制台连接失败')
  } finally {
    consoleLoading.value = false
  }
}

function openConsoleWebSSH() {
  if (!consoleData.value) return
  const { tempUser, tempPassword } = consoleData.value
  const hash = `#console=1&host=127.0.0.1&port=22&user=${encodeURIComponent(tempUser)}&pass=${encodeURIComponent(tempPassword)}`
  window.open('/webssh/index.html' + hash, '_blank')
}

async function handleDeleteConsole() {
  if (!consoleData.value || !currentTenant.value) return
  consoleLoading.value = true
  try {
    await deleteConsoleConnection({ id: currentTenant.value.id, connectionId: consoleData.value.connectionId })
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
function openVcnManager(tenantId: string, vcn: any) {
  vcnManagerUserId.value = tenantId
  vcnManagerVcn.value = vcn
  vcnManagerOpen.value = true
}

const volumeManagerOpen = ref(false)
const volumeManagerUserId = ref('')
const volumeManagerTenantName = ref('')
function openVolumePanel(tenant: any) {
  volumeManagerUserId.value = tenant.id
  volumeManagerTenantName.value = tenant.username || tenant.tenantName || ''
  volumeManagerOpen.value = true
}

async function openVcnPanel(tenant: any) {
  vcnTenant.value = tenant
  vcnList.value = []
  reservedIps.value = []
  currentTenant.value = tenant
  vcnVisible.value = true
  vcnListLoading.value = true
  try {
    const res = await getVcns({ id: tenant.id })
    vcnList.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载 VCN 失败')
  } finally {
    vcnListLoading.value = false
  }
  loadReservedIps()
}

function formatBytes(bytes: number) {
  if (!bytes || bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return (bytes / Math.pow(1024, i)).toFixed(2) + ' ' + units[i]
}

async function loadAllTenants() {
  globalLoading.value = true
  try {
    const res = await getTenantList({ current: 1, size: 1000 })
    const records = res.data.records || []
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

async function loadTenantInstances(td: TenantData) {
  td.loading = true
  try {
    const res = await getInstanceList({ id: td.tenant.id })
    td.instances = res.data || []
  } catch {
    td.instances = []
  } finally {
    td.loading = false
  }
}

function onTabChange(key: string) {
  if (key === 'volume') loadBootVolumes()
  if (key === 'allVolumes') loadAllVolumes()
}

function openDetail(tenant: any, record: any) {
  currentTenant.value = tenant
  currentInstance.value = record
  activeTab.value = 'info'
  ingressRules.value = []
  egressRules.value = []
  bootVolumes.value = []
  vcns.value = []
  trafficData.value = null
  networkDetail.value = null
  consoleData.value = null
  drawerVisible.value = true
  loadNetworkDetail()
  loadSecurityRules()
}

async function handleAction(tenant: any, record: any, action: string) {
  actionLoading[record.instanceId] = true
  try {
    await updateInstanceState({ id: tenant.id, instanceId: record.instanceId, action })
    message.success('操作已提交')
    const td = tenantDataList.value.find(t => t.tenant.id === tenant.id)
    if (td) scheduleReload(() => loadTenantInstances(td), 3000)
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
  Modal.confirm({
    title: `确定${label}实例？`,
    content: `目标实例：${record.name || record.instanceId}`,
    okText: '确定',
    okButtonProps: danger ? { danger: true } : undefined,
    cancelText: '取消',
    onOk: () => handleAction(tenant, record, key),
  })
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
    })
    message.success('实例已终止')
    verifyModalVisible.value = false
    drawerVisible.value = false
    const td = tenantDataList.value.find(t => t.tenant.id === currentTenant.value.id)
    if (td) scheduleReload(() => loadTenantInstances(td), 3000)
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
    await changeIp({ id: currentTenant.value.id, instanceId: currentInstance.value.instanceId })
    message.success('换 IP 请求已提交')
    scheduleReload(() => loadNetworkDetail(), 3000)
  } catch (e: any) {
    message.error(e?.message || '换 IP 失败')
  } finally {
    changeIpLoading.value = false
  }
}

async function loadNetworkDetail() {
  if (!currentInstance.value || !currentTenant.value) return
  netDetailLoading.value = true
  try {
    const res = await getInstanceNetworkDetail({ id: currentTenant.value.id, instanceId: currentInstance.value.instanceId })
    networkDetail.value = res.data || null
  } catch (e: any) {
    message.error(e?.message || '加载网络详情失败')
  } finally {
    netDetailLoading.value = false
  }
}

async function handleAddIpv6(vnic?: any) {
  if (!currentInstance.value || !currentTenant.value) return
  const vnicId = vnic?.vnicId || 'default'
  ipv6AddLoading.value[vnicId] = true
  try {
    const res = await addIpv6({ id: currentTenant.value.id, instanceId: currentInstance.value.instanceId, vnicId: vnic?.vnicId })
    message.success('IPv6 已分配: ' + (res.data?.ipv6Address || ''))
    loadNetworkDetail()
    await checkIpv6SecurityHealth()
  } catch (e: any) {
    message.error(e?.message || '分配 IPv6 失败')
  } finally {
    ipv6AddLoading.value[vnicId] = false
  }
}

async function handleRemoveIpv6(ip6: any) {
  if (!currentTenant.value) return
  const ipv6Id = ip6?.ipv6Id
  if (!ipv6Id) {
    message.error('缺少 ipv6Id，无法取消分配')
    return
  }
  ipv6RemoveLoading.value[ipv6Id] = true
  try {
    await removeIpv6({ id: currentTenant.value.id, ipv6Id })
    message.success('IPv6 已取消分配')
    loadNetworkDetail()
  } catch (e: any) {
    message.error(e?.message || '取消分配 IPv6 失败')
  } finally {
    ipv6RemoveLoading.value[ipv6Id] = false
  }
}

function isIpv6Cidr(cidr: string | undefined) {
  if (!cidr) return false
  return cidr.includes(':')
}

async function checkIpv6SecurityHealth() {
  if (!currentInstance.value || !currentTenant.value) return
  try {
    const res = await getSecurityRules({
      id: currentTenant.value.id,
      instanceId: currentInstance.value.instanceId,
    })
    const data = res.data || []
    const ingress = data.filter((r: any) => r.direction === 'ingress')
    const egress = data.filter((r: any) => r.direction === 'egress')

    const hasIpv6Ingress = ingress.some((r: any) => isIpv6Cidr(r.source))
    const hasIpv6Egress = egress.some((r: any) => isIpv6Cidr(r.source))

    if (hasIpv6Ingress && hasIpv6Egress) return

    const missing: string[] = []
    if (!hasIpv6Ingress) missing.push('IPv6 入站规则')
    if (!hasIpv6Egress) missing.push('IPv6 出站规则')

    Modal.warning({
      title: 'IPv6 连通性自检提醒',
      content: `检测到当前安全列表缺少 ${missing.join('、')}，可能出现“有 IPv6 地址但不通”。建议前往“安全列表”补充对应 IPv6 规则（如 ::/0）。`,
      okText: '知道了',
    })
  } catch {
    // 忽略自检失败，不影响主流程
  }
}

async function loadSecurityRules() {
  if (!currentInstance.value || !currentTenant.value) return
  secLoading.value = true
  try {
    const res = await getSecurityRules({ id: currentTenant.value.id, instanceId: currentInstance.value.instanceId })
    const data = res.data || []
    ingressRules.value = data.filter((r: any) => r.direction === 'ingress')
    egressRules.value = data.filter((r: any) => r.direction === 'egress')
  } catch (e: any) {
    message.error(e?.message || '加载安全规则失败')
  } finally {
    secLoading.value = false
  }
}

async function handleReleaseAll() {
  if (!currentInstance.value || !currentTenant.value) return
  releaseLoading.value = true
  try {
    await releaseAllPorts({ id: currentTenant.value.id, instanceId: currentInstance.value.instanceId })
    message.success('已放行所有端口')
    loadSecurityRules()
  } catch (e: any) {
    message.error(e?.message || '放行失败')
  } finally {
    releaseLoading.value = false
  }
}

async function handleOciPreset() {
  if (!currentInstance.value || !currentTenant.value) return
  presetLoading.value = true
  try {
    await releaseOciPreset({ id: currentTenant.value.id, instanceId: currentInstance.value.instanceId })
    message.success('已应用纯TCP预设规则')
    loadSecurityRules()
  } catch (e: any) {
    message.error(e?.message || '应用预设失败')
  } finally {
    presetLoading.value = false
  }
}

function showAddRuleModal() {
  Object.assign(ruleForm, { direction: 'ingress', protocol: 'TCP', source: '0.0.0.0/0', portMin: null, portMax: null, description: '' })
  addRuleVisible.value = true
}

async function handleAddRule() {
  if (!currentInstance.value || !currentTenant.value) return
  if (ruleForm.protocol === 'TCP' || ruleForm.protocol === 'UDP') {
    if (!ruleForm.portMin && !ruleForm.portMax) {
      ruleForm.portMin = 1
      ruleForm.portMax = 65535
    } else if (!ruleForm.portMin || !ruleForm.portMax) {
      message.warning('请填写完整的端口范围，或留空表示全部端口')
      return
    }
  }
  addRuleLoading.value = true
  try {
    await addSecurityRule({
      id: currentTenant.value.id, instanceId: currentInstance.value.instanceId,
      direction: ruleForm.direction, protocol: ruleForm.protocol, source: ruleForm.source,
      portMin: ruleForm.portMin?.toString(), portMax: ruleForm.portMax?.toString(), description: ruleForm.description,
    })
    message.success('规则已添加')
    addRuleVisible.value = false
    loadSecurityRules()
  } catch (e: any) {
    message.error(e?.message || '添加规则失败')
  } finally {
    addRuleLoading.value = false
  }
}

async function handleDeleteSecurityRule(direction: string, ruleIndex: number) {
  if (!currentInstance.value || !currentTenant.value) return
  deleteRuleLoading.value = true
  try {
    await deleteSecurityRule({
      id: currentTenant.value.id,
      instanceId: currentInstance.value.instanceId,
      direction,
      ruleIndex,
    })
    message.success('规则已删除')
    loadSecurityRules()
  } catch (e: any) {
    message.error(e?.message || '删除规则失败')
  } finally {
    deleteRuleLoading.value = false
  }
}

async function loadBootVolumes() {
  if (!currentInstance.value || !currentTenant.value) return
  volLoading.value = true
  try {
    const res = await getBootVolumes({ id: currentTenant.value.id, instanceId: currentInstance.value.instanceId })
    bootVolumes.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载引导卷失败')
  } finally {
    volLoading.value = false
  }
}

function openEditVolume(record: any) {
  Object.assign(editVolForm, { bootVolumeId: record.id, displayName: record.displayName, sizeInGBs: record.sizeInGBs, vpusPerGB: record.vpusPerGB ?? 10 })
  editVolVisible.value = true
}

async function handleEditVolume() {
  editVolLoading.value = true
  try {
    await updateBootVolume({ id: currentTenant.value.id, ...editVolForm })
    message.success('引导卷已更新')
    editVolVisible.value = false
    loadBootVolumes()
  } catch (e: any) {
    message.error(e?.message || '更新引导卷失败')
  } finally {
    editVolLoading.value = false
  }
}

async function applyVolumePreset(size: number) {
  if (bootVolumes.value.length === 0) return
  const vol = bootVolumes.value[0]
  editVolLoading.value = true
  try {
    await updateBootVolume({
      id: currentTenant.value.id,
      bootVolumeId: vol.id,
      displayName: vol.displayName,
      sizeInGBs: size,
      vpusPerGB: 120,
    })
    message.success(`引导卷已调整为 ${size} GB / 120 VPUs`)
    loadBootVolumes()
  } catch (e: any) {
    message.error(e?.message || '调整引导卷失败')
  } finally {
    editVolLoading.value = false
  }
}

async function handleAssignEphemeralIp(ipd: any) {
  if (!currentInstance.value || !currentTenant.value) return
  ephemeralIpLoading.value = true
  try {
    await assignEphemeralIp({
      id: currentTenant.value.id,
      instanceId: currentInstance.value.instanceId,
      privateIpId: ipd.privateIpId,
    })
    message.success('公网 IPv4 已分配')
    loadNetworkDetail()
  } catch (e: any) {
    message.error(e?.message || '分配公网IP失败')
  } finally {
    ephemeralIpLoading.value = false
  }
}

async function handleDeletePublicIp(ipd: any) {
  if (!currentTenant.value) return
  try {
    await deletePublicIp({ id: currentTenant.value.id, privateIpId: ipd.privateIpId })
    message.success('公网IP已删除')
    loadNetworkDetail()
  } catch (e: any) {
    message.error(e?.message || '删除公网IP失败')
  }
}

async function handleDeleteSecondaryIp(ipd: any) {
  if (!currentTenant.value) return
  try {
    await deleteSecondaryIp({ id: currentTenant.value.id, privateIpId: ipd.privateIpId })
    message.success('辅助IP已删除')
    loadNetworkDetail()
  } catch (e: any) {
    message.error(e?.message || '删除辅助IP失败')
  }
}

function getPrimaryIps(vnic: any) {
  return (vnic.ipDetails || []).filter((ip: any) => ip.isPrimary)
}
function getSecondaryIps(vnic: any) {
  return (vnic.ipDetails || []).filter((ip: any) => !ip.isPrimary)
}

const auxIpLoading = ref(false)
async function handleAddAuxIp() {
  if (!currentTenant.value || !currentInstance.value) return
  auxIpLoading.value = true
  try {
    let ipId = ''
    let ipAddr = ''

    // 先查找未绑定的预留IP
    try {
      const listRes = await listReservedIps({ id: currentTenant.value.id })
      const unbound = (listRes.data || []).find((ip: any) => !ip.isAssigned)
      if (unbound) {
        ipId = unbound.id
        ipAddr = unbound.ipAddress
        message.info('使用已有预留IP: ' + ipAddr)
      }
    } catch {}

    // 没有未绑定的预留IP，才创建新的
    if (!ipId) {
      const res = await createReservedIp({ id: currentTenant.value.id, displayName: 'aux-' + Date.now() })
      ipId = res.data?.id
      ipAddr = res.data?.ipAddress || ''
      if (!ipId) throw new Error('创建预留IP失败')
      message.success('预留IP已创建: ' + ipAddr)
    }

    await assignReservedIp({ id: currentTenant.value.id, publicIpId: ipId, instanceId: currentInstance.value.instanceId })
    message.success('辅助IP已附加到实例: ' + ipAddr)
    loadNetworkDetail()
  } catch (e: any) {
    message.error(e?.message || '添加辅助IP失败')
  } finally {
    auxIpLoading.value = false
  }
}

function showCreateReservedIpModal() { createRipName.value = ''; createRipVisible.value = true }

async function handleCreateReservedIp() {
  createRipLoading.value = true
  try {
    const res = await createReservedIp({ id: currentTenant.value.id, displayName: createRipName.value || undefined })
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
    const res = await listReservedIps({ id: currentTenant.value.id })
    reservedIps.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载预留IP失败')
  } finally {
    reservedIpListLoading.value = false
  }
}

async function handleDeleteReservedIp(publicIpId: string) {
  try {
    await deleteReservedIp({ id: currentTenant.value.id, publicIpId })
    message.success('预留IP已删除')
    loadReservedIps()
  } catch (e: any) { message.error(e?.message || '删除预留IP失败') }
}

async function handleAssignReservedIp(publicIpId: string) {
  if (!currentInstance.value) return
  try {
    await assignReservedIp({ id: currentTenant.value.id, publicIpId, instanceId: currentInstance.value.instanceId })
    message.success('预留IP已绑定')
    loadReservedIps(); loadNetworkDetail()
  } catch (e: any) { message.error(e?.message || '绑定失败') }
}

async function handleUnassignReservedIp(publicIpId: string) {
  try {
    await unassignReservedIp({ id: currentTenant.value.id, publicIpId })
    message.success('预留IP已解绑')
    loadReservedIps(); loadNetworkDetail()
  } catch (e: any) { message.error(e?.message || '解绑失败') }
}

function openEditInstance() {
  if (!currentInstance.value) return
  editInstanceForm.displayName = currentInstance.value.name || ''
  editInstanceForm.ocpus = currentInstance.value.ocpus || 1
  editInstanceForm.memoryInGBs = currentInstance.value.memoryInGBs || 6
  editInstanceVisible.value = true
}

async function handleEditInstance() {
  if (!currentInstance.value || !currentTenant.value) return
  editInstanceLoading.value = true
  try {
    const payload: any = { id: currentTenant.value.id, instanceId: currentInstance.value.instanceId }
    if (editInstanceForm.displayName && editInstanceForm.displayName !== currentInstance.value.name) payload.displayName = editInstanceForm.displayName
    if (isFlexShape.value) {
      if (editInstanceForm.ocpus !== currentInstance.value.ocpus) payload.ocpus = editInstanceForm.ocpus
      if (editInstanceForm.memoryInGBs !== currentInstance.value.memoryInGBs) payload.memoryInGBs = editInstanceForm.memoryInGBs
    }
    if (!payload.displayName && !payload.ocpus && !payload.memoryInGBs) { message.info('未检测到修改'); editInstanceLoading.value = false; return }
    const res = await updateInstance(payload)
    message.success('实例已更新')
    if (res.data?.name) currentInstance.value.name = res.data.name
    if (res.data?.ocpus) currentInstance.value.ocpus = res.data.ocpus
    if (res.data?.memoryInGBs) currentInstance.value.memoryInGBs = res.data.memoryInGBs
    editInstanceVisible.value = false
    const td = tenantDataList.value.find(t => t.tenant.id === currentTenant.value.id)
    if (td) loadTenantInstances(td)
  } catch (e: any) {
    message.error(e?.message || '修改实例失败')
  } finally {
    editInstanceLoading.value = false
  }
}

async function loadVcns() {
  vcnLoading.value = true
  try {
    const res = await getVcns({ id: currentTenant.value.id })
    vcns.value = res.data || []
  } catch (e: any) { message.error(e?.message || '加载 VCN 失败') }
  finally { vcnLoading.value = false }
}

async function loadTraffic() {
  if (!currentInstance.value || !currentTenant.value) return
  trafficLoading.value = true
  try {
    const res = await getTrafficData({ id: currentTenant.value.id, instanceId: currentInstance.value.instanceId, minutes: trafficMinutes.value })
    trafficData.value = res.data || { inbound: 0, outbound: 0 }
  } catch (e: any) { message.error(e?.message || '加载流量数据失败') }
  finally { trafficLoading.value = false }
}

function openQuickTask(tenant: any) {
  quickTaskTenant.value = tenant
  Object.assign(quickTaskForm, {
    architecture: 'ARM', operationSystem: 'Ubuntu',
    ocpus: 1, memory: 6, disk: 50, createNumbers: 1, interval: 60, rootPassword: '', customScript: '',
    assignPublicIp: true, assignIpv6: false,
  })
  quickTaskVisible.value = true
}

async function handleQuickTask() {
  if (!quickTaskTenant.value) return

  if (!quickTaskForm.rootPassword) {
    const chars = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%'
    let pwd = ''
    for (let i = 0; i < 16; i++) pwd += chars[Math.floor(Math.random() * chars.length)]
    quickTaskForm.rootPassword = pwd
  }

  try {
    const checkRes = await hasRunningTask({ userId: quickTaskTenant.value.id })
    if (checkRes.data === true) {
      Modal.confirm({
        title: '重复任务提醒',
        content: '该账户已有正在运行的开机任务，是否仍要重复提交？',
        okText: '继续创建',
        cancelText: '取消',
        onOk: () => doQuickTask(),
      })
      return
    }
  } catch {}

  doQuickTask()
}

async function doQuickTask() {
  quickTaskLoading.value = true
  try {
    await createTask({ userId: quickTaskTenant.value.id, ...quickTaskForm })
    message.success('开机任务已创建')
    quickTaskVisible.value = false
  } catch (e: any) {
    message.error(e?.message || '创建任务失败')
  } finally {
    quickTaskLoading.value = false
  }
}

onMounted(() => {
  loadGroups()
  loadAllTenants()
  window.addEventListener('resize', checkMobile)
})
onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
  pendingTimers.forEach((t: any) => clearTimeout(t))
  pendingTimers.clear()
})
</script>

<style scoped>
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
.group-collapse { margin-bottom: 16px; }
.group-collapse :deep(.ant-collapse-header) { font-weight: 600; font-size: 14px; }
.group-collapse-l2 { margin-top: 8px; }
.group-collapse-l2 :deep(.ant-collapse-header) { font-weight: 500; font-size: 13px; padding-left: 12px !important; }
.group-header-label { vertical-align: middle; }
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
.instance-drawer-title {
  display: flex;
  align-items: center;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-main);
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
  .instance-toolbar { flex-direction: column; align-items: stretch; }
  .toolbar-left, .toolbar-right { width: 100%; flex-wrap: wrap; }
  .toolbar-left :deep(.ant-input-search) { width: 100% !important; flex: 1 1 100%; }
  .toolbar-right { justify-content: space-between; }
  .tenant-grid { grid-template-columns: 1fr 1fr; gap: 10px; }
  .tenant-card { padding: 14px; border-radius: 12px; }
  .tc-icon { font-size: 22px; }
  .tc-name { font-size: 13px; }
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
</style>
