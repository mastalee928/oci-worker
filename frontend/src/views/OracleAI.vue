<template>
  <div class="oracle-ai-page">
    <a-card class="mb-card" title="Oracle 生成式 AI 网关" :bordered="false">
      <a-space direction="vertical" style="width: 100%">
        <div class="sub">
          Base 示例：
          <code>http://&lt;本机或域名&gt;:{{ openaiPort }}/v1</code>
          （Header：
          <code>Authorization: Bearer sk-...</code>
          ）
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

    <a-row :gutter="[16, 16]">
      <a-col :xs="24" :lg="12">
        <a-card title="租户与模型" :bordered="false" :loading="tenantsLoading">
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
              />
            </a-form-item>
            <a-button type="primary" :loading="modelsLoading" :disabled="!ociUserId" @click="() => loadModelsIfNeeded(true)">
              刷新模型列表
            </a-button>
          </a-form>
        </a-card>
      </a-col>
      <a-col :xs="24" :lg="12" />
    </a-row>

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
          <template v-else-if="column.key === 'a'">
            <a-space>
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
            <b>{{ k.name || '未命名' }}</b> <code class="p">{{ k.keyPrefix }}…</code>
          </div>
          <a-button size="small" @click="toggleKey(k)">{{ k.disabled ? '启用' : '禁用' }}</a-button>
          <a-popconfirm title="确定删除？" @confirm="removeK(k)">
            <a-button size="small" danger>删除</a-button>
          </a-popconfirm>
        </div>
      </template>
    </a-card>

    <a-modal v-model:open="keyModalOpen" title="新密钥" :confirm-loading="keyCreating" @ok="submitKey">
      <a-form layout="vertical">
        <a-form-item label="备注名（可选）">
          <a-input v-model:value="keyName" />
        </a-form-item>
      </a-form>
    </a-modal>
    <a-modal v-model:open="plainKeyModalOpen" title="请立即复制保存" :footer="null" :width="600">
      <a-alert
        class="mb-alert"
        type="error"
        message="出于安全考虑，系统只保存密钥哈希，无法再次展示明文。关闭后如需更换，请生成新密钥。"
        show-icon
      />
      <a-typography-paragraph copyable>
        <code class="key-plain">{{ newKeyPlain }}</code>
      </a-typography-paragraph>
    </a-modal>

    <a-card title="对话测试（浏览器直连 /v1）" :bordered="false" class="mt-card">
      <a-alert
        class="mb-alert"
        type="info"
        show-icon
        message="该测试会从浏览器直接调用本机 OpenAI 兼容端口（如 :8080/v1）。用于快速验证网关是否能返回内容，避免 New API/IDE 侧差异干扰。"
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
            placeholder="先在左侧拉取模型列表"
            show-search
            :filter-option="filterModel"
            allow-clear
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

    <a-divider />
    <div class="sub sub-bottom">
      说明：未带 <code>max_tokens</code> 时网关会补默认 4000；请求体里 <code>force_non_stream: true</code> 会强制非流式。
      Multi-Agent 在网关内会走 <code>/v1/responses</code>；新库若缺列需执行
      <code>upgrade-oci-generative-tenant-columns.sql</code>。
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getTenantList } from '../api/tenant'
import {
  getOracleAiGateway,
  listOracleKeys,
  createOracleKey,
  setOracleKeyDisabled,
  removeOracleKey,
  listOpenAiModels,
  oracleAiChatTest,
} from '../api/oracleAi'

const tenantsLoading = ref(false)
const keysLoading = ref(false)
const modelsLoading = ref(false)
const keyCreating = ref(false)
const isMobile = ref(false)
const openaiPort = ref(8080)
const openaiPath = '/v1'
const ociUserId = ref<string | undefined>(undefined)
const tenantOptions = ref<{ label: string; value: string; ociRegion: string }[]>([])
const keys = ref<any[]>([])
const modelPick = ref<string[]>([])
const modelOptions = ref<{ label: string; value: string }[]>([])

const keyModalOpen = ref(false)
const plainKeyModalOpen = ref(false)
const newKeyPlain = ref('')
const keyName = ref('')
const baseHint = ref('')

const chatApiKey = ref('')
const chatModel = ref<string | undefined>(undefined)
const chatUserText = ref('')
const chatAssistantText = ref('')
const chatError = ref('')
const chatSending = ref(false)

const keyColumns = [
  { title: '备注', dataIndex: 'name', key: 'name' },
  { title: '前缀', dataIndex: 'keyPrefix', key: 'keyPrefix' },
  { title: '状态', key: 'dis' },
  { title: '创建', dataIndex: 'createTime', key: 'createTime' },
  { title: '最后使用', dataIndex: 'lastUsed', key: 'lastUsed' },
  { title: '操作', key: 'a', width: 200 },
] as any

const selectedRegion = computed(() => {
  return tenantOptions.value.find((x) => x.value === ociUserId.value)?.ociRegion
})

