import request from '../utils/request'

export function getTenantList(params: any) {
  return request.post('/oci/user/list', params)
}

export function addTenant(data: any) {
  return request.post('/oci/user/add', data)
}

export function updateTenant(data: any) {
  return request.post('/oci/user/update', data)
}

export function removeTenant(data: { idList: string[] }) {
  return request.post('/oci/user/remove', data)
}

export function getTenantDetails(data: { id: string }) {
  return request.post('/oci/user/details', data)
}

export function refreshPlanType(data: { id: string }) {
  return request.post('/oci/user/refreshPlanType', data)
}

export function getTenantFullInfo(data: { id: string }) {
  return request.post('/oci/user/fullInfo', data)
}

export function uploadKey(formData: FormData) {
  return request.post('/oci/user/uploadKey', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function getDomainSettings(data: { id: string }) {
  return request.post('/oci/user/domainSettings', data)
}

export function updateMfa(data: { id: string; enabled: boolean }) {
  return request.post('/oci/user/updateMfa', data)
}

export function updatePasswordExpiry(data: { id: string; days: number }) {
  return request.post('/oci/user/updatePasswordExpiry', data)
}

export function getAuditLogs(data: { id: string }) {
  return request.post('/oci/user/auditLogs', data)
}

export function getServiceQuotas(data: { id: string }) {
  return request.post('/oci/user/quotas', data)
}

export function getTenantGroups() {
  return request.get('/oci/user/groups')
}

export function createGroup(data: { name: string; level: string; parent?: string }) {
  return request.post('/oci/user/createGroup', data)
}

export function saveGroupOrder(data: { order: string[] }) {
  return request.post('/oci/user/saveGroupOrder', data)
}

export function renameGroup(data: { oldName: string; newName: string; level: string }) {
  return request.post('/oci/user/renameGroup', data)
}

export function deleteGroup(data: { name: string; level: string }) {
  return request.post('/oci/user/deleteGroup', data)
}
