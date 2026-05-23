<template>
  <div class="cf-ssl-tab">
    <div class="cf-toolbar">
      <a-button :loading="loading" @click="load">刷新</a-button>
    </div>
    <a-spin :spinning="loading">
      <a-form layout="vertical" style="max-width: 480px">
        <a-form-item label="加密模式 (SSL)">
          <a-select v-model:value="form.ssl" :options="sslOptions" @change="save('ssl', form.ssl)" />
          <p class="cf-hint">off / flexible / full / strict</p>
        </a-form-item>
        <a-form-item label="始终使用 HTTPS">
          <a-switch
            :checked="form.always_use_https === 'on'"
            @change="(v: boolean) => save('always_use_https', v ? 'on' : 'off')"
          />
        </a-form-item>
        <a-form-item label="最低 TLS 版本">
          <a-select
            v-model:value="form.min_tls_version"
            :options="tlsOptions"
            @change="save('min_tls_version', form.min_tls_version)"
          />
        </a-form-item>
        <a-form-item label="TLS 1.3">
          <a-switch
            :checked="form.tls_1_3 === 'on'"
            @change="(v: boolean) => save('tls_1_3', v ? 'on' : 'off')"
          />
        </a-form-item>
      </a-form>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getCfSslSettings, setCfSslSetting } from '../../api/cloudflare'

const props = defineProps<{ zoneId?: string }>()

const loading = ref(false)
const saving = ref(false)
const form = reactive({
  ssl: 'full',
  always_use_https: 'off',
  min_tls_version: '1.2',
  tls_1_3: 'on',
})

const sslOptions = [
  { value: 'off', label: '关闭 (off)' },
  { value: 'flexible', label: '灵活 (flexible)' },
  { value: 'full', label: '完全 (full)' },
  { value: 'strict', label: '完全（严格）(strict)' },
]
const tlsOptions = [
  { value: '1.0', label: 'TLS 1.0' },
  { value: '1.1', label: 'TLS 1.1' },
  { value: '1.2', label: 'TLS 1.2' },
  { value: '1.3', label: 'TLS 1.3' },
]

function applyData(data: Record<string, unknown> | null) {
  if (!data) return
  if (data.ssl != null) form.ssl = String(data.ssl)
  if (data.always_use_https != null) form.always_use_https = String(data.always_use_https)
  if (data.min_tls_version != null) form.min_tls_version = String(data.min_tls_version)
  if (data.tls_1_3 != null) form.tls_1_3 = String(data.tls_1_3)
}

async function load() {
  if (!props.zoneId) return
  loading.value = true
  try {
    const res = await getCfSslSettings({ zoneId: props.zoneId })
    applyData(res.data)
  } finally {
    loading.value = false
  }
}

async function save(settingId: string, value: unknown) {
  if (!props.zoneId || saving.value) return
  saving.value = true
  try {
    const res = await setCfSslSetting({ zoneId: props.zoneId, settingId, value })
    applyData(res.data)
    message.success('已保存')
  } finally {
    saving.value = false
  }
}

watch(() => props.zoneId, () => load(), { immediate: true })
</script>

<style scoped>
.cf-toolbar { margin-bottom: 16px; }
.cf-hint { margin: 4px 0 0; font-size: 12px; color: var(--text-sub); }
</style>
