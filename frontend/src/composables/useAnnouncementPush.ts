import { computed, onMounted, onUnmounted, reactive, ref, type Ref } from 'vue'
import { message } from 'ant-design-vue'
import request from '../utils/request'
import { appQueryCache } from '../utils/queryCache'

const ANNOUNCEMENT_INBOX_STALE_MS = 30_000
const ANNOUNCEMENT_STATUS_POLL_MS = 3_000
const DEFAULT_EVENT_TYPES = ['ACTION_REQUIRED', 'OIC_MAINTENANCE', 'MAINTENANCE', 'SECURITY', 'EMERGENCY']

export type AnnouncementTenant = {
  id: string
  username?: string
  tenantName?: string
  region?: string
  tenancyTail?: string
  groupLevel1?: string
  groupLevel2?: string
}

export type AnnouncementGroupOption = {
  key: string
  label: string
  count: number
  level: '1' | '2'
  groupLevel1: string
  groupLevel2?: string
}

export type AnnouncementItem = Record<string, any> & { aggregateKey: string }
export type AnnouncementInboxRange = '24h' | '7d' | '30d' | 'all' | 'custom'
export type AnnouncementMarkAction = 'read' | 'ignore' | 'unignore'

type UseAnnouncementPushOptions = {
  isMobile: Readonly<Ref<boolean>>
  onAnnouncementEnabled?: () => void
}

