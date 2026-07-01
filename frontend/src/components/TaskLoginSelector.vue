<template>
  <a-form-item :label="isPublicKeyMode ? '登录方式' : label" class="task-root-password-item task-login-selector">
    <a-input
      v-if="isPublicKeyMode"
      class="task-login-public-key-input"
      value="使用 SSH 公钥登录"
      readonly
    />
    <a-input-password
      v-else
      :value="rootPassword"
      :placeholder="placeholder"
      autocomplete="new-password"
      @update:value="onPasswordInput"
    />
    <div class="task-login-actions" aria-label="登录凭据快捷操作">
      <button type="button" class="task-login-action" @click="useRandomPassword">随机生成</button>
      <button
        type="button"
        class="task-login-action"
        :class="{ 'task-login-action--active': isSavedPasswordMode }"
        @click="useSavedPassword"
      >
        我的密码
      </button>
      <button
        type="button"
        class="task-login-action"
        :class="{ 'task-login-action--active': isPublicKeyMode }"
        @click="useSavedPublicKey"
      >
        我的公钥
      </button>
    </div>
  </a-form-item>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  rootPassword?: string
  loginMode?: string
  sshPublicKey?: string
  savedRootPassword?: string
  savedSshPublicKey?: string
  placeholder?: string
  label?: string
}>(), {
  rootPassword: '',
  loginMode: 'PASSWORD',
  sshPublicKey: '',
  savedRootPassword: '',
  savedSshPublicKey: '',
  placeholder: '留空=随机生成',
  label: 'Root 密码',
})

const emit = defineEmits<{
  'update:rootPassword': [value: string]
  'update:loginMode': [value: string]
  'update:sshPublicKey': [value: string]
  missing: [type: 'password' | 'publicKey']
}>()

const isPublicKeyMode = computed(() => props.loginMode === 'SSH_PUBLIC_KEY')
const isSavedPasswordMode = computed(() =>
  props.loginMode === 'PASSWORD' && !!props.savedRootPassword && props.rootPassword === props.savedRootPassword,
)

function randomPassword() {
  const chars = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%'
  let pwd = ''
  for (let i = 0; i < 16; i += 1) pwd += chars[Math.floor(Math.random() * chars.length)]
  return pwd
}

function setPasswordMode(value: string) {
  emit('update:loginMode', 'PASSWORD')
  emit('update:sshPublicKey', '')
  emit('update:rootPassword', value)
}

function onPasswordInput(value: string) {
  setPasswordMode(value)
}

function useRandomPassword() {
  setPasswordMode(randomPassword())
}

function useSavedPassword() {
  if (!props.savedRootPassword) {
    emit('missing', 'password')
    return
  }
  setPasswordMode(props.savedRootPassword)
}

function useSavedPublicKey() {
  if (!props.savedSshPublicKey) {
    emit('missing', 'publicKey')
    return
  }
  emit('update:loginMode', 'SSH_PUBLIC_KEY')
  emit('update:rootPassword', '')
  emit('update:sshPublicKey', props.savedSshPublicKey)
}
</script>

<style scoped>
.task-login-selector {
  --task-login-action: #9ca3ff;
  --task-login-action-hover: #c4c8ff;
  --task-login-success: #75e0b4;
  --task-login-input: #0f172a;
  --task-login-key-border: rgba(117, 224, 180, 0.32);
  --task-login-key-bg: linear-gradient(90deg, rgba(117, 224, 180, 0.08), rgba(124, 114, 255, 0.06)), var(--task-login-input);
}

:global([data-theme="light"]) .task-login-selector {
  --task-login-action: #6366f1;
  --task-login-action-hover: #4f46e5;
  --task-login-success: #047857;
  --task-login-input: #ffffff;
  --task-login-key-border: rgba(5, 150, 105, 0.32);
  --task-login-key-bg: linear-gradient(90deg, rgba(5, 150, 105, 0.08), rgba(99, 102, 241, 0.05)), var(--task-login-input);
}

.task-login-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 27px;
  margin-top: 8px;
}

.task-login-action {
  appearance: none;
  height: 24px;
  padding: 0 2px;
  border: 0;
  background: transparent;
  color: var(--task-login-action);
  cursor: pointer;
  font-family: inherit;
  font-size: 13px;
  font-weight: 400;
  line-height: normal;
  min-width: 0;
}

.task-login-action:hover {
  color: var(--task-login-action-hover);
}

.task-login-action--active,
.task-login-action--active:hover {
  color: var(--task-login-success);
}

.task-login-public-key-input,
:deep(.task-login-public-key-input.ant-input) {
  border-color: var(--task-login-key-border);
  background: var(--task-login-key-bg) !important;
  color: var(--task-login-success) !important;
  -webkit-text-fill-color: var(--task-login-success);
  cursor: default;
  font-weight: 400;
}

.task-login-selector :deep(.ant-form-item-control-input) {
  min-height: 0;
}

@media (max-width: 768px) {
  .task-login-actions {
    gap: 8px;
  }

  .task-login-action {
    padding: 0 2px;
  }
}
</style>
