<template>
  <a-modal
    :mask-closable="false"
    :keyboard="false"
    v-model:open="modalOpen"
    :title="portForm.id ? '编辑端口绑定' : '添加端口绑定'"
    :confirm-loading="saving"
    :width="isMobile ? 'calc(100vw - 32px)' : 720"
    @ok="savePortBindingRow"
  >
    <a-form layout="vertical">
      <a-form-item label="租户">
        <a-select
          v-model:value="portForm.ociUserId"
          :options="tenantOptions"
          :loading="tenantsLoading"
          placeholder="选择 OCI 租户"
          show-search
          :filter-option="filterTenant"
          :get-popup-container="selectPopupContainer"
          @change="onPortTenantChange"
        />
      </a-form-item>
      <a-form-item label="Region">
        <a-select
          v-model:value="portForm.ociRegion"
          :options="portRegionOptions"
          :loading="portRegionsLoading"
          placeholder="选择该租户订阅的 Region"
          show-search
          :filter-option="filterOciRegionSelectOption"
          :disabled="!portForm.ociUserId"
          :get-popup-container="selectPopupContainer"
          @change="onPortRegionChange"
        />
        <div class="sub-muted form-help">该端口会固定转发到这里选择的 OCI Generative AI 区域。</div>
      </a-form-item>
      <a-form-item label="API Key">
        <a-space direction="vertical" style="width: 100%">
          <a-select
            v-model:value="portForm.openaiKeyId"
            :options="portKeyOptions"
            :loading="portKeysLoading"
            placeholder="选择该租户的 API Key"
            :get-popup-container="selectPopupContainer"
          />
          <a-button size="small" :disabled="!portForm.ociUserId" :loading="portKeyCreating" @click="createPortTenantKey">
            生成该租户 API Key
          </a-button>
        </a-space>
      </a-form-item>
      <a-form-item label="端口">
        <a-input-number v-model:value="portForm.port" :min="30000" :max="39999" :precision="0" style="width: 100%" />
      </a-form-item>
      <a-form-item label="默认 max_tokens">
        <a-input-number
          v-model:value="portForm.defaultMaxTokens"
          :min="1"
          :max="200000"
          :precision="0"
          :controls="false"
          placeholder="留空使用全局默认"
          style="width: 100%"
        />
        <div class="sub-muted form-help">仅在请求未显式传 max_tokens 时生效。</div>
      </a-form-item>
      <a-form-item label="备注">
        <a-input v-model:value="portForm.name" placeholder="sub2api-channel-1" />
      </a-form-item>
      <a-form-item label="模型">
        <a-radio-group
          v-model:value="portForm.modelLimitMode"
          class="model-mode-group"
          @change="onPortModelLimitModeChange"
        >
          <a-radio-button value="unlimited">不限制模型</a-radio-button>
          <a-radio-button value="limited">限制模型</a-radio-button>
        </a-radio-group>
        <template v-if="isMobile">
          <select
            class="mobile-model-select"
            multiple
            :value="portForm.allowedModels"
            @change="onMobileModelSelect"
            size="6"
            style="width:100%;min-height:80px"
            :disabled="portModelsLoading || portForm.modelLimitMode === 'unlimited'"
          >
            <option v-if="!portModelOptions.length" disabled>暂无可用模型</option>
            <option v-for="m in portModelOptions" :key="m.value" :value="m.value">{{ m.label }}</option>
          </select>
          <div class="sub-muted form-help">限制模型时该端口的 /v1/models 只返回这里选择的模型；不限制时返回该区域可用模型。</div>
          <a-button
            class="port-model-refresh"
            size="small"
            :loading="portModelsLoading"
            :disabled="!portForm.ociUserId"
            @click="() => portForm.ociUserId && loadPortModels(portForm.ociUserId, portForm.ociRegion, true)"
          >
            刷新模型列表
          </a-button>
        </template>
        <a-select
          v-else
          v-model:value="portForm.allowedModels"
          mode="multiple"
          :options="portModelOptions"
          :loading="portModelsLoading"
          :disabled="portForm.modelLimitMode === 'unlimited'"
          :placeholder="portForm.modelLimitMode === 'unlimited' ? '当前不限制模型' : '至少选择一个模型'"
          allow-clear
          show-search
          :filter-option="filterModel"
          :max-tag-count="6"
          :max-tag-placeholder="(omittedValues: any[]) => `+${omittedValues?.length || 0}`"
          :get-popup-container="selectPopupContainer"
          :dropdown-style="{ maxHeight: 'min(70vh, 480px)' }"
        />
        <div v-if="portForm.modelLimitMode === 'limited' && !portForm.allowedModels.length" class="sub-muted form-help">
          限制模型时至少保留一个模型；不限制时请切回“不限制模型”。
        </div>
      </a-form-item>

      <a-form-item label="启用">
        <a-switch v-model:checked="portForm.enabled" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { listOciRegionOptions } from '../../api/system'
