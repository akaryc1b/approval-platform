# MySQL 8.4 P3-H1 — Migration Runtime Binding CAS Acceptance Evidence

## Acceptance state

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_G3_RUNTIME_BINDING_STORE_PROVEN
MYSQL_P3_H1_MIGRATION_BINDING_CAS_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

This record accepts only the bounded P3-H1 MySQL 8.4 command-fence and M5-D5 Runtime Binding completion cluster. It does not claim full M5 migration compatibility, real Flowable migration execution on MySQL, historical upgrade/restore acceptance, complete dual-database CI, operations acceptance, or MySQL production support.

## Exact implementation identity

```text
main:          1747b22123fd71cccd8334853ad7060c6645b443
formal branch: agent/mysql-8-4-production-compatibility
accepted implementation Head:
               39eb608008107b07422446edef5a26a3a5470773
natural Run:   31466834558 / #1417
conclusion:    success
```

The accepted implementation Head is a forward descendant of the P3-G3 accepted Head and remains unmerged on Draft PR #92.

## Accepted production boundary

P3-H1 accepts:

```text
JdbcApprovalInstanceCommandFenceFactory
JdbcMySqlApprovalInstanceCommandFence
JdbcApprovalMigrationRuntimeBindingCasStoreFactory
JdbcMySqlApprovalMigrationRuntimeBindingCasStore
JdbcApprovalMigrationBindingRevisionReader vendor-safe UUID binding
ApprovalRuntimeBindingEvidenceConfiguration trusted fence factory
ApprovalMigrationExecutionConfiguration trusted D5 CAS factory
```

PostgreSQL continues to use the existing authorities:

```text
JdbcApprovalInstanceCommandFence
JdbcApprovalMigrationRuntimeBindingCasStore
PostgresSerializedApprovalMigrationRuntimeBindingCasStore
```

No PostgreSQL migration or PostgreSQL production implementation was changed by P3-H1.

## Accepted MySQL semantics

The accepted real-MySQL path proves two independent logical serialization authorities:

```text
approval-migration-binding-cas:v1:<tenantId>:<attemptId>
approval-instance-command:v1:<tenantId>:<approvalInstanceId>
```

The D5 completion local transaction proves:

1. exact per-attempt serialization before replay observation;
2. shared per-instance serialization with normal business commands;
3. exact VERIFYING Attempt authority;
4. exact D4 `EXACT_TARGET_RUNTIME` verification authority;
5. exact consumed Plan / running Intent / PlanConsumption authority;
6. active owned migration Fence authority;
7. exact source Runtime Binding and approval-instance projection;
8. exact target immutable Release Package and DEPLOYED deployment;
9. source binding revision evidence admission for the initial revision-1 case;
10. binding revision CAS `n -> n + 1`;
11. target binding revision evidence append;
12. approval-instance target projection CAS;
13. immutable completion evidence append;
14. Attempt `VERIFYING -> SUCCEEDED / CONFIRMED` plus event;
15. Fence `ACTIVE -> RELEASED` plus event;
16. governed audit sink participation;
17. exact completion replay;
18. changed-payload replay rejection;
19. stale binding conflict -> `RECONCILING / VERIFICATION_MISMATCH` with retained active Fence;
20. exact conflict replay;
21. whole-local-transaction rollback after an injected audit failure;
22. concurrent same-attempt callers produce exactly one completion and one replay.

The shared `JdbcApprovalMigrationBindingRevisionReader` also reads the exact MySQL binding revision with canonical UUID binding.

## Hash compatibility

P3-H1 retains the existing D5 evidence versions:

```text
m5-runtime-binding-v44
m5-runtime-binding-history-v44
m5-instance-completion-v44
m5-binding-cas-conflict-v44
m5-runtime-binding-cas-request-v1
```

The MySQL D5 implementation reproduces the existing ordered evidence field contract in Java rather than inventing a database-specific product hash version.

## Natural implementation Run #1417

```text
Run ID:     31466834558
Run number: #1417
Head:       39eb608008107b07422446edef5a26a3a5470773
Conclusion: success
```

All nine physical Jobs succeeded:

