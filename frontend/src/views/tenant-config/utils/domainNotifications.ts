// 域管理相关的纯常量与纯函数（无响应式状态），由 TenantConfig 域管理逻辑抽出，
// 供 useTenantDomainManagement composable 及域管理弹窗复用。

export type FactorOption = { key: string; label: string }

const DOMAIN_TYPE_CN: Record<string, string> = {
  DEFAULT: '默认域',
  PRIMARY: '主域',
  SECONDARY: '辅助域',
  EXTERNAL: '外部域',
}

export function domainTypeCn(t: string | null | undefined): string {
  if (!t) return ''
  const key = String(t).toUpperCase()
  return DOMAIN_TYPE_CN[key] || t
}

export const notificationValidateOptions = [
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

export const NOTIFICATION_EVENT_LABELS: Record<string, string> = {
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

export function isUnlockExpiredMessage(msg: any): boolean {
  const text = String(msg || '')
  return text.includes('解锁') || text.includes('失效') || text.includes('过期')
}

export function normalizeDomainNotification(raw: any) {
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

export function parseNotificationRecipients(text: string): string[] {
  return Array.from(new Set(
    String(text || '')
      .split(/[,，;；\n\r]+/)
      .map((s) => s.trim())
      .filter(Boolean),
  ))
}

export function formatNotificationValidationStatus(status: string | null | undefined): string {
  const s = String(status || '').toUpperCase()
  if (s === 'VERIFIED') return '已验证'
  if (s === 'PENDING') return '待验证'
  return '—'
}

export function notificationValidationStatusColor(status: string | null | undefined): string {
  const s = String(status || '').toUpperCase()
  if (s === 'VERIFIED') return 'green'
  if (s === 'PENDING') return 'orange'
  return 'default'
}

export function normalizeNotificationEventId(eventId: string | null | undefined): string {
  return String(eventId || '')
    .trim()
    .toLowerCase()
    .replace(/[-_]+/g, '.')
    .replace(/\.+/g, '.')
}

export function isAdminNotificationEvent(eventId: string | null | undefined): boolean {
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

export function formatNotificationEventName(eventId: string | null | undefined): string {
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

export const FACTOR_OPTIONS: FactorOption[] = [
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
