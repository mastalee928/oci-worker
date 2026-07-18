const TOKEN_KEY = 'token'
const ACCOUNT_KEY = 'account'
const WEBSSH_TOKEN_COOKIE = 'ow_panel_token'
const WEBSSH_CONNECTION_BOOKMARK_KEY = 'webssh_conn_bm'
const WEBSSH_PROXY_KEY = 'webssh_proxy'
let runtimeValidatedToken = ''

function cookieAttrs() {
  const secure = window.location.protocol === 'https:' ? '; Secure' : ''
  return `; Path=/; SameSite=Lax${secure}`
}

export function getPanelToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setWebSshTokenCookie(token?: string) {
  const value = token?.trim()
  if (!value) {
    document.cookie = `${WEBSSH_TOKEN_COOKIE}=; Max-Age=0${cookieAttrs()}`
    return
  }
  document.cookie = `${WEBSSH_TOKEN_COOKIE}=${value}${cookieAttrs()}`
}

export function setPanelToken(token: string) {
  const value = token.trim()
  if (value) {
    localStorage.setItem(TOKEN_KEY, value)
    setWebSshTokenCookie(value)
  } else {
    localStorage.removeItem(TOKEN_KEY)
    setWebSshTokenCookie('')
  }
}

/** 标记本次页面运行期间已经由登录接口签发或服务端验证过的 token。 */
export function markPanelSessionValidated(token: string) {
  runtimeValidatedToken = token.trim()
}

export function isPanelSessionValidated(token: string) {
  const value = token.trim()
  return !!value && value === runtimeValidatedToken
}

export function getPanelAccount() {
  return localStorage.getItem(ACCOUNT_KEY) || ''
}

export function setPanelAccount(account: string) {
  const value = account.trim()
  if (value) localStorage.setItem(ACCOUNT_KEY, value)
  else localStorage.removeItem(ACCOUNT_KEY)
}

export function clearPanelSession() {
  runtimeValidatedToken = ''
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ACCOUNT_KEY)
  setWebSshTokenCookie('')
}

/** 清除旧版 WebSSH 曾写入 localStorage 的 SSH/代理明文密码。 */
export function scrubLegacyWebSshStoredSecrets() {
  try {
    const raw = localStorage.getItem(WEBSSH_CONNECTION_BOOKMARK_KEY)
    if (raw) {
      const bookmarks = JSON.parse(raw)
      if (Array.isArray(bookmarks)) {
        let changed = false
        for (const item of bookmarks) {
          if (item && typeof item === 'object' && 'password' in item) {
            delete item.password
            changed = true
          }
        }
        if (changed) localStorage.setItem(WEBSSH_CONNECTION_BOOKMARK_KEY, JSON.stringify(bookmarks))
      }
    }
  } catch {
    localStorage.removeItem(WEBSSH_CONNECTION_BOOKMARK_KEY)
  }
  try {
    const raw = localStorage.getItem(WEBSSH_PROXY_KEY)
    if (raw) {
      const proxy = JSON.parse(raw)
      if (proxy && typeof proxy === 'object' && 'pass' in proxy) {
        delete proxy.pass
        localStorage.setItem(WEBSSH_PROXY_KEY, JSON.stringify(proxy))
      }
    }
  } catch {
    localStorage.removeItem(WEBSSH_PROXY_KEY)
  }
}
