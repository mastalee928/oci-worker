<template>
  <div class="instance-group-list">
    <section v-for="(group, index) in groups" :key="group.key" class="instance-group-card">
      <button
        type="button"
        class="instance-group-header"
        :class="{ disabled: groupTenantCount(group) === 0 }"
        :disabled="groupTenantCount(group) === 0"
        :aria-expanded="isGroupOpen(group.key)"
        @click="toggleGroup(group.key)"
      >
        <i :class="isGroupOpen(group.key) ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'" aria-hidden="true"></i>
        <span class="group-dot" :style="{ '--group-color': groupColors[index % groupColors.length] }"></span>
        <span class="group-label">{{ group.label }}</span>
        <a-badge :count="groupTenantCount(group)" :show-zero="true" class="oci-group-count-badge" />
      </button>

      <div v-if="isGroupOpen(group.key)" class="instance-group-body">
        <section v-for="child in group.children || []" :key="child.key" class="instance-subgroup">
          <button
            type="button"
            class="instance-subgroup-header"
            :class="{ disabled: child.tenants.length === 0 }"
            :disabled="child.tenants.length === 0"
            :aria-expanded="isL2Open(child.key)"
            @click="toggleL2(child.key)"
          >
            <i :class="isL2Open(child.key) ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'" aria-hidden="true"></i>
            <span class="group-label">{{ child.label }}</span>
            <a-badge :count="child.tenants.length" :show-zero="true" class="oci-group-count-badge" />
          </button>
          <div v-if="isL2Open(child.key)" class="tenant-rows">
            <TenantRow
              v-for="td in child.tenants"
              :key="td.tenant.id"
              :tenant-data="td"
              :active="td.tenant.id === activeTenantId"
              :loading="isTenantLoading(td)"
              :is-mobile="isMobile"
              @select="$emit('select-tenant', td)"
              @open-vcn="$emit('open-vcn', td.tenant)"
              @open-storage="$emit('open-storage', td.tenant)"
              @open-quick-task="$emit('open-quick-task', td.tenant)"
            />
          </div>
        </section>

        <section v-if="group.tenants.length" class="instance-subgroup direct-tenants">
          <div class="tenant-rows">
            <TenantRow
              v-for="td in group.tenants"
              :key="td.tenant.id"
              :tenant-data="td"
              :active="td.tenant.id === activeTenantId"
              :loading="isTenantLoading(td)"
              :is-mobile="isMobile"
              @select="$emit('select-tenant', td)"
              @open-vcn="$emit('open-vcn', td.tenant)"
              @open-storage="$emit('open-storage', td.tenant)"
              @open-quick-task="$emit('open-quick-task', td.tenant)"
            />
          </div>
        </section>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { defineComponent, h, type PropType } from 'vue'
import { Button as AButton, Tag as ATag } from 'ant-design-vue'

interface TenantData {
  tenant: any
  instances: any[]
  loading: boolean
  collapsed: boolean
}

interface GroupNode {
  label: string
  key: string
  children?: GroupNode[]
  tenants: TenantData[]
}

const props = defineProps<{
  groups: GroupNode[]
  activeGroupKeys: string[]
  activeL2Keys: string[]
  activeTenantId: string
  isMobile: boolean
  groupTenantCount: (group: GroupNode) => number
  isTenantLoading: (tenantData: TenantData) => boolean
}>()

const emit = defineEmits<{
  (e: 'select-tenant', tenantData: TenantData): void
  (e: 'open-vcn', tenant: any): void
  (e: 'open-storage', tenant: any): void
  (e: 'open-quick-task', tenant: any): void
  (e: 'collapse-change', keys: string[]): void
  (e: 'l2-collapse-change', keys: string[]): void
}>()

const groupColors = ['#6366f1', '#22c55e', '#f97316', '#8b5cf6', '#ec4899', '#f59e0b', '#14b8a6', '#3b82f6']

function isGroupOpen(key: string) {
  return props.activeGroupKeys.includes(key)
}

function isL2Open(key: string) {
  return props.activeL2Keys.includes(key)
}

function toggleGroup(key: string) {
  const keys = new Set(props.activeGroupKeys)
  if (keys.has(key)) keys.delete(key)
  else keys.add(key)
  emit('collapse-change', [...keys])
}

function toggleL2(key: string) {
  const keys = new Set(props.activeL2Keys)
  if (keys.has(key)) keys.delete(key)
  else keys.add(key)
  emit('l2-collapse-change', [...keys])
}

const TenantRow = defineComponent({
  name: 'InstanceTenantGroupedListRow',
  props: {
    tenantData: { type: Object as PropType<TenantData>, required: true },
    active: { type: Boolean, default: false },
    loading: { type: Boolean, default: false },
    isMobile: { type: Boolean, default: false },
  },
  emits: ['select', 'open-vcn', 'open-storage', 'open-quick-task'],
  setup(rowProps, { emit: rowEmit }) {
    const action = (
      label: string,
      icon: string,
      event: 'select' | 'open-vcn' | 'open-storage' | 'open-quick-task',
      primary = false,
    ) => h(AButton, {
      type: primary ? 'primary' : 'default',
      size: 'small',
      loading: primary && rowProps.loading,
      onClick: () => rowEmit(event),
    }, { default: () => [h('i', { class: icon, 'aria-hidden': 'true' }), label] })

    return () => h('div', { class: ['tenant-list-row', { active: rowProps.active }] }, [
      h('div', { class: 'tenant-identity' }, [
        h('div', { class: 'tenant-primary-name' }, String(rowProps.tenantData.tenant?.username || '未命名租户')),
        rowProps.tenantData.tenant?.tenantName
          ? h('div', { class: 'tenant-secondary-name' }, String(rowProps.tenantData.tenant.tenantName))
          : null,
      ]),
      h(ATag, { class: 'tenant-region' }, { default: () => String(rowProps.tenantData.tenant?.ociRegion || '—') }),
      h('div', { class: ['tenant-actions', { mobile: rowProps.isMobile }] }, [
        action('实例管理', 'ri-server-line', 'select', true),
        action('VCN', 'ri-share-line', 'open-vcn'),
        action('存储', 'ri-database-2-line', 'open-storage'),
        action('快捷开机', 'ri-play-circle-line', 'open-quick-task'),
      ]),
    ])
  },
})
</script>

