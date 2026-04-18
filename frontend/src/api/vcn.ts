import request from '../utils/request'

export function listVcns(data: { id: string }) {
  return request.post('/oci/vcn/list', data)
}

export function createVcn(data: {
  id: string
  compartmentId?: string
  displayName: string
  cidrBlock: string
  dnsLabel?: string
  createIgw?: boolean
}) {
  return request.post('/oci/vcn/create', data)
}

export function previewVcnDelete(data: { id: string; vcnId: string }) {
  return request.post('/oci/vcn/preview-delete', data)
}

export function deleteVcn(data: { id: string; vcnId: string; cascade?: boolean; verifyCode: string }) {
  return request.post('/oci/vcn/delete', data)
}
export function updateVcn(data: { id: string; vcnId: string; displayName?: string }) {
  return request.post('/oci/vcn/update', data)
}
export function listVcnGateways(data: { id: string; vcnId: string }) {
  return request.post('/oci/vcn/gateways', data)
}

// Subnet
export function listSubnets(data: { id: string; vcnId: string }) {
  return request.post('/oci/vcn/subnet/list', data)
}
export function createSubnet(data: {
  id: string
  vcnId: string
  displayName: string
  cidrBlock: string
  availabilityDomain?: string
  routeTableId?: string
  prohibitPublicIp?: boolean
}) {
  return request.post('/oci/vcn/subnet/create', data)
}
export function deleteSubnet(data: { id: string; subnetId: string; verifyCode: string }) {
  return request.post('/oci/vcn/subnet/delete', data)
}
export function updateSubnet(data: { id: string; subnetId: string; displayName?: string; routeTableId?: string; securityListIds?: string[] }) {
  return request.post('/oci/vcn/subnet/update', data)
}

// Internet Gateway
export function listInternetGateways(data: { id: string; vcnId: string }) {
  return request.post('/oci/vcn/igw/list', data)
}
export function createInternetGateway(data: { id: string; vcnId: string; displayName: string; isEnabled?: boolean }) {
  return request.post('/oci/vcn/igw/create', data)
}
export function deleteInternetGateway(data: { id: string; igwId: string; verifyCode: string }) {
  return request.post('/oci/vcn/igw/delete', data)
}
export function updateInternetGateway(data: { id: string; igwId: string; displayName?: string; isEnabled?: boolean }) {
  return request.post('/oci/vcn/igw/update', data)
}
export function setupIgwDefaultRoutes(data: { id: string; vcnId: string; igwId: string; addIpv6?: boolean }) {
  return request.post('/oci/vcn/igw/setupDefaultRoutes', data)
}

// NAT Gateway
export function listNatGateways(data: { id: string; vcnId: string }) {
  return request.post('/oci/vcn/nat/list', data)
}
export function createNatGateway(data: { id: string; vcnId: string; displayName: string }) {
  return request.post('/oci/vcn/nat/create', data)
}
export function deleteNatGateway(data: { id: string; natId: string; verifyCode: string }) {
  return request.post('/oci/vcn/nat/delete', data)
}
export function updateNatGateway(data: { id: string; natId: string; displayName?: string; blockTraffic?: boolean }) {
  return request.post('/oci/vcn/nat/update', data)
}

// Service Gateway
export function listServiceGateways(data: { id: string; vcnId: string }) {
  return request.post('/oci/vcn/sg/list', data)
}
export function createServiceGateway(data: { id: string; vcnId: string; displayName: string }) {
  return request.post('/oci/vcn/sg/create', data)
}
export function deleteServiceGateway(data: { id: string; sgId: string; verifyCode: string }) {
  return request.post('/oci/vcn/sg/delete', data)
}
export function updateServiceGateway(data: { id: string; sgId: string; displayName?: string; blockTraffic?: boolean }) {
  return request.post('/oci/vcn/sg/update', data)
}

// Route Table
export function listRouteTables(data: { id: string; vcnId: string }) {
  return request.post('/oci/vcn/rt/list', data)
}
export function deleteRouteTable(data: { id: string; rtId: string; verifyCode: string }) {
  return request.post('/oci/vcn/rt/delete', data)
}
export function getRouteTable(data: { id: string; rtId: string }) {
  return request.post('/oci/vcn/rt/detail', data)
}
export function updateRouteTable(data: { id: string; rtId: string; displayName?: string; routeRules?: any[] }) {
  return request.post('/oci/vcn/rt/update', data)
}

// Security List
export function listSecurityLists(data: { id: string; vcnId: string }) {
  return request.post('/oci/vcn/sl/list', data)
}
export function deleteSecurityList(data: { id: string; slId: string; verifyCode: string }) {
  return request.post('/oci/vcn/sl/delete', data)
}
export function getSecurityList(data: { id: string; slId: string }) {
  return request.post('/oci/vcn/sl/detail', data)
}
export function addSecurityListRule(data: { id: string; slId: string; direction: string; protocol: string; source: string; portMin?: string; portMax?: string; description?: string }) {
  return request.post('/oci/vcn/sl/addRule', data)
}
export function deleteSecurityListRule(data: { id: string; slId: string; direction: string; ruleIndex: number }) {
  return request.post('/oci/vcn/sl/deleteRule', data)
}

// DRG
export function listDrgs(data: { id: string }) {
  return request.post('/oci/vcn/drg/list', data)
}
export function createDrg(data: { id: string; compartmentId?: string; displayName: string }) {
  return request.post('/oci/vcn/drg/create', data)
}
export function deleteDrg(data: { id: string; drgId: string; verifyCode: string }) {
  return request.post('/oci/vcn/drg/delete', data)
}

// Local Peering Gateway
export function listLocalPeeringGateways(data: { id: string; vcnId: string }) {
  return request.post('/oci/vcn/lpg/list', data)
}
export function createLocalPeeringGateway(data: { id: string; vcnId: string; displayName: string }) {
  return request.post('/oci/vcn/lpg/create', data)
}
export function connectLocalPeeringGateway(data: { id: string; lpgId: string; peerId: string }) {
  return request.post('/oci/vcn/lpg/connect', data)
}
export function deleteLocalPeeringGateway(data: { id: string; lpgId: string; verifyCode: string }) {
  return request.post('/oci/vcn/lpg/delete', data)
}
export function updateLocalPeeringGateway(data: { id: string; lpgId: string; displayName?: string }) {
  return request.post('/oci/vcn/lpg/update', data)
}
