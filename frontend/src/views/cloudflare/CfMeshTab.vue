<template>
  <div class="cf-mesh-tab">
    <a-alert
      v-if="!cfConfigured"
      type="warning"
      show-icon
      message="尚未配置 Cloudflare"
      description="请先在系统设置里填写 Cloudflare Account ID 与 API Token。"
      class="mesh-alert"
    />

    <div class="mesh-toolbar">
      <a-space wrap>
        <a-button type="primary" :disabled="!cfConfigured" :loading="quickLoading" @click="createQuickMesh">
          <template #icon><ThunderboltOutlined /></template>
          创建节点并生成脚本
        </a-button>
        <a-button :disabled="!cfConfigured" :loading="loading" @click="loadAll">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </a-space>
      <a-space wrap class="mesh-summary">
        <a-tag color="blue">节点 {{ connectors.length }}</a-tag>
        <a-tag color="green">在线 {{ healthyCount }}</a-tag>
        <a-tag color="purple">路由 {{ routes.length }}</a-tag>
      </a-space>
    </div>

    <div class="mesh-overview">
      <div class="mesh-kpi">
        <span class="mesh-kpi-label">Cloudflare Mesh</span>
        <strong>{{ meshReady ? '已配置' : '待配置' }}</strong>
        <small>WARP Connector + 私网路由</small>
      </div>
      <div class="mesh-line" />
      <div class="mesh-kpi">
        <span class="mesh-kpi-label">Mesh 节点</span>
        <strong>{{ healthyCount }}/{{ connectors.length }}</strong>
        <small>Linux 服务器运行 warp-cli</small>
      </div>
      <div class="mesh-line" />
      <div class="mesh-kpi">
        <span class="mesh-kpi-label">OCI 私网</span>
        <strong>{{ routes.length }}</strong>
        <small>客户端通过节点访问 CIDR</small>
      </div>
    </div>

    <div class="mesh-dashboard">
      <div class="mesh-chart">
        <div class="mesh-chart-head">
          <strong>节点健康度</strong>
          <span>{{ healthPercent }}%</span>
        </div>
        <div class="mesh-stack">
          <span
            v-for="seg in statusSegments"
            :key="seg.key"
            :class="['mesh-stack-seg', seg.key]"
            :style="{ width: seg.percent + '%' }"
          />
        </div>
        <div class="mesh-legend">
          <span v-for="seg in statusSegments" :key="seg.key">
            <i :class="seg.key" />{{ seg.label }} {{ seg.count }}
          </span>
        </div>
      </div>
      <div class="mesh-chart">
        <div class="mesh-chart-head">
          <strong>路由分布</strong>
          <span>{{ routes.length }} 条</span>
        </div>
        <div class="route-bars">
          <div v-for="item in routeDistribution" :key="item.id" class="route-bar-row">
            <span>{{ item.name }}</span>
            <div class="route-bar"><i :style="{ width: item.percent + '%' }" /></div>
            <em>{{ item.count }}</em>
          </div>
          <a-empty v-if="routeDistribution.length === 0" description="暂无路由分布" />
        </div>
      </div>
      <div class="mesh-chart">
        <div class="mesh-chart-head">
          <strong>配置检查</strong>
          <span>{{ readyStepCount }}/{{ readinessChecks.length }}</span>
        </div>
        <div class="check-list">
          <div v-for="item in readinessChecks" :key="item.key" :class="['check-row', { ok: item.ok }]">
            <CheckCircleOutlined v-if="item.ok" />
            <ExclamationCircleOutlined v-else />
            <span>{{ item.label }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="mesh-section">
      <div class="mesh-section-head">
        <div>
          <h3>快速组网</h3>
          <p>按顺序创建节点、复制脚本、添加 OCI 私网段。适合第一次配置。</p>
        </div>
      </div>
      <a-form layout="vertical" class="mesh-form">
        <a-row :gutter="12">
          <a-col :xs="24" :md="8">
            <a-form-item label="节点名称">
              <a-input v-model:value="quickForm.name" placeholder="如 oci-ashburn-vcn" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :md="8">
            <a-form-item label="OCI 私网 CIDR">
              <a-input v-model:value="quickForm.network" placeholder="如 10.0.0.0/16" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :md="8">
            <a-form-item label="虚拟网络">
              <a-select
                v-model:value="quickForm.virtualNetworkId"
                allow-clear
                :options="virtualNetworkOptions"
                placeholder="默认网络"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="备注">
          <a-input v-model:value="quickForm.comment" placeholder="如 OCI Ashburn VCN" />
        </a-form-item>
      </a-form>
      <div class="mesh-guide">
        <div class="mesh-guide-item">
          <span>1</span>
          <p>创建 Mesh 节点，Cloudflare 会生成一个可注册的 Connector Token。</p>
        </div>
        <div class="mesh-guide-item">
          <span>2</span>
          <p>在 OCI Linux 机器上复制运行脚本，节点上线后状态会变成 healthy。</p>
        </div>
        <div class="mesh-guide-item">
          <span>3</span>
          <p>添加 OCI VCN/Subnet 的 CIDR，客户端就能通过 Mesh 访问私网地址。</p>
        </div>
      </div>
    </div>

    <div class="mesh-section">
      <div class="mesh-section-head">
        <div>
          <h3>节点与脚本</h3>
          <p>查看节点状态，生成安装脚本。脚本基于 Cloudflare Mesh 官方 get-started 命令。</p>
        </div>
        <a-space wrap>
          <a-input v-model:value="newNodeName" placeholder="新节点名称" style="width: 220px" @pressEnter="createNodeOnly" />
          <a-button :disabled="!cfConfigured" :loading="createNodeLoading" @click="createNodeOnly">
            <template #icon><PlusOutlined /></template>
            创建节点
          </a-button>
        </a-space>
      </div>

      <a-table
        v-if="!isMobile"
        :columns="connectorColumns"
        :data-source="connectors"
        :loading="loading"
        row-key="id"
        size="middle"
        :scroll="{ x: 980 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <a-tag :color="statusColor(record.status)">{{ record.status || 'unknown' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'routes'">
            {{ routeCount(record.id) }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space wrap>
              <a-button type="link" size="small" @click="selectNodeForScript(record)">脚本</a-button>
              <a-button type="link" size="small" @click="showConnections(record)">连接</a-button>
              <a-popconfirm :title="deleteNodeConfirmTitle(record)" @confirm="deleteNode(record)">
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
      <a-spin v-else :spinning="loading">
        <a-empty v-if="!loading && connectors.length === 0" description="暂无 Mesh 节点" />
        <div v-for="item in connectors" :key="item.id" class="mobile-card">
          <div class="mobile-card-header">
            <span class="mobile-card-title">{{ item.name }}</span>
            <a-tag :color="statusColor(item.status)">{{ item.status || 'unknown' }}</a-tag>
          </div>
          <div class="mobile-card-body">
            <div class="mobile-card-row"><span class="label">ID</span><span class="value">{{ item.id }}</span></div>
            <div class="mobile-card-row"><span class="label">路由</span><span class="value">{{ routeCount(item.id) }}</span></div>
            <div class="mobile-card-row"><span class="label">上线</span><span class="value">{{ item.activeAt || '-' }}</span></div>
          </div>
          <a-space wrap>
            <a-button size="small" @click="selectNodeForScript(item)">脚本</a-button>
            <a-button size="small" @click="showConnections(item)">连接</a-button>
            <a-popconfirm :title="deleteNodeConfirmTitle(item)" @confirm="deleteNode(item)">
              <a-button size="small" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </div>
      </a-spin>

      <div class="script-panel">
        <div class="script-head">
          <div>
            <strong>{{ scriptTitle }}</strong>
            <p>先选择节点生成 Token，再复制对应系统脚本到 OCI Linux 服务器执行。</p>
          </div>
          <a-space wrap>
            <a-select v-model:value="scriptOs" :options="scriptOsOptions" style="width: 170px" />
            <a-button :disabled="!installScript" @click="copyText(installScript)">复制安装脚本</a-button>
            <a-button :disabled="!registerCommand" @click="copyText(registerCommand)">复制注册命令</a-button>
          </a-space>
        </div>
        <a-tabs v-model:active-key="scriptTab" size="small">
          <a-tab-pane key="install" tab="安装脚本">
            <a-textarea :value="installScript || '请选择一个 Mesh 节点生成脚本'" readonly :auto-size="{ minRows: 8, maxRows: 14 }" />
          </a-tab-pane>
          <a-tab-pane key="register" tab="注册命令">
            <a-textarea :value="registerCommand || '请选择一个 Mesh 节点生成注册命令'" readonly :auto-size="{ minRows: 3, maxRows: 8 }" />
          </a-tab-pane>
          <a-tab-pane key="tutorial" tab="教程">
            <ol class="mesh-tutorial">
              <li>在本页创建 Mesh 节点，并点击“脚本”。</li>
              <li>把“安装脚本”复制到 OCI Linux 服务器执行。</li>
              <li>继续复制“注册命令”，把节点注册到 Cloudflare Mesh。</li>
              <li>回到本页刷新，节点状态变为 healthy 后，再添加私网 CIDR 路由。</li>
              <li>客户端设备安装 Cloudflare One Client 并加入 Zero Trust 组织后，即可访问 Mesh IP 或已发布 CIDR。</li>
            </ol>
          </a-tab-pane>
        </a-tabs>
      </div>

      <a-collapse v-model:active-key="advancedKeys" class="advanced-panel">
        <a-collapse-panel key="script" header="高级项：脚本行为">
          <a-row :gutter="12">
            <a-col :xs="24" :md="8">
              <a-checkbox v-model:checked="advancedScript.enableForwarding">启用 IP 转发</a-checkbox>
              <p class="advanced-hint">需要发布 OCI 私网 CIDR 时保持开启。</p>
            </a-col>
            <a-col :xs="24" :md="8">
              <a-checkbox v-model:checked="advancedScript.connectAfterInstall">注册后立即连接</a-checkbox>
              <p class="advanced-hint">安装完成后自动执行 connect。</p>
            </a-col>
            <a-col :xs="24" :md="8">
              <a-checkbox v-model:checked="advancedScript.includeStatusCheck">附带状态检查</a-checkbox>
              <p class="advanced-hint">脚本末尾输出 warp-cli status。</p>
            </a-col>
            <a-col :xs="24" :md="8">
              <a-checkbox v-model:checked="advancedScript.resetExisting">先清理旧注册</a-checkbox>
              <p class="advanced-hint">节点重复注册失败时再开启。</p>
            </a-col>
            <a-col :xs="24" :md="8">
              <a-checkbox v-model:checked="advancedScript.showMtuTip">显示 MTU 提示</a-checkbox>
              <p class="advanced-hint">Cloudflare 建议 MTU 1381。</p>
            </a-col>
          </a-row>
        </a-collapse-panel>

        <a-collapse-panel key="vnet" header="高级项：虚拟网络管理">
          <div class="vnet-advanced-head">
            <a-space wrap>
              <a-input v-model:value="vnetForm.name" placeholder="虚拟网络名称" style="width: 180px" />
              <a-input v-model:value="vnetForm.comment" placeholder="备注" style="width: 220px" />
              <a-checkbox v-model:checked="vnetForm.defaultNetwork">默认网络</a-checkbox>
              <a-button :disabled="!cfConfigured" :loading="vnetSaving" @click="createVirtualNetwork">新建虚拟网络</a-button>
            </a-space>
          </div>
          <a-table
            :columns="vnetColumns"
            :data-source="virtualNetworks"
            row-key="id"
            size="small"
            :pagination="false"
            :scroll="{ x: 760 }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'defaultNetwork'">
                <a-tag v-if="record.defaultNetwork" color="green">默认</a-tag>
                <span v-else>-</span>
              </template>
              <template v-else-if="column.key === 'action'">
                <a-popconfirm title="确定删除这个虚拟网络？" @confirm="deleteVirtualNetwork(record)">
                  <a-button type="link" danger size="small">删除</a-button>
                </a-popconfirm>
              </template>
            </template>
          </a-table>
        </a-collapse-panel>

        <a-collapse-panel key="diagnose" header="高级项：排障清单">
          <div class="diagnose-grid">
            <div>
              <strong>节点不上线</strong>
              <p>检查 Token 是否来自当前节点，服务器是否能访问 Cloudflare，`warp-cli status` 是否 connected。</p>
            </div>
            <div>
              <strong>CIDR 不能访问</strong>
              <p>确认脚本开启 IP 转发，OCI 安全列表/NSG 放行，客户端已加入 Zero Trust 组织。</p>
            </div>
            <div>
              <strong>需要高可用</strong>
              <p>创建第二个 Mesh 节点作为副本，再按 Cloudflare 控制台的 Mesh 节点 HA 配置做 active-passive。</p>
            </div>
          </div>
        </a-collapse-panel>
      </a-collapse>
    </div>

    <div class="mesh-section">
      <div class="mesh-section-head">
        <div>
          <h3>路由与拓扑</h3>
          <p>把 OCI 私网 CIDR 绑定到 Mesh 节点，并直观看到 Cloudflare、节点、私网之间的关系。</p>
        </div>
        <a-select
          v-model:value="routeFilterTunnelId"
          allow-clear
          :options="connectorOptions"
          placeholder="筛选节点"
          style="width: 220px"
        />
      </div>

      <div class="route-form">
        <a-form layout="vertical">
          <a-row :gutter="12">
            <a-col :xs="24" :md="7">
              <a-form-item label="节点">
                <a-select v-model:value="routeForm.tunnelId" :options="connectorOptions" placeholder="选择 Mesh 节点" />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="6">
              <a-form-item label="CIDR">
                <a-input v-model:value="routeForm.network" placeholder="10.0.0.0/16" />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="6">
              <a-form-item label="虚拟网络">
                <a-select v-model:value="routeForm.virtualNetworkId" allow-clear :options="virtualNetworkOptions" placeholder="默认网络" />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="5">
              <a-form-item label="操作">
                <a-button type="primary" block :disabled="!cfConfigured" :loading="routeSaving" @click="createRoute">
                  添加路由
                </a-button>
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="备注">
            <a-input v-model:value="routeForm.comment" placeholder="如 OCI VCN / 子网用途" />
          </a-form-item>
        </a-form>
      </div>

      <div class="topology">
        <div class="topology-node cloud">
          <ClusterOutlined />
          <strong>Cloudflare</strong>
          <small>Zero Trust Mesh</small>
        </div>
        <div class="topology-path" />
        <div class="topology-list">
          <div v-for="node in topologyNodes" :key="node.id" class="topology-node">
            <DeploymentUnitOutlined />
            <strong>{{ node.name }}</strong>
            <small>{{ node.routes.length ? node.routes.join(' / ') : '未绑定 CIDR' }}</small>
          </div>
          <a-empty v-if="topologyNodes.length === 0" description="暂无拓扑数据" />
        </div>
      </div>

      <a-table
        v-if="!isMobile"
        :columns="routeColumns"
        :data-source="filteredRoutes"
        :loading="loading"
        row-key="id"
        size="middle"
        :scroll="{ x: 920 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'node'">
            {{ connectorName(record.tunnelId) }}
          </template>
          <template v-else-if="column.key === 'vnet'">
            {{ virtualNetworkName(record.virtualNetworkId) }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-popconfirm title="确定删除这条私网路由？" @confirm="deleteRoute(record)">
              <a-button type="link" danger size="small">删除</a-button>
            </a-popconfirm>
          </template>
        </template>
      </a-table>
      <a-spin v-else :spinning="loading">
        <a-empty v-if="!loading && filteredRoutes.length === 0" description="暂无 Mesh 路由" />
        <div v-for="item in filteredRoutes" :key="item.id" class="mobile-card">
          <div class="mobile-card-header">
            <span class="mobile-card-title">{{ item.network }}</span>
            <a-tag>{{ connectorName(item.tunnelId) }}</a-tag>
          </div>
          <div class="mobile-card-body">
            <div class="mobile-card-row"><span class="label">虚拟网络</span><span class="value">{{ virtualNetworkName(item.virtualNetworkId) }}</span></div>
            <div class="mobile-card-row"><span class="label">备注</span><span class="value">{{ item.comment || '-' }}</span></div>
          </div>
          <a-popconfirm title="确定删除这条私网路由？" @confirm="deleteRoute(item)">
            <a-button size="small" danger>删除</a-button>
          </a-popconfirm>
        </div>
      </a-spin>
    </div>

    <a-modal v-model:open="connectionsVisible" title="Mesh 节点连接" :footer="null" :width="isMobile ? '100%' : 720">
      <a-table
        :columns="connectionColumns"
        :data-source="connections"
        :loading="connectionsLoading"
        row-key="uuid"
        size="small"
        :pagination="false"
        :scroll="{ x: 720 }"
      />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  CheckCircleOutlined,
  ClusterOutlined,
  DeploymentUnitOutlined,
  ExclamationCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons-vue'
import { useIsMobile } from '../../composables/useIsMobile'
import {
  createCfMeshConnector,
  createCfMeshRoute,
  createCfMeshVirtualNetwork,
  deleteCfMeshConnector,
  deleteCfMeshVirtualNetwork,
  deleteCfMeshRoute,
  getCfMeshConnectorToken,
  listCfMeshConnectorConnections,
  listCfMeshConnectors,
  listCfMeshRoutes,
  listCfMeshVirtualNetworks,
} from '../../api/cloudflare'

const props = defineProps<{ cfConfigured: boolean }>()
const { isMobile } = useIsMobile()

type MeshConnector = {
  id: string
  name: string
  status?: string
  tunType?: string
  activeAt?: string
  inactiveAt?: string
  createdAt?: string
  connectionCount?: number
}

type MeshRoute = {
  id: string
  network: string
  tunnelId: string
  virtualNetworkId?: string
  comment?: string
  createdAt?: string
}

type MeshVirtualNetwork = {
  id: string
  name: string
  comment?: string
  defaultNetwork?: boolean
}

const loading = ref(false)
const quickLoading = ref(false)
const createNodeLoading = ref(false)
const routeSaving = ref(false)
const vnetSaving = ref(false)
const connectors = ref<MeshConnector[]>([])
const routes = ref<MeshRoute[]>([])
const virtualNetworks = ref<MeshVirtualNetwork[]>([])
const newNodeName = ref('')
const routeFilterTunnelId = ref<string | undefined>(undefined)
const advancedKeys = ref<string[]>([])

const advancedScript = reactive({
  enableForwarding: true,
  connectAfterInstall: true,
  includeStatusCheck: true,
  resetExisting: false,
  showMtuTip: true,
})

const quickForm = reactive({
  name: 'oci-mesh-node',
  network: '10.0.0.0/16',
  virtualNetworkId: undefined as string | undefined,
  comment: 'OCI private network',
})

const routeForm = reactive({
  tunnelId: undefined as string | undefined,
  network: '',
  virtualNetworkId: undefined as string | undefined,
  comment: '',
})

const vnetForm = reactive({
  name: '',
  comment: '',
  defaultNetwork: false,
})

const selectedNode = ref<MeshConnector | null>(null)
const selectedToken = ref('')
const scriptOs = ref<'debian' | 'redhat'>('debian')
const scriptTab = ref('install')
const connectionsVisible = ref(false)
const connectionsLoading = ref(false)
const connections = ref<any[]>([])

const connectorColumns = [
  { title: '节点', dataIndex: 'name', key: 'name', width: 180 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 120 },
  { title: '路由数', key: 'routes', width: 90 },
  { title: '连接数', dataIndex: 'connectionCount', key: 'connectionCount', width: 90 },
  { title: '最近上线', dataIndex: 'activeAt', key: 'activeAt', width: 190 },
  { title: 'ID', dataIndex: 'id', key: 'id', width: 280 },
  { title: '操作', key: 'action', width: 180, fixed: 'right' },
]

const routeColumns = [
  { title: 'CIDR', dataIndex: 'network', key: 'network', width: 160 },
  { title: '节点', key: 'node', width: 180 },
  { title: '虚拟网络', key: 'vnet', width: 180 },
  { title: '备注', dataIndex: 'comment', key: 'comment', width: 220 },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 190 },
  { title: '操作', key: 'action', width: 90, fixed: 'right' },
]

const connectionColumns = [
  { title: '机房', dataIndex: 'coloName', key: 'coloName', width: 90 },
  { title: '来源 IP', dataIndex: 'originIp', key: 'originIp', width: 150 },
  { title: '版本', dataIndex: 'clientVersion', key: 'clientVersion', width: 120 },
  { title: '连接时间', dataIndex: 'openedAt', key: 'openedAt', width: 190 },
  { title: 'UUID', dataIndex: 'uuid', key: 'uuid', width: 260 },
]

const vnetColumns = [
  { title: '名称', dataIndex: 'name', key: 'name', width: 180 },
  { title: '默认', dataIndex: 'defaultNetwork', key: 'defaultNetwork', width: 90 },
  { title: '备注', dataIndex: 'comment', key: 'comment', width: 220 },
  { title: 'ID', dataIndex: 'id', key: 'id', width: 280 },
  { title: '操作', key: 'action', width: 90, fixed: 'right' },
]

const scriptOsOptions = [
  { label: 'Debian / Ubuntu', value: 'debian' },
  { label: 'RedHat / CentOS', value: 'redhat' },
]

const healthyCount = computed(() => connectors.value.filter(item => item.status === 'healthy').length)
const meshReady = computed(() => connectors.value.length > 0 || routes.value.length > 0)
const healthPercent = computed(() => connectors.value.length ? Math.round((healthyCount.value / connectors.value.length) * 100) : 0)
const connectorOptions = computed(() => connectors.value.map(item => ({
  label: `${item.name} (${item.status || 'unknown'})`,
  value: item.id,
})))
const virtualNetworkOptions = computed(() => virtualNetworks.value.map(item => ({
  label: item.defaultNetwork ? `${item.name} · 默认` : item.name,
  value: item.id,
})))
const scriptTitle = computed(() => selectedNode.value ? `脚本 · ${selectedNode.value.name}` : '脚本 · 请先选择节点')
const registerCommand = computed(() => {
  if (!selectedToken.value) return ''
  const parts = []
  if (advancedScript.resetExisting) {
    parts.push('sudo warp-cli disconnect || true', 'sudo warp-cli registration delete || true')
  }
  parts.push(`sudo warp-cli connector new ${selectedToken.value}`)
  if (advancedScript.connectAfterInstall) {
    parts.push('sudo warp-cli connect')
  }
  if (advancedScript.includeStatusCheck) {
    parts.push('sudo warp-cli status')
  }
  return parts.join(' && ')
})
const installScript = computed(() => {
  if (!selectedToken.value) return ''
  return scriptOs.value === 'debian' ? buildDebianScript(selectedToken.value) : buildRedHatScript(selectedToken.value)
})
const filteredRoutes = computed(() => {
  if (!routeFilterTunnelId.value) return routes.value
  return routes.value.filter(route => route.tunnelId === routeFilterTunnelId.value)
})
const topologyNodes = computed(() => connectors.value.map(node => ({
  id: node.id,
  name: node.name,
  routes: routes.value.filter(route => route.tunnelId === node.id).map(route => route.network),
})))
const statusSegments = computed(() => {
  const total = connectors.value.length || 1
  const healthy = connectors.value.filter(item => item.status === 'healthy').length
  const degraded = connectors.value.filter(item => item.status === 'degraded').length
  const down = connectors.value.filter(item => item.status === 'down').length
  const inactive = connectors.value.length - healthy - degraded - down
  return [
    { key: 'healthy', label: '健康', count: healthy, percent: Math.round((healthy / total) * 100) },
    { key: 'degraded', label: '降级', count: degraded, percent: Math.round((degraded / total) * 100) },
    { key: 'down', label: '离线', count: down, percent: Math.round((down / total) * 100) },
    { key: 'inactive', label: '未运行', count: Math.max(inactive, 0), percent: Math.round((Math.max(inactive, 0) / total) * 100) },
  ].filter(item => item.count > 0 || connectors.value.length === 0)
})
const routeDistribution = computed(() => {
  const max = Math.max(1, ...connectors.value.map(item => routeCount(item.id)))
  return connectors.value
    .map(item => {
      const count = routeCount(item.id)
      return { id: item.id, name: item.name, count, percent: Math.max(4, Math.round((count / max) * 100)) }
    })
    .filter(item => item.count > 0)
})
const readinessChecks = computed(() => [
  { key: 'config', label: 'Cloudflare 配置已启用', ok: props.cfConfigured },
  { key: 'node', label: '已创建 Mesh 节点', ok: connectors.value.length > 0 },
  { key: 'healthy', label: '至少一个节点在线', ok: healthyCount.value > 0 },
  { key: 'route', label: '已添加私网 CIDR 路由', ok: routes.value.length > 0 },
  { key: 'script', label: '已生成节点安装脚本', ok: Boolean(selectedToken.value) },
])
const readyStepCount = computed(() => readinessChecks.value.filter(item => item.ok).length)

watch(() => props.cfConfigured, ok => {
  if (ok) loadAll()
})

onMounted(() => {
  if (props.cfConfigured) loadAll()
})

async function loadAll() {
  loading.value = true
  try {
    const [nodeRes, routeRes, vnetRes] = await Promise.all([
      listCfMeshConnectors(true),
      listCfMeshRoutes({}, true),
      listCfMeshVirtualNetworks(true),
    ])
    connectors.value = nodeRes.data || []
    routes.value = routeRes.data || []
    virtualNetworks.value = vnetRes.data || []
    if (!routeForm.tunnelId && connectors.value.length > 0) {
      routeForm.tunnelId = connectors.value[0].id
    }
  } finally {
    loading.value = false
  }
}

async function createQuickMesh() {
  if (!quickForm.name.trim()) {
    message.warning('请输入节点名称')
    return
  }
  quickLoading.value = true
  try {
    const nodeRes = await createCfMeshConnector({ name: quickForm.name.trim() })
    const node = nodeRes.data as MeshConnector
    selectedNode.value = node
    routeForm.tunnelId = node.id
    const tokenRes = await getCfMeshConnectorToken({ tunnelId: node.id })
    selectedToken.value = tokenRes.data || ''
    if (quickForm.network.trim()) {
      await createCfMeshRoute({
        tunnelId: node.id,
        network: quickForm.network.trim(),
        virtualNetworkId: quickForm.virtualNetworkId,
        comment: quickForm.comment,
      })
    }
    message.success('Mesh 节点已创建，脚本已生成')
    await loadAll()
  } finally {
    quickLoading.value = false
  }
}

async function createNodeOnly() {
  const name = newNodeName.value.trim()
  if (!name) {
    message.warning('请输入节点名称')
    return
  }
  createNodeLoading.value = true
  try {
    const res = await createCfMeshConnector({ name })
    newNodeName.value = ''
    message.success('Mesh 节点已创建')
    await loadAll()
    await selectNodeForScript(res.data)
  } finally {
    createNodeLoading.value = false
  }
}

async function selectNodeForScript(node: MeshConnector) {
  selectedNode.value = node
  const res = await getCfMeshConnectorToken({ tunnelId: node.id })
  selectedToken.value = res.data || ''
  scriptTab.value = 'install'
}

async function deleteNode(node: MeshConnector) {
  const count = routeCount(node.id)
  await deleteCfMeshConnector({ tunnelId: node.id, forceDeleteRoutes: true })
  message.success(count > 0 ? `已删除 ${count} 条路由和 Mesh 节点` : 'Mesh 节点已删除')
  if (selectedNode.value?.id === node.id) {
    selectedNode.value = null
    selectedToken.value = ''
  }
  await loadAll()
}

function deleteNodeConfirmTitle(node: MeshConnector) {
  const count = routeCount(node.id)
  return count > 0
    ? `该节点绑定 ${count} 条私网路由，删除节点前会先删除这些路由。确定继续？`
    : '确定删除这个 Mesh 节点？'
}

async function showConnections(node: MeshConnector) {
  connectionsVisible.value = true
  connectionsLoading.value = true
  try {
    const res = await listCfMeshConnectorConnections({ tunnelId: node.id }, true)
    connections.value = res.data || []
  } finally {
    connectionsLoading.value = false
  }
}

async function createRoute() {
  if (!routeForm.tunnelId) {
    message.warning('请选择 Mesh 节点')
    return
  }
  if (!routeForm.network.trim()) {
    message.warning('请输入 CIDR')
    return
  }
  routeSaving.value = true
  try {
    await createCfMeshRoute({
      tunnelId: routeForm.tunnelId,
      network: routeForm.network.trim(),
      virtualNetworkId: routeForm.virtualNetworkId,
      comment: routeForm.comment,
    })
    routeForm.network = ''
    routeForm.comment = ''
    message.success('Mesh 路由已添加')
    await loadAll()
  } finally {
    routeSaving.value = false
  }
}

async function deleteRoute(route: MeshRoute) {
  await deleteCfMeshRoute({ routeId: route.id })
  message.success('Mesh 路由已删除')
  await loadAll()
}

async function createVirtualNetwork() {
  if (!vnetForm.name.trim()) {
    message.warning('请输入虚拟网络名称')
    return
  }
  vnetSaving.value = true
  try {
    await createCfMeshVirtualNetwork({
      name: vnetForm.name.trim(),
      comment: vnetForm.comment,
      defaultNetwork: vnetForm.defaultNetwork,
    })
    vnetForm.name = ''
    vnetForm.comment = ''
    vnetForm.defaultNetwork = false
    message.success('虚拟网络已创建')
    await loadAll()
  } finally {
    vnetSaving.value = false
  }
}

async function deleteVirtualNetwork(vnet: MeshVirtualNetwork) {
  await deleteCfMeshVirtualNetwork({ virtualNetworkId: vnet.id })
  message.success('虚拟网络已删除')
  if (quickForm.virtualNetworkId === vnet.id) quickForm.virtualNetworkId = undefined
  if (routeForm.virtualNetworkId === vnet.id) routeForm.virtualNetworkId = undefined
  await loadAll()
}

function routeCount(tunnelId: string) {
  return routes.value.filter(route => route.tunnelId === tunnelId).length
}

function connectorName(tunnelId?: string) {
  if (!tunnelId) return '-'
  return connectors.value.find(item => item.id === tunnelId)?.name || tunnelId
}

function virtualNetworkName(id?: string) {
  if (!id) return '默认网络'
  return virtualNetworks.value.find(item => item.id === id)?.name || id
}

function statusColor(status?: string) {
  if (status === 'healthy') return 'green'
  if (status === 'degraded') return 'orange'
  if (status === 'down') return 'red'
  return 'default'
}

async function copyText(text: string) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制')
  } catch {
    message.error('复制失败，请手动选择复制')
  }
}

