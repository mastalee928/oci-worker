<template>
  <div class="notify-section-panel announcement-push-panel">
    <a-tabs v-model:active-key="announcementTab" size="small" @change="handleAnnouncementTabChange">
      <a-tab-pane key="config" tab="推送配置">
        <a-spin :spinning="announcementConfigLoading || announcementTenantsLoading">
          <a-form layout="vertical" class="announcement-config-form">
            <div class="announcement-config-head">
              <a-form-item label="推送范围" class="announcement-event-form-item">
                <a-select
                  v-model:value="announcementPushConfig.eventTypes"
                  mode="multiple"
                  :show-search="false"
                  :options="announcementEventTypeOptions"
                  placeholder="选择需要推送的事件"
                />
              </a-form-item>
              <a-form-item label="启用推送" class="announcement-enable-form-item">
                <a-switch v-model:checked="announcementPushConfig.enabled" checked-children="开" un-checked-children="关" />
              </a-form-item>
            </div>

            <a-row :gutter="16">
              <a-col :xs="24" :md="12">
                <a-form-item label="扫描频率">
                  <a-select v-model:value="announcementPushConfig.frequencyMinutes" :options="announcementFrequencyOptions" />
                </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
                <a-form-item label="公告保留">
                  <a-select v-model:value="announcementPushConfig.recordRetentionDays" :options="announcementRecordRetentionOptions" />
                </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
                <a-form-item label="历史保留">
                  <a-select v-model:value="announcementPushConfig.batchRetentionDays" :options="announcementBatchRetentionOptions" />
                </a-form-item>
              </a-col>
            </a-row>

            <div class="tenant-picker-shell">
              <div class="tenant-picker-head">
                <div>
                  <div class="tenant-picker-title">接收租户</div>
                  <div class="tenant-picker-sub">已选择 {{ announcementSelectedTenantCount }} / {{ announcementTenants.length }} 个租户</div>
                </div>
                <a-button size="small" @click="tenantPickerVisible = true">选择租户</a-button>
              </div>
              <div class="tenant-chip-row">
                <a-tag v-if="!announcementPushConfig.selectedTenantIds.length">尚未选择</a-tag>
                <a-tag v-for="tenant in announcementSelectedTenantPreview" :key="tenant.id">
                  {{ tenant.tenantName || tenant.username || tenant.id }}
                </a-tag>
                <a-tag v-if="announcementPushConfig.selectedTenantIds.length > announcementSelectedTenantPreview.length">
                  等 {{ announcementPushConfig.selectedTenantIds.length }} 个
                </a-tag>
              </div>
            </div>

            <a-space wrap>
              <a-button type="primary" :loading="announcementSaveLoading" @click="saveAnnouncementPushConfig">保存云公告推送</a-button>
              <a-button :loading="announcementScanLoading" @click="triggerAnnouncementScan">立即扫描</a-button>
            </a-space>
          </a-form>
        </a-spin>
      </a-tab-pane>

      <a-tab-pane key="inbox" tab="公告收件箱">
        <AnnouncementInboxPanel
          v-model:range="announcementInboxRange"
          v-model:dates="announcementInboxDates"
          v-model:event-types="announcementInboxEventTypes"
          v-model:keyword="announcementInboxKeyword"
          :inbox="announcementInbox"
          :loading="announcementInboxLoading"
          :time-range-options="announcementTimeRangeOptions"
          :event-filter-options="announcementEventFilterOptions"
          :format-date-time="formatDateTime"
          @range-change="handleAnnouncementRangeChange"
          @filters-change="loadAnnouncementInbox(1)"
          @search="loadAnnouncementInbox(1)"
          @refresh="loadAnnouncementInbox(announcementInbox.current, true)"
          @page-change="loadAnnouncementInbox"
          @open-detail="openAnnouncementDetail"
          @mark="markAnnouncement"
        />
      </a-tab-pane>

      <a-tab-pane key="history" tab="推送历史">
        <a-spin :spinning="announcementBatchLoading">
          <a-empty v-if="!announcementBatches.records.length" description="暂无推送历史" />
          <div v-else class="announcement-list">
            <div v-for="batch in announcementBatches.records" :key="batch.id || batch.batchId" class="announcement-item">
              <div class="announcement-item-main">
                <div class="announcement-summary">{{ batch.batchId || '-' }}</div>
                <div class="announcement-meta">
                  {{ formatDateTime(batch.pushedAt || batch.createTime) }} · {{ batch.status || '-' }} · {{ batch.announcementCount || 0 }} 条公告 · {{ batch.tenantCount || 0 }} 个租户
                </div>
                <div v-if="batch.errorMessage" class="announcement-window">{{ batch.errorMessage }}</div>
              </div>
            </div>
          </div>
        </a-spin>
      </a-tab-pane>

      <a-tab-pane key="status" tab="扫描状态">
        <a-spin :spinning="announcementStatusLoading">
          <a-alert
            v-if="announcementScanRunning"
            class="announcement-scan-alert"
            type="info"
            show-icon
            message="云公告正在扫描中，完成后会自动更新成功租户、失败租户和最近错误。"
          />
          <a-descriptions :column="isMobile ? 1 : 2" bordered size="small">
            <a-descriptions-item label="当前状态">
              <a-tag :color="getAnnouncementStatusTagColor(announcementStatus.status)">
                {{ formatAnnouncementStatus(announcementStatus.status) }}
              </a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="本次开始">{{ formatDateTime(announcementStatus.scanStartedAt) }}</a-descriptions-item>
            <a-descriptions-item label="最近扫描">{{ formatDateTime(announcementStatus.lastScanAt) }}</a-descriptions-item>
            <a-descriptions-item label="下次扫描">{{ formatDateTime(announcementStatus.nextScanAt) }}</a-descriptions-item>
            <a-descriptions-item label="成功租户">{{ announcementStatus.successTenants ?? 0 }}</a-descriptions-item>
            <a-descriptions-item label="失败租户">{{ announcementStatus.failedTenants ?? 0 }}</a-descriptions-item>
            <a-descriptions-item label="最近错误">{{ announcementStatus.lastError || '-' }}</a-descriptions-item>
          </a-descriptions>
          <div style="margin-top: 14px">
            <a-button
              :loading="announcementScanLoading || announcementScanRunning"
              :disabled="announcementScanRunning"
              @click="triggerAnnouncementScan"
            >
              {{ announcementScanRunning ? '扫描中' : '立即扫描' }}
            </a-button>
          </div>
        </a-spin>
      </a-tab-pane>
    </a-tabs>

    <AnnouncementTenantPickerModal
      v-model:open="tenantPickerVisible"
      v-model:search="announcementTenantSearch"
      v-model:active-group-key="activeAnnouncementGroupKey"
      v-model:tenant-picker-page="tenantPickerPage"
      v-model:tenant-selected-page="tenantSelectedPage"
      :is-mobile="isMobile"
      :group-options="announcementGroupOptions"
      :filtered-tenants="filteredAnnouncementTenants"
      :selected-tenants="announcementSelectedTenants"
      :paged-filtered-tenants="pagedFilteredAnnouncementTenants"
      :paged-selected-tenants="pagedAnnouncementSelectedTenants"
      :selected-tenant-ids="announcementPushConfig.selectedTenantIds"
      :page-size="tenantPickerPageSize"
      @add-filtered-tenants="addFilteredAnnouncementTenants"
      @clear-tenants="clearAnnouncementTenants"
      @toggle-tenant="toggleAnnouncementTenant"
    />

    <a-modal
      v-model:open="announcementDetailVisible"
      title="公告详情"
      :width="isMobile ? '100%' : 760"
      :footer="null"
      :keyboard="false"
    >
      <a-spin :spinning="announcementDetailLoading">
        <a-empty v-if="!announcementDetail.aggregateKey" description="暂无详情" />
        <div v-else class="announcement-detail">
          <h3>{{ announcementDetail.summary || '-' }}</h3>
          <p class="announcement-meta">{{ announcementDetail.timeWindowText || '无维护时间窗口' }}</p>
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item label="公告类型">{{ announcementDetail.announcementType || '-' }}</a-descriptions-item>
            <a-descriptions-item label="影响服务">{{ (announcementDetail.services || []).join('、') || '-' }}</a-descriptions-item>
            <a-descriptions-item label="影响区域">{{ (announcementDetail.affectedRegions || []).join('、') || '-' }}</a-descriptions-item>
            <a-descriptions-item label="影响租户">{{ announcementDetail.tenantCount || 0 }}</a-descriptions-item>
          </a-descriptions>
          <div v-if="announcementDetail.liveDetail?.detail?.description" class="announcement-live-detail">
            {{ announcementDetail.liveDetail.detail.description }}
          </div>
          <div class="tenant-impact-list">
            <div v-for="tenant in announcementDetail.tenants || []" :key="tenant.tenantId + tenant.announcementId" class="tenant-impact-row">
              <span>{{ tenant.tenantName || tenant.tenantId }}</span>
              <a-tag v-if="tenant.read" color="green">已读</a-tag>
              <a-tag v-if="tenant.ignored">已忽略</a-tag>
            </div>
          </div>
        </div>
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { toRef, watch } from 'vue'
import AnnouncementInboxPanel from './AnnouncementInboxPanel.vue'
import AnnouncementTenantPickerModal from './AnnouncementTenantPickerModal.vue'
import { useAnnouncementPush } from '../../composables/useAnnouncementPush'

