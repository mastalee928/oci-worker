import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  getDomainSettings,
  updateMfa,
  updatePasswordExpiry,
  unlockDomainNotifications,
  getDomainNotifications,
  updateDomainNotifications,
  getAuditLogs,
  unlockAuthFactors,
  getAuthFactors,
  updateAuthFactors,
} from '../../../api/tenant'
import { sendVerifyCode } from '../../../api/system'
import {
  domainTypeCn,
  notificationValidateOptions,
  NOTIFICATION_EVENT_LABELS,
  FACTOR_OPTIONS,
  isUnlockExpiredMessage,
  normalizeDomainNotification,
  parseNotificationRecipients,
  formatNotificationValidationStatus,
  notificationValidationStatusColor,
  formatNotificationEventName,
  isAdminNotificationEvent,
} from '../utils/domainNotifications'

export function useTenantDomainManagement() {
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

  function resetDomainManagementState() {
    domainSettingsRequestSeq++
    domainSettingsLoading.value = false
    domainMgmtTenant.value = null
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
  }

  function closeDomainMgmt() {
    if (domainMgmtVisible.value) {
      domainMgmtVisible.value = false
    } else {
      resetDomainManagementState()
    }
  }

  async function openDomainManagementWorkspace(record: any) {
    resetDomainManagementState()
    domainMgmtTenant.value = record
    domainTab.value = 'security'
    domainMgmtVisible.value = true
    await loadDomainSettings()
  }

  watch(() => domainMgmtVisible.value, (v) => {
    if (!v) resetDomainManagementState()
  }, { flush: 'sync' })

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

  return {
    // 域壳 + 域列表
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
    // 安全策略 · MFA
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
    // 安全策略 · 密码过期
    pwdExpiryUpdatingId,
    handlePwdExpiryChange,
    // 域通知
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
    // 验证因素
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
    // 登录日志 / 审计日志
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
  }
}
