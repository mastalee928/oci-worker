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

export function uploadKey(formData: FormData) {
  return request.post('/oci/user/uploadKey', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}
