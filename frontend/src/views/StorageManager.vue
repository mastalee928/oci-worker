<template>
  <a-drawer
    :open="open"
    @update:open="(v: boolean) => emit('update:open', v)"
    :title="`存储 — ${tenantName || ''}`"
    width="1280"
    destroy-on-close
  >
    <div style="margin-bottom: 12px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
      <span style="color: var(--text-sub); font-size: 12px">Region</span>
      <a-select
        v-model:value="region"
        style="width: 240px"
        placeholder="选择 Region"
        :options="regionOptions"
        :loading="regionLoading"
        show-search
        option-filter-prop="label"
      />
      <span style="color: var(--text-sub); font-size: 12px">隔间</span>
      <a-select
        v-model:value="compartmentId"
        style="width: 260px"
        placeholder="全部隔间"
        allow-clear
        show-search
        option-filter-prop="label"
        :options="compartmentOptions"
        :loading="compartmentLoading"
      />
      <a-button type="primary" @click="loadAll" :loading="loading" :disabled="!region">
        <i class="ri-refresh-line" style="margin-right: 4px"></i>刷新
      </a-button>
    </div>

    <a-tabs v-model:activeKey="mainTab" size="small" @change="onMainTab">
      <a-tab-pane key="block" tab="块存储">
        <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 10px">
          <a-select v-model:value="blockView" style="width: 280px" :options="blockViewOptions" />
          <a-button v-if="blockView === 'volumeGroups'" size="small" type="primary" @click="openCreateVg" :disabled="!region">
            新建卷组
          </a-button>
          <a-button v-if="blockView === 'volumeBackupPolicies'" size="small" type="primary" @click="openCreatePolicy" :disabled="!region">
            新建备份策略
          </a-button>
          <a-button
            v-if="blockView === 'volumeBackupPolicyAssignments'"
            size="small"
            type="primary"
            @click="openCreateAssignment"
            :disabled="!region"
          >
            新建策略绑定
          </a-button>
        </div>
        <a-table
          :data-source="currentBlockRows"
          :columns="blockColumnsResolved"
          size="small"
          :pagination="{ pageSize: 12 }"
          row-key="rowKey"
          :scroll="{ x: 'max-content' }"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'attachmentSummary'">
              {{ record.attachmentSummary || '—' }}
            </template>
            <template v-else-if="column.key === 'spec'">
              <span style="font-size: 12px">{{ specCell(record) }}</span>
            </template>
            <template v-else-if="column.key === 'policyAsset'">
              <span style="font-size: 12px">{{ shortId(record.policyId) }} / {{ shortId(record.assetId) }}</span>
            </template>
            <template v-else-if="column.key === 'actions'">
              <a-space size="small" wrap>
                <a-button v-if="canRenameBlock" type="link" size="small" @click="openRename(record)">改名</a-button>
                <a-button v-if="canResizeBoot" type="link" size="small" @click="openResizeBoot(record)">扩容</a-button>
                <a-button v-if="canResizeBlock" type="link" size="small" @click="openResizeBlock(record)">扩容</a-button>
                <a-button v-if="canEnableBootReplication" type="link" size="small" @click="openEnableBootReplication(record)">启用复制</a-button>
                <a-button v-if="canEnableBlockReplication" type="link" size="small" @click="openEnableBlockReplication(record)">启用复制</a-button>
                <a-button v-if="canActivateBootReplica" type="link" size="small" @click="openActivateBootReplica(record)">激活为引导卷</a-button>
                <a-button v-if="canActivateBlockReplica" type="link" size="small" @click="openActivateBlockReplica(record)">激活为块卷</a-button>
                <a-button v-if="canEditBackupPolicy" type="link" size="small" @click="openEditPolicy(record)">编辑策略</a-button>
                <a-button v-if="canDeleteBlock" type="link" danger size="small" @click="openDelete(record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
      <a-tab-pane key="object" tab="对象存储与归档">
        <a-tabs v-model:activeKey="objectSub" size="small">
          <a-tab-pane key="buckets" tab="存储桶">
            <div style="margin-bottom: 8px">
              <a-button type="primary" size="small" @click="openCreateBucket" :disabled="!region || !objectData.namespace">新建桶</a-button>
            </div>
            <a-table :data-source="buckets" :columns="bucketColumns" size="small" row-key="rowKey" :pagination="{ pageSize: 12 }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'actions'">
                  <a-space size="small" wrap>
                    <a-button type="link" size="small" @click="openEditBucket(record)">编辑桶</a-button>
                    <a-button type="link" size="small" @click="openBucketPolicy(record)">桶策略</a-button>
                    <a-button type="link" danger size="small" @click="openDelete(record)">删除</a-button>
                  </a-space>
                </template>
              </template>
            </a-table>
          </a-tab-pane>
          <a-tab-pane key="pe" tab="专用端点">
            <div style="margin-bottom: 8px">
              <a-button type="primary" size="small" @click="openCreatePe" :disabled="!region || !objectData.namespace">新建专用端点</a-button>
            </div>
            <a-table :data-source="privateEndpoints" :columns="peColumns" size="small" row-key="id" :pagination="{ pageSize: 12 }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'actions'">
                  <a-button type="link" danger size="small" @click="openDelete(record)">删除</a-button>
                </template>
              </template>
            </a-table>
          </a-tab-pane>
        </a-tabs>
      </a-tab-pane>
    </a-tabs>

    <a-modal v-model:open="renameOpen" title="修改显示名称" @ok="submitRename" :confirm-loading="renameLoading">
      <a-input v-model:value="renameValue" placeholder="新名称" />
    </a-modal>

    <a-modal v-model:open="resizeBootOpen" title="引导卷扩容 (GB)" @ok="submitResizeBoot" :confirm-loading="resizeBootLoading">
      <a-input-number v-model:value="resizeBootGb" :min="1" style="width: 100%" placeholder="新大小 GB" />
    </a-modal>

    <a-modal v-model:open="resizeBlockOpen" title="块卷扩容 (GB)" @ok="submitResizeBlock" :confirm-loading="resizeBlockLoading">
      <a-input-number v-model:value="resizeBlockGb" :min="1" style="width: 100%" placeholder="新大小 GB" />
    </a-modal>

    <a-modal
      v-model:open="replBootOpen"
      title="启用引导卷跨区域复制"
      width="560px"
      @ok="submitReplBoot"
      :confirm-loading="replBootLoading"
    >
      <a-form layout="vertical" size="small">
        <a-form-item label="副本显示名称">
          <a-input v-model:value="replBootForm.replicaDisplayName" placeholder="例如 replica-boot-1" />
        </a-form-item>
        <a-form-item label="目标可用域 (AD)" extra="与源卷不同 AD，例如 PHX-AD-2">
          <a-input v-model:value="replBootForm.destinationAvailabilityDomain" />
        </a-form-item>
        <a-form-item label="XRR KMS Key OCID（可选）">
          <a-input v-model:value="replBootForm.xrrKmsKeyId" placeholder="留空则不加密副本" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="replBlockOpen"
      title="启用块卷跨区域复制"
      width="560px"
      @ok="submitReplBlock"
      :confirm-loading="replBlockLoading"
    >
      <a-form layout="vertical" size="small">
        <a-form-item label="副本显示名称">
          <a-input v-model:value="replBlockForm.replicaDisplayName" />
        </a-form-item>
        <a-form-item label="目标可用域 (AD)">
          <a-input v-model:value="replBlockForm.destinationAvailabilityDomain" />
        </a-form-item>
        <a-form-item label="XRR KMS Key OCID（可选）">
          <a-input v-model:value="replBlockForm.xrrKmsKeyId" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="actBootOpen"
      title="从引导卷副本激活新引导卷"
      width="600px"
      @ok="submitActBoot"
      :confirm-loading="actBootLoading"
    >
      <a-form layout="vertical" size="small">
        <a-form-item label="隔间">
          <a-select v-model:value="actBootForm.compartmentId" :options="compartmentOptions" show-search option-filter-prop="label" />
        </a-form-item>
        <a-form-item label="可用域">
          <a-input v-model:value="actBootForm.availabilityDomain" />
        </a-form-item>
        <a-form-item label="新卷显示名称">
          <a-input v-model:value="actBootForm.displayName" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="actBlockOpen"
      title="从块卷副本激活新块卷"
      width="600px"
      @ok="submitActBlock"
      :confirm-loading="actBlockLoading"
    >
      <a-form layout="vertical" size="small">
        <a-form-item label="隔间">
          <a-select v-model:value="actBlockForm.compartmentId" :options="compartmentOptions" show-search option-filter-prop="label" />
        </a-form-item>
        <a-form-item label="可用域">
          <a-input v-model:value="actBlockForm.availabilityDomain" />
        </a-form-item>
        <a-form-item label="新卷显示名称">
          <a-input v-model:value="actBlockForm.displayName" />
        </a-form-item>
        <a-form-item label="大小 GB（可选，默认与副本一致）">
          <a-input-number v-model:value="actBlockForm.sizeInGBs" :min="1" style="width: 100%" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="vgOpen"
      title="新建卷组"
      width="640px"
      @ok="submitCreateVg"
      :confirm-loading="vgLoading"
    >
      <a-form layout="vertical" size="small">
        <a-form-item label="隔间">
          <a-select v-model:value="vgForm.compartmentId" :options="compartmentOptions" show-search option-filter-prop="label" />
        </a-form-item>
        <a-form-item label="可用域">
          <a-input v-model:value="vgForm.availabilityDomain" placeholder="与成员块卷一致" />
        </a-form-item>
        <a-form-item label="显示名称">
          <a-input v-model:value="vgForm.displayName" />
        </a-form-item>
        <a-form-item label="成员块卷（可多选）" extra="仅列出当前数据中的块存储卷">
          <a-select
            v-model:value="vgForm.volumeIds"
            mode="multiple"
            style="width: 100%"
            :options="blockVolumeSelectOptions"
            show-search
            option-filter-prop="label"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="policyCreateOpen"
      title="新建卷备份策略"
      width="720px"
      @ok="submitCreatePolicy"
      :confirm-loading="policyCreateLoading"
    >
      <a-form layout="vertical" size="small">
        <a-form-item label="隔间">
          <a-select v-model:value="policyCreateForm.compartmentId" :options="compartmentOptions" show-search option-filter-prop="label" />
        </a-form-item>
        <a-form-item label="显示名称">
          <a-input v-model:value="policyCreateForm.displayName" />
        </a-form-item>
        <a-form-item label="计划 schedules（JSON 数组）" extra="字段见后端 parseVolumeBackupSchedules：backupType, period, offsetType, hourOfDay, retentionSeconds 等">
          <a-textarea v-model:value="policyCreateForm.schedulesJson" :rows="10" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="policyEditOpen"
      title="编辑卷备份策略"
      width="720px"
      @ok="submitEditPolicy"
      :confirm-loading="policyEditLoading"
    >
      <a-form layout="vertical" size="small">
        <a-form-item label="显示名称">
          <a-input v-model:value="policyEditForm.displayName" />
        </a-form-item>
        <a-form-item label="计划 schedules（JSON 数组，留空则仅更新名称）">
          <a-textarea v-model:value="policyEditForm.schedulesJson" :rows="10" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="assignOpen"
      title="新建备份策略绑定"
      width="560px"
      @ok="submitCreateAssignment"
      :confirm-loading="assignLoading"
    >
      <a-form layout="vertical" size="small">
        <a-form-item label="策略">
          <a-select v-model:value="assignForm.policyId" :options="policySelectOptions" show-search option-filter-prop="label" />
        </a-form-item>
        <a-form-item label="资产（引导卷 / 块卷 / 卷组）">
          <a-select v-model:value="assignForm.assetId" :options="assetSelectOptions" show-search option-filter-prop="label" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="bucketCreateOpen" title="新建存储桶" width="560px" @ok="submitCreateBucket" :confirm-loading="bucketCreateLoading">
      <a-form layout="vertical" size="small">
        <a-form-item label="隔间">
          <a-select v-model:value="bucketCreateForm.compartmentId" :options="compartmentOptions" show-search option-filter-prop="label" />
        </a-form-item>
        <a-form-item label="桶名称（全局唯一）">
          <a-input v-model:value="bucketCreateForm.name" />
        </a-form-item>
        <a-form-item label="公共访问类型（可选）">
          <a-select
            v-model:value="bucketCreateForm.publicAccessType"
            allow-clear
            placeholder="默认 NoPublicAccess"
            :options="publicAccessOptions"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="bucketEditOpen" title="更新存储桶" width="560px" @ok="submitEditBucket" :confirm-loading="bucketEditLoading">
      <a-form layout="vertical" size="small">
        <a-form-item label="版本控制 versioning">
          <a-select v-model:value="bucketEditForm.versioning" allow-clear placeholder="不修改" :options="versioningOptions" />
        </a-form-item>
        <a-form-item label="公共访问类型">
          <a-select v-model:value="bucketEditForm.publicAccessType" allow-clear placeholder="不修改" :options="publicAccessOptions" />
        </a-form-item>
        <a-form-item label="freeformTags（JSON 对象，可选）">
          <a-textarea v-model:value="bucketEditForm.freeformTagsJson" :rows="4" placeholder='{"env":"dev"}' />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="peCreateOpen" title="新建对象存储专用端点" width="560px" @ok="submitCreatePe" :confirm-loading="peCreateLoading">
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 12px"
        message="若创建失败，请确认子网与 OCI 控制台要求的 accessTargets 等参数；当前后端为最小字段集。"
      />
      <a-form layout="vertical" size="small">
        <a-form-item label="隔间">
          <a-select v-model:value="peCreateForm.compartmentId" :options="compartmentOptions" show-search option-filter-prop="label" />
        </a-form-item>
        <a-form-item label="显示名称">
          <a-input v-model:value="peCreateForm.displayName" />
        </a-form-item>
        <a-form-item label="子网 OCID">
          <a-input v-model:value="peCreateForm.subnetId" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="deleteOpen" :title="'删除 — ' + (deleteTarget?.displayName || deleteTarget?.name || '')" @ok="submitDelete" :confirm-loading="deleteLoading"
      ok-text="确认删除" :ok-button-props="{ danger: true }">
      <a-alert type="warning" message="删除需要 Telegram 验证码" show-icon style="margin-bottom: 12px" />
      <a-input v-model:value="deleteCode" placeholder="6 位验证码" maxlength="6" />
    </a-modal>

    <a-modal v-model:open="policyOpen" title="编辑桶策略（JSON）" width="720px" @ok="submitPolicy" :confirm-loading="policyLoading" ok-text="保存">
      <a-alert type="info" message="保存前会发送 Telegram 验证码（editBucketPolicy）" show-icon style="margin-bottom: 12px" />
      <a-input v-model:value="policyCode" placeholder="先点「发送验证码」后输入 6 位码" maxlength="6" style="margin-bottom: 8px" />
      <a-button size="small" @click="sendPolicyCode" :loading="policySendLoading">发送验证码</a-button>
      <a-textarea v-model:value="policyText" :rows="14" style="margin-top: 12px" />
    </a-modal>
  </a-drawer>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  listStorageRegions,
  listStorageCompartments,
  blockStorageAggregate,
  objectStorageAggregate,
  deleteStorage,
  putBucketPolicy,
  storageMutate,
} from '../api/storage'
import { sendVerifyCode } from '../api/system'

