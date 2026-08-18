# MySQL 8.4 P3-H8 D8 Plan Aggregation Acceptance Evidence

## Status and authorization boundary

This record captures the completed implementation evidence for the bounded MySQL 8.4 D8 Plan
Aggregation parity gate.

```text
IMPLEMENTATION_ACCEPTED
IMPLEMENTATION_HEAD_GREEN
ARTIFACT_EVIDENCE_INDEPENDENTLY_VERIFIED
FORMAL_ACCEPTANCE_RECORD_STAGED_ONLY
FORMAL_DOCUMENTED_HEAD_NOT_AUTHORIZED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

This file is intentionally committed only to the non-PR staging branch. The implementation consumed
the complete authorized CI budget: one natural Run A and one natural correction Run B. Integrating
this document into the formal branch would create a third full PR validation Run and would also
require the new documented Head itself to be proven. Neither action is authorized by this gate.

Therefore:

- the exact formal implementation Head is green and its permanent Artifacts are verified;
- this staging record must not be represented as a green formal documented Head;
- PR #92 remains Open, Draft and unmerged;
- Issues #91, #82 and #62 remain Open;
- MySQL 8.4 remains not production supported.

## Tracking and exact identities

```text
Repository:     akaryc1b/approval-platform
Issue:          #91
Parent issue:   #62
Overall gate:   #82
Draft PR:       #92
Formal branch:  agent/mysql-8-4-production-compatibility
Staging branch: agent/mysql-8-4-p3-h8-plan-aggregation-staging
Main baseline:  4af817a46dcbefee7e84b78e022e87f24e0ffcda
```

Accepted implementation identity:

```text
Formal implementation Head:
8ddee0bfdb5dfe97530e6d94fc876b2fc4bd0cb5

Resolved tree:
4f021393a75628d753fe222604da4ad07c4672a7

Parent 1 — Run A formal Head:
13aaa53782716644f4416860c9ea0585a66c240a

Parent 2 — staging correction Head:
9325cd4c3ba3ee3d8209d33db924f344d5023dc2
```

The formal correction is an ordinary two-parent Merge Commit. No squash, rebase, force push,
cherry-pick history rewrite, direct main push or auto-merge occurred.

## Selected gate

```text
P3-H8 / M5-D8 Plan Aggregation Semantic Equivalence
PostgreSQL 16 -> MySQL 8.4
```

This gate converts only the existing D8 plan-level aggregation authority. It does not implement a
later migration step, historical upgrade/restore, Flowable production parity, performance,
operations, backup/restore, deployment or production promotion.

## Accepted production scope

The implementation adds and wires:

```text
JdbcApprovalMigrationPlanAggregationStoreFactory
JdbcMySqlCanonicalApprovalMigrationPlanAggregationStore
JdbcMySqlApprovalMigrationPlanAggregationStore
JdbcMySqlApprovalMigrationPlanAggregationBase
JdbcMySqlApprovalMigrationPlanAggregationPlanReadSupport
JdbcMySqlApprovalMigrationPlanAggregationSignalSupport
JdbcMySqlApprovalMigrationPlanAggregationFactSupport
JdbcMySqlApprovalMigrationPlanAggregationBuildSupport
JdbcMySqlApprovalMigrationPlanAggregationQuerySupport
JdbcMySqlApprovalMigrationPlanAggregationPersistenceSupport
MySqlV50D8PlanAggregationGuards
ApprovalMigrationPlanAggregationConfiguration -> trusted factory
```

The MySQL V50 authority installs nine D8 guards:

```text
ap_process_migration_plan_aggregate
  BEFORE INSERT payload/lineage/predecessor validation
  BEFORE UPDATE append-only rejection
  BEFORE DELETE append-only rejection

ap_process_migration_plan_aggregate_event
  BEFORE INSERT aggregate lineage validation
  BEFORE UPDATE append-only rejection
  BEFORE DELETE append-only rejection

ap_process_migration_plan_completion
  BEFORE INSERT terminal aggregate lineage validation
  BEFORE UPDATE append-only rejection
  BEFORE DELETE append-only rejection
