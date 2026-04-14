<template>
  <a-config-provider :locale="zhCN" :theme="antdTheme">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { theme as antTheme } from 'ant-design-vue'
import { useThemeStore } from './stores/theme'

const themeStore = useThemeStore()

onMounted(() => {
  themeStore.init()
})

const antdTheme = computed(() => ({
  algorithm: themeStore.isDark ? antTheme.darkAlgorithm : antTheme.defaultAlgorithm,
  token: {
    colorPrimary: '#6366f1',
    colorLink: '#818cf8',
    colorLinkHover: '#6366f1',
    colorSuccess: '#10b981',
    colorWarning: '#f59e0b',
    colorError: '#ef4444',
    borderRadius: 12,
    fontFamily: "'Inter', system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
    colorBgContainer: themeStore.isDark ? 'rgba(30, 41, 59, 0.4)' : '#ffffff',
    colorBgElevated: themeStore.isDark ? '#0f172a' : '#ffffff',
    colorBorder: themeStore.isDark ? 'rgba(255,255,255,0.06)' : 'rgba(15,23,42,0.08)',
    colorText: themeStore.isDark ? '#f1f5f9' : '#0f172a',
    colorTextSecondary: themeStore.isDark ? '#94a3b8' : '#475569',
  },
}))
</script>