function buildDebianScript(token: string) {
  const lines = [
    '#!/usr/bin/env bash',
    'set -euo pipefail',
  ]
  if (advancedScript.showMtuTip) {
    lines.push('echo "Cloudflare Mesh recommends MTU 1381 or higher path MTU discovery support."')
  }
  lines.push(
    'curl -fsSL https://pkg.cloudflareclient.com/pubkey.gpg | sudo gpg --yes --dearmor -o /usr/share/keyrings/cloudflare-warp-archive-keyring.gpg',
    'echo "deb [signed-by=/usr/share/keyrings/cloudflare-warp-archive-keyring.gpg] https://pkg.cloudflareclient.com/ $(. /etc/os-release && echo $VERSION_CODENAME) main" | sudo tee /etc/apt/sources.list.d/cloudflare-client.list',
    'sudo apt-get update -qq',
    'sudo apt-get install -y -qq cloudflare-warp',
  )
  if (advancedScript.enableForwarding) {
    lines.push(
    "printf 'net.ipv4.ip_forward = 1\\nnet.ipv6.conf.all.forwarding = 1\\nnet.ipv6.conf.all.accept_ra = 2\\n' | sudo tee /etc/sysctl.d/99-zzz-cloudflare-warp-connector.conf",
    'sudo sysctl --system',
    )
  }
  if (advancedScript.resetExisting) {
    lines.push('sudo warp-cli disconnect || true', 'sudo warp-cli registration delete || true')
  }
  lines.push(`sudo warp-cli connector new ${token}`)
  if (advancedScript.connectAfterInstall) {
    lines.push('sudo warp-cli connect')
  }
  if (advancedScript.includeStatusCheck) {
    lines.push('sudo warp-cli status')
  }
  return lines.join('\n')
}

