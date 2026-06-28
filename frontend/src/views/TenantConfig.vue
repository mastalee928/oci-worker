<template>
  <div class="tenant-config-root">
    <div class="table-toolbar">
      <a-space wrap>
        <a-input-search v-model:value="searchText" placeholder="搜索租户" allow-clear @search="onSearchTenants" style="width: 200px" />
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
            </div>
          </div>
          </div>
        </div>

        <div v-if="!groupTree.length && !loading" style="text-align: center; padding: 40px; color: var(--text-sub)">
          暂无租户配置
        </div>
      </template>
    </a-spin>

    <div class="tenant-page-float-actions" aria-label="页面快捷操作">
      <a-tooltip
        placement="left"
        :title="allGroupsExpanded ? '收起所有一级分组与子分组' : '展开所有一级分组与子分组'"
      >
        <a-button type="default" shape="circle" class="float-action-btn" @click="toggleAllGroups">
          <template #icon>
            <MenuUnfoldOutlined v-if="allGroupsExpanded" />
            <MenuFoldOutlined v-else />
          </template>
        </a-button>
      </a-tooltip>
      <a-tooltip placement="left" title="返回页面顶部">
        <a-button type="default" shape="circle" class="float-action-btn" @click="scrollTenantPageTop">
          <template #icon><VerticalAlignTopOutlined /></template>
        </a-button>
      </a-tooltip>
    </div>

    <!-- 批量移动到分组 -->
    <a-modal :mask-closable="false" :keyboard="false"
      v-model:open="batchMoveVisible"
      title="批量移动到分组"
      :confirm-loading="batchMoveLoading"
      :width="isMobile ? 'calc(100vw - 32px)' : 480"
      @ok="confirmBatchMove"
    >
      <p style="margin: 0 0 12px; color: var(--text-sub)">已选择 {{ selectedRowKeys.length }} 个租户</p>
      <a-form layout="vertical">
        <a-form-item label="一级分组" required>
          <a-select
            v-model:value="batchMoveG1"
            placeholder="选择一级分组"
            show-search
            :filter-option="filterGroupOption"
            @change="batchMoveG2 = undefined"
          >
            <a-select-option v-for="g in batchMoveLevel1Options" :key="g" :value="g">{{ g }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-if="batchMoveG1 && batchMoveG1 !== '未分组'" label="二级分组（可选）">
          <a-select
            v-model:value="batchMoveG2"
            placeholder="不选则仅归入一级分组"
            allow-clear
            show-search
            :filter-option="filterGroupOption"
          >
            <a-select-option v-for="g in batchMoveLevel2Options" :key="g" :value="g">{{ g }}</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 分组管理器弹窗 -->
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="groupMgrVisible" title="管理分组" :width="isMobile ? '100%' : 700" :footer="null" centered>
      <a-button type="primary" block style="margin-bottom: 20px" @click="openCreateGroupForm">
        <template #icon><PlusOutlined /></template>添加分组
      </a-button>

      <!-- 新增分组表单（内联） -->
      <div v-if="createGroupFormVisible" style="margin-bottom: 16px; padding: 16px; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-card);">
        <a-space direction="vertical" style="width: 100%">
          <a-input v-model:value="createGroupName" placeholder="分组名称" @press-enter="handleCreateGroup" />
          <a-select v-model:value="createGroupLevel" style="width: 100%">
            <a-select-option value="1">一级分组</a-select-option>
            <a-select-option value="2">二级分组（子分组）</a-select-option>
          </a-select>
          <a-select v-if="createGroupLevel === '2'" v-model:value="createGroupParent" placeholder="选择父分组" style="width: 100%">
            <a-select-option v-for="g in groupData.level1" :key="g" :value="g">{{ g }}</a-select-option>
          </a-select>
          <a-space>
            <a-button type="primary" :loading="createGroupLoading" @click="handleCreateGroup">保存</a-button>
            <a-button @click="createGroupFormVisible = false">取消</a-button>
          </a-space>
        </a-space>
      </div>

      <!-- 分组列表 -->
      <div v-if="groupTree.length" style="display: flex; flex-direction: column; gap: 8px;">
        <div v-for="(group, gi) in groupTree" :key="group.key">
          <div style="background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; padding: 14px 16px; display: flex; align-items: center; justify-content: space-between; transition: all 0.2s;">
            <div style="display: flex; align-items: center; gap: 12px;">
              <div class="group-dot" :style="{ background: groupColors[gi % groupColors.length] }"></div>
              <span style="font-weight: 600;">{{ group.label }}</span>
              <span style="font-size: 13px; color: var(--text-sub);">{{ groupTotalCount(group) }} 个租户</span>
              <span v-if="group.children?.length" style="font-size: 12px; color: var(--text-sub);">({{ group.children.length }} 个子分组)</span>
            </div>
            <div style="display: flex; gap: 8px;">
              <a-button size="small" @click="handleMgrAddSub(group.label)">
                <template #icon><PlusOutlined /></template>子分组
              </a-button>
              <a-button size="small" @click="openRenameGroup(group.label, '1')">
                <template #icon><EditOutlined /></template>
              </a-button>
              <a-popconfirm title="删除该分组？租户将移至「未分组」" @confirm="handleMgrDeleteGroup(group.label, '1')">
                <a-button size="small" danger><template #icon><DeleteOutlined /></template></a-button>
              </a-popconfirm>
            </div>
          </div>
          <!-- 子分组 -->
          <div v-for="sub in (group.children || [])" :key="sub.key"
            style="margin-left: 32px; margin-top: 6px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; padding: 12px 16px; display: flex; align-items: center; justify-content: space-between;">
            <div style="display: flex; align-items: center; gap: 12px;">
              <span style="font-weight: 500;">{{ sub.label }}</span>
              <span style="font-size: 13px; color: var(--text-sub);">{{ sub.tenants.length }} 个租户</span>
            </div>
            <div style="display: flex; gap: 8px;">
              <a-button size="small" @click="openRenameGroup(sub.label, '2')">
                <template #icon><EditOutlined /></template>
              </a-button>
              <a-popconfirm title="删除该子分组？" @confirm="handleMgrDeleteGroup(sub.label, '2')">
                <a-button size="small" danger><template #icon><DeleteOutlined /></template></a-button>
              </a-popconfirm>
            </div>
          </div>
        </div>
      </div>
      <div v-else style="text-align: center; padding: 40px; color: var(--text-sub);">暂无分组</div>
    </a-modal>

    <!-- 重命名分组弹窗 -->
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="renameVisible" title="重命名分组" @ok="handleRenameGroup" :confirm-loading="renameLoading" centered>
      <a-input v-model:value="renameNewName" placeholder="输入新分组名" @press-enter="handleRenameGroup" />
    </a-modal>

    <!-- 添加子分组弹窗 -->
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="addSubVisible" title="添加子分组" @ok="handleAddSubGroupConfirm" centered>
      <p style="color: var(--text-sub); margin-bottom: 8px">父分组: <a-tag color="blue">{{ addSubParent }}</a-tag></p>
      <a-input v-model:value="addSubName" placeholder="输入子分组名称" @press-enter="handleAddSubGroupConfirm" />
    </a-modal>

    <!-- 新增/编辑弹窗（内嵌快速导入） -->
    <a-modal :keyboard="false"
      v-model:open="modalVisible"
      :title="editingId ? '编辑配置' : '新增配置'"
      :width="isMobile ? '100%' : 680"
      :body-style="{ maxHeight: '75vh', overflow: 'auto' }"
      @ok="handleSubmit"
      :confirm-loading="submitLoading"
      :mask-closable="false"
    >
      <a-form :model="formState" layout="vertical" class="tenant-form-compact">
        <div v-if="!editingId" class="tenant-quick-import-block">
          <div class="tenant-quick-import-header">⚡ 快速导入 — 粘贴 OCI 配置自动填充</div>
          <div class="tenant-quick-import-body">
            <a-textarea
              v-model:value="importText"
              :rows="6"
              placeholder="粘贴 OCI 配置内容，例如：
[Profile-Name]
user=ocid1.user.oc1...
fingerprint=a5:48:75:06...
tenancy=ocid1.tenancy.oc1...
region=ap-tokyo-1"
              style="font-family: monospace; font-size: 12px"
            />
            <a-button type="primary" size="small" style="margin-top: 8px" @click="parseAndFill">
              <template #icon><ThunderboltOutlined /></template>解析并填充
            </a-button>
          </div>
        </div>

        <a-form-item label="自定义名称" required>
          <a-input v-model:value="formState.username" placeholder="例：我的甲骨文1号" />
        </a-form-item>
        <a-form-item label="Tenant OCID" required>
          <a-input v-model:value="formState.ociTenantId" placeholder="ocid1.tenancy.oc1.." />
        </a-form-item>
        <a-form-item label="User OCID" required>
          <a-input v-model:value="formState.ociUserId" placeholder="ocid1.user.oc1.." />
        </a-form-item>
        <a-form-item label="Fingerprint" required>
          <a-input v-model:value="formState.ociFingerprint" placeholder="xx:xx:xx:..." />
        </a-form-item>
        <a-form-item label="Region" required>
          <a-segmented
            v-model:value="regionInputMode"
            :options="regionInputModeOptions"
            block
            size="small"
            class="region-input-mode"
          />
          <a-select
            v-if="regionInputMode === 'select'"
            v-model:value="formState.ociRegion"
            placeholder="选择区域"
            show-search
            :loading="regionOptionsLoading"
            :filter-option="filterOciRegionSelectOption"
            @change="normalizeRegionInput"
          >
            <a-select-option v-for="opt in ociRegionSelectOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</a-select-option>
          </a-select>
          <a-input
            v-else
            v-model:value="formState.ociRegion"
            placeholder="如 eu-turin-1"
            allow-clear
            @blur="normalizeRegionInput"
            @press-enter="normalizeRegionInput"
          />
        </a-form-item>
        <a-form-item label="私钥 (.pem)" required>
          <a-segmented
            v-model:value="keyInputMode"
            block
            class="pem-input-mode-segmented"
            :options="[
              { label: '上传文件', value: 'upload' },
              { label: '粘贴内容', value: 'paste' },
            ]"
            @change="onKeyInputModeChange"
          />
          <div class="pem-key-input-slot">
            <a-upload-dragger
              v-if="keyInputMode === 'upload'"
              class="pem-upload-dragger"
              :before-upload="handleUpload"
              :max-count="1"
              accept=".pem"
              :file-list="fileList"
              :show-upload-list="false"
              @remove="handleRemoveFile"
            >
              <p class="ant-upload-drag-icon"><InboxOutlined /></p>
              <p class="ant-upload-text">{{ isMobile ? '点击选择 PEM 文件' : '点击或拖拽 PEM 文件到此处' }}</p>
            </a-upload-dragger>
            <a-textarea
              v-else
              v-model:value="pemPasteText"
              :rows="4"
              class="pem-paste-textarea"
              placeholder="粘贴完整 PEM 私钥，须包含：
