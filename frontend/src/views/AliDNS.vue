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
        <a-spin :spinning="domainLoading">
          <a-empty v-if="domains.length === 0" description="暂无域名" />
          <button
            v-for="domain in domains"
            :key="domain.domainName"
            type="button"
            class="domain-item"
            :class="{ active: selectedDomain === domain.domainName }"
            @click="selectDomain(domain.domainName)"
          >
            <span class="domain-name">{{ domain.domainName }}</span>
            <span class="domain-status" :class="`status-${domain.dnsStatus || `normal`}`" :title="domain.dnsStatus === `not_system` ? `未使用系统分配DNS地址` : `正常`"></span>
            <span class="domain-name">{{ domain.domainName }}</span>
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
  listAliDNSDomainDnsServers,

defineOptions({ name: 'AliDNS' })

type DomainRow = {
  domainId?: string
  domainName: string
  punyCode?: string
  recordCount?: number
  recordCount?: number
  dnsStatus?: "normal" | "not_system"
}
  recordId: string
  rr: string
  type: string
  value: string
  line?: string
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
  }
  // Load DNS status for all domains
  await loadDomainDnsStatus()
}

async function loadDomainDnsStatus() {
  if (!domains.value.length) return
  try {
    const promises = domains.value.map(async (domain) => {
      try {
        const res = await listAliDNSDomainDnsServers(domain.domainName)
        const servers = res.data || []
        const serverList = servers.map((s: any) => s.server || '').join(',')
        const isSystemDns = serverList.includes('alidns') || serverList.includes('hichina')
        domain.dnsStatus = isSystemDns ? 'normal' : 'not_system'
      } catch {
        domain.dnsStatus = 'normal'
      }
    })
    await Promise.all(promises)
  } catch {
    // ignore
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
    lines.value = [{ lineCode: 'default', lineName: '默认' }]
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
      rrKeyWord: keyword,
      valueKeyWord: keyword,
      typeKeyWord: typeFilter.value,
      line: lineFilter.value,
    })
    const data = res.data || {}
    records.value = data.records || []
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
  const found = lines.value.find((line) => line.lineCode === code)
  return found?.lineDisplayName || found?.lineName || code || '默认'
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
  display: grid;
  grid-template-columns: minmax(220px, 300px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
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
.domain-status {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-right: 6px;
}
.domain-status.status-normal {
  background: #52c41a;
}
.domain-status.status-not_system {
  background: #ff4d4f;
}

.domain-name {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  .alidns-layout {
    grid-template-columns: 1fr;
  }
  .record-filter,
  .alidns-record-search {
    width: 100%;
  }
}
</style>
