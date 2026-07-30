import { canonicalJson, sha256Hex, type JsonValue } from './index.js';
import type { ReferenceTelemetrySignal, TelemetrySignalKind } from './telemetry-signal.js';
import { TELEMETRY_HANDOFF_VERSION, UnsupportedTelemetryHandoffVersionError } from './telemetry-signal.js';

const encoder = new TextEncoder();
const MAX_BOUND = 10_000;

export type TelemetryAggregationStatus = 'aggregated' | 'duplicate' | 'capacity_rejected' | 'failed_closed';

export interface TelemetryAggregationPolicy {
  readonly contractVersion: '1';
  readonly windowSizeOrdinals: number;
  readonly deduplicationWindowOrdinals: number;
  readonly maxActiveWindows: number;
  readonly maxAggregationIdentities: number;
  readonly maxTrackedSignalDigests: number;
}

export interface TelemetryAggregationSnapshot {
  readonly contractVersion: '1';
  readonly windowStartOrdinal: number;
  readonly windowEndOrdinalExclusive: number;
  readonly aggregationIdentityDigest: string;
  readonly signalName: string;
  readonly signalKind: TelemetrySignalKind;
  readonly endpointId: string;
  readonly operation: string;
  readonly outcome: string;
  readonly provenanceDigest: string;
  readonly attributes: Readonly<Record<string, string>>;
  readonly totalQuantity: number;
  readonly signalCount: number;
  readonly firstSignalId: string;
  readonly lastSignalId: string;
  readonly firstOrdinal: number;
  readonly lastOrdinal: number;
  readonly snapshotDigest: string;
}

export interface TelemetryAggregationResult {
  readonly contractVersion: '1';
  readonly status: TelemetryAggregationStatus;
  readonly reasonCode: string;
  readonly acceptedSignalCount: number;
  readonly duplicateSignalCount: number;
  readonly activeWindowCount: number;
  readonly activeIdentityCount: number;
  readonly trackedSignalCount: number;
  readonly snapshot: TelemetryAggregationSnapshot | null;
  readonly rolledOverSnapshots: readonly TelemetryAggregationSnapshot[];
}

interface MutableAggregate {
  readonly windowStartOrdinal: number;
  readonly windowEndOrdinalExclusive: number;
  readonly aggregationIdentityDigest: string;
  readonly signalName: string;
  readonly signalKind: TelemetrySignalKind;
  readonly endpointId: string;
  readonly operation: string;
  readonly outcome: string;
  readonly provenanceDigest: string;
  readonly attributes: Readonly<Record<string, string>>;
  totalQuantity: number;
  signalCount: number;
  readonly firstSignalId: string;
  lastSignalId: string;
  readonly firstOrdinal: number;
  lastOrdinal: number;
}

export class ScriptedTelemetryAggregationStore {
  private windows = new Map<number, Map<string, MutableAggregate>>();
  private trackedSignalDigests = new Map<string, number>();
  private lastOrdinal = -1;
  private operationCount = 0;
  private acceptedSignalCountInternal = 0;
  private duplicateSignalCountInternal = 0;
  private readonly failureOperationNumbers: ReadonlySet<number>;
  readonly policy: TelemetryAggregationPolicy;

  constructor(policy: TelemetryAggregationPolicy, failureOperationNumbers: readonly number[] = []) {
    this.policy = validateTelemetryAggregationPolicy(policy);
    this.failureOperationNumbers = positiveUniqueIntegers(
      failureOperationNumbers,
      'telemetryAggregationStore.failureOperationNumbers',
    );
  }

  get activeWindowCount(): number { return this.windows.size; }
  get activeIdentityCount(): number {
    let count = 0;
    for (const values of this.windows.values()) count += values.size;
    return count;
  }
  get trackedSignalCount(): number { return this.trackedSignalDigests.size; }
  get acceptedSignalCount(): number { return this.acceptedSignalCountInternal; }
  get duplicateSignalCount(): number { return this.duplicateSignalCountInternal; }

