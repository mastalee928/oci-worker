<template>
  <div class="cf-dns-tab">
    <CfZoneBar
      v-model:zone-id="innerZoneId"
      :cf-configured="cfConfigured"
      show-create-zone
      @zone-change="onZoneChange"
      @create-zone="openCreateZoneModal"
    />

    <a-empty v-if="!innerZoneId && cfConfigured" description="请先选择区域" />

    <template v-else-if="innerZoneId">
      <!-- Zone 详情 -->
      <a-card size="small" title="区域详情" class="cf-sub-card" :loading="zoneDetailLoading">
        <template #extra>
          <a-space wrap>
            <span class="cf-label-inline">暂停解析</span>
            <a-switch
              :checked="!!zoneDetail?.paused"
              :loading="zonePausedLoading"
              @change="handleZonePaused"
            />
            <a-popconfirm
              title="确定删除此区域？此操作不可恢复。"
              ok-text="删除"
              ok-type="danger"
              @confirm="handleDeleteZone"
            >
              <a-button danger size="small" :loading="zoneDeleteLoading">删除区域</a-button>
            </a-popconfirm>
          </a-space>
        </template>
        <a-descriptions v-if="zoneDetail" bordered size="small" :column="isMobile ? 1 : 3">
          <a-descriptions-item label="域名">{{ zoneDetail.name }}</a-descriptions-item>
          <a-descriptions-item label="状态">
            <a-tag :color="zoneDetail.status === 'active' ? 'success' : 'default'">{{ zoneDetail.status }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="套餐">{{ zoneDetail.planName || '—' }}</a-descriptions-item>
          <a-descriptions-item label="Zone ID" :span="isMobile ? 1 : 3">{{ zoneDetail.id }}</a-descriptions-item>
          <a-descriptions-item label="NS 服务器" :span="isMobile ? 1 : 3">
            <span v-if="zoneDetail.nameServers?.length">{{ zoneDetail.nameServers.join(' · ') }}</span>
            <span v-else>—</span>
          </a-descriptions-item>
        </a-descriptions>
      </a-card>

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
      <div class="cf-toolbar">
        <a-space wrap>
          <a-input-search
            v-model:value="dnsSearch"
            placeholder="搜索记录名称"
            allow-clear
            style="width: 200px"
            @search="onDnsFilterChange"
          />
          <a-select
            v-model:value="dnsTypeFilter"
            placeholder="类型筛选"
            allow-clear
            style="width: 120px"
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
        row-key="id"
        size="middle"
        :scroll="{ x: 1100 }"
        :pagination="dnsPagination"
        @change="onDnsTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'proxied'">
            <a-tag v-if="record.proxied" color="orange">代理</a-tag>
            <span v-else>—</span>
          </template>
          <template v-else-if="column.key === 'priority'">
            {{ record.priority ?? '—' }}
          </template>
          <template v-else-if="column.key === 'comment'">
            {{ record.comment || '—' }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space wrap>
              <a-button type="link" size="small" @click="openDnsModal(record)">编辑</a-button>
              <a-popconfirm title="确定删除此 DNS 记录？" @confirm="handleDeleteDns(record.id)">
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>

      <a-spin v-else :spinning="dnsLoading">
        <a-empty v-if="!dnsLoading && dnsRecords.length === 0" description="暂无 DNS 记录" />
        <div v-for="item in dnsRecords" :key="item.id" class="mobile-card">
          <div class="mobile-card-header">
            <span class="mobile-card-title">{{ item.type }} · {{ item.name }}</span>
            <a-tag v-if="item.proxied" color="orange">代理</a-tag>
          </div>
          <div class="mobile-card-body">
            <div class="mobile-card-row"><span class="label">内容</span><span class="value">{{ item.content }}</span></div>
            <div class="mobile-card-row"><span class="label">TTL</span><span class="value">{{ item.ttl }}</span></div>
            <div v-if="item.priority != null" class="mobile-card-row"><span class="label">优先级</span><span class="value">{{ item.priority }}</span></div>
            <div v-if="item.comment" class="mobile-card-row"><span class="label">备注</span><span class="value">{{ item.comment }}</span></div>
          </div>
          <a-space wrap style="margin-top: 8px">
            <a-button size="small" @click="openDnsModal(item)">编辑</a-button>
            <a-popconfirm title="确定删除？" @confirm="handleDeleteDns(item.id)">
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
    </template>

    <!-- 创建区域 -->
    <a-modal
      v-model:open="createZoneVisible"
      title="添加区域"
      :confirm-loading="createZoneLoading"
      @ok="submitCreateZone"
    >
      <a-form layout="vertical">
        <a-form-item label="域名" required>
          <a-input v-model:value="createZoneName" placeholder="如 example.com" @pressEnter="submitCreateZone" />
        </a-form-item>
        <p class="cf-hint">创建后请在域名注册商处将 NS 指向 Cloudflare 提供的名称服务器。</p>
      </a-form>
    </a-modal>

    <!-- DNS 记录 -->
    <a-modal
      v-model:open="dnsModalVisible"
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
import CfZoneBar from './CfZoneBar.vue'
import {
  getCfZoneDetail,
  createCfZone,
  deleteCfZone,
  setCfZonePaused,
  listCfDnsRecords,
  addCfDnsRecord,
  updateCfDnsRecord,
  deleteCfDnsRecord,
  exportCfDnsRecords,
  importCfDnsRecords,
  getCfDnssec,
  setCfDnssec,
} from '../../api/cloudflare'

interface CfZoneDetail {
  id: string
  name: string
  status: string
  paused?: boolean
  planName?: string
  nameServers?: string[]
}

interface DnsRecord {
  id: string
  type: string
  name: string
  content: string
  proxied?: boolean
  ttl?: number
  priority?: number
  comment?: string
}

const props = defineProps<{
  cfConfigured: boolean
  zoneId?: string
}>()

const emit = defineEmits<{
  'update:zoneId': [value: string | undefined]
}>()

const innerZoneId = computed({
  get: () => props.zoneId,
  set: (v) => emit('update:zoneId', v),
})

const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }

const zoneDetailLoading = ref(false)
const zoneDetail = ref<CfZoneDetail | null>(null)
const zonePausedLoading = ref(false)
const zoneDeleteLoading = ref(false)

const createZoneVisible = ref(false)
const createZoneLoading = ref(false)
const createZoneName = ref('')

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
const dnsTypeFilterOptions = [{ value: '', label: '全部类型' }, ...dnsTypeOptions]

const dnsProxiedSupported = computed(() => ['A', 'AAAA', 'CNAME'].includes(dnsForm.type))
const dnsPrioritySupported = computed(() => ['MX', 'SRV'].includes(dnsForm.type))

const dnsColumns = [
  { title: '类型', dataIndex: 'type', width: 72 },
  { title: '名称', dataIndex: 'name', ellipsis: true },
  { title: '内容', dataIndex: 'content', ellipsis: true },
  { title: '代理', key: 'proxied', width: 72 },
  { title: 'TTL', dataIndex: 'ttl', width: 72 },
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

async function onZoneChange(zoneId: string | undefined) {
  if (!zoneId) {
    zoneDetail.value = null
    dnsRecords.value = []
    dnssec.value = null
    return
  }
  await Promise.all([loadZoneDetail(), loadDnssec(), loadDnsRecords()])
}

async function loadZoneDetail() {
  if (!innerZoneId.value) return
  zoneDetailLoading.value = true
  try {
    const res = await getCfZoneDetail({ zoneId: innerZoneId.value })
    zoneDetail.value = res.data || null
  } catch {
    zoneDetail.value = null
  } finally {
    zoneDetailLoading.value = false
  }
}

async function handleZonePaused(paused: boolean) {
  if (!innerZoneId.value) return
  zonePausedLoading.value = true
  try {
    const res = await setCfZonePaused({ zoneId: innerZoneId.value, paused })
    if (zoneDetail.value) {
      zoneDetail.value.paused = res.data?.paused ?? paused
    }
    message.success(paused ? '区域已暂停' : '区域已恢复')
  } finally {
    zonePausedLoading.value = false
  }
}

async function handleDeleteZone() {
  if (!innerZoneId.value) return
  zoneDeleteLoading.value = true
  try {
    await deleteCfZone({ zoneId: innerZoneId.value })
    message.success('区域已删除')
    innerZoneId.value = undefined
    zoneDetail.value = null
    dnsRecords.value = []
    dnssec.value = null
  } finally {
    zoneDeleteLoading.value = false
  }
}

function openCreateZoneModal() {
  createZoneName.value = ''
  createZoneVisible.value = true
}

async function submitCreateZone() {
  const name = createZoneName.value.trim()
  if (!name) {
    message.warning('请输入域名')
    return
  }
  createZoneLoading.value = true
  try {
    const res = await createCfZone({ name })
    message.success('区域已创建')
    createZoneVisible.value = false
    if (res.data?.id) {
      innerZoneId.value = res.data.id
      await onZoneChange(res.data.id)
    }
  } finally {
    createZoneLoading.value = false
  }
}

async function loadDnssec() {
  if (!innerZoneId.value) return
  dnssecLoading.value = true
  try {
    const res = await getCfDnssec({ zoneId: innerZoneId.value })
    dnssec.value = res.data || null
  } catch {
    dnssec.value = null
  } finally {
    dnssecLoading.value = false
  }
}

async function handleSetDnssec(status: 'active' | 'disabled') {
  if (!innerZoneId.value) return
  dnssecSetLoading.value = true
  try {
    const res = await setCfDnssec({ zoneId: innerZoneId.value, status })
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

async function loadDnsRecords() {
  if (!innerZoneId.value) return
  dnsLoading.value = true
  try {
    const res = await listCfDnsRecords({
      zoneId: innerZoneId.value,
      page: dnsPage.value,
      perPage: dnsPerPage.value,
      search: dnsSearch.value.trim() || undefined,
      type: dnsTypeFilter.value || undefined,
    })
    const data = res.data || {}
    dnsRecords.value = data.records || []
    dnsTotal.value = data.total ?? dnsRecords.value.length
    dnsPage.value = data.page ?? dnsPage.value
    dnsPerPage.value = data.perPage ?? dnsPerPage.value
    dnsTotalPages.value = data.totalPages ?? 1
  } finally {
    dnsLoading.value = false
  }
}

function openDnsModal(record?: DnsRecord) {
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
  if (!innerZoneId.value) return
  if (!dnsForm.name.trim() || !dnsForm.content.trim()) {
    message.warning('请填写名称与内容')
    return
  }
  const payload = {
    zoneId: innerZoneId.value,
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

async function handleDeleteDns(recordId: string) {
  if (!innerZoneId.value) return
  await deleteCfDnsRecord({ zoneId: innerZoneId.value, recordId })
  message.success('已删除')
  await loadDnsRecords()
}

function openImportModal() {
  importBindContent.value = ''
  importProxied.value = false
  importModalVisible.value = true
}

async function submitImportDns() {
  if (!innerZoneId.value) return
  if (!importBindContent.value.trim()) {
    message.warning('请粘贴 BIND 内容')
    return
  }
  importLoading.value = true
  try {
    await importCfDnsRecords({
      zoneId: innerZoneId.value,
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
  if (!innerZoneId.value || !zoneDetail.value) return
  exportLoading.value = true
  try {
    const res = await exportCfDnsRecords({ zoneId: innerZoneId.value })
    const content = typeof res.data === 'string' ? res.data : String(res.data ?? '')
    const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${zoneDetail.value.name || 'zone'}.bind`
    a.click()
    URL.revokeObjectURL(url)
    message.success('导出成功')
  } finally {
    exportLoading.value = false
  }
}

watch(() => props.zoneId, (id) => {
  if (id) onZoneChange(id)
})

onMounted(() => {
  window.addEventListener('resize', checkMobile)
  if (props.zoneId) onZoneChange(props.zoneId)
})
onUnmounted(() => window.removeEventListener('resize', checkMobile))
</script>

<style scoped>
.cf-dns-tab { min-height: 120px; }
.cf-sub-card { margin-bottom: 16px; }
.cf-toolbar { margin-bottom: 16px; }
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
</style>
