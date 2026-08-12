# MySQL 8.4 P3-H4 — Migration Engine Execution Persistence Acceptance Evidence

## Decision

P3-H4 implementation evidence is accepted at the exact implementation Head below. This record authorizes only the H4 capability marker transition from `STAGED` to `PROVEN` after a docs-only compare and a new natural current-Head validation.

It does not authorize real Flowable migration execution on MySQL, D4/D6/D7 compatibility, PR Ready, merge, Issue closure or MySQL production support.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_PROVEN
MYSQL_P3_H4_MIGRATION_ENGINE_EXECUTION_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

## Exact implementation identity

```text
main:        1747b22123fd71cccd8334853ad7060c6645b443
formal:      agent/mysql-8-4-production-compatibility
impl Head:   6ca5b8a13fb298dbae4fb119104239fd5987f599
PR:          #92 remains Open + Draft + unmerged
Issue:       #91 remains Open
```

The implementation Head was reached only by non-force fast-forward progression from the accepted H3 Head. Failed Head #1429 was not rerun in place.

## Exact H4 production scope

P3-H4 converts only:

```text
ApprovalMigrationEngineExecutionStore
```

Production additions/wiring:

```text
JdbcApprovalMigrationEngineExecutionStoreFactory
JdbcMySqlApprovalMigrationEngineExecutionStore
ApprovalMigrationExecutionConfiguration -> trusted execution-store factory
```

The existing PostgreSQL `JdbcApprovalMigrationEngineExecutionStore` remains unchanged.

The MySQL implementation reuses the already accepted transaction-bound instance command lock:

```text
JdbcMySqlApprovalInstanceCommandFence.acquireMigrationLock(...)
approval-instance-command:v1:<tenantId>:<approvalInstanceId>
```

No new lock namespace, application database branch, Flowable table access or PostgreSQL migration edit was introduced.

## Product semantics proven on real MySQL 8.4

The accepted implementation proves the short-transaction persistence protocol around one governed engine migration dispatch.

Prepare proves:

- exact H2-provisioned + H3-claimed `CLAIMED` Attempt authority;
- exact Attempt revision, worker and unexpired lease;
- exact ACTIVE durable Fence revision, worker and unexpired lease;
- shared H1/H3 instance command serialization;
- exact source Runtime Binding identity;
- exact RUNNING Intent + CONSUMED Plan + PlanConsumption target authority;
- immutable engine-request insertion;
- request/evidence hash construction;
- `CLAIMED -> ENGINE_REQUESTED` exact revision CAS;
- matching Attempt transition event;
- governed audit append;
- transaction rollback when audit fails.

Finalization proves:

- exact prepared Attempt revision and engine-request reference;
- exact immutable request lineage;
- exact Fence revision/worker authority;
- immutable engine-outcome insertion;
- returned call -> `VERIFYING / ACCEPTED / NONE`;
- ambiguous result -> `UNKNOWN / UNKNOWN / ENGINE_OUTCOME_UNKNOWN`;
- rejected result -> `FAILED_TERMINAL / REJECTED / ENGINE_REJECTED` with mutable Attempt request reference cleared according to the domain invariant;
- one outcome per engine request;
- duplicate finalization rejection;
- transaction rollback when outcome audit fails.

Concurrency/fault coverage additionally proves:

- concurrent prepare admits exactly one immutable engine request;
- stale tenant/Attempt/Fence authority fails closed;
- source Runtime Binding drift fails closed;
- target Plan/engine-definition drift fails closed;
- UUID text and `datetime(6)` canonicalization remain exact;
- H3 claim semantics remain green in the same real-MySQL shard.

The immutable engine request/outcome rows preserve dispatch lineage even when rejected terminal mutable Attempt state correctly clears `engineRequestReference`.

## Separation from Flowable

H4 accepts only persistence on the two sides of the engine API call:

```text
prepare short transaction
-> no database transaction held
-> ProcessInstanceMigrationPort.migrateOne(...)
-> finalizeOutcome short transaction
```

The `FlowableProcessInstanceMigrationAdapter` is unchanged. H4 does not claim real Flowable migration execution, Flowable schema compatibility or executable-server MySQL acceptance. Those remain P5 requirements.

## Natural validation trail

### Run #1429 — product/domain transition failure

```text
Run:        31556137343 / #1429
Head:       f9730b610620aeb922cc9b3ec27f9ada91ecab93
Conclusion: failure
Classification:
PRODUCT_BUG / REJECTED_FINALIZATION_REQUEST_REFERENCE
```

Physical result:

```text
Java 21 / Maven core:              success
Persistence JDBC / shard 0:        success
Persistence JDBC / shard 1:        success
Persistence JDBC / shard 2:        failure
Persistence JDBC / shard 3:        success
Repository hygiene:                success
Vben TypeScript / production build success
UniApp TypeScript / H5 / WeChat:   success
Java 21 / Maven / PostgreSQL:      aggregate inherited shard-2 failure
```

Focused H4 real-MySQL suite at #1429:

```text
7 tests
0 failures
1 error
0 skipped
17.58 s
```

Exactly one method failed:

```text
engineRejectedBecomesFailedTerminal
```

The failed branch built `FAILED_TERMINAL + REJECTED` while retaining the mutable Attempt `engineRequestReference`, which violated `ApprovalMigrationRules.validateAttemptEvidence` and failed closed in the domain constructor.

