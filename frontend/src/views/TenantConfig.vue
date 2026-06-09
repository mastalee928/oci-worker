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
      <template v-if="searchText">
        <a-table v-if="!isMobile" :columns="columns" :data-source="tableData" :loading="loading"
          :row-selection="{ selectedRowKeys, onChange: onSelectChange }" :pagination="false"
          row-key="id" size="middle">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'tenantName'">
              <span v-if="record.tenantName">{{ record.tenantName }}</span>
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
              <a-tag :color="record.planType === 'PAYG' ? 'green' : record.planType === 'FREE' ? 'orange' : 'default'">{{ record.planType || '获取中...' }}</a-tag>
            </template>
            <template v-if="column.key === 'createTime'">
              {{ formatTenantAddedTime(record.createTime) }}
            </template>
            <template v-if="column.key === 'action'">
              <a-space>
                <a-button type="link" size="small" @click="showEditModal(record)">编辑</a-button>
                <a-button type="link" size="small" @click="openDomainMgmt(record)">域</a-button>
                <a-button type="link" size="small" @click="openTenantMgmt(record)">租户</a-button>
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
              <a-tag :color="r.planType === 'PAYG' ? 'green' : r.planType === 'FREE' ? 'orange' : 'default'">{{ r.planType || '?' }}</a-tag>
            </div>
            <div class="mobile-card-body">
              <div class="mobile-card-row"><span class="label">租户名</span><span class="value">{{ r.tenantName || '获取中...' }}</span></div>
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
              <a-button type="link" size="small" @click="openDomainMgmt(r)">域</a-button>
              <a-button type="link" size="small" @click="openTenantMgmt(r)">租户</a-button>
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
                  <span :class="['plan-tag', pt === 'PAYG' ? 'tag-green' : pt === 'FREE' ? 'tag-orange' : 'tag-gray']">{{ pt }}×{{ pc }}</span>
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
            <div v-for="sub in group.children" :key="sub.key" class="group-card subgroup-card">
              <div class="group-card-header subgroup-header">
                <div class="group-card-header-main">
                <div class="drag-handle" title="拖动排序">
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
                    :row-selection="{ selectedRowKeys, onChange: onSelectChange }"
                    row-key="id" size="small">
                    <template #bodyCell="{ column, record }">
                      <template v-if="column.key === 'tenantName'">
                        <span v-if="record.tenantName">{{ record.tenantName }}</span>
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
                        <a-tag :color="record.planType === 'PAYG' ? 'green' : record.planType === 'FREE' ? 'orange' : 'default'">{{ record.planType || '获取中...' }}</a-tag>
                      </template>
                      <template v-if="column.key === 'createTime'">
                        {{ formatTenantAddedTime(record.createTime) }}
                      </template>
                      <template v-if="column.key === 'action'">
                        <a-space>
                          <a-button type="link" size="small" @click="showEditModal(record)">编辑</a-button>
                          <a-button type="link" size="small" @click="openDomainMgmt(record)">域</a-button>
                          <a-button type="link" size="small" @click="openTenantMgmt(record)">租户</a-button>
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
                        <a-tag :color="r.planType === 'PAYG' ? 'green' : r.planType === 'FREE' ? 'orange' : 'default'" style="margin:0">{{ r.planType || '?' }}</a-tag>
                      </div>
                      <div class="mobile-card-body">
                        <div class="mobile-card-row"><span class="label">租户名</span><span class="value">{{ r.tenantName || '获取中...' }}</span></div>
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
                        <a-button type="link" size="small" @click="openDomainMgmt(r)">域</a-button>
                        <a-button type="link" size="small" @click="openTenantMgmt(r)">租户</a-button>
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
        </div>
          <div v-if="expandedGroups.has(group.key) && group.tenants.length" class="group-card subgroup-card">
            <div class="group-body">
              <template v-if="group.tenants.length">
                <a-table v-if="!isMobile" :columns="columns" :data-source="group.tenants" :pagination="false"
                  :row-selection="{ selectedRowKeys, onChange: onSelectChange }"
                  row-key="id" size="small">
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'tenantName'">
                      <span v-if="record.tenantName">{{ record.tenantName }}</span>
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
                      <a-tag :color="record.planType === 'PAYG' ? 'green' : record.planType === 'FREE' ? 'orange' : 'default'">{{ record.planType || '获取中...' }}</a-tag>
                    </template>
                    <template v-if="column.key === 'createTime'">
                      {{ formatTenantAddedTime(record.createTime) }}
                    </template>
                    <template v-if="column.key === 'action'">
                      <a-space>
                        <a-button type="link" size="small" @click="showEditModal(record)">编辑</a-button>
                        <a-button type="link" size="small" @click="openDomainMgmt(record)">域</a-button>
                        <a-button type="link" size="small" @click="openTenantMgmt(record)">租户</a-button>
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
                      <a-tag :color="r.planType === 'PAYG' ? 'green' : r.planType === 'FREE' ? 'orange' : 'default'" style="margin:0">{{ r.planType || '?' }}</a-tag>
                    </div>
                    <div class="mobile-card-body">
                      <div class="mobile-card-row"><span class="label">租户名</span><span class="value">{{ r.tenantName || '获取中...' }}</span></div>
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
                      <a-button type="link" size="small" @click="openDomainMgmt(r)">域</a-button>
                      <a-button type="link" size="small" @click="openTenantMgmt(r)">租户</a-button>
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
          <a-select v-model:value="formState.ociRegion" placeholder="选择区域" show-search
            :filter-option="filterOciRegionSelectOption">
            <a-select-option v-for="opt in ociRegionSelectOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</a-select-option>
          </a-select>
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
        <a-tab-pane key="account" tab="账户信息">
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
          <a-descriptions-item label="试用期内已消费">
            <template v-if="tenantInfoData.subscriptionUsage?.summary != null">
              {{ tenantInfoData.subscriptionUsage.summary.totalConsumedLabel
                ?? tenantInfoData.subscriptionUsage.summary.totalConsumed
                ?? '0' }}
              <span v-if="tenantInfoData.subscriptionUsage.timeUsageStarted" style="display: block; font-size: 11px; color: var(--text-sub); margin-top: 2px">
                自 {{ formatUtcCnDate(tenantInfoData.subscriptionUsage.timeUsageStarted) }}（Usage API）
              </span>
            </template>
            <span v-else>—</span>
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
        <a-tab-pane key="compartments" tab="区间">
          <CompartmentManager
            v-if="tenantTab === 'compartments' && tenantMgmtTenant?.id"
            :tenant-id="tenantMgmtTenant.id"
          />
        </a-tab-pane>
        <a-tab-pane key="iam" tab="IAM 策略">
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
            <a-table-column title="操作" key="action" :width="72">
              <template #default="{ record }">
                <a-button type="link" size="small" @click.stop="openAnnouncementDetail(record)">详情</a-button>
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
            </div>
          </a-spin>
        </a-tab-pane>
        <a-tab-pane key="quotas" tab="账户配额">
          <a-space style="margin-bottom: 12px">
            <a-button type="primary" @click="loadQuotas" :loading="quotasLoading">
              <template #icon><ReloadOutlined /></template>查询配额
            </a-button>
            <a-input-search v-model:value="quotaSearch" placeholder="搜索服务/配额名" allow-clear style="width: 220px" />
          </a-space>
          <a-table v-if="!isMobile" :data-source="filteredQuotas" :loading="quotasLoading" size="small"
            :pagination="{ pageSize: 20 }" :row-key="(r: any) => r.serviceName + r.limitName + r.availabilityDomain">
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
      </a-tabs>
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
                  <a-tag :color="announcementStatusColor(announcementDetail.userStatus)">
                    {{ formatAnnouncementUserStatus(announcementDetail.userStatus) }}
                  </a-tag>
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
            <span style="font-size: 12px; color: var(--text-sub)">
              来源：OCI Audit（租户 compartment + 时间窗），身份域登录事件以 <code style="font-size:11px">additionalDetails.eventId</code> 为准（与 Oracle IAM 审计迁移文档一致）
            </span>
          </a-space>
          <a-spin :spinning="auditLogsLoading">
            <a-empty v-if="!auditLogsLoading && !selectedAuditDomain" description="请点击「加载」按钮拉取当前域的登录日志" />
            <div v-else-if="selectedAuditDomain">
              <AuditLogTable :rows="selectedAuditDomain.logs || []"
                :error="selectedAuditDomain.error || selectedAuditDomain.notice" :is-mobile="isMobile" />
            </div>
          </a-spin>
        </a-tab-pane>
        
      </a-tabs>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'TenantConfig' })