const props = withDefaults(
  defineProps<{
    open: boolean
    userId: string
    tenantName?: string
    defaultRegion?: string
  }>(),
  { defaultRegion: '' },
)
const emit = defineEmits<{ (e: 'update:open', v: boolean): void }>()

const regionLoading = ref(false)
const compartmentLoading = ref(false)
const loading = ref(false)
const region = ref('')
const compartmentId = ref<string | undefined>(undefined)
const regionOptions = ref<{ label: string; value: string }[]>([])
const compartmentOptions = ref<{ label: string; value: string }[]>([])

const mainTab = ref('block')
const objectSub = ref('buckets')
const blockView = ref('bootVolumes')
const blockData = ref<Record<string, any[]>>({})
const objectData = ref<{ namespace?: string; buckets?: any[]; privateEndpoints?: any[] }>({})

const blockViewOptions = [
  { label: '引导卷', value: 'bootVolumes' },
  { label: '块存储卷', value: 'blockVolumes' },
  { label: '引导卷备份', value: 'bootVolumeBackups' },
  { label: '块存储备份', value: 'blockVolumeBackups' },
  { label: '引导卷副本', value: 'bootVolumeReplicas' },
  { label: '块存储卷副本', value: 'blockVolumeReplicas' },
  { label: '卷组', value: 'volumeGroups' },
  { label: '卷组备份', value: 'volumeGroupBackups' },
  { label: '卷组副本', value: 'volumeGroupReplicas' },
  { label: '备份策略', value: 'volumeBackupPolicies' },
  { label: '备份策略绑定', value: 'volumeBackupPolicyAssignments' },
]

