# Documentation Index

本目录包含：

1. **Living documentation**：描述当前已合并产品、权威 Roadmap 和生产操作，可随产品演进更新。
2. **Immutable governance records**：保存历史阶段验收时的精确范围、证据和仓库状态，后续不得重写。
3. **Historical design drafts**：设计输入，不等于已实现或可调用能力。

## Current baseline

- M4 PR #55：merged / closed；
- M4 product merge：`58efb4255394fe3911700719669c4423a3ab212e`；
- M4.1 documentation PR #59：merged / closed；
- M4.1 merge：`9ce91f0fb71e12cd4eff04597aef3d612133d539`；
- Flyway：V1–V32；
- permanent workflow：`.github/workflows/approval-platform-validation.yml`；
- current development：M5 Issue #56 / Draft PR #58；
- next planned milestone：M6 Ecosystem and AI。

M4 支持 detect-only migration assessment，但不支持真实运行实例迁移执行。M5 正在分阶段验证和开发该能力。

## Canonical roadmap

[`ROADMAP.md`](ROADMAP.md) 是权威路线图。

当前顺序：

- M3 Collaboration：complete；
- M4 Operations：complete；
- M4.1 Documentation Reconciliation：complete；
- M5 Governed Process Instance Migration and Release Operations：in progress；
- M6 Ecosystem and AI：planned。

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

不要为格式、拼写、链接或合并后状态修改这些文件。

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

### M5 development

1. Issue #56 and Draft PR #58
2. [`M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md`](M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md)
3. [`M4_RELEASE_MIGRATION_EXECUTION_PROTOCOL_DRAFT.md`](M4_RELEASE_MIGRATION_EXECUTION_PROTOCOL_DRAFT.md)
4. M5 feasibility and later governance records created on PR #58

### M6 preparation

1. [`ROADMAP.md`](ROADMAP.md) M6 section
2. connector protocols and SDK contracts
3. AI Provider SPI, data-governance and audit design created in a future M6 issue/PR

## Documentation rules

- historical acceptance records describe their own timestamp；
- living docs describe current merged capabilities and active roadmap；
- planned work must be labelled planned, unavailable or design-only；
- M5 and M6 must remain separate milestones；
- detect-only assessment must not be described as execution；
- AI advice must not be described as an approval decision；
- production instructions must use server-authenticated principal identity；
- browser and Mobile are never trusted identity, authority, audit, worker, lease or engine evidence；
- production code and repair procedures must not query or modify Flowable `ACT_*` tables。