import {
  createOracleKey,
  listOpenAiModels,
  listOracleKeys,
  saveOracleAiPortBinding,
} from '../../api/oracleAi'
import {
  filterOciRegionSelectOption,
  getOciRegionDisplayName,
} from '../../utils/ociRegionCatalog'

const props = defineProps<{
  tenantOptions: { label: string; value: string; ociRegion: string }[]
  tenantsLoading: boolean
  portBindings: any[]
  isMobile: boolean
}>()

const emit = defineEmits<{
  (e: 'ensure-tenants'): void
  (e: 'saved'): void
  (e: 'generated-key', apiKey: string): void
}>()

const modalOpen = ref(false)
const saving = ref(false)
const portKeysLoading = ref(false)
const portKeyCreating = ref(false)
const portKeyOptions = ref<{ label: string; value: string }[]>([])
const portKeyFallbackOption = ref<{ label: string; value: string } | null>(null)
const portRegionsLoading = ref(false)
const portRegionOptions = ref<{ label: string; value: string }[]>([])
const portModelsLoading = ref(false)
const portModelOptions = ref<{ label: string; value: string; title?: string }[]>([])

const portForm = ref<{
  id?: string
  name?: string
  port: number
  ociUserId?: string
  ociRegion?: string
  openaiKeyId?: string
  defaultMaxTokens?: number | null
  allowedModels: string[]
  modelLimitMode: 'unlimited' | 'limited'
  enabled: boolean
}>({
  port: 30000,
  defaultMaxTokens: null,
  allowedModels: [],
  modelLimitMode: 'unlimited',
  enabled: true,
})

function selectPopupContainer() {
  return document.body
}

function filterTenant(input: string, opt: any) {
  const q = String(input || '').toLowerCase()
  const label = String(opt?.label || '').toLowerCase()
  return label.includes(q)
}

function filterModel(input: string, opt: any) {
  const q = String(input || '').toLowerCase()
  const label = String(opt?.label || '').toLowerCase()
  const value = String(opt?.value || '').toLowerCase()
  return label.includes(q) || value.includes(q)
}

function normalizeModelSelection(models?: unknown) {
  if (!Array.isArray(models)) return []
  const seen = new Set<string>()
  const out: string[] = []
  for (const raw of models) {
    const s = String(raw || '').trim()
    if (!s || seen.has(s)) continue
    seen.add(s)
    out.push(s)
  }
  return out
}

function mapModelOptions(data: any) {
  const raw = Array.isArray(data?.data) ? data.data : Array.isArray(data) ? data : []
  const seen = new Set<string>()
  const out: { label: string; value: string; title?: string }[] = []
  for (const item of raw) {
    const id = String(item?.id || item?.model || item || '').trim()
    if (!id || seen.has(id)) continue
    seen.add(id)
    out.push({ label: id, value: id, title: id })
  }
  return out
}

function ensureSelectedModelsInOptions(options: { label: string; value: string; title?: string }[], selected?: string[]) {
  const existing = new Set(options.map((x) => x.value))
  const out = options.slice()
  for (const model of normalizeModelSelection(selected)) {
    if (existing.has(model)) continue
    out.push({
      value: model,
      label: `${model}（不在当前列表）`,
      title: '不在当前列表（可能是租户/区域变化或模型下线）',
    })
    existing.add(model)
  }
  return out
}

