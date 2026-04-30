<template>
  <div>
    <div class="table-toolbar">
      <a-space wrap>
        <a-input-search v-model:value="searchText" placeholder="搜索租户" allow-clear @search="loadData" style="width: 200px" />
        <a-button @click="openGroupManager">
          <template #icon><FolderOutlined /></template>管理分组
        </a-button>
        <a-button type="primary" @click="showAddModal">
          <template #icon><PlusOutlined /></template>新增配置
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
              <a-tag color="blue">{{ getRegionLabel(record.ociRegion) }}</a-tag>
              <div style="font-size: 11px; color: var(--text-sub); margin-top: 2px">{{ record.ociRegion }}</div>
            </template>
            <template v-if="column.key === 'taskStatus'">
              <a-badge v-if="record.hasRunningTask" status="processing" text="执行开机任务中" />
              <span v-else style="color: #999">无开机任务</span>
            </template>
            <template v-if="column.key === 'planType'">
              <a-tag :color="record.planType === 'PAYG' ? 'green' : record.planType === 'FREE' ? 'orange' : 'default'">{{ record.planType || '获取中...' }}</a-tag>
            </template>
            <template v-if="column.key === 'action'">
              <a-space>
                <a-button type="link" size="small" @click="openTenantInfo(record)">详情</a-button>
                <a-button type="link" size="small" @click="showEditModal(record)">编辑</a-button>
                <a-button type="link" size="small" @click="openDomainMgmt(record)">管理</a-button>
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
              <div class="mobile-card-row"><span class="label">区域</span><a-tag color="blue" style="margin:0">{{ getRegionLabel(r.ociRegion) }}</a-tag></div>
              <div class="mobile-card-row">
                <span class="label">开机任务</span>
                <a-badge v-if="r.hasRunningTask" status="processing" text="执行中" />
                <span v-else style="color: #999">无</span>
              </div>
            </div>
            <div class="mobile-card-actions">
              <a-button type="link" size="small" @click="openTenantInfo(r)">详情</a-button>
              <a-button type="link" size="small" @click="showEditModal(r)">编辑</a-button>
              <a-button type="link" size="small" @click="openDomainMgmt(r)">管理</a-button>
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
            <div style="display: flex; align-items: center; gap: 10px;">
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

              <div v-if="groupTotalCount(group) > 0" class="group-stats">
                <div class="stat-item">
                  <span class="stat-icon">📅</span>
                  <span>{{ groupTotalCount(group) }}</span>
                </div>
                <template v-for="(pc, pt) in getPlanCounts(group)" :key="pt">
                  <span :class="['plan-tag', pt === 'PAYG' ? 'tag-green' : pt === 'FREE' ? 'tag-orange' : 'tag-gray']">{{ pt }}×{{ pc }}</span>
                </template>
              </div>

              <div style="display: flex; gap: 6px; margin-left: auto;">
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

            <div v-show="expandedGroups.has(group.key)" class="group-body">
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
                      <a-tag color="blue">{{ getRegionLabel(record.ociRegion) }}</a-tag>
                      <div style="font-size: 11px; color: var(--text-sub); margin-top: 2px">{{ record.ociRegion }}</div>
                    </template>
                    <template v-if="column.key === 'taskStatus'">
                      <a-badge v-if="record.hasRunningTask" status="processing" text="执行开机任务中" />
                      <span v-else style="color: #999">无开机任务</span>
                    </template>
                    <template v-if="column.key === 'planType'">
                      <a-tag :color="record.planType === 'PAYG' ? 'green' : record.planType === 'FREE' ? 'orange' : 'default'">{{ record.planType || '获取中...' }}</a-tag>
                    </template>
                    <template v-if="column.key === 'action'">
                      <a-space>
                        <a-button type="link" size="small" @click="openTenantInfo(record)">详情</a-button>
                        <a-button type="link" size="small" @click="showEditModal(record)">编辑</a-button>
                        <a-button type="link" size="small" @click="openDomainMgmt(record)">管理</a-button>
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
                      <div class="mobile-card-row"><span class="label">区域</span><a-tag color="blue" style="margin:0">{{ getRegionLabel(r.ociRegion) }}</a-tag></div>
                      <div class="mobile-card-row">
                        <span class="label">任务</span>
                        <a-badge v-if="r.hasRunningTask" status="processing" text="执行中" />
                        <span v-else style="color: #999">无</span>
                      </div>
                    </div>
                    <div class="mobile-card-actions">
                      <a-button type="link" size="small" @click="openTenantInfo(r)">详情</a-button>
                      <a-button type="link" size="small" @click="showEditModal(r)">编辑</a-button>
                      <a-button type="link" size="small" @click="openDomainMgmt(r)">管理</a-button>
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

          <!-- 二级子分组卡片 -->
          <template v-if="group.children && expandedGroups.has(group.key)">
            <div v-for="sub in group.children" :key="sub.key" class="group-card subgroup-card">
              <div style="display: flex; align-items: center; gap: 10px;">
                <div class="drag-handle" title="拖动排序">
                  <span style="font-size: 12px; line-height: 1;">⠿</span>
                </div>
                <div class="collapse-btn" @click="toggleGroup(sub.key)">
                  <DownOutlined v-if="expandedGroups.has(sub.key)" />
                  <RightOutlined v-else />
                </div>
                <span class="subgroup-name" @click="toggleGroup(sub.key)">{{ sub.label }}</span>
                <div class="group-stats">
                  <div class="stat-item">
                    <span class="stat-icon">📅</span>
                    <span>{{ sub.tenants.length }}</span>
                  </div>
                </div>
                <div style="display: flex; gap: 6px; margin-left: auto;">
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

              <div v-show="expandedGroups.has(sub.key)" class="group-body">
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
                        <a-tag color="blue">{{ getRegionLabel(record.ociRegion) }}</a-tag>
                        <div style="font-size: 11px; color: var(--text-sub); margin-top: 2px">{{ record.ociRegion }}</div>
                      </template>
                      <template v-if="column.key === 'taskStatus'">
                        <a-badge v-if="record.hasRunningTask" status="processing" text="执行开机任务中" />
                        <span v-else style="color: #999">无开机任务</span>
                      </template>
                      <template v-if="column.key === 'planType'">
                        <a-tag :color="record.planType === 'PAYG' ? 'green' : record.planType === 'FREE' ? 'orange' : 'default'">{{ record.planType || '获取中...' }}</a-tag>
                      </template>
                      <template v-if="column.key === 'action'">
                        <a-space>
                          <a-button type="link" size="small" @click="openTenantInfo(record)">详情</a-button>
                          <a-button type="link" size="small" @click="showEditModal(record)">编辑</a-button>
                          <a-button type="link" size="small" @click="openDomainMgmt(record)">管理</a-button>
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
                        <div class="mobile-card-row"><span class="label">区域</span><a-tag color="blue" style="margin:0">{{ getRegionLabel(r.ociRegion) }}</a-tag></div>
                        <div class="mobile-card-row">
                          <span class="label">任务</span>
                          <a-badge v-if="r.hasRunningTask" status="processing" text="执行中" />
                          <span v-else style="color: #999">无</span>
                        </div>
                      </div>
                      <div class="mobile-card-actions">
                        <a-button type="link" size="small" @click="openTenantInfo(r)">详情</a-button>
                        <a-button type="link" size="small" @click="showEditModal(r)">编辑</a-button>
                        <a-button type="link" size="small" @click="openDomainMgmt(r)">管理</a-button>
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
        <div v-if="!groupTree.length && !loading" style="text-align: center; padding: 40px; color: var(--text-sub)">
          暂无租户配置
        </div>
      </template>
    </a-spin>

    <!-- 分组管理器弹窗 -->
    <a-modal v-model:open="groupMgrVisible" title="管理分组" :width="isMobile ? '100%' : 700" :footer="null" centered>
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
    <a-modal v-model:open="renameVisible" title="重命名分组" @ok="handleRenameGroup" :confirm-loading="renameLoading" centered>
      <a-input v-model:value="renameNewName" placeholder="输入新分组名" @press-enter="handleRenameGroup" />
    </a-modal>

    <!-- 添加子分组弹窗 -->
    <a-modal v-model:open="addSubVisible" title="添加子分组" @ok="handleAddSubGroupConfirm" centered>
      <p style="color: var(--text-sub); margin-bottom: 8px">父分组: <a-tag color="blue">{{ addSubParent }}</a-tag></p>
      <a-input v-model:value="addSubName" placeholder="输入子分组名称" @press-enter="handleAddSubGroupConfirm" />
    </a-modal>

    <!-- 新增/编辑弹窗（内嵌快速导入） -->
    <a-modal v-model:open="modalVisible" :title="editingId ? '编辑配置' : '新增配置'" :width="isMobile ? '100%' : 680" @ok="handleSubmit" :confirm-loading="submitLoading" :mask-closable="false">
      <a-form :model="formState" layout="vertical">
        <!-- 快速导入区域（仅新增时显示） -->
        <a-collapse v-if="!editingId" :bordered="false" :active-key="['import']" style="margin-bottom: 16px; background: #f6f8fa; border-radius: 8px">
          <a-collapse-panel key="import" header="⚡ 快速导入 — 粘贴 OCI 配置自动填充">
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
          </a-collapse-panel>
        </a-collapse>

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
            :filter-option="(input: string, option: any) => {
              const val = (option?.value || '').toLowerCase()
              const label = (regionMap[option?.value] || '').toLowerCase()
              const kw = input.toLowerCase()
              return val.includes(kw) || label.includes(kw)
            }">
            <a-select-option v-for="r in regions" :key="r" :value="r">{{ getRegionLabel(r) }}（{{ r }}）</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="私钥文件 (.pem)">
          <a-upload-dragger
            :before-upload="handleUpload"
            :max-count="1"
            accept=".pem"
            :file-list="fileList"
            @remove="handleRemoveFile"
          >
            <p class="ant-upload-drag-icon"><InboxOutlined /></p>
            <p class="ant-upload-text">点击或拖拽 PEM 文件到此处上传</p>
          </a-upload-dragger>
          <span v-if="formState.ociKeyPath && !fileList.length" style="color: #888; font-size: 12px; margin-top: 4px; display: block">
            已有密钥：{{ formState.ociKeyPath }}
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

    <!-- 租户详情弹窗 -->
    <a-modal v-model:open="tenantInfoVisible" title="租户信息" :width="isMobile ? '100%' : 680"
      :footer="null" centered :bodyStyle="{ maxHeight: '70vh', overflow: 'auto' }">
      <a-spin :spinning="tenantInfoLoading">
        <a-descriptions :column="1" bordered size="small" style="margin-top: 8px">
          <a-descriptions-item label="当前配置">
            <span style="color: var(--primary); font-weight: 600">{{ tenantInfoData.configName || '—' }}</span>
          </a-descriptions-item>
          <a-descriptions-item label="租户名称">{{ tenantInfoData.tenantName || '—' }}</a-descriptions-item>
          <a-descriptions-item label="homeRegionKey">{{ tenantInfoData.homeRegionKey || '—' }}</a-descriptions-item>
          <a-descriptions-item label="upiIdcsCompatibilityLayerEndpoint">
            <span style="word-break: break-all">{{ tenantInfoData.upiIdcsCompatibilityLayerEndpoint || '—' }}</span>
          </a-descriptions-item>
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
            <a-tag v-if="tenantInfoData.planType" :color="tenantInfoData.planType === 'FREE' ? 'default' : 'green'">
              {{ tenantInfoData.planType === 'FREE' ? '免费套餐 (Free Tier)' : tenantInfoData.planType }}
            </a-tag>
            <span v-else>—</span>
          </a-descriptions-item>
          <a-descriptions-item label="账户类型">
            <a-tag v-if="tenantInfoData.accountType" color="orange">{{ tenantInfoData.accountType }}</a-tag>
            <span v-else>—</span>
          </a-descriptions-item>
          <a-descriptions-item label="升级状态">
            <a-tag v-if="tenantInfoData.upgradeState" color="purple">{{ tenantInfoData.upgradeState }}</a-tag>
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
        </a-descriptions>

        <a-divider style="margin: 14px 0">账务信息</a-divider>

        <a-spin :spinning="billingLoading">
          <template v-if="billingData">
            <a-row :gutter="12">
              <a-col :xs="24" :sm="24">
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
            </a-row>

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
          <a-empty v-else description="未加载账务信息" />
        </a-spin>
      </a-spin>
    </a-modal>

    <!-- 域管理弹窗 -->
    <a-modal v-model:open="domainMgmtVisible" :title="'域管理 — ' + (domainMgmtTenant?.username || '')"
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
        <a-tab-pane key="quotas" tab="账户配额">
          <a-alert type="info" show-icon style="margin-bottom: 10px"
            message="账户配额为租户级数据，与域选择无关" />
          <a-space style="margin-bottom: 12px">
            <a-button @click="loadQuotas" :loading="quotasLoading">
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
              <template #default="{ text, record }">
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
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { PlusOutlined, ThunderboltOutlined, InboxOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import type { UploadFile } from 'ant-design-vue'
import { getTenantList, addTenant, updateTenant, removeTenant, uploadKey, getTenantFullInfo, getTenantBillingSummary, downloadInvoicePdf, getDomainSettings, updateMfa, updatePasswordExpiry, getAuditLogs, getServiceQuotas, getTenantGroups, createGroup, renameGroup, deleteGroup, saveGroupOrder, unlockAuthFactors, getAuthFactors, updateAuthFactors } from '../api/tenant'
import { sendVerifyCode } from '../api/system'
import { RightOutlined, DownOutlined, SettingOutlined, FolderOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import AuditLogTable from '../components/AuditLogTable.vue'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'

dayjs.extend(utc)

const router = useRouter()

const regionMap: Record<string, string> = {
  'us-ashburn-1': '美国东部（阿什本）',
  'us-phoenix-1': '美国西部（凤凰城）',
  'us-sanjose-1': '美国西部（圣何塞）',
  'us-chicago-1': '美国中西部（芝加哥）',
  'ca-toronto-1': '加拿大东南部（多伦多）',
  'ca-montreal-1': '加拿大东南部（蒙特利尔）',
  'eu-frankfurt-1': '德国中部（法兰克福）',
  'eu-zurich-1': '瑞士北部（苏黎世）',
  'eu-amsterdam-1': '荷兰西北部（阿姆斯特丹）',
  'eu-marseille-1': '法国南部（马赛）',
  'eu-stockholm-1': '瑞典北部（斯德哥尔摩）',
  'eu-milan-1': '意大利西北部（米兰）',
  'eu-paris-1': '法国中部（巴黎）',
  'eu-madrid-1': '西班牙中部（马德里）',
  'uk-london-1': '英国南部（伦敦）',
  'uk-cardiff-1': '英国西部（加的夫）',
  'ap-tokyo-1': '日本东部（东京）',
  'ap-osaka-1': '日本中部（大阪）',
  'ap-seoul-1': '韩国中部（首尔）',
  'ap-chuncheon-1': '韩国北部（春川）',
  'ap-mumbai-1': '印度西部（孟买）',
  'ap-hyderabad-1': '印度南部（海得拉巴）',
  'ap-singapore-1': '新加坡（新加坡）',
  'ap-sydney-1': '澳大利亚东部（悉尼）',
  'ap-melbourne-1': '澳大利亚东南部（墨尔本）',
  'sa-saopaulo-1': '巴西东部（圣保罗）',
  'sa-vinhedo-1': '巴西东南部（维涅杜）',
  'sa-santiago-1': '智利中部（圣地亚哥）',
  'me-jeddah-1': '沙特阿拉伯西部（吉达）',
  'me-dubai-1': '阿联酋东部（迪拜）',
  'me-abudhabi-1': '阿联酋中部（阿布扎比）',
  'me-riyadh-1': '沙特阿拉伯中部（利雅得）',
  'af-johannesburg-1': '南非中部（约翰内斯堡）',
  'il-jerusalem-1': '以色列中部（耶路撒冷）',
  'mx-queretaro-1': '墨西哥中部（克雷塔罗）',
  'mx-monterrey-1': '墨西哥东北部（蒙特雷）',
  'us-saltlake-2': '美国中西部（盐湖城）',
  'us-langley-1': '美国政府（兰利）',
  'us-luke-1': '美国政府（卢克）',
  'us-gov-ashburn-1': '美国政府（阿什本）',
  'us-gov-chicago-1': '美国政府（芝加哥）',
  'us-gov-phoenix-1': '美国政府（凤凰城）',
}
const regions = Object.keys(regionMap)

function getRegionLabel(code: string) {
  return regionMap[code] || code
}

function formatUtcCnDate(v: any): string {
  if (!v) return '—'
  const d = dayjs.utc(v)
  if (!d.isValid()) return '—'
  return `${d.year()}年${d.month() + 1}月${d.date()}日（UTC）`
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
  { title: '区域', dataIndex: 'ociRegion', key: 'ociRegion', width: 220 },
  { title: '开机任务', key: 'taskStatus', width: 140 },
  { title: '账户类型', dataIndex: 'planType', key: 'planType', width: 130 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 270 },
]

const loading = ref(false)
const submitLoading = ref(false)
const tableData = ref<any[]>([])
const searchText = ref('')
const selectedRowKeys = ref<string[]>([])
const modalVisible = ref(false)
const editingId = ref('')
const pagination = reactive({ current: 1, pageSize: 10, total: 0 })
const importText = ref('')
const fileList = ref<UploadFile[]>([])

const formState = reactive({
  username: '', ociTenantId: '', ociUserId: '',
  ociFingerprint: '', ociRegion: '', ociKeyPath: '',
  groupLevel1: '' as string, groupLevel2: '' as string,
})

const groupData = ref<{ level1: string[]; level2: Record<string, string[]> }>({ level1: [], level2: {} })
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

const tenantInfoVisible = ref(false)
const tenantInfoLoading = ref(false)
const tenantInfoData = ref<any>({})
const billingLoading = ref(false)
const billingData = ref<any | null>(null)
function checkMobile() { isMobile.value = window.innerWidth < 768 }

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
  message.success('已解析并填充，请上传私钥后提交')
}

async function loadData() {
  loading.value = true
  try {
    const res = await getTenantList({
      current: 1,
      size: 9999,
      keyword: searchText.value,
    })
    tableData.value = res.data.records || []
    pagination.total = res.data.total || 0
  } catch (e: any) {
    message.error(e?.message || '加载租户列表失败')
  } finally {
    loading.value = false
  }
  loadGroups()
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
}

function showAddModal() {
  editingId.value = ''
  resetForm()
  modalVisible.value = true
}

function showEditModal(record: any) {
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

async function loadQuotas() {
  quotasLoading.value = true
  try {
    const res = await getServiceQuotas({ id: domainMgmtTenant.value.id })
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

async function loadGroups() {
  try {
    const res = await getTenantGroups()
    groupData.value = res.data || { level1: [], level2: {} }
  } catch {}
}

const level2Options = computed(() => {
  if (!formState.groupLevel1) return []
  return groupData.value.level2[formState.groupLevel1] || []
})

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
  saveGroupOrder({ order: names }).then(() => loadGroups()).catch(() => {})
}

function onDragEnd() {
  resetDrag()
}

function resetDrag() {
  dragFromIndex.value = -1
  dragOverIndex.value = -1
}

function toggleGroup(key: string) {
  if (expandedGroups.value.has(key)) {
    expandedGroups.value.delete(key)
  } else {
    expandedGroups.value.add(key)
  }
}

async function openTenantInfo(record: any) {
  tenantInfoData.value = { configName: record.username }
  tenantInfoVisible.value = true
  tenantInfoLoading.value = true
  billingLoading.value = true
  billingData.value = null
  try {
    const [res, bill] = await Promise.all([
      getTenantFullInfo({ id: record.id }),
      getTenantBillingSummary({ id: record.id, limits: { invoices: 5, payments: 5, usageStatements: 3 } }),
    ])
    const d = res.data || {}
    tenantInfoData.value = d
    billingData.value = bill.data || null
    if (record?.id) {
      const row = tableData.value.find((r: any) => r.id === record.id)
      if (row) {
        if (d.planType) row.planType = d.planType
        if (d.tenantName) row.tenantName = d.tenantName
      }
    }
  } catch (e: any) {
    message.error(e?.message || '获取租户详情失败')
  } finally {
    tenantInfoLoading.value = false
    billingLoading.value = false
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
    loadData()
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
        loadData()
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
    loadData()
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
    loadData()
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
    loadData()
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
    if (pendingFile) {
      const fd = new FormData()
      fd.append('file', pendingFile)
      const uploadRes = await uploadKey(fd)
      keyPath = uploadRes.data
    }

    if (!keyPath && !editingId.value) {
      message.warning('请上传私钥文件')
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
    loadData()
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
    loadData()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
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
        selectedRowKeys.value = []
        loadData()
      } catch (e: any) {
        message.error(e?.message || '删除失败')
      }
    },
  })
}

onMounted(async () => {
  await loadData()
  for (const g of groupTree.value) {
    expandedGroups.value.add(g.key)
    if (g.children) {
      for (const c of g.children) expandedGroups.value.add(c.key)
    }
  }
  window.addEventListener('resize', checkMobile)
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
/* 快速导入折叠区：覆盖内联浅色背景，随主题切换 */
:deep(.ant-collapse) {
  background: var(--bg-card) !important;
  border: 1px solid var(--border) !important;
  border-radius: var(--radius-sm) !important;
  box-shadow: var(--shadow-card) !important;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
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
.group-name {
  font-weight: 600;
  font-size: 16px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin: 0;
}
.subgroup-name {
  font-weight: 600;
  font-size: 15px;
  cursor: pointer;
}
.group-stats {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--text-sub, #999);
  margin-left: 8px;
}
.group-stats .stat-item {
  display: flex;
  align-items: center;
  gap: 4px;
}
.group-stats .stat-icon { font-size: 14px; }
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
  .group-name { font-size: 13px; max-width: 140px; }
  .subgroup-card { margin-left: 16px; }
  .group-bar-right { flex-wrap: wrap; gap: 4px; }
  .drag-handle, .collapse-btn { width: 32px; height: 32px; }
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
</style>
