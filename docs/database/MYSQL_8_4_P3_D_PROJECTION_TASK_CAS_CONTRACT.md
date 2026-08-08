# MySQL 8.4 P3-D Projection and Task CAS Contract

Status: `P3_D_STAGED / REMOTE_VALIDATION_PENDING / MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED`

Decision date: `2026-08-08`

Tracking:

- Issue: `#91`;
- Draft PR: `#92`;
- formal branch: `agent/mysql-8-4-production-compatibility`;
- non-triggering assembly branch: `agent/mysql-8-4-p3-d-projection-cas-staging`;
- current accepted `main`: `1747b22123fd71cccd8334853ad7060c6645b443`;
- accepted P3-C Head before this slice: `04402f9cc4bdc93f4cd44502d8be5b72a1df48b2`;
- accepted P3-C natural PR Run: `31177684067` / `#1362`.

## 1. Scope

P3-D converts one platform-owned persistence authority:

```text
ApprovalProjectionStore
```

The bounded scope includes:

- definition-version serialization;
- purchase-payment business-key serialization;
- definition, instance and task projection persistence;
- UUID, UTC `datetime(6)` and native JSON conversion;
- pending-task claim and control claim;
- pending-task transfer;
- exact claimed-version completion and cancellation CAS;
- active-task synchronization;
- running-instance status transition and withdrawal;
- trusted executable-server store selection.

P3-D does not convert unrelated query, form, release, SLA, notification, migration, AI or controlled-automation stores.

## 2. Immutable PostgreSQL reference

`JdbcApprovalProjectionStore` remains the accepted PostgreSQL 16 implementation and is not rewritten by this slice.

It retains:

- `pg_advisory_xact_lock(hashtextextended(...))` transaction serialization;
- PostgreSQL `UPDATE ... RETURNING *` task mutation/readback;
- native UUID binding and `OffsetDateTime` readback;
- `jsonb` projection storage;
- PostgreSQL `ON CONFLICT` active-task synchronization;
- accepted tenant, status, assignee and version predicates.

`POSTGRESQL_PROJECTION_STORE_UNCHANGED`

## 3. Trusted vendor selection

`JdbcApprovalProjectionStoreFactory` resolves the database vendor only from JDBC metadata:

```text
PostgreSQL 16 -> JdbcApprovalProjectionStore
MySQL 8.4    -> JdbcMySqlApprovalProjectionStore
```

No browser, mobile client, request header, tenant payload, Connector or AI output can select the persistence dialect.

The executable server binds its existing singleton `ApprovalProjectionStore` authority through this factory. Domain and application services remain vendor independent.

## 4. MySQL transaction serialization

MySQL does not provide PostgreSQL transaction-scoped advisory locks. P3-D uses bounded MySQL named locks only inside an already active Spring-managed local database transaction.

For a definition or business-key scope:

1. the server canonicalizes the exact scope;
2. the scope is namespaced and hashed to a lowercase 64-character SHA-256 lock name;
3. `GET_LOCK(lock_name, 30)` executes on the exact transaction-bound JDBC connection;
4. repeated acquisition of the same scope inside that transaction is idempotent in the application registry;
5. all acquired names are retained until transaction completion;
6. `RELEASE_LOCK(lock_name)` executes in reverse acquisition order only after commit or rollback;
7. a timeout, null result, unexpected result or SQL failure fails closed;
8. a release failure aborts the JDBC connection so a session holding an unknown lock cannot return to the pool.

Lock acquisition without an active synchronized transaction is rejected before business mutation.

The lock protects application-side side effects that occur between `lockDefinition` / `lockBusinessKey` and the strict projection insert. It is not exposed as a generic lock service.

`MYSQL_NAMED_LOCKS_TRANSACTION_BOUND`

## 5. MySQL projection value contract

The existing trusted `JdbcDatabaseValueAdapter` remains the only bounded value conversion authority:

- UUID binds as canonical lowercase-hyphenated `varchar(36)`;
- UTC instants bind as `LocalDateTime` and persist as `datetime(6)`;
- readback accepts only the governed UUID and instant representations;
- unsupported JDBC representations fail closed;
- attachment, instance and task identities remain tenant scoped.

Projection JSON is produced by the configured Jackson mapper and inserted through native MySQL `JSON`. Readback must preserve:

- attachment list order;
- assignee ordering;
- identity maps and sets;
- Unicode strings;
- exact decimal values stored by relational numeric columns;
- null optional release snapshot fields.

P3-D introduces no new JSON envelope or hash version because these projection documents are not themselves canonical cross-database evidence hashes.

## 6. Task mutation and readback

MySQL task claim, control claim and transfer replace PostgreSQL `UPDATE ... RETURNING *` with one local transaction:

