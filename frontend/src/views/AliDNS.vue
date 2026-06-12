
        const domainSelectOptions = computed(() => domains.value.map(d => ({ label: d.domainName, value: d.domainName })));
<template>
  <div class="alidns-page">
    <a-alert
      v-if="!configured"
      type="warning"
      show-icon
      class="alidns-alert"
      message="灏氭湭閰嶇疆闃块噷浜慏NS"
      description="璇峰厛鍦ㄣ€岀郴缁熻缃€嶁啋銆岄樋閲屼簯DNS銆嶅～鍐?AccessKey ID 鍜?AccessKey Secret锛屽苟鐐瑰嚮娴嬭瘯杩炴帴銆?
    />

    <div class="alidns-toolbar">
      <a-space wrap>
        <a-button :loading="domainLoading" @click="loadDomains(1)">
          <template #icon><ReloadOutlined /></template>
          鍒锋柊鍩熷悕
        </a-button>
        <a-button type="primary" :disabled="!selectedDomain" @click="openRecordModal()">
          <template #icon><PlusOutlined /></template>
          娣诲姞瑙ｆ瀽
        </a-button>
      </a-space>
      <a-input-search
        v-model:value="recordSearch"
        class="alidns-record-search"
        placeholder="鎼滅储涓绘満璁板綍鎴栬褰曞€?
        allow-clear
        :disabled="!selectedDomain"
        @search="loadRecords(1)"
      />
    </div>

    <div class="alidns-layout">
      <section class="alidns-domain-panel">
        <div class="panel-title">鍩熷悕</div>

        <!-- 绉诲姩绔細涓嬫媺閫夋嫨 -->
        <a-select
          v-if="isMobile"
          :value="selectedDomain"
          :loading="domainLoading"
          :options="domainSelectOptions"
          :placeholder="domains.length === 0 ? '鏆傛棤鍩熷悕' : '閫夋嫨鍩熷悕'"
          class="mobile-domain-select"
          @change="selectDomain"
        />

        <!-- 妗岄潰绔細鎸夐挳鍒楄〃 -->
        <a-spin v-else :spinning="domainLoading">
          <a-empty v-if="domains.length === 0" description="鏆傛棤鍩熷悕" />
          <button
            v-for="domain in domains"
            :key="domain.domainName"
            type="button"
            class="domain-item"
            :class="{ active: selectedDomain === domain.domainName }"
            @click="selectDomain(domain.domainName)"
          >
            <!-- DNS 鐘舵€佹爣绛?-->
            <span 
              v-if="domain.dnsStatus === 'normal'"
              class="domain-status domain-status-normal"
            >姝ｅ父</span>
            <span 
              v-else-if="domain.dnsStatus === 'not_system'"
              class="domain-status domain-status-not-system"
            >鏈粦瀹欴NS</span>
            
            <!-- 鍩熷悕鍚嶇О -->
            <span class="domain-name">{{ domain.domainName }}</span>
            
            <!-- 璁板綍鏁伴噺 -->
            <span class="domain-meta">{{ domain.recordCount || 0 }} 鏉¤褰?/span>
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
            <div class="panel-title">{{ selectedDomain || '瑙ｆ瀽璁板綍' }}</div>
            <div class="panel-subtitle">鏀寔榛樿绾胯矾銆佷腑鍥界Щ鍔ㄣ€佷腑鍥借仈閫氥€佷腑鍥界數淇＄瓑鏅鸿兘 DNS 绾胯矾</div>
          </div>
          <a-space wrap v-if="!isMobile">
            <a-select
              v-model:value="typeFilter"
              class="record-filter"
              allow-clear
              placeholder="绫诲瀷"
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
              placeholder="绾胯矾"
              :options="lineOptions"
              :disabled="!selectedDomain"
              @change="loadRecords(1)"
            />
            <a-button :loading="recordLoading" :disabled="!selectedDomain" @click="loadRecords(recordPage)">
              <template #icon><ReloadOutlined /></template>
              鍒锋柊璁板綍
            </a-button>
          </a-space>
          <div v-if="isMobile" class="mobile-filters">
            <select v-model="typeFilter" :disabled="!selectedDomain" @change="loadRecords(1)" class="native-select">
              <option value="" disabled>绫诲瀷</option>
              <option v-for="t in typeOptions" :key="t" :value="t">{{ t }}</option>
            </select>
            <select v-model="lineFilter" :disabled="!selectedDomain" @change="loadRecords(1)" class="native-select">
              <option value="" disabled>绾胯矾</option>
              <option v-for="l in lineOptions" :key="l.value" :value="l.value">{{ l.label }}</option>
            </select>
            <a-button :loading="recordLoading" :disabled="!selectedDomain" @click="loadRecords(recordPage)" class="mobile-refresh-btn">
              <template #icon><ReloadOutlined /></template>
              鍒锋柊璁板綍
            </a-button>
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
                <a-button type="link" size="small" @click="openRecordModal(record)">缂栬緫</a-button>
                <a-popconfirm title="纭畾鍒犻櫎姝よВ鏋愯褰曪紵" @confirm="deleteRecord(record)">
                  <a-button type="link" danger size="small">鍒犻櫎</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>

        <a-spin v-else :spinning="recordLoading">
          <a-empty v-if="records.length === 0" description="鏆傛棤瑙ｆ瀽璁板綍" />
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
            <div class="mobile-record-row"><span>璁板綍鍊?/span><strong>{{ record.value }}</strong></div>
            <div class="mobile-record-row"><span>绾胯矾</span><strong>{{ lineLabel(record.line) }}</strong></div>
            <div class="mobile-record-row"><span>TTL</span><strong>{{ record.ttl || '鈥? }}</strong></div>
            <div v-if="record.priority != null" class="mobile-record-row">
              <span>浼樺厛绾?/span><strong>{{ record.priority }}</strong>
            </div>
            <a-space wrap class="mobile-record-actions">
              <a-button size="small" @click="openRecordModal(record)">缂栬緫</a-button>
              <a-popconfirm title="纭畾鍒犻櫎姝よВ鏋愯褰曪紵" @confirm="deleteRecord(record)">
                <a-button size="small" danger>鍒犻櫎</a-button>
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
      :title="editingRecordId ? '缂栬緫瑙ｆ瀽璁板綍' : '娣诲姞瑙ｆ瀽璁板綍'"
      :width="isMobile ? '100%' : 560"
      :confirm-loading="recordSaveLoading"
      :mask-closable="false"
      ok-text="淇濆瓨"
      @ok="saveRecord"
    >
      <a-form layout="vertical">
        <a-form-item label="璁板綍绫诲瀷" required>
          <a-select v-model:value="recordForm.type" :options="typeOptions" />
        </a-form-item>
        <a-form-item label="涓绘満璁板綍" required>
          <a-input v-model:value="recordForm.rr" placeholder="濡?www 鎴?@" />
        </a-form-item>
        <a-form-item label="璁板綍鍊? required>
          <a-input v-model:value="recordForm.value" placeholder="IP銆佸煙鍚嶆垨鏂囨湰" />
        </a-form-item>
        <a-form-item label="鏅鸿兘绾胯矾">
          <a-select
            v-model:value="recordForm.line"
            show-search
            option-filter-prop="label"
            :options="lineOptions"
            placeholder="榛樿"
          />
        </a-form-item>
        <a-form-item label="TTL">
          <a-input-number v-model:value="recordForm.ttl" :min="1" style="width: 100%" />
        </a-form-item>
        <a-form-item v-if="prioritySupported" label="浼樺厛绾?>
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
  return base.length > 0 ? base : [{ label: '榛樿', value: 'default' }]
})