Permanent correction evidence:

```text
docs/database/MYSQL_8_4_P3_H4_CORRECTION_EVIDENCE.md
```

Correction-1 changed only the new MySQL H4 store so `EngineOutcome.REJECTED` clears the mutable Attempt request reference while VERIFYING/UNKNOWN paths retain it. Tests were not weakened. The failed Head was not rerun.

### Run #1430 — implementation acceptance

```text
Run:        31556628276 / #1430
Head:       6ca5b8a13fb298dbae4fb119104239fd5987f599
Conclusion: completed / success
Physical jobs: 9 / 9 success
```

All physical jobs succeeded:

```text
Repository hygiene                         success
Vben TypeScript / production build        success
UniApp TypeScript / H5 / WeChat           success
Java 21 / Maven core                      success
Persistence JDBC / shard 0                success
Persistence JDBC / shard 1                success
Persistence JDBC / shard 2                success
Persistence JDBC / shard 3                success
Java 21 / Maven / PostgreSQL aggregate    success
```

## Focused H4 matrix at accepted implementation Head

```text
JdbcApprovalMigrationEngineExecutionStoreFactoryTest:
2 / 0 / 0 / 0, 0.012 s
selected shard 3

JdbcApprovalMigrationEngineExecutionStoreMySqlContractTest:
3 / 0 / 0 / 0, 0.013 s
selected shard 3

JdbcApprovalMigrationEngineExecutionStoreMySqlIntegrationTest:
7 / 0 / 0 / 0, 37.333 s
selected shard 2

Focused total:
12 / 0 / 0 / 0
```

## Independent persistence reconstruction

The final Maven artifact for #1430 was independently downloaded and parsed rather than relying on the Actions summary.

```text
selected persistence classes:             137
unique selected classes:                  137
duplicate selections:                       0
Surefire report classes:                  136
expected abstract without report:           1
non-abstract selected without report:       0
extra reports:                               0
selection coverage:                     exact

shard 0: 30
shard 1: 34
shard 2: 35
shard 3: 38

Persistence JDBC:
554 / 0 / 0 / 0
aggregate reported persistence time: 1019.262 s
```

The only selected class without a Surefire report is the expected abstract integration base:

```text
io.github.akaryc1b.approval.persistence.jdbc.AbstractJdbcApprovalMigrationProtocolIntegrationTest
```

## Independent Maven Core reconstruction

`maven-core.log` was independently parsed from per-test-class summary lines:

```text
Maven Core:
1469 / 0 / 0 / 0
```

Therefore:

```text
Maven Core:    1469 / 0 / 0 / 0
Persistence:    554 / 0 / 0 / 0
Combined:      2023 / 0 / 0 / 0
```

## Independently verified implementation Artifacts

All four final #1430 ZIPs were independently downloaded. Local byte size and SHA-256 exactly match GitHub metadata, and every ZIP passes archive integrity verification.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9126275626` | `1127220` | `d7e2dbe9a989a535c4a5f407d7ab507381e3766e23030d3997b9df4003e5ebd9` |
| Vben | `9126243485` | `18915` | `591c3807a6cac73ca3b4a115e1396b1ae2c40db42748f93d30813bd75b233ac0` |
| Mobile | `9126225716` | `9787` | `f2f2dfc02a9297cd8742e84bcd9870b53079849a22587ddd34421f488a4b10de` |
| Hygiene | `9126214036` | `17515` | `b8541980e49110ad78528efba2fb447da2bebb9751d681f39045eed808384066` |

Every artifact is bound to branch `agent/mysql-8-4-production-compatibility` and exact Head `6ca5b8a13fb298dbae4fb119104239fd5987f599`.

## Existing PostgreSQL observation

The pre-existing PostgreSQL D3 store contains the same unconditional final-transition request-reference expression observed during #1429 diagnosis. H4 deliberately did not modify that PostgreSQL authority because this slice is bounded to the new MySQL execution-store compatibility path.

This is retained as an explicit observation, not hidden as an equivalence claim. Later cross-vendor semantic-equivalence acceptance must close or explicitly disposition it before MySQL production support can be claimed.

## Append-only discipline

P3-H4 used no:

```text
same-Head rerun
empty commit workflow trigger
amend
rebase
force push
force merge
```

The failed #1429 Head remains permanent evidence. Correction-1 moved only forward through new commits and a non-force fast-forward formal-branch update.

## Remaining non-scope / release blockers

P3-H4 does not prove or claim:

- real Flowable migration execution on MySQL 8.4;
- Flowable schema creation/upgrade on MySQL;
- D4 `ApprovalMigrationExactVerificationStore` compatibility;
- D6 reconciliation compatibility;
- D7 orchestration compatibility;
- generic `ApprovalMigrationProtocolStore` MySQL compatibility;
- complete PostgreSQL/MySQL D3 semantic-equivalence closure for the noted pre-existing PostgreSQL rejected branch;
- historical MySQL upgrade/restore acceptance;
- complete permanent dual-database CI;
- performance/operations/backup/restore acceptance;
- MySQL production support;
- PR Ready, merge or Issue #91 closure.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_PROVEN
MYSQL_P3_H4_MIGRATION_ENGINE_EXECUTION_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
