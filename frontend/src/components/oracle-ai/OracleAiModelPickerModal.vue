<template>
  <teleport to="body">
    <div
      v-if="open"
      class="model-library-backdrop"
      aria-hidden="false"
      @click.self="emit('update:open', false)"
    >
      <section class="model-library-modal" role="dialog" aria-modal="true" aria-labelledby="modelModalTitle" @wheel.stop>
        <div class="model-library-head">
          <div>
            <div id="modelModalTitle" class="model-library-title">管理模型库</div>
            <div class="model-library-subtitle">
              所有模型统一展示，但按端点能力分组保存，避免聊天、TTS、Embedding、Rerank 混在一起。
            </div>
          </div>
          <button class="model-library-close" type="button" @click="emit('update:open', false)">×</button>

          <div class="model-library-toolbar">
            <input
              ref="searchInputRef"
              v-model="keyword"
              class="model-library-input"
              placeholder="全局搜索模型 ID，例如 grok / gemini / rerank / embed"
            />
            <select v-model="providerFilter" class="model-library-select">
              <option value="">全部来源</option>
              <option value="xai">xAI</option>
              <option value="google">Google</option>
              <option value="openai">OpenAI OSS</option>
              <option value="cohere">Cohere</option>
              <option value="meta">Meta</option>
              <option value="oracle">Oracle AI</option>
            </select>
            <select v-model="groupFilter" class="model-library-select">
              <option value="">全部端点</option>
              <option value="chat">聊天</option>
              <option value="audio">Audio Speech</option>
              <option value="embed">Embedding</option>
              <option value="rerank">Rerank</option>
              <option value="safety">安全审核</option>
              <option value="pending">待确认</option>
            </select>
            <select v-model="statusFilter" class="model-library-select">
              <option value="">全部状态</option>
              <option value="selected">已选择</option>
              <option value="unselected">未选择</option>
              <option value="pending">待确认</option>
            </select>
          </div>
        </div>

        <div class="model-library-body">
          <div class="model-library-layout">
            <div class="model-groups">
              <section
                v-for="group in visibleGroups"
                :key="group.id"
                class="model-group"
                :class="{ empty: !group.models.length }"
              >
                <div class="model-group-head">
                  <div>
                    <div class="model-group-title-row">
                      <span class="model-dot" :class="group.dotClass"></span>
                      <span class="model-group-title">{{ group.title }}</span>
                      <span class="model-tag" :class="group.tagClass">{{ group.configKey }}</span>
                    </div>
                    <div class="model-group-desc">{{ group.description }}</div>
                  </div>
                  <span class="model-tag" :class="group.tagClass">{{ groupSelectedCount(group.id) ? `已选 ${groupSelectedCount(group.id)}` : group.id === 'pending' ? '等待确认' : '未选择' }}</span>
                </div>

                <div class="model-list">
                  <label
                    v-for="model in group.models"
                    :key="model.id"
                    class="model-row"
                    :class="[
                      model.group === 'audio' ? 'audio' : '',
                      model.group === 'pending' ? 'pending' : '',
                      isSelected(model.id) ? 'active' : '',
                    ]"
                  >
                    <input
                      type="checkbox"
                      :checked="isSelected(model.id)"
                      :disabled="!model.selectable && !isSelected(model.id)"
                      @change="toggleModel(model.id, model.selectable)"
                    />
                    <span class="model-row-main">
                      <span class="model-name" :title="model.id">{{ model.id }}</span>
                      <span class="model-desc">{{ model.description }}</span>
                    </span>
                    <span class="model-actions">
                      <span class="model-tag" :class="model.tagColor">{{ model.capability }}</span>
                      <span class="model-tag" :class="endpointTagClass(model.group)">{{ model.statusLabel }}</span>
                    </span>
                  </label>
                  <div v-if="!group.models.length" class="model-empty-line">{{ group.emptyText }}</div>
                </div>
              </section>
            </div>

            <aside class="model-preview-panel">
              <div class="model-preview-title">
                <span>保存预览</span>
                <span class="model-tag blue">分池写入</span>
              </div>

              <div v-if="!draftSelected.length" class="model-preview-block">
                <div class="model-preview-block-title">allowedModels</div>
                <div class="model-preview-item">不限制模型</div>
              </div>

              <template v-else>
                <div
                  v-for="group in groups"
                  :key="group.id"
                  class="model-preview-block"
                >
                  <div class="model-preview-block-title">{{ group.configKey }}</div>
                  <template v-if="selectedByGroup[group.id]?.length">
                    <div
                      v-for="model in selectedByGroup[group.id]"
                      :key="model.id"
                      class="model-preview-item"
                      :title="model.id"
                    >
                      {{ model.id }}
                    </div>
                  </template>
                  <div v-else class="model-preview-item muted">未选择</div>
                </div>
              </template>

              <div class="model-endpoint-map">
                <div><b>聊天请求</b> 不会主动选择 TTS / Rerank / Embedding 模型</div>
                <div><b>非聊天请求</b> 按端点进入对应模型池</div>
                <div><b>未来模型</b> 先分类，未确认时进入待确认</div>
              </div>
            </aside>
          </div>
        </div>

        <div class="model-library-footer">
          <div class="model-footer-note">
            这个结构不是只为 TTS 服务，后续 Rerank、Embedding、安全审核都能继续扩展。
          </div>
          <div class="model-footer-actions">
            <button class="model-btn" type="button" @click="emit('update:open', false)">取消</button>
            <button class="model-btn primary" type="button" :disabled="saving || disabled" @click="confirmSelection">
              保存模型库
            </button>
          </div>
        </div>
      </section>
    </div>
  </teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  ORACLE_AI_MODEL_GROUPS,
  inferOracleAiModelMeta,
  uniqueModels,
  type OracleAiModelGroupId,
  type OracleAiModelMeta,
  type OracleAiModelOption,
} from './modelMeta'

