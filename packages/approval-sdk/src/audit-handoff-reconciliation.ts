import { canonicalJson, sha256Hex, type JsonValue } from './index.js';
import type { AuditHandoffAcknowledgement, AuditHandoffEnvelope } from './audit-handoff.js';
import { validateAuditHandoffEnvelope } from './audit-handoff.js';
import { TELEMETRY_HANDOFF_VERSION, UnsupportedTelemetryHandoffVersionError } from './telemetry-signal.js';

const encoder = new TextEncoder();
const MAX_RECONCILIATION_CAPACITY = 10_000;

export type ExpectedHandoffState = 'pending' | 'acknowledged';
export type HandoffReconciliationClassification =
  | 'pending_confirmed'
  | 'acknowledged_confirmed'
  | 'acknowledgement_missing'
  | 'missing_no_evidence'
  | 'conflicting_evidence';
export type HandoffReconciliationStatus = 'recorded' | 'duplicate' | 'capacity_rejected' | 'failed_closed';
export type ReconciliationAppendStatus = 'accepted' | 'capacity_rejected' | 'duplicate_rejected';

export interface AuditHandoffReconciliationInput {
  readonly contractVersion: '1';
  readonly expectedState: ExpectedHandoffState;
  readonly envelope: AuditHandoffEnvelope;
  readonly pendingEnvelope: AuditHandoffEnvelope | null;
  readonly acknowledgement: AuditHandoffAcknowledgement | null;
  readonly reconciliationOrdinal: number;
}

export interface AuditHandoffReconciliationProof {
  readonly contractVersion: '1';
  readonly handoffId: string;
  readonly expectedState: ExpectedHandoffState;
  readonly classification: HandoffReconciliationClassification;
  readonly safeToFinalize: boolean;
  readonly reconciliationOrdinal: number;
  readonly envelopeDigest: string;
  readonly pendingEnvelopeDigest: string | null;
  readonly acknowledgementId: string | null;
  readonly acknowledgementEnvelopeDigest: string | null;
  readonly evidenceDigest: string;
  readonly proofDigest: string;
}

export interface AuditHandoffReconciliationResult {
  readonly contractVersion: '1';
  readonly status: HandoffReconciliationStatus;
  readonly reasonCode: string;
  readonly proof: AuditHandoffReconciliationProof;
  readonly storedProofCount: number;
}

export class ScriptedHandoffReconciliationStore {
  private readonly proofsInternal: AuditHandoffReconciliationProof[] = [];
  private readonly proofDigests = new Set<string>();
  private readonly failureOperationNumbers: ReadonlySet<number>;
  private appendAttempts = 0;

  constructor(
    readonly capacity: number,
    failureOperationNumbers: readonly number[] = [],
  ) {
    requireIntegerRange(capacity, 1, MAX_RECONCILIATION_CAPACITY, 'handoffReconciliationStore.capacity');
    this.failureOperationNumbers = positiveUniqueIntegers(
      failureOperationNumbers,
      'handoffReconciliationStore.failureOperationNumbers',
    );
  }

  get proofs(): readonly AuditHandoffReconciliationProof[] { return [...this.proofsInternal]; }
  get size(): number { return this.proofsInternal.length; }

  append(proof: AuditHandoffReconciliationProof): ReconciliationAppendStatus {
    this.appendAttempts += 1;
    if (this.failureOperationNumbers.has(this.appendAttempts)) throw new Error('scripted reconciliation store failure');
    if (this.proofDigests.has(proof.proofDigest)) return 'duplicate_rejected';
    if (this.proofsInternal.length >= this.capacity) return 'capacity_rejected';
    this.proofsInternal.push(proof);
    this.proofDigests.add(proof.proofDigest);
    return 'accepted';
  }
}

