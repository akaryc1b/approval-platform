# MySQL 8.4 P3-D2a Existing Task Completion CAS Contract

## Status and scope

This document defines one deliberately narrow compatibility slice on top of the accepted P3-D task claim/transfer primitive.

```text
source formal Head: 1e94168db3c48e5ce426748d00a8b62e3013c865
implementation Head: 39e17383f70d80b01b00be9db1a49fec89d6bef0
staging branch: agent/mysql-8-4-p3-d2a-task-completion-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
```

The exact natural current-Head Run and Artifact evidence is intentionally bound in PR #92 metadata and Issue #91 after the formal branch advances. This avoids an evidence-only commit loop and an unnecessary second workflow trigger.

The slice proves only the terminal transition of one already-existing, already-claimed MySQL approval task:

```text
COMPLETING -> COMPLETED
```

It does not synchronize active engine tasks, advance the approval-instance status, bind a complete MySQL `ApprovalProjectionStore`, or claim MySQL production support.

## Exact operation

The bounded primitive exposes:

```text
completeClaimedTask(
    tenantId,
    taskId,
    assigneeId,
    claimedTaskVersion,
    completedAt
)
```

All caller-owned values are validated before mutation. `claimedTaskVersion` must be positive and incrementable. `completedAt` must have at most microsecond precision because MySQL persists the evidence in `datetime(6)`.

## Compare-and-set predicates

Completion succeeds only when one row matches every predicate:

```text
tenant_id = exact tenant
task_id = exact task
assignee_id = exact case-sensitive assignee
status = COMPLETING
version = exact claimedTaskVersion
completed_at is null
```

The only mutation is:

```text
status = COMPLETED
completed_at = completedAt
updated_at = completedAt
version = version + 1
```

Wrong tenant, wrong or case-variant assignee, stale/future version, non-`COMPLETING` status, an already populated completion timestamp, and concurrent losers all fail closed.

## Transaction and readback protocol

The operation executes inside one Spring JDBC transaction on the governed DataSource:

1. validate all input values;
2. read the exact tenant/task row and require the completion precondition;
3. execute one strict conditional `UPDATE` containing the caller-supplied claimed version;
4. require an affected-row count of exactly one;
5. read the exact row with `FOR UPDATE` in the same transaction;
6. verify immutable task identity, engine identity, definition identity, name, assignee and creation time;
7. verify `COMPLETED`, exact `claimedTaskVersion + 1`, and exact `updated_at` / `completed_at` evidence;
8. return the verified projection;
9. roll back the mutation when verification or a surrounding platform operation fails.

The implementation contains no automatic retry, deadlock retry, fail-open fallback, status repair or evidence repair.

## Concurrency invariant

Two concurrent completion attempts using the same exact task, assignee and claimed version produce:

```text
one successful COMPLETED transition
one ProjectionConflictException
one version increment
one persisted completion timestamp
```

The loser cannot overwrite the winner and is not retried into a second successful transition.

## Explicit non-scope

This slice does not implement or imply:

- active-task discovery or upsert;
- cancellation of no-longer-active sibling tasks;
- approval-instance `RUNNING -> COMPLETED/REJECTED` transition;
- instance revision CAS;
- definition or business-key locking;
- projection JSON compatibility;
- withdrawal or remaining lifecycle transitions;
- release/runtime-binding compatibility;
- executable-server binding;
- Flowable execution on MySQL;
- production promotion.

A permanent regression proves that completing one task leaves sibling tasks and the parent instance untouched.

## Real-MySQL acceptance suite

The permanent class is:

```text
JdbcMySqlApprovalTaskCompletionCasIntegrationTest
```

Eight test methods cover all required conditions:

1. exact `COMPLETING -> COMPLETED` transition;
2. exact version increment and microsecond timestamp readback;
3. repeated completion rejection;
4. wrong tenant rejection;
5. wrong and case-variant assignee rejection;
6. stale/future version rejection;
7. pending and already-completed status rejection;
8. one-winner concurrent completion;
9. surrounding-transaction rollback;
10. sub-microsecond and non-positive/non-incrementable version rejection before mutation;
11. no sibling-task synchronization or parent-instance status advancement.

The PostgreSQL suites and all previously accepted MySQL slices must remain green.

## Append-only implementation sequence

```text
04d6e36b947134aacccba608f8381e10462603bc  define bounded completion contract
9647c1dc8020a6cbfc01bf8c93b1cbd503617ef0  add claimed-task completion CAS
39e17383f70d80b01b00be9db1a49fec89d6bef0  add real MySQL acceptance suite
```

No applied PostgreSQL migration, permanent workflow, executable-server Bean or production database acceptance flag changed.

## Authorization boundary

Authorized:

- implement and validate this bounded existing-task completion CAS primitive;
- record the natural PR Run and Artifacts in PR #92 and Issue #91;
- correct any failure with a new append-only commit.

Not authorized:

- bind an incomplete MySQL projection store to the executable server;
- mark PR #92 Ready or merge it;
- close Issues #91, #82 or #62;
- claim MySQL production support;
- modify already-applied PostgreSQL migrations;
- add another automatic workflow;
- deploy or execute Production Promotion.

```text
MYSQL_P3_D2A_TASK_COMPLETION_CAS_IMPLEMENTED_PENDING_VALIDATION
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
```
