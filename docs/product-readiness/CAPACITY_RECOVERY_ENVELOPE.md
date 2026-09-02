# Capacity and Recovery Operating Envelope — Initial Slice

Tracking: [#140 — Publish capacity and recovery operating envelope](https://github.com/akaryc1b/approval-platform/issues/140), supporting [#107](https://github.com/akaryc1b/approval-platform/issues/107).

This page defines the first executable capacity/recovery slice for the existing purchase-payment product path. It publishes measured local evidence for one configured workload point. It does not publish a production sizing promise, marketing TPS figure, peak-resource envelope, RPO or RTO.

## Entrypoints

```bash
pnpm demo:runtime:capacity-recovery:plan
pnpm demo:runtime:capacity-recovery:check
pnpm demo:runtime:capacity-recovery
pnpm demo:runtime:capacity-recovery:ci
```

Every runtime writes only to:

```text
.runtime/capacity-recovery/<run-id>/
```

The directory is ignored by Git and must not be committed.

## Governed profiles

| Profile | Current executable status | Dataset boundary |
| --- | --- | --- |
| Small Demo | `EXECUTABLE` | One tenant, six directory identities, one eight-node purchase-payment definition, one deterministic seeded instance and six generated instances |
| Standard Deployment | `PLANNED` | Five tenants, 500 identities, 25 definitions, 1,000 active and 10,000 historical instances |
| Large Tenant | `PLANNED` | One tenant, 10,000 identities, 100 definitions, 10,000 active and 1,000,000 historical instances |

Only Small Demo may emit the initial slice claims. The two larger profiles remain declarations for later workload generation and do not inherit Small Demo results.

## Small Demo measured path

The runtime reuses `demo-backend.mjs`, PostgreSQL 16, Redis, Spring Boot, Flowable and the deterministic purchase-payment Seed. It then uses the public application HTTP boundaries to:

```text
upload one bounded attachment per request
→ start six unique purchase-payment instances with concurrency 2
→ execute 60 pending-task list/detail reads with concurrency 6
→ approve manager tasks
→ approve finance-review tasks
→ complete two governed finance-countersign tasks per instance
→ approve payment confirmation
→ re-read every completed instance and timeline
→ clean the existing disposable runtime lifecycle
```

The approval stages use bounded concurrency 4 across independent process instances. They do not write platform or Flowable tables directly.

A successful execution records:

- exact commit and tree identity;
- operating-system, CPU, memory and tool versions;
- PostgreSQL server version, database size, connections, commits, rollbacks, block reads/hits, temporary bytes, locks and deadlocks;
- point-in-time backend process-tree RSS, virtual memory and CPU observations;
- every HTTP operation, status and latency;
- request count, throughput, error rate and P50/P95/P99 latency by operation;
- observed stage queue delay;
- completed purchase flows per second;
- database storage growth;
- exact tenant, business-key, attachment and instance identities;
- cleanup results.

The process observations are point-in-time samples. They are explicitly not a peak CPU or memory envelope.

## Configured acceptance point

Small Demo passes only when the configured workload satisfies all declared thresholds:

```text
error rate = 0
pending-list/detail P95 <= 2,500 ms
pending-list/detail P99 <= 5,000 ms
pending-list/detail throughput >= 1 request/second
completed purchase flows >= 0.02 flow/second
```

The emitted profile status is:

```text
PASSED_AT_CONFIGURED_POINT_ONLY
```

This means the exact configured point passed. It does not mean the system was driven beyond that point, and it is not a maximum production capacity statement.

## Outbox and Connector recovery reuse

The capacity command does not create a second Outbox, Connector or sandbox test. It requires exact-Head evidence from the accepted purchase-payment E2E:

```text
transactional completion Outbox
→ signed local payment sandbox returns HTTP 503
→ Outbox remains PENDING and recoverable
→ sandbox recovery is enabled
→ bounded retry reaches DELIVERED
→ exactly one payment side effect is accepted
```

CI reuses the immediately preceding exact-Head E2E evidence. A local run without accepted exact-Head evidence executes enough clean E2E runs to satisfy the existing two-run gate.

The capacity summary measures the filesystem evidence interval between retained PENDING and DELIVERED records. That number is labelled:

```text
FILESYSTEM_EVIDENCE_INTERVAL_NOT_PRODUCTION_RTO
```

It must never be presented as production RTO.

## Initial bounded claims

Only a successful exact-Head runtime and complete cleanup may emit:

```text
SMALL_DEMO_CAPACITY_BASELINE_PASSED
SMALL_DEMO_CONCURRENT_PURCHASE_FLOW_PASSED
SMALL_DEMO_READ_PRESSURE_PASSED
OUTBOX_CONNECTOR_RECOVERY_REUSED_AND_MEASURED
CAPACITY_RECOVERY_INITIAL_SLICE_PUBLISHED
```

## Explicit limitations

```text
STANDARD_DEPLOYMENT_CAPACITY_NOT_VERIFIED
LARGE_TENANT_CAPACITY_NOT_VERIFIED
PRODUCTION_CAPACITY_NOT_VERIFIED
PEAK_RESOURCE_ENVELOPE_NOT_VERIFIED
MULTI_NODE_CAPACITY_NOT_VERIFIED
UPGRADE_REHEARSAL_NOT_VERIFIED
BACKUP_RESTORE_NOT_VERIFIED
RPO_RTO_NOT_VERIFIED
MYSQL_8_4_NOT_VERIFIED
PRODUCTION_DEPLOYMENT_NOT_VERIFIED
RELEASE_NOT_CREATED
```

PostgreSQL 16 is the only database target for this slice. The independent MySQL 8.4 work in PR #92 is neither modified nor treated as accepted.

## Remaining delivery

Issue #140 remains open after the initial slice. Subsequent work must add real Standard Deployment and Large Tenant datasets, observed behavior beyond the stable point, multi-node application measurements, and an executable in-flight upgrade plus backup/restore rehearsal with deterministic consistency summaries and actual bounded RPO/RTO measurements.
