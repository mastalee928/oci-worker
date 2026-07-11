import { computed, nextTick, reactive, ref, watch, type Ref } from 'vue'
import { message } from 'ant-design-vue'
import type { UploadFile } from 'ant-design-vue'
import { addTenant, getTenantList, removeTenant, updateTenant, uploadKey } from '../../../api/tenant'
import { loadOciRegionCatalog, ociRegionSelectOptions } from '../../../utils/ociRegionCatalog'
import { appQueryCache, createListSignature } from '../../../utils/queryCache'

type KeyInputMode = 'upload' | 'paste'
type RegionInputMode = 'select' | 'manual'

interface TenantCatalogLike {
  tenants: any[]
  tenantsLoading: boolean
  tenantsError?: string | null
  ensureTenants: (options?: { force?: boolean; keyword?: string; silent?: boolean }) => Promise<any>
  ensureGroups: (options?: { force?: boolean; silent?: boolean }) => Promise<any>
  invalidate: () => void
  removeTenantsFromCache: (ids: string[]) => void
}

interface GroupLoadController {
  setPendingExpandTarget: (target?: { groupLevel1?: string; groupLevel2?: string } | null) => void
  clearPendingExpandTarget: () => void
  applyDefaultExpandAfterLoad: () => void
}

interface UseTenantConfigActionsOptions {
  catalog: TenantCatalogLike
  isMobile: Ref<boolean>
  getGroupLoadController?: () => GroupLoadController | null | undefined
}

const TENANT_SEARCH_STALE_MS = 15_000
const TENANT_PRIVATE_KEY_MAX_BYTES = 64 * 1024
const OCI_REGION_ID_PATTERN = /^[a-z]{2}-[a-z0-9]+(?:-[a-z0-9]+)*-\d+$/
const PEM_PRIVATE_KEY_PATTERN = /-----BEGIN (PRIVATE KEY|RSA PRIVATE KEY|EC PRIVATE KEY)-----([A-Za-z0-9+/=\r\n\s]+)-----END \1-----/

