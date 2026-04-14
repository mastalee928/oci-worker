import request from '../utils/request'

export function getInstanceList(data: { id: string }) {
  return request.post('/oci/instance/list', data)
}

export function updateInstanceState(data: { id: string; instanceId: string; action: string }) {
  return request.post('/oci/instance/updateState', data)
}

export function terminateInstance(data: { id: string; instanceId: string }) {
  return request.post('/oci/instance/terminate', data)
}

export function changeIp(data: any) {
  return request.post('/oci/network/changeIp', data)
}

export function getAvailableShapes(data: { id: string }) {
  return request.post('/oci/instance/shapes', data)
}

export function getSecurityRules(data: { id: string; instanceId: string }) {
  return request.post('/oci/network/securityRules', data)
}

export function releaseAllPorts(data: { id: string; instanceId: string }) {
  return request.post('/oci/network/releaseAllPorts', data)
}

export function addSecurityRule(data: any) {
  return request.post('/oci/network/addSecurityRule', data)
}

export function getBootVolumes(data: { id: string; instanceId: string }) {
  return request.post('/oci/instance/bootVolumes', data)
}

export function updateBootVolume(data: any) {
  return request.post('/oci/instance/updateBootVolume', data)
}

export function getVcns(data: { id: string }) {
  return request.post('/oci/network/vcns', data)
}

export function getTrafficData(data: { id: string; instanceId: string; minutes?: number }) {
  return request.post('/oci/traffic/data', data)
}

export function getInstanceNetworkDetail(data: { id: string; instanceId: string }) {
  return request.post('/oci/instance/instanceDetail', data)
}

export function addIpv6(data: { id: string; instanceId: string }) {
  return request.post('/oci/instance/addIpv6', data)
}

export function createReservedIp(data: { id: string; displayName?: string }) {
  return request.post('/oci/instance/createReservedIp', data)
}

export function listReservedIps(data: { id: string }) {
  return request.post('/oci/instance/listReservedIps', data)
}

export function deleteReservedIp(data: { id: string; publicIpId: string }) {
  return request.post('/oci/instance/deleteReservedIp', data)
}

export function assignReservedIp(data: { id: string; publicIpId: string; instanceId: string }) {
  return request.post('/oci/instance/assignReservedIp', data)
}

export function unassignReservedIp(data: { id: string; publicIpId: string }) {
  return request.post('/oci/instance/unassignReservedIp', data)
}
