import { canonicalJson, sha256Hex, type JsonValue } from './index.js';

export const TELEMETRY_HANDOFF_VERSION = '1' as const;
const encoder = new TextEncoder();
const MAX_ALLOWED_KEYS = 32;
const MAX_ALLOWED_NAMES = 64;
const MAX_SIGNAL_BATCH = 1000;
const MAX_EXPORTER_CAPACITY = 10_000;
const FORBIDDEN_ATTRIBUTE = /(?:authorization|token|password|secret|private[._-]?key|certificate|credential|tenant|operator|permission|authority|auditReference|audit_reference)/i;

export type TelemetrySignalKind = 'counter' | 'event';
export type TelemetryExportStatus = 'exported' | 'degraded';
export type TelemetryAppendStatus = 'accepted' | 'capacity_rejected' | 'duplicate_rejected';

export interface TelemetryAttributePolicy {
  readonly contractVersion: '1';
  readonly allowedSignalNames: readonly string[];
  readonly allowedAttributeKeys: readonly string[];
  readonly allowedAttributeValues: Readonly<Record<string, readonly string[]>>;
  readonly maxAttributeCount: number;
  readonly maxAttributeValueLength: number;
  readonly maxBatchSize: number;
}

export interface TelemetrySignalInput {
  readonly contractVersion: '1';
  readonly signalId: string;
  readonly signalName: string;
  readonly signalKind: TelemetrySignalKind;
  readonly endpointId: string;
  readonly operation: string;
  readonly requestId: string;
  readonly traceId: string;
  readonly bindingId: string;
  readonly authenticationContextId: string;
  readonly outcome: string;
  readonly quantity: number;
  readonly ordinal: number;
  readonly provenanceDigest: string;
  readonly attributes: Readonly<Record<string, string>>;
}

export interface ReferenceTelemetrySignal extends TelemetrySignalInput {
  readonly aggregationIdentityDigest: string;
  readonly signalDigest: string;
}

export interface TelemetryExportProof {
  readonly contractVersion: '1';
  readonly signalCount: number;
  readonly firstSignalId: string;
  readonly lastSignalId: string;
  readonly batchDigest: string;
}

export interface TelemetryExportResult {
  readonly contractVersion: '1';
  readonly status: TelemetryExportStatus;
  readonly reasonCode: string;
  readonly proof: TelemetryExportProof | null;
  readonly exportedSignalCount: number;
  readonly exporterSize: number;
}

export class UnsupportedTelemetryHandoffVersionError extends Error {
  constructor(readonly contractVersion: unknown) {
    super(`Unsupported telemetry/handoff contract version: ${String(contractVersion)}`);
    this.name = 'UnsupportedTelemetryHandoffVersionError';
  }
}

export class ScriptedTelemetryExporter {
  private readonly signalsInternal: ReferenceTelemetrySignal[] = [];
  private readonly batchDigests = new Set<string>();
  private appendAttempts = 0;
  private readonly failureBatchNumbers: ReadonlySet<number>;

  constructor(
    readonly capacity: number,
    failureBatchNumbers: readonly number[] = [],
  ) {
    requireCapacity(capacity, 'telemetryExporter.capacity');
    this.failureBatchNumbers = positiveUniqueIntegers(failureBatchNumbers, 'telemetryExporter.failureBatchNumbers');
  }

  get signals(): readonly ReferenceTelemetrySignal[] {
    return [...this.signalsInternal];
  }

  get size(): number {
    return this.signalsInternal.length;
  }

  appendBatch(signals: readonly ReferenceTelemetrySignal[], proof: TelemetryExportProof): TelemetryAppendStatus {
    this.appendAttempts += 1;
    if (this.failureBatchNumbers.has(this.appendAttempts)) {
      throw new Error('scripted telemetry exporter failure');
    }
    if (this.batchDigests.has(proof.batchDigest)) return 'duplicate_rejected';
    if (this.signalsInternal.length + signals.length > this.capacity) return 'capacity_rejected';
    this.signalsInternal.push(...signals);
    this.batchDigests.add(proof.batchDigest);
    return 'accepted';
  }
}

