
        const domainSelectOptions = computed(() => domains.value.map(d => ({ label: d.domainName, value: d.domainName })));
<template>
  <div class="alidns-page">
    <a-alert
      v-if="!configured"
      type="warning"
      show-icon
      class="alidns-alert"
      message="尚未配置阿里云DNS"
      description="请先在「系统设置」→「阿里云DNS」填写 AccessKey ID 和 AccessKey Secret，并点击测试连接。"
    />

    <div class="alidns-toolbar">
      <a-space wrap>
        <a-button :loading="domainLoading" @click="loadDomains(1)">
          <template #icon><ReloadOutlined /></template>
          刷新域名
        </a-button>
        <a-button type="primary" :disabled="!selectedDomain" @click="openRecordModal()">
          <template #icon><PlusOutlined /></template>
          添加解析
        </a-button>
      </a-space>
      <a-input-search
        v-model:value="recordSearch"
        class="alidns-record-search"
        placeholder="搜索主机记录或记录值"
        allow-clear
        :disabled="!selectedDomain"
        @search="loadRecords(1)"
      />
    </div>

    <div class="alidns-layout">
      <section class="alidns-domain-panel">
        <div class="panel-title">域名</div>

        <!-- 移动端：下拉选择 -->
        <a-select
          v-if="isMobile"
          :value="selectedDomain"
          :loading="domainLoading"
          :options="domainSelectOptions"
          :placeholder="domains.length === 0 ? '暂无域名' : '选择域名'"
          class="mobile-domain-select"
          @change="selectDomain"
        />

        <!-- 桌面端：按钮列表 -->
        <a-spin v-else :spinning="domainLoading">
          <a-empty v-if="domains.length === 0" description="暂无域名" />
          <button
            v-for="domain in domains"
            :key="domain.domainName"
            type="button"
            class="domain-item"
            :class="{ active: selectedDomain === domain.domainName }"
            @click="selectDomain(domain.domainName)"
          >
            <!-- DNS 状态标签 -->
            <span 
              v-if="domain.dnsStatus === 'normal'"
              class="domain-status domain-status-normal"
            >正常</span>
            <span 
              v-else-if="domain.dnsStatus === 'not_system'"
              class="domain-status domain-status-not-system"
            >未绑定DNS</span>
            
            <!-- 域名名称 -->
            <span class="domain-name">{{ domain.domainName }}</span>
            
            <!-- 记录数量 -->
            <span class="domain-meta">{{ domain.recordCount || 0 }} 条记录</span>
          </button>
        </a-spin>
        <a-pagination
          v-if="domainTotal > domainPerPage"
          v-model:current="domainPage"
          simple
          size="small"
          :page-size="domainPerPage"
          :total="domainTotal"
          @change="loadDomains"
        />
      </section>

      <section class="alidns-record-panel">
        <div class="record-panel-head">
          <div>
            <div class="panel-title">{{ selectedDomain || '解析记录' }}</div>
            <div class="panel-subtitle">支持默认线路、中国移动、中国联通、中国电信等智能 DNS 线路</div>
          </div>
          <a-space wrap>
            <a-select
              v-model:value="typeFilter"
              class="record-filter"
              allow-clear
              placeholder="类型"
              :options="typeOptions"
              :disabled="!selectedDomain"
              @change="loadRecords(1)"
            />
            <a-select
              v-model:value="lineFilter"
              class="record-filter"
              allow-clear
              show-search
              option-filter-prop="label"
              placeholder="线路"
              :options="lineOptions"
              :disabled="!selectedDomain"
              @change="loadRecords(1)"
            />
            <a-button :loading="recordLoading" :disabled="!selectedDomain" @click="loadRecords(recordPage)">
              <template #icon><ReloadOutlined /></template>
              刷新记录
            </a-button>
          </a-space>
        </div>

        <a-table
          v-if="!isMobile"
          :columns="recordColumns"
          :data-source="records"
          :loading="recordLoading"
          :pagination="recordPagination"
          row-key="recordId"
          size="small"
          @change="onRecordTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'type'">
              <a-tag>{{ record.type }}</a-tag>
            </template>
            <template v-else-if="column.key === 'name'">
              <span class="record-name">{{ record.rr }}.{{ selectedDomain }}</span>
            </template>
            <template v-else-if="column.key === 'line'">
              {{ lineLabel(record.line) }}
            </template>
            <template v-else-if="column.key === 'status'">
              <a-switch
                size="small"
                :checked="record.status === 'ENABLE'"
                :loading="statusLoadingId === record.recordId"
                @change="(checked: boolean) => toggleRecordStatus(record, checked)"
              />
            </template>
            <template v-else-if="column.key === 'actions'">
              <a-space size="small">
                <a-button type="link" size="small" @click="openRecordModal(record)">编辑</a-button>
                <a-popconfirm title="确定删除此解析记录？" @confirm="deleteRecord(record)">
                  <a-button type="link" danger size="small">删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>

        <a-spin v-else :spinning="recordLoading">
          <a-empty v-if="records.length === 0" description="暂无解析记录" />
          <div v-for="record in records" :key="record.recordId" class="mobile-record-card">
            <div class="mobile-record-head">
              <div>
                <span class="mobile-record-title">{{ record.rr }}.{{ selectedDomain }}</span>
                <a-tag>{{ record.type }}</a-tag>
              </div>
              <a-switch
                size="small"
                :checked="record.status === 'ENABLE'"
                :loading="statusLoadingId === record.recordId"
                @change="(checked: boolean) => toggleRecordStatus(record, checked)"
              />
            </div>
            <div class="mobile-record-row"><span>记录值</span><strong>{{ record.value }}</strong></div>
            <div class="mobile-record-row"><span>线路</span><strong>{{ lineLabel(record.line) }}</strong></div>
            <div class="mobile-record-row"><span>TTL</span><strong>{{ record.ttl || '—' }}</strong></div>
            <div v-if="record.priority != null" class="mobile-record-row">
              <span>优先级</span><strong>{{ record.priority }}</strong>
            </div>
            <a-space wrap class="mobile-record-actions">
              <a-button size="small" @click="openRecordModal(record)">编辑</a-button>
              <a-popconfirm title="确定删除此解析记录？" @confirm="deleteRecord(record)">
                <a-button size="small" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </div>
          <a-pagination
            v-if="recordTotal > recordPerPage"
            v-model:current="recordPage"
            size="small"
            :page-size="recordPerPage"
            :total="recordTotal"
            @change="loadRecords"
          />
        </a-spin>
      </section>
    </div>

    <a-modal
      v-model:open="recordModalVisible"
      :title="editingRecordId ? '编辑解析记录' : '添加解析记录'"
      :width="isMobile ? '100%' : 560"
      :confirm-loading="recordSaveLoading"
      :mask-closable="false"
      ok-text="保存"
      @ok="saveRecord"
    >
      <a-form layout="vertical">
        <a-form-item label="记录类型" required>
          <a-select v-model:value="recordForm.type" :options="typeOptions" />
        </a-form-item>
        <a-form-item label="主机记录" required>
          <a-input v-model:value="recordForm.rr" placeholder="如 www 或 @" />
        </a-form-item>
        <a-form-item label="记录值" required>
          <a-input v-model:value="recordForm.value" placeholder="IP、域名或文本" />
        </a-form-item>
        <a-form-item label="智能线路">
          <a-select
            v-model:value="recordForm.line"
            show-search
            option-filter-prop="label"
            :options="lineOptions"
            placeholder="默认"
          />
        </a-form-item>
        <a-form-item label="TTL">
          <a-input-number v-model:value="recordForm.ttl" :min="1" style="width: 100%" />
        </a-form-item>
        <a-form-item v-if="prioritySupported" label="优先级">
          <a-input-number v-model:value="recordForm.priority" :min="0" :max="65535" style="width: 100%" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { useIsMobile } from '../composables/useIsMobile'
