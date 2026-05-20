<template>
  <div class="compartment-manager">
    <div class="compartment-nav-bar">
      <a-button
        v-if="canGoUp"
        type="default"
        size="small"
        class="compartment-back-btn"
        @click="goUpOneLevel"
      >
        <template #icon><ArrowLeftOutlined /></template>
        返回上一级
      </a-button>
      <a-breadcrumb class="compartment-breadcrumb">
        <a-breadcrumb-item v-for="(b, i) in breadcrumb" :key="b.id">
          <a v-if="i < breadcrumb.length - 1" class="compartment-crumb-link" @click="navigateTo(b.id)">{{ b.name }}</a>
          <span v-else class="compartment-crumb-current">{{ b.name }}</span>
        </a-breadcrumb-item>
      </a-breadcrumb>
    </div>

    <a-space wrap style="margin-bottom: 12px">
      <a-button type="primary" @click="openCreateModal">
        <template #icon><PlusOutlined /></template>创建区间
      </a-button>
      <a-button @click="reloadList" :loading="loading">
        <template #icon><ReloadOutlined /></template>刷新
      </a-button>
      <a-input-search
        v-model:value="keyword"
        placeholder="搜索名称 / OCID"
        allow-clear
        style="width: 240px"
        @search="reloadList"
      />
    </a-space>

    <a-table
      :data-source="tableItems"
      :loading="loading"
      size="small"
      row-key="id"
      :pagination="{ pageSize: 15, showSizeChanger: true }"
    >
      <a-table-column title="名称" key="name" :width="200">
        <template #default="{ record }">
          <a @click="enterCompartment(record)">{{ record.name }}</a>
        </template>
      </a-table-column>
      <a-table-column title="状态" data-index="lifecycleState" key="state" :width="100">
        <template #default="{ text }">
          <a-tag :color="compartmentStateColor(text)">{{ formatCompartmentState(text) }}</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="OCID" key="ocid" :width="140">
        <template #default="{ record }">
          <a-typography-text copyable :content="record.id" style="font-size: 11px">
            {{ shortOcId(record.id) }}
          </a-typography-text>
        </template>
      </a-table-column>
      <a-table-column title="子区间" data-index="childCount" key="childCount" :width="72" />
      <a-table-column title="创建时间" key="timeCreated" :width="168">
        <template #default="{ record }">{{ formatUtcCnDate(record.timeCreated) }}</template>
      </a-table-column>
      <a-table-column title="操作" key="action" :width="200" fixed="right">
        <template #default="{ record }">
          <a-space size="small" wrap>
            <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
            <template v-if="!record.root">
              <a-button type="link" size="small" @click="openRename(record)">重命名</a-button>
              <a-button type="link" size="small" danger @click="openDelete(record)">删除</a-button>
            </template>
          </a-space>
        </template>
      </a-table-column>
    </a-table>

    <!-- 创建 -->
    <a-modal v-model:open="createVisible" title="创建区间" @ok="submitCreate" :confirm-loading="createLoading">
      <a-form layout="vertical">
        <a-form-item label="父区间">
          <a-input :value="createParentLabel" disabled />
        </a-form-item>
        <a-form-item label="名称" required>
          <a-input v-model:value="createForm.name" placeholder="同父级下唯一，最多 100 字符" />
        </a-form-item>
        <a-form-item label="描述">
          <a-textarea v-model:value="createForm.description" :rows="3" placeholder="1–400 字符" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 重命名 -->
    <a-modal v-model:open="renameVisible" title="重命名区间" @ok="submitRename" :confirm-loading="renameLoading" ok-text="保存">
      <a-alert type="warning" message="重命名区间需 Telegram 验证码（有效期 5 分钟）" show-icon style="margin-bottom: 12px" />
      <a-form layout="vertical">
        <a-form-item label="Telegram 验证码" required>
          <a-input v-model:value="renameCode" placeholder="请输入 6 位验证码" :maxlength="6" allow-clear />
          <a-button type="link" size="small" :loading="renameCodeSending" style="padding-left: 0; margin-top: 4px"
            @click="resendRenameCode">重新发送验证码</a-button>
        </a-form-item>
        <a-form-item label="名称" required>
          <a-input v-model:value="renameForm.name" />
        </a-form-item>
        <a-form-item label="描述">
          <a-textarea v-model:value="renameForm.description" :rows="3" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 详情 -->
    <a-drawer
      v-model:open="detailVisible"
      :title="'区间 — ' + (detailData?.name || '')"
      width="720"
      :destroy-on-close="true"
      :mask-closable="false"
      :keyboard="false"
    >
      <a-spin :spinning="detailLoading">
        <template v-if="detailData">
          <a-descriptions :column="1" bordered size="small" style="margin-bottom: 16px">
            <a-descriptions-item label="OCID">
              <a-typography-text copyable :content="detailData.id">{{ detailData.id }}</a-typography-text>
            </a-descriptions-item>
            <a-descriptions-item label="状态">
              <a-tag :color="compartmentStateColor(detailData.lifecycleState)">
                {{ formatCompartmentState(detailData.lifecycleState) }}
              </a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="描述">{{ detailData.description || '—' }}</a-descriptions-item>
            <a-descriptions-item label="子区间数">{{ detailData.childCount ?? 0 }}</a-descriptions-item>
            <a-descriptions-item label="创建时间">{{ formatUtcCnDate(detailData.timeCreated) }}</a-descriptions-item>
          </a-descriptions>

          <a-tabs v-model:activeKey="detailTab">
            <a-tab-pane key="children" tab="子区间">
              <a-button type="primary" size="small" style="margin-bottom: 8px" @click="enterCompartment(detailData)">
                进入此层级子区间列表
              </a-button>
              <a-table :data-source="detailData.children || []" size="small" row-key="id" :pagination="false">
                <a-table-column title="名称" key="name">
                  <template #default="{ record }">
                    <a @click="enterCompartment(record)">{{ record.name }}</a>
                  </template>
                </a-table-column>
                <a-table-column title="状态" data-index="lifecycleState" key="state" :width="90">
                  <template #default="{ text }">
                    <a-tag :color="compartmentStateColor(text)">{{ formatCompartmentState(text) }}</a-tag>
                  </template>
                </a-table-column>
                <a-table-column title="子区间" data-index="childCount" key="cc" :width="64" />
              </a-table>
              <a-empty v-if="!(detailData.children || []).length" description="无直接子区间" />
            </a-tab-pane>
            <a-tab-pane key="resources" tab="编辑资源">
              <a-space style="margin-bottom: 8px" wrap>
                <a-button size="small" type="primary" :loading="resourcesLoading" @click="loadResources(true)">
                  加载资源
                </a-button>
                <span style="font-size: 12px; color: var(--text-sub)">
                  可迁移：Instance / Volume / BootVolume；其余类型请用 OCI 控制台
                </span>
              </a-space>
              <a-table :data-source="resources" :loading="resourcesLoading" size="small" row-key="identifier"
                :pagination="false">
                <a-table-column title="名称" data-index="displayName" key="dn" :ellipsis="true" />
                <a-table-column title="类型" data-index="resourceType" key="rt" :width="100" />
                <a-table-column title="状态" data-index="lifecycleState" key="st" :width="88" />
                <a-table-column title="操作" key="act" :width="100">
                  <template #default="{ record }">
                    <a-button v-if="record.moveable" type="link" size="small" @click="openMoveResource(record)">
                      迁移
                    </a-button>
                    <span v-else style="font-size: 11px; color: var(--text-sub)">—</span>
                  </template>
                </a-table-column>
              </a-table>
              <a-button v-if="resourcesNextPage" type="link" size="small" style="margin-top: 8px"
                :loading="resourcesLoading" @click="loadResources(false)">加载更多</a-button>
            </a-tab-pane>
          </a-tabs>
        </template>
      </a-spin>
    </a-drawer>

    <!-- 迁移资源 -->
    <a-modal
      v-model:open="deleteVisible"
      :title="'删除区间 — ' + (deleteTarget?.name || '')"
      @ok="submitDelete"
      :confirm-loading="deleteLoading"
      ok-text="确认删除"
      :ok-button-props="{ danger: true }"
    >
      <a-alert type="warning" message="删除须为空区间；需 Telegram 验证码（有效期 5 分钟）" show-icon style="margin-bottom: 12px" />
      <a-input v-model:value="deleteCode" placeholder="请输入 6 位验证码" :maxlength="6" allow-clear />
      <div style="margin-top: 8px">
        <a-button type="link" size="small" :loading="deleteCodeSending" @click="resendDeleteCode">重新发送验证码</a-button>
      </div>
    </a-modal>

    <a-modal v-model:open="moveResVisible" title="迁移资源到其他区间" @ok="submitMoveResource"
      :confirm-loading="moveResLoading" ok-text="确认迁移">
      <a-alert type="warning" message="迁移资源需 Telegram 验证码（有效期 5 分钟）" show-icon style="margin-bottom: 12px" />
      <a-form layout="vertical">
        <a-form-item label="资源">
          <a-input :value="moveResForm.label" disabled />
        </a-form-item>
        <a-form-item label="Telegram 验证码" required>
          <a-input v-model:value="moveResCode" placeholder="请输入 6 位验证码" :maxlength="6" allow-clear />
          <a-button type="link" size="small" :loading="moveResCodeSending" style="padding-left: 0; margin-top: 4px"
            @click="resendMoveResCode">重新发送验证码</a-button>
        </a-form-item>
        <a-form-item label="目标区间" required>
          <a-select
            v-model:value="moveResForm.targetCompartmentId"
            show-search
            allow-clear
            placeholder="搜索并选择目标区间"
            :loading="moveTargetLoading"
            :options="moveTargetOptions"
            option-filter-prop="label"
            style="width: 100%"
          />
          <div v-if="moveResForm.targetCompartmentId" style="margin-top: 6px; font-size: 11px; color: var(--text-sub); word-break: break-all">
            OCID：{{ moveResForm.targetCompartmentId }}
          </div>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined, ArrowLeftOutlined } from '@ant-design/icons-vue'
