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

## Increment A — delegation policy foundation

The first M3 increment establishes a server-authoritative proxy-rule lifecycle without changing existing task assignment behavior yet.

Completed capabilities:

- tenant-owned delegation rules with `ALL` and definition-specific scopes;
- self-service create, list and revoke APIs under `/api/approval/delegations`;
- durable request idempotency for create and revoke commands;
- principal-scoped PostgreSQL advisory serialization for overlap-safe writes;
- same-scope time-window overlap rejection while allowing definition-specific rules to override a global rule;
- deterministic effective-rule resolution with definition-specific precedence;
- self-delegation, invalid time windows, durations longer than 366 days and unauthorized revocation rejection;
- immutable `DELEGATION_RULE_CREATED` and `DELEGATION_RULE_REVOKED` audit evidence;
- stable delegation-specific 400, 404, 409 and 500 API responses;
- Flyway V14 schema constraints and resolution/query indexes;
- PostgreSQL/Testcontainers coverage for idempotency, tenant isolation, overlap rejection, precedence, revocation and fallback.

Implementation head `df242607fef1db2d01af5230c35408f89ffc7b26` passed permanent workflow run `29712802784`:

- repository hygiene: passed;
- Java 21 / Maven / PostgreSQL: passed;
- `JdbcApprovalDelegationIntegrationTest`: 3 tests, 0 failures, 0 errors, 0 skipped;
- Vben TypeScript and production build: passed;
- UniApp TypeScript, H5 and WeChat builds: passed.

Final documented head `f4db7bd85e132c5421252fe80239ab481683f34a` passed permanent committed-head workflow run `29712943726` with the same four jobs green.

The next increment will bind effective delegation resolution into platform-owned task assignment and expose governed PC/mobile management surfaces. It will preserve the original principal as immutable audit identity rather than silently replacing ownership.

## Initial validation baseline

M3 starts from `main` merge commit `4e468f9f049b52cb20855872cddf44eaa237501b`, which contains the complete M2 delivery and the permanent `.github/workflows/approval-platform-validation.yml` workflow.
