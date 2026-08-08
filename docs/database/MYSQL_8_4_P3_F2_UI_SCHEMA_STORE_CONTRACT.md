# MySQL 8.4 P3-F2 UI Schema Store Contract

## Status

```text
source formal Head: 94139c0bd3e31a2d4a15e7074f89ca7d5b65846f
source natural Run: 31239694806 / #1369 / success
implementation Head: 9c7e1156f0976669e69179b7165b376a04e1e44d
implementation Run: 31246001448 / #1371 / success
implementation branch: agent/mysql-8-4-p3-f2-ui-schema-store-staging
acceptance branch: agent/mysql-8-4-p3-f2-acceptance-evidence-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
```

P3-F2 converts only the existing immutable `ApprovalUiSchemaStore` authority for MySQL 8.4.

```text
MYSQL_P3_F2_UI_SCHEMA_STORE_STAGED
MYSQL_P3_F2_UI_SCHEMA_STORE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

The evidence-recording commit containing this final document is validated by its own natural current-Head PR Run. PR metadata, rather than another repository commit, binds that final Run and its Artifacts to avoid an endless evidence-commit cycle.

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

```text
POSTGRESQL_UI_SCHEMA_STORE_UNCHANGED
```

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
if absent -> STRICT INSERT
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

A simple `cast(:schemaJson as json)` or a plain canonical-text envelope is therefore not sufficient for this store.

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

## Hash-equivalence boundary

The real MySQL matrix persists and restores all supported numeric classes and then recomputes `UiSchemaHasher`.

The following must remain equal:

```text
hash(original UiSchemaDefinition)
hash(restored UiSchemaDefinition)
persisted content_hash
```

The matrix includes:

```text
Byte
Short
Integer
Long
BigInteger
Float
Double
BigDecimal with trailing scale
Unicode String
Boolean
nested List
```

This proves that MySQL Binary JSON normalization cannot silently change the Java type or the deterministic UI Schema evidence hash.

## Time contract

MySQL persists `published_at` as UTC `datetime(6)`.

Before persistence, P3-F2 applies the accepted nearest-microsecond contract:

```text
< 500 ns remainder -> round down
>= 500 ns remainder -> round up
carry into the next second when required
```

Readback uses the governed JDBC value adapter. Unsupported JDBC time representations fail closed.

The permanent matrix proves:

```text
2026-08-08T06:07:08.999999500Z
    -> 2026-08-08T06:07:09.000000Z
```

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

## Strict admission and relational boundary

UI Schema publication remains a plain STRICT INSERT. P3-F2 does not use:

```text
INSERT IGNORE
REPLACE INTO
ON DUPLICATE KEY UPDATE
```

The primary identity remains:

```text
tenant_id, form_key, form_version, ui_schema_version
```

The existing foreign key to the immutable Form Definition remains active. P3-F2 does not disable `FOREIGN_KEY_CHECKS`, remove the constraint or seed invalid orphan UI Schemas.

## Implementation structure

P3-F2 adds exactly these production changes:

```text
JdbcApprovalUiSchemaStoreFactory
JdbcMySqlApprovalUiSchemaStore
JdbcMySqlUiSchemaCodec
ApprovalFormConfiguration -> trusted factory binding
```

Permanent acceptance adds:

```text
JdbcApprovalUiSchemaStoreFactoryTest
JdbcApprovalUiSchemaStoreMySqlContractTest
JdbcApprovalUiSchemaStoreMySqlIntegrationTest
```

The append-only implementation sequence is:

```text
02c27203e0434ce53c3e81129f7631f6b31088fc  define bounded P3-F2 contract
b9964223d8a696f7ca33e8faaee624141d3942a5  add closed typed MySQL UI Schema codec
e1ede6702b917905d1ee75882650ee953c2f2c73  add MySQL immutable UI Schema Store
d2060e3c4c7a22bf78fea0c494c57664e4dc0eac  add trusted UI Schema Store factory
05af016acfc52663b2293debddd0e67ad6315fd5  bind executable UI Schema Store through factory
b0d0173d18e77e7409237af0bcb5d664d7b1f43f freeze factory metadata selection
fa5c09064b64914cbbfe1ef6d6a424518fd78a07  freeze source and scope boundaries
73b79327aa93ce840bfbdbe3453466345967b7cc  add real MySQL behavior acceptance
9c7e1156f0976669e69179b7165b376a04e1e44d  tighten evidence cases before formal validation
```

The formal branch moved only through a non-forced fast-forward update. No rebase, force push, direct `main` update, empty commit or same-Head rerun occurred.

## Permanent real-MySQL acceptance matrix

`JdbcApprovalUiSchemaStoreMySqlIntegrationTest` uses a real MySQL 8.4 Testcontainers instance with:

```text
InnoDB
utf8mb4_0900_as_cs
UTC session time
READ COMMITTED
strict SQL mode
datetime(6)
useAffectedRows=false
```

Five test methods prove:

1. trusted factory selection;
2. publication, idempotent replay and audit integration;
3. exact numeric classes, trailing `BigDecimal` scale, Unicode, Boolean and nested-list preservation;
4. stable `UiSchemaHasher` before and after persistence;
5. strict two-member `CANONICAL_UI_SCHEMA_TYPED_JSON_V1` envelope;
6. nearest-microsecond publication-time carry;
7. exact-version and latest-version reads;
8. deterministic highest-version selection;
9. tenant isolation and case-sensitive tenant identity;
10. concurrent same-version publication with one insertion side effect;
11. conflicting content rejection after serialization;
12. lock rejection outside a transaction;
13. surrounding rollback removing the row and releasing the lock;
14. malformed, untyped, unknown-kind, duplicate-key and extended envelopes failing closed.

Accepted implementation result:

```text
JdbcApprovalUiSchemaStoreMySqlIntegrationTest
Tests run: 5
Failures: 0
Errors: 0
Skipped: 0
Time: 13.697 s
```

Supporting permanent boundaries:

```text
JdbcApprovalUiSchemaStoreMySqlContractTest: 3 / 0 / 0 / 0
JdbcApprovalUiSchemaStoreFactoryTest:       2 / 0 / 0 / 0
```

Existing PostgreSQL UI Schema, form-runtime, renderer, design and repository tests remain mandatory regressions.

## Natural implementation validation — Run #1371

Natural `pull_request` Run `31246001448` / `#1371` completed `success` at exact implementation Head:

```text
9c7e1156f0976669e69179b7165b376a04e1e44d
```

All nine physical Jobs succeeded:

| Job | ID | Result |
| --- | ---: | --- |
| UniApp TypeScript / H5 / WeChat | `93074591452` | success |
| Persistence JDBC / shard 1 | `93074591470` | success |
| Vben TypeScript / production build | `93074591472` | success |
| Repository hygiene | `93074591477` | success |
| Java 21 / Maven core | `93074591480` | success |
| Persistence JDBC / shard 3 | `93074591487` | success |
| Persistence JDBC / shard 0 | `93074591493` | success |
| Persistence JDBC / shard 2 | `93074591540` | success |
| Java 21 / Maven / PostgreSQL | `93074918406` | success |

### Independent test reconstruction

```text
Maven Core:                           1469 / 0 / 0 / 0
Persistence JDBC:                      446 / 0 / 0 / 0
Aggregate:                            1915 / 0 / 0 / 0
selected persistence test classes:     106
Surefire report classes:               105
expected abstract without report:        1
duplicate selections:                    0
non-abstract selected without report:    0
selection coverage:                  exact
aggregate reported persistence time: 815.954 s
```

Deterministic shard distribution:

```text
shard 0: 25
shard 1: 30
shard 2: 21
shard 3: 30
unique: 106 / 106
```

P3-F2 suites are independently placed and selected exactly once:

```text
shard 1 -> JdbcApprovalUiSchemaStoreFactoryTest
shard 1 -> JdbcApprovalUiSchemaStoreMySqlContractTest
shard 2 -> JdbcApprovalUiSchemaStoreMySqlIntegrationTest
```

### Independently verified Run #1371 Artifacts

Every ZIP was independently downloaded. Local size and SHA-256 exactly match GitHub metadata, and every archive passes ZIP integrity verification. Every Artifact is bound to branch `agent/mysql-8-4-production-compatibility`, Head `9c7e1156f0976669e69179b7165b376a04e1e44d`, and expires `2026-11-06T07:22:05Z`.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9018541496` | `996360` | `e77de584e9b1a2e49bb627defc931f3f2af4c340398126c44fd71926b0c0a9a6` |
| Vben | `9018521950` | `18880` | `ca2b391518849a65a059f798e8d415c4c5e88a817e431b48302fe70c4770c0c3` |
| Mobile | `9018511186` | `9821` | `9e966304972a7836339e4ace0bc0410119b6a6178cac968756c28934f601a6ec` |
| Hygiene | `9018500960` | `17520` | `c8d021d9d560571b2db884d469af61222738b0945ce263fc0275ab235736729a` |

## CI usage

```text
planned normal implementation Runs: 1
actual implementation Runs:        1
Correction Runs:                    0
same-Head reruns:                   0
empty commits:                      0
force pushes / rebases:             0 / 0
deployments / promotions:           0 / 0
```

## Explicit non-scope and remaining blockers

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
- formal MySQL production promotion.

## Authorization boundary

Authorized:

- retain this accepted bounded MySQL UI Schema Store;
- continue later independent compatibility slices;
- retain natural PR Runs and Artifacts as evidence;
- correct future failures with append-only commits.

Not authorized:

- mark PR #92 Ready or merge it;
- close Issues #91, #82 or #62;
- claim MySQL production support;
- edit already-applied PostgreSQL migrations;
- weaken PostgreSQL behavior or test coverage;
- add a second automatic workflow;
- deploy or execute Production Promotion.

```text
MYSQL_P3_F2_UI_SCHEMA_STORE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
```
