<template>
  <div class="oracle-ai-page">
    <a-tabs v-model:activeKey="activeModeTab" class="mode-tabs">
      <a-tab-pane key="single" tab="单账户模式">
    <a-card class="mb-card" title="Oracle 生成式 AI 网关" :bordered="false">
      <a-space direction="vertical" style="width: 100%">
        <div class="sub top-line">
          <div>
            Base：
            <code>http://&lt;主机或域名&gt;:{{ openaiPort }}/v1</code>
            <span class="sub-muted">（Header：<code>Authorization: Bearer sk-...</code>）</span>
          </div>
          <a-space align="center" class="proxy-switch">
            <a-switch v-model:checked="openaiProxyEnabled" @change="onToggleProxy" />
            <span class="sub-muted">启用 OpenAI 转发</span>
          </a-space>
        </div>
        <a-typography-paragraph copyable :content="publicBaseUrl">
          <code class="code-wrap">{{ publicBaseUrl }}</code>
        </a-typography-paragraph>
      </a-space>
      <a-alert
        v-if="baseHint"
        class="mb-alert"
        type="info"
        :message="baseHint"
        show-icon
      />
    </a-card>

    <a-card title="API 密钥" :bordered="false" class="mt-card">
      <a-row class="key-toolbar" :gutter="[8, 8]" align="middle" justify="start" wrap>
        <a-col>
          <a-button type="primary" :disabled="!ociUserId" @click="openKeyModal">生成新密钥</a-button>
        </a-col>
        <a-col>
          <a-button :disabled="!ociUserId" @click="refreshKeys">刷新</a-button>
        </a-col>
      </a-row>
      <a-table
        v-if="!isMobile"
        :columns="keyColumns"
        :data-source="keys"
        :loading="keysLoading"
        row-key="id"
        size="middle"
        :pagination="false"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'dis'">
            <a-tag :color="record.disabled ? 'red' : 'green'">{{ record.disabled ? '已禁用' : '正常' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'keyMasked'">
            <code class="key-masked">{{ record.keyMasked || 'sk-****' }}</code>
          </template>
          <template v-else-if="column.key === 'createTime'">
            {{ formatKeyTime(record.createTime) }}
          </template>
          <template v-else-if="column.key === 'lastUsed'">
            {{ formatKeyTime(record.lastUsed) }}
          </template>
          <template v-else-if="column.key === 'a'">
            <a-space>
              <a-button size="small" type="link" @click="viewKey(record)">查看</a-button>
              <a-button size="small" @click="toggleKey(record)">
                {{ record.disabled ? '启用' : '禁用' }}
              </a-button>
              <a-popconfirm title="确定删除？客户端需改密钥。" @confirm="removeK(record)">
                <a-button size="small" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
      <template v-else>
        <a-empty v-if="!keys.length && !keysLoading" description="无密钥" />
        <div v-for="k in keys" :key="k.id" class="key-card-m">
          <div>
            <b>{{ k.name || '未命名' }}</b> <code class="p">{{ k.keyMasked || 'sk-****' }}</code>
          </div>
          <a-button size="small" type="link" @click="viewKey(k)">查看</a-button>
          <a-button size="small" @click="toggleKey(k)">{{ k.disabled ? '启用' : '禁用' }}</a-button>
          <a-popconfirm title="确定删除？" @confirm="removeK(k)">
            <a-button size="small" danger>删除</a-button>
          </a-popconfirm>
        </div>
      </template>
    </a-card>

    <a-card title="默认 max_tokens" :bordered="false" class="mt-card">
      <a-form layout="vertical" class="max-token-form">
        <a-form-item label="max_tokens">
          <a-space wrap align="center">
            <a-input-number
              v-model:value="defaultMaxTokensInput"
              :min="1"
              :max="200000"
              :precision="0"
              :controls="false"
              class="max-token-input"
            />
            <a-button type="primary" :loading="savingDefaultMaxTokens" @click="saveDefaultMaxTokens">
              保存
            </a-button>
            <a-button :loading="gatewayLoading" @click="loadGateway">刷新</a-button>
            <span class="sub-muted">当前默认: {{ defaultMaxTokens }}</span>
          </a-space>
        </a-form-item>
        <div class="sub max-token-help">
          仅影响未显式传 <code>max_tokens</code> 的 AI 请求，保存后立即生效。
        </div>
      </a-form>
    </a-card>

    <a-card title="租户与模型" :bordered="false" class="mt-card" :loading="tenantsLoading">
      <a-form layout="vertical">
        <a-form-item label="选择租户（Region=该租户 OCI 区域）">
          <a-select
            v-model:value="ociUserId"
            :options="tenantOptions"
            placeholder="选择租户"
            show-search
            :filter-option="filterTenant"
            @change="onTenantChange"
            allow-clear
            :get-popup-container="selectPopupContainer"
          />
        </a-form-item>
        <a-form-item v-if="selectedRegion" label="区域">
          <a-tag color="blue">{{ selectedRegion }}</a-tag>
        </a-form-item>
        <a-form-item label="可选模型（OCI 管理面 ListModels）">
          <a-select
            v-model:value="modelPick"
            mode="multiple"
            :options="modelOptions"
            :loading="modelsLoading"
            placeholder="先选租户，再刷新"
            allow-clear
            show-search
            :filter-option="filterModel"
            :max-tag-count="6"
            :max-tag-placeholder="(omittedValues: any[]) => `+${omittedValues?.length || 0}`"
            :get-popup-container="selectPopupContainer"
            :dropdown-style="{ maxHeight: 'min(70vh, 480px)' }"
          />
        </a-form-item>
        <a-button type="primary" :loading="modelsLoading" :disabled="!ociUserId" @click="() => loadModelsIfNeeded(true)">
          刷新模型列表
        </a-button>
      </a-form>
    </a-card>

    <a-card title="对话测试（浏览器直连 /v1）" :bordered="false" class="mt-card">
      <a-alert
        class="mb-alert"
        type="info"
        show-icon
        message="浏览器直连 :8080/v1 快速验证（绕过 New API/IDE 差异）。"
      />
      <a-form layout="vertical">
        <a-form-item label="API Key（sk-...，仅保存在浏览器本地）">
          <a-input-password v-model:value="chatApiKey" placeholder="sk-..." allow-clear />
        </a-form-item>
        <a-form-item label="模型">
          <a-select
            v-model:value="chatModel"
            :options="modelOptions"
            :disabled="!modelOptions.length"
            placeholder="先在上方拉取模型列表"
            show-search
            :filter-option="filterModel"
            allow-clear
            :get-popup-container="selectPopupContainer"
            :dropdown-style="{ maxHeight: 'min(70vh, 480px)' }"
          />
        </a-form-item>
        <a-form-item label="用户消息">
          <a-textarea v-model:value="chatUserText" :rows="4" placeholder="输入要测试的内容…" />
        </a-form-item>
        <a-space wrap>
          <a-button
            type="primary"
            :loading="chatSending"
            :disabled="!chatApiKey || !chatModel || !chatUserText"
            @click="sendChatTest"
          >
            发送测试
          </a-button>
          <a-button :disabled="chatSending" @click="clearChatTest">清空</a-button>
        </a-space>
      </a-form>

      <div v-if="chatError" class="chat-box chat-error">
        <div class="chat-label">错误</div>
        <pre class="chat-pre">{{ chatError }}</pre>
      </div>
      <div v-if="chatAssistantText" class="chat-box">
        <div class="chat-label">Assistant</div>
        <pre class="chat-pre">{{ chatAssistantText }}</pre>
      </div>
    </a-card>

      </a-tab-pane>

      <a-tab-pane key="multi" tab="多账户中转">
        <a-card title="独立中转端口" :bordered="false" class="mt-card">
          <a-row class="key-toolbar" :gutter="[8, 8]" align="middle">
            <a-col>
              <a-button type="primary" @click="openPortModal()">添加端口绑定</a-button>
            </a-col>
            <a-col>
              <a-button :loading="portBindingsLoading" @click="loadPortBindings">刷新</a-button>
            </a-col>
            <a-col>
              <span class="sub-muted">端口范围 30000-39999，保存后立即生效。</span>
            </a-col>
          </a-row>
          <a-alert
            class="mb-alert"
            type="info"
            show-icon
            message="保存后 OCIworker 会立即监听本机端口；如需外网访问，还需要在系统防火墙和 OCI 安全列表放行对应端口。"
          />
          <div v-if="isMobile" class="port-mobile-list">
            <a-spin v-if="portBindingsLoading" />
            <a-empty v-if="!portBindings.length && !portBindingsLoading" description="暂无端口绑定" />
            <div v-for="record in portBindings" :key="record.id" class="port-card-m">
              <div class="port-card-head">
                <div>
                  <div class="port-card-title">{{ record.name || `port-${record.port}` }}</div>
                  <code>{{ portBaseUrl(record.port) }}</code>
                </div>
                <a-switch :checked="record.enabled" :loading="portSwitchingId === record.id" @change="(v: boolean) => togglePortBinding(record, v)" />
              </div>
              <div class="port-card-grid">
                <span>端口</span><b>{{ record.port }}</b>
                <span>租户</span><b>{{ record.tenantName || record.ociUserId || '-' }}</b>
                <span>区域</span><b>{{ regionDisplay(record.ociRegion) || '-' }}</b>
                <span>Tokens</span><b>{{ record.defaultMaxTokens || '全局默认' }}</b>
                <span>模型</span>
                <a-tooltip :title="modelTooltip(record.allowedModels)">
                  <b class="model-summary">{{ modelSummary(record.allowedModels) }}</b>
                </a-tooltip>
                <span>状态</span><a-tag :color="portStatusColor(record)">{{ portStatusText(record) }}</a-tag>
                <span>Key备注</span><code class="key-masked">{{ record.keyName || record.keyMasked || 'sk-****' }}</code>
              </div>
              <div v-if="record.statusMessage" class="sub-muted status-message">{{ record.statusMessage }}</div>
              <a-space class="port-card-actions" wrap>
                <a-button size="small" @click="revealPortKey(record)">查看密钥</a-button>
                <a-button size="small" @click="openPortModal(record)">编辑</a-button>
                <a-popconfirm title="确定删除该端口绑定？" @confirm="removePortBindingRow(record)">
                  <a-button size="small" danger>删除</a-button>
                </a-popconfirm>
              </a-space>
            </div>
          </div>
          <a-table
            v-else
            class="port-table"
            :columns="portColumns"
            :data-source="portBindings"
            :loading="portBindingsLoading"
            row-key="id"
            size="middle"
            :pagination="false"
            :scroll="{ x: 1610 }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'enabled'">
                <a-switch :checked="record.enabled" :loading="portSwitchingId === record.id" @change="(v: boolean) => togglePortBinding(record, v)" />
              </template>
              <template v-else-if="column.key === 'port'">
                <code>{{ record.port }}</code>
              </template>
              <template v-else-if="column.key === 'tenant'">
                <div>{{ record.tenantName || record.ociUserId || '-' }}</div>
                <span class="sub-muted">{{ regionDisplay(record.ociRegion) || '-' }}</span>
              </template>
              <template v-else-if="column.key === 'base'">
                <a-typography-paragraph copyable :content="portBaseUrl(record.port)" style="margin: 0">
                  <code class="code-wrap">{{ portBaseUrl(record.port) }}</code>
                </a-typography-paragraph>
              </template>
              <template v-else-if="column.key === 'maxTokens'">
                {{ record.defaultMaxTokens || '全局默认' }}
              </template>
              <template v-else-if="column.key === 'models'">
                <a-tooltip :title="modelTooltip(record.allowedModels)">
                  <span class="model-summary">{{ modelSummary(record.allowedModels) }}</span>
                </a-tooltip>
              </template>
              <template v-else-if="column.key === 'status'">
                <a-tag :color="portStatusColor(record)">{{ portStatusText(record) }}</a-tag>
                <div v-if="record.statusMessage" class="sub-muted status-message">{{ record.statusMessage }}</div>
              </template>
              <template v-else-if="column.key === 'key'">
                <code class="key-masked">{{ record.keyName || record.keyMasked || 'sk-****' }}</code>
              </template>
              <template v-else-if="column.key === 'lastUsed'">
                {{ formatKeyTime(record.lastUsed) }}
              </template>
              <template v-else-if="column.key === 'a'">
                <a-space class="port-actions" :size="4">
                  <a-button size="small" @click="revealPortKey(record)">查看密钥</a-button>
                  <a-button size="small" @click="openPortModal(record)">编辑</a-button>
                  <a-popconfirm title="确定删除该端口绑定？" @confirm="removePortBindingRow(record)">
                    <a-button size="small" danger>删除</a-button>
                  </a-popconfirm>
                </a-space>
              </template>
            </template>
          </a-table>
        </a-card>
      </a-tab-pane>
    </a-tabs>

    <a-modal :mask-closable="false" :keyboard="false" v-model:open="keyModalOpen" title="新密钥" :confirm-loading="keyCreating" @ok="submitKey">
      <a-form layout="vertical">
        <a-form-item label="备注名（可选）">
          <a-input v-model:value="keyName" />
        </a-form-item>
      </a-form>
    </a-modal>
    <a-modal :mask-closable="false" :keyboard="false" v-model:open="plainKeyModalOpen" title="密钥已生成" :footer="null" :width="600">
      <a-alert
        class="mb-alert"
        type="info"
        message="密钥已写入数据库（加密保存）。关闭后仍可在列表点击「查看」复制完整密钥。"
        show-icon
      />
      <a-typography-paragraph copyable>
        <code class="key-plain">{{ newKeyPlain }}</code>
      </a-typography-paragraph>
    </a-modal>

    <a-modal :mask-closable="false" :keyboard="false"
      v-model:open="keyViewOpen"
      title="密钥详情"
      :footer="null"
      class="key-view-modal"
      :width="isMobile ? 'calc(100vw - 32px)' : 920"
    >
      <a-spin :spinning="keyViewLoading">
      <a-descriptions bordered size="small" :column="1">
        <a-descriptions-item label="备注">{{ keyViewRow?.name || '未命名' }}</a-descriptions-item>
        <template v-if="keyViewPort">
          <a-descriptions-item label="域名 URL">
            <a-typography-paragraph copyable :content="keyViewDomainBaseUrl" style="margin: 0">
              <code class="code-wrap">{{ keyViewDomainBaseUrl }}</code>
            </a-typography-paragraph>
          </a-descriptions-item>
          <a-descriptions-item label="IP URL">
            <a-typography-paragraph copyable :content="keyViewIpBaseUrl" style="margin: 0">
              <code class="code-wrap">{{ keyViewIpBaseUrl }}</code>
            </a-typography-paragraph>
          </a-descriptions-item>
        </template>
        <a-descriptions-item label="密钥">
          <template v-if="keyViewPlain">
            <a-typography-paragraph copyable style="margin: 0">
              <code class="key-plain">{{ keyViewPlain }}</code>
            </a-typography-paragraph>
          </template>
          <span v-else-if="keyViewError" style="color: var(--danger-text, #f87171)">{{ keyViewError }}</span>
          <span v-else style="color: var(--text-sub)">加载中…</span>
        </a-descriptions-item>
        <a-descriptions-item label="状态">
          <a-tag :color="keyViewRow?.disabled ? 'red' : 'green'">{{ keyViewRow?.disabled ? '已禁用' : '正常' }}</a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="创建">{{ formatKeyTime(keyViewRow?.createTime) }}</a-descriptions-item>
        <a-descriptions-item label="最后使用">{{ formatKeyTime(keyViewRow?.lastUsed) }}</a-descriptions-item>
        <a-descriptions-item v-if="!keyViewPort" label="Base">
          <a-typography-paragraph copyable :content="publicBaseUrl" style="margin: 0">
            <code class="code-wrap">{{ publicBaseUrl }}</code>
          </a-typography-paragraph>
        </a-descriptions-item>
      </a-descriptions>
      </a-spin>
      <a-alert
        class="mt-card"
        type="warning"
        show-icon
        message="完整密钥加密存于数据库；修改面板登录密码后若无法解密，请重新生成密钥。"
      />
    </a-modal>


    <a-modal :mask-closable="false" :keyboard="false" v-model:open="portModalOpen" :title="portForm.id ? '编辑端口绑定' : '添加端口绑定'" :confirm-loading="portSaving" :width="isMobile ? 'calc(100vw - 32px)' : 720" @ok="savePortBindingRow">
      <a-form layout="vertical">
        <a-form-item label="租户">
          <a-select
            v-model:value="portForm.ociUserId"
            :options="tenantOptions"
            placeholder="选择 OCI 租户"
            show-search
            :filter-option="filterTenant"
            :get-popup-container="selectPopupContainer"
            @change="onPortTenantChange"
          />
        </a-form-item>
        <a-form-item label="Region">
          <a-select
            v-model:value="portForm.ociRegion"
            :options="portRegionOptions"
            :loading="portRegionsLoading"
            placeholder="选择该租户订阅的 Region"
            show-search
            :filter-option="filterOciRegionSelectOption"
            :disabled="!portForm.ociUserId"
            :get-popup-container="selectPopupContainer"
            @change="onPortRegionChange"
          />
          <div class="sub-muted form-help">该端口会固定转发到这里选择的 OCI Generative AI 区域。</div>
        </a-form-item>
        <a-form-item label="API Key">
          <a-space direction="vertical" style="width: 100%">
            <a-select
              v-model:value="portForm.openaiKeyId"
              :options="portKeyOptions"
              :loading="portKeysLoading"
              placeholder="选择该租户的 API Key"
              :get-popup-container="selectPopupContainer"
            />
            <a-button size="small" :disabled="!portForm.ociUserId" :loading="portKeyCreating" @click="createPortTenantKey">
              生成该租户 API Key
            </a-button>
          </a-space>
        </a-form-item>
        <a-form-item label="端口">
          <a-input-number v-model:value="portForm.port" :min="30000" :max="39999" :precision="0" style="width: 100%" />
        </a-form-item>
        <a-form-item label="默认 max_tokens">
          <a-input-number
            v-model:value="portForm.defaultMaxTokens"
            :min="1"
            :max="200000"
            :precision="0"
            :controls="false"
            placeholder="留空使用全局默认"
            style="width: 100%"
          />
          <div class="sub-muted form-help">仅在请求未显式传 max_tokens 时生效。</div>
        </a-form-item>
        <a-form-item label="备注">
          <a-input v-model:value="portForm.name" placeholder="sub2api-channel-1" />
        </a-form-item>
        <a-form-item label="模型">
          <template v-if="isMobile">
            <select
              multiple
              :value="portForm.allowedModels"
              @change="(e) => { portForm.allowedModels = Array.from(e.target.selectedOptions, o => o.value) }"
              size="6"
              style="width:100%;min-height:80px"
              :disabled="portModelsLoading"
            >
              <option v-if="!portModelOptions.length" disabled>暂无可用模型</option>
              <option v-for="m in portModelOptions" :key="m.value" :value="m.value">{{ m.label }}</option>
            </select>
            <div class="sub-muted form-help">保存后该端口的 /v1/models 只返回这里选择的模型；留空表示不限制。</div>
            <a-button
              class="port-model-refresh"
              size="small"
              :loading="portModelsLoading"
              :disabled="!portForm.ociUserId"
              @click="() => portForm.ociUserId && loadPortModels(portForm.ociUserId, portForm.ociRegion, true)"
            >
              刷新模型列表
            </a-button>
          </template>
          <a-select
            v-else
            v-model:value="portForm.allowedModels"
            mode="multiple"
            :options="portModelOptions"
            :loading="portModelsLoading"
            placeholder="留空不限制模型"
            allow-clear
            show-search
            :filter-option="filterModel"
            :max-tag-count="6"
            :max-tag-placeholder="(omittedValues: any[]) => `+${omittedValues?.length || 0}`"
            :get-popup-container="selectPopupContainer"
            :dropdown-style="{ maxHeight: 'min(70vh, 480px)' }"
          />
        </a-form-item>

        <a-form-item label="启用">
          <a-switch v-model:checked="portForm.enabled" />
        </a-form-item>
      </a-form>
    </a-modal>
    <div class="sub sub-bottom" v-if="activeModeTab === 'single'">
      说明：未带 <code>max_tokens</code> 时使用当前默认值；请求体里 <code>force_non_stream: true</code> 会强制非流式。
      Multi-Agent 在网关内会走 <code>/v1/responses</code>。
    </div>
    <div class="sub sub-bottom" v-else>
      说明：多账户端口用于给 sub2api / New API 做负载均衡；端口范围 <code>30000-39999</code>。
      每个端口可单独设置 <code>max_tokens</code> 和模型列表；模型留空表示不限制。
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'OracleAI' })
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getTenantList } from '../api/tenant'
import { listOciRegionOptions } from '../api/system'
import {
  getOciRegionDisplayName,
  filterOciRegionSelectOption,
} from '../utils/ociRegionCatalog'
import {
  getOracleAiGateway,
  setOracleAiGatewayEnabled,
  setOracleAiDefaultMaxTokens,
  listOracleKeys,
  revealOracleKey,
  createOracleKey,
  setOracleKeyDisabled,
  removeOracleKey,
  listOracleAiPortBindings,
  saveOracleAiPortBinding,
  setOracleAiPortBindingEnabled,
  removeOracleAiPortBinding,
  listOpenAiModels,
  oracleAiChatTest,
  getOracleAiUiState,
  saveOracleAiUiState,
} from '../api/oracleAi'

