# MySQL 8.4 Production Compatibility

Status: `R0_STARTED / MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED`

Decision date: `2026-08-07`

Tracking:

- Issue: `#91`;
- parent milestone: `#62`;
- overall acceptance blocked: `#82`;
- branch: `agent/mysql-8-4-production-compatibility`;
- exact source `main`: `0cf6572770953a46fe5b16d15ecdff78cf607855`.

## 1. Decision

The original product commitment is restored:

```text
PostgreSQL 16 and MySQL 8.4 are both first-class production database targets.
```

PostgreSQL remains the current accepted production reference. MySQL 8.4 is not supported yet and
must not be represented as supported until every gate in this document is complete.

This workstream is not a documentation-only reconciliation and is not satisfied by:

- adding a MySQL JDBC driver;
- starting Spring Boot against an empty MySQL schema;
- executing the RuoYi menu example SQL;
- running only a small smoke test;
- translating DDL without proving concurrent runtime semantics;
- excluding PostgreSQL-specific tests from the MySQL gate;
- accepting different product decisions or evidence hashes without an explicit versioned contract.

## 2. Exact current truth

At the source Head, the Approval Platform is PostgreSQL-specific in implementation and permanent
validation.

### 2.1 Dependencies and executable configuration

The current persistence module and executable server depend on:

- the PostgreSQL JDBC driver;
- Flyway PostgreSQL support;
- PostgreSQL Testcontainers support.

The default executable configuration uses a PostgreSQL JDBC URL. There is no accepted MySQL
production profile, no startup vendor mismatch guard and no MySQL persistence test gate.

### 2.2 Migration history

The current accepted platform migration lineage reaches governed version `V50` and was developed
and validated against PostgreSQL. Existing migrations are immutable evidence. They must not be
edited, renamed, reformatted or conditionally changed after application.

The MySQL path therefore requires a separately selected vendor migration location that expresses
the same logical version and platform invariants without changing the existing PostgreSQL
checksums.

### 2.3 SQL and storage coupling

Known PostgreSQL-specific categories include:

- `jsonb` columns and `cast(... as jsonb)`;
- `timestamptz` and PostgreSQL/pgjdbc microsecond rounding behavior;
- `bytea` attachment content;
- PostgreSQL UUID binding and readback;
- `ON CONFLICT DO NOTHING` and `ON CONFLICT DO UPDATE`;
- PostgreSQL advisory locks;
- row-lock and `SKIP LOCKED` claim behavior;
- partial and predicate indexes;
- PostgreSQL query-plan inspection, including JSON-formatted `EXPLAIN`;
- PostgreSQL casts, intervals, functions and date/time expressions;
- transactional-DDL assumptions and deferred constraint validation;
- PostgreSQL-specific test fixtures, containers and failure classifications.

The exact inventory must be generated from the current branch before implementation scope is
frozen. This list is a starting classification, not a claim of completeness.

### 2.4 Semantics coupled to persistence

Database behavior participates in product correctness for:

- tenant isolation and non-leaking lookup;
- idempotency registration, replay and payload conflict;
- optimistic revision CAS;
- task claims and duplicate transition prevention;
- Outbox/Inbox deduplication, leases and fencing;
- append-only audit evidence and hash chains;
- notification and SLA retry/dead-letter state;
- release lifecycle, activation and runtime binding;
- governed process-instance migration evidence;
- AI assistance evidence;
- controlled-automation lineage and replay;
- timestamp canonicalization used before hashing and equality comparison.

A MySQL implementation is incomplete if it only reproduces tables but does not prove these
semantics.

## 3. Compatibility contract

### 3.1 Supported database identities

The only accepted production database identities for this milestone are:

```text
POSTGRESQL_16
MYSQL_8_4
```

The identity must be derived by trusted server startup code from JDBC metadata and approved
configuration. It must never be accepted from an HTTP request, browser, Mobile client, connector,
Provider output or untrusted header.

Unsupported products, unsupported major versions and configuration/vendor mismatches must fail
startup with a bounded, non-secret diagnostic.

### 3.2 MySQL production baseline

MySQL acceptance requires:

- MySQL `8.4.x`;
- InnoDB for every platform and Flowable table;
- `utf8mb4` with one explicitly selected and documented collation policy;
- strict SQL mode;
- server-owned DDL authority for governed triggers: when binary logging is enabled, `log_bin_trust_function_creators=ON`, and the migration identity has `TRIGGER` on the platform schema; the application must not change global variables or disable binary logging; MySQL 8.4 deprecates this variable, so future-major compatibility requires a new authority contract;
- UTC application and connection/session time semantics;
- `datetime(6)` or an explicitly proven equivalent for platform instants;
- binary-safe storage for canonical hashes and attachment content;
- no case-folding ambiguity for identifiers, idempotency keys, hashes or external IDs.

### 3.3 Product equivalence

For identical trusted commands and canonical inputs, PostgreSQL and MySQL must produce equivalent:

- application dispositions and error codes;
- state transitions;
- authorization outcomes;
- tenant visibility;
- immutable evidence and canonical hashes;
- idempotency replay/conflict classification;
- bounded pagination and server-owned ordering;
- recovery and unknown-result decisions.

