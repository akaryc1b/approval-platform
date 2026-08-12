# MySQL 8.4 P3-H5 Staging Evidence

## Status

This record is staging evidence only. It is not formal acceptance and does not claim that H5 has passed Maven, PostgreSQL integration, MySQL 8.4 Testcontainers, permanent CI, artifact verification, Ready review, merge, deployment, or production promotion.

```text
MYSQL_P3_H5_MIGRATION_EXACT_VERIFICATION_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```

## Source authority

H5 staging was created from the accepted H4 formal Head:

```text
formal branch: agent/mysql-8-4-production-compatibility
source Head: eb8fc4b82a0a244418b112bdb71008b9ce179cae
source accepted Run: 31557032908 / #1432
source Run conclusion: success
```

The H4 Run remains predecessor evidence only. It is not H5 proof.

## H5-R0 PostgreSQL equivalence correction

H5-R0 classified the retained PostgreSQL D3 rejected-finalization behavior as:

```text
PRODUCT_BUG + TEST_GAP
```

Append-only test-first history:

```text
c6b733e5a2d1b529a4a8148c1c64fe9241969998
  test(db-compat): reproduce migration D3 equivalence gap

e74c965c4e860b10f43dba7a96262d35bb0f946c
  fix(db-compat): align PostgreSQL rejected migration finalization
```

The production correction changes only the mutable Attempt request reference for a persisted `REJECTED` outcome. Immutable H4 request/outcome evidence remains retained.

## H5 MySQL implementation scope

The staging candidate adds or changes only the bounded D4 compatibility surface:

- `JdbcApprovalMigrationExactVerificationStoreFactory`;
- `JdbcMySqlApprovalMigrationExactVerificationStore`;
- `ApprovalMigrationExecutionConfiguration` D4 wiring to the trusted factory;
- PostgreSQL D3 rejected-finalization correction and regression;
- MySQL H5 factory, contract, schema and real integration tests;
- H5 contract and this staging evidence record.

No H5 Flyway migration, D6 reconciliation implementation, D7 orchestration implementation, workflow change, public endpoint, `ACT_*` access, deployment logic or production-promotion logic is added.

## Real H2 -> H3 -> H4 -> H5 lineage

The H5 real-MySQL integration fixture does not directly insert a `VERIFYING` Attempt. It enters D4 through the existing accepted authorities:

```text
H2 provisioning
-> H3 claim + active migration Fence
-> H4 prepare immutable Engine Request
-> H4 returned Engine Outcome
-> Attempt VERIFYING
-> H5 prepare
-> bounded verification snapshot
-> H5 finalize
```

The retained H4 real-MySQL suite continues to prove that stale tenant/Attempt/Fence, Runtime Binding drift and Plan target drift cannot create the H4 immutable request lineage consumed by H5.

## H4 immutable evidence integrity hardening

H5 staging additionally verifies the complete immutable H4 Engine Request / Engine Outcome before first D4 finalization authority is accepted.

Test-first hardening history:

```text
784d0969db2d8b69bec9b0ba76cc507ce1a622ea
  test(mysql): require complete H4 lineage verification in H5

2a4f3f594662c5b936b55555a2c17112dcc6938e
  fix(mysql): verify complete H4 immutable lineage before H5

6a4352954358f08873cf9279fb8070b3c3507768
  docs(db-compat): record complete H4 evidence validation in H5

d1cfa278d832a728d222574bbebf76c446dcfcac
  test(mysql): reject corrupted H4 evidence before H5

89ebafd5a19879e043f9b9b8c20aab2d9d1aa872
  test(mysql): retain real H4 corruption regression in H5
```

The MySQL D4 store now verifies relational/payload equivalence for the H4 request and returned outcome, validates request-to-outcome Attempt/Fence revision continuity, and recomputes:

```text
m5-engine-request-v1
m5-engine-request-evidence-v1
m5-engine-outcome-v1
```

The real MySQL corruption regression first creates a genuine H2 -> H3 -> H4 `VERIFYING` Attempt and then proves two corruption classes are rejected before any H5 evidence is written:

- H4 Engine Request JSON payload no longer matches its relational target deployment evidence;
- H4 Engine Outcome relational `outcome_hash` no longer matches the accepted H4 outcome hash protocol.

Both cases must leave the Attempt in `VERIFYING` and retain the single immutable H4 request/outcome rows without creating a D4 evidence row.

This does not re-read mutable Plan or Runtime Binding authority and does not create a MySQL-only migration decision. H4 remains the owner of target selection; H5 validates the immutable evidence that H4 froze.

## Stored D4 replay integrity hardening

The MySQL replay path now self-validates stored D4 evidence before `requireExactReplay` can return it.