const tenantsLoading = ref(false)
const activeModeTab = ref('single')
const keysLoading = ref(false)
const modelsLoading = ref(false)
const keyCreating = ref(false)
const isMobile = ref(false)
const openaiPort = ref(8080)
const openaiPath = '/v1'
const ociUserId = ref<string | undefined>(undefined)
// 注意：后端 id 可能是 number；这里统一用 string，避免 localStorage 回填比较失败
const tenantOptions = ref<{ label: string; value: string; ociRegion: string }[]>([])
const keys = ref<any[]>([])
const portBindings = ref<any[]>([])
const portBindingsLoading = ref(false)
const portSwitchingId = ref('')
const portModalOpen = ref(false)
const portSaving = ref(false)
const portKeysLoading = ref(false)
const portKeyCreating = ref(false)
const portKeyOptions = ref<{ label: string; value: string }[]>([])
const portRegionsLoading = ref(false)
const portRegionOptions = ref<{ label: string; value: string }[]>([])
const portModelsLoading = ref(false)
const portModelOptions = ref<{ label: string; value: string; title?: string }[]>([])
const portForm = ref<{
  id?: string
  name?: string
  port: number
  ociUserId?: string
  ociRegion?: string
  openaiKeyId?: string
  defaultMaxTokens?: number | null
  allowedModels: string[]
  enabled: boolean
}>({
  port: 30000,
  defaultMaxTokens: null,
  allowedModels: [],
  enabled: true,
})
const modelPick = ref<string[]>([])
const modelOptions = ref<
  {
    label: string
    value: string
    title?: string
    disabled?: boolean
  }[]
