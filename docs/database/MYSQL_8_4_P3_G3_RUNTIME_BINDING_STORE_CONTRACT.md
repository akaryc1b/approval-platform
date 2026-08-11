# MySQL 8.4 P3-G3 Runtime Binding Store Contract

## Baseline identity

```text
source accepted capability: MYSQL_P3_G2_RELEASE_LIFECYCLE_EFFECTIVE_PROVEN
source formal Head: 861448f54171428d1c39b3a77bbbf84d9a1d3be2
implementation branch: agent/mysql-8-4-p3-g3-runtime-binding-store-staging
formal branch: agent/mysql-8-4-production-compatibility
accepted implementation Head: 62fb43630bad2c4efb5499019de8c7928e384d7f
accepted implementation Run: 31458317787 / #1408
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

P3-G3 converts only the existing **initial Runtime Binding creation/read authority** represented by `ApprovalRuntimeBindingStore` for MySQL 8.4.

```text
MYSQL_P3_G3_RUNTIME_BINDING_STORE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

## Exact production scope

P3-G3 adds:

```text
JdbcApprovalRuntimeBindingStoreFactory
JdbcMySqlApprovalRuntimeBindingStore
ApprovalProcessReleaseLifecycleConfiguration -> trusted Runtime Binding factory
```

The following application/domain contracts remain unchanged and database-neutral:

```text
ApprovalRuntimeBindingStore
ApprovalRuntimeBinding
RuntimeBindingEnforcingProjectionStore
RuntimeBindingRecordingAuditEventSink
PurchasePaymentApplicationService
ApprovalProcessReleaseDispositionService
ApprovalProcessReleaseMigrationAssessmentService
```

The existing PostgreSQL implementation remains unchanged:

```text
JdbcApprovalRuntimeBindingStore
```

PostgreSQL Flyway history remains immutable.

## Runtime Binding model and G3 boundary

When an approval instance starts against a governed release, the platform creates initial Runtime Binding evidence that identifies the exact release package and exact deployed engine definition selected for that instance.

The application-level binding contains:

```text
tenant_id
approval_instance_id
business_key
engine_instance_id
definition_key
release_version
release_package_hash
definition_version
definition_hash
form_package_version
form_package_hash
form_version
form_hash
ui_schema_version
ui_schema_hash
compiler_version
compiled_artifact_hash
bpmn_hash
deployment_metadata_hash
engine_deployment_id
engine_definition_id
engine_version
binding_evidence_hash
bound_by
bound_at
request_id
trace_id
audit_chain_reference
```

`ApprovalRuntimeBinding` remains immutable as the application value used to create and validate the initial binding evidence.

The database row, however, is **not claimed to remain permanently immutable after M5-D5**. PostgreSQL V44 deliberately evolves the same row through a separate governed migration-CAS protocol. Therefore P3-G3 proves only:

```text
initial Runtime Binding Store save
initial exact reads
release usage count
release pagination
initial start/replay/rollback behavior
fail-closed projection enforcement
```

It does not prove the later D5 row-mutation authority.

## Trusted vendor selection

`JdbcApprovalRuntimeBindingStoreFactory` derives database identity only from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`.

```text
PostgreSQL 16 -> JdbcApprovalRuntimeBindingStore
MySQL 8.4    -> JdbcMySqlApprovalRuntimeBindingStore
```

No request, browser field, tenant property, profile string, Connector payload, AI payload or workflow payload can select the persistence implementation.

## Strict initial admission contract

The bounded G3 Store API creates initial Runtime Binding evidence through a strict insert into:

```text
ap_process_runtime_binding
```

The MySQL implementation does not use:

```text
INSERT IGNORE
REPLACE
ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS
retry-on-duplicate
initial evidence repair
```

A duplicate initial binding must fail rather than silently rewrite the existing binding.

The accepted MySQL V50 schema remains authoritative for relational uniqueness, foreign keys and CHECK constraints. P3-G3 does not disable or weaken them.

## Complete initial provenance graph

A valid initial Runtime Binding cannot exist in isolation.

The real MySQL acceptance fixture proves the relational chain remains enabled:

```text
immutable Release Package
  -> Process Release lifecycle for the same tenant/definition/release
  -> DEPLOYED Release Deployment
  -> platform approval instance
  -> initial Runtime Binding
