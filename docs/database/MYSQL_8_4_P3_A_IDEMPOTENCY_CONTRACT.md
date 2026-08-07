# MySQL 8.4 P3-A Command Idempotency Contract

Status: `P3_A_CORRECTION_STAGED / REMOTE_VALIDATION_PENDING / MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED`

Decision date: `2026-08-07`

Tracking:

- Issue: `#91`;
- Draft PR: `#92`;
- implementation branch: `agent/mysql-8-4-production-compatibility`;
- non-triggering assembly branch: `agent/mysql-8-4-p2-staging`;
- original source `main`: `0cf6572770953a46fe5b16d15ecdff78cf607855`;
- accepted current `main` incorporated by ordinary Merge Commit:
  `1747b22123fd71cccd8334853ad7060c6645b443`;
- last successful parallel P3 Head before this slice:
  `a2d05106843e87c41a6550a1693449437c3b3bd0`;
- last successful natural PR Run before this slice: `31168524624` / `#1355`;
- first P3-A assembled Head: `c8b999cb582a4313175383bd7011d90012b154fe`;
- retained failed natural PR Run: `31169444506` / `#1356`;
- exact failed Job: Persistence JDBC shard 3, `92837681077`;
- failure classification: `PRODUCT_BUG / MYSQL_JSON_NUMERIC_PRECISION`;
- same-Head rerun count: `0`.

## 1. Scope

P3-A converts and proves one shared high-risk persistence primitive only:

```text
JdbcIdempotencyGuard
```

This slice does not attempt a broad JDBC conversion. It does not claim that task claims, audit
chains, Inbox/Outbox, worker leases, release binding, process-instance migration evidence, AI
evidence, controlled-automation lineage, Flowable or the executable server are MySQL compatible.

The permanent implementation boundary consists of:

- `JdbcIdempotencyDialect`;
- the existing vendor-neutral `JdbcIdempotencyGuard` coordinator;
- the accepted PostgreSQL command-idempotency behavior already exercised by the PostgreSQL product
  integration suite;
- `JdbcIdempotencyGuardMySqlIntegrationTest`;
- `JdbcIdempotencyGuardMySqlContractIntegrationTest`.

## 2. Architecture boundary

Database selection remains server-owned. `JdbcIdempotencyDialect` resolves the database vendor from
trusted JDBC metadata and enforces the supported product/version policy before selecting SQL.
Browser, Mobile, request headers, Connector data, Provider output and command payloads cannot select
or manufacture the database dialect.

The application and domain contracts remain vendor independent. Vendor differences are confined to
the persistence infrastructure:

```text
application IdempotencyGuard port
        |
        v
JdbcIdempotencyGuard transaction coordinator
        |
        +-- PostgreSQL admission / JSONB / replay SQL
        |
        +-- MySQL admission / canonical-text envelope / replay SQL
```

P3-A introduces no database-vendor branch into an application service.

## 3. Admission and replay contract

The logical key remains exact and tenant scoped:

```text
(tenant_id, operation, idempotency_key)
```

Required behavior:

1. the first accepted command inserts one `IN_PROGRESS` record;
2. the command action and the completed serialized result are committed in the same platform
   transaction;
3. an exact replay returns the first completed result without invoking the action again;
4. the same key with a different request hash fails with
   `IdempotencyConflictException`;
5. the same key and payload with a different declared result type fails with
   `IdempotencyConflictException`;
6. an incomplete record is never reported as a completed replay;
7. action failure rolls back admission so a later caller can make a new first attempt;
8. replay never rewrites the original `request_id` or `trace_id` evidence;
9. tenant, operation and case-sensitive key scopes remain independent;
10. concurrent duplicates execute at most one accepted action;
11. database storage cannot change a completed result's exact JSON scalar values.

## 4. MySQL SQL decision

PostgreSQL retains its accepted no-op admission and JSONB result representation:

```text
INSERT ... ON CONFLICT (...) DO NOTHING
result_json = CAST(:resultJson AS jsonb)
```

MySQL uses a plain `INSERT`. A primary-key duplicate is interpreted as a replay candidate only after
the database has applied its normal unique-key serialization. The implementation deliberately does
not use:

```text
INSERT IGNORE
ON DUPLICATE KEY UPDATE
```

Those broad forms could suppress non-duplicate errors or perform mutation where PostgreSQL performs
no-op admission. Unexpected SQL failures remain failures.

### 4.1 Retained precision failure

The first P3-A Run proved that storing the command result directly as a MySQL native JSON object was
not semantically equivalent for exact decimal values:

```text
expected: 123456789012.123456
actual:   123456789012.12344
```

The failing test was:

```text
JdbcIdempotencyGuardMySqlContractIntegrationTest
  .roundTripsUnicodeAndExactNumericJsonWithoutRepeatingTheAction
```

MySQL's binary JSON numeric representation had already changed the decimal before replay. This is a
product implementation defect, not a test-formatting issue. The exact assertion remains unchanged.
The failed Run and multipart Artifact remain retained and were not rerun.

### 4.2 Versioned canonical JSON text envelope

MySQL still uses its native `JSON` column, but the completed result is now stored as a versioned
envelope whose payload is the exact Jackson-produced JSON text:

```json
{
  "encoding": "CANONICAL_JSON_TEXT_V1",
  "payload": "<exact serialized command result JSON>"
}
```

The database parses only the envelope. It does not parse the payload's decimal, integer, Unicode or
other scalar values. Replay accepts only:

- a JSON object;
- exact encoding `CANONICAL_JSON_TEXT_V1`;
- a JSON string payload.

It then applies `JSON_UNQUOTE(JSON_EXTRACT(...))` to recover the original text for the same configured
Jackson contract. A missing, malformed or differently versioned envelope fails closed instead of
silently accepting a lossy native-JSON value.

The connection remains pinned to UTC and `utf8mb4_0900_as_cs`.

## 5. Permanent real-MySQL acceptance matrix

`JdbcIdempotencyGuardMySqlIntegrationTest` retains six scenarios:

1. exact completed replay invokes the action once;
2. same key with a different payload hash conflicts;
3. action failure rolls back admission and permits a later first attempt;
4. concurrent exact duplicates serialize and invoke one action;
5. tenant and case-sensitive key scopes remain independent;
6. MySQL uses narrow admission and the exact versioned canonical-result envelope.

`JdbcIdempotencyGuardMySqlContractIntegrationTest` adds five scenarios:

7. the same idempotency key remains independent across different operations;
8. replay with a different declared result type conflicts without invoking the action;
9. concurrent different-payload use of one logical key fails closed after only the accepted action;
10. Unicode, exact decimal and large-integer JSON values round-trip without a repeated action;
11. replay with different request/trace identifiers preserves the first immutable evidence.

All eleven scenarios must execute against a real `mysql:8.4` Testcontainers instance configured
with:

- InnoDB;
- `utf8mb4_0900_as_cs`;
- UTC server, connection and session semantics;
- `READ COMMITTED`;
- strict SQL mode.

A disabled Docker environment, H2, a mock DataSource or a deterministic in-memory implementation is
not acceptance evidence for this slice.

## 6. PostgreSQL non-regression gate

P3-A may be accepted only when the existing PostgreSQL command/product integration tests continue to
pass unchanged. The MySQL dialect must not modify the PostgreSQL SQL strings, migration history,
JSONB behavior, transaction boundaries, idempotency conflict classification or existing product
results.

The final natural PR Run for this batch must retain:

- all four PostgreSQL persistence shards;
- exact selected-class coverage;
- no duplicate selection;
- no unreported non-abstract selected class;
- Maven Core, Vben, Mobile and Repository Hygiene success;
- the four permanent Artifact classes.

## 7. Failure semantics

The following remain fail closed:

- unsupported database product or version;
- database metadata resolution failure;
- unexpected admission row count;
- result completion row count other than one;
- replay of an `IN_PROGRESS` record;
- request-hash mismatch;
- result-type mismatch;
- missing or differently versioned canonical-result envelope;
- malformed or incompatible persisted JSON text;
- SQL failure other than the exact MySQL duplicate-admission classification.

P3-A adds no automatic retry. Deadlock, connection loss, commit ambiguity and unknown-result handling
remain governed by their existing transaction/application boundaries and require later P4 fault
acceptance before MySQL production support can be declared.

## 8. Explicit non-claims and remaining blockers

P3-A does not prove:

- full MySQL migration upgrade/restore acceptance;
- all production JDBC stores;
- optimistic CAS equivalence outside this idempotency primitive;
- task-claim and bounded-worker locking;
- advisory-lock replacement;
- complete JSON canonicalization for every evidence store;
- complete UUID, BLOB and query-plan equivalence across the repository;
- audit, Inbox/Outbox, lease, release, M5, AI or controlled-automation persistence;
- Flowable or executable-server compatibility;
- dual-vendor permanent CI selections;
- backup, restore, rollback, fault, security, concurrency or performance formal acceptance.

The parallel UUID, UTC timestamp and attachment BLOB primitive at the same Draft PR remains an
independent bounded P3 input and does not turn any of these non-claims into full support.

PR `#92` must remain Draft after this slice. Issues `#91`, `#82` and `#62` must remain Open. No
Ready transition, merge, production-support claim, deployment or Production Promotion is authorized.

## 9. Correction and acceptance rule

The failed Head `c8b999cb582a4313175383bd7011d90012b154fe` must remain visible. It must not be
rerun or replaced by an empty commit. The correction must be carried by new commits and one new
natural `pull_request` Run.

The slice may be recorded as implemented only after one unchanged correction Head completes with:

- all nine physical Jobs successful;
- both MySQL idempotency integration classes successful;
- eleven MySQL idempotency scenarios successful;
- the exact decimal value `123456789012.123456` replayed unchanged;
- exact persistence selection/reports coverage;
- four independently downloaded Artifacts whose local byte counts and SHA-256 values match GitHub;
- no new actionable Review or security finding.

Until then:

```text
MYSQL_P3_A_IDEMPOTENCY_CORRECTION_PENDING
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

After those exact gates pass, the bounded conclusion is:

```text
MYSQL_P3_A_IDEMPOTENCY_SEMANTICS_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```
