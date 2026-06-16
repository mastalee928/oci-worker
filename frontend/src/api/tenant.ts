import request from '../utils/request'

export function getTenantList(params: any) {
  return request.post('/oci/user/list', params)
}

export function addTenant(data: any) {
  return request.post('/oci/user/add', data)
}

export function updateTenant(data: any) {
  return request.post('/oci/user/update', data)
}

export function removeTenant(data: { idList: string[] }) {
  return request.post('/oci/user/remove', data)
}

export function batchMoveTenantGroup(data: {
  idList: string[]
  groupLevel1: string
  groupLevel2?: string
}) {
  return request.post('/oci/user/batchMoveGroup', data)
}

export function getTenantDetails(data: { id: string }) {
  return request.post('/oci/user/details', data)
}

export function refreshPlanType(data: { id: string }) {
  return request.post('/oci/user/refreshPlanType', data)
}

export function getTenantFullInfo(data: { id: string }) {
  return request.post('/oci/user/fullInfo', data)
}

export function getTenantBillingSummary(data: {
  id: string
  limits?: { invoices?: number; payments?: number; usageStatements?: number; costDays?: number }
}) {
  return request.post('/oci/user/billingSummary', data)
}

export function downloadInvoicePdf(data: { id: string; invoiceId: string; fileName?: string }) {
  return request.post('/oci/user/invoicePdf', data, { responseType: 'blob' as any })
}

export type BudgetTargetType = 'COMPARTMENT' | 'TAG'
export type BudgetProcessingPeriodType = 'MONTH' | 'INVOICE' | 'SINGLE_USE'
export type BudgetAlertType = 'ACTUAL' | 'FORECAST'
export type BudgetThresholdType = 'PERCENTAGE' | 'ABSOLUTE'

export interface BudgetPayload {
  id: string
  budgetId?: string
  displayName: string
  description?: string
  amount: number | string
  compartmentId?: string
  targetType?: BudgetTargetType
  target?: string
  targets?: string[]
  resetPeriod?: 'MONTHLY'
  processingPeriodType?: BudgetProcessingPeriodType
  budgetProcessingPeriodStartOffset?: number | null
  startDate?: string
  endDate?: string
}

export interface BudgetAlertRulePayload {
  id: string
  budgetId: string
  alertRuleId?: string
  displayName: string
  description?: string
  type?: BudgetAlertType
  threshold: number | string
  thresholdType?: BudgetThresholdType
  recipients: string
  message?: string
}

export function listBudgets(data: { id: string }) {
  return request.post('/oci/user/budgets', data)
}

export function createBudget(data: BudgetPayload) {
  return request.post('/oci/user/budget/create', data)
}

export function updateBudget(data: BudgetPayload & { budgetId: string }) {
  return request.post('/oci/user/budget/update', data)
}

export function deleteBudget(data: { id: string; budgetId: string }) {
  return request.post('/oci/user/budget/delete', data)
}

export function listBudgetAlertRules(data: { id: string; budgetId: string }) {
  return request.post('/oci/user/budget/alertRules', data)
}

export function createBudgetAlertRule(data: BudgetAlertRulePayload) {
  return request.post('/oci/user/budget/alertRule/create', data)
}

export function updateBudgetAlertRule(data: BudgetAlertRulePayload & { alertRuleId: string }) {
  return request.post('/oci/user/budget/alertRule/update', data)
}

export function deleteBudgetAlertRule(data: { id: string; budgetId: string; alertRuleId: string }) {
  return request.post('/oci/user/budget/alertRule/delete', data)
}

export function listTenantRegions(data: { id: string }) {
  return request.post('/oci/user/regions', data)
}

export function subscribeTenantRegion(data: { id: string; regionKey: string; verifyCode: string }) {
  return request.post('/oci/user/region/subscribe', data)
}

export function uploadKey(formData: FormData) {
  return request.post('/oci/user/uploadKey', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function getDomainSettings(data: { id: string }) {
  return request.post('/oci/user/domainSettings', data)
}

export function updateMfa(data: { id: string; domainId?: string; enabled: boolean; verifyCode: string }) {
  return request.post('/oci/user/updateMfa', data)
}

export function updatePasswordExpiry(data: { id: string; domainId?: string; days: number }) {
  return request.post('/oci/user/updatePasswordExpiry', data)
}

export interface DomainNotificationPayload {
  id: string
  domainId: string
  accessToken?: string
  notificationEnabled?: boolean
  testModeEnabled?: boolean
  testRecipients?: string[] | string
  sendNotificationToOldAndNewPrimaryEmailsWhenAdminChangesPrimaryEmail?: boolean
  fromEmailAddress?: {
    value?: string
    displayName?: string
    validate?: 'email' | 'domain' | string
  }
  eventSettings?: Array<{ eventId: string; enabled: boolean }>
}

export function unlockDomainNotifications(data: { verifyCode: string }) {
  return request.post('/oci/user/domainNotificationsUnlock', data)
}

export function getDomainNotifications(data: { id: string; domainId: string; accessToken?: string }) {
  return request.post('/oci/user/domainNotifications', data)
}

export function updateDomainNotifications(data: DomainNotificationPayload) {
  return request.post('/oci/user/updateDomainNotifications', data)
}

export function getAuditLogs(data: { id: string; days?: number; domainId?: string }) {
  return request.post('/oci/user/auditLogs', data)
}

export function getServiceQuotas(data: { id: string }) {
  return request.post('/oci/user/quotas', data)
}

/** 经典 IAM Policy（Identity API），与身份域策略无关 */
export function listIamPolicies(data: { id: string }) {
  return request.post('/oci/user/iamPolicies', data)
}

export function getIamPolicy(data: { id: string; policyId: string }) {
  return request.post('/oci/user/iamPolicy', data)
}

/** OCI 云公告（Announcements API），只读 */
export function listAnnouncements(data: { id: string }) {
  return request.post('/oci/user/announcements', data)
}

export function getAnnouncementDetail(data: { id: string; announcementId: string }) {
  return request.post('/oci/user/announcement', data)
}

export function unlockAuthFactors(data: { verifyCode: string }) {
  return request.post('/oci/user/authFactorsUnlock', data)
}

export function getAuthFactors(data: { id: string; accessToken: string }) {
  return request.post('/oci/user/authFactors', data)
}

export function updateAuthFactors(data: {
  id: string
  domainId: string
  accessToken: string
  factors?: Record<string, boolean>
  limits?: Record<string, number>
  trustedDevice?: Record<string, any>
}) {
  return request.post('/oci/user/updateAuthFactors', data)
}

export function getTenantGroups() {
  return request.get('/oci/user/groups')
}

export function createGroup(data: { name: string; level: string; parent?: string }) {
  return request.post('/oci/user/createGroup', data)
}

export function saveGroupOrder(data: { order: string[] }) {
  return request.post('/oci/user/saveGroupOrder', data)
}

export function renameGroup(data: { oldName: string; newName: string; level: string }) {
  return request.post('/oci/user/renameGroup', data)
}

export function deleteGroup(data: { name: string; level: string }) {
  return request.post('/oci/user/deleteGroup', data)
}
