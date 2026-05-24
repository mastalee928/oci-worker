<template>
  <div class="cf-workers-pages-tab">
    <div class="cf-toolbar">
      <a-space wrap>
        <a-button type="primary" :disabled="!cfConfigured" @click="openCreateModal">
          <template #icon><PlusOutlined /></template>
          创建应用程序
        </a-button>
        <a-button :loading="loading" :disabled="!cfConfigured" @click="loadAll">刷新</a-button>
      </a-space>
    </div>

    <a-alert
      v-if="usageLoadError"
      type="warning"
      show-icon
      :message="usageLoadError"
      style="margin-bottom: 12px"
    />
    <a-alert
      v-if="appsLoadError"
      type="warning"
      show-icon
      :message="appsLoadError"
      style="margin-bottom: 12px"
    />

    <a-card title="使用情况" size="small" class="cf-usage-card" :loading="usageLoading">
      <template #extra>
        <span class="cf-usage-range">{{ usage.dateRangeLabel || '—' }}</span>
      </template>

      <div class="cf-usage-today">
        <div class="cf-usage-today-head">
          <span>今天的请求</span>
          <span>{{ formatMetric(usage.todayRequests, usage.todayRequestsAvailable) }}
            <template v-if="usage.todayRequestsLimit != null"> / {{ formatNum(usage.todayRequestsLimit) }}</template>
          </span>
        </div>
        <a-progress
          v-if="usage.todayRequestsAvailable && usage.todayRequestsLimit"
          :percent="todayRequestPercent"
          :show-info="false"
          stroke-color="#f5a623"
          :stroke-width="6"
        />
      </div>

      <a-collapse ghost class="cf-usage-limits">
        <a-collapse-panel key="limits" header="查看限额">
          <p v-if="usage.limitsNote" class="cf-limits-note">{{ usage.limitsNote }}</p>
          <a-table
            :columns="limitColumns"
            :data-source="limitRows"
            row-key="feature"
            size="small"
            :pagination="false"
            :scroll="isMobile ? { x: 320 } : undefined"
          />
        </a-collapse-panel>
      </a-collapse>

      <a-row :gutter="[12, 12]" class="cf-usage-metrics">
        <a-col :xs="12" :sm="12" :md="6">
          <div class="cf-metric-card">
            <div class="cf-metric-label">请求</div>
            <div class="cf-metric-value">{{ formatMetricCompact(usage.periodRequests, usage.periodRequestsAvailable) }}</div>
          </div>
        </a-col>
        <a-col :xs="12" :sm="12" :md="6">
          <div class="cf-metric-card">
            <div class="cf-metric-label">CPU 时间</div>
            <div class="cf-metric-value">{{ formatCpu(usage.cpuTimeMs, usage.cpuTimeMsAvailable) }}</div>
          </div>
        </a-col>
        <a-col :xs="12" :sm="12" :md="6">
          <div class="cf-metric-card">
            <div class="cf-metric-label">可观测性事件</div>
            <div class="cf-metric-value">{{ formatMetric(usage.observabilityEvents, usage.observabilityAvailable) }}</div>
          </div>
        </a-col>
        <a-col :xs="12" :sm="12" :md="6">
          <div class="cf-metric-card">
            <div class="cf-metric-label">Workers 构建时间</div>
            <div class="cf-metric-value">{{ formatMetric(usage.buildMinutes, usage.buildMinutesAvailable, ' 分钟') }}</div>
          </div>
        </a-col>
      </a-row>
      <p class="cf-hint">用量来自 Cloudflare GraphQL（需 Account Analytics 读权限）。可观测性与构建时间暂未接入 API。</p>
    </a-card>

    <a-divider orientation="left">应用程序</a-divider>

    <a-table
      v-if="!isMobile"
      :columns="appColumns"
      :data-source="applications"
      :loading="loading"
      row-key="rowKey"
      size="middle"
      :pagination="false"
      :scroll="{ x: 880 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'kind'">
          <a-tag :color="record.kind === 'pages' ? 'blue' : 'purple'">
            {{ record.kind === 'pages' ? 'Pages' : 'Worker' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'url'">
          <a v-if="record.url" :href="urlHref(record)" target="_blank" rel="noopener">{{ record.url }}</a>
          <span v-else>—</span>
        </template>
      </template>
    </a-table>
    <a-spin v-else :spinning="loading">
      <a-empty v-if="!loading && applications.length === 0" description="暂无应用程序" />
      <div v-for="item in applications" :key="item.rowKey" class="mobile-card">
        <div class="mobile-card-header">
          <span class="mobile-card-title">{{ item.name || item.id }}</span>
          <a-tag :color="item.kind === 'pages' ? 'blue' : 'purple'">
            {{ item.kind === 'pages' ? 'Pages' : 'Worker' }}
          </a-tag>
        </div>
        <div class="mobile-card-body">
          <div class="mobile-card-row">
            <span class="label">地址</span>
            <span class="value">
              <a v-if="item.url" :href="urlHref(item)" target="_blank" rel="noopener">{{ item.url }}</a>
              <template v-else>—</template>
            </span>
          </div>
          <div v-if="item.createdOn" class="mobile-card-row">
            <span class="label">创建</span><span class="value">{{ item.createdOn }}</span>
          </div>
        </div>
      </div>
    </a-spin>

    <a-modal
      v-model:open="createVisible"
      title="创建应用程序"
      :footer="null"
      :width="isMobile ? 'calc(100vw - 32px)' : 720"
      destroy-on-close
    >
      <div v-if="createStep === 'choose'" class="cf-create-grid">
        <div class="cf-create-card" @click="startHelloWorld">
          <div class="cf-create-card-title">从 Hello World! 开始</div>
          <p>创建并部署一个最简单的 Worker，返回 Hello World。</p>
        </div>
        <div class="cf-create-card" @click="createStep = 'template'">
          <div class="cf-create-card-title">选择模板</div>
          <p>从内置 Worker / Pages 模板快速创建。</p>
        </div>
        <div class="cf-create-card" @click="createStep = 'upload'">
          <div class="cf-create-card-title">上传静态文件</div>
          <p>将文件夹或文件 Direct Upload 到新的 Pages 项目。</p>
        </div>
      </div>

      <template v-else-if="createStep === 'hello'">
        <a-form layout="vertical">
          <a-form-item label="Worker 名称" required>
            <a-input v-model:value="helloForm.name" placeholder="如 my-hello-worker（字母、数字、_、-）" allow-clear />
          </a-form-item>
          <a-space>
            <a-button @click="createStep = 'choose'">返回</a-button>
            <a-button type="primary" :loading="createLoading" @click="submitHelloWorld">创建并部署</a-button>
          </a-space>
        </a-form>
      </template>

      <template v-else-if="createStep === 'template'">
        <a-button type="link" class="cf-back-link" @click="createStep = 'choose'">← 返回</a-button>
        <a-spin :spinning="templatesLoading">
          <a-row :gutter="[12, 12]">
            <a-col v-for="tpl in templates" :key="tpl.id" :xs="24" :sm="12">
              <div class="cf-template-card" @click="selectTemplate(tpl)">
                <div class="cf-template-head">
                  <span class="cf-template-name">{{ tpl.name }}</span>
                  <a-tag size="small">{{ tpl.kind === 'pages' ? 'Pages' : 'Worker' }}</a-tag>
                </div>
                <p>{{ tpl.description }}</p>
              </div>
            </a-col>
          </a-row>
        </a-spin>
      </template>

      <template v-else-if="createStep === 'template-form'">
        <a-form layout="vertical">
          <a-alert type="info" show-icon style="margin-bottom: 12px"
            :message="`模板：${selectedTemplate?.name}（${selectedTemplate?.kind === 'pages' ? 'Pages' : 'Worker'}）`" />
          <a-form-item :label="selectedTemplate?.kind === 'pages' ? 'Pages 项目名称' : 'Worker 名称'" required>
            <a-input
              v-model:value="templateForm.name"
              :placeholder="selectedTemplate?.kind === 'pages' ? '小写字母、数字与连字符' : '字母、数字、下划线与连字符'"
              allow-clear
            />
          </a-form-item>
          <a-space>
            <a-button @click="createStep = 'template'">返回</a-button>
            <a-button type="primary" :loading="createLoading" @click="submitTemplate">创建</a-button>
          </a-space>
        </a-form>
      </template>

      <template v-else-if="createStep === 'upload'">
        <a-form layout="vertical">
          <a-button type="link" class="cf-back-link" @click="createStep = 'choose'">← 返回</a-button>
          <a-form-item label="Pages 项目名称" required>
            <a-input v-model:value="uploadForm.name" placeholder="如 my-static-site（小写、数字、连字符）" allow-clear />
          </a-form-item>
          <a-form-item label="静态文件" required>
            <input ref="fileInputRef" type="file" multiple webkitdirectory directory @change="onPickFiles" />
            <p v-if="uploadForm.files.length" class="cf-upload-summary">
              已选 {{ uploadForm.files.length }} 个文件（{{ formatBytes(uploadTotalBytes) }}）
            </p>
            <p class="cf-hint">选择包含 index.html 的文件夹；最多 {{ MAX_UPLOAD_FILES }} 个文件、总大小不超过 25 MiB。</p>
          </a-form-item>
          <a-space>
            <a-button @click="createStep = 'choose'">返回</a-button>
            <a-button type="primary" :loading="createLoading" @click="submitUpload">部署站点</a-button>
          </a-space>
        </a-form>
      </template>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { useIsMobile } from '../../composables/useIsMobile'
import {
  getCfWorkersPagesUsage,
  listCfWorkersPagesApplications,
  listCfWorkersPagesTemplates,
  createCfWorkerHelloWorld,
  createCfWorkerFromTemplate,
  createCfPagesFromTemplate,
  deployCfPagesStatic,
} from '../../api/cloudflare'

const MAX_UPLOAD_FILES = 100
const MAX_UPLOAD_BYTES = 25 * 1024 * 1024

interface UsageSummary {
  dateRangeLabel?: string
  limitsNote?: string
  todayRequests?: number | null
  todayRequestsAvailable?: boolean
  todayRequestsLimit?: number | null
  todayObservabilityEvents?: number | null
  todayObservabilityLimit?: number | null
  periodRequests?: number | null
  periodRequestsAvailable?: boolean
  cpuTimeMs?: number | null
  cpuTimeMsAvailable?: boolean
  observabilityEvents?: number | null
  observabilityAvailable?: boolean
  buildMinutes?: number | null
  buildMinutesAvailable?: boolean
}

interface AppRow {
  rowKey: string
  kind: 'worker' | 'pages'
  id?: string
  name?: string
  url?: string
  createdOn?: string
  modifiedOn?: string
}

interface TemplateRow {
  id: string
  name: string
  description?: string
  kind: 'worker' | 'pages'
}

defineProps<{ cfConfigured: boolean }>()
const { isMobile } = useIsMobile()

const loading = ref(false)
const usageLoading = ref(false)
const createLoading = ref(false)
const templatesLoading = ref(false)
const createVisible = ref(false)
const usageLoadError = ref('')
const appsLoadError = ref('')
const createStep = ref<'choose' | 'hello' | 'template' | 'template-form' | 'upload'>('choose')
const fileInputRef = ref<HTMLInputElement | null>(null)

const usage = reactive<UsageSummary>({})
const applications = ref<AppRow[]>([])
const templates = ref<TemplateRow[]>([])
const selectedTemplate = ref<TemplateRow | null>(null)

const helloForm = reactive({ name: '' })
const templateForm = reactive({ name: '' })
const uploadForm = reactive({ name: '', files: [] as { path: string; contentBase64: string }[] })

const uploadTotalBytes = computed(() =>
  uploadForm.files.reduce((sum, f) => sum + Math.ceil((f.contentBase64.length * 3) / 4), 0))

const limitColumns = [
  { title: '功能', dataIndex: 'feature' },
  { title: '使用情况', dataIndex: 'usage', width: 120 },
  { title: '限制', dataIndex: 'limit', width: 120 },
]

const limitRows = computed(() => [
  {
    feature: '今天的请求',
    usage: formatMetric(usage.todayRequests, usage.todayRequestsAvailable),
    limit: usage.todayRequestsLimit != null ? formatNum(usage.todayRequestsLimit) : '—',
  },
  {
    feature: '今天的可观测性事件',
    usage: formatMetric(usage.todayObservabilityEvents, usage.observabilityAvailable),
    limit: usage.todayObservabilityLimit != null ? formatNum(usage.todayObservabilityLimit) : '—',
  },
])

const todayRequestPercent = computed(() => {
  if (!usage.todayRequestsAvailable || usage.todayRequests == null || !usage.todayRequestsLimit) return 0
  const limit = usage.todayRequestsLimit
  return Math.min(100, Math.round((usage.todayRequests / limit) * 1000) / 10)
})

const appColumns = [
  { title: '类型', key: 'kind', width: 96 },
  { title: '名称', dataIndex: 'name', ellipsis: true },
  { title: '地址', key: 'url', ellipsis: true },
  { title: '创建', dataIndex: 'createdOn', width: 180 },
  { title: '修改', dataIndex: 'modifiedOn', width: 180 },
]

function formatNum(n?: number | null) {
  if (n === undefined || n === null) return '—'
  return n.toLocaleString('zh-CN')
}

function formatMetric(
  n?: number | null,
  available?: boolean,
  suffix = '',
) {
  if (available === false) return '—'
  if (n === undefined || n === null) return '—'
  return `${n.toLocaleString('zh-CN')}${suffix}`
}

function formatMetricCompact(n?: number | null, available?: boolean) {
  if (available === false || n === undefined || n === null) return '—'
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(2)}k`
  return String(n)
}

function formatCpu(ms?: number | null, available?: boolean) {
  if (available === false || ms === undefined || ms === null) return '—'
  return `${ms.toLocaleString('zh-CN')} ms`
}

function formatBytes(n: number) {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KiB`
  return `${(n / (1024 * 1024)).toFixed(2)} MiB`
}

function urlHref(record: AppRow) {
  const u = record.url || ''
  if (u.startsWith('http')) return u
  return `https://${u}`
}

function resetUsage() {
  Object.keys(usage).forEach(k => delete (usage as Record<string, unknown>)[k])
}

async function loadUsage() {
  usageLoading.value = true
  usageLoadError.value = ''
  try {
    const res = await getCfWorkersPagesUsage(true)
    resetUsage()
    Object.assign(usage, res.data || {})
  } catch (e: unknown) {
    resetUsage()
    usageLoadError.value = (e as Error)?.message || '拉取使用情况失败（请检查 API 令牌是否含 Analytics 读权限）'
  } finally {
    usageLoading.value = false
  }
}

async function loadApplications() {
  loading.value = true
  appsLoadError.value = ''
  try {
    const res = await listCfWorkersPagesApplications(true)
    applications.value = (res.data || []).map((item: AppRow, idx: number) => ({
      ...item,
      rowKey: `${item.kind}-${item.name || item.id || idx}`,
    }))
  } catch (e: unknown) {
    applications.value = []
    appsLoadError.value = (e as Error)?.message || '拉取应用程序列表失败'
  } finally {
    loading.value = false
  }
}

async function loadTemplates() {
  templatesLoading.value = true
  try {
    const res = await listCfWorkersPagesTemplates(true)
    templates.value = res.data || []
  } finally {
    templatesLoading.value = false
  }
}

async function loadAll() {
  await Promise.all([loadUsage(), loadApplications()])
}

function openCreateModal() {
  createStep.value = 'choose'
  helloForm.name = ''
  templateForm.name = ''
  uploadForm.name = ''
  uploadForm.files = []
  selectedTemplate.value = null
  if (fileInputRef.value) fileInputRef.value.value = ''
  createVisible.value = true
  if (templates.value.length === 0) loadTemplates()
}

function startHelloWorld() {
  createStep.value = 'hello'
}

function selectTemplate(tpl: TemplateRow) {
  selectedTemplate.value = tpl
  templateForm.name = ''
  createStep.value = 'template-form'
}

async function submitHelloWorld() {
  const name = helloForm.name.trim()
  if (!name) {
    message.warning('请输入 Worker 名称')
    return
  }
  createLoading.value = true
  try {
    await createCfWorkerHelloWorld({ name })
    message.success('Worker 已创建')
    createVisible.value = false
    await loadApplications()
  } finally {
    createLoading.value = false
  }
}

async function submitTemplate() {
  const name = templateForm.name.trim()
  const tpl = selectedTemplate.value
  if (!tpl) return
  if (!name) {
    message.warning('请输入名称')
    return
  }
  createLoading.value = true
  try {
    if (tpl.kind === 'pages') {
      await createCfPagesFromTemplate({ name, templateId: tpl.id })
    } else {
      await createCfWorkerFromTemplate({ name, templateId: tpl.id })
    }
    message.success('已创建')
    createVisible.value = false
    await loadApplications()
  } finally {
    createLoading.value = false
  }
}

function normalizeUploadPaths(files: { path: string; contentBase64: string }[]) {
  if (files.length <= 1) return files
  const firstSeg = files[0].path.split('/')[0]
  if (!firstSeg || !files.every(f => f.path.startsWith(`${firstSeg}/`))) return files
  return files
    .map(f => ({ ...f, path: f.path.slice(firstSeg.length + 1) }))
    .filter(f => f.path)
}

async function onPickFiles(e: Event) {
  const input = e.target as HTMLInputElement
  const fileList = input.files
  if (!fileList?.length) {
    uploadForm.files = []
    return
  }
  const files: { path: string; contentBase64: string }[] = []
  let totalBytes = 0
  for (const file of Array.from(fileList)) {
    if (files.length >= MAX_UPLOAD_FILES) {
      message.warning(`最多选择 ${MAX_UPLOAD_FILES} 个文件`)
      break
    }
    const rel = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name
    const path = rel.replace(/^\/+/, '').replace(/\\/g, '/')
    if (!path || path.includes('..')) continue
    if (file.size > MAX_UPLOAD_BYTES) {
      message.warning(`文件过大：${path}`)
      continue
    }
    totalBytes += file.size
    if (totalBytes > MAX_UPLOAD_BYTES) {
      message.warning('总大小不能超过 25 MiB')
      break
    }
    const contentBase64 = await readFileBase64(file)
    files.push({ path, contentBase64 })
  }
  uploadForm.files = normalizeUploadPaths(files)
}

function readFileBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const result = reader.result
      if (typeof result !== 'string') {
        reject(new Error('read failed'))
        return
      }
      const comma = result.indexOf(',')
      resolve(comma >= 0 ? result.slice(comma + 1) : result)
    }
    reader.onerror = () => reject(reader.error ?? new Error('read failed'))
    reader.readAsDataURL(file)
  })
}