| Job | ID | Result |
| --- | ---: | --- |
| Repository hygiene | `93701373291` | success |
| UniApp TypeScript / H5 / WeChat | `93701373295` | success |
| Java 21 / Maven core | `93701373301` | success |
| Vben TypeScript / production build | `93701373319` | success |
| Persistence JDBC / shard 1 | `93701373343` | success |
| Persistence JDBC / shard 2 | `93701373346` | success |
| Persistence JDBC / shard 0 | `93701373352` | success |
| Persistence JDBC / shard 3 | `93701373366` | success |
| Java 21 / Maven / PostgreSQL aggregate | `93702026347` | success |

No same-Head rerun was used.

## Independent Persistence reconstruction

The final Maven Artifact was independently downloaded and parsed rather than relying only on the workflow summary.

```text
selected test classes:                  128
unique selected test classes:           128
Surefire report classes:                127
expected abstract without report:         1
abstract class:
  AbstractJdbcApprovalMigrationProtocolIntegrationTest
duplicate selections:                     0
non-abstract selected without report:     0
selection coverage:                    exact

tests:                                  521
failures:                                 0
errors:                                   0
skipped:                                  0
aggregate reported persistence time: 953.957 s
```

Deterministic shard distribution:

```text
shard 0: 30
shard 1: 33
shard 2: 30
shard 3: 35
unique: 128 / 128
```

## Focused P3-H1 matrix

The four permanent H1 suites are selected exactly once:

```text
JdbcApprovalInstanceCommandFenceFactoryTest
  shard 3
  2 / 0 / 0 / 0
  reported time: 0.005 s

JdbcApprovalMigrationRuntimeBindingCasStoreFactoryTest
  shard 3
  2 / 0 / 0 / 0
  reported time: 0.073 s

JdbcApprovalMigrationRuntimeBindingCasMySqlContractTest
  shard 0
  3 / 0 / 0 / 0
  reported time: 0.020 s

JdbcApprovalMigrationRuntimeBindingCasMySqlIntegrationTest
  shard 1
  6 / 0 / 0 / 0
  reported time: 36.457 s
```

Focused H1 total:

```text
13 / 0 failures / 0 errors / 0 skipped
```

The six real-MySQL integration methods prove:

```text
concurrentSameAttemptSerializesToOneCompletionAndOneReplay
commandFenceRequiresTransactionAndBlocksBusinessCommands
changedPayloadReplayIsRejectedWithoutFurtherMutation
staleBindingRecordsConflictRetainsFenceAndReplaysWithoutMutation
auditFailureAfterRealCasMutationsRollsEntireLocalTransactionBack
exactCompletionCommitsBindingProjectionHistoryAttemptFenceAndReplay
```

## Independent Maven Core reconstruction

The Maven Core log was independently parsed two ways:

1. summing every per-test-class Surefire summary line;
2. summing every module-level Surefire aggregate line.

Both produce the same result:

```text
Maven Core: 1469 / 0 failures / 0 errors / 0 skipped
```

Combined accepted backend test count:

```text
Maven Core:       1469 / 0 / 0 / 0
Persistence JDBC: 521 / 0 / 0 / 0
Combined:         1990 / 0 / 0 / 0
```

## Independently verified implementation Artifacts

All four final #1417 ZIPs were independently downloaded into a local evidence environment. For every Artifact:

- local byte size exactly matches GitHub metadata;
- local SHA-256 exactly matches GitHub digest metadata;
- ZIP integrity verification succeeds;
- the Artifact is bound to formal branch `agent/mysql-8-4-production-compatibility`;
- the Artifact is bound to exact Head `39eb608008107b07422446edef5a26a3a5470773`.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9091906150` | `1088840` | `aff1cb4b9f81582e46910312f8c4969e8e0e06aca5c09a434d91981c47215d88` |
| Vben | `9091865218` | `18926` | `2cab19040a7cabd9c8db4d5e38e945e05811559998f43ec655bfb2425bdf69b6` |
| Mobile | `9091846820` | `9816` | `21489c32e436828e4a25fd69283b02aa153bb69496fe56d028b29c6a5bf269da` |
| Hygiene | `9091829790` | `17484` | `0c604f9f142d435d77679f9b9d19599534ccf46135ceab789aadd502a2559189` |

Artifact expiration:

```text
2026-11-09T06:54:26Z
```

## Retained natural failure and correction trail

No failed Head was rerun in place and no failure was erased.

### #1412

```text
Head: 9fe38f566dd372469f241a9b6d208060d7a9b28a
Run:  31463451055 / #1412
```

Classifications:

```text
WORKFLOW_FORMATTING / CHECKSTYLE_UNUSED_IMPORT
STATIC_CONTRACT_STALE_AFTER_TRUSTED_VENDOR_FACTORY
```

The correction removed the unused import and upgraded the old M5-D5 Node boundary so server wiring must use the trusted factory while the PostgreSQL factory branch continues to require the existing serialized PostgreSQL authority.

### #1413

```text
Head: 3733a5dff5c784ab74ce23a98a7ca4382b5389bb
Run:  31464617235 / #1413
```

Classification:

```text
TEST_FIXTURE_API_BUG / RELEASE_DEPLOYMENT_RECORD_ACCESSOR
```

The one-line correction uses `deploymentRecordId()`.

### #1415

```text
Head: 4dbc6d6a433d765e3a34800729890bee8bd6ff60
Run:  31465008588 / #1415
```

Classification:

```text
TEST_FIXTURE_BUG / MISSING_MIGRATION_PLAN_AUTHORIZATION_FK_PROVENANCE
```

All six H1 integration methods reached the real MySQL V38 current-authorization FK and were correctly rejected because the fixture inserted a final-state Plan before its immutable Authorization existed.

Correction established the real production ordering:

```text
Plan PROPOSED
-> immutable Authorization
-> bind current Authorization
-> Plan CONSUMED
-> Intent / PlanConsumption
```

### #1416

```text
Head: 0f5c9540b679e34430e56b8c683b5afc588197e3
Run:  31466151664 / #1416
```

Classification:

```text
TEST_FIXTURE_BUG / EMPTY_ACTIVE_TASK_SET_VIOLATES_V38_SELECTION_INVARIANT
```

All six H1 integration methods crossed the authorization FK and were correctly rejected by `ap_process_migration_plan_instance_chk_4`, whose exact source invariant requires a JSON active-task array with length `1..100`.

The fixture correction freezes the same active task later represented by exact verification:

```json
["managerApproval"]
```

The correction weakens no constraint and disables no FK.

## Evidence documents

The retained correction trail is recorded in:

```text
docs/database/MYSQL_8_4_P3_H1_CORRECTION_EVIDENCE.md
docs/database/MYSQL_8_4_P3_H1_SELECTION_CORRECTION_EVIDENCE.md
```

## Explicit non-scope retained

P3-H1 acceptance does not imply acceptance of:

- migration Plan creation/authorization/consumption as a complete MySQL Store workflow;
- migration Intent creation/admission as a complete MySQL Store workflow;
- Attempt provisioning/claim/lease recovery as a complete MySQL Store workflow;
- D3 real engine dispatch compatibility on MySQL;
- D4 exact verification creation/readback compatibility as a complete MySQL authority;
- D6 reconciliation;
- D7 orchestration;
- real Flowable migration execution on MySQL;
- complete MySQL migration protocol compatibility;
- remaining PostgreSQL-specific persistence stores;
- historical MySQL upgrade/restore rehearsals;
- permanent complete dual-database CI;
- operations, backup/restore, performance or production promotion;
- Ready transition or merge of PR #92;
- closure of Issue #91.

The H1 real-MySQL suite seeds D3/D4 authority rows solely as complete relational prerequisites for testing D5 completion. That fixture construction is not a production-support claim for those upstream stores.

## Acceptance decision

The implementation Head `39eb608008107b07422446edef5a26a3a5470773` is accepted as the P3-H1 implementation candidate because:

- the complete natural workflow is 9/9 success;
- focused H1 tests are 13/13 success;
- the full persistence selection is exact and all 521 tests pass;
- Maven Core contributes another 1469 passing tests;
- all four final Artifacts were independently verified;
- every natural failure remains permanently visible with forward-only corrections;
- PostgreSQL behavior remains unchanged;
- the overall MySQL support marker remains fail-closed.

The documentation acceptance Head must now receive its own new natural PR validation before P3-H1 is finally closed as current-Head `PROVEN` evidence.
