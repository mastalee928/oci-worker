<template>
  <section class="planned-module">
    <header>
      <div class="module-icon"><i class="ri-node-tree"></i></div>
      <div>
        <div class="module-status">接口研究阶段</div>
        <h4>组织管理</h4>
        <p>用于查看父子租户关系、邀请状态和订阅映射。入口对所有账户可见，创建资格最终由 Oracle 订阅类型决定。</p>
      </div>
    </header>
    <div class="capability-list">
      <div><i class="ri-building-4-line"></i><span><strong>租户关系</strong>展示父租户、子租户、状态、OCID 与监管关系。</span></div>
      <div><i class="ri-user-add-line"></i><span><strong>创建与邀请</strong>创建子租户或邀请已有租户加入组织。</span></div>
      <div><i class="ri-links-line"></i><span><strong>订阅映射</strong>管理组织成员与 Universal Credits 订阅的映射关系。</span></div>
    </div>
    <a-alert type="info" show-icon message="空组织是正常状态" description="普通或免费账户可以看到组织入口，但提交创建时可能由 Oracle 返回订阅类型不支持。当前未开放写操作。" />
  </section>
</template>

<style scoped>
.planned-module header { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
.module-icon { display: grid; width: 38px; height: 38px; flex: 0 0 auto; place-items: center; border-radius: 10px; color: var(--primary); background: color-mix(in srgb, var(--primary) 11%, transparent); font-size: 20px; }
.module-status { color: var(--primary); font-size: 11px; font-weight: 700; }
.planned-module h4 { margin: 2px 0 4px; color: var(--text-main); font-size: 16px; }
.planned-module p { margin: 0; color: var(--text-sub); font-size: 13px; line-height: 1.6; }
.capability-list { display: grid; gap: 8px; margin-bottom: 14px; }
.capability-list > div { display: grid; grid-template-columns: 28px minmax(0, 1fr); align-items: start; gap: 8px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--input-bg); }
.capability-list i { color: var(--primary); font-size: 17px; }
.capability-list span { color: var(--text-sub); font-size: 12px; line-height: 1.55; }
.capability-list strong { margin-right: 6px; color: var(--text-main); }
</style>