const prioritySupported = computed(() => ['MX', 'SRV'].includes(recordForm.type))
const recordPagination = computed(() => ({
  current: recordPage.value,
  pageSize: recordPerPage.value,
  total: recordTotal.value,
  showSizeChanger: true,
  showTotal: (total: number) => `鍏?${total} 鏉,
}))

const recordColumns = [
  { title: '绫诲瀷', key: 'type', width: 90 },
  { title: '涓绘満璁板綍', key: 'name', ellipsis: true },
  { title: '璁板綍鍊?, dataIndex: 'value', key: 'value', ellipsis: true },
  { title: '绾胯矾', key: 'line', width: 130 },
  { title: 'TTL', dataIndex: 'ttl', key: 'ttl', width: 90 },
  { title: '浼樺厛绾?, dataIndex: 'priority', key: 'priority', width: 90 },
  { title: '鍚敤', key: 'status', width: 80 },
  { title: '鎿嶄綔', key: 'actions', width: 130 },
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
      { lineCode: "default", lineName: "榛樿" },
      { lineCode: "telecom", lineName: "涓浗鐢典俊" },
      { lineCode: "unicom", lineName: "涓浗鑱旈€? },
      { lineCode: "mobile", lineName: "涓浗绉诲姩" },
      { lineCode: "edu", lineName: "鏁欒偛缃? },
      { lineCode: "oversea", lineName: "澧冨" },
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
  if (!recordForm.rr.trim()) return message.warning('璇峰～鍐欎富鏈鸿褰?)
  if (!recordForm.value.trim()) return message.warning('璇峰～鍐欒褰曞€?)
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
    message.success('宸蹭繚瀛?)
    recordModalVisible.value = false
    await loadRecords(recordPage.value)
    await loadDomains(domainPage.value)
  } catch (e: any) {
    message.error(e?.message || '淇濆瓨澶辫触')
  } finally {
    recordSaveLoading.value = false
  }
}

async function deleteRecord(record: DnsRecord) {
  await deleteAliDNSRecord(record.recordId)
  message.success('宸插垹闄?)
  await loadRecords(recordPage.value)
  await loadDomains(domainPage.value)
}

async function toggleRecordStatus(record: DnsRecord, checked: boolean) {
  statusLoadingId.value = record.recordId
  try {
    await setAliDNSRecordStatus(record.recordId, checked ? 'ENABLE' : 'DISABLE')
    record.status = checked ? 'ENABLE' : 'DISABLE'
    message.success(checked ? '宸插惎鐢? : '宸叉殏鍋?)
  } catch (e: any) {
    message.error(e?.message || '鎿嶄綔澶辫触')
  } finally {
    statusLoadingId.value = ''
  }
}

function lineLabel(code?: string) {
  if (!code) return '榛樿'
  const found = lines.value.find((line) => line.lineCode === code)
  if (found) {
    return found.lineDisplayName || found.lineName || code
  }
  // Fallback static mapping
  const staticMap: Record<string, string> = {
    'default': '榛樿',
    'telecom': '涓浗鐢典俊',
    'unicom': '涓浗鑱旈€?,
    'mobile': '涓浗绉诲姩',
    'edu': '鏁欒偛缃?,
    'oversea': '澧冨',
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
  榛樿: 0,
  涓浗鐢典俊: 1,
  涓浗鑱旈€? 2,
  涓浗绉诲姩: 3,
  鏁欒偛缃? 4,
  澧冨: 5,
  鎼滅储寮曟搸: 6,
  涓浗鍦板尯: 7,
}

function sortRecords(list: DnsRecord[]): DnsRecord[] {
  return [...list].sort((a, b) => {
    // Primary: type priority
    const aTp = TYPE_PRIORITY[a.type] ?? 999;
    const bTp = TYPE_PRIORITY[b.type] ?? 999;
    if (aTp !== bTp) return aTp - bTp;
    // Secondary: line priority (only for A and AAAA)
    if (a.type === 'A' || a.type === 'AAAA') {
      const aLn = LINE_PRIORITY[a.lineName || '榛樿'] ?? 999;
      const bLn = LINE_PRIORITY[b.lineName || '榛樿'] ?? 999;
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
  width: 100%;
  max-width: 1400px;
}
.alidns-domain-panel,
.alidns-record-panel {
  width: 100%;
}
.alidns-domain-panel {
  flex-shrink: 0;
  max-height: 400px;
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
  flex: 1;
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
.mobile-filters {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.mobile-filters .ant-btn {
  flex-shrink: 0;
}
/* Native mobile select - system picker on Android/iOS */
.native-select {
  flex: 1;
  min-width: 0;
  padding: 6px 10px;
  border: 1px solid var(--border, #d9d9d9);
  border-radius: var(--radius, 6px);
  background: var(--input-bg, #fff);
  color: var(--text-main, #000);
  font-size: 13px;
  appearance: none;
  -webkit-appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg%20xmlns%3D%27http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%27%20width%3D%2712%27%20height%3D%2712%27%20viewBox%3D%270%200%2012%2012%27%3E%3Cpath%20fill%3D%27%23888%27%20d%3D%27M6%208L1%203h10z%27%2F%3E%3C%2Fsvg%3E");
  background-repeat: no-repeat;
  background-position: right 10px center;
  padding-right: 30px;
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











