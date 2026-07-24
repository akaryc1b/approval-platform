import { canonicalJson, sha256Hex, type JsonValue } from './index.js';
import { telemetryAggregationSnapshotMap, type TelemetryAggregationSnapshot } from './telemetry-aggregation.js';
import { CHECKPOINT_ESCALATION_VERSION, UnsupportedCheckpointEscalationVersionError, type AggregateCheckpointStatus, type AggregateExportCheckpoint, type AggregateExportCheckpointResult } from './aggregate-checkpoint-model.js';

const encoder = new TextEncoder();
export function aggregateExportCheckpointMap(
  value: AggregateExportCheckpoint,
): Record<string, JsonValue> {
  return {
    checkpointDigest: value.checkpointDigest,
    checkpointId: value.checkpointId,
    checkpointOrdinal: value.checkpointOrdinal,
    contractVersion: value.contractVersion,
    firstSnapshotDigest: value.firstSnapshotDigest,
    lastSnapshotDigest: value.lastSnapshotDigest,
    maximumWindowEndOrdinalExclusive: value.maximumWindowEndOrdinalExclusive,
    minimumWindowStartOrdinal: value.minimumWindowStartOrdinal,
    previousCheckpointDigest: value.previousCheckpointDigest,
    snapshotCount: value.snapshotCount,
    snapshotSetDigest: value.snapshotSetDigest,
    streamReference: value.streamReference,
  };
}

export function aggregateExportCheckpointResultMap(
  value: AggregateExportCheckpointResult,
): Record<string, JsonValue> {
  return {
    checkpoint: value.checkpoint === null ? null : aggregateExportCheckpointMap(value.checkpoint),
    contractVersion: value.contractVersion,
    reasonCode: value.reasonCode,
    status: value.status,
    storedCheckpointCount: value.storedCheckpointCount,
  };
}

export function checkpointResult(
  status: AggregateCheckpointStatus,
  reasonCode: string,
  checkpoint: AggregateExportCheckpoint | null,
  storedCheckpointCount: number,
): AggregateExportCheckpointResult {
  return Object.freeze({
    contractVersion: CHECKPOINT_ESCALATION_VERSION,
    status,
    reasonCode,
    checkpoint,
    storedCheckpointCount,
  });
}

export async function validateSnapshot(snapshot: TelemetryAggregationSnapshot): Promise<void> {
  requireVersion(snapshot.contractVersion);
  for (const [field, value] of Object.entries({
    aggregationIdentityDigest: snapshot.aggregationIdentityDigest,
    signalName: snapshot.signalName,
    endpointId: snapshot.endpointId,
    operation: snapshot.operation,
    outcome: snapshot.outcome,
    provenanceDigest: snapshot.provenanceDigest,
    firstSignalId: snapshot.firstSignalId,
    lastSignalId: snapshot.lastSignalId,
    snapshotDigest: snapshot.snapshotDigest,
  })) requireString(value, `aggregateSnapshot.${field}`);
  requireNonNegativeInteger(snapshot.windowStartOrdinal, 'aggregateSnapshot.windowStartOrdinal');
  requireIntegerRange(
    snapshot.windowEndOrdinalExclusive,
    snapshot.windowStartOrdinal + 1,
    Number.MAX_SAFE_INTEGER,
    'aggregateSnapshot.windowEndOrdinalExclusive',
  );
  requireIntegerRange(snapshot.totalQuantity, 1, Number.MAX_SAFE_INTEGER, 'aggregateSnapshot.totalQuantity');
  requireIntegerRange(snapshot.signalCount, 1, Number.MAX_SAFE_INTEGER, 'aggregateSnapshot.signalCount');
  requireNonNegativeInteger(snapshot.firstOrdinal, 'aggregateSnapshot.firstOrdinal');
  requireNonNegativeInteger(snapshot.lastOrdinal, 'aggregateSnapshot.lastOrdinal');
  if (snapshot.firstOrdinal > snapshot.lastOrdinal
    || snapshot.firstOrdinal < snapshot.windowStartOrdinal
    || snapshot.lastOrdinal >= snapshot.windowEndOrdinalExclusive) {
    throw new TypeError('aggregateSnapshot ordinal range is invalid');
  }
  if (snapshot.signalKind !== 'counter' && snapshot.signalKind !== 'event') {
    throw new TypeError('aggregateSnapshot.signalKind is unsupported');
  }
  const value = telemetryAggregationSnapshotMap(snapshot);
  const withoutDigest = { ...value } as Record<string, JsonValue>;
  delete withoutDigest.snapshotDigest;
  const expected = await checkpointDigest(withoutDigest);
  if (expected !== snapshot.snapshotDigest) throw new TypeError('aggregateSnapshot digest mismatch');
}

export function compareSnapshots(left: TelemetryAggregationSnapshot, right: TelemetryAggregationSnapshot): number {
  return left.windowStartOrdinal - right.windowStartOrdinal
    || left.aggregationIdentityDigest.localeCompare(right.aggregationIdentityDigest)
    || left.snapshotDigest.localeCompare(right.snapshotDigest);
}

export function sameStrings(expected: readonly string[], actual: readonly string[]): boolean {
  if (!Array.isArray(actual) || expected.length !== actual.length) return false;
  return expected.every((value, index) => value === actual[index]);
}

export async function checkpointDigest(value: JsonValue): Promise<string> {
  return sha256Hex(encoder.encode(canonicalJson(value)));
}

function requireVersion(value: unknown): asserts value is '1' {
  if (value !== CHECKPOINT_ESCALATION_VERSION) {
    throw new UnsupportedCheckpointEscalationVersionError(value);
  }
}
function requireString(value: unknown, field: string): asserts value is string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new TypeError(`${field} must be a non-empty string`);
  }
}
function requireNonNegativeInteger(value: unknown, field: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new TypeError(`${field} must be a non-negative safe integer`);
  }
}
function requireIntegerRange(value: unknown, min: number, max: number, field: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min || value > max) {
    throw new TypeError(`${field} must be an integer in [${min}, ${max}]`);
  }
}