function regionDisplay(region?: string) {
  const r = String(region || '').trim()
  if (!r) return ''
  return `${getOciRegionDisplayName(r)} (${r})`
}

function keyFallbackOption(row?: any) {
  const value = String(row?.openaiKeyId || '').trim()
  if (!value) return null
  const name = row?.keyName || '当前保存的 API Key'
  const masked = row?.keyMasked || 'sk-****'
  return { value, label: `${name} (${masked})` }
}

function ensureSelectedKeyOption(options: { label: string; value: string }[]) {
  const selected = String(portForm.value.openaiKeyId || '').trim()
  if (!selected || options.some((x) => String(x.value) === selected)) return options
  const fallback = portKeyFallbackOption.value && String(portKeyFallbackOption.value.value) === selected
    ? portKeyFallbackOption.value
    : { value: selected, label: '当前保存的 API Key（不在当前可用列表）' }
  return [fallback, ...options]
}

function nextPortValue() {
  const used = new Set((props.portBindings || []).map((x: any) => Number(x?.port)).filter((x: number) => Number.isFinite(x)))
  for (let p = 30000; p <= 39999; p++) {
    if (!used.has(p)) return p
  }
  return 30000
}

function ensurePortTenantOptionsLoading() {
  if (!props.tenantOptions.length && !props.tenantsLoading) {
    emit('ensure-tenants')
  }
}

function open(row?: any) {
  if (!row) ensurePortTenantOptionsLoading()
  portForm.value = {
    id: row?.id,
    name: row?.name || '',
    port: Number(row?.port || nextPortValue()),
    ociUserId: row?.ociUserId || undefined,
    ociRegion: row?.ociRegion || row?.tenantDefaultRegion || undefined,
    openaiKeyId: row?.openaiKeyId || undefined,
    defaultMaxTokens: row?.defaultMaxTokens ? Number(row.defaultMaxTokens) : null,
    allowedModels: Array.isArray(row?.allowedModels) ? row.allowedModels : [],
    modelLimitMode: Array.isArray(row?.allowedModels) && row.allowedModels.length ? 'limited' : 'unlimited',
    enabled: row?.enabled !== false,
  }
  modalOpen.value = true
  portKeyFallbackOption.value = keyFallbackOption(row)
  portKeyOptions.value = portKeyFallbackOption.value ? [portKeyFallbackOption.value] : []
  portRegionOptions.value = []
  portModelOptions.value = ensureSelectedModelsInOptions([], portForm.value.allowedModels || [])
  if (portForm.value.ociUserId) {
    const tenantId = portForm.value.ociUserId
    void loadPortModalTenantContext(
      tenantId,
      portForm.value.ociRegion,
      portForm.value.modelLimitMode === 'limited',
    )
  } else {
    portKeysLoading.value = false
    portRegionsLoading.value = false
    portModelsLoading.value = false
  }
}

async function loadPortModalTenantContext(tenantId: string, preferredRegion?: string, loadModels = false) {
  await Promise.all([
    loadPortKeys(tenantId).catch(() => undefined),
    loadPortRegions(tenantId, preferredRegion).catch(() => undefined),
  ])
  if (loadModels && portForm.value.ociUserId === tenantId) {
    await loadPortModels(tenantId, portForm.value.ociRegion)
  }
}

function onPortTenantChange() {
  portForm.value.openaiKeyId = undefined
  portForm.value.ociRegion = undefined
  portForm.value.allowedModels = []
  portForm.value.modelLimitMode = 'unlimited'
  portKeyOptions.value = []
  portKeyFallbackOption.value = null
  portRegionOptions.value = []
  portModelOptions.value = []
  if (portForm.value.ociUserId) {
    const tenantId = portForm.value.ociUserId
    void loadPortModalTenantContext(tenantId)
  } else {
    portKeysLoading.value = false
    portRegionsLoading.value = false
    portModelsLoading.value = false
  }
}

function onPortRegionChange() {
  portForm.value.allowedModels = []
  portForm.value.modelLimitMode = 'unlimited'
  portModelOptions.value = []
}

