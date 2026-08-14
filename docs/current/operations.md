# Current Operations

## 文档状态

本文是默认分支当前运维边界，不是某个发布版本的 Runbook。部署前必须同时核对 [`capability-status.md`](capability-status.md)、[`compatibility.md`](compatibility.md) 和真实 Release 快照。

没有 Release 或 Production Supported 声明时，不得把默认分支、测试通过或正式验收解释为生产推广授权。

## 生产前置条件

- 使用 Compatibility 中列出的 Java、Spring Boot、Flowable、Node 和数据库组合；
- 应用二进制、配置、Flyway locations 和 Release manifest 来自同一发布；
- 使用可信认证集成建立服务端 principal；
- 限制 Management API、Actuator、数据库、对象存储和 Secret Manager 网络访问；
- Flowable 自动 schema update 在生产关闭；
- 具备同一恢复点的平台表与 Flowable 表备份；
- 完成恢复演练、升级前后数据一致性验证和明确的 operator ownership；
- 所需能力在 Capability Status 中明确达到相应发布与生产支持状态。

## 启动与数据库

1. 验证可恢复备份和恢复责任人；
2. 验证应用、配置、Release manifest 和制品摘要；
3. 验证所有 Flyway locations，与生成的组合拓扑一致；
4. 单实例受控启动并执行 Flyway validate/migrate；
5. 验证最终 schema history、平台约束和 Flowable schema；
6. 验证 principal-backed authentication、tenant isolation 和 Management authorization；
7. 验证默认关闭的迁移、Connector、Provider 和自动化能力没有被环境误启用；
8. 检查 readiness、metrics、Outbox、lease、UNKNOWN 和 dead-letter 状态；
9. 逐步加入其余无状态节点。

不得通过修改已应用 migration、手工补平台记录或直接编辑 Flowable 表来“修复”启动失败。

## 身份和授权

生产身份、租户、操作人、权限、资源范围、request/trace 和高风险操作证据由服务端建立。浏览器、Mobile、SDK、Connector 或 AI payload 不能覆盖它们。

高风险管理操作至少需要：

- 服务端授权结果；
- bounded operation reason；
- idempotency evidence；
- server-owned request/trace evidence；
- 可持久化的治理审计。

认证、授权或审计不可用时必须 fail closed，不能临时改用客户端 header 或关闭拦截器。

## Process migration

以下执行类能力必须保持安全默认值，除非独立生产授权明确允许：

```text
APPROVAL_MIGRATION_EXECUTION_ENABLED=false
APPROVAL_MIGRATION_WORKER_ENABLED=false
APPROVAL_MIGRATION_ORCHESTRATION_ENABLED=false
APPROVAL_MIGRATION_AGGREGATION_ENABLED=false
APPROVAL_MIGRATION_RECONCILIATION_AUTOMATIC_ENABLED=false
APPROVAL_MIGRATION_KILL_SWITCH_ENABLED=false
```

运维规则：

- 只接受精确、不可变、已授权且重新校验的计划；
- 每个实例有独立 claim、attempt 和 terminal evidence；
- 引擎调用发生在平台数据库事务之外；
- timeout 或 I/O ambiguity 进入 durable UNKNOWN，禁止自动重试；
- reconciliation 只读核对，不重新 dispatch；
- runtime binding 只通过精确 CAS 更新；
- Canary、bounded orchestration 和 plan aggregation 不能扩大实例范围；
- 合并或验收不代表真实生产迁移已经授权。

## Connector and external delivery

- Secret Material 只从受控 Secret Manager 获取，不进入日志、客户端或持久业务证据；
- credential reference、tenant routing 和 provider identity 由服务端绑定；
- Token refresh、single-flight、timeout、retry classification 和 diagnostics 必须有边界；
- UNKNOWN 外部结果不能伪装为失败后盲目重试；
- SDK/Event 契约不等于 durable subscription、delivery Worker、Broker 或自动恢复已可生产；
- 未完成 operated ownership、限流、告警、积压恢复和回放演练前，不得推广生产流量。

