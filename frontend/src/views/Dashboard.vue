<template>
  <div>
    <a-row :gutter="16">
      <a-col :span="6">
        <a-card>
          <a-statistic title="租户数量" :value="glance.tenantCount || 0">
            <template #prefix><UserOutlined /></template>
          </a-statistic>
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card>
          <a-statistic title="运行中任务" :value="glance.runningTaskCount || 0" value-style="color: #3f8600">
            <template #prefix><ThunderboltOutlined /></template>
          </a-statistic>
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card>
          <a-statistic title="CPU 使用率" :value="glance.cpuUsage || '0'" suffix="%">
            <template #prefix><DashboardOutlined /></template>
          </a-statistic>
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card>
          <a-statistic title="内存使用率" :value="glance.memoryUsage || '0'" suffix="%">
            <template #prefix><CloudServerOutlined /></template>
          </a-statistic>
        </a-card>
      </a-col>
    </a-row>
    <a-row :gutter="16" style="margin-top: 16px">
      <a-col :span="12">
        <a-card title="系统信息">
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item label="运行时长">{{ glance.uptime || '-' }}</a-descriptions-item>
            <a-descriptions-item label="总内存">{{ glance.totalMemoryGB || '-' }} GB</a-descriptions-item>
          </a-descriptions>
        </a-card>
      </a-col>
      <a-col :span="12">
        <a-card title="快捷操作">
          <a-space direction="vertical" style="width: 100%">
            <a-button type="primary" block @click="$router.push('/tenant')">管理租户配置</a-button>
            <a-button block @click="$router.push('/task')">查看开机任务</a-button>
            <a-button block @click="$router.push('/instance')">管理实例</a-button>
          </a-space>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { UserOutlined, ThunderboltOutlined, DashboardOutlined, CloudServerOutlined } from '@ant-design/icons-vue'
import { getGlance } from '../api/system'

const glance = ref<any>({})

onMounted(async () => {
  try {
    const res = await getGlance()
    glance.value = res.data
  } catch { /* ignore */ }
})
</script>
