# Documentation Index

仓库文档按读者任务组织：先体验产品，再进入当前事实、架构、运维、稳定协议、历史验收和未来路线。任何 living 文档都不复制会快速过期的 SHA、Workflow Run 或 Artifact 身份。

## 先体验产品

- [10-Minute Quick Start](product-readiness/QUICK_START.md) — 一键启动 PostgreSQL、Redis、后端、PC 与 H5；
- [Purchase-Payment Golden Path](product-readiness/PURCHASE_PAYMENT_GOLDEN_PATH.md) — 完成审批、付款沙箱故障恢复与幂等校验；
- [Local Demo User Guide](product-readiness/USER_GUIDE.md)；
- [Local Demo Administrator Guide](product-readiness/ADMIN_GUIDE.md)；
- [Local Demo Operator Guide](product-readiness/OPERATOR_GUIDE.md)；
- [PC/H5 Browser and Accessibility Matrix](product-readiness/BROWSER_ACCESSIBILITY_MATRIX.md)；
- [Online Evaluation Sandbox](product-readiness/ONLINE_DEMO.md) — 当前是建设方案，尚无公共 URL；
- [Product Readiness Status](product-readiness/README.md)。

`docs/product-readiness/` 描述可执行的 Product Alpha 路径和证据边界。它不能把本地演示、构建或沙箱结果自动提升为 Release、真实生产集成或 Production Support。

## 当前事实

`docs/current/` 描述默认分支当前已合并代码的架构、运维、兼容和能力状态。Current 文档不保存历史 SHA、Run、PR 或 Artifact 身份。

- [Current Documentation](current/README.md)
- [Current Architecture](current/architecture.md)
- [Current Operations](current/operations.md)
- [Current Compatibility](current/compatibility.md)
- [Current Capability Status](current/capability-status.md)
- [Machine-Readable Capability Status](current/capability-status.json)

Capability Status 与 Compatibility 由 `config/capabilities.json`、仓库运行时配置和完整 Flyway 迁移树生成，不应手工编辑。

## 稳定参考

稳定协议、Schema、连接器规范、安全模型和 ADR 由 `docs/reference/` 索引。

- [Reference Index](reference/README.md)
- [Approval DSL](PROCESS_DSL.md)
- [Form Schema](FORM_SCHEMA.md)
- [Connector Specification](CONNECTOR_SPEC.md)
- [Security Model](SECURITY_MODEL.md)
- [Tenancy Model](TENANCY_MODEL.md)
- [Architecture Decision Records](adr/)

## 历史验收证据

`docs/acceptance/` 定义不可变策略和索引。历史文件证明某个精确范围在当时如何验证，不代表当前 Release 或 Production Support。

- [Acceptance Index](acceptance/README.md)
- [M3 Final Acceptance](M3_FINAL_ACCEPTANCE.md)
- [M4 Final Acceptance](M4_FINAL_ACCEPTANCE.md)
- [M5 Final Permanent Acceptance Evidence](M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md)
- [M6 Overall Formal Acceptance](m6/M6_OVERALL_FORMAL_ACCEPTANCE.md)
- [M6-G End-to-End Production Readiness Acceptance](m6/M6_G_END_TO_END_PRODUCTION_READINESS_ACCEPTANCE.md)

## 发布快照

只有真实 Git tag、GitHub Release、release manifest 和制品摘要齐备时，才允许建立版本目录。

- [Release Documentation](releases/README.md)
- [Next Release Workspace](releases/next/README.md)

`releases/next/` 只是候选材料工作区，不是 Release。

## 未来路线

[ROADMAP.md](ROADMAP.md) 只描述未来优先级和完成条件，不作为当前实现状态的权威来源。

## 兼容入口

以下历史 living-document 路径继续保留，只负责转向新的 Current 权威页面：

- [ARCHITECTURE.md](ARCHITECTURE.md)
- [OPERATIONS.md](OPERATIONS.md)
- [COMPATIBILITY.md](COMPATIBILITY.md)
