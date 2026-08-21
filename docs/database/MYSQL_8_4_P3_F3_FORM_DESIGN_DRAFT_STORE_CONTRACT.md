# MySQL 8.4 P3-F3 Form Design Draft Store Contract

## Baseline identity

```text
source formal Head: 495a33b7cde9e4d628f5a75f78860069b92feac3
source accepted capability: MYSQL_P3_F2_UI_SCHEMA_STORE_PROVEN
implementation Head: e00f5ab1d689227339490daf165c11c5503964cb
implementation Run: 31359965889 / #1378 / success
implementation branch: agent/mysql-8-4-p3-f3-form-design-draft-store-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

P3-F3 converts only the existing mutable `ApprovalFormDesignDraftStore` persistence authority for MySQL 8.4. It does not redesign the form designer and does not add product capability.

```text
MYSQL_P3_F3_FORM_DESIGN_DRAFT_STORE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

The evidence-recording commit containing this final contract must itself be validated by its own natural current-Head Pull Request Run. PR metadata and the Issue checkpoint bind that final Run and its Artifacts after completion so this document does not create an endless evidence-commit cycle.

## Exact production scope

P3-F3 adds exactly these production changes:

```text
JdbcApprovalFormDesignDraftStoreFactory
JdbcMySqlApprovalFormDesignDraftStore
ApprovalFormConfiguration -> trusted factory binding
```

The existing application contract remains:

```text
ApprovalFormDesignDraftStore
ApprovalFormDesignService
FormDesignDraft
```

P3-F3 does not modify the existing PostgreSQL implementation `JdbcApprovalFormDesignDraftStore`, does not modify `ApprovalFormDesignService`, and does not edit PostgreSQL Flyway migrations.

```text
POSTGRESQL_FORM_DESIGN_DRAFT_STORE_UNCHANGED
APPLICATION_FORM_DESIGN_SERVICE_DATABASE_NEUTRAL
```

Permanent acceptance adds exactly these bounded suites:

```text
JdbcApprovalFormDesignDraftStoreFactoryTest
JdbcApprovalFormDesignDraftStoreMySqlContractTest
JdbcApprovalFormDesignDraftStoreMySqlIntegrationTest
```

No Form Package Store, Form Submission Store, Flowable MySQL, Approval Release Lifecycle or CI-matrix implementation is included in P3-F3.

## Trusted implementation selection

`JdbcApprovalFormDesignDraftStoreFactory` resolves the database from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`.

```text
PostgreSQL 16 -> JdbcApprovalFormDesignDraftStore
MySQL 8.4    -> JdbcMySqlApprovalFormDesignDraftStore
```

No request value, profile string, tenant data, HTTP field, browser value, mobile value, connector payload or other user-controlled field selects the persistence implementation.

The executable server binding is now:

```text
ApprovalFormConfiguration
    -> JdbcApprovalFormDesignDraftStoreFactory.create(...)
```

Direct PostgreSQL construction remains available to existing PostgreSQL tests and callers; the server-owned executable path is vendor-selected through trusted metadata only.

## MySQL draft representation

The existing governed MySQL clean-current-schema baseline remains authoritative. P3-F3 does not create a second draft table and does not change the PostgreSQL V9 definition.

MySQL draft identity remains:

```text
tenant_id, draft_id
```

UUID values use the governed `JdbcDatabaseValueAdapter`:

```text
PostgreSQL -> native UUID
MySQL      -> canonical varchar(36) UUID text
```

Required and nullable source/package version columns retain their existing meaning. P3-F3 does not reinterpret or repair those values.

## Mutable timestamp contract

MySQL stores mutable draft timestamps as UTC `datetime(6)` values.

Before MySQL binding, P3-F3 floors each application `Instant` to microsecond precision:

```java
instant.truncatedTo(ChronoUnit.MICROS)
```

This is deliberately different from nearest-microsecond rounding used by separately governed immutable/audit evidence paths.

For mutable drafts, persistence must never manufacture a timestamp later than the application clock merely because the value sits on the half-microsecond boundary.

Permanent edge case:

```text
2026-08-10T05:06:07.999999500Z
    -> 2026-08-10T05:06:07.999999Z
