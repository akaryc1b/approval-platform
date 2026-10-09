# Calendar-aware overdue-action completion lag

Enable `APPROVAL_OBSERVABILITY_SLA_LAG_ENABLED=true` explicitly. It is off by default.
The existing SLA execution worker must also be enabled and its governed OVERDUE
handler configured. This feature does not create another worker, queue, endpoint,
database migration, notification source or exporter.

## What is measured

The existing worker calls the optional observation hook only after `markSucceeded`
returns. The input is the returned SUCCEEDED intent, not the original claimed row:

```text
natural lag = completedAt - scheduledAt
working lag = existing working-calendar elapsedDuration(scheduledAt, completedAt)
```

`scheduledAt` is the persisted OVERDUE action deadline, including the existing
policy's overdue offset and any rescheduling performed by the SLA lifecycle.
`completedAt` is the successful attempt's persisted finish timestamp, captured by
the worker before its final store call. It is not the later JDBC commit timestamp,
first overdue detection time, HTTP response time, external notification delivery,
complete process/task duration or human handling time. Already-recorded detection
followed by an acknowledgement retry can therefore have a nonzero completion lag.
No new deadline is calculated from task/process age or from event payload fields.

Natural time is always reported. A WORKING_TIME policy additionally reports working
time using the intent's exact immutable policy and calendar versions. A newer active
calendar cannot replace that snapshot. Weekends, day overrides, overnight intervals
and DST follow `ApprovalWorkingTimeCalculator`; a genuine zero working interval is
recorded as zero. A NATURAL_TIME policy has no working-time sample, not a fabricated
zero. Missing, unpublished or mismatched versions produce no timing sample.

The interval is between the two recorded action timestamps. It does not reconstruct
all earlier pause windows or subtract cumulative wall-clock pause time from working
time. Pending/cancelled actions and failed attempts produce no successful-lag sample.
Retries contribute only when that execution intent eventually succeeds. Governed
replays have a separate fixed `source="replay"` dimension; they are not silently
mixed into original execution latency. There is no historical backfill or ID cache.

## Series and queries

`approval.sla.overdue.completion.lag` exports Prometheus timer `_seconds_count`,
`_seconds_sum` and `_seconds_bucket` series on the existing internal registry surface.
New tag values are fixed: `target=process|task|collaboration`,
`source=original|replay`, `time_basis=natural_time|working_time` (at most 12 tag tuples,
before the existing operator-owned common labels and histogram buckets).
No tenant, policy/calendar ID/version, task, recipient, event, request, payload or
exception text is a metric label. Do not add the two time bases as extra executions.

Histogram boundaries span an expected one second to seven days, not an SLA threshold
or a cap on admitted values; longer admitted samples enter the +Inf bucket. Inputs
over 366 natural days or with reversed timestamps are dropped before calendar reads
and calculation, never clipped or zero-filled. Percentiles are bucket estimates.
Select one deployment and enough completed observations, keeping time bases distinct:

```promql
histogram_quantile(0.95,
  sum by (le, environment, target, time_basis) (
    rate(approval_sla_overdue_completion_lag_seconds_bucket{
      environment="production",source="original"}[1h])))
```

There is no automatically enabled latency alert or invented production SLO. These
completion distributions omit unfinished work. Continue using the durable pending,
overdue and dead-letter gauges to diagnose work that has not completed.

## Transaction and failure boundaries

The observer refuses before any read if an outer transaction or synchronization
scope is active. Even if a test caller later commits that outer scope, this feature
conservatively omits its timing; it does not register an after-commit database read
against still-bound business resources. The normal scheduled worker uses separate
short persistence transactions. Worker outcome reporting and its optional completion
hook are separately protected from RuntimeException, including a telemetry exception
whose type happens to match a persistence conflict. A real persistence failure is
not relabeled success and never reaches the completion hook.

Enabled observations perform exact-version reads in a separate read-only Spring
transaction with a two-second JDBC transaction timeout. Read-only is a transaction
hint, not an authorization boundary. The shared database pool's acquisition and
network timeouts still apply; the two-second setting is not a hard wall-clock thread
cancellation guarantee. Reads and bounded calendar calculation run on the existing
worker thread after its write, can delay later batch items and need deployment load
evaluation. Default-off operation makes no new calendar queries or threads.

A query/lookup/registry RuntimeException drops the observation without changing the
already persisted execution, retry/lease state or transactional notification Outbox.
`approval_sla_overdue_completion_observations_dropped_total` counts locally omitted
observations/emissions (including an original worker-counter emission failure), not
lost business events. It can increment more than once for one execution. Registry
failure can omit or partially update series, and a crash after commit can lose a
sample. Timers/counters reset on process restart; this is not durable exactly-once
accounting or authoritative PROCESS_EXECUTION_FAILED/business resolution evidence.

## Verification

Normal Maven execution includes the shared 21-case Java calendar suite, metrics and
worker fault-isolation tests, actual Spring configuration/transaction wiring and
`ApprovalSlaLagPostgresTest`. The latter requires Docker and real repository migrations:
immutable calendar v1 while v2 is active, real SLA claims/timeout recorder/Outbox,
acknowledgement retry, final success, reconstruction, outer rollback and a real SQL
failure isolated to the observation transaction. Seed SQL is test-only. It does not
send human notifications or establish full browser/database causality, production
capacity, shutdown guarantees or completion of Issue #146. Source/test presence is
not a pass; inspect the current commit's actual CI results.

