# MySQL 8.4 P3-ME2 Staging Evidence

## Exact staging identities

```text
Repository:       akaryc1b/approval-platform
Formal source:    7a1bcf2c4f1ae69d33f2d717e8b8177aa953af03
Staging branch:   agent/mysql-8-4-p3-me2-diagnostics-query-staging
Test-first Head:  288a57ad6a8a1bf59be9c036604a6ec6b4b5c73b
Implementation:   38d58dbd7d06bb3de03595176f5872b8d4297e70
Implementation tree:
fecd8afbe9389631e00bdb18a0b7bf5f1689d966
```

The test-first Head is one commit ahead of the exact formal source and contains only the bounded
P3-ME2 contract plus factory, static-SQL and real-MySQL integration tests. The implementation Head is
one ordinary child of that test-first Head. No history was squashed, rebased or force-updated.

## Frozen production scope

```text
JdbcApprovalMigrationDiagnosticsQueryFactory
JdbcMySqlApprovalMigrationDiagnosticsQuery
ApprovalMigrationOperationsConfiguration -> trusted diagnostics factory
```

The PostgreSQL diagnostics implementation remains unchanged. The MySQL peer is confined to
persistence infrastructure and preserves the existing application port, bounded filters, page model,
sort vocabulary, timeline vocabulary and fail-closed status display.

No Flyway migration, schema, D1-D8 mutation store, Flowable integration, workflow, Provider,
Connector, Event, deployment or production-support boundary changes.

## Review results

The source review verified:

- database vendor selection originates only from trusted JDBC metadata;
- PostgreSQL continues to use `JdbcApprovalMigrationDiagnosticsQuery`;
- MySQL uses one read-only Repeatable Read transaction for multi-statement page reads;
- latest aggregate, orchestration run/event, kill-switch observation, attempt, reconciliation and
  binding evidence use deterministic `ROW_NUMBER()` authorities;
- `JSON_LENGTH` replaces PostgreSQL `jsonb_array_length`;
- UUID and UTC `datetime(6)` binding/readback use `JdbcDatabaseValueAdapter`;
- tenant and plan predicates remain exact and case-sensitive under the accepted MySQL collation;
- the three server-owned sort orders retain stable sequence and instance-identity tie-breakers;
- evidence-time filters remain inclusive and bounded by the application port;
- result mapping preserves the PostgreSQL hash-precedence and timeline rules;
- unknown or malformed display values fail closed instead of manufacturing authority;
- the query contains no mutation, engine dispatch, lock acquisition, retry, scheduler or Flowable
  `ACT_*` access.

Forbidden-source review found none of:

```text
FILTER (WHERE ...)
JOIN LATERAL
::text / ::uuid / ::jsonb
jsonb_array_length
INSERT INTO
UPDATE
DELETE FROM
INSERT IGNORE
REPLACE
ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS
GET_LOCK
LOCK TABLES
SET GLOBAL
SET PERSIST
ACT_* access
```

## Locally available validation

The exact MySQL production source Git blob was independently reconstructed locally and matched:

```text
186340dbceeb4597c65bbc45e5451eb8c72b6661
```

A Java 21 syntax/type-boundary compilation was executed with minimal dependency stubs:

```text
javac --release 21 -Xlint:all,-serial -Werror
result: success
compiled classes: 35
```

The permanent static contract's complete required-token and forbidden-token matrix was also executed
against the exact source and passed.

The following are intentionally reserved for the natural formal Run:

```text
full Maven reactor:                 NOT_LOCALLY_EXECUTABLE
real MySQL 8.4 Testcontainers:      NOT_LOCALLY_EXECUTABLE
four deterministic JDBC shards:    CI_ONLY
Maven/Hygiene/Vben/Mobile Artifacts:CI_ONLY
```

Those limitations are not represented as successful database acceptance.

## Staging isolation

At freeze time:

```text
staging Pull Requests: 0
staging workflow Runs: 0
workflow dispatches:   0
same-Head reruns:      0
force pushes:          0
rebases:               0
```

## Freeze decision

```text
CODE_FROZEN
SCOPE_FROZEN
STATIC_REVIEW_PASS
LOCAL_AVAILABLE_VALIDATION_PASS
NO_KNOWN_ACTIONABLE_DEFECT
READY_FOR_ONE_ORDINARY_FORMAL_MERGE

CI_BUDGET_TARGET=1
CI_BUDGET_MAXIMUM=2

MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
NO_READY
NO_MAIN_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```
