# MySQL 8.4 P3-G3 D5 Scope Correction Evidence

## Natural failed correction run

```text
Run: 31457936280 / #1407
Head: 53b55d2021ccdc451944ad1f73ad6f1c06abd74c
Conclusion: failure
Classification: TEST_SCOPE_BUG / D5_RUNTIME_BINDING_GUARD_OUTSIDE_G3
```

No failed Head was rerun in place. No empty commit, rebase or force push was used.

## What #1407 proved

Correction-1 successfully repaired the missing Process Release lifecycle provenance exposed by #1406.

The G3 real-MySQL suite advanced from the earlier FK errors to:

```text
JdbcApprovalRuntimeBindingStoreMySqlIntegrationTest:
5 tests
4 passed
1 assertion failed
0 errors
```

The successful methods prove that the real MySQL baseline can now execute:

- strict Runtime Binding creation with complete Release Package / ACTIVE lifecycle / DEPLOYED deployment / instance provenance;
- UUID and `datetime(6)` round-trip;
- tenant/case isolation;
- release count and deterministic pagination;
- real release-bound `PurchasePaymentApplicationService.start(...)` commit and replay;
- exact `RuntimeBindingRecordingAuditEventSink` package/deployment binding;
- failure after the actual binding insert rolls platform projection, binding, audit and idempotency evidence back;
- release-bound projection lookup fails closed when the initial binding is absent.

The trusted factory also remained green:

```text
JdbcApprovalRuntimeBindingStoreFactoryTest:
2 / 0 / 0 / 0
```

## Exact failed assertion

The only failed assertion expected arbitrary direct SQL `UPDATE` of `ap_process_runtime_binding` to be rejected by the database.

MySQL accepted the update, so JUnit reported:

```text
Expected org.springframework.dao.DataAccessException to be thrown,
but nothing was thrown.
```

This was not a failure of `JdbcMySqlApprovalRuntimeBindingStore`: the G3 store has no update API and still performs strict initial inserts only.

## Why the assertion exceeded G3 scope

The current PostgreSQL history no longer treats the Runtime Binding row as permanently immutable after initial creation.

M5-D5 V44 deliberately evolves the row for governed migration completion:

```text
binding_revision
last_migration_attempt_id
last_verification_id
last_verification_evidence_hash
```

and explicitly performs:

```text
drop trigger trg_process_runtime_binding_immutable on ap_process_runtime_binding;
```

before installing the D5 migration-specific guarded CAS protocol and append-only runtime-binding evidence history.

Therefore the correct compatibility split is:

```text
P3-G3:
initial Runtime Binding Store creation/read authority
strict insert-only store API
complete initial provenance
start/replay/rollback semantics

later D5 compatibility slice:
controlled Runtime Binding row mutation
binding_revision CAS
migration attempt / verification lineage
guarded mutation protocol
runtime-binding evidence history
conflict / completion replay semantics
```

G3 must not freeze a pre-D5 raw-row immutability assumption that PostgreSQL itself no longer has.

## Correction-2

Correction-2 removes only the out-of-scope direct SQL update/delete expectations from the G3 integration suite.

The permanent G3 test continues to require:

- duplicate initial binding insertion fails;
- missing approval-instance FK provenance fails;
- the store exposes only initial save and read/query operations;
- successful initial bindings remain exact and hash-bound;
- D5 mutation authority is not implemented or claimed.

Production code is unchanged.

## Honest global status

```text
MYSQL_P3_G3_RUNTIME_BINDING_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

The MySQL D5 Runtime Binding CAS/guard protocol remains explicitly incomplete and must be accepted in its own later slice before MySQL production support can be claimed.
