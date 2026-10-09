# SLA completion-lag export and explicitly configured alerts

This extends `sla-completion-lag.md`. It monitors the final successful **OVERDUE
execution action**, not first timeout detection, total process/task duration,
business recovery, or notification receipt. A process that never finishes an
OVERDUE action is absent from this distribution. Keep existing workflow-population,
SLA execution and Outbox/engine-job alerts; these rules do not replace them.

## Application and scrape target

Set `APPROVAL_OBSERVABILITY_SLA_LAG_ENABLED=true` in the application. The existing
private `/actuator/prometheus` endpoint exports:

- `approval_sla_overdue_completion_lag_seconds_count`, `_sum`, `_bucket` after the
  first accepted observation, separately by `target`, `source`, and `time_basis`;
- `approval_sla_overdue_completion_observations_dropped_total` from observer startup.

No timing samples before the first successful action is normal. It is not a zero
latency and not evidence that unfinished actions are healthy. The application still
owns the worker; no new timer, polling thread, write or external sender is added.

Apply `environment` and `sla_lag_monitor: enabled` as **scrape target labels**, not
only Prometheus external labels. The application common label is
`application="approval-platform"`; the job is `approval-platform`. Example:

```yaml
static_configs:
  - targets: ['approval-1:8081']
    labels:
      environment: production
      sla_lag_monitor: enabled
```

The generated rules select the exact `instance` label, which may differ from the
address when relabeling is used. Names must be unique within job/environment.
Do not expose the management listener publicly. No production rule is automatically
loaded, no target expectation is changed by running tests, and no default objective
is assumed.

## Generate one rule file per target

An operator must select each objective and minimum sample estimate. This **example
is not a production recommendation**:

```json
{
  "environment": "production",
  "instance": "approval-1:8081",
  "objectives": [
    { "target": "task", "timeBasis": "working_time", "thresholdSeconds": 300, "minimumSamples": 20 },
    { "target": "task", "timeBasis": "natural_time", "thresholdSeconds": 3600, "minimumSamples": 20 }
  ]
}
```

```sh
node scripts/ops/sla-completion-alerts.mjs --config sla-objectives.json --output approval-sla-completion.rules.yml
promtool check rules approval-sla-completion.rules.yml
```

The generator creates a new JSON-format YAML rule file and refuses to overwrite an
existing file or symlink. Invalid/unknown fields, duplicate objectives and selector
injection fail before writing. Up to six unique process/task/collaboration ×
natural_time/working_time objectives can share a file. Explicitly provision the
reviewed output through the existing Prometheus `rule_files` list. Keep only one
file for a target to avoid duplicated monitoring rules. Update configuration through
the operator's usual change process, not by overwriting an unreviewed live file.

The distribution window is 30 minutes; P95 must exceed the selected objective for
five minutes with the configured sample floor. `increase` handles counter resets
and extrapolates a count estimate; the floor is not an exact audit count. P95 is a
classic-histogram estimate, not an exact order statistic. The timer's seven-day
expected histogram range limits tail resolution, so thresholds must be positive
and below seven days. Original and governed-replay executions are not combined;
only `source="original"` contributes to these objectives. Working and natural time
remain separate, and one environment never supplies another's health or traffic.

## ApprovalSlaOverdueCompletionLagHigh

Inspect successful action lag and the pinned calendar definition in the existing
application evidence. The rule requires a reachable target, a valid observer
counter, enough original samples, a finite current count, and an infinity bucket
that agrees with that count. It never substitutes missing values with zero.
A recent dropped-observation increase suppresses the P95 alert because the sample
may be biased. Correct the omission first; a fresh five-minute hold is required
once the observation window is clean. Low sample volume is not a latency pass.
No threshold here changes a business deadline, enables automatic approval or
replays an Outbox message.

## ApprovalSlaCompletionObservationsDropped

The omission counter increased in the past 30 minutes for a reachable, enabled
target and the condition persisted for two minutes. Inspect calendar/version
availability, outer transaction usage, JDBC read bounds and the metrics registry.
The action may already be successfully committed. Do not retry business operations
just to repair a metric, and do not interpret an omission as a lost durable event.
A counter reset alone does not represent new omissions. Failures before a first
scrape can be unobservable as increments; use application diagnostics as well.

## ApprovalSlaCompletionMonitoringUnavailable

The selected target is reachable but the observer counter is missing, non-finite
or negative for two minutes. Verify the independent application and scrape opt-ins.
Unused but correctly initialized observers expose a zero counter and do not alert.
This checks observer-counter availability, not completeness of every histogram
bucket. Missing/invalid timing denominators or infinity buckets suppress P95; they
are not asserted healthy. Down or removed targets are handled by existing runtime
availability rules, not this condition.

## Verification and boundaries

`ApprovalSlaLagPrometheusPostgresTest` starts the actual Spring Boot application with
an isolated PostgreSQL database and private management port. It uses real SLA
claims, the timeout event recorder, immutable calendar/policy storage and Outbox;
HTTP count/sum/infinity-bucket samples must reconcile to 237,602 natural seconds and
7,202 working seconds after a committed detection/acknowledgement retry. A repeat
poll does not add samples. Invalid observation evidence increments the actual HTTP
omission counter without changing the committed Outbox. The public application port
must not provide these metrics, and exported timing labels cannot contain fixture
business identities. Seeded projection records are controlled fixtures: this is not
a browser-driven Flowable approval or a human-recipient test.

`verify-sla-completion-alerts.mjs` evaluates the same generated rule expressions in
the existing digest-pinned promtool lifecycle. The cases cover holds, recovery,
unused observers, sample floors, cross-environment isolation, replay exclusion,
counter resets, invalid/missing/stale counters and histogram consistency. Native
failure propagates; mocked unit runners do not print native acceptance. No new
workflow, binary download or larger global timeout is added. Read current-commit
CI results; local generator tests alone do not prove native rule or HTTP execution.

Alert resolution can mean samples aged out, sampling failed or a target disappeared;
it is **not a durable business-resolution event**. New generated rules have native
engine tests but not a separate live Alertmanager/human-delivery drill. This feature
is not full calendar-aware process/task SLA latency or complete Issue #146.

References: Prometheus query functions and rule-testing documentation:
https://prometheus.io/docs/prometheus/latest/querying/functions/
https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/
