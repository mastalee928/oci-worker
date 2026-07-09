<template>
  <a-modal
    :mask-closable="false"
    :keyboard="false"
    v-model:open="mfaVerifyVisibleModel"
    title="安全验证 — MFA 多因素认证"
    :width="isMobile ? '100%' : 420"
    :confirm-loading="mfaVerifyLoading"
    :ok-text="mfaTargetEnabled ? '确认启用' : '确认关闭'"
    @ok="submitMfaChange"
    @cancel="cancelMfaVerify"
  >
    <a-alert
      type="warning"
      show-icon
      message="验证码已发送至 Telegram"
      description="MFA 是身份域登录安全策略，改动会影响该域内用户登录控制台。"
      style="margin-bottom: 12px"
    />
    <a-input
      v-model:value="mfaVerifyCodeModel"
      placeholder="请输入 6 位验证码"
      size="large"
      :maxlength="6"
      inputmode="numeric"
      allow-clear
      @pressEnter="submitMfaChange"
    />
    <div class="region-verify-actions">
      <span>验证码有效期 5 分钟</span>
      <a-button type="link" size="small" :loading="mfaVerifyCodeSending" @click="resendMfaVerifyCode">重新发送</a-button>
    </div>
  </a-modal>

  <a-modal
    :mask-closable="false"
    :keyboard="false"
    v-model:open="openModel"
    :title="'域管理 — ' + (tenant?.username || '')"
    :width="isMobile ? '100%' : 840"
    :footer="null"
    centered
    :bodyStyle="{ maxHeight: '75vh', overflow: 'auto' }"
  >
    <div class="domain-switcher" v-if="domainList.length > 0 || domainSettingsLoading">
      <span class="domain-switcher-label">当前域：</span>
      <a-select
        v-if="domainList.length > 0"
        :value="selectedDomainIdModel"
        style="min-width: 280px"
        :disabled="domainList.length <= 1"
        @change="(v: any) => handleDomainChange(String(v))"
      >
        <a-select-option v-for="d in domainList" :key="d.domainId" :value="d.domainId" :label="d.displayName">
          <span class="domain-option">
            <span class="domain-option-name">{{ d.displayName || '—' }}</span>
            <span v-if="d.type" class="domain-type-pill">{{ domainTypeCn(d.type) }}</span>
          </span>
        </a-select-option>
      </a-select>
      <a-spin v-if="domainSettingsLoading && domainList.length === 0" size="small" />
      <a-tag v-if="selectedDomain?.error || selectedDomain?.mfaError || selectedDomain?.passwordPolicyError" color="error" style="margin-left: 6px">异常</a-tag>
      <a-tooltip v-if="domainList.length === 1" title="当前租户仅一个域，无需切换">
        <i class="ri-information-line" style="color: var(--text-sub); font-size: 14px; margin-left: 6px"></i>
      </a-tooltip>
    </div>
    <a-alert
      v-if="!domainSettingsLoading && domainList.length === 0"
      type="warning"
      message="未读取到 Identity Domain 信息"
      show-icon
      style="margin-bottom: 12px"
    />

    <a-tabs v-model:activeKey="activeTabModel">
      <a-tab-pane key="security" tab="安全策略">
        <a-spin :spinning="domainSettingsLoading">
          <template v-if="selectedDomain">
            <a-alert v-if="selectedDomain.mfaError" type="warning" show-icon :message="selectedDomain.mfaError" style="margin-bottom: 8px" />
            <a-descriptions :column="1" bordered size="small">
              <a-descriptions-item label="MFA 多因素认证（Security Policy for OCI Console）">
                <a-space align="center" wrap>
                  <a-switch
                    :checked="!!selectedDomain.mfaEnabled"
                    :loading="mfaUpdatingId === selectedDomain.domainId"
                    :disabled="selectedDomain.mfaEnabled === null || selectedDomain.mfaEnabled === undefined"
                    checked-children="已启用"
                    un-checked-children="已关闭"
                    @change="(v: any) => handleMfaChange(selectedDomain, v as boolean)"
                  />
                  <span style="font-size: 12px; color: var(--text-sub)">
                    对应 OCI：身份域 → 安全 → 登录策略 → Security Policy for OCI Console 的「激活」状态
                  </span>
                  <span v-if="selectedDomain.consolePolicyName" style="font-size: 12px; color: var(--text-sub)">
                    · 策略：{{ selectedDomain.consolePolicyName }}
                  </span>
                </a-space>
              </a-descriptions-item>
              <a-descriptions-item label="密码过期天数（defaultPasswordPolicy）">
                <a-space wrap>
                  <a-input-number
                    :value="selectedDomain.passwordExpiresAfterDays ?? 0"
                    @update:value="(v: any) => (selectedDomain.passwordExpiresAfterDays = v as number)"
                    :min="0"
                    :max="999"
                    style="width: 120px"
                  />
                  <span style="color: var(--text-sub); font-size: 12px">
                    在 N 天后失效；0 = 永不过期
                  </span>
                  <a-button
                    type="primary"
                    size="small"
                    :loading="pwdExpiryUpdatingId === selectedDomain.domainId"
                    @click="handlePwdExpiryChange(selectedDomain)"
                  >
                    保存
                  </a-button>
                  <span v-if="selectedDomain.passwordPolicyName" style="font-size: 12px; color: var(--text-sub)">
                    策略：{{ selectedDomain.passwordPolicyName }}（priority={{ selectedDomain.passwordPolicyPriority ?? '-' }}）
                  </span>
                </a-space>
                <div v-if="selectedDomain.passwordPolicyError" style="color: #faad14; font-size: 12px; margin-top: 4px">
                  {{ selectedDomain.passwordPolicyError }}
                </div>
              </a-descriptions-item>
            </a-descriptions>
          </template>
        </a-spin>
      </a-tab-pane>

      <a-tab-pane key="factors" tab="验证因素">
        <div v-if="!authFactorToken" class="factor-lock">
          <i class="ri-shield-keyhole-line factor-lock-icon"></i>
          <div class="factor-lock-title">修改验证因素需要 Telegram 二次验证</div>
          <div class="factor-lock-desc">
            该设置对应「身份域 → 安全 → 验证因素」。改动将影响域内所有用户的 MFA 登录方式，请确认后再操作。
          </div>
          <a-space style="margin-top: 14px" wrap>
            <a-button @click="sendFactorCode" :loading="factorCodeSending">
              <template #icon><i class="ri-send-plane-line"></i></template>
              获取验证码
            </a-button>
            <a-input v-model:value="factorCodeInputModel" placeholder="6 位验证码" :maxlength="6" style="width: 140px" />
            <a-button type="primary" :loading="factorUnlocking" @click="doUnlockFactors">解锁</a-button>
          </a-space>
        </div>
        <div v-else>
          <a-alert
            type="success"
            show-icon
            style="margin-bottom: 12px"
            message="已通过 TG 验证，10 分钟内可在本 Tab 自由保存；切换域不需要重新验证。"
          />
          <a-spin :spinning="authFactorLoading">
            <template v-if="selectedFactorDomain">
              <a-alert v-if="selectedFactorDomain.error" type="warning" show-icon :message="selectedFactorDomain.error" style="margin-bottom: 10px" />
              <template v-else>
                <div class="factor-section-title">因素</div>
                <div class="factor-grid">
                  <a-checkbox
                    v-for="f in factorOptions"
                    :key="f.key"
                    :checked="!!selectedFactorDomain.factors?.[f.key]"
                    @change="(e: any) => (selectedFactorDomain.factors[f.key] = e.target.checked)"
                  >
                    {{ f.label }}
                  </a-checkbox>
                </div>

                <div class="factor-section-title">参数</div>
                <a-space wrap>
                  <span class="factor-label">最大注册设备数</span>
                  <a-input-number
                    :value="selectedFactorDomain.limits?.maxEnrolledDevices"
                    @update:value="(v: any) => (selectedFactorDomain.limits.maxEnrolledDevices = v)"
                    :min="1"
                    :max="20"
                    style="width: 110px"
                  />
                  <span class="factor-hint">maxEnrolledDevices</span>
                </a-space>

                <div class="factor-section-title">可信设备</div>
                <a-space wrap>
                  <a-switch
                    :checked="!!selectedFactorDomain.trustedDevice?.enabled"
                    @change="(v: any) => (selectedFactorDomain.trustedDevice.enabled = v)"
                    checked-children="启用"
                    un-checked-children="禁用"
                  />
                  <span class="factor-label">最大可信设备数</span>
                  <a-input-number
                    :value="selectedFactorDomain.trustedDevice?.maxTrustedEndpoints"
                    @update:value="(v: any) => (selectedFactorDomain.trustedDevice.maxTrustedEndpoints = v)"
                    :min="1"
                    :max="50"
                    style="width: 110px"
                  />
                  <span class="factor-label">信任天数</span>
                  <a-input-number
                    :value="selectedFactorDomain.trustedDevice?.maxEndpointTrustDurationInDays"
                    @update:value="(v: any) => (selectedFactorDomain.trustedDevice.maxEndpointTrustDurationInDays = v)"
                    :min="1"
                    :max="365"
                    style="width: 110px"
                  />
                </a-space>

                <div class="factor-section-title">登录规则</div>
                <a-space wrap>
                  <span class="factor-label">最大 MFA 失败次数</span>
                  <a-input-number
                    :value="selectedFactorDomain.limits?.maxIncorrectAttempts"
                    @update:value="(v: any) => (selectedFactorDomain.limits.maxIncorrectAttempts = v)"
                    :min="1"
                    :max="50"
                    style="width: 110px"
                  />
                  <span class="factor-hint">endpointRestrictions.maxIncorrectAttempts</span>
                </a-space>

                <div style="margin-top: 12px; display: flex; justify-content: flex-end; gap: 8px">
                  <a-button size="small" @click="reloadFactors">重置</a-button>
                  <a-button
                    size="small"
                    type="primary"
                    :loading="factorSavingId === selectedFactorDomain.domainId"
                    @click="saveFactors(selectedFactorDomain)"
                  >
                    保存
                  </a-button>
                </div>
              </template>
            </template>
          </a-spin>
        </div>
      </a-tab-pane>

      <a-tab-pane key="notifications" tab="通知">
        <div v-if="!notificationToken" class="factor-lock notification-lock">
          <i class="ri-shield-keyhole-line factor-lock-icon"></i>
          <div class="factor-lock-title">修改域通知需要 Telegram 二次验证</div>
          <div class="factor-lock-desc">
            该设置对应「身份域 → 设置 → 通知」。解锁后可查看和保存域通知配置，切换域不需要重新验证。
          </div>
          <a-space class="notification-lock-actions" wrap>
            <a-button @click="sendNotificationCode" :loading="notificationCodeSending">
              <template #icon><i class="ri-send-plane-line"></i></template>
              获取验证码
            </a-button>
            <a-input v-model:value="notificationCodeInputModel" placeholder="6 位验证码" :maxlength="6" style="width: 140px" />
            <a-button type="primary" :loading="notificationUnlocking" @click="doUnlockNotifications">解锁</a-button>
          </a-space>
        </div>
        <div v-else>
          <a-alert
            type="success"
            show-icon
            style="margin-bottom: 12px"
            message="已通过 TG 验证，10 分钟内可编辑域通知；切换域不需要重新验证。"
          />
          <div class="notification-toolbar">
            <div class="notification-toolbar-title">当前域通知配置</div>
            <a-button size="small" :loading="notificationLoading" :disabled="!selectedDomainIdModel" @click="loadDomainNotifications">
              <template #icon><ReloadOutlined /></template>刷新当前域
            </a-button>
          </div>
          <a-spin :spinning="notificationLoading">
            <template v-if="notificationData">
              <div class="domain-notification-layout">
                <section class="notification-panel">
                  <div class="notification-panel-header">
                    <div class="notification-panel-title">一般通知</div>
                    <div class="notification-inline-control">
                      <span>为所有的身份域用户启用通知</span>
                      <a-switch
                        :checked="!!notificationData.notificationEnabled"
                        checked-children="已启用"
                        un-checked-children="已关闭"
                        @change="(v: any) => (notificationData.notificationEnabled = v)"
                      />
                    </div>
                  </div>
                  <a-form layout="vertical" size="small" class="notification-form-grid">
                    <a-form-item label="发件人电子邮件地址">
                      <a-input v-model:value="notificationData.fromEmailAddress.value" allow-clear />
                    </a-form-item>
                    <a-form-item label="发件人显示名">
                      <a-input v-model:value="notificationData.fromEmailAddress.displayName" allow-clear />
                    </a-form-item>
                    <a-form-item label="发件邮箱验证方式">
                      <a-select v-model:value="notificationData.fromEmailAddress.validate" :options="notificationValidateOptions" />
                    </a-form-item>
                    <a-form-item label="发件邮箱验证状态">
                      <a-tag :color="notificationValidationStatusColor(notificationData.fromEmailAddress.validationStatus)">
                        {{ formatNotificationValidationStatus(notificationData.fromEmailAddress.validationStatus) }}
                      </a-tag>
                    </a-form-item>
                  </a-form>
                </section>

                <section class="notification-panel">
                  <div class="notification-panel-header">
                    <div>
                      <div class="notification-panel-title">收件人</div>
                      <div class="notification-panel-subtitle">通过电子邮件通知发送给选定收件人来测试这些通知。</div>
                    </div>
                    <div class="notification-inline-control">
                      <span>限定的收件人列表</span>
                      <a-switch
                        :checked="!!notificationData.testModeEnabled"
                        checked-children="是"
                        un-checked-children="否"
                        @change="(v: any) => (notificationData.testModeEnabled = v)"
                      />
                    </div>
                  </div>
                  <a-form layout="vertical" size="small">
                    <a-form-item label="测试收件人电子邮件地址">
                      <a-textarea
                        v-model:value="notificationRecipientsTextModel"
                        :auto-size="{ minRows: 2, maxRows: 5 }"
                        allow-clear
                      />
                    </a-form-item>
                  </a-form>
                </section>

                <a-collapse
                  v-model:activeKey="notificationEventActiveKeysModel"
                  class="notification-collapse"
                  :bordered="false"
                >
                  <a-collapse-panel key="admin" class="notification-collapse-panel">
                    <template #header>
                      <div class="notification-collapse-title">
                        <span>管理员通知</span>
                        <a-tag style="margin:0">{{ notificationAdminEvents.length }} 项</a-tag>
                      </div>
                    </template>
                    <a-empty v-if="notificationAdminEvents.length === 0" description="暂无管理员通知" />
                    <div v-else class="notification-event-list">
                      <div v-for="event in notificationAdminEvents" :key="event.eventId" class="notification-event-row">
                        <div class="notification-event-copy">
                          <div class="notification-event-name">{{ formatNotificationEventName(event.eventId) }}</div>
                          <div class="notification-event-id">{{ event.eventId }}</div>
                        </div>
                        <a-switch
                          :checked="!!event.enabled"
                          checked-children="是"
                          un-checked-children="否"
                          @change="(v: any) => (event.enabled = v)"
                        />
                      </div>
                    </div>
                  </a-collapse-panel>

                  <a-collapse-panel key="endUser" class="notification-collapse-panel">
                    <template #header>
                      <div class="notification-collapse-title">
                        <span>最终用户通知</span>
                        <a-tag style="margin:0">{{ notificationEndUserEvents.length }} 项</a-tag>
                      </div>
                    </template>
                    <a-empty v-if="notificationEndUserEvents.length === 0" description="暂无最终用户通知" />
                    <div v-else class="notification-event-list">
                      <div v-for="event in notificationEndUserEvents" :key="event.eventId" class="notification-event-row">
                        <div class="notification-event-copy">
                          <div class="notification-event-name">{{ formatNotificationEventName(event.eventId) }}</div>
                          <div class="notification-event-id">{{ event.eventId }}</div>
                        </div>
                        <a-switch
                          :checked="!!event.enabled"
                          checked-children="是"
                          un-checked-children="否"
                          @change="(v: any) => (event.enabled = v)"
                        />
                      </div>
                    </div>
                  </a-collapse-panel>
                </a-collapse>
              </div>

              <div class="notification-actions">
                <a-button size="small" :loading="notificationLoading" @click="loadDomainNotifications">
                  <template #icon><ReloadOutlined /></template>刷新
                </a-button>
                <a-button
                  size="small"
                  type="primary"
                  :loading="notificationSaving"
                  @click="saveDomainNotifications"
                >
                  保存
                </a-button>
              </div>
            </template>
            <a-empty v-else :description="notificationLoading ? '正在加载通知设置' : '请选择域后加载通知设置'">
              <template #extra>
                <a-button type="primary" size="small" :disabled="!selectedDomainIdModel" @click="loadDomainNotifications">
                  加载通知设置
                </a-button>
              </template>
            </a-empty>
          </a-spin>
        </div>
      </a-tab-pane>

      <a-tab-pane key="logs" tab="登录日志">
        <a-space style="margin-bottom: 12px" wrap>
          <a-button type="primary" @click="loadAuditLogs" :loading="auditLogsLoading" :disabled="!selectedDomainIdModel">
            <template #icon><ReloadOutlined /></template>加载最近{{ auditDaysModel }}天登录日志
          </a-button>
          <a-select v-model:value="auditDaysModel" style="width: 120px" @change="onAuditDaysChange">
            <a-select-option :value="1">最近 1 天</a-select-option>
            <a-select-option :value="3">最近 3 天</a-select-option>
            <a-select-option :value="7">最近 7 天</a-select-option>
            <a-select-option :value="14">最近 14 天</a-select-option>
            <a-select-option :value="30">最近 30 天</a-select-option>
          </a-select>
        </a-space>
        <a-spin :spinning="auditLogsLoading">
          <a-empty v-if="!auditLogsLoading && !auditLogsLoaded" description="请点击「加载」按钮拉取当前域的登录日志" />
          <a-empty v-else-if="!auditLogsLoading && !selectedAuditDomain" description="未读取到当前域的登录日志结果，请重新加载" />
          <div v-else-if="selectedAuditDomain">
            <AuditLogTable
              :rows="selectedAuditDomain.logs || []"
              :error="selectedAuditDomain.error || selectedAuditDomain.notice"
              :is-mobile="isMobile"
            />
          </div>
        </a-spin>
      </a-tab-pane>

      <a-tab-pane key="audit" tab="审计日志">
        <a-space style="margin-bottom: 12px" wrap>
          <a-button type="primary" @click="loadDomainAuditLogs" :loading="domainAuditLogsLoading" :disabled="!selectedDomainIdModel">
            <template #icon><ReloadOutlined /></template>加载最近{{ domainAuditDaysModel }}天审计日志
          </a-button>
          <a-select v-model:value="domainAuditDaysModel" style="width: 120px" @change="onDomainAuditDaysChange">
            <a-select-option :value="1">最近 1 天</a-select-option>
            <a-select-option :value="3">最近 3 天</a-select-option>
            <a-select-option :value="7">最近 7 天</a-select-option>
            <a-select-option :value="14">最近 14 天</a-select-option>
            <a-select-option :value="30">最近 30 天</a-select-option>
          </a-select>
        </a-space>
        <a-spin :spinning="domainAuditLogsLoading">
          <a-empty v-if="!domainAuditLogsLoading && !domainAuditLogsLoaded" description="请点击「加载」按钮拉取当前域的审计日志" />
          <a-empty v-else-if="!domainAuditLogsLoading && !selectedDomainAudit" description="未读取到当前域的审计日志结果，请重新加载" />
          <div v-else-if="selectedDomainAudit">
            <AuditLogTable
              :rows="selectedDomainAudit.logs || []"
              :error="selectedDomainAudit.error || selectedDomainAudit.notice"
              :is-mobile="isMobile"
              :event-labels="notificationEventLabels"
            />
          </div>
        </a-spin>
      </a-tab-pane>
    </a-tabs>
  </a-modal>
