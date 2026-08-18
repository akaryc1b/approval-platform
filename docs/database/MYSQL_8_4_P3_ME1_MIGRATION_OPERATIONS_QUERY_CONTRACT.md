# MySQL 8.4 P3-ME1 Migration Operations Query Contract

Status: `TEST_FIRST / STAGING / NOT_ACCEPTED`

## Live selection identity

```text
Repository:     akaryc1b/approval-platform
Issue:          #91
Parent issue:   #62
Overall gate:   #82
Draft PR:       #92
Main baseline:  4af817a46dcbefee7e84b78e022e87f24e0ffcda
Formal source:  8ddee0bfdb5dfe97530e6d94fc876b2fc4bd0cb5
Staging branch: agent/mysql-8-4-p3-me1-operations-query-staging
```

The gate identifier is `P3-ME1`, not `P3-H9`. An independent non-PR AI governance-history
staging branch already uses the H9 label. This migration gate does not reuse, modify or absorb that
parallel AI staging work.

## Remaining migration protocol inventory

The live application ports, JDBC implementations, trusted factories, production wiring and tests
were inspected from the exact formal source Head before selecting this gate.

| Protocol authority | PostgreSQL/general JDBC | MySQL 8.4 peer | Trusted factory/wiring | Current status |
| --- | --- | --- | --- | --- |
| D1 migration binding CAS | `JdbcApprovalMigrationRuntimeBindingCasStore` | `JdbcMySqlApprovalMigrationRuntimeBindingCasStore` | factory present | accepted before H6 |
| D2 attempt provisioning | `JdbcApprovalMigrationAttemptProvisioningStore` | `JdbcMySqlApprovalMigrationAttemptProvisioningStore` | factory present | accepted |
| D3 attempt claim / engine execution | JDBC stores present | MySQL stores present | factories present | accepted through H4 |
| D4 exact verification | `JdbcApprovalMigrationExactVerificationStore` | `JdbcMySqlApprovalMigrationExactVerificationStore` | factory present | accepted through H5 |
| D5 runtime binding CAS | JDBC authority present | MySQL peer present | factory present | accepted |
| D6 reconciliation execution | `JdbcApprovalMigrationReconciliationExecutionStore` | `JdbcMySqlApprovalMigrationReconciliationExecutionStore` | factory present | accepted through H6 |
| D7 orchestration | `JdbcApprovalMigrationOrchestrationStore` | `JdbcMySqlApprovalMigrationOrchestrationStore` | factory present | accepted through H7 |
| D8 plan aggregation | `JdbcApprovalMigrationPlanAggregationStore` | `JdbcMySqlApprovalMigrationPlanAggregationStore` | factory present | implementation Head green |
| M5-E1 operations visibility | `JdbcApprovalMigrationOperationsQuery` | none | production constructs PostgreSQL class directly | first remaining gap |
| M5-E2 diagnostics | `JdbcApprovalMigrationDiagnosticsQuery` | none | production constructs PostgreSQL class directly | downstream; out of scope |

The live M5-E1 port explicitly defines tenant-scoped, read-only operations visibility over immutable
D1-D8 evidence. M5-E2 is a separate downstream diagnostics authority and is not bundled into this
gate.

## NEXT_GATE_SELECTED

```text
P3-ME1 / M5-E1 Migration Operations Visibility Query Semantic Equivalence
PostgreSQL 16 -> MySQL 8.4
```

## SCOPE

This gate adds exactly:

```text
JdbcApprovalMigrationOperationsQueryFactory
JdbcMySqlApprovalMigrationOperationsQuery
ApprovalMigrationOperationsConfiguration -> trusted M5-E1 factory
```

It provides MySQL 8.4 semantic equivalence for:

- tenant-scoped operations summary;
- bounded plan filtering and deterministic pagination;
- exact plan detail reads;
- bounded selected-instance visibility;
- latest D1-D8 aggregate, attempt, verification, completion, conflict and reconciliation evidence;
- exact UTC `datetime(6)` and UUID conversion;
- a read-only repeatable-read snapshot for multi-statement page metadata.

No schema, trigger, Flyway migration, mutation store, Flowable access or runtime-binding change is
required.

