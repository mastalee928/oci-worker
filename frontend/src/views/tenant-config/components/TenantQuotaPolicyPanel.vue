<template>
  <section class="planned-module">
    <header>
      <div class="module-icon"><i class="ri-speed-up-line"></i></div>
      <div>
        <div class="module-status">接口研究阶段</div>
        <h4>限额策略</h4>
        <p>此模块将区分 Oracle 官方 Quota Policy 与 OCIWorker 自有流量保护规则，避免把两种规则混为一谈。</p>
      </div>
    </header>
    <div class="boundary-grid">
      <article>
        <strong>Oracle 官方策略</strong>
        <span>限制资源数量，或将指定服务与资源配额设置为零。</span>
      </article>
      <article>
        <strong>OCIWorker 流量保护</strong>
        <span>按统计周期监测网络流量，超限后执行停止实例等自动化动作。</span>
      </article>
    </div>
    <a-alert type="info" show-icon message="当前未开放写操作" description="正式接入前仍需确认 Governance Rules API、策略挂载关系、权限与 Work Request 行为。" />
  </section>
</template>

<style scoped>
.planned-module header { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
.module-icon { display: grid; width: 38px; height: 38px; flex: 0 0 auto; place-items: center; border-radius: 10px; color: var(--primary); background: color-mix(in srgb, var(--primary) 11%, transparent); font-size: 20px; }
.module-status { color: var(--primary); font-size: 11px; font-weight: 700; }
.planned-module h4 { margin: 2px 0 4px; color: var(--text-main); font-size: 16px; }
.planned-module p { margin: 0; color: var(--text-sub); font-size: 13px; line-height: 1.6; }
.boundary-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-bottom: 14px; }
.boundary-grid article { padding: 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--input-bg); }
.boundary-grid strong, .boundary-grid span { display: block; }
.boundary-grid strong { margin-bottom: 5px; color: var(--text-main); font-size: 13px; }
.boundary-grid span { color: var(--text-sub); font-size: 12px; line-height: 1.55; }
@media (max-width: 768px) { .boundary-grid { grid-template-columns: 1fr; } }
</style>
