# MySQL 8.4 P3-H1 — Correction Evidence

## Status

```text
MYSQL_P3_H1_MIGRATION_BINDING_CAS_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

This record retains the first natural P3-H1 failure and the forward-only corrections made before the next natural Pull Request validation. It does not mark P3-H1 proven and does not authorize Ready, merge, Issue closure, deployment, production migration execution, or MySQL production support.

## Failed implementation Head

```text
Head: 9fe38f566dd372469f241a9b6d208060d7a9b28a
Run:  31463451055 / #1412
Conclusion: failure
```

The Head was not rerun. No empty commit, amend, rebase, force push, workflow rerun, or history rewrite was used.

## Natural Run #1412 outcome

The natural Pull Request workflow completed with the following physical-job state:

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

P3-H1 real-MySQL behavior was not executed by this Run. Maven Core and all four Persistence shards stopped at the same Checkstyle error before H1 test compilation/execution.

## Natural failure classification A

```text
WORKFLOW_FORMATTING / CHECKSTYLE_UNUSED_IMPORT
```

The new H1 integration test contained one unused static import:

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
```

Checkstyle rejected the Persistence module before H1 Java compilation or test execution. Maven Core reached the same Persistence Checkstyle gate and failed for the same reason.

Correction: remove the unused import only as part of the forward correction Head.

## Natural failure classification B

```text
STATIC_CONTRACT_STALE_AFTER_TRUSTED_VENDOR_FACTORY
```

The retained M5-D5 Node boundary still required `ApprovalMigrationExecutionConfiguration` to construct `PostgresSerializedApprovalMigrationRuntimeBindingCasStore` directly. P3-H1 intentionally moved server wiring to the trusted JDBC-metadata factory:

```text
JdbcApprovalMigrationRuntimeBindingCasStoreFactory.create(...)
```

The old assertion therefore rejected the new architecture even though the PostgreSQL branch still retains the original cross-node serializer.

Correction: the permanent D5 boundary now requires all of the following simultaneously:

1. server configuration uses `JdbcApprovalMigrationRuntimeBindingCasStoreFactory.create(...)`;
2. the factory's PostgreSQL branch constructs `PostgresSerializedApprovalMigrationRuntimeBindingCasStore`;
3. the factory's MySQL branch constructs `JdbcMySqlApprovalMigrationRuntimeBindingCasStore`;
4. the original PostgreSQL `pg_advisory_lock` and explicit `pg_advisory_unlock` assertions remain;
5. the original PostgreSQL concurrent exact-replay test remains required;
6. no scheduler, public controller, retry authority, or Flowable call is introduced by H1.

No PostgreSQL production implementation or PostgreSQL migration was changed by this correction.

## Pre-next-run static fixture/API audit

After classifying #1412, a correction-branch static audit found additional stale assumptions in the new H1 MySQL integration fixture. They were not reached by #1412 because Checkstyle stopped the build first and therefore are not represented as #1412 natural-run root causes.

Classification:

```text
PRE_RUN_STATIC_FIXTURE_API_DRIFT
```

The audit found and corrected these fixture-only issues:

- `BindingCasResult` accessors aligned to the current port:
  - `completionEvidence()` instead of a stale `completion()` assumption;
  - `conflictEvidence()` instead of a stale `conflict()` assumption;
- every `CompletionRequest` call now follows the current exact order:
  `tenant, attempt, verification, worker, attemptRevision, fenceRevision, bindingRevision, happenedAt, requestId, traceId`;
- the fixture now serializes a real current `ApprovalMigrationAttempt` rather than an ad-hoc map;
- the fixture now serializes a real current `ApprovalMigrationCommandFence` rather than a stale constructor/payload shape;
- the fixture now serializes a real `ApprovalMigrationExactVerification` with a bounded `ApprovalMigrationEngineSnapshot` whose server-derived classification is `EXACT_TARGET_RUNTIME`;
- Plan, Intent and Plan Consumption inserts use the current durable D5 authority columns retained by the accepted PostgreSQL D5 fixture;
- Engine Request and Engine Outcome inserts use the exact V41 durable column contract rather than a simplified non-existent shape;
- V41 request lineage uses the original earlier attempt/fence revisions and the outcome uses `CALL_RETURNED_AWAITING_VERIFICATION`; D4 verification remains bound to the later `VERIFYING` attempt revision consumed by D5;
- the fixture does not disable `FOREIGN_KEY_CHECKS`, alter the MySQL schema, bypass constraints, or call an unaccepted MySQL D3/D4 production store.

This audit changes test fixture construction only. It does not claim D3 engine-dispatch compatibility or D4 verification-store compatibility on MySQL; those rows are upstream authority fixtures consumed solely to exercise the bounded D5 completion transaction.

## Correction branch discipline

Correction branch:

```text
agent/mysql-8-4-p3-h1-migration-binding-cas-correction-1
```

The correction remains forward-only. The failed Head `9fe38f566dd372469f241a9b6d208060d7a9b28a` and Run #1412 stay permanently visible.

One intermediate Contents-API rewrite produced a broader-than-intended textual diff in the H1 integration test. A mandatory compare audit detected it before formal-branch promotion. The subsequent fixture/API correction deliberately replaced that intermediate content with the current-contract fixture. The intermediate commit remains visible on the correction branch; it is not represented as an independently accepted implementation state.

## Production-code boundary

Relative to failed Head `9fe38f566dd372469f241a9b6d208060d7a9b28a`, this correction is required to have zero production-code changes. Its allowed net files are limited to:

```text
scripts/tests/m5-d5-runtime-binding-cas-boundary.test.mjs
server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationRuntimeBindingCasMySqlIntegrationTest.java
docs/database/MYSQL_8_4_P3_H1_CORRECTION_EVIDENCE.md
```

Before formal promotion, a compare gate must confirm exactly this boundary and `behind=0`.

## Next gate

The correction Head may be non-force fast-forwarded to `agent/mysql-8-4-production-compatibility` only after the exact compare passes. The next validation must be a new natural Pull Request Run on the new Head. Run #1412 must not be rerun.

If that natural Run fails, its Head and failure are retained and corrected with another forward commit. If it succeeds, H1 still remains `STAGED` until independent test reconstruction, final Artifact verification, acceptance-document closure, `STAGED -> PROVEN`, and a final current-Head natural CI all pass.
