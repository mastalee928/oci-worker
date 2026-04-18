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
              <a-button size="small" danger @click="askDelete('subnet', record)">删除</a-button>
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
              <a-button size="small" danger @click="askDelete('igw', record)">删除</a-button>
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
              <a-button size="small" danger @click="askDelete('nat', record)">删除</a-button>
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
              <a-button size="small" danger @click="askDelete('sg', record)">删除</a-button>
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
              <a-button size="small" danger @click="askDelete('rt', record)">删除</a-button>
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
              <a-button size="small" danger @click="askDelete('sl', record)">删除</a-button>
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
  listSubnets, createSubnet, deleteSubnet,
  listInternetGateways, createInternetGateway, deleteInternetGateway,
  listNatGateways, createNatGateway, deleteNatGateway,
  listServiceGateways, createServiceGateway, deleteServiceGateway,
  listRouteTables, deleteRouteTable,
  listSecurityLists, deleteSecurityList,
  listLocalPeeringGateways, createLocalPeeringGateway, connectLocalPeeringGateway, deleteLocalPeeringGateway,
  previewVcnDelete, deleteVcn,
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
