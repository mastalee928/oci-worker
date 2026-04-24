<template>
  <div class="orbit-main-wrap">
    <video
      v-if="themeStore.isDark && !themeStore.wallpaperActive"
      class="orbit-bg-video"
      :src="orbitVideo"
      autoplay
      loop
      muted
      playsinline
    />
    <div
      v-if="themeStore.isDark && !themeStore.wallpaperActive"
      class="orbit-texture-overlay"
      aria-hidden="true"
    />

    <a-layout :class="['main-layout', { 'main-layout--orbit': themeStore.isDark }]">
    <div v-if="mobileMenuOpen && isMobile" class="mobile-overlay" @click="mobileMenuOpen = false" />

    <a-layout-sider
      v-model:collapsed="collapsed"
      :trigger="null"
      collapsible
      :width="260"
      :collapsed-width="isMobile ? 0 : 64"
      :class="['sider', { 'liquid-glass': themeStore.isDark, 'sider-mobile': isMobile, 'sider-mobile-open': mobileMenuOpen && isMobile, 'sider-collapsed-desktop': collapsed && !isMobile }]"
      :style="isMobile && !mobileMenuOpen ? { display: 'none' } : {}"
    >
      <div class="brand">
        <i class="ri-server-line brand-icon"></i>
        <span v-if="!collapsed || (isMobile && mobileMenuOpen)" class="brand-text font-orbit-display"
          >OCI <span class="brand-neon">Worker</span></span
        >
      </div>
      <a-menu mode="inline" :selected-keys="[currentRoute]" @click="handleMenuClick"
        class="nav-menu" theme="dark">
        <!-- 图标必须用 #icon 插槽，否则与标题挤在 title-content 里，侧栏折叠时文字无法隐藏 -->
        <a-menu-item key="dashboard">
          <template #icon><i class="ri-dashboard-3-line menu-ri"></i></template>
          仪表盘
        </a-menu-item>
        <a-menu-item key="tenant">
          <template #icon><i class="ri-user-settings-line menu-ri"></i></template>
          租户配置
        </a-menu-item>
        <a-menu-item key="instance">
          <template #icon><i class="ri-server-line menu-ri"></i></template>
          实例管理
        </a-menu-item>
        <a-menu-item key="task">
          <template #icon><i class="ri-flashlight-line menu-ri"></i></template>
          开机任务
        </a-menu-item>
        <a-menu-item key="log">
          <template #icon><i class="ri-file-list-3-line menu-ri"></i></template>
          日志查看
        </a-menu-item>
        <a-menu-item key="webssh">
          <template #icon><i class="ri-terminal-box-line menu-ri"></i></template>
          WebSSH
        </a-menu-item>
        <a-menu-item key="settings">
          <template #icon><i class="ri-settings-4-line menu-ri"></i></template>
          系统设置
        </a-menu-item>
      </a-menu>

      <div class="sidebar-footer" :class="{ 'sidebar-footer--compact': compactSidebarFooter }">
        <div class="user-card">
          <div class="avatar">{{ avatarLetter }}</div>
          <div v-if="!compactSidebarFooter" class="user-info">
            <div class="user-name">Admin</div>
            <div class="user-status">管理员在线</div>
          </div>
        </div>
        <button
          type="button"
          class="btn-logout"
          :class="{ 'btn-logout--icon-only': compactSidebarFooter }"
          :title="compactSidebarFooter ? '安全退出' : undefined"
          @click="handleLogout"
        >
          <i class="ri-logout-box-r-line"></i>
          <span v-if="!compactSidebarFooter">安全退出</span>
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
          <h2 class="page-title" :class="{ 'font-orbit-display': themeStore.isDark }">
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
      <div
        :class="[
          'app-content',
          {
            'no-padding': isWebSSH,
            'orbit-page-surface': themeStore.isDark,
            'orbit-page-surface--bleed': themeStore.isDark && isWebSSH,
          },
        ]"
      >
        <router-view />
      </div>
    </a-layout>

  </a-layout>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useThemeStore } from '../stores/theme'
import { ORBIT_SHELL_VIDEO } from '../constants/orbit'

const orbitVideo = ORBIT_SHELL_VIDEO

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const themeStore = useThemeStore()
const collapsed = ref(false)
const mobileMenuOpen = ref(false)
const isMobile = ref(false)

const avatarLetter = computed(() => 'A')

/** 桌面窄栏（64px）用紧凑底栏；移动端抽屉展开时仍为全宽，保持完整用户信息 */
const compactSidebarFooter = computed(
  () => collapsed.value && !(isMobile.value && mobileMenuOpen.value),
)

