<template>
  <div>
    <div class="table-toolbar">
      <a-space v-if="!isMobile">
        <a-button type="primary" @click="showCreateModal">
          <template #icon><PlusOutlined /></template>创建开机任务
        </a-button>
        <a-button @click="loadData" :loading="loading">
          <template #icon><ReloadOutlined /></template>刷新
        </a-button>
        <a-input-search v-model:value="searchKeyword" placeholder="搜索租户/区域/架构..."
          style="width: 260px" allow-clear @search="handleSearch" enter-button="搜索" />
        <a-select v-model:value="filterStatus" placeholder="状态筛选" style="width: 130px"
          allow-clear @change="handleSearch">
          <a-select-option value="">全部</a-select-option>
          <a-select-option value="RUNNING">运行中</a-select-option>
          <a-select-option value="STOPPED">已停止</a-select-option>
          <a-select-option value="COMPLETED">已完成</a-select-option>
          <a-select-option value="FAILED">已失败</a-select-option>
        </a-select>
        <a-button :disabled="!selectedRowKeys.length" :loading="batchLoading"
          @click="handleBatchStop" danger>
          批量暂停
        </a-button>
        <a-button :disabled="!selectedRowKeys.length" :loading="batchLoading"
          type="primary" @click="handleBatchResume">
          批量启动
        </a-button>
      </a-space>
      <div v-else class="mobile-task-toolbar">
        <div class="mobile-task-toolbar-row">
          <a-button type="primary" block @click="showCreateModal">
            <template #icon><PlusOutlined /></template>创建
          </a-button>
          <a-button block @click="loadData" :loading="loading">
            <template #icon><ReloadOutlined /></template>刷新
          </a-button>
        </div>
        <a-input-search v-model:value="searchKeyword" placeholder="搜索租户/区域/架构"
          allow-clear @search="handleSearch" enter-button="搜索" />
        <a-select v-model:value="filterStatus" placeholder="状态筛选"
          allow-clear @change="handleSearch">
          <a-select-option value="">全部状态</a-select-option>
          <a-select-option value="RUNNING">运行中</a-select-option>
          <a-select-option value="STOPPED">已停止</a-select-option>
          <a-select-option value="COMPLETED">已完成</a-select-option>
          <a-select-option value="FAILED">已失败</a-select-option>
        </a-select>
        <div class="mobile-task-batchbar">
          <span class="mobile-task-selected">已选 {{ selectedRowKeys.length }} 个</span>
          <a-space size="small">
            <a-button size="small" :disabled="!selectedRowKeys.length" :loading="batchLoading"
              @click="handleBatchStop" danger>
              批量暂停
            </a-button>
            <a-button size="small" :disabled="!selectedRowKeys.length" :loading="batchLoading"
              type="primary" @click="handleBatchResume">
              批量启动
            </a-button>
            <a-button v-if="selectedRowKeys.length" size="small" type="link" @click="clearSelectedTasks">
              清空
            </a-button>
          </a-space>
        </div>
      </div>
    </div>

    <a-table v-if="!isMobile" :columns="columns" :data-source="tableData" :loading="loading" :pagination="pagination"
      row-key="id" @change="handleTableChange" size="middle"
      :row-selection="{ selectedRowKeys, onChange: (keys: TaskRowKey[]) => selectedRowKeys = keys }"
      :row-class-name="(record: any) => record.status !== 'RUNNING' ? 'row-inactive' : ''">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'architecture'">
          <a-tag :color="isTaskArmArchitecture(record.architecture) ? 'green' : 'blue'">{{ formatTaskArchitectureLabel(record.architecture) }}</a-tag>
        </template>
        <template v-if="column.key === 'progress'">
          <span>
            <span style="font-weight: 600">{{ progressDisplaySuccess(record) }}</span>
            <span style="color: var(--text-sub)"> / {{ record.createNumbers }}</span>
            <a-tag v-if="record.progressOverTarget" color="orange" style="margin-left: 6px"
              :title="progressOverTargetTip">超目标</a-tag>
          </span>
        </template>
        <template v-if="column.key === 'status'">
          <a-badge :status="badgeStatusMap[record.status] || 'default'"
            :text="statusMap[record.status] || record.status" />
        </template>
        <template v-if="column.key === 'config'">
          {{ record.ocpus }}C / {{ record.memory }}G / {{ formatTaskConfigDisk(record.disk, record.vpusPerGB) }}
        </template>
        <template v-if="column.key === 'action'">
          <a-space>
            <a-button v-if="record.status === 'RUNNING' || record.status === 'STOPPED'" type="link" size="small" @click="showEditModal(record)">修改</a-button>
            <a-popconfirm v-if="record.status === 'RUNNING'" title="确定停止任务?" @confirm="handleStop(record)">
              <a-button type="link" danger size="small" :loading="actionLoading[record.id]">停止</a-button>
            </a-popconfirm>
            <a-popconfirm v-if="record.status === 'STOPPED'" title="确定恢复任务?" @confirm="handleResume(record)">
              <a-button type="link" size="small" :loading="actionLoading[record.id]">继续</a-button>
            </a-popconfirm>
            <a-button v-if="record.status !== 'RUNNING'" type="link" size="small" @click="showDetailModal(record)">详情</a-button>
            <a-popconfirm v-if="record.status !== 'RUNNING'" title="确定删除此记录?" @confirm="handleDelete(record)">
              <a-button type="link" danger size="small" :loading="actionLoading[record.id]">删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 移动端卡片列表 -->
    <a-spin v-else :spinning="loading">
      <a-empty v-if="!loading && tableData.length === 0" description="暂无任务" />
      <div class="mobile-card-list">
      <div v-for="task in tableData" :key="task.id" class="mobile-card"
        :class="{ 'mobile-card-selected': isTaskSelected(task.id) }"
        :style="{ opacity: task.status !== 'RUNNING' ? 0.65 : 1 }">
        <div class="mobile-card-header">
          <span class="mobile-card-title">
            <a-tag :color="isTaskArmArchitecture(task.architecture) ? 'green' : 'blue'" style="margin-right: 6px">{{ formatTaskArchitectureLabel(task.architecture) }}</a-tag>
            {{ task.username }}
          </span>
          <a-badge :status="badgeStatusMap[task.status] || 'default'" :text="statusMap[task.status] || task.status" />
        </div>
        <div class="mobile-card-body">
          <div class="mobile-card-row"><span class="label">区域</span><span class="value">{{ task.ociRegion }}</span></div>
          <div class="mobile-card-row"><span class="label">配置</span><span class="value">{{ task.ocpus }}C / {{ task.memory }}G / {{ formatTaskConfigDisk(task.disk, task.vpusPerGB) }}</span></div>
          <div class="mobile-card-row"><span class="label">进度</span>
            <span class="value">{{ progressDisplaySuccess(task) }} / {{ task.createNumbers }}
              <a-tag v-if="task.progressOverTarget" color="orange" size="small" style="margin-left: 4px" :title="progressOverTargetTip">超</a-tag>
            </span>
          </div>
          <div class="mobile-card-row"><span class="label">间隔</span><span class="value">{{ task.intervalSeconds }}s</span></div>
          <div class="mobile-card-row"><span class="label">尝试</span><span class="value">{{ task.attemptCount }} 次</span></div>
          <div class="mobile-card-row"><span class="label">创建</span><span class="value">{{ task.createTime }}</span></div>
        </div>
        <div class="mobile-card-actions">
          <div class="mobile-card-action-links">
            <a-button v-if="task.status === 'RUNNING' || task.status === 'STOPPED'" type="link" size="small" @click="showEditModal(task)">修改</a-button>
            <a-popconfirm v-if="task.status === 'RUNNING'" title="确定停止？" @confirm="handleStop(task)">
              <a-button type="link" danger size="small" :loading="actionLoading[task.id]">停止</a-button>
            </a-popconfirm>
            <a-popconfirm v-if="task.status === 'STOPPED'" title="确定恢复？" @confirm="handleResume(task)">
              <a-button type="link" size="small" :loading="actionLoading[task.id]">继续</a-button>
            </a-popconfirm>
            <a-button v-if="task.status !== 'RUNNING'" type="link" size="small" @click="showDetailModal(task)">详情</a-button>
            <a-popconfirm v-if="task.status !== 'RUNNING'" title="确定删除？" @confirm="handleDelete(task)">
              <a-button type="link" danger size="small" :loading="actionLoading[task.id]">删除</a-button>
            </a-popconfirm>
          </div>
          <a-checkbox
            class="mobile-card-check"
            :checked="isTaskSelected(task.id)"
            :aria-label="`选择任务 ${task.username || task.id}`"
            @change="(e: any) => toggleTaskSelection(task.id, e.target.checked)"
          />
        </div>
      </div>
      </div>
      <a-pagination
        v-if="pagination.total > pagination.pageSize"
        class="mobile-task-pagination"
        size="small"
        :current="pagination.current"
        :page-size="pagination.pageSize"
        :total="pagination.total"
        :show-size-changer="false"
        show-less-items
        @change="handleMobilePageChange"
      />
    </a-spin>

    <a-modal :keyboard="false" v-model:open="createVisible" title="创建开机任务" :width="isMobile ? 'calc(100vw - 24px)' : 600" @ok="handleCreate"
      :confirm-loading="createLoading" :mask-closable="false" :body-style="taskModalBodyStyle"
      centered
      :wrap-class-name="isMobile ? 'task-mobile-modal-wrap' : undefined">
      <a-form :model="createForm" layout="vertical">
        <a-form-item label="选择租户" required>
          <a-select v-model:value="createForm.userId" placeholder="选择租户" show-search option-filter-prop="label"
            @change="onTenantChange">
            <a-select-option v-for="t in tenants" :key="t.id" :value="t.id" :label="t.username">
              {{ t.username }} ({{ t.ociRegion }})
            </a-select-option>
          </a-select>
        </a-form-item>
        <ShapeSeriesPicker
          v-model:architecture="createForm.architecture"
          :shapes="availableShapes"
          :loading="shapesLoading"
          :hint="availableShapes.length ? `查询到 ${availableShapes.length} 个可用 Shape（随租户区域变化）` : ''"
        />
        <a-form-item label="操作系统">
          <a-select v-model:value="createForm.operationSystem">
            <a-select-option value="Ubuntu">Ubuntu（最新版）</a-select-option>
            <a-select-option value="Ubuntu 24.04">Ubuntu 24.04 LTS</a-select-option>
            <a-select-option value="Ubuntu 22.04">Ubuntu 22.04 LTS</a-select-option>
            <a-select-option value="Ubuntu 20.04">Ubuntu 20.04 LTS</a-select-option>
            <a-select-option value="Oracle Linux">Oracle Linux</a-select-option>
            <a-select-option value="CentOS">CentOS</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-if="createDenseIoTiers?.length" label="DenseIO 档位">
          <a-select v-model:value="createDenseIoTierKey" style="width: 100%">
            <a-select-option v-for="t in createDenseIoTiers" :key="denseIoFlexTierKey(t)" :value="denseIoFlexTierKey(t)">
              {{ formatDenseIoTierLabel(t) }}
            </a-select-option>
          </a-select>
          <div style="color: var(--text-sub); font-size: 12px; margin-top: 4px">
            本地 NVMe 与网络带宽随档位由 OCI 自动配置，与控制台一致
          </div>
        </a-form-item>
        <a-row v-if="!createDenseIoTiers?.length" :gutter="12">
          <a-col :xs="12" :sm="12">
            <a-form-item :label="createOcpuLabel">
              <a-input-number
                :value="createForm.ocpus"
                :min="createShapeLimits.minOcpus"
                :max="createShapeLimits.maxOcpus"
                :step="1"
                :disabled="createBmLocked"
                style="width: 100%"
                @update:value="(v: number | null) => applyTaskOcpusInput(createForm, v, availableShapes)"
                @blur="() => clampTaskShapeResources(createForm, availableShapes)"
              />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="12">
            <a-form-item :label="createMemoryLabel">
              <a-input-number
                :value="createForm.memory"
                :min="createShapeLimits.minMemory"
                :max="createShapeLimits.maxMemory"
                :step="1"
                :disabled="createBmLocked"
                style="width: 100%"
                @update:value="(v: number | null) => applyTaskMemoryInput(createForm, v, availableShapes)"
                @blur="() => clampTaskShapeResources(createForm, availableShapes)"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :xs="12" :sm="12">
            <a-form-item label="磁盘 (GB)">
              <a-input-number v-model:value="createForm.disk" :min="47" :max="200" :step="1" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="12">
            <a-form-item label="VPUs/GB">
              <a-input-number
                v-model:value="createForm.vpusPerGB"
                :min="BOOT_VOLUME_VPUS_MIN"
                :max="BOOT_VOLUME_VPUS_MAX"
                :step="BOOT_VOLUME_VPUS_STEP"
                style="width: 100%"
                @blur="createForm.vpusPerGB = snapBootVpusPerGb(createForm.vpusPerGB)"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :xs="12" :sm="8">
            <a-form-item label="开机数量">
              <a-input-number v-model:value="createForm.createNumbers" :min="1" :max="5" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="8">
            <a-form-item label="重试间隔 (秒)">
              <a-input-number v-model:value="createForm.interval" :min="10" :max="600" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="8">
            <TaskLoginSelector
              v-model:root-password="createForm.rootPassword"
              v-model:login-mode="createForm.loginMode"
              v-model:ssh-public-key="createForm.sshPublicKey"
              :saved-root-password="taskSavedRootPassword"
              :saved-ssh-public-key="taskSavedSshPublicKey"
              placeholder="留空=随机生成"
              @missing="warnTaskCredentialMissing"
            />
          </a-col>
        </a-row>
        <div style="display: flex; align-items: center; gap: 32px; margin-bottom: 16px">
          <span style="display: inline-flex; align-items: center; gap: 8px">
            <a-switch v-model:checked="createForm.assignPublicIp" />
            <span>公网IP</span>
          </span>
          <span style="display: inline-flex; align-items: center; gap: 8px">
            <a-switch v-model:checked="createForm.assignIpv6" />
            <span>IPv6</span>
          </span>
        </div>
        <a-form-item label="自定义开机脚本（cloud-init）">
          <a-textarea v-model:value="createForm.customScript" placeholder="开机后自动执行的 Shell 脚本，留空则不执行&#10;&#10;示例：&#10;apt update && apt install -y docker.io&#10;ufw disable" :auto-size="{ minRows: 3, maxRows: 8 }" />
          <div style="color: var(--text-sub); font-size: 12px; margin-top: 4px">
            脚本会追加在密码设置之后执行，以 root 身份运行
          </div>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 编辑任务弹窗 -->
    <a-modal :keyboard="false" v-model:open="editVisible" title="编辑开机任务" :width="isMobile ? 'calc(100vw - 24px)' : 600" @ok="handleEdit"
      :confirm-loading="editLoading" :mask-closable="false" :body-style="taskModalBodyStyle"
      centered
      :wrap-class-name="isMobile ? 'task-mobile-modal-wrap' : undefined">
      <a-form :model="editForm" layout="vertical">
        <ShapeSeriesPicker
          v-model:architecture="editForm.architecture"
          :shapes="editAvailableShapes"
          :loading="editShapesLoading"
          :hint="editAvailableShapes.length ? `查询到 ${editAvailableShapes.length} 个可用 Shape` : ''"
        />
        <a-form-item label="操作系统">
          <a-select v-model:value="editForm.operationSystem">
            <a-select-option value="Ubuntu">Ubuntu（最新版）</a-select-option>
            <a-select-option value="Ubuntu 24.04">Ubuntu 24.04 LTS</a-select-option>
            <a-select-option value="Ubuntu 22.04">Ubuntu 22.04 LTS</a-select-option>
            <a-select-option value="Ubuntu 20.04">Ubuntu 20.04 LTS</a-select-option>
            <a-select-option value="Oracle Linux">Oracle Linux</a-select-option>
            <a-select-option value="CentOS">CentOS</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-if="editDenseIoTiers?.length" label="DenseIO 档位">
          <a-select v-model:value="editDenseIoTierKey" style="width: 100%">
            <a-select-option v-for="t in editDenseIoTiers" :key="denseIoFlexTierKey(t)" :value="denseIoFlexTierKey(t)">
              {{ formatDenseIoTierLabel(t) }}
            </a-select-option>
          </a-select>
          <div style="color: var(--text-sub); font-size: 12px; margin-top: 4px">
            本地 NVMe 与网络带宽随档位由 OCI 自动配置，与控制台一致
          </div>
        </a-form-item>
        <a-row v-if="!editDenseIoTiers?.length" :gutter="12">
          <a-col :xs="12" :sm="12">
            <a-form-item :label="editOcpuLabel">
              <a-input-number
                :value="editForm.ocpus"
                :min="editShapeLimits.minOcpus"
                :max="editShapeLimits.maxOcpus"
                :step="1"
                :disabled="editBmLocked"
                style="width: 100%"
                @update:value="(v: number | null) => applyTaskOcpusInput(editForm, v, editAvailableShapes)"
                @blur="() => clampTaskShapeResources(editForm, editAvailableShapes)"
              />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="12">
            <a-form-item :label="editMemoryLabel">
              <a-input-number
                :value="editForm.memory"
                :min="editShapeLimits.minMemory"
                :max="editShapeLimits.maxMemory"
                :step="1"
                :disabled="editBmLocked"
                style="width: 100%"
                @update:value="(v: number | null) => applyTaskMemoryInput(editForm, v, editAvailableShapes)"
                @blur="() => clampTaskShapeResources(editForm, editAvailableShapes)"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :xs="12" :sm="12">
            <a-form-item label="磁盘 (GB)">
              <a-input-number v-model:value="editForm.disk" :min="47" :max="200" :step="1" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="12">
            <a-form-item label="VPUs/GB">
              <a-input-number
                v-model:value="editForm.vpusPerGB"
                :min="BOOT_VOLUME_VPUS_MIN"
                :max="BOOT_VOLUME_VPUS_MAX"
                :step="BOOT_VOLUME_VPUS_STEP"
                style="width: 100%"
                @blur="editForm.vpusPerGB = snapBootVpusPerGb(editForm.vpusPerGB)"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :xs="12" :sm="8">
            <a-form-item label="开机数量">
              <a-input-number v-model:value="editForm.createNumbers" :min="1" :max="5" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="12" :sm="8">
            <a-form-item label="重试间隔 (秒)">
              <a-input-number v-model:value="editForm.interval" :min="10" :max="600" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="8">
            <TaskLoginSelector
              v-model:root-password="editForm.rootPassword"
              v-model:login-mode="editForm.loginMode"
              v-model:ssh-public-key="editForm.sshPublicKey"
              :saved-root-password="taskSavedRootPassword"
              :saved-ssh-public-key="taskSavedSshPublicKey"
              placeholder="留空=保持不变"
              @missing="warnTaskCredentialMissing"
            />
          </a-col>
        </a-row>
        <div style="display: flex; align-items: center; gap: 32px; margin-bottom: 16px">
          <span style="display: inline-flex; align-items: center; gap: 8px">
            <a-switch v-model:checked="editForm.assignPublicIp" />
            <span>公网IP</span>
          </span>
          <span style="display: inline-flex; align-items: center; gap: 8px">
            <a-switch v-model:checked="editForm.assignIpv6" />
            <span>IPv6</span>
          </span>
        </div>
        <a-form-item label="自定义开机脚本（cloud-init）">
          <a-textarea v-model:value="editForm.customScript" placeholder="开机后自动执行的 Shell 脚本，留空则不执行" :auto-size="{ minRows: 3, maxRows: 8 }" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 任务详情弹窗 -->
    <a-modal :keyboard="false" v-model:open="detailVisible" title="开机任务详情" :width="isMobile ? 'calc(100vw - 24px)' : 720" :footer="null" :mask-closable="false"
      :body-style="taskModalBodyStyle" :wrap-class-name="isMobile ? 'task-mobile-modal-wrap' : undefined">
      <a-spin :spinning="detailLoading">
        <div v-if="detailData" class="task-detail">
          <!-- 任务元信息 -->
          <div class="detail-section">
            <div class="detail-section-title">任务信息</div>
            <a-descriptions :column="isMobile ? 1 : 2" size="small" bordered>
              <a-descriptions-item label="租户">{{ detailData.username }}</a-descriptions-item>
              <a-descriptions-item label="区域">{{ detailData.ociRegion }}</a-descriptions-item>
              <a-descriptions-item label="架构">
                <a-tag :color="isTaskArmArchitecture(detailData.architecture) ? 'green' : 'blue'">{{ formatTaskArchitectureLabel(detailData.architecture) }}</a-tag>
              </a-descriptions-item>
              <a-descriptions-item label="操作系统">{{ detailData.operationSystem || '—' }}</a-descriptions-item>
              <a-descriptions-item label="配置">{{ detailData.ocpus }}C / {{ detailData.memory }}G / {{ formatTaskConfigDisk(detailData.disk, detailData.vpusPerGB) }}</a-descriptions-item>
              <a-descriptions-item label="进度">
                <span style="font-weight:600">{{ progressDisplaySuccess(detailData) }}</span> / {{ detailData.createNumbers }}
                <a-tag v-if="detailData.progressOverTarget" color="orange" style="margin-left:6px" :title="progressOverTargetTip">超目标</a-tag>
                <div v-if="(detailData.successCount ?? 0) !== (detailData.recordedInstanceCount ?? 0)" style="font-size:12px;color:var(--text-sub);margin-top:4px">
                  计次 {{ detailData.successCount ?? 0 }} 与已记录 {{ detailData.recordedInstanceCount ?? 0 }} 条不一致时，进度分子取较大值展示。
                </div>
              </a-descriptions-item>
              <a-descriptions-item label="公网IP">{{ detailData.assignPublicIp ? '启用' : '禁用' }}</a-descriptions-item>
              <a-descriptions-item label="IPv6">{{ detailData.assignIpv6 ? '启用' : '禁用' }}</a-descriptions-item>
              <a-descriptions-item label="状态">
                <a-badge :status="badgeStatusMap[detailData.status] || 'default'" :text="statusMap[detailData.status] || detailData.status" />
              </a-descriptions-item>
              <a-descriptions-item label="创建时间">{{ detailData.createTime }}</a-descriptions-item>
              <a-descriptions-item label="登录用户">root</a-descriptions-item>
              <a-descriptions-item label="登录方式">
                {{ detailData.loginMode === 'SSH_PUBLIC_KEY' ? 'SSH 公钥' : 'Root 密码' }}
              </a-descriptions-item>
              <a-descriptions-item v-if="detailData.loginMode !== 'SSH_PUBLIC_KEY'" label="Root 密码" :span="isMobile ? 1 : 2">
                <a-typography-text v-if="detailData.rootPassword" copyable>
                  {{ pwdVisible ? detailData.rootPassword : '••••••••' }}
                </a-typography-text>
                <a-button type="link" size="small" @click="pwdVisible = !pwdVisible" style="padding:0;margin-left:8px">
                  {{ pwdVisible ? '隐藏' : '显示' }}
                </a-button>
                <span v-if="!detailData.rootPassword" style="color: var(--text-sub)">—</span>
              </a-descriptions-item>
              <a-descriptions-item v-if="detailData.customScript" label="自定义脚本" :span="isMobile ? 1 : 2">
                <pre class="script-block">{{ detailData.customScript }}</pre>
              </a-descriptions-item>
            </a-descriptions>
          </div>

          <!-- 失败原因 / 已创建实例 -->
          <div v-if="isFailedTask(detailData)" class="detail-section" style="margin-top:16px">
            <div class="detail-section-title">失败原因日志</div>
            <div class="task-failure-log">
              <pre>{{ taskFailureLog(detailData) }}</pre>
            </div>
          </div>
          <div v-else class="detail-section" style="margin-top:16px">
            <div class="detail-section-title">
              已创建实例
              <a-tag color="green" style="margin-left:8px">{{ detailData.instances?.length || 0 }} 台</a-tag>
            </div>
            <a-empty v-if="!detailData.instances || detailData.instances.length === 0" description="暂无成功创建的实例记录" />
            <a-table v-else-if="!isMobile" :data-source="detailData.instances" :pagination="false" size="small" row-key="instanceId">
              <a-table-column title="名称" data-index="instanceName" :ellipsis="true">
                <template #default="{ record }">
                  <a-tooltip :title="record.instanceName">
                    <span>{{ record.instanceName || '—' }}</span>
                  </a-tooltip>
                </template>
              </a-table-column>
              <a-table-column title="规格" key="spec" :width="140">
                <template #default="{ record }">
                  <div style="font-weight:600">{{ record.ocpus }}C / {{ record.memory }}G</div>
                  <div style="font-size:11px;color:var(--text-sub)">{{ formatTaskConfigDisk(record.disk, record.vpusPerGB) }} · {{ record.shape }}</div>
                </template>
              </a-table-column>
              <a-table-column title="公网 IP" data-index="publicIp" :width="160">
                <template #default="{ record }">
                  <a-typography-text v-if="record.publicIp" copyable>{{ record.publicIp }}</a-typography-text>
                  <span v-else style="color: var(--text-sub)">—</span>
                </template>
              </a-table-column>
              <a-table-column title="创建时间" data-index="createdAt" :width="170">
                <template #default="{ record }">{{ formatDateTime(record.createdAt) }}</template>
              </a-table-column>
            </a-table>
            <div v-else class="task-instance-mobile-list">
              <div v-for="instance in detailData.instances" :key="instance.instanceId" class="task-instance-mobile-card">
                <div class="task-instance-mobile-title">{{ instance.instanceName || '—' }}</div>
                <div class="mobile-card-row">
                  <span class="label">规格</span>
                  <span class="value">{{ instance.ocpus }}C / {{ instance.memory }}G</span>
                </div>
                <div class="mobile-card-row">
                  <span class="label">磁盘</span>
                  <span class="value">{{ formatTaskConfigDisk(instance.disk, instance.vpusPerGB) }}</span>
                </div>
                <div class="mobile-card-row">
                  <span class="label">Shape</span>
                  <span class="value">{{ instance.shape || '—' }}</span>
                </div>
                <div class="mobile-card-row">
                  <span class="label">公网 IP</span>
                  <span class="value">
                    <a-typography-text v-if="instance.publicIp" copyable>{{ instance.publicIp }}</a-typography-text>
                    <span v-else style="color: var(--text-sub)">—</span>
                  </span>
                </div>
                <div class="mobile-card-row">
                  <span class="label">创建时间</span>
                  <span class="value">{{ formatDateTime(instance.createdAt) }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'TaskManager' })
