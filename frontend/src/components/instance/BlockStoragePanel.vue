<template>
  <div style="margin-bottom: 12px; display: flex; gap: 8px; flex-wrap: wrap; align-items: center">
    <a-button @click="loadBlockVolumes" :loading="blockVolLoading || externalBootVolLoading">刷新存储</a-button>
    <a-button type="primary" @click="openCreateBlockVolume" :disabled="!instance">创建并挂载</a-button>
    <a-button @click="openAttachBlockVolume" :disabled="!instance">挂载已有卷</a-button>
  </div>
  <div v-if="instance" style="font-size: 12px; color: var(--text-sub); margin-bottom: 10px">
    可用域：{{ instance.availabilityDomain || '—' }} · 区间：{{ instance.compartmentName || instance.compartmentId || '—' }}
  </div>
  <div class="detail-section-title">块存储卷</div>
  <a-table
    v-if="!isMobile"
    :data-source="blockVolumes"
    :columns="blockVolColumns"
    size="small"
    :pagination="false"
    :row-key="attachedVolumeRowKey"
    :loading="blockVolLoading"
    :scroll="{ x: 760 }"
  >
    <template #bodyCell="{ column, record }">
      <template v-if="column.key === 'blockVolAction'">
        <a-space size="small">
          <a-button type="link" size="small" @click="openEditAttachedVolume(record)">编辑</a-button>
          <a-popconfirm
            title="确定卸载该块存储卷？卷不会被删除，可在「存储」中再次挂载。"
            @confirm="handleDetachBlockVolume(record)"
          >
            <a-button type="link" danger size="small" :loading="detachBlockVolId === record.attachmentId">卸载</a-button>
          </a-popconfirm>
        </a-space>
      </template>
    </template>
  </a-table>
  <template v-else>
    <a-empty v-if="blockVolumes.length === 0" description="暂无已挂载卷" />
    <div v-for="vol in blockVolumes" :key="attachedVolumeRowKey(vol)" class="mobile-card">
      <div class="mobile-card-header">
        <span class="mobile-card-title">{{ vol.displayName }}</span>
        <a-space size="small">
          <a-button type="link" size="small" @click="openEditAttachedVolume(vol)">编辑</a-button>
          <a-popconfirm title="确定卸载？" @confirm="handleDetachBlockVolume(vol)">
            <a-button type="link" danger size="small">卸载</a-button>
          </a-popconfirm>
        </a-space>
      </div>
      <div class="mobile-card-body">
        <div class="mobile-card-row"><span class="label">大小</span><span class="value">{{ vol.sizeInGBs }} GB</span></div>
        <div class="mobile-card-row"><span class="label">VPUs/GB</span><span class="value">{{ vol.vpusPerGB }}</span></div>
        <div class="mobile-card-row"><span class="label">设备</span><span class="value">{{ vol.device || '—' }}</span></div>
        <div class="mobile-card-row"><span class="label">卷状态</span><span class="value">{{ vol.volumeLifecycleState }}</span></div>
        <div class="mobile-card-row"><span class="label">挂载状态</span><span class="value">{{ vol.attachmentLifecycleState }}</span></div>
      </div>
    </div>
  </template>
  <div class="detail-section-title external-boot-title">
    <span>外部引导卷</span>
    <a-button size="small" @click="loadExternalBootVolumes" :loading="externalBootVolLoading">刷新</a-button>
  </div>
  <a-table
    v-if="!isMobile"
    :data-source="externalBootVolumes"
    :columns="externalBootVolColumns"
    size="small"
    :pagination="false"
    :row-key="externalBootVolumeRowKey"
    :loading="externalBootVolLoading"
    :scroll="{ x: 820 }"
  >
    <template #bodyCell="{ column, record }">
      <template v-if="column.key === 'externalBootState'">
        <a-tag :color="record.attached ? 'purple' : 'green'">{{ record.attachState || (record.attached ? '已挂载' : '可挂载') }}</a-tag>
      </template>
      <template v-else-if="column.key === 'externalBootAction'">
        <a-space size="small">
          <a-button v-if="!record.attached" type="link" size="small" @click="openAttachExternalBootVolume(record)">挂载</a-button>
          <a-button
            v-else
            type="link"
            danger
            size="small"
            :loading="detachExternalBootVolId === record.bootVolumeAttachmentId"
            @click="openDetachExternalBootVolume(record)"
          >
            分离
          </a-button>
        </a-space>
      </template>
    </template>
  </a-table>
  <template v-else>
    <a-empty v-if="externalBootVolumes.length === 0" description="暂无外部引导卷" />
    <div v-for="vol in externalBootVolumes" :key="externalBootVolumeRowKey(vol)" class="mobile-card">
      <div class="mobile-card-header">
        <span class="mobile-card-title">{{ vol.displayName }}</span>
        <a-space size="small">
          <a-tag :color="vol.attached ? 'purple' : 'green'">{{ vol.attachState || (vol.attached ? '已挂载' : '可挂载') }}</a-tag>
          <a-button v-if="!vol.attached" type="link" size="small" @click="openAttachExternalBootVolume(vol)">挂载</a-button>
          <a-button v-else type="link" danger size="small" @click="openDetachExternalBootVolume(vol)">分离</a-button>
        </a-space>
      </div>
      <div class="mobile-card-body">
        <div class="mobile-card-row"><span class="label">大小</span><span class="value">{{ vol.sizeInGBs }} GB</span></div>
        <div class="mobile-card-row"><span class="label">VPUs/GB</span><span class="value">{{ vol.vpusPerGB }}</span></div>
        <div class="mobile-card-row"><span class="label">卷状态</span><span class="value">{{ vol.volumeLifecycleState || vol.lifecycleState }}</span></div>
        <div class="mobile-card-row"><span class="label">挂载状态</span><span class="value">{{ vol.attachmentLifecycleState || '—' }}</span></div>
        <div class="mobile-card-row"><span class="label">可用域</span><span class="value">{{ vol.availabilityDomain || '—' }}</span></div>
      </div>
    </div>
  </template>

  <a-modal :keyboard="false" v-model:open="createBlockVolVisible" title="创建并挂载块存储卷" @ok="handleCreateBlockVolume"
    :confirm-loading="createBlockVolLoading" :mask-closable="false" :width="isMobile ? '100%' : 480">
    <a-form layout="vertical">
      <a-form-item label="显示名称">
        <a-input v-model:value="createBlockVolForm.displayName" placeholder="block-volume" />
      </a-form-item>
      <a-form-item label="容量 (GB)" extra="OCI 要求 50～32768 GB，步进 1 GB">
        <a-input-number v-model:value="createBlockVolForm.sizeInGBs" :min="50" :max="32768" style="width: 100%" />
      </a-form-item>
      <a-form-item label="性能 (VPUs/GB)" extra="0 最低成本 · 10 均衡 · 20 较高 · 30～120 超高（步进 10）">
        <a-select v-model:value="createBlockVolForm.vpusPerGB">
          <a-select-option :value="0">最低成本 (0)</a-select-option>
          <a-select-option :value="10">均衡 (10)</a-select-option>
          <a-select-option :value="20">较高性能 (20)</a-select-option>
          <a-select-option :value="30">高性能 (30)</a-select-option>
          <a-select-option :value="60">高性能 (60)</a-select-option>
          <a-select-option :value="120">超高性能 (120)</a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="挂载类型" extra="半虚拟化会自动连接；iSCSI 需要在实例内连接和挂载">
        <a-segmented v-model:value="createBlockVolForm.attachmentType" :options="blockAttachmentTypeOptions" />
      </a-form-item>
      <a-form-item label="设备路径（可选）" extra="留空由 OCI 自动分配，例如 /dev/oracleoci/oraclevdb">
        <a-input v-model:value="createBlockVolForm.device" placeholder="/dev/oracleoci/oraclevdb" allow-clear />
      </a-form-item>
    </a-form>
  </a-modal>

  <a-modal :keyboard="false" v-model:open="attachBlockVolVisible" title="挂载已有块存储卷" @ok="handleAttachBlockVolume"
    :confirm-loading="attachBlockVolLoading" :mask-closable="false" :width="isMobile ? '100%' : 520">
    <a-form layout="vertical">
      <a-form-item label="块存储卷" extra="仅列出与当前实例同可用域、同区间且未挂载的 AVAILABLE 卷">
        <a-select
          v-model:value="attachBlockVolForm.volumeId"
          show-search
          option-filter-prop="label"
          placeholder="选择块存储卷"
          :loading="unattachedBlockVolLoading"
          :options="unattachedBlockVolOptions"
        />
      </a-form-item>
      <a-form-item label="挂载类型" extra="半虚拟化会自动连接；iSCSI 需要在实例内连接和挂载">
        <a-segmented v-model:value="attachBlockVolForm.attachmentType" :options="blockAttachmentTypeOptions" />
      </a-form-item>
      <a-form-item label="设备路径（可选）">
        <a-input v-model:value="attachBlockVolForm.device" placeholder="/dev/oracleoci/oraclevdb" allow-clear />
      </a-form-item>
    </a-form>
  </a-modal>

  <a-modal :keyboard="false" v-model:open="attachExternalBootVisible" title="挂载外部引导卷" @ok="handleAttachExternalBootVolume"
    :confirm-loading="attachExternalBootLoading" :mask-closable="false" :width="isMobile ? '100%' : 520">
    <a-form layout="vertical">
      <a-form-item label="引导卷">
        <div class="selected-volume-box">
          <div class="selected-volume-name">{{ attachExternalBootTarget?.displayName || '—' }}</div>
          <div class="selected-volume-meta">
            {{ attachExternalBootTarget?.sizeInGBs || '—' }} GB · {{ attachExternalBootTarget?.vpusPerGB ?? '—' }} VPUs/GB · {{ attachExternalBootTarget?.availabilityDomain || '—' }}
          </div>
        </div>
      </a-form-item>
    </a-form>
  </a-modal>

  <a-modal :mask-closable="false" :keyboard="false" v-model:open="detachExternalBootVisible" title="安全验证 — 分离外部引导卷"
    :z-index="INSTANCE_SAFETY_MODAL_Z_INDEX"
    :wrap-class-name="INSTANCE_SAFETY_MODAL_WRAP_CLASS"
    :width="isMobile ? '100%' : 420" @ok="handleDetachExternalBootVolume" :confirm-loading="detachExternalBootLoading"
    ok-text="确认分离" :ok-button-props="{ danger: true }">
    <a-alert type="warning" show-icon style="margin-bottom: 16px">
      <template #message>分离外部引导卷需要 Telegram 验证码</template>
    </a-alert>
    <div class="selected-volume-box" style="margin-bottom: 12px">
      <div class="selected-volume-name">{{ detachExternalBootTarget?.displayName || '—' }}</div>
      <div class="selected-volume-meta">{{ detachExternalBootTarget?.bootVolumeId || '' }}</div>
    </div>
    <a-input v-model:value="detachExternalBootCode" placeholder="请输入6位验证码" size="large" :maxlength="6" allow-clear />
    <div style="margin-top: 12px; display: flex; justify-content: space-between; align-items: center">
      <span style="color: var(--text-sub); font-size: 12px">验证码有效期 5 分钟</span>
      <a-button type="link" size="small" :loading="detachExternalBootSending" @click="() => sendDetachExternalBootCode(false)">重新发送</a-button>
    </div>
  </a-modal>

  <a-modal :keyboard="false" v-model:open="editBlockVolVisible" title="编辑块存储卷" @ok="handleEditBlockVolume"
    :confirm-loading="editBlockVolLoading" :mask-closable="false" :width="isMobile ? '100%' : 480">
    <a-form layout="vertical">
      <a-form-item label="名称">
        <a-input v-model:value="editBlockVolForm.displayName" />
      </a-form-item>
      <a-form-item label="大小 (GB)">
        <a-input-number v-model:value="editBlockVolForm.sizeInGBs" :min="50" :max="32768" style="width: 100%" />
        <div style="color: #999; font-size: 12px; margin-top: 4px">只能增大，不能缩小。最小 50 GB</div>
      </a-form-item>
      <a-form-item label="性能 (VPUs/GB)">
        <a-select v-model:value="editBlockVolForm.vpusPerGB">
          <a-select-option :value="0">最低成本 (0)</a-select-option>
          <a-select-option :value="10">均衡 (10)</a-select-option>
          <a-select-option :value="20">较高性能 (20)</a-select-option>
          <a-select-option :value="30">高性能 (30)</a-select-option>
          <a-select-option :value="120">超高性能 (120)</a-select-option>
        </a-select>
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  attachBlockVolume,
  attachExternalBootVolume,
  createBlockVolumeAndAttach,
  detachBlockVolume,
  detachExternalBootVolume,
  getBlockVolumes,
  getExternalBootVolumes,
  getUnattachedBlockVolumes,
  updateBlockVolume,
} from '../../api/instance'
import { sendVerifyCode } from '../../api/system'
import {
  INSTANCE_CONFIRM_MODAL_WRAP_CLASS,
  INSTANCE_CONFIRM_MODAL_Z_INDEX,
  INSTANCE_SAFETY_MODAL_WRAP_CLASS,
  INSTANCE_SAFETY_MODAL_Z_INDEX,
} from '../../utils/overlayZIndex'

