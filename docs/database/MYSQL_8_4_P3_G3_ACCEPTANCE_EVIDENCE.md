# MySQL 8.4 P3-G3 Runtime Binding Store Acceptance Evidence

## Accepted bounded capability

P3-G3 accepts the MySQL 8.4 compatibility of the existing **initial Runtime Binding Store authority**:

```text
JdbcApprovalRuntimeBindingStoreFactory
JdbcMySqlApprovalRuntimeBindingStore
ApprovalProcessReleaseLifecycleConfiguration -> trusted factory
```

The accepted scope is the creation and read/query contract used when an approval instance is first bound to an exact governed release and deployed engine definition.

This acceptance does **not** claim that all later Runtime Binding mutation semantics are complete on MySQL. M5-D5 deliberately evolves Runtime Binding rows through a separate guarded migration CAS protocol, and that authority remains a later compatibility slice.

## Exact accepted implementation Head

```text
main: 1747b22123fd71cccd8334853ad7060c6645b443
formal branch: agent/mysql-8-4-production-compatibility
implementation Head: 62fb43630bad2c4efb5499019de8c7928e384d7f
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

## Natural implementation acceptance Run

```text
Run: 31458317787 / #1408
Head: 62fb43630bad2c4efb5499019de8c7928e384d7f
Conclusion: success
Physical Jobs: 9 / 9 success
```

Physical Job evidence:

| Job | ID | Result |
| --- | ---: | --- |
| Java 21 / Maven core | `93676593701` | success |
| Vben TypeScript / production build | `93676593702` | success |
| Repository hygiene | `93676593714` | success |
| Persistence JDBC / shard 1 | `93676593723` | success |
| UniApp TypeScript / H5 / WeChat | `93676593729` | success |
| Persistence JDBC / shard 0 | `93676593744` | success |
| Persistence JDBC / shard 3 | `93676593761` | success |
| Persistence JDBC / shard 2 | `93676593775` | success |
| Java 21 / Maven / PostgreSQL | `93677063266` | success |

## Focused P3-G3 matrix

The implementation Run selected every bounded G3 suite exactly once:

```text
shard 1:
JdbcApprovalRuntimeBindingStoreMySqlContractTest
3 / 0 / 0 / 0, 0.009 s

shard 2:
JdbcApprovalRuntimeBindingStoreFactoryTest
2 / 0 / 0 / 0, 0.005 s

shard 2:
JdbcApprovalRuntimeBindingStoreMySqlIntegrationTest
5 / 0 / 0 / 0, 37.320 s
```

The five real MySQL methods prove:

1. strict initial binding insertion with exact UUID and nearest-microsecond `bound_at` readback;
2. exact approval-instance and engine-instance reads, tenant/case isolation, release usage counting and deterministic `order by bound_at desc, approval_instance_id` pagination;
3. duplicate initial binding rejection and real approval-instance / Process Release lifecycle / Release Package / Deployment foreign-key provenance;
4. a real release-bound `PurchasePaymentApplicationService.start(...)` transaction creates projection, Runtime Binding, governed audit and idempotency evidence once, while request replay does not call the external engine twice;
5. a delegate-audit failure after the Runtime Binding insert has actually occurred rolls platform instance/task projection, Runtime Binding, audit and idempotency evidence back while the external engine start remains one-shot;
6. release-bound projection reads continue to fail closed when the initial Runtime Binding is absent.

## Independent persistence reconstruction

The final Maven artifact from #1408 was downloaded independently and its four deterministic selection files and Surefire XML reports were reconstructed locally.

```text
selected persistence test classes:      124
unique selected classes:                 124
Surefire report classes:                 123
expected abstract without report:          1
expected abstract:
  AbstractJdbcApprovalMigrationProtocolIntegrationTest

duplicate selections:                      0
non-abstract selected without report:      0
unexpected reports outside selection:      0
selection coverage:                     exact

Persistence JDBC:
508 / 0 / 0 / 0
aggregate reported persistence time:
946.799 s
```

Deterministic shard distribution:

```text
shard 0: 29
shard 1: 32
shard 2: 30
shard 3: 33
unique: 124 / 124
```

## Independent Maven Core reconstruction

`maven-core.log` was parsed independently from the same final Maven artifact rather than reusing an earlier slice's count.

```text
Maven Core:
1469 / 0 / 0 / 0

Persistence JDBC:
508 / 0 / 0 / 0

