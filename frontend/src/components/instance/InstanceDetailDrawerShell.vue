<template>
  <a-drawer
    root-class-name="instance-detail-drawer"
    :keyboard="false"
    v-model:open="openModel"
    :title="instance?.name || '实例详情'"
    :width="isMobile ? '100%' : 780"
    placement="right"
    :mask-closable="false"
  >
    <a-tabs v-model:activeKey="activeTabModel" @change="$emit('tab-change', String($event))">
      <a-tab-pane key="info" tab="基本信息">
        <InstanceDetailInfoPanel
          ref="detailInfoPanelRef"
          mode="info"
          :tenant="tenant"
          :instance="instance"
          :active="open && activeTab === 'info'"
          :region="currentDetailRegion"
          :state-color-map="stateColorMap"
          :action-loading="actionLoading"
          :change-ip-loading="changeIpLoading"
          :instance-info-loading="instanceInfoLoading"
          @refresh-info="$emit('refresh-info')"
          @edit-instance="$emit('edit-instance')"
          @edit-fault-domain="$emit('edit-fault-domain')"
          @instance-action="$emit('instance-action', $event)"
          @change-ip="$emit('change-ip')"
          @terminate="$emit('terminate')"
        />
      </a-tab-pane>

      <a-tab-pane key="security" tab="安全列表">
        <InstanceSecurityPanel
          ref="securityPanelRef"
          :tenant="tenant"
          :instance="instance"
          :is-mobile="isMobile"
          :active="activeTab === 'security'"
          :region="currentDetailRegion"
          :compartment-id="instance?.compartmentId"
          @overlay-active-change="$emit('security-overlay-active-change', $event)"
        />
      </a-tab-pane>

      <a-tab-pane key="volume" tab="引导卷">
        <BootVolumePanel
          ref="bootVolumePanelRef"
          :tenant="tenant"
          :instance="instance"
          :is-mobile="isMobile"
          :active="activeTab === 'volume'"
          :region="currentDetailRegion"
          @overlay-active-change="$emit('boot-volume-overlay-active-change', $event)"
          @boot-volume-updated="$emit('boot-volume-updated')"
        />
      </a-tab-pane>

      <a-tab-pane key="blockVolume" tab="块存储">
        <BlockStoragePanel
          ref="blockStoragePanelRef"
          :tenant="tenant"
          :instance="instance"
          :is-mobile="isMobile"
          :active="activeTab === 'blockVolume'"
          :region="currentDetailRegion"
          :on-stop-instance="onStopInstance"
          @overlay-active-change="$emit('block-storage-overlay-active-change', $event)"
        />
      </a-tab-pane>

      <a-tab-pane key="network" tab="网络">
        <InstanceNetworkPanel
          ref="networkPanelRef"
          :tenant="tenant"
          :instance="instance"
          :is-mobile="isMobile"
          :active="activeTab === 'network'"
          :region="currentDetailRegion"
          :compartment-id="instance?.compartmentId"
          @open-vcn-manager="$emit('open-vcn-manager', $event)"
        />
      </a-tab-pane>

      <a-tab-pane key="traffic" tab="流量统计">
        <InstanceTrafficPanel
          ref="trafficPanelRef"
          :tenant="tenant"
          :instance="instance"
          :is-mobile="isMobile"
          :active="activeTab === 'traffic'"
          :region="currentDetailRegion"
          @overlay-active-change="$emit('traffic-overlay-active-change', $event)"
        />
      </a-tab-pane>

      <a-tab-pane key="shape" tab="形状编辑">
        <InstanceShapeEditPanel
          ref="shapeEditPanelRef"
          :tenant="tenant"
          :instance="instance"
          :active="activeTab === 'shape'"
          :region="currentDetailRegion"
          @focus-panel="focusShapePanel"
          @instance-updated="$emit('shape-instance-updated', $event)"
          @reload-instance-list="$emit('reload-instance-list')"
        />
      </a-tab-pane>

      <a-tab-pane key="console" tab="串行控制台">
        <InstanceDetailInfoPanel
          mode="console"
          :tenant="tenant"
          :instance="instance"
          :active="activeTab === 'console'"
          :region="currentDetailRegion"
          :console-loading="consoleLoading"
          :console-data="consoleData"
          @create-console="$emit('create-console')"
          @open-console="$emit('open-console')"
          @delete-console="$emit('delete-console')"
        />
      </a-tab-pane>
    </a-tabs>
    <template v-if="activeTab === 'shape' && showForceA2ToA1Button" #footer>
      <div class="instance-drawer-shape-footer">
        <a-button danger @click="$emit('open-force-a2-to-a1')">A2强改A1</a-button>
      </div>
    </template>
  </a-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { defineAppAsyncComponent } from '../../utils/asyncComponent'

defineOptions({ name: 'InstanceDetailDrawerShell' })

