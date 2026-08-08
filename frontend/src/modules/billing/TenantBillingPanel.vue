<template>
  <section class="tenant-billing-panel" aria-label="租户账务信息">
    <a-spin :spinning="billingLoading">
      <a-space v-if="!billingData && !billingLoading" style="margin-bottom: 12px">
        <a-button type="primary" @click="loadTenantBilling">加载账务信息</a-button>
      </a-space>

      <template v-if="billingData">
        <div class="billing-view-head">
          <div>
            <h3>{{ viewTitle }}</h3>
            <p>{{ viewDescription }}</p>
          </div>
          <a-space wrap>
            <a-button size="small" :loading="billingLoading" @click="refreshAll">
              <template #icon><ReloadOutlined /></template>刷新账务
            </a-button>
            <a-button v-if="billingData.links?.billingOverview" type="link" size="small"
              :href="billingData.links.billingOverview" target="_blank" rel="noopener noreferrer">
              Oracle 控制台
            </a-button>
          </a-space>
        </div>

        <a-tabs v-model:activeKey="activeView" class="billing-tabs" @change="handleViewChange">
          <a-tab-pane key="overview" tab="概览">
            <div class="billing-summary-grid">
              <a-card size="small">
                <div class="billing-card-label">订阅状态</div>
                <div class="billing-card-value">{{ subscription?.subscriptionStatusLabel || subscription?.subscriptionStatus || tenantInfo?.subscriptionStatusLabel || '—' }}</div>
                <div class="billing-card-note">{{ subscription?.planTypeLabel || tenantInfo?.planTypeLabel || 'OSP 订阅' }}</div>
              </a-card>
              <a-card size="small">
                <div class="billing-card-label">最近发票</div>
                <div class="billing-card-value">{{ money(latestInvoice?.totalAmount, latestInvoice?.currencyCode) }}</div>
                <div class="billing-card-note">{{ latestInvoice?.invoiceNo || '暂无发票数据' }}</div>
              </a-card>
              <a-card size="small">
                <div class="billing-card-label">期间成本</div>
                <div class="billing-card-value">{{ usageTotal }}</div>
                <div class="billing-card-note">{{ usageLabel }}</div>
              </a-card>
              <a-card size="small">
                <div class="billing-card-label">支付方式</div>
                <div class="billing-card-value billing-card-value-small">{{ paymentMethod }}</div>
                <div class="billing-card-note">Oracle 托管</div>
              </a-card>
            </div>

            <div class="billing-grid-two">
              <a-card size="small" title="成本分析（Usage API）">
                <div v-if="billingData.usage?.available" class="billing-bars" aria-label="按服务成本">
                  <div v-for="row in usageBars" :key="row.label" class="billing-bar-item">
                    <div class="billing-bar-track"><div class="billing-bar" :style="{ height: `${row.percent}%` }" /></div>
                    <span>{{ row.label }}</span>
                  </div>
                </div>
                <a-alert v-else type="warning" show-icon :message="billingData.usage?.reason || '成本分析不可用'" />
                <a-space wrap style="margin-top: 12px">
                  <span class="billing-muted">统计周期</span>
                  <a-select v-model:value="billingCostDaysModel" style="width: 112px" :options="billingCostDayOptions" />
                  <a-button type="primary" size="small" :loading="billingLoading" @click="reloadCost">查询成本</a-button>
                </a-space>
              </a-card>
              <a-card size="small" title="最近发票">
                <div v-if="latestInvoices.length" class="billing-mini-list">
                  <div v-for="invoice in latestInvoices" :key="invoice.invoiceId" class="billing-mini-row">
                    <div>
                      <strong>{{ invoice.invoiceNo || invoice.invoiceId || '—' }}</strong>
                      <span>{{ formatDate(invoice.invoiceDate) }} · 到期 {{ formatDate(invoice.dueDate) }}</span>
                    </div>
                    <div class="billing-mini-right">
                      <a-tag :color="invoiceColor(invoice)">{{ invoiceStatus(invoice) }}</a-tag>
                      <span>{{ money(invoice.totalAmount, invoice.currencyCode) }}</span>
                    </div>
                  </div>
                </div>
                <a-empty v-else description="暂无发票" />
                <a-button type="link" size="small" @click="activeView = 'invoices'">查看全部账单</a-button>
              </a-card>
            </div>
          </a-tab-pane>

          <a-tab-pane key="invoices" tab="账单">
            <template v-if="!invoiceDetail">
              <a-alert v-if="billingData.invoices?.available === false" type="warning" show-icon
                :message="billingData.invoices?.reason || '发票接口不可用'" style="margin-bottom: 10px" />
              <div class="billing-table-wrap">
                <a-table v-if="!isMobile" size="small" :data-source="invoiceItems" :pagination="{ pageSize: 10 }"
                  row-key="invoiceId">
                  <a-table-column title="发票号" data-index="invoiceNo" key="invoiceNo" :width="150" />
                  <a-table-column title="状态" key="status" :width="110">
                    <template #default="{ record }"><a-tag :color="invoiceColor(record)">{{ invoiceStatus(record) }}</a-tag></template>
                  </a-table-column>
                  <a-table-column title="开票日期" data-index="invoiceDate" key="invoiceDate" :width="170">
                    <template #default="{ text }">{{ formatDate(text) }}</template>
                  </a-table-column>
                  <a-table-column title="到期日" data-index="dueDate" key="dueDate" :width="170">
                    <template #default="{ text }">{{ formatDate(text) }}</template>
                  </a-table-column>
                  <a-table-column title="金额" key="amount" :width="150">
                    <template #default="{ record }">{{ money(record.totalAmount, record.currencyCode) }}</template>
                  </a-table-column>
                  <a-table-column title="操作" key="actions" :width="250">
                    <template #default="{ record }">
                      <a-space size="small">
                        <a-button type="link" size="small" @click="openInvoiceDetail(record)">查看明细</a-button>
                        <a-button type="link" size="small" :disabled="!record.invoiceId" @click="handleDownloadInvoice(record)">下载 PDF</a-button>
                        <a-button v-if="isInvoicePayable(record)" type="link" size="small" @click="openPayment('invoice', record)">支付账单</a-button>
                      </a-space>
                    </template>
                  </a-table-column>
                </a-table>
                <a-spin v-else :spinning="false">
                  <a-empty v-if="!invoiceItems.length" description="暂无发票" />
                  <div v-for="invoice in invoiceItems" :key="invoice.invoiceId" class="mobile-card">
                    <div class="mobile-card-header">
                      <span class="mobile-card-title">{{ invoice.invoiceNo || invoice.invoiceId || '—' }}</span>
                      <a-tag :color="invoiceColor(invoice)" style="margin:0">{{ invoiceStatus(invoice) }}</a-tag>
                    </div>
                    <div class="mobile-card-body">
                      <div class="mobile-card-row"><span class="label">金额</span><span class="value">{{ money(invoice.totalAmount, invoice.currencyCode) }}</span></div>
                      <div class="mobile-card-row"><span class="label">开票</span><span class="value">{{ formatDate(invoice.invoiceDate) }}</span></div>
                      <div class="mobile-card-row"><span class="label">到期</span><span class="value">{{ formatDate(invoice.dueDate) }}</span></div>
                    </div>
                    <div class="mobile-card-actions">
                      <a-button type="link" size="small" @click="openInvoiceDetail(invoice)">查看明细</a-button>
                      <a-button type="link" size="small" :disabled="!invoice.invoiceId" @click="handleDownloadInvoice(invoice)">下载 PDF</a-button>
                      <a-button v-if="isInvoicePayable(invoice)" type="link" size="small" @click="openPayment('invoice', invoice)">支付账单</a-button>
                    </div>
                  </div>
                </a-spin>
              </div>
            </template>
            <template v-else>
              <div class="billing-detail-head">
                <a-button type="link" size="small" @click="closeInvoiceDetail">← 返回账单</a-button>
                <a-space>
                  <a-button size="small" :loading="invoiceDetailLoading" @click="reloadInvoiceDetail">刷新明细</a-button>
                  <a-button v-if="isInvoicePayable(invoiceDetail)" type="primary" size="small" @click="openPayment('invoice', invoiceDetail)">支付账单</a-button>
                </a-space>
              </div>
              <a-descriptions bordered size="small" :column="isMobile ? 1 : 3">
                <a-descriptions-item label="发票号">{{ invoiceDetail.invoiceNo || '—' }}</a-descriptions-item>
                <a-descriptions-item label="状态"><a-tag :color="invoiceColor(invoiceDetail)">{{ invoiceStatus(invoiceDetail) }}</a-tag></a-descriptions-item>
                <a-descriptions-item label="金额">{{ money(invoiceDetail.amountDue ?? invoiceDetail.totalAmount, invoiceDetail.currencyCode) }}</a-descriptions-item>
                <a-descriptions-item label="开票日期">{{ formatDate(invoiceDetail.invoiceDate) }}</a-descriptions-item>
                <a-descriptions-item label="到期日">{{ formatDate(invoiceDetail.dueDate) }}</a-descriptions-item>
                <a-descriptions-item label="付款条款">{{ invoiceDetail.paymentTerms || '—' }}</a-descriptions-item>
              </a-descriptions>
              <a-table v-if="!isMobile" style="margin-top: 12px" size="small" :loading="invoiceDetailLoading"
                :data-source="invoiceDetail.lines || []" :pagination="{ pageSize: 15 }" row-key="orderNo">
                <a-table-column title="产品" data-index="product" key="product" :ellipsis="true" />
                <a-table-column title="订单号" data-index="orderNo" key="orderNo" :width="140" />
                <a-table-column title="使用周期" key="period" :width="220">
                  <template #default="{ record }">{{ formatDate(record.timeStart) }} ～ {{ formatDate(record.timeEnd) }}</template>
                </a-table-column>
                <a-table-column title="数量" data-index="quantity" key="quantity" :width="100" />
                <a-table-column title="单价" key="unit" :width="130"><template #default="{ record }">{{ money(record.netUnitPrice, record.currencyCode) }}</template></a-table-column>
                <a-table-column title="总价" key="total" :width="130"><template #default="{ record }">{{ money(record.totalPrice, record.currencyCode) }}</template></a-table-column>
              </a-table>
              <div v-else class="billing-line-mobile">
                <div v-for="(line, index) in (invoiceDetail.lines || [])" :key="`${line.orderNo || 'line'}-${index}`" class="mobile-card">
                  <div class="mobile-card-header"><strong>{{ line.product || '账单明细' }}</strong><span>{{ money(line.totalPrice, line.currencyCode) }}</span></div>
                  <div class="mobile-card-body">
                    <div class="mobile-card-row"><span class="label">订单号</span><span class="value">{{ line.orderNo || '—' }}</span></div>
                    <div class="mobile-card-row"><span class="label">数量</span><span class="value">{{ line.quantity ?? '—' }}</span></div>
                    <div class="mobile-card-row"><span class="label">单价</span><span class="value">{{ money(line.netUnitPrice, line.currencyCode) }}</span></div>
                  </div>
                </div>
              </div>
            </template>
          </a-tab-pane>

          <a-tab-pane key="subscription" tab="商业订阅">
            <a-spin :spinning="workspaceLoading">
              <template v-if="subscription">
                <div class="billing-subscription-grid">
                  <a-card size="small">
                    <div class="billing-eyebrow">当前套餐</div>
                    <h4>{{ subscription.planTypeLabel || subscription.planType || 'OSP 订阅' }}</h4>
                    <p class="billing-muted">订阅编号：{{ subscription.subscriptionPlanNumber || subscription.id || '—' }}</p>
                    <a-descriptions bordered size="small" :column="1">
                      <a-descriptions-item label="状态"><a-tag :color="subscriptionColor">{{ subscription.subscriptionStatusLabel || subscription.subscriptionStatus || '—' }}</a-tag></a-descriptions-item>
                      <a-descriptions-item label="货币">{{ subscription.currencyCode || tenantInfo?.currencyCode || '—' }}</a-descriptions-item>
                      <a-descriptions-item label="付款方式">{{ subscription.paymentMethodLabel || subscription.paymentMethod || '—' }}</a-descriptions-item>
                      <a-descriptions-item label="付款意向">{{ subscription.isIntentToPay === true ? '已完成' : subscription.isIntentToPay === false ? '未完成' : '—' }}</a-descriptions-item>
                    </a-descriptions>
                    <a-space wrap style="margin-top: 14px">
                      <a-button type="primary" :disabled="!subscription.id" @click="openPayment('subscription', subscription)">支付订阅</a-button>
                      <a-button @click="activeView = 'address'">管理账单地址</a-button>
                    </a-space>
                  </a-card>
                  <a-card size="small" title="付款流程">
                    <a-alert type="info" show-icon message="付款由 Oracle 官方支付页面完成" description="OCIWorker 只创建支付授权，不收集或保存银行卡信息。完成后返回本页面刷新账务状态。" />
                    <a-button v-if="workspace?.links?.billingAccount" type="link" style="padding-left:0; margin-top: 10px" :href="workspace.links.billingAccount" target="_blank" rel="noopener noreferrer">打开 Oracle 账务页面</a-button>
                  </a-card>
                </div>
              </template>
              <a-empty v-else description="未读取到 OSP 商业订阅" />
            </a-spin>
          </a-tab-pane>

          <a-tab-pane key="address" tab="账单地址">
            <a-spin :spinning="workspaceLoading">
              <div class="billing-address-layout">
                <a-card size="small" title="当前账单地址">
                  <a-descriptions v-if="address" bordered size="small" :column="1">
                    <a-descriptions-item label="公司">{{ address.companyName || '—' }}</a-descriptions-item>
                    <a-descriptions-item label="联系人">{{ contactName(address) }}</a-descriptions-item>
                    <a-descriptions-item label="邮箱">{{ address.emailAddress || '—' }}</a-descriptions-item>
                    <a-descriptions-item label="地址">{{ addressLine(address) }}</a-descriptions-item>
                    <a-descriptions-item label="地址键"><span class="billing-code">{{ address.addressKey || '—' }}</span></a-descriptions-item>
                  </a-descriptions>
                  <a-empty v-else description="未读取到账单地址" />
                </a-card>
                <a-card size="small" title="编辑并验证">
                  <a-form layout="vertical">
                    <a-row :gutter="12">
                      <a-col :xs="24" :sm="12"><a-form-item label="公司"><a-input v-model:value="addressForm.companyName" allow-clear /></a-form-item></a-col>
                      <a-col :xs="24" :sm="12"><a-form-item label="邮箱"><a-input v-model:value="addressForm.emailAddress" allow-clear /></a-form-item></a-col>
                      <a-col :xs="24" :sm="12"><a-form-item label="名"><a-input v-model:value="addressForm.firstName" allow-clear /></a-form-item></a-col>
                      <a-col :xs="24" :sm="12"><a-form-item label="姓"><a-input v-model:value="addressForm.lastName" allow-clear /></a-form-item></a-col>
                      <a-col :xs="24"><a-form-item label="地址行 1" required><a-input v-model:value="addressForm.line1" allow-clear /></a-form-item></a-col>
                      <a-col :xs="24"><a-form-item label="地址行 2"><a-input v-model:value="addressForm.line2" allow-clear /></a-form-item></a-col>
                      <a-col :xs="24" :sm="8"><a-form-item label="城市" required><a-input v-model:value="addressForm.city" allow-clear /></a-form-item></a-col>
                      <a-col :xs="24" :sm="8"><a-form-item label="州/省"><a-input v-model:value="addressForm.province" allow-clear /></a-form-item></a-col>
                      <a-col :xs="24" :sm="8"><a-form-item label="邮编" required><a-input v-model:value="addressForm.postalCode" allow-clear /></a-form-item></a-col>
                      <a-col :xs="24" :sm="12"><a-form-item label="国家" required><a-input v-model:value="addressForm.country" allow-clear /></a-form-item></a-col>
                      <a-col :xs="24" :sm="12"><a-form-item label="电话"><a-input v-model:value="addressForm.phoneNumber" allow-clear /></a-form-item></a-col>
                    </a-row>
                  </a-form>
                  <a-alert v-if="addressVerification" :type="addressVerification.verified ? 'success' : 'warning'" show-icon
                    :message="addressVerification.verified ? 'Oracle 地址验证通过' : 'Oracle 地址需要人工确认'"
                    :description="`${addressVerification.verificationCode || '—'} · ${addressVerification.quality || '—'}`" style="margin-bottom: 12px" />
                  <a-space wrap>
                    <a-button type="primary" :loading="addressVerifying" @click="verifyAddress">验证地址</a-button>
                    <a-button :disabled="!addressVerification?.verified" :loading="addressSaving" @click="saveAddress">确认修改</a-button>
                  </a-space>
                </a-card>
              </div>
            </a-spin>
          </a-tab-pane>
        </a-tabs>
      </template>

      <a-empty v-else :description="billingLoading ? '正在加载账务数据' : '暂无账务数据'" />
    </a-spin>

    <a-modal v-model:open="paymentVisible" :title="paymentTitle" :width="isMobile ? '100%' : 500" :footer="null" :mask-closable="false">
      <template v-if="!paymentResult">
        <a-alert type="warning" show-icon message="请确认你了解此操作"
          :description="paymentKind === 'invoice' ? '支付操作可能产生真实费用，付款授权将由 Oracle 官方页面完成。' : '订阅付款授权将由 Oracle 官方页面完成，OCIWorker 不会收集银行卡信息。'" />
        <a-space class="billing-payment-actions">
          <a-button @click="paymentVisible = false">取消</a-button>
          <a-button type="primary" :loading="paymentLoading" @click="confirmPayment">确定，继续</a-button>
        </a-space>
      </template>
      <template v-else>
        <a-alert type="info" show-icon message="已创建 Oracle 支付会话"
          description="请在 Oracle 官方页面完成付款。返回 OCIWorker 后点击刷新账务查看最新状态。" />
        <div class="billing-payment-result">
          <a-button type="primary" block @click="openOfficialPayment">打开 Oracle 官方支付页面</a-button>
          <a-button block style="margin-top: 8px" @click="refreshAfterPayment">返回并刷新账务</a-button>
          <a-typography-paragraph v-if="paymentResult.officialUrl" copyable :content="paymentResult.officialUrl" class="billing-official-url" />
        </div>
      </template>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import {
  getTenantBillingWorkspace,
  getTenantInvoiceDetails,
  startTenantInvoicePayment,
  startTenantSubscriptionPayment,
  updateTenantBillingAddress,
  verifyTenantBillingAddress,
  type BillingAddressPayload,
} from '../../api/tenant'

