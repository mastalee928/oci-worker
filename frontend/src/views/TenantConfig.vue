<template>
  <div class="tenant-config-root">
    <TenantConfigListPanel
      v-model:search-text="searchText"
      :normalized-search-text="normalizedSearchText"
      :loading="loading"
      :is-mobile="isMobile"
      :columns="columns"
      :table-data="tableData"
      :display-groups="displayGroups"
      :selected-row-keys="selectedRowKeys"
      :tenant-table-scroll-x="tenantTableScrollX"
      :tenant-mobile-virtual-max-height="tenantMobileVirtualMaxHeight"
      :tenant-mobile-search-reset-key="tenantMobileSearchResetKey"
      :expanded-groups="expandedGroups"
      :group-tree="groupTree"
      :group-colors="groupColors"
      :drag-over-index="dragOverIndex"
      :drag-over-pos="dragOverPos"
      :drag-from-index="dragFromIndex"
      :sub-drag-parent="subDragParent"
      :sub-drag-over-index="subDragOverIndex"
      :sub-drag-over-pos="subDragOverPos"
      :sub-drag-from-index="subDragFromIndex"
      :display-tenant-name="displayTenantName"
      :get-oci-region-display-name="getOciRegionDisplayName"
      :format-tenant-added-time="formatTenantAddedTime"
      :format-plan-badge="formatPlanBadge"
      :plan-type-badge-class="planTypeBadgeClass"
      :plan-type-badge-style="planTypeBadgeStyle"
      :plan-summary-tag-class="planSummaryTagClass"
      :group-total-count="groupTotalCount"
      :get-plan-counts="getPlanCounts"
      :tenant-row-key="tenantRowKey"
      :should-virtualize-tenant-mobile-cards="shouldVirtualizeTenantMobileCards"
      :tenant-group-virtual-reset-key="tenantGroupVirtualResetKey"
      @search-tenants="onSearchTenants"
      @open-group-manager="openGroupManager"
      @show-add-modal="showAddModal"
      @open-batch-move-modal="openBatchMoveModal"
      @batch-delete="handleBatchDelete"
      @select-change="onSelectChange"
      @show-edit-modal="showEditModal"
      @open-tenant-mgmt="openTenantMgmt"
      @open-domain-mgmt="openDomainMgmt"
      @go-user-management="goUserManagement"
      @delete-tenant="handleDelete"
      @toggle-group="toggleGroup"
      @add-sub-group="handleAddSubGroup"
      @rename-group="openRenameGroup"
      @delete-group="handleDeleteGroup"
      @drag-over="onDragOver"
      @drop="onDrop"
      @drag-start="onDragStart"
      @drag-end="onDragEnd"
      @sub-drag-over="onSubDragOver"
      @sub-drop="onSubDrop"
      @sub-drag-start="onSubDragStart"
      @sub-drag-end="onSubDragEnd"
    />
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

    <TenantBatchMoveModal
      v-model:open="batchMoveVisible"
      v-model:level1="batchMoveG1"
      v-model:level2="batchMoveG2"
      :loading="batchMoveLoading"
      :is-mobile="isMobile"
      :selected-count="selectedRowKeys.length"
      :level1-options="batchMoveLevel1Options"
      :level2-options="batchMoveLevel2Options"
      :filter-option="filterGroupOption"
      :on-confirm="confirmBatchMove"
    />

    <TenantGroupManagerModal
      v-model:open="groupMgrVisible"
      v-model:create-form-visible="createGroupFormVisible"
      v-model:create-name="createGroupName"
      v-model:create-level="createGroupLevel"
      v-model:create-parent="createGroupParent"
      :is-mobile="isMobile"
      :group-tree="groupTree"
      :group-colors="groupColors"
      :group-data="groupData"
      :create-loading="createGroupLoading"
      :group-total-count="groupTotalCount"
      @open-create-form="openCreateGroupForm"
      @create-group="handleCreateGroup"
      @mgr-add-sub="handleMgrAddSub"
      @rename-group="openRenameGroup"
      @delete-group="handleMgrDeleteGroup"
    />

    <TenantRenameGroupModal
      v-model:open="renameVisible"
      v-model:name="renameNewName"
      :loading="renameLoading"
      :on-confirm="handleRenameGroup"
    />

    <TenantAddSubGroupModal
      v-model:open="addSubVisible"
      v-model:name="addSubName"
      :parent="addSubParent"
      :on-confirm="handleAddSubGroupConfirm"
    />

    <TenantConfigFormModal
      v-model:open="modalVisible"
      v-model:import-text="importText"
      v-model:key-input-mode="keyInputMode"
      v-model:pem-paste-text="pemPasteText"
      v-model:region-input-mode="regionInputMode"
      :editing-id="editingId"
      :loading="submitLoading"
      :is-mobile="isMobile"
      :form-state="formState"
      :file-list="fileList"
      :region-input-mode-options="regionInputModeOptions"
      :region-options-loading="regionOptionsLoading"
      :region-options="ociRegionSelectOptions"
      :filter-region-option="filterOciRegionSelectOption"
      :group-level1-options="formGroupLevel1Options"
      :level2-options="level2Options"
      :before-upload="handleUpload"
      :on-submit="handleSubmit"
      @parse-and-fill="parseAndFill"
      @normalize-region="normalizeRegionInput"
      @key-input-mode-change="onKeyInputModeChange"
      @remove-file="handleRemoveFile"
      @clear-group-level2="formState.groupLevel2 = ''"
    />

    <TenantManagementModal
      v-if="tenantMgmtVisible || tenantMgmtTenant"
      v-model:open="tenantMgmtVisible"
      v-model:active-tab="tenantTab"
      v-model:iam-policy-search="iamPolicySearch"
      v-model:iam-expanded-row-keys="iamExpandedRowKeys"
      v-model:quota-region="quotaRegion"
      v-model:quota-service="quotaService"
      v-model:quota-search="quotaSearch"
      v-model:billing-cost-days="billingCostDays"
      v-model:region-search="regionSearch"
      v-model:region-subscribe-verify-visible="regionSubscribeVerifyVisible"
      v-model:region-subscribe-code="regionSubscribeCode"
      v-model:budget-form-visible="budgetFormVisible"
      v-model:budget-alert-form-visible="budgetAlertFormVisible"
      v-model:announcement-search="announcementSearch"
      v-model:announcement-drawer-visible="announcementDrawerVisible"
      v-model:announcement-detail-tab="announcementDetailTab"
      :tenant="tenantMgmtTenant"
      :is-mobile="isMobile"
      :tenant-info-loading="tenantInfoLoading"
      :tenant-info-data="tenantInfoData"
      :billing-loading="billingLoading"
      :billing-data="billingData"
      :billing-cost-day-options="billingCostDayOptions"
      :budgets-loading="budgetsLoading"
      :budgets-data="budgetsData"
      :budgets-list="budgetsList"
      :selected-budget-id="selectedBudgetId"
      :selected-budget="selectedBudget"
      :selected-budget-alert-rules="selectedBudgetAlertRules"
      :budget-alert-rules-loading="budgetAlertRulesLoading"
      :budget-compartments-loading="budgetCompartmentsLoading"
      :budget-form-loading="budgetFormLoading"
      :budget-form-mode="budgetFormMode"
      :budget-form="budgetForm"
      :budget-target-type-options="budgetTargetTypeOptions"
      :budget-processing-period-options="budgetProcessingPeriodOptions"
      :budget-reset-period-options="budgetResetPeriodOptions"
      :budget-compartment-options="budgetCompartmentOptions"
      :budget-target-compartment-options="budgetTargetCompartmentOptions"
      :budget-alert-form-loading="budgetAlertFormLoading"
      :budget-alert-form-mode="budgetAlertFormMode"
      :budget-alert-form="budgetAlertForm"
      :budget-alert-type-options="budgetAlertTypeOptions"
      :budget-threshold-type-options="budgetThresholdTypeOptions"
      :regions-loading="regionsLoading"
      :regions-data="regionsData"
      :filtered-regions="filteredRegions"
      :region-subscribe-sending-key="regionSubscribeSendingKey"
      :region-subscribe-loading="regionSubscribeLoading"
      :region-subscribe-code-sending="regionSubscribeCodeSending"
      :region-subscribe-target="regionSubscribeTarget"
      :region-subscribe-target-display="regionSubscribeTargetDisplay"
      :iam-policies-loading="iamPoliciesLoading"
      :filtered-iam-policies="filteredIamPolicies"
      :iam-policy-statements="iamPolicyStatements"
      :iam-policy-detail-loading="iamPolicyDetailLoading"
      :quotas-loading="quotasLoading"
      :quotas-list="quotasList"
      :quota-region-options="quotaRegionOptions"
      :quota-service-options="quotaServiceOptions"
      :filtered-quotas="filteredQuotas"
      :announcements-loading="announcementsLoading"
      :filtered-announcements="filteredAnnouncements"
      :announcements-retention-note="announcementsRetentionNote"
      :announcement-read-updating-id="announcementReadUpdatingId"
      :announcement-drawer-title="announcementDrawerTitle"
      :announcement-detail-loading="announcementDetailLoading"
      :announcement-detail="announcementDetail"
      :announcement-impacted="announcementImpacted"
      :announcement-history="announcementHistory"
      :on-tenant-tab-change="onTenantTabChange"
      :handle-refresh-tenant-account-info="handleRefreshTenantAccountInfo"
      :plan-type-tag-color="planTypeTagColor"
      :format-plan-type="formatPlanType"
      :format-payment-method="formatPaymentMethod"
      :format-account-type="formatAccountType"
      :format-upgrade-state="formatUpgradeState"
      :format-subscription-status="formatSubscriptionStatus"
      :subscription-status-tag-color="subscriptionStatusTagColor"
      :format-utc-cn-date="formatUtcCnDate"
      :format-country-cn="formatCountryCn"
      :load-iam-policies="loadIamPolicies"
      :on-iam-expand="onIamExpand"
      :short-oc-id="shortOcId"
      :on-quota-region-change="onQuotaRegionChange"
      :load-quotas="loadQuotas"
      :load-tenant-billing="loadTenantBilling"
      :reload-billing-cost="reloadBillingCost"
      :format-billing-period="formatBillingPeriod"
      :handle-download-invoice="handleDownloadInvoice"
      :open-create-budget="openCreateBudget"
      :load-budgets="loadBudgets"
      :budget-row-class-name="budgetRowClassName"
      :budget-table-row="budgetTableRow"
      :format-budget-target-tooltip="formatBudgetTargetTooltip"
      :format-budget-target="formatBudgetTarget"
      :format-budget-amount="formatBudgetAmount"
      :budget-progress-percent="budgetProgressPercent"
      :budget-progress-status="budgetProgressStatus"
      :format-budget-spend="formatBudgetSpend"
      :format-budget-processing-period="formatBudgetProcessingPeriod"
      :open-edit-budget="openEditBudget"
      :handle-delete-budget="handleDeleteBudget"
      :select-budget="selectBudget"
      :reload-selected-budget-alert-rules="reloadSelectedBudgetAlertRules"
      :open-create-budget-alert-rule="openCreateBudgetAlertRule"
      :format-budget-alert-type="formatBudgetAlertType"
      :format-budget-alert-threshold="formatBudgetAlertThreshold"
      :open-edit-budget-alert-rule="openEditBudgetAlertRule"
      :handle-delete-budget-alert-rule="handleDeleteBudgetAlertRule"
      :submit-budget-form="submitBudgetForm"
      :filter-budget-compartment-option="filterBudgetCompartmentOption"
      :on-budget-target-type-change="onBudgetTargetTypeChange"
      :submit-budget-alert-form="submitBudgetAlertForm"
      :load-regions="loadRegions"
      :format-region-display="formatRegionDisplay"
      :region-status-color="regionStatusColor"
      :format-region-status="formatRegionStatus"
      :confirm-subscribe-region="confirmSubscribeRegion"
      :submit-region-subscribe="submitRegionSubscribe"
      :resend-region-subscribe-code="resendRegionSubscribeCode"
      :load-announcements="loadAnnouncements"
      :announcement-custom-row="announcementCustomRow"
      :announcement-status-color="announcementStatusColor"
      :format-announcement-user-status="formatAnnouncementUserStatus"
      :is-announcement-unread="isAnnouncementUnread"
      :open-announcement-detail="openAnnouncementDetail"
      :mark-announcement-as-read="markAnnouncementAsRead"
      :format-announcement-body="formatAnnouncementBody"
    />
    <TenantDomainManagementModal
      v-if="domainMgmtVisible || domainMgmtTenant || mfaVerifyVisible"
      v-model:open="domainMgmtVisible"
      v-model:active-tab="domainTab"
      v-model:selected-domain-id="selectedDomainId"
      v-model:mfa-verify-visible="mfaVerifyVisible"
      v-model:mfa-verify-code="mfaVerifyCode"
      v-model:factor-code-input="factorCodeInput"
      v-model:notification-code-input="notificationCodeInput"
      v-model:notification-recipients-text="notificationRecipientsText"
      v-model:notification-event-active-keys="notificationEventActiveKeys"
      v-model:audit-days="auditDays"
      v-model:domain-audit-days="domainAuditDays"
      :tenant="domainMgmtTenant"
      :is-mobile="isMobile"
      :domain-settings-loading="domainSettingsLoading"
      :domain-list="domainList"
      :selected-domain="selectedDomain"
      :mfa-updating-id="mfaUpdatingId"
      :mfa-verify-loading="mfaVerifyLoading"
      :mfa-verify-code-sending="mfaVerifyCodeSending"
      :mfa-target-enabled="mfaTargetEnabled"
      :pwd-expiry-updating-id="pwdExpiryUpdatingId"
      :auth-factor-token="authFactorToken"
      :auth-factor-loading="authFactorLoading"
      :selected-factor-domain="selectedFactorDomain"
      :factor-code-sending="factorCodeSending"
      :factor-unlocking="factorUnlocking"
      :factor-saving-id="factorSavingId"
      :notification-loading="notificationLoading"
      :notification-saving="notificationSaving"
      :notification-data="notificationData"
      :notification-unlocking="notificationUnlocking"
      :notification-code-sending="notificationCodeSending"
      :notification-token="notificationToken"
      :notification-validate-options="notificationValidateOptions"
      :notification-admin-events="notificationAdminEvents"
      :notification-end-user-events="notificationEndUserEvents"
      :audit-logs-loading="auditLogsLoading"
      :audit-logs-loaded="auditLogsLoaded"
      :selected-audit-domain="selectedAuditDomain"
      :domain-audit-logs-loading="domainAuditLogsLoading"
      :domain-audit-logs-loaded="domainAuditLogsLoaded"
      :selected-domain-audit="selectedDomainAudit"
      :notification-event-labels="NOTIFICATION_EVENT_LABELS"
      :factor-options="FACTOR_OPTIONS"
      :handle-domain-change="handleDomainChange"
      :domain-type-cn="domainTypeCn"
      :handle-mfa-change="handleMfaChange"
      :handle-pwd-expiry-change="handlePwdExpiryChange"
      :send-factor-code="sendFactorCode"
      :do-unlock-factors="doUnlockFactors"
      :reload-factors="reloadFactors"
      :save-factors="saveFactors"
      :send-notification-code="sendNotificationCode"
      :do-unlock-notifications="doUnlockNotifications"
      :load-domain-notifications="loadDomainNotifications"
      :save-domain-notifications="saveDomainNotifications"
      :notification-validation-status-color="notificationValidationStatusColor"
      :format-notification-validation-status="formatNotificationValidationStatus"
      :format-notification-event-name="formatNotificationEventName"
      :load-audit-logs="loadAuditLogs"
      :on-audit-days-change="onAuditDaysChange"
      :load-domain-audit-logs="loadDomainAuditLogs"
      :on-domain-audit-days-change="onDomainAuditDaysChange"
      :submit-mfa-change="submitMfaChange"
      :cancel-mfa-verify="cancelMfaVerify"
      :resend-mfa-verify-code="resendMfaVerifyCode"
    />
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'TenantConfig' })

