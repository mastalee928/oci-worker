<template>
  <a-drawer
    :open="open"
    @update:open="(v: boolean) => emit('update:open', v)"
    :title="`卷组 — ${tenantName}`"
    width="960"
    destroy-on-close
  >
    <a-alert type="info" show-icon style="margin-bottom: 12px"
      message="此处展示当前租户下所有区域、所有隔间的卷资源。即使租户下无实例，也可以管理孤儿卷与备份。" />

    <div style="margin-bottom: 12px; display: flex; gap: 8px; align-items: center">
      <a-button type="primary" @click="load" :loading="loading">
        <i class="ri-refresh-line" style="margin-right: 4px"></i>刷新
      </a-button>
      <a-input-search
        v-model:value="keyword"
        placeholder="按名称搜索"
        style="width: 260px"
        allow-clear
      />
      <span style="color: var(--text-sub); font-size: 12px">
        共 {{ allVolumes.length }} 项
        （引导卷 {{ countByType('BOOT') }}，块存储 {{ countByType('BLOCK') }}，引导备份 {{ countByType('BOOT_BACKUP') }}，块备份 {{ countByType('BLOCK_BACKUP') }}）
      </span>
    </div>

    <a-tabs v-model:activeKey="subTab" size="small">
      <a-tab-pane key="BOOT" :tab="`引导卷 (${countByType('BOOT')})`">
        <a-table :data-source="filtered('BOOT')" :columns="cols" size="small" :pagination="{ pageSize: 20 }" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'delAction'">
              <a-button type="link" danger size="small" @click="openDelete(record)">删除</a-button>
            </template>
            <template v-else-if="column.key === 'size'">
              {{ record.sizeInGBs }} GB
            </template>
            <template v-else-if="column.key === 'timeCreated'">
              {{ formatTime(record.timeCreated) }}
            </template>
            <template v-else-if="column.key === 'lifecycleState'">
              <a-badge :status="record.lifecycleState === 'AVAILABLE' ? 'success' : 'default'" :text="record.lifecycleState" />
            </template>
          </template>
        </a-table>
      </a-tab-pane>
      <a-tab-pane key="BLOCK" :tab="`块存储卷 (${countByType('BLOCK')})`">
        <a-table :data-source="filtered('BLOCK')" :columns="cols" size="small" :pagination="{ pageSize: 20 }" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'delAction'">
              <a-button type="link" danger size="small" @click="openDelete(record)">删除</a-button>
            </template>
            <template v-else-if="column.key === 'size'">{{ record.sizeInGBs }} GB</template>
            <template v-else-if="column.key === 'timeCreated'">{{ formatTime(record.timeCreated) }}</template>
            <template v-else-if="column.key === 'lifecycleState'">
              <a-badge :status="record.lifecycleState === 'AVAILABLE' ? 'success' : 'default'" :text="record.lifecycleState" />
            </template>
          </template>
        </a-table>
      </a-tab-pane>
      <a-tab-pane key="BOOT_BACKUP" :tab="`引导卷备份 (${countByType('BOOT_BACKUP')})`">
        <a-table :data-source="filtered('BOOT_BACKUP')" :columns="backupCols" size="small" :pagination="{ pageSize: 20 }" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'delAction'">
              <a-button type="link" danger size="small" @click="openDelete(record)">删除</a-button>
            </template>
            <template v-else-if="column.key === 'size'">{{ record.sizeInGBs || record.uniqueSizeInGBs || '-' }} GB</template>
            <template v-else-if="column.key === 'timeCreated'">{{ formatTime(record.timeCreated) }}</template>
            <template v-else-if="column.key === 'lifecycleState'">
              <a-badge :status="record.lifecycleState === 'AVAILABLE' ? 'success' : 'default'" :text="record.lifecycleState" />
            </template>
          </template>
        </a-table>
      </a-tab-pane>
      <a-tab-pane key="BLOCK_BACKUP" :tab="`块存储备份 (${countByType('BLOCK_BACKUP')})`">
        <a-table :data-source="filtered('BLOCK_BACKUP')" :columns="backupCols" size="small" :pagination="{ pageSize: 20 }" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'delAction'">
              <a-button type="link" danger size="small" @click="openDelete(record)">删除</a-button>
            </template>
            <template v-else-if="column.key === 'size'">{{ record.sizeInGBs || record.uniqueSizeInGBs || '-' }} GB</template>
            <template v-else-if="column.key === 'timeCreated'">{{ formatTime(record.timeCreated) }}</template>
            <template v-else-if="column.key === 'lifecycleState'">
              <a-badge :status="record.lifecycleState === 'AVAILABLE' ? 'success' : 'default'" :text="record.lifecycleState" />
            </template>
          </template>
        </a-table>
      </a-tab-pane>
    </a-tabs>

    <a-modal v-model:open="deleteVisible" :title="'删除卷 — ' + (deleteTarget?.displayName || '')" @ok="doDelete" :confirm-loading="deleting"
      ok-text="确认删除" :ok-button-props="{ danger: true }">
      <a-alert type="warning" message="此操作不可恢复，请谨慎操作。需 Telegram 验证码。" show-icon style="margin-bottom: 12px" />
      <div style="margin-bottom: 8px">
        <span style="color: var(--text-sub)">名称：</span>{{ deleteTarget?.displayName }}
      </div>
      <div style="margin-bottom: 8px">
        <span style="color: var(--text-sub)">类型：</span>{{ typeLabel(deleteTarget?.type) }}
      </div>
      <div style="margin-bottom: 12px">
        <span style="color: var(--text-sub)">区域：</span>{{ deleteTarget?.region || '-' }}
      </div>
      <a-input v-model:value="deleteCode" placeholder="6 位 Telegram 验证码" maxlength="6" />
    </a-modal>
  </a-drawer>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import { listAllVolumes, deleteVolume } from '../api/volume'