const publicAccessOptions = [
  { label: 'NoPublicAccess', value: 'NoPublicAccess' },
  { label: 'ObjectRead', value: 'ObjectRead' },
  { label: 'ObjectReadWithoutList', value: 'ObjectReadWithoutList' },
]

const versioningOptions = [
  { label: 'Enabled', value: 'Enabled' },
  { label: 'Suspended', value: 'Suspended' },
]

const blockColumnsResolved = computed(() => {
  const v = blockView.value
  const cols: any[] = [
    { title: '名称', dataIndex: 'displayName', key: 'displayName', ellipsis: true, width: 200 },
    { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 110 },
  ]
  if (
    [
      'bootVolumes',
      'blockVolumes',
      'bootVolumeReplicas',
      'blockVolumeReplicas',
      'bootVolumeBackups',
      'blockVolumeBackups',
      'volumeGroups',
    ].includes(v)
  ) {
    cols.push({ title: '规格 / AD', key: 'spec', width: 200, ellipsis: true })
  }
  cols.push({ title: '隔间', dataIndex: 'compartmentName', key: 'compartmentName', width: 120, ellipsis: true })
  if (v === 'bootVolumes' || v === 'blockVolumes') {
    cols.push({ title: '挂载', key: 'attachmentSummary', width: 140, ellipsis: true })
  }
  if (v === 'volumeBackupPolicyAssignments') {
    cols.push({ title: '策略 / 资产', key: 'policyAsset', width: 180 })
  }
  cols.push({ title: '操作', key: 'actions', width: 320 })
  return cols
})

