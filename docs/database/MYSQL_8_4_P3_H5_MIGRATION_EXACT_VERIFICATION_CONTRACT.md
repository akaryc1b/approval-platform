# MySQL 8.4 P3-H5 Migration Exact Verification Contract

## Scope

H5 closes only the persistence compatibility boundary immediately after accepted H4 migration engine execution. It does not implement D6 reconciliation, D7 orchestration, generic migration protocol completion, Flowable execution, deployment, retry, rollback, recovery, or production promotion.

The existing application port `ApprovalMigrationExactVerificationStore` remains the authority. Database-vendor differences are confined to JDBC persistence infrastructure.

## H5-R0 disposition

The PostgreSQL D3 implementation previously retained `engineRequestReference` for every finalization disposition. The domain invariant does not permit a mutable request reference on `FAILED_TERMINAL + REJECTED`; immutable engine request/outcome rows remain the durable evidence instead.

Disposition: `PRODUCT_BUG + TEST_GAP`.

A legal rejected finalization could therefore fail in the domain transition and roll back rather than reach its required terminal state. H5-A adds a focused PostgreSQL regression and the minimal correction: clear the mutable request reference only when the persisted engine outcome is `REJECTED`. No domain or application contract changes are authorized.

## Existing D4 authority

D4 is the existing two-stage `ApprovalMigrationExactVerificationStore` contract and PostgreSQL `JdbcApprovalMigrationExactVerificationStore` behavior:

1. `prepare(PrepareRequest)` opens a short persistence transaction, establishes exact verification authority and returns the public-engine read command.
2. The public engine is read outside that persistence transaction.
3. `finalizeVerification(FinalizeRequest)` opens a second short persistence transaction, revalidates authority, derives the classification from the bounded snapshot, persists immutable verification evidence and performs the required Attempt transition.

Preparation requires all of the following server-owned authoritative state:

- tenant-scoped Attempt in `VERIFYING` at the exact expected Attempt revision;
- immutable H4 engine request and returned engine outcome for that Attempt, where the outcome is `CALL_RETURNED_AWAITING_VERIFICATION` and the engine call was both attempted and returned;
- the H4 engine request id must equal the Attempt mutable `engineRequestReference`;
- the H4 engine request worker must equal the preparing worker;
- active migration command Fence for the same Attempt with exact worker, expected Fence revision and unexpired authority at `happenedAt`;
- no client-supplied database dialect or authoritative verification classification.

The verification command is constructed from durable Attempt identity and contains no client-selected database or release authority.

Finalization re-locks and revalidates the exact Attempt, immutable H4 request/outcome lineage and active command Fence before recording any D4 effect. If any authority has become stale, finalization fails closed.

## Lineage ownership across H1-H5

H5 does not invent a second source of migration authority or re-read mutable predecessor state merely because MySQL lacks PostgreSQL-specific trigger syntax. The accepted lineage is intentionally split by protocol ownership:

- H1 establishes migration target and runtime-binding CAS authority;
- H2 provisions the exact Attempt from the governed Plan / Intent lineage;
- H3 claims that Attempt and creates the active migration command Fence;
- H4 revalidates the current Runtime Binding plus RUNNING Intent / CONSUMED Plan target, including source binding evidence and target release/package/deployment/engine identity, then freezes those decisions into immutable Engine Request evidence before dispatch;
- H4 records the immutable Engine Outcome and advances only a returned call to `VERIFYING`;
- H5 consumes the resulting `VERIFYING` Attempt plus the immutable H4 Engine Request / Engine Outcome and exact active Fence as its D4 authority.

Therefore the H5 real-MySQL success fixture must enter through the real H2 -> H3 -> H4 path rather than manufacturing a `VERIFYING` Attempt. The retained H4 real-MySQL regression must continue proving that wrong tenant, stale Attempt/Fence, Runtime Binding drift and Plan target drift cannot create the immutable request lineage that H5 later consumes.

