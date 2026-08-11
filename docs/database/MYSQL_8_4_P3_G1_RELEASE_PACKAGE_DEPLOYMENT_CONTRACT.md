# MySQL 8.4 P3-G1 Approval Release Package and Deployment Contract

## Baseline identity

```text
source accepted capability: MYSQL_P3_F5_FORM_SUBMISSION_STORE_PROVEN
source formal Head: d942e2fd531066cadbf7687d70177ce40fc79369
implementation branch: agent/mysql-8-4-p3-g1-release-package-deployment-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

P3-G1 converts only the existing immutable `ApprovalReleasePackageStore` and mutable `ApprovalReleaseDeploymentStore` persistence authorities for MySQL 8.4.

```text
MYSQL_P3_G1_RELEASE_PACKAGE_DEPLOYMENT_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

## Exact production scope

P3-G1 adds:

```text
JdbcApprovalReleasePackageStoreFactory
JdbcMySqlApprovalReleasePackageStore
JdbcApprovalReleaseDeploymentStoreFactory
JdbcMySqlApprovalReleaseDeploymentStore
ApprovalDesignConfiguration -> trusted Release Package factory binding
ApprovalReleaseDeploymentConfiguration -> trusted Deployment factory binding
```

The following application/domain contracts remain unchanged and database-neutral:

```text
ApprovalReleasePackageStore
ApprovalReleaseDeploymentStore
ApprovalDesignService
ApprovalReleasePreflightService
ApprovalReleaseDeploymentService
ApprovalReleasePackage
ApprovalReleaseDeployment
ApprovalReleasePackageHasher
```

The existing PostgreSQL implementations remain unchanged:

```text
JdbcApprovalReleasePackageStore
JdbcApprovalReleaseDeploymentStore
```

PostgreSQL Flyway history remains immutable.

## Why Package and Deployment are one bounded foundation

The existing deployment service acquires both authorities in one governed sequence:

```text
ApprovalReleaseDeploymentService.deployOnce(...)
  -> ApprovalReleasePackageStore.lockVersion(...)
  -> ApprovalReleasePackageStore.find(...)
  -> ApprovalReleaseDeploymentStore.lock(...)
  -> ApprovalReleaseDeploymentStore.find(...)
  -> preflight verification
  -> save/update deployment projection
  -> engine deployment
  -> CAS update to DEPLOYED or FAILED
```

Therefore MySQL deployment compatibility requires both stores to preserve independent transaction-scoped serialization domains. P3-G1 does not claim the entire release publication or activation lifecycle is MySQL-compatible.

## Trusted implementation selection