```

## Accepted semantic contract

The MySQL implementation preserves the existing D8 business semantics:

1. vendor selection comes only from trusted JDBC metadata;
2. the application port remains database neutral;
3. one tenant/plan scope is serialized by a transaction-bound MySQL named lock before replay or
   revision reads;
4. only a consumed sealed plan and immutable D1-D7 evidence can contribute to the aggregate;
5. the canonical selected-instance sequence must match the sealed selected count;
6. exact idempotency replay returns the existing aggregate/event/completion identities;
7. a changed replay is rejected;
8. a new idempotency key cannot create another revision from unchanged authoritative input;
9. revisions advance exactly by one and predecessor hashes form one continuous chain;
10. tenant identity is exact and case-sensitive;
11. UTC instants are canonicalized to MySQL `datetime(6)` before JSON and hash participation;
12. aggregate, aggregate event, optional completion and Audit append are atomic;
13. Audit failure rolls back every D8 write;
14. incomplete or contradictory evidence fails closed without completion evidence;
15. terminal failure evidence may create an exact terminal plan completion;
16. no Flowable call or runtime-binding mutation occurs;
17. no automatic retry is introduced for unknown results;
18. PostgreSQL D8 behavior and regression coverage remain intact.

The implementation contains no:

```text
INSERT IGNORE
REPLACE
broad ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS bypass
PostgreSQL-only cast or lateral SQL in the MySQL store
user-selected database dialect
silent duplicate swallowing
silent overwrite
```

## Staging and formal integration trail

The D8 candidate was developed on a non-PR staging branch. It produced no staging workflow Run.

The candidate was then rebased onto the already-green H8 AI Durable Evidence documented Head:

```text
Staging rebaseline:
08dbf24d340e2e84990290448f18d433177449e2

Parent 1:
d3a2333c3e4c308a956a80e52f3afb687276e974

Parent 2:
2129facfa9d39fa5e1ff24538958212bac1a57d7
```

The initial formal D8 Merge Commit was:

```text
13aaa53782716644f4416860c9ea0585a66c240a

Parent 1:
2129facfa9d39fa5e1ff24538958212bac1a57d7

Parent 2:
08dbf24d340e2e84990290448f18d433177449e2
```

## Run A — retained failure

```text
Run ID:     32090888436
Run number: 1525
Event:      pull_request
Attempt:    1
PR:         #92
Head:       13aaa53782716644f4416860c9ea0585a66c240a
Base:       4af817a46dcbefee7e84b78e022e87f24e0ffcda
Conclusion: failure
```

Run A retained seven successful physical Jobs. The only test failure was in:

```text
Job: 95572660385
Persistence JDBC / shard 1

JdbcApprovalMigrationPlanAggregationStoreMySqlIntegrationTest
incompleteD7SignalLineageFailsClosedAsInvalidEvidence
```

The result correctly remained fail closed:

```text
AggregateStatus.INVALID_OR_INCOMPLETE_EVIDENCE
CanaryStatus.INVALID
PauseReason.INCOMPLETE_EVIDENCE
paused = true
completion = null
```

The test incorrectly expected raw `OrchestrationStatus.INVALID`. The established domain aggregation
rule normalizes every non-`NONE` pause reason to `OrchestrationStatus.PAUSED`. The actual result was
therefore correct and the test expectation was inconsistent with the existing cross-database domain
authority.

Classification:

```text
TEST_EXPECTATION_BUG / DOMAIN_RULE_MISMATCH
```

Run A was not rerun.

## Correction

The staging correction was exactly one assertion line:

```text
OrchestrationStatus.INVALID
->
OrchestrationStatus.PAUSED
```

Correction identities:

```text
Staging correction:
9325cd4c3ba3ee3d8209d33db924f344d5023dc2

Formal correction Merge Commit:
8ddee0bfdb5dfe97530e6d94fc876b2fc4bd0cb5
```

No production class, SQL, schema, trigger, hash domain, timestamp contract, PostgreSQL behavior or
workflow was changed by the correction.

## Run B — accepted implementation Head

```text
Run ID:     32091830387
Run number: 1526
Event:      pull_request
Attempt:    1
PR:         #92
Head:       8ddee0bfdb5dfe97530e6d94fc876b2fc4bd0cb5
Base:       4af817a46dcbefee7e84b78e022e87f24e0ffcda
Conclusion: success
Jobs:       9 / 9 success
```

| Job | ID | Result |
| --- | ---: | --- |
| Java 21 / Maven core | `95575390861` | success |
| Repository hygiene | `95575390892` | success |
| Vben TypeScript / production build | `95575390906` | success |
| UniApp TypeScript / H5 / WeChat | `95575390916` | success |
| Persistence JDBC / shard 3 | `95575390971` | success |
| Persistence JDBC / shard 1 | `95575391013` | success |
| Persistence JDBC / shard 0 | `95575391021` | success |
| Persistence JDBC / shard 2 | `95575391053` | success |
| Java 21 / Maven / PostgreSQL aggregate | `95576147414` | success |

## Independent test reconstruction

```text
Java Core:                             1469 / 0 / 0 / 0
Persistence JDBC:                       640 / 0 / 0 / 0
Combined:                              2109 / 0 / 0 / 0

selected persistence test classes:      161
unique selected classes:                 161
Surefire report classes:                 160
selected abstract without report:          1
abstract class:
  AbstractJdbcApprovalMigrationProtocolIntegrationTest