import { ref, reactive, computed, onMounted, onUnmounted, watch, type CSSProperties } from 'vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import { getTaskList, createTask, updateTask, stopTask, hasRunningTask, resumeTask, deleteTask, batchStopTask, batchResumeTask, getTaskDetail } from '../api/task'
import { getTenantList } from '../api/tenant'
import { getAvailableShapes } from '../api/instance'
import { getTaskCredential } from '../api/system'
import {
  applyTaskOcpusInput,
  applyTaskMemoryInput,
  applyTaskShapeDefaults,
  clampTaskShapeResources,
  isFixedTaskShapeSpec,
  resolveTaskShapeLimits,
  taskMemoryFieldLabel,
  taskOcpuFieldLabel,
  validateDenseIoFlexTier,
} from '../constants/ociBmShapeSpecs'
import { useDenseIoFlexTier } from '../composables/useDenseIoFlexTier'
import ShapeSeriesPicker from '../components/ShapeSeriesPicker.vue'
import TaskLoginSelector from '../components/TaskLoginSelector.vue'
import {
  BOOT_VOLUME_VPUS_MAX,
  BOOT_VOLUME_VPUS_MIN,
  BOOT_VOLUME_VPUS_STEP,
  formatTaskConfigDisk,
  snapBootVpusPerGb,
} from '../utils/bootVolume'
import {
  TASK_ARM_SHAPE,
  formatTaskArchitectureLabel,
  isTaskArmArchitecture,
  normalizeTaskArchitecture,
} from '../utils/shapeSeries'