  async aggregate(signal: ReferenceTelemetrySignal): Promise<TelemetryAggregationResult> {
    this.operationCount += 1;
    if (this.failureOperationNumbers.has(this.operationCount)) {
      return this.result('failed_closed', 'telemetry_aggregation_store_failed', null, []);
    }
    try {
      await validateReferenceTelemetrySignal(signal);
    } catch {
      return this.result('failed_closed', 'telemetry_signal_invalid', null, []);
    }
    if (signal.ordinal < this.lastOrdinal) {
      return this.result('failed_closed', 'telemetry_aggregation_ordinal_regression', null, []);
    }

    const nextTracked = new Map(this.trackedSignalDigests);
    const minimumTrackedOrdinal = Math.max(0, signal.ordinal - this.policy.deduplicationWindowOrdinals);
    for (const [digest, ordinal] of nextTracked) {
      if (ordinal < minimumTrackedOrdinal) nextTracked.delete(digest);
    }
    if (nextTracked.has(signal.signalDigest)) {
      this.trackedSignalDigests = nextTracked;
      this.lastOrdinal = signal.ordinal;
      this.duplicateSignalCountInternal += 1;
      return this.result('duplicate', 'telemetry_signal_duplicate', null, []);
    }

    const nextWindows = cloneWindows(this.windows);
    const windowStart = Math.floor(signal.ordinal / this.policy.windowSizeOrdinals) * this.policy.windowSizeOrdinals;
    const rolledOverSnapshots: TelemetryAggregationSnapshot[] = [];
    const windowStarts = [...nextWindows.keys()].sort((left, right) => left - right);
    if (!nextWindows.has(windowStart)) {
      while (nextWindows.size >= this.policy.maxActiveWindows) {
        const oldest = windowStarts.shift();
        if (oldest === undefined) break;
        const removed = nextWindows.get(oldest);
        if (removed !== undefined) {
          for (const aggregate of [...removed.values()].sort((left, right) =>
            left.aggregationIdentityDigest.localeCompare(right.aggregationIdentityDigest))) {
            rolledOverSnapshots.push(await snapshot(aggregate));
          }
        }
        nextWindows.delete(oldest);
      }
    }

    let prospectiveIdentityCount = 0;
    for (const values of nextWindows.values()) prospectiveIdentityCount += values.size;
    const window = nextWindows.get(windowStart) ?? new Map<string, MutableAggregate>();
    const existing = window.get(signal.aggregationIdentityDigest);
    if (existing === undefined && prospectiveIdentityCount >= this.policy.maxAggregationIdentities) {
      return this.result('capacity_rejected', 'telemetry_aggregation_identity_capacity', null, []);
    }

    let nextAggregate: MutableAggregate;
    try {
      nextAggregate = existing === undefined
        ? newAggregate(signal, windowStart, this.policy.windowSizeOrdinals)
        : updateAggregate(existing, signal);
    } catch {
      return this.result('failed_closed', 'telemetry_aggregation_quantity_overflow', null, []);
    }
    window.set(signal.aggregationIdentityDigest, nextAggregate);
    nextWindows.set(windowStart, window);

    while (nextTracked.size >= this.policy.maxTrackedSignalDigests) {
      const candidate = [...nextTracked.entries()].sort((left, right) =>
        left[1] - right[1] || left[0].localeCompare(right[0]))[0];
      if (candidate === undefined) break;
      nextTracked.delete(candidate[0]);
    }
    nextTracked.set(signal.signalDigest, signal.ordinal);

    this.windows = nextWindows;
    this.trackedSignalDigests = nextTracked;
    this.lastOrdinal = signal.ordinal;
    this.acceptedSignalCountInternal += 1;
    const aggregateSnapshot = await snapshot(nextAggregate);
    return this.result(
      'aggregated',
      rolledOverSnapshots.length === 0 ? 'telemetry_signal_aggregated' : 'telemetry_signal_aggregated_with_rollover',
      aggregateSnapshot,
      rolledOverSnapshots,
    );
  }

  async snapshots(): Promise<readonly TelemetryAggregationSnapshot[]> {
    const output: TelemetryAggregationSnapshot[] = [];
    for (const start of [...this.windows.keys()].sort((left, right) => left - right)) {
      const values = this.windows.get(start)!;
      for (const aggregate of [...values.values()].sort((left, right) =>
        left.aggregationIdentityDigest.localeCompare(right.aggregationIdentityDigest))) {
        output.push(await snapshot(aggregate));
      }
    }
    return output;
  }

  private result(
    status: TelemetryAggregationStatus,
    reasonCode: string,
    value: TelemetryAggregationSnapshot | null,
    rolledOverSnapshots: readonly TelemetryAggregationSnapshot[],
  ): TelemetryAggregationResult {
    return Object.freeze({
      contractVersion: TELEMETRY_HANDOFF_VERSION,
      status,
      reasonCode,
      acceptedSignalCount: this.acceptedSignalCountInternal,
      duplicateSignalCount: this.duplicateSignalCountInternal,
      activeWindowCount: this.activeWindowCount,
      activeIdentityCount: this.activeIdentityCount,
      trackedSignalCount: this.trackedSignalCount,
      snapshot: value,
      rolledOverSnapshots: Object.freeze([...rolledOverSnapshots]),
    });
  }
}

