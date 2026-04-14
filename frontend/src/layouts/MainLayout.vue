<template>
  <a-layout class="main-layout">
    <!-- Mobile overlay -->
    <div v-if="mobileMenuOpen && isMobile" class="mobile-overlay" @click="mobileMenuOpen = false" />

    <a-layout-sider
      v-model:collapsed="collapsed"
      :trigger="null"
      collapsible
      :width="240"
      :collapsed-width="isMobile ? 0 : 64"
      :class="['sider', { 'sider-mobile': isMobile, 'sider-mobile-open': mobileMenuOpen && isMobile }]"
      :style="isMobile && !mobileMenuOpen ? { display: 'none' } : {}"
    >
      <div class="logo">
        <div class="logo-icon">⚡</div>
        <span v-if="!collapsed || (isMobile && mobileMenuOpen)" class="logo-text">OCI Worker</span>
      </div>
      <a-menu mode="inline" :selected-keys="[currentRoute]" @click="handleMenuClick"
        class="nav-menu" theme="dark">
        <a-menu-item key="dashboard">
          <DashboardOutlined />
          <span>首页</span>
        </a-menu-item>
        <a-menu-item key="tenant">
          <UserOutlined />
          <span>租户配置</span>
        </a-menu-item>
        <a-menu-item key="instance">
          <CloudServerOutlined />
          <span>实例管理</span>
        </a-menu-item>
        <a-menu-item key="task">
          <ThunderboltOutlined />
          <span>开机任务</span>
        </a-menu-item>
        <a-menu-item key="log">
          <FileTextOutlined />
          <span>日志查看</span>
        </a-menu-item>
        <a-menu-item key="settings">
          <SettingOutlined />
          <span>系统设置</span>
        </a-menu-item>
        <a-menu-item key="backup">
          <SaveOutlined />
          <span>备份恢复</span>
        </a-menu-item>
      </a-menu>
    </a-layout-sider>

    <a-layout class="content-layout">
      <header class="app-header">
        <div class="header-left">
          <MenuOutlined v-if="isMobile" class="trigger" @click="mobileMenuOpen = !mobileMenuOpen" />
          <MenuUnfoldOutlined v-else-if="collapsed" class="trigger" @click="collapsed = false" />
          <MenuFoldOutlined v-else class="trigger" @click="collapsed = true" />
          <h2 class="page-title">{{ currentTitle }}</h2>
        </div>
        <div class="header-right">
          <a-dropdown>
            <div class="user-avatar">
              <a-avatar :size="34" style="background: linear-gradient(135deg, #18E299, #0fa76e); cursor: pointer">
                <template #icon><UserOutlined /></template>
              </a-avatar>
            </div>
            <template #overlay>
              <a-menu>
                <a-menu-item @click="handleLogout">
                  <LogoutOutlined />
                  退出登录
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>
      </header>
      <a-layout-content class="app-content">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  DashboardOutlined, UserOutlined, CloudServerOutlined,
  ThunderboltOutlined,
  FileTextOutlined, SettingOutlined, SaveOutlined,
  MenuUnfoldOutlined, MenuFoldOutlined, MenuOutlined, LogoutOutlined,
} from '@ant-design/icons-vue'
import { useUserStore } from '../stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const collapsed = ref(false)
const mobileMenuOpen = ref(false)
const isMobile = ref(false)

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
const currentTitle = computed(() => {
  const r = router.getRoutes().find(r => r.name === route.name)
  return (r?.meta?.title as string) || 'OCI Worker'
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
  background: #0d0d0d !important;
  border-right: 1px solid rgba(255,255,255,0.06);
}
.sider-mobile {
  position: fixed !important;
  z-index: 1001;
  height: 100vh;
  left: 0;
  top: 0;
}

.logo {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 0 16px;
  border-bottom: 1px solid rgba(255,255,255,0.06);
}
.logo-icon {
  font-size: 22px;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #18E299, #0fa76e);
  border-radius: 10px;
  flex-shrink: 0;
}
.logo-text {
  font-size: 18px;
  font-weight: 600;
  color: #fff;
  letter-spacing: -0.4px;
  white-space: nowrap;
}

.nav-menu {
  background: transparent !important;
  border: none !important;
  padding: 8px;
}

.app-header {
  height: 56px;
  background: #fff !important;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(0,0,0,0.05);
  position: sticky;
  top: 0;
  z-index: 100;
  backdrop-filter: blur(12px);
}
.header-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.trigger { font-size: 18px; cursor: pointer; color: #666; transition: color 0.2s; flex-shrink: 0; }
.trigger:hover { color: #18E299; }
.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #0d0d0d;
  letter-spacing: -0.2px;
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.app-content {
  margin: 16px;
  padding: 24px;
  background: #fff;
  border-radius: 16px;
  border: 1px solid rgba(0,0,0,0.05);
  min-height: calc(100vh - 88px);
  overflow-x: hidden;
}

.mobile-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.45);
  z-index: 1000;
}

@media (max-width: 768px) {
  .app-header {
    padding: 0 12px;
    height: 48px;
  }
  .page-title { font-size: 14px; }
  .app-content {
    margin: 8px;
    padding: 12px;
    border-radius: 12px;
    min-height: calc(100vh - 64px);
  }
}
</style>
