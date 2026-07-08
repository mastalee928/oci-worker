<template>
  <a-modal
    :mask-closable="false"
    :keyboard="false"
    :open="open"
    title="重命名分组"
    :confirm-loading="loading"
    centered
    @update:open="emit('update:open', $event)"
    @ok="onConfirm"
  >
    <a-input :value="name" placeholder="输入新分组名" @update:value="emit('update:name', $event)" @press-enter="onConfirm" />
  </a-modal>
</template>

<script setup lang="ts">
defineOptions({ name: 'TenantRenameGroupModal' })

defineProps<{
  open: boolean
  loading: boolean
  name: string
  onConfirm: () => unknown
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:name', value: string): void
}>()
</script>