## Separate lifecycle timing foundation

`ApprovalSlaLifecycleTimingCalculator` is a pure application-layer building block,
not wired to a worker, endpoint, timer, alert, Spring bean or historical backfill.
It does not change the completion-lag series above. It accepts the existing
`SlaInstance`, its exact immutable `SlaPolicyVersion`, and, for working-time policies,
the exact immutable `CalendarVersion`. The intended future caller must read trusted
committed persistence records in a coherent observation boundary; a Java record or
TERMINAL status alone cannot prove a database commit. The calculator does no I/O and
cannot establish provenance, transaction isolation, rollback or exactly-once export.

For a terminal SLA row within a bounded 366-day interval it can return:

- gross natural elapsed: `terminalAt - startedAt`, including all pauses;
- gross working elapsed: calendar-working intersections between those timestamps,
  also including working intervals during pauses;
- policy duration: available only for NATURAL_TIME with `naturalTimePauses=false`.
  Pause-enabled NATURAL_TIME and every WORKING_TIME policy explicitly return
  `MISSING_PAUSE_HISTORY` for this separate value. Even zero gross duration keeps
  this conservative availability rule. Natural-time policies have no working value.

These gross values are not pause-adjusted SLA consumption or deadline-breach evidence.
They must not be used to claim the full calendar-aware process/task SLA feature or
to provision a process/task SLA alert. Existing due/overdue deadlines remain
authoritative. Terminal outcomes include cancellation, rejection and withdrawal,
not only success. A task terminated by process completion can retain an
`INSTANCE_COMPLETED` terminal reason; a future exporter must preserve these existing
outcome semantics rather than equating every TERMINAL row with successful task work.

The calculator validates tenant, policy ID/version, definition/task applicability,
existing process-to-task/participant policy inheritance, calendar ID/version and
time zone. Inactive or archived immutable versions remain valid; a newer active
version is never substituted. Missing, mismatched or unpublished snapshots and
invalid, reversed or overlong intervals return fixed unavailable reasons with no
partial timing. Calendar calculation errors also return unavailable, never a zero
or clipped result. Repeating the same input is deterministic computation, not a new
execution or durable idempotency guarantee. No identities or exception text appear
in the result, and no new metric labels are introduced.

### Persistence and integration gate

The current schema stores cumulative wall-clock pause milliseconds, not immutable
pause windows. Resume clears each `paused_at`; terminalization clears an open pause
without adding that last window to the accumulator. Therefore zero cumulative pause
time does not prove that the lifecycle was unpaused, and nonzero wall-clock pause
time cannot be subtracted from calendar-working elapsed. This calculator accepts no
caller-provided pause history, completeness flag or synthetic unpaused assertion.

Before pause-adjusted timing can be added, a separately governed persistence change
must retain authoritative, ordered pause/resume/terminal boundaries atomically with
SLA transitions, define completeness for legacy rows and terminal-during-pause cases,
and prove concurrency, rollback, retry and replay behavior against PostgreSQL. That
change requires resolving #146's foundation-stage no-Flyway boundary.

The live projection decorator now forwards the same business `completedAt`,
`changedAt`, or `withdrawnAt` to the existing SLA terminal write. It does not sample
another processing clock. Existing service signatures remain compatible and require
the persisted post-transition projection: their timestamp is that projection's
`updatedAt`. New callers should supply the explicit event timestamp. Active
collaboration participants use persisted `decidedAt`, `removedAt`, or `canceledAt`;
a terminal collaboration uses its persisted `terminalAt` for the existing aggregate
cancellation. Existing terminal reasons are unchanged, including cancellation of
remaining participant SLAs when a collaboration becomes satisfied or rejected.

Missing timestamps and timestamps preceding the source instance/participant start
or an affected active SLA start are rejected before SLA writes, without a clock
fallback. An active participant SLA absent from the
collaboration snapshot cannot establish when removal occurred: synchronization fails
before its SLA writes rather than inventing removal evidence. The JDBC collaboration
store retains terminal participant rows, so complete snapshots provide that evidence.
No schema or historical backfill is introduced. Already-terminal SLA rows retain
existing active-only update fencing; replay does not revise their original timestamp.

The existing transaction boundary must still commit before any future observation
is emitted. The lifecycle calculator remains unwired. Application checks cover
clock delay, source timestamps, validation, replay input, order and delegate failure;
PostgreSQL regressions cover persisted SLA/intent times, duplicate fencing, and
projection/SLA/intent withdrawal rollback. Their source presence is not a database pass: inspect exact-head CI results.
Rolling back this code changes timestamps of future terminal transitions back to the
previous processing-clock behavior; it does not rewrite existing rows.

The shared dependency-free Java checks and normal JUnit wrapper cover natural and
working elapsed, pause-history unavailability, weekends, overrides, overnight
intervals, DST, exact version/tenant drift, policy inheritance, bounded rejection,
immutable retired snapshots and repeated calculation without input mutation.
They do not claim a database rollback/replay rehearsal or live lifecycle metrics.
This intentionally unused internal API can be removed without a schema/configuration
rollback; its value is the tested fail-closed contract for that later integration.
