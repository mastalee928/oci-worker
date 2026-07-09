<template>
  <div
    class="app-panel-skeleton"
    :class="`app-panel-skeleton--${normalizedVariant}`"
    aria-live="polite"
  >
    <template v-if="normalizedVariant === 'cards'">
      <div class="app-skeleton-card-grid">
        <article v-for="item in cardItems" :key="item" class="app-skeleton-tenant-card">
          <div class="app-skeleton-card-head">
            <span class="app-skeleton-shape app-skeleton-avatar"></span>
            <div class="app-skeleton-card-title">
              <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--md"></span>
              <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--xs"></span>
            </div>
          </div>
          <div class="app-skeleton-pill-row">
            <span class="app-skeleton-shape app-skeleton-pill app-skeleton-pill--sm"></span>
            <span class="app-skeleton-shape app-skeleton-pill app-skeleton-pill--md"></span>
          </div>
          <div class="app-skeleton-action-stack">
            <span class="app-skeleton-shape app-skeleton-button app-skeleton-button--primary"></span>
            <span class="app-skeleton-shape app-skeleton-button"></span>
            <span class="app-skeleton-shape app-skeleton-button"></span>
            <span class="app-skeleton-shape app-skeleton-button app-skeleton-button--short"></span>
          </div>
        </article>
      </div>
    </template>

    <template v-else-if="normalizedVariant === 'table'">
      <section class="app-skeleton-panel">
        <div class="app-skeleton-toolbar">
          <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--label"></span>
          <span class="app-skeleton-shape app-skeleton-control"></span>
          <span class="app-skeleton-shape app-skeleton-button app-skeleton-button--tiny"></span>
        </div>
        <div class="app-skeleton-table">
          <div class="app-skeleton-table-head">
            <span v-for="item in tableColumns" :key="item" class="app-skeleton-shape"></span>
          </div>
          <div v-for="item in tableRows" :key="item" class="app-skeleton-table-row">
            <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--md"></span>
            <span class="app-skeleton-shape app-skeleton-pill app-skeleton-pill--sm"></span>
            <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--sm"></span>
            <span class="app-skeleton-shape app-skeleton-button app-skeleton-button--tiny"></span>
          </div>
        </div>
      </section>
    </template>

    <template v-else-if="normalizedVariant === 'detail'">
      <section class="app-skeleton-panel">
        <div class="app-skeleton-toolbar app-skeleton-toolbar--detail">
          <span class="app-skeleton-shape app-skeleton-button app-skeleton-button--tiny"></span>
          <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--xs"></span>
        </div>
        <div class="app-skeleton-description">
          <div v-for="item in detailRows" :key="item" class="app-skeleton-description-row">
            <span class="app-skeleton-shape app-skeleton-label-cell"></span>
            <span class="app-skeleton-shape app-skeleton-value-cell"></span>
          </div>
        </div>
        <div class="app-skeleton-section-mark">
          <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--sm"></span>
        </div>
        <div class="app-skeleton-detail-grid">
          <section v-for="item in detailCards" :key="item" class="app-skeleton-detail-card">
            <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--sm"></span>
            <span class="app-skeleton-shape app-skeleton-line"></span>
            <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--md"></span>
          </section>
        </div>
      </section>
    </template>

    <template v-else-if="normalizedVariant === 'list'">
      <section class="app-skeleton-panel">
        <div class="app-skeleton-toolbar">
          <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--label"></span>
          <span class="app-skeleton-shape app-skeleton-control"></span>
          <span class="app-skeleton-shape app-skeleton-button app-skeleton-button--tiny"></span>
        </div>
        <div class="app-skeleton-list">
          <article v-for="item in listRows" :key="item" class="app-skeleton-list-row">
            <div>
              <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--md"></span>
              <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--sm"></span>
            </div>
            <span class="app-skeleton-shape app-skeleton-pill app-skeleton-pill--sm"></span>
            <span class="app-skeleton-shape app-skeleton-button app-skeleton-button--tiny"></span>
          </article>
        </div>
      </section>
    </template>

    <template v-else-if="normalizedVariant === 'compact'">
      <div class="app-skeleton-compact">
        <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--md"></span>
        <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--sm"></span>
      </div>
    </template>

    <template v-else>
      <section class="app-skeleton-panel">
        <div class="app-skeleton-panel-head">
          <div>
            <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--md"></span>
            <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--xs"></span>
          </div>
          <span class="app-skeleton-shape app-skeleton-button app-skeleton-button--tiny"></span>
        </div>
        <div class="app-skeleton-metric-row">
          <span v-for="item in metricItems" :key="item" class="app-skeleton-shape app-skeleton-metric"></span>
        </div>
        <div class="app-skeleton-list">
          <article v-for="item in panelRows" :key="item" class="app-skeleton-list-row">
            <div>
              <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--md"></span>
              <span class="app-skeleton-shape app-skeleton-line app-skeleton-line--xs"></span>
            </div>
            <span class="app-skeleton-shape app-skeleton-pill app-skeleton-pill--sm"></span>
          </article>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

