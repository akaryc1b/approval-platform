# Acceptance Records

Acceptance 文档是不可变历史证据，回答“某个精确范围在当时如何被验证”。它们可以记录 commit、PR、Workflow Run、Job、Artifact、摘要、失败修正和非授权边界。

Acceptance 不能回答“当前默认分支支持什么”“是否已经发布”或“是否支持生产”。这些结论分别由 Current、Release 和 Production Support 决策承担。

## 不可变规则

- 已登记在 `config/acceptance-lock.json` 的文件必须保持 Git blob 完全一致；
- 不得重写、删除或静默更正历史验收；
- 修正必须新增 `CORRECTION`、`AMENDMENT` 或新的后续验收记录；
- 当前架构、兼容、运维和能力状态不得复制历史 SHA/Run 作为 living baseline；
- 后续目录整理只使用 `git mv`，并为旧路径提供兼容入口。

## 当前入口

第一阶段保留现有文件路径，避免破坏历史链接：

- M3：[`../M3_FINAL_ACCEPTANCE.md`](../M3_FINAL_ACCEPTANCE.md)
- M4：[`../M4_FINAL_ACCEPTANCE.md`](../M4_FINAL_ACCEPTANCE.md)
- M5：[`../M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md`](../M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md)
- M6：[`../m6/M6_OVERALL_FORMAL_ACCEPTANCE.md`](../m6/M6_OVERALL_FORMAL_ACCEPTANCE.md)
- M6 端到端：[`../m6/M6_G_END_TO_END_PRODUCTION_READINESS_ACCEPTANCE.md`](../m6/M6_G_END_TO_END_PRODUCTION_READINESS_ACCEPTANCE.md)

详细切片、Correction、Run 和安全证据继续从 `docs/` 与 `docs/m6/` 的历史文件访问，直到单独的链接保持型目录迁移完成。
