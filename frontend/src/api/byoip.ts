import request from '../utils/request'

type R = { region?: string }

export function getByoipHelp() {
  return request.post('/oci/byoip/help', {})
}

export function listByoipRanges(data: { id: string } & R) {
  return request.post('/oci/byoip/listRanges', data)
}

export function getByoipRange(data: { id: string; byoipRangeId: string } & R) {
  return request.post('/oci/byoip/getRange', data)
}

export function createByoipRange(data: {
  id: string
  displayName?: string
  cidrBlock?: string
  ipv6CidrBlock?: string
} & R) {
  return request.post('/oci/byoip/createRange', data)
}

export function updateByoipRange(data: { id: string; byoipRangeId: string; displayName?: string } & R) {
  return request.post('/oci/byoip/updateRange', data)
}

export function deleteByoipRange(data: { id: string; byoipRangeId: string } & R) {
  return request.post('/oci/byoip/deleteRange', data)
}

export function validateByoipRange(data: { id: string; byoipRangeId: string } & R) {
  return request.post('/oci/byoip/validateRange', data)
}

export function advertiseByoipRange(data: { id: string; byoipRangeId: string } & R) {
  return request.post('/oci/byoip/advertiseRange', data)
}

export function withdrawByoipRange(data: { id: string; byoipRangeId: string } & R) {
  return request.post('/oci/byoip/withdrawRange', data)
}

export function changeByoipRangeCompartment(data: {
  id: string
  byoipRangeId: string
  compartmentId: string
} & R) {
  return request.post('/oci/byoip/changeRangeCompartment', data)
}

export function listByoipAllocatedRanges(data: { id: string; byoipRangeId: string } & R) {
  return request.post('/oci/byoip/listAllocatedRanges', data)
}

export function listPublicIpPools(data: { id: string; byoipRangeId?: string } & R) {
  return request.post('/oci/byoip/listPools', data)
}

export function createPublicIpPool(data: { id: string; displayName?: string } & R) {
  return request.post('/oci/byoip/createPool', data)
}

export function updatePublicIpPool(data: { id: string; publicIpPoolId: string; displayName?: string } & R) {
  return request.post('/oci/byoip/updatePool', data)
}

export function deletePublicIpPool(data: { id: string; publicIpPoolId: string } & R) {
  return request.post('/oci/byoip/deletePool', data)
}

export function addPublicIpPoolCapacity(data: {
  id: string
  publicIpPoolId: string
  byoipRangeId: string
  cidrBlock: string
} & R) {
  return request.post('/oci/byoip/addPoolCapacity', data)
}

export function removePublicIpPoolCapacity(data: {
  id: string
  publicIpPoolId: string
  cidrBlock: string
} & R) {
  return request.post('/oci/byoip/removePoolCapacity', data)
}

export function createByoipPublicIp(data: {
  id: string
  publicIpPoolId: string
  displayName?: string
} & R) {
  return request.post('/oci/byoip/createPublicIp', data)
}

export function listByoipPublicIps(data: { id: string } & R) {
  return request.post('/oci/byoip/listPublicIps', data)
}

export function assignByoipv6ToVcn(data: {
  id: string
  vcnId: string
  byoipRangeId: string
  ipv6CidrBlock: string
} & R) {
  return request.post('/oci/byoip/assignIpv6ToVcn', data)
}

/** @deprecated 使用 createByoipPublicIp */
export function createByoipReservedIp(data: {
  id: string
  publicIpPoolId: string
  displayName?: string
} & R) {
  return createByoipPublicIp(data)
}
