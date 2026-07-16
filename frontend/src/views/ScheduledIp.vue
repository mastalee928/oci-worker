<template>
  <div class="scheduled-ip-root">
  <div class="scheduled-page">
    <div class="page-toolbar">
      <button class="btn btn-ghost" :disabled="initialLoading || refreshing" @click="loadOverview(true)">
        <i class="ri-refresh-line" :class="{ spinning: refreshing }"></i>刷新
      </button>
      <button class="btn btn-primary" @click="openEditor()">
        <i class="ri-add-line"></i>新建任务
      </button>
    </div>

    <div class="stats">
      <div class="stat">
        <div class="k">启用中任务</div>
        <template v-if="initialLoading"><div class="stat-loading"></div><div class="stat-loading sub-loading"></div></template>
        <template v-else><div class="v">{{ stats.enabled }} <small>/ 共 {{ stats.total }}</small></div><div class="sub">{{ stats.paused }} 个已暂停</div></template>
      </div>
      <div class="stat warn">
        <div class="k">异常 / DNS 失败</div>
        <template v-if="initialLoading"><div class="stat-loading"></div><div class="stat-loading sub-loading"></div></template>
        <template v-else><div class="v">{{ stats.errors }}</div><div class="sub">{{ latestErrorText }}</div></template>
      </div>
      <div class="stat ok">
        <div class="k">最近一次执行</div>
        <template v-if="initialLoading"><div class="stat-loading"></div><div class="stat-loading sub-loading"></div></template>
        <template v-else><div class="v stat-text">{{ latestResultText }}</div><div class="sub">{{ latestResultSub }}</div></template>
      </div>
      <div class="stat">
        <div class="k">下一次执行</div>
        <template v-if="initialLoading"><div class="stat-loading"></div><div class="stat-loading sub-loading"></div></template>
        <template v-else><div class="v stat-text">{{ nextRunText }}</div><div class="sub">{{ nextRunSub }}</div></template>
      </div>
    </div>

    <div class="filters">
      <div class="search">
        <i class="ri-search-line" aria-hidden="true"></i>
        <input v-model="query" placeholder="搜索租户 / 实例 / 域名 / IP">
      </div>
      <div class="sel">
        <select v-model="statusFilter">
          <option value="">状态：全部</option>
          <option value="on">启用中</option>
          <option value="off">已暂停</option>
          <option value="err">异常</option>
        </select>
      </div>
      <div class="sel">
        <select v-model="providerFilter">
          <option value="">DNS：全部</option>
          <option value="CF">Cloudflare</option>
          <option value="ALI">阿里云 DNS</option>
          <option value="NONE">未配置</option>
        </select>
      </div>
      <div class="sel">
        <select v-model="regionFilter">
          <option value="">区域：全部</option>
          <option v-for="region in taskRegions" :key="region" :value="region">{{ region }}</option>
        </select>
      </div>
    </div>

    <div class="card">
      <table>
        <thead>
          <tr>
            <th style="width: 22%">任务 / 实例</th>
            <th>租户</th>
            <th>区域</th>
            <th>当前公网 IP</th>
            <th>域名 / DNS</th>
            <th>周期 / 下次执行</th>
            <th>最近结果</th>
            <th style="text-align: center">启用</th>
            <th style="text-align: right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="initialLoading" class="empty-row loading-row">
            <td colspan="9"><div class="table-loading"><i class="ri-loader-4-line spinning"></i> 正在读取任务与执行状态</div></td>
          </tr>
          <tr v-else-if="!filteredTasks.length" class="empty-row">
            <td colspan="9"><div class="empty">{{ loadError || '没有匹配的任务' }}</div></td>
          </tr>
          <tr v-for="task in filteredTasks" :key="task.id">
            <td>
              <div class="cell-main">{{ task.name }}</div>
              <div class="cell-sub"><StatusBadge :task="task" /></div>
            </td>
            <td>{{ task.tenantName || '-' }}</td>
            <td>
              <span class="mono">{{ task.region }}</span>
              <div class="cell-sub">{{ task.instanceName || '-' }} · {{ shortShape(task.shape) }}</div>
            </td>
            <td><span class="ip">{{ task.currentPublicIp || '暂无' }}</span></td>
            <td>
              <template v-if="task.dnsEnabled">
                <div class="cell-main dns-name">{{ task.fqdn }}</div>
                <div class="prov">
                  <span class="pi" :class="task.dnsProvider === 'CF' ? 'pi-cf' : 'pi-ali'">
                    {{ task.dnsProvider === 'CF' ? 'CF' : '阿' }}
                  </span>
                  {{ task.dnsProvider === 'CF' ? 'Cloudflare' : '阿里云 DNS' }}
                </div>
              </template>
              <span v-else class="cell-sub">未配置</span>
            </td>
            <td>
              <div class="cell-main interval-text">每 {{ formatInterval(task.intervalMinutes) }}</div>
              <div class="cell-sub">下次：{{ task.enabled ? formatDateTime(task.nextRunTime) : '暂无' }}</div>
            </td>
            <td>
              <ResultBadge :task="task" />
              <div class="cell-sub result-message">{{ task.lastMessage || '等待执行' }}</div>
            </td>
            <td style="text-align: center">
              <button
                class="sw"
                :class="{ on: task.enabled }"
                role="switch"
                :aria-checked="task.enabled"
                :disabled="busyIds.has(task.id)"
                @click="toggleTask(task)"
              ></button>
            </td>
            <td>
              <div class="row-actions">
                <button class="link go" :disabled="busyIds.has(task.id)" @click="runNow(task)">立即执行</button>
                <button class="link" :disabled="busyIds.has(task.id)" @click="openEditor(task)">配置</button>
                <button class="link" @click="openLogs(task)">日志</button>
                <button class="link" :disabled="busyIds.has(task.id)" @click="copyTask(task)">复制</button>
                <button class="link danger" :disabled="busyIds.has(task.id)" @click="confirmDelete(task)">删除</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <Teleport to="body">
    <div v-if="editorVisible || logsVisible" class="scheduled-mask" aria-hidden="true"></div>

    <aside v-if="editorVisible" class="scheduled-drawer" aria-label="定时换 IP 任务编辑器">
      <div class="drawer-head">
        <div>
          <h3>{{ editingId ? '编辑定时换 IP 任务' : '新建定时换 IP 任务' }}</h3>
          <p>依次选择租户与实例，设置换 IP 周期，并按需配置 DNS 同步与通知</p>
        </div>
        <button class="icon-btn" title="关闭" @click="closeEditor"><i class="ri-close-line"></i></button>
      </div>
      <div class="drawer-body">
        <div class="step">
          <div class="step-h"><span class="step-n">1</span><span class="step-t">选择租户</span><span class="step-d">来源：现有租户配置</span></div>
          <div class="field">
            <label>租户 <span class="req">*</span></label>
            <a-select
              v-model:value="editor.tenantConfigId"
              class="tenant-select"
              show-search
              option-filter-prop="label"
              placeholder="请选择租户..."
              :options="tenantOptions"
              :loading="tenantCatalog.tenantsLoading"
              :dropdown-style="{ zIndex: 1380 }"
              @change="onTenantChange"
            />
            <div class="hint">仅可选择已在「租户配置」中添加的租户，此处不新增租户。</div>
          </div>
        </div>

        <div class="step">
          <div class="step-h"><span class="step-n">2</span><span class="step-t">选择区域</span><span class="step-d">该租户已开通的 region</span></div>
          <div v-if="regionsLoading" class="inline-loading"><i class="ri-loader-4-line spinning"></i>正在读取区域</div>
          <div v-else class="region-tabs">
            <button
              v-for="region in editorRegions"
              :key="region"
              class="chip"
              :class="{ sel: editor.region === region }"
              @click="selectRegion(region)"
            >{{ region }}</button>
            <span v-if="!editorRegions.length" class="hint">请先选择租户</span>
          </div>
        </div>

        <div class="step">
          <div class="step-h"><span class="step-n">3</span><span class="step-t">选择实例</span><span class="step-d">一个任务对应一台实例</span></div>
          <div class="picker">
            <div v-if="instancesLoading" class="empty"><i class="ri-loader-4-line spinning"></i> 正在读取实例</div>
            <button
              v-for="instance in editorInstances"
              v-else
              :key="instance.instanceId"
              class="pick"
              :class="{ sel: editor.instanceId === instance.instanceId }"
              @click="selectInstance(instance)"
            >
              <span class="radio"></span>
              <span class="pick-main"><span class="pn">{{ instance.name }}</span><span class="pm">{{ instance.shape }} · {{ instance.state }}</span></span>
              <span class="pip"><span class="ip">{{ instance.publicIp || '暂无公网 IP' }}</span></span>
            </button>
            <div v-if="!instancesLoading && editor.region && !editorInstances.length" class="empty">该区域暂无实例</div>
            <div v-if="!instancesLoading && !editor.region" class="empty">请先选择租户与区域</div>
          </div>
        </div>

        <div class="step">
          <div class="step-h"><span class="step-n">4</span><span class="step-t">执行周期</span><span class="step-d">按分钟间隔</span></div>
          <div class="field">
            <label>间隔（分钟）<span class="req">*</span></label>
            <div class="chips interval-chips">
              <button v-for="item in intervalOptions" :key="item.value" class="chip" :class="{ sel: editor.intervalMinutes === item.value }" @click="editor.intervalMinutes = item.value">{{ item.label }}</button>
            </div>
            <input v-model.number="editor.intervalMinutes" type="number" min="10">
            <div class="hint">最小 10 分钟。间隔任务不依赖时区，无需单独设置。</div>
          </div>
          <div class="field">
            <label>首次执行</label>
            <div class="seg">
              <button :class="{ on: !editor.firstRunNow }" @click="editor.firstRunNow = false">按间隔等待</button>
              <button :class="{ on: editor.firstRunNow }" @click="editor.firstRunNow = true">保存后立即执行一次</button>
            </div>
          </div>
        </div>

        <div class="step">
          <div class="step-h"><span class="step-n">5</span><span class="step-t">DNS 同步</span><span class="step-d">换 IP 成功后自动更新</span></div>
          <div class="panel">
            <div class="trow">
              <div><div class="tt">启用 DNS 同步</div><div class="td">仅填完整域名，系统自动匹配 Zone / 主域并更新 A 记录</div></div>
              <button
                class="sw"
                :class="{ on: editor.dnsEnabled }"
                role="switch"
                :aria-checked="editor.dnsEnabled"
                :disabled="dnsConfigLoading"
                @click="toggleDnsEnabled"
              ></button>
            </div>
          </div>
          <div v-if="editor.dnsEnabled" class="dns-box">
            <div class="two">
              <div class="field">
                <label>DNS 服务商</label>
                <select v-model="editor.dnsProvider" :disabled="dnsConfigLoading || !hasConfiguredDnsProvider">
                  <option value="CF" :disabled="!cfDnsConfigured">Cloudflare{{ cfDnsConfigured ? '' : '（未配置）' }}</option>
                  <option value="ALI" :disabled="!aliDnsConfigured">阿里云 DNS{{ aliDnsConfigured ? '' : '（未配置）' }}</option>
                </select>
              </div>
              <div class="field">
                <label>记录类型</label>
                <select disabled><option>A（第一版）</option></select>
              </div>
            </div>
            <div class="field">
              <label>完整域名 <span class="req">*</span></label>
              <input v-model.trim="editor.fqdn" type="text" placeholder="api.example.com">
              <div v-if="!selectedDnsProviderConfigured" class="hint warn">当前 DNS 服务商未配置，请切换服务商或关闭 DNS 同步。</div>
              <div v-if="dnsPreview" class="hint ok">{{ dnsPreview }}</div>
            </div>
          </div>
        </div>

        <div class="step">
          <div class="step-h"><span class="step-n">6</span><span class="step-t">Telegram 通知</span><span class="step-d">默认只推异常</span></div>
          <div class="panel">
            <NotifyRow title="换 IP 成功" desc="高频任务建议关闭，避免刷屏" v-model="editor.notifySuccess" />
            <NotifyRow title="换 IP 失败" desc="首次失败立即通知，相同错误进入冷却" v-model="editor.notifyIpFailure" />
            <NotifyRow title="DNS 同步失败" desc="会注明公网 IP 是否已更换" v-model="editor.notifyDnsFailure" />
            <NotifyRow title="任务自动暂停" desc="连续失败或租户失效时" v-model="editor.notifyAutoPaused" />
          </div>
        </div>
      </div>
      <div class="drawer-foot">
        <button class="btn btn-ghost" :disabled="saving" @click="closeEditor">取消</button>
        <button class="btn btn-brand" :disabled="saving" @click="saveEditor">
          <i v-if="saving" class="ri-loader-4-line spinning"></i>{{ saving ? '保存中' : '保存任务' }}
        </button>
      </div>
    </aside>

    <aside v-if="logsVisible" class="scheduled-drawer log-drawer" aria-label="定时换 IP 执行日志">
      <div class="drawer-head">
        <div><h3>{{ logTask?.name }} · 执行日志</h3><p>{{ logTask?.tenantName }} · {{ logTask?.region }} · {{ logTask?.instanceName }}</p></div>
        <button class="icon-btn" title="关闭" @click="closeLogs"><i class="ri-close-line"></i></button>
      </div>
      <div class="drawer-body">
        <div v-if="logsLoading" class="empty"><i class="ri-loader-4-line spinning"></i> 正在读取执行日志</div>
        <div v-else-if="!taskLogs.length" class="empty">暂无执行日志</div>
        <div v-for="row in taskLogs" v-else :key="row.id" class="log-row">
          <div class="lt">{{ formatLogTime(row.startedAt) }}</div>
          <div class="lc">
            <span class="badge" :class="logBadgeClass(row)"><span class="bd"></span>{{ logBadgeText(row) }}</span>
            <div class="lm">
              <template v-if="row.oldIp || row.newIp">
                <span class="ip">{{ row.oldIp || '暂无' }}</span><span class="arrow">→</span><span class="ip">{{ row.newIp || '暂无' }}</span>，
              </template>{{ row.message || row.dnsMessage || '执行完成' }}
            </div>
          </div>
        </div>
      </div>
      <div class="drawer-foot">
        <button class="btn btn-ghost" @click="closeLogs">关闭</button>
        <button class="btn btn-brand" :disabled="retryingDns || !logTask?.dnsEnabled" @click="retryDns">
          <i v-if="retryingDns" class="ri-loader-4-line spinning"></i>{{ retryingDns ? '提交中' : '重试 DNS' }}
        </button>
      </div>
    </aside>
  </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { useTenantCatalogStore, type TenantRecord } from '../stores/tenantCatalog'
