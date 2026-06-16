import request from '../utils/request'

export function listUsers(data: {
  tenantId: string
  domainId?: string
  keyword?: string
  current?: number
  size?: number
}) {
  return request.post('/oci/identity/list', data)
}

export function listIdentityDomains(data: { tenantId: string }) {
  return request.post('/oci/identity/domains', data)
}

export function listGroups(data: { tenantId: string }) {
  return request.post('/oci/identity/groups', data)
}

export function listDomainGroups(data: { tenantId: string; domainId: string }) {
  return request.post('/oci/identity/domainGroups', data)
}

export function createUser(data: any) {
  return request.post('/oci/identity/create', data)
}

export function resetPassword(data: { tenantId: string; userId: string }) {
  return request.post('/oci/identity/resetPassword', data)
}

export function clearMfa(data: { tenantId: string; userId: string; verifyCode: string }) {
  return request.post('/oci/identity/clearMfa', data)
}

export function addToAdmin(data: { tenantId: string; userId: string }) {
  return request.post('/oci/identity/addToAdmin', data)
}

export function removeFromAdmin(data: { tenantId: string; userId: string; verifyCode: string }) {
  return request.post('/oci/identity/removeFromAdmin', data)
}

export function getUserGroups(data: { tenantId: string; userId: string }) {
  return request.post('/oci/identity/userGroups', data)
}

export function updateUser(data: {
  tenantId: string
  userId: string
  userName?: string
  email?: string
  verifyCode: string
  groupIds?: string[]
}) {
  return request.post('/oci/identity/updateUser', data)
}

export function updateUserState(data: { tenantId: string; userId: string; blocked: boolean; verifyCode?: string }) {
  return request.post('/oci/identity/updateUserState', data)
}

export function listMfaDevices(data: { tenantId: string; userId: string }) {
  return request.post('/oci/identity/listMfaDevices', data)
}

export function getUserCapabilities(data: { tenantId: string; userId: string }) {
  return request.post('/oci/identity/userCapabilities', data)
}

export function updateUserCapabilities(data: {
  tenantId: string
  userId: string
  verifyCode: string
  capabilities: Record<string, boolean>
}) {
  return request.post('/oci/identity/updateUserCapabilities', data)
}
