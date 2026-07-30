# Documentation Index

本目录包含：

1. **Living documentation**：描述当前已合并产品、权威 Roadmap 和生产操作，可随产品演进更新。
2. **Immutable governance records**：保存历史阶段验收时的精确范围、证据和仓库状态，后续不得重写。
3. **Historical design drafts**：设计输入，不等于已实现或可调用能力。

## Current baseline

- 当前已合并产品基线：M4 / M4.1；
- M4 PR #55：merged / closed；
- M4 product merge：`58efb4255394fe3911700719669c4423a3ab212e`；
- M4.1 documentation PR #59：merged / closed；
- M4.1 merge：`9ce91f0fb71e12cd4eff04597aef3d612133d539`；
- 已合并 `main` Flyway：V1–V32；
- M5 PR #58 Flyway candidate：V33–V48，未分配 V49；
- permanent workflow：`.github/workflows/approval-platform-validation.yml`；
- current development：M5 Issue #56 / PR #58，已达到 pre-merge final permanent acceptance candidate；
- parallel independent development：M6 PR #67–#70，完全不属于 PR #58 范围。

M4 已合并能力仅支持 detect-only migration assessment。M5 PR #58 已完成受治理实例迁移协议、只读 Operations、深度加固和发布演练，但在 PR #58 正式合并前仍不是 `main` 已合并能力。M5 代码合并也不代表生产迁移执行授权；Production execution 始终为 `NOT_AUTHORIZED`。

## Canonical roadmap

[`ROADMAP.md`](ROADMAP.md) 是权威路线图。

当前顺序：

- M3 Collaboration：complete；
- M4 Operations：complete；
- M4.1 Documentation Reconciliation：complete；
- M5 Governed Process Instance Migration and Release Operations：final acceptance candidate in PR #58；
- M6 Ecosystem and AI：parallel independent Draft PRs，不属于 M5。

README、产品章程和其他 living docs 不得使用与 `ROADMAP.md` 冲突的阶段编号。

## Living documentation

| Document | Purpose |
| --- | --- |
| [`ROADMAP.md`](ROADMAP.md) | 权威阶段顺序、状态、范围和门禁 |
| [`PRODUCT_CHARTER.md`](PRODUCT_CHARTER.md) | 产品范围、原则、用户、成功标准和近期路线 |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | 系统边界、分层、数据流、事务和部署形态 |
| [`PROCESS_DSL.md`](PROCESS_DSL.md) | Approval DSL 协议与发布制品关系 |
| [`FORM_SCHEMA.md`](FORM_SCHEMA.md) | Form/UI/rule/permission schema 协议 |
| [`OPERATIONS.md`](OPERATIONS.md) | 生产身份、启动、迁移、release、SLA、故障和恢复手册 |

## Final acceptance indexes

| Document | Status |
| --- | --- |
| [`M3_FINAL_ACCEPTANCE.md`](M3_FINAL_ACCEPTANCE.md) | immutable M3 final acceptance |
| [`M4_FINAL_ACCEPTANCE.md`](M4_FINAL_ACCEPTANCE.md) | M4 post-merge acceptance index |
| [`M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md`](M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md) | M5 pre-merge final permanent acceptance evidence; production execution remains unauthorized |

## M5 governance and permanent evidence

| Stage | Document | Meaning |
| --- | --- | --- |
| D1–D8 | [`M5_D_SERVER_SIDE_EXECUTION_PROTOCOL.md`](M5_D_SERVER_SIDE_EXECUTION_PROTOCOL.md) | governed execution, exact verification, durable UNKNOWN, reconciliation, Canary, orchestration and aggregation protocol |
| E1 | [`M5_E1_READ_ONLY_OPERATIONS_PERMANENT_EVIDENCE.md`](M5_E1_READ_ONLY_OPERATIONS_PERMANENT_EVIDENCE.md) | tenant-scoped read-only Operations visibility |
| E2 | [`M5_E2_ADVANCED_DIAGNOSTICS_PERMANENT_EVIDENCE.md`](M5_E2_ADVANCED_DIAGNOSTICS_PERMANENT_EVIDENCE.md) | bounded advanced read-only diagnostics, lifecycle timeline and Web/Mobile evidence |
| F1 | [`M5_F1_FAULT_SECURITY_OBSERVABILITY_PERMANENT_EVIDENCE.md`](M5_F1_FAULT_SECURITY_OBSERVABILITY_PERMANENT_EVIDENCE.md) | fault/security/observability foundation |
| F2 | [`M5_F2_DEEP_HARDENING_PERMANENT_EVIDENCE.md`](M5_F2_DEEP_HARDENING_PERMANENT_EVIDENCE.md) | 24-case fault matrix, 24-case security matrix and deep observability hardening |
| G1 | [`M5_G1_RELEASE_REHEARSAL_PERMANENT_EVIDENCE.md`](M5_G1_RELEASE_REHEARSAL_PERMANENT_EVIDENCE.md) | isolated release rehearsal and production-readiness evidence |
| G1 runbook | [`M5_G1_RELEASE_REHEARSAL_AND_PRODUCTION_READINESS.md`](M5_G1_RELEASE_REHEARSAL_AND_PRODUCTION_READINESS.md) | release conditions, safe defaults, UNKNOWN procedure, rollback and stop-the-line |
| G2 | [`M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md`](M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md) | final regression, artifacts, SHA-256, limitations and conditional merge gate |

