<template>
  <div class="setup-page">
    <div class="setup-glow" />
    <div class="setup-card">
      <div class="setup-brand">
        <div class="brand-icon">⚡</div>
        <h1>OCI Worker</h1>
        <p>首次使用，请设置管理员账户</p>
      </div>

      <div class="security-notice">
        <SafetyCertificateOutlined style="color: #18E299; font-size: 16px" />
        <span>请设置管理员账户，密码至少6位</span>
      </div>

      <a-form :model="form" @finish="handleSetup" layout="vertical" class="setup-form">
        <a-form-item name="account" :rules="[{ required: true, message: '请设置用户名' }]">
          <a-input v-model:value="form.account" placeholder="设置用户名" size="large" class="setup-input">
            <template #prefix><UserOutlined style="color: #888" /></template>
          </a-input>
        </a-form-item>
        <a-form-item name="password" :rules="passwordRules">
          <a-input-password v-model:value="form.password" placeholder="设置密码" size="large" class="setup-input">
            <template #prefix><LockOutlined style="color: #888" /></template>
          </a-input-password>
        </a-form-item>
        <a-form-item name="confirmPassword" :rules="confirmRules">
          <a-input-password v-model:value="form.confirmPassword" placeholder="确认密码" size="large" class="setup-input">
            <template #prefix><LockOutlined style="color: #888" /></template>
          </a-input-password>
        </a-form-item>

        <div class="password-strength" v-if="form.password">
          <div class="strength-bar">
            <div class="strength-fill" :style="{ width: strengthPercent + '%', background: strengthColor }" />
          </div>
          <span :style="{ color: strengthColor }">{{ strengthLabel }}</span>
        </div>

        <a-form-item>
          <a-button type="primary" html-type="submit" :loading="loading" block size="large" class="setup-btn">
            完成初始化
          </a-button>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { UserOutlined, LockOutlined, SafetyCertificateOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { useUserStore } from '../stores/user'
import { setupAccount, needSetup } from '../api/auth'
import { onMounted } from 'vue'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const form = reactive({ account: '', password: '', confirmPassword: '' })

onMounted(async () => {
  try {
    const res = await needSetup()
    if (!res.data) {
      router.replace('/login')
    }
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
  if (s <= 2) return '#ff4d4f'
  if (s <= 4) return '#faad14'
  return '#18E299'
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
  background: #fafafa;
  position: relative;
  overflow: hidden;
}
.setup-glow {
  position: absolute;
  top: -200px;
  left: 50%;
  transform: translateX(-50%);
  width: 800px;
  height: 600px;
  background: radial-gradient(ellipse, rgba(24, 226, 153, 0.12) 0%, rgba(24, 226, 153, 0.03) 50%, transparent 70%);
  pointer-events: none;
}
.setup-card {
  width: 460px;
  max-width: calc(100vw - 32px);
  padding: 48px 40px;
  background: #fff;
  border-radius: 24px;
  border: 1px solid rgba(0,0,0,0.05);
  box-shadow: 0 4px 24px rgba(0,0,0,0.06);
  position: relative;
  z-index: 1;
}
.setup-brand {
  text-align: center;
  margin-bottom: 24px;
}
.brand-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  background: linear-gradient(135deg, #18E299, #0fa76e);
  border-radius: 16px;
  box-shadow: 0 4px 16px rgba(24, 226, 153, 0.25);
}
.setup-brand h1 {
  font-size: 26px;
  font-weight: 600;
  color: #0d0d0d;
  letter-spacing: -0.5px;
  margin: 0 0 4px;
}
.setup-brand p {
  color: #888;
  font-size: 14px;
  margin: 0;
}
.security-notice {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: rgba(24, 226, 153, 0.06);
  border: 1px solid rgba(24, 226, 153, 0.15);
  border-radius: 12px;
  font-size: 13px;
  color: #555;
  margin-bottom: 24px;
}
.setup-input { border-radius: 12px !important; height: 48px; }
.setup-btn {
  height: 48px !important;
  font-size: 16px !important;
  font-weight: 600 !important;
  border-radius: 9999px !important;
  margin-top: 8px;
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
  background: #eee;
  border-radius: 2px;
  overflow: hidden;
}
.strength-fill {
  height: 100%;
  border-radius: 2px;
  transition: width 0.3s, background 0.3s;
}
@media (max-width: 480px) {
  .setup-card { padding: 32px 24px; border-radius: 20px; }
  .setup-brand h1 { font-size: 22px; }
}
</style>