function specCell(record: any) {
  const parts: string[] = []
  if (record.sizeInGBs != null) parts.push(`${record.sizeInGBs} GB`)
  if (record.availabilityDomain) parts.push(String(record.availabilityDomain))
  return parts.length ? parts.join(' · ') : '—'
}

function shortId(id: string | undefined) {
  if (!id) return '—'
  return id.length > 14 ? `…${id.slice(-10)}` : id
}

const bucketColumns = [
  { title: '名称', dataIndex: 'name', key: 'name', ellipsis: true },
  { title: '访问', dataIndex: 'publicAccessType', key: 'publicAccessType', width: 140 },
  { title: '层级', dataIndex: 'storageTier', key: 'storageTier', width: 100 },
  { title: '隔间', dataIndex: 'compartmentName', key: 'compartmentName', width: 120, ellipsis: true },
  { title: '操作', key: 'actions', width: 220 },
]

const peColumns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName', ellipsis: true },
  { title: '状态', dataIndex: 'lifecycleState', key: 'lifecycleState', width: 110 },
  { title: '子网', dataIndex: 'subnetId', key: 'subnetId', ellipsis: true },
  { title: '隔间', dataIndex: 'compartmentName', key: 'compartmentName', width: 120, ellipsis: true },
  { title: '操作', key: 'actions', width: 80 },
]

const currentBlockRows = computed(() => {
  const rows = (blockData.value as any)[blockView.value] || []
  return rows.map((r: any, i: number) => ({
    ...r,
    rowKey: r.id || `${blockView.value}-${i}`,
  }))
})

const buckets = computed(() =>
  (objectData.value.buckets || []).map((b: any, i: number) => ({
    ...b,
    rowKey: b.name || `b-${i}`,
  })),
)
const privateEndpoints = computed(() => objectData.value.privateEndpoints || [])

const canRenameBlock = computed(() =>
  ['bootVolumes', 'blockVolumes', 'bootVolumeReplicas', 'blockVolumeReplicas', 'volumeGroups'].includes(blockView.value),
)
const canResizeBoot = computed(() => blockView.value === 'bootVolumes')
const canResizeBlock = computed(() => blockView.value === 'blockVolumes')
const canEnableBootReplication = computed(() => blockView.value === 'bootVolumes')
const canEnableBlockReplication = computed(() => blockView.value === 'blockVolumes')
const canActivateBootReplica = computed(() => blockView.value === 'bootVolumeReplicas')
const canActivateBlockReplica = computed(() => blockView.value === 'blockVolumeReplicas')
const canEditBackupPolicy = computed(() => blockView.value === 'volumeBackupPolicies')
const canDeleteBlock = computed(() => true)

const blockVolumeSelectOptions = computed(() => {
  const rows = (blockData.value as any).blockVolumes || []
  const cid = vgForm.value.compartmentId
  return rows
    .filter((r: any) => !cid || r.compartmentId === cid)
    .map((r: any) => ({
      label: `${r.displayName || r.id} (${(r.id || '').slice(-8)})`,
      value: r.id,
    }))
})

const policySelectOptions = computed(() => {
  const rows = (blockData.value as any).volumeBackupPolicies || []
  return rows.map((r: any) => ({
    label: `${r.displayName || r.id} (${(r.id || '').slice(-8)})`,
    value: r.id,
  }))
})

const assetSelectOptions = computed(() => {
  const out: { label: string; value: string }[] = []
  for (const r of (blockData.value as any).bootVolumes || []) {
    out.push({ label: `[引导卷] ${r.displayName || r.id}`, value: r.id })
  }
  for (const r of (blockData.value as any).blockVolumes || []) {
    out.push({ label: `[块卷] ${r.displayName || r.id}`, value: r.id })
  }
  for (const r of (blockData.value as any).volumeGroups || []) {
    out.push({ label: `[卷组] ${r.displayName || r.id}`, value: r.id })
  }
  return out
})

watch(
  () => props.open,
  (v) => {
    if (v && props.userId) void initDrawer()
  },
)

watch(region, (r) => {
  try {
    localStorage.setItem(`storage.region:${props.userId}`, r || '')
  } catch {}
  if (props.open && r) {
    void loadCompartments()
    void loadAll()
  }
})

async function initDrawer() {
  regionLoading.value = true
  try {
    const res = await listStorageRegions()
    const ids = (res.data || []) as string[]
    regionOptions.value = ids.map((x) => ({ label: x, value: x }))
    const cached = (() => {
      try {
        return localStorage.getItem(`storage.region:${props.userId}`) || ''
      } catch {
        return ''
      }
    })()
    region.value = (props.defaultRegion && ids.includes(props.defaultRegion) ? props.defaultRegion : null)
      || (cached && ids.includes(cached) ? cached : null)
      || (ids[0] || '')
    if (region.value) {
      await loadCompartments()
      await loadAll()
    }
  } catch (e: any) {
    message.error(e?.message || '加载 Region 失败')
  } finally {
    regionLoading.value = false
  }
}

async function loadCompartments() {
  if (!props.userId || !region.value) return
  compartmentLoading.value = true
  try {
    const res = await listStorageCompartments({ id: props.userId, region: region.value })
    const list = res.data || []
    compartmentOptions.value = list.map((c: any) => ({
      label: `${c.name || c.id} (${(c.id || '').slice(-8)})`,
      value: c.id,
    }))
  } catch {
    compartmentOptions.value = []
  } finally {
    compartmentLoading.value = false
  }
}

function onMainTab() {
  if (mainTab.value === 'object') void loadObject()
}

async function loadAll() {
  if (!props.userId || !region.value) return
  loading.value = true
  try {
    const res = await blockStorageAggregate({
      id: props.userId,
      region: region.value,
      compartmentId: compartmentId.value || undefined,
    } as any)
    blockData.value = res.data || {}
    if (mainTab.value === 'object') await loadObject()
  } catch (e: any) {
    message.error(e?.message || '加载块存储失败')
  } finally {
    loading.value = false
  }
}

