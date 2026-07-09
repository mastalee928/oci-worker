import { computed, reactive, ref, watch, type ComputedRef } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import {
  createBudget,
  createBudgetAlertRule,
  deleteBudget,
  deleteBudgetAlertRule,
  downloadInvoicePdf,
  getAnnouncementDetail,
  getIamPolicy,
  getServiceQuotas,
  getTenantBillingSummary,
  getTenantFullInfo,
  listAnnouncements,
  listBudgetAlertRules,
  listBudgets,
  listIamPolicies,
  listTenantRegions,
  markAnnouncementRead,
  subscribeTenantRegion,
  updateBudget,
  updateBudgetAlertRule,
} from '../../../api/tenant'
import type { BudgetAlertType, BudgetProcessingPeriodType, BudgetTargetType, BudgetThresholdType } from '../../../api/tenant'
import { listCompartmentPicker } from '../../../api/compartment'
import { sendVerifyCode } from '../../../api/system'
import { getOciRegionDisplayName } from '../../../utils/ociRegionCatalog'
import { appQueryCache } from '../../../utils/queryCache'

dayjs.extend(utc)

interface UseTenantManagementOptions {
  tableData: ComputedRef<any[]>
}

const TENANT_INFO_STALE_MS = 30_000
const TENANT_REGION_STALE_MS = 5 * 60_000
const TENANT_QUOTA_STALE_MS = 2 * 60_000

