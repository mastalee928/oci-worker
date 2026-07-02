import { computed, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { forceA2ToA1 } from '../api/instance'

interface UseForceA2ToA1Options {
  getTenant: () => any | null
  getInstance: () => any | null
  resolveRegionParam: () => { region?: string }
  refreshShapeOptions: () => Promise<unknown> | unknown
  reloadCurrentTenantInstances: () => void
}

export function useForceA2ToA1(options: UseForceA2ToA1Options) {
  const forceA2ModalVisible = ref(false)
  const forceA2Loading = ref(false)
  const forceA2Q = reactive({
    trial: undefined as boolean | undefined,
    a2Shape: undefined as boolean | undefined,
    risk: undefined as boolean | undefined,
  })
  const forceA2AllYes = computed(
    () => forceA2Q.trial === true && forceA2Q.a2Shape === true && forceA2Q.risk === true,
  )
  const showForceA2ToA1Button = computed(
    () => options.getInstance()?.shape === 'VM.Standard.A2.Flex',
  )

  let forceA2RequestGen = 0

  function resetForceA2Modal() {
    forceA2Q.trial = undefined
    forceA2Q.a2Shape = undefined
    forceA2Q.risk = undefined
  }

  function openForceA2ToA1Modal() {
    if (!options.getInstance()) return
    resetForceA2Modal()
    forceA2ModalVisible.value = true
  }

  function isCurrentForceA2Request(gen: number, tenantId: string, instanceId: string) {
    if (gen !== forceA2RequestGen) return false
    const tenant = options.getTenant()
    const instance = options.getInstance()
    return String(tenant?.id || '') === tenantId && String(instance?.instanceId || '') === instanceId
  }

  async function handleForceA2ToA1Confirm() {
    if (!forceA2AllYes.value) {
      message.warning('请三项均选择「是」后再执行')
      return Promise.reject()
    }
    const instance = options.getInstance()
    const tenant = options.getTenant()
    if (!instance || !tenant) return Promise.reject()
    const tenantId = String(tenant.id || '')
    const instanceId = String(instance.instanceId || '')
    if (!tenantId || !instanceId) return Promise.reject()
    const gen = ++forceA2RequestGen
    forceA2Loading.value = true
    try {
      const res = await forceA2ToA1({
        id: tenant.id,
        instanceId: instance.instanceId,
        ...options.resolveRegionParam(),
      })
      if (!isCurrentForceA2Request(gen, tenantId, instanceId)) return Promise.reject()
      message.success('已成功转为A1，稍后刷新页面后在基本信息中查看')
      const inst = options.getInstance()
      if (inst) {
        if (res.data?.shape) inst.shape = res.data.shape
        if (res.data?.ocpus != null) inst.ocpus = res.data.ocpus
        if (res.data?.memoryInGBs != null) inst.memoryInGBs = res.data.memoryInGBs
        if (res.data?.name) inst.name = res.data.name
      }
      forceA2ModalVisible.value = false
      resetForceA2Modal()
      await options.refreshShapeOptions()
      options.reloadCurrentTenantInstances()
    } catch (e: any) {
      if (!isCurrentForceA2Request(gen, tenantId, instanceId)) return Promise.reject()
      const msg = String(e?.message || '')
      if (msg.includes('当前实例 Shape 不是') && msg.includes('请检查当前 Shape')) {
        Modal.error({ title: '无法执行强改', content: msg, okText: '知道了' })
      } else {
        message.error('本次更改失败，您可再次尝试！')
      }
      return Promise.reject()
    } finally {
      if (gen === forceA2RequestGen) forceA2Loading.value = false
    }
  }

  return {
    showForceA2ToA1Button,
    forceA2ModalVisible,
    forceA2Loading,
    forceA2Q,
    forceA2AllYes,
    resetForceA2Modal,
    openForceA2ToA1Modal,
    handleForceA2ToA1Confirm,
  }
}
