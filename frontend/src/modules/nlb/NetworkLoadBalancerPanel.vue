<template>
  <section class="nlb-panel">
    <div class="nlb-toolbar nlb-toolbar-actions">
      <a-space wrap>
        <a-button type="primary" size="small" @click="openCreateNlb">创建负载均衡器</a-button>
        <a-button size="small" :loading="listLoading" @click="loadNlbs(true)">刷新</a-button>
      </a-space>
    </div>

    <a-alert
      v-if="contextError"
      type="warning"
      show-icon
      :message="contextError"
      class="nlb-context-alert"
    />
    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      closable
      :message="loadError"
      class="nlb-context-alert"
    />

    <a-spin :spinning="listLoading">
      <a-empty v-if="!listLoading && !rows.length" description="当前 VCN 暂无网络负载均衡器" />
      <template v-else>
        <div class="nlb-desktop-list">
          <a-table
            size="small"
            row-key="id"
            table-layout="fixed"
            :data-source="rows"
            :columns="nlbColumns"
            :pagination="false"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'displayName'">
                <a-button type="link" size="small" class="resource-link" :title="record.displayName || record.id" @click="openDetail(record)">
                  {{ record.displayName || record.id }}
                </a-button>
              </template>
              <template v-else-if="column.key === 'exposure'">
                <a-tag :color="record.isPrivate ? 'blue' : 'orange'">{{ record.isPrivate ? '私有' : '公有' }}</a-tag>
              </template>
              <template v-else-if="column.key === 'ipAddresses'">
                <div v-for="ip in (record.ipAddresses || []).slice(0, 2)" :key="ip.ipAddress || ip.reservedIpId">
                  {{ ip.ipAddress || '—' }}
                </div>
                <span v-if="!(record.ipAddresses || []).length" class="muted">—</span>
              </template>
              <template v-else-if="column.key === 'lifecycleState'">
                <a-badge :status="lifecycleBadge(record.lifecycleState)" :text="record.lifecycleState || 'UNKNOWN'" />
              </template>
              <template v-else-if="column.key === 'healthStatus'">
                <a-badge :status="healthTagStatus(record.healthStatus)" :text="healthStatusText(record.healthStatus)" />
              </template>
              <template v-else-if="column.key === 'counts'">
                {{ record.listenerCount || 0 }} / {{ record.backendSetCount || 0 }} / {{ record.backendCount || 0 }}
              </template>
              <template v-else-if="column.key === 'action'">
                <a-space :size="2" wrap>
                  <a-button size="small" type="link" @click="openDetail(record)">管理</a-button>
                  <a-button size="small" type="link" @click="openEditNlb(record)">编辑</a-button>
                  <a-button size="small" type="link" @click="openNsgEditor(record)">NSG</a-button>
                  <a-button size="small" type="link" danger @click="askDeleteNlb(record)">删除</a-button>
                </a-space>
              </template>
            </template>
          </a-table>
        </div>

        <div class="nlb-mobile-list">
          <a-card v-for="record in rows" :key="record.id" size="small" class="nlb-mobile-card">
            <div class="nlb-mobile-header">
              <a-button type="link" size="small" class="resource-link nlb-mobile-name" :title="record.displayName || record.id" @click="openDetail(record)">
                {{ record.displayName || record.id }}
              </a-button>
              <a-tag :color="record.isPrivate ? 'blue' : 'orange'">{{ record.isPrivate ? '私有' : '公有' }}</a-tag>
            </div>
            <div class="nlb-mobile-info">
              <div class="nlb-mobile-row">
                <span class="nlb-mobile-label">IP 地址</span>
                <span class="nlb-mobile-value">
                  {{ (record.ipAddresses || []).map((ip: any) => ip.ipAddress).filter(Boolean).join(', ') || '—' }}
                </span>
              </div>
              <div class="nlb-mobile-row">
                <span class="nlb-mobile-label">子网</span>
                <span class="nlb-mobile-value">{{ record.subnetName || record.subnetId || '—' }}</span>
              </div>
              <div class="nlb-mobile-row">
                <span class="nlb-mobile-label">生命周期</span>
                <a-badge :status="lifecycleBadge(record.lifecycleState)" :text="record.lifecycleState || 'UNKNOWN'" />
              </div>
              <div class="nlb-mobile-row">
                <span class="nlb-mobile-label">健康</span>
                <a-badge :status="healthTagStatus(record.healthStatus)" :text="healthStatusText(record.healthStatus)" />
              </div>
              <div class="nlb-mobile-row">
                <span class="nlb-mobile-label">资源数量</span>
                <span class="nlb-mobile-value">Listener {{ record.listenerCount || 0 }} / Set {{ record.backendSetCount || 0 }} / Backend {{ record.backendCount || 0 }}</span>
              </div>
            </div>
            <div class="nlb-mobile-actions">
              <a-button size="small" @click="openDetail(record)">管理</a-button>
              <a-button size="small" @click="openEditNlb(record)">编辑</a-button>
              <a-button size="small" @click="openNsgEditor(record)">NSG</a-button>
              <a-button size="small" danger @click="askDeleteNlb(record)">删除</a-button>
            </div>
          </a-card>
        </div>
      </template>
    </a-spin>

    <div v-if="workRequests.length" class="nlb-work-summary">
      <div class="section-heading">
        <span>最近 Work Request</span>
        <a-button type="link" size="small" @click="clearFinishedWorkRequests">清理已结束</a-button>
      </div>
      <a-table size="small" row-key="id" :data-source="workRequests" :columns="workColumns" :pagination="false" :scroll="{ x: 620 }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <a-badge :status="workBadge(record.status)" :text="workRequestStatusText(record.status)" />
          </template>
          <template v-else-if="column.key === 'progress'">
            <a-progress :percent="Number(record.percentComplete || 0)" size="small" :status="record.status === 'FAILED' ? 'exception' : undefined" />
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button type="link" size="small" @click="showWorkRequest(record)">详情</a-button>
          </template>
        </template>
      </a-table>
    </div>

    <!-- NLB detail -->
    <a-modal v-model:open="detailOpen" width="1120px" wrap-class-name="nlb-responsive-modal" :z-index="1300" :footer="null" :destroy-on-close="false" title="网络负载均衡器管理">
      <a-spin :spinning="detailLoading">
        <template v-if="detail">
          <div class="detail-header">
            <div>
              <a-typography-title :level="5">{{ detail.displayName || detail.id }}</a-typography-title>
              <a-space wrap>
                <a-tag>{{ detail.nlbIpVersion || 'IPV4' }}</a-tag>
                <a-tag :color="detail.isPrivate ? 'blue' : 'orange'">{{ detail.isPrivate ? '私有 NLB' : '公有 NLB' }}</a-tag>
                <a-badge :status="healthTagStatus(detail.healthStatus)" :text="healthStatusText(detail.healthStatus)" />
              </a-space>
            </div>
            <a-space wrap>
              <a-button size="small" @click="openEditNlb(detail)">编辑</a-button>
              <a-button size="small" @click="openNsgEditor(detail)">网络安全组</a-button>
              <a-button size="small" @click="openMoveCompartment(detail)">迁移区间</a-button>
              <a-button size="small" danger @click="askDeleteNlb(detail)">删除</a-button>
            </a-space>
          </div>
          <a-alert v-if="detail.healthError" type="warning" show-icon :message="detail.healthError" class="detail-alert" />
          <a-descriptions bordered size="small" :column="{ xs: 1, sm: 2, lg: 3 }" class="detail-descriptions">
            <a-descriptions-item label="OCID"><span class="mono">{{ detail.id }}</span></a-descriptions-item>
            <a-descriptions-item label="子网">{{ detail.subnet?.displayName || detail.subnetId || '—' }}</a-descriptions-item>
            <a-descriptions-item label="生命周期">{{ detail.lifecycleState || '—' }}</a-descriptions-item>
            <a-descriptions-item label="IP 地址">{{ (detail.ipAddresses || []).map((item: any) => item.ipAddress).filter(Boolean).join(', ') || '—' }}</a-descriptions-item>
            <a-descriptions-item label="ETag"><span class="mono">{{ detail.etag || '—' }}</span></a-descriptions-item>
            <a-descriptions-item label="Listener 数量">{{ detail.listenerCount || 0 }}</a-descriptions-item>
            <a-descriptions-item label="Backend Set 数量">{{ detail.backendSetCount || 0 }}</a-descriptions-item>
            <a-descriptions-item label="Backend 数量">{{ detail.backendCount || 0 }}</a-descriptions-item>
          </a-descriptions>

          <a-tabs v-model:activeKey="detailTab" @change="onDetailTab">
            <a-tab-pane key="listeners" tab="Listener">
              <div class="op-row">
                <a-button type="primary" size="small" @click="openCreateListener">创建 Listener</a-button>
                <a-button size="small" :loading="childLoading.listeners" @click="loadListeners(true)">刷新</a-button>
              </div>
              <a-table size="small" row-key="name" :loading="childLoading.listeners" :data-source="listeners" :columns="listenerColumns" :pagination="false" :scroll="{ x: 850 }">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'action'">
                    <a-space :size="2">
                      <a-button type="link" size="small" @click="openEditListener(record)">编辑</a-button>
                      <a-button type="link" size="small" danger @click="askDeleteListener(record)">删除</a-button>
                    </a-space>
                  </template>
                </template>
              </a-table>
            </a-tab-pane>
            <a-tab-pane key="backendSets" tab="Backend Set / 健康检查">
              <div class="op-row">
                <a-button type="primary" size="small" @click="openCreateBackendSet">创建 Backend Set</a-button>
                <a-button size="small" :loading="childLoading.backendSets" @click="loadBackendSets(true)">刷新</a-button>
              </div>
              <a-table size="small" row-key="name" :loading="childLoading.backendSets" :data-source="backendSets" :columns="backendSetColumns" :pagination="false" :scroll="{ x: 1050 }">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'health'">
                    <a-badge :status="healthTagStatus(record.health?.status)" :text="healthStatusText(record.health?.status)" />
                  </template>
                  <template v-else-if="column.key === 'action'">
                    <a-space :size="2" wrap>
                      <a-button type="link" size="small" @click="openBackendSetDetail(record)">管理 Backend</a-button>
                      <a-button type="link" size="small" @click="openHealthChecker(record)">健康检查</a-button>
                      <a-button type="link" size="small" @click="openEditBackendSet(record)">编辑</a-button>
                      <a-button type="link" size="small" danger @click="askDeleteBackendSet(record)">删除</a-button>
                    </a-space>
                  </template>
                </template>
              </a-table>
            </a-tab-pane>
            <a-tab-pane key="workRequests" tab="Work Request">
              <div class="op-row"><a-button size="small" @click="refreshWorkRequests">刷新</a-button></div>
              <a-table size="small" row-key="id" :data-source="workRequests" :columns="workColumns" :pagination="false" :scroll="{ x: 620 }">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'status'"><a-badge :status="workBadge(record.status)" :text="workRequestStatusText(record.status)" /></template>
                  <template v-else-if="column.key === 'progress'"><a-progress :percent="Number(record.percentComplete || 0)" size="small" /></template>
                  <template v-else-if="column.key === 'action'"><a-button type="link" size="small" @click="showWorkRequest(record)">详情</a-button></template>
                </template>
              </a-table>
            </a-tab-pane>
          </a-tabs>
        </template>
        <a-empty v-else description="请选择一个负载均衡器" />
      </a-spin>
    </a-modal>

    <!-- Create/Edit NLB -->
    <a-modal v-model:open="nlbFormOpen" wrap-class-name="nlb-responsive-modal" :z-index="1400" :title="nlbFormMode === 'create' ? '创建网络负载均衡器' : '编辑网络负载均衡器'" :confirm-loading="formLoading" @ok="submitNlbForm">
      <a-alert v-if="nlbFormMode === 'create' && !nlbForm.isPrivate" type="warning" show-icon message="公有 NLB 会暴露到 Internet，请确认子网与安全策略。" class="form-alert" />
      <a-form layout="vertical">
        <a-form-item label="名称" required><a-input v-model:value="nlbForm.displayName" :maxlength="255" /></a-form-item>
        <a-form-item v-if="nlbFormMode === 'create'" label="子网" required>
          <a-select v-model:value="nlbForm.subnetId" show-search option-filter-prop="label" placeholder="仅显示当前 VCN 子网" :get-popup-container="popupContainer">
            <a-select-option v-for="item in options.subnets || []" :key="item.id" :value="item.id" :label="item.displayName || item.id">{{ item.displayName || item.id }}（{{ item.cidrBlock || '—' }}）</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-if="nlbFormMode === 'create'" label="类型"><a-switch v-model:checked="nlbForm.isPrivate" checked-children="私有" un-checked-children="公有" /></a-form-item>
        <a-form-item label="NLB IP 版本"><a-select v-model:value="nlbForm.nlbIpVersion"><a-select-option v-for="item in (options.nlbIpVersions || ['IPV4'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item>
        <a-form-item label="保留源/目标地址"><a-switch v-model:checked="nlbForm.isPreserveSourceDestination" @change="onPreserveSourceDestinationChange" /></a-form-item>
        <a-form-item label="对称哈希"><a-switch v-model:checked="nlbForm.isSymmetricHashEnabled" :disabled="!nlbForm.isPreserveSourceDestination" /></a-form-item>
      </a-form>
    </a-modal>

    <!-- NSG -->
    <a-modal v-model:open="nsgOpen" wrap-class-name="nlb-responsive-modal" :z-index="1400" title="网络安全组" :confirm-loading="formLoading" @ok="submitNsg">
      <a-form layout="vertical"><a-form-item label="网络安全组"><a-select v-model:value="nsgForm.networkSecurityGroupIds" mode="multiple" allow-clear :get-popup-container="popupContainer">
        <a-select-option v-for="item in options.networkSecurityGroups || []" :key="item.id" :value="item.id">{{ item.displayName || item.id }}</a-select-option>
      </a-select></a-form-item></a-form>
    </a-modal>

    <!-- Change compartment -->
    <a-modal v-model:open="moveCompartmentOpen" wrap-class-name="nlb-responsive-modal" :z-index="1450" title="迁移负载均衡器区间" :confirm-loading="moveCompartmentLoading" ok-text="继续安全验证" @ok="submitMoveCompartment">
      <a-alert type="warning" show-icon message="迁移会改变资源所属区间和 IAM 权限边界；网络转发关系保持不变，但当前账号必须同时具备源、目标区间权限。" class="form-alert" />
      <a-form layout="vertical">
        <a-form-item label="负载均衡器"><a-input :value="moveCompartmentTarget?.displayName || moveCompartmentTarget?.id" disabled /></a-form-item>
        <a-form-item label="目标区间" required>
          <a-select
            v-model:value="moveCompartmentForm.targetCompartmentId"
            show-search
            allow-clear
            option-filter-prop="label"
            placeholder="搜索并选择目标区间"
            :loading="compartmentOptionsLoading"
            :options="compartmentOptions"
            :get-popup-container="popupContainer"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- Listener -->
    <a-modal v-model:open="listenerOpen" wrap-class-name="nlb-responsive-modal" :z-index="1400" :title="listenerMode === 'create' ? '创建 Listener' : '编辑 Listener'" :confirm-loading="formLoading" width="720px" @ok="submitListener">
      <a-form layout="vertical">
        <a-form-item v-if="listenerMode === 'create'" label="名称" required><a-input v-model:value="listenerForm.name" /></a-form-item>
        <a-form-item label="默认 Backend Set" required><a-select v-model:value="listenerForm.defaultBackendSetName" :get-popup-container="popupContainer"><a-select-option v-for="item in backendSets" :key="item.name" :value="item.name">{{ item.name }}</a-select-option></a-select></a-form-item>
        <a-form-item label="端口" required><a-input-number v-model:value="listenerForm.port" :min="1" :max="65535" style="width: 100%" /></a-form-item>
        <a-form-item label="协议"><a-select v-model:value="listenerForm.protocol"><a-select-option v-for="item in (options.protocols || ['TCP'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item>
        <a-form-item label="IP 版本"><a-select v-model:value="listenerForm.ipVersion"><a-select-option v-for="item in (options.ipVersions || ['IPV4'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item>
        <a-form-item label="PPv2"><a-switch v-model:checked="listenerForm.isPpv2Enabled" /></a-form-item>
        <div class="form-grid form-grid--three">
          <a-form-item label="TCP 空闲超时"><a-input-number v-model:value="listenerForm.tcpIdleTimeout" :min="0" /></a-form-item>
          <a-form-item label="UDP 空闲超时"><a-input-number v-model:value="listenerForm.udpIdleTimeout" :min="0" /></a-form-item>
          <a-form-item label="L3 IP 空闲超时"><a-input-number v-model:value="listenerForm.l3IpIdleTimeout" :min="0" /></a-form-item>
        </div>
      </a-form>
    </a-modal>

    <!-- Backend set -->
    <a-modal v-model:open="backendSetOpen" wrap-class-name="nlb-responsive-modal" :z-index="1400" :title="backendSetMode === 'create' ? '创建 Backend Set' : '编辑 Backend Set'" :confirm-loading="formLoading" width="780px" @ok="submitBackendSet">
      <a-form layout="vertical">
        <a-form-item v-if="backendSetMode === 'create'" label="名称" required><a-input v-model:value="backendSetForm.name" /></a-form-item>
        <a-form-item label="负载均衡策略"><a-select v-model:value="backendSetForm.policy"><a-select-option v-for="item in (options.policies || ['FIVE_TUPLE'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item>
        <a-form-item label="IP 版本"><a-select v-model:value="backendSetForm.ipVersion"><a-select-option v-for="item in (options.ipVersions || ['IPV4'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item>
        <div class="checkbox-grid"><a-checkbox v-model:checked="backendSetForm.isPreserveSource" :disabled="!!detail?.isPreserveSourceDestination">保留源地址</a-checkbox><a-checkbox v-model:checked="backendSetForm.isFailOpen">故障开放</a-checkbox><a-checkbox v-model:checked="backendSetForm.isInstantFailoverEnabled">快速故障转移</a-checkbox><a-checkbox v-model:checked="backendSetForm.isInstantFailoverTcpResetEnabled" :disabled="!backendSetForm.isInstantFailoverEnabled">故障转移 TCP Reset</a-checkbox><a-checkbox v-model:checked="backendSetForm.areOperationallyActiveBackendsPreferred">优先活跃 Backend</a-checkbox></div>
        <a-divider>健康检查器</a-divider>
        <a-form-item label="协议"><a-select v-model:value="backendSetForm.healthChecker.protocol"><a-select-option v-for="item in (options.healthCheckProtocols || ['TCP'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item>
        <div class="form-grid form-grid--four"><a-form-item label="端口"><a-input-number v-model:value="backendSetForm.healthChecker.port" :min="1" :max="65535" /></a-form-item><a-form-item label="重试次数"><a-input-number v-model:value="backendSetForm.healthChecker.retries" :min="0" /></a-form-item><a-form-item label="间隔 (ms)"><a-input-number v-model:value="backendSetForm.healthChecker.intervalInMillis" :min="100" /></a-form-item><a-form-item label="超时 (ms)"><a-input-number v-model:value="backendSetForm.healthChecker.timeoutInMillis" :min="100" /></a-form-item></div>
        <template v-if="['HTTP', 'HTTPS'].includes(String(backendSetForm.healthChecker.protocol).toUpperCase())">
          <a-form-item label="URL Path"><a-input v-model:value="backendSetForm.healthChecker.urlPath" placeholder="/health" /></a-form-item>
          <a-form-item label="响应正文正则"><a-input v-model:value="backendSetForm.healthChecker.responseBodyRegex" /></a-form-item>
          <a-form-item label="预期响应码"><a-input-number v-model:value="backendSetForm.healthChecker.returnCode" :min="100" :max="599" style="width: 100%" /></a-form-item>
        </template>
        <template v-if="['TCP', 'UDP'].includes(String(backendSetForm.healthChecker.protocol).toUpperCase())">
          <a-form-item label="请求数据"><a-textarea v-model:value="backendSetForm.healthChecker.requestData" :rows="2" placeholder="文本，或 base64:..." /></a-form-item>
          <a-form-item label="预期响应数据"><a-textarea v-model:value="backendSetForm.healthChecker.responseData" :rows="2" placeholder="文本，或 base64:..." /></a-form-item>
        </template>
        <template v-if="String(backendSetForm.healthChecker.protocol).toUpperCase() === 'DNS'">
          <a-divider>DNS 查询</a-divider>
          <a-form-item label="传输协议"><a-select v-model:value="backendSetForm.healthChecker.dns.transportProtocol"><a-select-option v-for="item in (options.dnsTransportProtocols || ['UDP'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item>
          <a-form-item label="域名" required><a-input v-model:value="backendSetForm.healthChecker.dns.domainName" placeholder="example.com" /></a-form-item>
          <div class="form-grid form-grid--two"><a-form-item label="Query Class"><a-select v-model:value="backendSetForm.healthChecker.dns.queryClass"><a-select-option v-for="item in (options.dnsQueryClasses || ['IN'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item><a-form-item label="Query Type"><a-select v-model:value="backendSetForm.healthChecker.dns.queryType"><a-select-option v-for="item in (options.dnsQueryTypes || ['A'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item></div>
          <a-form-item label="允许的 RCODE"><a-select v-model:value="backendSetForm.healthChecker.dns.rcodes" mode="multiple"><a-select-option v-for="item in (options.dnsRcodes || ['NOERROR'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item>
        </template>
      </a-form>
    </a-modal>

    <!-- Health checker -->
    <a-modal v-model:open="healthOpen" wrap-class-name="nlb-responsive-modal" :z-index="1400" title="更新健康检查器" :confirm-loading="formLoading" width="780px" @ok="submitHealthChecker">
      <a-form layout="vertical">
        <a-form-item label="协议"><a-select v-model:value="healthForm.protocol"><a-select-option v-for="item in (options.healthCheckProtocols || ['TCP'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item>
        <div class="form-grid form-grid--four"><a-form-item label="端口"><a-input-number v-model:value="healthForm.port" :min="1" :max="65535" /></a-form-item><a-form-item label="重试次数"><a-input-number v-model:value="healthForm.retries" :min="0" /></a-form-item><a-form-item label="间隔 (ms)"><a-input-number v-model:value="healthForm.intervalInMillis" :min="100" /></a-form-item><a-form-item label="超时 (ms)"><a-input-number v-model:value="healthForm.timeoutInMillis" :min="100" /></a-form-item></div>
        <template v-if="['HTTP', 'HTTPS'].includes(String(healthForm.protocol).toUpperCase())">
          <a-form-item label="URL Path"><a-input v-model:value="healthForm.urlPath" /></a-form-item>
          <a-form-item label="响应正文正则"><a-input v-model:value="healthForm.responseBodyRegex" /></a-form-item>
          <a-form-item label="预期响应码"><a-input-number v-model:value="healthForm.returnCode" :min="100" :max="599" style="width: 100%" /></a-form-item>
        </template>
        <template v-if="['TCP', 'UDP'].includes(String(healthForm.protocol).toUpperCase())">
          <a-form-item label="请求数据"><a-textarea v-model:value="healthForm.requestData" :rows="2" placeholder="文本，或 base64:..." /></a-form-item>
          <a-form-item label="预期响应数据"><a-textarea v-model:value="healthForm.responseData" :rows="2" placeholder="文本，或 base64:..." /></a-form-item>
        </template>
        <template v-if="String(healthForm.protocol).toUpperCase() === 'DNS'">
          <a-divider>DNS 查询</a-divider>
          <a-form-item label="传输协议"><a-select v-model:value="healthForm.dns.transportProtocol"><a-select-option v-for="item in (options.dnsTransportProtocols || ['UDP'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item>
          <a-form-item label="域名" required><a-input v-model:value="healthForm.dns.domainName" placeholder="example.com" /></a-form-item>
          <div class="form-grid form-grid--two"><a-form-item label="Query Class"><a-select v-model:value="healthForm.dns.queryClass"><a-select-option v-for="item in (options.dnsQueryClasses || ['IN'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item><a-form-item label="Query Type"><a-select v-model:value="healthForm.dns.queryType"><a-select-option v-for="item in (options.dnsQueryTypes || ['A'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item></div>
          <a-form-item label="允许的 RCODE"><a-select v-model:value="healthForm.dns.rcodes" mode="multiple"><a-select-option v-for="item in (options.dnsRcodes || ['NOERROR'])" :key="item" :value="item">{{ item }}</a-select-option></a-select></a-form-item>
        </template>
      </a-form>
    </a-modal>

    <!-- Backend detail -->
    <a-modal v-model:open="backendDetailOpen" wrap-class-name="nlb-responsive-modal" :z-index="1400" width="900px" title="Backend 管理" :footer="null">
      <div class="op-row"><a-button type="primary" size="small" @click="openCreateBackend">添加 Backend</a-button><a-button size="small" @click="loadBackends(true)">刷新</a-button></div>
      <a-table size="small" row-key="name" :loading="childLoading.backends" :data-source="backends" :columns="backendColumns" :pagination="false" :scroll="{ x: 760 }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'health'"><a-badge :status="healthTagStatus(record.health?.status)" :text="healthStatusText(record.health?.status)" /><span v-if="record.health?.operationalStatus" class="muted"> / {{ record.health.operationalStatus }}</span></template>
          <template v-else-if="column.key === 'flags'"><a-tag v-if="record.isDrain" color="orange">Drain</a-tag><a-tag v-if="record.isBackup" color="purple">Backup</a-tag><a-tag v-if="record.isOffline" color="red">Offline</a-tag><span v-if="!record.isDrain && !record.isBackup && !record.isOffline" class="muted">正常</span></template>
          <template v-else-if="column.key === 'action'"><a-space :size="2"><a-button type="link" size="small" @click="openEditBackend(record)">编辑</a-button><a-button type="link" size="small" danger @click="askDeleteBackend(record)">删除</a-button></a-space></template>
        </template>
      </a-table>
    </a-modal>

    <!-- Backend form -->
    <a-modal v-model:open="backendOpen" wrap-class-name="nlb-responsive-modal" :z-index="1500" :title="backendMode === 'create' ? '添加 Backend' : '编辑 Backend'" :confirm-loading="formLoading" @ok="submitBackend">
      <a-form layout="vertical">
        <template v-if="backendMode === 'create'">
          <a-form-item label="名称" required><a-input v-model:value="backendForm.name" /></a-form-item>
          <a-form-item label="IP 地址"><a-input v-model:value="backendForm.ipAddress" /></a-form-item>
          <a-form-item label="目标实例 OCID"><a-input v-model:value="backendForm.targetId" /></a-form-item>
          <a-form-item label="端口" required><a-input-number v-model:value="backendForm.port" :min="1" :max="65535" style="width: 100%" /></a-form-item>
        </template>
        <a-form-item label="权重"><a-input-number v-model:value="backendForm.weight" :min="0" :max="100" style="width: 100%" /></a-form-item>
        <a-space wrap><a-checkbox v-model:checked="backendForm.isDrain">Drain</a-checkbox><a-checkbox v-model:checked="backendForm.isBackup">Backup</a-checkbox><a-checkbox v-model:checked="backendForm.isOffline">Offline</a-checkbox></a-space>
      </a-form>
    </a-modal>

    <!-- Verification -->
    <a-modal v-model:open="verifyOpen" wrap-class-name="nlb-responsive-modal" :z-index="1600" title="危险操作安全验证" :confirm-loading="verifyLoading" @ok="submitVerification" ok-text="确认执行" :ok-button-props="{ danger: true }">
      <a-alert type="warning" show-icon message="该操作会改变真实 OCI 网络流量或删除资源，请确认目标后输入 Telegram 验证码。" />
      <div class="verify-target">{{ verifyText }}</div>
      <a-input v-model:value="verifyCode" size="large" maxlength="6" inputmode="numeric" placeholder="请输入 6 位验证码" @pressEnter="submitVerification" />
      <div class="verify-footer"><span>验证码有效期 5 分钟</span><a-button type="link" size="small" :loading="verifySending" @click="resendVerification">重新发送</a-button></div>
    </a-modal>

    <!-- Work Request detail -->
    <a-modal v-model:open="workDetailOpen" wrap-class-name="nlb-responsive-modal" :z-index="1600" title="Work Request 详情" :footer="null" width="820px">
      <a-spin :spinning="workDetailLoading">
        <div class="op-row work-detail-toolbar"><span class="muted">失败时自动读取 OCI 错误和日志</span><a-button size="small" @click="refreshActiveWorkRequest">刷新诊断</a-button></div>
        <a-descriptions v-if="activeWorkRequest" bordered size="small" :column="{ xs: 1, sm: 2 }">
          <a-descriptions-item label="ID"><span class="mono">{{ activeWorkRequest.id }}</span></a-descriptions-item>
          <a-descriptions-item label="状态"><a-badge :status="workBadge(activeWorkRequest.status)" :text="workRequestStatusText(activeWorkRequest.status)" /></a-descriptions-item>
          <a-descriptions-item label="进度"><a-progress :percent="Number(activeWorkRequest.percentComplete || 0)" /></a-descriptions-item>
          <a-descriptions-item label="操作">{{ activeWorkRequest.operation || '—' }}</a-descriptions-item>
        </a-descriptions>
        <a-alert v-if="activeWorkRequest?.timedOut" type="warning" show-icon message="轮询已超时，Work Request 仍可能在 OCI 后台继续处理。" style="margin-top: 12px" />
        <a-alert v-if="activeWorkRequest?.pollError" type="warning" show-icon :message="activeWorkRequest.pollError" style="margin-top: 12px" />
        <a-alert v-if="activeWorkRequest?.diagnosticsError" type="error" show-icon :message="activeWorkRequest.diagnosticsError" style="margin-top: 12px" />
        <a-divider v-if="activeWorkRequest?.errors?.length">错误</a-divider>
        <a-list v-if="activeWorkRequest?.errors?.length" size="small" bordered :data-source="activeWorkRequest.errors"><template #renderItem="{ item }"><a-list-item>{{ item.code || '' }} {{ item.message || '' }}</a-list-item></template></a-list>
        <a-divider v-if="activeWorkRequest?.logs?.length">日志</a-divider>
        <a-list v-if="activeWorkRequest?.logs?.length" size="small" bordered :data-source="activeWorkRequest.logs"><template #renderItem="{ item }"><a-list-item>{{ item.timestamp || '' }} {{ item.message || '' }}</a-list-item></template></a-list>
        <a-empty v-if="activeWorkRequest?.terminal && !activeWorkRequest?.errors?.length && !activeWorkRequest?.logs?.length && !workDetailLoading && !activeWorkRequest?.diagnosticsError" description="OCI 未返回错误或日志" class="work-empty" />
      </a-spin>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { listCompartmentPicker } from '../../api/compartment'
import { sendVerifyCode } from '../../api/system'
import {
  changeNlbCompartment,
  createNetworkLoadBalancer,
  createNlbBackend,
  createNlbBackendSet,
  createNlbListener,
  deleteNetworkLoadBalancer,
  deleteNlbBackend,
  deleteNlbBackendSet,
  deleteNlbListener,
  getNetworkLoadBalancer,
  getNlbBackend,
  getNlbBackendSet,
  getNlbHealthChecker,
  getNlbListener,
  getNlbWorkRequest,
  getNetworkLoadBalancerOptions,
  listNetworkLoadBalancers,
  listNlbWorkRequestErrors,
  listNlbWorkRequestLogs,
  listNlbBackends,
  listNlbBackendSets,
  listNlbListeners,
  updateNetworkLoadBalancer,
  updateNlbBackend,
  updateNlbBackendSet,
  updateNlbHealthChecker,
  updateNlbListener,
  updateNlbNetworkSecurityGroups,
} from '../../api/nlb'
import type { NlbOptions, WorkRequestState } from './types'
import { healthStatusText, healthTagStatus, workRequestStatusText } from './types'

const props = defineProps<{
  open: boolean
  userId: string
  region?: string
  compartmentId?: string
  vcn: any
}>()
const emit = defineEmits<{
  (e: 'changed'): void
  (e: 'editing-overlay-change', v: boolean): void
}>()

const base = computed(() => ({
  id: props.userId,
  region: props.region?.trim() || undefined,
  compartmentId: props.compartmentId?.trim() || String(props.vcn?.compartmentId || '').trim() || undefined,
  vcnId: String(props.vcn?.id || ''),
}))
const contextError = computed(() => !base.value.id ? '未选择 OCI 租户。' : !base.value.vcnId ? '未选择 VCN。' : !base.value.compartmentId ? '当前 VCN 缺少 Compartment 上下文，无法查询 NLB。' : '')

const rows = ref<any[]>([])
const options = reactive<NlbOptions>({})
const listLoading = ref(false)
const loadError = ref('')
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<any>(null)
const detailTab = ref('listeners')
const selectedNlbId = ref('')
const listeners = ref<any[]>([])
const backendSets = ref<any[]>([])
const backends = ref<any[]>([])
const activeBackendSetName = ref('')
const childLoading = reactive({ listeners: false, backendSets: false, backends: false })
const contextGeneration = ref(0)

const nlbColumns = [
  { title: '名称', key: 'displayName', width: '16%', ellipsis: true },
  { title: '类型', key: 'exposure', width: '7%' },
  { title: 'IP 地址', key: 'ipAddresses', width: '14%' },
  { title: '子网', dataIndex: 'subnetName', key: 'subnetName', width: '13%', ellipsis: true },
  { title: '生命周期', key: 'lifecycleState', width: '10%' },
  { title: '健康', key: 'healthStatus', width: '9%' },
  { title: 'Listener / Set / Backend', key: 'counts', width: '13%' },
  { title: '操作', key: 'action', width: '18%' },
]
const listenerColumns = [
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: '协议', dataIndex: 'protocol', key: 'protocol', width: 100 },
  { title: '端口', dataIndex: 'port', key: 'port', width: 80 },
  { title: '默认 Backend Set', dataIndex: 'defaultBackendSetName', key: 'defaultBackendSetName' },
  { title: '操作', key: 'action', width: 130 },
]
const backendSetColumns = [
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: '策略', dataIndex: 'policy', key: 'policy', width: 120 },
  { title: 'Backend 数', dataIndex: 'backendCount', key: 'backendCount', width: 90 },
  { title: '健康', key: 'health', width: 100 },
  { title: '操作', key: 'action', width: 300 },
]
const backendColumns = [
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: '目标', key: 'target', customRender: ({ record }: any) => record.ipAddress || record.targetId || '—' },
  { title: '端口', dataIndex: 'port', key: 'port', width: 70 },
  { title: '权重', dataIndex: 'weight', key: 'weight', width: 70 },
  { title: '标记', key: 'flags', width: 170 },
  { title: '健康', key: 'health', width: 150 },
  { title: '操作', key: 'action', width: 120 },
]
const workColumns = [
  { title: 'Work Request', dataIndex: 'id', key: 'id', ellipsis: true },
  { title: '状态', key: 'status', width: 100 },
  { title: '进度', key: 'progress', width: 180 },
  { title: '操作', dataIndex: 'operation', key: 'operation', width: 180 },
  { title: '操作', key: 'action', width: 65 },
]

const nlbFormOpen = ref(false)
const nlbFormMode = ref<'create' | 'edit'>('create')
const nlbFormTargetId = ref('')
const formLoading = ref(false)
const nlbForm = reactive<any>(defaultNlbForm())
const nsgOpen = ref(false)
const nsgForm = reactive<any>({ networkSecurityGroupIds: [], nlb: null })
const moveCompartmentOpen = ref(false)
const moveCompartmentLoading = ref(false)
const moveCompartmentTarget = ref<any>(null)
const moveCompartmentForm = reactive<{ targetCompartmentId?: string }>({ targetCompartmentId: '' })
const compartmentOptions = ref<Array<{ label: string; value: string }>>([])
const compartmentOptionsLoading = ref(false)
const listenerOpen = ref(false)
const listenerMode = ref<'create' | 'edit'>('create')
const listenerForm = reactive<any>(defaultListenerForm())
const backendSetOpen = ref(false)
const backendSetMode = ref<'create' | 'edit'>('create')
const backendSetForm = reactive<any>(defaultBackendSetForm())
const healthOpen = ref(false)
const healthForm = reactive<any>(defaultHealthForm())
const healthTarget = ref<any>(null)
const backendDetailOpen = ref(false)
const backendOpen = ref(false)
const backendMode = ref<'create' | 'edit'>('create')
const backendForm = reactive<any>(defaultBackendForm())

const verifyOpen = ref(false)
const verifyLoading = ref(false)
const verifySending = ref(false)
const verifyCode = ref('')
const verifyAction = ref('')
const verifyContextKey = ref('')
const verifyText = ref('')
let verifyCallback: (() => Promise<void>) | null = null

const workRequests = ref<WorkRequestState[]>([])
const activeWorkRequest = ref<WorkRequestState | null>(null)
const workDetailOpen = ref(false)
const workDetailLoading = ref(false)
const workTimers = new Map<string, ReturnType<typeof setTimeout>>()

const editingOverlayOpen = computed(() => (
  detailOpen.value ||
  nlbFormOpen.value ||
  nsgOpen.value ||
  moveCompartmentOpen.value ||
  listenerOpen.value ||
  backendSetOpen.value ||
  healthOpen.value ||
  backendDetailOpen.value ||
  backendOpen.value ||
  verifyOpen.value ||
  workDetailOpen.value
))

watch(
  [() => props.open, editingOverlayOpen],
  ([open, overlayOpen]) => {
    emit('editing-overlay-change', Boolean(open && overlayOpen))
  },
  { immediate: true },
)

function defaultNlbForm() {
  return { displayName: '', subnetId: '', isPrivate: true, nlbIpVersion: 'IPV4', isPreserveSourceDestination: false, isSymmetricHashEnabled: false, ifMatch: '' }
}
function defaultListenerForm() {
  return { name: '', defaultBackendSetName: '', port: 80, protocol: 'TCP', ipVersion: 'IPV4', isPpv2Enabled: false, tcpIdleTimeout: null, udpIdleTimeout: null, l3IpIdleTimeout: null, ifMatch: '' }
}
function defaultDnsHealthForm() {
  return { transportProtocol: 'UDP', domainName: '', queryClass: 'IN', queryType: 'A', rcodes: ['NOERROR'] }
}
function defaultHealthForm() {
  return { protocol: 'TCP', port: 80, retries: 3, timeoutInMillis: 3000, intervalInMillis: 10000, urlPath: '/', responseBodyRegex: '', returnCode: 200, requestData: '', responseData: '', dns: defaultDnsHealthForm() }
}
function defaultBackendSetForm() {
  return { name: '', policy: 'FIVE_TUPLE', ipVersion: 'IPV4', isPreserveSource: true, isFailOpen: false, isInstantFailoverEnabled: false, isInstantFailoverTcpResetEnabled: true, areOperationallyActiveBackendsPreferred: false, ifMatch: '', healthChecker: defaultHealthForm() }
}
function defaultBackendForm() {
  return { name: '', ipAddress: '', targetId: '', port: 80, weight: 1, isDrain: false, isBackup: false, isOffline: false, ifMatch: '' }
}

function onPreserveSourceDestinationChange(checked: boolean) {
  if (!checked) nlbForm.isSymmetricHashEnabled = false
}

function normalizedHealthForm(source?: any) {
  const value = { ...defaultHealthForm(), ...(source || {}) }
  value.dns = { ...defaultDnsHealthForm(), ...(source?.dns || {}) }
  return value
}

function healthCheckerPayload(source: any) {
  const { ifMatch: _ifMatch, ...value } = { ...source, dns: { ...(source?.dns || {}) } }
  const protocol = String(value.protocol || '').toUpperCase()
  if (!['HTTP', 'HTTPS'].includes(protocol)) {
    value.urlPath = undefined
    value.responseBodyRegex = undefined
    value.returnCode = undefined
  }
  if (!['TCP', 'UDP'].includes(protocol)) {
    value.requestData = undefined
    value.responseData = undefined
  }
  if (protocol !== 'DNS') value.dns = undefined
  return value
}

watch(() => [props.open, props.userId, props.region, props.compartmentId, props.vcn?.id] as const, ([open]) => {
  contextGeneration.value += 1
  if (open && !contextError.value) {
    resetState()
    void loadNlbs(false)
    void loadOptions(false)
  } else if (!open) {
    resetState()
  }
}, { immediate: true })

function resetState() {
  rows.value = []
  detail.value = null
  selectedNlbId.value = ''
  listeners.value = []
  backendSets.value = []
  backends.value = []
  activeBackendSetName.value = ''
  listLoading.value = false
  detailLoading.value = false
  childLoading.listeners = false
  childLoading.backendSets = false
  childLoading.backends = false
  formLoading.value = false
  moveCompartmentLoading.value = false
  compartmentOptionsLoading.value = false
  workDetailLoading.value = false
  loadError.value = ''
  detailOpen.value = false
  nlbFormOpen.value = false
  nsgOpen.value = false
  moveCompartmentOpen.value = false
  listenerOpen.value = false
  backendSetOpen.value = false
  healthOpen.value = false
  backendDetailOpen.value = false
  backendOpen.value = false
  verifyOpen.value = false
  verifyCallback = null
  workDetailOpen.value = false
  nlbFormTargetId.value = ''
  moveCompartmentTarget.value = null
  moveCompartmentForm.targetCompartmentId = ''
  compartmentOptions.value = []
  Object.keys(options).forEach(key => delete (options as Record<string, unknown>)[key])
  activeWorkRequest.value = null
  workRequests.value.forEach(item => cancelWorkTimer(item.id))
  workRequests.value = []
}

function popupContainer(trigger?: HTMLElement) {
  return (trigger?.closest('.ant-modal-content') as HTMLElement) || document.body
}

async function loadNlbs(force = false) {
  if (contextError.value) return
  const generation = contextGeneration.value
  listLoading.value = true
  loadError.value = ''
  try {
    const response: any = await listNetworkLoadBalancers({ ...base.value, force })
    if (generation === contextGeneration.value) rows.value = response?.data || []
  } catch (error: any) {
    if (generation === contextGeneration.value) loadError.value = error?.message || '加载 NLB 失败'
  } finally { if (generation === contextGeneration.value) listLoading.value = false }
}

async function loadOptions(force = false) {
  if (contextError.value) return
  const generation = contextGeneration.value
  try {
    const response: any = await getNetworkLoadBalancerOptions({ ...base.value, force })
    if (generation === contextGeneration.value) Object.assign(options, response?.data || {})
  } catch (error: any) {
    if (generation === contextGeneration.value && !options.subnets?.length) loadError.value = error?.message || '加载 NLB 选项失败'
  }
}

async function openDetail(row: any, force = false) {
  const targetId = String(row?.id || '')
  if (!targetId) return
  const generation = contextGeneration.value
  selectedNlbId.value = targetId
  detailOpen.value = true
  detailLoading.value = true
  try {
    const response: any = await getNetworkLoadBalancer({ ...base.value, networkLoadBalancerId: targetId, force })
    if (generation !== contextGeneration.value || selectedNlbId.value !== targetId) return
    detail.value = response?.data || null
    detailTab.value = 'listeners'
    await loadListeners()
  } catch (error: any) {
    if (generation === contextGeneration.value && selectedNlbId.value === targetId) message.error(error?.message || '加载 NLB 详情失败')
  } finally {
    if (generation === contextGeneration.value && selectedNlbId.value === targetId) detailLoading.value = false
  }
}

function onDetailTab(key: string) {
  if (key === 'listeners') void loadListeners()
  else if (key === 'backendSets') void loadBackendSets()
  else if (key === 'workRequests') void refreshWorkRequests()
}

function nlbContext() { return { ...base.value, networkLoadBalancerId: selectedNlbId.value } }

async function loadListeners(force = false) {
  if (!selectedNlbId.value) return
  const generation = contextGeneration.value
  const nlbId = selectedNlbId.value
  childLoading.listeners = true
  try { const response: any = await listNlbListeners({ ...base.value, networkLoadBalancerId: nlbId, force }); if (generation === contextGeneration.value && selectedNlbId.value === nlbId) listeners.value = response?.data || [] }
  catch (error: any) { if (generation === contextGeneration.value && selectedNlbId.value === nlbId) message.error(error?.message || '加载 Listener 失败') }
  finally { if (generation === contextGeneration.value && selectedNlbId.value === nlbId) childLoading.listeners = false }
}
async function loadBackendSets(force = false) {
  if (!selectedNlbId.value) return
  const generation = contextGeneration.value
  const nlbId = selectedNlbId.value
  childLoading.backendSets = true
  try { const response: any = await listNlbBackendSets({ ...base.value, networkLoadBalancerId: nlbId, force }); if (generation === contextGeneration.value && selectedNlbId.value === nlbId) backendSets.value = response?.data || [] }
  catch (error: any) { if (generation === contextGeneration.value && selectedNlbId.value === nlbId) message.error(error?.message || '加载 Backend Set 失败') }
  finally { if (generation === contextGeneration.value && selectedNlbId.value === nlbId) childLoading.backendSets = false }
}
async function loadBackends(force = false) {
  if (!selectedNlbId.value || !activeBackendSetName.value) return
  const generation = contextGeneration.value
  const nlbId = selectedNlbId.value
  const backendSetName = activeBackendSetName.value
  childLoading.backends = true
  try { const response: any = await listNlbBackends({ ...base.value, networkLoadBalancerId: nlbId, backendSetName, force }); if (generation === contextGeneration.value && selectedNlbId.value === nlbId && activeBackendSetName.value === backendSetName) backends.value = response?.data || [] }
  catch (error: any) { if (generation === contextGeneration.value && selectedNlbId.value === nlbId && activeBackendSetName.value === backendSetName) message.error(error?.message || '加载 Backend 失败') }
  finally { if (generation === contextGeneration.value && selectedNlbId.value === nlbId && activeBackendSetName.value === backendSetName) childLoading.backends = false }
}

function openCreateNlb() { Object.assign(nlbForm, defaultNlbForm()); nlbFormTargetId.value = ''; nlbFormMode.value = 'create'; nlbFormOpen.value = true; void loadOptions(false) }
async function openEditNlb(row: any) {
  const targetId = String(row?.id || '').trim()
  if (!targetId) return message.warning('负载均衡器 OCID 不能为空')
  const generation = contextGeneration.value
  nlbFormTargetId.value = targetId
  let target = row?.id === detail.value?.id ? detail.value : row
  try {
    const response: any = await getNetworkLoadBalancer({ ...base.value, networkLoadBalancerId: targetId, force: true })
    if (generation !== contextGeneration.value) return
    target = response?.data || target
  } catch (error: any) {
    if (generation !== contextGeneration.value) return
    message.warning(error?.message || '未能刷新 NLB 详情，将使用当前数据编辑')
  }
  Object.assign(nlbForm, defaultNlbForm(), { displayName: target?.displayName || '', nlbIpVersion: target?.nlbIpVersion || 'IPV4', isPreserveSourceDestination: !!target?.isPreserveSourceDestination, isSymmetricHashEnabled: !!target?.isSymmetricHashEnabled, ifMatch: target?.etag || '' })
  nlbFormMode.value = 'edit'; nlbFormOpen.value = true
}
async function submitNlbForm() {
  if (!nlbForm.displayName?.trim()) return message.warning('请输入名称')
  if (nlbFormMode.value === 'create' && !nlbForm.subnetId) return message.warning('请选择当前 VCN 子网')
  if (nlbFormMode.value === 'edit' && !nlbFormTargetId.value) return message.warning('负载均衡器 OCID 不能为空')
  formLoading.value = true
  try {
    const response: any = nlbFormMode.value === 'create'
      ? await createNetworkLoadBalancer({ ...base.value, ...nlbForm })
      : await updateNetworkLoadBalancer({ ...base.value, networkLoadBalancerId: nlbFormTargetId.value, ...nlbForm })
    nlbFormOpen.value = false; message.success('操作已提交'); trackMutation(response?.data); await loadNlbs(true); emit('changed')
  } catch (error: any) { message.error(error?.message || '操作失败') }
  finally { formLoading.value = false }
}

async function loadFreshNlb(row: any, action: string) {
  const targetId = String(row?.id || '').trim()
  if (!targetId) { message.warning('负载均衡器 OCID 不能为空'); return null }
  const generation = contextGeneration.value
  try {
    const response: any = await getNetworkLoadBalancer({ ...base.value, networkLoadBalancerId: targetId, force: true })
    if (generation !== contextGeneration.value) return null
    return response?.data || row
  } catch (error: any) {
    if (generation !== contextGeneration.value) return null
    message.error(error?.message || `${action}前刷新 NLB 详情失败`)
    return null
  }
}

async function loadFreshChild(fetcher: () => Promise<any>, fallback: any, action: string) {
  const generation = contextGeneration.value
  try {
    const response: any = await fetcher()
    if (generation !== contextGeneration.value) return null
    return response?.data || fallback
  } catch (error: any) {
    if (generation !== contextGeneration.value) return null
    message.error(error?.message || `${action}前刷新资源详情失败`)
    return null
  }
}

async function openNsgEditor(row: any) {
  const target = await loadFreshNlb(row, '更新 NSG')
  if (!target) return
  nsgForm.nlb = target
  nsgForm.networkSecurityGroupIds = [...(target.networkSecurityGroupIds || [])]
  nsgOpen.value = true
}
async function submitNsg() {
  if (!nsgForm.nlb?.id) return
  formLoading.value = true
  try { const response: any = await updateNlbNetworkSecurityGroups({ ...base.value, networkLoadBalancerId: nsgForm.nlb.id, ifMatch: nsgForm.nlb.etag, networkSecurityGroupIds: nsgForm.networkSecurityGroupIds || [] }); nsgOpen.value = false; message.success('网络安全组更新已提交'); trackMutation(response?.data); await loadNlbs(true) }
  catch (error: any) { message.error(error?.message || '更新 NSG 失败') }
  finally { formLoading.value = false }
}

async function openMoveCompartment(row: any) {
  const target = await loadFreshNlb(row, '迁移区间')
  if (!target) return
  moveCompartmentTarget.value = target
  moveCompartmentForm.targetCompartmentId = ''
  moveCompartmentOpen.value = true
  compartmentOptions.value = []
  compartmentOptionsLoading.value = true
  try {
    const response: any = await listCompartmentPicker({ id: base.value.id })
    const currentCompartmentId = String(target.compartmentId || base.value.compartmentId || '').trim()
    compartmentOptions.value = (response?.data?.items || [])
      .filter((item: any) => String(item?.id || '').trim() && String(item.id).trim() !== currentCompartmentId)
      .map((item: any) => ({ label: item.pathLabel || item.name || item.id, value: item.id }))
  } catch (error: any) {
    message.error(error?.message || '加载目标区间失败')
  } finally {
    compartmentOptionsLoading.value = false
  }
}

async function submitMoveCompartment() {
  const target = moveCompartmentTarget.value
  const targetCompartmentId = String(moveCompartmentForm.targetCompartmentId || '').trim()
  if (!target?.id) return message.warning('负载均衡器 OCID 不能为空')
  if (!targetCompartmentId) return message.warning('请选择目标区间')
  const targetLabel = compartmentOptions.value.find(item => item.value === targetCompartmentId)?.label || targetCompartmentId
  await openVerification('changeNlbCompartment', `${base.value.id}|${target.id}|${targetCompartmentId}`, `迁移 NLB：${target.displayName || target.id} → ${targetLabel}`, async () => {
    const fresh = await loadFreshNlb(target, '迁移区间')
    if (!fresh) throw new Error('迁移前刷新 NLB 详情失败')
    moveCompartmentLoading.value = true
    try {
      const response: any = await changeNlbCompartment({ ...base.value, networkLoadBalancerId: target.id, targetCompartmentId, ifMatch: fresh.etag, verifyCode: verifyCode.value })
      moveCompartmentOpen.value = false
      detailOpen.value = false
      message.success('区间迁移已提交')
      trackMutation(response?.data)
      await loadNlbs(true)
      emit('changed')
    } finally {
      moveCompartmentLoading.value = false
    }
  })
}

function openCreateListener() { Object.assign(listenerForm, defaultListenerForm(), { defaultBackendSetName: backendSets.value[0]?.name || '' }); listenerMode.value = 'create'; listenerOpen.value = true; if (!backendSets.value.length) void loadBackendSets() }
async function openEditListener(row: any) {
  let source = row
  try { const response: any = await getNlbListener({ ...nlbContext(), listenerName: row.name, force: true }); source = response?.data || row } catch { source = row }
  Object.assign(listenerForm, defaultListenerForm(), source)
  listenerForm.ifMatch = source?.etag || row?.etag || ''
  listenerMode.value = 'edit'; listenerOpen.value = true
}
async function submitListener() {
  if (!listenerForm.defaultBackendSetName || !listenerForm.port) return message.warning('请填写默认 Backend Set 和端口')
  formLoading.value = true
  try {
    const response: any = listenerMode.value === 'create'
      ? await createNlbListener({ ...base.value, networkLoadBalancerId: selectedNlbId.value, ...listenerForm })
      : await updateNlbListener({ ...base.value, networkLoadBalancerId: selectedNlbId.value, listenerName: listenerForm.name, ...listenerForm })
    listenerOpen.value = false; message.success('Listener 操作已提交'); trackMutation(response?.data); await loadListeners(true); emit('changed')
  } catch (error: any) { message.error(error?.message || 'Listener 操作失败') }
  finally { formLoading.value = false }
}

function openCreateBackendSet() { Object.assign(backendSetForm, defaultBackendSetForm()); backendSetForm.healthChecker = defaultHealthForm(); backendSetMode.value = 'create'; backendSetOpen.value = true }
async function openEditBackendSet(row: any) {
  let source = row
  try { const response: any = await getNlbBackendSet({ ...nlbContext(), backendSetName: row.name, force: true }); source = response?.data || row } catch { source = row }
  Object.assign(backendSetForm, defaultBackendSetForm(), source)
  backendSetForm.ifMatch = source?.etag || row?.etag || ''
  backendSetForm.healthChecker = normalizedHealthForm(source?.healthChecker || row.healthChecker)
  backendSetMode.value = 'edit'; backendSetOpen.value = true
}
async function submitBackendSet() {
  if (backendSetMode.value === 'create' && !backendSetForm.name?.trim()) return message.warning('请输入 Backend Set 名称')
  if (String(backendSetForm.healthChecker?.protocol || '').toUpperCase() === 'DNS' && !backendSetForm.healthChecker?.dns?.domainName?.trim()) return message.warning('请输入 DNS 健康检查域名')
  formLoading.value = true
  try {
    if (detail.value?.isPreserveSourceDestination) backendSetForm.isPreserveSource = true
    const payload = { ...backendSetForm, backends: backendSetMode.value === 'create' ? [] : undefined, healthChecker: healthCheckerPayload(backendSetForm.healthChecker) }
    const response: any = backendSetMode.value === 'create'
      ? await createNlbBackendSet({ ...base.value, networkLoadBalancerId: selectedNlbId.value, ...payload })
      : await updateNlbBackendSet({ ...base.value, networkLoadBalancerId: selectedNlbId.value, backendSetName: backendSetForm.name, ...payload })
    backendSetOpen.value = false; message.success('Backend Set 操作已提交'); trackMutation(response?.data); await loadBackendSets(true); emit('changed')
  } catch (error: any) { message.error(error?.message || 'Backend Set 操作失败') }
  finally { formLoading.value = false }
}

async function openHealthChecker(row: any) {
  healthTarget.value = row
  try { const response: any = await getNlbHealthChecker({ ...nlbContext(), backendSetName: row.name, force: true }); Object.assign(healthForm, normalizedHealthForm(response?.data || row.healthChecker)); healthForm.ifMatch = response?.data?.etag || row.etag || '' }
  catch { Object.assign(healthForm, normalizedHealthForm(row.healthChecker)); healthForm.ifMatch = row.etag || '' }
  healthOpen.value = true
}
async function submitHealthChecker() {
  if (!healthTarget.value?.name) return
  if (String(healthForm.protocol || '').toUpperCase() === 'DNS' && !healthForm.dns?.domainName?.trim()) return message.warning('请输入 DNS 健康检查域名')
  formLoading.value = true
  try { const response: any = await updateNlbHealthChecker({ ...base.value, networkLoadBalancerId: selectedNlbId.value, backendSetName: healthTarget.value.name, ifMatch: healthForm.ifMatch, healthChecker: healthCheckerPayload(healthForm) }); healthOpen.value = false; message.success('健康检查器更新已提交'); trackMutation(response?.data); await loadBackendSets(true) }
  catch (error: any) { message.error(error?.message || '更新健康检查器失败') }
  finally { formLoading.value = false }
}

async function openBackendSetDetail(row: any) { activeBackendSetName.value = row.name; backendDetailOpen.value = true; await loadBackends() }
function openCreateBackend() { Object.assign(backendForm, defaultBackendForm()); backendMode.value = 'create'; backendOpen.value = true }
async function openEditBackend(row: any) {
  let source = row
  try { const response: any = await getNlbBackend({ ...nlbContext(), backendSetName: activeBackendSetName.value, backendName: row.name, force: true }); source = response?.data || row } catch { source = row }
  Object.assign(backendForm, defaultBackendForm(), source)
  backendForm.ifMatch = source?.etag || row?.etag || ''
  backendMode.value = 'edit'; backendOpen.value = true
}
async function submitBackend() {
  if (backendMode.value === 'create' && (!backendForm.name?.trim() || !backendForm.port)) return message.warning('请填写 Backend 名称与端口')
  if (backendMode.value === 'create' && !backendForm.ipAddress?.trim() && !backendForm.targetId?.trim()) return message.warning('Backend 的 IP 地址和目标实例 OCID 至少填写一项')
  if (backendForm.weight != null && (backendForm.weight < 0 || backendForm.weight > 100)) return message.warning('Backend 权重必须在 0-100 之间')
  formLoading.value = true
  try {
    const response: any = backendMode.value === 'create'
      ? await createNlbBackend({ ...base.value, networkLoadBalancerId: selectedNlbId.value, backendSetName: activeBackendSetName.value, backend: { ...backendForm } })
      : await updateNlbBackend({ ...base.value, networkLoadBalancerId: selectedNlbId.value, backendSetName: activeBackendSetName.value, backendName: backendForm.name, ...backendForm })
    backendOpen.value = false; message.success('Backend 操作已提交'); trackMutation(response?.data); await loadBackends(true); emit('changed')
  } catch (error: any) { message.error(error?.message || 'Backend 操作失败') }
  finally { formLoading.value = false }
}

function askDeleteNlb(row: any) { openVerification('deleteNlb', `${base.value.id}|${row.id}`, `删除 NLB：${row.displayName || row.id}`, async () => { const fresh = await loadFreshNlb(row, '删除'); if (!fresh) throw new Error('删除前刷新 NLB 详情失败'); const response: any = await deleteNetworkLoadBalancer({ ...base.value, networkLoadBalancerId: row.id, ifMatch: fresh.etag, verifyCode: verifyCode.value }); trackMutation(response?.data); await loadNlbs(true); detailOpen.value = false; emit('changed') }) }
function askDeleteListener(row: any) {
  const nlbId = selectedNlbId.value
  const listenerName = String(row?.name || '')
  openVerification('deleteNlbListener', `${base.value.id}|${nlbId}|${listenerName}`, `删除 Listener：${listenerName}`, async () => {
    const fresh = await loadFreshChild(() => getNlbListener({ ...base.value, networkLoadBalancerId: nlbId, listenerName, force: true }), row, '删除 Listener')
    if (!fresh) throw new Error('删除前刷新 Listener 详情失败')
    const response: any = await deleteNlbListener({ ...base.value, networkLoadBalancerId: nlbId, listenerName, ifMatch: fresh.etag, verifyCode: verifyCode.value })
    trackMutation(response?.data); await loadListeners(true)
  })
}
function askDeleteBackendSet(row: any) {
  const nlbId = selectedNlbId.value
  const backendSetName = String(row?.name || '')
  openVerification('deleteNlbBackendSet', `${base.value.id}|${nlbId}|${backendSetName}`, `删除 Backend Set：${backendSetName}（可能被 Listener 引用）`, async () => {
    const fresh = await loadFreshChild(() => getNlbBackendSet({ ...base.value, networkLoadBalancerId: nlbId, backendSetName, force: true }), row, '删除 Backend Set')
    if (!fresh) throw new Error('删除前刷新 Backend Set 详情失败')
    const response: any = await deleteNlbBackendSet({ ...base.value, networkLoadBalancerId: nlbId, backendSetName, ifMatch: fresh.etag, verifyCode: verifyCode.value })
    trackMutation(response?.data); await loadBackendSets(true)
  })
}
function askDeleteBackend(row: any) {
  const nlbId = selectedNlbId.value
  const backendSetName = activeBackendSetName.value
  const backendName = String(row?.name || '')
  openVerification('deleteNlbBackend', `${base.value.id}|${nlbId}|${backendSetName}|${backendName}`, `删除 Backend：${backendName}`, async () => {
    const fresh = await loadFreshChild(() => getNlbBackend({ ...base.value, networkLoadBalancerId: nlbId, backendSetName, backendName, force: true }), row, '删除 Backend')
    if (!fresh) throw new Error('删除前刷新 Backend 详情失败')
    const response: any = await deleteNlbBackend({ ...base.value, networkLoadBalancerId: nlbId, backendSetName, backendName, ifMatch: fresh.etag, verifyCode: verifyCode.value })
    trackMutation(response?.data); await loadBackends(true)
  })
}

async function openVerification(action: string, contextKey: string, text: string, callback: () => Promise<void>) {
  verifyAction.value = action; verifyContextKey.value = contextKey; verifyText.value = text; verifyCode.value = ''; verifyCallback = callback; verifySending.value = true
  try { await sendVerifyCode(action, { contextKey, contextText: text }); verifyOpen.value = true } catch (error: any) { message.error(error?.message || '发送验证码失败') }
  finally { verifySending.value = false }
}
async function resendVerification() { if (!verifyAction.value) return; verifySending.value = true; try { await sendVerifyCode(verifyAction.value, { contextKey: verifyContextKey.value, contextText: verifyText.value }) } catch (error: any) { message.error(error?.message || '发送验证码失败') } finally { verifySending.value = false } }
async function submitVerification() { if (!/^\d{6}$/.test(verifyCode.value)) return message.warning('请输入 6 位验证码'); if (!verifyCallback) return; verifyLoading.value = true; try { await verifyCallback(); verifyOpen.value = false; verifyCallback = null } catch (error: any) { message.error(error?.message || '操作失败') } finally { verifyLoading.value = false } }

function trackMutation(result: any) {
  const id = String(result?.workRequestId || '').trim()
  if (!id) { void loadNlbs(true); return }
  const item: WorkRequestState = { id, compartmentId: base.value.compartmentId, status: 'ACCEPTED', percentComplete: 0, terminal: false, successful: false, operation: result.operation, resourceId: result.resourceId }
  const existing = workRequests.value.findIndex(row => row.id === id)
  if (existing >= 0) workRequests.value.splice(existing, 1, item); else workRequests.value.unshift(item)
  pollWorkRequest(item)
}
function replaceWorkRequest(next: WorkRequestState) {
  const index = workRequests.value.findIndex(row => row.id === next.id)
  if (index >= 0) workRequests.value.splice(index, 1, next)
  else workRequests.value.unshift(next)
  if (activeWorkRequest.value?.id === next.id) activeWorkRequest.value = next
  return next
}
function workRequestContext(item: WorkRequestState) {
  return {
    id: base.value.id,
    region: base.value.region,
    compartmentId: String(item.compartmentId || base.value.compartmentId || '').trim(),
    workRequestId: item.id,
  }
}
async function loadWorkRequestDiagnostics(item: WorkRequestState, showLoading = false) {
  if (showLoading) workDetailLoading.value = true
  const generation = contextGeneration.value
  try {
    const request = workRequestContext(item)
    const [errorsResult, logsResult] = await Promise.allSettled([
      listNlbWorkRequestErrors(request),
      listNlbWorkRequestLogs(request),
    ])
    if (generation !== contextGeneration.value) return item
    const diagnosticErrors: string[] = []
    const next: WorkRequestState = { ...item, diagnosticsError: undefined }
    if (errorsResult.status === 'fulfilled') next.errors = (errorsResult.value as any)?.data || []
    else diagnosticErrors.push((errorsResult.reason as any)?.message || '读取 Work Request 错误失败')
    if (logsResult.status === 'fulfilled') next.logs = (logsResult.value as any)?.data || []
    else diagnosticErrors.push((logsResult.reason as any)?.message || '读取 Work Request 日志失败')
    next.diagnosticsError = diagnosticErrors.join('；') || undefined
    return replaceWorkRequest(next)
  } finally {
    if (showLoading) workDetailLoading.value = false
  }
}
function pollWorkRequest(item: WorkRequestState) {
  cancelWorkTimer(item.id)
  const startedAt = Date.now()
  const generation = contextGeneration.value
  const requestContext = workRequestContext(item)
  const poll = async () => {
    if (generation !== contextGeneration.value) return
    try {
      const response: any = await getNlbWorkRequest(requestContext)
      if (generation !== contextGeneration.value) return
      const current = workRequests.value.find(row => row.id === item.id) || item
      let next = replaceWorkRequest({ ...current, ...(response?.data || {}), pollError: undefined })
      if (next.terminal || Date.now() - startedAt >= 120000) {
        if (!next.terminal) next = replaceWorkRequest({ ...next, timedOut: true })
        else next = await loadWorkRequestDiagnostics(next, false)
        if (next.terminal && next.successful) { void loadNlbs(true); if (detailOpen.value) void openDetail({ id: selectedNlbId.value }, true) }
        return
      }
      workTimers.set(item.id, setTimeout(poll, 1800))
    } catch (error: any) {
      if (generation !== contextGeneration.value) return
      const current = workRequests.value.find(row => row.id === item.id) || item
      const next = replaceWorkRequest({ ...current, pollError: error?.message || '查询 Work Request 失败' })
      if (Date.now() - startedAt >= 120000) {
        replaceWorkRequest({ ...next, timedOut: true })
        return
      }
      workTimers.set(item.id, setTimeout(poll, 3000))
    }
  }
  void poll()
}
function cancelWorkTimer(id: string) { const timer = workTimers.get(id); if (timer) clearTimeout(timer); workTimers.delete(id) }
async function refreshWorkRequests() { for (const item of workRequests.value) { cancelWorkTimer(item.id); pollWorkRequest(item) } }
function clearFinishedWorkRequests() { workRequests.value = workRequests.value.filter(item => { if (item.terminal || item.timedOut) { cancelWorkTimer(item.id); return false } return true }) }
async function showWorkRequest(item: WorkRequestState) { activeWorkRequest.value = item; workDetailOpen.value = true; await refreshActiveWorkRequest() }
async function refreshActiveWorkRequest() {
  const item = activeWorkRequest.value
  if (!item) return
  const generation = contextGeneration.value
  workDetailLoading.value = true
  try {
    const response: any = await getNlbWorkRequest(workRequestContext(item))
    if (generation !== contextGeneration.value) return
    const next = replaceWorkRequest({ ...item, ...(response?.data || {}) })
    await loadWorkRequestDiagnostics(next, false)
  } catch (error: any) {
    if (generation === contextGeneration.value) replaceWorkRequest({ ...item, diagnosticsError: error?.message || '刷新 Work Request 诊断失败' })
  } finally {
    if (generation === contextGeneration.value) workDetailLoading.value = false
  }
}

function lifecycleBadge(state?: string): 'success' | 'processing' | 'error' | 'default' {
  const value = String(state || '').toUpperCase()
  if (value === 'AVAILABLE') return 'success'
  if (value.includes('ING')) return 'processing'
  if (value === 'FAILED' || value === 'TERMINATED') return 'error'
  return 'default'
}
function workBadge(status?: string): 'success' | 'processing' | 'error' | 'warning' | 'default' {
  const value = String(status || '').toUpperCase()
  if (value === 'SUCCEEDED') return 'success'
  if (value === 'FAILED') return 'error'
  if (value === 'CANCELING') return 'warning'
  if (value === 'ACCEPTED' || value === 'IN_PROGRESS') return 'processing'
  return 'default'
}

onUnmounted(() => {
  workTimers.forEach(timer => clearTimeout(timer))
  workTimers.clear()
  emit('editing-overlay-change', false)
})
</script>

<style scoped>
.nlb-panel { min-height: 220px; }
.nlb-toolbar, .detail-header, .op-row, .section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.nlb-toolbar-actions { justify-content: flex-end; margin-bottom: 10px; }
.nlb-title { margin: 0 0 2px; }
.nlb-context-alert, .detail-alert, .form-alert { margin: 10px 0; }
.nlb-work-summary { margin-top: 18px; }
.section-heading { margin: 8px 0; font-weight: 600; }
.detail-header { margin-bottom: 12px; }
.detail-header h5 { margin: 0 0 5px; }
.detail-descriptions { margin-bottom: 12px; }
.resource-link { padding: 0; max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle; }
.resource-link :deep(span) { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nlb-desktop-list { width: 100%; min-width: 0; }
.nlb-desktop-list .resource-link { display: block; max-width: 100%; text-align: left; }
.nlb-desktop-list :deep(.ant-table-cell) { min-width: 0; overflow-wrap: anywhere; }
.nlb-desktop-list :deep(.ant-badge-status-text) { margin-inline-start: 4px; white-space: normal; }
.nlb-mobile-list { display: none; }
.nlb-mobile-card + .nlb-mobile-card { margin-top: 12px; }
.nlb-mobile-header { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-width: 0; }
.nlb-mobile-name { display: block; flex: 1 1 auto; min-width: 0; max-width: none; text-align: left; }
.nlb-mobile-info { display: grid; gap: 8px; margin-top: 10px; }
.nlb-mobile-row { display: grid; grid-template-columns: 76px minmax(0, 1fr); align-items: start; gap: 8px; }
.nlb-mobile-label { color: rgba(0, 0, 0, .45); }
.nlb-mobile-value { min-width: 0; overflow-wrap: anywhere; }
.nlb-mobile-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--border); }
.nlb-mobile-actions :deep(.ant-btn) { width: 100%; }
.muted { color: rgba(0, 0, 0, .45); }
.mono { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; word-break: break-all; }
.verify-target { margin: 14px 0; padding: 10px 12px; background: #fff7e6; border: 1px solid #ffd591; border-radius: 4px; font-weight: 600; }
.verify-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; color: rgba(0, 0, 0, .45); font-size: 12px; }
.work-detail-toolbar { margin-bottom: 10px; }
.work-empty { margin-top: 12px; }
.form-grid { display: grid; width: 100%; column-gap: 16px; }
.form-grid--four { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.form-grid--three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.form-grid--two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.form-grid :deep(.ant-form-item) { min-width: 0; }
.form-grid :deep(.ant-input-number), .form-grid :deep(.ant-select) { width: 100%; }
.checkbox-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 18px; margin-bottom: 24px; }
.checkbox-grid :deep(.ant-checkbox-wrapper) { margin-inline-start: 0; }

