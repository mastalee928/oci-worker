<template>
  <a-drawer :mask-closable="false" :keyboard="false"
    :open="open"
    @update:open="(v: boolean) => emit('update:open', v)"
    :title="`VCN 管理 — ${vcn?.displayName || ''}`"
    width="960"
    :z-index="VCN_MANAGER_DRAWER_Z_INDEX"
    :wrap-class-name="VCN_MANAGER_DRAWER_WRAP_CLASS"
    destroy-on-close
  >
    <a-descriptions :column="2" size="small" bordered style="margin-bottom: 16px">
      <a-descriptions-item label="VCN 名称">
        <span>{{ vcn?.displayName }}</span>
        <a-button type="link" size="small" @click="openRename('vcn', vcn)" title="改名">
          <i class="ri-edit-line"></i>
        </a-button>
      </a-descriptions-item>
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
          <a-button size="small" @click="loadSubnets(true)" :loading="loading.subnet">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.subnet" :data-source="data.subnet" :columns="cols.subnet" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'displayName'">
              <span>{{ record.displayName }}</span>
              <a-button type="link" size="small" @click="openRename('subnet', record)" title="改名">
                <i class="ri-edit-line"></i>
              </a-button>
            </template>
            <template v-else-if="column.key === 'action'">
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
          <a-button size="small" @click="loadIgw(true)" :loading="loading.igw">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.igw" :data-source="data.igw" :columns="cols.igw" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'displayName'">
              <span>{{ record.displayName }}</span>
              <a-button type="link" size="small" @click="openRename('igw', record)" title="改名">
                <i class="ri-edit-line"></i>
              </a-button>
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <a-button size="small" type="primary" @click="openEditIgw(record)">编辑</a-button>
                <a-button size="small" @click="toggleIgw(record)" :loading="togglingId === record.id">
                  {{ record.isEnabled ? '禁用' : '启用' }}
                </a-button>
                <a-button size="small" danger @click="askDelete('igw', record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="nat" tab="NAT 网关">
        <div class="op-row">
          <a-button type="primary" size="small" @click="showCreateNat = true">创建 NAT</a-button>
          <a-button size="small" @click="loadNat(true)" :loading="loading.nat">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.nat" :data-source="data.nat" :columns="cols.nat" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'displayName'">
              <span>{{ record.displayName }}</span>
              <a-button type="link" size="small" @click="openRename('nat', record)" title="改名">
                <i class="ri-edit-line"></i>
              </a-button>
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <a-button size="small" @click="toggleNat(record)" :loading="togglingId === record.id">
                  {{ record.blockTraffic ? '放行流量' : '阻断流量' }}
                </a-button>
                <a-button size="small" danger @click="askDelete('nat', record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="sg" tab="服务网关">
        <div class="op-row">
          <a-button type="primary" size="small" @click="showCreateSg = true">创建 SG</a-button>
          <a-button size="small" @click="loadSg(true)" :loading="loading.sg">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.sg" :data-source="data.sg" :columns="cols.sg" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'displayName'">
              <span>{{ record.displayName }}</span>
              <a-button type="link" size="small" @click="openRename('sg', record)" title="改名">
                <i class="ri-edit-line"></i>
              </a-button>
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <a-button size="small" @click="toggleSg(record)" :loading="togglingId === record.id">
                  {{ record.blockTraffic ? '放行流量' : '阻断流量' }}
                </a-button>
                <a-button size="small" danger @click="askDelete('sg', record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="lpg" tab="LPG 对等">
        <div class="op-row">
          <a-button type="primary" size="small" @click="showCreateLpg = true">创建 LPG</a-button>
          <a-button size="small" @click="loadLpg(true)" :loading="loading.lpg">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.lpg" :data-source="data.lpg" :columns="cols.lpg" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'displayName'">
              <span>{{ record.displayName }}</span>
              <a-button type="link" size="small" @click="openRename('lpg', record)" title="改名">
                <i class="ri-edit-line"></i>
              </a-button>
            </template>
            <template v-else-if="column.key === 'action'">
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
          <a-button type="primary" size="small" @click="showCreateRt = true">创建路由表</a-button>
          <a-button size="small" @click="loadRt(true)" :loading="loading.rt">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.rt" :data-source="data.rt" :columns="cols.rt" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'displayName'">
              {{ record.displayName }}
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <a-button size="small" type="primary" @click="openEditRt(record)">编辑规则</a-button>
                <a-button size="small" danger @click="askDelete('rt', record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="sl" tab="安全列表">
        <div class="op-row">
          <a-button size="small" @click="loadSl(true)" :loading="loading.sl">刷新</a-button>
        </div>
        <a-table size="small" :loading="loading.sl" :data-source="data.sl" :columns="cols.sl" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'displayName'">
              {{ record.displayName }}
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <a-button size="small" type="primary" @click="openEditSl(record)">编辑规则</a-button>
                <a-button size="small" danger @click="askDelete('sl', record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
    </a-tabs>

    <!-- Create Subnet -->
    <a-modal v-bind="vcnManagerModalProps" :mask-closable="false" :keyboard="false" v-model:open="showCreateSubnet" title="创建子网" @ok="doCreateSubnet" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="名称" required><a-input v-model:value="newSubnet.displayName" /></a-form-item>
        <a-form-item label="CIDR" required><a-input v-model:value="newSubnet.cidrBlock" placeholder="如 10.0.1.0/24" /></a-form-item>
        <a-form-item label="可用域（留空为区域子网）"><a-input v-model:value="newSubnet.availabilityDomain" /></a-form-item>
        <a-form-item label="路由表 ID（可选）"><a-input v-model:value="newSubnet.routeTableId" /></a-form-item>
        <a-form-item><a-checkbox v-model:checked="newSubnet.prohibitPublicIp">私有子网（禁止公网 IP）</a-checkbox></a-form-item>
      </a-form>
    </a-modal>

    <!-- Create IGW -->
    <a-modal v-bind="vcnManagerModalProps" :mask-closable="false" :keyboard="false" v-model:open="showCreateIgw" title="创建 Internet 网关" @ok="doCreateIgw" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="名称" required><a-input v-model:value="newIgw.displayName" /></a-form-item>
        <a-form-item><a-checkbox v-model:checked="newIgw.isEnabled">启用</a-checkbox></a-form-item>
      </a-form>
    </a-modal>

    <!-- Create NAT -->
    <a-modal v-bind="vcnManagerModalProps" :mask-closable="false" :keyboard="false" v-model:open="showCreateNat" title="创建 NAT 网关" @ok="doCreateNat" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="名称" required><a-input v-model:value="newNat.displayName" /></a-form-item>
      </a-form>
    </a-modal>

    <!-- Create SG -->
    <a-modal v-bind="vcnManagerModalProps" :mask-closable="false" :keyboard="false" v-model:open="showCreateSg" title="创建服务网关" @ok="doCreateSg" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="名称" required><a-input v-model:value="newSg.displayName" /></a-form-item>
        <a-alert type="info" message="默认添加该区域的全部 Oracle Services Network 服务" show-icon />
      </a-form>
    </a-modal>

    <!-- Create LPG -->
    <a-modal v-bind="vcnManagerModalProps" :mask-closable="false" :keyboard="false" v-model:open="showCreateLpg" title="创建 Local Peering Gateway" @ok="doCreateLpg" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="名称" required><a-input v-model:value="newLpg.displayName" /></a-form-item>
      </a-form>
    </a-modal>

    <!-- Create Route Table -->
    <a-modal v-bind="vcnManagerModalProps" :mask-closable="false" :keyboard="false" v-model:open="showCreateRt" title="创建路由表" @ok="doCreateRt" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="名称" required><a-input v-model:value="newRt.displayName" :maxlength="255" /></a-form-item>
      </a-form>
    </a-modal>

    <!-- Connect LPG -->
    <a-modal v-bind="vcnManagerModalProps" :mask-closable="false" :keyboard="false" v-model:open="showConnectLpg" title="连接对端 LPG" @ok="doConnectLpg" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="对端 LPG OCID" required>
          <a-input v-model:value="connectLpgPeerId" placeholder="ocid1.localpeeringgateway..." />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- Rename modal -->
    <a-modal v-bind="vcnManagerModalProps" :mask-closable="false" :keyboard="false" v-model:open="showRename" :title="'改名 — ' + renameLabel" @ok="doRename" :confirm-loading="editing">
      <a-form layout="vertical">
        <a-form-item label="新名称" required>
          <a-input v-model:value="renameValue" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- Edit subnet -->
    <a-modal v-bind="vcnManagerModalProps" :mask-closable="false" :keyboard="false" v-model:open="showEditSubnet" title="编辑子网" @ok="doEditSubnet" :confirm-loading="editing" width="600px">
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

    <RouteTableRulesManager
      v-model:open="showEditRt"
      :user-id="props.userId"
      :vcn="props.vcn"
      :route-table="editingRt"
      :oci-region="props.ociRegion"
      @saved="onRouteRulesSaved"
    />

    <!-- Edit security list rules -->
    <a-modal v-bind="vcnManagerModalProps" :keyboard="false" v-model:open="showEditSl" :title="'安全规则 — ' + (slDetail?.displayName || '')" :footer="null" width="1000px" :mask-closable="false">
      <a-spin :spinning="slDetailLoading">
        <div style="margin-bottom: 12px; display: flex; gap: 8px; flex-wrap: wrap">
          <a-button type="primary" size="small" @click="openAddSlRule('ingress')">添加入站规则</a-button>
          <a-button type="primary" size="small" @click="openAddSlRule('egress')">添加出站规则</a-button>
          <a-button size="small" @click="reloadSl">刷新</a-button>
        </div>
        <a-divider orientation="left" plain>入站规则 ({{ (slDetail?.ingressSecurityRules || []).length }})</a-divider>
        <a-table size="small" :data-source="slDetail?.ingressSecurityRules || []" :columns="slIngressCols" :pagination="false" row-key="index">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'protocol'">{{ protocolLabel(record.protocol) }}</template>
            <template v-else-if="column.key === 'isStateless'">{{ record.isStateless ? '无状态' : '有状态' }}</template>
            <template v-else-if="column.key === 'ruleAction'">
              <a-popconfirm title="确定删除？" :z-index="VCN_MANAGER_CONFIRM_MODAL_Z_INDEX" @confirm="doDeleteSlRule('ingress', record.index)">
                <a-button size="small" danger type="link">删除</a-button>
              </a-popconfirm>
            </template>
          </template>
        </a-table>
        <a-divider orientation="left" plain>出站规则 ({{ (slDetail?.egressSecurityRules || []).length }})</a-divider>
        <a-table size="small" :data-source="slDetail?.egressSecurityRules || []" :columns="slEgressCols" :pagination="false" row-key="index">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'protocol'">{{ protocolLabel(record.protocol) }}</template>
            <template v-else-if="column.key === 'isStateless'">{{ record.isStateless ? '无状态' : '有状态' }}</template>
            <template v-else-if="column.key === 'ruleAction'">
              <a-popconfirm title="确定删除？" :z-index="VCN_MANAGER_CONFIRM_MODAL_Z_INDEX" @confirm="doDeleteSlRule('egress', record.index)">
                <a-button size="small" danger type="link">删除</a-button>
              </a-popconfirm>
            </template>
          </template>
        </a-table>
      </a-spin>
    </a-modal>

    <!-- Add security list rule -->
    <a-modal v-bind="vcnManagerModalProps" :mask-closable="false" :keyboard="false" v-model:open="showAddSlRule" :title="(addSlForm.direction === 'ingress' ? '添加入站规则' : '添加出站规则')"
      @ok="doAddSlRule" :confirm-loading="editing">
      <a-form layout="vertical">
        <a-form-item label="协议" required>
          <a-select v-model:value="addSlForm.protocol">
            <a-select-option value="all">全部</a-select-option>
            <a-select-option value="6">TCP (6)</a-select-option>
            <a-select-option value="17">UDP (17)</a-select-option>
            <a-select-option value="1">ICMP (1)</a-select-option>
            <a-select-option value="58">ICMPv6 (58)</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item :label="addSlForm.direction === 'ingress' ? '来源 CIDR' : '目的 CIDR'" required>
          <a-input v-model:value="addSlForm.source" placeholder="0.0.0.0/0 或 ::/0" />
        </a-form-item>
        <a-row :gutter="12" v-if="addSlForm.protocol === '6' || addSlForm.protocol === '17'">
          <a-col :span="12">
            <a-form-item label="端口起 (可空=全部)">
              <a-input v-model:value="addSlForm.portMin" placeholder="如 22" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="端口止 (空=等于起)">
              <a-input v-model:value="addSlForm.portMax" placeholder="如 80" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="描述">
          <a-input v-model:value="addSlForm.description" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- Edit IGW: setup default routes -->
    <a-modal v-bind="vcnManagerModalProps" :mask-closable="false" :keyboard="false" v-model:open="showEditIgw" :title="'编辑 IGW — ' + (editIgwTarget?.displayName || '')" @ok="doSetupIgwRoutes"
      :confirm-loading="editing" ok-text="应用到默认路由表">
      <a-alert type="info" show-icon style="margin-bottom: 12px"
        message="将在该 VCN 的默认路由表中添加指向此 IGW 的默认路由；已存在的规则会自动跳过" />
      <a-form layout="vertical">
        <a-form-item label="目标网关 (Internet Gateway)">
          <a-input :value="editIgwTarget?.displayName" disabled />
        </a-form-item>
        <a-form-item>
          <a-checkbox v-model:checked="igwRouteIpv4" disabled>
            0.0.0.0/0 → 该 IGW （IPv4 默认路由）
          </a-checkbox>
        </a-form-item>
        <a-form-item>
          <a-checkbox v-model:checked="igwRouteIpv6">
            ::/0 → 该 IGW （IPv6 默认路由）
          </a-checkbox>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- Delete verify：不可点遮罩/Esc 关闭；保留底部「取消」与右上角 X -->
    <a-modal
      v-bind="vcnManagerModalProps"
      v-model:open="showDelete"
      :title="'删除 ' + deleteLabel"
      :width="400"
      :mask-closable="false"
      :keyboard="false"
      destroy-on-close
      @ok="doDelete"
      :confirm-loading="deleting"
      ok-text="确认删除"
      :ok-button-props="{ danger: true }"
    >
      <a-alert type="warning" :message="`确定要删除 ${deleteTarget?.displayName || deleteTarget?.id}？需要 Telegram 验证码`" show-icon style="margin-bottom: 12px" />
      <a-input v-model:value="deleteCode" placeholder="TG 验证码" size="large" :maxlength="6" allow-clear />
      <div class="vcn-delete-verify-footer">
        <span>验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="deleteCodeSending" @click="resendDeleteVerifyCode">重新发送</a-button>
      </div>
    </a-modal>

    <!-- Delete VCN preview + verify -->
    <a-modal v-bind="vcnManagerModalProps" :mask-closable="false" :keyboard="false" v-model:open="showDeleteVcn" title="删除 VCN" @ok="doDeleteVcn" :confirm-loading="deleting" ok-text="确认删除" :ok-button-props="{ danger: true }">
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
      <a-input v-model:value="deleteVcnCode" placeholder="TG 验证码" size="large" :maxlength="6" allow-clear style="margin-top: 12px" />
      <div class="vcn-delete-verify-footer">
        <span>验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="deleteCodeSending" @click="resendDeleteVerifyCode">重新发送</a-button>
      </div>
    </a-modal>
  </a-drawer>
