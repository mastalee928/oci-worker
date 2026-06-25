<template>
  <div>
    <a-tabs v-model:active-key="activeTab" class="settings-page-tabs">
      <a-tab-pane key="security" tab="安全设置">
        <a-card title="修改登录密码" class="settings-card pwd-change-card">
          <div v-if="!pwdTgVerified" class="lock-panel settings-no-select">
            <i :class="tgConfigured ? 'ri-shield-check-line' : 'ri-lock-2-line'" class="lock-icon"></i>
            <p class="lock-text">{{ tgConfigured ? '修改密码需要 Telegram 验证码' : '请输入登录密码以继续' }}</p>
            <a-space v-if="tgConfigured" direction="vertical" style="width: 100%">
              <a-button block @click="sendPwdVerifyCode" :loading="pwdCodeSending" :disabled="pwdCodeCountdown > 0">
                {{ pwdCodeCountdown > 0 ? pwdCodeCountdown + '秒后重新发送' : '发送验证码' }}
              </a-button>
              <a-input v-model:value="pwdTgCode" placeholder="输入 TG 验证码" @pressEnter="verifyPwdTgCode" />
              <a-button type="primary" block @click="verifyPwdTgCode" :disabled="!pwdTgCode">验证</a-button>
            </a-space>
            <a-space v-else direction="vertical" style="width: 100%">
              <a-input-password v-model:value="pwdOverlayPwd" placeholder="输入登录密码" @pressEnter="verifyPwdOverlay" />
              <a-button type="primary" block @click="verifyPwdOverlay" :disabled="!pwdOverlayPwd">验证</a-button>
            </a-space>
          </div>
          <a-form v-else :model="pwdForm" layout="vertical">
            <a-form-item label="原密码" required>
              <a-input-password v-model:value="pwdForm.oldPassword" placeholder="输入当前密码" />
            </a-form-item>
            <a-form-item label="新密码" required>
              <a-input-password v-model:value="pwdForm.newPassword" placeholder="至少6位" />
            </a-form-item>
            <a-form-item label="确认新密码" required>
              <a-input-password v-model:value="pwdForm.confirmPassword" placeholder="再次输入新密码" />
            </a-form-item>
            <a-button type="primary" @click="handleChangePassword" :loading="pwdLoading">修改密码</a-button>
          </a-form>
        </a-card>

        <a-card title="登录安全说明" class="settings-card settings-no-select" style="margin-top: 16px">
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item label="Token 有效期">24 小时</a-descriptions-item>
            <a-descriptions-item label="关闭浏览器">Token 保持有效，直到过期</a-descriptions-item>
            <a-descriptions-item label="Token 存储">浏览器 localStorage</a-descriptions-item>
          </a-descriptions>
          <div style="margin-top: 12px">
            <a-button danger @click="handleForceLogout">立即退出登录</a-button>
          </div>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="notify" tab="消息通知">
        <a-card title="消息通知" class="settings-card-wide notify-tg-card notify-settings-card">
          <div v-if="!notifyPwdVerified" class="lock-panel settings-no-select">
            <i class="ri-lock-2-line lock-icon"></i>
            <p class="lock-text">请输入登录密码进行配置</p>
            <a-space direction="vertical" style="width: 100%">
              <a-input-password v-model:value="notifyPwd" placeholder="输入登录密码" @pressEnter="verifyNotifyPwd" />
              <a-button type="primary" block @click="verifyNotifyPwd" :disabled="!notifyPwd">验证</a-button>
            </a-space>
          </div>
          <div v-else>
            <a-segmented v-model:value="notifySection" :options="notifySectionOptions" class="notify-section-segment" />

            <a-form v-if="notifySection === 'telegram'" layout="vertical" class="notify-section-panel">
              <a-form-item label="Bot Token">
                <a-input v-model:value="tgConfig.botToken" placeholder="输入 Telegram Bot Token" />
              </a-form-item>
              <a-form-item label="Chat ID">
                <a-input v-model:value="tgConfig.chatId" placeholder="输入 Chat ID" />
              </a-form-item>
              <a-form-item label="通知类型">
                <a-checkbox-group v-model:value="tgConfig.notifyTypes" :options="notifyTypeOptions" />
              </a-form-item>
              <a-space wrap>
                <a-button type="primary" @click="saveTgConfig" :loading="saveLoading">保存</a-button>
                <a-button @click="testTgNotify" :loading="testLoading">测试发送</a-button>
              </a-space>
            </a-form>

            <a-form v-else-if="notifySection === 'daily'" layout="vertical" class="notify-section-panel">
              <a-alert type="info" show-icon style="margin-bottom: 16px">
                <template #message>每日播报使用东八区（Asia/Shanghai）时间。</template>
              </a-alert>
              <a-form-item label="启用每日播报">
                <a-checkbox
                  :checked="tgConfig.notifyTypes.includes('daily_report')"
                  @change="toggleNotifyType('daily_report', $event.target.checked)"
                >
                  接收每日播报
                </a-checkbox>
              </a-form-item>
              <a-form-item label="播报时间">
                <a-time-picker
                  v-model:value="dailyReportTimePicked"
                  format="HH:mm"
                  :show-second="false"
                  :minute-step="1"
                  value-format="HH:mm"
                />
              </a-form-item>
              <a-button type="primary" @click="saveTgConfig" :loading="saveLoading">保存</a-button>
            </a-form>

            <div v-else-if="notifySection === 'announcement'" class="notify-section-panel announcement-push-panel">
              <a-tabs v-model:active-key="announcementTab" size="small" @change="handleAnnouncementTabChange">
                <a-tab-pane key="config" tab="推送配置">
                  <a-form layout="vertical" class="announcement-config-form">
                    <div class="announcement-config-head">
                      <a-form-item label="推送范围" class="announcement-event-form-item">
                        <a-select
                          v-model:value="announcementPushConfig.eventTypes"
                          mode="multiple"
                          :show-search="false"
                          :options="announcementEventTypeOptions"
                          placeholder="选择需要推送的事件"
                        />
                      </a-form-item>
                      <a-form-item label="启用推送" class="announcement-enable-form-item">
                        <a-switch v-model:checked="announcementPushConfig.enabled" checked-children="开" un-checked-children="关" />
                      </a-form-item>
                    </div>
                    <a-row :gutter="16">
                      <a-col :xs="24" :md="12">
                        <a-form-item label="扫描频率">
                          <a-select v-model:value="announcementPushConfig.frequencyMinutes" :options="announcementFrequencyOptions" />
                        </a-form-item>
                      </a-col>
                      <a-col :xs="24" :md="12">
                        <a-form-item label="公告保留">
                          <a-select v-model:value="announcementPushConfig.recordRetentionDays" :options="announcementRecordRetentionOptions" />
                        </a-form-item>
                      </a-col>
                      <a-col :xs="24" :md="12">
                        <a-form-item label="历史保留">
                          <a-select v-model:value="announcementPushConfig.batchRetentionDays" :options="announcementBatchRetentionOptions" />
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <div class="tenant-picker-shell">
                      <div class="tenant-picker-head">
                        <div>
                          <div class="tenant-picker-title">接收租户</div>
                          <div class="tenant-picker-sub">已选择 {{ announcementSelectedTenantCount }} / {{ announcementTenants.length }} 个租户</div>
                        </div>
                        <a-button size="small" @click="tenantPickerVisible = true">选择租户</a-button>
                      </div>
                      <div class="tenant-chip-row">
                        <a-tag v-if="!announcementPushConfig.selectedTenantIds.length">尚未选择</a-tag>
                        <a-tag v-for="tenant in announcementSelectedTenantPreview" :key="tenant.id">
                          {{ tenant.tenantName || tenant.username || tenant.id }}
                        </a-tag>
                        <a-tag v-if="announcementPushConfig.selectedTenantIds.length > announcementSelectedTenantPreview.length">
                          等 {{ announcementPushConfig.selectedTenantIds.length }} 个
                        </a-tag>
                      </div>
                    </div>

                    <a-space wrap>
                      <a-button type="primary" :loading="announcementSaveLoading" @click="saveAnnouncementPushConfig">保存云公告推送</a-button>
                      <a-button :loading="announcementScanLoading" @click="triggerAnnouncementScan">立即扫描</a-button>
                    </a-space>
                  </a-form>
                </a-tab-pane>

                <a-tab-pane key="inbox" tab="公告收件箱">
                  <div class="announcement-toolbar">
                    <a-select
                      v-model:value="announcementInboxRange"
                      class="announcement-filter-select"
                      :options="announcementTimeRangeOptions"
                      @change="handleAnnouncementRangeChange"
                    />
                    <a-range-picker
                      v-if="announcementInboxRange === 'custom'"
                      v-model:value="announcementInboxDates"
                      value-format="YYYY-MM-DD HH:mm:ss"
                      format="YYYY-MM-DD HH:mm"
                      :show-time="{ format: 'HH:mm' }"
                      class="announcement-date-range"
                      @change="loadAnnouncementInbox(1)"
                    />
                    <a-select
                      v-model:value="announcementInboxEventTypes"
                      mode="multiple"
                      :show-search="false"
                      allow-clear
                      class="announcement-event-filter"
                      placeholder="全部事件"
                      :options="announcementEventFilterOptions"
                      @change="loadAnnouncementInbox(1)"
                    />
                    <a-input-search
                      v-model:value="announcementInboxKeyword"
                      placeholder="搜索摘要、服务、区域、租户名"
                      allow-clear
                      @search="loadAnnouncementInbox(1)"
                    />
                    <a-button :loading="announcementInboxLoading" @click="loadAnnouncementInbox()">刷新</a-button>
                  </div>
                  <a-spin :spinning="announcementInboxLoading">
                    <a-empty v-if="!announcementInbox.records.length" description="暂无公告" />
                    <div v-else class="announcement-list">
                      <div v-for="item in announcementInbox.records" :key="item.aggregateKey" class="announcement-item">
                        <div class="announcement-item-main">
                          <a-space wrap size="small">
                            <a-tag>{{ item.announcementTypeLabel || item.announcementType || '公告' }}</a-tag>
                            <span v-if="item.announcementType && item.announcementTypeLabel !== item.announcementType" class="announcement-type-origin">
                              {{ item.announcementType }}
                            </span>
                            <a-tag v-if="item.read" color="green">已读</a-tag>
                            <a-tag v-if="item.ignored" color="default">已忽略</a-tag>
                            <a-tag v-if="item.pushedBatchId" color="blue">{{ item.pushedBatchId }}</a-tag>
                          </a-space>
                          <div class="announcement-summary">{{ item.summary || '-' }}</div>
                          <div class="announcement-meta">
                            {{ formatDateTime(item.timeCreated) }} · 影响 {{ item.tenantCount || 0 }} 个租户 · {{ item.tenantPreview || '-' }}
                          </div>
                          <div v-if="item.timeWindowText" class="announcement-window">{{ item.timeWindowText }}</div>
                        </div>
                        <a-space wrap>
                          <a-button size="small" @click="openAnnouncementDetail(item)">详情</a-button>
                          <a-button v-if="!item.read" size="small" @click="markAnnouncement(item, 'read')">已读</a-button>
                          <a-button v-if="!item.ignored" size="small" @click="markAnnouncement(item, 'ignore')">忽略</a-button>
                          <a-button v-else size="small" @click="markAnnouncement(item, 'unignore')">取消忽略</a-button>
                        </a-space>
                      </div>
                    </div>
                  </a-spin>
                  <a-pagination
                    v-if="announcementInbox.total > announcementInbox.size"
                    size="small"
                    class="announcement-pagination"
                    :current="announcementInbox.current"
                    :page-size="announcementInbox.size"
                    :total="announcementInbox.total"
                    @change="loadAnnouncementInbox"
                  />
                </a-tab-pane>

                <a-tab-pane key="history" tab="推送历史">
                  <a-spin :spinning="announcementBatchLoading">
                    <a-empty v-if="!announcementBatches.records.length" description="暂无推送历史" />
                    <div v-else class="announcement-list">
                      <div v-for="batch in announcementBatches.records" :key="batch.id || batch.batchId" class="announcement-item">
                        <div class="announcement-item-main">
                          <div class="announcement-summary">{{ batch.batchId || '-' }}</div>
                          <div class="announcement-meta">
                            {{ formatDateTime(batch.pushedAt || batch.createTime) }} · {{ batch.status || '-' }} · {{ batch.announcementCount || 0 }} 条公告 · {{ batch.tenantCount || 0 }} 个租户
                          </div>
                          <div v-if="batch.errorMessage" class="announcement-window">{{ batch.errorMessage }}</div>
                        </div>
                      </div>
                    </div>
                  </a-spin>
                </a-tab-pane>

                <a-tab-pane key="status" tab="扫描状态">
                  <a-descriptions :column="isMobile ? 1 : 2" bordered size="small">
                    <a-descriptions-item label="当前状态">{{ announcementStatus.scanning ? '扫描中' : announcementStatus.status || '空闲' }}</a-descriptions-item>
                    <a-descriptions-item label="最近扫描">{{ formatDateTime(announcementStatus.lastScanAt) }}</a-descriptions-item>
                    <a-descriptions-item label="下次扫描">{{ formatDateTime(announcementStatus.nextScanAt) }}</a-descriptions-item>
                    <a-descriptions-item label="成功租户">{{ announcementStatus.successTenants ?? 0 }}</a-descriptions-item>
                    <a-descriptions-item label="失败租户">{{ announcementStatus.failedTenants ?? 0 }}</a-descriptions-item>
                    <a-descriptions-item label="最近错误">{{ announcementStatus.lastError || '-' }}</a-descriptions-item>
                  </a-descriptions>
                  <div style="margin-top: 14px">
                    <a-button :loading="announcementScanLoading" @click="triggerAnnouncementScan">立即扫描</a-button>
                  </div>
                </a-tab-pane>
              </a-tabs>
            </div>

            <a-descriptions v-else :column="1" bordered size="small" class="notify-section-panel settings-no-select">
              <a-descriptions-item label="登录通知">登录成功/失败时发送，包含IP地址、账号、时间</a-descriptions-item>
              <a-descriptions-item label="创建任务">创建开机任务时通知</a-descriptions-item>
              <a-descriptions-item label="任务结果">开机成功或认证失败时通知，包含实例详情</a-descriptions-item>
              <a-descriptions-item label="每日播报">在所设东八区时刻自动发送（默认 09:00），包含租户总数、失效租户、运行中任务</a-descriptions-item>
              <a-descriptions-item label="云公告推送">后台按租户范围扫描 OCI 云公告，同一公告聚合后推送到 Telegram</a-descriptions-item>
            </a-descriptions>
          </div>
        </a-card>

        <a-modal
          v-model:open="tenantPickerVisible"
          title="选择接收云公告的租户"
          :width="isMobile ? '100%' : 980"
          :footer="null"
          :keyboard="false"
          centered
          class="tenant-picker-modal"
        >
          <div class="tenant-picker-modal-body">
            <div class="tenant-picker-toolbar">
              <a-input-search
                v-model:value="announcementTenantSearch"
                class="tenant-picker-search"
                placeholder="搜索租户名、用户名、区域"
                allow-clear
              />
              <div class="tenant-picker-actions">
                <a-popconfirm
                  :title="`确认添加当前筛选出的 ${filteredAnnouncementTenants.length} 个租户？`"
                  ok-text="确认"
                  cancel-text="取消"
                  :disabled="filteredAnnouncementTenants.length === 0"
                  @confirm="addFilteredAnnouncementTenants"
                >
                  <a-button size="small" :disabled="filteredAnnouncementTenants.length === 0">全部添加</a-button>
                </a-popconfirm>
                <a-popconfirm
                  :title="`确认移除已选择的 ${announcementSelectedTenants.length} 个租户？`"
                  ok-text="确认"
                  cancel-text="取消"
                  :disabled="announcementSelectedTenants.length === 0"
                  @confirm="clearAnnouncementTenants"
                >
                  <a-button size="small" danger :disabled="announcementSelectedTenants.length === 0">全部移除</a-button>
                </a-popconfirm>
              </div>
            </div>
            <a-select
              v-if="isMobile"
              v-model:value="activeAnnouncementGroupKey"
              class="tenant-mobile-group-select"
              :show-search="false"
            >
              <a-select-option
                v-for="group in announcementGroupOptions"
                :key="group.key"
                :value="group.key"
              >
                <span class="tenant-mobile-group-option" :class="{ 'tenant-mobile-group-option--child': group.level === '2' }">
                  <span>{{ group.label }}</span>
                  <small>{{ group.count }}</small>
                </span>
              </a-select-option>
            </a-select>
            <div class="tenant-picker-grid">
              <div v-if="!isMobile" class="tenant-picker-block tenant-picker-group-block">
                <div class="tenant-picker-title">分组</div>
                <a-empty v-if="!announcementGroupOptions.length" description="暂无分组" />
                <div v-else class="tenant-group-list">
                  <button
                    v-for="group in announcementGroupOptions"
                    :key="group.key"
                    type="button"
                    class="tenant-group-row tenant-group-button"
                    :class="{ 'tenant-group-button--active': activeAnnouncementGroupKey === group.key, 'tenant-group-button--child': group.level === '2' }"
                    @click="activeAnnouncementGroupKey = group.key"
                  >
                    <span>{{ group.label }}</span>
                    <small>{{ group.count }}</small>
                  </button>
                </div>
              </div>
              <div class="tenant-picker-block">
                <div class="tenant-picker-title">当前分组租户</div>
                <div class="tenant-list">
                  <div v-for="tenant in pagedFilteredAnnouncementTenants" :key="tenant.id" class="tenant-row">
                    <div class="tenant-name">
                      <strong>{{ tenant.tenantName || tenant.username || tenant.id }}</strong>
                      <small>{{ tenant.username }} · {{ tenant.region || '-' }} · {{ tenant.groupLevel1 || '未分组' }}{{ tenant.groupLevel2 ? ' / ' + tenant.groupLevel2 : '' }}</small>
                    </div>
                    <a-checkbox
                      :checked="announcementPushConfig.selectedTenantIds.includes(tenant.id)"
                      @change="toggleAnnouncementTenant(tenant.id, $event.target.checked)"
                    />
                  </div>
                </div>
                <a-pagination
                  v-if="filteredAnnouncementTenants.length > tenantPickerPageSize"
                  v-model:current="tenantPickerPage"
                  size="small"
                  class="tenant-picker-pagination"
                  :page-size="tenantPickerPageSize"
                  :total="filteredAnnouncementTenants.length"
                  :show-size-changer="false"
                />
              </div>
              <div class="tenant-picker-block">
                <div class="tenant-picker-title">已选择接收</div>
                <a-empty v-if="!announcementSelectedTenants.length" description="尚未选择租户" />
                <div v-else class="tenant-selected-list">
                  <div v-for="tenant in pagedAnnouncementSelectedTenants" :key="tenant.id" class="tenant-selected-row">
                    <div class="tenant-name">
                      <strong>{{ tenant.tenantName || tenant.username || tenant.id }}</strong>
                      <small>{{ tenant.username }} · {{ tenant.region || '-' }}</small>
                    </div>
                    <a-button size="small" type="link" @click="toggleAnnouncementTenant(tenant.id, false)">移除</a-button>
                  </div>
                </div>
                <a-pagination
                  v-if="announcementSelectedTenants.length > tenantPickerPageSize"
                  v-model:current="tenantSelectedPage"
                  size="small"
                  class="tenant-picker-pagination"
                  :page-size="tenantPickerPageSize"
                  :total="announcementSelectedTenants.length"
                  :show-size-changer="false"
                />
              </div>
            </div>
          </div>
        </a-modal>

        <a-modal
          v-model:open="announcementDetailVisible"
          title="公告详情"
          :width="isMobile ? '100%' : 760"
          :footer="null"
          :keyboard="false"
        >
          <a-spin :spinning="announcementDetailLoading">
            <a-empty v-if="!announcementDetail.aggregateKey" description="暂无详情" />
            <div v-else class="announcement-detail">
              <h3>{{ announcementDetail.summary || '-' }}</h3>
              <p class="announcement-meta">{{ announcementDetail.timeWindowText || '无维护时间窗口' }}</p>
              <a-descriptions :column="1" bordered size="small">
                <a-descriptions-item label="公告类型">{{ announcementDetail.announcementType || '-' }}</a-descriptions-item>
                <a-descriptions-item label="影响服务">{{ (announcementDetail.services || []).join('、') || '-' }}</a-descriptions-item>
                <a-descriptions-item label="影响区域">{{ (announcementDetail.affectedRegions || []).join('、') || '-' }}</a-descriptions-item>
                <a-descriptions-item label="影响租户">{{ announcementDetail.tenantCount || 0 }}</a-descriptions-item>
              </a-descriptions>
              <div v-if="announcementDetail.liveDetail?.detail?.description" class="announcement-live-detail">
                {{ announcementDetail.liveDetail.detail.description }}
              </div>
              <div class="tenant-impact-list">
                <div v-for="tenant in announcementDetail.tenants || []" :key="tenant.tenantId + tenant.announcementId" class="tenant-impact-row">
                  <span>{{ tenant.tenantName || tenant.tenantId }}</span>
                  <a-tag v-if="tenant.read" color="green">已读</a-tag>
                  <a-tag v-if="tenant.ignored">已忽略</a-tag>
                </div>
              </div>
            </div>
          </a-spin>
        </a-modal>

        <a-modal :keyboard="false"
          v-model:open="notifySaveVerifyVisible"
          title="安全验证 — 保存 Telegram 通知配置"
          :width="isMobile ? '100%' : 400"
          :mask-closable="false"
          @ok="confirmNotifySave"
          :confirm-loading="saveLoading"
          ok-text="确认保存"
        >
          <a-alert type="info" show-icon style="margin-bottom: 16px">
            <template #message>验证码已发送至 Telegram</template>
          </a-alert>
          <a-input
            v-model:value="notifySaveVerifyCode"
            placeholder="请输入6位验证码"
            size="large"
            :maxlength="6"
            allow-clear
            @pressEnter="confirmNotifySave"
          />
          <div style="margin-top: 12px; display: flex; justify-content: space-between; align-items: center">
            <span style="color: var(--text-sub); font-size: 12px">验证码有效期 5 分钟</span>
            <a-button type="link" size="small" :loading="notifySaveCodeSending" @click="sendNotifySaveCode">
              {{ notifySaveCodeCountdown > 0 ? notifySaveCodeCountdown + ' 秒后可重发' : '重新发送' }}
            </a-button>
          </div>
          <div style="margin-top: 8px">
            <a-button type="link" size="small" style="padding: 0; height: auto" @click="showNotifyTgLostHint">
              Telegram丢失
            </a-button>
          </div>
        </a-modal>
      </a-tab-pane>

      <a-tab-pane key="proxy" tab="OCI 代理">
        <a-card class="settings-card-wide settings-card-oci-proxy">
          <template #title>
            <span><i class="ri-server-line" style="margin-right: 8px; vertical-align: middle"></i>OCI 代理配置</span>
          </template>
          <a-form layout="vertical">
            <a-form-item>
              <a-checkbox v-model:checked="ociProxyForm.enabled">启用 OCI API 代理（HTTP / SOCKS5 / SOCKS5h）</a-checkbox>
            </a-form-item>
            <a-form-item v-if="ociProxyForm.enabled" label="代理类型">
              <a-select
                v-model:value="ociProxyForm.proxyType"
                :options="ociProxyTypeOptions"
                class="oci-proxy-type-select"
              />
            </a-form-item>
            <a-row v-if="ociProxyForm.enabled" :gutter="[12, 0]">
              <a-col :xs="24" :sm="14">
                <a-form-item label="主机" required>
                  <a-input v-model:value="ociProxyForm.host" placeholder="如 10.0.0.1 或 域名" />
                </a-form-item>
              </a-col>
              <a-col :xs="24" :sm="10">
                <a-form-item label="端口" required>
                  <a-input-number
                    v-model:value="ociProxyForm.port"
                    :min="1"
                    :max="65535"
                    placeholder="端口"
                    style="width: 100%"
                  />
                </a-form-item>
              </a-col>
            </a-row>
            <a-row v-if="ociProxyForm.enabled" :gutter="[12, 0]">
              <a-col :span="24" :md="12">
                <a-form-item label="用户名（可选）">
                  <a-input v-model:value="ociProxyForm.username" placeholder="代理认证用户" allow-clear />
                </a-form-item>
              </a-col>
              <a-col :span="24" :md="12">
                <a-form-item label="密码（可选）">
                  <a-input-password v-model:value="ociProxyForm.password" placeholder="不修改可保留脱敏显示" allow-clear />
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item v-if="ociProxyForm.enabled" label="完整代理 URL（可选）">
              <a-input
                v-model:value="ociProxyForm.fullUrl"
                class="oci-proxy-url-input"
                placeholder="留空则使用上方组合；或粘贴完整地址覆盖，如 socks5h://user:pass@host:1080"
                allow-clear
              />
            </a-form-item>
            <a-space>
              <a-button type="primary" @click="saveOciProxy" :loading="ociProxySaveLoading">保存设置</a-button>
              <a-button @click="testOciProxy" :loading="ociProxyTestLoading">测试代理</a-button>
            </a-space>
          </a-form>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="alidns" tab="阿里云DNS">
        <a-card class="settings-card-wide settings-card-wide--alidns">
          <template #title>
            <span><i class="ri-global-line" style="margin-right: 8px; vertical-align: middle"></i>阿里云DNS 凭据</span>
          </template>
          <div class="alidns-settings-layout">
            <a-alert
              type="info"
              show-icon
              message="AccessKey 权限"
              description="建议为 OCI Worker 单独创建 RAM 用户，并授予 AliyunDNSFullAccess 或最小化 DNS 只读/编辑权限。"
            />
            <a-form layout="vertical" class="alidns-settings-form">
              <a-form-item label="AccessKey ID" required>
                <a-input v-model:value="alidnsForm.accessKeyId" placeholder="填写 AccessKey ID" allow-clear />
              </a-form-item>
              <a-form-item label="AccessKey Secret" required>
                <a-input-password
                  v-model:value="alidnsForm.accessKeySecret"
                  :placeholder="alidnsSecretConfigured ? '已配置（留空不修改）' : '填写 AccessKey Secret'"
                  allow-clear
                />
              </a-form-item>
              <a-space wrap>
                <a-button type="primary" @click="saveAlidnsConfig" :loading="alidnsSaveLoading">保存设置</a-button>
                <a-button @click="testAlidnsConfig" :loading="alidnsTestLoading">测试连接</a-button>
              </a-space>
            </a-form>
          </div>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="cloudflare" tab="Cloudflare">
        <a-card class="settings-card-wide settings-card-wide--cf">
          <template #title>
            <span><i class="ri-cloud-line" style="margin-right: 8px; vertical-align: middle"></i>Cloudflare 全局凭据</span>
          </template>
          <div class="cf-settings-layout">
            <div class="cf-settings-layout__help">
              <a-alert type="info" show-icon message="如何创建 API 令牌" class="cf-settings-help-alert">
                <template #description>
                  <div class="cf-settings-help">
                    <p><strong>推荐：账户 API 令牌</strong>（以 <code>cfat_</code> 开头，适合 OCIWorker 长期集成）</p>
                    <ol>
                      <li>登录 <a href="https://dash.cloudflare.com/" target="_blank" rel="noopener noreferrer">Cloudflare 控制台</a></li>
                      <li>进入 <strong>管理账户 → 账户 API 令牌 → 创建令牌 → 自定义令牌</strong></li>
                      <li>资源范围：<strong>整个账户</strong>，并包含 <strong>所有区域</strong>（或至少包含要管理的 Zone）</li>
                      <li>
                        权限（自定义最小权限时建议至少包含）：
                        <ul>
                          <li><strong>Account</strong> → <strong>Cloudflare Tunnel</strong> → <strong>Edit</strong>（Tunnel 连接器、Public Hostname / ingress 路由）</li>
                          <li><strong>Account</strong> → <strong>Workers Scripts</strong> → <strong>Edit</strong>（创建 / 上传 Worker 脚本）</li>
                          <li><strong>Account</strong> → <strong>Cloudflare Pages</strong> → <strong>Edit</strong>（Pages 项目、Direct Upload 静态部署）</li>
                          <li><strong>Account</strong> → <strong>Analytics</strong> → <strong>Read</strong>（Workers 和 Pages「使用情况」用量）</li>
                          <li><strong>Zone</strong> → <strong>DNS</strong> → <strong>Edit</strong>（DNS 记录、Tunnel 自动 CNAME）</li>
                          <li><strong>Zone</strong> → <strong>Workers Routes</strong> → <strong>Edit</strong>（域名 → Workers 路由，可选）</li>
                          <li>其余功能按需勾选对应 <strong>Edit</strong>（防火墙、SSL、缓存等）；同一项只勾 Edit，不必读+编辑双勾</li>
                        </ul>
                      </li>
                      <li>若使用 Cloudflare 提供的「编辑全部区域」等宽权限模板，通常已包含上述能力，无需逐项勾选</li>
                      <li>创建后复制完整 Token；<strong>Account ID</strong> 见创建成功页或仪表盘 Overview 右侧</li>
                    </ol>
                    <p class="cf-settings-help-note">
                      备选：用户 API 令牌（非 <code>cfat_</code>）亦可。
                    </p>
                  </div>
                </template>
              </a-alert>
            </div>
            <div class="cf-settings-layout__form">
              <a-form layout="vertical" class="cf-settings-form">
                <a-form-item label="Account ID" required>
                  <a-input v-model:value="cfForm.accountId" placeholder="32 位 Account ID" allow-clear />
                </a-form-item>
                <a-form-item label="API Token" required>
                  <a-input-password
                    v-model:value="cfForm.apiToken"
                    :placeholder="cfTokenConfigured ? '已配置（留空不修改）' : '粘贴 API Token'"
                    allow-clear
                  />
                </a-form-item>
                <a-space wrap>
                  <a-button type="primary" @click="saveCfConfig" :loading="cfSaveLoading">保存设置</a-button>
                  <a-button @click="testCfConfig" :loading="cfTestLoading">测试连接</a-button>
                </a-space>
              </a-form>
            </div>
          </div>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="audit" tab="登录统计">
        <a-card class="settings-card-audit">
          <template #title>登录记录（保留 7 天，超时自动清理）</template>
          <template #extra>
            <a-button v-if="auditTgVerified" type="link" size="small" :loading="auditLoading" @click="loadAudit">
              <template #icon><ReloadOutlined /></template>
              刷新
            </a-button>
          </template>
          <div v-if="!tgConfigured">
            <a-alert
              type="error"
              show-icon
              message="登录统计需通过 Telegram 验证后才能查看"
              description="请先在「消息通知」中配置 Telegram Bot 与 Chat ID。"
            />
          </div>
          <div v-else-if="!auditTgVerified" class="lock-panel audit-lock-panel">
            <i class="ri-shield-check-line lock-icon"></i>
            <p class="lock-text">查看登录记录前请完成 Telegram 验证</p>
            <a-space direction="vertical" style="width: 100%; max-width: 320px">
              <a-button
                block
                @click="sendAuditVerifyCode"
                :loading="auditCodeSending"
                :disabled="auditCodeCountdown > 0"
              >
                {{ auditCodeCountdown > 0 ? auditCodeCountdown + ' 秒后可重新发送' : '发送验证码到 Telegram' }}
              </a-button>
              <a-input
                v-model:value="auditUnlockCode"
                placeholder="输入 6 位验证码"
                maxlength="6"
                allow-clear
                @pressEnter="verifyAuditUnlock"
              />
              <a-button type="primary" block @click="verifyAuditUnlock" :disabled="!auditUnlockCode">查看登录记录</a-button>
            </a-space>
          </div>
          <template v-else>
            <a-table
              class="audit-table"
              table-layout="fixed"
              row-key="id"
              size="small"
              v-model:expanded-row-keys="auditExpandedKeys"
              :loading="auditLoading"
              :columns="auditColumns"
              :data-source="auditRows"
              :pagination="auditPagination"
              :scroll="{ x: 1312 }"
              :expand-column-width="46"
              :expand-icon="auditExpandIcon"
              @change="onAuditTableChange"
            >
              <template #expandedRowRender="{ record }">
                <div class="audit-expanded-inner">
                  <template v-if="auditDetailSections(record).length">
                    <div v-for="sec in auditDetailSections(record)" :key="sec.title" class="audit-detail-block">
                      <div class="audit-detail-h">{{ sec.title }}</div>
                      <a-descriptions bordered size="small" :column="1">
                        <a-descriptions-item v-for="(val, key) in sec.entries" :key="String(key)" :label="key">
                          <span class="audit-detail-val">{{ val }}</span>
                        </a-descriptions-item>
                      </a-descriptions>
                    </div>
                  </template>
                  <a-empty v-else description="无扩展详情（该条为升级前记录或未采集）" />
                </div>
              </template>
              <template #bodyCell="{ column, record }">
                <template v-if="isAuditCopyColumn(column)">
                  <div
                    class="audit-copy-cell"
                    :class="{ 'audit-copy-cell--tap': isMobile }"
                    @click.stop="onAuditCopyCellTap(record, column)"
                  >
                    <span class="audit-copy-text" :title="auditScalar(record, column)">{{ auditScalar(record, column) }}</span>
                    <a-button
                      v-if="!isMobile"
                      type="link"
                      size="small"
                      class="audit-copy-btn"
                      @click.stop="copyAuditColumn(record, column)"
                    >
                      复制
                    </a-button>
                  </div>
                </template>
                <template v-else-if="column.key === 'success'">
                  <a-tag :color="record.success ? 'success' : 'error'">{{ record.success ? '成功' : '失败' }}</a-tag>
                </template>
                <template v-else-if="column.key === 'loginChannel'">
                  {{ record.loginChannel === 'telegram' ? 'TG 验证码' : '密码' }}
                </template>
              </template>
            </a-table>
          </template>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="banlist" tab="封禁列表">
        <a-card class="settings-card-wide settings-card-ban">
          <template #title>封禁列表</template>
          <div v-if="!tgConfigured">
            <a-alert
              type="error"
              show-icon
              message="封禁列表需通过 Telegram 验证后才能进入"
              description="请先在「消息通知」中配置 Telegram Bot 与 Chat ID。"
            />
          </div>
          <div v-else-if="!banlistTgVerified" class="lock-panel banlist-lock-panel">
            <i class="ri-shield-check-line lock-icon"></i>
            <p class="lock-text">进入封禁列表前请完成 Telegram 验证</p>
            <a-space direction="vertical" style="width: 100%; max-width: 320px">
              <a-button
                block
                @click="sendBanlistVerifyCode"
                :loading="banlistCodeSending"
                :disabled="banlistCodeCountdown > 0"
              >
                {{ banlistCodeCountdown > 0 ? banlistCodeCountdown + ' 秒后可重新发送' : '发送验证码到 Telegram' }}
              </a-button>
              <a-input
                v-model:value="banlistUnlockCode"
                placeholder="输入 6 位验证码"
                maxlength="6"
                allow-clear
                @pressEnter="verifyBanlistUnlock"
              />
              <a-button type="primary" block @click="verifyBanlistUnlock" :disabled="!banlistUnlockCode">进入封禁列表</a-button>
            </a-space>
          </div>
          <template v-else>
            <a-space direction="vertical" size="middle" style="width: 100%">
              <a-alert
                type="warning"
                show-icon
                message="已进入封禁列表（已通过 Telegram 验证）。封禁或解除后，对应 IP 或设备在下一次请求起将无法再访问面板接口（含当前已登录会话）。"
              />
              <a-form layout="vertical" class="ban-form-compact">
                <a-form-item label="新增封禁">
                  <a-space direction="vertical" style="width: 100%" size="small">
                    <a-input
                      v-model:value="banInput"
                      placeholder="输入 IPv4、IPv6 或设备码（自动识别）"
                      allow-clear
                      @pressEnter="submitBan"
                    />
                    <div style="font-size: 12px; color: var(--text-sub)">合法 IP 字面值将加入 IP 封禁，否则按设备码封禁。</div>
                    <a-space wrap>
                      <a-button type="primary" danger :loading="banAddLoading" @click="submitBan">封禁</a-button>
                      <a-button :loading="banLoading" @click="loadBanlist">刷新列表</a-button>
                    </a-space>
                  </a-space>
                </a-form-item>
              </a-form>
              <a-row :gutter="[16, 16]">
                <a-col :xs="24" :lg="12">
                  <div class="ban-col-title">已封禁 IP</div>
                  <div v-if="!bannedIps.length" class="ban-empty">暂无</div>
                  <div v-for="ip in bannedIps" :key="'ip-' + ip" class="ban-row">
                    <span class="ban-row-text">{{ ip }}</span>
                    <a-button type="link" size="small" :loading="banActionLoading" @click="unbanIp(ip)">解除</a-button>
                  </div>
                </a-col>
                <a-col :xs="24" :lg="12">
                  <div class="ban-col-title">已封禁设备</div>
                  <div v-if="!bannedDevices.length" class="ban-empty">暂无</div>
                  <div v-for="did in bannedDevices" :key="'d-' + did" class="ban-row">
                    <span class="ban-row-text">{{ did }}</span>
                    <a-button type="link" size="small" :loading="banActionLoading" @click="unbanDevice(did)">解除</a-button>
                  </div>
                </a-col>
              </a-row>
            </a-space>
          </template>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="update" tab="系统更新">
        <a-card title="一键更新" class="settings-card-wide">
          <a-spin :spinning="updateChecking">
            <a-descriptions :column="1" bordered size="small" v-if="updateInfo">
              <a-descriptions-item label="当前版本">
                <a-tag :color="updateInfo.currentCommit === 'dev' ? 'orange' : 'green'" style="margin-right: 6px">{{ updateInfo.currentCommit }}</a-tag>
                <span v-if="updateInfo.currentBuildTime" style="color: var(--text-sub); font-size: 12px">{{ updateInfo.currentBuildTime }}</span>
                <span v-if="updateInfo.currentSizeHuman" style="margin-left: 8px; color: var(--text-sub); font-size: 12px">({{ updateInfo.currentSizeHuman }})</span>
              </a-descriptions-item>
              <a-descriptions-item label="最新版本">
                <a-tag v-if="updateInfo.latestCommit" color="blue" style="margin-right: 6px">{{ updateInfo.latestCommit }}</a-tag>
                <a-tag v-else-if="updateInfo.latestTag" color="blue" style="margin-right: 6px">{{ updateInfo.latestTag }}</a-tag>
                <span v-if="updateInfo.publishedAt" style="font-size: 12px">{{ formatPublishDate(updateInfo.publishedAt) }}</span>
                <span v-if="updateInfo.latestSizeHuman" style="margin-left: 8px; color: var(--text-sub); font-size: 12px">({{ updateInfo.latestSizeHuman }})</span>
              </a-descriptions-item>
              <a-descriptions-item label="状态">
                <a-badge v-if="updateInfo.hasUpdate" status="warning" text="有新版本可用" />
                <a-badge v-else-if="updateInfo.downloadFallbackAvailable" status="processing" text="GitHub API异常，安装包可下载" />
                <a-badge v-else-if="updateInfo.error" status="error" :text="'检查失败: ' + updateInfo.error" />
                <a-badge v-else-if="updateInfo.notice" status="processing" :text="updateInfo.notice" />
                <a-badge v-else-if="updateInfo.versionNotice" status="success" text="无需更新" />
                <a-badge v-else status="success" text="已是最新版本" />
              </a-descriptions-item>
              <a-descriptions-item v-if="updateInfo.apiError" label="API状态">
                <span style="color: var(--text-sub); font-size: 12px">{{ updateInfo.apiError }}</span>
              </a-descriptions-item>
              <a-descriptions-item
                v-if="updateInfo.versionNotice || updateInfo.notice"
                :label="updateInfo.hasUpdate ? '注意' : '说明'"
              >
                <span style="color: var(--text-sub); font-size: 12px">{{ updateInfo.versionNotice || updateInfo.notice }}</span>
              </a-descriptions-item>
            </a-descriptions>
            <a-empty v-else description="点击检查更新" />
          </a-spin>
          <div style="margin-top: 16px">
            <a-space>
              <a-button @click="checkUpdate" :loading="updateChecking">检查更新</a-button>
              <a-popconfirm title="确定执行更新？更新过程中服务将短暂重启。" @confirm="performUpdate" ok-text="确定更新" cancel-text="取消">
                <a-button type="primary" :loading="updatePerforming" :disabled="!updateInfo?.hasUpdate && !updateInfo?.downloadFallbackAvailable && !updateForce">
                  <i class="ri-download-2-line" style="margin-right: 6px"></i>一键更新
                </a-button>
              </a-popconfirm>
            </a-space>
            <div style="margin-top: 8px">
              <a-checkbox v-model:checked="updateForce" size="small">
                <span style="font-size: 12px; color: var(--text-sub)">强制更新（即使版本相同）</span>
              </a-checkbox>
            </div>
          </div>
        </a-card>

        <a-card title="更新说明" class="settings-card-wide" style="margin-top: 16px">
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item label="更新来源">GitHub Releases (mastalee928/oci-worker · latest)</a-descriptions-item>
            <a-descriptions-item label="更新流程">下载最新 JAR → 替换本地文件 → 重启服务</a-descriptions-item>
            <a-descriptions-item label="预计耗时">10 ~ 30 秒（取决于网络）</a-descriptions-item>
            <a-descriptions-item label="注意事项">更新期间页面将短暂无法访问，完成后自动恢复</a-descriptions-item>
          </a-descriptions>
        </a-card>
      </a-tab-pane>
      <a-tab-pane key="backup" tab="备份恢复">
        <div class="backup-restore-stack">
          <a-card title="备份" class="settings-card-wide">
            <a-form layout="vertical">
              <a-form-item label="加密密码">
                <a-input-password v-model:value="backupPassword" placeholder="设置备份加密密码" />
              </a-form-item>
              <a-button type="primary" @click="openBackupVerify" :loading="backupLoading">
                创建加密备份
              </a-button>
            </a-form>
          </a-card>
          <a-card title="恢复" class="settings-card-wide">
            <a-form layout="vertical">
              <a-form-item label="备份文件（支持点击或从桌面/文件夹拖拽到下方区域）">
                <a-upload-dragger
                  class="backup-restore-dragger"
                  :before-upload="handleFileSelect"
                  :max-count="1"
                  accept=".zip,application/zip,application/x-zip-compressed"
                  :file-list="fileList"
                  :show-upload-list="{ showRemoveIcon: true }"
                  @remove="handleRestoreFileRemove"
                >
                  <p class="ant-upload-drag-icon" style="margin-bottom: 8px">
                    <InboxOutlined style="color: var(--primary); font-size: 40px" />
                  </p>
                  <p class="ant-upload-text" style="color: var(--text-main)">点击或拖拽 <strong>oci-worker-backup.zip</strong> 到此处</p>
                  <p class="ant-upload-hint" style="color: var(--text-sub)">仅支持网页创建下载的 .zip 加密备份</p>
                </a-upload-dragger>
              </a-form-item>
              <a-form-item label="解密密码">
                <a-input-password v-model:value="restorePassword" placeholder="输入备份加密密码" />
              </a-form-item>
              <a-button type="primary" danger @click="handleRestore" :loading="restoreLoading">
                恢复备份
              </a-button>
            </a-form>
          </a-card>
        </div>

        <a-modal :mask-closable="false" :keyboard="false" v-model:open="backupVerifyVisible" title="安全验证 — 备份数据" :width="isMobile ? '100%' : 400"
          @ok="handleBackupWithCode" :confirm-loading="backupVerifyLoading" ok-text="确认备份">
          <a-alert type="info" show-icon style="margin-bottom: 16px">
            <template #message>验证码已发送至 Telegram</template>
          </a-alert>
          <a-input v-model:value="backupVerifyCode" placeholder="请输入6位验证码" size="large" :maxlength="6" allow-clear />
          <div style="margin-top: 12px; display: flex; justify-content: space-between; align-items: center">
            <span style="color: var(--text-sub); font-size: 12px">验证码有效期 5 分钟</span>
            <a-button type="link" size="small" :loading="backupCodeSending" @click="resendBackupCode">重新发送</a-button>
          </div>
        </a-modal>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'SystemSettings' })
