<template>
<div class="table-toolbar">
  <a-space wrap>
    <a-input-search v-model:value="searchTextModel" placeholder="搜索租户" allow-clear @search="onSearchTenants" style="width: 200px" />
    <a-button @click="openGroupManager">
      <template #icon><FolderOutlined /></template>管理分组
    </a-button>
    <a-button type="primary" @click="showAddModal">
      <template #icon><PlusOutlined /></template>新增配置
    </a-button>
    <a-button :disabled="!selectedRowKeys.length" @click="openBatchMoveModal">
      批量移动
    </a-button>
    <a-button danger :disabled="!selectedRowKeys.length" @click="handleBatchDelete">
      批量删除
    </a-button>
  </a-space>
</div>

<a-spin :spinning="loading">
  <!-- 搜索模式：平铺 -->
  <template v-if="normalizedSearchText">
    <a-table v-if="!isMobile" :columns="columns" :data-source="tableData" :loading="loading"
      :scroll="{ x: tenantTableScrollX }"
      :row-selection="{ selectedRowKeys, onChange: onSelectChange }" :pagination="false"
      row-key="id" size="middle">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'username'">
          <span class="tenant-name-cell" :title="record.username">{{ record.username }}</span>
        </template>
        <template v-if="column.key === 'tenantName'">
          <a-tooltip v-if="record.tenantName" :title="record.tenantName">
            <span class="tenant-table-text-cell">{{ displayTenantName(record.tenantName) }}</span>
          </a-tooltip>
          <span v-else style="color: var(--text-sub); font-size: 12px">获取中...</span>
        </template>
        <template v-if="column.key === 'ociRegion'">
          <a-tag color="blue">{{ getOciRegionDisplayName(record.ociRegion) }}</a-tag>
          <div style="font-size: 11px; color: var(--text-sub); margin-top: 2px">{{ record.ociRegion }}</div>
        </template>
        <template v-if="column.key === 'taskStatus'">
          <a-badge v-if="record.hasRunningTask" status="processing" text="执行开机任务中" />
          <span v-else style="color: #999">无开机任务</span>
        </template>
        <template v-if="column.key === 'planType'">
          <span :class="planTypeBadgeClass(record.planType)" :style="planTypeBadgeStyle(record.planType)">{{ formatPlanBadge(record.planType, '获取中...') }}</span>
        </template>
        <template v-if="column.key === 'createTime'">
          {{ formatTenantAddedTime(record.createTime) }}
        </template>
        <template v-if="column.key === 'action'">
          <a-space>
            <a-button type="link" size="small" @click="showEditModal(record)">编辑</a-button>
            <a-button type="link" size="small" @click="openTenantMgmt(record)">租户</a-button>
            <a-button type="link" size="small" @click="openDomainMgmt(record)">域</a-button>
            <a-button type="link" size="small" @click="goUserManagement(record)">用户</a-button>
            <a-popconfirm title="确定删除?" @confirm="handleDelete(record.id)">
              <a-button type="link" danger size="small">删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>
    <template v-else>
      <a-empty v-if="tableData.length === 0" description="无搜索结果" />
      <VirtualTenantCardList
        v-else-if="shouldVirtualizeTenantMobileCards(tableData.length)"
        :items="tableData"
        :item-key="tenantRowKey"
        :estimate-size="188"
        :max-height="tenantMobileVirtualMaxHeight"
        :reset-key="tenantMobileSearchResetKey"
      >
        <template #item="{ item: r }">
          <div class="mobile-card">
            <div class="mobile-card-header">
              <span class="mobile-card-title">{{ r.username }}</span>
              <span :class="planTypeBadgeClass(r.planType)" :style="planTypeBadgeStyle(r.planType)">{{ formatPlanBadge(r.planType, '?') }}</span>
            </div>
            <div class="mobile-card-body">
            <div class="mobile-card-row">
              <span class="label">租户名</span>
              <span class="value">{{ r.tenantName || '获取中...' }}</span>
            </div>
            <div class="mobile-card-row"><span class="label">主区域</span><a-tag color="blue" style="margin:0">{{ getOciRegionDisplayName(r.ociRegion) }}</a-tag></div>
            <div class="mobile-card-row">
              <span class="label">开机任务</span>
              <a-badge v-if="r.hasRunningTask" status="processing" text="执行中" />
              <span v-else style="color: #999">无</span>
            </div>
            <div class="mobile-card-row">
              <span class="label">添加日期</span>
              <span class="value">{{ formatTenantAddedTime(r.createTime) }}</span>
            </div>
          </div>
          <div class="mobile-card-actions">
            <a-button type="link" size="small" @click="showEditModal(r)">编辑</a-button>
            <a-button type="link" size="small" @click="openTenantMgmt(r)">租户</a-button>
            <a-button type="link" size="small" @click="openDomainMgmt(r)">域</a-button>
            <a-button type="link" size="small" @click="goUserManagement(r)">用户</a-button>
            <a-popconfirm title="确定删除?" @confirm="handleDelete(r.id)">
              <a-button type="link" danger size="small">删除</a-button>
            </a-popconfirm>
          </div>
        </div>
        </template>
      </VirtualTenantCardList>
      <template v-else>
        <div v-for="r in tableData" :key="r.id" class="mobile-card">
          <div class="mobile-card-header">
            <span class="mobile-card-title">{{ r.username }}</span>
            <span :class="planTypeBadgeClass(r.planType)" :style="planTypeBadgeStyle(r.planType)">{{ formatPlanBadge(r.planType, '?') }}</span>
          </div>
          <div class="mobile-card-body">
          <div class="mobile-card-row">
            <span class="label">租户名</span>
            <span class="value">{{ r.tenantName || '获取中...' }}</span>
          </div>
          <div class="mobile-card-row"><span class="label">主区域</span><a-tag color="blue" style="margin:0">{{ getOciRegionDisplayName(r.ociRegion) }}</a-tag></div>
          <div class="mobile-card-row">
            <span class="label">开机任务</span>
            <a-badge v-if="r.hasRunningTask" status="processing" text="执行中" />
            <span v-else style="color: #999">无</span>
          </div>
          <div class="mobile-card-row">
            <span class="label">添加日期</span>
            <span class="value">{{ formatTenantAddedTime(r.createTime) }}</span>
          </div>
        </div>
        <div class="mobile-card-actions">
          <a-button type="link" size="small" @click="showEditModal(r)">编辑</a-button>
          <a-button type="link" size="small" @click="openTenantMgmt(r)">租户</a-button>
          <a-button type="link" size="small" @click="openDomainMgmt(r)">域</a-button>
          <a-button type="link" size="small" @click="goUserManagement(r)">用户</a-button>
          <a-popconfirm title="确定删除?" @confirm="handleDelete(r.id)">
            <a-button type="link" danger size="small">删除</a-button>
          </a-popconfirm>
        </div>
      </div>
      </template>
    </template>
  </template>

  <!-- 分组视图 -->
  <template v-else>
    <div v-for="(group, gi) in displayGroups" :key="group.key" class="group-section"
      :data-group-idx="gi"
      @dragover.prevent="onDragOver($event, gi)"
      @drop="onDrop($event, gi)"
      :class="{ 'drag-over-top': dragOverIndex === gi && dragOverPos === 'top' && dragFromIndex !== gi,
                 'drag-over-bottom': dragOverIndex === gi && dragOverPos === 'bottom' && dragFromIndex !== gi,
                 'dragging': dragFromIndex === gi }">
      <!-- 一级分组卡片 -->
      <div class="group-card">
        <div class="group-card-header">
          <div class="group-card-header-main">
          <div class="drag-handle" title="拖动排序" draggable="true"
            @dragstart="onDragStart($event, gi)"
            @dragend="onDragEnd">
            <span style="font-size: 14px; line-height: 1;">⠿</span>
          </div>
          <div class="collapse-btn" @click="toggleGroup(group.key)">
            <DownOutlined v-if="expandedGroups.has(group.key)" />
            <RightOutlined v-else />
          </div>
          <div class="group-dot" :style="{ background: groupColors[gi % groupColors.length], boxShadow: '0 0 8px ' + groupColors[gi % groupColors.length] + '80' }"></div>
          <span class="group-name" @click="toggleGroup(group.key)">{{ group.label }}</span>

          <div class="group-stats">
            <a-badge
              :count="groupTotalCount(group)"
              :show-zero="true"
              class="group-tenant-count-badge oci-group-count-badge"
            />
            <template v-if="!isMobile" v-for="(pc, pt) in getPlanCounts(group)" :key="pt">
              <span :class="['plan-tag', planSummaryTagClass(String(pt))]" :style="planTypeBadgeStyle(String(pt))">{{ pt }}×{{ pc }}</span>
            </template>
          </div>
          </div>

          <div class="group-card-header-actions">
            <button class="group-action-btn" @click.stop="handleAddSubGroup(group.label)">
              <PlusOutlined /> 子分组
            </button>
            <a-dropdown :trigger="['click']" @click.stop>
              <button class="group-action-btn" title="编辑分组"><SettingOutlined /></button>
              <template #overlay>
                <a-menu>
                  <a-menu-item @click="openRenameGroup(group.label, '1')">重命名</a-menu-item>
                  <a-menu-item danger @click="handleDeleteGroup(group.label, '1')">删除分组</a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
        </div>

      <!-- 二级子分组卡片 -->
      <template v-if="group.children && expandedGroups.has(group.key)">
        <div v-for="(sub, si) in group.children" :key="sub.key" class="group-card subgroup-card"
          @dragover="onSubDragOver($event, group.label, Number(si))"
          @drop="onSubDrop($event, group.label, Number(si))"
          :class="{ 'sub-drag-over-top': subDragParent === group.label && subDragOverIndex === Number(si) && subDragOverPos === 'top' && subDragFromIndex !== Number(si),
                     'sub-drag-over-bottom': subDragParent === group.label && subDragOverIndex === Number(si) && subDragOverPos === 'bottom' && subDragFromIndex !== Number(si),
                     'dragging': subDragParent === group.label && subDragFromIndex === Number(si) }">
          <div class="group-card-header subgroup-header">
            <div class="group-card-header-main">
            <div class="drag-handle" title="拖动排序" draggable="true"
              @dragstart.stop="onSubDragStart($event, group.label, Number(si))"
              @dragend="onSubDragEnd">
              <span style="font-size: 12px; line-height: 1;">⠿</span>
            </div>
            <div class="collapse-btn" @click="toggleGroup(sub.key)">
              <DownOutlined v-if="expandedGroups.has(sub.key)" />
              <RightOutlined v-else />
            </div>
            <span class="subgroup-name" @click="toggleGroup(sub.key)">{{ sub.label }}</span>
            <a-badge
              :count="sub.tenants.length"
              :show-zero="true"
              class="group-tenant-count-badge oci-group-count-badge"
            />
            </div>
            <div class="group-card-header-actions">
              <a-dropdown :trigger="['click']" @click.stop>
                <button class="group-action-btn" title="编辑分组"><SettingOutlined /></button>
                <template #overlay>
                  <a-menu>
                    <a-menu-item @click="openRenameGroup(sub.label, '2')">重命名</a-menu-item>
                    <a-menu-item danger @click="handleDeleteGroup(sub.label, '2')">删除分组</a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </div>
          </div>

          <div v-if="expandedGroups.has(sub.key)" class="group-body">
            <template v-if="sub.tenants.length">
              <a-table v-if="!isMobile" :columns="columns" :data-source="sub.tenants" :pagination="false"
                :scroll="{ x: tenantTableScrollX }"
                :row-selection="{ selectedRowKeys, onChange: onSelectChange }"
                row-key="id" size="small">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'username'">
                    <span class="tenant-name-cell" :title="record.username">{{ record.username }}</span>
                  </template>
                  <template v-if="column.key === 'tenantName'">
                    <a-tooltip v-if="record.tenantName" :title="record.tenantName">
                      <span class="tenant-table-text-cell">{{ displayTenantName(record.tenantName) }}</span>
                    </a-tooltip>
                    <span v-else style="color: var(--text-sub); font-size: 12px">获取中...</span>
                  </template>
                  <template v-if="column.key === 'ociRegion'">
                    <a-tag color="blue">{{ getOciRegionDisplayName(record.ociRegion) }}</a-tag>
                    <div style="font-size: 11px; color: var(--text-sub); margin-top: 2px">{{ record.ociRegion }}</div>
                  </template>
                  <template v-if="column.key === 'taskStatus'">
                    <a-badge v-if="record.hasRunningTask" status="processing" text="执行开机任务中" />
                    <span v-else style="color: #999">无开机任务</span>
                  </template>
                  <template v-if="column.key === 'planType'">
                    <span :class="planTypeBadgeClass(record.planType)" :style="planTypeBadgeStyle(record.planType)">{{ formatPlanBadge(record.planType, '获取中...') }}</span>
                  </template>
                  <template v-if="column.key === 'createTime'">
                    {{ formatTenantAddedTime(record.createTime) }}
                  </template>
                  <template v-if="column.key === 'action'">
                    <a-space>
                      <a-button type="link" size="small" @click="showEditModal(record)">编辑</a-button>
                      <a-button type="link" size="small" @click="openTenantMgmt(record)">租户</a-button>
                      <a-button type="link" size="small" @click="openDomainMgmt(record)">域</a-button>
                      <a-button type="link" size="small" @click="goUserManagement(record)">用户</a-button>
                      <a-popconfirm title="确定删除?" @confirm="handleDelete(record.id)">
                        <a-button type="link" danger size="small">删除</a-button>
                      </a-popconfirm>
                    </a-space>
                  </template>
                </template>
              </a-table>
              <template v-else>
                <VirtualTenantCardList
                  v-if="shouldVirtualizeTenantMobileCards(sub.tenants.length)"
                  :items="sub.tenants"
                  :item-key="tenantRowKey"
                  :estimate-size="188"
                  :max-height="tenantMobileVirtualMaxHeight"
                  :reset-key="tenantGroupVirtualResetKey(sub.key, sub.tenants)"
                >
                  <template #item="{ item: r }">
                    <div class="mobile-card">
                      <div class="mobile-card-header">
                        <span class="mobile-card-title">{{ r.username }}</span>
                        <span :class="planTypeBadgeClass(r.planType)" :style="planTypeBadgeStyle(r.planType)">{{ formatPlanBadge(r.planType, '?') }}</span>
                      </div>
                      <div class="mobile-card-body">
                        <div class="mobile-card-row">
                          <span class="label">租户名</span>
                          <span class="value">{{ r.tenantName || '获取中...' }}</span>
                        </div>
                        <div class="mobile-card-row"><span class="label">主区域</span><a-tag color="blue" style="margin:0">{{ getOciRegionDisplayName(r.ociRegion) }}</a-tag></div>
                        <div class="mobile-card-row">
                          <span class="label">任务</span>
                          <a-badge v-if="r.hasRunningTask" status="processing" text="执行中" />
                          <span v-else style="color: #999">无</span>
                        </div>
                        <div class="mobile-card-row">
                          <span class="label">添加日期</span>
                          <span class="value">{{ formatTenantAddedTime(r.createTime) }}</span>
                        </div>
                      </div>
                      <div class="mobile-card-actions">
                        <a-button type="link" size="small" @click="showEditModal(r)">编辑</a-button>
                        <a-button type="link" size="small" @click="openTenantMgmt(r)">租户</a-button>
                        <a-button type="link" size="small" @click="openDomainMgmt(r)">域</a-button>
                        <a-button type="link" size="small" @click="goUserManagement(r)">用户</a-button>
                        <a-popconfirm title="确定删除?" @confirm="handleDelete(r.id)">
                          <a-button type="link" danger size="small">删除</a-button>
                        </a-popconfirm>
                      </div>
                    </div>
                  </template>
                </VirtualTenantCardList>
                <template v-else>
                <div v-for="r in sub.tenants" :key="r.id" class="mobile-card">
                  <div class="mobile-card-header">
                    <span class="mobile-card-title">{{ r.username }}</span>
                    <span :class="planTypeBadgeClass(r.planType)" :style="planTypeBadgeStyle(r.planType)">{{ formatPlanBadge(r.planType, '?') }}</span>
                  </div>
                  <div class="mobile-card-body">
                    <div class="mobile-card-row">
                      <span class="label">租户名</span>
                      <span class="value">{{ r.tenantName || '获取中...' }}</span>
                    </div>
                    <div class="mobile-card-row"><span class="label">主区域</span><a-tag color="blue" style="margin:0">{{ getOciRegionDisplayName(r.ociRegion) }}</a-tag></div>
                    <div class="mobile-card-row">
                      <span class="label">任务</span>
                      <a-badge v-if="r.hasRunningTask" status="processing" text="执行中" />
                      <span v-else style="color: #999">无</span>
                    </div>
                    <div class="mobile-card-row">
                      <span class="label">添加日期</span>
                      <span class="value">{{ formatTenantAddedTime(r.createTime) }}</span>
                    </div>
                  </div>
                  <div class="mobile-card-actions">
                    <a-button type="link" size="small" @click="showEditModal(r)">编辑</a-button>
                    <a-button type="link" size="small" @click="openTenantMgmt(r)">租户</a-button>
                    <a-button type="link" size="small" @click="openDomainMgmt(r)">域</a-button>
                    <a-button type="link" size="small" @click="goUserManagement(r)">用户</a-button>
                    <a-popconfirm title="确定删除?" @confirm="handleDelete(r.id)">
                      <a-button type="link" danger size="small">删除</a-button>
                    </a-popconfirm>
                  </div>
                </div>
                </template>
              </template>
            </template>
            <div v-else style="text-align: center; padding: 20px; color: var(--text-sub); font-size: 12px;">
              暂无租户
            </div>
          </div>
        </div>
      </template>
      <div v-if="expandedGroups.has(group.key) && group.tenants.length" class="group-card subgroup-card">
        <div class="group-body">
          <template v-if="group.tenants.length">
            <a-table v-if="!isMobile" :columns="columns" :data-source="group.tenants" :pagination="false"
              :scroll="{ x: tenantTableScrollX }"
              :row-selection="{ selectedRowKeys, onChange: onSelectChange }"
              row-key="id" size="small">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'username'">
                  <span class="tenant-name-cell" :title="record.username">{{ record.username }}</span>
                </template>
                <template v-if="column.key === 'tenantName'">
                  <a-tooltip v-if="record.tenantName" :title="record.tenantName">
                    <span class="tenant-table-text-cell">{{ displayTenantName(record.tenantName) }}</span>
                  </a-tooltip>
                  <span v-else style="color: var(--text-sub); font-size: 12px">获取中...</span>
                </template>
                <template v-if="column.key === 'ociRegion'">
                  <a-tag color="blue">{{ getOciRegionDisplayName(record.ociRegion) }}</a-tag>
                  <div style="font-size: 11px; color: var(--text-sub); margin-top: 2px">{{ record.ociRegion }}</div>
                </template>
                <template v-if="column.key === 'taskStatus'">
                  <a-badge v-if="record.hasRunningTask" status="processing" text="执行开机任务中" />
                  <span v-else style="color: #999">无开机任务</span>
                </template>
                <template v-if="column.key === 'planType'">
                  <span :class="planTypeBadgeClass(record.planType)" :style="planTypeBadgeStyle(record.planType)">{{ formatPlanBadge(record.planType, '获取中...') }}</span>
                </template>
                <template v-if="column.key === 'createTime'">
                  {{ formatTenantAddedTime(record.createTime) }}
                </template>
                <template v-if="column.key === 'action'">
                  <a-space>
                    <a-button type="link" size="small" @click="showEditModal(record)">编辑</a-button>
                    <a-button type="link" size="small" @click="openTenantMgmt(record)">租户</a-button>
                    <a-button type="link" size="small" @click="openDomainMgmt(record)">域</a-button>
                    <a-button type="link" size="small" @click="goUserManagement(record)">用户</a-button>
                    <a-popconfirm title="确定删除?" @confirm="handleDelete(record.id)">
                      <a-button type="link" danger size="small">删除</a-button>
                    </a-popconfirm>
                  </a-space>
                </template>
              </template>
            </a-table>
            <template v-else>
              <VirtualTenantCardList
                v-if="shouldVirtualizeTenantMobileCards(group.tenants.length)"
                :items="group.tenants"
                :item-key="tenantRowKey"
                :estimate-size="188"
                :max-height="tenantMobileVirtualMaxHeight"
                :reset-key="tenantGroupVirtualResetKey(group.key, group.tenants)"
              >
                <template #item="{ item: r }">
                  <div class="mobile-card">
                    <div class="mobile-card-header">
                      <span class="mobile-card-title">{{ r.username }}</span>
                      <span :class="planTypeBadgeClass(r.planType)" :style="planTypeBadgeStyle(r.planType)">{{ formatPlanBadge(r.planType, '?') }}</span>
                    </div>
                    <div class="mobile-card-body">
                      <div class="mobile-card-row">
                        <span class="label">租户名</span>
                        <span class="value">{{ r.tenantName || '获取中...' }}</span>
                      </div>
                      <div class="mobile-card-row"><span class="label">主区域</span><a-tag color="blue" style="margin:0">{{ getOciRegionDisplayName(r.ociRegion) }}</a-tag></div>
                      <div class="mobile-card-row">
                        <span class="label">任务</span>
                        <a-badge v-if="r.hasRunningTask" status="processing" text="执行中" />
                        <span v-else style="color: #999">无</span>
                      </div>
                      <div class="mobile-card-row">
                        <span class="label">添加日期</span>
                        <span class="value">{{ formatTenantAddedTime(r.createTime) }}</span>
                      </div>
                    </div>
                    <div class="mobile-card-actions">
                      <a-button type="link" size="small" @click="showEditModal(r)">编辑</a-button>
                      <a-button type="link" size="small" @click="openTenantMgmt(r)">租户</a-button>
                      <a-button type="link" size="small" @click="openDomainMgmt(r)">域</a-button>
                      <a-button type="link" size="small" @click="goUserManagement(r)">用户</a-button>
                      <a-popconfirm title="确定删除?" @confirm="handleDelete(r.id)">
                        <a-button type="link" danger size="small">删除</a-button>
                      </a-popconfirm>
                    </div>
                  </div>
                </template>
              </VirtualTenantCardList>
              <template v-else>
              <div v-for="r in group.tenants" :key="r.id" class="mobile-card">
                <div class="mobile-card-header">
                  <span class="mobile-card-title">{{ r.username }}</span>
                  <span :class="planTypeBadgeClass(r.planType)" :style="planTypeBadgeStyle(r.planType)">{{ formatPlanBadge(r.planType, '?') }}</span>
                </div>
                <div class="mobile-card-body">
                  <div class="mobile-card-row">
                    <span class="label">租户名</span>
                    <span class="value">{{ r.tenantName || '获取中...' }}</span>
                  </div>
                  <div class="mobile-card-row"><span class="label">主区域</span><a-tag color="blue" style="margin:0">{{ getOciRegionDisplayName(r.ociRegion) }}</a-tag></div>
                  <div class="mobile-card-row">
                    <span class="label">任务</span>
                    <a-badge v-if="r.hasRunningTask" status="processing" text="执行中" />
                    <span v-else style="color: #999">无</span>
                  </div>
                  <div class="mobile-card-row">
                    <span class="label">添加日期</span>
                    <span class="value">{{ formatTenantAddedTime(r.createTime) }}</span>
                  </div>
                </div>
                <div class="mobile-card-actions">
                  <a-button type="link" size="small" @click="showEditModal(r)">编辑</a-button>
                  <a-button type="link" size="small" @click="openTenantMgmt(r)">租户</a-button>
                  <a-button type="link" size="small" @click="openDomainMgmt(r)">域</a-button>
                  <a-button type="link" size="small" @click="goUserManagement(r)">用户</a-button>
                  <a-popconfirm title="确定删除?" @confirm="handleDelete(r.id)">
                    <a-button type="link" danger size="small">删除</a-button>
                  </a-popconfirm>
                </div>
              </div>
              </template>
            </template>
          </template>
        </div>
      </div>
      </div>
    </div>

    <div v-if="!groupTree.length && !loading" style="text-align: center; padding: 40px; color: var(--text-sub)">
      暂无租户配置
    </div>
  </template>
