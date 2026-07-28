# M5-F2 Observability Metric Catalog

## Scope

This catalog covers only M5 governed process-instance migration and the tenant-scoped read-only Operations APIs. It does not authorize production execution, create a scheduler, scan tenants, or export tenant, plan, intent, attempt, instance, request or trace identity as metric labels.

Production execution remains `NOT_AUTHORIZED`.

## Cardinality rules

Allowed labels are closed enums owned by server code. The following values are forbidden as metric labels:

- tenant ID, operator ID or role;
- definition key, release ID or package hash;
- plan, intent, attempt, approval-instance or engine-instance ID;
- request ID, trace ID, lease owner or fencing owner;
- exception class, exception message, error message, SQL or request parameter;
- any user-provided free text.

Request and trace IDs may appear only in bounded, redacted response headers or structured logs. Lease and fence owners returned by E2 are irreversible short SHA-256 references and still do not become metric labels.

## Registered metrics

| Metric | Type | Labels | Closed label values | Trigger | Cardinality risk | Alert use | Sensitive data |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `approval.migration.operations.read` | Counter | `operation`, `result`, `failure_class` | `operation`: `summary`, `plan_list`, `plan_detail`, `instance_list`, `plan_diagnostics`, `diagnostic_instance_list`, `instance_diagnostics`; `result`: `success`, `failure`; `failure_class`: `none`, `invalid_request`, `unauthenticated`, `forbidden`, `not_found`, `method_not_allowed`, `conflict`, `rate_limited`, `server_error` | Any recognized E1/E2 GET response body | Low: fixed 7 × 2 × 9 maximum combinations | 5xx, auth failures, forbidden growth, rate limiting | No |
| `approval.migration.operations.read.latency` | Timer | same three labels | same closed sets | Any recognized E1/E2 GET request, including failed requests | Low: same fixed combinations; histogram publication disabled | Latency SLO and degraded read diagnostics | No |
| `approval.migration.safety.event` | Counter | `event` | `unknown_entered`, `reconciliation_observation_recorded`, `reconciliation_manual_review_required`, `canary_limit_reached`, `orchestration_bounded_stop`, `kill_switch_blocked`, `plan_aggregation_completed`, `stale_ownership_rejected`, `duplicate_outcome_prevented`, `verification_mismatch`, `runtime_binding_cas_failed`, `completion_evidence_failed` | Existing governed state transitions or prevented unsafe writes | Low: exactly 12 pre-registered values | UNKNOWN growth, manual backlog pressure, stale authority, duplicate prevention, verification/CAS/completion failures, canary/orchestration stops | No |
| `approval.migration.safety.feature.enabled` | Gauge | `feature` | `execution`, `worker`, `orchestration`, `aggregation`, `automatic_reconciliation`, `kill_switch` | Effective server configuration at application startup | Low: exactly 6 pre-registered values | Execution/worker/orchestration/aggregation/reconciliation drift and Kill Switch state | No |

The feature gauge reports configuration state only. A value of `1` is not an execution authorization token and cannot bypass the one-shot gates, tenant authorization, ownership, lease, fencing, verification, CAS or evidence requirements.

## Current-count diagnostics and cross-tenant safety

M5-E2 exposes current `unknownCount`, `ambiguousUnknownCount`, `manualReviewCount`, `reconcilingCount` and related counts only through an authorized tenant-scoped plan diagnostic GET. They are intentionally **not** emitted as gauges labelled by tenant or plan and are not computed by a background cross-tenant scanner.

Operational alerting therefore uses:

1. growth rates of the closed safety-event counters for early warning;
2. the closed feature-state gauge for default-disabled controls and Kill Switch state;
3. tenant-scoped E2 diagnostics for operator confirmation and backlog enumeration;
4. no global tenant-enumerating scheduled query.

This is a deliberate security/cardinality trade-off. A future global fleet metric would require a separately authorized aggregate source that contains no tenant labels; it is not part of M5.

## Failure-open observability

- Counter/timer registration and writes are wrapped so registry failures cannot change API responses or migration state.
- Application services call `ApprovalMigrationSafetyTelemetry.safeRecord`; a throwing telemetry adapter is ignored.
- Telemetry failure never initiates a retry, reconciliation, worker run or migration dispatch.
- Observability-pipeline degradation is detected by alerting on missing scrape/heartbeat signals, not by attempting to write another in-process metric through the failed path.

## Timer bounds

The read latency timer declares an expected range of 1 millisecond to 30 seconds and does not enable percentile histograms in application code. Backend-specific histogram or SLO buckets, when desired, must be configured outside the application and reviewed for storage impact.

## Stability evidence

Java tests repeatedly record the same read and every safety event, then assert that meter registration count remains stable and that forbidden label keys are absent. Node boundary tests also reject resource IDs, messages, exceptions and dynamic labels in M5 telemetry sources.
