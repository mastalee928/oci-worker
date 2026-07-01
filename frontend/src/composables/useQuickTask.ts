import { computed, reactive, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { getAvailableShapes } from '../api/instance'
import { createTask, hasRunningTask } from '../api/task'
import { loadOciRegionCatalog } from '../utils/ociRegionCatalog'
import {
  applyTaskMemoryInput,
  applyTaskOcpusInput,
  applyTaskShapeDefaults,
  clampTaskShapeResources,
  isFixedTaskShapeSpec,
  resolveTaskShapeLimits,
  taskMemoryFieldLabel,
  taskOcpuFieldLabel,
  validateDenseIoFlexTier,
} from '../constants/ociBmShapeSpecs'
import { useDenseIoFlexTier } from './useDenseIoFlexTier'
import { snapBootVpusPerGb } from '../utils/bootVolume'
import { SHAPE_E2_MICRO, TASK_ARM_SHAPE, normalizeTaskArchitecture } from '../utils/shapeSeries'
import {
  QUICK_TASK_CONFIRM_MODAL_WRAP_CLASS,
  QUICK_TASK_CONFIRM_MODAL_Z_INDEX,
} from '../utils/overlayZIndex'

export function useQuickTask() {
  const quickTaskVisible = ref(false)
  const quickTaskLoading = ref(false)
  const quickTaskTenant = ref<any>(null)
  const quickTaskShapes = ref<any[]>([])
  const quickTaskShapesLoading = ref(false)
  const quickTaskForm = reactive({
    ociRegion: undefined as string | undefined,
    architecture: TASK_ARM_SHAPE,
    operationSystem: 'Ubuntu',
    ocpus: 1,
    memory: 6,
    disk: 50,
    vpusPerGB: 10,
    createNumbers: 1,
    interval: 60,
    rootPassword: '',
    customScript: '',
    assignPublicIp: true,
    assignIpv6: false,
  })

  const quickTaskBmLocked = ref(false)
  const quickTaskShapeLimits = computed(() =>
    resolveTaskShapeLimits(quickTaskForm.architecture, quickTaskShapes.value),
  )
  const quickTaskOcpuLabel = computed(() =>
    taskOcpuFieldLabel(quickTaskForm.architecture, quickTaskShapes.value),
  )
  const quickTaskMemoryLabel = computed(() =>
    taskMemoryFieldLabel(quickTaskForm.architecture, quickTaskShapes.value),
  )
  const {
    tiers: quickDenseIoTiers,
    tierKey: quickDenseIoTierKey,
    formatDenseIoTierLabel,
    denseIoFlexTierKey,
  } = useDenseIoFlexTier(quickTaskForm)

  let quickTaskShapeLoadGen = 0

  function quickTaskPopupContainer(triggerNode?: HTMLElement) {
    return (triggerNode?.closest('.quick-task-modal-wrap') as HTMLElement | null) || document.body
  }

  function resolveQuickTaskRegion() {
    return quickTaskForm.ociRegion?.trim() || quickTaskTenant.value?.ociRegion?.trim() || ''
  }

  async function loadQuickTaskShapes() {
    const tid = quickTaskTenant.value?.id
    if (!tid) {
      quickTaskShapes.value = []
      return
    }
    const gen = ++quickTaskShapeLoadGen
    quickTaskShapesLoading.value = true
    try {
      const region = resolveQuickTaskRegion() || undefined
      const res = await getAvailableShapes({ id: tid, ...(region ? { region } : {}) })
      if (gen !== quickTaskShapeLoadGen) return
      quickTaskShapes.value = res.data || []
      quickTaskForm.architecture = normalizeTaskArchitecture(quickTaskForm.architecture)
      const arch = quickTaskForm.architecture
      const ok =
        arch === SHAPE_E2_MICRO ||
        quickTaskShapes.value.some((s: any) => s.shape === arch)
      if (!ok) quickTaskForm.architecture = TASK_ARM_SHAPE
      if (gen === quickTaskShapeLoadGen && isFixedTaskShapeSpec(quickTaskForm.architecture)) {
        quickTaskBmLocked.value = applyTaskShapeDefaults(quickTaskForm, quickTaskShapes.value)
      }
    } catch {
      if (gen === quickTaskShapeLoadGen) quickTaskShapes.value = []
    } finally {
      if (gen === quickTaskShapeLoadGen) quickTaskShapesLoading.value = false
    }
  }

  function buildQuickTaskPayload(region: string) {
    const architecture = normalizeTaskArchitecture(quickTaskForm.architecture)
    const vpusPerGB = snapBootVpusPerGb(quickTaskForm.vpusPerGB)
    quickTaskForm.architecture = architecture
    quickTaskForm.vpusPerGB = vpusPerGB
    return {
      userId: quickTaskTenant.value.id,
      ...quickTaskForm,
      architecture,
      ociRegion: region,
      vpusPerGB,
    }
  }

  function openQuickTask(tenant: any) {
    quickTaskTenant.value = tenant
    Object.assign(quickTaskForm, {
      ociRegion: tenant.ociRegion || undefined,
      architecture: TASK_ARM_SHAPE,
      operationSystem: 'Ubuntu',
      ocpus: 1,
      memory: 6,
      disk: 50,
      vpusPerGB: 10,
      createNumbers: 1,
      interval: 60,
      rootPassword: '',
      customScript: '',
      assignPublicIp: true,
      assignIpv6: false,
    })
    quickTaskBmLocked.value = false
    quickTaskVisible.value = true
    void loadOciRegionCatalog(tenant.id)
    void loadQuickTaskShapes()
  }

  function generateQuickTaskRandomPwd() {
    const chars = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%'
    let pwd = ''
    for (let i = 0; i < 16; i++) pwd += chars[Math.floor(Math.random() * chars.length)]
    quickTaskForm.rootPassword = pwd
    message.success('已生成随机密码')
  }

  function updateQuickTaskOcpus(value: number | null) {
    applyTaskOcpusInput(quickTaskForm, value, quickTaskShapes.value)
  }

  function updateQuickTaskMemory(value: number | null) {
    applyTaskMemoryInput(quickTaskForm, value, quickTaskShapes.value)
  }

  function clampQuickTaskResources() {
    clampTaskShapeResources(quickTaskForm, quickTaskShapes.value)
  }

  function snapQuickTaskBootVpus() {
    quickTaskForm.vpusPerGB = snapBootVpusPerGb(quickTaskForm.vpusPerGB)
  }

  async function doQuickTask(payload: any) {
    try {
      await createTask(payload)
      message.success('开机任务已创建')
      quickTaskVisible.value = false
    } catch (e: any) {
      message.error(e?.message || '创建任务失败')
    }
  }

  async function handleQuickTask() {
    if (quickTaskLoading.value) return
    if (!quickTaskTenant.value) return
    quickTaskLoading.value = true
    let waitingDuplicateConfirm = false
    try {
      const region = resolveQuickTaskRegion()
      if (!region) {
        message.error('请选择目标区域')
        return
      }
      quickTaskForm.ociRegion = region
      const denseErr = validateDenseIoFlexTier(quickTaskForm.architecture, quickTaskForm.ocpus, quickTaskForm.memory)
      if (denseErr) {
        message.error(denseErr)
        return
      }
      if (quickTaskForm.architecture?.includes('A2.Flex') && quickTaskForm.ocpus === 1 && quickTaskForm.memory === 1) {
        message.error('比例错误')
        return
      }

      if (!quickTaskForm.rootPassword) generateQuickTaskRandomPwd()
      const payload = buildQuickTaskPayload(region)

      try {
        const checkRes = await hasRunningTask({ userId: quickTaskTenant.value.id })
        if (checkRes.data === true) {
          waitingDuplicateConfirm = true
          Modal.confirm({
            title: '重复任务提醒',
            content: '该账户已有正在运行的开机任务，是否仍要重复提交？',
            okText: '继续创建',
            cancelText: '取消',
            zIndex: QUICK_TASK_CONFIRM_MODAL_Z_INDEX,
            wrapClassName: QUICK_TASK_CONFIRM_MODAL_WRAP_CLASS,
            onOk: () => doQuickTask(payload),
            onCancel: () => { quickTaskLoading.value = false },
            afterClose: () => { quickTaskLoading.value = false },
          })
          return
        }
      } catch {}

      await doQuickTask(payload)
    } finally {
      if (!waitingDuplicateConfirm) quickTaskLoading.value = false
    }
  }

  watch(
    () => quickTaskForm.architecture,
    (arch) => {
      if (arch == null || arch === undefined) return
      quickTaskBmLocked.value = applyTaskShapeDefaults(quickTaskForm, quickTaskShapes.value)
      clampTaskShapeResources(quickTaskForm, quickTaskShapes.value)
    },
  )

  watch(
    () => [quickTaskForm.ocpus, quickTaskForm.memory, quickTaskShapeLimits.value] as const,
    () => clampTaskShapeResources(quickTaskForm, quickTaskShapes.value),
  )

  watch(quickTaskShapes, () => {
    if (isFixedTaskShapeSpec(quickTaskForm.architecture)) {
      quickTaskBmLocked.value = applyTaskShapeDefaults(quickTaskForm, quickTaskShapes.value)
    }
    clampTaskShapeResources(quickTaskForm, quickTaskShapes.value)
  })

  watch(quickTaskVisible, (open) => {
    if (!open) {
      quickTaskShapes.value = []
      quickTaskShapeLoadGen++
      quickTaskShapesLoading.value = false
      quickTaskBmLocked.value = false
    }
  })

  watch(
    () => quickTaskForm.ociRegion,
    async () => {
      if (!quickTaskVisible.value || !quickTaskTenant.value?.id) return
      await loadQuickTaskShapes()
    },
  )

  return {
    quickTaskVisible,
    quickTaskLoading,
    quickTaskTenant,
    quickTaskShapes,
    quickTaskShapesLoading,
    quickTaskForm,
    quickTaskPopupContainer,
    quickTaskBmLocked,
    quickTaskShapeLimits,
    quickTaskOcpuLabel,
    quickTaskMemoryLabel,
    quickDenseIoTiers,
    quickDenseIoTierKey,
    formatDenseIoTierLabel,
    denseIoFlexTierKey,
    openQuickTask,
    generateQuickTaskRandomPwd,
    updateQuickTaskOcpus,
    updateQuickTaskMemory,
    clampQuickTaskResources,
    snapQuickTaskBootVpus,
    handleQuickTask,
  }
}
