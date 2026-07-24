# M5-B Process Migration Persistence Protocol

M5-B PERSISTENCE SLICE: `AUTHORIZED_DOMAIN_AND_PERSISTENCE_ONLY`

M5-B stage status: `IN_PROGRESS`

The user explicitly accepted the completed M5-A evidence package and authorized progression into
M5-B. This stage adds platform-owned domain and PostgreSQL persistence evidence only.

It does not authorize M5-C, M5-D, Flowable invocation, a migration worker, execution APIs, client
execution controls, automatic migration, or automatic retry.

## Scope

M5-B owns closed intent, attempt, verification and reconciliation vocabularies; tenant-scoped
identities; revision CAS; append-only evidence; exact idempotent replay; and bounded conflict
classification.

`planId` and `planHash` are persisted only as opaque future references. M5-B cannot create,
authorize, consume or execute a plan. This stage does not authorize M5-C.

PR #58 remains Open + Draft. Issues #13 and #14 remain Open. Production code does not query or
modify Flowable `ACT_*` tables. The only automatic PR/main workflow remains
`.github/workflows/approval-platform-validation.yml`.

## Flyway protocol

Flyway advances continuously from V32 through V37:

- V33 creates current intent state and append-only intent events;
- V34 creates current one-instance attempt state and append-only attempt events;
- V35 creates immutable verification and reconciliation evidence;
- V36 strengthens tenant lineage, canonical payload consistency and current-row/event atomicity;
- V37 strengthens explicit lease-owner fencing and independently durable UNKNOWN evidence.

No V38 or later migration is introduced. The six tenant-scoped tables remain:

- `ap_process_migration_intent`;
- `ap_process_migration_intent_event`;
- `ap_process_migration_attempt`;
- `ap_process_migration_attempt_event`;
- `ap_process_migration_verification`;
- `ap_process_migration_reconciliation`.

Current rows retain indexed identity, state and revision columns plus canonical JSONB records.
PostgreSQL guards reject deletion, immutable identity changes, stable-payload tampering, revision
skipping, prohibited state transitions, current/event mismatches and mutation of append-only evidence.
Deferred constraints require every inserted or advanced current row to have its matching event at
transaction commit.

## CAS, tenant isolation, lineage, lease and UNKNOWN

Intent and attempt creation persist the current row and revision-1 event in one short transaction.
Exact replay returns the existing record; changed evidence is a conflict. Every transition requires
expected state and revision, advances revision exactly once, and appends one matching event.

The same UUID can exist independently in different tenants. Composite foreign keys prevent
cross-tenant intent, attempt and evidence binding. Every retry requires a new attempt identity and the
immediate `FAILED_RETRYABLE` parent in the same tenant, intent and approval instance. Attempt 1 has no
parent. Persistence does not schedule retries.

A claim records an explicit lease actor. Same-owner renewal must occur before expiry and extend the
lease. A different owner can take over only at or after expiry. Leaving `CLAIMED` requires the current
unexpired owner. Revision CAS and database lease guards jointly fence stale owners. Persistence does not
poll, schedule, renew or take over leases automatically.

`UNKNOWN` is durable and requires engine outcome `UNKNOWN`, failure class
`ENGINE_OUTCOME_UNKNOWN`, bounded error evidence and the preserved engine request reference. It may
advance only to `RECONCILING` after open reconciliation evidence exists. UNKNOWN-derived attempt
closure requires terminal reconciliation evidence. Never retry `UNKNOWN` automatically.

Verification stores source, target and observed engine-definition identity, runtime-binding hashes,
canonical task keys, runtime/history presence and a closed outcome. Reconciliation begins `OPEN`,
may enter `MANUAL_REVIEW_REQUIRED`, and ends once with immutable resolution evidence. Per-attempt
verification and reconciliation sequences are gap-free.

## JDBC and validation boundary

`ApprovalMigrationProtocolStore` provides create/read/transition intent and attempt operations plus
append/read verification and reconciliation operations. Its explicit lease-actor transition overload
fails closed unless implemented by the store. JDBC stores use short transactions and perform no
Flowable operation, worker polling, runtime-binding update, plan creation or API action.

Permanent tests cover domain invariants, tenant isolation, idempotency, revision CAS, atomic event
append, concurrent replay, active-attempt ownership, strict retry lineage, lease renewal/takeover
fencing, durable UNKNOWN reconciliation gating, canonical payload tamper resistance, immutable gap-free
outcome evidence and database rejection of direct lifecycle bypass.

M5-B remains `IN_PROGRESS`. It does not authorize marking PR #58 Ready, merging it, enabling
auto-merge, or exposing production migration execution.