Combined:
1977 / 0 / 0 / 0
```

## Independently verified #1408 final Artifacts

Every final artifact ZIP was actually downloaded. Local byte size and local SHA-256 exactly match GitHub metadata and every ZIP passes archive integrity verification.

All are bound to:

```text
branch: agent/mysql-8-4-production-compatibility
Head: 62fb43630bad2c4efb5499019de8c7928e384d7f
Run: 31458317787 / #1408
expires: 2026-11-09T04:23:22Z
```

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9088904237` | `850885` | `f649768d947bfadbb9fe52edb4f8027ea3cf7b1fda61720202391127fd8e7ac5` |
| Vben | `9088877319` | `18959` | `a6eea0041b85d7b8a2db21976641b4cedb82869bed5b23e594cc2e6309b8fa13` |
| Mobile | `9088862519` | `9814` | `8050a346f3308858868357ba832ca0c36614677422e866d9dfce28696eccf4b2` |
| Hygiene | `9088849523` | `17486` | `41e2eae7871369867709602c7447ab104d1ab350c88af134f35ef04e0fad2509` |

The intermediate Maven summary-part artifact is workflow assembly evidence and is not one of the four final retained artifact identities above.

## Retained natural failure trail

### #1406 — incomplete Runtime Binding provenance fixture

```text
Run: 31457454239 / #1406
Head: 720b9f6a65ffcaf2d4e27729efd5ec1a108a8dce
Conclusion: failure
Classification:
TEST_FIXTURE_BUG / MISSING_PROCESS_RELEASE_LIFECYCLE_FK_PROVENANCE
```

The real MySQL baseline correctly rejected a Runtime Binding whose fixture had Package + Deployment but omitted the required Process Release lifecycle row through:

```text
fk_process_runtime_binding_lifecycle
```

Correction-1 added a real `PUBLISHED -> ACTIVE` lifecycle using the already accepted G2 authority. It did not disable or weaken the foreign key.

Permanent evidence:

```text
docs/database/MYSQL_8_4_P3_G3_CORRECTION_EVIDENCE.md
```

### #1407 — stale pre-D5 raw-row immutability assumption

```text
Run: 31457936280 / #1407
Head: 53b55d2021ccdc451944ad1f73ad6f1c06abd74c
Conclusion: failure
Classification:
TEST_SCOPE_BUG / D5_RUNTIME_BINDING_GUARD_OUTSIDE_G3
```

Correction-1 had already repaired the lifecycle FK and the real G3 suite advanced to:

```text
5 methods
4 passed
1 assertion failure
0 errors
```

The only failed assertion expected arbitrary direct SQL `UPDATE` of the Runtime Binding row to be rejected forever.

That assumption is stale after M5-D5 V44. PostgreSQL V44 intentionally adds migration lineage fields and executes:

```text
drop trigger trg_process_runtime_binding_immutable on ap_process_runtime_binding;
```

before installing the D5 migration-specific guarded revision-CAS and append-only Runtime Binding evidence protocol.

Correction-2 therefore narrowed G3 back to the actual `ApprovalRuntimeBindingStore` authority: strict **initial** insertion plus exact reads/queries. It did not add or claim the D5 MySQL guard/CAS protocol.

Permanent evidence:

```text
docs/database/MYSQL_8_4_P3_G3_D5_SCOPE_CORRECTION_EVIDENCE.md
```

## Append-only workflow discipline

Across #1406, #1407 and #1408:

- no failed Head was rerun in place;
- no empty commit was used to trigger CI;
- no rebase was used to erase history;
- no force push was used;
- each correction received a new meaningful Head;
- failed Runs remain visible as permanent evidence.

## PostgreSQL preservation

P3-G3 does not modify:

```text
JdbcApprovalRuntimeBindingStore
ApprovalRuntimeBindingStore
ApprovalRuntimeBinding
RuntimeBindingRecordingAuditEventSink
RuntimeBindingEnforcingProjectionStore
PurchasePaymentApplicationService
ApprovalProcessReleaseDispositionService
ApprovalProcessReleaseMigrationAssessmentService
PostgreSQL Flyway history
```

The natural #1408 aggregate PostgreSQL evidence Job succeeded.

## Explicit remaining D5 gap

The following MySQL authority is **not** accepted by P3-G3:

```text
ApprovalMigrationRuntimeBindingCasStore
PostgresSerializedApprovalMigrationRuntimeBindingCasStore
```

Nor does P3-G3 prove MySQL equivalents of the D5 Runtime Binding mutation protocol, including:

```text
binding_revision CAS
migration-attempt lineage
exact-verification lineage
controlled target-release mutation
append-only runtime-binding evidence history
completion/conflict replay semantics
migration-specific transaction serialization
```

Those semantics must be accepted in a later bounded compatibility slice.

## Acceptance state

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_G2_RELEASE_LIFECYCLE_EFFECTIVE_PROVEN
MYSQL_P3_G3_RUNTIME_BINDING_STORE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

P3-G3 acceptance does not authorize PR Ready, merge, Issue closure, Flowable work, D5 compatibility implementation or MySQL production promotion.
