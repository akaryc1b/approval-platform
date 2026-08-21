# MySQL 8.4 P3-H7-GA Run A Assertion Correction Evidence

Status: `RUN_A_FAILED / CORRECTION_STAGING / NOT_ACCEPTED`

## 1. Exact Run A identity

```text
main:                 779c4fbd09dcf17d45cc523e725222797cc5cb85
implementation Head:  a9e796c52559435323f84eff747da98fa2e9e245
formal branch:        agent/mysql-8-4-production-compatibility
PR:                   #92 / Open / Draft / Unmerged
Run:                  31793671225 / #1468
Event:                pull_request
Attempt:              1
Base:                 779c4fbd09dcf17d45cc523e725222797cc5cb85
Conclusion:           failure
```

Run A was created only by the natural PR synchronize event after an ordinary two-parent Merge
Commit. It was not a `workflow_dispatch`, same-Head rerun or empty trigger commit.

## 2. Classification

```text
STATIC_CONTRACT_FALSE_POSITIVE
STALE_FACTORY_TYPE_ASSERTION
NOT_PRODUCT_BUG
NOT_MIGRATION_BUG
NOT_ENVIRONMENT_FAILURE
```

The accepted MySQL D7 factory boundary returns:

```text
JdbcMySqlCanonicalApprovalMigrationOrchestrationStore
```

The H7 real integration test still asserted the pre-canonicalization internal delegate type:

```text
JdbcMySqlApprovalMigrationOrchestrationStore
```

The factory contract test already expected the canonical wrapper. Run A therefore failed only at
the stale duplicate type assertion in
`JdbcApprovalMigrationOrchestrationStoreMySqlIntegrationTest`.

## 3. Job matrix

| Job | Job ID | Result |
| --- | ---: | --- |
| Repository hygiene | `94745922776` | success |
| Java 21 / Maven core | `94745922900` | success |
| Persistence JDBC / shard 0 | `94745922866` | failure |
| Persistence JDBC / shard 1 | `94745923003` | success |
| Persistence JDBC / shard 2 | `94745922939` | success |
| Persistence JDBC / shard 3 | `94745922840` | success |
| Vben TypeScript / production build | `94745922952` | success |
| UniApp TypeScript / H5 / WeChat | `94745922886` | success |
| Java 21 / Maven / PostgreSQL aggregation | `94746781826` | failure |

The aggregation failed because one required persistence shard failed. It did not hide or override
the shard result.

## 4. Gate-specific execution proof

The new H7-GA authority tests all executed without skips:

| Suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `MySqlV50TriggerInstallationAuthorityTest` | 6 | 0 | 0 | 0 |
| `MySqlV50TriggerInstallationAuthorityIntegrationTest` | 1 | 0 | 0 | 0 |
| `MySql84ProductionTestServerTest` | 2 | 0 | 0 | 0 |
| `MySqlFlywayCleanMigrationIntegrationTest` | 8 | 0 | 0 | 0 |
| `MySqlV50SchemaUniqueConstraintNameTest` | 1 | 0 | 0 | 0 |

This proves that Run A reached and passed:

- the closed, read-only trigger-authority preflight;
- the default binary-logged `trust=OFF` fail-closed path before platform DDL;
- the governed binary-logged `trust=ON` positive server posture;
- the exact MySQL V50 checksum `1718152560`;
- installation of all ten D7 append-only triggers.

The existing real D1 → D2 → D7 integration suite executed 6 tests. Its first method completed all
setup and factory construction, then failed only at the stale type assertion before invoking the
already-covered behavior path.

## 5. Test reconstruction

```text
Java Core:                           1469 / 0 / 0 / 0
Persistence JDBC:                    614 / 1 / 0 / 0
Combined:                           2083 / 1 / 0 / 0

selected persistence classes:         153
unique selected classes:              153
duplicate selections:                   0
Surefire report classes:              152
expected abstract without report:       1
non-abstract selected without report:   0
selection coverage:                  exact
```

Deterministic shard selection:

```text
shard 0: 41
shard 1: 36
shard 2: 36
shard 3: 40
```

The only failing suite and method were:

```text
JdbcApprovalMigrationOrchestrationStoreMySqlIntegrationTest
prepareDispatchAndFinalizeUseRealClaimFenceAndStrictReplay
```

Exact failure:

```text
expected JdbcMySqlApprovalMigrationOrchestrationStore
but was JdbcMySqlCanonicalApprovalMigrationOrchestrationStore
```

## 6. Independently verified failure Artifacts

Every final Run A Artifact was independently downloaded. The local byte count and SHA-256 exactly
matched GitHub metadata, and every ZIP passed integrity verification.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9216591198` | `1201998` | `027366dc79ff2638bc44ed10f2ad8006cb0e23d5bee026292b4fdd61c13ff9c9` |
| Hygiene | `9216586699` | `152582` | `018196e1de3280e37a9879f5997b9f5243e4e97dc9daf4f684c462a098a17752` |
| Vben | `9216523169` | `18884` | `a7df156af59a14b8f9ac96080712cea95d506d1eff4bd264cf1259d1e14f519a` |
| Mobile | `9216506786` | `9816` | `51a4e93fc0eda9b6c321f72d6510621fca2188dee3f431a46cc564ddf5125355` |

These are failure-trail Artifacts and must not be cited as gate acceptance proof.

## 7. Bounded correction

Correction commit:

```text
2d655a1cab2641aa00aa4b429a6a329d45353109
```

The correction changes only the expected factory product type to:

```text
JdbcMySqlCanonicalApprovalMigrationOrchestrationStore
```

No production Java, Flyway migration, MySQL trigger, checksum, server setting, timestamp contract,
D7 hash, replay, lease, fence, tenant or lineage behavior changed. No assertion was removed, no
integration test was converted to a mock and no coverage was excluded.

## 8. Remaining CI budget

```text
Run A: consumed / failure
Run B: one natural correction Run authorized
Run C: forbidden
same-head rerun: forbidden
workflow_dispatch: forbidden
empty trigger commit: forbidden
```

No `PROVEN`, `ACCEPTED`, `SUPPORTED`, Ready, merge, issue closure, deployment or production
promotion claim is authorized before the exact correction Head completes Run B and its permanent
Artifacts are independently verified.
