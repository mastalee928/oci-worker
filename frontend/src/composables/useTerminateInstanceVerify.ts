import { ref, type Ref } from 'vue'
import { message } from 'ant-design-vue'
import { terminateInstance } from '../api/instance'
import { sendVerifyCode } from '../api/system'

type RegionParam = Record<string, any>

interface UseTerminateInstanceVerifyOptions {
  currentTenant: Ref<any>
  currentInstance: Ref<any>
  resolveRegionParam: () => RegionParam
  onTerminated: (tenant: any, instance: any) => void | Promise<void>
}

export function useTerminateInstanceVerify(options: UseTerminateInstanceVerifyOptions) {
  const verifyModalVisible = ref(false)
  const verifyCode = ref('')
  const verifyLoading = ref(false)
  const verifySending = ref(false)
  const deleteBootVolume = ref(true)

  function terminateVerifyContext(tenant: any, instance: any) {
    const region = String(options.resolveRegionParam()?.region || '').trim()
    const tenantId = String(tenant?.id || '').trim()
    const instanceId = String(instance?.instanceId || '').trim()
    const instanceName = String(instance?.name || instance?.displayName || instanceId || '未知实例').trim()
    const tenantName = String(tenant?.username || tenant?.tenantName || tenantId || '未知租户').trim()
    return {
      contextKey: [tenantId, instanceId, region].join('|'),
      contextText: `${tenantName} / ${instanceName}${region ? ` / ${region}` : ''}`,
    }
  }

  async function openTerminateVerify(tenant: any, record: any) {
    if (verifySending.value || verifyLoading.value) return
    if (!tenant?.id || !record?.instanceId) {
      message.error('终止目标不存在，请重新打开验证')
      return
    }
    options.currentTenant.value = tenant
    options.currentInstance.value = record
    verifyCode.value = ''
    deleteBootVolume.value = true
    verifySending.value = true
    try {
      await sendVerifyCode('terminate', terminateVerifyContext(tenant, record))
      message.success('验证码已发送至 Telegram')
      verifyModalVisible.value = true
    } catch (e: any) {
      message.error(e?.message || '发送验证码失败')
    } finally {
      verifySending.value = false
    }
  }

  async function resendTerminateVerifyCode() {
    if (verifySending.value || verifyLoading.value) return
    const tenant = options.currentTenant.value
    const instance = options.currentInstance.value
    if (!tenant?.id || !instance?.instanceId) {
      message.error('终止目标不存在，请重新打开验证')
      return
    }
    verifySending.value = true
    try {
      await sendVerifyCode('terminate', terminateVerifyContext(tenant, instance))
      message.success('验证码已重新发送')
    } catch (e: any) {
      message.error(e?.message || '发送失败')
    } finally {
      verifySending.value = false
    }
  }

  async function handleTerminateWithCode() {
    if (verifyLoading.value) return
    const code = verifyCode.value.trim()
    if (!code || code.length !== 6) {
      message.warning('请输入6位验证码')
      return
    }

    const tenant = options.currentTenant.value
    const instance = options.currentInstance.value
    if (!tenant || !instance) {
      message.error('终止目标不存在，请重新打开验证')
      return
    }

    verifyLoading.value = true
    try {
      await terminateInstance({
        id: tenant.id,
        instanceId: instance.instanceId,
        verifyCode: code,
        preserveBootVolume: !deleteBootVolume.value,
        ...options.resolveRegionParam(),
      })
    } catch (e: any) {
      message.error(e?.message || '终止失败')
      return
    } finally {
      verifyLoading.value = false
    }

    message.success('实例已终止')
    verifyCode.value = ''
    verifyModalVisible.value = false
    try {
      await options.onTerminated(tenant, instance)
    } catch {
      message.warning('实例已终止，但列表刷新失败，请手动刷新')
    }
  }

  return {
    verifyModalVisible,
    verifyCode,
    verifyLoading,
    verifySending,
    deleteBootVolume,
    openTerminateVerify,
    resendTerminateVerifyCode,
    handleTerminateWithCode,
  }
}