-----BEGIN PRIVATE KEY-----
...
-----END PRIVATE KEY-----"
            />
          </div>
          <div v-if="keyInputMode === 'upload' && fileList.length" class="pem-upload-filename">
            {{ fileList[0]?.name }}
            <a class="pem-upload-remove" @click.prevent="handleRemoveFile">移除</a>
          </div>
          <span v-if="formState.ociKeyPath && !fileList.length && !pemPasteText.trim()" class="pem-existing-hint">
            已有密钥：{{ formState.ociKeyPath }}（上传或粘贴可覆盖）
          </span>
        </a-form-item>
        <a-form-item label="一级分组">
          <a-select v-model:value="formState.groupLevel1" placeholder="不选则归入「未分组」" allow-clear show-search
            @change="formState.groupLevel2 = ''">
            <a-select-option v-for="g in groupData.level1.filter(n => n !== '未分组')" :key="g" :value="g">{{ g }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-if="formState.groupLevel1" label="二级分组（可选）">
          <a-select v-model:value="formState.groupLevel2" placeholder="不选则直接归入一级分组" allow-clear show-search>
            <a-select-option v-for="g in level2Options" :key="g" :value="g">{{ g }}</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 租户级管理 -->
    <a-modal v-model:open="tenantMgmtVisible" :title="'租户 — ' + (tenantMgmtTenant?.username || '')"
      :width="isMobile ? '100%' : 840" :footer="null" centered :bodyStyle="{ maxHeight: '75vh', overflow: 'auto' }"
      :mask-closable="false" :keyboard="false">
      <a-tabs v-model:activeKey="tenantTab" @change="onTenantTabChange">
        <a-tab-pane key="account" tab="租户信息">
          <div class="tenant-account-pane">
            <a-tooltip title="刷新租户信息">
              <button
                class="tenant-account-refresh"
                :class="{ spinning: tenantInfoLoading }"
                type="button"
                aria-label="刷新租户信息"
                :disabled="tenantInfoLoading"
                @click="handleRefreshTenantAccountInfo"
              >↻</button>
            </a-tooltip>
            <a-spin :spinning="tenantInfoLoading">
              <a-descriptions :column="1" bordered size="small" style="margin-top: 8px">
                <a-descriptions-item label="租户名称">{{ tenantInfoData.tenantName || '—' }}</a-descriptions-item>
                <a-descriptions-item label="homeRegionKey">{{ tenantInfoData.homeRegionKey || '—' }}</a-descriptions-item>
                <a-descriptions-item label="租户 ID">
                  <span style="word-break: break-all; font-size: 12px">{{ tenantInfoData.tenantId || '—' }}</span>
                </a-descriptions-item>
                <a-descriptions-item label="描述">{{ tenantInfoData.description || '—' }}</a-descriptions-item>
                <a-descriptions-item label="已订阅的区域">
                  <template v-if="tenantInfoData.subscribedRegions?.length">
                    <a-tag v-for="r in tenantInfoData.subscribedRegions" :key="r" color="blue" style="margin: 2px">{{ r }}</a-tag>
                  </template>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="订阅套餐">
                  <a-tag v-if="tenantInfoData.planType" :color="planTypeTagColor(tenantInfoData.planType)">
                    {{ tenantInfoData.planTypeLabel || formatPlanType(tenantInfoData.planType) }}
                  </a-tag>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="支付方式">
                  {{ tenantInfoData.paymentMethodLabel || formatPaymentMethod(tenantInfoData.paymentMethod) || '—' }}
                </a-descriptions-item>
                <a-descriptions-item label="账户类型">
                  <a-tag v-if="tenantInfoData.accountType" color="orange">{{ formatAccountType(tenantInfoData.accountType) }}</a-tag>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="升级状态">
                  <a-tag v-if="tenantInfoData.upgradeState" color="purple">
                    {{ tenantInfoData.upgradeStateLabel || formatUpgradeState(tenantInfoData.upgradeState) }}
                  </a-tag>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="订阅状态">
                  <a-tag v-if="tenantInfoData.subscriptionStatus || tenantInfoData.subscriptionStatusLabel"
                    :color="subscriptionStatusTagColor(tenantInfoData.subscriptionStatus)">
                    {{ tenantInfoData.subscriptionStatusLabel || formatSubscriptionStatus(tenantInfoData.subscriptionStatus) }}
                    <span v-if="tenantInfoData.subscriptionStatus && tenantInfoData.subscriptionStatusLabel"
                      style="opacity: 0.75; font-size: 11px"> ({{ tenantInfoData.subscriptionStatus }})</span>
                  </a-tag>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="货币">{{ tenantInfoData.currencyCode || '—' }}</a-descriptions-item>
                <a-descriptions-item label="已完成付款意向">
                  <a-tag v-if="tenantInfoData.isIntentToPay !== undefined && tenantInfoData.isIntentToPay !== null"
                    :color="tenantInfoData.isIntentToPay ? 'green' : 'red'">
                    {{ tenantInfoData.isIntentToPay ? '是' : '否' }}
                  </a-tag>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="开始日期">{{ formatUtcCnDate(tenantInfoData.subscriptionStartTime) }}</a-descriptions-item>
                <a-descriptions-item label="注册地">{{ formatCountryCn(tenantInfoData.registrationLocation) }}</a-descriptions-item>
                <a-descriptions-item label="订阅编号">
                  <span style="word-break: break-all; font-size: 12px">{{ tenantInfoData.subscriptionPlanNumber || '—' }}</span>
                </a-descriptions-item>
                <a-descriptions-item label="组织订阅 OCID">
                  <span style="word-break: break-all; font-size: 12px">{{ tenantInfoData.subscriptionOrgOcid || '—' }}</span>
                </a-descriptions-item>
              </a-descriptions>
            </a-spin>
          </div>
        </a-tab-pane>
        <a-tab-pane key="compartments" tab="区间">
          <CompartmentManager
            v-if="tenantTab === 'compartments' && tenantMgmtTenant?.id"
            :tenant-id="tenantMgmtTenant.id"
          />
        </a-tab-pane>
        <a-tab-pane key="iam" tab="IAM策略">
          <a-alert type="info" show-icon style="margin-bottom: 10px"
            message="对应 OCI 控制台「身份与安全性 → 身份 → 策略」（经典 IAM Policy API），与身份域内的安全策略无关。只读列表。" />
          <a-space style="margin-bottom: 12px" wrap>
            <a-button type="primary" @click="loadIamPolicies" :loading="iamPoliciesLoading">
              <template #icon><ReloadOutlined /></template>加载策略
            </a-button>
            <a-input-search v-model:value="iamPolicySearch" placeholder="搜索名称/描述" allow-clear style="width: 220px" />
          </a-space>
          <a-table v-if="!isMobile" :data-source="filteredIamPolicies" :loading="iamPoliciesLoading" size="small"
            :pagination="{ pageSize: 15 }" row-key="id"
            v-model:expanded-row-keys="iamExpandedRowKeys"
            @expand="onIamExpand">
            <template #expandedRowRender="{ record }">
              <a-spin :spinning="iamPolicyDetailLoading === record.id">
                <div v-if="(iamPolicyStatements[record.id] || []).length" class="iam-statements">
                  <div v-for="(st, si) in iamPolicyStatements[record.id]" :key="si" class="iam-statement-line">{{ si + 1 }}. {{ st }}</div>
                </div>
                <a-empty v-else description="展开后加载策略语句" />
              </a-spin>
            </template>
            <a-table-column title="名称" data-index="name" key="name" :width="160" :ellipsis="true" />
            <a-table-column title="描述" data-index="description" key="description" :ellipsis="true" />
            <a-table-column title="语句数" data-index="statementCount" key="statementCount" :width="72" />
            <a-table-column title="状态" data-index="lifecycleState" key="lifecycleState" :width="88" />
            <a-table-column title="Compartment" data-index="compartmentId" key="compartmentId" :width="120" :ellipsis="true">
              <template #default="{ text }">
                <span style="font-size: 11px">{{ shortOcId(text) }}</span>
              </template>
            </a-table-column>
            <a-table-column title="创建时间" data-index="timeCreated" key="timeCreated" :width="168">
              <template #default="{ text }">{{ formatUtcCnDate(text) }}</template>
            </a-table-column>
          </a-table>
          <a-spin v-else :spinning="iamPoliciesLoading">
            <a-empty v-if="!iamPoliciesLoading && filteredIamPolicies.length === 0" description="请点击「加载策略」" />
            <div v-for="p in filteredIamPolicies" :key="p.id" class="mobile-card">
              <div class="mobile-card-header">
                <span class="mobile-card-title">{{ p.name }}</span>
                <a-tag style="margin:0">{{ p.statementCount ?? 0 }} 条</a-tag>
              </div>
              <div class="mobile-card-body">
                <div class="mobile-card-row"><span class="label">描述</span><span class="value">{{ p.description || '—' }}</span></div>
                <div class="mobile-card-row"><span class="label">状态</span><span class="value">{{ p.lifecycleState || '—' }}</span></div>
              </div>
            </div>
          </a-spin>
        </a-tab-pane>
        <a-tab-pane key="quotas" tab="账户配额">
          <div class="quota-toolbar">
            <div class="quota-region-field">
              <span class="quota-region-label">区域</span>
              <select
                v-if="isMobile"
                v-model="quotaRegion"
                class="quota-region-native-select"
                :disabled="quotasLoading || regionsLoading || !quotaRegionOptions.length"
                @change="onQuotaRegionChange"
              >
                <option v-for="opt in quotaRegionOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
              <a-select
                v-else
                v-model:value="quotaRegion"
                class="quota-region-select"
                :options="quotaRegionOptions"
                :loading="regionsLoading"
                :disabled="quotasLoading || !quotaRegionOptions.length"
                :show-search="false"
                @change="onQuotaRegionChange"
              >
                <template #option="{ label, isHomeRegion }">
                  <span class="quota-region-option">
                    <span class="quota-region-option-code">{{ label }}</span>
                    <span v-if="isHomeRegion" class="quota-region-home-mark">主区域</span>
                  </span>
                </template>
              </a-select>
            </div>
            <div class="quota-service-field">
              <span class="quota-region-label">服务</span>
              <select
                v-if="isMobile"
                v-model="quotaService"
                class="quota-region-native-select"
                :disabled="quotasLoading || !quotasList.length"
              >
                <option v-for="opt in quotaServiceOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
              <a-select
                v-else
                v-model:value="quotaService"
                class="quota-service-select"
                :options="quotaServiceOptions"
                :disabled="quotasLoading || !quotasList.length"
                :show-search="false"
              />
            </div>
            <a-input-search v-model:value="quotaSearch" placeholder="搜索服务/配额名" allow-clear class="quota-search" />
            <a-button type="primary" @click="loadQuotas(true)" :loading="quotasLoading">
              <template #icon><ReloadOutlined /></template>查询配额
            </a-button>
          </div>
          <a-table v-if="!isMobile" :data-source="filteredQuotas" :loading="quotasLoading" size="small"
            :pagination="{ pageSize: 20 }" :row-key="(r: any) => `${r.region || ''}:${r.serviceName}:${r.limitName}:${r.availabilityDomain || ''}`">
            <a-table-column title="服务" data-index="serviceName" key="serviceName" :width="140">
              <template #default="{ text }">
                <a-tag>{{ text }}</a-tag>
              </template>
            </a-table-column>
            <a-table-column title="配额名称" data-index="limitName" key="limitName" :ellipsis="true" />
            <a-table-column title="AD" data-index="availabilityDomain" key="ad" :width="120" :ellipsis="true">
              <template #default="{ text }">
                <span style="font-size: 12px">{{ text || '全局' }}</span>
              </template>
            </a-table-column>
            <a-table-column title="上限" data-index="limit" key="limit" :width="80" />
            <a-table-column title="已用" data-index="used" key="used" :width="80">
              <template #default="{ text }">
                <span>{{ text ?? '—' }}</span>
              </template>
            </a-table-column>
            <a-table-column title="可用" data-index="available" key="available" :width="80">
              <template #default="{ text }">
                <a-tag v-if="text !== null && text !== undefined" :color="text === 0 ? 'red' : 'green'">{{ text }}</a-tag>
                <span v-else>—</span>
              </template>
            </a-table-column>
          </a-table>
          <a-spin v-else :spinning="quotasLoading">
            <a-empty v-if="!quotasLoading && filteredQuotas.length === 0" description="无配额数据" />
            <div v-for="(q, qi) in filteredQuotas" :key="qi" class="mobile-card">
              <div class="mobile-card-header">
                <a-tag style="margin:0">{{ q.serviceName }}</a-tag>
                <a-tag v-if="q.available !== null && q.available !== undefined" :color="q.available === 0 ? 'red' : 'green'" style="margin:0">可用: {{ q.available }}</a-tag>
              </div>
              <div class="mobile-card-body">
                <div class="mobile-card-row"><span class="label">配额</span><span class="value">{{ q.limitName }}</span></div>
                <div class="mobile-card-row"><span class="label">AD</span><span class="value">{{ q.availabilityDomain || '全局' }}</span></div>
                <div class="mobile-card-row"><span class="label">上限</span><span class="value">{{ q.limit }}</span></div>
                <div class="mobile-card-row"><span class="label">已用</span><span class="value">{{ q.used ?? '—' }}</span></div>
              </div>
            </div>
          </a-spin>
        </a-tab-pane>
        <a-tab-pane key="billing" tab="账务信息">
        <a-spin :spinning="billingLoading">
          <a-space v-if="!billingData && !billingLoading" style="margin-bottom: 12px">
            <a-button type="primary" @click="loadTenantBilling">加载账务信息</a-button>
          </a-space>
          <template v-if="billingData">
            <a-row :gutter="12">
              <a-col :xs="24" :sm="12">
                <a-card size="small" :bordered="true">
                  <div style="font-size: 12px; color: var(--text-sub)">最近发票</div>
                  <div style="font-weight: 700; font-size: 16px; margin-top: 4px">
                    <span v-if="billingData.summary?.latestInvoice?.totalAmount !== undefined && billingData.summary?.latestInvoice?.totalAmount !== null">
                      {{ billingData.summary.latestInvoice.totalAmount }} {{ billingData.summary.latestInvoice.currencyCode || '' }}
                    </span>
                    <span v-else>—</span>
                  </div>
                  <div style="margin-top: 6px; font-size: 12px; color: var(--text-sub)">
                    <span v-if="billingData.summary?.latestInvoice?.invoiceNo">No: {{ billingData.summary.latestInvoice.invoiceNo }}</span>
                    <span v-else>暂无发票数据</span>
                  </div>
                </a-card>
              </a-col>
              <a-col :xs="24" :sm="12">
                <a-card size="small" :bordered="true">
                  <div style="font-size: 12px; color: var(--text-sub)">期间成本（Usage API）</div>
                  <div style="font-weight: 700; font-size: 16px; margin-top: 4px">
                    <template v-if="billingData.usage?.available && billingData.usage?.summary">
                      {{ billingData.usage.summary.totalCost ?? '—' }} {{ billingData.usage.summary.currency || '' }}
                    </template>
                    <span v-else>—</span>
                  </div>
                  <div style="margin-top: 6px; font-size: 12px; color: var(--text-sub)">
                    <span v-if="billingData.usage?.available">近 {{ billingData.usage.periodDays || billingCostDays }} 天</span>
                    <span v-else>成本数据未加载</span>
                  </div>
                </a-card>
              </a-col>
            </a-row>

            <div style="margin-top: 12px">
              <a-space wrap style="margin-bottom: 8px">
                <a-button size="small" :loading="billingLoading" @click="loadTenantBilling">
                  <template #icon><ReloadOutlined /></template>刷新账务
                </a-button>
                <span style="font-weight: 600">成本分析</span>
                <a-select v-model:value="billingCostDays" style="width: 110px" :options="billingCostDayOptions" />
                <a-button size="small" type="primary" :loading="billingLoading" @click="reloadBillingCost">查询成本</a-button>
                <a v-if="billingData.links?.costAnalysis" :href="billingData.links.costAnalysis" target="_blank" rel="noopener noreferrer" style="font-size: 12px">控制台</a>
              </a-space>
              <a-alert v-if="billingData.usage && !billingData.usage.available" type="warning" show-icon
                :message="billingData.usage.reason || '成本分析不可用'" style="margin-bottom: 8px" />
              <template v-else-if="billingData.usage?.available">
                <div style="font-size: 12px; color: var(--text-sub); margin-bottom: 8px">
                  {{ formatBillingPeriod(billingData.usage.timeUsageStarted, billingData.usage.timeUsageEnded) }}
                </div>
                <div style="font-weight: 600; margin-bottom: 6px">按服务</div>
                <a-table
                  v-if="!isMobile"
                  size="small"
                  :data-source="billingData.usage.byService || []"
                  :pagination="{ pageSize: 10 }"
                  row-key="service"
                >
                  <a-table-column title="服务" data-index="service" key="service" :ellipsis="true" />
                  <a-table-column title="成本" key="cost" :width="140">
                    <template #default="{ record }">
                      {{ record.cost ?? '—' }} {{ record.currency || '' }}
                    </template>
                  </a-table-column>
                </a-table>
                <a-spin v-else :spinning="false">
                  <a-empty v-if="!(billingData.usage.byService || []).length" description="无服务分项" />
                  <div v-for="(row, i) in (billingData.usage.byService || [])" :key="row.service || i" class="mobile-card">
                    <div class="mobile-card-body">
                      <div class="mobile-card-row"><span class="label">服务</span><span class="value">{{ row.service }}</span></div>
                      <div class="mobile-card-row"><span class="label">成本</span><span class="value">{{ row.cost }} {{ row.currency || '' }}</span></div>
                    </div>
                  </div>
                </a-spin>
                <div style="font-weight: 600; margin: 12px 0 6px">按日趋势</div>
                <a-table
                  v-if="!isMobile"
                  size="small"
                  :data-source="billingData.usage.byDay || []"
                  :pagination="{ pageSize: 10 }"
                  row-key="date"
                >
                  <a-table-column title="日期" data-index="date" key="date" :width="120" />
                  <a-table-column title="成本" key="cost" :width="140">
                    <template #default="{ record }">
                      {{ record.cost ?? '—' }} {{ record.currency || '' }}
                    </template>
                  </a-table-column>
                </a-table>
                <a-spin v-else :spinning="false">
                  <a-empty v-if="!(billingData.usage.byDay || []).length" description="无按日数据" />
                  <div v-for="(row, i) in (billingData.usage.byDay || [])" :key="row.date || i" class="mobile-card">
                    <div class="mobile-card-body">
                      <div class="mobile-card-row"><span class="label">日期</span><span class="value">{{ row.date }}</span></div>
                      <div class="mobile-card-row"><span class="label">成本</span><span class="value">{{ row.cost }} {{ row.currency || '' }}</span></div>
                    </div>
                  </div>
                </a-spin>
              </template>
            </div>

            <a-alert v-if="billingData.invoices && billingData.invoices.available === false"
              type="warning" show-icon style="margin-top: 10px"
              :message="billingData.invoices.reason || '发票接口不可用'" />

            <div style="margin-top: 10px">
              <div style="font-weight: 600; margin-bottom: 6px">最近发票</div>
              <a-table
                v-if="!isMobile"
                size="small"
                :data-source="billingData.invoices?.items || []"
                :pagination="false"
                row-key="invoiceId"
              >
                <a-table-column title="发票号" data-index="invoiceNo" key="invoiceNo" :width="140" />
                <a-table-column title="状态" data-index="status" key="status" :width="120" />
                <a-table-column title="开票日期" data-index="invoiceDate" key="invoiceDate" :width="180" />
                <a-table-column title="到期日" data-index="dueDate" key="dueDate" :width="180" />
                <a-table-column title="金额" key="amount" :width="140">
                  <template #default="{ record }">
                    <span>{{ record.totalAmount ?? '—' }} {{ record.currencyCode || '' }}</span>
                  </template>
                </a-table-column>
                <a-table-column title="操作" key="action" :width="120">
                  <template #default="{ record }">
                    <a-button type="link" size="small" @click="handleDownloadInvoice(record)" :disabled="!record.invoiceId">下载PDF</a-button>
                  </template>
                </a-table-column>
              </a-table>
              <a-spin v-else :spinning="false">
                <a-empty v-if="(billingData.invoices?.items || []).length === 0" description="暂无发票" />
                <div v-for="(inv, ii) in (billingData.invoices?.items || [])" :key="inv.invoiceId || ii" class="mobile-card">
                  <div class="mobile-card-header">
                    <span class="mobile-card-title">{{ inv.invoiceNo || '—' }}</span>
                    <a-tag style="margin:0">{{ inv.status || '—' }}</a-tag>
                  </div>
                  <div class="mobile-card-body">
                    <div class="mobile-card-row"><span class="label">金额</span><span class="value">{{ inv.totalAmount ?? '—' }} {{ inv.currencyCode || '' }}</span></div>
                    <div class="mobile-card-row"><span class="label">开票</span><span class="value">{{ inv.invoiceDate || '—' }}</span></div>
                    <div class="mobile-card-row"><span class="label">到期</span><span class="value">{{ inv.dueDate || '—' }}</span></div>
                  </div>
                  <div class="mobile-card-actions">
                    <a-button type="link" size="small" @click="handleDownloadInvoice(inv)" :disabled="!inv.invoiceId">下载PDF</a-button>
                  </div>
                </div>
              </a-spin>
            </div>
          </template>
          <a-empty v-else :description="billingLoading ? '正在加载账务数据' : '暂无账务数据'" />
        </a-spin>
        </a-tab-pane>
        <a-tab-pane key="budgets" tab="成本预算">
          <a-spin :spinning="budgetsLoading">
            <div class="budget-toolbar">
              <a-space wrap>
                <a-button type="primary" size="small" @click="openCreateBudget">
                  <template #icon><PlusOutlined /></template>新建预算
                </a-button>
                <a-button size="small" :loading="budgetsLoading" @click="loadBudgets">
                  <template #icon><ReloadOutlined /></template>刷新
                </a-button>
                <a v-if="budgetsData?.links?.budgets" :href="budgetsData.links.budgets" target="_blank" rel="noopener noreferrer" style="font-size: 12px">控制台</a>
              </a-space>
            </div>

            <template v-if="budgetsList.length">
              <a-table
                v-if="!isMobile"
                class="budget-table"
                size="small"
                :data-source="budgetsList"
                :pagination="{ pageSize: 8 }"
                row-key="id"
                :row-class-name="budgetRowClassName"
                @row="budgetTableRow"
              >
                <a-table-column title="名称" data-index="displayName" key="displayName" :ellipsis="true" />
                <a-table-column title="目标" key="target" :ellipsis="true">
                  <template #default="{ record }">
                    <a-tooltip :title="formatBudgetTargetTooltip(record)">
                      <div class="budget-target-cell">{{ formatBudgetTarget(record) }}</div>
                    </a-tooltip>
                  </template>
                </a-table-column>
                <a-table-column title="预算" key="amount" :width="110">
                  <template #default="{ record }">{{ formatBudgetAmount(record) }}</template>
                </a-table-column>
                <a-table-column title="已用" key="actual" :width="170">
                  <template #default="{ record }">
                    <a-progress
                      :percent="budgetProgressPercent(record)"
                      :status="budgetProgressStatus(record)"
                      size="small"
                    />
                    <div class="budget-spend-line">{{ formatBudgetSpend(record.actualSpend, record.amount) }}</div>
                  </template>
                </a-table-column>
                <a-table-column title="周期" key="period" :width="100">
                  <template #default="{ record }">{{ formatBudgetProcessingPeriod(record.processingPeriodType) }}</template>
                </a-table-column>
                <a-table-column title="状态" key="state" :width="90">
                  <template #default="{ record }">
                    <a-tag :color="record.lifecycleState === 'ACTIVE' ? 'green' : 'default'">{{ record.lifecycleState || '—' }}</a-tag>
                  </template>
                </a-table-column>
                <a-table-column title="操作" key="action" :width="150">
                  <template #default="{ record }">
                    <a-space size="small">
                      <a-button type="link" size="small" @click.stop="openEditBudget(record)">编辑</a-button>
                      <a-popconfirm title="确定删除该成本预算？" @confirm="handleDeleteBudget(record)">
                        <a-button type="link" danger size="small" @click.stop>删除</a-button>
                      </a-popconfirm>
                    </a-space>
                  </template>
                </a-table-column>
              </a-table>

              <div v-else>
                <div v-for="b in budgetsList" :key="b.id" class="mobile-card budget-mobile-card" :class="{ 'budget-mobile-card-active': b.id === selectedBudgetId }" @click="selectBudget(b)">
                  <div class="mobile-card-header">
                    <span class="mobile-card-title">{{ b.displayName || '—' }}</span>
                    <a-tag style="margin:0" :color="b.lifecycleState === 'ACTIVE' ? 'green' : 'default'">{{ b.lifecycleState || '—' }}</a-tag>
                  </div>
                  <div class="mobile-card-body">
                    <div class="mobile-card-row"><span class="label">预算</span><span class="value">{{ formatBudgetAmount(b) }}</span></div>
                    <div class="mobile-card-row"><span class="label">已用</span><span class="value">{{ formatBudgetSpend(b.actualSpend, b.amount) }}</span></div>
                    <div class="mobile-card-row"><span class="label">预测</span><span class="value">{{ formatBudgetSpend(b.forecastedSpend, b.amount) }}</span></div>
                    <div class="mobile-card-row"><span class="label">周期</span><span class="value">{{ formatBudgetProcessingPeriod(b.processingPeriodType) }}</span></div>
                    <div class="mobile-card-row"><span class="label">目标</span><span class="value">{{ formatBudgetTarget(b) }}</span></div>
                    <a-progress :percent="budgetProgressPercent(b)" :status="budgetProgressStatus(b)" size="small" />
                  </div>
                  <div class="mobile-card-actions">
                    <a-button type="link" size="small" @click.stop="openEditBudget(b)">编辑</a-button>
                    <a-popconfirm title="确定删除该成本预算？" @confirm="handleDeleteBudget(b)">
                      <a-button type="link" danger size="small" @click.stop>删除</a-button>
                    </a-popconfirm>
                  </div>
                </div>
              </div>

              <div class="budget-alert-section" v-if="selectedBudget">
                <div class="budget-alert-header">
                  <div>
                    <div class="budget-alert-title">预算告警规则</div>
                    <div class="budget-alert-subtitle">{{ selectedBudget.displayName || '—' }} · {{ selectedBudget.alertRules?.length || 0 }} 条规则</div>
                  </div>
                  <a-space size="small" wrap>
                    <a-button size="small" :loading="budgetAlertRulesLoading" @click="reloadSelectedBudgetAlertRules">
                      <template #icon><ReloadOutlined /></template>刷新告警
                    </a-button>
                    <a-button size="small" type="primary" @click="openCreateBudgetAlertRule(selectedBudget)">
                      <template #icon><PlusOutlined /></template>新建告警
                    </a-button>
                  </a-space>
                </div>

                <a-table
                  v-if="!isMobile"
                  size="small"
                  :data-source="selectedBudgetAlertRules"
                  :pagination="false"
                  row-key="id"
                >
                  <a-table-column title="名称" data-index="displayName" key="displayName" :ellipsis="true" />
                  <a-table-column title="类型" key="type" :width="90">
                    <template #default="{ record }">{{ formatBudgetAlertType(record.type) }}</template>
                  </a-table-column>
                  <a-table-column title="阈值" key="threshold" :width="120">
                    <template #default="{ record }">{{ formatBudgetAlertThreshold(record) }}</template>
                  </a-table-column>
                  <a-table-column title="电子邮件收件人" data-index="recipients" key="recipients" :ellipsis="true" />
                  <a-table-column title="状态" key="state" :width="90">
                    <template #default="{ record }">
                      <a-tag :color="record.lifecycleState === 'ACTIVE' ? 'green' : 'default'">{{ record.lifecycleState || '—' }}</a-tag>
                    </template>
                  </a-table-column>
                  <a-table-column title="操作" key="action" :width="140">
                    <template #default="{ record }">
                      <a-space size="small">
                        <a-button type="link" size="small" @click="openEditBudgetAlertRule(record)">编辑</a-button>
                        <a-popconfirm title="确定删除该告警规则？" @confirm="handleDeleteBudgetAlertRule(record)">
                          <a-button type="link" danger size="small">删除</a-button>
                        </a-popconfirm>
                      </a-space>
                    </template>
                  </a-table-column>
                </a-table>

                <div v-else>
                  <a-empty v-if="selectedBudgetAlertRules.length === 0" description="暂无告警规则" />
                  <div v-for="r in selectedBudgetAlertRules" :key="r.id" class="mobile-card">
                    <div class="mobile-card-header">
                      <span class="mobile-card-title">{{ r.displayName || '—' }}</span>
                      <a-tag style="margin:0" :color="r.lifecycleState === 'ACTIVE' ? 'green' : 'default'">{{ r.lifecycleState || '—' }}</a-tag>
                    </div>
                    <div class="mobile-card-body">
                      <div class="mobile-card-row"><span class="label">类型</span><span class="value">{{ formatBudgetAlertType(r.type) }}</span></div>
                      <div class="mobile-card-row"><span class="label">阈值</span><span class="value">{{ formatBudgetAlertThreshold(r) }}</span></div>
                      <div class="mobile-card-row"><span class="label">电子邮件收件人</span><span class="value">{{ r.recipients || '—' }}</span></div>
                      <div class="mobile-card-row"><span class="label">电子邮件</span><span class="value">{{ r.message || '—' }}</span></div>
                    </div>
                    <div class="mobile-card-actions">
                      <a-button type="link" size="small" @click="openEditBudgetAlertRule(r)">编辑</a-button>
                      <a-popconfirm title="确定删除该告警规则？" @confirm="handleDeleteBudgetAlertRule(r)">
                        <a-button type="link" danger size="small">删除</a-button>
                      </a-popconfirm>
                    </div>
                  </div>
                </div>
              </div>
            </template>

            <a-empty v-else :description="budgetsLoading ? '正在加载成本预算' : '暂无成本预算'" />
          </a-spin>
        </a-tab-pane>
        <a-tab-pane key="regions" tab="区域管理">
          <a-spin :spinning="regionsLoading">
            <div class="region-toolbar">
              <a-space wrap>
                <a-button type="primary" size="small" :loading="regionsLoading" @click="loadRegions(false, true)">
                  <template #icon><ReloadOutlined /></template>刷新
                </a-button>
                <a-input-search
                  v-model:value="regionSearch"
                  placeholder="搜索区域/标识符"
                  allow-clear
                  class="region-search"
                />
                <a v-if="regionsData?.links?.regions" :href="regionsData.links.regions" target="_blank" rel="noopener noreferrer" style="font-size: 12px">控制台</a>
              </a-space>
            </div>

            <a-table
              v-if="!isMobile"
              class="region-table"
              :data-source="filteredRegions"
              :loading="regionsLoading"
              size="small"
              :pagination="{ pageSize: 10 }"
              row-key="regionKey"
            >
              <a-table-column title="区域" key="region" :ellipsis="true">
                <template #default="{ record }">
                  <div class="region-name-cell">
                    <span class="region-name-main">{{ formatRegionDisplay(record) }}</span>
                    <a-tag v-if="record.isHomeRegion" color="blue" style="margin:0">主区域</a-tag>
                  </div>
                  <div class="region-key-line">{{ record.regionKey || '—' }}</div>
                </template>
              </a-table-column>
              <a-table-column title="区域标识符" data-index="regionName" key="regionName" :width="190" :ellipsis="true" />
              <a-table-column title="订阅状态" key="status" :width="120">
                <template #default="{ record }">
                  <a-tag :color="regionStatusColor(record.status)">{{ formatRegionStatus(record.status) }}</a-tag>
                </template>
              </a-table-column>
              <a-table-column title="操作" key="action" :width="100">
                <template #default="{ record }">
                  <a-button
                    v-if="record.canSubscribe"
                    type="primary"
                    size="small"
                    :loading="regionSubscribeSendingKey === record.regionKey"
                    @click="confirmSubscribeRegion(record)"
                  >
                    订阅
                  </a-button>
                  <span v-else class="region-action-empty">—</span>
                </template>
              </a-table-column>
            </a-table>

            <div v-else>
              <a-empty v-if="!regionsLoading && filteredRegions.length === 0" description="暂无区域数据" />
              <div v-for="r in filteredRegions" :key="r.regionKey || r.regionName" class="mobile-card region-mobile-card">
                <div class="mobile-card-header">
                  <span class="mobile-card-title">{{ formatRegionDisplay(r) }}</span>
                  <a-tag :color="regionStatusColor(r.status)" style="margin:0">{{ formatRegionStatus(r.status) }}</a-tag>
                </div>
                <div class="mobile-card-body">
                  <div class="mobile-card-row"><span class="label">标识符</span><span class="value">{{ r.regionName || '—' }}</span></div>
                  <div class="mobile-card-row"><span class="label">区域 Key</span><span class="value">{{ r.regionKey || '—' }}</span></div>
                  <div class="mobile-card-row"><span class="label">主区域</span><span class="value">{{ r.isHomeRegion ? '是' : '否' }}</span></div>
                </div>
                <div v-if="r.canSubscribe" class="mobile-card-actions">
                  <a-button
                    type="primary"
                    size="small"
                    :loading="regionSubscribeSendingKey === r.regionKey"
                    @click="confirmSubscribeRegion(r)"
                  >
                    订阅
                  </a-button>
                </div>
              </div>
            </div>
          </a-spin>
        </a-tab-pane>
        <a-tab-pane key="announcements" tab="云公告">
          <a-space style="margin-bottom: 12px" wrap>
            <a-button type="primary" @click="loadAnnouncements" :loading="announcementsLoading">
              <template #icon><ReloadOutlined /></template>加载公告
            </a-button>
            <a-input-search v-model:value="announcementSearch" placeholder="搜索摘要/工单号/类型" allow-clear style="width: 240px" />
          </a-space>
          <div v-if="announcementsRetentionNote" style="font-size: 12px; color: var(--text-sub); margin-bottom: 8px">
            {{ announcementsRetentionNote }}
          </div>
          <a-table v-if="!isMobile" :data-source="filteredAnnouncements" :loading="announcementsLoading" size="small"
            :pagination="{ pageSize: 15 }" row-key="id"
            :custom-row="announcementCustomRow">
            <a-table-column title="摘要" data-index="summary" key="summary" :ellipsis="true" />
            <a-table-column title="类型" data-index="announcementType" key="announcementType" :width="120" />
            <a-table-column title="发布时间" data-index="timeCreated" key="timeCreated" :width="168">
              <template #default="{ text }">{{ formatUtcCnDate(text) }}</template>
            </a-table-column>
            <a-table-column title="阅读状态" data-index="userStatus" key="userStatus" :width="88">
              <template #default="{ text }">
                <a-tag :color="announcementStatusColor(text)">{{ formatAnnouncementUserStatus(text) }}</a-tag>
              </template>
            </a-table-column>
            <a-table-column title="工单号" data-index="referenceTicketNumber" key="referenceTicketNumber" :width="120" :ellipsis="true" />
            <a-table-column title="操作" key="action" :width="150">
              <template #default="{ record }">
                <a-space size="small">
                  <a-button type="link" size="small" @click.stop="openAnnouncementDetail(record)">详情</a-button>
                  <a-popconfirm
                    v-if="isAnnouncementUnread(record)"
                    title="确认标记为已读？"
                    ok-text="确定"
                    cancel-text="取消"
                    @confirm="markAnnouncementAsRead(record)"
                  >
                    <a-button
                      type="link"
                      size="small"
                      :loading="announcementReadUpdatingId === record.id"
                      @click.stop
                    >
                      标记已读
                    </a-button>
                  </a-popconfirm>
                </a-space>
              </template>
            </a-table-column>
          </a-table>
          <a-spin v-else :spinning="announcementsLoading">
            <a-empty v-if="!announcementsLoading && filteredAnnouncements.length === 0" description="请点击「加载公告」" />
            <div v-for="a in filteredAnnouncements" :key="a.id" class="mobile-card" @click="openAnnouncementDetail(a)">
              <div class="mobile-card-header">
                <span class="mobile-card-title">{{ a.summary || '—' }}</span>
                <a-tag :color="announcementStatusColor(a.userStatus)" style="margin:0">{{ formatAnnouncementUserStatus(a.userStatus) }}</a-tag>
              </div>
              <div class="mobile-card-body">
                <div class="mobile-card-row"><span class="label">类型</span><span class="value">{{ a.announcementType || '—' }}</span></div>
                <div class="mobile-card-row"><span class="label">时间</span><span class="value">{{ formatUtcCnDate(a.timeCreated) }}</span></div>
              </div>
              <div v-if="isAnnouncementUnread(a)" class="mobile-card-actions" @click.stop>
                <a-popconfirm
                  title="确认标记为已读？"
                  ok-text="确定"
                  cancel-text="取消"
                  @confirm="markAnnouncementAsRead(a)"
                >
                  <a-button
                    type="link"
                    size="small"
                    :loading="announcementReadUpdatingId === a.id"
                    @click.stop
                  >
                    标记已读
                  </a-button>
                </a-popconfirm>
              </div>
            </div>
          </a-spin>
        </a-tab-pane>
      </a-tabs>
    </a-modal>

    <a-modal
      :mask-closable="false"
      :keyboard="false"
      v-model:open="mfaVerifyVisible"
      title="安全验证 — MFA 多因素认证"
      :width="isMobile ? '100%' : 420"
      :confirm-loading="mfaVerifyLoading"
      :ok-text="mfaTargetEnabled ? '确认启用' : '确认关闭'"
      @ok="submitMfaChange"
      @cancel="cancelMfaVerify"
    >
      <a-alert
        type="warning"
        show-icon
        message="验证码已发送至 Telegram"
        description="MFA 是身份域登录安全策略，改动会影响该域内用户登录控制台。"
        style="margin-bottom: 12px"
      />
      <a-input
        v-model:value="mfaVerifyCode"
        placeholder="请输入 6 位验证码"
        size="large"
        :maxlength="6"
        inputmode="numeric"
        allow-clear
        @pressEnter="submitMfaChange"
      />
      <div class="region-verify-actions">
        <span>验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="mfaVerifyCodeSending" @click="resendMfaVerifyCode">重新发送</a-button>
      </div>
    </a-modal>

    <a-modal
      :mask-closable="false"
      :keyboard="false"
      v-model:open="regionSubscribeVerifyVisible"
      title="安全验证 — 订阅区域"
      :width="isMobile ? '100%' : 420"
      :confirm-loading="regionSubscribeLoading"
      ok-text="确认订阅"
      @ok="submitRegionSubscribe"
    >
      <a-alert type="info" show-icon message="验证码已发送至 Telegram" style="margin-bottom: 12px" />
      <div class="region-verify-target">
        <div class="region-verify-name">{{ regionSubscribeTargetDisplay }}</div>
        <div class="region-verify-meta">{{ regionSubscribeTarget?.regionName || '—' }} · {{ regionSubscribeTarget?.regionKey || '—' }}</div>
      </div>
      <a-input
        v-model:value="regionSubscribeCode"
        placeholder="请输入 6 位验证码"
        size="large"
        :maxlength="6"
        inputmode="numeric"
        allow-clear
        @pressEnter="submitRegionSubscribe"
      />
      <div class="region-verify-actions">
        <span>验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="regionSubscribeCodeSending" @click="resendRegionSubscribeCode">重新发送</a-button>
      </div>
    </a-modal>

    <a-modal
      :mask-closable="false"
      :keyboard="false"
      v-model:open="budgetFormVisible"
      :title="budgetFormMode === 'create' ? '新建成本预算' : '编辑成本预算'"
      :width="isMobile ? '100%' : 620"
      :confirm-loading="budgetFormLoading"
      @ok="submitBudgetForm"
    >
      <a-form layout="vertical" class="budget-form">
        <a-form-item label="名称" required>
          <a-input v-model:value="budgetForm.displayName" allow-clear />
        </a-form-item>
        <a-form-item label="金额" required>
          <a-input-number v-model:value="budgetForm.amount" :min="1" style="width: 100%" />
        </a-form-item>
        <a-form-item label="描述">
          <a-textarea v-model:value="budgetForm.description" :rows="2" allow-clear />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :xs="24" :sm="12">
            <a-form-item label="处理周期">
              <a-select v-model:value="budgetForm.processingPeriodType" :options="budgetProcessingPeriodOptions" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="12">
            <a-form-item label="重置周期">
              <a-select v-model:value="budgetForm.resetPeriod" :options="budgetResetPeriodOptions" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="每月中的第几天开始处理预算">
          <a-input-number v-model:value="budgetForm.budgetProcessingPeriodStartOffset" :min="1" :max="31" style="width: 100%" allow-clear />
        </a-form-item>
        <a-row v-if="budgetForm.processingPeriodType === 'SINGLE_USE'" :gutter="12">
          <a-col :xs="24" :sm="12">
            <a-form-item label="开始日期">
              <a-input v-model:value="budgetForm.startDate" placeholder="2026-06-01" allow-clear />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="12">
            <a-form-item label="结束日期">
              <a-input v-model:value="budgetForm.endDate" placeholder="2026-06-30" allow-clear />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="预算所在区间" required>
          <a-select
            v-model:value="budgetForm.compartmentId"
            :options="budgetCompartmentOptions"
            :loading="budgetCompartmentsLoading"
            :disabled="budgetFormMode === 'edit'"
            show-search
            option-filter-prop="label"
            :filter-option="filterBudgetCompartmentOption"
          />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :xs="24" :sm="8">
            <a-form-item label="目标类型" required>
              <a-select
                v-model:value="budgetForm.targetType"
                :options="budgetTargetTypeOptions"
                :disabled="budgetFormMode === 'edit'"
                @change="onBudgetTargetTypeChange"
              />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="16">
            <a-form-item :label="budgetForm.targetType === 'TAG' ? '目标标签' : '目标区间'" required>
              <a-select
                v-if="budgetForm.targetType === 'COMPARTMENT'"
                v-model:value="budgetForm.target"
                :options="budgetTargetCompartmentOptions"
                :loading="budgetCompartmentsLoading"
                :disabled="budgetFormMode === 'edit'"
                show-search
                option-filter-prop="label"
                :filter-option="filterBudgetCompartmentOption"
              />
              <a-input v-else v-model:value="budgetForm.target" :disabled="budgetFormMode === 'edit'" allow-clear />
            </a-form-item>
          </a-col>
        </a-row>
      </a-form>
    </a-modal>

    <a-modal
      :mask-closable="false"
      :keyboard="false"
      v-model:open="budgetAlertFormVisible"
      :title="budgetAlertFormMode === 'create' ? '新建预算告警' : '编辑预算告警'"
      :width="isMobile ? '100%' : 560"
      :confirm-loading="budgetAlertFormLoading"
      @ok="submitBudgetAlertForm"
    >
      <a-form layout="vertical" class="budget-form">
        <a-form-item label="名称" required>
          <a-input v-model:value="budgetAlertForm.displayName" allow-clear />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :xs="24" :sm="12">
            <a-form-item label="告警类型">
              <a-select v-model:value="budgetAlertForm.type" :options="budgetAlertTypeOptions" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="12">
            <a-form-item label="阈值类型">
              <a-select v-model:value="budgetAlertForm.thresholdType" :options="budgetThresholdTypeOptions" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="阈值" required>
          <a-input-number v-model:value="budgetAlertForm.threshold" :min="1" style="width: 100%" />
        </a-form-item>
        <a-form-item label="电子邮件收件人" required>
          <a-input v-model:value="budgetAlertForm.recipients" placeholder="name@example.com, team@example.com" allow-clear />
        </a-form-item>
        <a-form-item label="电子邮件">
          <a-textarea v-model:value="budgetAlertForm.message" :rows="2" allow-clear />
        </a-form-item>
        <a-form-item label="描述（可选）">
          <a-textarea v-model:value="budgetAlertForm.description" :rows="2" allow-clear />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-drawer :mask-closable="false" :keyboard="false"
      v-model:open="announcementDrawerVisible"
      :title="announcementDrawerTitle"
      :width="isMobile ? '100%' : 720"
      placement="right"
      :destroy-on-close="true"
    >
      <a-spin :spinning="announcementDetailLoading">
        <a-tabs v-model:activeKey="announcementDetailTab">
          <a-tab-pane key="detail" tab="详情">
            <template v-if="announcementDetail">
              <a-descriptions :column="1" bordered size="small">
                <a-descriptions-item label="摘要">{{ announcementDetail.summary || '—' }}</a-descriptions-item>
                <a-descriptions-item label="公告 ID">
                  <span style="word-break: break-all; font-size: 12px">{{ announcementDetail.id || '—' }}</span>
                </a-descriptions-item>
                <a-descriptions-item label="工单号">{{ announcementDetail.referenceTicketNumber || '—' }}</a-descriptions-item>
                <a-descriptions-item label="类型">{{ announcementDetail.announcementType || '—' }}</a-descriptions-item>
                <a-descriptions-item label="平台">{{ announcementDetail.platformType || '—' }}</a-descriptions-item>
                <a-descriptions-item label="状态">{{ announcementDetail.lifecycleState || '—' }}</a-descriptions-item>
                <a-descriptions-item label="阅读状态">
                  <a-space size="small">
                    <a-tag :color="announcementStatusColor(announcementDetail.userStatus)">
                      {{ formatAnnouncementUserStatus(announcementDetail.userStatus) }}
                    </a-tag>
                    <a-popconfirm
                      v-if="isAnnouncementUnread(announcementDetail)"
                      title="确认标记为已读？"
                      ok-text="确定"
                      cancel-text="取消"
                      @confirm="markAnnouncementAsRead(announcementDetail)"
                    >
                      <a-button
                        type="link"
                        size="small"
                        :loading="announcementReadUpdatingId === announcementDetail.id"
                      >
                        标记已读
                      </a-button>
                    </a-popconfirm>
                  </a-space>
                </a-descriptions-item>
                <a-descriptions-item label="涉及服务">
                  <template v-if="announcementDetail.services?.length">
                    <a-tag v-for="s in announcementDetail.services" :key="s" style="margin: 2px">{{ s }}</a-tag>
                  </template>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="受影响区域">
                  <template v-if="announcementDetail.affectedRegions?.length">
                    <a-tag v-for="r in announcementDetail.affectedRegions" :key="r" color="blue" style="margin: 2px">{{ r }}</a-tag>
                  </template>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="环境">{{ announcementDetail.environmentName || '—' }}</a-descriptions-item>
                <a-descriptions-item label="创建时间">{{ formatUtcCnDate(announcementDetail.timeCreated) }}</a-descriptions-item>
                <a-descriptions-item label="更新时间">{{ formatUtcCnDate(announcementDetail.timeUpdated) }}</a-descriptions-item>
                <a-descriptions-item v-if="announcementDetail.chainId" label="链 ID">
                  <span style="word-break: break-all; font-size: 12px">{{ announcementDetail.chainId }}</span>
                </a-descriptions-item>
              </a-descriptions>
              <div v-if="announcementDetail.description" class="announcement-block">
                <div class="announcement-block-title">描述</div>
                <div
                  class="announcement-description"
                  v-html="formatAnnouncementBody(announcementDetail.description)"
                />
              </div>
              <div v-if="announcementDetail.additionalInformation" class="announcement-block">
                <div class="announcement-block-title">附加信息</div>
                <div
                  class="announcement-description"
                  v-html="formatAnnouncementBody(announcementDetail.additionalInformation)"
                />
              </div>
            </template>
            <a-empty v-else description="暂无详情" />
          </a-tab-pane>
          <a-tab-pane key="impacted" tab="受影响资源">
            <a-table
              v-if="announcementImpacted.length"
              size="small"
              :data-source="announcementImpacted"
              :pagination="false"
              row-key="resourceId"
            >
              <a-table-column title="资源名称" data-index="resourceName" key="resourceName" :ellipsis="true" />
              <a-table-column title="资源 ID" data-index="resourceId" key="resourceId" :ellipsis="true">
                <template #default="{ text }">
                  <span style="font-size: 11px; word-break: break-all">{{ text || '—' }}</span>
                </template>
              </a-table-column>
              <a-table-column title="区域" data-index="region" key="region" :width="140" />
            </a-table>
            <a-empty v-else description="无受影响资源" />
          </a-tab-pane>
          <a-tab-pane key="history" tab="公告历史">
            <a-alert v-if="!announcementDetail?.chainId" type="info" show-icon message="该公告无 chainId，无关联历史条目。" />
            <a-table
              v-else-if="announcementHistory.length"
              size="small"
              :data-source="announcementHistory"
              :pagination="{ pageSize: 10 }"
              row-key="id"
            >
              <a-table-column title="摘要" data-index="summary" key="summary" :ellipsis="true" />
              <a-table-column title="类型" data-index="announcementType" key="announcementType" :width="110" />
              <a-table-column title="时间" data-index="timeCreated" key="timeCreated" :width="168">
                <template #default="{ text }">{{ formatUtcCnDate(text) }}</template>
              </a-table-column>
              <a-table-column title="操作" key="action" :width="72">
                <template #default="{ record }">
                  <a-button type="link" size="small" @click="openAnnouncementDetail(record)">查看</a-button>
                </template>
              </a-table-column>
            </a-table>
            <a-empty v-else-if="announcementDetail?.chainId" description="同链无其它历史公告" />
          </a-tab-pane>
        </a-tabs>
      </a-spin>
    </a-drawer>

    <!-- 域管理弹窗 -->
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="domainMgmtVisible" :title="'域管理 — ' + (domainMgmtTenant?.username || '')"
      :width="isMobile ? '100%' : 840" :footer="null" centered :bodyStyle="{ maxHeight: '75vh', overflow: 'auto' }">
      <!-- 域选择器（Tab 之外，全局） -->
      <div class="domain-switcher" v-if="domainList.length > 0 || domainSettingsLoading">
        <span class="domain-switcher-label">当前域：</span>
        <a-select
          v-if="domainList.length > 0"
          :value="selectedDomainId"
          style="min-width: 280px"
          :disabled="domainList.length <= 1"
          @change="(v: any) => handleDomainChange(String(v))"
        >
          <a-select-option v-for="d in domainList" :key="d.domainId" :value="d.domainId" :label="d.displayName">
            <span class="domain-option">
              <span class="domain-option-name">{{ d.displayName || '—' }}</span>
              <span v-if="d.type" class="domain-type-pill">{{ domainTypeCn(d.type) }}</span>
            </span>
          </a-select-option>
        </a-select>
        <a-spin v-if="domainSettingsLoading && domainList.length === 0" size="small" />
        <a-tag v-if="selectedDomain?.error || selectedDomain?.mfaError || selectedDomain?.passwordPolicyError" color="error" style="margin-left: 6px">异常</a-tag>
        <a-tooltip v-if="domainList.length === 1" title="当前租户仅一个域，无需切换">
          <i class="ri-information-line" style="color: var(--text-sub); font-size: 14px; margin-left: 6px"></i>
        </a-tooltip>
      </div>
      <a-alert v-if="!domainSettingsLoading && domainList.length === 0" type="warning"
        message="未读取到 Identity Domain 信息" show-icon style="margin-bottom: 12px" />

      <a-tabs v-model:activeKey="domainTab">
        <a-tab-pane key="security" tab="安全策略">
          <a-spin :spinning="domainSettingsLoading">
            <template v-if="selectedDomain">
              <a-alert v-if="selectedDomain.mfaError" type="warning" show-icon :message="selectedDomain.mfaError" style="margin-bottom: 8px" />
              <a-descriptions :column="1" bordered size="small">
                <a-descriptions-item label="MFA 多因素认证（Security Policy for OCI Console）">
                  <a-space align="center" wrap>
                    <a-switch :checked="!!selectedDomain.mfaEnabled"
                      :loading="mfaUpdatingId === selectedDomain.domainId"
                      :disabled="selectedDomain.mfaEnabled === null || selectedDomain.mfaEnabled === undefined"
                      checked-children="已启用" un-checked-children="已关闭"
                      @change="(v: any) => handleMfaChange(selectedDomain, v as boolean)" />
                    <span style="font-size: 12px; color: var(--text-sub)">
                      对应 OCI：身份域 → 安全 → 登录策略 → Security Policy for OCI Console 的「激活」状态
                    </span>
                    <span v-if="selectedDomain.consolePolicyName" style="font-size: 12px; color: var(--text-sub)">
                      · 策略：{{ selectedDomain.consolePolicyName }}
                    </span>
                  </a-space>
                </a-descriptions-item>
                <a-descriptions-item label="密码过期天数（defaultPasswordPolicy）">
                  <a-space wrap>
                    <a-input-number :value="selectedDomain.passwordExpiresAfterDays ?? 0"
                      @update:value="(v: any) => (selectedDomain.passwordExpiresAfterDays = v as number)"
                      :min="0" :max="999" style="width: 120px" />
                    <span style="color: var(--text-sub); font-size: 12px">
                      在 N 天后失效；0 = 永不过期
                    </span>
                    <a-button type="primary" size="small"
                      :loading="pwdExpiryUpdatingId === selectedDomain.domainId"
                      @click="handlePwdExpiryChange(selectedDomain)">保存</a-button>
                    <span v-if="selectedDomain.passwordPolicyName" style="font-size: 12px; color: var(--text-sub)">
                      策略：{{ selectedDomain.passwordPolicyName }}（priority={{ selectedDomain.passwordPolicyPriority ?? '-' }}）
                    </span>
                  </a-space>
                  <div v-if="selectedDomain.passwordPolicyError" style="color: #faad14; font-size: 12px; margin-top: 4px">
                    {{ selectedDomain.passwordPolicyError }}
                  </div>
                </a-descriptions-item>
              </a-descriptions>
            </template>
          </a-spin>
        </a-tab-pane>
        <a-tab-pane key="factors" tab="验证因素">
          <!-- TG 验证门 -->
          <div v-if="!authFactorToken" class="factor-lock">
            <i class="ri-shield-keyhole-line factor-lock-icon"></i>
            <div class="factor-lock-title">修改验证因素需要 Telegram 二次验证</div>
            <div class="factor-lock-desc">
              该设置对应「身份域 → 安全 → 验证因素」。改动将影响域内所有用户的 MFA 登录方式，请确认后再操作。
            </div>
            <a-space style="margin-top: 14px" wrap>
              <a-button @click="sendFactorCode" :loading="factorCodeSending">
                <template #icon><i class="ri-send-plane-line"></i></template>
                获取验证码
              </a-button>
              <a-input v-model:value="factorCodeInput" placeholder="6 位验证码" :maxlength="6" style="width: 140px" />
              <a-button type="primary" :loading="factorUnlocking" @click="doUnlockFactors">解锁</a-button>
            </a-space>
          </div>
          <div v-else>
            <a-alert type="success" show-icon style="margin-bottom: 12px"
              message="已通过 TG 验证，10 分钟内可在本 Tab 自由保存；切换域不需要重新验证。" />
            <a-spin :spinning="authFactorLoading">
              <template v-if="selectedFactorDomain">
                <a-alert v-if="selectedFactorDomain.error" type="warning" show-icon :message="selectedFactorDomain.error" style="margin-bottom: 10px" />
                <template v-else>
                  <div class="factor-section-title">因素</div>
                  <div class="factor-grid">
                    <a-checkbox v-for="f in FACTOR_OPTIONS" :key="f.key"
                      :checked="!!selectedFactorDomain.factors?.[f.key]"
                      @change="(e: any) => (selectedFactorDomain.factors[f.key] = e.target.checked)">
                      {{ f.label }}
                    </a-checkbox>
                  </div>

                  <div class="factor-section-title">参数</div>
                  <a-space wrap>
                    <span class="factor-label">最大注册设备数</span>
                    <a-input-number :value="selectedFactorDomain.limits?.maxEnrolledDevices"
                      @update:value="(v: any) => (selectedFactorDomain.limits.maxEnrolledDevices = v)"
                      :min="1" :max="20" style="width: 110px" />
                    <span class="factor-hint">maxEnrolledDevices</span>
                  </a-space>

                  <div class="factor-section-title">可信设备</div>
                  <a-space wrap>
                    <a-switch :checked="!!selectedFactorDomain.trustedDevice?.enabled"
                      @change="(v: any) => (selectedFactorDomain.trustedDevice.enabled = v)"
                      checked-children="启用" un-checked-children="禁用" />
                    <span class="factor-label">最大可信设备数</span>
                    <a-input-number :value="selectedFactorDomain.trustedDevice?.maxTrustedEndpoints"
                      @update:value="(v: any) => (selectedFactorDomain.trustedDevice.maxTrustedEndpoints = v)"
                      :min="1" :max="50" style="width: 110px" />
                    <span class="factor-label">信任天数</span>
                    <a-input-number :value="selectedFactorDomain.trustedDevice?.maxEndpointTrustDurationInDays"
                      @update:value="(v: any) => (selectedFactorDomain.trustedDevice.maxEndpointTrustDurationInDays = v)"
                      :min="1" :max="365" style="width: 110px" />
                  </a-space>

                  <div class="factor-section-title">登录规则</div>
                  <a-space wrap>
                    <span class="factor-label">最大 MFA 失败次数</span>
                    <a-input-number :value="selectedFactorDomain.limits?.maxIncorrectAttempts"
                      @update:value="(v: any) => (selectedFactorDomain.limits.maxIncorrectAttempts = v)"
                      :min="1" :max="50" style="width: 110px" />
                    <span class="factor-hint">endpointRestrictions.maxIncorrectAttempts</span>
                  </a-space>

                  <div style="margin-top: 12px; display: flex; justify-content: flex-end; gap: 8px">
                    <a-button size="small" @click="reloadFactors">重置</a-button>
                    <a-button size="small" type="primary" :loading="factorSavingId === selectedFactorDomain.domainId"
                      @click="saveFactors(selectedFactorDomain)">保存</a-button>
                  </div>
                </template>
              </template>
            </a-spin>
          </div>
        </a-tab-pane>
        <a-tab-pane key="notifications" tab="通知">
          <div v-if="!notificationToken" class="factor-lock notification-lock">
            <i class="ri-shield-keyhole-line factor-lock-icon"></i>
            <div class="factor-lock-title">修改域通知需要 Telegram 二次验证</div>
            <div class="factor-lock-desc">
              该设置对应「身份域 → 设置 → 通知」。解锁后可查看和保存域通知配置，切换域不需要重新验证。
            </div>
            <a-space class="notification-lock-actions" wrap>
              <a-button @click="sendNotificationCode" :loading="notificationCodeSending">
                <template #icon><i class="ri-send-plane-line"></i></template>
                获取验证码
              </a-button>
              <a-input v-model:value="notificationCodeInput" placeholder="6 位验证码" :maxlength="6" style="width: 140px" />
              <a-button type="primary" :loading="notificationUnlocking" @click="doUnlockNotifications">解锁</a-button>
            </a-space>
          </div>
          <div v-else>
            <a-alert type="success" show-icon style="margin-bottom: 12px"
              message="已通过 TG 验证，10 分钟内可编辑域通知；切换域不需要重新验证。" />
            <div class="notification-toolbar">
              <div class="notification-toolbar-title">当前域通知配置</div>
              <a-button size="small" :loading="notificationLoading" :disabled="!selectedDomainId" @click="loadDomainNotifications">
                <template #icon><ReloadOutlined /></template>刷新当前域
              </a-button>
            </div>
            <a-spin :spinning="notificationLoading">
            <template v-if="notificationData">
              <div class="domain-notification-layout">
                <section class="notification-panel">
                  <div class="notification-panel-header">
                    <div class="notification-panel-title">一般通知</div>
                    <div class="notification-inline-control">
                      <span>为所有的身份域用户启用通知</span>
                      <a-switch
                        :checked="!!notificationData.notificationEnabled"
                        checked-children="已启用"
                        un-checked-children="已关闭"
                        @change="(v: any) => (notificationData.notificationEnabled = v)"
                      />
                    </div>
                  </div>
                  <a-form layout="vertical" size="small" class="notification-form-grid">
                    <a-form-item label="发件人电子邮件地址">
                      <a-input v-model:value="notificationData.fromEmailAddress.value" allow-clear />
                    </a-form-item>
                    <a-form-item label="发件人显示名">
                      <a-input v-model:value="notificationData.fromEmailAddress.displayName" allow-clear />
                    </a-form-item>
                    <a-form-item label="发件邮箱验证方式">
                      <a-select v-model:value="notificationData.fromEmailAddress.validate" :options="notificationValidateOptions" />
                    </a-form-item>
                    <a-form-item label="发件邮箱验证状态">
                      <a-tag :color="notificationValidationStatusColor(notificationData.fromEmailAddress.validationStatus)">
                        {{ formatNotificationValidationStatus(notificationData.fromEmailAddress.validationStatus) }}
                      </a-tag>
                    </a-form-item>
                  </a-form>
                </section>

                <section class="notification-panel">
                  <div class="notification-panel-header">
                    <div>
                      <div class="notification-panel-title">收件人</div>
                      <div class="notification-panel-subtitle">通过电子邮件通知发送给选定收件人来测试这些通知。</div>
                    </div>
                    <div class="notification-inline-control">
                      <span>限定的收件人列表</span>
                      <a-switch
                        :checked="!!notificationData.testModeEnabled"
                        checked-children="是"
                        un-checked-children="否"
                        @change="(v: any) => (notificationData.testModeEnabled = v)"
                      />
                    </div>
                  </div>
                  <a-form layout="vertical" size="small">
                    <a-form-item label="测试收件人电子邮件地址">
                      <a-textarea
                        v-model:value="notificationRecipientsText"
                        :auto-size="{ minRows: 2, maxRows: 5 }"
                        allow-clear
                      />
                    </a-form-item>
                  </a-form>
                </section>

                <a-collapse
                  v-model:activeKey="notificationEventActiveKeys"
                  class="notification-collapse"
                  :bordered="false"
                >
                  <a-collapse-panel key="admin" class="notification-collapse-panel">
                    <template #header>
                      <div class="notification-collapse-title">
                        <span>管理员通知</span>
                        <a-tag style="margin:0">{{ notificationAdminEvents.length }} 项</a-tag>
                      </div>
                    </template>
                    <a-empty v-if="notificationAdminEvents.length === 0" description="暂无管理员通知" />
                    <div v-else class="notification-event-list">
                      <div v-for="event in notificationAdminEvents" :key="event.eventId" class="notification-event-row">
                        <div class="notification-event-copy">
                          <div class="notification-event-name">{{ formatNotificationEventName(event.eventId) }}</div>
                          <div class="notification-event-id">{{ event.eventId }}</div>
                        </div>
                        <a-switch
                          :checked="!!event.enabled"
                          checked-children="是"
                          un-checked-children="否"
                          @change="(v: any) => (event.enabled = v)"
                        />
                      </div>
                    </div>
                  </a-collapse-panel>

                  <a-collapse-panel key="endUser" class="notification-collapse-panel">
                    <template #header>
                      <div class="notification-collapse-title">
                        <span>最终用户通知</span>
                        <a-tag style="margin:0">{{ notificationEndUserEvents.length }} 项</a-tag>
                      </div>
                    </template>
                    <a-empty v-if="notificationEndUserEvents.length === 0" description="暂无最终用户通知" />
                    <div v-else class="notification-event-list">
                      <div v-for="event in notificationEndUserEvents" :key="event.eventId" class="notification-event-row">
                        <div class="notification-event-copy">
                          <div class="notification-event-name">{{ formatNotificationEventName(event.eventId) }}</div>
                          <div class="notification-event-id">{{ event.eventId }}</div>
                        </div>
                        <a-switch
                          :checked="!!event.enabled"
                          checked-children="是"
                          un-checked-children="否"
                          @change="(v: any) => (event.enabled = v)"
                        />
                      </div>
                    </div>
                  </a-collapse-panel>
                </a-collapse>
              </div>

              <div class="notification-actions">
                <a-button size="small" :loading="notificationLoading" @click="loadDomainNotifications">
                  <template #icon><ReloadOutlined /></template>刷新
                </a-button>
                <a-button
                  size="small"
                  type="primary"
                  :loading="notificationSaving"
                  @click="saveDomainNotifications"
                >
                  保存
                </a-button>
              </div>
            </template>
            <a-empty v-else :description="notificationLoading ? '正在加载通知设置' : '请选择域后加载通知设置'">
              <template #extra>
                <a-button type="primary" size="small" :disabled="!selectedDomainId" @click="loadDomainNotifications">
                  加载通知设置
                </a-button>
              </template>
            </a-empty>
            </a-spin>
          </div>
        </a-tab-pane>
        <a-tab-pane key="logs" tab="登录日志">
          <a-space style="margin-bottom: 12px" wrap>
            <a-button type="primary" @click="loadAuditLogs" :loading="auditLogsLoading" :disabled="!selectedDomainId">
              <template #icon><ReloadOutlined /></template>加载最近{{ auditDays }}天登录日志
            </a-button>
            <a-select v-model:value="auditDays" style="width: 120px" @change="onAuditDaysChange">
              <a-select-option :value="1">最近 1 天</a-select-option>
              <a-select-option :value="3">最近 3 天</a-select-option>
              <a-select-option :value="7">最近 7 天</a-select-option>
              <a-select-option :value="14">最近 14 天</a-select-option>
              <a-select-option :value="30">最近 30 天</a-select-option>
            </a-select>
          </a-space>
          <a-spin :spinning="auditLogsLoading">
            <a-empty v-if="!auditLogsLoading && !auditLogsLoaded" description="请点击「加载」按钮拉取当前域的登录日志" />
            <a-empty v-else-if="!auditLogsLoading && !selectedAuditDomain" description="未读取到当前域的登录日志结果，请重新加载" />
            <div v-else-if="selectedAuditDomain">
              <AuditLogTable :rows="selectedAuditDomain.logs || []"
                :error="selectedAuditDomain.error || selectedAuditDomain.notice" :is-mobile="isMobile" />
            </div>
          </a-spin>
        </a-tab-pane>
        <a-tab-pane key="audit" tab="审计日志">
          <a-space style="margin-bottom: 12px" wrap>
            <a-button type="primary" @click="loadDomainAuditLogs" :loading="domainAuditLogsLoading" :disabled="!selectedDomainId">
              <template #icon><ReloadOutlined /></template>加载最近{{ domainAuditDays }}天审计日志
            </a-button>
            <a-select v-model:value="domainAuditDays" style="width: 120px" @change="onDomainAuditDaysChange">
              <a-select-option :value="1">最近 1 天</a-select-option>
              <a-select-option :value="3">最近 3 天</a-select-option>
              <a-select-option :value="7">最近 7 天</a-select-option>
              <a-select-option :value="14">最近 14 天</a-select-option>
              <a-select-option :value="30">最近 30 天</a-select-option>
            </a-select>
          </a-space>
          <a-spin :spinning="domainAuditLogsLoading">
            <a-empty v-if="!domainAuditLogsLoading && !domainAuditLogsLoaded" description="请点击「加载」按钮拉取当前域的审计日志" />
            <a-empty v-else-if="!domainAuditLogsLoading && !selectedDomainAudit" description="未读取到当前域的审计日志结果，请重新加载" />
            <div v-else-if="selectedDomainAudit">
              <AuditLogTable :rows="selectedDomainAudit.logs || []"
                :error="selectedDomainAudit.error || selectedDomainAudit.notice" :is-mobile="isMobile"
                :event-labels="NOTIFICATION_EVENT_LABELS" />
            </div>
          </a-spin>
        </a-tab-pane>
        
      </a-tabs>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'TenantConfig' })