defineOptions({ name: 'AnnouncementPushPanel' })

const props = defineProps<{
  isMobile: boolean
}>()

const emit = defineEmits<{
  (e: 'announcementEnabled'): void
}>()

const {
  announcementTab,
  announcementSaveLoading,
  announcementScanLoading,
  announcementConfigLoading,
  announcementTenantsLoading,
  announcementStatusLoading,
  announcementInboxLoading,
  announcementBatchLoading,
  announcementDetailLoading,
  tenantPickerVisible,
  announcementDetailVisible,
  announcementTenantSearch,
  announcementInboxKeyword,
  announcementInboxRange,
  announcementInboxDates,
  announcementInboxEventTypes,
  announcementTenants,
  activeAnnouncementGroupKey,
  tenantPickerPage,
  tenantSelectedPage,
  tenantPickerPageSize,
  announcementStatus,
  announcementDetail,
  announcementInbox,
  announcementBatches,
  announcementPushConfig,
  announcementEventTypeOptions,
  announcementEventFilterOptions,
  announcementTimeRangeOptions,
  announcementFrequencyOptions,
  announcementRecordRetentionOptions,
  announcementBatchRetentionOptions,
  announcementGroupOptions,
  filteredAnnouncementTenants,
  announcementSelectedTenantCount,
  announcementSelectedTenants,
  announcementSelectedTenantPreview,
  announcementScanRunning,
  pagedFilteredAnnouncementTenants,
  pagedAnnouncementSelectedTenants,
  saveAnnouncementPushConfig,
  triggerAnnouncementScan,
  loadAnnouncementInbox,
  handleAnnouncementTabChange,
  openAnnouncementDetail,
  markAnnouncement,
  toggleAnnouncementTenant,
  addFilteredAnnouncementTenants,
  clearAnnouncementTenants,
  handleAnnouncementRangeChange,
  formatDateTime,
  formatAnnouncementStatus,
  resetTenantPickerPage,
  normalizeTenantPickerPage,
  normalizeTenantSelectedPage,
} = useAnnouncementPush({
  isMobile: toRef(props, 'isMobile'),
  onAnnouncementEnabled: () => emit('announcementEnabled'),
})

