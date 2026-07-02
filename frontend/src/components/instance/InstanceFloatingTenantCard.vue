<template>
  <div
    v-if="visible"
    class="tenant-floating-card"
    :class="{
      'tenant-floating-card-rolling': card.phase === 'rolling',
      'tenant-floating-card-docked': card.phase === 'docked',
    }"
    :style="cardStyle"
  >
    <div class="tenant-floating-flight">
      <div class="tenant-floating-roll">
        <div class="tenant-floating-card-face">
          <div class="tc-header">
            <i class="ri-cloud-line tc-icon"></i>
            <div class="tc-info">
              <div class="tc-name">{{ card.username }}</div>
              <div class="tc-region">{{ card.region }}</div>
            </div>
          </div>
          <div class="tc-tags">
            <a-tag v-if="card.planType" :color="tenantPlanTagColor(card.planType)" :style="tenantPlanTagStyle(card.planType)" size="small">{{ formatTenantPlanType(card.planType) }}</a-tag>
            <a-tag v-if="card.tenantName" size="small" color="blue">{{ card.tenantName }}</a-tag>
          </div>
          <div class="tc-actions">
            <a-button
              v-for="item in actionItems"
              :key="item.key"
              block
              :type="floatingTenantButtonType(item.key)"
              class="tenant-floating-action"
              :class="{ 'tenant-floating-action-active': item.key === workspaceKind }"
              :disabled="card.phase === 'rolling'"
              @click.stop="$emit('action-click', item.key)"
            >
              <i :class="item.icon"></i>
              <span>{{ item.label }}</span>
            </a-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'InstanceFloatingTenantCard' })

type FloatingTenantCardPhase = 'idle' | 'rolling' | 'docked'
type TenantWorkspaceKind = 'instance' | 'vcn' | 'storage'
type FloatingTenantActionKey = TenantWorkspaceKind | 'quick'

interface FloatingTenantCardState {
  phase: FloatingTenantCardPhase
  username: string
  tenantName: string
  region: string
  planType: string
}

defineProps<{
  visible: boolean
  card: FloatingTenantCardState
  cardStyle: Record<string, string>
  workspaceKind: TenantWorkspaceKind | null
  actionItems: { key: FloatingTenantActionKey; label: string; icon: string }[]
  tenantPlanTagStyle: (plan: unknown) => Record<string, string> | undefined
  tenantPlanTagColor: (plan: unknown) => string
  formatTenantPlanType: (plan: unknown) => string
  floatingTenantButtonType: (action: FloatingTenantActionKey) => string
}>()

defineEmits<{
  (e: 'action-click', action: FloatingTenantActionKey): void
}>()
</script>

<style scoped>
.tenant-floating-card {
  position: fixed;
  z-index: var(--oci-z-tenant-floating-card);
  perspective: 1200px;
  perspective-origin: center center;
  transform-style: preserve-3d;
  contain: layout style;
  overflow: visible;
  pointer-events: auto;
  transform: translateZ(0);
}
.tenant-floating-card-rolling {
  z-index: var(--oci-z-tenant-floating-card-rolling);
  pointer-events: none;
  will-change: transform;
  animation: tenantFloatingFlight var(--tenant-float-duration) cubic-bezier(0.18, 0.82, 0.22, 1) forwards;
}
.tenant-floating-card-docked {
  animation: tenantFloatingSettle 160ms ease-out both;
}
.tenant-floating-flight,
.tenant-floating-roll {
  height: 100%;
  overflow: visible;
  transform-style: preserve-3d;
}
.tenant-floating-card-rolling .tenant-floating-flight {
  will-change: transform;
  animation: tenantFloatingLift var(--tenant-float-duration) cubic-bezier(0.18, 0.82, 0.22, 1) forwards;
}
.tenant-floating-roll {
  position: relative;
  will-change: transform;
}
.tenant-floating-card-rolling .tenant-floating-roll {
  animation: tenantFloatingRoll var(--tenant-float-duration) linear forwards;
}
.tenant-floating-card-face {
  position: relative;
  height: 100%;
  padding: 18px;
  border: 1px solid var(--tenant-floating-card-border);
  border-radius: 16px;
  background: var(--bg-card);
  background: var(--tenant-floating-card-bg);
  box-shadow: var(--tenant-floating-card-shadow);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  transform: translateZ(1px);
  backface-visibility: hidden;
  -webkit-backface-visibility: hidden;
  will-change: transform;
}
.tenant-floating-card-face::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(90deg, var(--primary), #8b5cf6);
}
.tenant-floating-roll::after {
  content: '';
  position: absolute;
  inset: 0;
  border: 1px solid var(--tenant-floating-card-border);
  border-radius: 16px;
  background:
    radial-gradient(circle at 22% 18%, rgba(129, 140, 248, 0.18), transparent 30%),
    var(--tenant-floating-card-bg);
  box-shadow: var(--tenant-floating-card-shadow);
  transform: rotateY(180deg) translateZ(0.5px);
  backface-visibility: hidden;
  -webkit-backface-visibility: hidden;
}
.tenant-floating-card-docked .tenant-floating-roll::after {
  display: none;
}
.tenant-floating-card-face :deep(.tenant-floating-action) {
  height: 34px;
  border-radius: 8px;
  font-weight: 600;
}
.tenant-floating-card-face :deep(.tenant-floating-action i) {
  margin-right: 6px;
  font-size: 15px;
}
.tenant-floating-card-face :deep(.tenant-floating-action.ant-btn-primary) {
  box-shadow: 0 8px 18px rgba(79, 70, 229, 0.26);
}
@keyframes tenantFloatingFlight {
  from { transform: translate3d(0, 0, 0); }
  to { transform: translate3d(var(--tenant-float-dx), var(--tenant-float-dy), 0); }
}
@keyframes tenantFloatingLift {
  0% { transform: translate3d(0, 0, 0) scale(1); }
  18% { transform: translate3d(0, -18px, 0) scale(1.035); }
  72% { transform: translate3d(0, -7px, 0) scale(1.012); }
  100% { transform: translate3d(0, 0, 0) scale(1); }
}
@keyframes tenantFloatingRoll {
  from { transform: rotate3d(0.08, 1, 0.02, 0deg); }
  to { transform: rotate3d(0.08, 1, 0.02, 360deg); }
}
@keyframes tenantFloatingSettle {
  from {
    transform: translateY(-3px) scale(0.992);
  }
  to {
    transform: translateY(0) scale(1);
  }
}
@media (prefers-reduced-motion: reduce) {
  .tenant-floating-card-rolling,
  .tenant-floating-card-rolling .tenant-floating-flight,
  .tenant-floating-card-rolling .tenant-floating-roll,
  .tenant-floating-card-docked {
    animation-duration: 1ms !important;
  }
}
.tc-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.tc-icon {
  font-size: 28px;
  color: var(--primary);
  flex-shrink: 0;
}
.tc-info { min-width: 0; flex: 1; }
.tc-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-main);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tc-region {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 2px;
}
.tc-tags {
  display: flex;
  gap: 6px;
  margin-bottom: 14px;
  flex-wrap: wrap;
  min-height: 22px;
}
.tc-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: auto;
}

@media (max-width: 768px) {
  .tc-icon { font-size: 22px; }
  .tc-name { font-size: 13px; }
}
</style>