import {
  addAliDNSRecord,
  deleteAliDNSRecord,
  getAliDNSAccountConfig,
  listAliDNSDomains,
  listAliDNSLines,
  listAliDNSRecords,

  setAliDNSRecordStatus,
  updateAliDNSRecord,
} from '../api/alidns'

defineOptions({ name: 'AliDNS' })

type DomainRow = {
  domainId?: string
  domainName: string
  punyCode?: string
  recordCount?: number
  dnsStatus?: 'normal' | 'not_system'
}

type DnsRecord = {
  recordId: string
  rr: string
  type: string
  value: string
  line?: string
  lineName?: string
  ttl?: number
  priority?: number
  status?: string
}

type LineRow = {
  lineCode: string
  lineName?: string
  lineDisplayName?: string
}

const { isMobile } = useIsMobile()

// Mobile domain dropdown options
const domainSelectOptions = computed(() =>
  domains.value.map((d: DomainRow) => ({ label: d.domainName, value: d.domainName }))
);

const configured = ref(false)
const domains = ref<DomainRow[]>([])
const domainLoading = ref(false)
const domainPage = ref(1)
const domainPerPage = ref(30)
const domainTotal = ref(0)
const selectedDomain = ref('')

const records = ref<DnsRecord[]>([])
const recordLoading = ref(false)
const recordPage = ref(1)
const recordPerPage = ref(50)
const recordTotal = ref(0)
const recordSearch = ref('')
const typeFilter = ref<string | undefined>()
const lineFilter = ref<string | undefined>()