```

It must not carry into the next second.

The rule prevents an immediate update using the same application clock from observing:

```text
createdAt > updatedAt
```

This P3-F3 rule is local to MySQL mutable Form Design Draft persistence. It does not change PostgreSQL timestamp behavior, `AuditHashCanonicalizer`, immutable Form publication time or immutable UI Schema publication time.

## Form Schema evidence

MySQL Binary JSON is not used as the semantic source for Form Schema text.

P3-F3 stores a strict two-member envelope:

```json
{
  "encoding": "CANONICAL_JSON_TEXT_V1",
  "payload": "<exact serialized FormDefinition JSON text>"
}
```

Readback requires:

1. an object outer envelope;
2. exactly two members;
3. exact `CANONICAL_JSON_TEXT_V1` encoding;
4. textual payload;
5. strict duplicate-key detection;
6. successful closed `FormDefinition` reconstruction.

Missing, unknown, extended, duplicate or malformed evidence fails closed. P3-F3 performs no evidence repair, fallback interpretation, old-format guessing or polymorphic class construction.

The permanent matrix proves stable `FormSchemaHasher` values before and after persistence.

## UI Schema evidence

P3-F3 directly reuses the already accepted P3-F2 `JdbcMySqlUiSchemaCodec`:

```text
CANONICAL_UI_SCHEMA_TYPED_JSON_V1
```

This is required because UI Schema generic `Object` values participate in `UiSchemaHasher` with their Java value types. Plain MySQL Binary JSON normalization could otherwise change evidence classes.

The accepted closed typed protocol preserves:

```text
Byte
Short
Integer
Long
BigInteger
Float
Double
BigDecimal including scale
Unicode String
Boolean
nested List
string-keyed Map
```

P3-F3 does not introduce reflective polymorphic typing, class-name deserialization, arbitrary Java object reconstruction or fallback evidence repair.

The permanent matrix proves stable `UiSchemaHasher` values before and after draft persistence.

## Tenant and exact-read contract

Every exact read, row lock and compare-and-swap update requires both:

```text
tenant_id
draft_id
```

There is no draft-id-only fallback read.

The accepted MySQL collation remains:

```text
utf8mb4_0900_as_cs
```

Tenant identity therefore remains case-sensitive.

The permanent matrix proves that:

```text
Tenant-Draft-MySQL
```

and its lower-case spelling are not treated as the same tenant identity.

Another tenant using the same logical Form key remains isolated and is not returned by exact reads or listing.

## Listing contract

Draft listing always predicates by exact tenant identity.

Keyword matching remains case-insensitive within the tenant through parameterized MySQL predicates:

```text
lower(form_key) like concat('%', lower(:keyword), '%')
lower(name)     like concat('%', lower(:keyword), '%')
```

The optional status predicate is server-constructed only as one of:

```text
<empty predicate>
and status = :status
```

P3-F3 does not interpolate keyword or tenant content into SQL.

Ordering remains:

```text
updated_at desc, draft_id
```

Pagination remains parameterized:

```text
limit :limit offset :offset
```

The final implementation does not use Java `String.formatted(...)` for SQL containing literal `%` LIKE tokens. That correction is permanently retained because Java format parsing and SQL wildcard syntax must not share authority.

## Optimistic CAS contract

Mutable updates retain the existing optimistic compare-and-swap predicate:

```text
where tenant_id = :tenantId
  and draft_id = :draftId
  and revision = :expectedRevision
  and status in ('DRAFT', 'VALIDATED')
```

A successful mutation advances the revision.

A concurrent stale writer cannot overwrite a committed row. The Store returns a failed CAS and `ApprovalFormDesignService` converts the lost race into the existing `DraftRevisionConflictException` behavior.

There is no MySQL-specific branch in `ApprovalFormDesignService` and no automatic retry added around the CAS.

## Row-lock contract

Validation/archive serialization continues to use the existing Store `lock` protocol.

PostgreSQL retains its existing row-level `FOR UPDATE` behavior.

MySQL uses InnoDB row-level:

```sql
select revision
from ap_form_design_draft
where tenant_id = :tenantId
  and draft_id = :draftId