type Fn = (...args: any[]) => any

const props = defineProps<{
  tenantId: string
  tenantInfo: any
  isMobile: boolean
  billingLoading: boolean
  billingData: any | null
  billingCostDays: number
  billingCostDayOptions: any[]
  loadTenantBilling: Fn
  reloadBillingCost: Fn
  handleDownloadInvoice: Fn
}>()

const activeView = ref('overview')
const workspace = ref<any | null>(null)
const workspaceLoading = ref(false)
const invoiceDetail = ref<any | null>(null)
const invoiceDetailLoading = ref(false)
const paymentVisible = ref(false)
const paymentLoading = ref(false)
const paymentKind = ref<'invoice' | 'subscription'>('invoice')
const paymentTarget = ref<any | null>(null)
const paymentResult = ref<any | null>(null)
const addressVerification = ref<any | null>(null)
const addressVerifying = ref(false)
const addressSaving = ref(false)
const billingCostDaysLocal = ref(props.billingCostDays)
const addressForm = reactive<Record<string, string>>({
  addressKey: '', line1: '', line2: '', line3: '', line4: '', streetName: '', streetNumber: '',
  city: '', county: '', country: '', province: '', postalCode: '', state: '', emailAddress: '',
  companyName: '', firstName: '', middleName: '', lastName: '', phoneCountryCode: '', phoneNumber: '',
  jobTitle: '', departmentName: '',
})