Both factories derive the database implementation only from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`.

```text
PostgreSQL 16 -> existing PostgreSQL stores
MySQL 8.4    -> MySQL stores
```

No browser field, request payload, tenant data, profile string, workflow payload or other user-controlled value can select the persistence dialect.

## Immutable Approval Release Package contract

`ApprovalReleasePackageStore` retains the exact immutable identity:

```text
tenant_id
definition_key
release_version
```

and immutable evidence binding:

```text
definition_version + definition_hash
form_package_version + form_package_hash
form_version + form_hash
ui_schema_version + ui_schema_hash
compiler_version
bpmn_resource_name + bpmn_artifact + bpmn_hash
optional dmn_artifact + dmn_hash
deployment_metadata_hash
package_hash
source_draft_id
published_by
published_at
```

MySQL uses strict insert semantics into `ap_approval_release_package`.

P3-G1 does not use:

```text
INSERT IGNORE
REPLACE
ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS
```

The existing schema uniqueness, hashes and foreign keys remain authoritative.

## Release Package serialization lock

PostgreSQL retains its transaction-scoped advisory lock.

MySQL reuses `JdbcMySqlTransactionLockManager` with the exact logical namespace:

```text
approval-release:<tenantId>:<definitionKey>:<releaseVersion>
```

The lock:

- requires an active synchronized local transaction;
- serializes publication/deployment ownership for one exact release version;
- remains held through commit or rollback;
- releases after transaction completion;
- has no automatic retry;
- does not move database branching into application services.

## Release Package reads and listing

Exact reads remain:

```text
find -> tenant_id + definition_key + release_version
findLatest -> tenant_id + definition_key, release_version DESC LIMIT 1
findByDraft -> tenant_id + source_draft_id
```

Listing remains tenant-scoped and deterministic:

```text
optional definition_key filter
order by definition_key, release_version desc
limit + offset
```

Tenant identity remains case-sensitive under the governed MySQL baseline collation `utf8mb4_0900_as_cs`.

UUID values use `JdbcDatabaseValueAdapter`, so MySQL persists canonical textual UUID values while the application receives exact `UUID` values.

## Release Package immutable time contract

`published_at` is immutable release evidence and follows the accepted immutable MySQL timestamp rule:

```text
UTC datetime(6)
nearest-microsecond canonicalization
500 ns boundary carries forward
```

P3-G1 does not use the P3-F3 mutable draft flooring rule for release publication evidence.

## Deployment projection identity and state

`ApprovalReleaseDeploymentStore` retains one deployment projection for:

```text
tenant_id
definition_key
release_version
```

The projection binds the immutable Release Package hash and owns:

```text
deployment_record_id
release_package_hash
status
attempt_count
engine_deployment_id
engine_definition_id
engine_version
last_error_code
last_error_message
requested_by
created_at
updated_at
deployed_at
```

Domain state remains unchanged:

```text
PENDING
DEPLOYED
FAILED
```

Only `DEPLOYED` may contain complete engine identity and `deployed_at`. Only `FAILED` may contain complete failure evidence. P3-G1 does not weaken those domain invariants.

## Deployment serialization lock

MySQL uses a separate transaction-scoped lock namespace:

```text
approval-release-deployment:<tenantId>:<definitionKey>:<releaseVersion>
```

This lock is intentionally distinct from the immutable Release Package lock.

`ApprovalReleaseDeploymentService` retains the existing order:

```text
Release Package lock
then Deployment lock
```

No new automatic retry or lock-order inversion is introduced.

## Deployment CAS contract

Initial deployment persistence remains a strict insert into `ap_approval_release_deployment`.

Updates retain the existing compare-and-set predicate:

```text
tenant_id = :tenantId
definition_key = :definitionKey
release_version = :releaseVersion
attempt_count = :expectedAttemptCount
```

A stale attempt count returns `false`. Exactly one matching row may update. An impossible multi-row update fails closed.

The application service therefore retains:

```text
first attempt -> PENDING attempt 1
failure        -> FAILED attempt 1
retry          -> PENDING attempt 2
success        -> DEPLOYED attempt 2
```

without database-specific branching.

## Deployment time contract

`created_at`, `updated_at` and optional `deployed_at` use the shared governed MySQL UTC `datetime(6)` adapter and nearest-microsecond canonicalization used by other immutable/operational evidence stores.

The domain ordering invariant remains:

```text
updated_at >= created_at
```

## Transaction and rollback contract

P3-G1 proves that transaction-bound MySQL named locks and JDBC writes participate in the caller-owned Spring transaction.

For Release Package locking:

- a competing transaction cannot acquire the same exact release-version lock before the owner completes;
- rollback releases the lock.

For Deployment:

- a transaction may acquire the exact deployment lock and insert a PENDING projection;
- if that transaction rolls back, no deployment row remains;
- the lock releases on rollback;
- a subsequent transaction acquires the same lock and successfully persists the projection.

The real MySQL acceptance suite also acquires the Release Package and Deployment namespaces in the same transaction, preserving the application service's established lock order.

## Permanent acceptance matrix

The bounded permanent suites are:

```text
JdbcApprovalReleaseFoundationStoreFactoryTest
JdbcApprovalReleaseFoundationMySqlContractTest
JdbcApprovalReleaseFoundationMySqlIntegrationTest
```

The real integration suite uses MySQL 8.4 Testcontainers with the accepted platform settings:

```text
InnoDB
utf8mb4_0900_as_cs
UTC session time
READ COMMITTED
strict SQL mode
datetime(6)
useAffectedRows=false
```

Accepted implementation/current capability Head:

```text
de811f6a5739173932fe7834285884f30fef145a
```

Natural implementation validation:

```text
Run: 31450800678 / #1397
Conclusion: success
```

Exact P3-G1 suite results reconstructed from final Surefire XML:

```text
JdbcApprovalReleaseFoundationStoreFactoryTest:
  tests 2 / failures 0 / errors 0 / skipped 0 / time 0.006 s

