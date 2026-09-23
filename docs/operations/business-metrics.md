# Transaction-bound business metrics

This is the business instrumentation for #146 / PR #147, not complete
workflow observability or permission to release. It reuses the existing Micrometer
registry and private management surface. No exporter, workflow, database migration,
notification worker, engine listener or second business backend is introduced.

## Sources and semantics

`ApprovalPlatformConfiguration` wraps the existing `JdbcApprovalProjectionStore`
and `JdbcIdempotencyGuard`. All reads, parameters, return values, locking, CAS,
idempotency, audit and Outbox behavior continue through the original implementations.
The projection decorator adds no query. The registry never receives a tenant,
operator, instance/task ID, business key, request ID, exception text or payload.

| Micrometer name | Source of an observation |
| --- | --- |
| `approval.process.started` | Successful `createInstance`, after the surrounding transaction commits |
| `approval.process.completed` | A newly created terminal instance, or task synchronization to `COMPLETED`, after commit |
| `approval.process.rejected` | The same boundaries with terminal `REJECTED`; a task sent back for revision is not a terminal process rejection |
| `approval.process.withdrawn` | Successful `withdrawRunningInstance`, after commit |
| `approval.process.duration` | Persisted instance creation-to-terminal timestamp for a successful terminal insert/update, after commit |
| `approval.task.completed` | Successful `completeTaskAndSynchronize`, after commit, including a completed rejection/revision task |
| `approval.task.waiting.duration` | Persisted creation-to-normal-claim wall-clock duration, after the claim transaction commits |
| `approval.command.duration` | An actually executed start/approve/reject/resubmit callback through the existing idempotency guard, until transaction completion |
| `approval.telemetry.dropped` | Observations omitted due to absent transaction synchronization, bounded savepoint tracking or a telemetry failure |

The command timer adds **only** `operation` (`start`, `approve`, `reject`, `resubmit`) and
`outcome` (`committed`, `rolled_back`, `unknown`, `failed`) timer labels. The existing
`application` common tag is preserved. Other operations
pass through unchanged and do not create dynamic label values. `failed` describes
an action that threw while its surrounding transaction nevertheless committed;
`rolled_back` also includes downstream persistence/commit failure and rollback of
an enclosing transaction or savepoint. This is **command execution/transaction
outcome**, not an authoritative `PROCESS_EXECUTION_FAILED` event or process state.
Failures before the idempotency guard invokes its action are outside this timer.

The command timer measures callback-entry-to-transaction-completion, including result
persistence and any remaining surrounding transaction. It does not measure HTTP
latency, time waiting for human approval, complete process duration, or time spent
waiting to acquire the initial idempotency record. Its histogram is configured
with a 1 ms to 5 minute expected range; that is not a business timeout policy.

## Task waiting duration

`approval.task.waiting.duration` observes the existing normal `claimPendingTask`
result: `TaskProjection.updatedAt - TaskProjection.createdAt`. PostgreSQL returns
both persisted timestamps in the existing atomic claim response. The decorator
adds no lookup, poll, identifier cache, database write or engine listener. The
normal path serves approval, rejection and resubmission; it is not restricted to
positive decisions. Transfers, withdrawal, cancellation and control-only claims
are not new normal claims and add no sample.

A sample is published only when the enclosing transaction commits, using the same
savepoint/rollback safeguards as lifecycle counters. Cached idempotent responses
and stale-task conflicts add no sample. A successful claim measures the task's
wall-clock age at the command timestamp; it is not a task-completed counter or
proof that a human opened the task then. It includes time before a transfer, pauses,
non-working time and any prior rolled-back attempts. It does not measure an SLA
business calendar, per-assignee handling time, the currently waiting population,
or the later claim-to-commit processing interval.

The timer adds no tags; existing deployment/common tags are retained. It exports
`approval_task_waiting_duration_seconds_count`, `_sum`, and histogram `_bucket`
series on the existing private Prometheus endpoint. Its expected histogram range
is 1 second through 90 days, not a timeout threshold or a cap on valid samples.
Genuine zero waits are recorded. Negative/missing timestamps, duration overflow,
inconsistent claim results and registry failures drop the observation through
`approval_telemetry_dropped_total` without changing the returned claim or transaction.
Historical tasks contribute only when a new normal claim commits; there is no
backfill. Process restarts reset the timer, not the persisted task creation time.