import { listTenantRegions } from '../api/tenant'
import { getInstanceList, getInstancePublicIps } from '../api/instance'
import { getCfAccountConfig } from '../api/cloudflare'
import { getAliDNSAccountConfig } from '../api/alidns'
import {
  copyScheduledIpTask,
  createScheduledIpTask,
  deleteScheduledIpTask,
  getScheduledIpOverview,
  listScheduledIpTaskLogs,
  retryScheduledIpDns,
  runScheduledIpTask,
  setScheduledIpTaskEnabled,
  updateScheduledIpTask,
  type ScheduledIpOverview,
  type ScheduledIpRunLog,
  type ScheduledIpTask,
  type ScheduledIpTaskPayload,
} from '../api/scheduledIp'

defineOptions({ name: 'ScheduledIp' })

const StatusBadge = defineComponent({
  props: { task: { type: Object as () => ScheduledIpTask, required: true } },
  setup(props) {
    return () => {
      const status = taskFilterStatus(props.task)
      const cfg = status === 'err' ? ['b-err', '异常'] : status === 'off' ? ['b-off', '已暂停'] : ['b-on', '正常']
      return h('span', { class: ['badge', cfg[0]] }, [h('span', { class: 'bd' }), cfg[1]])
    }
  },
})

const ResultBadge = defineComponent({
  props: { task: { type: Object as () => ScheduledIpTask, required: true } },
  setup(props) {
    return () => {
      const status = props.task.lastStatus
      const cfg = status === 'SUCCESS'
        ? ['b-on', '成功']
        : status === 'PENDING'
          ? ['b-info', '等待']
          : status === 'RUNNING'
            ? ['b-info', '执行中']
            : status === 'DISABLED'
              ? ['b-off', '已暂停']
              : ['b-err', '失败']
      return h('span', { class: ['badge', cfg[0]] }, [h('span', { class: 'bd' }), cfg[1]])
    }
  },
})

