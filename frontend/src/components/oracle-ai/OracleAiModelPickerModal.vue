<template>
  <teleport to="body">
    <div
      v-if="open"
      class="modal-backdrop open"
      aria-hidden="false"
      @click.self="emit('update:open', false)"
    >
      <section class="model-modal" role="dialog" aria-modal="true" aria-labelledby="modelModalTitle">
        <div class="modal-head">
          <div>
            <div id="modelModalTitle" class="modal-title">管理模型白名单</div>
            <div class="modal-subtitle">主页面只保留摘要；完整模型库在这里搜索、筛选、勾选。</div>
          </div>
          <button class="modal-close" type="button" @click="emit('update:open', false)">×</button>
        </div>

        <div class="modal-body">
          <div class="model-toolbar">
            <input
              ref="searchInputRef"
              v-model="keyword"
              class="input"
              placeholder="搜索模型 ID，例如 gemini / grok / gpt-oss"
            />
            <select v-model="capabilityFilter" class="select">
              <option value="">全部能力</option>
              <option value="chat">聊天</option>
              <option value="vision">视觉</option>
              <option value="reasoning">推理</option>
              <option value="responses">多代理</option>
              <option value="code">代码</option>
              <option value="non-chat">非聊天端点</option>
            </select>
            <select v-model="endpointFilter" class="select">
              <option value="">全部端点</option>
              <option value="chat">Chat Completions</option>
              <option value="responses">Responses</option>
              <option value="embed">Embeddings</option>
              <option value="rerank">Rerank</option>
              <option value="audio">Audio</option>
            </select>
            <label class="selected-only">
              <input v-model="selectedOnly" type="checkbox" />
              只看已选
            </label>
          </div>

          <div class="model-modal-layout">
            <div class="model-list-panel">
              <div class="model-list-head">
                <span>模型列表</span>
                <span class="sub-muted">{{ availableCount }} 个模型 · 当前显示 {{ filteredModels.length }} 个</span>
              </div>
              <div class="model-list-scroll">
                <table class="model-table">
                  <thead>
                    <tr>
                      <th>选择</th>
                      <th>模型</th>
                      <th>能力</th>
                      <th>端点</th>
                      <th>状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="model in filteredModels"
                      :key="model.id"
                      :class="{ 'model-row-disabled': !model.selectable }"
                    >
                      <td>
                        <input
                          type="checkbox"
                          :checked="isSelected(model.id)"
                          :disabled="!model.selectable && !isSelected(model.id)"
                          @change="toggleModel(model.id, model.selectable)"
                        />
                      </td>
                      <td>
                        <div class="model-id-cell">
                          <span class="model-id-main" :title="model.id">{{ model.id }}</span>
                          <span class="model-id-sub">{{ model.description }}</span>
                        </div>
                      </td>
                      <td><span class="tag" :class="model.tagColor">{{ model.capability }}</span></td>
                      <td>{{ model.endpoint }}</td>
                      <td><span class="tag" :class="model.selectable ? 'green' : 'default'">{{ model.selectable ? '可选' : '不可选' }}</span></td>
                    </tr>
                    <tr v-if="!filteredModels.length">
                      <td colspan="5" class="model-empty">没有符合条件的模型</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            <aside class="model-selected-panel">
              <div class="selected-head">
                <span>已选预览</span>
                <span class="tag green">{{ draftSelected.length }}</span>
              </div>
              <div class="selected-list">
                <template v-if="selectedPreview.length">
                  <div v-for="model in selectedPreview" :key="model.id" class="selected-item">
                    <b :title="model.id">{{ model.id }}</b>
                    <span>{{ model.endpoint }} · {{ model.capability }}</span>
                  </div>
                  <div v-if="selectedHiddenCount > 0" class="selected-item">
                    <b>+ {{ selectedHiddenCount }} 个</b>
                    <span>保存时写入白名单</span>
                  </div>
                </template>
                <div v-else class="selected-item">
                  <b>不限制模型</b>
                  <span>留空即允许全部模型</span>
                </div>
              </div>
            </aside>
          </div>
        </div>

        <div class="modal-footer">
          <button class="btn" type="button" @click="emit('update:open', false)">取消</button>
          <button class="btn primary" type="button" :disabled="saving || disabled" @click="confirmSelection">
            保存选择
          </button>
        </div>
      </section>
    </div>
  </teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  inferOracleAiModelMeta,
  uniqueModels,
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
const capabilityFilter = ref('')
const endpointFilter = ref('')
const selectedOnly = ref(false)
const draftSelected = ref<string[]>([])
const searchInputRef = ref<HTMLInputElement | null>(null)

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
const availableCount = computed(() => models.value.length)
const selectedSet = computed(() => new Set(draftSelected.value))
const selectedOnlyMeta = computed(() => {
  const byId = new Map(models.value.map((model) => [model.id, model]))
  return draftSelected.value.map((id) => byId.get(id) || inferOracleAiModelMeta(id))
})
const selectedPreview = computed(() => selectedOnlyMeta.value.slice(0, 6))
const selectedHiddenCount = computed(() => Math.max(0, draftSelected.value.length - selectedPreview.value.length))
const filteredModels = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return models.value.filter((model) => {
    if (q && !model.id.toLowerCase().includes(q) && !model.description.toLowerCase().includes(q)) return false
    if (capabilityFilter.value && model.capabilityValue !== capabilityFilter.value) return false
    if (endpointFilter.value && model.endpointValue !== endpointFilter.value) return false
    if (selectedOnly.value && !selectedSet.value.has(model.id)) return false
    return true
  })
})

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    draftSelected.value = uniqueModels(props.modelValue)
    await nextTick()
    searchInputRef.value?.focus()
  },
)

