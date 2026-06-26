import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import { message } from 'ant-design-vue'
import router from '../router'

export interface OciRequestConfig extends AxiosRequestConfig {
  /** 业务 code≠0 时不弹全局 message（由调用方自行提示） */
  skipBusinessMessage?: boolean
  /** 网络/HTTP 错误时不弹全局 message（由调用方自行提示） */
  skipErrorMessage?: boolean
}

const request = axios.create({
  baseURL: '/api',
  timeout: 60000,
  withCredentials: true,
})

function getToken() {
  const t = localStorage.getItem('token')
  return t ? t.trim() : ''
}

function clearSession() {
  localStorage.removeItem('token')
  localStorage.removeItem('account')
}

request.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    const value = token.startsWith('Bearer ') ? token : `Bearer ${token}`
    config.headers.Authorization = value
  }
  return config
})

let redirecting = false
function redirectTo(path: string) {
  if (redirecting) return
  redirecting = true
  router.push(path).finally(() => {
    setTimeout(() => { redirecting = false }, 300)
  })
}

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res && typeof res === 'object' && 'code' in res && res.code !== 0) {
      const cfg = response.config as OciRequestConfig
      if (!cfg.skipBusinessMessage) {
        message.error(res.message || '请求失败')
      }
      if (res.code === 401) {
        clearSession()
        redirectTo('/login')
      }
      if (res.code === 403 && res.message?.includes('初始化')) {
        redirectTo('/setup')
      }
      return Promise.reject(new Error(res.message))
    }
    return res
  },
  (error) => {
    if (error.config?.skipErrorMessage) {
      return Promise.reject(error)
    }
    if (error.response?.status === 503) {
      const msg = error.response?.data?.message
      if (msg) message.error(msg)
      else message.error('站点暂时不可用')
      return Promise.reject(error)
    }
    if (error.response?.status === 403) {
      const msg = error.response?.data?.message
      if (msg) message.error(msg)
      return Promise.reject(error)
    }
    if (error.response?.status === 401) {
      clearSession()
      redirectTo('/login')
    } else {
      message.error(error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default request
