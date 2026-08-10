<template>
  <a-modal
    :keyboard="false"
    :open="open"
    title="快捷开机任务"
    :width="isMobile ? '100%' : 600"
    :z-index="QUICK_TASK_MODAL_Z_INDEX"
    wrap-class-name="quick-task-modal-wrap"
    :confirm-loading="loading"
    :mask-closable="false"
    centered
    @update:open="$emit('update:open', $event)"
    @ok="$emit('confirm')"
  >
    <div style="margin-bottom: 12px">
      <a-tag color="blue">{{ tenant?.username }}</a-tag>
      <span style="color: var(--text-sub); font-size: 12px; margin-left: 8px">租户配置区域：{{ tenant?.ociRegion || '—' }}</span>
    </div>
    <a-form :model="form" layout="vertical">
      <a-form-item label="目标区域（开机任务）">
        <a-select
          v-model:value="form.ociRegion"
          placeholder="选择目标区域"
          :show-search="false"
          :options="ociRegionSelectOptions"
          :get-popup-container="popupContainer"
        />
      </a-form-item>
      <ShapeSeriesPicker
        v-model:architecture="form.architecture"
        :shapes="shapes"
        :loading="shapesLoading"
        :hint="shapes.length ? `已从 OCI 加载 ${shapes.length} 个可用 Shape（随目标区域变化）` : ''"
        :is-mobile="isMobile"
        :get-popup-container="popupContainer"
      />
      <a-row :gutter="12">
        <a-col :xs="24" :sm="10">
          <a-form-item label="操作系统">
            <a-select v-model:value="form.operationSystem" :get-popup-container="popupContainer">
              <a-select-option value="Ubuntu">Ubuntu（最新版）</a-select-option>
              <a-select-option value="Ubuntu 24.04">Ubuntu 24.04 LTS</a-select-option>
              <a-select-option value="Ubuntu 22.04">Ubuntu 22.04 LTS</a-select-option>
              <a-select-option value="Ubuntu 20.04">Ubuntu 20.04 LTS</a-select-option>
              <a-select-option value="Oracle Linux">Oracle Linux</a-select-option>
              <a-select-option value="CentOS">CentOS</a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
        <a-col :xs="24" :sm="14">
          <a-form-item label="实例名称">
            <a-input v-model:value="form.instanceName" :maxlength="255" placeholder="留空则自动生成" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item v-if="denseIoTiers?.length" label="DenseIO 档位">
        <a-select
          :value="denseIoTierKey"
          style="width: 100%"
          :get-popup-container="popupContainer"
          @update:value="$emit('update:denseIoTierKey', $event)"
        >
          <a-select-option v-for="t in denseIoTiers" :key="denseIoFlexTierKey(t)" :value="denseIoFlexTierKey(t)">
            {{ formatDenseIoTierLabel(t) }}
          </a-select-option>
        </a-select>
        <div style="color: var(--text-sub); font-size: 12px; margin-top: 4px">
          本地 NVMe 与网络带宽随档位由 OCI 自动配置，与控制台一致
        </div>
      </a-form-item>
      <a-row v-if="!denseIoTiers?.length" :gutter="12">
        <a-col :xs="12" :sm="12">
          <a-form-item :label="ocpuLabel">
            <a-input-number
              :value="form.ocpus"
              :min="shapeLimits.minOcpus"
              :max="shapeLimits.maxOcpus"
              :disabled="bmLocked"
              style="width: 100%"
              @update:value="$emit('update-ocpus', $event)"
              @blur="$emit('clamp-resources')"
            />
          </a-form-item>
        </a-col>
        <a-col :xs="12" :sm="12">
          <a-form-item :label="memoryLabel">
            <a-input-number
              :value="form.memory"
              :min="shapeLimits.minMemory"
              :max="shapeLimits.maxMemory"
              :disabled="bmLocked"
              style="width: 100%"
              @update:value="$emit('update-memory', $event)"
              @blur="$emit('clamp-resources')"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <a-row :gutter="12">
        <a-col :xs="12" :sm="12">
          <a-form-item label="磁盘 (GB)">
            <a-input-number v-model:value="form.disk" :min="47" :max="200" style="width: 100%" />
          </a-form-item>
        </a-col>
        <a-col :xs="12" :sm="12">
          <a-form-item label="VPUs/GB">
            <a-input-number
              v-model:value="form.vpusPerGB"
              :min="BOOT_VOLUME_VPUS_MIN"
              :max="BOOT_VOLUME_VPUS_MAX"
              :step="BOOT_VOLUME_VPUS_STEP"
              style="width: 100%"
              @blur="$emit('snap-boot-vpus')"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <div class="quick-login-options-row">
        <div>
          <a-form-item label="数量">
            <a-input-number v-model:value="form.createNumbers" :min="1" :max="500" style="width: 100%" />
          </a-form-item>
        </div>
        <div>
          <a-form-item label="间隔 (秒)">
            <a-input-number v-model:value="form.interval" :min="1" :max="600" style="width: 100%" />
          </a-form-item>
        </div>
        <div>
          <TaskLoginSelector
            v-model:root-password="form.rootPassword"
            v-model:login-mode="form.loginMode"
            v-model:ssh-public-key="form.sshPublicKey"
            :saved-root-password="savedRootPassword"
            :saved-ssh-public-key="savedSshPublicKey"
            :credential-loading="credentialLoading"
            placeholder="留空=随机生成"
            @missing="$emit('credential-missing', $event)"
          />
        </div>
      </div>
      <div style="display: flex; align-items: center; gap: 32px; margin-bottom: 16px">
        <span style="display: inline-flex; align-items: center; gap: 8px">
          <a-switch v-model:checked="form.assignPublicIp" />
          <span>公网IP</span>
        </span>
        <span style="display: inline-flex; align-items: center; gap: 8px">
          <a-switch v-model:checked="form.assignIpv6" />
          <span>IPv6</span>
        </span>
      </div>
      <div class="capacity-probe-box">
        <span class="capacity-probe-label">
          容量探测
          <a-tooltip
            placement="top"
            :get-popup-container="(trigger: HTMLElement) => trigger.parentElement || document.body"
          >
            <template #title>
              <div>关闭：不探测，按设定间隔直接尝试开机</div>
              <div>仅参考：每轮查询 Oracle 容量报告，结果仅展示，不影响开机节奏</div>
              <div>智能节流：报告无货时降低尝试频率（每 5 轮仍真实尝试 1 次），探测到有货立刻恢复全速</div>
              <div style="margin-top: 4px; opacity: 0.75">容量报告由 Oracle 提供，仅供参考，不保证与实际开机结果一致</div>
            </template>
            <InfoCircleOutlined style="color: var(--text-sub)" />
          </a-tooltip>
        </span>
        <a-segmented v-model:value="form.capacityProbeMode" :options="capacityProbeOptions" size="small" />
      </div>
      <a-form-item label="自定义开机脚本">
        <a-textarea v-model:value="form.customScript" placeholder="可选，留空不执行" :auto-size="{ minRows: 2, maxRows: 5 }" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import ShapeSeriesPicker from '../ShapeSeriesPicker.vue'
