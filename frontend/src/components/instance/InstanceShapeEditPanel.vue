<template>
  <div class="instance-shape-edit-panel">
    <a-alert type="info" show-icon style="margin-bottom: 16px">
      <template #message>
        列表按当前实例镜像与可用域从 OCI ListShapes 拉取；Flex 规格须落在 API 返回的 OCPU/内存上下限内。提交后 OCI 可能自动停止并重启实例以生效。
      </template>
    </a-alert>

    <a-alert
      v-if="shapeEditTask"
      ref="shapeEditTaskAlertRef"
      :type="shapeEditTaskAlertType"
      show-icon
      style="margin-bottom: 16px"
    >
      <template #message>
        <div class="shape-edit-task-head">
          <span>{{ shapeEditTaskTitle }}</span>
          <a-space v-if="shapeEditTaskActive" size="small" wrap>
            <a-button size="small" :loading="shapeEditTaskActionLoading" @click="handleToggleShapeEditTaskPause">
              <template #icon>
                <PlayCircleOutlined v-if="shapeEditTaskPaused" />
                <PauseCircleOutlined v-else />
              </template>
              {{ shapeEditTaskPaused ? '恢复' : '暂停' }}
            </a-button>
            <a-button size="small" danger :loading="shapeEditTaskActionLoading" @click="handleStopShapeEditTask">
              <template #icon><StopOutlined /></template>
              停止
            </a-button>
          </a-space>
        </div>
      </template>
      <template #description>
        <a-progress
          :percent="shapeEditTaskPercent"
          :status="shapeEditTaskProgressStatus"
          size="small"
          style="margin-top: 8px"
        />
        <div class="shape-edit-task-message">{{ shapeEditTaskMessage }}</div>
      </template>
    </a-alert>

    <a-spin :spinning="shapeEditLoading">
      <template v-if="instance">
        <a-descriptions :column="1" bordered size="small" style="margin-bottom: 16px">
          <a-descriptions-item label="当前 Shape">{{ instance.shape }}</a-descriptions-item>
          <a-descriptions-item label="当前配置">
            {{ instance.ocpus ?? '—' }} OCPU / {{ instance.memoryInGBs ?? '—' }} GB
          </a-descriptions-item>
          <a-descriptions-item label="状态">
            <a-badge :status="stateColorMap[instance.state] || 'default'" :text="instance.state" />
          </a-descriptions-item>
        </a-descriptions>

        <a-form layout="vertical" class="shape-edit-form">
          <a-form-item label="目标 Shape">
            <a-select
              v-model:value="shapeForm.shape"
              show-search
              :filter-option="filterShapeOption"
              placeholder="选择兼容形状"
              :options="shapeEditSelectOptions"
              :disabled="shapeEditTaskActive"
              @change="onShapeFormShapeChange"
            />
          </a-form-item>

          <template v-if="shapeEditSelectedMeta?.isFlexible">
            <a-row :gutter="12">
              <a-col :span="12">
                <a-form-item :label="shapeOcpuLabel">
                  <a-input-number
                    v-model:value="shapeForm.ocpus"
                    :min="shapeOcpuMin"
                    :max="shapeOcpuMax"
                    :step="1"
                    :disabled="shapeEditTaskActive"
                    style="width: 100%"
                  />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item :label="shapeMemoryLabel">
                  <a-input-number
                    v-model:value="shapeForm.memoryInGBs"
                    :min="shapeMemoryMin"
                    :max="shapeMemoryMax"
                    :step="1"
                    :disabled="shapeEditTaskActive"
                    style="width: 100%"
                  />
                </a-form-item>
              </a-col>
            </a-row>
          </template>

          <a-alert
            v-else-if="shapeEditSelectedMeta"
            type="warning"
            show-icon
            message="当前为固定规格 Shape，仅可更换形状系列，不能单独调整 OCPU/内存。"
            style="margin-bottom: 12px"
          />

          <a-space wrap>
            <a-button
              type="primary"
              :loading="shapeEditSaving"
              :disabled="shapeEditTaskActive || !shapeForm.shape"
              @click="handleApplyShapeEdit"
            >
              应用形状变更
            </a-button>
            <a-button :disabled="shapeEditLoading" @click="loadOptions(true)">刷新 Shape 列表</a-button>
          </a-space>
        </a-form>
      </template>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { PauseCircleOutlined, PlayCircleOutlined, StopOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import {
  getShapeEditTaskStatus,
  getShapesForInstance,
  pauseShapeEditTask,
  resumeShapeEditTask,
  stopShapeEditTask,
  updateInstance,
  type ShapeEditTaskStatus,
} from '../../api/instance'
import {
  getFlexShapeSpec,
  resolveShapeEditFlexLimits,
  formatShapeResourceRangeLabel,
} from '../../constants/ociBmShapeSpecs'

const props = defineProps<{
  tenant: any | null
  instance: any | null
  active: boolean
  region?: string
}>()

const emit = defineEmits<{
  (e: 'focus-panel'): void
  (e: 'instance-updated', patch: Record<string, any>): void
  (e: 'reload-instance-list'): void
}>()

const stateColorMap: Record<string, string> = {
  RUNNING: 'success',
  STOPPED: 'error',
  STARTING: 'processing',
  STOPPING: 'warning',
  TERMINATED: 'default',
}

const shapeEditLoading = ref(false)
const shapeEditSaving = ref(false)
const shapeEditTask = ref<ShapeEditTaskStatus | null>(null)
const shapeEditTaskActionLoading = ref(false)
const shapeEditTaskAlertRef = ref<any>(null)
const shapeEditOptions = ref<any[]>([])
const shapeForm = reactive({ shape: '' as string, ocpus: 1 as number, memoryInGBs: 6 as number })
let shapeEditTaskPollTimer: ReturnType<typeof setInterval> | null = null
let shapeLoadSeq = 0

const shapeEditSelectOptions = computed(() =>
  shapeEditOptions.value.map((s: any) => ({
    value: s.shape,
    label: `${s.shape}${s.processorDescription ? ` — ${s.processorDescription}` : ''}`,
  })),
)

const shapeEditSelectedMeta = computed(() =>
  shapeEditOptions.value.find((s: any) => s.shape === shapeForm.shape) ?? null,
)

const shapeEditFlexLimits = computed(() =>
  resolveShapeEditFlexLimits(shapeForm.shape, shapeEditSelectedMeta.value),
)
const shapeOcpuMin = computed(() => shapeEditFlexLimits.value.minOcpus)
const shapeOcpuMax = computed(() => shapeEditFlexLimits.value.maxOcpus)
const shapeMemoryMin = computed(() => shapeEditFlexLimits.value.minMemory)
const shapeMemoryMax = computed(() => shapeEditFlexLimits.value.maxMemory)
const shapeOcpuLabel = computed(() =>
  formatShapeResourceRangeLabel('OCPU', shapeOcpuMin.value, shapeOcpuMax.value),
)
const shapeMemoryLabel = computed(() =>
  formatShapeResourceRangeLabel('内存 GB', shapeMemoryMin.value, shapeMemoryMax.value),
)
const shapeEditTaskActive = computed(() => !!shapeEditTask.value && !shapeEditTask.value.terminal)
const shapeEditTaskPaused = computed(() => shapeEditTask.value?.status === 'PAUSED')
const shapeEditTaskPercent = computed(() => {
  const task = shapeEditTask.value
  if (!task) return 0
  if (task.status === 'SUCCESS') return 100
  if (task.status === 'FAILED' || task.status === 'STOPPED') return 100
  if (!task.maxRetries) return 0
  return Math.min(99, Math.round((task.retryCount / task.maxRetries) * 100))
})
const shapeEditTaskProgressStatus = computed(() => {
  const status = shapeEditTask.value?.status
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED' || status === 'STOPPED') return 'exception'
  return 'active'
})
const shapeEditTaskTitle = computed(() => {
  const status = shapeEditTask.value?.status
  if (status === 'SUCCESS') return '形状变更成功'
  if (status === 'FAILED') return '形状变更失败'
  if (status === 'STOPPED') return '后台重试已停止'
  if (status === 'PAUSED') return '后台重试已暂停'
  return '检测到缺货，后台自动重试中'
})
const shapeEditTaskAlertType = computed(() => {
  const status = shapeEditTask.value?.status
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED' || status === 'STOPPED') return 'error'
  return 'warning'
})
const shapeEditTaskMessage = computed(() => {
  const task = shapeEditTask.value
  if (!task) return ''
  if (task.status === 'RUNNING') return `重试中 (第 ${task.retryCount} 次)`
  if (task.status === 'PAUSED') return '已暂停'
  if (task.status === 'FAILED') return task.message || '失败'
  return task.message || '等待中...'
})

function regionParam(): { region?: string } {
  const r =
    (props.region && String(props.region).trim()) ||
    (props.instance?.region && String(props.instance.region).trim()) ||
    (props.tenant?.ociRegion && String(props.tenant.ociRegion).trim()) ||
    ''
  return r ? { region: r } : {}
}

function currentTargetKey() {
  const scope = regionParam()
  return [
    props.tenant?.id || '',
    props.instance?.instanceId || '',
    scope.region || '',
  ].join('|')
}

function sameTarget(targetKey: string) {
  return currentTargetKey() === targetKey
}

function taskMatchesCurrent(status: ShapeEditTaskStatus) {
  if (props.tenant?.id && status.tenantId && status.tenantId !== props.tenant.id) return false
  if (props.instance?.instanceId && status.instanceId && status.instanceId !== props.instance.instanceId) return false
  const currentRegion = regionParam().region
  if (currentRegion && status.region && status.region !== currentRegion) return false
  return true
}

function clampShapeNum(v: number, min?: number | null, max?: number | null) {
  let n = v
  if (min != null && n < min) n = min
  if (max != null && n > max) n = max
  return n
}

function filterShapeOption(input: string, option: any) {
  const label = option?.label ?? option?.value ?? ''
  return String(label).toLowerCase().includes(input.toLowerCase())
}

function onShapeFormShapeChange() {
  const meta = shapeEditSelectedMeta.value
  if (!meta?.isFlexible) return
  const lim = resolveShapeEditFlexLimits(shapeForm.shape, meta)
  const inst = props.instance
  const flexDefault = getFlexShapeSpec(shapeForm.shape)
  if (inst?.shape === shapeForm.shape) {
    const o = inst.ocpus ?? flexDefault?.ocpus ?? lim.minOcpus
    const m = inst.memoryInGBs ?? flexDefault?.memory ?? lim.minMemory
    shapeForm.ocpus = clampShapeNum(Number(o) || 1, lim.minOcpus, lim.maxOcpus)
    shapeForm.memoryInGBs = clampShapeNum(Number(m) || 6, lim.minMemory, lim.maxMemory)
  } else if (flexDefault) {
    shapeForm.ocpus = flexDefault.ocpus
    shapeForm.memoryInGBs = flexDefault.memory
  } else {
    shapeForm.ocpus = clampShapeNum(Number(meta.ocpuMin) || 1, lim.minOcpus, lim.maxOcpus)
    shapeForm.memoryInGBs = clampShapeNum(Number(meta.memoryMinInGBs) || 6, lim.minMemory, lim.maxMemory)
  }
}

async function loadOptions(force = false) {
  if (!props.instance || !props.tenant) return
  if (shapeEditLoading.value && !force) return
  const requestId = ++shapeLoadSeq
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const targetKey = currentTargetKey()
  const scope = regionParam()
  shapeEditLoading.value = true
  try {
    const res = await getShapesForInstance({
      id: tenantId,
      instanceId,
      ...scope,
      force,
    })
    if (requestId !== shapeLoadSeq || !sameTarget(targetKey)) return
    shapeEditOptions.value = res.data || []
    const cur = props.instance?.shape
    shapeForm.shape = shapeEditOptions.value.some((s: any) => s.shape === cur)
      ? cur
      : (shapeEditOptions.value[0]?.shape ?? '')
    shapeForm.ocpus = props.instance?.ocpus ?? 1
    shapeForm.memoryInGBs = props.instance?.memoryInGBs ?? 6
    onShapeFormShapeChange()
  } catch (e: any) {
    if (requestId === shapeLoadSeq && sameTarget(targetKey)) message.error(e?.message || '加载 Shape 列表失败')
  } finally {
    if (requestId === shapeLoadSeq && sameTarget(targetKey)) shapeEditLoading.value = false
  }
}

function isShapeEditTaskStatusData(data: any): data is ShapeEditTaskStatus {
  return !!data && typeof data.taskId === 'string' && typeof data.status === 'string'
}

function stopShapeEditTaskPolling() {
  if (shapeEditTaskPollTimer != null) {
    clearInterval(shapeEditTaskPollTimer)
    shapeEditTaskPollTimer = null
  }
}

function startShapeEditTaskPolling(taskId: string) {
  if (!taskId) return
  stopShapeEditTaskPolling()
  shapeEditTaskPollTimer = setInterval(() => {
    void pollShapeEditTask(taskId)
  }, 2000)
}

function revealShapeEditTaskAlert() {
  emit('focus-panel')
  void nextTick(() => {
    const el = shapeEditTaskAlertRef.value?.$el ?? shapeEditTaskAlertRef.value
    el?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' })
  })
}

function applyShapeEditResult(result?: Record<string, any>) {
  if (!result) return
  emit('instance-updated', result)
  void loadOptions()
  emit('reload-instance-list')
}

function handleShapeEditTaskStatus(status: ShapeEditTaskStatus) {
  if (!taskMatchesCurrent(status)) return
  const previousStatus = shapeEditTask.value?.status
  const previousTaskId = shapeEditTask.value?.taskId
  shapeEditTask.value = status
  if (previousTaskId !== status.taskId) {
    revealShapeEditTaskAlert()
  }
  if (!status.terminal) return

  stopShapeEditTaskPolling()
  if (previousStatus === status.status) return

  if (status.status === 'SUCCESS') {
    applyShapeEditResult(status.result)
    message.success('形状变更成功')
  } else if (status.status === 'FAILED') {
    message.error(status.message || '形状变更失败')
  } else if (status.status === 'STOPPED') {
    message.warning(status.message || '后台重试已停止')
  }
}

async function pollShapeEditTask(taskId: string) {
  try {
    const res = await getShapeEditTaskStatus(taskId)
    if (!isShapeEditTaskStatusData(res.data)) return
    if (shapeEditTask.value?.taskId && shapeEditTask.value.taskId !== taskId) return
    handleShapeEditTaskStatus(res.data)
  } catch (e: any) {
    stopShapeEditTaskPolling()
    if (shapeEditTask.value && !shapeEditTask.value.terminal) {
      shapeEditTask.value = {
        ...shapeEditTask.value,
        status: 'FAILED',
        pending: false,
        terminal: true,
        message: e?.message || '后台任务状态查询失败',
      }
    }
    message.error(e?.message || '后台任务状态查询失败')
  }
}

async function handleToggleShapeEditTaskPause() {
  const task = shapeEditTask.value
  if (!task || task.terminal) return
  shapeEditTaskActionLoading.value = true
  try {
    const res = shapeEditTaskPaused.value
      ? await resumeShapeEditTask(task.taskId)
      : await pauseShapeEditTask(task.taskId)
    if (isShapeEditTaskStatusData(res.data)) {
      handleShapeEditTaskStatus(res.data)
      if (!res.data.terminal && !shapeEditTaskPollTimer) {
        startShapeEditTaskPolling(res.data.taskId)
      }
    }
    message.success(shapeEditTaskPaused.value ? '后台重试已暂停' : '后台重试已恢复')
  } catch (e: any) {
    message.error(e?.message || '操作后台任务失败')
  } finally {
    shapeEditTaskActionLoading.value = false
  }
}

async function handleStopShapeEditTask() {
  const task = shapeEditTask.value
  if (!task || task.terminal) return
  shapeEditTaskActionLoading.value = true
  try {
    const res = await stopShapeEditTask(task.taskId)
    if (isShapeEditTaskStatusData(res.data)) {
      handleShapeEditTaskStatus(res.data)
    }
  } catch (e: any) {
    message.error(e?.message || '停止后台任务失败')
  } finally {
    shapeEditTaskActionLoading.value = false
  }
}

function stopWithKeepalive() {
  const task = shapeEditTask.value
  if (!task || task.terminal) return
  try {
    const token = localStorage.getItem('token')?.trim()
    const headers: Record<string, string> = {}
    if (token) headers.Authorization = token.startsWith('Bearer ') ? token : `Bearer ${token}`
    void fetch(`/api/oci/instance/shapeEditTask/${encodeURIComponent(task.taskId)}/stop`, {
      method: 'POST',
      keepalive: true,
      credentials: 'include',
      headers,
    })
  } catch {
    // 页面卸载阶段无法可靠展示错误，静默处理即可。
  }
}

async function stopSilently() {
  const task = shapeEditTask.value
  if (!task || task.terminal) {
    stopShapeEditTaskPolling()
    return
  }
  stopShapeEditTaskPolling()
  shapeEditTask.value = null
  shapeEditTaskActionLoading.value = false
  try {
    await stopShapeEditTask(task.taskId)
  } catch {
    // 切换实例或销毁组件时静默清理，避免打断用户当前操作。
  }
}

function reset() {
  shapeLoadSeq += 1
  stopShapeEditTaskPolling()
  shapeEditLoading.value = false
  shapeEditSaving.value = false
  shapeEditTaskActionLoading.value = false
  shapeEditTask.value = null
  shapeEditOptions.value = []
  shapeForm.shape = ''
  shapeForm.ocpus = 1
  shapeForm.memoryInGBs = 6
}

async function handleApplyShapeEdit() {
  if (!props.instance || !props.tenant || !shapeForm.shape) return
  const meta = shapeEditSelectedMeta.value
  const inst = props.instance
  const tenantId = props.tenant.id
  const instanceId = inst.instanceId
  const targetKey = currentTargetKey()
  const payload: Record<string, unknown> = {
    id: tenantId,
    instanceId,
    ...regionParam(),
  }
  let changed = false
  if (shapeForm.shape !== inst.shape) {
    payload.shape = shapeForm.shape
    changed = true
  }
  if (meta?.isFlexible) {
    const ocpuChanged = shapeForm.ocpus !== inst.ocpus
    const memChanged = shapeForm.memoryInGBs !== inst.memoryInGBs
    if (changed || ocpuChanged || memChanged) {
      payload.ocpus = shapeForm.ocpus
      payload.memoryInGBs = shapeForm.memoryInGBs
      changed = true
    }
  } else if (!changed) {
    message.info('未检测到变更')
    return
  }
  if (!changed) {
    message.info('未检测到变更')
    return
  }
  shapeEditSaving.value = true
  try {
    const res = await updateInstance(payload as any)
    if (!sameTarget(targetKey)) return
    if (isShapeEditTaskStatusData(res.data)) {
      handleShapeEditTaskStatus(res.data)
      if (!res.data.terminal) {
        startShapeEditTaskPolling(res.data.taskId)
      }
      message.warning(res.data.message || '检测到缺货，将在后台自动重试')
      return
    }
    message.success('形状变更已提交')
    if (res.data) emit('instance-updated', res.data)
    emit('reload-instance-list')
  } catch (e: any) {
    if (sameTarget(targetKey)) message.error(e?.message || '形状变更失败')
  } finally {
    if (sameTarget(targetKey)) shapeEditSaving.value = false
  }
}

function handleBeforeUnload() {
  stopWithKeepalive()
}

watch(
  () => currentTargetKey(),
  (target, oldTarget) => {
    if (!oldTarget || target === oldTarget) return
    void stopSilently()
    reset()
    if (props.active) void loadOptions()
  },
)

watch(
  () => props.active,
  (active) => {
    if (active && !shapeEditOptions.value.length && !shapeEditLoading.value) {
      void loadOptions()
    }
  },
  { immediate: true },
)

onMounted(() => {
  window.addEventListener('beforeunload', handleBeforeUnload)
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
  void stopSilently()
  stopShapeEditTaskPolling()
})

defineExpose({
  loadOptions,
  reset,
  stopSilently,
})
</script>

<style scoped>
.shape-edit-task-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.shape-edit-task-message {
  margin-top: 6px;
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.5;
}
</style>