const props = withDefaults(defineProps<{
  open?: boolean
  modelValue?: string[]
  options?: OracleAiModelOption[]
  saving?: boolean
  disabled?: boolean
}>(), {
  open: false,
  modelValue: () => [],
  options: () => [],
  saving: false,
  disabled: false,
})

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:modelValue', value: string[]): void
  (e: 'confirm', value: string[]): void
}>()

const keyword = ref('')
const providerFilter = ref('')
const groupFilter = ref<'' | OracleAiModelGroupId>('')
const statusFilter = ref('')
const draftSelected = ref<string[]>([])
const searchInputRef = ref<HTMLInputElement | null>(null)
let previousBodyOverflow: string | null = null

const groups = ORACLE_AI_MODEL_GROUPS

const models = computed<OracleAiModelMeta[]>(() => {
  const rows = (props.options || []).map((option) => inferOracleAiModelMeta(option)).filter((x) => x.id)
  const seen = new Set<string>()
  const out = rows.filter((row) => {
    if (seen.has(row.id)) return false
    seen.add(row.id)
    return true
  })
  for (const id of uniqueModels(props.modelValue)) {
    if (seen.has(id)) continue
    seen.add(id)
    out.push(inferOracleAiModelMeta(id))
  }
  return out
})

const selectedSet = computed(() => new Set(draftSelected.value))
const selectedByGroup = computed<Record<OracleAiModelGroupId, OracleAiModelMeta[]>>(() => {
  const byId = new Map(models.value.map((model) => [model.id, model]))
  const bucket = emptyGroupBucket()
  for (const id of draftSelected.value) {
    const model = byId.get(id) || inferOracleAiModelMeta(id)
    bucket[model.group].push(model)
  }
  return bucket
})

const visibleGroups = computed(() => {
  const filtered = filterModels(models.value)
  return groups
    .filter((group) => !groupFilter.value || group.id === groupFilter.value)
    .map((group) => ({
      ...group,
      models: filtered.filter((model) => model.group === group.id),
    }))
})

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      unlockPageScroll()
      return
    }
    lockPageScroll()
    draftSelected.value = uniqueModels(props.modelValue)
    await nextTick()
    searchInputRef.value?.focus()
  },
  { immediate: true },
)