## Process duration

`approval.process.duration` measures the complete instance's wall-clock age at its
persisted terminal command timestamp: `updated_at - created_at`. It is emitted for
`COMPLETED`, `REJECTED` and `WITHDRAWN`, labeled respectively `outcome="completed"`,
`"rejected"`, and `"withdrawn"`. These three fixed labels are the only new dimensions;
existing deployment/common tags remain. A rejected process is not an execution failure.
A task sent back for revision while its instance remains RUNNING has no terminal sample.

The PostgreSQL store adds `RETURNING created_at, updated_at` to the existing instance
insert and guarded status/withdrawal updates. It retains every existing mutation
predicate, version check and affected-row check. The optional `ApprovalProcessTimingObserver`
receives only the two stored timestamps and the fixed terminal outcome, after the store's
writes succeed. It adds no lookup, SQL statement, ID cache, scheduler or engine listener.
The original two-argument constructor stays compatible and uses a no-op observer.
The executable server wires the commit/savepoint-aware business-metrics recorder.

Only the winning mutation can register an observation. Failed claims, wrong tenant or
initiator, duplicate terminal attempts and cached idempotent responses add no sample.
RUNNING updates, task replacement and control-only operations do not terminate a process.
An initially terminal insert is measured too; a genuine zero is valid. Null/negative or
overflow timing is dropped, not clamped. Optional timestamp-extraction and observer
exceptions cannot change affected-row authority; SQL mutation failures still propagate.
Publication obeys the same commit, rollback, savepoint, missing-transaction and registry
failure rules as the other business observations.

Time includes human waiting, pauses, transfers, non-working days and prior failed attempts,
but ends at the server's persisted command timestamp, not after the final commit or external
callback. It is not a business-calendar SLA, human handling time, current active population,
or payment delivery latency. Old running instances can be measured after restart without
remembering their starts in memory. Completed history is not backfilled. Timers reset on
restart and remain best-effort observations, not durable exactly-once accounting.

The private endpoint exports `approval_process_duration_seconds_count`, `_sum` and
histogram `_bucket` series. The histogram's 1-second to 90-day expected range is not an SLA
threshold or a cap on valid samples. For completed instances, use a window with enough
terminations and restrict to the intended deployment before aggregating replicas:

```promql
histogram_quantile(0.95,
  sum by (le, application) (rate(approval_process_duration_seconds_bucket{outcome="completed"}[1h])))
```

Long-running unfinished instances are absent from this completion distribution; use
separate active/overdue instrumentation, not this timer, to detect them. No new P95/SLA
alert or production threshold is implicitly enabled by adding this metric.

## Replay, rollback and failure isolation

A cached idempotent result does not execute the wrapped callback. A repeated
business key may execute a start callback but return an existing instance; because
lifecycle counts observe the actual projection write, it is not a new start.

Observations are recorded from Spring transaction completion callbacks only.
Rollback and unknown completion cannot release lifecycle success counts. Nested
savepoint rollback cancels observations created inside that savepoint without
canceling observations registered before it. Savepoint identity storage is bounded
at 64 per observation and discarded on completion; exceeding the bound drops the
observation conservatively. Repeated completion callbacks do not double count.

A nontransactional call still runs the original business implementation, but no
committed lifecycle claim is emitted. Meter failures are caught separately from
business failures. They increment a local dropped counter and produce a fixed,
once-per-recorder warning without including exception or request details. Business
exceptions propagate unchanged. There is no telemetry network call in a business
transaction and no automatic business retry.

These are process-local operational metrics, **not durable exactly-once accounting**.
An application crash between commit and observation can lose a sample; a restart
resets counters. Registry/exporter failure can lose or partially record metrics.
Use the existing persistent projections/audit for reconciliation, and monitor
`approval_telemetry_dropped_total`. No unbounded event-ID cache is maintained.

## Internal Prometheus queries

The existing management endpoint translates counters to `_total` and timer units
to seconds. Series appear when first used. For example:

```promql
sum(rate(approval_process_started_total[5m]))
sum(rate(approval_process_completed_total[5m]))
sum(rate(approval_task_completed_total[5m]))
sum by (operation, outcome) (rate(approval_command_duration_seconds_count[5m]))
histogram_quantile(0.95,
  sum by (le, operation) (rate(approval_command_duration_seconds_bucket{outcome="committed"}[5m])))
```

Do not subtract started/completed counters to infer the global active-process
population: replicas, restarts, withdrawals and historical processes make that
incorrect. Do not reuse command durations as task-waiting or process-SLA latency.
The existing broader alert fixtures do not become fully instrumented merely
because these new series exist; live alert/notification acceptance remains open.

For the observed task-wait distribution (use a window with enough completed claim
transactions, and keep deployments separate when choosing an operational threshold):

```promql
histogram_quantile(0.95,
  sum by (le, application) (rate(approval_task_waiting_duration_seconds_bucket[1h])))
```

This is not a process-SLA alert and does not diagnose tasks that have not yet been
claimed. Such active/overdue population instrumentation remains separate work.

## Executable verification

`ApprovalBusinessMetricsTest` uses the real Spring transaction manager/lifecycle
and Micrometer with mocked JDBC/projection responses. It covers commit, rollback,
terminal separation, no-transaction omission, cached replay, bounded labels,
registry failures, nested savepoint rollback and repeated/unknown completion.

`ApprovalTaskWaitingMetricsTest` covers exact persisted durations, commit-only
publication, rollback/retry, cached replay, conflicts, control-path exclusion,
invalid timestamps, clock regression, overflow, registry failure, nested rollback
and duplicate/unknown completion. It uses the real Spring transaction manager and
Micrometer with controlled JDBC/projection responses.

`ApprovalBusinessMetricsPostgresTest` uses the existing Spring Boot application,
real PostgreSQL 16 and Flowable with the governed purchase-payment Seed. Docker is
required, not silently skipped. It verifies a rolled-back real approval, reuse of
that rolled-back idempotency key, all five actual application-service approvals,
cached replay, stale-task failure, and HTTP scraping of the private Prometheus
endpoint. Waiting-time assertions require five committed claim samples, no samples
from rollback/replay/identity rejection/conflicts, and exact total-time reconciliation
with the persisted task history (this service uses one timestamp for claim and
completion). The HTTP response must contain waiting histogram/count/sum series.
It neither writes business tables directly nor substitutes browser
acceptance, live Prometheus/Alertmanager delivery or production operation.

`ApprovalProcessDurationMetricsTest` covers the three outcomes, commit/rollback/savepoints,
invalid and overflowing durations, genuine zero and long samples, bounded labels, registry
failure and repeated/unknown completion. `JdbcApprovalProcessTimingIntegrationTest` uses
real PostgreSQL and the projection APIs for terminal insert/completion/rejection/withdrawal,
rollback, a reconstructed store, tenant/initiator rejection, concurrent terminal attempts,
observer failure isolation and constructor compatibility. It requires Docker.

The existing real Spring Boot/Flowable test additionally requires one process-duration
sample after five committed task approvals, none for intermediate work/replay/conflict,
stored instance total-time reconciliation, and actual HTTP histogram/count/sum export.

These ordinary Maven tests run in the server and persistence modules through the existing
permanent validation. Source or test existence is not evidence that
the current commit has passed; inspect its actual Maven test reports.

## Notification delivery observations

The SLA-timeout notification route now adds process-local counters for reserved-route
dispatch attempts, delivered receipts and bounded failure categories. Durable
notification backlog/dead-letter state is not inferred from these counters: the
existing PostgreSQL Outbox sampler publishes a reserved-route subset under
`approval_notification_outbox_*`. See `sla-timeout-notifications.md` and
`outbox-backlog-metrics.md` for exact semantics and failure/staleness boundaries.

## Remaining #146 scope

Calendar-aware task/SLA latency, active/overdue gauges, authoritative execution-failure
events, recovery/resolution notifications, asynchronous Outbox/Connector trace linking,
production idempotent human delivery and live collector/database outage/recovery drills
are still open. No process failure/timeout count is synthesized from an HTTP error or
task rejection. No production readiness, supported capacity or issue closure is claimed.
