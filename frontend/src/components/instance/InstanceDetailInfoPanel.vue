<template>
  <template v-if="mode === 'info'">
    <a-button size="small" @click="$emit('refresh-info')" :loading="instanceInfoLoading" style="margin-bottom: 12px">
      刷新实例信息
    </a-button>
    <a-descriptions :column="1" bordered size="small" v-if="instance">
      <a-descriptions-item label="实例名称">
        {{ instance.name }}
        <a-button type="link" size="small" @click="$emit('edit-instance')" style="margin-left: 8px">
          <template #icon><EditOutlined /></template>修改
        </a-button>
      </a-descriptions-item>
      <a-descriptions-item label="实例 ID">
        <a-typography-text copyable style="font-size: 12px">{{ instance.instanceId }}</a-typography-text>
      </a-descriptions-item>
      <a-descriptions-item label="Region">{{ instance.region }}</a-descriptions-item>
      <a-descriptions-item label="Shape">{{ instance.shape }}</a-descriptions-item>
      <a-descriptions-item label="配置">{{ instance.ocpus }} OCPU / {{ instance.memoryInGBs }} GB</a-descriptions-item>
      <a-descriptions-item label="可用性域">
        <span :title="instance.availabilityDomain || ''">{{ formatAvailabilityDomain(instance.availabilityDomain) }}</span>
      </a-descriptions-item>
      <a-descriptions-item label="故障域">
        <span :title="instance.faultDomain || ''">{{ formatFaultDomain(instance.faultDomain) }}</span>
        <a-button type="link" size="small" class="fault-domain-edit" @click="$emit('edit-fault-domain')">
          <template #icon><EditOutlined /></template>修改
        </a-button>
      </a-descriptions-item>
      <a-descriptions-item label="区间">{{ instance.compartmentName || '—' }}</a-descriptions-item>
      <a-descriptions-item label="状态">
        <a-badge :status="stateColorMap[instance.state] || 'default'" :text="instance.state" />
      </a-descriptions-item>
      <a-descriptions-item label="创建时间">
        <template v-if="hasValidInstanceCreatedTime(instance.timeCreated)">
          {{ formatInstanceCreatedDate(instance.timeCreated) }}（<button
            type="button"
            class="time-zone-toggle"
            :title="showUtcTime ? '点击切换为北京时间' : '点击切换为 UTC 时间'"
            @click="showUtcTime = !showUtcTime"
          >{{ showUtcTime ? 'UTC' : '北京时间' }}</button>）
        </template>
        <template v-else>—</template>
      </a-descriptions-item>
    </a-descriptions>

    <a-divider orientation="left">网络信息</a-divider>
    <InstanceNetworkDetailPanel
      ref="networkDetailPanelRef"
      :tenant="tenant"
      :instance="instance"
      :active="active"
      :region="region"
      :compartment-id="instance?.compartmentId"
    />

    <a-divider />
    <a-space>
      <a-popconfirm v-if="instance?.state === 'STOPPED'" title="确定启动？" @confirm="$emit('instance-action', 'START')">
        <a-button type="primary" :loading="actionLoading[instance?.instanceId]">启动</a-button>
      </a-popconfirm>
      <a-popconfirm v-if="instance?.state === 'RUNNING'" title="确定停止？" @confirm="$emit('instance-action', 'STOP')">
        <a-button :loading="actionLoading[instance?.instanceId]">停止</a-button>
      </a-popconfirm>
      <a-popconfirm v-if="instance?.state === 'RUNNING'" title="确定重启？" @confirm="$emit('instance-action', 'RESET')">
        <a-button :loading="actionLoading[instance?.instanceId]">重启</a-button>
      </a-popconfirm>
      <a-popconfirm title="确定换 IP？" @confirm="$emit('change-ip')">
        <a-button :loading="changeIpLoading" :disabled="instance?.state !== 'RUNNING'">换 IP</a-button>
      </a-popconfirm>
      <a-button danger @click="$emit('terminate')">终止</a-button>
    </a-space>

    <div class="instance-guard-row">
      <a-switch
        size="small"
        :checked="guardEnabled"
        :loading="guardSaving"
        @change="toggleGuard"
      />
      <span class="instance-guard-copy">
        <strong>自动开机守护</strong>
        <small v-if="guardEnabled && guardInfo">
          检测到停止会自动启动 · 已自动启动 {{ guardInfo.startCount || 0 }} 次<template
            v-if="guardInfo.lastMessage"
          > · {{ guardInfo.lastMessage }}</template>
        </small>
        <small v-else>开启后定时检测该实例，发现 STOPPED 自动执行启动</small>
      </span>
    </div>

    <div v-if="instance?.state === 'STOPPED'" class="instance-guard-row">
      <a-button size="small" :loading="stopCauseLoading" @click="loadStopCause">
        查询停机原因
      </a-button>
      <span v-if="stopCauseText" class="instance-guard-copy">
        <small>{{ stopCauseText }}</small>
      </span>
    </div>
  </template>

  <template v-else>
    <a-alert type="info" show-icon style="margin-bottom: 16px">
      <template #message>用于实例网络异常时的紧急救援，通过 OCI 内部通道连接实例串口</template>
    </a-alert>

    <template v-if="!consoleData">
      <a-button type="primary" @click="$emit('create-console')" :loading="consoleLoading">
        <i class="ri-terminal-line" style="margin-right: 6px"></i>创建控制台连接
      </a-button>
      <div style="margin-top: 8px; color: var(--text-sub); font-size: 12px">
        创建后会生成一个一键连接链接，可直接进入串口终端
      </div>
    </template>

    <template v-else>
      <a-descriptions :column="1" bordered size="small">
        <a-descriptions-item label="连接状态">
          <a-badge status="success" text="已就绪" />
        </a-descriptions-item>
        <a-descriptions-item label="一键连接">
          <a-button type="primary" @click="$emit('open-console')">
            <i class="ri-external-link-line" style="margin-right: 6px"></i>打开串行控制台
          </a-button>
        </a-descriptions-item>
        <a-descriptions-item label="SSH 命令">
          <a-typography-text copyable :content="consoleData.sshCommand" style="font-size: 11px; word-break: break-all">
            {{ consoleData.sshCommand?.substring(0, 80) }}...
          </a-typography-text>
        </a-descriptions-item>
      </a-descriptions>
      <div style="margin-top: 12px">
        <a-popconfirm title="确定断开控制台连接？" @confirm="$emit('delete-console')">
          <a-button danger :loading="consoleLoading">断开连接</a-button>
        </a-popconfirm>
      </div>
      <div style="margin-top: 8px; color: var(--text-sub); font-size: 12px">
        提示：断开后临时用户将自动清理。进入控制台后按 Ctrl+] 或 ~. 退出。
      </div>
    </template>

    <a-divider orientation="left">本地连接（VNC / 串口）</a-divider>
    <template v-if="!localConsole">
      <div style="color: var(--text-sub); font-size: 12px; margin-bottom: 8px">
        使用你自己的 SSH 公钥创建控制台连接，在本地终端建立隧道后，用 RealVNC 等客户端连接
        localhost:5900 进入图形界面。私钥不经过面板。注意：与上方面板串口连接互斥，创建任意一种会替换另一种。
      </div>
      <a-textarea
        v-model:value="localConsoleKey"
        :auto-size="{ minRows: 2, maxRows: 4 }"
        placeholder="粘贴 OpenSSH 公钥（ssh-ed25519 / ssh-rsa 开头），对应私钥保存在你本地"
      />
      <a-button
        type="primary"
        style="margin-top: 8px"
        :loading="localConsoleCreating"
        :disabled="!localConsoleKey.trim()"
        @click="createLocalConsole"
      >
        <i class="ri-computer-line" style="margin-right: 6px"></i>创建本地连接
      </a-button>
      <div v-if="localConsoleCreating" style="margin-top: 6px; color: var(--text-sub); font-size: 12px">
        正在创建，需清理旧连接并等待 OCI 生效，约 30~60 秒…
      </div>
    </template>
    <template v-else>
      <a-descriptions :column="1" bordered size="small">
        <a-descriptions-item label="VNC 隧道命令">
          <a-typography-text copyable :content="localConsole.vncCommand || ''" style="font-size: 11px; word-break: break-all">
            {{ (localConsole.vncCommand || '').substring(0, 80) }}...
          </a-typography-text>
        </a-descriptions-item>
        <a-descriptions-item label="串口 SSH 命令">
          <a-typography-text copyable :content="localConsole.serialCommand || ''" style="font-size: 11px; word-break: break-all">
            {{ (localConsole.serialCommand || '').substring(0, 80) }}...
          </a-typography-text>
        </a-descriptions-item>
      </a-descriptions>
      <div style="margin-top: 8px; color: var(--text-sub); font-size: 12px; line-height: 1.7">
        使用方法：① 在本地终端运行 VNC 隧道命令（默认用 ~/.ssh 下的私钥，其他路径给两处 ssh 各加
        -i 私钥路径）；② 保持终端窗口运行；③ 打开 RealVNC 连接 localhost:5900。
        串口命令则直接在本地终端运行即可交互。
      </div>
      <a-popconfirm title="确定断开本地连接？" @confirm="removeLocalConsole">
        <a-button danger style="margin-top: 10px" :loading="localConsoleDeleting">断开本地连接</a-button>
      </a-popconfirm>
    </template>
  </template>