:global(.nlb-responsive-modal .ant-modal) { max-width: calc(100vw - 24px); }
:global(.nlb-responsive-modal .ant-modal-body) { max-height: calc(100vh - 190px); max-height: calc(100dvh - 190px); overflow-y: auto; overscroll-behavior: contain; }

@media (max-width: 899px) {
  .form-grid--four, .form-grid--three { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 899px) {
  .nlb-desktop-list { display: none; }
  .nlb-mobile-list { display: block; }
}

@media (max-width: 575px) {
  .form-grid--four, .form-grid--three, .form-grid--two, .checkbox-grid { grid-template-columns: minmax(0, 1fr); }
  .nlb-toolbar, .detail-header { align-items: stretch; }
  .nlb-toolbar > *, .detail-header > * { width: 100%; }
  .nlb-toolbar :deep(.ant-space), .detail-header :deep(.ant-space) { width: 100%; }
  .nlb-toolbar :deep(.ant-space-item), .detail-header :deep(.ant-space-item) { flex: 1 1 auto; }
  .nlb-toolbar :deep(.ant-btn) { width: 100%; }
  .resource-link { max-width: 140px; }
  .verify-footer { align-items: flex-start; gap: 8px; }
  :global(.nlb-responsive-modal .ant-modal) { top: 12px; margin: 0 auto; padding-bottom: 12px; }
  :global(.nlb-responsive-modal .ant-modal-body) { max-height: calc(100vh - 160px); max-height: calc(100dvh - 160px); padding-inline: 16px; }
  :global(.nlb-responsive-modal .ant-modal-header), :global(.nlb-responsive-modal .ant-modal-footer) { padding-inline: 16px; }
}
</style>