import { ref, reactive, computed, h, onMounted, onActivated, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { MenuFoldOutlined, MenuUnfoldOutlined, VerticalAlignTopOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import { getDomainSettings, updateMfa, updatePasswordExpiry, unlockDomainNotifications, getDomainNotifications, updateDomainNotifications, getAuditLogs, unlockAuthFactors, getAuthFactors, updateAuthFactors } from '../api/tenant'
import { sendVerifyCode } from '../api/system'
import { EyeOutlined, EyeInvisibleOutlined } from '@ant-design/icons-vue'
import {
  ociRegionSelectOptions,
  getOciRegionDisplayName,
  filterOciRegionSelectOption,
} from '../utils/ociRegionCatalog'
import { useTenantCatalogStore } from '../stores/tenantCatalog'
import { useThemeStore } from '../stores/theme'
import { createListSignature } from '../utils/queryCache'
import { defineAppAsyncComponent } from '../utils/asyncComponent'
import { useTenantConfigActions } from './tenant-config/composables/useTenantConfigActions'
import { useTenantManagement } from './tenant-config/composables/useTenantManagement'
import { useTenantGroups } from './tenant-config/composables/useTenantGroups'
import {
  formatTenantPlanLabel as formatPlanType,
  formatTenantPlanType as formatPlanBadgeValue,
  isFreeTierPlan,
  isPaygPlan,
  normalizeTenantPlanType as normalizePlanType,
  tenantPlanTagColor as planTypeTagColor,
} from '../utils/tenantPlan'

const TenantConfigListPanel = defineAppAsyncComponent(() => import('./tenant-config/components/TenantConfigListPanel.vue'))
const TenantBatchMoveModal = defineAppAsyncComponent(() => import('./tenant-config/components/TenantBatchMoveModal.vue'), { loading: 'none' })
const TenantGroupManagerModal = defineAppAsyncComponent(() => import('./tenant-config/components/TenantGroupManagerModal.vue'), { loading: 'none' })
const TenantRenameGroupModal = defineAppAsyncComponent(() => import('./tenant-config/components/TenantRenameGroupModal.vue'), { loading: 'none' })
const TenantAddSubGroupModal = defineAppAsyncComponent(() => import('./tenant-config/components/TenantAddSubGroupModal.vue'), { loading: 'none' })
const TenantConfigFormModal = defineAppAsyncComponent(() => import('./tenant-config/components/TenantConfigFormModal.vue'), { loading: 'none' })
const TenantManagementModal = defineAppAsyncComponent(() => import('./tenant-config/components/TenantManagementModal.vue'), { loading: 'none' })
const TenantDomainManagementModal = defineAppAsyncComponent(() => import('./tenant-config/components/TenantDomainManagementModal.vue'), { loading: 'none' })

const router = useRouter()
const catalog = useTenantCatalogStore()
const themeStore = useThemeStore()
const TENANT_MOBILE_VIRTUAL_MIN = 12

function formatTenantAddedTime(iso?: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso)
  return d.toLocaleString('zh-CN', { hour12: false })
}

function formatPlanBadge(plan: string | null | undefined, fallback = '获取中...') {
  return formatPlanBadgeValue(plan) || fallback
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

function tenantRowKey(item: unknown, index: number) {
  return String((item as any)?.id ?? index)
}
function shouldVirtualizeTenantMobileCards(count: number) {
  return count > TENANT_MOBILE_VIRTUAL_MIN
}
function tenantGroupVirtualResetKey(groupKey: string, tenants: any[]) {
  return `${groupKey}|${createListSignature(tenants, (r: any) => r.id)}`
}

const isMobile = ref(window.innerWidth < 768)
const viewportHeight = ref(window.innerHeight)
const tenantMobileVirtualMaxHeight = computed(() => Math.max(360, Math.min(680, viewportHeight.value - 180)))
const groupColors = ['#1677ff', '#52c41a', '#fa541c', '#722ed1', '#eb2f96', '#faad14', '#13c2c2']

let tenantGroupsController: ReturnType<typeof useTenantGroups> | null = null

const {
  submitLoading,
  searchText,
  normalizedSearchText,
  tableData,
  loading,
  tenantMobileSearchResetKey,
  modalVisible,
  editingId,
  importText,
  fileList,
  keyInputMode,
  pemPasteText,
  regionOptionsLoading,
  regionInputMode,
  regionInputModeOptions,
  formState,
  parseAndFill,
  loadData,
  onSearchTenants,
  invalidateCatalogAndReload,
  clearTenantSearchTimer,
  clearTenantInfoPollTimers,
  showAddModal,
  showEditModal,
  onKeyInputModeChange,
  normalizeRegionInput,
  handleUpload,
  handleRemoveFile,
  handleSubmit,
  handleDelete,
} = useTenantConfigActions({
  catalog,
  isMobile,
  getGroupLoadController: () => tenantGroupsController,
})

const tenantGroups = useTenantGroups({
  catalog,
  tableData,
  formState,
  loadData,
  invalidateCatalogAndReload,
})
tenantGroupsController = tenantGroups

const {
  selectedRowKeys,
  batchMoveVisible,
  batchMoveLoading,
  batchMoveG1,
  batchMoveG2,
  groupData,
  renameVisible,
  renameLoading,
  renameOldName,
  renameNewName,
  renameLevel,
  addSubVisible,
  addSubParent,
  addSubName,
  groupMgrVisible,
  createGroupFormVisible,
  createGroupName,
  createGroupLevel,
  createGroupParent,
  createGroupLoading,
  level2Options,
  formGroupLevel1Options,
  batchMoveLevel1Options,
  batchMoveLevel2Options,
  filterGroupOption,
  groupTree,
  groupTotalCount,
  getPlanCounts,
  expandedGroups,
  setPendingExpandTarget,
  clearPendingExpandTarget,
  applyDefaultExpandAfterLoad,
  allGroupsExpanded,
  toggleAllGroups,
  dragFromIndex,
  dragOverIndex,
  dragOverPos,
  subDragParent,
  subDragFromIndex,
  subDragOverIndex,
  subDragOverPos,
  displayGroups,
  onSubDragStart,
  onSubDragOver,
  onSubDrop,
  onSubDragEnd,
  onDragStart,
  onDragOver,
  onDrop,
  onDragEnd,
  toggleGroup,
  onSelectChange,
  openRenameGroup,
  handleRenameGroup,
  handleDeleteGroup,
  handleAddSubGroup,
  handleAddSubGroupConfirm,
  openGroupManager,
  openCreateGroupForm,
  handleCreateGroup,
  handleMgrAddSub,
  handleMgrDeleteGroup,
  openBatchMoveModal,
  confirmBatchMove,
  handleBatchDelete,
} = tenantGroups

const tenantManagement = useTenantManagement({
  tableData,
})

const {
  tenantMgmtVisible,
  tenantMgmtTenant,
  tenantTab,
  tenantInfoLoading,
  tenantInfoData,
  billingLoading,
  billingData,
  billingCostDays,
  billingCostDayOptions,
  budgetsLoading,
  budgetsData,
  budgetsList,
  selectedBudgetId,
  selectedBudget,
  selectedBudgetAlertRules,
  budgetAlertRulesLoading,
  budgetCompartmentsLoading,
  budgetFormVisible,
  budgetFormLoading,
  budgetFormMode,
  budgetForm,
  budgetTargetTypeOptions,
  budgetProcessingPeriodOptions,
  budgetResetPeriodOptions,
  budgetCompartmentOptions,
  budgetTargetCompartmentOptions,
  budgetAlertFormVisible,
  budgetAlertFormLoading,
  budgetAlertFormMode,
  budgetAlertForm,
  budgetAlertTypeOptions,
  budgetThresholdTypeOptions,
  regionsLoading,
  regionsData,
  filteredRegions,
  regionSearch,
  regionSubscribeVerifyVisible,
  regionSubscribeLoading,
  regionSubscribeCodeSending,
  regionSubscribeSendingKey,
  regionSubscribeCode,
  regionSubscribeTarget,
  regionSubscribeTargetDisplay,
  iamPoliciesLoading,
  iamPolicySearch,
  iamExpandedRowKeys,
  filteredIamPolicies,
  iamPolicyStatements,
  iamPolicyDetailLoading,
  quotasLoading,
  quotasList,
  quotaSearch,
  quotaRegion,
  quotaService,
  quotaRegionOptions,
  quotaServiceOptions,
  filteredQuotas,
  announcementsLoading,
  announcementSearch,
  filteredAnnouncements,
  announcementsRetentionNote,
  announcementDrawerVisible,
  announcementDetailLoading,
  announcementDetailTab,
  announcementDetail,
  announcementImpacted,
  announcementHistory,
  announcementDrawerTitle,
  announcementReadUpdatingId,
  openTenantMgmt,
  onTenantTabChange,
  handleRefreshTenantAccountInfo,
  loadIamPolicies,
  onIamExpand,
  shortOcId,
  onQuotaRegionChange,
  loadQuotas,
  loadTenantBilling,
  reloadBillingCost,
  formatBillingPeriod,
  handleDownloadInvoice,
  openCreateBudget,
  loadBudgets,
  budgetRowClassName,
  budgetTableRow,
  formatBudgetTargetTooltip,
  formatBudgetTarget,
  formatBudgetAmount,
  budgetProgressPercent,
  budgetProgressStatus,
  formatBudgetSpend,
  formatBudgetProcessingPeriod,
  openEditBudget,
  handleDeleteBudget,
  selectBudget,
  reloadSelectedBudgetAlertRules,
  openCreateBudgetAlertRule,
  formatBudgetAlertType,
  formatBudgetAlertThreshold,
  openEditBudgetAlertRule,
  handleDeleteBudgetAlertRule,
  submitBudgetForm,
  filterBudgetCompartmentOption,
  onBudgetTargetTypeChange,
  submitBudgetAlertForm,
  loadRegions,
  formatRegionDisplay,
  regionStatusColor,
  formatRegionStatus,
  confirmSubscribeRegion,
  submitRegionSubscribe,
  resendRegionSubscribeCode,
  loadAnnouncements,
  announcementCustomRow,
  announcementStatusColor,
  formatAnnouncementUserStatus,
  isAnnouncementUnread,
  openAnnouncementDetail,
  markAnnouncementAsRead,
  formatAnnouncementBody,
  formatPaymentMethod,
  formatAccountType,
  formatUpgradeState,
  formatSubscriptionStatus,
  subscriptionStatusTagColor,
  formatUtcCnDate,
  formatCountryCn,
} = tenantManagement

function checkMobile() {
  viewportHeight.value = window.innerHeight
  isMobile.value = window.innerWidth < 768
}

const domainMgmtVisible = ref(false)
const domainMgmtTenant = ref<any>(null)
const domainTab = ref('security')
const domainSettingsLoading = ref(false)
const domainList = ref<any[]>([])
let domainSettingsRequestSeq = 0
const selectedDomainId = ref('')
const mfaUpdatingId = ref('')
const mfaVerifyVisible = ref(false)
const mfaVerifyLoading = ref(false)
const mfaVerifyCodeSending = ref(false)
const mfaVerifyCode = ref('')
const mfaTargetDomain = ref<any | null>(null)
const mfaTargetEnabled = ref(false)
let mfaCodeSendSeq = 0
let mfaSubmitSeq = 0
const pwdExpiryUpdatingId = ref('')
let pwdExpiryRequestSeq = 0
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
let notificationRequestSeq = 0
let notificationSaveSeq = 0
let notificationUnlockSeq = 0
let notificationCodeSendSeq = 0
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
  resetMfaState()
  pwdExpiryRequestSeq++
  pwdExpiryUpdatingId.value = ''
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
  const seq = ++notificationCodeSendSeq
  notificationCodeSending.value = true
  try {
    await sendVerifyCode('domainNotifications')
    if (seq !== notificationCodeSendSeq) return
    message.success('验证码已发送至 Telegram')
  } catch (e: any) {
    if (seq !== notificationCodeSendSeq) return
    message.error(e?.message || '发送验证码失败')
  } finally {
    if (seq === notificationCodeSendSeq) notificationCodeSending.value = false
  }
}

async function doUnlockNotifications() {
  if (!notificationCodeInput.value || notificationCodeInput.value.length !== 6) {
    return message.warning('请输入 6 位验证码')
  }
  const seq = ++notificationUnlockSeq
  notificationUnlocking.value = true
  try {
    const r = await unlockDomainNotifications({ verifyCode: notificationCodeInput.value })
    if (seq !== notificationUnlockSeq) return
    notificationToken.value = r.data?.accessToken || ''
    notificationCodeInput.value = ''
    if (!notificationToken.value) throw new Error('未获取到访问令牌')
    await loadDomainNotifications()
    if (seq !== notificationUnlockSeq) return
    message.success('已解锁')
  } catch (e: any) {
    if (seq !== notificationUnlockSeq) return
    message.error(e?.message || '解锁失败')
  } finally {
    if (seq === notificationUnlockSeq) notificationUnlocking.value = false
  }
}

function resetNotificationState(keepToken = false) {
  notificationRequestSeq++
  notificationSaveSeq++
  notificationUnlockSeq++
  notificationCodeSendSeq++
  notificationData.value = null
  notificationRecipientsText.value = ''
  notificationEventActiveKeys.value = []
  notificationLoading.value = false
  notificationSaving.value = false
  notificationUnlocking.value = false
  notificationCodeSending.value = false
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
let authFactorRequestSeq = 0
let authFactorUnlockSeq = 0
let authFactorSaveSeq = 0
let authFactorCodeSendSeq = 0

function resetAuthFactorState() {
  authFactorRequestSeq++
  authFactorUnlockSeq++
  authFactorSaveSeq++
  authFactorCodeSendSeq++
  factorCodeInput.value = ''
  authFactorToken.value = ''
  authFactorDomains.value = []
  authFactorLoading.value = false
  factorUnlocking.value = false
  factorCodeSending.value = false
  factorSavingId.value = ''
}

async function sendFactorCode() {
  const seq = ++authFactorCodeSendSeq
  factorCodeSending.value = true
  try {
    await sendVerifyCode('authFactors')
    if (seq !== authFactorCodeSendSeq) return
    message.success('验证码已发送至 Telegram')
  } catch (e: any) {
    if (seq !== authFactorCodeSendSeq) return
    message.error(e?.message || '发送验证码失败')
  } finally {
    if (seq === authFactorCodeSendSeq) factorCodeSending.value = false
  }
}

async function doUnlockFactors() {
  if (!factorCodeInput.value || factorCodeInput.value.length !== 6) {
    return message.warning('请输入 6 位验证码')
  }
  const seq = ++authFactorUnlockSeq
  factorUnlocking.value = true
  try {
    const r = await unlockAuthFactors({ verifyCode: factorCodeInput.value })
    if (seq !== authFactorUnlockSeq) return
    authFactorToken.value = r.data?.accessToken || ''
    factorCodeInput.value = ''
    if (!authFactorToken.value) throw new Error('未获取到访问令牌')
    await reloadFactors()
    if (seq !== authFactorUnlockSeq) return
    message.success('已解锁')
  } catch (e: any) {
    if (seq !== authFactorUnlockSeq) return
    message.error(e?.message || '解锁失败')
  } finally {
    if (seq === authFactorUnlockSeq) factorUnlocking.value = false
  }
}

async function reloadFactors() {
  const tenantId = domainMgmtTenant.value?.id
  const accessToken = authFactorToken.value
  if (!tenantId || !accessToken) return
  const seq = ++authFactorRequestSeq
  authFactorLoading.value = true
  try {
    const r = await getAuthFactors({ id: tenantId, accessToken })
    if (seq !== authFactorRequestSeq || tenantId !== domainMgmtTenant.value?.id || accessToken !== authFactorToken.value) return
    const raw = (r.data && typeof r.data === 'object' && 'domains' in r.data) ? r.data.domains : r.data
    authFactorDomains.value = (Array.isArray(raw) ? raw : []).map((d: any) => ({
      ...d,
      factors: { ...(d.factors || {}) },
      limits: { ...(d.limits || {}) },
      trustedDevice: { ...(d.trustedDevice || {}) },
    }))
  } catch (e: any) {
    if (seq !== authFactorRequestSeq) return
    message.error(e?.message || '读取验证因素失败')
    if (String(e?.message || '').includes('解锁') || String(e?.message || '').includes('失效') || String(e?.message || '').includes('过期')) {
      resetAuthFactorState()
    }
  } finally {
    if (seq === authFactorRequestSeq) authFactorLoading.value = false
  }
}

async function saveFactors(d: any) {
  const tenantId = domainMgmtTenant.value?.id
  const accessToken = authFactorToken.value
  const domainId = d?.domainId
  if (!tenantId || !accessToken || !domainId) return
  const seq = ++authFactorSaveSeq
  factorSavingId.value = domainId
  try {
    const r = await updateAuthFactors({
      id: tenantId,
      domainId,
      accessToken,
      factors: d.factors,
      limits: d.limits,
      trustedDevice: d.trustedDevice,
    })
    if (seq !== authFactorSaveSeq || tenantId !== domainMgmtTenant.value?.id || accessToken !== authFactorToken.value) return
    if (r.data?.skipped) {
      message.info('未检测到变更')
    } else {
      message.success(`已保存 ${r.data?.changedOps || 0} 项变更`)
    }
    await reloadFactors()
  } catch (e: any) {
    if (seq !== authFactorSaveSeq) return
    message.error(e?.message || '保存失败')
    if (String(e?.message || '').includes('解锁') || String(e?.message || '').includes('失效') || String(e?.message || '').includes('过期')) {
      resetAuthFactorState()
    }
  } finally {
    if (seq === authFactorSaveSeq) factorSavingId.value = ''
  }
}

async function openDomainMgmt(record: any) {
  domainMgmtTenant.value = record
  domainTab.value = 'security'
  domainList.value = []
  selectedDomainId.value = ''
  resetMfaState()
  pwdExpiryRequestSeq++
  pwdExpiryUpdatingId.value = ''
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
    domainSettingsRequestSeq++
    domainSettingsLoading.value = false
    resetMfaState()
    pwdExpiryRequestSeq++
    pwdExpiryUpdatingId.value = ''
    resetAuthFactorState()
    resetNotificationState()
  }
})