import { ref, reactive, computed, h, onMounted, onActivated, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { PlusOutlined, ThunderboltOutlined, InboxOutlined, ReloadOutlined, MenuFoldOutlined, MenuUnfoldOutlined, VerticalAlignTopOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import type { UploadFile } from 'ant-design-vue'
import { getTenantList, addTenant, updateTenant, removeTenant, batchMoveTenantGroup, uploadKey, getTenantFullInfo, getTenantBillingSummary, downloadInvoicePdf, listBudgets, createBudget, updateBudget, deleteBudget, listBudgetAlertRules, createBudgetAlertRule, updateBudgetAlertRule, deleteBudgetAlertRule, listTenantRegions, subscribeTenantRegion, getDomainSettings, updateMfa, updatePasswordExpiry, unlockDomainNotifications, getDomainNotifications, updateDomainNotifications, getAuditLogs, getServiceQuotas, listIamPolicies, getIamPolicy, listAnnouncements, getAnnouncementDetail, markAnnouncementRead, getTenantGroups, createGroup, renameGroup, deleteGroup, saveGroupOrder, unlockAuthFactors, getAuthFactors, updateAuthFactors } from '../api/tenant'
import type { BudgetAlertType, BudgetProcessingPeriodType, BudgetTargetType, BudgetThresholdType } from '../api/tenant'
import { listCompartmentPicker } from '../api/compartment'
import { sendVerifyCode } from '../api/system'
import { RightOutlined, DownOutlined, SettingOutlined, FolderOutlined, EditOutlined, DeleteOutlined, EyeOutlined, EyeInvisibleOutlined } from '@ant-design/icons-vue'
import AuditLogTable from '../components/AuditLogTable.vue'
import CompartmentManager from '../components/CompartmentManager.vue'
import {
  loadOciRegionCatalog,
  ociRegionSelectOptions,
  getOciRegionDisplayName,
  filterOciRegionSelectOption,
} from '../utils/ociRegionCatalog'
import dayjs from 'dayjs'
import { collectGroupExpandKeys, isAllGroupsExpanded } from '../composables/groupExpandToggle'
import { useTenantCatalogStore } from '../stores/tenantCatalog'
import { useThemeStore } from '../stores/theme'
import { appQueryCache } from '../utils/queryCache'
import utc from 'dayjs/plugin/utc'

dayjs.extend(utc)

const router = useRouter()
const catalog = useTenantCatalogStore()
const themeStore = useThemeStore()
const searchLoading = ref(false)
const TENANT_SEARCH_STALE_MS = 15_000
const TENANT_INFO_STALE_MS = 30_000
const TENANT_REGION_STALE_MS = 5 * 60_000
const TENANT_QUOTA_STALE_MS = 2 * 60_000

function formatUtcCnDate(v: any): string {
  if (!v) return '—'
  const d = dayjs.utc(v)
  if (!d.isValid()) return '—'
  return `${d.year()}年${d.month() + 1}月${d.date()}日（UTC）`
}

/** 租户配置列表「添加日期」列 */
function formatTenantAddedTime(iso?: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso)
  return d.toLocaleString('zh-CN', { hour12: false })
}

function formatBillingPeriod(start: string | null | undefined, end: string | null | undefined): string {
  if (!start && !end) return '—'
  const s = start ? formatUtcCnDate(start) : '—'
  const e = end ? formatUtcCnDate(end) : '—'
  return `${s} ～ ${e}`
}

function normalizePlanType(plan: string | null | undefined) {
  if (!plan) return ''
  return String(plan).trim().toUpperCase().replace(/[\s-]+/g, '_')
}

function isPaygPlan(plan: string | null | undefined) {
  const p = normalizePlanType(plan)
  return p === 'PAYG'
}

function isFreeTierPlan(plan: string | null | undefined) {
  const p = normalizePlanType(plan).replace(/_/g, '')
  return p === 'FREE' || p === 'FREETIER'
}

function formatPlanType(v: string | null | undefined): string {
  if (!v) return '—'
  if (isFreeTierPlan(v)) return '免费套餐 (Free Tier)'
  if (isPaygPlan(v)) return '按量付费 (PAYG)'
  const map: Record<string, string> = {
    PAYG: '按量付费 (PAYG)',
  }
  const normalized = normalizePlanType(v)
  return map[normalized] || normalized || v
}

function planTypeTagColor(plan: string | null | undefined) {
  if (isFreeTierPlan(plan)) return 'default'
  return 'green'
}

function formatPlanBadge(plan: string | null | undefined, fallback = '获取中...') {
  const normalized = normalizePlanType(plan)
  if (!normalized) return fallback
  if (isPaygPlan(normalized)) return 'PAYG'
  if (isFreeTierPlan(normalized)) return normalized === 'FREE' ? 'FREE' : 'FREE_TIER'
  return normalized
}

function planTypeBadgeClass(plan: string | null | undefined) {
  if (!normalizePlanType(plan)) return ['plan-tag', 'tag-gray']
  if (isPaygPlan(plan)) return ['plan-tag', 'tag-green']
  if (isFreeTierPlan(plan)) return ['plan-tag', 'tag-free-tier']
  return ['plan-tag', 'tag-gray']
}

function planTypeBadgeStyle(plan: string | null | undefined) {
  if (!isFreeTierPlan(plan)) return undefined
  if (themeStore.isDark) {
    return {
      color: 'rgba(255, 255, 255, 0.92)',
      background: 'rgba(255, 255, 255, 0.12)',
      borderColor: 'rgba(255, 255, 255, 0.16)',
      boxShadow: 'inset 0 1px 0 rgba(255, 255, 255, 0.08)',
      backdropFilter: 'blur(10px) saturate(145%)',
      WebkitBackdropFilter: 'blur(10px) saturate(145%)',
    }
  }
  return {
    color: 'rgba(15, 23, 42, 0.92)',
    background: 'rgba(15, 23, 42, 0.12)',
    borderColor: 'rgba(15, 23, 42, 0.2)',
    boxShadow: 'inset 0 1px 0 rgba(255, 255, 255, 0.78)',
    backdropFilter: 'blur(10px) saturate(145%)',
    WebkitBackdropFilter: 'blur(10px) saturate(145%)',
  }
}

function planSummaryTagClass(plan: string) {
  if (isPaygPlan(plan)) return 'tag-green'
  if (isFreeTierPlan(plan)) return 'tag-free-tier'
  return 'tag-gray'
}

function formatPaymentMethod(v: string | null | undefined): string {
  if (!v) return '—'
  const map: Record<string, string> = {
    FREE_TRIAL: '免费试用 (FREE_TRIAL)',
    CREDIT_CARD: '信用卡',
    PAYPAL: 'PayPal',
  }
  return map[v] || v
}

function isOciSubscriptionId(id: string | null | undefined): boolean {
  if (!id || typeof id !== 'string') return false
  return id.trim().toLowerCase().startsWith('ocid1.')
}

function formatRewardsAmount(amount: number | null | undefined, currency?: string | null): string {
  if (amount == null) return '—'
  const cur = currency ? ` ${currency}` : ''
  return `${amount}${cur}`
}

function formatAccountType(v: string | null | undefined): string {
  if (!v) return '—'
  const map: Record<string, string> = {
    PERSONAL: '个人',
    CORPORATE: '企业',
    CORPORATE_SUBMITTED: '企业（已提交）',
  }
  return map[v] || v
}

function formatSubscriptionStatus(v: string | null | undefined): string {
  if (!v) return '—'
  const map: Record<string, string> = {
    ACTIVE: '有效',
    EXPIRED: '已过期',
    INACTIVE: '未激活',
    PENDING: '处理中',
    ERROR: '异常',
  }
  return map[v] || v
}

function subscriptionStatusTagColor(status: string | null | undefined) {
  const s = (status || '').toUpperCase()
  if (s === 'EXPIRED' || s === 'ERROR') return 'error'
  if (s === 'PENDING' || s === 'INACTIVE') return 'warning'
  if (s === 'ACTIVE') return 'success'
  return 'blue'
}

function formatUpgradeState(v: string | null | undefined): string {
  if (!v) return '—'
  const map: Record<string, string> = {
    PROMO: '促销/试用',
    SUBMITTED: '已提交',
    ERROR: '错误',
    UPGRADED: '已升级',
    UPGRADE_PENDING: '升级待处理',
    UPGRADE_COMPLETE: '升级完成',
    UPGRADE_FAILED: '升级失败',
  }
  return map[v] || v
}

function formatCountryCn(v: any): string {
  if (!v) return '—'
  const raw = String(v).trim()
  if (!raw) return '—'
  const key = raw.toUpperCase()

  // 优先：如果是 ISO 3166-1 alpha-2 code，使用 Intl.DisplayNames 自动翻译为中文
  if (/^[A-Z]{2}$/.test(key) && typeof Intl !== 'undefined') {
    // 业务口径：TW 展示为「中华民国台湾」
    if (key === 'TW') return '中华民国台湾'
    try {
      const dn = new (Intl as any).DisplayNames(['zh-CN'], { type: 'region' })
      const cn = dn.of(key)
      if (cn && typeof cn === 'string') return cn
    } catch {}
  }

  // 优先按国家/地区码映射
  const CODE_TO_CN: Record<string, string> = {
    // 兜底/特殊口径（避免 DisplayNames 差异）
    TW: '中华民国台湾',
  }
  if (CODE_TO_CN[key]) return CODE_TO_CN[key]

  // 兼容部分英文名/别名
  const NAME_TO_CN: Record<string, string> = {
    'UNITED STATES': '美国',
    'UNITED STATES OF AMERICA': '美国',
    'USA': '美国',
    'CHINA': '中华人民共和国',
    "PEOPLE'S REPUBLIC OF CHINA": '中华人民共和国',
    'PRC': '中华人民共和国',
    'TURKEY': '土耳其',
    'TÜRKIYE': '土耳其',
    'TURKIYE': '土耳其',
    'CZECH REPUBLIC': '捷克共和国',
    'CZECHIA': '捷克共和国',
    'TAIWAN': '中华民国台湾',
    'TAIWAN, PROVINCE OF CHINA': '中华民国台湾',
  }
  const upper = key
  for (const [k, cn] of Object.entries(NAME_TO_CN)) {
    if (upper === k) return cn
  }

  return raw
}

const tenantTableScrollX = 1368
const showTenantCreateTimeColumn = false
const tenantCreateTimeColumn = { title: '添加日期', key: 'createTime', width: 168 }
const tenantNameMasked = ref(false)

function maskTenantName(value: any) {
  const text = String(value ?? '').trim()
  if (!text) return ''
  return `${text.charAt(0)}***${text.charAt(text.length - 1)}`
}

function displayTenantName(value: any) {
  const text = String(value ?? '').trim()
  if (!text) return ''
  return tenantNameMasked.value ? maskTenantName(text) : text
}

function toggleTenantNameMask(e?: MouseEvent) {
  e?.stopPropagation()
  tenantNameMasked.value = !tenantNameMasked.value
}

function tenantNameColumnTitle() {
  const Icon = tenantNameMasked.value ? EyeInvisibleOutlined : EyeOutlined
  return h('span', { class: 'tenant-name-title' }, [
    h('span', { class: 'tenant-name-title-text' }, '租户名'),
    h(
      'span',
      {
        class: 'tenant-name-mask-btn',
        role: 'button',
        tabindex: 0,
        title: tenantNameMasked.value ? '显示租户名' : '打码租户名',
        'aria-label': tenantNameMasked.value ? '显示租户名' : '打码租户名',
        onClick: toggleTenantNameMask,
        onKeydown: (event: KeyboardEvent) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault()
            toggleTenantNameMask(event as unknown as MouseEvent)
          }
        },
      },
      [h(Icon)],
    ),
  ])
}