>([])

const keyModalOpen = ref(false)
const plainKeyModalOpen = ref(false)
const newKeyPlain = ref('')
const keyName = ref('')
const baseHint = ref('')
const openaiProxyEnabled = ref(true)
const gatewayLoading = ref(false)
const serverIp = ref('')
const defaultMaxTokens = ref(2048)
const defaultMaxTokensInput = ref<number | null>(2048)
const savingDefaultMaxTokens = ref(false)

const keyViewOpen = ref(false)
const keyViewRow = ref<any | null>(null)
const keyViewPlain = ref('')
const keyViewError = ref('')
const keyViewLoading = ref(false)
const keyViewPort = ref<number | null>(null)

const chatApiKey = ref('')
const chatModel = ref<string | undefined>(undefined)
const chatUserText = ref('')
const chatAssistantText = ref('')
const chatError = ref('')
const chatSending = ref(false)

const keyColumns = [
  { title: '备注', dataIndex: 'name', key: 'name' },
  { title: '密钥', key: 'keyMasked', width: 200 },
  { title: '状态', key: 'dis' },
  { title: '创建', key: 'createTime', width: 168 },
  { title: '最后使用', key: 'lastUsed', width: 168 },
  { title: '操作', key: 'a', width: 200 },
] as any

const portColumns = [
  { title: '开关', key: 'enabled', width: 84 },
  { title: '端口', key: 'port', width: 88 },
  { title: '租户', key: 'tenant', width: 220 },
  { title: '状态', key: 'status', width: 130 },
  { title: 'Base URL', key: 'base', width: 280 },
  { title: '上限', key: 'maxTokens', width: 96 },
  { title: '模型', key: 'models', width: 180 },
  { title: 'Key备注', key: 'key', width: 170 },
  { title: '最近使用', key: 'lastUsed', width: 140 },
  { title: '操作', key: 'a', width: 220 },
] as any