const statusMap: Record<string, string> = {
  RUNNING: '运行中', STOPPED: '已停止', COMPLETED: '已完成', FAILED: '已失败',
}
const badgeStatusMap: Record<string, string> = {
  RUNNING: 'processing', STOPPED: 'default', COMPLETED: 'success', FAILED: 'error',
}

const progressOverTargetTip =
  '成功计次与「本任务已记录实例」中较大者超过目标时标红；多开可能产生 OCI 费用，请至控制台与实例页核对。'

/** 进度分子：计次与已记录实例数取大，避免历史把计次改小后仍显示 1/1 而实际多开 */
function progressDisplaySuccess(r: { successCount?: number; recordedInstanceCount?: number } | null | undefined) {
  if (!r) return 0
  return Math.max(r.successCount ?? 0, r.recordedInstanceCount ?? 0)
}

function isFailedTask(task: { status?: string } | null | undefined) {
  return task?.status === 'FAILED'
}

function taskFailureLog(task: { failureReason?: string } | null | undefined) {
  const reason = task?.failureReason?.trim()
  return reason || '该失败任务没有保存到详细失败原因，可能是升级前创建的历史任务。请查看日志页面或 Telegram 推送记录。'
}

const columns = [
  { title: '租户', dataIndex: 'username', key: 'username' },
  { title: 'Region', dataIndex: 'ociRegion', key: 'ociRegion', width: 140 },
  { title: '架构', dataIndex: 'architecture', key: 'architecture', width: 80 },
  { title: '配置', key: 'config', width: 160 },
  { title: '进度', key: 'progress', width: 128 },
  { title: '间隔(s)', dataIndex: 'intervalSeconds', key: 'intervalSeconds', width: 80 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '尝试次数', dataIndex: 'attemptCount', key: 'attemptCount', width: 90 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 200 },
]

