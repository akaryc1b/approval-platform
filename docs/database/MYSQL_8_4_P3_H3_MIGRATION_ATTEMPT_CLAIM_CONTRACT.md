# MySQL 8.4 P3-H3 Migration Attempt Claim and Lease Fencing Contract

## Baseline identity

```text
source accepted capability: MYSQL_P3_H2_MIGRATION_ATTEMPT_PROVISIONING_PROVEN
source formal Head: a7df8a29cee6eb7327ed284e422b6a9d498cba2e
implementation branch: agent/mysql-8-4-p3-h3-migration-attempt-claim-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

P3-H3 converts only the existing `ApprovalMigrationAttemptClaimStore` authority for MySQL 8.4.

```text
MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

## Exact production scope

P3-H3 may add or modify only:

```text
JdbcApprovalMigrationAttemptClaimStoreFactory
JdbcMySqlApprovalMigrationAttemptClaimStore
JdbcMySqlApprovalInstanceCommandFence.acquireMigrationLock(...)
ApprovalMigrationExecutionConfiguration -> trusted claim factory
```

The package-private MySQL command-fence entrypoint is a bounded infrastructure extension of the already accepted H1 authority. It acquires the exact existing `approval-instance-command:v1:<tenant>:<instance>` transaction lock through the existing `JdbcMySqlTransactionLockManager`. It adds no new lock namespace, no new application port method and no change to business-command behavior.

The existing PostgreSQL implementation remains unchanged and remains the PostgreSQL authority:

```text
JdbcApprovalMigrationAttemptClaimStore
JdbcApprovalMigrationProtocolStore
JdbcApprovalMigrationAttemptRepository
JdbcApprovalMigrationAttemptTransitioner
JdbcApprovalInstanceCommandFence
```

No applied PostgreSQL migration is edited.

## Port and vendor boundary

The application contract remains unchanged:

```text
ApprovalMigrationAttemptClaimStore
```

Vendor selection is derived only from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`. No request, worker, tenant, browser, Mobile client, Connector, Provider, AI result or migration payload may select the database dialect.

P3-H3 does not make the generic `ApprovalMigrationProtocolStore` MySQL-compatible. The MySQL Claim Store owns only the bounded Attempt/Intent/Fence/ClaimBatch persistence operations required by this port.

## Claim transaction authority

One `claim` call executes in one local database transaction and may only:

1. replay an already durable claim batch for the exact tenant + request id + request hash;
2. lock the exact tenant + Intent row;
3. reconstruct the exact claim-visible Intent authority from Intent + consumed Plan + PlanConsumption relational evidence;
4. require Intent status `PENDING` or `RUNNING` and require the exact consumed Plan lifetime to remain current;
5. select at most `limit` claimable Attempts in stable `created_at, attempt_id` order using MySQL 8.4 `FOR UPDATE SKIP LOCKED`;
6. for each candidate, acquire the accepted H1 transaction-bound instance command lock;
7. reject/skip a `PENDING` candidate when another active durable command fence exists for its instance;
8. transition an unfenced `PENDING` Attempt to `CLAIMED` revision+1 with exact worker + lease evidence;
9. create one matching ACTIVE durable migration command fence and one fence event;
10. allow an expired `CLAIMED` Attempt to be taken over only when its matching ACTIVE fence exists and the lease is expired;
11. transition the PENDING Intent to RUNNING exactly once when at least one Attempt is first claimed;
12. append matching Attempt and Intent transition events;
13. append one immutable ClaimBatch, including a legitimate empty batch;
14. append one governed claim Audit event;
15. commit all state/evidence together.

A claim with no available candidate is still a durable empty ClaimBatch. Exact replay of that request returns the same empty result.

## Bounded relational Intent reconstruction

MySQL V50 does not expose every `ApprovalMigrationIntent` field as a first-class Intent column. P3-H3 must not leave `status/revision` relational columns inconsistent with a stale typed Intent payload when it owns PENDING -> RUNNING.

For this transition only, H3 reconstructs the exact Intent domain value from:

```text
ap_process_migration_intent
+ exact consumed ap_process_migration_plan
+ ap_process_migration_plan_consumption
```

Mapping is bounded and explicit:

```text
Intent identity/status/revision/idempotency/evidence hash/created+updated time -> Intent row
selectedInstanceCount + expiresAt -> exact consumed Plan
requestedBy + operationReason + requestId + traceId + auditChainReference -> exact PlanConsumption
```

The reconstructed typed payload is then transitioned through `ApprovalMigrationIntent.transitioned` and persisted together with the relational status/revision update.

This is not a claim that generic MySQL Plan/Intent creation, authorization, admission, querying or arbitrary payload reconstruction is accepted. Those remain outside H3.

## Claim candidate and SKIP LOCKED contract

MySQL uses:

```text
select payload_json
from ap_process_migration_attempt
where tenant_id=? and intent_id=?
  and (status='PENDING' or (status='CLAIMED' and lease_until<=?))
