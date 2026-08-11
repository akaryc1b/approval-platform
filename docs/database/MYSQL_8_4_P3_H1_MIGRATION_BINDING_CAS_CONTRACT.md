# MySQL 8.4 P3-H1 Migration Runtime Binding CAS Contract

## Baseline identity

```text
source accepted capability: MYSQL_P3_G3_RUNTIME_BINDING_STORE_PROVEN
source formal Head: fafd0ed5841d96162f551cb8e0d44413232e8456
implementation branch: agent/mysql-8-4-p3-h1-migration-binding-cas-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

P3-H1 converts only the **M5-D5 exact migration Runtime Binding completion cluster** and the shared Approval Instance command-fence serialization required by that cluster for MySQL 8.4.

```text
MYSQL_P3_H1_MIGRATION_BINDING_CAS_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

## Exact production scope

P3-H1 adds:

```text
JdbcApprovalInstanceCommandFenceFactory
JdbcMySqlApprovalInstanceCommandFence
JdbcApprovalMigrationRuntimeBindingCasStoreFactory
JdbcMySqlApprovalMigrationRuntimeBindingCasStore
JdbcApprovalMigrationBindingRevisionReader -> vendor-safe UUID binding
ApprovalRuntimeBindingEvidenceConfiguration -> trusted fence factory
ApprovalMigrationExecutionConfiguration -> trusted D5 CAS factory
```

PostgreSQL retains:

```text
JdbcApprovalInstanceCommandFence
JdbcApprovalMigrationRuntimeBindingCasStore
PostgresSerializedApprovalMigrationRuntimeBindingCasStore
```

Application/domain contracts remain database-neutral:

```text
ApprovalInstanceCommandFence
ApprovalMigrationRuntimeBindingCasStore
ApprovalMigrationRuntimeBindingCasService
ApprovalMigrationBindingRevisionReader
ApprovalMigrationAttemptPipelineService
ApprovalMigrationCommandFence
ApprovalMigrationExactVerification
ApprovalMigrationRuntimeBindingEvidence
ApprovalMigrationInstanceCompletionEvidence
ApprovalMigrationBindingCasConflictEvidence
```

PostgreSQL Flyway V1-V50 history remains unchanged.

## Why H1 is a two-authority bounded cluster

D5 completion cannot be accepted by replacing only the outer PostgreSQL advisory lock.

The existing PostgreSQL path has two serialization authorities:

```text
attempt serialization:
PostgresSerializedApprovalMigrationRuntimeBindingCasStore

approval-instance command serialization:
JdbcApprovalInstanceCommandFence
```

The instance command fence is shared by normal business commands and migration completion. Without the same logical lock on MySQL, a business command and D5 completion could race on one approval instance even if the D5 attempt itself were serialized.

Therefore H1 converts exactly:

```text
per-attempt D5 completion serialization
+
per-instance business/migration command serialization
+
D5 completion transaction persistence
```

It does not convert the complete M5 migration subsystem.

## Trusted vendor selection

