<template>
  <a-form-item :label="isPublicKeyMode ? '登录方式' : label" class="task-root-password-item task-login-selector">
    <a-input
      v-if="isPublicKeyMode"
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
    <div class="task-login-actions">
      <a-button type="link" size="small" class="task-root-password-gen" @click="useRandomPassword">随机生成</a-button>
      <a-button type="link" size="small" class="task-root-password-gen" @click="useSavedPassword">我的密码</a-button>
      <a-button type="link" size="small" class="task-root-password-gen" @click="useSavedPublicKey">我的公钥</a-button>
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
.task-login-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 24px;
  margin-top: 4px;
}

.task-login-actions :deep(.ant-btn-link) {
  height: 22px;
  padding: 0;
}
</style>
