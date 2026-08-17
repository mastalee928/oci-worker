import request from '../utils/request'
import type { OciRequestConfig } from '../utils/request'

export interface FlowLogRecord {
  time?: string
  direction?: string
  sourceAddress?: string
  sourcePort?: number
  destinationAddress?: string
  destinationPort?: number
  protocol?: string
  action?: string
  bytes?: number
  packets?: number
}

export function getFlowLogStatus(data: { id: string; region?: string; subnetIds: string[] }) {
  return request.post<{ subnets: Record<string, { enabled: boolean }> }>('/oci/flowlog/status', data, {
    timeout: 60_000,
  })
}

export function toggleFlowLog(data: {
  id: string
  region?: string
  subnetId: string
  subnetName?: string
  enabled: boolean
}) {
  return request.post('/oci/flowlog/toggle', data, { timeout: 120_000 })
}

export interface FlowLogInstanceStatus {
  subnetId: string
  subnetName?: string | null
  enabled: boolean
  privateIp?: string | null
}

export function getFlowLogInstanceStatus(data: {
  id: string
  region?: string
  instanceId: string
}) {
  // 辅助性状态查询：失败静默（开关显示未开启即可），不弹全局错误提示。
  return request.post<FlowLogInstanceStatus>('/oci/flowlog/instanceStatus', data, {
    timeout: 60_000,
    skipBusinessMessage: true,
    skipErrorMessage: true,
  } as OciRequestConfig)
}

export function searchFlowLog(data: {
  id: string
  region?: string
  privateIp?: string
  instanceId?: string
  minutes: number
  rejectOnly: boolean
}) {
  return request.post<{ records: FlowLogRecord[]; flowLogConfigured: boolean; privateIp?: string }>(
    '/oci/flowlog/search',
    data,
    { timeout: 90_000 },
  )
}
