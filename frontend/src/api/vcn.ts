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

// Route Table
export function listRouteTables(data: { id: string; vcnId: string }) {
  return request.post('/oci/vcn/rt/list', data)
}
export function deleteRouteTable(data: { id: string; rtId: string; verifyCode: string }) {
  return request.post('/oci/vcn/rt/delete', data)
}

// Security List
export function listSecurityLists(data: { id: string; vcnId: string }) {
  return request.post('/oci/vcn/sl/list', data)
}
export function deleteSecurityList(data: { id: string; slId: string; verifyCode: string }) {
  return request.post('/oci/vcn/sl/delete', data)
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