const tenantInfo = computed(() => props.tenantInfo || {})
const subscription = computed(() => workspace.value?.subscription || props.billingData?.subscription || null)
const address = computed(() => workspace.value?.address || subscription.value?.billingAddress || null)
const invoiceItems = computed(() => props.billingData?.invoices?.items || [])
const latestInvoices = computed(() => invoiceItems.value.slice(0, 4))
const latestInvoice = computed(() => invoiceItems.value[0] || props.billingData?.summary?.latestInvoice || null)
const usageTotal = computed(() => {
  const usage = props.billingData?.usage
  return usage?.available && usage.summary ? money(usage.summary.totalCost, usage.summary.currency) : '—'
})
const usageLabel = computed(() => {
  const usage = props.billingData?.usage
  return usage?.available ? `近 ${usage.periodDays || props.billingCostDays} 天` : '成本数据未加载'
})
const paymentMethod = computed(() => subscription.value?.paymentMethodLabel || subscription.value?.paymentMethod || tenantInfo.value.paymentMethodLabel || tenantInfo.value.paymentMethod || '—')
const subscriptionColor = computed(() => tagColor(subscription.value?.subscriptionStatus))
const viewTitle = computed(() => ({ overview: '账务概览', invoices: '账单', subscription: '商业订阅', address: '账单地址' }[activeView.value] || '账务信息'))
const viewDescription = computed(() => ({
  overview: 'OSP 正式账单与 Usage API 成本分开显示',
  invoices: '查看发票、发票明细并在 Oracle 官方页面完成付款',
  subscription: '管理 OSP 商业订阅和付款授权',
  address: '验证并更新 OSP 账单地址',
}[activeView.value] || ''))
const paymentTitle = computed(() => paymentKind.value === 'invoice' ? '支付账单' : '支付商业订阅')
const billingCostDaysModel = computed({
  get: () => billingCostDaysLocal.value,
  set: (value) => (billingCostDaysLocal.value = Number(value)),
})

