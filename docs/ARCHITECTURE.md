# 总体架构

## 文档状态

本文描述 M4 合并后的当前系统架构，是 living document。

当前正式基线：

- `main`：`58efb4255394fe3911700719669c4423a3ab212e`；
- M4 PR：#55，Merged / Closed；
- Flyway：V1–V32；
- 永久验证 workflow：`.github/workflows/approval-platform-validation.yml`。

详细验收范围见 [`M4_FINAL_ACCEPTANCE.md`](M4_FINAL_ACCEPTANCE.md)。

## 系统形态

Approval Platform 是独立审批产品，不作为 RuoYi 或其他宿主项目的内嵌工作流模块。

```text
Host Systems / SSO / Directory / Business Services
                │
        REST · Webhook · SDK · Connector
                │
                ▼
Trusted Identity & Integration Boundary
  authenticated ApprovalPrincipal · tenant scope
                │
                ▼
Approval API and Client Surfaces
  Participant API · Management API · Web · Mobile
                │
                ▼
Application Orchestration and Governance
  command · query · authorization · idempotency · audit
                │
       ┌────────┴─────────┐
       ▼                  ▼
Approval Domain       Platform-owned Persistence
  process/form/task      PostgreSQL · Flyway · Outbox
  SLA/release/binding    immutable evidence · projections
       │                  │
       ▼                  ▼
Engine SPI          Worker / Connector Ports
       │                  │
       ▼                  ▼
Flowable public API   external systems and side effects
```

宿主系统和前端不直接调用 Flowable。Flowable 只通过正式 Engine SPI 和公开 API 参与执行。

## 分层与模块边界

### Domain

纯 Java 领域模型和业务规则。Domain 不依赖 Spring、Flowable、数据库或宿主框架。

当前领域包含：

- Approval DSL、定义版本和发布证据；
- Form、UI、permission 和 Form Package；
- instance、task、delegation、handover 和 collaboration；
- notification、comment、attachment 和 audit contract；
- work calendar、SLA policy、SLA instance 和责任历史；
- process release lifecycle 和 runtime binding。

### Application

Application 编排用例和业务事务边界，只依赖 Domain 与 ports。

职责包括：

- command/query orchestration；
- 权威状态重新读取与校验；
- tenant 和业务资源隔离；
- 幂等命令和冲突检测；
- 审计、Outbox 和治理证据；
- SLA 创建、同步、执行计划和 worker 决策；
- release publication、activation、disposition 和 migration assessment；
- external-call 前后状态机和恢复语义。

### API and security

Server API 提供 participant 和 management 两类边界。

- `ApprovalIdentityContextFilter` 从可信认证集成获得 `ApprovalPrincipal`；
- tenant、operator、authorities、requestId 和 traceId 由服务端权威包装；
- management handler 必须声明明确 capability 和 resource scope；
- participant access 使用 task、initiator、delegation、handover、collaboration 和 visibility evidence；
- management role 不等于任务参与权；
- route authority、按钮状态和客户端隐藏仅是体验层，不是安全边界。

生产默认身份模式为 `principal`。`local-headers` 只允许显式 local/test profile。浏览器和 Mobile 不能制造可信 tenant、operator 或 permission。

### Persistence

PostgreSQL 保存平台领域状态、查询投影和治理证据。

关键规则：

- 敏感记录全部 tenant scoped；
- immutable version、attempt、replay、audit 和 activation history 不被普通 update 覆盖；
- 并发通过 unique constraint、optimistic version、conditional update、advisory lock 或 row lock 处理；
- Flyway migration 一经提交和应用不得重写；
- 生产应用代码不查询或修改 Flowable `ACT_*` 表；
- consistency、replay 和 migration assessment 只使用平台自有记录和公开 application ports。

### Engine SPI

Engine SPI 隔离产品语义和引擎实现。

当前已验收的引擎边界包括：

- 部署确定性编译产物；
- 启动精确 engine definition；
- 办理审批任务；
- 暂停、恢复和终止等已实现操作；
- 通过公开 Flowable API 获取必要执行结果。

“迁移”作为产品目标并不表示真实运行实例迁移已经可用。M4 只提供 detect-only assessment。M5 必须先验证官方迁移 API，禁止使用内部表或未公开实现类绕过。

### Connector

Connector 负责：

- 组织、人员和责任来源；
- 文件和对象存储；
- 通知和第三方消息；
- 业务回调和第三方待办；
- 宿主系统集成。

连接器不得改变审批核心状态机。外部调用失败必须归属到明确 intent/attempt/Outbox 状态，不得通过覆盖原始证据伪装成功。

## M4 权威边界

### 身份与租户

`ApprovalPrincipal` 保存：

- tenantId；
- operatorId；
- bounded authorities；
- account enabled state；
- optional session expiry；
- server-resolved enterprise responsibility evidence。

所有 `/api/approval/**` 请求先建立 server-owned request context。伪造 header 不能覆盖 principal 中的租户、操作人或权限。

### 企业授权与责任

统一责任来源支持：

- person；
- department；
- position；
- role；
- user group。

中央 resolver 将 closed enterprise roles 映射到 management capabilities，并结合 platform、tenant 或 department resource scope 做确定性决策。

高风险 capability 在进入业务 handler 前要求：

- 允许的服务端授权结果；
- bounded operation reason；
- valid idempotency key；
- trusted requestId/traceId；
- 成功写入先行治理审计。

任一证据失败均 fail closed。

## 关键运行模型

### 流程发布与 Release Lifecycle

发布链路：

