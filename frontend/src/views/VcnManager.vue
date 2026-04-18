<template>
  <a-drawer
    :open="open"
    @update:open="(v: boolean) => emit('update:open', v)"
    :title="`VCN 管理 — ${vcn?.displayName || ''}`"
    width="960"
    destroy-on-close
  >
    <a-descriptions :column="2" size="small" bordered style="margin-bottom: 16px">
      <a-descriptions-item label="VCN 名称">{{ vcn?.displayName }}</a-descriptions-item>
      <a-descriptions-item label="CIDR">{{ (vcn?.cidrBlocks || [vcn?.cidrBlock]).filter(Boolean).join(', ') }}</a-descriptions-item>
      <a-descriptions-item label="DNS Label">{{ vcn?.dnsLabel || '-' }}</a-descriptions-item>
      <a-descriptions-item label="状态">
        <a-badge :status="vcn?.lifecycleState === 'AVAILABLE' ? 'success' : 'default'" :text="vcn?.lifecycleState" />
      </a-descriptions-item>
    </a-descriptions>

    <div style="margin-bottom: 12px">
      <a-space>
        <a-button danger @click="openDeleteVcn">删除此 VCN</a-button>
      </a-space>
    </div>

    <a-tabs v-model:activeKey="activeTab" @change="onTab">
      <a-tab-pane key="subnet" tab="子网">
        <div class="op-row">
          <a-button type="primary" size="small" @click="showCreateSubnet = true">创建子网</a-button>
          <a-button size="small" @click="loadSubnets" :loading="loading.subnet">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.subnet" :data-source="data.subnet" :columns="cols.subnet" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'action'">
              <a-space>
                <a-button size="small" @click="openEditSubnet(record)">编辑</a-button>
                <a-button size="small" danger @click="askDelete('subnet', record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="igw" tab="Internet 网关">
        <div class="op-row">
          <a-button type="primary" size="small" @click="showCreateIgw = true">创建 IGW</a-button>
          <a-button size="small" @click="loadIgw" :loading="loading.igw">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.igw" :data-source="data.igw" :columns="cols.igw" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'action'">
              <a-space>
                <a-button size="small" @click="toggleIgw(record)" :loading="togglingId === record.id">
                  {{ record.isEnabled ? '禁用' : '启用' }}
                </a-button>
                <a-button size="small" @click="openRename('igw', record)">改名</a-button>
                <a-button size="small" danger @click="askDelete('igw', record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="nat" tab="NAT 网关">
        <div class="op-row">
          <a-button type="primary" size="small" @click="showCreateNat = true">创建 NAT</a-button>
          <a-button size="small" @click="loadNat" :loading="loading.nat">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.nat" :data-source="data.nat" :columns="cols.nat" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'action'">
              <a-space>
                <a-button size="small" @click="toggleNat(record)" :loading="togglingId === record.id">
                  {{ record.blockTraffic ? '放行流量' : '阻断流量' }}
                </a-button>
                <a-button size="small" @click="openRename('nat', record)">改名</a-button>
                <a-button size="small" danger @click="askDelete('nat', record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="sg" tab="服务网关">
        <div class="op-row">
          <a-button type="primary" size="small" @click="showCreateSg = true">创建 SG</a-button>
          <a-button size="small" @click="loadSg" :loading="loading.sg">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.sg" :data-source="data.sg" :columns="cols.sg" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'action'">
              <a-space>
                <a-button size="small" @click="toggleSg(record)" :loading="togglingId === record.id">
                  {{ record.blockTraffic ? '放行流量' : '阻断流量' }}
                </a-button>
                <a-button size="small" @click="openRename('sg', record)">改名</a-button>
                <a-button size="small" danger @click="askDelete('sg', record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="lpg" tab="LPG 对等">
        <div class="op-row">
          <a-button type="primary" size="small" @click="showCreateLpg = true">创建 LPG</a-button>
          <a-button size="small" @click="loadLpg" :loading="loading.lpg">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.lpg" :data-source="data.lpg" :columns="cols.lpg" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'action'">
              <a-space>
                <a-button size="small" @click="openConnectLpg(record)">连接对端</a-button>
                <a-button size="small" @click="openRename('lpg', record)">改名</a-button>
                <a-button size="small" danger @click="askDelete('lpg', record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="rt" tab="路由表">
        <div class="op-row">
          <a-button size="small" @click="loadRt" :loading="loading.rt">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.rt" :data-source="data.rt" :columns="cols.rt" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'action'">
              <a-space>
                <a-button size="small" @click="openEditRt(record)">编辑规则</a-button>
                <a-button size="small" danger @click="askDelete('rt', record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="sl" tab="安全列表">
        <div class="op-row">
          <a-button size="small" @click="loadSl" :loading="loading.sl">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.sl" :data-source="data.sl" :columns="cols.sl" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'action'">
              <a-space>
                <a-button size="small" @click="openViewSl(record)">查看规则</a-button>
                <a-button size="small" danger @click="askDelete('sl', record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
    </a-tabs>

    <!-- Create Subnet -->
    <a-modal v-model:open="showCreateSubnet" title="创建子网" @ok="doCreateSubnet" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="名称" required><a-input v-model:value="newSubnet.displayName" /></a-form-item>
        <a-form-item label="CIDR" required><a-input v-model:value="newSubnet.cidrBlock" placeholder="如 10.0.1.0/24" /></a-form-item>
        <a-form-item label="可用域（留空为区域子网）"><a-input v-model:value="newSubnet.availabilityDomain" /></a-form-item>
        <a-form-item label="路由表 ID（可选）"><a-input v-model:value="newSubnet.routeTableId" /></a-form-item>
        <a-form-item><a-checkbox v-model:checked="newSubnet.prohibitPublicIp">私有子网（禁止公网 IP）</a-checkbox></a-form-item>
      </a-form>
    </a-modal>

    <!-- Create IGW -->
    <a-modal v-model:open="showCreateIgw" title="创建 Internet 网关" @ok="doCreateIgw" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="名称" required><a-input v-model:value="newIgw.displayName" /></a-form-item>
        <a-form-item><a-checkbox v-model:checked="newIgw.isEnabled">启用</a-checkbox></a-form-item>
      </a-form>
    </a-modal>

    <!-- Create NAT -->
    <a-modal v-model:open="showCreateNat" title="创建 NAT 网关" @ok="doCreateNat" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="名称" required><a-input v-model:value="newNat.displayName" /></a-form-item>
      </a-form>
    </a-modal>

    <!-- Create SG -->
    <a-modal v-model:open="showCreateSg" title="创建服务网关" @ok="doCreateSg" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="名称" required><a-input v-model:value="newSg.displayName" /></a-form-item>
        <a-alert type="info" message="默认添加该区域的全部 Oracle Services Network 服务" show-icon />
      </a-form>
    </a-modal>

    <!-- Create LPG -->
    <a-modal v-model:open="showCreateLpg" title="创建 Local Peering Gateway" @ok="doCreateLpg" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="名称" required><a-input v-model:value="newLpg.displayName" /></a-form-item>
      </a-form>
    </a-modal>

    <!-- Connect LPG -->
    <a-modal v-model:open="showConnectLpg" title="连接对端 LPG" @ok="doConnectLpg" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="对端 LPG OCID" required>
          <a-input v-model:value="connectLpgPeerId" placeholder="ocid1.localpeeringgateway..." />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- Rename modal -->
    <a-modal v-model:open="showRename" :title="'改名 — ' + renameLabel" @ok="doRename" :confirm-loading="editing">
      <a-form layout="vertical">
        <a-form-item label="新名称" required>
          <a-input v-model:value="renameValue" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- Edit subnet -->
    <a-modal v-model:open="showEditSubnet" title="编辑子网" @ok="doEditSubnet" :confirm-loading="editing" width="600px">
      <a-form layout="vertical">
        <a-form-item label="名称"><a-input v-model:value="editSubnet.displayName" /></a-form-item>
        <a-form-item label="路由表">
          <a-select v-model:value="editSubnet.routeTableId" allow-clear placeholder="保持不变">
            <a-select-option v-for="rt in data.rt" :key="rt.id" :value="rt.id">{{ rt.displayName }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="安全列表">
          <a-select v-model:value="editSubnet.securityListIds" mode="multiple" allow-clear placeholder="保持不变">
            <a-select-option v-for="sl in data.sl" :key="sl.id" :value="sl.id">{{ sl.displayName }}</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- Edit route table rules -->
    <a-modal v-model:open="showEditRt" title="编辑路由表规则" @ok="doEditRt" :confirm-loading="editing" width="900px" :mask-closable="false">
      <a-spin :spinning="rtDetailLoading">
        <div style="margin-bottom: 8px">
          <a-button type="primary" size="small" @click="addRouteRule">添加规则</a-button>
          <span style="margin-left: 12px; color: var(--text-sub); font-size: 12px">可选网关将从该 VCN 的 IGW/NAT/SG/LPG 中选择</span>
        </div>
        <a-table size="small" :data-source="editRtRules" :columns="rtRuleCols" :pagination="false" row-key="_k">
          <template #bodyCell="{ column, record, index }">
            <template v-if="column.key === 'destination'">
              <a-input v-model:value="record.destination" placeholder="如 0.0.0.0/0" size="small" />
            </template>
            <template v-else-if="column.key === 'destinationType'">
              <a-select v-model:value="record.destinationType" size="small" style="width: 100%">
                <a-select-option value="CIDR_BLOCK">CIDR_BLOCK</a-select-option>
                <a-select-option value="SERVICE_CIDR_BLOCK">SERVICE_CIDR_BLOCK</a-select-option>
              </a-select>
            </template>
            <template v-else-if="column.key === 'networkEntityId'">
              <a-select v-model:value="record.networkEntityId" size="small" style="width: 100%" show-search
                :filter-option="filterOption">
                <a-select-option v-for="g in vcnGateways" :key="g.id" :value="g.id">
                  [{{ typeLabel(g.type) }}] {{ g.displayName }}
                </a-select-option>
              </a-select>
            </template>
            <template v-else-if="column.key === 'description'">
              <a-input v-model:value="record.description" size="small" />
            </template>
            <template v-else-if="column.key === 'ruleAction'">
              <a-button size="small" danger @click="removeRouteRule(index)">移除</a-button>
            </template>
          </template>
        </a-table>
      </a-spin>
    </a-modal>

    <!-- View security list rules -->
    <a-modal v-model:open="showViewSl" :title="'安全列表规则 — ' + (slDetail?.displayName || '')" :footer="null" width="900px">
      <a-spin :spinning="slDetailLoading">
        <a-alert type="info" message="如需添加/删除规则，请到「实例管理」里对应实例的「网络」Tab 使用「安全规则」操作" show-icon style="margin-bottom: 12px" />
        <a-divider orientation="left" plain>入站规则 ({{ (slDetail?.ingressSecurityRules || []).length }})</a-divider>
        <a-table size="small" :data-source="slDetail?.ingressSecurityRules || []" :columns="slCols" :pagination="false" row-key="source" />
        <a-divider orientation="left" plain>出站规则 ({{ (slDetail?.egressSecurityRules || []).length }})</a-divider>
        <a-table size="small" :data-source="slDetail?.egressSecurityRules || []" :columns="slEgressCols" :pagination="false" row-key="destination" />
      </a-spin>
    </a-modal>

    <!-- Delete verify -->
    <a-modal v-model:open="showDelete" :title="'删除 ' + deleteLabel" @ok="doDelete" :confirm-loading="deleting" ok-text="确认删除" :ok-button-props="{ danger: true }">
      <a-alert type="warning" :message="`确定要删除 ${deleteTarget?.displayName || deleteTarget?.id}？需要 Telegram 验证码`" show-icon style="margin-bottom: 12px" />
      <a-input v-model:value="deleteCode" placeholder="TG 验证码" />
    </a-modal>

    <!-- Delete VCN preview + verify -->
    <a-modal v-model:open="showDeleteVcn" title="删除 VCN" @ok="doDeleteVcn" :confirm-loading="deleting" ok-text="确认删除" :ok-button-props="{ danger: true }">
      <a-alert type="error" message="将级联删除该 VCN 下全部子资源（子网、网关、路由表、安全列表等）" show-icon style="margin-bottom: 12px" />
      <div v-if="vcnPreview" style="max-height: 300px; overflow: auto; margin-bottom: 12px">
        <div v-for="(items, key) in vcnPreview" :key="key" style="margin-bottom: 6px">
          <strong>{{ key }}:</strong>
          <span v-if="!items || items.length === 0" style="color: #999">无</span>
          <div v-else>
            <div v-for="it in items" :key="it.id" style="font-size: 12px; margin-left: 8px">
              {{ it.displayName || it.id }}
            </div>
          </div>
        </div>
      </div>
      <a-checkbox v-model:checked="cascadeDelete">级联删除子资源</a-checkbox>
      <a-input v-model:value="deleteVcnCode" placeholder="TG 验证码" style="margin-top: 12px" />
    </a-modal>
  </a-drawer>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  listSubnets, createSubnet, deleteSubnet, updateSubnet,
  listInternetGateways, createInternetGateway, deleteInternetGateway, updateInternetGateway,
  listNatGateways, createNatGateway, deleteNatGateway, updateNatGateway,
  listServiceGateways, createServiceGateway, deleteServiceGateway, updateServiceGateway,
  listRouteTables, deleteRouteTable, getRouteTable, updateRouteTable,
  listSecurityLists, deleteSecurityList, getSecurityList,
  listLocalPeeringGateways, createLocalPeeringGateway, connectLocalPeeringGateway, deleteLocalPeeringGateway, updateLocalPeeringGateway,
  previewVcnDelete, deleteVcn, listVcnGateways,
} from '../api/vcn'
import { sendVerifyCode } from '../api/system'

