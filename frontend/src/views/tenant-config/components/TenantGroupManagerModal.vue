<template>
  <a-modal
    :mask-closable="false"
    :keyboard="false"
    :open="open"
    title="管理分组"
    :width="isMobile ? '100%' : 700"
    :footer="null"
    centered
    @update:open="emit('update:open', $event)"
  >
    <a-button type="primary" block style="margin-bottom: 20px" @click="emit('open-create-form')">
      <template #icon><PlusOutlined /></template>添加分组
    </a-button>

    <div v-if="createFormVisible" style="margin-bottom: 16px; padding: 16px; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-card);">
      <a-space direction="vertical" style="width: 100%">
        <a-input :value="createName" placeholder="分组名称" @update:value="emit('update:createName', $event)" @press-enter="emit('create-group')" />
        <a-select :value="createLevel" style="width: 100%" @update:value="emit('update:createLevel', $event)">
          <a-select-option value="1">一级分组</a-select-option>
          <a-select-option value="2">二级分组（子分组）</a-select-option>
        </a-select>
        <a-select v-if="createLevel === '2'" :value="createParent" placeholder="选择父分组" style="width: 100%" @update:value="emit('update:createParent', $event)">
          <a-select-option v-for="group in groupData.level1" :key="group" :value="group">{{ group }}</a-select-option>
        </a-select>
        <a-space>
          <a-button type="primary" :loading="createLoading" @click="emit('create-group')">保存</a-button>
          <a-button @click="emit('update:createFormVisible', false)">取消</a-button>
        </a-space>
      </a-space>
    </div>

    <div v-if="groupTree.length" style="display: flex; flex-direction: column; gap: 8px;">
      <div v-for="(group, groupIndex) in groupTree" :key="group.key">
        <div style="background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; padding: 14px 16px; display: flex; align-items: center; justify-content: space-between; transition: all 0.2s;">
          <div style="display: flex; align-items: center; gap: 12px;">
            <div class="group-dot" :style="{ background: groupColors[groupIndex % groupColors.length] }"></div>
            <span style="font-weight: 600;">{{ group.label }}</span>
            <span style="font-size: 13px; color: var(--text-sub);">{{ groupTotalCount(group) }} 个租户</span>
            <span v-if="group.children?.length" style="font-size: 12px; color: var(--text-sub);">({{ group.children.length }} 个子分组)</span>
          </div>
          <div style="display: flex; gap: 8px;">
            <a-button size="small" @click="emit('mgr-add-sub', group.label)">
              <template #icon><PlusOutlined /></template>子分组
            </a-button>
            <a-button size="small" @click="emit('rename-group', group.label, '1')">
              <template #icon><EditOutlined /></template>
            </a-button>
            <a-popconfirm title="删除该分组？租户将移至「未分组」" @confirm="emit('delete-group', group.label, '1')">
              <a-button size="small" danger><template #icon><DeleteOutlined /></template></a-button>
            </a-popconfirm>
          </div>
        </div>
        <div
          v-for="sub in (group.children || [])"
          :key="sub.key"
          style="margin-left: 32px; margin-top: 6px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; padding: 12px 16px; display: flex; align-items: center; justify-content: space-between;"
        >
          <div style="display: flex; align-items: center; gap: 12px;">
            <span style="font-weight: 500;">{{ sub.label }}</span>
            <span style="font-size: 13px; color: var(--text-sub);">{{ sub.tenants.length }} 个租户</span>
          </div>
          <div style="display: flex; gap: 8px;">
            <a-button size="small" @click="emit('rename-group', sub.label, '2')">
              <template #icon><EditOutlined /></template>
            </a-button>
            <a-popconfirm title="删除该子分组？" @confirm="emit('delete-group', sub.label, '2')">
              <a-button size="small" danger><template #icon><DeleteOutlined /></template></a-button>
            </a-popconfirm>
          </div>
        </div>
      </div>
    </div>
    <div v-else style="text-align: center; padding: 40px; color: var(--text-sub);">暂无分组</div>
  </a-modal>
</template>

<script setup lang="ts">
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons-vue'

defineOptions({ name: 'TenantGroupManagerModal' })

interface GroupNode {
  label: string
  key: string
  children?: GroupNode[]
  tenants: any[]
}

defineProps<{
  open: boolean
  isMobile: boolean
  groupTree: GroupNode[]
  groupColors: string[]
  groupData: { level1: string[]; level2: Record<string, string[]> }
  createFormVisible: boolean
  createName: string
  createLevel: string
  createParent: string
  createLoading: boolean
  groupTotalCount: (group: GroupNode) => number
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:createFormVisible', value: boolean): void
  (e: 'update:createName', value: string): void
  (e: 'update:createLevel', value: string): void
  (e: 'update:createParent', value: string): void
  (e: 'open-create-form'): void
  (e: 'create-group'): void
  (e: 'mgr-add-sub', parentName: string): void
  (e: 'rename-group', name: string, level: string): void
  (e: 'delete-group', name: string, level: string): void
}>()
</script>

<style scoped>
.group-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}
</style>
