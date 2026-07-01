<template>
  <a-form-item :label="isPublicKeyMode ? '登录方式' : label" class="task-root-password-item task-login-selector">
    <a-input
      v-if="isPublicKeyMode"
      class="task-login-public-key-input"
      value="使用 SSH 公钥登录"
      disabled
    />
    <a-input-password
      v-else
      :value="rootPassword"
      :placeholder="placeholder"
      autocomplete="new-password"
      @update:value="onPasswordInput"
    />
    <div class="task-login-actions" aria-label="登录凭据快捷操作">
      <a-button type="text" size="small" class="task-login-action" @click="useRandomPassword">随机生成</a-button>
      <a-button
        type="text"
        size="small"
        class="task-login-action"
        :class="{ 'task-login-action--active': isSavedPasswordMode }"
        @click="useSavedPassword"
      >
        我的密码
      </a-button>
      <a-button
        type="text"
        size="small"
        class="task-login-action"
        :class="{ 'task-login-action--active': isPublicKeyMode }"
        @click="useSavedPublicKey"
      >
        我的公钥
      </a-button>
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
  --task-login-accent: #34d399;
  --task-login-accent-bg: rgba(16, 185, 129, 0.14);
  --task-login-accent-soft: rgba(16, 185, 129, 0.08);
  --task-login-accent-border: rgba(16, 185, 129, 0.45);
}

:global([data-theme="light"]) .task-login-selector {
  --task-login-accent: #047857;
  --task-login-accent-bg: rgba(5, 150, 105, 0.12);
  --task-login-accent-soft: rgba(5, 150, 105, 0.08);
  --task-login-accent-border: rgba(5, 150, 105, 0.38);
}

.task-login-actions {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 6px;
  min-height: 26px;
  margin-top: 6px;
  overflow-x: auto;
  white-space: nowrap;
  scrollbar-width: none;
}

.task-login-actions::-webkit-scrollbar {
  display: none;
}

.task-login-action {
  height: 24px;
  padding: 0 8px;
  border-radius: 6px;
  color: var(--primary);
  font-size: 12px;
}

.task-login-action:hover {
  background: rgba(99, 102, 241, 0.1);
}

.task-login-action--active,
.task-login-action--active:hover {
  background: var(--task-login-accent-bg);
  color: var(--task-login-accent);
  font-weight: 600;
}

.task-login-public-key-input,
:deep(.task-login-public-key-input.ant-input-disabled) {
  border-color: var(--task-login-accent-border);
  background: var(--task-login-accent-soft) !important;
  color: var(--task-login-accent) !important;
  -webkit-text-fill-color: var(--task-login-accent);
  font-weight: 600;
}

.task-login-selector :deep(.ant-form-item-control-input) {
  min-height: 0;
}

@media (max-width: 768px) {
  .task-login-actions {
    gap: 4px;
  }

  .task-login-action {
    padding: 0 6px;
  }
}
</style>