const lines = ref<LineRow[]>([])
const recordModalVisible = ref(false)
const recordSaveLoading = ref(false)
const editingRecordId = ref('')
const statusLoadingId = ref('')
const recordForm = reactive({
  rr: '@',
  type: 'A',
  value: '',
  line: 'default',
  ttl: 600,
  priority: null as number | null,
})

const typeOptions = ['A', 'AAAA', 'CNAME', 'TXT', 'MX', 'NS', 'SRV', 'CAA'].map((value) => ({ label: value, value }))

const lineOptions = computed(() => {
  const base = lines.value
    .filter((line) => line.lineCode)
    .map((line) => ({
      label: line.lineDisplayName || line.lineName || line.lineCode,
      value: line.lineCode,
    }))
  return base.length > 0 ? base : [{ label: '默认', value: 'default' }]
})

const prioritySupported = computed(() => ['MX', 'SRV'].includes(recordForm.type))
const recordPagination = computed(() => ({
  current: recordPage.value,
  pageSize: recordPerPage.value,
  total: recordTotal.value,
  showSizeChanger: true,
  showTotal: (total: number) => `共 ${total} 条`,
}))

const recordColumns = [
  { title: '类型', key: 'type', width: 90 },
  { title: '主机记录', key: 'name', ellipsis: true },
  { title: '记录值', dataIndex: 'value', key: 'value', ellipsis: true },
  { title: '线路', key: 'line', width: 130 },
  { title: 'TTL', dataIndex: 'ttl', key: 'ttl', width: 90 },
  { title: '优先级', dataIndex: 'priority', key: 'priority', width: 90 },
  { title: '启用', key: 'status', width: 80 },
  { title: '操作', key: 'actions', width: 130 },
]

async function loadConfig() {
  try {
    const res = await getAliDNSAccountConfig()
    configured.value = res.data?.configured === true
  } catch {
    configured.value = false
  }
}

async function loadDomains(page = domainPage.value) {
  domainLoading.value = true
  try {
    const res = await listAliDNSDomains(page, domainPerPage.value)
    const data = res.data || {}
    domains.value = data.records || data || []
    domainTotal.value = data.total ?? domains.value.length
    domainPage.value = page
    if (!selectedDomain.value && domains.value.length > 0) {
      await selectDomain(domains.value[0].domainName)
    }
  } finally {
    domainLoading.value = false
  }
}