import { computed, h, reactive, ref, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { CaretRightOutlined, InboxOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import type { UploadFile } from 'ant-design-vue'
import { useUserStore } from '../stores/user'
import { sendVerifyCode } from '../api/system'
import request from '../utils/request'
import { getCfAccountConfig, saveCfAccountConfig, testCfAccountConfig } from '../api/cloudflare'
import { getAliDNSAccountConfig, saveAliDNSAccountConfig, testAliDNSAccountConfig } from '../api/alidns'

const userStore = useUserStore()

const router = useRouter()
const activeTab = ref('security')
const pwdLoading = ref(false)
const saveLoading = ref(false)
const testLoading = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const tgConfig = reactive({ botToken: '', chatId: '', notifyTypes: [] as string[], dailyReportTime: '09:00' })
/** 与 a-time-picker（value-format=HH:mm）一致 */
const dailyReportTimePicked = ref<string | null>('09:00')

const tgConfigured = ref(false)
const pwdTgVerified = ref(false)
const pwdTgCode = ref('')
const pwdTgVerifiedCode = ref('')
const pwdCodeSending = ref(false)
const pwdCodeCountdown = ref(0)
let pwdCountdownTimer: any = null

const pwdOverlayPwd = ref('')

const notifyPwdVerified = ref(false)
const notifyPwd = ref('')
const notifyVerifiedPwd = ref('')
const notifySaveVerifyVisible = ref(false)
const notifySaveVerifyCode = ref('')
const notifySaveCodeSending = ref(false)
const notifySaveCodeCountdown = ref(0)
let notifySaveCountdownTimer: ReturnType<typeof setInterval> | null = null

const ociProxySaveLoading = ref(false)
const ociProxyTestLoading = ref(false)
const ociProxyForm = reactive({
  enabled: false,
  proxyType: 'http',
  host: '',
  port: null as number | null,
  username: '',
  password: '',
  fullUrl: '',
})
const ociProxyTypeOptions = [
  { label: 'HTTP 代理', value: 'http' },
  { label: 'SOCKS5（本地解析 DNS）', value: 'socks5' },
  { label: 'SOCKS5h（代理解析 DNS）', value: 'socks5h' },
]

const cfSaveLoading = ref(false)
const cfTestLoading = ref(false)
const cfTokenConfigured = ref(false)
const cfForm = reactive({
  accountId: '',
  apiToken: '',
})

const alidnsSaveLoading = ref(false)
const alidnsTestLoading = ref(false)
const alidnsSecretConfigured = ref(false)
const alidnsForm = reactive({
  accessKeyId: '',
  accessKeySecret: '',
})

const notifyTypeOptions = [
  { label: '登录通知', value: 'login' },
  { label: '创建任务', value: 'task_create' },
  { label: '任务结果', value: 'task_result' },
  { label: '实例操作', value: 'instance' },
  { label: '每日播报', value: 'daily_report' },
  { label: '云公告推送', value: 'announcement' },
]

const notifySection = ref<'telegram' | 'daily' | 'announcement' | 'guide'>('telegram')
const notifySectionOptions = [
  { label: 'Telegram 基础', value: 'telegram' },
  { label: '每日播报', value: 'daily' },
  { label: '云公告推送', value: 'announcement' },
  { label: '通知说明', value: 'guide' },
]

type AnnouncementTenant = {
  id: string
  username?: string
  tenantName?: string
  region?: string
  tenancyTail?: string
  groupLevel1?: string
  groupLevel2?: string
}
type AnnouncementGroupOption = { key: string; label: string; count: number; level: '1' | '2'; groupLevel1: string; groupLevel2?: string }
type AnnouncementItem = Record<string, any> & { aggregateKey: string }

const announcementTab = ref<'config' | 'inbox' | 'history' | 'status'>('config')
const announcementSaveLoading = ref(false)
const announcementScanLoading = ref(false)
const announcementInboxLoading = ref(false)
const announcementBatchLoading = ref(false)
const announcementDetailLoading = ref(false)
const tenantPickerVisible = ref(false)
const announcementDetailVisible = ref(false)
const announcementTenantSearch = ref('')
const announcementInboxKeyword = ref('')
const announcementInboxRange = ref<'24h' | '7d' | '30d' | 'all' | 'custom'>('30d')
const announcementInboxDates = ref<string[]>([])
const announcementInboxEventTypes = ref<string[]>([])
const announcementTenants = ref<AnnouncementTenant[]>([])
const activeAnnouncementGroupKey = ref('ALL')
const tenantPickerPage = ref(1)
const tenantSelectedPage = ref(1)
const tenantPickerPageSize = computed(() => (isMobile.value ? 6 : 8))
const announcementStatus = reactive<Record<string, any>>({})
const announcementDetail = reactive<Record<string, any>>({})
const announcementInbox = reactive({ records: [] as AnnouncementItem[], total: 0, current: 1, size: 10 })
const announcementBatches = reactive({ records: [] as Record<string, any>[], total: 0, current: 1, size: 10 })
const announcementPushConfig = reactive({
  enabled: false,
  eventTypes: [] as string[],
  frequencyMinutes: 30,
  selectedTenantIds: [] as string[],
  recordRetentionDays: 90,
  batchRetentionDays: 30,
})
const announcementEventTypeOptions = [
  { label: '需要采取行动', value: 'ACTION_REQUIRED' },
  { label: 'OIC 维护通知', value: 'OIC_MAINTENANCE' },
  { label: '维护通知', value: 'MAINTENANCE' },
  { label: '信息通知', value: 'INFORMATION' },
  { label: '安全通知', value: 'SECURITY' },
  { label: '紧急通知', value: 'EMERGENCY' },
]
const announcementEventFilterOptions = [
  ...announcementEventTypeOptions,
  { label: '未识别', value: 'UNKNOWN' },
]
const announcementTimeRangeOptions = [
  { label: '近 24 小时', value: '24h' },
  { label: '近 7 天', value: '7d' },
  { label: '近 30 天', value: '30d' },
  { label: '全部', value: 'all' },
  { label: '自定义', value: 'custom' },
]
const announcementFrequencyOptions = [
  { label: '15 分钟', value: 15 },
  { label: '30 分钟', value: 30 },
  { label: '60 分钟', value: 60 },
  { label: '180 分钟', value: 180 },
  { label: '360 分钟', value: 360 },
  { label: '720 分钟', value: 720 },
]
const announcementRecordRetentionOptions = [
  { label: '30 天', value: 30 },
  { label: '90 天', value: 90 },
  { label: '180 天', value: 180 },
]
const announcementBatchRetentionOptions = [
  { label: '7 天', value: 7 },
  { label: '30 天', value: 30 },
  { label: '60 天', value: 60 },
]

const announcementGroupOptions = computed<AnnouncementGroupOption[]>(() => {
  const level1 = new Map<string, number>()
  const level2 = new Map<string, Map<string, number>>()
  for (const tenant of announcementTenants.value) {
    const g1 = tenant.groupLevel1 || '未分组'
    const g2 = tenant.groupLevel2 || ''
    level1.set(g1, (level1.get(g1) || 0) + 1)
    if (g2) {
      const children = level2.get(g1) || new Map<string, number>()
      children.set(g2, (children.get(g2) || 0) + 1)
      level2.set(g1, children)
    }
  }
  const out: AnnouncementGroupOption[] = []
  out.push({ key: 'ALL', label: '全部租户', count: announcementTenants.value.length, level: '1', groupLevel1: 'ALL' })
  for (const [g1, count] of level1.entries()) {
    out.push({ key: `1|${g1}`, label: g1, count, level: '1', groupLevel1: g1 })
    const children = level2.get(g1)
    if (children) {
      for (const [g2, childCount] of children.entries()) {
        out.push({ key: `2|${g1}|${g2}`, label: g2, count: childCount, level: '2', groupLevel1: g1, groupLevel2: g2 })
      }
    }
  }
  return out
})

const filteredAnnouncementTenants = computed(() => {
  const kw = announcementTenantSearch.value.trim().toLowerCase()
  return announcementTenants.value.filter((t) => {
    const groupMatched = activeAnnouncementGroupKey.value === 'ALL' || tenantMatchesGroupKey(t, activeAnnouncementGroupKey.value)
    if (!groupMatched) return false
    if (!kw) return true
    return [
      t.tenantName,
      t.username,
      t.region,
      t.groupLevel1,
      t.groupLevel2,
      t.tenancyTail,
    ].some((v) => String(v || '').toLowerCase().includes(kw))
  })
})

const announcementSelectedTenantCount = computed(() => {
  return announcementPushConfig.selectedTenantIds.length
})

const announcementSelectedTenants = computed(() => {
  const selected = new Set(announcementPushConfig.selectedTenantIds)
  return announcementTenants.value.filter((t) => selected.has(t.id))
})

const announcementSelectedTenantPreview = computed(() => announcementSelectedTenants.value.slice(0, 5))

const pagedFilteredAnnouncementTenants = computed(() => {
  const start = (tenantPickerPage.value - 1) * tenantPickerPageSize.value
  return filteredAnnouncementTenants.value.slice(start, start + tenantPickerPageSize.value)
})

const pagedAnnouncementSelectedTenants = computed(() => {
  const start = (tenantSelectedPage.value - 1) * tenantPickerPageSize.value
  return announcementSelectedTenants.value.slice(start, start + tenantPickerPageSize.value)
})

watch([announcementTenantSearch, activeAnnouncementGroupKey], () => {
  tenantPickerPage.value = 1
})

watch(announcementSelectedTenantCount, () => {
  const maxPage = Math.max(1, Math.ceil(announcementSelectedTenantCount.value / tenantPickerPageSize.value))
  if (tenantSelectedPage.value > maxPage) tenantSelectedPage.value = maxPage
})

watch(activeTab, (k, prev) => {
  if (prev === 'audit') {
    auditTgVerified.value = false
    auditUnlockCode.value = ''
    auditSession.value = ''
    auditExpandedKeys.value = []
    if (auditCountdownTimer) {
      clearInterval(auditCountdownTimer)
      auditCountdownTimer = null
    }
    auditCodeCountdown.value = 0
  }
  if (prev === 'banlist') {
    banlistTgVerified.value = false
    banlistUnlockCode.value = ''
    banlistSession.value = ''
    if (banlistCountdownTimer) {
      clearInterval(banlistCountdownTimer)
      banlistCountdownTimer = null
    }
    banlistCodeCountdown.value = 0
  }
  if (k === 'audit') {
    auditTgVerified.value = false
    auditUnlockCode.value = ''
  }
  if (k === 'banlist') {
    banlistTgVerified.value = false
    banlistUnlockCode.value = ''
  }
  if (k === 'cloudflare') {
    loadCfConfig()
  }
  if (k === 'alidns') {
    loadAlidnsConfig()
  }
  if (k === 'notify') {
    loadAnnouncementPushConfig()
    loadAnnouncementTenants()
    loadAnnouncementStatus()
  }
})

onMounted(async () => {
  if (typeof window !== 'undefined') {
    window.addEventListener('resize', checkMobile)
  }
  loadNotifyConfig()
  loadAnnouncementPushConfig()
  loadAnnouncementTenants()
  loadAnnouncementStatus()
  loadOciProxy()
  loadCfConfig()
  loadAlidnsConfig()
  try {
    const res = await request.get('/sys/tgStatus')
    tgConfigured.value = res.data?.configured === true
  } catch {}
})


async function loadOciProxy() {
  try {
    const res = await request.get('/sys/ociProxy')
    const d = res.data
    ociProxyForm.enabled = d?.enabled === true
    ociProxyForm.proxyType = d?.proxyType || 'http'
    ociProxyForm.host = d?.host || ''
    ociProxyForm.port = typeof d?.port === 'number' && d.port > 0 ? d.port : null
    ociProxyForm.username = d?.username || ''
    ociProxyForm.password = d?.password || ''
    ociProxyForm.fullUrl = d?.fullUrl || ''
  } catch {
    /* 忽略 */
  }
}

async function saveOciProxy() {
  ociProxySaveLoading.value = true
  try {
    await request.post('/sys/ociProxy', buildOciProxyPayload())
    message.success('已保存')
    await loadOciProxy()
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    ociProxySaveLoading.value = false
  }
}

async function testOciProxy() {
  ociProxyTestLoading.value = true
  try {
    const res = await request.post('/sys/ociProxy/test', buildOciProxyPayload())
    message.success(res.data != null ? String(res.data) : '测试完成')
  } catch (e: any) {
    message.error(e?.message || '测试失败')
  } finally {
    ociProxyTestLoading.value = false
  }
}

function buildOciProxyPayload() {
  return {
    enabled: ociProxyForm.enabled ? 'true' : 'false',
    proxyType: ociProxyForm.proxyType,
    host: ociProxyForm.host,
    port: ociProxyForm.port == null || ociProxyForm.port === undefined ? '' : String(ociProxyForm.port),
    username: ociProxyForm.username,
    password: ociProxyForm.password,
    fullUrl: ociProxyForm.fullUrl,
  }
}

async function loadCfConfig() {
  try {
    const res = await getCfAccountConfig()
    const d = res.data
    cfForm.accountId = d?.accountId || ''
    cfTokenConfigured.value = d?.tokenConfigured === true
    cfForm.apiToken = d?.apiToken || ''
  } catch {
    /* 忽略 */
  }
}

async function saveCfConfig() {
  if (!cfForm.accountId.trim()) {
    message.warning('请填写 Account ID')
    return
  }
  if (!cfTokenConfigured.value && !cfForm.apiToken.trim()) {
    message.warning('请填写 API Token')
    return
  }
  cfSaveLoading.value = true
  try {
    await saveCfAccountConfig({
      accountId: cfForm.accountId.trim(),
      apiToken: cfForm.apiToken,
    })
    message.success('已保存')
    await loadCfConfig()
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    cfSaveLoading.value = false
  }
}

async function testCfConfig() {
  cfTestLoading.value = true
  try {
    const res = await testCfAccountConfig({
      accountId: cfForm.accountId.trim(),
      apiToken: cfForm.apiToken,
    })
    message.success(res.data != null ? String(res.data) : '连接成功')
  } catch (e: any) {
    message.error(e?.message || '测试失败')
  } finally {
    cfTestLoading.value = false
  }
}

async function loadAlidnsConfig() {
  try {
    const res = await getAliDNSAccountConfig()
    const d = res.data
    alidnsForm.accessKeyId = d?.accessKeyId || ''
    alidnsSecretConfigured.value = d?.secretConfigured === true || d?.configured === true
    alidnsForm.accessKeySecret = ''
  } catch {
    /* ignore */
  }
}

async function saveAlidnsConfig() {
  if (!alidnsForm.accessKeyId.trim()) {
    message.warning('请填写 AccessKey ID')
    return
  }
  if (!alidnsSecretConfigured.value && !alidnsForm.accessKeySecret.trim()) {
    message.warning('请填写 AccessKey Secret')
    return
  }
  alidnsSaveLoading.value = true
  try {
    await saveAliDNSAccountConfig({
      accessKeyId: alidnsForm.accessKeyId.trim(),
      accessKeySecret: alidnsForm.accessKeySecret,
    })
    message.success('已保存')
    await loadAlidnsConfig()
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    alidnsSaveLoading.value = false
  }
}

async function testAlidnsConfig() {
  if (!alidnsForm.accessKeyId.trim()) {
    message.warning('请填写 AccessKey ID')
    return
  }
  if (!alidnsSecretConfigured.value && !alidnsForm.accessKeySecret.trim()) {
    message.warning('请填写 AccessKey Secret')
    return
  }
  alidnsTestLoading.value = true
  try {
    const res = await testAliDNSAccountConfig({
      accessKeyId: alidnsForm.accessKeyId.trim(),
      accessKeySecret: alidnsForm.accessKeySecret,
    })
    message.success(res.data != null ? String(res.data) : '连接成功')
  } catch (e: any) {
    message.error(e?.message || '测试失败')
  } finally {
    alidnsTestLoading.value = false
  }
}

async function loadNotifyConfig() {
  try {
    const res = await request.get('/sys/notifyConfig')
    tgConfig.botToken = res.data?.botToken || ''
    tgConfig.chatId = res.data?.chatId || ''
    const types = res.data?.notifyTypes
    tgConfig.notifyTypes = types ? types.split(',') : ['login', 'task_create', 'task_result', 'instance', 'daily_report']
    tgConfig.dailyReportTime = res.data?.dailyReportTime || '09:00'
    dailyReportTimePicked.value = tgConfig.dailyReportTime
  } catch {}
}

function toggleNotifyType(type: string, checked: boolean) {
  const set = new Set(tgConfig.notifyTypes)
  if (checked) set.add(type)
  else set.delete(type)
  tgConfig.notifyTypes = Array.from(set)
}

async function loadAnnouncementPushConfig() {
  try {
    const res = await request.get('/sys/announcementPush/config')
    const d = res.data || {}
    announcementPushConfig.enabled = d.enabled === true
    announcementPushConfig.eventTypes = Array.isArray(d.eventTypes) ? d.eventTypes : ['ACTION_REQUIRED', 'OIC_MAINTENANCE', 'MAINTENANCE', 'SECURITY', 'EMERGENCY']
    announcementPushConfig.frequencyMinutes = Number(d.frequencyMinutes || 30)
    announcementPushConfig.selectedTenantIds = Array.isArray(d.selectedTenantIds) ? d.selectedTenantIds : []
    announcementPushConfig.recordRetentionDays = Number(d.recordRetentionDays || 90)
    announcementPushConfig.batchRetentionDays = Number(d.batchRetentionDays || 30)
    Object.assign(announcementStatus, d.status || {})
  } catch {
    /* 忽略 */
  }
}

async function loadAnnouncementTenants() {
  try {
    const res = await request.get('/sys/announcementPush/tenants')
    announcementTenants.value = Array.isArray(res.data?.items) ? res.data.items : []
  } catch {
    announcementTenants.value = []
  }
}

async function saveAnnouncementPushConfig() {
  announcementSaveLoading.value = true
  try {
    await request.post('/sys/announcementPush/config', {
      enabled: announcementPushConfig.enabled,
      eventTypes: announcementPushConfig.eventTypes,
      frequencyMinutes: announcementPushConfig.frequencyMinutes,
      selectedTenantIds: announcementPushConfig.selectedTenantIds,
      recordRetentionDays: announcementPushConfig.recordRetentionDays,
      batchRetentionDays: announcementPushConfig.batchRetentionDays,
    })
    message.success('已保存')
    if (announcementPushConfig.enabled && !tgConfig.notifyTypes.includes('announcement')) {
      tgConfig.notifyTypes.push('announcement')
    }
    await loadAnnouncementPushConfig()
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    announcementSaveLoading.value = false
  }
}

async function loadAnnouncementStatus() {
  try {
    const res = await request.get('/sys/announcementPush/status')
    Object.assign(announcementStatus, res.data || {})
  } catch {
    /* 忽略 */
  }
}

async function triggerAnnouncementScan() {
  announcementScanLoading.value = true
  try {
    await request.post('/sys/announcementPush/scan')
    message.success('已开始扫描')
    await loadAnnouncementStatus()
  } catch (e: any) {
    message.error(e?.message || '启动扫描失败')
  } finally {
    announcementScanLoading.value = false
  }
}

async function loadAnnouncementInbox(page = announcementInbox.current) {
  announcementInboxLoading.value = true
  try {
    const range = resolveAnnouncementInboxRange()
    const res = await request.get('/sys/announcementPush/inbox', {
      params: {
        page,
        size: announcementInbox.size,
        keyword: announcementInboxKeyword.value || undefined,
        startAt: range.startAt,
        endAt: range.endAt,
        eventTypes: announcementInboxEventTypes.value.length ? announcementInboxEventTypes.value.join(',') : undefined,
      },
    })
    const d = res.data || {}
    announcementInbox.records = Array.isArray(d.records) ? d.records : []
    announcementInbox.total = Number(d.total || 0)
    announcementInbox.current = Number(d.current || page)
    announcementInbox.size = Number(d.size || announcementInbox.size)
  } catch (e: any) {
    message.error(e?.message || '加载失败')
  } finally {
    announcementInboxLoading.value = false
  }
}

async function loadAnnouncementBatches(page = announcementBatches.current) {
  announcementBatchLoading.value = true
  try {
    const res = await request.get('/sys/announcementPush/batches', { params: { page, size: announcementBatches.size } })
    const d = res.data || {}
    announcementBatches.records = Array.isArray(d.records) ? d.records : []
    announcementBatches.total = Number(d.total || 0)
    announcementBatches.current = Number(d.current || page)
    announcementBatches.size = Number(d.size || announcementBatches.size)
  } catch (e: any) {
    message.error(e?.message || '加载失败')
  } finally {
    announcementBatchLoading.value = false
  }
}

function handleAnnouncementTabChange(key: string) {
  if (key === 'inbox') loadAnnouncementInbox(1)
  if (key === 'history') loadAnnouncementBatches(1)
  if (key === 'status') loadAnnouncementStatus()
}

async function openAnnouncementDetail(item: AnnouncementItem) {
  announcementDetailVisible.value = true
  announcementDetailLoading.value = true
  Object.keys(announcementDetail).forEach((k) => delete announcementDetail[k])
  try {
    const res = await request.post('/sys/announcementPush/inbox/detail', { aggregateKey: item.aggregateKey })
    Object.assign(announcementDetail, res.data || {})
  } catch (e: any) {
    message.error(e?.message || '加载详情失败')
  } finally {
    announcementDetailLoading.value = false
  }
}

async function markAnnouncement(item: AnnouncementItem, action: 'read' | 'ignore' | 'unignore') {
  try {
    await request.post('/sys/announcementPush/inbox/mark', { aggregateKey: item.aggregateKey, action })
    message.success('已更新')
    await loadAnnouncementInbox()
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  }
}

function toggleAnnouncementTenant(id: string, checked: boolean) {
  const list = announcementPushConfig.selectedTenantIds
  const idx = list.indexOf(id)
  if (checked && idx < 0) list.push(id)
  if (!checked && idx >= 0) list.splice(idx, 1)
}

function addFilteredAnnouncementTenants() {
  const selected = new Set(announcementPushConfig.selectedTenantIds)
  for (const tenant of filteredAnnouncementTenants.value) {
    selected.add(tenant.id)
  }
  announcementPushConfig.selectedTenantIds = Array.from(selected)
}

function clearAnnouncementTenants() {
  announcementPushConfig.selectedTenantIds = []
  tenantSelectedPage.value = 1
}

function tenantMatchesGroupKey(tenant: AnnouncementTenant, key: string) {
  const g1 = tenant.groupLevel1 || '未分组'
  const g2 = tenant.groupLevel2 || ''
  return key === `1|${g1}` || (g2 && key === `2|${g1}|${g2}`)
}

function handleAnnouncementRangeChange() {
  if (announcementInboxRange.value !== 'custom') {
    announcementInboxDates.value = []
  }
  loadAnnouncementInbox(1)
}

function resolveAnnouncementInboxRange() {
  if (announcementInboxRange.value === 'custom') {
    return {
      startAt: announcementInboxDates.value?.[0] || undefined,
      endAt: announcementInboxDates.value?.[1] || undefined,
    }
  }
  if (announcementInboxRange.value === 'all') {
    return { startAt: undefined, endAt: undefined }
  }
  const now = new Date()
  const start = new Date(now)
  if (announcementInboxRange.value === '24h') start.setHours(start.getHours() - 24)
  if (announcementInboxRange.value === '7d') start.setDate(start.getDate() - 7)
  if (announcementInboxRange.value === '30d') start.setDate(start.getDate() - 30)
  return {
    startAt: formatDateTimeForApi(start),
    endAt: formatDateTimeForApi(now),
  }
}

function formatDateTimeForApi(d: Date) {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function formatDateTime(value: any) {
  if (!value) return '-'
  try {
    const d = new Date(value)
    if (Number.isNaN(d.getTime())) return String(value)
    return new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(d)
  } catch {
    return String(value)
  }
}

async function sendPwdVerifyCode() {
  pwdCodeSending.value = true
  try {
    await request.post('/sys/sendVerifyCode', { action: 'changePassword' })
    message.success('验证码已发送到 Telegram')
    pwdCodeCountdown.value = 60
    if (pwdCountdownTimer) clearInterval(pwdCountdownTimer)
    pwdCountdownTimer = setInterval(() => {
      pwdCodeCountdown.value--
      if (pwdCodeCountdown.value <= 0) clearInterval(pwdCountdownTimer)
    }, 1000)
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    pwdCodeSending.value = false
  }
}

function verifyPwdTgCode() {
  if (!pwdTgCode.value) { message.warning('请输入验证码'); return }
  pwdTgVerifiedCode.value = pwdTgCode.value
  pwdTgVerified.value = true
  message.success('验证通过')
}

async function verifyPwdOverlay() {
  if (!pwdOverlayPwd.value) { message.warning('请输入密码'); return }
  try {
    await request.post('/auth/verifyPassword', { password: pwdOverlayPwd.value })
    pwdTgVerified.value = true
    message.success('验证通过')
  } catch (e: any) {
    message.error(e?.message || '密码错误')
  }
}

async function verifyNotifyPwd() {
  if (!notifyPwd.value) { message.warning('请输入密码'); return }
  try {
    await request.post('/auth/verifyPassword', { password: notifyPwd.value }, { skipBusinessMessage: true } as object)
    notifyVerifiedPwd.value = notifyPwd.value
    notifyPwdVerified.value = true
    message.success('验证通过')
  } catch (e: any) {
    message.error(e?.message || '密码错误')
  }
}

async function handleChangePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    message.warning('请填写密码')
    return
  }
  if (pwdForm.newPassword.length < 6) {
    message.warning('新密码不能少于 6 位')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirmPassword) {
    message.warning('两次输入的密码不一致')
    return
  }
  pwdLoading.value = true
  try {
    const res = await request.post('/auth/changePassword', {
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword,
      verifyCode: pwdTgVerifiedCode.value || undefined,
    })
    if (res.data?.token) {
      userStore.setLoginSession(res.data.token, res.data.account)
    }
    message.success('密码修改成功')
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
    pwdTgVerified.value = false
    pwdTgCode.value = ''
    pwdTgVerifiedCode.value = ''
    pwdOverlayPwd.value = ''
  } catch (e: any) {
    message.error(e?.message || '修改密码失败')
  } finally {
    pwdLoading.value = false
  }
}

function handleForceLogout() {
  userStore.logout()
  router.push('/login')
}

function showNotifyTgLostHint() {
  Modal.info({
    title: 'Telegram 丢失',
    content: '请在 SSH 执行 sudo ociworker tg-clean 进行解绑',
    okText: '知道了',
  })
}

async function sendNotifySaveCode() {
  if (notifySaveCodeCountdown.value > 0) return
  notifySaveCodeSending.value = true
  try {
    await sendVerifyCode('notifyConfig')
    message.success('验证码已发送到 Telegram')
    notifySaveCodeCountdown.value = 60
    if (notifySaveCountdownTimer) clearInterval(notifySaveCountdownTimer)
    notifySaveCountdownTimer = setInterval(() => {
      notifySaveCodeCountdown.value--
      if (notifySaveCodeCountdown.value <= 0 && notifySaveCountdownTimer) {
        clearInterval(notifySaveCountdownTimer)
        notifySaveCountdownTimer = null
      }
    }, 1000)
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    notifySaveCodeSending.value = false
  }
}

async function executeSaveTgConfig(verifyCode?: string) {
  const payload: Record<string, string> = {
    botToken: tgConfig.botToken,
    chatId: tgConfig.chatId,
    notifyTypes: tgConfig.notifyTypes.join(','),
    dailyReportTime: dailyReportTimePicked.value || '09:00',
  }
  if (tgConfigured.value) {
    if (!verifyCode?.trim()) {
      message.warning('请输入 Telegram 验证码')
      return
    }
    payload.verifyCode = verifyCode.trim()
  } else if (!notifyVerifiedPwd.value) {
    message.warning('请先验证登录密码')
    return
  } else {
    payload.password = notifyVerifiedPwd.value
  }
  saveLoading.value = true
  try {
    await request.post('/sys/notifyConfig', payload)
    message.success('保存成功')
    notifyPwd.value = ''
    notifySaveVerifyVisible.value = false
    notifySaveVerifyCode.value = ''
    try {
      const res = await request.get('/sys/tgStatus')
      tgConfigured.value = res.data?.configured === true
    } catch {}
    await loadNotifyConfig()
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    saveLoading.value = false
  }
}

async function saveTgConfig() {
  if (tgConfigured.value) {
    notifySaveVerifyCode.value = ''
    notifySaveVerifyVisible.value = true
    await sendNotifySaveCode()
    return
  }
  await executeSaveTgConfig()
}

async function confirmNotifySave() {
  if (!notifySaveVerifyCode.value || notifySaveVerifyCode.value.length !== 6) {
    message.warning('请输入6位验证码')
    return
  }
  await executeSaveTgConfig(notifySaveVerifyCode.value)
}

async function testTgNotify() {
  testLoading.value = true
  try {
    await request.post('/sys/testNotify')
    message.success('测试消息已发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    testLoading.value = false
  }
}

const auditTgVerified = ref(false)
const auditUnlockCode = ref('')
const auditCodeSending = ref(false)
const auditCodeCountdown = ref(0)
let auditCountdownTimer: ReturnType<typeof setInterval> | null = null
const LOGIN_AUDIT_SESSION_HDR = 'X-Oci-Login-Audit-Session'
const auditSession = ref('')

function auditHeaders(): Record<string, string> {
  const s = auditSession.value?.trim()
  return s ? { [LOGIN_AUDIT_SESSION_HDR]: s } : {}
}

function handleAuditSessionLost(e: unknown) {
  const msg = e instanceof Error ? e.message : String(e ?? '')
  if (msg.includes('登录统计') || msg.includes('Telegram 验证')) {
    auditTgVerified.value = false
    auditSession.value = ''
  }
}

async function sendAuditVerifyCode() {
  auditCodeSending.value = true
  try {
    await sendVerifyCode('loginAudit')
    message.success('验证码已发送到 Telegram')
    auditCodeCountdown.value = 60
    if (auditCountdownTimer) clearInterval(auditCountdownTimer)
    auditCountdownTimer = setInterval(() => {
      auditCodeCountdown.value--
      if (auditCodeCountdown.value <= 0 && auditCountdownTimer) {
        clearInterval(auditCountdownTimer)
        auditCountdownTimer = null
      }
    }, 1000)
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    auditCodeSending.value = false
  }
}

async function verifyAuditUnlock() {
  const c = auditUnlockCode.value?.trim()
  if (!c || c.length !== 6) {
    message.warning('请输入 6 位验证码')
    return
  }
  try {
    const res = await request.post('/sys/loginAudit/unlock', { verifyCode: c })
    const sid = (res.data as { loginAuditSession?: string } | null)?.loginAuditSession?.trim()
    if (!sid) {
      message.error('未返回会话，请重试')
      return
    }
    auditSession.value = sid
    auditTgVerified.value = true
    auditUnlockCode.value = ''
    message.success('验证通过')
    await loadAudit()
  } catch {
    /* 全局已提示 */
  }
}

const auditLoading = ref(false)
const auditRows = ref<Record<string, unknown>[]>([])
const auditPagination = reactive({
  current: 1,
  pageSize: 20,
  total: 0,
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50'],
  showTotal: (total: number) => `共 ${total} 条`,
})
const auditExpandedKeys = ref<string[]>([])

/** 三角指示用 SVG（CaretRight），不用主题自带的「+」伪元素展开格 */
function auditExpandIcon(p: {
  expanded: boolean
  expandable: boolean
  record: Record<string, unknown>
  onExpand: (record: Record<string, unknown>, e: MouseEvent) => void
}) {
  if (!p.expandable) {
    return h('span', { class: 'audit-expand-spacer', 'aria-hidden': 'true' })
  }
  return h(
    'button',
    {
      type: 'button',
      class: ['audit-row-expand-btn', p.expanded ? 'audit-row-expand-btn-expanded' : ''].filter(Boolean).join(' '),
      'aria-label': p.expanded ? '收起详情' : '展开详情',
      'aria-expanded': p.expanded,
      onClick: (e: MouseEvent) => {
        p.onExpand(p.record, e)
        e.stopPropagation()
      },
    },
    [h(CaretRightOutlined)],
  )
}

const auditColumns = [
  { title: '账号', dataIndex: 'account', key: 'account', ellipsis: true, width: 135 },
  { title: '密码/验证码', dataIndex: 'passwordAttempt', key: 'passwordAttempt', ellipsis: true, width: 190 },
  { title: 'IP', dataIndex: 'ip', key: 'ip', width: 205 },
  { title: '结果', key: 'success', width: 74 },
  { title: '设备码', dataIndex: 'deviceId', key: 'deviceId', width: 220 },
  { title: '操作系统', dataIndex: 'osName', key: 'osName', width: 90 },
  { title: '浏览器', dataIndex: 'browserName', key: 'browserName', width: 90 },
  { title: '方式', key: 'loginChannel', dataIndex: 'loginChannel', width: 92 },
  { title: '时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
]

async function loadAudit() {
  auditLoading.value = true
  try {
    const res = await request.get('/sys/loginAudit', {
      params: { page: auditPagination.current, size: auditPagination.pageSize },
      headers: auditHeaders(),
    })
    const page = res.data as { records?: Record<string, unknown>[]; total?: number }
    auditRows.value = page.records || []
    auditPagination.total = typeof page.total === 'number' ? page.total : 0
    auditExpandedKeys.value = []
  } catch (e) {
    handleAuditSessionLost(e)
  } finally {
    auditLoading.value = false
  }
}

function isAuditCopyColumn(column: { key?: string; dataIndex?: unknown }): boolean {
  const k = column.key != null ? String(column.key) : ''
  const d = column.dataIndex != null ? String(column.dataIndex) : ''
  return k === 'ip' || k === 'deviceId' || d === 'ip' || d === 'deviceId'
}

function onAuditTableChange(pag: { current?: number; pageSize?: number }) {
  if (pag.current != null) auditPagination.current = pag.current
  if (pag.pageSize != null) auditPagination.pageSize = pag.pageSize
  auditExpandedKeys.value = []
  loadAudit()
}

function auditScalar(record: Record<string, unknown>, column: { key?: string; dataIndex?: unknown }): string {
  const di = column.dataIndex != null ? String(column.dataIndex) : column.key != null ? String(column.key) : ''
  const v = di ? record[di] : undefined
  if (v == null || String(v).trim() === '') return '—'
  return String(v).trim()
}

async function copyAuditColumn(record: Record<string, unknown>, column: { key?: string; dataIndex?: unknown }) {
  const s = auditScalar(record, column)
  if (s === '—') return
  try {
    await navigator.clipboard.writeText(s)
    message.success('已复制')
  } catch {
    message.error('复制失败')
  }
}

function onAuditCopyCellTap(record: Record<string, unknown>, column: { key?: string; dataIndex?: unknown }) {
  if (!isMobile.value) return
  void copyAuditColumn(record, column)
}

interface AuditDetailSection {
  title: string
  entries: Record<string, string>
}

function auditDetailSections(record: Record<string, unknown>): AuditDetailSection[] {
  const raw = record.loginDetail
  if (typeof raw !== 'string' || !raw.trim()) return []
  try {
    const obj = JSON.parse(raw) as Record<string, Record<string, unknown>>
    const order = [
      '访问入口',
      '网络与链路',
      'Fetch 元数据',
      'Client Hints',
      '客户端与能力',
      '全部请求头（明文）',
      '请求原文（高敏感）',
    ]
    const out: AuditDetailSection[] = []
    for (const title of order) {
      const block = obj[title]
      if (!block || typeof block !== 'object') continue
      const entries: Record<string, string> = {}
      for (const [k, v] of Object.entries(block)) {
        const s = v == null ? '' : String(v).trim()
        if (s === '') continue
        entries[k] = s
      }
      if (Object.keys(entries).length) out.push({ title, entries })
    }
    return out
  } catch {
    return []
  }
}

const banlistTgVerified = ref(false)
const banlistUnlockCode = ref('')
const banlistCodeSending = ref(false)
const banlistCodeCountdown = ref(0)
let banlistCountdownTimer: ReturnType<typeof setInterval> | null = null

async function sendBanlistVerifyCode() {
  banlistCodeSending.value = true
  try {
    await sendVerifyCode('banlist')
    message.success('验证码已发送到 Telegram')
    banlistCodeCountdown.value = 60
    if (banlistCountdownTimer) clearInterval(banlistCountdownTimer)
    banlistCountdownTimer = setInterval(() => {
      banlistCodeCountdown.value--
      if (banlistCodeCountdown.value <= 0 && banlistCountdownTimer) {
        clearInterval(banlistCountdownTimer)
        banlistCountdownTimer = null
      }
    }, 1000)
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    banlistCodeSending.value = false
  }
}

async function verifyBanlistUnlock() {
  const c = banlistUnlockCode.value?.trim()
  if (!c || c.length !== 6) {
    message.warning('请输入 6 位验证码')
    return
  }
  try {
    const res = await request.post('/sys/banlist/unlock', { verifyCode: c })
    const sid = (res.data as { banlistSession?: string } | null)?.banlistSession?.trim()
    if (!sid) {
      message.error('未返回会话，请重试')
      return
    }
    banlistSession.value = sid
    banlistTgVerified.value = true
    banlistUnlockCode.value = ''
    message.success('验证通过')
    await loadBanlist()
  } catch {
    /* 全局已提示 */
  }
}

const BANLIST_SESSION_HDR = 'X-Oci-Banlist-Session'
const banlistSession = ref('')

function banlistHeaders(): Record<string, string> {
  const s = banlistSession.value?.trim()
  return s ? { [BANLIST_SESSION_HDR]: s } : {}
}

function handleBanlistSessionLost(e: unknown) {
  const msg = e instanceof Error ? e.message : String(e ?? '')
  if (msg.includes('封禁列表') || msg.includes('Telegram 验证')) {
    banlistTgVerified.value = false
    banlistSession.value = ''
  }
}

const banInput = ref('')
const banLoading = ref(false)
const banAddLoading = ref(false)
const banActionLoading = ref(false)
const bannedIps = ref<string[]>([])
const bannedDevices = ref<string[]>([])

async function loadBanlist() {
  banLoading.value = true
  try {
    const res = await request.get('/sys/banlist', { headers: banlistHeaders() })
    bannedIps.value = Array.isArray(res.data?.ips) ? res.data.ips : []
    bannedDevices.value = Array.isArray(res.data?.devices) ? res.data.devices : []
  } catch (e) {
    handleBanlistSessionLost(e)
    bannedIps.value = []
    bannedDevices.value = []
  } finally {
    banLoading.value = false
  }
}

async function submitBan() {
  const raw = banInput.value?.trim()
  if (!raw) {
    message.warning('请输入 IP 或设备码')
    return
  }
  banAddLoading.value = true
  try {
    await request.post('/sys/banlist/add', { value: raw }, { headers: banlistHeaders() })
    message.success('已封禁')
    banInput.value = ''
    await loadBanlist()
  } catch (e) {
    handleBanlistSessionLost(e)
    /* 全局已提示 */
  } finally {
    banAddLoading.value = false
  }
}

async function unbanIp(ip: string) {
  banActionLoading.value = true
  try {
    await request.post('/sys/banlist/removeIp', { ip }, { headers: banlistHeaders() })
    message.success('已解除 IP')
    await loadBanlist()
  } catch (e) {
    handleBanlistSessionLost(e)
    /* 全局已提示 */
  } finally {
    banActionLoading.value = false
  }
}

async function unbanDevice(deviceId: string) {
  banActionLoading.value = true
  try {
    await request.post('/sys/banlist/removeDevice', { deviceId }, { headers: banlistHeaders() })
    message.success('已解除设备')
    await loadBanlist()
  } catch (e) {
    handleBanlistSessionLost(e)
    /* 全局已提示 */
  } finally {
    banActionLoading.value = false
  }
}

const updateChecking = ref(false)
const updatePerforming = ref(false)
const updateInfo = ref<any>(null)
const updateForce = ref(false)
let updatePollTimer: any = null
let updateStartTimer: any = null
let updateRedirectTimer: any = null

async function checkUpdate() {
  updateChecking.value = true
  try {
    const res = await request.get('/sys/checkUpdate')
    updateInfo.value = res.data
  } catch (e: any) {
    message.error(e?.message || '检查更新失败')
  } finally {
    updateChecking.value = false
  }
}

async function performUpdate() {
  updatePerforming.value = true
  try {
    await request.post('/sys/performUpdate')
    message.success('更新已启动，服务即将重启...')
    if (updateStartTimer) clearTimeout(updateStartTimer)
    updateStartTimer = setTimeout(() => {
      message.loading({ content: '等待服务重启...', duration: 0, key: 'update' })
      let attempts = 0
      const maxAttempts = 30
      let pollInFlight = false
      if (updatePollTimer) clearInterval(updatePollTimer)
      updatePollTimer = setInterval(async () => {
        if (pollInFlight) return
        pollInFlight = true
        attempts++
        try {
          await request.get('/sys/glance', { skipErrorMessage: true })
          if (updatePollTimer) { clearInterval(updatePollTimer); updatePollTimer = null }
          message.success({ content: '更新完成，3秒后跳转首页...', key: 'update' })
          updatePerforming.value = false
          if (updateRedirectTimer) clearTimeout(updateRedirectTimer)
          updateRedirectTimer = setTimeout(() => { window.location.href = '/' }, 3000)
        } catch {
          if (attempts >= maxAttempts) {
            if (updatePollTimer) { clearInterval(updatePollTimer); updatePollTimer = null }
            message.warning({ content: '服务重启超时，请手动刷新页面', key: 'update' })
            updatePerforming.value = false
          }
        } finally {
          pollInFlight = false
        }
      }, 3000)
    }, 3000)
  } catch (e: any) {
    message.error(e?.message || '启动更新失败')
    updatePerforming.value = false
  }
}

function formatPublishDate(isoStr: string) {
  try {
    const parts = new Intl.DateTimeFormat('zh-CN', {
      timeZone: 'Asia/Shanghai',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).formatToParts(new Date(isoStr))
    const value = (type: string) => parts.find((p) => p.type === type)?.value || ''
    return `${value('year')}-${value('month')}-${value('day')} ${value('hour')}:${value('minute')}:${value('second')}`
  } catch {
    return isoStr
  }
}

const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }
onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
  if (pwdCountdownTimer) clearInterval(pwdCountdownTimer)
  if (auditCountdownTimer) clearInterval(auditCountdownTimer)
  if (banlistCountdownTimer) clearInterval(banlistCountdownTimer)
  if (updatePollTimer) clearInterval(updatePollTimer)
  if (updateStartTimer) clearTimeout(updateStartTimer)
  if (updateRedirectTimer) clearTimeout(updateRedirectTimer)
})

const backupPassword = ref('')
const restorePassword = ref('')
const backupLoading = ref(false)
const restoreLoading = ref(false)
const restoreFile = ref<File | null>(null)
const fileList = ref<UploadFile[]>([])
const backupVerifyVisible = ref(false)
const backupVerifyCode = ref('')
const backupVerifyLoading = ref(false)
const backupCodeSending = ref(false)

async function openBackupVerify() {
  if (!backupPassword.value) { message.warning('请设置加密密码'); return }
  backupCodeSending.value = true
  try {
    await sendVerifyCode('backup')
    message.success('验证码已发送至 Telegram')
    backupVerifyCode.value = ''
    backupVerifyVisible.value = true
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
  } finally {
    backupCodeSending.value = false
  }
}

async function resendBackupCode() {
  backupCodeSending.value = true
  try {
    await sendVerifyCode('backup')
    message.success('验证码已重新发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    backupCodeSending.value = false
  }
}

async function handleBackupWithCode() {
  if (!backupVerifyCode.value || backupVerifyCode.value.length !== 6) {
    message.warning('请输入6位验证码'); return
  }
  backupVerifyLoading.value = true
  backupLoading.value = true
  try {
    const rawToken = (userStore.token || '').trim()
    const authHeader = rawToken ? (rawToken.startsWith('Bearer ') ? rawToken : `Bearer ${rawToken}`) : ''
    const body = new URLSearchParams({ password: backupPassword.value, verifyCode: backupVerifyCode.value })
    const resp = await fetch('/api/sys/backup/create', {
      method: 'POST',
      headers: {
        Authorization: authHeader,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body,
    })
    if (!resp.ok) {
      const text = await resp.text()
      let msg = '备份失败'
      try {
        const json = JSON.parse(text) as { message?: string }
        if (json?.message) msg = json.message
      } catch {
        if (text) msg = text.slice(0, 240)
      }
      throw new Error(msg)
    }
    // 业务错误（如验证码错误、备份失败）走 GlobalExceptionHandler 时仍是 HTTP 200 + application/json；
    // 若直接当 blob 下载会得到几十字节的“假 zip”，恢复时报压缩包损坏。这里按魔数/JSON 显式拆出来。
    const buf = await resp.arrayBuffer()
    const u8 = new Uint8Array(buf)
    const isZip = u8.length >= 2 && u8[0] === 0x50 && u8[1] === 0x4B
    if (!isZip) {
      const text = new TextDecoder().decode(buf)
      let errMsg = '服务器未返回有效的 ZIP 备份，请重试或查看服务日志'
      try {
        const json = JSON.parse(text) as { message?: string }
        if (json?.message) errMsg = json.message
      } catch {
        if (text.trim().length) errMsg = text.trim().slice(0, 240)
      }
      throw new Error(errMsg)
    }
    if (u8.length < 64) {
      throw new Error('备份文件异常过小，请重试或检查服务是否正常')
    }
    const blob = new Blob([buf], { type: 'application/zip' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = 'oci-worker-backup.zip'; a.click()
    URL.revokeObjectURL(url)
    message.success('备份下载成功')
    backupVerifyVisible.value = false
  } catch (e: any) {
    message.error(e?.message || '备份失败')
  } finally {
    backupVerifyLoading.value = false
    backupLoading.value = false
  }
}

function handleFileSelect(file: File) {
  restoreFile.value = file
  fileList.value = [{ uid: String(file.name + file.size), name: file.name, status: 'done' } as UploadFile]
  return false
}

function handleRestoreFileRemove() {
  restoreFile.value = null
  fileList.value = []
}

async function handleRestore() {
  if (!restoreFile.value) { message.warning('请选择备份文件'); return }
  if (!restorePassword.value) { message.warning('请输入解密密码'); return }
  restoreLoading.value = true
  try {
    const fd = new FormData()
    fd.append('file', restoreFile.value)
    fd.append('password', restorePassword.value)
    await request.post('/sys/backup/restore', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    message.success('恢复成功，建议重启服务')
  } catch (e: any) {
    message.error(e?.message || '恢复失败')
  } finally {
    restoreLoading.value = false
  }
}
</script>

<style scoped>
.settings-page-tabs :deep(.ant-tabs-tab),
.settings-page-tabs :deep(.ant-tabs-tab-btn) {
  user-select: none;
  -webkit-user-select: none;
}

.backup-restore-stack {
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-width: 560px;
}
.backup-restore-dragger :deep(.ant-upload) {
  background: var(--input-bg) !important;
  border-color: var(--border) !important;
}
.backup-restore-dragger :deep(.ant-upload:hover) {
  border-color: rgba(129, 140, 248, 0.45) !important;
}

.settings-card {
  max-width: 480px;
  border-radius: var(--radius-lg) !important;
  box-shadow: var(--shadow-card) !important;
  border-color: var(--border) !important;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: var(--trans);
}
.settings-no-select,
.settings-no-select :deep(.ant-card-head-title),
.settings-no-select :deep(.ant-descriptions-item-label),
.settings-no-select :deep(.ant-descriptions-item-content),
.settings-no-select :deep(.lock-text) {
  user-select: none;
  -webkit-user-select: none;
}
.notify-tg-card :deep(.ant-card-head-title),
.pwd-change-card :deep(.ant-card-head-title) {
  user-select: none;
  -webkit-user-select: none;
}
.settings-card-wide {
  max-width: 560px;
  border-radius: var(--radius-lg) !important;
  box-shadow: var(--shadow-card) !important;
  border-color: var(--border) !important;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: var(--trans);
}
.notify-settings-card {
  max-width: min(1120px, 100%);
  width: 100%;
}
.notify-section-segment {
  margin-bottom: 18px;
  max-width: 100%;
  overflow-x: auto;
}
.notify-section-panel {
  max-width: 100%;
}
.announcement-push-panel {
  width: 100%;
}
.announcement-config-form {
  max-width: 860px;
}
.announcement-config-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: start;
}
.announcement-event-form-item {
  min-width: 0;
}
.announcement-enable-form-item {
  min-width: 104px;
}
.announcement-enable-form-item :deep(.ant-form-item-control-input-content) {
  display: flex;
  justify-content: flex-end;
}
.tenant-picker-shell {
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  padding: 14px;
  margin-bottom: 16px;
  background: var(--input-bg, rgba(255, 255, 255, 0.03));
}
.tenant-picker-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.tenant-picker-title {
  font-weight: 600;
  color: var(--text-main);
}
.tenant-picker-sub,
.announcement-meta {
  font-size: 12px;
  color: var(--text-sub);
}
.tenant-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.announcement-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}
.announcement-filter-select {
  width: 132px;
}
.announcement-date-range {
  width: 300px;
}
.announcement-event-filter {
  min-width: 260px;
}
.announcement-toolbar :deep(.ant-input-search) {
  max-width: 360px;
  min-width: 220px;
}
.announcement-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.announcement-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  background: var(--input-bg, rgba(255, 255, 255, 0.03));
}
.announcement-item-main {
  min-width: 0;
  flex: 1;
}
.announcement-summary {
  margin-top: 7px;
  color: var(--text-main);
  font-weight: 600;
  line-height: 1.45;
  word-break: break-word;
}
.announcement-window {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-sub);
  white-space: pre-line;
}
.announcement-type-origin {
  align-self: center;
  color: var(--text-sub);
  font-size: 12px;
}
.announcement-pagination {
  margin-top: 14px;
  text-align: right;
}
.tenant-picker-modal :deep(.ant-modal-body) {
  max-height: calc(100vh - 132px);
  overflow: hidden;
}
.tenant-picker-modal :deep(.ant-modal-content) {
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
}
.tenant-picker-modal :deep(.ant-modal-header) {
  flex: 0 0 auto;
}
.tenant-picker-modal-body {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.tenant-picker-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.tenant-picker-search {
  width: min(420px, 100%);
  flex: 0 1 420px;
}
.tenant-picker-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex: 1 0 auto;
}
.tenant-mobile-group-select {
  width: 100%;
  margin-top: 10px;
}
.tenant-mobile-group-option {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
  max-width: 100%;
}
.tenant-mobile-group-option--child {
  padding-left: 1.5em;
}
.tenant-mobile-group-option small {
  font-size: 11px;
  line-height: 1;
  color: var(--text-sub);
}
.tenant-picker-grid {
  display: grid;
  grid-template-columns: minmax(210px, 260px) minmax(0, 1fr) minmax(240px, 300px);
  gap: 14px;
  margin-top: 12px;
  min-height: 0;
  height: min(62vh, 600px);
}
.tenant-picker-block {
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  padding: 12px;
  min-width: 0;
  min-height: 0;
  background: var(--bg-card);
  display: flex;
  flex-direction: column;
}
.tenant-group-list,
.tenant-list,
.tenant-selected-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
  min-height: 0;
}
.tenant-group-list {
  overflow: auto;
  padding-right: 2px;
}
.tenant-list,
.tenant-selected-list {
  overflow: visible;
}
.tenant-group-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  font-size: 13px;
  color: var(--text-main);
}
.tenant-group-button {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-md, 8px);
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.tenant-group-button:hover,
.tenant-group-button--active {
  border-color: rgba(129, 140, 248, 0.45);
  background: rgba(129, 140, 248, 0.12);
}
.tenant-group-button--child {
  padding-left: 22px;
}
.tenant-group-row span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tenant-group-row small,
.tenant-name small {
  color: var(--text-sub);
}
.tenant-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 48px;
  gap: 8px;
  align-items: center;
  min-height: 56px;
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
}
.tenant-row:last-child {
  border-bottom: 0;
}
.tenant-row-head {
  color: var(--text-sub);
  font-size: 12px;
  padding-top: 0;
}
.tenant-name {
  min-width: 0;
}
.tenant-name strong,
.tenant-name small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tenant-selected-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 56px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  background: var(--input-bg, rgba(255, 255, 255, 0.03));
}
.tenant-picker-pagination {
  margin-top: auto;
  padding-top: 10px;
  text-align: right;
}
.announcement-detail h3 {
  margin-top: 0;
  color: var(--text-main);
}
.announcement-live-detail {
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  color: var(--text-main);
  background: var(--input-bg, rgba(255, 255, 255, 0.03));
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 280px;
  overflow: auto;
}
.tenant-impact-list {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tenant-impact-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
}
.settings-card-wide--cf {
  max-width: min(960px, 100%);
}
.settings-card-oci-proxy {
  max-width: min(880px, 100%);
  width: 100%;
}
.settings-card-oci-proxy :deep(.oci-proxy-type-select) {
  max-width: 420px;
  width: 100%;
}
.settings-card-oci-proxy :deep(.oci-proxy-url-input) {
  max-width: 100%;
  width: 100%;
}

