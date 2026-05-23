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
            </div>
          </div>
        </a-spin>
      </a-tab-pane>

      <a-tab-pane key="workers" tab="Workers 脚本">
        <div class="cf-toolbar">
          <a-button :loading="scriptsLoading" :disabled="!cfConfigured" @click="loadScripts">刷新</a-button>
        </div>
        <a-table
          :columns="scriptColumns"
          :data-source="scripts"
          :loading="scriptsLoading"
          row-key="id"
          size="middle"
        />
        <p class="cf-hint">账户级 Workers 脚本列表。路由绑定请在「域名 → Workers 路由」中配置。</p>
      </a-tab-pane>
    </a-tabs>

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

    <a-modal v-model:open="tokenVisible" title="Tunnel 运行 Token" :footer="null" width="640px">
      <a-textarea :value="tokenText" :rows="4" readonly />
    </a-modal>

    <a-drawer v-model:open="connVisible" :title="connTitle" width="480">
      <a-spin :spinning="connLoading">
        <a-empty v-if="!connLoading && connections.length === 0" description="暂无活跃连接" />
        <a-list v-else :data-source="connections" item-layout="vertical">
          <template #renderItem="{ item }">
            <a-list-item>
              <a-list-item-meta
                :title="item.coloName || '—'"
                :description="`UUID: ${item.uuid || '—'} · ${item.originIp || '—'}`"
              />
            </a-list-item>
          </template>
        </a-list>
      </a-spin>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import {
  listCfTunnels,
  createCfTunnel,
  deleteCfTunnel,
  getCfTunnelToken,
  listCfTunnelConnections,
  listCfWorkerScripts,
} from '../../api/cloudflare'

defineProps<{ cfConfigured: boolean }>()

const accountTab = ref('tunnel')
const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }

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

const scriptsLoading = ref(false)
const scripts = ref<any[]>([])

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
  const res = await getCfTunnelToken({ tunnelId: record.id })
  tokenText.value = res.data || ''
  tokenVisible.value = true
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

onMounted(() => {
  window.addEventListener('resize', checkMobile)
  loadTunnels()
})
onUnmounted(() => window.removeEventListener('resize', checkMobile))
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
.mobile-card-row .label { color: var(--text-sub); }
</style>