```

The #1406 natural failure is retained because MySQL correctly rejected an incomplete fixture that omitted Process Release lifecycle evidence through:

```text
fk_process_runtime_binding_lifecycle
```

Correction-1 added the missing real `PUBLISHED -> ACTIVE` lifecycle through the already accepted G2 authority. No constraint was disabled.

## UUID and timestamp boundary

`approval_instance_id` uses `JdbcDatabaseValueAdapter`:

```text
Java UUID <-> canonical MySQL UUID text
```

`bound_at` is initial binding evidence time and uses the accepted evidence timestamp contract:

```text
UTC datetime(6)
nearest-microsecond canonicalization
500 ns boundary carries forward
```

The application-owned `binding_evidence_hash` protocol remains unchanged. P3-G3 does not introduce a database-specific evidence hash.

## Exact lookup contract

Exact approval-instance lookup remains scoped by:

```text
tenant_id + approval_instance_id
```

Exact engine-instance lookup remains scoped by:

```text
tenant_id + engine_instance_id
```

Tenant identity remains case-sensitive under the accepted MySQL collation:

```text
utf8mb4_0900_as_cs
```

No cross-tenant fallback is permitted.

## Release usage and deterministic pagination

Release usage remains scoped by:

```text
tenant_id + definition_key + release_version
```

`countReleaseUsage(...)` remains the authoritative read consumed by release disposition governance.

Paged Runtime Binding reads remain deterministic:

```text
order by bound_at desc, approval_instance_id
limit + offset
```

The same page is consumed by governed migration assessment. P3-G3 does not alter migration assessment decisions.

## Production instance-start transaction

The permanent MySQL suite proves the existing production composition:

```text
PurchasePaymentApplicationService
  -> exact Effective Release
  -> external ApprovalEngine.startExact(...)
  -> create platform instance/task projections
  -> INSTANCE_STARTED audit event
  -> RuntimeBindingRecordingAuditEventSink
       -> load exact Release Package
       -> load exact DEPLOYED deployment
       -> verify projection/package/deployment identity
       -> compute application-owned binding evidence hash
       -> save initial Runtime Binding
       -> append governed audit event
```

The platform projection, initial Runtime Binding, governed audit and idempotency evidence join the same local JDBC transaction.

Request replay returns the original result and does not call the external engine a second time.

## Failure and rollback contract

The real MySQL suite proves a failure **after the Runtime Binding insert has actually occurred**.

When the delegate audit sink fails after `runtimeBindings.save(binding)`:

```text
external engine start: remains one-shot and cannot be rolled back
platform instance projection: rolled back
platform task projection: rolled back
initial runtime binding: rolled back
audit event: absent
idempotency evidence: rolled back
```

This preserves the existing externally visible platform transaction boundary without pretending an external engine call is locally transactional.

## Fail-closed projection read contract

`RuntimeBindingEnforcingProjectionStore` remains application-owned and database-neutral.

For release-bound instances:

```text
missing initial binding -> fail closed
binding/projection mismatch -> fail closed
exact binding -> projection read allowed
```

P3-G3 does not add a MySQL-specific bypass.

## Explicit separation from M5-D5 migration Runtime Binding CAS

P3-G3 does **not** convert:

```text
ApprovalMigrationRuntimeBindingCasStore
PostgresSerializedApprovalMigrationRuntimeBindingCasStore
```

PostgreSQL M5-D5 V44 deliberately adds controlled mutation lineage to `ap_process_runtime_binding`:

```text
binding_revision
last_migration_attempt_id
last_verification_id
last_verification_evidence_hash
```

and explicitly replaces the old pre-D5 immutability assumption:

```text
drop trigger trg_process_runtime_binding_immutable on ap_process_runtime_binding;
```

with a migration-specific guarded revision-CAS and append-only Runtime Binding evidence protocol.

The #1407 natural failure is retained because the original G3 test incorrectly expected arbitrary raw-row UPDATE rejection as a permanent Runtime Binding property. That assertion exceeded G3 scope and froze a pre-D5 assumption that PostgreSQL itself no longer has.

Correction-2 narrowed G3 to the real `ApprovalRuntimeBindingStore` authority and did not implement or claim MySQL support for:

```text
binding_revision CAS
migration attempt lineage
exact verification lineage
controlled target-release mutation
append-only runtime-binding evidence history
migration completion/conflict replay
migration-specific transaction serialization
```

Those remain mandatory later compatibility work.

## Permanent acceptance matrix

The bounded permanent suites are:

```text
JdbcApprovalRuntimeBindingStoreFactoryTest
JdbcApprovalRuntimeBindingStoreMySqlContractTest
JdbcApprovalRuntimeBindingStoreMySqlIntegrationTest
```

Accepted implementation Run #1408 proves:

```text
JdbcApprovalRuntimeBindingStoreFactoryTest:
2 / 0 / 0 / 0, 0.005 s

JdbcApprovalRuntimeBindingStoreMySqlContractTest:
3 / 0 / 0 / 0, 0.009 s

