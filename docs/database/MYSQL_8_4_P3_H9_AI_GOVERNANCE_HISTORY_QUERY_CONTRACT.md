# MySQL 8.4 P3-H9 AI Governance History Query Contract

Status: `STAGING / NOT_ACCEPTED`

## Tracking and exact baseline

```text
Repository:     akaryc1b/approval-platform
Issue:          #91
Parent issue:   #62
Overall gate:   #82
Draft PR:       #92
Formal branch:  agent/mysql-8-4-production-compatibility
Source Head:    8ddee0bfdb5dfe97530e6d94fc876b2fc4bd0cb5
Main baseline:  4af817a46dcbefee7e84b78e022e87f24e0ffcda
Staging branch: agent/mysql-8-4-p3-h9-governance-history-staging
```

The source Head is the exact green D8 Plan Aggregation implementation Head proven by natural Run
`32091830387 / #1526`. The D8 acceptance body remains staged separately because its two-run budget
was exhausted. H9 must not rewrite, squash, hide or reinterpret that history.

## Source-driven gate selection

The next bounded production authority with an existing PostgreSQL implementation and no accepted
MySQL peer is:

```text
ApprovalAssistanceGovernanceHistoryQuery
JdbcApprovalAssistanceGovernanceHistoryQuery
```

The current query is constructed directly by the executable application composition root and uses
PostgreSQL aggregate `FILTER` syntax. H9 converts only this read-only governance-history boundary.
It does not widen AI authority, invoke a Provider, create or tombstone durable evidence, execute an
approval command, schedule autonomous work, or claim MySQL production support.

## Required implementation

H9 must provide:

```text
JdbcApprovalAssistanceGovernanceHistoryQueryFactory
JdbcMySqlApprovalAssistanceGovernanceHistoryQuery
ApprovalAssistanceProductionConfiguration -> trusted factory
real MySQL 8.4 query-equivalence evidence
```

Database selection must be derived only from trusted JDBC metadata through the established
`ApprovalDatabaseVendorResolver`. Browser, Mobile, HTTP, Connector, Event, Template, Provider,
business payload and request headers must not select the database dialect.

PostgreSQL 16 must continue to use the existing query implementation without behavior or SQL
regression.

## Product-equivalent read protocol

For the same immutable governance evidence, PostgreSQL 16 and MySQL 8.4 must return equivalent:

1. tenant-scoped history rows with no cross-tenant leakage;
2. deterministic newest-first ordering with a stable server-owned tie-breaker;
3. exact bounded pagination and limit validation;
4. exact status/event/category counts and grouped summaries;
5. exact retention and tombstone visibility semantics;
6. exact request, evidence, event and state lineage identities exposed by the existing application
   port;
7. canonical UTC microsecond instants;
8. empty-history behavior;
9. fail-closed behavior for malformed, contradictory or incomplete stored evidence;
10. read-only behavior with no mutation, lock escalation, retry, scheduler or approval-command
    authority.

A successful query that silently changes count semantics, null handling, ordering, timestamp
precision or tenant identity is not acceptable.

## MySQL SQL boundary

PostgreSQL conditional aggregates such as:

```sql
count(*) filter (where predicate)
```

must be represented in MySQL with explicit portable conditional aggregation, for example:

```sql
sum(case when predicate then 1 else 0 end)
```

The final implementation must preserve exact null and zero-count semantics. It must not introduce:

```text
INSERT IGNORE
REPLACE
ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS
user variables
session-global mutation
SQL_CALC_FOUND_ROWS
implicit case-insensitive tenant comparison
unbounded result scans
client-selected sort expressions
```

Any dialect difference must remain inside persistence infrastructure. Domain and application layers
must not accumulate `if mysql` branches.

## Ordering and pagination authority

Every externally visible list must use a deterministic server-owned order. A timestamp alone is not
sufficient when multiple rows share the same MySQL `datetime(6)` value. The query must retain the
existing stable identity tie-breaker and must use bounded `LIMIT`/offset or the exact existing
application-port pagination contract.

Negative offsets, non-positive limits, limits above the established maximum, malformed tenant
identity and unsupported query shape must fail before SQL execution.

## Time and canonical evidence boundary

