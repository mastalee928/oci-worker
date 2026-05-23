<template>
  <div class="cf-page">
    <a-alert
      v-if="!cfConfigured"
      type="warning"
      show-icon
      style="margin-bottom: 16px"
      message="尚未配置 Cloudflare"
      description="请先在「系统设置 → Cloudflare」填写 Account ID 与 API Token，并点击测试连接。"
    />

    <a-tabs v-model:active-key="activeTab" @change="onTabChange">
      <a-tab-pane key="dns" tab="DNS 与区域">
        <CfDnsTab v-model:zone-id="selectedZoneId" :cf-configured="cfConfigured" />
      </a-tab-pane>

      <a-tab-pane key="email" tab="电子邮件">
        <CfEmailTab v-model:zone-id="selectedZoneId" :cf-configured="cfConfigured" />
      </a-tab-pane>

      <!-- Tunnel 连接器 -->
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
                <a-popconfirm title="确定删除此 Tunnel？" @confirm="handleDeleteTunnel(record.id)">
                  <a-button type="link" danger size="small">删除</a-button>
                </a-popconfirm>
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
              <div class="mobile-card-row"><span class="label">创建</span><span class="value">{{ item.createdAt || '—' }}</span></div>
            </div>
            <a-space wrap style="margin-top: 8px">
              <a-button size="small" @click="showToken(item)">Token</a-button>
              <a-button size="small" @click="showConnections(item)">连接</a-button>
              <a-popconfirm title="确定删除？" @confirm="handleDeleteTunnel(item.id)">
                <a-button size="small" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </div>
        </a-spin>
      </a-tab-pane>

      <a-tab-pane key="workers" tab="Workers">
        <a-empty description="Workers 脚本与路由功能开发中" />
      </a-tab-pane>
      <a-tab-pane key="security" tab="应用安全">
        <a-empty description="WAF / 防火墙规则等功能开发中" />
      </a-tab-pane>
      <a-tab-pane key="cache" tab="缓存与性能">
        <a-empty description="缓存清理与性能设置开发中" />
      </a-tab-pane>
      <a-tab-pane key="analytics" tab="分析与日志">
        <a-empty description="分析与 Logpush 功能开发中" />
      </a-tab-pane>
      <a-tab-pane key="zerotrust" tab="Zero Trust">
        <a-empty description="Access / Gateway 等功能开发中" />
      </a-tab-pane>
    </a-tabs>

    <!-- Tunnel -->
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
        <p class="cf-hint">创建后将返回 cloudflared 运行 Token（仅展示一次，请妥善保存）。</p>
      </a-form>
    </a-modal>

    <a-modal v-model:open="tokenVisible" title="Tunnel 运行 Token" :footer="null" width="640px">
      <a-alert type="info" show-icon message="在服务器上安装 cloudflared 后执行下方命令" style="margin-bottom: 12px" />
      <a-textarea :value="tokenText" :rows="4" readonly />
      <a-space style="margin-top: 12px">
        <a-button type="primary" @click="copyText(tokenText)">复制 Token</a-button>
        <a-button @click="copyText(installCmd)">复制安装命令</a-button>
      </a-space>
      <pre class="cf-code-block">{{ installCmd }}</pre>
    </a-modal>

    <a-drawer v-model:open="connVisible" :title="connTitle" width="480">
      <a-spin :spinning="connLoading">
        <a-empty v-if="!connLoading && connections.length === 0" description="暂无活跃连接" />
        <a-list v-else :data-source="connections" item-layout="vertical">
          <template #renderItem="{ item }">
            <a-list-item>
              <a-list-item-meta
                :title="item.coloName || '—'"
                :description="`UUID: ${item.uuid || '—'} · 来源 IP: ${item.originIp || '—'}`"
              />
              <div class="cf-conn-meta">建立: {{ item.openedAt || '—' }}</div>
            </a-list-item>
          </template>
        </a-list>
      </a-spin>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import CfDnsTab from './cloudflare/CfDnsTab.vue'
import CfEmailTab from './cloudflare/CfEmailTab.vue'
import {
  getCfAccountConfig,
  listCfTunnels,
  createCfTunnel,
  deleteCfTunnel,
  getCfTunnelToken,
  listCfTunnelConnections,
} from '../api/cloudflare'

const activeTab = ref('dns')
const cfConfigured = ref(false)
const selectedZoneId = ref<string | undefined>(undefined)
const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }

// Tunnel
const tunnelLoading = ref(false)
const tunnels = ref<any[]>([])
const createVisible = ref(false)
const createLoading = ref(false)
const createName = ref('')
const tokenVisible = ref(false)
const tokenText = ref('')
const connVisible = ref(false)
const connLoading = ref(false)
const connTitle = ref('连接详情')
const connections = ref<any[]>([])
const installCmd = computed(() =>
  tokenText.value ? `cloudflared service install ${tokenText.value}` : '')
const tunnelColumns = [
  { title: '名称', dataIndex: 'name', width: 160 },
  { title: 'ID', dataIndex: 'id', ellipsis: true },
  { title: '状态', key: 'status', width: 100 },
  { title: '创建时间', dataIndex: 'createdAt', width: 180 },
  { title: '操作', key: 'action', width: 220 },
]

function statusColor(s: string) {
  switch (s) {
    case 'healthy': return 'success'
    case 'degraded': return 'warning'
    case 'down': return 'error'
    case 'inactive': return 'default'
    default: return 'processing'
  }
}

async function loadCfConfig() {
  try {
    const res = await getCfAccountConfig()
    cfConfigured.value = res.data?.configured === true
  } catch {
    cfConfigured.value = false
  }
}

function onTabChange(key: string) {
  if (!cfConfigured.value) return
  if (key === 'tunnel') loadTunnels()
}

async function loadTunnels() {
  if (!cfConfigured.value) return
  tunnelLoading.value = true
  try {
    const res = await listCfTunnels()
    tunnels.value = res.data || []
  } finally {
    tunnelLoading.value = false
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
      tokenText.value = res.data.token
      tokenVisible.value = true
    }
    await loadTunnels()
  } finally {
    createLoading.value = false
  }
}

async function handleDeleteTunnel(id: string) {
  await deleteCfTunnel({ tunnelId: id })
  message.success('已删除')
  await loadTunnels()
}

async function showToken(record: any) {
  try {
    const res = await getCfTunnelToken({ tunnelId: record.id })
    tokenText.value = res.data || ''
    tokenVisible.value = true
  } catch { /* request 已提示 */ }
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

async function copyText(text: string) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制')
  } catch {
    message.error('复制失败')
  }
}

onMounted(async () => {
  window.addEventListener('resize', checkMobile)
  await loadCfConfig()
})
onUnmounted(() => window.removeEventListener('resize', checkMobile))
</script>

<style scoped>
.cf-page { min-height: 200px; }
.cf-toolbar { margin-bottom: 16px; }
.cf-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-sub);
}
.cf-code-block {
  margin-top: 12px;
  padding: 10px 12px;
  font-family: ui-monospace, monospace;
  font-size: 12px;
  word-break: break-all;
  background: var(--bg-elevated, rgba(0, 0, 0, 0.04));
  border-radius: 6px;
  border: 1px solid var(--border);
}
.cf-conn-meta { font-size: 12px; color: var(--text-sub); }
.mobile-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
  background: var(--bg-card, transparent);
}
.mobile-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
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
