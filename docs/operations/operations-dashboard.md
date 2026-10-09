# Approval operations dashboard

Import `deploy/observability/grafana/approval-operations.json` into an operator-managed
Grafana instance. This is a Classic dashboard JSON artifact using the built-in
Prometheus data source, stat, time-series, table and row panels. It does not provision
a Grafana server, credentials, a public endpoint or a new automatic workflow.

## Setup

Use an internal Prometheus data source that scrapes the existing private management
listener. Import the JSON, select that data source, then one environment and one
instance. The environment label comes from the scrape target configuration. Keep the
Grafana folder and data source restricted to operators; dashboard variables do not
provide tenant authorization or data-source isolation.

For current workflow counts, enable application configuration
`APPROVAL_OBSERVABILITY_WORKFLOW_ENABLED=true`, mark the expected Prometheus target
`workflow_monitor: enabled`, and load the shipped population rule file. The dashboard
requires `approval:workflow_population_sample_healthy` before showing the six population
values. For queue panels, enable `APPROVAL_OBSERVABILITY_OUTBOX_ENABLED=true` and the
expected-target label `outbox_monitor: enabled`. Both features remain off by default.
See `workflow-population-metrics.md` and `outbox-backlog-metrics.md` for their resource
budgets and SQL/freshness semantics.

## Panels and interpretation

The 25 query panels cover service/sampling health and sample age; active, SLA-covered
and overdue processes/tasks; P95 process duration, task wait and committed command
time; dropped telemetry; global and notification Outbox pending/in-flight/dead/oldest;
notification delivered/failure rates; and currently firing alerts.

Instance and environment selectors are deliberately single-select, with no All option.
A queue/workflow snapshot is never summed across replicas sharing a database. The
latency panels apply histogram_quantile to bucket rates for the selected instance,
keeping process outcomes or command operations separate. Timer units are seconds,
not milliseconds; missing/unused counters and no-traffic histograms stay unknown.
These natural-time observations are not calendar-aware SLA measurements.

All missing values display **未知**, not zero. Population panels require complete healthy
samples; queue panels require finite nonnegative values, sampling success and target
reachability. A genuine fresh zero remains zero. Notification gauges are a subset of
the global Outbox, not additional messages; do not add them together. Connector-delivered
counters are not proof that a human read a notification. A disappeared alert alone is
not business recovery. There is no trace drill-down link until an actual collector and
its supported query contract have been integrated; no invented trace URL is supplied.

## Executable checks

The existing native validation parses all 25 resolved panel queries using the pinned
promtool. Six deterministic native scenarios / 65 assertions cover selected-replica
isolation, healthy values, failed sampling, unavailable targets, incomplete population
samples, NaN/infinite queue observations and real zero populations. These use the
actual production population-health rule rather than a second implementation of it.

Node tests validate JSON structure, datasource/selector scoping, panel geometry,
missing-data handling, units, receipt validation and native-driver failure behavior.
The native receipt `OPS_OPERATIONS_DASHBOARD_QUERIES_VERIFIED` records the exact JSON
digest and query/test counts. It deliberately reports `grafanaBrowserVerified=false`
and `productionDeploymentVerified=false`. JSON/query validation is not a rendered
Grafana browser test or deployment acceptance; confirm import/rendering in the target
Grafana version before operational use.

Related live notification exercise: `workflow-population-delivery.md`.
Format reference: [Grafana dashboard JSON models](https://grafana.com/docs/grafana-cloud/learn-and-build/visualizations/dashboards/build-dashboards/view-dashboard-json-model/).