async function submitUpload() {
  const name = uploadForm.name.trim()
  if (!name) {
    message.warning('请输入 Pages 项目名称')
    return
  }
  if (uploadForm.files.length === 0) {
    message.warning('请选择要上传的文件或文件夹')
    return
  }
  createLoading.value = true
  try {
    await deployCfPagesStatic({ name, files: uploadForm.files })
    message.success('Pages 站点已部署')
    createVisible.value = false
    await loadApplications()
  } finally {
    createLoading.value = false
  }
}

defineExpose({ loadAll })
</script>

<style scoped>
.cf-workers-pages-tab { min-height: 120px; }
.cf-toolbar { margin-bottom: 16px; }
.cf-usage-card { margin-bottom: 16px; }
.cf-usage-range { font-size: 12px; color: var(--text-sub); }
.cf-usage-today { margin-bottom: 12px; }
.cf-usage-today-head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 6px;
  gap: 8px;
  flex-wrap: wrap;
}
.cf-usage-limits { margin-bottom: 16px; }
.cf-limits-note { margin: 0 0 8px; font-size: 12px; color: var(--text-sub); }
.cf-usage-metrics { margin-top: 8px; }
.cf-metric-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  min-height: 72px;
}
.cf-metric-label { font-size: 12px; color: var(--text-sub); margin-bottom: 6px; }
.cf-metric-value { font-size: 22px; font-weight: 600; word-break: break-all; }
.cf-hint { margin-top: 12px; font-size: 12px; color: var(--text-sub); }
.cf-create-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}
.cf-create-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.cf-create-card:hover {
  border-color: var(--primary, #1677ff);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}
.cf-create-card-title { font-weight: 600; margin-bottom: 8px; }
.cf-create-card p { margin: 0; font-size: 13px; color: var(--text-sub); }
.cf-template-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  height: 100%;
}
.cf-template-card:hover { border-color: var(--primary, #1677ff); }
.cf-template-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.cf-template-name { font-weight: 600; }
.cf-back-link { padding-left: 0; margin-bottom: 8px; }
.cf-upload-summary { margin: 8px 0 0; font-size: 13px; }
.mobile-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}
.mobile-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
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
