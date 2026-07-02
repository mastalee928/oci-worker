import { computed, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  getInstanceList,
  updateInstanceState,
  changeIp,
} from '../api/instance'
import {
  INSTANCE_CONFIRM_MODAL_WRAP_CLASS,
  INSTANCE_CONFIRM_MODAL_Z_INDEX,
} from '../utils/overlayZIndex'

interface UseInstanceActionsOptions {
  getActiveTenantData: () => any | null
  getCurrentTenant: () => any | null
  getCurrentInstance: () => any | null
  setCurrentInstance: (instance: any) => void
  findTenantDataById: (tenantId: string) => any | undefined
  getInstancePanelRegion: () => string
  resolveDetailRegionParam: () => { region?: string }
  resolveDetailScopeParam: () => { region?: string; compartmentId?: string }
  scheduleReload: (fn: () => void, delay: number) => void
  loadTenantInstances: (td: any, options?: any) => Promise<unknown> | unknown
  loadNetworkDetail: () => Promise<unknown> | unknown
  openTerminateVerify: (tenant: any, record: any) => void
  setConfirmOverlayActive: (active: boolean) => void
}

const INSTANCE_ACTION_LABELS: Record<string, string> = {
  START: '启动',
  SOFTRESET: '重启',
  RESET: '断电重启',
  SOFTSTOP: '暂停',
}

export function useInstanceActions(options: UseInstanceActionsOptions) {
  const actionLoading = reactive<Record<string, boolean>>({})
  const changeIpLoadingOwner = ref('')
  const instanceInfoLoadingOwner = ref('')
  const changeIpLoading = computed(() => {
    const instanceId = String(options.getCurrentInstance()?.instanceId || '')
    return !!instanceId && changeIpLoadingOwner.value === instanceId
  })
  const instanceInfoLoading = computed(() => {
    const instanceId = String(options.getCurrentInstance()?.instanceId || '')
    return !!instanceId && instanceInfoLoadingOwner.value === instanceId
  })

  let actionRequestGen = 0
  let changeIpRequestGen = 0
  let refreshInfoRequestGen = 0
  const actionRequestByInstance: Record<string, number> = {}

  function isCurrentInstance(tenantId: string, instanceId: string) {
    const tenant = options.getCurrentTenant()
    const instance = options.getCurrentInstance()
    return String(tenant?.id || '') === tenantId && String(instance?.instanceId || '') === instanceId
  }

  async function handleAction(tenant: any, record: any, action: string) {
    const tenantId = String(tenant?.id || '')
    const instanceId = String(record?.instanceId || '')
    if (!tenantId || !instanceId) return
    const gen = ++actionRequestGen
    actionRequestByInstance[instanceId] = gen
    actionLoading[instanceId] = true
    try {
      const reg =
        (record.region && String(record.region).trim()) ||
        (options.getInstancePanelRegion()?.trim() || tenant.ociRegion || '').trim()
      await updateInstanceState({ id: tenantId, instanceId, action, region: reg })
      message.success('操作已提交')
      const td = options.findTenantDataById(tenantId)
      if (td) options.scheduleReload(() => options.loadTenantInstances(td, { force: true }), 3000)
    } catch (e: any) {
      message.error(e?.message || '操作失败')
    } finally {
      if (actionRequestByInstance[instanceId] === gen) {
        actionLoading[instanceId] = false
        delete actionRequestByInstance[instanceId]
      }
    }
  }

  function onInstanceMenuClick(record: any, key: string) {
    const td = options.getActiveTenantData()
    if (!td?.tenant || !record) return
    const tenant = td.tenant
    if (key === 'TERMINATE') {
      options.openTerminateVerify(tenant, record)
      return
    }
    const label = INSTANCE_ACTION_LABELS[key] || key
    const danger = key === 'RESET' || key === 'SOFTSTOP'
    options.setConfirmOverlayActive(true)
    Modal.confirm({
      title: `确定${label}实例？`,
      content: `目标实例：${record.name || record.instanceId}`,
      okText: '确定',
      okButtonProps: danger ? { danger: true } : undefined,
      cancelText: '取消',
      zIndex: INSTANCE_CONFIRM_MODAL_Z_INDEX,
      wrapClassName: INSTANCE_CONFIRM_MODAL_WRAP_CLASS,
      onOk: () => handleAction(tenant, record, key),
      afterClose: () => {
        options.setConfirmOverlayActive(false)
      },
    })
  }

  function handleCurrentInstanceAction(action: 'START' | 'STOP' | 'RESET') {
    const tenant = options.getCurrentTenant()
    const instance = options.getCurrentInstance()
    if (!tenant || !instance) return
    void handleAction(tenant, instance, action)
  }

  function stopCurrentDetailInstance() {
    const tenant = options.getCurrentTenant()
    const instance = options.getCurrentInstance()
    if (!tenant || !instance) return
    return handleAction(tenant, instance, 'STOP')
  }

  async function handleChangeIp() {
    const tenant = options.getCurrentTenant()
    const instance = options.getCurrentInstance()
    const tenantId = String(tenant?.id || '')
    const instanceId = String(instance?.instanceId || '')
    if (!tenantId || !instanceId) return
    const gen = ++changeIpRequestGen
    changeIpLoadingOwner.value = instanceId
    try {
      await changeIp({
        id: tenantId,
        instanceId,
        ...options.resolveDetailScopeParam(),
      })
      if (!isCurrentInstance(tenantId, instanceId)) return
      message.success('换 IP 请求已提交')
      options.scheduleReload(() => { void options.loadNetworkDetail() }, 3000)
    } catch (e: any) {
      if (!isCurrentInstance(tenantId, instanceId)) return
      message.error(e?.message || '换 IP 失败')
    } finally {
      if (gen === changeIpRequestGen) changeIpLoadingOwner.value = ''
    }
  }

  async function refreshInstanceInfo() {
    const tenant = options.getCurrentTenant()
    const instance = options.getCurrentInstance()
    const tenantId = String(tenant?.id || '')
    const instanceId = String(instance?.instanceId || '')
    if (!tenantId || !instanceId) return
    const gen = ++refreshInfoRequestGen
    instanceInfoLoadingOwner.value = instanceId
    try {
      const res = await getInstanceList({
        id: tenantId,
        ...options.resolveDetailRegionParam(),
        force: true,
      })
      if (!isCurrentInstance(tenantId, instanceId)) return
      const fresh = (res.data || []).find((i: any) => i.instanceId === instanceId)
      if (!fresh) {
        message.warning('实例不存在或已终止')
        return
      }
      const current = options.getCurrentInstance()
      options.setCurrentInstance({ ...current, ...fresh })
      const td = options.findTenantDataById(tenantId)
      if (td) {
        const idx = td.instances.findIndex((i: any) => i.instanceId === instanceId)
        if (idx >= 0) td.instances[idx] = { ...td.instances[idx], ...fresh }
      }
      message.success('实例信息已刷新')
    } catch (e: any) {
      if (!isCurrentInstance(tenantId, instanceId)) return
      message.error(e?.message || '刷新实例信息失败')
    } finally {
      if (gen === refreshInfoRequestGen) instanceInfoLoadingOwner.value = ''
    }
  }

  return {
    actionLoading,
    changeIpLoading,
    instanceInfoLoading,
    onInstanceMenuClick,
    handleCurrentInstanceAction,
    stopCurrentDetailInstance,
    handleChangeIp,
    refreshInstanceInfo,
  }
}
