# MySQL 8.4 P3-H1 — V38 Selection Correction Evidence

## Status

```text
MYSQL_P3_H1_MIGRATION_BINDING_CAS_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

This record retains natural Run #1416 and its fixture-only correction. It does not mark P3-H1 proven and does not authorize Ready, merge, Issue closure, deployment, production migration execution, or MySQL production support.

## Natural failure #1416

```text
Head:       0f5c9540b679e34430e56b8c683b5afc588197e3
Run:        31466151664 / #1416
Conclusion: failure
```

No same-Head rerun was used.

Final physical-job state:

```text
Repository hygiene:                success
Vben TypeScript / production build success
UniApp TypeScript / H5 / WeChat:   success
Java 21 / Maven core:              success
Persistence JDBC / shard 0:        success
Persistence JDBC / shard 1:        failure
Persistence JDBC / shard 2:        success
Persistence JDBC / shard 3:        success
Java 21 / Maven / PostgreSQL:      failure (aggregate inherited shard-1 failure)
```

Run #1416 proved the prior V38 current-authorization correction was effective. The H1 test reached the next immutable selection invariant instead of failing `fk_process_migration_plan_current_authorization_v38`.

## Exact H1 result

Shard 1 selected `JdbcApprovalMigrationRuntimeBindingCasMySqlIntegrationTest` and executed all six methods:

```text
Tests run: 6
Failures:  0
Errors:    6
Skipped:   0
```

Every error occurred while inserting `ap_process_migration_plan_instance` authority before D5 CAS execution:

```text
Check constraint 'ap_process_migration_plan_instance_chk_4' is violated
```

The exact V38 source contract is:

```sql
check (
  jsonb_typeof(active_task_definition_keys)='array'
  and jsonb_array_length(active_task_definition_keys) between 1 and 100
)
```

The H1 fixture incorrectly supplied:

```json
[]
```

The accepted M5-C plan contract requires each selected RUNNING instance to freeze a nonempty canonical active-task key set. The H1 exact-verification fixture already uses the task definition key:

```text
managerApproval
```

Classification:

```text
TEST_FIXTURE_BUG / EMPTY_ACTIVE_TASK_SET_VIOLATES_V38_SELECTION_INVARIANT
```

This is positive MySQL compatibility evidence: the clean MySQL V50 baseline enforces the V38 nonempty migration-plan selection rather than accepting incomplete execution authority.

## Failed evidence part

The failed shard-1 evidence part was retained:

```text
Artifact ID: 9091652394
Bytes:       124836
SHA-256:     a482d856a707f3f8e3e0b8dd7f277ed2ac35123fc64b9a5d6f36ecbef4bace0c
```

This is failed-run evidence and is not an acceptance Artifact.

## Correction-4

Correction branch:

```text
agent/mysql-8-4-p3-h1-migration-binding-cas-correction-4
```

The correction changes only the test authority selection from:

```json
[]
```

to:

```json
["managerApproval"]
```

This preserves the exact same task identity later used by the D4 exact-verification snapshot.

No schema, foreign key, check constraint, trigger, production store, application service, PostgreSQL migration, or PostgreSQL implementation is modified. No `FOREIGN_KEY_CHECKS` bypass is used.

## Forward-only discipline

The retained natural failure chain now includes:

```text
#1412  CHECKSTYLE_UNUSED_IMPORT + stale D5 factory boundary
#1413  RELEASE_DEPLOYMENT_RECORD_ACCESSOR
#1415  MISSING_MIGRATION_PLAN_AUTHORIZATION_FK_PROVENANCE
#1416  EMPTY_ACTIVE_TASK_SET_VIOLATES_V38_SELECTION_INVARIANT
```

All failed Heads remain visible. None is rerun in place. No empty commit, rebase, amend, force push, or history rewrite is used.

## Next gate

Before correction-4 reaches the formal branch, compare must prove:

```text
behind: 0
production changes: 0
allowed changes:
  MySqlH1MigrationPlanAuthorityFixture.java
  MYSQL_8_4_P3_H1_SELECTION_CORRECTION_EVIDENCE.md
```

The next validation must be a new natural Pull Request run on a new Head. If it fails, its exact relational or D5 semantic failure remains visible and receives another forward correction. If it succeeds, H1 remains `STAGED` until independent test reconstruction, Artifact verification, acceptance evidence, `STAGED -> PROVEN`, and final current-Head natural CI all pass.
