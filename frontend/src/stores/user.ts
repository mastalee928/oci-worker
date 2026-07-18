import { defineStore } from 'pinia'
import { computed } from 'vue'
import {
  clearPanelSession,
  getPanelAccount,
  getPanelToken,
  markPanelSessionValidated,
  setPanelAccount,
  setPanelToken,
} from '../utils/session'

export const useUserStore = defineStore('user', () => {
  const token = computed<string>({
    get: () => getPanelToken(),
    set: (v: string) => setPanelToken(v),
  })

  const account = computed<string>({
    get: () => getPanelAccount(),
    set: (v: string) => setPanelAccount(v),
  })

  const displayName = computed(() => account.value.trim() || 'Admin')

  const avatarLetter = computed(() => {
    const n = account.value.trim()
    if (!n) return 'A'
    return n.charAt(0).toUpperCase()
  })

  function setToken(t: string) {
    setPanelToken(t)
  }

  function setAccount(name: string) {
    setPanelAccount(name)
  }

  /** 登录成功后写入 token 与用户名 */
  function setLoginSession(t: string, name?: string) {
    setToken(t)
    markPanelSessionValidated(t)
    if (name?.trim()) setAccount(name.trim())
  }

  function logout() {
    clearPanelSession()
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
