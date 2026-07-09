<template>
    <!-- 租户级管理 -->
    <a-modal v-model:open="openModel" :title="'租户 — ' + (tenant?.username || '')"
      :width="isMobile ? '100%' : 840" :footer="null" centered :bodyStyle="{ maxHeight: '75vh', overflow: 'auto' }"
      :mask-closable="false" :keyboard="false">
      <a-tabs v-model:activeKey="activeTabModel" @change="handleTenantTabChange">
        <a-tab-pane key="account" tab="租户信息">
          <div class="tenant-account-pane">
            <a-tooltip title="刷新租户信息">
              <button
                class="tenant-account-refresh"
                :class="{ spinning: tenantInfoLoading }"
                type="button"
                aria-label="刷新租户信息"
                :disabled="tenantInfoLoading"
                @click="handleRefreshTenantAccountInfo"
              >↻</button>
            </a-tooltip>
            <a-spin :spinning="tenantInfoLoading">
              <a-descriptions :column="1" bordered size="small" style="margin-top: 8px">
                <a-descriptions-item label="租户名称">{{ tenantInfoData.tenantName || '—' }}</a-descriptions-item>
                <a-descriptions-item label="homeRegionKey">{{ tenantInfoData.homeRegionKey || '—' }}</a-descriptions-item>
                <a-descriptions-item label="租户 ID">
                  <span style="word-break: break-all; font-size: 12px">{{ tenantInfoData.tenantId || '—' }}</span>
                </a-descriptions-item>
                <a-descriptions-item label="描述">{{ tenantInfoData.description || '—' }}</a-descriptions-item>
                <a-descriptions-item label="已订阅的区域">
                  <template v-if="tenantInfoData.subscribedRegions?.length">
                    <a-tag v-for="r in tenantInfoData.subscribedRegions" :key="r" color="blue" style="margin: 2px">{{ r }}</a-tag>
                  </template>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="订阅套餐">
                  <a-tag v-if="tenantInfoData.planType" :color="planTypeTagColor(tenantInfoData.planType)">
                    {{ tenantInfoData.planTypeLabel || formatPlanType(tenantInfoData.planType) }}
                  </a-tag>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="支付方式">
                  {{ tenantInfoData.paymentMethodLabel || formatPaymentMethod(tenantInfoData.paymentMethod) || '—' }}
                </a-descriptions-item>
                <a-descriptions-item label="账户类型">
                  <a-tag v-if="tenantInfoData.accountType" color="orange">{{ formatAccountType(tenantInfoData.accountType) }}</a-tag>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="升级状态">
                  <a-tag v-if="tenantInfoData.upgradeState" color="purple">
                    {{ tenantInfoData.upgradeStateLabel || formatUpgradeState(tenantInfoData.upgradeState) }}
                  </a-tag>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="订阅状态">
                  <a-tag v-if="tenantInfoData.subscriptionStatus || tenantInfoData.subscriptionStatusLabel"
                    :color="subscriptionStatusTagColor(tenantInfoData.subscriptionStatus)">
                    {{ tenantInfoData.subscriptionStatusLabel || formatSubscriptionStatus(tenantInfoData.subscriptionStatus) }}
                    <span v-if="tenantInfoData.subscriptionStatus && tenantInfoData.subscriptionStatusLabel"
                      style="opacity: 0.75; font-size: 11px"> ({{ tenantInfoData.subscriptionStatus }})</span>
                  </a-tag>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="货币">{{ tenantInfoData.currencyCode || '—' }}</a-descriptions-item>
                <a-descriptions-item label="已完成付款意向">
                  <a-tag v-if="tenantInfoData.isIntentToPay !== undefined && tenantInfoData.isIntentToPay !== null"
                    :color="tenantInfoData.isIntentToPay ? 'green' : 'red'">
                    {{ tenantInfoData.isIntentToPay ? '是' : '否' }}
                  </a-tag>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="开始日期">{{ formatUtcCnDate(tenantInfoData.subscriptionStartTime) }}</a-descriptions-item>
                <a-descriptions-item label="注册地">{{ formatCountryCn(tenantInfoData.registrationLocation) }}</a-descriptions-item>
                <a-descriptions-item label="订阅编号">
                  <span style="word-break: break-all; font-size: 12px">{{ tenantInfoData.subscriptionPlanNumber || '—' }}</span>
                </a-descriptions-item>
                <a-descriptions-item label="组织订阅 OCID">
                  <span style="word-break: break-all; font-size: 12px">{{ tenantInfoData.subscriptionOrgOcid || '—' }}</span>
                </a-descriptions-item>
              </a-descriptions>
            </a-spin>
          </div>
        </a-tab-pane>
        <a-tab-pane key="compartments" tab="区间">
          <CompartmentManager
            v-if="activeTabModel === 'compartments' && tenant?.id"
            :tenant-id="tenant.id"
          />
        </a-tab-pane>
        <a-tab-pane key="iam" tab="IAM策略">
          <a-alert type="info" show-icon style="margin-bottom: 10px"
            message="对应 OCI 控制台「身份与安全性 → 身份 → 策略」（经典 IAM Policy API），与身份域内的安全策略无关。只读列表。" />
          <a-space style="margin-bottom: 12px" wrap>
            <a-button type="primary" @click="loadIamPolicies" :loading="iamPoliciesLoading">
              <template #icon><ReloadOutlined /></template>加载策略
            </a-button>
            <a-input-search v-model:value="iamPolicySearchModel" placeholder="搜索名称/描述" allow-clear style="width: 220px" />
          </a-space>
          <a-table v-if="!isMobile" :data-source="filteredIamPolicies" :loading="iamPoliciesLoading" size="small"
            :pagination="{ pageSize: 15 }" row-key="id"
            v-model:expanded-row-keys="iamExpandedRowKeysModel"
            @expand="onIamExpand">
            <template #expandedRowRender="{ record }">
              <a-spin :spinning="iamPolicyDetailLoading === record.id">
                <div v-if="(iamPolicyStatements[record.id] || []).length" class="iam-statements">
                  <div v-for="(st, si) in iamPolicyStatements[record.id]" :key="si" class="iam-statement-line">{{ si + 1 }}. {{ st }}</div>
                </div>
                <a-empty v-else description="展开后加载策略语句" />
              </a-spin>
            </template>
            <a-table-column title="名称" data-index="name" key="name" :width="160" :ellipsis="true" />
            <a-table-column title="描述" data-index="description" key="description" :ellipsis="true" />
            <a-table-column title="语句数" data-index="statementCount" key="statementCount" :width="72" />
            <a-table-column title="状态" data-index="lifecycleState" key="lifecycleState" :width="88" />
            <a-table-column title="Compartment" data-index="compartmentId" key="compartmentId" :width="120" :ellipsis="true">
              <template #default="{ text }">
                <span style="font-size: 11px">{{ shortOcId(text) }}</span>
              </template>
            </a-table-column>
            <a-table-column title="创建时间" data-index="timeCreated" key="timeCreated" :width="168">
              <template #default="{ text }">{{ formatUtcCnDate(text) }}</template>
            </a-table-column>
          </a-table>
          <a-spin v-else :spinning="iamPoliciesLoading">
            <a-empty v-if="!iamPoliciesLoading && filteredIamPolicies.length === 0" description="请点击「加载策略」" />
            <div v-for="p in filteredIamPolicies" :key="p.id" class="mobile-card">
              <div class="mobile-card-header">
                <span class="mobile-card-title">{{ p.name }}</span>
                <a-tag style="margin:0">{{ p.statementCount ?? 0 }} 条</a-tag>
              </div>
              <div class="mobile-card-body">
                <div class="mobile-card-row"><span class="label">描述</span><span class="value">{{ p.description || '—' }}</span></div>
                <div class="mobile-card-row"><span class="label">状态</span><span class="value">{{ p.lifecycleState || '—' }}</span></div>
              </div>
            </div>
          </a-spin>
        </a-tab-pane>
        <a-tab-pane key="quotas" tab="账户配额">
          <div class="quota-toolbar">
            <div class="quota-region-field">
              <span class="quota-region-label">区域</span>
              <select
                v-if="isMobile"
                v-model="quotaRegionModel"
                class="quota-region-native-select"
                :disabled="quotasLoading || regionsLoading || !quotaRegionOptions.length"
                @change="onQuotaRegionChange"
              >
                <option v-for="opt in quotaRegionOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
              <a-select
                v-else
                v-model:value="quotaRegionModel"
                class="quota-region-select"
                :options="quotaRegionOptions"
                :loading="regionsLoading"
                :disabled="quotasLoading || !quotaRegionOptions.length"
                :show-search="false"
                @change="onQuotaRegionChange"
              >
                <template #option="{ label, isHomeRegion }">
                  <span class="quota-region-option">
                    <span class="quota-region-option-code">{{ label }}</span>
                    <span v-if="isHomeRegion" class="quota-region-home-mark">主区域</span>
                  </span>
                </template>
              </a-select>
            </div>
            <div class="quota-service-field">
              <span class="quota-region-label">服务</span>
              <select
                v-if="isMobile"
                v-model="quotaServiceModel"
                class="quota-region-native-select"
                :disabled="quotasLoading || !quotasList.length"
              >
                <option v-for="opt in quotaServiceOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
              <a-select
                v-else
                v-model:value="quotaServiceModel"
                class="quota-service-select"
                :options="quotaServiceOptions"
                :disabled="quotasLoading || !quotasList.length"
                :show-search="false"
              />
            </div>
            <a-input-search v-model:value="quotaSearchModel" placeholder="搜索服务/配额名" allow-clear class="quota-search" />
            <a-button type="primary" @click="loadQuotas(true)" :loading="quotasLoading">
              <template #icon><ReloadOutlined /></template>查询配额
            </a-button>
          </div>
          <a-table v-if="!isMobile" :data-source="filteredQuotas" :loading="quotasLoading" size="small"
            :pagination="{ pageSize: 20 }" :row-key="(r: any) => `${r.region || ''}:${r.serviceName}:${r.limitName}:${r.availabilityDomain || ''}`">
            <a-table-column title="服务" data-index="serviceName" key="serviceName" :width="140">
              <template #default="{ text }">
                <a-tag>{{ text }}</a-tag>
              </template>
            </a-table-column>
            <a-table-column title="配额名称" data-index="limitName" key="limitName" :ellipsis="true" />
            <a-table-column title="AD" data-index="availabilityDomain" key="ad" :width="120" :ellipsis="true">
              <template #default="{ text }">
                <span style="font-size: 12px">{{ text || '全局' }}</span>
              </template>
            </a-table-column>
            <a-table-column title="上限" data-index="limit" key="limit" :width="80" />
            <a-table-column title="已用" data-index="used" key="used" :width="80">
              <template #default="{ text }">
                <span>{{ text ?? '—' }}</span>
              </template>
            </a-table-column>
            <a-table-column title="可用" data-index="available" key="available" :width="80">
              <template #default="{ text }">
                <a-tag v-if="text !== null && text !== undefined" :color="text === 0 ? 'red' : 'green'">{{ text }}</a-tag>
                <span v-else>—</span>
              </template>
            </a-table-column>
          </a-table>
          <a-spin v-else :spinning="quotasLoading">
            <a-empty v-if="!quotasLoading && filteredQuotas.length === 0" description="无配额数据" />
            <div v-for="(q, qi) in filteredQuotas" :key="qi" class="mobile-card">
              <div class="mobile-card-header">
                <a-tag style="margin:0">{{ q.serviceName }}</a-tag>
                <a-tag v-if="q.available !== null && q.available !== undefined" :color="q.available === 0 ? 'red' : 'green'" style="margin:0">可用: {{ q.available }}</a-tag>
              </div>
              <div class="mobile-card-body">
                <div class="mobile-card-row"><span class="label">配额</span><span class="value">{{ q.limitName }}</span></div>
                <div class="mobile-card-row"><span class="label">AD</span><span class="value">{{ q.availabilityDomain || '全局' }}</span></div>
                <div class="mobile-card-row"><span class="label">上限</span><span class="value">{{ q.limit }}</span></div>
                <div class="mobile-card-row"><span class="label">已用</span><span class="value">{{ q.used ?? '—' }}</span></div>
              </div>
            </div>
          </a-spin>
        </a-tab-pane>
        <a-tab-pane key="billing" tab="账务信息">
        <a-spin :spinning="billingLoading">
          <a-space v-if="!billingData && !billingLoading" style="margin-bottom: 12px">
            <a-button type="primary" @click="loadTenantBilling">加载账务信息</a-button>
          </a-space>
          <template v-if="billingData">
            <a-row :gutter="12">
              <a-col :xs="24" :sm="12">
                <a-card size="small" :bordered="true">
                  <div style="font-size: 12px; color: var(--text-sub)">最近发票</div>
                  <div style="font-weight: 700; font-size: 16px; margin-top: 4px">
                    <span v-if="billingData.summary?.latestInvoice?.totalAmount !== undefined && billingData.summary?.latestInvoice?.totalAmount !== null">
                      {{ billingData.summary.latestInvoice.totalAmount }} {{ billingData.summary.latestInvoice.currencyCode || '' }}
                    </span>
                    <span v-else>—</span>
                  </div>
                  <div style="margin-top: 6px; font-size: 12px; color: var(--text-sub)">
                    <span v-if="billingData.summary?.latestInvoice?.invoiceNo">No: {{ billingData.summary.latestInvoice.invoiceNo }}</span>
                    <span v-else>暂无发票数据</span>
                  </div>
                </a-card>
              </a-col>
              <a-col :xs="24" :sm="12">
                <a-card size="small" :bordered="true">
                  <div style="font-size: 12px; color: var(--text-sub)">期间成本（Usage API）</div>
                  <div style="font-weight: 700; font-size: 16px; margin-top: 4px">
                    <template v-if="billingData.usage?.available && billingData.usage?.summary">
                      {{ billingData.usage.summary.totalCost ?? '—' }} {{ billingData.usage.summary.currency || '' }}
                    </template>
                    <span v-else>—</span>
                  </div>
                  <div style="margin-top: 6px; font-size: 12px; color: var(--text-sub)">
                    <span v-if="billingData.usage?.available">近 {{ billingData.usage.periodDays || billingCostDays }} 天</span>
                    <span v-else>成本数据未加载</span>
                  </div>
                </a-card>
              </a-col>
            </a-row>

            <div style="margin-top: 12px">
              <a-space wrap style="margin-bottom: 8px">
                <a-button size="small" :loading="billingLoading" @click="loadTenantBilling">
                  <template #icon><ReloadOutlined /></template>刷新账务
                </a-button>
                <span style="font-weight: 600">成本分析</span>
                <a-select v-model:value="billingCostDaysModel" style="width: 110px" :options="billingCostDayOptions" />
                <a-button size="small" type="primary" :loading="billingLoading" @click="reloadBillingCost">查询成本</a-button>
                <a v-if="billingData.links?.costAnalysis" :href="billingData.links.costAnalysis" target="_blank" rel="noopener noreferrer" style="font-size: 12px">控制台</a>
              </a-space>
              <a-alert v-if="billingData.usage && !billingData.usage.available" type="warning" show-icon
                :message="billingData.usage.reason || '成本分析不可用'" style="margin-bottom: 8px" />
              <template v-else-if="billingData.usage?.available">
                <div style="font-size: 12px; color: var(--text-sub); margin-bottom: 8px">
                  {{ formatBillingPeriod(billingData.usage.timeUsageStarted, billingData.usage.timeUsageEnded) }}
                </div>
                <div style="font-weight: 600; margin-bottom: 6px">按服务</div>
                <a-table
                  v-if="!isMobile"
                  size="small"
                  :data-source="billingData.usage.byService || []"
                  :pagination="{ pageSize: 10 }"
                  row-key="service"
                >
                  <a-table-column title="服务" data-index="service" key="service" :ellipsis="true" />
                  <a-table-column title="成本" key="cost" :width="140">
                    <template #default="{ record }">
                      {{ record.cost ?? '—' }} {{ record.currency || '' }}
                    </template>
                  </a-table-column>
                </a-table>
                <a-spin v-else :spinning="false">
                  <a-empty v-if="!(billingData.usage.byService || []).length" description="无服务分项" />
                  <div v-for="(row, i) in (billingData.usage.byService || [])" :key="row.service || i" class="mobile-card">
                    <div class="mobile-card-body">
                      <div class="mobile-card-row"><span class="label">服务</span><span class="value">{{ row.service }}</span></div>
                      <div class="mobile-card-row"><span class="label">成本</span><span class="value">{{ row.cost }} {{ row.currency || '' }}</span></div>
                    </div>
                  </div>
                </a-spin>
                <div style="font-weight: 600; margin: 12px 0 6px">按日趋势</div>
                <a-table
                  v-if="!isMobile"
                  size="small"
                  :data-source="billingData.usage.byDay || []"
                  :pagination="{ pageSize: 10 }"
                  row-key="date"
                >
                  <a-table-column title="日期" data-index="date" key="date" :width="120" />
                  <a-table-column title="成本" key="cost" :width="140">
                    <template #default="{ record }">
                      {{ record.cost ?? '—' }} {{ record.currency || '' }}
                    </template>
                  </a-table-column>
                </a-table>
                <a-spin v-else :spinning="false">
                  <a-empty v-if="!(billingData.usage.byDay || []).length" description="无按日数据" />
                  <div v-for="(row, i) in (billingData.usage.byDay || [])" :key="row.date || i" class="mobile-card">
                    <div class="mobile-card-body">
                      <div class="mobile-card-row"><span class="label">日期</span><span class="value">{{ row.date }}</span></div>
                      <div class="mobile-card-row"><span class="label">成本</span><span class="value">{{ row.cost }} {{ row.currency || '' }}</span></div>
                    </div>
                  </div>
                </a-spin>
              </template>
            </div>

            <a-alert v-if="billingData.invoices && billingData.invoices.available === false"
              type="warning" show-icon style="margin-top: 10px"
              :message="billingData.invoices.reason || '发票接口不可用'" />

            <div style="margin-top: 10px">
              <div style="font-weight: 600; margin-bottom: 6px">最近发票</div>
              <a-table
                v-if="!isMobile"
                size="small"
                :data-source="billingData.invoices?.items || []"
                :pagination="false"
                row-key="invoiceId"
              >
                <a-table-column title="发票号" data-index="invoiceNo" key="invoiceNo" :width="140" />
                <a-table-column title="状态" data-index="status" key="status" :width="120" />
                <a-table-column title="开票日期" data-index="invoiceDate" key="invoiceDate" :width="180" />
                <a-table-column title="到期日" data-index="dueDate" key="dueDate" :width="180" />
                <a-table-column title="金额" key="amount" :width="140">
                  <template #default="{ record }">
                    <span>{{ record.totalAmount ?? '—' }} {{ record.currencyCode || '' }}</span>
                  </template>
                </a-table-column>
                <a-table-column title="操作" key="action" :width="120">
                  <template #default="{ record }">
                    <a-button type="link" size="small" @click="handleDownloadInvoice(record)" :disabled="!record.invoiceId">下载PDF</a-button>
                  </template>
                </a-table-column>
              </a-table>
              <a-spin v-else :spinning="false">
                <a-empty v-if="(billingData.invoices?.items || []).length === 0" description="暂无发票" />
                <div v-for="(inv, ii) in (billingData.invoices?.items || [])" :key="inv.invoiceId || ii" class="mobile-card">
                  <div class="mobile-card-header">
                    <span class="mobile-card-title">{{ inv.invoiceNo || '—' }}</span>
                    <a-tag style="margin:0">{{ inv.status || '—' }}</a-tag>
                  </div>
                  <div class="mobile-card-body">
                    <div class="mobile-card-row"><span class="label">金额</span><span class="value">{{ inv.totalAmount ?? '—' }} {{ inv.currencyCode || '' }}</span></div>
                    <div class="mobile-card-row"><span class="label">开票</span><span class="value">{{ inv.invoiceDate || '—' }}</span></div>
                    <div class="mobile-card-row"><span class="label">到期</span><span class="value">{{ inv.dueDate || '—' }}</span></div>
                  </div>
                  <div class="mobile-card-actions">
                    <a-button type="link" size="small" @click="handleDownloadInvoice(inv)" :disabled="!inv.invoiceId">下载PDF</a-button>
                  </div>
                </div>
              </a-spin>
            </div>
          </template>
          <a-empty v-else :description="billingLoading ? '正在加载账务数据' : '暂无账务数据'" />
        </a-spin>
        </a-tab-pane>
        <a-tab-pane key="budgets" tab="成本预算">
          <a-spin :spinning="budgetsLoading">
            <div class="budget-toolbar">
              <a-space wrap>
                <a-button type="primary" size="small" @click="openCreateBudget">
                  <template #icon><PlusOutlined /></template>新建预算
                </a-button>
                <a-button size="small" :loading="budgetsLoading" @click="loadBudgets">
                  <template #icon><ReloadOutlined /></template>刷新
                </a-button>
                <a v-if="budgetsData?.links?.budgets" :href="budgetsData.links.budgets" target="_blank" rel="noopener noreferrer" style="font-size: 12px">控制台</a>
              </a-space>
            </div>

            <template v-if="budgetsList.length">
              <a-table
                v-if="!isMobile"
                class="budget-table"
                size="small"
                :data-source="budgetsList"
                :pagination="{ pageSize: 8 }"
                row-key="id"
                :row-class-name="budgetRowClassName"
                @row="budgetTableRow"
              >
                <a-table-column title="名称" data-index="displayName" key="displayName" :ellipsis="true" />
                <a-table-column title="目标" key="target" :ellipsis="true">
                  <template #default="{ record }">
                    <a-tooltip :title="formatBudgetTargetTooltip(record)">
                      <div class="budget-target-cell">{{ formatBudgetTarget(record) }}</div>
                    </a-tooltip>
                  </template>
                </a-table-column>
                <a-table-column title="预算" key="amount" :width="110">
                  <template #default="{ record }">{{ formatBudgetAmount(record) }}</template>
                </a-table-column>
                <a-table-column title="已用" key="actual" :width="170">
                  <template #default="{ record }">
                    <a-progress
                      :percent="budgetProgressPercent(record)"
                      :status="budgetProgressStatus(record)"
                      size="small"
                    />
                    <div class="budget-spend-line">{{ formatBudgetSpend(record.actualSpend, record.amount) }}</div>
                  </template>
                </a-table-column>
                <a-table-column title="周期" key="period" :width="100">
                  <template #default="{ record }">{{ formatBudgetProcessingPeriod(record.processingPeriodType) }}</template>
                </a-table-column>
                <a-table-column title="状态" key="state" :width="90">
                  <template #default="{ record }">
                    <a-tag :color="record.lifecycleState === 'ACTIVE' ? 'green' : 'default'">{{ record.lifecycleState || '—' }}</a-tag>
                  </template>
                </a-table-column>
                <a-table-column title="操作" key="action" :width="150">
                  <template #default="{ record }">
                    <a-space size="small">
                      <a-button type="link" size="small" @click.stop="openEditBudget(record)">编辑</a-button>
                      <a-popconfirm title="确定删除该成本预算？" @confirm="handleDeleteBudget(record)">
                        <a-button type="link" danger size="small" @click.stop>删除</a-button>
                      </a-popconfirm>
                    </a-space>
                  </template>
                </a-table-column>
              </a-table>

              <div v-else>
                <div v-for="b in budgetsList" :key="b.id" class="mobile-card budget-mobile-card" :class="{ 'budget-mobile-card-active': b.id === selectedBudgetId }" @click="selectBudget(b)">
                  <div class="mobile-card-header">
                    <span class="mobile-card-title">{{ b.displayName || '—' }}</span>
                    <a-tag style="margin:0" :color="b.lifecycleState === 'ACTIVE' ? 'green' : 'default'">{{ b.lifecycleState || '—' }}</a-tag>
                  </div>
                  <div class="mobile-card-body">
                    <div class="mobile-card-row"><span class="label">预算</span><span class="value">{{ formatBudgetAmount(b) }}</span></div>
                    <div class="mobile-card-row"><span class="label">已用</span><span class="value">{{ formatBudgetSpend(b.actualSpend, b.amount) }}</span></div>
                    <div class="mobile-card-row"><span class="label">预测</span><span class="value">{{ formatBudgetSpend(b.forecastedSpend, b.amount) }}</span></div>
                    <div class="mobile-card-row"><span class="label">周期</span><span class="value">{{ formatBudgetProcessingPeriod(b.processingPeriodType) }}</span></div>
                    <div class="mobile-card-row"><span class="label">目标</span><span class="value">{{ formatBudgetTarget(b) }}</span></div>
                    <a-progress :percent="budgetProgressPercent(b)" :status="budgetProgressStatus(b)" size="small" />
                  </div>
                  <div class="mobile-card-actions">
                    <a-button type="link" size="small" @click.stop="openEditBudget(b)">编辑</a-button>
                    <a-popconfirm title="确定删除该成本预算？" @confirm="handleDeleteBudget(b)">
                      <a-button type="link" danger size="small" @click.stop>删除</a-button>
                    </a-popconfirm>
                  </div>
                </div>
              </div>

              <div class="budget-alert-section" v-if="selectedBudget">
                <div class="budget-alert-header">
                  <div>
                    <div class="budget-alert-title">预算告警规则</div>
                    <div class="budget-alert-subtitle">{{ selectedBudget.displayName || '—' }} · {{ selectedBudget.alertRules?.length || 0 }} 条规则</div>
                  </div>
                  <a-space size="small" wrap>
                    <a-button size="small" :loading="budgetAlertRulesLoading" @click="reloadSelectedBudgetAlertRules">
                      <template #icon><ReloadOutlined /></template>刷新告警
                    </a-button>
                    <a-button size="small" type="primary" @click="openCreateBudgetAlertRule(selectedBudget)">
                      <template #icon><PlusOutlined /></template>新建告警
                    </a-button>
                  </a-space>
                </div>

                <a-table
                  v-if="!isMobile"
                  size="small"
                  :data-source="selectedBudgetAlertRules"
                  :pagination="false"
                  row-key="id"
                >
                  <a-table-column title="名称" data-index="displayName" key="displayName" :ellipsis="true" />
                  <a-table-column title="类型" key="type" :width="90">
                    <template #default="{ record }">{{ formatBudgetAlertType(record.type) }}</template>
                  </a-table-column>
                  <a-table-column title="阈值" key="threshold" :width="120">
                    <template #default="{ record }">{{ formatBudgetAlertThreshold(record) }}</template>
                  </a-table-column>
                  <a-table-column title="电子邮件收件人" data-index="recipients" key="recipients" :ellipsis="true" />
                  <a-table-column title="状态" key="state" :width="90">
                    <template #default="{ record }">
                      <a-tag :color="record.lifecycleState === 'ACTIVE' ? 'green' : 'default'">{{ record.lifecycleState || '—' }}</a-tag>
                    </template>
                  </a-table-column>
                  <a-table-column title="操作" key="action" :width="140">
                    <template #default="{ record }">
                      <a-space size="small">
                        <a-button type="link" size="small" @click="openEditBudgetAlertRule(record)">编辑</a-button>
                        <a-popconfirm title="确定删除该告警规则？" @confirm="handleDeleteBudgetAlertRule(record)">
                          <a-button type="link" danger size="small">删除</a-button>
                        </a-popconfirm>
                      </a-space>
                    </template>
                  </a-table-column>
                </a-table>

                <div v-else>
                  <a-empty v-if="selectedBudgetAlertRules.length === 0" description="暂无告警规则" />
                  <div v-for="r in selectedBudgetAlertRules" :key="r.id" class="mobile-card">
                    <div class="mobile-card-header">
                      <span class="mobile-card-title">{{ r.displayName || '—' }}</span>
                      <a-tag style="margin:0" :color="r.lifecycleState === 'ACTIVE' ? 'green' : 'default'">{{ r.lifecycleState || '—' }}</a-tag>
                    </div>
                    <div class="mobile-card-body">
                      <div class="mobile-card-row"><span class="label">类型</span><span class="value">{{ formatBudgetAlertType(r.type) }}</span></div>
                      <div class="mobile-card-row"><span class="label">阈值</span><span class="value">{{ formatBudgetAlertThreshold(r) }}</span></div>
                      <div class="mobile-card-row"><span class="label">电子邮件收件人</span><span class="value">{{ r.recipients || '—' }}</span></div>
                      <div class="mobile-card-row"><span class="label">电子邮件</span><span class="value">{{ r.message || '—' }}</span></div>
                    </div>
                    <div class="mobile-card-actions">
                      <a-button type="link" size="small" @click="openEditBudgetAlertRule(r)">编辑</a-button>
                      <a-popconfirm title="确定删除该告警规则？" @confirm="handleDeleteBudgetAlertRule(r)">
                        <a-button type="link" danger size="small">删除</a-button>
                      </a-popconfirm>
                    </div>
                  </div>
                </div>
              </div>
            </template>

            <a-empty v-else :description="budgetsLoading ? '正在加载成本预算' : '暂无成本预算'" />
          </a-spin>
        </a-tab-pane>
        <a-tab-pane key="regions" tab="区域管理">
          <a-spin :spinning="regionsLoading">
            <div class="region-toolbar">
              <a-space wrap>
                <a-button type="primary" size="small" :loading="regionsLoading" @click="loadRegions(false, true)">
                  <template #icon><ReloadOutlined /></template>刷新
                </a-button>
                <a-input-search
                  v-model:value="regionSearchModel"
                  placeholder="搜索区域/标识符"
                  allow-clear
                  class="region-search"
                />
                <a v-if="regionsData?.links?.regions" :href="regionsData.links.regions" target="_blank" rel="noopener noreferrer" style="font-size: 12px">控制台</a>
              </a-space>
            </div>

            <a-table
              v-if="!isMobile"
              class="region-table"
              :data-source="filteredRegions"
              :loading="regionsLoading"
              size="small"
              :pagination="{ pageSize: 10 }"
              row-key="regionKey"
            >
              <a-table-column title="区域" key="region" :ellipsis="true">
                <template #default="{ record }">
                  <div class="region-name-cell">
                    <span class="region-name-main">{{ formatRegionDisplay(record) }}</span>
                    <a-tag v-if="record.isHomeRegion" color="blue" style="margin:0">主区域</a-tag>
                  </div>
                  <div class="region-key-line">{{ record.regionKey || '—' }}</div>
                </template>
              </a-table-column>
              <a-table-column title="区域标识符" data-index="regionName" key="regionName" :width="190" :ellipsis="true" />
              <a-table-column title="订阅状态" key="status" :width="120">
                <template #default="{ record }">
                  <a-tag :color="regionStatusColor(record.status)">{{ formatRegionStatus(record.status) }}</a-tag>
                </template>
              </a-table-column>
              <a-table-column title="操作" key="action" :width="100">
                <template #default="{ record }">
                  <a-button
                    v-if="record.canSubscribe"
                    type="primary"
                    size="small"
                    :loading="regionSubscribeSendingKey === record.regionKey"
                    @click="confirmSubscribeRegion(record)"
                  >
                    订阅
                  </a-button>
                  <span v-else class="region-action-empty">—</span>
                </template>
              </a-table-column>
            </a-table>

            <div v-else>
              <a-empty v-if="!regionsLoading && filteredRegions.length === 0" description="暂无区域数据" />
              <div v-for="r in filteredRegions" :key="r.regionKey || r.regionName" class="mobile-card region-mobile-card">
                <div class="mobile-card-header">
                  <span class="mobile-card-title">{{ formatRegionDisplay(r) }}</span>
                  <a-tag :color="regionStatusColor(r.status)" style="margin:0">{{ formatRegionStatus(r.status) }}</a-tag>
                </div>
                <div class="mobile-card-body">
                  <div class="mobile-card-row"><span class="label">标识符</span><span class="value">{{ r.regionName || '—' }}</span></div>
                  <div class="mobile-card-row"><span class="label">区域 Key</span><span class="value">{{ r.regionKey || '—' }}</span></div>
                  <div class="mobile-card-row"><span class="label">主区域</span><span class="value">{{ r.isHomeRegion ? '是' : '否' }}</span></div>
                </div>
                <div v-if="r.canSubscribe" class="mobile-card-actions">
                  <a-button
                    type="primary"
                    size="small"
                    :loading="regionSubscribeSendingKey === r.regionKey"
                    @click="confirmSubscribeRegion(r)"
                  >
                    订阅
                  </a-button>
                </div>
              </div>
            </div>
          </a-spin>
        </a-tab-pane>
        <a-tab-pane key="announcements" tab="云公告">
          <a-space style="margin-bottom: 12px" wrap>
            <a-button type="primary" @click="loadAnnouncements" :loading="announcementsLoading">
              <template #icon><ReloadOutlined /></template>加载公告
            </a-button>
            <a-input-search v-model:value="announcementSearchModel" placeholder="搜索摘要/工单号/类型" allow-clear style="width: 240px" />
          </a-space>
          <div v-if="announcementsRetentionNote" style="font-size: 12px; color: var(--text-sub); margin-bottom: 8px">
            {{ announcementsRetentionNote }}
          </div>
          <a-table v-if="!isMobile" :data-source="filteredAnnouncements" :loading="announcementsLoading" size="small"
            :pagination="{ pageSize: 15 }" row-key="id"
            :custom-row="announcementCustomRow">
            <a-table-column title="摘要" data-index="summary" key="summary" :ellipsis="true" />
            <a-table-column title="类型" data-index="announcementType" key="announcementType" :width="120" />
            <a-table-column title="发布时间" data-index="timeCreated" key="timeCreated" :width="168">
              <template #default="{ text }">{{ formatUtcCnDate(text) }}</template>
            </a-table-column>
            <a-table-column title="阅读状态" data-index="userStatus" key="userStatus" :width="88">
              <template #default="{ text }">
                <a-tag :color="announcementStatusColor(text)">{{ formatAnnouncementUserStatus(text) }}</a-tag>
              </template>
            </a-table-column>
            <a-table-column title="工单号" data-index="referenceTicketNumber" key="referenceTicketNumber" :width="120" :ellipsis="true" />
            <a-table-column title="操作" key="action" :width="150">
              <template #default="{ record }">
                <a-space size="small">
                  <a-button type="link" size="small" @click.stop="openAnnouncementDetail(record)">详情</a-button>
                  <a-popconfirm
                    v-if="isAnnouncementUnread(record)"
                    title="确认标记为已读？"
                    ok-text="确定"
                    cancel-text="取消"
                    @confirm="markAnnouncementAsRead(record)"
                  >
                    <a-button
                      type="link"
                      size="small"
                      :loading="announcementReadUpdatingId === record.id"
                      @click.stop
                    >
                      标记已读
                    </a-button>
                  </a-popconfirm>
                </a-space>
              </template>
            </a-table-column>
          </a-table>
          <a-spin v-else :spinning="announcementsLoading">
            <a-empty v-if="!announcementsLoading && filteredAnnouncements.length === 0" description="请点击「加载公告」" />
            <div v-for="a in filteredAnnouncements" :key="a.id" class="mobile-card" @click="openAnnouncementDetail(a)">
              <div class="mobile-card-header">
                <span class="mobile-card-title">{{ a.summary || '—' }}</span>
                <a-tag :color="announcementStatusColor(a.userStatus)" style="margin:0">{{ formatAnnouncementUserStatus(a.userStatus) }}</a-tag>
              </div>
              <div class="mobile-card-body">
                <div class="mobile-card-row"><span class="label">类型</span><span class="value">{{ a.announcementType || '—' }}</span></div>
                <div class="mobile-card-row"><span class="label">时间</span><span class="value">{{ formatUtcCnDate(a.timeCreated) }}</span></div>
              </div>
              <div v-if="isAnnouncementUnread(a)" class="mobile-card-actions" @click.stop>
                <a-popconfirm
                  title="确认标记为已读？"
                  ok-text="确定"
                  cancel-text="取消"
                  @confirm="markAnnouncementAsRead(a)"
                >
                  <a-button
                    type="link"
                    size="small"
                    :loading="announcementReadUpdatingId === a.id"
                    @click.stop
                  >
                    标记已读
                  </a-button>
                </a-popconfirm>
              </div>
            </div>
          </a-spin>
        </a-tab-pane>
      </a-tabs>
    </a-modal>

    <a-modal
      :mask-closable="false"
      :keyboard="false"
      v-model:open="regionSubscribeVerifyVisibleModel"
      title="安全验证 — 订阅区域"
      :width="isMobile ? '100%' : 420"
      :confirm-loading="regionSubscribeLoading"
      ok-text="确认订阅"
      @ok="submitRegionSubscribe"
    >
      <a-alert type="info" show-icon message="验证码已发送至 Telegram" style="margin-bottom: 12px" />
      <div class="region-verify-target">
        <div class="region-verify-name">{{ regionSubscribeTargetDisplay }}</div>
        <div class="region-verify-meta">{{ regionSubscribeTarget?.regionName || '—' }} · {{ regionSubscribeTarget?.regionKey || '—' }}</div>
      </div>
      <a-input
        v-model:value="regionSubscribeCodeModel"
        placeholder="请输入 6 位验证码"
        size="large"
        :maxlength="6"
        inputmode="numeric"
        allow-clear
        @pressEnter="submitRegionSubscribe"
      />
      <div class="region-verify-actions">
        <span>验证码有效期 5 分钟</span>
        <a-button type="link" size="small" :loading="regionSubscribeCodeSending" @click="resendRegionSubscribeCode">重新发送</a-button>
      </div>
    </a-modal>

    <a-modal
      :mask-closable="false"
      :keyboard="false"
      v-model:open="budgetFormVisibleModel"
      :title="budgetFormMode === 'create' ? '新建成本预算' : '编辑成本预算'"
      :width="isMobile ? '100%' : 620"
      :confirm-loading="budgetFormLoading"
      @ok="submitBudgetForm"
    >
      <a-form layout="vertical" class="budget-form">
        <a-form-item label="名称" required>
          <a-input v-model:value="budgetForm.displayName" allow-clear />
        </a-form-item>
        <a-form-item label="金额" required>
          <a-input-number v-model:value="budgetForm.amount" :min="1" style="width: 100%" />
        </a-form-item>
        <a-form-item label="描述">
          <a-textarea v-model:value="budgetForm.description" :rows="2" allow-clear />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :xs="24" :sm="12">
            <a-form-item label="处理周期">
              <a-select v-model:value="budgetForm.processingPeriodType" :options="budgetProcessingPeriodOptions" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="12">
            <a-form-item label="重置周期">
              <a-select v-model:value="budgetForm.resetPeriod" :options="budgetResetPeriodOptions" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="每月中的第几天开始处理预算">
          <a-input-number v-model:value="budgetForm.budgetProcessingPeriodStartOffset" :min="1" :max="31" style="width: 100%" allow-clear />
        </a-form-item>
        <a-row v-if="budgetForm.processingPeriodType === 'SINGLE_USE'" :gutter="12">
          <a-col :xs="24" :sm="12">
            <a-form-item label="开始日期">
              <a-input v-model:value="budgetForm.startDate" placeholder="2026-06-01" allow-clear />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="12">
            <a-form-item label="结束日期">
              <a-input v-model:value="budgetForm.endDate" placeholder="2026-06-30" allow-clear />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="预算所在区间" required>
          <a-select
            v-model:value="budgetForm.compartmentId"
            :options="budgetCompartmentOptions"
            :loading="budgetCompartmentsLoading"
            :disabled="budgetFormMode === 'edit'"
            show-search
            option-filter-prop="label"
            :filter-option="filterBudgetCompartmentOption"
          />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :xs="24" :sm="8">
            <a-form-item label="目标类型" required>
              <a-select
                v-model:value="budgetForm.targetType"
                :options="budgetTargetTypeOptions"
                :disabled="budgetFormMode === 'edit'"
                @change="onBudgetTargetTypeChange"
              />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="16">
            <a-form-item :label="budgetForm.targetType === 'TAG' ? '目标标签' : '目标区间'" required>
              <a-select
                v-if="budgetForm.targetType === 'COMPARTMENT'"
                v-model:value="budgetForm.target"
                :options="budgetTargetCompartmentOptions"
                :loading="budgetCompartmentsLoading"
                :disabled="budgetFormMode === 'edit'"
                show-search
                option-filter-prop="label"
                :filter-option="filterBudgetCompartmentOption"
              />
              <a-input v-else v-model:value="budgetForm.target" :disabled="budgetFormMode === 'edit'" allow-clear />
            </a-form-item>
          </a-col>
        </a-row>
      </a-form>
    </a-modal>

    <a-modal
      :mask-closable="false"
      :keyboard="false"
      v-model:open="budgetAlertFormVisibleModel"
      :title="budgetAlertFormMode === 'create' ? '新建预算告警' : '编辑预算告警'"
      :width="isMobile ? '100%' : 560"
      :confirm-loading="budgetAlertFormLoading"
      @ok="submitBudgetAlertForm"
    >
      <a-form layout="vertical" class="budget-form">
        <a-form-item label="名称" required>
          <a-input v-model:value="budgetAlertForm.displayName" allow-clear />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :xs="24" :sm="12">
            <a-form-item label="告警类型">
              <a-select v-model:value="budgetAlertForm.type" :options="budgetAlertTypeOptions" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="12">
            <a-form-item label="阈值类型">
              <a-select v-model:value="budgetAlertForm.thresholdType" :options="budgetThresholdTypeOptions" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="阈值" required>
          <a-input-number v-model:value="budgetAlertForm.threshold" :min="1" style="width: 100%" />
        </a-form-item>
        <a-form-item label="电子邮件收件人" required>
          <a-input v-model:value="budgetAlertForm.recipients" placeholder="name@example.com, team@example.com" allow-clear />
        </a-form-item>
        <a-form-item label="电子邮件">
          <a-textarea v-model:value="budgetAlertForm.message" :rows="2" allow-clear />
        </a-form-item>
        <a-form-item label="描述（可选）">
          <a-textarea v-model:value="budgetAlertForm.description" :rows="2" allow-clear />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-drawer :mask-closable="false" :keyboard="false"
      v-model:open="announcementDrawerVisibleModel"
      :title="announcementDrawerTitle"
      :width="isMobile ? '100%' : 720"
      placement="right"
      :destroy-on-close="true"
    >
      <a-spin :spinning="announcementDetailLoading">
        <a-tabs v-model:activeKey="announcementDetailTabModel">
          <a-tab-pane key="detail" tab="详情">
            <template v-if="announcementDetail">
              <a-descriptions :column="1" bordered size="small">
                <a-descriptions-item label="摘要">{{ announcementDetail.summary || '—' }}</a-descriptions-item>
                <a-descriptions-item label="公告 ID">
                  <span style="word-break: break-all; font-size: 12px">{{ announcementDetail.id || '—' }}</span>
                </a-descriptions-item>
                <a-descriptions-item label="工单号">{{ announcementDetail.referenceTicketNumber || '—' }}</a-descriptions-item>
                <a-descriptions-item label="类型">{{ announcementDetail.announcementType || '—' }}</a-descriptions-item>
                <a-descriptions-item label="平台">{{ announcementDetail.platformType || '—' }}</a-descriptions-item>
                <a-descriptions-item label="状态">{{ announcementDetail.lifecycleState || '—' }}</a-descriptions-item>
                <a-descriptions-item label="阅读状态">
                  <a-space size="small">
                    <a-tag :color="announcementStatusColor(announcementDetail.userStatus)">
                      {{ formatAnnouncementUserStatus(announcementDetail.userStatus) }}
                    </a-tag>
                    <a-popconfirm
                      v-if="isAnnouncementUnread(announcementDetail)"
                      title="确认标记为已读？"
                      ok-text="确定"
                      cancel-text="取消"
                      @confirm="markAnnouncementAsRead(announcementDetail)"
                    >
                      <a-button
                        type="link"
                        size="small"
                        :loading="announcementReadUpdatingId === announcementDetail.id"
                      >
                        标记已读
                      </a-button>
                    </a-popconfirm>
                  </a-space>
                </a-descriptions-item>
                <a-descriptions-item label="涉及服务">
                  <template v-if="announcementDetail.services?.length">
                    <a-tag v-for="s in announcementDetail.services" :key="s" style="margin: 2px">{{ s }}</a-tag>
                  </template>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="受影响区域">
                  <template v-if="announcementDetail.affectedRegions?.length">
                    <a-tag v-for="r in announcementDetail.affectedRegions" :key="r" color="blue" style="margin: 2px">{{ r }}</a-tag>
                  </template>
                  <span v-else>—</span>
                </a-descriptions-item>
                <a-descriptions-item label="环境">{{ announcementDetail.environmentName || '—' }}</a-descriptions-item>
                <a-descriptions-item label="创建时间">{{ formatUtcCnDate(announcementDetail.timeCreated) }}</a-descriptions-item>
                <a-descriptions-item label="更新时间">{{ formatUtcCnDate(announcementDetail.timeUpdated) }}</a-descriptions-item>
                <a-descriptions-item v-if="announcementDetail.chainId" label="链 ID">
                  <span style="word-break: break-all; font-size: 12px">{{ announcementDetail.chainId }}</span>
                </a-descriptions-item>
              </a-descriptions>
              <div v-if="announcementDetail.description" class="announcement-block">
                <div class="announcement-block-title">描述</div>
                <div
                  class="announcement-description"
                  v-html="formatAnnouncementBody(announcementDetail.description)"
                />
              </div>
              <div v-if="announcementDetail.additionalInformation" class="announcement-block">
                <div class="announcement-block-title">附加信息</div>
                <div
                  class="announcement-description"
                  v-html="formatAnnouncementBody(announcementDetail.additionalInformation)"
                />
              </div>
            </template>
            <a-empty v-else description="暂无详情" />
          </a-tab-pane>
          <a-tab-pane key="impacted" tab="受影响资源">
            <a-table
              v-if="announcementImpacted.length"
              size="small"
              :data-source="announcementImpacted"
              :pagination="false"
              row-key="resourceId"
            >
              <a-table-column title="资源名称" data-index="resourceName" key="resourceName" :ellipsis="true" />
              <a-table-column title="资源 ID" data-index="resourceId" key="resourceId" :ellipsis="true">
                <template #default="{ text }">
                  <span style="font-size: 11px; word-break: break-all">{{ text || '—' }}</span>
                </template>
              </a-table-column>
              <a-table-column title="区域" data-index="region" key="region" :width="140" />
            </a-table>
            <a-empty v-else description="无受影响资源" />
          </a-tab-pane>
          <a-tab-pane key="history" tab="公告历史">
            <a-alert v-if="!announcementDetail?.chainId" type="info" show-icon message="该公告无 chainId，无关联历史条目。" />
            <a-table
              v-else-if="announcementHistory.length"
              size="small"
              :data-source="announcementHistory"
              :pagination="{ pageSize: 10 }"
              row-key="id"
            >
              <a-table-column title="摘要" data-index="summary" key="summary" :ellipsis="true" />
              <a-table-column title="类型" data-index="announcementType" key="announcementType" :width="110" />
              <a-table-column title="时间" data-index="timeCreated" key="timeCreated" :width="168">
                <template #default="{ text }">{{ formatUtcCnDate(text) }}</template>
              </a-table-column>
              <a-table-column title="操作" key="action" :width="72">
                <template #default="{ record }">
                  <a-button type="link" size="small" @click="openAnnouncementDetail(record)">查看</a-button>
                </template>
              </a-table-column>
            </a-table>
            <a-empty v-else-if="announcementDetail?.chainId" description="同链无其它历史公告" />
          </a-tab-pane>
        </a-tabs>
      </a-spin>
    </a-drawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { defineAppAsyncComponent } from '../../../utils/asyncComponent'