const props = defineProps<{
  tenant: any | null
  instance: any | null
  isMobile: boolean
  active: boolean
  region?: string
  onStopInstance?: () => void | Promise<unknown>
}>()

const emit = defineEmits<{
  (e: 'overlay-active-change', active: boolean): void
}>()

const blockVolColumns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName', ellipsis: true, width: 220 },
  { title: '大小 (GB)', dataIndex: 'sizeInGBs', key: 'sizeInGBs', width: 90 },
  { title: 'VPUs/GB', dataIndex: 'vpusPerGB', key: 'vpusPerGB', width: 80 },
  { title: '设备路径', dataIndex: 'device', key: 'device', width: 120, ellipsis: true },
  { title: '卷状态', dataIndex: 'volumeLifecycleState', key: 'volumeLifecycleState', width: 100 },
  { title: '挂载状态', dataIndex: 'attachmentLifecycleState', key: 'attachmentLifecycleState', width: 100 },
  { title: '操作', key: 'blockVolAction', width: 120 },
]

const externalBootVolColumns = [
  { title: '状态', dataIndex: 'attachState', key: 'externalBootState', width: 90 },
  { title: '名称', dataIndex: 'displayName', key: 'displayName', ellipsis: true, width: 220 },
  { title: '大小 (GB)', dataIndex: 'sizeInGBs', key: 'sizeInGBs', width: 90 },
  { title: 'VPUs/GB', dataIndex: 'vpusPerGB', key: 'vpusPerGB', width: 80 },
  { title: '卷状态', dataIndex: 'volumeLifecycleState', key: 'volumeLifecycleState', width: 100 },
  { title: '挂载状态', dataIndex: 'attachmentLifecycleState', key: 'attachmentLifecycleState', width: 100 },
  { title: '可用域', dataIndex: 'availabilityDomain', key: 'availabilityDomain', width: 170, ellipsis: true },
  { title: '操作', key: 'externalBootAction', width: 110 },
]

