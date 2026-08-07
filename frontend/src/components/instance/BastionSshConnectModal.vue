<template>
  <a-modal
    :open="open"
    :width="620"
    :closable="!connecting"
    :mask-closable="!connecting"
    :keyboard="!connecting"
    :destroy-on-close="false"
    :confirm-loading="connecting"
    ok-text="开始连接"
    cancel-text="取消"
    :ok-button-props="{ disabled: !canSubmit }"
    :cancel-button-props="{ disabled: connecting }"
    @update:open="updateOpen"
    @ok="submit"
    @cancel="cancel"
  >
    <template #title>
      <span class="bastion-modal-title">
        <SafetyCertificateOutlined />
        <span>OCI Bastion SSH</span>
      </span>
    </template>

    <a-spin :spinning="credentialLoading">
      <div class="bastion-modal-body">
        <div class="bastion-target-line">
          <div class="bastion-target-name">{{ instanceLabel }}</div>
          <div class="bastion-target-meta">
            {{ region || '未指定区域' }} · {{ instance?.privateIp || '目标私网地址由 OCI 解析' }}
          </div>
        </div>

        <a-alert
          v-if="credentials && !credentials.passwordAvailable && credentials.loginMode === 'PASSWORD'"
          type="warning"
          show-icon
          message="任务中没有保存密码，请输入本次连接密码"
          class="bastion-alert"
        />
        <a-alert
          v-if="credentials && credentials.loginMode === 'SSH_PUBLIC_KEY'"
          type="info"
          show-icon
          message="该实例使用 SSH 公钥登录，请提供对应私钥"
          class="bastion-alert"
        />

        <a-alert
          v-if="form.loginMode === 'PASSWORD'"
          type="info"
          show-icon
          message="密码登录将使用 OCI Port Forwarding 会话"
          class="bastion-alert"
        />

        <a-steps
          v-if="connecting"
          class="bastion-progress"
          size="small"
          progress-dot
          :current="1"
          :items="connectionSteps"
        />

        <div class="bastion-field">
          <label>登录方式</label>
          <a-segmented
            v-model:value="form.loginMode"
            :options="loginModeOptions"
            block
          />
        </div>

        <div class="bastion-field">
          <label>用户名</label>
          <a-input v-model:value="form.username" autocomplete="username" placeholder="root" />
        </div>

        <template v-if="form.loginMode === 'PASSWORD'">
          <div class="bastion-field">
            <label>密码来源</label>
            <a-radio-group v-model:value="form.passwordSource" button-style="solid">
              <a-radio-button value="saved" :disabled="!credentials?.passwordAvailable">
                使用任务密码
              </a-radio-button>
              <a-radio-button value="manual">手动输入</a-radio-button>
            </a-radio-group>
          </div>
          <div v-if="form.passwordSource === 'manual'" class="bastion-field">
            <label>本次密码</label>
            <a-input-password
              v-model:value="form.password"
              autocomplete="current-password"
              placeholder="请输入实例 SSH 密码"
            />
          </div>
        </template>

        <template v-else>
          <div class="bastion-field">
            <label>私钥</label>
            <div class="bastion-key-actions">
              <a-upload
                accept=".key,.pem,.txt"
                :show-upload-list="false"
                :before-upload="readPrivateKey"
              >
                <a-button>
                  <template #icon><UploadOutlined /></template>
                  选择私钥文件
                </a-button>
              </a-upload>
              <span v-if="privateKeySource" class="bastion-key-source">{{ privateKeySource }}</span>
            </div>
            <a-textarea
              v-model:value="form.privateKey"
              :auto-size="{ minRows: 5, maxRows: 10 }"
              autocomplete="off"
              placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"
            />
            <div class="bastion-secret-note">
              <KeyOutlined />
              私钥只用于本次会话，不会写入任务或日志。
            </div>
          </div>
          <div class="bastion-field">
            <label>私钥口令（可选）</label>
            <a-input-password v-model:value="form.passphrase" autocomplete="new-password" />
          </div>
        </template>

        <div v-if="credentials === null && !credentialLoading" class="bastion-empty">
          无法读取任务凭据，请填写登录信息后重试。
        </div>
      </div>
    </a-spin>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { KeyOutlined, SafetyCertificateOutlined, UploadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import type { BastionCredentialAvailability, BastionLoginMode } from '../../api/bastion'
import type { BastionConnectForm } from '../../composables/useBastionSshConnect'

const props = defineProps<{
  open: boolean
  tenant: any | null
  instance: any | null
  region: string
  credentials: BastionCredentialAvailability | null
  credentialLoading: boolean
  connecting: boolean
}>()

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'connect', form: BastionConnectForm): void
}>()

