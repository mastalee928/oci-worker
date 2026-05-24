<template>
  <div class="cf-account-panel">
    <a-tabs v-model:active-key="accountTab">
      <a-tab-pane key="tunnel" tab="Tunnel 连接器">
        <div class="cf-toolbar">
          <a-space wrap>
            <a-button type="primary" :disabled="!cfConfigured" @click="openCreateTunnel">
              <template #icon><PlusOutlined /></template>
              创建 Tunnel
            </a-button>
            <a-button :loading="tunnelLoading" :disabled="!cfConfigured" @click="loadTunnels">
              <template #icon><ReloadOutlined /></template>
              刷新
            </a-button>
          </a-space>
        </div>
        <a-table
          v-if="!isMobile"
          :columns="tunnelColumns"
          :data-source="tunnels"
          :loading="tunnelLoading"
          row-key="id"
          size="middle"
          :scroll="{ x: 900 }"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'status'">
              <a-tag :color="statusColor(record.status)">{{ record.status || 'unknown' }}</a-tag>
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space wrap>
                <a-button type="link" size="small" @click="showToken(record)">Token</a-button>
                <a-button type="link" size="small" @click="showConnections(record)">连接</a-button>
                <a-button type="link" danger size="small" @click="openDeleteTunnel(record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
        <a-spin v-else :spinning="tunnelLoading">
          <a-empty v-if="!tunnelLoading && tunnels.length === 0" description="暂无 Tunnel" />
          <div v-for="item in tunnels" :key="item.id" class="mobile-card">
            <div class="mobile-card-header">
              <span class="mobile-card-title">{{ item.name }}</span>
              <a-tag :color="statusColor(item.status)">{{ item.status }}</a-tag>
            </div>
            <div class="mobile-card-body">
              <div class="mobile-card-row"><span class="label">ID</span><span class="value">{{ item.id }}</span></div>
              <div v-if="item.createdAt" class="mobile-card-row"><span class="label">创建</span><span class="value">{{ item.createdAt }}</span></div>
            </div>
            <a-space wrap class="mobile-card-actions">
              <a-button size="small" @click="showToken(item)">Token</a-button>
              <a-button size="small" @click="showConnections(item)">连接</a-button>
              <a-button size="small" danger @click="openDeleteTunnel(item)">删除</a-button>
            </a-space>
          </div>
        </a-spin>
      </a-tab-pane>

      <a-tab-pane key="ipaccess" tab="IP 访问规则">
        <div class="cf-toolbar">
          <a-space wrap>
            <a-button type="primary" :disabled="!cfConfigured" @click="openCreateIpRule">
              <template #icon><PlusOutlined /></template>
              添加规则
            </a-button>
            <a-button :loading="ipRulesLoading" :disabled="!cfConfigured" @click="loadIpRules">
              <template #icon><ReloadOutlined /></template>
              刷新
            </a-button>
          </a-space>
        </div>
        <a-table
          v-if="!isMobile"
          :columns="ipRuleColumns"
          :data-source="ipRules"
          :loading="ipRulesLoading"
          row-key="id"
          size="middle"
          :scroll="{ x: 960 }"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'target'">
              {{ targetLabel(record.target) }}
            </template>
            <template v-else-if="column.key === 'mode'">
              <a-tag :color="modeColor(record.mode)">{{ modeLabel(record.mode) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'scope'">
              账户中的所有网站
            </template>
            <template v-else-if="column.key === 'action'">
              <a-popconfirm title="确定删除此 IP 访问规则？" @confirm="handleDeleteIpRule(record.id)">
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
            </template>
          </template>
        </a-table>
        <a-spin v-else :spinning="ipRulesLoading">
          <a-empty v-if="!ipRulesLoading && ipRules.length === 0" description="暂无 IP 访问规则" />
          <div v-for="item in ipRules" :key="item.id" class="mobile-card">
            <div class="mobile-card-header">
              <span class="mobile-card-title">{{ item.value }}</span>
              <a-tag :color="modeColor(item.mode)">{{ modeLabel(item.mode) }}</a-tag>
            </div>
            <div class="mobile-card-body">
              <div class="mobile-card-row"><span class="label">类型</span><span class="value">{{ targetLabel(item.target) }}</span></div>
              <div class="mobile-card-row"><span class="label">范围</span><span class="value">账户下所有网站</span></div>
              <div v-if="item.notes" class="mobile-card-row"><span class="label">注释</span><span class="value">{{ item.notes }}</span></div>
              <div v-if="item.createdOn" class="mobile-card-row"><span class="label">创建</span><span class="value">{{ item.createdOn }}</span></div>
            </div>
            <a-popconfirm title="确定删除此 IP 访问规则？" @confirm="handleDeleteIpRule(item.id)">
              <a-button size="small" danger>删除</a-button>
            </a-popconfirm>
          </div>
        </a-spin>
        <p class="cf-hint">账户级 IP 访问规则，对账户下所有 Zone 生效。复杂条件请使用「域名 → 安全性」防火墙规则。</p>
      </a-tab-pane>

      <a-tab-pane key="workers" tab="Workers 脚本">
        <div class="cf-toolbar">
          <a-button :loading="scriptsLoading" :disabled="!cfConfigured" @click="loadScripts">刷新</a-button>
        </div>
        <a-table
          v-if="!isMobile"
          :columns="scriptColumns"
          :data-source="scripts"
          :loading="scriptsLoading"
          row-key="id"
          size="middle"
        />
        <a-spin v-else :spinning="scriptsLoading">
          <a-empty v-if="!scriptsLoading && scripts.length === 0" description="暂无 Workers 脚本" />
          <div v-for="item in scripts" :key="item.id" class="mobile-card">
            <div class="mobile-card-header">
              <span class="mobile-card-title">{{ item.id }}</span>
            </div>
            <div class="mobile-card-body">
              <div v-if="item.createdOn" class="mobile-card-row"><span class="label">创建</span><span class="value">{{ item.createdOn }}</span></div>
              <div v-if="item.modifiedOn" class="mobile-card-row"><span class="label">修改</span><span class="value">{{ item.modifiedOn }}</span></div>
            </div>
          </div>
        </a-spin>
        <p class="cf-hint">账户级 Workers 脚本列表。路由绑定请在「域名 → Workers 路由」中配置。</p>
      </a-tab-pane>
    </a-tabs>

    <a-modal
      v-model:open="ipCreateVisible"
      title="添加 IP 访问规则"
      :confirm-loading="ipCreateLoading"
      width="520px"
      @ok="submitCreateIpRule"
    >
      <a-form layout="vertical">
        <a-form-item label="匹配类型" required>
          <a-select v-model:value="ipForm.target" :options="ipTargetOptions" />
        </a-form-item>
        <a-form-item label="匹配值" required>
          <a-input v-model:value="ipForm.value" :placeholder="ipValuePlaceholder" allow-clear />
        </a-form-item>
        <a-form-item label="动作" required>
          <a-select v-model:value="ipForm.mode" :options="ipModeOptions" />
        </a-form-item>
        <a-form-item label="注释">
          <a-input v-model:value="ipForm.notes" placeholder="可选备注" allow-clear />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="createVisible"
      title="创建 Cloudflare Tunnel"
      :confirm-loading="createLoading"
      @ok="submitCreateTunnel"
    >
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="createName" placeholder="如 production-web" @pressEnter="submitCreateTunnel" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="tokenVisible"
      :title="tokenModalTitle"
      :footer="null"
      :width="isMobile ? 'calc(100vw - 32px)' : 720"
      @after-open-change="onTokenModalOpenChange"
    >
      <a-alert type="warning" show-icon class="cf-token-alert">
        <template #message>Token 等同密钥，请勿泄露。连接成功后还需在 Cloudflare 配置 Public Hostname。</template>
      </a-alert>

      <a-form layout="vertical" class="cf-token-form">
        <a-form-item label="运行 Token">
          <a-textarea :value="tokenText" :rows="3" readonly class="cf-token-text" />
          <a-button type="link" size="small" class="cf-copy-link" @click="copyText(tokenText, 'Token')">
            复制 Token
          </a-button>
        </a-form-item>

        <a-divider orientation="left">SSH 安装命令生成</a-divider>

        <a-row :gutter="12">
          <a-col :xs="24" :sm="8">
            <a-form-item label="CPU 架构" required>
              <a-select
                v-model:value="installArch"
                placeholder="请选择架构"
                :options="tunnelArchOptions"
                allow-clear
              />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="8">
            <a-form-item label="操作系统" required>
              <a-select
                v-model:value="installOs"
                placeholder="请选择系统"
                :options="tunnelOsOptions"
                allow-clear
              />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="8">
            <a-form-item label="连接协议" required>
              <a-select
                v-model:value="installProtocol"
                placeholder="请选择协议"
                :options="tunnelProtocolOptions"
                allow-clear
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-empty
          v-if="!installScriptReady"
          description="请先选择架构、系统与连接协议，再生成可粘贴的 SSH 命令"
        />
        <template v-else>
          <a-form-item label="粘贴到 SSH 终端执行">
            <a-textarea :value="installScript" :rows="14" readonly class="cf-install-script" />
          </a-form-item>
          <a-space wrap>
            <a-button type="primary" @click="copyText(installScript, '安装命令')">
              复制完整命令
            </a-button>
          </a-space>
        </template>
      </a-form>
    </a-modal>

    <a-drawer v-model:open="connVisible" :title="connTitle" :width="isMobile ? '100%' : 480">
      <a-spin :spinning="connLoading">
        <a-empty v-if="!connLoading && connections.length === 0" description="暂无活跃连接" />
        <a-list v-else :data-source="connections" item-layout="vertical">
          <template #renderItem="{ item }">
            <a-list-item>
              <a-list-item-meta
                :title="connItemTitle(item)"
                :description="connDescription(item)"
              />
            </a-list-item>
          </template>
        </a-list>
      </a-spin>
    </a-drawer>

    <a-modal
      v-model:open="deleteModalVisible"
      title="安全验证 — 删除 Tunnel"
      :width="400"
      :confirm-loading="deleteVerifyLoading"
      ok-text="确认删除"
      :ok-button-props="{ danger: true }"
      @ok="confirmDeleteTunnel"
    >
      <a-alert type="error" show-icon style="margin-bottom: 16px">
        <template #message>删除 Tunnel 不可恢复，验证码已发送至 Telegram</template>
      </a-alert>
      <p v-if="deleteTarget" class="cf-delete-target">将删除：<b>{{ deleteTarget.name }}</b></p>
      <a-input v-model:value="deleteVerifyCode" placeholder="请输入6位验证码" size="large" :maxlength="6" allow-clear />
      <div class="cf-verify-footer">
        <span>验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="verifySending" @click="sendDeleteCode">重新发送</a-button>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { useIsMobile } from '../../composables/useIsMobile'
import {
  listCfTunnels,
  createCfTunnel,
  deleteCfTunnel,
  getCfTunnelToken,
  listCfTunnelConnections,
  listCfWorkerScripts,
  listCfIpAccessRules,
  createCfIpAccessRule,
  deleteCfIpAccessRule,
} from '../../api/cloudflare'
import { sendVerifyCode } from '../../api/system'
import {
  tunnelArchOptions,
  tunnelOsOptions,
  tunnelProtocolOptions,
  buildTunnelInstallScript,
  canBuildTunnelInstallScript,
  type TunnelInstallArch,
  type TunnelInstallOs,
  type TunnelInstallProtocol,
} from './cfTunnelInstallScript'

defineProps<{ cfConfigured: boolean }>()

const accountTab = ref('tunnel')
const { isMobile } = useIsMobile()

const tunnelLoading = ref(false)
const tunnels = ref<any[]>([])
const createVisible = ref(false)
const createLoading = ref(false)
const createName = ref('')
const tokenVisible = ref(false)
const tokenText = ref('')
const tokenTunnelName = ref('')
const installArch = ref<TunnelInstallArch | undefined>(undefined)
const installOs = ref<TunnelInstallOs | undefined>(undefined)
const installProtocol = ref<TunnelInstallProtocol | undefined>(undefined)

const tokenModalTitle = computed(() =>
  tokenTunnelName.value ? `Tunnel · ${tokenTunnelName.value}` : 'Tunnel 运行 Token')

const installScriptReady = computed(() =>
  canBuildTunnelInstallScript(installArch.value, installOs.value, installProtocol.value, tokenText.value))

const installScript = computed(() => {
  if (!installScriptReady.value) return ''
  return buildTunnelInstallScript({
    arch: installArch.value!,
    os: installOs.value!,
    protocol: installProtocol.value!,
    token: tokenText.value,
    tunnelName: tokenTunnelName.value || undefined,
  })
})

function resetInstallOptions() {
  installArch.value = undefined
  installOs.value = undefined
  installProtocol.value = undefined
}

function openTokenModal(name: string, token: string) {
  tokenTunnelName.value = name
  tokenText.value = token
  resetInstallOptions()
  tokenVisible.value = true
}

function onTokenModalOpenChange(open: boolean) {
  if (!open) resetInstallOptions()
}

async function copyText(text: string, label: string) {
  if (!text.trim()) {
    message.warning(`${label} 为空`)
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    message.success(`${label} 已复制`)
  } catch {
    message.error('复制失败，请手动选择文本')
  }
}
const connVisible = ref(false)
const connLoading = ref(false)
const connTitle = ref('连接详情')
const connections = ref<any[]>([])

const scriptsLoading = ref(false)
const scripts = ref<any[]>([])

const ipRulesLoading = ref(false)
const ipRules = ref<any[]>([])
const ipCreateVisible = ref(false)
const ipCreateLoading = ref(false)
const ipForm = reactive({
  target: 'ip' as 'ip' | 'ip6' | 'ip_range' | 'country' | 'asn',
  value: '',
  mode: 'block' as 'block' | 'challenge' | 'js_challenge' | 'managed_challenge' | 'whitelist',
  notes: '',
})

const ipTargetOptions = [
  { value: 'ip', label: 'IP 地址' },
  { value: 'ip6', label: 'IPv6 地址' },
  { value: 'ip_range', label: 'IP 网段' },
  { value: 'country', label: '国家/地区' },
  { value: 'asn', label: 'ASN' },
]

const ipModeOptions = [
  { value: 'block', label: '阻止 (block)' },
  { value: 'challenge', label: '质询 (challenge)' },
  { value: 'js_challenge', label: 'JS 质询' },
  { value: 'managed_challenge', label: '托管质询' },
  { value: 'whitelist', label: '白名单 (whitelist)' },
]

const ipValuePlaceholder = computed(() => {
  switch (ipForm.target) {
    case 'ip': return '如 203.0.113.1'
    case 'ip6': return '如 2001:db8::1'
    case 'ip_range': return '如 203.0.113.0/24'
    case 'country': return '两位国家码，如 CN'
    case 'asn': return '如 13335'
    default: return ''
  }
})

const ipRuleColumns = [
  { title: '匹配值', dataIndex: 'value', ellipsis: true, width: 160 },
  { title: '类型', key: 'target', width: 100 },
  { title: '适用于', key: 'scope', width: 140 },
  { title: '注释', dataIndex: 'notes', ellipsis: true },
  { title: '动作', key: 'mode', width: 100 },
  { title: '创建时间', dataIndex: 'createdOn', width: 180 },
  { title: '操作', key: 'action', width: 80 },
]

function targetLabel(t?: string) {
  return ipTargetOptions.find(o => o.value === t)?.label || t || '—'
}

function modeLabel(m?: string) {
  return ipModeOptions.find(o => o.value === m)?.label?.replace(/\s*\([^)]*\)/, '') || m || '—'
}

function modeColor(m?: string) {
  if (m === 'whitelist') return 'success'
  if (m === 'block') return 'error'
  return 'warning'
}

async function loadIpRules() {
  ipRulesLoading.value = true
  try {
    const res = await listCfIpAccessRules()
    ipRules.value = res.data || []
  } finally {
    ipRulesLoading.value = false
  }
}

function openCreateIpRule() {
  ipForm.target = 'ip'
  ipForm.value = ''
  ipForm.mode = 'block'
  ipForm.notes = ''
  ipCreateVisible.value = true
}

async function submitCreateIpRule() {
  if (!ipForm.value.trim()) {
    message.warning('请填写匹配值')
    return
  }
  ipCreateLoading.value = true
  try {
    await createCfIpAccessRule({
      target: ipForm.target,
      value: ipForm.value.trim(),
      mode: ipForm.mode,
      notes: ipForm.notes.trim() || undefined,
    })
    message.success('规则已创建')
    ipCreateVisible.value = false
    await loadIpRules()
  } finally {
    ipCreateLoading.value = false
  }
}

async function handleDeleteIpRule(ruleId: string) {
  await deleteCfIpAccessRule({ ruleId })
  message.success('已删除')
  await loadIpRules()
}

watch(accountTab, tab => {
  if (tab === 'ipaccess') loadIpRules()
  if (tab === 'workers' && scripts.value.length === 0) loadScripts()
})

const deleteModalVisible = ref(false)
const deleteVerifyLoading = ref(false)
const deleteVerifyCode = ref('')
const verifySending = ref(false)
const deleteTarget = ref<{ id: string; name: string } | null>(null)

const tunnelColumns = [
  { title: '名称', dataIndex: 'name', width: 160 },
  { title: 'ID', dataIndex: 'id', ellipsis: true },
  { title: '状态', key: 'status', width: 100 },
  { title: '创建时间', dataIndex: 'createdAt', width: 180 },
  { title: '操作', key: 'action', width: 220 },
]

const scriptColumns = [
  { title: '脚本名', dataIndex: 'id' },
  { title: '创建', dataIndex: 'createdOn', width: 180 },
  { title: '修改', dataIndex: 'modifiedOn', width: 180 },
]

function statusColor(s: string) {
  switch (s) {
    case 'healthy': return 'success'
    case 'degraded': return 'warning'
    case 'down': return 'error'
    default: return 'processing'
  }
}

function connDescription(item: {
  uuid?: string
  originIp?: string
  clientVersion?: string
  openedAt?: string
  arch?: string
  isPendingReconnect?: boolean
}) {
  const parts = [
    item.uuid ? `UUID: ${item.uuid}` : '',
    item.originIp ? `源 IP: ${item.originIp}` : '',
    item.clientVersion ? `cloudflared ${item.clientVersion}` : '',
    item.arch ? item.arch : '',
    item.openedAt ? `连接于 ${item.openedAt}` : '',
    item.isPendingReconnect ? '待重连' : '',
  ].filter(Boolean)
  return parts.join(' · ') || '—'
}

function connItemTitle(item: { coloName?: string }) {
  return item.coloName ? `数据中心 ${item.coloName}` : '—'
}

async function loadTunnels() {
  tunnelLoading.value = true
  try {
    const res = await listCfTunnels()
    tunnels.value = res.data || []
  } finally {
    tunnelLoading.value = false
  }
}

async function loadScripts() {
  scriptsLoading.value = true
  try {
    const res = await listCfWorkerScripts()
    scripts.value = res.data || []
  } finally {
    scriptsLoading.value = false
  }
}

function openCreateTunnel() {
  createName.value = ''
  createVisible.value = true
}

async function submitCreateTunnel() {
  const name = createName.value.trim()
  if (!name) {
    message.warning('请输入 Tunnel 名称')
    return
  }
  createLoading.value = true
  try {
    const res = await createCfTunnel({ name })
    message.success('Tunnel 已创建')
    createVisible.value = false
    if (res.data?.token) {
      openTokenModal(name, res.data.token)
    }
    await loadTunnels()
  } finally {
    createLoading.value = false
  }
}

async function sendDeleteCode() {
  verifySending.value = true
  try {
    await sendVerifyCode('cfTunnelDelete')
    message.success('验证码已发送')
  } finally {
    verifySending.value = false
  }
}

async function openDeleteTunnel(record: { id: string; name: string }) {
  deleteTarget.value = record
  deleteVerifyCode.value = ''
  deleteModalVisible.value = true
  try {
    await sendDeleteCode()
  } catch {
    /* sendVerifyCode 已提示 */
  }
}

async function confirmDeleteTunnel() {
  if (!deleteTarget.value) return
  if (!deleteVerifyCode.value || deleteVerifyCode.value.length !== 6) {
    message.warning('请输入6位验证码')
    return
  }
  deleteVerifyLoading.value = true
  try {
    await deleteCfTunnel({
      tunnelId: deleteTarget.value.id,
      verifyCode: deleteVerifyCode.value,
    })
    message.success('已删除')
    deleteModalVisible.value = false
    deleteTarget.value = null
    await loadTunnels()
  } finally {
    deleteVerifyLoading.value = false
  }
}

async function showToken(record: { id: string; name: string }) {
  const res = await getCfTunnelToken({ tunnelId: record.id })
  openTokenModal(record.name, res.data || '')
}

async function showConnections(record: any) {
  connTitle.value = `连接 · ${record.name}`
  connVisible.value = true
  connLoading.value = true
  connections.value = []
  try {
    const res = await listCfTunnelConnections({ tunnelId: record.id })
    connections.value = res.data || []
  } finally {
    connLoading.value = false
  }
}

onMounted(() => loadTunnels())
</script>

<style scoped>
.cf-toolbar { margin-bottom: 16px; }
.cf-hint { margin-top: 12px; font-size: 12px; color: var(--text-sub); }
.mobile-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}
.mobile-card-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}
.mobile-card-title { font-weight: 600; }
.mobile-card-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
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
.cf-token-alert { margin-bottom: 16px; }
.cf-token-form { margin-top: 4px; }
.cf-token-text,
.cf-install-script {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}
.cf-copy-link { padding-left: 0; margin-top: 4px; }
</style>