import { ref, reactive, computed, onMounted, onActivated, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { PlusOutlined, ThunderboltOutlined, InboxOutlined, ReloadOutlined, MenuFoldOutlined, MenuUnfoldOutlined, VerticalAlignTopOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import type { UploadFile } from 'ant-design-vue'
import { getTenantList, addTenant, updateTenant, removeTenant, batchMoveTenantGroup, uploadKey, getTenantFullInfo, getTenantBillingSummary, downloadInvoicePdf, getDomainSettings, updateMfa, updatePasswordExpiry, getAuditLogs, getServiceQuotas, listIamPolicies, getIamPolicy, listAnnouncements, getAnnouncementDetail, getTenantGroups, createGroup, renameGroup, deleteGroup, saveGroupOrder, unlockAuthFactors, getAuthFactors, updateAuthFactors } from '../api/tenant'
import { sendVerifyCode } from '../api/system'
import { RightOutlined, DownOutlined, SettingOutlined, FolderOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons-vue'
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
import utc from 'dayjs/plugin/utc'

dayjs.extend(utc)

const router = useRouter()
const catalog = useTenantCatalogStore()
const searchLoading = ref(false)

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

function isFreeTierPlan(plan: string | null | undefined) {
  if (!plan) return false
  const p = plan.toUpperCase().replace(/[_-]/g, '')
  return p === 'FREE' || p === 'FREETIER'
}

function formatPlanType(v: string | null | undefined): string {
  if (!v) return '—'
  if (isFreeTierPlan(v)) return '免费套餐 (Free Tier)'
  const map: Record<string, string> = {
    PAYG: '按量付费 (PAYG)',
  }
  return map[v] || v
}

function planTypeTagColor(plan: string | null | undefined) {
  if (isFreeTierPlan(plan)) return 'default'
  return 'green'
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

const columns = [
  { title: '名称', dataIndex: 'username', key: 'username', ellipsis: true },
  { title: '租户名', dataIndex: 'tenantName', key: 'tenantName', width: 150, ellipsis: true },
  { title: '主区域', dataIndex: 'ociRegion', key: 'ociRegion', width: 220 },
  { title: '开机任务', key: 'taskStatus', width: 140 },
  { title: '账户类型', dataIndex: 'planType', key: 'planType', width: 130 },
  { title: '添加日期', key: 'createTime', width: 168 },
  { title: '操作', key: 'action', width: 270 },
]

const loading = computed(() => catalog.tenantsLoading || searchLoading.value)
const submitLoading = ref(false)
const searchTableData = ref<any[]>([])
const tableData = computed(() => (searchText.value ? searchTableData.value : catalog.tenants) as any[])
const searchText = ref('')
const selectedRowKeys = ref<string[]>([])
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
const billingLoading = ref(false)
const billingData = ref<any | null>(null)
const billingCostDays = ref(30)
const billingCostDayOptions = [
  { value: 7, label: '近 7 天' },
  { value: 30, label: '近 30 天' },
  { value: 90, label: '近 90 天' },
]

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
  formState.ociRegion = fields['region'] || formState.ociRegion
  message.success('已解析并填充，请上传或粘贴私钥后提交')
}

/** 新增/编辑保存后展开目标分组；普通 loadData 不设置 */
let pendingExpandTarget: { groupLevel1?: string; groupLevel2?: string } | null = null

async function loadData(expandAfter?: { groupLevel1?: string; groupLevel2?: string }) {
  if (expandAfter && typeof expandAfter === 'object') {
    pendingExpandTarget = expandAfter
  }
  if (searchText.value) {
    searchLoading.value = true
    try {
      const res = await getTenantList({
        current: pagination.current,
        size: pagination.pageSize,
        keyword: searchText.value,
      })
      searchTableData.value = res.data.records || []
      pagination.total = res.data.total || 0
    } catch (e: any) {
      message.error(e?.message || '加载租户列表失败')
    } finally {
      searchLoading.value = false
    }
    return
  }
  try {
    await Promise.all([
      catalog.ensureTenants({ force: false }),
      catalog.ensureGroups({ force: false }),
    ])
  } catch (e: any) {
    message.error(e?.message || catalog.tenantsError || '加载租户列表失败')
  }
  applyDefaultExpandAfterLoad()
}

async function loadGroups() {
  await catalog.ensureGroups({ force: true })
}

function onSearchTenants() {
  pendingExpandTarget = null
  pagination.current = 1
  void loadData()
}

function invalidateCatalogAndReload() {
  catalog.invalidate()
  void loadData()
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

async function showAddModal() {
  editingId.value = ''
  resetForm()
  await loadOciRegionCatalog()
  modalVisible.value = true
}

async function showEditModal(record: any) {
  editingId.value = record.id
  Object.assign(formState, {
    username: record.username,
    ociTenantId: record.ociTenantId,
    ociUserId: record.ociUserId,
    ociFingerprint: record.ociFingerprint,
    ociRegion: record.ociRegion,
    ociKeyPath: record.ociKeyPath,
    groupLevel1: record.groupLevel1 || '',
    groupLevel2: record.groupLevel2 || undefined,
  })
  pendingFile = null
  fileList.value = []
  importText.value = ''
  pemPasteText.value = ''
  keyInputMode.value = 'upload'
  await loadOciRegionCatalog(record.id)
  modalVisible.value = true
}

const domainMgmtVisible = ref(false)
const domainMgmtTenant = ref<any>(null)
const domainTab = ref('security')
const domainSettingsLoading = ref(false)
const domainList = ref<any[]>([])
const selectedDomainId = ref('')
const mfaUpdatingId = ref('')
const pwdExpiryUpdatingId = ref('')
const auditLogsLoading = ref(false)
const auditLogs = ref<any[]>([])
const auditDays = ref(7)
const quotasLoading = ref(false)
const quotasList = ref<any[]>([])
const quotaSearch = ref('')

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

function handleDomainChange(domainId: string) {
  if (!domainId || domainId === selectedDomainId.value) return
  selectedDomainId.value = domainId
}

function onAuditDaysChange() {
  auditLogs.value = []
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
  auditLogs.value = []
  resetAuthFactorState()
  domainMgmtVisible.value = true
  await loadDomainSettings()
}

watch(() => domainMgmtVisible.value, (v) => {
  if (!v) resetAuthFactorState()
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
  const prev = domain.mfaEnabled
  mfaUpdatingId.value = domain.domainId
  try {
    await updateMfa({ id: domainMgmtTenant.value.id, domainId: domain.domainId, enabled: checked })
    domain.mfaEnabled = checked
    message.success(checked ? 'MFA 已启用' : 'MFA 已关闭')
  } catch (e: any) {
    domain.mfaEnabled = prev
    message.error(e?.message || '更新 MFA 策略失败')
  } finally {
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

async function loadAuditLogs() {
  if (!selectedDomainId.value) {
    message.warning('请先选择域')
    return
  }
  auditLogsLoading.value = true
  try {
    const res = await getAuditLogs({ id: domainMgmtTenant.value.id, days: auditDays.value })
    auditLogs.value = Array.isArray(res.data) ? res.data : []
    const cur = auditLogs.value.find((d: any) => d.domainId === selectedDomainId.value)
    const count = cur?.logs?.length || 0
    if (!count && !cur?.error && !cur?.notice) {
      message.info('最近时间窗口内未检索到登录相关日志')
    }
  } catch (e: any) {
    message.error(e?.message || '获取登录日志失败')
  } finally {
    auditLogsLoading.value = false
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

async function loadQuotas() {
  if (!tenantMgmtTenant.value?.id) return
  quotasLoading.value = true
  try {
    const res = await getServiceQuotas({ id: tenantMgmtTenant.value.id })
    quotasList.value = res.data || []
    if (!quotasList.value.length) {
      message.info('未获取到配额信息')
    }
  } catch (e: any) {
    message.error(e?.message || '获取配额失败')
  } finally {
    quotasLoading.value = false
  }
}

const filteredQuotas = computed(() => {
  if (!quotaSearch.value) return quotasList.value
  const kw = quotaSearch.value.toLowerCase()
  return quotasList.value.filter((q: any) =>
    (q.serviceName || '').toLowerCase().includes(kw) ||
    (q.limitName || '').toLowerCase().includes(kw)
  )
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
    for (const [l2, l2Items] of l2Map) {
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
    const key = t.planType === 'PAYG' ? 'PAYG' : t.planType === 'FREE_TIER' || t.planType === 'FREE' ? 'FREE' : 'UNKNOWN'
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

const displayGroups = computed(() => {
  if (localOrder.value.length === 0) return groupTree.value
  const map = new Map<string, any>()
  for (const g of groupTree.value) map.set(g.label, g)
  const result: any[] = []
  for (const name of localOrder.value) {
    const g = map.get(name)
    if (g) { result.push(g); map.delete(name) }
  }
  for (const g of map.values()) result.push(g)
  return result
})

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
  billingData.value = null
  await loadTenantAccountInfo(record)
}

function onTenantTabChange(key: string) {
  if (key === 'billing' && !billingData.value) loadTenantBilling()
  if (key === 'quotas' && !quotasList.value.length && !quotasLoading.value) loadQuotas()
  if (key === 'iam' && !iamPoliciesList.value.length) loadIamPolicies()
  if (key === 'announcements' && !announcementsList.value.length) loadAnnouncements()
}

async function loadTenantAccountInfo(record: any) {
  tenantInfoData.value = { configName: record.username, id: record.id }
  tenantInfoLoading.value = true
  try {
    const res = await getTenantFullInfo({ id: record.id })
    const d = res.data || {}
    tenantInfoData.value = { ...d, id: record.id }
    if (record?.id) {
      const row = tableData.value.find((r: any) => r.id === record.id)
      if (row) {
        if (d.planType) row.planType = d.planType
        if (d.tenantName) row.tenantName = d.tenantName
      }
    }
  } catch (e: any) {
    message.error(e?.message || '获取账户信息失败')
  } finally {
    tenantInfoLoading.value = false
  }
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
  if (!formState.username || !formState.ociTenantId || !formState.ociUserId || !formState.ociFingerprint || !formState.ociRegion) {
    message.warning('请填写所有必填项')
    return
  }

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

    const data = { ...formState, ociKeyPath: keyPath }
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
  if (!searchText.value) {
    void catalog.ensureTenants({ silent: true }).catch(() => {})
    void catalog.ensureGroups({ silent: true }).catch(() => {})
  }
})
onUnmounted(() => window.removeEventListener('resize', checkMobile))
</script>

<style scoped>
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
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  line-height: 18px;
  white-space: nowrap;
}
.tag-green { background: rgba(82, 196, 26, 0.15); color: #52c41a; }
.tag-orange { background: rgba(250, 173, 20, 0.15); color: #faad14; }
.tag-gray { background: rgba(150, 150, 150, 0.15); color: #999; }
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

.tenant-form-compact :deep(.ant-form-item) {
  margin-bottom: 12px;
}
.tenant-form-compact :deep(.ant-form-item-label) {
  padding-bottom: 2px;
}
.pem-input-mode-segmented {
  margin-bottom: 8px;
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