const blockVolumes = ref<any[]>([])
const blockVolLoading = ref(false)
const detachBlockVolId = ref('')
const externalBootVolumes = ref<any[]>([])
const externalBootVolLoading = ref(false)
const detachExternalBootVolId = ref('')

const createBlockVolVisible = ref(false)
const createBlockVolLoading = ref(false)
const blockAttachmentTypeOptions = [
  { label: '半虚拟化', value: 'paravirtualized' },
  { label: 'iSCSI', value: 'iscsi' },
]
const createBlockVolForm = reactive({
  displayName: '',
  sizeInGBs: 100,
  vpusPerGB: 10,
  device: '',
  attachmentType: 'paravirtualized' as 'paravirtualized' | 'iscsi',
})

const attachBlockVolVisible = ref(false)
const attachBlockVolLoading = ref(false)
const unattachedBlockVolLoading = ref(false)
const unattachedBlockVolOptions = ref<{ label: string; value: string }[]>([])
const attachBlockVolForm = reactive({
  volumeId: '' as string,
  device: '',
  attachmentType: 'paravirtualized' as 'paravirtualized' | 'iscsi',
})

const attachExternalBootVisible = ref(false)
const attachExternalBootLoading = ref(false)
const attachExternalBootTarget = ref<any>(null)

