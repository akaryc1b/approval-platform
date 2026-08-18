# MySQL 8.4 P3-ME2 Migration Diagnostics Query Contract

Status: `TEST_FIRST_STAGING / NOT_ACCEPTED`

## Tracking and exact baseline

```text
Repository:      akaryc1b/approval-platform
Issue:           #91
Parent issue:    #62
Overall gate:    #82
Draft PR:        #92
Main baseline:   78c084dba54dbdde12af4599c2eb4883e6a0890d
Formal baseline: 7a1bcf2c4f1ae69d33f2d717e8b8177aa953af03
Staging branch:  agent/mysql-8-4-p3-me2-diagnostics-query-staging
```

The formal baseline already contains the accepted D1-D8 migration persistence chain, P3-ME1
operations visibility and P3-H9 AI governance-history query. Its natural synchronization Run
`32117199585 / #1540` completed successfully after current `main` removed the accidental H9
staging-only files.

## Remaining migration protocol inventory

The live application-port, JDBC implementation, factory and executable-wiring inventory was rebuilt
from the exact formal baseline before this gate was selected.

| Authority | PostgreSQL implementation | MySQL implementation / factory | Live status |
| --- | --- | --- | --- |
| D1 plan, intent and consumed-plan authority | retained JDBC migration plan/intent stores | accepted MySQL authority fixtures and vendor-safe downstream stores | accepted predecessor |
| D2 attempt provisioning | `JdbcApprovalMigrationAttemptProvisioningStore` | `JdbcMySqlApprovalMigrationAttemptProvisioningStore` through factory | accepted |
| D3 attempt claim and lease fence | `JdbcApprovalMigrationAttemptClaimStore` | `JdbcMySqlApprovalMigrationAttemptClaimStore` through factory | accepted |
| D4 engine execution and exact verification | PostgreSQL engine/exact-verification stores | MySQL engine and exact-verification stores through trusted factories | accepted through H5 |
| D5 runtime-binding CAS | PostgreSQL serialized runtime-binding CAS store | `JdbcMySqlApprovalMigrationRuntimeBindingCasStore` through factory | accepted |
| D6 reconciliation execution | `JdbcApprovalMigrationReconciliationExecutionStore` | `JdbcMySqlApprovalMigrationReconciliationExecutionStore` through factory | accepted |
| D7 bounded orchestration | `JdbcApprovalMigrationOrchestrationStore` | canonical MySQL orchestration store through factory | accepted |
| D8 plan aggregation | PostgreSQL serialized plan aggregation store | canonical MySQL plan aggregation store through factory and nine guards | accepted |
| M5-E1 operations visibility | `JdbcApprovalMigrationOperationsQuery` | `JdbcMySqlApprovalMigrationOperationsQuery` through factory | accepted |
| M5-E2 diagnostics | `JdbcApprovalMigrationDiagnosticsQuery` | no MySQL peer, no factory, direct PostgreSQL construction | **first remaining gap** |

The once-suspected reconciliation store is not the next gap: the live source already contains a
trusted D6 factory and a real MySQL implementation. M5-E2 is the first direct downstream read
authority that is PostgreSQL-only.

## Selected bounded gate

```text
NEXT_GATE_SELECTED=P3-ME2
SCOPE=M5-E2 Migration Diagnostics Query Semantic Equivalence
POSTGRESQL_AUTHORITY=ApprovalMigrationDiagnosticsQuery + JdbcApprovalMigrationDiagnosticsQuery
MYSQL_GAP=missing MySQL peer, factory and executable wiring
CI_BUDGET=target 1 full Run, maximum 2 full Runs
```

P3-ME2 changes only the persistence implementation selected for the existing application port:

```text
ApprovalMigrationDiagnosticsQuery
  findPlanDiagnostics(tenantId, planId)
  findInstances(InstanceCriteria)
  findInstance(tenantId, planId, approvalInstanceId)
```

The port already owns bounded page/page-size validation, a maximum 31-day evidence range, three
server-owned sort modes and a maximum 64-event instance timeline. ME2 does not widen that authority.

## Required implementation

ME2 must provide exactly:

```text
JdbcApprovalMigrationDiagnosticsQueryFactory
JdbcMySqlApprovalMigrationDiagnosticsQuery
ApprovalMigrationOperationsConfiguration -> trusted diagnostics factory
```

Database selection must come only from trusted JDBC metadata through
`ApprovalDatabaseVendorResolver`:

```text
PostgreSQL 16 -> existing JdbcApprovalMigrationDiagnosticsQuery
MySQL 8.4     -> JdbcMySqlApprovalMigrationDiagnosticsQuery
```

Browser, Mobile, HTTP, headers, tenant data, Connector, Event, AI and business payloads cannot select
the database implementation.

## Semantic-equivalence boundary

For the same immutable D1-D8 evidence, PostgreSQL 16 and MySQL 8.4 must return equivalent:

1. exact case-sensitive tenant and plan isolation;
2. plan, intent, latest aggregate, canary, latest orchestration run/event, latest kill-switch
   observation and completion diagnostics;
3. exact selected/provisioned/pending/claimed/engine/verifying/reconciling/unknown/manual-review/
   conflict/stale/terminal/success/unresolved counts;
4. exact ambiguous-unknown count from immutable engine outcomes;
5. latest attempt by attempt number and stable attempt identity;
6. latest reconciliation and runtime-binding evidence by revision/sequence and stable identity;
7. exact failure-class and reconciliation-state classification;
8. exact hash precedence for completion, binding, conflict, reconciliation, observation,
   verification, engine outcome, engine request and plan selection evidence;
9. exact bounded filters for instance, attempt status, failure class, reconciliation state and
   inclusive evidence-time boundaries;
10. deterministic `SEQUENCE_ASC`, `LATEST_EVIDENCE_ASC` and `LATEST_EVIDENCE_DESC` ordering with
    stable sequence and instance tie-breakers;
11. exact limit/offset page metadata and missing-plan failure;
12. canonical UTC microsecond instants and vendor-safe UUID binding/readback;
13. timeline construction over immutable evidence only;
14. read-only Repeatable Read behavior with no repair, lock, retry, dispatch or mutation authority.

The PostgreSQL query and tests remain unchanged except for the executable composition root no longer
constructing the PostgreSQL implementation directly.

## MySQL SQL boundary

PostgreSQL-only `LATERAL`, `jsonb_array_length`, native UUID materialization and
`OffsetDateTime` reads must be represented by MySQL 8.4 equivalents:

- `ROW_NUMBER() OVER (...)` derived tables for latest rows;
- `JSON_LENGTH(...)` for orchestration batch cardinality;
- `JdbcDatabaseValueAdapter` for UUID and UTC `datetime(6)` values;
- the existing named-parameter, tenant and bounded pagination contract.

Forbidden in the MySQL query:

```text
FILTER (WHERE ...)
JOIN LATERAL
::uuid / ::text / ::jsonb
INSERT / UPDATE / DELETE
INSERT IGNORE
REPLACE
ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS
GET_LOCK / LOCK TABLES
SET GLOBAL / SET PERSIST
Flowable ACT_* access
automatic retry
```

## Test-first acceptance matrix

| Test | Required proof |
| --- | --- |
| `JdbcApprovalMigrationDiagnosticsQueryFactoryTest` | trusted PostgreSQL/MySQL metadata selection and unsupported-vendor rejection |
| `JdbcApprovalMigrationDiagnosticsQueryMySqlContractTest` | exact MySQL latest-row SQL, JSON length, deterministic sorting, bounded pagination, read-only RR and forbidden-token boundary |
| `JdbcApprovalMigrationDiagnosticsQueryMySqlIntegrationTest` | real MySQL D1-D8 predecessor chain, tenant isolation, plan summary, filtered page, instance timeline, latest attempt/outcome, terminal evidence, UTC time and no mutation |
| retained `JdbcApprovalMigrationDiagnosticsQueryIntegrationTest` | PostgreSQL non-regression |
| updated ME1 composition boundary | ME1 remains accepted while M5-E2 advances through its own factory |

All target suites must be selected exactly once by the permanent persistence shards, emit Surefire
reports and execute with `skipped = 0` before acceptance.

## Staging and CI discipline

Development remains on this non-PR branch until:

```text
CODE_FROZEN
SCOPE_FROZEN
STATIC_REVIEW_PASS
LOCAL_AVAILABLE_VALIDATION_PASS
NO_KNOWN_ACTIONABLE_DEFECT
```

Only then may an ordinary two-parent Merge Commit advance the formal branch and naturally trigger
PR #92. Same-Head reruns, workflow dispatch, empty trigger commits, force pushes, rebases, squashes
and direct `main` pushes are prohibited.

```text
MYSQL_P3_ME2_TEST_FIRST_STAGING
MYSQL_P3_ME1_REMAINS_ACCEPTED
MYSQL_P3_H9_REMAINS_ACCEPTED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```
