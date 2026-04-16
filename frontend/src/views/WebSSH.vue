<template>
  <div class="webssh-container">
    <div class="webssh-toolbar">
      <a-space>
        <a-input v-model:value="websshHost" placeholder="WebSSH 地址" style="width: 260px" size="small">
          <template #addonBefore>
            <a-select v-model:value="websshProtocol" style="width: 80px" size="small">
              <a-select-option value="http://">http</a-select-option>
              <a-select-option value="https://">https</a-select-option>
            </a-select>
          </template>
        </a-input>
        <a-input-number v-model:value="websshPort" placeholder="端口" :min="1" :max="65535" style="width: 100px" size="small" />
        <a-button type="primary" size="small" @click="connectWebSSH">
          <i class="ri-link" style="margin-right: 4px"></i>连接
        </a-button>
        <a-button size="small" @click="openInNewTab">
          <i class="ri-external-link-line" style="margin-right: 4px"></i>新窗口打开
        </a-button>
      </a-space>
      <div class="webssh-tip" v-if="!iframeUrl">
        <a-alert type="info" show-icon>
          <template #message>
            WebSSH 服务默认端口 8008，需在服务器上通过 Docker 启动，详见项目 <code>webssh/</code> 目录
          </template>
        </a-alert>
      </div>
    </div>
    <div class="webssh-frame-wrapper" v-if="iframeUrl">
      <iframe
        ref="iframeRef"
        :src="iframeUrl"
        class="webssh-iframe"
        allow="clipboard-read; clipboard-write"
        sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-modals"
      ></iframe>
    </div>
    <div v-else class="webssh-placeholder">
      <i class="ri-terminal-box-line placeholder-icon"></i>
      <p>输入 WebSSH 服务地址后点击连接</p>
      <p class="placeholder-sub">WebSSH 是一个独立的 Go 服务，通过 Docker 部署在服务器上</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'

const websshProtocol = ref('http://')
const websshHost = ref('')
const websshPort = ref(8008)
const iframeUrl = ref('')
const iframeRef = ref<HTMLIFrameElement>()

onMounted(() => {
  const saved = localStorage.getItem('webssh_url')
  if (saved) {
    try {
      const { protocol, host, port } = JSON.parse(saved)
      websshProtocol.value = protocol || 'http://'
      websshHost.value = host || ''
      websshPort.value = port || 8008
    } catch {}
  }
  if (!websshHost.value) {
    websshHost.value = window.location.hostname
  }
})

function buildUrl() {
  if (!websshHost.value) {
    message.warning('请输入 WebSSH 地址')
    return ''
  }
  return `${websshProtocol.value}${websshHost.value}:${websshPort.value}`
}

function connectWebSSH() {
  const url = buildUrl()
  if (!url) return
  iframeUrl.value = url
  localStorage.setItem('webssh_url', JSON.stringify({
    protocol: websshProtocol.value,
    host: websshHost.value,
    port: websshPort.value,
  }))
  message.success('正在连接 WebSSH...')
}

function openInNewTab() {
  const url = buildUrl()
  if (!url) return
  window.open(url, '_blank')
}
</script>

<style scoped>
.webssh-container {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 120px);
}

.webssh-toolbar {
  margin-bottom: 12px;
  flex-shrink: 0;
}

.webssh-tip {
  margin-top: 10px;
}

.webssh-frame-wrapper {
  flex: 1;
  border-radius: var(--radius-lg);
  overflow: hidden;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-card);
}

.webssh-iframe {
  width: 100%;
  height: 100%;
  border: none;
  background: #1a1b2e;
}

.webssh-placeholder {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-sub);
  border-radius: var(--radius-lg);
  border: 2px dashed var(--border);
}

.placeholder-icon {
  font-size: 64px;
  margin-bottom: 16px;
  opacity: 0.3;
}

.placeholder-sub {
  font-size: 12px;
  opacity: 0.6;
  margin-top: 4px;
}

@media (max-width: 768px) {
  .webssh-container {
    height: calc(100vh - 80px);
  }
  :deep(.ant-space) {
    flex-wrap: wrap;
  }
}
</style>
