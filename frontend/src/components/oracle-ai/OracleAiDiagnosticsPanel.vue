<template>
  <div class="oracle-ai-diagnostics">
    <div class="diagnostic-head">
      <a-segmented v-model:value="activeSection" :options="sectionOptions" />
      <a-space class="diagnostic-actions" wrap>
        <a-button :loading="activeLoading" @click="refreshActive">刷新</a-button>
        <a-switch v-if="activeSection === 'requests'" v-model:checked="autoRefresh" />
      </a-space>
    </div>

    <a-card v-if="activeSection === 'requests'" title="请求日志" :bordered="false" class="diagnostic-card">
      <div class="filter-bar">
        <a-select v-model:value="requestFilters.limit" :options="limitOptions" class="filter-small" :get-popup-container="popupContainer" />
        <a-select v-model:value="requestFilters.status" :options="statusOptions" class="filter-small" :get-popup-container="popupContainer" />
        <a-select
          v-model:value="requestFilters.memberId"
          :options="memberOptions"
          class="filter-member"
          allow-clear
          placeholder="成员"
          :show-search="false"
          :get-popup-container="popupContainer"
        />
        <a-select v-model:value="requestFilters.keywordType" :options="keywordTypeOptions" class="filter-small" :get-popup-container="popupContainer" />
        <a-input
          v-model:value="requestFilters.keyword"
          class="filter-keyword"
          allow-clear
          placeholder="输入关键词"
          @press-enter="loadRequests"
        />
        <a-select v-model:value="requestFilters.hasTools" :options="booleanOptions" class="filter-small" :get-popup-container="popupContainer" />
        <a-select v-model:value="requestFilters.clientAborted" :options="abortOptions" class="filter-small" :get-popup-container="popupContainer" />
        <a-button type="primary" :loading="requestsLoading" @click="loadRequests">查询</a-button>
      </div>

      <a-spin v-if="!isMobile && requestsLoading && !requests.length" />
      <a-empty v-else-if="!isMobile && !requests.length" description="暂无日志" />
      <template v-else-if="!isMobile">
        <div class="table-wrap">
          <table class="table-wide">
            <thead>
              <tr>
                <th>时间/请求</th>
                <th>成员</th>
                <th>模型</th>
                <th>协议</th>
                <th>状态</th>
                <th>耗时</th>
                <th>Tokens</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="record in requestPageRows" :key="record.id">
                <td>
                  {{ formatTime(record.createTime) }}
                  <span class="muted-block"><code>{{ shortId(record.requestId) }}</code></span>
                </td>
                <td>
                  <strong>{{ requestMemberName(record) }}</strong>
                  <span class="muted-block">{{ requestMemberMeta(record) }}</span>
                </td>
                <td>
                  <a-tooltip :title="record.model">
                    <span class="model-summary">{{ record.model || '-' }}</span>
                  </a-tooltip>
                </td>
                <td>
                  {{ requestPathText(record.requestPath) }}
                  <span class="muted-block">
                    {{ record.stream ? 'stream' : 'json' }} · tools {{ record.toolCount || 0 }}
                    <span v-if="record.bridgeType"> · {{ record.bridgeType }}</span>
                  </span>
                </td>
                <td>
                  <a-tag :color="requestStatusColor(record)">{{ requestStatusText(record) }}</a-tag>
                  <span v-if="record.errorType" class="muted-block">{{ record.errorType }}</span>
                </td>
                <td>
                  {{ formatMs(record.latencyMs) }}
                  <span v-if="record.firstChunkMs" class="muted-block">首块 {{ formatMs(record.firstChunkMs) }}</span>
                </td>
                <td>
                  {{ record.tokenCount || 0 }}
                  <span class="muted-block">估 {{ record.estimatedPromptTokens || 0 }}</span>
                </td>
                <td><a-button size="small" @click="openRequestDetail(record)">详情</a-button></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <span>共 {{ requests.length }} 条</span>
          <button
            v-for="page in requestPages"
            :key="page"
            type="button"
            :class="{ 'page-chip': page === requestPage }"
            @click="requestPage = page"
          >
            {{ page }}
          </button>
        </div>
      </template>

      <div v-else class="request-card-list">
        <a-spin v-if="requestsLoading" />
        <a-empty v-if="!requests.length && !requestsLoading" description="暂无日志" />
        <div v-for="record in requests" :key="record.id" class="request-card" @click="openRequestDetail(record)">
          <div class="request-card-head">
            <div>
              <div class="strong-line">{{ requestMemberName(record) }}</div>
              <code>{{ shortId(record.requestId) }}</code>
            </div>
            <a-tag :color="requestStatusColor(record)">{{ requestStatusText(record) }}</a-tag>
          </div>
          <div class="request-card-grid">
            <span>模型</span><b>{{ record.model || '-' }}</b>
            <span>协议</span><b>{{ record.requestPath || '-' }}</b>
            <span>工具</span><b>{{ record.toolCount || 0 }}</b>
            <span>耗时</span><b>{{ formatMs(record.latencyMs) }}</b>
            <span>时间</span><b>{{ formatTime(record.createTime) }}</b>
          </div>
        </div>
      </div>
    </a-card>

    <a-card v-else-if="activeSection === 'health'" title="健康诊断" :bordered="false" class="diagnostic-card">
      <div class="metric-grid">
        <div class="metric-item">
          <span>监听</span>
          <b>{{ health.running ? '正常' : '未监听' }}</b>
        </div>
        <div class="metric-item">
          <span>端口</span>
          <b>{{ health.port || '-' }}</b>
        </div>
        <div class="metric-item">
          <span>成员</span>
          <b>{{ health.enabledMemberCount || 0 }}/{{ health.memberCount || 0 }}</b>
        </div>
        <div class="metric-item">
          <span>健康</span>
          <b>{{ health.healthyMemberCount || 0 }}</b>
        </div>
        <div class="metric-item">
          <span>并发</span>
          <b>{{ health.inFlight || 0 }}</b>
        </div>
        <div class="metric-item">
          <span>失败</span>
          <b>{{ health.recentFailureCount || 0 }}</b>
        </div>
      </div>
      <a-spin v-if="healthLoading && !healthMembers.length" />
      <a-empty v-else-if="!healthMembers.length" description="暂无健康数据" />
      <div v-else-if="!isMobile" class="table-wrap">
        <table class="table-mid">
          <thead>
            <tr>
              <th>成员</th>
              <th>区域</th>
              <th>健康</th>
              <th>并发</th>
              <th>最近状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="record in healthMembers" :key="record.memberId">
              <td>
                <strong>{{ record.bindingName || record.memberId || '-' }}</strong>
                <span class="muted-block">{{ record.port || '-' }} · {{ record.tenantName || record.tenantUsername || '-' }}</span>
              </td>
              <td>{{ record.ociRegion || record.tenantDefaultRegion || '-' }}</td>
              <td>
                <a-tag :color="healthColor(record.healthStatus)">{{ healthText(record.healthStatus) }}</a-tag>
                <span v-if="record.healthMessage" class="muted-block status-message">{{ record.healthMessage }}</span>
              </td>
              <td>{{ record.inFlight || 0 }}</td>
              <td>
                {{ record.lastStatus ? `HTTP ${record.lastStatus}` : '-' }}
                <span v-if="record.lastErrorType" class="muted-block">{{ record.lastErrorType }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="health-card-list">
        <div v-for="(record, idx) in healthMembers" :key="record.memberId || record.port || record.bindingName || idx" class="health-card">
          <div class="health-card-head">
            <div class="health-card-title">
              <strong>{{ record.bindingName || record.memberId || '-' }}</strong>
              <span>{{ record.port || '-' }} · {{ record.tenantName || record.tenantUsername || '-' }}</span>
            </div>
            <a-tag :color="healthColor(record.healthStatus)">{{ healthText(record.healthStatus) }}</a-tag>
          </div>
          <div class="health-card-grid">
            <span>区域</span><b>{{ record.ociRegion || record.tenantDefaultRegion || '-' }}</b>
            <span>并发</span><b>{{ record.inFlight || 0 }}</b>
            <span>最近状态</span><b>{{ record.lastStatus ? `HTTP ${record.lastStatus}` : '-' }}</b>
            <span v-if="record.lastErrorType">错误类型</span><b v-if="record.lastErrorType">{{ record.lastErrorType }}</b>
          </div>
          <div v-if="record.healthMessage" class="health-card-message">{{ record.healthMessage }}</div>
        </div>
      </div>
    </a-card>

    <a-card v-else title="模型来源" :bordered="false" class="diagnostic-card">
      <div class="filter-bar">
        <a-input v-model:value="modelAccount" class="filter-keyword" allow-clear placeholder="指定成员/端口/租户，可留空" />
        <a-switch v-model:checked="forceModelRefresh" />
        <span class="sub-line">强制刷新</span>
        <a-button type="primary" :loading="modelsLoading" @click="loadModels">查询</a-button>
      </div>
      <a-spin v-if="modelsLoading && !modelRows.length" />
      <a-empty v-else-if="!modelRows.length" description="暂无模型来源" />
      <div v-else-if="!isMobile" class="table-wrap">
        <table class="table-mid">
          <thead>
            <tr>
              <th>模型</th>
              <th>能力</th>
              <th>来源</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="record in modelRows" :key="record.id">
              <td>
                <a-tooltip :title="record.id">
                  <span class="model-id">{{ record.id }}</span>
                </a-tooltip>
              </td>
              <td><a-tag :color="capabilityColor(record.ociworkerCapability)">{{ record.ociworkerCapability || '-' }}</a-tag></td>
              <td>{{ sourceCount(record) }}</td>
              <td><a-button size="small" @click="openModelDetail(record)">来源</a-button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="model-card-list">
        <div v-for="record in modelRows" :key="record.id" class="model-card">
          <div class="model-card-head">
            <span class="model-card-id">{{ record.id }}</span>
            <a-tag :color="capabilityColor(record.ociworkerCapability)">{{ record.ociworkerCapability || '-' }}</a-tag>
          </div>
          <div class="model-card-grid">
            <span>来源</span><b>{{ sourceCount(record) }}</b>
          </div>
          <a-button size="small" block @click="openModelDetail(record)">查看来源</a-button>
        </div>
      </div>
      <div v-if="modelErrors.length" class="model-error-list">
        <div v-for="(item, idx) in modelErrors" :key="idx" class="model-error-row">
          <a-tag color="red">错误</a-tag>
          <span>{{ sourceTitle(item) }}</span>
          <code>{{ item.error }}</code>
        </div>
      </div>
    </a-card>

    <a-drawer
      v-model:open="requestDetailOpen"
      title="请求详情"
      :width="drawerWidth"
      placement="right"
      destroy-on-close
    >
      <a-descriptions v-if="requestDetail" bordered size="small" :column="1">
        <a-descriptions-item label="Request ID"><code>{{ requestDetail.requestId || '-' }}</code></a-descriptions-item>
        <a-descriptions-item label="成员">{{ requestMemberName(requestDetail) }}</a-descriptions-item>
        <a-descriptions-item label="成员 ID"><code>{{ requestDetail.memberId || '-' }}</code></a-descriptions-item>
        <a-descriptions-item label="端口绑定"><code>{{ requestDetail.portBindingId || '-' }}</code></a-descriptions-item>
        <a-descriptions-item label="模型">{{ requestDetail.model || '-' }}</a-descriptions-item>
        <a-descriptions-item label="协议">{{ requestDetail.requestPath || '-' }} / {{ requestDetail.stream ? 'stream' : 'json' }}</a-descriptions-item>
        <a-descriptions-item label="工具">{{ requestDetail.toolCount || 0 }} / 返回 {{ requestDetail.responseToolCallCount || 0 }} / {{ requestDetail.toolLifecycleCompleted ? '完整' : '未标记完整' }}</a-descriptions-item>
        <a-descriptions-item label="状态">{{ requestStatusText(requestDetail) }}</a-descriptions-item>
        <a-descriptions-item label="耗时">{{ formatMs(requestDetail.latencyMs) }} / 首块 {{ formatMs(requestDetail.firstChunkMs) }}</a-descriptions-item>
        <a-descriptions-item label="Tokens">{{ requestDetail.tokenCount || 0 }} / 估算 {{ requestDetail.estimatedPromptTokens || 0 }}</a-descriptions-item>
        <a-descriptions-item label="重试">{{ requestDetail.retryCount || 0 }}</a-descriptions-item>
        <a-descriptions-item label="时间">{{ formatTime(requestDetail.createTime) }}</a-descriptions-item>
        <a-descriptions-item label="错误类型">{{ requestDetail.errorType || '-' }}</a-descriptions-item>
        <a-descriptions-item label="错误内容">
          <pre class="detail-pre">{{ requestDetail.errorMessage || '-' }}</pre>
        </a-descriptions-item>
      </a-descriptions>
    </a-drawer>

    <a-drawer
      v-model:open="modelDetailOpen"
      title="模型来源"
      :width="drawerWidth"
      placement="right"
      destroy-on-close
    >
      <template v-if="modelDetail">
        <div class="drawer-title">{{ modelDetail.id }}</div>
        <a-empty v-if="!modelSources(modelDetail).length" description="暂无来源" />
        <div v-for="(source, idx) in modelSources(modelDetail)" :key="idx" class="source-row">
          <div class="strong-line">{{ sourceTitle(source) }}</div>
          <div class="sub-line">{{ source.ociRegion || '-' }} · {{ source.modelSource || '-' }}</div>
          <div v-if="source.healthStatus" class="source-status">
            <a-tag :color="healthColor(source.healthStatus)">{{ healthText(source.healthStatus) }}</a-tag>
            <span>{{ source.healthMessage || '' }}</span>
          </div>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'OracleAiDiagnosticsPanel' })
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  getOracleAiLbHealth,
  listOracleAiLbModels,
  listOracleAiLbRequests,
} from '../../api/oracleAi'

