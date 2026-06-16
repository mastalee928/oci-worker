<template>
  <div class="cf-zone-bar" :class="{ 'is-mobile': isMobile }">
    <a-select
      :value="zoneId"
      :disabled="!cfConfigured"
      :loading="zonesLoading"
      placeholder="选择区域（Zone）"
      :show-search="false"
      class="cf-zone-select"
      :options="zoneOptions"
      @update:value="onSelectChange"
    />
    <div class="cf-zone-bar-actions">
      <a-button :disabled="!cfConfigured" :loading="zonesLoading" @click="loadZones(zonePage)">
        <template #icon><ReloadOutlined /></template>
        刷新区域
      </a-button>
      <a-button v-if="showCreateZone" type="primary" :disabled="!cfConfigured" @click="emit('create-zone')">
        <template #icon><PlusOutlined /></template>
        添加区域
      </a-button>
    </div>
    <a-pagination
      v-if="zoneTotalPages > 1"
      v-model:current="zonePage"
      class="cf-zone-pagination"
      :total="zoneTotal"
      :page-size="zonePerPage"
      size="small"
      :show-size-changer="false"
      :show-total="(t: number) => `共 ${t} 个区域`"
      @change="onZonePageChange"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { listCfZonesPage } from '../../api/cloudflare'
import { useIsMobile } from '../../composables/useIsMobile'

interface CfZoneSummary {
  id: string
  name: string
  status: string
  paused?: boolean
}

const props = withDefaults(defineProps<{
  cfConfigured: boolean
  zoneId?: string
  showCreateZone?: boolean
}>(), {
  showCreateZone: false,
})

const emit = defineEmits<{
  'update:zoneId': [value: string | undefined]
  'zone-change': [zoneId: string | undefined]
  'create-zone': []
}>()

const { isMobile } = useIsMobile()

const zonesLoading = ref(false)
const zones = ref<CfZoneSummary[]>([])
const zonePage = ref(1)
const zonePerPage = ref(50)
const zoneTotal = ref(0)
const zoneTotalPages = ref(1)

const zoneOptions = computed(() =>
  zones.value.map(z => ({ value: z.id, label: `${z.name} (${z.status})` })))

async function loadZones(page = zonePage.value) {
  if (!props.cfConfigured) return
  zonesLoading.value = true
  try {
    const res = await listCfZonesPage({ page, perPage: zonePerPage.value })
    const data = res.data || {}
    zones.value = data.records || []
    zoneTotal.value = data.total ?? zones.value.length
    zonePage.value = data.page ?? page
    zonePerPage.value = data.perPage ?? zonePerPage.value
    zoneTotalPages.value = data.totalPages ?? 1
    if (props.zoneId && !zones.value.some(z => z.id === props.zoneId)) {
      // keep selection if on another page
    }
  } finally {
    zonesLoading.value = false
  }
}

function onSelectChange(val: string | undefined) {
  emit('update:zoneId', val)
  emit('zone-change', val)
}

function onZonePageChange(page: number) {
  zonePage.value = page
  loadZones(page)
}

watch(() => props.cfConfigured, (v) => {
  if (v) loadZones(1)
})

onMounted(() => {
  if (props.cfConfigured) loadZones(1)
})

defineExpose({ loadZones, zones })
</script>

<style scoped>
.cf-zone-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 16px;
}
.cf-zone-select {
  min-width: 220px;
  flex: 1;
  max-width: 360px;
}
.cf-zone-bar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.cf-zone-bar.is-mobile {
  flex-direction: column;
  align-items: stretch;
}
.cf-zone-bar.is-mobile .cf-zone-select {
  min-width: 0;
  max-width: none;
  width: 100%;
}
.cf-zone-bar.is-mobile .cf-zone-bar-actions {
  width: 100%;
}
.cf-zone-bar.is-mobile .cf-zone-bar-actions .ant-btn {
  flex: 1;
}
.cf-zone-bar.is-mobile .cf-zone-pagination {
  width: 100%;
  display: flex;
  justify-content: center;
}
</style>
