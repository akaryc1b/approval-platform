# MySQL 8.4 P3-ME1 Migration Operations Query Acceptance Evidence

## Status and authorization boundary

This record accepts the implementation candidate for:

```text
P3-ME1 / M5-E1 Migration Operations Visibility Query Semantic Equivalence
PostgreSQL 16 -> MySQL 8.4
```

Status when this record is created:

```text
IMPLEMENTATION_ACCEPTED
IMPLEMENTATION_HEAD_GREEN
ARTIFACT_EVIDENCE_INDEPENDENTLY_VERIFIED
DOCUMENTED_HEAD_VALIDATION_REQUIRED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

The successful implementation Run proves the exact implementation Head, not the later commit that
contains this record. One fresh natural `pull_request` Run is therefore required at the exact
formal documented Head. That Run is documented-head validation, not a same-Head confirmation rerun.

This gate does not accept M5-E2 Diagnostics, complete PRB-15, authorize PR #92 to leave Draft, or
authorize deployment, Production Promotion or a MySQL production-support claim.

## Tracking and exact identities

```text
Repository:      akaryc1b/approval-platform
Issue:           #91
Parent issue:    #62
Overall gate:    #82
Draft PR:        #92
Main baseline:   4af817a46dcbefee7e84b78e022e87f24e0ffcda
Formal branch:   agent/mysql-8-4-production-compatibility
Staging branch:  agent/mysql-8-4-p3-me1-operations-query-staging
```

Accepted implementation identity:

```text
Formal implementation Head:
d1152d44ff63e4a1b373299c0a9bb6c1a0bfd972

Resolved tree:
c83fe4d82b64743ab79d5fd18443f592b21c5891

Parent 1 — previous formal Head:
8ddee0bfdb5dfe97530e6d94fc876b2fc4bd0cb5