const activeSection = ref<'requests' | 'health' | 'models'>('requests')
const sectionOptions = [
  { label: '请求日志', value: 'requests' },
  { label: '健康诊断', value: 'health' },
  { label: '模型来源', value: 'models' },
]
const limitOptions = [50, 100, 200, 500].map((value) => ({ label: `${value} 条`, value }))
const statusOptions = [
  { label: '全部状态', value: 'all' },
  { label: '成功', value: 'success' },
  { label: '失败', value: 'failed' },
  { label: '客户端断开', value: 'client_aborted' },
]
const keywordTypeOptions = [
  { label: '模型', value: 'model' },
  { label: '请求ID', value: 'requestId' },
]
const booleanOptions = [
  { label: '工具不限', value: 'all' },
  { label: '有工具', value: 'true' },
  { label: '无工具', value: 'false' },
]
const abortOptions = [
  { label: '断开不限', value: 'all' },
  { label: '已断开', value: 'true' },
  { label: '未断开', value: 'false' },
]

const requests = ref<any[]>([])
const health = ref<any>({})
const models = ref<any>({})
const requestsLoading = ref(false)
const healthLoading = ref(false)
const modelsLoading = ref(false)
const autoRefresh = ref(false)
const forceModelRefresh = ref(false)
const modelAccount = ref('')
const requestPage = ref(1)
const requestPageSize = 12
const isMobile = ref(false)
const requestDetailOpen = ref(false)
const requestDetail = ref<any | null>(null)
const modelDetailOpen = ref(false)
const modelDetail = ref<any | null>(null)
let autoTimer: ReturnType<typeof setInterval> | undefined

