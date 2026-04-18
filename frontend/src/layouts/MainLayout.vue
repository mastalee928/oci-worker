<template>
  <a-layout class="main-layout">
    <div v-if="mobileMenuOpen && isMobile" class="mobile-overlay" @click="mobileMenuOpen = false" />

    <a-layout-sider
      v-model:collapsed="collapsed"
      :trigger="null"
      collapsible
      :width="260"
      :collapsed-width="isMobile ? 0 : 64"
      :class="['sider', { 'sider-mobile': isMobile, 'sider-mobile-open': mobileMenuOpen && isMobile }]"
      :style="isMobile && !mobileMenuOpen ? { display: 'none' } : {}"
    >
      <div class="brand">
        <i class="ri-server-line brand-icon"></i>
        <span v-if="!collapsed || (isMobile && mobileMenuOpen)" class="brand-text">OCI Worker</span>
      </div>
      <a-menu mode="inline" :selected-keys="[currentRoute]" @click="handleMenuClick"
        class="nav-menu" theme="dark">
        <a-menu-item key="dashboard"><i class="ri-dashboard-3-line menu-ri"></i><span>仪表盘</span></a-menu-item>
        <a-menu-item key="tenant"><i class="ri-user-settings-line menu-ri"></i><span>租户配置</span></a-menu-item>
        <a-menu-item key="instance"><i class="ri-server-line menu-ri"></i><span>实例管理</span></a-menu-item>
        <a-menu-item key="task"><i class="ri-flashlight-line menu-ri"></i><span>开机任务</span></a-menu-item>
        <a-menu-item key="log"><i class="ri-file-list-3-line menu-ri"></i><span>日志查看</span></a-menu-item>
        <a-menu-item key="webssh"><i class="ri-terminal-box-line menu-ri"></i><span>WebSSH</span></a-menu-item>
        <a-menu-item key="settings"><i class="ri-settings-4-line menu-ri"></i><span>系统设置</span></a-menu-item>
      </a-menu>

      <div class="sidebar-footer">
        <div class="user-card">
          <div class="avatar">{{ avatarLetter }}</div>
          <div class="user-info">
            <div class="user-name">Admin</div>
            <div class="user-status">管理员在线</div>
          </div>
        </div>
        <button class="btn-logout" @click="handleLogout">
          <i class="ri-logout-box-r-line"></i> 安全退出
        </button>
      </div>
    </a-layout-sider>

    <a-layout class="content-layout">
      <header v-if="!isWebSSH" class="app-header">
        <div class="header-left">
          <button v-if="isMobile" class="header-btn trigger" @click="mobileMenuOpen = !mobileMenuOpen">
            <i class="ri-menu-line"></i>
          </button>
          <button v-else class="header-btn trigger" @click="collapsed = !collapsed">
            <i :class="collapsed ? 'ri-menu-unfold-line' : 'ri-menu-fold-line'"></i>
          </button>
          <h2 class="page-title">
            <i :class="pageTitleIcon"></i>
            {{ currentTitle }}
          </h2>
        </div>
        <div class="header-actions">
          <button class="header-btn" @click="themeStore.toggleWallpaper" title="切换壁纸背景">
            <i :class="themeStore.wallpaperActive ? 'ri-image-fill' : 'ri-image-line'"></i>
          </button>
          <button class="header-btn" @click="themeStore.toggleTheme" title="切换主题">
            <i :class="themeStore.isDark ? 'ri-sun-line' : 'ri-moon-line'"></i>
          </button>
        </div>
      </header>
      <div :class="['app-content', { 'no-padding': isWebSSH }]">
        <router-view />
      </div>
    </a-layout>

  </a-layout>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useThemeStore } from '../stores/theme'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const themeStore = useThemeStore()
const collapsed = ref(false)
const mobileMenuOpen = ref(false)
const isMobile = ref(false)

const avatarLetter = computed(() => 'A')

function checkMobile() {
  isMobile.value = window.innerWidth < 768
  if (isMobile.value) collapsed.value = true
}

onMounted(() => {
  checkMobile()
  window.addEventListener('resize', checkMobile)
})
onUnmounted(() => window.removeEventListener('resize', checkMobile))

const currentRoute = computed(() => route.path.split('/')[1] || 'dashboard')
const isWebSSH = computed(() => currentRoute.value === 'webssh')
const currentTitle = computed(() => {
  const r = router.getRoutes().find(r => r.name === route.name)
  return (r?.meta?.title as string) || 'OCI Worker'
})

