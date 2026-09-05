# MySQL 8.4 P3-H8 AI Durable Evidence Contract

Status: `STAGING / NOT_ACCEPTED`

Tracking:

- Issue: `#91`;
- Draft PR: `#92`;
- non-triggering staging branch: `agent/mysql-8-4-p3-h8-ai-evidence-staging`;
- exact source Head: `c7a2ecffab2492a7953d201e78b322ae1afb6d49`;
- accepted predecessor: `P3-H7-GA MySQL D7 Guard Installation Authority`;
- source validation: `31991589566 / #1490 / success`.

## Gate selection

The current source inventory was compared by application port, PostgreSQL/general JDBC authority,
MySQL peer, trusted metadata factory and real MySQL suite. The first remaining production authority
with a PostgreSQL implementation but no MySQL implementation is:

```text
ApprovalAssistanceDurableEvidenceStore
JdbcApprovalAssistanceDurableEvidenceStore
```

The production composition root also constructs the PostgreSQL store directly. H8 therefore
converts exactly this hash-only AI evidence and retention-tombstone boundary. It does not widen AI
authority, invoke a Provider, execute an approval command, introduce raw Prompt or Provider data,
or claim MySQL production support.

## Required implementation

H8 must provide:

```text
JdbcApprovalAssistanceDurableEvidenceStoreFactory
JdbcMySqlApprovalAssistanceDurableEvidenceStore
ApprovalAssistanceProductionConfiguration -> trusted factory
MySqlV50AiEvidenceGuards
real MySQL 8.4 integration evidence
```

Vendor selection is derived only from trusted JDBC metadata. Browser, Mobile, Connector, Event,
Template, Provider and business payloads cannot select the database dialect.

## Product-equivalent protocol

For the same canonical evidence and tombstone command, PostgreSQL 16 and MySQL 8.4 must preserve:

1. tenant-scoped evidence identity, request-evidence identity and content-evidence identity;
2. one strict stored revision and exact replay;
3. conflict for same request/different evidence or same identity/different evidence;
4. ACTIVE revision one followed by at most one TOMBSTONED revision two;
5. retention-expired rejection before `retention_until`;
6. explicit early-deletion reasons only;
7. exact event predecessor and event/state hash linkage;
8. deterministic concurrent store and tombstone single-winner behavior;
9. transaction rollback of evidence, event and state together;
10. immutable evidence and append-only event records;
11. no raw Provider input/output, Prompt, summary, observation, recommendation, Secret, JSON or
    binary payload storage;
12. no retry, scheduler, autonomous deletion, Flowable access or approval-command authority.

## MySQL transaction and schema authority

MySQL uses the existing transaction-bound named-lock manager and acquires the tenant-scoped
identity locks in deterministic lexical order. It does not use `INSERT IGNORE`, `REPLACE`, broad
`ON DUPLICATE KEY UPDATE`, `FOREIGN_KEY_CHECKS`, automatic retry or affected-row ambiguity.

MySQL V50 adds a bounded physical `tombstone_hash` event column and nine governed triggers. The
inserted event is the only authority that materializes or advances the state row. State writes must
match exactly one stored or tombstone event; evidence and event rows reject UPDATE and DELETE.

The server-owned H7 trigger-creation preflight remains unchanged. H8 does not execute `SET GLOBAL`,
`SET PERSIST`, disable binary logging or grant `SUPER`.

## Time and hash boundary

Production approval-assistance evidence is already canonicalized to database microseconds before
the evidence hash is created. The MySQL Store rejects non-canonical evidence times rather than
silently changing a hash. Tombstone request time is canonicalized before tombstone and event hashes
are produced, and the canonical value is returned and persisted.

## Test-first matrix

| Suite | Required proof |
| --- | --- |
| `JdbcApprovalAssistanceDurableEvidenceStoreFactoryTest` | trusted PostgreSQL/MySQL metadata selection |
| `JdbcApprovalAssistanceDurableEvidenceStoreMySqlContractTest` | bounded SQL, lock, factory and wiring contract |
| `MySqlV50H8AiEvidenceSchemaContractTest` | exact V50 schema/trigger/checksum authority |
| `JdbcApprovalAssistanceDurableEvidenceStoreMySqlIntegrationTest` | real MySQL store, replay, conflict, tenant, retention, concurrency, rollback and tamper rejection |
| retained PostgreSQL integration | no PostgreSQL regression |
| `m6-e-p4-durable-evidence-boundary.test.mjs` | cross-module dual-database and permanent security boundary |

## Authorization boundary

Authorized:

- append-only H8 implementation on the non-triggering staging branch;
- ordinary two-parent integration into Draft PR #92 after local gates;
- natural PR CI and permanent Artifact verification;
- correction commits with new Heads when a real defect is observed.

Not authorized:

- Ready or merge of PR #92;
- closing Issue #91, #82 or #62;
- MySQL production-support declaration;
- Provider call, customer credential, deployment, traffic mutation or Production Promotion;
- modification of applied PostgreSQL migrations;
- weakening PostgreSQL tests or AI advisory-only boundaries.

```text
MYSQL_P3_H8_AI_DURABLE_EVIDENCE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
AI_IS_NOT_AN_OPERATOR
```