</a-spin>

</template>
<script setup lang="ts">
import { computed, toRefs } from 'vue'
import { DownOutlined, FolderOutlined, PlusOutlined, RightOutlined, SettingOutlined } from '@ant-design/icons-vue'
import VirtualTenantCardList from '../../../components/tenant/VirtualTenantCardList.vue'

const props = defineProps<{
  searchText: string
  normalizedSearchText: string
  loading: boolean
  isMobile: boolean
  columns: any[]
  tableData: any[]
  displayGroups: any[]
  selectedRowKeys: string[]
  tenantTableScrollX: number
  tenantMobileVirtualMaxHeight: number
  tenantMobileSearchResetKey: string
  expandedGroups: Set<string>
  groupTree: any[]
  groupColors: string[]
  dragOverIndex: number
  dragOverPos: string
  dragFromIndex: number
  subDragParent: string
  subDragOverIndex: number
  subDragOverPos: string
  subDragFromIndex: number
  displayTenantName: (value: any) => string
  getOciRegionDisplayName: (region: any) => string
  formatTenantAddedTime: (value?: string | null) => string
  formatPlanBadge: (plan: string | null | undefined, fallback?: string) => string
  planTypeBadgeClass: (plan: string | null | undefined) => any
  planTypeBadgeStyle: (plan: string | null | undefined) => any
  planSummaryTagClass: (plan: string) => string
  groupTotalCount: (group: any) => number
  getPlanCounts: (group: any) => Record<string, number>
  tenantRowKey: (item: unknown, index: number) => string
  shouldVirtualizeTenantMobileCards: (count: number) => boolean
  tenantGroupVirtualResetKey: (groupKey: string, tenants: any[]) => string
}>()