const columns = computed(() => [
  { title: '名称', dataIndex: 'username', key: 'username', width: 300 },
  { title: tenantNameColumnTitle, dataIndex: 'tenantName', key: 'tenantName', width: 220, ellipsis: true },
  { title: '主区域', dataIndex: 'ociRegion', key: 'ociRegion', width: 220 },
  { title: '开机任务', key: 'taskStatus', width: 140 },
  { title: '账户类型', dataIndex: 'planType', key: 'planType', width: 130 },
  ...(showTenantCreateTimeColumn ? [tenantCreateTimeColumn] : []),
  { title: '操作', key: 'action', width: 310 },
])

const submitLoading = ref(false)
const searchTableData = ref<any[]>([])
const searchText = ref('')
const normalizedSearchText = computed(() => searchText.value.trim())
const isSearchingTenants = computed(() => !!normalizedSearchText.value)
const loading = computed(() => isSearchingTenants.value ? searchLoading.value : catalog.tenantsLoading)
const tableData = computed(() => (normalizedSearchText.value ? searchTableData.value : catalog.tenants) as any[])
const selectedRowKeys = ref<string[]>([])
let tenantInfoPollTimers: ReturnType<typeof setTimeout>[] = []
const batchMoveVisible = ref(false)
const batchMoveLoading = ref(false)
const batchMoveG1 = ref<string | undefined>(undefined)
const batchMoveG2 = ref<string | undefined>(undefined)
const modalVisible = ref(false)
const editingId = ref('')
const pagination = reactive({ current: 1, pageSize: 10, total: 0 })
const importText = ref('')
const fileList = ref<UploadFile[]>([])
const keyInputMode = ref<'upload' | 'paste'>('upload')
const pemPasteText = ref('')
const regionOptionsLoading = ref(false)
const regionInputMode = ref<'select' | 'manual'>('select')
const regionInputModeOptions = [
  { label: '列表选择', value: 'select' },
  { label: '手动输入', value: 'manual' },
]
const OCI_REGION_ID_PATTERN = /^[a-z]{2}-[a-z0-9]+(?:-[a-z0-9]+)*-\d+$/
let regionOptionsRequestSeq = 0

const formState = reactive({
  username: '', ociTenantId: '', ociUserId: '',
  ociFingerprint: '', ociRegion: '', ociKeyPath: '',
  groupLevel1: '' as string, groupLevel2: '' as string,
})

const groupData = computed(() => catalog.groupData)
const groupColors = ['#1677ff', '#52c41a', '#fa541c', '#722ed1', '#eb2f96', '#faad14', '#13c2c2']

const renameVisible = ref(false)
const renameLoading = ref(false)
const renameOldName = ref('')
const renameNewName = ref('')
const renameLevel = ref('1')

const addSubVisible = ref(false)
const addSubParent = ref('')
const addSubName = ref('')

const groupMgrVisible = ref(false)
const createGroupFormVisible = ref(false)
const createGroupName = ref('')
const createGroupLevel = ref('1')
const createGroupParent = ref('')
const createGroupLoading = ref(false)

let pendingFile: File | null = null
const isMobile = ref(window.innerWidth < 768)

const tenantMgmtVisible = ref(false)
const tenantMgmtTenant = ref<any>(null)
const tenantTab = ref('account')
const tenantInfoLoading = ref(false)
const tenantInfoData = ref<any>({})
let tenantInfoRequestSeq = 0
const billingLoading = ref(false)
const billingData = ref<any | null>(null)
const billingCostDays = ref(30)
const billingCostDayOptions = [
  { value: 7, label: '近 7 天' },
  { value: 30, label: '近 30 天' },
  { value: 90, label: '近 90 天' },
]
const budgetsLoading = ref(false)
const budgetsData = ref<any | null>(null)
const selectedBudgetId = ref('')
const budgetAlertRulesLoading = ref(false)
const budgetCompartmentsLoading = ref(false)
const budgetCompartmentsData = ref<any | null>(null)
const budgetCompartmentsLoadedTenantId = ref('')
const budgetFormVisible = ref(false)
const budgetFormLoading = ref(false)
const budgetFormMode = ref<'create' | 'edit'>('create')
const budgetForm = reactive({
  budgetId: '',
  displayName: '',
  description: '',
  amount: 100,
  compartmentId: '',
  targetType: 'COMPARTMENT' as BudgetTargetType,
  target: '',
  resetPeriod: 'MONTHLY' as 'MONTHLY',
  processingPeriodType: 'MONTH' as BudgetProcessingPeriodType,
  budgetProcessingPeriodStartOffset: null as number | null,
  startDate: '',
  endDate: '',
})
const budgetAlertFormVisible = ref(false)
const budgetAlertFormLoading = ref(false)
const budgetAlertFormMode = ref<'create' | 'edit'>('create')
const budgetAlertForm = reactive({
  budgetId: '',
  alertRuleId: '',
  displayName: '',
  description: '',
  type: 'ACTUAL' as BudgetAlertType,
  threshold: 80,
  thresholdType: 'PERCENTAGE' as BudgetThresholdType,
  recipients: '',
  message: '',
})
const budgetTargetTypeOptions = [
  { label: '区间', value: 'COMPARTMENT' },
  { label: '标签', value: 'TAG' },
]
const budgetProcessingPeriodOptions = [
  { label: '每月', value: 'MONTH' },
  { label: '发票周期', value: 'INVOICE' },
  { label: '一次性', value: 'SINGLE_USE' },
]
const budgetResetPeriodOptions = [{ label: '每月', value: 'MONTHLY' }]
const budgetAlertTypeOptions = [
  { label: '实际支出', value: 'ACTUAL' },
  { label: '预测支出', value: 'FORECAST' },
]
const budgetThresholdTypeOptions = [
  { label: '百分比', value: 'PERCENTAGE' },
  { label: '固定金额', value: 'ABSOLUTE' },
]
const budgetsList = computed<any[]>(() => Array.isArray(budgetsData.value?.items) ? budgetsData.value.items : [])
const selectedBudget = computed<any | null>(() =>
  budgetsList.value.find((b: any) => b.id === selectedBudgetId.value) || budgetsList.value[0] || null)
const selectedBudgetAlertRules = computed<any[]>(() =>
  Array.isArray(selectedBudget.value?.alertRules) ? selectedBudget.value.alertRules : [])
const budgetCompartmentOptions = computed(() => buildBudgetCompartmentOptions(budgetForm.compartmentId))
const budgetTargetCompartmentOptions = computed(() => buildBudgetCompartmentOptions(budgetForm.target))

const regionsLoading = ref(false)
const regionsData = ref<any | null>(null)
let regionsRequestSeq = 0
const regionSearch = ref('')
const regionSubscribeVerifyVisible = ref(false)
const regionSubscribeLoading = ref(false)
const regionSubscribeCodeSending = ref(false)
const regionSubscribeSendingKey = ref('')
const regionSubscribeCode = ref('')
const regionSubscribeTarget = ref<any | null>(null)
const regionsList = computed<any[]>(() => Array.isArray(regionsData.value?.items) ? regionsData.value.items : [])
const sortedRegionsList = computed<any[]>(() => sortTenantRegions(regionsList.value))
const quotaRegionOptions = computed(() => {
  const seen = new Set<string>()
  const options: Array<{ label: string; value: string; isHomeRegion: boolean }> = []
  const subscribedRegions = sortedRegionsList.value.filter((r: any) =>
    r?.subscribed && r?.regionName && String(r?.status || '').toUpperCase() === 'READY',
  )
  for (const region of subscribedRegions) {
    const value = String(region.regionName || '').trim()
    if (!value || seen.has(value)) continue
    seen.add(value)
    options.push({ label: value, value, isHomeRegion: Boolean(region.isHomeRegion) })
  }
  const fallbackRegion = String(tenantMgmtTenant.value?.ociRegion || '').trim()
  if (fallbackRegion && !seen.has(fallbackRegion)) {
    options.push({ label: fallbackRegion, value: fallbackRegion, isHomeRegion: false })
  }
  return options
})
const filteredRegions = computed<any[]>(() => {
  const kw = regionSearch.value.trim().toLowerCase()
  if (!kw) return sortedRegionsList.value
  return sortTenantRegions(regionsList.value.filter((r: any) => {
    const haystack = [
      formatRegionDisplay(r),
      r.regionName,
      r.regionKey,
      formatRegionStatus(r.status),
      r.isHomeRegion ? '主区域' : '',
    ].filter(Boolean).join(' ').toLowerCase()
    return haystack.includes(kw)
  }))
})
const regionSubscribeTargetDisplay = computed(() =>
  regionSubscribeTarget.value ? formatRegionDisplay(regionSubscribeTarget.value) : '—')

const iamPoliciesLoading = ref(false)
const iamPoliciesList = ref<any[]>([])
const iamPoliciesCompartmentId = ref('')
const iamPolicySearch = ref('')
const iamExpandedRowKeys = ref<string[]>([])
const iamPolicyStatements = ref<Record<string, string[]>>({})
const iamPolicyDetailLoading = ref('')
const announcementsLoading = ref(false)
const announcementsList = ref<any[]>([])
const announcementsRetentionNote = ref('')
const announcementSearch = ref('')
const announcementDrawerVisible = ref(false)
const announcementDetailLoading = ref(false)
const announcementDetailTab = ref('detail')
const announcementDetail = ref<any | null>(null)
const announcementImpacted = ref<any[]>([])
const announcementHistory = ref<any[]>([])
const announcementDrawerTitle = ref('云公告详情')
const announcementReadUpdatingId = ref('')
function checkMobile() { isMobile.value = window.innerWidth < 768 }

function formatAnnouncementUserStatus(v: string | null | undefined): string {
  if (v === 'Read') return '已读'
  if (v === 'Unread') return '未读'
  return '未知'
}

function announcementStatusColor(v: string | null | undefined): string {
  if (v === 'Read') return 'default'
  if (v === 'Unread') return 'blue'
  return 'default'
}

function isAnnouncementUnread(record: any): boolean {
  return record?.userStatus === 'Unread'
}

/** 转义 HTML 并将 OCI 公告中的 [text](url) 转为可点击链接 */
function formatAnnouncementBody(text: string | null | undefined): string {
  if (!text) return ''
  const escaped = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
  return escaped.replace(
    /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
    '<a href="$2" target="_blank" rel="noopener noreferrer" class="announcement-link">$1</a>',
  )
}

function announcementCustomRow(record: any) {
  return {
    style: { cursor: 'pointer' },
    onClick: () => openAnnouncementDetail(record),
  }
}

function parseAndFill() {
  if (!importText.value.trim()) {
    message.warning('请粘贴 OCI 配置内容')
    return
  }
  const lines = importText.value.split('\n').map(l => l.trim()).filter(l => l)
  let name = ''
  const fields: Record<string, string> = {}

  for (const line of lines) {
    const sec = line.match(/^\[(.+)\]$/)
    if (sec) { name = sec[1]; continue }
    const kv = line.match(/^(\w+)\s*=\s*(.+)$/)
    if (kv) fields[kv[1].toLowerCase()] = kv[2].trim()
  }

  if (!fields['user'] && !fields['tenancy'] && !fields['fingerprint']) {
    message.error('未能解析出有效配置，请检查格式')
    return
  }

  formState.username = name || formState.username
  formState.ociUserId = fields['user'] || formState.ociUserId
  formState.ociTenantId = fields['tenancy'] || formState.ociTenantId
  formState.ociFingerprint = fields['fingerprint'] || formState.ociFingerprint
  const parsedRegion = normalizeOciRegionValue(fields['region'])
  if (parsedRegion) {
    formState.ociRegion = parsedRegion
    syncRegionInputMode(parsedRegion)
  }
  message.success('已解析并填充，请上传或粘贴私钥后提交')
}

/** 新增/编辑保存后展开目标分组；普通 loadData 不设置 */
let pendingExpandTarget: { groupLevel1?: string; groupLevel2?: string } | null = null
let tenantSearchTimer: ReturnType<typeof setTimeout> | null = null
let tenantSearchRequestSeq = 0

async function loadData(expandAfter?: { groupLevel1?: string; groupLevel2?: string }) {
  if (expandAfter && typeof expandAfter === 'object') {
    pendingExpandTarget = expandAfter
  }
  const keyword = normalizedSearchText.value
  if (keyword) {
    const requestSeq = ++tenantSearchRequestSeq
    searchLoading.value = true
    try {
      const page = pagination.current
      const size = pagination.pageSize
      const res = await appQueryCache.fetch(
        ['tenantConfig', 'search', keyword, page, size],
        () => getTenantList({ current: page, size, keyword }),
        { staleMs: TENANT_SEARCH_STALE_MS },
      )
      if (requestSeq === tenantSearchRequestSeq && normalizedSearchText.value === keyword) {
        searchTableData.value = res.data.records || []
        pagination.total = res.data.total || 0
        scheduleTenantInfoPollingIfNeeded(searchTableData.value)
      }
    } catch (e: any) {
      message.error(e?.message || '加载租户列表失败')
    } finally {
      if (requestSeq === tenantSearchRequestSeq) {
        searchLoading.value = false
      }
    }
    return
  }
  tenantSearchRequestSeq += 1
  searchLoading.value = false
  searchTableData.value = []
  try {
    await Promise.all([
      catalog.ensureTenants({ force: false }),
      catalog.ensureGroups({ force: false }),
    ])
  } catch (e: any) {
    message.error(e?.message || catalog.tenantsError || '加载租户列表失败')
  }
  applyDefaultExpandAfterLoad()
  scheduleTenantInfoPollingIfNeeded(catalog.tenants as any[])
}

async function loadGroups() {
  await catalog.ensureGroups({ force: true })
}

function onSearchTenants() {
  pendingExpandTarget = null
  pagination.current = 1
  if (tenantSearchTimer) {
    clearTimeout(tenantSearchTimer)
    tenantSearchTimer = null
  }
  void loadData()
}

watch(searchText, () => {
  pendingExpandTarget = null
  pagination.current = 1
  if (tenantSearchTimer) {
    clearTimeout(tenantSearchTimer)
  }
  if (!normalizedSearchText.value) {
    tenantSearchRequestSeq += 1
    searchLoading.value = false
    searchTableData.value = []
    pagination.total = 0
  }
  tenantSearchTimer = setTimeout(() => {
    tenantSearchTimer = null
    void loadData()
  }, 250)
})

function invalidateCatalogAndReload() {
  catalog.invalidate()
  appQueryCache.invalidate(['tenantConfig'])
  void loadData()
}

function clearTenantInfoPollTimers() {
  for (const timer of tenantInfoPollTimers) clearTimeout(timer)
  tenantInfoPollTimers = []
}

async function refreshTenantListSilently() {
  const keyword = normalizedSearchText.value
  try {
    if (keyword) {
      const requestSeq = tenantSearchRequestSeq
      const page = pagination.current
      const size = pagination.pageSize
      const res = await appQueryCache.fetch(
        ['tenantConfig', 'search', keyword, page, size],
        () => getTenantList({ current: page, size, keyword }),
        { staleMs: TENANT_SEARCH_STALE_MS, force: true },
      )
      if (requestSeq === tenantSearchRequestSeq && normalizedSearchText.value === keyword) {
        searchTableData.value = res.data.records || []
        pagination.total = res.data.total || 0
      }
      return
    }
    catalog.invalidate()
    await Promise.all([
      catalog.ensureTenants({ force: true, silent: true }),
      catalog.ensureGroups({ force: false, silent: true }),
    ])
  } catch {
    // 静默轮询只负责把后台刷新结果带回页面，失败时保留当前显示。
  }
}

function scheduleTenantInfoPolling() {
  clearTenantInfoPollTimers()
  for (const delay of [3000, 8000, 15000, 30000]) {
    tenantInfoPollTimers.push(setTimeout(() => {
      void refreshTenantListSilently()
    }, delay))
  }
}

function scheduleTenantInfoPollingIfNeeded(rows: any[]) {
  if (!Array.isArray(rows) || rows.length === 0) return
  const hasPending = rows.some((r: any) => !r?.tenantName || !r?.planType)
  if (hasPending) {
    scheduleTenantInfoPolling()
  }
}

function handleTableChange(pag: any) {
  pagination.current = pag.current
  pagination.pageSize = pag.pageSize
  loadData()
}

function onSelectChange(keys: string[]) {
  selectedRowKeys.value = keys
}

function resetForm() {
  Object.assign(formState, { username: '', ociTenantId: '', ociUserId: '', ociFingerprint: '', ociRegion: '', ociKeyPath: '', groupLevel1: '', groupLevel2: '' })
  regionInputMode.value = 'select'
  pendingFile = null
  fileList.value = []
  importText.value = ''
  pemPasteText.value = ''
  keyInputMode.value = isMobile.value ? 'paste' : 'upload'
}

function onKeyInputModeChange() {
  if (keyInputMode.value === 'upload') {
    pemPasteText.value = ''
  } else {
    pendingFile = null
    fileList.value = []
  }
}

function validatePemPasteText(text: string): boolean {
  const t = text.trim()
  return t.includes('BEGIN') && t.includes('PRIVATE KEY')
}

function pemPasteTextToFile(text: string): File {
  const trimmed = text.trim()
  const body = trimmed.endsWith('\n') ? trimmed : `${trimmed}\n`
  return new File([body], 'pasted.pem', { type: 'application/x-pem-file' })
}

function normalizeOciRegionValue(value?: string) {
  return String(value || '').trim().toLowerCase()
}

function hasOciRegionOption(value?: string) {
  const normalized = normalizeOciRegionValue(value)
  if (!normalized) return false
  return ociRegionSelectOptions.value.some((opt) => opt.value === normalized)
}

function syncRegionInputMode(value?: string) {
  const normalized = normalizeOciRegionValue(value)
  regionInputMode.value = normalized && !hasOciRegionOption(normalized) ? 'manual' : 'select'
}

function normalizeRegionInput() {
  formState.ociRegion = normalizeOciRegionValue(formState.ociRegion)
}

async function refreshRegionOptionsForForm(userId?: string) {
  const requestSeq = ++regionOptionsRequestSeq
  regionOptionsLoading.value = true
  try {
    await loadOciRegionCatalog(userId)
  } finally {
    if (requestSeq === regionOptionsRequestSeq) {
      regionOptionsLoading.value = false
    }
  }
}

function showAddModal() {
  editingId.value = ''
  resetForm()
  modalVisible.value = true
  void refreshRegionOptionsForForm()
}

function showEditModal(record: any) {
  editingId.value = record.id
  const normalizedRegion = normalizeOciRegionValue(record.ociRegion)
  Object.assign(formState, {
    username: record.username,
    ociTenantId: record.ociTenantId,
    ociUserId: record.ociUserId,
    ociFingerprint: record.ociFingerprint,
    ociRegion: normalizedRegion,
    ociKeyPath: record.ociKeyPath,
    groupLevel1: record.groupLevel1 || '',
    groupLevel2: record.groupLevel2 || undefined,
  })
  syncRegionInputMode(normalizedRegion)
  pendingFile = null
  fileList.value = []
  importText.value = ''
  pemPasteText.value = ''
  keyInputMode.value = 'upload'
  modalVisible.value = true
  void refreshRegionOptionsForForm(record.id)
}

