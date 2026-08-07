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

function showPopupError(popup: Window, errorText: string) {
  try {
    const doc = popup.document
    doc.open()
    doc.write(`<!doctype html>
      <html lang="zh-CN"><head><meta charset="utf-8"><title>SSH 连接失败</title>
      <style>body{margin:0;background:#0b1020;color:#e5e7eb;font:14px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif;display:grid;min-height:100vh;place-items:center}.box{box-sizing:border-box;width:min(620px,calc(100vw - 32px));border:1px solid #263252;border-radius:10px;background:#121a2f;padding:24px;box-shadow:0 18px 60px #0008}h1{font-size:18px;margin:0 0 14px;color:#f8fafc}.error{white-space:pre-wrap;color:#fda4af;background:#3b1420;border:1px solid #7f1d35;border-radius:8px;padding:13px}.hint{color:#94a3b8;font-size:12px;margin-top:14px}button{margin-top:18px;border:1px solid #475569;border-radius:6px;background:#1e293b;color:#e2e8f0;padding:7px 16px;cursor:pointer}</style></head>
      <body><main class="box"><h1>SSH 连接失败</h1><div id="error" class="error"></div><div class="hint">请处理上面的配置后，从实例菜单重新发起连接。</div><button type="button" onclick="window.close()">关闭窗口</button></main></body></html>`)
    doc.close()
    const node = doc.getElementById('error')
    if (node) node.textContent = errorText
    popup.focus()
  } catch {
    // A browser can revoke access if the popup was already navigated or closed.
  }
}

export function useBastionSshConnect() {
  const visible = ref(false)
  const tenant = ref<any | null>(null)
  const instance = ref<any | null>(null)
  const region = ref('')
  const credentials = ref<BastionCredentialAvailability | null>(null)
  const credentialLoading = ref(false)
  const connecting = ref(false)
  let requestGeneration = 0
  let connectAttempt = 0

  function resetSelection() {
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

    const popup = window.open('', '_blank')
    if (!popup) {
      message.error('浏览器阻止了终端窗口，请允许本站打开新窗口后重试')
      return
    }
    try { popup.opener = null } catch { /* browser may make opener read-only */ }

    const generation = requestGeneration
    const attempt = ++connectAttempt
    connecting.value = true
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
      if (generation !== requestGeneration || attempt !== connectAttempt) {
        if (!popup.closed) popup.close()
        return
      }
      if (popup.closed) {
        message.error('终端窗口已关闭，请重新发起连接')
        return
      }

      const url = buildUrl(response.data.token, response.data)
      setWebSshTokenCookie(getPanelToken())
      popup.location.replace(url)

      connecting.value = false
      visible.value = false
      const sessionLabel = response.data.sessionType === 'PORT_FORWARDING'
        ? '端口转发会话'
        : 'Managed SSH 会话'
      message.success(`${sessionLabel}已准备`)
    } catch (error: any) {
      if (generation === requestGeneration && attempt === connectAttempt) {
        const errorText = bastionErrorMessage(error)
        if (!popup.closed) showPopupError(popup, errorText)
        message.error(errorText.split('\n')[0])
      } else if (!popup.closed) {
        popup.close()
      }
    } finally {
      if (generation === requestGeneration && attempt === connectAttempt) {
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
    open,
    close,
    connect,
  }
}