const emit = defineEmits<{
  (e: 'update:searchText', value: string): void
  (e: 'search-tenants'): void
  (e: 'open-group-manager'): void
  (e: 'show-add-modal'): void
  (e: 'open-batch-move-modal'): void
  (e: 'batch-delete'): void
  (e: 'select-change', keys: string[]): void
  (e: 'show-edit-modal', record: any): void
  (e: 'open-tenant-mgmt', record: any): void
  (e: 'open-domain-mgmt', record: any): void
  (e: 'go-user-management', record: any): void
  (e: 'delete-tenant', id: string): void
  (e: 'toggle-group', key: string): void
  (e: 'add-sub-group', label: string): void
  (e: 'rename-group', label: string, level: string): void
  (e: 'delete-group', label: string, level: string): void
  (e: 'drag-over', event: DragEvent, index: number): void
  (e: 'drop', event: DragEvent, index: number): void
  (e: 'drag-start', event: DragEvent, index: number): void
  (e: 'drag-end'): void
  (e: 'sub-drag-over', event: DragEvent, parent: string, index: number): void
  (e: 'sub-drop', event: DragEvent, parent: string, index: number): void
  (e: 'sub-drag-start', event: DragEvent, parent: string, index: number): void
  (e: 'sub-drag-end'): void
}>()

