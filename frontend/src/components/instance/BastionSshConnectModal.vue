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
          v-if="!connecting && credentials && !credentials.passwordAvailable && credentials.loginMode === 'PASSWORD'"
          type="warning"
          show-icon
          message="任务中没有保存密码，请输入本次连接密码"
          class="bastion-alert"
        />
        <a-alert
          v-if="!connecting && credentials && credentials.loginMode === 'SSH_PUBLIC_KEY'"
          type="info"
          show-icon
          message="该实例使用 SSH 公钥登录，请提供对应私钥"
          class="bastion-alert"
        />

        <a-alert
          v-if="!connecting && form.loginMode === 'PASSWORD'"
          type="info"
          show-icon
          message="密码登录将使用 OCI Port Forwarding 会话"
          class="bastion-alert"
        />

        <a-alert
          v-if="connectionError"
          type="error"
          show-icon
          class="bastion-alert bastion-connection-error"
        >
          <template #message>
            <div class="bastion-error-message">{{ connectionError }}</div>
          </template>
        </a-alert>

        <div
          v-if="showProgress"
          class="bastion-progress"
          role="status"
          aria-live="polite"
        >
          <div class="bastion-progress-heading">正在建立 Bastion SSH 连接</div>
          <div
            v-for="(step, index) in connectionSteps"
            :key="step.title"
            class="bastion-step"
            :class="{
              'is-doing': stepState(index) === 'doing',
              'is-done': stepState(index) === 'done',
              'is-failed': stepState(index) === 'failed',
            }"
          >
            <span class="bastion-step-icon">
              <LoadingOutlined v-if="stepState(index) === 'doing'" />
              <CheckOutlined v-else-if="stepState(index) === 'done'" />
              <CloseCircleOutlined v-else-if="stepState(index) === 'failed'" />
              <span v-else>{{ index + 1 }}</span>
            </span>
            <span class="bastion-step-copy">
              <strong>{{ step.title }}</strong>
              <small>{{ connectionStepDescriptions[index] }}</small>
            </span>
          </div>
        </div>

        <div v-if="!connecting" class="bastion-credentials">
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
              <a-radio-button
                value="profile"
                :disabled="profileLoading || !profilePassword"
              >
                我的密码
              </a-radio-button>
              <a-radio-button value="manual">手动输入</a-radio-button>
            </a-radio-group>
            <div v-if="form.passwordSource === 'profile'" class="bastion-secret-note">
              <KeyOutlined />
              使用系统设置 - 安全设置 - 开机凭据中保存的「我的密码」。
            </div>
            <div
              v-else-if="!profileLoading && !profilePassword"
              class="bastion-secret-note"
            >
              「我的密码」未配置，可到系统设置 - 安全设置 - 开机凭据中保存。
            </div>
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
            <input
              ref="privateKeyInput"
              class="bastion-key-input"
              type="file"
              accept=".key,.pem,.txt"
              @change="handlePrivateKeyChange"
            />
            <div
              class="bastion-key-dropzone"
              :class="{ dragging: privateKeyDragging, loaded: !!privateKeySource }"
              role="button"
              tabindex="0"
              @click="openPrivateKeyPicker"
              @keydown.enter.prevent="openPrivateKeyPicker"
              @keydown.space.prevent="openPrivateKeyPicker"
              @dragenter.prevent="privateKeyDragging = true"
              @dragover.prevent="privateKeyDragging = true"
              @dragleave.prevent="privateKeyDragging = false"
              @drop.prevent="handlePrivateKeyDrop"
            >
              <UploadOutlined />
              <span class="bastion-key-drop-copy">
                <strong v-if="privateKeyDragging">松开以加载私钥</strong>
                <strong v-else-if="privateKeySource">已加载 {{ privateKeySource }}</strong>
                <strong v-else>选择私钥文件</strong>
                <small v-if="privateKeySource">拖入其他文件可替换</small>
                <small v-else>支持将 .key / .pem / .txt 文件从桌面拖入</small>
              </span>
            </div>
            <a-textarea
              v-model:value="form.privateKey"
              :auto-size="{ minRows: 5, maxRows: 10 }"
              autocomplete="off"
              placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"
              @input="handlePrivateKeyInput"
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
      </div>
    </a-spin>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  CheckOutlined,
  CloseCircleOutlined,
  KeyOutlined,
  LoadingOutlined,
  SafetyCertificateOutlined,
  UploadOutlined,
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import type { BastionCredentialAvailability, BastionLoginMode } from '../../api/bastion'
import type { BastionConnectForm } from '../../composables/useBastionSshConnect'
import { getTaskCredential } from '../../api/system'