type AppPanelSkeletonVariant = 'panel' | 'cards' | 'table' | 'detail' | 'list' | 'compact'

const props = withDefaults(defineProps<{
  variant?: AppPanelSkeletonVariant
  rows?: number
}>(), {
  variant: 'panel',
  rows: 3,
})

const variants = new Set<AppPanelSkeletonVariant>(['panel', 'cards', 'table', 'detail', 'list', 'compact'])

const normalizedVariant = computed<AppPanelSkeletonVariant>(() => (
  variants.has(props.variant) ? props.variant : 'panel'
))

function makeItems(count: number, fallback: number, max: number) {
  const safeCount = Number.isFinite(count) ? Math.trunc(count) : fallback
  return Array.from({ length: Math.max(1, Math.min(max, safeCount || fallback)) }, (_, index) => index)
}

const cardItems = computed(() => makeItems(props.rows, 3, 6))
const tableRows = computed(() => makeItems(props.rows + 1, 4, 8))
const detailRows = computed(() => makeItems(props.rows + 3, 6, 9))
const listRows = computed(() => makeItems(props.rows + 1, 4, 8))
const panelRows = computed(() => makeItems(props.rows, 3, 6))
const tableColumns = [0, 1, 2, 3]
const detailCards = [0, 1]
const metricItems = [0, 1, 2]
</script>

<style scoped>
.app-panel-skeleton {
  width: 100%;
  pointer-events: none;
  --app-skeleton-surface: rgba(30, 41, 59, 0.34);
  --app-skeleton-detail-surface: rgba(15, 23, 42, 0.2);
  --app-skeleton-block: rgba(148, 163, 184, 0.13);
  --app-skeleton-block-strong: rgba(129, 140, 248, 0.16);
  --app-skeleton-shine: rgba(255, 255, 255, 0.12);
}

:global([data-theme="light"] .app-panel-skeleton) {
  --app-skeleton-surface: rgba(255, 255, 255, 0.82);
  --app-skeleton-detail-surface: rgba(248, 250, 252, 0.72);
  --app-skeleton-block: rgba(15, 23, 42, 0.075);
  --app-skeleton-block-strong: rgba(99, 102, 241, 0.12);
  --app-skeleton-shine: rgba(255, 255, 255, 0.78);
}

.app-skeleton-panel,
.app-skeleton-compact,
.app-skeleton-tenant-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--app-skeleton-surface);
  box-shadow: var(--shadow-card);
}

.app-skeleton-panel {
  display: grid;
  gap: 14px;
  padding: 14px;
}

.app-panel-skeleton--detail .app-skeleton-panel {
  box-shadow: none;
}

.app-skeleton-shape {
  position: relative;
  display: block;
  overflow: hidden;
  border-radius: var(--radius-sm);
  background:
    linear-gradient(90deg, transparent, var(--app-skeleton-shine), transparent),
    var(--app-skeleton-block);
  background-size: 240% 100%, 100% 100%;
  animation: app-panel-skeleton-shimmer 1.35s ease-in-out infinite;
}

.app-skeleton-line {
  width: 100%;
  height: 10px;
}

.app-skeleton-line--xs {
  width: 38%;
}

.app-skeleton-line--sm {
  width: 52%;
}

.app-skeleton-line--md {
  width: 72%;
}

.app-skeleton-line--label {
  width: 54px;
}

.app-skeleton-pill {
  height: 22px;
  border-radius: var(--radius-pill);
}

.app-skeleton-pill--sm {
  width: 62px;
}

.app-skeleton-pill--md {
  width: 88px;
}

.app-skeleton-button,
.app-skeleton-control {
  height: 32px;
  border-radius: var(--radius-sm);
}

.app-skeleton-button {
  width: 100%;
}

