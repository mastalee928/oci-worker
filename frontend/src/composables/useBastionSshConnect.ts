import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  getBastionCredentialAvailability,
  prepareBastionSession,
  type BastionCredentialAvailability,
  type BastionLoginMode,
  type BastionPrepareRequest,
} from '../api/bastion'
import { getPanelToken, setWebSshTokenCookie } from '../utils/session'

export interface BastionConnectForm {
  loginMode: BastionLoginMode
  username: string
  passwordSource: 'saved' | 'manual'
  password: string
  privateKey: string
  passphrase: string
}

function bastionErrorMessage(error: any) {
  const raw = String(
    error?.response?.data?.message
      || error?.message
      || '',
  ).trim()
  const normalized = raw.toLowerCase()
  // 永远保留服务端原始错误（含 OCI 详情与 opc-request-id），提示语只作补充。
  const withDetail = (hint: string) => (raw ? `${hint}\n服务端详情：${raw}` : hint)
  if (normalized.includes('timed out waiting for oci bastion ssh session')
      || normalized.includes('bastion ssh session failed')) {
    return withDetail('OCI Bastion SSH 会话未在官方等待窗口内变为 ACTIVE。请检查实例 SSH 22 入站规则、Cloud Agent Bastion 插件和目标私网路由后重试。')
  }
  if (normalized.includes('timed out waiting for oci bastion')
      || normalized.includes('timed out waiting for bastion work request')
      || normalized.includes('bastion work request failed')
      || normalized.includes('create or reuse bastion failed')) {
    return withDetail('OCI Bastion 创建或工作请求超时。请稍后重试；若持续失败，请检查 Bastion 所在子网、VCN 路由和 IAM，并使用错误中的 opc-request-id 查询 OCI 日志。')
  }
  if (normalized.includes('client-cidr-block-allow-list')
      || normalized.includes('client cidr allow-list')
      || normalized.includes('client cidr could not be determined')) {
    return withDetail([
      'OCI Bastion 尚未配置客户端 CIDR 白名单。',
      '请在 OCI Worker 配置中设置 OCI_BASTION_CLIENT_CIDR_ALLOW_LIST=<Worker 公网出口 IP>/32，设置后重启服务。',
      '不要使用 0.0.0.0/0。',
    ].join('\n'))
  }
  if (normalized.includes('private endpoint') && normalized.includes('does not allow tcp')) {
    return withDetail('目标实例的安全列表或 NSG 未允许 OCI Bastion 私有端点访问 SSH 22 端口。请按错误中的 Bastion IP/32 添加入站规则后重试。')
  }
  if (normalized.includes('private subnet route')
      || normalized.includes('target subnet route table')
      || normalized.includes('usable route to a service')
      || normalized.includes('internet gateway')
      || normalized.includes('nat gateway')) {
    return withDetail('OCI Bastion 找不到可用的网关路由。请为目标 VCN 配置 Service Gateway、NAT Gateway 或 Internet Gateway 及对应路由后重试。')
  }
  if (normalized.includes('available private subnet')
      || normalized.includes('no available subnet')
      || raw.includes('没有可用的私有子网')
      || raw.includes('没有可用子网')) {
    return withDetail('目标 VCN 中没有可用于 OCI Bastion 的子网。系统会优先使用实例所在子网，并兼容同 AD 或区域子网；请检查子网状态和网关路由。')
  }
  return raw || '堡垒机连接准备失败'
}

function bastionFailureStep(error: any) {
  const raw = String(error?.response?.data?.message || error?.message || '').toLowerCase()
  if (raw.includes('timed out waiting for oci bastion ssh session')
      || raw.includes('bastion ssh session failed')
      || raw.includes('create bastion ssh session')) return 2
  if (raw.includes('timed out waiting for oci bastion')
      || raw.includes('timed out waiting for bastion work request')
      || raw.includes('bastion work request failed')
      || raw.includes('create or reuse bastion')
      || raw.includes('bastion creation failed')) return 1
  if (raw.includes('private subnet')
      || raw.includes('available subnet')
      || raw.includes('service gateway')
      || raw.includes('route table')
      || raw.includes('internet gateway')
      || raw.includes('nat gateway')
      || raw.includes('cloud agent')
      || raw.includes('target instance')
      || raw.includes('私有子网')
      || raw.includes('可用子网')
      || raw.includes('目标 vcn')
      || raw.includes('路由')
      || raw.includes('可用域')) return 0
  if (raw.includes('client cidr')
      || raw.includes('client-cidr')
      || raw.includes('private endpoint')
      || raw.includes('bastion creation')) return 1
  if (raw.includes('bastion session')
      || raw.includes('ssh session')
      || raw.includes('work request')) return 2
  return 0
}