const {
  normalizedSearchText,
  loading,
  isMobile,
  columns,
  tableData,
  displayGroups,
  selectedRowKeys,
  tenantTableScrollX,
  tenantMobileVirtualMaxHeight,
  tenantMobileSearchResetKey,
  expandedGroups,
  groupTree,
  groupColors,
  dragOverIndex,
  dragOverPos,
  dragFromIndex,
  subDragParent,
  subDragOverIndex,
  subDragOverPos,
  subDragFromIndex,
} = toRefs(props)

const searchTextModel = computed({
  get: () => props.searchText,
  set: (value: string) => emit('update:searchText', value || ''),
})

function onSearchTenants() { emit('search-tenants') }
function openGroupManager() { emit('open-group-manager') }
function showAddModal() { emit('show-add-modal') }
function openBatchMoveModal() { emit('open-batch-move-modal') }
function handleBatchDelete() { emit('batch-delete') }
function onSelectChange(keys: string[]) { emit('select-change', keys) }
function showEditModal(record: any) { emit('show-edit-modal', record) }
function openTenantMgmt(record: any) { emit('open-tenant-mgmt', record) }
function openDomainMgmt(record: any) { emit('open-domain-mgmt', record) }
function goUserManagement(record: any) { emit('go-user-management', record) }
function handleDelete(id: string) { emit('delete-tenant', id) }
function toggleGroup(key: string) { emit('toggle-group', key) }
function handleAddSubGroup(label: string) { emit('add-sub-group', label) }
function openRenameGroup(label: string, level: string) { emit('rename-group', label, level) }
function handleDeleteGroup(label: string, level: string) { emit('delete-group', label, level) }
function onDragOver(event: DragEvent, index: number) { emit('drag-over', event, index) }
function onDrop(event: DragEvent, index: number) { emit('drop', event, index) }
function onDragStart(event: DragEvent, index: number) { emit('drag-start', event, index) }
function onDragEnd() { emit('drag-end') }
function onSubDragOver(event: DragEvent, parent: string, index: number) { emit('sub-drag-over', event, parent, index) }
function onSubDrop(event: DragEvent, parent: string, index: number) { emit('sub-drop', event, parent, index) }
function onSubDragStart(event: DragEvent, parent: string, index: number) { emit('sub-drag-start', event, parent, index) }
function onSubDragEnd() { emit('sub-drag-end') }

