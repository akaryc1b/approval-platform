# Documentation Index

This directory contains two different kinds of documentation:

1. **Living documentation**, which describes the current merged product and should be updated when the product changes.
2. **Immutable governance records**, which preserve the exact scope, evidence and repository state accepted at a historical stage and must not be rewritten later.

## Current repository baseline

- Current accepted milestone: **M4**
- M4 Pull Request: `#55` — merged and closed
- M4 source head: `2edd83af89b381438932338898387913192c8fbd`
- M4 merge commit on `main`: `58efb4255394fe3911700719669c4423a3ab212e`
- Final permanent validation run: `29986751894` — `success`
- Highest Flyway migration: `V32`
- Permanent validation workflow: `.github/workflows/approval-platform-validation.yml`

The M4 baseline supports process release lifecycle and detect-only migration assessment. It does **not** support real process-instance migration execution.

## Living documentation

These documents describe the current product baseline and may be updated through normal reviewed pull requests:

| Document | Purpose |
| --- | --- |
| [`PRODUCT_CHARTER.md`](PRODUCT_CHARTER.md) | Product scope, users, success criteria, current delivery baseline and roadmap |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Current system boundaries, layers, data flows, transaction model and deployment shape |
| [`PROCESS_DSL.md`](PROCESS_DSL.md) | Approval DSL protocol and release-artifact relationship |
| [`FORM_SCHEMA.md`](FORM_SCHEMA.md) | Form, UI, rule and permission schema protocol |
| [`OPERATIONS.md`](OPERATIONS.md) | Production identity, startup, migration, release, SLA execution, incident and recovery runbook |

Living documentation must not contradict the latest accepted governance records. When a new capability is merged, update living docs in the same milestone or through an explicit post-merge reconciliation issue.

## Final acceptance indexes

| Document | Status |
| --- | --- |
| [`M3_FINAL_ACCEPTANCE.md`](M3_FINAL_ACCEPTANCE.md) | Immutable M3 final acceptance record |
| [`M4_FINAL_ACCEPTANCE.md`](M4_FINAL_ACCEPTANCE.md) | Post-merge M4 acceptance index and lineage summary |

`M4_FINAL_ACCEPTANCE.md` links the phase-specific M4 records and records the final merge commit. It does not replace the detailed evidence in those phase records.

## Immutable M4 governance records

The following files preserve acceptance-time facts. References such as “PR remains Open + Draft” describe the required state at that acceptance boundary; they are not stale instructions and must not be edited after the PR is later merged.

| Stage | Document | Frozen Git blob |
| --- | --- | --- |
| M4-A | [`M4_IDENTITY_AND_TENANT_GOVERNANCE.md`](M4_IDENTITY_AND_TENANT_GOVERNANCE.md) | `716ecf6503aeaea7a6dbfa5980964a5c4b983619` |
| M4-B | [`M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md`](M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md) | `888f07df905726cfb3507d2ae495db3247d6c4fe` |
| M4-C | [`M4_SLA_AND_CALENDAR_GOVERNANCE.md`](M4_SLA_AND_CALENDAR_GOVERNANCE.md) | `beb098bc6b4ee68c6ca11da0678a76780b72a049` |
| M4-D | [`M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md`](M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md) | `dc687d073e0352e0b88d96bd8df0f4ee36775b6e` |
| M4-E/F | [`M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md`](M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md) | `3c78cee75ed1ec3536fc8e26d440592e2038c6f2` |

The M3 record is also frozen:

- [`M3_FINAL_ACCEPTANCE.md`](M3_FINAL_ACCEPTANCE.md) — `459c684027e4a08f08655bff3e31721912dc35bc`

Do not modify these files for formatting, spelling, link updates or post-merge status changes. Create a new index or later milestone record instead.

## Historical design drafts

| Document | Meaning |
| --- | --- |
| [`M4_RELEASE_MIGRATION_EXECUTION_PROTOCOL_DRAFT.md`](M4_RELEASE_MIGRATION_EXECUTION_PROTOCOL_DRAFT.md) | Design-only input for possible future migration execution; execution remains unavailable, disabled, fail-closed and not exposed to browser or Mobile clients |

A design draft is not an accepted implementation and must never be presented as a callable product capability.

## Reading paths

### Product overview

1. Root [`README.md`](../README.md)
2. [`PRODUCT_CHARTER.md`](PRODUCT_CHARTER.md)
3. [`ARCHITECTURE.md`](ARCHITECTURE.md)

### Process and form development

1. [`PROCESS_DSL.md`](PROCESS_DSL.md)
2. [`FORM_SCHEMA.md`](FORM_SCHEMA.md)
3. M2 implementation tests and source code

### Production operations

1. [`OPERATIONS.md`](OPERATIONS.md)
2. [`M4_FINAL_ACCEPTANCE.md`](M4_FINAL_ACCEPTANCE.md)
3. The relevant immutable M4 governance record

### M5 preparation

1. [`M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md`](M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md)
2. [`M4_RELEASE_MIGRATION_EXECUTION_PROTOCOL_DRAFT.md`](M4_RELEASE_MIGRATION_EXECUTION_PROTOCOL_DRAFT.md)
3. A new M5 feasibility record based on the official Flowable public API

## Documentation rules

- A historical acceptance record describes the accepted state at its own timestamp.
- Living docs describe the latest merged `main` baseline.
- Planned work must be labelled as planned, unavailable or design-only.
- Detect-only assessment must not be described as migration execution.
- Production instructions must use server-authenticated principal identity and fail-closed authorization.
- Browser and Mobile inputs are never trusted tenant, operator, authority, audit, worker, lease or engine evidence.
- Production code and operational repair procedures must not query or modify Flowable `ACT_*` tables.