export function useTenantConfigActions(options: UseTenantConfigActionsOptions) {
  const { catalog, isMobile, getGroupLoadController } = options

  const searchLoading = ref(false)
  const submitLoading = ref(false)
  const searchTableData = ref<any[]>([])
  const searchText = ref('')
  const normalizedSearchText = computed(() => searchText.value.trim())
  const isSearchingTenants = computed(() => !!normalizedSearchText.value)
  const tableData = computed(() => (normalizedSearchText.value ? searchTableData.value : catalog.tenants) as any[])
  const loading = computed(() => {
    const rows = tableData.value
    const hasRows = Array.isArray(rows) && rows.length > 0
    if (isSearchingTenants.value) return searchLoading.value && !hasRows
    return catalog.tenantsLoading && !hasRows
  })
  const tenantMobileSearchResetKey = computed(() =>
    `search|${normalizedSearchText.value}|${createListSignature(tableData.value, (r: any) => r.id)}`,
  )

  const modalVisible = ref(false)
  const editingId = ref('')
  const pagination = reactive({ current: 1, pageSize: 10, total: 0 })
  const importText = ref('')
  const fileList = ref<UploadFile[]>([])
  const keyInputMode = ref<KeyInputMode>('upload')
  const pemPasteText = ref('')
  const formErrors = reactive<Record<string, string>>({})
  const regionOptionsLoading = ref(false)
  const regionInputMode = ref<RegionInputMode>('select')
  const regionInputModeOptions = [
    { label: '列表选择', value: 'select' },
    { label: '手动输入', value: 'manual' },
  ]

  const formState = reactive({
    username: '',
    ociTenantId: '',
    ociUserId: '',
    ociFingerprint: '',
    ociRegion: '',
    ociKeyPath: '',
    groupLevel1: '' as string,
    groupLevel2: '' as string | undefined,
  })

  let pendingFile: File | null = null
  let tenantSearchTimer: ReturnType<typeof setTimeout> | null = null
  let tenantSearchRequestSeq = 0
  let tenantInfoPollTimers: ReturnType<typeof setTimeout>[] = []
  let regionOptionsRequestSeq = 0

  function focusFirstFormError() {
    void nextTick(() => {
      const field = document.querySelector('.tenant-form-compact .ant-form-item-has-error input, .tenant-form-compact .ant-form-item-has-error textarea') as HTMLElement | null
      field?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      field?.focus()
    })
  }

  function groupController() {
    return getGroupLoadController?.()
  }

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
    const parsedRegion = normalizeOciRegionValue(fields['region'])
    if (parsedRegion) {
      formState.ociRegion = parsedRegion
      syncRegionInputMode(parsedRegion)
    }
    message.success('已解析并填充，请上传或粘贴私钥后提交')
  }

  async function loadData(expandAfter?: { groupLevel1?: string; groupLevel2?: string }) {
    if (expandAfter && typeof expandAfter === 'object') {
      groupController()?.setPendingExpandTarget(expandAfter)
    }
    const keyword = normalizedSearchText.value
    if (keyword) {
      const requestSeq = ++tenantSearchRequestSeq
      searchLoading.value = true
      try {
        const page = pagination.current
        const size = pagination.pageSize
        const res = await appQueryCache.fetch(
          ['tenantConfig', 'search', keyword, page, size],
          () => getTenantList({ current: page, size, keyword }),
          { staleMs: TENANT_SEARCH_STALE_MS },
        )
        if (requestSeq === tenantSearchRequestSeq && normalizedSearchText.value === keyword) {
          searchTableData.value = res.data.records || []
          pagination.total = res.data.total || 0
          scheduleTenantInfoPollingIfNeeded(searchTableData.value)
        }
      } catch (e: any) {
        if (requestSeq === tenantSearchRequestSeq && normalizedSearchText.value === keyword) {
          message.error(e?.message || '加载租户列表失败')
        }
      } finally {
        if (requestSeq === tenantSearchRequestSeq) {
          searchLoading.value = false
        }
      }
      return
    }
    tenantSearchRequestSeq += 1
    searchLoading.value = false
    searchTableData.value = []
    try {
      await Promise.all([
        catalog.ensureTenants({ force: false }),
        catalog.ensureGroups({ force: false }),
      ])
    } catch (e: any) {
      message.error(e?.message || catalog.tenantsError || '加载租户列表失败')
    }
    groupController()?.applyDefaultExpandAfterLoad()
    scheduleTenantInfoPollingIfNeeded(catalog.tenants as any[])
  }

  function onSearchTenants() {
    groupController()?.clearPendingExpandTarget()
    pagination.current = 1
    clearTenantSearchTimer()
    clearTenantInfoPollTimers()
    void loadData()
  }

  watch(searchText, () => {
    groupController()?.clearPendingExpandTarget()
    pagination.current = 1
    clearTenantSearchTimer()
    clearTenantInfoPollTimers()
    if (!normalizedSearchText.value) {
      tenantSearchRequestSeq += 1
      searchLoading.value = false
      searchTableData.value = []
      pagination.total = 0
    }
    tenantSearchTimer = setTimeout(() => {
      tenantSearchTimer = null
      void loadData()
    }, 250)
  })

  function invalidateCatalogAndReload() {
    catalog.invalidate()
    appQueryCache.invalidate(['tenantConfig'])
    void loadData()
  }

  function clearTenantSearchTimer() {
    if (tenantSearchTimer) {
      clearTimeout(tenantSearchTimer)
      tenantSearchTimer = null
    }
  }

  function clearTenantInfoPollTimers() {
    for (const timer of tenantInfoPollTimers) clearTimeout(timer)
    tenantInfoPollTimers = []
  }

  async function refreshTenantListSilently() {
    const keyword = normalizedSearchText.value
    try {
      if (keyword) {
        const requestSeq = tenantSearchRequestSeq
        const page = pagination.current
        const size = pagination.pageSize
        const res = await appQueryCache.fetch(
          ['tenantConfig', 'search', keyword, page, size],
          () => getTenantList({ current: page, size, keyword }),
          { staleMs: TENANT_SEARCH_STALE_MS, force: true },
        )
        if (requestSeq === tenantSearchRequestSeq && normalizedSearchText.value === keyword) {
          searchTableData.value = res.data.records || []
          pagination.total = res.data.total || 0
          clearTenantInfoPollingIfComplete(searchTableData.value)
        }
        return
      }
      catalog.invalidate()
      await Promise.all([
        catalog.ensureTenants({ force: true, silent: true }),
        catalog.ensureGroups({ force: false, silent: true }),
      ])
      clearTenantInfoPollingIfComplete(catalog.tenants as any[])
    } catch {
      // 静默轮询只负责把后台刷新结果带回页面，失败时保留当前显示。
    }
  }

  function scheduleTenantInfoPolling() {
    clearTenantInfoPollTimers()
    for (const delay of [3000, 8000, 15000, 30000]) {
      tenantInfoPollTimers.push(setTimeout(() => {
        void refreshTenantListSilently()
      }, delay))
    }
  }

  function scheduleTenantInfoPollingIfNeeded(rows: any[]) {
    if (!Array.isArray(rows) || rows.length === 0) {
      clearTenantInfoPollTimers()
      return
    }
    const hasPending = rows.some((r: any) => !r?.tenantName || !r?.planType)
    if (hasPending) {
      scheduleTenantInfoPolling()
    } else {
      clearTenantInfoPollTimers()
    }
  }

  function clearTenantInfoPollingIfComplete(rows: any[]) {
    if (!Array.isArray(rows) || rows.length === 0) {
      clearTenantInfoPollTimers()
      return
    }
    if (!rows.some((r: any) => !r?.tenantName || !r?.planType)) {
      clearTenantInfoPollTimers()
    }
  }

  function resetForm() {
    Object.assign(formState, {
      username: '',
      ociTenantId: '',
      ociUserId: '',
      ociFingerprint: '',
      ociRegion: '',
      ociKeyPath: '',
      groupLevel1: '',
      groupLevel2: '',
    })
    regionInputMode.value = 'select'
    pendingFile = null
    fileList.value = []
    importText.value = ''
    pemPasteText.value = ''
    keyInputMode.value = isMobile.value ? 'paste' : 'upload'
  }

  function onKeyInputModeChange(mode: KeyInputMode = keyInputMode.value) {
    formErrors.privateKey = ''
    if (mode === 'upload') {
      pemPasteText.value = ''
    } else {
      pendingFile = null
      fileList.value = []
    }
  }

  function extractPrivateKeyPem(text: string): { pem: string; error: string | null } {
    const normalized = text.replace(/^\uFEFF/, '')
    const match = PEM_PRIVATE_KEY_PATTERN.exec(normalized)
    if (!match) {
      if (/-----BEGIN (?:PUBLIC KEY|RSA PUBLIC KEY)-----/.test(normalized)) {
        return { pem: '', error: '当前内容是公钥，请使用 OCI API 私钥' }
      }
      if (/-----BEGIN ENCRYPTED PRIVATE KEY-----/.test(normalized)) {
        return { pem: '', error: '暂不支持加密私钥，请使用未加密的 OCI API 私钥' }
      }
      return { pem: '', error: '未找到完整的 PEM 私钥内容' }
    }
    try {
      if (atob(match[2].replace(/\s/g, '')).length <= 0) throw new Error('empty key')
    } catch {
      return { pem: '', error: 'PEM 私钥内容损坏或 Base64 格式无效' }
    }
    const pem = `-----BEGIN ${match[1]}-----${match[2]}-----END ${match[1]}-----\n`
    return { pem, error: null }
  }

  async function validatePrivateKeyFile(file: File): Promise<string | null> {
    if (file.size <= 0) return '私钥文件为空'
    if (file.size > TENANT_PRIVATE_KEY_MAX_BYTES) return '私钥文件不能超过 64 KB'
    try {
      const text = await file.text()
      return extractPrivateKeyPem(text).error
    } catch {
      return '无法读取私钥文件，请重新选择'
    }
  }

  function pemPasteTextToFile(text: string): File {
    return new File([extractPrivateKeyPem(text).pem], 'pasted.pem', { type: 'application/x-pem-file' })
  }

  function normalizeOciRegionValue(value?: string) {
    return String(value || '').trim().toLowerCase()
  }

  function hasOciRegionOption(value?: string) {
    const normalized = normalizeOciRegionValue(value)
    if (!normalized) return false
    return ociRegionSelectOptions.value.some((opt) => opt.value === normalized)
  }

  function syncRegionInputMode(value?: string) {
    const normalized = normalizeOciRegionValue(value)
    regionInputMode.value = normalized && !hasOciRegionOption(normalized) ? 'manual' : 'select'
  }

  function normalizeRegionInput() {
    formState.ociRegion = normalizeOciRegionValue(formState.ociRegion)
  }

  async function refreshRegionOptionsForForm(userId?: string) {
    const requestSeq = ++regionOptionsRequestSeq
    regionOptionsLoading.value = true
    try {
      await loadOciRegionCatalog(userId)
    } finally {
      if (requestSeq === regionOptionsRequestSeq) {
        regionOptionsLoading.value = false
      }
    }
  }

  function showAddModal() {
    editingId.value = ''
    resetForm()
    modalVisible.value = true
    void refreshRegionOptionsForForm()
  }

  function showEditModal(record: any) {
    editingId.value = record.id
    const normalizedRegion = normalizeOciRegionValue(record.ociRegion)
    Object.assign(formState, {
      username: record.username,
      ociTenantId: record.ociTenantId,
      ociUserId: record.ociUserId,
      ociFingerprint: record.ociFingerprint,
      ociRegion: normalizedRegion,
      ociKeyPath: record.ociKeyPath,
      groupLevel1: record.groupLevel1 || '',
      groupLevel2: record.groupLevel2 || undefined,
    })
    syncRegionInputMode(normalizedRegion)
    pendingFile = null
    fileList.value = []
    importText.value = ''
    pemPasteText.value = ''
    keyInputMode.value = 'upload'
    modalVisible.value = true
    void refreshRegionOptionsForForm(record.id)
  }

  async function handleUpload(file: File) {
    formErrors.privateKey = ''
    const validationError = await validatePrivateKeyFile(file)
    if (validationError) {
      pendingFile = null
      fileList.value = []
      formErrors.privateKey = validationError
      message.warning(validationError)
      return false
    }
    pendingFile = file
    fileList.value = [{ uid: '-1', name: file.name, status: 'done' } as UploadFile]
    return false
  }

  function handleRemoveFile() {
    pendingFile = null
    fileList.value = []
  }

  watch(() => formState.username, () => { formErrors.username = '' })
  watch(() => formState.ociTenantId, () => { formErrors.ociTenantId = '' })
  watch(() => formState.ociUserId, () => { formErrors.ociUserId = '' })
  watch(() => formState.ociFingerprint, () => { formErrors.ociFingerprint = '' })
  watch(() => formState.ociRegion, () => { formErrors.ociRegion = '' })
  watch(pemPasteText, () => { formErrors.privateKey = '' })

  async function handleSubmit() {
    if (submitLoading.value) return
    const normalizedRegion = normalizeOciRegionValue(formState.ociRegion)
    Object.keys(formErrors).forEach((key) => { formErrors[key] = '' })
    if (!formState.username.trim()) formErrors.username = '请输入自定义名称'
    if (!formState.ociTenantId.trim()) formErrors.ociTenantId = '请输入 Tenant OCID'
    if (!formState.ociUserId.trim()) formErrors.ociUserId = '请输入 User OCID'
    if (!formState.ociFingerprint.trim()) formErrors.ociFingerprint = '请输入 Fingerprint'
    if (!normalizedRegion) formErrors.ociRegion = '请选择或输入 Region'
    if (Object.values(formErrors).some(Boolean)) {
      message.warning('请填写所有必填项')
      focusFirstFormError()
      return
    }
    if (regionInputMode.value === 'manual' && !OCI_REGION_ID_PATTERN.test(normalizedRegion) && !hasOciRegionOption(normalizedRegion)) {
      message.warning('Region ID 格式不正确，例如 eu-turin-1')
      return
    }
    formState.ociRegion = normalizedRegion

    submitLoading.value = true
    try {
      let keyPath = formState.ociKeyPath
      let fileToUpload: File | null = pendingFile
      if (!fileToUpload && keyInputMode.value === 'paste' && pemPasteText.value.trim()) {
        if (new Blob([pemPasteText.value]).size > TENANT_PRIVATE_KEY_MAX_BYTES) {
          message.warning('私钥内容不能超过 64 KB')
          return
        }
        const extracted = extractPrivateKeyPem(pemPasteText.value)
        if (extracted.error) {
          formErrors.privateKey = extracted.error
          message.warning(extracted.error)
          focusFirstFormError()
          return
        }
        fileToUpload = pemPasteTextToFile(pemPasteText.value)
      }
      if (fileToUpload) {
        const fileValidationError = await validatePrivateKeyFile(fileToUpload)
        if (fileValidationError) {
          formErrors.privateKey = fileValidationError
          message.warning(fileValidationError)
          focusFirstFormError()
          return
        }
        const fd = new FormData()
        fd.append('file', fileToUpload)
        const uploadRes = await uploadKey(fd)
        keyPath = uploadRes.data
      }

      if (!keyPath && !editingId.value) {
        formErrors.privateKey = keyInputMode.value === 'paste' ? '请粘贴 PEM 私钥' : '请上传私钥文件'
        message.warning(formErrors.privateKey)
        focusFirstFormError()
        return
      }

      const data = { ...formState, ociRegion: normalizedRegion, ociKeyPath: keyPath }
      if (editingId.value) {
        await updateTenant({ id: editingId.value, ...data })
        message.success('更新成功')
      } else {
        await addTenant(data)
        message.success('添加成功')
      }
      modalVisible.value = false
      catalog.invalidate()
      await loadData({
        groupLevel1: formState.groupLevel1,
        groupLevel2: formState.groupLevel2,
      })
      scheduleTenantInfoPolling()
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
      catalog.invalidate()
      catalog.removeTenantsFromCache([id])
      void loadData()
    } catch (e: any) {
      message.error(e?.message || '删除失败')
    }
  }

  return {
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
    formErrors,
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
    onKeyInputModeChange,
    normalizeRegionInput,
    showAddModal,
    showEditModal,
    handleUpload,
    handleRemoveFile,
    handleSubmit,
    handleDelete,
  }
}
