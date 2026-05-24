<template>
  <div class="cf-zone-overview">
    <a-card size="small" title="区域详情" :loading="loading">
      <template v-if="!isMobile" #extra>
        <a-space wrap>
          <span class="cf-label-inline">暂停解析</span>
          <a-switch
            :checked="!!detail?.paused"
            :loading="pausedLoading"
            :disabled="!props.zoneId"
            @change="onPauseSwitch"
          />
          <a-button danger size="small" :loading="deleteLoading" :disabled="!zoneId" @click="openDeleteModal">
            删除区域
          </a-button>
          <a-button size="small" :loading="loading" :disabled="!zoneId" @click="load">刷新</a-button>
        </a-space>
      </template>
      <div v-if="isMobile && detail" class="cf-overview-actions">
        <div class="cf-overview-action-row">
          <span class="cf-label-inline">暂停解析</span>
          <a-switch
            :checked="!!detail.paused"
            :loading="pausedLoading"
            :disabled="!props.zoneId"
            @change="onPauseSwitch"
          />
        </div>
        <a-space wrap>
          <a-button danger size="small" :loading="deleteLoading" :disabled="!zoneId" @click="openDeleteModal">
            删除区域
          </a-button>
          <a-button size="small" :loading="loading" :disabled="!zoneId" @click="load">刷新</a-button>
        </a-space>
      </div>
      <a-descriptions v-if="detail" bordered size="small" :column="isMobile ? 1 : 2">
        <a-descriptions-item label="域名">{{ detail.name }}</a-descriptions-item>
        <a-descriptions-item label="状态">
          <a-tag :color="detail.status === 'active' ? 'success' : 'default'">{{ detail.status }}</a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="套餐">{{ detail.planName || '—' }}</a-descriptions-item>
        <a-descriptions-item label="Zone ID">{{ detail.id }}</a-descriptions-item>
        <a-descriptions-item label="NS 服务器" :span="isMobile ? 1 : 2">
          <span v-if="detail.nameServers?.length">{{ detail.nameServers.join(' · ') }}</span>
          <span v-else>—</span>
        </a-descriptions-item>
      </a-descriptions>
      <a-empty v-else-if="!loading" description="暂无区域信息" />
    </a-card>

    <a-modal
      v-model:open="pauseModalVisible"
      :mask-closable="false"
      :keyboard="false"
      :title="pendingPaused ? '安全验证 — 暂停解析' : '安全验证 — 恢复解析'"
      :width="400"
      :confirm-loading="pauseVerifyLoading"
      ok-text="确认"
      @ok="confirmPause"
    >
      <a-alert type="warning" show-icon style="margin-bottom: 16px">
        <template #message>验证码已发送至 Telegram，{{ pendingPaused ? '暂停后域名将不再解析' : '恢复后域名将正常解析' }}</template>
      </a-alert>
      <a-input v-model:value="pauseVerifyCode" placeholder="请输入6位验证码" size="large" :maxlength="6" allow-clear />
      <div class="cf-verify-footer">
        <span>验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="verifySending" @click="sendPauseCode">重新发送</a-button>
      </div>
    </a-modal>

    <a-modal
      v-model:open="deleteModalVisible"
      :mask-closable="false"
      :keyboard="false"
      title="安全验证 — 删除区域"
      :width="400"
      :confirm-loading="deleteVerifyLoading"
      ok-text="确认删除"
      :ok-button-props="{ danger: true }"
      @ok="confirmDelete"
    >
      <a-alert type="error" show-icon style="margin-bottom: 16px">
        <template #message>删除区域不可恢复，验证码已发送至 Telegram</template>
      </a-alert>
      <p v-if="detail" class="cf-delete-target">将删除：<b>{{ detail.name }}</b></p>
      <a-input v-model:value="deleteVerifyCode" placeholder="请输入6位验证码" size="large" :maxlength="6" allow-clear />
      <div class="cf-verify-footer">
        <span>验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="verifySending" @click="sendDeleteCode">重新发送</a-button>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getCfZoneDetail, deleteCfZone, setCfZonePaused } from '../../api/cloudflare'