function buildRedHatScript(token: string) {
  const lines = [
    '#!/usr/bin/env bash',
    'set -euo pipefail',
  ]
  if (advancedScript.showMtuTip) {
    lines.push('echo "Cloudflare Mesh recommends MTU 1381 or higher path MTU discovery support."')
  }
  lines.push(
    'sudo dnf install -y epel-release || true',
    'curl -fsSl https://pkg.cloudflareclient.com/cloudflare-warp-ascii.repo | sudo tee /etc/yum.repos.d/cloudflare-warp.repo',
    'sudo yum install -y cloudflare-warp',
  )
  if (advancedScript.enableForwarding) {
    lines.push(
    "printf 'net.ipv4.ip_forward = 1\\nnet.ipv6.conf.all.forwarding = 1\\nnet.ipv6.conf.all.accept_ra = 2\\n' | sudo tee /etc/sysctl.d/99-zzz-cloudflare-warp-connector.conf",
    'sudo sysctl --system',
    )
  }
  if (advancedScript.resetExisting) {
    lines.push('sudo warp-cli disconnect || true', 'sudo warp-cli registration delete || true')
  }
  lines.push(`sudo warp-cli connector new ${token}`)
  if (advancedScript.connectAfterInstall) {
    lines.push('sudo warp-cli connect')
  }
  if (advancedScript.includeStatusCheck) {
    lines.push('sudo warp-cli status')
  }
  return lines.join('\n')
}
</script>

