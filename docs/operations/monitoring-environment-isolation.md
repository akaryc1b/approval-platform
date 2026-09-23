# Monitoring environment isolation

When one Prometheus observes multiple deployments, an identical `instance`
address in production and staging is not the same target. Configure a stable,
operator-owned `environment` label on each target. It must agree on `up`, all
application metrics, recorded health and the alert routed to Alertmanager.
Never use a tenant, user, request or process identifier as an environment value.

## Rules covered

HTTP error ratios and latency histograms retain `environment` in aggregation
and in minimum-traffic-floor matching. Healthy high-volume traffic in staging
cannot dilute production errors or make a low-traffic production signal pass
the traffic floor. The thresholds, histogram boundaries and hold times do not
change. SLA action counters and Connector ratios/timeouts likewise preserve
the originating environment while retaining their existing aggregation scope.

Workflow population health and monitoring-unavailable exclusion match
`job, instance, environment`. Outbox/notification alerts use the same target
identity for reachability and availability. Inside an Outbox sample, queue
values and sample health match all common application labels. Missing queue
values cannot be supplied by another environment or another application.
There is no extra queue, database query, poller, recording rule or retry action.

Existing Alertmanager grouping already includes environment. These rules
retain that label instead of discarding it before routing. Alert names,
severity, owners, annotations, thresholds and holds are unchanged. Existing
runbook links remain valid. Reload the existing rule files together through
the operator's ordinary configuration rollout; no automatic reload or
production change is performed by repository validation.

## Missing data and recovery

A failed or missing sample is not a healthy empty queue. A healthy target in
another environment cannot suppress the intended monitoring-unavailable alert.
Actual recovery is local to the target and starts its original hold again;
monitoring recovery is not business resolution. This does not add durable
PROCESS_EXECUTION_FAILED or business-resolution events.

Unlabeled legacy targets still work together as one unlabeled population;
they cannot obtain the missing environment identity automatically. They do
not match a target with an explicit nonempty environment. Assign labels before
combining multiple previously unlabeled deployments.

The target key assumes `instance` is unique within a job and environment.
Clusters with overlapping addresses inside the same environment must use
unique instance labels or separate Prometheus scopes. This is not tenant
access control or an arbitrary-cluster isolation guarantee.

The existing `ApprovalPlatformScrapeMissing` alert detects total disappearance
of the job, not disappearance of one environment while another still exists.
Per-environment disappearance requires an independently maintained inventory
of expected targets; none is invented here. A removed target is not reported
as a zero-valued healthy sample.

## Executable checks

`node --test scripts/tests/ops-monitoring-isolation.test.mjs` covers fixture
inventory, structural regressions, file safety and failure propagation. These
local checks do not evaluate PromQL.

The existing pinned promtool provisioner also evaluates the three actual rule
files against 51 scenarios and 2,079 exact-label/value assertions. Every
checkpoint checks all 20 alert names and the workflow health record. Cases
include same-address environments, failed/missing/stale samples, partial
samples, differing application labels, traffic dilution, low traffic, SLA
retry thresholds and hold/recovery behavior. The original rule fixtures are
retained; only their obsolete target-join spelling assertions are updated.

There is no extra download, workflow, shortened alert hold or enlarged native
suite deadline. A native failure propagates; a unit runner does not print
native acceptance. These rules are not proof of a production rollout, live
human notification, or a complete business-to-collector integration.

Prometheus set operators and aggregation semantics:
https://prometheus.io/docs/prometheus/latest/querying/operators/
