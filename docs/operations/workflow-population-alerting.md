# Workflow population alerts

This #146 / PR #147 slice uses the six existing database-backed population gauges.
It adds one health recording rule and three operator alerts, not a business scheduler,
new incident/event type, notification service or automatic approval/replay operation.
It does not alter the original nine platform alerts or eight Outbox/notification alerts.

## Enable deliberately

Enable the application sampler with `APPROVAL_OBSERVABILITY_WORKFLOW_ENABLED=true`.
Independently mark only the expected scrape targets with `workflow_monitor: enabled`.
The shipped Prometheus example keeps this label commented out; enabling Outbox monitoring
alone does not enable these rules. Retain the expected label during a sampler outage.
Removing it silences the expectation rather than repairing the sample.

Mount `approval-workflow-population.rules.yml` beside the other production rule files
and load its explicit path from `deploy/observability/prometheus/prometheus.yml`.
Do not glob in generated `.test.yml` fixtures. Reload with the normal operator procedure.
Keep the health recording rule and all three alerts together in their supplied group,
in the supplied order. Do not copy just the alert expressions without the recording rule.
The existing Alertmanager receiver is reused unchanged; no new recipient is selected.

## Complete sample health

`approval:workflow_population_sample_healthy` exists with value 1 only for a reachable,
explicitly expected target with sample_up=1, all six gauges present/finite/nonnegative,
and `overdue <= sla_covered <= active` for each of PROCESS and TASK. Zero is a valid
count. Missing, NaN, infinity, negative or internally inconsistent values are not.
There is no zero fallback. Application-side sampling marks expired observations invalid
at two sampling intervals; rules consume that health signal, not a new wall-clock timeout.

All gauges must have identical common labels within the sample. Only the final join to
Prometheus `up` uses job/instance matching, because `up` need not carry the application's
common tag. Record and overdue alerts retain deployment labels. Data from another target,
another scope or mismatched common labels cannot fill a missing gauge. No replica sum,
business-ID tag, cardinality expansion or subtraction of lifecycle counters is introduced.

| Alert | Condition | Hold | Severity |
| --- | --- | --- | --- |
| ApprovalProcessPopulationOverdue | Complete healthy sample has process overdue > 0 | 2 minutes | warning |
| ApprovalTaskPopulationOverdue | Complete healthy sample has task overdue > 0 | 2 minutes | warning |
| ApprovalWorkflowPopulationMonitoringUnavailable | Expected reachable target has no complete healthy sample | 2 minutes | critical |

The two-minute holds are initial operator notification settings, not SLA durations or
capacity limits. Deadlines remain the persisted SLA `overdue_at` values. No arbitrary
active-population or percentage-coverage threshold is imposed. A population with no SLA
coverage can be a healthy database sample, but is not proof of SLA compliance.

## ApprovalProcessPopulationOverdue

Inspect the existing governed process SLA view, deadline, pause state and responsible
operator. This is a current population, not a count of timeout events, task rejections or
failed HTTP requests. A TASK-only timeout cannot activate this alert. Do not complete,
withdraw or replay approvals to clear a monitoring condition.

## ApprovalTaskPopulationOverdue

Inspect current PENDING/COMPLETING tasks of RUNNING processes with an ACTIVE TASK SLA.
Paused SLAs are covered but not overdue; terminal targets are excluded by the reader.
Collaboration-participant deadlines are outside these two populations. The alert cannot
identify a tenant or task; use existing authorized management views for that detail.

## ApprovalWorkflowPopulationMonitoringUnavailable

Check sampler opt-in, private database connection, query timeout, sample age and the
presence/labels of all six gauges. This alert owns an expected target that still answers
scrapes but cannot supply a complete workflow sample. Existing platform availability
rules own up=0 and whole-job scrape absence. A target removed entirely from discovery
requires separate operator target-inventory monitoring; this rule cannot infer that it
ought to exist from absent input alone.

Overdue alerts stop matching while their sample is unavailable. Such a resolved message
is **not business recovery**. Keep monitoring availability visible and require a fresh,
complete sample with zero overdue before declaring recovery. When sampling recovers but
overdue counts remain positive, the overdue hold begins again. Resolution of this
monitoring alert means data is available again, not that workflows became compliant.

## Executable verification and limits

`workflow-population-alert-fixtures.mjs` defines 54 deterministic scenarios with 243
alert assertions and 81 exact recording-rule sample assertions. Native promtool checks
process/task isolation, hold boundaries, true-zero recovery, transient conditions,
missing/NaN/infinite/negative/stale input, subset consistency, default-off behavior,
other jobs, disappearing/unreachable targets, replicas, and common-label preservation.

The existing `verify-prometheus-rules.mjs` provisioning path reuses the already pinned,
digest/version-checked promtool archive to parse this production file and evaluate the
fixtures. No extra download, workflow, retry or larger timeout is introduced. The current
CI's retained SLA/observability log must contain `OPS_WORKFLOW_POPULATION_RULES_VERIFIED`
with current rule/fixture digests before claiming native success. Generated test files
stay inside the existing owned temporary directory and are removed in its finally block.

`ops-workflow-population-alerting.test.mjs` inventories cases and exercises the verifier
with controlled runner results, including error propagation and no acceptance printing.
Those unit checks are not a PromQL interpreter. The permanent native provisioning test
runs the actual engine in CI and cannot pass by returning only a fixture-runner result.
The original platform and Outbox native tests and loopback delivery rehearsal remain.

Native rule evaluation uses controlled series and virtual time. It is not live delivery
from PostgreSQL through a collector and Alertmanager to a real recipient. Separate
application/database population tests, separate Outbox delivery tests, and these rule
tests must not be combined into that end-to-end claim. Rule-file inclusion does not prove
a deployed Prometheus/Alertmanager has reloaded it. Human delivery, live workflow-alert
routing, collector outage recovery and production capacity remain unverified here.