function formatKeyTime(iso?: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso)
  return d.toLocaleString('zh-CN', { hour12: false })
}

/** 下拉挂到 body，避免挂在 .app-content 内与 overflow 滚轮「抢事件」导致难滚回顶部 */
function selectPopupContainer() {
  return document.body
}

const selectedRegion = computed(() => {
  return tenantOptions.value.find((x) => x.value === ociUserId.value)?.ociRegion
})

function regionDisplay(region?: string) {
  const r = String(region || '').trim()
  if (!r) return ''
  return `${getOciRegionDisplayName(r)} (${r})`
}

const publicBaseUrl = computed(() => {
  if (typeof window === 'undefined') {
    return `https://<主机名>:${openaiPort.value}${openaiPath}`
  }
  const p = location.protocol
  const h = location.hostname
  return `${p}//${h}:${openaiPort.value}${openaiPath}`
})

function portBaseUrl(port?: number) {
  const p = Number(port || 0)
  if (!p) return ''
  if (typeof window === 'undefined') {
    return `http://<host>:${p}${openaiPath}`
  }
  return `${location.protocol}//${location.hostname}:${p}${openaiPath}`
}

const keyViewDomainBaseUrl = computed(() => {
  const port = Number(keyViewPort.value || 0)
  return port ? portBaseUrl(port) : ''
})

const keyViewIpBaseUrl = computed(() => {
  const port = Number(keyViewPort.value || 0)
  if (!port) return ''
  const host = serverIp.value || (typeof window !== 'undefined' ? location.hostname : '<host>')
  return `http://${host}:${port}${openaiPath}`
})

const LS_CHAT_KEY = 'ociworker.oracleAi.chatTest.v1'
const restoring = ref(false)
/** 避免「租户 options 未加载时 a-select 把值清掉」立刻触发 watch 用空值覆盖 localStorage */
const selectionPersistEnabled = ref(false)
const serverUiStateLoaded = ref(false)
const serverUiState = ref<{ ociUserId?: string; modelPick?: string[] } | null>(null)
let persistTimer: any = null

