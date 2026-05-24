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
      :scroll="{ x: 1100 }"
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
        <template v-else-if="column.key === 'actions'">
          <a-space v-if="record.kind === 'worker'" wrap size="small">
            <a-button type="link" size="small" @click="openEditWorker(record)">编辑代码</a-button>
            <a-button type="link" size="small" @click="openRenameWorker(record)">重命名</a-button>
            <a-button type="link" size="small" danger @click="openDeleteWorker(record)">删除</a-button>
          </a-space>
          <span v-else class="cf-muted">—</span>
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
          <div v-if="item.kind === 'worker'" class="mobile-card-actions">
            <a-space wrap size="small">
              <a-button size="small" @click="openEditWorker(item)">编辑代码</a-button>
              <a-button size="small" @click="openRenameWorker(item)">重命名</a-button>
              <a-button size="small" danger @click="openDeleteWorker(item)">删除</a-button>
            </a-space>
          </div>
          <div v-if="item.createdOn" class="mobile-card-row">
            <span class="label">创建</span><span class="value">{{ item.createdOn }}</span>
          </div>
        </div>
      </div>
    </a-spin>

    <!-- 创建向导 -->
    <a-modal
      v-model:open="createVisible"
      :mask-closable="false"
      :keyboard="false"
      :title="createModalTitle"
      :footer="null"
      :width="isMobile ? 'calc(100vw - 32px)' : 760"
      destroy-on-close
    >
      <div v-if="createStep !== 'choose' && createStep !== 'success'" class="cf-step-bar">
        <span :class="{ active: createStepIndex >= 1 }">1. 选择方法</span>
        <span class="cf-step-sep">→</span>
        <span :class="{ active: createStepIndex >= 2 }">2. {{ createStepTwoLabel }}</span>
        <span v-if="createStepIndex >= 3" class="cf-step-sep">→</span>
        <span v-if="createStepIndex >= 3" :class="{ active: createStepIndex >= 3 }">3. 部署</span>
      </div>

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
            <a-button type="primary" @click="proceedHelloToDeploy">下一步：预览并部署</a-button>
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
            <a-button type="primary" @click="proceedTemplateToDeploy">下一步：预览并部署</a-button>
          </a-space>
        </a-form>
      </template>

      <template v-else-if="createStep === 'deploy-worker'">
        <a-spin :spinning="deployPreviewLoading">
          <a-form layout="vertical">
            <a-alert type="info" show-icon style="margin-bottom: 12px"
              :message="`部署 Worker：${deployForm.label || 'Hello World'}`" />
            <a-form-item label="Worker 名称">
              <a-input v-model:value="deployForm.name" placeholder="Worker 名称" allow-clear />
            </a-form-item>
            <a-form-item label="workers.dev 地址（部署后可用）">
              <a-input :value="workerDevPreviewUrl" readonly>
                <template v-if="workerDevPreviewUrl" #addonAfter>
                  <a :href="workerDevPreviewUrl" target="_blank" rel="noopener">预览</a>
                </template>
              </a-input>
              <p v-if="!workersSubdomain" class="cf-hint">未能读取账户子域，部署后将尝试自动启用 workers.dev。</p>
            </a-form-item>
            <a-form-item label="Worker 脚本代码" required>
              <div class="cf-code-editor-wrap">
                <div class="cf-code-editor-head">
                  <span>在此编写 ES Module，部署前可随意修改</span>
                  <span class="cf-code-len">{{ deployForm.script.length }} 字符</span>
                </div>
                <a-textarea
                  v-model:value="deployForm.script"
                  class="cf-code-editor"
                  :rows="16"
                  spellcheck="false"
                  placeholder="export default { async fetch() { return new Response('Hello'); } };"
                />
              </div>
              <p class="cf-hint">编辑完成后点「部署」，将上传脚本并启用 workers.dev 子域。</p>
            </a-form-item>
            <a-space>
              <a-button @click="backFromDeployWorker">返回</a-button>
              <a-button type="primary" :loading="createLoading" @click="submitDeployWorker">部署</a-button>
            </a-space>
          </a-form>
        </a-spin>
      </template>

      <template v-else-if="createStep === 'deploy-pages'">
        <a-spin :spinning="deployPreviewLoading">
          <a-form layout="vertical">
            <a-alert type="info" show-icon style="margin-bottom: 12px"
              :message="`部署 Pages：${deployForm.label || selectedTemplate?.name || ''}`" />
            <a-form-item label="Pages 项目名称">
              <a-input v-model:value="deployForm.name" placeholder="项目名称" allow-clear />
            </a-form-item>
            <a-form-item label="文件预览">
              <a-table
                :columns="pagesPreviewColumns"
                :data-source="deployForm.pagesFiles"
                row-key="path"
                size="small"
                :pagination="false"
                :scroll="{ y: 240 }"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'preview'">
                    <pre class="cf-file-preview">{{ filePreviewSnippet(record.content) }}</pre>
                  </template>
                </template>
              </a-table>
              <p class="cf-hint">确认文件无误后部署。Pages 地址将在部署成功后显示。</p>
            </a-form-item>
            <a-space>
              <a-button @click="createStep = 'template-form'">返回</a-button>
              <a-button type="primary" :loading="createLoading" @click="submitDeployPagesTemplate">部署</a-button>
            </a-space>
          </a-form>
        </a-spin>
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
            <a-button type="primary" :disabled="uploadForm.files.length === 0" @click="proceedUploadPreview">下一步：预览文件</a-button>
          </a-space>
        </a-form>
      </template>

      <template v-else-if="createStep === 'upload-preview'">
        <a-form layout="vertical">
          <a-alert type="info" show-icon style="margin-bottom: 12px" :message="`Pages 项目：${uploadForm.name}`" />
          <a-form-item label="上传文件列表">
            <a-table
              :columns="uploadPreviewColumns"
              :data-source="uploadPreviewRows"
              row-key="path"
              size="small"
              :pagination="false"
              :scroll="{ y: 280 }"
            />
          </a-form-item>
          <a-space>
            <a-button @click="createStep = 'upload'">返回</a-button>
            <a-button type="primary" :loading="createLoading" @click="submitUpload">部署站点</a-button>
          </a-space>
        </a-form>
      </template>

      <template v-else-if="createStep === 'success'">
        <a-result status="success" title="部署成功">
          <template #subTitle>
            <p>{{ successInfo.kind === 'pages' ? 'Pages 项目' : 'Worker' }}「{{ successInfo.name }}」已部署。</p>
            <p v-if="successInfo.url">
              访问地址：
              <a :href="successInfo.url" target="_blank" rel="noopener">{{ successInfo.url }}</a>
            </p>
            <p v-else class="cf-hint">地址生成中，请稍后刷新列表。</p>
          </template>
          <template #extra>
            <a-space>
              <a-button v-if="successInfo.url" type="primary" @click="openSuccessUrl">打开链接</a-button>
              <a-button @click="finishCreate">完成</a-button>
            </a-space>
          </template>
        </a-result>
      </template>
    </a-modal>

    <!-- 编辑已有 Worker -->
    <a-modal
      v-model:open="editWorkerVisible"
      :mask-closable="false"
      :keyboard="false"
      title="编辑 Worker 代码"
      :width="isMobile ? 'calc(100vw - 32px)' : 760"
      :confirm-loading="editWorkerLoading"
      ok-text="保存并部署"
      cancel-text="取消"
      destroy-on-close
      @ok="submitEditWorker"
    >
      <a-spin :spinning="editWorkerScriptLoading">
        <a-form layout="vertical">
          <a-form-item label="Worker 名称">
            <a-input :value="editWorkerForm.name" readonly />
          </a-form-item>
          <a-form-item v-if="editWorkerForm.url" label="workers.dev 地址">
            <a :href="editWorkerForm.url" target="_blank" rel="noopener">{{ editWorkerForm.url }}</a>
          </a-form-item>
          <a-form-item label="Worker 脚本代码" required>
            <div class="cf-code-editor-wrap">
              <div class="cf-code-editor-head">
                <span>修改后保存将重新部署到 Cloudflare</span>
                <span class="cf-code-len">{{ editWorkerForm.script.length }} 字符</span>
              </div>
              <a-textarea
                v-model:value="editWorkerForm.script"
                class="cf-code-editor"
                :rows="16"
                spellcheck="false"
                placeholder="Worker ES Module 代码"
              />
            </div>
          </a-form-item>
        </a-form>
      </a-spin>
    </a-modal>

    <a-modal
      v-model:open="renameWarningVisible"
      :mask-closable="false"
      :keyboard="false"
      title="重命名前请注意"
      ok-text="继续重命名"
      cancel-text="取消"
      :width="isMobile ? 'calc(100vw - 32px)' : 520"
      destroy-on-close
      @ok="confirmRenameWarning"
    >
      <a-alert type="warning" show-icon style="margin-bottom: 16px">
        <template #message>重命名不会自动更新域名上的 Workers 路由</template>
      </a-alert>
      <p class="cf-rename-notice">
        即将重命名 Worker「<b>{{ renameWorkerForm.name }}</b>」。系统会把当前脚本部署到新名称，并删除原名称下的 Worker。
      </p>
      <p class="cf-rename-notice">
        若您曾在某个域名的「Workers 路由」里，将访问路径绑定到<strong>原 Worker 名称</strong>，重命名后这些路由规则<strong>不会</strong>自动改成新名称，相关访问可能失效。
      </p>
      <p class="cf-rename-notice">
        请在重命名完成后，前往对应域名的 Workers 路由页面，把路由中的脚本名改为新名称。
      </p>
      <p class="cf-rename-notice cf-rename-notice-last">确认已了解上述影响后，可继续填写新名称。</p>
    </a-modal>

    <a-modal
      v-model:open="renameWorkerVisible"
      :mask-closable="false"
      :keyboard="false"
      title="重命名 Worker"
      :confirm-loading="renameWorkerLoading"
      ok-text="重命名"
      cancel-text="取消"
      destroy-on-close
      @ok="submitRenameWorker"
    >
      <a-form layout="vertical">
        <a-form-item label="当前名称">
          <a-input :value="renameWorkerForm.name" readonly />
        </a-form-item>
        <a-form-item label="新名称" required>
          <a-input
            v-model:value="renameWorkerForm.newName"
            placeholder="字母、数字、下划线与连字符"
            allow-clear
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="deleteWorkerVisible"
      :mask-closable="false"
      :keyboard="false"
      title="安全验证 — 删除 Worker"
      :width="400"
      :confirm-loading="deleteWorkerLoading"
      ok-text="确认删除"
      :ok-button-props="{ danger: true }"
      destroy-on-close
      @ok="confirmDeleteWorker"
    >
      <a-alert type="error" show-icon style="margin-bottom: 16px">
        <template #message>删除 Worker 不可恢复，验证码已发送至 Telegram</template>
      </a-alert>
      <p v-if="deleteWorkerTarget" class="cf-delete-target">将删除：<b>{{ deleteWorkerTarget }}</b></p>
      <a-input
        v-model:value="deleteWorkerVerifyCode"
        placeholder="请输入6位验证码"
        size="large"
        :maxlength="6"
        allow-clear
      />
      <div class="cf-verify-footer">
        <span>验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="deleteWorkerVerifySending" @click="sendDeleteWorkerCode">
          重新发送
        </a-button>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, nextTick } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { useIsMobile } from '../../composables/useIsMobile'