const NotifyRow = defineComponent({
  props: { title: { type: String, required: true }, desc: { type: String, required: true }, modelValue: { type: Boolean, required: true } },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('div', { class: 'trow' }, [
      h('div', [h('div', { class: 'tt' }, props.title), h('div', { class: 'td' }, props.desc)]),
      h('button', {
        class: ['sw', { on: props.modelValue }],
        role: 'switch',
        'aria-checked': props.modelValue,
        onClick: () => emit('update:modelValue', !props.modelValue),
      }),
    ])
  },
})

type InstanceOption = {
  instanceId: string
  name: string
  shape?: string
  state?: string
  compartmentId?: string
  publicIp?: string | null
}

const tenantCatalog = useTenantCatalogStore()
const router = useRouter()
const initialLoading = ref(true)
const refreshing = ref(false)
const loadError = ref('')
const overview = ref<ScheduledIpOverview>({ tasks: [], stats: { total: 0, enabled: 0, paused: 0, errors: 0 } })
const query = ref('')
const statusFilter = ref('')
const providerFilter = ref('')
const regionFilter = ref('')
const busyIds = reactive(new Set<string>())
let pollTimer: number | undefined
let pageActive = false
let overviewLoadSeq = 0
let editorSessionSeq = 0
let regionLoadSeq = 0
let instanceLoadSeq = 0
let logLoadSeq = 0
let dnsConfigPromise: Promise<void> | null = null

const editorVisible = ref(false)
const editingId = ref('')
const saving = ref(false)
const regionsLoading = ref(false)
const instancesLoading = ref(false)
const dnsConfigLoading = ref(false)
const dnsConfigLoaded = ref(false)
const dnsConfigLoadError = ref(false)
const cfDnsConfigured = ref(false)
const aliDnsConfigured = ref(false)
const editorRegions = ref<string[]>([])
const editorInstances = ref<InstanceOption[]>([])
const editor = reactive({
  tenantConfigId: '', region: '', instanceId: '', instanceName: '', shape: '', compartmentId: '', currentPublicIp: '',
  intervalMinutes: 60, firstRunNow: false, dnsEnabled: false, dnsProvider: 'CF' as 'CF' | 'ALI', fqdn: '',
  notifySuccess: false, notifyIpFailure: true, notifyDnsFailure: true, notifyAutoPaused: true,
})

const logsVisible = ref(false)
const logsLoading = ref(false)
const retryingDns = ref(false)
const logTask = ref<ScheduledIpTask | null>(null)
const taskLogs = ref<ScheduledIpRunLog[]>([])

const intervalOptions = [
  { value: 10, label: '10 分钟' }, { value: 30, label: '30 分钟' }, { value: 60, label: '1 小时' },
  { value: 360, label: '6 小时' }, { value: 1440, label: '24 小时' },
]

const stats = computed(() => overview.value.stats || { total: 0, enabled: 0, paused: 0, errors: 0 })
const tasks = computed(() => overview.value.tasks || [])
const taskRegions = computed(() => [...new Set(tasks.value.map(task => task.region).filter(Boolean))].sort())
const hasConfiguredDnsProvider = computed(() => cfDnsConfigured.value || aliDnsConfigured.value)
const selectedDnsProviderConfigured = computed(() => (
  editor.dnsProvider === 'CF' ? cfDnsConfigured.value : aliDnsConfigured.value
))
const filteredTasks = computed(() => {
  const q = query.value.trim().toLowerCase()
  return tasks.value.filter(task => {
    if (statusFilter.value && taskFilterStatus(task) !== statusFilter.value) return false
    const provider = task.dnsEnabled ? task.dnsProvider : 'NONE'
    if (providerFilter.value && provider !== providerFilter.value) return false
    if (regionFilter.value && task.region !== regionFilter.value) return false
    if (q && !`${task.name} ${task.tenantName || ''} ${task.instanceName || ''} ${task.fqdn || ''} ${task.currentPublicIp || ''}`.toLowerCase().includes(q)) return false
    return true
  })
})
const tenantOptions = computed(() => tenantCatalog.tenants.map(tenant => ({ value: tenant.id, label: tenantLabel(tenant) })))
const latestErrorText = computed(() => {
  const task = tasks.value
    .filter(row => taskFilterStatus(row) === 'err')
    .sort((a, b) => dateTimestamp(b.lastRunTime || b.updateTime) - dateTimestamp(a.lastRunTime || a.updateTime))[0]
  return task ? `${task.instanceName || task.name} · ${task.lastMessage || '执行异常'}` : '当前没有异常任务'
})
const latestResultText = computed(() => {
  const row = stats.value.latestLog
  if (!row) return '暂无执行记录'
  if (row.status === 'RUNNING') return '正在执行'
  return row.status === 'SUCCESS' ? '换 IP 成功' : row.status === 'PARTIAL_FAILED' ? 'DNS 同步失败' : '换 IP 失败'
})
const latestResultSub = computed(() => {
  const row = stats.value.latestLog
  if (!row) return '等待首次执行'
  const task = tasks.value.find(item => item.id === row.taskId)
  return `${task?.instanceName || task?.name || '任务'} · ${relativeTime(row.finishedAt || row.startedAt)}`
})
const nextRunText = computed(() => stats.value.nextTask?.nextRunTime ? formatTime(stats.value.nextTask.nextRunTime) : '暂无计划')
const nextRunSub = computed(() => stats.value.nextTask ? `${stats.value.nextTask.instanceName || stats.value.nextTask.name} · ${relativeFuture(stats.value.nextTask.nextRunTime)}` : '没有启用中的任务')
const dnsPreview = computed(() => {
  const fqdn = editor.fqdn.trim().toLowerCase()
  if (!editor.dnsEnabled || !fqdn) return ''
  return `将从当前账号中自动匹配最具体的 ${editor.dnsProvider === 'CF' ? 'Zone' : '主域'}，更新 A 记录 ${fqdn}（不存在则创建）`
})