function displayTenantName(value: any) { return props.displayTenantName(value) }
function getOciRegionDisplayName(region: any) { return props.getOciRegionDisplayName(region) }
function formatTenantAddedTime(value?: string | null) { return props.formatTenantAddedTime(value) }
function formatPlanBadge(plan: string | null | undefined, fallback?: string) { return props.formatPlanBadge(plan, fallback) }
function planTypeBadgeClass(plan: string | null | undefined) { return props.planTypeBadgeClass(plan) }
function planTypeBadgeStyle(plan: string | null | undefined) { return props.planTypeBadgeStyle(plan) }
function planSummaryTagClass(plan: string) { return props.planSummaryTagClass(plan) }
function groupTotalCount(group: any) { return props.groupTotalCount(group) }
function getPlanCounts(group: any) { return props.getPlanCounts(group) }
function tenantRowKey(item: unknown, index: number) { return props.tenantRowKey(item, index) }
function shouldVirtualizeTenantMobileCards(count: number) { return props.shouldVirtualizeTenantMobileCards(count) }
function tenantGroupVirtualResetKey(groupKey: string, tenants: any[]) { return props.tenantGroupVirtualResetKey(groupKey, tenants) }
</script>

<style scoped>
.mobile-card {
  content-visibility: auto;
  contain-intrinsic-size: 180px;
}