watch(
  () => props.modelValue,
  (value) => {
    if (!props.open) draftSelected.value = uniqueModels(value)
  },
  { deep: true, immediate: true },
)

function isSelected(id: string) {
  return selectedSet.value.has(id)
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

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 2200;
  display: grid;
  place-items: center;
  padding: 28px;
  background: rgba(2, 6, 23, 0.72);
  backdrop-filter: blur(8px);
}
.model-modal {
  width: min(1240px, calc(100vw - 56px));
  max-height: min(860px, calc(100vh - 56px));
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: rgba(15, 23, 42, 0.96);
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.42);
  overflow: hidden;
  color: var(--text-main);
}
.modal-head,
.modal-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  padding: 16px 18px;
  border-bottom: 1px solid var(--border);
}
.modal-title {
  font-size: 16px;
  font-weight: 700;
}
.modal-subtitle {
  margin-top: 3px;
  color: var(--text-sub);
  font-size: 12px;
}
.modal-close {
  width: 34px;
  height: 34px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: rgba(15, 23, 42, 0.5);
  color: var(--text-main);
  cursor: pointer;
}
.modal-body {
  min-height: 0;
  padding: 16px 18px;
  overflow: hidden;
}
.modal-footer {
  justify-content: flex-end;
  border-top: 1px solid var(--border);
  border-bottom: 0;
}
.model-toolbar {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) 168px 168px auto;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}
.input,
.select {
  height: 32px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--input-bg);
  color: var(--text-main);
  padding: 0 12px;
  outline: none;
}
.selected-only {
  display: flex;
  gap: 8px;
  align-items: center;
  color: var(--text-sub);
  font-size: 13px;
  line-height: 1.5;
  white-space: nowrap;
}
.selected-only input,
.model-table input {
  accent-color: var(--primary);
}
.model-modal-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 12px;
  min-height: 0;
}
.model-list-panel,
.model-selected-panel {
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: rgba(2, 6, 23, 0.22);
  overflow: hidden;
}
.model-list-head,
.selected-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
  padding: 11px 12px;
  border-bottom: 1px solid var(--border);
  font-weight: 600;
  font-size: 13px;
}
.sub-muted {
  color: var(--text-sub);
  font-size: 13px;
  line-height: 1.5;
  font-weight: 400;
}
.model-list-scroll {
  max-height: min(430px, calc(100vh - 310px));
  overflow: auto;
}
.model-table {
  width: 100%;
  min-width: 820px;
  border-collapse: collapse;
  font-size: 13px;
}
.model-table th,
.model-table td {
  padding: 10px 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.055);
  white-space: nowrap;
  text-align: left;
}
.model-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: rgba(15, 23, 42, 0.96);
  color: var(--text-sub);
  font-weight: 600;
}
.model-table tr:hover td {
  background: rgba(129, 140, 248, 0.04);
}
.model-row-disabled {
  opacity: 0.72;
}
.model-id-cell {
  display: grid;
  gap: 3px;
  min-width: 0;
}
.model-id-main {
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}
.model-id-sub {
  color: var(--text-sub);
  font-size: 11px;
}
.tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 7px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1;
  border: 1px solid transparent;
}
.tag.green {
  color: #86efac;
  background: rgba(34, 197, 94, 0.12);
  border-color: rgba(34, 197, 94, 0.22);
}
.tag.blue {
  color: #93c5fd;
  background: rgba(59, 130, 246, 0.12);
  border-color: rgba(59, 130, 246, 0.22);
}
.tag.orange {
  color: #fcd34d;
  background: rgba(245, 158, 11, 0.12);
  border-color: rgba(245, 158, 11, 0.22);
}
.tag.default {
  color: var(--text-sub);
  background: rgba(148, 163, 184, 0.1);
  border-color: rgba(148, 163, 184, 0.18);
}
.selected-list {
  display: grid;
  gap: 8px;
  max-height: min(430px, calc(100vh - 310px));
  padding: 12px;
  overflow: auto;
}
.selected-item {
  display: grid;
  gap: 4px;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: rgba(15, 23, 42, 0.45);
}
.selected-item b {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}
.selected-item span {
  color: var(--text-sub);
  font-size: 11px;
}
.model-empty {
  color: var(--text-sub);
  text-align: center;
}
.btn {
  height: 32px;
  padding: 0 15px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-card);
  color: var(--text-main);
  font-weight: 500;
  cursor: pointer;
}
.btn.primary {
  border-color: var(--primary);
  background: linear-gradient(135deg, var(--primary) 0%, var(--primary-hover) 100%);
  color: #fff;
  font-weight: 600;
  box-shadow: 0 4px 10px -2px rgba(99, 102, 241, 0.4);
}
.btn:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}
:global([data-theme='light']) .model-modal {
  background: rgba(255, 255, 255, 0.96);
}
:global([data-theme='light']) .model-list-panel,
:global([data-theme='light']) .model-selected-panel {
  background: rgba(248, 250, 252, 0.6);
}
:global([data-theme='light']) .model-table th {
  background: rgba(248, 250, 252, 0.96);
}
:global([data-theme='light']) .tag.green {
  color: #047857;
  background: rgba(16, 185, 129, 0.11);
  border-color: rgba(16, 185, 129, 0.28);
}
:global([data-theme='light']) .tag.blue {
  color: #1d4ed8;
  background: rgba(59, 130, 246, 0.1);
  border-color: rgba(59, 130, 246, 0.24);
}
:global([data-theme='light']) .tag.orange {
  color: #b45309;
  background: rgba(245, 158, 11, 0.12);
  border-color: rgba(245, 158, 11, 0.26);
}
@media (max-width: 900px) {
  .modal-backdrop {
    padding: 12px;
  }
  .model-modal {
    width: calc(100vw - 24px);
    max-height: calc(100vh - 24px);
  }
  .model-toolbar,
  .model-modal-layout {
    grid-template-columns: 1fr;
  }
}
</style>
