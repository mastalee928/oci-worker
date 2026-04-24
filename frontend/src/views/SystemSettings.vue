<template>
  <div>
    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="security" tab="安全设置">
        <a-card title="修改登录密码" class="settings-card">
          <div v-if="!pwdTgVerified" class="lock-panel">
            <i :class="tgConfigured ? 'ri-shield-check-line' : 'ri-lock-2-line'" class="lock-icon"></i>
            <p class="lock-text">{{ tgConfigured ? '修改密码需要 Telegram 验证码' : '请输入登录密码以继续' }}</p>
            <a-space v-if="tgConfigured" direction="vertical" style="width: 100%">
              <a-button block @click="sendPwdVerifyCode" :loading="pwdCodeSending" :disabled="pwdCodeCountdown > 0">
                {{ pwdCodeCountdown > 0 ? pwdCodeCountdown + '秒后重新发送' : '发送验证码' }}
              </a-button>
              <a-input v-model:value="pwdTgCode" placeholder="输入 TG 验证码" @pressEnter="verifyPwdTgCode" />
              <a-button type="primary" block @click="verifyPwdTgCode" :disabled="!pwdTgCode">验证</a-button>
            </a-space>
            <a-space v-else direction="vertical" style="width: 100%">
              <a-input-password v-model:value="pwdOverlayPwd" placeholder="输入登录密码" @pressEnter="verifyPwdOverlay" />
              <a-button type="primary" block @click="verifyPwdOverlay" :disabled="!pwdOverlayPwd">验证</a-button>
            </a-space>
          </div>
          <a-form v-else :model="pwdForm" layout="vertical">
            <a-form-item label="原密码" required>
              <a-input-password v-model:value="pwdForm.oldPassword" placeholder="输入当前密码" />
            </a-form-item>
            <a-form-item label="新密码" required>
              <a-input-password v-model:value="pwdForm.newPassword" placeholder="至少6位" />
            </a-form-item>
            <a-form-item label="确认新密码" required>
              <a-input-password v-model:value="pwdForm.confirmPassword" placeholder="再次输入新密码" />
            </a-form-item>
            <a-button type="primary" @click="handleChangePassword" :loading="pwdLoading">修改密码</a-button>
          </a-form>
        </a-card>

        <a-card title="登录安全说明" class="settings-card" style="margin-top: 16px">
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item label="Token 有效期">24 小时</a-descriptions-item>
            <a-descriptions-item label="关闭浏览器">Token 保持有效，直到过期</a-descriptions-item>
            <a-descriptions-item label="Token 存储">浏览器 localStorage</a-descriptions-item>
          </a-descriptions>
          <div style="margin-top: 12px">
            <a-button danger @click="handleForceLogout">立即退出登录</a-button>
          </div>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="notify" tab="消息通知">
        <a-card title="Telegram 通知" class="settings-card-wide">
          <div v-if="!notifyPwdVerified" class="lock-panel">
            <i class="ri-lock-2-line lock-icon"></i>
            <p class="lock-text">请输入登录密码进行配置</p>
            <a-space direction="vertical" style="width: 100%">
              <a-input-password v-model:value="notifyPwd" placeholder="输入登录密码" @pressEnter="verifyNotifyPwd" />
              <a-button type="primary" block @click="verifyNotifyPwd" :disabled="!notifyPwd">验证</a-button>
            </a-space>
          </div>
          <a-form v-else layout="vertical">
            <a-form-item label="Bot Token">
              <a-input v-model:value="tgConfig.botToken" placeholder="输入 Telegram Bot Token" />
            </a-form-item>
            <a-form-item label="Chat ID">
              <a-input v-model:value="tgConfig.chatId" placeholder="输入 Chat ID" />
            </a-form-item>
            <a-form-item label="通知类型">
              <a-checkbox-group v-model:value="tgConfig.notifyTypes" :options="notifyTypeOptions" />
            </a-form-item>
            <a-space>
              <a-button type="primary" @click="saveTgConfig" :loading="saveLoading">保存</a-button>
              <a-button @click="testTgNotify" :loading="testLoading">测试发送</a-button>
            </a-space>
          </a-form>
        </a-card>

        <a-card title="通知说明" class="settings-card-wide" style="margin-top: 16px">
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item label="登录通知">登录成功/失败时发送，包含IP地址、账号、时间</a-descriptions-item>
            <a-descriptions-item label="创建任务">创建开机任务时通知</a-descriptions-item>
            <a-descriptions-item label="任务结果">开机成功或认证失败时通知，包含实例详情</a-descriptions-item>
            <a-descriptions-item label="每日播报">每天 9:00 自动发送，包含租户总数、失效租户、运行中任务</a-descriptions-item>
          </a-descriptions>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="update" tab="系统更新">
        <a-card title="一键更新" class="settings-card-wide">
          <a-spin :spinning="updateChecking">
            <a-descriptions :column="1" bordered size="small" v-if="updateInfo">
              <a-descriptions-item label="当前版本">
                <a-tag :color="updateInfo.currentCommit === 'dev' ? 'orange' : 'green'" style="margin-right: 6px">{{ updateInfo.currentCommit }}</a-tag>
                <span v-if="updateInfo.currentBuildTime" style="color: var(--text-sub); font-size: 12px">{{ updateInfo.currentBuildTime }}</span>
                <span v-if="updateInfo.currentSizeHuman" style="margin-left: 8px; color: var(--text-sub); font-size: 12px">({{ updateInfo.currentSizeHuman }})</span>
              </a-descriptions-item>
              <a-descriptions-item label="最新版本">
                <a-tag v-if="updateInfo.latestCommit" color="blue" style="margin-right: 6px">{{ updateInfo.latestCommit }}</a-tag>
                <span v-if="updateInfo.publishedAt" style="font-size: 12px">{{ formatPublishDate(updateInfo.publishedAt) }}</span>
                <span v-if="updateInfo.latestSizeHuman" style="margin-left: 8px; color: var(--text-sub); font-size: 12px">({{ updateInfo.latestSizeHuman }})</span>
              </a-descriptions-item>
              <a-descriptions-item label="状态">
                <a-badge v-if="updateInfo.hasUpdate" status="warning" text="有新版本可用" />
                <a-badge v-else-if="updateInfo.error" status="error" :text="'检查失败: ' + updateInfo.error" />
                <a-badge v-else-if="updateInfo.notice" status="processing" :text="updateInfo.notice" />
                <a-badge v-else-if="updateInfo.versionNotice" status="success" text="无需更新" />
                <a-badge v-else status="success" text="已是最新版本" />
              </a-descriptions-item>
              <a-descriptions-item
                v-if="updateInfo.versionNotice"
                :label="updateInfo.hasUpdate ? '注意' : '说明'"
              >
                <span style="color: var(--text-sub); font-size: 12px">{{ updateInfo.versionNotice }}</span>
              </a-descriptions-item>
            </a-descriptions>
            <a-empty v-else description="点击检查更新" />
          </a-spin>
          <div style="margin-top: 16px">
            <a-space>
              <a-button @click="checkUpdate" :loading="updateChecking">检查更新</a-button>
              <a-popconfirm title="确定执行更新？更新过程中服务将短暂重启。" @confirm="performUpdate" ok-text="确定更新" cancel-text="取消">
                <a-button type="primary" :loading="updatePerforming" :disabled="!updateInfo?.hasUpdate && !updateForce">
                  <i class="ri-download-2-line" style="margin-right: 6px"></i>一键更新
                </a-button>
              </a-popconfirm>
            </a-space>
            <div style="margin-top: 8px">
              <a-checkbox v-model:checked="updateForce" size="small">
                <span style="font-size: 12px; color: var(--text-sub)">强制更新（即使版本相同）</span>
              </a-checkbox>
            </div>
          </div>
        </a-card>

        <a-card title="更新说明" class="settings-card-wide" style="margin-top: 16px">
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item label="更新来源">GitHub Releases (mastalee928/oci-worker)</a-descriptions-item>
            <a-descriptions-item label="更新流程">下载最新 JAR → 替换本地文件 → 重启服务</a-descriptions-item>
            <a-descriptions-item label="预计耗时">10 ~ 30 秒（取决于网络）</a-descriptions-item>
            <a-descriptions-item label="注意事项">更新期间页面将短暂无法访问，完成后自动恢复</a-descriptions-item>
          </a-descriptions>
        </a-card>
      </a-tab-pane>
      <a-tab-pane key="backup" tab="备份恢复">
        <div class="backup-restore-stack">
          <a-card title="备份" class="settings-card-wide">
            <a-form layout="vertical">
              <a-form-item label="加密密码">
                <a-input-password v-model:value="backupPassword" placeholder="设置备份加密密码" />
              </a-form-item>
              <a-button type="primary" @click="openBackupVerify" :loading="backupLoading">
                创建加密备份
              </a-button>
            </a-form>
          </a-card>
          <a-card title="恢复" class="settings-card-wide">
            <a-form layout="vertical">
              <a-form-item label="备份文件（支持点击或从桌面/文件夹拖拽到下方区域）">
                <a-upload-dragger
                  class="backup-restore-dragger"
                  :before-upload="handleFileSelect"
                  :max-count="1"
                  accept=".zip,application/zip,application/x-zip-compressed"
                  :file-list="fileList"
                  :show-upload-list="{ showRemoveIcon: true }"
                  @remove="handleRestoreFileRemove"
                >
                  <p class="ant-upload-drag-icon" style="margin-bottom: 8px">
                    <InboxOutlined style="color: var(--primary); font-size: 40px" />
                  </p>
                  <p class="ant-upload-text" style="color: var(--text-main)">点击或拖拽 <strong>oci-worker-backup.zip</strong> 到此处</p>
                  <p class="ant-upload-hint" style="color: var(--text-sub)">仅支持网页创建下载的 .zip 加密备份</p>
                </a-upload-dragger>
              </a-form-item>
              <a-form-item label="解密密码">
                <a-input-password v-model:value="restorePassword" placeholder="输入备份加密密码" />
              </a-form-item>
              <a-button type="primary" danger @click="handleRestore" :loading="restoreLoading">
                恢复备份
              </a-button>
            </a-form>
          </a-card>
        </div>

        <a-modal v-model:open="backupVerifyVisible" title="安全验证 — 备份数据" :width="isMobile ? '100%' : 400"
          @ok="handleBackupWithCode" :confirm-loading="backupVerifyLoading" ok-text="确认备份">
          <a-alert type="info" show-icon style="margin-bottom: 16px">
            <template #message>验证码已发送至 Telegram</template>
          </a-alert>
          <a-input v-model:value="backupVerifyCode" placeholder="请输入6位验证码" size="large" :maxlength="6" allow-clear />
          <div style="margin-top: 12px; display: flex; justify-content: space-between; align-items: center">
            <span style="color: var(--text-sub); font-size: 12px">验证码有效期 5 分钟</span>
            <a-button type="link" size="small" :loading="backupCodeSending" @click="resendBackupCode">重新发送</a-button>
          </div>
        </a-modal>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { InboxOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import type { UploadFile } from 'ant-design-vue'
import { useUserStore } from '../stores/user'
import { sendVerifyCode } from '../api/system'
import request from '../utils/request'

const userStore = useUserStore()

const router = useRouter()
const activeTab = ref('security')
const pwdLoading = ref(false)
const saveLoading = ref(false)
const testLoading = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const tgConfig = reactive({ botToken: '', chatId: '', notifyTypes: [] as string[] })

const tgConfigured = ref(false)
const pwdTgVerified = ref(false)
const pwdTgCode = ref('')
const pwdTgVerifiedCode = ref('')
const pwdCodeSending = ref(false)
const pwdCodeCountdown = ref(0)
let pwdCountdownTimer: any = null

const pwdOverlayPwd = ref('')

const notifyPwdVerified = ref(false)
const notifyPwd = ref('')
const notifyVerifiedPwd = ref('')

const notifyTypeOptions = [
  { label: '登录通知', value: 'login' },
  { label: '创建任务', value: 'task_create' },
  { label: '任务结果', value: 'task_result' },
  { label: '每日播报', value: 'daily_report' },
]

onMounted(async () => {
  loadNotifyConfig()
  try {
    const res = await request.get('/sys/tgStatus')
    tgConfigured.value = res.data?.configured === true
  } catch {}
})


async function loadNotifyConfig() {
  try {
    const res = await request.get('/sys/notifyConfig')
    tgConfig.botToken = res.data?.botToken || ''
    tgConfig.chatId = res.data?.chatId || ''
    const types = res.data?.notifyTypes
    tgConfig.notifyTypes = types ? types.split(',') : ['login', 'task_create', 'task_result', 'daily_report']
  } catch {}
}

async function sendPwdVerifyCode() {
  pwdCodeSending.value = true
  try {
    await request.post('/sys/sendVerifyCode', { action: 'changePassword' })
    message.success('验证码已发送到 Telegram')
    pwdCodeCountdown.value = 60
    if (pwdCountdownTimer) clearInterval(pwdCountdownTimer)
    pwdCountdownTimer = setInterval(() => {
      pwdCodeCountdown.value--
      if (pwdCodeCountdown.value <= 0) clearInterval(pwdCountdownTimer)
    }, 1000)
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    pwdCodeSending.value = false
  }
}

function verifyPwdTgCode() {
  if (!pwdTgCode.value) { message.warning('请输入验证码'); return }
  pwdTgVerifiedCode.value = pwdTgCode.value
  pwdTgVerified.value = true
  message.success('验证通过')
}

async function verifyPwdOverlay() {
  if (!pwdOverlayPwd.value) { message.warning('请输入密码'); return }
  try {
    await request.post('/auth/verifyPassword', { password: pwdOverlayPwd.value })
    pwdTgVerified.value = true
    message.success('验证通过')
  } catch (e: any) {
    message.error(e?.message || '密码错误')
  }
}

function verifyNotifyPwd() {
  if (!notifyPwd.value) { message.warning('请输入密码'); return }
  notifyVerifiedPwd.value = notifyPwd.value
  notifyPwdVerified.value = true
  message.success('验证通过')
}

async function handleChangePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    message.warning('请填写密码')
    return
  }
  if (pwdForm.newPassword.length < 6) {
    message.warning('新密码不能少于 6 位')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirmPassword) {
    message.warning('两次输入的密码不一致')
    return
  }
  pwdLoading.value = true
  try {
    const res = await request.post('/auth/changePassword', {
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword,
      verifyCode: pwdTgVerifiedCode.value || undefined,
    })
    if (res.data?.token) {
      localStorage.setItem('token', res.data.token)
    }
    message.success('密码修改成功')
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
    pwdTgVerified.value = false
    pwdTgCode.value = ''
    pwdTgVerifiedCode.value = ''
    pwdOverlayPwd.value = ''
  } catch (e: any) {
    message.error(e?.message || '修改密码失败')
  } finally {
    pwdLoading.value = false
  }
}

