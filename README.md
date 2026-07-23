# Approval Platform

[中文](#项目定位) | [English](#english-summary)

生产级、可独立部署的通用审批与流程协作平台。平台以 Approval DSL、Form Schema 和平台自有治理证据为产品模型，通过正式 Engine SPI 使用 Flowable 作为流程执行内核。

> **当前正式基线（2026-07-23）**：M4 已完成正式治理验收并通过 PR #55 合并到 `main`。当前合并提交为 `58efb4255394fe3911700719669c4423a3ab212e`，Flyway 迁移连续至 `V32`，最终永久验证 Run `29986751894` 为 `success`。

## 项目定位

Approval Platform 不是业务系统中的简单审批组件，也不是 Flowable API 或内部表的直接封装。它提供独立的审批产品层、运行时治理、审计证据和多端交互，并通过 REST、Webhook、SDK 与连接器接入宿主系统。

核心原则：

- 业务系统不直接依赖 Flowable API；
- 生产代码不查询或修改 Flowable `ACT_*` 内部表；
- Approval DSL 是产品流程模型，BPMN/DMN 是确定性编译产物；
- Form Schema 与 UI Schema 独立于具体前端组件；
- 身份、租户、权限、运行时绑定和审计证据由服务端权威产生；
- 浏览器和 Mobile 端只提供业务输入，不能制造可信执行上下文；
- 外部连接器或引擎调用不被伪装成平台数据库原子事务；
- 历史版本、执行尝试、审计链和治理验收记录保留可追踪证据。

## 技术基线

- 后端：Java 21、Spring Boot 4、Flowable 8；
- PC：Vue 3、TypeScript、Vben Admin、Element Plus；
- 移动端：UniApp Vue 3、Unibest、Wot UI；
- 数据库：PostgreSQL 16 验证基线、Flyway V1–V32；
- 构建：Maven 多模块、pnpm workspace；
- CI：`.github/workflows/approval-platform-validation.yml`；
- License：Apache License 2.0。

## 当前能力

### 流程设计与发布

- Approval DSL 与递归树设计器；
- 静态验证、路径模拟和确定性 BPMN/DMN 编译；
- Form Schema、UI Schema、字段权限和多端 Renderer；
- immutable Form Package 与 Approval Release Package；
- 发布预检、部署证据、生效版本和安全导入导出；
- PC、H5 与微信小程序的一致运行时语义。

### 审批运行与协作治理

- 发起、同意、拒绝、退回、撤回、取回和重提；
- 动态审批人解析与责任快照；
- 委派、离职交接、加签、减签和并发决策治理；
- 评论、回复、@人员、附件与可见范围；
- 通知意图、投递尝试、重试、死信和受治理 replay；
- 任务投影、幂等命令、业务回调和完整时间线。

### 审计与运维治理

- tenant-scoped 版本化审计事件和哈希链；
- detect-only 一致性检查；
- 统一 operational failure 视图和受治理 replay；
- PostgreSQL 事务、并发、索引和 `EXPLAIN (FORMAT JSON)` 验证；
- 结构化错误、requestId/traceId 和低基数指标；
- 唯一永久 CI workflow 与仓库边界测试。

### M4 企业级治理

M4 已交付并正式验收：

- `ApprovalPrincipal`、默认 fail-closed 的生产身份模式和租户隔离；
- 人员、部门、岗位、角色、用户组的统一责任来源；
- 管理 capability、资源 scope 与审批参与权的严格分离；
- 高风险操作的 bounded reason、幂等键和先行审计证据；
- tenant-scoped 版本化工作日历与不可变 SLA policy；
- 自然时间、工作时间、时区和 DST 计算；
- SLA instance、责任历史、执行 intent、append-only attempt、lease、retry、DEAD 和 replay；
- 流程 Release Lifecycle：`DRAFT`、`PUBLISHED`、`ACTIVE`、`DEPRECATED`、`RETIRED`；
- 同 tenant + definition 最多一个 `ACTIVE` release；
- 新实例精确绑定当前 ACTIVE release，已有实例保留原 runtime binding；
- 缺失或冲突 runtime binding 时 fail closed；
- detect-only 运行实例迁移评估、确定性 `reportHash` 与 Web 报告页。

## 重要能力边界

当前**没有**运行中流程实例迁移执行能力。

现有 `/approval/versions/migration-dry-run` 和对应服务端接口只生成非变更评估证据，不会：

- 执行或强制迁移；
- 修改 Flowable 运行时状态；
- 重新绑定实例；
- 自动重试未知迁移结果；
- 提供 rollback、execute、force 或 apply 操作。

真实迁移执行必须在后续 M5 中先验证 Flowable 官方公开 API，再设计不可变计划、授权、执行 intent、attempt、verification 和 reconciliation 协议。

## Monorepo 结构

```text
approval-platform/
├── apps/
│   ├── server/
│   ├── web/
│   ├── mobile/
│   └── docs-site/
├── server-modules/
├── packages/
├── connectors/
├── deploy/
├── examples/
├── docs/
└── scripts/
```

## 里程碑状态

| 阶段 | 状态 | 主要内容 |
| --- | --- | --- |
| M1 | 已形成生产形态能力 | 采购付款审批纵向切片与多端运行 |
| M2 | 已验收 | Approval DSL、设计器、动态表单、发布与版本治理 |
| M3 | 已验收并合并 | 协作、委派、通知、审计、一致性与运维恢复治理 |
| M4 | 已验收并合并 | 身份租户、企业授权、SLA、执行 replay、Release Lifecycle 与迁移评估 |
| M4.1 | 进行中 | 合并后 living documentation 对齐 |
| M5 | 规划中 | 受治理的运行实例迁移可行性、执行、验证与 reconciliation |

Issues #13 和 #14 当前仍保持 Open；Issue 状态不替代已提交代码、治理文档和永久 CI 证据。

## Roadmap

### M5 — Governed Process Instance Migration

计划按阶段推进：

1. 验证 Flowable 官方迁移 API 与安全支持矩阵；
2. 设计 immutable migration plan 和权威 activity mapping；
3. 建立 authorization、execution intent、attempt 和 lease；
4. 实现调用后 verification 与 UNKNOWN reconciliation；
5. 提供默认关闭、服务端专用的受治理执行器；
6. 完成故障注入、安全、并发、运维和正式验收。

M5-A 验证完成前，不增加生产迁移端点、迁移 worker、V33 执行模型或前端执行按钮。

AI 辅助审批、更多连接器、SDK 和模板生态仍是后续产品方向，但不再错误地作为已经开始的 M4/M5 定义。

## 文档

从 [`docs/README.md`](docs/README.md) 进入完整文档索引。

主要 living docs：

- [`docs/PRODUCT_CHARTER.md`](docs/PRODUCT_CHARTER.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/PROCESS_DSL.md`](docs/PROCESS_DSL.md)
- [`docs/FORM_SCHEMA.md`](docs/FORM_SCHEMA.md)
- [`docs/OPERATIONS.md`](docs/OPERATIONS.md)

验收入口：

- [`docs/M3_FINAL_ACCEPTANCE.md`](docs/M3_FINAL_ACCEPTANCE.md)
- [`docs/M4_FINAL_ACCEPTANCE.md`](docs/M4_FINAL_ACCEPTANCE.md)

## English Summary

Approval Platform is a production-oriented standalone approval and workflow collaboration platform built around Approval DSL, Form Schema, platform-owned governance evidence and a formal Flowable engine boundary.

M4 has been formally accepted and merged. The current baseline includes principal-backed tenant identity, enterprise authorization, versioned work calendars, immutable SLA policies, durable SLA execution and replay, process release lifecycle governance, immutable runtime binding and detect-only process-instance migration assessment.

Real process-instance migration execution is not available yet. It is planned as a separately governed M5 capability with official-engine API validation, immutable plans, server-side execution, verification and reconciliation.

## License

Apache License 2.0