import {
  listCompartments,
  listCompartmentPicker,
  getCompartmentDetail,
  createCompartment,
  updateCompartment,
  deleteCompartment,
  listCompartmentResources,
  moveCompartmentResource,
} from '../api/compartment'
import { sendVerifyCode } from '../api/system'

const props = defineProps<{
  tenantId: string
}>()

const loading = ref(false)
const keyword = ref('')
const tableItems = ref<any[]>([])
const tenancyId = ref('')
const breadcrumb = ref<{ id: string; name: string }[]>([])
const browseParentId = ref('')

const canGoUp = computed(() => breadcrumb.value.length > 1)

const createVisible = ref(false)
const createLoading = ref(false)
const createForm = ref({ name: '', description: '' })

const renameVisible = ref(false)
const renameLoading = ref(false)
const renameCode = ref('')
const renameCodeSending = ref(false)
const renameForm = ref({ compartmentId: '', name: '', description: '' })

const deleteVisible = ref(false)
const deleteTarget = ref<any>(null)
const deleteCode = ref('')
const deleteLoading = ref(false)
const deleteCodeSending = ref(false)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailData = ref<any>(null)
const detailTab = ref('children')

const resources = ref<any[]>([])
const resourcesLoading = ref(false)
const resourcesNextPage = ref<string | null>(null)

