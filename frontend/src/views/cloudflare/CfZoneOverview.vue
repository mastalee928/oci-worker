<template>
  <div class="cf-zone-overview">
    <a-card size="small" title="区域详情" :loading="loading">
      <template #extra>
        <a-space wrap>
          <span class="cf-label-inline">暂停解析</span>
          <a-switch
            :checked="!!detail?.paused"
            :loading="pausedLoading"
            :disabled="!zoneId"
            @change="handlePaused"
          />
          <a-popconfirm
            title="确定删除此区域？此操作不可恢复。"
            ok-text="删除"
            ok-type="danger"
            @confirm="handleDelete"
          >
            <a-button danger size="small" :loading="deleteLoading" :disabled="!zoneId">删除区域</a-button>
          </a-popconfirm>
          <a-button size="small" :loading="loading" :disabled="!zoneId" @click="load">刷新</a-button>
        </a-space>
      </template>
      <a-descriptions v-if="detail" bordered size="small" :column="isMobile ? 1 : 2">
        <a-descriptions-item label="域名">{{ detail.name }}</a-descriptions-item>
        <a-descriptions-item label="状态">
          <a-tag :color="detail.status === 'active' ? 'success' : 'default'">{{ detail.status }}</a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="套餐">{{ detail.planName || '—' }}</a-descriptions-item>
        <a-descriptions-item label="Zone ID">{{ detail.id }}</a-descriptions-item>
        <a-descriptions-item label="NS 服务器" :span="isMobile ? 1 : 2">
          <span v-if="detail.nameServers?.length">{{ detail.nameServers.join(' · ') }}</span>
          <span v-else>—</span>
        </a-descriptions-item>
      </a-descriptions>
      <a-empty v-else-if="!loading" description="暂无区域信息" />
    </a-card>
    <p class="cf-hint">使用左侧菜单管理 DNS、电子邮件、SSL/TLS、安全性、缓存、Workers 路由与 Page Rules。</p>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'
import { getCfZoneDetail, deleteCfZone, setCfZonePaused } from '../../api/cloudflare'

const props = defineProps<{
  zoneId?: string
}>()

const emit = defineEmits<{
  'update:zoneId': [value: string | undefined]
}>()

const loading = ref(false)
const pausedLoading = ref(false)
const deleteLoading = ref(false)
const detail = ref<{
  id: string
  name: string
  status: string
  paused?: boolean
  planName?: string
  nameServers?: string[]
} | null>(null)

const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }

let loadSeq = 0

async function load() {
  if (!props.zoneId) {
    detail.value = null
    return
  }
  const seq = ++loadSeq
  loading.value = true
  try {
    const res = await getCfZoneDetail({ zoneId: props.zoneId }, true)
    if (seq !== loadSeq) return
    detail.value = res.data || null
  } catch {
    if (seq !== loadSeq) return
    detail.value = null
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function handlePaused(paused: boolean) {
  if (!props.zoneId) return
  pausedLoading.value = true
  try {
    const res = await setCfZonePaused({ zoneId: props.zoneId, paused })
    if (detail.value) detail.value.paused = res.data?.paused ?? paused
    message.success(paused ? '区域已暂停' : '区域已恢复')
  } finally {
    pausedLoading.value = false
  }
}

async function handleDelete() {
  if (!props.zoneId) return
  deleteLoading.value = true
  try {
    await deleteCfZone({ zoneId: props.zoneId })
    message.success('区域已删除')
    detail.value = null
    emit('update:zoneId', undefined)
  } finally {
    deleteLoading.value = false
  }
}

watch(() => props.zoneId, () => load(), { immediate: true })
onMounted(() => window.addEventListener('resize', checkMobile))
onUnmounted(() => window.removeEventListener('resize', checkMobile))
</script>

<style scoped>
.cf-hint {
  margin-top: 12px;
  font-size: 12px;
  color: var(--text-sub);
}
.cf-label-inline {
  font-size: 13px;
  color: var(--text-sub);
}
</style>