async function loadDomainSettings() {
  const tenantId = domainMgmtTenant.value?.id
  if (!tenantId) return
  const seq = ++domainSettingsRequestSeq
  domainSettingsLoading.value = true
  try {
    const res = await getDomainSettings({ id: tenantId })
    if (seq !== domainSettingsRequestSeq || tenantId !== domainMgmtTenant.value?.id) return
    const raw = (res.data && typeof res.data === 'object' && 'domains' in res.data) ? res.data.domains : res.data
    domainList.value = Array.isArray(raw) ? raw : []
    if (domainList.value.length > 0 && !selectedDomainId.value) {
      selectedDomainId.value = domainList.value[0].domainId
    }
  } catch (e: any) {
    if (seq !== domainSettingsRequestSeq) return
    message.error(e?.message || '获取域设置失败')
  } finally {
    if (seq === domainSettingsRequestSeq) domainSettingsLoading.value = false
  }
}

async function handleMfaChange(domain: any, checked: boolean) {
  const tenantId = domainMgmtTenant.value?.id
  const domainId = domain?.domainId
  if (!tenantId || !domainId) return
  const seq = ++mfaCodeSendSeq
  mfaTargetDomain.value = domain
  mfaTargetEnabled.value = checked
  mfaVerifyCode.value = ''
  mfaUpdatingId.value = domainId
  try {
    await sendVerifyCode('domainMfa')
    if (seq !== mfaCodeSendSeq || tenantId !== domainMgmtTenant.value?.id || domainId !== mfaTargetDomain.value?.domainId) return
    message.success('验证码已发送至 Telegram')
    mfaVerifyVisible.value = true
  } catch (e: any) {
    if (seq !== mfaCodeSendSeq) return
    message.error(e?.message || '发送验证码失败')
    mfaTargetDomain.value = null
    mfaUpdatingId.value = ''
  }
}