MySQL `datetime(6)` values must be read as exact UTC instants using the established JDBC value
adapter/canonicalizer. The query must not round a value a second time, reinterpret local time, or
change evidence/hash identities. PostgreSQL `timestamptz` behavior remains unchanged.

## Security and authority boundaries

H9 is read only:

- no Provider invocation;
- no Prompt or raw model payload exposure;
- no tenant/operator/authority manufacture;
- no approval, reject, transfer, withdraw, terminate or migration command;
- no direct Flowable `ACT_*` access;
- no evidence mutation or tombstone creation;
- no retry worker, scheduler or autonomous deletion;
- no production credential or customer data;
- no deployment or Production Promotion.

Raw Provider input/output, Prompt text, summaries, observations, recommendations, Secrets and binary
content must remain absent from governance-history storage and query responses unless already
explicitly represented by the existing hash-only application port. H9 must not broaden that port.

## Test-first matrix

The staging implementation must begin with failing tests that prove the required boundary:

| Suite | Required proof |
| --- | --- |
| `JdbcApprovalAssistanceGovernanceHistoryQueryFactoryTest` | trusted PostgreSQL/MySQL metadata selection and unsupported-vendor rejection |
| `JdbcApprovalAssistanceGovernanceHistoryQueryMySqlContractTest` | MySQL SQL shape, conditional aggregation, deterministic order, bounded pagination, read-only and forbidden-token contract |
| `JdbcApprovalAssistanceGovernanceHistoryQueryMySqlIntegrationTest` | real MySQL 8.4 tenant isolation, empty history, counts, ordering, pagination, canonical time, tombstone/retention visibility and malformed-evidence rejection |
| retained PostgreSQL governance-history tests | no PostgreSQL regression |
| repository boundary tests | executable composition uses the trusted factory and AI remains advisory/read-only |

All target suites must be deterministically selected exactly once by the permanent persistence
shards, produce Surefire reports, and execute with `skipped = 0` before acceptance.

## Staging and validation discipline

Authorized staging sequence:

1. create tests on this non-PR branch;
2. observe the intended local/test-first failure when executable in the available environment;
3. implement only the bounded H9 query and factory;
4. run all locally available static, compilation and targeted checks;
5. freeze the staging Head;
6. ordinary two-parent merge into the then-current formal branch;
7. allow one natural PR #92 synchronize Run A;
8. correct a real failure only with a new commit and a new natural Head;
9. independently verify all nine Jobs, target test reports and all four permanent Artifacts.

No workflow dispatch, same-Head rerun, empty trigger commit, force push, rebase, squash or direct
`main` push is authorized.

A maximum of two complete formal CI Runs is allowed for this bounded gate unless a later explicit
instruction changes the budget. A documentation-only Run must not be created after that budget is
exhausted.

## Acceptance boundary

H9 can be called implementation-accepted only when:

- the exact formal implementation Head completes a natural successful Run;
- all nine physical Jobs succeed;
- the MySQL and retained PostgreSQL governance-history suites execute with zero failures, errors and
  skips;
- selection coverage is exact with no duplicates or silent omissions;
- Maven, Hygiene, Vben and Mobile Artifacts are independently downloaded, byte-counted, SHA-256
  verified and ZIP-integrity checked;
- the failed/correction trail, if any, remains append-only and visible.

A later acceptance record creates a new documented Head and therefore requires its own explicit
validation authorization. It must not borrow the implementation Head Run.

## Current authorization boundary

Authorized:

- bounded test-first H9 work on this non-PR staging branch;
- source inventory and exact protocol comparison;
- ordinary merge into Draft PR #92 only after staging checks;
- natural PR CI and append-only correction commits.

Not authorized:

- Ready transition or merge of PR #92;
- closing Issue #91, #82 or #62;
- MySQL 8.4 production-support declaration;
- modification of already-applied PostgreSQL migrations;
- weakening PostgreSQL behavior or test coverage;
- next compatibility gate before H9 disposition;
- deployment, traffic mutation or Production Promotion.

```text
MYSQL_P3_H9_AI_GOVERNANCE_HISTORY_QUERY_STAGED
MYSQL_P3_H8_D8_PLAN_AGGREGATION_IMPLEMENTATION_ACCEPTED
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
