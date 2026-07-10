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
.tenant-config-root {
  position: relative;
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
</style>