type TaskRowKey = string | number

const selectedRowKeys = ref<TaskRowKey[]>([])
const batchLoading = ref(false)
const loading = ref(false)
const createLoading = ref(false)
const shapesLoading = ref(false)
const tableData = ref<any[]>([])
const tenants = ref<any[]>([])
const availableShapes = ref<any[]>([])
const createVisible = ref(false)
const taskSavedRootPassword = ref('')
const taskSavedSshPublicKey = ref('')
const searchKeyword = ref('')
const filterStatus = ref('')
const pagination = reactive({ current: 1, pageSize: 10, total: 0 })
const actionLoading = reactive<Record<string, boolean>>({})
const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }
onMounted(() => window.addEventListener('resize', checkMobile))
onUnmounted(() => window.removeEventListener('resize', checkMobile))
const taskModalBodyStyle = computed<CSSProperties | undefined>(() => isMobile.value
  ? { maxHeight: 'calc(100vh - 156px)', overflowY: 'auto', padding: '14px 12px 16px' }
  : undefined)

function isTaskSelected(id: TaskRowKey) {
  const key = String(id)
  return selectedRowKeys.value.some(k => String(k) === key)
}

function toggleTaskSelection(id: TaskRowKey, checked: boolean) {
  const key = String(id)
  const next = selectedRowKeys.value.filter(k => String(k) !== key)
  selectedRowKeys.value = checked ? [...next, id] : next
}

