import { sha256Hex, type JsonValue } from './index.js';
import { telemetryAggregationSnapshotMap } from './telemetry-aggregation.js';
import { CHECKPOINT_ESCALATION_VERSION, type AggregateCheckpointAppendStatus, type AggregateCheckpointStatus, type AggregateExportCheckpoint, type AggregateExportCheckpointInput, type AggregateExportCheckpointPolicy, type AggregateExportCheckpointResult, ScriptedAggregateCheckpointStore, validateAggregateExportCheckpointPolicy, requireNonNegativeInteger, requireString, requireVersion } from './aggregate-checkpoint-model.js';
import { checkpointDigest as digest, checkpointResult, compareSnapshots, sameStrings, validateSnapshot } from './aggregate-checkpoint-support.js';

const encoder = new TextEncoder();
export { CHECKPOINT_ESCALATION_VERSION, ScriptedAggregateCheckpointStore, UnsupportedCheckpointEscalationVersionError, validateAggregateExportCheckpointPolicy, type AggregateCheckpointAppendStatus, type AggregateCheckpointStatus, type AggregateExportCheckpoint, type AggregateExportCheckpointInput, type AggregateExportCheckpointPolicy, type AggregateExportCheckpointResult } from './aggregate-checkpoint-model.js';
export { aggregateExportCheckpointMap, aggregateExportCheckpointResultMap } from './aggregate-checkpoint-support.js';

export async function recordAggregateExportCheckpoint(
  policyInput: AggregateExportCheckpointPolicy,
  input: AggregateExportCheckpointInput,
  store: ScriptedAggregateCheckpointStore,
): Promise<AggregateExportCheckpointResult> {
  const policy = validateAggregateExportCheckpointPolicy(policyInput);
  requireVersion(input.contractVersion);
  requireString(input.streamReference, 'aggregateCheckpoint.streamReference');
  requireNonNegativeInteger(input.checkpointOrdinal, 'aggregateCheckpoint.checkpointOrdinal');
  if (!Array.isArray(input.snapshots)
    || input.snapshots.length === 0
    || input.snapshots.length > policy.maxSnapshotsPerCheckpoint) {
    throw new TypeError('aggregateCheckpoint.snapshots is outside policy bounds');
  }
  if (store.capacity > policy.maxStoredCheckpoints) {
    throw new TypeError('aggregateCheckpoint store capacity exceeds policy maximum');
  }

  const snapshots = [...input.snapshots].sort(compareSnapshots);
  const snapshotDigests = snapshots.map((value) => value.snapshotDigest);
  if (new Set(snapshotDigests).size !== snapshotDigests.length) {
    return checkpointResult('failed_closed', 'aggregate_checkpoint_duplicate_snapshot', null, store.size);
  }
  for (const snapshot of snapshots) await validateSnapshot(snapshot);

  if (!sameStrings(snapshotDigests, input.exportedSnapshotDigests)) {
    return checkpointResult('failed_closed', 'aggregate_checkpoint_partial_export', null, store.size);
  }
  const maximumLastOrdinal = Math.max(...snapshots.map((value) => value.lastOrdinal));
  if (input.checkpointOrdinal < maximumLastOrdinal) {
    return checkpointResult('failed_closed', 'aggregate_checkpoint_before_snapshot', null, store.size);
  }
  if (input.previousCheckpointDigest !== null) {
    requireString(input.previousCheckpointDigest, 'aggregateCheckpoint.previousCheckpointDigest');
  }

  const snapshotSetValue = snapshots.map(telemetryAggregationSnapshotMap) as unknown as JsonValue;
  const snapshotSetDigest = await digest(snapshotSetValue);
  const minimumWindowStartOrdinal = Math.min(...snapshots.map((value) => value.windowStartOrdinal));
  const maximumWindowEndOrdinalExclusive = Math.max(
    ...snapshots.map((value) => value.windowEndOrdinalExclusive),
  );
  const checkpointId = await sha256Hex(encoder.encode(
    `aggregate-export-checkpoint-v1\n${input.streamReference}\n${input.checkpointOrdinal}\n${snapshotSetDigest}`,
  ));
  const checkpointValue: JsonValue = {
    checkpointId,
    checkpointOrdinal: input.checkpointOrdinal,
    contractVersion: CHECKPOINT_ESCALATION_VERSION,
    firstSnapshotDigest: snapshotDigests[0]!,
    lastSnapshotDigest: snapshotDigests[snapshotDigests.length - 1]!,
    maximumWindowEndOrdinalExclusive,
    minimumWindowStartOrdinal,
    previousCheckpointDigest: input.previousCheckpointDigest,
    snapshotCount: snapshots.length,
    snapshotSetDigest,
    streamReference: input.streamReference,
  };
  const checkpointDigest = await digest(checkpointValue);
  const checkpoint: AggregateExportCheckpoint = Object.freeze({
    contractVersion: CHECKPOINT_ESCALATION_VERSION,
    checkpointId,
    streamReference: input.streamReference,
    checkpointOrdinal: input.checkpointOrdinal,
    previousCheckpointDigest: input.previousCheckpointDigest,
    snapshotCount: snapshots.length,
    firstSnapshotDigest: snapshotDigests[0]!,
    lastSnapshotDigest: snapshotDigests[snapshotDigests.length - 1]!,
    minimumWindowStartOrdinal,
    maximumWindowEndOrdinalExclusive,
    snapshotSetDigest,
    checkpointDigest,
  });

  let appendStatus: AggregateCheckpointAppendStatus;
  try {
    appendStatus = store.append(checkpoint, snapshotDigests);
  } catch {
    return checkpointResult('failed_closed', 'aggregate_checkpoint_store_failed', checkpoint, store.size);
  }
  if (appendStatus === 'duplicate_rejected') {
    return checkpointResult('duplicate', 'aggregate_checkpoint_duplicate', checkpoint, store.size);
  }
  if (appendStatus === 'capacity_rejected') {
    return checkpointResult('capacity_rejected', 'aggregate_checkpoint_store_capacity', checkpoint, store.size);
  }
  if (appendStatus === 'continuity_rejected') {
    return checkpointResult('failed_closed', 'aggregate_checkpoint_continuity_mismatch', checkpoint, store.size);
  }
  if (appendStatus === 'ordinal_rejected') {
    return checkpointResult('failed_closed', 'aggregate_checkpoint_ordinal_regression', checkpoint, store.size);
  }
  if (appendStatus === 'snapshot_reuse_rejected') {
    return checkpointResult('failed_closed', 'aggregate_checkpoint_snapshot_reuse', checkpoint, store.size);
  }
  return checkpointResult('recorded', 'aggregate_checkpoint_recorded', checkpoint, store.size);
}

