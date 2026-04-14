<template>
  <div class="setup-page">
    <div class="bg-glow" />
    <div class="bg-glow-2" />
    <div class="setup-card">
      <div class="setup-header">
        <i class="ri-server-line logo-icon"></i>
        <h2>OCI Worker</h2>
        <p>首次使用，请设置管理员账户</p>
      </div>

      <div class="security-notice">
        <i class="ri-shield-check-line" style="color: #818cf8; font-size: 18px; flex-shrink: 0"></i>
        <span>请设置管理员账户，密码至少6位</span>
      </div>

      <a-form :model="form" @finish="handleSetup" layout="vertical" class="setup-form">
        <a-form-item name="account" :rules="[{ required: true, message: '请设置用户名' }]">
          <a-input v-model:value="form.account" placeholder="设置用户名" size="large" class="setup-input">
            <template #prefix><i class="ri-user-3-line input-prefix-icon"></i></template>
          </a-input>
        </a-form-item>
        <a-form-item name="password" :rules="passwordRules">
          <a-input-password v-model:value="form.password" placeholder="设置密码" size="large" class="setup-input">
            <template #prefix><i class="ri-lock-2-line input-prefix-icon"></i></template>
          </a-input-password>
        </a-form-item>
        <a-form-item name="confirmPassword" :rules="confirmRules">
          <a-input-password v-model:value="form.confirmPassword" placeholder="确认密码" size="large" class="setup-input">
            <template #prefix><i class="ri-lock-2-line input-prefix-icon"></i></template>
          </a-input-password>
        </a-form-item>

        <div class="password-strength" v-if="form.password">
          <div class="strength-bar">
            <div class="strength-fill" :style="{ width: strengthPercent + '%', background: strengthColor }" />
          </div>
          <span :style="{ color: strengthColor }">{{ strengthLabel }}</span>
        </div>

        <a-form-item>
          <button type="submit" :disabled="loading" class="submit-btn">
            <span v-if="loading">初始化中...</span>
            <template v-else>
              完成初始化
              <i class="ri-arrow-right-line"></i>
            </template>
          </button>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useUserStore } from '../stores/user'
import { setupAccount, needSetup } from '../api/auth'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const form = reactive({ account: '', password: '', confirmPassword: '' })

onMounted(async () => {
  try {
    const res = await needSetup()
    if (!res.data) router.replace('/login')
  } catch {
    router.replace('/login')
  }
})

const passwordRules = [
  { required: true, message: '请设置密码' },
  { min: 6, message: '密码至少6个字符' },
]

const confirmRules = [
  { required: true, message: '请确认密码' },
  {
    validator: (_: any, value: string) => {
      if (value && value !== form.password) return Promise.reject('两次密码不一致')
      return Promise.resolve()
    },
    trigger: 'change',
  },
]

const passwordStrength = computed(() => {
  const p = form.password
  if (!p) return 0
  let score = 0
  if (p.length >= 8) score++
  if (p.length >= 12) score++
  if (/[A-Z]/.test(p)) score++
  if (/[a-z]/.test(p)) score++
  if (/\d/.test(p)) score++
  if (/[!@#$%^&*]/.test(p)) score++
  return score
})

const strengthPercent = computed(() => Math.min(100, (passwordStrength.value / 6) * 100))
const strengthColor = computed(() => {
  const s = passwordStrength.value
  if (s <= 2) return '#ef4444'
  if (s <= 4) return '#f59e0b'
  return '#10b981'
})
const strengthLabel = computed(() => {
  const s = passwordStrength.value
  if (s <= 2) return '弱'
  if (s <= 4) return '中'
  return '强'
})

async function handleSetup() {
  if (form.password !== form.confirmPassword) {
    message.error('两次密码不一致')
    return
  }
  loading.value = true
  try {
    const res = await setupAccount({ account: form.account, password: form.password })
    userStore.setToken(res.data.token)
    message.success('初始化完成，欢迎使用 OCI Worker！')
    router.push('/')
  } catch {
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.setup-page {
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
.setup-card {
  background: rgba(30, 41, 59, 0.4);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  padding: 48px 40px;
  border-radius: 24px;
  width: 100%;
  max-width: 420px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5);
  position: relative;
  z-index: 1;
}
.setup-header {
  text-align: center;
  margin-bottom: 24px;
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
.setup-header h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: #fff;
}
.setup-header p {
  margin: 8px 0 0;
  color: #94a3b8;
  font-size: 14px;
}
.security-notice {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: rgba(99, 102, 241, 0.08);
  border: 1px solid rgba(99, 102, 241, 0.2);
  border-radius: 12px;
  font-size: 13px;
  color: #94a3b8;
  margin-bottom: 24px;
}
.input-prefix-icon {
  color: #94a3b8;
  font-size: 18px;
}
.setup-input {
  height: 48px !important;
  background: rgba(15, 23, 42, 0.5) !important;
  border: 1px solid rgba(255, 255, 255, 0.1) !important;
  border-radius: 12px !important;
  font-size: 15px !important;
  padding: 4px 16px !important;
}
.setup-input:hover {
  border-color: rgba(255, 255, 255, 0.2) !important;
}
.setup-input:where(.ant-input-affix-wrapper-focused),
.setup-input:focus {
  border-color: #6366f1 !important;
  background: rgba(15, 23, 42, 0.8) !important;
  box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.15) !important;
}
.setup-input :deep(input) {
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
  color: #fff !important;
  font-size: 15px !important;
  caret-color: #fff !important;
}
.setup-input :deep(.ant-input-prefix) {
  margin-inline-end: 12px;
}
.setup-input :deep(.ant-input-suffix) {
  color: #94a3b8;
}
.password-strength {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}
.strength-bar {
  flex: 1;
  height: 4px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 2px;
  overflow: hidden;
}
.strength-fill {
  height: 100%;
  border-radius: 2px;
  transition: width 0.3s, background 0.3s;
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
  margin-top: 8px;
}
.submit-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 10px 15px -3px rgba(99, 102, 241, 0.4);
}
.submit-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}
.setup-form :deep(.ant-form-item) {
  margin-bottom: 20px;
}
@media (max-width: 480px) {
  .setup-card { padding: 36px 24px; margin: 0 16px; }
  .setup-header h2 { font-size: 20px; }
}
</style>