</template>

<script setup lang="ts">
defineOptions({ name: 'TenantDomainManagementModal' })

import { computed } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { defineAppAsyncComponent } from '../../../utils/asyncComponent'

const AuditLogTable = defineAppAsyncComponent(() => import('../../../components/AuditLogTable.vue'), { loading: 'none' })

type FactorOption = { key: string; label: string }

const props = defineProps<{
  open: boolean
  activeTab: string
  tenant: any | null
  isMobile: boolean
  domainSettingsLoading: boolean
  domainList: any[]
  selectedDomainId: string
  selectedDomain: any | null
  mfaUpdatingId: string
  mfaVerifyVisible: boolean
  mfaVerifyLoading: boolean
  mfaVerifyCodeSending: boolean
  mfaVerifyCode: string
  mfaTargetEnabled: boolean
  pwdExpiryUpdatingId: string
  authFactorToken: string
  authFactorLoading: boolean
  selectedFactorDomain: any | null
  factorCodeSending: boolean
  factorCodeInput: string
  factorUnlocking: boolean
  factorSavingId: string
  notificationLoading: boolean
  notificationSaving: boolean
  notificationData: any | null
  notificationRecipientsText: string
  notificationEventActiveKeys: string[]
  notificationCodeSending: boolean
  notificationCodeInput: string
  notificationUnlocking: boolean
  notificationToken: string
  notificationValidateOptions: any[]
  notificationAdminEvents: any[]
  notificationEndUserEvents: any[]
  auditLogsLoading: boolean
  auditLogsLoaded: boolean
  auditDays: number
  selectedAuditDomain: any | null
  domainAuditLogsLoading: boolean
  domainAuditLogsLoaded: boolean
  domainAuditDays: number
  selectedDomainAudit: any | null
  notificationEventLabels: Record<string, string>
  factorOptions: FactorOption[]
  handleDomainChange: (domainId: string) => void
  domainTypeCn: (type: string | null | undefined) => string
  handleMfaChange: (domain: any, checked: boolean) => void
  handlePwdExpiryChange: (domain: any) => void
  sendFactorCode: () => void
  doUnlockFactors: () => void
  reloadFactors: () => void
  saveFactors: (domain: any) => void
  sendNotificationCode: () => void
  doUnlockNotifications: () => void
  loadDomainNotifications: () => void
  saveDomainNotifications: () => void
  notificationValidationStatusColor: (status: string | null | undefined) => string
  formatNotificationValidationStatus: (status: string | null | undefined) => string
  formatNotificationEventName: (eventId: string | null | undefined) => string
  loadAuditLogs: () => void
  onAuditDaysChange: () => void
  loadDomainAuditLogs: () => void
  onDomainAuditDaysChange: () => void
  submitMfaChange: () => void
  cancelMfaVerify: () => void
  resendMfaVerifyCode: () => void
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:activeTab', value: string): void
  (e: 'update:selectedDomainId', value: string): void
  (e: 'update:mfaVerifyVisible', value: boolean): void
  (e: 'update:mfaVerifyCode', value: string): void
  (e: 'update:factorCodeInput', value: string): void
  (e: 'update:notificationCodeInput', value: string): void
  (e: 'update:notificationRecipientsText', value: string): void
  (e: 'update:notificationEventActiveKeys', value: string[]): void
  (e: 'update:auditDays', value: number): void
  (e: 'update:domainAuditDays', value: number): void
}>()

