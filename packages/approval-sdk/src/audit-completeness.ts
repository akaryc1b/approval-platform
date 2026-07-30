import { canonicalJson, sha256Hex, type JsonValue } from './index.js';
import type { AdapterAuditEvent } from './diagnostics-audit.js';
import { EMISSION_POLICY_VERSION, UnsupportedEmissionPolicyVersionError } from './diagnostic-emission.js';

const encoder = new TextEncoder();
const MAX_IN_MEMORY_CAPACITY = 10_000;

export type AuditPhase = 'started' | 'attempt' | 'terminal';

export type AuditCompletenessReason =
  | 'complete'
  | 'record_count_mismatch'
  | 'sequence_mismatch'
  | 'phase_mismatch'
  | 'event_type_mismatch'
  | 'duplicate_event_id'
  | 'identity_mismatch'
  | 'time_regression';

export type AuditBatchEmissionStatus = 'committed' | 'failed_closed';

export type AtomicAuditAppendStatus =
  | 'accepted'
  | 'capacity_rejected'
  | 'duplicate_rejected';

export interface AuditCompletenessPolicy {
  readonly contractVersion: '1';
  readonly expectedAttemptCount: number;
  readonly startedEventType: string;
  readonly attemptEventType: string;
  readonly terminalEventTypes: readonly string[];
}

export interface AuditEmissionRecord {
  readonly contractVersion: '1';
  readonly sequence: number;
  readonly phase: AuditPhase;
  readonly event: AdapterAuditEvent;
}

export interface AuditCompletenessProof {
  readonly contractVersion: '1';
  readonly recordCount: number;
  readonly attemptCount: number;
  readonly firstEventId: string;
  readonly terminalEventId: string;
  readonly identityDigest: string;
  readonly batchDigest: string;
}

export type AuditCompletenessValidation =
  | {
    readonly complete: true;
    readonly reason: 'complete';
    readonly proof: AuditCompletenessProof;
  }
  | {
    readonly complete: false;
    readonly reason: Exclude<AuditCompletenessReason, 'complete'>;
    readonly proof: null;
  };

export interface AtomicAuditBatch {
  readonly proof: AuditCompletenessProof;
  readonly records: readonly AuditEmissionRecord[];
}

export interface AuditBatchEmissionResult {
  readonly contractVersion: '1';
  readonly status: AuditBatchEmissionStatus;
  readonly reasonCode: string;
  readonly proof: AuditCompletenessProof | null;
  readonly committedRecordCount: number;
  readonly sinkSize: number;
}

export class ScriptedAtomicAuditSink {
  readonly records: AuditEmissionRecord[] = [];
  private readonly batchDigests = new Set<string>();
  private readonly failureBatchNumbers: Set<number>;
  private appendAttempts = 0;

  constructor(
    readonly capacity: number,
    failureBatchNumbers: readonly number[] = [],
  ) {
    requireCapacity(capacity, 'auditSink.capacity');
    this.failureBatchNumbers = positiveUniqueIntegers(
      failureBatchNumbers,
      'auditSink.failureBatchNumbers',
    );
  }

  appendBatch(batch: AtomicAuditBatch): AtomicAuditAppendStatus {
    validateProof(batch.proof);
    if (!Array.isArray(batch.records) || batch.records.length === 0) {
      throw new TypeError('audit batch records must be non-empty');
    }
    this.appendAttempts += 1;
    if (this.failureBatchNumbers.has(this.appendAttempts)) {
      throw new Error('scripted audit sink failure');
    }
    if (this.batchDigests.has(batch.proof.batchDigest)) return 'duplicate_rejected';
    if (this.records.length + batch.records.length > this.capacity) return 'capacity_rejected';
    const copies = batch.records.map((record) => freezeAuditRecord(record));
    this.records.push(...copies);
    this.batchDigests.add(batch.proof.batchDigest);
    return 'accepted';
  }

  size(): number {
    return this.records.length;
  }
}