<style scoped>
.cf-mesh-tab {
  --mesh-surface: #ffffff;
  --mesh-surface-soft: #fbfcfe;
  --mesh-surface-muted: #f8fafc;
  --mesh-border: #e5e7eb;
  --mesh-border-soft: #edf0f5;
  --mesh-text: #111827;
  --mesh-subtext: #4b5563;
  --mesh-muted: #6b7280;
  display: flex;
  flex-direction: column;
  gap: 16px;
  color: var(--mesh-text);
}

.cf-mesh-tab :deep(.ant-form-item-label > label),
.cf-mesh-tab :deep(.ant-table),
.cf-mesh-tab :deep(.ant-table-cell),
.cf-mesh-tab :deep(.ant-collapse),
.cf-mesh-tab :deep(.ant-tabs),
.cf-mesh-tab :deep(.ant-checkbox-wrapper) {
  color: var(--mesh-text);
}

.cf-mesh-tab :deep(.ant-input),
.cf-mesh-tab :deep(.ant-input-affix-wrapper),
.cf-mesh-tab :deep(.ant-input-number),
.cf-mesh-tab :deep(.ant-select-selector),
.cf-mesh-tab :deep(textarea.ant-input) {
  background: var(--mesh-surface) !important;
  border-color: #d9d9d9 !important;
  color: var(--mesh-text) !important;
}