async function loadPersistedState() {
  try {
    const r: any = await getOracleAiUiState()
    const d = r?.data
    const s = d && typeof d === 'object' ? d : {}
    serverUiState.value = {
      ociUserId: typeof s.ociUserId === 'string' ? s.ociUserId : undefined,
      modelPick: Array.isArray(s.modelPick) ? (s.modelPick.filter((x: any) => typeof x === 'string') as string[]) : [],
    }
    serverUiStateLoaded.value = true

    // 先填值，后续等租户 options ready 再二次回填确认
    restoring.value = true
    if (serverUiState.value?.ociUserId) {
      ociUserId.value = serverUiState.value.ociUserId
    }
    if (serverUiState.value?.modelPick?.length) {
      modelPick.value = serverUiState.value.modelPick
    }
  } catch {
    serverUiStateLoaded.value = true
  } finally {
    setTimeout(() => {
      restoring.value = false
    }, 0)
  }
}

function persistState() {
  if (!selectionPersistEnabled.value) return
  if (persistTimer) clearTimeout(persistTimer)
  persistTimer = setTimeout(() => {
    saveOracleAiUiState({
      ociUserId: ociUserId.value || '',
      modelPick: (modelPick.value || []).slice(0, 200),
    }).catch(() => {})
  }, 300)
}

/** 租户列表已就绪后，从 localStorage 再应用一次，抵消 Select 在 options 空时的误清空 */
function reapplyOracleAiSelectionFromStorage() {
  // 保留函数名以减少改动：现在改为“从后端 UI state 再应用一次”
  try {
    const savedId = String(serverUiState.value?.ociUserId || '').trim()
    const savedModels = Array.isArray(serverUiState.value?.modelPick) ? serverUiState.value?.modelPick || [] : []
    if (savedId && tenantOptions.value.some((x) => x.value === savedId)) {
      restoring.value = true
      ociUserId.value = savedId
      if (savedModels.length) modelPick.value = savedModels
      setTimeout(() => {
        restoring.value = false
      }, 0)
    }
    if (ociUserId.value && tenantOptions.value.some((x) => x.value === ociUserId.value)) {
      loadModelsIfNeeded(false)
      refreshKeys()
    }
  } catch {
  }
}

function loadChatPersisted() {
  if (typeof window === 'undefined') return
  try {
    const raw = localStorage.getItem(LS_CHAT_KEY)
    if (!raw) return
    const s = JSON.parse(raw || '{}') || {}
    if (typeof s.chatApiKey === 'string') chatApiKey.value = s.chatApiKey
    if (typeof s.chatModel === 'string') chatModel.value = s.chatModel
  } catch {
  }
}

function persistChatState() {
  if (typeof window === 'undefined') return
  try {
    localStorage.setItem(
      LS_CHAT_KEY,
      JSON.stringify({
        chatApiKey: chatApiKey.value || '',
        chatModel: chatModel.value || '',
      }),
    )
  } catch {
  }
}

function checkM() {
  isMobile.value = window.innerWidth < 768
}

onMounted(() => {
  checkM()
  window.addEventListener('resize', checkM)
  loadPersistedState()
  loadChatPersisted()
  try {
    localStorage.removeItem('ociworker.oracleAi.chatTest.badModels.v1')
  } catch {
  }
  loadGateway()
  loadTenants()
  loadPortBindings()
})

onUnmounted(() => {
  window.removeEventListener('resize', checkM)
})

async function loadGateway() {
  gatewayLoading.value = true
  try {
    const r: any = await getOracleAiGateway()
    const p = r?.data?.openaiApiPort
    if (p != null) openaiPort.value = p
    serverIp.value = String(r?.data?.serverIp || '').trim()
    if (typeof r?.data?.openaiProxyEnabled === 'boolean') {
      openaiProxyEnabled.value = r.data.openaiProxyEnabled
    }
    const mt = Number(r?.data?.defaultMaxTokens)
    if (Number.isFinite(mt) && mt > 0) {
      defaultMaxTokens.value = Math.trunc(mt)
      defaultMaxTokensInput.value = Math.trunc(mt)
    }
    baseHint.value = r?.data?.baseUrlExample
      ? `Base 示例: ${r.data.baseUrlExample}`
      : `对外访问需在防火墙放行 TCP ${openaiPort.value}。`
  } catch {
    baseHint.value = `请放行 TCP 端口 ${openaiPort.value} 供 New API/客户端访问。`
  } finally {
    gatewayLoading.value = false
  }
}

async function saveDefaultMaxTokens() {
  const value = Number(defaultMaxTokensInput.value)
  if (!Number.isFinite(value) || value < 1) {
    message.warning('请输入大于 0 的 max_tokens')
    return
  }
  savingDefaultMaxTokens.value = true
  try {
    const r: any = await setOracleAiDefaultMaxTokens({ defaultMaxTokens: Math.trunc(value) })
    const saved = Number(r?.data?.defaultMaxTokens)
    defaultMaxTokens.value = Number.isFinite(saved) && saved > 0 ? Math.trunc(saved) : Math.trunc(value)
    defaultMaxTokensInput.value = defaultMaxTokens.value
    message.success('已保存，立即生效')
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    savingDefaultMaxTokens.value = false
  }
}

async function onToggleProxy(v: boolean) {
  try {
    const r: any = await setOracleAiGatewayEnabled({ enabled: v })
    const en = r?.data?.openaiProxyEnabled
    if (typeof en === 'boolean') {
      openaiProxyEnabled.value = en
    }
    message.success(v ? '已开启转发' : '已关闭转发')
  } catch (e: any) {
    message.error(e?.message || '设置失败')
    // revert by reloading gateway state
    loadGateway()
  }
}

function filterTenant(input: string, opt: any) {
  return (String(opt?.label || '')
    .toLowerCase()
    .includes((input || '').toLowerCase()))
}
function filterModel(input: string, opt: any) {
  return (String(opt?.label || '')
    .toLowerCase()
    .includes((input || '').toLowerCase()))
}

function modelSummary(models?: string[]) {
  if (!Array.isArray(models) || !models.length) return '不限制'
  if (models.length <= 2) return models.join(', ')
  return `${models[0]}, ${models[1]} +${models.length - 2}`
}

function modelTooltip(models?: string[]) {
  if (!Array.isArray(models) || !models.length) return '不限制模型'
  return models.join('\n')
}

function mapModelOptions(data: any) {
  let list: any[] = []
  if (data?.data && Array.isArray(data.data)) {
    list = data.data
  } else if (Array.isArray(data)) {
    list = data
  }
  const mapped = list
    .map((m) => {
      const id = String(m?.id || m || '').trim()
      const label = String(m?.displayName || m?.name || m?.id || m || '').trim()
      if (!id) return null
      const note = String(m?.ociworkerNote || '').trim()
      const ociId = String(m?.ociId || '').trim()
      const finalLabel = `${label || id}`
      const titleBits = [note, ociId].filter((x) => x && x.trim())
      const title = titleBits.join(' | ') || finalLabel
      return { value: id, label: finalLabel, title }
    })
    .filter((x) => x) as { label: string; value: string; title?: string }[]

  const seenVal = new Set<string>()
  const options: { label: string; value: string; title?: string }[] = []
  for (const opt of mapped) {
    const v = String(opt?.value || '').trim()
    if (!v || seenVal.has(v)) continue
    seenVal.add(v)
    options.push(opt)
  }
  return options
}

