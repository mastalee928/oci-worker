export type OracleAiModelOption = {
  label: string
  value: string
  title?: string
  disabled?: boolean
}

export type OracleAiModelMeta = {
  id: string
  label: string
  title?: string
  description: string
  provider: string
  capability: string
  capabilityValue: string
  endpoint: string
  endpointValue: string
  group: OracleAiModelGroupId
  tagColor: 'green' | 'blue' | 'orange' | 'default'
  selectable: boolean
  statusLabel: string
}

export type OracleAiModelGroupId = 'chat' | 'audio' | 'embed' | 'rerank' | 'safety' | 'pending'

export function normalizeModelId(raw?: unknown) {
  return String(raw || '').trim()
}

function providerName(id: string) {
  const lower = id.toLowerCase()
  if (lower.startsWith('xai.')) return 'xAI'
  if (lower.startsWith('google.')) return 'Google'
  if (lower.startsWith('openai.')) return 'OpenAI OSS'
  if (lower.startsWith('cohere.')) return 'Cohere'
  if (lower.startsWith('meta.')) return 'Meta'
  if (lower.includes('moderator')) return 'Moderation'
  return 'Oracle AI'
}

const GROK_AUDIO_MODEL_IDS = new Set([
  'xai.grok-tts',
])

const GROK_MULTI_AGENT_MODEL_IDS = new Set([
  'xai.grok-4.20-multi-agent-0309',
  'xai.grok-4.20-multi-agent',
])

const GROK_CODE_MODEL_IDS = new Set([
  'xai.grok-code-fast-1',
])

const GROK_REASONING_MODEL_IDS = new Set([
  'xai.grok-4.3',
  'xai.grok-4.20-0309-reasoning',
  'xai.grok-4.20-reasoning',
  'xai.grok-4-1-fast-reasoning',
  'xai.grok-4-fast-reasoning',
  'xai.grok-4',
  'xai.grok-3-mini',
  'xai.grok-3-mini-fast',
])

const GROK_NON_REASONING_MODEL_IDS = new Set([
  'xai.grok-4.20-0309-non-reasoning',
  'xai.grok-4.20-non-reasoning',
  'xai.grok-4-1-fast-non-reasoning',
  'xai.grok-4-fast-non-reasoning',
  'xai.grok-3',
  'xai.grok-3-fast',
])

function isGrokReasoningModel(id: string) {
  return GROK_REASONING_MODEL_IDS.has(id)
}

function isGrokNonReasoningModel(id: string) {
  return GROK_NON_REASONING_MODEL_IDS.has(id)
}

export const ORACLE_AI_MODEL_GROUPS: Array<{
  id: OracleAiModelGroupId
  title: string
  configKey: string
  description: string
  dotClass: string
  tagClass: string
  emptyText: string
}> = [
  {
    id: 'chat',
    title: '聊天 / Responses',
    configKey: 'chatModels',
    description: '用于对话、推理、多模态和工具调用，进入 Chat Completions 或 Responses。',
    dotClass: '',
    tagClass: 'blue',
    emptyText: '当前筛选下没有聊天模型',
  },
  {
    id: 'audio',
    title: 'Audio Speech / TTS',
    configKey: 'audioSpeechModels',
    description: '用于文本转语音，调用入口为 /v1/audio/speech，不进入聊天白名单。',
    dotClass: 'green',
    tagClass: 'green',
    emptyText: '当前筛选下没有 TTS 模型',
  },
  {
    id: 'embed',
    title: 'Embedding',
    configKey: 'embeddingModels',
    description: '用于向量化文本或多模态内容，调用入口为 /v1/embeddings。',
    dotClass: 'cyan',
    tagClass: 'cyan',
    emptyText: '当前筛选下没有 Embedding 模型',
  },
  {
    id: 'rerank',
    title: 'Rerank',
    configKey: 'rerankModels',
    description: '用于重排序检索结果，调用入口为 /v1/rerank，后续新增 Rerank 模型直接进入这里。',
    dotClass: 'amber',
    tagClass: 'amber',
    emptyText: '当前筛选下没有 Rerank 模型',
  },
  {
    id: 'safety',
    title: '安全 / 审核',
    configKey: 'moderationModels',
    description: '用于内容安全、审核或防护类能力，不进入聊天模型池。',
    dotClass: 'rose',
    tagClass: 'rose',
    emptyText: '当前筛选下没有安全审核模型',
  },
  {
    id: 'pending',
    title: '待确认',
    configKey: 'unclassifiedModels',
    description: 'OCI 官方接口能力未确认前，只展示来源，不写入任何调用池。',
    dotClass: '',
    tagClass: 'default',
    emptyText: '当前筛选下没有待确认模型',
  },
]