.cf-mesh-tab :deep(.ant-input::placeholder),
.cf-mesh-tab :deep(textarea.ant-input::placeholder) {
  color: #9ca3af !important;
}

.cf-mesh-tab :deep(.ant-select-selection-placeholder),
.cf-mesh-tab :deep(.ant-select-selection-item) {
  color: var(--mesh-subtext) !important;
}

.cf-mesh-tab :deep(.ant-table) {
  background: var(--mesh-surface);
}

.cf-mesh-tab :deep(.ant-table-thead > tr > th) {
  background: var(--mesh-surface-muted) !important;
  color: var(--mesh-subtext) !important;
  border-bottom-color: var(--mesh-border) !important;
}

.cf-mesh-tab :deep(.ant-table-tbody > tr > td) {
  background: var(--mesh-surface) !important;
  color: var(--mesh-text) !important;
  border-bottom-color: var(--mesh-border-soft) !important;
}

.cf-mesh-tab :deep(.ant-table-tbody > tr:hover > td) {
  background: #f3f7ff !important;
}

.cf-mesh-tab :deep(.ant-empty-description),
.cf-mesh-tab :deep(.ant-pagination),
.cf-mesh-tab :deep(.ant-collapse-content),
.cf-mesh-tab :deep(.ant-collapse-header) {
  color: var(--mesh-subtext) !important;
}

