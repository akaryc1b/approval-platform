# MySQL 8.4 P3-F4 Form Package Store Contract

## Baseline identity

```text
source accepted capability: MYSQL_P3_F3_FORM_DESIGN_DRAFT_STORE_PROVEN
source formal Head: 3b455b789cb0699232e201ee11b3b6c6b19e7bee
implementation branch: agent/mysql-8-4-p3-f4-form-package-store-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

P3-F4 converts only the existing immutable `ApprovalFormPackageStore` persistence authority for MySQL 8.4. It does not redesign the Form Package lifecycle and does not expand into submission, release activation or runtime execution.

```text
MYSQL_P3_F4_FORM_PACKAGE_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

## Exact production scope

P3-F4 adds:

```text
JdbcApprovalFormPackageStoreFactory
JdbcMySqlApprovalFormPackageStore
ApprovalFormConfiguration -> trusted factory binding
```

The existing application and domain contracts remain unchanged:

```text
ApprovalFormPackageStore
ApprovalFormDesignService
FormPackage
FormPackageHasher
```

The PostgreSQL implementation `JdbcApprovalFormPackageStore` remains unchanged. PostgreSQL Flyway history remains immutable.

```text
POSTGRESQL_FORM_PACKAGE_STORE_UNCHANGED
APPLICATION_FORM_DESIGN_SERVICE_DATABASE_NEUTRAL
```

## Trusted implementation selection

`JdbcApprovalFormPackageStoreFactory` derives the database implementation only from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`.

```text
PostgreSQL 16 -> JdbcApprovalFormPackageStore
MySQL 8.4    -> JdbcMySqlApprovalFormPackageStore
```

No request field, tenant payload, browser input, profile string or user-controlled value can select the package-store dialect.

## Immutable package identity

The existing package identity remains:

```text
tenant_id
form_key
package_version
```

A Package binds one exact immutable Form Schema and UI Schema pair:

```text
form_version + form_hash
ui_schema_version + ui_schema_hash
package_hash
source_draft_id
published_by
published_at
```

`FormPackageHasher` remains the sole application-level package hash authority. P3-F4 does not introduce a MySQL-specific package hash or database-generated replacement.

The package hash remains derived from the existing `form-package-v1` contract over:

```text
formKey
packageVersion
formVersion
formHash
uiSchemaVersion
uiSchemaHash
```

## Version serialization contract

PostgreSQL retains its existing transaction-scoped advisory lock. MySQL reuses the already accepted transaction-bound `JdbcMySqlTransactionLockManager` for the exact logical scope:

```text
form-package:<tenantId>:<formKey>:<packageVersion>
```

The MySQL lock:

- requires an active synchronized local transaction;
- serializes the exact package-version namespace;
- remains held until transaction completion;
- is released after commit or rollback;
- does not perform automatic retry;
- does not move dialect selection into `ApprovalFormDesignService`.

P3-F4 does not add a second lock namespace or replace the package version lock with a row lock on a row that may not exist yet.

## Read and tenant contract

Exact package lookup requires:

```text
tenant_id = :tenantId
form_key = :formKey
package_version = :packageVersion
```

Published-draft replay lookup requires:

```text
tenant_id = :tenantId
source_draft_id = :draftId
```

The accepted MySQL baseline remains `utf8mb4_0900_as_cs`, so tenant identity remains case-sensitive. A Package from one tenant must never be visible through another tenant identity, including case variants.

UUID values use the governed `JdbcDatabaseValueAdapter`; MySQL binds `source_draft_id` as its canonical textual UUID representation and decodes it back to the exact `UUID` domain value.

## Publication time contract

`published_at` is immutable publication evidence and follows the already accepted immutable F1/F2 MySQL timestamp rule:

```text
UTC datetime(6)
nearest-microsecond canonicalization
500 ns boundary carries forward
```

P3-F4 deliberately does not reuse P3-F3 mutable-draft timestamp flooring. A value such as:

```text
2026-08-10T05:06:07.999999500Z
```

is canonicalized to:

```text
2026-08-10T05:06:08.000000Z
```

before MySQL binding, matching the existing immutable evidence canonicalization contract.

## Strict immutable admission

Package persistence remains a strict insert into `ap_form_package`.

P3-F4 does not use:

```text
INSERT IGNORE
REPLACE INTO
ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS
silent overwrite
automatic retry
package evidence repair
```

A duplicate package identity therefore fails rather than mutating existing immutable evidence.

## Atomic publish transaction contract

`ApprovalFormDesignService.publish` remains database-neutral and retains the existing sequence:

1. lock the draft row;
2. validate draft revision and content;
3. calculate Form/UI hashes and `FormPackageHasher` result;
4. acquire `form-package:<tenant>:<formKey>:<packageVersion>`;
5. reject an occupied package version;
6. publish immutable Form Definition if absent and identical;
7. publish immutable UI Schema if absent and identical;
8. insert the immutable Form Package;
9. transition the draft to `PUBLISHED` by CAS;
10. append `FORM_PACKAGE_PUBLISHED` audit evidence;
11. commit through the existing idempotency transaction boundary.

P3-F4 must prove that the real MySQL Package insert participates in this same transaction. If a later draft transition fails after the Package insert, the surrounding rollback must remove the newly inserted Form Definition, UI Schema, Form Package and publish audit while retaining the previously validated draft state.

## Replay and conflict contract

A published draft is replayed through:

```text
ApprovalFormPackageStore.findByDraft(tenantId, draftId)
```

The replay must return the exact immutable Package already bound to that draft. A request for a different package version after publication fails with the existing `PackageVersionConflictException` behavior.

If another draft attempts to publish an already occupied package version:

- different package content must fail;
- the same hash from another draft must still fail because the package version already belongs to another draft;
- the existing Package must remain unchanged;
- another tenant may independently use the same form key and package version.

## Permanent acceptance matrix

The bounded permanent suites are:

```text
JdbcApprovalFormPackageStoreFactoryTest
JdbcApprovalFormPackageStoreMySqlContractTest
JdbcApprovalFormPackageStoreMySqlIntegrationTest
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
- real service publication through MySQL Draft/Form/UI/Package/Audit/Idempotency stores;
- exact Package round-trip and `findByDraft` replay;
- stable `FormPackageHasher` from persisted evidence;
- persisted Form/UI hashes equal the Package-bound hashes;
- immutable publication time canonicalization at the 500 ns carry boundary;
- case-sensitive tenant isolation;
- strict duplicate insert rejection;
- active-transaction requirement for package version locking;
- concurrent package-version lock blocking;
- lock release after rollback;
- same package version / different content rejection;
- same form key and package version allowed independently across tenants;
- rollback after a real Package insert restores Form/UI/Package/Audit and leaves the draft validated.

Existing PostgreSQL `JdbcApprovalFormDesignIntegrationTest` remains a mandatory regression and is not replaced by the MySQL suite.

## Explicit non-scope

P3-F4 does not implement or imply MySQL compatibility for:

- P3-F5 Form Submission Store;
- Approval Release Lifecycle;
- effective release activation or runtime binding;
- Flowable MySQL execution;
- complete dual-database CI;
- historical upgrade, backup or restore;
- MySQL production authorization;
- PR Ready transition;
- merge of PR #92;
- closure of Issue #91.

It also does not modify the existing Form Package hash contract, PostgreSQL Package Store, PostgreSQL migrations, or application-layer publish decisions.

```text
MYSQL_P3_F4_FORM_PACKAGE_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
