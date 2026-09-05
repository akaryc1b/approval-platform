# MySQL 8.4 P3-G2 Helper Visibility Correction Evidence

## Natural failed Run #1400

```text
Run: 31453571353 / #1400
Head: 630f394d87bfe9e1a79525f2a119a801eecc90d6
Conclusion: failure
Classification: TEST_COMPILE_BUG / STATIC_HELPER_VISIBILITY
```

The previous P3-G2 Checkstyle correction was successful: shared persistence Checkstyle reported zero violations. The Java test compiler then rejected the new G2 integration test before Surefire could execute any persistence test method.

Exact compiler failure:

```text
JdbcApprovalReleaseLifecycleMySqlIntegrationTest.java:[888,25]
await(java.util.concurrent.CountDownLatch)
in JdbcApprovalReleaseLifecycleMySqlIntegrationTest
cannot override await(java.util.concurrent.CountDownLatch)
in MySqlApprovalProjectionStoreIntegrationSupport
attempting to assign weaker access privileges; was package
```

The shared MySQL integration support already exposes a package-private static `await(CountDownLatch)` helper. The G2 subclass accidentally declared another helper with the same signature as `private static`, which Java treats as static method hiding with weaker visibility.

Observed Run impact:

```text
Repository hygiene: success
Vben: success
UniApp: success
Persistence shard 0: failure at testCompile
Persistence shard 1: failure at testCompile
Persistence shard 2: failure at testCompile
Persistence shard 3: failure at testCompile
Maven Core: failure at the same shared testCompile boundary
PostgreSQL evidence aggregate: failure because Maven evidence parts failed
```

Surefire did not begin in `approval-persistence-jdbc`, so Run #1400 contains no MySQL lifecycle/effective-release product semantic failure evidence.

## Correction

Correction commit:

```text
7536f1503d8df8a16c2de0cad5ccb3b2c9b45037
```

Exact test-only change:

```diff
-private static void await(CountDownLatch latch)
+static void await(CountDownLatch latch)
```

This aligns visibility with the inherited package-private helper boundary. No production SQL, lock namespace, revision CAS, state transition, UUID conversion, timestamp canonicalization, server wiring, domain code or application service changed.

No same-Head rerun was requested. No empty commit, rebase or force push was used.

```text
MYSQL_P3_G2_RELEASE_LIFECYCLE_EFFECTIVE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