.tenant-name-title {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 22px;
  line-height: 22px;
  white-space: nowrap;
}

.tenant-name-title-text {
  display: inline-block;
}

.tenant-name-mask-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  padding: 0;
  border: 0;
  outline: none;
  box-shadow: none;
  background: transparent !important;
  color: var(--text-sub);
  cursor: pointer;
  border-radius: 50%;
  line-height: 1;
  vertical-align: middle;
  appearance: none;
  opacity: 0.82;
  transition: color 0.16s ease, background-color 0.16s ease, opacity 0.16s ease;
}

.tenant-name-mask-btn:hover,
.tenant-name-mask-btn:focus-visible {
  color: var(--primary);
  background: rgba(148, 163, 184, 0.16) !important;
  opacity: 1;
}

.table-toolbar {
  margin-bottom: 16px;
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  transition: var(--trans);
}

.group-section {
  margin-bottom: 12px;
  transition: transform 0.15s ease, opacity 0.15s ease;
  position: relative;
}

.group-section.dragging {
  opacity: 0.4;
}

.group-section.drag-over-top::before {
  content: '';
  position: absolute;
  top: -6px;
  left: 0;
  right: 0;
  height: 3px;
  background: var(--primary, #1677ff);
  border-radius: 2px;
  box-shadow: 0 0 8px var(--primary, #1677ff);
}

.group-section.drag-over-bottom::after {
  content: '';
  position: absolute;
  bottom: -6px;
  left: 0;
  right: 0;
  height: 3px;
  background: var(--primary, #1677ff);
  border-radius: 2px;
  box-shadow: 0 0 8px var(--primary, #1677ff);
}

.group-card {
  background: var(--bg-card, #fff);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px 16px;
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  transition: all 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
  position: relative;
  overflow: hidden;
}

.group-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 3px;
  background: linear-gradient(90deg, var(--primary, #1677ff), #8b5cf6);
  transform: scaleX(0);
  transition: transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
  transform-origin: left;
}

.group-card:hover::before {
  transform: scaleX(1);
}

.group-card:hover {
  border-color: rgba(129, 140, 248, 0.5);
  box-shadow: 0 8px 24px -4px rgba(99, 102, 241, 0.15);
}

.subgroup-card {
  margin-left: 32px;
  margin-top: 10px;
  background: transparent;
  border-color: rgba(148, 163, 184, 0.18);
  box-shadow: none;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.subgroup-card::before {
  display: none;
}

.subgroup-card:hover {
  border-color: rgba(129, 140, 248, 0.28);
  box-shadow: none;
}

.subgroup-card.dragging {
  opacity: 0.45;
}

.subgroup-card.sub-drag-over-top::before,
.subgroup-card.sub-drag-over-bottom::after {
  content: '';
  position: absolute;
  left: 12px;
  right: 12px;
  height: 3px;
  display: block;
  background: var(--primary, #1677ff);
  border-radius: 2px;
  box-shadow: 0 0 8px var(--primary, #1677ff);
  z-index: 2;
}

.subgroup-card.sub-drag-over-top::before {
  top: 0;
}

.subgroup-card.sub-drag-over-bottom::after {
  bottom: 0;
}

.group-bar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.drag-handle {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text-sub, #999);
  cursor: grab;
  transition: all 0.2s;
  font-size: 14px;
  flex-shrink: 0;
  user-select: none;
}

.drag-handle:active {
  cursor: grabbing;
}

.drag-handle:hover {
  color: var(--primary, #1677ff);
  border-color: var(--primary, #1677ff);
}

.collapse-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
  font-size: 12px;
  flex-shrink: 0;
}

.collapse-btn:hover {
  border-color: var(--primary, #1677ff);
}

.group-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}

.group-card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.group-card-header-main {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1 1 auto;
  min-width: 0;
}

.group-card-header-actions {
  display: flex;
  gap: 6px;
  margin-left: auto;
  flex-shrink: 0;
}

.group-name {
  font-weight: 600;
  font-size: 16px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin: 0;
  flex: 1 1 auto;
  min-width: 0;
}

.subgroup-name {
  font-weight: 600;
  font-size: 15px;
  cursor: pointer;
  flex: 1 1 auto;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.group-stats {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--text-sub, #999);
  margin-left: 8px;
  flex-shrink: 0;
}

.group-tenant-count-badge {
  margin-left: 8px;
  flex-shrink: 0;
}

.group-action-btn {
  padding: 5px 10px;
  background: rgba(255, 255, 255, 0.03);
  color: var(--text-sub);
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  gap: 4px;
}

.group-action-btn:hover {
  border-color: var(--primary, #1677ff);
  color: var(--primary, #1677ff);
}

.plan-tag {
  box-sizing: border-box;
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  line-height: 18px;
  white-space: nowrap;
}

.tag-green {
  background: rgba(82, 196, 26, 0.15);
  color: #52c41a;
}

.tag-free-tier {
  color: rgba(255, 255, 255, 0.92);
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.16);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(10px) saturate(145%);
  -webkit-backdrop-filter: blur(10px) saturate(145%);
}

.tag-gray {
  background: rgba(150, 150, 150, 0.15);
  color: #999;
}

:global([data-theme="light"] .tag-free-tier) {
  color: rgba(15, 23, 42, 0.9);
  background: rgba(15, 23, 42, 0.1);
  border-color: rgba(15, 23, 42, 0.18);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78);
}

.group-body {
  overflow: hidden;
  transition: all 0.4s cubic-bezier(0.34, 1.56, 0.64, 1);
  margin-top: 12px;
}

.subgroup-section {
  margin-bottom: 2px;
}

.tenant-name-cell {
  display: inline-block;
  font-weight: 600;
  white-space: nowrap;
}

.tenant-table-text-cell {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: bottom;
  white-space: nowrap;
}

.tenant-info-tag {
  margin: 0;
}

@media (max-width: 768px) {
  .table-toolbar {
    flex-direction: column;
  }

  .table-toolbar :deep(.ant-space) {
    flex-wrap: wrap;
    width: 100%;
    gap: 8px !important;
  }

  .table-toolbar :deep(.ant-input-search) {
    width: 100% !important;
  }

  .group-card {
    padding: 10px 12px;
    border-radius: 10px;
  }

  .group-name,
  .subgroup-name {
    font-size: 14px;
    max-width: none;
    flex: 1 1 0;
    min-width: 48px;
  }

  .subgroup-card {
    margin-left: 16px;
  }

  .group-bar-right {
    flex-wrap: wrap;
    gap: 4px;
  }

  .drag-handle,
  .collapse-btn {
    width: 32px;
    height: 32px;
  }

  .group-card-header {
    gap: 8px;
  }

  .group-card-header-main {
    flex: 1 1 100%;
    min-width: 0;
  }

  .group-stats {
    margin-left: auto;
    gap: 6px;
  }

  .group-card-header-actions {
    margin-left: 0;
    width: 100%;
    justify-content: flex-end;
  }

  .subgroup-header .group-card-header-actions {
    width: auto;
    margin-left: auto;
  }
}
</style>
