import { canonicalJson, sha256Hex, type JsonValue } from './index.js';
import type { AuditCompletenessProof } from './audit-completeness.js';
import { TELEMETRY_HANDOFF_VERSION, UnsupportedTelemetryHandoffVersionError } from './telemetry-signal.js';

const encoder = new TextEncoder();
const MAX_QUEUE_CAPACITY = 10_000;

export type ScriptedHandoffOutcome = 'ack' | 'nack' | 'timeout_like' | 'failure';
export type AuditHandoffStatus =
  | 'acknowledged'
  | 'duplicate_acknowledged'
  | 'pending'
  | 'capacity_rejected'
  | 'failed_closed';

export interface AuditHandoffInput {
  readonly contractVersion: '1';
  readonly handoffId: string;
  readonly destinationReference: string;
  readonly handoffOrdinal: number;
  readonly proof: AuditCompletenessProof;
}

export interface AuditHandoffEnvelope {
  readonly contractVersion: '1';
  readonly handoffId: string;
  readonly destinationReference: string;
  readonly handoffOrdinal: number;
  readonly auditBatchDigest: string;
  readonly auditIdentityDigest: string;
  readonly recordCount: number;
  readonly attemptCount: number;
  readonly firstEventId: string;
  readonly terminalEventId: string;
  readonly envelopeDigest: string;
}

export interface AuditHandoffAcknowledgement {
  readonly contractVersion: '1';
  readonly acknowledgementId: string;
  readonly handoffId: string;
  readonly destinationReference: string;
  readonly envelopeDigest: string;
  readonly auditBatchDigest: string;
  readonly deliveryAttempt: number;
  readonly acknowledgedOrdinal: number;
}

export interface AuditHandoffResult {
  readonly contractVersion: '1';
  readonly status: AuditHandoffStatus;
  readonly reasonCode: string;
  readonly envelope: AuditHandoffEnvelope;
  readonly acknowledgement: AuditHandoffAcknowledgement | null;
  readonly deliveryAttempt: number;
  readonly pendingCount: number;
  readonly acknowledgedCount: number;
}

interface PendingState {
  readonly envelope: AuditHandoffEnvelope;
  deliveryAttempts: number;
}

export class ScriptedAuditHandoffQueue {
  private readonly pending = new Map<string, PendingState>();
  private readonly acknowledged = new Map<string, AuditHandoffAcknowledgement>();
  private readonly outcomes: readonly ScriptedHandoffOutcome[];
  private nextOutcomeIndex = 0;

  constructor(
    readonly capacity: number,
    outcomes: readonly ScriptedHandoffOutcome[],
  ) {
    requireIntegerRange(capacity, 1, MAX_QUEUE_CAPACITY, 'auditHandoffQueue.capacity');
    if (!Array.isArray(outcomes) || outcomes.length === 0) throw new TypeError('auditHandoffQueue.outcomes must be non-empty');
    for (const outcome of outcomes) {
      if (!['ack', 'nack', 'timeout_like', 'failure'].includes(outcome)) {
        throw new TypeError(`Unsupported scripted handoff outcome: ${String(outcome)}`);
      }
    }
    this.outcomes = [...outcomes];
  }

  get pendingCount(): number {
    return this.pending.size;
  }

  get acknowledgedCount(): number {
    return this.acknowledged.size;
  }

  handoff(envelope: AuditHandoffEnvelope): Promise<AuditHandoffResult> {
    return this.doHandoff(validateAuditHandoffEnvelope(envelope));
  }