.mesh-alert {
  margin-bottom: 4px;
}

.mesh-toolbar,
.mesh-section-head,
.script-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.mesh-summary {
  justify-content: flex-end;
}

.mesh-overview {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 64px minmax(0, 1fr) 64px minmax(0, 1fr);
  align-items: center;
  padding: 16px;
  border: 1px solid var(--mesh-border);
  border-radius: 8px;
  background: var(--mesh-surface);
}

.mesh-kpi {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  text-align: center;
}

.mesh-kpi strong {
  font-size: 22px;
  line-height: 1.2;
  color: var(--mesh-text);
}

.mesh-kpi small,
.mesh-kpi-label,
.mesh-section-head p,
.script-head p,
.topology-node small {
  color: var(--mesh-muted);
}

.mesh-line {
  height: 2px;
  background: linear-gradient(90deg, #1677ff, #52c41a);
}

.mesh-section {
  padding: 16px;
  border: 1px solid var(--mesh-border);
  border-radius: 8px;
  background: var(--mesh-surface);
}

.mesh-dashboard {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.mesh-chart {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--mesh-border);
  border-radius: 8px;
  background: var(--mesh-surface);
}

.mesh-chart-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.mesh-chart-head span {
  color: #1677ff;
  font-weight: 600;
}

.mesh-stack {
  height: 10px;
  display: flex;
  overflow: hidden;
  border-radius: 999px;
  background: #f0f0f0;
}

.mesh-stack-seg.healthy,
.mesh-legend i.healthy {
  background: #52c41a;
}

.mesh-stack-seg.degraded,
.mesh-legend i.degraded {
  background: #faad14;
}

.mesh-stack-seg.down,
.mesh-legend i.down {
  background: #ff4d4f;
}

.mesh-stack-seg.inactive,
.mesh-legend i.inactive {
  background: #d9d9d9;
}

.mesh-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  margin-top: 10px;
  color: var(--mesh-muted);
  font-size: 12px;
}

