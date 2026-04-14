<template>
  <div>
    <a-tabs v-model:active-key="activeTab">
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

      <a-tab-pane key="security" tab="安全设置">
        <a-card title="MFA 二步验证">
          <a-alert message="MFA 功能将在后续版本实现" type="info" show-icon />
        </a-card>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { message } from 'ant-design-vue'

const activeTab = ref('notify')
const tgConfig = reactive({ botToken: '', chatId: '' })
const dingConfig = reactive({ webhook: '' })

function saveTgConfig() { message.success('保存成功') }
function testTgNotify() { message.info('测试发送功能将在后续版本实现') }
function saveDingConfig() { message.success('保存成功') }
</script>
