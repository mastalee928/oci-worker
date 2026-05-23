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

    <div v-else-if="zoneId" class="cf-zone-body">
      <a-menu
        v-model:selected-keys="menuSelectedKeys"
        mode="inline"
        class="cf-zone-menu"
        :inline-collapsed="isMobile"
        @click="onMenuClick"
      >
        <a-menu-item v-for="item in menuItems" :key="item.key">
          <component :is="item.icon" v-if="item.icon" />
          <span>{{ item.label }}</span>
        </a-menu-item>
      </a-menu>
      <div class="cf-zone-content">
        <slot />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
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

const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }

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

watch(() => props.zoneId, (id) => {
  if (!id && props.activeMenu !== 'overview') {
    emit('update:activeMenu', 'overview')
  }
})

onMounted(() => window.addEventListener('resize', checkMobile))
onUnmounted(() => window.removeEventListener('resize', checkMobile))
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
}
@media (max-width: 767px) {
  .cf-zone-body { flex-direction: column; }
  .cf-zone-menu {
    width: 100%;
    border-right: none;
    border-bottom: 1px solid var(--border);
  }
}
</style>