import {
  getCfWorkersPagesUsage,
  listCfWorkersPagesApplications,
  listCfWorkersPagesTemplates,
  getCfWorkersSubdomainInfo,
  getCfWorkersPagesTemplatePreview,
  deployCfWorker,
  getCfWorkerScript,
  updateCfWorkerScript,
  renameCfWorker,
  deleteCfWorker,
  createCfPagesFromTemplate,
  deployCfPagesStatic,
} from '../../api/cloudflare'
import { sendVerifyCode } from '../../api/system'

const MAX_UPLOAD_FILES = 100
const MAX_UPLOAD_BYTES = 25 * 1024 * 1024

type CreateStep =
  | 'choose'
  | 'hello'
  | 'template'
  | 'template-form'
  | 'deploy-worker'
  | 'deploy-pages'
  | 'upload'
  | 'upload-preview'
  | 'success'

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

interface PagesPreviewFile {
  path: string
  content: string
}

const props = defineProps<{ cfConfigured: boolean; active?: boolean }>()
const { isMobile } = useIsMobile()

const loading = ref(false)
const usageLoading = ref(false)
const createLoading = ref(false)
const templatesLoading = ref(false)
const deployPreviewLoading = ref(false)
const editWorkerLoading = ref(false)
const editWorkerScriptLoading = ref(false)
const createVisible = ref(false)
const editWorkerVisible = ref(false)
const renameWarningVisible = ref(false)
const renameWorkerVisible = ref(false)
const deleteWorkerVisible = ref(false)
const renameWorkerLoading = ref(false)
const deleteWorkerLoading = ref(false)
const deleteWorkerVerifySending = ref(false)
const deleteWorkerVerifyCode = ref('')
const deleteWorkerTarget = ref('')
const usageLoadError = ref('')
const appsLoadError = ref('')
const createStep = ref<CreateStep>('choose')
const fileInputRef = ref<HTMLInputElement | null>(null)
const workersSubdomain = ref('')
const deployBackStep = ref<CreateStep>('hello')

