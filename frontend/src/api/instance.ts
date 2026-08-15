import request from '../utils/request'
import type { OciRequestConfig } from '../utils/request'

type R = { region?: string; compartmentId?: string; force?: boolean }

export type ShapeEditTaskState = 'PENDING' | 'RUNNING' | 'PAUSED' | 'SUCCESS' | 'FAILED' | 'STOPPED'
export type BlockVolumeAttachmentType = 'paravirtualized' | 'iscsi'

export interface ShapeEditTaskStatus {
  taskId: string
  instanceId: string
  tenantId: string
  region?: string
  status: ShapeEditTaskState
  message?: string
  retryCount: number
  maxRetries: number
  pending: boolean
  paused: boolean
  stopped: boolean
  terminal: boolean
  result?: Record<string, any>
}

export interface InstancePublicIpTarget {
  instanceId: string
  compartmentId?: string
}

export interface InstancePublicIpResponse {
  publicIps: Record<string, string | null>
  complete: boolean
  requested: number
  resolved: number
}

export function getInstanceList(data: { id: string } & R, config?: OciRequestConfig) {
  return request.post('/oci/instance/list', data, { timeout: 40_000, ...config })
}

export function getInstancePublicIps(
  data: { id: string; instances: InstancePublicIpTarget[] } & R,
  config?: OciRequestConfig,
) {
  return request.post<InstancePublicIpResponse>('/oci/instance/publicIps', data, { timeout: 35_000, ...config })
}

export interface InstanceBootVolumeSummary {
  sizeGB?: number | null
  vpusPerGB?: number | null
}

export function getInstanceBootVolumeSummaries(
  data: {
    id: string
    instances: Array<{ instanceId: string; availabilityDomain?: string; compartmentId?: string }>
  } & R,
  config?: OciRequestConfig,
) {
  return request.post<{ volumes: Record<string, InstanceBootVolumeSummary | null> }>(
    '/oci/instance/bootVolumeSummaries',
    data,
    { timeout: 60_000, ...config },
  )
}

export function updateInstanceState(data: { id: string; instanceId: string; action: string } & R) {
  return request.post('/oci/instance/updateState', data)
}

export interface InstanceGuardStatus {
  enabled: boolean
  intervalMinutes: number
  lastState?: string | null
  lastCheckTime?: string | null
  lastStartTime?: string | null
  startCount: number
  lastMessage?: string | null
}

export interface InstanceGuardRecord {
  id: string
  tenantConfigId: string
  tenantName?: string | null
  region: string
  instanceId: string
  instanceName?: string | null
  enabled: boolean
  intervalMinutes?: number | null
  nextCheckTime?: string | null
  notifyMuted?: boolean | null
  lastState?: string | null
  lastCheckTime?: string | null
  lastStartTime?: string | null
  startCount?: number | null
  consecutiveFailures?: number | null
  lastMessage?: string | null
  createTime?: string | null
  updateTime?: string | null
}

export function getInstanceGuardStatus(data: { id: string; instanceId: string } & R) {
  return request.post<InstanceGuardStatus>('/oci/instanceGuard/status', data)
}

export function saveInstanceGuard(
  data: {
    id: string
    instanceId: string
    instanceName?: string
    enabled: boolean
    intervalMinutes?: number
  } & R,
) {
  return request.post<InstanceGuardStatus>('/oci/instanceGuard/save', data)
}

export function listInstanceGuards() {
  return request.post<InstanceGuardRecord[]>('/oci/instanceGuard/list', {})
}

export function toggleInstanceGuard(data: { guardId: string; enabled: boolean }) {
  return request.post<InstanceGuardStatus>('/oci/instanceGuard/toggle', data)
}

export function setInstanceGuardInterval(data: { guardId: string; intervalMinutes: number }) {
  return request.post<InstanceGuardStatus>('/oci/instanceGuard/interval', data)
}

export function setInstanceGuardNotify(data: { guardId: string; muted: boolean }) {
  return request.post<InstanceGuardStatus>('/oci/instanceGuard/notify', data)
}

export interface InstanceStopCause {
  instanceId: string
  state: string
  found: boolean
  cause: string
}

export function getInstanceStopCause(data: { id: string; instanceId: string } & R) {
  return request.post<InstanceStopCause>('/oci/instanceGuard/stopCause', data, { timeout: 60_000 })
}

export function deleteInstanceGuard(data: { guardId: string }) {
  return request.post('/oci/instanceGuard/delete', data)
}

export function terminateInstance(data: { id: string; instanceId: string; verifyCode: string; preserveBootVolume?: boolean } & R) {
  return request.post('/oci/instance/terminate', data)
}

