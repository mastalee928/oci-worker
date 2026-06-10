# Page Agent 集成 — 探讨记录（已放弃）

**状态**：**不集成**，后续默认勿再推进。**未授权实现**，开发仓勿擅自开工。  
**记录时间**：2026-05  
**参考**：[alibaba/page-agent](https://github.com/alibaba/page-agent)（页内 GUI Agent，自然语言控 DOM，MIT）

---

## 用户结论

> 好的，放弃 page-agent

---

## 为何不适合 OCI Worker（讨论摘要）

| 点 | 说明 |
|----|------|
| 安全 | 面板含终止实例、删租户、放行端口等；DOM Agent 误操作代价高 |
| UI | Ant Design Vue + 抽屉/折叠/异步；70+ 租户场景 Agent 易点错 |
| 能力重叠 | 抢机/任务等应走后端 API，比模拟点击更稳 |
| 隐私 | 自托管环境不宜用 Demo CDN 把 DOM 发给外部 LLM |
| 与 Oracle AI 关系 | Oracle AI = OCI 生成式 **网关**；Page Agent = **页面自动化**，层次不同，不宜绑在一起 |

---

## 若将来有人再提

- 默认答复：**已放弃**；优先做分组 UX、实例列表性能、后端工作流等
- 若坚持「对话式 Copilot」，应评估 **后端 tool calling**，而非引入 Page Agent DOM 方案
- 须用户 **重新明确授权** 且单独立项（安全白名单、LLM 配置、默认关闭）才可 reopen

---

## 相关备忘

- 中转隧道搁置：`.cursor/relay-tunnel-exploration-deferred.md`
- 分组展示顺序容后：`.cursor/deferred-tenant-group-display-order.md`
