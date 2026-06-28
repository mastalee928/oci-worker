<template>
  <div class="announcement-inbox-panel">
    <div class="announcement-toolbar">
      <a-select
        v-model:value="rangeModel"
        class="announcement-filter-select"
        :options="timeRangeOptions"
        @change="onRangeChange"
      />
      <a-range-picker
        v-if="rangeModel === 'custom'"
        v-model:value="datesModel"
        value-format="YYYY-MM-DD HH:mm:ss"
        format="YYYY-MM-DD HH:mm"
        :show-time="{ format: 'HH:mm' }"
        class="announcement-date-range"
        @change="$emit('filtersChange')"
      />
      <a-select
        v-model:value="eventTypesModel"
        mode="multiple"
        :show-search="false"
        allow-clear
        class="announcement-event-filter"
        placeholder="全部事件"
        :options="eventFilterOptions"
        @change="$emit('filtersChange')"
      />
      <a-input-search
        v-model:value="keywordModel"
        placeholder="搜索摘要、服务、区域、租户名"
        allow-clear
        @search="$emit('search')"
      />
      <a-button :loading="loading" @click="$emit('refresh')">刷新</a-button>
    </div>

    <a-spin :spinning="loading">
      <a-empty v-if="!inbox.records.length" description="暂无公告" />
      <div
        v-else
        ref="listRef"
        class="announcement-virtual-list"
        :style="{ height: virtualListHeight + 'px' }"
      >
        <div class="announcement-virtual-spacer" :style="{ height: totalSize + 'px' }">
          <div
            v-for="row in virtualRows"
            :key="row.key"
            :ref="measureVirtualRow"
            :data-index="row.index"
            class="announcement-virtual-row"
            :style="{ transform: `translateY(${row.start}px)` }"
          >
            <div class="announcement-item">
              <div class="announcement-item-main">
                <a-space wrap size="small">
                  <a-tag>{{ row.item.announcementTypeLabel || row.item.announcementType || '公告' }}</a-tag>
                  <span
                    v-if="row.item.announcementType && row.item.announcementTypeLabel !== row.item.announcementType"
                    class="announcement-type-origin"
                  >
                    {{ row.item.announcementType }}
                  </span>
                  <a-tag v-if="row.item.read" color="green">已读</a-tag>
                  <a-tag v-if="row.item.ignored" color="default">已忽略</a-tag>
                  <a-tag v-if="row.item.pushedBatchId" color="blue">{{ row.item.pushedBatchId }}</a-tag>
                </a-space>
                <div class="announcement-summary">{{ row.item.summary || '-' }}</div>
                <div class="announcement-meta">
                  {{ formatDateTime(row.item.timeCreated) }} · 影响 {{ row.item.tenantCount || 0 }} 个租户 · {{ row.item.tenantPreview || '-' }}
                </div>
                <div v-if="row.item.timeWindowText" class="announcement-window">{{ row.item.timeWindowText }}</div>
              </div>
              <a-space wrap>
                <a-button size="small" @click="$emit('openDetail', row.item)">详情</a-button>
                <a-button v-if="!row.item.read" size="small" @click="$emit('mark', row.item, 'read')">已读</a-button>
                <a-button v-if="!row.item.ignored" size="small" @click="$emit('mark', row.item, 'ignore')">忽略</a-button>
                <a-button v-else size="small" @click="$emit('mark', row.item, 'unignore')">取消忽略</a-button>
              </a-space>
            </div>
          </div>
        </div>
      </div>
    </a-spin>

    <a-pagination
      v-if="inbox.total > inbox.size"
      size="small"
      class="announcement-pagination"
      :current="inbox.current"
      :page-size="inbox.size"
      :total="inbox.total"
      @change="$emit('pageChange', $event)"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useVirtualizer } from '@tanstack/vue-virtual'

defineOptions({ name: 'AnnouncementInboxPanel' })

type AnnouncementRange = '24h' | '7d' | '30d' | 'all' | 'custom'
type AnnouncementItem = Record<string, any> & { aggregateKey: string }
type InboxState = { records: AnnouncementItem[]; total: number; current: number; size: number }
type SelectOption = { label: string; value: string }

const props = defineProps<{
  inbox: InboxState
  loading: boolean
  range: AnnouncementRange
  dates: string[]
  eventTypes: string[]
  keyword: string
  timeRangeOptions: SelectOption[]
  eventFilterOptions: SelectOption[]
  formatDateTime: (value: any) => string
}>()