const domainMgmtVisible = ref(false)
const domainMgmtTenant = ref<any>(null)
const domainTab = ref('security')
const domainSettingsLoading = ref(false)
const domainList = ref<any[]>([])
const selectedDomainId = ref('')
const mfaUpdatingId = ref('')
const mfaVerifyVisible = ref(false)
const mfaVerifyLoading = ref(false)
const mfaVerifyCodeSending = ref(false)
const mfaVerifyCode = ref('')
const mfaTargetDomain = ref<any | null>(null)
const mfaTargetEnabled = ref(false)
const pwdExpiryUpdatingId = ref('')
const auditLogsLoading = ref(false)
const auditLogsLoaded = ref(false)
const auditLogs = ref<any[]>([])
const auditDays = ref(7)
let auditLogsRequestSeq = 0
const domainAuditLogsLoading = ref(false)
const domainAuditLogsLoaded = ref(false)
const domainAuditLogs = ref<any[]>([])
const domainAuditDays = ref(7)
let domainAuditLogsRequestSeq = 0
const notificationLoading = ref(false)
const notificationSaving = ref(false)
const notificationData = ref<any | null>(null)
const notificationRecipientsText = ref('')
const notificationEventActiveKeys = ref<string[]>([])
const notificationCodeSending = ref(false)
const notificationCodeInput = ref('')
const notificationUnlocking = ref(false)
const notificationToken = ref('')
const quotasLoading = ref(false)
const quotasList = ref<any[]>([])
let quotaRequestSeq = 0
const quotaSearch = ref('')
const quotaRegion = ref('')
const quotaService = ref('__all')
const quotaServiceOptions = computed(() => {
  const services = new Set<string>()
  for (const item of quotasList.value) {
    const service = String(item?.serviceName || '').trim()
    if (service) services.add(service)
  }
  return [
    { label: '全部服务', value: '__all' },
    ...Array.from(services).sort((a, b) => a.localeCompare(b)).map((service) => ({
      label: service,
      value: service,
    })),
  ]
})

const DOMAIN_TYPE_CN: Record<string, string> = {
  DEFAULT: '默认域',
  PRIMARY: '主域',
  SECONDARY: '辅助域',
  EXTERNAL: '外部域',
}
function domainTypeCn(t: string | null | undefined): string {
  if (!t) return ''
  const key = String(t).toUpperCase()
  return DOMAIN_TYPE_CN[key] || t
}

const selectedDomain = computed<any | null>(() =>
  domainList.value.find((d: any) => d.domainId === selectedDomainId.value) || null,
)
const selectedFactorDomain = computed<any | null>(() =>
  authFactorDomains.value.find((d: any) => d.domainId === selectedDomainId.value) || null,
)
const selectedAuditDomain = computed<any | null>(() =>
  auditLogs.value.find((d: any) => d.domainId === selectedDomainId.value) || null,
)
const selectedDomainAudit = computed<any | null>(() =>
  domainAuditLogs.value.find((d: any) => d.domainId === selectedDomainId.value) || null,
)
const notificationEvents = computed<any[]>(() =>
  Array.isArray(notificationData.value?.eventSettings) ? notificationData.value.eventSettings : [],
)
const notificationAdminEvents = computed<any[]>(() =>
  notificationEvents.value.filter((event: any) => isAdminNotificationEvent(event.eventId)),
)
const notificationEndUserEvents = computed<any[]>(() =>
  notificationEvents.value.filter((event: any) => !isAdminNotificationEvent(event.eventId)),
)
const notificationValidateOptions = [
  { label: '电子邮件地址', value: 'email' },
  { label: '域', value: 'domain' },
]

const NOTIFICATION_ADMIN_LABELS = new Set([
  '作业已启动',
  '作业已取消',
  '作业已完成',
  '作业失败',
  '已超出限额限制',
  '已启动发件人电子邮件域验证',
  '为发件人电子邮件地址启动了电子邮件地址验证',
  '同步作业概要',
  'AD 桥与 OCI IAM 之间的连接中断时通知管理员',
  'AD 桥与 OCI IAM 之间的连接恢复时通知管理员',
  '有桥更新可用',
  'AD 桥与 OCI IAM 之间的同步成功时通知管理员',
  'AD 桥与 OCI IAM 之间的同步失败时通知管理员',
  '辅助域创建',
  'SAML SP 签名证书即将到期',
  'SAML IdP 签名证书即将到期',
  '已更新控制台登录策略同意书',
  '已还原控制台登录策略同意书',
  '已自动记录控制台登录策略同意书',
  '在 SSO 会话 Cookie 中检测到可疑活动',
  'job_drsync_conflicts_report',
])

const NOTIFICATION_EVENT_LABELS: Record<string, string> = {
  // 管理员通知
  'job.started': '作业已启动',
  'job.start': '作业已启动',
  'job.cancelled': '作业已取消',
  'job.canceled': '作业已取消',
  'job.cancel': '作业已取消',
  'job.completed': '作业已完成',
  'job.complete': '作业已完成',
  'job.failed': '作业失败',
  'job.failure': '作业失败',
  'job.error': '作业失败',
  'limit.exceeded': '已超出限额限制',
  'quota.limit.exceeded': '已超出限额限制',
  'sender.email.domain.verification.started': '已启动发件人电子邮件域验证',
  'sender.email.domain.verification.start': '已启动发件人电子邮件域验证',
  'sender.email.address.verification.started': '为发件人电子邮件地址启动了电子邮件地址验证',
  'sender.email.address.verification.start': '为发件人电子邮件地址启动了电子邮件地址验证',
  'sender.email.verification.started': '为发件人电子邮件地址启动了电子邮件地址验证',
  'sync.job.summary': '同步作业概要',
  'job.sync.summary': '同步作业概要',
  'job.synchronization.summary': '同步作业概要',
  'ad.bridge.connection.interrupted': 'AD 桥与 OCI IAM 之间的连接中断时通知管理员',
  'ad.bridge.connection.down': 'AD 桥与 OCI IAM 之间的连接中断时通知管理员',
  'ad.bridge.connection.restored': 'AD 桥与 OCI IAM 之间的连接恢复时通知管理员',
  'ad.bridge.connection.up': 'AD 桥与 OCI IAM 之间的连接恢复时通知管理员',
  'ad.bridge.update.available': '有桥更新可用',
  'ad.bridge.sync.success': 'AD 桥与 OCI IAM 之间的同步成功时通知管理员',
  'ad.bridge.sync.failure': 'AD 桥与 OCI IAM 之间的同步失败时通知管理员',
  'secondary.domain.create.success': '辅助域创建',
  'secondary.domain.created': '辅助域创建',
  'saml.sp.signing.certificate.expiring': 'SAML SP 签名证书即将到期',
  'saml.idp.signing.certificate.expiring': 'SAML IdP 签名证书即将到期',
  'console.signon.policy.consent.updated': '已更新控制台登录策略同意书',
  'console.signon.policy.consent.restored': '已还原控制台登录策略同意书',
  'console.signon.policy.consent.auto.recorded': '已自动记录控制台登录策略同意书',
  'sso.session.cookie.suspicious.activity': '在 SSO 会话 Cookie 中检测到可疑活动',
  'job_drsync_conflicts_report': 'job_drsync_conflicts_report',

  // 最终用户通知
  'admin.user.create.success': '欢迎使用',
  'admin.approval.create.success': '已创建审批请求',
  'admin.workflow.request.expiry': '工作流请求已过期',
  'admin.workflow.request.rejected': '工作流请求已被拒绝',
  'admin.me.register.activation.required': '自行注册电子邮件验证',
  'admin.me.register.success': '欢迎自行注册用户',
  'admin.user.federated.create.success': '欢迎联合 SSO 用户',
  'admin.user.authentication.delegated.create.success': '欢迎委派验证用户',
  'admin.user.initiate.activation.success': '欢迎（重新发送）',
  'admin.user.authentication.delegated.initiate.activation.success': '欢迎委派验证用户（重新发送）',
  'admin.me.password.reset.request.success': '密码恢复',
  'admin.me.recovery.email.verification.required': '恢复电子邮件验证',
  'admin.me.recovery.email.verify.request.success': '恢复电子邮件验证',
  'admin.me.primary.email.verification.required': '主电子邮件验证',
  'admin.me.primary.email.verify.request.success': '主电子邮件验证',
  'admin.me.secondary.email.verification.required': '辅助电子邮件验证',
  'admin.me.secondary.email.verify.request.success': '辅助电子邮件验证',
  'admin.me.recovery.email.update.success': '恢复电子邮件更新',
  'admin.me.primary.email.update.success': '主电子邮件更新',
  'admin.me.secondary.email.update.success': '辅助电子邮件更新',
  'admin.me.password.change.success': '密码更改',
  'admin.user.password.change.success': '管理员已将密码更改为已知值',
  'admin.user.password.reset.success': '密码重置',
  'admin.user.password.reset.request.success': '管理员代表用户请求密码重置',
  'admin.user.activate.success': '用户激活',
  'admin.user.activation.success': '用户激活',
  'admin.user.deactivate.success': '用户停用',
  'admin.user.deactivation.success': '用户停用',
  'admin.user.lock.success': '已锁定用户账户',
  'admin.user.account.locked': '已锁定用户账户',
  'admin.me.account.recovery.max.attempts.exceeded': '用户已超过账户恢复最大尝试次数',
  'admin.user.account.recovery.max.attempts.exceeded': '用户已超过账户恢复最大尝试次数',
  'admin.user.unlock.success': '已取消锁定用户账户',
  'admin.user.account.unlocked': '已取消锁定用户账户',
  'admin.user.update.success': '管理员已更新用户概要信息',
  'admin.user.replace.success': '管理员已替换用户概要信息',
  'admin.me.mfa.enrollment.request': '用于启用两步验证的设备注册请求',
  'admin.me.device.registration.request': '用于启用两步验证的设备注册请求',
  'admin.me.mfa.locked': '两步验证用户账户已锁定',
  'admin.me.mfa.account.locked': '两步验证用户账户已锁定',
  'admin.user.federated.mfa.locked': '两步验证联合 SSO 用户账户已锁定',
  'admin.me.bypasscode.verify': '两步绕过码验证',
  'admin.me.bypasscode.verification': '两步绕过码验证',
  'admin.me.kerberos.enable.request': '启用 Kerberos 验证请求',
  'admin.access.request.create.success': '已提交新的访问请求',
  'admin.access.request.complete.success': '已完成访问请求',
  'admin.access.request.complete.failure': '无法完成访问请求',
  'admin.me.email.otp.verify': '两步电子邮件一次性验证码验证',
  'admin.me.email.otp.verification': '两步电子邮件一次性验证码验证',
  'admin.me.new.device.login': '检测到使用您的账户的新设备登录',
  'admin.user.primary.email.verify.complete': '主电子邮件验证完成时通知用户',
  'admin.user.recovery.email.verify.complete': '恢复电子邮件验证完成时通知用户',
  'admin.user.secondary.email.verify.complete': '辅助恢复电子邮件验证完成时通知用户',
  'admin.verify.email.link': '验证电子邮件链接',
  'admin.user.password.propagation.failure': '用户密码传播失败',
  'admin_user_mfa_reset_success': 'admin_user_mfa_reset_success',
  'admin_user_bypasscode_create_success': 'admin_user_bypasscode_create_success',
  'admin_requester_approval_create_success': 'admin_requester_approval_create_success',
  'admin_approval_update_success': 'admin_approval_update_success',
}

function handleDomainChange(domainId: string) {
  if (!domainId || domainId === selectedDomainId.value) return
  selectedDomainId.value = domainId
  auditLogsRequestSeq++
  auditLogs.value = []
  auditLogsLoaded.value = false
  auditLogsLoading.value = false
  domainAuditLogsRequestSeq++
  domainAuditLogs.value = []
  domainAuditLogsLoaded.value = false
  domainAuditLogsLoading.value = false
  resetNotificationState(true)
  if (domainTab.value === 'notifications' && notificationToken.value) void loadDomainNotifications()
  if (domainTab.value === 'logs') void loadAuditLogs()
  if (domainTab.value === 'audit') void loadDomainAuditLogs()
}

watch(() => domainTab.value, (tab) => {
  if (tab === 'notifications' && selectedDomainId.value && notificationToken.value && !notificationData.value) {
    void loadDomainNotifications()
  }
})

function onAuditDaysChange() {
  auditLogsRequestSeq++
  auditLogs.value = []
  auditLogsLoaded.value = false
  auditLogsLoading.value = false
}

function onDomainAuditDaysChange() {
  domainAuditLogsRequestSeq++
  domainAuditLogs.value = []
  domainAuditLogsLoaded.value = false
  domainAuditLogsLoading.value = false
}

async function sendNotificationCode() {
  notificationCodeSending.value = true
  try {
    await sendVerifyCode('domainNotifications')
    message.success('验证码已发送至 Telegram')
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
  } finally {
    notificationCodeSending.value = false
  }
}

async function doUnlockNotifications() {
  if (!notificationCodeInput.value || notificationCodeInput.value.length !== 6) {
    return message.warning('请输入 6 位验证码')
  }
  notificationUnlocking.value = true
  try {
    const r = await unlockDomainNotifications({ verifyCode: notificationCodeInput.value })
    notificationToken.value = r.data?.accessToken || ''
    notificationCodeInput.value = ''
    if (!notificationToken.value) throw new Error('未获取到访问令牌')
    await loadDomainNotifications()
    message.success('已解锁')
  } catch (e: any) {
    message.error(e?.message || '解锁失败')
  } finally {
    notificationUnlocking.value = false
  }
}

function resetNotificationState(keepToken = false) {
  notificationData.value = null
  notificationRecipientsText.value = ''
  notificationEventActiveKeys.value = []
  if (!keepToken) {
    notificationCodeInput.value = ''
    notificationToken.value = ''
  }
}

function isUnlockExpiredMessage(msg: any): boolean {
  const text = String(msg || '')
  return text.includes('解锁') || text.includes('失效') || text.includes('过期')
}

function normalizeDomainNotification(raw: any) {
  const from = raw?.fromEmailAddress || {}
  const validate = String(from.validate || 'email').toLowerCase()
  return {
    ...(raw || {}),
    notificationEnabled: !!raw?.notificationEnabled,
    testModeEnabled: !!raw?.testModeEnabled,
    testRecipients: Array.isArray(raw?.testRecipients) ? raw.testRecipients : [],
    sendNotificationToOldAndNewPrimaryEmailsWhenAdminChangesPrimaryEmail:
      !!raw?.sendNotificationToOldAndNewPrimaryEmailsWhenAdminChangesPrimaryEmail,
    fromEmailAddress: {
      value: from.value || '',
      displayName: from.displayName || '',
      validate: validate === 'domain' ? 'domain' : 'email',
      validationStatus: from.validationStatus || '',
    },
    eventSettings: Array.isArray(raw?.eventSettings)
      ? raw.eventSettings.map((e: any) => ({ eventId: e.eventId || '', enabled: !!e.enabled })).filter((e: any) => e.eventId)
      : [],
  }
}

function parseNotificationRecipients(text: string): string[] {
  return Array.from(new Set(
    String(text || '')
      .split(/[,，;；\n\r]+/)
      .map((s) => s.trim())
      .filter(Boolean),
  ))
}

function formatNotificationValidationStatus(status: string | null | undefined): string {
  const s = String(status || '').toUpperCase()
  if (s === 'VERIFIED') return '已验证'
  if (s === 'PENDING') return '待验证'
  return '—'
}

function notificationValidationStatusColor(status: string | null | undefined): string {
  const s = String(status || '').toUpperCase()
  if (s === 'VERIFIED') return 'green'
  if (s === 'PENDING') return 'orange'
  return 'default'
}

function normalizeNotificationEventId(eventId: string | null | undefined): string {
  return String(eventId || '')
    .trim()
    .toLowerCase()
    .replace(/[-_]+/g, '.')
    .replace(/\.+/g, '.')
}

function isAdminNotificationEvent(eventId: string | null | undefined): boolean {
  const label = formatNotificationEventName(eventId)
  if (NOTIFICATION_ADMIN_LABELS.has(label)) return true
  const normalized = normalizeNotificationEventId(eventId)
  return (
    normalized.startsWith('job.') ||
    normalized.includes('.job.') ||
    normalized.includes('bridge') ||
    normalized.includes('drsync') ||
    normalized.includes('saml') ||
    normalized.includes('signing.certificate') ||
    normalized.includes('console.signon.policy.consent') ||
    normalized.includes('sso.session.cookie') ||
    normalized.includes('secondary.domain') ||
    normalized.includes('limit.exceeded') ||
    normalized.includes('sender.email')
  )
}

function formatNotificationEventName(eventId: string | null | undefined): string {
  const id = String(eventId || '').trim()
  if (!id) return '未知事件'
  const exact = NOTIFICATION_EVENT_LABELS[id] || NOTIFICATION_EVENT_LABELS[id.toLowerCase()]
  if (exact) return exact
  const normalized = normalizeNotificationEventId(id)
  const mapped = NOTIFICATION_EVENT_LABELS[normalized]
  if (mapped) return mapped
  if (normalized.includes('job') && normalized.includes('start')) return '作业已启动'
  if (normalized.includes('job') && (normalized.includes('cancel') || normalized.includes('canceled') || normalized.includes('cancelled'))) return '作业已取消'
  if (normalized.includes('job') && (normalized.includes('complete') || normalized.includes('completed'))) return '作业已完成'
  if (normalized.includes('job') && (normalized.includes('fail') || normalized.includes('failed') || normalized.includes('error'))) return '作业失败'
  if (normalized.includes('limit')) return '已超出限额限制'
  if (normalized.includes('sender.email.domain') && normalized.includes('verification')) return '已启动发件人电子邮件域验证'
  if (normalized.includes('sender.email') && normalized.includes('verification')) return '为发件人电子邮件地址启动了电子邮件地址验证'
  if (normalized.includes('sync') && normalized.includes('summary')) return '同步作业概要'
  if (normalized.includes('bridge') && normalized.includes('connection') && (normalized.includes('interrupt') || normalized.includes('down') || normalized.includes('broken'))) return 'AD 桥与 OCI IAM 之间的连接中断时通知管理员'
  if (normalized.includes('bridge') && normalized.includes('connection') && (normalized.includes('restore') || normalized.includes('up') || normalized.includes('recover'))) return 'AD 桥与 OCI IAM 之间的连接恢复时通知管理员'
  if (normalized.includes('bridge') && normalized.includes('update')) return '有桥更新可用'
  if (normalized.includes('bridge') && normalized.includes('sync') && normalized.includes('success')) return 'AD 桥与 OCI IAM 之间的同步成功时通知管理员'
  if (normalized.includes('bridge') && normalized.includes('sync') && (normalized.includes('fail') || normalized.includes('error'))) return 'AD 桥与 OCI IAM 之间的同步失败时通知管理员'
  if (normalized.includes('secondary.domain')) return '辅助域创建'
  if (normalized.includes('saml') && normalized.includes('sp') && (normalized.includes('cert') || normalized.includes('certificate'))) return 'SAML SP 签名证书即将到期'
  if (normalized.includes('saml') && normalized.includes('idp') && (normalized.includes('cert') || normalized.includes('certificate'))) return 'SAML IdP 签名证书即将到期'
  if (normalized.includes('console') && normalized.includes('signon') && normalized.includes('consent') && normalized.includes('update')) return '已更新控制台登录策略同意书'
  if (normalized.includes('console') && normalized.includes('signon') && normalized.includes('consent') && normalized.includes('restore')) return '已还原控制台登录策略同意书'
  if (normalized.includes('console') && normalized.includes('signon') && normalized.includes('consent')) return '已自动记录控制台登录策略同意书'
  if (normalized.includes('sso') && normalized.includes('cookie') && normalized.includes('suspicious')) return '在 SSO 会话 Cookie 中检测到可疑活动'
  if (normalized.includes('password.propagation') && normalized.includes('fail')) return '用户密码传播失败'
  if (normalized.includes('new.device') && normalized.includes('login')) return '检测到使用您的账户的新设备登录'
  if (normalized.includes('email.otp')) return '两步电子邮件一次性验证码验证'
  if (normalized.includes('kerberos')) return '启用 Kerberos 验证请求'
  if (normalized.includes('bypasscode')) return '两步绕过码验证'
  if (normalized.includes('mfa') && normalized.includes('lock')) return '两步验证用户账户已锁定'
  if (normalized.includes('access.request') && normalized.includes('create')) return '已提交新的访问请求'
  if (normalized.includes('access.request') && normalized.includes('complete') && (normalized.includes('fail') || normalized.includes('error'))) return '无法完成访问请求'
  if (normalized.includes('access.request') && normalized.includes('complete')) return '已完成访问请求'
  if (normalized.includes('password.reset.request') && normalized.includes('user')) return '管理员代表用户请求密码重置'
  if (normalized.includes('password.reset.request')) return '密码恢复'
  if (normalized.includes('password.reset')) return '密码重置'
  if (normalized.includes('password.change')) return '密码更改'
  if (normalized.includes('user.federated') && normalized.includes('create')) return '欢迎联合 SSO 用户'
  if (normalized.includes('authentication.delegated') && normalized.includes('initiate.activation')) return '欢迎委派验证用户（重新发送）'
  if (normalized.includes('authentication.delegated') && normalized.includes('create')) return '欢迎委派验证用户'
  if (normalized.includes('initiate.activation')) return '欢迎（重新发送）'
  if (normalized.includes('register.activation')) return '自行注册电子邮件验证'
  if (normalized.includes('register.success')) return '欢迎自行注册用户'
  if (normalized.includes('primary.email') && normalized.includes('complete')) return '主电子邮件验证完成时通知用户'
  if (normalized.includes('recovery.email') && normalized.includes('complete')) return '恢复电子邮件验证完成时通知用户'
  if (normalized.includes('secondary.email') && normalized.includes('complete')) return '辅助恢复电子邮件验证完成时通知用户'
  if (normalized.includes('primary.email') && normalized.includes('update')) return '主电子邮件更新'
  if (normalized.includes('recovery.email') && normalized.includes('update')) return '恢复电子邮件更新'
  if (normalized.includes('secondary.email') && normalized.includes('update')) return '辅助电子邮件更新'
  if (normalized.includes('primary.email') && normalized.includes('verif')) return '主电子邮件验证'
  if (normalized.includes('recovery.email') && normalized.includes('verif')) return '恢复电子邮件验证'
  if (normalized.includes('secondary.email') && normalized.includes('verif')) return '辅助电子邮件验证'
  if (normalized.includes('workflow.request.expiry')) return '工作流请求已过期'
  if (normalized.includes('workflow.request.rejected')) return '工作流请求已被拒绝'
  if (normalized.includes('approval.create')) return '已创建审批请求'
  return id
}

// ------- 验证因素 -------
const FACTOR_OPTIONS: { key: string; label: string }[] = [
  { key: 'totp', label: '移动应用程序验证码 (TOTP)' },
  { key: 'push', label: '移动应用程序通知 (Push)' },
  { key: 'phoneCall', label: '电话' },
  { key: 'sms', label: '短信 (SMS)' },
  { key: 'email', label: '电子邮件' },
  { key: 'securityQuestions', label: '安全问题' },
  { key: 'fido', label: 'FIDO 通行密钥' },
  { key: 'yubico', label: 'Yubico OTP' },
  { key: 'bypassCode', label: '绕过码' },
  { key: 'duoSecurity', label: 'Duo Security' },
]
const factorCodeSending = ref(false)
const factorCodeInput = ref('')
const factorUnlocking = ref(false)
const authFactorToken = ref('')
const authFactorLoading = ref(false)
const authFactorDomains = ref<any[]>([])
const factorSavingId = ref('')

function resetAuthFactorState() {
  factorCodeInput.value = ''
  authFactorToken.value = ''
  authFactorDomains.value = []
}

async function sendFactorCode() {
  factorCodeSending.value = true
  try {
    await sendVerifyCode('authFactors')
    message.success('验证码已发送至 Telegram')
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
  } finally {
    factorCodeSending.value = false
  }
}

async function doUnlockFactors() {
  if (!factorCodeInput.value || factorCodeInput.value.length !== 6) {
    return message.warning('请输入 6 位验证码')
  }
  factorUnlocking.value = true
  try {
    const r = await unlockAuthFactors({ verifyCode: factorCodeInput.value })
    authFactorToken.value = r.data?.accessToken || ''
    factorCodeInput.value = ''
    if (!authFactorToken.value) throw new Error('未获取到访问令牌')
    await reloadFactors()
    message.success('已解锁')
  } catch (e: any) {
    message.error(e?.message || '解锁失败')
  } finally {
    factorUnlocking.value = false
  }
}

async function reloadFactors() {
  if (!authFactorToken.value) return
  authFactorLoading.value = true
  try {
    const r = await getAuthFactors({ id: domainMgmtTenant.value.id, accessToken: authFactorToken.value })
    const raw = (r.data && typeof r.data === 'object' && 'domains' in r.data) ? r.data.domains : r.data
    authFactorDomains.value = (Array.isArray(raw) ? raw : []).map((d: any) => ({
      ...d,
      factors: { ...(d.factors || {}) },
      limits: { ...(d.limits || {}) },
      trustedDevice: { ...(d.trustedDevice || {}) },
    }))
  } catch (e: any) {
    message.error(e?.message || '读取验证因素失败')
    if (String(e?.message || '').includes('解锁') || String(e?.message || '').includes('失效') || String(e?.message || '').includes('过期')) {
      resetAuthFactorState()
    }
  } finally {
    authFactorLoading.value = false
  }
}

async function saveFactors(d: any) {
  factorSavingId.value = d.domainId
  try {
    const r = await updateAuthFactors({
      id: domainMgmtTenant.value.id,
      domainId: d.domainId,
      accessToken: authFactorToken.value,
      factors: d.factors,
      limits: d.limits,
      trustedDevice: d.trustedDevice,
    })
    if (r.data?.skipped) {
      message.info('未检测到变更')
    } else {
      message.success(`已保存 ${r.data?.changedOps || 0} 项变更`)
    }
    await reloadFactors()
  } catch (e: any) {
    message.error(e?.message || '保存失败')
    if (String(e?.message || '').includes('解锁') || String(e?.message || '').includes('失效') || String(e?.message || '').includes('过期')) {
      resetAuthFactorState()
    }
  } finally {
    factorSavingId.value = ''
  }
}

async function openDomainMgmt(record: any) {
  domainMgmtTenant.value = record
  domainTab.value = 'security'
  domainList.value = []
  selectedDomainId.value = ''
  auditLogsRequestSeq++
  auditLogs.value = []
  auditLogsLoaded.value = false
  auditLogsLoading.value = false
  domainAuditLogsRequestSeq++
  domainAuditLogs.value = []
  domainAuditLogsLoaded.value = false
  domainAuditLogsLoading.value = false
  resetNotificationState()
  resetAuthFactorState()
  domainMgmtVisible.value = true
  await loadDomainSettings()
}

watch(() => domainMgmtVisible.value, (v) => {
  if (!v) {
    resetAuthFactorState()
    resetNotificationState()
  }
})