defineOptions({ name: 'TenantManagementModal' })

const CompartmentManager = defineAppAsyncComponent(() => import('../../../components/CompartmentManager.vue'), { loading: 'none' })

type Fn = (...args: any[]) => any

const props = defineProps<{
  open: boolean
  activeTab: string
  tenant: any | null
  isMobile: boolean
  tenantInfoLoading: boolean
  tenantInfoData: any
  billingLoading: boolean
  billingData: any | null
  billingCostDays: number
  billingCostDayOptions: any[]
  budgetsLoading: boolean
  budgetsData: any | null
  budgetsList: any[]
  selectedBudgetId: string
  selectedBudget: any | null
  selectedBudgetAlertRules: any[]
  budgetAlertRulesLoading: boolean
  budgetCompartmentsLoading: boolean
  budgetFormVisible: boolean
  budgetFormLoading: boolean
  budgetFormMode: 'create' | 'edit'
  budgetForm: any
  budgetTargetTypeOptions: any[]
  budgetProcessingPeriodOptions: any[]
  budgetResetPeriodOptions: any[]
  budgetCompartmentOptions: any[]
  budgetTargetCompartmentOptions: any[]
  budgetAlertFormVisible: boolean
  budgetAlertFormLoading: boolean
  budgetAlertFormMode: 'create' | 'edit'
  budgetAlertForm: any
  budgetAlertTypeOptions: any[]
  budgetThresholdTypeOptions: any[]
  regionsLoading: boolean
  regionsData: any | null
  regionSearch: string
  filteredRegions: any[]
  regionSubscribeSendingKey: string
  regionSubscribeVerifyVisible: boolean
  regionSubscribeLoading: boolean
  regionSubscribeCodeSending: boolean
  regionSubscribeCode: string
  regionSubscribeTarget: any | null
  regionSubscribeTargetDisplay: string
  iamPoliciesLoading: boolean
  filteredIamPolicies: any[]
  iamPolicySearch: string
  iamExpandedRowKeys: any[]
  iamPolicyStatements: Record<string, string[]>
  iamPolicyDetailLoading: string
  quotasLoading: boolean
  quotasList: any[]
  quotaRegion: string
  quotaRegionOptions: any[]
  quotaService: string
  quotaServiceOptions: any[]
  quotaSearch: string
  filteredQuotas: any[]
  announcementsLoading: boolean
  filteredAnnouncements: any[]
  announcementsRetentionNote: string
  announcementSearch: string
  announcementReadUpdatingId: string
  announcementDrawerVisible: boolean
  announcementDrawerTitle: string
  announcementDetailLoading: boolean
  announcementDetailTab: string
  announcementDetail: any | null
  announcementImpacted: any[]
  announcementHistory: any[]
  onTenantTabChange: Fn
  handleRefreshTenantAccountInfo: Fn
  planTypeTagColor: Fn
  formatPlanType: Fn
  formatPaymentMethod: Fn
  formatAccountType: Fn
  formatUpgradeState: Fn
  formatSubscriptionStatus: Fn
  subscriptionStatusTagColor: Fn
  formatUtcCnDate: Fn
  formatCountryCn: Fn
  loadIamPolicies: Fn
  onIamExpand: Fn
  shortOcId: Fn
  onQuotaRegionChange: Fn
  loadQuotas: Fn
  loadTenantBilling: Fn
  reloadBillingCost: Fn
  formatBillingPeriod: Fn
  handleDownloadInvoice: Fn
  openCreateBudget: Fn
  loadBudgets: Fn
  budgetRowClassName: Fn
  budgetTableRow: Fn
  formatBudgetTargetTooltip: Fn
  formatBudgetTarget: Fn
  formatBudgetAmount: Fn
  budgetProgressPercent: Fn
  budgetProgressStatus: Fn
  formatBudgetSpend: Fn
  formatBudgetProcessingPeriod: Fn
  openEditBudget: Fn
  handleDeleteBudget: Fn
  selectBudget: Fn
  reloadSelectedBudgetAlertRules: Fn
  openCreateBudgetAlertRule: Fn
  formatBudgetAlertType: Fn
  formatBudgetAlertThreshold: Fn
  openEditBudgetAlertRule: Fn
  handleDeleteBudgetAlertRule: Fn
  submitBudgetForm: Fn
  filterBudgetCompartmentOption: Fn
  onBudgetTargetTypeChange: Fn
  submitBudgetAlertForm: Fn
  loadRegions: Fn
  formatRegionDisplay: Fn
  regionStatusColor: Fn
  formatRegionStatus: Fn
  confirmSubscribeRegion: Fn
  submitRegionSubscribe: Fn
  resendRegionSubscribeCode: Fn
  loadAnnouncements: Fn
  announcementCustomRow: Fn
  announcementStatusColor: Fn
  formatAnnouncementUserStatus: Fn
  isAnnouncementUnread: Fn
  openAnnouncementDetail: Fn
  markAnnouncementAsRead: Fn
  formatAnnouncementBody: Fn
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:activeTab', value: string): void
  (e: 'update:iamPolicySearch', value: string): void
  (e: 'update:iamExpandedRowKeys', value: any[]): void
  (e: 'update:quotaRegion', value: string): void
  (e: 'update:quotaService', value: string): void
  (e: 'update:quotaSearch', value: string): void
  (e: 'update:billingCostDays', value: number): void
  (e: 'update:regionSearch', value: string): void
  (e: 'update:regionSubscribeVerifyVisible', value: boolean): void
  (e: 'update:regionSubscribeCode', value: string): void
  (e: 'update:budgetFormVisible', value: boolean): void
  (e: 'update:budgetAlertFormVisible', value: boolean): void
  (e: 'update:announcementSearch', value: string): void
  (e: 'update:announcementDrawerVisible', value: boolean): void
  (e: 'update:announcementDetailTab', value: string): void
}>()

