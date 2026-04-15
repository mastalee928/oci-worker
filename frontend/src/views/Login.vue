<template>
  <div class="login-page">
    <div class="bg-glow" />
    <div class="bg-glow-2" />
    <div class="login-card">
      <div class="login-header">
        <i class="ri-server-line logo-icon"></i>
        <h2>OCI Worker</h2>
        <p>Oracle Cloud 实例管理面板</p>
      </div>
      <template v-if="loginMode === 'password'">
        <a-form :model="form" @finish="handleLogin" layout="vertical" class="login-form">
          <a-form-item name="account" :rules="[{ required: true, message: '请输入账号' }]">
            <a-input v-model:value="form.account" placeholder="管理员账号" size="large" class="login-input">
              <template #prefix><i class="ri-user-3-line input-prefix-icon"></i></template>
            </a-input>
          </a-form-item>
          <a-form-item name="password" :rules="[{ required: true, message: '请输入密码' }]">
            <a-input-password v-model:value="form.password" placeholder="登录密码" size="large" class="login-input">
              <template #prefix><i class="ri-lock-2-line input-prefix-icon"></i></template>
            </a-input-password>
          </a-form-item>
          <a-form-item>
            <button type="submit" :disabled="loading" class="submit-btn">
              <span v-if="loading">登录中...</span>
              <template v-else>
                立即登录
                <i class="ri-arrow-right-line"></i>
              </template>
            </button>
          </a-form-item>
        </a-form>
        <div v-if="tgAvailable" class="tg-switch" @click="loginMode = 'tg'">
          <i class="ri-telegram-line"></i> Telegram 验证码登录
        </div>
      </template>

      <template v-else>
        <div class="login-form">
          <div v-if="!tgCodeSent" style="text-align: center">
            <p class="tg-desc">将向绑定的 Telegram Bot 发送登录验证码</p>
            <button class="submit-btn" :disabled="tgSendLoading" @click="handleTgSendCode">
              <span v-if="tgSendLoading">发送中...</span>
              <template v-else>
                <i class="ri-send-plane-line"></i> 发送验证码
              </template>
            </button>
          </div>
          <template v-else>
            <div style="margin-bottom: 20px">
              <a-input v-model:value="tgCode" placeholder="输入验证码" size="large" class="login-input"
                :maxlength="18" @pressEnter="handleTgLogin">
                <template #prefix><i class="ri-shield-keyhole-line input-prefix-icon"></i></template>
              </a-input>
            </div>
            <button :disabled="tgLoginLoading || tgCode.length < 17" class="submit-btn" @click="handleTgLogin">
              <span v-if="tgLoginLoading">验证中...</span>
              <template v-else>
                验证并登录
                <i class="ri-arrow-right-line"></i>
              </template>
            </button>
            <div class="tg-countdown" v-if="tgCountdown > 0">
              {{ tgCountdown }}秒后可重新发送
            </div>
            <div v-else class="tg-resend" @click="handleTgSendCode">重新发送</div>
          </template>
          <div class="tg-switch" @click="loginMode = 'password'; tgCodeSent = false">
            <i class="ri-lock-2-line"></i> 账号密码登录
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useUserStore } from '../stores/user'
import { login, needSetup, tgLoginAvailable, tgLoginSendCode, tgLogin } from '../api/auth'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const form = reactive({ account: '', password: '' })

const loginMode = ref<'password' | 'tg'>('password')
const tgAvailable = ref(false)
const tgCodeSent = ref(false)
const tgSendLoading = ref(false)
const tgLoginLoading = ref(false)
const tgCode = ref('')
const tgCountdown = ref(0)
let countdownTimer: any = null

onMounted(async () => {
  try {
    const res = await needSetup()
    if (res.data) { router.replace('/setup'); return }
  } catch {}
  try {
    const res = await tgLoginAvailable()
    tgAvailable.value = res.data === true
  } catch {}
})

onUnmounted(() => { if (countdownTimer) clearInterval(countdownTimer) })

async function handleLogin() {
  loading.value = true
  try {
    const res = await login(form)
    userStore.setToken(res.data.token)
    message.success('登录成功')
    router.push('/')
  } catch {
  } finally {
    loading.value = false
  }
}

async function handleTgSendCode() {
  tgSendLoading.value = true
  try {
    await tgLoginSendCode()
    message.success('验证码已发送到 Telegram')
    tgCodeSent.value = true
    tgCode.value = ''
    startCountdown()
  } catch {
  } finally {
    tgSendLoading.value = false
  }
}