const usage = reactive<UsageSummary>({})
const applications = ref<AppRow[]>([])
const templates = ref<TemplateRow[]>([])
const selectedTemplate = ref<TemplateRow | null>(null)

const helloForm = reactive({ name: '' })
const templateForm = reactive({ name: '' })
const uploadForm = reactive({ name: '', files: [] as { path: string; contentBase64: string }[] })

const deployForm = reactive({
  name: '',
  script: '',
  templateId: '',
  label: '',
  kind: 'worker' as 'worker' | 'pages',
  pagesFiles: [] as PagesPreviewFile[],
})

const successInfo = reactive({
  name: '',
  url: '',
  kind: 'worker' as 'worker' | 'pages',
})

const editWorkerForm = reactive({
  name: '',
  script: '',
  url: '',
})

const renameWorkerForm = reactive({
  name: '',
  newName: '',
})

const uploadTotalBytes = computed(() =>
  uploadForm.files.reduce((sum, f) => sum + Math.ceil((f.contentBase64.length * 3) / 4), 0))

const createModalTitle = computed(() => {
  if (createStep.value === 'success') return '部署完成'
  if (createStep.value === 'deploy-worker') return '部署 Worker'
  if (createStep.value === 'deploy-pages') return '部署 Pages'
  if (createStep.value === 'upload-preview') return '预览并部署 Pages'
  return '创建应用程序'
})

