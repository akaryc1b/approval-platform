# MySQL 8.4 P3-H6 Reconciliation Execution Acceptance Evidence

## Status and authorization boundary

This record accepts only the bounded P3-H6 implementation slice:

```text
D6 Reconciliation Execution Semantic Equivalence
PostgreSQL 16 -> MySQL 8.4
```

It does not accept MySQL 8.4 as a production-supported database, does not accept the complete dual-database roadmap, does not implement D7 bounded orchestration, and does not authorize PR #92 to leave Draft or be merged.

The exact implementation Head is:

```text
1cac6c42237807a6d29ea151bc95752146a908f5
```

The exact implementation baseline is:

```text
main: 779c4fbd09dcf17d45cc523e725222797cc5cb85
```

The implementation Head is an ordinary two-parent merge commit:

```text
parent 1: 0d111f77e3a37c8e160984337055a3e74b4f9cf2
parent 2: 82cd2fef36b5def5efba903599ac6774524bf0d7
```

No squash, rebase, force update, workflow dispatch, same-Head rerun, empty trigger commit, direct `main` push, Ready transition, PR merge, deployment, or production promotion occurred.

## Remaining-protocol inventory decision

The live-source inventory established the direct H5 downstream sequence as follows:

| Protocol authority | PostgreSQL authority | MySQL authority before H6 | Result |
| --- | --- | --- | --- |
| D3 engine execution and ambiguous finalization | existing PostgreSQL execution store | accepted MySQL execution store from H4 | complete before H6 |
| D4 exact verification persistence | existing PostgreSQL exact-verification store | accepted MySQL exact-verification store from H5 | complete before H6 |
| D5 runtime binding CAS | existing PostgreSQL CAS store | existing MySQL CAS store selected by trusted metadata factory | complete before H6 |
| D6 reconciliation execution | `JdbcApprovalMigrationReconciliationExecutionStore` | none | first live PostgreSQL-only authority gap |
| D7 bounded orchestration | existing PostgreSQL orchestration authority | not part of this slice | remains blocked after H6 |

D6 was selected because it is the first continuous authority after the accepted H5/D4 boundary that had a PostgreSQL production implementation but no MySQL 8.4 semantic-equivalent implementation. D6 accepts only a durable ambiguous `UNKNOWN` predecessor, performs one read-only reconciliation observation, and explicitly delegates any target-binding mutation to the separately governed D5 CAS authority. D7 is downstream and was not implemented.

## Accepted production changes

P3-H6 adds exactly these production boundaries:

```text
JdbcApprovalMigrationReconciliationExecutionStoreFactory
JdbcMySqlApprovalMigrationReconciliationExecutionStore
ApprovalMigrationExecutionConfiguration -> trusted D6 factory wiring
```

The application authority remains database-neutral:

```text
ApprovalMigrationReconciliationStore
ApprovalMigrationReconciliationService
```

