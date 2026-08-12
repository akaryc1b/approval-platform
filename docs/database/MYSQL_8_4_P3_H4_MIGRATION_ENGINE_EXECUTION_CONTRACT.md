# MySQL 8.4 P3-H4 Migration Engine Execution Persistence Contract

## Baseline identity

```text
source accepted capability: MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_PROVEN
source formal Head: cfa704d20d55e5b1fb33d72964ebc2b24577c1cc
implementation branch: agent/mysql-8-4-p3-h4-migration-engine-execution-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

P3-H4 converts only the short-transaction persistence authority behind:

```text
ApprovalMigrationEngineExecutionStore
```

```text
MYSQL_P3_H4_MIGRATION_ENGINE_EXECUTION_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

## Exact production scope

P3-H4 may add or modify only the persistence selection and MySQL implementation required by the existing application port:

```text
JdbcApprovalMigrationEngineExecutionStoreFactory
JdbcMySqlApprovalMigrationEngineExecutionStore
ApprovalMigrationExecutionConfiguration -> trusted execution-store factory
```

The already accepted H3 infrastructure entrypoint is reused unchanged:

```text
JdbcMySqlApprovalInstanceCommandFence.acquireMigrationLock(...)
approval-instance-command:v1:<tenantId>:<approvalInstanceId>
```

The existing PostgreSQL authority remains unchanged:

```text
JdbcApprovalMigrationEngineExecutionStore
```

No applied PostgreSQL migration is edited.

## Separation from Flowable execution

The existing application protocol intentionally keeps the public engine migration call outside both persistence transactions:

```text
prepare short transaction
-> no database transaction held
-> ProcessInstanceMigrationPort.migrateOne(...)
-> finalizeOutcome short transaction
```

P3-H4 proves only the MySQL persistence authority on the two sides of that engine call. It does not prove the real Flowable schema, Flowable migration implementation or executable server against MySQL 8.4. Those remain P5 blockers.

The existing `FlowableProcessInstanceMigrationAdapter` remains vendor-neutral engine infrastructure and is not modified by H4.

## Trusted vendor boundary

Vendor selection is derived only from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`.

```text
PostgreSQL 16 -> JdbcApprovalMigrationEngineExecutionStore
MySQL 8.4    -> JdbcMySqlApprovalMigrationEngineExecutionStore
```

No request, tenant, worker, browser, Mobile client, Connector, Provider, AI result or migration payload may select the database dialect.

## V41 guard equivalence

PostgreSQL V41 protects immutable engine request/outcome evidence with PostgreSQL trigger functions. The MySQL V50 baseline contains the same relational request/outcome tables and constraints but does not obtain semantic equivalence from PostgreSQL PL/pgSQL triggers.

Therefore H4 must reproduce the V41 guard semantics inside each MySQL short transaction with explicit row locks, exact authority checks, strict inserts and revision CAS. A textual JSON/cast translation alone is insufficient.

## Prepare transaction contract

`prepare` executes one local transaction and must perform this order:

1. canonicalize the requested `happenedAt` to the accepted nearest-microsecond UTC contract;
2. lock the exact tenant + Attempt row;
3. require exact `CLAIMED` status, expected Attempt revision, lease owner and unexpired lease;
4. acquire the already accepted H1/H3 transaction-bound instance command lock;
5. lock the matching ACTIVE durable migration Fence;
6. require exact worker, expected Fence revision and unexpired Fence lease;
7. lock the current Runtime Binding for the same tenant + instance;
8. require exact binding evidence hash, engine instance id and source engine definition identity from the Attempt;
9. lock the RUNNING Intent + exact CONSUMED Plan + matching PlanConsumption authority;
10. require exact target release/package/deployment/engine-definition identity;
11. create one immutable engine-request evidence row by strict insert;
12. transition the Attempt `CLAIMED -> ENGINE_REQUESTED` by exact revision/state CAS;
13. append the matching Attempt transition event;
14. append the governed audit event;
15. commit all evidence together.

If any step fails, the engine request row, Attempt transition, Attempt event and audit side effects must roll back together.

## Immutable engine request evidence

One request row binds at minimum:

```text
tenant
engineRequestId
intentId
attemptId
approvalInstanceId
workerId
pre-transition Attempt revision
Fence id + revision
engineInstanceId
source binding evidence hash
source engine definition id
target release version + package hash
target engine deployment + definition id
activity mappings
request hash
evidence hash
requestedAt
requestId + traceId
```

H4 retains the established hash versions:

```text
m5-engine-request-v1
m5-engine-request-evidence-v1
```

The request table remains append-only through application behavior and strict uniqueness. No `INSERT IGNORE`, `REPLACE`, `ON DUPLICATE KEY UPDATE`, duplicate swallowing or automatic retry is allowed.

## Attempt prepare transition

The only H4 prepare transition is:

```text
CLAIMED -> ENGINE_REQUESTED
```

The transition must:

- advance revision exactly once;
- clear the durable claim lease according to the existing domain protocol;
- retain the worker as the durable lease actor in the Attempt event;
- set `engine_request_reference` to the exact generated engine request id;
- preserve `EngineOutcome.NOT_REQUESTED`;
- write relational state and typed Attempt payload consistently;
- append exactly one matching Attempt event.

A stale Attempt revision, stale worker, expired lease, stale Runtime Binding, stale Intent/Plan target or stale Fence fails closed before any engine call can occur.

## Finalization transaction contract

`finalizeOutcome` executes a separate local transaction after the engine call has returned, rejected, or become ambiguous.

It must:

1. canonicalize finalization time to nearest-microsecond UTC;
2. lock the exact Attempt;
3. require exact `ENGINE_REQUESTED` status, exact prepared Attempt revision and exact engine request reference;
4. acquire the same transaction-bound instance command lock;
5. lock the exact immutable engine request row and verify the complete prepared lineage;
6. lock the same ACTIVE Fence and require its exact revision/worker and unexpired lease;
7. create exactly one immutable engine-outcome row by strict insert;
8. transition the Attempt by exact revision/state CAS;
9. append exactly one matching Attempt transition event;
10. append one governed outcome audit event;
11. commit all evidence together.

No failed finalization is automatically retried. If an engine call may have occurred and the authoritative response is unavailable, the only safe durable result is `UNKNOWN`.

## Closed final disposition mapping

```text
CALL_RETURNED_AWAITING_VERIFICATION
  -> Attempt VERIFYING
  -> EngineOutcome ACCEPTED
  -> FailureClass NONE

