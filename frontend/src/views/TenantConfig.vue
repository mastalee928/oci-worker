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
import { Modal } from 'ant-design-vue'
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
import { useTenantDomainManagement } from './tenant-config/composables/useTenantDomainManagement'
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
  showAddModal: showAddConfigModal,
  showEditModal: showEditConfigModal,
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
  openTenantMgmt: openTenantManagementWorkspace,
  closeTenantMgmt,
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

const {
  domainMgmtVisible,
  domainMgmtTenant,
  domainTab,
  domainSettingsLoading,
  domainList,
  selectedDomainId,
  selectedDomain,
  domainTypeCn,
  handleDomainChange,
  openDomainManagementWorkspace,
  closeDomainMgmt,
  mfaUpdatingId,
  mfaVerifyVisible,
  mfaVerifyLoading,
  mfaVerifyCodeSending,
  mfaVerifyCode,
  mfaTargetEnabled,
  handleMfaChange,
  cancelMfaVerify,
  resendMfaVerifyCode,
  submitMfaChange,
  pwdExpiryUpdatingId,
  handlePwdExpiryChange,
  notificationLoading,
  notificationSaving,
  notificationData,
  notificationRecipientsText,
  notificationEventActiveKeys,
  notificationCodeSending,
  notificationCodeInput,
  notificationUnlocking,
  notificationToken,
  notificationValidateOptions,
  notificationAdminEvents,
  notificationEndUserEvents,
  NOTIFICATION_EVENT_LABELS,
  sendNotificationCode,
  doUnlockNotifications,
  loadDomainNotifications,
  saveDomainNotifications,
  formatNotificationValidationStatus,
  notificationValidationStatusColor,
  formatNotificationEventName,
  factorCodeSending,
  factorCodeInput,
  factorUnlocking,
  authFactorToken,
  authFactorLoading,
  selectedFactorDomain,
  factorSavingId,
  FACTOR_OPTIONS,
  sendFactorCode,
  doUnlockFactors,
  reloadFactors,
  saveFactors,
  auditLogsLoading,
  auditLogsLoaded,
  auditDays,
  selectedAuditDomain,
  domainAuditLogsLoading,
  domainAuditLogsLoaded,
  domainAuditDays,
  selectedDomainAudit,
  onAuditDaysChange,
  onDomainAuditDaysChange,
  loadAuditLogs,
  loadDomainAuditLogs,
} = useTenantDomainManagement()

type TenantConfigOverlayTarget = 'form' | 'tenant' | 'domain'

function closeTenantConfigOverlays(except?: TenantConfigOverlayTarget) {
  if (except !== 'form') {
    modalVisible.value = false
  }
  if (except !== 'tenant') {
    closeTenantMgmt()
  }
  if (except !== 'domain') {
    closeDomainMgmt()
  }
}

function showAddModal() {
  closeTenantConfigOverlays('form')
  showAddConfigModal()
}

function showEditModal(record: any) {
  closeTenantConfigOverlays('form')
  showEditConfigModal(record)
}

async function openTenantMgmt(record: any) {
  closeTenantConfigOverlays('tenant')
  await openTenantManagementWorkspace(record)
}

async function openDomainMgmt(record: any) {
  closeTenantConfigOverlays('domain')
  await openDomainManagementWorkspace(record)
}

function checkMobile() {
  viewportHeight.value = window.innerHeight
  isMobile.value = window.innerWidth < 768
}

function scrollTenantPageTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function goUserManagement(record: any) {
  closeTenantConfigOverlays()
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
