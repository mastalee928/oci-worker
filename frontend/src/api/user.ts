import request from '../utils/request'

export function listUsers(data: { tenantId: string }) {
  return request.post('/oci/identity/list', data)
}

export function listGroups(data: { tenantId: string }) {
  return request.post('/oci/identity/groups', data)
}

export function createUser(data: any) {
  return request.post('/oci/identity/create', data)
}

export function resetPassword(data: { tenantId: string; userId: string }) {
  return request.post('/oci/identity/resetPassword', data)
}

export function clearMfa(data: { tenantId: string; userId: string }) {
  return request.post('/oci/identity/clearMfa', data)
}

export function addToAdmin(data: { tenantId: string; userId: string }) {
  return request.post('/oci/identity/addToAdmin', data)
}

export function removeFromAdmin(data: { tenantId: string; userId: string }) {
  return request.post('/oci/identity/removeFromAdmin', data)
}

export function getUserGroups(data: { tenantId: string; userId: string }) {
  return request.post('/oci/identity/userGroups', data)
}