const usageBars = computed(() => {
  const rows = (props.billingData?.usage?.byDay || []).slice(-6)
  const values = rows.map((row: any) => Number(row.cost) || 0)
  const max = Math.max(...values, 1)
  return rows.map((row: any, index: number) => ({
    label: String(row.date || '').slice(5) || `D${index + 1}`,
    percent: Math.max(8, Math.round((Number(row.cost) || 0) / max * 100)),
  }))
})

watch(() => props.tenantId, () => {
  workspace.value = null
  invoiceDetail.value = null
  addressVerification.value = null
})
watch(() => props.billingCostDays, (value) => {
  billingCostDaysLocal.value = value
})
watch(address, (value) => {
  if (value && !addressForm.line1) setAddressForm(value)
}, { immediate: true })

async function handleViewChange(key: string | number) {
  activeView.value = String(key)
  if (activeView.value === 'subscription' || activeView.value === 'address') await loadWorkspace()
}

async function loadWorkspace(force = false) {
  if (!props.tenantId || (workspace.value && !force) || workspaceLoading.value) return
  workspaceLoading.value = true
  try {
    const response: any = await getTenantBillingWorkspace({ id: props.tenantId })
    workspace.value = response?.data || null
    if (workspace.value?.address) setAddressForm(workspace.value.address)
  } catch (error: any) {
    message.error(error?.message || '读取订阅/账单地址失败')
  } finally {
    workspaceLoading.value = false
  }
}