const openModel = computed({ get: () => props.open, set: (value) => emit('update:open', value) })
const activeTabModel = computed({ get: () => props.activeTab, set: (value) => emit('update:activeTab', String(value)) })
const iamPolicySearchModel = computed({ get: () => props.iamPolicySearch, set: (value) => emit('update:iamPolicySearch', value) })
const iamExpandedRowKeysModel = computed({ get: () => props.iamExpandedRowKeys, set: (value) => emit('update:iamExpandedRowKeys', value) })
const quotaRegionModel = computed({ get: () => props.quotaRegion, set: (value) => emit('update:quotaRegion', value) })
const quotaServiceModel = computed({ get: () => props.quotaService, set: (value) => emit('update:quotaService', value) })
const quotaSearchModel = computed({ get: () => props.quotaSearch, set: (value) => emit('update:quotaSearch', value) })
const billingCostDaysModel = computed({ get: () => props.billingCostDays, set: (value) => emit('update:billingCostDays', value) })
const regionSearchModel = computed({ get: () => props.regionSearch, set: (value) => emit('update:regionSearch', value) })
const regionSubscribeVerifyVisibleModel = computed({ get: () => props.regionSubscribeVerifyVisible, set: (value) => emit('update:regionSubscribeVerifyVisible', value) })
const regionSubscribeCodeModel = computed({ get: () => props.regionSubscribeCode, set: (value) => emit('update:regionSubscribeCode', value) })
const budgetFormVisibleModel = computed({ get: () => props.budgetFormVisible, set: (value) => emit('update:budgetFormVisible', value) })
const budgetAlertFormVisibleModel = computed({ get: () => props.budgetAlertFormVisible, set: (value) => emit('update:budgetAlertFormVisible', value) })
const announcementSearchModel = computed({ get: () => props.announcementSearch, set: (value) => emit('update:announcementSearch', value) })
const announcementDrawerVisibleModel = computed({ get: () => props.announcementDrawerVisible, set: (value) => emit('update:announcementDrawerVisible', value) })
const announcementDetailTabModel = computed({ get: () => props.announcementDetailTab, set: (value) => emit('update:announcementDetailTab', String(value)) })

