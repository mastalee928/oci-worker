import request from '../utils/request'

export function getAliDNSAccountConfig() {
  return request.get('/alidns/account/config')
}

export function saveAliDNSAccountConfig(data: { accessKeyId: string; accessKeySecret: string }) {
  return request.post('/alidns/account/config', data)
}

export function testAliDNSAccountConfig(data: { accessKeyId: string; accessKeySecret: string }) {
  return request.post('/alidns/account/test', data)
}

export function listAliDNSDomains(page = 1, perPage = 20) {
  return request.post('/alidns/domains/list', { page, perPage })
}

export function listAliDNSRecords(params: {
  domainName: string
  page?: number
  perPage?: number
  rrKeyWord?: string
  typeKeyWord?: string
  valueKeyWord?: string
  line?: string
}) {
  return request.post('/alidns/records/list', params)
}

export function addAliDNSRecord(data: {
  domainName: string
  rr: string
  type: string
  value: string
  line?: string
  ttl?: number
  priority?: number | null
}) {
  return request.post('/alidns/records/add', data)
}

export function updateAliDNSRecord(data: {
  recordId: string
  rr: string
  type: string
  value: string
  line?: string
  ttl?: number
  priority?: number | null
}) {
  return request.post('/alidns/records/update', data)
}

export function deleteAliDNSRecord(recordId: string) {
  return request.post('/alidns/records/delete', { recordId })
}

export function setAliDNSRecordStatus(recordId: string, status: 'ENABLE' | 'DISABLE') {
  return request.post('/alidns/records/status', { recordId, status })
}

export function listAliDNSLines(domainName?: string) {
  return request.post('/alidns/lines/list', { domainName })
}

export function listAliDNSDomainDnsServers(domainName: string) {
  return request.post('/alidns/domains/dns-servers', { domainName })
}