function startCountdown() {
  tgCountdown.value = 60
  if (countdownTimer) clearInterval(countdownTimer)
  countdownTimer = setInterval(() => {
    tgCountdown.value--
    if (tgCountdown.value <= 0) clearInterval(countdownTimer)
  }, 1000)
}

async function handleTgLogin() {
  if (!tgCode.value.includes(':') || tgCode.value.length < 17) { message.warning('请输入完整的验证码'); return }
  tgLoginLoading.value = true
  try {
    const res = await tgLogin({ code: tgCode.value })
    userStore.setToken(res.data.token)
    message.success('登录成功')
    router.push('/')
  } catch {
  } finally {
    tgLoginLoading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #020617;
  position: relative;
  overflow: hidden;
}
.bg-glow {
  position: absolute;
  width: 600px; height: 600px;
  background: radial-gradient(circle, rgba(99, 102, 241, 0.1) 0%, transparent 70%);
  top: -10%; left: -10%;
  z-index: 0;
  animation: float 10s infinite ease-in-out;
}
.bg-glow-2 {
  position: absolute;
  width: 500px; height: 500px;
  background: radial-gradient(circle, rgba(168, 85, 247, 0.1) 0%, transparent 70%);
  bottom: -10%; right: -10%;
  z-index: 0;
  animation: float 10s infinite ease-in-out reverse;
}
@keyframes float {
  0%, 100% { transform: translate(0, 0); }
  50% { transform: translate(30px, 30px); }
}
.login-card {
  background: rgba(30, 41, 59, 0.4);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  padding: 48px 40px;
  border-radius: 24px;
  width: 100%;
  max-width: 380px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5);
  position: relative;
  z-index: 1;
}
.login-header {
  text-align: center;
  margin-bottom: 32px;
}
.logo-icon {
  font-size: 48px;
  display: inline-block;
  background: linear-gradient(135deg, #818cf8, #c084fc);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  filter: drop-shadow(0 0 10px rgba(99, 102, 241, 0.3));
  margin-bottom: 10px;
}
.login-header h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: #fff;
}
.login-header p {
  margin: 8px 0 0;
  color: #94a3b8;
  font-size: 14px;
}
.input-prefix-icon {
  color: #94a3b8;
  font-size: 18px;
}
.login-input {
  height: 48px !important;
  background: rgba(15, 23, 42, 0.5) !important;
  border: 1px solid rgba(255, 255, 255, 0.1) !important;
  border-radius: 12px !important;
  font-size: 15px !important;
  padding: 4px 16px !important;
}
.login-input:hover {
  border-color: rgba(255, 255, 255, 0.2) !important;
}
.login-input:where(.ant-input-affix-wrapper-focused),
.login-input:focus {
  border-color: #6366f1 !important;
  background: rgba(15, 23, 42, 0.8) !important;
  box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.15) !important;
}
.login-input :deep(input) {
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
  color: #fff !important;
  font-size: 15px !important;
  caret-color: #fff !important;
}
.login-input :deep(.ant-input-prefix) {
  margin-inline-end: 12px;
}
.login-input :deep(.ant-input-suffix) {
  color: #94a3b8;
}
.submit-btn {
  width: 100%;
  padding: 14px;
  background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
  color: #fff;
  border: none;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: 0.3s;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-family: inherit;
}
.submit-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 10px 15px -3px rgba(99, 102, 241, 0.4);
}
.submit-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}
.login-form :deep(.ant-form-item) {
  margin-bottom: 20px;
}

.tg-switch {
  text-align: center;
  margin-top: 16px;
  color: #818cf8;
  font-size: 13px;
  cursor: pointer;
  transition: 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.tg-switch:hover {
  color: #a5b4fc;
}
.tg-desc {
  color: #94a3b8;
  font-size: 14px;
  margin-bottom: 24px;
}
.tg-countdown {
  text-align: center;
  color: #64748b;
  font-size: 12px;
  margin-top: 8px;
}
.tg-resend {
  text-align: center;
  color: #818cf8;
  font-size: 13px;
  cursor: pointer;
  margin-top: 8px;
}
.tg-resend:hover {
  color: #a5b4fc;
}

@media (max-width: 480px) {
  .login-card { padding: 36px 24px; margin: 0 16px; }
  .login-header h2 { font-size: 20px; }
}
</style>