for update
```

The lock is scoped to the already existing exact tenant/draft row and is transaction-scoped by the caller.

P3-F3 deliberately does not introduce a new MySQL named-lock/advisory-lock namespace for mutable draft rows.

The permanent real-MySQL matrix proves:

1. transaction A locks the exact draft row;
2. transaction B attempting the same row lock remains blocked while A owns the lock;
3. A can mutate the draft inside its transaction;
4. surrounding rollback removes that mutation;
5. rollback releases the InnoDB row lock;
6. a later transaction can acquire the same row lock and update normally.

## Service transaction contract

`ApprovalFormDesignService` remains database-neutral.

Mutating service commands continue through the existing `JdbcIdempotencyGuard` transaction boundary. P3-F3 does not create a second transaction manager, manual JDBC commit path or MySQL-specific service transaction.

The real MySQL matrix proves two service updates using the same expected revision have exactly:

```text
1 SUCCESS
1 DraftRevisionConflictException
```

The winning mutation is the only committed `FORM_DESIGN_DRAFT_SAVED` audit side effect for that race.

## Strict admission and forbidden shortcuts

P3-F3 retains strict insert/update semantics.

It does not use:

```text
INSERT IGNORE
REPLACE INTO
ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS
automatic CAS retry
evidence repair
pg_advisory_lock
pg_advisory_xact_lock
reflective Jackson default typing
```

P3-F3 does not weaken draft status checks, revision checks, tenant predicates, existing relational constraints or failure semantics.

## P3-F4 exclusion guard

The real MySQL P3-F3 integration suite deliberately supplies an unsupported `ApprovalFormPackageStore`.

Any accidental Package Store execution throws immediately:

```text
P3-F4 Form Package Store is out of scope
```

This ensures P3-F3 cannot silently gain acceptance by exercising an unaccepted MySQL Form Package path.

The suite does not invoke draft package publication.

## Permanent real-MySQL acceptance matrix

`JdbcApprovalFormDesignDraftStoreMySqlIntegrationTest` uses a real `mysql:8.4` Testcontainers instance with:

```text
InnoDB
utf8mb4_0900_as_cs
UTC session time
READ COMMITTED
strict SQL mode
datetime(6)
useAffectedRows=false
```

Four real-MySQL test methods prove:

1. trusted MySQL factory selection;
2. blank draft creation through `ApprovalFormDesignService`;
3. Form/UI update and exact draft readback;
4. deterministic Form/UI evidence hashing after persistence;
5. closed Form JSON envelope;
6. reuse of closed P3-F2 typed UI envelope;
7. Unicode and high-precision typed value round-trip;
8. mutable `datetime(6)` microsecond flooring at the 500 ns boundary;
9. exact tenant isolation and case-sensitive tenant identity;
10. deterministic keyword listing and ordering;
11. two concurrent service updates against one expected revision with exactly one CAS winner;
12. no duplicate committed draft-save audit event from the lost race;
13. real InnoDB `FOR UPDATE` blocking across two transactions;
14. surrounding rollback restoring the original row;
15. rollback releasing the exact row lock;
16. subsequent lock/update success;
17. malformed Form evidence fail-closed behavior;
18. malformed/unknown UI evidence fail-closed behavior.

Accepted focused result from natural Run #1378:

```text
JdbcApprovalFormDesignDraftStoreMySqlIntegrationTest
Tests run: 4
Failures: 0
Errors: 0
Skipped: 0
Time: 13.349 s
```

Supporting permanent boundaries:

```text
JdbcApprovalFormDesignDraftStoreFactoryTest:          2 / 0 / 0 / 0, 0.025 s
JdbcApprovalFormDesignDraftStoreMySqlContractTest:    3 / 0 / 0 / 0, 0.016 s
```

The factory and static contract suites are selected on persistence shard 0. The real MySQL integration suite is selected on persistence shard 2. Each is selected exactly once.

## PostgreSQL non-regression

Existing PostgreSQL Form Design integration tests remain permanent regressions and are not replaced by the MySQL suite.

P3-F3 does not edit:

```text
JdbcApprovalFormDesignDraftStore
ApprovalFormDesignService
PostgreSQL Flyway migrations
```

Natural Run #1378 completed its final `Java 21 / Maven / PostgreSQL` evidence assembly job successfully after all four persistence shards and Maven Core succeeded.

This proves the P3-F3 MySQL persistence path did not require weakening the existing PostgreSQL validation gate.

```text
POSTGRESQL_FORM_DESIGN_DRAFT_STORE_UNCHANGED
POSTGRESQL_16_SUPPORTED
```

## Natural implementation validation — Run #1378

Natural `pull_request` Run:

```text
Run: 31359965889 / #1378
Head: e00f5ab1d689227339490daf165c11c5503964cb
Conclusion: completed / success
```

All nine physical Jobs succeeded:

| Job | ID | Result |
| --- | ---: | --- |
| UniApp TypeScript / H5 / WeChat | `93366772545` | success |
| Vben TypeScript / production build | `93366772573` | success |
| Persistence JDBC / shard 1 | `93366772584` | success |
| Repository hygiene | `93366772585` | success |
| Java 21 / Maven core | `93366772590` | success |
| Persistence JDBC / shard 0 | `93366772613` | success |
| Persistence JDBC / shard 3 | `93366772618` | success |
| Persistence JDBC / shard 2 | `93366772627` | success |
| Java 21 / Maven / PostgreSQL | `93367240628` | success |

## Independent final test reconstruction

The downloaded Maven Artifact was independently unpacked and its retained reports reconstructed.

Maven Core:

```text
tests: 1469
failures: 0
errors: 0
skipped: 0
```

Persistence JDBC:

```text
shards: 4
selected test classes: 109
Surefire report classes: 108
selected abstract classes without reports: 1
tests: 455
failures: 0
errors: 0
skipped: 0
aggregate reported test time: 798.938 s
selection coverage: exact
duplicate selection count: 0
non-abstract selected classes without reports: 0
```

Combined Maven Core + Persistence test count:

```text
1924 / 0 failures / 0 errors / 0 skipped
```

Deterministic persistence shard distribution:

```text
shard 0: 27
shard 1: 30
shard 2: 22
shard 3: 30
unique: 109 / 109
total selected: 109
```

P3-F3 suites are selected exactly once:

```text
shard 0 -> JdbcApprovalFormDesignDraftStoreFactoryTest
shard 0 -> JdbcApprovalFormDesignDraftStoreMySqlContractTest
shard 2 -> JdbcApprovalFormDesignDraftStoreMySqlIntegrationTest
```

## Independently verified Run #1378 Artifacts

Every final ZIP was independently downloaded from GitHub Actions, measured locally, SHA-256 recomputed locally and checked with ZIP integrity verification.

Local byte counts and SHA-256 values exactly match GitHub Artifact metadata.

All four Artifacts are bound to:

```text
branch: agent/mysql-8-4-production-compatibility
Head: e00f5ab1d689227339490daf165c11c5503964cb
Run: 31359965889 / #1378
```

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9052044983` | `1008681` | `6c44b7f28fb027e74ae3f1fcaa9d761ea06546a934a7ce459d890aea9e19e700` |
| Vben | `9052018340` | `18887` | `5afcb455e16beee83e30fb3e0af9b8d365d972c6c09a0125426612c9cf6c17dc` |
| Mobile | `9052003026` | `9800` | `24ad14338957d2fc07a32d98f743b8a723daf31ffc94eef49b95a5a87d62cb65` |
| Hygiene | `9051985070` | `17508` | `d0d6949af5345032387135bfcb9ee3d766884e81db48df949dd702f3e33a9d12` |

