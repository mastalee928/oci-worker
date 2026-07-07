<template>
  <a-card title="独立中转端口" :bordered="false" class="mt-card">
    <a-row class="key-toolbar" :gutter="[8, 8]" align="middle">
      <a-col>
        <a-button type="primary" @click="$emit('create')">添加端口绑定</a-button>
      </a-col>
      <a-col>
        <a-button :loading="loading" @click="$emit('refresh')">刷新</a-button>
      </a-col>
      <a-col>
        <span class="sub-muted">端口范围 30000-39999，保存后立即生效。</span>
      </a-col>
    </a-row>
    <a-alert
      class="mb-alert"
      type="info"
      show-icon
      message="保存后 OCIworker 会立即监听本机端口；如需外网访问，还需要在系统防火墙和 OCI 安全列表放行对应端口。"
    />
    <div v-if="isMobile" class="port-mobile-list">
      <a-spin v-if="loading" />
      <a-empty v-if="!items.length && !loading" description="暂无端口绑定" />
      <div v-for="record in items" :key="record.id" class="port-card-m">
        <div class="port-card-head">
          <div>
            <div class="port-card-title">{{ record.name || `port-${record.port}` }}</div>
            <code>{{ portIpBaseUrl(record.port) }}</code>
          </div>
          <a-switch
            :checked="record.enabled"
            :loading="isSwitching(record)"
            @change="(v: boolean) => $emit('toggle', record, v)"
          />
        </div>
        <div class="port-card-grid">
          <span>端口</span><b>{{ record.port }}</b>
          <span>租户</span><b>{{ record.tenantName || record.ociUserId || '-' }}</b>
          <span>区域</span><b>{{ regionDisplay(record.ociRegion) || '-' }}</b>
          <span>Tokens</span><b>{{ record.defaultMaxTokens || '全局默认' }}</b>
          <span>模型</span>
          <a-tooltip :title="modelTooltip(record.allowedModels)">
            <b class="model-summary">{{ modelSummary(record.allowedModels) }}</b>
          </a-tooltip>
          <span>状态</span><a-tag :color="portStatusColor(record)">{{ portStatusText(record) }}</a-tag>
          <span>Key备注</span><code class="key-masked">{{ record.keyName || record.keyMasked || 'sk-****' }}</code>
        </div>
        <div v-if="record.statusMessage" class="sub-muted status-message">{{ record.statusMessage }}</div>
        <a-space class="port-card-actions" wrap>
          <a-button size="small" @click="$emit('view-key', record)">查看密钥</a-button>
          <a-button size="small" @click="$emit('edit', record)">编辑</a-button>
          <a-popconfirm title="确定删除该端口绑定？" @confirm="$emit('remove', record)">
            <a-button size="small" danger>删除</a-button>
          </a-popconfirm>
        </a-space>
      </div>
    </div>
    <a-spin v-else-if="loading && !items.length" />
    <a-empty v-else-if="!items.length" description="暂无端口绑定" />
    <div v-else class="table-wrap">
      <table class="table-wide port-binding-table">
        <thead>
          <tr>
            <th>开关</th>
            <th>端口</th>
            <th>租户</th>
            <th>状态</th>
            <th>Base URL</th>
            <th>上限</th>
            <th>模型</th>
            <th>Key备注</th>
            <th>最后使用</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="record in items" :key="record.id">
            <td>
              <a-switch
                :checked="record.enabled"
                :loading="isSwitching(record)"
                @change="(v: boolean) => $emit('toggle', record, v)"
              />
            </td>
            <td><code>{{ record.port }}</code></td>
            <td>
              {{ record.tenantName || record.ociUserId || '-' }}
              <span class="muted-block">{{ record.ociRegion || '-' }}</span>
            </td>
            <td>
              <a-tag :color="portStatusColor(record)">{{ portStatusText(record) }}</a-tag>
              <span v-if="record.statusMessage" class="muted-block status-message">{{ record.statusMessage }}</span>
            </td>
            <td><code>{{ portIpBaseUrl(record.port) }}</code></td>
            <td>{{ record.defaultMaxTokens || '全局默认' }}</td>
            <td>
              <a-tooltip :title="modelTooltip(record.allowedModels)">
                <span class="model-summary">{{ modelSummary(record.allowedModels) }}</span>
              </a-tooltip>
            </td>
            <td><code class="key-masked">{{ record.keyName || record.keyMasked || 'sk-****' }}</code></td>
            <td>{{ formatKeyTime(record.lastUsed) }}</td>
            <td>
              <a-space class="actions" :size="4">
                <a-button size="small" @click="$emit('view-key', record)">查看密钥</a-button>
                <a-button size="small" @click="$emit('edit', record)">编辑</a-button>
                <a-popconfirm title="确定删除该端口绑定？" @confirm="$emit('remove', record)">
                  <a-button size="small" danger>删除</a-button>
                </a-popconfirm>
              </a-space>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </a-card>
</template>

