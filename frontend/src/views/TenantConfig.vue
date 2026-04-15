<template>
  <div>
    <div class="table-toolbar">
      <a-space wrap>
        <a-input-search v-model:value="searchText" placeholder="搜索租户" allow-clear @search="loadData" style="width: 200px" />
        <a-select v-model:value="groupFilterL1" placeholder="一级分组" allow-clear style="width: 140px"
          @change="groupFilterL2 = ''">
          <a-select-option v-for="g in groupData.level1" :key="g" :value="g">{{ g }}</a-select-option>
        </a-select>
        <a-select v-if="groupFilterL1" v-model:value="groupFilterL2" placeholder="二级分组" allow-clear style="width: 140px">
          <a-select-option v-for="g in filterLevel2Options" :key="g" :value="g">{{ g }}</a-select-option>
        </a-select>
        <a-button type="primary" @click="showAddModal">
          <template #icon><PlusOutlined /></template>新增配置
        </a-button>
        <a-button @click="groupMgmtVisible = true">
          <template #icon><AppstoreOutlined /></template>分组管理
        </a-button>
        <a-button danger :disabled="!selectedRowKeys.length" @click="handleBatchDelete">
          批量删除
        </a-button>
      </a-space>
    </div>

    <!-- 分组视图：当不做筛选时按分组折叠展示 -->
    <template v-if="!groupFilterL1 && !searchText">
      <a-spin :spinning="loading">
        <div v-for="group in groupTree" :key="group.key" class="group-section">
          <div class="group-header" @click="toggleGroup(group.key)">
            <span class="group-expand-icon">
              <DownOutlined v-if="expandedGroups.has(group.key)" />
              <RightOutlined v-else />
            </span>
            <FolderOutlined style="margin-right: 6px; color: var(--primary)" />
            <span class="group-title">{{ group.label }}</span>
            <a-badge :count="group.tenants.length + (group.children?.reduce((s: number, c: GroupNode) => s + c.tenants.length, 0) || 0)"
              :number-style="{ backgroundColor: 'var(--primary)' }" style="margin-left: 8px" />
          </div>
          <div v-show="expandedGroups.has(group.key)" class="group-body">
            <!-- 直接属于一级分组的租户 -->
            <a-table v-if="group.tenants.length" :columns="columns" :data-source="group.tenants" :pagination="false"
              row-key="id" size="small" style="margin-bottom: 8px">
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

            <!-- 二级分组 -->
            <template v-if="group.children">
              <div v-for="sub in group.children" :key="sub.key" class="subgroup-section">
                <div class="subgroup-header" @click="toggleGroup(sub.key)">
                  <span class="group-expand-icon">
                    <DownOutlined v-if="expandedGroups.has(sub.key)" />
                    <RightOutlined v-else />
                  </span>
                  <FolderOutlined style="margin-right: 4px; font-size: 12px; color: var(--text-sub)" />
                  <span class="subgroup-title">{{ sub.label }}</span>
                  <a-badge :count="sub.tenants.length" :number-style="{ backgroundColor: '#8c8c8c' }" style="margin-left: 6px" />
                </div>
                <div v-show="expandedGroups.has(sub.key)" style="padding-left: 16px">
                  <a-table :columns="columns" :data-source="sub.tenants" :pagination="false"
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
                </div>
              </div>
            </template>
          </div>
        </div>
        <div v-if="!groupTree.length && !loading" style="text-align: center; padding: 40px; color: var(--text-sub)">
          暂无租户配置
        </div>
      </a-spin>
    </template>

    <!-- 筛选/搜索模式：平铺表格 -->
    <template v-else>
      <a-table
        :columns="columns"
        :data-source="groupedTableData"
        :loading="loading"
        :row-selection="{ selectedRowKeys, onChange: onSelectChange }"
        :pagination="pagination"
        row-key="id"
        @change="handleTableChange"
        size="middle"
      >
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
    </template>

    <!-- 分组管理弹窗 -->
    <a-modal v-model:open="groupMgmtVisible" title="分组管理" :width="isMobile ? '100%' : 520" :footer="null" centered>
      <a-divider orientation="left" style="font-size: 13px">一级分组</a-divider>
      <div style="display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 12px">
        <a-tag v-for="g in groupData.level1" :key="g" closable @close="removeGroupL1(g)" color="blue" style="font-size: 13px; padding: 4px 10px">
          {{ g }}
        </a-tag>
      </div>
      <a-space style="margin-bottom: 16px">
        <a-input v-model:value="newGroupL1" placeholder="新一级分组名称" style="width: 200px" @press-enter="addGroupL1" />
        <a-button type="primary" size="small" @click="addGroupL1">添加</a-button>
      </a-space>

      <a-divider orientation="left" style="font-size: 13px">二级分组</a-divider>
      <div v-for="(subs, parent) in groupData.level2" :key="parent" style="margin-bottom: 8px">
        <div style="font-weight: 500; margin-bottom: 4px; color: var(--primary)">{{ parent }}</div>
        <div style="display: flex; flex-wrap: wrap; gap: 6px; margin-left: 12px">
          <a-tag v-for="s in subs" :key="s" closable @close="removeGroupL2(parent as string, s)" style="font-size: 13px; padding: 4px 10px">
            {{ s }}
          </a-tag>
        </div>
      </div>
      <a-space style="margin-top: 8px">
        <a-select v-model:value="newGroupL2Parent" placeholder="所属一级分组" style="width: 140px">
          <a-select-option v-for="g in groupData.level1" :key="g" :value="g">{{ g }}</a-select-option>
        </a-select>
        <a-input v-model:value="newGroupL2" placeholder="新二级分组名称" style="width: 160px" @press-enter="addGroupL2" />
        <a-button type="primary" size="small" @click="addGroupL2">添加</a-button>
      </a-space>
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
          <a-select v-model:value="formState.groupLevel1" placeholder="选择或留空" allow-clear show-search
            @change="formState.groupLevel2 = ''">
            <a-select-option v-for="g in groupData.level1" :key="g" :value="g">{{ g }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-if="formState.groupLevel1" label="二级分组">
          <a-select v-model:value="formState.groupLevel2" placeholder="选择或留空" allow-clear show-search>
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
          <a-descriptions-item label="订阅开始时间">{{ tenantInfoData.subscriptionStartTime || '—' }}</a-descriptions-item>
        </a-descriptions>
      </a-spin>
    </a-modal>

    <!-- 域管理弹窗 -->
    <a-modal v-model:open="domainMgmtVisible" :title="'域管理 — ' + (domainMgmtTenant?.username || '')"
      :width="isMobile ? '100%' : 780" :footer="null" centered :bodyStyle="{ maxHeight: '75vh', overflow: 'auto' }">
      <a-tabs v-model:activeKey="domainTab">
        <a-tab-pane key="security" tab="安全策略">
          <a-spin :spinning="domainSettingsLoading">
            <a-descriptions :column="1" bordered size="small">
              <a-descriptions-item label="MFA 多因素认证">
                <a-switch v-model:checked="domainMfaEnabled"
                  :loading="mfaUpdating"
                  checked-children="已启用" un-checked-children="已关闭"
                  @change="handleMfaChange" />
              </a-descriptions-item>
              <a-descriptions-item label="密码过期天数">
                <a-space>
                  <a-input-number v-model:value="domainPwdExpiryDays" :min="0" :max="365" style="width: 120px" />
                  <span style="color: var(--text-sub); font-size: 12px">0 = 永不过期</span>
                  <a-button type="primary" size="small" @click="handlePwdExpiryChange" :loading="pwdExpiryUpdating">保存</a-button>
                </a-space>
              </a-descriptions-item>
            </a-descriptions>
          </a-spin>
        </a-tab-pane>
        <a-tab-pane key="logs" tab="登录日志">
          <a-button @click="loadAuditLogs" :loading="auditLogsLoading" style="margin-bottom: 12px">
            <template #icon><ReloadOutlined /></template>加载最近7天登录日志
          </a-button>
          <a-table :data-source="auditLogs" :loading="auditLogsLoading" size="small" :pagination="{ pageSize: 20 }" row-key="eventTime">
            <a-table-column title="时间" data-index="eventTime" key="eventTime" :width="180">
              <template #default="{ text }">
                <span style="font-size: 12px">{{ text ? text.replace('T', ' ').substring(0, 19) : '—' }}</span>
              </template>
            </a-table-column>
            <a-table-column title="用户" data-index="principalName" key="principalName" :ellipsis="true" />
            <a-table-column title="IP" data-index="ipAddress" key="ipAddress" :width="140" />
            <a-table-column title="事件" data-index="eventName" key="eventName" :ellipsis="true" />
            <a-table-column title="状态" data-index="responseStatus" key="responseStatus" :width="80">
              <template #default="{ text }">
                <a-tag :color="text === '200' ? 'green' : 'red'">{{ text || '—' }}</a-tag>
              </template>
            </a-table-column>
          </a-table>
        </a-tab-pane>
        <a-tab-pane key="quotas" tab="账户配额">
          <a-space style="margin-bottom: 12px">
            <a-button @click="loadQuotas" :loading="quotasLoading">
              <template #icon><ReloadOutlined /></template>查询配额
            </a-button>
            <a-input-search v-model:value="quotaSearch" placeholder="搜索服务/配额名" allow-clear style="width: 220px" />
          </a-space>
          <a-table :data-source="filteredQuotas" :loading="quotasLoading" size="small"
            :pagination="{ pageSize: 20 }" row-key="(r: any) => r.serviceName + r.limitName + r.availabilityDomain">
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
        </a-tab-pane>
      </a-tabs>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { PlusOutlined, ThunderboltOutlined, InboxOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import type { UploadFile } from 'ant-design-vue'
import { getTenantList, addTenant, updateTenant, removeTenant, uploadKey, getTenantFullInfo, getDomainSettings, updateMfa, updatePasswordExpiry, getAuditLogs, getServiceQuotas, getTenantGroups } from '../api/tenant'
import { FolderOutlined, RightOutlined, DownOutlined, EditOutlined, DeleteOutlined, AppstoreOutlined } from '@ant-design/icons-vue'

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
const groupFilterL1 = ref<string>('')
const groupFilterL2 = ref<string>('')
const groupMgmtVisible = ref(false)
const newGroupL1 = ref('')
const newGroupL2Parent = ref('')
const newGroupL2 = ref('')

let pendingFile: File | null = null
const isMobile = ref(window.innerWidth < 768)

const tenantInfoVisible = ref(false)
const tenantInfoLoading = ref(false)
const tenantInfoData = ref<any>({})
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
    groupLevel2: record.groupLevel2 || '',
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
const domainMfaEnabled = ref(false)
const mfaUpdating = ref(false)
const domainPwdExpiryDays = ref(0)
const pwdExpiryUpdating = ref(false)
const auditLogsLoading = ref(false)
const auditLogs = ref<any[]>([])
const quotasLoading = ref(false)
const quotasList = ref<any[]>([])
const quotaSearch = ref('')

async function openDomainMgmt(record: any) {
  domainMgmtTenant.value = record
  domainTab.value = 'security'
  domainMfaEnabled.value = false
  domainPwdExpiryDays.value = 0
  auditLogs.value = []
  domainMgmtVisible.value = true
  domainSettingsLoading.value = true
  try {
    const res = await getDomainSettings({ id: record.id })
    domainMfaEnabled.value = res.data?.mfaEnabled === true
    domainPwdExpiryDays.value = res.data?.passwordExpiresAfterDays ?? 0
  } catch (e: any) {
    message.error(e?.message || '获取域设置失败')
  } finally {
    domainSettingsLoading.value = false
  }
}

async function handleMfaChange(checked: boolean) {
  mfaUpdating.value = true
  try {
    await updateMfa({ id: domainMgmtTenant.value.id, enabled: checked })
    message.success(checked ? 'MFA 已启用' : 'MFA 已关闭')
  } catch (e: any) {
    domainMfaEnabled.value = !checked
    message.error(e?.message || '更新 MFA 失败')
  } finally {
    mfaUpdating.value = false
  }
}

async function handlePwdExpiryChange() {
  pwdExpiryUpdating.value = true
  try {
    await updatePasswordExpiry({ id: domainMgmtTenant.value.id, days: domainPwdExpiryDays.value })
    message.success('密码过期策略已更新')
  } catch (e: any) {
    message.error(e?.message || '更新密码策略失败')
  } finally {
    pwdExpiryUpdating.value = false
  }
}

async function loadAuditLogs() {
  auditLogsLoading.value = true
  try {
    const res = await getAuditLogs({ id: domainMgmtTenant.value.id })
    auditLogs.value = res.data || []
    if (!auditLogs.value.length) {
      message.info('未找到登录相关日志')
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

const filterLevel2Options = computed(() => {
  if (!groupFilterL1.value) return []
  return groupData.value.level2[groupFilterL1.value] || []
})

const groupedTableData = computed(() => {
  let data = tableData.value
  if (groupFilterL1.value) {
    data = data.filter((r: any) => r.groupLevel1 === groupFilterL1.value)
    if (groupFilterL2.value) {
      data = data.filter((r: any) => r.groupLevel2 === groupFilterL2.value)
    }
  }
  return data
})

interface GroupNode {
  label: string
  key: string
  children?: GroupNode[]
  tenants: any[]
}

const groupTree = computed<GroupNode[]>(() => {
  const all = tableData.value
  const ungrouped = all.filter((r: any) => !r.groupLevel1)
  const grouped = all.filter((r: any) => !!r.groupLevel1)

  const l1Map = new Map<string, any[]>()
  for (const r of grouped) {
    const list = l1Map.get(r.groupLevel1) || []
    list.push(r)
    l1Map.set(r.groupLevel1, list)
  }

  const nodes: GroupNode[] = []

  for (const [l1, items] of l1Map) {
    const withL2 = items.filter(r => !!r.groupLevel2)
    const withoutL2 = items.filter(r => !r.groupLevel2)

    const l2Map = new Map<string, any[]>()
    for (const r of withL2) {
      const list = l2Map.get(r.groupLevel2) || []
      list.push(r)
      l2Map.set(r.groupLevel2, list)
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

  if (ungrouped.length > 0) {
    nodes.push({ label: '未分组', key: '__ungrouped__', tenants: ungrouped })
  }

  return nodes
})

const expandedGroups = ref<Set<string>>(new Set())

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
  try {
    const res = await getTenantFullInfo({ id: record.id })
    tenantInfoData.value = res.data || {}
  } catch (e: any) {
    message.error(e?.message || '获取租户详情失败')
  } finally {
    tenantInfoLoading.value = false
  }
}

function addGroupL1() {
  const name = newGroupL1.value.trim()
  if (!name) return
  if (groupData.value.level1.includes(name)) {
    message.warning('该分组已存在')
    return
  }
  groupData.value.level1.push(name)
  newGroupL1.value = ''
}

function removeGroupL1(name: string) {
  groupData.value.level1 = groupData.value.level1.filter(g => g !== name)
  delete groupData.value.level2[name]
}

function addGroupL2() {
  if (!newGroupL2Parent.value) {
    message.warning('请选择所属一级分组')
    return
  }
  const name = newGroupL2.value.trim()
  if (!name) return
  if (!groupData.value.level2[newGroupL2Parent.value]) {
    groupData.value.level2[newGroupL2Parent.value] = []
  }
  if (groupData.value.level2[newGroupL2Parent.value].includes(name)) {
    message.warning('该分组已存在')
    return
  }
  groupData.value.level2[newGroupL2Parent.value].push(name)
  newGroupL2.value = ''
}

function removeGroupL2(parent: string, name: string) {
  if (groupData.value.level2[parent]) {
    groupData.value.level2[parent] = groupData.value.level2[parent].filter(g => g !== name)
    if (!groupData.value.level2[parent].length) {
      delete groupData.value.level2[parent]
    }
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
  groupTree.value.forEach(g => {
    expandedGroups.value.add(g.key)
    g.children?.forEach(c => expandedGroups.value.add(c.key))
  })
  window.addEventListener('resize', checkMobile)
})
onUnmounted(() => window.removeEventListener('resize', checkMobile))
</script>

<style scoped>
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
  margin-bottom: 4px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
  overflow: hidden;
  background: var(--bg-card, #fff);
  transition: var(--trans);
}
.group-header {
  display: flex;
  align-items: center;
  padding: 10px 16px;
  cursor: pointer;
  user-select: none;
  background: var(--bg-card, #fafafa);
  border-bottom: 1px solid var(--border);
  transition: background 0.2s;
}
.group-header:hover {
  background: var(--bg-hover, #f0f5ff);
}
.group-expand-icon {
  width: 16px;
  margin-right: 8px;
  font-size: 12px;
  color: var(--text-sub);
}
.group-title {
  font-weight: 600;
  font-size: 14px;
}
.group-body {
  padding: 8px 12px;
}
.subgroup-section {
  margin-bottom: 4px;
  border: 1px solid var(--border);
  border-radius: 6px;
  overflow: hidden;
}
.subgroup-header {
  display: flex;
  align-items: center;
  padding: 6px 12px;
  cursor: pointer;
  user-select: none;
  background: var(--bg-card, #fafbfc);
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}
.subgroup-header:hover {
  background: var(--bg-hover, #f0f5ff);
}
.subgroup-title {
  font-weight: 500;
  color: var(--text-sub);
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