```text
Design Draft
   │ preflight + deterministic compile
   ▼
Immutable Definition / Form Package / Release Package
   │ explicit deployment
   ▼
Platform Deployment Evidence + Engine Identity
   │ governed lifecycle transition
   ▼
PUBLISHED → ACTIVE → DEPRECATED → RETIRED
```

允许的 lifecycle 是闭合状态机。同一 tenant + definition 最多一个 `ACTIVE` release。

有效版本切换在平台数据库事务中同时处理：

- prior lifecycle disposition；
- target activation；
- effective-release projection；
- immutable activation history；
- audit and idempotency evidence。

### Release-bound start 和 runtime binding

新实例启动流程：

1. 从 authenticated tenant 解析 exact effective release；
2. 校验 lifecycle 为 `ACTIVE`；
3. 校验 Release Package 和 deployment evidence 一致；
4. 使用精确 engine definition 启动；
5. 在平台事务中持久化 instance、task、audit、idempotency 和 immutable runtime binding；
6. 后续 read/replay 校验 runtime binding 和平台投影一致性。

已有实例不会因为 effective release 切换而被重新绑定。缺失或冲突 binding 时 fail closed。legacy 未绑定实例可以兼容读取，但不能伪造 release evidence。

### SLA 设计与运行

SLA 设计证据：

```text
Work Calendar Identity
   └── immutable Calendar Version
SLA Policy Identity
   └── immutable Policy Version → exact Calendar Version
```

运行证据：

```text
Approval / Task / Collaboration lifecycle
                │
                ▼
          SLA Instance
  authoritative due/reminder/overdue
                │
                ▼
       Execution Intent Queue
 READY · CLAIMED · RETRY_WAIT · SUCCEEDED · DEAD · CANCELLED
                │
                ▼
     append-only Execution Attempt
```

worker 使用 bounded tenant batch、`FOR UPDATE SKIP LOCKED`、lease 和 CAS。claim 在短事务中完成，外部 connector 或业务 action 在事务外执行，结果再通过独立短事务记录。

UNKNOWN 或 unsupported action 不得伪装为成功。`DEAD` replay 创建新 intent 和 immutable replay evidence，不修改原始 intent/attempt。

### Detect-only migration assessment

M4 的迁移评估流程：

1. 读取 source/target release lifecycle 和 package evidence；
2. 校验 target deployment；
3. 读取 source-bound runtime bindings；
4. 读取平台 instance 和 active task projections；
5. 对比 source/target definition topology；
6. 输出 global findings、per-instance decision、bindingEvidenceHash 和 deterministic reportHash；
7. 证明业务和引擎状态没有被修改。

该流程不生成 executable plan，不调用引擎迁移命令，不修改 runtime binding，也不提供 execute/force/rollback。

## 事务与外部调用模型

平台数据库事务只保证平台自有记录的原子性。

- 审批投影、SLA 同步、审计和幂等证据可以共享同一平台事务；
- worker claim、external call 和 outcome 分为多个短边界；
- 引擎或连接器调用不能与 PostgreSQL 共享原子事务；
- 调用超时不能直接推断为失败；
- 任何未来 migration execution 必须显式建模 `NOT_STARTED`、`SUCCEEDED`、`FAILED`、`UNKNOWN`、`VERIFYING` 和 `RECONCILING` 等语义。

## 可观测性

结构化日志和 trace 可以包含 bounded tenant、operator、requestId、traceId、resource identity 和错误证据。

metrics 只使用闭集低基数标签。禁止将以下值作为 metric tag：

- tenantId；
- operatorId / userId；
- instanceId / taskId；
- definition key / release version；
- requestId / traceId；
- plan or binding hash；
- arbitrary error message or reason。

M4 主要指标包括：

- `approval.management.authorization`；
- `approval.management.request.duration`；
- `approval.sla.execution.worker`；
- `approval.release.lifecycle.operation`；
- `approval.release.migration.assessment`；
- `approval.runtime.binding.validation`。

## 部署形态

### 单体模块化部署

适合当前阶段和中小规模：

- 无状态 API；
- PostgreSQL；
- Flowable engine；
- 可选 Redis、对象存储和外部 connector；
- 持久化 worker 状态与幂等证据。

### 水平扩展

增加节点前必须确保：

- 所有节点使用相同应用、compiler 和 migration 版本；
- 共享同一平台数据库和可信身份集成；
- worker claim/lease 使用相同数据库时钟和配置；
- Actuator、数据库和凭据只暴露给受控网络；
- 连接器支持传入的 idempotency evidence；
- 不启用每节点隐式 Flowable schema update。

后续可以按消息、报表、AI 或运维 worker 拆分，但不提前微服务化，也不允许拆分削弱事务、审计和 tenant 边界。

## 永久禁止项

- 生产应用查询或修改 Flowable `ACT_*` 表；
- 客户端制造 trusted permission、tenant、operator、audit reference、worker、lease owner 或 engine identity；
- management role 自动获得 task participation；
- 任务参与人自动获得 management capability；
- 外部调用置于长数据库事务；
- 删除或覆盖原始 attempt、replay、audit、runtime binding 或 activation history；
- 将 detect-only report 描述为 migration execution；
- 在没有官方 API 验证和 reconciliation 的情况下自动重试未知迁移结果。

## 相关文档

- [`PRODUCT_CHARTER.md`](PRODUCT_CHARTER.md)
- [`PROCESS_DSL.md`](PROCESS_DSL.md)
- [`FORM_SCHEMA.md`](FORM_SCHEMA.md)
- [`OPERATIONS.md`](OPERATIONS.md)
- [`M4_FINAL_ACCEPTANCE.md`](M4_FINAL_ACCEPTANCE.md)
- [`README.md`](README.md)
