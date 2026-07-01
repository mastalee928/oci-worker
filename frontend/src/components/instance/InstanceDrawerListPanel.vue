<template>
  <div class="instance-panel">
    <div class="instance-panel-toolbar">
      <span class="instance-panel-toolbar-label">Region</span>
      <a-select
        v-model:value="regionValue"
        class="instance-panel-region-select"
        :options="regionOptions"
        :loading="regionLoading"
        show-search
        option-filter-prop="label"
        placeholder="选择区域"
        @change="emit('region-change')"
      />
      <a-button size="small" :loading="tenantData.loading" @click="emit('refresh')">
        <template #icon><ReloadOutlined /></template>刷新
      </a-button>
      <span v-if="regionLoading" class="instance-panel-region-hint">正在同步订阅区域…</span>
    </div>

    <a-spin :spinning="tenantData.loading">
      <a-empty v-if="!tenantData.loading && tenantData.instances.length === 0" description="暂无实例" />

      <div v-else-if="isMobile" class="instance-mobile-list">
        <VirtualTenantCardList
          v-if="tenantData.instances.length > virtualCardMin"
          :items="tenantData.instances"
          :item-key="itemKey"
          :estimate-size="176"
          :max-height="mobileVirtualMaxHeight"
          :reset-key="virtualResetKey"
        >
          <template #item="{ item: record }">
            <div class="instance-mobile-card">
              <div class="imc-header">
                <span class="imc-name" :title="record.name">{{ record.name }}</span>
                <a-badge :status="stateColorMap[record.state] || 'default'" :text="record.state" />
              </div>
              <div class="imc-body">
                <div class="imc-row">
                  <span class="imc-label">规格</span>
                  <div class="imc-value-group">
                    <span class="imc-value-main">{{ record.ocpus }}C / {{ record.memoryInGBs }}G</span>
                    <span class="imc-value-sub">{{ record.shape }}</span>
                  </div>
                </div>
                <div class="imc-row">
                  <span class="imc-label">公网 IP</span>
                  <a-typography-text v-if="record.publicIp" copyable class="ip-copy imc-value-main">{{ record.publicIp }}</a-typography-text>
                  <span v-else class="imc-value-sub">—</span>
                </div>
              </div>
              <div class="imc-footer">
                <a-button type="link" size="small" @click="emit('open-detail', record)">
                  <i class="ri-information-line" style="margin-right: 4px"></i>详情
                </a-button>
                <a-dropdown :trigger="['click']">
                  <a-button type="link" size="small" class="instance-action-trigger" :loading="actionLoading[record.instanceId]">
                    实例操作
                    <DownOutlined style="font-size: 10px; margin-left: 2px" />
                  </a-button>
                  <template #overlay>
                    <a-menu class="instance-action-menu" @click="(info: any) => emitMenuClick(record, info.key)">
                      <a-menu-item key="START">
                        <i class="ri-play-fill" style="color: #52c41a; margin-right: 8px"></i>启动
                      </a-menu-item>
                      <a-menu-item key="SOFTRESET" :disabled="record.state !== 'RUNNING'">
                        <i class="ri-restart-line" style="color: #faad14; margin-right: 8px"></i>重启
                      </a-menu-item>
                      <a-menu-item key="RESET" :disabled="record.state !== 'RUNNING'">
                        <i class="ri-shut-down-line" style="color: #ff7a45; margin-right: 8px"></i>断电重启
                      </a-menu-item>
                      <a-menu-item key="SOFTSTOP" :disabled="record.state !== 'RUNNING'">
                        <i class="ri-stop-fill" style="color: #8c8c8c; margin-right: 8px"></i>暂停
                      </a-menu-item>
                      <a-menu-divider />
                      <a-menu-item key="TERMINATE" danger>
                        <i class="ri-close-circle-line" style="color: #ff4d4f; margin-right: 8px"></i>终止
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </div>
            </div>
          </template>
        </VirtualTenantCardList>

        <template v-else>
          <div
            v-for="record in tenantData.instances"
            :key="record.instanceId"
            class="instance-mobile-card"
          >
            <div class="imc-header">
              <span class="imc-name" :title="record.name">{{ record.name }}</span>
              <a-badge :status="stateColorMap[record.state] || 'default'" :text="record.state" />
            </div>
            <div class="imc-body">
              <div class="imc-row">
                <span class="imc-label">规格</span>
                <div class="imc-value-group">
                  <span class="imc-value-main">{{ record.ocpus }}C / {{ record.memoryInGBs }}G</span>
                  <span class="imc-value-sub">{{ record.shape }}</span>
                </div>
              </div>
              <div class="imc-row">
                <span class="imc-label">公网 IP</span>
                <a-typography-text v-if="record.publicIp" copyable class="ip-copy imc-value-main">{{ record.publicIp }}</a-typography-text>
                <span v-else class="imc-value-sub">—</span>
              </div>
            </div>
            <div class="imc-footer">
              <a-button type="link" size="small" @click="emit('open-detail', record)">
                <i class="ri-information-line" style="margin-right: 4px"></i>详情
              </a-button>
              <a-dropdown :trigger="['click']">
                <a-button type="link" size="small" class="instance-action-trigger" :loading="actionLoading[record.instanceId]">
                  实例操作
                  <DownOutlined style="font-size: 10px; margin-left: 2px" />
                </a-button>
                <template #overlay>
                  <a-menu class="instance-action-menu" @click="(info: any) => emitMenuClick(record, info.key)">
                    <a-menu-item key="START">
                      <i class="ri-play-fill" style="color: #52c41a; margin-right: 8px"></i>启动
                    </a-menu-item>
                    <a-menu-item key="SOFTRESET" :disabled="record.state !== 'RUNNING'">
                      <i class="ri-restart-line" style="color: #faad14; margin-right: 8px"></i>重启
                    </a-menu-item>
                    <a-menu-item key="RESET" :disabled="record.state !== 'RUNNING'">
                      <i class="ri-shut-down-line" style="color: #ff7a45; margin-right: 8px"></i>断电重启
                    </a-menu-item>
                    <a-menu-item key="SOFTSTOP" :disabled="record.state !== 'RUNNING'">
                      <i class="ri-stop-fill" style="color: #8c8c8c; margin-right: 8px"></i>暂停
                    </a-menu-item>
                    <a-menu-divider />
                    <a-menu-item key="TERMINATE" danger>
                      <i class="ri-close-circle-line" style="color: #ff4d4f; margin-right: 8px"></i>终止
                    </a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </div>
          </div>
        </template>
      </div>

      <a-table
        v-else
        :columns="columns"
        :data-source="tenantData.instances"
        :loading="tenantData.loading"
        row-key="instanceId"
        size="middle"
        :pagination="false"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <a-tooltip :title="record.name" placement="topLeft">
              <span class="instance-name-cell">{{ record.name }}</span>
            </a-tooltip>
          </template>
          <template v-if="column.key === 'state'">
            <a-badge :status="stateColorMap[record.state] || 'default'" :text="record.state" />
          </template>
          <template v-if="column.key === 'shape'">
            <div class="shape-cell">
              <div class="shape-main">{{ record.ocpus }}C / {{ record.memoryInGBs }}G</div>
              <div class="shape-sub" :title="record.shape">{{ record.shape }}</div>
            </div>
          </template>
          <template v-if="column.key === 'publicIp'">
            <a-typography-text v-if="record.publicIp" copyable class="ip-copy">{{ record.publicIp }}</a-typography-text>
            <span v-else style="color: var(--text-sub)">—</span>
          </template>
          <template v-if="column.key === 'action'">
            <a-space :size="2">
              <a-button type="link" size="small" @click="emit('open-detail', record)">详情</a-button>
              <a-dropdown :trigger="['click']">
                <a-button type="link" size="small" class="instance-action-trigger" :loading="actionLoading[record.instanceId]">
                  实例操作
                  <DownOutlined style="font-size: 10px; margin-left: 2px" />
                </a-button>
                <template #overlay>
                  <a-menu class="instance-action-menu" @click="(info: any) => emitMenuClick(record, info.key)">
                    <a-menu-item key="START">
                      <i class="ri-play-fill" style="color: #52c41a; margin-right: 8px"></i>启动
                    </a-menu-item>
                    <a-menu-item key="SOFTRESET" :disabled="record.state !== 'RUNNING'">
                      <i class="ri-restart-line" style="color: #faad14; margin-right: 8px"></i>重启
                    </a-menu-item>
                    <a-menu-item key="RESET" :disabled="record.state !== 'RUNNING'">
                      <i class="ri-shut-down-line" style="color: #ff7a45; margin-right: 8px"></i>断电重启
                    </a-menu-item>
                    <a-menu-item key="SOFTSTOP" :disabled="record.state !== 'RUNNING'">
                      <i class="ri-stop-fill" style="color: #8c8c8c; margin-right: 8px"></i>暂停
                    </a-menu-item>
                    <a-menu-divider />
                    <a-menu-item key="TERMINATE" danger>
                      <i class="ri-close-circle-line" style="color: #ff4d4f; margin-right: 8px"></i>终止
                    </a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { DownOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import VirtualTenantCardList from '../tenant/VirtualTenantCardList.vue'

type TenantDataLike = {
  tenant: any
  instances: any[]
  loading: boolean
}

const props = defineProps<{
  tenantData: TenantDataLike
  region: string
  regionOptions: { label: string; value: string }[]
  regionLoading: boolean
  isMobile: boolean
  stateColorMap: Record<string, string>
  actionLoading: Record<string, boolean>
  virtualCardMin: number
  mobileVirtualMaxHeight: number
  virtualResetKey: string
  itemKey: (item: any, index: number) => string
}>()

const emit = defineEmits<{
  (e: 'update:region', value: string): void
  (e: 'region-change'): void
  (e: 'refresh'): void
  (e: 'open-detail', record: any): void
  (e: 'menu-click', payload: { record: any; key: string }): void
}>()

const regionValue = computed({
  get: () => props.region,
  set: (value: string) => emit('update:region', value),
})

const columns = [
  { title: '名称', dataIndex: 'name', key: 'name', width: 180, ellipsis: true },
  { title: '规格', key: 'shape', width: 180 },
  { title: '公网 IP', dataIndex: 'publicIp', key: 'publicIp', width: 150 },
  { title: '状态', dataIndex: 'state', key: 'state', width: 110 },
  { title: '操作', key: 'action', width: 180 },
]

function emitMenuClick(record: any, key: unknown) {
  emit('menu-click', { record, key: String(key) })
}

</script>

<style scoped>
.instance-panel {
  background: transparent;
  border: none;
  border-radius: 0;
  padding: 0;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  box-shadow: none;
}
.instance-panel-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-bottom: 14px;
  padding: 10px 12px;
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: 10px;
}
.instance-panel-toolbar-label {
  color: var(--text-sub);
  font-size: 12px;
  white-space: nowrap;
}
.instance-panel-region-select {
  min-width: 200px;
  flex: 1 1 220px;
  max-width: 100%;
}
.instance-panel-region-hint {
  font-size: 12px;
  color: var(--text-sub);
  line-height: 1.3;
}
.instance-name-cell {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
  font-weight: 600;
}
.shape-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}
.shape-main {
  font-weight: 600;
  color: var(--text-main);
  font-size: 13px;
}
.shape-sub {
  font-size: 11px;
  color: var(--text-sub);
  font-family: 'JetBrains Mono', 'SF Mono', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 160px;
}
.ip-copy :deep(.ant-typography-copy) {
  margin-inline-start: 4px;
}
.instance-action-trigger {
  padding-inline: 4px;
}
.instance-mobile-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.instance-mobile-card {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  content-visibility: auto;
  contain-intrinsic-size: 180px;
}
.imc-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border);
}
.imc-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-main);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}
.imc-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.imc-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 13px;
}
.imc-label {
  color: var(--text-sub);
  flex-shrink: 0;
  width: 60px;
  padding-top: 2px;
}
.imc-value-group {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}
.imc-value-main {
  color: var(--text-main);
  font-weight: 600;
}
.imc-value-sub {
  font-size: 11px;
  color: var(--text-sub);
  font-family: 'JetBrains Mono', 'SF Mono', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.imc-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 6px;
  border-top: 1px solid var(--border);
}
.imc-footer :deep(.ant-btn-link) {
  font-size: 14px;
  height: 32px;
  padding-inline: 10px;
}
:global(.instance-action-menu) {
  min-width: 150px;
}
:global(.instance-action-menu .ri-play-fill),
:global(.instance-action-menu .ri-restart-line),
:global(.instance-action-menu .ri-shut-down-line),
:global(.instance-action-menu .ri-stop-fill),
:global(.instance-action-menu .ri-close-circle-line) {
  font-size: 14px;
  vertical-align: -2px;
}
@media (min-width: 769px) {
  .instance-panel-region-hint {
    flex: 0 0 auto;
    margin-left: auto;
  }
}
@media (max-width: 768px) {
  .instance-panel-toolbar {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
  }
  .instance-panel-region-select {
    width: 100% !important;
    min-width: 0;
    flex: none;
    max-width: none;
  }
  .instance-panel-region-hint {
    margin-left: 0;
    width: 100%;
  }
}
</style>