function handleForceLogout() {
  localStorage.removeItem('token')
  router.push('/login')
}

async function saveTgConfig() {
  saveLoading.value = true
  try {
    await request.post('/sys/notifyConfig', {
      botToken: tgConfig.botToken,
      chatId: tgConfig.chatId,
      notifyTypes: tgConfig.notifyTypes.join(','),
      password: notifyVerifiedPwd.value,
    })
    message.success('保存成功')
    notifyPwdVerified.value = false
    notifyPwd.value = ''
    notifyVerifiedPwd.value = ''
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    saveLoading.value = false
  }
}

async function testTgNotify() {
  testLoading.value = true
  try {
    await request.post('/sys/testNotify')
    message.success('测试消息已发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    testLoading.value = false
  }
}

const updateChecking = ref(false)
const updatePerforming = ref(false)
const updateInfo = ref<any>(null)
const updateForce = ref(false)
let updatePollTimer: any = null
let updateStartTimer: any = null
let updateRedirectTimer: any = null

async function checkUpdate() {
  updateChecking.value = true
  try {
    const res = await request.get('/sys/checkUpdate')
    updateInfo.value = res.data
  } catch (e: any) {
    message.error(e?.message || '检查更新失败')
  } finally {
    updateChecking.value = false
  }
}

async function performUpdate() {
  updatePerforming.value = true
  try {
    await request.post('/sys/performUpdate')
    message.success('更新已启动，服务即将重启...')
    if (updateStartTimer) clearTimeout(updateStartTimer)
    updateStartTimer = setTimeout(() => {
      message.loading({ content: '等待服务重启...', duration: 0, key: 'update' })
      let attempts = 0
      const maxAttempts = 30
      if (updatePollTimer) clearInterval(updatePollTimer)
      updatePollTimer = setInterval(async () => {
        attempts++
        try {
          await request.get('/sys/glance')
          if (updatePollTimer) { clearInterval(updatePollTimer); updatePollTimer = null }
          message.success({ content: '更新完成，3秒后跳转首页...', key: 'update' })
          updatePerforming.value = false
          if (updateRedirectTimer) clearTimeout(updateRedirectTimer)
          updateRedirectTimer = setTimeout(() => { window.location.href = '/' }, 3000)
        } catch {
          if (attempts >= maxAttempts) {
            if (updatePollTimer) { clearInterval(updatePollTimer); updatePollTimer = null }
            message.warning({ content: '服务重启超时，请手动刷新页面', key: 'update' })
            updatePerforming.value = false
          }
        }
      }, 3000)
    }, 3000)
  } catch (e: any) {
    message.error(e?.message || '启动更新失败')
    updatePerforming.value = false
  }
}

function formatPublishDate(isoStr: string) {
  try {
    return new Date(isoStr).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
  } catch {
    return isoStr
  }
}

const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }
onMounted(() => window.addEventListener('resize', checkMobile))
onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
  if (pwdCountdownTimer) clearInterval(pwdCountdownTimer)
  if (updatePollTimer) clearInterval(updatePollTimer)
  if (updateStartTimer) clearTimeout(updateStartTimer)
  if (updateRedirectTimer) clearTimeout(updateRedirectTimer)
})

