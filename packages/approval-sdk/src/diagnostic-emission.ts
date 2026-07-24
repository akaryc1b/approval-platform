import { canonicalJson, sha256Hex, type JsonValue } from './index.js';
import type { DiagnosticSeverity, SafeDiagnostic } from './diagnostics-audit.js';

export const EMISSION_POLICY_VERSION = '1' as const;
const encoder = new TextEncoder();
const MAX_SAMPLE_DENOMINATOR = 1_000_000;
const MAX_DEDUPLICATION_WINDOW = 1_000_000;
const MAX_IN_MEMORY_CAPACITY = 10_000;
export type DiagnosticEmissionStatus =
  | 'emitted'
  | 'below_threshold'
  | 'sampled_out'
  | 'duplicate'
  | 'sink_capacity'
  | 'sink_failed';

export type DiagnosticSinkAppendStatus = 'accepted' | 'capacity_rejected';

export interface DiagnosticEmissionPolicy {
  readonly contractVersion: '1';
  readonly minimumSeverity: DiagnosticSeverity;
  readonly sampleNumerator: number;
  readonly sampleDenominator: number;
  readonly sampleSalt: string;
  readonly deduplicationWindowOrdinals: number;
  readonly deduplicationCapacity: number;
}

export interface DiagnosticEmissionRequest {
  readonly contractVersion: '1';
  readonly emissionId: string;
  readonly sampleKey: string;
  readonly ordinal: number;
  readonly diagnostic: SafeDiagnostic;
}

export interface DiagnosticSinkRecord {
  readonly emissionId: string;
  readonly ordinal: number;
  readonly fingerprint: string;
  readonly diagnostic: SafeDiagnostic;
}

export interface DiagnosticEmissionResult {
  readonly contractVersion: '1';
  readonly status: DiagnosticEmissionStatus;
  readonly reasonCode: string;
  readonly fingerprint: string;
  readonly sampleBucket: number;
  readonly sinkSize: number;
  readonly trackerSize: number;
}

export class UnsupportedEmissionPolicyVersionError extends Error {
  constructor(readonly contractVersion: unknown) {
    super(`Unsupported emission policy contract version: ${String(contractVersion)}`);
    this.name = 'UnsupportedEmissionPolicyVersionError';
  }
}

/** Deterministic bounded tracker. Ordinals are supplied by the caller; no clock is read. */
export class BoundedDiagnosticDeduplicationTracker {
  private readonly seen = new Map<string, number>();
  private lastObservedOrdinal = -1;

  constructor(readonly capacity: number) {
    requireCapacity(capacity, 'deduplicationTracker.capacity');
  }

  isDuplicate(fingerprint: string, ordinal: number, windowOrdinals: number): boolean {
    requireString(fingerprint, 'fingerprint');
    requireNonNegativeInteger(ordinal, 'ordinal');
    requireWindow(windowOrdinals);
    if (ordinal < this.lastObservedOrdinal) {
      throw new TypeError('Diagnostic emission ordinals must be monotonic');
    }
    this.lastObservedOrdinal = ordinal;
    this.expire(ordinal, windowOrdinals);
    const previous = this.seen.get(fingerprint);
    return previous !== undefined && ordinal - previous <= windowOrdinals;
  }

  record(fingerprint: string, ordinal: number, windowOrdinals: number): void {
    requireString(fingerprint, 'fingerprint');
    requireNonNegativeInteger(ordinal, 'ordinal');
    requireWindow(windowOrdinals);
    if (ordinal < this.lastObservedOrdinal) {
      throw new TypeError('Diagnostic emission ordinals must be monotonic');
    }
    this.lastObservedOrdinal = ordinal;
    this.expire(ordinal, windowOrdinals);
    if (!this.seen.has(fingerprint) && this.seen.size >= this.capacity) {
      const victim = [...this.seen.entries()]
        .sort(([leftKey, leftOrdinal], [rightKey, rightOrdinal]) =>
          leftOrdinal - rightOrdinal || leftKey.localeCompare(rightKey))[0];
      if (victim) this.seen.delete(victim[0]);
    }
    this.seen.set(fingerprint, ordinal);
  }

  size(): number {
    return this.seen.size;
  }

  private expire(ordinal: number, windowOrdinals: number): void {
    for (const [fingerprint, previous] of this.seen) {
      if (ordinal - previous > windowOrdinals) this.seen.delete(fingerprint);
    }
  }
}

/** Deterministic fake diagnostic sink with bounded capacity and scripted append failures. */
export class ScriptedInMemoryDiagnosticSink {
  readonly records: DiagnosticSinkRecord[] = [];
  private appendAttempts = 0;
  private readonly failureAppendNumbers: Set<number>;

