import request from '../utils/request'

export function getGlance() {
  return request.get('/sys/glance')
}

export function sendVerifyCode(action: string) {
  return request.post('/sys/sendVerifyCode', { action })
}

export function getTgStatus() {
  return request.get('/sys/tgStatus')
}