const detachExternalBootVisible = ref(false)
const detachExternalBootLoading = ref(false)
const detachExternalBootSending = ref(false)
const detachExternalBootTarget = ref<any>(null)
const detachExternalBootCode = ref('')

const editBlockVolVisible = ref(false)
const editBlockVolLoading = ref(false)
const editBlockVolForm = reactive({ volumeId: '', displayName: '', sizeInGBs: 50, vpusPerGB: 10 })
const confirmOverlayActive = ref(false)
let blockLoadSeq = 0
let externalBootLoadSeq = 0
let unattachedBlockLoadSeq = 0

const hasOpenModal = computed(() =>
  createBlockVolVisible.value ||
  attachBlockVolVisible.value ||
  attachExternalBootVisible.value ||
  detachExternalBootVisible.value ||
  editBlockVolVisible.value ||
  confirmOverlayActive.value,
)

function regionParam(): { region?: string } {
  const r =
    (props.region && String(props.region).trim()) ||
    (props.instance?.region && String(props.instance.region).trim()) ||
    (props.tenant?.ociRegion && String(props.tenant.ociRegion).trim()) ||
    ''
  return r ? { region: r } : {}
}

function reset() {
  blockLoadSeq += 1
  externalBootLoadSeq += 1
  unattachedBlockLoadSeq += 1
  blockVolumes.value = []
  externalBootVolumes.value = []
  blockVolLoading.value = false
  externalBootVolLoading.value = false
  unattachedBlockVolLoading.value = false
  detachBlockVolId.value = ''
  detachExternalBootVolId.value = ''
  createBlockVolVisible.value = false
  attachBlockVolVisible.value = false
  attachExternalBootVisible.value = false
  detachExternalBootVisible.value = false
  editBlockVolVisible.value = false
  confirmOverlayActive.value = false
  detachExternalBootCode.value = ''
  attachExternalBootTarget.value = null
  detachExternalBootTarget.value = null
  unattachedBlockVolOptions.value = []
}

