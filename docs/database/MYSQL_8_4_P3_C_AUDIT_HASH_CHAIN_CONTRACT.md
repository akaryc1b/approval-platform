# MySQL 8.4 P3-C Audit Hash-Chain Contract

Status: `P3_C_STAGED / REMOTE_VALIDATION_PENDING / MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED`

Decision date: `2026-08-07`

Tracking:

- Issue: `#91`;
- Draft PR: `#92`;
- implementation branch: `agent/mysql-8-4-production-compatibility`;
- non-triggering assembly branch: `agent/mysql-8-4-p3-c-audit-staging`;
- current accepted `main`: `1747b22123fd71cccd8334853ad7060c6645b443`;
- accepted P3-B Head before this slice: `646a996f546cc0d0907bc3ab95c6833b4747e6bd`;
- accepted P3-B natural PR Run: `31173925936` / `#1360`.

## 1. Scope

P3-C converts and proves one existing high-risk evidence primitive only:

```text
ApprovalAuditStore / AuditEventSink
```

The bounded implementation consists of:

- the accepted PostgreSQL `JdbcAuditEventSink` without SQL or hash-contract changes;
- `AuditHashCanonicalizer`, an exact Java replica of the immutable PostgreSQL V21 payload and chain
  hash functions;
- `JdbcMySqlAuditEventSink` for MySQL 8.4 append, query and integrity verification;
- `JdbcAuditEventStoreFactory`, selected only from trusted JDBC metadata;
- executable-server binding through the existing `auditEventSink` bean;
- real PostgreSQL and MySQL integration acceptance.

This slice does not convert unrelated audit-adjacent stores, operational-failure replay, projections,
notifications, release evidence, M5 migration evidence, AI evidence or controlled-automation
lineage.

## 2. Immutable PostgreSQL reference contract

PostgreSQL V21 remains the source of truth. P3-C does not edit V21 or any previously applied
migration. PostgreSQL continues to use:

```text
ap_audit_payload_hash(...)
ap_audit_chain_hash(previous_hash, payload_hash)
```

The payload hash is SHA-256 over the UTF-8 bytes of the exact PostgreSQL `jsonb` text document
containing:

- event ID;
- tenant and operator identities;
- action and aggregate identity;
- schema name and version;
- request and optional trace identity;
- UTC occurred time with exactly six fractional digits;
- normalized string-to-string attributes.

The chain hash remains:

```text
SHA-256(previous_hash + ":" + payload_hash)
```

PostgreSQL append admission, database functions, `jsonb`, `timestamptz`, row locking, integrity CTE,
query behavior and failure codes remain unchanged.

## 3. Cross-database canonicalization

`AuditHashCanonicalizer` reproduces the permanent PostgreSQL V21 representation rather than
introducing a new hash version.

The replica binds:

1. object keys ordered by UTF-8 byte length and then unsigned byte order, matching PostgreSQL
   `jsonb` object ordering;
2. exact PostgreSQL object separators `, ` and `: `;
3. JSON string escaping for quotes, reverse solidus, standard control escapes and remaining control
   characters;
4. non-ASCII Unicode retained as UTF-8 rather than escaped or normalized;
5. a null trace identifier represented as JSON `null`;
6. attributes represented as a nested JSON object with the same recursive key ordering;
7. occurred instants rounded to the nearest microsecond and rendered in UTC with six fractional
   digits;
8. lowercase SHA-256 output.

The PostgreSQL integration test calls the existing V21 database functions for empty, escaped,
Unicode, null-trace, sub-microsecond and second-carry vectors. A Java result that differs by one byte
fails acceptance.

## 4. MySQL append and chain-state protocol

MySQL uses the existing V50 `ap_audit_event` and `ap_audit_chain_state` schema. No new migration is
introduced.

For every append:

1. the tenant chain-state row is admitted by a plain `INSERT`;
2. only a primary-key duplicate on that isolated insert means the chain already exists;
3. the chain-state row is locked with `FOR UPDATE`;
4. the next tenant sequence is derived while holding that lock;
5. occurred time is canonicalized to the accepted PostgreSQL nearest-microsecond boundary;
6. payload and current hashes are calculated by the verified V21 replica;
7. the event is inserted once with native MySQL JSON attributes;
8. chain state advances through an exact previous-sequence and previous-hash CAS;
9. event insert and state advance commit or roll back in one transaction.

The implementation does not use:

```text
INSERT IGNORE
ON DUPLICATE KEY UPDATE
```

A duplicate event ID, malformed evidence, failed insert or failed chain-state CAS rolls back the
whole append. No repair, retry or fail-open fallback is added.