import { sendVerifyCode } from '../api/system'

const props = defineProps<{ open: boolean; userId: string; tenantName?: string }>()
const emit = defineEmits<{ (e: 'update:open', v: boolean): void }>()

const loading = ref(false)
const allVolumes = ref<any[]>([])
const subTab = ref('BOOT')
const keyword = ref('')

const cols = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName', ellipsis: true },
  { title: '大小', key: 'size', width: 90 },
  { title: '区域', dataIndex: 'region', key: 'region', width: 130, ellipsis: true },
  { title: '隔间', dataIndex: 'compartmentName', key: 'compartmentName', width: 120, ellipsis: true },
  { title: '状态', key: 'lifecycleState', width: 110 },
  { title: '创建时间', key: 'timeCreated', width: 160 },
  { title: '操作', key: 'delAction', width: 70 },
]
const backupCols = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName', ellipsis: true },
  { title: '大小', key: 'size', width: 90 },
  { title: '来源', dataIndex: 'sourceType', key: 'sourceType', width: 100 },
  { title: '区域', dataIndex: 'region', key: 'region', width: 130, ellipsis: true },
  { title: '状态', key: 'lifecycleState', width: 110 },
  { title: '创建时间', key: 'timeCreated', width: 160 },
  { title: '操作', key: 'delAction', width: 70 },
]

watch(() => props.open, (v) => {
  if (v && props.userId) load()
})

function countByType(type: string) {
  return allVolumes.value.filter(v => v.type === type).length
}
function filtered(type: string) {
  const k = keyword.value.trim().toLowerCase()
  return allVolumes.value.filter(v => v.type === type && (!k || (v.displayName || '').toLowerCase().includes(k)))
}
function typeLabel(t?: string) {
  return ({ BOOT: '引导卷', BLOCK: '块存储卷', BOOT_BACKUP: '引导卷备份', BLOCK_BACKUP: '块存储备份' } as any)[t || ''] || t || ''
}
function formatTime(t?: string) {
  if (!t) return '-'
  return t.replace('T', ' ').substring(0, 19)
}

async function load() {
  loading.value = true
  try {
    const r = await listAllVolumes({ id: props.userId })
    allVolumes.value = r.data || []
  } catch (e: any) { message.error(e?.message || '加载卷列表失败') }
  finally { loading.value = false }
}

const deleteVisible = ref(false)
const deleteTarget = ref<any>(null)
const deleteCode = ref('')
const deleting = ref(false)

async function openDelete(row: any) {
  deleteTarget.value = row
  deleteCode.value = ''
  try {
    await sendVerifyCode('deleteVolume')
    message.success('验证码已发送至 Telegram')
  } catch (e: any) { message.error(e?.message || '发送验证码失败'); return }
  deleteVisible.value = true
}

const filteredKey = computed(() => subTab.value)
void filteredKey

async function doDelete() {
  if (!deleteCode.value || deleteCode.value.length !== 6) return message.warning('请输入 6 位验证码')
  deleting.value = true
  try {
    await deleteVolume({
      id: props.userId,
      type: deleteTarget.value.type,
      volumeId: deleteTarget.value.id,
      verifyCode: deleteCode.value,
    })
    message.success('卷已删除')
    deleteVisible.value = false
    load()
  } catch (e: any) { message.error(e?.message || '删除失败') }
  finally { deleting.value = false }
}
</script>
