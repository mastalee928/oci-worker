import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { createConsoleConnection, deleteConsoleConnection } from '../api/instance'
import { getPanelToken, setWebSshTokenCookie } from '../utils/session'

interface UseInstanceConsoleOptions {
  getTenant: () => any | null
  getInstance: () => any | null
  resolveRegionParam: () => { region?: string }
}

export function useInstanceConsole(options: UseInstanceConsoleOptions) {
  const consoleLoading = ref(false)
  const consoleData = ref<any>(null)
  let consoleRequestGen = 0

  function clearConsoleData() {
    consoleRequestGen += 1
    consoleData.value = null
    consoleLoading.value = false
  }

  function isCurrentConsoleRequest(gen: number, tenantId: string, instanceId?: string) {
    if (gen !== consoleRequestGen) return false
    const tenant = options.getTenant()
    const instance = options.getInstance()
    if (String(tenant?.id || '') !== tenantId) return false
    return !instanceId || String(instance?.instanceId || '') === instanceId
  }

  function openConsoleWebSSH() {
    if (!consoleData.value?.connectionId) return
    const tenant = options.getTenant()
    const instance = options.getInstance()
    const label = instance?.displayName || instance?.instanceId || 'Serial Console'
    const params = new URLSearchParams({
      console: '1',
      connectionId: consoleData.value.connectionId,
      label,
    })
    if (tenant?.id != null) params.set('userId', String(tenant.id))
    if (instance?.instanceId) params.set('instanceId', instance.instanceId)
    const region = options.resolveRegionParam().region
    if (region) params.set('region', region)
    if (instance?.state) params.set('state', instance.state)
    setWebSshTokenCookie(getPanelToken())
    window.open('/webssh/index.html#' + params.toString(), '_blank')
  }

  async function handleCreateConsole() {
    const instance = options.getInstance()
    const tenant = options.getTenant()
    if (!instance || !tenant) return
    const tenantId = String(tenant.id || '')
    const instanceId = String(instance.instanceId || '')
    const gen = ++consoleRequestGen
    consoleLoading.value = true
    try {
      const res = await createConsoleConnection({
        id: tenant.id,
        instanceId: instance.instanceId,
        ...options.resolveRegionParam(),
      })
      if (!isCurrentConsoleRequest(gen, tenantId, instanceId)) return
      consoleData.value = res.data
      message.success('控制台连接已创建')
      openConsoleWebSSH()
    } catch (e: any) {
      if (gen === consoleRequestGen) message.error(e?.message || '创建控制台连接失败')
    } finally {
      if (gen === consoleRequestGen) consoleLoading.value = false
    }
  }

  async function handleDeleteConsole() {
    const tenant = options.getTenant()
    if (!consoleData.value || !tenant) return
    const tenantId = String(tenant.id || '')
    const gen = ++consoleRequestGen
    consoleLoading.value = true
    try {
      await deleteConsoleConnection({
        id: tenant.id,
        connectionId: consoleData.value.connectionId,
        ...options.resolveRegionParam(),
      })
      if (!isCurrentConsoleRequest(gen, tenantId)) return
      clearConsoleData()
      message.success('控制台连接已断开')
    } catch (e: any) {
      if (gen === consoleRequestGen) message.error(e?.message || '断开连接失败')
    } finally {
      if (gen === consoleRequestGen) consoleLoading.value = false
    }
  }

  return {
    consoleLoading,
    consoleData,
    clearConsoleData,
    handleCreateConsole,
    openConsoleWebSSH,
    handleDeleteConsole,
  }
}
