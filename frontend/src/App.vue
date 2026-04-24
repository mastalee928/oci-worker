<template>
  <a-config-provider :locale="zhCN" :theme="antdTheme" :get-popup-container="popupContainer">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { theme as antTheme } from 'ant-design-vue'
import { useThemeStore } from './stores/theme'

const themeStore = useThemeStore()
const route = useRoute()

/** 挂到 body，避免侧栏 backdrop-filter/transform 导致折叠菜单 Tooltip 飘到内容区 */
function popupContainer() {
  return document.body
}

function applyOrbitBodyClass() {
  const p = route.path
  const auth = p === '/login' || p === '/setup'
  document.body.classList.toggle('orbit-app-shell', !auth)
  document.body.classList.toggle('orbit-auth', auth)
}

onMounted(() => {
  themeStore.init()
  applyOrbitBodyClass()
})

watch(() => route.path, applyOrbitBodyClass)

onUnmounted(() => {
  document.body.classList.remove('orbit-app-shell', 'orbit-auth')
})

/** 深色 = Orbis 色板（cream + neon）；浅色 = 原样 */
const antdTheme = computed(() => {
  if (themeStore.isDark) {
    return {
      algorithm: antTheme.darkAlgorithm,
      token: {
        colorPrimary: '#6fff00',
        colorLink: '#7fff50',
        colorLinkHover: '#5ee600',
        colorSuccess: '#10b981',
        colorWarning: '#f59e0b',
        colorError: '#ef4444',
        borderRadius: 12,
        fontFamily: "'Inter', ui-monospace, system-ui, 'Segoe UI', sans-serif",
        colorBgContainer: 'rgba(255, 255, 255, 0.06)',
        colorBgElevated: 'rgba(1, 8, 40, 0.92)',
        colorBorder: 'rgba(255, 255, 255, 0.1)',
        colorText: '#eff4ff',
        colorTextSecondary: 'rgba(239, 244, 255, 0.62)',
      },
    }
  }
  return {
    algorithm: antTheme.defaultAlgorithm,
    token: {
      colorPrimary: '#6366f1',
      colorLink: '#818cf8',
      colorLinkHover: '#6366f1',
      colorSuccess: '#10b981',
      colorWarning: '#f59e0b',
      colorError: '#ef4444',
      borderRadius: 12,
      fontFamily: "'Inter', system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
      colorBgContainer: '#ffffff',
      colorBgElevated: '#ffffff',
      colorBorder: 'rgba(15,23,42,0.08)',
      colorText: '#0f172a',
      colorTextSecondary: '#475569',
    },
  }
})
</script>
