<template>
  <a-modal
    :mask-closable="false"
    :keyboard="false"
    :open="open"
    title="安全验证 — 终止实例"
    :width="400"
    :z-index="INSTANCE_SAFETY_MODAL_Z_INDEX"
    :wrap-class-name="INSTANCE_SAFETY_MODAL_WRAP_CLASS"
    :confirm-loading="loading"
    ok-text="确认终止"
    ok-type="primary"
    :ok-button-props="{ danger: true }"
    @update:open="emit('update:open', $event)"
    @ok="handleOk"
  >
    <a-alert type="warning" show-icon style="margin-bottom: 16px">
      <template #message>终止实例不可逆，验证码已发送至 Telegram</template>
    </a-alert>
    <a-input
      :value="code"
      placeholder="请输入6位验证码"
      size="large"
      :maxlength="6"
      allow-clear
      @update:value="emit('update:code', $event)"
    />
    <div class="terminate-verify-option">
      <a-checkbox
        :checked="deleteBootVolume"
        @update:checked="emit('update:deleteBootVolume', $event)"
      >
        同时删除引导卷（不可恢复）
      </a-checkbox>
    </div>
    <div class="terminate-verify-footer">
      <span>验证码有效期 5 分钟</span>
      <a-button type="link" size="small" :loading="sending" @click="onResend">重新发送</a-button>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { INSTANCE_SAFETY_MODAL_WRAP_CLASS, INSTANCE_SAFETY_MODAL_Z_INDEX } from '../../utils/overlayZIndex'

const props = defineProps<{
  open: boolean
  code: string
  deleteBootVolume: boolean
  loading: boolean
  sending: boolean
  onConfirm: () => void | Promise<unknown>
  onResend: () => void | Promise<unknown>
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:code', value: string): void
  (e: 'update:deleteBootVolume', value: boolean): void
}>()

function handleOk() {
  return props.onConfirm()
}
</script>

<style scoped>
.terminate-verify-option {
  margin-top: 12px;
}

.terminate-verify-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}

.terminate-verify-footer span {
  color: var(--text-sub);
  font-size: 12px;
}
</style>
