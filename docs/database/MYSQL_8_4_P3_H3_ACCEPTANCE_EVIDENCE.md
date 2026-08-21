# MySQL 8.4 P3-H3 — Migration Attempt Claim and Lease Fencing Acceptance Evidence

## Decision

P3-H3 implementation evidence is accepted at the exact implementation Head below. This record authorizes only the H3 capability marker transition from `STAGED` to `PROVEN` after a docs-only compare and a new natural current-Head validation.

It does not authorize PR Ready, merge, Issue closure, production migration execution, D3/D4/D6/D7 compatibility or MySQL production support.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_H2_MIGRATION_ATTEMPT_PROVISIONING_PROVEN
MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

## Exact implementation identity

```text
main:        1747b22123fd71cccd8334853ad7060c6645b443
formal:      agent/mysql-8-4-production-compatibility
impl Head:   8e76716d3ceefb181ef94cd3de1448224c08ab5a
PR:          #92 Open + Draft + unmerged
Issue:       #91 Open
```

The implementation Head was reached only by non-force fast-forward progression from the accepted H2 Head. Failed Heads were not rerun in place.

## Exact H3 production scope

P3-H3 converts only:

```text
ApprovalMigrationAttemptClaimStore
```

Production additions/wiring:

```text
JdbcApprovalMigrationAttemptClaimStoreFactory
JdbcMySqlApprovalMigrationAttemptClaimStore
JdbcMySqlApprovalInstanceCommandFence.acquireMigrationLock(...)
ApprovalMigrationExecutionConfiguration -> trusted claim factory
```

The package-private command-fence extension reuses the exact already accepted H1 lock namespace and transaction-bound lock manager:

```text
approval-instance-command:v1:<tenantId>:<approvalInstanceId>
```

It does not change the application fence port or PostgreSQL production behavior.

## Product semantics proven on real MySQL 8.4

The accepted implementation proves:

- trusted PostgreSQL/MySQL claim-store selection from JDBC metadata;
- bounded MySQL `FOR UPDATE SKIP LOCKED` candidate selection;
- H2-provisioned `PENDING` Attempt -> `CLAIMED` revision-CAS;
- durable Attempt transition evidence;
- ACTIVE migration command Fence + FenceEvent creation;
- exact ClaimBatch persistence, including durable empty batches;
- exact request replay and changed request-hash rejection;
- bounded relational reconstruction of the claim-visible Intent;
- PENDING Intent -> RUNNING relational + typed-payload transition;
- shared H1 instance command lock and business-command fencing;
- current-owner lease renewal;
- expiry takeover by a new worker;
- stale previous-owner rejection after takeover;
- concurrent workers yielding one claimed Attempt and one legitimate empty batch;
- tenant-scoped fail-closed access;
- claim-audit rollback of Attempt/Fence/Intent/ClaimBatch state;
- renewal-audit rollback of Attempt/Fence lease mutation;
- canonical UUID text and nearest-microsecond UTC datetime(6) evidence;
- PostgreSQL implementation and permanent PostgreSQL coverage unchanged.

No `FOREIGN_KEY_CHECKS`, `INSERT IGNORE`, `REPLACE`, `ON DUPLICATE KEY UPDATE`, PostgreSQL `ON CONFLICT`, application-layer vendor branch or direct Flowable table access was introduced.

## Natural validation trail

### Run #1425 — failed before H3 Surefire execution

```text
Run:        31482891770 / #1425
Head:       c6ef21667a7f6975795e54eb4cf0a9b111af3ac0
Conclusion: failure
Classification:
TEST_FIXTURE_API_BUG / H2_LIFECYCLE_HELPER_SIGNATURE_DRIFT
```

The H3 integration test used an obsolete fourth argument when invoking the accepted H2 source-lifecycle fixture. All persistence shards and Maven Core were blocked during test compilation because every shard compiles the complete test source set. H3 product tests did not execute. The failed Head was not rerun.

Permanent evidence:

```text
docs/database/MYSQL_8_4_P3_H3_CORRECTION_EVIDENCE.md
```

Correction-1 only aligned the test fixture call with the accepted H2 helper signature. Production code did not change.

### Run #1426 — real MySQL semantics passed; static contract false-positive

```text
Run:        31483447770 / #1426
Head:       e723eb7fa87b14cb128f167398bbb932f49880e0
Conclusion: failure
Classification:
STATIC_CONTRACT_FALSE_POSITIVE / UNBOUNDED_ON_CONFLICT_TOKEN
```

Focused evidence at #1426:

```text
JdbcApprovalMigrationAttemptClaimStoreFactoryTest:
2 / 0 / 0 / 0

JdbcApprovalMigrationAttemptClaimStoreMySqlIntegrationTest:
6 / 0 / 0 / 0, 16.457 s

JdbcApprovalMigrationAttemptClaimStoreMySqlContractTest:
3 tests / 1 assertion failure / 0 errors / 0 skipped
```

