import request from '../utils/request'
import type { OciRequestConfig } from '../utils/request'

export function login(data: { account: string; password: string; mfaCode?: string }) {
  return request.post('/auth/login', data)
}

export function getCurrentAccount() {
  return request.get<{ account: string }>('/auth/account')
}

export function needSetup() {
  // 后端启动初期可能返回 503（安全配置加载中），由登录页自行重试，不弹全局错误。
  return request.get('/auth/needSetup', {
    skipBusinessMessage: true,
    skipErrorMessage: true,
  } as OciRequestConfig)
}

export function setupAccount(data: { account: string; password: string }) {
  return request.post('/auth/setup', data)
}

export function tgLoginAvailable() {
  return request.get('/auth/tgLoginAvailable')
}

export function tgLoginSendCode() {
  return request.post('/auth/tgLoginSendCode')
}

export function tgLogin(data: { code: string }) {
  return request.post('/auth/tgLogin', data)
}
