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
  if (normalized.includes('client-cidr-block-allow-list')
      || normalized.includes('client cidr allow-list')) {
    return [
      'OCI Bastion 尚未配置客户端 CIDR 白名单。',
      '请在 OCI Worker 配置中设置 OCI_BASTION_CLIENT_CIDR_ALLOW_LIST=<Worker 公网出口 IP>/32，设置后重启服务。',
      '不要使用 0.0.0.0/0。',
    ].join('\n')
  }
  return raw || '堡垒机连接准备失败'
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
      if (!connecting.value || connectionStep.value >= 3) return
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
      const popup = window.open(url, '_blank')
      if (!popup) {
        connectionStep.value = 3
        connectionError.value = '浏览器阻止了 WebSSH 新窗口，请允许本站弹出窗口后重试。'
        return
      }
      try { popup.opener = null } catch { /* browser may make opener read-only */ }

      connecting.value = false
      visible.value = false
      const sessionLabel = response.data.sessionType === 'PORT_FORWARDING'
        ? '端口转发会话'
        : 'Managed SSH 会话'
      message.success(`${sessionLabel}已准备`)
    } catch (error: any) {
      if (generation === requestGeneration && attempt === connectAttempt) {
        connectionError.value = bastionErrorMessage(error)
        if (connectionStep.value < 0) connectionStep.value = 0
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
