<template>
  <div class="webssh-container">
    <div v-if="showSettings" class="webssh-settings">
      <a-card size="small" style="max-width: 400px; margin: 0 auto">
        <template #title>
          <span>WebSSH 服务配置</span>
        </template>
        <a-form layout="vertical" size="small">
          <a-form-item label="服务地址">
            <a-input v-model:value="configHost" placeholder="WebSSH 服务地址" />
          </a-form-item>
          <a-form-item label="端口">
            <a-input-number v-model:value="configPort" :min="1" :max="65535" style="width: 100%" />
          </a-form-item>
          <a-form-item label="协议">
            <a-radio-group v-model:value="configProtocol">
              <a-radio value="http">HTTP</a-radio>
              <a-radio value="https">HTTPS</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-button type="primary" block @click="saveAndConnect">保存并连接</a-button>
        </a-form>
      </a-card>
    </div>
    <iframe
      v-show="!showSettings"
      ref="iframeRef"
      :src="iframeUrl"
      class="webssh-iframe"
      allow="clipboard-read; clipboard-write"
      sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-modals allow-downloads"
      @load="onIframeLoad"
      @error="onIframeError"
    ></iframe>
    <a-button
      class="webssh-settings-btn"
      shape="circle"
      size="small"
      @click="showSettings = !showSettings"
    >
      <i class="ri-settings-3-line"></i>
    </a-button>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'

const iframeRef = ref<HTMLIFrameElement>()
const showSettings = ref(false)
const configHost = ref('')
const configPort = ref(8008)
const configProtocol = ref('http')
const iframeUrl = ref('')

onMounted(() => {
  const saved = localStorage.getItem('webssh_config')
  if (saved) {
    try {
      const c = JSON.parse(saved)
      configHost.value = c.host || ''
      configPort.value = c.port || 8008
      configProtocol.value = c.protocol || 'http'
    } catch {}
  }
  if (!configHost.value) {
    configHost.value = window.location.hostname
    configProtocol.value = window.location.protocol === 'https:' ? 'https' : 'http'
  }
  iframeUrl.value = buildUrl()
})

function buildUrl() {
  return `${configProtocol.value}://${configHost.value}:${configPort.value}`
}

function saveAndConnect() {
  if (!configHost.value) { message.warning('请输入地址'); return }
  localStorage.setItem('webssh_config', JSON.stringify({
    host: configHost.value,
    port: configPort.value,
    protocol: configProtocol.value,
  }))
  iframeUrl.value = buildUrl()
  showSettings.value = false
}

function onIframeLoad() {
  showSettings.value = false
}

function onIframeError() {
  message.error('WebSSH 服务连接失败，请检查配置')
  showSettings.value = true
}
</script>

<style scoped>
.webssh-container {
  position: relative;
  height: calc(100vh - 64px);
  margin: -24px;
}

.webssh-settings {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(8px);
}

.webssh-iframe {
  width: 100%;
  height: 100%;
  border: none;
}

.webssh-settings-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 20;
  opacity: 0.3;
  transition: opacity 0.2s;
}
.webssh-settings-btn:hover {
  opacity: 1;
}
</style>
