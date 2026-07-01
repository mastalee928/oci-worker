<template>
  <a-button @click="loadBootVolumes" :loading="volLoading" style="margin-bottom: 12px">加载引导卷</a-button>
  <a-table v-if="!isMobile" :data-source="bootVolumes" :columns="volColumns" size="small" :pagination="false" row-key="id">
    <template #bodyCell="{ column, record }">
      <template v-if="column.key === 'volAction'">
        <a-button
          type="link"
          size="small"
          :disabled="!isBootVolumeAvailable(record)"
          :title="bootVolumeBusyTitle(record)"
          @click="openEditVolume(record)"
        >编辑</a-button>
      </template>
    </template>
  </a-table>
  <template v-else>
    <a-empty v-if="bootVolumes.length === 0" description="暂无引导卷" />
    <div v-for="vol in bootVolumes" :key="vol.id" class="mobile-card">
      <div class="mobile-card-header">
        <span class="mobile-card-title">{{ vol.displayName }}</span>
        <a-button
          type="link"
          size="small"
          :disabled="!isBootVolumeAvailable(vol)"
          :title="bootVolumeBusyTitle(vol)"
          @click="openEditVolume(vol)"
        >编辑</a-button>
      </div>
      <div class="mobile-card-body">
        <div class="mobile-card-row"><span class="label">大小</span><span class="value">{{ vol.sizeInGBs }} GB</span></div>
        <div class="mobile-card-row"><span class="label">性能</span><span class="value">{{ vol.vpusPerGB }} VPUs/GB</span></div>
        <div class="mobile-card-row"><span class="label">状态</span><span class="value">{{ vol.lifecycleState }}</span></div>
      </div>
    </div>
  </template>
  <div v-if="bootVolumes.length > 0" style="margin-top: 20px">
    <div style="font-size: 13px; color: var(--text-sub); margin-bottom: 10px">快捷预设（性能 120 VPUs/GB）</div>
    <a-space wrap>
      <a-popconfirm v-for="size in [50, 100, 150, 200]" :key="size"
        :title="`确定将引导卷调整为 ${size} GB / 120 VPUs？`"
        :disabled="!isBootVolumeAvailable(bootVolumes[0])"
        @confirm="applyVolumePreset(size)">
        <a-button
          :loading="editVolLoading"
          :disabled="!isBootVolumeAvailable(bootVolumes[0])"
          :title="bootVolumeBusyTitle(bootVolumes[0])"
        >{{ size }} GB</a-button>
      </a-popconfirm>
    </a-space>
  </div>

  <a-modal :keyboard="false" v-model:open="editVolVisible" title="编辑引导卷" @ok="handleEditVolume"
    :confirm-loading="editVolLoading" :mask-closable="false">
    <a-form layout="vertical">
      <a-form-item label="名称">
        <a-input v-model:value="editVolForm.displayName" />
      </a-form-item>
      <a-form-item label="大小 (GB)">
        <a-input-number v-model:value="editVolForm.sizeInGBs" :min="50" :max="32768" style="width: 100%" />
        <div style="color: #999; font-size: 12px; margin-top: 4px">只能增大，不能缩小。最小 50 GB</div>
      </a-form-item>
      <a-form-item label="性能 (VPUs/GB)">
        <a-select v-model:value="editVolForm.vpusPerGB">
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
import { message } from 'ant-design-vue'
import { getBootVolumes, updateBootVolume } from '../../api/instance'

const props = defineProps<{
  tenant: any | null
  instance: any | null
  isMobile: boolean
  active: boolean
  region?: string
}>()

const emit = defineEmits<{
  (e: 'overlay-active-change', active: boolean): void
  (e: 'boot-volume-updated'): void
}>()

const volColumns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName' },
  { title: '大小 (GB)', dataIndex: 'sizeInGBs', key: 'sizeInGBs', width: 100 },
  { title: '性能', dataIndex: 'vpusPerGB', key: 'vpusPerGB', width: 130 },
  { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
  { title: '操作', key: 'volAction', width: 80 },
]

const volLoading = ref(false)
const bootVolumes = ref<any[]>([])
const editVolVisible = ref(false)
const editVolLoading = ref(false)
const editVolForm = reactive({ bootVolumeId: '', displayName: '', sizeInGBs: 50, vpusPerGB: 10 })
let bootLoadSeq = 0

const hasOpenModal = computed(() => editVolVisible.value)

