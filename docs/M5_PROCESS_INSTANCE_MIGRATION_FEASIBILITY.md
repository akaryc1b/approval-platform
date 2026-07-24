# M5 Process Instance Migration Feasibility

M5-A STATUS: `CAPABILITY_VALIDATION_ONLY`

M5-A EVIDENCE GATE: `COMPLETE_PENDING_EXPLICIT_ACCEPTANCE`

Overall conclusion: `SUPPORTED_WITH_LIMITATIONS`

This document consolidates all eight isolated M5-A evidence slices for Flowable 8.0.0. It records
the final technical feasibility decision and the minimum governance constraints indicated by
permanent tests. It does not authorize M5-B, production migration persistence, an executor, a
worker, an execution API, automatic migration, automatic retry, or client execution controls.

## 1. Verified repository and engine baseline

- Current verified `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`.
- Latest permanently validated engine-evidence head before this consolidation:
  `3a2bcb9343029a2eb86a4b45dc791e513dda284c`.
- Flowable dependency baseline: `org.flowable:flowable-bom:8.0.0`.
- Flyway remains continuous through `V32`; M5-A adds no `V33`.
- The only automatic PR/main validation path remains
  `.github/workflows/approval-platform-validation.yml`.
- All migration execution experiments remain under test sources.
- Production code does not query or modify Flowable `ACT_*` tables.
- No production migration Controller, endpoint, worker, browser command, mobile command, lease,
  idempotency store, attempt store, or reconciliation implementation is introduced.
- Issues #13 and #14 remain Open, and PR #58 remains Open + Draft pending explicit stage acceptance.

The main documentation baselines were incorporated through merge commits only; no rebase or force
update was used.

## 2. Decision model

M5-A distinguishes three different questions:

1. **Engine capability** — can the exact Flowable 8.0.0 public API perform or expose the behavior?
2. **Verifiability** — can public runtime, task, management and history APIs prove the result without
   querying internal tables?
3. **Initial production-safe scope** — can the platform safely expose the behavior after applying
   server-side command serialization, immutable evidence and fail-closed reconciliation?

Status vocabulary:

- `SUPPORTED`: directly verified and acceptable without an additional graph-specific prohibition.
- `SUPPORTED_WITH_LIMITATIONS`: verified for exact shapes or readback rules, but production use must
  be restricted by graph, state, command-gate or verification constraints.
- `UNSUPPORTED`: outside the initial safe scope or shown to produce unsafe/incomplete evidence.
- `UNKNOWN_REQUIRES_MORE_EVIDENCE`: not safely classified by the current public-API evidence.

A public method existing is not sufficient evidence of production safety.

## 3. Public API findings

Flowable 8.0.0 exposes process-instance migration through public APIs including:

- `ProcessEngine#getProcessMigrationService()`;
- `ProcessMigrationService#createProcessInstanceMigrationBuilder()`;
- `ProcessInstanceMigrationBuilder#validateMigration(String)`;
- `ProcessInstanceMigrationBuilder#migrate(String)`;
- definition-wide synchronous and batch migration methods;
- explicit one-to-one, one-to-many and many-to-one activity mappings;
- migration documents and validation results;
- migration-time variables for expression evaluation.

The reviewed public migration API does **not** provide:

- an official rollback operation;
- an idempotency key or exactly-once contract;
- an invocation receipt;
- a compare-and-set precondition against platform state;
- a transaction shared with the platform database;
- a promise that jobs, timers, subscriptions or arbitrary complex graph shapes remain safe.

Exact single-instance migration is real, but universal safe migration is not.

## 4. Permanent engine evidence

### 4.1 First slice: public surface and basic user-task mapping

`FlowableProcessInstanceMigrationCapabilityTest` verifies:

- exact one-instance validation and migration;
- same-ID automatic mapping;
- explicit one-to-one mapping for a renamed task;
- target-definition and active-task readback;
- preservation of a simple process variable;
- absence of a rollback method on the public migration builder.

### 4.2 Second slice: runtime evidence, suspension, ended instances and receive tasks

`FlowableProcessInstanceMigrationEvidenceCapabilityTest` verifies, for exact direct shapes:

- same-ID task and execution identity preservation;
- process, execution-local and task-local variable readback;
- candidate-user and candidate-group identity links;
- runtime and selected history references after migration;
- technical preservation of suspension, while the initial platform policy still rejects suspended
  instances;
- ended-instance rejection;
- receive-task eligibility even when historic user tasks exist and no active user task remains.

### 4.3 Third slice: parallel mappings

Permanent tests verify:

- one source activity to two target activities;
- two source activities to one target activity;
- a same-topology parallel process with one completed branch and one active branch;
- stale validation rejection after task completion;
- multi-instance migration remains outside the accepted scope.

### 4.4 Fourth slice: embedded subprocess and Call Activity

Permanent tests verify:

- movement from a root task into an embedded subprocess;
- movement from an embedded subprocess back to a root task;
- independent migration of one called child while its parent remains on the source definition;
- movement of a called child into the parent using the public call-activity mapping option.

Parent-to-child Call Activity creation and automatic recursive migration of a parent/child tree
remain unsupported.

### 4.5 Fifth slice: timer, boundary timer and pending job evidence

Permanent tests verify:

- same-ID intermediate timer-catch migration with one target-definition timer job and a fixed due
  date;
- adding a non-interrupting boundary timer around an active user task;
- removing that boundary timer while keeping the task active;
- a pending async job can remain bound to the source definition after the process instance moves to
  the target definition.

The pending-job split binding is deterministic unsafe evidence. Async and pending-job instances are
excluded from the initial safe scope.

### 4.6 Sixth slice: duplicate invocation and UNKNOWN reconciliation

Permanent tests verify:

- the exact same public migration document can be accepted again after the instance already reached
  the target;
- replay acceptance is not an idempotency contract;
- exact public readback can classify `SOURCE_STATE_CONFIRMED`, `TARGET_STATE_CONFIRMED`, or
  `RECONCILIATION_REQUIRED`;
- target process state mixed with a source-bound job must fail closed;
- an exception or disconnected response never authorizes blind retry.

### 4.7 Seventh slice: missing, terminated and history-only states

Permanent tests classify:

- `MISSING_NO_EVIDENCE`;
- `HISTORY_ONLY_SOURCE_COMPLETED`;
- `HISTORY_ONLY_SOURCE_TERMINATED`;
- `HISTORY_ONLY_TARGET_COMPLETED`;
- `HISTORY_ONLY_TARGET_TERMINATED`.

Runtime absence alone is never proof of `NOT_APPLIED`. Definition reference, end time, delete reason
and applicable historic tasks must agree. Terminal history is not an active migration success.

### 4.8 Eighth slice: concurrent engine commands

`FlowableProcessInstanceMigrationConcurrentCommandCapabilityTest` runs two real two-thread races,
with both commands released from the same `CountDownLatch` gate and twenty trials per race.

Permanent Run `30058147323` / #471 observed:

- migration versus task completion:
  - `COMPLETION_WON_SOURCE_COMPLETED = 6`;
  - `MIGRATION_WON_TARGET_ACTIVE_AFTER_COMPLETE_CONFLICT = 14`;
  - no duplicate task, mixed definition or unexplained runtime/history result;
- concurrent duplicate migrations:
  - `ONE_MIGRATION_WON = 20`;
  - every final state contained one target-definition instance and exactly one target task.

H2 emitted transaction deadlock/rollback errors for losing commands. This proves that overlapping
commands can produce a coherent winner in the tested embedded engine, but it does not provide a
platform command gate, durable idempotency receipt, PostgreSQL contract or cluster guarantee.
Uncoordinated production execution therefore remains unsupported.

## 5. Final capability matrix