JdbcApprovalRuntimeBindingStoreMySqlIntegrationTest:
5 / 0 / 0 / 0, 37.320 s
```

The real suite uses the accepted MySQL 8.4 Testcontainers baseline:

```text
InnoDB
utf8mb4_0900_as_cs
UTC session time
READ COMMITTED
strict SQL mode
datetime(6)
useAffectedRows=false
```

It proves:

- trusted PostgreSQL/MySQL factory selection;
- strict initial Runtime Binding insertion;
- duplicate initial insert rejection;
- exact approval-instance lookup;
- exact engine-instance lookup;
- UUID round-trip;
- nearest-microsecond `bound_at` round-trip including 500 ns carry;
- tenant/case isolation;
- release usage count;
- deterministic release pagination and `hasMore`;
- complete real Release Package / Process Release lifecycle / Deployment / instance foreign-key provenance;
- real instance-start commit and idempotent replay;
- exact initial binding evidence matches the immutable Release Package and DEPLOYED deployment;
- failure after an actual initial binding insert rolls local platform evidence back;
- release-bound projection reads fail closed without the initial binding;
- PostgreSQL Runtime Binding implementation and PostgreSQL suites remain unchanged.

## Accepted implementation validation

Natural implementation acceptance:

```text
Run: 31458317787 / #1408
Head: 62fb43630bad2c4efb5499019de8c7928e384d7f
Conclusion: success
Jobs: 9 / 9 success
```

Independent reconstruction:

```text
Maven Core:                           1469 / 0 / 0 / 0
Persistence JDBC:                      508 / 0 / 0 / 0
Combined:                              1977 / 0 / 0 / 0
selected persistence test classes:     124
Surefire report classes:               123
expected abstract without report:        1
duplicate selections:                    0
non-abstract selected without report:    0
unexpected reports:                      0
selection coverage:                  exact
aggregate reported persistence time: 946.799 s
```

Deterministic shards:

```text
29 / 32 / 30 / 33
unique: 124 / 124
```

## Independently verified #1408 Artifacts

All four final ZIPs were independently downloaded. Local byte size and SHA-256 exactly match GitHub metadata and every archive passes ZIP integrity verification.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9088904237` | `850885` | `f649768d947bfadbb9fe52edb4f8027ea3cf7b1fda61720202391127fd8e7ac5` |
| Vben | `9088877319` | `18959` | `a6eea0041b85d7b8a2db21976641b4cedb82869bed5b23e594cc2e6309b8fa13` |
| Mobile | `9088862519` | `9814` | `8050a346f3308858868357ba832ca0c36614677422e866d9dfce28696eccf4b2` |
| Hygiene | `9088849523` | `17486` | `41e2eae7871369867709602c7447ab104d1ab350c88af134f35ef04e0fad2509` |

Artifacts are bound to formal branch Head `62fb43630bad2c4efb5499019de8c7928e384d7f` and expire `2026-11-09T04:23:22Z`.

## Retained natural failure trail

```text
#1406 / 31457454239
Head: 720b9f6a65ffcaf2d4e27729efd5ec1a108a8dce
TEST_FIXTURE_BUG / MISSING_PROCESS_RELEASE_LIFECYCLE_FK_PROVENANCE

#1407 / 31457936280
Head: 53b55d2021ccdc451944ad1f73ad6f1c06abd74c
TEST_SCOPE_BUG / D5_RUNTIME_BINDING_GUARD_OUTSIDE_G3

#1408 / 31458317787
Head: 62fb43630bad2c4efb5499019de8c7928e384d7f
success
```

No failed Head was rerun in place. No empty commit, rebase or force push was used.

Permanent failure evidence:

```text
docs/database/MYSQL_8_4_P3_G3_CORRECTION_EVIDENCE.md
docs/database/MYSQL_8_4_P3_G3_D5_SCOPE_CORRECTION_EVIDENCE.md
```

## Forbidden shortcuts

P3-G3 does not use:

```text
INSERT IGNORE
REPLACE
ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS
same-Head workflow rerun
empty commit workflow trigger
force push
```

The MySQL Runtime Binding Store contains no PostgreSQL advisory-lock SQL.

## Explicit non-scope

P3-G3 does not implement or imply MySQL compatibility for:

- `ApprovalMigrationRuntimeBindingCasStore` or D5 Runtime Binding mutation/replay serialization;
- Flowable schema or real Flowable execution on MySQL;
- remaining message/comment/query/SLA/notification/operational-failure stores;
- remaining migration intent/attempt/execution/reconciliation authorities;
- AI or controlled-automation evidence authorities not yet converted;
- historical MySQL fixture upgrade/restore rehearsal;
- future V51+ MySQL upgrade contract;
- complete permanent dual-database CI;
- backup/restore, rollback or incident runbooks;
- full database performance/query-plan acceptance;
- MySQL production authorization;
- PR Ready transition;
- merge of PR #92;
- closure of Issue #91.

No later slice is started by this contract.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_G2_RELEASE_LIFECYCLE_EFFECTIVE_PROVEN
MYSQL_P3_G3_RUNTIME_BINDING_STORE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
