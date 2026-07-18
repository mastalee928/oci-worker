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
      <a-tag color="blue">搜索结果: {{ searchTotal }} 条</a-tag>
      <a-tag>关键词: {{ activeSearchKeyword }}</a-tag>
      <a-tag v-if="searchBackendLimited">仅返回最近 {{ searchLimit }} 条</a-tag>
      <a-tag v-else-if="searchResults.length > displayRows.length">仅显示最近 {{ displayRows.length }} 条</a-tag>
    </div>
    <div ref="logContainer" class="log-container">
      <div v-if="isSearchMode" v-for="row in displayRows" :key="row.key" class="log-line" :class="getLogClass(row.line)"
           v-html="highlightText(row.line)"></div>
      <div v-else v-for="row in displayRows" :key="row.key" class="log-line" :class="getLogClass(row.line)">{{ row.line }}</div>
      <div v-if="!displayRows.length" class="log-empty">
        {{ isSearchMode ? '未找到匹配日志' : '等待日志数据...' }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'LogViewer' })
import { ref, computed, nextTick, onMounted, onActivated, onDeactivated, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'
import request from '../utils/request'

interface RealtimeLogLine {
  seq: number
  text: string
}

interface LogDisplayRow {
  key: string
  line: string
}

const logLines = ref<RealtimeLogLine[]>([])
const connected = ref(false)
const autoScroll = ref(true)
const logContainer = ref<HTMLElement>()
let ws: WebSocket | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let scrollRaf: number | null = null
let manualDisconnect = false
let viewActive = false
const shouldAutoConnect = ref(true)

const searchKeyword = ref('')
const activeSearchKeyword = ref('')
const searchResults = ref<string[]>([])
const searchTotal = ref(0)
const searchBackendLimited = ref(false)
const searchLimit = ref(0)
const searchLoading = ref(false)
const isSearchMode = ref(false)
const REALTIME_RENDER_LIMIT = 1500
const SEARCH_RENDER_LIMIT = 1000
const SEARCH_REQUEST_LIMIT = 1000
let logSeq = 0
let searchRequestSeq = 0

const displayRows = computed<LogDisplayRow[]>(() => {
  if (isSearchMode.value) {
    const source = searchResults.value
    const start = Math.max(source.length - SEARCH_RENDER_LIMIT, 0)
    return source.slice(start).map((line, index) => ({ key: `s-${start + index}`, line }))
  }
  const source = logLines.value
  const start = Math.max(source.length - REALTIME_RENDER_LIMIT, 0)
  return source.slice(start).map((item) => ({ key: `r-${item.seq}`, line: item.text }))
})

function getWsUrl() {
  const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${protocol}://${location.host}/ws/log`
}

function connect() {
  if (!viewActive || !shouldAutoConnect.value) return
  if (ws && ws.readyState <= WebSocket.OPEN) return
  manualDisconnect = false

  const socket = new WebSocket(getWsUrl())
  ws = socket
  socket.onopen = () => {
    if (ws !== socket) return
    connected.value = true
    stopReconnect()
  }
  socket.onclose = () => {
    if (ws !== socket) return
    connected.value = false
    if (!manualDisconnect && viewActive && shouldAutoConnect.value) scheduleReconnect()
  }
  socket.onerror = () => {
    if (ws !== socket) return
    connected.value = false
  }
  socket.onmessage = (e) => {
    if (ws !== socket || !viewActive) return
    logLines.value.push({ seq: ++logSeq, text: String(e.data) })
    if (logLines.value.length > 10000) logLines.value.splice(0, 2000)
    if (autoScroll.value && !isSearchMode.value) {
      scheduleScrollToBottom()
    }
  }
}

function disconnect(manual = true) {
  if (manual) shouldAutoConnect.value = false
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
    shouldAutoConnect.value = true
    connect()
  }
}

function scheduleReconnect() {
  if (!viewActive || !shouldAutoConnect.value) return
  stopReconnect()
  reconnectTimer = setTimeout(() => connect(), 3000)
}

