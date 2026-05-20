import request from '../utils/request'

export function listCompartments(data: { id: string; parentId?: string; keyword?: string }) {
  return request.post('/oci/user/compartments', data)
}

export function getCompartmentDetail(data: { id: string; compartmentId: string }) {
  return request.post('/oci/user/compartmentDetail', data)
}

export function createCompartment(data: {
  id: string
  parentId: string
  name: string
  description?: string
}) {
  return request.post('/oci/user/compartmentCreate', data)
}

export function updateCompartment(data: {
  id: string
  compartmentId: string
  name?: string
  description?: string
}) {
  return request.post('/oci/user/compartmentUpdate', data)
}

export function deleteCompartment(data: { id: string; compartmentId: string; verifyCode: string }) {
  return request.post('/oci/user/compartmentDelete', data)
}

export function moveCompartment(data: { id: string; compartmentId: string; newParentId: string }) {
  return request.post('/oci/user/compartmentMove', data)
}

export function listCompartmentResources(data: {
  id: string
  compartmentId: string
  pageToken?: string
  limit?: number
}) {
  return request.post('/oci/user/compartmentResources', data)
}

export function moveCompartmentResource(data: {
  id: string
  resourceId: string
  resourceType: string
  targetCompartmentId: string
}) {
  return request.post('/oci/user/compartmentMoveResource', data)
}