import { sendVerifyCode } from '../../api/system'
import { useIsMobile } from '../../composables/useIsMobile'

const props = defineProps<{
  zoneId?: string
}>()

const emit = defineEmits<{
  'update:zoneId': [value: string | undefined]
}>()

const loading = ref(false)
const pausedLoading = ref(false)
const deleteLoading = ref(false)
const detail = ref<{
  id: string
  name: string
  status: string
  paused?: boolean
  planName?: string
  nameServers?: string[]
} | null>(null)

const { isMobile } = useIsMobile()

const pauseModalVisible = ref(false)
const deleteModalVisible = ref(false)
const pendingPaused = ref(false)
const pauseVerifyCode = ref('')
const deleteVerifyCode = ref('')
const pauseVerifyLoading = ref(false)
const deleteVerifyLoading = ref(false)
const verifySending = ref(false)

let loadSeq = 0

async function load() {
  if (!props.zoneId) {
    detail.value = null
    return
  }
  const seq = ++loadSeq
  loading.value = true
  try {
    const res = await getCfZoneDetail({ zoneId: props.zoneId }, true)
    if (seq !== loadSeq) return
    detail.value = res.data || null
  } catch {
    if (seq !== loadSeq) return
    detail.value = null
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function sendPauseCode() {
  verifySending.value = true
  try {
    await sendVerifyCode('cfZonePause')
    message.success('验证码已发送')
  } finally {
    verifySending.value = false
  }
}

async function sendDeleteCode() {
  verifySending.value = true
  try {
    await sendVerifyCode('cfZoneDelete')
    message.success('验证码已发送')
  } finally {
    verifySending.value = false
  }
}

async function onPauseSwitch(paused: boolean) {
  if (!props.zoneId) return
  pendingPaused.value = paused
  pauseVerifyCode.value = ''
  pauseModalVisible.value = true
  try {
    await sendPauseCode()
  } catch {
    /* sendVerifyCode 已提示 */
  }
}

async function confirmPause() {
  if (!props.zoneId) return
  if (!pauseVerifyCode.value || pauseVerifyCode.value.length !== 6) {
    message.warning('请输入6位验证码')
    return
  }
  pauseVerifyLoading.value = true
  pausedLoading.value = true
  try {
    const res = await setCfZonePaused({
      zoneId: props.zoneId,
      paused: pendingPaused.value,
      verifyCode: pauseVerifyCode.value,
    })
    if (detail.value) detail.value.paused = res.data?.paused ?? pendingPaused.value
    message.success(pendingPaused.value ? '区域已暂停' : '区域已恢复')
    pauseModalVisible.value = false
  } finally {
    pauseVerifyLoading.value = false
    pausedLoading.value = false
  }
}

async function openDeleteModal() {
  if (!props.zoneId) return
  deleteVerifyCode.value = ''
  deleteModalVisible.value = true
  try {
    await sendDeleteCode()
  } catch {
    /* sendVerifyCode 已提示 */
  }
}

async function confirmDelete() {
  if (!props.zoneId) return
  if (!deleteVerifyCode.value || deleteVerifyCode.value.length !== 6) {
    message.warning('请输入6位验证码')
    return
  }
  deleteVerifyLoading.value = true
  deleteLoading.value = true
  try {
    await deleteCfZone({ zoneId: props.zoneId, verifyCode: deleteVerifyCode.value })
    message.success('区域已删除')
    deleteModalVisible.value = false
    detail.value = null
    emit('update:zoneId', undefined)
  } finally {
    deleteVerifyLoading.value = false
    deleteLoading.value = false
  }
}

watch(() => props.zoneId, () => load(), { immediate: true })
</script>

<style scoped>
.cf-overview-actions {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}
.cf-overview-action-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.cf-label-inline {
  font-size: 13px;
  color: var(--text-sub);
}
.cf-verify-footer {
  margin-top: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--text-sub);
}
.cf-delete-target {
  margin-bottom: 12px;
  color: var(--text-main);
}
</style>
