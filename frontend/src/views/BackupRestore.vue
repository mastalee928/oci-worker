<template>
  <div>
    <a-row :gutter="[16, 16]">
      <a-col :xs="24" :md="12">
        <a-card title="备份">
          <a-form layout="vertical">
            <a-form-item label="加密密码">
              <a-input-password v-model:value="backupPassword" placeholder="设置备份加密密码" />
            </a-form-item>
            <a-button type="primary" @click="openBackupVerify" :loading="backupLoading">
              创建加密备份
            </a-button>
          </a-form>
        </a-card>
      </a-col>
      <a-col :xs="24" :md="12">
        <a-card title="恢复">
          <a-form layout="vertical">
            <a-form-item label="备份文件">
              <a-upload :before-upload="handleFileSelect" :max-count="1" accept=".zip" :file-list="fileList" @remove="() => { restoreFile = null; fileList = [] }">
                <a-button><UploadOutlined />选择备份文件</a-button>
              </a-upload>
            </a-form-item>
            <a-form-item label="解密密码">
              <a-input-password v-model:value="restorePassword" placeholder="输入备份加密密码" />
            </a-form-item>
            <a-button type="primary" danger @click="handleRestore" :loading="restoreLoading">
              恢复备份
            </a-button>
          </a-form>
        </a-card>
      </a-col>
    </a-row>

    <!-- 备份验证码弹窗 -->
    <a-modal v-model:open="verifyModalVisible" title="安全验证 — 备份数据" :width="isMobile ? '100%' : 400"
      @ok="handleBackupWithCode" :confirm-loading="verifyLoading" ok-text="确认备份">
      <a-alert type="info" show-icon style="margin-bottom: 16px">
        <template #message>验证码已发送至 Telegram</template>
      </a-alert>
      <a-input v-model:value="verifyCode" placeholder="请输入6位验证码" size="large" :maxlength="6" allow-clear />
      <div style="margin-top: 12px; display: flex; justify-content: space-between; align-items: center">
        <span style="color: var(--text-sub); font-size: 12px">验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="verifySending" @click="resendCode">重新发送</a-button>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { UploadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import type { UploadFile } from 'ant-design-vue'
import { useUserStore } from '../stores/user'
import { sendVerifyCode } from '../api/system'
import request from '../utils/request'

const userStore = useUserStore()
const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }
onMounted(() => window.addEventListener('resize', checkMobile))
onUnmounted(() => window.removeEventListener('resize', checkMobile))

const backupPassword = ref('')
const restorePassword = ref('')
const backupLoading = ref(false)
const restoreLoading = ref(false)
const restoreFile = ref<File | null>(null)
const fileList = ref<UploadFile[]>([])

const verifyModalVisible = ref(false)
const verifyCode = ref('')
const verifyLoading = ref(false)
const verifySending = ref(false)

async function openBackupVerify() {
  if (!backupPassword.value) {
    message.warning('请设置加密密码')
    return
  }
  verifySending.value = true
  try {
    await sendVerifyCode('backup')
    message.success('验证码已发送至 Telegram')
    verifyCode.value = ''
    verifyModalVisible.value = true
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
  } finally {
    verifySending.value = false
  }
}

async function resendCode() {
  verifySending.value = true
  try {
    await sendVerifyCode('backup')
    message.success('验证码已重新发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    verifySending.value = false
  }
}

async function handleBackupWithCode() {
  if (!verifyCode.value || verifyCode.value.length !== 6) {
    message.warning('请输入6位验证码')
    return
  }
  verifyLoading.value = true
  backupLoading.value = true
  try {
    const params = new URLSearchParams({
      password: backupPassword.value,
      verifyCode: verifyCode.value,
    })
    const resp = await fetch(`/api/sys/backup/create?${params}`, {
      method: 'POST',
      headers: { Authorization: userStore.token || '' },
    })
    if (!resp.ok) {
      const text = await resp.text()
      try {
        const json = JSON.parse(text)
        throw new Error(json.message || '备份失败')
      } catch {
        throw new Error(text || '备份失败')
      }
    }
    const blob = await resp.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'oci-worker-backup.zip'
    a.click()
    URL.revokeObjectURL(url)
    message.success('备份下载成功')
    verifyModalVisible.value = false
  } catch (e: any) {
    message.error(e?.message || '备份失败')
  } finally {
    verifyLoading.value = false
    backupLoading.value = false
  }
}

function handleFileSelect(file: File) {
  restoreFile.value = file
  fileList.value = [{ uid: '-1', name: file.name, status: 'done' } as UploadFile]
  return false
}

async function handleRestore() {
  if (!restoreFile.value) {
    message.warning('请选择备份文件')
    return
  }
  if (!restorePassword.value) {
    message.warning('请输入解密密码')
    return
  }
  restoreLoading.value = true
  try {
    const fd = new FormData()
    fd.append('file', restoreFile.value)
    fd.append('password', restorePassword.value)
    await request.post('/sys/backup/restore', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    message.success('恢复成功，建议重启服务')
  } catch (e: any) {
    message.error(e?.message || '恢复失败')
  } finally {
    restoreLoading.value = false
  }
}
</script>

<style scoped>
:deep(.ant-card) {
  border-radius: var(--radius-lg) !important;
  border-color: var(--border) !important;
  box-shadow: var(--shadow-card) !important;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: var(--trans);
}
</style>