All six real MySQL claim/lease methods passed naturally. The only failure was a static source scan for the bare text `on conflict`, which crossed the normal Java token boundary `Exception conflict(...)`; no PostgreSQL `ON CONFLICT` SQL existed.

Permanent evidence:

```text
docs/database/MYSQL_8_4_P3_H3_STATIC_CONTRACT_CORRECTION_EVIDENCE.md
```

Correction-2 retained the PostgreSQL-upsert prohibition but narrowed the assertion to actual SQL shapes: `on conflict (`, `on conflict do ` and `on conflict on constraint `. Production code and real MySQL tests did not change. The failed Head was not rerun.

### Run #1427 — implementation acceptance

```text
Run:        31484274254 / #1427
Head:       8e76716d3ceefb181ef94cd3de1448224c08ab5a
Conclusion: completed / success
Physical jobs: 9 / 9 success
```

All physical jobs succeeded:

```text
Repository hygiene                         success
Vben TypeScript / production build        success
UniApp TypeScript / H5 / WeChat           success
Java 21 / Maven core                      success
Persistence JDBC / shard 0                success
Persistence JDBC / shard 1                success
Persistence JDBC / shard 2                success
Persistence JDBC / shard 3                success
Java 21 / Maven / PostgreSQL aggregate    success
```

## Focused H3 matrix at accepted implementation Head

```text
JdbcApprovalMigrationAttemptClaimStoreFactoryTest:
2 / 0 / 0 / 0, 0.089 s
selected shard 1

JdbcApprovalMigrationAttemptClaimStoreMySqlContractTest:
3 / 0 / 0 / 0, 0.306 s
selected shard 3

JdbcApprovalMigrationAttemptClaimStoreMySqlIntegrationTest:
6 / 0 / 0 / 0, 16.369 s
selected shard 2

Focused total:
11 / 0 / 0 / 0
```

## Independent persistence reconstruction

The final Maven artifact for #1427 was independently downloaded and parsed rather than relying on the Actions summary.

```text
selected persistence classes:             134
unique selected classes:                  134
duplicate selections:                       0
Surefire report classes:                  133
expected abstract without report:           1
non-abstract selected without report:       0
selection coverage:                     exact

shard 0: 30
shard 1: 34
shard 2: 34
shard 3: 36

Persistence JDBC:
542 / 0 / 0 / 0
aggregate reported persistence time: 975.105 s
```

The only selected class without a Surefire report is the expected abstract integration base:

```text
io.github.akaryc1b.approval.persistence.jdbc.AbstractJdbcApprovalMigrationProtocolIntegrationTest
```

## Independent Maven Core reconstruction

`maven-core.log` was independently parsed in two separate ways:

```text
per-test-class report lines:
1469 / 0 / 0 / 0

per-module summary lines:
1469 / 0 / 0 / 0
```

Therefore:

```text
Maven Core:    1469 / 0 / 0 / 0
Persistence:    542 / 0 / 0 / 0
Combined:      2011 / 0 / 0 / 0
```

## Independently verified implementation Artifacts

All four final #1427 ZIPs were independently downloaded. Local byte size and SHA-256 exactly match GitHub metadata, and every ZIP passes archive integrity verification.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9098542203` | `1114546` | `69fda9a5692ab41d7d250694b3babf83fd36700ad1c63433bda29f98c54f3664` |
| Vben | `9098494378` | `18885` | `9c808c4300bd472e1ba42b12896d00a03c1b0167c2dbcdd0a6aa486d9ec65469` |
| Mobile | `9098467342` | `9793` | `554344351f94104ce8cb416a48e92163452f3dc046ef53fb4a03359603a7cf03` |
| Hygiene | `9098452743` | `17513` | `db3b8c1b1920c371352f6ec8c8fb5f9d835e1195480a3d1525e03457ffd312cc` |

All artifacts are bound to branch `agent/mysql-8-4-production-compatibility` and Head `8e76716d3ceefb181ef94cd3de1448224c08ab5a`.

## Append-only discipline

P3-H3 used no:

```text
same-Head rerun
empty commit workflow trigger
amend
rebase
force push
force merge
```

The failed #1425 and #1426 Heads remain permanent evidence. Corrections moved only forward through new commits and non-force fast-forward formal-branch updates.

## Remaining non-scope / release blockers

P3-H3 does not prove or claim:

- generic `ApprovalMigrationProtocolStore` MySQL compatibility;
- migration Plan/Intent creation/admission compatibility as a whole;
- D3 `ApprovalMigrationEngineExecutionStore` compatibility;
- real Flowable migration dispatch;
- D4 exact-verification compatibility;
- D6 reconciliation compatibility;
- D7 orchestration compatibility;
- historical MySQL upgrade/restore acceptance;
- complete permanent dual-database CI;
- operational/performance/backup/restore acceptance;
- MySQL production support;
- PR Ready, merge or Issue #91 closure.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_H2_MIGRATION_ATTEMPT_PROVISIONING_PROVEN
MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