export function validateTelemetryAggregationPolicy(policy: TelemetryAggregationPolicy): TelemetryAggregationPolicy {
  requireVersion(policy.contractVersion);
  requireIntegerRange(policy.windowSizeOrdinals, 1, 1_000_000, 'telemetryAggregationPolicy.windowSizeOrdinals');
  requireIntegerRange(policy.deduplicationWindowOrdinals, 0, 1_000_000, 'telemetryAggregationPolicy.deduplicationWindowOrdinals');
  requireIntegerRange(policy.maxActiveWindows, 1, MAX_BOUND, 'telemetryAggregationPolicy.maxActiveWindows');
  requireIntegerRange(policy.maxAggregationIdentities, 1, MAX_BOUND, 'telemetryAggregationPolicy.maxAggregationIdentities');
  requireIntegerRange(policy.maxTrackedSignalDigests, 1, MAX_BOUND, 'telemetryAggregationPolicy.maxTrackedSignalDigests');
  return Object.freeze({ ...policy, contractVersion: TELEMETRY_HANDOFF_VERSION });
}

export function telemetryAggregationSnapshotMap(value: TelemetryAggregationSnapshot): Record<string, JsonValue> {
  return {
    aggregationIdentityDigest: value.aggregationIdentityDigest,
    attributes: value.attributes,
    contractVersion: value.contractVersion,
    endpointId: value.endpointId,
    firstOrdinal: value.firstOrdinal,
    firstSignalId: value.firstSignalId,
    lastOrdinal: value.lastOrdinal,
    lastSignalId: value.lastSignalId,
    operation: value.operation,
    outcome: value.outcome,
    provenanceDigest: value.provenanceDigest,
    signalCount: value.signalCount,
    signalKind: value.signalKind,
    signalName: value.signalName,
    snapshotDigest: value.snapshotDigest,
    totalQuantity: value.totalQuantity,
    windowEndOrdinalExclusive: value.windowEndOrdinalExclusive,
    windowStartOrdinal: value.windowStartOrdinal,
  };
}

export function telemetryAggregationResultMap(value: TelemetryAggregationResult): Record<string, JsonValue> {
  return {
    acceptedSignalCount: value.acceptedSignalCount,
    activeIdentityCount: value.activeIdentityCount,
    activeWindowCount: value.activeWindowCount,
    contractVersion: value.contractVersion,
    duplicateSignalCount: value.duplicateSignalCount,
    reasonCode: value.reasonCode,
    rolledOverSnapshots: value.rolledOverSnapshots.map(telemetryAggregationSnapshotMap),
    snapshot: value.snapshot === null ? null : telemetryAggregationSnapshotMap(value.snapshot),
    status: value.status,
    trackedSignalCount: value.trackedSignalCount,
  };
}

function cloneWindows(
  source: ReadonlyMap<number, ReadonlyMap<string, MutableAggregate>>,
): Map<number, Map<string, MutableAggregate>> {
  const output = new Map<number, Map<string, MutableAggregate>>();
  for (const [start, values] of source) {
    const copied = new Map<string, MutableAggregate>();
    for (const [digest, value] of values) copied.set(digest, { ...value, attributes: value.attributes });
    output.set(start, copied);
  }
  return output;
}

function newAggregate(
  signal: ReferenceTelemetrySignal,
  windowStartOrdinal: number,
  windowSizeOrdinals: number,
): MutableAggregate {
  return {
    windowStartOrdinal,
    windowEndOrdinalExclusive: windowStartOrdinal + windowSizeOrdinals,
    aggregationIdentityDigest: signal.aggregationIdentityDigest,
    signalName: signal.signalName,
    signalKind: signal.signalKind,
    endpointId: signal.endpointId,
    operation: signal.operation,
    outcome: signal.outcome,
    provenanceDigest: signal.provenanceDigest,
    attributes: Object.freeze({ ...signal.attributes }),
    totalQuantity: signal.quantity,
    signalCount: 1,
    firstSignalId: signal.signalId,
    lastSignalId: signal.signalId,
    firstOrdinal: signal.ordinal,
    lastOrdinal: signal.ordinal,
  };
}

function updateAggregate(existing: MutableAggregate, signal: ReferenceTelemetrySignal): MutableAggregate {
  const totalQuantity = existing.totalQuantity + signal.quantity;
  if (!Number.isSafeInteger(totalQuantity)) throw new TypeError('Telemetry aggregate quantity exceeds safe integer range');
  return {
    ...existing,
    totalQuantity,
    signalCount: existing.signalCount + 1,
    lastSignalId: signal.signalId,
    lastOrdinal: signal.ordinal,
  };
}