function clearSelectedTasks() {
  selectedRowKeys.value = []
}

function selectedTaskIds() {
  return selectedRowKeys.value.map(String)
}

const createForm = reactive({
  userId: '', architecture: TASK_ARM_SHAPE, operationSystem: 'Ubuntu',
  ocpus: 1, memory: 6, disk: 50, vpusPerGB: 10, createNumbers: 1, interval: 60, rootPassword: '',
  loginMode: 'PASSWORD', sshPublicKey: '',
  customScript: '', assignPublicIp: true, assignIpv6: false,
})

const createBmLocked = ref(false)
const createShapeLimits = computed(() => resolveTaskShapeLimits(createForm.architecture, availableShapes.value))
const createOcpuLabel = computed(() => taskOcpuFieldLabel(createForm.architecture, availableShapes.value))
const createMemoryLabel = computed(() => taskMemoryFieldLabel(createForm.architecture, availableShapes.value))
const {
  tiers: createDenseIoTiers,
  tierKey: createDenseIoTierKey,
  formatDenseIoTierLabel,
  denseIoFlexTierKey,
} = useDenseIoFlexTier(createForm)

watch(
  () => createForm.architecture,
  (arch) => {
    if (arch == null || arch === undefined) return
    createBmLocked.value = applyTaskShapeDefaults(createForm, availableShapes.value)
    clampTaskShapeResources(createForm, availableShapes.value)
  },
)