async function selectDomain(domainName: string) {
  selectedDomain.value = domainName
  recordPage.value = 1
  await Promise.all([loadLines(), loadRecords(1)])
}

async function loadLines() {
  if (!selectedDomain.value) return
  try {
    const res = await listAliDNSLines(selectedDomain.value)
    lines.value = res.data || []
  } catch {
    // Fallback to static lines if API fails
    lines.value = [
      { lineCode: "default", lineName: "默认" },
      { lineCode: "telecom", lineName: "中国电信" },
      { lineCode: "unicom", lineName: "中国联通" },
      { lineCode: "mobile", lineName: "中国移动" },
      { lineCode: "edu", lineName: "教育网" },
      { lineCode: "oversea", lineName: "境外" },
    ]
  }
}

async function loadRecords(page = recordPage.value) {
  if (!selectedDomain.value) return
  recordLoading.value = true
  try {
    const keyword = recordSearch.value.trim()
    const res = await listAliDNSRecords({
      domainName: selectedDomain.value,
      page,
      perPage: recordPerPage.value,
      rrKeyWord: keyword || undefined,
      typeKeyWord: typeFilter.value || undefined,
      line: lineFilter.value || undefined,
    })
    const data = res.data || {}
    records.value = sortRecords(data.records || [])
    recordTotal.value = data.total ?? records.value.length
    recordPage.value = page
  } finally {
    recordLoading.value = false
  }
}

function onRecordTableChange(pagination: any) {
  recordPerPage.value = pagination.pageSize || recordPerPage.value
  loadRecords(pagination.current || 1)
}

function openRecordModal(record?: DnsRecord) {
  if (!selectedDomain.value) return
  editingRecordId.value = record?.recordId || ''
  recordForm.rr = record?.rr || '@'
  recordForm.type = record?.type || 'A'
  recordForm.value = record?.value || ''
  recordForm.line = record?.line || 'default'
  recordForm.ttl = record?.ttl || 600
  recordForm.priority = record?.priority ?? null
  recordModalVisible.value = true
}

async function saveRecord() {
  if (!recordForm.rr.trim()) return message.warning('请填写主机记录')
  if (!recordForm.value.trim()) return message.warning('请填写记录值')
  recordSaveLoading.value = true
  try {
    const payload = {
      domainName: selectedDomain.value,
      rr: recordForm.rr.trim(),
      type: recordForm.type,
      value: recordForm.value.trim(),
      line: recordForm.line,
      ttl: recordForm.ttl,
      priority: prioritySupported.value ? recordForm.priority : null,
    }
    if (editingRecordId.value) {
      await updateAliDNSRecord({ ...payload, recordId: editingRecordId.value })
    } else {
      await addAliDNSRecord(payload)
    }
    message.success('已保存')
    recordModalVisible.value = false
    await loadRecords(recordPage.value)
    await loadDomains(domainPage.value)
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    recordSaveLoading.value = false
  }
}

async function deleteRecord(record: DnsRecord) {
  await deleteAliDNSRecord(record.recordId)
  message.success('已删除')
  await loadRecords(recordPage.value)
  await loadDomains(domainPage.value)
}

async function toggleRecordStatus(record: DnsRecord, checked: boolean) {
  statusLoadingId.value = record.recordId
  try {
    await setAliDNSRecordStatus(record.recordId, checked ? 'ENABLE' : 'DISABLE')
    record.status = checked ? 'ENABLE' : 'DISABLE'
    message.success(checked ? '已启用' : '已暂停')
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    statusLoadingId.value = ''
  }
}

function lineLabel(code?: string) {
  if (!code) return '默认'
  const found = lines.value.find((line) => line.lineCode === code)
  if (found) {
    return found.lineDisplayName || found.lineName || code
  }
  // Fallback static mapping
  const staticMap: Record<string, string> = {
    'default': '默认',
    'telecom': '中国电信',
    'unicom': '中国联通',
    'mobile': '中国移动',
    'edu': '教育网',
    'oversea': '境外',
  }
  return staticMap[code] || code
}