const createStepIndex = computed(() => {
  switch (createStep.value) {
    case 'choose': return 1
    case 'hello':
    case 'template':
    case 'upload': return 1
    case 'template-form': return 2
    case 'deploy-worker':
    case 'deploy-pages':
    case 'upload-preview': return 3
    default: return 1
  }
})

const createStepTwoLabel = computed(() => {
  if (createStep.value === 'deploy-pages' || createStep.value === 'upload-preview') return '预览文件'
  if (createStep.value === 'deploy-worker' || createStep.value === 'template-form' || createStep.value === 'hello') {
    return '填写名称'
  }
  return '填写信息'
})

const workerDevPreviewUrl = computed(() => {
  const name = deployForm.name.trim()
  if (!name) return ''
  if (workersSubdomain.value) {
    return `https://${name}.${workersSubdomain.value}.workers.dev`
  }
  return `https://${name}.workers.dev`
})

const uploadPreviewRows = computed(() =>
  uploadForm.files.map(f => ({
    path: f.path,
    size: formatBytes(Math.ceil((f.contentBase64.length * 3) / 4)),
  })))

const limitColumns = [
  { title: '功能', dataIndex: 'feature' },
  { title: '使用情况', dataIndex: 'usage', width: 120 },
  { title: '限制', dataIndex: 'limit', width: 120 },
]