</template>

<script setup lang="ts">
import { ref, reactive, watch, computed, onUnmounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import RouteTableRulesManager from '../components/vcn/RouteTableRulesManager.vue'
import {
  listSubnets, createSubnet, deleteSubnet, updateSubnet,
  listInternetGateways, createInternetGateway, deleteInternetGateway, updateInternetGateway, setupIgwDefaultRoutes,
  listNatGateways, createNatGateway, deleteNatGateway, updateNatGateway,
  listServiceGateways, createServiceGateway, deleteServiceGateway, updateServiceGateway,
  listRouteTables, createRouteTable, deleteRouteTable,
  listSecurityLists, deleteSecurityList, getSecurityList, addSecurityListRule, deleteSecurityListRule,
  listLocalPeeringGateways, createLocalPeeringGateway, connectLocalPeeringGateway, deleteLocalPeeringGateway, updateLocalPeeringGateway,
  previewVcnDelete, deleteVcn, updateVcn,
} from '../api/vcn'
import { sendVerifyCode } from '../api/system'
import {
  VCN_MANAGER_CONFIRM_MODAL_WRAP_CLASS,
  VCN_MANAGER_CONFIRM_MODAL_Z_INDEX,
  VCN_MANAGER_DRAWER_WRAP_CLASS,
  VCN_MANAGER_DRAWER_Z_INDEX,
  VCN_MANAGER_MODAL_WRAP_CLASS,
  VCN_MANAGER_MODAL_Z_INDEX,
} from '../utils/overlayZIndex'

const props = withDefaults(defineProps<{
  open: boolean
  userId: string
  vcn: any
  ociRegion?: string
  initialTab?: string
  targetResourceId?: string
}>(), { ociRegion: '', initialTab: '', targetResourceId: '' })
const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'changed'): void
  (e: 'editing-overlay-change', v: boolean): void
}>()

