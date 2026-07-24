# M5 Process Instance Migration Concurrent Command Evidence

M5-A CONCURRENCY SLICE: `CAPABILITY_VALIDATION_ONLY`

Overall conclusion remains: `SUPPORTED_WITH_LIMITATIONS`

This addendum records isolated Flowable 8.0.0 command races. It does not authorize uncoordinated
production execution, M5-B, a migration worker, automatic retry, or any client execution control.

## 1. Scope and method

`FlowableProcessInstanceMigrationConcurrentCommandCapabilityTest` uses only public Flowable services
and two independent Java executor threads. Each command reaches a `CountDownLatch` start gate before
both are released. The test therefore exercises overlapping engine commands rather than a stale
validation followed by a deliberately sequential command.

Each race is repeated twenty times. The test does not require a fixed scheduler winner. Instead,
every trial must produce one complete, closed and internally coherent result. Any duplicated task,
mixed source/target definition, unexplained runtime/history combination, both-command failure, or
missing evidence fails the permanent test.

The evidence remains limited to the embedded H2 engine used by the repository capability suite. It
is not a PostgreSQL locking contract and is not proof for clustered engine nodes.

## 2. Migration versus task completion

The first race starts these commands together for one same-ID user-task instance:

- exact single-instance migration to the target definition;
- completion of the active task captured before the gate opens.

Accepted closed outcomes are:

1. `BOTH_SUCCEEDED_TARGET_COMPLETED`: migration commits before completion, both commands return
   normally, runtime ends, and history references the target definition;
2. `COMPLETION_WON_SOURCE_COMPLETED`: completion commits first, history references the source
   definition, and migration returns an error;
3. `MIGRATION_WON_TARGET_ACTIVE_AFTER_COMPLETE_CONFLICT`: migration commits, completion returns an
   error, and exactly one target task remains active.

No accepted outcome permits a mixed definition, more than one task, an unexplained terminal state,
or an automatic retry of the losing command.

## 3. Concurrent duplicate migrations

The second race starts two independently built migrations for the same instance, same target and
same explicit renamed-task mapping. The final state must always be one target-definition runtime
instance with exactly one target task.

The only accepted command results are:

- `BOTH_MIGRATIONS_ACCEPTED`; or
- `ONE_MIGRATION_WON` with the other command returning an error.

Neither result is an idempotency contract. If both calls are accepted, the engine has explicitly not
provided a unique winner. If one call fails, the error still does not provide a durable platform
idempotency receipt.

## 4. Permanent observed evidence

Permanent workflow Run `30058147323` / run #471 completed successfully at head
`3a2bcb9343029a2eb86a4b45dc791e513dda284c`.

The exact twenty-trial distributions were:

- migration versus completion:
  - `COMPLETION_WON_SOURCE_COMPLETED=6`;
  - `MIGRATION_WON_TARGET_ACTIVE_AFTER_COMPLETE_CONFLICT=14`;
  - `BOTH_SUCCEEDED_TARGET_COMPLETED=0`;
- concurrent duplicate migrations:
  - `ONE_MIGRATION_WON=20`;
  - `BOTH_MIGRATIONS_ACCEPTED=0`.

Every completion race ended as one coherent source-completed or target-active state. Every duplicate
race ended with one target-definition instance and exactly one target task. The losing H2 commands
emitted transaction deadlock/rollback errors. No duplicate task or mixed source/target definition
was observed.

These counts are evidence for this exact engine/database/test shape only. They are not scheduler
probabilities and must not be used as a PostgreSQL or production prediction.

## 5. Governance decision

| Concern | M5-A decision | Required rule |
|---|---|---|
| Uncoordinated migration versus task completion | `UNSUPPORTED` for the initial production scope | A server-side lease/CAS gate must serialize authoritative commands before engine invocation. |
| Detecting a closed concurrent outcome | `SUPPORTED_WITH_LIMITATIONS` | Reconcile command results with runtime, task and history readback; mixed evidence fails closed. |
| Concurrent duplicate migration invocation | `UNSUPPORTED` | One durable platform attempt may invoke the engine; duplicates return stored state and never call Flowable. |
| Losing command retry | `UNSUPPORTED` | A command error after overlap is not proof that nothing committed; reconcile before any new attempt. |
| Cluster/PostgreSQL concurrency behavior | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Embedded H2 evidence must not be generalized into a database or cluster contract. |

## 6. Platform implications

- Validation is not a lock and cannot authorize a later command after another operation wins.
- Every authoritative migration, completion, withdraw, reject and terminate command requires a
  server-side command gate tied to the same process-instance authority.
- The engine call remains outside the platform database transaction, so the platform must persist an
  attempt before invocation and verify public engine state afterward.
- A success response is not sufficient when another command overlaps; runtime, active tasks and
  history must form one sealed result.
- An exception from either command is not safe retry evidence.
- Concurrent duplicate calls must not reach Flowable in a production implementation.
- No browser or mobile request may manufacture the lease, command winner or reconciliation result.

## 7. Remaining limitations

- PostgreSQL and clustered-engine locking behavior;
- migration versus withdraw, reject, terminate, timer firing and external job execution;
- process shapes with parallel scopes, subprocess trees, jobs or timers during the race;
- network timeout while one concurrent command commits;
- production lease expiry and worker crash behavior.

## 8. Slice decision

Current M5-A decision remains `SUPPORTED_WITH_LIMITATIONS`.

This slice does not authorize M5-B, `V33`, production persistence, a worker, execution APIs,
automatic migration, automatic retry of `UNKNOWN`, or production enablement.