duplicate selection count:                 0
non-abstract selected without report:       0
extra report classes:                       0
selection coverage:                     exact
aggregate reported persistence time: 1155.319 s
```

Deterministic shard distribution:

```text
shard 0: 42 classes / 167 tests
shard 1: 39 classes / 155 tests
shard 2: 38 classes / 124 tests
shard 3: 42 classes / 194 tests
unique: 161 / 161
```

## Exact D8 target test matrix

Every target suite was present in the selected class set, produced one Surefire report and executed
with `skipped = 0`.

| Suite | Shard | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| `MySqlV50H8PlanAggregationSchemaContractTest` | 3 | 3 | 0 | 0 | 0 |
| `JdbcApprovalMigrationPlanAggregationStoreFactoryTest` | 2 | 2 | 0 | 0 | 0 |
| `JdbcApprovalMigrationPlanAggregationStoreMySqlContractTest` | 0 | 3 | 0 | 0 | 0 |
| `JdbcApprovalMigrationPlanAggregationStoreMySqlIntegrationTest` | 1 | 6 | 0 | 0 | 0 |
| retained `JdbcApprovalMigrationPlanAggregationStoreIntegrationTest` | 1 | 6 | 0 | 0 | 0 |

The real MySQL integration suite covers exact replay, canonical time, changed replay, tenant
isolation, stale revision rejection, unchanged-input rejection, concurrent-node serialization, real
D7 signals, real D2/D3 terminal completion, incomplete evidence, Audit rollback and database guard
tamper rejection.

## Independently verified permanent Artifacts

Every final Artifact ZIP was independently downloaded. Local bytes and SHA-256 exactly match GitHub
metadata, and every archive passed complete ZIP integrity verification.

All four Artifacts are bound to:

```text
Run:    32091830387
Branch: agent/mysql-8-4-production-compatibility
Head:   8ddee0bfdb5dfe97530e6d94fc876b2fc4bd0cb5
Expiry: 2026-11-16T02:26:18Z
```

| Artifact | ID | Bytes | ZIP entries | SHA-256 |
| --- | ---: | ---: | ---: | --- |
| Maven | `9308666297` | `1238038` | `332` | `8702d2739802109b74dc183111c6aae1680831b46566107a2c0b4a547235f475` |
| Hygiene | `9308665952` | `153225` | `6` | `d85e104ef4006794da2e21537f2a95a505a91a3a5a32fc3a234df49828ce29e5` |
| Vben | `9308608849` | `18911` | `8` | `0521066fe8b5ccb0ee4e9137029da3bb4e6ee5852763e0c139f5c9c3b8c4ec56` |
| Mobile | `9308595711` | `9795` | `5` | `113df2885e3cf8ce4b2ced012f0a3d1c38fca1b8e25f37307fb5c8672254fa89` |

The Maven Artifact contains the four deterministic selected-test manifests, four shard logs, 160
Surefire XML reports, the Maven core log, aggregate verification log and exact persistence summary.

## Remaining compatibility boundary

This D8 gate does not complete PRB-15 or MySQL production acceptance. Remaining work includes, at
minimum:

- remaining PostgreSQL-only JDBC authorities outside this D8 scope;
- historical MySQL fixture upgrade and restore rehearsals;
- a governed future V51+ migration contract;
- real executable-server and Flowable scenarios on MySQL 8.4;
- complete permanent dual-vendor CI for all required suites;
- query-plan, performance, deadlock, crash, timeout and unknown-result acceptance;
- backup, restore, rollback, security and operational rehearsals;
- final PRB-15 acceptance, Ready transition, Merge Commit and natural post-main verification.

No next compatibility gate was started as part of this acceptance work.

## Documented-head rule

The exact formal implementation Head is green:

```text
8ddee0bfdb5dfe97530e6d94fc876b2fc4bd0cb5
```

This staging document necessarily creates a different staging Head. It is not covered by Run
`32091830387 / #1526` and must not be described as a green formal documented Head.

The explicit gate budget allowed at most two full CI Runs. Run A failed and Run B succeeded. A formal
acceptance-document merge would create Run C, which is prohibited. Consequently this record remains
staged, and the formal branch remains at the exact green implementation Head.

```text
MYSQL_P3_H8_D8_PLAN_AGGREGATION_IMPLEMENTATION_ACCEPTED
D8_IMPLEMENTATION_HEAD_GREEN
D8_ARTIFACTS_INDEPENDENTLY_VERIFIED
D8_FORMAL_ACCEPTANCE_RECORD_STAGED_ONLY
D8_DOCUMENTED_HEAD_NOT_AUTHORIZED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
NO_RUN_C
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```