watch(
  () => [createForm.ocpus, createForm.memory, createShapeLimits.value] as const,
  () => clampTaskShapeResources(createForm, availableShapes.value),
)

watch(availableShapes, () => {
  if (isFixedTaskShapeSpec(createForm.architecture)) {
    createBmLocked.value = applyTaskShapeDefaults(createForm, availableShapes.value)
  }
})

const editVisible = ref(false)
const editLoading = ref(false)
const editAvailableShapes = ref<any[]>([])
const editShapesLoading = ref(false)
const editBmLocked = ref(false)
const editShapeLimits = computed(() => resolveTaskShapeLimits(editForm.architecture, editAvailableShapes.value))
const editOcpuLabel = computed(() => taskOcpuFieldLabel(editForm.architecture, editAvailableShapes.value))
const editMemoryLabel = computed(() => taskMemoryFieldLabel(editForm.architecture, editAvailableShapes.value))
const editForm = reactive({
  taskId: '',
  userId: '',
  architecture: TASK_ARM_SHAPE, operationSystem: 'Ubuntu',
  ocpus: 1, memory: 6, disk: 50, vpusPerGB: 10, createNumbers: 1, interval: 60, rootPassword: '',
  loginMode: 'PASSWORD', sshPublicKey: '',
  customScript: '', assignPublicIp: true, assignIpv6: false,
})
const {
  tiers: editDenseIoTiers,
  tierKey: editDenseIoTierKey,
} = useDenseIoFlexTier(editForm)

watch(
  () => editForm.architecture,
  (arch) => {
    if (arch == null || arch === undefined) return
    editBmLocked.value = applyTaskShapeDefaults(editForm, editAvailableShapes.value)
    clampTaskShapeResources(editForm, editAvailableShapes.value)
  },
)

watch(
  () => [editForm.ocpus, editForm.memory, editShapeLimits.value] as const,
  () => clampTaskShapeResources(editForm, editAvailableShapes.value),
)

watch(editAvailableShapes, () => {
  if (isFixedTaskShapeSpec(editForm.architecture)) {
    editBmLocked.value = applyTaskShapeDefaults(editForm, editAvailableShapes.value)
  }
})

async function loadEditAvailableShapes(tenantId: string, region?: string, currentArch?: string) {
  if (!tenantId) {
    editAvailableShapes.value = []
    return
  }
  editShapesLoading.value = true
  try {
    const res = await getAvailableShapes({ id: tenantId, ...(region?.trim() ? { region: region.trim() } : {}) })
    let rows = res.data || []
    const arch = normalizeTaskArchitecture(currentArch ?? editForm.architecture)
    if (arch && !rows.some((s: any) => s.shape === arch)) {
      rows = [{ shape: arch, processorDescription: '当前任务' }, ...rows]
    }
    editAvailableShapes.value = rows
  } catch {
    editAvailableShapes.value = []
  } finally {
    editShapesLoading.value = false
  }
}

async function loadTaskCredential() {
  try {
    const res = await getTaskCredential()
    taskSavedRootPassword.value = res.data?.rootPassword || ''
    taskSavedSshPublicKey.value = res.data?.sshPublicKey || ''
  } catch {
    taskSavedRootPassword.value = ''
    taskSavedSshPublicKey.value = ''
  }
}

function warnTaskCredentialMissing(type: 'password' | 'publicKey') {
  Modal.warning({
    title: type === 'password' ? '未设置我的密码' : '未设置我的公钥',
    content: type === 'password'
      ? '请先到系统设置 - 安全设置 - 开机凭据中配置我的密码。'
      : '请先到系统设置 - 安全设置 - 开机凭据中配置我的公钥。',
    zIndex: 1300,
  })
}

async function showEditModal(record: any) {
  void loadTaskCredential()
  Object.assign(editForm, {
    taskId: record.id,
    userId: record.userId || '',
    architecture: normalizeTaskArchitecture(record.architecture),
    operationSystem: record.operationSystem || 'Ubuntu',
    ocpus: record.ocpus,
    memory: record.memory,
    disk: record.disk,
    vpusPerGB: record.vpusPerGB ?? 10,
    createNumbers: record.createNumbers,
    interval: record.intervalSeconds,
    rootPassword: '',
    loginMode: record.loginMode || 'PASSWORD',
    sshPublicKey: record.sshPublicKey || '',
    customScript: record.customScript || '',
    assignPublicIp: record.assignPublicIp ?? true,
    assignIpv6: record.assignIpv6 ?? false,
  })
  editBmLocked.value = applyTaskShapeDefaults(editForm, editAvailableShapes.value)
  editVisible.value = true
  await loadEditAvailableShapes(record.userId, record.ociRegion, record.architecture)
  editBmLocked.value = applyTaskShapeDefaults(editForm, editAvailableShapes.value)
}

