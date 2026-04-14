import request from '../utils/request'

export function login(data: { account: string; password: string; mfaCode?: string }) {
  return request.post('/auth/login', data)
}

export function needSetup() {
  return request.get('/auth/needSetup')
}

export function setupAccount(data: { account: string; password: string }) {
  return request.post('/auth/setup', data)
}