const requestFilters = reactive({
  limit: 100,
  status: 'all',
  memberId: '',
  keywordType: 'model',
  keyword: '',
  hasTools: 'all',
  clientAborted: 'all',
})

const activeLoading = computed(() => {
  if (activeSection.value === 'requests') return requestsLoading.value
  if (activeSection.value === 'health') return healthLoading.value
  return modelsLoading.value
})
const drawerWidth = computed(() => isMobile.value ? 'calc(100vw - 24px)' : 720)
const healthMembers = computed(() => Array.isArray(health.value?.members) ? health.value.members : [])
const modelRows = computed(() => Array.isArray(models.value?.data) ? models.value.data : [])
const modelErrors = computed(() => Array.isArray(models.value?.errors) ? models.value.errors : [])
const requestPages = computed(() => Math.max(1, Math.ceil(requests.value.length / requestPageSize)))
const requestPageRows = computed(() => {
  const start = (requestPage.value - 1) * requestPageSize
  return requests.value.slice(start, start + requestPageSize)
})
const memberOptions = computed(() => {
  const seen = new Set<string>()
  const options: { label: string; value: string }[] = [{ label: '全部成员', value: '' }]
  for (const source of [...healthMembers.value, ...requests.value]) {
    const id = String(source?.memberId || '').trim()
    if (!id || seen.has(id)) continue
    seen.add(id)
    options.push({ label: sourceTitle(source), value: id })
  }
  return options
})

