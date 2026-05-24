import request, { type OciRequestConfig } from '../utils/request'

/** 选区域时后台拉取，失败由页面静默处理，不弹全局红字 */
const cfSilent: OciRequestConfig = { skipBusinessMessage: true }

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

export function getCfZoneDetail(data: { zoneId: string }, silent = false) {
  return request.post('/cf/zones/detail', data, silent ? cfSilent : undefined)
}

export function createCfZone(data: { name: string }) {
  return request.post('/cf/zones/create', data)
}

export function deleteCfZone(data: { zoneId: string; verifyCode: string }) {
  return request.post('/cf/zones/delete', data)
}

export function setCfZonePaused(data: { zoneId: string; paused: boolean; verifyCode: string }) {
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

export function getCfDnssec(data: { zoneId: string }, silent = false) {
  return request.post('/cf/dns/dnssec/get', data, silent ? cfSilent : undefined)
}

export function setCfDnssec(data: { zoneId: string; status: 'active' | 'disabled' }) {
  return request.post('/cf/dns/dnssec/set', data)
}

export function getCfEmailSettings(data: { zoneId: string }, silent = false) {
  return request.post('/cf/email/settings', data, silent ? cfSilent : undefined)
}

export function enableCfEmailRouting(data: { zoneId: string }) {
  return request.post('/cf/email/enable', data)
}

export function disableCfEmailRouting(data: { zoneId: string }) {
  return request.post('/cf/email/disable', data)
}

export function getCfEmailDns(data: { zoneId: string }, silent = false) {
  return request.post('/cf/email/dns/get', data, silent ? cfSilent : undefined)
}

export function lockCfEmailDns(data: { zoneId: string }) {
  return request.post('/cf/email/dns/lock', data)
}

export function unlockCfEmailDns(data: { zoneId: string }) {
  return request.post('/cf/email/dns/unlock', data)
}

export function listCfEmailRules(data: { zoneId: string }, silent = false) {
  return request.post('/cf/email/rules/list', data, silent ? cfSilent : undefined)
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

export function getCfCatchAllRule(data: { zoneId: string }, silent = false) {
  return request.post('/cf/email/rules/catch-all/get', data, silent ? cfSilent : undefined)
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

export function listCfWorkers(silent = false) {
  return request.post('/cf/email/workers/list', {}, silent ? cfSilent : undefined)
}

export function listCfTunnels() {
  return request.post('/cf/tunnel/list', {})
}

export function createCfTunnel(data: { name: string }) {
  return request.post('/cf/tunnel/create', data)
}

export function deleteCfTunnel(data: { tunnelId: string; verifyCode: string }) {
  return request.post('/cf/tunnel/delete', data)
}

export function getCfTunnelToken(data: { tunnelId: string }) {
  return request.post('/cf/tunnel/token', data)
}

export function listCfTunnelConnections(data: { tunnelId: string }) {
  return request.post('/cf/tunnel/connections', data)
}

export function listCfTunnelRoutes(data: { tunnelId: string }) {
  return request.post('/cf/tunnel/routes/list', data)
}

export function createCfTunnelRoute(data: {
  tunnelId: string
  zoneId: string
  subdomain?: string
  service: string
}) {
  return request.post('/cf/tunnel/routes/create', data)
}

export function deleteCfTunnelRoute(data: { tunnelId: string; hostname: string }) {
  return request.post('/cf/tunnel/routes/delete', data)
}

export function listCfIpAccessRules() {
  return request.post('/cf/access-rules/list', {})
}

export function createCfIpAccessRule(data: {
  target: 'ip' | 'ip6' | 'ip_range' | 'country' | 'asn'
  value: string
  mode: 'block' | 'challenge' | 'js_challenge' | 'managed_challenge' | 'whitelist'
  notes?: string
}) {
  return request.post('/cf/access-rules/create', data)
}

export function deleteCfIpAccessRule(data: { ruleId: string }) {
  return request.post('/cf/access-rules/delete', data)
}

export function getCfSslSettings(data: { zoneId: string }, silent = false) {
  return request.post('/cf/ssl/get', data, silent ? cfSilent : undefined)
}

export function setCfSslSetting(data: { zoneId: string; settingId: string; value: unknown }) {
  return request.post('/cf/ssl/set', data)
}

export function getCfCacheSettings(data: { zoneId: string }, silent = false) {
  return request.post('/cf/cache/get', data, silent ? cfSilent : undefined)
}

export function setCfCacheSetting(data: { zoneId: string; settingId: string; value: unknown }) {
  return request.post('/cf/cache/set', data)
}

export function purgeCfCache(data: { zoneId: string; purgeEverything?: boolean; files?: string[] }) {
  return request.post('/cf/cache/purge', data)
}

export function getCfSecurityProtection(data: { zoneId: string }, silent = false) {
  return request.post('/cf/security/protection/get', data, silent ? cfSilent : undefined)
}

export function setCfSecurityProtection(data: { zoneId: string; settingId: string; value: unknown }) {
  return request.post('/cf/security/protection/set', data)
}

export function listCfFirewallRules(data: { zoneId: string }, silent = false) {
  return request.post('/cf/security/firewall/list', data, silent ? cfSilent : undefined)
}

export function createCfFirewallRule(data: {
  zoneId: string
  action: string
  expression: string
  description?: string
  paused?: boolean
}) {
  return request.post('/cf/security/firewall/create', data)
}

export function setCfFirewallRulePaused(data: { zoneId: string; ruleId: string; paused: boolean }) {
  return request.post('/cf/security/firewall/paused', data)
}

export function updateCfFirewallRule(data: {
  zoneId: string
  ruleId: string
  action?: string
  description?: string
  expression?: string
  paused?: boolean
}) {
  return request.post('/cf/security/firewall/update', data)
}

export function deleteCfFirewallRule(data: { zoneId: string; ruleId: string }) {
  return request.post('/cf/security/firewall/delete', data)
}

export function listCfWorkersRoutes(data: { zoneId: string }, silent = false) {
  return request.post('/cf/workers/routes/list', data, silent ? cfSilent : undefined)
}

export function createCfWorkersRoute(data: { zoneId: string; pattern: string; script: string }) {
  return request.post('/cf/workers/routes/create', data)
}

export function deleteCfWorkersRoute(data: { zoneId: string; routeId: string }) {
  return request.post('/cf/workers/routes/delete', data)
}

export function listCfZoneRules(data: { zoneId: string }, silent = false) {
  return request.post('/cf/rules/list', data, silent ? cfSilent : undefined)
}

export function listCfPageRules(data: { zoneId: string }, silent = false) {
  return request.post('/cf/rules/pagerules/list', data, silent ? cfSilent : undefined)
}

export function deleteCfPageRule(data: { zoneId: string; ruleId: string }) {
  return request.post('/cf/rules/pagerules/delete', data)
}

export function listCfWorkerScripts(silent = false) {
  return request.post('/cf/workers/scripts/list', {}, silent ? cfSilent : undefined)
}