function ensureSelectedModelsInOptions(options: { label: string; value: string; title?: string }[], selected?: string[]) {
  const existing = new Set(options.map((x: any) => String(x?.value || '')))
  for (const raw of selected || []) {
    const s = String(raw || '').trim()
    if (!s || existing.has(s)) continue
    options.push({
      value: s,
      label: `${s}（不在当前列表）`,
      title: '不在当前列表（可能是租户/区域变化或模型下线）',
    })
    existing.add(s)
  }
  return options
}

async function loadTenants(options?: { silent?: boolean }) {
  const silent = options?.silent === true
  selectionPersistEnabled.value = false
  if (!silent) tenantsLoading.value = true
  try {
    const res: any = await getTenantList({ current: 1, size: 5000, keyword: '' })
    const rec = res.data?.records || []
    tenantOptions.value = rec.map((t: any) => ({
      label: `${t.username} (${t.ociRegion || '?'})`,
      value: String(t.id ?? ''),
      ociRegion: t.ociRegion,
    }))
    reapplyOracleAiSelectionFromStorage()
  } catch (e: any) {
    if (!silent) message.error(e?.message || '加载租户失败')
  } finally {
    if (!silent) tenantsLoading.value = false
    selectionPersistEnabled.value = true
  }
}

function onTenantChange() {
  if (restoring.value) {
    persistState()
    return
  }
  modelOptions.value = []
  modelPick.value = []
  persistState()
  loadModelsIfNeeded(false)
  refreshKeys()
}

watch(
  () => [ociUserId.value, modelPick.value],
  () => {
    if (tenantsLoading.value) return
    if (!selectionPersistEnabled.value) return
    persistState()
  },
  { deep: true },
)

watch(
  () => [chatApiKey.value, chatModel.value],
  () => persistChatState(),
  { deep: true },
)

async function loadModelsIfNeeded(alertOnErr: boolean) {
  if (!ociUserId.value) return
  modelsLoading.value = true
  try {
    const r: any = await listOpenAiModels({ ociUserId: ociUserId.value })
    modelOptions.value = mapModelOptions(r?.data)

    // 多选在「模型 options 曾为空」时可能被清空，用后端 UI state 再补一次
    if (!modelPick.value?.length && serverUiState.value?.modelPick?.length) {
      modelPick.value = serverUiState.value.modelPick.filter((x: any) => typeof x === 'string') as string[]
    }

    // 防止“已选模型”因 options 刷新而丢失：把已选 value 补进 options（只做展示）
    modelOptions.value = ensureSelectedModelsInOptions(modelOptions.value, [
      ...(modelPick.value || []),
      ...(chatModel.value ? [chatModel.value] : []),
    ])

    if (!chatModel.value && modelOptions.value.length) {
      chatModel.value = modelOptions.value[0].value
    }
    if (!modelOptions.value.length && alertOnErr) {
      message.info('无模型条目或 OCI 返回与预期结构不同，请查看后端日志。')
    }
  } catch {
  } finally {
    modelsLoading.value = false
  }
}

async function sendChatTest() {
  if (!chatApiKey.value || !chatModel.value || !chatUserText.value) return
  chatSending.value = true
  chatAssistantText.value = ''
  chatError.value = ''
  try {
    const model = String(chatModel.value || '')
    const isMultiAgent = model.toLowerCase().includes('multi-agent') || model.toLowerCase().includes('multiagent')

    const payload = isMultiAgent
      ? {
          model,
          input: [
            {
              role: 'user',
              content: [{ type: 'input_text', text: chatUserText.value }],
            },
          ],
          stream: false,
        }
      : {
          model,
          messages: [{ role: 'user', content: chatUserText.value }],
          stream: false,
        }

    const parseAndSet = (raw: string) => {
      let json: any
      try {
        json = raw ? JSON.parse(raw) : {}
      } catch {
        chatAssistantText.value = raw
        return
      }
      if (isMultiAgent) {
        const outText =
          json?.output_text ||
          json?.output?.[0]?.content?.find?.((x: any) => x?.type === 'output_text')?.text ||
          json?.output?.[0]?.content?.find?.((x: any) => x?.type === 'text')?.text
        chatAssistantText.value = typeof outText === 'string' && outText ? outText : JSON.stringify(json, null, 2)
        return
      }
      const c0 = json?.choices?.[0]
      const content = c0?.message?.content
      chatAssistantText.value = typeof content === 'string' ? content : JSON.stringify(json, null, 2)
    }

    // 为避免浏览器环境下的 HTTPS/CORS/网络策略导致失败，统一走同源 /api 代请求（服务端本机访问 127.0.0.1:8080/v1）
    const r: any = await oracleAiChatTest({
      apiKey: chatApiKey.value,
      model,
      input: chatUserText.value,
    })
    const status = r?.data?.status ?? r?.status
    const body = r?.data?.body ?? r?.body ?? ''
    if (typeof status === 'number' && status >= 400) {
      chatError.value = `HTTP ${status}\n${String(body || '')}`
      return
    }
    parseAndSet(String(body || ''))
  } catch (e: any) {
    chatError.value = e?.message || String(e)
  } finally {
    chatSending.value = false
  }
}

function clearChatTest() {
  chatUserText.value = ''
  chatAssistantText.value = ''
  chatError.value = ''
}

async function refreshKeys() {
  if (!ociUserId.value) {
    keys.value = []
    return
  }
  keysLoading.value = true
  try {
    const r: any = await listOracleKeys({ ociUserId: ociUserId.value })
    const raw = r.data
    keys.value = Array.isArray(raw) ? raw : raw?.records || []
  } finally {
    keysLoading.value = false
  }
}

async function loadPortBindings() {
  portBindingsLoading.value = true
  try {
    const r: any = await listOracleAiPortBindings()
    portBindings.value = Array.isArray(r?.data) ? r.data : []
  } finally {
    portBindingsLoading.value = false
  }
}

function nextPortValue() {
  const used = new Set((portBindings.value || []).map((x: any) => Number(x?.port)).filter((x: number) => Number.isFinite(x)))
  for (let p = 30000; p <= 39999; p++) {
    if (!used.has(p)) return p
  }
  return 30000
}