onMounted(async () => {
  void loadDnsProviderAvailability()
  try {
    await loadOverview(false)
  } finally {
    initialLoading.value = false
    if (pageActive) startPolling()
  }
})

onActivated(() => {
  pageActive = true
  void loadDnsProviderAvailability(true)
  if (!initialLoading.value) {
    void loadOverview(false, true)
    startPolling()
  }
})

onDeactivated(() => {
  pageActive = false
  overviewLoadSeq++
  refreshing.value = false
  stopPolling()
})

onUnmounted(() => {
  pageActive = false
  overviewLoadSeq++
  stopPolling()
})

function startPolling() {
  if (pollTimer !== undefined) return
  pollTimer = window.setInterval(() => void loadOverview(false, true), 10_000)
}

function stopPolling() {
  if (pollTimer === undefined) return
  window.clearInterval(pollTimer)
  pollTimer = undefined
}

async function loadDnsProviderAvailability(force = false) {
  if (dnsConfigPromise) return await dnsConfigPromise
  if (dnsConfigLoaded.value && !force) return
  dnsConfigLoading.value = true
  dnsConfigPromise = (async () => {
    const [cfResult, aliResult] = await Promise.allSettled([
      getCfAccountConfig(),
      getAliDNSAccountConfig(),
    ])
    cfDnsConfigured.value = cfResult.status === 'fulfilled' && cfResult.value.data?.configured === true
    aliDnsConfigured.value = aliResult.status === 'fulfilled' && aliResult.value.data?.configured === true
    dnsConfigLoadError.value = cfResult.status === 'rejected' || aliResult.status === 'rejected'
    dnsConfigLoaded.value = true
  })().finally(() => {
    dnsConfigLoading.value = false
    dnsConfigPromise = null
  })
  await dnsConfigPromise
}

function preferredDnsProvider(): 'CF' | 'ALI' {
  if (cfDnsConfigured.value) return 'CF'
  if (aliDnsConfigured.value) return 'ALI'
  return 'CF'
}

function applyNewTaskDnsDefaults() {
  editor.dnsEnabled = hasConfiguredDnsProvider.value
  editor.dnsProvider = preferredDnsProvider()
}

function toggleDnsEnabled() {
  if (editor.dnsEnabled) {
    editor.dnsEnabled = false
    return
  }
  if (dnsConfigLoading.value) {
    message.info('正在读取 DNS 配置状态，请稍候')
    return
  }
  if (dnsConfigLoadError.value) {
    message.error('DNS 配置状态读取失败，请刷新后重试')
    return
  }
  if (!hasConfiguredDnsProvider.value) {
    Modal.warning({
      title: '尚未配置 DNS 服务商',
      content: '请先前往「系统设置」中的 Cloudflare 或阿里云DNS完成凭据配置。',
      okText: '前往系统设置',
      maskClosable: false,
      keyboard: false,
      onOk: () => router.push('/settings'),
    })
    return
  }
  if (!selectedDnsProviderConfigured.value) editor.dnsProvider = preferredDnsProvider()
  editor.dnsEnabled = true
}

async function loadOverview(showMessage = false, silent = false) {
  const requestSeq = ++overviewLoadSeq
  if (!silent) refreshing.value = true
  try {
    const res = await getScheduledIpOverview()
    if (requestSeq !== overviewLoadSeq) return
    overview.value = res.data || { tasks: [], stats: { total: 0, enabled: 0, paused: 0, errors: 0 } }
    loadError.value = ''
    if (showMessage) message.success('任务状态已刷新')
  } catch (e: any) {
    if (requestSeq !== overviewLoadSeq) return
    loadError.value = e?.message || '加载定时换 IP 任务失败'
    if (showMessage) message.error(loadError.value)
  } finally {
    if (!silent && requestSeq === overviewLoadSeq) refreshing.value = false
  }
}

function resetEditor() {
  Object.assign(editor, {
    tenantConfigId: '', region: '', instanceId: '', instanceName: '', shape: '', compartmentId: '', currentPublicIp: '',
    intervalMinutes: 60, firstRunNow: false, dnsEnabled: false, dnsProvider: 'CF', fqdn: '',
    notifySuccess: false, notifyIpFailure: true, notifyDnsFailure: true, notifyAutoPaused: true,
  })
  editorRegions.value = []
  editorInstances.value = []
}

async function openEditor(task?: ScheduledIpTask) {
  const sessionSeq = ++editorSessionSeq
  regionLoadSeq++
  instanceLoadSeq++
  resetEditor()
  editingId.value = task?.id || ''
  editorVisible.value = true
  try {
    await Promise.all([tenantCatalog.ensureTenants(), loadDnsProviderAvailability()])
  } catch (e: any) {
    if (sessionSeq === editorSessionSeq) message.error(e?.message || '读取租户配置失败')
    return
  }
  if (sessionSeq !== editorSessionSeq || !editorVisible.value) return
  if (!task) {
    applyNewTaskDnsDefaults()
    return
  }
  Object.assign(editor, {
    tenantConfigId: task.tenantConfigId, region: task.region,
    instanceId: task.instanceId, instanceName: task.instanceName || '', shape: task.shape || '',
    compartmentId: task.compartmentId || '', currentPublicIp: task.currentPublicIp || '',
    intervalMinutes: task.intervalMinutes || 60, firstRunNow: false, dnsEnabled: task.dnsEnabled,
    dnsProvider: task.dnsProvider === 'ALI' ? 'ALI' : 'CF', fqdn: task.fqdn || '',
    notifySuccess: task.notifySuccess, notifyIpFailure: task.notifyIpFailure,
    notifyDnsFailure: task.notifyDnsFailure, notifyAutoPaused: task.notifyAutoPaused,
  })
  await loadRegions(task.tenantConfigId, task.region)
  await loadInstances(task.instanceId, task)
}

function closeEditor() {
  if (saving.value) return
  editorSessionSeq++
  regionLoadSeq++
  instanceLoadSeq++
  editorVisible.value = false
}

async function onTenantChange(value: string) {
  instanceLoadSeq++
  editor.instanceId = ''
  editor.instanceName = ''
  editorInstances.value = []
  await loadRegions(value)
}

