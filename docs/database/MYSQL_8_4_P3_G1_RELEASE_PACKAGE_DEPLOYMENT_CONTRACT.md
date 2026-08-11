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
MYSQL_P3_G1_RELEASE_PACKAGE_DEPLOYMENT_STAGED
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

P3-G1 must prove that transaction-bound MySQL named locks and JDBC writes participate in the caller-owned Spring transaction.

For Release Package locking:

- a competing transaction cannot acquire the same exact release-version lock before the owner completes;
- rollback releases the lock.

For Deployment:

- a transaction may acquire the exact deployment lock and insert a PENDING projection;
- if that transaction rolls back, no deployment row may remain;
- the lock must release on rollback;
- a subsequent transaction must acquire the same lock and successfully persist the projection.

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

It must prove at minimum:

- trusted factory selection for both stores;
- Release Package exact read/latest/read-by-draft/listing;
- strict immutable Release Package insert and duplicate rejection;
- Release Package UUID round-trip;
- Release Package immutable timestamp canonicalization;
- Release Package tenant/case isolation;
- Release Package transaction-lock blocking and rollback release;
- Deployment strict insert;
- Deployment PENDING -> FAILED -> retry PENDING -> DEPLOYED CAS transitions;
- stale attempt-count rejection;
- Deployment UUID and timestamp round-trip;
- Deployment tenant isolation and deterministic find-by-definition ordering;
- Deployment transaction-lock blocking;
- Deployment insert rollback and lock release;
- PostgreSQL implementations and existing PostgreSQL suites remain unchanged.

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

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_F5_FORM_SUBMISSION_STORE_PROVEN
MYSQL_P3_G1_RELEASE_PACKAGE_DEPLOYMENT_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