export async function validateAuditCompleteness(
  rawPolicy: AuditCompletenessPolicy,
  records: readonly AuditEmissionRecord[],
): Promise<AuditCompletenessValidation> {
  const policy = validateAuditCompletenessPolicy(rawPolicy);
  if (!Array.isArray(records) || records.length !== policy.expectedAttemptCount + 2) {
    return incomplete('record_count_mismatch');
  }

  const eventIds = new Set<string>();
  let previousTime = -1;
  const firstEvent = records[0]!.event;
  const identity = auditIdentity(firstEvent);
  for (let index = 0; index < records.length; index += 1) {
    const record = records[index]!;
    validateAuditRecord(record);
    if (record.sequence !== index) return incomplete('sequence_mismatch');
    const expectedPhase: AuditPhase = index === 0
      ? 'started'
      : index === records.length - 1
        ? 'terminal'
        : 'attempt';
    if (record.phase !== expectedPhase) return incomplete('phase_mismatch');
    if (index === 0 && record.event.eventType !== policy.startedEventType) {
      return incomplete('event_type_mismatch');
    }
    if (expectedPhase === 'attempt' && record.event.eventType !== policy.attemptEventType) {
      return incomplete('event_type_mismatch');
    }
    if (expectedPhase === 'terminal' && !policy.terminalEventTypes.includes(record.event.eventType)) {
      return incomplete('event_type_mismatch');
    }
    if (eventIds.has(record.event.eventId)) return incomplete('duplicate_event_id');
    eventIds.add(record.event.eventId);
    if (!sameAuditIdentity(identity, auditIdentity(record.event))) {
      return incomplete('identity_mismatch');
    }
    if (record.event.occurredAtEpochSeconds < previousTime) {
      return incomplete('time_regression');
    }
    previousTime = record.event.occurredAtEpochSeconds;
  }

  const identityDigest = await sha256Hex(encoder.encode(canonicalJson(identity)));
  const batchDigest = await sha256Hex(encoder.encode(canonicalJson(
    records.map(auditRecordMap) as JsonValue,
  )));
  const proof: AuditCompletenessProof = Object.freeze({
    contractVersion: EMISSION_POLICY_VERSION,
    recordCount: records.length,
    attemptCount: policy.expectedAttemptCount,
    firstEventId: records[0]!.event.eventId,
    terminalEventId: records[records.length - 1]!.event.eventId,
    identityDigest,
    batchDigest,
  });
  return { complete: true, reason: 'complete', proof };
}

export async function emitCompleteAuditBatch(
  policy: AuditCompletenessPolicy,
  records: readonly AuditEmissionRecord[],
  sink: ScriptedAtomicAuditSink,
): Promise<AuditBatchEmissionResult> {
  const validation = await validateAuditCompleteness(policy, records);
  if (!validation.complete) {
    return auditBatchResult('failed_closed', `audit_${validation.reason}`, null, 0, sink);
  }
  let appendStatus: AtomicAuditAppendStatus;
  try {
    appendStatus = sink.appendBatch({ proof: validation.proof, records });
  } catch {
    return auditBatchResult('failed_closed', 'audit_sink_failed', validation.proof, 0, sink);
  }
  if (appendStatus === 'capacity_rejected') {
    return auditBatchResult('failed_closed', 'audit_sink_capacity', validation.proof, 0, sink);
  }
  if (appendStatus === 'duplicate_rejected') {
    return auditBatchResult('failed_closed', 'audit_duplicate_batch', validation.proof, 0, sink);
  }
  return auditBatchResult(
    'committed',
    'audit_batch_committed',
    validation.proof,
    records.length,
    sink,
  );
}

export function validateAuditCompletenessPolicy(
  policy: AuditCompletenessPolicy,
): AuditCompletenessPolicy {
  validateVersion(policy.contractVersion);
  requireNonNegativeInteger(policy.expectedAttemptCount, 'auditPolicy.expectedAttemptCount');
  if (policy.expectedAttemptCount > MAX_IN_MEMORY_CAPACITY - 2) {
    throw new TypeError('expectedAttemptCount exceeds the safe in-memory bound');
  }
  requireString(policy.startedEventType, 'auditPolicy.startedEventType');
  requireString(policy.attemptEventType, 'auditPolicy.attemptEventType');
  if (!Array.isArray(policy.terminalEventTypes) || policy.terminalEventTypes.length === 0) {
    throw new TypeError('auditPolicy.terminalEventTypes must be non-empty');
  }
  const terminal = uniqueStrings(policy.terminalEventTypes, 'auditPolicy.terminalEventTypes');
  return Object.freeze({ ...policy, terminalEventTypes: Object.freeze(terminal) });
}

function auditBatchResult(
  status: AuditBatchEmissionStatus,
  reasonCode: string,
  proof: AuditCompletenessProof | null,
  committedRecordCount: number,
  sink: ScriptedAtomicAuditSink,
): AuditBatchEmissionResult {
  return Object.freeze({
    contractVersion: EMISSION_POLICY_VERSION,
    status,
    reasonCode,
    proof,
    committedRecordCount,
    sinkSize: sink.size(),
  });
}

