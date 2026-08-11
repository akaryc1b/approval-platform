# MySQL 8.4 P3-H2 — Migration Attempt Provisioning Acceptance Evidence

## Decision

P3-H2 implementation evidence is accepted at the exact implementation Head below. This record authorizes only the H2 capability marker transition from `STAGED` to `PROVEN` after a docs-only compare and a new natural current-Head validation.

It does not authorize PR Ready, merge, Issue closure, production migration execution, broader M5 migration compatibility, or MySQL production support.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_H1_MIGRATION_BINDING_CAS_PROVEN
MYSQL_P3_H2_MIGRATION_ATTEMPT_PROVISIONING_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

## Exact implementation identity

```text
main:        1747b22123fd71cccd8334853ad7060c6645b443
formal:      agent/mysql-8-4-production-compatibility
impl Head:   b68707fdbecbd42a398dc554c4c4ca5b3ca90253
PR:          #92 Open + Draft + unmerged
Issue:       #91 Open
```

The implementation Head was reached only by non-force fast-forward progression from the accepted H1 Head. No failed Head was rerun in place.

## Exact H2 production scope

P3-H2 converts only:

```text
ApprovalMigrationAttemptProvisioningStore
```

Production additions/wiring:

```text
JdbcApprovalMigrationAttemptProvisioningStoreFactory
JdbcMySqlApprovalMigrationAttemptProvisioningStore
ApprovalMigrationExecutionConfiguration -> trusted provisioning factory
```

The factory derives vendor identity from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`:

```text
PostgreSQL -> existing JdbcApprovalMigrationAttemptProvisioningStore
MySQL      -> JdbcMySqlApprovalMigrationAttemptProvisioningStore
```

Application/domain code remains database-neutral. PostgreSQL provisioning production classes and all applied PostgreSQL Flyway migrations remain unchanged.

## Bounded MySQL authority

The MySQL H2 store owns only the short transaction that materializes initial attempts from already-existing relational authority:

```text
Intent identity/status
+
CONSUMED Plan + Plan Consumption
+
Plan lifetime / selected count
+
sealed Plan selections
+
current Runtime Binding
+
current approval-instance projection
->
initial PENDING Attempt + immutable initial Attempt Event + governed audit
```

The store locks the exact Intent using `FOR UPDATE`, locks the exact consumed Plan/Consumption relation, verifies sealed selection order/count and current source Runtime Binding evidence, creates missing initial attempts with strict inserts, and returns an authoritative replay when provisioning is already complete.

No duplicate-key shortcut is used as the primary concurrency authority.

## Honest upstream boundary

P3-H2 deliberately does not reconstruct unaccepted generic MySQL Plan/Intent payloads merely to claim broader compatibility.

The V50 relational contract independently proves the authority H2 needs. In particular:

- current lifetime is checked through the exact consumed Plan `expires_at`;
- selection cardinality is checked through `Plan.selected_instance_count` against real sealed selection rows;
- exact Intent/Plan definition and source/target release identity are checked relationally;
- the Plan Consumption row binds the consumed Plan to the exact Intent.

Full MySQL Plan creation/authorization/consumption, Intent admission, and generic Plan/Intent payload readback remain outside H2 and remain release blockers.

## Value and transaction semantics

MySQL H2 uses:

```text
JdbcDatabaseValueAdapter
AuditHashCanonicalizer.canonicalInstant
JdbcApprovalMigrationJson
TransactionTemplate
```

The request instant is canonicalized to accepted nearest-microsecond UTC semantics before typed Attempt/Event evidence is created and before `datetime(6)` binding.

Initial attempts are exactly:

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

Each created Attempt receives exactly one matching revision-1 initial Attempt Event with durable evidence.

Audit append occurs only when new attempts are created. Audit failure after real Attempt/Event inserts rolls back the complete local transaction.

## Retained natural failure — #1421

The first natural H2 implementation Head was:

```text
Head:       45523a22319529eedd40fd2cfa6861ebfa1249eb
Run:        31472425741 / #1421
Conclusion: failure
```

The Head was not rerun.

The only failing persistence shard was shard 2. Maven Core, shards 0/1/3, Repository Hygiene, Vben and Mobile succeeded; the PostgreSQL evidence aggregate inherited the failed persistence part.

All five H2 real-MySQL methods failed before the H2 production store was invoked because the test fixture attempted to save the source Runtime Binding without its required source Release Lifecycle FK provenance:

```text
fk_process_runtime_binding_lifecycle
```

Permanent classification:

```text
TEST_FIXTURE_BUG / MISSING_RUNTIME_BINDING_RELEASE_LIFECYCLE_PROVENANCE
```

Correction-1 added only real test provenance:

```text
source Release Package
-> DRAFT -> PUBLISHED at package publishedAt
-> PUBLISHED -> ACTIVE one second later
-> source Runtime Binding
```

The failed Head and Run remain permanently visible. No `FOREIGN_KEY_CHECKS`, schema bypass, force push, rebase, amend, empty commit, or same-Head workflow rerun was used.

Permanent failure record:

```text
docs/database/MYSQL_8_4_P3_H2_CORRECTION_EVIDENCE.md
```

## Successful implementation Run — #1422

Natural Pull Request Run:

```text
Run:        31473038094 / #1422
Head:       b68707fdbecbd42a398dc554c4c4ca5b3ca90253
Conclusion: success
```

All nine physical Jobs completed successfully:

| Job | ID | Result |
| --- | ---: | --- |
| Repository hygiene | `93720410538` | success |
| Vben TypeScript / production build | `93720410605` | success |
| UniApp TypeScript / H5 / WeChat | `93720410634` | success |
| Java 21 / Maven core | `93720410665` | success |
| Persistence JDBC / shard 0 | `93720410669` | success |
| Persistence JDBC / shard 3 | `93720410706` | success |
| Persistence JDBC / shard 2 | `93720410711` | success |
| Persistence JDBC / shard 1 | `93720410816` | success |
| Java 21 / Maven / PostgreSQL | `93721255845` | success |

Shard 2 is the H2 shard and passed after correction-1, proving the missing lifecycle provenance was corrected without weakening the real FK graph.

## Independent #1422 Artifact verification

All four final workflow Artifacts were independently downloaded. Local byte size and SHA-256 exactly match GitHub metadata and every ZIP passes archive integrity verification.

All are bound to:

```text
branch: agent/mysql-8-4-production-compatibility
Head:   b68707fdbecbd42a398dc554c4c4ca5b3ca90253
expiry: 2026-11-09T08:24:13Z
```

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9094197160` | `1101798` | `456d27334b48a82b85b6b9592e9ff856d63dc9ecf0b79cd8775c64c37847d045` |
| Vben | `9094136459` | `18923` | `66175fa9808dd83d2de73770cac9645bb37e61c3823d301c0efe6d4d019db2c5` |
| Mobile | `9094116918` | `9809` | `cd19435eeefd7b94620cddb0d50fdb59e27678ed2a83963e5e22fcb09cecc1f8` |
| Hygiene | `9094090952` | `17495` | `2a0839fb5c195127367b3fd2c483ec247ce0755fcd7648cd2d0e7ecba17b041e` |

