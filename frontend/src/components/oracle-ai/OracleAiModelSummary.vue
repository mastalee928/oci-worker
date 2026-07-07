<template>
  <a-form-item class="model-summary-field" label="模型库（OCI 管理面 ListModels）">
    <div class="model-summary-panel">
      <div class="model-summary-main">
        <div class="model-summary-title">已选模型</div>
        <div class="model-tags">
          <template v-if="selectedModels.length">
            <span v-for="model in visibleSelectedModels" :key="model" class="tag green">{{ model }}</span>
            <span v-if="hiddenSelectedCount > 0" class="tag default">+ {{ hiddenSelectedCount }}</span>
          </template>
          <span v-else class="tag default">不限制模型</span>
        </div>
        <div class="model-summary-meta">
          <span>可选模型：{{ selectableCount }}</span>
          <span>可用模型：{{ availableCount }}</span>
          <span>上次刷新：{{ lastRefreshedText || '-' }}</span>
        </div>
      </div>
      <div class="toolbar model-summary-actions">
        <a-button :disabled="disabled" @click="emit('open-picker')">管理模型</a-button>
        <a-button type="primary" :loading="loading" :disabled="disabled" @click="emit('refresh-models')">
          刷新模型列表
        </a-button>
      </div>
    </div>
  </a-form-item>
  <div class="toolbar model-save-row">
    <a-button :loading="saving" :disabled="disabled" @click="emit('save-selection')">
      保存
    </a-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { inferOracleAiModelMeta, uniqueModels, type OracleAiModelOption } from './modelMeta'

const props = withDefaults(defineProps<{
  modelValue?: string[]
  options?: OracleAiModelOption[]
  loading?: boolean
  saving?: boolean
  disabled?: boolean
  lastRefreshedText?: string
}>(), {
  modelValue: () => [],
  options: () => [],
  loading: false,
  saving: false,
  disabled: false,
  lastRefreshedText: '',
})

const emit = defineEmits<{
  (e: 'refresh-models'): void
  (e: 'save-selection'): void
  (e: 'open-picker'): void
}>()

const selectedModels = computed(() => uniqueModels(props.modelValue))
const visibleSelectedModels = computed(() => selectedModels.value.slice(0, 3))
const hiddenSelectedCount = computed(() => Math.max(0, selectedModels.value.length - visibleSelectedModels.value.length))
const availableCount = computed(() => props.options?.length || 0)
const selectableCount = computed(() => {
  return (props.options || []).filter((option) => inferOracleAiModelMeta(option).selectable).length
})
</script>

<style scoped>
.model-summary-field {
  container-type: inline-size;
}
.model-summary-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  align-items: center;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(15, 23, 42, 0.38);
}
.model-summary-main {
  min-width: 0;
}
.model-summary-title {
  margin-bottom: 7px;
  color: var(--text-main);
  font-weight: 600;
  font-size: 13px;
}
.model-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
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
  margin-right: 0;
  max-width: min(260px, 100%);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag.green {
  color: #86efac;
  background: rgba(34, 197, 94, 0.12);
  border-color: rgba(34, 197, 94, 0.22);
}
.tag.default {
  color: var(--text-sub);
  background: rgba(148, 163, 184, 0.1);
  border-color: rgba(148, 163, 184, 0.18);
}
.model-summary-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-top: 8px;
  color: var(--text-sub);
  font-size: 12px;
}
.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 0;
}
.model-summary-actions {
  min-width: 0;
  justify-content: flex-end;
}
.model-summary-actions :deep(.ant-btn) {
  max-width: 100%;
}
.model-save-row {
  margin-top: 0;
}
:global([data-theme='light']) .model-summary-panel {
  border-color: rgba(15, 23, 42, 0.08);
  background: #f8fafc;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);
}
:global([data-theme='light']) .tag.green {
  color: #047857;
  background: rgba(16, 185, 129, 0.11);
  border-color: rgba(16, 185, 129, 0.28);
}
:global([data-theme='light']) .tag.default {
  color: #475569;
  background: rgba(100, 116, 139, 0.1);
  border-color: rgba(100, 116, 139, 0.2);
}
@media (max-width: 900px) {
  .model-summary-panel {
    grid-template-columns: 1fr;
  }
  .model-summary-actions {
    justify-content: flex-start;
  }
}
@container (max-width: 760px) {
  .model-summary-panel {
    grid-template-columns: 1fr;
  }
  .model-summary-actions {
    justify-content: flex-start;
  }
}
</style>
