const TOKEN_KEY = 'token'
const ACCOUNT_KEY = 'account'
const WEBSSH_TOKEN_COOKIE = 'ow_panel_token'

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

export function getPanelAccount() {
  return localStorage.getItem(ACCOUNT_KEY) || ''
}

export function setPanelAccount(account: string) {
  const value = account.trim()
  if (value) localStorage.setItem(ACCOUNT_KEY, value)
  else localStorage.removeItem(ACCOUNT_KEY)
}

export function clearPanelSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ACCOUNT_KEY)
  setWebSshTokenCookie('')
}
