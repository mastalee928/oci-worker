<template>
  <div class="cf-page">
    <a-alert
      v-if="!cfConfigured"
      type="warning"
      show-icon
      style="margin-bottom: 16px"
      message="尚未配置 Cloudflare"
      description="请先在「系统设置 → Cloudflare」填写 Account ID 与 API Token，并点击测试连接。"
    />

    <a-tabs v-model:active-key="topTab">
      <a-tab-pane key="zones" tab="域名">
        <CfZoneWorkspace :cf-configured="cfConfigured" @open-tunnel="onOpenTunnel" />
      </a-tab-pane>
      <a-tab-pane key="account" tab="账户服务">
        <CfAccountPanel ref="accountPanelRef" :cf-configured="cfConfigured" />
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'Cloudflare' })
import { ref, onMounted, nextTick } from 'vue'
import type { ComponentPublicInstance } from 'vue'
import { defineAppAsyncComponent } from '../utils/asyncComponent'
const CfZoneWorkspace = defineAppAsyncComponent(() => import('./cloudflare/CfZoneWorkspace.vue'), {
  loadingText: '正在载入域名工作区',
  loadingDescription: '读取账户配置并准备域名列表',
})
const CfAccountPanel = defineAppAsyncComponent(() => import('./cloudflare/CfAccountPanel.vue'), {
  loadingText: '正在载入账户服务',
  loadingDescription: '读取 Cloudflare 账户与服务信息',
})
import { getCfAccountConfig } from '../api/cloudflare'

const topTab = ref('zones')
const cfConfigured = ref(false)
const accountPanelRef = ref<ComponentPublicInstance<{
  openTunnelRoutesById: (tunnelId: string, tunnelName?: string, zoneId?: string) => Promise<void>
}> | null>(null)

async function onOpenTunnel(payload: { tunnelId: string; tunnelName?: string; zoneId?: string }) {
  topTab.value = 'account'
  await nextTick()
  await accountPanelRef.value?.openTunnelRoutesById(payload.tunnelId, payload.tunnelName, payload.zoneId)
}

async function loadCfConfig() {
  try {
    const res = await getCfAccountConfig()
    cfConfigured.value = res.data?.configured === true
  } catch {
    cfConfigured.value = false
  }
}

onMounted(loadCfConfig)
</script>

<style scoped>
.cf-page { min-height: 200px; }
</style>
