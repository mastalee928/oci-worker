<template>
  <div class="cf-email-tab">
      <!-- Email Routing 状态 -->
      <a-alert
        v-if="emailSettings"
        :type="emailStatusType"
        show-icon
        style="margin-bottom: 16px"
        :message="`Email Routing · ${emailSettings.name || zoneName || ''}`"
      >
        <template #description>
          <span>状态：{{ emailSettings.status || '—' }} · </span>
          <span>{{ emailSettings.enabled ? '已启用' : '未启用' }}</span>
          <a-space wrap style="margin-left: 8px">
            <a-button
              v-if="!emailSettings.enabled"
              type="link"
              size="small"
              :loading="emailToggleLoading"
              @click="handleEnableEmail"
            >
              启用 Email Routing
            </a-button>
            <a-popconfirm
              v-else
              title="确定禁用 Email Routing？"
              @confirm="handleDisableEmail"
            >
              <a-button type="link" size="small" danger :loading="emailToggleLoading">禁用</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </a-alert>

      <!-- Email DNS 记录 -->
      <a-card title="Email DNS 记录" size="small" class="cf-sub-card">
        <template #extra>
          <a-space wrap>
            <a-button size="small" :loading="emailDnsLoading" @click="loadEmailDns">刷新</a-button>
            <a-button
              size="small"
              type="primary"
              :loading="emailDnsLockLoading"
              @click="handleLockEmailDns"
            >
              锁定 MX
            </a-button>
            <a-button
              size="small"
              :loading="emailDnsLockLoading"
              @click="handleUnlockEmailDns"
            >
              解锁 MX
            </a-button>
          </a-space>
        </template>
        <a-table
          v-if="!isMobile"
          :columns="emailDnsColumns"
          :data-source="emailDnsRecords"
          :loading="emailDnsLoading"
          row-key="id"
          size="small"
          :pagination="false"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'locked'">
              <a-tag :color="record.locked ? 'orange' : 'default'">{{ record.locked ? '已锁定' : '未锁定' }}</a-tag>
            </template>
          </template>
        </a-table>
        <a-spin v-else :spinning="emailDnsLoading">
          <a-empty v-if="!emailDnsLoading && emailDnsRecords.length === 0" description="暂无 Email DNS 记录" />
          <div v-for="item in emailDnsRecords" :key="item.id" class="mobile-card">
            <div class="mobile-card-header">
              <span class="mobile-card-title">{{ item.type }} · {{ item.name }}</span>
              <a-tag :color="item.locked ? 'orange' : 'default'">{{ item.locked ? '已锁定' : '未锁定' }}</a-tag>
            </div>
            <div class="mobile-card-body">
              <div class="mobile-card-row"><span class="label">内容</span><span class="value">{{ item.content }}</span></div>
              <div class="mobile-card-row"><span class="label">优先级</span><span class="value">{{ item.priority ?? '—' }}</span></div>
            </div>
          </div>
        </a-spin>
      </a-card>

      <!-- 目标邮箱 -->
      <a-card title="目标邮箱（账户级）" size="small" class="cf-sub-card">
        <template #extra>
          <a-button type="link" size="small" :loading="destLoading" @click="loadDestinations">刷新</a-button>
        </template>
        <div class="cf-toolbar">
          <a-space wrap>
            <a-input
              v-model:value="newDestEmail"
              placeholder="转发目标，如 you@gmail.com"
              style="width: 240px"
              @pressEnter="handleAddDestination"
            />
            <a-button type="primary" :loading="destCreateLoading" @click="handleAddDestination">添加并验证</a-button>
          </a-space>
          <p class="cf-hint">Cloudflare 会向该邮箱发送验证邮件，验证通过后才可用于转发规则。</p>
        </div>
        <a-table
          :columns="destColumns"
          :data-source="destinations"
          :loading="destLoading"
          row-key="id"
          size="small"
          :pagination="false"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'verified'">
              <a-tag :color="isDestVerified(record) ? 'success' : 'warning'">
                {{ isDestVerified(record) ? '已验证' : '待验证' }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'created'">
              {{ formatCfTime(record.created) }}
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space wrap>
                <a-button
                  v-if="!isDestVerified(record)"
                  type="link"
                  size="small"
                  :loading="destResendId === record.id"
                  @click="handleResendDestination(record)"
                >
                  重发验证
                </a-button>
                <a-popconfirm title="确定删除此目标邮箱？" @confirm="handleDeleteDestination(record.id)">
                  <a-button type="link" danger size="small">删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-card>

      <!-- 路由规则 -->
      <a-card title="路由规则" size="small" class="cf-sub-card">
        <template #extra>
          <a-space>
            <a-button type="primary" size="small" @click="openRuleModal()">
              <template #icon><PlusOutlined /></template>
              添加规则
            </a-button>
            <a-button size="small" :loading="emailRulesLoading" @click="loadEmailRules">刷新</a-button>
          </a-space>
        </template>
        <a-table
          v-if="!isMobile"
          :columns="emailRuleColumns"
          :data-source="emailRules"
          :loading="emailRulesLoading"
          row-key="id"
          size="small"
          :scroll="{ x: 960 }"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'actionType'">
              {{ actionTypeLabel(record.actionType) }}
            </template>
            <template v-else-if="column.key === 'target'">
              {{ formatRuleTarget(record) }}
            </template>
            <template v-else-if="column.key === 'enabled'">
              <a-switch
                :checked="record.enabled"
                size="small"
                @change="(v: boolean) => handleToggleEmailRule(record, v)"
              />
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space wrap>
                <a-button type="link" size="small" @click="openRuleModal(record)">编辑</a-button>
                <a-popconfirm title="确定删除此规则？" @confirm="handleDeleteEmailRule(record.id)">
                  <a-button type="link" danger size="small">删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
        <a-spin v-else :spinning="emailRulesLoading">
          <a-empty v-if="!emailRulesLoading && emailRules.length === 0" description="暂无路由规则" />
          <div v-for="item in emailRules" :key="item.id" class="mobile-card">
            <div class="mobile-card-header">
              <span class="mobile-card-title">{{ item.customAddress || item.name }}</span>
              <a-switch
                :checked="item.enabled"
                size="small"
                @change="(v: boolean) => handleToggleEmailRule(item, v)"
              />
            </div>
            <div class="mobile-card-body">
              <div class="mobile-card-row"><span class="label">动作</span><span class="value">{{ actionTypeLabel(item.actionType) }}</span></div>
              <div class="mobile-card-row"><span class="label">目标</span><span class="value">{{ formatRuleTarget(item) }}</span></div>
              <div class="mobile-card-row"><span class="label">优先级</span><span class="value">{{ item.priority ?? '—' }}</span></div>
            </div>
            <a-space wrap style="margin-top: 8px">
              <a-button size="small" @click="openRuleModal(item)">编辑</a-button>
              <a-popconfirm title="确定删除？" @confirm="handleDeleteEmailRule(item.id)">
                <a-button size="small" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </div>
        </a-spin>
      </a-card>

      <!-- Catch-All -->
      <a-card title="Catch-All 规则" size="small" class="cf-sub-card">
        <template #extra>
          <a-button size="small" :loading="catchAllLoading" @click="loadCatchAll">刷新</a-button>
        </template>
        <a-spin :spinning="catchAllLoading">
          <a-empty v-if="!catchAllLoading && !catchAllRule?.id" description="暂无 Catch-All 规则" />
          <template v-else-if="catchAllRule">
            <a-descriptions bordered size="small" :column="isMobile ? 1 : 2">
              <a-descriptions-item label="启用">
                <a-switch
                  :checked="catchAllRule.enabled"
                  size="small"
                  @change="(v: boolean) => { catchAllForm.enabled = v; submitCatchAll() }"
                />
              </a-descriptions-item>
              <a-descriptions-item label="动作">{{ actionTypeLabel(catchAllRule.actionType) }}</a-descriptions-item>
              <a-descriptions-item label="目标" :span="isMobile ? 1 : 2">{{ formatRuleTarget(catchAllRule) }}</a-descriptions-item>
            </a-descriptions>
            <a-button type="link" size="small" style="margin-top: 8px; padding-left: 0" @click="openCatchAllModal">
              编辑 Catch-All
            </a-button>
          </template>
        </a-spin>
      </a-card>

    <!-- 路由规则弹窗 -->
    <a-modal
      v-model:open="ruleModalVisible"
      :mask-closable="false"
      :keyboard="false"
      :title="ruleEditingId ? '编辑路由规则' : '添加路由规则'"
      :confirm-loading="ruleSaveLoading"
      width="560px"
      @ok="submitEmailRule"
    >
      <a-form layout="vertical">
        <a-form-item label="规则名称">
          <a-input v-model:value="ruleForm.name" placeholder="可选" />
        </a-form-item>
        <a-form-item label="自定义地址" required>
          <a-input
            v-model:value="ruleForm.customAddress"
            :placeholder="zoneName ? `如 hello@${zoneName}` : '完整邮箱地址'"
          />
        </a-form-item>
        <a-form-item label="动作类型" required>
          <a-select v-model:value="ruleForm.actionType" :options="actionTypeOptions" />
        </a-form-item>
        <a-form-item v-if="ruleForm.actionType === 'forward'" label="转发到" required>
          <a-select
            v-model:value="ruleForm.destinations"
            mode="multiple"
            placeholder="选择已验证的目标邮箱"
            :options="verifiedDestOptions"
            show-search
          />
        </a-form-item>
        <a-form-item v-if="ruleForm.actionType === 'worker'" label="Worker" required>
          <a-select
            v-model:value="ruleForm.workerName"
            placeholder="选择 Worker 脚本"
            :options="workerOptions"
            :loading="workersLoading"
            show-search
            allow-clear
          />
        </a-form-item>
        <a-form-item label="优先级">
          <a-input-number v-model:value="ruleForm.priority" :min="0" style="width: 100%" />
        </a-form-item>
        <a-form-item label="启用">
          <a-switch v-model:checked="ruleForm.enabled" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- Catch-All 弹窗 -->
    <a-modal
      v-model:open="catchAllModalVisible"
      :mask-closable="false"
      :keyboard="false"
      title="编辑 Catch-All 规则"
      :confirm-loading="catchAllSaveLoading"
      width="560px"
      @ok="submitCatchAll"
    >
      <a-form layout="vertical">
        <a-form-item label="动作类型" required>
          <a-select v-model:value="catchAllForm.actionType" :options="actionTypeOptions" />
        </a-form-item>
        <a-form-item v-if="catchAllForm.actionType === 'forward'" label="转发到" required>
          <a-select
            v-model:value="catchAllForm.destinations"
            mode="multiple"
            placeholder="选择已验证的目标邮箱"
            :options="verifiedDestOptions"
            show-search
          />
        </a-form-item>
        <a-form-item v-if="catchAllForm.actionType === 'worker'" label="Worker" required>
          <a-select
            v-model:value="catchAllForm.workerName"
            placeholder="选择 Worker 脚本"
            :options="workerOptions"
            :loading="workersLoading"
            show-search
            allow-clear
          />
        </a-form-item>
        <a-form-item label="启用">
          <a-switch v-model:checked="catchAllForm.enabled" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, reactive, watch, onMounted, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import CfZoneBar from './CfZoneBar.vue'
import {
  getCfEmailSettings,
  enableCfEmailRouting,
  disableCfEmailRouting,
  getCfEmailDns,
  lockCfEmailDns,
  unlockCfEmailDns,
  listCfEmailRules,
  createCfEmailRule,
  deleteCfEmailRule,
  updateCfEmailRule,
  getCfCatchAllRule,
  updateCfCatchAllRule,
  listCfEmailDestinations,
  createCfEmailDestination,
  resendCfEmailDestination,
  deleteCfEmailDestination,
  listCfWorkers,
} from '../../api/cloudflare'

interface EmailDestination {
  id: string
  email: string
  /** 是否已验证（后端根据 verifiedAt 解析） */
  verified?: boolean
  /** 验证通过时间，CF API 原字段 */
  verifiedAt?: string
  created?: string
  modified?: string
}

interface EmailRule {
  id: string
  name?: string
  customAddress?: string
  actionType?: string
  destinations?: string[]
  workerName?: string
  enabled?: boolean
  priority?: number
}

interface EmailDnsRecord {
  id: string
  type: string
  name: string
  content: string
  priority?: number
  locked?: boolean
}

const props = defineProps<{
  cfConfigured: boolean
  zoneId: string
}>()

const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }

