<template>
  <div>
    <a-modal
      v-model:open="updateOverlayVisible"
      :width="isMobile ? 'calc(100vw - 24px)' : 560"
      :footer="null"
      :closable="false"
      :mask-closable="false"
      :mask-style="upgradeMaskStyle"
      :keyboard="false"
      centered
      wrap-class-name="upgrade-flow-modal-wrap"
      class="upgrade-flow-modal"
    >
      <div class="upgrade-flow-dialog" :class="{ 'upgrade-flow-dialog--done': updateOverlayMode !== 'running' }">
        <UpgradeLoader v-if="updateOverlayMode === 'running'" />
        <div v-else class="upgrade-flow-result" :class="'upgrade-flow-result--' + updateOverlayMode">
          <i :class="updateOverlayMode === 'success' ? 'ri-checkbox-circle-line' : 'ri-error-warning-line'"></i>
          <h3>{{ updateOverlayTitle }}</h3>
          <p>{{ updateOverlaySub }}</p>
          <a-space v-if="updateOverlayMode !== 'success'" wrap>
            <a-button type="primary" @click="refreshPage">刷新页面</a-button>
            <a-button @click="closeUpdateOverlay">关闭</a-button>
          </a-space>
        </div>
        <div v-if="updateOverlayMode === 'running'" class="upgrade-flow-status">
          <strong>{{ updateOverlayTitle }}</strong>
          <span>{{ updateOverlaySub }}</span>
        </div>
      </div>
    </a-modal>

    <a-modal
      v-model:open="taskPublicKeyModalVisible"
      title="我的公钥"
      :width="isMobile ? 'calc(100vw - 24px)' : 620"
      ok-text="保存"
      cancel-text="关闭"
      :mask-closable="false"
      centered
      @ok="saveTaskPublicKeyDraft"
    >
      <a-textarea
        v-model:value="taskPublicKeyDraft"
        placeholder="粘贴 OpenSSH 公钥内容"
        :auto-size="{ minRows: 5, maxRows: 10 }"
        class="task-public-key-modal-textarea"
      />
      <div class="task-public-key-modal-actions">
        <a-button size="small" :disabled="!taskPublicKeyDraft" @click="copyTaskPublicKeyDraft">复制</a-button>
        <a-button size="small" danger :disabled="!taskPublicKeyDraft" @click="taskPublicKeyDraft = ''">清空</a-button>
      </div>
    </a-modal>

    <SystemSettingsTabsFrame
      :primary-tabs="settingsPrimaryTabs"
      :secondary-tabs="activeSettingsSecondaryTabs"
      :active-primary="activeSettingsPrimary"
      :active-title="activeSettingsContentTitle"
      :active-desc="activeSettingsContentDesc"
      :active-path="activeSettingsPath"
      :secondary-locked="settingsSecondaryLocked"
      :is-secondary-active="isSettingsSecondaryKeyActive"
      @select-primary="handleSettingsPrimarySelect"
      @select-secondary="handleSettingsSecondarySelect"
    >

        <template v-if="activeSettingsPrimary === 'security' && !securityTgVerified">
          <a-card title="Telegram 验证" class="settings-card pwd-change-card security-gate-card">
            <template #extra><a-tag color="warning">待验证</a-tag></template>
            <div class="lock-panel settings-no-select">
              <i class="ri-shield-check-line lock-icon"></i>
              <p class="lock-text">查看和修改安全设置前，请完成 Telegram 验证</p>
              <a-space direction="vertical" style="width: 100%">
                <a-button block @click="sendSecuritySettingsCode" :loading="securityCodeSending" :disabled="securityCodeCountdown > 0">
                  {{ securityCodeCountdown > 0 ? securityCodeCountdown + ' 秒后可重新发送' : '发送验证码到 Telegram' }}
                </a-button>
                <a-input
                  v-model:value="securityUnlockCode"
                  placeholder="输入 6 位验证码"
                  maxlength="6"
                  allow-clear
                  @pressEnter="unlockSecuritySettingsPanel"
                />
                <a-button
                  type="primary"
                  block
                  @click="unlockSecuritySettingsPanel"
                  :loading="securityUnlocking"
                  :disabled="!securityUnlockCode || securityUnlocking"
                >
                  验证并进入安全设置
                </a-button>
              </a-space>
            </div>
          </a-card>
        </template>

        <template v-else-if="activeTab === 'security'">

        <a-card v-if="securitySection === 'password'" title="修改登录密码" class="settings-card pwd-change-card">
          <template #extra><a-tag color="success">已通过安全验证</a-tag></template>
          <a-form :model="pwdForm" layout="vertical">
            <a-form-item label="原密码" required>
              <a-input-password v-model:value="pwdForm.oldPassword" placeholder="输入当前密码" :maxlength="256" />
            </a-form-item>
            <a-form-item label="新密码" required>
              <a-input-password v-model:value="pwdForm.newPassword" placeholder="至少8位" :maxlength="256" />
            </a-form-item>
            <a-form-item label="确认新密码" required>
              <a-input-password v-model:value="pwdForm.confirmPassword" placeholder="再次输入新密码" :maxlength="256" />
            </a-form-item>
            <a-button type="primary" @click="handleChangePassword" :loading="pwdLoading">修改密码</a-button>
          </a-form>
        </a-card>

        <a-card v-else-if="securitySection === 'credential'" title="开机凭据" class="settings-card task-credential-card">
          <template #extra><a-tag>已配置 {{ taskCredentialConfiguredCount }} 项</a-tag></template>
          <div class="task-credential-panel">
            <section class="task-credential-section">
              <div class="task-credential-section-head">
                <span class="task-credential-title"><i class="ri-lock-password-line"></i>我的密码</span>
                <a-tag v-if="taskCredentialForm.rootPassword" color="blue">已设置</a-tag>
                <a-tag v-else>未设置</a-tag>
              </div>
              <a-input-password
                v-model:value="taskCredentialForm.rootPassword"
                placeholder="保存后，快捷开机可一键填入 Root 密码"
                autocomplete="new-password"
                allow-clear
              />
            </section>

            <section class="task-credential-section">
              <div class="task-credential-section-head">
                <span class="task-credential-title"><i class="ri-key-2-line"></i>我的公钥</span>
                <a-tag v-if="taskCredentialForm.sshPublicKey" color="green">已设置</a-tag>
                <a-tag v-else>未设置</a-tag>
              </div>
              <a-upload-dragger
                accept=".pub"
                :multiple="false"
                :show-upload-list="false"
                :before-upload="beforeTaskPublicKeyUpload"
                class="task-public-key-upload"
              >
                <p class="ant-upload-drag-icon"><InboxOutlined /></p>
                <p class="ant-upload-text">拖入 .pub 公钥文件，或点击选择</p>
              </a-upload-dragger>

              <div class="task-public-key-summary" :class="{ 'task-public-key-summary--empty': !taskCredentialForm.sshPublicKey }">
                <div class="task-public-key-summary-main">
                  <span class="task-public-key-dot"></span>
                  <div class="task-public-key-summary-text">
                    <strong>{{ taskCredentialForm.sshPublicKey ? '公钥已隐藏' : '未设置公钥' }}</strong>
                    <span>{{ taskCredentialForm.sshPublicKey ? maskedTaskPublicKey : '可拖入 .pub 文件，或手动粘贴保存' }}</span>
                  </div>
                </div>
                <a-space size="small" wrap>
                  <a-button size="small" @click="openTaskPublicKeyModal">{{ taskCredentialForm.sshPublicKey ? '查看/编辑' : '粘贴' }}</a-button>
                  <a-button v-if="taskCredentialForm.sshPublicKey" size="small" @click="clearTaskPublicKey">移除</a-button>
                </a-space>
              </div>
            </section>

            <div class="task-credential-actions">
              <span class="task-credential-status">已配置 {{ taskCredentialConfiguredCount }} 项</span>
              <a-space wrap>
                <a-button @click="reloadTaskCredentialConfig" :loading="taskCredentialLoading">重新读取</a-button>
                <a-button danger @click="clearTaskCredentialConfig">清空</a-button>
                <a-button type="primary" :loading="taskCredentialSaving" @click="saveTaskCredentialConfig">保存</a-button>
              </a-space>
            </div>
          </div>
        </a-card>

        <a-card v-else title="登录安全说明" class="settings-card settings-no-select">
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item label="Token 有效期">24 小时</a-descriptions-item>
            <a-descriptions-item label="关闭浏览器">Token 保持有效，直到过期</a-descriptions-item>
            <a-descriptions-item label="Token 存储">浏览器 localStorage</a-descriptions-item>
          </a-descriptions>
          <div style="margin-top: 12px">
            <a-button danger @click="handleForceLogout">立即退出登录</a-button>
          </div>
        </a-card>
        </template>

        <template v-else-if="activeSettingsPrimary === 'notify' && !notifyPwdVerified">
          <a-card title="登录密码验证" class="settings-card pwd-change-card security-gate-card">
            <template #extra><a-tag color="warning">待验证</a-tag></template>
            <div class="lock-panel settings-no-select">
              <i class="ri-lock-2-line lock-icon"></i>
              <p class="lock-text">请输入登录密码进入通知设置</p>
              <a-space direction="vertical" style="width: 100%">
                <a-input-password v-model:value="notifyPwd" placeholder="输入登录密码" @pressEnter="verifyNotifyPwd" />
                <a-button type="primary" block @click="verifyNotifyPwd" :disabled="!notifyPwd">验证并进入通知设置</a-button>
              </a-space>
            </div>
          </a-card>
        </template>

        <template v-else-if="activeTab === 'notify'">
        <a-card title="消息通知" class="settings-card-wide notify-tg-card notify-settings-card">
          <div>
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

            <AnnouncementPushPanel
              v-else-if="notifySection === 'announcement'"
              :is-mobile="isMobile"
              @announcement-enabled="ensureAnnouncementNotifyType"
            />

            <a-descriptions v-else :column="1" bordered size="small" class="notify-section-panel settings-no-select">
              <a-descriptions-item label="登录通知">登录成功/失败时发送，包含IP地址、账号、时间</a-descriptions-item>
              <a-descriptions-item label="创建任务">创建开机任务时通知</a-descriptions-item>
              <a-descriptions-item label="任务结果">开机成功或认证失败时通知，包含实例详情</a-descriptions-item>
              <a-descriptions-item label="每日播报">在所设东八区时刻自动发送（默认 09:00），包含租户总数、失效租户、运行中任务</a-descriptions-item>
              <a-descriptions-item label="云公告推送">后台按租户范围扫描 OCI 云公告，同一公告聚合后推送到 Telegram</a-descriptions-item>
            </a-descriptions>
          </div>
        </a-card>

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
        </template>

        <template v-else-if="activeTab === 'proxy'">
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
        </template>

        <template v-else-if="activeTab === 'alidns'">
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
        </template>

        <template v-else-if="activeTab === 'cloudflare'">
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
        </template>

        <template v-else-if="activeTab === 'audit'">
        <a-card class="settings-card-audit">
          <template #title>登录记录（保留 7 天，超时自动清理）</template>
          <template #extra>
            <a-button type="link" size="small" :loading="auditLoading" @click="loadAudit">
              <template #icon><ReloadOutlined /></template>
              刷新
            </a-button>
          </template>
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
        </a-card>
        </template>

        <template v-else-if="activeTab === 'banlist'">
        <a-card class="settings-card-wide settings-card-ban">
          <template #title>封禁列表</template>
          <template #extra><a-tag color="success">已通过安全验证</a-tag></template>
            <a-space direction="vertical" size="middle" style="width: 100%">
              <a-alert
                type="warning"
                show-icon
                message="已通过安全验证。封禁或解除后，对应 IP 或设备在下一次请求起将无法再访问面板接口（含当前已登录会话）。"
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
        </a-card>
        </template>

        <template v-else-if="activeTab === 'update'">
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
              <a-button @click="checkUpdate" :loading="updateChecking" :disabled="updatePerforming">检查更新</a-button>
              <a-popconfirm v-model:open="updateConfirmOpen" title="确定执行更新？更新过程中服务将短暂重启。" @confirm="performUpdate" ok-text="确定更新" cancel-text="取消">
                <a-button type="primary" :loading="updatePerforming" :disabled="updatePerforming || (!updateInfo?.hasUpdate && !updateInfo?.downloadFallbackAvailable && !updateForce)">
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
        </template>
        <template v-else-if="activeTab === 'backup'">
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
          <div style="margin-top: 8px">
            <a-button type="link" size="small" style="padding: 0; height: auto" @click="showNotifyTgLostHint">
              Telegram丢失
            </a-button>
          </div>
        </a-modal>
        </template>
    </SystemSettingsTabsFrame>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'SystemSettings' })