const moveResVisible = ref(false)
const moveResLoading = ref(false)
const moveResCode = ref('')
const moveResCodeSending = ref(false)
const moveTargetLoading = ref(false)
const moveTargetOptions = ref<{ label: string; value: string }[]>([])
const moveResForm = ref({ resourceId: '', resourceType: '', targetCompartmentId: undefined as string | undefined, label: '' })

const createParentLabel = computed(() => {
  const last = breadcrumb.value[breadcrumb.value.length - 1]
  return last ? `${last.name} (${shortOcId(last.id)})` : '—'
})

function shortOcId(id: string | null | undefined) {
  if (!id) return '—'
  if (id.length <= 24) return id
  return id.slice(0, 14) + '…' + id.slice(-8)
}

function formatUtcCnDate(v: unknown) {
  if (v == null || v === '') return '—'
  const d = new Date(v as string | number)
  if (Number.isNaN(d.getTime())) return '—'
  const y = d.getUTCFullYear()
  const m = String(d.getUTCMonth() + 1).padStart(2, '0')
  const day = String(d.getUTCDate()).padStart(2, '0')
  const h = String(d.getUTCHours()).padStart(2, '0')
  const min = String(d.getUTCMinutes()).padStart(2, '0')
  return `${y}年${m}月${day}日 UTC ${h}:${min}`
}

function formatCompartmentState(s: string | null | undefined) {
  if (!s) return '—'
  const u = s.toUpperCase()
  if (u === 'ACTIVE') return '活动'
  if (u === 'DELETING') return '正在删除'
  if (u === 'DELETED') return '已删除'
  if (u === 'CREATING') return '创建中'
  return s
}

function compartmentStateColor(s: string | null | undefined) {
  const u = (s || '').toUpperCase()
  if (u === 'ACTIVE') return 'success'
  if (u === 'DELETING') return 'warning'
  if (u === 'DELETED') return 'default'
  return 'processing'
}

