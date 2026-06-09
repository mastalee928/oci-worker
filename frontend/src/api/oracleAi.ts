import request from '../utils/request'

export function getOracleAiGateway() {
  return request.post('/oci/oracle-ai/gateway', {})
}

export function setOracleAiGatewayEnabled(data: { enabled: boolean }) {
  return request.post('/oci/oracle-ai/gateway/setEnabled', data)
}

export function setOracleAiDefaultMaxTokens(data: { defaultMaxTokens: number }) {
  return request.post('/oci/oracle-ai/gateway/default-max-tokens', data)
}

export function createOracleKey(data: { ociUserId: string; name?: string }) {
  return request.post('/oci/oracle-ai/keys/create', data)
}

export function listOracleKeys(data: { ociUserId: string }) {
  return request.post('/oci/oracle-ai/keys/list', data)
}

export function revealOracleKey(data: { id: string }) {
  return request.post('/oci/oracle-ai/keys/reveal', data)
}

export function setOracleKeyDisabled(data: { id: string; disabled: boolean }) {
  return request.post('/oci/oracle-ai/keys/setDisabled', data)
}

export function removeOracleKey(data: { id: string }) {
  return request.post('/oci/oracle-ai/keys/remove', data)
}

export function listOracleAiPortBindings() {
  return request.post('/oci/oracle-ai/ports/list', {})
}

export function saveOracleAiPortBinding(data: {
  id?: string
  name?: string
  port: number
  ociUserId: string
  openaiKeyId: string
  defaultMaxTokens?: number | null
  allowedModels?: string[]
  enabled?: boolean
}) {
  return request.post('/oci/oracle-ai/ports/save', data)
}

export function setOracleAiPortBindingEnabled(data: { id: string; enabled: boolean }) {
  return request.post('/oci/oracle-ai/ports/setEnabled', data)
}

export function removeOracleAiPortBinding(data: { id: string }) {
  return request.post('/oci/oracle-ai/ports/remove', data)
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

export function oracleAiChatTest(data: { apiKey: string; model: string; input: string }) {
  return request.post('/oci/oracle-ai/chat-test', data)
}

export function createGenerativeProject(data: { ociUserId: string; displayName?: string }) {
  return request.post('/oci/oracle-ai/generative-projects/create', data)
}

export function getOracleAiUiState() {
  return request.post('/oci/oracle-ai/ui-state/get', {})
}

export function saveOracleAiUiState(data: { ociUserId?: string; modelPick?: string[] }) {
  return request.post('/oci/oracle-ai/ui-state/save', data)
}