const publicBaseUrl = computed(() => {
  if (typeof window === 'undefined') {
    return `https://<主机名>:${openaiPort.value}${openaiPath}`
  }
  const p = location.protocol
  const h = location.hostname
  return `${p}//${h}:${openaiPort.value}${openaiPath}`
})

const LS_KEY = 'ociworker.oracleAi.state.v1'
const LS_CHAT_KEY = 'ociworker.oracleAi.chatTest.v1'
const restoring = ref(false)

function loadPersistedState() {
  if (typeof window === 'undefined') return
  try {
    const raw = localStorage.getItem(LS_KEY)
    if (!raw) return
    const s = JSON.parse(raw || '{}') || {}
    restoring.value = true
    if (typeof s.ociUserId === 'string' && s.ociUserId) {
      ociUserId.value = s.ociUserId
    }
    if (Array.isArray(s.modelPick)) {
      modelPick.value = s.modelPick.filter((x: any) => typeof x === 'string') as string[]
    }
  } catch {
  } finally {
    // allow onTenantChange() to run for user-driven changes only
    setTimeout(() => {
      restoring.value = false
    }, 0)
  }
}

function persistState() {
  if (typeof window === 'undefined') return
  try {
    localStorage.setItem(
      LS_KEY,
      JSON.stringify({
        ociUserId: ociUserId.value || '',
        modelPick: modelPick.value || [],
      }),
    )
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
  loadGateway()
  loadTenants()
})

onUnmounted(() => {
  window.removeEventListener('resize', checkM)
})

async function loadGateway() {
  try {
    const r: any = await getOracleAiGateway()
    const p = r?.data?.openaiApiPort
    if (p != null) openaiPort.value = p
    baseHint.value = r?.data?.baseUrlExample
      ? `Base 示例: ${r.data.baseUrlExample}`
      : `对外访问需在防火墙放行 TCP ${openaiPort.value}。`
  } catch {
    baseHint.value = `请放行 TCP 端口 ${openaiPort.value} 供 New API/客户端访问。`
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

async function loadTenants() {
  tenantsLoading.value = true
  try {
    const res: any = await getTenantList({ current: 1, size: 5000, keyword: '' })
    const rec = res.data?.records || []
    tenantOptions.value = rec.map((t: any) => ({
      label: `${t.username} (${t.ociRegion || '?'})`,
      value: t.id,
      ociRegion: t.ociRegion,
    }))
    // 恢复时如果租户仍存在，则自动拉取模型/密钥
    if (ociUserId.value && tenantOptions.value.some((x) => x.value === ociUserId.value)) {
      loadModelsIfNeeded(false)
      refreshKeys()
    }
  } catch (e: any) {
    message.error(e?.message || '加载租户失败')
  } finally {
    tenantsLoading.value = false
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
  () => persistState(),
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
    const d = r?.data
    let list: any[] = []
    if (d?.data && Array.isArray(d.data)) {
      list = d.data
    } else if (Array.isArray(d)) {
      list = d
    }
    modelOptions.value = list
      .map((m) => {
        const id = String(m?.id || m || '').trim()
        const label = String(m?.displayName || m?.name || m?.id || m || '').trim()
        if (!id) return null
        const note = String(m?.ociworkerNote || '').trim()
        const finalLabel = `${label || id}`
        return { value: id, label: finalLabel, title: note || finalLabel }
      })
      .filter((x) => x) as any
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

    // 真正“浏览器直连 /v1”：对普通模型走 /chat/completions；对 Multi-Agent 走 /responses
    const base = publicBaseUrl.value.replace(/\/+$/, '')
    const url = `${base}${isMultiAgent ? '/responses' : '/chat/completions'}`
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

    const resp = await fetch(url, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        authorization: `Bearer ${chatApiKey.value}`,
      },
      body: JSON.stringify(payload),
    })

    const text = await resp.text()
    if (!resp.ok) {
      chatError.value = `HTTP ${resp.status}\n${text}`
      return
    }

    let json: any
    try {
      json = text ? JSON.parse(text) : {}
    } catch {
      chatAssistantText.value = text
      return
    }

    if (isMultiAgent) {
      // 尝试从 responses 结构里提取文本；否则回退展示原 JSON
      const outText =
        json?.output_text ||
        json?.output?.[0]?.content?.find?.((x: any) => x?.type === 'output_text')?.text ||
        json?.output?.[0]?.content?.find?.((x: any) => x?.type === 'text')?.text
      if (typeof outText === 'string' && outText) {
        chatAssistantText.value = outText
      } else {
        chatAssistantText.value = JSON.stringify(json, null, 2)
      }
      return
    }

    const c0 = json?.choices?.[0]
    const content = c0?.message?.content
    chatAssistantText.value = typeof content === 'string' ? content : JSON.stringify(json, null, 2)
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
.sub-bottom {
  margin-bottom: 24px;
}
.key-toolbar {
  /* 12px - 16px：按钮行与表格之间的安全间距 */
  margin-bottom: 14px;
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
.code-wrap {
  word-break: break-all;
  user-select: all;
}
.key-plain { word-break: break-all; font-size: 13px; }
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
</style>