JdbcApprovalReleaseFoundationMySqlContractTest:
  tests 3 / failures 0 / errors 0 / skipped 0 / time 0.005 s

JdbcApprovalReleaseFoundationMySqlIntegrationTest:
  tests 5 / failures 0 / errors 0 / skipped 0 / time 16.475 s
```

Deterministic selection is exact and non-overlapping:

```text
shard 2 -> JdbcApprovalReleaseFoundationStoreFactoryTest
shard 2 -> JdbcApprovalReleaseFoundationMySqlIntegrationTest
shard 3 -> JdbcApprovalReleaseFoundationMySqlContractTest
```

The real five-method integration suite proves:

- trusted MySQL Store selection;
- Release Package exact read, latest read, read-by-draft and deterministic listing;
- strict immutable Package insert and duplicate rejection;
- Package UUID round-trip;
- Package immutable timestamp 500 ns carry canonicalization;
- Package tenant and case isolation;
- Package transaction-lock blocking and rollback release;
- Deployment strict insert;
- Deployment PENDING -> FAILED -> retry PENDING -> DEPLOYED CAS transitions;
- stale attempt-count rejection;
- Deployment UUID and timestamp round-trip;
- Deployment tenant isolation and deterministic definition ordering;
- Deployment real foreign-key enforcement;
- Deployment transaction-lock blocking;
- a real Deployment insert is removed by rollback;
- the Deployment lock releases on rollback;
- Package and Deployment lock namespaces remain distinct and composable in the established order.

## Run #1397 full permanent validation

All nine physical Jobs succeeded:

| Job | ID | Result |
| --- | ---: | --- |
| Vben TypeScript / production build | `93654587570` | success |
| Java 21 / Maven core | `93654587616` | success |
| UniApp TypeScript / H5 / WeChat | `93654587643` | success |
| Persistence JDBC / shard 2 | `93654587654` | success |
| Persistence JDBC / shard 1 | `93654587658` | success |
| Repository hygiene | `93654587664` | success |
| Persistence JDBC / shard 3 | `93654587666` | success |
| Persistence JDBC / shard 0 | `93654587682` | success |
| Java 21 / Maven / PostgreSQL | `93655023953` | success |

Independent Maven evidence reconstruction:

```text
Maven Core:                           1469 / 0 / 0 / 0
Persistence JDBC:                      484 / 0 / 0 / 0
Combined:                             1953 / 0 / 0 / 0
selected persistence test classes:     118
Surefire report classes:               117
expected abstract without report:        1
duplicate selections:                    0
non-abstract selected without report:    0
selection coverage:                  exact
aggregate reported persistence time: 887.041 s
```

Deterministic persistence shard distribution:

```text
shard 0: 29
shard 1: 30
shard 2: 26
shard 3: 33
unique: 118 / 118
```

## Independently verified Run #1397 Artifacts

All final Run #1397 ZIP artifacts were independently downloaded. Local byte counts and SHA-256 digests exactly match GitHub metadata and every archive passes ZIP integrity verification.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9086302284` | `1045799` | `13004d33432177afb86f1703bd23235c9185a72549f96fa052a604fdb5548709` |
| Vben | `9086276727` | `18818` | `6d47a03bc4548d22c54d9f806e61c89da2066d1d5c2426bec76dfe5c7cfb9141` |
| Mobile | `9086265859` | `9809` | `ffabc086ad781411feffb18c8a5360223b2e48b55061cd50305a6eddf3b8bdbd` |
| Hygiene | `9086251775` | `17510` | `b6885b6ab81edd86304379072edc71166362ace9265e48c8c258f54fdff5ec76` |