<script setup lang="ts">
import { getOciRegionDisplayName } from '../../utils/ociRegionCatalog'

const props = defineProps<{
  items: any[]
  loading: boolean
  switchingId: string | number
  isMobile: boolean
  serverIp?: string
  openaiPath?: string
}>()

defineEmits<{
  (e: 'create'): void
  (e: 'refresh'): void
  (e: 'toggle', row: any, enabled: boolean): void
  (e: 'view-key', row: any): void
  (e: 'edit', row: any): void
  (e: 'remove', row: any): void
}>()

function formatKeyTime(iso?: string | null) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function regionDisplay(region?: string) {
  const r = String(region || '').trim()
  if (!r) return ''
  return `${getOciRegionDisplayName(r)} (${r})`
}

function portIpBaseUrl(port?: number) {
  const p = Number(port || 0)
  if (!p) return ''
  const host = props.serverIp || '<服务器IP>'
  return `http://${host}:${p}${props.openaiPath || '/v1'}`
}

function modelSummary(models?: string[]) {
  const arr = Array.isArray(models) ? models.filter(Boolean) : []
  if (!arr.length) return '不限制'
  if (arr.length <= 2) return arr.join('、')
  return `${arr[0]}、${arr[1]} +${arr.length - 2}`
}

function modelTooltip(models?: string[]) {
  const arr = Array.isArray(models) ? models.filter(Boolean) : []
  return arr.length ? arr.join('\n') : '不限制模型'
}

function portStatusText(row: any) {
  if (!row?.enabled) return '已停用'
  if (row?.status === 'listening') return '监听中'
  if (row?.status === 'failed') return '启动失败'
  if (row?.running === true) return '监听中'
  if (row?.running === false) return '未监听'
  return row?.status || '未监听'
}

function portStatusColor(row: any) {
  if (!row?.enabled) return 'default'
  if (row?.status === 'listening') return 'green'
  if (row?.status === 'failed') return 'red'
  if (row?.running === true) return 'green'
  if (row?.running === false) return 'orange'
  return 'orange'
}

function isSwitching(row: any) {
  const current = String(props.switchingId ?? '')
  const id = String(row?.id ?? '')
  return !!current && !!id && current === id
}
</script>

<style scoped>
.key-toolbar {
  margin-bottom: 14px;
}

.mb-alert {
  margin: 0 0 8px;
}

.sub-muted {
  color: var(--text-sub, #666);
  font-size: 13px;
  line-height: 1.5;
  opacity: 0.9;
}

.table-wrap {
  width: 100%;
  overflow-x: auto;
  overflow-y: hidden;
}

.table-wide {
  width: 100%;
  min-width: 1580px;
  border-collapse: collapse;
  color: var(--text-main);
  font-size: 14px;
}

.table-wide th,
.table-wide td {
  padding: 13px 16px;
  border-bottom: 1px solid var(--border);
  text-align: left;
  vertical-align: middle;
  background: transparent;
  white-space: nowrap;
}

.table-wide th {
  color: var(--text-sub);
  font-size: 13px;
  font-weight: 600;
}

.table-wide tbody tr:last-child td {
  border-bottom: 0;
}

.table-wide tbody tr:hover {
  background: rgba(129, 140, 248, 0.04);
}

.muted-block {
  display: block;
  margin-top: 3px;
  color: var(--text-sub);
  font-size: 12px;
}

.status-message {
  max-width: 330px;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.model-summary {
  display: inline-block;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: bottom;
  white-space: nowrap;
}

.key-masked {
  color: #c4b5fd;
  font-size: 12px;
  user-select: none;
}

.port-mobile-list {
  display: grid;
  gap: 10px;
}

.port-card-m {
  border: 1px solid var(--border, rgba(255, 255, 255, 0.06));
  border-radius: 8px;
  padding: 10px;
  background: var(--bg-card, rgba(30, 41, 59, 0.4));
}

.port-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.port-card-title {
  margin-bottom: 4px;
  font-weight: 600;
}

.port-card-head code {
  display: inline-block;
  max-width: min(72vw, 420px);
  overflow-wrap: anywhere;
  font-size: 12px;
}

.port-card-grid {
  display: grid;
  grid-template-columns: 74px minmax(0, 1fr);
  align-items: center;
  gap: 6px 10px;
  font-size: 13px;
}

.port-card-grid > span {
  color: var(--text-sub, #666);
}

.port-card-grid > b,
.port-card-grid > code,
.port-card-grid > div {
  min-width: 0;
  overflow-wrap: anywhere;
}

.port-card-grid > b,
.port-card-grid > code {
  font-weight: 500;
}

.port-card-actions {
  margin-top: 10px;
}

@media (max-width: 767px) {
  .key-toolbar {
    align-items: flex-start;
  }

  .key-toolbar :deep(.ant-col) {
    max-width: 100%;
  }

  .key-toolbar .sub-muted {
    display: inline-block;
  }
}
</style>