const props = defineProps<{ open: boolean; userId: string; vcn: any }>()
const emit = defineEmits<{ (e: 'update:open', v: boolean): void; (e: 'changed'): void }>()

const activeTab = ref('subnet')
const loading = reactive({ subnet: false, igw: false, nat: false, sg: false, lpg: false, rt: false, sl: false })
const data = reactive<Record<string, any[]>>({ subnet: [], igw: [], nat: [], sg: [], lpg: [], rt: [], sl: [] })

const cols = {
  subnet: [
    { title: '名称', dataIndex: 'displayName', key: 'displayName', ellipsis: true },
    { title: 'CIDR', dataIndex: 'cidrBlock', key: 'cidrBlock', width: 140 },
    { title: '可用域', dataIndex: 'availabilityDomain', key: 'availabilityDomain', ellipsis: true },
    { title: '禁公网', dataIndex: 'prohibitPublicIpOnVnic', key: 'ppip', width: 80,
      customRender: ({ text }: any) => text ? '是' : '否' },
    { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
    { title: '操作', key: 'action', width: 90 },
  ],
  igw: [
    { title: '名称', dataIndex: 'displayName', key: 'displayName' },
    { title: '启用', dataIndex: 'isEnabled', key: 'isEnabled', width: 80,
      customRender: ({ text }: any) => text ? '是' : '否' },
    { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
    { title: '操作', key: 'action', width: 90 },
  ],
  nat: [
    { title: '名称', dataIndex: 'displayName', key: 'displayName' },
    { title: 'NAT IP', dataIndex: 'natIp', key: 'natIp', width: 140 },
    { title: '阻断流量', dataIndex: 'blockTraffic', key: 'blockTraffic', width: 90,
      customRender: ({ text }: any) => text ? '是' : '否' },
    { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
    { title: '操作', key: 'action', width: 90 },
  ],
  sg: [
    { title: '名称', dataIndex: 'displayName', key: 'displayName' },
    { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
    { title: '操作', key: 'action', width: 90 },
  ],
  lpg: [
    { title: '名称', dataIndex: 'displayName', key: 'displayName' },
    { title: '对等状态', dataIndex: 'peeringStatus', key: 'peeringStatus', width: 110 },
    { title: '对端 CIDR', dataIndex: 'peerAdvertisedCidr', key: 'peerAdvertisedCidr', width: 140 },
    { title: '操作', key: 'action', width: 170 },
  ],
  rt: [
    { title: '名称', dataIndex: 'displayName', key: 'displayName' },
    { title: '路由规则数', key: 'rules', width: 100,
      customRender: ({ record }: any) => (record.routeRules || []).length },
    { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
    { title: '操作', key: 'action', width: 90 },
  ],
  sl: [
    { title: '名称', dataIndex: 'displayName', key: 'displayName' },
    { title: '入站规则', dataIndex: 'ingressRulesCount', key: 'ingressRulesCount', width: 90 },
    { title: '出站规则', dataIndex: 'egressRulesCount', key: 'egressRulesCount', width: 90 },
    { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
    { title: '操作', key: 'action', width: 90 },
  ],
}

watch(() => props.open, (v) => {
  if (v && props.vcn?.id) {
    activeTab.value = 'subnet'
    loadSubnets()
  }
})

function onTab(k: string) {
  if (k === 'subnet') loadSubnets()
  else if (k === 'igw') loadIgw()
  else if (k === 'nat') loadNat()
  else if (k === 'sg') loadSg()
  else if (k === 'lpg') loadLpg()
  else if (k === 'rt') loadRt()
  else if (k === 'sl') loadSl()
}

async function wrap<T>(k: keyof typeof loading, fn: () => Promise<T>): Promise<T | null> {
  loading[k] = true
  try { return await fn() }
  catch (e: any) { message.error(e?.message || '加载失败'); return null }
  finally { loading[k] = false }
}

async function loadSubnets() {
  const r = await wrap('subnet', () => listSubnets({ id: props.userId, vcnId: props.vcn.id }))
  if (r) data.subnet = r.data || []
}
async function loadIgw() {
  const r = await wrap('igw', () => listInternetGateways({ id: props.userId, vcnId: props.vcn.id }))
  if (r) data.igw = r.data || []
}
async function loadNat() {
  const r = await wrap('nat', () => listNatGateways({ id: props.userId, vcnId: props.vcn.id }))
  if (r) data.nat = r.data || []
}
async function loadSg() {
  const r = await wrap('sg', () => listServiceGateways({ id: props.userId, vcnId: props.vcn.id }))
  if (r) data.sg = r.data || []
}
async function loadLpg() {
  const r = await wrap('lpg', () => listLocalPeeringGateways({ id: props.userId, vcnId: props.vcn.id }))
  if (r) data.lpg = r.data || []
}
async function loadRt() {
  const r = await wrap('rt', () => listRouteTables({ id: props.userId, vcnId: props.vcn.id }))
  if (r) data.rt = r.data || []
}
async function loadSl() {
  const r = await wrap('sl', () => listSecurityLists({ id: props.userId, vcnId: props.vcn.id }))
  if (r) data.sl = r.data || []
}

// ---- create ----
const creating = ref(false)
const showCreateSubnet = ref(false)
const newSubnet = reactive({ displayName: '', cidrBlock: '', availabilityDomain: '', routeTableId: '', prohibitPublicIp: false })
async function doCreateSubnet() {
  if (!newSubnet.displayName || !newSubnet.cidrBlock) return message.warning('请填写名称与 CIDR')
  creating.value = true
  try {
    await createSubnet({ id: props.userId, vcnId: props.vcn.id, ...newSubnet })
    message.success('创建成功')
    showCreateSubnet.value = false
    Object.assign(newSubnet, { displayName: '', cidrBlock: '', availabilityDomain: '', routeTableId: '', prohibitPublicIp: false })
    loadSubnets()
    emit('changed')
  } catch (e: any) { message.error(e?.message || '创建失败') }
  finally { creating.value = false }
}

const showCreateIgw = ref(false)
const newIgw = reactive({ displayName: '', isEnabled: true })
async function doCreateIgw() {
  if (!newIgw.displayName) return message.warning('请填写名称')
  creating.value = true
  try {
    await createInternetGateway({ id: props.userId, vcnId: props.vcn.id, ...newIgw })
    message.success('创建成功')
    showCreateIgw.value = false
    newIgw.displayName = ''; newIgw.isEnabled = true
    loadIgw()
    emit('changed')
  } catch (e: any) { message.error(e?.message || '创建失败') }
  finally { creating.value = false }
}

const showCreateNat = ref(false)
const newNat = reactive({ displayName: '' })
async function doCreateNat() {
  if (!newNat.displayName) return message.warning('请填写名称')
  creating.value = true
  try {
    await createNatGateway({ id: props.userId, vcnId: props.vcn.id, displayName: newNat.displayName })
    message.success('创建成功'); showCreateNat.value = false; newNat.displayName = ''
    loadNat(); emit('changed')
  } catch (e: any) { message.error(e?.message || '创建失败') }
  finally { creating.value = false }
}

const showCreateSg = ref(false)
const newSg = reactive({ displayName: '' })
async function doCreateSg() {
  if (!newSg.displayName) return message.warning('请填写名称')
  creating.value = true
  try {
    await createServiceGateway({ id: props.userId, vcnId: props.vcn.id, displayName: newSg.displayName })
    message.success('创建成功'); showCreateSg.value = false; newSg.displayName = ''
    loadSg(); emit('changed')
  } catch (e: any) { message.error(e?.message || '创建失败') }
  finally { creating.value = false }
}

const showCreateLpg = ref(false)
const newLpg = reactive({ displayName: '' })
async function doCreateLpg() {
  if (!newLpg.displayName) return message.warning('请填写名称')
  creating.value = true
  try {
    await createLocalPeeringGateway({ id: props.userId, vcnId: props.vcn.id, displayName: newLpg.displayName })
    message.success('创建成功'); showCreateLpg.value = false; newLpg.displayName = ''
    loadLpg(); emit('changed')
  } catch (e: any) { message.error(e?.message || '创建失败') }
  finally { creating.value = false }
}

const showConnectLpg = ref(false)
const connectLpgPeerId = ref('')
const connectLpgTarget = ref<any>(null)
function openConnectLpg(row: any) { connectLpgTarget.value = row; connectLpgPeerId.value = ''; showConnectLpg.value = true }
async function doConnectLpg() {
  if (!connectLpgPeerId.value) return message.warning('请输入对端 LPG OCID')
  creating.value = true
  try {
    await connectLocalPeeringGateway({ id: props.userId, lpgId: connectLpgTarget.value.id, peerId: connectLpgPeerId.value })
    message.success('已发起连接'); showConnectLpg.value = false
    loadLpg()
  } catch (e: any) { message.error(e?.message || '连接失败') }
  finally { creating.value = false }
}

// ---- delete ----
const showDelete = ref(false)
const deleting = ref(false)
const deleteType = ref('')
const deleteTarget = ref<any>(null)
const deleteCode = ref('')
const deleteLabel = ref('')

async function askDelete(type: string, row: any) {
  deleteType.value = type
  deleteTarget.value = row
  deleteCode.value = ''
  deleteLabel.value = ({
    subnet: '子网', igw: 'Internet 网关', nat: 'NAT 网关',
    sg: '服务网关', lpg: 'LPG', rt: '路由表', sl: '安全列表',
  } as any)[type] || type
  try { await sendVerifyCode('deleteVcn') } catch {}
  showDelete.value = true
}

async function doDelete() {
  if (!deleteCode.value) return message.warning('请输入验证码')
  deleting.value = true
  const id = props.userId
  const code = deleteCode.value
  const t = deleteTarget.value
  try {
    switch (deleteType.value) {
      case 'subnet': await deleteSubnet({ id, subnetId: t.id, verifyCode: code }); break
      case 'igw': await deleteInternetGateway({ id, igwId: t.id, verifyCode: code }); break
      case 'nat': await deleteNatGateway({ id, natId: t.id, verifyCode: code }); break
      case 'sg': await deleteServiceGateway({ id, sgId: t.id, verifyCode: code }); break
      case 'lpg': await deleteLocalPeeringGateway({ id, lpgId: t.id, verifyCode: code }); break
      case 'rt': await deleteRouteTable({ id, rtId: t.id, verifyCode: code }); break
      case 'sl': await deleteSecurityList({ id, slId: t.id, verifyCode: code }); break
    }
    message.success('删除成功')
    showDelete.value = false
    onTab(activeTab.value)
    emit('changed')
  } catch (e: any) { message.error(e?.message || '删除失败') }
  finally { deleting.value = false }
}

// ---- delete VCN ----
const showDeleteVcn = ref(false)
const vcnPreview = ref<any>(null)
const cascadeDelete = ref(true)
const deleteVcnCode = ref('')

async function openDeleteVcn() {
  vcnPreview.value = null
  deleteVcnCode.value = ''
  cascadeDelete.value = true
  showDeleteVcn.value = true
  try {
    const r = await previewVcnDelete({ id: props.userId, vcnId: props.vcn.id })
    vcnPreview.value = r.data
  } catch (e: any) { message.warning(e?.message || '预览失败，将继续，可直接删除') }
  try { await sendVerifyCode('deleteVcn') } catch {}
}

// ---- Rename ----
const showRename = ref(false)
const editing = ref(false)
const renameType = ref('')
const renameTarget = ref<any>(null)
const renameValue = ref('')
const renameLabel = ref('')

function openRename(type: string, row: any) {
  renameType.value = type
  renameTarget.value = row
  renameValue.value = row.displayName || ''
  renameLabel.value = ({ igw: 'IGW', nat: 'NAT', sg: 'SG', lpg: 'LPG' } as any)[type] || type
  showRename.value = true
}

async function doRename() {
  if (!renameValue.value.trim()) return message.warning('请输入名称')
  editing.value = true
  const id = props.userId
  const t = renameTarget.value
  try {
    switch (renameType.value) {
      case 'igw': await updateInternetGateway({ id, igwId: t.id, displayName: renameValue.value }); break
      case 'nat': await updateNatGateway({ id, natId: t.id, displayName: renameValue.value }); break
      case 'sg': await updateServiceGateway({ id, sgId: t.id, displayName: renameValue.value }); break
      case 'lpg': await updateLocalPeeringGateway({ id, lpgId: t.id, displayName: renameValue.value }); break
    }
    message.success('已更新')
    showRename.value = false
    onTab(activeTab.value)
  } catch (e: any) { message.error(e?.message || '更新失败') }
  finally { editing.value = false }
}

// ---- Toggle enable/block ----
const togglingId = ref('')
async function toggleIgw(row: any) {
  togglingId.value = row.id
  try {
    await updateInternetGateway({ id: props.userId, igwId: row.id, isEnabled: !row.isEnabled })
    message.success(row.isEnabled ? '已禁用' : '已启用')
    loadIgw()
  } catch (e: any) { message.error(e?.message || '切换失败') }
  finally { togglingId.value = '' }
}
async function toggleNat(row: any) {
  togglingId.value = row.id
  try {
    await updateNatGateway({ id: props.userId, natId: row.id, blockTraffic: !row.blockTraffic })
    message.success(row.blockTraffic ? '已放行' : '已阻断')
    loadNat()
  } catch (e: any) { message.error(e?.message || '切换失败') }
  finally { togglingId.value = '' }
}
async function toggleSg(row: any) {
  togglingId.value = row.id
  try {
    await updateServiceGateway({ id: props.userId, sgId: row.id, blockTraffic: !row.blockTraffic })
    message.success(row.blockTraffic ? '已放行' : '已阻断')
    loadSg()
  } catch (e: any) { message.error(e?.message || '切换失败') }
  finally { togglingId.value = '' }
}

// ---- Edit subnet ----
const showEditSubnet = ref(false)
const editSubnet = reactive<{ id: string; displayName: string; routeTableId: string | undefined; securityListIds: string[] }>(
  { id: '', displayName: '', routeTableId: undefined, securityListIds: [] }
)

async function openEditSubnet(row: any) {
  editSubnet.id = row.id
  editSubnet.displayName = row.displayName
  editSubnet.routeTableId = row.routeTableId
  editSubnet.securityListIds = Array.isArray(row.securityListIds) ? [...row.securityListIds] : []
  // ensure rt/sl options loaded
  if (!data.rt.length) loadRt()
  if (!data.sl.length) loadSl()
  showEditSubnet.value = true
}

async function doEditSubnet() {
  editing.value = true
  try {
    await updateSubnet({
      id: props.userId,
      subnetId: editSubnet.id,
      displayName: editSubnet.displayName,
      routeTableId: editSubnet.routeTableId || undefined,
      securityListIds: editSubnet.securityListIds.length ? editSubnet.securityListIds : undefined,
    })
    message.success('已更新')
    showEditSubnet.value = false
    loadSubnets()
  } catch (e: any) { message.error(e?.message || '更新失败') }
  finally { editing.value = false }
}

// ---- Edit route table ----
const showEditRt = ref(false)
const rtDetailLoading = ref(false)
const editingRtId = ref('')
const editRtRules = ref<any[]>([])
const vcnGateways = ref<any[]>([])
const rtRuleCols = [
  { title: '目标 (Destination)', key: 'destination', width: 180 },
  { title: '类型', key: 'destinationType', width: 180 },
  { title: '下一跳网关', key: 'networkEntityId' },
  { title: '描述', key: 'description', width: 160 },
  { title: '操作', key: 'ruleAction', width: 80 },
]
function typeLabel(t: string) {
  return { internetGateway: 'IGW', natGateway: 'NAT', serviceGateway: 'SG', localPeeringGateway: 'LPG' }[t] || t
}
function filterOption(input: string, option: any) {
  return (option.children?.[0]?.children || '').toLowerCase().includes(input.toLowerCase())
}

async function openEditRt(row: any) {
  editingRtId.value = row.id
  editRtRules.value = []
  vcnGateways.value = []
  showEditRt.value = true
  rtDetailLoading.value = true
  try {
    const [d, g] = await Promise.all([
      getRouteTable({ id: props.userId, rtId: row.id }),
      listVcnGateways({ id: props.userId, vcnId: props.vcn.id }),
    ])
    editRtRules.value = ((d.data?.routeRules) || []).map((r: any, i: number) => ({ ...r, _k: `k_${i}` }))
    vcnGateways.value = g.data || []
  } catch (e: any) { message.error(e?.message || '加载失败') }
  finally { rtDetailLoading.value = false }
}
function addRouteRule() {
  editRtRules.value.push({
    destination: '0.0.0.0/0',
    destinationType: 'CIDR_BLOCK',
    networkEntityId: vcnGateways.value[0]?.id || '',
    description: '',
    _k: 'k_' + Date.now() + Math.random(),
  })
}
function removeRouteRule(i: number) { editRtRules.value.splice(i, 1) }
async function doEditRt() {
  for (const r of editRtRules.value) {
    if (!r.destination || !r.networkEntityId) return message.warning('每条规则必须填写目标和下一跳网关')
  }
  editing.value = true
  try {
    const rules = editRtRules.value.map(r => ({
      destination: r.destination,
      destinationType: r.destinationType,
      networkEntityId: r.networkEntityId,
      description: r.description,
    }))
    await updateRouteTable({ id: props.userId, rtId: editingRtId.value, routeRules: rules })
    message.success('路由规则已更新')
    showEditRt.value = false
    loadRt()
  } catch (e: any) { message.error(e?.message || '更新失败') }
  finally { editing.value = false }
}

// ---- View SL ----
const showViewSl = ref(false)
const slDetail = ref<any>(null)
const slDetailLoading = ref(false)
const slCols = [
  { title: '协议', dataIndex: 'protocol', key: 'protocol', width: 70 },
  { title: '来源', dataIndex: 'source', key: 'source', width: 160 },
  { title: '来源类型', dataIndex: 'sourceType', key: 'sourceType', width: 130 },
  { title: '状态', dataIndex: 'isStateless', key: 'isStateless', width: 70,
    customRender: ({ text }: any) => text ? '无状态' : '有状态' },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
]
const slEgressCols = [
  { title: '协议', dataIndex: 'protocol', key: 'protocol', width: 70 },
  { title: '目的', dataIndex: 'destination', key: 'destination', width: 160 },
  { title: '目的类型', dataIndex: 'destinationType', key: 'destinationType', width: 130 },
  { title: '状态', dataIndex: 'isStateless', key: 'isStateless', width: 70,
    customRender: ({ text }: any) => text ? '无状态' : '有状态' },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
]

async function openViewSl(row: any) {
  slDetail.value = null
  showViewSl.value = true
  slDetailLoading.value = true
  try {
    const r = await getSecurityList({ id: props.userId, slId: row.id })
    slDetail.value = r.data
  } catch (e: any) { message.error(e?.message || '加载失败') }
  finally { slDetailLoading.value = false }
}

async function doDeleteVcn() {
  if (!deleteVcnCode.value) return message.warning('请输入验证码')
  Modal.confirm({
    title: '再次确认',
    content: '此操作不可恢复，确定要删除该 VCN 吗？',
    okText: '删除',
    okType: 'danger',
    onOk: async () => {
      deleting.value = true
      try {
        await deleteVcn({ id: props.userId, vcnId: props.vcn.id, cascade: cascadeDelete.value, verifyCode: deleteVcnCode.value })
        message.success('VCN 已删除')
        showDeleteVcn.value = false
        emit('update:open', false)
        emit('changed')
      } catch (e: any) { message.error(e?.message || '删除 VCN 失败') }
      finally { deleting.value = false }
    },
  })
}
</script>

<style scoped>
.op-row { display: flex; gap: 8px; margin-bottom: 10px; }
</style>
