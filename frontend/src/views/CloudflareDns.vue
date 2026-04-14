<template>
  <div>
    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="config" tab="CF 配置">
        <div class="table-toolbar">
          <a-button type="primary" @click="showAddCfg">
            <template #icon><PlusOutlined /></template>新增 CF 配置
          </a-button>
        </div>
        <a-table :columns="cfgColumns" :data-source="cfgList" :loading="cfgLoading" row-key="id" size="middle">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'apiToken'">
              <span>{{ record.apiToken?.substring(0, 8) }}****</span>
            </template>
            <template v-if="column.key === 'action'">
              <a-popconfirm title="确定删除?" @confirm="handleDeleteCfg(record.id)">
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
      <a-tab-pane key="dns" tab="DNS 记录">
        <a-empty description="DNS 记录管理将在后续版本实现" />
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { PlusOutlined } from '@ant-design/icons-vue'

const activeTab = ref('config')
const cfgLoading = ref(false)
const cfgList = ref<any[]>([])

const cfgColumns = [
  { title: '域名', dataIndex: 'domain' },
  { title: 'Zone ID', dataIndex: 'zoneId', ellipsis: true },
  { title: 'API Token', key: 'apiToken', width: 160 },
  { title: '创建时间', dataIndex: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 80 },
]

function showAddCfg() {
  // Will be implemented in phase 7
}

function handleDeleteCfg(_id: string) {
  // Will be implemented in phase 7
}
</script>

<style scoped>
.table-toolbar { margin-bottom: 16px; }
</style>