const form = reactive<BastionConnectForm>({
  loginMode: 'PASSWORD',
  username: 'root',
  passwordSource: 'manual',
  password: '',
  privateKey: '',
  passphrase: '',
})
const privateKeySource = ref('')

const loginModeOptions = [
  { label: '密码', value: 'PASSWORD' as BastionLoginMode },
  { label: 'SSH 私钥', value: 'SSH_PUBLIC_KEY' as BastionLoginMode },
]

const connectionSteps = [
  { title: '校验目标' },
  { title: '准备 Bastion' },
  { title: '创建 SSH 会话' },
  { title: '打开终端' },
]

const instanceLabel = computed(() =>
  String(props.instance?.displayName || props.instance?.name || props.instance?.instanceId || '实例'),
)

const canSubmit = computed(() => {
  if (props.credentialLoading || props.connecting || !form.username.trim()) return false
  if (form.loginMode === 'PASSWORD') {
    return form.passwordSource === 'saved'
      ? !!props.credentials?.passwordAvailable
      : form.password.length > 0
  }
  return form.privateKey.trim().length > 0
})

watch(() => props.open, (open) => {
  resetForm()
  if (open) applyAvailability(props.credentials)
}, { immediate: true })

watch(() => props.credentials, (availability) => {
  if (props.open && availability) applyAvailability(availability)
})

function resetForm() {
  form.loginMode = 'PASSWORD'
  form.username = 'root'
  form.passwordSource = 'manual'
  form.password = ''
  form.privateKey = ''
  form.passphrase = ''
  privateKeySource.value = ''
}

function applyAvailability(availability: BastionCredentialAvailability | null) {
  if (!availability) return
  form.loginMode = availability.loginMode
  form.username = availability.username || 'root'
  form.passwordSource = availability.passwordAvailable ? 'saved' : 'manual'
}

function readPrivateKey(file: File) {
  if (file.size > 512 * 1024) {
    message.error('私钥文件不能超过 512 KB')
    return false
  }
  const reader = new FileReader()
  reader.onload = () => {
    form.privateKey = String(reader.result || '')
    privateKeySource.value = file.name
  }
  reader.onerror = () => {
    privateKeySource.value = ''
  }
  reader.readAsText(file)
  return false
}

function submit() {
  if (!canSubmit.value) return
  emit('connect', {
    loginMode: form.loginMode,
    username: form.username.trim(),
    passwordSource: form.passwordSource,
    password: form.password,
    privateKey: form.privateKey,
    passphrase: form.passphrase,
  })
}

function cancel() {
  if (!props.connecting) emit('update:open', false)
}

function updateOpen(value: boolean) {
  if (!value && props.connecting) return
  emit('update:open', value)
}
</script>

<style scoped>
.bastion-modal-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.bastion-modal-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-top: 4px;
}
.bastion-target-line {
  border-bottom: 1px solid var(--border, #e5e7eb);
  padding-bottom: 12px;
}
.bastion-target-name {
  color: var(--text-main, #111827);
  font-size: 16px;
  font-weight: 650;
}
.bastion-target-meta {
  color: var(--text-sub, #6b7280);
  font-size: 12px;
  margin-top: 4px;
  word-break: break-word;
}
.bastion-alert { margin: 0; }
.bastion-progress {
  padding: 4px 0 2px;
}
.bastion-field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.bastion-field > label {
  color: var(--text-main, #111827);
  font-size: 13px;
  font-weight: 600;
}
.bastion-key-actions {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.bastion-key-source {
  color: var(--text-sub, #6b7280);
  font-size: 12px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bastion-secret-note {
  align-items: center;
  color: var(--text-sub, #6b7280);
  display: flex;
  font-size: 12px;
  gap: 6px;
}
.bastion-empty {
  color: var(--text-sub, #6b7280);
  font-size: 12px;
}
@media (max-width: 640px) {
  .bastion-modal-body { gap: 13px; }
}
</style>
