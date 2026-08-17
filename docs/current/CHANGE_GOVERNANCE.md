# Change Governance

**Status:** Current  
**Applies to:** new changes and material updates to open work after this policy is merged

## Purpose

Approval Platform uses risk-proportional governance. Formal Gate, Exact Head binding, immutable evidence and post-merge verification are reserved for changes whose failure could materially affect production data, tenant isolation, authorization, auditability or external business actions.

A Gate is a validation mechanism. It is not a reason to create another branch, Pull Request or acceptance document. A Pull Request should normally deliver one independently mergeable vertical slice.

## Risk levels

| Level | Typical changes | Required governance |
| --- | --- | --- |
| **L1 — ordinary** | Documentation, UI copy, test cleanup, behavior-preserving refactoring, small dependency or tooling updates | Pull Request, affected checks, normal review |
| **L2 — important** | Database migration, public API or event contract, permission mapping, Connector integration, persistence semantics, build or CI behavior | Pull Request, impact and compatibility note, targeted unit and integration tests, rollback description; ADR only for durable architecture decisions |
| **L3 — high risk** | Production instance migration, database cutover, tenant-isolation or authentication boundary, AI automatic business action, audit replay, irreversible data operation, secret or supply-chain trust boundary | Tracked issue, Formal Gate, threat and failure analysis, rollback and recovery rehearsal, Exact Head validation, immutable evidence and post-merge `main` verification |

The module name does not determine the level. A Connector display-field change may be L1 or L2; a Connector that holds production credentials and performs external writes is L3. AI that drafts a suggestion is normally L2; AI that can approve, reject, transfer or execute commands is L3.

### Minimum escalation triggers

A change is at least **L2** when it modifies any of the following:

- persisted schema, migration history or serialization;
- public API, event, Connector or extension contract;
- authorization, workflow state, concurrency or idempotency semantics;
- CI, release, dependency provenance or security-scanning behavior.

A change is **L3** when one failure can cause broad, difficult-to-reverse or cross-tenant impact, or when rollback requires manual recovery whose outcome cannot be determined automatically.

Authors propose the level. Reviewers may raise it. Lowering an L3 classification requires a written reason in the Pull Request.

## Work-in-progress limits

For one milestone, keep at most:

- two active feature Pull Requests;
- one additional maintenance or urgent-fix Pull Request.

Draft Pull Requests count toward the limit. A placeholder branch containing only an issue scaffold, bootstrap note or future plan should remain an Issue until the first reviewable vertical slice exists.

When a Pull Request is replaced, close it promptly and reference the successor with `superseded-by: #<number>`. Do not keep synchronizing or validating the superseded branch.

Dependabot updates should be grouped by ecosystem where practical. A dependency update is a maintenance stream, not a reason to create a separate acceptance program for every package.

## Pull Request lifecycle

- **L1:** an Issue is optional. The Pull Request and its checks are the record.
- **L2:** use an Issue when coordination, sequencing or follow-up work is needed. Put concise design, compatibility and rollback information in the Pull Request unless an ADR is justified.
- **L3:** a tracked Issue and explicit acceptance decision are required.

R0, G1, G2 and similar checkpoints may be used as checklist items inside one Pull Request. They must not automatically become separate branches or Pull Requests.

Move a Pull Request out of Draft when its vertical slice, required tests and necessary documentation are ready for review. Do not repeat `NO_READY`, `NO_MERGE`, `NO_DEPLOYMENT` or similar status slogans across multiple documents. GitHub state is authoritative unless an L3 decision record needs a concise exception block.

## Validation and evidence

| Level | Expected validation | Evidence retained |
| --- | --- | --- |
| **L1** | Format, compile or affected-module checks; targeted unit tests | Pull Request checks and review |
| **L2** | Affected-module tests, relevant integration/compatibility tests, migration or contract tests where applicable; one final required CI result | Pull Request summary, test results and compatibility/rollback note |
| **L3** | Full required matrix, Exact Head binding, recovery/rollback tests, controlled final run and post-merge verification | Immutable manifest or artifact set tied to the accepted commit |

Historical acceptance documents prove the version accepted at that time. They do not define current repository state and should not be continuously rewritten as the codebase evolves.

Current operational and architectural facts belong under `docs/current/`. Release-specific evidence belongs under `docs/releases/`; superseded material belongs under `docs/archive/` when those directories are introduced or expanded.

## Adoption

This policy applies prospectively. Existing historical evidence does not need to be rewritten. Open work should be reclassified L1/L2/L3 at its next material update, and unnecessary placeholder or superseded Pull Requests should be closed through normal repository triage.