async function handleEdit() {
  const denseErr = validateDenseIoFlexTier(editForm.architecture, editForm.ocpus, editForm.memory)
  if (denseErr) {
    message.error(denseErr)
    return
  }
  if (editForm.architecture?.includes('A2.Flex') && editForm.ocpus === 1 && editForm.memory === 1) {
    message.error('比例错误')
    return
  }
  if (editForm.loginMode === 'SSH_PUBLIC_KEY' && !editForm.sshPublicKey) {
    warnTaskCredentialMissing('publicKey')
    return
  }
  const payload = {
    ...editForm,
    architecture: normalizeTaskArchitecture(editForm.architecture),
    vpusPerGB: snapBootVpusPerGb(editForm.vpusPerGB),
  }
  editForm.architecture = payload.architecture
  editForm.vpusPerGB = payload.vpusPerGB
  editLoading.value = true
  try {
    await updateTask(payload)
    message.success('任务已更新')
    editVisible.value = false
    loadData()
  } catch (e: any) {
    message.error(e?.message || '更新任务失败')
  } finally {
    editLoading.value = false
  }
}

watch(editVisible, (open) => {
  if (!open) {
    editAvailableShapes.value = []
    editBmLocked.value = false
  }
})

function generateRandomPwd() {
  const chars = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%'
  let pwd = ''
  for (let i = 0; i < 16; i++) pwd += chars[Math.floor(Math.random() * chars.length)]
  createForm.rootPassword = pwd
  message.success('已生成随机密码')
}

async function loadTenantAvailableShapes(tenantId: string, region?: string) {
  if (!tenantId) return []
  const res = await getAvailableShapes({ id: tenantId, ...(region?.trim() ? { region: region.trim() } : {}) })
  return res.data || []
}

async function onTenantChange(tenantId: string) {
  if (!tenantId) {
    availableShapes.value = []
    return
  }
  shapesLoading.value = true
  try {
    const t = tenants.value.find((x: any) => x.id === tenantId)
    availableShapes.value = await loadTenantAvailableShapes(tenantId, t?.ociRegion)
  } catch {
    availableShapes.value = []
  } finally {
    shapesLoading.value = false
  }
}

function handleSearch() {
  pagination.current = 1
  loadData()
}

async function loadData() {
  loading.value = true
  try {
    const res = await getTaskList({
      current: pagination.current,
      size: pagination.pageSize,
      keyword: searchKeyword.value || undefined,
      status: filterStatus.value || undefined,
    })
    tableData.value = res.data.records || []
    pagination.total = res.data.total || 0
  } catch (e: any) {
    message.error(e?.message || '加载任务列表失败')
  } finally {
    loading.value = false
  }
}

async function loadTenants() {
  try {
    const res = await getTenantList({ current: 1, size: 1000 })
    tenants.value = res.data.records || []
  } catch (e: any) {
    message.error(e?.message || '加载租户失败')
  }
}

function handleTableChange(pag: any) {
  pagination.current = pag.current
  pagination.pageSize = pag.pageSize || pagination.pageSize
  loadData()
}

function handleMobilePageChange(page: number) {
  pagination.current = page
  loadData()
}

function showCreateModal() {
  loadTenants()
  void loadTaskCredential()
  availableShapes.value = []
  Object.assign(createForm, {
    userId: '', architecture: TASK_ARM_SHAPE, operationSystem: 'Ubuntu',
    ocpus: 1, memory: 6, disk: 50, vpusPerGB: 10, createNumbers: 1, interval: 60, rootPassword: '',
    loginMode: 'PASSWORD', sshPublicKey: '',
    customScript: '', assignPublicIp: true, assignIpv6: false,
  })
  createBmLocked.value = false
  createVisible.value = true
}

async function handleCreate() {
  if (createLoading.value) return
  if (!createForm.userId) { message.warning('请选择租户'); return }
  createLoading.value = true
  let waitingDuplicateConfirm = false
  try {
    const denseErr = validateDenseIoFlexTier(createForm.architecture, createForm.ocpus, createForm.memory)
    if (denseErr) {
      message.error(denseErr)
      return
    }
    if (createForm.architecture?.includes('A2.Flex') && createForm.ocpus === 1 && createForm.memory === 1) {
      message.error('比例错误')
      return
    }
    if (createForm.loginMode === 'SSH_PUBLIC_KEY') {
      if (!createForm.sshPublicKey) {
        warnTaskCredentialMissing('publicKey')
        return
      }
      createForm.rootPassword = ''
    } else if (!createForm.rootPassword) {
      generateRandomPwd()
    }
    const payload = {
      ...createForm,
      architecture: normalizeTaskArchitecture(createForm.architecture),
      vpusPerGB: snapBootVpusPerGb(createForm.vpusPerGB),
    }
    createForm.architecture = payload.architecture
    createForm.vpusPerGB = payload.vpusPerGB

    try {
      const checkRes = await hasRunningTask({ userId: createForm.userId })
      if (checkRes.data === true) {
        waitingDuplicateConfirm = true
        Modal.confirm({
          title: '重复任务提醒',
          content: '该账户已有正在运行的开机任务，是否仍要重复提交？',
          okText: '继续创建',
          cancelText: '取消',
          onOk: () => doCreate(payload),
          onCancel: () => { createLoading.value = false },
          afterClose: () => { createLoading.value = false },
        })
        return
      }
    } catch {}

    await doCreate(payload)
  } finally {
    if (!waitingDuplicateConfirm) createLoading.value = false
  }
}

async function doCreate(payload: any) {
  try {
    await createTask(payload)
    message.success('任务创建成功')
    createVisible.value = false
    loadData()
  } catch (e: any) {
    message.error(e?.message || '创建任务失败')
  }
}

async function handleStop(record: any) {
  actionLoading[record.id] = true
  try {
    await stopTask({ taskId: record.id, userId: record.userId })
    message.success('任务已停止')
    loadData()
  } catch (e: any) {
    message.error(e?.message || '停止任务失败')
  } finally {
    actionLoading[record.id] = false
  }
}

