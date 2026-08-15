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
      <template #message>
        用于实例网络异常时的紧急救援。提供两种连法：面板串口（浏览器直接用）与本机直连（你自己的电脑 +
        RealVNC）。两者互斥，创建任意一种会替换另一种。
      </template>
    </a-alert>

    <div class="console-method-card">
      <div class="console-method-head">
        <i class="ri-terminal-box-line"></i>
        <div class="console-method-title">
          <strong>面板串口连接</strong>
          <small>由面板服务器建立隧道，浏览器一键进入串口终端，零配置</small>
        </div>
      </div>

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
            <a-typography-text :copyable="{ text: consoleData.sshCommand || '' }" style="font-size: 11px; word-break: break-all">
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
    </div>

    <div class="console-method-card">
      <div class="console-method-head">
        <i class="ri-computer-line"></i>
        <div class="console-method-title">
          <strong>本机直连（VNC / 串口）</strong>
          <small>命令复制到你自己的电脑运行，直连 Oracle 网关，可用 RealVNC 看图形界面</small>
        </div>
      </div>

      <template v-if="!localConsole">
      <div class="local-console-tip">
        在<strong>你自己的电脑</strong>（Windows / macOS）上直连实例控制台：面板只负责在 OCI
        创建连接对象，生成的命令需要复制到<strong>你电脑的终端</strong>里运行，隧道由你的电脑直连
        Oracle，与面板所在服务器无关。
      </div>
      <a-radio-group v-model:value="localKeyMode" button-style="solid" size="small" class="local-console-mode">
        <a-radio-button value="generate">自动生成密钥</a-radio-button>
        <a-radio-button value="upload">上传公钥文件</a-radio-button>
        <a-radio-button value="paste">粘贴公钥</a-radio-button>
      </a-radio-group>

      <div v-if="localKeyMode === 'generate'" class="local-console-tip">
        面板会生成一次性密钥对并用公钥创建连接；创建成功后私钥<strong>仅显示一次</strong>，
        请立即下载保存到你的电脑。
      </div>
      <template v-else-if="localKeyMode === 'upload'">
        <input
          ref="localKeyFileInput"
          class="local-console-file"
          type="file"
          accept=".pub,.txt,.key,.pem"
          @change="handleLocalKeyFileChange"
        />
        <div
          class="local-console-dropzone"
          :class="{ dragging: localKeyDragging, loaded: !!localKeySource }"
          role="button"
          tabindex="0"
          @click="localKeyFileInput?.click()"
          @keydown.enter.prevent="localKeyFileInput?.click()"
          @dragenter.prevent="localKeyDragging = true"
          @dragover.prevent="localKeyDragging = true"
          @dragleave.prevent="localKeyDragging = false"
          @drop.prevent="handleLocalKeyDrop"
        >
          <i class="ri-upload-2-line"></i>
          <span v-if="localKeyDragging">松开以加载公钥</span>
          <span v-else-if="localKeySource">已加载 {{ localKeySource }}，拖入其他文件可替换</span>
          <span v-else>点击选择公钥文件（.pub / .txt），或从桌面拖入</span>
        </div>
      </template>
      <a-textarea
        v-else
        v-model:value="localConsoleKey"
        :auto-size="{ minRows: 2, maxRows: 4 }"
        placeholder="粘贴 OpenSSH 公钥（ssh-ed25519 / ssh-rsa 开头）。没有密钥？本机终端运行 ssh-keygen -t ed25519 后粘贴 ~/.ssh/id_ed25519.pub 内容"
      />
      <a-button
        type="primary"
        style="margin-top: 10px"
        :loading="localConsoleCreating"
        :disabled="localKeyMode !== 'generate' && !localConsoleKey.trim()"
        @click="createLocalConsole"
      >
        <i class="ri-computer-line" style="margin-right: 6px"></i>创建本机直连
      </a-button>
      <div v-if="localConsoleCreating" class="local-console-tip" style="margin-top: 6px">
        正在创建，需清理旧连接并等待 OCI 生效，约 30~60 秒…
      </div>
    </template>
    <template v-else>
      <a-alert v-if="localConsole.privateKey" type="warning" show-icon class="local-console-alert">
        <template #message>
          私钥仅显示这一次，请立即下载保存到你的电脑；丢失后需重新创建连接。
          <a-button size="small" type="primary" style="margin-left: 8px" @click="downloadLocalKey">
            下载私钥（{{ localConsole.keyFileName }}）
          </a-button>
        </template>
      </a-alert>
      <a-radio-group v-model:value="localOsMode" button-style="solid" size="small" class="local-console-mode">
        <a-radio-button value="win">Windows（PowerShell）</a-radio-button>
        <a-radio-button value="mac">macOS / Linux</a-radio-button>
      </a-radio-group>
      <a-descriptions :column="1" bordered size="small">
        <a-descriptions-item label="VNC 隧道命令">
          <a-typography-text :copyable="{ text: displayVncCommand }" style="font-size: 11px; word-break: break-all">
            {{ displayVncCommand.substring(0, 80) }}...
          </a-typography-text>
        </a-descriptions-item>
        <a-descriptions-item label="串口 SSH 命令">
          <a-typography-text :copyable="{ text: displaySerialCommand }" style="font-size: 11px; word-break: break-all">
            {{ displaySerialCommand.substring(0, 80) }}...
          </a-typography-text>
        </a-descriptions-item>
      </a-descriptions>
      <div class="local-console-tip" style="margin-top: 8px">
        使用步骤（全部在<strong>你自己的电脑</strong>上操作）：<br />
        ① 打开{{ localOsMode === 'win' ? ' PowerShell' : '终端' }}，cd 到私钥所在目录<template
          v-if="localConsole.keyFileName"
        >（即你下载 {{ localConsole.keyFileName }} 的目录）</template>；<template
          v-if="localOsMode === 'mac'"
        >先执行 chmod 600 私钥文件；</template><br />
        ② 运行「VNC 隧道命令」，保持窗口不关闭；<br />
        ③ 打开 RealVNC 等客户端，连接 <strong>localhost:5900</strong>——这里的 localhost
        指你自己的电脑，隧道已把实例画面转发到了你本机的 5900 端口。<template
          v-if="!localConsole.keyFileName"
        ><br />若你的私钥不在默认 ~/.ssh 路径，请给命令中两处 ssh 各加 -i 私钥路径。</template>
      </div>
      <a-popconfirm title="确定断开本机直连？" @confirm="removeLocalConsole">
        <a-button danger style="margin-top: 10px" :loading="localConsoleDeleting">断开本机直连</a-button>
      </a-popconfirm>
    </template>
    </div>
  </template>