## WHY_THIS_IS_NEXT

M5-E1 is the first direct consumer after D8 whose PostgreSQL implementation already exists but whose
MySQL implementation, trusted factory and production wiring are absent. The current PostgreSQL query
uses PostgreSQL-only aggregate `FILTER`, `LATERAL` joins, PostgreSQL UUID extraction and
`OffsetDateTime` result conversion. It therefore cannot be used as the MySQL production authority.

M5-E2 Diagnostics is downstream of M5-E1 and remains excluded. Remaining AI governance-history work
is an independent compatibility stream and is also excluded.

## POSTGRESQL_AUTHORITY

```text
ApprovalMigrationOperationsQuery
JdbcApprovalMigrationOperationsQuery
JdbcApprovalMigrationOperationsQueryIntegrationTest
ApprovalMigrationOperationsConfiguration
```

The PostgreSQL implementation and its regression tests remain unchanged.

## MYSQL_GAP

At gate selection there is no:

```text
JdbcMySqlApprovalMigrationOperationsQuery
JdbcApprovalMigrationOperationsQueryFactory
real MySQL M5-E1 integration suite
trusted vendor-aware M5-E1 production wiring
```

## Fixed invariants

1. Database vendor selection comes only from trusted JDBC metadata.
2. Browser, HTTP, Tenant, Header, AI, Connector, Event and business payloads cannot select SQL.
3. The query is read only and does not acquire business locks or mutate migration evidence.
4. Multi-statement count/page/detail reads use one read-only repeatable-read transaction.
5. Tenant identity remains exact and case-sensitive.
6. Plan and instance ordering remains deterministic and bounded.
7. The latest aggregate and instance evidence follows the existing PostgreSQL precedence rules.
8. MySQL UUID and UTC `datetime(6)` values are converted through the accepted JDBC value adapter.
9. D1-D8 immutable lineage is read, never reconstructed from mutable Flowable authority.
10. PostgreSQL behavior, SQL and tests are not weakened.
11. M5-E2 diagnostics is not implemented in this slice.
12. No `INSERT IGNORE`, `REPLACE`, broad `ON DUPLICATE KEY UPDATE`, `FOREIGN_KEY_CHECKS`, silent
    overwrite, automatic retry or direct `ACT_*` access exists.

## EXPECTED_TEST_MATRIX

| Suite | Required proof |
| --- | --- |
| `JdbcApprovalMigrationOperationsQueryFactoryTest` | exact PostgreSQL 16 / MySQL 8.4 metadata selection and fail-closed unsupported identity |
| `JdbcApprovalMigrationOperationsQueryMySqlContractTest` | read-only MySQL dialect, exact latest-row strategy, JDBC value adapter and production factory wiring |
| `JdbcApprovalMigrationOperationsQueryMySqlIntegrationTest` | real MySQL D1-D8 summary, plan/detail/instance visibility, latest revision, filters, bounds, tenant isolation and no mutation |
| retained `JdbcApprovalMigrationOperationsQueryIntegrationTest` | unchanged PostgreSQL M5-E1 behavior |

No schema/guard contract is required because this gate adds no physical schema authority.

## CI_BUDGET

```text
Target full CI Runs: 1
Maximum full CI Runs: 2
Run A: one natural PR #92 synchronize after staging freeze
Run B: only for one classified, corrected defect
```

Forbidden:

```text
same-head rerun
workflow_dispatch
empty trigger commit
force push
rebase
auto-merge
direct main push
Run C
```

## Authorization boundary

Authorized:

- test-first implementation on the non-PR staging branch;
- local/static validation available in the current environment;
- one ordinary two-parent integration into the formal branch after freeze;
- one natural Run A and, only if required, one correction Run B;
- independent permanent Artifact verification.

Not authorized:

- M5-E2 Diagnostics implementation;
- another migration or AI compatibility gate;
- Ready or merge of PR #92;
- closing Issues #91, #82 or #62;
- MySQL production-support declaration;
- deployment or Production Promotion.

```text
NEXT_GATE_SELECTED=P3_ME1_MIGRATION_OPERATIONS_QUERY
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```