# MySQL 8.4 P3-G2 Acceptance Evidence

## Accepted capability

```text
MYSQL_P3_G2_RELEASE_LIFECYCLE_EFFECTIVE_PROVEN
```

This record accepts only the bounded P3-G2 Process Release lifecycle, Effective Release current projection, immutable activation history and current-effective deactivation CAS capability on MySQL 8.4.

It does not authorize overall MySQL production support, PR Ready, merge or Issue closure.

## Exact implementation identity

```text
main: 1747b22123fd71cccd8334853ad7060c6645b443
formal branch: agent/mysql-8-4-production-compatibility
accepted implementation Head: ff1c4a38d7776132d2b92e31f3b1b5d851f88dae
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

The accepted implementation includes:

```text
JdbcApprovalProcessReleaseStoreFactory
JdbcMySqlApprovalProcessReleaseStore
JdbcApprovalEffectiveReleaseStoreFactory
JdbcMySqlApprovalEffectiveReleaseStore
JdbcApprovalEffectiveReleaseDeactivationPortFactory
JdbcMySqlApprovalEffectiveReleaseDeactivationPort
ApprovalProcessReleaseLifecycleConfiguration trusted bindings
ApprovalReleaseDeploymentConfiguration trusted Effective Release binding
```

The existing PostgreSQL lifecycle/effective/deactivation implementations remain unchanged.

## Retained natural failure trail

### Run #1399 — static hygiene

```text
Run: 31453303684 / #1399
Head: 06809e65ec92e3eb5d2c9a401cf17d9e0d85f8dc
Conclusion: failure
Classification: STATIC_HYGIENE / CHECKSTYLE_UNUSED_IMPORT
```

The shared persistence Checkstyle gate rejected one unused import in the new G2 integration test before Surefire began.

Correction:

```text
51bf9f9ce2dc406f2b1685f4ff6c20a121135b1a
```

Only the unused test import was removed. Production behavior was unchanged.

### Run #1400 — test helper visibility

```text
Run: 31453571353 / #1400
Head: 630f394d87bfe9e1a79525f2a119a801eecc90d6
Conclusion: failure
Classification: TEST_COMPILE_BUG / STATIC_HELPER_VISIBILITY
```

Shared Checkstyle passed. Test compilation then rejected a subclass static helper because it used weaker visibility than the inherited package-private helper.

Correction:

```text
7536f1503d8df8a16c2de0cad5ccb3b2c9b45037
```

Only test helper visibility changed. Production behavior was unchanged.

Neither failed Head was rerun in place. No empty commit, force push or rebase was used.

## Accepted implementation Run #1401

```text
Run: 31453802991 / #1401
Head: ff1c4a38d7776132d2b92e31f3b1b5d851f88dae
Conclusion: success
Trigger: natural pull_request validation
Physical Jobs: 9 / 9 success
```

Job identities:

| Job | ID | Result |
| --- | ---: | --- |
| Java 21 / Maven core | `93663374676` | success |
| UniApp TypeScript / H5 / WeChat | `93663374679` | success |
| Repository hygiene | `93663374687` | success |
| Persistence JDBC / shard 0 | `93663374710` | success |
| Persistence JDBC / shard 2 | `93663374718` | success |
| Persistence JDBC / shard 1 | `93663374737` | success |
| Persistence JDBC / shard 3 | `93663374741` | success |
| Vben TypeScript / production build | `93663374764` | success |
| Java 21 / Maven / PostgreSQL | `93663870117` | success |

## Permanent P3-G2 focused suites

```text
JdbcApprovalReleaseLifecycleStoreFactoryTest
2 / 0 / 0 / 0
0.009 s
selected exactly once on shard 2

JdbcApprovalReleaseLifecycleMySqlContractTest
3 / 0 / 0 / 0
0.012 s
selected exactly once on shard 2

