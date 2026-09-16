# Transaction-bound business metrics

This is the first business instrumentation slice for #146 / PR #147, not complete
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
| `approval.task.completed` | Successful `completeTaskAndSynchronize`, after commit, including a completed rejection/revision task |
| `approval.command.duration` | An actually executed start/approve/reject/resubmit callback through the existing idempotency guard, until transaction completion |
| `approval.telemetry.dropped` | Observations omitted due to absent transaction synchronization, bounded savepoint tracking or a telemetry failure |

The instrumentation adds **only** `operation` (`start`, `approve`, `reject`, `resubmit`) and
`outcome` (`committed`, `rolled_back`, `unknown`, `failed`) timer labels. The existing
`application` common tag is preserved. Other operations
pass through unchanged and do not create dynamic label values. `failed` describes
an action that threw while its surrounding transaction nevertheless committed;
`rolled_back` also includes downstream persistence/commit failure and rollback of
an enclosing transaction or savepoint. This is **command execution/transaction
outcome**, not an authoritative `PROCESS_EXECUTION_FAILED` event or process state.
Failures before the idempotency guard invokes its action are outside this timer.

The timer measures callback-entry-to-transaction-completion, including result
persistence and any remaining surrounding transaction. It does not measure HTTP
latency, time waiting for human approval, complete process duration, or time spent
waiting to acquire the initial idempotency record. Its histogram is configured
with a 1 ms to 5 minute expected range; that is not a business timeout policy.

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

## Executable verification

`ApprovalBusinessMetricsTest` uses the real Spring transaction manager/lifecycle
and Micrometer with mocked JDBC/projection responses. It covers commit, rollback,
terminal separation, no-transaction omission, cached replay, bounded labels,
registry failures, nested savepoint rollback and repeated/unknown completion.

`ApprovalBusinessMetricsPostgresTest` uses the existing Spring Boot application,
real PostgreSQL 16 and Flowable with the governed purchase-payment Seed. Docker is
required, not silently skipped. It verifies a rolled-back real approval, reuse of
that rolled-back idempotency key, all five actual application-service approvals,
cached replay, stale-task failure, and HTTP scraping of the private Prometheus
endpoint. It neither writes business tables directly nor substitutes browser
acceptance, live Prometheus/Alertmanager delivery or production operation.

Both tests are ordinary Maven tests in the executable server and are selected by
the existing permanent validation. Source or test existence is not evidence that
the current commit has passed; inspect its actual Maven test reports.

## Remaining #146 scope

Process/task durations, active/overdue gauges, durable timeout/failure events,
Outbox/notification backlog instruments, asynchronous trace linking, idempotent
notification delivery and live collector outage/recovery drills are still open.
No process failure/timeout count is synthesized from an HTTP error or task
rejection. No production readiness, supported capacity or issue closure is claimed.