async function loadRegions(tenantId: string, preferred?: string) {
  const requestSeq = ++regionLoadSeq
  regionsLoading.value = true
  editorRegions.value = []
  try {
    const res = await listTenantRegions({ id: tenantId })
    if (requestSeq !== regionLoadSeq || editor.tenantConfigId !== tenantId || !editorVisible.value) return
    const items: Array<Record<string, unknown>> = Array.isArray(res.data?.items) ? res.data.items : []
    const regions: string[] = items
      .filter((item: any) => item?.subscribed === true)
      .map((item: any) => String(item.regionName || item.regionKey || '').trim())
      .filter((value: string) => value.length > 0)
    const fallback = tenantCatalog.tenantById.get(tenantId)?.ociRegion
    const resolvedRegions: string[] = regions.length ? regions : fallback ? [fallback] : []
    editorRegions.value = [...new Set<string>(resolvedRegions)]
    editor.region = preferred && editorRegions.value.includes(preferred) ? preferred : editorRegions.value[0] || ''
    if (editor.region && !preferred) await loadInstances()
  } catch (e: any) {
    if (requestSeq !== regionLoadSeq || editor.tenantConfigId !== tenantId || !editorVisible.value) return
    const fallback = tenantCatalog.tenantById.get(tenantId)?.ociRegion
    editorRegions.value = fallback ? [fallback] : []
    editor.region = preferred || fallback || ''
    message.error(e?.message || '读取租户区域失败')
    if (editor.region && !preferred) await loadInstances()
  } finally {
    if (requestSeq === regionLoadSeq) regionsLoading.value = false
  }
}

async function selectRegion(region: string) {
  if (editor.region === region) return
  editor.region = region
  editor.instanceId = ''
  editor.instanceName = ''
  await loadInstances()
}

async function loadInstances(preferredInstanceId?: string, taskSnapshot?: ScheduledIpTask) {
  if (!editor.tenantConfigId || !editor.region) return
  const requestSeq = ++instanceLoadSeq
  const tenantId = editor.tenantConfigId
  const region = editor.region
  instancesLoading.value = true
  editorInstances.value = []
  try {
    const res = await getInstanceList({ id: tenantId, region })
    if (requestSeq !== instanceLoadSeq || editor.tenantConfigId !== tenantId || editor.region !== region || !editorVisible.value) return
    const rows = (Array.isArray(res.data) ? res.data : []).map((row: any) => ({
      instanceId: String(row.instanceId || row.id || ''), name: String(row.name || row.displayName || ''),
      shape: row.shape, state: row.state, compartmentId: row.compartmentId, publicIp: row.publicIp,
    })).filter((row: InstanceOption) => row.instanceId)
    if (rows.length) {
      try {
        const targets = rows.map((row: InstanceOption) => ({
          instanceId: row.instanceId,
          compartmentId: row.compartmentId,
        }))
        const ips: Record<string, string | null> = {}
        for (let offset = 0; offset < targets.length; offset += 500) {
          const ipRes = await getInstancePublicIps({
            id: tenantId,
            region,
            instances: targets.slice(offset, offset + 500),
          })
          if (requestSeq !== instanceLoadSeq || editor.tenantConfigId !== tenantId || editor.region !== region || !editorVisible.value) return
          Object.assign(ips, ipRes.data?.publicIps || {})
        }
        for (const row of rows) if (Object.prototype.hasOwnProperty.call(ips, row.instanceId)) row.publicIp = ips[row.instanceId]
      } catch {
        // 实例主体仍可选择；公网 IP 留空并由后端执行时重新解析。
      }
    }
    if (taskSnapshot && preferredInstanceId && !rows.some((row: InstanceOption) => row.instanceId === preferredInstanceId)) {
      rows.unshift({
        instanceId: preferredInstanceId, name: taskSnapshot.instanceName || taskSnapshot.name,
        shape: taskSnapshot.shape, state: 'UNKNOWN', compartmentId: taskSnapshot.compartmentId,
        publicIp: taskSnapshot.currentPublicIp,
      })
    }
    editorInstances.value = rows
    if (preferredInstanceId) {
      const selected = rows.find((row: InstanceOption) => row.instanceId === preferredInstanceId)
      if (selected) selectInstance(selected)
    }
  } catch (e: any) {
    if (requestSeq === instanceLoadSeq) message.error(e?.message || '读取实例列表失败')
  } finally {
    if (requestSeq === instanceLoadSeq) instancesLoading.value = false
  }
}

function selectInstance(instance: InstanceOption) {
  editor.instanceId = instance.instanceId
  editor.instanceName = instance.name
  editor.shape = instance.shape || ''
  editor.compartmentId = instance.compartmentId || ''
  editor.currentPublicIp = instance.publicIp || ''
}

async function saveEditor() {
  if (!editor.tenantConfigId) { message.warning('请选择租户'); return }
  if (!editor.region) { message.warning('请选择区域'); return }
  if (!editor.instanceId) { message.warning('请选择实例'); return }
  if (!Number.isInteger(editor.intervalMinutes) || editor.intervalMinutes < 10 || editor.intervalMinutes > 525600) {
    message.warning('执行间隔必须是 10 到 525600 之间的整数分钟')
    return
  }
  if (editor.dnsEnabled && !selectedDnsProviderConfigured.value) {
    message.warning(`${editor.dnsProvider === 'CF' ? 'Cloudflare' : '阿里云 DNS'}尚未配置，请切换服务商或关闭 DNS 同步`)
    return
  }
  if (editor.dnsEnabled && !editor.fqdn.trim()) { message.warning('请填写完整域名'); return }
  const payload: ScheduledIpTaskPayload = {
    tenantConfigId: editor.tenantConfigId, region: editor.region,
    instanceId: editor.instanceId, instanceName: editor.instanceName, shape: editor.shape,
    compartmentId: editor.compartmentId, currentPublicIp: editor.currentPublicIp,
    intervalMinutes: editor.intervalMinutes, firstRunNow: editor.firstRunNow,
    dnsEnabled: editor.dnsEnabled, dnsProvider: editor.dnsEnabled ? editor.dnsProvider : undefined,
    fqdn: editor.dnsEnabled ? editor.fqdn.trim() : undefined,
    notifySuccess: editor.notifySuccess, notifyIpFailure: editor.notifyIpFailure,
    notifyDnsFailure: editor.notifyDnsFailure, notifyAutoPaused: editor.notifyAutoPaused,
  }
  saving.value = true
  try {
    if (editingId.value) await updateScheduledIpTask({ ...payload, id: editingId.value })
    else await createScheduledIpTask(payload)
    message.success(editingId.value ? '任务配置已保存' : '定时换 IP 任务已创建')
    editorVisible.value = false
    await loadOverview()
  } catch (e: any) {
    message.error(e?.message || '保存任务失败')
  } finally {
    saving.value = false
  }
}

async function toggleTask(task: ScheduledIpTask) {
  busyIds.add(task.id)
  try {
    await setScheduledIpTaskEnabled(task.id, !task.enabled)
    message.success(task.enabled ? '任务已暂停' : '任务已启用')
    await loadOverview()
  } catch (e: any) {
    message.error(e?.message || '修改任务状态失败')
  } finally {
    busyIds.delete(task.id)
  }
}

async function runNow(task: ScheduledIpTask) {
  busyIds.add(task.id)
  try {
    await runScheduledIpTask(task.id)
    message.success('立即执行请求已提交')
    window.setTimeout(() => void loadOverview(false, true), 800)
  } catch (e: any) {
    message.error(e?.message || '提交立即执行失败')
  } finally {
    busyIds.delete(task.id)
  }
}

async function copyTask(task: ScheduledIpTask) {
  busyIds.add(task.id)
  try {
    await copyScheduledIpTask(task.id)
    message.success('任务已复制，新副本默认暂停')
    await loadOverview()
  } catch (e: any) {
    message.error(e?.message || '复制任务失败')
  } finally {
    busyIds.delete(task.id)
  }
}

