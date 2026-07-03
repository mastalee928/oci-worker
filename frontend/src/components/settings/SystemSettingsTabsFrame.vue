<template>
  <div class="settings-tabs-frame settings-page-tabs">
    <nav class="settings-primary-tabs" aria-label="系统设置一级分类">
      <button
        v-for="tab in primaryTabs"
        :key="tab.key"
        type="button"
        class="settings-primary-tab"
        :class="{ active: activePrimary === tab.key }"
        @click="emit('selectPrimary', tab.key)"
      >
        <span>{{ tab.label }}</span>
        <span class="settings-primary-count">{{ tab.count }}</span>
      </button>
    </nav>

    <div v-if="!secondaryLocked" class="settings-secondary-wrap">
      <nav class="settings-secondary-tabs" aria-label="系统设置二级分类">
        <button
          v-for="tab in secondaryTabs"
          :key="tab.key"
          type="button"
          class="settings-secondary-tab"
          :class="{ active: isSecondaryActive(tab.key) }"
          @click="emit('selectSecondary', tab.key)"
        >
          {{ tab.label }}
        </button>
      </nav>
    </div>

    <div class="settings-content">
      <div class="settings-content-head">
        <div class="settings-content-title">
          <h2>{{ activeTitle }}</h2>
          <p v-if="activeDesc">{{ activeDesc }}</p>
        </div>
        <span v-if="activePath" class="settings-path-tag">{{ activePath }}</span>
      </div>

      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'SystemSettingsTabsFrame' })

type PrimaryTab = {
  key: string
  label: string
  count: number
}

type SecondaryTab = {
  key: string
  label: string
}

defineProps<{
  primaryTabs: readonly PrimaryTab[]
  secondaryTabs: readonly SecondaryTab[]
  activePrimary: string
  activeTitle: string
  activeDesc: string
  activePath: string
  secondaryLocked: boolean
  isSecondaryActive: (key: string) => boolean
}>()

const emit = defineEmits<{
  selectPrimary: [key: string]
  selectSecondary: [key: string]
}>()
</script>

<style scoped>
.settings-tabs-frame {
  --settings-secondary-bg: rgba(15, 23, 42, 0.16);

  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--bg-card);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}

:global(html[data-theme="light"] .settings-tabs-frame) {
  --settings-secondary-bg: rgba(248, 250, 252, 0.52);
}

.settings-primary-tabs {
  display: flex;
  gap: 6px;
  padding: 10px 12px 0;
  overflow-x: auto;
  scrollbar-width: thin;
}

.settings-primary-tab {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-width: 96px;
  height: 42px;
  padding: 0 14px;
  border: 1px solid transparent;
  border-radius: var(--radius-md) var(--radius-md) 0 0;
  background: transparent;
  color: var(--text-sub);
  white-space: nowrap;
  cursor: pointer;
  transition: var(--trans);
}

.settings-primary-tab:hover {
  color: var(--text-main);
  background: rgba(129, 140, 248, 0.06);
}

.settings-primary-tab.active {
  color: var(--primary);
  background: var(--primary-light);
  border-color: var(--border);
  border-bottom-color: transparent;
  font-weight: 650;
}

.settings-primary-tab.active::after {
  content: "";
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: -1px;
  height: 2px;
  border-radius: 2px 2px 0 0;
  background: var(--primary);
}

.settings-primary-count {
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.14);
  color: inherit;
  font-size: 12px;
  line-height: 20px;
  text-align: center;
}

.settings-secondary-wrap {
  border-top: 1px solid var(--border);
  padding: 0 18px;
  background: var(--settings-secondary-bg);
}

:global(html[data-theme="light"] .settings-secondary-wrap) {
  background: rgba(248, 250, 252, 0.52);
}

.settings-secondary-tabs {
  display: flex;
  align-items: center;
  gap: 22px;
  min-height: 46px;
  overflow-x: auto;
  scrollbar-width: thin;
}

.settings-secondary-tab {
  position: relative;
  display: inline-flex;
  align-items: center;
  height: 46px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text-sub);
  white-space: nowrap;
  cursor: pointer;
  transition: var(--trans);
}

.settings-secondary-tab:hover {
  color: var(--text-main);
}

.settings-secondary-tab.active {
  color: var(--primary);
  font-weight: 600;
}

.settings-secondary-tab.active::after {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  border-radius: 2px 2px 0 0;
  background: var(--primary);
}

.settings-content {
  padding: 20px;
}

.settings-content-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.settings-content-title {
  min-width: 0;
}

.settings-content-title h2 {
  margin: 0;
  color: var(--text-main);
  font-size: 18px;
  line-height: 1.3;
  font-weight: 700;
}

.settings-content-title p {
  margin: 6px 0 0;
  color: var(--text-sub);
  font-size: 13px;
}

.settings-path-tag {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  min-height: 28px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--input-bg);
  color: var(--text-sub);
  font-size: 12px;
  white-space: nowrap;
}

@media (max-width: 768px) {
  .settings-primary-tabs {
    padding: 8px 10px 0;
  }

  .settings-primary-tab {
    min-width: auto;
    height: 38px;
    padding: 0 12px;
  }

  .settings-secondary-wrap {
    padding: 0 12px;
  }

  .settings-secondary-tabs {
    gap: 18px;
  }

  .settings-content {
    padding: 14px;
  }

  .settings-content-head {
    align-items: stretch;
    flex-direction: column;
  }

  .settings-path-tag {
    width: fit-content;
  }
}
</style>