.settings-card-audit {
  max-width: min(1680px, 100%);
  width: 100%;
  border-radius: var(--radius-lg) !important;
  box-shadow: var(--shadow-card) !important;
  border-color: var(--border) !important;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: var(--trans);
}
.settings-card-audit :deep(.ant-table-wrapper) {
  width: 100%;
}
.settings-card-audit :deep(.ant-spin-nested-loading),
.settings-card-audit :deep(.ant-spin-container) {
  width: 100%;
}
.settings-card-audit :deep(.ant-table-row-expand-icon-cell) {
  text-align: center;
  vertical-align: middle;
}
.settings-card-audit :deep(.audit-copy-cell) {
  position: relative;
  min-width: 0;
  width: 100%;
  min-height: 22px;
}
.settings-card-audit :deep(.audit-copy-text) {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding-right: 2px;
}
.settings-card-audit :deep(.audit-copy-cell:not(.audit-copy-cell--tap) .audit-copy-text) {
  padding-right: 44px;
}
.settings-card-audit :deep(.audit-copy-btn) {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  padding: 0 4px !important;
  height: auto !important;
  line-height: 1.2 !important;
  margin: 0 !important;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s;
  background: linear-gradient(
    90deg,
    rgba(15, 23, 42, 0) 0%,
    rgba(15, 23, 42, 0.55) 28%,
    rgba(15, 23, 42, 0.92) 45%
  );
  border-radius: 4px;
  z-index: 1;
}
.settings-card-audit :deep(.audit-copy-cell:hover .audit-copy-btn) {
  opacity: 1;
  pointer-events: auto;
}
.settings-card-audit :deep(.audit-copy-cell--tap .audit-copy-text) {
  padding-right: 0;
}
.settings-card-audit :deep(.audit-copy-cell--tap) {
  cursor: pointer;
  user-select: none;
  -webkit-tap-highlight-color: transparent;
}
.settings-card-audit :deep(.audit-row-expand-btn) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  margin: 0;
  padding: 0;
  border: 1px solid var(--border, rgba(148, 163, 184, 0.35));
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.04);
  color: rgba(226, 232, 240, 0.92);
  cursor: pointer;
  line-height: 0;
  transition: border-color 0.2s, color 0.2s, background 0.2s;
}
.settings-card-audit :deep(.audit-row-expand-btn:hover),
.settings-card-audit :deep(.audit-row-expand-btn:focus-visible) {
  border-color: #818cf8;
  color: #c7d2fe;
  outline: none;
}
.settings-card-audit :deep(.audit-row-expand-btn .anticon) {
  font-size: 11px;
  transition: transform 0.2s ease;
}
.settings-card-audit :deep(.audit-row-expand-btn-expanded .anticon) {
  transform: rotate(90deg);
}
.settings-card-audit :deep(.audit-expand-spacer) {
  display: inline-block;
  width: 22px;
  height: 22px;
  visibility: hidden;
}
.audit-expanded-inner {
  padding: 8px 12px 16px 8px;
  max-width: 100%;
}
.audit-detail-block {
  margin-bottom: 16px;
}
.audit-detail-block:last-child {
  margin-bottom: 0;
}
.audit-detail-h {
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--text-main, #e2e8f0);
  font-size: 13px;
}
.audit-detail-val {
  word-break: break-all;
  white-space: pre-wrap;
  font-size: 12px;
}
.settings-card-ban {
  max-width: min(1000px, 100%);
  width: 100%;
}
.ban-form-compact {
  max-width: 560px;
}
.audit-lock-panel,
.banlist-lock-panel {
  max-width: 100%;
}
.ban-col-title {
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--text-main);
}
.ban-empty {
  color: var(--text-sub);
  font-size: 13px;
  padding: 8px 0;
}
.ban-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  margin-bottom: 8px;
  background: var(--input-bg, rgba(255, 255, 255, 0.02));
}
.ban-row-text {
  word-break: break-all;
  font-size: 13px;
  flex: 1;
  min-width: 0;
}

