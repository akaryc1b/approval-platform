# MySQL 8.4 P3-H9 AI Governance History Query Contract

Status: `REBASELINED_IMPLEMENTATION_CANDIDATE / NOT_ACCEPTED`

## Tracking and live rebaseline

```text
Repository:      akaryc1b/approval-platform
Issue:           #91
Parent issue:    #62
Overall gate:    #82
Draft PR:        #92
Formal branch:   agent/mysql-8-4-production-compatibility
Formal source:   0f116535b6def397978d0855c2c9593cb2ce6801
Current main:    1f59b40955c1e476e3a187971327f943ea3d06f9
Staging branch:  agent/mysql-8-4-p3-h9-governance-history-staging
Candidate source:66a2b2ea0372d48cca51131da69e9feedbce4a00
```

The current default branch contains the three H9 test-first commits whose messages describe a
non-PR staging boundary. This record does not rewrite, reset, revert or hide that history. The H9
staging branch absorbs the exact current `main` through an ordinary merge and resolves the duplicated
static contract against the actual application port.

The formal source retains the complete accepted D8 and P3-ME1 implementation history. The earlier
P3-ME1 same-Head rerun remains a visible governance nonconformance and is not reinterpreted or used as
H9 proof.

## Source-driven gate selection

The bounded PostgreSQL-only production authority selected for H9 is:

```text
ApprovalAssistanceGovernanceHistoryQuery
JdbcApprovalAssistanceGovernanceHistoryQuery
```

The exact application authority is:

```java
HistorySummary summarize(HistoryWindow window);
```

It is one bounded aggregate summary. It is not a list, page, offset, limit or sort protocol. H9 must
not manufacture ordering or pagination requirements that the application port does not expose.

## Static-contract correction trail

The H9 SQL contract committed to current `main` required `ORDER BY` and `LIMIT`. Those assertions do
not correspond to the source-owned application authority and would force unrelated SQL into a scalar
aggregate query.

Classification:

```text
STATIC_CONTRACT_FALSE_POSITIVE
PORT_SCOPE_DRIFT
```

The corrected permanent contract instead proves:

- the port exposes exactly one bounded `summarize(HistoryWindow)` operation;
- the MySQL query is read-only and Repeatable Read;
- tenant and half-open time predicates are exact;
- PostgreSQL `FILTER` aggregates are represented by MySQL `SUM(CASE ...)` aggregates;
- UTC `datetime(6)` binding and readback use the accepted database value adapter;
- no list, pagination, sort, command or mutation authority is added.

No PostgreSQL test, behavior or SQL is weakened by this correction.

## Required production implementation

H9 provides exactly:

```text
JdbcApprovalAssistanceGovernanceHistoryQueryFactory
JdbcMySqlApprovalAssistanceGovernanceHistoryQuery
ApprovalAssistanceProductionConfiguration -> trusted factory
```

Database selection is derived only from trusted JDBC metadata through
`ApprovalDatabaseVendorResolver`:

```text
PostgreSQL 16 -> existing JdbcApprovalAssistanceGovernanceHistoryQuery
MySQL 8.4     -> JdbcMySqlApprovalAssistanceGovernanceHistoryQuery
```

Browser, Mobile, HTTP, Connector, Event, Template, Provider, tenant data, headers and business
payloads cannot select the database implementation.

## Product-equivalent summary protocol

For the same immutable governance evidence and exact `HistoryWindow`, PostgreSQL 16 and MySQL 8.4
must return equivalent:

1. exact tenant-isolated half-open `[fromInclusive,toExclusive)` evidence selection;
2. active and tombstoned evidence counts;
3. Provider invocation and attempt counts;
4. advisory-result counts;
5. unsafe retry and post-invocation fallback counts;
6. retention-due count evaluated at `observedAt` and limited to active evidence;
7. earliest and latest recorded instants;
8. complete `AiOutcomeClassification` counts in exact enum order;
9. complete use-case counts in exact enum order;
10. Provider/advisory totals and distinct version-bundle counts per use case;
11. exact `EMPTY`, `SINGLE_VERSION_BUNDLE` and `MULTIPLE_VERSION_BUNDLES` classification;
12. empty-history behavior with no manufactured timestamps;
13. domain coherence checks and fail-closed malformed or contradictory evidence handling;
14. read-only behavior with no repair, retry, tombstone, scheduler or command authority.