function onPortModelLimitModeChange(e?: any) {
  const nextMode = e?.target?.value || portForm.value.modelLimitMode
  if (nextMode === 'unlimited') {
    portForm.value.allowedModels = []
  } else {
    portForm.value.allowedModels = normalizeModelSelection(portForm.value.allowedModels)
    if (portForm.value.ociUserId) {
      void loadPortModels(portForm.value.ociUserId, portForm.value.ociRegion, true)
    }
  }
}

function onMobileModelSelect(e: Event) {
  const sel = e.target as HTMLSelectElement
  portForm.value.allowedModels = Array.from(sel.selectedOptions, (o: HTMLOptionElement) => o.value)
}

async function loadPortRegions(tenantId: string, preferred?: string) {
  portRegionsLoading.value = true
  try {
    const r: any = await listOciRegionOptions(tenantId)
    if (portForm.value.ociUserId !== tenantId) return
    const rows = Array.isArray(r?.data) ? r.data : []
    const options = rows
      .map((x: any) => ({
        value: String(x.regionId || '').trim(),
        label: x.label || String(x.regionId || '').trim(),
      }))
      .filter((x: any) => x.value)
    const tenantDefault = props.tenantOptions.find((x) => x.value === tenantId)?.ociRegion || ''
    const selected = String(preferred || portForm.value.ociRegion || tenantDefault || '').trim()
    if (selected && !options.some((x: any) => x.value === selected)) {
      options.unshift({ value: selected, label: regionDisplay(selected) })
    }
    portRegionOptions.value = options
    if (!portForm.value.ociRegion) {
      portForm.value.ociRegion = selected || options[0]?.value
    }
  } catch {
    if (portForm.value.ociUserId !== tenantId) return
    const fallback = String(preferred || props.tenantOptions.find((x) => x.value === tenantId)?.ociRegion || '').trim()
    portRegionOptions.value = fallback ? [{ value: fallback, label: regionDisplay(fallback) }] : []
    if (!portForm.value.ociRegion) {
      portForm.value.ociRegion = fallback || undefined
    }
  } finally {
    if (portForm.value.ociUserId === tenantId) {
      portRegionsLoading.value = false
    }
  }
}

async function loadPortKeys(tenantId: string) {
  portKeysLoading.value = true
  try {
    const r: any = await listOracleKeys({ ociUserId: tenantId })
    if (portForm.value.ociUserId !== tenantId) return
    const raw = Array.isArray(r?.data) ? r.data : r?.data?.records || []
    portKeyOptions.value = ensureSelectedKeyOption(raw
      .filter((x: any) => !x.disabled)
      .map((x: any) => ({
        value: x.id,
        label: `${x.name || '未命名'} (${x.keyMasked || 'sk-****'})`,
      })))
    if (!portForm.value.openaiKeyId && portKeyOptions.value.length) {
      portForm.value.openaiKeyId = portKeyOptions.value[0].value
    }
  } catch {
    if (portForm.value.ociUserId !== tenantId) return
    portKeyOptions.value = portKeyFallbackOption.value ? [portKeyFallbackOption.value] : []
  } finally {
    if (portForm.value.ociUserId === tenantId) {
      portKeysLoading.value = false
    }
  }
}

async function createPortTenantKey() {
  const tenantId = portForm.value.ociUserId
  if (!tenantId) return
  portKeyCreating.value = true
  try {
    const r: any = await createOracleKey({
      ociUserId: tenantId,
      name: portForm.value.name || `port-${portForm.value.port}`,
    })
    const id = r?.data?.id
    const apiKey = r?.data?.apiKey || ''
    await loadPortKeys(tenantId)
    if (portForm.value.ociUserId !== tenantId) return
    if (id) {
      portForm.value.openaiKeyId = id
    }
    if (apiKey) {
      emit('generated-key', apiKey)
    }
    message.success('已生成 API Key')
  } catch (e: any) {
    message.error(e?.message || '生成失败')
  } finally {
    portKeyCreating.value = false
  }
}

