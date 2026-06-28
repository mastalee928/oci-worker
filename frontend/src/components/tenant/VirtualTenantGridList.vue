<template>
  <div ref="parentRef" class="virtual-tenant-grid-list" :style="{ height: listHeight + 'px', overflow: 'auto' }">
    <div class="virtual-tenant-grid-spacer" :style="{ height: totalSize + 'px' }">
      <div
        v-for="row in virtualRows"
        :key="row.key"
        :ref="measureVirtualRow"
        :data-index="row.index"
        class="virtual-tenant-grid-row"
        :style="{
          transform: `translateY(${row.start}px)`,
          gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
          gap: gridGap + 'px',
          paddingBottom: gridGap + 'px',
        }"
      >
        <div v-for="entry in row.items" :key="entry.key" class="virtual-tenant-grid-cell">
          <slot name="item" :item="entry.item" :index="entry.index" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useVirtualizer } from '@tanstack/vue-virtual'

const props = withDefaults(
  defineProps<{
    items: any[]
    minItemWidth?: number
    estimateSize?: number
    gap?: number
    maxHeight?: number
    resetKey?: string | number
    itemKey?: (item: any, index: number) => string
  }>(),
  {
    minItemWidth: 260,
    estimateSize: 236,
    gap: 16,
    maxHeight: 720,
    resetKey: '',
    itemKey: (item: any, index: number) =>
      String((item as { id?: string })?.id ?? index),
  },
)

const parentRef = ref<HTMLElement | null>(null)
const parentWidth = ref(0)
let resizeObserver: ResizeObserver | null = null
let measureFrame: number | null = null

function positiveNumber(value: number, fallback: number) {
  return Number.isFinite(value) && value > 0 ? value : fallback
}

const gridGap = computed(() => (Number.isFinite(props.gap) ? Math.max(0, props.gap) : 16))
const minItemWidth = computed(() => positiveNumber(props.minItemWidth, 260))
const rowEstimate = computed(() => positiveNumber(props.estimateSize, 236))
const maxListHeight = computed(() => Math.max(rowEstimate.value, positiveNumber(props.maxHeight, 720)))

const columns = computed(() => {
  const width = parentWidth.value
  if (!width) return 1
  return Math.max(1, Math.floor((width + gridGap.value) / (minItemWidth.value + gridGap.value)))
})

const rowCount = computed(() => Math.ceil(props.items.length / columns.value))

const virtualizer = useVirtualizer(
  computed(() => ({
    count: rowCount.value,
    getScrollElement: () => parentRef.value,
    estimateSize: () => rowEstimate.value,
    overscan: 4,
  })),
)

const listHeight = computed(() => {
  if (!props.items.length) return 0
  const estimated = rowCount.value * rowEstimate.value
  return Math.min(Math.max(estimated, rowEstimate.value), maxListHeight.value)
})

const totalSize = computed(() => virtualizer.value.getTotalSize())

const virtualRows = computed(() =>
  virtualizer.value.getVirtualItems().map((vi) => {
    const startIndex = vi.index * columns.value
    const rowItems = props.items.slice(startIndex, startIndex + columns.value)
    return {
      key: String(vi.key),
      index: vi.index,
      start: vi.start,
      items: rowItems.map((item, offset) => {
        const index = startIndex + offset
        return {
          key: props.itemKey(item, index),
          index,
          item,
        }
      }),
    }
  }).filter((row) => row.items.length > 0),
)

function measureVirtualRow(el: Element | any) {
  if (el instanceof HTMLElement) {
    virtualizer.value.measureElement(el)
  }
}

function measureAfterLayout(resetScroll = false) {
  if (measureFrame !== null) {
    window.cancelAnimationFrame(measureFrame)
  }
  measureFrame = window.requestAnimationFrame(async () => {
    measureFrame = null
    await nextTick()
    virtualizer.value.measure()
    if (resetScroll) virtualizer.value.scrollToOffset(0)
  })
}

function updateWidth() {
  const width = parentRef.value?.clientWidth || 0
  if (Math.abs(width - parentWidth.value) < 1) return
  parentWidth.value = width
  measureAfterLayout(false)
}

onMounted(async () => {
  await nextTick()
  updateWidth()
  if (parentRef.value) {
    resizeObserver = new ResizeObserver(updateWidth)
    resizeObserver.observe(parentRef.value)
  }
})

onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  if (measureFrame !== null) {
    window.cancelAnimationFrame(measureFrame)
    measureFrame = null
  }
})

watch(
  () => [props.items.length, props.resetKey, columns.value],
  () => measureAfterLayout(true),
)
</script>

<style scoped>
.virtual-tenant-grid-list {
  width: 100%;
  overscroll-behavior: contain;
}

.virtual-tenant-grid-spacer {
  position: relative;
  width: 100%;
}

.virtual-tenant-grid-row {
  position: absolute;
  top: 0;
  left: 0;
  display: grid;
  width: 100%;
  box-sizing: border-box;
  will-change: transform;
}

.virtual-tenant-grid-cell {
  min-width: 0;
}

.virtual-tenant-grid-cell :deep(.tenant-card) {
  height: 100%;
  margin-bottom: 0;
}
</style>
