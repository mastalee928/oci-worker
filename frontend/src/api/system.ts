import request, { type OciRequestConfig } from '../utils/request'

export function getGlance() {
  return request.get('/sys/glance')
}

export function sendVerifyCode(
  action: string,
  context?: { contextKey?: string; contextText?: string },
  options?: { quiet?: boolean },
) {
  return request.post('/sys/sendVerifyCode', { action, ...(context || {}) }, options?.quiet
    ? { skipBusinessMessage: true, skipErrorMessage: true } as OciRequestConfig
    : undefined)
}

export function sendSecuritySettingsVerifyCode() {
  return request.post('/sys/security/sendVerifyCode')
}

export function unlockSecuritySettings(verifyCode: string) {
  return request.post('/sys/security/unlock', { verifyCode })
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