function popupContainer() {
  return document.body
}

function updateViewport() {
  isMobile.value = typeof window !== 'undefined' && window.innerWidth < 768
}

function requestPayload() {
  const keyword = requestFilters.keyword.trim()
  const status = requestFilters.status === 'all' ? undefined : requestFilters.status
  const payload: any = {
    limit: requestFilters.limit,
    status,
    memberId: String(requestFilters.memberId || '').trim() || undefined,
    hasTools: requestFilters.hasTools === 'all' ? undefined : requestFilters.hasTools === 'true',
    clientAborted: status === 'client_aborted' || requestFilters.clientAborted === 'all'
      ? undefined
      : requestFilters.clientAborted === 'true',
  }
  if (keyword) {
    payload[requestFilters.keywordType] = keyword
  }
  return payload
}

async function loadRequests() {
  requestsLoading.value = true
  try {
    const r: any = await listOracleAiLbRequests(requestPayload())
    requests.value = Array.isArray(r?.data) ? r.data : []
  } catch (e: any) {
    message.error(e?.message || '读取请求日志失败')
  } finally {
    requestsLoading.value = false
  }
}

async function loadHealth() {
  healthLoading.value = true
  try {
    const r: any = await getOracleAiLbHealth()
    health.value = r?.data || {}
  } catch (e: any) {
    message.error(e?.message || '读取健康诊断失败')
  } finally {
    healthLoading.value = false
  }
}

