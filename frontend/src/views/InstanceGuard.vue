<template>
  <div class="guard-page">
    <a-card :bordered="false" class="guard-head-card">
      <div class="guard-head">
        <div>
          <h2 class="guard-title"><i class="ri-shield-check-line"></i> 实例守护</h2>
          <p class="guard-desc">
            按每台实例设置的间隔轮询状态，检测到 STOPPED 自动执行启动并推送通知。
          </p>
        </div>
        <a-space>
          <a-statistic title="守护总数" :value="records.length" class="guard-stat" />
          <a-statistic title="启用中" :value="enabledCount" class="guard-stat" />
          <a-button type="primary" @click="openEditor"><PlusOutlined /> 新建守护</a-button>
          <a-button :loading="loading" @click="loadRecords()"><ReloadOutlined /> 刷新</a-button>
        </a-space>
      </div>
    </a-card>

    <a-card :bordered="false">
      <a-table
        :data-source="records"
        :columns="columns"
        :loading="loading"
        row-key="id"
        size="middle"
        :pagination="{ pageSize: 20, showSizeChanger: false }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'instance'">
            <div class="cell-main">{{ record.instanceName || '未命名实例' }}</div>
            <div class="cell-sub">{{ shortId(record.instanceId) }}</div>
          </template>
          <template v-else-if="column.key === 'tenant'">
            <div class="cell-main">{{ record.tenantName || record.tenantConfigId }}</div>
            <div class="cell-sub">{{ record.region }}</div>
          </template>
          <template v-else-if="column.key === 'enabled'">
            <a-switch
              :checked="!!record.enabled"
              :loading="busyIds.has(record.id)"
              @change="(checked: any) => toggleRecord(record, checked === true)"
            />
          </template>
          <template v-else-if="column.key === 'interval'">
            <a-input-number
              :value="record.intervalMinutes || 2"
              :min="1"
              :max="1440"
              size="small"
              :disabled="busyIds.has(record.id)"
              @change="(value: any) => changeInterval(record, value)"
            />
            <span class="cell-sub" style="margin-left: 6px">分钟</span>
          </template>
          <template v-else-if="column.key === 'state'">
            <a-tag :color="stateColor(record.lastState)">{{ record.lastState || '未检测' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'startCount'">
            {{ record.startCount || 0 }} 次
          </template>
          <template v-else-if="column.key === 'lastCheck'">
            <div class="cell-main">{{ formatTime(record.lastCheckTime) }}</div>
            <div v-if="record.lastStartTime" class="cell-sub">
              上次自动启动 {{ formatTime(record.lastStartTime) }}
            </div>
          </template>
          <template v-else-if="column.key === 'message'">
            <a-tooltip :title="record.lastMessage || ''">
              <span class="cell-message">{{ record.lastMessage || '—' }}</span>
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'actions'">
            <a-popconfirm title="确定删除该守护？不会影响实例本身。" @confirm="removeRecord(record)">
              <a-button type="text" danger size="small">删除</a-button>
            </a-popconfirm>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal
      :open="editorVisible"
      title="新建实例守护"
      :width="560"
      :mask-closable="false"
      ok-text="开启守护"
      cancel-text="取消"
      :confirm-loading="saving"
      :ok-button-props="{ disabled: !canSubmit }"
      @ok="submitEditor"
      @cancel="closeEditor"
    >
      <div class="editor-body">
        <div class="editor-field">
          <label>租户</label>
          <a-select
            v-model:value="editor.tenantId"
            show-search
            option-filter-prop="label"
            placeholder="请选择租户..."
            :options="tenantOptions"
            :loading="tenantCatalog.tenantsLoading"
            @change="onTenantChange"
          />
        </div>
        <div class="editor-field">
          <label>区域</label>
          <a-select
            v-model:value="editor.region"
            placeholder="请先选择租户"
            :options="regionOptions"
            :loading="regionsLoading"
            @change="onRegionChange"
          />
        </div>
        <div class="editor-field">
          <label>实例（可多选）</label>
          <a-select
            v-model:value="editor.instanceIds"
            mode="multiple"
            :show-search="false"
            placeholder="请先选择租户与区域"
            :options="instanceOptions"
            :loading="instancesLoading"
            :max-tag-count="6"
          />
        </div>
        <div class="editor-field">
          <label>检测间隔（分钟）</label>
          <a-input-number v-model:value="editor.intervalMinutes" :min="1" :max="1440" style="width: 160px" />
          <div class="editor-hint">默认 2 分钟。检测到实例 STOPPED 会自动执行启动。</div>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import {
  deleteInstanceGuard,
  getInstanceList,
  listInstanceGuards,
  saveInstanceGuard,
  setInstanceGuardInterval,
  toggleInstanceGuard,
  type InstanceGuardRecord,
} from '../api/instance'
import { listTenantRegions } from '../api/tenant'
import { useTenantCatalogStore } from '../stores/tenantCatalog'

defineOptions({ name: 'InstanceGuard' })

const tenantCatalog = useTenantCatalogStore()
const records = ref<InstanceGuardRecord[]>([])
const loading = ref(false)
const busyIds = reactive(new Set<string>())
let loadSeq = 0
let pollTimer: number | undefined

const editorVisible = ref(false)
const saving = ref(false)
const regionsLoading = ref(false)
const instancesLoading = ref(false)
const editorRegions = ref<string[]>([])
const editorInstances = ref<Array<{ instanceId: string; name: string; shape?: string; state?: string }>>([])
let regionLoadSeq = 0
let instanceLoadSeq = 0
const editor = reactive({
  tenantId: undefined as string | undefined,
  region: undefined as string | undefined,
  instanceIds: [] as string[],
  intervalMinutes: 2,
})

const columns = [
  { title: '实例', key: 'instance', width: 220 },
  { title: '租户 / 区域', key: 'tenant', width: 170 },
  { title: '守护', key: 'enabled', width: 80 },
  { title: '检测间隔', key: 'interval', width: 150 },
  { title: '最近状态', key: 'state', width: 110 },
  { title: '自动启动', key: 'startCount', width: 90 },
  { title: '最近检测', key: 'lastCheck', width: 170 },
  { title: '最近消息', key: 'message' },
  { title: '操作', key: 'actions', width: 80 },
]

const enabledCount = computed(() => records.value.filter(record => record.enabled).length)
const tenantOptions = computed(() =>
  tenantCatalog.tenants.map(tenant => ({ value: tenant.id, label: tenant.username })))
const regionOptions = computed(() =>
  editorRegions.value.map(region => ({ value: region, label: region })))
const instanceOptions = computed(() => editorInstances.value.map(row => ({
  value: row.instanceId,
  label: `${row.name || '未命名'}${row.shape ? ' · ' + row.shape : ''}${row.state ? ' · ' + row.state : ''}`,
})))
const canSubmit = computed(() =>
  !!editor.tenantId && !!editor.region && editor.instanceIds.length > 0
  && Number(editor.intervalMinutes) >= 1)

onMounted(() => {
  void loadRecords()
  startPolling()
})
onActivated(() => {
  void loadRecords(true)
  startPolling()
})
onDeactivated(stopPolling)
onUnmounted(stopPolling)

function startPolling() {
  if (pollTimer !== undefined) return
  pollTimer = window.setInterval(() => void loadRecords(true), 15_000)
}

function stopPolling() {
  if (pollTimer === undefined) return
  window.clearInterval(pollTimer)
  pollTimer = undefined
}

async function loadRecords(silent = false) {
  const seq = ++loadSeq
  if (!silent) loading.value = true
  try {
    const res = await listInstanceGuards()
    if (seq !== loadSeq) return
    records.value = Array.isArray(res.data) ? res.data : []
  } catch (error: any) {
    if (seq === loadSeq && !silent) message.error(error?.message || '读取实例守护列表失败')
  } finally {
    if (seq === loadSeq && !silent) loading.value = false
  }
}

async function toggleRecord(record: InstanceGuardRecord, enabled: boolean) {
  if (busyIds.has(record.id)) return
  busyIds.add(record.id)
  try {
    await toggleInstanceGuard({ guardId: record.id, enabled })
    record.enabled = enabled
    message.success(enabled ? '守护已开启' : '守护已关闭')
    void loadRecords(true)
  } catch (error: any) {
    message.error(error?.message || '操作失败')
  } finally {
    busyIds.delete(record.id)
  }
}

async function changeInterval(record: InstanceGuardRecord, value: unknown) {
  const minutes = Number(value)
  if (!Number.isFinite(minutes) || minutes < 1) return
  if (minutes === (record.intervalMinutes || 2) || busyIds.has(record.id)) return
  busyIds.add(record.id)
  try {
    await setInstanceGuardInterval({ guardId: record.id, intervalMinutes: minutes })
    record.intervalMinutes = minutes
    message.success(`检测间隔已改为 ${minutes} 分钟`)
  } catch (error: any) {
    message.error(error?.message || '修改间隔失败')
    void loadRecords(true)
  } finally {
    busyIds.delete(record.id)
  }
}

async function removeRecord(record: InstanceGuardRecord) {
  try {
    await deleteInstanceGuard({ guardId: record.id })
    message.success('已删除守护')
    void loadRecords(true)
  } catch (error: any) {
    message.error(error?.message || '删除失败')
  }
}

function openEditor() {
  editor.tenantId = undefined
  editor.region = undefined
  editor.instanceIds = []
  editor.intervalMinutes = 2
  editorRegions.value = []
  editorInstances.value = []
  editorVisible.value = true
  void tenantCatalog.ensureTenants()
}

function closeEditor() {
  if (saving.value) return
  regionLoadSeq++
  instanceLoadSeq++
  editorVisible.value = false
}

async function onTenantChange(value: unknown) {
  const tenantId = String(value || '')
  editor.region = undefined
  editor.instanceIds = []
  editorInstances.value = []
  if (!tenantId) return
  const seq = ++regionLoadSeq
  regionsLoading.value = true
  editorRegions.value = []
  try {
    const res = await listTenantRegions({ id: tenantId })
    if (seq !== regionLoadSeq || editor.tenantId !== tenantId) return
    const items: any[] = Array.isArray(res.data?.items) ? res.data.items : []
    const regions = items
      .filter(item => item?.subscribed === true)
      .map(item => String(item.regionName || item.regionKey || '').trim())
      .filter(Boolean)
    const fallback = tenantCatalog.tenantById.get(tenantId)?.ociRegion
    editorRegions.value = [...new Set(regions.length ? regions : fallback ? [fallback] : [])]
    if (editorRegions.value.length === 1) {
      editor.region = editorRegions.value[0]
      void loadEditorInstances()
    }
  } catch {
    const fallback = tenantCatalog.tenantById.get(tenantId)?.ociRegion
    editorRegions.value = fallback ? [fallback] : []
    if (fallback) {
      editor.region = fallback
      void loadEditorInstances()
    }
  } finally {
    if (seq === regionLoadSeq) regionsLoading.value = false
  }
}

function onRegionChange() {
  editor.instanceIds = []
  editorInstances.value = []
  void loadEditorInstances()
}

async function loadEditorInstances() {
  const tenantId = editor.tenantId
  const region = editor.region
  if (!tenantId || !region) return
  const seq = ++instanceLoadSeq
  instancesLoading.value = true
  try {
    const res = await getInstanceList({ id: tenantId, region })
    if (seq !== instanceLoadSeq || editor.tenantId !== tenantId || editor.region !== region) return
    editorInstances.value = (Array.isArray(res.data) ? res.data : [])
      .map((row: any) => ({
        instanceId: String(row.instanceId || row.id || ''),
        name: String(row.name || row.displayName || ''),
        shape: row.shape,
        state: row.state,
      }))
      .filter(row => row.instanceId)
  } catch (error: any) {
    if (seq === instanceLoadSeq) message.error(error?.message || '读取实例列表失败')
  } finally {
    if (seq === instanceLoadSeq) instancesLoading.value = false
  }
}

async function submitEditor() {
  if (!canSubmit.value || saving.value) return
  saving.value = true
  const interval = Number(editor.intervalMinutes) || 2
  let succeeded = 0
  const failed: string[] = []
  try {
    for (const instanceId of editor.instanceIds) {
      const row = editorInstances.value.find(item => item.instanceId === instanceId)
      try {
        await saveInstanceGuard({
          id: editor.tenantId!,
          instanceId,
          region: editor.region,
          instanceName: row?.name || undefined,
          enabled: true,
          intervalMinutes: interval,
        })
        succeeded += 1
      } catch (error: any) {
        failed.push(`${row?.name || instanceId}：${error?.message || '未知错误'}`)
      }
    }
    if (failed.length === 0) {
      message.success(`已为 ${succeeded} 台实例开启守护`)
      editorVisible.value = false
    } else {
      if (succeeded > 0) message.warning(`${succeeded} 台成功，${failed.length} 台失败`)
      message.error(failed.join('\n'))
    }
    void loadRecords(true)
  } finally {
    saving.value = false
  }
}

function stateColor(state?: string | null) {
  switch ((state || '').toUpperCase()) {
    case 'RUNNING': return 'green'
    case 'STOPPED': return 'red'
    case 'STOPPING':
    case 'STARTING': return 'orange'
    case 'TERMINATED':
    case 'TERMINATING': return 'default'
    default: return 'blue'
  }
}

function shortId(id?: string | null) {
  const value = String(id || '')
  return value.length > 26 ? `${value.slice(0, 12)}…${value.slice(-10)}` : value
}

function formatTime(value?: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getMonth() + 1}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
</script>

<style scoped>
.guard-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.guard-head {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  justify-content: space-between;
}
.guard-title {
  align-items: center;
  display: flex;
  font-size: 18px;
  font-weight: 650;
  gap: 8px;
  margin: 0;
}
.guard-desc {
  color: var(--text-sub, #6b7280);
  font-size: 12.5px;
  margin: 6px 0 0;
}
.guard-stat {
  min-width: 76px;
}
.cell-main {
  color: var(--text-main, #111827);
  font-size: 13px;
}
.cell-sub {
  color: var(--text-sub, #6b7280);
  font-size: 11.5px;
  word-break: break-all;
}
.cell-message {
  color: var(--text-sub, #6b7280);
  display: inline-block;
  font-size: 12px;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.editor-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-top: 4px;
}
.editor-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.editor-field > label {
  color: var(--text-main, #111827);
  font-size: 13px;
  font-weight: 600;
}
.editor-hint {
  color: var(--text-sub, #6b7280);
  font-size: 12px;
}
</style>
