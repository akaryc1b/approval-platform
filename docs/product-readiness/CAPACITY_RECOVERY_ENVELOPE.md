# Capacity and Recovery Operating Envelope

Tracking: [#140 — Publish capacity and recovery operating envelope](https://github.com/akaryc1b/approval-platform/issues/140), supporting [#107](https://github.com/akaryc1b/approval-platform/issues/107).

This page defines the executable local-reference capacity and recovery path for the existing purchase-payment Product Alpha scenario. It publishes results only after an exact-Head runtime, threshold checks, retained Artifact audit and fail-closed cleanup succeed.

It does **not** publish a production sizing promise, marketing TPS figure, maximum stable envelope, peak-resource envelope, production RPO or production RTO.

## Entrypoints

```bash
pnpm demo:runtime:capacity-recovery:plan
pnpm demo:runtime:capacity-recovery:check
pnpm demo:runtime:capacity-recovery
pnpm demo:runtime:capacity-recovery:ci
```

Every runtime writes untracked evidence under:

```text
.runtime/capacity-recovery/<run-id>/
```

The command now executes four serial stages:

```text
Small Demo configured point and accepted single-event recovery
→ Standard Deployment local-reference profile
→ Large Tenant local-reference profile
→ configured-volume Outbox / Generic REST Connector backlog drain
```

The final backup/restore and local RPO/RTO rehearsal remains separate unfinished work in the same Draft PR.

## Governed profiles

| Profile | Implementation status | Local-reference dataset |
| --- | --- | --- |
| Small Demo | `EXECUTABLE_INITIAL` | One tenant, six governed identities, one eight-node purchase-payment definition, one seeded instance and six generated instances |
| Standard Deployment — Local Reference | `EXECUTABLE_EXTENDED` | 24 generated instances, 480 configured reads, 240 higher-concurrency reads and complete five-task approval flows |
| Large Tenant — Local Reference | `EXECUTABLE_EXTENDED` | 72 additional generated instances, 1,440 configured reads, 480 higher-concurrency reads and 96 cumulative instances |
| Outbox / Connector backlog drain | `IMPLEMENTED_EXACT_HEAD_EVIDENCE_PENDING` | A fresh bounded set of 96 completed instances delivered through the existing Generic REST Connector and signed local payment sandbox |

The profile names describe workload classes. They are not production deployment sizes.

## Capacity profile path

The Small, Standard and Large profiles reuse:

- PostgreSQL 16;
- Redis;
- Spring Boot and Flowable;
- the governed purchase-payment process and six-identity directory;
- real attachment, start, pending-task, task-action, instance and timeline APIs;
- the transactional completion Outbox.

The configured profile matrix performs:

```text
bounded attachment uploads
→ concurrent purchase-payment starts
→ configured list/detail read pressure
→ higher-concurrency read observation
→ manager approval
→ finance review
→ two-person finance countersign
→ payment confirmation
→ completed-instance and sampled-timeline checks
→ read-only completion-Outbox backlog count
```

Standard Deployment uses:

```text
generated instances: 24
start concurrency: 6
approval concurrency: 8
configured reads: 480 at concurrency 12
higher-point reads: 240 at concurrency 24
```

Large Tenant adds:

```text
generated instances: 72
cumulative generated instances: 96
start concurrency: 12
approval concurrency: 16
configured reads: 1,440 at concurrency 24
higher-point reads: 480 at concurrency 48
```

Every successful profile remains labelled:

```text
PASSED_AT_CONFIGURED_POINT_ONLY
```

The higher read point remains:

```text
HIGHER_THAN_CONFIGURED_READ_POINT_OBSERVED_NOT_MAXIMUM_ENVELOPE
```

Neither label identifies the maximum stable envelope.

## Bounded approval-command retry

Only this exact local command boundary may retry a server-declared optimistic-locking response:

```text
POST http://127.0.0.1:8080/api/approval/tasks/<uuid>/approve
HTTP 500
code = APPROVAL_COMMAND_FAILED
retryable = true
```

The policy permits at most four total attempts with deterministic delays of 50, 100 and 200 milliseconds. It preserves the same idempotency key, uses fresh request and trace IDs, and retains each transport attempt.

Network failures, reads, attachments, process starts and unrelated writes are not retried.

## Configured-volume Outbox / Connector drain

The backlog-drain stage does not create a second Outbox, Connector, workflow or payment provider. It starts the existing local backend with:

- the existing Generic REST Connector;
- the existing Outbox dispatcher;
- the existing loopback-only signed payment sandbox;
- an explicit local-only business-key and purchase-order prefix allowlist;
- a bounded batch size of 96;
- bounded retry delay and maximum attempts.

It then executes:

```text
create 96 unique real purchase-payment instances
→ complete all five approval tasks for each instance
→ signed payment sandbox remains unavailable
→ every target completion event receives HTTP 503
→ every target event remains PENDING and recoverable
→ verify 96 unique Outbox IDs, event IDs, aggregate IDs and idempotency keys
→ enable the existing sandbox recovery control
→ wait until every target event becomes DELIVERED
→ verify HTTP 200 and unique provider request IDs
→ verify the sandbox accepts exactly 96 unique payment results
→ hold a five-observation exactly-once stability window
→ clean backend, ports, containers and disposable data
```

The sandbox volume mode remains restricted to the local profile and to prefixes extending the governed demo identity. Exact-value validation remains the default for the accepted single-event golden path.

The implementation is complete on the candidate branch, but acceptance remains **evidence pending** until the new exact Head succeeds.

The candidate claim is:

```text
OUTBOX_CONNECTOR_BACKLOG_DRAIN_LOCAL_CONFIGURED_VOLUME_PASSED
```

It may be accepted only after the new exact Head completes naturally and its retained Artifact is audited.

The measured recovery interval is labelled:

```text
LOCAL_SINGLE_NODE_CONFIGURED_VOLUME_NOT_PRODUCTION_RTO
```

It is not production recovery throughput and not production RTO.

## Retained machine evidence

The profile matrix retains:

```text
source-identity.json
profile-matrix-contract.json
profile-matrix-host.json
profile-matrix-command-retry-evidence.json
standard-deployment-profile.json
standard-deployment-request-samples.json
large-tenant-profile.json
large-tenant-request-samples.json
profile-matrix-cleanup.json
profile-matrix-summary.json
```

The configured-volume drain retains:

```text
source-identity.json
backlog-drain-contract.json
backlog-drain-instances.json
backlog-drain-command-attempts.json
outbox-backlog-unavailable.json
outbox-backlog-delivered.json
backlog-drain-cleanup.json
outbox-backlog-drain-summary.json
```

In GitHub Actions, bounded JSON evidence is embedded in the existing `root-install.log` Artifact envelope with per-file size and SHA-256 digests. Evidence remains untracked and must never be committed.

## Claims gated on exact-Head evidence

```text
STANDARD_DEPLOYMENT_LOCAL_REFERENCE_PASSED
LARGE_TENANT_LOCAL_REFERENCE_PASSED
MULTI_INSTANCE_APPROVAL_THROUGHPUT_MEASURED
OUTBOX_BACKLOG_CREATION_VOLUME_MEASURED
BEYOND_CONFIGURED_READ_POINT_OBSERVED
CAPACITY_PROFILE_MATRIX_PUBLISHED
OUTBOX_CONNECTOR_BACKLOG_DRAIN_LOCAL_CONFIGURED_VOLUME_PASSED
```

Implementation or marker presence alone releases no claim.

## Remaining recovery rehearsal

Issue #140 remains open. Before PR #142 can become Ready, the same branch must still execute:

```text
create a real in-flight purchase-payment instance
→ capture deterministic pre-backup business summaries
→ stop the backend
→ take a PostgreSQL backup
→ rebuild a disposable PostgreSQL / Redis lifecycle
→ restore the backup
→ start the same candidate application
→ compare instance, task, audit, attachment and Outbox summaries
→ continue the in-flight workflow through public APIs
→ complete payment and Outbox delivery
→ measure local backup, restore, health and first-business-read intervals
```

Required candidate outcomes are:

```text
LOCAL_BACKUP_RESTORE_REHEARSAL_PASSED
PRE_POST_BUSINESS_CONSISTENCY_PASSED
LOCAL_RPO_MEASURED
LOCAL_RTO_MEASURED
```

They must remain qualified as local, quiesced, single-node evidence.

## Explicit limitations

```text
PRODUCTION_CAPACITY_NOT_VERIFIED
MAXIMUM_STABLE_ENVELOPE_NOT_VERIFIED
PEAK_RESOURCE_ENVELOPE_NOT_VERIFIED
MULTI_NODE_CAPACITY_NOT_VERIFIED
PRODUCTION_OUTBOX_DRAIN_RATE_NOT_VERIFIED
MULTI_NODE_OUTBOX_DRAIN_NOT_VERIFIED
UPGRADE_REHEARSAL_NOT_VERIFIED
BACKUP_RESTORE_NOT_VERIFIED
RPO_RTO_NOT_VERIFIED
PRODUCTION_RPO_NOT_VERIFIED
PRODUCTION_RTO_NOT_VERIFIED
MYSQL_8_4_NOT_VERIFIED
PRODUCTION_DEPLOYMENT_NOT_VERIFIED
RELEASE_NOT_CREATED
```

PostgreSQL 16 is the only database target for this work. The independent MySQL 8.4 work in PR #92 is not modified or treated as accepted.