async function loadPortModels(tenantId: string, region?: string, alertOnErr = false) {
  portModelsLoading.value = true
  try {
    const r: any = await listOpenAiModels({ ociUserId: tenantId, ociRegion: region })
    if (portForm.value.ociUserId !== tenantId) return
    portModelOptions.value = ensureSelectedModelsInOptions(mapModelOptions(r?.data), portForm.value.allowedModels || [])
    if (!portModelOptions.value.length && alertOnErr) {
      message.info('无模型条目或 OCI 返回与预期结构不同，请查看后端日志。')
    }
  } catch (e: any) {
    if (portForm.value.ociUserId !== tenantId) return
    if (alertOnErr) {
      message.error(e?.message || '刷新模型失败')
    }
  } finally {
    if (portForm.value.ociUserId === tenantId) {
      portModelsLoading.value = false
    }
  }
}

async function savePortBindingRow() {
  if (saving.value) return
  const f = portForm.value
  const selectedModels = normalizeModelSelection(f.allowedModels)
  const port = Number(f.port)
  if (!f.ociUserId || !f.openaiKeyId) {
    message.warning('请选择租户和 API Key')
    return
  }
  if (!Number.isFinite(port) || port < 30000 || port > 39999) {
    message.warning('端口必须在 30000-39999 之间')
    return
  }
  if (!Number.isInteger(port)) {
    message.warning('端口必须是整数')
    return
  }
  const duplicated = (props.portBindings || []).some((x: any) => {
    if (String(x?.id || '') === String(f.id || '')) return false
    return Number(x?.port) === Math.trunc(port)
  })
  if (duplicated) {
    message.warning('该端口已被其他绑定使用')
    return
  }
  const maxTokens = f.defaultMaxTokens == null ? null : Number(f.defaultMaxTokens)
  if (maxTokens != null && (!Number.isFinite(maxTokens) || maxTokens < 1 || maxTokens > 200000)) {
    message.warning('默认 max_tokens 必须在 1-200000 之间')
    return
  }
  if (maxTokens != null && !Number.isInteger(maxTokens)) {
    message.warning('默认 max_tokens 必须是整数')
    return
  }
  if (f.modelLimitMode === 'limited' && !selectedModels.length) {
    message.warning('限制模型时至少选择一个模型')
    return
  }
  saving.value = true
  try {
    await saveOracleAiPortBinding({
      id: f.id,
      name: f.name,
      port: Math.trunc(port),
      ociUserId: f.ociUserId,
      ociRegion: f.ociRegion,
      openaiKeyId: f.openaiKeyId,
      defaultMaxTokens: maxTokens == null ? null : Math.trunc(maxTokens),
      allowedModels: f.modelLimitMode === 'limited' ? selectedModels : [],
      enabled: f.enabled,
    })
    modalOpen.value = false
    message.success('已保存，端口已同步')
    emit('saved')
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

defineExpose({ open })
</script>

<style scoped>
.sub-muted {
  color: var(--text-sub, #666);
  font-size: 13px;
  line-height: 1.5;
  opacity: 0.9;
}

.form-help {
  margin-top: 6px;
}

.model-mode-group {
  display: flex;
  width: 100%;
  margin-bottom: 8px;
}

.model-mode-group :deep(.ant-radio-button-wrapper) {
  flex: 1;
  text-align: center;
}

.port-model-refresh {
  margin-top: 8px;
}

.mobile-model-select {
  width: 100%;
  min-height: 80px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  padding: 4px;
  background: var(--card-bg, #1a1f2e);
  color: #e0e0e0;
  font-size: 14px;
}

.mobile-model-select option {
  background: var(--card-bg, #1a1f2e);
  color: #e0e0e0;
}

.mobile-model-select option:checked,
.mobile-model-select option:focus,
.mobile-model-select option:hover {
  background: rgba(24, 144, 255, 0.3);
  color: #fff;
}

:global([data-theme="light"] .mobile-model-select) {
  border-color: rgba(15, 23, 42, 0.14);
  background: #f8fafc;
  color: #0f172a;
}

:global([data-theme="light"] .mobile-model-select option) {
  background: #fff;
  color: #0f172a;
}

:global([data-theme="light"] .mobile-model-select option:checked),
:global([data-theme="light"] .mobile-model-select option:focus),
:global([data-theme="light"] .mobile-model-select option:hover) {
  background: rgba(59, 130, 246, 0.16);
  color: #0f172a;
}
</style>