order by created_at, attempt_id
limit ?
for update skip locked
```

The product semantics are:

- bounded claim size 1..100;
- deterministic server-owned ordering among rows visible to the transaction;
- locked rows are skipped rather than blocking a worker indefinitely;
- a candidate must still pass instance-fence authority after selection;
- no claim may mutate another tenant;
- no retry loop broadens the requested limit.

## Attempt lease transition contract

Initial claim:

```text
PENDING -> CLAIMED
revision + 1
lease_owner = worker
lease_until = exact canonical lease end
lease_actor = worker in durable transition event
```

Expiry takeover:

```text
CLAIMED -> CLAIMED
revision + 1
old lease must be expired at happenedAt
matching ACTIVE fence must exist
new worker becomes lease owner
```

Renewal through `renew` also uses `CLAIMED -> CLAIMED` revision CAS and must update the same durable fence in the same transaction.

Attempt payload JSON, relational columns and Attempt Event evidence must describe the same canonical state.

## Durable command fence contract

P3-H3 reuses the H1 accepted MySQL instance command serialization authority directly inside persistence infrastructure:

```text
JdbcMySqlApprovalInstanceCommandFence
JdbcMySqlTransactionLockManager
JdbcMySqlApprovalInstanceCommandFence.lockScope(...)
```

The package-private `acquireMigrationLock(...)` method added by H3 acquires the same lock scope used by H1/D5 and by the business-command guard. It requires the same active synchronized local transaction and inherits the same bounded timeout and after-completion release protocol. No application-level database or migration-lock branch is added.

The durable fence remains separate relational evidence in:

```text
ap_approval_instance_command_fence
ap_approval_instance_command_fence_event
```

Initial claim creates exactly one ACTIVE MIGRATION fence. Renewal/takeover updates it by exact revision + current owner CAS. A stale owner cannot renew after takeover. Business commands remain fenced while the ACTIVE lease is current.

No `INSERT IGNORE`, `REPLACE`, `ON DUPLICATE KEY UPDATE`, `FOREIGN_KEY_CHECKS` or broad duplicate swallowing is allowed.

## ClaimBatch replay contract

Every call persists one `ApprovalMigrationClaimBatch`, including an empty batch, with exact:

```text
tenant
intent
worker
requested limit
ordered claimed Attempt ids
ordered Fence ids
request hash
claimed time
request id
trace id
```

Replay lookup is tenant + request id. Reuse with a different Intent or request hash fails closed. Claimed Attempts and Fences referenced by the batch must still exist; disappearing replay evidence is an integrity failure.

## Time and UUID boundary

All MySQL UUID parameters use `JdbcDatabaseValueAdapter` canonical text binding. All persisted Attempt, Intent, Fence, FenceEvent and ClaimBatch instants are canonicalized through the accepted nearest-microsecond UTC `AuditHashCanonicalizer.canonicalInstant` contract before typed payload serialization and before `datetime(6)` binding.

No database-local clock is authoritative for claim/lease decisions.

## Renewal and takeover semantics

`renew` must prove:

- only a current `CLAIMED` Attempt can renew/take over;
- the H1 instance lock is held before durable fence mutation;
- same-owner renewal occurs before current expiry and extends beyond the existing expiry;
- different-owner takeover occurs only at/after expiry;
- stale previous owner is rejected after takeover;
- Attempt and Fence revisions advance together in one transaction;
- audit failure rolls the complete renewal/takeover transaction back.

## Permanent acceptance matrix

P3-H3 permanent suites must include:

```text
JdbcApprovalMigrationAttemptClaimStoreFactoryTest
JdbcApprovalMigrationAttemptClaimStoreMySqlContractTest
JdbcApprovalMigrationAttemptClaimStoreMySqlIntegrationTest
```

Real MySQL coverage must prove at minimum:

- trusted PostgreSQL/MySQL factory selection;
- H2-provisioned PENDING Attempt -> CLAIMED with durable Fence + events + ClaimBatch;
- exact request replay and changed-request-hash conflict;
- PENDING Intent -> RUNNING relational + typed-payload transition;
- concurrent workers produce one claimed Attempt and one authoritative empty batch;
- current owner renewal;
- expiry takeover by another worker;
- stale previous owner rejection after takeover;
- active fence blocks normal business commands until expiry;
- tenant isolation;
- audit failure rolls back Attempt/Fence/Intent/ClaimBatch mutations;
- datetime(6) canonicalization and UUID readback;
- PostgreSQL production implementation and tests remain unchanged.

## Explicit non-scope

P3-H3 does not implement or imply compatibility for:

- generic `ApprovalMigrationProtocolStore` on MySQL;
- Plan creation/authorization/admission as a whole;
- Intent creation/admission/querying as a whole;
- D3 `ApprovalMigrationEngineExecutionStore`;
- real Flowable migration dispatch;
- D4 `ApprovalMigrationExactVerificationStore`;
- D6 reconciliation;
- D7 orchestration;
- historical MySQL upgrade/restore acceptance;
- complete dual-database permanent CI;
- operations/performance/backup/restore/production promotion;
- PR Ready transition;
- merge of PR #92;
- closure of Issue #91.

No later slice is started by this contract.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_H2_MIGRATION_ATTEMPT_PROVISIONING_PROVEN
MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
