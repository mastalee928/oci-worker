import request from '../utils/request'

export type ScheduledIpProvider = 'CF' | 'ALI' | null
export type ScheduledIpTaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'IP_FAILED' | 'DNS_FAILED' | 'AUTO_PAUSED' | 'DISABLED'

export interface ScheduledIpTask {
  id: string
  name: string
  tenantConfigId: string
  tenantName?: string
  region: string
  instanceId: string
  instanceName?: string
  shape?: string
  compartmentId?: string
  currentPublicIp?: string
  enabled: boolean
  intervalMinutes: number
  nextRunTime?: string
  lastRunTime?: string
  lastStatus?: ScheduledIpTaskStatus
  lastMessage?: string
  dnsEnabled: boolean
  dnsProvider?: ScheduledIpProvider
  fqdn?: string
  notifySuccess: boolean
  notifyIpFailure: boolean
  notifyDnsFailure: boolean
  notifyAutoPaused: boolean
  consecutiveFailures?: number
  createTime?: string
  updateTime?: string
}

export interface ScheduledIpRunLog {
  id: string
  taskId: string
  runId: string
  triggerType: string
  status: string
  oldIp?: string
  newIp?: string
  dnsStatus?: string
  message?: string
  dnsMessage?: string
  startedAt: string
  finishedAt?: string
}

export interface ScheduledIpOverview {
  tasks: ScheduledIpTask[]
  stats: {
    total: number
    enabled: number
    paused: number
    errors: number
    latestLog?: ScheduledIpRunLog
    nextTask?: ScheduledIpTask
  }
}

export interface ScheduledIpTaskPayload {
  id?: string
  tenantConfigId: string
  region: string
  instanceId: string
  instanceName: string
  shape?: string
  compartmentId?: string
  currentPublicIp?: string
  intervalMinutes: number
  firstRunNow: boolean
  dnsEnabled: boolean
  dnsProvider?: Exclude<ScheduledIpProvider, null>
  fqdn?: string
  notifySuccess: boolean
  notifyIpFailure: boolean
  notifyDnsFailure: boolean
  notifyAutoPaused: boolean
}

export function getScheduledIpOverview() {
  return request.post<ScheduledIpOverview>('/scheduled-ip/overview', {})
}

export function createScheduledIpTask(data: ScheduledIpTaskPayload) {
  return request.post<ScheduledIpTask>('/scheduled-ip/task/create', data)
}

export function updateScheduledIpTask(data: ScheduledIpTaskPayload & { id: string }) {
  return request.post<ScheduledIpTask>('/scheduled-ip/task/update', data)
}

export function copyScheduledIpTask(id: string) {
  return request.post<ScheduledIpTask>('/scheduled-ip/task/copy', { id })
}

export function setScheduledIpTaskEnabled(id: string, enabled: boolean) {
  return request.post<ScheduledIpTask>('/scheduled-ip/task/enabled', { id, enabled })
}

export function deleteScheduledIpTask(id: string) {
  return request.post('/scheduled-ip/task/delete', { id })
}

export function runScheduledIpTask(id: string) {
  return request.post('/scheduled-ip/task/run', { id })
}

export function listScheduledIpTaskLogs(id: string) {
  return request.post<ScheduledIpRunLog[]>('/scheduled-ip/task/logs', { id })
}

export function retryScheduledIpDns(id: string) {
  return request.post('/scheduled-ip/task/retry-dns', { id })
}