</template>

<script setup lang="ts">
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import { EditOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { computed, ref, watch } from 'vue'
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
const localKeyMode = ref<'generate' | 'upload' | 'paste'>('generate')
const localOsMode = ref<'win' | 'mac'>(
  typeof navigator !== 'undefined' && /Mac/i.test(navigator.platform || '') ? 'mac' : 'win',
)
const localKeyFileInput = ref<HTMLInputElement | null>(null)
const localKeyDragging = ref(false)
const localKeySource = ref('')

const localKeyPath = computed(() =>
  localConsole.value?.keyFileName ? `./${localConsole.value.keyFileName}` : '')

const displayVncCommand = computed(() =>
  buildLocalCommand(localConsole.value?.vncCommand || ''))
const displaySerialCommand = computed(() =>
  buildLocalCommand(localConsole.value?.serialCommand || ''))

function buildLocalCommand(raw: string) {
  let cmd = raw
  if (cmd && localKeyPath.value) cmd = injectKeyPath(cmd, localKeyPath.value)
  return localOsMode.value === 'win' ? toPowershellQuoting(cmd) : cmd
}

function injectKeyPath(cmd: string, keyPath: string) {
  return cmd
    .replace(/^ssh /, `ssh -i ${keyPath} `)
    .replace("ProxyCommand='ssh ", `ProxyCommand='ssh -i ${keyPath} `)
}

/** OCI 返回的是 bash 单引号写法，PowerShell 需要把 ProxyCommand 的引号换成双引号。 */
function toPowershellQuoting(cmd: string) {
  const start = cmd.indexOf("ProxyCommand='")
  const end = cmd.lastIndexOf("'")
  if (start < 0 || end <= start + 13) return cmd
  return `${cmd.slice(0, start)}ProxyCommand="${cmd.slice(start + 14, end)}"${cmd.slice(end + 1)}`
}

function handleLocalKeyFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) void loadLocalKeyFile(file)
}

