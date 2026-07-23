# M5 Process Instance Migration Timer and Job Evidence

M5-A TIMER/JOB SLICE: `CAPABILITY_VALIDATION_ONLY`

Overall conclusion remains: `SUPPORTED_WITH_LIMITATIONS`

This fifth evidence slice supplements the existing M5 feasibility, parallel and subprocess records.
It does not authorize M5-B, production execution, a database schema, a worker, automatic retry,
job execution, or operational mutation of retries and dead-letter state.

## 1. Baseline and isolation

- Current verified `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`.
- M5 branch baseline before this slice: `d73d52895c0829034d641e15a8f48504b832a42e`.
- Flowable dependency baseline remains `org.flowable:flowable-bom:8.0.0`.
- Flyway remains continuous through `V32`; this slice adds no `V33`.
- All timer and job migration operations remain in test sources.
- The tests use `ProcessMigrationService`, `RuntimeService`, `TaskService` and `ManagementService`
  public APIs only.
- No job is executed, rescheduled, deleted, retried, moved to dead letter, or queried through
  Flowable tables.
- The only automatic PR/main workflow remains
  `.github/workflows/approval-platform-validation.yml`.

## 2. Official public API boundary

Flowable 8.0.0 documents the following migration cases as supported:

- automatic migration of intermediate catch-event wait states with the same activity ID;
- migration of a wait state to an activity with a boundary timer event;
- migration of a wait state with a boundary timer event to an activity without that event;
- direct migration of async service tasks in the current engine line.

Public `ManagementService` exposes `createTimerJobQuery()` and `createJobQuery()`. Public `Job`
readback includes process instance, process definition, element, due date and correlation evidence.
A technical job ID is not treated as a stable platform identifier.

Primary references:

- https://www.flowable.com/open-source/docs/bpmn/ch08-ProcessInstanceMigration
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/ManagementService.html
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/job/api/TimerJobQuery.html
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/job/api/Job.html

## 3. Isolated engine evidence

The fifth slice adds three test-only classes and four exact tests:

1. `FlowableProcessInstanceMigrationTimerCatchCapabilityTest` migrates an intermediate timer catch
   event with a fixed future due date. After migration exactly one timer job remains, references the
   target definition and original process instance, uses the target timer element, and retains the
   fixed semantic due date.
2. `FlowableProcessInstanceMigrationBoundaryTimerCapabilityTest` migrates an active user task from
   a definition without a boundary timer to one with a non-interrupting boundary timer. The target
   user task remains active and one target-definition timer job is created.
3. The same class migrates an active user task from a definition with a boundary timer to one
   without it. The target user task remains active and the timer job is removed.
4. `FlowableProcessInstanceMigrationPendingAsyncJobCapabilityTest` migrates a direct async service
   task while its executable job remains pending because the async executor is disabled. Exactly one
   job remains and public readback references the target definition, process instance and activity.

These tests prove semantic readback for exact simple shapes. They do not prove safe migration of
executing or locked jobs, jobs already acquired by another node, retries, dead-letter jobs,
suspended jobs, async-leave jobs, nested scopes, timer races, job execution after migration, or
external timeout and duplicate-invocation behavior.

## 4. Capability matrix delta

This table supersedes the corresponding rows in the initial feasibility matrix.

| # | Scenario | M5-A status | Evidence and limitation |
|---:|---|---|---|
| 9 | Boundary Event | `SUPPORTED_WITH_LIMITATIONS` | Adding and removing one non-interrupting boundary timer on a direct user-task wait state is verified. Signal/message boundaries, interrupting execution, nested scopes and fired events remain unproven. |
| 10 | Timer | `SUPPORTED_WITH_LIMITATIONS` | A same-ID intermediate timer catch event and a fixed semantic due date are verified through one target timer job. Timer firing and concurrent due-date races remain unproven. |
| 11 | Async Job | `SUPPORTED_WITH_LIMITATIONS` | A direct async service task with one unexecuted job is migrated to the same activity ID. Locked, executing, async-leave and failed jobs remain blocked. |
| 12 | Pending job | `SUPPORTED_WITH_LIMITATIONS` | Public job readback verifies exactly one pending target-definition job for the tested direct async service-task shape. Retry, lock and dead-letter semantics remain unproven. |
| 27 | Timeout although engine migration completed | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Timer and job state can be read publicly, but no invocation receipt or idempotency contract proves whether a timed-out call completed. |
| 28 | Engine returns success but post-verification differs | `SUPPORTED_WITH_LIMITATIONS` | Timer/job count, definition, element and due-date evidence can detect mismatch for these shapes; mismatch must enter reconciliation rather than completion. |

## 5. Governance implications

- The sealed plan must include the expected job family: timer job, executable async job, suspended
  job or no job.
- Before/after evidence must bind process instance ID, target process definition ID, element ID,
  semantic due date where applicable, job count, retries, lock state and correlation evidence when
  exposed.
- A technical job ID must not be the only equality condition because Flowable documents a separate
  correlation ID for tracking jobs whose technical ID can change.
- Validation and job snapshots are point-in-time evidence and must be refreshed immediately before
  invocation.
- A job becoming executable, locked, moved, retried or removed after validation invalidates the
  execution authorization.
- The initial production candidate scope must reject locked, executing, suspended, dead-letter and
  multi-job instances until dedicated evidence exists.
- Timeout remains `UNKNOWN`; it must never trigger a blind retry.

## 6. Still prohibited

- `V33` or migration persistence;
- production execute, force, rollback or reconciliation endpoints;
- a migration worker or automatic job executor control;
- job execution, deletion, rescheduling, retry mutation or dead-letter replay from M5-A;
- browser or mobile migration controls;
- automatic retry of `UNKNOWN`;
- direct Flowable `ACT_*` access;
- a temporary or second M5 workflow.

## 7. Slice decision

Current M5-A decision remains `SUPPORTED_WITH_LIMITATIONS`.

This slice permits further isolated M5-A evidence only. It does not authorize M5-B or production
migration execution.
