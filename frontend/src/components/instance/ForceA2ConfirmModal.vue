<template>
  <a-modal
    :keyboard="false"
    :open="open"
    title="A2 强改 A1"
    ok-text="确认执行"
    cancel-text="取消"
    :confirm-loading="loading"
    :ok-button-props="{ disabled: !allYes, danger: true }"
    :mask-closable="false"
    :width="isMobile ? '100%' : 480"
    @update:open="emit('update:open', $event)"
    @ok="handleOk"
    @cancel="emit('cancel')"
  >
    <a-alert type="error" show-icon style="margin-bottom: 16px" message="强行 A2→A1 存在账号封禁等风险，请谨慎操作。" />
    <div class="force-a2-q">
      <div class="force-a2-q-label">该账户是否在试用期内？</div>
      <a-radio-group
        :value="trial"
        :options="yesNoOptions"
        @update:value="emit('update:trial', $event)"
      />
    </div>
    <div class="force-a2-q">
      <div class="force-a2-q-label">请确认目前该形状为 VM.Standard.A2.Flex？</div>
      <a-radio-group
        :value="a2Shape"
        :options="yesNoOptions"
        @update:value="emit('update:a2Shape', $event)"
      />
    </div>
    <div class="force-a2-q">
      <div class="force-a2-q-label">是否知悉强行转 A1 有被封号的风险？</div>
      <a-radio-group
        :value="risk"
        :options="yesNoOptions"
        @update:value="emit('update:risk', $event)"
      />
    </div>
  </a-modal>
</template>

<script setup lang="ts">
const props = defineProps<{
  open: boolean
  loading: boolean
  allYes: boolean
  isMobile: boolean
  onConfirm: () => void | Promise<unknown>
  trial?: boolean
  a2Shape?: boolean
  risk?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:trial', value: boolean): void
  (e: 'update:a2Shape', value: boolean): void
  (e: 'update:risk', value: boolean): void
  (e: 'cancel'): void
}>()

const yesNoOptions = [
  { label: '是', value: true },
  { label: '否', value: false },
]

function handleOk() {
  return props.onConfirm()
}
</script>

<style scoped>
.force-a2-q {
  margin-bottom: 16px;
}

.force-a2-q:last-child {
  margin-bottom: 0;
}

.force-a2-q-label {
  margin-bottom: 8px;
  font-weight: 500;
  color: var(--text-main);
}
</style>