const openModel = computed({
  get: () => props.open,
  set: (value: boolean) => emit('update:open', value),
})
const activeTabModel = computed({
  get: () => props.activeTab,
  set: (value: string) => emit('update:activeTab', value),
})
const selectedDomainIdModel = computed({
  get: () => props.selectedDomainId,
  set: (value: string) => emit('update:selectedDomainId', value),
})
const mfaVerifyVisibleModel = computed({
  get: () => props.mfaVerifyVisible,
  set: (value: boolean) => emit('update:mfaVerifyVisible', value),
})
const mfaVerifyCodeModel = computed({
  get: () => props.mfaVerifyCode,
  set: (value: string) => emit('update:mfaVerifyCode', value),
})
const factorCodeInputModel = computed({
  get: () => props.factorCodeInput,
  set: (value: string) => emit('update:factorCodeInput', value),
})
const notificationCodeInputModel = computed({
  get: () => props.notificationCodeInput,
  set: (value: string) => emit('update:notificationCodeInput', value),
})
const notificationRecipientsTextModel = computed({
  get: () => props.notificationRecipientsText,
  set: (value: string) => emit('update:notificationRecipientsText', value),
})
const notificationEventActiveKeysModel = computed({
  get: () => props.notificationEventActiveKeys,
  set: (value: string[]) => emit('update:notificationEventActiveKeys', value),
})
const auditDaysModel = computed({
  get: () => props.auditDays,
  set: (value: number) => emit('update:auditDays', value),
})
const domainAuditDaysModel = computed({
  get: () => props.domainAuditDays,
  set: (value: number) => emit('update:domainAuditDays', value),
})
</script>

