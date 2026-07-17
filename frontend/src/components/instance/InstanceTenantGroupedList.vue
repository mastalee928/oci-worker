<template>
  <div class="instance-group-list">
    <section
      v-for="(group, index) in groups"
      :key="group.key"
      class="group-section"
      :style="{ '--group-color': groupColors[index % groupColors.length] }"
    >
      <div class="group-card">
        <div class="group-card-header">
          <div class="group-card-header-main">
            <div
              class="collapse-btn"
              :class="{ disabled: groupTenantCount(group) === 0 }"
              role="button"
              :tabindex="groupTenantCount(group) === 0 ? -1 : 0"
              :aria-disabled="groupTenantCount(group) === 0"
              :aria-expanded="isGroupOpen(group.key)"
              @click="toggleGroup(group.key)"
              @keydown.enter.prevent="toggleGroup(group.key)"
              @keydown.space.prevent="toggleGroup(group.key)"
            >
              <DownOutlined v-if="isGroupOpen(group.key)" />
              <RightOutlined v-else />
            </div>
            <span
              class="group-dot"
              :style="{
                background: groupColors[index % groupColors.length],
                boxShadow: `0 0 8px ${groupColors[index % groupColors.length]}80`,
              }"
            ></span>
            <span class="group-name" @click="toggleGroup(group.key)">{{ group.label }}</span>
            <div class="group-stats">
              <a-badge
                :count="groupTenantCount(group)"
                :show-zero="true"
                class="group-tenant-count-badge oci-group-count-badge"
              />
            </div>
          </div>
        </div>
      </div>

      <template v-if="isGroupOpen(group.key)">
        <section v-for="child in group.children || []" :key="child.key" class="group-card subgroup-card">
          <div class="group-card-header subgroup-header">
            <div class="group-card-header-main">
              <div
                class="collapse-btn"
                :class="{ disabled: child.tenants.length === 0 }"
                role="button"
                :tabindex="child.tenants.length === 0 ? -1 : 0"
                :aria-disabled="child.tenants.length === 0"
                :aria-expanded="isL2Open(child.key)"
                @click="toggleL2(child.key)"
                @keydown.enter.prevent="toggleL2(child.key)"
                @keydown.space.prevent="toggleL2(child.key)"
              >
                <DownOutlined v-if="isL2Open(child.key)" />
                <RightOutlined v-else />
              </div>
              <span class="subgroup-name" @click="toggleL2(child.key)">{{ child.label }}</span>
              <a-badge
                :count="child.tenants.length"
                :show-zero="true"
                class="group-tenant-count-badge oci-group-count-badge"
              />
            </div>
          </div>
          <div v-if="isL2Open(child.key)" class="group-body">
            <slot v-if="viewMode === 'card'" name="card-list" :tenants="child.tenants" />
            <div v-else class="tenant-rows">
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
          </div>
        </section>

        <section v-if="group.tenants.length" class="group-card subgroup-card">
          <div class="group-body">
            <slot v-if="viewMode === 'card'" name="card-list" :tenants="group.tenants" />
            <div v-else class="tenant-rows">
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
          </div>
        </section>
      </template>
    </section>
  </div>
</template>

<script setup lang="ts">
import { defineComponent, h, type PropType } from 'vue'
import { Button as AButton, Tag as ATag } from 'ant-design-vue'
import { DownOutlined, RightOutlined } from '@ant-design/icons-vue'
import { TENANT_GROUP_COLORS } from '../../constants/tenantGroupStyle'

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
  viewMode: 'card' | 'table'
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

const groupColors = TENANT_GROUP_COLORS

function isGroupOpen(key: string) {
  return props.activeGroupKeys.includes(key)
}

function isL2Open(key: string) {
  return props.activeL2Keys.includes(key)
}

function toggleGroup(key: string) {
  const group = props.groups.find(item => item.key === key)
  if (group && props.groupTenantCount(group) === 0) return
  const keys = new Set(props.activeGroupKeys)
  if (keys.has(key)) keys.delete(key)
  else keys.add(key)
  emit('collapse-change', [...keys])
}

