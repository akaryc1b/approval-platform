# MySQL 8.4 P3-G1 Correction Evidence

## Scope

This record retains the natural P3-G1 Approval Release Package + Deployment validation failure and its append-only correction. It does not authorize P3-G2, Flowable MySQL, MySQL production support, PR Ready, merge, or Issue closure.

## Failed natural Run #1394

```text
Run: 31450308077 / #1394
Head: 9fce72a4cfd6fc9d4e2ffafb423a06e86fe539d9
result: failure
classification: STATIC_HYGIENE / CHECKSTYLE_UNUSED_IMPORT
```

The production and test slice was successfully checked out and deterministically sharded. Repository hygiene, Vben TypeScript/production build and UniApp TypeScript/H5/WeChat all completed successfully.

The Java/Persistence paths were stopped by one shared Checkstyle violation before any P3-G1 test method could execute:

```text
JdbcMySqlApprovalReleaseDeploymentStore.java:15:8
Unused import - java.util.UUID
[UnusedImports]
```

The same source-level violation appeared independently in:

```text
Java 21 / Maven core
Persistence JDBC / shard 0
Persistence JDBC / shard 1
Persistence JDBC / shard 2
Persistence JDBC / shard 3
```

The final `Java 21 / Maven / PostgreSQL` evidence aggregation consequently failed because the required Maven/Persistence evidence parts were unsuccessful. That aggregate failure is not an independent PostgreSQL product regression.

Because Checkstyle failed before the persistence tests, Run #1394 provides no pass/fail evidence about the new MySQL Release Package or Deployment runtime semantics.

## Append-only correction

The correction removes only the unused import:

```java
import java.util.UUID;
```

from:

```text
JdbcMySqlApprovalReleaseDeploymentStore.java
```

No SQL, lock namespace, CAS predicate, UUID conversion, timestamp canonicalization, server wiring, domain contract, application service, PostgreSQL implementation, Flyway migration, or test assertion is changed by this correction.

The failed Head is retained and will not be rerun in place. Validation continues only from a new append-only Head.

```text
P3_G1_RUN_1394_FAILURE_RETAINED
NO_SAME_HEAD_RERUN
NO_FORCE_PUSH
NO_EMPTY_COMMIT
MYSQL_P3_G1_RELEASE_PACKAGE_DEPLOYMENT_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
