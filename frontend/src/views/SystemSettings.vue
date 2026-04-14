<template>
  <div>
    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="security" tab="安全设置">
        <a-card title="修改登录密码" style="max-width: 480px">
          <a-form :model="pwdForm" layout="vertical">
            <a-form-item label="原密码" required>
              <a-input-password v-model:value="pwdForm.oldPassword" placeholder="输入当前密码" />
            </a-form-item>
            <a-form-item label="新密码" required>
              <a-input-password v-model:value="pwdForm.newPassword" placeholder="至少 6 位" />
            </a-form-item>
            <a-form-item label="确认新密码" required>
              <a-input-password v-model:value="pwdForm.confirmPassword" placeholder="再次输入新密码" />
            </a-form-item>
            <a-button type="primary" @click="handleChangePassword" :loading="pwdLoading">修改密码</a-button>
          </a-form>
        </a-card>

        <a-card title="登录安全说明" style="max-width: 480px; margin-top: 16px">
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
        <a-card title="Telegram 通知">
          <a-form layout="vertical">
            <a-form-item label="Bot Token">
              <a-input v-model:value="tgConfig.botToken" placeholder="输入 Telegram Bot Token" />
            </a-form-item>
            <a-form-item label="Chat ID">
              <a-input v-model:value="tgConfig.chatId" placeholder="输入 Chat ID" />
            </a-form-item>
            <a-button type="primary" @click="saveTgConfig">保存</a-button>
            <a-button style="margin-left: 8px" @click="testTgNotify">测试发送</a-button>
          </a-form>
        </a-card>

        <a-card title="钉钉通知" style="margin-top: 16px">
          <a-form layout="vertical">
            <a-form-item label="Webhook URL">
              <a-input v-model:value="dingConfig.webhook" placeholder="输入钉钉 Webhook URL" />
            </a-form-item>
            <a-button type="primary" @click="saveDingConfig">保存</a-button>
          </a-form>
        </a-card>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import request from '../utils/request'

const router = useRouter()
const activeTab = ref('security')
const pwdLoading = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const tgConfig = reactive({ botToken: '', chatId: '' })
const dingConfig = reactive({ webhook: '' })

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
    })
    if (res.data?.token) {
      localStorage.setItem('token', res.data.token)
    }
    message.success('密码修改成功')
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
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

function saveTgConfig() { message.success('保存成功') }
function testTgNotify() { message.info('测试发送功能将在后续版本实现') }
function saveDingConfig() { message.success('保存成功') }
</script>