const zoneName = ref('')

const emailSettings = ref<{ enabled?: boolean; status?: string; name?: string } | null>(null)
const emailToggleLoading = ref(false)

const emailDnsLoading = ref(false)
const emailDnsLockLoading = ref(false)
const emailDnsRecords = ref<EmailDnsRecord[]>([])

const destLoading = ref(false)
const destCreateLoading = ref(false)
const destResendId = ref('')
const destinations = ref<EmailDestination[]>([])
const newDestEmail = ref('')

const emailRulesLoading = ref(false)
const emailRules = ref<EmailRule[]>([])

const catchAllLoading = ref(false)
const catchAllSaveLoading = ref(false)
const catchAllRule = ref<EmailRule | null>(null)

const workersLoading = ref(false)
const workers = ref<{ id: string }[]>([])

const ruleModalVisible = ref(false)
const ruleSaveLoading = ref(false)
const ruleEditingId = ref('')
const ruleForm = reactive({
  name: '',
  customAddress: '',
  actionType: 'forward' as 'forward' | 'drop' | 'worker',
  destinations: [] as string[],
  workerName: undefined as string | undefined,
  priority: 0,
  enabled: true,
})

const catchAllModalVisible = ref(false)
const catchAllForm = reactive({
  actionType: 'drop' as 'forward' | 'drop' | 'worker',
  destinations: [] as string[],
  workerName: undefined as string | undefined,
  enabled: false,
})