export function useAnnouncementPush(options: UseAnnouncementPushOptions) {
  const announcementTab = ref<'config' | 'inbox' | 'history' | 'status'>('config')
  const announcementSaveLoading = ref(false)
  const announcementScanLoading = ref(false)
  const announcementConfigLoading = ref(false)
  const announcementTenantsLoading = ref(false)
  const announcementStatusLoading = ref(false)
  const announcementInboxLoading = ref(false)
  const announcementBatchLoading = ref(false)
  const announcementDetailLoading = ref(false)
  const tenantPickerVisible = ref(false)
  const announcementDetailVisible = ref(false)
  const announcementTenantSearch = ref('')
  const announcementInboxKeyword = ref('')
  const announcementInboxRange = ref<AnnouncementInboxRange>('30d')
  const announcementInboxDates = ref<string[]>([])
  const announcementInboxEventTypes = ref<string[]>([])
  const announcementTenants = ref<AnnouncementTenant[]>([])
  const activeAnnouncementGroupKey = ref('ALL')
  const tenantPickerPage = ref(1)
  const tenantSelectedPage = ref(1)
  const tenantPickerPageSize = computed(() => (options.isMobile.value ? 6 : 8))
  const announcementStatus = reactive<Record<string, any>>({})
  const announcementDetail = reactive<Record<string, any>>({})
  const announcementInbox = reactive({ records: [] as AnnouncementItem[], total: 0, current: 1, size: 50 })
  const announcementBatches = reactive({ records: [] as Record<string, any>[], total: 0, current: 1, size: 10 })
  const announcementPushConfig = reactive({
    enabled: false,
    eventTypes: [] as string[],
    frequencyMinutes: 30,
    selectedTenantIds: [] as string[],
    recordRetentionDays: 90,
    batchRetentionDays: 30,
  })

  let configRequestSeq = 0
  let tenantsRequestSeq = 0
  let statusRequestSeq = 0
  let inboxRequestSeq = 0
  let batchRequestSeq = 0
  let detailRequestSeq = 0
  let saveRequestSeq = 0
  let scanRequestSeq = 0
  let statusLoadingRequestCount = 0
  let announcementStatusPollTimer: ReturnType<typeof setInterval> | null = null
  let announcementScanCompletionNoticeArmed = false
  let disposed = false

  const announcementEventTypeOptions = [
    { label: '需要采取行动', value: 'ACTION_REQUIRED' },
    { label: 'OIC 维护通知', value: 'OIC_MAINTENANCE' },
    { label: '维护通知', value: 'MAINTENANCE' },
    { label: '信息通知', value: 'INFORMATION' },
    { label: '安全通知', value: 'SECURITY' },
    { label: '紧急通知', value: 'EMERGENCY' },
  ]
  const announcementEventFilterOptions = [
    ...announcementEventTypeOptions,
    { label: '未识别', value: 'UNKNOWN' },
  ]
  const announcementTimeRangeOptions = [
    { label: '近 24 小时', value: '24h' },
    { label: '近 7 天', value: '7d' },
    { label: '近 30 天', value: '30d' },
    { label: '全部', value: 'all' },
    { label: '自定义', value: 'custom' },
  ]
  const announcementFrequencyOptions = [
    { label: '15 分钟', value: 15 },
    { label: '30 分钟', value: 30 },
    { label: '60 分钟', value: 60 },
    { label: '180 分钟', value: 180 },
    { label: '360 分钟', value: 360 },
    { label: '720 分钟', value: 720 },
  ]
  const announcementRecordRetentionOptions = [
    { label: '30 天', value: 30 },
    { label: '90 天', value: 90 },
    { label: '180 天', value: 180 },
  ]
  const announcementBatchRetentionOptions = [
    { label: '7 天', value: 7 },
    { label: '30 天', value: 30 },
    { label: '60 天', value: 60 },
  ]

  const announcementGroupOptions = computed<AnnouncementGroupOption[]>(() => {
    const level1 = new Map<string, number>()
    const level2 = new Map<string, Map<string, number>>()
    for (const tenant of announcementTenants.value) {
      const g1 = tenant.groupLevel1 || '未分组'
      const g2 = tenant.groupLevel2 || ''
      level1.set(g1, (level1.get(g1) || 0) + 1)
      if (g2) {
        const children = level2.get(g1) || new Map<string, number>()
        children.set(g2, (children.get(g2) || 0) + 1)
        level2.set(g1, children)
      }
    }
    const out: AnnouncementGroupOption[] = []
    out.push({ key: 'ALL', label: '全部租户', count: announcementTenants.value.length, level: '1', groupLevel1: 'ALL' })
    for (const [g1, count] of level1.entries()) {
      out.push({ key: `1|${g1}`, label: g1, count, level: '1', groupLevel1: g1 })
      const children = level2.get(g1)
      if (children) {
        for (const [g2, childCount] of children.entries()) {
          out.push({ key: `2|${g1}|${g2}`, label: g2, count: childCount, level: '2', groupLevel1: g1, groupLevel2: g2 })
        }
      }
    }
    return out
  })

  const filteredAnnouncementTenants = computed(() => {
    const kw = announcementTenantSearch.value.trim().toLowerCase()
    return announcementTenants.value.filter((tenant) => {
      const groupMatched = activeAnnouncementGroupKey.value === 'ALL'
        || tenantMatchesGroupKey(tenant, activeAnnouncementGroupKey.value)
      if (!groupMatched) return false
      if (!kw) return true
      return [
        tenant.tenantName,
        tenant.username,
        tenant.region,
        tenant.groupLevel1,
        tenant.groupLevel2,
        tenant.tenancyTail,
      ].some((value) => String(value || '').toLowerCase().includes(kw))
    })
  })

  const announcementSelectedTenantCount = computed(() => announcementPushConfig.selectedTenantIds.length)
  const announcementScanRunning = computed(() => announcementStatus.scanning === true)
  const announcementSelectedTenants = computed(() => {
    const selected = new Set(announcementPushConfig.selectedTenantIds)
    return announcementTenants.value.filter((tenant) => selected.has(tenant.id))
  })
  const announcementSelectedTenantPreview = computed(() => announcementSelectedTenants.value.slice(0, 5))
  const pagedFilteredAnnouncementTenants = computed(() => {
    const start = (tenantPickerPage.value - 1) * tenantPickerPageSize.value
    return filteredAnnouncementTenants.value.slice(start, start + tenantPickerPageSize.value)
  })
  const pagedAnnouncementSelectedTenants = computed(() => {
    const start = (tenantSelectedPage.value - 1) * tenantPickerPageSize.value
    return announcementSelectedTenants.value.slice(start, start + tenantPickerPageSize.value)
  })

  async function loadInitialAnnouncementPushData() {
    await Promise.allSettled([
      loadAnnouncementPushConfig(),
      loadAnnouncementTenants(),
      loadAnnouncementStatus(),
    ])
  }

  async function loadAnnouncementPushConfig() {
    const seq = ++configRequestSeq
    announcementConfigLoading.value = true
    try {
      const res = await request.get('/sys/announcementPush/config')
      if (seq !== configRequestSeq || disposed) return
      const data = res.data || {}
      announcementPushConfig.enabled = data.enabled === true
      announcementPushConfig.eventTypes = Array.isArray(data.eventTypes) ? data.eventTypes : [...DEFAULT_EVENT_TYPES]
      announcementPushConfig.frequencyMinutes = Number(data.frequencyMinutes || 30)
      announcementPushConfig.selectedTenantIds = Array.isArray(data.selectedTenantIds) ? [...data.selectedTenantIds] : []
      announcementPushConfig.recordRetentionDays = Number(data.recordRetentionDays || 90)
      announcementPushConfig.batchRetentionDays = Number(data.batchRetentionDays || 30)
      if (Object.keys(announcementStatus).length === 0) {
        applyAnnouncementStatus(data.status || {}, false)
      }
      pruneSelectedTenantIds()
    } catch {
      /* 配置加载失败时沿用默认值，避免阻断通知设置页 */
    } finally {
      if (seq === configRequestSeq) announcementConfigLoading.value = false
    }
  }

  async function loadAnnouncementTenants() {
    const seq = ++tenantsRequestSeq
    announcementTenantsLoading.value = true
    try {
      const res = await request.get('/sys/announcementPush/tenants')
      if (seq !== tenantsRequestSeq || disposed) return
      announcementTenants.value = Array.isArray(res.data?.items) ? res.data.items : []
      pruneSelectedTenantIds()
      normalizeTenantPickerPage()
      normalizeTenantSelectedPage()
    } catch {
      if (seq === tenantsRequestSeq && !disposed) {
        announcementTenants.value = []
      }
    } finally {
      if (seq === tenantsRequestSeq) announcementTenantsLoading.value = false
    }
  }

  async function saveAnnouncementPushConfig() {
    const seq = ++saveRequestSeq
    announcementSaveLoading.value = true
    try {
      await request.post('/sys/announcementPush/config', {
        enabled: announcementPushConfig.enabled,
        eventTypes: announcementPushConfig.eventTypes,
        frequencyMinutes: announcementPushConfig.frequencyMinutes,
        selectedTenantIds: announcementPushConfig.selectedTenantIds,
        recordRetentionDays: announcementPushConfig.recordRetentionDays,
        batchRetentionDays: announcementPushConfig.batchRetentionDays,
      })
      if (seq !== saveRequestSeq || disposed) return
      message.success('已保存')
      if (announcementPushConfig.enabled) {
        options.onAnnouncementEnabled?.()
      }
      await loadAnnouncementPushConfig()
    } catch (e: any) {
      if (seq === saveRequestSeq && !disposed) message.error(e?.message || '保存失败')
    } finally {
      if (seq === saveRequestSeq) announcementSaveLoading.value = false
    }
  }

  async function loadAnnouncementStatus(silent = false) {
    const seq = ++statusRequestSeq
    if (!silent) {
      statusLoadingRequestCount += 1
      announcementStatusLoading.value = true
    }
    try {
      const res = await request.get('/sys/announcementPush/status')
      if (seq !== statusRequestSeq || disposed) return
      applyAnnouncementStatus(res.data || {}, true)
    } catch {
      /* 状态加载失败时保持当前状态展示 */
    } finally {
      if (!silent) {
        statusLoadingRequestCount = Math.max(0, statusLoadingRequestCount - 1)
        if (statusLoadingRequestCount === 0) announcementStatusLoading.value = false
      }
    }
  }

  async function triggerAnnouncementScan() {
    const seq = ++scanRequestSeq
    announcementScanLoading.value = true
    try {
      await request.post('/sys/announcementPush/scan')
      if (seq !== scanRequestSeq || disposed) return
      announcementTab.value = 'status'
      markAnnouncementScanStarted()
      message.success('已开始扫描')
      appQueryCache.invalidate(['systemSettings', 'announcementInbox'])
      await loadAnnouncementStatus()
    } catch (e: any) {
      if (seq === scanRequestSeq && !disposed) {
        const msg = e?.message || '启动扫描失败'
        if (String(msg).includes('扫描正在进行中')) {
          announcementTab.value = 'status'
          message.info('云公告扫描正在进行中')
          await loadAnnouncementStatus()
        } else {
          message.error(msg)
        }
      }
    } finally {
      if (seq === scanRequestSeq) announcementScanLoading.value = false
    }
  }

  async function loadAnnouncementInbox(page = announcementInbox.current, force = false) {
    const seq = ++inboxRequestSeq
    announcementInboxLoading.value = true
    try {
      const range = resolveAnnouncementInboxRange()
      const params = {
        page,
        size: announcementInbox.size,
        keyword: announcementInboxKeyword.value || undefined,
        startAt: range.startAt,
        endAt: range.endAt,
        eventTypes: announcementInboxEventTypes.value.length ? announcementInboxEventTypes.value.join(',') : undefined,
      }
      const data = await appQueryCache.fetch(
        ['systemSettings', 'announcementInbox', params],
        async () => {
          const res = await request.get('/sys/announcementPush/inbox', { params })
          return res.data || {}
        },
        { staleMs: ANNOUNCEMENT_INBOX_STALE_MS, force },
      )
      if (seq !== inboxRequestSeq || disposed) return
      announcementInbox.records = Array.isArray(data.records) ? data.records : []
      announcementInbox.total = Number(data.total || 0)
      announcementInbox.current = Number(data.current || page)
      announcementInbox.size = Number(data.size || announcementInbox.size)
    } catch (e: any) {
      if (seq === inboxRequestSeq && !disposed) {
        message.error(e?.message || '加载失败')
      }
    } finally {
      if (seq === inboxRequestSeq) announcementInboxLoading.value = false
    }
  }

  async function loadAnnouncementBatches(page = announcementBatches.current) {
    const seq = ++batchRequestSeq
    announcementBatchLoading.value = true
    try {
      const res = await request.get('/sys/announcementPush/batches', { params: { page, size: announcementBatches.size } })
      if (seq !== batchRequestSeq || disposed) return
      const data = res.data || {}
      announcementBatches.records = Array.isArray(data.records) ? data.records : []
      announcementBatches.total = Number(data.total || 0)
      announcementBatches.current = Number(data.current || page)
      announcementBatches.size = Number(data.size || announcementBatches.size)
    } catch (e: any) {
      if (seq === batchRequestSeq && !disposed) {
        message.error(e?.message || '加载失败')
      }
    } finally {
      if (seq === batchRequestSeq) announcementBatchLoading.value = false
    }
  }

  function handleAnnouncementTabChange(key: string) {
    if (key === 'inbox') void loadAnnouncementInbox(1)
    if (key === 'history') void loadAnnouncementBatches(1)
    if (key === 'status') void loadAnnouncementStatus()
  }

  async function openAnnouncementDetail(item: AnnouncementItem) {
    const aggregateKey = item?.aggregateKey
    if (!aggregateKey) return
    const seq = ++detailRequestSeq
    announcementDetailVisible.value = true
    announcementDetailLoading.value = true
    replaceReactiveObject(announcementDetail, {})
    try {
      const res = await request.post('/sys/announcementPush/inbox/detail', { aggregateKey })
      if (seq !== detailRequestSeq || disposed) return
      replaceReactiveObject(announcementDetail, res.data || {})
    } catch (e: any) {
      if (seq === detailRequestSeq && !disposed) {
        message.error(e?.message || '加载详情失败')
      }
    } finally {
      if (seq === detailRequestSeq) announcementDetailLoading.value = false
    }
  }

  async function markAnnouncement(item: AnnouncementItem, action: AnnouncementMarkAction) {
    const aggregateKey = item?.aggregateKey
    if (!aggregateKey) return
    try {
      await request.post('/sys/announcementPush/inbox/mark', { aggregateKey, action })
      message.success('已更新')
      appQueryCache.invalidate(['systemSettings', 'announcementInbox'])
      await loadAnnouncementInbox(announcementInbox.current, true)
    } catch (e: any) {
      if (!disposed) message.error(e?.message || '操作失败')
    }
  }

  function toggleAnnouncementTenant(id: string, checked: boolean) {
    if (!id) return
    const list = announcementPushConfig.selectedTenantIds
    const idx = list.indexOf(id)
    if (checked && idx < 0) list.push(id)
    if (!checked && idx >= 0) list.splice(idx, 1)
  }

  function addFilteredAnnouncementTenants() {
    const selected = new Set(announcementPushConfig.selectedTenantIds)
    for (const tenant of filteredAnnouncementTenants.value) {
      if (tenant.id) selected.add(tenant.id)
    }
    announcementPushConfig.selectedTenantIds = Array.from(selected)
  }

  function clearAnnouncementTenants() {
    announcementPushConfig.selectedTenantIds = []
    tenantSelectedPage.value = 1
  }

  function tenantMatchesGroupKey(tenant: AnnouncementTenant, key: string) {
    const g1 = tenant.groupLevel1 || '未分组'
    const g2 = tenant.groupLevel2 || ''
    return key === `1|${g1}` || (g2 && key === `2|${g1}|${g2}`)
  }

  function handleAnnouncementRangeChange(value?: AnnouncementInboxRange) {
    const range = value || announcementInboxRange.value
    if (range !== 'custom') {
      announcementInboxDates.value = []
    }
    void loadAnnouncementInbox(1)
  }

  function resolveAnnouncementInboxRange() {
    if (announcementInboxRange.value === 'custom') {
      return {
        startAt: announcementInboxDates.value?.[0] || undefined,
        endAt: announcementInboxDates.value?.[1] || undefined,
      }
    }
    if (announcementInboxRange.value === 'all') {
      return { startAt: undefined, endAt: undefined }
    }
    const now = new Date()
    const start = new Date(now)
    if (announcementInboxRange.value === '24h') start.setHours(start.getHours() - 24)
    if (announcementInboxRange.value === '7d') start.setDate(start.getDate() - 7)
    if (announcementInboxRange.value === '30d') start.setDate(start.getDate() - 30)
    return {
      startAt: formatDateTimeForApi(start),
      endAt: formatDateTimeForApi(now),
    }
  }

  function formatDateTimeForApi(date: Date) {
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  }

  function formatDateTime(value: any) {
    if (!value) return '-'
    try {
      const date = new Date(value)
      if (Number.isNaN(date.getTime())) return String(value)
      return new Intl.DateTimeFormat('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      }).format(date)
    } catch {
      return String(value)
    }
  }

  function formatAnnouncementStatus(value: any) {
    if (announcementScanRunning.value) return '扫描中'
    const key = String(value || '').toUpperCase()
    const labels: Record<string, string> = {
      IDLE: '空闲',
      RUNNING: '扫描中',
      SUCCESS: '成功',
      PARTIAL: '部分失败',
      FAILED: '失败',
      BASELINE: '基线完成',
    }
    return labels[key] || value || '空闲'
  }

  function pruneSelectedTenantIds() {
    if (!announcementTenants.value.length || !announcementPushConfig.selectedTenantIds.length) return
    const validIds = new Set(announcementTenants.value.map((tenant) => tenant.id).filter(Boolean))
    const next = announcementPushConfig.selectedTenantIds.filter((id) => validIds.has(id))
    if (next.length !== announcementPushConfig.selectedTenantIds.length) {
      announcementPushConfig.selectedTenantIds = next
    }
  }

  function resetTenantPickerPage() {
    tenantPickerPage.value = 1
  }

  function normalizeTenantPickerPage() {
    const maxPage = Math.max(1, Math.ceil(filteredAnnouncementTenants.value.length / tenantPickerPageSize.value))
    if (tenantPickerPage.value > maxPage) tenantPickerPage.value = maxPage
    if (tenantPickerPage.value < 1) tenantPickerPage.value = 1
  }

  function normalizeTenantSelectedPage() {
    const maxPage = Math.max(1, Math.ceil(announcementSelectedTenantCount.value / tenantPickerPageSize.value))
    if (tenantSelectedPage.value > maxPage) tenantSelectedPage.value = maxPage
    if (tenantSelectedPage.value < 1) tenantSelectedPage.value = 1
  }

  function applyAnnouncementStatus(data: Record<string, any>, notifyOnFinish: boolean) {
    const wasScanning = announcementStatus.scanning === true
    replaceReactiveObject(announcementStatus, data || {})
    if (announcementStatus.scanning === true) {
      startAnnouncementStatusPolling()
      return
    }
    stopAnnouncementStatusPolling()
    if (notifyOnFinish && wasScanning && announcementScanCompletionNoticeArmed) {
      announcementScanCompletionNoticeArmed = false
      handleAnnouncementScanFinished()
    }
  }

  function markAnnouncementScanStarted() {
    announcementScanCompletionNoticeArmed = true
    Object.assign(announcementStatus, {
      scanning: true,
      status: 'RUNNING',
      scanStartedAt: new Date().toISOString(),
      lastError: '',
    })
    startAnnouncementStatusPolling()
  }

  function startAnnouncementStatusPolling() {
    if (disposed || announcementStatusPollTimer) return
    announcementScanCompletionNoticeArmed = true
    announcementStatusPollTimer = setInterval(() => {
      void loadAnnouncementStatus(true)
    }, ANNOUNCEMENT_STATUS_POLL_MS)
  }

  function stopAnnouncementStatusPolling() {
    if (!announcementStatusPollTimer) return
    clearInterval(announcementStatusPollTimer)
    announcementStatusPollTimer = null
  }

  function handleAnnouncementScanFinished() {
    appQueryCache.invalidate(['systemSettings', 'announcementInbox'])
    const status = String(announcementStatus.status || '').toUpperCase()
    const error = String(announcementStatus.lastError || '').trim()
    if (status === 'FAILED') {
      message.error(error ? `云公告扫描失败：${error}` : '云公告扫描失败')
    } else if (status === 'PARTIAL' || error) {
      message.warning(status === 'PARTIAL'
        ? (error ? `云公告扫描完成，部分租户失败：${error}` : '云公告扫描完成，部分租户失败')
        : `云公告扫描结束：${error}`)
    } else {
      message.success('云公告扫描完成')
    }
    if (announcementTab.value === 'inbox') {
      void loadAnnouncementInbox(1, true)
    }
    if (announcementTab.value === 'history') {
      void loadAnnouncementBatches(1)
    }
  }

  function invalidateAnnouncementPushRequests() {
    configRequestSeq++
    tenantsRequestSeq++
    statusRequestSeq++
    inboxRequestSeq++
    batchRequestSeq++
    detailRequestSeq++
    saveRequestSeq++
    scanRequestSeq++
    statusLoadingRequestCount = 0
    stopAnnouncementStatusPolling()
    announcementConfigLoading.value = false
    announcementTenantsLoading.value = false
    announcementStatusLoading.value = false
    announcementInboxLoading.value = false
    announcementBatchLoading.value = false
    announcementDetailLoading.value = false
    announcementSaveLoading.value = false
    announcementScanLoading.value = false
  }

  onMounted(() => {
    void loadInitialAnnouncementPushData()
  })

  onUnmounted(() => {
    disposed = true
    invalidateAnnouncementPushRequests()
  })

  return {
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
    loadInitialAnnouncementPushData,
    loadAnnouncementPushConfig,
    loadAnnouncementTenants,
    loadAnnouncementStatus,
    saveAnnouncementPushConfig,
    triggerAnnouncementScan,
    loadAnnouncementInbox,
    loadAnnouncementBatches,
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
  }
}

function replaceReactiveObject(target: Record<string, any>, source: Record<string, any>) {
  Object.keys(target).forEach((key) => delete target[key])
  Object.assign(target, source)
}