async function loadObject() {
  if (!props.userId || !region.value) return
  try {
    const res = await objectStorageAggregate({
      id: props.userId,
      region: region.value,
      compartmentId: compartmentId.value || undefined,
    } as any)
    objectData.value = res.data || {}
  } catch (e: any) {
    message.error(e?.message || '加载对象存储失败')
  }
}

watch(compartmentId, () => {
  if (props.open && region.value) void loadAll()
})

function resourceTypeForBlockRow(row: any): string | null {
  const map: Record<string, string> = {
    bootVolumes: 'BOOT_VOLUME',
    blockVolumes: 'BLOCK_VOLUME',
    bootVolumeBackups: 'BOOT_VOLUME_BACKUP',
    blockVolumeBackups: 'BLOCK_VOLUME_BACKUP',
    bootVolumeReplicas: 'BOOT_VOLUME_REPLICA',
    blockVolumeReplicas: 'BLOCK_VOLUME_REPLICA',
    volumeGroups: 'VOLUME_GROUP',
    volumeGroupBackups: 'VOLUME_GROUP_BACKUP',
    volumeGroupReplicas: 'VOLUME_GROUP_REPLICA',
    volumeBackupPolicies: 'VOLUME_BACKUP_POLICY',
    volumeBackupPolicyAssignments: 'VOLUME_BACKUP_POLICY_ASSIGNMENT',
  }
  return map[blockView.value] || null
}

const deleteOpen = ref(false)
const deleteTarget = ref<any>(null)
const deleteCode = ref('')
const deleteLoading = ref(false)

async function openDelete(row: any) {
  deleteTarget.value = row
  deleteCode.value = ''
  try {
    await sendVerifyCode('deleteStorage')
    message.success('验证码已发送至 Telegram')
  } catch (e: any) {
    message.error(e?.message || '发送验证码失败')
    return
  }
  deleteOpen.value = true
}

async function submitDelete() {
  if (!deleteCode.value || deleteCode.value.length !== 6) return message.warning('请输入 6 位验证码')
  if (!region.value || !props.userId) return
  const rt =
    mainTab.value === 'object'
      ? (objectSub.value === 'buckets' ? 'BUCKET' : 'PRIVATE_ENDPOINT')
      : resourceTypeForBlockRow(deleteTarget.value)
  if (!rt) return message.warning('当前类型不支持删除')
  deleteLoading.value = true
  try {
    if (rt === 'BUCKET') {
      await deleteStorage({
        id: props.userId,
        region: region.value,
        resourceType: 'BUCKET',
        resourceId: deleteTarget.value.name,
        namespace: objectData.value.namespace,
        bucketName: deleteTarget.value.name,
        verifyCode: deleteCode.value,
      })
    } else {
      await deleteStorage({
        id: props.userId,
        region: region.value,
        resourceType: rt,
        resourceId: deleteTarget.value.id,
        verifyCode: deleteCode.value,
      })
    }
    message.success('已删除')
    deleteOpen.value = false
    await loadAll()
    if (mainTab.value === 'object') await loadObject()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  } finally {
    deleteLoading.value = false
  }
}

const renameOpen = ref(false)
const renameTarget = ref<any>(null)
const renameValue = ref('')
const renameLoading = ref(false)

function openRename(row: any) {
  renameTarget.value = row
  renameValue.value = row.displayName || ''
  renameOpen.value = true
}

async function submitRename() {
  if (!props.userId || !region.value || !renameTarget.value?.id) return
  renameLoading.value = true
  try {
    const v = blockView.value
    if (v === 'bootVolumes') {
      await storageMutate({
        action: 'updateBootVolume',
        id: props.userId,
        region: region.value,
        bootVolumeId: renameTarget.value.id,
        displayName: renameValue.value,
      })
    } else if (v === 'blockVolumes') {
      await storageMutate({
        action: 'updateBlockVolume',
        id: props.userId,
        region: region.value,
        volumeId: renameTarget.value.id,
        displayName: renameValue.value,
      })
    } else if (v === 'bootVolumeReplicas') {
      await storageMutate({
        action: 'updateBootVolumeReplica',
        id: props.userId,
        region: region.value,
        replicaId: renameTarget.value.id,
        displayName: renameValue.value,
      })
    } else if (v === 'blockVolumeReplicas') {
      await storageMutate({
        action: 'updateBlockVolumeReplica',
        id: props.userId,
        region: region.value,
        replicaId: renameTarget.value.id,
        displayName: renameValue.value,
      })
    } else if (v === 'volumeGroups') {
      await storageMutate({
        action: 'updateVolumeGroup',
        id: props.userId,
        region: region.value,
        volumeGroupId: renameTarget.value.id,
        displayName: renameValue.value,
      })
    } else {
      message.warning('当前类型不支持改名')
      return
    }
    message.success('已更新')
    renameOpen.value = false
    await loadAll()
  } catch (e: any) {
    message.error(e?.message || '更新失败')
  } finally {
    renameLoading.value = false
  }
}

const resizeBootOpen = ref(false)
const resizeBootTarget = ref<any>(null)
const resizeBootGb = ref<number | null>(null)
const resizeBootLoading = ref(false)

function openResizeBoot(row: any) {
  resizeBootTarget.value = row
  resizeBootGb.value = row.sizeInGBs != null ? Number(row.sizeInGBs) + 1 : null
  resizeBootOpen.value = true
}

async function submitResizeBoot() {
  if (!props.userId || !region.value || !resizeBootTarget.value?.id || resizeBootGb.value == null) {
    return message.warning('请填写新大小')
  }
  resizeBootLoading.value = true
  try {
    await storageMutate({
      action: 'updateBootVolume',
      id: props.userId,
      region: region.value,
      bootVolumeId: resizeBootTarget.value.id,
      displayName: resizeBootTarget.value.displayName || 'boot',
      sizeInGBs: resizeBootGb.value,
    })
    message.success('已提交扩容')
    resizeBootOpen.value = false
    await loadAll()
  } catch (e: any) {
    message.error(e?.message || '扩容失败')
  } finally {
    resizeBootLoading.value = false
  }
}