<style scoped>
.domain-switcher {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 12px;
  margin-bottom: 12px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
}
.domain-switcher-label {
  font-size: 13px;
  color: var(--text-sub);
  white-space: nowrap;
}
.domain-option {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.domain-option-name {
  font-weight: 500;
}
.domain-type-pill {
  display: inline-block;
  font-size: 11px;
  line-height: 1.5;
  padding: 0 6px;
  border-radius: 10px;
  background: rgba(22, 119, 255, 0.12);
  color: var(--primary, #1677ff);
}
.factor-lock {
  padding: 36px 20px;
  border: 1px dashed var(--border);
  border-radius: var(--radius-sm, 8px);
  text-align: center;
  background: var(--bg-card);
}
.factor-lock-icon {
  font-size: 36px;
  color: var(--primary, #1677ff);
  display: block;
  margin-bottom: 8px;
}
.factor-lock-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 6px;
}
.factor-lock-desc {
  font-size: 12px;
  color: var(--text-sub);
  max-width: 520px;
  margin: 0 auto;
  line-height: 1.6;
}
.notification-lock-actions {
  justify-content: center;
  margin-top: 14px;
}
.factor-section-title {
  font-weight: 600;
  font-size: 13px;
  margin: 12px 0 8px;
  color: var(--text-main);
}
.factor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 8px 12px;
}
.factor-label {
  font-size: 12px;
  color: var(--text-sub);
}
.factor-hint {
  font-size: 11px;
  color: var(--text-sub);
  opacity: 0.7;
  font-family: monospace;
}
.domain-notification-layout {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.notification-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
  background: var(--bg-card);
}
.notification-toolbar-title {
  min-width: 0;
  color: var(--text-main);
  font-size: 13px;
  font-weight: 600;
}
.notification-panel {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
  background: var(--bg-card);
  padding: 12px;
}
.notification-panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.notification-panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
}
.notification-panel-subtitle {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-sub);
  line-height: 1.5;
}
.notification-inline-control {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  color: var(--text-sub);
  font-size: 12px;
  text-align: right;
}
.notification-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 12px;
}
.notification-form-grid :deep(.ant-form-item) {
  margin-bottom: 8px;
}
.notification-collapse {
  display: grid;
  gap: 12px;
  background: transparent;
}
.notification-collapse :deep(.ant-collapse-item) {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
  background: var(--bg-card);
}
.notification-collapse :deep(.ant-collapse-header) {
  align-items: center !important;
  padding: 12px !important;
  color: var(--text-main) !important;
}
.notification-collapse :deep(.ant-collapse-content) {
  border-top: 1px solid var(--border);
  background: transparent;
}
.notification-collapse :deep(.ant-collapse-content-box) {
  padding: 12px !important;
}
.notification-collapse-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  font-size: 14px;
  font-weight: 600;
}
.notification-event-list {
  display: grid;
  gap: 8px;
}
.notification-event-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
  background: var(--bg-main, transparent);
}
.notification-event-copy {
  min-width: 0;
}
.notification-event-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-main);
}
.notification-event-id {
  margin-top: 2px;
  font-size: 11px;
  color: var(--text-sub);
  word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.notification-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
  flex-wrap: wrap;
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
  .region-verify-actions {
    align-items: flex-start;
    flex-direction: column;
  }
  .domain-switcher {
    align-items: stretch;
    flex-direction: column;
  }
  .domain-switcher :deep(.ant-select) {
    width: 100% !important;
    min-width: 0 !important;
  }
  .notification-panel-header {
    align-items: flex-start;
    flex-direction: column;
  }
  .notification-inline-control {
    justify-content: space-between;
    width: 100%;
    text-align: left;
  }
  .notification-form-grid {
    grid-template-columns: 1fr;
  }
  .notification-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
  .notification-toolbar :deep(.ant-btn) {
    width: 100%;
  }
  .notification-event-row {
    align-items: flex-start;
    flex-direction: column;
  }
  .notification-event-row :deep(.ant-switch) {
    align-self: flex-end;
  }
  .notification-actions {
    justify-content: stretch;
  }
  .notification-actions :deep(.ant-btn) {
    flex: 1 1 120px;
  }
  .notification-lock-actions {
    width: 100%;
    justify-content: stretch;
  }
  .notification-lock-actions :deep(.ant-space-item) {
    flex: 1 1 100%;
  }
  .notification-lock-actions :deep(.ant-input),
  .notification-lock-actions :deep(.ant-btn) {
    width: 100% !important;
  }
}
</style>