export function validateTelemetryAttributePolicy(policy: TelemetryAttributePolicy): TelemetryAttributePolicy {
  requireVersion(policy.contractVersion);
  const allowedSignalNames = uniqueStrings(policy.allowedSignalNames, 'telemetryPolicy.allowedSignalNames', MAX_ALLOWED_NAMES);
  const allowedAttributeKeys = uniqueStrings(policy.allowedAttributeKeys, 'telemetryPolicy.allowedAttributeKeys', MAX_ALLOWED_KEYS);
  for (const key of allowedAttributeKeys) {
    if (FORBIDDEN_ATTRIBUTE.test(key)) throw new TypeError(`Forbidden telemetry attribute key: ${key}`);
  }
  const allowedAttributeValues = validateAllowedAttributeValues(allowedAttributeKeys, policy.allowedAttributeValues);
  requireIntegerRange(policy.maxAttributeCount, 0, allowedAttributeKeys.length, 'telemetryPolicy.maxAttributeCount');
  requireIntegerRange(policy.maxAttributeValueLength, 1, 256, 'telemetryPolicy.maxAttributeValueLength');
  requireIntegerRange(policy.maxBatchSize, 1, MAX_SIGNAL_BATCH, 'telemetryPolicy.maxBatchSize');
  return Object.freeze({
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    allowedSignalNames: Object.freeze(allowedSignalNames),
    allowedAttributeKeys: Object.freeze(allowedAttributeKeys),
    allowedAttributeValues,
    maxAttributeCount: policy.maxAttributeCount,
    maxAttributeValueLength: policy.maxAttributeValueLength,
    maxBatchSize: policy.maxBatchSize,
  });
}

export async function createReferenceTelemetrySignal(
  policyInput: TelemetryAttributePolicy,
  input: TelemetrySignalInput,
): Promise<ReferenceTelemetrySignal> {
  const policy = validateTelemetryAttributePolicy(policyInput);
  requireVersion(input.contractVersion);
  requireString(input.signalId, 'telemetry.signalId');
  requireString(input.signalName, 'telemetry.signalName');
  if (!policy.allowedSignalNames.includes(input.signalName)) {
    throw new TypeError(`Telemetry signal name is not allowed: ${input.signalName}`);
  }
  if (input.signalKind !== 'counter' && input.signalKind !== 'event') {
    throw new TypeError('telemetry.signalKind is unsupported');
  }
  for (const [field, value] of Object.entries({
    endpointId: input.endpointId,
    operation: input.operation,
    requestId: input.requestId,
    traceId: input.traceId,
    bindingId: input.bindingId,
    authenticationContextId: input.authenticationContextId,
    outcome: input.outcome,
    provenanceDigest: input.provenanceDigest,
  })) requireString(value, `telemetry.${field}`);
  requireNonNegativeInteger(input.ordinal, 'telemetry.ordinal');
  requireNonNegativeInteger(input.quantity, 'telemetry.quantity');
  if (input.signalKind === 'event' && input.quantity !== 1) {
    throw new TypeError('Event telemetry quantity must equal 1');
  }
  if (input.signalKind === 'counter' && input.quantity < 1) {
    throw new TypeError('Counter telemetry quantity must be positive');
  }
  const attributes = validateAttributes(policy, input.attributes);
  const aggregationInput: JsonValue = {
    attributes,
    endpointId: input.endpointId,
    operation: input.operation,
    outcome: input.outcome,
    provenanceDigest: input.provenanceDigest,
    signalKind: input.signalKind,
    signalName: input.signalName,
  };
  const aggregationIdentityDigest = await sha256Hex(encoder.encode(canonicalJson(aggregationInput)));
  const signalInput: JsonValue = {
    aggregationIdentityDigest,
    attributes,
    authenticationContextId: input.authenticationContextId,
    bindingId: input.bindingId,
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    endpointId: input.endpointId,
    operation: input.operation,
    ordinal: input.ordinal,
    outcome: input.outcome,
    provenanceDigest: input.provenanceDigest,
    quantity: input.quantity,
    requestId: input.requestId,
    signalId: input.signalId,
    signalKind: input.signalKind,
    signalName: input.signalName,
    traceId: input.traceId,
  };
  const signalDigest = await sha256Hex(encoder.encode(canonicalJson(signalInput)));
  return Object.freeze({
    ...input,
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    attributes,
    aggregationIdentityDigest,
    signalDigest,
  });
}