async function reloadList() {
  if (!props.tenantId) return
  loading.value = true
  try {
    const parentId = browseParentId.value || undefined
    const res = await listCompartments({
      id: props.tenantId,
      parentId,
      keyword: keyword.value.trim() || undefined,
    })
    const data = res.data || {}
    tenancyId.value = data.tenancyId || ''
    breadcrumb.value = data.breadcrumb || []
    tableItems.value = data.items || []
  } catch (e: any) {
    message.error(e?.message || '加载区间失败')
  } finally {
    loading.value = false
  }
}

function navigateTo(id: string) {
  browseParentId.value = id === tenancyId.value ? '' : id
  reloadList()
}

/** 返回面包屑的上一级（如 IntegrationsCompartment → root） */
function goUpOneLevel() {
  const bc = breadcrumb.value
  if (bc.length < 2) return
  navigateTo(bc[bc.length - 2].id)
}

/** 点名称：进入该层子区间列表（与 OCI 控制台一致），不关列表只换层级 */
function enterCompartment(record: any) {
  if (!record?.id) return
  detailVisible.value = false
  browseParentId.value = record.id === tenancyId.value ? '' : record.id
  reloadList()
}

function openCreateModal() {
  const pid = breadcrumb.value.length
    ? breadcrumb.value[breadcrumb.value.length - 1].id
    : tenancyId.value
  if (!pid) {
    message.warning('请先加载区间列表')
    return
  }
  createForm.value = { name: '', description: '' }
  createVisible.value = true
}

async function submitCreate() {
  const parentId = breadcrumb.value.length
    ? breadcrumb.value[breadcrumb.value.length - 1].id
    : tenancyId.value
  if (!createForm.value.name.trim()) {
    message.warning('请输入名称')
    return
  }
  createLoading.value = true
  try {
    await createCompartment({
      id: props.tenantId,
      parentId,
      name: createForm.value.name.trim(),
      description: createForm.value.description?.trim(),
    })
    message.success('区间已创建')
    createVisible.value = false
    await reloadList()
  } catch (e: any) {
    message.error(e?.message || '创建失败')
  } finally {
    createLoading.value = false
  }
}

async function openDetail(record: any) {
  detailVisible.value = true
  detailTab.value = 'children'
  resources.value = []
  resourcesNextPage.value = null
  detailLoading.value = true
  try {
    const res = await getCompartmentDetail({ id: props.tenantId, compartmentId: record.id })
    detailData.value = res.data || null
  } catch (e: any) {
    message.error(e?.message || '加载详情失败')
  } finally {
    detailLoading.value = false
  }
}

async function openRename(record: any) {
  renameForm.value = {
    compartmentId: record.id,
    name: record.name?.replace(/ \(root\)$/, '') || record.name,
    description: record.description || '',
  }
  renameCode.value = ''
  try {
    await sendVerifyCode('updateCompartment')
    message.success('验证码已发送至 Telegram')
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
    return
  }
  renameVisible.value = true
}

