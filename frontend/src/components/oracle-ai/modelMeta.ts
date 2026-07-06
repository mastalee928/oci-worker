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
  capability: string
  capabilityValue: string
  endpoint: string
  endpointValue: string
  tagColor: 'green' | 'blue' | 'orange' | 'default'
  selectable: boolean
}

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

export function inferOracleAiModelMeta(option: OracleAiModelOption | string): OracleAiModelMeta {
  const id = normalizeModelId(typeof option === 'string' ? option : option.value)
  const label = normalizeModelId(typeof option === 'string' ? option : option.label) || id
  const title = typeof option === 'string' ? '' : normalizeModelId(option.title)
  const lower = id.toLowerCase()
  const source = providerName(lower)
  const forcedDisabled = typeof option !== 'string' && option.disabled === true

  if (lower.includes('embed')) {
    return {
      id,
      label,
      title,
      description: `${source} · Embedding 端点`,
      capability: 'Embed',
      capabilityValue: 'non-chat',
      endpoint: 'Embeddings',
      endpointValue: 'embed',
      tagColor: 'default',
      selectable: false,
    }
  }
  if (lower.includes('rerank')) {
    return {
      id,
      label,
      title,
      description: `${source} · Rerank 端点`,
      capability: 'Rerank',
      capabilityValue: 'non-chat',
      endpoint: 'Rerank',
      endpointValue: 'rerank',
      tagColor: 'default',
      selectable: false,
    }
  }
  if (lower.includes('tts') || lower.includes('voice-agent')) {
    return {
      id,
      label,
      title,
      description: `${source} · 音频生成端点`,
      capability: 'Audio',
      capabilityValue: 'non-chat',
      endpoint: 'Audio',
      endpointValue: 'audio',
      tagColor: 'default',
      selectable: false,
    }
  }
  if (lower.includes('moderator')) {
    return {
      id,
      label,
      title,
      description: `${source} · 内容安全端点`,
      capability: 'Moderation',
      capabilityValue: 'non-chat',
      endpoint: 'Moderation',
      endpointValue: 'moderation',
      tagColor: 'default',
      selectable: false,
    }
  }
  if (lower.includes('multi-agent') || lower.includes('multiagent')) {
    return {
      id,
      label,
      title,
      description: `${source} · 需要 Responses 桥接`,
      capability: 'Multi-Agent',
      capabilityValue: 'responses',
      endpoint: 'Responses',
      endpointValue: 'responses',
      tagColor: 'orange',
      selectable: !forcedDisabled,
    }
  }
  if (lower.includes('code')) {
    return {
      id,
      label,
      title,
      description: `${source} · 代码任务`,
      capability: 'Code',
      capabilityValue: 'code',
      endpoint: 'Chat Completions',
      endpointValue: 'chat',
      tagColor: 'blue',
      selectable: !forcedDisabled,
    }
  }
  if (lower.includes('non-reasoning')) {
    return {
      id,
      label,
      title,
      description: `${source} · 非推理聊天`,
      capability: 'Chat',
      capabilityValue: 'chat',
      endpoint: 'Chat Completions',
      endpointValue: 'chat',
      tagColor: 'green',
      selectable: !forcedDisabled,
    }
  }
  if (lower.includes('reasoning') || lower.includes('gpt-oss')) {
    return {
      id,
      label,
      title,
      description: `${source} · 推理模型`,
      capability: 'Reasoning',
      capabilityValue: 'reasoning',
      endpoint: 'Chat Completions',
      endpointValue: 'chat',
      tagColor: 'blue',
      selectable: !forcedDisabled,
    }
  }
  if (lower.includes('vision') || lower.includes('gemini')) {
    return {
      id,
      label,
      title,
      description: `${source} · 多模态`,
      capability: 'Vision',
      capabilityValue: 'vision',
      endpoint: 'Chat Completions',
      endpointValue: 'chat',
      tagColor: 'green',
      selectable: !forcedDisabled,
    }
  }
  return {
    id,
    label,
    title,
    description: `${source} · 通用聊天`,
    capability: 'Chat',
    capabilityValue: 'chat',
    endpoint: 'Chat Completions',
    endpointValue: 'chat',
    tagColor: 'green',
    selectable: !forcedDisabled,
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