export async function exportTelemetryBatch(
  policyInput: TelemetryAttributePolicy,
  signals: readonly ReferenceTelemetrySignal[],
  exporter: ScriptedTelemetryExporter,
): Promise<TelemetryExportResult> {
  const policy = validateTelemetryAttributePolicy(policyInput);
  if (!Array.isArray(signals) || signals.length === 0 || signals.length > policy.maxBatchSize) {
    throw new TypeError('Telemetry batch size is outside policy bounds');
  }
  const signalIds = new Set<string>();
  const signalDigests = new Set<string>();
  let previousOrdinal = -1;
  for (const signal of signals) {
    requireVersion(signal.contractVersion);
    if (!signalIds.add(signal.signalId)) throw new TypeError('Telemetry batch contains duplicate signalId');
    if (!signalDigests.add(signal.signalDigest)) throw new TypeError('Telemetry batch contains duplicate signalDigest');
    if (signal.ordinal < previousOrdinal) throw new TypeError('Telemetry batch ordinals must be non-decreasing');
    previousOrdinal = signal.ordinal;
  }
  const batchValue = signals.map(signalMap) as unknown as JsonValue;
  const batchDigest = await sha256Hex(encoder.encode(canonicalJson(batchValue)));
  const proof: TelemetryExportProof = Object.freeze({
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    signalCount: signals.length,
    firstSignalId: signals[0]!.signalId,
    lastSignalId: signals[signals.length - 1]!.signalId,
    batchDigest,
  });
  let appendStatus: TelemetryAppendStatus;
  try {
    appendStatus = exporter.appendBatch(signals, proof);
  } catch {
    return result('degraded', 'telemetry_exporter_failed', proof, 0, exporter.size);
  }
  if (appendStatus === 'capacity_rejected') {
    return result('degraded', 'telemetry_exporter_capacity', proof, 0, exporter.size);
  }
  if (appendStatus === 'duplicate_rejected') {
    return result('degraded', 'telemetry_duplicate_batch', proof, 0, exporter.size);
  }
  return result('exported', 'telemetry_batch_exported', proof, signals.length, exporter.size);
}

export function telemetrySignalMap(signal: ReferenceTelemetrySignal): Record<string, JsonValue> {
  return signalMap(signal);
}

export function telemetryExportResultMap(value: TelemetryExportResult): Record<string, JsonValue> {
  return {
    contractVersion: value.contractVersion,
    exportedSignalCount: value.exportedSignalCount,
    exporterSize: value.exporterSize,
    proof: value.proof === null ? null : {
      batchDigest: value.proof.batchDigest,
      contractVersion: value.proof.contractVersion,
      firstSignalId: value.proof.firstSignalId,
      lastSignalId: value.proof.lastSignalId,
      signalCount: value.proof.signalCount,
    },
    reasonCode: value.reasonCode,
    status: value.status,
  };
}

function result(
  status: TelemetryExportStatus,
  reasonCode: string,
  proof: TelemetryExportProof | null,
  exportedSignalCount: number,
  exporterSize: number,
): TelemetryExportResult {
  return Object.freeze({
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    status,
    reasonCode,
    proof,
    exportedSignalCount,
    exporterSize,
  });
}