| # | Scenario | Final M5-A status | Permanent evidence and required limitation |
|---:|---|---|---|
| 1 | Single user task activity mapping | `SUPPORTED` | Same-ID and explicit one-to-one migration are permanently verified. |
| 2 | Same activity name but different ID | `SUPPORTED_WITH_LIMITATIONS` | Names are not authoritative; immutable activity IDs and an explicit reviewed mapping are required. |
| 3 | Multiple active activities | `SUPPORTED_WITH_LIMITATIONS` | Exact one-to-many and many-to-one shapes are verified; arbitrary execution trees are not. |
| 4 | Parallel branches | `SUPPORTED_WITH_LIMITATIONS` | Tested parallel shapes only; active and completed branch evidence must match. |
| 5 | Parallel gateway with completed branch evidence | `SUPPORTED_WITH_LIMITATIONS` | One completed/one active branch shape is verified through public runtime and history APIs. |
| 6 | Multi-instance approval activity | `UNSUPPORTED` | Collection, completion-condition and child-execution semantics are outside the accepted scope. |
| 7 | Embedded subprocess | `SUPPORTED_WITH_LIMITATIONS` | Root-to-embedded and embedded-to-root mappings are verified for exact shapes. |
| 8 | Call Activity | `SUPPORTED_WITH_LIMITATIONS` | Independent called-child and child-to-parent movement are verified; parent-to-child creation and recursive tree migration are unsupported. |
| 9 | Boundary Event | `SUPPORTED_WITH_LIMITATIONS` | Only tested non-interrupting timer-boundary add/remove shapes are accepted. |
| 10 | Timer | `SUPPORTED_WITH_LIMITATIONS` | Fixed-date intermediate timer-catch behavior is verified; firing races and recurring timers are not. |
| 11 | Async Job | `UNSUPPORTED` | A migrated instance can remain paired with a source-bound executable job. |
| 12 | Pending job | `UNSUPPORTED` | Every executable/pending-job instance is excluded from the candidate safe scope. |
| 13 | Process variables | `SUPPORTED_WITH_LIMITATIONS` | Simple process-variable preservation is verified; complex serializers and transient values remain unproven. |
| 14 | Execution local variable | `SUPPORTED_WITH_LIMITATIONS` | Verified only where direct same-ID migration retains the execution identity. |
| 15 | Task local variable | `SUPPORTED_WITH_LIMITATIONS` | Verified only where direct same-ID migration retains the task identity. |
| 16 | Identity Link | `SUPPORTED_WITH_LIMITATIONS` | Candidate identity links are verified for the direct task shape; recreated tasks and other link types are not. |
| 17 | Candidate User / Candidate Group | `SUPPORTED_WITH_LIMITATIONS` | Existing candidates remain observable; target assignment rules must not be assumed to re-run. |
| 18 | Suspended instance | `SUPPORTED_WITH_LIMITATIONS` | Engine preservation is verified, but initial platform policy must reject suspended execution. |
| 19 | Ended instance | `UNSUPPORTED` | No runtime instance remains and public migration validation rejects it. |
| 20 | Historic tasks exist but no active task | `SUPPORTED_WITH_LIMITATIONS` | A receive-task wait state is verified; eligibility must inspect active activities and wait-state type. |
| 21 | Source and target DSL structures incompatible | `UNSUPPORTED` | Platform compatibility policy must reject incompatible compiled structures even if mappings could be forced. |
| 22 | Source activity absent from target | `SUPPORTED_WITH_LIMITATIONS` | Automatic validation fails; an explicit mapping bound to immutable compiled artifacts is mandatory. |
| 23 | One source activity to multiple target activities | `SUPPORTED_WITH_LIMITATIONS` | The exact permanent one-to-many shape is verified. |
| 24 | Multiple source activities to one target activity | `SUPPORTED_WITH_LIMITATIONS` | The exact permanent many-to-one shape is verified. |
| 25 | Concurrent task completion during migration | `UNSUPPORTED` | Real H2 races produce one winner and rollback the loser, but production must serialize authoritative commands before engine invocation. |
| 26 | Concurrent withdraw, reject or terminate | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Not directly raced; it remains prohibited and cannot inherit task-completion semantics. |
| 27 | Timeout although engine migration completed | `SUPPORTED_WITH_LIMITATIONS` | Exact source/target/history readback can classify selected states, but no receipt exists and non-unique evidence remains UNKNOWN. |
| 28 | Engine returns success but post-verification differs | `SUPPORTED_WITH_LIMITATIONS` | Public readback detects mismatch; the attempt must enter reconciliation and never completed success. |

## 6. Candidate minimum safe scope for later implementation review

The evidence supports only a future candidate scope with all of these constraints:

- one exact active process instance per invocation;
- server-resolved tenant, instance, source release, target release and immutable compiled artifacts;
- active graph shape covered by permanent tests;
- deterministic mapping by immutable activity IDs;
- no multi-instance activity;
- no executable, pending, locked, failed or dead-letter job;
- no suspended, ended, terminated or missing runtime instance;
- no unverified interrupting/message/signal boundary subscription;
- a server-side lease or compare-and-set command gate shared by migration, completion, withdraw,
  reject and terminate authority;
- a server-generated idempotency key and immutable attempt identity committed before the external
  engine call;
- validation immediately before invocation;
- Flowable invocation outside the platform database transaction;
- bounded public before/after snapshots;
- completion only after exact verification;
- durable `UNKNOWN` for timeout, disconnection or ambiguous evidence;
- no automatic retry of `UNKNOWN`;
- no batch or definition-wide migration in the first production scope.

These are requirements for a later design phase, not implemented capabilities in M5-A.

## 7. Required outcome and reconciliation model

A future implementation must keep at least these outcomes distinct:

- intent not started;
- validation rejected;
- engine invocation explicitly rejected;
- command lost a concurrency race;
- invocation response lost or timed out;
- source runtime confirmed;
- target runtime confirmed;
- source history completed;
- source history terminated;
- target history completed;
- target history terminated;
- missing/no evidence;
- post-verification mismatch;
- reconciliation required;
- verified success;
- unsafe for automatic recovery.

