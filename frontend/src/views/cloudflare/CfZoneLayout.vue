<template>
  <div class="cf-zone-layout">
    <CfZoneBar
      :zone-id="zoneId"
      :cf-configured="cfConfigured"
      show-create-zone
      @update:zone-id="onZoneIdChange"
      @create-zone="emit('create-zone')"
    />

    <a-empty
      v-if="!zoneId && cfConfigured"
      description="请先选择区域（Zone）"
      style="margin: 48px 0"
    />

    <div v-else-if="zoneId" class="cf-zone-body" :class="{ 'is-mobile': isMobile }">
      <a-menu
        v-if="!isMobile"
        v-model:selected-keys="menuSelectedKeys"
        mode="inline"
        class="cf-zone-menu"
        @click="onMenuClick"
      >
        <a-menu-item v-for="item in menuItems" :key="item.key">
          <component :is="item.icon" v-if="item.icon" />
          <span>{{ item.label }}</span>
        </a-menu-item>
      </a-menu>

      <div v-else ref="tabsRef" class="cf-zone-tabs">
        <button
          v-for="item in menuItems"
          :key="item.key"
          type="button"
          class="cf-zone-tab"
          :class="{ active: activeMenu === item.key }"
          @click="selectMenu(item.key)"
        >
          <component :is="item.icon" class="cf-zone-tab-icon" />
          <span class="cf-zone-tab-label">{{ item.label }}</span>
        </button>
      </div>

      <div class="cf-zone-content">
        <slot />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import {
  DashboardOutlined,
  GlobalOutlined,
  MailOutlined,
  LockOutlined,
  SafetyOutlined,
  CloudOutlined,
  NodeIndexOutlined,
  PartitionOutlined,
} from '@ant-design/icons-vue'
import CfZoneBar from './CfZoneBar.vue'
import { useIsMobile } from '../../composables/useIsMobile'

export type CfZoneMenuKey =
  | 'overview'
  | 'dns'
  | 'email'
  | 'ssl'
  | 'security'
  | 'cache'
  | 'workers-routes'
  | 'rules'

const props = defineProps<{
  cfConfigured: boolean
  zoneId?: string
  activeMenu: CfZoneMenuKey
}>()

const emit = defineEmits<{
  'update:zoneId': [value: string | undefined]
  'update:activeMenu': [value: CfZoneMenuKey]
  'create-zone': []
}>()

const { isMobile } = useIsMobile()
const tabsRef = ref<HTMLElement | null>(null)

const menuItems = [
  { key: 'overview' as const, label: '概述', icon: DashboardOutlined },
  { key: 'dns' as const, label: 'DNS', icon: GlobalOutlined },
  { key: 'email' as const, label: '电子邮件', icon: MailOutlined },
  { key: 'ssl' as const, label: 'SSL/TLS', icon: LockOutlined },
  { key: 'security' as const, label: '安全性', icon: SafetyOutlined },
  { key: 'cache' as const, label: '缓存', icon: CloudOutlined },
  { key: 'workers-routes' as const, label: 'Workers 路由', icon: NodeIndexOutlined },
  { key: 'rules' as const, label: '规则', icon: PartitionOutlined },
]

const menuSelectedKeys = computed(() => [props.activeMenu])

function onZoneIdChange(val: string | undefined) {
  emit('update:zoneId', val)
}

function onMenuClick({ key }: { key: string }) {
  emit('update:activeMenu', key as CfZoneMenuKey)
}

function selectMenu(key: CfZoneMenuKey) {
  emit('update:activeMenu', key)
}

function scrollActiveTabIntoView() {
  if (!isMobile.value) return
  nextTick(() => {
    const el = tabsRef.value?.querySelector('.cf-zone-tab.active') as HTMLElement | null
    el?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' })
  })
}

watch(() => props.activeMenu, scrollActiveTabIntoView)
watch(isMobile, v => { if (v) scrollActiveTabIntoView() })

watch(() => props.zoneId, (id) => {
  if (!id && props.activeMenu !== 'overview') {
    emit('update:activeMenu', 'overview')
  }
})

onMounted(() => {
  scrollActiveTabIntoView()
})
</script>

<style scoped>
.cf-zone-layout { min-height: 200px; }
.cf-zone-body {
  display: flex;
  gap: 0;
  margin-top: 8px;
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
  min-height: 420px;
}
.cf-zone-menu {
  width: 168px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  background: var(--bg-card, transparent);
}
.cf-zone-content {
  flex: 1;
  min-width: 0;
  padding: 16px;
  overflow: auto;
  animation: cfContentIn 0.28s ease;
}
@keyframes cfContentIn {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 移动端横向 Tab */
.cf-zone-body.is-mobile {
  flex-direction: column;
  min-height: 0;
}
.cf-zone-tabs {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  overflow-x: auto;
  overflow-y: hidden;
  border-bottom: 1px solid var(--border);
  background: var(--bg-card, transparent);
  scrollbar-width: none;
  -webkit-overflow-scrolling: touch;
}
.cf-zone-tabs::-webkit-scrollbar { display: none; }

.cf-zone-tab {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0;
  margin: 0;
  padding: 8px 11px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--text-sub);
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
  transition:
    padding 0.28s cubic-bezier(0.4, 0, 0.2, 1),
    gap 0.28s cubic-bezier(0.4, 0, 0.2, 1),
    background 0.28s ease,
    color 0.28s ease,
    transform 0.2s ease,
    box-shadow 0.28s ease;
}
.cf-zone-tab:active {
  transform: scale(0.94);
}
.cf-zone-tab.active {
  gap: 6px;
  padding: 8px 14px;
  background: color-mix(in srgb, var(--primary, #1677ff) 14%, transparent);
  color: var(--primary, #1677ff);
  box-shadow: 0 1px 4px color-mix(in srgb, var(--primary, #1677ff) 18%, transparent);
}
.cf-zone-tab-icon {
  font-size: 18px;
  flex-shrink: 0;
  transition: transform 0.28s cubic-bezier(0.4, 0, 0.2, 1);
}
.cf-zone-tab.active .cf-zone-tab-icon {
  transform: scale(1.06);
}
.cf-zone-tab-label {
  display: inline-block;
  max-width: 0;
  opacity: 0;
  overflow: hidden;
  white-space: nowrap;
  font-weight: 500;
  transition:
    max-width 0.32s cubic-bezier(0.4, 0, 0.2, 1),
    opacity 0.22s ease,
    margin 0.28s ease;
}
.cf-zone-tab.active .cf-zone-tab-label {
  max-width: 140px;
  opacity: 1;
}

@media (max-width: 767px) {
  .cf-zone-content {
    padding: 12px;
  }
}
</style>
