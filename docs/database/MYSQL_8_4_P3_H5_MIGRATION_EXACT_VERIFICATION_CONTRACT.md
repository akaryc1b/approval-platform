# MySQL 8.4 P3-H5 Migration Exact Verification Contract

## Scope

H5 closes only the persistence compatibility boundary immediately after accepted H4 migration engine execution. It does not implement D6 reconciliation, D7 orchestration, generic migration protocol completion, Flowable execution, deployment, retry, rollback, recovery, or production promotion.

The existing application port `ApprovalMigrationExactVerificationStore` remains the authority. Database-vendor differences are confined to JDBC persistence infrastructure.

## H5-R0 disposition

The PostgreSQL D3 implementation previously retained `engineRequestReference` for every finalization disposition. The domain invariant does not permit a mutable request reference on `FAILED_TERMINAL + REJECTED`; immutable engine request/outcome rows remain the durable evidence instead.

Disposition: `PRODUCT_BUG + TEST_GAP`.

A legal rejected finalization could therefore fail in the domain transition and roll back rather than reach its required terminal state. H5-A adds a focused PostgreSQL regression and the minimal correction: clear the mutable request reference only when the persisted engine outcome is `REJECTED`. No domain or application contract changes are authorized.

## Existing D4 authority

D4 is the existing `ApprovalMigrationExactVerificationStore` contract and PostgreSQL `JdbcApprovalMigrationExactVerificationStore` behavior.

Preparation requires all of the following server-owned authoritative state:

- tenant-scoped Attempt in `VERIFYING` at the exact expected revision;
- active migration command Fence with exact worker, revision and unexpired authority;
- current Runtime Binding matching the Attempt source engine identity and binding evidence hash;
- consumed authoritative migration Plan / PlanConsumption matching the Intent target identity;
- immutable H4 engine request matching tenant, intent, attempt, instance, worker, fence, source and target lineage;
- immutable H4 engine outcome matching that engine request and `CALL_RETURNED_AWAITING_VERIFICATION`;
- no client-supplied database dialect or authoritative engine/release identity.

The verification command is constructed from durable server-owned state.

## Real verification classifications

H5 preserves the existing application/domain classifications exactly:

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

No new verification or Attempt status is introduced.

## Finalization semantics

Every accepted finalization persists exactly one immutable exact-verification evidence row whose classification must equal the server-side classification of the supplied verification snapshot.

`EXACT_TARGET_RUNTIME` persists evidence and leaves the Attempt in `VERIFYING`. A later protocol stage owns successful completion.

Every non-exact-target classification transitions the Attempt to:

- status: `RECONCILING`
- engine outcome: `VERIFICATION_MISMATCH`
- failure class: `RECONCILIATION_REQUIRED`

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
- existing migration JSON codec/canonical payload rules;
- existing evidence hash protocol and prefixes;
- `JdbcMySqlTransactionLockManager` for deterministic transaction-scoped serialization where row-lock semantics require it;
- the already-normalized MySQL baseline schema for the V43/V44 exact-verification tables and constraints.

No new Flyway migration is authorized by H5.

## Fail-closed matrix

Preparation/finalization must reject without authoritative writes when any of these are stale or inconsistent:

- wrong tenant;
- wrong Attempt state or stale Attempt revision;
- stale Runtime Binding;
- stale or foreign Fence;
- wrong Engine Request;
- wrong Engine Outcome;
- target Plan/PlanConsumption drift;
- worker mismatch;
- instance/intent/source/target lineage mismatch;
- conflicting verification replay;
- duplicate finalization that is not the exact authoritative replay.

Tenant mismatch must not reveal whether another tenant's resource exists.

## Replay and concurrency

An exact repeated authoritative verification may be returned only under the existing strict replay contract. Conflicting evidence or stale replay fails closed; `INSERT IGNORE`, `REPLACE`, broad upsert, or duplicate-catching-as-success are forbidden.

Two concurrent verification attempts must admit at most one authoritative evidence/finalization effect. A stale contender cannot overwrite the winner. Verification evidence, Attempt transition, event and audit effects must not duplicate.

Concurrency tests must use deterministic synchronization or transaction-lock coordination rather than timing sleeps.

## Atomicity

Verification evidence persistence, any required Attempt CAS/event append, and audit append form one transaction. Failure of evidence insertion, Attempt transition, event append, or audit append rolls the whole business transaction back. H5 must never leave evidence without the corresponding state effect, or a state effect without required evidence/audit/event records.

## UNKNOWN / ambiguous results

Read failure, truncation, missing evidence, contradictory evidence, and other non-exact classifications retain their exact existing disposition. H5 does not retry an engine operation, infer success/failure, repair business state, or launch reconciliation.

## Acceptance boundary

Until focused PostgreSQL and real MySQL tests, deterministic concurrency/rollback tests, permanent boundary assertions, and the required physical CI evidence are green for the exact formal Head, the capability remains:

`MYSQL_P3_H5_MIGRATION_EXACT_VERIFICATION_STAGED`

H5 cannot claim `MYSQL_8_4_PRODUCTION_SUPPORTED`, Ready, merge, deployment, release, canary, rollout, traffic mutation, or production promotion.
