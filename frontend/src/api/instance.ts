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

export function getSecurityRules(data: { id: string; instanceId: string }) {
  return request.post('/oci/network/securityRules', data)
}

export function releaseAllPorts(data: { id: string; instanceId: string }) {
  return request.post('/oci/network/releaseAllPorts', data)
}

export function getBootVolumes(data: { id: string }) {
  return request.post('/oci/instance/bootVolumes', data)
}

export function getVcns(data: { id: string }) {
  return request.post('/oci/network/vcns', data)
}

export function getTrafficData(data: { id: string; instanceId: string; minutes?: number }) {
  return request.post('/oci/traffic/data', data)
}
