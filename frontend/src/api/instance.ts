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
  return request.post('/oci/instance/changeIp', data)
}

export function stopChangeIp(data: { taskId: string }) {
  return request.post('/oci/instance/stopChangeIp', data)
}

export function getInstanceCfg(data: { id: string; instanceId: string }) {
  return request.post('/oci/instance/getCfg', data)
}

export function updateInstanceCfg(data: any) {
  return request.post('/oci/instance/updateCfg', data)
}

export function createIpv6(data: { id: string; instanceId: string }) {
  return request.post('/oci/instance/createIpv6', data)
}