.lock-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 32px 24px;
  max-width: 280px;
  margin: 0 auto;
}

.lock-icon {
  font-size: 36px;
  color: #818cf8;
  margin-bottom: 12px;
}

.lock-text {
  color: #94a3b8;
  margin-bottom: 20px;
  text-align: center;
  font-size: 14px;
}

.cf-settings-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 380px);
  gap: 20px;
  align-items: start;
}
.cf-settings-layout__help {
  min-width: 0;
}
.cf-settings-help-alert {
  margin-bottom: 0;
  height: 100%;
}
.cf-settings-help-alert :deep(.ant-alert) {
  height: 100%;
}
.cf-settings-layout__form {
  min-width: 0;
  padding: 16px 18px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 12px);
  background: var(--bg-card);
}
.cf-settings-form :deep(.ant-form-item:last-of-type) {
  margin-bottom: 16px;
}

.cf-settings-help {
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-sub);
}
.cf-settings-help p {
  margin: 0 0 8px;
}
.cf-settings-help ol,
.cf-settings-help ul {
  margin: 0 0 8px;
  padding-left: 1.25em;
}
.cf-settings-help li {
  margin-bottom: 4px;
}
.cf-settings-help code {
  font-size: 12px;
  padding: 0 4px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.06);
}
.cf-settings-help-note {
  font-size: 12px;
  opacity: 0.9;
}

