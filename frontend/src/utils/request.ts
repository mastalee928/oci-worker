import axios from 'axios'
import { message } from 'ant-design-vue'
import router from '../router'

const request = axios.create({
  baseURL: '/api',
  timeout: 60000,
  withCredentials: true,
})

function getToken() {
  const t = localStorage.getItem('token')
  return t ? t.trim() : ''
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
      message.error(res.message || '请求失败')
      if (res.code === 401) {
        localStorage.removeItem('token')
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
    if (error.response?.status === 503) {
      const msg = error.response?.data?.message
      if (msg) message.error(msg)
      else message.error('站点暂时不可用')
      return Promise.reject(error)
    }
    if (error.response?.status === 403) {
      const msg = error.response?.data?.message
      if (msg && !error.config?.skipErrorMessage) message.error(msg)
      return Promise.reject(error)
    }
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      redirectTo('/login')
    } else {
      // 如一键更新时轮询健康接口，服务重启窗口会出现 502，不应打全局红字
      if (error.config?.skipErrorMessage) {
        return Promise.reject(error)
      }
      message.error(error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default request
