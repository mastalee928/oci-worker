# OCI Network Load Balancer（NLB）接入计划与验收记录

更新时间：2026-08-08

## 1. 项目状态

- 状态：独立模块实现完成，本地构建与自动化测试通过。
- OCI SDK：`oci-java-sdk-networkloadbalancer 3.83.0`。
- 交付范围：NLB、Listener、Backend Set、Health Checker、Backend、健康状态、Work Request 全部包含读写能力。
- 尚需部署环境执行的事项：使用具有真实 OCI 权限的租户做一次小流量冒烟验收。该事项依赖外部 OCI 账号、配额、IAM Policy 和可用子网，不是代码功能缺口。

## 2. 固定产品约束

以下约束已经落实，后续维护不得擅自改变：

1. 入口固定为：

   `实例管理 → 某租户 → 虚拟云网络 → 某个 VCN → 负载均衡器`

2. VCN 标签顺序固定为：

   `子网 | Internet 网关 | NAT 网关 | 服务网关 | LPG | 路由表 | 安全列表 | 负载均衡器`

3. 不新增左侧菜单。
4. 不新增独立前端路由。
5. 不创建顶层 NLB 页面。
6. 不修改 Oracle AI 负载均衡页面、接口、服务、配置或数据库。
7. 必须是独立模块：
   - 后端：`com.ociworker.nlb`
   - 前端：`frontend/src/modules/nlb`
8. `VcnManager.vue` 只负责挂载模块并传递租户、Region、Compartment 和 VCN 上下文。

## 3. 独立模块结构

### 后端

- `backend/src/main/java/com/ociworker/nlb/NetworkLoadBalancerController.java`
- `backend/src/main/java/com/ociworker/nlb/NetworkLoadBalancerService.java`
- `backend/src/main/java/com/ociworker/nlb/model/NlbRequests.java`
- `backend/src/main/java/com/ociworker/nlb/support/NlbSdkDetailsFactory.java`
- `backend/src/main/java/com/ociworker/nlb/support/NlbSdkMapper.java`
- `backend/src/test/java/com/ociworker/nlb/`

统一接口前缀：`/api/oci/nlb`。

### 前端

- `frontend/src/api/nlb.ts`
- `frontend/src/modules/nlb/types.ts`
- `frontend/src/modules/nlb/NetworkLoadBalancerPanel.vue`

唯一宿主变更：

- `frontend/src/views/VcnManager.vue`

## 4. 已实现能力

| 资源 | 查询 | 创建 | 更新 | 删除 | 额外能力 |
| --- | --- | --- | --- | --- | --- |
| NLB | 列表、详情 | 是 | 名称及高级参数 | 是 | NSG、迁移区间、综合健康 |
| Listener | 列表、详情 | 是 | 是 | 是 | 协议、端口、默认 Backend Set、IP 版本、PPv2 |
| Backend Set | 列表、详情 | 是 | 是 | 是 | 策略、源地址保留、故障开放、快速故障转移 |
| Health Checker | 详情 | 随 Backend Set 创建 | 独立更新 | 随 Backend Set | TCP/HTTP/HTTPS 等 SDK 支持参数 |
| Backend | 列表、详情 | 是 | 是 | 是 | IP/目标 OCID、端口、权重、Drain、Backup、Offline |
| Work Request | 状态 | 不适用 | 轮询 | 不适用 | 进度、错误、日志、超时、取消轮询 |

补充实现：

- 列表处理 OCI 全部分页。
- 查询可访问区间中的 NLB，最终只保留子网属于当前 VCN 的资源；迁移 Compartment 后仍可在原 VCN 上下文继续管理。
- 当前区间查询失败会明确报错；其他不可访问区间会安全跳过。
- 创建表单只能选择当前 VCN 的子网。
- 创建请求使用稳定 `opc-retry-token`。
- 更新和删除尽量使用最新 ETag/`if-match`。
- 读请求有短时缓存，手动刷新使用 `force` 绕过缓存。
- 所有写操作提交后清理当前租户、Region 下的 NLB 缓存。
- 未知 SDK 枚举不会进入前端选项。
- Backend 权重限制为 0-100，IP 地址和目标 OCID 至少填写一项。

## 5. 前端交互

### 推荐创建顺序

1. 在当前 VCN 的“负载均衡器”标签创建 NLB。
2. 打开 NLB 的“管理”。
3. 创建 Backend Set，并配置 Health Checker。
4. 在 Backend Set 中添加 Backend。
5. 创建 Listener，将其默认 Backend Set 指向第 3 步的 Backend Set。
6. 观察 Work Request，确认状态为“已完成”。
7. 检查 NLB、Backend Set 和 Backend 健康状态。

### 异步操作语义