PRE_DISPATCH_REJECTED
ENGINE_REJECTED
  -> Attempt FAILED_TERMINAL
  -> EngineOutcome REJECTED
  -> FailureClass ENGINE_REJECTED

AMBIGUOUS_UNKNOWN
  -> Attempt UNKNOWN
  -> EngineOutcome UNKNOWN
  -> FailureClass ENGINE_OUTCOME_UNKNOWN
```

H4 must not mark a returned engine call as migration completion. `VERIFYING` continues into D4 exact verification.

## Exact immutable request lineage on finalization

Finalization must fail closed if the prepared object disagrees with durable request evidence on any identity that can affect dispatch authority, including:

```text
engineRequestId
requestEvidenceHash
tenant + intent + attempt + instance
Attempt revision lineage
Fence revision
engine instance/source definition
target deployment/definition
preparedAt
requestId/traceId
```

The worker used for finalization authority comes from the durable request row, not a new client-supplied identity.

## Time, UUID and JSON boundary

MySQL uses the already accepted shared primitives:

```text
JdbcDatabaseValueAdapter
AuditHashCanonicalizer.canonicalInstant
JdbcApprovalMigrationJson
```

All UUID values bind as canonical text for MySQL. All persisted H4 instants use nearest-microsecond UTC before typed payload serialization and `datetime(6)` binding.

The MySQL implementation must contain no PostgreSQL-only persistence syntax such as:

```text
::text
::jsonb
cast(... as jsonb)
for update of
pg_advisory
ON CONFLICT
```

MySQL JSON columns receive valid JSON text through normal prepared parameters.

## Audit and rollback contract

The prepare audit action remains:

```text
PROCESS_MIGRATION_ENGINE_REQUESTED
```

The finalization audit action remains:

```text
PROCESS_MIGRATION_ENGINE_OUTCOME_RECORDED
```

Audit failure is transaction-fatal. H4 real-MySQL tests must prove rollback of:

- engine request + `ENGINE_REQUESTED` Attempt transition;
- engine outcome + final Attempt transition.

## Concurrency and stale-owner contract

The instance command lock plus row locks and revision CAS must ensure:

- two concurrent prepares for one claimed Attempt cannot create two request rows;
- only the exact current lease owner can prepare;
- expiry or takeover before prepare invalidates stale ownership;
- a stale prepared revision cannot finalize after another authoritative transition;
- one engine request can produce at most one durable outcome;
- no automatic retry occurs after an ambiguous/unknown engine result.

## Permanent acceptance matrix

P3-H4 permanent suites must include:

```text
JdbcApprovalMigrationEngineExecutionStoreFactoryTest
JdbcApprovalMigrationEngineExecutionStoreMySqlContractTest
JdbcApprovalMigrationEngineExecutionStoreMySqlIntegrationTest
```

Real MySQL coverage must prove at minimum:

- trusted PostgreSQL/MySQL factory selection;
- H2 provisioning + H3 claim as the source of a real CLAIMED Attempt/Fence;
- prepare creates immutable request and transitions Attempt to ENGINE_REQUESTED atomically;
- returned engine call finalizes to VERIFYING with immutable outcome evidence;
- ambiguous engine result finalizes once to UNKNOWN and rejects duplicate finalization;
- rejected result finalizes to FAILED_TERMINAL;
- stale Attempt/Fence/tenant authority fails closed;
- source Runtime Binding and target Intent/Plan drift fail closed;
- concurrent prepare admits at most one request;
- prepare audit failure rolls back request/Attempt/event;
- finalization audit failure rolls back outcome/Attempt/event;
- UUID/datetime(6)/typed payload round trip remains exact;
- PostgreSQL authority and PostgreSQL permanent tests remain unchanged.

## Explicit non-scope

P3-H4 does not implement or imply compatibility for:

- real Flowable migration execution on MySQL 8.4;
- Flowable schema creation/upgrade on MySQL;
- D4 `ApprovalMigrationExactVerificationStore`;
- D6 reconciliation;
- D7 orchestration;
- generic `ApprovalMigrationProtocolStore` on MySQL;
- historical MySQL upgrade/restore acceptance;
- complete permanent dual-database CI;
- performance/operations/backup/restore acceptance;
- PR Ready transition;
- merge of PR #92;
- closure of Issue #91.

No later slice is started by this contract.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_PROVEN
MYSQL_P3_H4_MIGRATION_ENGINE_EXECUTION_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