async function loadDomainSettings() {
  domainSettingsLoading.value = true
  try {
    const res = await getDomainSettings({ id: domainMgmtTenant.value.id })
    const raw = (res.data && typeof res.data === 'object' && 'domains' in res.data) ? res.data.domains : res.data
    domainList.value = Array.isArray(raw) ? raw : []
    if (domainList.value.length > 0 && !selectedDomainId.value) {
      selectedDomainId.value = domainList.value[0].domainId
    }
  } catch (e: any) {
    message.error(e?.message || '获取域设置失败')
  } finally {
    domainSettingsLoading.value = false
  }
}

async function handleMfaChange(domain: any, checked: boolean) {
  if (!domainMgmtTenant.value?.id || !domain?.domainId) return
  mfaTargetDomain.value = domain
  mfaTargetEnabled.value = checked
  mfaVerifyCode.value = ''
  mfaUpdatingId.value = domain.domainId
  try {
    await sendVerifyCode('domainMfa')
    message.success('验证码已发送至 Telegram')
    mfaVerifyVisible.value = true
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
    mfaTargetDomain.value = null
    mfaUpdatingId.value = ''
  }
}

function cancelMfaVerify() {
  mfaVerifyVisible.value = false
  mfaVerifyCode.value = ''
  mfaTargetDomain.value = null
  mfaUpdatingId.value = ''
}

async function resendMfaVerifyCode() {
  if (!mfaTargetDomain.value?.domainId) return
  mfaVerifyCodeSending.value = true
  try {
    await sendVerifyCode('domainMfa')
    mfaVerifyCode.value = ''
    message.success('验证码已重新发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    mfaVerifyCodeSending.value = false
  }
}

async function submitMfaChange() {
  const tenantId = domainMgmtTenant.value?.id
  const domain = mfaTargetDomain.value
  if (!tenantId || !domain?.domainId) return
  if (!mfaVerifyCode.value || mfaVerifyCode.value.length !== 6) {
    message.warning('请输入 6 位验证码')
    return
  }

  const prev = domain.mfaEnabled
  mfaVerifyLoading.value = true
  mfaUpdatingId.value = domain.domainId
  try {
    await updateMfa({
      id: tenantId,
      domainId: domain.domainId,
      enabled: mfaTargetEnabled.value,
      verifyCode: mfaVerifyCode.value,
    })
    domain.mfaEnabled = mfaTargetEnabled.value
    message.success(mfaTargetEnabled.value ? 'MFA 已启用' : 'MFA 已关闭')
    mfaVerifyVisible.value = false
    mfaVerifyCode.value = ''
    mfaTargetDomain.value = null
  } catch (e: any) {
    domain.mfaEnabled = prev
    message.error(e?.message || '更新 MFA 策略失败')
  } finally {
    mfaVerifyLoading.value = false
    mfaUpdatingId.value = ''
  }
}

async function handlePwdExpiryChange(domain: any) {
  pwdExpiryUpdatingId.value = domain.domainId
  try {
    await updatePasswordExpiry({
      id: domainMgmtTenant.value.id,
      domainId: domain.domainId,
      days: domain.passwordExpiresAfterDays ?? 0,
    })
    message.success('密码过期策略已更新')
  } catch (e: any) {
    message.error(e?.message || '更新密码策略失败')
  } finally {
    pwdExpiryUpdatingId.value = ''
  }
}

async function loadDomainNotifications() {
  if (!domainMgmtTenant.value?.id || !selectedDomainId.value) {
    message.warning('请先选择域')
    return
  }
  if (!notificationToken.value) {
    message.warning('请先通过 TG 验证码解锁域通知')
    return
  }
  notificationLoading.value = true
  try {
    const res = await getDomainNotifications({
      id: domainMgmtTenant.value.id,
      domainId: selectedDomainId.value,
      accessToken: notificationToken.value,
    })
    notificationData.value = normalizeDomainNotification(res.data || {})
    notificationRecipientsText.value = (notificationData.value.testRecipients || []).join('\n')
  } catch (e: any) {
    message.error(e?.message || '读取域通知设置失败')
    if (isUnlockExpiredMessage(e?.message)) resetNotificationState()
  } finally {
    notificationLoading.value = false
  }
}

async function saveDomainNotifications() {
  if (!domainMgmtTenant.value?.id || !selectedDomainId.value || !notificationData.value) return
  if (!notificationToken.value) {
    message.warning('请先通过 TG 验证码解锁域通知')
    return
  }
  const fromEmail = notificationData.value.fromEmailAddress || {}
  if (!String(fromEmail.value || '').trim()) {
    message.warning('请填写发件人电子邮件地址')
    return
  }
  notificationSaving.value = true
  try {
    const res = await updateDomainNotifications({
      id: domainMgmtTenant.value.id,
      domainId: selectedDomainId.value,
      accessToken: notificationToken.value,
      notificationEnabled: !!notificationData.value.notificationEnabled,
      testModeEnabled: !!notificationData.value.testModeEnabled,
      testRecipients: parseNotificationRecipients(notificationRecipientsText.value),
      sendNotificationToOldAndNewPrimaryEmailsWhenAdminChangesPrimaryEmail:
        !!notificationData.value.sendNotificationToOldAndNewPrimaryEmailsWhenAdminChangesPrimaryEmail,
      fromEmailAddress: {
        value: String(fromEmail.value || '').trim(),
        displayName: String(fromEmail.displayName || '').trim(),
        validate: fromEmail.validate || 'email',
      },
      eventSettings: notificationEvents.value.map((e: any) => ({
        eventId: e.eventId,
        enabled: !!e.enabled,
      })),
    })
    if (res.data?.skipped) {
      message.info('未检测到变更')
    } else {
      message.success('域通知设置已保存')
    }
    const next = res.data?.notification || notificationData.value
    notificationData.value = normalizeDomainNotification(next)
    notificationRecipientsText.value = (notificationData.value.testRecipients || []).join('\n')
  } catch (e: any) {
    message.error(e?.message || '保存域通知设置失败')
    if (isUnlockExpiredMessage(e?.message)) resetNotificationState()
  } finally {
    notificationSaving.value = false
  }
}

async function loadAuditLogs() {
  if (!selectedDomainId.value) {
    message.warning('请先选择域')
    return
  }
  const seq = ++auditLogsRequestSeq
  const tenantId = domainMgmtTenant.value?.id
  const domainId = selectedDomainId.value
  if (!tenantId) {
    message.warning('请先选择租户')
    return
  }
  auditLogsLoading.value = true
  auditLogsLoaded.value = false
  auditLogs.value = []
  try {
    const res = await getAuditLogs({
      id: tenantId,
      days: auditDays.value,
      domainId,
      mode: 'login',
    })
    if (seq !== auditLogsRequestSeq || domainId !== selectedDomainId.value) return
    auditLogs.value = Array.isArray(res.data) ? res.data : []
    auditLogsLoaded.value = true
  } catch (e: any) {
    if (seq !== auditLogsRequestSeq) return
    auditLogs.value = []
    auditLogsLoaded.value = true
    message.error(e?.message || '获取登录日志失败')
  } finally {
    if (seq === auditLogsRequestSeq) {
      auditLogsLoading.value = false
    }
  }
}

async function loadDomainAuditLogs() {
  if (!selectedDomainId.value) {
    message.warning('请先选择域')
    return
  }
  const seq = ++domainAuditLogsRequestSeq
  const tenantId = domainMgmtTenant.value?.id
  const domainId = selectedDomainId.value
  if (!tenantId) {
    message.warning('请先选择租户')
    return
  }
  domainAuditLogsLoading.value = true
  domainAuditLogsLoaded.value = false
  domainAuditLogs.value = []
  try {
    const res = await getAuditLogs({
      id: tenantId,
      days: domainAuditDays.value,
      domainId,
      mode: 'audit',
    })
    if (seq !== domainAuditLogsRequestSeq || domainId !== selectedDomainId.value) return
    domainAuditLogs.value = Array.isArray(res.data) ? res.data : []
    domainAuditLogsLoaded.value = true
  } catch (e: any) {
    if (seq !== domainAuditLogsRequestSeq) return
    domainAuditLogs.value = []
    domainAuditLogsLoaded.value = true
    message.error(e?.message || '获取审计日志失败')
  } finally {
    if (seq === domainAuditLogsRequestSeq) {
      domainAuditLogsLoading.value = false
    }
  }
}

const filteredIamPolicies = computed(() => {
  if (!iamPolicySearch.value) return iamPoliciesList.value
  const kw = iamPolicySearch.value.toLowerCase()
  return iamPoliciesList.value.filter((p: any) =>
    (p.name || '').toLowerCase().includes(kw) ||
    (p.description || '').toLowerCase().includes(kw),
  )
})

const filteredAnnouncements = computed(() => {
  if (!announcementSearch.value) return announcementsList.value
  const kw = announcementSearch.value.toLowerCase()
  return announcementsList.value.filter((a: any) =>
    (a.summary || '').toLowerCase().includes(kw) ||
    (a.referenceTicketNumber || '').toLowerCase().includes(kw) ||
    (a.announcementType || '').toLowerCase().includes(kw),
  )
})

function shortOcId(id: string | null | undefined): string {
  if (!id) return '—'
  if (id.length <= 22) return id
  return id.slice(0, 12) + '…' + id.slice(-8)
}

function resolveDefaultQuotaRegion(): string {
  const configuredRegion = String(tenantMgmtTenant.value?.ociRegion || '').trim()
  if (configuredRegion) return configuredRegion
  const homeRegion = sortedRegionsList.value.find((r: any) => r?.subscribed && r?.isHomeRegion && r?.regionName)
  const region = String(
    homeRegion?.regionName ||
    regionsData.value?.homeRegionName ||
    quotaRegionOptions.value[0]?.value ||
    '',
  ).trim()
  return region
}

async function ensureQuotaRegionSelected() {
  if (!tenantMgmtTenant.value?.id) return
  const available = quotaRegionOptions.value.map((opt) => opt.value)
  if (!quotaRegion.value || (available.length && !available.includes(quotaRegion.value))) {
    quotaRegion.value = resolveDefaultQuotaRegion()
  }
}

function loadQuotaRegionsAfterQuery() {
  if (!tenantMgmtTenant.value?.id || regionsData.value || regionsLoading.value) return
  void loadRegions(true)
}

async function loadQuotas(force = false) {
  if (!tenantMgmtTenant.value?.id) return
  await ensureQuotaRegionSelected()
  const requestSeq = ++quotaRequestSeq
  const tenantId = tenantMgmtTenant.value.id
  const region = quotaRegion.value || ''
  quotasLoading.value = true
  try {
    const rows = await appQueryCache.fetch(
      ['tenantConfig', 'quotas', tenantId, region],
      async () => {
        const res = await getServiceQuotas({ id: tenantId, region: region || undefined })
        return res.data || []
      },
      { staleMs: TENANT_QUOTA_STALE_MS, force },
    )
    if (requestSeq !== quotaRequestSeq || tenantMgmtTenant.value?.id !== tenantId || (quotaRegion.value || '') !== region) return
    quotasList.value = rows
    const services = new Set(quotasList.value.map((q: any) => String(q?.serviceName || '').trim()).filter(Boolean))
    if (quotaService.value !== '__all' && !services.has(quotaService.value)) {
      quotaService.value = '__all'
    }
    if (!quotasList.value.length) {
      message.info('未获取到配额信息')
    }
  } catch (e: any) {
    if (requestSeq === quotaRequestSeq) {
      message.error(e?.message || '获取配额失败')
    }
  } finally {
    if (requestSeq === quotaRequestSeq) {
      quotasLoading.value = false
      loadQuotaRegionsAfterQuery()
    }
  }
}

async function onQuotaRegionChange() {
  quotasList.value = []
  quotaService.value = '__all'
  await loadQuotas()
}

const filteredQuotas = computed(() => {
  const selectedService = quotaService.value
  const kw = quotaSearch.value.trim().toLowerCase()
  return quotasList.value.filter((q: any) => {
    const serviceName = String(q.serviceName || '')
    if (selectedService !== '__all' && serviceName !== selectedService) return false
    if (!kw) return true
    return serviceName.toLowerCase().includes(kw) ||
      String(q.limitName || '').toLowerCase().includes(kw)
  })
})

const level2Options = computed(() => {
  if (!formState.groupLevel1) return []
  return groupData.value.level2[formState.groupLevel1] || []
})

const batchMoveLevel1Options = computed(() => {
  const set = new Set<string>(groupData.value.level1 || [])
  set.add('未分组')
  return Array.from(set)
})

const batchMoveLevel2Options = computed(() => {
  const g1 = batchMoveG1.value
  if (!g1 || g1 === '未分组') return []
  return groupData.value.level2[g1] || []
})

function filterGroupOption(input: string, option: any) {
  const label = option?.label ?? option?.children ?? option?.value ?? ''
  return String(label).toLowerCase().includes(input.toLowerCase())
}

interface GroupNode {
  label: string
  key: string
  children?: GroupNode[]
  tenants: any[]
}

const groupTree = computed<GroupNode[]>(() => {
  const all = tableData.value
  const gd = groupData.value

  // 从租户数据聚合
  const l1Map = new Map<string, any[]>()
  for (const r of all) {
    const g1 = r.groupLevel1 || '未分组'
    const list = l1Map.get(g1) || []
    list.push(r)
    l1Map.set(g1, list)
  }

  // 合并 groupData 中的空一级分组（确保没有租户的分组也显示）
  for (const g1 of gd.level1) {
    if (!l1Map.has(g1)) l1Map.set(g1, [])
  }

  const nodes: GroupNode[] = []

  // 按后端返回的 level1 顺序构建，未在顺序中的排最后
  const orderedKeys: string[] = []
  for (const g1 of gd.level1) {
    if (l1Map.has(g1) && !orderedKeys.includes(g1)) orderedKeys.push(g1)
  }
  for (const k of l1Map.keys()) {
    if (!orderedKeys.includes(k)) orderedKeys.push(k)
  }

  for (const l1 of orderedKeys) {
    const items = l1Map.get(l1) || []
    const withL2 = items.filter((r: any) => !!r.groupLevel2)
    const withoutL2 = items.filter((r: any) => !r.groupLevel2)

    const l2Map = new Map<string, any[]>()
    for (const r of withL2) {
      const list = l2Map.get(r.groupLevel2) || []
      list.push(r)
      l2Map.set(r.groupLevel2, list)
    }

    // 合并 groupData 中的空二级分组
    const l2Names = gd.level2[l1] || []
    for (const l2 of l2Names) {
      if (!l2Map.has(l2)) l2Map.set(l2, [])
    }

    const children: GroupNode[] = []
    const orderedL2Keys: string[] = []
    for (const l2 of l2Names) {
      if (l2Map.has(l2) && !orderedL2Keys.includes(l2)) orderedL2Keys.push(l2)
    }
    for (const l2 of l2Map.keys()) {
      if (!orderedL2Keys.includes(l2)) orderedL2Keys.push(l2)
    }
    for (const l2 of orderedL2Keys) {
      const l2Items = l2Map.get(l2) || []
      children.push({ label: l2, key: `${l1}/${l2}`, tenants: l2Items })
    }

    nodes.push({
      label: l1,
      key: l1,
      children: children.length > 0 ? children : undefined,
      tenants: withoutL2,
    })
  }

  return nodes
})

function groupTotalCount(group: GroupNode): number {
  return group.tenants.length + (group.children?.reduce((s, c) => s + c.tenants.length, 0) || 0)
}

function getAllGroupTenants(group: GroupNode): any[] {
  const all = [...group.tenants]
  if (group.children) {
    for (const c of group.children) all.push(...c.tenants)
  }
  return all
}

function getPlanCounts(group: GroupNode): Record<string, number> {
  const all = getAllGroupTenants(group)
  const counts: Record<string, number> = {}
  for (const t of all) {
    const key = isPaygPlan(t.planType) ? 'PAYG' : isFreeTierPlan(t.planType) ? 'FREE' : 'UNKNOWN'
    counts[key] = (counts[key] || 0) + 1
  }
  return counts
}

const expandedGroups = ref<Set<string>>(new Set())

/** 一级 + 子分组节点总数（每个分组卡片算 1） */
function countGroupNodesIncludingSubs(tree: GroupNode[]): number {
  let n = 0
  for (const g of tree) {
    n += 1
    if (g.children?.length) n += g.children.length
  }
  return n
}

/**
 * 规则：配置数 >10 或 分组数（含子分组）>3 时默认全部收起；否则默认展开所有分组。
 * 新增/编辑保存后若设置了 pendingExpandTarget，仅展开该一级（及可选二级），不受上述规则影响。
 */
function applyDefaultExpandAfterLoad() {
  if (pendingExpandTarget) {
    const l1 = (pendingExpandTarget.groupLevel1 || '').trim() || '未分组'
    const next = new Set<string>([l1])
    const l2 = (pendingExpandTarget.groupLevel2 || '').trim()
    if (l2) next.add(`${l1}/${l2}`)
    expandedGroups.value = next
    pendingExpandTarget = null
    return
  }
  const tenantCount = tableData.value.length
  const groupCount = countGroupNodesIncludingSubs(groupTree.value)
  if (tenantCount > 10 || groupCount > 3) {
    expandedGroups.value = new Set()
    return
  }
  expandAllGroupsFromTree()
}

function expandAllGroupsFromTree() {
  const next = new Set<string>()
  for (const g of groupTree.value) {
    next.add(g.key)
    if (g.children) {
      for (const c of g.children) next.add(c.key)
    }
  }
  expandedGroups.value = next
}

const tenantExpandableKeys = computed(() => collectGroupExpandKeys(groupTree.value))

const allGroupsExpanded = computed(() =>
  isAllGroupsExpanded(expandedGroups.value, tenantExpandableKeys.value),
)

function toggleAllGroups() {
  if (isAllGroupsExpanded(expandedGroups.value, tenantExpandableKeys.value)) {
    expandedGroups.value = new Set()
  } else {
    expandAllGroupsFromTree()
  }
}

function scrollTenantPageTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

const dragFromIndex = ref(-1)
const dragOverIndex = ref(-1)
const dragOverPos = ref<'top' | 'bottom'>('top')
const localOrder = ref<string[]>([])
const subDragParent = ref('')
const subDragFromIndex = ref(-1)
const subDragOverIndex = ref(-1)
const subDragOverPos = ref<'top' | 'bottom'>('top')
const localSubOrders = ref<Record<string, string[]>>({})

const displayGroups = computed(() => {
  const source = localOrder.value.length === 0 ? groupTree.value : (() => {
    const map = new Map<string, any>()
    for (const g of groupTree.value) map.set(g.label, g)
    const result: any[] = []
    for (const name of localOrder.value) {
      const g = map.get(name)
      if (g) { result.push(g); map.delete(name) }
    }
    for (const g of map.values()) result.push(g)
    return result
  })()
  if (!Object.keys(localSubOrders.value).length) return source
  return source.map((g: any) => {
    const order = localSubOrders.value[g.label]
    if (!order?.length || !g.children?.length) return g
    const map = new Map<string, any>()
    for (const c of g.children) map.set(c.label, c)
    const children: any[] = []
    for (const name of order) {
      const child = map.get(name)
      if (child) { children.push(child); map.delete(name) }
    }
    for (const child of map.values()) children.push(child)
    return { ...g, children }
  })
})

const renderedGroupChildren = (parent: string) => {
  const group = displayGroups.value.find((g: any) => g.label === parent)
  return group?.children || []
}

function onSubDragStart(e: DragEvent, parent: string, idx: number) {
  subDragParent.value = parent
  subDragFromIndex.value = idx
  const map = new Map<string, any>()
  for (const child of renderedGroupChildren(parent)) map.set(child.label, child)
  localSubOrders.value = {
    ...localSubOrders.value,
    [parent]: Array.from(map.keys()),
  }
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', `${parent}:${idx}`)
    e.dataTransfer.setDragImage(e.target as HTMLElement, 0, 0)
  }
}

function onSubDragOver(e: DragEvent, parent: string, idx: number) {
  if (subDragFromIndex.value < 0 || subDragParent.value !== parent) return
  e.preventDefault()
  e.stopPropagation()
  const target = e.currentTarget as HTMLElement
  const rect = target.getBoundingClientRect()
  const mid = rect.top + rect.height / 2
  subDragOverPos.value = e.clientY < mid ? 'top' : 'bottom'
  subDragOverIndex.value = idx
}

function onSubDrop(e: DragEvent, parent: string, toIdx: number) {
  const fromIdx = subDragFromIndex.value
  if (fromIdx < 0 || subDragParent.value !== parent) return
  e.preventDefault()
  e.stopPropagation()
  if (fromIdx === toIdx) {
    resetSubDrag()
    return
  }

  const names = [...(localSubOrders.value[parent] || renderedGroupChildren(parent).map((c: any) => c.label))]
  const [moved] = names.splice(fromIdx, 1)
  let insertIdx = subDragOverPos.value === 'bottom' ? toIdx + 1 : toIdx
  if (fromIdx < insertIdx) insertIdx -= 1
  if (insertIdx < 0) insertIdx = 0
  if (insertIdx > names.length) insertIdx = names.length
  names.splice(insertIdx, 0, moved)
  localSubOrders.value = { ...localSubOrders.value, [parent]: names }

  resetSubDrag()
  saveGroupOrder({ parent, order: names }).then(() => invalidateCatalogAndReload()).catch(() => {})
}

function onSubDragEnd() {
  resetSubDrag()
}

function resetSubDrag() {
  subDragParent.value = ''
  subDragFromIndex.value = -1
  subDragOverIndex.value = -1
}

function onDragStart(e: DragEvent, idx: number) {
  dragFromIndex.value = idx
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', String(idx))
    e.dataTransfer.setDragImage(e.target as HTMLElement, 0, 0)
  }
  localOrder.value = displayGroups.value.map(g => g.label)
}

function onDragOver(e: DragEvent, idx: number) {
  if (dragFromIndex.value < 0) return
  const target = (e.currentTarget as HTMLElement)
  const rect = target.getBoundingClientRect()
  const mid = rect.top + rect.height / 2
  dragOverPos.value = e.clientY < mid ? 'top' : 'bottom'
  dragOverIndex.value = idx
}

function onDrop(_e: DragEvent, toIdx: number) {
  const fromIdx = dragFromIndex.value
  if (fromIdx < 0 || fromIdx === toIdx) { resetDrag(); return }

  const names = [...localOrder.value]
  const [moved] = names.splice(fromIdx, 1)
  let insertIdx = dragOverPos.value === 'bottom' ? toIdx + 1 : toIdx
  if (fromIdx < insertIdx) insertIdx -= 1
  if (insertIdx < 0) insertIdx = 0
  if (insertIdx > names.length) insertIdx = names.length
  names.splice(insertIdx, 0, moved)
  localOrder.value = names

  resetDrag()
  saveGroupOrder({ order: names }).then(() => invalidateCatalogAndReload()).catch(() => {})
}

function onDragEnd() {
  resetDrag()
}

function resetDrag() {
  dragFromIndex.value = -1
  dragOverIndex.value = -1
}

function toggleGroup(key: string) {
  const s = new Set(expandedGroups.value)
  if (s.has(key)) s.delete(key)
  else s.add(key)
  expandedGroups.value = s
}

async function openTenantMgmt(record: any) {
  tenantMgmtTenant.value = record
  tenantTab.value = 'account'
  tenantMgmtVisible.value = true
  iamPoliciesList.value = []
  iamPoliciesCompartmentId.value = ''
  iamPolicyStatements.value = {}
  iamExpandedRowKeys.value = []
  announcementsList.value = []
  announcementsRetentionNote.value = ''
  announcementDrawerVisible.value = false
  quotasList.value = []
  quotaSearch.value = ''
  quotaRegion.value = ''
  quotaService.value = '__all'
  billingData.value = null
  budgetsData.value = null
  selectedBudgetId.value = ''
  regionsData.value = null
  regionSearch.value = ''
  regionSubscribeVerifyVisible.value = false
  regionSubscribeTarget.value = null
  regionSubscribeCode.value = ''
  budgetCompartmentsData.value = null
  budgetCompartmentsLoadedTenantId.value = ''
  budgetFormVisible.value = false
  budgetAlertFormVisible.value = false
  await loadTenantAccountInfo(record)
}

function onTenantTabChange(key: string) {
  if (key === 'budgets') {
    if (!budgetsData.value && !budgetsLoading.value) loadBudgets()
    if (!budgetCompartmentsData.value && !budgetCompartmentsLoading.value) loadBudgetCompartments()
  }
  if (key === 'regions' && !regionsData.value && !regionsLoading.value) loadRegions()
  if (key === 'billing' && !billingData.value) loadTenantBilling()
  if (key === 'quotas' && !quotasList.value.length && !quotasLoading.value) loadQuotas()
  if (key === 'iam' && !iamPoliciesList.value.length) loadIamPolicies()
  if (key === 'announcements' && !announcementsList.value.length) loadAnnouncements()
}

async function loadTenantAccountInfo(record: any, options: { preserve?: boolean; force?: boolean } = {}) {
  const requestSeq = ++tenantInfoRequestSeq
  const tenantId = record.id
  if (!options.preserve) {
    tenantInfoData.value = { configName: record.username, id: tenantId }
  }
  tenantInfoLoading.value = true
  try {
    const res = await appQueryCache.fetch(
      ['tenantConfig', 'tenantInfo', tenantId],
      () => getTenantFullInfo({ id: tenantId }),
      { staleMs: TENANT_INFO_STALE_MS, force: options.force === true },
    )
    if (requestSeq !== tenantInfoRequestSeq || tenantMgmtTenant.value?.id !== tenantId) return
    const d = res.data || {}
    tenantInfoData.value = { ...d, id: tenantId }
    if (record?.id) {
      const row = tableData.value.find((r: any) => r.id === tenantId)
      if (row) {
        if (d.planType) row.planType = d.planType
        if (d.tenantName) row.tenantName = d.tenantName
      }
    }
  } catch (e: any) {
    if (requestSeq === tenantInfoRequestSeq) {
      message.error(e?.message || '获取账户信息失败')
    }
  } finally {
    if (requestSeq === tenantInfoRequestSeq) {
      tenantInfoLoading.value = false
    }
  }
}

async function handleRefreshTenantAccountInfo() {
  if (!tenantMgmtTenant.value?.id || tenantInfoLoading.value) return
  appQueryCache.invalidate(['tenantConfig', 'tenantInfo', tenantMgmtTenant.value.id])
  await loadTenantAccountInfo(tenantMgmtTenant.value, { preserve: true, force: true })
}

async function loadTenantBilling() {
  const tenantId = tenantInfoData.value?.id || tenantMgmtTenant.value?.id
  if (!tenantId) return
  billingLoading.value = true
  try {
    const bill = await getTenantBillingSummary({
      id: tenantId,
      limits: { invoices: 5, payments: 5, usageStatements: 3, costDays: billingCostDays.value },
    })
    billingData.value = bill.data || null
  } catch (e: any) {
    message.error(e?.message || '获取账务信息失败')
  } finally {
    billingLoading.value = false
  }
}

async function reloadBillingCost() {
  const tenantId = tenantInfoData.value?.id || tenantMgmtTenant.value?.id
  if (!tenantId) return
  billingLoading.value = true
  try {
    const bill = await getTenantBillingSummary({
      id: tenantId,
      limits: { invoices: 5, costDays: billingCostDays.value },
    })
    const data = bill.data || {}
    if (billingData.value) {
      billingData.value = {
        ...billingData.value,
        usage: data.usage,
        links: { ...billingData.value.links, ...data.links },
      }
    } else {
      billingData.value = data
    }
  } catch (e: any) {
    message.error(e?.message || '查询成本失败')
  } finally {
    billingLoading.value = false
  }
}

