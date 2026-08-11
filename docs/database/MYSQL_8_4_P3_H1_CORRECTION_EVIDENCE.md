# MySQL 8.4 P3-H1 — Correction Evidence

## Status

```text
MYSQL_P3_H1_MIGRATION_BINDING_CAS_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

This record retains the natural P3-H1 failures and the forward-only corrections made before subsequent natural Pull Request validation. It does not mark P3-H1 proven and does not authorize Ready, merge, Issue closure, deployment, production migration execution, or MySQL production support.

## Natural failure #1412

```text
Head:       9fe38f566dd372469f241a9b6d208060d7a9b28a
Run:        31463451055 / #1412
Conclusion: failure
```

The Head was not rerun. No empty commit, amend, rebase, force push, workflow rerun, or history rewrite was used.

Final physical-job state:

```text
Java 21 / Maven core:              failure
Persistence JDBC / shard 0:        failure
Persistence JDBC / shard 1:        failure
Persistence JDBC / shard 2:        failure
Persistence JDBC / shard 3:        failure
Repository hygiene:                failure
Vben TypeScript / production build success
UniApp TypeScript / H5 / WeChat:   success
Java 21 / Maven / PostgreSQL:      failure (aggregate inherited Maven failure)
```

P3-H1 real-MySQL behavior was not executed by #1412. Maven Core and all four Persistence shards stopped at the same Checkstyle error before H1 test compilation/execution.

### #1412 classification A

```text
WORKFLOW_FORMATTING / CHECKSTYLE_UNUSED_IMPORT
```

The new H1 integration test contained one unused static `assertFalse` import. Checkstyle rejected the Persistence module before H1 Java compilation or test execution.

Correction: remove the unused import.

### #1412 classification B

```text
STATIC_CONTRACT_STALE_AFTER_TRUSTED_VENDOR_FACTORY
```

The retained M5-D5 Node boundary still required `ApprovalMigrationExecutionConfiguration` to construct `PostgresSerializedApprovalMigrationRuntimeBindingCasStore` directly. P3-H1 intentionally moved server wiring to the trusted JDBC-metadata factory:

```text
JdbcApprovalMigrationRuntimeBindingCasStoreFactory.create(...)
```

The corrected permanent D5 boundary requires all of the following simultaneously:

1. server configuration uses `JdbcApprovalMigrationRuntimeBindingCasStoreFactory.create(...)`;
2. the factory's PostgreSQL branch constructs `PostgresSerializedApprovalMigrationRuntimeBindingCasStore`;
3. the factory's MySQL branch constructs `JdbcMySqlApprovalMigrationRuntimeBindingCasStore`;
4. the original PostgreSQL `pg_advisory_lock` and explicit `pg_advisory_unlock` assertions remain;
5. the original PostgreSQL concurrent exact-replay test remains required;
6. no scheduler, public controller, retry authority, or Flowable call is introduced by H1.

No PostgreSQL production implementation or PostgreSQL migration changed.

## Pre-#1413 static fixture/API audit

After classifying #1412, a correction-branch static audit found additional stale assumptions in the H1 MySQL integration fixture. They were not reached by #1412 because Checkstyle stopped the build first and are therefore not #1412 natural-run root causes.

Classification:

```text
PRE_RUN_STATIC_FIXTURE_API_DRIFT
```

The audit corrected fixture-only issues:

- `BindingCasResult` accessors aligned to `completionEvidence()` / `conflictEvidence()`;
- every `CompletionRequest` uses the current `happenedAt, requestId, traceId` ordering;
- the fixture serializes real `ApprovalMigrationAttempt`, `ApprovalMigrationCommandFence` and `ApprovalMigrationExactVerification` objects;
- the verification contains a bounded real `ApprovalMigrationEngineSnapshot` with server-consistent `EXACT_TARGET_RUNTIME` classification;
- Plan, Intent and Plan Consumption inserts use the current durable D5 authority columns;
- Engine Request and Engine Outcome inserts use the exact V41 durable table contract;
- request/outcome/verification revisions retain the D3 -> D4 -> D5 lineage used by the accepted PostgreSQL D5 fixture;
- no `FOREIGN_KEY_CHECKS` bypass, schema mutation or unaccepted MySQL D3/D4 production-store claim was added.

This correction only constructs upstream authority rows consumed by D5; it does not claim MySQL D3 engine-dispatch or D4 verification-store compatibility.

One intermediate Contents-API rewrite produced a broader-than-intended textual diff. A mandatory compare audit detected it before formal promotion. The subsequent fixture/API correction deliberately replaced the intermediate content with the current-contract fixture. The intermediate commit remains visible in correction-branch history and was never treated as an accepted Head.

## Natural failure #1413

```text
Head:       3733a5dff5c784ab74ce23a98a7ca4382b5389bb
Run:        31464617235 / #1413
Conclusion: failure
```

Run #1413 proved the #1412 corrections were effective:

- Repository hygiene passed, including the upgraded M5-D5 trusted-factory boundary;
- Checkstyle passed for `approval-persistence-jdbc`;
- Maven reached `testCompile`;
- UniApp succeeded;
- the four deterministic Persistence jobs and Maven Core then encountered the same single H1 test-compilation error;
- the PostgreSQL aggregate failed only because the required Maven evidence parts were failed.

The exact compiler error was:

```text
JdbcApprovalMigrationRuntimeBindingCasMySqlIntegrationTest.java:[572,29]
cannot find symbol: method deploymentId()
location: ApprovalReleaseDeployment
```

The domain record exposes `deploymentRecordId()`, not `deploymentId()`.

Classification:

```text
TEST_FIXTURE_API_BUG / RELEASE_DEPLOYMENT_RECORD_ACCESSOR
```

P3-H1 real-MySQL behavior still did not execute in #1413 because `testCompile` stopped before Surefire.

Correction-2 is intentionally one-line test-fixture logic:

```text
targetDeployment.deploymentId()
->
targetDeployment.deploymentRecordId()
```

A post-write compare independently verified the integration-test net diff from the #1413 Head as exactly:

```text
additions: 1
deletions: 1
production changes: 0
```

Run #1413 is not rerun.

## Correction discipline

Correction branches used:

```text
agent/mysql-8-4-p3-h1-migration-binding-cas-correction-1
agent/mysql-8-4-p3-h1-migration-binding-cas-correction-2
```

The failed Heads `9fe38f566dd372469f241a9b6d208060d7a9b28a` and `3733a5dff5c784ab74ce23a98a7ca4382b5389bb`, together with Runs #1412 and #1413, remain permanently visible.

No failed Head was rerun. No empty commit, rebase, amend, force push, or history rewrite was used.

## Production-code boundary

Corrections after the original H1 implementation Head are evidence/test-boundary corrections only. In particular, correction-2 relative to the #1413 Head is allowed to modify only:

```text
server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationRuntimeBindingCasMySqlIntegrationTest.java
docs/database/MYSQL_8_4_P3_H1_CORRECTION_EVIDENCE.md
```

Before formal promotion, a compare gate must confirm exactly this boundary and `behind=0`.

## Next gate

The correction-2 Head may be non-force fast-forwarded to `agent/mysql-8-4-production-compatibility` only after the exact compare passes. The next validation must be a new natural Pull Request Run on the new Head. Runs #1412 and #1413 must not be rerun.

If the next natural Run fails, its Head and failure remain visible and are corrected with another forward commit. If it succeeds, H1 still remains `STAGED` until independent test reconstruction, final Artifact verification, acceptance-document closure, `STAGED -> PROVEN`, and a final current-Head natural CI all pass.
