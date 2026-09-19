# Workflow population notification rehearsal

This extends the population rules described in `workflow-population-alerting.md`.
It exercises real Prometheus and Alertmanager processes through a real loopback HTTP
receiver. Inputs are controlled metric fixtures, not approval database rows or a
production notification provider. Business resolution events and human delivery remain
separate work; an Alertmanager resolved state is not an authoritative business event.

## Scenario

The existing native provisioner now runs three targets concurrently, initially healthy:

| Target | Injected condition | Original alert |
| --- | --- | --- |
| Process | Process overdue 1, task overdue 0 | ApprovalProcessPopulationOverdue |
| Task | Task overdue 1, process overdue 0 | ApprovalTaskPopulationOverdue |
| Unavailable | sample_up 0 and six NaN gauges | ApprovalWorkflowPopulationMonitoringUnavailable |

The production rule file is loaded unchanged, including each **two-minute hold**.
The rehearsal observes all three alerts pending and then firing, with exact target,
severity, owner, component and common labels. Wrong-scope or extra alerts fail validation.
The current production receiver template is reused; only its local destination and
one-second test batching replace the operator address and normal batching delays.
This does not verify production group_wait/group_interval timing.

For **each** alert, the receiver records the first firing and returns HTTP 503.
A subsequent notification must retain its fingerprint and startsAt. Only after all
three retries are acknowledged are all targets restored to fresh, healthy zero-overdue
samples. Each alert must deliver a matching resolved notification and Prometheus must
return to zero active alerts. The receipt book contains six unique in-memory records
(three firing, three resolved), with at least nine HTTP attempts in total.

This intentionally demonstrates at-least-once transport and test-recipient deduplication,
not durable production exactly-once delivery. The receiver state is test-only and is
not deployed as `approval-ops-notifier`. No business row, approval action, payment,
production receiver configuration, SLA or alert threshold is modified.

## Execution and failure bounds

The existing `scripts/ops/verify-prometheus-rules.mjs` provisioner reuses its already
SHA-256/version-verified Prometheus/promtool and Alertmanager binaries. No extra native
download or automatic workflow is added. The existing 360-second native-test ceiling
is unchanged. Three two-minute holds run in parallel, not serially.

The live stage has a 210-second runtime deadline and a 240-second process bound.
HTTP reads use two-second timeouts and byte limits; receiver requests allow at most
64 KiB and 20 attempts per fixed alert. Child environments contain only PATH/LANG/
LC_ALL, not deployment credentials. All listeners bind to 127.0.0.1. Shutdown terminates
owned children/listeners and removes only the owned temporary directory. A forced
native-process kill is a failure, never successful cleanup.

Only native provisioning publishes `OPS_WORKFLOW_POPULATION_DELIVERY_VERIFIED` after
validating the exact production rule and receiver digests. Unit-injected runners return
values without publishing acceptance. The receipt explicitly sets
`databaseBusinessChainVerified`, `humanNotificationVerified`, and
`productionBatchTimingVerified` to false.

## Related operations view

Import `deploy/observability/grafana/approval-operations.json` using the instructions in
`operations-dashboard.md`. Observe sampler health alongside overdue counts; a vanished
alert during sampling failure is not evidence that the business condition recovered.

Protocol references: [Alertmanager receiver configuration and webhook format](https://prometheus.io/docs/alerting/latest/configuration/).
Repository-native tests and their current CI result, not the upstream protocol reference,
determine whether this implementation has executed successfully.