function checkMobile() {
  isMobile.value = window.innerWidth < 768
  /** 移动端抽屉展开时要走「展开菜单」（图标+完整标题），不能与桌面折叠态混用 */
  if (isMobile.value && !mobileMenuOpen.value) collapsed.value = true
}

/** 移动端：抽屉打开 → 收起 Sider 的 collapsed，菜单恢复为内联全文；合上 → 再收起省状态 */
watch(mobileMenuOpen, open => {
  if (!isMobile.value) return
  collapsed.value = open ? false : true
})

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
.orbit-main-wrap {
  position: relative;
  min-height: 100vh;
  isolation: isolate;
}
.main-layout {
  min-height: 100vh;
  position: relative;
  z-index: 10;
}
.main-layout--orbit {
  min-height: 100vh;
}
.content-layout { min-width: 0; }

.sider {
  background: var(--bg-sidebar) !important;
  border-right: 1px solid var(--border);
  box-shadow: 6px 0 32px -12px rgba(0, 0, 0, 0.35);
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
[data-theme='light'] .sider {
  box-shadow: 6px 0 28px -12px rgba(15, 23, 42, 0.08);
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
  border-bottom: 1px solid var(--border);
}
.sider-collapsed-desktop .brand {
  justify-content: center;
  padding: 0 8px;
}
.brand-icon {
  font-size: 28px;
  color: #818cf8;
}
.brand-text {
  font-size: 18px;
  font-weight: 400;
  text-transform: uppercase;
  color: #eff4ff;
  letter-spacing: 0.04em;
  white-space: nowrap;
}
.brand-neon {
  color: #6fff00;
  margin-left: 0.2em;
}
[data-theme='light'] .brand-text,
[data-theme='light'] .brand-neon {
  background: linear-gradient(135deg, #a5b4fc 0%, #6366f1 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  margin: 0;
}
[data-theme='light'] .brand-neon {
  margin-left: 0.15em;
}

.nav-menu {
  flex: 1;
  padding: 12px 8px !important;
  overflow-x: hidden;
  overflow-y: auto;
  background: transparent !important;
  border: none !important;
}
.nav-menu :deep(.ant-menu-item) {
  text-align: left !important;
  padding-left: 24px !important;
}
/* 桌面侧栏收起（64px）：上面两条会盖住 Ant 的居中逻辑，图标会整体偏右，需在 .sider-collapsed-desktop 下覆盖 */
.sider-collapsed-desktop .nav-menu :deep(.ant-menu-item) {
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  padding-left: 0 !important;
  padding-right: 0 !important;
  text-align: center !important;
  margin: 2px 4px !important;
  overflow: visible !important;
}
/* flex 居中时标题节点仍会占位，图标默认 flex-shrink:1 会被挤成 0 宽 → 只见紫底不见图标 */
.sider-collapsed-desktop .nav-menu :deep(.ant-menu-item-icon) {
  flex-shrink: 0 !important;
}
.sider-collapsed-desktop .nav-menu :deep(.ant-menu-title-content) {
  flex: 0 0 0 !important;
  min-width: 0 !important;
  max-width: 0 !important;
  margin: 0 !important;
  padding: 0 !important;
  overflow: hidden !important;
  opacity: 0 !important;
}
.sider-collapsed-desktop .nav-menu :deep(.ant-menu-item-selected .ant-menu-item-icon) {
  color: #fff !important;
}
.sider-collapsed-desktop .menu-ri {
  margin-right: 0 !important;
}
.menu-ri {
  font-size: 20px;
  margin-right: 12px;
  vertical-align: -0.15em;
}

.sidebar-footer {
  flex-shrink: 0;
  padding: 12px 16px 16px;
  display: flex;
  flex-direction: column;
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

.sidebar-footer--compact {
  padding: 8px 6px 12px;
  align-items: center;
  overflow: hidden;
}
.sidebar-footer--compact .user-card {
  justify-content: center;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  padding: 8px 4px;
  margin-bottom: 8px;
  box-sizing: border-box;
}
.sidebar-footer--compact .avatar {
  margin: 0 auto;
}
.btn-logout--icon-only {
  width: 40px !important;
  height: 40px;
  min-width: 40px !important;
  padding: 0 !important;
  gap: 0 !important;
  margin: 0 auto;
}
.btn-logout--icon-only .ri-logout-box-r-line {
  font-size: 18px;
}

.app-header {
  height: 80px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  z-index: 40;
  flex-shrink: 0;
  border-bottom: 1px solid var(--border);
  background: color-mix(in srgb, var(--bg-body) 55%, transparent);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
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
  letter-spacing: -0.03em;
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
