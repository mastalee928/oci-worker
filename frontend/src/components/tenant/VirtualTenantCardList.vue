<template>
  <div ref="parentRef" class="virtual-tenant-list" :style="{ height: height + 'px', overflow: 'auto' }">
    <div :style="{ height: totalSize + 'px', width: '100%', position: 'relative' }">
      <div
        v-for="vRow in virtualRows"
        :key="vRow.key"
        :style="{
          position: 'absolute',
          top: 0,
          left: 0,
          width: '100%',
          transform: `translateY(${vRow.start}px)`,
        }"
      >
        <slot name="item" :item="vRow.item" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useVirtualizer } from '@tanstack/vue-virtual'

const props = withDefaults(
  defineProps<{
    items: unknown[]
    estimateSize?: number
    height?: number
    itemKey?: (item: unknown, index: number) => string
  }>(),
  {
    estimateSize: 168,
    height: 480,
    itemKey: (item: unknown, index: number) =>
      String((item as { id?: string })?.id ?? index),
  },
)

const parentRef = ref<HTMLElement | null>(null)

const virtualizer = useVirtualizer(
  computed(() => ({
    count: props.items.length,
    getScrollElement: () => parentRef.value,
    estimateSize: () => props.estimateSize,
    overscan: 4,
  })),
)

const totalSize = computed(() => virtualizer.value.getTotalSize())

const virtualRows = computed(() =>
  virtualizer.value.getVirtualItems().map((vi) => ({
    key: props.itemKey(props.items[vi.index], vi.index),
    start: vi.start,
    item: props.items[vi.index],
  })),
)

watch(
  () => props.items.length,
  () => {
    virtualizer.value.measure()
  },
)
</script>

<style scoped>
.virtual-tenant-list {
  width: 100%;
}
.virtual-tenant-list :deep(.tenant-card),
.virtual-tenant-list :deep(.mobile-card) {
  margin-bottom: 12px;
}
</style>
