# Capacity and Recovery Operating Envelope

Tracking: [#140](https://github.com/akaryc1b/approval-platform/issues/140), supporting [#107](https://github.com/akaryc1b/approval-platform/issues/107). Implementation remains in [PR #142](https://github.com/akaryc1b/approval-platform/pull/142), independent from PR #92.

This executable path measures the existing purchase-payment scenario on one local-reference PostgreSQL 16 / Redis / Spring Boot / Flowable application. Implementation and test success do not constitute production capacity or recovery guarantees.

## Entrypoints

```bash
pnpm demo:runtime:capacity-recovery:plan
pnpm demo:runtime:capacity-recovery:check
pnpm demo:runtime:capacity-recovery
pnpm demo:runtime:capacity-recovery:ci
```

The serial runtime now executes:

```text
Small Demo and single-event recovery
→ Standard Deployment: create and complete 24 real purchase-payment instances
→ Large Tenant: create and complete 72 more instances on the same database
→ stop the profile application, retain PostgreSQL/Redis and the original 96 PENDING events
→ pin the original rows before starting the existing Connector and unavailable sandbox
→ observe HTTP 503 for all 96 events
→ atomically publish the exact generated-event allowlist
→ enable the existing recovery control file
→ observe the same 96 events DELIVERED with exactly 96 accepted payment results
→ five stable observations, then parent-owned full cleanup
→ exact-PR-base in-flight PostgreSQL backup/restore into the candidate application
→ restored-state comparison, continuation, exact single-event authorization and cleanup
```

There is no second batch of 96 workflows. The drain stage does not reset data, upload attachments, start instances or execute approval commands. It receives the two profiles' original completed-instance identities directly before the matrix's `finally` cleanup.

## Candidate status

The original-backlog handoff and exact-event authorization are implemented together. Their new exact-Head natural runtime and retained Artifact audit remain **evidence pending** until the candidate run completes and its evidence is checked. PR #142 remains Draft; Issues #140 and #107 remain Open until their acceptance requirements are met.

The predecessor `0fb92e4ec4d6f1c4ec8aaaae5b4f018212e33ece` passed natural Run `33936188078`. That historical run used the preceding fresh-volume/prefix-based path and does not accept these new changes. The current PR records the new commit, tree, Workflow and artifact identities without committing generated runtime evidence.

## Governed profiles

| Profile | Implementation | Configured local workload |
| --- | --- | --- |
| Small Demo | `EXECUTABLE_INITIAL` | Six generated instances; start concurrency 2; approval concurrency 4; 60 reads at concurrency 6 |
| Standard Deployment — Local Reference | `EXECUTABLE_EXTENDED` | 24 instances; start concurrency 6; approval concurrency 8; 480 reads at concurrency 12, then 240 reads at concurrency 24 |
| Large Tenant — Local Reference | `EXECUTABLE_EXTENDED` | 72 additional instances, 96 cumulative; start concurrency 12; approval concurrency 16; 1,440 reads at concurrency 24, then 480 reads at concurrency 48 |

Successful profiles mean only `PASSED_AT_CONFIGURED_POINT_ONLY`. The higher read point is not a maximum stable capacity search. CPU and RSS are point-in-time observations, not a peak-resource envelope.

Only `POST /api/approval/tasks/<uuid>/approve` receiving HTTP 500, `APPROVAL_COMMAND_FAILED` and `retryable = true` may retry: four total attempts, 50/100/200 ms delays, the same idempotency key and fresh request/trace IDs. Transport attempts remain retained in the profile evidence. The drain creates no additional approval-command attempts.

## Original-event continuity and authorization

Before the dispatcher starts, the handoff requires 96 unique original completed instances and 96 PENDING Outbox rows with zero attempts, no delivery timestamp, no provider response and canonical completion idempotency keys. Every later observation preserves the original Outbox ID, event ID, event type, aggregate ID, idempotency key, request ID and trace ID. Missing, duplicate or substituted rows fail closed.

The local sandbox no longer supports business-key or purchase-order prefix widening. Its default golden-path mode still requires the scenario's exact business key and purchase-order reference. The explicit volume/rehearsal mode uses:

```text
APPROVAL_DEMO_PAYMENT_SANDBOX_EVENT_ALLOWLIST_FILE
```

A private UTF-8 allowlist is published before the existing recovery control. Its versioned header binds the tenant; each of at most 96 records binds the exact event ID, canonical idempotency key, aggregate ID, business key and purchase-order reference as one tuple. The parser rejects wildcards, empty or oversized fields, malformed UUIDs, duplicate identities, wrong tenants, excessive file sizes, directories and symbolic links.

Recovery loads and freezes this allowlist before making the sandbox available. Missing or invalid authorization leaves it unavailable. The existing signature, key ID, tenant, event type, aggregate/payload identity, completion status and supplier checks remain. Before any accepted payment side effect, the callback must match the complete authorized tuple. Replacing the file after activation cannot widen the frozen membership.

The sandbox retains every accepted tuple plus the SHA-256 of the exact authorization bytes. Acceptance compares this ledger, not just its count, to the published list: exactly 96 unique event IDs, idempotency keys and payment results. Changed-payload idempotent replays remain rejected.

## Drain measurement

The monotonic `performance.now()` clock measures from immediately before writing the recovery control to each event's first observed DELIVERED state. Evidence retains the first completion, full observed drain duration, events/second, interpolated P50/P95/P99, individual samples, observation count and maximum observation gap.

The five stability observations do not inflate the completed-drain duration or rewrite first-delivery samples. Partial samples survive failure without bypassing cleanup. The labels remain:

```text
RECOVERY_CONTROL_WRITE_START_TO_FIRST_OBSERVED_DELIVERED
POLL_OBSERVED_COMPLETION_NOT_PROVIDER_LATENCY
LOCAL_SINGLE_NODE_CONFIGURED_VOLUME_NOT_PRODUCTION_RTO
```

These are polling observations including query overhead, not provider service-time percentiles or production recovery throughput.

## Cleanup ownership and evidence

The drain stops its backend but deliberately does not destroy its parent's data. Its cleanup evidence explicitly states `scope: BACKEND_ONLY` and `volumeOwner: PROFILE_MATRIX`. The enclosing matrix's `finally` performs the full disposable-volume deletion and verifies ports 5432, 6379 and 8080. A drain-stage pass alone is insufficient: the matrix must also finish successfully with full cleanup evidence before the upgrade stage starts.

Untracked evidence stays under `.runtime/capacity-recovery/<run-id>/`. The existing `root-install.log` Artifact envelopes retain bounded JSON with file sizes and SHA-256 digests. Additional mandatory drain evidence is:

```text
matrix-backlog-handoff.json
payment-sandbox-allowlist.json
outbox-backlog-observations.json
```

The handoff links the originating matrix run and source identity to its original instances and rows. The allowlist JSON retains all entries, the exact UTF-8 control-file content and its digest; auditors can reproduce the bytes without another artifact system. Existing unavailable/delivered snapshots, command-attempt records, summaries and cleanup files remain mandatory.

## In-flight upgrade and restore

The existing rehearsal starts the exact PR base in a detached worktree, advances a real purchase to finance countersign, captures public instance/task/audit state, quiesces the application and takes a validated PostgreSQL 16 custom-format backup. It rebuilds disposable PostgreSQL/Redis, restores that backup, starts the candidate application, compares business state and continues the same instance through public APIs.

Its completion callback now also uses an exact generated-event allowlist (one event), checks HTTP 503/PENDING followed by DELIVERED and verifies one accepted tuple. Backup, worktree, processes, ports and disposable-volume cleanup remain mandatory. The candidate labels are:

```text
LOCAL_IN_FLIGHT_POSTGRES_UPGRADE_RESTORE_REHEARSAL_PASSED
LOCAL_QUIESCED_POSTGRESQL_16_REHEARSAL_NOT_PRODUCTION_RPO_RTO
```

Quiesced committed-record loss and local application-stop-to-first-business-read time do not establish crash consistency, zero-downtime upgrade, rollback safety or production RPO/RTO.

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

PostgreSQL 16 is the only target of this work. The independent MySQL 8.4 work in PR #92 is neither modified nor treated as accepted.
