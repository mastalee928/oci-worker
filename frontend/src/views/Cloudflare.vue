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

    <a-tabs v-model:active-key="topTab" destroy-inactive-tab-pane>
      <a-tab-pane key="zones" tab="域名">
        <CfZoneWorkspace :cf-configured="cfConfigured" />
      </a-tab-pane>
      <a-tab-pane key="account" tab="账户服务">
        <p class="cf-top-hint">Tunnel 与 Workers 脚本为<strong>账户级</strong>资源，不绑定单个域名。</p>
        <CfAccountPanel :cf-configured="cfConfigured" />
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import CfZoneWorkspace from './cloudflare/CfZoneWorkspace.vue'
import CfAccountPanel from './cloudflare/CfAccountPanel.vue'
import { getCfAccountConfig } from '../api/cloudflare'

const topTab = ref('zones')
const cfConfigured = ref(false)

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
.cf-top-hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--text-sub);
}
</style>
