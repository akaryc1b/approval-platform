# MySQL 8.4 P3-D Task Claim and Transfer CAS Contract

## Status

This document defines one deliberately narrow compatibility slice for the independent MySQL 8.4 workstream.

```text
source formal Head: 04402f9cc4bdc93f4cd44502d8be5b72a1df48b2
staging branch: agent/mysql-8-4-p3-d-task-cas-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
```

The slice proves compare-and-set behavior for already-existing approval-task projection rows. It does not claim that the complete `ApprovalProjectionStore`, executable server, Flowable runtime, or MySQL production target is supported.

## Exact scope

This slice covers only these existing task operations:

1. tenant-scoped task lookup;
2. tenant-scoped deterministic task listing;
3. assignee-owned pending-task claim;
4. control-plane pending-task claim without assignee substitution;
5. assignee-owned pending-task transfer;
6. exact version increment and persisted readback;
7. single-winner behavior under concurrent claims and transfers;
8. rollback when a surrounding platform transaction fails.

The implementation consumes the existing immutable MySQL V50 schema. It adds no Flyway migration and changes no PostgreSQL migration.

## Explicit non-scope

The following remain blocked and are not implied by this slice:

- definition publication locking;
- business-key admission locking;
- definition or instance creation on MySQL;
- projection JSON creation or active-task upsert;
- task completion and active-task synchronization;
- instance withdrawal;
- release/runtime-binding projection compatibility;
- executable-server binding of a complete MySQL `ApprovalProjectionStore`;
- Flowable execution on MySQL;
- automatic retries, deadlock retries, fail-open fallback or production promotion.

The primitive must remain unbound from the executable application until the complete projection-store contract is proven.

## Authority boundary

Database identity is resolved only from trusted JDBC metadata through the existing `JdbcDatabaseValueAdapter` and `ApprovalDatabaseVendorResolver` path.

The primitive:

- accepts only MySQL 8.4;
- rejects use against PostgreSQL or an unsupported vendor;
- never accepts a database-vendor value from HTTP, a browser, a Connector, a request header or a business payload;
- preserves exact case-sensitive tenant and assignee identities under `utf8mb4_0900_as_cs`.

## Task read contract

Task reads are explicit and tenant-scoped:

```text
findTask(tenant_id, task_id)
findTasks(tenant_id, instance_id)
```

`findTasks` orders by:

```text
created_at ASC, task_id ASC
```

UUID and UTC timestamp readback use the bounded JDBC value adapter. Unsupported JDBC representations fail closed.

## Claim contract

An assignee-owned claim updates exactly one row only when all predicates are true:

```text
tenant_id = requested tenant
task_id = requested task
assignee_id = exact operator identity
status = PENDING
```

The mutation is:

```text
status = COMPLETING
version = version + 1
updated_at = claimedAt
```

A control claim uses the same transition but omits only the assignee predicate. It does not manufacture or rewrite the persisted assignee.

Zero matching rows, multiple affected rows, missing readback or mismatched readback are conflicts. No retry occurs.

## Transfer contract

A transfer updates exactly one row only when all predicates are true:

```text
tenant_id = requested tenant
task_id = requested task
assignee_id = exact current assignee
status = PENDING
```

The mutation is:

```text
assignee_id = exact target assignee
version = version + 1
updated_at = transferredAt
```

Wrong tenant, wrong owner, case-variant owner, non-pending status and a concurrent winner all fail closed without a second mutation.

## MySQL transaction protocol

MySQL has no PostgreSQL-style `UPDATE ... RETURNING *` path for this store. Each mutation therefore executes in one local database transaction:

1. validate all caller-owned values before database mutation;
2. execute one strict conditional `UPDATE`;
3. require an affected-row count of exactly one;
4. read the exact tenant/task row using `FOR UPDATE` in the same transaction;
5. verify persisted status, assignee, version and operation timestamp;
6. return the verified projection;
7. roll back the entire mutation if any verification or surrounding platform operation fails.

The implementation uses neither `INSERT IGNORE` nor `ON DUPLICATE KEY UPDATE`, because this slice performs no admission insert.

## Time precision

`ap_approval_task.updated_at` is `datetime(6)` on MySQL. Operation timestamps must have at most microsecond precision before mutation. Sub-microsecond inputs are rejected before the database is touched rather than silently changing evidence.

## Concurrency invariants

For one pending task:

- two concurrent exact-owner claims produce one winner and one conflict;
- two concurrent transfers from the same current owner produce one winner and one conflict;
- the winning mutation increments the version exactly once;
- the loser cannot overwrite the winner;
- no automatic retry converts a conflict into a second successful mutation.

## Rollback invariant

The primitive uses Spring transaction participation on the configured DataSource. When invoked inside a surrounding platform transaction, a later exception must restore the original pending row, assignee, version and timestamp.

## Permanent acceptance matrix

A real MySQL 8.4 Testcontainers suite must prove:

1. canonical UUID and microsecond timestamp readback;
2. exact claim transition and version increment;
3. wrong-tenant, wrong-assignee and case-variant rejection without mutation;
4. control claim behavior;
5. exact transfer transition and target identity;
6. one-winner concurrent claims;
7. one-winner concurrent transfers;
8. surrounding-transaction rollback;
9. deterministic tenant-scoped task listing;
10. sub-microsecond rejection before mutation.

The permanent PostgreSQL suite must remain unchanged and green.

## Authorization boundary

Authorized:

- implement and validate this bounded MySQL task-CAS primitive;
- retain natural PR Runs and Artifacts as evidence;
- correct failures with new commits.

Not authorized:

- bind an incomplete MySQL projection store to the executable server;
- mark PR #92 Ready;
- merge PR #92;
- close Issues #91, #82 or #62;
- claim MySQL production support;
- edit already-applied PostgreSQL migrations;
- add a second automatic workflow;
- deploy or execute Production Promotion.

```text
MYSQL_P3_D_TASK_CAS_PRIMITIVE_IN_PROGRESS
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
```
