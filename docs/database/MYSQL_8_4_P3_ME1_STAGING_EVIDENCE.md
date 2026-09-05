# MySQL 8.4 P3-ME1 Migration Operations Query Staging Evidence

## Status

```text
CODE_FROZEN
SCOPE_FROZEN
STATIC_REVIEW_PASS
LOCAL_AVAILABLE_VALIDATION_PASS
NO_KNOWN_ACTIONABLE_DEFECT
STAGING_ONLY
NOT_ACCEPTED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

This record freezes the non-PR staging candidate for:

```text
P3-ME1 / M5-E1 Migration Operations Visibility Query Semantic Equivalence
PostgreSQL 16 -> MySQL 8.4
```

It does not accept MySQL 8.4 for production, authorize PR #92 to leave Draft, or start M5-E2.

## Exact frozen implementation identity

```text
Repository:     akaryc1b/approval-platform
Main baseline:  4af817a46dcbefee7e84b78e022e87f24e0ffcda
Formal source:  8ddee0bfdb5dfe97530e6d94fc876b2fc4bd0cb5
Staging branch: agent/mysql-8-4-p3-me1-operations-query-staging
Implementation Head before this evidence record:
                fe1736d6d5c25882cad34d1bc3305c39bf2d7b30
Implementation tree:
                8a2fd0872d6c97f91e01d2084d13e229a722a203
```

The evidence-record commit changes documentation only. Production and test source remain frozen at
the implementation tree above.

## Frozen scope

Production changes are limited to:

```text
JdbcApprovalMigrationOperationsQueryFactory
JdbcMySqlApprovalMigrationOperationsQuery
ApprovalMigrationOperationsConfiguration -> factory for M5-E1 only
```

Test-first additions are limited to:

```text
JdbcApprovalMigrationOperationsQueryFactoryTest
JdbcApprovalMigrationOperationsQueryMySqlContractTest
JdbcApprovalMigrationOperationsQueryMySqlIntegrationTest
```

The source-driven gate contract is:

```text
docs/database/MYSQL_8_4_P3_ME1_MIGRATION_OPERATIONS_QUERY_CONTRACT.md
```

Unchanged authorities include:

```text
JdbcApprovalMigrationOperationsQuery
JdbcApprovalMigrationOperationsQueryIntegrationTest
JdbcApprovalMigrationDiagnosticsQuery
ApprovalMigrationDiagnosticsQuery
all Flyway migrations
all MySQL V50 guards
all workflows
```

No M5-E2, AI governance-history, Connector, Event, schema, Flowable, performance, operations,
backup/restore, deployment or production-promotion work is included.

## Semantic review

The frozen candidate preserves:

1. trusted JDBC-metadata vendor selection;
2. the existing PostgreSQL M5-E1 implementation unchanged;
3. MySQL-only SQL isolated in persistence infrastructure;
4. tenant-exact and case-sensitive filtering;
5. bounded deterministic plan and instance pagination;
6. latest aggregate selection by revision and aggregate identity;
7. latest attempt, instance-completion, binding-conflict and reconciliation selection by the same
   authoritative ordering used by PostgreSQL;
8. the existing evidence precedence for selected-instance visibility;
9. accepted UUID and UTC `datetime(6)` JDBC conversion;
10. read-only repeatable-read transactions for multi-statement count/page consistency;
11. no business lock, mutation, retry, silent overwrite or direct Flowable-table access;
12. no reduction of PostgreSQL guards or regression tests.

## Static review

Verified from the frozen tree:

```text
required MySQL window-function latest-row strategy present
PostgreSQL FILTER absent from MySQL implementation
PostgreSQL LATERAL absent from MySQL implementation
trusted factory contains exact POSTGRESQL and MYSQL branches
production configuration uses the M5-E1 factory
M5-E2 remains directly wired to its unchanged PostgreSQL implementation
forbidden mutation SQL absent
INSERT IGNORE absent
REPLACE absent
ON DUPLICATE KEY UPDATE absent
FOREIGN_KEY_CHECKS absent
GET_LOCK absent
ACT_* table reads absent
workflow changes absent
migration/schema changes absent
```

The Flowable static rejection was deliberately scoped to actual `FROM ACT_*` or `JOIN ACT_*`
patterns so legitimate identifiers such as `exact_*` cannot create a false positive.

The factory contract requires exact unsupported-vendor and unsupported-version exception classes;
it does not accept a broad generic exception as proof.

## Local available validation

The current execution environment cannot obtain the full Maven dependency graph or launch the real
MySQL 8.4 Testcontainers suite without using the permanent PR workflow. Those items are recorded as:

```text
FULL_MAVEN_REACTOR=NOT_LOCALLY_EXECUTABLE
MYSQL_8_4_TESTCONTAINERS=NOT_LOCALLY_EXECUTABLE
```

Available validation completed before freeze:

```text
source-level Java construction and import review
factory/vendor selection review
production wiring review
forbidden-token review
scope-diff review
newline/checkstyle rule review
minimal relational SQL-shape execution
window-function latest aggregate selection
window-function latest attempt/reconciliation selection
summary CASE aggregation
plan page/count shape
instance evidence join shape
```

The relational SQL-shape execution used a minimal local SQLite model only to detect malformed SQL
composition, duplicate aliases, window-selection mistakes and count/page inconsistencies. It is not
represented as MySQL compatibility proof. MySQL syntax, Connector/J conversion, transaction
isolation and real D1-D8 fixture execution remain mandatory in the single natural formal Run A.

## Expected permanent matrix

```text
JdbcApprovalMigrationOperationsQueryFactoryTest
JdbcApprovalMigrationOperationsQueryMySqlContractTest
JdbcApprovalMigrationOperationsQueryMySqlIntegrationTest
retained JdbcApprovalMigrationOperationsQueryIntegrationTest
```

Every target class must be selected exactly once and report `skipped = 0` in the permanent Maven
Artifact.

## CI budget and authorization

```text
Target full CI Runs: 1
Maximum full CI Runs: 2
Run A: natural PR #92 synchronize only
Run B: only after one classified and corrected defect
NO_RUN_C
```

Not authorized:

```text
workflow_dispatch
same-head rerun
empty trigger commit
force push
rebase
auto-merge
direct main push
Ready
merge PR #92
close #91, #82 or #62
MYSQL_8_4_PRODUCTION_SUPPORTED
deployment
Production Promotion
```

```text
P3_ME1_STAGING_FROZEN
P3_ME1_FORMAL_CI_NOT_YET_RUN
PR_92_REMAINS_OPEN_DRAFT
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```