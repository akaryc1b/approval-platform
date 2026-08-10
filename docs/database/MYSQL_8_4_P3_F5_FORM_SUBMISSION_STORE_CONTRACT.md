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
MYSQL_P3_F5_FORM_SUBMISSION_STORE_STAGED
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

This deliberately does **not** reuse the P3-F2 UI Schema Java-number type protocol. `FormSubmissionHasher` already canonicalizes all `Number` values through `BigDecimal(number.toString()).stripTrailingZeros()`, and P3-F5 must preserve cross-database application behavior rather than introduce MySQL-specific generic Java value classes.

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

P3-F5 must prove that both initial submissions and revision inserts participate in the caller-owned Spring JDBC transaction boundary.

For revisions specifically:

- a transaction that acquires the MySQL instance lock, inserts a revision, and then rolls back must leave no revision row;
- the named lock must release on rollback;
- a later transaction must be able to acquire the same instance lock and successfully save the same next revision number.

For concurrent next-revision attempts planned from the same previous revision, exactly one transaction may become the next revision owner. The loser must observe the advanced latest revision after acquiring the lock and fail explicitly rather than overwrite or append a second row with the same revision number.

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

It must prove:

- trusted factory selection;
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
- revision rollback removes the insert and releases the lock;
- malformed or extended JSON envelopes fail closed.

Existing PostgreSQL submission/runtime suites remain mandatory regression evidence and are not replaced by the MySQL suite.

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
MYSQL_P3_F5_FORM_SUBMISSION_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
