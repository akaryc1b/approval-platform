# Capacity and Recovery Operating Envelope

Tracking: [#140 — Publish capacity and recovery operating envelope](https://github.com/akaryc1b/approval-platform/issues/140), supporting [#107](https://github.com/akaryc1b/approval-platform/issues/107).

This page defines the executable local-reference capacity and recovery path for the existing purchase-payment product scenario. It publishes measured evidence only after an exact-Head runtime succeeds. It does not publish a production sizing promise, marketing TPS figure, maximum stable envelope, peak-resource envelope, RPO or RTO.

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

| Profile | Implementation status | Local-reference dataset |
| --- | --- | --- |
| Small Demo | `EXECUTABLE_INITIAL` / exact-Head evidence accepted for the earlier slice | One tenant, six governed directory identities, one eight-node purchase-payment definition, one deterministic seeded instance and six generated instances |
| Standard Deployment — Local Reference | `EXECUTABLE_EXTENDED` / evidence pending on the current Head | One tenant, the governed six-identity directory, 24 generated purchase-payment instances, 480 configured reads, 240 higher-concurrency reads and complete five-task approval flows |
| Large Tenant — Local Reference | `EXECUTABLE_EXTENDED` / evidence pending on the current Head | The same governed tenant and directory, 72 additional generated instances, 1,440 configured reads, 480 higher-concurrency reads and a cumulative 96 generated instances across the extended matrix |

The profile names describe workload classes, not production deployment promises. The Standard and Large profiles deliberately use the existing local Product Alpha tenant, users, process definition, form and attachment model so the test measures the same real business path instead of synthesizing a second platform.

## Small Demo measured path

The initial runtime reuses `demo-backend.mjs`, PostgreSQL 16, Redis, Spring Boot, Flowable and the deterministic purchase-payment Seed. It then uses public application HTTP boundaries to:

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

The accepted earlier exact-Head Small Demo result is labelled:

```text
PASSED_AT_CONFIGURED_POINT_ONLY
```

That means the exact configured point passed. It is not a maximum or production capacity statement.

## Extended Standard and Large matrix

After the initial Small Demo plus purchase-payment recovery run, the same one-command entrypoint starts a fresh disposable local backend lifecycle and executes the two larger local-reference profiles serially.

For each profile it performs:

```text
bounded attachment uploads
→ concurrent purchase-payment starts
→ configured pending-list/detail read pressure
→ a higher-concurrency read observation
→ manager approval
→ finance review
→ two-person finance countersign
→ payment confirmation
→ completed-instance checks
→ sampled timeline checks
→ read-only completion-Outbox backlog count
```

The Standard profile uses:

```text
generated instances: 24
start concurrency: 6
approval concurrency: 8
configured reads: 480 at concurrency 12
higher-point reads: 240 at concurrency 24
```

The Large profile adds:

```text
generated instances: 72
cumulative generated instances: 96
start concurrency: 12
approval concurrency: 16
configured reads: 1,440 at concurrency 24
higher-point reads: 480 at concurrency 48
```

The higher read point is retained as:

```text
HIGHER_THAN_CONFIGURED_READ_POINT_OBSERVED_NOT_MAXIMUM_ENVELOPE
```

It demonstrates observed behavior above the configured read concurrency. It does not search for or identify the maximum stable envelope.

## Measurement and evidence

A successful profile records:

- exact commit and tree identity;
- operating-system, CPU, memory and tool versions;
- PostgreSQL server version, database size, connections, commits, rollbacks, block reads/hits, temporary bytes, locks and deadlocks;
- point-in-time backend process-tree RSS, virtual memory and CPU observations;
- every HTTP operation, status and latency;
- request count, throughput, error rate and P50/P95/P99 latency by operation;
- observed task-stage queue delay;
- completed purchase flows per second;
- database storage growth;
- exact tenant, business-key, attachment and instance identities;
- complete cleanup results.

Process observations are point-in-time samples. They are explicitly not a peak CPU or memory envelope.

Each profile passes only when its configured point satisfies its governed zero-error, latency and throughput thresholds. Every successful profile remains labelled:

```text
PASSED_AT_CONFIGURED_POINT_ONLY
```

## Outbox and Connector recovery

The capacity command does not create a second Outbox, Connector or payment sandbox. It reuses the existing purchase-payment E2E to prove:

```text
transactional completion Outbox
→ signed local payment sandbox returns HTTP 503
→ Outbox remains PENDING and recoverable
→ sandbox recovery is enabled
→ bounded retry reaches DELIVERED
→ exactly one payment side effect is accepted
```

The measured interval between the retained PENDING and DELIVERED evidence is labelled:

```text
FILESYSTEM_EVIDENCE_INTERVAL_NOT_PRODUCTION_RTO
```

It must never be presented as production RTO.

The extended profile matrix separately creates a bounded completion-Outbox backlog while the dispatcher is disabled. It measures the exact PENDING row count for the generated completed instances. It does not yet run a high-volume Connector drain, so this remains explicit:

```text
OUTBOX_CONNECTOR_BACKLOG_DRAIN_VOLUME_NOT_VERIFIED
```

## Claims already accepted for the earlier Small Demo Head

```text
SMALL_DEMO_CAPACITY_BASELINE_PASSED
SMALL_DEMO_CONCURRENT_PURCHASE_FLOW_PASSED
SMALL_DEMO_READ_PRESSURE_PASSED
OUTBOX_CONNECTOR_RECOVERY_REUSED_AND_MEASURED
CAPACITY_RECOVERY_INITIAL_SLICE_PUBLISHED
```

These claims remain bound to their recorded exact Head, Workflow Run and Artifact. Their presence here does not release them for a later Head.

## Extended claims gated on current exact-Head evidence

The executable code may emit the following only after both larger profiles and cleanup succeed:

```text
STANDARD_DEPLOYMENT_LOCAL_REFERENCE_PASSED
LARGE_TENANT_LOCAL_REFERENCE_PASSED
MULTI_INSTANCE_APPROVAL_THROUGHPUT_MEASURED
OUTBOX_BACKLOG_CREATION_VOLUME_MEASURED
BEYOND_CONFIGURED_READ_POINT_OBSERVED
CAPACITY_PROFILE_MATRIX_PUBLISHED
```

Until a natural Workflow and retained Artifact are audited, these are implementation targets with evidence pending, not accepted results.

## Explicit limitations

```text
PRODUCTION_CAPACITY_NOT_VERIFIED
MAXIMUM_STABLE_ENVELOPE_NOT_VERIFIED
PEAK_RESOURCE_ENVELOPE_NOT_VERIFIED
MULTI_NODE_CAPACITY_NOT_VERIFIED
OUTBOX_CONNECTOR_BACKLOG_DRAIN_VOLUME_NOT_VERIFIED
UPGRADE_REHEARSAL_NOT_VERIFIED
BACKUP_RESTORE_NOT_VERIFIED
RPO_RTO_NOT_VERIFIED
MYSQL_8_4_NOT_VERIFIED
PRODUCTION_DEPLOYMENT_NOT_VERIFIED
RELEASE_NOT_CREATED
```

PostgreSQL 16 is the only database target for this work. The independent MySQL 8.4 work in PR #92 is neither modified nor treated as accepted.

## Remaining delivery

Issue #140 remains open. After exact-Head Standard and Large evidence, the same Draft PR must still add a bounded high-volume Outbox/Connector backlog drain, then an executable in-flight upgrade plus backup/restore rehearsal with deterministic pre/post consistency summaries and actual measured local RPO/RTO. Multi-node and production-capacity claims remain separate work.