## AI Provider and assistance

- Provider 运行时默认关闭，只有明确配置和环境授权才能建立真实调用；
- 生成必须由显式用户动作触发，每次请求保持受控 attempt 上限；
- 字段权限、最小化和脱敏必须发生在 Provider dispatch 前；
- Provider 返回后重新读取并校验任务、Release、Form 和 UI Schema；
- post-dispatch timeout/I/O ambiguity 进入 UNKNOWN，不允许第二次 Provider 调用；
- 持久证据只保存 bounded hash、版本、分类、计数和 lineage；
- 输出始终作为 advisory 展示并要求人工核对；
- Provider 不得直接调用审批命令；
- Action whitelist 为空或生产重新认证不可用时，受控自动化必须 fail closed；
- 未完成客户环境、egress、成本、retention、on-call、incident 和发布批准前，不得声明生产支持。

## Health, metrics and logs

监控至少覆盖：

- 应用、数据库、Flyway 和 trusted identity；
- Outbox、Connector backlog、Token/Secret source 和外部调用状态；
- migration claim、lease、UNKNOWN、reconciliation 和 plan aggregation；
- AI Circuit、RateLimiter、usage posture、UNKNOWN 和 durable evidence；
- audit、idempotency、runtime binding 和 consistency findings。

Metrics 只使用闭集低基数标签。禁止把 tenant、operator、instance、task、plan、request、trace、hash、Secret、Prompt 或任意错误文本作为 metric tag。

日志不得包含完整表单值、原始 Prompt、原始 Provider 响应、凭据、完整权限集合、任意 SQL 或 Flowable 内部表内容。

## 备份与恢复

备份必须在同一恢复点覆盖：

- 平台领域表、投影、Outbox 和 idempotency；
- DSL、Form、UI、Release、deployment 和 runtime binding；
- SLA、migration intent/attempt/verification/reconciliation/aggregation；
- Connector credential reference、Token/dispatch evidence；
- AI advisory、governance 和 controlled-automation lineage；
- audit、event、dead-letter 和 retention evidence；
- Flowable 管理的引擎表。

恢复后：

1. 执行 Flyway validate 并核对组合版本；
2. 抽样验证不可变 hash、foreign key、tenant lineage 和 append-only 约束；
3. 核对 ACTIVE release、deployment 和 engine identity；
4. 检查 runnable queue、过期 lease、UNKNOWN 和 dead-letter；
5. 只执行只读 consistency、diagnostics 和 reconciliation 检查；
6. 不自动 redeploy、reactivate、replay、migrate、invoke Provider 或执行自动化命令。

备份只有在真实恢复演练通过后才算有效。

## 升级与回滚

升级前必须：

- 核对 Compatibility、Capability Status、Release manifest、SBOM 和制品摘要；
- 执行完整永久验证和数据库升级路径；
- 保存上一应用镜像和已验证备份；
- 验证所有节点使用同一应用、编译器、Flyway 和协议版本；
- 演练停线、UNKNOWN、Connector/Outbox 堆积和恢复流程。

应用二进制回滚只有在旧版本理解新版本已经写入的 schema 和协议证据时才安全。Flyway 不支持向下重写。无法兼容回滚时，应使用受控恢复或 forward fix。

回滚应用不等于回滚 Release、迁移实例、撤销外部调用或删除 AI/Connector 证据。

## 生产阻塞原则

以下任一条件存在时，都不能把能力描述为 Production Supported：

- 没有真实 Release；
- 数据库或运行时组合未完整接受；
- 生产身份、Secret、egress 或网络边界未验证；
- 没有 named operator、on-call、告警、容量和恢复演练；
- UNKNOWN、积压、retention、rollback 或 incident response 只停留在设计/只读阶段；
- 自动化缺少合格 Application Command、生产重新认证或明确 whitelist；
- 安全告警清单、依赖适用性或供应链证据不完整。