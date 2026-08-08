# MySQL 8.4 P3-D Task Claim and Transfer CAS Contract

## Status

This document records one deliberately narrow compatibility slice for the independent MySQL 8.4 workstream.

```text
source formal Head: 04402f9cc4bdc93f4cd44502d8be5b72a1df48b2
implementation Head: 040cace2c3ede0aa222805bf3b4b840188c0905c
implementation natural Run: 31232865290 / #1363 / success
staging branch: agent/mysql-8-4-p3-d-task-cas-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
```

The slice proves compare-and-set behavior for already-existing approval-task projection rows. It does not claim that the complete `ApprovalProjectionStore`, executable server, Flowable runtime, or MySQL production target is supported.

The evidence-recording commit that contains this final document remains subject to its own natural current-Head PR validation. PR metadata, rather than another repository commit, must bind that final current-Head Run and its Artifacts to avoid an endless evidence-commit cycle.

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

The primitive remains unbound from the executable application until the complete projection-store contract is proven.

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
version = transaction-read expected version
```

The mutation is:

```text
status = COMPLETING
version = version + 1
updated_at = claimedAt
```

A control claim uses the same transition but omits only the assignee predicate. It does not manufacture or rewrite the persisted assignee. Same-transaction readback verifies that the original assignee remains unchanged.

Zero matching rows, multiple affected rows, missing readback or mismatched readback are conflicts. No retry occurs.

## Transfer contract

A transfer updates exactly one row only when all predicates are true:

```text
tenant_id = requested tenant
task_id = requested task
assignee_id = exact current assignee
status = PENDING
version = transaction-read expected version
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
2. read the exact tenant/task row and require the operation precondition;
3. capture the current persisted version as `expectedVersion`;
4. execute one strict conditional `UPDATE` including `version = expectedVersion`;
5. require an affected-row count of exactly one;
6. read the exact tenant/task row using `FOR UPDATE` in the same transaction;
7. verify immutable task/instance/tenant identity, status, assignee, `version + 1` and exact operation timestamp;
8. return the verified projection;
9. roll back the entire mutation if any verification or surrounding platform operation fails.

The initial read is not treated as ownership. The conditional update is the compare-and-set fence. Two contenders may observe the same version, but only one can satisfy the update predicates after row-lock serialization.

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

The primitive uses Spring transaction participation on the configured DataSource. When invoked inside a surrounding platform transaction, a later exception restores the original pending row, assignee, version and timestamp.

## Implementation structure

The slice added exactly one bounded production primitive:

```text
JdbcMySqlApprovalTaskCasStore
```

It is intentionally not an `ApprovalProjectionStore` implementation and is not registered as an executable-server Bean.

The implementation sequence was append-only:

```text
ff4e3059a4e0f0453e6133fcefac143e774e2a18  define bounded contract
4cc6c0c6ff6d3df105de6369c19c0a1dc5be3d21  add task CAS primitive
c28580879a7a2da45b36cc59992059cadc74578f  remove unused imports and tighten control-claim readback
040cace2c3ede0aa222805bf3b4b840188c0905c  add real MySQL concurrency and rollback acceptance
```

The formal branch moved from `04402f9cc4bdc93f4cd44502d8be5b72a1df48b2` to the implementation Head through a non-forced fast-forward update. No rebase, force push or direct `main` update occurred.

## Permanent acceptance matrix

`JdbcMySqlApprovalTaskCasStoreIntegrationTest` uses a real MySQL 8.4 Testcontainers instance with:

```text
InnoDB
utf8mb4_0900_as_cs
UTC session time
READ COMMITTED
strict SQL mode
datetime(6)
```

Nine test methods cover all ten required acceptance conditions:

1. canonical UUID and microsecond timestamp readback;
2. exact claim transition and version increment;
3. wrong-tenant, wrong-assignee and case-variant rejection without mutation;
4. control claim behavior with assignee preservation;
5. exact transfer transition and target identity;
6. one-winner concurrent claims;
7. one-winner concurrent transfers;
8. surrounding-transaction rollback;
9. deterministic tenant-scoped task listing;
10. sub-microsecond rejection before mutation.

Result:

```text
Tests run: 9
Failures: 0
Errors: 0
Skipped: 0
Time: 26.192 s
```

The class was selected exactly once in Persistence shard `0`.

## Natural implementation validation

Natural `pull_request` Run:

```text
Run: 31232865290
Number: #1363
Head: 040cace2c3ede0aa222805bf3b4b840188c0905c
Conclusion: success
Correction Runs: 0
Same-Head reruns: 0
```

All nine physical Jobs succeeded:

| Job | ID | Result |
| --- | ---: | --- |
| Java 21 / Maven core | `93039962738` | success |
| Vben TypeScript / production build | `93039962739` | success |
| Persistence JDBC / shard 3 | `93039962744` | success |
| UniApp TypeScript / H5 / WeChat | `93039962753` | success |
| Persistence JDBC / shard 0 | `93039962761` | success |
| Persistence JDBC / shard 1 | `93039962765` | success |
| Repository hygiene | `93039962766` | success |
| Persistence JDBC / shard 2 | `93039962776` | success |
| Java 21 / Maven / PostgreSQL | `93040309681` | success |

Independent test reconstruction:

```text
Maven Core:                           1469 / 0 / 0 / 0
Persistence JDBC:                      411 / 0 / 0 / 0
Aggregate:                            1880 / 0 / 0 / 0
selected persistence test classes:      97
Surefire report classes:                96
expected abstract without report:        1
duplicate selections:                    0
non-abstract selected without report:    0
selection coverage:                  exact
```

Deterministic shard distribution:

```text
shard 0: 22
shard 1: 27
shard 2: 19
shard 3: 29
unique:  97 / 97
```

## Independently verified implementation Artifacts

All four ZIP archives were independently downloaded. Local byte counts and SHA-256 values exactly matched GitHub metadata, and every archive passed ZIP integrity verification.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9014441830` | `958563` | `b5b41667b4546e82c0680b136aa55951b6b22a8f67eab456073455561f361235` |
| Vben | `9014421124` | `18907` | `c1f2225c4881bdd9919f4dd888a0f5fa5b233111a10606ed68c8fb9e3f357967` |
| Mobile | `9014409542` | `9813` | `6b8c0cb9adaea1c202f288059c2dade1e40b0d49d07a4aa33b665af432b78d81` |
| Hygiene | `9014397236` | `17497` | `53d6d7c4c27011036cc9bc25217a9517fc901c56b0030b693751b72cf2218600` |

Every Artifact is bound to branch `agent/mysql-8-4-production-compatibility`, Head `040cace2c3ede0aa222805bf3b4b840188c0905c`, and expires `2026-11-06T01:31:56Z`.

## Remaining projection blockers

This proof does not remove the following blockers:

- MySQL definition and business-key serialization replacing PostgreSQL advisory locks;
- definition and instance write/read compatibility;
- canonical projection JSON storage and readback;
- active-task upsert replacing PostgreSQL `ON CONFLICT`;
- task completion/cancel synchronization and instance-status CAS;
- withdrawal and remaining lifecycle mutations;
- executable-server binding of a complete vendor-safe projection store;
- Flowable and end-to-end MySQL execution.

## Authorization boundary

Authorized:

- retain this bounded MySQL task-CAS primitive and permanent evidence;
- continue later independent projection slices;
- correct future failures with new commits.

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
MYSQL_P3_D_TASK_CAS_PRIMITIVE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
```
