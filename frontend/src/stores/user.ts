import { defineStore } from 'pinia'
import { computed } from 'vue'

const TOKEN_KEY = 'token'
const ACCOUNT_KEY = 'account'

export const useUserStore = defineStore('user', () => {
  const token = computed<string>({
    get: () => localStorage.getItem(TOKEN_KEY) || '',
    set: (v: string) => {
      if (v) localStorage.setItem(TOKEN_KEY, v)
      else localStorage.removeItem(TOKEN_KEY)
    },
  })

  const account = computed<string>({
    get: () => localStorage.getItem(ACCOUNT_KEY) || '',
    set: (v: string) => {
      if (v) localStorage.setItem(ACCOUNT_KEY, v)
      else localStorage.removeItem(ACCOUNT_KEY)
    },
  })

  const displayName = computed(() => account.value.trim() || 'Admin')

  const avatarLetter = computed(() => {
    const n = account.value.trim()
    if (!n) return 'A'
    return n.charAt(0).toUpperCase()
  })

  function setToken(t: string) {
    if (t) localStorage.setItem(TOKEN_KEY, t)
    else localStorage.removeItem(TOKEN_KEY)
  }

  function setAccount(name: string) {
    if (name) localStorage.setItem(ACCOUNT_KEY, name)
    else localStorage.removeItem(ACCOUNT_KEY)
  }

  /** 登录成功后写入 token 与用户名 */
  function setLoginSession(t: string, name?: string) {
    setToken(t)
    if (name?.trim()) setAccount(name.trim())
  }

  function logout() {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(ACCOUNT_KEY)
  }

  return {
    token,
    account,
    displayName,
    avatarLetter,
    setToken,
    setAccount,
    setLoginSession,
    logout,
  }
})