Parent 2 — frozen staging Head:
0a7fbe1ce7889295cf2bd61627b30c3f28fadc24
```

The formal implementation commit is an ordinary two-parent Merge Commit. No squash, rebase, force
push, direct `main` push, auto-merge or history rewrite occurred.

## Source-driven gate selection

The live migration protocol inventory was rebuilt from the exact predecessor formal Head before
implementation. D1 through D8 already had accepted MySQL stores, factories and real integration
coverage. The first remaining direct downstream migration authority was M5-E1:

```text
ApprovalMigrationOperationsQuery
JdbcApprovalMigrationOperationsQuery
```

The existing PostgreSQL query uses PostgreSQL aggregate `FILTER`, `LATERAL` latest-row joins, native
UUID reads and `OffsetDateTime` result conversion. Production wiring constructed that implementation
directly. There was no MySQL peer or trusted vendor-aware factory.

M5-E2 Diagnostics is a separate downstream authority and remains unchanged and out of scope. An
independent AI governance-history compatibility staging branch also remains separate and was not
merged into this gate.

## Accepted production scope

The implementation adds and wires exactly:

```text
JdbcApprovalMigrationOperationsQueryFactory
JdbcMySqlApprovalMigrationOperationsQuery
ApprovalMigrationOperationsConfiguration -> trusted M5-E1 factory
```

The existing PostgreSQL implementation remains unchanged. The existing M5-E2 diagnostics bean still
constructs `JdbcApprovalMigrationDiagnosticsQuery` directly and is not claimed as MySQL compatible.

No Flyway migration, MySQL V50 guard, schema, mutation store, Flowable integration, workflow,
Connector, Event, AI capability, performance gate, operations procedure, backup/restore procedure or
production-promotion path is changed.

## Accepted semantic contract

The MySQL implementation preserves the existing M5-E1 externally visible behavior:

1. database identity is derived only from trusted JDBC metadata;
2. PostgreSQL 16 continues to use the existing `JdbcApprovalMigrationOperationsQuery`;
3. MySQL 8.4 uses a dedicated persistence-infrastructure implementation;
4. tenant identity remains exact and case-sensitive;
5. summary reads remain tenant scoped and are computed from immutable migration evidence;
6. plan filters remain bounded by definition, plan status, aggregate status and pause state;
7. plan ordering remains deterministic by `created_at DESC, plan_id DESC`;
8. selected-instance ordering remains deterministic by sealed `sequence_no`;
9. latest plan aggregate selection is by aggregate revision and aggregate identity;
10. latest attempt, completion, binding-conflict and reconciliation selection preserves the
    PostgreSQL authority ordering;
11. instance evidence precedence remains completion, conflict, reconciliation, observation,
    verification, then sealed selected-instance evidence;
12. exact UUID binding/readback uses the accepted database value adapter;
13. exact UTC MySQL `datetime(6)` readback uses the accepted canonical conversion;
14. multi-statement count/page/detail operations run in one read-only repeatable-read transaction;
15. missing or cross-tenant plan access fails closed;
16. bounded pagination rejects invalid limits and offsets;
17. the query acquires no business lock and mutates no migration evidence;
18. no automatic retry is added;
19. no Flowable `ACT_*` table is read;
20. PostgreSQL M5-E1 SQL and tests are not weakened.

The MySQL implementation contains no:

```text
FILTER (WHERE ...)
LATERAL
INSERT IGNORE
REPLACE
broad ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS
GET_LOCK
LOCK TABLES
SET GLOBAL
SET PERSIST
business mutation SQL
user-selected database dialect
direct ACT_* access
```

## Test-first matrix

The gate added:

```text
JdbcApprovalMigrationOperationsQueryFactoryTest
JdbcApprovalMigrationOperationsQueryMySqlContractTest
JdbcApprovalMigrationOperationsQueryMySqlIntegrationTest
```

The existing PostgreSQL regression remains:

```text
JdbcApprovalMigrationOperationsQueryIntegrationTest
```

The real MySQL integration suite constructs authority through existing accepted stores and fixtures.
It does not replace migration lineage with mocks. It covers:

- tenant-scoped summary;
- consumed, active, paused, unresolved and completed counts;
- deterministic plan filtering and pagination;
- exact plan detail;
- selected-instance detail;
- latest D8 aggregate revision;
- real attempt provisioning and claim visibility;
- real engine terminal outcome and plan-completion visibility;
- tenant isolation and case sensitivity;
- missing-plan and pagination rejection;
- proof that query execution does not mutate plan or aggregate evidence.

## Natural implementation Run A

```text
Run ID:      32099784675
Run number:  1527
Event:       pull_request
Attempt:     1
PR:          #92
Head:        d1152d44ff63e4a1b373299c0a9bb6c1a0bfd972
Base:        4af817a46dcbefee7e84b78e022e87f24e0ffcda
PR merge SHA: 912a27724e0aa0c2f418337e86362175c87027ee
Conclusion:  success
Jobs:        9 / 9 success
```

No workflow dispatch, same-Head rerun, empty trigger commit or manual retry occurred.

| Job | ID | Result |
| --- | ---: | --- |
| UniApp TypeScript / H5 / WeChat | `95597931648` | success |
| Java 21 / Maven core | `95597931732` | success |
| Vben TypeScript / production build | `95597931733` | success |
| Persistence JDBC / shard 1 | `95597931781` | success |
| Repository hygiene | `95597931784` | success |
| Persistence JDBC / shard 0 | `95597931804` | success |
| Persistence JDBC / shard 2 | `95597931805` | success |
| Persistence JDBC / shard 3 | `95597931855` | success |
| Java 21 / Maven / PostgreSQL aggregate | `95598571254` | success |

## Independent Maven reconstruction

The final Maven Artifact was downloaded independently. The reconstruction used the four selected-test
manifests, all Surefire XML reports, four shard logs, Maven core log and aggregate verification log.

```text
Java Core:                              1469 / 0 / 0 / 0
Persistence JDBC:                        648 / 0 / 0 / 0
Combined:                               2117 / 0 / 0 / 0

selected persistence test classes:       164
unique selected classes:                  164
Surefire report classes:                  163
selected abstract without report:           1
abstract class:
  AbstractJdbcApprovalMigrationProtocolIntegrationTest