const actionTypeOptions = [
  { value: 'forward', label: '转发' },
  { value: 'drop', label: '丢弃' },
  { value: 'worker', label: 'Worker' },
]

const emailDnsColumns = [
  { title: '类型', dataIndex: 'type', width: 72 },
  { title: '名称', dataIndex: 'name', ellipsis: true },
  { title: '内容', dataIndex: 'content', ellipsis: true },
  { title: '优先级', dataIndex: 'priority', width: 80 },
  { title: '锁定', key: 'locked', width: 88 },
]

const destColumns = [
  { title: '邮箱', dataIndex: 'email', ellipsis: true },
  { title: '验证', key: 'verified', width: 100 },
  { title: '创建时间', key: 'created', width: 168 },
  { title: '操作', key: 'action', width: 140 },
]

const emailRuleColumns = [
  { title: '名称', dataIndex: 'name', width: 100, ellipsis: true },
  { title: '自定义地址', dataIndex: 'customAddress', ellipsis: true },
  { title: '动作', key: 'actionType', width: 80 },
  { title: '目标', key: 'target', ellipsis: true },
  { title: '优先级', dataIndex: 'priority', width: 72 },
  { title: '启用', key: 'enabled', width: 72 },
  { title: '操作', key: 'action', width: 120 },
]

