# M3 Collaboration, Delegation and Notification Governance

## Status

M3 is active on `agent/m3-collaboration-delegation-and-notification-hardening` after M2 was merged and the permanent validation workflow on `main` was confirmed green.

The M3 pull request must remain open and in Draft state during implementation. It must target `main` directly and must not be stacked on an M2 branch.

## Scope baseline

M3 will harden collaboration, delegation and notification behavior across backend, PC and mobile clients. The implementation scope includes:

- sequential and parallel countersign, any-sign, vote and weighted approval policies;
- add-sign, remove-sign, delegation and assisted handling with explicit authority boundaries;
- proxy rules, employee-departure transfer and durable process handover;
- comment governance, notification-channel governance and user notification preferences;
- tenant isolation, idempotency, concurrency safety, immutable audit evidence and stable authorization failures;
- consistent PC, H5 and WeChat semantics backed by server-authoritative APIs;
- permanent committed-head CI coverage with no PR-only helper, self-committing workflow or temporary payload.

## Delivery guardrails

- Preserve existing M1 and M2 runtime compatibility unless a migration is explicitly documented and tested.
- Do not read or mutate Flowable tables directly; platform-owned projections and application ports remain authoritative.
- Keep identity, delegation and notification decisions deterministic and auditable.
- Keep notification delivery separate from business callback Outbox responsibilities.
- Add database constraints, integration coverage and cross-platform contract tests with each increment.
- Do not mark the M3 pull request ready, merge it, close it or retarget it during active development.

## Initial validation baseline

M3 starts from `main` merge commit `4e468f9f049b52cb20855872cddf44eaa237501b`, which contains the complete M2 delivery and the permanent `.github/workflows/approval-platform-validation.yml` workflow.