function stopReconnect() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

function cancelScheduledScroll() {
  if (scrollRaf != null) {
    cancelAnimationFrame(scrollRaf)
    scrollRaf = null
  }
}

function scheduleScrollToBottom() {
  if (!viewActive || scrollRaf != null) return
  scrollRaf = requestAnimationFrame(() => {
    scrollRaf = null
    if (!viewActive || !autoScroll.value || isSearchMode.value) return
    const el = logContainer.value
    if (el) el.scrollTo(0, el.scrollHeight)
  })
}

function clearLogs() {
  logLines.value = []
  logSeq = 0
}

async function handleSearch(value: string) {
  const kw = value?.trim() || searchKeyword.value?.trim()
  if (!kw) {
    message.warning('请输入搜索关键词')
    return
  }
  const requestSeq = ++searchRequestSeq
  searchLoading.value = true
  try {
    const res = await request.post('/log/search', { keyword: kw, limit: SEARCH_REQUEST_LIMIT })
    if (requestSeq !== searchRequestSeq) return
    const payload = res.data
    if (Array.isArray(payload)) {
      searchResults.value = payload
      searchTotal.value = payload.length
      searchBackendLimited.value = false
      searchLimit.value = SEARCH_RENDER_LIMIT
    } else {
      const records = Array.isArray(payload?.records) ? payload.records : []
      searchResults.value = records
      searchTotal.value = Number(payload?.total ?? records.length) || 0
      searchBackendLimited.value = payload?.limited === true
      searchLimit.value = Number(payload?.limit ?? SEARCH_REQUEST_LIMIT) || SEARCH_REQUEST_LIMIT
    }
    activeSearchKeyword.value = kw
    isSearchMode.value = true
    nextTick(() => {
      logContainer.value?.scrollTo(0, logContainer.value.scrollHeight)
    })
  } catch (e: any) {
    if (requestSeq === searchRequestSeq) {
      message.error(e?.message || '搜索失败')
    }
  } finally {
    if (requestSeq === searchRequestSeq) {
      searchLoading.value = false
    }
  }
}

function exitSearch() {
  searchRequestSeq++
  isSearchMode.value = false
  searchResults.value = []
  searchTotal.value = 0
  searchBackendLimited.value = false
  searchLimit.value = 0
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

function activateLogViewer() {
  viewActive = true
  connect()
}

function deactivateLogViewer() {
  viewActive = false
  cancelScheduledScroll()
  disconnect(false)
}

onMounted(activateLogViewer)
onActivated(activateLogViewer)
onDeactivated(deactivateLogViewer)

onUnmounted(() => {
  viewActive = false
  cancelScheduledScroll()
  disconnect(false)
})
</script>

<style scoped>
.log-toolbar { margin-bottom: 12px; transition: var(--trans); }
.log-container {
  background: var(--input-bg);
  color: var(--text-main);
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  line-height: 1.6;
  padding: 16px;
  border-radius: var(--radius-sm);
  height: calc(100vh - 280px);
  overflow-y: auto;
  overflow-x: hidden;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: var(--trans);
}
.log-line { white-space: pre-wrap; word-break: break-all; }
.log-error { color: var(--danger-text); }
.log-warn { color: var(--warning-text); }
.log-success { color: var(--success-text); }
.log-empty { color: var(--text-sub); text-align: center; padding: 40px; }
:deep(.log-highlight) {
  background: var(--warning-bg);
  color: var(--text-main);
  padding: 0 2px;
  border-radius: 2px;
  font-weight: bold;
}

@media (max-width: 768px) {
  .log-toolbar :deep(.ant-space) {
    flex-wrap: wrap;
    gap: 6px !important;
  }
  .log-toolbar :deep(.ant-input-search) {
    width: 100% !important;
  }
  .log-toolbar :deep(.ant-divider-vertical) {
    display: none;
  }
  .log-container {
    font-size: 11px;
    line-height: 1.5;
    padding: 10px;
    height: calc(100vh - 220px);
  }
}
</style>