Append-only history:

```text
7be13e1b5253e372081f6d86440a9051bda7be19
  test(mysql): require H5 replay hash revalidation

fa78345caf2a44f37cb909a3773f1ec58aed6ce8
  fix(mysql): revalidate stored H5 evidence hashes on replay

079914ecce24a0434c09e645a039b87c4df13304
  docs(db-compat): require H5 replay evidence self-validation
```

Before stored D4 evidence is accepted, MySQL recomputes:

```text
m5-exact-verification-request-v1
m5-exact-verification-evidence-v1
```

The request hash is reconstructed from persisted tenant, Attempt, worker, expected Attempt revision, expected Fence revision and request id. The evidence hash is reconstructed from the stored verification identity, H4 request/outcome ids, source/target definitions, classification, snapshot hash and request hash.

This does not re-open historical command authority. The old Fence does not need to remain active and the current Attempt does not need to remain at the pre-finalization revision; therefore exact-target and reconciliation replay semantics remain aligned with PostgreSQL D4.

## Current focused test surface

The H5 staging candidate contains:

```text
JdbcApprovalMigrationEngineExecutionRejectedFinalizationIntegrationTest
JdbcApprovalMigrationExactVerificationStoreFactoryTest
JdbcApprovalMigrationExactVerificationStoreMySqlContractTest
JdbcApprovalMigrationExactVerificationStoreMySqlIntegrationTest
MySqlV50H5ExactVerificationSchemaContractTest
```

The real H5 MySQL integration covers:

- true H2 -> H3 -> H4 -> H5 lineage;
- exact-target classification and strict replay;
- source-runtime mismatch into `RECONCILING`;
- wrong tenant, stale Attempt revision, stale Fence and wrong worker rejection;
- client classification rejection through the server-derived Application contract;
- corrupted H4 request payload and outcome hash rejection before D4 evidence;
- deterministic concurrent finalization with one authoritative effect;
- audit failure rolling back evidence, Attempt transition and Attempt event;
- UUID / `datetime(6)` / JSON round-trip through existing compatibility primitives.

The static contract additionally pins:

- trusted vendor selection;
- PostgreSQL 16 / MySQL 8.4 selection and unsupported vendor/version rejection;
- no PostgreSQL-only SQL in the MySQL store;
- no `INSERT IGNORE`, `REPLACE` or broad upsert success path;
- no direct `ACT_*` access;
- no D6 reconciliation invocation;
- no fake H5 Attempt insertion or PostgreSQL trigger bypass in the MySQL success fixture;
- complete H4 immutable request/outcome evidence validation and hash protocols;
- retention of the real H4 corruption regression;
- D4 stored request/evidence hash recomputation before replay.

## Permanent CI discovery expectation

The existing persistence selector discovers Surefire-compatible test names automatically, so the H5 test classes require no workflow edit.

Relative to accepted H4 #1432, the expected class-selection delta is five additional persistence test classes. Before actual Run A this is only an expectation, not evidence.

No staging branch workflow Run has been authorized or used as a compiler/debugger.

## Validation blocker

The current execution container cannot obtain an executable repository checkout:

- no repository checkout is mounted;
- `github.com`, `api.github.com` and `raw.githubusercontent.com` are not resolvable/reachable from the container network;
- no usable proxy is configured;
- repository ZIP retrieval through the connected GitHub read surface does not expose a local source archive;
- Java 21 is present, but Maven/repository dependencies required for the real build are not locally available.

Therefore the following remain unexecuted and must not be represented as green:

```text
focused Maven/checkstyle verification
PostgreSQL rejected-finalization regression
real MySQL 8.4 H5 Testcontainers suite
adjacent H1-H4 regression execution
git diff --check in a real checkout
```

This is a validation-environment blocker, not a product success or failure classification.

## Formal CI budget

H5 retains the explicit two-run maximum:

```text
Run A:
  one natural PR synchronize after a fully validated staging candidate is integrated
  into agent/mysql-8-4-production-compatibility

Run B:
  only if Run A exposes a real issue requiring a new correction commit
```

Forbidden shortcuts remain:

```text
no workflow_dispatch
no empty trigger commit
no same-Head rerun
no rebase
no force push
no direct main push
```

At this staging record:

```text
H5 full CI Runs: 0
H5 permanent artifacts: none
formal branch updates from H5: 0
```

## Gate

Until executable local/focused verification is real and green, the candidate must remain on the non-triggering staging branch.

```text
MYSQL_P3_H5_MIGRATION_EXACT_VERIFICATION_STAGED
HOLD_FORMAL
NO_RUN_A_YET
NO_READY
NO_MERGE
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```
