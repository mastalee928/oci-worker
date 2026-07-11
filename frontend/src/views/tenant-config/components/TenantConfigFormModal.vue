<template>
  <a-modal
    :keyboard="false"
    :open="open"
    :title="editingId ? '编辑配置' : '新增配置'"
    :width="isMobile ? '100%' : 680"
    :body-style="{ maxHeight: '75vh', overflow: 'auto' }"
    :confirm-loading="loading"
    :mask-closable="false"
    @update:open="emit('update:open', $event)"
    @ok="onSubmit"
  >
    <a-form :model="formState" layout="vertical" class="tenant-form-compact">
      <div v-if="!editingId" class="tenant-quick-import-block">
        <div class="tenant-quick-import-header">⚡ 快速导入 — 粘贴 OCI 配置自动填充</div>
        <div class="tenant-quick-import-body">
          <a-textarea
            :value="importText"
            :rows="6"
            placeholder="粘贴 OCI 配置内容，例如：
[Profile-Name]
user=ocid1.user.oc1...
fingerprint=a5:48:75:06...
tenancy=ocid1.tenancy.oc1...
region=ap-tokyo-1"
            style="font-family: monospace; font-size: 12px"
            @update:value="emit('update:importText', $event)"
          />
          <a-button type="primary" size="small" style="margin-top: 8px" @click="emit('parse-and-fill')">
            <template #icon><ThunderboltOutlined /></template>解析并填充
          </a-button>
        </div>
      </div>

      <a-form-item label="自定义名称" required :validate-status="formErrors.username ? 'error' : ''" :help="formErrors.username">
        <a-input v-model:value="formState.username" placeholder="例：我的甲骨文1号" />
      </a-form-item>
      <a-form-item label="Tenant OCID" required :validate-status="formErrors.ociTenantId ? 'error' : ''" :help="formErrors.ociTenantId">
        <a-input v-model:value="formState.ociTenantId" placeholder="ocid1.tenancy.oc1.." />
      </a-form-item>
      <a-form-item label="User OCID" required :validate-status="formErrors.ociUserId ? 'error' : ''" :help="formErrors.ociUserId">
        <a-input v-model:value="formState.ociUserId" placeholder="ocid1.user.oc1.." />
      </a-form-item>
      <a-form-item label="Fingerprint" required :validate-status="formErrors.ociFingerprint ? 'error' : ''" :help="formErrors.ociFingerprint">
        <a-input v-model:value="formState.ociFingerprint" placeholder="xx:xx:xx:..." />
      </a-form-item>
      <a-form-item label="Region" required :validate-status="formErrors.ociRegion ? 'error' : ''" :help="formErrors.ociRegion">
        <a-segmented
          :value="regionInputMode"
          :options="regionInputModeOptions"
          block
          size="small"
          class="region-input-mode"
          @update:value="handleRegionInputModeUpdate"
        />
        <a-select
          v-if="regionInputMode === 'select'"
          v-model:value="formState.ociRegion"
          placeholder="选择区域"
          show-search
          :loading="regionOptionsLoading"
          :filter-option="filterRegionOption"
          @change="emit('normalize-region')"
        >
          <a-select-option v-for="option in regionOptions" :key="option.value" :value="option.value">{{ option.label }}</a-select-option>
        </a-select>
        <a-input
          v-else
          v-model:value="formState.ociRegion"
          placeholder="如 eu-turin-1"
          allow-clear
          @blur="emit('normalize-region')"
          @press-enter="emit('normalize-region')"
        />
      </a-form-item>
      <a-form-item label="私钥 (.pem)" required :validate-status="formErrors.privateKey ? 'error' : ''" :help="formErrors.privateKey">
        <a-segmented
          :value="keyInputMode"
          block
          class="pem-input-mode-segmented"
          :options="[
            { label: '上传文件', value: 'upload' },
            { label: '粘贴内容', value: 'paste' },
          ]"
          @update:value="handleKeyInputModeUpdate"
        />
        <div class="pem-key-input-slot">
          <a-upload-dragger
            v-if="keyInputMode === 'upload'"
            class="pem-upload-dragger"
            :before-upload="beforeUpload"
            :max-count="1"
            accept=".pem"
            :file-list="fileList"
            :show-upload-list="false"
            @remove="emit('remove-file')"
          >
            <p class="ant-upload-drag-icon"><InboxOutlined /></p>
            <p class="ant-upload-text">{{ isMobile ? '点击选择 PEM 文件' : '点击或拖拽 PEM 文件到此处' }}</p>
          </a-upload-dragger>
          <a-textarea
            v-else
            :value="pemPasteText"
            :rows="4"
            class="pem-paste-textarea"
            placeholder="粘贴完整 PEM 私钥，须包含：