const pageTitleIcon = computed(() => {
  const icons: Record<string, string> = {
    dashboard: 'ri-dashboard-3-line',
    tenant: 'ri-user-settings-line',
    instance: 'ri-server-line',
    task: 'ri-flashlight-line',
    log: 'ri-file-list-3-line',
    webssh: 'ri-terminal-box-line',
    settings: 'ri-settings-4-line',
    user: 'ri-group-line',
  }
  return icons[currentRoute.value] || 'ri-dashboard-3-line'
})

function handleMenuClick({ key }: { key: string }) {
  router.push('/' + key)
  if (isMobile.value) mobileMenuOpen.value = false
}

function handleLogout() {
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.main-layout { min-height: 100vh; }
.content-layout { min-width: 0; }

.sider {
  background: var(--bg-sidebar) !important;
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  transition: background 0.3s;
  position: sticky;
  top: 0;
  height: 100vh;
  align-self: flex-start;
  z-index: 10;
}
.sider :deep(.ant-layout-sider-children) {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}
.sider :deep(.ant-layout-sider-trigger),
.sider :deep(.ant-layout-sider-zero-width-trigger) {
  display: none !important;
}
.wallpaper-active .sider {
  background: rgba(15, 23, 42, 0.85) !important;
}
[data-theme="light"] .wallpaper-active .sider {
  background: rgba(255, 255, 255, 0.88) !important;
}
.sider-mobile {
  position: fixed !important;
  z-index: 1001;
  height: 100vh;
  left: 0;
  top: 0;
}
.sider-mobile-open {
  width: 260px !important;
  min-width: 260px !important;
  max-width: 260px !important;
  flex: 0 0 260px !important;
}

.brand {
  height: 80px;
  display: flex;
  align-items: center;
  padding: 0 24px;
  gap: 12px;
  flex-shrink: 0;
}
.brand-icon {
  font-size: 28px;
  color: #818cf8;
}
.brand-text {
  font-size: 20px;
  font-weight: 800;
  background: linear-gradient(135deg, #a5b4fc 0%, #6366f1 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  white-space: nowrap;
}

.nav-menu {
  flex: 1;
  padding: 12px 8px !important;
  overflow-y: auto;
  background: transparent !important;
  border: none !important;
}
.nav-menu :deep(.ant-menu-item) {
  text-align: left !important;
  padding-left: 24px !important;
}
.menu-ri {
  font-size: 20px;
  margin-right: 12px;
  vertical-align: -0.15em;
}

.sidebar-footer {
  flex-shrink: 0;
  padding: 12px 16px 16px;
}

.user-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  background: var(--bg-card);
  border-radius: 16px;
  border: 1px solid var(--border);
  margin-bottom: 10px;
  cursor: default;
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  box-shadow: var(--shadow-card);
}
.avatar {
  width: 42px;
  height: 42px;
  background: linear-gradient(135deg, #818cf8, #4f46e5);
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 700;
  font-size: 18px;
  box-shadow: 0 4px 10px rgba(99, 102, 241, 0.3);
  flex-shrink: 0;
}
.user-info { flex: 1; min-width: 0; }
.user-name { font-weight: 700; font-size: 14px; color: var(--text-main); }
.user-status { font-size: 12px; color: var(--text-sub); margin-top: 2px; }
.btn-logout {
  width: 100%;
  padding: 10px;
  border-radius: 10px;
  border: 1px solid rgba(239, 68, 68, 0.2);
  background: rgba(239, 68, 68, 0.05);
  color: #ef4444;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  transition: var(--trans);
  font-family: inherit;
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  box-shadow: var(--shadow-card);
}
.btn-logout:hover {
  background: rgba(239, 68, 68, 0.1);
  transform: translateY(-1px);
}

.app-header {
  height: 80px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  z-index: 40;
  flex-shrink: 0;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}
.page-title {
  font-weight: 800;
  font-size: 24px;
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--text-main);
  margin: 0;
  white-space: nowrap;
}
.page-title i {
  color: var(--primary);
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}
.header-btn {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-card);
  border: 1px solid var(--border);
  color: var(--text-main);
  cursor: pointer;
  transition: var(--trans);
  font-size: 18px;
  backdrop-filter: blur(12px);
}
.header-btn:hover {
  background: var(--input-bg);
  border-color: var(--primary);
  color: var(--primary);
  transform: translateY(-2px);
}
.trigger {
  font-size: 18px;
}

.app-content {
  flex: 1;
  padding: 0 32px 32px;
  overflow-y: auto;
  overflow-x: hidden;
  scroll-behavior: smooth;
}
.app-content.no-padding {
  padding: 0;
  overflow: hidden;
}

.mobile-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  z-index: 1000;
  backdrop-filter: blur(4px);
}

@media (max-width: 768px) {
  .app-header {
    padding: 0 16px;
    height: 64px;
  }
  .page-title {
    font-size: 18px;
  }
  .app-content {
    padding: 0 16px 24px;
  }
}
</style>
