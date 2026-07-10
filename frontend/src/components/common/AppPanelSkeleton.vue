<template>
  <div
    class="app-panel-loader"
    :class="`app-panel-loader--${normalizedVariant}`"
    aria-live="polite"
  >
    <div class="app-panel-loader-core">
      <div class="app-panel-loader-orbit" aria-hidden="true">
        <span class="app-panel-loader-pulse"></span>
        <span class="app-panel-loader-satellite"></span>
      </div>
      <div class="app-panel-loader-copy">
        <strong>{{ title }}</strong>
        <span>{{ description }}</span>
      </div>
      <div class="app-panel-loader-signal" aria-hidden="true">
        <i></i><i></i><i></i>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

type AppPanelLoaderVariant = 'panel' | 'cards' | 'table' | 'detail' | 'list' | 'compact'

const props = withDefaults(defineProps<{
  variant?: AppPanelLoaderVariant
  title?: string
  description?: string
}>(), {
  variant: 'panel',
  title: '正在载入工作区',
  description: '连接本地数据并准备操作面板',
})

const variants = new Set<AppPanelLoaderVariant>(['panel', 'cards', 'table', 'detail', 'list', 'compact'])
const normalizedVariant = computed<AppPanelLoaderVariant>(() => (
  variants.has(props.variant) ? props.variant : 'panel'
))
</script>

<style scoped>
.app-panel-loader {
  --app-loader-accent: var(--primary, #7c83ff);
  --app-loader-accent-rgb: 124, 131, 255;
  display: grid;
  place-items: center;
  box-sizing: border-box;
  width: 100%;
  min-height: 360px;
  color: var(--text-main, var(--text, #eef2ff));
  pointer-events: none;
  animation: app-panel-loader-enter 0.2s ease-out both;
}

:global([data-theme="light"] .app-panel-loader) {
  --app-loader-accent: var(--primary, #5865e8);
  --app-loader-accent-rgb: 88, 101, 232;
}

.app-panel-loader--cards { min-height: 390px; }
.app-panel-loader--table,
.app-panel-loader--list { min-height: 320px; }
.app-panel-loader--detail { min-height: min(520px, calc(100vh - 190px)); }
.app-panel-loader--compact { min-height: 132px; }

.app-panel-loader-core {
  display: grid;
  justify-items: center;
  gap: 16px;
  transform: translateY(-8px);
}

.app-panel-loader--detail .app-panel-loader-core {
  gap: 12px;
  transform: translateY(-26px);
}

.app-panel-loader-orbit {
  position: relative;
  width: 70px;
  height: 70px;
}

.app-panel-loader--detail .app-panel-loader-orbit {
  width: 54px;
  height: 54px;
}

.app-panel-loader-orbit::before,
.app-panel-loader-orbit::after {
  content: '';
  position: absolute;
  border-radius: 50%;
}

.app-panel-loader-orbit::before {
  inset: 7px;
  border: 1px solid rgba(var(--app-loader-accent-rgb), 0.22);
  box-shadow: inset 0 0 18px rgba(var(--app-loader-accent-rgb), 0.08);
}

.app-panel-loader--detail .app-panel-loader-orbit::before { inset: 6px; }

.app-panel-loader-orbit::after {
  inset: 0;
  border: 1px solid transparent;
  border-top-color: var(--app-loader-accent);
  border-right-color: rgba(var(--app-loader-accent-rgb), 0.28);
  animation: app-panel-loader-spin 1.15s cubic-bezier(0.55, 0.15, 0.45, 0.85) infinite;
}

.app-panel-loader-pulse {
  position: absolute;
  inset: 25px;
  border-radius: 50%;
  background: var(--app-loader-accent);
  box-shadow: 0 0 0 0 rgba(var(--app-loader-accent-rgb), 0.32);
  animation: app-panel-loader-pulse 1.8s ease-out infinite;
}

.app-panel-loader--detail .app-panel-loader-pulse { inset: 20px; }

.app-panel-loader-satellite {
  position: absolute;
  inset: 0;
  animation: app-panel-loader-spin 2.5s linear infinite;
}

.app-panel-loader-satellite::after {
  content: '';
  position: absolute;
  top: 1px;
  left: 33px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--text-main, var(--text, #eef2ff));
  box-shadow: 0 0 8px rgba(var(--app-loader-accent-rgb), 0.8);
}

.app-panel-loader--detail .app-panel-loader-satellite::after { left: 25px; }

.app-panel-loader-copy { text-align: center; }
.app-panel-loader-copy strong {
  display: block;
  font-size: 14px;
  font-weight: 650;
}
.app-panel-loader-copy span {
  display: block;
  margin-top: 4px;
  color: var(--text-sub, #8793aa);
  font-size: 12px;
}

.app-panel-loader-signal { display: flex; gap: 5px; }
.app-panel-loader-signal i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--app-loader-accent);
  animation: app-panel-loader-dot 1.2s ease-in-out infinite;
}
.app-panel-loader-signal i:nth-child(2) { animation-delay: 0.15s; }
.app-panel-loader-signal i:nth-child(3) { animation-delay: 0.3s; }

@keyframes app-panel-loader-enter {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes app-panel-loader-spin { to { transform: rotate(360deg); } }
@keyframes app-panel-loader-pulse {
  70%, 100% { box-shadow: 0 0 0 15px rgba(var(--app-loader-accent-rgb), 0); }
}
@keyframes app-panel-loader-dot {
  0%, 100% { opacity: 0.25; transform: translateY(0); }
  50% { opacity: 1; transform: translateY(-3px); }
}

@media (max-width: 640px) {
  .app-panel-loader { min-height: 300px; }
  .app-panel-loader--detail { min-height: min(480px, calc(100vh - 170px)); }
  .app-panel-loader--compact { min-height: 120px; }
}

@media (prefers-reduced-motion: reduce) {
  .app-panel-loader,
  .app-panel-loader-orbit::after,
  .app-panel-loader-pulse,
  .app-panel-loader-satellite,
  .app-panel-loader-signal i {
    animation: none;
  }
}
</style>