async function openPortModal(row?: any) {
  if (!row) {
    await loadTenants({ silent: true })
  }
  portForm.value = {
    id: row?.id,
    name: row?.name || '',
    port: Number(row?.port || nextPortValue()),
    ociUserId: row?.ociUserId || undefined,
    ociRegion: row?.ociRegion || row?.tenantDefaultRegion || undefined,
    openaiKeyId: row?.openaiKeyId || undefined,
    defaultMaxTokens: row?.defaultMaxTokens ? Number(row.defaultMaxTokens) : null,
    allowedModels: Array.isArray(row?.allowedModels) ? row.allowedModels : [],
    enabled: row?.enabled !== false,
  }
  portModalOpen.value = true
  portKeyOptions.value = []
  portRegionOptions.value = []
  portModelOptions.value = []
  if (portForm.value.ociUserId) {
    const tenantId = portForm.value.ociUserId
    const keyTask = loadPortKeys(tenantId).catch(() => {})
    await loadPortRegions(tenantId, portForm.value.ociRegion)
    await loadPortModels(tenantId, portForm.value.ociRegion)
    await keyTask
  }
}

async function onPortTenantChange() {
  portForm.value.openaiKeyId = undefined
  portForm.value.ociRegion = undefined
  portForm.value.allowedModels = []
  portKeyOptions.value = []
  portRegionOptions.value = []
  portModelOptions.value = []
  if (portForm.value.ociUserId) {
    const tenantId = portForm.value.ociUserId
    const keyTask = loadPortKeys(tenantId).catch(() => {})
    await loadPortRegions(tenantId)
    await loadPortModels(tenantId, portForm.value.ociRegion)
    await keyTask
  }
}

async function onPortRegionChange() {
  portForm.value.allowedModels = []
  portModelOptions.value = []
  if (portForm.value.ociUserId) {
    await loadPortModels(portForm.value.ociUserId, portForm.value.ociRegion, true)
  }
}

async function loadPortRegions(tenantId: string, preferred?: string) {
  portRegionsLoading.value = true
  try {
    const r: any = await listOciRegionOptions(tenantId)
    const rows = Array.isArray(r?.data) ? r.data : []
    const options = rows
      .map((x: any) => ({
        value: String(x.regionId || '').trim(),
        label: x.label || String(x.regionId || '').trim(),
      }))
      .filter((x: any) => x.value)
    const tenantDefault = tenantOptions.value.find((x) => x.value === tenantId)?.ociRegion || ''
    const selected = String(preferred || portForm.value.ociRegion || tenantDefault || '').trim()
    if (selected && !options.some((x: any) => x.value === selected)) {
      options.unshift({ value: selected, label: regionDisplay(selected) })
    }
    portRegionOptions.value = options
    if (!portForm.value.ociRegion) {
      portForm.value.ociRegion = selected || options[0]?.value
    }
  } catch {
    const fallback = String(preferred || tenantOptions.value.find((x) => x.value === tenantId)?.ociRegion || '').trim()
    portRegionOptions.value = fallback ? [{ value: fallback, label: regionDisplay(fallback) }] : []
    if (!portForm.value.ociRegion) {
      portForm.value.ociRegion = fallback || undefined
    }
  } finally {
    portRegionsLoading.value = false
  }
}

async function loadPortKeys(tenantId: string) {
  portKeysLoading.value = true
  try {
    const r: any = await listOracleKeys({ ociUserId: tenantId })
    const raw = Array.isArray(r?.data) ? r.data : r?.data?.records || []
    portKeyOptions.value = raw
      .filter((x: any) => !x.disabled)
      .map((x: any) => ({
        value: x.id,
        label: `${x.name || '未命名'} (${x.keyMasked || 'sk-****'})`,
      }))
    if (!portForm.value.openaiKeyId && portKeyOptions.value.length) {
      portForm.value.openaiKeyId = portKeyOptions.value[0].value
    }
  } catch {
    portKeyOptions.value = []
  } finally {
    portKeysLoading.value = false
  }
}

async function createPortTenantKey() {
  const tenantId = portForm.value.ociUserId
  if (!tenantId) return
  portKeyCreating.value = true
  try {
    const r: any = await createOracleKey({
      ociUserId: tenantId,
      name: portForm.value.name || `port-${portForm.value.port}`,
    })
    const id = r?.data?.id
    newKeyPlain.value = r?.data?.apiKey || ''
    await loadPortKeys(tenantId)
    if (id) {
      portForm.value.openaiKeyId = id
    }
    if (newKeyPlain.value) {
      plainKeyModalOpen.value = true
    }
    message.success('已生成 API Key')
  } catch (e: any) {
    message.error(e?.message || '生成失败')
  } finally {
    portKeyCreating.value = false
  }
}

async function loadPortModels(tenantId: string, region?: string, alertOnErr = false) {
  portModelsLoading.value = true
  try {
    const r: any = await listOpenAiModels({ ociUserId: tenantId, ociRegion: region })
    portModelOptions.value = ensureSelectedModelsInOptions(mapModelOptions(r?.data), portForm.value.allowedModels || [])
    if (!portModelOptions.value.length && alertOnErr) {
      message.info('无模型条目或 OCI 返回与预期结构不同，请查看后端日志。')
    }
  } catch (e: any) {
    if (alertOnErr) {
      message.error(e?.message || '刷新模型失败')
    }
  } finally {
    portModelsLoading.value = false
  }
}

async function revealPortKey(row: any) {
  const id = row?.openaiKeyId
  if (!id) return
  keyViewRow.value = {
    name: row?.keyName || row?.name || `port-${row?.port || ''}`,
    disabled: !row?.enabled,
    createTime: row?.createTime,
    lastUsed: row?.lastUsed,
  }
  keyViewPlain.value = ''
  keyViewError.value = ''
  keyViewPort.value = Number(row?.port || 0) || null
  keyViewOpen.value = true
  keyViewLoading.value = true
  try {
    const r: any = await revealOracleKey({ id })
    keyViewPlain.value = r?.data?.apiKey || ''
    if (!keyViewPlain.value) {
      keyViewError.value = '未返回完整 API Key'
    }
  } catch (e: any) {
    keyViewError.value = e?.message || '无法读取完整 API Key'
  } finally {
    keyViewLoading.value = false
  }
}

async function savePortBindingRow() {
  const f = portForm.value
  if (!f.ociUserId || !f.openaiKeyId) {
    message.warning('请选择租户和 API Key')
    return
  }
  if (!Number.isFinite(Number(f.port)) || Number(f.port) < 30000 || Number(f.port) > 39999) {
    message.warning('端口必须在 30000-39999 之间')
    return
  }
  portSaving.value = true
  try {
    await saveOracleAiPortBinding({
      id: f.id,
      name: f.name,
      port: Math.trunc(Number(f.port)),
      ociUserId: f.ociUserId,
      ociRegion: f.ociRegion,
      openaiKeyId: f.openaiKeyId,
      defaultMaxTokens: f.defaultMaxTokens ? Math.trunc(Number(f.defaultMaxTokens)) : null,
      allowedModels: f.allowedModels || [],
      enabled: f.enabled,
    })
    portModalOpen.value = false
    message.success('已保存，端口已同步')
    await loadPortBindings()
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    portSaving.value = false
  }
}