Physical plans, lock implementation and vendor diagnostics may differ. Product authority and
safety decisions may not silently differ.

## 4. Architecture decision

### 4.1 Keep dialect code in infrastructure

Database-specific behavior belongs in persistence infrastructure. Domain and application modules
must remain database independent.

The preferred structure is:

```text
application/domain ports
        |
        v
vendor-neutral JDBC coordinator contracts
        |
        +-- PostgreSQL statement/migration implementation
        |
        +-- MySQL statement/migration implementation
```

Scattered vendor checks inside use-case services are prohibited. A bounded vendor strategy,
statement catalog, repository implementation pair or equivalent reviewed pattern must be used.

### 4.2 Preserve PostgreSQL implementation and evidence

The accepted PostgreSQL implementation must remain continuously green while MySQL is added.

No compatibility change may:

- weaken a PostgreSQL constraint;
- replace an exact PostgreSQL concurrency proof with an in-memory mock;
- edit an applied migration;
- lower timestamp precision without a versioned canonicalization decision;
- remove a query-plan gate merely because MySQL uses a different planner;
- change evidence hashes as an accidental side effect of JDBC conversion.

### 4.3 Vendor migration locations

The target Flyway structure must select one vendor history at startup, for example:

```text
db/migration/postgresql
db/migration/mysql
```

The final path may differ after R0, but it must satisfy all of the following:

1. an existing PostgreSQL installation sees the same applied migration identities and checksums;
2. a clean PostgreSQL installation reaches the accepted schema without duplicate execution;
3. a clean MySQL installation receives one unambiguous migration for every required logical
   version;
4. a MySQL historical-upgrade fixture can advance through documented checkpoints;
5. startup cannot accidentally load both vendor histories;
6. Java migrations are vendor-safe and cannot execute PostgreSQL SQL against MySQL;
7. the unique highest governed version remains explicit for each vendor.

A migration relocation must be proven not to change PostgreSQL Flyway checksums or discovery
semantics before it is accepted.

## 5. Mandatory work stages

## R0 — Inventory and design freeze

Deliver:

- exact dependency inventory;
- exact migration inventory through `V50`;
- exact production JDBC source inventory;
- exact PostgreSQL syntax/function/type inventory;
- exact test-class and CI selection inventory;
- Flowable schema/configuration inventory;
- compatibility threat model;
- accepted migration-location strategy;
- accepted vendor-strategy architecture.

R0 acceptance requires evidence generated from the exact branch Head. Approximate grep counts or
historical PR descriptions are not sufficient.

## P1 — Vendor identity, dependencies and startup profiles

Deliver:

- MySQL Connector/J;
- Flyway MySQL support;
- Testcontainers MySQL support;
- trusted database-vendor resolver;
- fail-closed supported-version policy;
- PostgreSQL and MySQL production configuration examples;
- explicit connection/session initialization for MySQL strict mode, UTC and character settings;
- startup tests for accepted, unsupported and mismatched vendors.

P1 must not claim persistence compatibility.

## P2 — MySQL migration lineage

Deliver a MySQL schema equivalent to the current logical platform schema.

Every table, key, constraint and index must be classified as:

- exact equivalent;
- vendor-specific equivalent with the same product invariant;
- implemented by transactional protocol rather than DDL;
- intentionally unavailable and therefore a release blocker.

Required migration tests:

- empty PostgreSQL to current;
- historical PostgreSQL checkpoints to current;
- empty MySQL to current;
- historical MySQL checkpoints to current;
- repeated validation with unchanged checksums;
- unsupported mixed-location and wrong-vendor startup rejection.

## P3 — JDBC implementation compatibility

Each production store must either be demonstrably portable or have bounded PostgreSQL/MySQL
implementations.

The conversion order must prioritize shared high-risk primitives:

1. database vendor and timestamp canonicalization;
2. command idempotency;
3. audit and immutable hash evidence;
4. tenant-scoped projections and task claims;
5. Inbox/Outbox and worker lease protocols;
6. form/release/runtime-binding persistence;
7. SLA/notification/operational failure persistence;
8. process-instance migration evidence and reconciliation;
9. AI assistance evidence;
10. controlled-automation lineage.

No broad repository conversion is accepted without focused regression evidence for each slice.

## P4 — Semantic and concurrency matrix

The dual-database matrix must include deterministic tests for:

- duplicate command registration;
- same-key/same-payload replay;
- same-key/different-payload conflict;
- concurrent task completion;
- stale revision/CAS rejection;
- row-lock claim exclusion;
- lease expiry and stale-owner fencing;
- deadlock retry classification where retry is safe;
- crash before and after external side effects;
- durable `UNKNOWN` and reconciliation;
- exact timestamp boundary behavior;
- append-only event ordering and hash-chain verification;
- cross-tenant enumeration and spoofing resistance.

Tests must use real PostgreSQL and real MySQL containers. H2 or mocks cannot satisfy this gate.

## P5 — Flowable and executable server

For each vendor:

- initialize the platform schema through the approved Flyway path;
- initialize/upgrade the Flowable schema through an approved production process;
- start the executable application with `flowable.database-schema-update=false`;
- deploy representative deterministic BPMN/DMN artifacts;
- start low-value and high-value flows;
- execute serial and parallel approvals;
- exercise timers/jobs required by the accepted product scope;
- prove platform code does not query or update Flowable internal tables;
- verify platform/Flowable backup consistency requirements.

## P6 — Permanent workflow

The existing permanent workflow must be extended rather than duplicated.

The final backend evidence must distinguish:

```text
Maven core
PostgreSQL persistence shards
MySQL persistence shards
Dual-database completeness verification
```

The verifier must fail for:

- a discovered test omitted from both required vendor selections;
- an unexpected vendor-specific exclusion;
- a duplicate test selection;
- any failure, error or unapproved skip;
- missing Surefire evidence;
- a green aggregate when a required vendor job failed.

Web, Mobile and Hygiene gates remain required.

## P7 — Production operations

Deliver vendor-specific runbooks covering:

- installation and required server variables;
- database users and privileges;
- connection pool/session initialization;
- isolation and deadlock handling;
- capacity and index health;
- backup and point-in-time recovery;
- consistent platform plus Flowable restore;
- migration failure and evidence-preserving rollback;
- incident diagnosis without manual business-state edits;
- unsupported downgrade behavior.

## G1 — Formal acceptance

Formal acceptance must bind exact Heads and Runs to:

- complete dual-database migration evidence;
- complete dual-database persistence test evidence;
- executable server and Flowable evidence;
- fault, security and concurrency evidence;
- performance/query-plan evidence;
- backup, restore, upgrade and rollback rehearsals;
- independently verified artifacts and SHA-256 values;
- all known limitations.

## G2 — Merge and post-main closure

Only a fresh, unchanged, mergeable Head with every gate green may be marked Ready.

Final integration rules:

- Merge Commit only;
- no squash;
- no rebase;
- no auto-merge;
- no force merge;
- no direct `main` push.

Issue `#91` remains open until the resulting natural `push -> main` run proves both databases and
all artifacts are independently verified. Issues `#82` and `#62` remain open until this workstream
and their own acceptance gates are complete.

## 6. Security and correctness threats

R0 and later reviews must explicitly cover:

- MySQL collation making tenant IDs, hashes or idempotency keys case-insensitive;
- silent timestamp timezone conversion or precision loss;
- MySQL affected-row semantics changing CAS decisions;
- `ON DUPLICATE KEY UPDATE` causing mutation where PostgreSQL performed no-op admission;
- gap locks or isolation differences widening contention or changing claims;
- DDL auto-commit causing partial migration state;
- JSON normalization changing canonical evidence;
- unsigned/integer-width differences changing revisions or counters;
- truncated identifiers or index prefixes weakening uniqueness;
- BLOB/text coercion changing hashes;
- deadlock retries duplicating an external command;
- vendor selection being influenced by an untrusted client;
- a MySQL test profile silently skipping PostgreSQL-specific classes instead of supplying an
  equivalent proof.

## 7. Initial blocker register

| ID | Blocker | Current state | Required resolution |
| --- | --- | --- | --- |
| DB01 | No MySQL runtime driver | Open | P1 dependency and startup validation |
| DB02 | No Flyway MySQL support | Open | P1/P2 vendor history |
| DB03 | PostgreSQL-only migrations through V50 | Open | P2 immutable MySQL lineage |
| DB04 | `jsonb` and PostgreSQL casts | Open | P2/P3 JSON strategy |
| DB05 | `timestamptz` and PostgreSQL instant rounding | Open | versioned cross-vendor instant contract |
| DB06 | `bytea` attachment storage | Open | BLOB adapter and round-trip proof |
| DB07 | PostgreSQL conflict/upsert syntax | Open | exact MySQL admission/CAS protocols |
| DB08 | PostgreSQL advisory locks | Open | bounded MySQL serialization strategy |
| DB09 | PostgreSQL index/query-plan gates | Open | equivalent MySQL index/plan gates |
| DB10 | PostgreSQL-only Testcontainers suite | Open | full dual-vendor matrix |
| DB11 | PostgreSQL-only permanent workflow | Open | P6 workflow extension |
| DB12 | No MySQL Flowable/application acceptance | Open | P5 real execution |
| DB13 | No MySQL backup/restore/rollback runbook | Open | P7 rehearsals |
| DB14 | Living docs describe MySQL as future-only | Open | update only as verified gates advance |

## 8. Current authorization boundary

Authorized now:

- R0 inventory and architecture decisions;
- append-only tests that expose PostgreSQL coupling;
- bounded P1 dependency/configuration work after R0 evidence;
- Draft PR development on the independent branch.

Not authorized now:

- claiming MySQL production support;
- marking Issue `#91` complete;
- closing Issue `#82` or parent Issue `#62`;
- weakening PostgreSQL behavior;
- editing applied migrations;
- merging before formal dual-database acceptance.

`DUAL_DATABASE_COMMITMENT_RESTORED`

`MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED`
