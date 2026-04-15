<template>
  <div>
    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="security" tab="安全设置">
        <a-card title="修改登录密码" class="settings-card">
          <div style="position: relative; overflow: hidden; min-height: 200px">
            <!-- 安全验证遮罩 -->
            <div v-if="!pwdTgVerified" class="overlay-mask">
              <div class="overlay-content">
                <i :class="tgConfigured ? 'ri-shield-check-line' : 'ri-lock-2-line'" style="font-size: 32px; color: #818cf8; margin-bottom: 12px"></i>
                <p style="margin-bottom: 16px; color: #94a3b8">{{ tgConfigured ? '修改密码需要 Telegram 验证码' : '请输入登录密码以继续' }}</p>
                <a-space v-if="tgConfigured" direction="vertical" style="width: 240px">
                  <a-button block @click="sendPwdVerifyCode" :loading="pwdCodeSending" :disabled="pwdCodeCountdown > 0">
                    {{ pwdCodeCountdown > 0 ? pwdCodeCountdown + '秒后重新发送' : '发送验证码' }}
                  </a-button>
                  <a-input v-model:value="pwdTgCode" placeholder="输入 TG 验证码" @pressEnter="verifyPwdTgCode" />
                  <a-button type="primary" block @click="verifyPwdTgCode" :disabled="!pwdTgCode">验证</a-button>
                </a-space>
                <a-space v-else direction="vertical" style="width: 240px">
                  <a-input-password v-model:value="pwdOverlayPwd" placeholder="输入登录密码" @pressEnter="verifyPwdOverlay" />
                  <a-button type="primary" block @click="verifyPwdOverlay" :disabled="!pwdOverlayPwd">验证</a-button>
                </a-space>
              </div>
            </div>
            <a-form :model="pwdForm" layout="vertical">
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
          </div>
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
          <div style="position: relative; overflow: hidden; min-height: 200px">
            <!-- 密码验证遮罩 -->
            <div v-if="!notifyPwdVerified" class="overlay-mask">
              <div class="overlay-content">
                <i class="ri-lock-2-line" style="font-size: 32px; color: #818cf8; margin-bottom: 12px"></i>
                <p style="margin-bottom: 16px; color: #94a3b8">请输入登录密码进行配置</p>
                <a-space direction="vertical" style="width: 240px">
                  <a-input-password v-model:value="notifyPwd" placeholder="输入登录密码" @pressEnter="verifyNotifyPwd" />
                  <a-button type="primary" block @click="verifyNotifyPwd" :disabled="!notifyPwd">验证</a-button>
                </a-space>
              </div>
            </div>
            <a-form layout="vertical">
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
          </div>
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
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import request from '../utils/request'

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

onUnmounted(() => { if (pwdCountdownTimer) clearInterval(pwdCountdownTimer) })

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
</script>

<style scoped>
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

.overlay-mask {
  position: absolute;
  inset: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgb(15, 23, 42);
  border-radius: inherit;
}

.overlay-content {
  text-align: center;
  padding: 24px;
}

@media (max-width: 768px) {
  .settings-card,
  .settings-card-wide {
    max-width: 100% !important;
  }
}
</style>
