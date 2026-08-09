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
  getInstanceGuardStatus,
  saveInstanceGuard,
  type InstanceGuardStatus,
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
  () => {
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
