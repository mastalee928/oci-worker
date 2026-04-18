<template>
  <div>
    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="config" tab="CF 配置">
        <div class="mobile-toolbar">
          <a-button type="primary" @click="showAddCfg">
            <template #icon><PlusOutlined /></template>新增 CF 配置
          </a-button>
        </div>

        <a-table v-if="!isMobile" :columns="cfgColumns" :data-source="cfgList" :loading="cfgLoading" row-key="id" size="middle">
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

        <a-spin v-else :spinning="cfgLoading">
          <a-empty v-if="!cfgLoading && cfgList.length === 0" description="暂无配置" />
          <div v-for="item in cfgList" :key="item.id" class="mobile-card">
            <div class="mobile-card-header">
              <span class="mobile-card-title">{{ item.domain }}</span>
              <a-popconfirm title="确定删除?" @confirm="handleDeleteCfg(item.id)">
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
            </div>
            <div class="mobile-card-body">
              <div class="mobile-card-row"><span class="label">Zone ID</span><span class="value">{{ item.zoneId }}</span></div>
              <div class="mobile-card-row"><span class="label">API Token</span><span class="value">{{ item.apiToken?.substring(0, 8) }}****</span></div>
              <div class="mobile-card-row"><span class="label">创建时间</span><span class="value">{{ item.createTime }}</span></div>
            </div>
          </div>
        </a-spin>
      </a-tab-pane>
      <a-tab-pane key="dns" tab="DNS 记录">
        <a-empty description="DNS 记录管理将在后续版本实现" />
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'

const activeTab = ref('config')
const cfgLoading = ref(false)
const cfgList = ref<any[]>([])
const isMobile = ref(window.innerWidth < 768)
function checkMobile() { isMobile.value = window.innerWidth < 768 }
onMounted(() => window.addEventListener('resize', checkMobile))
onUnmounted(() => window.removeEventListener('resize', checkMobile))

const cfgColumns = [
  { title: '域名', dataIndex: 'domain' },
  { title: 'Zone ID', dataIndex: 'zoneId', ellipsis: true },
  { title: 'API Token', key: 'apiToken', width: 160 },
  { title: '创建时间', dataIndex: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 80 },
]

function showAddCfg() {
  message.info('该功能暂未开放')
}

function handleDeleteCfg(_id: string) {
  message.info('该功能暂未开放')
}
</script>