const resizeBlockOpen = ref(false)
const resizeBlockTarget = ref<any>(null)
const resizeBlockGb = ref<number | null>(null)
const resizeBlockLoading = ref(false)

function openResizeBlock(row: any) {
  resizeBlockTarget.value = row
  resizeBlockGb.value = row.sizeInGBs != null ? Number(row.sizeInGBs) + 1 : null
  resizeBlockOpen.value = true
}

async function submitResizeBlock() {
  if (!props.userId || !region.value || !resizeBlockTarget.value?.id || resizeBlockGb.value == null) {
    return message.warning('请填写新大小')
  }
  resizeBlockLoading.value = true
  try {
    await storageMutate({
      action: 'updateBlockVolume',
      id: props.userId,
      region: region.value,
      volumeId: resizeBlockTarget.value.id,
      displayName: resizeBlockTarget.value.displayName || 'volume',
      sizeInGBs: resizeBlockGb.value,
    })
    message.success('已提交扩容')
    resizeBlockOpen.value = false
    await loadAll()
  } catch (e: any) {
    message.error(e?.message || '扩容失败')
  } finally {
    resizeBlockLoading.value = false
  }
}

const replBootOpen = ref(false)
const replBootRow = ref<any>(null)
const replBootForm = ref({ replicaDisplayName: '', destinationAvailabilityDomain: '', xrrKmsKeyId: '' })
const replBootLoading = ref(false)

function openEnableBootReplication(row: any) {
  replBootRow.value = row
  replBootForm.value = {
    replicaDisplayName: `${row.displayName || 'boot'}-replica`,
    destinationAvailabilityDomain: '',
    xrrKmsKeyId: '',
  }
  replBootOpen.value = true
}

async function submitReplBoot() {
  if (!props.userId || !region.value || !replBootRow.value?.id) return
  const f = replBootForm.value
  if (!f.replicaDisplayName.trim() || !f.destinationAvailabilityDomain.trim()) {
    return message.warning('请填写副本名称与目标 AD')
  }
  replBootLoading.value = true
  try {
    await storageMutate({
      action: 'enableBootVolumeReplication',
      id: props.userId,
      region: region.value,
      bootVolumeId: replBootRow.value.id,
      replicaDisplayName: f.replicaDisplayName.trim(),
      destinationAvailabilityDomain: f.destinationAvailabilityDomain.trim(),
      xrrKmsKeyId: f.xrrKmsKeyId.trim(),
    })
    message.success('已提交启用复制')
    replBootOpen.value = false
    await loadAll()
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    replBootLoading.value = false
  }
}

const replBlockOpen = ref(false)
const replBlockRow = ref<any>(null)
const replBlockForm = ref({ replicaDisplayName: '', destinationAvailabilityDomain: '', xrrKmsKeyId: '' })
const replBlockLoading = ref(false)

function openEnableBlockReplication(row: any) {
  replBlockRow.value = row
  replBlockForm.value = {
    replicaDisplayName: `${row.displayName || 'vol'}-replica`,
    destinationAvailabilityDomain: '',
    xrrKmsKeyId: '',
  }
  replBlockOpen.value = true
}

async function submitReplBlock() {
  if (!props.userId || !region.value || !replBlockRow.value?.id) return
  const f = replBlockForm.value
  if (!f.replicaDisplayName.trim() || !f.destinationAvailabilityDomain.trim()) {
    return message.warning('请填写副本名称与目标 AD')
  }
  replBlockLoading.value = true
  try {
    await storageMutate({
      action: 'enableBlockVolumeReplication',
      id: props.userId,
      region: region.value,
      volumeId: replBlockRow.value.id,
      replicaDisplayName: f.replicaDisplayName.trim(),
      destinationAvailabilityDomain: f.destinationAvailabilityDomain.trim(),
      xrrKmsKeyId: f.xrrKmsKeyId.trim(),
    })
    message.success('已提交启用复制')
    replBlockOpen.value = false
    await loadAll()
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    replBlockLoading.value = false
  }
}

const actBootOpen = ref(false)
const actBootRow = ref<any>(null)
const actBootForm = ref({ compartmentId: '', availabilityDomain: '', displayName: '' })
const actBootLoading = ref(false)

function openActivateBootReplica(row: any) {
  actBootRow.value = row
  actBootForm.value = {
    compartmentId: row.compartmentId || compartmentId.value || compartmentOptions.value[0]?.value || '',
    availabilityDomain: row.availabilityDomain || '',
    displayName: `${row.displayName || 'boot'}-activated`,
  }
  actBootOpen.value = true
}

async function submitActBoot() {
  if (!props.userId || !region.value || !actBootRow.value?.id) return
  const f = actBootForm.value
  if (!f.compartmentId || !f.availabilityDomain.trim() || !f.displayName.trim()) {
    return message.warning('请填写隔间、AD 与名称')
  }
  actBootLoading.value = true
  try {
    await storageMutate({
      action: 'activateBootReplicaAsBootVolume',
      id: props.userId,
      region: region.value,
      replicaId: actBootRow.value.id,
      compartmentId: f.compartmentId,
      availabilityDomain: f.availabilityDomain.trim(),
      displayName: f.displayName.trim(),
    })
    message.success('已创建引导卷')
    actBootOpen.value = false
    await loadAll()
  } catch (e: any) {
    message.error(e?.message || '激活失败')
  } finally {
    actBootLoading.value = false
  }
}

const actBlockOpen = ref(false)
const actBlockRow = ref<any>(null)
const actBlockForm = ref({ compartmentId: '', availabilityDomain: '', displayName: '', sizeInGBs: null as number | null })
const actBlockLoading = ref(false)

function openActivateBlockReplica(row: any) {
  actBlockRow.value = row
  actBlockForm.value = {
    compartmentId: row.compartmentId || compartmentId.value || compartmentOptions.value[0]?.value || '',
    availabilityDomain: row.availabilityDomain || '',
    displayName: `${row.displayName || 'vol'}-activated`,
    sizeInGBs: row.sizeInGBs != null ? Number(row.sizeInGBs) : null,
  }
  actBlockOpen.value = true
}