duplicate selection count:                  0
non-abstract selected without report:        0
extra report classes:                        0
selection coverage:                      exact
aggregate reported persistence time: 1149.449 s
```

Deterministic shard distribution:

```text
shard 0: 43 selected / 43 reports / 169 tests
shard 1: 41 selected / 40 reports / 161 tests
shard 2: 38 selected / 38 reports / 124 tests
shard 3: 42 selected / 42 reports / 194 tests
unique: 164 / 164
```

The one selected class without a report is the expected abstract migration protocol base in shard 1.
No non-abstract class was omitted and no report was produced outside the selected set.

## Exact target test results

Every target suite was selected exactly once, produced one Surefire report and executed with
`skipped = 0`.

| Suite | Shard | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| `JdbcApprovalMigrationOperationsQueryMySqlContractTest` | 0 | 2 | 0 | 0 | 0 |
| `JdbcApprovalMigrationOperationsQueryFactoryTest` | 1 | 3 | 0 | 0 | 0 |
| `JdbcApprovalMigrationOperationsQueryMySqlIntegrationTest` | 1 | 3 | 0 | 0 | 0 |
| retained `JdbcApprovalMigrationOperationsQueryIntegrationTest` | 3 | 2 | 0 | 0 | 0 |

## Independently verified permanent Artifacts

Every final Artifact ZIP was independently downloaded. Local byte length and SHA-256 exactly match
GitHub metadata, and every archive passed complete ZIP integrity verification.

All four Artifacts are bound to:

```text
Run:     32099784675
Branch:  agent/mysql-8-4-production-compatibility
Head:    d1152d44ff63e4a1b373299c0a9bb6c1a0bfd972
Expiry:  2026-11-16T04:36:47Z
```

| Artifact | ID | Bytes | ZIP entries | SHA-256 |
| --- | ---: | ---: | ---: | --- |
| Maven | `9311222844` | `1250572` | `338` | `7644cbacd008f439377c5a6db1e12666f18458eede6bf21f85fc5f2def07e31e` |
| Hygiene | `9311218877` | `153200` | `6` | `64b1a59e367ff0c594de3bb81b709d6217073e58909f63369e8d710408b45c3c` |
| Vben | `9311174244` | `18739` | `8` | `4d0ba43075ee854ddec5498e45fa6755edc22f57822e8fac77d316987f59cf47` |
| Mobile | `9311167638` | `9820` | `5` | `9bd716507da513a3083ade28af5e6b840df95b69c8fa2cf564cee20ae006e527` |

The Maven ZIP contains 338 entries, including the four deterministic manifests, four persistence
logs, 163 Surefire XML reports, the Maven core log, aggregate verification log and persistence
summary.

## Correction trail

Run A was green. Therefore:

```text
NO_PRODUCT_CORRECTION
NO_TEST_FIXTURE_CORRECTION
NO_STATIC_CONTRACT_CORRECTION
NO_RUN_B_FOR_IMPLEMENTATION
```

The only remaining authorized full Run is the documented-head validation created by integrating this
acceptance record into the formal branch. It is not a repeat of the implementation Head.

## Remaining compatibility boundary

P3-ME1 does not complete MySQL production equivalence. Remaining work includes, at minimum:

- M5-E2 diagnostics query parity;
- other remaining PostgreSQL-only JDBC authorities outside this gate;
- historical MySQL fixture upgrade and restore rehearsals;
- a governed future migration contract after the clean V50 baseline;
- real executable-server and Flowable scenarios on MySQL 8.4;
- complete permanent dual-vendor CI for all required production suites;
- query-plan, performance, deadlock, crash, timeout and unknown-result acceptance;
- backup, restore, rollback, security and operational rehearsals;
- final PRB-15 formal acceptance, Ready transition, Merge Commit and natural post-main validation.

No next compatibility gate is started by this record.

## Documented-head rule

This file creates a new staging Head after the successful implementation Run. It must be merged into
the formal branch with an ordinary two-parent Merge Commit whose first parent is the exact current
formal implementation Head and whose second parent is the exact acceptance staging Head.

P3-ME1 becomes formally documented-head green only after one fresh natural `pull_request` Run:

- is attempt 1;
- is bound to PR #92;
- uses the exact documented formal Head;
- succeeds in all nine physical Jobs;
- executes every target suite with zero skips;
- produces Maven, Hygiene, Vben and Mobile Artifacts bound to that Head;
- and those four final ZIPs are independently verified again.

No third full Run is authorized.

```text
MYSQL_P3_ME1_OPERATIONS_QUERY_IMPLEMENTATION_ACCEPTED
P3_ME1_IMPLEMENTATION_HEAD_GREEN
P3_ME1_ARTIFACTS_INDEPENDENTLY_VERIFIED
P3_ME1_DOCUMENTED_HEAD_VALIDATION_REQUIRED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```