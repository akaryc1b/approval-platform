# MySQL 8.4 P3-G2 Correction Evidence

## Natural failed Run #1399

```text
Run: 31453303684 / #1399
Head: 06809e65ec92e3eb5d2c9a401cf17d9e0d85f8dc
Conclusion: failure
Classification: STATIC_HYGIENE / CHECKSTYLE_UNUSED_IMPORT
```

The P3-G2 implementation Head reached the permanent pull-request workflow naturally. The failure occurred before any Persistence JDBC test method executed.

All four Persistence JDBC shards and Maven Core compile the shared `approval-persistence-jdbc` test source tree. Checkstyle rejected one unused import in the newly added real-MySQL G2 integration test:

```text
JdbcApprovalReleaseLifecycleMySqlIntegrationTest.java:22:8
Unused import - org.springframework.dao.DataAccessException
```

Observed impact:

```text
Repository hygiene: success
Vben: success
UniApp: success
Persistence shard 0: failure at shared Checkstyle
Persistence shard 1: failure at shared Checkstyle
Persistence shard 2: failure at shared Checkstyle
Persistence shard 3: failure at shared Checkstyle
Maven Core: failure at shared Checkstyle
PostgreSQL evidence aggregate: failure because Maven evidence parts failed
```

This Run provides no evidence of a MySQL lifecycle/effective-release semantic failure because Surefire never started in the persistence module.

## Correction

Correction commit:

```text
51bf9f9ce2dc406f2b1685f4ff6c20a121135b1a
```

Exact change:

```diff
-import org.springframework.dao.DataAccessException;
```

No production SQL, lock namespace, revision CAS, state transition, UUID conversion, timestamp canonicalization, server wiring, domain code or application service changed.

No same-Head rerun was requested. No empty commit, rebase or force push was used.

```text
MYSQL_P3_G2_RELEASE_LIFECYCLE_EFFECTIVE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