async function loadBlockVolumes() {
  if (!props.instance || !props.tenant) return
  const requestId = ++blockLoadSeq
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const region = regionParam()
  blockVolLoading.value = true
  try {
    const res = await getBlockVolumes({
      id: tenantId,
      instanceId,
      ...region,
    })
    if (requestId !== blockLoadSeq || props.tenant?.id !== tenantId || props.instance?.instanceId !== instanceId) return
    blockVolumes.value = res.data || []
  } catch (e: any) {
    if (requestId === blockLoadSeq) message.error(e?.message || '加载已挂载卷失败')
  } finally {
    if (requestId === blockLoadSeq) blockVolLoading.value = false
  }
  if (requestId === blockLoadSeq) void loadExternalBootVolumes()
}

async function loadExternalBootVolumes() {
  if (!props.instance || !props.tenant) return
  const requestId = ++externalBootLoadSeq
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const region = regionParam()
  externalBootVolLoading.value = true
  try {
    const res = await getExternalBootVolumes({
      id: tenantId,
      instanceId,
      ...region,
    })
    if (requestId !== externalBootLoadSeq || props.tenant?.id !== tenantId || props.instance?.instanceId !== instanceId) return
    externalBootVolumes.value = res.data || []
  } catch (e: any) {
    if (requestId === externalBootLoadSeq) message.error(e?.message || '加载外部引导卷失败')
  } finally {
    if (requestId === externalBootLoadSeq) externalBootVolLoading.value = false
  }
}

function attachedVolumeRowKey(record: any) {
  return record?.rowKey || record?.bootVolumeAttachmentId || record?.attachmentId || record?.id
}

function externalBootVolumeRowKey(record: any) {
  return record?.rowKey || record?.bootVolumeAttachmentId || record?.bootVolumeId || record?.id
}

function openEditAttachedVolume(record: any) {
  openEditBlockVolume(record)
}

function defaultBlockVolumeName() {
  const inst = props.instance
  if (!inst?.name) return 'block-volume'
  return `${inst.name}-data`
}

function openCreateBlockVolume() {
  createBlockVolForm.displayName = defaultBlockVolumeName()
  createBlockVolForm.sizeInGBs = 100
  createBlockVolForm.vpusPerGB = 10
  createBlockVolForm.device = ''
  createBlockVolForm.attachmentType = 'paravirtualized'
  createBlockVolVisible.value = true
}

async function handleCreateBlockVolume() {
  if (!props.instance || !props.tenant) return
  if (!createBlockVolForm.sizeInGBs || createBlockVolForm.sizeInGBs < 50) {
    message.warning('容量须至少 50 GB')
    return
  }
  createBlockVolLoading.value = true
  try {
    await createBlockVolumeAndAttach({
      id: props.tenant.id,
      instanceId: props.instance.instanceId,
      displayName: createBlockVolForm.displayName?.trim() || defaultBlockVolumeName(),
      sizeInGBs: createBlockVolForm.sizeInGBs,
      vpusPerGB: createBlockVolForm.vpusPerGB,
      device: createBlockVolForm.device?.trim() || undefined,
      attachmentType: createBlockVolForm.attachmentType,
      ...regionParam(),
    })
    message.success('块存储卷已创建并提交挂载')
    createBlockVolVisible.value = false
    void loadBlockVolumes()
  } catch (e: any) {
    message.error(e?.message || '创建并挂载失败')
  } finally {
    createBlockVolLoading.value = false
  }
}

