# Outbox operator alerts

These are operator alerts for the aggregate metrics in [Outbox backlog observations](outbox-backlog-metrics.md).
They neither execute payment retries nor replace durable workflow timeout/failure notifications.
The original nine availability/SLA/Connector rules remain unchanged.

## Enable deliberately

Enable `APPROVAL_OBSERVABILITY_OUTBOX_ENABLED=true` on the application and set the
Prometheus **target label** `outbox_monitor: enabled` for each target expected to
export this monitor. The shipped scrape example leaves that label commented out.
Do not derive the expectation from a series emitted by the monitor itself: a failed
monitor cannot reliably report its own disappearance. Targets intentionally running
without the feature must not have the expectation label.

Mount both `approval-platform.rules.yml` and `approval-outbox.rules.yml` at
`/etc/prometheus/rules/` using the explicit filenames in `prometheus.yml`. Do not
mount rule-test fixtures as rules. The Outbox file is JSON, which is valid YAML.
Retain the existing private management network boundary; do not expose database-wide
counts or Alertmanager administration endpoints to application tenants.

| Alert | Default condition | Hold |
| --- | --- | --- |
| ApprovalOutboxBacklogHigh | PENDING + IN_FLIGHT > 100 | 5 minutes |
| ApprovalOutboxOldestUnfinished | oldest unfinished age > 300 seconds | 2 minutes |
| ApprovalOutboxExpiredLeases | expired leases > 0 | 2 minutes |
| ApprovalOutboxDeadLetters | DEAD > 0 | immediate |
| ApprovalOutboxMonitoringUnavailable | expected reachable target lacks a complete fresh sample | 2 minutes |

These are starting thresholds, not a measured production capacity or payment SLA.
Due rows and expired leases are subsets; adding them again exaggerates the backlog.
Health joins match on job and instance, without summing repeated snapshots from
replicas sharing one database. Backlog addition matches the complete shared label
set of PENDING and IN_FLIGHT, retaining application, environment and other deployment
labels used for notification grouping. Do not narrow that arithmetic to `on(job,
instance)`: one-to-one arithmetic would discard its other routing labels. Missing
series from another target cannot supply the missing half of a queue observation.
A healthy replica cannot hide another target's missing sample.
An unreachable target is handled by the original availability rules, not a second
Outbox sampling alarm. Missing, stale, NaN or incomplete samples mean unknown health.
A resolved queue alert during sampling loss is not evidence of queue recovery; check
sampling/target availability alongside business alerts.

## ApprovalOutboxBacklogHigh

Check the dispatch enablement, worker throughput, retry scheduling and callback
availability. Planned backoff contributes to pending population. Inspect the
existing diagnostics and audit; do not lower retry delays or replay payments merely
to make this gauge smaller.

## ApprovalOutboxOldestUnfinished

Inspect why the oldest unfinished message remains. The sampled age includes future
backoff and is not an advancing wall-clock timer between observations, nor does it
prove that a worker is stuck. Confirm the sample is healthy before intervention.

## ApprovalOutboxExpiredLeases

Check worker health, bounded callback timeouts and lease recovery through the existing
dispatcher. Do not repair incidents by writing directly to Flowable `ACT_*` tables
or changing Outbox ownership manually.

## ApprovalOutboxDeadLetters

Review terminal delivery evidence and external idempotency before an authorized
recovery. DEAD rows continue to alert while retained. This rule does not clear,
reschedule or acknowledge them; payment replay and incident acknowledgement remain
separate actions.

## ApprovalOutboxMonitoringUnavailable

Check the expected target's opt-in configuration, private observation connection,
query timeout and last sample age. A missing gauge or invalid sample is not zero.
Keep the target label enabled when diagnosing an unexpected outage; removing it
would remove the monitoring expectation, not repair the monitor.

## Notification route and executable checks

The existing Alertmanager route sends firing and resolved notifications to
`approval-platform-operations`. Its `approval-ops-notifier` address is an operator
integration point, **not a notification service supplied by this change**. Provision
an authenticated internal receiver and external contact routing separately; keep
credentials in deployment secrets. Webhook delivery can repeat. A production
receiver needs durable deduplication and incident state, not just an in-memory set.

`node scripts/ops/verify-prometheus-rules.mjs` runs the existing pinned promtool
checks, then all five new rules against deterministic native-engine fixtures for
thresholds, holds, recovery, missing/NaN/stale samples, disabled targets and replicas.
It reuses the same digest-checked Prometheus 3.13.3 archive, then provisions
Alertmanager 0.34.0 from an independently pinned upstream SHA-256. Downloaded tools
receive only PATH and locale variables, not GitHub/cloud/application credentials.

A bounded loopback rehearsal then starts real Prometheus and Alertmanager and uses
the unchanged Outbox rule file. A controlled HTTP metric source moves DEAD from
zero to one and back. The existing receiver route is retained; only its destination
and batching delays are replaced with loopback and one-second test values. The
receiver records the first firing notification but returns 503, then acknowledges
the retry and subsequent resolution. The same fingerprint and startsAt must survive;
the bounded in-memory fixture has one firing and one resolved record despite retries.
Native processes, HTTP listeners and owned temporary files must be cleaned up.

This tests real parser/evaluation, scraping, routing, retry and resolution over HTTP.
It is **not** a new database-to-human notification E2E, persistent notifier acceptance,
all-alert live hold-period timing, production Alertmanager batching timing, or a live
collector/database outage drill. All rules' holds are tested with promtool's virtual
time; the real notification path uses the immediate DEAD rule. The earlier real
PostgreSQL backlog tests are separate evidence, not silently relabeled this scenario.
The normal CI entry executes the checks without adding another workflow or rerunning
a failed scenario until it passes. Inspect the current run, not only test existence.
The native entrypoint emits `OPS_OUTBOX_ALERTS_VERIFIED` only after rule evaluation
and the real delivery rehearsal succeed. Injected unit runners return their fixture
results without printing native acceptance records. A unit-test status object is
not notification evidence.

Upstream references: [alert rules](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/),
[Alertmanager configuration](https://prometheus.io/docs/alerting/latest/configuration/),
[release checksums](https://prometheus.io/download/).