<style scoped>
.instance-group-list { display: grid; gap: 12px; margin-bottom: 18px; }
.instance-group-card { overflow: hidden; border: 1px solid var(--border); border-radius: 14px; background: color-mix(in srgb, var(--bg-card) 90%, transparent); box-shadow: 0 8px 24px -22px color-mix(in srgb, var(--text-main) 34%, transparent); transition: border-color .18s ease, box-shadow .18s ease; }
.instance-group-card:hover { border-color: color-mix(in srgb, var(--primary) 32%, var(--border)); box-shadow: 0 12px 28px -22px color-mix(in srgb, var(--primary) 44%, transparent); }
.instance-group-header, .instance-subgroup-header { width: 100%; border: 0; display: flex; align-items: center; color: var(--text-main); text-align: left; cursor: pointer; }
.instance-group-header { min-height: 56px; gap: 9px; padding: 14px 16px; background: transparent; font: 600 14px/1.4 inherit; }
.instance-group-header:hover, .instance-subgroup-header:hover { background: color-mix(in srgb, var(--primary-light) 58%, transparent); }
.instance-group-header.disabled, .instance-subgroup-header.disabled { cursor: default; opacity: .58; }
.instance-group-header > i, .instance-subgroup-header > i { color: var(--text-sub); font-size: 17px; }
.group-dot { --group-color: var(--primary); width: 9px; height: 9px; border-radius: 50%; background: var(--group-color); box-shadow: 0 0 0 4px color-mix(in srgb, var(--group-color) 13%, transparent); }
.group-label { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.instance-group-body { padding: 12px 14px 14px 38px; border-top: 1px solid color-mix(in srgb, var(--border) 76%, transparent); }
.instance-subgroup { overflow: hidden; border: 1px solid var(--border); border-radius: 12px; background: color-mix(in srgb, var(--panel-bg) 82%, transparent); }
.instance-subgroup + .instance-subgroup { margin-top: 9px; }
.instance-subgroup-header { min-height: 48px; gap: 8px; padding: 12px 14px; background: transparent; font: 500 13px/1.4 inherit; }
.direct-tenants { padding: 4px 12px; }
.tenant-rows { border-top: 1px solid color-mix(in srgb, var(--border) 72%, transparent); }
.direct-tenants .tenant-rows { border-top: 0; }
:deep(.tenant-list-row) { min-height: 62px; padding: 10px 8px; border-bottom: 1px solid var(--border); display: grid; grid-template-columns: minmax(220px, 1fr) auto auto; align-items: center; gap: 14px; transition: background-color .16s ease, box-shadow .16s ease; }
:deep(.tenant-list-row:last-child) { border-bottom: 0; }
:deep(.tenant-list-row:hover) { background: color-mix(in srgb, var(--primary-light) 58%, transparent); box-shadow: inset 3px 0 0 color-mix(in srgb, var(--primary) 70%, transparent); }
:deep(.tenant-list-row.active) { background: color-mix(in srgb, var(--primary-light) 82%, transparent); box-shadow: inset 3px 0 0 var(--primary); }
:deep(.tenant-identity) { min-width: 0; }
:deep(.tenant-primary-name) { overflow: hidden; color: var(--text-main); font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
:deep(.tenant-secondary-name) { margin-top: 2px; overflow: hidden; color: var(--text-sub); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
:deep(.tenant-region) { margin: 0; border-color: color-mix(in srgb, var(--border) 82%, transparent); border-radius: 999px; background: color-mix(in srgb, var(--input-bg) 82%, transparent); color: var(--text-sub); font-size: 11px; }
:deep(.tenant-actions) { display: flex; justify-content: flex-end; gap: 7px; flex-wrap: wrap; }
:deep(.tenant-actions .ant-btn) { min-height: 30px; border-radius: 9px; font-weight: 500; box-shadow: none; }
:deep(.tenant-actions .ant-btn i) { margin-right: 5px; }
:deep(.tenant-actions .ant-btn:not(.ant-btn-primary)) { border-color: color-mix(in srgb, var(--border) 88%, transparent); background: color-mix(in srgb, var(--bg-card) 70%, transparent); color: var(--text-main); }
:deep(.tenant-actions .ant-btn:not(.ant-btn-primary):hover) { border-color: color-mix(in srgb, var(--primary) 45%, var(--border)); background: var(--primary-light); color: var(--primary); }
@media (max-width: 768px) {
  .instance-group-body { padding: 10px; }
  :deep(.tenant-list-row) { grid-template-columns: 1fr; gap: 9px; padding: 12px 8px; }
  :deep(.tenant-region) { justify-self: start; }
  :deep(.tenant-actions) { justify-content: flex-start; }
}
</style>