async function loadUnattachedBlockVolumeOptions() {
  if (!props.instance || !props.tenant) return
  const requestId = ++unattachedBlockLoadSeq
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const region = regionParam()
  unattachedBlockVolLoading.value = true
  try {
    const res = await getUnattachedBlockVolumes({
      id: tenantId,
      instanceId,
      ...region,
    })
    if (requestId !== unattachedBlockLoadSeq || props.tenant?.id !== tenantId || props.instance?.instanceId !== instanceId) return
    const rows = res.data || []
    unattachedBlockVolOptions.value = rows.map((r: any) => ({
      value: r.id,
      label: `${r.displayName || r.id} · ${r.sizeInGBs} GB · ${r.vpusPerGB ?? '—'} VPUs`,
    }))
  } catch (e: any) {
    if (requestId === unattachedBlockLoadSeq) {
      message.error(e?.message || '加载可挂载卷失败')
      unattachedBlockVolOptions.value = []
    }
  } finally {
    if (requestId === unattachedBlockLoadSeq) unattachedBlockVolLoading.value = false
  }
}

function openAttachBlockVolume() {
  attachBlockVolForm.volumeId = ''
  attachBlockVolForm.device = ''
  attachBlockVolForm.attachmentType = 'paravirtualized'
  attachBlockVolVisible.value = true
  void loadUnattachedBlockVolumeOptions()
}

async function handleAttachBlockVolume() {
  if (!props.instance || !props.tenant) return
  if (!attachBlockVolForm.volumeId) {
    message.warning('请选择块存储卷')
    return
  }
  attachBlockVolLoading.value = true
  try {
    await attachBlockVolume({
      id: props.tenant.id,
      instanceId: props.instance.instanceId,
      volumeId: attachBlockVolForm.volumeId,
      device: attachBlockVolForm.device?.trim() || undefined,
      attachmentType: attachBlockVolForm.attachmentType,
      ...regionParam(),
    })
    message.success('已提交挂载')
    attachBlockVolVisible.value = false
    void loadBlockVolumes()
  } catch (e: any) {
    message.error(e?.message || '挂载失败')
  } finally {
    attachBlockVolLoading.value = false
  }
}

function openAttachExternalBootVolume(record: any) {
  attachExternalBootTarget.value = record
  attachExternalBootVisible.value = true
}

async function handleAttachExternalBootVolume() {
  if (!props.instance || !props.tenant || !attachExternalBootTarget.value?.bootVolumeId) return
  attachExternalBootLoading.value = true
  try {
    await attachExternalBootVolume({
      id: props.tenant.id,
      instanceId: props.instance.instanceId,
      bootVolumeId: attachExternalBootTarget.value.bootVolumeId,
      ...regionParam(),
    })
    message.success('已提交挂载外部引导卷')
    attachExternalBootVisible.value = false
    void loadExternalBootVolumes()
  } catch (e: any) {
    message.error(e?.message || '挂载外部引导卷失败')
  } finally {
    attachExternalBootLoading.value = false
  }
}

function currentInstanceState() {
  return String(props.instance?.state || props.instance?.lifecycleState || '').toUpperCase()
}

function openDetachExternalBootVolume(record: any) {
  if (!props.instance || !props.tenant) return
  if (!record?.bootVolumeAttachmentId) {
    message.warning('未找到引导卷附加关系')
    return
  }
  if (currentInstanceState() !== 'STOPPED') {
    confirmOverlayActive.value = true
    Modal.confirm({
      title: '需要先停止实例',
      content: `实例状态为 ${props.instance.state || props.instance.lifecycleState || '未知'}，请先断电停止实例后再分离外部引导卷。`,
      okText: '断电停止',
      cancelText: '取消',
      okButtonProps: { danger: true },
      zIndex: INSTANCE_CONFIRM_MODAL_Z_INDEX,
      wrapClassName: INSTANCE_CONFIRM_MODAL_WRAP_CLASS,
      async onOk() {
        await props.onStopInstance?.()
      },
      afterClose: () => {
        confirmOverlayActive.value = false
      },
    })
    return
  }
  detachExternalBootTarget.value = record
  detachExternalBootCode.value = ''
  void sendDetachExternalBootCode(true)
}

