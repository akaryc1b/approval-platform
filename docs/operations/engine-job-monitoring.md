# Flowable engine job observations

This #146 slice observes four Flowable 8 BPMN job queues through the public
ManagementService count APIs. It does not inspect native engine tables, acquire
jobs, retrieve their payloads or exception stacks, change retries, create an
incident, or mark an approval failed. It does not activate the async executor.

## Enable and roll back

Set `APPROVAL_OBSERVABILITY_ENGINE_JOBS_ENABLED=true` explicitly. Default is off.
`approval.observability.engine-jobs.interval-ms` defaults to 30000 and allows
5000 through 300000. The corresponding interval environment variable is
`APPROVAL_OBSERVABILITY_ENGINE_JOBS_INTERVAL_MS`.

The existing private Prometheus management endpoint exports the series. Do not
expose it as a tenant dashboard. The monitor starts after ApplicationReadyEvent
and owns one daemon poller, no new database or engine instance. Disable the flag
and restart to remove instrumentation; no message or schema migration is needed.

The four count queries use the **shared engine pool** and its configured acquisition,
JDBC and database statement timeouts. They do not have a new monitor-specific SQL
timeout. Validate those timeouts and aggregate-query cost before production enablement.
At most one poll is active and there are no queued overlapping polls; a slow or hung
query makes the old sample stale. A close interrupts the worker and waits at most
five seconds, but cannot guarantee cancellation of an uninterruptible driver call.
A late result cannot republish after close. The shared engine/pool are never closed.
An accidental manual refresh within a Spring business transaction is rejected
before engine access; ordinary polling runs on its independent thread.

## Metric definitions

`approval_engine_jobs{queue="..."}` has exactly these fixed queue values:

| Queue | Meaning |
| --- | --- |
| `executable` | Jobs returned by createJobQuery().count(), including locked/in-progress jobs; not an immediately-runnable count |
| `timer` | Timer jobs, including future schedules and retry timers; not automatically overdue |
| `suspended` | Suspended jobs; intentional suspension is not an error |
| `dead_letter` | Current dead-letter jobs; not a count of failed approval processes |

Counts cover all tenants visible to this BPMN engine and include no tenant, process,
job, recipient, payload or exception labels. Only the fixed queue label is added;
existing deployment/common labels remain. External-worker and history job queues
and CMMN engines are outside this four-queue instrument. No duration or SLA lateness
is inferred from queue membership.

The queries are sequential: this is **not an atomic snapshot**. A job can move
between queries. Do not sum the queues into a transactional total, or sum duplicate
observations from replicas sharing an engine database. View one instance, or aggregate
replicas only using an explicit deployment/database grouping.

`approval_engine_jobs_sample_up` is 1 only for a successful complete, fresh sample.
`approval_engine_jobs_sample_age_seconds` uses monotonic time from the first read's
start. `approval_engine_jobs_sample_timestamp_seconds` retains the application-clock
time at the last successful read start, not database time. The local counter
`approval_engine_jobs_sample_errors_total` counts failed samples, not failed jobs.

Before the first read, after failure, after two poll intervals, or after close, all
four queue gauges are NaN and sample_up is 0. A genuine empty, healthy queue is 0.
Scrapes only read cached data; they never query the engine or wait for a poll.

## Alert configuration

Load `approval-engine-jobs.rules.yml` using the explicit production rule_files list.
Only add `engine_job_monitor: enabled` to targets expected to run this instrument.
The default scrape example does not enable that expectation. A complete-sample
record requires all four finite nonnegative counts, sample_up=1 and target up=1,
with matching common labels. Partial replicas cannot fill each other's missing data.

## ApprovalEngineJobDeadLetters

A healthy sample with dead_letter > 0 for two minutes raises a critical alert.
Investigate through authorized engine diagnostics. Preserve job identity and any
external side-effect evidence. Use the existing authorized recovery procedure only
after the cause and retry safety are understood. Never auto-replay or delete jobs
just to clear this gauge. Job dead-letter state alone does not prove a terminal
approval-process failure or authorize a PROCESS_EXECUTION_FAILED event.

## ApprovalEngineJobMonitoringUnavailable

A reachable expected target without a complete healthy sample for two minutes raises
a critical alert. Inspect configuration, shared pool pressure, engine connectivity,
query duration and the last sample age. If the target is down or missing, rely on the
existing service/target-discovery alerting instead of manufacturing a job count.

A dead-letter alert no longer matching during monitoring failure is **not business
recovery**. Confirm a fresh complete sample and inspect actual job/process outcomes.
There is no new human recipient, durable incident or resolution event in this slice.

## Verification scope

EngineJobPopulationMonitorChecks and its ordinary JUnit wrapper cover cached scrapes,
failure/null reads, genuine zero, whole-read freshness, overlapping refreshes,
background lifecycle and closed late results. Configuration tests cover default-off,
only fixed queue labels, full invalidation after a partial read and transaction refusal.

ApprovalEngineJobMetricsPostgresTest uses the real application, Flowable and
PostgreSQL plus HTTP Prometheus export. It creates asynchronous and timer jobs across
two fixture tenants, suspends/activates, moves to/from dead letter using test-only
public engine operations, recreates the reader and executes jobs. It is not proof of
automatic retry exhaustion, a production recovery endpoint or a collector outage.

The existing pinned promtool provisioner evaluates 36 scenarios, 102 alert assertions
and 51 health-record assertions. Positive, hold, recovery, missing, stale, non-finite,
negative, disabled, unreachable and cross-replica cases are retained. Unit runners
only test invocation; current CI must provide actual native results. No additional
workflow, binary download, alert hold reduction or increased native ceiling is needed.

Public API reference: Flowable 8 ManagementService,
https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/ManagementService.html
