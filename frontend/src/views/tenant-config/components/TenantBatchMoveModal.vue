<template>
  <a-modal
    :mask-closable="false"
    :keyboard="false"
    :open="open"
    title="批量移动到分组"
    :confirm-loading="loading"
    :width="isMobile ? 'calc(100vw - 32px)' : 480"
    @update:open="emit('update:open', $event)"
    @ok="handleOk"
  >
    <p style="margin: 0 0 12px; color: var(--text-sub)">已选择 {{ selectedCount }} 个租户</p>
    <a-form layout="vertical">
      <a-form-item label="一级分组" required>
        <a-select
          :value="level1"
          placeholder="选择一级分组"
          show-search
          :filter-option="filterOption"
          @change="handleLevel1Change"
        >
          <a-select-option v-for="group in level1Options" :key="group" :value="group">{{ group }}</a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item v-if="level1 && level1 !== '未分组'" label="二级分组（可选）">
        <a-select
          :value="level2"
          placeholder="不选则仅归入一级分组"
          allow-clear
          show-search
          :filter-option="filterOption"
          @update:value="emit('update:level2', $event)"
        >
          <a-select-option v-for="group in level2Options" :key="group" :value="group">{{ group }}</a-select-option>
        </a-select>
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
defineOptions({ name: 'TenantBatchMoveModal' })

type FilterOption = (input: string, option: any) => boolean

const props = defineProps<{
  open: boolean
  loading: boolean
  isMobile: boolean
  selectedCount: number
  level1?: string
  level2?: string
  level1Options: string[]
  level2Options: string[]
  filterOption: FilterOption
  onConfirm: () => unknown
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:level1', value: string | undefined): void
  (e: 'update:level2', value: string | undefined): void
}>()

async function handleOk() {
  try {
    await props.onConfirm()
  } catch {
    // 父级已给出业务提示；这里仅阻止 rejected promise 冒到控制台。
  }
}

function handleLevel1Change(value: string | undefined) {
  emit('update:level1', value)
  emit('update:level2', undefined)
}
</script>
