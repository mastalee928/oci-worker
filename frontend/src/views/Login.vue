<template>
  <div class="login-page">
    <div class="login-glow" />
    <div class="login-card">
      <div class="login-brand">
        <div class="brand-icon">⚡</div>
        <h1>OCI Worker</h1>
        <p>Oracle Cloud 实例管理面板</p>
      </div>
      <a-form :model="form" @finish="handleLogin" layout="vertical" class="login-form">
        <a-form-item name="account" :rules="[{ required: true, message: '请输入账号' }]">
          <a-input v-model:value="form.account" placeholder="账号" size="large" class="login-input">
            <template #prefix><UserOutlined style="color: #888" /></template>
          </a-input>
        </a-form-item>
        <a-form-item name="password" :rules="[{ required: true, message: '请输入密码' }]">
          <a-input-password v-model:value="form.password" placeholder="密码" size="large" class="login-input">
            <template #prefix><LockOutlined style="color: #888" /></template>
          </a-input-password>
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" :loading="loading" block size="large" class="login-btn">
            登录
          </a-button>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { useUserStore } from '../stores/user'
import { login, needSetup } from '../api/auth'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const form = reactive({ account: '', password: '' })

onMounted(async () => {
  try {
    const res = await needSetup()
    if (res.data) {
      router.replace('/setup')
    }
  } catch {}
})

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
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fafafa;
  position: relative;
  overflow: hidden;
}
.login-glow {
  position: absolute;
  top: -200px;
  left: 50%;
  transform: translateX(-50%);
  width: 800px;
  height: 600px;
  background: radial-gradient(ellipse, rgba(24, 226, 153, 0.12) 0%, rgba(24, 226, 153, 0.03) 50%, transparent 70%);
  pointer-events: none;
}
.login-card {
  width: 420px;
  max-width: calc(100vw - 32px);
  padding: 48px 40px;
  background: #fff;
  border-radius: 24px;
  border: 1px solid rgba(0,0,0,0.05);
  box-shadow: 0 4px 24px rgba(0,0,0,0.06);
  position: relative;
  z-index: 1;
}
.login-brand {
  text-align: center;
  margin-bottom: 36px;
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
.login-brand h1 {
  font-size: 26px;
  font-weight: 600;
  color: #0d0d0d;
  letter-spacing: -0.5px;
  margin: 0 0 4px;
}
.login-brand p {
  color: #888;
  font-size: 14px;
  margin: 0;
}
.login-input { border-radius: 12px !important; height: 48px; }
.login-btn {
  height: 48px !important;
  font-size: 16px !important;
  font-weight: 600 !important;
  border-radius: 9999px !important;
}

@media (max-width: 480px) {
  .login-card { padding: 32px 24px; border-radius: 20px; }
  .login-brand h1 { font-size: 22px; }
}
</style>