.mesh-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.mesh-legend i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.route-bars {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.route-bar-row {
  display: grid;
  grid-template-columns: minmax(80px, 120px) minmax(0, 1fr) 28px;
  align-items: center;
  gap: 8px;
  color: var(--mesh-subtext);
  font-size: 12px;
}

.route-bar-row span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.route-bar-row em {
  font-style: normal;
  text-align: right;
}

.route-bar {
  height: 8px;
  border-radius: 999px;
  background: #edf0f5;
  overflow: hidden;
}

.route-bar i {
  height: 100%;
  display: block;
  border-radius: inherit;
  background: #1677ff;
}

.check-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.check-row {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--mesh-muted);
}

.check-row :deep(svg) {
  color: #faad14;
}

.check-row.ok {
  color: #2f6f22;
}

.check-row.ok :deep(svg) {
  color: #52c41a;
}

.mesh-section h3 {
  margin: 0 0 4px;
  font-size: 16px;
  color: var(--mesh-text);
}

.mesh-section p {
  margin: 0;
}

.mesh-form {
  margin-top: 14px;
}

.mesh-guide {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 4px;
}

.mesh-guide-item {
  display: flex;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--mesh-border-soft);
  border-radius: 8px;
  background: var(--mesh-surface-muted);
}

.mesh-guide-item span {
  flex: 0 0 24px;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  color: #fff;
  background: #1677ff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
}

