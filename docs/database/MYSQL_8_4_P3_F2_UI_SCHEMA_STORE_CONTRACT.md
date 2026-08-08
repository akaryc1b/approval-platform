# MySQL 8.4 P3-F2 UI Schema Store Contract

## Status

```text
source formal Head: 94139c0bd3e31a2d4a15e7074f89ca7d5b65846f
source natural Run: 31239694806 / #1369 / success
staging branch: agent/mysql-8-4-p3-f2-ui-schema-store-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
```

P3-F2 converts only the existing immutable `ApprovalUiSchemaStore` authority for MySQL 8.4.

```text
MYSQL_P3_F2_UI_SCHEMA_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

## Exact scope

This slice covers only:

1. trusted PostgreSQL/MySQL `ApprovalUiSchemaStore` selection;
2. transaction-scoped UI Schema version serialization;
3. immutable UI Schema save;
4. tenant-scoped exact-version read;
5. tenant-scoped latest-version read;
6. deterministic latest-version ordering;
7. exact closed-type preservation for generic UI values;
8. PostgreSQL-compatible nearest-microsecond publication time;
9. executable-server binding through the trusted factory;
10. real MySQL 8.4 concurrency, rollback and malformed-evidence acceptance.

It introduces no new application command, API, domain model, pagination contract or UI capability.

## Immutable PostgreSQL reference

`JdbcApprovalUiSchemaStore` remains the accepted PostgreSQL 16 implementation.

P3-F2 does not modify its:

- `pg_advisory_xact_lock(hashtextextended(...))` serialization;
- `jsonb` storage;
- UTC `timestamptz` behavior;
- exact-version read;
- latest-version ordering;
- strict insert semantics.

No PostgreSQL Flyway migration is edited.

`POSTGRESQL_UI_SCHEMA_STORE_UNCHANGED`

## Trusted implementation selection

`JdbcApprovalUiSchemaStoreFactory` resolves the database only from trusted JDBC metadata:

```text
PostgreSQL 16 -> JdbcApprovalUiSchemaStore
MySQL 8.4    -> JdbcMySqlApprovalUiSchemaStore
```

The executable `ApprovalFormConfiguration` obtains its `ApprovalUiSchemaStore` Bean through this factory.

No browser, mobile client, HTTP header, Connector, Event, Template, AI result, tenant payload or operator input can select the implementation.

## MySQL transaction serialization

MySQL UI Schema publication uses the accepted bounded transaction-scoped named-lock manager.

The exact logical scope is:

```text
ui-schema:<tenantId>:<formKey>:<formVersion>:<uiSchemaVersion>
```

The lock:

- requires an active synchronized local transaction;
- is acquired on the transaction-bound JDBC connection;
- remains held through commit or rollback;
- is released and verified after transaction completion;
- fails closed on timeout, null, unexpected result or SQL failure;
- is not treated as durable business state;
- adds no automatic retry.

After acquiring the lock, the existing application protocol remains:

```text
find exact version
if same content hash -> replay existing publication
if different content hash -> UiSchemaVersionConflictException
if absent -> strict insert
```

## Why plain JSON text is insufficient

`UiSchemaDefinition` contains generic `Object` values in:

```text
SectionVisibility.expectedValue
ComponentDefinition.properties
```

The deterministic `UiSchemaHasher` includes both the Java value class and its string representation. A plain JSON read can therefore change externally relevant evidence, for example:

```text
BigDecimal("123.4500") -> Double(123.45)
Integer(1)              -> Long(1)
```

A simple `cast(:schemaJson as json)` or a plain canonical-text envelope is not sufficient for this store.

## Closed typed-value protocol

P3-F2 stores a strict outer envelope in the native MySQL `JSON` column:

```json
{
  "encoding": "CANONICAL_UI_SCHEMA_TYPED_JSON_V1",
  "payload": "<typed UI Schema JSON text>"
}
```

Inside the typed payload, every generic value is encoded through a closed data-only protocol:

```text
NULL
STRING
BOOLEAN
NUMBER
LIST
MAP
```

Numbers are limited to the deterministic supported set:

```text
BYTE
SHORT
INTEGER
LONG
BIG_INTEGER
FLOAT
DOUBLE
BIG_DECIMAL
```

Each number stores its exact type tag and exact string value. Non-finite `Float` and `Double` values are rejected before persistence. Unsupported `Number` implementations and unsupported arbitrary Java objects fail closed.

Lists and maps are recursively typed. Map keys must be strings and are encoded in deterministic key order. No class name is accepted from stored data, no reflective type construction occurs and no Jackson polymorphic typing is enabled.

Readback requires:

- the outer root is an object with exactly two members;
- `encoding` exactly equals `CANONICAL_UI_SCHEMA_TYPED_JSON_V1`;
- `payload` is a string;
- the typed payload has no duplicate object keys;
- every generic UI value is a valid typed envelope;
- every typed envelope has exactly the members required by its kind;
- every number type and value is accepted by the closed parser;
- the reconstructed `UiSchemaDefinition` consumes every decoded generic value exactly once.

Missing, unversioned, unknown, duplicate-key, extra-member, malformed or partially typed evidence fails closed. The store performs no evidence repair, old-format guessing or fallback interpretation.

## Time contract

MySQL persists `published_at` as UTC `datetime(6)`.

Before persistence, P3-F2 applies the accepted nearest-microsecond contract:

```text
< 500 ns remainder -> round down
>= 500 ns remainder -> round up
carry into the next second when required
```

Readback uses the governed JDBC value adapter. Unsupported JDBC time representations fail closed.

## Read and ordering contract

Exact reads always require:

```text
tenant_id
form_key
form_version
ui_schema_version
```

Latest reads always require:

```text
tenant_id
form_key
form_version
```

and use deterministic ordering:

```sql
order by ui_schema_version desc
limit 1
```

Tenant identity remains case-sensitive under `utf8mb4_0900_as_cs`. A same form/version in another tenant is independent and never leaks through either query.

## Strict admission

UI Schema publication remains a plain strict insert. P3-F2 does not use:

```text
INSERT IGNORE
REPLACE INTO
ON DUPLICATE KEY UPDATE
```

The primary identity remains:

```text
tenant_id, form_key, form_version, ui_schema_version
```

The existing foreign key to the immutable Form Definition remains active. P3-F2 does not disable `FOREIGN_KEY_CHECKS` or seed invalid orphan UI Schemas.

## Permanent real-MySQL acceptance matrix

A real MySQL 8.4 Testcontainers suite must prove:

1. trusted factory selection;
2. lock rejection outside a transaction;
3. publication, idempotent replay and audit integration;
4. exact `BigDecimal`, `BigInteger`, `Integer`, `Long`, Unicode, nested list and map preservation;
5. stable `UiSchemaHasher` value before and after persistence;
6. strict typed-envelope storage;
7. nearest-microsecond publication-time readback, including carry;
8. exact-version and latest-version reads;
9. deterministic latest-version selection;
10. tenant isolation and case-sensitive tenant identity;
11. concurrent same-version publication has one insertion side effect;
12. conflicting content is rejected after serialization;
13. surrounding rollback removes the UI Schema and releases the lock;
14. malformed, untyped, unknown, duplicate-key or extended envelopes fail closed;
15. existing PostgreSQL UI Schema tests remain green.

## Explicit non-scope

P3-F2 does not implement or imply MySQL compatibility for:

- Form Design Draft Store;
- Form Package Store;
- Form Submission Store;
- remaining Approval Definition/Release lifecycle stores;
- comments, messages or query projections;
- SLA, notification or operational-failure stores;
- M5 migration evidence;
- AI assistance evidence;
- controlled-automation lineage;
- Flowable execution;
- historical migration/restore acceptance;
- query-plan or production performance acceptance;
- complete dual-vendor permanent CI;
- backup/restore, rollback or incident-response rehearsals;
- MySQL production promotion.

## Authorization boundary

Authorized:

- implement and validate this bounded MySQL UI Schema Store;
- bind only this existing Store through trusted metadata selection;
- retain natural PR Runs and Artifacts as evidence;
- correct failures with append-only commits.

Not authorized:

- mark PR #92 Ready or merge it;
- close Issues #91, #82 or #62;
- claim MySQL production support;
- edit already-applied PostgreSQL migrations;
- weaken PostgreSQL behavior or test coverage;
- add a second automatic workflow;
- deploy or execute Production Promotion.

```text
MYSQL_P3_F2_UI_SCHEMA_STORE_IN_PROGRESS
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
```