const BootVolumePanel = defineAppAsyncComponent(() => import('./BootVolumePanel.vue'), { loadingVariant: 'detail', loadingText: '正在载入引导卷', loadingDescription: '准备卷信息与操作面板' })
const BlockStoragePanel = defineAppAsyncComponent(() => import('./BlockStoragePanel.vue'), { loadingVariant: 'detail', loadingText: '正在载入块存储', loadingDescription: '准备存储卷与挂载信息' })
const InstanceTrafficPanel = defineAppAsyncComponent(() => import('./InstanceTrafficPanel.vue'), { loadingVariant: 'detail', loadingText: '正在载入流量统计', loadingDescription: '读取实例网络流量数据' })
const InstanceSecurityPanel = defineAppAsyncComponent(() => import('./InstanceSecurityPanel.vue'), { loadingVariant: 'detail', loadingText: '正在载入安全列表', loadingDescription: '准备安全规则与操作面板' })
const InstanceNetworkPanel = defineAppAsyncComponent(() => import('./InstanceNetworkPanel.vue'), { loadingVariant: 'detail', loadingText: '正在载入云网络', loadingDescription: '准备 VCN、子网与网络操作' })
const InstanceDetailInfoPanel = defineAppAsyncComponent(() => import('./InstanceDetailInfoPanel.vue'), { loadingVariant: 'detail', loadingText: '正在载入实例详情', loadingDescription: '准备操作面板' })
const InstanceShapeEditPanel = defineAppAsyncComponent(() => import('./InstanceShapeEditPanel.vue'), { loadingVariant: 'detail', loadingText: '正在载入形状配置', loadingDescription: '读取实例规格与可用选项' })

const props = defineProps<{
  open: boolean
  activeTab: string
  tenant: any
  instance: any
  isMobile: boolean
  currentDetailRegion?: string
  stateColorMap: Record<string, string>
  actionLoading: Record<string, boolean>
  changeIpLoading: boolean
  instanceInfoLoading: boolean
  showForceA2ToA1Button: boolean
  consoleLoading: boolean
  consoleData: any
  onStopInstance: () => Promise<any> | any
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:activeTab', value: string): void
  (e: 'tab-change', key: string): void
  (e: 'refresh-info'): void
  (e: 'edit-instance'): void
  (e: 'edit-fault-domain'): void
  (e: 'instance-action', action: 'START' | 'STOP' | 'RESET'): void
  (e: 'change-ip'): void
  (e: 'terminate'): void
  (e: 'security-overlay-active-change', active: boolean): void
  (e: 'boot-volume-overlay-active-change', active: boolean): void
  (e: 'boot-volume-updated'): void
  (e: 'block-storage-overlay-active-change', active: boolean): void
  (e: 'open-vcn-manager', payload: any): void
  (e: 'traffic-overlay-active-change', active: boolean): void
  (e: 'focus-shape-panel'): void
  (e: 'shape-instance-updated', result?: Record<string, any>): void
  (e: 'reload-instance-list'): void
  (e: 'open-force-a2-to-a1'): void
  (e: 'create-console'): void
  (e: 'open-console'): void
  (e: 'delete-console'): void
}>()

const openModel = computed({
  get: () => props.open,
  set: (value: boolean) => emit('update:open', value),
})

const activeTabModel = computed({
  get: () => props.activeTab,
  set: (value: string) => emit('update:activeTab', value),
})

const bootVolumePanelRef = ref<any>(null)
const blockStoragePanelRef = ref<any>(null)
const trafficPanelRef = ref<any>(null)
const securityPanelRef = ref<any>(null)
const networkPanelRef = ref<any>(null)
const detailInfoPanelRef = ref<any>(null)
const shapeEditPanelRef = ref<any>(null)

function focusShapePanel() {
  activeTabModel.value = 'shape'
  emit('focus-shape-panel')
}

function resetAllPanels() {
  detailInfoPanelRef.value?.reset?.()
  bootVolumePanelRef.value?.reset?.()
  blockStoragePanelRef.value?.reset?.()
  trafficPanelRef.value?.reset?.()
  securityPanelRef.value?.reset?.()
  networkPanelRef.value?.reset?.()
  shapeEditPanelRef.value?.reset?.()
}

function stopShapeSilently() {
  return shapeEditPanelRef.value?.stopSilently?.()
}

function loadShapeOptions() {
  return shapeEditPanelRef.value?.loadOptions?.()
}

function loadNetworkVcns(force?: boolean) {
  return networkPanelRef.value?.loadVcns?.(force)
}

function loadBlockVolumes() {
  return blockStoragePanelRef.value?.loadBlockVolumes?.()
}

function loadNetworkDetail() {
  return detailInfoPanelRef.value?.loadNetworkDetail?.()
}

defineExpose({
  resetAllPanels,
  stopShapeSilently,
  loadShapeOptions,
  loadNetworkVcns,
  loadBlockVolumes,
  loadNetworkDetail,
})
</script>

<style scoped>
:global(.instance-detail-drawer .ant-drawer-body) {
  display: flex;
  min-height: 0;
  flex-direction: column;
}

:global(.instance-detail-drawer .ant-tabs) {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
}

:global(.instance-detail-drawer .ant-tabs-content-holder),
:global(.instance-detail-drawer .ant-tabs-content),
:global(.instance-detail-drawer .ant-tabs-tabpane-active) {
  min-height: 100%;
}

.instance-drawer-shape-footer {
  display: flex;
  justify-content: flex-end;
}
</style>
