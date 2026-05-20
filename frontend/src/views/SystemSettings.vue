<template>
  <div>
    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="security" tab="安全设置">
        <a-card title="修改登录密码" class="settings-card">
          <div v-if="!pwdTgVerified" class="lock-panel">
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

        <a-card title="登录安全说明" class="settings-card" style="margin-top: 16px">
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
        <a-card title="Telegram 通知" class="settings-card-wide">
          <div v-if="!notifyPwdVerified" class="lock-panel">
            <i class="ri-lock-2-line lock-icon"></i>
            <p class="lock-text">请输入登录密码进行配置</p>
            <a-space direction="vertical" style="width: 100%">
              <a-input-password v-model:value="notifyPwd" placeholder="输入登录密码" @pressEnter="verifyNotifyPwd" />
              <a-button type="primary" block @click="verifyNotifyPwd" :disabled="!notifyPwd">验证</a-button>
            </a-space>
          </div>
          <a-form v-else layout="vertical">
            <a-form-item label="Bot Token">
              <a-input v-model:value="tgConfig.botToken" placeholder="输入 Telegram Bot Token" />
            </a-form-item>
            <a-form-item label="Chat ID">
              <a-input v-model:value="tgConfig.chatId" placeholder="输入 Chat ID" />
            </a-form-item>
            <a-form-item label="通知类型">
              <a-checkbox-group v-model:value="tgConfig.notifyTypes" :options="notifyTypeOptions" />
            </a-form-item>
            <a-form-item v-if="tgConfig.notifyTypes.includes('daily_report')" label="每日播报时间">
              <a-time-picker
                v-model:value="dailyReportTimePicked"
                format="HH:mm"
                :show-second="false"
                :minute-step="1"
                value-format="HH:mm"
              />
              <div style="margin-top: 6px; font-size: 12px; color: var(--text-sub)">
                东八区（Asia/Shanghai），默认 09:00
              </div>
            </a-form-item>
            <div v-if="tgConfigured" style="font-size: 12px; color: var(--text-sub); margin-bottom: 8px">
              已绑定 Telegram：保存将发送验证码到 TG 进行确认。
            </div>
            <a-space wrap>
              <a-button type="primary" @click="saveTgConfig" :loading="saveLoading">保存</a-button>
              <a-button @click="testTgNotify" :loading="testLoading">测试发送</a-button>
            </a-space>
          </a-form>
        </a-card>

        <a-modal
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
        </a-modal>

        <a-card title="通知说明" class="settings-card-wide" style="margin-top: 16px">
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item label="登录通知">登录成功/失败时发送，包含IP地址、账号、时间</a-descriptions-item>
            <a-descriptions-item label="创建任务">创建开机任务时通知</a-descriptions-item>
            <a-descriptions-item label="任务结果">开机成功或认证失败时通知，包含实例详情</a-descriptions-item>
            <a-descriptions-item label="每日播报">在所设东八区时刻自动发送（默认 09:00），包含租户总数、失效租户、运行中任务</a-descriptions-item>
          </a-descriptions>
        </a-card>
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
                <span v-if="updateInfo.publishedAt" style="font-size: 12px">{{ formatPublishDate(updateInfo.publishedAt) }}</span>
                <span v-if="updateInfo.latestSizeHuman" style="margin-left: 8px; color: var(--text-sub); font-size: 12px">({{ updateInfo.latestSizeHuman }})</span>
              </a-descriptions-item>
              <a-descriptions-item label="状态">
                <a-badge v-if="updateInfo.hasUpdate" status="warning" text="有新版本可用" />
                <a-badge v-else-if="updateInfo.error" status="error" :text="'检查失败: ' + updateInfo.error" />
                <a-badge v-else-if="updateInfo.notice" status="processing" :text="updateInfo.notice" />
                <a-badge v-else-if="updateInfo.versionNotice" status="success" text="无需更新" />
                <a-badge v-else status="success" text="已是最新版本" />
              </a-descriptions-item>
              <a-descriptions-item
                v-if="updateInfo.versionNotice"
                :label="updateInfo.hasUpdate ? '注意' : '说明'"
              >
                <span style="color: var(--text-sub); font-size: 12px">{{ updateInfo.versionNotice }}</span>
              </a-descriptions-item>
            </a-descriptions>
            <a-empty v-else description="点击检查更新" />
          </a-spin>
          <div style="margin-top: 16px">
            <a-space>
              <a-button @click="checkUpdate" :loading="updateChecking">检查更新</a-button>
              <a-popconfirm title="确定执行更新？更新过程中服务将短暂重启。" @confirm="performUpdate" ok-text="确定更新" cancel-text="取消">
                <a-button type="primary" :loading="updatePerforming" :disabled="!updateInfo?.hasUpdate && !updateForce">
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
            <a-descriptions-item label="更新来源">GitHub Releases (mastalee928/oci-worker)</a-descriptions-item>
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

        <a-modal v-model:open="backupVerifyVisible" title="安全验证 — 备份数据" :width="isMobile ? '100%' : 400"
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
import { h, reactive, ref, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { CaretRightOutlined, InboxOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import type { UploadFile } from 'ant-design-vue'
import { useUserStore } from '../stores/user'
import { sendVerifyCode } from '../api/system'
import request from '../utils/request'

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

const notifyTypeOptions = [
  { label: '登录通知', value: 'login' },
  { label: '创建任务', value: 'task_create' },
  { label: '任务结果', value: 'task_result' },
  { label: '实例操作', value: 'instance' },
  { label: '每日播报', value: 'daily_report' },
]

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
})

onMounted(async () => {
  if (typeof window !== 'undefined') {
    window.addEventListener('resize', checkMobile)
  }
  loadNotifyConfig()
  loadOciProxy()
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
      localStorage.setItem('token', res.data.token)
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
  localStorage.removeItem('token')
  router.push('/login')
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
    return new Date(isoStr).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
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
.settings-card-wide {
  max-width: 560px;
  border-radius: var(--radius-lg) !important;
  box-shadow: var(--shadow-card) !important;
  border-color: var(--border) !important;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: var(--trans);
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

@media (max-width: 768px) {
  .settings-card,
  .settings-card-wide,
  .settings-card-audit,
  .backup-restore-stack {
    max-width: 100% !important;
  }
}
</style>