export async function reconcileAuditHandoff(
  input: AuditHandoffReconciliationInput,
  store: ScriptedHandoffReconciliationStore,
): Promise<AuditHandoffReconciliationResult> {
  requireVersion(input.contractVersion);
  if (input.expectedState !== 'pending' && input.expectedState !== 'acknowledged') {
    throw new TypeError('handoffReconciliation.expectedState is unsupported');
  }
  requireNonNegativeInteger(input.reconciliationOrdinal, 'handoffReconciliation.reconciliationOrdinal');
  const envelope = validateAuditHandoffEnvelope(input.envelope);
  const pendingEnvelope = input.pendingEnvelope === null ? null : validateAuditHandoffEnvelope(input.pendingEnvelope);
  const acknowledgement = input.acknowledgement === null ? null : validateAcknowledgement(input.acknowledgement);

  const classification = classify(envelope, input.expectedState, pendingEnvelope, acknowledgement);
  const evidenceInput: JsonValue = {
    acknowledgement: acknowledgement === null ? null : acknowledgementMap(acknowledgement),
    envelope: envelopeMap(envelope),
    pendingEnvelope: pendingEnvelope === null ? null : envelopeMap(pendingEnvelope),
  };
  const evidenceDigest = await sha256Hex(encoder.encode(canonicalJson(evidenceInput)));
  const proofInput: JsonValue = {
    acknowledgementEnvelopeDigest: acknowledgement?.envelopeDigest ?? null,
    acknowledgementId: acknowledgement?.acknowledgementId ?? null,
    classification,
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    envelopeDigest: envelope.envelopeDigest,
    evidenceDigest,
    expectedState: input.expectedState,
    handoffId: envelope.handoffId,
    pendingEnvelopeDigest: pendingEnvelope?.envelopeDigest ?? null,
    reconciliationOrdinal: input.reconciliationOrdinal,
    safeToFinalize: classification === 'acknowledged_confirmed',
  };
  const proofDigest = await sha256Hex(encoder.encode(canonicalJson(proofInput)));
  const proof: AuditHandoffReconciliationProof = Object.freeze({
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    handoffId: envelope.handoffId,
    expectedState: input.expectedState,
    classification,
    safeToFinalize: classification === 'acknowledged_confirmed',
    reconciliationOrdinal: input.reconciliationOrdinal,
    envelopeDigest: envelope.envelopeDigest,
    pendingEnvelopeDigest: pendingEnvelope?.envelopeDigest ?? null,
    acknowledgementId: acknowledgement?.acknowledgementId ?? null,
    acknowledgementEnvelopeDigest: acknowledgement?.envelopeDigest ?? null,
    evidenceDigest,
    proofDigest,
  });

  let appendStatus: ReconciliationAppendStatus;
  try {
    appendStatus = store.append(proof);
  } catch {
    return result('failed_closed', 'handoff_reconciliation_store_failed', proof, store.size);
  }
  if (appendStatus === 'capacity_rejected') {
    return result('capacity_rejected', 'handoff_reconciliation_store_capacity', proof, store.size);
  }
  if (appendStatus === 'duplicate_rejected') {
    return result('duplicate', 'handoff_reconciliation_duplicate_proof', proof, store.size);
  }
  const reasonCode = classification === 'acknowledged_confirmed'
    ? 'handoff_acknowledged_confirmed'
    : classification === 'pending_confirmed'
      ? 'handoff_pending_confirmed'
      : classification === 'acknowledgement_missing'
        ? 'handoff_acknowledgement_missing'
        : classification === 'missing_no_evidence'
          ? 'handoff_missing_no_evidence'
          : 'handoff_conflicting_evidence';
  return result('recorded', reasonCode, proof, store.size);
}

export function auditHandoffReconciliationProofMap(
  value: AuditHandoffReconciliationProof,
): Record<string, JsonValue> {
  return {
    acknowledgementEnvelopeDigest: value.acknowledgementEnvelopeDigest,
    acknowledgementId: value.acknowledgementId,
    classification: value.classification,
    contractVersion: value.contractVersion,
    envelopeDigest: value.envelopeDigest,
    evidenceDigest: value.evidenceDigest,
    expectedState: value.expectedState,
    handoffId: value.handoffId,
    pendingEnvelopeDigest: value.pendingEnvelopeDigest,
    proofDigest: value.proofDigest,
    reconciliationOrdinal: value.reconciliationOrdinal,
    safeToFinalize: value.safeToFinalize,
  };
}

