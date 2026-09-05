# MySQL 8.4 P3-H3 — Correction Evidence

## Status

```text
MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

This record permanently retains the first natural P3-H3 failure and its forward-only correction. It does not mark H3 proven and does not authorize Ready, merge, Issue closure, production migration execution or MySQL production support.

## Failed Head

```text
Head:       c6ef21667a7f6975795e54eb4cf0a9b111af3ac0
Run:        31482891770 / #1425
Conclusion: failure
```

Run #1425 was a natural pull-request workflow run. The failed Head is not rerun. No empty commit, amend, rebase, force push or history rewrite is used.

## Classification

```text
TEST_FIXTURE_API_BUG / H2_LIFECYCLE_HELPER_SIGNATURE_DRIFT
```

The H3 real-MySQL integration test invoked the already accepted H2 source-lifecycle fixture using an obsolete draft signature:

```text
seedActiveSourceRelease(dataSource, sourceRelease, workerId, happenedAt)
```

The accepted H2 helper signature is:

```text
seedActiveSourceRelease(dataSource, sourceRelease, workerId)
```

H2 intentionally derives the Release Lifecycle timeline from the immutable Release Package `publishedAt` evidence instead of accepting an unrelated caller-supplied time.

## Physical impact

The error was a Java `testCompile` failure before Surefire executed the H3 real-MySQL suite.

Because every persistence shard compiles the complete test source set, all four Persistence jobs failed from the same single source error. Maven Core reached the persistence module and failed from the same test compilation error. Repository Hygiene, Vben and Mobile remained successful; the PostgreSQL aggregate inherited the incomplete Maven evidence.

There is no H3 Claim Store, `FOR UPDATE SKIP LOCKED`, lease, Fence, ClaimBatch or Intent-transition product failure evidence at this Head.

## Forward-only correction

Correction-1 removes only the obsolete fourth `Instant` argument from the H3 integration fixture call. Production source is unchanged.

The corrected fixture therefore uses the exact already accepted H2 source Release Lifecycle authority without redefining its time contract.

## Non-claims

This correction does not claim:

- H3 semantic acceptance;
- successful MySQL claim or lease execution;
- D3/D4/D6/D7 compatibility;
- MySQL production support;
- PR Ready or merge authorization;
- Issue #91 closure.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_H2_MIGRATION_ATTEMPT_PROVISIONING_PROVEN
MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
