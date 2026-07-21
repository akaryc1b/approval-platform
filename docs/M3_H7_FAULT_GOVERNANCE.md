# M3 H7 Cross-Module Fault Governance

## Scope

H7 verifies rollback isolation across idempotency, audit, notification replay, business Outbox replay, consistency replay and Outbox attempt evidence using real PostgreSQL transactions.

## Fault matrix

The Testcontainers matrix verifies:

- notification replay returns to `DEAD_LETTER` when audit append fails;
- business Outbox replay remains `DEAD` when audit append fails;
- consistency replay does not leave a new check, audit row, chain state or idempotency result when the outer audit fails;
- an Outbox attempt-number conflict leaves the message `IN_FLIGHT`, keeps its worker lease and does not partially increment attempts;
- idempotency result encoding failure rolls back command side effects and the idempotency row;
- duplicate audit event insertion does not advance the tenant hash-chain tail.

Original failure records and attempt evidence remain intact. No test reads or modifies Flowable tables.

The Outbox conflict fixture uses only real state transitions: `DEAD` to governed replay, then `PENDING` to `IN_FLIGHT`, followed by a trigger-level attempt conflict. It does not bypass V23 constraints with an incomplete row.

## Evidence

H7 code head `2f15550a8e7d72957234ae46d63eb9d1b866e6e1` passed permanent workflow run `29764954338`:

- all four workflow jobs passed;
- `JdbcApprovalCrossModuleFaultIntegrationTest`: 6 tests passed;
- `JdbcApprovalOperationalFailureIntegrationTest`: 7 tests passed;
- `JdbcApprovalConsistencyIntegrationTest`: 6 tests passed;
- PostgreSQL module: 128 tests, 0 failures, 0 errors, 0 skipped;
- apps/server: 17 tests, 0 failures, 0 errors, 0 skipped;
- full 16-module reactor: `BUILD SUCCESS`;
- Vben, UniApp, H5 and WeChat verification passed.

Permanent CI first exposed an invalid hand-written Outbox attempt fixture. The corrected fixture now reaches the intended production trigger conflict through the existing state machines.

This is the single H7 governance record and is not edited after creation.