function currentTenantMgmtId(): string {
  return tenantInfoData.value?.id || tenantMgmtTenant.value?.id || ''
}

function tenantRootCompartmentId(): string {
  return tenantInfoData.value?.tenantId
    || tenantMgmtTenant.value?.ociTenantId
    || tenantMgmtTenant.value?.tenantId
    || ''
}

function tenantRootCompartmentDisplay(): string {
  const name = tenantInfoData.value?.tenantName
    || tenantMgmtTenant.value?.tenantName
    || tenantMgmtTenant.value?.username
    || 'root'
  return `${name}（根）`
}

function normalizeBudgetCompartmentLabel(label: any): string {
  return String(label || '').replace(/\s*\(root\)/g, '（根）')
}

function budgetCompartmentDepth(item: any): number {
  const label = normalizeBudgetCompartmentLabel(item?.pathLabel || item?.name)
  if (!label) return 0
  return Math.max(0, label.split('/').length - 1)
}

function budgetCompartmentOptionLabel(item: any): string {
  if (item?.root) return normalizeBudgetCompartmentLabel(item?.pathLabel || item?.name) || tenantRootCompartmentDisplay()
  const depth = budgetCompartmentDepth(item)
  const name = String(item?.name || item?.pathLabel || '').trim()
  return `${'　'.repeat(Math.max(1, depth))}${name}`
}

function budgetCompartmentSearchLabel(item: any): string {
  return normalizeBudgetCompartmentLabel(item?.pathLabel || item?.name)
}

function budgetCompartmentItems(): any[] {
  const items = budgetCompartmentsData.value?.items
  return Array.isArray(items) ? items : []
}

function findBudgetCompartment(compartmentId: string | null | undefined): any | null {
  const id = (compartmentId || '').trim()
  if (!id) return null
  return budgetCompartmentItems().find((c: any) => c?.id === id) || null
}

function formatBudgetCompartmentDisplay(compartmentId: string | null | undefined): string {
  const id = (compartmentId || '').trim()
  if (!id) return tenantRootCompartmentDisplay()
  const known = findBudgetCompartment(id)
  if (known) return known.root
    ? (normalizeBudgetCompartmentLabel(known.pathLabel || known.name) || tenantRootCompartmentDisplay())
    : String(known.name || shortOcId(id))
  return id === tenantRootCompartmentId() ? tenantRootCompartmentDisplay() : shortOcId(id)
}

function buildBudgetCompartmentOptions(currentId: string | null | undefined) {
  const rootId = tenantRootCompartmentId()
  const seen = new Set<string>()
  const options: Array<{ label: string; value: string; title?: string; searchLabel?: string }> = []

  for (const item of budgetCompartmentItems()) {
    const id = String(item?.id || '').trim()
    if (!id || seen.has(id)) continue
    const label = budgetCompartmentOptionLabel(item)
    options.push({ label, value: id, title: id, searchLabel: `${budgetCompartmentSearchLabel(item)} ${id}` })
    seen.add(id)
  }

  if (rootId && !seen.has(rootId)) {
    options.unshift({ label: tenantRootCompartmentDisplay(), value: rootId, title: rootId, searchLabel: `${tenantRootCompartmentDisplay()} ${rootId}` })
    seen.add(rootId)
  }

  const cur = (currentId || '').trim()
  if (cur && !seen.has(cur)) {
    const label = formatBudgetCompartmentDisplay(cur)
    options.push({ label, value: cur, title: cur, searchLabel: `${label} ${cur}` })
  }
  return options
}

function filterBudgetCompartmentOption(input: string, option: any) {
  const text = `${option?.label || ''} ${option?.searchLabel || ''} ${option?.value || ''}`.toLowerCase()
  return text.includes(input.toLowerCase())
}

function budgetCurrencyCode(): string {
  return tenantInfoData.value?.currencyCode || billingData.value?.currencyCode || ''
}