function resetMfaState() {
  mfaCodeSendSeq++
  mfaSubmitSeq++
  mfaVerifyVisible.value = false
  mfaVerifyLoading.value = false
  mfaVerifyCodeSending.value = false
  mfaVerifyCode.value = ''
  mfaTargetDomain.value = null
  mfaTargetEnabled.value = false
  mfaUpdatingId.value = ''
}

function cancelMfaVerify() {
  resetMfaState()
}

async function resendMfaVerifyCode() {
  const tenantId = domainMgmtTenant.value?.id
  const domainId = mfaTargetDomain.value?.domainId
  if (!tenantId || !domainId) return
  const seq = ++mfaCodeSendSeq
  mfaVerifyCodeSending.value = true
  try {
    await sendVerifyCode('domainMfa')
    if (seq !== mfaCodeSendSeq || tenantId !== domainMgmtTenant.value?.id || domainId !== mfaTargetDomain.value?.domainId) return
    mfaVerifyCode.value = ''
    message.success('验证码已重新发送')
  } catch (e: any) {
    if (seq !== mfaCodeSendSeq) return
    message.error(e?.message || '发送失败')
  } finally {
    if (seq === mfaCodeSendSeq) mfaVerifyCodeSending.value = false
  }
}

async function submitMfaChange() {
  const tenantId = domainMgmtTenant.value?.id
  const domain = mfaTargetDomain.value
  const domainId = domain?.domainId
  const enabled = mfaTargetEnabled.value
  const verifyCode = mfaVerifyCode.value
  if (!tenantId || !domainId) return
  if (!verifyCode || verifyCode.length !== 6) {
    message.warning('请输入 6 位验证码')
    return
  }

  const seq = ++mfaSubmitSeq
  const prev = domain.mfaEnabled
  mfaVerifyLoading.value = true
  mfaUpdatingId.value = domainId
  try {
    await updateMfa({
      id: tenantId,
      domainId,
      enabled,
      verifyCode,
    })
    if (seq !== mfaSubmitSeq || tenantId !== domainMgmtTenant.value?.id || domainId !== mfaTargetDomain.value?.domainId) return
    domain.mfaEnabled = enabled
    message.success(enabled ? 'MFA 已启用' : 'MFA 已关闭')
    mfaVerifyVisible.value = false
    mfaVerifyCode.value = ''
    mfaTargetDomain.value = null
    mfaTargetEnabled.value = false
  } catch (e: any) {
    if (seq !== mfaSubmitSeq) return
    domain.mfaEnabled = prev
    message.error(e?.message || '更新 MFA 策略失败')
  } finally {
    if (seq === mfaSubmitSeq) {
      mfaVerifyLoading.value = false
      mfaUpdatingId.value = ''
    }
  }
}

