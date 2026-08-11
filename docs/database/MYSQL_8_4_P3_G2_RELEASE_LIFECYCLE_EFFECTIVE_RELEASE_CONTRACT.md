# MySQL 8.4 P3-G2 Process Release Lifecycle and Effective Release Contract

## Baseline identity

```text
source accepted capability: MYSQL_P3_G1_RELEASE_PACKAGE_DEPLOYMENT_PROVEN
source formal Head: 42c1528dbf928fc112817733474f537e62f28bb6
implementation branch: agent/mysql-8-4-p3-g2-release-lifecycle-effective-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

P3-G2 converts only the existing Process Release lifecycle authority, current Effective Release authority, immutable activation history and current-effective deactivation CAS for MySQL 8.4.

```text
MYSQL_P3_G2_RELEASE_LIFECYCLE_EFFECTIVE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

## Exact production scope

P3-G2 adds:

```text
JdbcApprovalProcessReleaseStoreFactory
JdbcMySqlApprovalProcessReleaseStore
JdbcApprovalEffectiveReleaseStoreFactory
JdbcMySqlApprovalEffectiveReleaseStore
JdbcApprovalEffectiveReleaseDeactivationPortFactory
JdbcMySqlApprovalEffectiveReleaseDeactivationPort
ApprovalProcessReleaseLifecycleConfiguration -> trusted lifecycle/deactivation factories
ApprovalReleaseDeploymentConfiguration -> trusted Effective Release factory
```

The following application/domain contracts remain unchanged and database-neutral:

```text
ApprovalProcessReleaseStore
ApprovalEffectiveReleaseStore
ApprovalEffectiveReleaseDeactivationPort
ApprovalProcessReleaseLifecycleService
ApprovalProcessReleaseActivationService
ApprovalEffectiveReleaseService
ApprovalProcessReleaseDispositionService
ApprovalProcessRelease
ApprovalEffectiveRelease
ApprovalReleaseLifecycle
```

The existing PostgreSQL implementations remain unchanged:

```text
JdbcApprovalProcessReleaseStore
JdbcApprovalEffectiveReleaseStore
JdbcApprovalEffectiveReleaseDeactivationPort
```

PostgreSQL Flyway history remains immutable.

## Why lifecycle, effective projection and deactivation are one bounded slice

The governed release switch is not a single-table operation.

Activation executes inside the shared idempotency transaction:

```text
ApprovalProcessReleaseActivationService
  -> Process Release definition lock
  -> load target lifecycle
  -> load current ACTIVE lifecycle
  -> current ACTIVE -> DEPRECATED when present
  -> target PUBLISHED/DEPRECATED -> ACTIVE
  -> ApprovalEffectiveReleaseService
       -> require exact immutable Release Package
       -> require DEPLOYED deployment projection
       -> Effective Release definition lock
       -> current-effective revision verification
       -> save/update current effective projection
       -> append immutable activation history
  -> append governed audit evidence
```

Explicit deprecation executes:

```text
ApprovalProcessReleaseDispositionService
  -> Process Release definition lock
  -> Effective Release definition lock
  -> ACTIVE lifecycle -> DEPRECATED
  -> ApprovalEffectiveReleaseDeactivationPort.clear(... expectedRevision ...)
```

If current-effective removal fails after the lifecycle transition, the whole caller-owned transaction must roll back. Therefore lifecycle state, current-effective state and deactivation CAS cannot be accepted independently as production-compatible authorities.

## Trusted implementation selection