async function refreshAll() {
  await props.loadTenantBilling()
  await loadWorkspace(true)
}

async function reloadCost() {
  await props.reloadBillingCost(billingCostDaysLocal.value)
}

function money(value: any, currency?: string) {
  if (value === null || value === undefined || value === '') return '—'
  return `${value}${currency ? ` ${currency}` : ''}`
}

function formatDate(value: any) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toISOString().slice(0, 19).replace('T', ' ')
}

function invoiceStatus(invoice: any) {
  const status = String(invoice?.status || invoice?.invoiceStatus || '').toUpperCase()
  if (invoice?.payable === true && !['PAID', 'CLOSED', 'CANCELLED'].includes(status)) return '待支付'
  if (status === 'PAID') return '已支付'
  if (status === 'CANCELLED') return '已取消'
  return invoice?.status || '—'
}

function invoiceColor(invoice: any) {
  const status = String(invoice?.status || '').toUpperCase()
  if (status === 'PAID' || status === 'CLOSED') return 'green'
  if (status === 'CANCELLED') return 'red'
  return invoice?.payable === false ? 'default' : 'orange'
}

function isInvoicePayable(invoice: any) {
  const status = String(invoice?.status || '').toUpperCase()
  return invoice?.payable !== false && !['PAID', 'CLOSED', 'CANCELLED'].includes(status)
}