function toggleL2(key: string) {
  const child = props.groups.flatMap(group => group.children || []).find(item => item.key === key)
  if (child && child.tenants.length === 0) return
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
.instance-group-list { margin-bottom: 18px; }
.group-section { position: relative; isolation: isolate; min-width: 0; margin-bottom: 12px; }
.group-section::before { content: ''; position: absolute; z-index: -2; inset: 0; border: 1px solid color-mix(in srgb, var(--group-color, var(--primary)) 16%, var(--border)); border-radius: 14px; background: linear-gradient(180deg, color-mix(in srgb, var(--group-color, var(--primary)) 7%, transparent), transparent 150px), color-mix(in srgb, var(--bg-card, #fff) 46%, transparent); pointer-events: none; }
.group-section::after { content: ''; position: absolute; z-index: -1; top: 14px; bottom: 14px; left: 0; width: 3px; border-radius: 0 3px 3px 0; background: var(--group-color, var(--primary)); box-shadow: 0 0 10px color-mix(in srgb, var(--group-color, var(--primary)) 42%, transparent); opacity: .72; pointer-events: none; }
.group-card { position: relative; overflow: hidden; padding: 14px 16px; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-card, #fff); transition: border-color .2s ease, box-shadow .2s ease; }
.group-card::before { content: ''; position: absolute; top: 0; left: 0; width: 100%; height: 3px; background: linear-gradient(90deg, var(--primary, #1677ff), #8b5cf6); transform: scaleX(0); transform-origin: left; transition: transform .3s cubic-bezier(.34, 1.56, .64, 1); }
.group-card:hover::before { transform: scaleX(1); }
.group-card:hover { border-color: rgba(129, 140, 248, .5); box-shadow: 0 8px 24px -4px rgba(99, 102, 241, .15); }
.subgroup-card { margin-top: 10px; margin-left: 32px; background: transparent; border-color: rgba(148, 163, 184, .18); box-shadow: none; backdrop-filter: none; -webkit-backdrop-filter: none; }
.subgroup-card::before { display: none; }
.subgroup-card:hover { border-color: rgba(129, 140, 248, .28); box-shadow: none; }
.group-card-header { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.group-card-header-main { display: flex; align-items: center; gap: 10px; flex: 1 1 auto; min-width: 0; }
.collapse-btn { width: 28px; height: 28px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; border: 1px solid var(--border); border-radius: 6px; background: rgba(255, 255, 255, .03); cursor: pointer; font-size: 12px; transition: all .2s; }
.collapse-btn:hover { border-color: var(--primary, #1677ff); }
.collapse-btn.disabled { cursor: default; opacity: .58; }
.group-dot { width: 12px; height: 12px; flex-shrink: 0; border-radius: 50%; }
.group-name { min-width: 0; margin: 0; overflow: hidden; flex: 0 1 auto; cursor: pointer; font-size: 16px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.subgroup-name { min-width: 0; overflow: hidden; flex: 0 1 auto; cursor: pointer; font-size: 15px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.group-stats { display: flex; align-items: center; gap: 10px; flex-shrink: 0; color: var(--text-sub, #999); font-size: 12px; }
.group-tenant-count-badge { flex-shrink: 0; }
.group-body { overflow: hidden; margin-top: 12px; }
.tenant-rows { border-top: 1px solid color-mix(in srgb, var(--border) 72%, transparent); }
.group-body > .tenant-rows { border-top: 0; }
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
  .group-card { padding: 10px 12px; border-radius: 10px; }
  .group-name, .subgroup-name { max-width: calc(100% - 86px); min-width: 48px; font-size: 14px; }
  .subgroup-card { margin-left: 16px; }
  .collapse-btn { width: 32px; height: 32px; }
  .group-card-header { gap: 8px; }
  .group-card-header-main { flex: 1 1 100%; min-width: 0; }
  .group-stats { gap: 6px; }
  :deep(.tenant-list-row) { grid-template-columns: 1fr; gap: 9px; padding: 12px 8px; }
  :deep(.tenant-region) { justify-self: start; }
  :deep(.tenant-actions) { justify-content: flex-start; }
}
</style>
