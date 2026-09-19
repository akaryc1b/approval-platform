# Current workflow populations

This #146 / PR #147 slice reports current process/task populations from the platform's
PostgreSQL projections. It does not derive active counts from process-local counters,
read Flowable tables, execute SLA actions, send notifications or modify business rows.

## Enable

Set `APPROVAL_OBSERVABILITY_WORKFLOW_ENABLED=true`
(`approval.observability.workflow.enabled=true`). The default is off, independent of
Outbox observation and OTLP tracing. `approval.observability.workflow.interval-ms`
defaults to 30000 and accepts 5000 through 300000. Use the existing private Prometheus
management listener, not an application/tenant endpoint.

The feature owns one read-only Hikari connection at most and one fixed-delay daemon
worker. It is not a second business DataSource bean. With both workflow and Outbox
observation enabled, their combined additional budget is at most two connections
and two workers. Disabling either leaves the other's behavior unchanged.

Explicit PostgreSQL JDBC configuration is required, not JNDI or a custom
DataSource-only setup. The pool reuses resolved `spring.datasource` credentials and
Hikari data-source properties, including TLS settings. Acquisition/validation bounds
are 500/250 ms; each statement has a two-second query timeout. Driver connect/socket/
cancel timeouts are 2/3/1 seconds and cannot be overridden in the JDBC URL.

## Definitions

One read-only statement obtains database `statement_timestamp()` and one snapshot.
It selects identifiers internally only to join the same tenant, instance, target
scope and task. It returns six counts and a timestamp, never identifiers or payloads.

| Prometheus series | Meaning |
| --- | --- |
| `approval_process_active` | Current RUNNING process projections, including those without an SLA |
| `approval_process_sla_covered` | Active processes with a PROCESS SLA in ACTIVE or PAUSED state |
| `approval_process_overdue` | Active processes with an ACTIVE PROCESS SLA whose `overdue_at` has arrived |
| `approval_task_active` | PENDING or COMPLETING tasks whose owning process is still RUNNING |
| `approval_task_sla_covered` | Active tasks with a matching TASK SLA in ACTIVE or PAUSED state |
| `approval_task_overdue` | Active tasks with an ACTIVE TASK SLA whose `overdue_at` has arrived |
| `approval_workflow_sample_up` | 1 only while the last successful observation is fresh and the monitor is open |
| `approval_workflow_sample_age_seconds` | Monotonic age from the start of the last successful read, including query time |
| `approval_workflow_sample_timestamp_seconds` | Database timestamp of the last successful snapshot |
| `approval_workflow_sample_errors_total` | Local failed/null snapshot reads, not failed approval operations |

Covered and overdue are subsets, not extra work to add to active. A zero overdue
count with zero covered targets is not proof of SLA compliance. Paused SLAs remain
covered but are not overdue. Resumption uses the persisted revised deadline. The
predicate matches the existing SLA query (`ACTIVE` and `overdue_at <= observedAt`),
not merely `due_at`, creation age, an HTTP error, a notification or a failed task.
The database clock defines observation time; no new timeout event is created.

An overdue TASK is not an overdue PROCESS. Collaboration-participant SLAs do not
enter either SLA subset. Terminal process/task projections are excluded even if
an old SLA row has not yet been terminated. EXISTS checks prevent multiple matching
SLA rows from multiplying a target. Current-instance deadlines remain bound to their
stored policy/calendar; the monitor does not re-evaluate policy versions or calendars.

## Availability and operational use

Before the first read, after a failed read, after close, or at two sampling intervals
without fresh data, all six population gauges are NaN and sample_up is zero. Last
successful timestamp/age remain available. An overlong read cannot manufacture fresh
data. Scraping never waits for a query; polls cannot overlap; shutdown cancels the
worker, closes only its own pool and waits up to five seconds. Late results cannot
reactivate a closed monitor. Default-disabled operation creates no worker or pool.

No tenant, task, instance, user, request, business key, policy or exception text becomes
a metric label. Existing deployment/common tags remain. Do not sum identical database
snapshots across application replicas. Group deliberately by the operator's database/
deployment identity and use max, with health and target reachability checked separately.

Example diagnostic condition, not a shipped/paging-tested rule:

```promql
(approval_process_overdue > 0)
and on (job, instance) (approval_workflow_sample_up == 1)
and on (job, instance) (up{job="approval-platform"} == 1)
```

Configure an independent expected-target label and absence alert before paging on this
monitor. A disappeared metric or resolved condition during sampling loss is unknown,
not recovery. This slice does not alter existing alert rules or claim native alert
routing for these new series. Row counts are snapshots, not durable event accounting.
The database can still scan a large active population; timeout is unavailable evidence,
never a successful truncated count. Production load, DNS behavior and shutdown under
network failure require deployment-specific validation.

## Verification boundaries

`WorkflowPopulationMonitorChecks` contains JDK-only cases executed unchanged locally
and through `WorkflowPopulationMonitorTest` in JUnit. They cover cached reads, failures,
recovery, staleness, overlap, shutdown and JDBC resource/timeout protocol. JDBC proxies
do not evaluate SQL. Configuration/Micrometer tests check default-off, no replacement
business DataSource, unsafe settings, exact gauges and fixed labels.

`JdbcApprovalWorkflowPopulationIntegrationTest` requires real PostgreSQL with existing
migrations: scoped deadlines, missing coverage, pause/resume, COMPLETING/canceled tasks,
terminal exclusions, tenant separation, reader restart, transaction visibility and a
real table-lock/query-timeout recovery. Projection transitions use the existing store;
SLA/policy rows are explicit test fixtures, not a new engine-owned timer scenario.

`ApprovalWorkflowPopulationPostgresTest` runs the real Spring Boot/Flowable application,
checks rollback and five committed approvals plus replay, and scrapes numeric series
from the private HTTP endpoint. Approval calls use the configured identity filter and
application services, not browser/approval-HTTP acceptance. Current-head CI results,
not source existence, determine execution status. No migration, external recipient,
production capacity or complete Issue #146 acceptance is implied.
