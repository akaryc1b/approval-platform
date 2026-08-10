# MySQL 8.4 P3-F5 Form Submission Store Contract

## Baseline identity

```text
source accepted capability: MYSQL_P3_F4_FORM_PACKAGE_STORE_PROVEN
source formal Head: d75b148eed6788c86c4f6cac9c2959d44e04bd33
implementation branch: agent/mysql-8-4-p3-f5-form-submission-store-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

P3-F5 converts only the existing `ApprovalFormSubmissionStore` persistence authority for MySQL 8.4. That port owns both the immutable initial form submission snapshot and its immutable per-instance revision chain.

```text
MYSQL_P3_F5_FORM_SUBMISSION_STORE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

## Exact production scope

P3-F5 adds:

```text
JdbcApprovalFormSubmissionStoreFactory
JdbcMySqlApprovalFormSubmissionStore
ApprovalFormConfiguration -> trusted factory binding
```

The existing application/domain contracts remain unchanged:

```text
ApprovalFormSubmissionStore
ApprovalFormSubmissionService
ApprovalFormRuntimeService
FormSubmissionHasher
```

The PostgreSQL implementation `JdbcApprovalFormSubmissionStore` remains unchanged. PostgreSQL Flyway history remains immutable.

```text
POSTGRESQL_FORM_SUBMISSION_STORE_UNCHANGED
APPLICATION_FORM_SUBMISSION_SERVICES_DATABASE_NEUTRAL
```

## Trusted implementation selection

