# MySQL 8.4 P3-H4 — Correction Evidence

## Status

```text
MYSQL_P3_H4_MIGRATION_ENGINE_EXECUTION_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

This record permanently retains the first natural P3-H4 failure and its forward-only product correction. It does not mark H4 proven and does not authorize Flowable MySQL execution, D4/D6/D7 compatibility, PR Ready, merge, Issue closure or MySQL production support.

## Failed Head

```text
Head:       f9730b610620aeb922cc9b3ec27f9ada91ecab93
Run:        31556137343 / #1429
Conclusion: failure
```

Run #1429 was a natural pull-request validation run. The failed Head is not rerun. No empty commit, amend, rebase, force push or history rewrite is used.

## Physical result

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

The failing shard had already entered Surefire. H3 real-MySQL claim coverage in the same shard remained green.

## Focused H4 result at failed Head

```text
JdbcApprovalMigrationEngineExecutionStoreMySqlIntegrationTest:
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

The other six real-MySQL H4 methods passed naturally, covering:

- prepare + returned call -> VERIFYING;
- ambiguous result -> UNKNOWN and duplicate finalization rejection;
- stale tenant/Attempt/Fence/RuntimeBinding/target fail-closed behavior;
- concurrent prepare one-winner behavior;
- prepare audit rollback;
- finalization audit rollback.

## Exact failure

Classification:

```text
PRODUCT_BUG / REJECTED_FINALIZATION_REQUEST_REFERENCE
```

The rejected finalization branch correctly selected:

```text
AttemptStatus.FAILED_TERMINAL
EngineOutcome.REJECTED
FailureClass.ENGINE_REJECTED
```

but it also unconditionally copied the prior `engineRequestReference` into the new `ApprovalMigrationAttemptTransition`.

The domain contract in `ApprovalMigrationRules.validateAttemptEvidence` deliberately requires an engine request reference only for:

```text
ENGINE_REQUESTED
VERIFYING
UNKNOWN
RECONCILING
SUCCEEDED
FAILED_TERMINAL/BLOCKED_STALE with EngineOutcome.UNKNOWN
```

Therefore `FAILED_TERMINAL + REJECTED` must not retain the request reference in mutable Attempt state. The immutable `ap_process_migration_engine_request` and `ap_process_migration_engine_outcome` rows remain the durable dispatch lineage.

The domain constructor failed closed before the rejected Attempt transition could be written:

```text
IllegalArgumentException:
attempt engine request evidence is inconsistent
```

## Forward-only correction

Correction-1 changes only the new MySQL H4 production implementation:

```text
EngineOutcome.REJECTED
-> transition engineRequestReference = null

all other accepted outcomes
-> preserve current engineRequestReference
```

This preserves the existing domain protocol:

- returned call -> VERIFYING retains request reference;
- ambiguous result -> UNKNOWN retains request reference;
- rejected terminal state clears mutable Attempt request reference;
- immutable request/outcome evidence remains append-only and unchanged.

No test expectation is weakened.

## Existing PostgreSQL note

The pre-existing PostgreSQL `JdbcApprovalMigrationEngineExecutionStore` currently contains the same unconditional final-transition request-reference expression. H4 does not modify that already-existing PostgreSQL authority because this slice is bounded to the new MySQL persistence authority and PostgreSQL production code was an explicit non-scope condition.

This evidence records the observation without claiming the PostgreSQL rejected branch is corrected or accepted by H4. Cross-vendor semantic-equivalence closure remains required by the later compatibility acceptance work before MySQL can be production supported.

## Non-claims

This correction does not itself authorize:

- H4 PROVEN status;
- real Flowable migration execution on MySQL;
- D4 exact verification compatibility;
- D6 reconciliation compatibility;
- D7 orchestration compatibility;
- full PostgreSQL/MySQL semantic-equivalence acceptance;
- MySQL production support;
- PR Ready or merge;
- Issue #91 closure.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_PROVEN
MYSQL_P3_H4_MIGRATION_ENGINE_EXECUTION_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
