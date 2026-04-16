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
                <span style="font-weight: 600; color: var(--primary)">{{ updateInfo.currentBuildTime || '—' }}</span>
                <span style="margin-left: 8px; color: var(--text-sub); font-size: 12px">({{ updateInfo.currentSizeHuman }})</span>
              </a-descriptions-item>
              <a-descriptions-item label="最新版本">
                <span v-if="updateInfo.latestCommit" style="font-weight: 600">
                  <a-tag color="blue" style="margin-right: 6px">{{ updateInfo.latestCommit }}</a-tag>
                </span>
                <span v-if="updateInfo.publishedAt">{{ formatPublishDate(updateInfo.publishedAt) }}</span>
                <span style="margin-left: 8px; color: var(--text-sub); font-size: 12px">({{ updateInfo.latestSizeHuman }})</span>
              </a-descriptions-item>
              <a-descriptions-item label="状态">
                <a-badge v-if="updateInfo.hasUpdate" status="warning" text="有新版本可用" />
                <a-badge v-else-if="updateInfo.error" status="error" :text="'检查失败: ' + updateInfo.error" />
                <a-badge v-else status="success" text="已是最新版本" />
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
    setTimeout(() => {
      message.loading({ content: '等待服务重启...', duration: 0, key: 'update' })
      let attempts = 0
      const maxAttempts = 30
      const poll = setInterval(async () => {
        attempts++
        try {
          await request.get('/sys/glance')
          clearInterval(poll)
          message.success({ content: '更新完成，服务已恢复！', key: 'update' })
          updatePerforming.value = false
          checkUpdate()
        } catch {
          if (attempts >= maxAttempts) {
            clearInterval(poll)
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
  .settings-card-wide {
    max-width: 100% !important;
  }
}
</style>