async function handlePwdExpiryChange(domain: any) {
  const tenantId = domainMgmtTenant.value?.id
  const domainId = domain?.domainId
  if (!tenantId || !domainId) return
  const days = domain.passwordExpiresAfterDays ?? 0
  const seq = ++pwdExpiryRequestSeq
  pwdExpiryUpdatingId.value = domainId
  try {
    await updatePasswordExpiry({
      id: tenantId,
      domainId,
      days,
    })
    if (seq !== pwdExpiryRequestSeq || tenantId !== domainMgmtTenant.value?.id || domainId !== selectedDomainId.value) return
    message.success('密码过期策略已更新')
  } catch (e: any) {
    if (seq !== pwdExpiryRequestSeq) return
    message.error(e?.message || '更新密码策略失败')
  } finally {
    if (seq === pwdExpiryRequestSeq) pwdExpiryUpdatingId.value = ''
  }
}

async function loadDomainNotifications() {
  const tenantId = domainMgmtTenant.value?.id
  const domainId = selectedDomainId.value
  const accessToken = notificationToken.value
  if (!tenantId || !domainId) {
    message.warning('请先选择域')
    return
  }
  if (!accessToken) {
    message.warning('请先通过 TG 验证码解锁域通知')
    return
  }
  const seq = ++notificationRequestSeq
  notificationLoading.value = true
  try {
    const res = await getDomainNotifications({
      id: tenantId,
      domainId,
      accessToken,
    })
    if (seq !== notificationRequestSeq || tenantId !== domainMgmtTenant.value?.id || domainId !== selectedDomainId.value || accessToken !== notificationToken.value) return
    notificationData.value = normalizeDomainNotification(res.data || {})
    notificationRecipientsText.value = (notificationData.value.testRecipients || []).join('\n')
  } catch (e: any) {
    if (seq !== notificationRequestSeq) return
    message.error(e?.message || '读取域通知设置失败')
    if (isUnlockExpiredMessage(e?.message)) resetNotificationState()
  } finally {
    if (seq === notificationRequestSeq) notificationLoading.value = false
  }
}

