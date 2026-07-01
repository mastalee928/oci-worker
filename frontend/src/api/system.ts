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

export function listOciRegionOptions(userId?: string) {
  return request.get('/sys/ociRegionOptions', { params: userId ? { userId } : {} })
}

export function getTaskCredential() {
  return request.get('/sys/taskCredential')
}

export function saveTaskCredential(data: { rootPassword?: string; sshPublicKey?: string }) {
  return request.post('/sys/taskCredential', data)
}