watch(
  () => props.modelValue,
  (value) => {
    if (!props.open) draftSelected.value = uniqueModels(value)
  },
  { deep: true, immediate: true },
)

function emptyGroupBucket(): Record<OracleAiModelGroupId, OracleAiModelMeta[]> {
  return {
    chat: [],
    audio: [],
    embed: [],
    rerank: [],
    safety: [],
    pending: [],
  }
}

function providerValue(model: OracleAiModelMeta) {
  const id = model.id.toLowerCase()
  if (id.startsWith('xai.')) return 'xai'
  if (id.startsWith('google.')) return 'google'
  if (id.startsWith('openai.')) return 'openai'
  if (id.startsWith('cohere.')) return 'cohere'
  if (id.startsWith('meta.')) return 'meta'
  return 'oracle'
}

function filterModels(input: OracleAiModelMeta[]) {
  const q = keyword.value.trim().toLowerCase()
  return input.filter((model) => {
    const haystack = [
      model.id,
      model.label,
      model.description,
      model.provider,
      model.capability,
      model.endpoint,
    ].join(' ').toLowerCase()
    if (q && !haystack.includes(q)) return false
    if (providerFilter.value && providerValue(model) !== providerFilter.value) return false
    if (statusFilter.value === 'selected' && !selectedSet.value.has(model.id)) return false
    if (statusFilter.value === 'unselected' && selectedSet.value.has(model.id)) return false
    if (statusFilter.value === 'pending' && model.group !== 'pending') return false
    return true
  })
}

function groupSelectedCount(group: OracleAiModelGroupId) {
  return selectedByGroup.value[group]?.length || 0
}

function isSelected(id: string) {
  return selectedSet.value.has(id)
}

function endpointTagClass(group: OracleAiModelGroupId) {
  if (group === 'audio') return 'green'
  if (group === 'embed') return 'cyan'
  if (group === 'rerank') return 'amber'
  if (group === 'safety') return 'rose'
  if (group === 'pending') return 'default'
  return 'blue'
}

function toggleModel(id: string, selectable: boolean) {
  if (!selectable && !isSelected(id)) return
  const next = new Set(draftSelected.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  draftSelected.value = Array.from(next)
}

function confirmSelection() {
  const selected = uniqueModels(draftSelected.value)
  emit('update:modelValue', selected)
  emit('confirm', selected)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.open) {
    emit('update:open', false)
  }
}

function lockPageScroll() {
  if (typeof document === 'undefined' || previousBodyOverflow !== null) return
  previousBodyOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
}

function unlockPageScroll() {
  if (typeof document === 'undefined' || previousBodyOverflow === null) return
  document.body.style.overflow = previousBodyOverflow
  previousBodyOverflow = null
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown)
  unlockPageScroll()
})
</script>

<style>
.model-library-backdrop {
  position: fixed;
  inset: 0;
  z-index: 2200;
  display: grid;
  place-items: center;
  padding: 28px;
  background: rgba(2, 6, 23, 0.56);
  backdrop-filter: blur(7px);
  overscroll-behavior: contain;
}

[data-theme='light'] .model-library-backdrop {
  background: rgba(15, 23, 42, 0.16);
}

.model-library-modal {
  width: min(1240px, calc(100vw - 48px));
  height: min(880px, calc(100vh - 56px));
  max-height: min(880px, calc(100vh - 56px));
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 18px;
  background: rgba(15, 23, 42, 0.78);
  box-shadow: 0 22px 70px rgba(0, 0, 0, 0.36);
  color: var(--text-main);
  overscroll-behavior: contain;
}

[data-theme='light'] .model-library-modal {
  border-color: rgba(15, 23, 42, 0.12);
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 20px 58px rgba(15, 23, 42, 0.12);
}

.model-library-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--border);
}

.model-library-title {
  font-size: 18px;
  font-weight: 750;
}