const verifiedDestOptions = computed(() =>
  destinations.value
    .filter(d => isDestVerified(d))
    .map(d => ({ value: d.email, label: d.email })))

const workerOptions = computed(() =>
  workers.value.map(w => ({ value: w.id, label: w.id })))

const emailStatusType = computed(() => {
  const s = emailSettings.value?.status
  if (s === 'ready') return 'success'
  if (s === 'misconfigured' || s === 'misconfigured/locked') return 'error'
  return 'info'
})

function isDestVerified(d: EmailDestination) {
  if (typeof d.verified === 'boolean') return d.verified
  // 兼容旧数据：CF 的 verified 字段实为 ISO 时间戳，非空即已验证
  const at = d.verifiedAt ?? (typeof d.verified === 'string' ? d.verified : undefined)
  return !!at && String(at).trim() !== ''
}

function formatCfTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('zh-CN', { hour12: false })
}

function actionTypeLabel(t?: string) {
  if (t === 'drop') return '丢弃'
  if (t === 'worker') return 'Worker'
  return '转发'
}

function formatRuleTarget(rule: EmailRule) {
  if (rule.actionType === 'drop') return '—'
  if (rule.actionType === 'worker') return rule.workerName || '—'
  return (rule.destinations || []).join(', ') || '—'
}