export function auditHandoffReconciliationResultMap(
  value: AuditHandoffReconciliationResult,
): Record<string, JsonValue> {
  return {
    contractVersion: value.contractVersion,
    proof: auditHandoffReconciliationProofMap(value.proof),
    reasonCode: value.reasonCode,
    status: value.status,
    storedProofCount: value.storedProofCount,
  };
}

function classify(
  envelope: AuditHandoffEnvelope,
  expectedState: ExpectedHandoffState,
  pendingEnvelope: AuditHandoffEnvelope | null,
  acknowledgement: AuditHandoffAcknowledgement | null,
): HandoffReconciliationClassification {
  if (pendingEnvelope !== null && acknowledgement !== null) return 'conflicting_evidence';
  if (pendingEnvelope !== null && !sameEnvelope(envelope, pendingEnvelope)) return 'conflicting_evidence';
  if (acknowledgement !== null && !sameAcknowledgement(envelope, acknowledgement)) return 'conflicting_evidence';
  if (acknowledgement !== null) return 'acknowledged_confirmed';
  if (pendingEnvelope !== null) {
    return expectedState === 'acknowledged' ? 'acknowledgement_missing' : 'pending_confirmed';
  }
  return 'missing_no_evidence';
}

function sameEnvelope(expected: AuditHandoffEnvelope, observed: AuditHandoffEnvelope): boolean {
  return expected.handoffId === observed.handoffId
    && expected.destinationReference === observed.destinationReference
    && expected.envelopeDigest === observed.envelopeDigest
    && expected.auditBatchDigest === observed.auditBatchDigest
    && expected.auditIdentityDigest === observed.auditIdentityDigest;
}

function sameAcknowledgement(expected: AuditHandoffEnvelope, acknowledgement: AuditHandoffAcknowledgement): boolean {
  return expected.handoffId === acknowledgement.handoffId
    && expected.destinationReference === acknowledgement.destinationReference
    && expected.envelopeDigest === acknowledgement.envelopeDigest
    && expected.auditBatchDigest === acknowledgement.auditBatchDigest;
}

function validateAcknowledgement(value: AuditHandoffAcknowledgement): AuditHandoffAcknowledgement {
  requireVersion(value.contractVersion);
  for (const [field, entry] of Object.entries({
    acknowledgementId: value.acknowledgementId,
    handoffId: value.handoffId,
    destinationReference: value.destinationReference,
    envelopeDigest: value.envelopeDigest,
    auditBatchDigest: value.auditBatchDigest,
  })) requireString(entry, `handoffAcknowledgement.${field}`);
  requireIntegerRange(value.deliveryAttempt, 1, Number.MAX_SAFE_INTEGER, 'handoffAcknowledgement.deliveryAttempt');
  requireNonNegativeInteger(value.acknowledgedOrdinal, 'handoffAcknowledgement.acknowledgedOrdinal');
  return value;
}

function envelopeMap(value: AuditHandoffEnvelope): Record<string, JsonValue> {
  return {
    attemptCount: value.attemptCount,
    auditBatchDigest: value.auditBatchDigest,
    auditIdentityDigest: value.auditIdentityDigest,
    contractVersion: value.contractVersion,
    destinationReference: value.destinationReference,
    envelopeDigest: value.envelopeDigest,
    firstEventId: value.firstEventId,
    handoffId: value.handoffId,
    handoffOrdinal: value.handoffOrdinal,
    recordCount: value.recordCount,
    terminalEventId: value.terminalEventId,
  };
}

function acknowledgementMap(value: AuditHandoffAcknowledgement): Record<string, JsonValue> {
  return {
    acknowledgedOrdinal: value.acknowledgedOrdinal,
    acknowledgementId: value.acknowledgementId,
    auditBatchDigest: value.auditBatchDigest,
    contractVersion: value.contractVersion,
    deliveryAttempt: value.deliveryAttempt,
    destinationReference: value.destinationReference,
    envelopeDigest: value.envelopeDigest,
    handoffId: value.handoffId,
  };
}

function result(
  status: HandoffReconciliationStatus,
  reasonCode: string,
  proof: AuditHandoffReconciliationProof,
  storedProofCount: number,
): AuditHandoffReconciliationResult {
  return Object.freeze({
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    status,
    reasonCode,
    proof,
    storedProofCount,
  });
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