  constructor(
    readonly capacity: number,
    failureAppendNumbers: readonly number[] = [],
  ) {
    requireCapacity(capacity, 'diagnosticSink.capacity');
    this.failureAppendNumbers = positiveUniqueIntegers(
      failureAppendNumbers,
      'diagnosticSink.failureAppendNumbers',
    );
  }

  append(record: DiagnosticSinkRecord): DiagnosticSinkAppendStatus {
    validateDiagnosticSinkRecord(record);
    this.appendAttempts += 1;
    if (this.failureAppendNumbers.has(this.appendAttempts)) {
      throw new Error('scripted diagnostic sink failure');
    }
    if (this.records.length >= this.capacity) return 'capacity_rejected';
    this.records.push(Object.freeze({ ...record }));
    return 'accepted';
  }

  size(): number {
    return this.records.length;
  }
}

export async function diagnosticFingerprint(diagnostic: SafeDiagnostic): Promise<string> {
  validateSafeDiagnostic(diagnostic);
  return sha256Hex(encoder.encode(canonicalJson(safeDiagnosticMap(diagnostic))));
}

export async function diagnosticSampleBucket(
  policy: DiagnosticEmissionPolicy,
  sampleKey: string,
  fingerprint: string,
): Promise<number> {
  const validated = validateDiagnosticEmissionPolicy(policy);
  requireString(sampleKey, 'sampleKey');
  requireString(fingerprint, 'fingerprint');
  const hash = await sha256Hex(encoder.encode(
    `${validated.sampleSalt}\n${sampleKey}\n${fingerprint}`,
  ));
  return Number.parseInt(hash.slice(0, 8), 16) % validated.sampleDenominator;
}

export async function emitDiagnostic(
  rawPolicy: DiagnosticEmissionPolicy,
  request: DiagnosticEmissionRequest,
  tracker: BoundedDiagnosticDeduplicationTracker,
  sink: ScriptedInMemoryDiagnosticSink,
): Promise<DiagnosticEmissionResult> {
  const policy = validateDiagnosticEmissionPolicy(rawPolicy);
  validateEmissionRequest(request);
  if (tracker.capacity !== policy.deduplicationCapacity) {
    throw new TypeError('Diagnostic tracker capacity does not match policy');
  }
  const fingerprint = await diagnosticFingerprint(request.diagnostic);
  const sampleBucket = await diagnosticSampleBucket(policy, request.sampleKey, fingerprint);
  const base = {
    contractVersion: EMISSION_POLICY_VERSION,
    fingerprint,
    sampleBucket,
  } as const;

  if (severityRank(request.diagnostic.severity) < severityRank(policy.minimumSeverity)) {
    return emissionResult(base, 'below_threshold', 'diagnostic_below_threshold', sink, tracker);
  }
  if (sampleBucket >= policy.sampleNumerator) {
    return emissionResult(base, 'sampled_out', 'diagnostic_sampled_out', sink, tracker);
  }
  if (tracker.isDuplicate(
    fingerprint,
    request.ordinal,
    policy.deduplicationWindowOrdinals,
  )) {
    return emissionResult(base, 'duplicate', 'diagnostic_duplicate', sink, tracker);
  }

  let appendStatus: DiagnosticSinkAppendStatus;
  try {
    appendStatus = sink.append({
      emissionId: request.emissionId,
      ordinal: request.ordinal,
      fingerprint,
      diagnostic: request.diagnostic,
    });
  } catch {
    return emissionResult(base, 'sink_failed', 'diagnostic_sink_failed', sink, tracker);
  }
  if (appendStatus === 'capacity_rejected') {
    return emissionResult(base, 'sink_capacity', 'diagnostic_sink_capacity', sink, tracker);
  }
  tracker.record(fingerprint, request.ordinal, policy.deduplicationWindowOrdinals);
  return emissionResult(base, 'emitted', 'diagnostic_emitted', sink, tracker);
}

export function validateDiagnosticEmissionPolicy(
  policy: DiagnosticEmissionPolicy,
): DiagnosticEmissionPolicy {
  validateVersion(policy.contractVersion);
  requireSeverity(policy.minimumSeverity);
  requireNonNegativeInteger(policy.sampleNumerator, 'policy.sampleNumerator');
  requirePositiveInteger(policy.sampleDenominator, 'policy.sampleDenominator');
  if (policy.sampleDenominator > MAX_SAMPLE_DENOMINATOR) {
    throw new TypeError(`sampleDenominator cannot exceed ${MAX_SAMPLE_DENOMINATOR}`);
  }
  if (policy.sampleNumerator > policy.sampleDenominator) {
    throw new TypeError('sampleNumerator cannot exceed sampleDenominator');
  }
  requireString(policy.sampleSalt, 'policy.sampleSalt');
  requireWindow(policy.deduplicationWindowOrdinals);
  requireCapacity(policy.deduplicationCapacity, 'policy.deduplicationCapacity');
  return Object.freeze({ ...policy });
}

