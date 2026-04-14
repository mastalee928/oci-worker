<template>
  <div>
    <div class="log-toolbar">
      <a-space wrap>
        <a-badge :status="connected ? 'success' : 'error'" :text="connected ? '已连接' : '未连接'" />
        <a-button @click="toggleConnection">{{ connected ? '断开' : '连接' }}</a-button>
        <a-button @click="clearLogs">清空</a-button>
        <a-switch v-model:checked="autoScroll" checked-children="自动滚动" un-checked-children="手动" />
        <a-divider type="vertical" />
        <a-input-search
          v-model:value="searchKeyword"
          placeholder="搜索日志关键词..."
          style="width: 300px"
          allow-clear
          @search="handleSearch"
          :loading="searchLoading"
          enter-button="搜索"
        />
        <a-button v-if="isSearchMode" @click="exitSearch">返回实时</a-button>
      </a-space>
    </div>
    <div v-if="isSearchMode" style="margin-bottom: 8px">
      <a-tag color="blue">搜索结果: {{ searchResults.length }} 条</a-tag>
      <a-tag>关键词: {{ activeSearchKeyword }}</a-tag>
    </div>
    <div ref="logContainer" class="log-container">
      <div v-if="isSearchMode" v-for="(line, i) in displayLines" :key="'s'+i" class="log-line" :class="getLogClass(line)"
           v-html="highlightText(line)"></div>
      <div v-else v-for="(line, i) in displayLines" :key="'r'+i" class="log-line" :class="getLogClass(line)">{{ line }}</div>
      <div v-if="!displayLines.length" class="log-empty">
        {{ isSearchMode ? '未找到匹配日志' : '等待日志数据...' }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'
import request from '../utils/request'

const logLines = ref<string[]>([])
const connected = ref(false)
const autoScroll = ref(true)
const logContainer = ref<HTMLElement>()
let ws: WebSocket | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let manualDisconnect = false

const searchKeyword = ref('')
const activeSearchKeyword = ref('')
const searchResults = ref<string[]>([])
const searchLoading = ref(false)
const isSearchMode = ref(false)

const displayLines = computed(() => {
  return isSearchMode.value ? searchResults.value : logLines.value
})

function getWsUrl() {
  const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
  const host = import.meta.env.DEV ? 'localhost:8818' : location.host
  return `${protocol}://${host}/ws/log`
}

function connect() {
  if (ws && ws.readyState <= WebSocket.OPEN) return
  manualDisconnect = false

  ws = new WebSocket(getWsUrl())
  ws.onopen = () => {
    connected.value = true
    stopReconnect()
  }
  ws.onclose = () => {
    connected.value = false
    if (!manualDisconnect) scheduleReconnect()
  }
  ws.onerror = () => {
    connected.value = false
  }
  ws.onmessage = (e) => {
    logLines.value.push(e.data)
    if (logLines.value.length > 10000) logLines.value.splice(0, 2000)
    if (autoScroll.value && !isSearchMode.value) {
      nextTick(() => {
        logContainer.value?.scrollTo(0, logContainer.value.scrollHeight)
      })
    }
  }
}

function disconnect() {
  manualDisconnect = true
  stopReconnect()
  ws?.close()
  ws = null
  connected.value = false
}

function toggleConnection() {
  if (connected.value) {
    disconnect()
  } else {
    connect()
  }
}

function scheduleReconnect() {
  stopReconnect()
  reconnectTimer = setTimeout(() => connect(), 3000)
}

function stopReconnect() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

function clearLogs() {
  logLines.value = []
}

async function handleSearch(value: string) {
  const kw = value?.trim() || searchKeyword.value?.trim()
  if (!kw) {
    message.warning('请输入搜索关键词')
    return
  }
  searchLoading.value = true
  try {
    const res = await request.post('/log/search', { keyword: kw })
    searchResults.value = res.data || []
    activeSearchKeyword.value = kw
    isSearchMode.value = true
    nextTick(() => {
      logContainer.value?.scrollTo(0, logContainer.value.scrollHeight)
    })
  } catch (e: any) {
    message.error(e?.message || '搜索失败')
  } finally {
    searchLoading.value = false
  }
}

function exitSearch() {
  isSearchMode.value = false
  searchResults.value = []
  activeSearchKeyword.value = ''
  nextTick(() => {
    logContainer.value?.scrollTo(0, logContainer.value.scrollHeight)
  })
}

function highlightText(line: string) {
  if (!activeSearchKeyword.value) return escapeHtml(line)
  const escaped = escapeHtml(line)
  const kwEscaped = escapeHtml(activeSearchKeyword.value)
  const regex = new RegExp(`(${kwEscaped.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi')
  return escaped.replace(regex, '<span class="log-highlight">$1</span>')
}

function escapeHtml(text: string) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function getLogClass(line: string) {
  if (line.includes('ERROR')) return 'log-error'
  if (line.includes('WARN')) return 'log-warn'
  if (line.includes('成功') || line.includes('SUCCESS')) return 'log-success'
  return ''
}

onMounted(() => connect())
onUnmounted(() => disconnect())
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
:deep(.log-highlight) {
  background: #e6a23c;
  color: #1e1e1e;
  padding: 0 2px;
  border-radius: 2px;
  font-weight: bold;
}
</style>