All three factories derive database identity only from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`.

```text
PostgreSQL 16 -> existing PostgreSQL authorities
MySQL 8.4    -> bounded MySQL authorities
```

No request, browser field, tenant property, workflow payload, Connector payload, AI payload or profile string can select the persistence implementation.

## Closed lifecycle contract

The domain lifecycle remains unchanged:

```text
DRAFT -> PUBLISHED
PUBLISHED -> ACTIVE
PUBLISHED -> RETIRED
ACTIVE -> DEPRECATED
DEPRECATED -> ACTIVE
DEPRECATED -> RETIRED
RETIRED -> terminal
```

Persisted lifecycle rows can never be DRAFT.

Published content remains immutable after publication. `ACTIVE` selects the default release for new instances and never rewrites an existing runtime binding.

## Process Release persistence identity

`ap_process_release_lifecycle` remains tenant scoped by:

```text
tenant_id
definition_key
release_version
```

The row binds:

```text
release_package_hash
lifecycle_state
revision
published_by
published_at
activated_at
deprecated_at
retired_at
last_transition_by
last_transition_at
last_transition_reason
last_idempotency_key
last_request_id
last_trace_id
last_audit_chain_reference
```

The immutable transition history remains in:

```text
ap_process_release_lifecycle_history
```

with:

```text
transition_id
tenant_id
definition_key
release_version
release_package_hash
from_state
to_state
revision
reason
idempotency_key
operator_id
request_id
trace_id
audit_chain_reference
happened_at
```

## Process Release definition serialization

PostgreSQL retains its transaction-scoped advisory lock.

MySQL uses `JdbcMySqlTransactionLockManager` with the exact namespace:

```text
approval-process-release:<tenantId>:<definitionKey>
```

The lock:

- requires an active synchronized local transaction;
- serializes all lifecycle changes for one tenant + definition;
- remains held through commit or rollback;
- releases after transaction completion;
- has no automatic retry;
- does not replace revision CAS;
- does not move vendor branching into application services.

## Process Release publication and revision CAS

Initial lifecycle persistence remains strict:

```text
DRAFT -> PUBLISHED
revision = 1
```

`savePublished` must insert both the lifecycle row and exactly one matching transition fact in the caller-owned transaction.

Subsequent transitions retain the exact optimistic predicate:

```text
tenant_id = :tenantId
definition_key = :definitionKey
release_version = :releaseVersion
revision = :expectedRevision
```

The new lifecycle revision must be exactly:

```text
expectedRevision + 1
```

A stale revision returns `false`. No transition history may be appended after a failed CAS.

## Single ACTIVE lifecycle invariant

The existing MySQL V50 baseline remains authoritative for release-lifecycle relational constraints.

P3-G2 proves through the real service path that two concurrent target activations cannot commit two ACTIVE lifecycles for the same tenant + definition.

The required externally visible result is:

```text
exactly one target becomes ACTIVE
previous ACTIVE becomes DEPRECATED
losing target remains PUBLISHED
exactly one current Effective Release points at the winner
losing transaction leaves no audit/idempotency/history residue
```

P3-G2 does not weaken uniqueness or emulate it with application-only prechecks.

## Transition history contract

Transition history remains append-only.

Exact idempotency lookup is tenant scoped:

```text
tenant_id + idempotency_key
```

Paged history remains deterministic:

```text
order by revision desc, transition_id desc
```

Lifecycle listing remains tenant scoped and deterministic:

```text
optional definition_key
optional lifecycle_state
order by definition_key, release_version desc
limit + offset
```

Tenant identity remains case-sensitive under `utf8mb4_0900_as_cs`.

## Transition UUID and time evidence

MySQL transition IDs use `JdbcDatabaseValueAdapter` and persist canonical UUID text while the application receives exact `UUID` values.

Lifecycle timestamps use the accepted immutable/evidence timestamp rule:

```text
UTC datetime(6)
nearest-microsecond canonicalization
500 ns boundary carries forward
```

This applies to:

```text
published_at
activated_at
deprecated_at
retired_at
last_transition_at
transition happened_at
```

No mutable-draft flooring rule is used here.

## Effective Release current projection

`ap_approval_effective_release` remains one mutable current projection per:

```text
tenant_id
definition_key
```

It binds the selected immutable release and deployed engine identity:

```text
effective_release_version
previous_release_version
release_package_hash
definition_version + definition_hash
form_package_version + form_package_hash
form_schema_version + form_schema_hash
ui_schema_version + ui_schema_hash
compiler_version
compiled_artifact_hash
bpmn_hash
deployment_metadata_hash
engine_deployment_id
engine_definition_id
engine_version
status = ACTIVE
revision
activated_by
activated_at
change_reason
request_id
trace_id
```

The domain continues to reject non-ACTIVE current-effective projections.

## Effective Release serialization and CAS

PostgreSQL retains its advisory lock.

MySQL uses the independent namespace:

```text
approval-effective-release:<tenantId>:<definitionKey>
```

This namespace is intentionally different from:

```text
approval-process-release:<tenantId>:<definitionKey>
```

Both locks can be acquired in the governed order in one transaction without aliasing.

Current projection updates retain:

```text
tenant_id = :tenantId
definition_key = :definitionKey
revision = :expectedRevision
```

and revision advances exactly once.

A stale revision returns `false`. No activation history may be appended after a failed Effective Release CAS.

## Immutable activation history

Every successful effective selection appends exactly one fact to:

```text
ap_approval_release_activation_history
```

Actions remain closed to:

```text
ACTIVATE
ROLLBACK
```

History remains tenant + definition scoped and deterministic:

```text
order by revision desc, activation_id desc
```

`wasActivated` continues to use immutable history to authorize governed rollback targets.

Activation IDs use `JdbcDatabaseValueAdapter` UUID conversion. `activated_at` uses the same nearest-microsecond evidence canonicalization.

## Governed rollback contract

A rollback target must:

- exist as an immutable Release Package;
- have a successfully DEPLOYED deployment projection;
- have lifecycle state `DEPRECATED`;
- have previously appeared in immutable Effective Release activation history.

A successful rollback must atomically:

```text
current ACTIVE -> DEPRECATED
rollback target DEPRECATED -> ACTIVE
Effective Release -> rollback target
previous_release_version -> former current release
Effective Release revision + 1
append ROLLBACK activation history
append lifecycle transitions
audit and idempotency evidence
```

No historical runtime binding is rewritten.

## Explicit deprecation and current-effective removal

Explicit deprecation is valid only for an ACTIVE lifecycle with an exact current Effective Release projection.

The current projection is removed with revision evidence:

```text
delete from ap_approval_effective_release
where tenant_id = :tenantId
  and definition_key = :definitionKey
  and revision = :expectedRevision
