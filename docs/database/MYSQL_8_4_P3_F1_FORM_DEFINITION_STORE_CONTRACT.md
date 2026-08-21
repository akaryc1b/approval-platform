# MySQL 8.4 P3-F1 Form Definition Store Contract

## Status

```text
source formal Head: dfdc2719de008e3e9d5862455fbfeeaba6c3ef0e
source natural Run: 31234925377 / #1366 / success
implementation Head: 46836a705b6b3f92f79695af75db059e9f7a2349
implementation Run: 31239228406 / #1367 / retained failure
correction Head: 23c5d4c25f3435f909441e5fa5323fef693dbec2
accepted implementation Run: 31239422078 / #1368 / success
staging branch: agent/mysql-8-4-p3-f1-form-store-staging
correction branch: agent/mysql-8-4-p3-f1-contract-correction-staging
acceptance branch: agent/mysql-8-4-p3-f1-acceptance-evidence-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
```

P3-F1 converts only the existing immutable `ApprovalFormStore` authority for MySQL 8.4.

```text
MYSQL_P3_F1_FORM_DEFINITION_STORE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

The evidence-recording commit containing this final document is validated by its own natural current-Head PR Run. PR metadata, rather than another repository commit, binds that final Run and its Artifacts to avoid an endless evidence-commit cycle.

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

`POSTGRESQL_FORM_STORE_UNCHANGED`

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

Exact version reads and searches always include the tenant predicate. A same key/version in another tenant is independent and never leaks through query results.

## Implementation structure

P3-F1 adds exactly these production changes:

```text
JdbcApprovalFormStoreFactory
JdbcMySqlApprovalFormStore
ApprovalFormConfiguration -> trusted factory binding
```

Permanent acceptance adds:

```text
JdbcApprovalFormStoreFactoryTest
JdbcApprovalFormStoreMySqlContractTest
JdbcApprovalFormStoreMySqlIntegrationTest
```

The append-only implementation sequence is:

```text
01965a45baaa473e91db42a63b47af6cb06806b9  define bounded P3-F1 contract
9c8ae44dba5320bd40332b9617f0f8643591cd8a  add trusted form-store factory
a83e4d69ec8f2cceb7db1186779e0c15f52f693c  add MySQL immutable form store
0f895ff3795663159301c187d9c28041eda5d8a8  bind executable form store through factory
f5cbed3885b547fe33dca419395408d497034af1  freeze factory metadata selection
c67dc80c505a86fecc7cd719dd3729c487032706  add real MySQL behavior acceptance
46836a705b6b3f92f79695af75db059e9f7a2349  freeze source and scope boundaries
23c5d4c25f3435f909441e5fa5323fef693dbec2  correct SQL-concatenation static boundary
```

The formal branch moved only through non-forced fast-forward updates. No rebase, force push, direct `main` update or empty commit occurred.

## Permanent real-MySQL acceptance matrix

`JdbcApprovalFormStoreMySqlIntegrationTest` uses a real MySQL 8.4 Testcontainers instance with:

```text
InnoDB
utf8mb4_0900_as_cs
UTC session time
READ COMMITTED
strict SQL mode
datetime(6)
useAffectedRows=false
```

Five test methods prove all required behavior:

1. trusted factory selection;
2. publication, idempotent replay and audit integration;
3. exact Unicode and high-precision `BigDecimal` text preservation;
4. strict two-member `CANONICAL_JSON_TEXT_V1` envelope;
5. nearest-microsecond publication-time carry;
6. case-insensitive key/name search through `lower` and `concat`;
7. exact tenant isolation and case-sensitive tenant identity;
8. deterministic tie ordering and pagination;
9. concurrent same-version publication with one insertion side effect;
10. conflicting content rejection after serialization;
11. lock rejection outside a transaction;
12. surrounding rollback removing the row and releasing the lock;
13. malformed, unversioned, unknown and extended envelopes failing closed.

Accepted real-MySQL result at correction Head:

```text
JdbcApprovalFormStoreMySqlIntegrationTest
Tests run: 5
Failures: 0
Errors: 0
Skipped: 0
Time: 15.296 s
```

Supporting permanent boundaries:

```text
JdbcApprovalFormStoreMySqlContractTest: 3 / 0 / 0 / 0
JdbcApprovalFormStoreFactoryTest:       2 / 0 / 0 / 0
```

Existing PostgreSQL form, form-runtime, renderer, design and repository tests remain mandatory regressions.

## Retained failure and Correction trail

### Run #1367 — static contract false positive

Natural `pull_request` Run:

```text
Run: 31239228406
Number: #1367
Head: 46836a705b6b3f92f79695af75db059e9f7a2349
Conclusion: failure
Same-Head rerun: none
Classification: STATIC_CONTRACT_FALSE_POSITIVE / JAVA_BOOLEAN_OR_MISCLASSIFIED_AS_SQL_CONCAT
```

The production implementation and all real MySQL behavior succeeded. The only failing class was the new static source contract:

```text
JdbcApprovalFormStoreMySqlContractTest
Tests run: 3
Failures: 1
Errors: 0
Skipped: 0
```

The assertion searched the complete Java source for the generic token:

```text
 || 
