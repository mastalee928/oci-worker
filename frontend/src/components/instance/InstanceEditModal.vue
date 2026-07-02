<template>
  <a-modal
    :keyboard="false"
    :open="open"
    title="修改实例"
    :confirm-loading="loading"
    :mask-closable="false"
    :width="isMobile ? '100%' : 480"
    @update:open="emit('update:open', $event)"
    @ok="handleOk"
  >
    <a-form v-if="instance" layout="vertical">
      <a-form-item label="实例名称">
        <a-input
          :value="displayName"
          placeholder="输入新名称"
          @update:value="emit('update:displayName', $event)"
        />
      </a-form-item>
      <div class="instance-edit-hint">调整 Shape / OCPU / 内存请使用详情抽屉中的「形状编辑」页签。</div>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
const props = defineProps<{
  open: boolean
  loading: boolean
  isMobile: boolean
  instance: any
  displayName: string
  onConfirm: () => void | Promise<unknown>
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:displayName', value: string): void
}>()

function handleOk() {
  return props.onConfirm()
}
</script>

<style scoped>
.instance-edit-hint {
  color: #999;
  font-size: 12px;
}
</style>
