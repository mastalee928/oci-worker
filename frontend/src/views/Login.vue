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
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
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
    if (res.data) router.replace('/setup')
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

@media (max-width: 480px) {
  .login-card { padding: 36px 24px; margin: 0 16px; }
  .login-header h2 { font-size: 20px; }
}
</style>