import { computed, h, reactive, ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { CaretRightOutlined, InboxOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import type { UploadFile } from 'ant-design-vue'
import { useUserStore } from '../stores/user'
import { useThemeStore } from '../stores/theme'
import { getTaskCredential, saveTaskCredential, sendSecuritySettingsVerifyCode, sendVerifyCode, unlockSecuritySettings } from '../api/system'
import UpgradeLoader from '../components/UpgradeLoader.vue'
import SystemSettingsTabsFrame from '../components/settings/SystemSettingsTabsFrame.vue'
import request from '../utils/request'
import { defineAppAsyncComponent } from '../utils/asyncComponent'
import { getCfAccountConfig, saveCfAccountConfig, testCfAccountConfig } from '../api/cloudflare'
import { getAliDNSAccountConfig, saveAliDNSAccountConfig, testAliDNSAccountConfig } from '../api/alidns'

const userStore = useUserStore()
const themeStore = useThemeStore()

const AnnouncementPushPanel = defineAppAsyncComponent(() => import('../components/settings/AnnouncementPushPanel.vue'), { loading: 'none' })

const router = useRouter()
const activeTab = ref('security')
const activeSettingsPrimary = ref<'security' | 'notify' | 'integration' | 'system'>('security')
const securitySection = ref<'password' | 'credential' | 'guide'>('password')
type SettingsSecondaryKey =
  | 'password'
  | 'credential'
  | 'guide'
  | 'audit'
  | 'banlist'
  | 'telegram'
  | 'daily'
  | 'announcement'
  | 'notifyGuide'
  | 'proxy'
  | 'alidns'
  | 'cloudflare'
  | 'update'
  | 'backup'
type SettingsSecondaryTab = { key: SettingsSecondaryKey; label: string; title: string; desc: string }

const settingsPrimaryTabs = [
  { key: 'security', label: '安全', count: 5 },
  { key: 'notify', label: '通知', count: 4 },
  { key: 'integration', label: '集成', count: 3 },
  { key: 'system', label: '系统', count: 2 },
] as const
const settingsSecondaryTabs: Record<typeof settingsPrimaryTabs[number]['key'], SettingsSecondaryTab[]> = {
  security: [
    { key: 'password', label: '登录密码', title: '登录密码', desc: '' },
    { key: 'credential', label: '开机凭据', title: '开机凭据', desc: '保存我的密码和我的公钥，供快捷开机一键使用。' },
    { key: 'audit', label: '登录统计', title: '登录统计', desc: '' },
    { key: 'banlist', label: '封禁列表', title: '封禁列表', desc: '管理被封禁的 IP 和设备，支持新增、刷新和解除。' },
    { key: 'guide', label: '安全说明', title: '安全说明', desc: '查看 Token 有效期、关闭浏览器后的登录状态和退出登录入口。' },
  ],
  notify: [
    { key: 'telegram', label: 'Telegram 基础', title: 'Telegram 基础', desc: '配置 Bot Token、Chat ID 和通知类型。' },
    { key: 'daily', label: '每日播报', title: '每日播报', desc: '设置每日播报开关和东八区播报时间。' },
    { key: 'announcement', label: '云公告推送', title: '云公告推送', desc: '配置云公告推送、收件箱、推送历史和扫描状态。' },
    { key: 'notifyGuide', label: '通知说明', title: '通知说明', desc: '查看各类 Telegram 通知的触发规则。' },
  ],
  integration: [
    { key: 'proxy', label: 'OCI 代理', title: 'OCI 代理', desc: '配置 OCI API 代理类型、主机、端口、认证和完整代理 URL。' },
    { key: 'alidns', label: '阿里云DNS', title: '阿里云DNS', desc: '配置阿里云 DNS 凭据并测试连接。' },
    { key: 'cloudflare', label: 'Cloudflare', title: 'Cloudflare', desc: '配置 Cloudflare 全局凭据并测试连接。' },
  ],
  system: [
    { key: 'update', label: '系统更新', title: '系统更新', desc: '' },
    { key: 'backup', label: '备份恢复', title: '备份恢复', desc: '' },
  ],
}
const activeSettingsSecondaryTabs = computed(() => settingsSecondaryTabs[activeSettingsPrimary.value])
const settingsSecondaryLocked = computed(() =>
  (activeSettingsPrimary.value === 'security' && !securityTgVerified.value)
  || (activeSettingsPrimary.value === 'notify' && !notifyPwdVerified.value),
)
const activeSettingsSecondaryMeta = computed(() => {
  if (activeSettingsPrimary.value === 'security' && !securityTgVerified.value) {
    return { label: '', title: '安全验证', desc: '' }
  }
  if (activeSettingsPrimary.value === 'notify' && !notifyPwdVerified.value) {
    return { label: '', title: '通知验证', desc: '' }
  }
  return activeSettingsSecondaryTabs.value.find((tab) => isSettingsSecondaryActive(tab.key)) || activeSettingsSecondaryTabs.value[0]
})
const activeSettingsContentTitle = computed(() => activeSettingsSecondaryMeta.value.title)
const activeSettingsContentDesc = computed(() => activeSettingsSecondaryMeta.value.desc)
const activeSettingsPath = computed(() => {
  if (settingsSecondaryLocked.value) return ''
  const primary = settingsPrimaryTabs.find((tab) => tab.key === activeSettingsPrimary.value)?.label || ''
  return `${primary} / ${activeSettingsSecondaryMeta.value.label}`
})
const SECURITY_SETTINGS_SESSION_HDR = 'X-Oci-Security-Settings-Session'
const securityTgVerified = ref(false)
const securityUnlockCode = ref('')
const securityCodeSending = ref(false)
const securityUnlocking = ref(false)
const securityCodeCountdown = ref(0)
const securitySession = ref('')
let securityCountdownTimer: ReturnType<typeof setInterval> | null = null
const pwdLoading = ref(false)
const saveLoading = ref(false)
const testLoading = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const taskCredentialLoading = ref(false)
const taskCredentialSaving = ref(false)
const taskCredentialForm = reactive({ rootPassword: '', sshPublicKey: '' })
const taskPublicKeyModalVisible = ref(false)
const taskPublicKeyDraft = ref('')
const TASK_PUBLIC_KEY_MAX_SIZE = 16 * 1024
const tgConfig = reactive({ botToken: '', chatId: '', notifyTypes: [] as string[], dailyReportTime: '09:00' })
/** 与 a-time-picker（value-format=HH:mm）一致 */
const dailyReportTimePicked = ref<string | null>('09:00')

const tgConfigured = ref(false)
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

function isSettingsSecondaryActive(key: SettingsSecondaryKey): boolean {
  if (key === 'password' || key === 'credential' || key === 'guide') {
    return activeTab.value === 'security' && securitySection.value === key
  }
  if (key === 'telegram' || key === 'daily' || key === 'announcement') {
    return activeTab.value === 'notify' && notifySection.value === key
  }
  if (key === 'notifyGuide') {
    return activeTab.value === 'notify' && notifySection.value === 'guide'
  }
  return activeTab.value === key
}

function isSettingsSecondaryKeyActive(key: string): boolean {
  return isSettingsSecondaryActive(key as SettingsSecondaryKey)
}

function selectSettingsPrimary(key: typeof settingsPrimaryTabs[number]['key']) {
  activeSettingsPrimary.value = key
  if (key === 'security') {
    if (securityTgVerified.value) {
      selectSettingsSecondary('password')
    } else {
      activeTab.value = 'security'
      securitySection.value = 'password'
    }
    return
  }
  if (key === 'notify') {
    activeTab.value = 'notify'
    notifySection.value = 'telegram'
    if (notifyPwdVerified.value) selectSettingsSecondary('telegram')
    return
  }
  selectSettingsSecondary(settingsSecondaryTabs[key][0].key)
}

function handleSettingsPrimarySelect(key: string) {
  if (key === 'security' || key === 'notify' || key === 'integration' || key === 'system') {
    selectSettingsPrimary(key)
  }
}

function selectSettingsSecondary(key: SettingsSecondaryKey) {
  if (key === 'password' || key === 'credential' || key === 'guide') {
    activeSettingsPrimary.value = 'security'
    activeTab.value = 'security'
    securitySection.value = key
    return
  }
  if (key === 'audit' || key === 'banlist') {
    activeSettingsPrimary.value = 'security'
    activeTab.value = key
    return
  }
  if (key === 'telegram' || key === 'daily' || key === 'announcement') {
    activeSettingsPrimary.value = 'notify'
    activeTab.value = 'notify'
    notifySection.value = key
    return
  }
  if (key === 'notifyGuide') {
    activeSettingsPrimary.value = 'notify'
    activeTab.value = 'notify'
    notifySection.value = 'guide'
    return
  }
  if (key === 'proxy' || key === 'alidns' || key === 'cloudflare') {
    activeSettingsPrimary.value = 'integration'
    activeTab.value = key
    return
  }
  activeSettingsPrimary.value = 'system'
  activeTab.value = key
}

function handleSettingsSecondarySelect(key: string) {
  selectSettingsSecondary(key as SettingsSecondaryKey)
}

const taskCredentialConfiguredCount = computed(() =>
  (taskCredentialForm.rootPassword ? 1 : 0) + (taskCredentialForm.sshPublicKey ? 1 : 0),
)

const maskedTaskPublicKey = computed(() => maskOpenSshPublicKey(taskCredentialForm.sshPublicKey))

watch(activeTab, (k) => {
  if (k === 'audit' && securityTgVerified.value) {
    loadAudit()
  }
  if (k === 'banlist' && securityTgVerified.value) {
    loadBanlist()
  }
  if (k === 'cloudflare') {
    loadCfConfig()
  }
  if (k === 'alidns') {
    loadAlidnsConfig()
  }
})

onMounted(async () => {
  if (typeof window !== 'undefined') {
    window.addEventListener('resize', checkMobile)
  }
  loadNotifyConfig()
  reloadTaskCredentialConfig()
  loadOciProxy()
  loadCfConfig()
  loadAlidnsConfig()
  try {
    const res = await request.get('/sys/tgStatus')
    tgConfigured.value = res.data?.configured === true
  } catch {
    /* 状态读取失败不阻断设置页渲染 */
  }
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
  } catch {
    /* 通知配置读取失败时沿用默认表单值 */
  }
}

function toggleNotifyType(type: string, checked: boolean) {
  const set = new Set(tgConfig.notifyTypes)
  if (checked) set.add(type)
  else set.delete(type)
  tgConfig.notifyTypes = Array.from(set)
}

function ensureAnnouncementNotifyType() {
  if (!tgConfig.notifyTypes.includes('announcement')) {
    tgConfig.notifyTypes.push('announcement')
  }
}

async function sendSecuritySettingsCode() {
  securityCodeSending.value = true
  try {
    await sendSecuritySettingsVerifyCode()
    message.success('验证码已发送到 Telegram')
    securityCodeCountdown.value = 60
    if (securityCountdownTimer) clearInterval(securityCountdownTimer)
    securityCountdownTimer = setInterval(() => {
      securityCodeCountdown.value--
      if (securityCodeCountdown.value <= 0 && securityCountdownTimer) {
        clearInterval(securityCountdownTimer)
        securityCountdownTimer = null
      }
    }, 1000)
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    securityCodeSending.value = false
  }
}

async function unlockSecuritySettingsPanel() {
  if (securityUnlocking.value) return
  const c = securityUnlockCode.value?.trim()
  if (!c || c.length !== 6) {
    message.warning('请输入 6 位验证码')
    return
  }
  securityUnlocking.value = true
  try {
    const res = await unlockSecuritySettings(c)
    securitySession.value = res.data?.securitySession || ''
    auditSession.value = res.data?.loginAuditSession || ''
    banlistSession.value = res.data?.banlistSession || ''
    if (!securitySession.value || !auditSession.value || !banlistSession.value) {
      message.error('未返回安全会话，请重试')
      return
    }
    securityTgVerified.value = true
    securityUnlockCode.value = ''
    message.success('验证通过')
    selectSettingsSecondary('password')
  } catch {
    /* 全局已提示 */
  } finally {
    securityUnlocking.value = false
  }
}

function handleSecuritySessionLost(e: unknown): boolean {
  const msg = e instanceof Error ? e.message : String(e ?? '')
  if (!msg.includes('安全设置') && !msg.includes('Telegram 验证') && !msg.includes('登录统计') && !msg.includes('封禁列表')) {
    return false
  }
  securityTgVerified.value = false
  securitySession.value = ''
  auditSession.value = ''
  banlistSession.value = ''
  securityUnlockCode.value = ''
  activeSettingsPrimary.value = 'security'
  activeTab.value = 'security'
  securitySection.value = 'password'
  auditExpandedKeys.value = []
  return true
}

async function verifyNotifyPwd() {
  if (!notifyPwd.value) { message.warning('请输入密码'); return }
  try {
    await request.post('/auth/verifyPassword', { password: notifyPwd.value }, { skipBusinessMessage: true } as object)
    notifyVerifiedPwd.value = notifyPwd.value
    notifyPwd.value = ''
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
  if (pwdForm.newPassword.length < 8) {
    message.warning('新密码不能少于 8 位')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirmPassword) {
    message.warning('两次输入的密码不一致')
    return
  }
  pwdLoading.value = true
  try {
    const headers = securitySession.value ? { [SECURITY_SETTINGS_SESSION_HDR]: securitySession.value } : undefined
    const config = { ...(headers ? { headers } : {}), skipBusinessMessage: true } as object
    const res = await request.post('/auth/changePassword', {
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword,
    }, config)
    if (res.data?.token) {
      userStore.setLoginSession(res.data.token, res.data.account)
    }
    message.success('密码修改成功')
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
  } catch (e: any) {
    if (handleSecuritySessionLost(e)) {
      message.warning('安全验证已失效，请重新验证')
    } else {
      message.error(e?.message || '修改密码失败')
    }
  } finally {
    pwdLoading.value = false
  }
}

async function reloadTaskCredentialConfig() {
  taskCredentialLoading.value = true
  try {
    const res = await getTaskCredential()
    taskCredentialForm.rootPassword = res.data?.rootPassword || ''
    taskCredentialForm.sshPublicKey = res.data?.sshPublicKey || ''
  } catch (e: any) {
    message.error(e?.message || '读取开机凭据失败')
  } finally {
    taskCredentialLoading.value = false
  }
}

async function saveTaskCredentialConfig() {
  taskCredentialSaving.value = true
  try {
    await saveTaskCredential({
      rootPassword: taskCredentialForm.rootPassword || '',
      sshPublicKey: taskCredentialForm.sshPublicKey || '',
    })
    message.success('开机凭据已保存')
    await reloadTaskCredentialConfig()
  } catch (e: any) {
    message.error(e?.message || '保存开机凭据失败')
  } finally {
    taskCredentialSaving.value = false
  }
}

function maskOpenSshPublicKey(value: string) {
  const raw = (value || '').trim().replace(/[\r\n]+/g, ' ')
  if (!raw) return ''
  const parts = raw.split(/\s+/)
  const body = parts[1] || ''
  const maskedBody = body.length > 16
    ? `${body.slice(0, 4)}****${body.slice(-6)}`
    : '********'
  return `OpenSSH 公钥 ${maskedBody}`
}

function openTaskPublicKeyModal() {
  taskPublicKeyDraft.value = taskCredentialForm.sshPublicKey || ''
  taskPublicKeyModalVisible.value = true
}

function saveTaskPublicKeyDraft() {
  taskCredentialForm.sshPublicKey = (taskPublicKeyDraft.value || '').trim().replace(/[\r\n]+/g, ' ')
  taskPublicKeyModalVisible.value = false
}

async function copyTaskPublicKeyDraft() {
  if (!taskPublicKeyDraft.value) return
  try {
    await navigator.clipboard.writeText(taskPublicKeyDraft.value)
    message.success('公钥已复制')
  } catch {
    message.error('复制失败')
  }
}

function clearTaskPublicKey() {
  taskCredentialForm.sshPublicKey = ''
}

function beforeTaskPublicKeyUpload(file: File) {
  const name = file.name || ''
  if (!name.toLowerCase().endsWith('.pub')) {
    message.warning('请选择 .pub 公钥文件')
    return false
  }
  if (file.size > TASK_PUBLIC_KEY_MAX_SIZE) {
    message.warning('公钥文件过大，请确认选择的是 .pub 公钥文件')
    return false
  }
  const reader = new FileReader()
  reader.onload = () => {
    const content = String(reader.result || '').trim().replace(/[\r\n]+/g, ' ')
    if (!content) {
      message.warning('公钥文件为空')
      return
    }
    taskCredentialForm.sshPublicKey = content
    message.success('公钥已读取')
  }
  reader.onerror = () => message.error('读取公钥文件失败')
  reader.readAsText(file)
  return false
}

function clearTaskCredentialConfig() {
  Modal.confirm({
    title: '清空开机凭据',
    content: '确定清空我的密码和我的公钥？',
    okText: '清空',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      taskCredentialSaving.value = true
      try {
        await saveTaskCredential({ rootPassword: '', sshPublicKey: '' })
        taskCredentialForm.rootPassword = ''
        taskCredentialForm.sshPublicKey = ''
        message.success('开机凭据已清空')
      } catch (e: any) {
        message.error(e?.message || '清空开机凭据失败')
      } finally {
        taskCredentialSaving.value = false
      }
    },
  })
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
    } catch {
      /* 保存已成功，状态回读失败不影响用户操作 */
    }
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

const LOGIN_AUDIT_SESSION_HDR = 'X-Oci-Login-Audit-Session'
const auditSession = ref('')

function auditHeaders(): Record<string, string> {
  const s = auditSession.value?.trim()
  return s ? { [LOGIN_AUDIT_SESSION_HDR]: s } : {}
}

function handleAuditSessionLost(e: unknown) {
  handleSecuritySessionLost(e)
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
  { title: '凭据结果', dataIndex: 'passwordAttempt', key: 'passwordAttempt', ellipsis: true, width: 190 },
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
      '请求头（已脱敏）',
      '敏感字段处理',
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

const BANLIST_SESSION_HDR = 'X-Oci-Banlist-Session'
const banlistSession = ref('')

function banlistHeaders(): Record<string, string> {
  const s = banlistSession.value?.trim()
  return s ? { [BANLIST_SESSION_HDR]: s } : {}
}

function handleBanlistSessionLost(e: unknown) {
  handleSecuritySessionLost(e)
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
type UpdateOverlayMode = 'running' | 'success' | 'error' | 'timeout'
const updateConfirmOpen = ref(false)
const updateOverlayVisible = ref(false)
const updateOverlayMode = ref<UpdateOverlayMode>('running')
const updateOverlayTitle = ref('正在准备升级')
const updateOverlaySub = ref('请不要关闭页面')
const upgradeMaskStyle = computed(() => ({
  background: themeStore.isDark ? 'rgba(3, 7, 18, 0.64)' : 'rgba(241, 245, 249, 0.72)',
  backdropFilter: 'blur(12px)',
  WebkitBackdropFilter: 'blur(12px)',
}))
let updatePollTimer: ReturnType<typeof setTimeout> | null = null
let updateStartTimer: any = null
let updateRedirectTimer: any = null
let updateStageTimer: any = null
let updatePollGeneration = 0

function setUpdateOverlay(mode: UpdateOverlayMode, title: string, sub: string) {
  updateOverlayMode.value = mode
  updateOverlayTitle.value = title
  updateOverlaySub.value = sub
}

function clearUpdateTimers() {
  updatePollGeneration++
  if (updatePollTimer) { clearTimeout(updatePollTimer); updatePollTimer = null }
  if (updateStartTimer) { clearTimeout(updateStartTimer); updateStartTimer = null }
  if (updateRedirectTimer) { clearTimeout(updateRedirectTimer); updateRedirectTimer = null }
  if (updateStageTimer) { clearTimeout(updateStageTimer); updateStageTimer = null }
}

function closeUpdateOverlay() {
  updateConfirmOpen.value = false
  updateOverlayVisible.value = false
}

function refreshPage() {
  window.location.reload()
}

function delay(ms: number) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

async function isHomePageReady() {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 3000)
  try {
    const response = await fetch(`/?_upgradeReady=${Date.now()}`, {
      cache: 'no-store',
      credentials: 'same-origin',
      signal: controller.signal,
    })
    return response.ok
  } catch {
    return false
  } finally {
    clearTimeout(timer)
  }
}

async function waitForHomePageReady(maxAttempts = 5, intervalMs = 1000) {
  for (let i = 0; i < maxAttempts; i++) {
    if (await isHomePageReady()) return true
    if (i < maxAttempts - 1) await delay(intervalMs)
  }
  return false
}

function normalizeCommit(value: unknown) {
  const commit = String(value || '').trim().toLowerCase()
  return commit.length > 7 ? commit.slice(0, 7) : commit
}

async function readServiceCommit() {
  const res = await request.get('/sys/ready', {
    params: { _: Date.now() },
    timeout: 3000,
    skipErrorMessage: true,
  })
  if (!res.data?.ready) throw new Error('服务尚未就绪')
  return normalizeCommit(res.data?.commit)
}

function startUpdateRecoveryPolling() {
  const generation = updatePollGeneration
  const expectedCommit = normalizeCommit(updateInfo.value?.latestCommit)
  const previousCommit = normalizeCommit(updateInfo.value?.currentCommit)
  const mustObserveRestart = updateForce.value || !expectedCommit || expectedCommit === previousCommit
  const startedAt = Date.now()
  const maxWaitMs = 12 * 60 * 1000
  let sawServiceUnavailable = false

  const scheduleNext = () => {
    if (generation !== updatePollGeneration || !updatePerforming.value) return
    updatePollTimer = setTimeout(poll, 1500)
  }

  const poll = async () => {
    updatePollTimer = null
    if (generation !== updatePollGeneration || !updatePerforming.value) return

    if (Date.now() - startedAt >= maxWaitMs) {
      setUpdateOverlay('timeout', '升级等待超时', '下载或服务重启超过 12 分钟，请检查服务器更新日志')
      updatePerforming.value = false
      return
    }

    try {
      const runningCommit = await readServiceCommit()
      if (generation !== updatePollGeneration || !updatePerforming.value) return
      const targetIsRunning = expectedCommit
        ? runningCommit === expectedCommit
        : sawServiceUnavailable

      if (targetIsRunning && (!mustObserveRestart || sawServiceUnavailable)) {
        setUpdateOverlay('running', '正在确认页面可用', '新版本服务已启动，正在检查首页资源')
        const homeReady = await waitForHomePageReady()
        if (generation !== updatePollGeneration || !updatePerforming.value) return
        if (!homeReady) {
          scheduleNext()
          return
        }
        setUpdateOverlay('success', '升级完成', '新版本已恢复，正在返回首页')
        updatePerforming.value = false
        updateRedirectTimer = setTimeout(() => { window.location.href = '/' }, 1200)
        return
      }

      setUpdateOverlay('running', '正在下载并安装更新', '安装包完成后服务会自动重启，请保持页面打开')
    } catch {
      if (generation !== updatePollGeneration || !updatePerforming.value) return
      sawServiceUnavailable = true
      setUpdateOverlay('running', '正在等待服务恢复', '旧服务已停止，正在等待新版本启动')
    }

    scheduleNext()
  }

  void poll()
}

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
  updateConfirmOpen.value = false
  await nextTick()
  updatePerforming.value = true
  clearUpdateTimers()
  updateOverlayVisible.value = true
  setUpdateOverlay('running', '正在准备升级', '请不要关闭页面')
  try {
    updateStageTimer = setTimeout(() => {
      setUpdateOverlay('running', '正在下载更新', '正在获取最新版本和安装包')
    }, 900)
    await request.post('/sys/performUpdate', undefined, { skipErrorMessage: true })
    if (updateStageTimer) { clearTimeout(updateStageTimer); updateStageTimer = null }
    setUpdateOverlay('running', '正在下载并安装更新', '安装包完成后服务会自动重启，请保持页面打开')
    if (updateStartTimer) clearTimeout(updateStartTimer)
    updateStartTimer = setTimeout(() => {
      updateStartTimer = null
      startUpdateRecoveryPolling()
    }, 1000)
  } catch (e: any) {
    clearUpdateTimers()
    setUpdateOverlay('error', '升级启动失败', e?.message || '请稍后重试')
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
  if (securityCountdownTimer) clearInterval(securityCountdownTimer)
  clearUpdateTimers()
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
.security-gate-card {
  max-width: 520px;
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
:global(.upgrade-flow-modal .ant-modal-content) {
  overflow: hidden;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 20px;
  background:
    radial-gradient(circle at 50% 46%, rgba(95, 70, 150, 0.18), transparent 31%),
    linear-gradient(135deg, #100017, #07000d 76%);
  box-shadow: 0 30px 90px rgba(0, 0, 0, 0.42);
}
:global([data-theme="light"] .upgrade-flow-modal .ant-modal-content) {
  background:
    radial-gradient(circle at 50% 48%, rgba(120, 118, 160, 0.1), transparent 30%),
    linear-gradient(135deg, #f7f8fb, #eef1f7 82%);
  box-shadow: 0 30px 90px rgba(15, 23, 42, 0.18);
}
.upgrade-flow-dialog {
  overflow: hidden;
  color: rgba(255, 255, 255, 0.92);
}
:global([data-theme="light"] .upgrade-flow-dialog) {
  color: rgba(15, 23, 42, 0.9);
}
.upgrade-flow-dialog--done {
  background: var(--bg-card);
}
.upgrade-flow-status {
  padding: 18px 22px 22px;
  text-align: center;
  background: rgba(0, 0, 0, 0.12);
}
:global([data-theme="light"] .upgrade-flow-status) {
  background: rgba(255, 255, 255, 0.42);
}
.upgrade-flow-status strong {
  display: block;
  margin-bottom: 6px;
  font-size: 16px;
}
.upgrade-flow-status span {
  color: var(--text-sub);
  font-size: 13px;
}
.upgrade-flow-result {
  padding: 38px 22px;
  text-align: center;
}
.upgrade-flow-result i {
  display: block;
  margin-bottom: 12px;
  font-size: 42px;
}
.upgrade-flow-result--success i {
  color: #22c55e;
}
.upgrade-flow-result--error i,
.upgrade-flow-result--timeout i {
  color: #f59e0b;
}
.upgrade-flow-result h3 {
  margin: 0 0 8px;
  font-size: 20px;
  color: var(--text-main);
}
.upgrade-flow-result p {
  margin: 0 0 18px;
  color: var(--text-sub);
}
.notify-section-panel {
  max-width: 100%;
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

.task-credential-panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.task-credential-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  background: rgba(148, 163, 184, 0.06);
}

.task-credential-section-head,
.task-credential-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.task-credential-section-head {
  min-height: 24px;
  font-weight: 600;
  color: var(--text-main);
}

.task-credential-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.task-credential-title i {
  color: var(--primary);
  font-size: 16px;
  font-weight: 400;
}

.task-public-key-upload :deep(.ant-upload-drag) {
  padding: 8px 12px;
  min-height: 58px;
  border-radius: var(--radius-sm, 6px);
  background: rgba(148, 163, 184, 0.04);
}

.task-public-key-upload :deep(.ant-upload-btn) {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.task-public-key-upload :deep(.ant-upload-drag-icon) {
  margin: 0;
  line-height: 1;
}

.task-public-key-upload :deep(.ant-upload-drag-icon .anticon) {
  font-size: 21px;
}

.task-public-key-upload :deep(.ant-upload-text) {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.task-public-key-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 46px;
  padding: 8px 8px 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  background: rgba(16, 185, 129, 0.07);
}

.task-public-key-summary--empty {
  background: rgba(148, 163, 184, 0.04);
}

.task-public-key-summary-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  color: var(--text-sub);
}

.task-public-key-summary-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.task-public-key-summary-text strong {
  color: var(--text-main);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.25;
}

.task-public-key-summary-text span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.35;
}

.task-public-key-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.14);
}

.task-credential-status {
  color: var(--text-sub);
  font-size: 12px;
}

.task-public-key-modal-textarea {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.task-public-key-modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

@media (max-width: 768px) {
  .settings-card,
  .settings-card-wide,
  .settings-card-audit,
  .backup-restore-stack {
    max-width: 100% !important;
  }
  .task-credential-actions {
    align-items: flex-start;
    flex-direction: column;
  }
  .task-credential-actions :deep(.ant-space) {
    width: 100%;
  }
  .task-public-key-summary {
    align-items: flex-start;
    flex-direction: column;
  }
  .task-public-key-summary-text span {
    white-space: normal;
    word-break: break-word;
  }
  .cf-settings-layout {
    grid-template-columns: 1fr;
  }
  .cf-settings-layout__form {
    padding: 14px 12px;
  }
}
</style>
