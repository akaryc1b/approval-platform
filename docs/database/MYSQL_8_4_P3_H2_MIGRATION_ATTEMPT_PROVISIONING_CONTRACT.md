# MySQL 8.4 P3-H2 Migration Attempt Provisioning Contract

## Baseline identity

```text
source accepted capability: MYSQL_P3_H1_MIGRATION_BINDING_CAS_PROVEN
source formal Head: 1414e33fb7992c71f54923f1dc80a76a94cd91e2
implementation branch: agent/mysql-8-4-p3-h2-migration-attempt-provisioning-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

P3-H2 converts only the existing `ApprovalMigrationAttemptProvisioningStore` authority for MySQL 8.4.

```text
MYSQL_P3_H2_MIGRATION_ATTEMPT_PROVISIONING_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

## Exact production scope

P3-H2 may add only the infrastructure required to select and execute the existing provisioning port on PostgreSQL 16 or MySQL 8.4:

```text
JdbcApprovalMigrationAttemptProvisioningStoreFactory
JdbcMySqlApprovalMigrationAttemptProvisioningStore
ApprovalMigrationExecutionConfiguration -> trusted provisioning factory
```

The existing PostgreSQL implementation remains the PostgreSQL authority:

```text
JdbcApprovalMigrationAttemptProvisioningStore
JdbcApprovalMigrationProtocolStore
JdbcApprovalMigrationAttemptStore
JdbcApprovalMigrationAttemptRepository
JdbcApprovalMigrationAttemptCreator
JdbcApprovalMigrationAttemptTransitioner
```

No applied PostgreSQL migration is edited.

## Port boundary

The application contract remains unchanged:

```text
ApprovalMigrationAttemptProvisioningStore
```

