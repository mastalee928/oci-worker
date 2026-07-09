<template>
  <a-modal
    v-model:open="openModel"
    title="选择接收云公告的租户"
    :width="isMobile ? '100%' : 980"
    :footer="null"
    :keyboard="false"
    centered
    class="tenant-picker-modal"
  >
    <div class="tenant-picker-modal-body">
      <div class="tenant-picker-toolbar">
        <a-input-search
          v-model:value="searchModel"
          class="tenant-picker-search"
          placeholder="搜索租户名、用户名、区域"
          allow-clear
        />
        <div class="tenant-picker-actions">
          <a-popconfirm
            :title="`确认添加当前筛选出的 ${filteredTenants.length} 个租户？`"
            ok-text="确认"
            cancel-text="取消"
            :disabled="filteredTenants.length === 0"
            @confirm="$emit('addFilteredTenants')"
          >
            <a-button size="small" :disabled="filteredTenants.length === 0">全部添加</a-button>
          </a-popconfirm>
          <a-popconfirm
            :title="`确认移除已选择的 ${selectedTenants.length} 个租户？`"
            ok-text="确认"
            cancel-text="取消"
            :disabled="selectedTenants.length === 0"
            @confirm="$emit('clearTenants')"
          >
            <a-button size="small" danger :disabled="selectedTenants.length === 0">全部移除</a-button>
          </a-popconfirm>
        </div>
      </div>

      <a-select
        v-if="isMobile"
        v-model:value="activeGroupKeyModel"
        class="tenant-mobile-group-select"
        :show-search="false"
      >
        <a-select-option
          v-for="group in groupOptions"
          :key="group.key"
          :value="group.key"
        >
          <span class="tenant-mobile-group-option" :class="{ 'tenant-mobile-group-option--child': group.level === '2' }">
            <span>{{ group.label }}</span>
            <small>{{ group.count }}</small>
          </span>
        </a-select-option>
      </a-select>

      <div class="tenant-picker-grid">
        <div v-if="!isMobile" class="tenant-picker-block tenant-picker-group-block">
          <div class="tenant-picker-title">分组</div>
          <a-empty v-if="!groupOptions.length" description="暂无分组" />
          <div v-else class="tenant-group-list">
            <button
              v-for="group in groupOptions"
              :key="group.key"
              type="button"
              class="tenant-group-row tenant-group-button"
              :class="{ 'tenant-group-button--active': activeGroupKey === group.key, 'tenant-group-button--child': group.level === '2' }"
              @click="activeGroupKeyModel = group.key"
            >
              <span>{{ group.label }}</span>
              <small>{{ group.count }}</small>
            </button>
          </div>
        </div>

        <div class="tenant-picker-block">
          <div class="tenant-picker-title">当前分组租户</div>
          <div class="tenant-list">
            <div v-for="tenant in pagedFilteredTenants" :key="tenant.id" class="tenant-row">
              <div class="tenant-name">
                <strong>{{ tenant.tenantName || tenant.username || tenant.id }}</strong>
                <small>{{ tenant.username }} · {{ tenant.region || '-' }} · {{ tenant.groupLevel1 || '未分组' }}{{ tenant.groupLevel2 ? ' / ' + tenant.groupLevel2 : '' }}</small>
              </div>
              <a-checkbox
                :checked="selectedTenantIds.includes(tenant.id)"
                @change="$emit('toggleTenant', tenant.id, $event.target.checked)"
              />
            </div>
          </div>
          <a-pagination
            v-if="filteredTenants.length > pageSize"
            v-model:current="tenantPickerPageModel"
            size="small"
            class="tenant-picker-pagination"
            :page-size="pageSize"
            :total="filteredTenants.length"
            :show-size-changer="false"
          />
        </div>

        <div class="tenant-picker-block">
          <div class="tenant-picker-title">已选择接收</div>
          <a-empty v-if="!selectedTenants.length" description="尚未选择租户" />
          <div v-else class="tenant-selected-list">
            <div v-for="tenant in pagedSelectedTenants" :key="tenant.id" class="tenant-selected-row">
              <div class="tenant-name">
                <strong>{{ tenant.tenantName || tenant.username || tenant.id }}</strong>
                <small>{{ tenant.username }} · {{ tenant.region || '-' }}</small>
              </div>
              <a-button size="small" type="link" @click="$emit('toggleTenant', tenant.id, false)">移除</a-button>
            </div>
          </div>
          <a-pagination
            v-if="selectedTenants.length > pageSize"
            v-model:current="tenantSelectedPageModel"
            size="small"
            class="tenant-picker-pagination"
            :page-size="pageSize"
            :total="selectedTenants.length"
            :show-size-changer="false"
          />
        </div>
      </div>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { AnnouncementGroupOption, AnnouncementTenant } from '../../composables/useAnnouncementPush'

defineOptions({ name: 'AnnouncementTenantPickerModal' })

const props = defineProps<{
  open: boolean
  isMobile: boolean
  search: string
  activeGroupKey: string
  groupOptions: AnnouncementGroupOption[]
  filteredTenants: AnnouncementTenant[]
  selectedTenants: AnnouncementTenant[]
  pagedFilteredTenants: AnnouncementTenant[]
  pagedSelectedTenants: AnnouncementTenant[]
  selectedTenantIds: string[]
  tenantPickerPage: number
  tenantSelectedPage: number
  pageSize: number
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:search', value: string): void
  (e: 'update:activeGroupKey', value: string): void
  (e: 'update:tenantPickerPage', value: number): void
  (e: 'update:tenantSelectedPage', value: number): void
  (e: 'addFilteredTenants'): void
  (e: 'clearTenants'): void
  (e: 'toggleTenant', id: string, checked: boolean): void
}>()

