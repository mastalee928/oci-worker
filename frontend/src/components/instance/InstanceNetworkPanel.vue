<template>
  <div class="instance-network-panel">
    <a-button @click="loadVcns(true)" :loading="vcnLoading" style="margin-bottom: 12px">加载 VCN</a-button>
    <a-table
      v-if="!isMobile"
      :data-source="vcns"
      :columns="vcnColumns"
      size="small"
      :pagination="false"
      row-key="id"
      :loading="vcnLoading"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'vcnAction'">
          <a-button size="small" type="primary" @click="emit('open-vcn-manager', record)">管理</a-button>
        </template>
      </template>
    </a-table>
    <template v-else>
      <a-empty v-if="vcns.length === 0" description="暂无 VCN" />
      <div v-for="v in vcns" :key="v.id" class="mobile-card">
        <div class="mobile-card-header">
          <span class="mobile-card-title">{{ v.displayName }}</span>
        </div>
        <div class="mobile-card-body">
          <div class="mobile-card-row"><span class="label">CIDR</span><span class="value">{{ v.cidrBlock }}</span></div>
          <div class="mobile-card-row"><span class="label">状态</span><span class="value">{{ v.lifecycleState }}</span></div>
        </div>
        <div style="margin-top: 8px">
          <a-button block size="small" type="primary" @click="emit('open-vcn-manager', v)">管理</a-button>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getVcns } from '../../api/instance'

const props = defineProps<{
  tenant: any | null
  instance: any | null
  isMobile: boolean
  region?: string
}>()

const emit = defineEmits<{
  (e: 'open-vcn-manager', vcn: any): void
}>()

const vcnColumns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName' },
  { title: 'CIDR', dataIndex: 'cidrBlock', key: 'cidrBlock', width: 160 },
  { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 100 },
  { title: '操作', key: 'vcnAction', width: 100 },
]

const vcnLoading = ref(false)
const vcns = ref<any[]>([])
let vcnLoadSeq = 0

function regionParam(): { region?: string } {
  const r =
    (props.region && String(props.region).trim()) ||
    (props.instance?.region && String(props.instance.region).trim()) ||
    (props.tenant?.ociRegion && String(props.tenant.ociRegion).trim()) ||
    ''
  return r ? { region: r } : {}
}

function currentTargetKey() {
  const scope = regionParam()
  return [
    props.tenant?.id || '',
    props.instance?.instanceId || '',
    scope.region || '',
  ].join('|')
}

function sameTarget(targetKey: string) {
  return currentTargetKey() === targetKey
}

function reset() {
  vcnLoadSeq += 1
  vcnLoading.value = false
  vcns.value = []
}

async function loadVcns(force = false) {
  if (!props.tenant) return
  const requestId = ++vcnLoadSeq
  const tenantId = props.tenant.id
  const targetKey = currentTargetKey()
  const scope = regionParam()
  vcnLoading.value = true
  try {
    const res = await getVcns({
      id: tenantId,
      ...scope,
      force,
    })
    if (requestId !== vcnLoadSeq || !sameTarget(targetKey)) return
    vcns.value = res.data || []
  } catch (e: any) {
    if (requestId === vcnLoadSeq && sameTarget(targetKey)) message.error(e?.message || '加载 VCN 失败')
  } finally {
    if (requestId === vcnLoadSeq && sameTarget(targetKey)) vcnLoading.value = false
  }
}

watch(
  () => [props.tenant?.id, props.instance?.instanceId, props.region],
  () => {
    reset()
  },
)

defineExpose({
  loadVcns,
  reset,
})
</script>

<style scoped>
.mobile-card {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
}
.mobile-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
.mobile-card-title {
  color: var(--text-main);
  font-weight: 600;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mobile-card-body {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.mobile-card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
}
.mobile-card-row .label {
  color: var(--text-sub);
  flex: 0 0 auto;
}
.mobile-card-row .value {
  color: var(--text-main);
  min-width: 0;
  text-align: right;
  overflow-wrap: anywhere;
}
</style>