```

and therefore matched Java boolean OR expressions inside fail-closed envelope validation. It did not identify PostgreSQL SQL concatenation.

Run #1367 retained successful evidence includes:

```text
JdbcApprovalFormStoreMySqlIntegrationTest: 5 / 0 / 0 / 0, 15.196 s
JdbcApprovalFormStoreFactoryTest:          2 / 0 / 0 / 0
Persistence shard 3:                       152 / 0 / 0 / 0
```

The final failed Maven Artifact was independently downloaded and verified:

```text
Artifact: 9016483829
Bytes: 985344
SHA-256: fe68c4007672425c96938ee13c1cf41a4a79c02ea4c3cd37d6fc3b8af317e05f
ZIP integrity: valid
```

### Append-only correction

Commit `23c5d4c25f3435f909441e5fa5323fef693dbec2` changes only the static contract pattern:

```text
generic Java token:  " || "
exact PostgreSQL SQL: "lower('%' || :keyword || '%')"
```

No production source, SQL, JSON envelope, lock protocol, server binding, migration, retry policy or production-support flag changed.

## Accepted implementation validation — Run #1368

Natural `pull_request` Run `31239422078` / `#1368` completed `success` at exact correction Head:

```text
23c5d4c25f3435f909441e5fa5323fef693dbec2
```

All nine physical Jobs succeeded:

| Job | ID | Result |
| --- | ---: | --- |
| Repository hygiene | `93057745732` | success |
| Persistence JDBC / shard 0 | `93057745737` | success |
| Java 21 / Maven core | `93057745742` | success |
| Persistence JDBC / shard 1 | `93057745744` | success |
| Persistence JDBC / shard 2 | `93057745756` | success |
| Vben TypeScript / production build | `93057745760` | success |
| UniApp TypeScript / H5 / WeChat | `93057745769` | success |
| Persistence JDBC / shard 3 | `93057745821` | success |
| Java 21 / Maven / PostgreSQL | `93058053822` | success |

### Independent test reconstruction

```text
Maven Core:                           1469 / 0 / 0 / 0
Persistence JDBC:                      436 / 0 / 0 / 0
Aggregate:                            1905 / 0 / 0 / 0
selected persistence test classes:     103
Surefire report classes:               102
expected abstract without report:        1
duplicate selections:                    0
non-abstract selected without report:    0
selection coverage:                  exact
aggregate reported persistence time: 804.107 s
```

Deterministic shard distribution:

```text
shard 0: 25
shard 1: 28
shard 2: 20
shard 3: 30
unique: 103 / 103
```

The P3-F1 suites are independently placed and selected exactly once:

```text
shard 0 -> JdbcApprovalFormStoreFactoryTest
shard 1 -> JdbcApprovalFormStoreMySqlContractTest
shard 3 -> JdbcApprovalFormStoreMySqlIntegrationTest
```

### Independently verified Run #1368 Artifacts

Every ZIP was independently downloaded. Local size and SHA-256 exactly match GitHub metadata, and every archive passes ZIP integrity verification. Every Artifact is bound to branch `agent/mysql-8-4-production-compatibility`, Head `23c5d4c25f3435f909441e5fa5323fef693dbec2`, and expires `2026-11-06T04:25:46Z`.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9016549071` | `984342` | `164896c067654c51df23702bb46e9aff79053caee4fef2dd37a22d956d78106e` |
| Vben | `9016531434` | `18903` | `66a33b03539f328b5434a8f6a8edc2eb976c0a7e7497d49c77ae502ded5322f5` |
| Mobile | `9016520873` | `9813` | `c59dd13219fe9a0682a1f02c92d896b9cbf8227804977738b93403d971b8e07d` |
| Hygiene | `9016510625` | `17524` | `6b782e70d0b5baa8ba818ca054e263691f3bff7a2fc3960aa3568ea1ef59b54c` |

## CI usage

```text
planned normal Runs:       1
actual Runs:               2
Correction Runs:           1
same-Head reruns:          0
empty commits:             0
force pushes / rebases:    0 / 0
deployments / promotions:  0 / 0
```

## Explicit non-scope and remaining blockers

P3-F1 does not implement or imply MySQL compatibility for:

- UI Schema Store;
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

- retain this accepted bounded MySQL Form Definition Store;
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
MYSQL_P3_F1_FORM_DEFINITION_STORE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
```