let zoneLoadSeq = 0

function resolveZoneId(zoneId?: string) {
  return typeof zoneId === 'string' && zoneId ? zoneId : props.zoneId
}

async function onZoneChange(zoneId: string | undefined) {
  if (!zoneId) {
    zoneLoadSeq++
    emailSettings.value = null
    emailDnsRecords.value = []
    emailRules.value = []
    catchAllRule.value = null
    zoneName.value = ''
    return
  }
  const seq = ++zoneLoadSeq
  await Promise.all([
    loadEmailSettings(zoneId, seq, true),
    loadEmailDns(zoneId, seq, true),
    loadEmailRules(zoneId, seq, true),
    loadCatchAll(zoneId, seq, true),
    loadWorkers(true),
  ])
}

async function loadEmailSettings(zoneId?: string, seq?: number, silent = false) {
  const zid = resolveZoneId(zoneId)
  if (!zid) return
  try {
    const res = await getCfEmailSettings({ zoneId: zid }, silent)
    if (seq !== undefined && seq !== zoneLoadSeq) return
    emailSettings.value = res.data
    zoneName.value = res.data?.name || zoneName.value
  } catch {
    if (seq !== undefined && seq !== zoneLoadSeq) return
    emailSettings.value = null
  }
}

async function handleEnableEmail() {
  if (!props.zoneId) return
  emailToggleLoading.value = true
  try {
    const res = await enableCfEmailRouting({ zoneId: props.zoneId })
    emailSettings.value = res.data
    message.success('Email Routing 已启用')
    await loadEmailDns()
  } finally {
    emailToggleLoading.value = false
  }
}

async function handleDisableEmail() {
  if (!props.zoneId) return
  emailToggleLoading.value = true
  try {
    await disableCfEmailRouting({ zoneId: props.zoneId })
    message.success('Email Routing 已禁用')
    await loadEmailSettings()
  } finally {
    emailToggleLoading.value = false
  }
}

async function loadEmailDns(zoneId?: string, seq?: number, silent = false) {
  const zid = resolveZoneId(zoneId)
  if (!zid) return
  emailDnsLoading.value = true
  try {
    const res = await getCfEmailDns({ zoneId: zid }, silent)
    if (seq !== undefined && seq !== zoneLoadSeq) return
    emailDnsRecords.value = res.data || []
  } catch {
    if (seq !== undefined && seq !== zoneLoadSeq) return
    emailDnsRecords.value = []
  } finally {
    if (seq === undefined || seq === zoneLoadSeq) {
      emailDnsLoading.value = false
    }
  }
}

async function handleLockEmailDns() {
  if (!props.zoneId) return
  emailDnsLockLoading.value = true
  try {
    await lockCfEmailDns({ zoneId: props.zoneId })
    message.success('MX 记录已锁定')
    await loadEmailDns()
  } finally {
    emailDnsLockLoading.value = false
  }
}

async function handleUnlockEmailDns() {
  if (!props.zoneId) return
  emailDnsLockLoading.value = true
  try {
    await unlockCfEmailDns({ zoneId: props.zoneId })
    message.success('MX 记录已解锁')
    await loadEmailDns()
  } finally {
    emailDnsLockLoading.value = false
  }
}