```

`ApprovalEffectiveReleaseDeactivationPort` removes only the mutable current projection.

It must never delete:

```text
ap_approval_release_activation_history
```

If the delete returns zero after the lifecycle transition was attempted, the whole caller transaction must roll back:

```text
lifecycle state unchanged
effective projection unchanged
no new lifecycle history
no audit residue
no idempotency residue
```

## Retirement contract

Retirement remains terminal.

Only:

```text
PUBLISHED
DEPRECATED
```

may transition to `RETIRED`.

The release must not be the current Effective Release.

Retirement changes only lifecycle/evidence state. The immutable Release Package remains present and existing runtime bindings are not rewritten.

## Runtime Binding boundary

`ApprovalProcessReleaseDispositionService` reads `ApprovalRuntimeBindingStore.countReleaseUsage(...)` to report existing usage before deprecation/retirement.

P3-G2 does not convert the complete Runtime Binding Store for MySQL. It proves only that the existing count query used by this bounded disposition path remains read-only and does not mutate runtime binding evidence.

Full Runtime Binding Store MySQL compatibility remains outside this slice.

## Transaction rollback contract

The permanent MySQL suite proves rollback after a real lifecycle transition attempt, not merely a mocked precondition failure.

Specifically:

1. activation path transitions lifecycle rows before Effective Release validation;
2. missing deployment then fails inside the same governed transaction;
3. the earlier lifecycle transitions, audit writes and idempotency admission disappear on rollback;
4. explicit deprecation transitions lifecycle before current-effective clear;
5. forced clear CAS failure restores the original ACTIVE lifecycle and current Effective Release;
6. named locks release after rollback.

## Permanent acceptance matrix

The bounded permanent suites are:

```text
JdbcApprovalReleaseLifecycleStoreFactoryTest
JdbcApprovalReleaseLifecycleMySqlContractTest
JdbcApprovalReleaseLifecycleMySqlIntegrationTest
```

The real integration suite uses the accepted shared MySQL 8.4 Testcontainers baseline:

```text
InnoDB
utf8mb4_0900_as_cs
UTC session time
READ COMMITTED
strict SQL mode
datetime(6)
useAffectedRows=false
```

The real suite proves:

- trusted factory selection for lifecycle, Effective Release and deactivation authorities;
- Process Release exact read, active read, deterministic listing and transition history;
- transition UUID round-trip;
- lifecycle timestamp nearest-microsecond canonicalization;
- tenant/case isolation;
- Process Release revision CAS and no history append after stale CAS;
- Process Release transaction-lock fail-closed behavior, blocking and rollback release;
- Effective Release strict initial save;
- Effective Release revision CAS;
- Effective Release UUID/time round-trip;
- immutable activation history and `wasActivated`;
- Effective Release lock fail-closed behavior, blocking and distinct namespace;
- deactivation revision CAS and activation-history preservation;
- full activation switch atomicity and request replay;
- governed rollback to a previously active DEPRECATED release;
- missing deployment rollback after real lifecycle transition attempts;
- concurrent target activation permits exactly one committed switch;
- explicit deprecation clears only current-effective state;
- failed current-effective clear rolls lifecycle/audit/idempotency back;
- PUBLISHED/DEPRECATED retirement is terminal while immutable packages remain;
- PostgreSQL lifecycle/effective/deactivation implementations and PostgreSQL suites remain unchanged.

## Accepted implementation evidence

The accepted implementation Head is:

```text
ff1c4a38d7776132d2b92e31f3b1b5d851f88dae
```

Natural pull-request validation:

```text
Run: 31453802991 / #1401
Conclusion: success
Physical Jobs: 9 / 9 success
```

Focused P3-G2 suites:

```text
JdbcApprovalReleaseLifecycleStoreFactoryTest:
2 / 0 / 0 / 0, 0.009 s