function toBudgetNumber(v: any): number | null {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

function formatBudgetNumber(v: any): string {
  const n = toBudgetNumber(v)
  if (n === null) return '—'
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(n)
}

function formatBudgetMoneyValue(v: any, includeCurrency = true): string {
  const n = formatBudgetNumber(v)
  if (n === '—') return n
  const currency = includeCurrency ? budgetCurrencyCode() : ''
  return currency ? `${n} ${currency}` : n
}

function normalizeBudgetTargetType(v: any): BudgetTargetType {
  return String(v || '').trim().toUpperCase() === 'TAG' ? 'TAG' : 'COMPARTMENT'
}

function normalizeBudgetProcessingPeriodType(v: any): BudgetProcessingPeriodType {
  const s = String(v || '').trim().toUpperCase().replace('-', '_')
  if (s === 'INVOICE') return 'INVOICE'
  if (s === 'SINGLE_USE') return 'SINGLE_USE'
  return 'MONTH'
}

function normalizeBudgetAlertType(v: any): BudgetAlertType {
  return String(v || '').trim().toUpperCase() === 'FORECAST' ? 'FORECAST' : 'ACTUAL'
}

function normalizeBudgetThresholdType(v: any): BudgetThresholdType {
  return String(v || '').trim().toUpperCase() === 'ABSOLUTE' ? 'ABSOLUTE' : 'PERCENTAGE'
}

function firstBudgetTarget(record: any): string {
  const targets = Array.isArray(record?.targets) ? record.targets.filter(Boolean) : []
  return targets[0] || record?.targetCompartmentId || record?.compartmentId || ''
}

function formatBudgetTarget(record: any): string {
  const targetType = normalizeBudgetTargetType(record?.targetType)
  const target = firstBudgetTarget(record)
  if (targetType === 'TAG') return target ? `标签 ${target}` : '标签'
  return target ? `区间 ${formatBudgetCompartmentDisplay(target)}` : '区间'
}

function formatBudgetTargetTooltip(record: any): string {
  const targetType = normalizeBudgetTargetType(record?.targetType)
  const target = firstBudgetTarget(record)
  if (!target) return formatBudgetTarget(record)
  if (targetType === 'TAG') return `标签 ${target}`
  const known = findBudgetCompartment(target)
  const label = known ? budgetCompartmentSearchLabel(known) : formatBudgetCompartmentDisplay(target)
  return `区间 ${label}\n${target}`
}

function formatBudgetAmount(record: any): string {
  return formatBudgetMoneyValue(record?.amount)
}

function formatBudgetSpend(value: any, amount: any): string {
  const spent = formatBudgetNumber(value)
  const total = formatBudgetNumber(amount)
  if (spent === '—' && total === '—') return '—'
  const currency = budgetCurrencyCode()
  return `${spent} / ${total}${currency ? ` ${currency}` : ''}`
}

function budgetActualPercent(record: any): number {
  const direct = toBudgetNumber(record?.actualPercent)
  if (direct !== null) return direct
  const actual = toBudgetNumber(record?.actualSpend)
  const amount = toBudgetNumber(record?.amount)
  if (actual === null || !amount || amount <= 0) return 0
  return (actual / amount) * 100
}

function budgetProgressPercent(record: any): number {
  const percent = budgetActualPercent(record)
  return Math.max(0, Math.min(100, Number(percent.toFixed(1))))
}

function budgetProgressStatus(record: any) {
  const percent = budgetActualPercent(record)
  if (percent >= 100) return 'exception'
  if (percent >= 80) return 'active'
  return 'normal'
}

function formatBudgetProcessingPeriod(v: any): string {
  const s = normalizeBudgetProcessingPeriodType(v)
  const map: Record<BudgetProcessingPeriodType, string> = {
    MONTH: '每月',
    INVOICE: '发票周期',
    SINGLE_USE: '一次性',
  }
  return map[s]
}

function formatBudgetAlertType(v: any): string {
  return normalizeBudgetAlertType(v) === 'FORECAST' ? '预测支出' : '实际支出'
}

function formatBudgetAlertThreshold(record: any): string {
  const thresholdType = normalizeBudgetThresholdType(record?.thresholdType)
  if (thresholdType === 'PERCENTAGE') return `${formatBudgetNumber(record?.threshold)}%`
  return formatBudgetMoneyValue(record?.threshold)
}

function formatBudgetDateInput(v: any): string {
  if (!v) return ''
  const d = dayjs.utc(v)
  if (d.isValid()) return d.format('YYYY-MM-DD')
  return String(v).slice(0, 10)
}

function budgetDataBase() {
  return budgetsData.value && typeof budgetsData.value === 'object' ? budgetsData.value : { items: [] }
}

function upsertBudgetRow(row: any) {
  if (!row?.id) return
  const items = budgetsList.value.slice()
  const idx = items.findIndex((b: any) => b.id === row.id)
  if (idx >= 0) {
    const prev = items[idx]
    const next = { ...prev, ...row }
    if (!Array.isArray(row.alertRules) && Array.isArray(prev.alertRules)) {
      next.alertRules = prev.alertRules
    }
    items.splice(idx, 1, next)
  } else {
    items.unshift(row)
  }
  budgetsData.value = { ...budgetDataBase(), items }
}

function removeBudgetRow(budgetId: string) {
  const items = budgetsList.value.filter((b: any) => b.id !== budgetId)
  budgetsData.value = { ...budgetDataBase(), items }
  if (selectedBudgetId.value === budgetId) {
    selectedBudgetId.value = items[0]?.id || ''
  }
}

function setBudgetAlertRules(budgetId: string, rules: any[]) {
  const items = budgetsList.value.map((b: any) => (
    b.id === budgetId ? { ...b, alertRules: rules, alertRuleCount: rules.length } : b
  ))
  budgetsData.value = { ...budgetDataBase(), items }
}

function upsertBudgetAlertRuleRow(rule: any) {
  const budgetId = rule?.budgetId || budgetAlertForm.budgetId || selectedBudget.value?.id
  if (!budgetId || !rule?.id) return
  const budget = budgetsList.value.find((b: any) => b.id === budgetId)
  const rules = Array.isArray(budget?.alertRules) ? budget.alertRules.slice() : []
  const idx = rules.findIndex((r: any) => r.id === rule.id)
  if (idx >= 0) rules.splice(idx, 1, { ...rules[idx], ...rule })
  else rules.unshift(rule)
  setBudgetAlertRules(budgetId, rules)
}

async function loadBudgetCompartments(force = false) {
  const tenantId = currentTenantMgmtId()
  if (!tenantId) return
  if (!force && budgetCompartmentsLoadedTenantId.value === tenantId && budgetCompartmentsData.value) return
  budgetCompartmentsLoading.value = true
  try {
    const res = await listCompartmentPicker({ id: tenantId })
    const data = res.data || {}
    const items = Array.isArray(data.items) ? data.items : []
    budgetCompartmentsData.value = { ...data, items }
    budgetCompartmentsLoadedTenantId.value = tenantId
  } catch (e: any) {
    budgetCompartmentsData.value = null
    budgetCompartmentsLoadedTenantId.value = ''
    message.error(e?.message || '获取区间列表失败')
  } finally {
    budgetCompartmentsLoading.value = false
  }
}

async function loadBudgets() {
  const tenantId = currentTenantMgmtId()
  if (!tenantId) return
  budgetsLoading.value = true
  try {
    const res = await listBudgets({ id: tenantId })
    const data = res.data || {}
    const items = Array.isArray(data.items) ? data.items : []
    budgetsData.value = { ...data, items }
    if (items.length) {
      const exists = selectedBudgetId.value && items.some((b: any) => b.id === selectedBudgetId.value)
      selectedBudgetId.value = exists ? selectedBudgetId.value : items[0].id
    } else {
      selectedBudgetId.value = ''
    }
  } catch (e: any) {
    message.error(e?.message || '获取成本预算失败')
  } finally {
    budgetsLoading.value = false
  }
}

function selectBudget(record: any) {
  if (!record?.id) return
  selectedBudgetId.value = record.id
  if (!Array.isArray(record.alertRules) || record.alertRulesError) {
    void reloadSelectedBudgetAlertRules()
  }
}

function budgetRowClassName(record: any) {
  return record?.id === selectedBudgetId.value ? 'budget-row-selected' : ''
}

function budgetTableRow(record: any) {
  return { onClick: () => selectBudget(record) }
}

async function reloadSelectedBudgetAlertRules() {
  const tenantId = currentTenantMgmtId()
  const budgetId = selectedBudget.value?.id || selectedBudgetId.value
  if (!tenantId || !budgetId) return
  selectedBudgetId.value = budgetId
  budgetAlertRulesLoading.value = true
  try {
    const res = await listBudgetAlertRules({ id: tenantId, budgetId })
    const data = res.data
    const rules = Array.isArray(data) ? data : Array.isArray(data?.items) ? data.items : []
    setBudgetAlertRules(budgetId, rules)
  } catch (e: any) {
    message.error(e?.message || '获取预算告警规则失败')
  } finally {
    budgetAlertRulesLoading.value = false
  }
}

function onBudgetTargetTypeChange(value: BudgetTargetType) {
  budgetForm.target = value === 'COMPARTMENT' ? tenantRootCompartmentId() : ''
}

function openCreateBudget() {
  void loadBudgetCompartments()
  const rootCompartmentId = tenantRootCompartmentId()
  Object.assign(budgetForm, {
    budgetId: '',
    displayName: '',
    description: '',
    amount: 100,
    compartmentId: rootCompartmentId,
    targetType: 'COMPARTMENT' as BudgetTargetType,
    target: rootCompartmentId,
    resetPeriod: 'MONTHLY',
    processingPeriodType: 'MONTH' as BudgetProcessingPeriodType,
    budgetProcessingPeriodStartOffset: 1,
    startDate: '',
    endDate: '',
  })
  budgetFormMode.value = 'create'
  budgetFormVisible.value = true
}

function openEditBudget(record: any) {
  void loadBudgetCompartments()
  Object.assign(budgetForm, {
    budgetId: record?.id || '',
    displayName: record?.displayName || '',
    description: record?.description || '',
    amount: toBudgetNumber(record?.amount) ?? 100,
    compartmentId: record?.compartmentId || tenantRootCompartmentId(),
    targetType: normalizeBudgetTargetType(record?.targetType),
    target: firstBudgetTarget(record),
    resetPeriod: 'MONTHLY',
    processingPeriodType: normalizeBudgetProcessingPeriodType(record?.processingPeriodType),
    budgetProcessingPeriodStartOffset: record?.budgetProcessingPeriodStartOffset ?? null,
    startDate: formatBudgetDateInput(record?.startDate),
    endDate: formatBudgetDateInput(record?.endDate),
  })
  budgetFormMode.value = 'edit'
  budgetFormVisible.value = true
}

async function submitBudgetForm() {
  const tenantId = currentTenantMgmtId()
  if (!tenantId) return
  const displayName = budgetForm.displayName.trim()
  const amount = toBudgetNumber(budgetForm.amount)
  const targetType = normalizeBudgetTargetType(budgetForm.targetType)
  const processingPeriodType = normalizeBudgetProcessingPeriodType(budgetForm.processingPeriodType)
  const target = budgetForm.target.trim()
  const compartmentId = budgetForm.compartmentId.trim() || tenantRootCompartmentId()
  if (!displayName) { message.warning('请填写预算名称'); return }
  if (!amount || amount <= 0) { message.warning('预算金额必须大于 0'); return }
  if (budgetFormMode.value === 'create' && !compartmentId) { message.warning('请填写预算所在区间 OCID'); return }
  if (budgetFormMode.value === 'create' && targetType === 'TAG' && !target) { message.warning('请填写预算标签目标'); return }
  if (processingPeriodType === 'SINGLE_USE' && (!budgetForm.startDate || !budgetForm.endDate)) {
    message.warning('一次性预算需要填写开始和结束日期')
    return
  }

  const payload: any = {
    id: tenantId,
    displayName,
    description: budgetForm.description.trim(),
    amount,
    resetPeriod: 'MONTHLY',
    processingPeriodType,
    budgetProcessingPeriodStartOffset: budgetForm.budgetProcessingPeriodStartOffset,
  }
  if (processingPeriodType === 'SINGLE_USE') {
    payload.startDate = budgetForm.startDate
    payload.endDate = budgetForm.endDate
  }
  if (budgetFormMode.value === 'create') {
    payload.compartmentId = compartmentId
    payload.targetType = targetType
    payload.target = target || (targetType === 'COMPARTMENT' ? tenantRootCompartmentId() : '')
  } else {
    payload.budgetId = budgetForm.budgetId
  }

  budgetFormLoading.value = true
  try {
    const res = budgetFormMode.value === 'create'
      ? await createBudget(payload)
      : await updateBudget(payload)
    const row = res.data
    if (row?.id) {
      upsertBudgetRow(row)
      selectedBudgetId.value = row.id
    } else {
      await loadBudgets()
    }
    budgetFormVisible.value = false
    message.success(budgetFormMode.value === 'create' ? '成本预算已创建' : '成本预算已更新')
  } catch (e: any) {
    message.error(e?.message || '保存成本预算失败')
  } finally {
    budgetFormLoading.value = false
  }
}

async function handleDeleteBudget(record: any) {
  const tenantId = currentTenantMgmtId()
  const budgetId = record?.id
  if (!tenantId || !budgetId) return
  try {
    await deleteBudget({ id: tenantId, budgetId })
    removeBudgetRow(budgetId)
    message.success('成本预算已删除')
  } catch (e: any) {
    message.error(e?.message || '删除成本预算失败')
  }
}

function openCreateBudgetAlertRule(budget: any) {
  if (!budget?.id) return
  Object.assign(budgetAlertForm, {
    budgetId: budget.id,
    alertRuleId: '',
    displayName: '',
    description: '',
    type: 'ACTUAL' as BudgetAlertType,
    threshold: 80,
    thresholdType: 'PERCENTAGE' as BudgetThresholdType,
    recipients: '',
    message: '',
  })
  budgetAlertFormMode.value = 'create'
  budgetAlertFormVisible.value = true
}

function openEditBudgetAlertRule(record: any) {
  Object.assign(budgetAlertForm, {
    budgetId: record?.budgetId || selectedBudget.value?.id || '',
    alertRuleId: record?.id || '',
    displayName: record?.displayName || '',
    description: record?.description || '',
    type: normalizeBudgetAlertType(record?.type),
    threshold: toBudgetNumber(record?.threshold) ?? 80,
    thresholdType: normalizeBudgetThresholdType(record?.thresholdType),
    recipients: record?.recipients || '',
    message: record?.message || '',
  })
  budgetAlertFormMode.value = 'edit'
  budgetAlertFormVisible.value = true
}

async function submitBudgetAlertForm() {
  const tenantId = currentTenantMgmtId()
  const displayName = budgetAlertForm.displayName.trim()
  const threshold = toBudgetNumber(budgetAlertForm.threshold)
  const recipients = budgetAlertForm.recipients.trim()
  if (!tenantId || !budgetAlertForm.budgetId) return
  if (!displayName) { message.warning('请填写告警名称'); return }
  if (!threshold || threshold <= 0) { message.warning('告警阈值必须大于 0'); return }
  if (!recipients) { message.warning('请填写告警接收人'); return }

  const payload: any = {
    id: tenantId,
    budgetId: budgetAlertForm.budgetId,
    displayName,
    description: budgetAlertForm.description.trim(),
    type: normalizeBudgetAlertType(budgetAlertForm.type),
    threshold,
    thresholdType: normalizeBudgetThresholdType(budgetAlertForm.thresholdType),
    recipients,
    message: budgetAlertForm.message.trim(),
  }
  if (budgetAlertFormMode.value === 'edit') payload.alertRuleId = budgetAlertForm.alertRuleId

  budgetAlertFormLoading.value = true
  try {
    const res = budgetAlertFormMode.value === 'create'
      ? await createBudgetAlertRule(payload)
      : await updateBudgetAlertRule(payload)
    const rule = res.data
    if (rule?.id) upsertBudgetAlertRuleRow(rule)
    else await reloadSelectedBudgetAlertRules()
    budgetAlertFormVisible.value = false
    message.success(budgetAlertFormMode.value === 'create' ? '预算告警已创建' : '预算告警已更新')
  } catch (e: any) {
    message.error(e?.message || '保存预算告警失败')
  } finally {
    budgetAlertFormLoading.value = false
  }
}

async function handleDeleteBudgetAlertRule(record: any) {
  const tenantId = currentTenantMgmtId()
  const budgetId = record?.budgetId || selectedBudget.value?.id
  const alertRuleId = record?.id
  if (!tenantId || !budgetId || !alertRuleId) return
  try {
    await deleteBudgetAlertRule({ id: tenantId, budgetId, alertRuleId })
    const budget = budgetsList.value.find((b: any) => b.id === budgetId)
    const rules = (Array.isArray(budget?.alertRules) ? budget.alertRules : [])
      .filter((r: any) => r.id !== alertRuleId)
    setBudgetAlertRules(budgetId, rules)
    message.success('预算告警已删除')
  } catch (e: any) {
    message.error(e?.message || '删除预算告警失败')
  }
}

function formatRegionDisplay(record: any): string {
  const regionName = String(record?.regionName || '').trim()
  const label = regionName ? getOciRegionDisplayName(regionName) : ''
  return label || regionName || record?.regionKey || '—'
}

function formatRegionStatus(status: string | null | undefined): string {
  const s = String(status || '').toUpperCase()
  if (s === 'READY') return '订阅'
  if (s === 'IN_PROGRESS') return '处理中'
  if (s === 'NOT_SUBSCRIBED') return '未订阅'
  return status || '未知'
}

function regionStatusRank(status: string | null | undefined): number {
  const s = String(status || '').toUpperCase()
  if (s === 'READY') return 0
  if (s === 'IN_PROGRESS') return 1
  if (s === 'NOT_SUBSCRIBED') return 2
  return 3
}

function sortTenantRegions(list: any[]): any[] {
  return list
    .map((item, index) => ({ item, index }))
    .sort((a, b) => {
      const homeDiff = Number(Boolean(b.item?.isHomeRegion)) - Number(Boolean(a.item?.isHomeRegion))
      if (homeDiff !== 0) return homeDiff
      const rankDiff = regionStatusRank(a.item?.status) - regionStatusRank(b.item?.status)
      if (rankDiff !== 0) return rankDiff
      return a.index - b.index
    })
    .map(entry => entry.item)
}

function regionStatusColor(status: string | null | undefined): string {
  const s = String(status || '').toUpperCase()
  if (s === 'READY') return 'green'
  if (s === 'IN_PROGRESS') return 'processing'
  if (s === 'NOT_SUBSCRIBED') return 'default'
  return 'default'
}

async function loadRegions(silent = false, force = false) {
  const tenantId = currentTenantMgmtId()
  if (!tenantId) return
  const requestSeq = ++regionsRequestSeq
  regionsLoading.value = true
  try {
    const data = await appQueryCache.fetch(
      ['tenantConfig', 'regions', tenantId],
      async () => {
        const res = await listTenantRegions({ id: tenantId })
        return res.data || {}
      },
      { staleMs: TENANT_REGION_STALE_MS, force },
    )
    if (requestSeq !== regionsRequestSeq || currentTenantMgmtId() !== tenantId) return
    regionsData.value = { ...data, items: Array.isArray(data.items) ? data.items : [] }
    if (!silent && !regionsList.value.length) {
      message.info('未找到区域数据（或当前 API 用户无区域订阅读权限）')
    }
  } catch (e: any) {
    if (!silent && requestSeq === regionsRequestSeq) {
      message.error(e?.message || '获取区域列表失败')
    }
  } finally {
    if (requestSeq === regionsRequestSeq) {
      regionsLoading.value = false
    }
  }
}

function confirmSubscribeRegion(record: any) {
  if (!record?.regionKey || !record.canSubscribe) return
  Modal.confirm({
    title: '注意！订阅成功后无法取消订阅区域。',
    content: `${formatRegionDisplay(record)}（${record.regionName || record.regionKey}）`,
    okText: '继续',
    cancelText: '取消',
    async onOk() {
      await openRegionSubscribeVerify(record)
    },
  })
}

async function openRegionSubscribeVerify(record: any) {
  regionSubscribeTarget.value = record
  regionSubscribeCode.value = ''
  regionSubscribeSendingKey.value = record.regionKey || ''
  try {
    await sendVerifyCode('subscribeRegion')
    message.success('验证码已发送至 Telegram')
    regionSubscribeVerifyVisible.value = true
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
    throw e
  } finally {
    regionSubscribeSendingKey.value = ''
  }
}

async function resendRegionSubscribeCode() {
  if (!regionSubscribeTarget.value?.regionKey) return
  regionSubscribeCodeSending.value = true
  try {
    await sendVerifyCode('subscribeRegion')
    regionSubscribeCode.value = ''
    message.success('验证码已重新发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    regionSubscribeCodeSending.value = false
  }
}

async function submitRegionSubscribe() {
  const tenantId = currentTenantMgmtId()
  const regionKey = regionSubscribeTarget.value?.regionKey
  if (!tenantId || !regionKey) return
  if (!regionSubscribeCode.value || regionSubscribeCode.value.length !== 6) {
    message.warning('请输入 6 位验证码')
    return
  }

  regionSubscribeLoading.value = true
  try {
    await subscribeTenantRegion({
      id: tenantId,
      regionKey,
      verifyCode: regionSubscribeCode.value,
    })
    message.success('区域订阅已提交，激活可能需要几分钟')
    regionSubscribeVerifyVisible.value = false
    regionSubscribeCode.value = ''
    appQueryCache.invalidate(['tenantConfig', 'regions', tenantId])
    await loadRegions(false, true)
    if (tenantMgmtTenant.value) {
      await loadTenantAccountInfo(tenantMgmtTenant.value, { force: true })
    }
  } catch (e: any) {
    message.error(e?.message || '订阅区域失败')
  } finally {
    regionSubscribeLoading.value = false
  }
}

async function loadIamPolicies() {
  if (!tenantMgmtTenant.value?.id) return
  iamPoliciesLoading.value = true
  iamPolicyStatements.value = {}
  iamExpandedRowKeys.value = []
  try {
    const res = await listIamPolicies({ id: tenantMgmtTenant.value.id })
    const data = res.data || {}
    iamPoliciesList.value = data.items || []
    iamPoliciesCompartmentId.value = data.compartmentId || ''
    if (!iamPoliciesList.value.length) {
      message.info('未找到 IAM 策略（或当前 API 用户无 inspect policies 权限）')
    }
  } catch (e: any) {
    message.error(e?.message || '获取 IAM 策略失败')
  } finally {
    iamPoliciesLoading.value = false
  }
}

async function onIamExpand(expanded: boolean, record: any) {
  if (!expanded || !record?.id || !tenantMgmtTenant.value?.id) return
  if (iamPolicyStatements.value[record.id]?.length) return
  iamPolicyDetailLoading.value = record.id
  try {
    const res = await getIamPolicy({ id: tenantMgmtTenant.value.id, policyId: record.id })
    const stmts = res.data?.statements || []
    iamPolicyStatements.value = { ...iamPolicyStatements.value, [record.id]: stmts }
  } catch (e: any) {
    message.error(e?.message || '加载策略语句失败')
  } finally {
    iamPolicyDetailLoading.value = ''
  }
}

async function loadAnnouncements() {
  if (!tenantMgmtTenant.value?.id) return
  announcementsLoading.value = true
  try {
    const res = await listAnnouncements({ id: tenantMgmtTenant.value.id })
    const data = res.data || {}
    announcementsList.value = data.items || []
    announcementsRetentionNote.value = data.retentionNote || ''
    if (!announcementsList.value.length) {
      message.info('未找到云公告（或当前 API 用户无 announcement 读权限）')
    }
  } catch (e: any) {
    message.error(e?.message || '获取云公告失败')
  } finally {
    announcementsLoading.value = false
  }
}

function syncAnnouncementReadStatus(announcementId: string) {
  announcementsList.value = announcementsList.value.map((item: any) =>
    item?.id === announcementId ? { ...item, userStatus: 'Read' } : item,
  )
  if (announcementDetail.value?.id === announcementId) {
    announcementDetail.value = { ...announcementDetail.value, userStatus: 'Read' }
  }
}

async function markAnnouncementAsRead(record: any) {
  const announcementId = record?.id
  const tenantId = tenantMgmtTenant.value?.id
  if (!tenantId || !announcementId || announcementReadUpdatingId.value) return
  announcementReadUpdatingId.value = announcementId
  try {
    await markAnnouncementRead({ id: tenantId, announcementId })
    syncAnnouncementReadStatus(announcementId)
    message.success('已标记为已读')
  } catch (e: any) {
    message.error(e?.message || '设置公告已读失败')
  } finally {
    announcementReadUpdatingId.value = ''
  }
}

async function openAnnouncementDetail(record: any) {
  const announcementId = record?.id
  if (!announcementId || !tenantMgmtTenant.value?.id) return
  announcementDrawerVisible.value = true
  announcementDetailTab.value = 'detail'
  announcementDrawerTitle.value = record.summary || '云公告详情'
  announcementDetailLoading.value = true
  announcementDetail.value = null
  announcementImpacted.value = []
  announcementHistory.value = []
  try {
    const res = await getAnnouncementDetail({
      id: tenantMgmtTenant.value.id,
      announcementId,
    })
    const data = res.data || {}
    announcementDetail.value = data.detail || null
    announcementImpacted.value = data.impactedResources || []
    announcementHistory.value = data.history || []
    if (announcementDetail.value?.summary) {
      announcementDrawerTitle.value = announcementDetail.value.summary
    }
  } catch (e: any) {
    message.error(e?.message || '获取公告详情失败')
    announcementDrawerVisible.value = false
  } finally {
    announcementDetailLoading.value = false
  }
}

async function handleDownloadInvoice(inv: any) {
  const invoiceId = inv?.invoiceId
  const tenantId = tenantInfoData.value?.id
  if (!tenantId || !invoiceId) return
  try {
    const fileName = (inv?.invoiceNo ? `invoice-${inv.invoiceNo}.pdf` : `invoice-${invoiceId}.pdf`)
    const resp: any = await downloadInvoicePdf({ id: tenantId, invoiceId, fileName })
    const blob: Blob = resp instanceof Blob ? resp : (resp?.data as Blob)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = fileName
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch (e: any) {
    message.error(e?.message || '下载失败')
  }
}

function openRenameGroup(name: string, level: string) {
  renameOldName.value = name
  renameNewName.value = name
  renameLevel.value = level
  renameVisible.value = true
}

async function handleRenameGroup() {
  if (!renameNewName.value.trim()) { message.warning('名称不能为空'); return }
  renameLoading.value = true
  try {
    await renameGroup({ oldName: renameOldName.value, newName: renameNewName.value.trim(), level: renameLevel.value })
    message.success('分组已重命名')
    renameVisible.value = false
    invalidateCatalogAndReload()
  } catch (e: any) {
    message.error(e?.message || '重命名失败')
  } finally {
    renameLoading.value = false
  }
}

async function handleDeleteGroup(name: string, level: string) {
  Modal.confirm({
    title: '删除分组',
    content: `确定删除分组「${name}」？该分组下的租户将移至「未分组」`,
    async onOk() {
      try {
        await deleteGroup({ name, level })
        message.success('分组已删除')
        invalidateCatalogAndReload()
      } catch (e: any) {
        message.error(e?.message || '删除分组失败')
      }
    }
  })
}

function handleAddSubGroup(parentName: string) {
  addSubParent.value = parentName
  addSubName.value = ''
  addSubVisible.value = true
}

async function handleAddSubGroupConfirm() {
  const name = addSubName.value.trim()
  if (!name) { message.warning('子分组名不能为空'); return }
  try {
    await createGroup({ name, level: '2', parent: addSubParent.value })
    addSubVisible.value = false
    message.success('子分组已添加')
    invalidateCatalogAndReload()
  } catch (e: any) {
    message.error(e?.message || '添加子分组失败')
  }
}

function openGroupManager() {
  groupMgrVisible.value = true
  createGroupFormVisible.value = false
}

function openCreateGroupForm() {
  createGroupName.value = ''
  createGroupLevel.value = '1'
  createGroupParent.value = ''
  createGroupFormVisible.value = true
}

async function handleCreateGroup() {
  const name = createGroupName.value.trim()
  if (!name) { message.warning('分组名不能为空'); return }
  if (createGroupLevel.value === '2' && !createGroupParent.value) {
    message.warning('请选择父分组'); return
  }
  createGroupLoading.value = true
  try {
    await createGroup({
      name,
      level: createGroupLevel.value,
      parent: createGroupLevel.value === '2' ? createGroupParent.value : undefined,
    })
    message.success('分组已创建')
    createGroupFormVisible.value = false
    invalidateCatalogAndReload()
  } catch (e: any) {
    message.error(e?.message || '创建分组失败')
  } finally {
    createGroupLoading.value = false
  }
}

function handleMgrAddSub(parentName: string) {
  createGroupName.value = ''
  createGroupLevel.value = '2'
  createGroupParent.value = parentName
  createGroupFormVisible.value = true
}

async function handleMgrDeleteGroup(name: string, level: string) {
  try {
    await deleteGroup({ name, level })
    message.success('分组已删除')
    invalidateCatalogAndReload()
  } catch (e: any) {
    message.error(e?.message || '删除分组失败')
  }
}

function goUserManagement(record: any) {
  router.push(`/tenant/${record.id}/users`)
}

function handleUpload(file: File) {
  pendingFile = file
  fileList.value = [{ uid: '-1', name: file.name, status: 'done' } as UploadFile]
  return false
}

function handleRemoveFile() {
  pendingFile = null
  fileList.value = []
}

async function handleSubmit() {
  if (submitLoading.value) return
  const normalizedRegion = normalizeOciRegionValue(formState.ociRegion)
  if (!formState.username || !formState.ociTenantId || !formState.ociUserId || !formState.ociFingerprint || !normalizedRegion) {
    message.warning('请填写所有必填项')
    return
  }
  if (regionInputMode.value === 'manual' && !OCI_REGION_ID_PATTERN.test(normalizedRegion) && !hasOciRegionOption(normalizedRegion)) {
    message.warning('Region ID 格式不正确，例如 eu-turin-1')
    return
  }
  formState.ociRegion = normalizedRegion

  submitLoading.value = true
  try {
    let keyPath = formState.ociKeyPath
    let fileToUpload: File | null = pendingFile
    if (!fileToUpload && keyInputMode.value === 'paste' && pemPasteText.value.trim()) {
      if (!validatePemPasteText(pemPasteText.value)) {
        message.warning('请粘贴完整的 PEM 私钥（须包含 BEGIN … PRIVATE KEY … END）')
        submitLoading.value = false
        return
      }
      fileToUpload = pemPasteTextToFile(pemPasteText.value)
    }
    if (fileToUpload) {
      const fd = new FormData()
      fd.append('file', fileToUpload)
      const uploadRes = await uploadKey(fd)
      keyPath = uploadRes.data
    }

    if (!keyPath && !editingId.value) {
      message.warning(keyInputMode.value === 'paste' ? '请粘贴 PEM 私钥' : '请上传私钥文件')
      submitLoading.value = false
      return
    }

    const data = { ...formState, ociRegion: normalizedRegion, ociKeyPath: keyPath }
    if (editingId.value) {
      await updateTenant({ id: editingId.value, ...data })
      message.success('更新成功')
    } else {
      await addTenant(data)
      message.success('添加成功')
    }
    modalVisible.value = false
    catalog.invalidate()
    await loadData({
      groupLevel1: formState.groupLevel1,
      groupLevel2: formState.groupLevel2,
    })
    scheduleTenantInfoPolling()
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    submitLoading.value = false
  }
}

async function handleDelete(id: string) {
  try {
    await removeTenant({ idList: [id] })
    message.success('删除成功')
    catalog.invalidate()
    catalog.removeTenantsFromCache([id])
    loadData()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  }
}

async function openBatchMoveModal() {
  if (!selectedRowKeys.value.length) return
  batchMoveG1.value = undefined
  batchMoveG2.value = undefined
  try {
    await catalog.ensureGroups({ silent: true })
  } catch {
    /* 仍可用已有 groupData */
  }
  batchMoveVisible.value = true
}

async function confirmBatchMove() {
  if (!selectedRowKeys.value.length) return
  if (!batchMoveG1.value) {
    message.warning('请选择一级分组')
    return Promise.reject()
  }
  batchMoveLoading.value = true
  try {
    await batchMoveTenantGroup({
      idList: [...selectedRowKeys.value],
      groupLevel1: batchMoveG1.value,
      groupLevel2: batchMoveG2.value || undefined,
    })
    const target = {
      groupLevel1: batchMoveG1.value,
      groupLevel2: batchMoveG2.value || undefined,
    }
    message.success('已移动')
    batchMoveVisible.value = false
    selectedRowKeys.value = []
    catalog.invalidate()
    await loadData(target)
  } catch (e: any) {
    message.error(e?.message || '移动失败')
    return Promise.reject()
  } finally {
    batchMoveLoading.value = false
  }
}

function handleBatchDelete() {
  Modal.confirm({
    title: '确认批量删除？',
    content: `将删除 ${selectedRowKeys.value.length} 条配置`,
    async onOk() {
      try {
        await removeTenant({ idList: selectedRowKeys.value })
        message.success('删除成功')
        catalog.invalidate()
        catalog.removeTenantsFromCache([...selectedRowKeys.value])
        selectedRowKeys.value = []
        loadData()
      } catch (e: any) {
        message.error(e?.message || '删除失败')
      }
    },
  })
}

onMounted(async () => {
  void loadData()
  window.addEventListener('resize', checkMobile)
})
onActivated(() => {
  if (!normalizedSearchText.value) {
    void catalog.ensureTenants({ silent: true }).catch(() => {})
    void catalog.ensureGroups({ silent: true }).catch(() => {})
  }
})
onUnmounted(() => {
  if (tenantSearchTimer) {
    clearTimeout(tenantSearchTimer)
    tenantSearchTimer = null
  }
  clearTenantInfoPollTimers()
  window.removeEventListener('resize', checkMobile)
})
</script>

<style scoped>
.mobile-card {
  content-visibility: auto;
  contain-intrinsic-size: 180px;
}

.domain-switcher {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 12px;
  margin-bottom: 12px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
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
.tenant-name-mask-btn,
.tenant-config-root :deep(.tenant-name-mask-btn) {
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
.tenant-name-mask-btn:focus-visible,
.tenant-config-root :deep(.tenant-name-mask-btn:hover),
.tenant-config-root :deep(.tenant-name-mask-btn:focus-visible) {
  color: var(--primary);
  background: rgba(148, 163, 184, 0.16) !important;
  opacity: 1;
}
.domain-switcher-label {
  font-size: 13px;
  color: var(--text-sub);
  white-space: nowrap;
}
.domain-option {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.domain-option-name { font-weight: 500; }
.domain-type-pill {
  display: inline-block;
  font-size: 11px;
  line-height: 1.5;
  padding: 0 6px;
  border-radius: 10px;
  background: rgba(22, 119, 255, 0.12);
  color: var(--primary, #1677ff);
}
.domain-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
  padding: 12px 14px;
  margin-bottom: 12px;
}
.domain-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.factor-lock {
  padding: 36px 20px;
  border: 1px dashed var(--border);
  border-radius: var(--radius-sm, 8px);
  text-align: center;
  background: var(--bg-card);
}
.factor-lock-icon {
  font-size: 36px;
  color: var(--primary, #1677ff);
  display: block;
  margin-bottom: 8px;
}
.factor-lock-title { font-size: 15px; font-weight: 600; margin-bottom: 6px; }
.factor-lock-desc { font-size: 12px; color: var(--text-sub); max-width: 520px; margin: 0 auto; line-height: 1.6; }
.notification-lock-actions {
  justify-content: center;
  margin-top: 14px;
}
.factor-section-title {
  font-weight: 600;
  font-size: 13px;
  margin: 12px 0 8px;
  color: var(--text-main);
}
.factor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 8px 12px;
}
.factor-label { font-size: 12px; color: var(--text-sub); }
.factor-hint { font-size: 11px; color: var(--text-sub); opacity: 0.7; font-family: monospace; }
.domain-notification-layout {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.notification-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
  background: var(--bg-card);
}
.notification-toolbar-title {
  min-width: 0;
  color: var(--text-main);
  font-size: 13px;
  font-weight: 600;
}
.notification-panel {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
  background: var(--bg-card);
  padding: 12px;
}
.notification-panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.notification-panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
}
.notification-panel-subtitle {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-sub);
  line-height: 1.5;
}
.notification-inline-control {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  color: var(--text-sub);
  font-size: 12px;
  text-align: right;
}
.notification-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 12px;
}
.notification-form-grid :deep(.ant-form-item) {
  margin-bottom: 8px;
}
.notification-collapse {
  display: grid;
  gap: 12px;
  background: transparent;
}
.notification-collapse :deep(.ant-collapse-item) {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
  background: var(--bg-card);
}
.notification-collapse :deep(.ant-collapse-header) {
  align-items: center !important;
  padding: 12px !important;
  color: var(--text-main) !important;
}
.notification-collapse :deep(.ant-collapse-content) {
  border-top: 1px solid var(--border);
  background: transparent;
}
.notification-collapse :deep(.ant-collapse-content-box) {
  padding: 12px !important;
}
.notification-collapse-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  font-size: 14px;
  font-weight: 600;
}
.notification-event-list {
  display: grid;
  gap: 8px;
}
.notification-event-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
  background: var(--bg-main, transparent);
}
.notification-event-copy {
  min-width: 0;
}
.notification-event-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-main);
}
.notification-event-id {
  margin-top: 2px;
  font-size: 11px;
  color: var(--text-sub);
  word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.notification-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
  flex-wrap: wrap;
}
.table-toolbar {
  margin-bottom: 16px;
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  transition: var(--trans);
}
/* 快速导入（无折叠，仅展示） */
.tenant-quick-import-block {
  margin-bottom: 12px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: var(--trans);
  overflow: hidden;
}
.tenant-quick-import-header {
  padding: 12px 16px;
  font-weight: 600;
  font-size: 14px;
  color: var(--text-main);
  cursor: default;
  user-select: none;
  border-bottom: 1px solid var(--border);
}
.tenant-quick-import-body {
  padding: 12px 16px 16px;
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
  top: -6px; left: 0; right: 0;
  height: 3px;
  background: var(--primary, #1677ff);
  border-radius: 2px;
  box-shadow: 0 0 8px var(--primary, #1677ff);
}
.group-section.drag-over-bottom::after {
  content: '';
  position: absolute;
  bottom: -6px; left: 0; right: 0;
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
  top: 0; left: 0;
  width: 100%; height: 3px;
  background: linear-gradient(90deg, var(--primary, #1677ff), #8b5cf6);
  transform: scaleX(0);
  transition: transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
  transform-origin: left;
}
.group-card:hover::before { transform: scaleX(1); }
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
.subgroup-card::before { display: none; }
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
.group-bar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
}
.group-bar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.drag-handle {
  width: 28px; height: 28px;
  display: flex; align-items: center; justify-content: center;
  background: rgba(255,255,255,0.03);
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text-sub, #999);
  cursor: grab;
  transition: all 0.2s;
  font-size: 14px;
  flex-shrink: 0;
  user-select: none;
}
.drag-handle:active { cursor: grabbing; }
.drag-handle:hover { color: var(--primary, #1677ff); border-color: var(--primary, #1677ff); }
.collapse-btn {
  width: 28px; height: 28px;
  display: flex; align-items: center; justify-content: center;
  background: rgba(255,255,255,0.03);
  border: 1px solid var(--border);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
  font-size: 12px;
  flex-shrink: 0;
}
.collapse-btn:hover { border-color: var(--primary, #1677ff); }
.group-dot {
  width: 12px; height: 12px;
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
  background: rgba(255,255,255,0.03);
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
.tag-green { background: rgba(82, 196, 26, 0.15); color: #52c41a; }
.tag-free-tier {
  color: rgba(255, 255, 255, 0.92);
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.16);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(10px) saturate(145%);
  -webkit-backdrop-filter: blur(10px) saturate(145%);
}
.tag-gray { background: rgba(150, 150, 150, 0.15); color: #999; }
:global([data-theme="light"]) .tag-free-tier {
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
@media (max-width: 768px) {
  .group-card { padding: 10px 12px; border-radius: 10px; }
  .group-name, .subgroup-name { font-size: 14px; max-width: none; flex: 1 1 0; min-width: 48px; }
  .subgroup-card { margin-left: 16px; }
  .group-bar-right { flex-wrap: wrap; gap: 4px; }
  .drag-handle, .collapse-btn { width: 32px; height: 32px; }
  .group-card-header { gap: 8px; }
  .group-card-header-main { flex: 1 1 100%; min-width: 0; }
  .group-stats { margin-left: auto; gap: 6px; }
  .group-card-header-actions {
    margin-left: 0;
    width: 100%;
    justify-content: flex-end;
  }
  .subgroup-header .group-card-header-actions { width: auto; margin-left: auto; }
}
.tenant-config-root {
  position: relative;
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

.tenant-account-pane {
  position: relative;
}

.tenant-account-refresh {
  position: absolute;
  top: -20px;
  right: 6px;
  z-index: 2;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-sub);
  cursor: pointer;
  font-size: 15px;
  line-height: 20px;
  text-align: center;
  transition: color 0.18s ease, border-color 0.18s ease, background 0.18s ease, opacity 0.18s ease;
}

.tenant-account-refresh:hover:not(:disabled) {
  border-color: rgba(99, 102, 241, 0.58);
  background: rgba(99, 102, 241, 0.12);
  color: #818cf8;
}

.tenant-account-refresh:disabled {
  cursor: default;
  opacity: 0.72;
}

.tenant-account-refresh.spinning {
  animation: tenant-account-refresh-spin 0.85s linear infinite;
}

@keyframes tenant-account-refresh-spin {
  to { transform: rotate(360deg); }
}

:global([data-theme="light"]) .tenant-account-refresh {
  border-color: rgba(15, 23, 42, 0.12);
  background: rgba(15, 23, 42, 0.035);
  color: rgba(71, 85, 105, 0.82);
}

:global([data-theme="light"]) .tenant-account-refresh:hover:not(:disabled) {
  border-color: rgba(37, 99, 235, 0.38);
  background: rgba(37, 99, 235, 0.08);
  color: #2563eb;
}

.tenant-form-compact :deep(.ant-form-item) {
  margin-bottom: 12px;
}
.tenant-form-compact :deep(.ant-form-item-label) {
  padding-bottom: 2px;
}
.pem-input-mode-segmented {
  margin-bottom: 8px;
}
.region-input-mode {
  margin-bottom: 8px;
}
.quota-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 12px;
}
.quota-region-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.quota-service-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.quota-region-label {
  color: var(--text-sub);
  font-size: 13px;
  white-space: nowrap;
}
.quota-region-select {
  width: 180px;
}
.quota-service-select {
  width: 150px;
}
.quota-region-option {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.quota-region-option-code {
  min-width: 0;
  overflow: hidden;
  color: var(--text);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.quota-region-home-mark {
  color: var(--text-sub);
  flex: 0 0 auto;
  font-size: 11px;
  font-weight: 400;
  opacity: 0.72;
  transform: translateY(2px);
}
.quota-region-native-select {
  width: 100%;
  height: 32px;
  padding: 0 32px 0 10px;
  color: var(--text);
  background: var(--card-bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  outline: none;
}
.quota-search {
  width: 220px;
}
@media (max-width: 768px) {
  .quota-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
  .quota-region-field {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
    width: 100%;
  }
  .quota-service-field {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
    width: 100%;
  }
  .quota-search {
    width: 100%;
  }
  .quota-toolbar .ant-btn {
    width: 100%;
  }
}
/* 与 rows=4 的 textarea 同高（约 118px） */
.pem-key-input-slot {
  height: 118px;
}
.pem-key-input-slot .pem-upload-dragger,
.pem-key-input-slot .pem-upload-dragger :deep(.ant-upload),
.pem-key-input-slot .pem-upload-dragger :deep(.ant-upload-drag) {
  height: 118px;
  margin: 0;
  padding: 0;
  display: block;
}
.pem-key-input-slot .pem-upload-dragger :deep(.ant-upload-drag) {
  height: 100%;
}
.pem-key-input-slot .pem-upload-dragger :deep(.ant-upload-btn) {
  display: flex !important;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  width: 100%;
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}
.pem-key-input-slot .pem-upload-dragger :deep(.ant-upload-drag-icon) {
  margin: 0 0 6px 0;
  font-size: 28px;
  line-height: 1;
}
.pem-key-input-slot .pem-upload-dragger :deep(.ant-upload-text) {
  margin: 0;
  padding: 0 8px;
  font-size: 13px;
  line-height: 1.4;
  text-align: center;
}
.pem-key-input-slot .pem-paste-textarea,
.pem-key-input-slot .pem-paste-textarea :deep(textarea) {
  height: 118px !important;
  min-height: 118px !important;
  margin: 0;
  resize: none;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}
.pem-upload-filename {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-sub, #888);
}
.pem-upload-remove {
  margin-left: 8px;
}
.pem-existing-hint {
  color: var(--text-sub, #888);
  font-size: 12px;
  margin-top: 6px;
  display: block;
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

.budget-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 8px 0 12px;
}
.budget-toolbar :deep(.ant-space) {
  max-width: 100%;
}
.budget-target-cell {
  max-width: 220px;
  overflow: hidden;
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.budget-spend-line {
  margin-top: 2px;
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.4;
}
:deep(.budget-table .ant-table-row) {
  cursor: pointer;
}
:deep(.budget-row-selected) > td {
  background: rgba(22, 119, 255, 0.08) !important;
}
:deep(.budget-row-selected) > td:first-child {
  box-shadow: inset 2px 0 0 var(--primary, #1677ff);
}
.budget-alert-section {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}
.budget-alert-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.budget-alert-title {
  max-width: 460px;
  overflow: hidden;
  color: var(--text-main);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.budget-alert-subtitle {
  color: var(--text-sub);
  font-size: 12px;
}
.budget-mobile-card {
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;
}
.budget-mobile-card-active {
  border-color: rgba(22, 119, 255, 0.55);
  background: rgba(22, 119, 255, 0.04);
  box-shadow: 0 4px 16px rgba(22, 119, 255, 0.1);
}
.budget-form :deep(.ant-form-item) {
  margin-bottom: 12px;
}
.budget-form :deep(.ant-input),
.budget-form :deep(.ant-input-number),
.budget-form :deep(.ant-select),
.budget-form :deep(textarea) {
  max-width: 100%;
}

.region-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 8px 0 12px;
}
.region-search {
  width: 240px;
}
.region-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.region-name-main {
  min-width: 0;
  overflow: hidden;
  color: var(--text-main);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.region-key-line {
  margin-top: 2px;
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.4;
}
.region-action-empty {
  color: var(--text-sub);
  font-size: 12px;
}
.region-mobile-card {
  margin-bottom: 10px;
}
.region-verify-target {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: var(--input-bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
}
.region-verify-name {
  overflow: hidden;
  color: var(--text-main);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.region-verify-meta {
  margin-top: 4px;
  color: var(--text-sub);
  font-size: 12px;
  word-break: break-all;
}
.region-verify-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
  color: var(--text-sub);
  font-size: 12px;
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
  .group-header { padding: 8px 10px; }
  .group-body { padding: 4px 4px; }
  .budget-toolbar {
    align-items: flex-start;
    margin-top: 6px;
  }
  .budget-toolbar :deep(.ant-space) {
    width: 100%;
    gap: 8px !important;
  }
  .budget-alert-header {
    align-items: flex-start;
    flex-direction: column;
  }
  .budget-alert-title {
    max-width: 100%;
    white-space: normal;
    word-break: break-word;
  }
  .budget-alert-header :deep(.ant-space) {
    width: 100%;
  }
  .budget-mobile-card {
    margin-bottom: 10px;
  }
  .budget-mobile-card :deep(.ant-progress) {
    margin-top: 4px;
  }
  .budget-mobile-card .value {
    min-width: 0;
    text-align: right;
    word-break: break-word;
  }
  .budget-form :deep(.ant-col) {
    width: 100%;
  }
  .region-toolbar {
    align-items: flex-start;
    margin-top: 6px;
  }
  .region-toolbar :deep(.ant-space) {
    width: 100%;
    gap: 8px !important;
  }
  .region-search {
    width: 100%;
  }
  .region-mobile-card .value {
    min-width: 0;
    text-align: right;
    word-break: break-word;
  }
  .region-verify-actions {
    align-items: flex-start;
    flex-direction: column;
  }
  .domain-switcher {
    align-items: stretch;
    flex-direction: column;
  }
  .domain-switcher :deep(.ant-select) {
    width: 100% !important;
    min-width: 0 !important;
  }
  .notification-panel-header {
    align-items: flex-start;
    flex-direction: column;
  }
  .notification-inline-control {
    justify-content: space-between;
    width: 100%;
    text-align: left;
  }
  .notification-form-grid {
    grid-template-columns: 1fr;
  }
  .notification-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
  .notification-toolbar :deep(.ant-btn) {
    width: 100%;
  }
  .notification-event-row {
    align-items: flex-start;
    flex-direction: column;
  }
  .notification-event-row :deep(.ant-switch) {
    align-self: flex-end;
  }
  .notification-actions {
    justify-content: stretch;
  }
  .notification-actions :deep(.ant-btn) {
    flex: 1 1 120px;
  }
  .notification-lock-actions {
    width: 100%;
    justify-content: stretch;
  }
  .notification-lock-actions :deep(.ant-space-item) {
    flex: 1 1 100%;
  }
  .notification-lock-actions :deep(.ant-input),
  .notification-lock-actions :deep(.ant-btn) {
    width: 100% !important;
  }
}

.iam-statements { padding: 4px 0; }
.iam-statement-line {
  font-family: ui-monospace, monospace;
  font-size: 12px;
  line-height: 1.5;
  margin-bottom: 6px;
  word-break: break-word;
}

.announcement-block { margin-top: 14px; }
.announcement-block-title {
  font-weight: 600;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--text-main);
}
.announcement-description {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
  padding: 10px 12px;
  color: var(--text-main);
  background: var(--input-bg);
  border-radius: var(--radius-sm, 8px);
  border: 1px solid var(--border);
}
.announcement-description :deep(.announcement-link) {
  color: var(--primary);
  text-decoration: underline;
}
.announcement-description :deep(.announcement-link:hover) {
  color: var(--primary-hover);
}
</style>