async function loadModels() {
  modelsLoading.value = true
  try {
    const r: any = await listOracleAiLbModels({
      account: modelAccount.value.trim() || undefined,
      refresh: forceModelRefresh.value,
    })
    models.value = r?.data || {}
  } catch (e: any) {
    message.error(e?.message || '读取模型来源失败')
  } finally {
    modelsLoading.value = false
  }
}

function refreshActive() {
  if (activeSection.value === 'requests') return loadRequests()
  if (activeSection.value === 'health') return loadHealth()
  return loadModels()
}

function startAutoRefresh() {
  stopAutoRefresh()
  autoTimer = setInterval(() => {
    if (activeSection.value === 'requests' && !requestsLoading.value) {
      loadRequests()
    }
  }, 10000)
}

function stopAutoRefresh() {
  if (autoTimer) {
    clearInterval(autoTimer)
    autoTimer = undefined
  }
}

function openRequestDetail(row: any) {
  requestDetail.value = row
  requestDetailOpen.value = true
}

function openModelDetail(row: any) {
  modelDetail.value = row
  modelDetailOpen.value = true
}

function formatTime(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function shortId(id?: string) {
  const value = String(id || '')
  if (!value) return '-'
  if (value.length <= 14) return value
  return `${value.slice(0, 8)}...${value.slice(-4)}`
}

function valueOrDash(value: any) {
  return value === null || value === undefined || value === '' ? '-' : value
}

function formatMs(value: any) {
  const normalized = valueOrDash(value)
  return normalized === '-' ? '-' : `${normalized}ms`
}

function requestMemberName(row: any) {
  return row?.memberName || row?.bindingName || (row?.port ? `port-${row.port}` : row?.memberId || '-')
}

function requestMemberMeta(row: any) {
  const pieces = []
  if (row?.port) pieces.push(String(row.port))
  if (row?.tenantName || row?.tenantUsername) pieces.push(row.tenantName || row.tenantUsername)
  return pieces.join(' · ') || '-'
}

function requestPathText(path?: string) {
  const raw = String(path || '').trim()
  if (!raw) return '-'
  const normalized = raw.startsWith('/') ? raw : `/${raw}`
  if (normalized.startsWith('/v1/')) return normalized
  if (normalized === '/v1') return normalized
  return `/v1${normalized}`
}

function requestStatusText(row: any) {
  if (row?.clientAborted) return '客户端断开'
  const code = Number(row?.statusCode || 0)
  if (row?.status === 'success' || (code >= 200 && code < 400)) return `HTTP ${code || 200}`
  return code ? `HTTP ${code}` : row?.status || '失败'
}

function requestStatusColor(row: any) {
  if (row?.clientAborted) return 'default'
  const code = Number(row?.statusCode || 0)
  if (code >= 200 && code < 400) return 'green'
  if (code === 429) return 'orange'
  if (code >= 500) return 'red'
  return row?.status === 'success' ? 'green' : 'red'
}

function healthText(status?: string) {
  const value = String(status || '').toLowerCase()
  if (value === 'healthy') return '健康'
  if (value === 'unhealthy') return '异常'
  if (value === 'cooling') return '冷却'
  if (value === 'recovering') return '恢复观察'
  if (value === 'disabled') return '禁用'
  return status || '未知'
}

function healthColor(status?: string) {
  const value = String(status || '').toLowerCase()
  if (value === 'healthy') return 'green'
  if (value === 'unhealthy') return 'red'
  if (value === 'cooling') return 'orange'
  if (value === 'recovering') return 'orange'
  return 'default'
}

function capabilityColor(capability?: string) {
  const value = String(capability || '').toLowerCase()
  if (value.includes('embed')) return 'purple'
  if (value.includes('rerank')) return 'cyan'
  if (value.includes('chat')) return 'green'
  return 'default'
}

function sourceCount(row: any) {
  return modelSources(row).length
}

function modelSources(row: any) {
  return Array.isArray(row?.ociworkerSources) ? row.ociworkerSources : []
}

function sourceTitle(source: any) {
  const name = source?.bindingName || source?.memberName || (source?.port ? `port-${source.port}` : source?.memberId)
  const tenant = source?.tenantName || source?.tenantUsername
  return [name || '-', tenant].filter(Boolean).join(' · ')
}

watch(autoRefresh, (enabled) => {
  if (enabled) startAutoRefresh()
  else stopAutoRefresh()
})

watch(activeSection, (section) => {
  if (section === 'requests' && !requests.value.length) loadRequests()
  if (section === 'health' && !healthMembers.value.length) loadHealth()
  if (section === 'models' && !modelRows.value.length) loadModels()
})

watch(requests, () => {
  if (requestPage.value > requestPages.value) requestPage.value = requestPages.value
  if (requestPage.value < 1) requestPage.value = 1
})

onMounted(() => {
  updateViewport()
  window.addEventListener('resize', updateViewport)
  loadRequests()
  loadHealth()
})

onUnmounted(() => {
  stopAutoRefresh()
  window.removeEventListener('resize', updateViewport)
})
</script>

<style scoped>
.oracle-ai-diagnostics {
  display: grid;
  gap: 8px;
  min-width: 0;
}
.diagnostic-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 8px;
  margin-bottom: 4px;
  min-width: 0;
}
.diagnostic-head :deep(.ant-segmented) {
  max-width: 100%;
  padding: 2px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: rgba(15, 23, 42, 0.48);
}
.diagnostic-actions {
  flex: 0 1 auto;
  max-width: 100%;
  min-width: 0;
  margin-left: auto;
  justify-content: flex-end;
}
.diagnostic-head :deep(.ant-segmented-item) {
  min-width: 88px;
  border-radius: 8px;
  color: var(--text-sub);
}
.diagnostic-head :deep(.ant-segmented-item-selected) {
  background: rgba(129, 140, 248, 0.18);
  color: var(--text-main);
}
.diagnostic-card {
  margin-top: 8px;
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}
.filter-small {
  width: 128px;
}
.filter-member {
  width: 220px;
}
.filter-keyword {
  width: 260px;
  max-width: 100%;
}
.table-wrap {
  width: 100%;
  overflow-x: auto;
}
.table-mid,
.table-wide {
  width: 100%;
  border-collapse: collapse;
  color: var(--text-main);
  font-size: 14px;
}
.table-mid { min-width: 1180px; }
.table-wide { min-width: 1580px; }
.table-mid th,
.table-mid td,
.table-wide th,
.table-wide td {
  padding: 13px 16px;
  border-bottom: 1px solid var(--border);
  text-align: left;
  vertical-align: middle;
  background: transparent;
  white-space: nowrap;
}
.table-mid th,
.table-wide th {
  color: var(--text-sub);
  font-weight: 600;
  font-size: 13px;
}
.table-mid tbody tr:hover,
.table-wide tbody tr:hover {
  background: rgba(129, 140, 248, 0.04);
}
.table-mid tbody tr:last-child td,
.table-wide tbody tr:last-child td {
  border-bottom: 0;
}
.strong-line {
  font-weight: 600;
  min-width: 0;
}
.sub-line {
  color: var(--text-sub, #666);
  font-size: 12px;
  line-height: 1.55;
}
.ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.muted-block {
  display: block;
  margin-top: 3px;
  color: var(--text-sub, #666);
  font-size: 12px;
}
.status-message {
  max-width: 330px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-top: 4px;
}
.model-summary {
  display: inline-block;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.model-id {
  display: inline-block;
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}
.metric-item {
  border: 1px solid var(--border, rgba(148, 163, 184, 0.22));
  border-radius: 8px;
  padding: 10px;
  min-width: 0;
  background: rgba(15, 23, 42, 0.22);
}
.metric-item span {
  display: block;
  color: var(--text-sub, #666);
  font-size: 12px;
}
.metric-item b {
  display: block;
  margin-top: 4px;
  font-size: 18px;
  font-weight: 600;
}
.request-card-list {
  display: grid;
  gap: 10px;
}
.request-card {
  border: 1px solid var(--border, rgba(148, 163, 184, 0.22));
  border-radius: 8px;
  padding: 10px;
  background: var(--bg-card, rgba(30, 41, 59, 0.32));
}
.request-card-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: flex-start;
  margin-bottom: 8px;
}
.request-card-grid {
  display: grid;
  grid-template-columns: 54px minmax(0, 1fr);
  gap: 5px 10px;
  font-size: 13px;
}
.request-card-grid span {
  color: var(--text-sub, #666);
}
.request-card-grid b {
  font-weight: 500;
  min-width: 0;
  overflow-wrap: anywhere;
}
.health-card-list,
.model-card-list {
  display: grid;
  gap: 10px;
}
.health-card,
.model-card {
  border: 1px solid var(--border, rgba(148, 163, 184, 0.22));
  border-radius: 8px;
  padding: 10px;
  background: var(--bg-card, rgba(30, 41, 59, 0.32));
  min-width: 0;
}
.health-card-head,
.model-card-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: flex-start;
  margin-bottom: 9px;
  min-width: 0;
}
.health-card-head :deep(.ant-tag),
.model-card-head :deep(.ant-tag) {
  flex-shrink: 0;
  margin-inline-end: 0;
}
.health-card-title,
.model-card-id {
  min-width: 0;
}
.health-card-title strong,
.model-card-id {
  display: block;
  color: var(--text-main);
  font-weight: 600;
  line-height: 1.45;
  overflow-wrap: anywhere;
}
.health-card-title span {
  display: block;
  margin-top: 2px;
  color: var(--text-sub, #666);
  font-size: 12px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}
.health-card-grid,
.model-card-grid {
  display: grid;
  grid-template-columns: 68px minmax(0, 1fr);
  gap: 6px 10px;
  align-items: start;
  font-size: 13px;
}
.health-card-grid span,
.model-card-grid span {
  color: var(--text-sub, #666);
}
.health-card-grid b,
.model-card-grid b {
  min-width: 0;
  color: var(--text-main);
  font-weight: 500;
  overflow-wrap: anywhere;
}
.model-card-grid {
  margin-bottom: 10px;
}
.model-card :deep(.ant-btn) {
  width: 100%;
}
.health-card-message {
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px solid var(--border, rgba(148, 163, 184, 0.18));
  color: var(--text-sub, #666);
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}
.pagination {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 6px;
  padding-top: 12px;
  color: var(--text-sub);
  font-size: 12px;
}
.pagination button {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  min-width: 28px;
  height: 28px;
  border-radius: 8px;
}
.pagination .page-chip {
  border: 1px solid var(--border);
  color: var(--text-main);
  background: rgba(129, 140, 248, 0.14);
}
.detail-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.55;
}
.drawer-title {
  font-weight: 600;
  margin-bottom: 12px;
  word-break: break-all;
}
.source-row,
.model-error-row {
  border-bottom: 1px solid var(--border, rgba(148, 163, 184, 0.18));
  padding: 10px 0;
}
.source-row:first-child,
.model-error-row:first-child {
  padding-top: 0;
}
.source-status {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 6px;
  color: var(--text-sub, #666);
  font-size: 12px;
}
.model-error-list {
  display: grid;
  gap: 6px;
  margin-top: 12px;
}
.model-error-row {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}
.model-error-row code {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 12px;
}
@media (max-width: 1100px) {
  .diagnostic-head {
    align-items: flex-start;
  }
  .diagnostic-actions {
    width: 100%;
    margin-left: 0;
    justify-content: flex-end;
  }
}
@media (max-width: 767px) {
  .diagnostic-head {
    align-items: flex-start;
  }
  .filter-bar {
    align-items: stretch;
  }
  .filter-small,
  .filter-member,
  .filter-keyword {
    width: 100%;
  }
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .metric-item {
    padding: 8px;
  }
  .metric-item b {
    font-size: 16px;
  }
  .diagnostic-card :deep(.ant-card-head) {
    min-height: 48px;
    padding: 0 14px;
  }
  .diagnostic-card :deep(.ant-card-body) {
    padding: 14px;
  }
  .diagnostic-head :deep(.ant-segmented) {
    width: 100%;
  }
  .diagnostic-head :deep(.ant-segmented-group) {
    width: 100%;
  }
  .diagnostic-head :deep(.ant-segmented-item) {
    flex: 1;
    min-width: 0;
  }
  .diagnostic-head :deep(.ant-space) {
    width: 100%;
    justify-content: space-between;
  }
  .model-error-row {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .model-error-row > span {
    flex: 1 1 calc(100% - 56px);
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .model-error-row code {
    flex: 1 1 100%;
  }
}
:global([data-theme='light'] .diagnostic-head .ant-segmented) {
  background: rgba(248, 250, 252, 0.72);
}
:global([data-theme='light'] .metric-item) {
  background: rgba(248, 250, 252, 0.72);
}
:global([data-theme='light'] .health-card),
:global([data-theme='light'] .model-card) {
  background: rgba(248, 250, 252, 0.72);
}
</style>