.model-library-subtitle {
  margin-top: 4px;
  color: var(--text-sub);
  font-size: 13px;
}

.model-library-close {
  width: 34px;
  height: 34px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-card);
  color: var(--text-sub);
  cursor: pointer;
}

.model-library-toolbar {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 150px 150px 136px;
  gap: 10px;
  margin-top: 14px;
}

.model-library-input,
.model-library-select {
  width: 100%;
  height: 36px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--input-bg);
  color: var(--text-main);
  outline: none;
}

.model-library-input {
  padding: 0 12px;
}

.model-library-select {
  padding: 0 10px;
}

.model-library-input::placeholder {
  color: var(--text-sub);
}

.model-library-input:focus,
.model-library-select:focus {
  border-color: rgba(129, 140, 248, 0.48);
  box-shadow: 0 0 0 3px rgba(129, 140, 248, 0.15);
}

.model-library-body {
  min-height: 0;
  overflow: hidden;
  padding: 16px 18px 18px;
  overscroll-behavior: contain;
}

.model-library-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 14px;
  align-items: stretch;
  min-height: 0;
  height: 100%;
}

.model-groups {
  min-height: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: rgba(2, 6, 23, 0.12);
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
}

[data-theme='light'] .model-groups {
  background: rgba(248, 250, 252, 0.72);
}

.model-group {
  overflow: visible;
  border-bottom: 1px solid var(--border);
  background: transparent;
}

.model-group:last-child {
  border-bottom: 0;
}

.model-group-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  padding: 13px 14px;
  border-bottom: 1px solid var(--border);
}

.model-group-title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.model-group-title {
  font-weight: 750;
}

.model-group-desc {
  margin-top: 4px;
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.45;
}

.model-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--primary);
  box-shadow: 0 0 0 4px rgba(129, 140, 248, 0.15);
}

.model-dot.green {
  background: #22c55e;
  box-shadow: 0 0 0 4px rgba(34, 197, 94, 0.13);
}

.model-dot.amber {
  background: #f59e0b;
  box-shadow: 0 0 0 4px rgba(245, 158, 11, 0.12);
}

.model-dot.cyan {
  background: #22d3ee;
  box-shadow: 0 0 0 4px rgba(34, 211, 238, 0.12);
}

.model-dot.rose {
  background: #fb7185;
  box-shadow: 0 0 0 4px rgba(251, 113, 133, 0.12);
}

[data-theme='light'] .model-dot.green {
  background: #16a34a;
  box-shadow: 0 0 0 4px rgba(22, 163, 74, 0.1);
}

[data-theme='light'] .model-dot.amber {
  background: #d97706;
  box-shadow: 0 0 0 4px rgba(217, 119, 6, 0.1);
}

[data-theme='light'] .model-dot.cyan {
  background: #0891b2;
  box-shadow: 0 0 0 4px rgba(8, 145, 178, 0.1);
}

[data-theme='light'] .model-dot.rose {
  background: #e11d48;
  box-shadow: 0 0 0 4px rgba(225, 29, 72, 0.1);
}

.model-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  color: var(--text-sub);
  background: rgba(148, 163, 184, 0.08);
  font-size: 12px;
  white-space: nowrap;
}

.model-tag.blue {
  color: var(--primary);
  border-color: rgba(129, 140, 248, 0.28);
  background: rgba(129, 140, 248, 0.15);
}

.model-tag.green {
  color: #22c55e;
  border-color: rgba(34, 197, 94, 0.26);
  background: rgba(34, 197, 94, 0.13);
}

.model-tag.orange,
.model-tag.amber {
  color: #f59e0b;
  border-color: rgba(245, 158, 11, 0.26);
  background: rgba(245, 158, 11, 0.12);
}

.model-tag.cyan {
  color: #22d3ee;
  border-color: rgba(34, 211, 238, 0.26);
  background: rgba(34, 211, 238, 0.12);
}

.model-tag.rose {
  color: #fb7185;
  border-color: rgba(251, 113, 133, 0.26);
  background: rgba(251, 113, 133, 0.12);
}

