<template>
  <div>
    <div class="log-toolbar">
      <a-space>
        <a-badge :status="connected ? 'success' : 'error'" :text="connected ? '已连接' : '未连接'" />
        <a-button @click="toggleConnection">{{ connected ? '断开' : '连接' }}</a-button>
        <a-button @click="clearLogs">清空</a-button>
        <a-switch v-model:checked="autoScroll" checked-children="自动滚动" un-checked-children="手动" />
      </a-space>
    </div>
    <div ref="logContainer" class="log-container">
      <div v-for="(line, i) in logLines" :key="i" class="log-line" :class="getLogClass(line)">
        {{ line }}
      </div>
      <div v-if="!logLines.length" class="log-empty">暂无日志，请连接 WebSocket...</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onUnmounted } from 'vue'

const logLines = ref<string[]>([])
const connected = ref(false)
const autoScroll = ref(true)
const logContainer = ref<HTMLElement>()
let ws: WebSocket | null = null

function toggleConnection() {
  if (connected.value) {
    ws?.close()
    connected.value = false
  } else {
    connect()
  }
}

function connect() {
  const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
  const host = import.meta.env.DEV ? 'localhost:8818' : location.host
  ws = new WebSocket(`${protocol}://${host}/ws/log`)
  ws.onopen = () => { connected.value = true }
  ws.onclose = () => { connected.value = false }
  ws.onmessage = (e) => {
    logLines.value.push(e.data)
    if (logLines.value.length > 5000) logLines.value.splice(0, 1000)
    if (autoScroll.value) {
      nextTick(() => {
        logContainer.value?.scrollTo(0, logContainer.value.scrollHeight)
      })
    }
  }
}

function clearLogs() {
  logLines.value = []
}

function getLogClass(line: string) {
  if (line.includes('ERROR')) return 'log-error'
  if (line.includes('WARN')) return 'log-warn'
  if (line.includes('成功') || line.includes('SUCCESS')) return 'log-success'
  return ''
}

onUnmounted(() => { ws?.close() })
</script>

<style scoped>
.log-toolbar { margin-bottom: 12px; }
.log-container {
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  line-height: 1.6;
  padding: 16px;
  border-radius: 8px;
  height: calc(100vh - 280px);
  overflow-y: auto;
}
.log-line { white-space: pre-wrap; word-break: break-all; }
.log-error { color: #f56c6c; }
.log-warn { color: #e6a23c; }
.log-success { color: #67c23a; }
.log-empty { color: #666; text-align: center; padding: 40px; }
</style>