.script-panel,
.route-form,
.topology {
  margin-top: 14px;
}

.script-panel {
  padding: 12px;
  border: 1px solid var(--mesh-border-soft);
  border-radius: 8px;
  background: var(--mesh-surface-soft);
}

.script-head strong,
.mesh-chart strong,
.diagnose-grid strong,
.topology-node strong {
  color: var(--mesh-text);
}

.mesh-tutorial {
  margin: 0;
  padding-left: 20px;
  line-height: 1.9;
}

.advanced-panel {
  margin-top: 14px;
  background: var(--mesh-surface);
}

.advanced-hint {
  margin: 4px 0 12px;
  color: var(--mesh-muted);
  font-size: 12px;
}

.vnet-advanced-head {
  margin-bottom: 12px;
}

.diagnose-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.diagnose-grid > div {
  padding: 12px;
  border: 1px solid var(--mesh-border-soft);
  border-radius: 8px;
  background: var(--mesh-surface-soft);
}

.diagnose-grid p {
  margin-top: 6px;
  color: var(--mesh-muted);
}

.topology {
  display: grid;
  grid-template-columns: 180px 48px minmax(0, 1fr);
  align-items: stretch;
  gap: 12px;
  margin-bottom: 14px;
}

.topology-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.topology-node {
  min-height: 92px;
  padding: 12px;
  border: 1px solid #d9e6ff;
  border-radius: 8px;
  background: #f7fbff;
  display: flex;
  flex-direction: column;
  gap: 6px;
  justify-content: center;
}

.topology-node :deep(svg) {
  color: #1677ff;
  font-size: 22px;
}

.topology-node.cloud {
  background: #f6ffed;
  border-color: #b7eb8f;
}

.topology-node.cloud :deep(svg) {
  color: #52c41a;
}

.topology-path {
  align-self: center;
  height: 2px;
  background: #91caff;
}

.mobile-card {
  border: 1px solid var(--mesh-border-soft);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 10px;
  background: var(--mesh-surface);
}

.mobile-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.mobile-card-title {
  font-weight: 600;
  word-break: break-all;
}

.mobile-card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 10px;
}

.mobile-card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.mobile-card-row .label {
  color: var(--mesh-muted);
  flex: 0 0 auto;
}

.mobile-card-row .value {
  min-width: 0;
  text-align: right;
  word-break: break-all;
}

@media (max-width: 768px) {
  .mesh-toolbar,
  .mesh-section-head,
  .script-head {
    flex-direction: column;
  }

  .mesh-overview,
  .mesh-guide,
  .mesh-dashboard,
  .diagnose-grid,
  .topology {
    grid-template-columns: 1fr;
  }

  .mesh-line,
  .topology-path {
    display: none;
  }
}
</style>
