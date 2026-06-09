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
