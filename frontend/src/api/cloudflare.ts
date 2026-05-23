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

export function listCfDnsRecords(data: { zoneId: string; page?: number; perPage?: number }) {
  return request.post('/cf/dns/list', data)
}

export function addCfDnsRecord(data: {
  zoneId: string
  type: string
  name: string
  content: string
  proxied?: boolean
  ttl?: number
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
}) {
  return request.post('/cf/dns/update', data)
}

export function deleteCfDnsRecord(data: { zoneId: string; recordId: string }) {
  return request.post('/cf/dns/delete', data)
}