function confirmDelete(task: ScheduledIpTask) {
  Modal.confirm({
    title: '确定删除该任务？', content: task.name, okText: '删除', cancelText: '取消', okType: 'danger',
    maskClosable: false, keyboard: false,
    async onOk() {
      busyIds.add(task.id)
      try {
        await deleteScheduledIpTask(task.id)
        message.success('任务已删除')
        await loadOverview()
      } catch (e: any) {
        message.error(e?.message || '删除任务失败')
        throw e
      } finally {
        busyIds.delete(task.id)
      }
    },
  })
}

async function openLogs(task: ScheduledIpTask) {
  const requestSeq = ++logLoadSeq
  logTask.value = task
  taskLogs.value = []
  logsVisible.value = true
  logsLoading.value = true
  try {
    const res = await listScheduledIpTaskLogs(task.id)
    if (requestSeq !== logLoadSeq || logTask.value?.id !== task.id || !logsVisible.value) return
    taskLogs.value = res.data || []
  } catch (e: any) {
    if (requestSeq === logLoadSeq) message.error(e?.message || '读取执行日志失败')
  } finally {
    if (requestSeq === logLoadSeq) logsLoading.value = false
  }
}

function closeLogs() {
  if (retryingDns.value) return
  logLoadSeq++
  logsVisible.value = false
  taskLogs.value = []
}

async function retryDns() {
  if (!logTask.value) return
  const task = logTask.value
  retryingDns.value = true
  try {
    await retryScheduledIpDns(task.id)
    message.success('DNS 重新解析请求已提交')
    window.setTimeout(async () => {
      if (logsVisible.value && logTask.value?.id === task.id) await openLogs(task)
      await loadOverview(false, true)
    }, 1000)
  } catch (e: any) {
    message.error(e?.message || '提交 DNS 重试失败')
  } finally {
    retryingDns.value = false
  }
}

function taskFilterStatus(task: ScheduledIpTask) {
  if (['IP_FAILED', 'DNS_FAILED', 'AUTO_PAUSED'].includes(task.lastStatus || '')) return 'err'
  if (!task.enabled) return 'off'
  return 'on'
}

function tenantLabel(tenant: TenantRecord) {
  return tenant.username
}

function shortShape(shape?: string) {
  return (shape || '未知 Shape').replace(/^VM\.Standard\./, '')
}

function formatInterval(minutes?: number) {
  const value = Number(minutes || 0)
  if (value > 0 && value % 1440 === 0) return `${value / 1440} 天`
  if (value > 0 && value % 60 === 0) return `${value / 60} 小时`
  return `${value} 分钟`
}

