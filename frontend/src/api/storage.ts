import request from '../utils/request'

export function listStorageRegions(data: { id: string }) {
  return request.post('/oci/storage/regions', data)
}

export function listStorageCompartments(data: { id: string; region: string }) {
  return request.post('/oci/storage/compartments', data)
}

export function blockStorageAggregate(data: {
  id: string
  region: string
  compartmentId?: string
  /** 逗号分隔子集，如 bootVolumes；不传则全量 */
  sections?: string
}) {
  return request.post('/oci/storage/block/aggregate', data)
}

export function objectStorageAggregate(data: { id: string; region: string; compartmentId?: string }) {
  return request.post('/oci/storage/object/aggregate', data)
}

export function deleteStorage(data: {
  id: string
  region: string
  resourceType: string
  resourceId: string
  namespace?: string
  bucketName?: string
  verifyCode: string
}) {
  return request.post('/oci/storage/delete', data)
}

export function putBucketPolicy(data: {
  id: string
  region: string
  namespace: string
  bucketName: string
  policy: string
  verifyCode: string
}) {
  return request.post('/oci/storage/object/bucketPolicy', data)
}

export function storageMutate(data: Record<string, unknown>) {
  return request.post('/oci/storage/mutate', data)
}
