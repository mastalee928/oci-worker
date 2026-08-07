import request from '../utils/request'

export interface NlbContext {
  id: string
  region?: string
  compartmentId?: string
  vcnId: string
  force?: boolean
}

export interface NlbWorkRequestContext {
  id: string
  region?: string
  compartmentId: string
  workRequestId: string
}

export function listNetworkLoadBalancers(data: NlbContext) {
  return request.post('/oci/nlb/list', data)
}

export function getNetworkLoadBalancer(data: NlbContext & { networkLoadBalancerId: string }) {
  return request.post('/oci/nlb/detail', data)
}

export function getNetworkLoadBalancerOptions(data: NlbContext) {
  return request.post('/oci/nlb/options', data)
}

export function listNlbListeners(data: NlbContext & { networkLoadBalancerId: string }) {
  return request.post('/oci/nlb/listeners', data)
}

export function getNlbListener(data: NlbContext & { networkLoadBalancerId: string; listenerName: string }) {
  return request.post('/oci/nlb/listener/detail', data)
}

export function listNlbBackendSets(data: NlbContext & { networkLoadBalancerId: string }) {
  return request.post('/oci/nlb/backend-sets', data)
}

export function getNlbBackendSet(data: NlbContext & { networkLoadBalancerId: string; backendSetName: string }) {
  return request.post('/oci/nlb/backend-set/detail', data)
}

export function getNlbHealthChecker(data: NlbContext & { networkLoadBalancerId: string; backendSetName: string }) {
  return request.post('/oci/nlb/health-checker/detail', data)
}

export function listNlbBackends(data: NlbContext & { networkLoadBalancerId: string; backendSetName: string }) {
  return request.post('/oci/nlb/backends', data)
}

export function getNlbBackend(data: NlbContext & { networkLoadBalancerId: string; backendSetName: string; backendName: string }) {
  return request.post('/oci/nlb/backend/detail', data)
}

export function getNlbHealth(data: NlbContext & { networkLoadBalancerId: string }) {
  return request.post('/oci/nlb/health', data)
}

export function getNlbBackendSetHealth(data: NlbContext & { networkLoadBalancerId: string; backendSetName: string }) {
  return request.post('/oci/nlb/backend-set/health', data)
}

export function getNlbBackendHealth(data: NlbContext & { networkLoadBalancerId: string; backendSetName: string; backendName: string }) {
  return request.post('/oci/nlb/backend/health', data)
}

export function createNetworkLoadBalancer(data: NlbContext & Record<string, any>) {
  return request.post('/oci/nlb/create', data)
}

export function updateNetworkLoadBalancer(data: NlbContext & { networkLoadBalancerId: string } & Record<string, any>) {
  return request.post('/oci/nlb/update', data)
}

export function deleteNetworkLoadBalancer(data: NlbContext & { networkLoadBalancerId: string; ifMatch?: string; verifyCode: string }) {
  return request.post('/oci/nlb/delete', data)
}

export function updateNlbNetworkSecurityGroups(data: NlbContext & { networkLoadBalancerId: string; ifMatch?: string; networkSecurityGroupIds: string[] }) {
  return request.post('/oci/nlb/update-nsgs', data)
}

export function changeNlbCompartment(data: NlbContext & { networkLoadBalancerId: string; targetCompartmentId: string; ifMatch?: string; verifyCode: string }) {
  return request.post('/oci/nlb/change-compartment', data)
}

export function createNlbListener(data: NlbContext & Record<string, any>) {
  return request.post('/oci/nlb/listener/create', data)
}

export function updateNlbListener(data: NlbContext & Record<string, any>) {
  return request.post('/oci/nlb/listener/update', data)
}

export function deleteNlbListener(data: NlbContext & { networkLoadBalancerId: string; listenerName: string; ifMatch?: string; verifyCode: string }) {
  return request.post('/oci/nlb/listener/delete', data)
}

export function createNlbBackendSet(data: NlbContext & Record<string, any>) {
  return request.post('/oci/nlb/backend-set/create', data)
}

export function updateNlbBackendSet(data: NlbContext & Record<string, any>) {
  return request.post('/oci/nlb/backend-set/update', data)
}

export function deleteNlbBackendSet(data: NlbContext & { networkLoadBalancerId: string; backendSetName: string; ifMatch?: string; verifyCode: string }) {
  return request.post('/oci/nlb/backend-set/delete', data)
}

export function updateNlbHealthChecker(data: NlbContext & Record<string, any>) {
  return request.post('/oci/nlb/health-checker/update', data)
}

export function createNlbBackend(data: NlbContext & Record<string, any>) {
  return request.post('/oci/nlb/backend/create', data)
}

export function updateNlbBackend(data: NlbContext & Record<string, any>) {
  return request.post('/oci/nlb/backend/update', data)
}

export function deleteNlbBackend(data: NlbContext & { networkLoadBalancerId: string; backendSetName: string; backendName: string; ifMatch?: string; verifyCode: string }) {
  return request.post('/oci/nlb/backend/delete', data)
}

export function getNlbWorkRequest(data: NlbWorkRequestContext) {
  return request.post('/oci/nlb/work-request', data)
}

export function listNlbWorkRequestErrors(data: NlbWorkRequestContext) {
  return request.post('/oci/nlb/work-request/errors', data)
}

export function listNlbWorkRequestLogs(data: NlbWorkRequestContext) {
  return request.post('/oci/nlb/work-request/logs', data)
}

export function waitNlbWorkRequest(data: NlbWorkRequestContext & { timeoutSeconds?: number; pollIntervalMillis?: number }) {
  return request.post('/oci/nlb/work-request/wait', data)
}
