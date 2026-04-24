import request from '../utils/request'

export function getOracleAiGateway() {
  return request.post('/oci/oracle-ai/gateway', {})
}

export function createOracleKey(data: { ociUserId: string; name?: string }) {
  return request.post('/oci/oracle-ai/keys/create', data)
}

export function listOracleKeys(data: { ociUserId: string }) {
  return request.post('/oci/oracle-ai/keys/list', data)
}

export function setOracleKeyDisabled(data: { id: string; disabled: boolean }) {
  return request.post('/oci/oracle-ai/keys/setDisabled', data)
}

export function removeOracleKey(data: { id: string }) {
  return request.post('/oci/oracle-ai/keys/remove', data)
}

export function listOpenAiModels(data: { ociUserId: string; after?: string; modelId?: string }) {
  return request.post('/oci/oracle-ai/models', data)
}