Database vendor selection is derived only from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`. Tenant IDs, HTTP data, JSON, headers, AI, Connector, Event, Template, browser, mobile, or other business input cannot select the database dialect.

No Flyway migration, schema, table, column, index, trigger, workflow, D5 behavior, D7 behavior, Flowable behavior, AI capability, Connector capability, or Event capability was changed.

## MySQL D6 semantic contract

The MySQL 8.4 implementation preserves the PostgreSQL D6 authority and fail-closed behavior:

1. the exact tenant and attempt scope is serialized by a transaction-bound MySQL named lock;
2. the durable predecessor must be the exact expected revision in `UNKNOWN` or `RECONCILING` state with `EngineOutcome.UNKNOWN`;
3. the immutable ambiguous engine request/outcome evidence must exist and prove that an engine call may have occurred without a returned outcome;
4. changed-payload replay is rejected by strict request-hash equality;
5. the first D6 preparation appends reconciliation, lease, lease-event, attempt-transition, attempt-event, and audit evidence atomically;
6. an unexpired active lease blocks another worker;
7. an expired lease can be taken over only through strict revision progression and append-only lease-event evidence;
8. finalization requires the exact attempt, reconciliation, lease worker, lease revision, request hash, and unexpired lease authority;
9. source observation closes the attempt without automatic migration retry;
10. target observation never mutates runtime binding and requires the separately governed D5 binding CAS;
11. read failure, contradictory evidence, incomplete evidence, and unknown observation remain manual-review outcomes;
12. observation, reconciliation result, lease release, lease event, optional attempt conclusion, and audit evidence commit or roll back as one transaction;
13. UUID and UTC `datetime(6)` values use the existing vendor value adapter and canonical instant boundary;
14. canonical JSON and versioned evidence-hash labels remain aligned with the accepted PostgreSQL records;
15. migration redispatch, automatic retry of unknown outcomes, silent duplicate swallowing, and overwrite shortcuts remain forbidden.

The production SQL contains none of the following shortcuts or PostgreSQL-only tokens:

```text
INSERT IGNORE
REPLACE
ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS
pg_advisory_*
::jsonb
::text
FOR UPDATE OF
```

## Test-first history

The non-triggering staging branch was:

```text
agent/mysql-8-4-p3-h6-reconciliation-execution-staging
```

It had no Pull Request and no workflow Run. Tests were committed before production code:

| Commit | Purpose |
| --- | --- |
| `a0d8c27105d52b5d55df677eb23bd13b4cca185d` | D6 trusted vendor factory contract |
| `84a2cd4ba8cb10009825ab70f35bc7aaff56b2bc` | MySQL D6 SQL, portability, strictness, and wiring contract |
| `187be18511fd9f700f0401fbd3b06a6bf089cda6` | real MySQL 8.4 D6 integration matrix |
| `e70f76049f759d46c20ce23eb6a8b3e8b5670cd3` | trusted metadata D6 factory |
| `8f729ef79a005e9aec5877c37c9dcabde80b93c7` | MySQL 8.4 D6 reconciliation execution store |
| `82cd2fef36b5def5efba903599ac6774524bf0d7` | server wiring through the D6 factory |

The real MySQL tests construct the durable predecessor through the existing release, runtime-binding, H3 authority, provisioning, claim/fence, and D3 ambiguous-finalization stores. They do not manufacture a fake attempt row or bypass the accepted H1-H5 immutable lineage.

## Implementation Run

The only implementation full CI Run was the natural PR synchronize Run:

```text
Run ID:      31769375625
Run number:  #1454
Attempt:     1
Event:       pull_request
PR:          #92
Head:        1cac6c42237807a6d29ea151bc95752146a908f5
Base:        779c4fbd09dcf17d45cc523e725222797cc5cb85
Conclusion:  completed / success
```

No same-Head rerun or workflow dispatch occurred.

All nine physical Jobs succeeded:

| Job | ID | Result |
| --- | ---: | --- |
| Vben TypeScript / production build | `94671892110` | success |
| UniApp TypeScript / H5 / WeChat | `94671892131` | success |
| Persistence JDBC / shard 1 | `94671892134` | success |
| Persistence JDBC / shard 0 | `94671892173` | success |
| Java 21 / Maven core | `94671892188` | success |
| Persistence JDBC / shard 2 | `94671892194` | success |
| Repository hygiene | `94671892211` | success |
| Persistence JDBC / shard 3 | `94671892265` | success |
| Java 21 / Maven / PostgreSQL aggregation | `94672501178` | success |

## Independent test reconstruction

The final Maven Artifact was downloaded independently. The selected-test manifests and every Surefire XML report were parsed again rather than relying only on green Job conclusions.

```text
Java Core:                              1469 / 0 / 0 / 0
Persistence JDBC:                        590 / 0 / 0 / 0
Combined:                               2059 / 0 / 0 / 0