P3-H2 does not add database branching to application or domain code. Vendor selection is derived only from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`.

No tenant, request, browser, Mobile client, Connector payload, AI payload, worker payload, profile string or migration command can manufacture the database vendor.

## Provisioning authority

One provisioning call is allowed to do only the following in one local database transaction:

1. lock the exact tenant + migration Intent;
2. require Intent status `PENDING` or `RUNNING` and require it to remain unexpired;
3. lock/read the exact consumed immutable migration Plan bound by `planId + planHash`;
4. require exact Plan/Intent tenant, definition, source/target release hashes and selected count identity;
5. read the sealed Plan selections in `sequence_no` order;
6. join each selected instance to the current Runtime Binding and approval-instance projection;
7. require every selected instance to remain `RUNNING` and to retain its exact expected Runtime Binding evidence, source release and definition identity;
8. read existing attempts for the Intent;
9. reject attempts outside the sealed Plan selection;
10. create exactly one initial attempt for each selected instance that has no initial attempt yet;
11. append exactly one matching immutable initial Attempt Event per created attempt;
12. append one governed provisioning Audit Event only when at least one attempt was created;
13. commit all created attempts/events/audit together.

A replay after complete provisioning returns the exact current initial attempts with `createdCount=0` and does not append another provisioning audit event.

## MySQL serialization boundary

MySQL provisioning serializes callers through the exact migration Intent row:

```text
select ... from ap_process_migration_intent
where tenant_id=? and intent_id=?
for update
```

This is intentionally narrower than P3-H3 Attempt Claim semantics. P3-H2 does not implement claim scanning, `SKIP LOCKED`, lease takeover or worker fencing.

Two concurrent provisioning callers for one exact tenant + Intent must converge to:

```text
one authoritative creation transaction
one authoritative replay
one initial Attempt per sealed selected instance
one initial Attempt Event per created Attempt
one provisioning Audit Event
```

No duplicate-key shortcut is used as the primary concurrency authority.

## MySQL value and time boundary

MySQL UUID values are written through canonical UUID text binding compatible with the accepted V50 baseline.

All persisted attempt/event instants use the accepted nearest-microsecond UTC canonicalization before the domain evidence object is serialized and before `datetime(6)` binding. This prevents column/payload drift around the 500ns carry boundary and preserves the already accepted PostgreSQL observable instant semantics.

Attempt and event payloads contain typed domain objects; no generic untyped Number protocol is introduced by this slice.

## Initial Attempt contract

Every newly created initial Attempt is exactly:

```text
attempt_number = 1
parent_attempt_id = null
status = PENDING
engine_outcome = NOT_REQUESTED
revision = 1
lease owner/until = null
engine request reference = null
failure class = NONE
error summary = null
```

It is bound to the exact:

```text
tenant
Intent
approval instance
engine instance
sealed expected Runtime Binding evidence hash
source engine definition
target engine definition
```

The matching initial Attempt Event is revision 1, `fromStatus = null`, `toStatus = PENDING`, and carries durable evidence derived from that Attempt.

## Replay and conflict boundary

P3-H2 must fail closed when:

- the tenant-scoped Intent does not exist;
- the Intent is not current for provisioning;
- the consumed Plan is missing or no longer exact;
- sealed selection count/order/identity no longer matches;
- approval-instance status is no longer `RUNNING`;
- Runtime Binding evidence hash, source release/package or source definition drifted;
- an existing initial attempt differs from the sealed selection authority;
- an Attempt exists for the Intent but for an instance outside the sealed selection;
- a database constraint rejects the evidence;
- the audit append fails.

Audit failure after real Attempt/Event inserts must roll the complete local transaction back.

## Permanent acceptance matrix

P3-H2 permanent suites must include:

```text
JdbcApprovalMigrationAttemptProvisioningStoreFactoryTest
JdbcApprovalMigrationAttemptProvisioningStoreMySqlContractTest
JdbcApprovalMigrationAttemptProvisioningStoreMySqlIntegrationTest
```

The real MySQL integration suite must prove at minimum:

- trusted PostgreSQL/MySQL factory selection;
- exact initial Attempt creation from real Plan/Intent/Runtime Binding/projection provenance;
- exact replay with no duplicate rows or audit;
- tenant-scoped fail-closed reads;
- current Runtime Binding drift rejection before Attempt creation;
- concurrent provisioning produces one creator and one authoritative replay;
- audit failure rolls back real Attempt and Attempt Event inserts;
- persisted timestamps are canonical at `datetime(6)` precision;
- PostgreSQL provisioning production code and PostgreSQL suites remain unchanged.

## Forbidden shortcuts

P3-H2 does not use:

```text
FOREIGN_KEY_CHECKS
INSERT IGNORE
REPLACE
ON DUPLICATE KEY UPDATE
application-layer MySQL branches
same-Head workflow rerun
empty commit workflow trigger
force push
```

The MySQL provisioning class must not contain PostgreSQL-only syntax including:

```text
::text
::jsonb
cast(... as jsonb)
for share
pg_advisory
```

## Explicit non-scope

P3-H2 does not implement or imply MySQL compatibility for:

- `ApprovalMigrationAttemptClaimStore`;
- claim scanning or `SKIP LOCKED`;
- claim lease renewal, expiry takeover or stale-worker fencing;
- D3 `ApprovalMigrationEngineExecutionStore`;
- real Flowable migration dispatch;
- D4 `ApprovalMigrationExactVerificationStore`;
- D5 Runtime Binding CAS beyond the already accepted H1 scope;
- D6 reconciliation execution;
- D7 bounded orchestration;
- migration Intent creation/admission as a whole;
- migration Plan creation/authorization/consumption as a whole;
- historical MySQL upgrade/restore acceptance;
- complete permanent dual-database CI;
- operations, performance, backup/restore or production promotion;
- PR Ready transition;
- merge of PR #92;
- closure of Issue #91.

No later slice is started by this contract.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_H1_MIGRATION_BINDING_CAS_PROVEN
MYSQL_P3_H2_MIGRATION_ATTEMPT_PROVISIONING_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
