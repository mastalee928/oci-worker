import request from '../utils/request'

export function listUsers(data: { tenantId: string }) {
  return request.post('/oci/user/list', data)
}

export function listGroups(data: { tenantId: string }) {
  return request.post('/oci/user/groups', data)
}

export function createUser(data: any) {
  return request.post('/oci/user/create', data)
}

export function resetPassword(data: { tenantId: string; userId: string }) {
  return request.post('/oci/user/resetPassword', data)
}

export function clearMfa(data: { tenantId: string; userId: string }) {
  return request.post('/oci/user/clearMfa', data)
}

export function addToAdmin(data: { tenantId: string; userId: string }) {
  return request.post('/oci/user/addToAdmin', data)
}

export function removeFromAdmin(data: { tenantId: string; userId: string }) {
  return request.post('/oci/user/removeFromAdmin', data)
}

export function getUserGroups(data: { tenantId: string; userId: string }) {
  return request.post('/oci/user/userGroups', data)
}
