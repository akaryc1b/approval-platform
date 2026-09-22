# Engine job dashboard

Import `deploy/observability/grafana/approval-engine-jobs.json` alongside
`approval-operations.json` in the existing internal Grafana. The overview keeps
its 25 original queries and panels and adds an **引擎任务** link. The companion
has an **运行监控** return link. Both preserve current variables and time range.
There is no hosted Grafana instance, new authentication boundary or deployment
performed by importing these repository assets.

## Enable and select

Enable `APPROVAL_OBSERVABILITY_ENGINE_JOBS_ENABLED=true` in the application and
add the existing `engine_job_monitor: enabled` expectation to the intended
Prometheus target only. Load the existing explicit engine-job rule file; the
companion uses its `approval:engine_job_sample_healthy` record. No new alert,
sampler or recording rule is installed by the dashboard.

Choose the Prometheus datasource, one environment and one instance. The
application's common label and target labels must agree across its series.
There is no all-instances option. This is an operator view of shared engine
state, not a tenant-facing API or permission isolation mechanism. Grafana and
Prometheus access must remain protected by the operator's authentication and
network policy.

## Read the panels

Eight data panels use eleven queries: target reachability, complete sample,
executable/timer/suspended/dead-letter counts, four separate queue trends and
the two existing engine-job alerts. Planned timers and intentional suspension
are informational; no arbitrary capacity threshold is added.

Current counts require the real complete-sample record to equal one, a reachable
target and finite nonnegative queue values. Queue matching ignores only the
`queue` label; other common labels must agree. Missing, failed, stale, disabled
or incomplete observations stay **未知**, never zero. Instant count panels use
`last`, not `lastNotNull`; historical charts neither stack queues nor bridge
missing samples. Historical points do not prove current health.

Four Flowable public count queries are sequential, not an atomic snapshot.
Do not add them into a transactional total or sum replicas reading the same
database. Executable includes locked work; timer includes future schedules.
Dead letters do not mean that the entire approval process is terminally failed.
There are no retry, execution or deletion controls on this dashboard.

A disappearing alert is **not business recovery**: telemetry loss can make the
alert stop matching. Require a new complete healthy sample before confirming
that a queue is actually empty. Follow `engine-job-monitoring.md` for existing
alert runbooks and the shared-pool/query-timeout limitations.

## Executable checks and remaining verification

`ops-engine-job-dashboard.test.mjs` is imported by the existing SLA aggregate.
The existing pinned promtool provisioner parses all eleven shipped query
strings and executes 43 scenarios / 935 exact-label-and-value assertions. These
include actual engine recording/alert rules, original two-minute holds,
firing/resolution, absent/stale/nonfinite inputs, zeros, failed/recovered
sampling, disabled/down/removed targets, mismatched application labels and
partial replicas. Three additional cases within that inventory isolate
missing, stale and zero recording-rule data. No mock output is logged as a
native verification receipt, and a native failure propagates.

Rule-engine verification is not Grafana browser rendering, datasource
permissions, actual production scrape freshness, human notification delivery
or production acceptance. The verifier reports those unperformed boundaries
explicitly. Browser import, navigation and rendering still require a real
Grafana deployment. Dashboard links use Grafana's current-variable and time
range options; query guards use Prometheus set-operator label matching.

Official references:
- https://grafana.com/docs/grafana/latest/dashboards/build-dashboards/manage-dashboard-links/
- https://prometheus.io/docs/prometheus/latest/querying/operators/