[data-theme='light'] .model-tag.green {
  color: #16a34a;
  background: rgba(22, 163, 74, 0.1);
}

[data-theme='light'] .model-tag.orange,
[data-theme='light'] .model-tag.amber {
  color: #d97706;
  background: rgba(217, 119, 6, 0.1);
}

[data-theme='light'] .model-tag.cyan {
  color: #0891b2;
  background: rgba(8, 145, 178, 0.1);
}

[data-theme='light'] .model-tag.rose {
  color: #e11d48;
  background: rgba(225, 29, 72, 0.1);
}

.model-list {
  display: grid;
  gap: 8px;
  padding: 10px;
}

.model-row {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  min-height: 62px;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-card);
  cursor: pointer;
}

.model-row input {
  accent-color: var(--primary);
}

.model-row.active {
  border-color: rgba(129, 140, 248, 0.34);
  background: linear-gradient(135deg, rgba(129, 140, 248, 0.15), rgba(99, 102, 241, 0.04));
}

.model-row.audio.active {
  border-color: rgba(34, 197, 94, 0.34);
  background: linear-gradient(135deg, rgba(34, 197, 94, 0.13), rgba(34, 197, 94, 0.04));
}

.model-row.pending {
  cursor: default;
  opacity: 0.72;
}

.model-row-main {
  min-width: 0;
}

.model-name {
  display: block;
  overflow: hidden;
  color: var(--text-main);
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-desc {
  display: block;
  margin-top: 4px;
  overflow: hidden;
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

.model-empty-line {
  padding: 16px;
  color: var(--text-sub);
  text-align: center;
  font-size: 12px;
}

.model-preview-panel {
  display: grid;
  align-content: start;
  gap: 12px;
  min-height: 0;
  max-height: 100%;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--bg-card);
  padding: 14px;
}

.model-preview-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-weight: 750;
}

.model-preview-block {
  display: grid;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(148, 163, 184, 0.07);
}

.model-preview-block-title {
  color: var(--text-sub);
  font-size: 12px;
}

.model-preview-item {
  overflow: hidden;
  padding: 6px 8px;
  border-radius: 8px;
  background: rgba(148, 163, 184, 0.08);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-preview-item.muted {
  color: var(--text-sub);
  font-family: inherit;
}

.model-endpoint-map {
  display: grid;
  gap: 7px;
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.4;
}

.model-endpoint-map b {
  color: var(--text-main);
  font-weight: 650;
}

.model-library-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  border-top: 1px solid var(--border);
}

.model-footer-note {
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.45;
}

.model-footer-actions {
  display: flex;
  gap: 8px;
  white-space: nowrap;
}

.model-btn {
  height: 36px;
  padding: 0 14px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-card);
  color: var(--text-main);
  font-weight: 500;
  cursor: pointer;
}

.model-btn.primary {
  border-color: rgba(99, 102, 241, 0.34);
  color: #fff;
  background: linear-gradient(135deg, var(--primary), var(--primary-hover));
  box-shadow: 0 10px 22px rgba(99, 102, 241, 0.22);
}

.model-btn:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}

@media (max-width: 1100px) {
  .model-library-body {
    overflow: auto;
  }

  .model-library-layout {
    grid-template-columns: 1fr;
    height: auto;
  }

  .model-groups {
    overflow: visible;
  }

  .model-preview-panel {
    max-height: 360px;
  }
}

@media (max-width: 980px) {
  .model-library-backdrop {
    padding: 12px;
  }

  .model-library-modal {
    width: calc(100vw - 24px);
    height: calc(100vh - 24px);
    max-height: calc(100vh - 24px);
  }

  .model-library-toolbar {
    grid-template-columns: 1fr;
  }

  .model-library-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .model-footer-actions {
    justify-content: flex-end;
  }

  .model-row {
    grid-template-columns: 26px minmax(0, 1fr);
  }

  .model-actions {
    grid-column: 2;
    justify-content: flex-start;
  }
}
</style>