async function sendDetachExternalBootCode(openAfterSend = false) {
  detachExternalBootSending.value = true
  try {
    await sendVerifyCode('detachBootVolume')
    message.success(openAfterSend ? '验证码已发送至 Telegram' : '验证码已重新发送')
    if (openAfterSend) detachExternalBootVisible.value = true
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
  } finally {
    detachExternalBootSending.value = false
  }
}

async function handleDetachExternalBootVolume() {
  if (!props.instance || !props.tenant || !detachExternalBootTarget.value?.bootVolumeAttachmentId) return
  if (!detachExternalBootCode.value || detachExternalBootCode.value.length !== 6) {
    message.warning('请输入6位验证码')
    return
  }
  detachExternalBootLoading.value = true
  detachExternalBootVolId.value = detachExternalBootTarget.value.bootVolumeAttachmentId
  try {
    await detachExternalBootVolume({
      id: props.tenant.id,
      instanceId: props.instance.instanceId,
      bootVolumeAttachmentId: detachExternalBootTarget.value.bootVolumeAttachmentId,
      verifyCode: detachExternalBootCode.value,
      ...regionParam(),
    })
    message.success('已提交分离外部引导卷')
    detachExternalBootVisible.value = false
    void loadExternalBootVolumes()
  } catch (e: any) {
    message.error(e?.message || '分离外部引导卷失败')
  } finally {
    detachExternalBootLoading.value = false
    detachExternalBootVolId.value = ''
  }
}

function openEditBlockVolume(record: any) {
  editBlockVolForm.volumeId = record.volumeId
  editBlockVolForm.displayName = record.displayName
  editBlockVolForm.sizeInGBs = record.sizeInGBs != null ? Number(record.sizeInGBs) : 50
  editBlockVolForm.vpusPerGB = record.vpusPerGB ?? 10
  editBlockVolVisible.value = true
}

async function handleEditBlockVolume() {
  if (!props.tenant || !editBlockVolForm.volumeId) return
  editBlockVolLoading.value = true
  try {
    await updateBlockVolume({
      id: props.tenant.id,
      volumeId: editBlockVolForm.volumeId,
      displayName: editBlockVolForm.displayName,
      sizeInGBs: editBlockVolForm.sizeInGBs,
      vpusPerGB: editBlockVolForm.vpusPerGB,
      ...regionParam(),
    })
    message.success('块存储卷已更新')
    editBlockVolVisible.value = false
    void loadBlockVolumes()
  } catch (e: any) {
    message.error(e?.message || '更新块存储卷失败')
  } finally {
    editBlockVolLoading.value = false
  }
}

async function handleDetachBlockVolume(record: any) {
  if (!props.tenant || !record?.attachmentId) return
  detachBlockVolId.value = record.attachmentId
  try {
    await detachBlockVolume({
      id: props.tenant.id,
      volumeAttachmentId: record.attachmentId,
      ...regionParam(),
    })
    message.success('已提交卸载')
    void loadBlockVolumes()
  } catch (e: any) {
    message.error(e?.message || '卸载失败')
  } finally {
    detachBlockVolId.value = ''
  }
}

watch(
  () => [props.tenant?.id, props.instance?.instanceId, props.region],
  () => {
    reset()
    if (props.active) void loadBlockVolumes()
  },
)

watch(
  () => props.active,
  (active) => {
    if (active) void loadBlockVolumes()
  },
  { immediate: true },
)

watch(hasOpenModal, (active) => emit('overlay-active-change', active), { immediate: true })

defineExpose({
  loadBlockVolumes,
  loadExternalBootVolumes,
  reset,
  hasOpenModal,
})
</script>

<style scoped>
.detail-section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 12px 0 8px;
  color: var(--text-main);
  font-size: 13px;
  font-weight: 700;
}

.external-boot-title {
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

.selected-volume-box {
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-sidebar);
}

.selected-volume-name {
  color: var(--text-main);
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.selected-volume-meta {
  margin-top: 4px;
  color: var(--text-sub);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.mobile-card {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
}

.mobile-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.mobile-card-title {
  min-width: 0;
  color: var(--text-main);
  font-size: 14px;
  font-weight: 700;
  word-break: break-all;
}

.mobile-card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.mobile-card-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
}

.mobile-card-row .label {
  color: var(--text-sub);
  white-space: nowrap;
}

.mobile-card-row .value {
  min-width: 0;
  color: var(--text-main);
  text-align: right;
  word-break: break-all;
}
</style>
