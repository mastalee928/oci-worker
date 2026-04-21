import request from '../utils/request'

type R = { region?: string }

export function getInstanceList(data: { id: string } & R) {
  return request.post('/oci/instance/list', data)
}

export function updateInstanceState(data: { id: string; instanceId: string; action: string } & R) {
  return request.post('/oci/instance/updateState', data)
}

export function terminateInstance(data: { id: string; instanceId: string; verifyCode: string; preserveBootVolume?: boolean } & R) {
  return request.post('/oci/instance/terminate', data)
}

export function changeIp(data: Record<string, unknown> & R) {
  return request.post('/oci/network/changeIp', data)
}

export function updateInstance(data: { id: string; instanceId: string; displayName?: string; ocpus?: number; memoryInGBs?: number } & R) {
  return request.post('/oci/instance/updateInstance', data)
}

export function getAvailableShapes(data: { id: string } & R) {
  return request.post('/oci/instance/shapes', data)
}

export function getSecurityRules(data: { id: string; instanceId: string } & R) {
  return request.post('/oci/network/securityRules', data)
}

export function releaseAllPorts(data: { id: string; instanceId: string } & R) {
  return request.post('/oci/network/releaseAllPorts', data)
}

export function releaseOciPreset(data: { id: string; instanceId: string } & R) {
  return request.post('/oci/network/releaseOciPreset', data)
}

export function addSecurityRule(data: Record<string, unknown> & R) {
  return request.post('/oci/network/addSecurityRule', data)
}

export function deleteSecurityRule(data: { id: string; instanceId: string; direction: string; ruleIndex: number } & R) {
  return request.post('/oci/network/deleteSecurityRule', data)
}

export function getBootVolumes(data: { id: string; instanceId: string } & R) {
  return request.post('/oci/instance/bootVolumes', data)
}

export function updateBootVolume(data: Record<string, unknown> & R) {
  return request.post('/oci/instance/updateBootVolume', data)
}

export function getVcns(data: { id: string } & R) {
  return request.post('/oci/network/vcns', data)
}

export function getTrafficData(data: { id: string; instanceId: string; minutes?: number } & R) {
  return request.post('/oci/traffic/data', data)
}

export function getInstanceNetworkDetail(data: { id: string; instanceId: string } & R) {
  return request.post('/oci/instance/instanceDetail', data)
}

export function addIpv6(data: { id: string; instanceId: string; vnicId?: string } & R) {
  return request.post('/oci/instance/addIpv6', data)
}

export function removeIpv6(data: { id: string; ipv6Id: string } & R) {
  return request.post('/oci/instance/removeIpv6', data)
}

export function createReservedIp(data: { id: string; displayName?: string } & R) {
  return request.post('/oci/instance/createReservedIp', data)
}

export function listReservedIps(data: { id: string } & R) {
  return request.post('/oci/instance/listReservedIps', data)
}

export function deleteReservedIp(data: { id: string; publicIpId: string } & R) {
  return request.post('/oci/instance/deleteReservedIp', data)
}

export function assignReservedIp(data: { id: string; publicIpId: string; instanceId: string } & R) {
  return request.post('/oci/instance/assignReservedIp', data)
}

export function unassignReservedIp(data: { id: string; publicIpId: string } & R) {
  return request.post('/oci/instance/unassignReservedIp', data)
}

export function assignEphemeralIp(data: { id: string; instanceId: string; privateIpId: string } & R) {
  return request.post('/oci/network/assignEphemeralIp', data)
}

export function deletePublicIp(data: { id: string; privateIpId: string } & R) {
  return request.post('/oci/network/deletePublicIp', data)
}

export function deleteSecondaryIp(data: { id: string; privateIpId: string } & R) {
  return request.post('/oci/network/deleteSecondaryIp', data)
}

export function createConsoleConnection(data: { id: string; instanceId: string } & R) {
  return request.post('/oci/instance/createConsole', data)
}

export function deleteConsoleConnection(data: { id: string; connectionId: string } & R) {
  return request.post('/oci/instance/deleteConsole', data)
}
