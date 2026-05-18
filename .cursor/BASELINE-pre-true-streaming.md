# 真流式改动前基线（可还原）

- **Git 标签**：`pre-true-streaming`（指向改动前最后一次提交）
- **还原业务代码**：`git checkout pre-true-streaming -- backend/src/main/java/com/ociworker/service/OciGenerativeOpenAiService.java backend/src/main/java/com/ociworker/controller/OracleAiController.java frontend/src/views/OracleAI.vue`

## 真流式定义（非「接近」）

- 请求 OCI 时 `stream: true` + `Accept: text/event-stream`
- Multi-Agent **流式**走官方 `openai/v1/responses`（非 raw `/v1` 整包 JSON）
- 网关用 `longCopyStream` 按 OCI SSE 事件边读边写；chat 客户端再转译为 chat chunk
- 若 OCI 返回非 SSE，打 warn，**不**再整包后假分块
