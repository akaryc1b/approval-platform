# MySQL 8.4 P3-E Complete Projection Store Contract

## Status

```text
source formal Head: 1e94168db3c48e5ce426748d00a8b62e3013c865
source natural Run: 31233159187 / #1364 / success
staging branch: agent/mysql-8-4-p3-e-projection-store-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
```

P3-E completes the existing platform-owned `ApprovalProjectionStore` for MySQL 8.4 after the bounded P3-D task claim/transfer CAS primitive was independently accepted.

This document is a staging contract until one unchanged formal-branch natural `pull_request` Run succeeds and all four final Artifacts are independently verified.

```text
MYSQL_P3_E_COMPLETE_PROJECTION_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

## 1. Exact scope

P3-E converts the remaining methods of the existing `ApprovalProjectionStore` authority:

1. definition-version transaction serialization;
2. definition projection save and read;
3. business-key transaction serialization;
4. approval instance and initial task creation;
5. instance read by tenant/identifier and tenant/business key;
6. task completion with exact claimed-version CAS;
7. controlled task cancellation with exact claimed-version CAS;
8. active-task cancellation, update and strict insert synchronization;
9. running-instance status synchronization;
10. running-instance withdrawal and pending-task cancellation;
11. trusted PostgreSQL/MySQL implementation selection;
12. executable-server binding of that trusted factory.

P3-E introduces no new domain or application command. It converts only the persistence implementation behind the already-existing interface.

## 2. Accepted P3-D dependency

P3-D already proved the bounded MySQL task primitive:

```text
JdbcMySqlApprovalTaskCasStore
```

Accepted P3-D behavior includes tenant-scoped read/list, assignee claim, control claim, transfer, exact expected-version CAS, one-winner concurrency, deterministic ordering, microsecond input enforcement and surrounding-transaction rollback.

The complete P3-E store delegates those operations rather than creating a second task-CAS implementation:

```text
findTask                   -> JdbcMySqlApprovalTaskCasStore
findTasks                  -> JdbcMySqlApprovalTaskCasStore
claimPendingTask           -> JdbcMySqlApprovalTaskCasStore
claimPendingTaskForControl -> JdbcMySqlApprovalTaskCasStore
transferPendingTask        -> JdbcMySqlApprovalTaskCasStore
```

Before delegation, the complete store applies the accepted PostgreSQL nearest-microsecond canonicalization contract so executable application calls do not fail solely because Java supplied sub-microsecond precision.

`P3_D_TASK_CAS_DELEGATED_NOT_DUPLICATED`

## 3. Immutable PostgreSQL reference

`JdbcApprovalProjectionStore` remains the accepted PostgreSQL 16 implementation. P3-E does not rewrite it or any PostgreSQL Flyway migration.

The PostgreSQL implementation retains:

- `pg_advisory_xact_lock(hashtextextended(...))` for definition and business-key serialization;
- native UUID and `timestamptz` behavior;
- native `jsonb` instance projection storage;
- `UPDATE ... RETURNING *` for claim and transfer readback;
- `ON CONFLICT (tenant_id, engine_task_id) DO UPDATE` for active-task synchronization;
- exact tenant, instance, task, status, assignee and version predicates.

`POSTGRESQL_PROJECTION_STORE_UNCHANGED`

## 4. Trusted vendor selection and executable binding

`JdbcApprovalProjectionStoreFactory` resolves the vendor only from trusted JDBC metadata:

```text
PostgreSQL 16 -> JdbcApprovalProjectionStore
MySQL 8.4    -> JdbcMySqlApprovalProjectionStore
```

The executable server binds its existing singleton `ApprovalProjectionStore` Bean through this factory.

No browser, mobile client, HTTP header, Connector, Event, Template, AI output, tenant payload or operator input can select the database implementation.

Domain and application modules remain vendor independent. P3-E adds no `if mysql` branch to those modules.

## 5. MySQL transaction-scoped serialization

MySQL user-level locks are connection scoped and are not automatically released by commit or rollback. P3-E therefore uses one bounded transaction lock manager on the exact Spring transaction-bound JDBC connection.

For each definition or business-key scope:

1. require an active synchronized local transaction;
2. resolve the current MySQL database catalog from the transaction connection;
3. combine the application namespace, database catalog and exact logical scope;
4. hash the value to a lowercase 64-character SHA-256 lock name;
5. acquire `GET_LOCK(lock_name, 30)`;
6. hold every acquired lock through commit or rollback;
7. release locks in reverse order with `RELEASE_LOCK(lock_name)` in transaction `afterCompletion`;
8. reject timeout, null, unexpected result or SQL failure;
9. abort the JDBC connection if explicit release fails, preventing a session with unknown lock ownership from returning to the pool.

Repeated acquisition of the same lock by the same store and transaction is application-idempotent and does not increment MySQL lock reference counts.

The database catalog is part of the hash because MySQL named locks are server-wide rather than schema-local.

```text
MYSQL_NAMED_LOCKS_TRANSACTION_BOUND
DATABASE_SCOPED_LOCK_NAMES
```

## 6. Definition and business admission

Definition publication uses the existing protocol:

```text
lockDefinition
findDefinition
saveDefinition
```

Business-instance admission uses:

```text
lockBusinessKey
findByBusinessKey
createInstance
```

The named lock protects the interval between the precondition read, any existing engine/application side effect and the strict projection insert. Definition, instance and task admission remain plain strict inserts.

P3-E does not use:

```text
INSERT IGNORE
REPLACE INTO
ON DUPLICATE KEY UPDATE
```

for projection admission.

A duplicate definition, business key, engine instance, task identifier or engine-task identity remains an error unless the specific active-task synchronization protocol below classifies the exact existing owner.

## 7. UUID, JSON, decimal and time contract

The existing trusted `JdbcDatabaseValueAdapter` remains the only UUID and instant conversion authority:

- MySQL UUID values bind as canonical `varchar(36)`;
- MySQL instants bind as UTC values for `datetime(6)`;
- readback accepts only the governed UUID and instant representations;
- unsupported JDBC values fail closed.

Definition, instance and task write timestamps use the accepted nearest-microsecond canonicalization contract.

Instance JSON remains native MySQL `JSON` and must preserve:

- attachment order;
- assignee order;
- Unicode values;
- attributes;
- identity snapshots;
- role and position sets;
- complete optional release/form/UI provenance.

The relational `numeric(24,6)` amount remains exact. Projection JSON is not a canonical cross-database evidence hash and therefore does not introduce a new JSON text envelope.

## 8. Initial task ownership validation

Before an instance insert, every initial task must:

- be non-null;
- use the exact instance tenant;
- use the exact instance identifier;
- have a unique task identifier within the command;
- have a unique engine-task identifier within the command.

Validation completes before the instance row is mutated. A mismatch fails the local transaction without leaving a partial instance.

## 9. Completion and cancellation CAS

Task completion requires exactly:

```text
tenant_id = requested tenant
instance_id = requested instance
task_id = requested task
status = COMPLETING
version = claimedTaskVersion
```

The successful mutation sets:

```text
status = COMPLETED
completed_at = canonical operation time
updated_at = canonical operation time
version = version + 1
```

Controlled cancellation uses the same exact ownership and version fence, changing only the target status and completion metadata.

Zero affected rows means an exact `ProjectionConflictException`. There is no stale-read replay or automatic retry.

All active-task and instance synchronization remains in the caller's same local transaction. Any later conflict rolls back the preceding claimed-task mutation.

## 10. Active-task synchronization

After a successful claimed-task transition:

1. read only tasks for the exact tenant and instance;
2. cancel persisted pending/completing tasks no longer reported active;
3. update an existing active task only by exact tenant, instance and engine-task identity;
4. preserve the existing task identifier, version and creation timestamp, matching PostgreSQL `ON CONFLICT` behavior;
5. insert a genuinely new active task through a strict insert;
6. classify a duplicate only after reading its exact owner;
7. reject an engine-task identity owned by another approval instance;
8. reject a task identifier already owned by another engine task;
9. reject a task identifier owned by another tenant even though public reads remain tenant-scoped;
10. reject an unclassifiable unique conflict.

The global task-identifier lookup is internal and returns only a generic conflict. It does not expose the other tenant's row to an application caller.

```text
STRICT_ACTIVE_TASK_OWNERSHIP
GLOBAL_TASK_IDENTIFIER_FENCED
```

MySQL Connector/J is explicitly configured with `useAffectedRows: false`, retaining JDBC found-row semantics required for idempotent update-first active-task synchronization.

## 11. Running-instance transition and withdrawal

After task synchronization, the instance update requires:

```text
tenant_id = requested tenant
instance_id = requested instance
status = RUNNING
```

The instance version increments exactly once. A missing or non-running row rolls back the full completion/cancellation transaction.

Withdrawal requires exact:

```text
tenant
instance
initiator identity
RUNNING status
```

Only after that update succeeds are pending or completing tasks canceled for the same tenant and instance. The production MySQL collation keeps tenant, initiator and assignee comparison case-sensitive.

## 12. Permanent real-MySQL acceptance matrix

`JdbcApprovalProjectionStoreMySqlIntegrationTest` uses a real MySQL 8.4 Testcontainers instance configured with:

```text
InnoDB
utf8mb4_0900_as_cs
UTC session time
READ COMMITTED
strict SQL mode
datetime(6)
useAffectedRows=false
```

Nine scenarios cover:

1. trusted factory selection and transaction-required mutation;
2. definition, instance and task UUID/JSON/decimal/provenance/time round-trip;
3. definition lock held until commit and single publication side effect;
4. business lock held through rollback, rollback cleanup and exact later creation;
5. sub-microsecond executable claim/transfer canonicalization delegated to P3-D;
6. stale completion rejection, exact completion CAS, stale-task cancellation, existing-task update and strict new-task insert;
7. surrounding-transaction rollback restoring task, active tasks and instance;
8. cross-instance engine-task and cross-tenant global task-identifier conflicts with full rollback;
9. controlled cancellation plus tenant/initiator-fenced withdrawal.

`JdbcApprovalProjectionStoreMySqlContractTest` permanently freezes source, factory, profile, server-binding and non-claim boundaries.

`JdbcApprovalProjectionStoreFactoryTest` executes the trusted PostgreSQL and MySQL metadata selection paths.

Existing P3-D and PostgreSQL projection/purchase-payment tests remain mandatory regressions.

## 13. Fault and concurrency posture

P3-E explicitly proves:

- lock acquisition timeout or error fails closed;
- definition serialization through commit;
- business serialization through rollback;
- named-lock release after both outcomes;
- transaction rollback after stale version;
- transaction rollback after active-task ownership conflict;
- outer transaction rollback after an otherwise valid completion;
- strict global task identifier fencing;
- exact tenant and case-sensitive initiator isolation.

P3-E adds no automatic deadlock retry, connection retry, unknown-result replay, evidence repair or fail-open fallback.

`NO_AUTOMATIC_RETRY`

## 14. Explicit non-claims

P3-E does not prove:

- every other JDBC store is MySQL compatible;
- complete executable-server startup and scenario execution on MySQL;
- Flowable schema, job, timer or migration execution on MySQL;
- form, release, runtime binding, SLA, notification or operational-failure compatibility;
- M5 migration evidence compatibility;
- AI evidence or controlled-automation lineage compatibility;
- historical V50 fixture upgrade/restore;
- future V51+ migration behavior;
- query-plan and production performance acceptance;
- complete crash, deadlock, timeout and commit-ambiguity acceptance;
- backup/restore, operations, rollback or incident-response acceptance;
- complete dual-vendor permanent CI;
- MySQL production support.

## 15. Formal remote gate

P3-E may be marked proven only after the non-triggering staging commits move to the formal PR branch through a non-forced fast-forward and one natural `pull_request` Workflow succeeds at an unchanged Head.

The gate requires:

- all nine physical Jobs successful;
- all nine new real-MySQL P3-E scenarios successful;
- all P3-D real-MySQL CAS scenarios still successful;
- existing PostgreSQL projection regressions successful;
- exact deterministic persistence selection coverage;
- Maven, Vben, Mobile and Hygiene Artifacts independently downloaded;
- local Artifact bytes and SHA-256 equal GitHub metadata;
- no actionable Review, unresolved thread or security blocker;
- no same-Head rerun, empty commit, force push, rebase, deployment or Production Promotion.

Until then:

```text
MYSQL_P3_E_COMPLETE_PROJECTION_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
```