export function useTenantManagement(options: UseTenantManagementOptions) {
  const { tableData } = options

  function formatUtcCnDate(v: any): string {
    if (!v) return '—'
    const d = dayjs.utc(v)
    if (!d.isValid()) return '—'
    return `${d.year()}年${d.month() + 1}月${d.date()}日（UTC）`
  }

  function formatBillingPeriod(start: string | null | undefined, end: string | null | undefined): string {
    if (!start && !end) return '—'
    const s = start ? formatUtcCnDate(start) : '—'
    const e = end ? formatUtcCnDate(end) : '—'
    return `${s} ～ ${e}`
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
      } catch {
        // Intl.DisplayNames may be unavailable in older runtimes; fall back to local maps below.
      }
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

  const tenantMgmtVisible = ref(false)
  const tenantMgmtTenant = ref<any>(null)
  const tenantTab = ref('account')
  const tenantInfoLoading = ref(false)
  const tenantInfoData = ref<any>({})
  let tenantInfoRequestSeq = 0
  const billingLoading = ref(false)
  const billingData = ref<any | null>(null)
  let billingRequestSeq = 0
  const billingCostDays = ref(30)
  const billingCostDayOptions = [
    { value: 7, label: '近 7 天' },
    { value: 30, label: '近 30 天' },
    { value: 90, label: '近 90 天' },
  ]
  const budgetsLoading = ref(false)
  const budgetsData = ref<any | null>(null)
  let budgetsRequestSeq = 0
  const selectedBudgetId = ref('')
  const budgetAlertRulesLoading = ref(false)
  let budgetAlertRulesRequestSeq = 0
  const budgetCompartmentsLoading = ref(false)
  const budgetCompartmentsData = ref<any | null>(null)
  const budgetCompartmentsLoadedTenantId = ref('')
  let budgetCompartmentsRequestSeq = 0
  const budgetFormVisible = ref(false)
  const budgetFormLoading = ref(false)
  let budgetFormSubmitRequestSeq = 0
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
  let budgetAlertFormSubmitRequestSeq = 0
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
  let regionSubscribeVerifyRequestSeq = 0
  let regionSubscribeSubmitRequestSeq = 0
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
  let iamPoliciesRequestSeq = 0
  const iamPoliciesList = ref<any[]>([])
  const iamPoliciesCompartmentId = ref('')
  const iamPolicySearch = ref('')
  const iamExpandedRowKeys = ref<string[]>([])
  const iamPolicyStatements = ref<Record<string, string[]>>({})
  const iamPolicyDetailLoading = ref('')
  let iamPolicyDetailRequestSeq = 0
  const announcementsLoading = ref(false)
  let announcementsRequestSeq = 0
  const announcementsList = ref<any[]>([])
  const announcementsRetentionNote = ref('')
  const announcementSearch = ref('')
  const announcementDrawerVisible = ref(false)
  const announcementDetailLoading = ref(false)
  let announcementDetailRequestSeq = 0
  const announcementDetailTab = ref('detail')
  const announcementDetail = ref<any | null>(null)
  const announcementImpacted = ref<any[]>([])
  const announcementHistory = ref<any[]>([])
  const announcementDrawerTitle = ref('云公告详情')
  const announcementReadUpdatingId = ref('')

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
          const res = await getServiceQuotas({ id: tenantId, region: region || undefined, force })
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

  async function openTenantMgmt(record: any) {
    invalidateTenantManagementRequests()
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
    announcementDetailTab.value = 'detail'
    announcementDetail.value = null
    announcementImpacted.value = []
    announcementHistory.value = []
    announcementDrawerTitle.value = '云公告详情'
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
    regionSubscribeSendingKey.value = ''
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
    const requestSeq = ++billingRequestSeq
    billingLoading.value = true
    try {
      const bill = await getTenantBillingSummary({
        id: tenantId,
        limits: { invoices: 5, payments: 5, usageStatements: 3, costDays: billingCostDays.value },
      })
      if (requestSeq !== billingRequestSeq || currentTenantMgmtId() !== tenantId) return
      billingData.value = bill.data || null
    } catch (e: any) {
      if (requestSeq === billingRequestSeq && currentTenantMgmtId() === tenantId) {
        message.error(e?.message || '获取账务信息失败')
      }
    } finally {
      if (requestSeq === billingRequestSeq) {
        billingLoading.value = false
      }
    }
  }

  async function reloadBillingCost() {
    const tenantId = tenantInfoData.value?.id || tenantMgmtTenant.value?.id
    if (!tenantId) return
    const requestSeq = ++billingRequestSeq
    billingLoading.value = true
    try {
      const bill = await getTenantBillingSummary({
        id: tenantId,
        limits: { invoices: 5, costDays: billingCostDays.value },
      })
      if (requestSeq !== billingRequestSeq || currentTenantMgmtId() !== tenantId) return
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
      if (requestSeq === billingRequestSeq && currentTenantMgmtId() === tenantId) {
        message.error(e?.message || '查询成本失败')
      }
    } finally {
      if (requestSeq === billingRequestSeq) {
        billingLoading.value = false
      }
    }
  }

  function currentTenantMgmtId(): string {
    return tenantInfoData.value?.id || tenantMgmtTenant.value?.id || ''
  }

  function invalidateTenantManagementRequests() {
    tenantInfoRequestSeq++
    billingRequestSeq++
    budgetsRequestSeq++
    budgetAlertRulesRequestSeq++
    budgetCompartmentsRequestSeq++
    budgetFormSubmitRequestSeq++
    budgetAlertFormSubmitRequestSeq++
    regionsRequestSeq++
    regionSubscribeVerifyRequestSeq++
    regionSubscribeSubmitRequestSeq++
    quotaRequestSeq++
    iamPoliciesRequestSeq++
    iamPolicyDetailRequestSeq++
    announcementsRequestSeq++
    announcementDetailRequestSeq++

    tenantInfoLoading.value = false
    billingLoading.value = false
    budgetsLoading.value = false
    budgetAlertRulesLoading.value = false
    budgetCompartmentsLoading.value = false
    regionsLoading.value = false
    quotasLoading.value = false
    iamPoliciesLoading.value = false
    iamPolicyDetailLoading.value = ''
    announcementsLoading.value = false
    announcementDetailLoading.value = false
    announcementReadUpdatingId.value = ''
    regionSubscribeLoading.value = false
    regionSubscribeCodeSending.value = false
    regionSubscribeSendingKey.value = ''
    budgetFormLoading.value = false
    budgetAlertFormLoading.value = false
  }

  function clearTenantManagementState() {
    invalidateTenantManagementRequests()
    tenantMgmtTenant.value = null
    tenantTab.value = 'account'
    tenantInfoData.value = {}
    billingData.value = null
    budgetsData.value = null
    selectedBudgetId.value = ''
    budgetCompartmentsData.value = null
    budgetCompartmentsLoadedTenantId.value = ''
    budgetFormVisible.value = false
    budgetAlertFormVisible.value = false
    regionsData.value = null
    regionSearch.value = ''
    regionSubscribeVerifyVisible.value = false
    regionSubscribeTarget.value = null
    regionSubscribeCode.value = ''
    quotaSearch.value = ''
    quotaRegion.value = ''
    quotaService.value = '__all'
    quotasList.value = []
    iamPoliciesList.value = []
    iamPoliciesCompartmentId.value = ''
    iamPolicySearch.value = ''
    iamExpandedRowKeys.value = []
    iamPolicyStatements.value = {}
    announcementsList.value = []
    announcementsRetentionNote.value = ''
    announcementSearch.value = ''
    announcementDrawerVisible.value = false
    announcementDetailTab.value = 'detail'
    announcementDetail.value = null
    announcementImpacted.value = []
    announcementHistory.value = []
    announcementDrawerTitle.value = '云公告详情'
  }

  watch(tenantMgmtVisible, (visible) => {
    if (!visible) clearTenantManagementState()
  })

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
    const requestSeq = ++budgetCompartmentsRequestSeq
    budgetCompartmentsLoading.value = true
    try {
      const res = await listCompartmentPicker({ id: tenantId })
      if (requestSeq !== budgetCompartmentsRequestSeq || currentTenantMgmtId() !== tenantId) return
      const data = res.data || {}
      const items = Array.isArray(data.items) ? data.items : []
      budgetCompartmentsData.value = { ...data, items }
      budgetCompartmentsLoadedTenantId.value = tenantId
    } catch (e: any) {
      if (requestSeq === budgetCompartmentsRequestSeq && currentTenantMgmtId() === tenantId) {
        budgetCompartmentsData.value = null
        budgetCompartmentsLoadedTenantId.value = ''
        message.error(e?.message || '获取区间列表失败')
      }
    } finally {
      if (requestSeq === budgetCompartmentsRequestSeq) {
        budgetCompartmentsLoading.value = false
      }
    }
  }

  async function loadBudgets() {
    const tenantId = currentTenantMgmtId()
    if (!tenantId) return
    const requestSeq = ++budgetsRequestSeq
    budgetsLoading.value = true
    try {
      const res = await listBudgets({ id: tenantId })
      if (requestSeq !== budgetsRequestSeq || currentTenantMgmtId() !== tenantId) return
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
      if (requestSeq === budgetsRequestSeq && currentTenantMgmtId() === tenantId) {
        message.error(e?.message || '获取成本预算失败')
      }
    } finally {
      if (requestSeq === budgetsRequestSeq) {
        budgetsLoading.value = false
      }
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
    const requestSeq = ++budgetAlertRulesRequestSeq
    selectedBudgetId.value = budgetId
    budgetAlertRulesLoading.value = true
    try {
      const res = await listBudgetAlertRules({ id: tenantId, budgetId })
      if (requestSeq !== budgetAlertRulesRequestSeq || currentTenantMgmtId() !== tenantId) return
      const data = res.data
      const rules = Array.isArray(data) ? data : Array.isArray(data?.items) ? data.items : []
      setBudgetAlertRules(budgetId, rules)
    } catch (e: any) {
      if (requestSeq === budgetAlertRulesRequestSeq && currentTenantMgmtId() === tenantId) {
        message.error(e?.message || '获取预算告警规则失败')
      }
    } finally {
      if (requestSeq === budgetAlertRulesRequestSeq) {
        budgetAlertRulesLoading.value = false
      }
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
    const mode = budgetFormMode.value
    const displayName = budgetForm.displayName.trim()
    const amount = toBudgetNumber(budgetForm.amount)
    const targetType = normalizeBudgetTargetType(budgetForm.targetType)
    const processingPeriodType = normalizeBudgetProcessingPeriodType(budgetForm.processingPeriodType)
    const target = budgetForm.target.trim()
    const compartmentId = budgetForm.compartmentId.trim() || tenantRootCompartmentId()
    if (!displayName) { message.warning('请填写预算名称'); return }
    if (!amount || amount <= 0) { message.warning('预算金额必须大于 0'); return }
    if (mode === 'create' && !compartmentId) { message.warning('请填写预算所在区间 OCID'); return }
    if (mode === 'create' && targetType === 'TAG' && !target) { message.warning('请填写预算标签目标'); return }
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
    if (mode === 'create') {
      payload.compartmentId = compartmentId
      payload.targetType = targetType
      payload.target = target || (targetType === 'COMPARTMENT' ? tenantRootCompartmentId() : '')
    } else {
      payload.budgetId = budgetForm.budgetId
    }

    const requestSeq = ++budgetFormSubmitRequestSeq
    budgetFormLoading.value = true
    try {
      const res = mode === 'create'
        ? await createBudget(payload)
        : await updateBudget(payload)
      if (requestSeq !== budgetFormSubmitRequestSeq || currentTenantMgmtId() !== tenantId) return
      const row = res.data
      if (row?.id) {
        upsertBudgetRow(row)
        selectedBudgetId.value = row.id
      } else {
        await loadBudgets()
      }
      budgetFormVisible.value = false
      message.success(mode === 'create' ? '成本预算已创建' : '成本预算已更新')
    } catch (e: any) {
      if (requestSeq === budgetFormSubmitRequestSeq && currentTenantMgmtId() === tenantId) {
        message.error(e?.message || '保存成本预算失败')
      }
    } finally {
      if (requestSeq === budgetFormSubmitRequestSeq) {
        budgetFormLoading.value = false
      }
    }
  }

  async function handleDeleteBudget(record: any) {
    const tenantId = currentTenantMgmtId()
    const budgetId = record?.id
    if (!tenantId || !budgetId) return
    try {
      await deleteBudget({ id: tenantId, budgetId })
      if (currentTenantMgmtId() !== tenantId) return
      removeBudgetRow(budgetId)
      message.success('成本预算已删除')
    } catch (e: any) {
      if (currentTenantMgmtId() === tenantId) {
        message.error(e?.message || '删除成本预算失败')
      }
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
    const mode = budgetAlertFormMode.value
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
    if (mode === 'edit') payload.alertRuleId = budgetAlertForm.alertRuleId

    const requestSeq = ++budgetAlertFormSubmitRequestSeq
    budgetAlertFormLoading.value = true
    try {
      const res = mode === 'create'
        ? await createBudgetAlertRule(payload)
        : await updateBudgetAlertRule(payload)
      if (requestSeq !== budgetAlertFormSubmitRequestSeq || currentTenantMgmtId() !== tenantId) return
      const rule = res.data
      if (rule?.id) upsertBudgetAlertRuleRow(rule)
      else await reloadSelectedBudgetAlertRules()
      budgetAlertFormVisible.value = false
      message.success(mode === 'create' ? '预算告警已创建' : '预算告警已更新')
    } catch (e: any) {
      if (requestSeq === budgetAlertFormSubmitRequestSeq && currentTenantMgmtId() === tenantId) {
        message.error(e?.message || '保存预算告警失败')
      }
    } finally {
      if (requestSeq === budgetAlertFormSubmitRequestSeq) {
        budgetAlertFormLoading.value = false
      }
    }
  }

  async function handleDeleteBudgetAlertRule(record: any) {
    const tenantId = currentTenantMgmtId()
    const budgetId = record?.budgetId || selectedBudget.value?.id
    const alertRuleId = record?.id
    if (!tenantId || !budgetId || !alertRuleId) return
    try {
      await deleteBudgetAlertRule({ id: tenantId, budgetId, alertRuleId })
      if (currentTenantMgmtId() !== tenantId) return
      const budget = budgetsList.value.find((b: any) => b.id === budgetId)
      const rules = (Array.isArray(budget?.alertRules) ? budget.alertRules : [])
        .filter((r: any) => r.id !== alertRuleId)
      setBudgetAlertRules(budgetId, rules)
      message.success('预算告警已删除')
    } catch (e: any) {
      if (currentTenantMgmtId() === tenantId) {
        message.error(e?.message || '删除预算告警失败')
      }
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
          const res = await listTenantRegions({ id: tenantId, force })
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
    const tenantId = currentTenantMgmtId()
    const regionKey = record?.regionKey
    if (!tenantId || !regionKey) return
    const requestSeq = ++regionSubscribeVerifyRequestSeq
    regionSubscribeTarget.value = record
    regionSubscribeCode.value = ''
    regionSubscribeSendingKey.value = regionKey
    try {
      await sendVerifyCode('subscribeRegion')
      if (
        requestSeq !== regionSubscribeVerifyRequestSeq ||
        currentTenantMgmtId() !== tenantId ||
        regionSubscribeTarget.value?.regionKey !== regionKey
      ) return
      message.success('验证码已发送至 Telegram')
      regionSubscribeVerifyVisible.value = true
    } catch (e: any) {
      if (
        requestSeq !== regionSubscribeVerifyRequestSeq ||
        currentTenantMgmtId() !== tenantId ||
        regionSubscribeTarget.value?.regionKey !== regionKey
      ) return
      message.error(e?.message || '发送验证码失败')
      throw e
    } finally {
      if (requestSeq === regionSubscribeVerifyRequestSeq) {
        regionSubscribeSendingKey.value = ''
      }
    }
  }

  async function resendRegionSubscribeCode() {
    const tenantId = currentTenantMgmtId()
    const regionKey = regionSubscribeTarget.value?.regionKey
    if (!tenantId || !regionKey) return
    const requestSeq = ++regionSubscribeVerifyRequestSeq
    regionSubscribeCodeSending.value = true
    try {
      await sendVerifyCode('subscribeRegion')
      if (
        requestSeq !== regionSubscribeVerifyRequestSeq ||
        currentTenantMgmtId() !== tenantId ||
        regionSubscribeTarget.value?.regionKey !== regionKey
      ) return
      regionSubscribeCode.value = ''
      message.success('验证码已重新发送')
    } catch (e: any) {
      if (
        requestSeq === regionSubscribeVerifyRequestSeq &&
        currentTenantMgmtId() === tenantId &&
        regionSubscribeTarget.value?.regionKey === regionKey
      ) {
        message.error(e?.message || '发送失败')
      }
    } finally {
      if (requestSeq === regionSubscribeVerifyRequestSeq) {
        regionSubscribeCodeSending.value = false
      }
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

    const verifyCode = regionSubscribeCode.value
    const requestSeq = ++regionSubscribeSubmitRequestSeq
    regionSubscribeLoading.value = true
    try {
      await subscribeTenantRegion({
        id: tenantId,
        regionKey,
        verifyCode,
      })
      if (
        requestSeq !== regionSubscribeSubmitRequestSeq ||
        currentTenantMgmtId() !== tenantId ||
        regionSubscribeTarget.value?.regionKey !== regionKey
      ) return
      message.success('区域订阅已提交，激活可能需要几分钟')
      regionSubscribeVerifyVisible.value = false
      regionSubscribeCode.value = ''
      appQueryCache.invalidate(['tenantConfig', 'regions', tenantId])
      await loadRegions(false, true)
      if (currentTenantMgmtId() === tenantId && tenantMgmtTenant.value) {
        await loadTenantAccountInfo(tenantMgmtTenant.value, { force: true })
      }
    } catch (e: any) {
      if (
        requestSeq === regionSubscribeSubmitRequestSeq &&
        currentTenantMgmtId() === tenantId &&
        regionSubscribeTarget.value?.regionKey === regionKey
      ) {
        message.error(e?.message || '订阅区域失败')
      }
    } finally {
      if (requestSeq === regionSubscribeSubmitRequestSeq) {
        regionSubscribeLoading.value = false
      }
    }
  }

  async function loadIamPolicies() {
    if (!tenantMgmtTenant.value?.id) return
    const tenantId = tenantMgmtTenant.value.id
    const requestSeq = ++iamPoliciesRequestSeq
    iamPolicyDetailRequestSeq++
    iamPoliciesLoading.value = true
    iamPolicyStatements.value = {}
    iamExpandedRowKeys.value = []
    try {
      const res = await listIamPolicies({ id: tenantId })
      if (requestSeq !== iamPoliciesRequestSeq || tenantMgmtTenant.value?.id !== tenantId) return
      const data = res.data || {}
      iamPoliciesList.value = data.items || []
      iamPoliciesCompartmentId.value = data.compartmentId || ''
      if (!iamPoliciesList.value.length) {
        message.info('未找到 IAM 策略（或当前 API 用户无 inspect policies 权限）')
      }
    } catch (e: any) {
      if (requestSeq === iamPoliciesRequestSeq && tenantMgmtTenant.value?.id === tenantId) {
        message.error(e?.message || '获取 IAM 策略失败')
      }
    } finally {
      if (requestSeq === iamPoliciesRequestSeq) {
        iamPoliciesLoading.value = false
      }
    }
  }

  async function onIamExpand(expanded: boolean, record: any) {
    if (!expanded || !record?.id || !tenantMgmtTenant.value?.id) return
    if (iamPolicyStatements.value[record.id]?.length) return
    const tenantId = tenantMgmtTenant.value.id
    const policyId = record.id
    const requestSeq = ++iamPolicyDetailRequestSeq
    iamPolicyDetailLoading.value = policyId
    try {
      const res = await getIamPolicy({ id: tenantId, policyId })
      if (requestSeq !== iamPolicyDetailRequestSeq || tenantMgmtTenant.value?.id !== tenantId) return
      const stmts = res.data?.statements || []
      iamPolicyStatements.value = { ...iamPolicyStatements.value, [policyId]: stmts }
    } catch (e: any) {
      if (requestSeq === iamPolicyDetailRequestSeq && tenantMgmtTenant.value?.id === tenantId) {
        message.error(e?.message || '加载策略语句失败')
      }
    } finally {
      if (requestSeq === iamPolicyDetailRequestSeq && iamPolicyDetailLoading.value === policyId) {
        iamPolicyDetailLoading.value = ''
      }
    }
  }

  async function loadAnnouncements() {
    if (!tenantMgmtTenant.value?.id) return
    const tenantId = tenantMgmtTenant.value.id
    const requestSeq = ++announcementsRequestSeq
    announcementsLoading.value = true
    try {
      const res = await listAnnouncements({ id: tenantId })
      if (requestSeq !== announcementsRequestSeq || tenantMgmtTenant.value?.id !== tenantId) return
      const data = res.data || {}
      announcementsList.value = data.items || []
      announcementsRetentionNote.value = data.retentionNote || ''
      if (!announcementsList.value.length) {
        message.info('未找到云公告（或当前 API 用户无 announcement 读权限）')
      }
    } catch (e: any) {
      if (requestSeq === announcementsRequestSeq && tenantMgmtTenant.value?.id === tenantId) {
        message.error(e?.message || '获取云公告失败')
      }
    } finally {
      if (requestSeq === announcementsRequestSeq) {
        announcementsLoading.value = false
      }
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
      if (tenantMgmtTenant.value?.id !== tenantId) return
      syncAnnouncementReadStatus(announcementId)
      message.success('已标记为已读')
    } catch (e: any) {
      if (tenantMgmtTenant.value?.id === tenantId) {
        message.error(e?.message || '设置公告已读失败')
      }
    } finally {
      if (tenantMgmtTenant.value?.id === tenantId && announcementReadUpdatingId.value === announcementId) {
        announcementReadUpdatingId.value = ''
      }
    }
  }

  async function openAnnouncementDetail(record: any) {
    const announcementId = record?.id
    const tenantId = tenantMgmtTenant.value?.id
    if (!announcementId || !tenantId) return
    const requestSeq = ++announcementDetailRequestSeq
    announcementDrawerVisible.value = true
    announcementDetailTab.value = 'detail'
    announcementDrawerTitle.value = record.summary || '云公告详情'
    announcementDetailLoading.value = true
    announcementDetail.value = null
    announcementImpacted.value = []
    announcementHistory.value = []
    try {
      const res = await getAnnouncementDetail({
        id: tenantId,
        announcementId,
      })
      if (requestSeq !== announcementDetailRequestSeq || tenantMgmtTenant.value?.id !== tenantId) return
      const data = res.data || {}
      announcementDetail.value = data.detail || null
      announcementImpacted.value = data.impactedResources || []
      announcementHistory.value = data.history || []
      if (announcementDetail.value?.summary) {
        announcementDrawerTitle.value = announcementDetail.value.summary
      }
    } catch (e: any) {
      if (requestSeq === announcementDetailRequestSeq && tenantMgmtTenant.value?.id === tenantId) {
        message.error(e?.message || '获取公告详情失败')
        announcementDrawerVisible.value = false
      }
    } finally {
      if (requestSeq === announcementDetailRequestSeq) {
        announcementDetailLoading.value = false
      }
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

  return {
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
  }
}
