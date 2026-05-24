<template>
  <div class="cf-workers-routes-tab">
    <div class="cf-toolbar">
      <a-space wrap>
        <a-button type="primary" @click="openModal()">添加路由</a-button>
        <a-button :loading="loading" @click="load">刷新</a-button>
      </a-space>
    </div>
    <a-table
      v-if="!isMobile"
      :columns="columns"
      :data-source="routes"
      :loading="loading"
      row-key="id"
      size="middle"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'action'">
          <a-popconfirm title="确定删除此路由？" @confirm="handleDelete(record.id)">
            <a-button type="link" danger size="small">删除</a-button>
          </a-popconfirm>
        </template>
      </template>
    </a-table>
    <a-spin v-else :spinning="loading">
      <a-empty v-if="!loading && routes.length === 0" description="暂无 Workers 路由" />
      <div v-for="record in routes" :key="record.id" class="mobile-card">
        <div class="mobile-card-header">
          <span class="mobile-card-title">{{ record.pattern }}</span>
        </div>
        <div class="mobile-card-body">
          <div class="mobile-card-row">
            <span class="label">Worker</span>
            <span class="value">{{ record.script }}</span>
          </div>
        </div>
        <a-popconfirm title="确定删除此路由？" @confirm="handleDelete(record.id)">
          <a-button size="small" danger>删除</a-button>
        </a-popconfirm>
      </div>
    </a-spin>

    <a-modal
      v-model:open="modalVisible"
      title="添加 Workers 路由"
      :confirm-loading="saveLoading"
      :width="isMobile ? 'calc(100vw - 32px)' : 520"
      @ok="submit"
    >
      <a-form layout="vertical">
        <a-form-item label="Pattern" required>
          <a-input v-model:value="form.pattern" placeholder="如 example.com/*" />
        </a-form-item>
        <a-form-item label="Worker 脚本" required>
          <a-select
            v-model:value="form.script"
            placeholder="选择脚本"
            :loading="scriptsLoading"
            :options="scriptOptions"
            show-search
            option-filter-prop="label"
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  listCfWorkersRoutes,
  createCfWorkersRoute,
  deleteCfWorkersRoute,
  listCfWorkerScripts,
} from '../../api/cloudflare'
import { useIsMobile } from '../../composables/useIsMobile'

const props = defineProps<{ zoneId?: string }>()
const { isMobile } = useIsMobile()

const loading = ref(false)
const saveLoading = ref(false)
const scriptsLoading = ref(false)
const modalVisible = ref(false)
const routes = ref<any[]>([])
const scripts = ref<{ id: string }[]>([])

const form = reactive({ pattern: '', script: '' })

const columns = [
  { title: 'Pattern', dataIndex: 'pattern', ellipsis: true },
  { title: 'Worker', dataIndex: 'script', width: 160 },
  { title: '操作', key: 'action', width: 80 },
]

const scriptOptions = computed(() =>
  scripts.value.map(s => ({ value: s.id, label: s.id })))

async function loadScripts() {
  scriptsLoading.value = true
  try {
    const res = await listCfWorkerScripts(true)
    scripts.value = res.data || []
  } finally {
    scriptsLoading.value = false
  }
}

async function load() {
  if (!props.zoneId) return
  loading.value = true
  try {
    const res = await listCfWorkersRoutes({ zoneId: props.zoneId })
    routes.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openModal() {
  form.pattern = ''
  form.script = ''
  modalVisible.value = true
  if (scripts.value.length === 0) loadScripts()
}

async function submit() {
  if (!props.zoneId) return
  if (!form.pattern.trim() || !form.script) {
    message.warning('请填写 pattern 并选择 Worker')
    return
  }
  saveLoading.value = true
  try {
    await createCfWorkersRoute({
      zoneId: props.zoneId,
      pattern: form.pattern.trim(),
      script: form.script,
    })
    message.success('路由已创建')
    modalVisible.value = false
    await load()
  } finally {
    saveLoading.value = false
  }
}

async function handleDelete(routeId: string) {
  if (!props.zoneId) return
  await deleteCfWorkersRoute({ zoneId: props.zoneId, routeId })
  message.success('已删除')
  await load()
}

watch(() => props.zoneId, () => load(), { immediate: true })
</script>

<style scoped>
.cf-toolbar { margin-bottom: 16px; }
.mobile-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}
.mobile-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.mobile-card-title { font-weight: 600; word-break: break-word; }
.mobile-card-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 4px;
}
.mobile-card-row .label { color: var(--text-sub); flex-shrink: 0; }
.mobile-card-row .value { text-align: right; word-break: break-all; }
</style>
