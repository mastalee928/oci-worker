import request from '../utils/request'

export function getCfAccountConfig() {
  return request.get('/cf/account/config')
}

export function saveCfAccountConfig(data: { accountId: string; apiToken: string }) {
  return request.post('/cf/account/config', data)
}

export function testCfAccountConfig(data: { accountId: string; apiToken: string }) {
  return request.post('/cf/account/test', data)
}

export function listCfZones(params?: { page?: number; perPage?: number }) {
  return request.post('/cf/zones/list', params || {})
}

export function listCfZonesPage(params?: { page?: number; perPage?: number }) {
  return request.post('/cf/zones/listPage', params || {})
}

export function getCfZoneDetail(data: { zoneId: string }) {
  return request.post('/cf/zones/detail', data)
}

export function createCfZone(data: { name: string }) {
  return request.post('/cf/zones/create', data)
}

export function deleteCfZone(data: { zoneId: string }) {
  return request.post('/cf/zones/delete', data)
}

export function setCfZonePaused(data: { zoneId: string; paused: boolean }) {
  return request.post('/cf/zones/paused', data)
}

export function listCfDnsRecords(data: {
  zoneId: string
  page?: number
  perPage?: number
  search?: string
  type?: string
}) {
  return request.post('/cf/dns/listPage', data)
}

export function addCfDnsRecord(data: {
  zoneId: string
  type: string
  name: string
  content: string
  proxied?: boolean
  ttl?: number
  priority?: number
  comment?: string
}) {
  return request.post('/cf/dns/add', data)
}

export function updateCfDnsRecord(data: {
  zoneId: string
  recordId: string
  type: string
  name: string
  content: string
  proxied?: boolean
  ttl?: number
  priority?: number
  comment?: string
}) {
  return request.post('/cf/dns/update', data)
}

export function deleteCfDnsRecord(data: { zoneId: string; recordId: string }) {
  return request.post('/cf/dns/delete', data)
}

export function exportCfDnsRecords(data: { zoneId: string }) {
  return request.post('/cf/dns/export', data)
}

export function importCfDnsRecords(data: { zoneId: string; bindContent: string; proxied?: boolean }) {
  return request.post('/cf/dns/import', data)
}

export function getCfDnssec(data: { zoneId: string }) {
  return request.post('/cf/dns/dnssec/get', data)
}

export function setCfDnssec(data: { zoneId: string; status: 'active' | 'disabled' }) {
  return request.post('/cf/dns/dnssec/set', data)
}

export function getCfEmailSettings(data: { zoneId: string }) {
  return request.post('/cf/email/settings', data)
}

export function enableCfEmailRouting(data: { zoneId: string }) {
  return request.post('/cf/email/enable', data)
}

export function disableCfEmailRouting(data: { zoneId: string }) {
  return request.post('/cf/email/disable', data)
}

export function getCfEmailDns(data: { zoneId: string }) {
  return request.post('/cf/email/dns/get', data)
}

export function lockCfEmailDns(data: { zoneId: string }) {
  return request.post('/cf/email/dns/lock', data)
}

export function unlockCfEmailDns(data: { zoneId: string }) {
  return request.post('/cf/email/dns/unlock', data)
}

export function listCfEmailRules(data: { zoneId: string }) {
  return request.post('/cf/email/rules/list', data)
}

export function createCfEmailRule(data: {
  zoneId: string
  name?: string
  customAddress?: string
  actionType?: 'forward' | 'drop' | 'worker'
  destination?: string
  destinations?: string[]
  workerName?: string
  enabled?: boolean
  priority?: number
}) {
  return request.post('/cf/email/rules/create', data)
}

export function deleteCfEmailRule(data: { zoneId: string; ruleId: string }) {
  return request.post('/cf/email/rules/delete', data)
}

export function updateCfEmailRule(data: {
  zoneId: string
  ruleId: string
  name?: string
  customAddress?: string
  actionType?: 'forward' | 'drop' | 'worker'
  destination?: string
  destinations?: string[]
  workerName?: string
  enabled?: boolean
  priority?: number
}) {
  return request.post('/cf/email/rules/update', data)
}

export function getCfCatchAllRule(data: { zoneId: string }) {
  return request.post('/cf/email/rules/catch-all/get', data)
}

export function updateCfCatchAllRule(data: {
  zoneId: string
  name?: string
  actionType: 'forward' | 'drop' | 'worker'
  destinations?: string[]
  workerName?: string
  enabled?: boolean
}) {
  return request.post('/cf/email/rules/catch-all/update', data)
}

export function listCfEmailDestinations() {
  return request.post('/cf/email/destinations/list', {})
}

export function createCfEmailDestination(data: { email: string }) {
  return request.post('/cf/email/destinations/create', data)
}

export function resendCfEmailDestination(data: { email: string }) {
  return request.post('/cf/email/destinations/resend', data)
}

export function deleteCfEmailDestination(data: { destinationId: string }) {
  return request.post('/cf/email/destinations/delete', data)
}

export function listCfWorkers() {
  return request.post('/cf/email/workers/list', {})
}

export function listCfTunnels() {
  return request.post('/cf/tunnel/list', {})
}

export function createCfTunnel(data: { name: string }) {
  return request.post('/cf/tunnel/create', data)
}

export function deleteCfTunnel(data: { tunnelId: string }) {
  return request.post('/cf/tunnel/delete', data)
}

export function getCfTunnelToken(data: { tunnelId: string }) {
  return request.post('/cf/tunnel/token', data)
}

export function listCfTunnelConnections(data: { tunnelId: string }) {
  return request.post('/cf/tunnel/connections', data)
}
