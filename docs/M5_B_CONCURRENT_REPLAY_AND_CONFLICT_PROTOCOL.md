# M5-B2 — Concurrent Replay and Conflict Protocol

## Scope and authority

This slice proves the existing M5-B PostgreSQL and JDBC persistence protocol under real concurrent calls. It does not add an executor, worker, Flowable invocation, plan authorization, runtime-binding mutation, API, UI action, scheduler, or automatic retry.

M5-B remains IN_PROGRESS. M5-C is not authorized by this document.

## Test harness

Permanent integration evidence is implemented in:

`JdbcApprovalMigrationProtocolConcurrencyIntegrationTest`

The harness uses:

- PostgreSQL Testcontainers with PostgreSQL 16;
- two independent `JdbcApprovalMigrationProtocolStore` instances;
- separate `JdbcTransactionManager` instances over `DriverManagerDataSource`;
- two real executor threads;
- a `CountDownLatch` ready gate and shared start gate;
- bounded future completion timeouts;
- exact success, replay, conflict, row-count, event-count and sequence assertions.

No test accepts an arbitrary set of exceptions or an unbounded set of final states.

## Closed concurrency outcomes

### Intent creation

| Competition | Required result |
| --- | --- |
| Same intent and same canonical payload | one insert and one exact replay; both return the same authoritative intent; one current row; one revision-1 event |
| Same idempotency key with different canonical payload | one winner and one bounded conflict; one current row; one revision-1 event |
| Same plan hash with different intent identities | one winner and one bounded conflict under the one-plan/one-intent constraint; one current row; one revision-1 event |

### Attempt creation

| Competition | Required result |
| --- | --- |
| Two intents claim the same tenant-scoped approval instance | one active owner and one bounded conflict; no orphan initial event |
| Same attempt identity and same payload | one insert and one exact replay; one current row; one revision-1 event |
| Same attempt identity with changed payload | one winner and one bounded conflict; no orphan initial event |

### Verification evidence

| Competition | Required result |
| --- | --- |
| Same evidence identity and same payload | both calls complete, one immutable row remains, and the second call is exact replay behavior |
| Same evidence identity with changed payload | one winner and one bounded conflict; persisted evidence equals the winner |
| Different evidence identities compete for the same sequence | one winner and one bounded conflict; the next sequence appends successfully; no sequence gap |

### Reconciliation evidence

| Competition | Required result |
| --- | --- |
| Same evidence identity and same payload | both calls complete and one immutable row remains |
| Same evidence identity with changed payload | one winner and one bounded conflict; persisted evidence equals the winner |
| Different identities compete for the same sequence | one winner and one bounded conflict; the next sequence appends successfully; no sequence gap |
| Terminal resolution competes with manual-review evidence | exactly one sequence owner succeeds; if terminal wins, no later evidence is accepted; if manual review wins, one later terminal resolution may close the progression and terminal evidence cannot advance afterward |

The terminal race has two explicitly enumerated database-legal paths. In both paths, exactly one row owns the contested sequence, the loser receives `MigrationProtocolConflictException`, and terminal evidence cannot advance.

## Existing protocol mechanisms proven

The tests exercise the committed V33–V35 protocol without changing those migrations:

- tenant-scoped unique idempotency, plan and current-row identities;
- one active attempt per tenant-scoped approval instance;
- current row and initial event in one short transaction;
- `ON CONFLICT DO NOTHING` only for exact replay identities;
- post-conflict authoritative read and full record equality;
- immutable evidence identity;
- unique per-attempt evidence sequence;
- trigger-enforced gap-free progression;
- trigger-enforced terminal reconciliation closure;
- uniform mapping to `MigrationProtocolConflictException`.

No V36 is required by this slice. M6-A, M6-B, M6-C and M6-D remain independent and do not own a Flyway version.

## Transaction boundary

Every create or append operation owns only a short platform database transaction. Concurrent calls use independent transaction managers and connections. No external engine call is covered by these transactions because this slice performs no engine call.

## Permanent boundary

The Node boundary `m5-migration-persistence-concurrency-boundary.test.mjs` requires the real-thread start gate, every closed scenario above, this protocol document, and the continuing absence of Flowable execution, workers, schedulers, direct `ACT_*` access, force, rollback, or browser/mobile authority.

## Explicit absences

- no worker or claim loop;
- no Flowable adapter or migration call;
- no plan creation, approval or consumption;
- no runtime-binding update;
- no automatic retry, including `UNKNOWN`;
- no API or UI execution control;
- no new workflow;
- no V36;
- no M6 change;
- no Ready, auto-merge, merge, or issue closure.

## Retained failed evidence

Run `30078875680` / #517 is retained as permanent failed evidence. The first concurrent suite proved that an identical intent or attempt can hit a second unique constraint before PostgreSQL applies the statement's named `ON CONFLICT` arbiter. The creator originally mapped that database rejection directly to a bounded conflict, so the two identical-call tests observed one insert and one conflict instead of one insert and one authoritative replay.

The minimal correction performs a post-rollback authoritative read in `JdbcApprovalMigrationIntentCreator` and `JdbcApprovalMigrationAttemptCreator`. It returns replay only when the complete durable record equals the requested value. A missing or changed record remains `MigrationProtocolConflictException`. No retry loop, sleep, schema change, relaxed assertion, or alternate final state was added.