- 写接口返回“已提交”及 Work Request ID，不会把“OCI 已接受请求”误报成最终成功。
- 前端约每 1.8 秒轮询一次。
- 单轮轮询上限为 120 秒；超时后停止自动轮询，但 OCI 后台任务仍可能继续。
- Work Request 失败或打开详情时，会读取 OCI 错误和日志。
- 网络查询异常同样受 120 秒截止限制，不会无限重试。

### 危险操作保护

以下操作要求 Telegram 六位验证码：

- 删除 NLB
- 迁移 NLB Compartment
- 删除 Listener
- 删除 Backend Set
- 删除 Backend

删除 Backend Set 时会提示它可能仍被 Listener 引用。

## 6. 实际业务影响

- 创建公有 NLB 会产生公网暴露面和 OCI 费用；必须检查安全列表、NSG、路由和后端监听端口。
- 修改 Listener 的协议、端口或 Backend Set，可能立即改变生产流量入口。
- 修改 Health Checker 可能让后端被摘除或重新加入转发。
- Backend 的 Drain、Offline、Backup 和权重会直接影响流量分配。
- 删除 Listener、Backend Set、Backend 或 NLB 可能造成中断。
- 迁移 Compartment 不改变 VCN/子网转发关系，但会改变 IAM 权限边界；源、目标区间都必须授权。
- OCI 写操作具有最终一致性，Work Request 成功后列表和健康状态仍可能短暂延迟。

## 7. IAM Policy 基线

实际语句应按租户的用户组、区间层级和 OCI Policy Builder 调整。常用最小基线如下：

```text
Allow group <OCI_USER_GROUP> to manage network-load-balancers in compartment <NLB_COMPARTMENT>
Allow group <OCI_USER_GROUP> to read virtual-network-family in compartment <VCN_COMPARTMENT>
Allow group <OCI_USER_GROUP> to use network-security-groups in compartment <VCN_COMPARTMENT>
Allow group <OCI_USER_GROUP> to inspect compartments in tenancy
```

如果需要迁移 NLB，还需同时在源区间和目标区间授予 NLB 管理权限。若 NLB、VCN、子网或 NSG 分布在不同区间，应分别授权。

权限核对重点：

- NLB 及其 Listener、Backend Set、Backend 的管理权限。
- VCN、Subnet 的读取权限。
- NSG 的读取和使用权限。
- Compartment 的查看权限。
- NLB Work Request 的读取权限；通常随 NLB 管理权限提供，最终以 OCI Policy Builder 和租户实际返回为准。

## 8. 自动化验证结果

### NLB 定向测试

命令：

```powershell
mvn -s .mvn/codex-settings.xml -o '-Dtest=NlbSdkDetailsFactoryTest,NlbSdkMapperTest,NetworkLoadBalancerControllerTest,NetworkLoadBalancerServiceTest,NlbFrontendContractTest' test
```

结果：

- 13 个测试通过。
- 0 failures，0 errors，0 skipped。
- 覆盖 DTO/SDK 构建、映射、验证码控制器、分页、当前 VCN 过滤、跨区间可见性、缓存复用和写后失效、前端模块契约。

### 后端全量测试

命令：

```powershell
mvn -s .mvn/codex-settings.xml -o test
```

结果：

- 383 个测试通过。
- 0 failures，0 errors，0 skipped。

### 前端生产构建

命令：

```powershell
npm.cmd run build
```

结果：

- `vue-tsc -b` 通过。
- Vite 生产构建通过。

## 9. 禁止改动边界审计

已确认：

- `frontend/src/router` 无 NLB 路由变更。
- 左侧菜单和主布局无 NLB 入口。
- `frontend/src/views/OracleAI.vue` 无 NLB 相关改动。
- Oracle AI API、Service、配置和数据库无 NLB 相关改动。
- NLB 只在 VCN 管理抽屉的最后一个标签出现。

## 10. OCI 实租户冒烟清单

部署后建议使用非生产子网和测试后端执行：

1. 创建私有 NLB，等待 Work Request 成功。
2. 创建 Backend Set 和 TCP Health Checker。
3. 添加一个测试 Backend。
4. 创建 Listener。
5. 从允许的客户端验证端口转发。
6. 修改 Backend 权重、Drain 和 Offline，观察健康及流量变化。
7. 查看成功 Work Request 日志。
8. 故意提交一个无权限或冲突操作，确认错误和日志可见。
9. 更新 NSG。
10. 迁移到测试 Compartment，确认仍能在原 VCN 标签中找到并管理。
11. 按 Backend → Listener → Backend Set → NLB 的安全顺序清理测试资源。

## 11. 上下文恢复规则

若任务上下文被压缩，先读本文件，再检查：

1. 固定产品约束是否仍满足。
2. `git status` 中是否存在用户未提交改动，禁止回滚。
3. NLB 后端是否仍位于 `com.ociworker.nlb`。
4. NLB 前端是否仍位于 `frontend/src/modules/nlb`。
5. VCN 标签顺序、路由/菜单边界和 Oracle AI 零改动。
6. 最近一次测试和构建结果。