async function submitActBlock() {
  if (!props.userId || !region.value || !actBlockRow.value?.id) return
  const f = actBlockForm.value
  if (!f.compartmentId || !f.availabilityDomain.trim() || !f.displayName.trim()) {
    return message.warning('请填写隔间、AD 与名称')
  }
  actBlockLoading.value = true
  try {
    const payload: Record<string, unknown> = {
      action: 'activateBlockReplicaAsVolume',
      id: props.userId,
      region: region.value,
      replicaId: actBlockRow.value.id,
      compartmentId: f.compartmentId,
      availabilityDomain: f.availabilityDomain.trim(),
      displayName: f.displayName.trim(),
    }
    if (f.sizeInGBs != null) payload.sizeInGBs = f.sizeInGBs
    await storageMutate(payload)
    message.success('已创建块卷')
    actBlockOpen.value = false
    await loadAll()
  } catch (e: any) {
    message.error(e?.message || '激活失败')
  } finally {
    actBlockLoading.value = false
  }
}

const vgOpen = ref(false)
const vgForm = ref({
  compartmentId: '',
  availabilityDomain: '',
  displayName: '',
  volumeIds: [] as string[],
})
const vgLoading = ref(false)

function openCreateVg() {
  vgForm.value = {
    compartmentId: compartmentId.value || compartmentOptions.value[0]?.value || '',
    availabilityDomain: '',
    displayName: 'volume-group-1',
    volumeIds: [],
  }
  vgOpen.value = true
}

async function submitCreateVg() {
  if (!props.userId || !region.value) return
  const f = vgForm.value
  if (!f.compartmentId || !f.availabilityDomain.trim() || !f.displayName.trim() || !f.volumeIds.length) {
    return message.warning('请填写隔间、AD、名称并至少选一个块卷')
  }
  vgLoading.value = true
  try {
    await storageMutate({
      action: 'createVolumeGroup',
      id: props.userId,
      region: region.value,
      compartmentId: f.compartmentId,
      availabilityDomain: f.availabilityDomain.trim(),
      displayName: f.displayName.trim(),
      volumeIds: f.volumeIds,
    })
    message.success('已创建卷组')
    vgOpen.value = false
    await loadAll()
  } catch (e: any) {
    message.error(e?.message || '创建失败')
  } finally {
    vgLoading.value = false
  }
}

const defaultSchedulesJson = `[
  {
    "backupType": "FULL",
    "period": "ONE_DAY",
    "offsetType": "STRUCTURED",
    "hourOfDay": 2,
    "retentionSeconds": 604800
  }
]`

const policyCreateOpen = ref(false)
const policyCreateForm = ref({ compartmentId: '', displayName: '', schedulesJson: defaultSchedulesJson })
const policyCreateLoading = ref(false)

function openCreatePolicy() {
  policyCreateForm.value = {
    compartmentId: compartmentId.value || compartmentOptions.value[0]?.value || '',
    displayName: 'backup-policy-1',
    schedulesJson: defaultSchedulesJson,
  }
  policyCreateOpen.value = true
}

async function submitCreatePolicy() {
  if (!props.userId || !region.value) return
  const f = policyCreateForm.value
  if (!f.compartmentId || !f.displayName.trim()) return message.warning('请填写隔间与名称')
  let schedules: any[] = []
  try {
    schedules = JSON.parse(f.schedulesJson || '[]')
    if (!Array.isArray(schedules)) throw new Error('schedules 须为数组')
  } catch (e: any) {
    return message.error(e?.message || 'schedules JSON 无效')
  }
  policyCreateLoading.value = true
  try {
    await storageMutate({
      action: 'createVolumeBackupPolicy',
      id: props.userId,
      region: region.value,
      compartmentId: f.compartmentId,
      displayName: f.displayName.trim(),
      schedules,
    })
    message.success('已创建策略')
    policyCreateOpen.value = false
    await loadAll()
  } catch (e: any) {
    message.error(e?.message || '创建失败')
  } finally {
    policyCreateLoading.value = false
  }
}

const policyEditOpen = ref(false)
const policyEditRow = ref<any>(null)
const policyEditForm = ref({ displayName: '', schedulesJson: '' })
const policyEditLoading = ref(false)

function openEditPolicy(row: any) {
  policyEditRow.value = row
  policyEditForm.value = {
    displayName: row.displayName || '',
    schedulesJson: row.schedules ? JSON.stringify(row.schedules, null, 2) : '',
  }
  policyEditOpen.value = true
}

async function submitEditPolicy() {
  if (!props.userId || !region.value || !policyEditRow.value?.id) return
  const f = policyEditForm.value
  let schedules: any[] | undefined
  if (f.schedulesJson.trim()) {
    try {
      const parsed = JSON.parse(f.schedulesJson)
      if (!Array.isArray(parsed)) throw new Error('schedules 须为数组')
      schedules = parsed
    } catch (e: any) {
      return message.error(e?.message || 'schedules JSON 无效')
    }
  }
  policyEditLoading.value = true
  try {
    const payload: Record<string, unknown> = {
      action: 'updateVolumeBackupPolicy',
      id: props.userId,
      region: region.value,
      policyId: policyEditRow.value.id,
      displayName: f.displayName.trim() || policyEditRow.value.displayName,
    }
    if (schedules && schedules.length) payload.schedules = schedules
    await storageMutate(payload)
    message.success('已更新策略')
    policyEditOpen.value = false
    await loadAll()
  } catch (e: any) {
    message.error(e?.message || '更新失败')
  } finally {
    policyEditLoading.value = false
  }
}

const assignOpen = ref(false)
const assignForm = ref({ policyId: '', assetId: '' })
const assignLoading = ref(false)

function openCreateAssignment() {
  assignForm.value = {
    policyId: policySelectOptions.value[0]?.value || '',
    assetId: assetSelectOptions.value[0]?.value || '',
  }
  assignOpen.value = true
}