@media (max-width: 768px) {
  .settings-card,
  .settings-card-wide,
  .settings-card-audit,
  .backup-restore-stack {
    max-width: 100% !important;
  }
  .notify-section-segment {
    width: 100%;
  }
  .announcement-toolbar,
  .announcement-item,
  .tenant-picker-head {
    flex-direction: column;
    align-items: stretch;
  }
  .announcement-config-head {
    grid-template-columns: 1fr;
    gap: 0;
  }
  .announcement-enable-form-item :deep(.ant-form-item-control-input-content) {
    justify-content: flex-start;
  }
  .announcement-filter-select,
  .announcement-date-range,
  .announcement-event-filter {
    width: 100%;
    min-width: 0;
  }
  .announcement-toolbar :deep(.ant-input-search) {
    max-width: 100%;
    min-width: 0;
  }
  .tenant-picker-grid {
    grid-template-columns: 1fr;
    height: auto;
    max-height: none;
    overflow: visible;
  }
  .tenant-picker-toolbar {
    display: contents;
  }
  .tenant-picker-search {
    order: 1;
    width: 100%;
    flex-basis: auto;
    margin-bottom: 8px;
  }
  .tenant-mobile-group-select {
    order: 2;
    margin-top: 0;
    margin-bottom: 8px;
  }
  .tenant-picker-actions {
    order: 3;
    justify-content: flex-start;
    flex: 0 0 auto;
    margin-bottom: 10px;
  }
  .tenant-picker-grid {
    order: 4;
  }
  .tenant-picker-modal :deep(.ant-modal) {
    max-width: 100%;
    margin: 8px auto;
  }
  .tenant-picker-modal :deep(.ant-modal-content) {
    max-height: calc(100vh - 16px);
  }
  .tenant-picker-modal :deep(.ant-modal-body) {
    max-height: calc(100vh - 98px);
    overflow: auto;
  }
  .tenant-picker-block {
    min-height: auto;
  }
  .tenant-group-list {
    max-height: 176px;
  }
  .tenant-list,
  .tenant-selected-list {
    overflow: visible;
  }
  .tenant-row,
  .tenant-selected-row {
    min-height: 58px;
  }
  .tenant-picker-pagination {
    margin-top: 8px;
  }
  .cf-settings-layout {
    grid-template-columns: 1fr;
  }
  .cf-settings-layout__form {
    padding: 14px 12px;
  }
}
</style>
