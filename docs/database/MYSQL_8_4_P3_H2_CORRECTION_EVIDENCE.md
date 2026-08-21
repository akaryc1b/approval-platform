# MySQL 8.4 P3-H2 — Correction Evidence

## Status

```text
MYSQL_P3_H2_MIGRATION_ATTEMPT_PROVISIONING_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

This record permanently retains the first natural P3-H2 failure and its forward-only fixture correction. It does not mark H2 proven and does not authorize Ready, merge, Issue closure, deployment, production migration execution or MySQL production support.

## Failed implementation Head

```text
Head:       45523a22319529eedd40fd2cfa6861ebfa1249eb
Run:        31472425741 / #1421
Conclusion: failure
```

Run #1421 was a natural Pull Request workflow run. The Head is not rerun. No empty commit, amend, rebase, force push, workflow rerun or history rewrite is used.

## Physical-job result

At natural completion:

```text
Java 21 / Maven core:              success
Persistence JDBC / shard 0:        success
Persistence JDBC / shard 1:        success
Persistence JDBC / shard 2:        failure
Persistence JDBC / shard 3:        success
Repository hygiene:                success
Vben TypeScript / production build success
UniApp TypeScript / H5 / WeChat:   success
Java 21 / Maven / PostgreSQL:      aggregate inherited persistence failure
```

The H2 factory/static contracts compiled and executed far enough for the deterministic persistence matrix to select all three H2 suites in shard 2. The failure occurred in the H2 real-MySQL fixture before `JdbcMySqlApprovalMigrationAttemptProvisioningStore` was invoked.

## Exact failure

All five H2 real-MySQL methods failed while seeding the source `ApprovalRuntimeBinding`:

```text
fk_process_runtime_binding_lifecycle
```

The fixture had created the source Release Package and source Deployment, but had not created the corresponding source `ap_process_release_lifecycle` authority required by the accepted Runtime Binding foreign-key graph.

The failure therefore occurred before Attempt provisioning behavior, replay, concurrency, drift rejection or rollback semantics could execute.

Classification:

```text
TEST_FIXTURE_BUG / MISSING_RUNTIME_BINDING_RELEASE_LIFECYCLE_PROVENANCE
```

This is not classified as an H2 production-store semantic failure.

## Forward correction

Correction branch:

```text
agent/mysql-8-4-p3-h2-migration-attempt-provisioning-correction-1
```

The correction adds test-only source Release Lifecycle provenance using the already accepted MySQL release lifecycle factory and the same semantic sequence used by H1:

```text
source Release Package
-> DRAFT -> PUBLISHED
-> PUBLISHED -> ACTIVE
-> source Runtime Binding
```

The publish transition is bound to the Release Package's own canonical `publishedAt`; activation is one second later. The correction does not weaken or bypass `fk_process_runtime_binding_lifecycle`.

No `FOREIGN_KEY_CHECKS`, direct schema mutation, fake FK row, production shortcut or PostgreSQL modification is permitted.

## Production-code boundary

Relative to failed Head `45523a22319529eedd40fd2cfa6861ebfa1249eb`, correction-1 must have zero production-code changes.

Allowed net files are limited to:

```text
server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationAttemptProvisioningStoreMySqlIntegrationTest.java
server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/MySqlH2MigrationAttemptProvisioningFixture.java
docs/database/MYSQL_8_4_P3_H2_CORRECTION_EVIDENCE.md
```

Before formal promotion, compare must prove:

```text
behind = 0
production changes = 0
```

The integration-test net change must be limited to invoking the new exact lifecycle fixture before source Runtime Binding persistence; the helper and evidence document are additive test/evidence files.

## Next gate

Only after the correction compare passes may the formal branch be non-force fast-forwarded to the new correction Head. The next validation must be a new natural Pull Request Run on that new Head.

Run #1421 remains permanently visible and is not rerun.

If the new natural Run fails, its Head and failure are retained and corrected by another forward Head. If it succeeds, H2 still remains `STAGED` until independent test reconstruction, final Artifact verification, docs-only acceptance, `STAGED -> PROVEN`, and a final current-Head natural validation all pass.