`HistoryWindow` remains bounded by the existing application contract, including its positive window,
31-day maximum window, 3650-day maximum lookback and no-future-window rules.

## MySQL SQL and transaction boundary

The MySQL peer uses:

```text
TransactionTemplate.setReadOnly(true)
TransactionDefinition.ISOLATION_REPEATABLE_READ
JdbcDatabaseValueAdapter
SUM(CASE WHEN ... THEN 1 ELSE 0 END)
COUNT(DISTINCT ...)
exact tenant predicate
exact half-open time predicates
```

Forbidden:

```text
FILTER (WHERE ...)
INSERT
UPDATE
DELETE
REPLACE
INSERT IGNORE
ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS
GET_LOCK
LOCK TABLES
SET GLOBAL
SET PERSIST
SQL_CALC_FOUND_ROWS
direct Flowable ACT_* access
Provider invocation
approval command authority
repair writes
automatic retry of an unknown result
```

Dialect differences remain inside persistence infrastructure. Domain and application services do not
accumulate `if mysql` branches.

## Time and canonical evidence boundary

MySQL `datetime(6)` parameters and results use the accepted vendor-aware value adapter. The query
must preserve UTC microsecond instants without local-time reinterpretation or a second rounding pass.
PostgreSQL `timestamptz` behavior remains unchanged.

## Test-first matrix

| Suite | Required proof |
| --- | --- |
| `JdbcApprovalAssistanceGovernanceHistoryQueryFactoryTest` | trusted PostgreSQL/MySQL metadata selection |
| `JdbcApprovalAssistanceGovernanceHistoryQueryMySqlContractTest` | actual summary-only port, read-only RR, exact MySQL conditional aggregation and forbidden authority |
| `JdbcApprovalAssistanceGovernanceHistoryQueryMySqlIntegrationTest` | real MySQL 8.4 empty summary, exact tenant/window/state/retention/outcome/use-case/version aggregation and no mutation |
| `JdbcApprovalAssistanceGovernanceHistoryQueryMySqlFaultIntegrationTest` | unavailable evidence authority fails without partial summary or repair write |
| retained PostgreSQL integration and fault tests | PostgreSQL non-regression |
| composition-root contract | executable application uses only the trusted factory |

Every target suite must be deterministically selected exactly once, produce a Surefire report and
execute with `skipped = 0`.

## Staging freeze and formal integration

Before formal integration the final staging tree must contain:

- exact current-main ancestry;
- the H9 test-first history;
- the bounded production implementation;
- the corrected summary-only static contract;
- no duplicate or contradictory H9 contract path;
- no PR or automatic workflow attached to the staging branch;
- no known actionable semantic, tenant, time, SQL-portability or scope defect.

Formal integration is allowed only as an ordinary two-parent Merge Commit:

```text
parent 1 = exact current formal Head
parent 2 = exact frozen H9 staging Head
```

The formal ref must move without force. No squash, rebase, direct `main` push or workflow dispatch is
allowed.

## CI budget and permanent evidence

H9 planned full CI:

```text
Target: 1 natural pull_request synchronize Run
Maximum: 2 natural Runs only when Run A exposes a classified correctable defect
```

Forbidden:

```text
same-Head rerun
workflow_dispatch
empty trigger commit
third full Run
```

A successful Run must prove all nine physical Jobs, exact target test execution, zero failures,
errors and skips, exact persistence selection coverage, and independently verified Maven, Hygiene,
Vben and Mobile ZIPs with byte count, SHA-256 and archive integrity.

## Acceptance and authorization boundary

H9 is not accepted by this record. It becomes implementation-accepted only after the exact formal
implementation Head completes its authorized natural Run and all permanent evidence is independently
verified.

A later acceptance document changes the Head and must follow the project documented-head rule. A
previous Head's Run cannot be borrowed as proof for a new document Head.

Even after H9 succeeds:

```text
PR #92 remains Open / Draft / Unmerged
Issue #91 remains Open
Issue #82 remains Open
Issue #62 remains Open
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

Not authorized:

- Ready transition;
- PR merge;
- Issue closure;
- next compatibility gate before H9 disposition;
- deployment or Production Promotion;
- MySQL production-support or dual-database production-acceptance claim.

```text
MYSQL_P3_H9_REBASELINED_IMPLEMENTATION_CANDIDATE
H9_STATIC_CONTRACT_CORRECTED_TO_ACTUAL_PORT
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
AI_IS_NOT_AN_OPERATOR
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```
