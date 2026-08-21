# MySQL 8.4 P3-G1 Tenant Assertion Correction Evidence

## Scope

This record retains the second natural P3-G1 validation failure and its append-only test-only correction. It does not authorize P3-G2, Flowable MySQL, MySQL production support, PR Ready, merge, or Issue closure.

## Failed natural Run #1395

```text
Run: 31450537171 / #1395
Head: 550bc531b2d7c4eab6275e758688ed8ae85c15cf
result: failure
classification: TEST_ASSERTION_BUG / NON_DISTINCT_TENANT_CASE_VALUE
```

The prior #1394 Checkstyle failure was corrected successfully. In #1395:

```text
Repository hygiene: success
Vben TypeScript / production build: success
UniApp TypeScript / H5 / WeChat: success
Java 21 / Maven core: success
Persistence shard 0: success
Persistence shard 1: success
Persistence shard 3: success
Persistence shard 2: failure
```

The new real MySQL suite executed all five methods:

```text
JdbcApprovalReleaseFoundationMySqlIntegrationTest
Tests run: 5
Failures: 1
Errors: 0
Skipped: 0
```

Four methods passed. The only failure was:

```text
releasePackageRoundTripsStrictImmutableIdentityTenantListingUuidAndTime
line 100
expected false but was true
```

The assertion attempted to prove case-sensitive tenant isolation with:

```java
OTHER_TENANT.toLowerCase()
```

but the shared fixture declares:

```java
OTHER_TENANT = "tenant-projection-other"
```

which is already entirely lowercase. The negative lookup therefore used the exact same tenant identity and correctly found the release package.

This failure is not evidence of a MySQL collation or tenant-isolation product defect. The companion Deployment assertion uses the mixed-case `TENANT = "Tenant-Projection-MySQL"` and its lowercase negative lookup passed in the same real MySQL suite.

## Append-only correction

The correction changes only the erroneous Release Package negative-case test input to a genuinely distinct case variant:

```java
OTHER_TENANT.toUpperCase(java.util.Locale.ROOT)
```

No production source, SQL, lock namespace, CAS predicate, UUID conversion, timestamp canonicalization, schema constraint, server wiring, PostgreSQL implementation, Flyway migration, or application/domain behavior is changed.

The failed Head is retained and is not rerun in place.

```text
P3_G1_RUN_1395_FAILURE_RETAINED
TEST_ONLY_ASSERTION_CORRECTION
NO_SAME_HEAD_RERUN
NO_FORCE_PUSH
NO_EMPTY_COMMIT
MYSQL_P3_G1_RELEASE_PACKAGE_DEPLOYMENT_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