const openModel = computed({
  get: () => props.open,
  set: (value: boolean) => emit('update:open', value),
})
const searchModel = computed({
  get: () => props.search,
  set: (value: string) => emit('update:search', value || ''),
})
const activeGroupKeyModel = computed({
  get: () => props.activeGroupKey,
  set: (value: string) => emit('update:activeGroupKey', value || 'ALL'),
})
const tenantPickerPageModel = computed({
  get: () => props.tenantPickerPage,
  set: (value: number) => emit('update:tenantPickerPage', value || 1),
})
const tenantSelectedPageModel = computed({
  get: () => props.tenantSelectedPage,
  set: (value: number) => emit('update:tenantSelectedPage', value || 1),
})
</script>

<style scoped>
.tenant-picker-modal :deep(.ant-modal-body) {
  max-height: calc(100vh - 132px);
  overflow: hidden;
}
.tenant-picker-modal :deep(.ant-modal-content) {
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
}
.tenant-picker-modal :deep(.ant-modal-header) {
  flex: 0 0 auto;
}
.tenant-picker-modal-body {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.tenant-picker-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.tenant-picker-search {
  width: min(420px, 100%);
  flex: 0 1 420px;
}
.tenant-picker-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex: 1 0 auto;
}
.tenant-mobile-group-select {
  width: 100%;
  margin-top: 10px;
}
.tenant-mobile-group-option {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
  max-width: 100%;
}
.tenant-mobile-group-option--child {
  padding-left: 1.5em;
}
.tenant-mobile-group-option small {
  font-size: 11px;
  line-height: 1;
  color: var(--text-sub);
}
.tenant-picker-grid {
  display: grid;
  grid-template-columns: minmax(210px, 260px) minmax(0, 1fr) minmax(240px, 300px);
  gap: 14px;
  margin-top: 12px;
  min-height: 0;
  height: min(62vh, 600px);
}
.tenant-picker-block {
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  padding: 12px;
  min-width: 0;
  min-height: 0;
  background: var(--bg-card);
  display: flex;
  flex-direction: column;
}
.tenant-picker-title {
  font-weight: 600;
  color: var(--text-main);
}
.tenant-group-list,
.tenant-list,
.tenant-selected-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
  min-height: 0;
}
.tenant-group-list {
  overflow: auto;
  padding-right: 2px;
}
.tenant-list,
.tenant-selected-list {
  overflow: visible;
}
.tenant-group-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  font-size: 13px;
  color: var(--text-main);
}
.tenant-group-button {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-md, 8px);
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.tenant-group-button:hover,
.tenant-group-button--active {
  border-color: rgba(129, 140, 248, 0.45);
  background: rgba(129, 140, 248, 0.12);
}
.tenant-group-button--child {
  padding-left: 22px;
}
.tenant-group-row span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tenant-group-row small,
.tenant-name small {
  color: var(--text-sub);
}
.tenant-row {
  content-visibility: auto;
  contain-intrinsic-size: 64px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 48px;
  gap: 8px;
  align-items: center;
  min-height: 56px;
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
}
.tenant-row:last-child {
  border-bottom: 0;
}
.tenant-name {
  min-width: 0;
}
.tenant-name strong,
.tenant-name small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tenant-selected-row {
  content-visibility: auto;
  contain-intrinsic-size: 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 56px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  background: var(--input-bg, rgba(255, 255, 255, 0.03));
}
.tenant-picker-pagination {
  margin-top: auto;
  padding-top: 10px;
  text-align: right;
}

@media (max-width: 768px) {
  .tenant-picker-grid {
    grid-template-columns: 1fr;
    height: auto;
    max-height: none;
    overflow: visible;
  }
  .tenant-picker-toolbar {
    display: contents;
  }
  .tenant-picker-search {
    order: 1;
    width: 100%;
    flex-basis: auto;
    margin-bottom: 8px;
  }
  .tenant-mobile-group-select {
    order: 2;
    margin-top: 0;
    margin-bottom: 8px;
  }
  .tenant-picker-actions {
    order: 3;
    justify-content: flex-start;
    flex: 0 0 auto;
    margin-bottom: 10px;
  }
  .tenant-picker-grid {
    order: 4;
  }
  .tenant-picker-modal :deep(.ant-modal) {
    max-width: 100%;
    margin: 8px auto;
  }
  .tenant-picker-modal :deep(.ant-modal-content) {
    max-height: calc(100vh - 16px);
  }
  .tenant-picker-modal :deep(.ant-modal-body) {
    max-height: calc(100vh - 98px);
    overflow: auto;
  }
  .tenant-picker-block {
    min-height: auto;
  }
  .tenant-group-list {
    max-height: 176px;
  }
  .tenant-list,
  .tenant-selected-list {
    overflow: visible;
  }
  .tenant-row,
  .tenant-selected-row {
    min-height: 58px;
  }
  .tenant-picker-pagination {
    margin-top: 8px;
  }
}
</style>