async function handleResume(record: any) {
  actionLoading[record.id] = true
  try {
    await resumeTask({ taskId: record.id })
    message.success('任务已恢复运行')
    loadData()
  } catch (e: any) {
    message.error(e?.message || '恢复任务失败')
  } finally {
    actionLoading[record.id] = false
  }
}

async function handleBatchStop() {
  if (!selectedRowKeys.value.length) return
  Modal.confirm({
    title: '批量暂停',
    content: `确定暂停选中的 ${selectedRowKeys.value.length} 个任务？`,
    async onOk() {
      batchLoading.value = true
      try {
        const res = await batchStopTask({ taskIds: selectedTaskIds() })
        message.success(`已暂停 ${res.data} 个任务`)
        selectedRowKeys.value = []
        loadData()
      } catch (e: any) {
        message.error(e?.message || '批量暂停失败')
      } finally {
        batchLoading.value = false
      }
    }
  })
}

async function handleBatchResume() {
  if (!selectedRowKeys.value.length) return
  Modal.confirm({
    title: '批量启动',
    content: `确定启动选中的 ${selectedRowKeys.value.length} 个任务？`,
    async onOk() {
      batchLoading.value = true
      try {
        const res = await batchResumeTask({ taskIds: selectedTaskIds() })
        message.success(`已启动 ${res.data} 个任务`)
        selectedRowKeys.value = []
        loadData()
      } catch (e: any) {
        message.error(e?.message || '批量启动失败')
      } finally {
        batchLoading.value = false
      }
    }
  })
}

async function handleDelete(record: any) {
  actionLoading[record.id] = true
  try {
    await deleteTask({ taskId: record.id })
    message.success('记录已删除')
    toggleTaskSelection(record.id, false)
    loadData()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  } finally {
    actionLoading[record.id] = false
  }
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailData = ref<any>(null)
const pwdVisible = ref(false)

async function showDetailModal(record: any) {
  detailVisible.value = true
  detailLoading.value = true
  pwdVisible.value = false
  detailData.value = null
  try {
    const res = await getTaskDetail({ taskId: record.id })
    detailData.value = res.data
  } catch (e: any) {
    message.error(e?.message || '加载任务详情失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

function formatDateTime(v: any) {
  if (!v) return '—'
  const s = String(v).replace('T', ' ')
  return s.length > 19 ? s.substring(0, 19) : s
}

onMounted(() => loadData())
</script>

<style scoped>
.table-toolbar { margin-bottom: 16px; transition: var(--trans); }
:deep(.row-inactive td) {
  color: var(--text-sub) !important;
}
.mobile-task-toolbar {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mobile-task-toolbar-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
}
.mobile-task-toolbar :deep(.ant-input-search),
.mobile-task-toolbar :deep(.ant-select) {
  width: 100% !important;
}
.mobile-task-batchbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 38px;
  padding: 7px 9px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card);
}
.mobile-task-batchbar :deep(.ant-space) {
  flex-wrap: wrap;
  justify-content: flex-end;
}
.mobile-task-selected {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-sub);
}
.mobile-card-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.mobile-card {
  padding: 12px 12px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card);
  box-shadow: var(--shadow-card);
  transition: border-color 0.16s ease, box-shadow 0.16s ease, background-color 0.16s ease;
}
.mobile-card-selected {
  border-color: var(--primary);
  box-shadow:
    inset 0 0 0 1px color-mix(in srgb, var(--primary) 36%, transparent),
    var(--shadow-card);
}
.mobile-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.mobile-card-header :deep(.ant-badge) {
  flex-shrink: 0;
}
.mobile-card-title {
  min-width: 0;
  font-weight: 600;
  color: var(--text-main);
  word-break: break-all;
}
.mobile-card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.mobile-card-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  min-height: 20px;
  font-size: 13px;
}
.mobile-card-row .label {
  flex-shrink: 0;
  color: var(--text-sub);
}
.mobile-card-row .value {
  min-width: 0;
  color: var(--text-main);
  text-align: right;
  word-break: break-all;
}
.mobile-card-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}
.mobile-card-action-links {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
  min-width: 0;
}
.mobile-card-action-links :deep(.ant-btn-link) {
  height: 24px;
  padding: 0 5px;
}
.mobile-card-check {
  flex-shrink: 0;
  padding: 2px 0 2px 10px;
}
.mobile-card-check :deep(.ant-checkbox-inner) {
  width: 18px;
  height: 18px;
  border-color: color-mix(in srgb, var(--text-sub) 55%, transparent);
  background: color-mix(in srgb, var(--bg-card) 88%, transparent);
}
.mobile-task-pagination {
  display: flex;
  justify-content: center;
  margin-top: 14px;
}
.task-instance-mobile-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.task-instance-mobile-card {
  padding: 10px 11px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card);
}
.task-instance-mobile-title {
  margin-bottom: 8px;
  font-weight: 600;
  color: var(--text-main);
  word-break: break-all;
}
.task-detail .detail-section-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 10px;
  color: var(--text-main);
}
.task-detail .script-block {
  margin: 0;
  padding: 8px 10px;
  background: var(--bg-sub, rgba(255,255,255,0.04));
  border-radius: 4px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
}
.task-failure-log {
  border: 1px solid color-mix(in srgb, #ff4d4f 38%, var(--border));
  border-radius: 8px;
  background: color-mix(in srgb, #ff4d4f 9%, var(--bg-card));
}
.task-failure-log pre {
  margin: 0;
  padding: 11px 12px;
  color: var(--text-main);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow: auto;
}
@media (max-width: 768px) {
  .table-toolbar { margin-bottom: 12px; }
  :global(.task-mobile-modal-wrap .ant-modal) {
    top: 12px;
    max-width: calc(100vw - 24px);
    padding-bottom: 12px;
  }
  :global(.task-mobile-modal-wrap .ant-modal-content) {
    display: flex;
    flex-direction: column;
    max-height: calc(100vh - 24px);
    border-radius: 8px;
  }
  :global(.task-mobile-modal-wrap .ant-modal-body) {
    flex: 1 1 auto;
  }
  :global(.task-mobile-modal-wrap .ant-modal-footer) {
    flex-shrink: 0;
    padding: 10px 12px;
  }
  :global(.task-mobile-modal-wrap .ant-modal-header) {
    flex-shrink: 0;
    padding-right: 32px;
  }
}
</style>
