<template>
  <a-alert v-if="error" type="warning" show-icon :message="error" style="margin-bottom: 8px" />
  <a-table v-if="!isMobile" :data-source="rows" size="small" :pagination="{ pageSize: 20 }" class="audit-log-table"
    :row-key="(r: any) => (r.eventTime + '|' + r.eventId + '|' + (r.actorName || '') + '|' + (r.clientIp || ''))"
    :scroll="{ x: 1250 }">
    <a-table-column data-index="eventTime" key="eventTime" :width="168">
      <template #title><span class="audit-th">时间</span></template>
      <template #default="{ text }">
        <span style="font-size: 12px">{{ formatTime(text) }}</span>
      </template>
    </a-table-column>
    <a-table-column data-index="eventId" key="eventId" :width="112">
      <template #title><span class="audit-th">事件</span></template>
      <template #default="{ text }">
        <a-tag :color="eventColor(text)" style="font-size: 11px; margin: 0">{{ eventLabel(text) }}</a-tag>
      </template>
    </a-table-column>
    <a-table-column data-index="actorName" key="actorName" :width="196" :ellipsis="{ showTitle: false }">
      <template #title><span class="audit-th">用户</span></template>
      <template #default="{ record }">
        <a-tooltip :title="String(record.actorDisplayName || record.actorName || '').trim() || undefined">
          <span style="font-size: 12px">{{ record.actorDisplayName || record.actorName || '—' }}</span>
        </a-tooltip>
      </template>
    </a-table-column>
    <a-table-column data-index="clientIp" key="clientIp" :width="138">
      <template #title><span class="audit-th">IP</span></template>
      <template #default="{ text }">
        <span style="font-size: 12px">{{ text || '—' }}</span>
      </template>
    </a-table-column>
    <a-table-column data-index="ssoIdentityProvider" key="ssoIdentityProvider" :width="148" :ellipsis="{ showTitle: false }">
      <template #title><span class="audit-th">身份提供者</span></template>
      <template #default="{ text }">
        <a-tooltip :title="text || undefined">
          <span style="font-size: 12px">{{ text || '—' }}</span>
        </a-tooltip>
      </template>
    </a-table-column>
    <a-table-column data-index="ssoProtectedResource" key="ssoProtectedResource" :width="216" :ellipsis="{ showTitle: false }">
      <template #title><span class="audit-th">应用/资源</span></template>
      <template #default="{ text }">
        <a-tooltip :title="text || undefined">
          <span style="font-size: 12px">{{ text || '—' }}</span>
        </a-tooltip>
      </template>
    </a-table-column>
    <a-table-column data-index="message" key="message" :width="264" :ellipsis="{ showTitle: false }">
      <template #title><span class="audit-th">备注</span></template>
      <template #default="{ text }">
        <a-tooltip :title="text || undefined"><span style="font-size: 12px">{{ text || '—' }}</span></a-tooltip>
      </template>
    </a-table-column>
  </a-table>
  <div v-else>
    <a-empty v-if="!rows?.length" description="无日志" />
    <div v-for="(log, li) in rows" :key="li" class="mobile-card">
      <div class="mobile-card-header">
        <span style="font-size: 12px">{{ formatTime(log.eventTime) }}</span>
        <a-tag :color="eventColor(log.eventId)" style="margin:0; font-size: 11px">{{ eventLabel(log.eventId) }}</a-tag>
      </div>
      <div class="mobile-card-body">
        <div class="mobile-card-row"><span class="label">用户</span><span class="value">{{ log.actorDisplayName || log.actorName || '—' }}</span></div>
        <div class="mobile-card-row"><span class="label">IP</span><span class="value">{{ log.clientIp || '—' }}</span></div>
        <div v-if="log.ssoIdentityProvider" class="mobile-card-row"><span class="label">提供者</span><span class="value">{{ log.ssoIdentityProvider }}</span></div>
        <div v-if="log.ssoProtectedResource" class="mobile-card-row"><span class="label">资源</span><span class="value">{{ log.ssoProtectedResource }}</span></div>
        <div v-if="log.message" class="mobile-card-row"><span class="label">备注</span><span class="value">{{ log.message }}</span></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  rows: any[]
  error?: string
  isMobile?: boolean
}>()

function formatTime(t?: string) {
  if (!t) return '—'
  return t.replace('T', ' ').substring(0, 19)
}

function eventLabel(id?: string) {
  if (!id) return '—'
  const map: Record<string, string> = {
    'sso.session.create.success': '登录成功',
    'sso.session.create.failure': '登录失败',
    'sso.authentication.failure': '认证失败',
    'sso.session.delete.success': '登出/会话结束',
    'admin.authentication.success': '管理员登录成功',
    'admin.authentication.failure': '管理员登录失败',
    'sso.app.access.success': '应用访问成功',
    'sso.app.access.failure': '应用访问失败',
    'sso.auth.factor.initiated': 'MFA 因素发起',
  }
  if (map[id]) return map[id]
  if (id.endsWith('.success')) return id.replace(/\.success$/, '') + ' 成功'
  if (id.endsWith('.failure')) return id.replace(/\.failure$/, '') + ' 失败'
  return id
}

function eventColor(id?: string) {
  if (!id) return 'default'
  if (id.endsWith('.success')) return 'green'
  if (id.endsWith('.failure')) return 'red'
  return 'default'
}
</script>

<style scoped>
/* 横向滚动表格：表头一字排开，避免相邻列标题被挤压重叠 */
.audit-log-table :deep(.ant-table-thead > tr > th) {
  white-space: nowrap;
}
.audit-th {
  white-space: nowrap;
  font-weight: 600;
}
.audit-log-table :deep(.ant-table-cell) {
  overflow: hidden;
}
</style>
