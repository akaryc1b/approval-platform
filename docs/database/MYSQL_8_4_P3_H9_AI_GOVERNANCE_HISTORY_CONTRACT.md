# MySQL 8.4 P3-H9 AI Governance History Contract

Status: `IMPLEMENTATION_CANDIDATE / NOT_ACCEPTED`

Tracking:

- Issue: `#91`;
- Draft PR: `#92`;
- source Head: `2129facfa9d39fa5e1ff24538958212bac1a57d7`;
- source Gate: `MYSQL_P3_H8_AI_DURABLE_EVIDENCE_PROVEN`;
- staging branch: `agent/mysql-8-4-p3-h9-governance-history-staging`.

## Scope

P3-H9 adds MySQL 8.4 semantic equivalence for the existing read-only governance-history
port:

```text
ApprovalAssistanceGovernanceHistoryQuery
JdbcApprovalAssistanceGovernanceHistoryQuery
```

The PostgreSQL implementation remains retained. H9 adds one bounded MySQL implementation
and a trusted JDBC-metadata factory, then routes the production composition root through
that factory.

H9 changes no evidence row, event, state, hash domain, retention transition, Provider
invocation, approval command, database migration, Workflow, Secret, deployment or
production-promotion authority.

## Required product semantics

For an exact trusted `HistoryWindow`, PostgreSQL 16 and MySQL 8.4 must return the same:

- tenant-isolated half-open `[fromInclusive,toExclusive)` evidence set;
- active and tombstoned counts;
- Provider invocation and attempt counts;
- advisory-result counts;
- unsafe retry and post-invocation fallback counts;
- retention-due count evaluated at `observedAt`;
- earliest and latest recorded instants;
- complete closed-enum outcome counts in enum order;
- complete use-case counts, Provider/advisory totals and distinct version-bundle counts;
- `EMPTY`, `SINGLE_VERSION_BUNDLE` and `MULTIPLE_VERSION_BUNDLES` classification.

The query executes inside a read-only Repeatable Read transaction. It never repairs,
tombstones, retries, re-dispatches, mutates evidence or infers authority from client input.

## MySQL SQL boundary

MySQL uses exact `SUM(CASE WHEN ... THEN 1 ELSE 0 END)` aggregates instead of PostgreSQL
`COUNT(*) FILTER (WHERE ...)`. The implementation retains named parameters, exact tenant
and time predicates, closed enum parsing and the domain constructor's coherence checks.

Forbidden in the MySQL query:

```text
FILTER (WHERE ...)
INSERT
UPDATE
DELETE
REPLACE
ON DUPLICATE KEY UPDATE
LOCK TABLES
GET_LOCK
SET TRANSACTION
SET GLOBAL
SET PERSIST
Flowable ACT_* access
```

Transaction isolation and read-only state are framework-owned server configuration, not SQL
selected by a request.

## Test-first matrix

| Test | Required proof |
| --- | --- |
| `JdbcApprovalAssistanceGovernanceHistoryQueryFactoryTest` | trusted PostgreSQL/MySQL metadata selection |
| `JdbcApprovalAssistanceGovernanceHistoryQueryMySqlContractTest` | read-only RR and exact MySQL aggregation without write authority |
| `JdbcApprovalAssistanceGovernanceHistoryQueryMySqlIntegrationTest` | exact tenant/window/state/retention/outcome/use-case/version aggregation on MySQL 8.4 |
| `JdbcApprovalAssistanceGovernanceHistoryQueryMySqlFaultIntegrationTest` | unavailable evidence authority fails without partial summary or repair write |
| retained PostgreSQL integration/fault tests | PostgreSQL non-regression |
| production configuration boundary | factory wiring only |

## Implementation candidate

The staging branch now contains:

```text
JdbcApprovalAssistanceGovernanceHistoryQueryFactory
JdbcMySqlApprovalAssistanceGovernanceHistoryQuery
ApprovalAssistanceProductionConfiguration -> trusted factory
```

The PostgreSQL query and every PostgreSQL migration remain unchanged. The MySQL query uses
the existing vendor-aware JDBC value adapter for UTC `datetime(6)` boundaries and result
materialization.

## Acceptance boundary

H9 may become proven only after:

1. tests are committed before production implementation on the non-triggering staging branch;
2. the complete candidate is fast-forwarded into the formal Draft branch without force;
3. one natural implementation Run succeeds;
4. Maven selection coverage and four permanent Artifact classes are independently verified;
5. one documented Head succeeds and its Artifacts are independently verified again.

PR #92 remains Draft and Issue #91 remains Open after H9. MySQL production support, Ready,
Merge, deployment and production promotion remain prohibited.

```text
MYSQL_P3_H9_IMPLEMENTATION_CANDIDATE
MYSQL_P3_H8_REMAINS_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PRB_15_REMAINS_OPEN
AI_IS_NOT_AN_OPERATOR
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```
