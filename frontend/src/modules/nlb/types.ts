export interface NlbContext {
  id: string
  region?: string
  compartmentId?: string
  vcnId: string
}

export interface NlbOptions {
  subnets?: any[]
  networkSecurityGroups?: any[]
  policies?: string[]
  protocols?: string[]
  nlbIpVersions?: string[]
  ipVersions?: string[]
  healthCheckProtocols?: string[]
  dnsTransportProtocols?: string[]
  dnsQueryClasses?: string[]
  dnsQueryTypes?: string[]
  dnsRcodes?: string[]
}

export interface WorkRequestState {
  id: string
  compartmentId?: string
  status?: string
  percentComplete?: number
  terminal?: boolean
  successful?: boolean
  timedOut?: boolean
  pollError?: string
  diagnosticsError?: string
  errors?: any[]
  logs?: any[]
  operation?: string
  resourceId?: string
}

export const terminalWorkRequestStatuses = new Set(['SUCCEEDED', 'FAILED', 'CANCELED'])

export function workRequestStatusText(status?: string) {
  const value = String(status || '').toUpperCase()
  return ({
    ACCEPTED: '已接受',
    IN_PROGRESS: '处理中',
    SUCCEEDED: '已完成',
    FAILED: '失败',
    CANCELING: '取消中',
    CANCELED: '已取消',
  } as Record<string, string>)[value] || value || '未知'
}

export function healthStatusText(status?: string) {
  const value = String(status || '').toUpperCase()
  return ({
    OK: '健康',
    WARNING: '警告',
    CRITICAL: '严重',
    UNKNOWN: '未知',
  } as Record<string, string>)[value] || value || '未知'
}

export function healthTagStatus(status?: string): 'success' | 'warning' | 'error' | 'default' {
  const value = String(status || '').toUpperCase()
  if (value === 'OK') return 'success'
  if (value === 'WARNING') return 'warning'
  if (value === 'CRITICAL') return 'error'
  return 'default'
}
