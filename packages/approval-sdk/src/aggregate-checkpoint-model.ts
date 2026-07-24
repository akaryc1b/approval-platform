import type { TelemetryAggregationSnapshot } from './telemetry-aggregation.js';

export const CHECKPOINT_ESCALATION_VERSION = '1' as const;
const MAX_BOUND = 10_000;

export type AggregateCheckpointStatus =
  | 'recorded'
  | 'duplicate'
  | 'capacity_rejected'
  | 'failed_closed';
export type AggregateCheckpointAppendStatus =
  | 'accepted'
  | 'duplicate_rejected'
  | 'capacity_rejected'
  | 'continuity_rejected'
  | 'ordinal_rejected'
  | 'snapshot_reuse_rejected';

export interface AggregateExportCheckpointPolicy {
  readonly contractVersion: '1';
  readonly maxSnapshotsPerCheckpoint: number;
  readonly maxStoredCheckpoints: number;
}

export interface AggregateExportCheckpointInput {
  readonly contractVersion: '1';
  readonly streamReference: string;
  readonly checkpointOrdinal: number;
  readonly previousCheckpointDigest: string | null;
  readonly snapshots: readonly TelemetryAggregationSnapshot[];
  readonly exportedSnapshotDigests: readonly string[];
}

export interface AggregateExportCheckpoint {
  readonly contractVersion: '1';
  readonly checkpointId: string;
  readonly streamReference: string;
  readonly checkpointOrdinal: number;
  readonly previousCheckpointDigest: string | null;
  readonly snapshotCount: number;
  readonly firstSnapshotDigest: string;
  readonly lastSnapshotDigest: string;
  readonly minimumWindowStartOrdinal: number;
  readonly maximumWindowEndOrdinalExclusive: number;
  readonly snapshotSetDigest: string;
  readonly checkpointDigest: string;
}

export interface AggregateExportCheckpointResult {
  readonly contractVersion: '1';
  readonly status: AggregateCheckpointStatus;
  readonly reasonCode: string;
  readonly checkpoint: AggregateExportCheckpoint | null;
  readonly storedCheckpointCount: number;
}

export class UnsupportedCheckpointEscalationVersionError extends Error {
  constructor(readonly contractVersion: unknown) {
    super(`Unsupported checkpoint/escalation contract version: ${String(contractVersion)}`);
    this.name = 'UnsupportedCheckpointEscalationVersionError';
  }
}

export class ScriptedAggregateCheckpointStore {
  private readonly checkpointsInternal: AggregateExportCheckpoint[] = [];
  private readonly checkpointDigests = new Set<string>();
  private readonly latestByStream = new Map<string, AggregateExportCheckpoint>();
  private readonly checkpointedSnapshotsByStream = new Map<string, Set<string>>();
  private readonly failureOperationNumbers: ReadonlySet<number>;
  private appendAttempts = 0;

  constructor(
    readonly capacity: number,
    failureOperationNumbers: readonly number[] = [],
  ) {
    requireIntegerRange(capacity, 1, MAX_BOUND, 'aggregateCheckpointStore.capacity');
    this.failureOperationNumbers = positiveUniqueIntegers(
      failureOperationNumbers,
      'aggregateCheckpointStore.failureOperationNumbers',
    );
  }

  get checkpoints(): readonly AggregateExportCheckpoint[] {
    return [...this.checkpointsInternal];
  }

  get size(): number {
    return this.checkpointsInternal.length;
  }

  latest(streamReference: string): AggregateExportCheckpoint | null {
    return this.latestByStream.get(streamReference) ?? null;
  }

  append(
    checkpoint: AggregateExportCheckpoint,
    snapshotDigests: readonly string[],
  ): AggregateCheckpointAppendStatus {
    this.appendAttempts += 1;
    if (this.failureOperationNumbers.has(this.appendAttempts)) {
      throw new Error('scripted aggregate checkpoint store failure');
    }
    if (this.checkpointDigests.has(checkpoint.checkpointDigest)) return 'duplicate_rejected';

    const latest = this.latestByStream.get(checkpoint.streamReference);
    if (latest === undefined) {
      if (checkpoint.previousCheckpointDigest !== null) return 'continuity_rejected';
    } else {
      if (checkpoint.previousCheckpointDigest !== latest.checkpointDigest) return 'continuity_rejected';
      if (checkpoint.checkpointOrdinal <= latest.checkpointOrdinal) return 'ordinal_rejected';
    }

    const checkpointed = this.checkpointedSnapshotsByStream.get(checkpoint.streamReference) ?? new Set<string>();
    if (snapshotDigests.some((digest) => checkpointed.has(digest))) return 'snapshot_reuse_rejected';
    if (this.checkpointsInternal.length >= this.capacity) return 'capacity_rejected';

    const nextCheckpointed = new Set(checkpointed);
    for (const digest of snapshotDigests) nextCheckpointed.add(digest);
    this.checkpointsInternal.push(checkpoint);
    this.checkpointDigests.add(checkpoint.checkpointDigest);
    this.latestByStream.set(checkpoint.streamReference, checkpoint);
    this.checkpointedSnapshotsByStream.set(checkpoint.streamReference, nextCheckpointed);
    return 'accepted';
  }
}

export function validateAggregateExportCheckpointPolicy(
  policy: AggregateExportCheckpointPolicy,
): AggregateExportCheckpointPolicy {
  requireVersion(policy.contractVersion);
  requireIntegerRange(
    policy.maxSnapshotsPerCheckpoint,
    1,
    MAX_BOUND,
    'aggregateCheckpointPolicy.maxSnapshotsPerCheckpoint',
  );
  requireIntegerRange(
    policy.maxStoredCheckpoints,
    1,
    MAX_BOUND,
    'aggregateCheckpointPolicy.maxStoredCheckpoints',
  );
  return Object.freeze({ ...policy, contractVersion: CHECKPOINT_ESCALATION_VERSION });
}

function positiveUniqueIntegers(values: readonly number[], field: string): ReadonlySet<number> {
  if (!Array.isArray(values)) throw new TypeError(`${field} must be an array`);
  const output = new Set<number>();
  for (const value of values) {
    requireIntegerRange(value, 1, Number.MAX_SAFE_INTEGER, field);
    if (output.has(value)) throw new TypeError(`${field} contains duplicates`);
    output.add(value);
  }
  return output;
}

export function requireVersion(value: unknown): asserts value is '1' {
  if (value !== CHECKPOINT_ESCALATION_VERSION) {
    throw new UnsupportedCheckpointEscalationVersionError(value);
  }
}
export function requireString(value: unknown, field: string): asserts value is string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new TypeError(`${field} must be a non-empty string`);
  }
}
export function requireNonNegativeInteger(value: unknown, field: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new TypeError(`${field} must be a non-negative safe integer`);
  }
}
export function requireIntegerRange(value: unknown, min: number, max: number, field: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min || value > max) {
    throw new TypeError(`${field} must be an integer in [${min}, ${max}]`);
  }
}