function handleTenantTabChange(key: string | number) {
  props.onTenantTabChange(String(key))
}
</script>

<style scoped>
.mobile-card {
  content-visibility: auto;
  contain-intrinsic-size: 180px;
}


.tenant-account-pane {
  position: relative;
}

.tenant-account-refresh {
  position: absolute;
  top: -20px;
  right: 6px;
  z-index: 2;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-sub);
  cursor: pointer;
  font-size: 15px;
  line-height: 20px;
  text-align: center;
  transition: color 0.18s ease, border-color 0.18s ease, background 0.18s ease, opacity 0.18s ease;
}

.tenant-account-refresh:hover:not(:disabled) {
  border-color: rgba(99, 102, 241, 0.58);
  background: rgba(99, 102, 241, 0.12);
  color: #818cf8;
}

.tenant-account-refresh:disabled {
  cursor: default;
  opacity: 0.72;
}

.tenant-account-refresh.spinning {
  animation: tenant-account-refresh-spin 0.85s linear infinite;
}

@keyframes tenant-account-refresh-spin {
  to { transform: rotate(360deg); }
}

:global([data-theme="light"] .tenant-account-refresh) {
  border-color: rgba(15, 23, 42, 0.12);
  background: rgba(15, 23, 42, 0.035);
  color: rgba(71, 85, 105, 0.82);
}