selected persistence test classes:       146
unique selected classes:                  146
Surefire report classes:                 145
expected abstract without report:          1
abstract class: AbstractJdbcApprovalMigrationProtocolIntegrationTest
duplicate selection count:                 0
non-abstract selected without report:       0
extra report classes:                       0
selection coverage:                     exact
aggregate reported persistence time: 1085.587 s
```

Deterministic shard distribution:

```text
shard 0: 36
shard 1: 35
shard 2: 35
shard 3: 40
unique: 146 / 146
```

The D6 suites were selected exactly once:

```text
shard 0 -> JdbcApprovalMigrationReconciliationExecutionStoreIntegrationTest
shard 0 -> JdbcApprovalMigrationReconciliationExecutionStoreMySqlContractTest
shard 0 -> JdbcApprovalMigrationReconciliationExecutionStoreMySqlIntegrationTest
shard 3 -> JdbcApprovalMigrationReconciliationExecutionStoreFactoryTest
```

The focused D6 matrix executed with no skip:

| Suite | Tests | Failures | Errors | Skipped | Reported time |
| --- | ---: | ---: | ---: | ---: | ---: |
| `JdbcApprovalMigrationReconciliationExecutionStoreFactoryTest` | 2 | 0 | 0 | 0 | 1.247 s |
| `JdbcApprovalMigrationReconciliationExecutionStoreIntegrationTest` | 7 | 0 | 0 | 0 | 6.035 s |
| `JdbcApprovalMigrationReconciliationExecutionStoreMySqlContractTest` | 3 | 0 | 0 | 0 | 0.006 s |
| `JdbcApprovalMigrationReconciliationExecutionStoreMySqlIntegrationTest` | 6 | 0 | 0 | 0 | 31.339 s |
| **Focused total** | **18** | **0** | **0** | **0** | **38.627 s** |

The real MySQL methods prove:

- source observation, no automatic retry, and exact replay without a second engine read;
- target observation with no binding mutation and mandatory separate D5 CAS;
- read-failure persistence as manual review with no migration redispatch;
- one strict expired-lease takeover;
- exact tenant identity and stale revision rejection;
- prepare-audit rollback;
- finalize-audit rollback with no partial observation or lease release.

The retained PostgreSQL D6 regression methods prove the corresponding source, target, read-failure, lease, append-only evidence, and rollback semantics without weakening the accepted PostgreSQL authority.

## Independently verified Artifacts

Every final Artifact ZIP was downloaded independently. Local byte counts and SHA-256 values exactly match GitHub metadata, and every ZIP passed full integrity verification.

| Artifact | ID | Bytes | ZIP entries | SHA-256 |
| --- | ---: | ---: | ---: | --- |
| Maven | `9207601841` | `1167049` | `302` | `22bd61e7038a1f002cb67846e4e15d1549644068d4eecfd5320e46510eeb212c` |
| Hygiene | `9207596447` | `152572` | `6` | `d5de920258e020f786f150c2e26a10284b8899ae5b3d6722b3f3b0cb577ecb40` |
| Vben | `9207563828` | `18982` | `8` | `da4f8c58693ec2801d37948488836e4d05d904dff645853cb0f622470ac581ae` |
| Mobile | `9207548237` | `9803` | `5` | `921739318bd599cd57e4a78ddb767c889be5ce42003e75c12a6c007cf961da71` |

All four Artifacts are bound to:

```text
branch: agent/mysql-8-4-production-compatibility
Head:   1cac6c42237807a6d29ea151bc95752146a908f5
```

The recorded expiration time is:

```text
2026-11-12T04:16:48Z
```

## Failure and correction trail

P3-H6 required no implementation correction Run:

```text
implementation Run A: #1454 -> success
same-Head reruns: 0
workflow dispatches: 0
failed H6 Heads: 0
correction commits after Run A: 0
```

The second and final available full CI slot is reserved only for the natural documented-Head verification created by this acceptance record. It is not a repeated implementation confirmation.

## Known remaining boundary

P3-H6 does not claim completion of the MySQL compatibility roadmap. At minimum, the following remain outside this acceptance:

- D7 bounded orchestration semantic equivalence;
- remaining PostgreSQL-only JDBC authorities identified by future bounded inventory gates;
- historical MySQL upgrade and restore acceptance;
- real executable-server and Flowable scenarios on MySQL 8.4;
- permanent complete dual-vendor CI matrices;
- query-plan, concurrency, performance, backup, restore, rollback, security, and operational acceptance;
- formal G1/G2 dual-database acceptance, controlled PR merge, and post-main verification.

PR #92 must remain Open, Draft, and unmerged. Issues #91, #82, and #62 must remain Open. No MySQL production-support, dual-database production-acceptance, deployment, or production-promotion claim is authorized.

## Documented-Head rule

This document changes the formal branch Head after the successful implementation Run. Therefore Run `31769375625 / #1454` proves the exact implementation Head, not this document commit or its subsequent formal merge commit.

The H6 evidence becomes final only after one natural `pull_request` Run succeeds at the exact documented Head created by the ordinary two-parent acceptance merge. No third full CI Run is authorized for this gate.

```text
MYSQL_P3_H6_RECONCILIATION_EXECUTION_IMPLEMENTATION_ACCEPTED
H6_IMPLEMENTATION_HEAD_GREEN
H6_DOCUMENTED_HEAD_VALIDATION_REQUIRED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
```

After the exact documented Head completes its natural Run successfully, the following conditional marker becomes satisfied without another document-only commit:

```text
MYSQL_P3_H6_RECONCILIATION_EXECUTION_PROVEN_AFTER_CURRENT_HEAD_SUCCESS
```