1. execute a strict conditional `UPDATE` with tenant, task, current status and required assignee predicates;
2. require exactly one affected row;
3. read the exact task through the same tenant/task key and transaction;
4. require the expected resulting status and assignee;
5. return the persisted version after the mandatory `version = version + 1` mutation.

A zero-row update is an exact `ProjectionConflictException`. There is no fallback read that converts a stale command into success.

## 7. Exact version CAS

Completion and controlled cancellation require all of:

```text
tenant_id
instance_id
task_id
status = COMPLETING
version = claimed_version
```

A stale version, stale status, wrong tenant, wrong instance or missing row updates zero rows and rolls back the whole command transaction.

After a successful claimed-task transition:

- stale active tasks are canceled only within the exact tenant and instance;
- existing engine-task rows are updated only when owned by that same instance;
- new active tasks use a strict insert;
- an engine-task identity owned by another approval instance is rejected;
- the running instance advances through a strict status predicate and version increment;
- any synchronization or instance-update conflict rolls back the earlier task mutation.

`STRICT_ACTIVE_TASK_OWNERSHIP`

## 8. Duplicate and replay boundaries

P3-D does not use:

```text
INSERT IGNORE
REPLACE INTO
ON DUPLICATE KEY UPDATE
```

for definition, instance or task admission.

Active-task synchronization uses update-first plus strict insert. A duplicate exception is classified only after reading the exact tenant/engine-task owner. Unrelated primary-key or unique-key collisions remain failures and cannot be treated as replay.

Command idempotency remains owned by the separately accepted P3-A `IdempotencyGuard`; P3-D does not add a second replay mechanism.

`NO_INSERT_IGNORE`

## 9. Withdrawal and control semantics

Instance withdrawal requires exact tenant, instance, initiator and `RUNNING` status. The configured case-sensitive MySQL collation preserves operator identity case.

Only after the instance update succeeds are pending or completing tasks canceled for that exact tenant and instance. A wrong tenant, wrong initiator, missing instance or terminal instance fails without task mutation.

Controlled task claim and cancellation preserve the existing application contract. This slice adds no new control command or automation authority.

## 10. Permanent test matrix

`JdbcApprovalProjectionStoreMySqlIntegrationTest` contains seven real MySQL 8.4 scenarios:

1. trusted metadata selects the MySQL store and mutation outside a transaction is rejected;
2. definition, instance and task UUID/JSON/decimal/microsecond values round-trip without tenant leakage;
3. concurrent first business-key creation remains serialized until the first transaction commits;
4. rollback releases the business-key lock and leaves no projection;
5. concurrent task claim produces exactly one winner with exact version increment;
6. transfer, stale-version rejection, completion and active-task synchronization preserve CAS;
7. controlled cancellation, withdrawal and cross-instance engine-task ownership remain fail closed.

`JdbcApprovalProjectionStoreMySqlContractTest` permanently checks source and executable binding boundaries.

Existing PostgreSQL projection and purchase-payment suites remain mandatory regression evidence.

## 11. Fault posture

P3-D explicitly covers:

- business-lock timeout and acquisition failure as command failure;
- lock release on commit;
- lock release on rollback;
- connection abort after named-lock release failure;
- zero-row stale task mutation;
- transaction rollback after downstream synchronization conflict;
- concurrent task claim;
- cross-instance engine-task collision;
- tenant and case-sensitive initiator isolation.

P3-D adds no automatic retry. Connection loss, commit ambiguity, server crash, arbitrary deadlock and unknown-result acceptance remain later cross-store fault gates.

`NO_AUTOMATIC_RETRY`

## 12. Explicit non-claims

P3-D does not prove:

- full executable Flowable operation on MySQL;
- every projection/query store is MySQL compatible;
- release, form, SLA, notification, M5, AI or controlled-automation evidence equivalence;
- historical V50 fixture upgrade/restore;
- future V51+ migration behavior;
- MySQL query-plan or production performance acceptance;
- backup/restore, rollback or incident-operation acceptance;
- complete dual-vendor permanent CI;
- MySQL production support.

## 13. Formal remote gate

The P3-D slice may be recorded as accepted only after one unchanged formal-branch natural `pull_request` Workflow with:

- all nine physical Jobs successful;
- all seven real MySQL P3-D scenarios successful;
- the permanent source contract successful;
- existing PostgreSQL projection regression successful;
- exact persistence test selection and report coverage;
- four independently downloaded Artifacts whose local bytes and SHA-256 match GitHub metadata;
- no actionable Review or security finding;
- no same-Head rerun, empty commit, force push, rebase, deployment or Production Promotion.

Until that gate is complete:

```text
MYSQL_P3_D_PROJECTION_TASK_CAS_SEMANTICS_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
```