Every archive passed ZIP integrity verification.

Artifact expiration recorded by GitHub:

```text
2026-11-08T05:52:13Z
```

## P3-F3 correction trail

No failed or superseded Head is hidden.

### Run #1373 — superseded after Checkstyle signal

```text
Run: 31359292701 / #1373
Head: a4b5397e47d97da355f22a57e826da2fc8b3d87f
Final workflow conclusion: cancelled after a newer Head superseded it
```

Before cancellation, all four persistence jobs reached the same persistence-module Checkstyle failure:

```text
Unused import: UiSchemaDefinition
```

Classification:

```text
WORKFLOW_FORMATTING / CHECKSTYLE_UNUSED_IMPORT
```

Correction:

```text
106f4b8475df8f6d5e8000b36e1fefddc43b2cf9
```

No same-Head rerun was used.

### Run #1374 — mutable datetime precision defect

```text
Run: 31359383569 / #1374
Head: 106f4b8475df8f6d5e8000b36e1fefddc43b2cf9
Conclusion: failure
```

The real MySQL P3-F3 integration suite exposed:

```text
updatedAt must not be before createdAt
```

Root cause:

```text
nearest-microsecond rounding of ...999999500Z
carried persisted createdAt into the next second
while an immediate same-clock update retained the original Instant
```

