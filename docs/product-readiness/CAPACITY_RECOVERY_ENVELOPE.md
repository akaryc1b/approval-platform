# Capacity and Recovery Operating Envelope

Tracking: [#140](https://github.com/akaryc1b/approval-platform/issues/140), supporting [#107](https://github.com/akaryc1b/approval-platform/issues/107). Implementation remains in [Draft PR #142](https://github.com/akaryc1b/approval-platform/pull/142), independent from PR #92.

This is an executable **local-reference** purchase-payment capacity and recovery path, not a production sizing promise. Implementation, a green Workflow, and acceptance of the requested scope are different things.

## Entrypoints

```bash
pnpm demo:runtime:capacity-recovery:plan
pnpm demo:runtime:capacity-recovery:check
pnpm demo:runtime:capacity-recovery
pnpm demo:runtime:capacity-recovery:ci
```

The current serial runtime is:

```text
Small Demo and single-event recovery
→ Standard Deployment and Large Tenant profile matrix
→ fresh 96-instance Outbox / Generic REST Connector backlog drain
→ exact-main in-flight PostgreSQL 16 backup/restore into the candidate application
→ restored-state comparison and continuation of the same workflow
```

The fresh backlog is an existing implementation limitation, not the requested reuse of the profile matrix's 96 rows. The upgrade/restore implementation is already wired into the launcher exactly once; it is not unimplemented future work.

## Current acceptance blockers

PR #142 remains Draft and Issues #140 and #107 remain Open.

1. **Reuse the existing 96-row backlog.** The profile matrix currently cleans its lifecycle, and the drain stage resets disposable data and creates another 96 workflows. A successful fresh-volume drain does not prove handoff or reuse of the matrix's original rows.
2. **Replace prefix-based sandbox widening with an exact generated-event allowlist.** Current volume mode still accepts configured business-key and purchase-order prefixes. The observation guard below verifies continuity of the measured rows; it is not callback authorization and does not remove this limitation. No wildcard or prefix allowance can satisfy the requested exact-event boundary.
3. **Verify the final candidate naturally and audit its retained evidence.** The observation changes described below have unit coverage, but their new exact-Head runtime measurements remain **evidence pending**. Older success cannot be transferred to a new commit or broader claim.

These are scope and evidence blockers even when the existing configured-volume Workflow succeeds. No Ready transition, merge, Release or deployment is authorized by the historical run alone.

## Governed workload profiles

| Profile | Implementation | Configured local workload |
| --- | --- | --- |
| Small Demo | `EXECUTABLE_INITIAL` | Six generated instances; start concurrency 2; approval concurrency 4; 60 reads at concurrency 6 |
| Standard Deployment — Local Reference | `EXECUTABLE_EXTENDED` | 24 generated instances; start concurrency 6; approval concurrency 8; 480 reads at concurrency 12, then 240 reads at concurrency 24 |
| Large Tenant — Local Reference | `EXECUTABLE_EXTENDED` | 72 additional instances, 96 cumulative; start concurrency 12; approval concurrency 16; 1,440 reads at concurrency 24, then 480 reads at concurrency 48 |

All profiles use the existing PostgreSQL 16, Redis, Spring Boot, Flowable, six-identity directory, public attachment/start/task/instance/timeline APIs and transactional completion Outbox. Successful profiles are only:

```text
PASSED_AT_CONFIGURED_POINT_ONLY
```

The higher read point is an observation, not a maximum stable capacity search. Process CPU and RSS are point-in-time observations, not a peak-resource envelope.

Only `POST /api/approval/tasks/<uuid>/approve` receiving HTTP 500 with `APPROVAL_COMMAND_FAILED` and `retryable = true` may retry. The bound is four total attempts with 50/100/200 ms delays, the same idempotency key and fresh request/trace IDs. Transport attempts remain retained. Other writes, reads and network failures are not retried by this policy.

## Backlog drain observations

The existing connector, dispatcher and signed local sandbox remain the execution path. Before recovery, every target row must be `PENDING` with HTTP 503 evidence and zero accepted payment side effects. After recovery, every target row must be `DELIVERED` with HTTP 200 and its exact provider request ID; the sandbox must record exactly 96 accepted results. Five further observations check stability.

The observation guard additionally pins the recovery baseline's Outbox ID, event ID, event type, aggregate ID, idempotency key, request ID and trace ID. Every recovery and stability snapshot must preserve that set and mapping. Duplicate, missing, added or replaced rows, decreasing attempt counts, changed delivery timestamps, and `DELIVERED` status regressions fail closed. A failed observer cannot resume or publish successful metrics.

The runtime now records each event's first observed delivery time using Node's monotonic `performance.now()` clock. The start boundary is immediately before writing the existing recovery control file. The measurement ends at the database/status observation, not the provider's internal side effect. Retained results include:

- first observed delivery latency, full observed drain duration and events per second;
- per-event first-observation samples and interpolated P50/P95/P99;
- observation count and maximum observation gap, including the stability window;
- identity continuity and failure diagnostics, including partial samples when a run fails.

The five stability observations do not increase the completed-drain duration or rewrite first-delivery samples. Database wall-clock timestamps are retained for consistency checks but are not subtracted from the Node clock to calculate latency.

Summary fields `recoveryElapsedMs` and `deliveredPerSecond` remain available. `observedDrain` carries the new distribution and explicit measurement boundary:

```text
RECOVERY_CONTROL_WRITE_START_TO_FIRST_OBSERVED_DELIVERED
POLL_OBSERVED_COMPLETION_NOT_PROVIDER_LATENCY
LOCAL_SINGLE_NODE_CONFIGURED_VOLUME_NOT_PRODUCTION_RTO
```

Polling granularity and query overhead affect these observations. They are not callback service-time percentiles, production drain capacity or production RTO. New numerical results must come from the new exact-Head retained artifact, not reconstructed timing for an older run.

## In-flight upgrade and restore rehearsal

The implemented rehearsal resolves the exact PR base and candidate SHAs, creates a detached base worktree, starts the baseline application, advances a real purchase to finance countersign, and captures business state. It then quiesces the application, executes PostgreSQL 16 `pg_dump`, rebuilds disposable PostgreSQL/Redis, restores with `pg_restore`, and starts the candidate application.

It compares the restored instance, task history, active tasks and audit timeline with the pre-backup state, continues the same workflow through public APIs, and verifies HTTP 503 / PENDING / recovery / DELIVERED with one accepted payment side effect. Cleanup covers the backend, ports, containers, disposable volume, temporary backup and worktree.

Its claim is limited to:

```text
LOCAL_IN_FLIGHT_POSTGRES_UPGRADE_RESTORE_REHEARSAL_PASSED
LOCAL_QUIESCED_POSTGRESQL_16_REHEARSAL_NOT_PRODUCTION_RPO_RTO
```

Local committed-record loss at a quiesced point and local application-stop-to-first-business-read time do not establish crash consistency, production RPO/RTO, zero-downtime upgrades or rollback safety.

## Historical exact-Head evidence

The preceding natural [Run 33854019996](https://github.com/akaryc1b/approval-platform/actions/runs/33854019996), Workflow #1717, succeeded for:

```text
Commit: 2c9a8643dac48e767afd71a4ef5321856e10baa1
Tree:   7ffe4c032135ae2048fe876a51f36ed305939ca6
Artifact: approval-vben-33854019996
Artifact ID: 9929995042
ZIP SHA-256: ca6784a7d7398983e46eb2c27d2f91c7ae5d77209791365eb78b698b7528fc96
```

The downloaded ZIP and all eight embedded envelopes were checked for their declared byte sizes and SHA-256 digests. They include two PC/H5 runs, two purchase-payment runs, Small Demo, the profile matrix, the fresh-volume drain and the upgrade/restore rehearsal. The envelopes bind to the same candidate tree and Workflow run.

That drain retained 96 PENDING rows followed by 96 DELIVERED rows and 96 accepted results. Its older summary reported 10,723 ms and 8.953 events/second. The old evidence does not contain the new per-event monotonic first-observation distribution, and it does not prove reuse of the matrix backlog or an exact-event allowlist.

## Retained evidence

Runtime evidence stays untracked under `.runtime/capacity-recovery/<run-id>/`. The existing bounded JSON envelopes in the permanent `root-install.log` artifact retain per-file size and SHA-256 digests; no second evidence system or Workflow is introduced.

The drain requires source identity, its contract, generated-instance identities, command attempts, unavailable and delivered snapshots, cleanup, summary, and now:

```text
outbox-backlog-observations.json
```

This file contains at most 96 first-delivery samples. Partial observations are written on failure before cleanup, and a missing observations file prevents a passed drain envelope. Raw runtime evidence must not be committed.

## Explicit limitations

```text
PRODUCTION_CAPACITY_NOT_VERIFIED
MAXIMUM_STABLE_ENVELOPE_NOT_VERIFIED
PEAK_RESOURCE_ENVELOPE_NOT_VERIFIED
MULTI_NODE_CAPACITY_NOT_VERIFIED
PRODUCTION_OUTBOX_DRAIN_RATE_NOT_VERIFIED
MULTI_NODE_OUTBOX_DRAIN_NOT_VERIFIED
PRODUCTION_RPO_NOT_VERIFIED
PRODUCTION_RTO_NOT_VERIFIED
PRODUCTION_RPO_RTO_NOT_VERIFIED
CRASH_CONSISTENCY_NOT_VERIFIED
ZERO_DOWNTIME_UPGRADE_NOT_VERIFIED
ROLLBACK_REHEARSAL_NOT_VERIFIED
MYSQL_8_4_NOT_VERIFIED
PRODUCTION_DEPLOYMENT_NOT_VERIFIED
RELEASE_NOT_CREATED
```

PostgreSQL 16 is the only database target of this work. PR #92 is neither modified nor treated as accepted.