import TaskLoginSelector from '../TaskLoginSelector.vue'
import { InfoCircleOutlined } from '@ant-design/icons-vue'
import {
  BOOT_VOLUME_VPUS_MAX,
  BOOT_VOLUME_VPUS_MIN,
  BOOT_VOLUME_VPUS_STEP,
} from '../../utils/bootVolume'
import { ociRegionSelectOptions } from '../../utils/ociRegionCatalog'
import { QUICK_TASK_MODAL_Z_INDEX } from '../../utils/overlayZIndex'

defineOptions({ name: 'QuickTaskModal' })

const capacityProbeOptions = [
  { label: '关闭', value: 'OFF' },
  { label: '仅参考', value: 'REFERENCE' },
  { label: '智能节流', value: 'THROTTLE' },
]

interface QuickTaskFormModel {
  ociRegion?: string
  architecture: string
  operationSystem: string
  instanceName: string
  ocpus: number
  memory: number
  disk: number
  vpusPerGB: number
  createNumbers: number
  interval: number
  rootPassword: string
  loginMode: string
  sshPublicKey: string
  customScript: string
  assignPublicIp: boolean
  assignIpv6: boolean
  capacityProbeMode: string
}

interface QuickTaskTenant {
  username?: string
  ociRegion?: string
}

interface QuickTaskShapeLimits {
  minOcpus: number
  maxOcpus: number
  minMemory: number
  maxMemory: number
}

defineProps<{
  open: boolean
  loading: boolean
  tenant: QuickTaskTenant | null
  form: QuickTaskFormModel
  shapes: any[]
  shapesLoading: boolean
  popupContainer: (triggerNode?: HTMLElement) => HTMLElement
  bmLocked: boolean
  savedRootPassword: string
  savedSshPublicKey: string
  credentialLoading: boolean
  shapeLimits: QuickTaskShapeLimits
  ocpuLabel: string
  memoryLabel: string
  denseIoTiers: any[] | null
  denseIoTierKey?: string
  formatDenseIoTierLabel: (tier: any) => string
  denseIoFlexTierKey: (tier: any) => string
  isMobile: boolean
}>()

defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:denseIoTierKey', value: string | undefined): void
  (e: 'confirm'): void
  (e: 'credential-missing', value: 'password' | 'publicKey'): void
  (e: 'update-ocpus', value: number | null): void
  (e: 'update-memory', value: number | null): void
  (e: 'clamp-resources'): void
  (e: 'snap-boot-vpus'): void
}>()
</script>

<style scoped>
.capacity-probe-box {
  align-items: center;
  background: var(--input-bg, rgba(15, 23, 42, 0.35));
  border: 1px solid var(--border, rgba(255, 255, 255, 0.1));
  border-radius: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: space-between;
  margin-bottom: 16px;
  padding: 10px 12px;
}
.capacity-probe-label {
  align-items: center;
  display: inline-flex;
  font-weight: 500;
  gap: 6px;
}
.quick-login-options-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1.15fr;
  gap: 12px;
  align-items: start;
}

@media (max-width: 768px) {
  .quick-login-options-row {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }
  .quick-login-options-row > div:nth-child(3) {
    grid-column: 1 / -1;
  }
}
</style>
