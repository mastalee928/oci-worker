<template>
  <div ref="parentRef" class="virtual-tenant-list" :style="{ height: listHeight + 'px', overflow: 'auto' }">
    <div :style="{ height: totalSize + 'px', width: '100%', position: 'relative' }">
      <div
        v-for="vRow in virtualRows"
        :key="vRow.key"
        :ref="measureVirtualRow"
        :data-index="vRow.index"
        class="virtual-tenant-row"
        :style="{
          position: 'absolute',
          top: 0,
          left: 0,
          width: '100%',
          boxSizing: 'border-box',
          paddingBottom: gap + 'px',
          transform: `translateY(${vRow.start}px)`,
        }"
      >
        <slot name="item" :item="vRow.item" :index="vRow.index" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onUnmounted, watch } from 'vue'
import { useVirtualizer } from '@tanstack/vue-virtual'

const props = withDefaults(
  defineProps<{
    items: any[]
    estimateSize?: number
    height?: number
    gap?: number
    maxHeight?: number
    resetKey?: string | number
    itemKey?: (item: any, index: number) => string
  }>(),
  {
    estimateSize: 168,
    height: 0,
    gap: 12,
    maxHeight: 640,
    resetKey: '',
    itemKey: (item: any, index: number) =>
      String((item as { id?: string })?.id ?? index),
  },
)

const parentRef = ref<HTMLElement | null>(null)
let resizeObserver: ResizeObserver | null = null
let measureFrame: number | null = null

function positiveNumber(value: number, fallback: number) {
  return Number.isFinite(value) && value > 0 ? value : fallback
}

const gap = computed(() => (Number.isFinite(props.gap) ? Math.max(0, props.gap) : 12))
const rowEstimate = computed(() => positiveNumber(props.estimateSize, 168))
const maxListHeight = computed(() => Math.max(rowEstimate.value, positiveNumber(props.maxHeight, 640)))
const fixedHeight = computed(() => positiveNumber(props.height, 0))

const virtualizer = useVirtualizer(
  computed(() => ({
    count: props.items.length,
    getScrollElement: () => parentRef.value,
    estimateSize: () => rowEstimate.value,
    overscan: 6,
  })),
)

const listHeight = computed(() => {
  const count = props.items.length
  if (!count) return 0
  if (fixedHeight.value > 0) return fixedHeight.value
  const estimated = count * rowEstimate.value
  return Math.min(Math.max(estimated, rowEstimate.value), maxListHeight.value)
})

const totalSize = computed(() => virtualizer.value.getTotalSize())

const virtualRows = computed(() =>
  virtualizer.value.getVirtualItems().map((vi) => ({
    key: props.itemKey(props.items[vi.index], vi.index),
    index: vi.index,
    start: vi.start,
    item: props.items[vi.index],
  })).filter((row) => row.item != null),
)

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

function measureVirtualRow(el: Element | any) {
  if (el instanceof HTMLElement) {
    virtualizer.value.measureElement(el)
  }
}

onMounted(async () => {
  await nextTick()
  if (parentRef.value) {
    resizeObserver = new ResizeObserver(() => measureAfterLayout(false))
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
  () => [props.items.length, props.resetKey],
  () => measureAfterLayout(true),
)
</script>

<style scoped>
.virtual-tenant-list {
  width: 100%;
  overscroll-behavior: contain;
}
.virtual-tenant-row {
  will-change: transform;
}
</style>