function tagColor(status: any) {
  const value = String(status || '').toUpperCase()
  if (value === 'ACTIVE' || value === 'PAID') return 'green'
  if (value === 'EXPIRED' || value === 'ERROR' || value === 'CANCELLED') return 'red'
  if (value === 'PENDING' || value === 'INACTIVE') return 'orange'
  return 'blue'
}

async function openInvoiceDetail(invoice: any) {
  if (!invoice?.invoiceId) return
  activeView.value = 'invoices'
  invoiceDetailLoading.value = true
  try {
    const response: any = await getTenantInvoiceDetails({ id: props.tenantId, invoiceId: invoice.invoiceId, limit: 200 })
    invoiceDetail.value = response?.data || null
  } catch (error: any) {
    message.error(error?.message || '读取发票明细失败')
  } finally {
    invoiceDetailLoading.value = false
  }
}

async function reloadInvoiceDetail() {
  if (!invoiceDetail.value) return
  await openInvoiceDetail(invoiceDetail.value)
}

function closeInvoiceDetail() {
  invoiceDetail.value = null
}

function openPayment(kind: 'invoice' | 'subscription', target: any) {
  paymentKind.value = kind
  paymentTarget.value = target
  paymentResult.value = null
  paymentVisible.value = true
}

async function confirmPayment() {
  if (!props.tenantId || !paymentTarget.value) return
  paymentLoading.value = true
  try {
    const response: any = paymentKind.value === 'invoice'
      ? await startTenantInvoicePayment({
        id: props.tenantId,
        invoiceId: paymentTarget.value.invoiceId,
        returnUrl: window.location.href,
        email: paymentTarget.value.preferredEmail || tenantInfo.value.username,
      })
      : await startTenantSubscriptionPayment({
        id: props.tenantId,
        subscriptionId: paymentTarget.value.id,
        email: tenantInfo.value.username,
      })
    paymentResult.value = response?.data || null
    if (!paymentResult.value) throw new Error('Oracle 未返回支付会话')
  } catch (error: any) {
    message.error(error?.message || '创建支付会话失败')
  } finally {
    paymentLoading.value = false
  }
}