// Sort records by type priority, then by line priority
const TYPE_PRIORITY: Record<string, number> = {
  A: 0,
  AAAA: 1,
  CNAME: 2,
  MX: 3,
  TXT: 4,
}
const LINE_PRIORITY: Record<string, number> = {
  默认: 0,
  中国电信: 1,
  中国联通: 2,
  中国移动: 3,
  教育网: 4,
  境外: 5,
  搜索引擎: 6,
  中国地区: 7,
}

function sortRecords(list: DnsRecord[]): DnsRecord[] {
  return [...list].sort((a, b) => {
    // Primary: type priority
    const aTp = TYPE_PRIORITY[a.type] ?? 999;
    const bTp = TYPE_PRIORITY[b.type] ?? 999;
    if (aTp !== bTp) return aTp - bTp;
    // Secondary: line priority (only for A and AAAA)
    if (a.type === 'A' || a.type === 'AAAA') {
      const aLn = LINE_PRIORITY[a.lineName || '默认'] ?? 999;
      const bLn = LINE_PRIORITY[b.lineName || '默认'] ?? 999;
      if (aLn !== bLn) return aLn - bLn;
    }
    return 0;
  });
}

onMounted(async () => {
  await loadConfig()
  if (configured.value) await loadDomains(1)
})
</script>

<style scoped>
.alidns-page {
  padding: 8px 0;
}
.alidns-alert {
  margin-bottom: 16px;
}
.alidns-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.alidns-record-search {
  width: min(360px, 100%);
}
.alidns-layout {
  display: flex;
  flex-direction: column;
  gap: 16px;
  align-items: stretch;
  width: 100%;
  max-width: 1400px;
}
.alidns-domain-panel {
  width: 280px;
  flex-shrink: 0;
  max-height: 230px;
  overflow-y: auto;
}
.alidns-domain-panel::-webkit-scrollbar {
  width: 6px;
}
.alidns-domain-panel::-webkit-scrollbar-thumb {
  background: #c0c4cc;
  border-radius: 3px;
}
.alidns-domain-panel,
.alidns-record-panel {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-card);
  box-shadow: var(--shadow-card);
  padding: 16px;
}
.panel-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-main);
}
.panel-subtitle {
  color: var(--text-sub);
  font-size: 12px;
  margin-top: 4px;
}
.domain-item {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  padding: 10px 12px;
  margin-top: 8px;
  background: transparent;
  color: var(--text-main);
  cursor: pointer;
  text-align: left;
}
.domain-item:hover,
.domain-item.active {
  border-color: var(--primary);
  background: var(--primary-light);
}
.domain-name {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.domain-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 2px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.5;
  flex-shrink: 0;
  white-space: nowrap;
}
.domain-status-normal {
  background: #52c41a;
  color: #fff;
}
.domain-status-not-system {
  background: #8c8c8c;
  color: #fff;
}
.status-normal {
  background: #52c41a;
}
.status-not_system {
  background: #ff4d4f;
}
.domain-meta {
  flex-shrink: 0;
  color: var(--text-sub);
  font-size: 12px;
}
.record-panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.record-filter {
  width: 150px;
}
.record-name {
  font-weight: 600;
}
.mobile-record-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 10px;
  background: var(--input-bg);
}
.mobile-record-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}
.mobile-record-title {
  display: block;
  font-weight: 700;
  margin-bottom: 6px;
  word-break: break-all;
}
.mobile-record-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 6px;
  color: var(--text-sub);
}
.mobile-record-row strong {
  color: var(--text-main);
  text-align: right;
  word-break: break-all;
}
.mobile-record-actions {
  margin-top: 12px;
}
@media (max-width: 900px) {
  .alidns-toolbar,
  .record-panel-head {
    flex-direction: column;
    align-items: stretch;
  }
  
  .record-filter,
  .alidns-record-search {
    width: 100%;
  }
}

.mobile-domain-select {
  width: 100%;
  margin-bottom: 8px;
}
</style>