function regionParam(): { region?: string } {
  const r =
    (props.region && String(props.region).trim()) ||
    (props.instance?.region && String(props.instance.region).trim()) ||
    (props.tenant?.ociRegion && String(props.tenant.ociRegion).trim()) ||
    ''
  return r ? { region: r } : {}
}

function reset() {
  bootLoadSeq += 1
  bootVolumes.value = []
  volLoading.value = false
  editVolVisible.value = false
  editVolLoading.value = false
  Object.assign(editVolForm, { bootVolumeId: '', displayName: '', sizeInGBs: 50, vpusPerGB: 10 })
}

async function loadBootVolumes() {
  if (!props.instance || !props.tenant) return
  const requestId = ++bootLoadSeq
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const region = regionParam()
  volLoading.value = true
  try {
    const res = await getBootVolumes({
      id: tenantId,
      instanceId,
      ...region,
    })
    if (requestId !== bootLoadSeq || props.tenant?.id !== tenantId || props.instance?.instanceId !== instanceId) return
    bootVolumes.value = res.data || []
  } catch (e: any) {
    if (requestId === bootLoadSeq) message.error(e?.message || '加载引导卷失败')
  } finally {
    if (requestId === bootLoadSeq) volLoading.value = false
  }
}

function bootVolumeState(record: any) {
  return String(record?.lifecycleState || record?.volumeLifecycleState || '').trim().toUpperCase()
}

function isBootVolumeAvailable(record: any) {
  const state = bootVolumeState(record)
  return !state || state === 'AVAILABLE'
}

function bootVolumeBusyTitle(record: any) {
  if (isBootVolumeAvailable(record)) return ''
  const state = bootVolumeState(record) || '未知'
  return state === 'UPDATE_PENDING'
    ? '引导卷正在更新中，请等待状态变为 AVAILABLE（可用）后再操作。'
    : `引导卷当前状态为 ${state}，请等待状态变为 AVAILABLE（可用）后再操作。`
}

function ensureBootVolumeAvailable(record: any) {
  if (isBootVolumeAvailable(record)) return true
  message.warning(bootVolumeBusyTitle(record))
  return false
}

function openEditVolume(record: any) {
  if (!ensureBootVolumeAvailable(record)) return
  Object.assign(editVolForm, {
    bootVolumeId: record.bootVolumeId || record.id,
    displayName: record.displayName,
    sizeInGBs: record.sizeInGBs,
    vpusPerGB: record.vpusPerGB ?? 10,
  })
  editVolVisible.value = true
}

async function handleEditVolume() {
  if (!props.tenant) return
  const currentVol = bootVolumes.value.find((v: any) => (v.bootVolumeId || v.id) === editVolForm.bootVolumeId)
  if (currentVol && !ensureBootVolumeAvailable(currentVol)) return
  editVolLoading.value = true
  try {
    await updateBootVolume({ id: props.tenant.id, ...editVolForm, ...regionParam() })
    message.success('引导卷已更新')
    editVolVisible.value = false
    void loadBootVolumes()
    emit('boot-volume-updated')
  } catch (e: any) {
    message.error(e?.message || '更新引导卷失败')
  } finally {
    editVolLoading.value = false
  }
}

async function applyVolumePreset(size: number) {
  if (!props.tenant || bootVolumes.value.length === 0) return
  const vol = bootVolumes.value[0]
  if (!ensureBootVolumeAvailable(vol)) return
  editVolLoading.value = true
  try {
    await updateBootVolume({
      id: props.tenant.id,
      bootVolumeId: vol.id,
      displayName: vol.displayName,
      sizeInGBs: size,
      vpusPerGB: 120,
      ...regionParam(),
    })
    message.success(`引导卷已调整为 ${size} GB / 120 VPUs`)
    void loadBootVolumes()
  } catch (e: any) {
    message.error(e?.message || '调整引导卷失败')
  } finally {
    editVolLoading.value = false
  }
}

watch(
  () => [props.tenant?.id, props.instance?.instanceId, props.region],
  () => {
    reset()
    if (props.active) void loadBootVolumes()
  },
)

watch(
  () => props.active,
  (active) => {
    if (active) void loadBootVolumes()
  },
  { immediate: true },
)

watch(hasOpenModal, (active) => emit('overlay-active-change', active), { immediate: true })

defineExpose({
  loadBootVolumes,
  reset,
  hasOpenModal,
})
</script>

<style scoped>
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