`JdbcApprovalFormSubmissionStoreFactory` derives the implementation only from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`.

```text
PostgreSQL 16 -> JdbcApprovalFormSubmissionStore
MySQL 8.4    -> JdbcMySqlApprovalFormSubmissionStore
```

No request field, tenant payload, browser input, profile string, workflow payload or user-controlled value can select the persistence dialect.

## Initial immutable submission contract

The initial submission remains a strict immutable insert into `ap_form_submission` with existing identity/evidence fields:

```text
submission_id
tenant_id
form_key
form_version
schema_hash
ui_schema_version + ui_schema_hash
business_key
values_json
start_parameters_json
instance_id
submitted_by
submitted_at
request_hash
```

Exact reads remain:

```text
findByInstance -> tenant_id + instance_id
findByBusinessKey -> tenant_id + business_key
```

The same business key may be used independently by another tenant, but the same tenant/business-key identity may not silently mutate an existing immutable submission.

P3-F5 does not introduce `INSERT IGNORE`, `REPLACE`, `ON DUPLICATE KEY UPDATE`, silent overwrite or automatic retry.

## Submission JSON evidence contract

`FormSubmission.values`, `FormSubmission.startParameters` and revision values are generic `Map<String,Object>` data. The accepted PostgreSQL store serializes those maps with the persistence `ObjectMapper`, stores JSON, and reconstructs them through the same `Map<String,Object>` target type.

MySQL Binary JSON may normalize numeric text before application readback. To prevent the database from becoming an additional unversioned serializer, MySQL writes a strict two-member outer envelope:

```json
{
  "encoding": "CANONICAL_FORM_SUBMISSION_JSON_TEXT_V1",
  "payload": "<ObjectMapper-produced map JSON text>"
}
```

The inner payload is therefore preserved as text inside MySQL JSON and is reconstructed through the same `Map<String,Object>` ObjectMapper semantics as the PostgreSQL store.

This deliberately does **not** reuse the P3-F2 UI Schema Java-number type protocol. `FormSubmissionHasher` already canonicalizes all `Number` values through `BigDecimal(number.toString()).stripTrailingZeros()`, and P3-F5 preserves cross-database application behavior rather than introducing MySQL-specific generic Java value classes.

The outer envelope fails closed when it is null, malformed, duplicated, has an unknown encoding, has extra members, or has a non-text payload.

## Immutable time contract

`submitted_at` and revision `modified_at` are immutable evidence timestamps and follow the accepted immutable MySQL timestamp rule used by P3-F1, P3-F2 and P3-F4:

```text
UTC datetime(6)
nearest-microsecond canonicalization
500 ns boundary carries forward
```

Example:

```text
2026-08-10T05:06:07.999999500Z
-> 2026-08-10T05:06:08.000000Z
```

P3-F5 does not reuse the P3-F3 mutable-draft microsecond flooring rule.

## Revision chain and serialization contract

The existing revision lifecycle remains:

```text
ApprovalFormRuntimeService.planRevision(...)
ApprovalFormRuntimeService.saveRevision(...)
```

`saveRevision` performs:

1. `lockInstance(tenantId, instanceId)`;
2. re-read `findLatestRevision`;
3. require `currentRevision + 1 == plannedRevisionNumber`;
4. bind approved attachments;
5. strict-insert the immutable revision.

PostgreSQL retains its transaction-scoped advisory lock.

MySQL reuses the accepted `JdbcMySqlTransactionLockManager` with the exact logical namespace:

```text
form-revision:<tenantId>:<instanceId>
```

The lock:

- requires an active synchronized local transaction;
- serializes one tenant/instance revision chain;
- remains held through commit or rollback;
- is released after transaction completion;
- has no automatic retry;
- does not move database branching into `ApprovalFormRuntimeService`.

Revision persistence remains a strict insert into `ap_form_submission_revision`. The existing database uniqueness constraint remains authoritative for duplicate revision numbers.

Latest revision remains deterministic:

```text
where tenant_id = :tenantId and instance_id = :instanceId
order by revision_number desc
limit 1
```

## Transaction and rollback contract

Both initial submissions and revision inserts participate in the caller-owned Spring JDBC transaction boundary.

For revisions specifically, the permanent MySQL suite proves:

- a transaction that acquires the MySQL instance lock, inserts a revision, and then rolls back leaves no revision row;
- the named lock releases on rollback;
- a later transaction can acquire the same instance lock and successfully save the same next revision number.

For concurrent next-revision attempts planned from the same previous revision, exactly one transaction becomes the next revision owner. The loser observes the advanced latest revision after acquiring the lock and reports conflict instead of overwriting or appending a second row with the same revision number.

## Tenant and relational contract

All reads and lock scopes retain tenant identity. The MySQL baseline remains `utf8mb4_0900_as_cs`, so tenant identity is case-sensitive.

The existing MySQL clean baseline foreign keys and uniqueness constraints remain enabled. P3-F5 does not use `FOREIGN_KEY_CHECKS`, constraint drops, schema mutation or test-only production bypasses.

UUID values use `JdbcDatabaseValueAdapter`, so MySQL stores canonical textual UUID values while the application receives exact `UUID` domain values.

## Permanent acceptance matrix

The bounded permanent suites are:

```text
JdbcApprovalFormSubmissionStoreFactoryTest
JdbcApprovalFormSubmissionStoreMySqlContractTest
JdbcApprovalFormSubmissionStoreMySqlIntegrationTest
```

The real integration suite uses MySQL 8.4 Testcontainers with:

```text
InnoDB
utf8mb4_0900_as_cs
UTC session time
READ COMMITTED
strict SQL mode
datetime(6)
useAffectedRows=false
```

Accepted implementation-Head results are:

```text
JdbcApprovalFormSubmissionStoreFactoryTest:
  2 / 0 failures / 0 errors / 0 skipped, 1.573 s

JdbcApprovalFormSubmissionStoreMySqlContractTest:
  3 / 0 failures / 0 errors / 0 skipped, 0.014 s

JdbcApprovalFormSubmissionStoreMySqlIntegrationTest:
  5 / 0 failures / 0 errors / 0 skipped, 37.081 s