Classification:

```text
PRODUCT_BUG / MYSQL_MUTABLE_DATETIME_ROUNDING
```

Correction changed only the MySQL mutable draft timestamp adapter to floor at microsecond precision.

The permanent 500 ns boundary remains in the real MySQL matrix.

### Runs #1375 and #1376 — superseded correction Heads

```text
#1375 / 31359609824 / Head 734f29aee9f735ebf3effe327d8a55019be3435d / cancelled
#1376 / 31359741842 / Head 3027bf8071bd1e9866c08bbb9bf295742d9f4f4f / cancelled
```

These were natural Pull Request Runs created by append-only correction commits and then cancelled when a newer correction/evidence Head superseded them.

They were not manually rerun and are not used as acceptance evidence.

### Run #1377 — MySQL listing SQL formatting defect

```text
Run: 31359765327 / #1377
Head: 79e54379bb09e0d0afad1288cb4538dc292f82c7
Conclusion: failure
```

Three of the four real MySQL P3-F3 test methods passed. The remaining listing path exposed:

```text
UnknownFormatConversionException: Conversion = '\''
```

Root cause:

```text
String.formatted(...) parsed literal SQL LIKE '%' wildcard tokens as Java format syntax
```

Classification:

```text
PRODUCT_BUG / MYSQL_DRAFT_LIST_SQL_FORMATTING
```

Correction removed Java formatter authority from the query and retained parameterized keyword SQL plus a bounded server-owned status placeholder replacement.

Final correction Head:

```text
e00f5ab1d689227339490daf165c11c5503964cb
```

Natural Run #1378 then completed success.

## CI usage and branch discipline

P3-F3 used append-only commits and natural Pull Request validation.

```text
same-Head reruns: 0
empty commits: 0
force pushes: 0
rebases: 0
auto-merge: 0
Ready transition: 0
merge: 0
deployments / promotions: 0 / 0
```

The formal branch was advanced from the P3-F3 staging lineage only by a non-forced fast-forward update.

Failures were corrected with new commits rather than hidden, retried in place or rewritten.

## Explicit non-scope and remaining blockers

P3-F3 does not implement or imply MySQL compatibility for:

- P3-F4 Form Package Store;
- P3-F5 Form Submission Store;
- Approval Release Lifecycle;
- Flowable MySQL execution;
- a complete dual-database CI matrix;
- historical upgrade or restore;
- backup/restore rehearsal;
- MySQL production authorization;
- PR Ready transition;
- merge of PR #92;
- closure of Issue #91.

Other Issue #91 work remains mandatory before MySQL 8.4 can become a production-supported target.

## Authorization boundary

Authorized after P3-F3 acceptance:

- retain this accepted bounded MySQL Form Design Draft Store;
- continue later independent compatibility slices only when separately scoped;
- retain natural Pull Request Runs and Artifacts as evidence;
- correct later failures through append-only commits.

Not authorized by P3-F3:

- mark PR #92 Ready;
- merge PR #92;
- close Issue #91;
- claim `MYSQL_8_4_PRODUCTION_SUPPORTED`;
- weaken PostgreSQL behavior or test coverage;
- modify already-applied PostgreSQL migrations;
- execute Production Promotion.

```text
MYSQL_P3_F3_FORM_DESIGN_DRAFT_STORE_PROVEN
POSTGRESQL_16_SUPPORTED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