For MySQL, H5 additionally verifies the complete H4 Engine Request / Engine Outcome relational identity against the governed JSON payload written by the accepted H4 implementation. The checked immutable Request fields include tenant, Intent, Attempt, approval instance, worker, pre-dispatch Attempt revision, Fence id/revision, engine instance, source binding evidence hash, source engine definition, target release/package/deployment/engine definition, activity mappings, request/evidence hashes, requested time and request/trace ids. The checked immutable Outcome fields include request/outcome ids, tenant/Intent/Attempt/worker, expected Attempt/Fence revisions, disposition and call flags, stable code, bounded summary, pre-dispatch snapshot hash, outcome hash, recorded time and request/trace ids.

H5 also recomputes the accepted H4 hash protocols from those immutable relational fields before D4 authority is accepted:

- `m5-engine-request-v1`;
- `m5-engine-request-evidence-v1`;
- `m5-engine-outcome-v1`.

This is evidence-integrity validation, not a second H4 business decision. H5 does not reselect target release/deployment from mutable Plan or Runtime Binding state. The active Fence id must still be the exact Fence frozen by the H4 request. This compensates at the MySQL persistence boundary for PostgreSQL trigger-backed lineage protection while preserving the same externally visible D4 state machine.

H5 must not directly query or mutate Flowable `ACT_*` tables, re-run H4 target selection, invoke D6 reconciliation, or treat mutable client input as release/package/deployment authority.

## Request identity and replay

The authoritative prepare request hash is the existing SHA-256 protocol over:

- protocol prefix `m5-exact-verification-request-v1`;
- tenant id;
- Attempt id;
- worker id;
- expected Attempt revision;
- expected Fence revision;
- request id.

If immutable D4 evidence already exists for the tenant/Attempt, only the exact same request hash may replay. A changed-payload replay is forbidden. Replay returns the immutable evidence and current Attempt without inserting, updating or duplicating business effects.

Before accepting stored MySQL D4 evidence for replay, the persistence layer must self-validate that evidence rather than trusting syntactically valid stored hashes. It must recompute the D4 request hash from the persisted tenant, Attempt, worker, expected Attempt revision, expected Fence revision and request id, and recompute `m5-exact-verification-evidence-v1` from the persisted evidence identity, classification, snapshot hash and request hash. Both recomputed values must match the relational columns and typed payload evidence.

This replay integrity check does **not** require the original command Fence to remain active or the current Attempt to remain at the pre-finalization revision. Exact-target evidence may replay with the current `VERIFYING` Attempt, while mismatch evidence may replay with the current `RECONCILING` Attempt, preserving the existing PostgreSQL D4 replay semantics.

## Real verification classifications

H5 preserves the existing domain classifications exactly:

- `EXACT_TARGET_RUNTIME`
- `EXACT_SOURCE_RUNTIME`
- `SOURCE_HISTORY_TERMINAL`
- `TARGET_HISTORY_TERMINAL`
- `MIXED_SOURCE_TARGET_EVIDENCE`
- `MISSING_NO_EVIDENCE`
- `STALE_OR_CONTRADICTORY_EVIDENCE`
- `TRUNCATED_MANUAL_REVIEW_REQUIRED`
- `READ_FAILURE_RECONCILIATION_REQUIRED`
- `INCOMPLETE_RECONCILIATION_REQUIRED`

Classification is server-derived by `ApprovalMigrationExactVerification.classify(...)` from the bounded engine snapshot and the Attempt source/target engine definition ids. The supplied classification must equal that derived value. No new verification or Attempt status is introduced.

## Finalization semantics

Every first accepted finalization persists exactly one immutable `ApprovalMigrationExactVerification` row bound to the exact Attempt, H4 engine request id, H4 engine outcome id, source/target definitions, request hash and bounded snapshot hash.

The verification evidence hash remains the existing SHA-256 protocol with prefix `m5-exact-verification-evidence-v1` and the same field ordering as PostgreSQL D4.

`EXACT_TARGET_RUNTIME` persists evidence and leaves the Attempt in `VERIFYING`. A later protocol stage owns successful completion.

Every non-`EXACT_TARGET_RUNTIME` classification transitions the Attempt to:

- status: `RECONCILING`
- engine outcome: `VERIFICATION_MISMATCH`
- failure class: `RECONCILIATION_REQUIRED`
- bounded error summary: `D4 <classification> requires reconciliation`

The mutable H4 request reference remains attached for reconciliation lineage on that path. H5 itself never runs reconciliation.

## Vendor selection