async function loadDestinations() {
  destLoading.value = true
  try {
    const res = await listCfEmailDestinations()
    destinations.value = res.data || []
  } finally {
    destLoading.value = false
  }
}

async function handleAddDestination() {
  const email = newDestEmail.value.trim()
  if (!email) {
    message.warning('请输入邮箱')
    return
  }
  destCreateLoading.value = true
  try {
    await createCfEmailDestination({ email })
    message.success('已添加，请查收验证邮件')
    newDestEmail.value = ''
    await loadDestinations()
  } finally {
    destCreateLoading.value = false
  }
}

async function handleResendDestination(record: EmailDestination) {
  destResendId.value = record.id
  try {
    await resendCfEmailDestination({ email: record.email })
    message.success('验证邮件已重发')
    await loadDestinations()
  } finally {
    destResendId.value = ''
  }
}

async function handleDeleteDestination(id: string) {
  await deleteCfEmailDestination({ destinationId: id })
  message.success('已删除')
  await loadDestinations()
}

async function loadEmailRules(zoneId?: string, seq?: number, silent = false) {
  const zid = resolveZoneId(zoneId)
  if (!zid) return
  emailRulesLoading.value = true
  try {
    const res = await listCfEmailRules({ zoneId: zid }, silent)
    if (seq !== undefined && seq !== zoneLoadSeq) return
    emailRules.value = res.data || []
  } catch {
    if (seq !== undefined && seq !== zoneLoadSeq) return
    emailRules.value = []
  } finally {
    if (seq === undefined || seq === zoneLoadSeq) {
      emailRulesLoading.value = false
    }
  }
}

async function loadWorkers(silent = false) {
  workersLoading.value = true
  try {
    const res = await listCfWorkers(silent)
    workers.value = res.data || []
  } catch {
    workers.value = []
  } finally {
    workersLoading.value = false
  }
}

function openRuleModal(record?: EmailRule) {
  if (record) {
    ruleEditingId.value = record.id
    ruleForm.name = record.name || ''
    ruleForm.customAddress = record.customAddress || ''
    ruleForm.actionType = (record.actionType as 'forward' | 'drop' | 'worker') || 'forward'
    ruleForm.destinations = [...(record.destinations || [])]
    ruleForm.workerName = record.workerName
    ruleForm.priority = record.priority ?? 0
    ruleForm.enabled = record.enabled !== false
  } else {
    ruleEditingId.value = ''
    ruleForm.name = ''
    ruleForm.customAddress = ''
    ruleForm.actionType = 'forward'
    ruleForm.destinations = []
    ruleForm.workerName = undefined
    ruleForm.priority = 0
    ruleForm.enabled = true
  }
  ruleModalVisible.value = true
  if (workers.value.length === 0) loadWorkers()
}

async function submitEmailRule() {
  if (!props.zoneId) return
  let addr = ruleForm.customAddress.trim()
  if (zoneName.value && addr && !addr.includes('@')) {
    addr = `${addr}@${zoneName.value}`
  }
  if (!addr) {
    message.warning('请填写自定义地址')
    return
  }
  if (ruleForm.actionType === 'forward' && ruleForm.destinations.length === 0) {
    message.warning('请选择转发目标')
    return
  }
  if (ruleForm.actionType === 'worker' && !ruleForm.workerName) {
    message.warning('请选择 Worker')
    return
  }
  ruleSaveLoading.value = true
  try {
    const payload = {
      zoneId: props.zoneId,
      name: ruleForm.name.trim() || undefined,
      customAddress: addr,
      actionType: ruleForm.actionType,
      destinations: ruleForm.actionType === 'forward' ? ruleForm.destinations : undefined,
      workerName: ruleForm.actionType === 'worker' ? ruleForm.workerName : undefined,
      priority: ruleForm.priority,
      enabled: ruleForm.enabled,
    }
    if (ruleEditingId.value) {
      await updateCfEmailRule({ ...payload, ruleId: ruleEditingId.value })
      message.success('规则已更新')
    } else {
      await createCfEmailRule(payload)
      message.success('规则已创建')
    }
    ruleModalVisible.value = false
    await loadEmailRules()
  } finally {
    ruleSaveLoading.value = false
  }
}