```

The five real MySQL methods prove:

- trusted factory-selected real MySQL store usage;
- strict immutable initial submission insert;
- exact `findByInstance` and `findByBusinessKey` reads;
- stable `FormSubmissionHasher` across persisted/reconstructed Map JSON values;
- nested values/start-parameters and Unicode round-trip;
- immutable submitted timestamp 500 ns carry canonicalization;
- tenant and tenant-case isolation;
- same business key independence across tenants;
- strict duplicate initial submission rejection;
- strict append-only revisions and deterministic latest revision;
- strict duplicate revision rejection;
- active-transaction requirement for revision locking;
- concurrent instance-lock blocking;
- exactly one concurrent next-revision winner;
- revision rollback removes a real insert and releases the lock;
- a later same-number revision commits successfully after that rollback;
- malformed or extended Submission and Revision JSON envelopes fail closed.

Existing PostgreSQL submission/runtime suites remain mandatory regression evidence and are not replaced by the MySQL suite.

## Accepted implementation Run #1390

Natural Pull Request validation:

```text
Run: 31371065700 / #1390
Head: 2e3ed357f380d37f69081b111349397bee1e9661
Conclusion: success
```

All nine physical jobs succeeded:

```text
Java 21 / Maven core                         93399939559  success
Persistence JDBC / shard 3                 93399939607  success
Persistence JDBC / shard 0                 93399939612  success
Persistence JDBC / shard 2                 93399939685  success
Persistence JDBC / shard 1                 93399939692  success
Repository hygiene                         93399939750  success
UniApp TypeScript / H5 / WeChat            93399939771  success
Vben TypeScript / production build         93399939849  success
Java 21 / Maven / PostgreSQL                93400609131  success
```

No failed P3-F5 Head, correction Run, same-Head rerun, force push, rebase or empty commit occurred before this acceptance.

## Independent implementation-Head evidence reconstruction

The final merged Maven Artifact was independently downloaded and reconstructed from its selected-test manifests and Surefire XML reports:

```text
Maven Core:                              1469 / 0 / 0 / 0
Persistence JDBC:                         474 / 0 / 0 / 0
Combined:                                1943 / 0 / 0 / 0
selected persistence test classes:        115
Surefire report classes:                  114
expected abstract without report:           1
duplicate selections:                       0
non-abstract selected without report:       0
selection coverage:                     exact
aggregate persistence test time:       953.953 s
```

Deterministic P3-F5 placement was verified exactly once:

```text
shard 0 -> JdbcApprovalFormSubmissionStoreMySqlContractTest
shard 2 -> JdbcApprovalFormSubmissionStoreFactoryTest
shard 3 -> JdbcApprovalFormSubmissionStoreMySqlIntegrationTest
```

## Independently verified implementation-Head Artifacts

All four final Run #1390 ZIPs were independently downloaded. Local byte count and SHA-256 match GitHub metadata exactly, and every ZIP passes integrity verification.

```text
Maven
ID:      9056162512
Bytes:   1033263
SHA-256: be8eff1814685372224f150fef99a0445ae2f1f6eed34bc55a69d8bf7739ceaa

Vben
ID:      9056107576
Bytes:   18830
SHA-256: 98ae982e7327975ee06f9e6ce6b3465dfc7a01a8b94ac3eb9ee3b3106141432b

Mobile
ID:      9056092845
Bytes:   9833
SHA-256: f7f78fb81f37d758bfd31949475883edb62e763ecb823b977dad19a99afdc253

Hygiene
ID:      9056065153
Bytes:   17542
SHA-256: d2ef5ed05a20867062d7395a2f83a38176cdc8bfa3cef15a973711978bdaf4c3
```

Each Artifact is bound by GitHub metadata to branch `agent/mysql-8-4-production-compatibility` and Head `2e3ed357f380d37f69081b111349397bee1e9661`.

## Explicit non-scope

P3-F5 does not implement or imply MySQL compatibility for:

- Approval Release Lifecycle;
- effective-release switching or runtime activation;
- Flowable MySQL execution;
- complete dual-database permanent CI;
- historical upgrade/restore rehearsal;
- backup/restore or production rollback runbooks;
- MySQL production authorization;
- PR Ready transition;
- merge of PR #92;
- closure of Issue #91.

It does not change `FormSubmissionHasher`, PostgreSQL Submission Store semantics, PostgreSQL migrations, form validation rules, task authorization or workflow-engine behavior.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_F5_FORM_SUBMISSION_STORE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