const emit = defineEmits<{
  (e: 'update:range', value: AnnouncementRange): void
  (e: 'update:dates', value: string[]): void
  (e: 'update:eventTypes', value: string[]): void
  (e: 'update:keyword', value: string): void
  (e: 'rangeChange', value: AnnouncementRange): void
  (e: 'filtersChange'): void
  (e: 'search'): void
  (e: 'refresh'): void
  (e: 'pageChange', page: number): void
  (e: 'openDetail', item: AnnouncementItem): void
  (e: 'mark', item: AnnouncementItem, action: 'read' | 'ignore' | 'unignore'): void
}>()

const ROW_ESTIMATE = 118
const MAX_LIST_HEIGHT = 640
const listRef = ref<HTMLElement | null>(null)

const rangeModel = computed({
  get: () => props.range,
  set: (value: AnnouncementRange) => emit('update:range', value),
})
const datesModel = computed({
  get: () => props.dates,
  set: (value: string[]) => emit('update:dates', value || []),
})
const eventTypesModel = computed({
  get: () => props.eventTypes,
  set: (value: string[]) => emit('update:eventTypes', value || []),
})
const keywordModel = computed({
  get: () => props.keyword,
  set: (value: string) => emit('update:keyword', value || ''),
})

const virtualizer = useVirtualizer(
  computed(() => ({
    count: props.inbox.records.length,
    getScrollElement: () => listRef.value,
    estimateSize: () => ROW_ESTIMATE,
    overscan: 6,
  })),
)

const virtualListHeight = computed(() => {
  const count = props.inbox.records.length
  if (!count) return 0
  return Math.min(Math.max(count * ROW_ESTIMATE, ROW_ESTIMATE), MAX_LIST_HEIGHT)
})
const totalSize = computed(() => virtualizer.value.getTotalSize())
const virtualRows = computed(() =>
  virtualizer.value.getVirtualItems().map((vi) => ({
    key: props.inbox.records[vi.index]?.aggregateKey || String(vi.key),
    index: vi.index,
    start: vi.start,
    item: props.inbox.records[vi.index],
  })).filter((row) => row.item),
)

function onRangeChange(value: AnnouncementRange) {
  emit('rangeChange', value)
}

function measureVirtualRow(el: Element | any) {
  if (el instanceof HTMLElement) {
    virtualizer.value.measureElement(el)
  }
}

watch(
  () => [props.inbox.records.length, props.inbox.current, props.keyword, props.range, props.eventTypes.join(',')],
  async () => {
    await nextTick()
    virtualizer.value.measure()
    virtualizer.value.scrollToOffset(0)
  },
)
</script>

<style scoped>
.announcement-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}
.announcement-filter-select {
  width: 132px;
}
.announcement-date-range {
  width: 300px;
}
.announcement-event-filter {
  min-width: 260px;
}
.announcement-toolbar :deep(.ant-input-search) {
  max-width: 360px;
  min-width: 220px;
}
.announcement-virtual-list {
  width: 100%;
  overflow: auto;
  overscroll-behavior: contain;
}
.announcement-virtual-spacer {
  position: relative;
  width: 100%;
}
.announcement-virtual-row {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  padding-bottom: 10px;
  will-change: transform;
}
.announcement-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md, 8px);
  background: var(--input-bg, rgba(255, 255, 255, 0.03));
}
.announcement-item-main {
  min-width: 0;
  flex: 1;
}
.announcement-summary {
  margin-top: 7px;
  color: var(--text-main);
  font-weight: 600;
  line-height: 1.45;
  word-break: break-word;
}
.announcement-meta,
.announcement-window,
.announcement-type-origin {
  font-size: 12px;
  color: var(--text-sub);
}
.announcement-window {
  margin-top: 6px;
  white-space: pre-line;
}
.announcement-type-origin {
  align-self: center;
}
.announcement-pagination {
  margin-top: 14px;
  text-align: right;
}

@media (max-width: 768px) {
  .announcement-toolbar,
  .announcement-item {
    flex-direction: column;
    align-items: stretch;
  }
  .announcement-filter-select,
  .announcement-date-range,
  .announcement-event-filter {
    width: 100%;
    min-width: 0;
  }
  .announcement-toolbar :deep(.ant-input-search) {
    max-width: 100%;
    min-width: 0;
  }
  .announcement-virtual-list {
    max-height: 60vh;
  }
}
</style>