async function handleDeleteEmailRule(ruleId: string) {
  if (!props.zoneId) return
  await deleteCfEmailRule({ zoneId: props.zoneId, ruleId })
  message.success('已删除')
  await loadEmailRules()
}

async function handleToggleEmailRule(record: EmailRule, enabled: boolean) {
  if (!props.zoneId) return
  await updateCfEmailRule({ zoneId: props.zoneId, ruleId: record.id, enabled })
  record.enabled = enabled
}

async function loadCatchAll(zoneId?: string, seq?: number, silent = false) {
  const zid = resolveZoneId(zoneId)
  if (!zid) return
  catchAllLoading.value = true
  try {
    const res = await getCfCatchAllRule({ zoneId: zid }, silent)
    if (seq !== undefined && seq !== zoneLoadSeq) return
    catchAllRule.value = res.data?.id ? res.data : null
    if (catchAllRule.value) {
      catchAllForm.actionType = (catchAllRule.value.actionType as 'forward' | 'drop' | 'worker') || 'drop'
      catchAllForm.destinations = [...(catchAllRule.value.destinations || [])]
      catchAllForm.workerName = catchAllRule.value.workerName
      catchAllForm.enabled = catchAllRule.value.enabled !== false
    }
  } catch {
    if (seq !== undefined && seq !== zoneLoadSeq) return
    catchAllRule.value = null
  } finally {
    if (seq === undefined || seq === zoneLoadSeq) {
      catchAllLoading.value = false
    }
  }
}

function openCatchAllModal() {
  if (catchAllRule.value) {
    catchAllForm.actionType = (catchAllRule.value.actionType as 'forward' | 'drop' | 'worker') || 'drop'
    catchAllForm.destinations = [...(catchAllRule.value.destinations || [])]
    catchAllForm.workerName = catchAllRule.value.workerName
    catchAllForm.enabled = catchAllRule.value.enabled !== false
  }
  catchAllModalVisible.value = true
  if (workers.value.length === 0) loadWorkers()
}

async function submitCatchAll() {
  if (!props.zoneId) return
  if (catchAllForm.actionType === 'forward' && catchAllForm.destinations.length === 0) {
    message.warning('请选择转发目标')
    return
  }
  if (catchAllForm.actionType === 'worker' && !catchAllForm.workerName) {
    message.warning('请选择 Worker')
    return
  }
  catchAllSaveLoading.value = true
  try {
    const res = await updateCfCatchAllRule({
      zoneId: props.zoneId,
      actionType: catchAllForm.actionType,
      destinations: catchAllForm.actionType === 'forward' ? catchAllForm.destinations : undefined,
      workerName: catchAllForm.actionType === 'worker' ? catchAllForm.workerName : undefined,
      enabled: catchAllForm.enabled,
    })
    catchAllRule.value = res.data?.id ? res.data : catchAllRule.value
    message.success('Catch-All 已更新')
    catchAllModalVisible.value = false
  } finally {
    catchAllSaveLoading.value = false
  }
}

watch(() => props.zoneId, (id) => {
  onZoneChange(id)
}, { immediate: true })

onMounted(async () => {
  window.addEventListener('resize', checkMobile)
  await loadDestinations()
})
onUnmounted(() => window.removeEventListener('resize', checkMobile))
</script>

<style scoped>
.cf-email-tab { min-height: 120px; }
.cf-sub-card { margin-bottom: 16px; }
.cf-toolbar { margin-bottom: 16px; }
.cf-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-sub);
}
.mobile-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
  background: var(--bg-card, transparent);
}
.mobile-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.mobile-card-title { font-weight: 600; }
.mobile-card-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 4px;
}
.mobile-card-row .label { color: var(--text-sub); flex-shrink: 0; }
.mobile-card-row .value { text-align: right; word-break: break-all; }
</style>
