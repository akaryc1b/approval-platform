# Aggregate Export Checkpoint and Reconciliation Escalation v1

## Boundary

- `contractVersion` is `1`; unknown versions fail closed.
- The contract consumes only validated `TelemetryAggregationSnapshot` and `AuditHandoffReconciliationProof` values.
- Every age, window and checkpoint decision uses caller-supplied ordinals. No clock, scheduler, logger, network, broker or persistence implementation exists.

## Aggregate export checkpoint

A checkpoint input contains a logical stream reference, checkpoint ordinal, optional previous-checkpoint digest, a bounded snapshot list and the snapshot digests reported as exported.

Before storage the implementation:

1. validates and recomputes every aggregate snapshot digest;
2. sorts snapshots by window start, aggregation identity and snapshot digest;
3. rejects duplicate snapshots;
4. requires the exported digest list to exactly equal the complete sorted snapshot set;
5. requires the checkpoint ordinal to be at least the last included signal ordinal;
6. computes a canonical snapshot-set digest, checkpoint ID and checkpoint digest.

`ScriptedAggregateCheckpointStore` enforces:

- first checkpoint has no previous digest;
- every following checkpoint references the current latest checkpoint;
- checkpoint ordinals strictly increase per logical stream;
- a snapshot digest cannot be checkpointed twice in the same stream;
- duplicate checkpoint digests are suppressed;
- capacity and scripted failure append no partial checkpoint.

A partial or mismatched export never creates a checkpoint.

## Reconciliation escalation

Escalation policy defines monotonic caller-ordinal thresholds for `observe`, `investigate` and `block` plus a bounded proof capacity.

Base classification levels are:

- `pending_confirmed` → `none`;
- `acknowledgement_missing` and `missing_no_evidence` → `observe`;
- `conflicting_evidence` → `investigate`;
- `acknowledged_confirmed` → `resolved`.

Caller-ordinal age may only increase the unresolved level. Stored unresolved proofs cannot regress in evaluation ordinal or escalation level.

Only `acknowledged_confirmed` produces a reference-only `HandoffFinalizationCheckpoint`. The checkpoint contains handoff, envelope, acknowledgement and reconciliation-proof references plus the explicit evaluation ordinal and canonical digest. Original audit records and trusted identity evidence are absent.

`ScriptedReconciliationEscalationStore` atomically stores the escalation proof and optional finalization checkpoint. Duplicate, capacity, ordinal regression, level regression, finalization conflict and scripted failure never partially commit a finalization.

## Fixture

`fixtures/checkpoint-escalation-v1.json` proves Java and TypeScript agreement on:

- snapshot-set digest `17c83a22849600f8291e02524333df39ae929506cbb17b1e223a2d4deca9d99a`;
- aggregate checkpoint digest `5a6b2f04cf14c6845c77be583ca93f5ca57ab9a699650cb8e0ea5f4ea7b5ae09`;
- observe proof digest `f1c1c95c4ab8c9f07bbfebc0866a4594f2e3c0a5dfcc9fd33d9dd455bdf124f5`;
- investigate proof digest `be0a041182579e0a3419afab04e6866f01ff06fab99676eeef4c548a3c0fd268`;
- block proof digest `2addc01c8cb6be64197fb7f69cb0726568db4d2e942be4ae6d273ea4f6903ee5`;
- finalization checkpoint digest `e108d3017d3cf456882ac3518e3cacd5d5805b9d2c8ebd47dd4eb585cb938156`;
- resolved escalation proof digest `3c25b58db4e963abf6d74d57ebaf0d14a537733578594bbb987c0c726aee30ee`.

## Outside this contract

Production aggregation, durable deduplication, telemetry export, OpenTelemetry, message brokers, audit queues, reconciliation persistence, clocks, endpoint resolution, authentication, credentials and network execution require separate accepted gates.