export function inferOracleAiModelMeta(option: OracleAiModelOption | string): OracleAiModelMeta {
  const id = normalizeModelId(typeof option === 'string' ? option : option.value)
  const label = normalizeModelId(typeof option === 'string' ? option : option.label) || id
  const title = typeof option === 'string' ? '' : normalizeModelId(option.title)
  const lower = id.toLowerCase()
  const source = providerName(lower)
  const forcedDisabled = typeof option !== 'string' && option.disabled === true

  if (lower.includes('voice-agent')) {
    return {
      id,
      label,
      title,
      provider: source,
      description: `${source} · 等待 OCI 官方接口能力确认后开放`,
      capability: '待确认',
      capabilityValue: 'pending',
      endpoint: '待确认',
      endpointValue: 'pending',
      group: 'pending',
      tagColor: 'default',
      selectable: false,
      statusLabel: '待确认',
    }
  }

  if (lower.includes('embed')) {
    return {
      id,
      label,
      title,
      provider: source,
      description: `${source} · Embedding 端点`,
      capability: 'Embed',
      capabilityValue: 'embed',
      endpoint: 'Embeddings',
      endpointValue: 'embed',
      group: 'embed',
      tagColor: 'default',
      selectable: !forcedDisabled,
      statusLabel: forcedDisabled ? '停用' : 'Embed',
    }
  }
  if (lower.includes('rerank')) {
    return {
      id,
      label,
      title,
      provider: source,
      description: `${source} · Rerank 端点`,
      capability: 'Rerank',
      capabilityValue: 'rerank',
      endpoint: 'Rerank',
      endpointValue: 'rerank',
      group: 'rerank',
      tagColor: 'orange',
      selectable: !forcedDisabled,
      statusLabel: forcedDisabled ? '停用' : 'Rerank',
    }
  }
  if (
    GROK_AUDIO_MODEL_IDS.has(lower)
    || lower.includes('tts')
    || lower.includes('speech')
    || lower.includes('text-to-speech')
    || lower.includes('audio')
  ) {
    return {
      id,
      label,
      title,
      provider: source,
      description: `${source} · 音频生成端点`,
      capability: 'Audio',
      capabilityValue: 'audio',
      endpoint: 'Audio Speech',
      endpointValue: 'audio',
      group: 'audio',
      tagColor: 'green',
      selectable: !forcedDisabled,
      statusLabel: forcedDisabled ? '停用' : 'TTS',
    }
  }
  if (
    lower.includes('content-moderator')
    || lower.includes('moderation')
    || lower.includes('moderator')
    || lower.includes('llama-guard')
  ) {
    return {
      id,
      label,
      title,
      provider: source,
      description: `${source} · 内容安全端点`,
      capability: 'Moderation',
      capabilityValue: 'safety',
      endpoint: 'Moderation',
      endpointValue: 'moderation',
      group: 'safety',
      tagColor: 'default',
      selectable: false,
      statusLabel: '待接入',
    }
  }
  if (GROK_MULTI_AGENT_MODEL_IDS.has(lower) || lower.includes('multi-agent') || lower.includes('multiagent')) {
    return {
      id,
      label,
      title,
      provider: source,
      description: `${source} · 需要 Responses 桥接`,
      capability: 'Multi-Agent',
      capabilityValue: 'responses',
      endpoint: 'Responses',
      endpointValue: 'responses',
      group: 'chat',
      tagColor: 'orange',
      selectable: !forcedDisabled,
      statusLabel: forcedDisabled ? '停用' : 'Responses',
    }
  }
  if (GROK_CODE_MODEL_IDS.has(lower) || lower.includes('code')) {
    return {
      id,
      label,
      title,
      provider: source,
      description: `${source} · 代码任务`,
      capability: 'Code',
      capabilityValue: 'code',
      endpoint: 'Chat Completions',
      endpointValue: 'chat',
      group: 'chat',
      tagColor: 'blue',
      selectable: !forcedDisabled,
      statusLabel: forcedDisabled ? '停用' : 'Chat',
    }
  }
  if (lower.includes('cohere.command-a-reasoning')) {
    return {
      id,
      label,
      title,
      provider: source,
      description: `${source} · 多模态推理`,
      capability: 'Vision Reasoning',
      capabilityValue: 'vision',
      endpoint: 'Chat Completions',
      endpointValue: 'chat',
      group: 'chat',
      tagColor: 'blue',
      selectable: !forcedDisabled,
      statusLabel: forcedDisabled ? '停用' : 'Reasoning',
    }
  }
  if (isGrokNonReasoningModel(lower) || lower.includes('non-reasoning')) {
    return {
      id,
      label,
      title,
      provider: source,
      description: `${source} · 非推理聊天`,
      capability: 'Chat',
      capabilityValue: 'chat',
      endpoint: 'Chat Completions',
      endpointValue: 'chat',
      group: 'chat',
      tagColor: 'green',
      selectable: !forcedDisabled,
      statusLabel: forcedDisabled ? '停用' : 'Chat',
    }
  }
  if (isGrokReasoningModel(lower) || lower.includes('reasoning') || lower.includes('gpt-oss')) {
    return {
      id,
      label,
      title,
      provider: source,
      description: `${source} · 推理模型`,
      capability: 'Reasoning',
      capabilityValue: 'reasoning',
      endpoint: 'Chat Completions',
      endpointValue: 'chat',
      group: 'chat',
      tagColor: 'blue',
      selectable: !forcedDisabled,
      statusLabel: forcedDisabled ? '停用' : 'Reasoning',
    }
  }
  if (lower.includes('vision') || lower.includes('gemini') || lower.includes('llama-4-')) {
    return {
      id,
      label,
      title,
      provider: source,
      description: `${source} · 多模态`,
      capability: 'Vision',
      capabilityValue: 'vision',
      endpoint: 'Chat Completions',
      endpointValue: 'chat',
      group: 'chat',
      tagColor: 'green',
      selectable: !forcedDisabled,
      statusLabel: forcedDisabled ? '停用' : 'Vision',
    }
  }
  return {
    id,
    label,
    title,
    provider: source,
    description: `${source} · 通用聊天`,
    capability: 'Chat',
    capabilityValue: 'chat',
    endpoint: 'Chat Completions',
    endpointValue: 'chat',
    group: 'chat',
    tagColor: 'green',
    selectable: !forcedDisabled,
    statusLabel: forcedDisabled ? '停用' : 'Chat',
  }
}

export function uniqueModels(models?: string[]) {
  const seen = new Set<string>()
  const out: string[] = []
  for (const raw of models || []) {
    const id = normalizeModelId(raw)
    if (!id || seen.has(id)) continue
    seen.add(id)
    out.push(id)
  }
  return out
}