const vcnManagerModalProps = {
  zIndex: VCN_MANAGER_MODAL_Z_INDEX,
  wrapClassName: VCN_MANAGER_MODAL_WRAP_CLASS,
}

const ociBase = computed((): { id: string; region?: string } => {
  const z = props.ociRegion?.trim()
  return z ? { id: props.userId, region: z } : { id: props.userId }
})

const activeTab = ref('subnet')
const loading = reactive({ subnet: false, igw: false, nat: false, sg: false, lpg: false, rt: false, sl: false })
const loadingSeq = reactive({ subnet: 0, igw: 0, nat: 0, sg: 0, lpg: 0, rt: 0, sl: 0 })
const contextSeq = ref(0)
const data = reactive<Record<string, any[]>>({ subnet: [], igw: [], nat: [], sg: [], lpg: [], rt: [], sl: [] })
const dataKeys = ['subnet', 'igw', 'nat', 'sg', 'lpg', 'rt', 'sl'] as const
const targetOpenKey = ref('')

const cols = {
  subnet: [
    { title: '名称', key: 'displayName', ellipsis: true },
    { title: 'CIDR', dataIndex: 'cidrBlock', key: 'cidrBlock', width: 140 },
    { title: '可用域', dataIndex: 'availabilityDomain', key: 'availabilityDomain', ellipsis: true },
    { title: '禁公网', dataIndex: 'prohibitPublicIpOnVnic', key: 'ppip', width: 80,
      customRender: ({ text }: any) => text ? '是' : '否' },
    { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
    { title: '操作', key: 'action', width: 150 },
  ],
  igw: [
    { title: '名称', key: 'displayName' },
    { title: '启用', dataIndex: 'isEnabled', key: 'isEnabled', width: 80,
      customRender: ({ text }: any) => text ? '是' : '否' },
    { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
    { title: '操作', key: 'action', width: 220 },
  ],
  nat: [
    { title: '名称', key: 'displayName' },
    { title: 'NAT IP', dataIndex: 'natIp', key: 'natIp', width: 140 },
    { title: '阻断流量', dataIndex: 'blockTraffic', key: 'blockTraffic', width: 90,
      customRender: ({ text }: any) => text ? '是' : '否' },
    { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
    { title: '操作', key: 'action', width: 180 },
  ],
  sg: [
    { title: '名称', key: 'displayName' },
    { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
    { title: '操作', key: 'action', width: 180 },
  ],
  lpg: [
    { title: '名称', key: 'displayName' },
    { title: '对等状态', dataIndex: 'peeringStatus', key: 'peeringStatus', width: 110 },
    { title: '对端 CIDR', dataIndex: 'peerAdvertisedCidr', key: 'peerAdvertisedCidr', width: 140 },
    { title: '操作', key: 'action', width: 170 },
  ],
  rt: [
    { title: '名称', key: 'displayName' },
    { title: '路由规则数', key: 'rules', width: 100,
      customRender: ({ record }: any) => (record.routeRules || []).length },
    { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
    { title: '操作', key: 'action', width: 150 },
  ],
  sl: [
    { title: '名称', key: 'displayName' },
    { title: '入站规则', dataIndex: 'ingressRulesCount', key: 'ingressRulesCount', width: 90 },
    { title: '出站规则', dataIndex: 'egressRulesCount', key: 'egressRulesCount', width: 90 },
    { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
    { title: '操作', key: 'action', width: 170 },
  ],
}

watch(
  () => [props.open, props.userId, props.vcn?.id, props.ociRegion, props.initialTab, props.targetResourceId] as const,
  ([open]) => {
    if (open && props.vcn?.id) {
      resetLoadedData()
      targetOpenKey.value = ''
      const tab = normalizeInitialTab(props.initialTab)
      activeTab.value = tab
      onTab(tab)
    }
  },
  { immediate: true },
)

function resetLoadedData() {
  contextSeq.value += 1
  dataKeys.forEach((key) => {
    loadingSeq[key] += 1
    loading[key] = false
    data[key] = []
  })
}

function currentContextKey(vcnId: string) {
  return `${props.userId || ''}|${props.ociRegion?.trim() || ''}|${vcnId}`
}

function isCurrentVcnContext(vcnId: string, contextKey: string, seq: number) {
  return props.open && props.vcn?.id === vcnId && currentContextKey(vcnId) === contextKey && contextSeq.value === seq
}

function onTab(k: string, force = false) {
  if (k === 'subnet') loadSubnets(force)
  else if (k === 'igw') loadIgw(force)
  else if (k === 'nat') loadNat(force)
  else if (k === 'sg') loadSg(force)
  else if (k === 'lpg') loadLpg(force)
  else if (k === 'rt') loadRt(force)
  else if (k === 'sl') loadSl(force)
}

function normalizeInitialTab(tab: string | undefined) {
  return dataKeys.includes(tab as any) ? tab as typeof dataKeys[number] : 'subnet'
}

function maybeOpenTargetResource(tab: 'subnet' | 'rt') {
  const targetId = props.targetResourceId?.trim()
  if (!targetId || props.initialTab !== tab) return
  const key = `${props.userId || ''}|${props.ociRegion?.trim() || ''}|${props.vcn?.id || ''}|${tab}|${targetId}`
  if (targetOpenKey.value === key) return
  const row = data[tab].find((item: any) => item?.id === targetId)
  if (!row) return
  targetOpenKey.value = key
  if (tab === 'subnet') void openEditSubnet(row)
  else void openEditRt(row)
}

async function wrap<T>(k: keyof typeof loading, fn: () => Promise<T>, stillRelevant: () => boolean): Promise<T | null> {
  const seq = ++loadingSeq[k]
  loading[k] = true
  try { return await fn() }
  catch (e: any) {
    if (stillRelevant()) message.error(e?.message || '加载失败')
    return null
  } finally {
    if (loadingSeq[k] === seq) loading[k] = false
  }
}

async function loadSubnets(force = false) {
  const vcnId = props.vcn?.id
  if (!vcnId) return
  const contextKey = currentContextKey(vcnId)
  const seq = contextSeq.value
  const r = await wrap('subnet', () => listSubnets({ ...ociBase.value, vcnId, force }), () => isCurrentVcnContext(vcnId, contextKey, seq))
  if (r && isCurrentVcnContext(vcnId, contextKey, seq)) {
    data.subnet = r.data || []
    maybeOpenTargetResource('subnet')
  }
}
async function loadIgw(force = false) {
  const vcnId = props.vcn?.id
  if (!vcnId) return
  const contextKey = currentContextKey(vcnId)
  const seq = contextSeq.value
  const r = await wrap('igw', () => listInternetGateways({ ...ociBase.value, vcnId, force }), () => isCurrentVcnContext(vcnId, contextKey, seq))
  if (r && isCurrentVcnContext(vcnId, contextKey, seq)) data.igw = r.data || []
}
async function loadNat(force = false) {
  const vcnId = props.vcn?.id
  if (!vcnId) return
  const contextKey = currentContextKey(vcnId)
  const seq = contextSeq.value
  const r = await wrap('nat', () => listNatGateways({ ...ociBase.value, vcnId, force }), () => isCurrentVcnContext(vcnId, contextKey, seq))
  if (r && isCurrentVcnContext(vcnId, contextKey, seq)) data.nat = r.data || []
}
async function loadSg(force = false) {
  const vcnId = props.vcn?.id
  if (!vcnId) return
  const contextKey = currentContextKey(vcnId)
  const seq = contextSeq.value
  const r = await wrap('sg', () => listServiceGateways({ ...ociBase.value, vcnId, force }), () => isCurrentVcnContext(vcnId, contextKey, seq))
  if (r && isCurrentVcnContext(vcnId, contextKey, seq)) data.sg = r.data || []
}
async function loadLpg(force = false) {
  const vcnId = props.vcn?.id
  if (!vcnId) return
  const contextKey = currentContextKey(vcnId)
  const seq = contextSeq.value
  const r = await wrap('lpg', () => listLocalPeeringGateways({ ...ociBase.value, vcnId, force }), () => isCurrentVcnContext(vcnId, contextKey, seq))
  if (r && isCurrentVcnContext(vcnId, contextKey, seq)) data.lpg = r.data || []
}
async function loadRt(force = false) {
  const vcnId = props.vcn?.id
  if (!vcnId) return
  const contextKey = currentContextKey(vcnId)
  const seq = contextSeq.value
  const r = await wrap('rt', () => listRouteTables({ ...ociBase.value, vcnId, force }), () => isCurrentVcnContext(vcnId, contextKey, seq))
  if (r && isCurrentVcnContext(vcnId, contextKey, seq)) {
    data.rt = r.data || []
    maybeOpenTargetResource('rt')
  }
}
async function loadSl(force = false) {
  const vcnId = props.vcn?.id
  if (!vcnId) return
  const contextKey = currentContextKey(vcnId)
  const seq = contextSeq.value
  const r = await wrap('sl', () => listSecurityLists({ ...ociBase.value, vcnId, force }), () => isCurrentVcnContext(vcnId, contextKey, seq))
  if (r && isCurrentVcnContext(vcnId, contextKey, seq)) data.sl = r.data || []
}

// ---- create ----
const creating = ref(false)
const showCreateSubnet = ref(false)
const newSubnet = reactive({ displayName: '', cidrBlock: '', availabilityDomain: '', routeTableId: '', prohibitPublicIp: false })
async function doCreateSubnet() {
  if (!newSubnet.displayName || !newSubnet.cidrBlock) return message.warning('请填写名称与 CIDR')
  creating.value = true
  try {
    await createSubnet({ ...ociBase.value, vcnId: props.vcn.id, ...newSubnet })
    message.success('创建成功')
    showCreateSubnet.value = false
    Object.assign(newSubnet, { displayName: '', cidrBlock: '', availabilityDomain: '', routeTableId: '', prohibitPublicIp: false })
    loadSubnets(true)
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
    await createInternetGateway({ ...ociBase.value, vcnId: props.vcn.id, ...newIgw })
    message.success('创建成功')
    showCreateIgw.value = false
    newIgw.displayName = ''; newIgw.isEnabled = true
    loadIgw(true)
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
    await createNatGateway({ ...ociBase.value, vcnId: props.vcn.id, displayName: newNat.displayName })
    message.success('创建成功'); showCreateNat.value = false; newNat.displayName = ''
    loadNat(true); emit('changed')
  } catch (e: any) { message.error(e?.message || '创建失败') }
  finally { creating.value = false }
}

const showCreateSg = ref(false)
const newSg = reactive({ displayName: '' })
async function doCreateSg() {
  if (!newSg.displayName) return message.warning('请填写名称')
  creating.value = true
  try {
    await createServiceGateway({ ...ociBase.value, vcnId: props.vcn.id, displayName: newSg.displayName })
    message.success('创建成功'); showCreateSg.value = false; newSg.displayName = ''
    loadSg(true); emit('changed')
  } catch (e: any) { message.error(e?.message || '创建失败') }
  finally { creating.value = false }
}

const showCreateLpg = ref(false)
const newLpg = reactive({ displayName: '' })
async function doCreateLpg() {
  if (!newLpg.displayName) return message.warning('请填写名称')
  creating.value = true
  try {
    await createLocalPeeringGateway({ ...ociBase.value, vcnId: props.vcn.id, displayName: newLpg.displayName })
    message.success('创建成功'); showCreateLpg.value = false; newLpg.displayName = ''
    loadLpg(true); emit('changed')
  } catch (e: any) { message.error(e?.message || '创建失败') }
  finally { creating.value = false }
}

const showCreateRt = ref(false)
const newRt = reactive({ displayName: '' })
async function doCreateRt() {
  const displayName = newRt.displayName.trim()
  if (!displayName) return message.warning('请填写名称')
  if (displayName.length > 255) return message.warning('名称不能超过 255 个字符')
  creating.value = true
  try {
    await createRouteTable({ ...ociBase.value, vcnId: props.vcn.id, displayName })
    message.success('创建成功')
    showCreateRt.value = false
    newRt.displayName = ''
    loadRt(true)
    emit('changed')
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
    await connectLocalPeeringGateway({ ...ociBase.value, lpgId: connectLpgTarget.value.id, peerId: connectLpgPeerId.value })
    message.success('已发起连接'); showConnectLpg.value = false
    loadLpg(true)
  } catch (e: any) { message.error(e?.message || '连接失败') }
  finally { creating.value = false }
}

// ---- delete ----
const showDelete = ref(false)
const deleting = ref(false)
const deleteCodeSending = ref(false)
const deleteType = ref('')
const deleteTarget = ref<any>(null)
const deleteCode = ref('')
const deleteLabel = ref('')

function deleteVerifyAction(type: string) {
  const m: Record<string, string> = {
    subnet: 'deleteVcnSubnet',
    igw: 'deleteVcnIgw',
    nat: 'deleteVcnNat',
    sg: 'deleteVcnSg',
    lpg: 'deleteVcnLpg',
    rt: 'deleteVcnRt',
    sl: 'deleteVcnSl',
  }
  return m[type] || 'deleteVcn'
}

async function askDelete(type: string, row: any) {
  deleteType.value = type
  deleteTarget.value = row
  deleteCode.value = ''
  deleteLabel.value = ({
    subnet: '子网', igw: 'Internet 网关', nat: 'NAT 网关',
    sg: '服务网关', lpg: 'LPG', rt: '路由表', sl: '安全列表',
  } as any)[type] || type
  try { await sendVerifyCode(deleteVerifyAction(type)) } catch {}
  showDelete.value = true
}

async function resendDeleteVerifyCode() {
  deleteCodeSending.value = true
  try {
    await sendVerifyCode(deleteVerifyAction(deleteType.value))
    message.success('验证码已重新发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    deleteCodeSending.value = false
  }
}

async function doDelete() {
  if (!deleteCode.value) return message.warning('请输入验证码')
  deleting.value = true
  const code = deleteCode.value
  const t = deleteTarget.value
  try {
    switch (deleteType.value) {
      case 'subnet': await deleteSubnet({ ...ociBase.value, subnetId: t.id, verifyCode: code }); break
      case 'igw': await deleteInternetGateway({ ...ociBase.value, igwId: t.id, verifyCode: code }); break
      case 'nat': await deleteNatGateway({ ...ociBase.value, natId: t.id, verifyCode: code }); break
      case 'sg': await deleteServiceGateway({ ...ociBase.value, sgId: t.id, verifyCode: code }); break
      case 'lpg': await deleteLocalPeeringGateway({ ...ociBase.value, lpgId: t.id, verifyCode: code }); break
      case 'rt': await deleteRouteTable({ ...ociBase.value, rtId: t.id, verifyCode: code }); break
      case 'sl': await deleteSecurityList({ ...ociBase.value, slId: t.id, verifyCode: code }); break
    }
    message.success('删除成功')
    showDelete.value = false
    onTab(activeTab.value, true)
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
    const r = await previewVcnDelete({ ...ociBase.value, vcnId: props.vcn.id })
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
  renameValue.value = row?.displayName || ''
  renameLabel.value = ({ vcn: 'VCN', subnet: '子网', igw: 'IGW', nat: 'NAT', sg: 'SG', lpg: 'LPG' } as any)[type] || type
  showRename.value = true
}

async function doRename() {
  if (!renameValue.value.trim()) return message.warning('请输入名称')
  editing.value = true
  const t = renameTarget.value
  try {
    switch (renameType.value) {
      case 'vcn': await updateVcn({ ...ociBase.value, vcnId: t.id, displayName: renameValue.value }); emit('changed'); break
      case 'subnet': await updateSubnet({ ...ociBase.value, subnetId: t.id, displayName: renameValue.value }); loadSubnets(true); break
      case 'igw': await updateInternetGateway({ ...ociBase.value, igwId: t.id, displayName: renameValue.value }); loadIgw(true); break
      case 'nat': await updateNatGateway({ ...ociBase.value, natId: t.id, displayName: renameValue.value }); loadNat(true); break
      case 'sg': await updateServiceGateway({ ...ociBase.value, sgId: t.id, displayName: renameValue.value }); loadSg(true); break
      case 'lpg': await updateLocalPeeringGateway({ ...ociBase.value, lpgId: t.id, displayName: renameValue.value }); loadLpg(true); break
    }
    message.success('已更新')
    showRename.value = false
  } catch (e: any) { message.error(e?.message || '更新失败') }
  finally { editing.value = false }
}

// ---- IGW edit (setup default routes) ----
const showEditIgw = ref(false)
const editIgwTarget = ref<any>(null)
const igwRouteIpv4 = ref(true)
const igwRouteIpv6 = ref(true)
function openEditIgw(row: any) {
  editIgwTarget.value = row
  igwRouteIpv4.value = true
  igwRouteIpv6.value = true
  showEditIgw.value = true
}
async function doSetupIgwRoutes() {
  editing.value = true
  try {
    await setupIgwDefaultRoutes({
      ...ociBase.value,
      vcnId: props.vcn.id,
      igwId: editIgwTarget.value.id,
      addIpv6: igwRouteIpv6.value,
    })
    message.success('已写入默认路由表')
    showEditIgw.value = false
    loadRt(true)
  } catch (e: any) { message.error(e?.message || '配置失败') }
  finally { editing.value = false }
}

// ---- Toggle enable/block ----
const togglingId = ref('')
async function toggleIgw(row: any) {
  togglingId.value = row.id
  try {
    await updateInternetGateway({ ...ociBase.value, igwId: row.id, isEnabled: !row.isEnabled })
    message.success(row.isEnabled ? '已禁用' : '已启用')
    loadIgw(true)
  } catch (e: any) { message.error(e?.message || '切换失败') }
  finally { togglingId.value = '' }
}
async function toggleNat(row: any) {
  togglingId.value = row.id
  try {
    await updateNatGateway({ ...ociBase.value, natId: row.id, blockTraffic: !row.blockTraffic })
    message.success(row.blockTraffic ? '已放行' : '已阻断')
    loadNat(true)
  } catch (e: any) { message.error(e?.message || '切换失败') }
  finally { togglingId.value = '' }
}
async function toggleSg(row: any) {
  togglingId.value = row.id
  try {
    await updateServiceGateway({ ...ociBase.value, sgId: row.id, blockTraffic: !row.blockTraffic })
    message.success(row.blockTraffic ? '已放行' : '已阻断')
    loadSg(true)
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
      ...ociBase.value,
      subnetId: editSubnet.id,
      displayName: editSubnet.displayName,
      routeTableId: editSubnet.routeTableId || undefined,
      securityListIds: editSubnet.securityListIds.length ? editSubnet.securityListIds : undefined,
    })
    message.success('已更新')
    showEditSubnet.value = false
    loadSubnets(true)
  } catch (e: any) { message.error(e?.message || '更新失败') }
  finally { editing.value = false }
}

// ---- Edit route table ----
const showEditRt = ref(false)
const editingRt = ref<any>(null)

async function openEditRt(row: any) {
  editingRt.value = row
  showEditRt.value = true
}
function onRouteRulesSaved() {
  loadRt(true)
  emit('changed')
}

// ---- Edit SL ----
const showEditSl = ref(false)
const slDetail = ref<any>(null)
const slDetailLoading = ref(false)
const editingSlId = ref('')
const slIngressCols = [
  { title: '协议', dataIndex: 'protocol', key: 'protocol', width: 100 },
  { title: '来源', dataIndex: 'source', key: 'source', width: 160 },
  { title: '端口', dataIndex: 'portRange', key: 'portRange', width: 110 },
  { title: '状态', dataIndex: 'isStateless', key: 'isStateless', width: 80 },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '操作', key: 'ruleAction', width: 70 },
]
const slEgressCols = [
  { title: '协议', dataIndex: 'protocol', key: 'protocol', width: 100 },
  { title: '目的', dataIndex: 'destination', key: 'destination', width: 160 },
  { title: '端口', dataIndex: 'portRange', key: 'portRange', width: 110 },
  { title: '状态', dataIndex: 'isStateless', key: 'isStateless', width: 80 },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '操作', key: 'ruleAction', width: 70 },
]
function protocolLabel(p: string) {
  return ({ all: '全部', '6': 'TCP', '17': 'UDP', '1': 'ICMP', '58': 'ICMPv6' } as any)[p] || p
}
async function openEditSl(row: any) {
  editingSlId.value = row.id
  slDetail.value = null
  showEditSl.value = true
  reloadSl()
}
async function reloadSl() {
  slDetailLoading.value = true
  try {
    const r = await getSecurityList({ ...ociBase.value, slId: editingSlId.value })
    slDetail.value = r.data
  } catch (e: any) { message.error(e?.message || '加载失败') }
  finally { slDetailLoading.value = false }
}

// ---- Add SL rule ----
const showAddSlRule = ref(false)
const addSlForm = reactive({ direction: 'ingress', protocol: '6', source: '0.0.0.0/0', portMin: '', portMax: '', description: '' })
function openAddSlRule(direction: string) {
  Object.assign(addSlForm, { direction, protocol: '6', source: '0.0.0.0/0', portMin: '', portMax: '', description: '' })
  showAddSlRule.value = true
}
async function doAddSlRule() {
  if (!addSlForm.source.trim()) return message.warning('请填写 CIDR')
  editing.value = true
  try {
    await addSecurityListRule({
      ...ociBase.value, slId: editingSlId.value,
      direction: addSlForm.direction, protocol: addSlForm.protocol, source: addSlForm.source,
      portMin: addSlForm.portMin || undefined, portMax: addSlForm.portMax || undefined,
      description: addSlForm.description || undefined,
    })
    message.success('已添加')
    showAddSlRule.value = false
    reloadSl()
    loadSl(true)
  } catch (e: any) { message.error(e?.message || '添加失败') }
  finally { editing.value = false }
}
async function doDeleteSlRule(direction: string, index: number) {
  try {
    await deleteSecurityListRule({ ...ociBase.value, slId: editingSlId.value, direction, ruleIndex: index })
    message.success('已删除')
    reloadSl()
    loadSl(true)
  } catch (e: any) { message.error(e?.message || '删除失败') }
}

async function doDeleteVcn() {
  if (!deleteVcnCode.value) return message.warning('请输入验证码')
  Modal.confirm({
    title: '再次确认',
    content: '此操作不可恢复，确定要删除该 VCN 吗？',
    okText: '删除',
    okType: 'danger',
    zIndex: VCN_MANAGER_CONFIRM_MODAL_Z_INDEX,
    wrapClassName: VCN_MANAGER_CONFIRM_MODAL_WRAP_CLASS,
    onOk: async () => {
      deleting.value = true
      try {
        await deleteVcn({ ...ociBase.value, vcnId: props.vcn.id, cascade: cascadeDelete.value, verifyCode: deleteVcnCode.value })
        message.success('VCN 已删除')
        showDeleteVcn.value = false
        emit('update:open', false)
        emit('changed')
      } catch (e: any) { message.error(e?.message || '删除 VCN 失败') }
      finally { deleting.value = false }
    },
  })
}

const editingOverlayOpen = computed(() => (
  showCreateSubnet.value ||
  showCreateIgw.value ||
  showCreateNat.value ||
  showCreateSg.value ||
  showCreateLpg.value ||
  showCreateRt.value ||
  showConnectLpg.value ||
  showRename.value ||
  showEditSubnet.value ||
  showEditRt.value ||
  showEditSl.value ||
  showAddSlRule.value ||
  showEditIgw.value ||
  showDelete.value ||
  showDeleteVcn.value
))

watch(
  [() => props.open, editingOverlayOpen],
  ([open, overlayOpen]) => {
    emit('editing-overlay-change', Boolean(open && overlayOpen))
  },
  { immediate: true },
)

onUnmounted(() => {
  emit('editing-overlay-change', false)
})
</script>

<style scoped>
.op-row { display: flex; gap: 8px; margin-bottom: 10px; }
.vcn-delete-verify-footer {
  margin-top: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--text-sub);
}
</style>