.app-skeleton-button--primary {
  background:
    linear-gradient(90deg, transparent, var(--app-skeleton-shine), transparent),
    var(--app-skeleton-block-strong);
  background-size: 240% 100%, 100% 100%;
}

.app-skeleton-button--short {
  width: 78%;
}

.app-skeleton-button--tiny {
  width: 74px;
}

.app-skeleton-control {
  width: min(240px, 48vw);
}

.app-skeleton-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
  width: 100%;
}

.app-skeleton-tenant-card {
  display: grid;
  gap: 14px;
  min-height: 228px;
  padding: 18px;
}

.app-skeleton-card-head {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
}

.app-skeleton-avatar {
  width: 44px;
  height: 44px;
  border-radius: 14px;
}

.app-skeleton-card-title,
.app-skeleton-action-stack,
.app-skeleton-list-row > div {
  display: grid;
  gap: 8px;
}

.app-skeleton-pill-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.app-skeleton-toolbar,
.app-skeleton-panel-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.app-skeleton-panel-head {
  justify-content: space-between;
}

.app-skeleton-panel-head > div {
  display: grid;
  flex: 1;
  gap: 8px;
}

.app-skeleton-toolbar--detail {
  justify-content: space-between;
}

.app-skeleton-table,
.app-skeleton-list,
.app-skeleton-description {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.app-skeleton-table-head,
.app-skeleton-table-row,
.app-skeleton-list-row,
.app-skeleton-description-row {
  display: grid;
  align-items: center;
  gap: 12px;
  min-height: 42px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
}

.app-skeleton-table-head {
  grid-template-columns: 1.4fr 0.7fr 1fr 0.6fr;
  min-height: 38px;
  background: var(--primary-light);
}

.app-skeleton-table-head .app-skeleton-shape {
  height: 9px;
}

.app-skeleton-table-row {
  grid-template-columns: 1.4fr 0.7fr 1fr 0.6fr;
}

.app-skeleton-list-row {
  grid-template-columns: minmax(0, 1fr) auto auto;
}

.app-skeleton-description-row {
  grid-template-columns: minmax(110px, 0.34fr) minmax(0, 1fr);
}

.app-skeleton-table-row:last-child,
.app-skeleton-list-row:last-child,
.app-skeleton-description-row:last-child {
  border-bottom: none;
}

.app-skeleton-label-cell,
.app-skeleton-value-cell {
  height: 18px;
}

.app-skeleton-label-cell {
  width: 74%;
}

.app-skeleton-value-cell {
  width: 88%;
}

.app-skeleton-section-mark {
  display: flex;
  align-items: center;
  min-height: 24px;
}

.app-skeleton-detail-grid,
.app-skeleton-metric-row {
  display: grid;
  gap: 12px;
}

.app-skeleton-detail-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.app-skeleton-detail-card {
  display: grid;
  gap: 10px;
  min-height: 104px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--app-skeleton-detail-surface);
}

.app-skeleton-metric-row {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.app-skeleton-metric {
  height: 64px;
}

.app-skeleton-compact {
  display: grid;
  gap: 8px;
  max-width: 520px;
  padding: 12px;
  box-shadow: none;
}

@keyframes app-panel-skeleton-shimmer {
  0% {
    background-position: 240% 0, 0 0;
  }
  100% {
    background-position: -240% 0, 0 0;
  }
}

@media (max-width: 640px) {
  .app-skeleton-card-grid,
  .app-skeleton-detail-grid,
  .app-skeleton-metric-row {
    grid-template-columns: 1fr;
  }

  .app-skeleton-toolbar {
    align-items: stretch;
    flex-wrap: wrap;
  }

  .app-skeleton-control {
    width: 100%;
  }

  .app-skeleton-table-head,
  .app-skeleton-table-row {
    grid-template-columns: 1fr 68px;
  }

  .app-skeleton-table-head .app-skeleton-shape:nth-child(n + 3),
  .app-skeleton-table-row .app-skeleton-shape:nth-child(n + 3) {
    display: none;
  }

  .app-skeleton-list-row {
    grid-template-columns: 1fr;
  }

  .app-skeleton-description-row {
    grid-template-columns: 92px minmax(0, 1fr);
  }
}

@media (prefers-reduced-motion: reduce) {
  .app-skeleton-shape {
    animation: none;
    background-position: 0 0;
  }
}
</style>