watch([announcementTenantSearch, activeAnnouncementGroupKey], resetTenantPickerPage)
watch(announcementSelectedTenantCount, normalizeTenantSelectedPage)
watch(tenantPickerPageSize, () => {
  normalizeTenantPickerPage()
  normalizeTenantSelectedPage()
})

function getAnnouncementStatusTagColor(status: any) {
  if (announcementScanRunning.value) return 'processing'
  const key = String(status || '').toUpperCase()
  if (key === 'FAILED') return 'error'
  if (key === 'PARTIAL') return 'warning'
  if (key === 'SUCCESS' || key === 'BASELINE') return 'success'
  return 'default'
}
</script>

<style scoped>
.notify-section-panel {
  max-width: 100%;
}
.announcement-push-panel {
  width: 100%;
}
.announcement-config-form {
  max-width: 860px;
}
.announcement-config-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: start;
}
.announcement-event-form-item {
  min-width: 0;
}
.announcement-enable-form-item {
  min-width: 104px;
}
.announcement-enable-form-item :deep(.ant-form-item-control-input-content) {
  display: flex;
  justify-content: flex-end;
}
.tenant-picker-shell {
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  padding: 14px;
  margin-bottom: 16px;
  background: var(--input-bg, rgba(255, 255, 255, 0.03));
}
.tenant-picker-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.tenant-picker-title {
  font-weight: 600;
  color: var(--text-main);
}
.tenant-picker-sub,
.announcement-meta {
  font-size: 12px;
  color: var(--text-sub);
}
.tenant-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.announcement-scan-alert {
  margin-bottom: 12px;
}
.announcement-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.announcement-item {
  content-visibility: auto;
  contain-intrinsic-size: 104px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  background: var(--input-bg, rgba(255, 255, 255, 0.03));
}
.announcement-item-main {
  min-width: 0;
  flex: 1;
}
.announcement-summary {
  margin-top: 7px;
  color: var(--text-main);
  font-weight: 600;
  line-height: 1.45;
  word-break: break-word;
}
.announcement-window {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-sub);
  white-space: pre-line;
}
.announcement-detail h3 {
  margin-top: 0;
  color: var(--text-main);
}
.announcement-live-detail {
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  color: var(--text-main);
  background: var(--input-bg, rgba(255, 255, 255, 0.03));
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 280px;
  overflow: auto;
}
.tenant-impact-list {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tenant-impact-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
}

@media (max-width: 768px) {
  .announcement-item,
  .tenant-picker-head {
    flex-direction: column;
    align-items: stretch;
  }
  .announcement-config-head {
    grid-template-columns: 1fr;
    gap: 0;
  }
  .announcement-enable-form-item :deep(.ant-form-item-control-input-content) {
    justify-content: flex-start;
  }
}
</style>