export function useBastionSshConnect() {
  const visible = ref(false)
  const tenant = ref<any | null>(null)
  const instance = ref<any | null>(null)
  const region = ref('')
  const credentials = ref<BastionCredentialAvailability | null>(null)
  const credentialLoading = ref(false)
  const connecting = ref(false)
  const connectionStep = ref(-1)
  const connectionError = ref('')
  let requestGeneration = 0
  let connectAttempt = 0
  let progressTimer: ReturnType<typeof setTimeout> | undefined

  function clearProgressTimer() {
    if (progressTimer !== undefined) {
      clearTimeout(progressTimer)
      progressTimer = undefined
    }
  }

  function resetConnectionState() {
    clearProgressTimer()
    connectionStep.value = -1
    connectionError.value = ''
  }

  function startProgress() {
    clearProgressTimer()
    connectionStep.value = 0
    const advance = () => {
      if (!connecting.value || connectionStep.value >= 2) return
      connectionStep.value += 1
      progressTimer = setTimeout(advance, 1600)
    }
    progressTimer = setTimeout(advance, 850)
  }

  function resetSelection() {
    resetConnectionState()
    credentials.value = null
    tenant.value = null
    instance.value = null
    region.value = ''
    credentialLoading.value = false
  }

  async function loadCredentials(generation: number, tenantId: string, instanceId: string) {
    credentialLoading.value = true
    try {
      const response = await getBastionCredentialAvailability({ id: tenantId, instanceId })
      if (generation === requestGeneration) credentials.value = response.data
    } catch (error: any) {
      if (generation === requestGeneration) {
        credentials.value = null
        message.error(error?.message || '无法读取实例登录凭据')
      }
    } finally {
      if (generation === requestGeneration) credentialLoading.value = false
    }
  }

  function open(recordTenant: any, recordInstance: any, regionValue?: string) {
    const tenantId = String(recordTenant?.id || '').trim()
    const instanceId = String(recordInstance?.instanceId || '').trim()
    if (!tenantId || !instanceId) {
      message.error('实例信息不完整')
      return
    }
    if (connecting.value) {
      message.info('堡垒机会话正在准备，请稍候')
      return
    }
    requestGeneration += 1
    connectAttempt += 1
    const generation = requestGeneration
    tenant.value = recordTenant
    instance.value = recordInstance
    region.value = String(
      regionValue || recordInstance?.region || recordTenant?.ociRegion || '',
    ).trim()
    credentials.value = null
    credentialLoading.value = false
    resetConnectionState()
    visible.value = true
    void loadCredentials(generation, tenantId, instanceId)
  }

  function close(force = false) {
    if (connecting.value && !force) return
    visible.value = false
  }

  function buildUrl(token: string, result: { targetPrivateIp?: string; targetUsername?: string }) {
    const params = new URLSearchParams({
      bastion: '1',
      token,
      label: String(instance.value?.displayName || instance.value?.name || instance.value?.instanceId || 'Bastion SSH'),
    })
    if (region.value) params.set('region', region.value)
    if (result.targetPrivateIp) params.set('targetPrivateIp', result.targetPrivateIp)
    if (result.targetUsername) params.set('targetUsername', result.targetUsername)
    return `/webssh/index.html#${params.toString()}`
  }

  async function connect(form: BastionConnectForm) {
    const currentTenant = tenant.value
    const currentInstance = instance.value
    const tenantId = String(currentTenant?.id || '').trim()
    const instanceId = String(currentInstance?.instanceId || '').trim()
    if (!tenantId || !instanceId || connecting.value) return

    const generation = requestGeneration
    const attempt = ++connectAttempt
    connectionError.value = ''
    connecting.value = true
    startProgress()

    try {
      const payload: BastionPrepareRequest = {
        id: tenantId,
        instanceId,
        region: region.value || undefined,
        compartmentId: currentInstance?.compartmentId || undefined,
        loginMode: form.loginMode,
        username: form.username.trim() || undefined,
      }
      if (form.loginMode === 'PASSWORD') {
        if (form.passwordSource === 'manual') payload.password = form.password
      } else {
        payload.privateKey = form.privateKey
        payload.passphrase = form.passphrase || undefined
      }

      const response = await prepareBastionSession(payload)
      if (generation !== requestGeneration || attempt !== connectAttempt) return

      connectionStep.value = 4
      const url = buildUrl(response.data.token, response.data)
      setWebSshTokenCookie(getPanelToken())
      connecting.value = false
      visible.value = false
      const sessionLabel = response.data.sessionType === 'PORT_FORWARDING'
        ? '端口转发会话'
        : 'Managed SSH 会话'
      message.success(`${sessionLabel}已准备`)
      window.location.assign(url)
    } catch (error: any) {
      if (generation === requestGeneration && attempt === connectAttempt) {
        connectionError.value = bastionErrorMessage(error)
        connectionStep.value = bastionFailureStep(error)
      }
    } finally {
      if (generation === requestGeneration && attempt === connectAttempt) {
        clearProgressTimer()
        connecting.value = false
      }
    }
  }

  watch(visible, (openValue) => {
    if (openValue) return
    requestGeneration += 1
    connectAttempt += 1
    connecting.value = false
    resetSelection()
  })

  return {
    visible,
    tenant,
    instance,
    region,
    credentials,
    credentialLoading,
    connecting,
    connectionStep,
    connectionError,
    open,
    close,
    connect,
  }
}
