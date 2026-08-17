# Documentation Index

仓库文档按“当前事实、历史验收、发布快照、稳定参考、未来路线”分离。任何文档都不能同时承担这五种职责。

## 当前事实

`docs/current/` 描述默认分支当前已合并代码的架构、运维、兼容和能力状态。Current 文档不保存历史 SHA、Run、PR 或 Artifact 身份。

- [`current/README.md`](current/README.md)
- [`current/architecture.md`](current/architecture.md)
- [`current/operations.md`](current/operations.md)
- [`current/compatibility.md`](current/compatibility.md)
- [`current/capability-status.md`](current/capability-status.md)
- [`current/capability-status.json`](current/capability-status.json)

Capability Status 与 Compatibility 由 `config/capabilities.json`、仓库运行时配置和完整 Flyway 迁移树生成，不应手工编辑。

## 历史验收证据

`docs/acceptance/` 定义不可变策略和索引。第一阶段保留现有历史文件路径，避免破坏 PR、Issue 和外部引用；后续只通过 `git mv` 和兼容入口分批整理目录。

- [`acceptance/README.md`](acceptance/README.md)
- [`M3_FINAL_ACCEPTANCE.md`](M3_FINAL_ACCEPTANCE.md)
- [`M4_FINAL_ACCEPTANCE.md`](M4_FINAL_ACCEPTANCE.md)
- [`M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md`](M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md) — historical pre-merge final permanent acceptance evidence
- [`m6/M6_OVERALL_FORMAL_ACCEPTANCE.md`](m6/M6_OVERALL_FORMAL_ACCEPTANCE.md)
- [`m6/M6_G_END_TO_END_PRODUCTION_READINESS_ACCEPTANCE.md`](m6/M6_G_END_TO_END_PRODUCTION_READINESS_ACCEPTANCE.md)

这些文件证明“某个精确范围在当时如何被验证”，不代表当前 Release 或 Production Support。

## 发布快照

只有真实 Git tag、GitHub Release、release manifest 和制品摘要齐备时，才允许建立版本目录。

- [`releases/README.md`](releases/README.md)
- [`releases/next/README.md`](releases/next/README.md)

`releases/next/` 只是候选材料工作区，不是 Release。

## 稳定参考

稳定协议、Schema、连接器规范、安全模型和 ADR 由 `docs/reference/` 索引。第一阶段保留原路径，避免大规模链接迁移。

- [`reference/README.md`](reference/README.md)
- [`PROCESS_DSL.md`](PROCESS_DSL.md)
- [`FORM_SCHEMA.md`](FORM_SCHEMA.md)
- [`CONNECTOR_SPEC.md`](CONNECTOR_SPEC.md)
- [`SECURITY_MODEL.md`](SECURITY_MODEL.md)
- [`TENANCY_MODEL.md`](TENANCY_MODEL.md)
- [`adr/`](adr/)

## 未来路线

[`ROADMAP.md`](ROADMAP.md) 只描述未来优先级和完成条件。它不再作为当前实现状态的权威来源。

## 兼容入口

以下历史 living-document 路径继续保留，但只负责转向新的 Current 权威页面：

- [`ARCHITECTURE.md`](ARCHITECTURE.md)
- [`OPERATIONS.md`](OPERATIONS.md)
- [`COMPATIBILITY.md`](COMPATIBILITY.md)