function handleLocalKeyDrop(event: DragEvent) {
  localKeyDragging.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file) void loadLocalKeyFile(file)
}

async function loadLocalKeyFile(file: File) {
  if (file.size > 64 * 1024) {
    message.error('公钥文件不应超过 64 KB')
    return
  }
  try {
    const text = (await file.text()).trim()
    if (!/^(ssh-(rsa|ed25519|dss)|ecdsa-sha2-nistp(256|384|521))\s/.test(text)) {
      message.error('文件内容不是 OpenSSH 公钥（应以 ssh-ed25519 / ssh-rsa 等开头）')
      return
    }
    localConsoleKey.value = text
    localKeySource.value = file.name
  } catch {
    message.error('读取公钥文件失败')
  }
}

function downloadLocalKey() {
  const key = localConsole.value?.privateKey
  if (!key) return
  const blob = new Blob([key], { type: 'application/x-pem-file' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = localConsole.value?.keyFileName || 'oci-console.key'
  link.click()
  URL.revokeObjectURL(url)
}

async function createLocalConsole() {
  const tenantId = String(props.tenant?.id || '').trim()
  const instanceId = String(props.instance?.instanceId || '').trim()
  const generate = localKeyMode.value === 'generate'
  const publicKey = generate ? '' : localConsoleKey.value.trim()
  if (!tenantId || !instanceId) {
    message.error('缺少租户或实例信息，请关闭抽屉后重新打开')
    return
  }
  if ((!generate && !publicKey) || localConsoleCreating.value) return
  localConsoleCreating.value = true
  try {
    const res = await createLocalConsoleConnection({
      id: tenantId,
      instanceId,
      region: props.region,
      publicKey,
      generateKey: generate,
    })
    localConsole.value = res.data
    localConsoleKey.value = ''
    localKeySource.value = ''
    message.success(generate
      ? '本机直连已创建，请立即下载私钥并复制命令到你的电脑运行'
      : '本机直连已创建，请复制命令到你的电脑运行')
  } catch (error: any) {
    message.error(error?.message || '创建本机直连失败')
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
      localKeySource.value = ''
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

.local-console-tip {
  color: var(--text-sub, #6b7280);
  font-size: 12px;
  line-height: 1.7;
  margin-bottom: 8px;
}
.console-method-card {
  border: 1px solid var(--border, rgba(148, 163, 184, 0.25));
  border-radius: 10px;
  margin-bottom: 16px;
  padding: 14px 16px;
}
.console-method-head {
  align-items: flex-start;
  border-bottom: 1px dashed var(--border, rgba(148, 163, 184, 0.25));
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
  padding-bottom: 10px;
}
.console-method-head > i {
  color: var(--ant-color-primary, #1677ff);
  font-size: 18px;
  line-height: 1.3;
}
.console-method-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.console-method-title strong {
  color: var(--text-main, #e2e8f0);
  font-size: 14px;
}
.console-method-title small {
  color: var(--text-sub, #6b7280);
  font-size: 12px;
}
.local-console-mode {
  margin-bottom: 10px;
}
.local-console-file {
  display: none;
}
.local-console-dropzone {
  align-items: center;
  border: 1px dashed var(--border, rgba(148, 163, 184, 0.4));
  border-radius: 8px;
  color: var(--text-sub, #6b7280);
  cursor: pointer;
  display: flex;
  font-size: 12.5px;
  gap: 8px;
  justify-content: center;
  min-height: 52px;
  padding: 10px 12px;
  transition: border-color 0.15s ease, color 0.15s ease;
}
.local-console-dropzone:hover,
.local-console-dropzone.dragging {
  border-color: var(--ant-color-primary, #1677ff);
  color: var(--ant-color-primary, #1677ff);
}
.local-console-dropzone.loaded {
  border-color: var(--success, #10b981);
  border-style: solid;
  color: var(--success-text, #34d399);
}
.local-console-alert {
  margin-bottom: 10px;
}
</style>