-----BEGIN PRIVATE KEY-----
...
-----END PRIVATE KEY-----"
            @update:value="emit('update:pemPasteText', $event)"
          />
        </div>
        <div v-if="keyInputMode === 'upload' && fileList.length" class="pem-upload-filename">
          {{ fileList[0]?.name }}
          <a class="pem-upload-remove" @click.prevent="emit('remove-file')">移除</a>
        </div>
        <span v-if="formState.ociKeyPath && !fileList.length && !pemPasteText.trim()" class="pem-existing-hint">
          已有密钥：{{ formState.ociKeyPath }}（上传或粘贴可覆盖）
        </span>
      </a-form-item>
      <a-form-item label="一级分组">
        <a-select
          v-model:value="formState.groupLevel1"
          placeholder="不选则归入「未分组」"
          allow-clear
          show-search
          @change="emit('clear-group-level2')"
        >
          <a-select-option v-for="group in groupLevel1Options" :key="group" :value="group">{{ group }}</a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item v-if="formState.groupLevel1" label="二级分组（可选）">
        <a-select v-model:value="formState.groupLevel2" placeholder="不选则直接归入一级分组" allow-clear show-search>
          <a-select-option v-for="group in level2Options" :key="group" :value="group">{{ group }}</a-select-option>
        </a-select>
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { InboxOutlined, ThunderboltOutlined } from '@ant-design/icons-vue'
import type { UploadFile } from 'ant-design-vue'

defineOptions({ name: 'TenantConfigFormModal' })

type KeyInputMode = 'upload' | 'paste'
type RegionInputMode = 'select' | 'manual'

interface TenantConfigFormState {
  username: string
  ociTenantId: string
  ociUserId: string
  ociFingerprint: string
  ociRegion: string
  ociKeyPath: string
  groupLevel1: string
  groupLevel2?: string
}

interface SelectOption {
  label: string
  value: string
}

type FilterOption = (input: string, option: any) => boolean

defineProps<{
  open: boolean
  editingId: string
  loading: boolean
  isMobile: boolean
  formState: TenantConfigFormState
  importText: string
  fileList: UploadFile[]
  keyInputMode: KeyInputMode
  pemPasteText: string
  formErrors: Record<string, string>
  regionInputMode: RegionInputMode
  regionInputModeOptions: SelectOption[]
  regionOptionsLoading: boolean
  regionOptions: SelectOption[]
  filterRegionOption: FilterOption
  groupLevel1Options: string[]
  level2Options: string[]
  beforeUpload: (file: File) => unknown
  onSubmit: () => unknown
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:importText', value: string): void
  (e: 'update:keyInputMode', value: KeyInputMode): void
  (e: 'update:pemPasteText', value: string): void
  (e: 'update:regionInputMode', value: RegionInputMode): void
  (e: 'parse-and-fill'): void
  (e: 'normalize-region'): void
  (e: 'key-input-mode-change', value: KeyInputMode): void
  (e: 'remove-file'): void
  (e: 'clear-group-level2'): void
}>()

function handleKeyInputModeUpdate(value: string | number) {
  const mode = value as KeyInputMode
  emit('update:keyInputMode', mode)
  emit('key-input-mode-change', mode)
}

function handleRegionInputModeUpdate(value: string | number) {
  emit('update:regionInputMode', value as RegionInputMode)
}
</script>

<style scoped>
.tenant-form-compact :deep(.ant-form-item) {
  margin-bottom: 12px;
}
.tenant-form-compact :deep(.ant-form-item-label) {
  padding-bottom: 2px;
}
.tenant-quick-import-block {
  margin-bottom: 12px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: var(--trans);
  overflow: hidden;
}
.tenant-quick-import-header {
  padding: 12px 16px;
  font-weight: 600;
  font-size: 14px;
  color: var(--text-main);
  cursor: default;
  user-select: none;
  border-bottom: 1px solid var(--border);
}
.tenant-quick-import-body {
  padding: 12px 16px 16px;
}
.pem-input-mode-segmented {
  margin-bottom: 8px;
}
.region-input-mode {
  margin-bottom: 8px;
}
.pem-key-input-slot {
  height: 118px;
}
.pem-key-input-slot .pem-upload-dragger,
.pem-key-input-slot .pem-upload-dragger :deep(.ant-upload),
.pem-key-input-slot .pem-upload-dragger :deep(.ant-upload-drag) {
  height: 118px;
  margin: 0;
  padding: 0;
  display: block;
}
.pem-key-input-slot .pem-upload-dragger :deep(.ant-upload-drag) {
  height: 100%;
}
.pem-key-input-slot .pem-upload-dragger :deep(.ant-upload-btn) {
  display: flex !important;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  width: 100%;
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}
.pem-key-input-slot .pem-upload-dragger :deep(.ant-upload-drag-icon) {
  margin: 0 0 6px 0;
  font-size: 28px;
  line-height: 1;
}
.pem-key-input-slot .pem-upload-dragger :deep(.ant-upload-text) {
  margin: 0;
  padding: 0 8px;
  font-size: 13px;
  line-height: 1.4;
  text-align: center;
}
.pem-key-input-slot .pem-paste-textarea,
.pem-key-input-slot .pem-paste-textarea :deep(textarea) {
  height: 118px !important;
  min-height: 118px !important;
  margin: 0;
  resize: none;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}
.pem-upload-filename {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-sub, #888);
}
.pem-upload-remove {
  margin-left: 8px;
}
.pem-existing-hint {
  color: var(--text-sub, #888);
  font-size: 12px;
  margin-top: 6px;
  display: block;
}
</style>
