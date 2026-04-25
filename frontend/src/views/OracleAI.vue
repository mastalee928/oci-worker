<template>
  <div class="oracle-ai-page">
    <a-card class="mb-card" title="Oracle 生成式 AI 网关" :bordered="false">
      <p class="sub">
        使用已保存租户的 OCI 配置，经 OpenAI 兼容端代理至 OCI
        <code>inference.generativeai</code>。New API 将 Base 设为
        <strong>:{{ openaiPort }}/v1</strong>，请求头
        <code>Authorization: Bearer &lt;下方 sk&gt;</code>。未带
        <code>max_tokens</code> 时由网关补默认 4000；<code>force_non_stream: true</code> 时强制非流式。
      </p>
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
      <a-col :xs="24" :lg="12">
        <a-card title="Base URL（给 New API）" :bordered="false">
          <p>专属端口：<code>:{{ openaiPort }}</code></p>
          <a-typography-paragraph copyable>
            <code class="code-wrap">{{ publicBaseUrl }}</code>
          </a-typography-paragraph>
        </a-card>
      </a-col>
    </a-row>

    <a-card title="API 密钥" :bordered="false" class="mt-card">
      <a-space class="mb-row" wrap>
        <a-button type="primary" :disabled="!ociUserId" @click="openKeyModal">生成新密钥</a-button>
        <a-button :disabled="!ociUserId" @click="refreshKeys">刷新</a-button>
      </a-space>
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
        message="密钥只显示这一遍。关闭后只能重新生成新的。"
        show-icon
      />
      <a-typography-paragraph copyable>
        <code class="key-plain">{{ newKeyPlain }}</code>
      </a-typography-paragraph>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'
import { getTenantList } from '../api/tenant'
import {
  getOracleAiGateway,
  listOracleKeys,
  createOracleKey,
  setOracleKeyDisabled,
  removeOracleKey,
  listOpenAiModels,
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

function checkM() {
  isMobile.value = window.innerWidth < 768
}

onMounted(() => {
  checkM()
  window.addEventListener('resize', checkM)
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
  } catch (e: any) {
    message.error(e?.message || '加载租户失败')
  } finally {
    tenantsLoading.value = false
  }
}

function onTenantChange() {
  modelOptions.value = []
  modelPick.value = []
  loadModelsIfNeeded(false)
  refreshKeys()
}

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
        return { value: id, label: label || id }
      })
      .filter((x) => x) as any
    if (!modelOptions.value.length && alertOnErr) {
      message.info('无模型条目或 OCI 返回与预期结构不同，请查看后端日志。')
    }
  } catch {
  } finally {
    modelsLoading.value = false
  }
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
.mb-card { margin-bottom: 16px; }
.mt-card { margin-top: 8px; }
.mb-alert { margin: 0 0 8px; }
.mb-row { margin-bottom: 12px; }
.code-wrap {
  word-break: break-all;
  user-select: all;
}
.key-plain { word-break: break-all; font-size: 13px; }
.key-card-m { padding: 8px; border: 1px solid var(--border, #e8e8e8); border-radius: 6px; margin-bottom: 8px; }
.key-card-m .p { font-size: 12px; }
</style>
