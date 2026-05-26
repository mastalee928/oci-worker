<template>
  <div class="cf-dns-tab">
      <!-- DNSSEC -->
      <a-card size="small" title="DNSSEC" class="cf-sub-card" :loading="dnssecLoading">
        <a-descriptions v-if="dnssec" bordered size="small" :column="isMobile ? 1 : 2">
          <a-descriptions-item label="状态">
            <a-tag :color="dnssec.status === 'active' ? 'success' : 'default'">{{ dnssec.status || '—' }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="DS 记录">{{ dnssec.ds || '—' }}</a-descriptions-item>
        </a-descriptions>
        <a-space wrap style="margin-top: 12px">
          <a-button
            type="primary"
            size="small"
            :disabled="dnssec?.status === 'active'"
            :loading="dnssecSetLoading"
            @click="handleSetDnssec('active')"
          >
            启用 DNSSEC
          </a-button>
          <a-button
            size="small"
            :disabled="dnssec?.status === 'disabled' || !dnssec?.status"
            :loading="dnssecSetLoading"
            @click="handleSetDnssec('disabled')"
          >
            禁用 DNSSEC
          </a-button>
          <a-button size="small" :loading="dnssecLoading" @click="loadDnssec">刷新</a-button>
        </a-space>
      </a-card>

      <!-- DNS 工具栏 -->
      <div class="cf-toolbar" :class="{ 'is-mobile': isMobile }">
        <a-space wrap class="cf-toolbar-space">
          <a-input-search
            v-model:value="dnsSearch"
            placeholder="搜索记录名称"
            allow-clear
            class="cf-dns-search"
            @search="onDnsFilterChange"
          />
          <a-select
            v-model:value="dnsTypeFilter"
            placeholder="类型筛选"
            allow-clear
            class="cf-dns-type-filter"
            :options="dnsTypeFilterOptions"
            @change="onDnsFilterChange"
          />
          <a-button type="primary" @click="openDnsModal()">
            <template #icon><PlusOutlined /></template>
            添加记录
          </a-button>
          <a-button :loading="dnsLoading" @click="loadDnsRecords">
            <template #icon><ReloadOutlined /></template>
            刷新记录
          </a-button>
          <a-button @click="openImportModal">
            <template #icon><ImportOutlined /></template>
            导入 BIND
          </a-button>
          <a-button :loading="exportLoading" @click="handleExportDns">
            <template #icon><ExportOutlined /></template>
            导出 BIND
          </a-button>
        </a-space>
      </div>

      <!-- DNS 表格 / 移动端卡片 -->
      <a-table
        v-if="!isMobile"
        :columns="dnsColumns"
        :data-source="dnsRecords"
        :loading="dnsLoading"
        :row-key="(r: DnsRecord) => r.id || r.workerDomainId || r.name"
        size="middle"
        :scroll="{ x: 1100 }"
        :pagination="dnsPagination"
        @change="onDnsTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'type'">
            <a-tag v-if="record.tunnelBound" color="cyan">隧道</a-tag>
            <a-tag v-else-if="record.workerBound" color="processing">Worker</a-tag>
            <span v-else>{{ record.type }}</span>
          </template>
          <template v-else-if="column.key === 'content'">
            <a-button
              v-if="record.tunnelBound && record.tunnelId"
              type="link"
              size="small"
              class="cf-dns-tunnel-link"
              @click="openTunnelFromDns(record)"
            >
              {{ record.content }}
            </a-button>
            <span v-else :class="{ 'cf-dns-special-target': record.workerBound }">{{ record.content }}</span>
          </template>
          <template v-else-if="column.key === 'proxied'">
            <a-tag v-if="record.proxied" color="orange">代理</a-tag>
            <span v-else>—</span>
          </template>
          <template v-else-if="column.key === 'ttl'">
            {{ formatDnsTtl(record.ttl) }}
          </template>
          <template v-else-if="column.key === 'priority'">
            {{ record.priority ?? '—' }}
          </template>
          <template v-else-if="column.key === 'comment'">
            {{ record.comment || '—' }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space wrap>
              <a-tooltip
                v-if="record.workerBound"
                title="Worker 自定义域请在 Workers 的「域和路由」中修改"
              >
                <a-button type="link" size="small" disabled>编辑</a-button>
              </a-tooltip>
              <a-tooltip
                v-else-if="record.tunnelBound"
                title="Tunnel 路由请在 Tunnel 连接器的 Public Hostname 中修改"
              >
                <a-button type="link" size="small" disabled>编辑</a-button>
              </a-tooltip>
              <a-button v-else type="link" size="small" @click="openDnsModal(record)">编辑</a-button>
              <a-popconfirm
                :title="dnsDeleteConfirmTitle(record)"
                @confirm="handleDeleteDns(record)"
              >
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>

      <a-spin v-else :spinning="dnsLoading">
        <a-empty v-if="!dnsLoading && dnsRecords.length === 0" description="暂无 DNS 记录" />
        <div v-for="item in dnsRecords" :key="item.id || item.workerDomainId || item.name" class="mobile-card">
          <div class="mobile-card-header">
            <span class="mobile-card-title">
              <template v-if="item.tunnelBound">隧道</template>
              <template v-else-if="item.workerBound">Worker</template>
              <template v-else>{{ item.type }}</template>
              · {{ item.name }}
            </span>
            <a-tag v-if="item.proxied" color="orange">代理</a-tag>
          </div>
          <p v-if="item.workerBound" class="cf-dns-special-hint">Worker 充当此主机名的 Origin，请在 Workers「域和路由」中修改。</p>
          <p v-else-if="item.tunnelBound" class="cf-dns-special-hint">隧道 CNAME 由 Cloudflare Tunnel 管理，请在 Tunnel Public Hostname 中修改。</p>
          <div class="mobile-card-body">
            <div class="mobile-card-row">
              <span class="label">{{ item.tunnelBound ? '隧道' : item.workerBound ? 'Worker' : '内容' }}</span>
              <a-button
                v-if="item.tunnelBound && item.tunnelId"
                type="link"
                size="small"
                class="cf-dns-tunnel-link mobile"
                @click="openTunnelFromDns(item)"
              >
                {{ item.content }}
              </a-button>
              <span v-else class="value" :class="{ 'cf-dns-special-target': item.workerBound }">{{ item.content }}</span>
            </div>
            <div class="mobile-card-row"><span class="label">TTL</span><span class="value">{{ formatDnsTtl(item.ttl) }}</span></div>
            <div v-if="item.priority != null" class="mobile-card-row"><span class="label">优先级</span><span class="value">{{ item.priority }}</span></div>
            <div v-if="item.comment" class="mobile-card-row"><span class="label">备注</span><span class="value">{{ item.comment }}</span></div>
          </div>
          <a-space wrap style="margin-top: 8px">
            <a-button v-if="!item.workerBound && !item.tunnelBound" size="small" @click="openDnsModal(item)">编辑</a-button>
            <a-popconfirm
              :title="dnsDeleteConfirmTitle(item)"
              @confirm="handleDeleteDns(item)"
            >
              <a-button size="small" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </div>
        <div v-if="dnsTotal > dnsPerPage" class="cf-mobile-pagination">
          <a-pagination
            v-model:current="dnsPage"
            :total="dnsTotal"
            :page-size="dnsPerPage"
            size="small"
            :show-size-changer="false"
            @change="loadDnsRecords"
          />
        </div>
      </a-spin>

    <!-- DNS 记录 -->
    <a-modal
      v-model:open="dnsModalVisible"
      :mask-closable="false"
      :keyboard="false"
      :title="dnsEditingId ? '编辑 DNS 记录' : '添加 DNS 记录'"
      :confirm-loading="dnsSaveLoading"
      @ok="submitDnsRecord"
    >
      <a-form layout="vertical">
        <a-form-item label="类型" required>
          <a-select v-model:value="dnsForm.type" :options="dnsTypeOptions" />
        </a-form-item>
        <a-form-item label="名称" required>
          <a-input v-model:value="dnsForm.name" placeholder="如 www 或 @ 或子域" />
        </a-form-item>
        <a-form-item label="内容" required>
          <a-input v-model:value="dnsForm.content" placeholder="IP、域名或文本" />
        </a-form-item>
        <a-form-item v-if="dnsPrioritySupported" label="优先级（MX/SRV）">
          <a-input-number v-model:value="dnsForm.priority" :min="0" :max="65535" style="width: 100%" />
        </a-form-item>
        <a-form-item v-if="dnsProxiedSupported" label="代理（橙云）">
          <a-switch v-model:checked="dnsForm.proxied" />
        </a-form-item>
        <a-form-item v-if="!dnsForm.proxied || !dnsProxiedSupported" label="TTL（秒，1=自动）">
          <a-input-number v-model:value="dnsForm.ttl" :min="1" style="width: 100%" />
        </a-form-item>
        <a-form-item label="备注">
          <a-input v-model:value="dnsForm.comment" placeholder="可选" allow-clear />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 导入 BIND -->
    <a-modal
      v-model:open="importModalVisible"
      :mask-closable="false"
      :keyboard="false"
      title="导入 BIND 区域文件"
      :confirm-loading="importLoading"
      width="640px"
      @ok="submitImportDns"
    >
      <a-form layout="vertical">
        <a-form-item label="BIND 内容" required>
          <a-textarea v-model:value="importBindContent" :rows="12" placeholder="粘贴 BIND 格式区域文件内容" />
        </a-form-item>
        <a-form-item label="导入时启用代理（橙云）">
          <a-switch v-model:checked="importProxied" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, reactive, watch, onMounted, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'
import type { TablePaginationConfig } from 'ant-design-vue/es/table'
import {
  PlusOutlined,
  ReloadOutlined,
  ImportOutlined,
  ExportOutlined,
} from '@ant-design/icons-vue'
import {
  listCfDnsRecords,
  addCfDnsRecord,
  updateCfDnsRecord,
  deleteCfDnsRecord,
  deleteCfWorkerDomain,
  exportCfDnsRecords,
  importCfDnsRecords,
  getCfDnssec,
  setCfDnssec,
} from '../../api/cloudflare'

interface DnsRecord {
  id: string
  type: string
  name: string
  content: string
  proxied?: boolean
  ttl?: number
  priority?: number
  comment?: string
  workerBound?: boolean
  workerDomainId?: string
  workerService?: string
  tunnelBound?: boolean
  tunnelId?: string
  tunnelName?: string
  rawType?: string
  rawContent?: string
}

const props = defineProps<{
  cfConfigured: boolean
  zoneId: string
}>()

const emit = defineEmits<{
  'update:zoneId': [value: string | undefined]
  'open-tunnel': [payload: { tunnelId: string; tunnelName?: string; zoneId?: string }]
}>()

function openTunnelFromDns(record: DnsRecord) {
  if (!record.tunnelId) {
    message.warning('缺少 Tunnel ID，无法打开隧道配置')
    return
  }
  emit('open-tunnel', {
    tunnelId: record.tunnelId,
    tunnelName: record.tunnelName || record.content,
    zoneId: props.zoneId,
  })
}

const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }

const dnssecLoading = ref(false)
const dnssecSetLoading = ref(false)
const dnssec = ref<{ status?: string; ds?: string } | null>(null)

const dnsLoading = ref(false)
const dnsRecords = ref<DnsRecord[]>([])
const dnsPage = ref(1)
const dnsPerPage = ref(50)
const dnsTotal = ref(0)
const dnsTotalPages = ref(1)
const dnsSearch = ref('')
const dnsTypeFilter = ref<string | undefined>(undefined)

const dnsModalVisible = ref(false)
const dnsSaveLoading = ref(false)
const dnsEditingId = ref('')
const dnsForm = reactive({
  type: 'A',
  name: '',
  content: '',
  proxied: false,
  ttl: 1,
  priority: 10,
  comment: '',
})

const importModalVisible = ref(false)
const importLoading = ref(false)
const importBindContent = ref('')
const importProxied = ref(false)
const exportLoading = ref(false)

const DNS_TYPES = ['A', 'AAAA', 'CNAME', 'TXT', 'MX', 'NS', 'SRV', 'CAA', 'HTTPS', 'PTR']
const dnsTypeOptions = DNS_TYPES.map(v => ({ value: v, label: v }))
const dnsTypeFilterOptions = [
  { value: '', label: '全部类型' },
  { value: 'WORKER', label: 'Worker' },
  { value: 'TUNNEL', label: '隧道' },
  ...dnsTypeOptions,
]

function dnsDeleteConfirmTitle(record: DnsRecord) {
  if (record.workerBound) return '确定移除此 Worker 自定义域？'
  if (record.tunnelBound) return '确定删除此隧道 DNS 记录？（不会删除 Tunnel 本身）'
  return '确定删除此 DNS 记录？'
}

function formatDnsTtl(ttl?: number) {
  if (ttl == null || ttl === 1) return '自动'
  return String(ttl)
}

const dnsProxiedSupported = computed(() => ['A', 'AAAA', 'CNAME'].includes(dnsForm.type))
const dnsPrioritySupported = computed(() => ['MX', 'SRV'].includes(dnsForm.type))

const dnsColumns = [
  { title: '类型', key: 'type', width: 88 },
  { title: '名称', dataIndex: 'name', ellipsis: true },
  { title: '内容', key: 'content', ellipsis: true },
  { title: '代理', key: 'proxied', width: 72 },
  { title: 'TTL', key: 'ttl', width: 72 },
  { title: '优先级', key: 'priority', width: 80 },
  { title: '备注', key: 'comment', ellipsis: true, width: 120 },
  { title: '操作', key: 'action', width: 120 },
]

const dnsPagination = computed(() => ({
  current: dnsPage.value,
  pageSize: dnsPerPage.value,
  total: dnsTotal.value,
  showSizeChanger: true,
  pageSizeOptions: ['20', '50', '100'],
  showTotal: (t: number) => `共 ${t} 条记录`,
}))

let zoneLoadSeq = 0

function resolveZoneId(zoneId?: string) {
  return typeof zoneId === 'string' && zoneId ? zoneId : props.zoneId
}

async function onZoneChange(zoneId: string | undefined) {
  if (!zoneId) {
    zoneLoadSeq++
    dnsRecords.value = []
    dnssec.value = null
    return
  }
  const seq = ++zoneLoadSeq
  await Promise.all([
    loadDnssec(zoneId, seq),
    loadDnsRecords(zoneId, seq),
  ])
}

async function loadDnssec(zoneId?: string, seq?: number) {
  const zid = resolveZoneId(zoneId)
  if (!zid) return
  dnssecLoading.value = true
  try {
    const res = await getCfDnssec({ zoneId: zid }, seq !== undefined)
    if (seq !== undefined && seq !== zoneLoadSeq) return
    dnssec.value = res.data || null
  } catch {
    if (seq !== undefined && seq !== zoneLoadSeq) return
    dnssec.value = null
  } finally {
    if (seq === undefined || seq === zoneLoadSeq) {
      dnssecLoading.value = false
    }
  }
}

async function handleSetDnssec(status: 'active' | 'disabled') {
  if (!props.zoneId) return
  dnssecSetLoading.value = true
  try {
    const res = await setCfDnssec({ zoneId: props.zoneId, status })
    dnssec.value = res.data || { status }
    message.success(status === 'active' ? 'DNSSEC 已启用' : 'DNSSEC 已禁用')
  } finally {
    dnssecSetLoading.value = false
  }
}

function onDnsFilterChange() {
  dnsPage.value = 1
  loadDnsRecords()
}

function onDnsTableChange(pag: TablePaginationConfig) {
  dnsPage.value = pag.current || 1
  if (pag.pageSize) dnsPerPage.value = pag.pageSize
  loadDnsRecords()
}

async function loadDnsRecords(zoneId?: string, seq?: number) {
  const zid = resolveZoneId(zoneId)
  if (!zid) return
  dnsLoading.value = true
  try {
    const res = await listCfDnsRecords({
      zoneId: zid,
      page: dnsPage.value,
      perPage: dnsPerPage.value,
      search: dnsSearch.value.trim() || undefined,
      type: dnsTypeFilter.value || undefined,
    })
    if (seq !== undefined && seq !== zoneLoadSeq) return
    const data = res.data || {}
    dnsRecords.value = data.records || []
    dnsTotal.value = data.total ?? dnsRecords.value.length
    dnsPage.value = data.page ?? dnsPage.value
    dnsPerPage.value = data.perPage ?? dnsPerPage.value
    dnsTotalPages.value = data.totalPages ?? 1
  } finally {
    if (seq === undefined || seq === zoneLoadSeq) {
      dnsLoading.value = false
    }
  }
}

function openDnsModal(record?: DnsRecord) {
  if (record?.workerBound) {
    message.info('Worker 自定义域请在 Workers「域和路由」中修改，此处仅展示。')
    return
  }
  if (record?.tunnelBound) {
    message.info('Tunnel 路由请在 Tunnel 连接器的 Public Hostname 中修改，此处仅展示。')
    return
  }
  if (record) {
    dnsEditingId.value = record.id
    dnsForm.type = record.type || 'A'
    dnsForm.name = record.name || ''
    dnsForm.content = record.content || ''
    dnsForm.proxied = !!record.proxied
    dnsForm.ttl = record.ttl ?? 1
    dnsForm.priority = record.priority ?? 10
    dnsForm.comment = record.comment || ''
  } else {
    dnsEditingId.value = ''
    dnsForm.type = 'A'
    dnsForm.name = ''
    dnsForm.content = ''
    dnsForm.proxied = false
    dnsForm.ttl = 1
    dnsForm.priority = 10
    dnsForm.comment = ''
  }
  dnsModalVisible.value = true
}

async function submitDnsRecord() {
  if (!props.zoneId) return
  if (!dnsForm.name.trim() || !dnsForm.content.trim()) {
    message.warning('请填写名称与内容')
    return
  }
  const payload = {
    zoneId: props.zoneId,
    type: dnsForm.type,
    name: dnsForm.name.trim(),
    content: dnsForm.content.trim(),
    proxied: dnsProxiedSupported.value ? dnsForm.proxied : false,
    ttl: dnsForm.proxied && dnsProxiedSupported.value ? 1 : (dnsForm.ttl || 1),
    priority: dnsPrioritySupported.value ? dnsForm.priority : undefined,
    comment: dnsForm.comment.trim() || undefined,
  }
  dnsSaveLoading.value = true
  try {
    if (dnsEditingId.value) {
      await updateCfDnsRecord({ ...payload, recordId: dnsEditingId.value })
      message.success('已更新')
    } else {
      await addCfDnsRecord(payload)
      message.success('已添加')
    }
    dnsModalVisible.value = false
    await loadDnsRecords()
  } finally {
    dnsSaveLoading.value = false
  }
}

async function handleDeleteDns(record: DnsRecord) {
  if (!props.zoneId) return
  if (record.workerBound) {
    if (!record.workerDomainId) {
      message.warning('缺少 Worker 自定义域 ID，无法删除')
      return
    }
    await deleteCfWorkerDomain({ workerDomainId: record.workerDomainId })
    message.success('已移除 Worker 自定义域')
  } else {
    if (!record.id) {
      message.warning('缺少 DNS 记录 ID')
      return
    }
    await deleteCfDnsRecord({ zoneId: props.zoneId, recordId: record.id })
    message.success('已删除')
  }
  await loadDnsRecords()
}

function openImportModal() {
  importBindContent.value = ''
  importProxied.value = false
  importModalVisible.value = true
}

async function submitImportDns() {
  if (!props.zoneId) return
  if (!importBindContent.value.trim()) {
    message.warning('请粘贴 BIND 内容')
    return
  }
  importLoading.value = true
  try {
    await importCfDnsRecords({
      zoneId: props.zoneId,
      bindContent: importBindContent.value.trim(),
      proxied: importProxied.value,
    })
    message.success('导入成功')
    importModalVisible.value = false
    await loadDnsRecords()
  } finally {
    importLoading.value = false
  }
}

async function handleExportDns() {
  if (!props.zoneId) return
  exportLoading.value = true
  try {
    const res = await exportCfDnsRecords({ zoneId: props.zoneId })
    const content = typeof res.data === 'string' ? res.data : String(res.data ?? '')
    const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${props.zoneId}.bind`
    a.click()
    URL.revokeObjectURL(url)
    message.success('导出成功')
  } finally {
    exportLoading.value = false
  }
}

watch(() => props.zoneId, (id) => {
  onZoneChange(id)
}, { immediate: true })

onMounted(() => {
  window.addEventListener('resize', checkMobile)
})
onUnmounted(() => window.removeEventListener('resize', checkMobile))
</script>

<style scoped>
.cf-dns-tab { min-height: 120px; }
.cf-sub-card { margin-bottom: 16px; }
.cf-toolbar { margin-bottom: 16px; }
.cf-dns-search { width: 200px; }
.cf-dns-type-filter { width: 120px; }
/* 移动端：搜索/筛选各占一行，四个操作按钮 2×2 */
.cf-toolbar.is-mobile :deep(.cf-toolbar-space) {
  display: grid !important;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  width: 100%;
}
.cf-toolbar.is-mobile :deep(.cf-toolbar-space .ant-space-item) {
  width: auto !important;
  max-width: none !important;
  margin: 0 !important;
}
.cf-toolbar.is-mobile :deep(.cf-toolbar-space .ant-space-item:nth-child(1)),
.cf-toolbar.is-mobile :deep(.cf-toolbar-space .ant-space-item:nth-child(2)) {
  grid-column: 1 / -1;
}
.cf-toolbar.is-mobile :deep(.cf-toolbar-space .ant-space-item:nth-child(n + 3) .ant-btn) {
  width: 100%;
}
.cf-toolbar.is-mobile .cf-dns-search,
.cf-toolbar.is-mobile .cf-dns-type-filter { width: 100%; }
.cf-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-sub);
}
.cf-label-inline {
  font-size: 13px;
  color: var(--text-sub);
}
.cf-mobile-pagination {
  margin-top: 12px;
  text-align: center;
}
.mobile-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
  background: var(--bg-card, transparent);
}
.mobile-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.mobile-card-title { font-weight: 600; }
.mobile-card-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 4px;
}
.mobile-card-row .label { color: var(--text-sub); flex-shrink: 0; }
.mobile-card-row .value { text-align: right; word-break: break-all; }
.cf-dns-special-target {
  color: var(--primary, #1677ff);
  font-weight: 500;
}
.cf-dns-tunnel-link {
  padding: 0;
  height: auto;
  font-weight: 500;
}
.cf-dns-tunnel-link.mobile {
  height: auto;
  padding: 0;
  white-space: normal;
  text-align: right;
}
.cf-dns-special-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--text-sub);
  line-height: 1.5;
}
</style>