async function submitCreateAssignment() {
  if (!props.userId || !region.value) return
  const f = assignForm.value
  if (!f.policyId || !f.assetId) return message.warning('请选择策略与资产')
  assignLoading.value = true
  try {
    await storageMutate({
      action: 'createVolumeBackupPolicyAssignment',
      id: props.userId,
      region: region.value,
      policyId: f.policyId,
      assetId: f.assetId,
    })
    message.success('已创建绑定')
    assignOpen.value = false
    await loadAll()
  } catch (e: any) {
    message.error(e?.message || '创建失败')
  } finally {
    assignLoading.value = false
  }
}

const bucketCreateOpen = ref(false)
const bucketCreateForm = ref({ compartmentId: '', name: '', publicAccessType: undefined as string | undefined })
const bucketCreateLoading = ref(false)

function openCreateBucket() {
  bucketCreateForm.value = {
    compartmentId: compartmentId.value || compartmentOptions.value[0]?.value || '',
    name: '',
    publicAccessType: undefined,
  }
  bucketCreateOpen.value = true
}

async function submitCreateBucket() {
  if (!props.userId || !region.value || !objectData.value.namespace) return
  const f = bucketCreateForm.value
  if (!f.compartmentId || !f.name.trim()) return message.warning('请填写隔间与桶名')
  bucketCreateLoading.value = true
  try {
    const payload: Record<string, unknown> = {
      action: 'createBucket',
      id: props.userId,
      region: region.value,
      namespace: objectData.value.namespace,
      compartmentId: f.compartmentId,
      name: f.name.trim(),
    }
    if (f.publicAccessType) payload.publicAccessType = f.publicAccessType
    await storageMutate(payload)
    message.success('已创建桶')
    bucketCreateOpen.value = false
    await loadObject()
  } catch (e: any) {
    message.error(e?.message || '创建失败')
  } finally {
    bucketCreateLoading.value = false
  }
}

const bucketEditOpen = ref(false)
const bucketEditRow = ref<any>(null)
const bucketEditForm = ref({
  versioning: undefined as string | undefined,
  publicAccessType: undefined as string | undefined,
  freeformTagsJson: '',
})
const bucketEditLoading = ref(false)

function openEditBucket(row: any) {
  bucketEditRow.value = row
  bucketEditForm.value = {
    versioning: undefined,
    publicAccessType: undefined,
    freeformTagsJson: '',
  }
  bucketEditOpen.value = true
}

async function submitEditBucket() {
  if (!props.userId || !region.value || !objectData.value.namespace || !bucketEditRow.value?.name) return
  const f = bucketEditForm.value
  let freeformTags: Record<string, string> | undefined
  if (f.freeformTagsJson.trim()) {
    try {
      freeformTags = JSON.parse(f.freeformTagsJson) as Record<string, string>
      if (typeof freeformTags !== 'object' || freeformTags === null || Array.isArray(freeformTags)) {
        message.error('freeformTags 须为 JSON 对象')
        return
      }
    } catch {
      message.error('freeformTags 须为合法 JSON 对象')
      return
    }
  }
  if (!f.versioning && !f.publicAccessType && !freeformTags) {
    message.warning('请至少修改一项')
    return
  }
  bucketEditLoading.value = true
  try {
    const payload: Record<string, unknown> = {
      action: 'updateBucket',
      id: props.userId,
      region: region.value,
      namespace: objectData.value.namespace,
      bucketName: bucketEditRow.value.name,
    }
    if (f.versioning) payload.versioning = f.versioning
    if (f.publicAccessType) payload.publicAccessType = f.publicAccessType
    if (freeformTags) payload.freeformTags = freeformTags
    await storageMutate(payload)
    message.success('已更新桶')
    bucketEditOpen.value = false
    await loadObject()
  } catch (e: any) {
    message.error(e?.message || '更新失败')
  } finally {
    bucketEditLoading.value = false
  }
}

const peCreateOpen = ref(false)
const peCreateForm = ref({ compartmentId: '', displayName: '', subnetId: '' })
const peCreateLoading = ref(false)

function openCreatePe() {
  peCreateForm.value = {
    compartmentId: compartmentId.value || compartmentOptions.value[0]?.value || '',
    displayName: 'pe-1',
    subnetId: '',
  }
  peCreateOpen.value = true
}

async function submitCreatePe() {
  if (!props.userId || !region.value || !objectData.value.namespace) return
  const f = peCreateForm.value
  if (!f.compartmentId || !f.displayName.trim() || !f.subnetId.trim()) {
    return message.warning('请填写隔间、名称与子网')
  }
  peCreateLoading.value = true
  try {
    await storageMutate({
      action: 'createPrivateEndpoint',
      id: props.userId,
      region: region.value,
      namespace: objectData.value.namespace,
      compartmentId: f.compartmentId,
      displayName: f.displayName.trim(),
      subnetId: f.subnetId.trim(),
    })
    message.success('已创建专用端点')
    peCreateOpen.value = false
    await loadObject()
  } catch (e: any) {
    message.error(e?.message || '创建失败')
  } finally {
    peCreateLoading.value = false
  }
}

const policyOpen = ref(false)
const policyBucket = ref<any>(null)
const policyText = ref('')
const policyCode = ref('')
const policyLoading = ref(false)
const policySendLoading = ref(false)

async function openBucketPolicy(row: any) {
  policyBucket.value = row
  policyText.value = ''
  policyCode.value = ''
  policyOpen.value = true
}

async function sendPolicyCode() {
  policySendLoading.value = true
  try {
    await sendVerifyCode('editBucketPolicy')
    message.success('验证码已发送')
  } catch (e: any) {
    message.error(e?.message || '发送失败')
  } finally {
    policySendLoading.value = false
  }
}

async function submitPolicy() {
  if (!policyCode.value || policyCode.value.length !== 6) return message.warning('请输入验证码')
  if (!policyBucket.value || !objectData.value.namespace) return
  policyLoading.value = true
  try {
    await putBucketPolicy({
      id: props.userId,
      region: region.value,
      namespace: objectData.value.namespace,
      bucketName: policyBucket.value.name,
      policy: policyText.value,
      verifyCode: policyCode.value,
    })
    message.success('桶策略已保存')
    policyOpen.value = false
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    policyLoading.value = false
  }
}
</script>