const pagesPreviewColumns = [
  { title: '路径', dataIndex: 'path', ellipsis: true, width: 160 },
  { title: '内容预览', key: 'preview' },
]

const uploadPreviewColumns = [
  { title: '路径', dataIndex: 'path', ellipsis: true },
  { title: '大小', dataIndex: 'size', width: 100 },
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
  { title: '创建', dataIndex: 'createdOn', width: 170 },
  { title: '修改', dataIndex: 'modifiedOn', width: 170 },
  { title: '操作', key: 'actions', width: 220, fixed: 'right' as const },
]

function formatNum(n?: number | null) {
  if (n === undefined || n === null) return '—'
  return n.toLocaleString('zh-CN')
}

function formatMetric(n?: number | null, available?: boolean, suffix = '') {
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

function filePreviewSnippet(content: string, maxLen = 120) {
  const s = (content || '').replace(/\s+/g, ' ').trim()
  return s.length > maxLen ? `${s.slice(0, maxLen)}…` : s
}

function resetUsage() {
  Object.keys(usage).forEach(k => delete (usage as Record<string, unknown>)[k])
}

async function loadWorkersSubdomain() {
  try {
    const res = await getCfWorkersSubdomainInfo(true)
    workersSubdomain.value = res.data?.subdomain || ''
  } catch {
    workersSubdomain.value = ''
  }
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

watch(
  () => [props.active, props.cfConfigured] as const,
  async ([isActive, configured]) => {
    if (isActive && configured) {
      await nextTick()
      loadAll()
    }
  },
  { immediate: true },
)

function resetDeployForm() {
  deployForm.name = ''
  deployForm.script = ''
  deployForm.templateId = ''
  deployForm.label = ''
  deployForm.kind = 'worker'
  deployForm.pagesFiles = []
}

function openCreateModal() {
  createStep.value = 'choose'
  helloForm.name = ''
  templateForm.name = ''
  uploadForm.name = ''
  uploadForm.files = []
  selectedTemplate.value = null
  resetDeployForm()
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

async function loadDeployWorkerPreview(templateId: string) {
  deployPreviewLoading.value = true
  try {
    await loadWorkersSubdomain()
    const res = await getCfWorkersPagesTemplatePreview({ templateId }, true)
    deployForm.script = res.data?.script || ''
  } catch (e: unknown) {
    message.error((e as Error)?.message || '加载 Worker 模板预览失败')
    deployForm.script = ''
  } finally {
    deployPreviewLoading.value = false
  }
}

async function loadDeployPagesPreview(templateId: string) {
  deployPreviewLoading.value = true
  try {
    const res = await getCfWorkersPagesTemplatePreview({ templateId }, true)
    const files = (res.data?.files || []) as { path: string; content: string }[]
    deployForm.pagesFiles = files.map(f => ({ path: f.path, content: f.content || '' }))
  } catch (e: unknown) {
    message.error((e as Error)?.message || '加载 Pages 模板预览失败')
    deployForm.pagesFiles = []
  } finally {
    deployPreviewLoading.value = false
  }
}

function proceedHelloToDeploy() {
  const name = helloForm.name.trim()
  if (!name) {
    message.warning('请输入 Worker 名称')
    return
  }
  deployBackStep.value = 'hello'
  deployForm.name = name
  deployForm.templateId = 'hello-world'
  deployForm.label = 'Hello World'
  deployForm.kind = 'worker'
  createStep.value = 'deploy-worker'
  loadDeployWorkerPreview('hello-world')
}

function proceedTemplateToDeploy() {
  const name = templateForm.name.trim()
  const tpl = selectedTemplate.value
  if (!tpl) return
  if (!name) {
    message.warning('请输入名称')
    return
  }
  deployForm.name = name
  deployForm.templateId = tpl.id
  deployForm.label = tpl.name
  deployForm.kind = tpl.kind
  if (tpl.kind === 'pages') {
    deployBackStep.value = 'template-form'
    createStep.value = 'deploy-pages'
    loadDeployPagesPreview(tpl.id)
  } else {
    deployBackStep.value = 'template-form'
    createStep.value = 'deploy-worker'
    loadDeployWorkerPreview(tpl.id)
  }
}

function backFromDeployWorker() {
  createStep.value = deployBackStep.value
}

function showSuccess(name: string, url: string | undefined, kind: 'worker' | 'pages') {
  successInfo.name = name
  successInfo.url = url || ''
  successInfo.kind = kind
  createStep.value = 'success'
}

async function submitDeployWorker() {
  const name = deployForm.name.trim()
  const script = deployForm.script.trim()
  if (!name) {
    message.warning('请输入 Worker 名称')
    return
  }
  if (!script) {
    message.warning('Worker 代码不能为空')
    return
  }
  createLoading.value = true
  try {
    const res = await deployCfWorker({ name, script })
    const url = res.data?.url as string | undefined
    showSuccess(name, url, 'worker')
  } finally {
    createLoading.value = false
  }
}

async function submitDeployPagesTemplate() {
  const name = deployForm.name.trim()
  const templateId = deployForm.templateId
  if (!name || !templateId) {
    message.warning('请填写项目名称')
    return
  }
  createLoading.value = true
  try {
    const res = await createCfPagesFromTemplate({ name, templateId })
    const url = res.data?.url as string | undefined
    showSuccess(name, url, 'pages')
  } finally {
    createLoading.value = false
  }
}

function proceedUploadPreview() {
  const name = uploadForm.name.trim()
  if (!name) {
    message.warning('请输入 Pages 项目名称')
    return
  }
  if (uploadForm.files.length === 0) {
    message.warning('请选择要上传的文件或文件夹')
    return
  }
  createStep.value = 'upload-preview'
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
    const res = await deployCfPagesStatic({ name, files: uploadForm.files })
    const url = res.data?.url as string | undefined
    showSuccess(name, url, 'pages')
  } finally {
    createLoading.value = false
  }
}

function openSuccessUrl() {
  if (successInfo.url) {
    window.open(successInfo.url, '_blank', 'noopener')
  }
}

async function finishCreate() {
  createVisible.value = false
  await loadApplications()
}

async function openEditWorker(record: AppRow) {
  const name = record.name || record.id
  if (!name) return
  editWorkerForm.name = name
  editWorkerForm.script = ''
  editWorkerForm.url = record.url || ''
  editWorkerVisible.value = true
  editWorkerScriptLoading.value = true
  try {
    const res = await getCfWorkerScript({ name }, true)
    editWorkerForm.script = res.data?.script || ''
  } catch (e: unknown) {
    message.error((e as Error)?.message || '拉取 Worker 代码失败')
    editWorkerVisible.value = false
  } finally {
    editWorkerScriptLoading.value = false
  }
}

async function submitEditWorker() {
  const name = editWorkerForm.name.trim()
  const script = editWorkerForm.script.trim()
  if (!name || !script) {
    message.warning('Worker 代码不能为空')
    return
  }
  editWorkerLoading.value = true
  try {
    const res = await updateCfWorkerScript({ name, script })
    const url = res.data?.url as string | undefined
    message.success('Worker 已保存并部署')
    editWorkerVisible.value = false
    if (url) editWorkerForm.url = url
    await loadApplications()
  } finally {
    editWorkerLoading.value = false
  }
}

function openRenameWorker(record: AppRow) {
  const name = record.name || record.id
  if (!name) return
  renameWorkerForm.name = name
  renameWorkerForm.newName = ''
  renameWarningVisible.value = true
}

function confirmRenameWarning() {
  renameWarningVisible.value = false
  renameWorkerVisible.value = true
}

async function submitRenameWorker() {
  const name = renameWorkerForm.name.trim()
  const newName = renameWorkerForm.newName.trim()
  if (!newName) {
    message.warning('请输入新名称')
    return Promise.reject()
  }
  if (newName === name) {
    message.warning('新名称不能与当前名称相同')
    return Promise.reject()
  }
  renameWorkerLoading.value = true
  try {
    await renameCfWorker({ name, newName })
    message.success('Worker 已重命名')
    renameWorkerVisible.value = false
    await loadApplications()
  } finally {
    renameWorkerLoading.value = false
  }
}

async function openDeleteWorker(record: AppRow) {
  const name = record.name || record.id
  if (!name) return
  deleteWorkerTarget.value = name
  deleteWorkerVerifyCode.value = ''
  deleteWorkerVisible.value = true
  try {
    await sendDeleteWorkerCode()
  } catch {
    /* sendVerifyCode 已提示 */
  }
}

async function sendDeleteWorkerCode() {
  deleteWorkerVerifySending.value = true
  try {
    await sendVerifyCode('cfWorkerDelete')
    message.success('验证码已发送至 Telegram')
  } finally {
    deleteWorkerVerifySending.value = false
  }
}

async function confirmDeleteWorker() {
  const name = deleteWorkerTarget.value.trim()
  if (!name) return Promise.reject()
  if (!deleteWorkerVerifyCode.value || deleteWorkerVerifyCode.value.length !== 6) {
    message.warning('请输入6位验证码')
    return Promise.reject()
  }
  deleteWorkerLoading.value = true
  try {
    await deleteCfWorker({ name, verifyCode: deleteWorkerVerifyCode.value })
    message.success('Worker 已删除')
    deleteWorkerVisible.value = false
    await loadApplications()
  } finally {
    deleteWorkerLoading.value = false
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
.cf-hint { margin-top: 8px; font-size: 12px; color: var(--text-sub); }
.cf-muted { color: var(--text-sub); font-size: 12px; }
.cf-step-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 16px;
  font-size: 13px;
  color: var(--text-sub);
}
.cf-step-bar .active { color: var(--primary, #1677ff); font-weight: 600; }
.cf-step-sep { opacity: 0.5; }
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
.cf-code-editor-wrap {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--input-bg);
}
.cf-code-editor-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  font-size: 12px;
  color: var(--text-sub);
  border-bottom: 1px solid var(--border);
}
.cf-code-len {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  flex-shrink: 0;
}
.cf-code-editor-wrap :deep(textarea.ant-input) {
  min-height: 320px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  line-height: 1.55;
  border: none !important;
  border-radius: 0 !important;
  box-shadow: none !important;
  background: transparent !important;
  color: var(--text-main) !important;
  caret-color: var(--text-main) !important;
  resize: vertical;
}
.cf-code-editor-wrap :deep(textarea.ant-input::placeholder) {
  color: var(--text-sub) !important;
}
.cf-code-editor-wrap :deep(textarea.ant-input:focus) {
  box-shadow: none !important;
}
.cf-file-preview {
  margin: 0;
  padding: 6px 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  max-height: 56px;
  overflow: hidden;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-main);
  background: var(--input-bg);
  border-radius: 4px;
}
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
.mobile-card-actions { margin-top: 8px; }
.cf-verify-footer {
  margin-top: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--text-sub);
}
.cf-delete-target {
  margin-bottom: 12px;
  color: var(--text-main);
}
.cf-rename-notice {
  margin: 0 0 12px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-main);
}
.cf-rename-notice-last {
  margin-bottom: 0;
  color: var(--text-sub);
  font-size: 13px;
}
</style>
