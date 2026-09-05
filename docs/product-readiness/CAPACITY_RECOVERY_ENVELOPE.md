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

The original-backlog handoff and exact-event authorization passed natural Run [33943489013](https://github.com/akaryc1b/approval-platform/actions/runs/33943489013) for commit `ded87e55a8d8435e588753821abca7bbd598d1bf`, tree `0a15e1f94677d7c60bbbab58f402e6396ed51116`. The retained artifact was independently audited; the measured results below belong only to that exact candidate.

A subsequent pre-merge correction fixes reference selection for post-merge pushes and local/main checkouts. The corrected candidate's full natural validation remains **evidence pending**. PR #142 remains Draft; Issues #140 and #107 remain Open. A successful predecessor run is not acceptance of a later commit.

## Audited configured-point results

Source: [PR artifact audit](https://github.com/akaryc1b/approval-platform/pull/142#issuecomment-5549891077), natural Workflow #1719. Artifact `approval-vben-33943489013`, ID `9962827183`, ZIP SHA-256:

```text
c84ebf27092d02e973ee8d89830568e6b734f1bef79a48f575160a07bbe415ae
```

All eight envelopes (two PC/H5, two purchase-payment, Small Demo, matrix, drain and upgrade/restore) passed byte-length, Base64, file-digest, path and source/run identity checks: 99 file entries and 17,726,846 decoded bytes. The PC/H5 envelopes record a synthetic merge checkout with a tree identical to the candidate, not a direct checkout of its commit SHA.

Host: Linux x64, 4 logical AMD EPYC 7763 CPUs, 16,766,410,752 bytes RAM; Node 22.23.2, Java 21.0.12.1, Maven 3.9.16 and PostgreSQL 16.15. One application instance. The configured workload is defined below.

| Observed metric | Small Demo | Standard Deployment | Large Tenant |
| --- | ---: | ---: | ---: |
| Completed generated instances | 6 | 24 | 72 additional / 96 cumulative |
| Approval tasks completed | 30 | 120 | 360 |
| Configured list/detail reads | 60 | 480 | 1,440 |
| Read P50 / P95 / P99 (ms) | 20.329 / 66.676 / 80.483 | 20.415 / 40.300 / 78.705 | 51.268 / 100.646 / 124.655 |
| Configured read throughput (requests/s) | 239.044 | 527.473 | 434.258 |
| Completed flow throughput (flows/s) | 3.446 | 4.887 | 6.134 |
| Observed HTTP error rate | 0 | 0 | 0 |
| Queue-delay P95 (ms) | 59.550 | 81.000 | 52.000 |
| Database growth (bytes) | 1,196,032 | 3,874,816 | 6,946,816 |
| Observed connections before / after | 11 / 11 | 11 / 11 | 11 / 11 |
| Deadlock delta | 0 | 0 | 0 |

The 30 Small Demo and 480 matrix approval commands each needed one observed transport attempt; no retryable command response or terminal command failure was recorded in this run. PostgreSQL's Large Tenant transaction-rollback counter nevertheless increased by 14; that database observation is retained and is not relabelled as an HTTP failure or hidden by the zero HTTP error rate. Per-operation request samples, higher-concurrency reads and point-in-time process observations remain in the artifact. These configured points do not establish a maximum stable or peak-resource envelope.

### Original 96-event drain

The matrix instance arrays, handoff and drain instance arrays match exactly. All 96 original events were PENDING with zero attempts before dispatcher startup, then retained HTTP 503/PENDING and finally HTTP 200/DELIVERED with unchanged identity mappings. The accepted five-field payment ledger equals the 96-entry authorization list and its SHA-256 matches the exact authorization bytes.

| Recomputed poll-observed recovery metric | Value |
| --- | ---: |
| First observed delivery | 9,284.336 ms |
| Complete observed drain | 9,672.929 ms |
| Throughput | 9.925 events/s |
| P50 / P95 / P99 | 9,672.929 / 9,672.929 / 9,672.929 ms |
| Unique event IDs / idempotency keys / accepted results | 96 / 96 / 96 |
| Additional accepted ledger entries | 0 |

The equal percentiles reflect completion first observed in the same polling interval, not identical provider processing times. The runtime records five stability checks and 32 total observations; the audit recomputed metrics from the retained 96 first-delivery samples. Full cleanup is retained by the enclosing matrix, separate from drain's backend-only cleanup.

### In-flight upgrade/restore

The baseline was `ace5a07b305a0b40777f5b9bcce8e81db4d6beb5`, distinct from candidate `ded87e5`. The retained pre/post instance, task and audit summaries compare equal, and the same in-flight instance continued to COMPLETED with one exactly authorized accepted payment result.

| Local rehearsal metric | Value |
| --- | ---: |
| Validated custom-format backup | 879,818 bytes / 572 ms |
| Database restore | 1,950 ms |
| Stopped application to first restored business read | 16,721 ms |
| Lost committed records within the compared business summary | 0 |
| Accepted payment results after continuation | 1 |

This was a quiesced, single-node local rehearsal. Equality of the retained summary is not an exhaustive comparison of every database table, attachment byte or production recovery scenario. Backup/worktree removal, backend stop, disposable-volume deletion and port cleanup are retained.

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

### Reference selection after merge

PR runs use the immutable PR base and head SHAs; the existing tree-equivalence guard still handles synthetic merge checkouts. Main `push` runs use that event's `before` and `after`, not moving `origin/main` and not the candidate itself. New/deleted/forced branches, wrong refs, invalid or identical SHAs, stale checkouts and missing CI event payloads fail closed.

Local and manually dispatched feature runs retain a distinct main merge base. When the merge base equals the candidate (for example, an up-to-date main checkout), a bounded depth-two fetch of the pinned candidate resolves its first parent. Failure to resolve a distinct parent does not fall back to same-version success. A normal main refresh does not truncate an existing full Git graph to depth one.

The regression suite includes real temporary Git repositories and a depth-one clone of a two-parent merge, plus event, identity and malformed-input tests. The top-level ordering test now requires an actually present matrix invocation before the upgrade invocation; a missing call returning `indexOf() === -1` cannot make it pass.


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