JdbcApprovalReleaseLifecycleMySqlContractTest:
3 / 0 / 0 / 0, 0.012 s

JdbcApprovalReleaseLifecycleMySqlIntegrationTest:
9 / 0 / 0 / 0, 40.994 s
```

Independent Maven evidence reconstruction:

```text
Maven Core: 1469 / 0 / 0 / 0
Persistence JDBC: 498 / 0 / 0 / 0
Combined: 1967 / 0 / 0 / 0
selected persistence test classes: 121
Surefire report classes: 120
expected abstract without report: 1
duplicate selections: 0
non-abstract selected without report: 0
selection coverage: exact
shards: 29 / 31 / 28 / 33
```

Independently verified Run #1401 Artifacts:

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9087337474` | `1058484` | `d61decf1a6f6e592768fa595fbfee7294c360b2f13bbdd591384213c853995f0` |
| Vben | `9087303561` | `18813` | `d7b0576d02932894ba8e42b59dd8c7bc08b753edc55545d8a9b8386fd91fbf0f` |
| Mobile | `9087290345` | `9778` | `f303e299f35f7d113ab1457b365b6b2be6d30ba1d52fc73e67758c56de7558b6` |
| Hygiene | `9087277299` | `17515` | `c58f9ba150e25fb9dc155e5f63909484221b4fa21c0bd80ca95f82cecfb4117a` |

All four ZIPs were independently downloaded; local byte size and SHA-256 match GitHub metadata and ZIP integrity verification succeeded.

The natural pre-acceptance failure trail remains visible:

```text
#1399 / 31453303684
STATIC_HYGIENE / CHECKSTYLE_UNUSED_IMPORT

#1400 / 31453571353
TEST_COMPILE_BUG / STATIC_HELPER_VISIBILITY
```

Neither failed Head was rerun in place. No empty commit, rebase or force push was used.

The detailed permanent evidence record is:

```text
docs/database/MYSQL_8_4_P3_G2_ACCEPTANCE_EVIDENCE.md
```

## Forbidden shortcuts

P3-G2 does not use:

```text
INSERT IGNORE
REPLACE
ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS
same-Head workflow rerun
empty commit workflow trigger
force push
```

The MySQL authorities do not contain PostgreSQL advisory-lock SQL.

## Explicit non-scope

P3-G2 does not implement or imply MySQL compatibility for:

- complete `ApprovalRuntimeBindingStore` persistence;
- complete Approval Design Draft / Definition Version / Compiled Artifact publication path beyond already accepted stores;
- Flowable schema or execution on MySQL;
- process-instance migration execution/reconciliation stores not yet converted;
- notification/SLA/operational-failure stores not yet converted;
- AI and controlled-automation evidence stores not yet converted;
- historical MySQL fixture upgrade/restore rehearsal;
- future V51+ MySQL upgrade contract;
- complete dual-database permanent CI;
- backup/restore, rollback or production incident runbooks;
- performance/query-plan acceptance for all remaining stores;
- MySQL production authorization;
- PR Ready transition;
- merge of PR #92;
- closure of Issue #91.

No later slice is started by this contract.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_G1_RELEASE_PACKAGE_DEPLOYMENT_PROVEN
MYSQL_P3_G2_RELEASE_LIFECYCLE_EFFECTIVE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