  private async doHandoff(envelope: AuditHandoffEnvelope): Promise<AuditHandoffResult> {
    const existingAcknowledgement = this.acknowledged.get(envelope.handoffId);
    if (existingAcknowledgement !== undefined) {
      if (existingAcknowledgement.envelopeDigest !== envelope.envelopeDigest) {
        return result('failed_closed', 'audit_handoff_identity_conflict', envelope, null, existingAcknowledgement.deliveryAttempt, this);
      }
      return result(
        'duplicate_acknowledged',
        'audit_handoff_duplicate_acknowledged',
        envelope,
        existingAcknowledgement,
        existingAcknowledgement.deliveryAttempt,
        this,
      );
    }

    let pending = this.pending.get(envelope.handoffId);
    if (pending !== undefined && pending.envelope.envelopeDigest !== envelope.envelopeDigest) {
      return result('failed_closed', 'audit_handoff_identity_conflict', envelope, null, pending.deliveryAttempts, this);
    }
    if (pending === undefined) {
      if (this.pending.size + this.acknowledged.size >= this.capacity) {
        return result('capacity_rejected', 'audit_handoff_queue_capacity', envelope, null, 0, this);
      }
      pending = { envelope, deliveryAttempts: 0 };
      this.pending.set(envelope.handoffId, pending);
    }

    pending.deliveryAttempts += 1;
    const deliveryAttempt = pending.deliveryAttempts;
    const outcome = this.outcomes[this.nextOutcomeIndex] ?? this.outcomes[this.outcomes.length - 1]!;
    this.nextOutcomeIndex += 1;

    if (outcome === 'nack') {
      return result('pending', 'audit_handoff_nack', envelope, null, deliveryAttempt, this);
    }
    if (outcome === 'timeout_like') {
      return result('pending', 'audit_handoff_timeout_like', envelope, null, deliveryAttempt, this);
    }
    if (outcome === 'failure') {
      return result('failed_closed', 'audit_handoff_queue_failed', envelope, null, deliveryAttempt, this);
    }

    const acknowledgement = await createAcknowledgement(envelope, deliveryAttempt);
    this.pending.delete(envelope.handoffId);
    this.acknowledged.set(envelope.handoffId, acknowledgement);
    return result('acknowledged', 'audit_handoff_acknowledged', envelope, acknowledgement, deliveryAttempt, this);
  }
}

export async function createAuditHandoffEnvelope(input: AuditHandoffInput): Promise<AuditHandoffEnvelope> {
  requireVersion(input.contractVersion);
  requireString(input.handoffId, 'auditHandoff.handoffId');
  requireString(input.destinationReference, 'auditHandoff.destinationReference');
  requireNonNegativeInteger(input.handoffOrdinal, 'auditHandoff.handoffOrdinal');
  validateProof(input.proof);
  const envelopeInput: JsonValue = {
    attemptCount: input.proof.attemptCount,
    auditBatchDigest: input.proof.batchDigest,
    auditIdentityDigest: input.proof.identityDigest,
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    destinationReference: input.destinationReference,
    firstEventId: input.proof.firstEventId,
    handoffId: input.handoffId,
    handoffOrdinal: input.handoffOrdinal,
    recordCount: input.proof.recordCount,
    terminalEventId: input.proof.terminalEventId,
  };
  const envelopeDigest = await sha256Hex(encoder.encode(canonicalJson(envelopeInput)));
  return Object.freeze({
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    handoffId: input.handoffId,
    destinationReference: input.destinationReference,
    handoffOrdinal: input.handoffOrdinal,
    auditBatchDigest: input.proof.batchDigest,
    auditIdentityDigest: input.proof.identityDigest,
    recordCount: input.proof.recordCount,
    attemptCount: input.proof.attemptCount,
    firstEventId: input.proof.firstEventId,
    terminalEventId: input.proof.terminalEventId,
    envelopeDigest,
  });
}