No exception by itself proves that no engine-side change committed. No response by itself proves that
all dependent entities match. Reconciliation must compare the sealed plan with runtime definition,
active activities, tasks, suspension, variables, identity links, jobs, timers, applicable
subscriptions and history.

## 8. Answers to mandatory M5-A questions

### Does the official API support migration?

Yes. Flowable 8.0.0 provides public validation and exact running-instance migration APIs. Support is
not equivalent to universal safe migration.

### What node types must currently be prohibited?

Prohibit multi-instance activities, executable or pending jobs, untested boundary/subscription
shapes, parent-to-child Call Activity creation, recursive process trees and every active graph not
covered by permanent tests. Suspended instances are also prohibited by initial platform policy.

### Can it migrate multiple instances?

The API exposes definition-wide and batch methods, but the initial safe scope must not use them.
Independent per-instance attempts are required for isolation, verification and reconciliation.

### Can it migrate one exact instance?

Yes. `validateMigration(String)` and `migrate(String)` support one exact runtime instance.

### Is there an official rollback?

No official rollback operation was found. A later migration back would be a separate governed
compensation attempt, not rollback of the original call.

### Is migration idempotent?

No documented idempotency guarantee exists. Sequential replay can be accepted, while concurrent
replay produced one H2 transaction winner and one loser. Neither behavior is a durable platform
idempotency contract.

### What happens on duplicate invocation?

A confirmed successful platform attempt must return stored evidence and must not call Flowable
again. A timed-out, disconnected or overlapping attempt must be reconciled before any new attempt.

### How can a timeout be evaluated?

Read public runtime, tasks, active activities, dependent jobs/timers, variables, identity links and
history. Exact sealed source or target evidence may be classified. Mixed, missing or non-unique
evidence remains `UNKNOWN` or `RECONCILIATION_REQUIRED`.

### How is activity mapping proven?

Bind the plan to immutable source/target compiled artifacts, record and hash the ordered mapping,
validate immediately before invocation, and compare the exact post-state with mapped target
activities. Activity names are not authoritative.

### How are task, variable, job, and identity-link losses detected?

Capture bounded public before/after snapshots and compare type, semantic value, state and count.
Stable identifiers may be compared only for shapes where permanent tests prove identity retention.
Any executable job in the initial scope is rejected rather than assumed preserved.

### Does migration affect history?

Yes. Permanent tests show selected historic process/task definition references moving to the target,
and history-only terminal states retaining the final definition and delete reason. The platform must
retain immutable source release and migration evidence independently of mutable engine history.

### Can verification avoid internal tables?

For the accepted candidate shapes, public APIs provide sufficient runtime, task, management and
history evidence to verify or fail closed. Any future invariant that cannot be proven through public
APIs remains unsupported; direct table access is prohibited.

### What does concurrency evidence prove?

It proves only that the tested embedded H2 engine produced coherent single-winner outcomes for two
specific races. It does not prove PostgreSQL, cluster, lease, idempotency or cross-command safety.
The platform must serialize authoritative commands before invocation and reconcile every overlap.

## 9. External-call protocol implications

Flowable and the platform database cannot participate in one atomic transaction. A later executor
must therefore separate:

1. committed intent and immutable attempt evidence;
2. server-side command lease/CAS acquisition;
3. final validation;
4. external Flowable invocation;
5. response evidence;
6. public engine readback;
7. exact verification;
8. completed outcome or durable reconciliation state.

The engine must never be invoked from a browser/mobile-manufactured authority context.

## 10. Final M5-A stage decision

Current M5-A decision: `SUPPORTED_WITH_LIMITATIONS`.

The technical evidence package is complete and ready for explicit stage acceptance. It demonstrates
a real public migration capability and a defensible, tightly restricted candidate scope, while also
showing why async jobs, uncoordinated commands, blind retries and broad graph support are unsafe.

This decision does not authorize M5-B. PR #58 must remain Open + Draft until the user explicitly
accepts M5-A and separately authorizes the next stage.

Still prohibited before that explicit acceptance:

- `V33` or production migration tables;
- migration-plan, intent, attempt, verification or reconciliation persistence;
- a production migration executor or worker;
- execute, force, rollback or reconciliation APIs;
- browser or mobile execution controls;
- automatic migration;
- automatic retry of `UNKNOWN`;
- direct Flowable table access;
- a temporary or second M5 workflow;
- marking PR #58 Ready or merging it.