async function togglePortBinding(row: any, enabled: boolean) {
  if (!row?.id) return
  portSwitchingId.value = row.id
  try {
    await setOracleAiPortBindingEnabled({ id: row.id, enabled })
    message.success(enabled ? '已启用' : '已禁用')
    await loadPortBindings()
  } catch (e: any) {
    message.error(e?.message || '操作失败')
    await loadPortBindings()
  } finally {
    portSwitchingId.value = ''
  }
}

async function removePortBindingRow(row: any) {
  if (!row?.id) return
  await removeOracleAiPortBinding({ id: row.id })
  message.success('已删除')
  await loadPortBindings()
}

function portStatusText(row: any) {
  if (!row?.enabled) return '已禁用'
  if (row?.status === 'listening') return '监听中'
  if (row?.status === 'failed') return '启动失败'
  return row?.status || '未监听'
}

function portStatusColor(row: any) {
  if (!row?.enabled) return 'default'
  if (row?.status === 'listening') return 'green'
  if (row?.status === 'failed') return 'red'
  return 'orange'
}

function openKeyModal() {
  keyName.value = ''
  keyModalOpen.value = true
}

async function submitKey() {
  if (!ociUserId.value) return
  keyCreating.value = true
  try {
    const r: any = await createOracleKey({ ociUserId: ociUserId.value, name: keyName.value || undefined })
    newKeyPlain.value = r.data?.apiKey || ''
    keyModalOpen.value = false
    plainKeyModalOpen.value = true
    message.success('已创建（请立即复制）')
    await refreshKeys()
  } finally {
    keyCreating.value = false
  }
}

function toggleKey(k: any) {
  if (!k?.id) return
  setOracleKeyDisabled({ id: k.id, disabled: !k.disabled })
    .then(() => {
      message.success('已更新')
      refreshKeys()
    })
    .catch(() => {})
}
function removeK(k: any) {
  if (!k?.id) return
  removeOracleKey({ id: k.id })
    .then(() => {
      message.success('已删除')
      refreshKeys()
    })
    .catch(() => {})
}

async function viewKey(k: any) {
  if (!k?.id) return
  keyViewRow.value = k
  keyViewPlain.value = ''
  keyViewError.value = ''
  keyViewPort.value = null
  keyViewOpen.value = true
  keyViewLoading.value = true
  try {
    const r: any = await revealOracleKey({ id: k.id })
    keyViewPlain.value = r?.data?.apiKey || ''
    if (!keyViewPlain.value) {
      keyViewError.value = '未返回密钥内容'
    }
  } catch (e: any) {
    keyViewError.value = e?.message || e?.data?.message || '无法读取完整密钥'
  } finally {
    keyViewLoading.value = false
  }
}
</script>

<style scoped>
.oracle-ai-page {
  max-width: 1200px;
  margin: 0 auto;
}
.sub {
  line-height: 1.6;
  color: var(--text-sub, #666);
  margin: 0 0 8px 0;
}
.sub code {
  font-size: 12px;
  padding: 0 4px;
}
.sub-muted { color: var(--text-sub, #666); opacity: 0.9; }
.top-line { display: flex; justify-content: space-between; gap: 12px; align-items: center; flex-wrap: wrap; }
.proxy-switch { margin-left: auto; }
.sub-bottom {
  margin-bottom: 24px;
}
.key-toolbar {
  /* 12px - 16px：按钮行与表格之间的安全间距 */
  margin-bottom: 14px;
}
.key-toolbar :deep(.ant-btn) {
  max-width: 100%;
}
.mb-card { margin-bottom: 16px; }
.mt-card { margin-top: 8px; }
.ma-hint {
  display: block;
  font-size: 12px;
  line-height: 1.5;
  margin: 0 0 8px 0;
}
.ma-hint code { font-size: 11px; }
.mb-alert { margin: 0 0 8px; }
.max-token-form :deep(.ant-form-item) { margin-bottom: 6px; }
.max-token-input { width: 220px; max-width: calc(100vw - 96px); }
.max-token-help { margin: 0; }
.port-table :deep(.ant-table-cell) {
  vertical-align: middle;
}
.port-table :deep(.ant-table),
.port-table :deep(.ant-table-container),
.port-table :deep(.ant-table-thead > tr > th),
.port-table :deep(.ant-table-tbody > tr > td) {
  background: transparent;
}
.port-actions {
  white-space: nowrap;
}
.port-actions :deep(.ant-btn-link) {
  padding-left: 4px;
  padding-right: 4px;
}
.model-summary {
  display: inline-block;
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.port-mobile-list {
  display: grid;
  gap: 10px;
}
.port-card-m {
  border: 1px solid var(--border, rgba(255, 255, 255, 0.06));
  border-radius: 8px;
  padding: 10px;
  background: var(--bg-card, rgba(30, 41, 59, 0.4));
}
.port-card-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 10px;
}
.port-card-title {
  font-weight: 600;
  margin-bottom: 4px;
}
.port-card-head code {
  display: inline-block;
  max-width: min(72vw, 420px);
  overflow-wrap: anywhere;
  font-size: 12px;
}
.port-card-grid {
  display: grid;
  grid-template-columns: 74px minmax(0, 1fr);
  gap: 6px 10px;
  align-items: center;
  font-size: 13px;
}
.port-card-grid > span {
  color: var(--text-sub, #666);
}
.port-card-grid > b,
.port-card-grid > code {
  min-width: 0;
  overflow-wrap: anywhere;
  font-weight: 500;
}
.port-card-actions {
  margin-top: 10px;
}
.code-wrap {
  word-break: break-all;
  user-select: all;
}
.key-plain { word-break: break-all; font-size: 13px; }
@media (min-width: 768px) {
  .key-view-modal :deep(.ant-descriptions-item-content) {
    overflow-x: auto;
  }
  .key-view-modal :deep(.ant-typography),
  .key-view-modal :deep(.key-plain) {
    white-space: nowrap;
    word-break: normal;
  }
}
.key-masked { font-size: 12px; user-select: none; }
.key-card-m { padding: 8px; border: 1px solid var(--border, #e8e8e8); border-radius: 6px; margin-bottom: 8px; }
.key-card-m .p { font-size: 12px; }
.chat-box {
  margin-top: 12px;
  border: 1px solid var(--border, #e8e8e8);
  border-radius: 8px;
  padding: 10px;
}
.chat-error { border-color: #ffccc7; }
.chat-label { font-weight: 600; margin-bottom: 6px; }
.chat-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.55;
}
@media (max-width: 767px) {
  .oracle-ai-page {
    max-width: 100%;
  }
  .key-toolbar {
    align-items: flex-start;
  }
  .key-toolbar :deep(.ant-col) {
    max-width: 100%;
  }
  .key-toolbar .sub-muted {
    display: inline-block;
    line-height: 1.5;
  }
  .sub-bottom {
    font-size: 12px;
  }
  .port-model-refresh {
    margin-top: 8px;
  }
}
</style>
