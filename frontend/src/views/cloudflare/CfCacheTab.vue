<template>
  <div class="cf-cache-tab">
    <div class="cf-toolbar">
      <a-space wrap>
        <a-button :loading="loading" @click="load">刷新</a-button>
        <a-popconfirm title="确定清理此区域全部缓存？" @confirm="purgeAll">
          <a-button danger :loading="purging">清理全部缓存</a-button>
        </a-popconfirm>
      </a-space>
    </div>
    <a-spin :spinning="loading">
      <a-form layout="vertical" style="max-width: 480px">
        <a-form-item label="缓存级别 (cache_level)">
          <a-select
            v-model:value="form.cache_level"
            :options="cacheLevelOptions"
            @change="save('cache_level', form.cache_level)"
          />
        </a-form-item>
        <a-form-item label="浏览器缓存 TTL (秒)">
          <a-input-number
            v-model:value="form.browser_cache_ttl"
            :min="0"
            style="width: 100%"
            @blur="save('browser_cache_ttl', form.browser_cache_ttl)"
          />
        </a-form-item>
        <a-form-item label="开发模式">
          <a-switch
            :checked="form.development_mode === 'on'"
            @change="(v: boolean) => save('development_mode', v ? 'on' : 'off')"
          />
        </a-form-item>
        <a-form-item label="Always Online">
          <a-switch
            :checked="form.always_online === 'on'"
            @change="(v: boolean) => save('always_online', v ? 'on' : 'off')"
          />
        </a-form-item>
      </a-form>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getCfCacheSettings, setCfCacheSetting, purgeCfCache } from '../../api/cloudflare'

const props = defineProps<{ zoneId?: string }>()

const loading = ref(false)
const saving = ref(false)
const purging = ref(false)
const form = reactive({
  cache_level: 'aggressive',
  browser_cache_ttl: 14400,
  development_mode: 'off',
  always_online: 'on',
})

const cacheLevelOptions = [
  { value: 'bypass', label: '绕过 (bypass)' },
  { value: 'basic', label: '基本 (basic)' },
  { value: 'simplified', label: '简化 (simplified)' },
  { value: 'aggressive', label: '积极 (aggressive)' },
]

function applyData(data: Record<string, unknown> | null) {
  if (!data) return
  if (data.cache_level != null) form.cache_level = String(data.cache_level)
  if (data.browser_cache_ttl != null) form.browser_cache_ttl = Number(data.browser_cache_ttl)
  if (data.development_mode != null) form.development_mode = String(data.development_mode)
  if (data.always_online != null) form.always_online = String(data.always_online)
}

async function load() {
  if (!props.zoneId) return
  loading.value = true
  try {
    const res = await getCfCacheSettings({ zoneId: props.zoneId })
    applyData(res.data)
  } finally {
    loading.value = false
  }
}

async function save(settingId: string, value: unknown) {
  if (!props.zoneId || saving.value) return
  saving.value = true
  try {
    const res = await setCfCacheSetting({ zoneId: props.zoneId, settingId, value })
    applyData(res.data)
    message.success('已保存')
  } finally {
    saving.value = false
  }
}

async function purgeAll() {
  if (!props.zoneId) return
  purging.value = true
  try {
    await purgeCfCache({ zoneId: props.zoneId, purgeEverything: true })
    message.success('缓存已清理')
  } finally {
    purging.value = false
  }
}

watch(() => props.zoneId, () => load(), { immediate: true })
</script>

<style scoped>
.cf-toolbar { margin-bottom: 16px; }
</style>