function signalMap(signal: ReferenceTelemetrySignal): Record<string, JsonValue> {
  return {
    aggregationIdentityDigest: signal.aggregationIdentityDigest,
    attributes: signal.attributes,
    authenticationContextId: signal.authenticationContextId,
    bindingId: signal.bindingId,
    contractVersion: signal.contractVersion,
    endpointId: signal.endpointId,
    operation: signal.operation,
    ordinal: signal.ordinal,
    outcome: signal.outcome,
    provenanceDigest: signal.provenanceDigest,
    quantity: signal.quantity,
    requestId: signal.requestId,
    signalDigest: signal.signalDigest,
    signalId: signal.signalId,
    signalKind: signal.signalKind,
    signalName: signal.signalName,
    traceId: signal.traceId,
  };
}

function validateAttributes(
  policy: TelemetryAttributePolicy,
  attributes: Readonly<Record<string, string>>,
): Readonly<Record<string, string>> {
  if (attributes === null || typeof attributes !== 'object' || Array.isArray(attributes)) {
    throw new TypeError('telemetry.attributes must be an object');
  }
  const keys = Object.keys(attributes).sort();
  if (keys.length > policy.maxAttributeCount) throw new TypeError('telemetry.attributes exceeds maxAttributeCount');
  const output: Record<string, string> = {};
  for (const key of keys) {
    requireString(key, 'telemetry.attribute key');
    if (!policy.allowedAttributeKeys.includes(key)) throw new TypeError(`Telemetry attribute key is not allowed: ${key}`);
    if (FORBIDDEN_ATTRIBUTE.test(key)) throw new TypeError(`Forbidden telemetry attribute key: ${key}`);
    const value = attributes[key];
    requireString(value, `telemetry.attributes.${key}`);
    if (value.length > policy.maxAttributeValueLength) throw new TypeError(`Telemetry attribute value is too long: ${key}`);
    if (!policy.allowedAttributeValues[key]?.includes(value)) throw new TypeError(`Telemetry attribute value is not allowed: ${key}`);
    output[key] = value;
  }
  return Object.freeze(output);
}


function validateAllowedAttributeValues(
  keys: readonly string[],
  values: Readonly<Record<string, readonly string[]>>,
): Readonly<Record<string, readonly string[]>> {
  if (values === null || typeof values !== 'object' || Array.isArray(values)) {
    throw new TypeError('telemetryPolicy.allowedAttributeValues must be an object');
  }
  const output: Record<string, readonly string[]> = {};
  const valueKeys = Object.keys(values).sort();
  if (valueKeys.length !== keys.length || valueKeys.some((key, index) => key !== keys[index])) {
    throw new TypeError('telemetryPolicy.allowedAttributeValues must cover exactly the allowed keys');
  }
  for (const key of keys) {
    output[key] = Object.freeze(uniqueStrings(values[key] ?? [], `telemetryPolicy.allowedAttributeValues.${key}`, 64));
  }
  return Object.freeze(output);
}

function uniqueStrings(values: readonly string[], field: string, maxSize: number): string[] {
  if (!Array.isArray(values) || values.length === 0 || values.length > maxSize) {
    throw new TypeError(`${field} must be non-empty and bounded`);
  }
  const output: string[] = [];
  const seen = new Set<string>();
  for (const value of values) {
    requireString(value, field);
    if (!seen.add(value)) throw new TypeError(`${field} contains duplicates`);
    output.push(value);
  }
  return output.sort();
}

function positiveUniqueIntegers(values: readonly number[], field: string): ReadonlySet<number> {
  const output = new Set<number>();
  for (const value of values) {
    requireIntegerRange(value, 1, Number.MAX_SAFE_INTEGER, field);
    if (!output.add(value)) throw new TypeError(`${field} contains duplicates`);
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
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new TypeError(`${field} must be a non-negative safe integer`);
  }
}

function requireIntegerRange(value: unknown, min: number, max: number, field: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min || value > max) {
    throw new TypeError(`${field} must be an integer in [${min}, ${max}]`);
  }
}

function requireCapacity(value: number, field: string): void {
  requireIntegerRange(value, 1, MAX_EXPORTER_CAPACITY, field);
}