function dateValue(value?: string) {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

function dateTimestamp(value?: string) {
  return dateValue(value)?.getTime() || 0
}

function formatDateTime(value?: string) {
  const date = dateValue(value)
  if (!date) return '暂无'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}

function formatTime(value?: string) {
  const date = dateValue(value)
  return date ? new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(date) : '暂无计划'
}

function relativeTime(value?: string) {
  const date = dateValue(value)
  if (!date) return '时间未知'
  const minutes = Math.max(0, Math.round((Date.now() - date.getTime()) / 60_000))
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  if (minutes < 1440) return `${Math.floor(minutes / 60)} 小时前`
  return `${Math.floor(minutes / 1440)} 天前`
}

function relativeFuture(value?: string) {
  const date = dateValue(value)
  if (!date) return '暂无计划'
  const minutes = Math.max(0, Math.ceil((date.getTime() - Date.now()) / 60_000))
  if (minutes < 1) return '即将执行'
  if (minutes < 60) return `约 ${minutes} 分钟后`
  if (minutes < 1440) return `约 ${Math.ceil(minutes / 60)} 小时后`
  return `约 ${Math.ceil(minutes / 1440)} 天后`
}

function formatLogTime(value?: string) {
  const date = dateValue(value)
  if (!date) return '时间未知'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}

function logBadgeClass(row: ScheduledIpRunLog) {
  if (row.status === 'RUNNING') return 'b-info'
  return row.status === 'SUCCESS' ? 'b-on' : row.status === 'PARTIAL_FAILED' ? 'b-warn' : 'b-err'
}

function logBadgeText(row: ScheduledIpRunLog) {
  if (row.status === 'RUNNING') return '执行中'
  return row.status === 'SUCCESS' ? '成功' : row.status === 'PARTIAL_FAILED' ? '部分失败' : row.status === 'AUTO_PAUSED' ? '已暂停' : '失败'
}
</script>

<style scoped>
.scheduled-ip-root { width: 100%; min-width: 0; }
.scheduled-page { min-width: 0; }
.page-toolbar { display: flex; align-items: center; justify-content: flex-end; gap: 10px; margin-bottom: 16px; }
.btn { min-height: 34px; padding: 7px 16px; border: 1px solid transparent; border-radius: 12px; display: inline-flex; align-items: center; justify-content: center; gap: 6px; color: var(--text-main); font: 500 14px/1.4 inherit; white-space: nowrap; cursor: pointer; transition: var(--trans); }
.btn:disabled, .link:disabled, .sw:disabled { opacity: .55; cursor: not-allowed; }
.btn-primary, .btn-brand { border-color: var(--primary); background: linear-gradient(135deg, var(--primary), var(--primary-hover)); color: #fff; font-weight: 600; box-shadow: 0 4px 10px -2px rgba(99, 102, 241, .4); }
.btn-primary:hover:not(:disabled), .btn-brand:hover:not(:disabled) { box-shadow: 0 8px 15px -3px rgba(99, 102, 241, .5); }
.btn-ghost { border-color: var(--border); background: var(--bg-card); backdrop-filter: blur(12px); }
.btn-ghost:hover:not(:disabled) { border-color: var(--primary); background: var(--input-bg); color: var(--primary); }
.stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; margin-bottom: 24px; }
.stat { min-width: 0; padding: 20px 18px; border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-card); box-shadow: var(--shadow-card); backdrop-filter: blur(12px); transition: border-color .2s, box-shadow .2s; }
.stat:hover { border-color: rgba(129, 140, 248, .5); box-shadow: var(--shadow-card), 0 0 0 1px rgba(129, 140, 248, .2), 0 10px 28px -12px rgba(99, 102, 241, .35); }
.stat .k { color: var(--text-sub); font-size: 12px; font-weight: 500; }
.stat .v { min-width: 0; margin-top: 10px; display: flex; align-items: baseline; gap: 8px; color: var(--text-main); font-size: 28px; font-weight: 800; font-variant-numeric: tabular-nums; letter-spacing: -.03em; line-height: 1.1; }
.stat .v small { color: var(--text-sub); font-size: 13px; font-weight: 500; }
.stat .sub { margin-top: 8px; overflow: hidden; color: var(--text-sub); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.stat-loading { width: 42%; height: 28px; margin-top: 10px; border-radius: 8px; background: linear-gradient(90deg, var(--input-bg) 25%, var(--primary-light) 50%, var(--input-bg) 75%); background-size: 200% 100%; animation: scheduled-shimmer 1.4s ease-in-out infinite; }
.stat-loading.sub-loading { width: 68%; height: 12px; margin-top: 8px; border-radius: 6px; }
.stat.warn .v { color: var(--warning-text); }
.stat.ok .v { color: var(--success-text); }
.stat .stat-text { overflow: hidden; font-size: 19px; text-overflow: ellipsis; white-space: nowrap; }
.filters { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.search { position: relative; flex: 1; min-width: 260px; }
.search input { width: 100%; height: 38px; padding: 0 16px 0 38px; border: 1px solid var(--border); border-radius: 12px; outline: none; background: var(--input-bg); color: var(--text-main); font: 14px inherit; transition: .15s; }
.search input::placeholder { color: color-mix(in srgb, var(--text-sub) 72%, transparent); }
.search input:focus { border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-light); }
.search > i { position: absolute; top: 10px; left: 14px; color: var(--text-sub); font-size: 16px; line-height: 18px; }
.sel { position: relative; }
.sel select, .field select, .field input { width: 100%; height: 38px; padding: 0 34px 0 14px; border: 1px solid var(--border); border-radius: 12px; outline: none; appearance: none; background: var(--input-bg); color: var(--text-main); font: 13px inherit; cursor: pointer; transition: .15s; }
.sel select { width: auto; min-width: 120px; }
.filters > .sel::after, .field:has(> select)::after { content: ''; position: absolute; right: 14px; bottom: 16px; width: 6px; height: 6px; border-right: 1.5px solid var(--text-sub); border-bottom: 1.5px solid var(--text-sub); transform: rotate(45deg); pointer-events: none; }
.field:has(> select) { position: relative; }
.field select:disabled { opacity: .6; cursor: not-allowed; }
.card { overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-card); box-shadow: var(--shadow-card); backdrop-filter: blur(12px); }
table { width: 100%; border-collapse: collapse; }
thead th { padding: 13px 16px; border-bottom: 1px solid var(--border); background: var(--input-bg); color: var(--text-sub); font-size: 12px; font-weight: 600; text-align: left; }
tbody td { padding: 14px 16px; border-bottom: 1px solid var(--border); color: var(--text-main); vertical-align: middle; }
tbody tr:last-child td { border-bottom: 0; }
tbody tr:hover { background: var(--primary-light); }
.loading-row:hover { background: transparent; }
.table-loading { padding: 34px 0; color: var(--text-sub); text-align: center; }
.cell-main { color: var(--text-main); font-weight: 500; }
.cell-sub { min-width: 0; margin-top: 2px; color: var(--text-sub); font-size: 12px; }
.mono, .ip { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; }
.dns-name, .result-message { max-width: 190px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.interval-text { font-size: 13px; }
.badge { padding: 3px 9px; border-radius: 9999px; display: inline-flex; align-items: center; gap: 5px; font-size: 11px; font-weight: 500; }
.badge .bd { width: 6px; height: 6px; border-radius: 50%; }
.b-on { background: var(--success-bg); color: var(--success-text); } .b-on .bd { background: var(--success-text); }
.b-off { background: color-mix(in srgb, var(--text-sub) 12%, transparent); color: var(--text-sub); } .b-off .bd { background: var(--text-sub); }
.b-err { background: var(--danger-bg); color: var(--danger-text); } .b-err .bd { background: var(--danger-text); }
.b-warn { background: var(--warning-bg); color: var(--warning-text); } .b-warn .bd { background: var(--warning-text); }
.b-info { background: var(--primary-light); color: var(--primary); } .b-info .bd { background: var(--primary); }
.prov { margin-top: 3px; display: inline-flex; align-items: center; gap: 6px; color: var(--text-sub); font-size: 12px; }
.pi { width: 16px; height: 16px; border-radius: 4px; display: inline-flex; align-items: center; justify-content: center; color: #fff; font-size: 8px; font-weight: 700; }
.pi-cf { background: #f6821f; } .pi-ali { background: #ff6a00; }
.sw { position: relative; width: 38px; min-width: 38px; height: 22px; margin: 0 auto; padding: 0; border: 0; border-radius: 9999px; background: color-mix(in srgb, var(--text-sub) 20%, transparent); cursor: pointer; transition: .2s; }
.sw.on { background: var(--primary); }
.sw::after { content: ''; position: absolute; top: 2px; left: 2px; width: 18px; height: 18px; border-radius: 50%; background: #fff; box-shadow: 0 1px 2px rgba(0, 0, 0, .2); transition: .2s; }
.sw.on::after { transform: translateX(16px); }
.row-actions { display: flex; align-items: center; justify-content: flex-end; gap: 4px; }
.link { padding: 3px 7px; border: 0; border-radius: 8px; background: transparent; color: var(--text-sub); font: 13px inherit; white-space: nowrap; cursor: pointer; }
.link:hover:not(:disabled) { background: var(--input-bg); color: var(--text-main); }
.link.go:hover:not(:disabled) { background: var(--primary-light); color: var(--primary); }
.link.danger:hover:not(:disabled) { background: var(--danger-bg); color: var(--danger-text); }
.empty { padding: 40px 16px; color: var(--text-sub); font-size: 13px; text-align: center; }
.scheduled-mask { position: fixed; inset: 0; z-index: 1350; background: rgba(0, 0, 0, .45); backdrop-filter: blur(8px); }
.scheduled-drawer { position: fixed; top: 0; right: 0; z-index: 1360; width: 560px; max-width: 94vw; height: 100dvh; border-left: 1px solid var(--border); border-radius: var(--radius-lg) 0 0 var(--radius-lg); display: flex; flex-direction: column; overflow: hidden; background: var(--bg-sidebar); color: var(--text-main); box-shadow: -8px 0 30px rgba(0, 0, 0, .2); }
.log-drawer { width: 480px; }
.drawer-head { padding: 20px 24px; border-bottom: 1px solid var(--border); display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.drawer-head h3 { margin: 0; color: var(--text-main); font-size: 18px; font-weight: 600; }
.drawer-head p { margin: 4px 0 0; color: var(--text-sub); font-size: 13px; }
.icon-btn { width: 40px; min-width: 40px; height: 40px; padding: 0; border: 1px solid var(--border); border-radius: 10px; display: inline-flex; align-items: center; justify-content: center; background: var(--bg-card); color: var(--text-main); font-size: 20px; cursor: pointer; transition: var(--trans); }
.icon-btn:hover { border-color: var(--primary); background: var(--input-bg); color: var(--primary); }
.drawer-body { flex: 1; overflow-y: auto; padding: 22px 24px; }
.drawer-foot { padding: 16px 24px; border-top: 1px solid var(--border); display: flex; justify-content: flex-end; gap: 10px; background: var(--bg-sidebar); }
.step { margin-bottom: 22px; }
.step:last-child { margin-bottom: 0; }
.step-h { margin-bottom: 12px; display: flex; align-items: center; gap: 9px; }
.step-n { width: 22px; height: 22px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex: none; background: var(--primary); color: #fff; font-size: 12px; font-weight: 600; }
.step-t { font-size: 14px; font-weight: 600; }
.step-d { margin-left: auto; color: var(--text-sub); font-size: 12px; text-align: right; }
.field { margin-bottom: 14px; }
.field label { margin-bottom: 6px; display: block; color: var(--text-sub); font-size: 12px; font-weight: 500; }
.field .req { color: var(--danger-text); }
.field input { padding: 0 12px; font-size: 14px; cursor: text; }
.field input:focus, .field select:focus { border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-light); }
.tenant-select { width: 100%; }
.tenant-select :deep(.ant-select-selector) {
  height: 38px !important;
  padding: 0 12px !important;
  align-items: center;
}
.tenant-select :deep(.ant-select-selection-search) {
  inset-inline-start: 12px;
  inset-inline-end: 34px;
}
.tenant-select :deep(.ant-select-selection-search-input) {
  height: 36px !important;
  line-height: 36px !important;
}
.tenant-select :deep(.ant-select-selection-placeholder),
.tenant-select :deep(.ant-select-selection-item) {
  line-height: 36px !important;
}
.hint { margin-top: 6px; color: var(--text-sub); font-size: 12px; line-height: 1.5; }
.hint.ok { color: var(--success-text); }
.hint.warn { color: var(--warning-text); }
.two { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.region-tabs, .chips { display: flex; gap: 8px; flex-wrap: wrap; }
.chip { padding: 6px 14px; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-card); color: var(--text-main); font: 13px inherit; cursor: pointer; }
.chip:hover { background: var(--input-bg); }
.chip.sel { border-color: var(--primary); background: var(--primary); color: #fff; }
.picker { max-height: 210px; overflow-y: auto; border: 1px solid var(--border); border-radius: 12px; background: var(--input-bg); }
.pick { width: 100%; padding: 11px 14px; border: 0; border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 12px; background: transparent; color: var(--text-main); text-align: left; cursor: pointer; }
.pick:last-of-type { border-bottom: 0; }
.pick:hover { background: var(--primary-light); }
.pick.sel { background: var(--primary-light); }
.pick .radio { position: relative; width: 16px; height: 16px; border: 2px solid var(--border); border-radius: 50%; flex: none; }
.pick.sel .radio { border-color: var(--primary); }
.pick.sel .radio::after { content: ''; position: absolute; inset: 2px; border-radius: 50%; background: var(--primary); }
.pick-main { min-width: 0; display: flex; flex-direction: column; }
.pn { font-size: 13.5px; font-weight: 500; }
.pm { margin-top: 1px; color: var(--text-sub); font-size: 12px; }
.pip { margin-left: auto; text-align: right; }
.interval-chips { margin-bottom: 10px; }
.seg { padding: 3px; border: 1px solid var(--border); border-radius: 9999px; display: inline-flex; background: var(--input-bg); }
.seg button { padding: 5px 14px; border: 0; border-radius: 9999px; background: transparent; color: var(--text-sub); font: 13px inherit; cursor: pointer; }
.seg button.on { background: var(--bg-card); color: var(--text-main); box-shadow: var(--shadow-card); }
.panel { padding: 4px 14px; border: 1px solid var(--border); border-radius: 12px; background: var(--input-bg); }
.trow { padding: 10px 0; border-bottom: 1px solid var(--border); display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.trow:last-child { border-bottom: 0; }
.tt { font-size: 13.5px; font-weight: 500; }
.td { margin-top: 2px; color: var(--text-sub); font-size: 12px; }
.panel :deep(.trow .tt) { font-size: 13.5px; font-weight: 500; }
.panel :deep(.trow .td) { margin-top: 2px; color: var(--text-sub); font-size: 12px; }
.panel :deep(.trow .sw) { position: relative; width: 38px; min-width: 38px; height: 22px; margin: 0; padding: 0; border: 0; border-radius: 9999px; background: color-mix(in srgb, var(--text-sub) 20%, transparent); cursor: pointer; transition: .2s; }
.panel :deep(.trow .sw.on) { background: var(--primary); }
.panel :deep(.trow .sw::after) { content: ''; position: absolute; top: 2px; left: 2px; width: 18px; height: 18px; border-radius: 50%; background: #fff; box-shadow: 0 1px 2px rgba(0, 0, 0, .2); transition: .2s; }
.panel :deep(.trow .sw.on::after) { transform: translateX(16px); }
.badge :deep(.bd) { width: 6px; height: 6px; border-radius: 50%; }
.badge.b-on :deep(.bd) { background: var(--success-text); }
.badge.b-off :deep(.bd) { background: var(--text-sub); }
.badge.b-err :deep(.bd) { background: var(--danger-text); }
.badge.b-warn :deep(.bd) { background: var(--warning-text); }
.badge.b-info :deep(.bd) { background: var(--primary); }
.dns-box { margin-top: 12px; }
.inline-loading { padding: 8px 0; color: var(--text-sub); font-size: 12px; }
.log-row { padding: 12px 0; border-bottom: 1px solid var(--border); display: flex; gap: 12px; }
.log-row:last-child { border-bottom: 0; }
.lt { width: 100px; padding-top: 2px; flex: none; color: var(--text-sub); font: 11px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.lc { min-width: 0; flex: 1; }
.lm { margin-top: 5px; overflow-wrap: anywhere; color: var(--text-sub); font-size: 13px; }
.arrow { margin: 0 6px; color: var(--text-sub); }
.spinning { display: inline-block; animation: scheduled-spin .85s linear infinite; }
@keyframes scheduled-spin { to { transform: rotate(360deg); } }
@keyframes scheduled-shimmer { to { background-position: -200% 0; } }

@media (max-width: 900px) {
  .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .two { grid-template-columns: 1fr; }
  .page-toolbar { align-items: stretch; flex-direction: column; }
  .page-toolbar .btn { width: 100%; }
}
@media (max-width: 768px) {
  .filters { align-items: stretch; }
  .search { width: 100%; min-width: 0; flex-basis: 100%; }
  .sel { min-width: 0; flex: 1 1 calc(50% - 5px); }
  .sel select { width: 100%; min-width: 0; }
  .card { overflow: visible; border: 0; background: transparent; box-shadow: none; backdrop-filter: none; }
  table, tbody, tr, td { display: block; width: 100%; }
  thead { display: none; }
  tbody { display: grid; gap: 10px; }
  tbody tr { padding: 12px; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-card); box-shadow: var(--shadow-card); backdrop-filter: blur(12px); }
  tbody td { padding: 5px 0; border: 0 !important; display: grid; grid-template-columns: 92px minmax(0, 1fr); gap: 8px; overflow-wrap: anywhere; text-align: left !important; }
  tbody td::before { color: var(--text-sub); font-size: 11px; line-height: 1.5; }
  tbody td:nth-child(1)::before { content: '任务 / 实例'; } tbody td:nth-child(2)::before { content: '租户'; }
  tbody td:nth-child(3)::before { content: '区域'; } tbody td:nth-child(4)::before { content: '当前公网 IP'; }
  tbody td:nth-child(5)::before { content: '域名 / DNS'; } tbody td:nth-child(6)::before { content: '周期 / 下次执行'; }
  tbody td:nth-child(7)::before { content: '最近结果'; } tbody td:nth-child(8)::before { content: '启用'; }
  tbody td:nth-child(9)::before { content: '操作'; }
  tbody tr.empty-row td { display: block; }
  tbody tr.empty-row td::before { display: none; }
  .row-actions { justify-content: flex-start; flex-wrap: wrap; }
  .sw { margin: 0; }
  .dns-name, .result-message { max-width: none; white-space: normal; }
}
@media (max-width: 520px) {
  .stats { grid-template-columns: 1fr; }
  .scheduled-drawer { width: 100%; max-width: 100%; border-radius: 0; }
  .drawer-head, .drawer-body, .drawer-foot { padding-right: 24px; padding-left: 24px; }
  .step-d { max-width: 46%; }
  .seg { width: 100%; }
  .seg button { flex: 1; padding-right: 8px; padding-left: 8px; }
}
@media (prefers-reduced-motion: reduce) { .spinning, .stat-loading { animation: none; } }
</style>
