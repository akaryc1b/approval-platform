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
MYSQL_P3_F4_FORM_PACKAGE_STORE_PROVEN
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

The accepted MySQL matrix proves that the real Package insert participates in this same transaction. A deliberately failed draft transition after the real Package insert rolls back the newly inserted Form Definition, UI Schema, Form Package and publish audit while retaining the previously validated draft state.

## Replay and conflict contract

A published draft is replayed through:

```text
ApprovalFormPackageStore.findByDraft(tenantId, draftId)
```

The replay returns the exact immutable Package already bound to that draft. A request for a different package version after publication retains the existing `PackageVersionConflictException` behavior.

If another draft attempts to publish an already occupied package version:

- different package content fails;
- the same package identity is not silently overwritten;
- the existing Package remains unchanged;
- another tenant may independently use the same form key and package version.

## Permanent acceptance matrix

The bounded permanent suites are:

```text
JdbcApprovalFormPackageStoreFactoryTest
JdbcApprovalFormPackageStoreMySqlContractTest
JdbcApprovalFormPackageStoreMySqlIntegrationTest
```

The accepted real integration suite uses MySQL 8.4 Testcontainers with:

```text
InnoDB
utf8mb4_0900_as_cs
UTC session time
READ COMMITTED
strict SQL mode
datetime(6)
useAffectedRows=false
```

Accepted focused results at implementation acceptance Head `a51783487feac3bd1bf27a350ff9e93d99eb1d38`:

```text
JdbcApprovalFormPackageStoreFactoryTest
Tests run: 2
Failures: 0
Errors: 0
Skipped: 0
Time: 0.026 s
Shard: 2

JdbcApprovalFormPackageStoreMySqlContractTest
Tests run: 3
Failures: 0
Errors: 0
Skipped: 0
Time: 0.025 s
Shard: 3

JdbcApprovalFormPackageStoreMySqlIntegrationTest
Tests run: 4
Failures: 0
Errors: 0
Skipped: 0
Time: 22.797 s
Shard: 0
```

The real MySQL suite proves:

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

Existing PostgreSQL `JdbcApprovalFormDesignIntegrationTest` remains a mandatory regression and passed in the same permanent validation. It is not replaced by the MySQL suite.

## Accepted implementation Run #1388

The exact natural Pull Request implementation acceptance Run is:

```text
Run: 31368630837 / #1388
Head: a51783487feac3bd1bf27a350ff9e93d99eb1d38
Branch: agent/mysql-8-4-production-compatibility
Conclusion: success
```

All nine physical Jobs succeeded:

| Job | ID | Result |
| --- | ---: | --- |
| Repository hygiene | `93392408355` | success |
| Persistence JDBC / shard 2 | `93392408378` | success |
| Vben TypeScript / production build | `93392408419` | success |
| Persistence JDBC / shard 3 | `93392408421` | success |
| Persistence JDBC / shard 1 | `93392408424` | success |
| Java 21 / Maven core | `93392408462` | success |
| Persistence JDBC / shard 0 | `93392408465` | success |
| UniApp TypeScript / H5 / WeChat | `93392408578` | success |
| Java 21 / Maven / PostgreSQL | `93393077046` | success |

### Independent test reconstruction

The final Maven Artifact was independently downloaded and reconstructed rather than trusting only the workflow summary.

```text
Maven Core:                           1469 / 0 / 0 / 0
Persistence JDBC:                      464 / 0 / 0 / 0
Combined:                             1933 / 0 / 0 / 0

selected persistence test classes:     112
Surefire report classes:               111
expected abstract without report:        1
duplicate selections:                    0
non-abstract selected without report:    0
selection coverage:                  exact
aggregate reported persistence time: 872.048 s
```

Deterministic shard distribution:

```text
shard 0: 28
shard 1: 30
shard 2: 23
shard 3: 31
unique: 112 / 112
```

The three P3-F4 suites are selected exactly once:

```text
shard 0 -> JdbcApprovalFormPackageStoreMySqlIntegrationTest
shard 2 -> JdbcApprovalFormPackageStoreFactoryTest
shard 3 -> JdbcApprovalFormPackageStoreMySqlContractTest
```

### Independently verified implementation Artifacts

Every final #1388 ZIP was independently downloaded. Local byte size and SHA-256 exactly match GitHub Artifact metadata, and every archive passes ZIP integrity verification. Every Artifact is bound to branch `agent/mysql-8-4-production-compatibility`, Head `a51783487feac3bd1bf27a350ff9e93d99eb1d38`.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9055220819` | `1020885` | `edf17b3e4bc29d38172c1bc22d6a40db23448de94b4523bf7d9747e7a9e04899` |
| Vben | `9055182512` | `18837` | `89608c254898d33bed700534e5ef1b1d9a35d1f00be41a26882855c152febf91` |
| Mobile | `9055163992` | `9811` | `3cf695acf4248d249f6fc1276addb2e0a63f3da3c963e472fa1349d83dab7496` |
| Hygiene | `9055139493` | `17484` | `131ba973205dfd8134e034685adc137f08cf1e65e7055a15513814b975831175` |

The #1388 Artifacts expire `2026-11-08T08:06:40Z`.

## Retained P3-F4 correction trail

P3-F4 retains every natural failed Head; no failed Head was rerun in place.

| Run | Head | Classification | Retained evidence |
| --- | --- | --- | --- |
| `#1383` / `31367201065` | `99534232f90930b0eb939fe3e0d00ae218b39a7c` | `TEST_FIXTURE_BUG / REAL_FK_DELETE_ORDER` | Draft-to-Package FK blocked parent-first teardown |
| `#1384` / `31367603507` | `a3ccda157e466998415949548c10911b5f6ae274` | `TEST_FIXTURE_BUG / MUTUAL_DRAFT_PACKAGE_FK_CYCLE` | Package-to-Draft FK proved the opposite edge |
| `#1385` / `31367839212` | `20a8c7fe0774d623533187b35533df57233d394a` | `KNOWN_INHERITED_TEST_FIXTURE_BUG / MUTUAL_DRAFT_PACKAGE_FK_CYCLE` | evidence-only Head retained the known first correction failure |
| `#1387` / `31368083214` | `8eb579802ae65dc2a6458ec2a5b42d29d4acc65b` | `TEST_FIXTURE_BUG / PUBLISHED_PAIR_CHECK_INVARIANT` | MySQL CHECK rejected clearing only package version on a PUBLISHED draft |
| `#1388` / `31368630837` | `a51783487feac3bd1bf27a350ff9e93d99eb1d38` | `ACCEPTED` | cycle- and CHECK-aware teardown; all nine Jobs success |

The final test-only teardown first changes the published test row to a valid non-published pair in one statement:

```sql
update ap_form_design_draft
set status = 'VALIDATED',
    published_package_version = null
where published_package_version is not null;
```

It then removes Package before Draft. This honors both real foreign keys and the published-pair CHECK invariant without `FOREIGN_KEY_CHECKS`, schema mutation or production-state weakening.

The detailed append-only failure record remains in:

```text
docs/database/MYSQL_8_4_P3_F4_FIXTURE_CORRECTION_EVIDENCE.md
```

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

No standalone `MYSQL_8_4_PRODUCTION_SUPPORTED` status is authorized by this acceptance.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_F4_FORM_PACKAGE_STORE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