function openOfficialPayment() {
  const url = paymentResult.value?.url || paymentResult.value?.officialUrl
  if (!url) {
    message.warning('Oracle 未返回支付地址，请从账务页面进入付款')
    return
  }
  window.open(url, '_blank', 'noopener,noreferrer')
}

async function refreshAfterPayment() {
  paymentVisible.value = false
  await refreshAll()
}

function setAddressForm(value: any) {
  for (const key of Object.keys(addressForm)) addressForm[key] = value?.[key] == null ? '' : String(value[key])
}

function contactName(value: any) {
  return [value?.firstName, value?.middleName, value?.lastName].filter(Boolean).join(' ') || '—'
}

function addressLine(value: any) {
  const line = [value?.line1, value?.line2, value?.line3, value?.line4].filter(Boolean).join(', ')
  const city = [value?.city, value?.province || value?.state, value?.postalCode].filter(Boolean).join(' ')
  return [line, city, value?.country].filter(Boolean).join(', ') || '—'
}

function addressPayload(): BillingAddressPayload {
  return {
    id: props.tenantId,
    subscriptionId: subscription.value?.id,
    ...Object.fromEntries(Object.entries(addressForm).filter(([, value]) => value !== '')),
  } as BillingAddressPayload
}

async function verifyAddress() {
  addressVerifying.value = true
  try {
    const response: any = await verifyTenantBillingAddress(addressPayload())
    addressVerification.value = response?.data || null
    if (addressVerification.value?.address) setAddressForm(addressVerification.value.address)
  } catch (error: any) {
    message.error(error?.message || '验证账单地址失败')
  } finally {
    addressVerifying.value = false
  }
}