async function saveDomainNotifications() {
  const tenantId = domainMgmtTenant.value?.id
  const domainId = selectedDomainId.value
  const accessToken = notificationToken.value
  if (!tenantId || !domainId || !notificationData.value) return
  if (!accessToken) {
    message.warning('请先通过 TG 验证码解锁域通知')
    return
  }
  const fromEmail = notificationData.value.fromEmailAddress || {}
  if (!String(fromEmail.value || '').trim()) {
    message.warning('请填写发件人电子邮件地址')
    return
  }
  const seq = ++notificationSaveSeq
  notificationSaving.value = true
  try {
    const res = await updateDomainNotifications({
      id: tenantId,
      domainId,
      accessToken,
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
    if (seq !== notificationSaveSeq || tenantId !== domainMgmtTenant.value?.id || domainId !== selectedDomainId.value || accessToken !== notificationToken.value) return
    if (res.data?.skipped) {
      message.info('未检测到变更')
    } else {
      message.success('域通知设置已保存')
    }
    const next = res.data?.notification || notificationData.value
    notificationData.value = normalizeDomainNotification(next)
    notificationRecipientsText.value = (notificationData.value.testRecipients || []).join('\n')
  } catch (e: any) {
    if (seq !== notificationSaveSeq) return
    message.error(e?.message || '保存域通知设置失败')
    if (isUnlockExpiredMessage(e?.message)) resetNotificationState()
  } finally {
    if (seq === notificationSaveSeq) notificationSaving.value = false
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

function scrollTenantPageTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function goUserManagement(record: any) {
  router.push(`/tenant/${record.id}/users`)
}

onMounted(async () => {
  void loadData()
  window.addEventListener('resize', checkMobile)
})
function ignoreCatalogWarmupError() {
  // 页面重新激活时的 catalog 预热是 best-effort，失败不打断当前页面操作。
}
onActivated(() => {
  if (!normalizedSearchText.value) {
    void catalog.ensureTenants({ silent: true }).catch(ignoreCatalogWarmupError)
    void catalog.ensureGroups({ silent: true }).catch(ignoreCatalogWarmupError)
  }
})
onUnmounted(() => {
  clearTenantSearchTimer()
  clearTenantInfoPollTimers()
  window.removeEventListener('resize', checkMobile)
})
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

:global([data-theme="light"] .tenant-account-refresh) {
  border-color: rgba(15, 23, 42, 0.12);
  background: rgba(15, 23, 42, 0.035);
  color: rgba(71, 85, 105, 0.82);
}

:global([data-theme="light"] .tenant-account-refresh:hover:not(:disabled)) {
  border-color: rgba(37, 99, 235, 0.38);
  background: rgba(37, 99, 235, 0.08);
  color: #2563eb;
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