Both factories derive database identity only from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`.

```text
PostgreSQL 16 -> existing PostgreSQL authorities
MySQL 8.4    -> bounded MySQL authorities
```

No tenant, browser, request header, workflow payload, Connector payload, AI payload, worker payload or Spring profile string may manufacture persistence dialect authority.

## MySQL transaction lock protocol

MySQL uses the already accepted `JdbcMySqlTransactionLockManager`.

D5 attempt lock namespace:

```text
approval-migration-binding-cas:v1:<tenantId>:<attemptId>
```

Shared instance command lock namespace:

```text
approval-instance-command:v1:<tenantId>:<approvalInstanceId>
```

The lock manager:

- requires an active synchronized local transaction;
- uses MySQL transaction-owned named locks;
- retains locks until transaction completion;
- verifies release;
- has no automatic retry;
- fails closed on timeout or unexpected lock state.

The same instance namespace is used by `JdbcMySqlApprovalInstanceCommandFence` and `JdbcMySqlApprovalMigrationRuntimeBindingCasStore` so business commands and migration completion cannot bypass one another.

## Business command fence contract

`guardBusinessCommand(...)` remains database-neutral at the port boundary.

MySQL behavior:

1. require a business command operation, never `MIGRATION`;
2. acquire the exact instance transaction lock;
3. read active command-fence rows for the same tenant + instance;
4. only an `ACTIVE` fence with `lease_until > happenedAt` blocks;
5. blocking raises `InstanceCommandFencedException`;
6. tenant identity remains case-sensitive;
7. no client-controlled lock key or bypass exists.

The existing command-fence table/event protocol is not rewritten.

## D5 authority consumed by H1

The completion transaction consumes already-created authority rows:

```text
migration plan: CONSUMED
migration intent: RUNNING
plan consumption
migration attempt: VERIFYING at exact expected revision
active command fence at exact expected revision / worker lease
engine request + outcome authority
exact verification: EXACT_TARGET_RUNTIME
source Runtime Binding and approval-instance projection
target immutable Release Package
target DEPLOYED Release Deployment
```

H1 does not claim MySQL compatibility for creation, claiming or engine execution of those rows. Real MySQL tests seed them as complete relational fixture authority and test only D5 completion semantics.

## Binding revision reader boundary

`JdbcApprovalMigrationBindingRevisionReader` is shared by the pipeline immediately before D5 CAS.

P3-H1 keeps the SQL portable and changes only UUID binding through `JdbcDatabaseValueAdapter`:

```text
PostgreSQL -> UUID
MySQL      -> canonical varchar(36)
```

No application branching is added.

## Successful D5 completion transaction

One MySQL local transaction must atomically:

```text
1. acquire per-attempt lock
2. detect exact replay if completion/conflict already exists
3. lock VERIFYING attempt
4. lock exact verification authority
5. lock plan / intent / consumption authority
6. acquire shared approval-instance command lock
7. lock ACTIVE migration fence
8. lock approval-instance projection
9. lock current Runtime Binding
10. prove source binding / projection / plan / verification authority
11. ensure append-only source binding revision evidence exists
12. lock exact target Release Package + DEPLOYED deployment
13. CAS Runtime Binding from expected source revision/hash/release/engine definition
14. append target Runtime Binding revision evidence
15. CAS approval-instance projection to the exact target release/engine definition
16. append immutable instance-completion evidence
17. transition attempt VERIFYING -> SUCCEEDED / CONFIRMED
18. append attempt transition event
19. release migration command fence and append fence event
20. append governed audit evidence
21. commit
```

Any failure before commit must roll back all local platform mutations.

## Runtime Binding V44 semantic equivalence

PostgreSQL V44 originally moved Runtime Binding from pre-D5 row immutability to controlled migration revision-CAS semantics.

The Runtime Binding row contains:

```text
binding_revision
last_migration_attempt_id
last_verification_id
last_verification_evidence_hash
```

MySQL must preserve the same externally visible D5 evidence contract even though the normalized MySQL V50 baseline does not execute PostgreSQL PL/pgSQL trigger bodies.

Therefore the MySQL D5 store explicitly performs the persistence effects that PostgreSQL V44 trigger/function infrastructure supplies:

```text
source revision evidence existence
binding revision +1
attempt / verification lineage
binding evidence hash update
append target binding revision evidence
```

For an initial G3 binding at revision 1, if no history row exists, H1 may admit exactly one source revision-1 evidence row from the already-persisted source binding before CAS.

For `binding_revision > 1`, missing source evidence fails closed. H1 does not repair arbitrary historical gaps.

## Hash compatibility contract

H1 does not invent new MySQL evidence hash versions.

It retains the existing D5 version labels:

```text
m5-runtime-binding-v44
m5-runtime-binding-history-v44
m5-instance-completion-v44
m5-binding-cas-conflict-v44
m5-runtime-binding-cas-request-v1
```

Request, completion, conflict and Runtime Binding history hashes use the same ordered source fields as PostgreSQL D5.

The target Runtime Binding hash reproduces the PostgreSQL V44 canonical field order and UTC `timestamptz::text` evidence representation in Java because MySQL does not have PostgreSQL `convert_to/sha256/timestamptz::text` SQL semantics.

The permanent real-MySQL suite must prove the resulting hashes are deterministic and stable on replay.

## UUID / JSON / time boundary

All MySQL D5 UUID columns use `JdbcDatabaseValueAdapter` canonical text binding/readback.

All D5 `datetime(6)` evidence uses accepted nearest-microsecond UTC canonicalization.

All JSON payloads continue to use `JdbcApprovalMigrationJson` and MySQL JSON columns without PostgreSQL casts such as:

```text
::text
::jsonb
cast(... as jsonb)
```

No reflective polymorphic type authority is introduced.

## Conflict and reconciliation boundary

If the source Runtime Binding CAS authority is stale, H1 must not mutate the binding or approval-instance projection.

It must atomically:

```text
append one immutable Binding CAS conflict evidence row
transition attempt VERIFYING -> RECONCILING
engine outcome -> VERIFICATION_MISMATCH
failure class -> RECONCILIATION_REQUIRED
append attempt event
append governed conflict audit event
retain the ACTIVE command fence for reconciliation ownership
```

Exact same-request replay returns `REPLAYED_CONFLICT` without duplicate evidence.

Changed-payload replay fails closed.

H1 does not execute D6 reconciliation.

## Exact completion replay boundary

After one committed successful completion:

```text
same request -> REPLAYED_COMPLETION
```

Replay must not:

```text
increment Runtime Binding revision again
append another target binding evidence row
append another completion row
transition Attempt again
release fence again
append another completion audit event
```

Changed-payload replay fails closed.

## Concurrency boundary

Two concurrent callers for the same tenant + attempt must serialize across database connections through the MySQL attempt lock.

Required result:

```text
one caller -> COMPLETED
one caller -> REPLAYED_COMPLETION
one completion row
one target binding revision
a single SUCCEEDED attempt transition
```

The instance lock separately prevents a concurrent business command from bypassing an active migration fence / D5 completion transaction.

## Rollback boundary

The real MySQL suite must inject a failure after the D5 completion transaction has already mutated real platform rows.

At minimum a governed audit failure must restore:

```text
Runtime Binding source revision/hash/release/engine definition
source approval-instance projection
no target binding evidence
no completion row
Attempt remains VERIFYING at original revision
Fence remains ACTIVE at original revision
no D5 completion audit row
```

Named locks must release after rollback.

## Permanent acceptance matrix

P3-H1 permanent suites must include:

```text
JdbcApprovalInstanceCommandFenceFactoryTest
JdbcApprovalMigrationRuntimeBindingCasStoreFactoryTest
JdbcApprovalMigrationRuntimeBindingCasMySqlContractTest
JdbcApprovalMigrationRuntimeBindingCasMySqlIntegrationTest
```

The real MySQL integration suite must prove at minimum:

- trusted command-fence factory selection;
- trusted D5 CAS factory selection;
- business command guard requires a transaction lock;
- active fence blocks a business command;
- instance lock blocks a competing caller and releases after rollback/commit;
- binding revision reader returns the exact MySQL revision;
- exact D5 success path;
- source revision-1 + target revision-2 append-only binding evidence;
- exact Runtime Binding target package/deployment/engine identity;
- exact approval-instance target projection;
- completion evidence;
- attempt SUCCEEDED transition/event;
- fence RELEASED transition/event;
- governed audit evidence;
- exact completion replay;
- changed-payload completion replay rejection;
- stale binding conflict / RECONCILING / retained fence;
- exact conflict replay;
- concurrent same-attempt serialization;
- audit-failure rollback after actual D5 mutations;
- PostgreSQL D5 classes and PostgreSQL suites remain unchanged.

## Forbidden shortcuts

P3-H1 does not use:

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

The MySQL D5 classes do not contain PostgreSQL advisory-lock SQL or PostgreSQL JSON/timestamp casts.

## Explicit non-scope

P3-H1 does not implement or imply MySQL compatibility for:

- migration plan creation / authorization / consumption workflow as a whole;
- migration intent creation/admission;
- attempt provisioning / claiming / lease recovery as a whole;
- D3 real engine execution / dispatch compatibility;
- D4 exact verification creation/readback compatibility as a full authority;
- D6 reconciliation execution;
- D7 bounded orchestration;
- real Flowable migration execution on MySQL;
- remaining migration stores not required by D5 completion;
- notification/SLA/operational-failure stores;
- AI / controlled-automation evidence stores not yet converted;
- historical MySQL upgrade/restore rehearsals;
- complete permanent dual-database CI;
- backup/restore, operations or production promotion;
- PR Ready transition;
- merge of PR #92;
- closure of Issue #91.

The existing one-shot migration runners remain default-disabled. H1 does not enable migration execution.

No later slice is started by this contract.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_G3_RUNTIME_BINDING_STORE_PROVEN
MYSQL_P3_H1_MIGRATION_BINDING_CAS_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