function incomplete(
  reason: Exclude<AuditCompletenessReason, 'complete'>,
): AuditCompletenessValidation {
  return { complete: false, reason, proof: null };
}

function auditIdentity(event: AdapterAuditEvent): JsonValue & Record<string, JsonValue> {
  return {
    authenticationContextId: event.authenticationContextId,
    bindingId: event.bindingId,
    endpointId: event.endpointId,
    operation: event.operation,
    provenanceDigest: event.provenanceDigest,
    requestId: event.requestId,
    traceId: event.traceId,
  };
}

function sameAuditIdentity(
  left: JsonValue & Record<string, JsonValue>,
  right: JsonValue & Record<string, JsonValue>,
): boolean {
  return canonicalJson(left) === canonicalJson(right);
}

function auditRecordMap(record: AuditEmissionRecord): JsonValue {
  return {
    contractVersion: record.contractVersion,
    event: auditEventMap(record.event),
    phase: record.phase,
    sequence: record.sequence,
  };
}

function auditEventMap(event: AdapterAuditEvent): JsonValue {
  return {
    authenticationContextId: event.authenticationContextId,
    bindingId: event.bindingId,
    contractVersion: event.contractVersion,
    endpointId: event.endpointId,
    eventId: event.eventId,
    eventType: event.eventType,
    occurredAtEpochSeconds: event.occurredAtEpochSeconds,
    operation: event.operation,
    outcome: event.outcome,
    provenanceDigest: event.provenanceDigest,
    reasonCode: event.reasonCode,
    requestId: event.requestId,
    traceId: event.traceId,
  };
}

function validateAuditRecord(record: AuditEmissionRecord): void {
  validateVersion(record.contractVersion);
  requireNonNegativeInteger(record.sequence, 'auditRecord.sequence');
  if (record.phase !== 'started' && record.phase !== 'attempt' && record.phase !== 'terminal') {
    throw new TypeError('auditRecord.phase is unsupported');
  }
  validateAuditEvent(record.event);
}

function validateAuditEvent(event: AdapterAuditEvent): void {
  if (event.contractVersion !== '1') throw new TypeError('audit event contract version is unsupported');
  for (const [field, value] of Object.entries({
    eventId: event.eventId,
    eventType: event.eventType,
    endpointId: event.endpointId,
    operation: event.operation,
    requestId: event.requestId,
    traceId: event.traceId,
    bindingId: event.bindingId,
    authenticationContextId: event.authenticationContextId,
    reasonCode: event.reasonCode,
    provenanceDigest: event.provenanceDigest,
  })) requireString(value, `auditEvent.${field}`);
  if (event.outcome !== 'succeeded' && event.outcome !== 'failed' && event.outcome !== 'rejected') {
    throw new TypeError('auditEvent.outcome is unsupported');
  }
  requireNonNegativeInteger(event.occurredAtEpochSeconds, 'auditEvent.occurredAtEpochSeconds');
}

function validateProof(proof: AuditCompletenessProof): void {
  validateVersion(proof.contractVersion);
  requirePositiveInteger(proof.recordCount, 'auditProof.recordCount');
  requireNonNegativeInteger(proof.attemptCount, 'auditProof.attemptCount');
  requireString(proof.firstEventId, 'auditProof.firstEventId');
  requireString(proof.terminalEventId, 'auditProof.terminalEventId');
  requireString(proof.identityDigest, 'auditProof.identityDigest');
  requireString(proof.batchDigest, 'auditProof.batchDigest');
}

function freezeAuditRecord(record: AuditEmissionRecord): AuditEmissionRecord {
  validateAuditRecord(record);
  return Object.freeze({ ...record, event: Object.freeze({ ...record.event }) });
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

function uniqueStrings(values: readonly string[], field: string): string[] {
  const output: string[] = [];
  const seen = new Set<string>();
  for (const value of values) {
    requireString(value, field);
    if (seen.has(value)) throw new TypeError(`${field} contains a duplicate value`);
    seen.add(value);
    output.push(value);
  }
  return output;
}

function validateVersion(value: unknown): asserts value is '1' {
  if (value !== EMISSION_POLICY_VERSION) throw new UnsupportedEmissionPolicyVersionError(value);
}