function emissionResult(
  base: {
    readonly contractVersion: '1';
    readonly fingerprint: string;
    readonly sampleBucket: number;
  },
  status: DiagnosticEmissionStatus,
  reasonCode: string,
  sink: ScriptedInMemoryDiagnosticSink,
  tracker: BoundedDiagnosticDeduplicationTracker,
): DiagnosticEmissionResult {
  return Object.freeze({
    ...base,
    status,
    reasonCode,
    sinkSize: sink.size(),
    trackerSize: tracker.size(),
  });
}

function safeDiagnosticMap(diagnostic: SafeDiagnostic): JsonValue {
  return {
    code: diagnostic.code,
    context: { ...diagnostic.context },
    contractVersion: diagnostic.contractVersion,
    message: diagnostic.message,
    provenanceDigest: diagnostic.provenanceDigest,
    redactionCount: diagnostic.redactionCount,
    severity: diagnostic.severity,
  };
}

function validateEmissionRequest(request: DiagnosticEmissionRequest): void {
  validateVersion(request.contractVersion);
  requireString(request.emissionId, 'emission.emissionId');
  requireString(request.sampleKey, 'emission.sampleKey');
  requireNonNegativeInteger(request.ordinal, 'emission.ordinal');
  validateSafeDiagnostic(request.diagnostic);
}

function validateSafeDiagnostic(diagnostic: SafeDiagnostic): void {
  if (diagnostic.contractVersion !== '1') {
    throw new TypeError('Safe diagnostic contract version is unsupported');
  }
  requireString(diagnostic.code, 'diagnostic.code');
  requireSeverity(diagnostic.severity);
  requireString(diagnostic.message, 'diagnostic.message');
  requireNonNegativeInteger(diagnostic.redactionCount, 'diagnostic.redactionCount');
  requireString(diagnostic.provenanceDigest, 'diagnostic.provenanceDigest');
  if (diagnostic.context === null || typeof diagnostic.context !== 'object' || Array.isArray(diagnostic.context)) {
    throw new TypeError('diagnostic.context must be an object');
  }
  for (const [key, value] of Object.entries(diagnostic.context)) {
    requireString(key, 'diagnostic.context key');
    requireString(value, `diagnostic.context.${key}`);
  }
}

function validateDiagnosticSinkRecord(record: DiagnosticSinkRecord): void {
  requireString(record.emissionId, 'diagnosticSinkRecord.emissionId');
  requireNonNegativeInteger(record.ordinal, 'diagnosticSinkRecord.ordinal');
  requireString(record.fingerprint, 'diagnosticSinkRecord.fingerprint');
  validateSafeDiagnostic(record.diagnostic);
}

function severityRank(severity: DiagnosticSeverity): number {
  switch (severity) {
    case 'info': return 0;
    case 'warning': return 1;
    case 'error': return 2;
  }
}

function validateVersion(value: unknown): asserts value is '1' {
  if (value !== EMISSION_POLICY_VERSION) throw new UnsupportedEmissionPolicyVersionError(value);
}

function requireSeverity(value: unknown): asserts value is DiagnosticSeverity {
  if (value !== 'info' && value !== 'warning' && value !== 'error') {
    throw new TypeError('diagnostic severity is unsupported');
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

function requirePositiveInteger(value: unknown, field: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 1) {
    throw new TypeError(`${field} must be a positive safe integer`);
  }
}

function requireCapacity(value: unknown, field: string): asserts value is number {
  requirePositiveInteger(value, field);
  if (value > MAX_IN_MEMORY_CAPACITY) {
    throw new TypeError(`${field} cannot exceed ${MAX_IN_MEMORY_CAPACITY}`);
  }
}

function requireWindow(value: unknown): asserts value is number {
  requireNonNegativeInteger(value, 'deduplicationWindowOrdinals');
  if (value > MAX_DEDUPLICATION_WINDOW) {
    throw new TypeError(`deduplicationWindowOrdinals cannot exceed ${MAX_DEDUPLICATION_WINDOW}`);
  }
}

function positiveUniqueIntegers(values: readonly number[], field: string): Set<number> {
  if (!Array.isArray(values)) throw new TypeError(`${field} must be an array`);
  const output = new Set<number>();
  for (const value of values) {
    requirePositiveInteger(value, field);
    if (output.has(value)) throw new TypeError(`${field} contains a duplicate value`);
    output.add(value);
  }
  return output;
}