:global([data-theme="light"] .tenant-account-refresh:hover:not(:disabled)) {
  border-color: rgba(37, 99, 235, 0.38);
  background: rgba(37, 99, 235, 0.08);
  color: #2563eb;
}

.quota-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 12px;
}
.quota-region-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.quota-service-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.quota-region-label {
  color: var(--text-sub);
  font-size: 13px;
  white-space: nowrap;
}
.quota-region-select {
  width: 180px;
}
.quota-service-select {
  width: 150px;
}
.quota-region-option {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.quota-region-option-code {
  min-width: 0;
  overflow: hidden;
  color: var(--text);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.quota-region-home-mark {
  color: var(--text-sub);
  flex: 0 0 auto;
  font-size: 11px;
  font-weight: 400;
  opacity: 0.72;
  transform: translateY(2px);
}
.quota-region-native-select {
  width: 100%;
  height: 32px;
  padding: 0 32px 0 10px;
  color: var(--text);
  background: var(--card-bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  outline: none;
}
.quota-search {
  width: 220px;
}
@media (max-width: 768px) {
  .quota-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
  .quota-region-field {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
    width: 100%;
  }
  .quota-service-field {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
    width: 100%;
  }
  .quota-search {
    width: 100%;
  }
  .quota-toolbar .ant-btn {
    width: 100%;
  }
}
.budget-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 8px 0 12px;
}
.budget-toolbar :deep(.ant-space) {
  max-width: 100%;
}
.budget-target-cell {
  max-width: 220px;
  overflow: hidden;
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.budget-spend-line {
  margin-top: 2px;
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.4;
}
:deep(.budget-table .ant-table-row) {
  cursor: pointer;
}
:deep(.budget-row-selected) > td {
  background: rgba(22, 119, 255, 0.08) !important;
}
:deep(.budget-row-selected) > td:first-child {
  box-shadow: inset 2px 0 0 var(--primary, #1677ff);
}
.budget-alert-section {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}
.budget-alert-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.budget-alert-title {
  max-width: 460px;
  overflow: hidden;
  color: var(--text-main);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.budget-alert-subtitle {
  color: var(--text-sub);
  font-size: 12px;
}
.budget-mobile-card {
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;
}
.budget-mobile-card-active {
  border-color: rgba(22, 119, 255, 0.55);
  background: rgba(22, 119, 255, 0.04);
  box-shadow: 0 4px 16px rgba(22, 119, 255, 0.1);
}
.budget-form :deep(.ant-form-item) {
  margin-bottom: 12px;
}
.budget-form :deep(.ant-input),
.budget-form :deep(.ant-input-number),
.budget-form :deep(.ant-select),
.budget-form :deep(textarea) {
  max-width: 100%;
}

.region-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 8px 0 12px;
}
.region-search {
  width: 240px;
}
.region-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.region-name-main {
  min-width: 0;
  overflow: hidden;
  color: var(--text-main);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.region-key-line {
  margin-top: 2px;
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.4;
}
.region-action-empty {
  color: var(--text-sub);
  font-size: 12px;
}
.region-mobile-card {
  margin-bottom: 10px;
}
.region-verify-target {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: var(--input-bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
}
.region-verify-name {
  overflow: hidden;
  color: var(--text-main);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.region-verify-meta {
  margin-top: 4px;
  color: var(--text-sub);
  font-size: 12px;
  word-break: break-all;
}
.region-verify-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
  color: var(--text-sub);
  font-size: 12px;
}

@media (max-width: 768px) {
  .budget-toolbar {
    align-items: flex-start;
    margin-top: 6px;
  }
  .budget-toolbar :deep(.ant-space) {
    width: 100%;
    gap: 8px !important;
  }
  .budget-alert-header {
    align-items: flex-start;
    flex-direction: column;
  }
  .budget-alert-title {
    max-width: 100%;
    white-space: normal;
    word-break: break-word;
  }
  .budget-alert-header :deep(.ant-space) {
    width: 100%;
  }
  .budget-mobile-card {
    margin-bottom: 10px;
  }
  .budget-mobile-card :deep(.ant-progress) {
    margin-top: 4px;
  }
  .budget-mobile-card .value {
    min-width: 0;
    text-align: right;
    word-break: break-word;
  }
  .budget-form :deep(.ant-col) {
    width: 100%;
  }
  .region-toolbar {
    align-items: flex-start;
    margin-top: 6px;
  }
  .region-toolbar :deep(.ant-space) {
    width: 100%;
    gap: 8px !important;
  }
  .region-search {
    width: 100%;
  }
  .region-mobile-card .value {
    min-width: 0;
    text-align: right;
    word-break: break-word;
  }
  .region-verify-actions {
    align-items: flex-start;
    flex-direction: column;
  }
}

.iam-statements { padding: 4px 0; }
.iam-statement-line {
  font-family: ui-monospace, monospace;
  font-size: 12px;
  line-height: 1.5;
  margin-bottom: 6px;
  word-break: break-word;
}

.announcement-block { margin-top: 14px; }
.announcement-block-title {
  font-weight: 600;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--text-main);
}
.announcement-description {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
  padding: 10px 12px;
  color: var(--text-main);
  background: var(--input-bg);
  border-radius: var(--radius-sm, 8px);
  border: 1px solid var(--border);
}
.announcement-description :deep(.announcement-link) {
  color: var(--primary);
  text-decoration: underline;
}
.announcement-description :deep(.announcement-link:hover) {
  color: var(--primary-hover);
}
</style>