JdbcApprovalReleaseLifecycleMySqlIntegrationTest
9 / 0 / 0 / 0
40.994 s
selected exactly once on shard 1
```

The real MySQL integration suite proves:

1. Process Release lifecycle round-trip, exact tenant isolation, revision CAS, transition UUID and timestamp canonicalization;
2. Effective Release round-trip, history, revision CAS, activation UUID/time evidence and deactivation CAS;
3. lifecycle and Effective Release locks fail closed outside transactions, block competitors and remain distinct namespaces;
4. governed activation commits lifecycle, current-effective, activation history, audit and idempotency evidence together;
5. governed rollback reactivates a previously active DEPRECATED release and appends ROLLBACK activation evidence;
6. missing deployment fails after real lifecycle transition attempts and rolls lifecycle/audit/idempotency evidence back;
7. concurrent targets allow exactly one committed governed switch;
8. explicit deprecation removes only the current-effective projection, while retirement from DEPRECATED and PUBLISHED preserves immutable Release Packages;
9. forced current-effective clear CAS failure rolls lifecycle, audit and idempotency changes back.

## Complete backend test reconstruction

The final Maven evidence ZIP was independently downloaded and parsed.

```text
Maven Core:
1469 / 0 / 0 / 0

Persistence JDBC:
498 / 0 / 0 / 0

Combined:
1967 / 0 / 0 / 0
```

Persistence discovery and deterministic selection:

```text
selected persistence test classes: 121
Surefire report classes: 120
expected abstract selected without report: 1
duplicate selections: 0
report classes not selected: 0
non-abstract selected classes without report: 0
selection coverage: exact
aggregate reported persistence time: 925.818 s
```

Shard distribution:

```text
shard 0: 29
shard 1: 31
shard 2: 28
shard 3: 33
unique: 121 / 121
```

The only selected class without a Surefire report is the known abstract integration base:

```text
io.github.akaryc1b.approval.persistence.jdbc.AbstractJdbcApprovalMigrationProtocolIntegrationTest
```

## Independently verified implementation Artifacts

All four final Run #1401 Artifact ZIPs were independently downloaded. Local byte size and SHA-256 match GitHub metadata exactly and every ZIP passes integrity verification.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9087337474` | `1058484` | `d61decf1a6f6e592768fa595fbfee7294c360b2f13bbdd591384213c853995f0` |
| Vben | `9087303561` | `18813` | `d7b0576d02932894ba8e42b59dd8c7bc08b753edc55545d8a9b8386fd91fbf0f` |
| Mobile | `9087290345` | `9778` | `f303e299f35f7d113ab1457b365b6b2be6d30ba1d52fc73e67758c56de7558b6` |
| Hygiene | `9087277299` | `17515` | `c58f9ba150e25fb9dc155e5f63909484221b4fa21c0bd80ca95f82cecfb4117a` |

Every Artifact is bound to:

```text
branch: agent/mysql-8-4-production-compatibility
Head: ff1c4a38d7776132d2b92e31f3b1b5d851f88dae
Run: 31453802991
```

GitHub expiration time is `2026-11-09T02:54:57Z`.

## Accepted lock and CAS boundaries

Process Release lock namespace:

```text
approval-process-release:<tenantId>:<definitionKey>
```

Effective Release lock namespace:

```text
approval-effective-release:<tenantId>:<definitionKey>
```

Lifecycle transition CAS:

```text
tenant_id = :tenantId
definition_key = :definitionKey
release_version = :releaseVersion
revision = :expectedRevision
```

Effective Release update CAS:

```text
tenant_id = :tenantId
definition_key = :definitionKey
revision = :expectedRevision
```

Current-effective removal CAS:

```text
tenant_id = :tenantId
definition_key = :definitionKey
revision = :expectedRevision
```

No automatic retry, broad duplicate upsert, foreign-key disabling or application-layer vendor branch was added.

## Current authorization boundary

P3-G2 is accepted as a bounded persistence/application transaction capability only.

Still not authorized or proven:

- complete Runtime Binding Store MySQL compatibility;
- remaining Approval Design / release-adjacent PostgreSQL-only stores;
- real Flowable MySQL schema/deployment/execution;
- complete process migration evidence compatibility;
- remaining notification/SLA/operational-failure stores;
- AI and controlled-automation evidence stores;
- historical MySQL upgrade/restore rehearsal;
- future V51+ upgrade contract;
- complete dual-vendor permanent CI;
- production query-plan/performance gates;
- backup/restore and operational rollback rehearsal;
- overall MySQL production authorization;
- PR Ready transition;
- merge of PR #92;
- closure of Issue #91.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_G1_RELEASE_PACKAGE_DEPLOYMENT_PROVEN
MYSQL_P3_G2_RELEASE_LIFECYCLE_EFFECTIVE_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