Artifact expiry is `2026-11-09T01:55:47Z`.

## Retained append-only correction trail

No failed Head was rerun in place. No force push, rebase, squash or empty CI-trigger commit was used.

### Run #1394 — static hygiene failure

```text
Run: 31450308077 / #1394
Head: 9fce72a4cfd6fc9d4e2ffafb423a06e86fe539d9
Conclusion: failure
Classification: STATIC_HYGIENE / CHECKSTYLE_UNUSED_IMPORT
```

One unused `java.util.UUID` import in `JdbcMySqlApprovalReleaseDeploymentStore` stopped Maven/Persistence before any G1 real-MySQL method executed. Vben, Mobile and Repository Hygiene succeeded. The correction removed only that unused import. The detailed record remains in:

```text
docs/database/MYSQL_8_4_P3_G1_CORRECTION_EVIDENCE.md
```

### Run #1395 — non-distinct tenant-case assertion

```text
Run: 31450537171 / #1395
Head: 550bc531b2d7c4eab6275e758688ed8ae85c15cf
Conclusion: failure
Classification: TEST_ASSERTION_BUG / NON_DISTINCT_TENANT_CASE_VALUE
```

The G1 real-MySQL suite executed all five methods with four passing and one assertion-only failure. The failed test called `OTHER_TENANT.toLowerCase()` while the shared fixture already defined `OTHER_TENANT` entirely in lowercase, so the supposed negative tenant was identical to the positive tenant. The correction changed only the test input to a genuinely distinct uppercase case variant. No production source changed. The detailed record remains in:

```text
docs/database/MYSQL_8_4_P3_G1_ASSERTION_CORRECTION_EVIDENCE.md
```

### Run #1397 — accepted implementation evidence

```text
Run: 31450800678 / #1397
Head: de811f6a5739173932fe7834285884f30fef145a
Conclusion: success
all nine physical Jobs: success
```

This is the accepted implementation evidence for P3-G1. The acceptance-recording commit that changes this document from `STAGED` to `PROVEN` is intentionally evidence-only; its own natural current-Head validation is bound through PR metadata after that run completes, avoiding recursive evidence commits.

## Explicit non-scope

P3-G1 does not implement or imply MySQL compatibility for:

- `ApprovalProcessReleaseStore` lifecycle state machine;
- `ApprovalEffectiveReleaseStore` current-effective projection and activation history;
- PUBLISHED -> ACTIVE -> DEPRECATED -> RETIRED lifecycle switching;
- effective-release activation or rollback;
- full `ApprovalDesignService` MySQL publication, because Approval Design Draft, Definition Version and Compiled Artifact stores remain outside this slice;
- real Flowable MySQL deployment/execution;
- complete dual-database permanent CI;
- historical upgrade/restore rehearsal;
- backup/restore or production rollback runbooks;
- MySQL production authorization;
- PR Ready transition;
- merge of PR #92;
- closure of Issue #91.

The next bounded slice is P3-G2 Process Release Lifecycle + Effective Release compatibility after P3-G1 is accepted.

## Current authorization boundary

Authorized:

- continue bounded append-only MySQL compatibility slices on Draft PR #92;
- retain natural PR Runs and final Artifacts as evidence;
- correct failures with new commits and new Heads.

Not authorized:

- mark PR #92 Ready;
- merge PR #92;
- close Issue #91;
- claim MySQL 8.4 production support;
- alter already-applied PostgreSQL migrations;
- weaken PostgreSQL behavior or existing validation;
- enter P3-G2 as part of this acceptance record.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_F5_FORM_SUBMISSION_STORE_PROVEN
MYSQL_P3_G1_RELEASE_PACKAGE_DEPLOYMENT_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