## Immutable M4 governance records

| Stage | Document | Frozen Git blob |
| --- | --- | --- |
| M4-A | [`M4_IDENTITY_AND_TENANT_GOVERNANCE.md`](M4_IDENTITY_AND_TENANT_GOVERNANCE.md) | `716ecf6503aeaea7a6dbfa5980964a5c4b983619` |
| M4-B | [`M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md`](M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md) | `888f07df905726cfb3507d2ae495db3247d6c4fe` |
| M4-C | [`M4_SLA_AND_CALENDAR_GOVERNANCE.md`](M4_SLA_AND_CALENDAR_GOVERNANCE.md) | `beb098bc6b4ee68c6ca11da0678a76780b72a049` |
| M4-D | [`M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md`](M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md) | `dc687d073e0352e0b88d96bd8df0f4ee36775b6e` |
| M4-E/F | [`M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md`](M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md) | `3c78cee75ed1ec3536fc8e26d440592e2038c6f2` |

M3 record：

- [`M3_FINAL_ACCEPTANCE.md`](M3_FINAL_ACCEPTANCE.md) — `459c684027e4a08f08655bff3e31721912dc35bc`

不要为格式、拼写、链接或合并后状态修改这些冻结文件。

## Historical design drafts

| Document | Meaning |
| --- | --- |
| [`M4_RELEASE_MIGRATION_EXECUTION_PROTOCOL_DRAFT.md`](M4_RELEASE_MIGRATION_EXECUTION_PROTOCOL_DRAFT.md) | M5 迁移执行设计输入；不等于已验收实现 |

## Reading paths

### Product and roadmap

1. Root [`README.md`](../README.md)
2. [`ROADMAP.md`](ROADMAP.md)
3. [`PRODUCT_CHARTER.md`](PRODUCT_CHARTER.md)
4. [`ARCHITECTURE.md`](ARCHITECTURE.md)

### M5 final acceptance

1. Issue #56 and PR #58
2. [`M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md`](M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md)
3. [`M5_G1_RELEASE_REHEARSAL_AND_PRODUCTION_READINESS.md`](M5_G1_RELEASE_REHEARSAL_AND_PRODUCTION_READINESS.md)
4. [`M5_F2_FAULT_SECURITY_OBSERVABILITY_HARDENING.md`](M5_F2_FAULT_SECURITY_OBSERVABILITY_HARDENING.md)
5. [`M5_F2_OBSERVABILITY_METRIC_CATALOG.md`](M5_F2_OBSERVABILITY_METRIC_CATALOG.md)
6. [`M5_E2_ADVANCED_DIAGNOSTICS_PERMANENT_EVIDENCE.md`](M5_E2_ADVANCED_DIAGNOSTICS_PERMANENT_EVIDENCE.md)
7. [`M5_D_SERVER_SIDE_EXECUTION_PROTOCOL.md`](M5_D_SERVER_SIDE_EXECUTION_PROTOCOL.md)

### M6 parallel preparation

1. [`ROADMAP.md`](ROADMAP.md) M6 section
2. Issues #62–#66 and independent Draft PRs #67–#70
3. M6 connector, SDK/event, template/component and AI governance records under their own branches

## Documentation rules

- historical acceptance records describe their own timestamp；
- living docs distinguish merged `main` capabilities from active PR candidates；
- planned work must be labelled planned, unavailable or design-only；
- M5 and M6 must remain separate milestones and branches；
- detect-only assessment must not be described as execution；
- AI advice must not be described as an approval decision；
- production instructions must use server-authenticated principal identity；
- browser and Mobile are never trusted identity, authority, audit, worker, lease or engine evidence；
- production code and repair procedures must not query or modify Flowable `ACT_*` tables；
- M5 merge does not authorize production migration execution。
