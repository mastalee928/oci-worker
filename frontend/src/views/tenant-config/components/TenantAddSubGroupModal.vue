<template>
  <a-modal
    :mask-closable="false"
    :keyboard="false"
    :open="open"
    title="添加子分组"
    centered
    @update:open="emit('update:open', $event)"
    @ok="onConfirm"
  >
    <p style="color: var(--text-sub); margin-bottom: 8px">父分组: <a-tag color="blue">{{ parent }}</a-tag></p>
    <a-input :value="name" placeholder="输入子分组名称" @update:value="emit('update:name', $event)" @press-enter="onConfirm" />
  </a-modal>
</template>

<script setup lang="ts">
defineOptions({ name: 'TenantAddSubGroupModal' })

defineProps<{
  open: boolean
  parent: string
  name: string
  onConfirm: () => unknown
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:name', value: string): void
}>()
</script>
