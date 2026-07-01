<template>
  <div class="instance-network-panel">
    <div class="network-toolbar">
      <div class="network-path">
        <button v-if="currentView.type !== 'overview'" class="back-link" type="button" @click="showOverview">
          返回网络
        </button>
        <span class="path-item">网络</span>
        <template v-if="currentView.type !== 'overview'">
          <span class="path-sep">/</span>
          <span class="path-item">{{ currentViewTitle }}</span>
        </template>
      </div>
      <a-button size="small" @click="loadInstanceNetwork(true)" :loading="networkLoading">
        刷新网络
      </a-button>
    </div>

    <a-spin :spinning="networkLoading">
      <a-empty v-if="!networkLoading && vnics.length === 0" description="暂无网络信息" />

      <template v-else>
        <template v-if="currentView.type === 'overview'">
          <section v-if="primaryVnic" class="network-section">
            <div class="section-head">
              <h3>网络信息</h3>
              <a-tag :color="stateColor(overviewVcn?.lifecycleState)">{{ overviewVcn?.lifecycleState || '—' }}</a-tag>
            </div>

            <div class="network-home-title">
              <span class="network-home-type">VCN</span>
              <span class="network-home-name">{{ vcnLabel(primaryVnic) }}</span>
              <button
                class="text-action-link"
                type="button"
                :disabled="!overviewVcnId"
                @click="openVcnManagerForVnic(primaryVnic)"
              >
                VCN 管理
              </button>
            </div>

            <a-collapse v-model:activeKey="networkInfoKeys" class="network-info-collapse" :bordered="false">
              <a-collapse-panel key="vcn" header="VCN">
                <div class="info-list">
                  <InfoRow label="名称">{{ vcnLabel(primaryVnic) }}</InfoRow>
                  <InfoRow label="IPv4 CIDR 块">{{ vcnCidrText(primaryVnic) }}</InfoRow>
                  <InfoRow label="IPv6 CIDR 块">{{ vcnIpv6Text(primaryVnic) }}</InfoRow>
                  <InfoRow label="DNS 标签">{{ overviewVcn?.dnsLabel || '—' }}</InfoRow>
                  <InfoRow label="DNS 域名">{{ overviewVcn?.vcnDomainName || '—' }}</InfoRow>
                  <InfoRow label="OCID">
                    <CopyText v-if="overviewVcnId" :text="overviewVcnId" />
                    <span v-else class="muted">—</span>
                  </InfoRow>
                </div>
              </a-collapse-panel>

              <a-collapse-panel key="subnet" header="子网">
                <div class="info-list">
                  <InfoRow label="名称">
                    <ResourceLink v-if="primaryVnic.subnetId" :text="subnetLabel(primaryVnic)" @click="openSubnet(primaryVnic)" />
                    <span v-else class="muted">—</span>
                  </InfoRow>
                  <InfoRow label="IPv4 CIDR 块">{{ overviewSubnet?.cidrBlock || primaryVnic.subnetCidrBlock || '—' }}</InfoRow>
                  <InfoRow label="IPv6 前缀">{{ overviewSubnet?.ipv6CidrBlock || '—' }}</InfoRow>
                  <InfoRow label="子网类型">{{ subnetTypeText(overviewSubnet) }}</InfoRow>
                  <InfoRow label="子网访问">{{ subnetAccessText(overviewSubnet) }}</InfoRow>
                  <InfoRow label="DNS 域名">{{ overviewSubnet?.subnetDomainName || primaryVnic.subnetDomainName || '—' }}</InfoRow>
                  <InfoRow label="DHCP 选项">
                    <CopyText v-if="overviewSubnet?.dhcpOptionsId" :text="overviewSubnet.dhcpOptionsId" />
                    <span v-else class="muted">—</span>
                  </InfoRow>
                  <InfoRow label="虚拟路由器 IP">{{ overviewSubnet?.virtualRouterIp || '—' }}</InfoRow>
                  <InfoRow label="虚拟路由器 MAC 地址">{{ overviewSubnet?.virtualRouterMac || '—' }}</InfoRow>
                </div>
              </a-collapse-panel>

              <a-collapse-panel key="routeSecurity" header="路由与安全">
                <div class="info-list">
                  <InfoRow label="路由表">
                    <ResourceLink v-if="primaryIpRouteTableId(primaryVnic)" :text="primaryIpRouteTableLabel(primaryVnic)" @click="openRouteTable(primaryVnic, primaryIpRouteTableId(primaryVnic))" />
                    <span v-else class="muted">—</span>
                  </InfoRow>
                  <InfoRow label="网络安全组">
                    <template v-if="nsgIds(primaryVnic).length">
                      <a-tag v-for="nsgId in nsgIds(primaryVnic)" :key="nsgId">{{ shortOcid(nsgId) }}</a-tag>
                    </template>
                    <span v-else class="muted">—</span>
                  </InfoRow>
                  <InfoRow label="安全列表">
                    <template v-if="securityListIds(overviewSubnet).length">
                      <a-tag v-for="slId in securityListIds(overviewSubnet)" :key="slId">{{ shortOcid(slId) }}</a-tag>
                    </template>
                    <span v-else class="muted">—</span>
                  </InfoRow>
                </div>
              </a-collapse-panel>

              <a-collapse-panel key="addressDns" header="地址与 DNS">
                <div class="info-list">
                  <InfoRow label="公共 IPv4 地址">
                    <template v-if="publicIpv4(primaryVnic)">
                      <CopyText :text="publicIpv4(primaryVnic)" />
                      <a-tag :color="publicIpTagColor(primaryVnic)" class="ip-tag">{{ publicIpLifetimeLabel(primaryVnic) }}</a-tag>
                    </template>
                    <span v-else class="muted">—</span>
                  </InfoRow>
                  <InfoRow label="专用 IPv4 地址">
                    <CopyText v-if="primaryIpAddress(primaryVnic)" :text="primaryIpAddress(primaryVnic)" />
                    <span v-else class="muted">—</span>
                  </InfoRow>
                  <InfoRow label="专用 DNS 记录">{{ primaryVnic.hostnameLabel ? '启用' : '禁用' }}</InfoRow>
                  <InfoRow label="主机名">{{ primaryVnic.hostnameLabel || '—' }}</InfoRow>
                  <InfoRow label="内部 FQDN">{{ primaryIp(primaryVnic)?.fqdn || primaryVnic.internalFqdn || '—' }}</InfoRow>
                  <InfoRow label="主 VNIC">
                    <ResourceLink v-if="primaryVnic.vnicId" :text="primaryVnic.displayName || 'VNIC'" @click="openVnic(primaryVnic)" />
                    <span v-else class="muted">—</span>
                  </InfoRow>
                  <InfoRow label="MAC 地址">{{ primaryVnic.macAddress || '—' }}</InfoRow>
                </div>
              </a-collapse-panel>
            </a-collapse>
          </section>

          <section class="network-section attached-section">
            <div class="section-head">
              <h3>附加的 VNIC</h3>
              <span class="section-count">{{ attachedVnics.length }} 个</span>
            </div>
            <div class="table-toolbar">
              <a-input v-model:value="vnicKeyword" allow-clear class="network-search" placeholder="搜索 VNIC / 子网 / IP" />
            </div>

            <a-table
              v-if="!isMobile"
              :data-source="filteredAttachedVnics"
              :columns="attachedColumns"
              :pagination="false"
              size="small"
              row-key="vnicId"
              :scroll="{ x: 980 }"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'name'">
                  <ResourceLink :text="record.displayName || 'VNIC'" @click="openVnic(record)" />
                  <a-tag v-if="record.isPrimary" class="primary-vnic-tag">主要 VNIC</a-tag>
                  <div class="sub-line">{{ shortOcid(record.vnicId) }}</div>
                </template>
                <template v-else-if="column.key === 'subnetType'">
                  {{ record.vlanId ? 'VLAN' : '子网' }}
                </template>
                <template v-else-if="column.key === 'subnet'">
                  <ResourceLink v-if="record.subnetId" :text="subnetLabel(record)" @click="openSubnet(record)" />
                  <span v-else class="muted">—</span>
                </template>
                <template v-else-if="column.key === 'state'">
                  <a-tag :color="stateColor(record.lifecycleState)">{{ record.lifecycleState || '—' }}</a-tag>
                </template>
                <template v-else-if="column.key === 'routeTable'">
                  <ResourceLink v-if="primaryIpRouteTableId(record)" :text="primaryIpRouteTableLabel(record)" @click="openRouteTable(record, primaryIpRouteTableId(record))" />
                  <span v-else class="muted">—</span>
                </template>
                <template v-else-if="column.key === 'fqdn'">
                  <span>{{ primaryIp(record)?.fqdn || record.internalFqdn || '—' }}</span>
                </template>
                <template v-else-if="column.key === 'vlanId'">
                  {{ record.vlanId || '—' }}
                </template>
                <template v-else-if="column.key === 'mac'">
                  {{ record.macAddress || '—' }}
                </template>
                <template v-else-if="column.key === 'action'">
                  <a-button type="link" size="small" @click="openVnic(record)">详情</a-button>
                </template>
              </template>
            </a-table>

            <template v-else>
              <div v-for="vnic in filteredAttachedVnics" :key="vnic.vnicId || vnic.subnetId" class="mobile-card">
                <div class="mobile-card-header">
                  <button class="mobile-title-link" type="button" @click="openVnic(vnic)">{{ vnic.displayName || 'VNIC' }}</button>
                  <div class="mobile-card-tags">
                    <a-tag v-if="vnic.isPrimary">主要 VNIC</a-tag>
                    <a-tag :color="stateColor(vnic.lifecycleState)">{{ vnic.lifecycleState || '—' }}</a-tag>
                  </div>
                </div>
                <div class="mobile-card-body">
                  <div class="mobile-card-row"><span class="label">专用 IP</span><span class="value">{{ primaryIpAddress(vnic) || '—' }}</span></div>
                  <div class="mobile-card-row"><span class="label">公共 IP</span><span class="value">{{ publicIpv4(vnic) || '—' }}</span></div>
                  <div class="mobile-card-row">
                    <span class="label">子网</span>
                    <ResourceLink v-if="vnic.subnetId" :text="subnetLabel(vnic)" @click="openSubnet(vnic)" />
                    <span v-else class="value">—</span>
                  </div>
                  <div class="mobile-card-row">
                    <span class="label">路由表</span>
                    <ResourceLink v-if="primaryIpRouteTableId(vnic)" :text="primaryIpRouteTableLabel(vnic)" @click="openRouteTable(vnic, primaryIpRouteTableId(vnic))" />
                    <span v-else class="value">—</span>
                  </div>
                </div>
              </div>
            </template>
          </section>
        </template>

        <template v-else-if="currentView.type === 'vnic' && currentVnic">
          <ResourceHeader
            type="VNIC"
            :name="currentVnic.displayName || 'VNIC'"
            :state="currentVnic.lifecycleState"
            @manage="openVcnManagerForVnic(currentVnic)"
          />
          <a-tabs v-model:activeKey="vnicTab" class="resource-tabs">
            <a-tab-pane key="info" tab="详细信息">
              <div class="detail-grid">
                <DetailPanel title="VNIC 信息">
                  <InfoRow label="子网"><ResourceLink v-if="currentVnic.subnetId" :text="subnetLabel(currentVnic)" @click="openSubnet(currentVnic)" /><span v-else class="muted">—</span></InfoRow>
                  <InfoRow label="跳过源/目的地检查">{{ yesNo(currentVnic.skipSourceDestCheck) }}</InfoRow>
                  <InfoRow label="MAC 地址">{{ currentVnic.macAddress || '—' }}</InfoRow>
                  <InfoRow label="VLAN 标记">{{ currentVnic.vlanId || '—' }}</InfoRow>
                  <InfoRow label="区间">{{ props.instance?.compartmentName || shortOcid(currentVnic.compartmentId) || '—' }}</InfoRow>
                  <InfoRow label="OCID"><CopyText v-if="currentVnic.vnicId" :text="currentVnic.vnicId" /><span v-else class="muted">—</span></InfoRow>
                  <InfoRow label="创建时间">{{ formatUtc(currentVnic.timeCreated) }}</InfoRow>
                  <InfoRow label="路由表"><ResourceLink v-if="currentVnic.routeTableId" :text="routeTableLabel(currentVnic)" @click="openRouteTable(currentVnic)" /><span v-else class="muted">—</span></InfoRow>
                </DetailPanel>
                <DetailPanel title="主要 IP 信息">
                  <InfoRow label="专用 IP 地址"><CopyText v-if="primaryIpAddress(currentVnic)" :text="primaryIpAddress(currentVnic)" /><span v-else class="muted">—</span></InfoRow>
                  <InfoRow label="专用 IP OCID"><CopyText v-if="primaryIp(currentVnic)?.privateIpId" :text="primaryIp(currentVnic).privateIpId" /><span v-else class="muted">—</span></InfoRow>
                  <InfoRow label="已分配">{{ formatUtc(primaryIp(currentVnic)?.timeCreated) }}</InfoRow>
                  <InfoRow label="路由表"><ResourceLink v-if="primaryIpRouteTableId(currentVnic)" :text="primaryIpRouteTableLabel(currentVnic)" @click="openRouteTable(currentVnic, primaryIpRouteTableId(currentVnic))" /><span v-else class="muted">—</span></InfoRow>
                  <InfoRow label="网络安全组">
                    <template v-if="nsgIds(currentVnic).length"><a-tag v-for="nsgId in nsgIds(currentVnic)" :key="nsgId">{{ shortOcid(nsgId) }}</a-tag></template>
                    <span v-else class="muted">—</span>
                  </InfoRow>
                  <InfoRow label="全限定域名">{{ primaryIp(currentVnic)?.fqdn || currentVnic.internalFqdn || '—' }}</InfoRow>
                  <InfoRow label="公共 IP 地址"><CopyText v-if="publicIpv4(currentVnic)" :text="publicIpv4(currentVnic)" /><span v-else class="muted">—</span></InfoRow>
                  <InfoRow label="公共 IP OCID"><CopyText v-if="primaryIp(currentVnic)?.publicIpId" :text="primaryIp(currentVnic).publicIpId" /><span v-else class="muted">—</span></InfoRow>
                </DetailPanel>
              </div>
            </a-tab-pane>
            <a-tab-pane key="ip" tab="IP 管理">
              <ResourceTableTitle title="IPv4 地址" />
              <a-input v-model:value="ipKeyword" allow-clear class="network-search table-search" placeholder="搜索 IP / FQDN / 路由表" />
              <a-table size="small" :data-source="filteredCurrentIps" :columns="ipColumns" :pagination="false" row-key="privateIpId" :scroll="{ x: 820 }">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'privateIp'">
                    {{ record.privateIpAddress || '—' }} <a-tag v-if="record.isPrimary">主要 IP</a-tag>
                  </template>
                  <template v-else-if="column.key === 'publicIp'">
                    <span>{{ record.publicIpAddress || '—' }}</span>
                    <a-tag v-if="record.publicIpLifetime" :color="record.publicIpLifetime === 'RESERVED' ? 'green' : 'orange'" class="ip-tag">{{ publicIpLifetimeText(record.publicIpLifetime) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'routeTable'">
                    <ResourceLink v-if="record.routeTableId" :text="record.routeTableDisplayName || routeTableName(record.routeTableId)" @click="openRouteTable(currentVnic, record.routeTableId)" />
                    <span v-else class="muted">—</span>
                  </template>
                  <template v-else-if="column.key === 'timeCreated'">{{ formatUtc(record.timeCreated) }}</template>
                </template>
              </a-table>
            </a-tab-pane>
            <a-tab-pane key="security" tab="安全">
              <DetailPanel title="网络安全组">
                <InfoRow label="NSG">
                  <template v-if="nsgIds(currentVnic).length"><a-tag v-for="nsgId in nsgIds(currentVnic)" :key="nsgId">{{ shortOcid(nsgId) }}</a-tag></template>
                  <span v-else class="muted">—</span>
                </InfoRow>
              </DetailPanel>
            </a-tab-pane>
            <a-tab-pane key="tags" tab="标记">
              <TagsTable :tags="resourceTags(currentVnic)" />
            </a-tab-pane>
          </a-tabs>
        </template>

        <template v-else-if="currentView.type === 'subnet' && currentSubnet">
          <ResourceHeader
            type="子网"
            :name="currentSubnet.displayName || '子网'"
            :state="currentSubnet.lifecycleState"
            @manage="openVcnManagerForSubnet(currentSubnet)"
          />
          <a-tabs v-model:activeKey="subnetTab" class="resource-tabs">
            <a-tab-pane key="info" tab="详细信息">
              <DetailPanel title="子网信息">
                <InfoRow label="OCID"><CopyText :text="currentSubnet.id" /></InfoRow>
                <InfoRow label="IPv4 CIDR 块">{{ currentSubnet.cidrBlock || '—' }}</InfoRow>
                <InfoRow label="IPv6 前缀">{{ currentSubnet.ipv6CidrBlock || '—' }}</InfoRow>
                <InfoRow label="虚拟路由器 MAC 地址">{{ currentSubnet.virtualRouterMac || '—' }}</InfoRow>
                <InfoRow label="子网类型">{{ currentSubnet.availabilityDomain ? '特定于可用性域' : '区域性' }}</InfoRow>
                <InfoRow label="可用性域">{{ currentSubnet.availabilityDomain || '—' }}</InfoRow>
                <InfoRow label="区间">{{ props.instance?.compartmentName || '—' }}</InfoRow>
                <InfoRow label="DNS 域名"><CopyText v-if="currentSubnet.subnetDomainName" :text="currentSubnet.subnetDomainName" /><span v-else class="muted">—</span></InfoRow>
                <InfoRow label="子网访问">{{ currentSubnet.prohibitPublicIpOnVnic ? '私有子网' : '公共子网' }}</InfoRow>
                <InfoRow label="DHCP 选项"><CopyText v-if="currentSubnet.dhcpOptionsId" :text="currentSubnet.dhcpOptionsId" /><span v-else class="muted">—</span></InfoRow>
                <InfoRow label="路由表"><ResourceLink v-if="currentSubnet.routeTableId" :text="routeTableName(currentSubnet.routeTableId)" @click="openRouteTableById(currentSubnet.routeTableId)" /><span v-else class="muted">—</span></InfoRow>
              </DetailPanel>
            </a-tab-pane>
            <a-tab-pane key="security" tab="安全">
              <DetailPanel title="安全列表">
                <InfoRow label="安全列表">
                  <template v-if="securityListIds(currentSubnet).length"><a-tag v-for="slId in securityListIds(currentSubnet)" :key="slId">{{ shortOcid(slId) }}</a-tag></template>
                  <span v-else class="muted">—</span>
                </InfoRow>
              </DetailPanel>
            </a-tab-pane>
            <a-tab-pane key="tags" tab="标记">
              <TagsTable :tags="resourceTags(currentSubnet)" />
            </a-tab-pane>
          </a-tabs>
        </template>

        <template v-else-if="currentView.type === 'routeTable' && currentRouteTable">
          <ResourceHeader
            type="路由表"
            :name="currentRouteTable.displayName || routeTableName(currentRouteTable.id)"
            :state="currentRouteTable.lifecycleState"
            @manage="openVcnManagerForRouteTable(currentRouteTable)"
          />
          <a-tabs v-model:activeKey="routeTableTab" class="resource-tabs">
            <a-tab-pane key="info" tab="详细信息">
              <DetailPanel title="路由表信息">
                <InfoRow label="OCID"><CopyText :text="currentRouteTable.id" /></InfoRow>
                <InfoRow label="创建时间">{{ formatUtc(currentRouteTable.timeCreated) }}</InfoRow>
                <InfoRow label="区间">{{ props.instance?.compartmentName || shortOcid(currentRouteTable.compartmentId) || '—' }}</InfoRow>
                <InfoRow label="VCN">{{ vcnLabel(routeTableOwnerVnic(currentRouteTable.id)) }}</InfoRow>
              </DetailPanel>
            </a-tab-pane>
            <a-tab-pane key="rules" tab="路由规则">
              <ResourceTableTitle title="路由规则" />
              <div class="table-toolbar route-rule-toolbar">
                <a-input v-model:value="routeRuleKeyword" allow-clear class="network-search" placeholder="搜索目的地 / 目标 / 说明" />
                <a-button type="primary" size="small" @click="openRouteRulesManager" :disabled="!currentRouteTableVcn">管理路由规则</a-button>
              </div>
              <a-spin :spinning="routeTableLoading">
                <a-table size="small" :data-source="filteredRouteRules" :columns="routeRuleColumns" :pagination="false" row-key="_key" :scroll="{ x: 840 }">
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'destination'">{{ record.destination || '—' }}</template>
                    <template v-else-if="column.key === 'targetType'">{{ targetTypeLabel(record.networkEntityId) }}</template>
                    <template v-else-if="column.key === 'target'">{{ targetDisplay(record.networkEntityId) }}</template>
                    <template v-else-if="column.key === 'routeType'">{{ routeTypeLabel(record.routeType) }}</template>
                    <template v-else-if="column.key === 'description'">{{ record.description || '—' }}</template>
                  </template>
                </a-table>
              </a-spin>
            </a-tab-pane>
            <a-tab-pane key="tags" tab="标记">
              <TagsTable :tags="resourceTags(currentRouteTable)" />
            </a-tab-pane>
          </a-tabs>

          <RouteTableRulesManager
            v-if="currentRouteTableVcn"
            v-model:open="routeRulesManagerOpen"
            :user-id="props.tenant?.id || ''"
            :vcn="currentRouteTableVcn"
            :route-table="currentRouteTable"
            :oci-region="scopeParam().region"
            @saved="onRouteRulesSaved"
          />
        </template>
      </template>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, ref, resolveComponent, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getInstanceNetworkDetail } from '../../api/instance'
import { getRouteTable } from '../../api/vcn'
import RouteTableRulesManager from '../vcn/RouteTableRulesManager.vue'

const InfoRow = defineComponent({
  props: { label: { type: String, required: true } },
  setup(props, { slots }) {
    return () => h('div', { class: 'info-row' }, [
      h('div', { class: 'info-label' }, props.label),
      h('div', { class: 'info-value' }, slots.default?.()),
    ])
  },
})

const DetailPanel = defineComponent({
  props: { title: { type: String, required: true } },
  setup(props, { slots }) {
    return () => h('section', { class: 'detail-panel' }, [
      h('h4', { class: 'detail-panel-title' }, props.title),
      h('div', { class: 'info-list' }, slots.default?.()),
    ])
  },
})

const ResourceLink = defineComponent({
  props: { text: { type: String, required: true } },
  emits: ['click'],
  setup(props, { emit }) {
    return () => h('button', { class: 'resource-link', type: 'button', onClick: () => emit('click') }, props.text)
  },
})

const CopyText = defineComponent({
  props: { text: { type: String, required: true } },
  setup(props) {
    const TypographyText = resolveComponent('a-typography-text')
    return () => h(TypographyText, { copyable: { text: props.text }, class: 'copy-text' }, () => shortOcid(props.text))
  },
})

const ResourceHeader = defineComponent({
  props: {
    type: { type: String, required: true },
    name: { type: String, required: true },
    state: { type: String, default: '' },
  },
  emits: ['manage'],
  setup(props, { emit }) {
    const Tag = resolveComponent('a-tag')
    return () => h('div', { class: 'resource-header' }, [
      h('div', { class: 'resource-title-wrap' }, [
        h('div', { class: 'resource-title-line' }, [
          h('h3', { class: 'resource-title' }, props.name),
          props.state ? h(Tag, { color: stateColor(props.state) }, () => props.state) : null,
        ]),
        h('div', { class: 'resource-type' }, props.type),
      ]),
      h('button', { class: 'text-action-link resource-manage-link', type: 'button', onClick: () => emit('manage') }, 'VCN 管理'),
    ])
  },
})

const ResourceTableTitle = defineComponent({
  props: { title: { type: String, required: true } },
  setup(props) {
    return () => h('div', { class: 'resource-table-title' }, [
      h('h4', props.title),
    ])
  },
})

const TagsTable = defineComponent({
  props: { tags: { type: Array, required: true } },
  setup(props) {
    const Table = resolveComponent('a-table')
    const Empty = resolveComponent('a-empty')
    const columns = [
      { title: '名称空间', dataIndex: 'namespace', key: 'namespace', width: 160 },
      { title: '标记键', dataIndex: 'key', key: 'key', width: 180 },
      { title: '标记值', dataIndex: 'value', key: 'value' },
      { title: '类型', dataIndex: 'type', key: 'type', width: 120 },
    ]
    return () => (props.tags as any[]).length
      ? h(Table, { size: 'small', columns, dataSource: props.tags, pagination: false, rowKey: 'rowKey', scroll: { x: 720 } })
      : h(Empty, { description: '暂无标记' })
  },
})

const props = defineProps<{
  tenant: any | null
  instance: any | null
  isMobile: boolean
  active: boolean
  region?: string
  compartmentId?: string
}>()

const emit = defineEmits<{
  (e: 'open-vcn-manager', payload: { vcn: any; tab?: 'subnet' | 'rt'; resourceId?: string }): void
}>()

const attachedColumns = [
  { title: '名称', key: 'name', width: 190 },
  { title: '子网或 VLAN', key: 'subnetType', width: 110 },
  { title: '子网 / VLAN 链路', key: 'subnet', width: 190 },
  { title: '状态', key: 'state', width: 100 },
  { title: '路由表', key: 'routeTable', width: 210 },
  { title: 'FQDN', key: 'fqdn', width: 200, ellipsis: true },
  { title: 'VLAN 标记', key: 'vlanId', width: 100 },
  { title: 'MAC 地址', key: 'mac', width: 150 },
  { title: '操作', key: 'action', width: 80 },
]

const ipColumns = [
  { title: '专用 IP 地址', key: 'privateIp', width: 180 },
  { title: '公共 IP 地址', key: 'publicIp', width: 180 },
  { title: 'IP 生存期', dataIndex: 'lifetime', key: 'lifetime', width: 120 },
  { title: '全限定域名', dataIndex: 'fqdn', key: 'fqdn', ellipsis: true },
  { title: '路由表', key: 'routeTable', width: 220 },
  { title: '已分配', key: 'timeCreated', width: 170 },
]

const routeRuleColumns = [
  { title: '目的地', key: 'destination', width: 180 },
  { title: '目标类型', key: 'targetType', width: 150 },
  { title: '目标', key: 'target', width: 260 },
  { title: '路由类型', key: 'routeType', width: 110 },
  { title: '说明', key: 'description', ellipsis: true },
]

const networkLoading = ref(false)
const networkDetail = ref<any>(null)
const currentView = ref<{ type: 'overview' | 'vnic' | 'subnet' | 'routeTable'; id?: string }>({ type: 'overview' })
const networkInfoKeys = ref<string[]>(['vcn', 'subnet'])
const vnicTab = ref('info')
const subnetTab = ref('info')
const routeTableTab = ref('info')
const vnicKeyword = ref('')
const ipKeyword = ref('')
const routeRuleKeyword = ref('')
const routeTableLoading = ref(false)
const routeRulesManagerOpen = ref(false)
const routeTableDetails = ref<Record<string, any>>({})
let networkLoadSeq = 0
let routeTableSeq = 0

const vnics = computed(() => {
  const list = networkDetail.value?.vnics
  return Array.isArray(list) ? list : []
})

const primaryVnic = computed(() => vnics.value.find((vnic: any) => vnic.isPrimary === true) || vnics.value[0] || null)
const overviewVcn = computed(() => primaryVnic.value?.vcn || null)
const overviewSubnet = computed(() => primaryVnic.value?.subnet || null)
const overviewVcnId = computed(() => overviewVcn.value?.id || primaryVnic.value?.vcnId || '')
const attachedVnics = computed(() => vnics.value)
const filteredAttachedVnics = computed(() => {
  const q = vnicKeyword.value.trim().toLowerCase()
  if (!q) return attachedVnics.value
  return attachedVnics.value.filter((vnic: any) => [
    vnic.displayName,
    vnic.vnicId,
    vnic.privateIp,
    vnic.publicIp,
    subnetLabel(vnic),
    routeTableLabel(vnic),
    primaryIpAddress(vnic),
    publicIpv4(vnic),
  ].some((item) => String(item || '').toLowerCase().includes(q)))
})

const vnicIndex = computed(() => {
  const map = new Map<string, any>()
  for (const vnic of vnics.value) {
    if (vnic.vnicId) map.set(vnic.vnicId, vnic)
  }
  return map
})
const subnetIndex = computed(() => {
  const map = new Map<string, any>()
  for (const vnic of vnics.value) {
    if (vnic.subnetId) {
      map.set(vnic.subnetId, { ...(vnic.subnet || {}), id: vnic.subnetId, displayName: subnetLabel(vnic), routeTableId: vnic.subnet?.routeTableId || vnic.routeTableId })
    }
  }
  return map
})
const routeTableIndex = computed(() => {
  const map = new Map<string, any>()
  for (const vnic of vnics.value) {
    if (vnic.routeTableId) map.set(vnic.routeTableId, { ...(vnic.routeTable || {}), id: vnic.routeTableId, displayName: routeTableLabel(vnic) })
    for (const ip of ipDetails(vnic)) {
      if (ip.routeTableId) map.set(ip.routeTableId, { ...(ip.routeTable || {}), id: ip.routeTableId, displayName: ip.routeTableDisplayName || routeTableName(ip.routeTableId) })
    }
  }
  return map
})

const currentVnic = computed(() => currentView.value.type === 'vnic' ? vnicIndex.value.get(currentView.value.id || '') : null)
const currentSubnet = computed(() => currentView.value.type === 'subnet' ? subnetIndex.value.get(currentView.value.id || '') : null)
const currentRouteTable = computed(() => {
  if (currentView.value.type !== 'routeTable') return null
  const id = currentView.value.id || ''
  return { ...(routeTableIndex.value.get(id) || {}), ...(routeTableDetails.value[id] || {}), id }
})
const currentRouteTableVcn = computed(() => currentRouteTable.value?.id ? vcnForRouteTable(currentRouteTable.value.id) : null)
const currentViewTitle = computed(() => {
  if (currentView.value.type === 'vnic') return currentVnic.value?.displayName || 'VNIC'
  if (currentView.value.type === 'subnet') return currentSubnet.value?.displayName || '子网'
  if (currentView.value.type === 'routeTable') return currentRouteTable.value?.displayName || '路由表'
  return ''
})
const filteredCurrentIps = computed(() => {
  const list = ipDetails(currentVnic.value)
  const q = ipKeyword.value.trim().toLowerCase()
  if (!q) return list
  return list.filter((ip: any) => [
    ip.privateIpAddress,
    ip.publicIpAddress,
    ip.fqdn,
    ip.routeTableDisplayName,
    ip.routeTableId,
  ].some((item) => String(item || '').toLowerCase().includes(q)))
})
const currentRouteRules = computed(() => Array.isArray(currentRouteTable.value?.routeRules) ? currentRouteTable.value.routeRules : [])
const filteredRouteRules = computed(() => {
  const q = routeRuleKeyword.value.trim().toLowerCase()
  const rows = currentRouteRules.value.map((rule: any, index: number) => ({ ...rule, _key: `${index}_${rule.destination || ''}_${rule.networkEntityId || ''}` }))
  if (!q) return rows
  return rows.filter((rule: any) => [
    rule.destination,
    rule.destinationType,
    rule.networkEntityId,
    rule.description,
    targetTypeLabel(rule.networkEntityId),
    targetDisplay(rule.networkEntityId),
  ].some((item) => String(item || '').toLowerCase().includes(q)))
})

function regionParam(): { region?: string } {
  const r =
    (props.region && String(props.region).trim()) ||
    (props.instance?.region && String(props.instance.region).trim()) ||
    (props.tenant?.ociRegion && String(props.tenant.ociRegion).trim()) ||
    ''
  return r ? { region: r } : {}
}

function scopeParam(): { region?: string; compartmentId?: string } {
  const base = regionParam()
  const cid =
    (props.compartmentId && String(props.compartmentId).trim()) ||
    (props.instance?.compartmentId && String(props.instance.compartmentId).trim()) ||
    ''
  return cid ? { ...base, compartmentId: cid } : base
}

function currentTargetKey() {
  const scope = scopeParam()
  return [
    props.tenant?.id || '',
    props.instance?.instanceId || '',
    scope.region || '',
    scope.compartmentId || '',
  ].join('|')
}

function sameTarget(targetKey: string) {
  return currentTargetKey() === targetKey
}

function reset() {
  networkLoadSeq += 1
  routeTableSeq += 1
  networkLoading.value = false
  routeTableLoading.value = false
  routeTableDetails.value = {}
  networkDetail.value = null
  currentView.value = { type: 'overview' }
  networkInfoKeys.value = ['vcn', 'subnet']
  vnicKeyword.value = ''
  ipKeyword.value = ''
  routeRuleKeyword.value = ''
}

async function loadInstanceNetwork(force = false) {
  if (!props.tenant || !props.instance) return
  const requestId = ++networkLoadSeq
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const targetKey = currentTargetKey()
  const scope = scopeParam()
  networkLoading.value = true
  try {
    const res = await getInstanceNetworkDetail({
      id: tenantId,
      instanceId,
      ...scope,
      force,
    })
    if (requestId !== networkLoadSeq || !sameTarget(targetKey)) return
    networkDetail.value = res.data || { vnics: [] }
    if (currentView.value.type !== 'overview' && currentView.value.id && !resourceExists(currentView.value.type, currentView.value.id)) {
      currentView.value = { type: 'overview' }
    }
  } catch (e: any) {
    if (requestId === networkLoadSeq && sameTarget(targetKey)) message.error(e?.message || '加载实例网络失败')
  } finally {
    if (requestId === networkLoadSeq && sameTarget(targetKey)) networkLoading.value = false
  }
}

function resourceExists(type: string, id: string) {
  if (type === 'vnic') return vnicIndex.value.has(id)
  if (type === 'subnet') return subnetIndex.value.has(id)
  if (type === 'routeTable') return routeTableIndex.value.has(id) || routeTableDetails.value[id]
  return true
}

function showOverview() {
  currentView.value = { type: 'overview' }
}

function openVnic(vnic: any) {
  if (!vnic?.vnicId) return
  currentView.value = { type: 'vnic', id: vnic.vnicId }
  vnicTab.value = 'info'
}

function openSubnet(vnic: any) {
  if (!vnic?.subnetId) return
  currentView.value = { type: 'subnet', id: vnic.subnetId }
  subnetTab.value = 'info'
}

function openRouteTable(vnic: any, routeTableId?: string) {
  const id = routeTableId || vnic?.routeTableId
  if (!id) return
  openRouteTableById(id)
}

function openRouteTableById(routeTableId: string) {
  currentView.value = { type: 'routeTable', id: routeTableId }
  routeTableTab.value = 'info'
  void loadRouteTableDetail(routeTableId)
}

async function loadRouteTableDetail(routeTableId: string, force = false) {
  if (!props.tenant?.id || !routeTableId) return
  const requestId = ++routeTableSeq
  const targetKey = currentTargetKey()
  routeTableLoading.value = true
  try {
    const res = await getRouteTable({ id: props.tenant.id, rtId: routeTableId, ...regionParam(), force })
    if (requestId !== routeTableSeq || !sameTarget(targetKey)) return
    routeTableDetails.value = { ...routeTableDetails.value, [routeTableId]: res.data || {} }
  } catch (e: any) {
    if (requestId === routeTableSeq && sameTarget(targetKey)) message.error(e?.message || '加载路由表失败')
  } finally {
    if (requestId === routeTableSeq && sameTarget(targetKey)) routeTableLoading.value = false
  }
}

function onRouteRulesSaved() {
  const routeTableId = currentRouteTable.value?.id
  if (routeTableId) void loadRouteTableDetail(routeTableId, true)
  void loadInstanceNetwork(true)
}

function openRouteRulesManager() {
  routeRulesManagerOpen.value = true
}

function openVcnManagerForVnic(vnic: any) {
  const vcn = vcnPayload(vnic)
  if (!vcn) return
  emit('open-vcn-manager', { vcn, tab: 'subnet', resourceId: vnic.subnetId })
}

function openVcnManagerForSubnet(subnet: any) {
  const owner = vnicForSubnet(subnet.id) || primaryVnic.value
  const vcn = vcnPayload(owner)
  if (!vcn) return
  emit('open-vcn-manager', { vcn, tab: 'subnet', resourceId: subnet.id })
}

function openVcnManagerForRouteTable(routeTable: any) {
  const vcn = vcnForRouteTable(routeTable.id)
  if (!vcn) return
  emit('open-vcn-manager', { vcn, tab: 'rt', resourceId: routeTable.id })
}

function primaryIp(vnic: any) {
  const ips = ipDetails(vnic)
  return ips.find((ip: any) => ip?.isPrimary) || ips[0] || null
}

function ipDetails(vnic: any) {
  return Array.isArray(vnic?.ipDetails) ? vnic.ipDetails : []
}

function publicIpv4(vnic: any) {
  return primaryIp(vnic)?.publicIpAddress || vnic?.publicIp || ''
}

function primaryIpAddress(vnic: any) {
  return primaryIp(vnic)?.privateIpAddress || vnic?.privateIp || ''
}

function primaryIpRouteTableId(vnic: any) {
  return primaryIp(vnic)?.routeTableId || vnic?.routeTableId || ''
}

function primaryIpRouteTableLabel(vnic: any) {
  const ip = primaryIp(vnic)
  return ip?.routeTable?.displayName || ip?.routeTableDisplayName || routeTableLabel(vnic)
}

function vcnLabel(vnic: any) {
  return vnic?.vcn?.displayName || vnic?.vcnDisplayName || vnic?.vcnId || '—'
}

function subnetLabel(vnic: any) {
  return vnic?.subnet?.displayName || vnic?.subnetDisplayName || vnic?.subnetId || '—'
}

function routeTableLabel(vnic: any) {
  return vnic?.routeTable?.displayName || vnic?.routeTableDisplayName || vnic?.routeTableId || '—'
}

function vcnCidrText(vnic: any) {
  const blocks = vnic?.vcn?.cidrBlocks
  if (Array.isArray(blocks) && blocks.length) return blocks.filter(Boolean).join(', ')
  return vnic?.vcn?.cidrBlock || vnic?.vcnCidrBlock || '—'
}

function vcnIpv6Text(vnic: any) {
  const blocks = vnic?.vcn?.ipv6CidrBlocks
  return Array.isArray(blocks) && blocks.length ? blocks.filter(Boolean).join(', ') : '—'
}

function subnetTypeText(subnet: any) {
  if (!subnet) return '—'
  return subnet.availabilityDomain ? '特定于可用性域' : '区域性'
}

function subnetAccessText(subnet: any) {
  if (!subnet) return '—'
  if (subnet.prohibitPublicIpOnVnic === true) return '私有子网'
  if (subnet.prohibitPublicIpOnVnic === false) return '公共子网'
  return '—'
}

function routeTableName(id: string) {
  return routeTableDetails.value[id]?.displayName || routeTableIndex.value.get(id)?.displayName || id
}

function routeTableOwnerVnic(id: string) {
  return vnics.value.find((vnic: any) => vnic.routeTableId === id || ipDetails(vnic).some((ip: any) => ip.routeTableId === id)) || primaryVnic.value
}

function vnicForSubnet(id: string) {
  return vnics.value.find((vnic: any) => vnic.subnetId === id) || null
}

function vcnForRouteTable(routeTableId: string) {
  const owner = routeTableOwnerVnic(routeTableId)
  return vcnPayload(owner)
}

function vcnPayload(vnic: any) {
  const vcn = vnic?.vcn || {}
  const id = vcn.id || vnic?.vcnId || currentRouteTable.value?.vcnId
  if (!id) return null
  return {
    ...vcn,
    id,
    displayName: vcn.displayName || vnic?.vcnDisplayName || id,
    cidrBlock: vcn.cidrBlock || vnic?.vcnCidrBlock,
    lifecycleState: vcn.lifecycleState || vnic?.vcnLifecycleState,
    region: vcn.region || scopeParam().region,
  }
}

function nsgIds(vnic: any) {
  return Array.isArray(vnic?.nsgIds) ? vnic.nsgIds.filter(Boolean) : []
}

function securityListIds(subnet: any) {
  return Array.isArray(subnet?.securityListIds) ? subnet.securityListIds.filter(Boolean) : []
}

function yesNo(value: any) {
  if (value === true) return '是'
  if (value === false) return '否'
  return '—'
}

function publicIpLifetimeLabel(vnic: any) {
  return publicIpLifetimeText(primaryIp(vnic)?.publicIpLifetime)
}

function publicIpLifetimeText(lifetime: string) {
  if (lifetime === 'RESERVED') return '预留'
  if (lifetime === 'EPHEMERAL') return '临时'
  return lifetime || '—'
}

function publicIpTagColor(vnic: any) {
  return primaryIp(vnic)?.publicIpLifetime === 'RESERVED' ? 'green' : 'orange'
}

function stateColor(state?: string) {
  if (state === 'AVAILABLE' || state === 'ATTACHED') return 'green'
  if (state === 'PROVISIONING' || state === 'ATTACHING') return 'blue'
  if (state === 'TERMINATING' || state === 'DETACHING') return 'orange'
  return 'default'
}

function targetTypeLabel(id: string) {
  if (!id) return '—'
  if (id.includes('.internetgateway.')) return 'Internet 网关'
  if (id.includes('.natgateway.')) return 'NAT 网关'
  if (id.includes('.servicegateway.')) return '服务网关'
  if (id.includes('.localpeeringgateway.')) return '本地对等连接网关'
  if (id.includes('.drg.')) return '动态路由网关'
  if (id.includes('.privateip.')) return '专用 IP'
  return '目标资源'
}

function targetDisplay(id: string) {
  return shortOcid(id)
}

function routeTypeLabel(type?: string) {
  return String(type || '').toUpperCase() === 'LOCAL' ? '本地' : '静态'
}

function resourceTags(resource: any) {
  const rows: any[] = []
  const freeform = resource?.freeformTags || {}
  for (const [key, value] of Object.entries(freeform)) {
    rows.push({ rowKey: `freeform:${key}`, namespace: 'Free-form', key, value: String(value ?? ''), type: '自由格式' })
  }
  const defined = resource?.definedTags || {}
  for (const [namespace, values] of Object.entries(defined)) {
    if (!values || typeof values !== 'object') continue
    for (const [key, value] of Object.entries(values as Record<string, any>)) {
      rows.push({ rowKey: `defined:${namespace}:${key}`, namespace, key, value: String(value ?? ''), type: '已定义' })
    }
  }
  return rows
}

function shortOcid(value: string | undefined) {
  if (!value) return '—'
  if (value.length <= 30) return value
  return `${value.slice(0, 14)}...${value.slice(-10)}`
}

function formatUtc(value: string | undefined) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())} UTC`
}

watch(
  () => [props.tenant?.id, props.instance?.instanceId, props.region, props.compartmentId],
  () => {
    reset()
    if (props.active) void loadInstanceNetwork()
  },
)

watch(
  () => props.active,
  (active) => {
    if (active && !networkDetail.value) void loadInstanceNetwork()
  },
  { immediate: true },
)

defineExpose({
  loadInstanceNetwork,
  loadVcns: loadInstanceNetwork,
  reset,
})
</script>

<style scoped>
.instance-network-panel {
  color: var(--text-main);
}

.network-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.network-path {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: var(--text-sub);
  font-size: 12px;
}

.back-link,
.resource-link,
.mobile-title-link,
.text-action-link {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--primary);
  cursor: pointer;
  text-align: left;
}

.back-link {
  margin-right: 6px;
  font-size: 12px;
}

.path-item {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.path-sep {
  color: var(--border);
}

.network-section {
  margin-bottom: 22px;
}

.section-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.section-head h3,
.resource-title,
.resource-table-title h4 {
  margin: 0;
  color: var(--text-main);
  font-size: 20px;
  font-weight: 700;
  line-height: 1.25;
}

.section-count {
  color: var(--text-sub);
  font-size: 12px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 24px;
}

.network-home-title {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-sidebar);
}

.network-home-type {
  flex: 0 0 auto;
  color: var(--text-sub);
  font-size: 12px;
}

.network-home-name {
  flex: 1;
  min-width: 0;
  color: var(--text-main);
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.network-info-collapse {
  background: transparent;
}

.network-info-collapse :deep(.ant-collapse-item) {
  margin-bottom: 8px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-sidebar);
  overflow: hidden;
}

.network-info-collapse :deep(.ant-collapse-header) {
  align-items: center;
  padding: 9px 12px !important;
  color: var(--text-main) !important;
  font-size: 13px;
  font-weight: 600;
}

.network-info-collapse :deep(.ant-collapse-content) {
  border-top: 1px solid var(--border);
  background: transparent;
}

.network-info-collapse :deep(.ant-collapse-content-box) {
  padding: 0 12px !important;
}

.network-info-collapse .info-list {
  border-top: 0;
}

.info-list {
  border-top: 1px solid var(--border);
}

.info-row {
  display: grid;
  grid-template-columns: minmax(128px, 210px) minmax(0, 1fr);
  gap: 14px;
  min-height: 48px;
  padding: 11px 0;
  border-bottom: 1px solid var(--border);
}

.info-label {
  color: var(--text-main);
  font-size: 13px;
  font-weight: 600;
  line-height: 24px;
}

.info-value {
  min-width: 0;
  color: var(--text-main);
  font-size: 13px;
  line-height: 24px;
  overflow-wrap: anywhere;
}

.table-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.network-search {
  width: 280px;
  max-width: 100%;
}

.table-search {
  margin-bottom: 10px;
}

.resource-link {
  line-height: 22px;
  overflow-wrap: anywhere;
}

.resource-link:hover,
.back-link:hover,
.mobile-title-link:hover,
.text-action-link:hover {
  color: var(--primary-hover);
  text-decoration: underline;
}

.text-action-link {
  flex: 0 0 auto;
  font-size: 12px;
  line-height: 22px;
  white-space: nowrap;
}

.text-action-link:disabled {
  color: var(--text-sub);
  cursor: not-allowed;
  opacity: 0.55;
  text-decoration: none;
}

.resource-manage-link {
  margin-top: 3px;
}

.muted,
.sub-line {
  color: var(--text-sub);
}

.sub-line {
  margin-top: 2px;
  font-size: 12px;
}

.ip-tag {
  margin-left: 6px;
}

.primary-vnic-tag {
  margin-left: 6px;
}

.resource-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.resource-title-line {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.resource-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-type {
  margin-top: 3px;
  color: var(--text-sub);
  font-size: 12px;
}

.resource-tabs {
  margin-top: 2px;
}

.detail-panel-title {
  margin: 0 0 10px;
  color: var(--text-main);
  font-size: 16px;
  font-weight: 700;
}

.resource-table-title {
  margin-bottom: 10px;
}

.route-rule-toolbar {
  justify-content: space-between;
  flex-wrap: wrap;
}

.mobile-card {
  margin-bottom: 10px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-sidebar);
}

.mobile-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.mobile-card-tags {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 0 0 auto;
}

.mobile-title-link {
  min-width: 0;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mobile-card-body {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.mobile-card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
}

.mobile-card-row .label {
  flex: 0 0 auto;
  color: var(--text-sub);
}

.mobile-card-row .value {
  min-width: 0;
  color: var(--text-main);
  text-align: right;
  overflow-wrap: anywhere;
}

@media (max-width: 900px) {
  .detail-grid {
    grid-template-columns: 1fr;
    gap: 12px;
  }
}

@media (max-width: 768px) {
  .network-toolbar,
  .resource-header,
  .network-home-title {
    align-items: stretch;
    flex-direction: column;
  }

  .section-head h3,
  .resource-title,
  .resource-table-title h4 {
    font-size: 18px;
  }

  .info-row {
    grid-template-columns: 1fr;
    gap: 2px;
    min-height: 0;
    padding: 9px 0;
  }

  .info-label,
  .info-value {
    line-height: 20px;
  }
}
</style>