## 5. Query and integrity semantics

MySQL query behavior remains:

- mandatory tenant predicate;
- half-open occurred-time range;
- optional operator, action, aggregate, request and trace filters;
- descending tenant sequence;
- bounded limit and offset;
- exact UUID, UTC instant, attributes and hash readback.

Integrity verification evaluates the full tenant sequence so a bounded time-range check still uses
the correct preceding chain context. It preserves the accepted failure ordering:

```text
SEQUENCE_MISMATCH
PREVIOUS_HASH_MISMATCH
PAYLOAD_HASH_MISMATCH
CURRENT_HASH_MISMATCH
CHAIN_STATE_MISMATCH
```

Verification is detect-only. It never updates an event, rewrites a hash or repairs chain state.

## 6. Executable-server binding

The existing `auditEventSink` bean remains the single platform audit authority. It now delegates to
`JdbcAuditEventStoreFactory`, which reads trusted JDBC metadata and accepts only:

- PostgreSQL 16 -> accepted `JdbcAuditEventSink`;
- MySQL 8.4 -> `JdbcMySqlAuditEventSink`.

Browser, Mobile, request headers, Connector payloads, AI output and application commands cannot
select the audit implementation.

## 7. Permanent acceptance matrix

`AuditHashCanonicalizerPostgresIntegrationTest` retains four real-PostgreSQL scenarios:

1. Java payload hashes equal the immutable V21 function for the complete canonical vector matrix;
2. Java chain hash equals `ap_audit_chain_hash`;
3. text that PostgreSQL cannot represent is rejected before hashing;
4. trusted metadata retains the accepted PostgreSQL implementation.

`JdbcAuditEventSinkMySqlIntegrationTest` adds eight real-MySQL scenarios:

5. tenant-isolated append, exact hash storage, canonical readback and valid verification;
6. concurrent same-tenant appends serialize without sequence gaps;
7. payload tampering is detected without repair;
8. non-string stored attributes are rejected rather than coerced into trusted evidence;
9. chain-state tampering is detected without repair;
10. duplicate event failure rolls back without advancing chain state;
11. queries remain tenant-bound, filtered and paginated;
12. trusted metadata selects the MySQL implementation.

The MySQL container must use:

- MySQL `8.4`;
- InnoDB;
- `utf8mb4_0900_as_cs`;
- UTC server, connection and session semantics;
- `READ COMMITTED`;
- strict SQL mode.

A mock DataSource, H2, disabled Docker environment or deterministic in-memory implementation is not
acceptance evidence.

## 8. Failure semantics

The following remain fail closed:

- unsupported database product or version;
- database metadata failure;
- invalid Unicode that cannot have one exact UTF-8 representation;
- chain-state row missing after admission;
- duplicate event identity;
- event insert count other than one;
- chain-state CAS count other than one;
- malformed stored JSON attributes;
- invalid persisted hash shape;
- any payload, predecessor, current-hash or chain-state mismatch.

P3-C adds no automatic retry and no automatic evidence repair. Deadlock, connection loss, commit
ambiguity and unknown-result behavior require later fault acceptance.

## 9. Explicit non-claims

P3-C does not prove:

- all append-only evidence stores;
- complete audit export or operations equivalence outside this store;
- general JSON canonicalization for other tables;
- task/projection CAS and claims;
- advisory-lock replacement;
- Inbox/Outbox behavior beyond accepted P3-B;
- release, migration, AI or controlled-automation evidence;
- real Flowable or executable-server scenario acceptance on MySQL;
- complete dual-vendor permanent CI selection;
- upgrade fixture, backup, restore, rollback, security, fault or performance acceptance.

PR `#92` must remain Draft. Issues `#91`, `#82` and `#62` must remain Open. No Ready transition,
merge, deployment, Production Promotion or MySQL production-support claim is authorized.

## 10. Acceptance rule

The slice may be recorded as implemented only after one unchanged formal branch Head completes one
natural `pull_request` Workflow with:

- all nine physical Jobs successful;
- all twelve P3-C integration scenarios successful;
- exact PostgreSQL V21 payload and chain hash equality;
- real MySQL concurrent append, tamper detection, rollback and query acceptance;
- exact persistence test selection and report coverage;
- four independently downloaded Artifacts whose local bytes and SHA-256 match GitHub;
- no actionable Review or security finding.

Until then:

```text
MYSQL_P3_C_AUDIT_HASH_CHAIN_VALIDATION_PENDING
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

After those exact gates pass, the bounded conclusion is:

```text
MYSQL_P3_C_AUDIT_HASH_CHAIN_SEMANTICS_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```
