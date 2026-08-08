# MySQL 8.4 P3-F1 Form Definition Store Contract

## Status

```text
source formal Head: dfdc2719de008e3e9d5862455fbfeeaba6c3ef0e
source natural Run: 31234925377 / #1366 / success
staging branch: agent/mysql-8-4-p3-f1-form-store-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
```

P3-F1 converts only the existing immutable `ApprovalFormStore` authority for MySQL 8.4.

```text
MYSQL_P3_F1_FORM_DEFINITION_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

## Exact scope

This slice covers only:

1. trusted PostgreSQL/MySQL `ApprovalFormStore` selection;
2. transaction-scoped form-version serialization;
3. immutable form-definition save;
4. tenant-scoped exact version read;
5. tenant-scoped keyword search;
6. deterministic pagination;
7. exact Form Schema JSON text preservation;
8. PostgreSQL-compatible nearest-microsecond publication time;
9. executable-server binding through the trusted factory;
10. real MySQL 8.4 concurrency, rollback and malformed-evidence acceptance.

It introduces no new application command, API or domain model.

## Immutable PostgreSQL reference

`JdbcApprovalFormStore` remains the accepted PostgreSQL 16 implementation.

P3-F1 does not modify its:

- `pg_advisory_xact_lock(hashtextextended(...))` serialization;
- `jsonb` storage;
- UTC `timestamptz` behavior;
- case-insensitive `lower(...)` search;
- deterministic ordering;
- strict insert semantics.

No PostgreSQL Flyway migration is edited.

## Trusted implementation selection

`JdbcApprovalFormStoreFactory` resolves the database only from trusted JDBC metadata:

```text
PostgreSQL 16 -> JdbcApprovalFormStore
MySQL 8.4    -> JdbcMySqlApprovalFormStore
```

The executable `ApprovalFormConfiguration` obtains its `ApprovalFormStore` Bean through this factory.

No browser, mobile client, HTTP header, Connector, Event, Template, AI result, tenant payload or operator input can select the implementation.

## MySQL transaction serialization

MySQL form publication uses the existing bounded transaction-scoped named-lock manager.

The exact logical scope is:

```text
form:<tenantId>:<formKey>:<formVersion>
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
if different content hash -> FormVersionConflictException
if absent -> strict insert
```

## MySQL JSON preservation

`FormDefinition` contains exact numeric values such as `BigDecimal minimum` constraints. MySQL Binary JSON can normalize a numeric token and therefore must not become the canonical representation of the Form Schema payload.

The MySQL store writes a versioned text envelope into the native `JSON` column:

```json
{
  "encoding": "CANONICAL_JSON_TEXT_V1",
  "payload": "<exact Jackson FormDefinition JSON text>"
}
```

Readback requires all of the following:

- the root is a JSON object;
- the object contains exactly two members;
- `encoding` is a string equal to `CANONICAL_JSON_TEXT_V1`;
- `payload` is a JSON string;
- the payload decodes into `FormDefinition`.

Missing, unversioned, unknown, extra-member or malformed envelopes fail closed. The store performs no evidence repair or fallback interpretation.

This preserves exact decimal text, field order, option order, Unicode and literal JSON content while retaining MySQL native JSON validation.

## Time contract

MySQL persists `published_at` as UTC `datetime(6)`.

Before persistence, P3-F1 applies the accepted nearest-microsecond contract used by PostgreSQL/pgjdbc evidence:

```text
< 500 ns remainder -> round down
>= 500 ns remainder -> round up
carry into the next second when required
```

Readback uses the governed JDBC value adapter. Unsupported JDBC time representations fail closed.

## Search and pagination

MySQL search preserves the existing externally visible query shape:

```text
tenant_id = exact tenant
keyword absent -> all tenant forms
keyword present -> lower(form_key/name) contains lower(keyword)
```

MySQL replaces PostgreSQL string concatenation with parameterized `concat(...)`.

Ordering remains:

```text
published_at DESC,
form_key ASC,
form_version DESC
```

The tenant plus ordered key is deterministic. `limit` and `offset` remain validated by `ApprovalFormStore.FormCriteria` and bound as parameters.

## Strict admission and tenant boundary

Form publication remains a plain strict insert. P3-F1 does not use:

```text
INSERT IGNORE
REPLACE INTO
ON DUPLICATE KEY UPDATE
```

The primary key remains:

```text
tenant_id, form_key, form_version
```

Exact version reads and searches always include the tenant predicate. A same key/version in another tenant is independent and must never leak through query results.

## Permanent real-MySQL acceptance matrix

A real MySQL 8.4 Testcontainers suite must prove:

1. trusted factory selection;
2. lock rejection outside a transaction;
3. publication, idempotent replay and audit integration;
4. exact Unicode and high-precision `BigDecimal` round-trip;
5. strict canonical text-envelope storage;
6. nearest-microsecond publication-time readback, including carry;
7. case-insensitive key/name search through MySQL `lower` + `concat`;
8. tenant isolation;
9. deterministic tie ordering and pagination;
10. concurrent same-version publication has one insertion side effect;
11. conflicting content is rejected after serialization;
12. surrounding rollback removes the form and releases the lock;
13. malformed or unknown JSON envelopes fail closed;
14. existing PostgreSQL form tests remain green.

## Explicit non-scope

P3-F1 does not implement or imply MySQL compatibility for:

- UI Schema Store;
- Form Design Draft Store;
- Form Package Store;
- Form Submission Store;
- Approval Definition/Release publication beyond the already accepted core projection path;
- comments, messages or query projections;
- SLA, notification or operational-failure stores;
- M5 migration evidence;
- AI assistance evidence;
- controlled-automation lineage;
- Flowable execution;
- historical migration/restore acceptance;
- query-plan or production performance acceptance;
- MySQL production promotion.

## Authorization boundary

Authorized:

- implement and validate this bounded MySQL Form Definition Store;
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
MYSQL_P3_F1_FORM_DEFINITION_STORE_IN_PROGRESS
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
```