const backupPassword = ref('')
const restorePassword = ref('')
const backupLoading = ref(false)
const restoreLoading = ref(false)
const restoreFile = ref<File | null>(null)
const fileList = ref<UploadFile[]>([])
const backupVerifyVisible = ref(false)
const backupVerifyCode = ref('')
const backupVerifyLoading = ref(false)
const backupCodeSending = ref(false)

async function openBackupVerify() {
  if (!backupPassword.value) { message.warning('请设置加密密码'); return }
  backupCodeSending.value = true
  try {
    await sendVerifyCode('backup')
    message.success('验证码已发送至 Telegram')
    backupVerifyCode.value = ''
    backupVerifyVisible.value = true
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
  } finally {
    backupCodeSending.value = false
  }
}

async function resendBackupCode() {
  backupCodeSending.value = true
  try {
    await sendVerifyCode('backup')
    message.success('验证码已重新发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    backupCodeSending.value = false
  }
}

async function handleBackupWithCode() {
  if (!backupVerifyCode.value || backupVerifyCode.value.length !== 6) {
    message.warning('请输入6位验证码'); return
  }
  backupVerifyLoading.value = true
  backupLoading.value = true
  try {
    const rawToken = (userStore.token || '').trim()
    const authHeader = rawToken ? (rawToken.startsWith('Bearer ') ? rawToken : `Bearer ${rawToken}`) : ''
    const body = new URLSearchParams({ password: backupPassword.value, verifyCode: backupVerifyCode.value })
    const resp = await fetch('/api/sys/backup/create', {
      method: 'POST',
      headers: {
        Authorization: authHeader,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body,
    })
    if (!resp.ok) {
      const text = await resp.text()
      let msg = '备份失败'
      try {
        const json = JSON.parse(text) as { message?: string }
        if (json?.message) msg = json.message
      } catch {
        if (text) msg = text.slice(0, 240)
      }
      throw new Error(msg)
    }
    // 业务错误（如验证码错误、备份失败）走 GlobalExceptionHandler 时仍是 HTTP 200 + application/json；
    // 若直接当 blob 下载会得到几十字节的“假 zip”，恢复时报压缩包损坏。这里按魔数/JSON 显式拆出来。
    const buf = await resp.arrayBuffer()
    const u8 = new Uint8Array(buf)
    const isZip = u8.length >= 2 && u8[0] === 0x50 && u8[1] === 0x4B
    if (!isZip) {
      const text = new TextDecoder().decode(buf)
      let errMsg = '服务器未返回有效的 ZIP 备份，请重试或查看服务日志'
      try {
        const json = JSON.parse(text) as { message?: string }
        if (json?.message) errMsg = json.message
      } catch {
        if (text.trim().length) errMsg = text.trim().slice(0, 240)
      }
      throw new Error(errMsg)
    }
    if (u8.length < 64) {
      throw new Error('备份文件异常过小，请重试或检查服务是否正常')
    }
    const blob = new Blob([buf], { type: 'application/zip' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = 'oci-worker-backup.zip'; a.click()
    URL.revokeObjectURL(url)
    message.success('备份下载成功')
    backupVerifyVisible.value = false
  } catch (e: any) {
    message.error(e?.message || '备份失败')
  } finally {
    backupVerifyLoading.value = false
    backupLoading.value = false
  }
}

function handleFileSelect(file: File) {
  restoreFile.value = file
  fileList.value = [{ uid: String(file.name + file.size), name: file.name, status: 'done' } as UploadFile]
  return false
}

function handleRestoreFileRemove() {
  restoreFile.value = null
  fileList.value = []
}

async function handleRestore() {
  if (!restoreFile.value) { message.warning('请选择备份文件'); return }
  if (!restorePassword.value) { message.warning('请输入解密密码'); return }
  restoreLoading.value = true
  try {
    const fd = new FormData()
    fd.append('file', restoreFile.value)
    fd.append('password', restorePassword.value)
    await request.post('/sys/backup/restore', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    message.success('恢复成功，建议重启服务')
  } catch (e: any) {
    message.error(e?.message || '恢复失败')
  } finally {
    restoreLoading.value = false
  }
}
</script>

<style scoped>
.backup-restore-stack {
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-width: 560px;
}
.backup-restore-dragger :deep(.ant-upload) {
  background: var(--input-bg) !important;
  border-color: var(--border) !important;
}
.backup-restore-dragger :deep(.ant-upload:hover) {
  border-color: rgba(129, 140, 248, 0.45) !important;
}

.settings-card {
  max-width: 480px;
  border-radius: var(--radius-lg) !important;
  box-shadow: var(--shadow-card) !important;
  border-color: var(--border) !important;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: var(--trans);
}
.settings-card-wide {
  max-width: 560px;
  border-radius: var(--radius-lg) !important;
  box-shadow: var(--shadow-card) !important;
  border-color: var(--border) !important;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: var(--trans);
}

.lock-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 32px 24px;
  max-width: 280px;
  margin: 0 auto;
}

.lock-icon {
  font-size: 36px;
  color: #818cf8;
  margin-bottom: 12px;
}

.lock-text {
  color: #94a3b8;
  margin-bottom: 20px;
  text-align: center;
  font-size: 14px;
}

@media (max-width: 768px) {
  .settings-card,
  .settings-card-wide,
  .backup-restore-stack {
    max-width: 100% !important;
  }
}
</style>