async function saveAddress() {
  if (!addressVerification.value?.verified) {
    message.warning('请先通过 Oracle 地址验证')
    return
  }
  addressSaving.value = true
  try {
    const response: any = await updateTenantBillingAddress(addressPayload())
    workspace.value = { ...(workspace.value || {}), ...(response?.data || {}), address: response?.data?.address || { ...addressForm } }
    addressVerification.value = null
    message.success('账单地址已更新')
    await props.loadTenantBilling()
  } catch (error: any) {
    message.error(error?.message || '保存账单地址失败')
  } finally {
    addressSaving.value = false
  }
}
</script>

<style scoped>
.tenant-billing-panel { min-width: 0; }
.billing-view-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin: 2px 0 12px; }
.billing-view-head h3 { margin: 0; font-size: 16px; }
.billing-view-head p { margin: 4px 0 0; color: var(--text-sub); font-size: 12px; }
.billing-tabs :deep(.ant-tabs-nav) { margin-bottom: 12px; }
.billing-summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.billing-card-label, .billing-eyebrow { color: var(--text-sub); font-size: 12px; }
.billing-card-value { margin-top: 5px; color: var(--text); font-size: 18px; font-weight: 700; }
.billing-card-value-small { font-size: 15px; }
.billing-card-note, .billing-muted { color: var(--text-sub); font-size: 12px; }
.billing-grid-two, .billing-subscription-grid, .billing-address-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; margin-top: 12px; }
.billing-bars { display: flex; align-items: end; gap: 9px; height: 140px; padding: 8px 4px 0; border-bottom: 1px solid var(--border); }
.billing-bar-item { display: flex; flex: 1; min-width: 0; height: 100%; flex-direction: column; align-items: center; justify-content: end; gap: 5px; color: var(--text-sub); font-size: 11px; }
.billing-bar-track { display: flex; width: 100%; height: calc(100% - 18px); align-items: end; justify-content: center; }
.billing-bar { width: min(28px, 70%); min-height: 4px; border-radius: 4px 4px 0 0; background: #6366f1; }
.billing-mini-list { display: grid; gap: 4px; }
.billing-mini-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--border); }
.billing-mini-row:last-child { border-bottom: 0; }
.billing-mini-row strong, .billing-mini-row span { display: block; }
.billing-mini-row span { margin-top: 3px; color: var(--text-sub); font-size: 11px; }
.billing-mini-right { flex: 0 0 auto; text-align: right; }
.billing-mini-right span { color: var(--text); font-size: 12px; }
.billing-table-wrap { min-width: 0; overflow-x: auto; }
.billing-detail-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.billing-line-mobile { margin-top: 12px; }
.billing-subscription-grid h4 { margin: 6px 0 4px; font-size: 20px; }
.billing-payment-actions { display: flex; justify-content: flex-end; width: 100%; margin-top: 18px; }
.billing-payment-result { margin-top: 18px; }
.billing-official-url { margin: 12px 0 0; word-break: break-all; font-size: 11px; }
.billing-code { word-break: break-all; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11px; }
@media (max-width: 900px) {
  .billing-summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 700px) {
  .billing-view-head { display: block; }
  .billing-view-head > :last-child { margin-top: 8px; }
  .billing-grid-two, .billing-subscription-grid, .billing-address-layout { grid-template-columns: 1fr; }
}
</style>
