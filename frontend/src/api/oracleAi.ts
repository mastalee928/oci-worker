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
  ociRegion?: string
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

export function getOracleAiLbOverview() {
  return request.post('/oci/oracle-ai/lb/overview', {})
}

export function createOracleAiLbKey(data: { name?: string }) {
  return request.post('/oci/oracle-ai/lb/keys/create', data)
}

export function listOracleAiLbKeys() {
  return request.post('/oci/oracle-ai/lb/keys/list', {})
}

export function revealOracleAiLbKey(data: { id: string }) {
  return request.post('/oci/oracle-ai/lb/keys/reveal', data)
}

export function setOracleAiLbKeyDisabled(data: { id: string; disabled: boolean }) {
  return request.post('/oci/oracle-ai/lb/keys/setDisabled', data)
}

export function removeOracleAiLbKey(data: { id: string }) {
  return request.post('/oci/oracle-ai/lb/keys/remove', data)
}

export function listOracleAiLbMembers() {
  return request.post('/oci/oracle-ai/lb/members/list', {})
}

export function saveOracleAiLbMember(data: {
  id?: string
  portBindingId: string
  weight?: number | null
  enabled?: boolean
  requestLimit5h?: number | null
  requestLimit7d?: number | null
  maxConcurrency?: number | null
  rpmLimit?: number | null
  tpmLimit?: number | null
  contextLimit?: number | null
  streamFirstChunkTimeoutSeconds?: number | null
  streamIdleTimeoutSeconds?: number | null
  streamMaxSeconds?: number | null
}) {
  return request.post('/oci/oracle-ai/lb/members/save', data)
}

export function setOracleAiLbMemberEnabled(data: { id: string; enabled: boolean }) {
  return request.post('/oci/oracle-ai/lb/members/setEnabled', data)
}

export function removeOracleAiLbMember(data: { id: string }) {
  return request.post('/oci/oracle-ai/lb/members/remove', data)
}

export function clearOracleAiLbMemberModelState(data: { id: string; model?: string }) {
  return request.post('/oci/oracle-ai/lb/members/model-state/clear', data)
}

export function listOracleAiLbRequests(data: { limit?: number } = {}) {
  return request.post('/oci/oracle-ai/lb/requests/list', data)
}

export function listOpenAiModels(data: { ociUserId: string; ociRegion?: string; after?: string; modelId?: string }) {
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
