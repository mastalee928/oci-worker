import request from '../utils/request'

export function login(data: { account: string; password: string; mfaCode?: string }) {
  return request.post('/auth/login', data)
}
