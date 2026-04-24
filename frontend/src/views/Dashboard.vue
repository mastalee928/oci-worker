<template>
  <div class="page-dashboard">
    <div class="dash-stats">
      <div class="dash-stat-card">
        <div class="icon-wrap" style="background: var(--primary-light); color: var(--primary)">
          <i class="ri-user-settings-line"></i>
        </div>
        <div class="val">{{ glance.tenantCount || 0 }}</div>
        <div class="lbl">租户数量</div>
      </div>
      <div class="dash-stat-card">
        <div class="icon-wrap" style="background: var(--success-bg); color: var(--success)">
          <i class="ri-flashlight-line"></i>
        </div>
        <div class="val" style="color: var(--success-text)">{{ glance.runningTaskCount || 0 }}</div>
        <div class="lbl">运行中任务</div>
      </div>
      <div class="dash-stat-card">
        <div class="icon-wrap" style="background: var(--warning-bg); color: var(--warning)">
          <i class="ri-cpu-line"></i>
        </div>
        <div class="val">{{ glance.cpuUsage || '0' }}%</div>
        <div class="lbl">CPU 使用率</div>
      </div>
      <div class="dash-stat-card">
        <div class="icon-wrap" style="background: var(--danger-bg); color: var(--danger)">
          <i class="ri-ram-line"></i>
        </div>
        <div class="val">{{ glance.memoryUsage || '0' }}%</div>
        <div class="lbl">内存使用率</div>
      </div>
    </div>

    <div class="dash-panels">
      <div class="dash-panel">
        <h3><i class="ri-information-line"></i>系统信息</h3>
        <a-descriptions :column="1" bordered size="small">
          <a-descriptions-item label="运行时长">{{ glance.uptime || '-' }}</a-descriptions-item>
          <a-descriptions-item label="总内存">{{ glance.totalMemoryGB || '-' }} GB</a-descriptions-item>
        </a-descriptions>
      </div>
      <div class="dash-panel">
        <h3><i class="ri-rocket-line"></i>快捷操作</h3>
        <div class="quick-actions">
          <button class="action-btn action-primary" @click="$router.push('/tenant')">
            <i class="ri-user-settings-line"></i>管理租户配置
          </button>
          <button class="action-btn" @click="$router.push('/task')">
            <i class="ri-flashlight-line"></i>查看开机任务
          </button>
          <button class="action-btn" @click="$router.push('/instance')">
            <i class="ri-server-line"></i>管理实例
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getGlance } from '../api/system'

const glance = ref<any>({})

onMounted(async () => {
  try {
    const res = await getGlance()
    glance.value = res.data
  } catch {}
})
</script>

<style scoped>
/* 主内容区 .app-content 为 overflow: auto 时，:hover 用 translateY 会把整卡上移，顶边/描边在容器顶部被裁切；悬停用阴影即可 */
.page-dashboard {
  padding-top: 2px;
}
.dash-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
  overflow: visible;
}
.dash-stat-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 20px 18px;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: border-color 0.2s, box-shadow 0.2s;
  box-shadow: var(--shadow-card);
}
.dash-stat-card:hover {
  border-color: rgba(129, 140, 248, 0.5);
  box-shadow:
    var(--shadow-card),
    0 0 0 1px rgba(129, 140, 248, 0.2),
    0 10px 28px -12px rgba(99, 102, 241, 0.35);
}
.icon-wrap {
  width: 42px; height: 42px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  margin-bottom: 14px;
}
.val {
  font-size: 28px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  color: var(--text-main);
  letter-spacing: -0.03em;
  line-height: 1.1;
}
.lbl {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 6px;
  font-weight: 500;
}
.dash-panels {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.dash-panel {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 24px;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: var(--shadow-card);
}
.dash-panel h3 {
  margin: 0 0 18px;
  font-size: 16px;
  font-weight: 700;
  color: var(--text-main);
  display: flex;
  align-items: center;
  gap: 8px;
}
.dash-panel h3 i {
  color: var(--primary);
}
.quick-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.action-btn {
  padding: 12px 18px;
  border-radius: 12px;
  border: 1px solid var(--border);
  background: var(--input-bg);
  color: var(--text-main);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: var(--trans);
  display: flex;
  align-items: center;
  gap: 10px;
  font-family: inherit;
}
.action-btn:hover {
  border-color: var(--primary);
  color: var(--primary);
  transform: translateY(-1px);
}
.action-btn i {
  font-size: 18px;
}
.action-primary {
  background: linear-gradient(135deg, var(--primary) 0%, var(--primary-hover) 100%);
  color: #fff;
  border-color: var(--primary);
  box-shadow: 0 4px 10px -2px rgba(99, 102, 241, 0.4);
}
.action-primary:hover {
  color: #fff;
  box-shadow: 0 8px 15px -3px rgba(99, 102, 241, 0.5);
}

@media (max-width: 900px) {
  .dash-stats { grid-template-columns: repeat(2, 1fr); }
  .dash-panels { grid-template-columns: 1fr; }
}
@media (max-width: 480px) {
  .val { font-size: 22px; }
  .dash-stat-card { padding: 16px 14px; }
}
</style>
