<template>
  <a-modal
    v-model:open="visible"
    :title="modalTitle"
    :width="isMobile ? '100%' : 880"
    :footer="null"
    :mask-closable="false"
    :keyboard="false"
    destroy-on-close
    @cancel="handleClose"
  >
    <template v-if="viewMode === 'list'">
      <div class="api-key-toolbar">
        <div class="api-key-user">用户：<b>{{ currentUser?.name || currentUser?.id || '—' }}</b></div>
        <a-button type="primary" :loading="creating" @click="handleCreate">新增 API Key</a-button>
      </div>

      <a-spin :spinning="loading">
        <a-empty v-if="!loading && apiKeys.length === 0" description="该用户暂无 API Key" />

        <a-table
          v-else-if="!isMobile"
          :columns="columns"
          :data-source="apiKeys"
          :pagination="false"
          row-key="fingerprint"
          size="middle"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'fingerprint'">
              <span class="fingerprint" :title="record.fingerprint || ''">{{ record.fingerprint || '—' }}</span>
            </template>
            <template v-else-if="column.key === 'state'">
              <a-tag :color="stateColor(record.lifecycleState)">{{ stateText(record.lifecycleState) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'timeCreated'">
              {{ formatTime(record.timeCreated) }}
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <a-button type="link" size="small" @click="showDetails(record)">查看</a-button>
                <a-button
                  type="link"
                  size="small"
                  class="danger-link"
                  :loading="deletingFingerprint === record.fingerprint"
                  @click="handleDelete(record)"
                >
                  删除
                </a-button>
              </a-space>
            </template>
          </template>
        </a-table>

        <div v-else class="api-key-mobile-list">
          <div v-for="item in apiKeys" :key="item.fingerprint || item.keyId" class="api-key-card">
            <div class="api-key-card-fingerprint" :title="item.fingerprint || ''">
              {{ item.fingerprint || '—' }}
            </div>
            <div class="api-key-card-row">
              <span>状态</span>
              <a-tag :color="stateColor(item.lifecycleState)">{{ stateText(item.lifecycleState) }}</a-tag>
            </div>
            <div class="api-key-card-row">
              <span>创建时间</span>
              <span>{{ formatTime(item.timeCreated) }}</span>
            </div>
            <div class="api-key-card-actions">
              <a-button block @click="showDetails(item)">查看</a-button>
              <a-button
                block
                danger
                :loading="deletingFingerprint === item.fingerprint"
                @click="handleDelete(item)"
              >
                删除
              </a-button>
            </div>
          </div>
        </div>
      </a-spin>
    </template>

    <template v-else-if="viewMode === 'detail'">
      <a-button class="back-button" @click="backToList">返回 API Key 列表</a-button>
      <a-descriptions :column="1" bordered size="small">
        <a-descriptions-item label="Fingerprint">{{ detailKey?.fingerprint || '—' }}</a-descriptions-item>
        <a-descriptions-item label="状态">{{ stateText(detailKey?.lifecycleState) }}</a-descriptions-item>
        <a-descriptions-item label="创建时间">{{ formatTime(detailKey?.timeCreated) }}</a-descriptions-item>
        <a-descriptions-item label="公钥">
          <pre class="key-content">{{ detailKey?.keyValue || '—' }}</pre>
        </a-descriptions-item>
      </a-descriptions>
    </template>

    <template v-else>
      <a-button class="back-button" @click="backToList">返回 API Key 列表</a-button>
      <a-alert
        type="warning"
        show-icon
        message="请立即下载并妥善保存私钥"
        description="私钥只在本次新增结果中提供；返回列表或关闭弹窗后，无法再次查看或下载。"
      />
      <a-descriptions class="created-summary" :column="1" bordered size="small">
        <a-descriptions-item label="Fingerprint">{{ createdKey?.fingerprint || '—' }}</a-descriptions-item>
        <a-descriptions-item label="状态">{{ stateText(createdKey?.lifecycleState) }}</a-descriptions-item>
        <a-descriptions-item label="创建时间">{{ formatTime(createdKey?.timeCreated) }}</a-descriptions-item>
      </a-descriptions>
      <div class="download-actions">
        <a-button type="primary" block @click="downloadPrivateKey">下载私钥</a-button>
        <a-button block @click="downloadPublicKey">下载公钥</a-button>
        <a-button block @click="downloadConfig">下载 OCI 配置</a-button>
      </div>
    </template>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import {
  closeUserApiKeySession,
  createUserApiKey,
  deleteUserApiKey,
  listUserApiKeys,
} from '../../api/user'

type ApiKeyItem = {
  keyId?: string
  keyValue?: string
  fingerprint?: string
  userId?: string
  timeCreated?: string
  lifecycleState?: string
  inactiveStatus?: number
}

type CreatedApiKey = ApiKeyItem & {
  publicKeyPem?: string
  privateKeyPem?: string
  config?: string
}

const props = defineProps<{
  tenantId: string
  isMobile: boolean
}>()

const visible = ref(false)
const loading = ref(false)
const creating = ref(false)
const deletingFingerprint = ref('')
const sessionToken = ref('')
const currentUser = ref<any>(null)
const apiKeys = ref<ApiKeyItem[]>([])
const detailKey = ref<ApiKeyItem | null>(null)
const createdKey = ref<CreatedApiKey | null>(null)
const viewMode = ref<'list' | 'detail' | 'created'>('list')

const modalTitle = computed(() => {
  const name = currentUser.value?.name || currentUser.value?.id
  return name ? `API Key 管理 — ${name}` : 'API Key 管理'
})

const columns = [
  { title: 'Fingerprint', key: 'fingerprint', width: 330, ellipsis: true },
  { title: '状态', key: 'state', width: 100 },
  { title: '创建时间', key: 'timeCreated', width: 190 },
  { title: '操作', key: 'action', width: 140 },
]

function payload() {
  return {
    tenantId: props.tenantId,
    userId: String(currentUser.value?.id || ''),
    sessionToken: sessionToken.value,
  }
}

async function open(user: any, token: string) {
  currentUser.value = user
  sessionToken.value = token
  apiKeys.value = []
  detailKey.value = null
  clearCreatedKey()
  viewMode.value = 'list'
  visible.value = true
  await loadApiKeys()
}

async function loadApiKeys() {
  loading.value = true
  try {
    const res = await listUserApiKeys(payload())
    apiKeys.value = Array.isArray(res.data) ? res.data : []
  } catch (e: any) {
    message.error(e?.message || '加载 API Key 失败')
  } finally {
    loading.value = false
  }
}

function showDetails(item: ApiKeyItem) {
  detailKey.value = item
  viewMode.value = 'detail'
}

async function backToList() {
  const shouldReload = viewMode.value === 'created'
  detailKey.value = null
  clearCreatedKey()
  viewMode.value = 'list'
  if (shouldReload) {
    await loadApiKeys()
  }
}

async function handleCreate() {
  creating.value = true
  try {
    const res = await createUserApiKey(payload())
    createdKey.value = (res.data || null) as CreatedApiKey | null
    if (!createdKey.value?.privateKeyPem) {
      throw new Error('OCI 已新增 API Key，但未返回一次性私钥')
    }
    viewMode.value = 'created'
    message.success('API Key 已新增')
  } catch (e: any) {
    message.error(e?.message || '新增 API Key 失败')
  } finally {
    creating.value = false
  }
}

async function handleDelete(item: ApiKeyItem) {
  const fingerprint = String(item.fingerprint || '').trim()
  if (!fingerprint) {
    message.warning('该 API Key 缺少 Fingerprint，无法删除')
    return
  }
  deletingFingerprint.value = fingerprint
  try {
    await deleteUserApiKey({ ...payload(), fingerprint })
    apiKeys.value = apiKeys.value.filter((key) => key.fingerprint !== fingerprint)
    message.success('API Key 已删除')
  } catch (e: any) {
    message.error(e?.message || '删除 API Key 失败')
  } finally {
    deletingFingerprint.value = ''
  }
}

function stateText(state?: string) {
  const value = String(state || '').toUpperCase()
  if (value === 'ACTIVE') return '正常'
  if (value === 'INACTIVE') return '已禁用'
  if (value === 'DELETING') return '删除中'
  if (value === 'DELETED') return '已删除'
  return state || '—'
}

function stateColor(state?: string) {
  const value = String(state || '').toUpperCase()
  if (value === 'ACTIVE') return 'green'
  if (value === 'INACTIVE' || value === 'DELETING' || value === 'DELETED') return 'red'
  return 'default'
}

function formatTime(value?: string) {
  if (!value) return '—'
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value
}

function downloadText(filename: string, content?: string) {
  if (!content) {
    message.warning('没有可下载的内容')
    return
  }
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

function filenamePrefix() {
  const raw = String(currentUser.value?.name || 'user').trim()
  return raw.replace(/[^a-zA-Z0-9_.-]+/g, '_') || 'user'
}

function downloadPrivateKey() {
  downloadText(privateKeyFilename(), createdKey.value?.privateKeyPem)
}

function downloadPublicKey() {
  downloadText(`${filenamePrefix()}_oci_api_key_public.pem`, createdKey.value?.publicKeyPem)
}

function downloadConfig() {
  const config = createdKey.value?.config?.replace(
    /^key_file=.*$/m,
    `key_file=./${privateKeyFilename()}`,
  )
  downloadText(`${filenamePrefix()}_oci_config`, config)
}

function privateKeyFilename() {
  return `${filenamePrefix()}_oci_api_key.pem`
}

function clearCreatedKey() {
  createdKey.value = null
}

function clearState() {
  apiKeys.value = []
  detailKey.value = null
  clearCreatedKey()
  viewMode.value = 'list'
  currentUser.value = null
}

function closeSessionQuietly(token: string) {
  if (!token) return
  void closeUserApiKeySession({ sessionToken: token }).catch(() => undefined)
}

function handleClose() {
  const token = sessionToken.value
  sessionToken.value = ''
  visible.value = false
  clearState()
  closeSessionQuietly(token)
}

onBeforeUnmount(() => {
  const token = sessionToken.value
  sessionToken.value = ''
  clearCreatedKey()
  closeSessionQuietly(token)
})

defineExpose({ open })
</script>

<style scoped>
.api-key-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.api-key-user {
  min-width: 0;
  color: var(--text-sub);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fingerprint {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.danger-link.ant-btn-link {
  color: var(--danger) !important;
}

.api-key-mobile-list {
  display: grid;
  gap: 12px;
}

.api-key-card {
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-card);
}

.api-key-card-fingerprint {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-main);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-weight: 600;
}

.api-key-card-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  color: var(--text-sub);
}

.api-key-card-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

.back-button {
  margin-bottom: 16px;
}

.key-content {
  max-height: 320px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--input-bg);
  color: var(--text-main);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
}

.created-summary {
  margin-top: 16px;
}

.download-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 16px;
}

@media (max-width: 767px) {
  .api-key-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .api-key-toolbar .ant-btn {
    width: 100%;
  }

  .download-actions {
    grid-template-columns: 1fr;
  }
}
</style>
