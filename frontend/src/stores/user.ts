import { defineStore } from 'pinia'
import { computed } from 'vue'

export const useUserStore = defineStore('user', () => {
  // Always read from localStorage so Pinia state stays in sync with direct localStorage writes.
  const token = computed<string>({
    get: () => localStorage.getItem('token') || '',
    set: (v: string) => {
      if (v) localStorage.setItem('token', v)
      else localStorage.removeItem('token')
    },
  })

  function setToken(t: string) {
    if (t) localStorage.setItem('token', t)
    else localStorage.removeItem('token')
  }

  function logout() {
    localStorage.removeItem('token')
  }

  return { token, setToken, logout }
})
