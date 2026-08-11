# MySQL 8.4 P3-G3 Runtime Binding Store Contract

## Baseline identity

```text
source accepted capability: MYSQL_P3_G2_RELEASE_LIFECYCLE_EFFECTIVE_PROVEN
source formal Head: 861448f54171428d1c39b3a77bbbf84d9a1d3be2
implementation branch: agent/mysql-8-4-p3-g3-runtime-binding-store-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

P3-G3 converts only the existing immutable `ApprovalRuntimeBindingStore` authority for MySQL 8.4.

```text
MYSQL_P3_G3_RUNTIME_BINDING_STORE_STAGED
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

## Why Runtime Binding is one bounded authority

A runtime binding is immutable platform evidence that one approval instance was started against one exact governed release and one exact deployed engine definition.

The binding records:

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

The application model already verifies that this evidence exactly binds an immutable `ApprovalReleasePackage` and a `DEPLOYED` `ApprovalReleaseDeployment`.

P3-G3 does not change the evidence protocol. It only makes the persistence authority vendor-compatible.

## Trusted vendor selection

`JdbcApprovalRuntimeBindingStoreFactory` derives database identity only from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`.

```text
PostgreSQL 16 -> JdbcApprovalRuntimeBindingStore
MySQL 8.4    -> JdbcMySqlApprovalRuntimeBindingStore
```

No request, browser field, tenant property, profile string, Connector payload, AI payload or workflow payload can select the persistence implementation.

## Immutable admission contract

Runtime Binding publication remains a strict insert into:

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
mutable evidence repair
```

A duplicate immutable binding must fail rather than silently rewrite existing evidence.

The accepted MySQL V50 schema remains authoritative for relational uniqueness, foreign keys, checks and immutability enforcement. P3-G3 does not weaken or disable any database invariant.

## UUID and timestamp boundary

`approval_instance_id` uses `JdbcDatabaseValueAdapter`:

```text
Java UUID <-> canonical MySQL UUID text
```

`bound_at` is immutable evidence time and therefore uses the accepted evidence timestamp contract:

```text
UTC datetime(6)
nearest-microsecond canonicalization
500 ns boundary carries forward
```

The application-owned `binding_evidence_hash` protocol is unchanged. P3-G3 does not introduce a database-specific evidence hash.

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

No cross-tenant lookup fallback is permitted.

## Release usage and deterministic pagination

Release usage remains scoped by:

```text
tenant_id + definition_key + release_version
```

`countReleaseUsage(...)` is the authoritative read used by release disposition governance.

Paged release binding reads remain deterministic:

```text
order by bound_at desc, approval_instance_id
limit + offset
```

The same page is consumed by governed migration assessment. P3-G3 does not change migration assessment decisions or pagination semantics.

## Production instance-start transaction

The permanent MySQL suite must prove the existing production composition:

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
       -> save immutable Runtime Binding
       -> append governed audit event
```

The projection, Runtime Binding, audit event and idempotency evidence must join the same local JDBC transaction.

Request replay must return the original result and must not start the external engine a second time.

## Failure and rollback contract

The permanent MySQL suite must prove failure after the Runtime Binding insert has actually occurred.

A delegate audit failure after `runtimeBindings.save(binding)` must result in:

```text
external engine start: remains one-shot and cannot be rolled back
platform instance projection: rolled back
platform task projection: rolled back
runtime binding: rolled back
audit event: absent
idempotency evidence: rolled back
```

This preserves the existing PostgreSQL externally visible transaction boundary.

## Fail-closed projection read contract

`RuntimeBindingEnforcingProjectionStore` remains application-owned and database-neutral.

For release-bound instances:

```text
missing immutable binding -> fail closed
binding/projection mismatch -> fail closed
exact binding -> projection read allowed
```

P3-G3 does not weaken this wrapper or add MySQL-specific bypass logic.

## Permanent acceptance matrix

The bounded permanent suites are:

```text
JdbcApprovalRuntimeBindingStoreFactoryTest
JdbcApprovalRuntimeBindingStoreMySqlContractTest
JdbcApprovalRuntimeBindingStoreMySqlIntegrationTest
```

The real integration suite uses the already accepted MySQL 8.4 Testcontainers baseline:

```text
InnoDB
utf8mb4_0900_as_cs
UTC session time
READ COMMITTED
strict SQL mode
datetime(6)
useAffectedRows=false
```

The real suite must prove at minimum:

- trusted PostgreSQL/MySQL factory selection;
- strict immutable Runtime Binding insertion;
- exact approval-instance lookup;
- exact engine-instance lookup;
- UUID round-trip;
- nearest-microsecond `bound_at` round-trip including the 500 ns carry boundary;
- tenant/case isolation;
- release usage count;
- deterministic release pagination and `hasMore`;
- duplicate immutable binding rejection;
- database immutability enforcement for update/delete when the governed baseline defines it;
- complete real release/package/deployment/instance foreign-key graph remains enabled;
- real instance-start commit and idempotent replay;
- exact binding evidence matches immutable Release Package + DEPLOYED deployment;
- failure after actual binding insert rolls platform evidence back;
- release-bound projection reads continue to fail closed without matching binding;
- PostgreSQL Runtime Binding implementation and PostgreSQL suites remain unchanged.

## Explicit separation from migration Runtime Binding CAS

P3-G3 does **not** convert:

```text
ApprovalMigrationRuntimeBindingCasStore
PostgresSerializedApprovalMigrationRuntimeBindingCasStore
```

That D5 protocol serializes migration completion replay per migration attempt and has a different authority, lock scope, failure model and transaction boundary.

It remains a later bounded compatibility slice.

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

The MySQL Runtime Binding Store does not contain PostgreSQL advisory-lock SQL.

## Explicit non-scope

P3-G3 does not implement or imply MySQL compatibility for:

- `ApprovalMigrationRuntimeBindingCasStore` or migration completion replay serialization;
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
MYSQL_P3_G3_RUNTIME_BINDING_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