</template>

<script setup lang="ts">
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import { EditOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { ref, watch } from 'vue'
import { defineAppAsyncComponent } from '../../utils/asyncComponent'
import {
  createLocalConsoleConnection,
  deleteConsoleConnection,
  getInstanceGuardStatus,
  getInstanceStopCause,
  saveInstanceGuard,
  type InstanceGuardStatus,
  type LocalConsoleConnection,
} from '../../api/instance'

dayjs.extend(utc)

defineOptions({ name: 'InstanceDetailInfoPanel' })

const InstanceNetworkDetailPanel = defineAppAsyncComponent(() => import('./InstanceNetworkDetailPanel.vue'), { loadingVariant: 'detail' })

const props = withDefaults(defineProps<{
  mode: 'info' | 'console'
  tenant?: any | null
  instance?: any | null
  active?: boolean
  region?: string
  stateColorMap?: Record<string, string>
  actionLoading?: Record<string, boolean>
  changeIpLoading?: boolean
  instanceInfoLoading?: boolean
  consoleLoading?: boolean
  consoleData?: any | null
}>(), {
  tenant: null,
  instance: null,
  active: false,
  stateColorMap: () => ({}),
  actionLoading: () => ({}),
  changeIpLoading: false,
  instanceInfoLoading: false,
  consoleLoading: false,
  consoleData: null,
})

defineEmits<{
  (e: 'refresh-info'): void
  (e: 'edit-instance'): void
  (e: 'edit-fault-domain'): void
  (e: 'instance-action', action: 'START' | 'STOP' | 'RESET'): void
  (e: 'change-ip'): void
  (e: 'terminate'): void
  (e: 'create-console'): void
  (e: 'open-console'): void
  (e: 'delete-console'): void
}>()

const networkDetailPanelRef = ref<any>(null)
const showUtcTime = ref(false)

const guardEnabled = ref(false)
const guardSaving = ref(false)
const guardInfo = ref<InstanceGuardStatus | null>(null)
let guardLoadGen = 0
const stopCauseLoading = ref(false)
const stopCauseText = ref('')

const localConsole = ref<LocalConsoleConnection | null>(null)
const localConsoleKey = ref('')
const localConsoleCreating = ref(false)
const localConsoleDeleting = ref(false)

async function createLocalConsole() {
  const tenantId = String(props.tenant?.id || '').trim()
  const instanceId = String(props.instance?.instanceId || '').trim()
  const publicKey = localConsoleKey.value.trim()
  if (!tenantId || !instanceId || !publicKey || localConsoleCreating.value) return
  localConsoleCreating.value = true
  try {
    const res = await createLocalConsoleConnection({
      id: tenantId,
      instanceId,
      region: props.region,
      publicKey,
    })
    localConsole.value = res.data
    localConsoleKey.value = ''
    message.success('本地连接已创建，请复制命令在本地终端使用')
  } catch (error: any) {
    message.error(error?.message || '创建本地连接失败')
  } finally {
    localConsoleCreating.value = false
  }
}

async function removeLocalConsole() {
  const tenantId = String(props.tenant?.id || '').trim()
  const connectionId = String(localConsole.value?.connectionId || '').trim()
  if (!tenantId || !connectionId || localConsoleDeleting.value) return
  localConsoleDeleting.value = true
  try {
    await deleteConsoleConnection({ id: tenantId, connectionId, region: props.region })
    localConsole.value = null
    message.success('本地连接已断开')
  } catch (error: any) {
    message.error(error?.message || '断开本地连接失败')
  } finally {
    localConsoleDeleting.value = false
  }
}

async function loadStopCause() {
  const tenantId = String(props.tenant?.id || '').trim()
  const instanceId = String(props.instance?.instanceId || '').trim()
  if (!tenantId || !instanceId || stopCauseLoading.value) return
  stopCauseLoading.value = true
  stopCauseText.value = ''
  try {
    const res = await getInstanceStopCause({ id: tenantId, instanceId, region: props.region })
    stopCauseText.value = res.data?.cause || '未查询到停机原因'
  } catch (error: any) {
    message.error(error?.message || '查询停机原因失败')
  } finally {
    stopCauseLoading.value = false
  }
}

async function loadGuardStatus() {
  const tenantId = String(props.tenant?.id || '').trim()
  const instanceId = String(props.instance?.instanceId || '').trim()
  if (!tenantId || !instanceId) {
    guardEnabled.value = false
    guardInfo.value = null
    return
  }
  const gen = ++guardLoadGen
  try {
    const res = await getInstanceGuardStatus({ id: tenantId, instanceId, region: props.region })
    if (gen !== guardLoadGen) return
    guardInfo.value = res.data
    guardEnabled.value = !!res.data?.enabled
  } catch {
    if (gen !== guardLoadGen) return
    guardInfo.value = null
    guardEnabled.value = false
  }
}

watch(
  () => [props.active, props.mode, String(props.instance?.instanceId || '')],
  (value, previous) => {
    stopCauseText.value = ''
    if (String(value?.[2] || '') !== String(previous?.[2] || '')) {
      localConsole.value = null
      localConsoleKey.value = ''
    }
    if (props.active && props.mode === 'info') void loadGuardStatus()
  },
  { immediate: true },
)

async function toggleGuard(checked: boolean | string | number) {
  const enabled = checked === true
  const tenantId = String(props.tenant?.id || '').trim()
  const instanceId = String(props.instance?.instanceId || '').trim()
  if (!tenantId || !instanceId || guardSaving.value) return
  guardSaving.value = true
  try {
    const res = await saveInstanceGuard({
      id: tenantId,
      instanceId,
      region: props.region,
      instanceName: String(props.instance?.displayName || props.instance?.name || ''),
      enabled,
    })
    guardInfo.value = res.data
    guardEnabled.value = !!res.data?.enabled
    message.success(enabled ? '已开启自动开机守护' : '已关闭自动开机守护')
  } catch (error: any) {
    message.error(error?.message || '自动开机守护设置失败')
  } finally {
    guardSaving.value = false
  }
}

function parseInstanceCreatedTime(v: unknown) {
  if (v == null || v === '') return null
  if (typeof v !== 'string' && typeof v !== 'number' && !(v instanceof Date)) return null
  const d = dayjs.utc(v)
  return d.isValid() ? d : null
}

function hasValidInstanceCreatedTime(v: unknown): boolean {
  return parseInstanceCreatedTime(v) !== null
}

function formatInstanceCreatedDate(v: unknown): string {
  const utcTime = parseInstanceCreatedTime(v)
  if (!utcTime) return '—'
  const d = showUtcTime.value ? utcTime : utcTime.add(8, 'hour')
  const y = d.year()
  const m = String(d.month() + 1).padStart(2, '0')
  const day = String(d.date()).padStart(2, '0')
  const hour = String(d.hour()).padStart(2, '0')
  const minute = String(d.minute()).padStart(2, '0')
  const second = String(d.second()).padStart(2, '0')
  return `${y}年${m}月${day}日 ${hour}:${minute}:${second}`
}

function formatAvailabilityDomain(value: unknown): string {
  const text = typeof value === 'string' ? value.trim() : ''
  if (!text) return '—'
  const match = text.match(/(?:^|-)AD-(\d+)$/i)
  return match ? `AD-${match[1]}` : text
}

function formatFaultDomain(value: unknown): string {
  const text = typeof value === 'string' ? value.trim() : ''
  if (!text) return '—'
  const match = text.match(/(?:FAULT-DOMAIN|FD)-(\d+)$/i)
  return match ? `FD-${match[1]}` : text
}

function loadNetworkDetail() {
  return networkDetailPanelRef.value?.loadNetworkDetail?.()
}

function reset() {
  networkDetailPanelRef.value?.reset?.()
}

defineExpose({
  loadNetworkDetail,
  reset,
})
</script>

<style scoped>
.time-zone-toggle {
  margin: 0;
  padding: 0;
  border: 0;
  outline: 0;
  color: var(--ant-color-primary, #1677ff);
  background: transparent;
  cursor: pointer;
  font: inherit;
}

.time-zone-toggle:hover,
.time-zone-toggle:focus-visible {
  text-decoration: underline;
}

.fault-domain-edit {
  margin-left: 8px;
  padding-inline: 0;
}

.instance-guard-row {
  align-items: flex-start;
  display: flex;
  gap: 10px;
  margin-top: 14px;
}

.instance-guard-row .ant-switch {
  margin-top: 2px;
}

.instance-guard-copy {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.instance-guard-copy strong {
  color: var(--text-main, #111827);
  font-size: 13px;
  font-weight: 600;
}

.instance-guard-copy small {
  color: var(--text-sub, #6b7280);
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}
</style>
