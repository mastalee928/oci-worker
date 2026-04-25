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

export function getOracleAiGenerativeContext(data: { ociUserId: string }) {
  return request.post('/oci/oracle-ai/generative-context/get', data)
}

export function saveOracleAiGenerativeContext(data: {
  ociUserId: string
  generativeOpenaiProject?: string
  generativeConversationStoreId?: string
}) {
  return request.post('/oci/oracle-ai/generative-context/save', data)
}

/** 管理面 ListGenerativeAiProject，用于一键填入 OpenAI-Project */
export function listGenerativeProjects(data: { ociUserId: string }) {
  return request.post('/oci/oracle-ai/generative-projects/list', data)
}