async function resendRenameCode() {
  renameCodeSending.value = true
  try {
    await sendVerifyCode('updateCompartment')
    message.success('验证码已重新发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    renameCodeSending.value = false
  }
}

async function submitRename() {
  if (!renameCode.value || renameCode.value.length !== 6) {
    message.warning('请输入 6 位验证码')
    return
  }
  if (!renameForm.value.name.trim()) {
    message.warning('名称不能为空')
    return
  }
  renameLoading.value = true
  try {
    await updateCompartment({
      id: props.tenantId,
      compartmentId: renameForm.value.compartmentId,
      name: renameForm.value.name.trim(),
      description: renameForm.value.description,
      verifyCode: renameCode.value,
    })
    message.success('已更新')
    renameVisible.value = false
    await reloadList()
    if (detailVisible.value && detailData.value?.id === renameForm.value.compartmentId) {
      await openDetail({ id: renameForm.value.compartmentId })
    }
  } catch (e: any) {
    message.error(e?.message || '更新失败')
  } finally {
    renameLoading.value = false
  }
}

async function openDelete(record: any) {
  deleteTarget.value = record
  deleteCode.value = ''
  try {
    await sendVerifyCode('deleteCompartment')
    message.success('验证码已发送至 Telegram')
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
    return
  }
  deleteVisible.value = true
}

async function resendDeleteCode() {
  deleteCodeSending.value = true
  try {
    await sendVerifyCode('deleteCompartment')
    message.success('验证码已重新发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    deleteCodeSending.value = false
  }
}

async function submitDelete() {
  if (!deleteCode.value || deleteCode.value.length !== 6) {
    message.warning('请输入 6 位验证码')
    return
  }
  if (!deleteTarget.value?.id) return
  deleteLoading.value = true
  try {
    await deleteCompartment({
      id: props.tenantId,
      compartmentId: deleteTarget.value.id,
      verifyCode: deleteCode.value,
    })
    message.success('已提交删除（异步，空区间方可完成）')
    deleteVisible.value = false
    if (detailVisible.value && detailData.value?.id === deleteTarget.value.id) {
      detailVisible.value = false
    }
    await reloadList()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  } finally {
    deleteLoading.value = false
  }
}

async function loadResources(reset: boolean) {
  if (!detailData.value?.id) return
  resourcesLoading.value = true
  try {
    const res = await listCompartmentResources({
      id: props.tenantId,
      compartmentId: detailData.value.id,
      pageToken: reset ? undefined : resourcesNextPage.value || undefined,
      limit: 50,
    })
    const data = res.data || {}
    const items = data.items || []
    if (reset) resources.value = items
    else resources.value = [...resources.value, ...items]
    resourcesNextPage.value = data.opcNextPage || null
  } catch (e: any) {
    message.error(e?.message || '加载资源失败')
  } finally {
    resourcesLoading.value = false
  }
}

async function loadMoveTargetOptions(excludeCompartmentId?: string) {
  if (!props.tenantId) return
  moveTargetLoading.value = true
  try {
    const res = await listCompartmentPicker({ id: props.tenantId })
    const items = (res.data?.items || []) as { id: string; pathLabel?: string; name?: string }[]
    const exclude = excludeCompartmentId?.trim()
    moveTargetOptions.value = items
      .filter((c) => !exclude || c.id !== exclude)
      .map((c) => ({
        label: c.pathLabel || c.name || c.id,
        value: c.id,
      }))
  } catch (e: any) {
    moveTargetOptions.value = []
    message.error(e?.message || '加载区间列表失败')
  } finally {
    moveTargetLoading.value = false
  }
}

async function openMoveResource(record: any) {
  moveResForm.value = {
    resourceId: record.identifier,
    resourceType: record.resourceType,
    targetCompartmentId: undefined,
    label: `${record.displayName || record.identifier} (${record.resourceType})`,
  }
  moveResCode.value = ''
  try {
    await sendVerifyCode('moveCompartmentResource')
    message.success('验证码已发送至 Telegram')
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
    return
  }
  moveResVisible.value = true
  await loadMoveTargetOptions(detailData.value?.id)
}

async function resendMoveResCode() {
  moveResCodeSending.value = true
  try {
    await sendVerifyCode('moveCompartmentResource')
    message.success('验证码已重新发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    moveResCodeSending.value = false
  }
}

async function submitMoveResource() {
  if (!moveResCode.value || moveResCode.value.length !== 6) {
    message.warning('请输入 6 位验证码')
    return
  }
  const targetId = (moveResForm.value.targetCompartmentId || '').trim()
  if (!targetId) {
    message.warning('请选择目标区间')
    return
  }
  moveResLoading.value = true
  try {
    await moveCompartmentResource({
      id: props.tenantId,
      resourceId: moveResForm.value.resourceId,
      resourceType: moveResForm.value.resourceType,
      targetCompartmentId: targetId,
      verifyCode: moveResCode.value,
    })
    message.success('资源已迁移')
    moveResVisible.value = false
    await loadResources(true)
  } catch (e: any) {
    message.error(e?.message || '迁移失败')
  } finally {
    moveResLoading.value = false
  }
}

watch(detailTab, (key) => {
  if (key === 'resources' && detailVisible.value && detailData.value?.id) {
    void loadResources(true)
  }
})

watch(
  () => props.tenantId,
  (id) => {
    if (id) {
      browseParentId.value = ''
      keyword.value = ''
      reloadList()
    }
  },
  { immediate: true },
)
</script>

<style scoped>
.compartment-manager {
  min-height: 200px;
}

.compartment-nav-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 12px;
}

.compartment-back-btn {
  flex-shrink: 0;
}

.compartment-breadcrumb {
  flex: 1;
  min-width: 0;
  margin-bottom: 0 !important;
}

.compartment-crumb-link {
  color: #1677ff;
  cursor: pointer;
}

.compartment-crumb-link:hover {
  color: #4096ff;
  text-decoration: underline;
}

.compartment-crumb-current {
  color: var(--text-main, rgba(255, 255, 255, 0.88));
  font-weight: 500;
}
</style>