## Independent Persistence reconstruction

The downloaded Maven Artifact was independently parsed rather than relying on the workflow UI summary.

Deterministic selection:

```text
shard 0: 30
shard 1: 33
shard 2: 33
shard 3: 35
selected classes: 131
unique selected: 131
duplicate selections: 0
Surefire report classes: 130
expected abstract without report: 1
non-abstract selected without report: 0
selection coverage: exact
```

The sole selected abstract class without a report is:

```text
io.github.akaryc1b.approval.persistence.jdbc.AbstractJdbcApprovalMigrationProtocolIntegrationTest
```

Persistence result reconstructed from the 130 Surefire XML reports:

```text
531 tests
0 failures
0 errors
0 skipped
aggregate reported test time: 905.457 s
```

## H2 focused matrix

All three H2 suites are selected exactly once in shard 2:

```text
JdbcApprovalMigrationAttemptProvisioningStoreFactoryTest
JdbcApprovalMigrationAttemptProvisioningStoreMySqlContractTest
JdbcApprovalMigrationAttemptProvisioningStoreMySqlIntegrationTest
```

Exact reconstructed result:

```text
Factory:      2 / 0 / 0 / 0, 0.019 s
Contract:     3 / 0 / 0 / 0, 0.006 s
Real MySQL:   5 / 0 / 0 / 0, 12.217 s
Focused H2:  10 / 0 / 0 / 0
```

Real MySQL method timings:

```text
createsExactAttemptReplaysAndCanonicalizesTime:                 0.442 s
concurrentProvisioningHasOneCreatorAndOneAuthoritativeReplay:   0.290 s
runtimeBindingDriftFailsClosedBeforeAttemptCreation:             0.280 s
auditFailureRollsBackRealAttemptAndEventInserts:                 0.304 s
tenantMismatchFailsClosedWithoutLeakingAuthority:                0.286 s
```

These methods prove exact creation/replay, tenant isolation, source Runtime Binding drift rejection, real cross-connection serialization through the Intent row, rollback after actual Attempt/Event inserts, and canonical `datetime(6)` evidence.

## Independent Maven Core reconstruction

The downloaded `maven-core.log` was independently reconstructed two ways:

```text
337 unique class-level Surefire reports -> 1469 / 0 / 0 / 0
22 module-level Surefire summaries      -> 1469 / 0 / 0 / 0
```

No class-level report was duplicated.

Combined accepted implementation result:

```text
Maven Core:      1469 / 0 / 0 / 0
Persistence:      531 / 0 / 0 / 0
Combined:        2000 / 0 / 0 / 0
```

## PostgreSQL non-regression

Run #1422 confirms the existing PostgreSQL provisioning path remains accepted through the unchanged production implementation selected by the trusted factory.

P3-H2 did not edit:

```text
JdbcApprovalMigrationAttemptProvisioningStore
JdbcApprovalMigrationProtocolStore
JdbcApprovalMigrationAttemptStore
JdbcApprovalMigrationAttemptRepository
JdbcApprovalMigrationAttemptCreator
JdbcApprovalMigrationAttemptTransitioner
```

No PostgreSQL Flyway migration was edited.

## Explicit non-claims

P3-H2 does not prove or claim MySQL compatibility for:

- Attempt Claim / claim scanning / `SKIP LOCKED`;
- lease renewal, expiry takeover or stale-worker fencing;
- D3 engine execution or real Flowable migration dispatch;
- D4 exact verification;
- D6 reconciliation;
- D7 orchestration;
- complete Plan/Intent/admission persistence compatibility;
- complete migration subsystem compatibility;
- historical MySQL upgrade/restore;
- complete permanent dual-vendor CI;
- production Flowable/server acceptance;
- operations/performance/backup/restore;
- MySQL production support.

## Acceptance gate

The implementation evidence is sufficient for a docs-only H2 status transition, subject to all of the following:

1. acceptance branch differs from implementation Head only by permanent documentation;
2. formal branch is non-force fast-forwarded to that exact acceptance Head;
3. the new `PROVEN` current Head receives a new natural Pull Request workflow Run;
4. that Run completes 9/9 physical Jobs successfully;
5. its final Artifacts are independently downloaded and verified again;
6. H2 focused 10/10 and full Maven/Persistence totals are reconstructed again;
7. PR #92 remains Draft/unmerged and Issue #91 remains Open.
