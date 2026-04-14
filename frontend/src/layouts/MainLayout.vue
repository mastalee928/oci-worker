<template>
  <a-layout class="main-layout">
    <a-layout-sider v-model:collapsed="collapsed" :trigger="null" collapsible theme="dark" :width="220">
      <div class="logo">
        <span v-if="!collapsed">OCI Worker</span>
        <span v-else>OW</span>
      </div>
      <a-menu theme="dark" mode="inline" :selected-keys="[currentRoute]" @click="handleMenuClick">
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
        <a-menu-item key="security">
          <SafetyOutlined />
          <span>安全列表</span>
        </a-menu-item>
        <a-menu-item key="network">
          <GlobalOutlined />
          <span>网络管理</span>
        </a-menu-item>
        <a-menu-item key="volume">
          <DatabaseOutlined />
          <span>引导卷</span>
        </a-menu-item>
        <a-menu-item key="traffic">
          <LineChartOutlined />
          <span>流量统计</span>
        </a-menu-item>
        <a-menu-item key="cloudflare">
          <CloudOutlined />
          <span>Cloudflare</span>
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
    <a-layout>
      <a-layout-header class="header">
        <div class="header-left">
          <MenuUnfoldOutlined v-if="collapsed" class="trigger" @click="collapsed = false" />
          <MenuFoldOutlined v-else class="trigger" @click="collapsed = true" />
          <span class="page-title">{{ currentTitle }}</span>
        </div>
        <div class="header-right">
          <a-dropdown>
            <a-space>
              <a-avatar :size="32" style="background-color: #1890ff">
                <template #icon><UserOutlined /></template>
              </a-avatar>
            </a-space>
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
      </a-layout-header>
      <a-layout-content class="content">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  DashboardOutlined, UserOutlined, CloudServerOutlined,
  ThunderboltOutlined, SafetyOutlined, GlobalOutlined,
  DatabaseOutlined, LineChartOutlined, CloudOutlined,
  FileTextOutlined, SettingOutlined, SaveOutlined,
  MenuUnfoldOutlined, MenuFoldOutlined, LogoutOutlined,
} from '@ant-design/icons-vue'
import { useUserStore } from '../stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const collapsed = ref(false)

const currentRoute = computed(() => route.path.split('/')[1] || 'dashboard')
const currentTitle = computed(() => {
  const r = router.getRoutes().find(r => r.name === route.name)
  return (r?.meta?.title as string) || 'OCI Worker'
})

function handleMenuClick({ key }: { key: string }) {
  router.push('/' + key)
}

function handleLogout() {
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.main-layout {
  min-height: 100vh;
}
.logo {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 700;
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}
.header {
  background: #fff;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}
.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}
.trigger {
  font-size: 18px;
  cursor: pointer;
  transition: color 0.3s;
}
.trigger:hover {
  color: #1890ff;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.header-right {
  cursor: pointer;
}
.content {
  margin: 24px;
  padding: 24px;
  background: #fff;
  border-radius: 8px;
  min-height: 280px;
}
</style>