export function changeIp(data: Record<string, unknown> & R) {
  return request.post('/oci/network/changeIp', data)
}

export function updateInstance(data: {
  id: string
  instanceId: string
  displayName?: string
  shape?: string
  ocpus?: number
  memoryInGBs?: number
} & R) {
  return request.post('/oci/instance/updateInstance', data)
}

export function unlockFaultDomainUpdate(data: {
  id: string
  instanceId: string
  verifyCode: string
}) {
  return request.post('/oci/instance/faultDomain/unlock', data)
}

export function updateFaultDomain(data: {
  id: string
  instanceId: string
  faultDomain: string
  accessToken: string
} & R) {
  return request.post('/oci/instance/faultDomain/update', data)
}

export function revokeFaultDomainUpdate(data: {
  id: string
  instanceId: string
  accessToken: string
}) {
  return request.post('/oci/instance/faultDomain/revoke', data)
}

export function getShapeEditTaskStatus(taskId: string) {
  return request.get<ShapeEditTaskStatus>(`/oci/instance/shapeEditTask/${taskId}`)
}

export function pauseShapeEditTask(taskId: string) {
  return request.post<ShapeEditTaskStatus>(`/oci/instance/shapeEditTask/${taskId}/pause`)
}

export function resumeShapeEditTask(taskId: string) {
  return request.post<ShapeEditTaskStatus>(`/oci/instance/shapeEditTask/${taskId}/resume`)
}

export function stopShapeEditTask(taskId: string) {
  return request.post<ShapeEditTaskStatus>(`/oci/instance/shapeEditTask/${taskId}/stop`)
}

export function getAvailableShapes(data: { id: string } & R) {
  return request.post('/oci/instance/shapes', data)
}

export function getShapesForInstance(data: { id: string; instanceId: string } & R) {
  return request.post('/oci/instance/shapesForInstance', data)
}

export function forceA2ToA1(data: { id: string; instanceId: string } & R) {
  return request.post('/oci/instance/forceA2ToA1', data)
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

export function getBlockVolumes(data: { id: string; instanceId: string } & R) {
  return request.post('/oci/instance/blockVolumes', data)
}

export function getExternalBootVolumes(data: { id: string; instanceId: string } & R) {
  return request.post('/oci/instance/externalBootVolumes', data)
}

export function getUnattachedBlockVolumes(data: { id: string; instanceId: string } & R) {
  return request.post('/oci/instance/unattachedBlockVolumes', data)
}

export function createBlockVolumeAndAttach(data: {
  id: string
  instanceId: string
  displayName?: string
  sizeInGBs: number
  vpusPerGB?: number
  device?: string
  attachmentType?: BlockVolumeAttachmentType
} & R) {
  return request.post('/oci/instance/createBlockVolumeAndAttach', data)
}

export function attachBlockVolume(data: {
  id: string
  instanceId: string
  volumeId: string
  device?: string
  attachmentType?: BlockVolumeAttachmentType
} & R) {
  return request.post('/oci/instance/attachBlockVolume', data)
}

export function attachExternalBootVolume(data: {
  id: string
  instanceId: string
  bootVolumeId: string
  attachmentType?: BlockVolumeAttachmentType
} & R) {
  return request.post('/oci/instance/attachExternalBootVolume', data)
}

export function detachBlockVolume(data: { id: string; volumeAttachmentId: string } & R) {
  return request.post('/oci/instance/detachBlockVolume', data)
}

export function detachExternalBootVolume(data: { id: string; instanceId: string; bootVolumeAttachmentId: string; verifyCode: string } & R) {
  return request.post('/oci/instance/detachExternalBootVolume', data)
}

export function updateBlockVolume(data: Record<string, unknown> & R) {
  return request.post('/oci/instance/updateBlockVolume', data)
}

export function getVcns(data: { id: string } & R) {
  return request.post('/oci/network/vcns', data)
}

export function getTrafficData(data: { id: string; instanceId: string; minutes?: number; startTime?: string; endTime?: string } & R) {
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

export { listByoipRanges, listPublicIpPools, createByoipReservedIp } from './byoip'

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

export interface LocalConsoleConnection {
  connectionId: string
  serialCommand?: string
  vncCommand?: string
  state?: string
  privateKey?: string
  keyFileName?: string
}

export function createLocalConsoleConnection(
  data: { id: string; instanceId: string; publicKey?: string; generateKey?: boolean } & R,
) {
  return request.post<LocalConsoleConnection>('/oci/instance/createLocalConsole', data, {
    timeout: 150_000,
  })
}

export function deleteConsoleConnection(data: { id: string; connectionId: string } & R) {
  return request.post('/oci/instance/deleteConsole', data)
}