const props = defineProps<{
  open: boolean
  tenant: any | null
  instance: any | null
  region: string
  credentials: BastionCredentialAvailability | null
  credentialLoading: boolean
  connecting: boolean
  connectionStep: number
  connectionError: string
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
const privateKeyInput = ref<HTMLInputElement | null>(null)
const privateKeyDragging = ref(false)
const profilePassword = ref('')
const profileLoading = ref(false)
let profileLoadGen = 0

const loginModeOptions = [
  { label: '密码', value: 'PASSWORD' as BastionLoginMode },
  { label: 'SSH 私钥', value: 'SSH_PUBLIC_KEY' as BastionLoginMode },
]

const connectionSteps = [
  { title: '查询 Bastion 状态' },
  { title: '创建 / 复用堡垒机' },
  { title: '创建 SSH 会话' },
  { title: '建立 SSH 通道' },
]

const instanceLabel = computed(() =>
  String(props.instance?.displayName || props.instance?.name || props.instance?.instanceId || '实例'),
)

const connectionStepDescriptions = [
  '查询目标实例网络、私有子网与 Cloud Agent 状态',
  '按 OCI 官方要求选择私有子网并创建或复用 Bastion',
  '创建 Managed SSH 或 Port Forwarding 会话并等待 ACTIVE',
  '使用会话密钥建立 Bastion 通道并进入 WebSSH',
]

const showProgress = computed(() => props.connecting || !!props.connectionError)

const canSubmit = computed(() => {
  if (props.credentialLoading || props.connecting || !form.username.trim()) return false
  if (form.loginMode === 'PASSWORD') {
    if (form.passwordSource === 'saved') return !!props.credentials?.passwordAvailable
    if (form.passwordSource === 'profile') return !!profilePassword.value
    return form.password.length > 0
  }
  return looksLikePrivateKey(form.privateKey)
})

watch(() => props.open, (open) => {
  resetForm()
  if (open) {
    applyAvailability(props.credentials)
    void loadProfilePassword()
  }
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
  privateKeyDragging.value = false
  if (privateKeyInput.value) privateKeyInput.value.value = ''
}

function applyAvailability(availability: BastionCredentialAvailability | null) {
  if (!availability) return
  form.loginMode = availability.loginMode
  form.username = availability.username || 'root'
  form.passwordSource = defaultPasswordSource(availability.passwordAvailable)
}

function defaultPasswordSource(taskPasswordAvailable: boolean | undefined) {
  if (taskPasswordAvailable) return 'saved' as const
  if (profilePassword.value) return 'profile' as const
  return 'manual' as const
}

async function loadProfilePassword() {
  const gen = ++profileLoadGen
  profileLoading.value = true
  try {
    const res = await getTaskCredential()
    if (gen !== profileLoadGen) return
    profilePassword.value = String(res.data?.rootPassword || '').trim()
  } catch {
    if (gen !== profileLoadGen) return
    profilePassword.value = ''
  } finally {
    if (gen === profileLoadGen) profileLoading.value = false
  }
  // 任务密码不可用且用户尚未手动输入时，自动切到刚加载好的「我的密码」。
  if (props.open && profilePassword.value && form.loginMode === 'PASSWORD'
      && form.passwordSource === 'manual' && !form.password
      && !props.credentials?.passwordAvailable) {
    form.passwordSource = 'profile'
  }
}

function looksLikePrivateKey(value: string) {
  const normalized = value.trim()
  return normalized.length > 80 && normalized.includes('PRIVATE KEY')
}

function openPrivateKeyPicker() {
  if (!props.connecting) privateKeyInput.value?.click()
}

function handlePrivateKeyChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) void loadPrivateKey(file)
}

function handlePrivateKeyDrop(event: DragEvent) {
  privateKeyDragging.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file) void loadPrivateKey(file)
}

function handlePrivateKeyInput() {
  if (privateKeySource.value) privateKeySource.value = ''
}

async function loadPrivateKey(file: File) {
  const filename = file.name.toLowerCase()
  if (!/\.(key|pem|txt)$/.test(filename)) {
    message.error('请选择 .key、.pem 或 .txt 私钥文件')
    return
  }
  if (file.size > 512 * 1024) {
    message.error('私钥文件不能超过 512 KB')
    return
  }
  try {
    const text = (await file.text()).trim()
    if (!looksLikePrivateKey(text)) {
      message.error('文件内容不是可识别的 SSH 私钥')
      return
    }
    form.privateKey = text
    privateKeySource.value = file.name
  } catch {
    privateKeySource.value = ''
    message.error('读取私钥文件失败')
  }
}

