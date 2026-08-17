# Acceptance Records

> 此文件由 `scripts/generate-acceptance-catalog.mjs` 根据 `config/acceptance-catalog.json` 和 `config/acceptance-lock.json` 生成。不要手工编辑。

Acceptance 文档是不可变历史证据，回答“某个精确范围在当时如何被验证”。它们可以记录 commit、PR、Workflow Run、Job、Artifact、摘要、失败修正和非授权边界。

Acceptance 不能回答“当前默认分支支持什么”“是否已经发布”或“是否支持生产”。这些结论分别由 Current、Release 和 Production Support 决策承担。

## 分类入口

| Milestone | Locked records | Index | Scope |
| --- | ---: | --- | --- |
| M3 | 1 | [分类入口](m3/README.md) | M3 final acceptance evidence. |
| M4 | 1 | [分类入口](m4/README.md) | M4 final acceptance evidence. |
| M5 | 1 | [分类入口](m5/README.md) | M5 Governed Process Instance Migration and Release Operations final permanent acceptance evidence. |
| M6 | 8 | [分类入口](m6/README.md) | M6 workstream, end-to-end and overall formal acceptance evidence. |

机器可读目录见 [`catalog.json`](catalog.json)。

## 不可变规则

- `config/acceptance-lock.json` 是已登记历史正文的 Blob 锁；
- Catalog 必须完整覆盖 Lock，且不得复制或手写 Blob；
- 不得重写、删除或静默更正历史验收；
- 修正必须新增 `CORRECTION`、`AMENDMENT` 或新的后续验收记录；
- Current、Release、Roadmap 和 Acceptance 不得互相替代。

## 路径保持策略

本阶段通过 M3–M6 分类入口建立规范目录，但不移动已锁定正文。历史正文中的相对链接、已有 PR/Issue 链接和外部引用也是证据上下文；直接搬入子目录会破坏这些链接。

后续只有在完整链接保持方案可以证明时，才允许使用保留相同 Git Blob 的受控迁移。目录整理本身不能改变验收结论。