async function snapshot(value: MutableAggregate): Promise<TelemetryAggregationSnapshot> {
  const input: JsonValue = {
    aggregationIdentityDigest: value.aggregationIdentityDigest,
    attributes: value.attributes,
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    endpointId: value.endpointId,
    firstOrdinal: value.firstOrdinal,
    firstSignalId: value.firstSignalId,
    lastOrdinal: value.lastOrdinal,
    lastSignalId: value.lastSignalId,
    operation: value.operation,
    outcome: value.outcome,
    provenanceDigest: value.provenanceDigest,
    signalCount: value.signalCount,
    signalKind: value.signalKind,
    signalName: value.signalName,
    totalQuantity: value.totalQuantity,
    windowEndOrdinalExclusive: value.windowEndOrdinalExclusive,
    windowStartOrdinal: value.windowStartOrdinal,
  };
  const snapshotDigest = await sha256Hex(encoder.encode(canonicalJson(input)));
  return Object.freeze({ ...value, contractVersion: TELEMETRY_HANDOFF_VERSION, snapshotDigest });
}

async function validateReferenceTelemetrySignal(signal: ReferenceTelemetrySignal): Promise<void> {
  requireVersion(signal.contractVersion);
  for (const [field, value] of Object.entries({
    signalId: signal.signalId,
    signalName: signal.signalName,
    endpointId: signal.endpointId,
    operation: signal.operation,
    requestId: signal.requestId,
    traceId: signal.traceId,
    bindingId: signal.bindingId,
    authenticationContextId: signal.authenticationContextId,
    outcome: signal.outcome,
    provenanceDigest: signal.provenanceDigest,
    aggregationIdentityDigest: signal.aggregationIdentityDigest,
    signalDigest: signal.signalDigest,
  })) requireString(value, `telemetry.${field}`);
  requireNonNegativeInteger(signal.ordinal, 'telemetry.ordinal');
  requireNonNegativeInteger(signal.quantity, 'telemetry.quantity');
  if (signal.signalKind !== 'counter' && signal.signalKind !== 'event') throw new TypeError('telemetry.signalKind is unsupported');
  if (signal.signalKind === 'event' && signal.quantity !== 1) throw new TypeError('Event telemetry quantity must equal 1');
  if (signal.signalKind === 'counter' && signal.quantity < 1) throw new TypeError('Counter telemetry quantity must be positive');

  const attributes = sortedStringMap(signal.attributes, 'telemetry.attributes');
  const aggregationInput: JsonValue = {
    attributes,
    endpointId: signal.endpointId,
    operation: signal.operation,
    outcome: signal.outcome,
    provenanceDigest: signal.provenanceDigest,
    signalKind: signal.signalKind,
    signalName: signal.signalName,
  };
  const aggregationDigest = await sha256Hex(encoder.encode(canonicalJson(aggregationInput)));
  if (aggregationDigest !== signal.aggregationIdentityDigest) throw new TypeError('Telemetry aggregation identity digest mismatch');
  const signalInput: JsonValue = {
    aggregationIdentityDigest: signal.aggregationIdentityDigest,
    attributes,
    authenticationContextId: signal.authenticationContextId,
    bindingId: signal.bindingId,
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    endpointId: signal.endpointId,
    operation: signal.operation,
    ordinal: signal.ordinal,
    outcome: signal.outcome,
    provenanceDigest: signal.provenanceDigest,
    quantity: signal.quantity,
    requestId: signal.requestId,
    signalId: signal.signalId,
    signalKind: signal.signalKind,
    signalName: signal.signalName,
    traceId: signal.traceId,
  };
  const signalDigest = await sha256Hex(encoder.encode(canonicalJson(signalInput)));
  if (signalDigest !== signal.signalDigest) throw new TypeError('Telemetry signal digest mismatch');
}

function sortedStringMap(values: Readonly<Record<string, string>>, field: string): Readonly<Record<string, string>> {
  if (values === null || typeof values !== 'object' || Array.isArray(values)) throw new TypeError(`${field} must be an object`);
  const output: Record<string, string> = {};
  for (const key of Object.keys(values).sort()) {
    requireString(key, `${field} key`);
    const value = values[key];
    requireString(value, `${field}.${key}`);
    output[key] = value;
  }
  return Object.freeze(output);
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

function requireVersion(value: unknown): asserts value is '1' {
  if (value !== TELEMETRY_HANDOFF_VERSION) throw new UnsupportedTelemetryHandoffVersionError(value);
}
function requireString(value: unknown, field: string): asserts value is string {
  if (typeof value !== 'string' || value.length === 0) throw new TypeError(`${field} must be a non-empty string`);
}
function requireNonNegativeInteger(value: unknown, field: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new TypeError(`${field} must be a non-negative safe integer`);
}
function requireIntegerRange(value: unknown, min: number, max: number, field: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min || value > max) {
    throw new TypeError(`${field} must be an integer in [${min}, ${max}]`);
  }
}
