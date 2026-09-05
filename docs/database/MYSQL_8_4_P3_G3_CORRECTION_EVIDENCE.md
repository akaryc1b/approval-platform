# MySQL 8.4 P3-G3 Runtime Binding Correction Evidence

## Natural failed implementation run

```text
Run: 31457454239 / #1406
Head: 720b9f6a65ffcaf2d4e27729efd5ec1a108a8dce
Conclusion: failure
Classification: TEST_FIXTURE_BUG / MISSING_PROCESS_RELEASE_LIFECYCLE_FK_PROVENANCE
```

The failed Head is retained permanently. It was not rerun in place and no empty commit, rebase or force push was used.

## What succeeded on the failed Head

The following physical Jobs completed successfully:

```text
Java 21 / Maven core
Vben TypeScript / production build
UniApp TypeScript / H5 / WeChat
Repository hygiene
Persistence JDBC / shard 0
Persistence JDBC / shard 1
Persistence JDBC / shard 3
```

`Persistence JDBC / shard 2` was the only persistence failure. The final PostgreSQL evidence aggregate failed only because one Maven evidence shard was not successful.

The new trusted factory already passed naturally:

```text
JdbcApprovalRuntimeBindingStoreFactoryTest:
2 / 0 / 0 / 0
```

## Exact failure

Shard 2 selected the new real-MySQL Runtime Binding suite. Four methods attempted to persist immutable Runtime Binding evidence and were rejected by the real MySQL relational baseline before any product-specific assertion could complete.

MySQL reported:

```text
Cannot add or update a child row
constraint: fk_process_runtime_binding_lifecycle
child: ap_process_runtime_binding
identity: tenant_id + definition_key + release_version
parent: ap_process_release_lifecycle
```

Affected paths were:

```text
storeRoundTripsUuidTimeTenantReleaseCountAndDeterministicPagination
immutableDuplicateUpdateDeleteAndForeignKeyBoundariesFailClosed
exactStartCommitsProjectionBindingAuditAndIdempotencyOnce
delegateAuditFailureAfterBindingInsertRollsBackAllPlatformEvidence
```

The rollback method expected the intentionally injected delegate-audit failure, but the incomplete test provenance graph caused the Runtime Binding insert to fail first with `DataIntegrityViolationException`.

The fail-closed projection-read method did not require a Runtime Binding insert and was not the source of the shard failure.

## Why this is a fixture bug, not a production bug

The G3 fixture already created:

```text
immutable Release Package
DEPLOYED Release Deployment
projection definition / instance where required
Effective Release for governed start paths
```

but omitted the required Process Release lifecycle row.

The production schema correctly refused a Runtime Binding that referenced a release version with no lifecycle authority. This is positive evidence that the governed MySQL V50 foreign key remained enabled and was not bypassed.

The production MySQL Runtime Binding Store performed the intended strict insert. No evidence shows an incorrect production SQL dialect, UUID conversion, timestamp conversion, immutable admission rule or transaction implementation.

## Fixture-only correction

Correction Head adds the missing real lifecycle provenance through the already accepted G2 authority:

```text
JdbcApprovalProcessReleaseStoreFactory
ApprovalProcessRelease.published(...)
PUBLISHED -> ACTIVE transition
```

For every G3 tenant fixture, the graph now contains:

```text
Release Package
  -> Process Release lifecycle: ACTIVE
  -> Release Deployment: DEPLOYED
  -> projection definition / instance when needed
  -> Runtime Binding
```

The direct Runtime Binding fixture also binds the exact engine deployment/definition/version returned by the seeded deployment instead of inventing independent engine evidence.

No foreign key, trigger, CHECK constraint or immutable rule is disabled or weakened.

## Production scope unchanged

Correction-1 changes no production code. It does not modify:

```text
JdbcMySqlApprovalRuntimeBindingStore
JdbcApprovalRuntimeBindingStoreFactory
server wiring
PostgreSQL Runtime Binding implementation
application/domain services
Flyway histories
```

It does not introduce:

```text
FOREIGN_KEY_CHECKS
INSERT IGNORE
REPLACE
ON DUPLICATE KEY UPDATE
retry-on-failure
same-Head workflow rerun
empty commit workflow trigger
force push
```

## Status after correction staging

```text
MYSQL_P3_G3_RUNTIME_BINDING_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

This evidence does not mark P3-G3 proven. A new natural PR Head must pass before acceptance can advance.
