# MySQL 8.4 P3-C Audit Hash-Chain Contract

Status: `P3_C_CORRECTION_STAGED / REMOTE_VALIDATION_PENDING / MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED`

Decision date: `2026-08-07`

Tracking:

- Issue: `#91`;
- Draft PR: `#92`;
- implementation branch: `agent/mysql-8-4-production-compatibility`;
- non-triggering assembly branch: `agent/mysql-8-4-p3-c-audit-staging`;
- current accepted `main`: `1747b22123fd71cccd8334853ad7060c6645b443`;
- accepted P3-B Head before this slice: `646a996f546cc0d0907bc3ab95c6833b4747e6bd`;
- accepted P3-B natural PR Run: `31173925936` / `#1360`;
- first P3-C Head: `38b6f96e84f55e529a5c479f6250f84ea85f182c`;
- retained failed natural PR Run: `31176470167` / `#1361`;
- exact failed Job: Persistence JDBC shard 3, `92859451597`;
- failure classification: `PRODUCT_BUG / MYSQL_DUPLICATE_INSERT_LOCK_UPGRADE_DEADLOCK`;
- failed Maven Artifact: `8993081858`, `958084` bytes, SHA-256
  `f88db97a4f79ad1722517d201a6a96270d3eac81a9c8e01bea32846a460224a1`;
- same-Head rerun count: `0`.

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

### 4.1 Retained first-run concurrency failure

The first P3-C Run retained the original plain-`INSERT` admission design and exposed a real MySQL
lock-order defect. Sixteen simultaneous first appends for one tenant could all reach the duplicate
primary-key path. The failed duplicate statements retained shared record locks, after which multiple
transactions attempted to upgrade to the exclusive `FOR UPDATE` lock. MySQL selected one as a
deadlock victim:

```text
JdbcAuditEventSinkMySqlIntegrationTest
  .serializesConcurrentTenantAppendsWithoutGaps

CannotAcquireLockException
MySQL error 1213 / SQLState 40001
Deadlock found when trying to get lock
```

The failure is a Product Bug, not infrastructure instability. Run `#1361` was not rerun and the
concurrency assertion was not weakened.

### 4.2 Corrected single-transaction lock admission

For every append:

1. payload canonicalization and hashing complete before database mutation;
2. the tenant chain-state row is admitted or exclusively locked by one bounded statement:

   ```sql
   INSERT ...
   ON DUPLICATE KEY UPDATE tenant_id = tenant_id
   ```

3. `ap_audit_chain_state` has only the exact tenant primary key as a uniqueness authority;
4. the duplicate clause performs only primary-key self-assignment and cannot change sequence, hash
   or timestamp state;
5. the chain-state row remains explicitly locked with `FOR UPDATE`;
6. the next tenant sequence is derived while holding the exclusive lock;
7. occurred time is canonicalized to the accepted PostgreSQL nearest-microsecond boundary;
8. payload and current hashes are calculated by the verified V21 replica;
9. the event is inserted once with native MySQL JSON attributes;
10. chain state advances through an exact previous-sequence and previous-hash CAS;
11. admission, event insert and state advance commit or roll back in one transaction.

The implementation still does not use `INSERT IGNORE`, does not update a business or evidence row
through the duplicate clause, and does not use duplicate-key update for event admission. A duplicate
event ID, malformed evidence, failed insert or failed chain-state CAS rolls back the whole append,
including a newly inserted zero-state row. No repair, retry or fail-open fallback is added.

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

`JdbcAuditEventSinkMySqlIntegrationTest` adds ten real-MySQL scenarios:

5. tenant-isolated append, exact hash storage, canonical readback and valid verification;
6. concurrent same-tenant first appends serialize without deadlock or sequence gaps;
7. chain-state admission contains only the bounded primary-key self-assignment;
8. a failed first append rolls back zero-state admission and consumes no sequence;
9. payload tampering is detected without repair;
10. non-string stored attributes are rejected rather than coerced into trusted evidence;
11. chain-state tampering is detected without repair;
12. duplicate event failure rolls back without advancing chain state;
13. queries remain tenant-bound, filtered and paginated;
14. trusted metadata selects the MySQL implementation.

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

P3-C adds no automatic retry and no automatic evidence repair. The corrected admission removes
the known duplicate-insert lock-upgrade cycle rather than retrying a deadlock. Other deadlocks,
connection loss, commit ambiguity and unknown-result behavior require later fault acceptance.

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
- all fourteen P3-C integration scenarios successful;
- exact PostgreSQL V21 payload and chain hash equality;
- real MySQL concurrent first-append admission without deadlock or sequence gaps;
- failed-first-append rollback, tamper detection and query acceptance;
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
