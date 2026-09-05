# MySQL 8.4 P3-B Inbox and Outbox Transaction Contract

Status: `P3_B_IMPLEMENTED_PENDING_NATURAL_VALIDATION`

Date: `2026-08-07`

Tracking:

- Issue `#91`;
- Draft PR `#92`;
- branch `agent/mysql-8-4-production-compatibility`;
- synchronized source `main`: `1747b22123fd71cccd8334853ad7060c6645b443`;
- PostgreSQL 16 remains the accepted production reference;
- MySQL 8.4 production support remains blocked.

## Scope

This slice converts the existing transactional Inbox and Outbox repositories from PostgreSQL-only
SQL to bounded PostgreSQL/MySQL infrastructure behavior. It does not add a broker, scheduler,
listener, automatic retry product, Connector mutation authority or production promotion.

The exact product invariants are:

1. one Inbox acquisition for one case-sensitive `(tenant, consumer, message)` identity;
2. same-payload replay reports `IN_PROGRESS` or `ALREADY_COMPLETED` without duplicate work;
3. different-payload replay reports `PAYLOAD_MISMATCH`;
4. expired Inbox leases may be reacquired exactly once and a different stale owner cannot complete
   or fail;
5. one Outbox row for one case-sensitive `(tenant, connector, idempotency key)` identity;
6. due Outbox rows are claimed in deterministic `(available_at, created_at, id)` order with row
   locks and `SKIP LOCKED`;
7. concurrent workers receive disjoint bounded claim sets;
8. expired Outbox leases may be reclaimed and a different stale owner cannot deliver, reschedule or
   dead-letter;
9. UUID, canonical JSON text and UTC microsecond timestamps round-trip without authority drift;
10. tenant and worker identities remain server-owned predicates.

The current interface identifies a lease owner by `workerId`. Production worker IDs therefore must
be unique per active worker incarnation. A separate opaque fencing token would require a versioned
application-port change and remains part of the later complete worker-protocol acceptance.

## Database-specific admission

PostgreSQL Inbox and Outbox admission uses `ON CONFLICT ... DO NOTHING` so a duplicate does not abort
an enclosing PostgreSQL transaction.

MySQL uses a plain strict `INSERT`. Only a translated duplicate-key exception is classified as a
replay or duplicate result. For Outbox, a duplicate is accepted only after the exact business key
`(tenant_id, connector_key, idempotency_key)` is found. A primary-key UUID collision carrying a
different business key remains an error.

The MySQL path deliberately does not use:

- `INSERT IGNORE`;
- `ON DUPLICATE KEY UPDATE`;
- warning-based truncation;
- catch-all success fallback.

Unexpected SQL and constraint failures remain errors.

## Canonical Outbox JSON

MySQL binary JSON may normalize the lexical representation of large or high-precision numbers. The
Outbox therefore follows the accepted idempotency correction and stores the serialized event payload
as a versioned text envelope inside the native JSON column:

```json
{
  "encoding": "CANONICAL_JSON_TEXT_V1",
  "payload": "<exact serialized JSON text>"
}
```

Readback accepts only this exact envelope version and a JSON string payload. Missing, unversioned or
unknown envelopes fail closed. This preserves Unicode, large integers and decimal lexical precision
without pretending that MySQL binary JSON is a byte-preserving canonicalization format.

PostgreSQL keeps its accepted `jsonb` implementation unchanged in this slice.

## Claim transaction

MySQL Outbox claim is a three-step transaction on one JDBC connection:

1. select due candidate IDs in deterministic `(available_at, created_at, id)` order using
   `FOR UPDATE SKIP LOCKED` and a bounded limit;
2. update exactly those locked rows to `IN_FLIGHT`, binding worker and lease expiry;
3. read back exactly the claimed rows and fail if the count differs.

PostgreSQL retains its accepted CTE/update/`RETURNING` implementation. The physical statements are
vendor specific while the product protocol remains the same. `SKIP LOCKED` is used only for this
queue-like claim boundary.

## Lease validity

A worker may mutate a claimed row only while all of the following remain true:

- the row is still in the expected processing state;
- `locked_by` equals the exact worker ID;
- `locked_until` is strictly later than the trusted operation instant.

The due predicate treats `locked_until <= now` as expired. Therefore the validity predicate is
exactly `locked_until > operationTime`; the equality boundary cannot be owned by both the expired
and recovering worker.

## Value conversion

The integration JDBC dialect is selected from trusted JDBC metadata and accepts only PostgreSQL 16
or exact MySQL 8.4. It provides bounded conversion for:

- PostgreSQL native UUID versus MySQL canonical `varchar(36)` UUID;
- PostgreSQL UTC `OffsetDateTime` versus MySQL UTC `datetime(6)` values;
- PostgreSQL `jsonb` versus the governed MySQL canonical-text envelope in native `json`.

Unsupported products, versions or JDBC value representations fail closed.

## Verification

The permanent PostgreSQL `JdbcInboxOutboxIntegrationTest` remains required and adds regression proof
for transaction-safe duplicate admission, primary-key collision rejection and expired-owner fencing.

`JdbcInboxMySqlIntegrationTest` and `JdbcOutboxMySqlIntegrationTest` use a shared real MySQL 8.4
Testcontainers instance and the governed MySQL V50 Flyway lineage. Together they cover:

- transaction-safe duplicate Outbox admission and primary-key collision rejection;
- UUID, Unicode, large integer, exact decimal and microsecond timestamp round-trip;
- lease expiry and stale-owner rejection;
- concurrent disjoint bounded `SKIP LOCKED` claims;
- concurrent Inbox duplicate admission;
- payload mismatch and completed replay;
- concurrent expired Inbox lease reacquisition;
- case-sensitive Inbox and Outbox keys;
- invalid bounds with zero database mutation;
- rejection of broad MySQL ignore/update admission syntax.

The predecessor Run `31170245564` / `#1357` is retained as a Checkstyle correction Run: the shared
failure was four idempotency files missing their final newline, not an Inbox/Outbox result. The
append-only correction completed successfully before this slice was submitted.

Run `31173287561` / `#1359` at implementation Head
`d02f359831029c34447782d817e2a581d54bda88` is retained as a focused test-assertion correction
Run. Maven Core, Vben, Mobile, Hygiene and Persistence shards `0`, `2` and `3` succeeded. Shard `1`
executed the new real-MySQL Outbox test and reported one assertion failure: the test expected three
rows after one insert, one business-key replay, one independent insert and one rejected primary-key
collision. The correct durable row count is two because neither replay nor rejected collision may
insert a row. Production SQL and transaction semantics passed; only the expected count was wrong.
No same-Head rerun is authorized or used.

This slice is accepted only after its exact unchanged Head passes one natural PR workflow with all
required Jobs, zero unapproved skips and independently verified Artifacts.

## Remaining blockers

This slice does not complete:

- the full production JDBC store inventory;
- an opaque per-claim fencing-token port;
- Audit hash-chain equivalence;
- approval projections and task CAS;
- release/runtime binding;
- SLA, notification and operational-failure persistence;
- M5 migration evidence;
- AI evidence and controlled-automation lineage;
- Flowable/executable-server MySQL acceptance;
- permanent dual-vendor CI separation;
- backup, restore, rollback, fault, performance and formal acceptance.

```text
MYSQL_8_4_INBOX_OUTBOX_IMPLEMENTED_PENDING_VALIDATION
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```