A new D4 factory may select only from trusted JDBC metadata through the existing `ApprovalDatabaseVendorResolver` and server-owned DataSource configuration:

- PostgreSQL 16 -> existing `JdbcApprovalMigrationExactVerificationStore`;
- MySQL 8.4 -> MySQL D4 implementation;
- unsupported product/version -> existing database baseline/vendor resolution fails closed.

HTTP requests, tenants, clients, connectors, AI results and migration payloads cannot select a dialect.

## MySQL persistence requirements

The MySQL implementation must preserve PostgreSQL externally visible semantics while using accepted compatibility primitives:

- `JdbcDatabaseValueAdapter` for UUID and time binding/reading;
- `JdbcApprovalMigrationJson` plus relational/payload consistency checks for governed rows read by the MySQL implementation;
- `JdbcMySqlApprovalInstanceCommandFence.acquireMigrationLock(...)`, which uses the existing transaction-bound MySQL named-lock manager for the same per-instance serialization boundary;
- existing request/evidence hash protocol and prefixes;
- MySQL-compatible JSON binding without PostgreSQL casts or PostgreSQL-only typed UUID reads;
- the already-normalized MySQL baseline schema containing the V43/V44 exact-verification structures and constraints.

MySQL `DATETIME(6)` precision must be handled through the accepted canonical-instant/value-adapter boundary so relational timestamps and JSON payload evidence do not diverge because of sub-microsecond input precision.

No new Flyway migration is authorized by H5 unless a concrete schema incompatibility is demonstrated by the real MySQL acceptance tests.

## Fail-closed matrix

Preparation/finalization must reject without unauthorized writes when any of these are stale or inconsistent:

- wrong tenant;
- wrong Attempt state or stale Attempt revision;
- stale or foreign Fence;
- worker mismatch;
- missing, foreign or inconsistent H4 Engine Request;
- missing, foreign or inconsistent H4 returned Engine Outcome;
- Attempt mutable request reference not matching the immutable H4 request id;
- H4 request/outcome lineage mismatch;
- H4 immutable request/outcome relational-payload mismatch or hash mismatch;
- stored D4 request/evidence hash mismatch during replay;
- conflicting verification replay;
- non-server-derived classification;
- relational/payload evidence divergence in governed MySQL rows.

Tenant mismatch must not reveal whether another tenant's resource exists.

## Replay and concurrency

An exact repeated authoritative verification may be returned only under the existing strict replay contract. Conflicting evidence or stale replay fails closed; `INSERT IGNORE`, `REPLACE`, broad upsert, or duplicate-catching-as-success are forbidden.

Two concurrent first finalizations must admit at most one authoritative evidence/finalization effect. A stale contender cannot overwrite the winner. Verification evidence, Attempt transition, Attempt event and audit effects must not duplicate.

Concurrency tests must use deterministic synchronization or transaction-lock coordination rather than timing sleeps.

## Atomicity

Verification evidence persistence, any required Attempt CAS/event append, and audit append form one transaction. Failure of evidence insertion, Attempt transition, event append, or audit append rolls the whole business transaction back. H5 must never leave evidence without the corresponding state effect, or a state effect without required evidence/audit/event records.

## UNKNOWN / ambiguous results

Read failure, truncation, missing evidence, contradictory evidence and all other non-exact-target classifications retain their exact existing disposition: immutable D4 evidence plus `RECONCILING / VERIFICATION_MISMATCH / RECONCILIATION_REQUIRED`. H5 does not retry an engine operation, infer success/failure beyond the existing classifier, repair business state, or launch reconciliation.

## Acceptance boundary

Before formal synchronization, H5 requires a complete staging candidate with focused PostgreSQL regression coverage, MySQL factory/contract/integration coverage, true H2 -> H3 -> H4 -> H5 lineage, deterministic concurrency and rollback coverage, formatting/checkstyle/diff checks, and real local Maven/Testcontainers validation.

Until those checks and the required physical CI evidence are green for the exact formal Head, the capability remains:

`MYSQL_P3_H5_MIGRATION_EXACT_VERIFICATION_STAGED`

H5 cannot claim `MYSQL_8_4_PRODUCTION_SUPPORTED`, Ready, merge, deployment, release, canary, rollout, traffic mutation, or production promotion.
