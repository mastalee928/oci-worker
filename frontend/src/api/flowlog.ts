import request from '../utils/request'

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
