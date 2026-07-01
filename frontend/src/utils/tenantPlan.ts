export function normalizeTenantPlanType(plan: unknown): string {
  if (plan == null) return ''
  return String(plan).trim().toUpperCase().replace(/[\s-]+/g, '_')
}

export function isPaygPlan(plan: unknown): boolean {
  return normalizeTenantPlanType(plan) === 'PAYG'
}

export function isFreeTierPlan(plan: unknown): boolean {
  const normalized = normalizeTenantPlanType(plan).replace(/_/g, '')
  return normalized === 'FREE' || normalized === 'FREETIER'
}

export function formatTenantPlanType(plan: unknown): string {
  const normalized = normalizeTenantPlanType(plan)
  if (!normalized) return ''
  if (isFreeTierPlan(normalized)) return normalized === 'FREE' ? 'FREE' : 'FREE_TIER'
  if (isPaygPlan(normalized)) return 'PAYG'
  return normalized
}

export function formatTenantPlanLabel(plan: unknown): string {
  const normalized = normalizeTenantPlanType(plan)
  if (!normalized) return '—'
  if (isFreeTierPlan(normalized)) return '免费套餐 (Free Tier)'
  if (isPaygPlan(normalized)) return '按量付费 (PAYG)'
  return normalized
}

export function tenantPlanTagColor(plan: unknown): string {
  if (!normalizeTenantPlanType(plan)) return 'default'
  return isFreeTierPlan(plan) ? 'default' : 'green'
}
