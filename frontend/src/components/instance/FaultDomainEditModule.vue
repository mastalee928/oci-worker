<template>
  <a-modal
    :keyboard="false"
    :open="verifyVisible"
    title="安全验证 — 修改故障域"
    :width="isMobile ? '100%' : 480"
    :mask-closable="false"
    destroy-on-close
    ok-text="验证"
    cancel-text="取消"
    :confirm-loading="verifying"
    @ok="verifyAndOpenEditor"
    @cancel="finish(false)"
  >
    <div class="verify-hint">验证码已发送至 Telegram，请验证后继续修改故障域。</div>
    <a-input
      v-model:value="verifyCode"
      placeholder="请输入 6 位验证码"
      :maxlength="6"
      size="large"
      @press-enter="verifyAndOpenEditor"
    />
    <div class="resend-row">
      <a-button type="link" size="small" :loading="sending" @click="sendCode(true)">重新发送</a-button>
    </div>
  </a-modal>

  <a-modal
    :keyboard="false"
    :open="editorVisible"
    title="修改故障域"
    :width="isMobile ? '100%' : 480"
    :mask-closable="false"
    destroy-on-close
    ok-text="确认修改"
    cancel-text="取消"
    :confirm-loading="saving"
    @ok="saveFaultDomain"
    @cancel="finish(true)"
  >
    <a-form layout="vertical">
      <a-form-item label="实例">
        <a-input :value="instance?.name || instance?.instanceId" disabled />
      </a-form-item>
      <a-form-item label="可用性域">
        <a-input :value="formatAvailabilityDomain(instance?.availabilityDomain)" disabled />
      </a-form-item>
      <a-form-item label="故障域" required>
        <a-select v-model:value="selectedFaultDomain" style="width: 100%">
          <a-select-option v-for="item in faultDomainOptions" :key="item.value" :value="item.value">
            {{ item.label }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <div class="fault-domain-hint">故障域只能在当前可用性域内切换，实际操作限制以 OCI 返回结果为准。</div>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { sendVerifyCode } from '../../api/system'
import { revokeFaultDomainUpdate, unlockFaultDomainUpdate, updateFaultDomain } from '../../api/instance'

defineOptions({ name: 'FaultDomainEditModule' })

const props = defineProps<{
  tenant: any
  instance: any
  region?: string
  isMobile: boolean
}>()

const emit = defineEmits<{
  (e: 'hide-detail'): void
  (e: 'finish', reopenDetail: boolean): void
  (e: 'updated', result: Record<string, any>): void
}>()

const verifyVisible = ref(false)
const editorVisible = ref(false)
const verifyCode = ref('')
const accessToken = ref('')
const selectedFaultDomain = ref('FAULT-DOMAIN-1')
const sending = ref(false)
const verifying = ref(false)
const saving = ref(false)
let finished = false

const faultDomainOptions = [1, 2, 3].map(index => ({
  label: `FD-${index}`,
  value: `FAULT-DOMAIN-${index}`,
}))

function targetKey() {
  return `${String(props.tenant?.id || '').trim()}|${String(props.instance?.instanceId || '').trim()}`
}

function targetText() {
  return `${props.instance?.name || props.instance?.instanceId || '实例'} / ${formatFaultDomain(props.instance?.faultDomain)}`
}

function normalizeFaultDomain(value: unknown) {
  const text = typeof value === 'string' ? value.trim().toUpperCase() : ''
  const match = text.match(/(?:FAULT-DOMAIN|FD)-(\d+)$/)
  return match ? `FAULT-DOMAIN-${match[1]}` : 'FAULT-DOMAIN-1'
}

function formatAvailabilityDomain(value: unknown) {
  const text = typeof value === 'string' ? value.trim() : ''
  const match = text.match(/(?:^|-)AD-(\d+)$/i)
  return match ? `AD-${match[1]}` : text || '—'
}

function formatFaultDomain(value: unknown) {
  const text = typeof value === 'string' ? value.trim().toUpperCase() : ''
  const match = text.match(/(?:FAULT-DOMAIN|FD)-(\d+)$/)
  return match ? `FD-${match[1]}` : '—'
}

async function sendCode(resend = false) {
  sending.value = true
  try {
    await sendVerifyCode('updateFaultDomain', {
      contextKey: targetKey(),
      contextText: targetText(),
    })
    message.success(resend ? '验证码已重新发送' : '验证码已发送至 Telegram')
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
    if (!resend) finish(false)
  } finally {
    sending.value = false
  }
}

async function verifyAndOpenEditor() {
  if (!/^\d{6}$/.test(verifyCode.value)) {
    message.warning('请输入 6 位 TG 验证码')
    return
  }
  verifying.value = true
  try {
    const response = await unlockFaultDomainUpdate({
      id: String(props.tenant?.id || ''),
      instanceId: String(props.instance?.instanceId || ''),
      verifyCode: verifyCode.value,
    })
    accessToken.value = response.data?.accessToken || ''
    if (!accessToken.value) throw new Error('未获得故障域修改授权')
    selectedFaultDomain.value = normalizeFaultDomain(props.instance?.faultDomain)
    verifyVisible.value = false
    emit('hide-detail')
    editorVisible.value = true
  } catch (e: any) {
    accessToken.value = ''
    message.error(e?.message || '安全验证失败')
  } finally {
    verifying.value = false
  }
}

async function saveFaultDomain() {
  if (selectedFaultDomain.value === normalizeFaultDomain(props.instance?.faultDomain)) {
    message.info('请选择不同的故障域')
    return
  }
  if (!accessToken.value) {
    message.warning('操作授权已失效，请重新完成 TG 验证')
    finish(true)
    return
  }
  saving.value = true
  try {
    const response = await updateFaultDomain({
      id: String(props.tenant?.id || ''),
      instanceId: String(props.instance?.instanceId || ''),
      faultDomain: selectedFaultDomain.value,
      accessToken: accessToken.value,
      ...(props.region ? { region: props.region } : {}),
    })
    message.success('故障域已更新')
    emit('updated', response.data || { faultDomain: selectedFaultDomain.value })
    accessToken.value = ''
    await finish(true)
  } catch (e: any) {
    const text = e?.message || '修改故障域失败'
    if (String(text).includes('授权已失效')) accessToken.value = ''
    message.error(text)
  } finally {
    saving.value = false
  }
}

async function finish(reopenDetail: boolean) {
  if (finished) return
  finished = true
  const unusedToken = accessToken.value
  verifyVisible.value = false
  editorVisible.value = false
  accessToken.value = ''
  if (unusedToken) {
    try {
      await revokeFaultDomainUpdate({
        id: String(props.tenant?.id || ''),
        instanceId: String(props.instance?.instanceId || ''),
        accessToken: unusedToken,
      })
    } catch {
      // 授权仍会在服务端按较短 TTL 自动失效，不阻塞界面关闭。
    }
  }
  emit('finish', reopenDetail)
}

onMounted(() => {
  verifyVisible.value = true
  void sendCode(false)
})
</script>

<style scoped>
.verify-hint,
.fault-domain-hint {
  margin-bottom: 16px;
  color: var(--text-sub);
  font-size: 12px;
}

.resend-row {
  margin-top: 12px;
  text-align: right;
}
</style>