type StepState = 'pending' | 'doing' | 'done' | 'failed'

function stepState(index: number): StepState {
  const current = Math.max(0, Math.min(4, props.connectionStep))
  if (props.connectionError && index === Math.min(current, connectionSteps.length - 1)) {
    return 'failed'
  }
  if (index < current) return 'done'
  if (props.connecting && index === current) return 'doing'
  return 'pending'
}

function submit() {
  if (!canSubmit.value) return
  emit('connect', {
    loginMode: form.loginMode,
    username: form.username.trim(),
    passwordSource: form.passwordSource,
    password: form.passwordSource === 'profile' ? profilePassword.value : form.password,
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
.bastion-error-message {
  white-space: pre-line;
}
.bastion-progress {
  background: var(--input-bg, rgba(15, 23, 42, 0.6));
  border: 1px solid var(--border, rgba(255, 255, 255, 0.08));
  border-radius: 10px;
  padding: 12px 14px;
}
.bastion-progress-heading {
  color: var(--text-main, #f1f5f9);
  font-size: 13px;
  font-weight: 650;
  margin-bottom: 8px;
}
.bastion-step {
  align-items: flex-start;
  display: flex;
  gap: 10px;
  min-height: 42px;
  position: relative;
}
.bastion-step:not(:last-child)::after {
  background: var(--border, rgba(255, 255, 255, 0.08));
  content: '';
  left: 10px;
  position: absolute;
  top: 24px;
  bottom: -4px;
  width: 1px;
}
.bastion-step-icon {
  align-items: center;
  background: var(--input-bg, rgba(15, 23, 42, 0.6));
  border: 1px solid var(--border, rgba(255, 255, 255, 0.12));
  border-radius: 50%;
  color: var(--text-sub, #94a3b8);
  display: inline-flex;
  flex: 0 0 20px;
  font-size: 11px;
  height: 20px;
  justify-content: center;
  position: relative;
  width: 20px;
  z-index: 1;
}
.bastion-step-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  padding-bottom: 7px;
}
.bastion-step-copy strong {
  color: var(--text-sub, #94a3b8);
  font-size: 12.5px;
  font-weight: 600;
}
.bastion-step-copy small {
  color: var(--text-sub, #94a3b8);
  font-size: 11.5px;
  line-height: 1.4;
}
.bastion-step.is-doing .bastion-step-icon {
  border-color: var(--primary, #818cf8);
  color: var(--primary, #818cf8);
}
.bastion-step.is-doing .bastion-step-copy strong,
.bastion-step.is-doing .bastion-step-copy small {
  color: var(--text-main, #f1f5f9);
}
.bastion-step.is-done .bastion-step-icon {
  border-color: var(--success, #10b981);
  color: var(--success-text, #34d399);
}
.bastion-step.is-done .bastion-step-copy strong {
  color: var(--text-main, #f1f5f9);
}
.bastion-step.is-failed .bastion-step-icon {
  border-color: var(--danger, #ef4444);
  color: var(--danger-text, #f87171);
}
.bastion-step.is-failed .bastion-step-copy strong,
.bastion-step.is-failed .bastion-step-copy small {
  color: var(--danger-text, #f87171);
}
.bastion-credentials {
  display: flex;
  flex-direction: column;
  gap: 16px;
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
.bastion-key-input {
  display: none;
}
.bastion-key-dropzone {
  align-items: center;
  background: var(--input-bg, rgba(15, 23, 42, 0.6));
  border: 1px solid var(--border, rgba(255, 255, 255, 0.08));
  border-radius: 8px;
  color: var(--text-main, #f1f5f9);
  cursor: pointer;
  display: flex;
  gap: 9px;
  min-height: 40px;
  padding: 8px 12px;
  transition: border-color 0.15s ease, background 0.15s ease;
  width: 100%;
}
.bastion-key-dropzone:hover,
.bastion-key-dropzone.dragging {
  background: var(--primary-light, rgba(129, 140, 248, 0.15));
  border-color: var(--primary, #818cf8);
}
.bastion-key-dropzone.dragging {
  color: var(--primary, #818cf8);
}
.bastion-key-dropzone.loaded {
  background: var(--success-bg, rgba(16, 185, 129, 0.15));
  border-color: var(--success, #10b981);
}
.bastion-key-drop-copy {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.bastion-key-drop-copy strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bastion-key-drop-copy small {
  color: var(--text-sub, #94a3b8);
  font-size: 11px;
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
