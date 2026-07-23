# Approval Platform

[中文](#项目定位) | [English](#english-summary)

生产级、可独立部署的通用审批与流程协作平台。平台以 Approval DSL、Form Schema 和平台自有治理证据为产品模型，通过正式 Engine SPI 使用 Flowable 作为流程执行内核。

> **当前产品基线**：M4 已通过 PR #55 正式验收并合并；M4.1 living documentation 已通过 PR #59 完成同步，权威 Roadmap 已通过 PR #60 对齐。Flyway 连续至 `V32`。M5 正在 Issue #56 / Draft PR #58 中开发，M6 规划为 Ecosystem and AI。

## 项目定位

Approval Platform 不是业务系统中的简单审批组件，也不是 Flowable API 或内部表的直接封装。它提供独立的审批产品层、运行时治理、审计证据和多端交互，并通过 REST、Webhook、SDK 与连接器接入宿主系统。

核心原则：

- 业务系统不直接依赖 Flowable API；
- 生产代码不查询或修改 Flowable `ACT_*` 内部表；
- Approval DSL 是产品流程模型，BPMN/DMN 是确定性编译产物；
- Form Schema 与 UI Schema 独立于具体前端组件；
- 身份、租户、权限、运行时绑定和审计证据由服务端权威产生；
- 浏览器和 Mobile 只提供业务输入，不能制造可信执行上下文；
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

- Approval DSL、递归树设计器、静态检查、路径模拟和确定性 BPMN/DMN 编译；
- Form Schema、UI Schema、字段权限、Form Package 和多端 Renderer；
- 发起、审批、退回、撤回、取回、重提、委派、交接、加签、减签和并发决策；
- 评论、回复、@人员、附件、通知、重试、死信和受治理 replay；
- tenant-scoped 审计哈希链、detect-only 一致性检查和 operational failure 治理；
- `ApprovalPrincipal`、租户隔离、企业责任、管理 capability 和资源 scope；
- 版本化工作日历、不可变 SLA policy、SLA intent/attempt、lease、retry、DEAD 和 replay；
- Process Release Lifecycle、单 ACTIVE release、release-bound start 和 immutable runtime binding；
- detect-only 运行实例迁移评估、确定性 `reportHash` 与 Web 报告页。

## 重要能力边界

M4 只有 detect-only 迁移评估。真实运行实例迁移执行正在 M5 中按阶段验证和开发。

在 M5 正式通过相应门禁前，不能：

- 使用 Flowable 内部表完成迁移；
- 暴露 execute、force 或伪 rollback；
- 让浏览器或 Mobile 直接调用引擎迁移；
- 对 UNKNOWN 外部结果进行盲目重试；
- 在没有不可变计划、验证和 reconciliation 的情况下启用生产执行。

## 里程碑状态

| 阶段 | 状态 | 主要内容 |
| --- | --- | --- |
| M0 | 已完成 | Monorepo、核心分层、Engine SPI、多端和 CI 基线 |
| M1 | 已完成 | 采购付款审批纵向切片与多端运行 |
| M2 | 已完成 | Approval DSL、设计器、动态表单、发布与版本治理 |
| M3 | 已验收并合并 | 协作、委派、通知、审计、一致性与运维恢复治理 |
| M4 | 已验收并合并 | Operations、企业治理、SLA、执行 replay、Release Lifecycle 与迁移评估 |
| M4.1 | 已完成并合并 | living documentation 与权威 Roadmap 对齐，PR #59 / #60 |
| M5 | 开发中 | Governed Process Instance Migration and Release Operations，Issue #56 / Draft PR #58 |
| M6 | 规划中 | Ecosystem and AI：连接器、SDK、模板生态与受治理 AI 审批能力 |

Issues #13 和 #14 当前仍保持 Open。

## Roadmap

权威路线见 [`docs/ROADMAP.md`](docs/ROADMAP.md)。

### M5 — Governed Process Instance Migration and Release Operations

M5 已在 Issue #56、Draft PR #58 中开发：

1. Flowable 公开迁移 API 和安全支持矩阵；
2. 迁移领域模型与持久化协议；
3. immutable migration plan 和审批门禁；
4. 服务端执行器、verification、UNKNOWN 和 reconciliation；
5. 管理 API 与先只读的 operations UI；
6. 故障注入、安全、并发和低基数可观测性；
7. 正式治理验收和冻结证据。

M5-A 当前结论为 `SUPPORTED_WITH_LIMITATIONS`。这不等于批准生产执行。

### M6 — Ecosystem and AI

M6 延续原始 Roadmap 的 Ecosystem and AI 方向：

- 钉钉、飞书等连接器与第三方任务协同；
- Java、TypeScript SDK，事件订阅和签名 Webhook；
- 流程模板中心、模板版本、受控导入导出和第三方组件注册；
- AI 材料完整性检查、审批摘要、风险识别和意见建议；
- 模型、Prompt、知识来源版本、脱敏、权限和审计证据；
- 人工确认优先的受控自动化，以及明确的禁用、降级和回退机制。

M6 不允许 AI 绕过审批权限、伪造操作者、直接修改流程状态或把模型建议表示为确定事实。

## 文档

从 [`docs/README.md`](docs/README.md) 进入完整文档索引。

- [`docs/ROADMAP.md`](docs/ROADMAP.md)
- [`docs/PRODUCT_CHARTER.md`](docs/PRODUCT_CHARTER.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/PROCESS_DSL.md`](docs/PROCESS_DSL.md)
- [`docs/FORM_SCHEMA.md`](docs/FORM_SCHEMA.md)
- [`docs/OPERATIONS.md`](docs/OPERATIONS.md)
- [`docs/M4_FINAL_ACCEPTANCE.md`](docs/M4_FINAL_ACCEPTANCE.md)

## English Summary

M4 Operations has been formally accepted and merged. M5 governed process-instance migration is currently under staged development in Issue #56 and Draft PR #58. M6 is planned for ecosystem integrations, SDKs, templates and governed AI assistance.

## License

Apache License 2.0