export function validateAuditHandoffEnvelope(envelope: AuditHandoffEnvelope): AuditHandoffEnvelope {
  requireVersion(envelope.contractVersion);
  for (const [field, value] of Object.entries({
    handoffId: envelope.handoffId,
    destinationReference: envelope.destinationReference,
    auditBatchDigest: envelope.auditBatchDigest,
    auditIdentityDigest: envelope.auditIdentityDigest,
    firstEventId: envelope.firstEventId,
    terminalEventId: envelope.terminalEventId,
    envelopeDigest: envelope.envelopeDigest,
  })) requireString(value, `auditHandoff.${field}`);
  requireNonNegativeInteger(envelope.handoffOrdinal, 'auditHandoff.handoffOrdinal');
  requireIntegerRange(envelope.recordCount, 1, Number.MAX_SAFE_INTEGER, 'auditHandoff.recordCount');
  requireNonNegativeInteger(envelope.attemptCount, 'auditHandoff.attemptCount');
  if (envelope.recordCount !== envelope.attemptCount + 2) {
    throw new TypeError('auditHandoff recordCount must equal attemptCount + 2');
  }
  return envelope;
}

export function auditHandoffEnvelopeMap(value: AuditHandoffEnvelope): Record<string, JsonValue> {
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

export function auditHandoffResultMap(value: AuditHandoffResult): Record<string, JsonValue> {
  return {
    acknowledgedCount: value.acknowledgedCount,
    acknowledgement: value.acknowledgement === null ? null : {
      acknowledgedOrdinal: value.acknowledgement.acknowledgedOrdinal,
      acknowledgementId: value.acknowledgement.acknowledgementId,
      auditBatchDigest: value.acknowledgement.auditBatchDigest,
      contractVersion: value.acknowledgement.contractVersion,
      deliveryAttempt: value.acknowledgement.deliveryAttempt,
      destinationReference: value.acknowledgement.destinationReference,
      envelopeDigest: value.acknowledgement.envelopeDigest,
      handoffId: value.acknowledgement.handoffId,
    },
    contractVersion: value.contractVersion,
    deliveryAttempt: value.deliveryAttempt,
    envelope: auditHandoffEnvelopeMap(value.envelope),
    pendingCount: value.pendingCount,
    reasonCode: value.reasonCode,
    status: value.status,
  };
}

async function createAcknowledgement(
  envelope: AuditHandoffEnvelope,
  deliveryAttempt: number,
): Promise<AuditHandoffAcknowledgement> {
  const acknowledgementId = await sha256Hex(
    encoder.encode(`audit-handoff-ack-v1\n${envelope.envelopeDigest}\n${deliveryAttempt}`),
  );
  return Object.freeze({
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    acknowledgementId,
    handoffId: envelope.handoffId,
    destinationReference: envelope.destinationReference,
    envelopeDigest: envelope.envelopeDigest,
    auditBatchDigest: envelope.auditBatchDigest,
    deliveryAttempt,
    acknowledgedOrdinal: envelope.handoffOrdinal + deliveryAttempt,
  });
}

function result(
  status: AuditHandoffStatus,
  reasonCode: string,
  envelope: AuditHandoffEnvelope,
  acknowledgement: AuditHandoffAcknowledgement | null,
  deliveryAttempt: number,
  queue: ScriptedAuditHandoffQueue,
): AuditHandoffResult {
  return Object.freeze({
    contractVersion: TELEMETRY_HANDOFF_VERSION,
    status,
    reasonCode,
    envelope,
    acknowledgement,
    deliveryAttempt,
    pendingCount: queue.pendingCount,
    acknowledgedCount: queue.acknowledgedCount,
  });
}

function validateProof(proof: AuditCompletenessProof): void {
  requireVersion(proof.contractVersion);
  requireIntegerRange(proof.recordCount, 1, Number.MAX_SAFE_INTEGER, 'auditProof.recordCount');
  requireNonNegativeInteger(proof.attemptCount, 'auditProof.attemptCount');
  if (proof.recordCount !== proof.attemptCount + 2) throw new TypeError('auditProof recordCount must equal attemptCount + 2');
  for (const [field, value] of Object.entries({
    firstEventId: proof.firstEventId,
    terminalEventId: proof.terminalEventId,
    identityDigest: proof.identityDigest,
    batchDigest: proof.batchDigest,
  })) requireString(value, `auditProof.${field}`);
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
