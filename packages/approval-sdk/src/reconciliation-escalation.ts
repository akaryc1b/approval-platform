import { canonicalJson, sha256Hex, type JsonValue } from './index.js';
import type {
  AuditHandoffReconciliationProof,
  HandoffReconciliationClassification,
} from './audit-handoff-reconciliation.js';
import {
  CHECKPOINT_ESCALATION_VERSION,
  UnsupportedCheckpointEscalationVersionError,
} from './aggregate-export-checkpoint.js';

const encoder = new TextEncoder();
const MAX_BOUND = 10_000;

export type ReconciliationEscalationLevel =
  | 'none'
  | 'observe'
  | 'investigate'
  | 'block'
  | 'resolved';
export type ReconciliationEscalationStatus =
  | 'recorded'
  | 'duplicate'
  | 'capacity_rejected'
  | 'failed_closed';
export type ReconciliationEscalationAppendStatus =
  | 'accepted'
  | 'duplicate_rejected'
  | 'capacity_rejected'
  | 'ordinal_rejected'
  | 'level_regression_rejected'
  | 'finalization_conflict_rejected';

export interface ReconciliationEscalationPolicy {
  readonly contractVersion: '1';
  readonly observeAfterOrdinals: number;
  readonly investigateAfterOrdinals: number;
  readonly blockAfterOrdinals: number;
  readonly maxStoredProofs: number;
}

export interface ReconciliationEscalationInput {
  readonly contractVersion: '1';
  readonly reconciliationProof: AuditHandoffReconciliationProof;
  readonly evaluationOrdinal: number;
}

export interface HandoffFinalizationCheckpoint {
  readonly contractVersion: '1';
  readonly handoffId: string;
  readonly envelopeDigest: string;
  readonly acknowledgementId: string;
  readonly reconciliationProofDigest: string;
  readonly evaluationOrdinal: number;
  readonly checkpointDigest: string;
}

export interface ReconciliationEscalationProof {
  readonly contractVersion: '1';
  readonly handoffId: string;
  readonly reconciliationProofDigest: string;
  readonly classification: HandoffReconciliationClassification;
  readonly reconciliationOrdinal: number;
  readonly evaluationOrdinal: number;
  readonly ageOrdinals: number;
  readonly escalationLevel: ReconciliationEscalationLevel;
  readonly requiresManualAction: boolean;
  readonly safeToFinalize: boolean;
  readonly finalizationCheckpointDigest: string | null;
  readonly proofDigest: string;
}

export interface ReconciliationEscalationResult {
  readonly contractVersion: '1';
  readonly status: ReconciliationEscalationStatus;
  readonly reasonCode: string;
  readonly proof: ReconciliationEscalationProof;
  readonly finalizationCheckpoint: HandoffFinalizationCheckpoint | null;
  readonly storedProofCount: number;
  readonly storedFinalizationCount: number;
}

interface StoredEscalation {
  readonly proof: ReconciliationEscalationProof;
  readonly finalizationCheckpoint: HandoffFinalizationCheckpoint | null;
}

export class ScriptedReconciliationEscalationStore {
  private readonly values: StoredEscalation[] = [];
  private readonly proofDigests = new Set<string>();
  private readonly latestByHandoff = new Map<string, StoredEscalation>();
  private readonly finalizationByHandoff = new Map<string, HandoffFinalizationCheckpoint>();
  private readonly failureOperationNumbers: ReadonlySet<number>;
  private appendAttempts = 0;

  constructor(
    readonly capacity: number,
    failureOperationNumbers: readonly number[] = [],
  ) {
    requireIntegerRange(capacity, 1, MAX_BOUND, 'reconciliationEscalationStore.capacity');
    this.failureOperationNumbers = positiveUniqueIntegers(
      failureOperationNumbers,
      'reconciliationEscalationStore.failureOperationNumbers',
    );
  }

  get proofs(): readonly ReconciliationEscalationProof[] {
    return this.values.map((value) => value.proof);
  }
  get finalizations(): readonly HandoffFinalizationCheckpoint[] {
    return [...this.finalizationByHandoff.values()];
  }
  get size(): number { return this.values.length; }
  get finalizationCount(): number { return this.finalizationByHandoff.size; }

  append(
    proof: ReconciliationEscalationProof,
    finalizationCheckpoint: HandoffFinalizationCheckpoint | null,
  ): ReconciliationEscalationAppendStatus {
    this.appendAttempts += 1;
    if (this.failureOperationNumbers.has(this.appendAttempts)) {
      throw new Error('scripted reconciliation escalation store failure');
    }
    if (this.proofDigests.has(proof.proofDigest)) return 'duplicate_rejected';

    const existingFinalization = this.finalizationByHandoff.get(proof.handoffId);
    if (existingFinalization !== undefined) {
      if (finalizationCheckpoint === null
        || finalizationCheckpoint.checkpointDigest !== existingFinalization.checkpointDigest) {
        return 'finalization_conflict_rejected';
      }
      return 'duplicate_rejected';
    }

    const latest = this.latestByHandoff.get(proof.handoffId);
    if (latest !== undefined) {
      if (proof.evaluationOrdinal < latest.proof.evaluationOrdinal) return 'ordinal_rejected';
      if (!allowsTransition(latest.proof.escalationLevel, proof.escalationLevel)) {
        return 'level_regression_rejected';
      }
    }
    if (this.values.length >= this.capacity) return 'capacity_rejected';

    const stored = Object.freeze({ proof, finalizationCheckpoint });
    this.values.push(stored);
    this.proofDigests.add(proof.proofDigest);
    this.latestByHandoff.set(proof.handoffId, stored);
    if (finalizationCheckpoint !== null) {
      this.finalizationByHandoff.set(proof.handoffId, finalizationCheckpoint);
    }
    return 'accepted';
  }
}

export function validateReconciliationEscalationPolicy(
  policy: ReconciliationEscalationPolicy,
): ReconciliationEscalationPolicy {
  requireVersion(policy.contractVersion);
  requireNonNegativeInteger(policy.observeAfterOrdinals, 'escalationPolicy.observeAfterOrdinals');
  requireNonNegativeInteger(
    policy.investigateAfterOrdinals,
    'escalationPolicy.investigateAfterOrdinals',
  );
  requireNonNegativeInteger(policy.blockAfterOrdinals, 'escalationPolicy.blockAfterOrdinals');
  if (policy.observeAfterOrdinals > policy.investigateAfterOrdinals
    || policy.investigateAfterOrdinals > policy.blockAfterOrdinals) {
    throw new TypeError('Escalation thresholds must be monotonic');
  }
  requireIntegerRange(policy.maxStoredProofs, 1, MAX_BOUND, 'escalationPolicy.maxStoredProofs');
  return Object.freeze({ ...policy, contractVersion: CHECKPOINT_ESCALATION_VERSION });
}

export async function evaluateReconciliationEscalation(
  policyInput: ReconciliationEscalationPolicy,
  input: ReconciliationEscalationInput,
  store: ScriptedReconciliationEscalationStore,
): Promise<ReconciliationEscalationResult> {
  const policy = validateReconciliationEscalationPolicy(policyInput);
  requireVersion(input.contractVersion);
  if (store.capacity > policy.maxStoredProofs) {
    throw new TypeError('Escalation store capacity exceeds policy maximum');
  }
  const reconciliation = validateReconciliationProof(input.reconciliationProof);
  requireNonNegativeInteger(input.evaluationOrdinal, 'escalation.evaluationOrdinal');
  if (input.evaluationOrdinal < reconciliation.reconciliationOrdinal) {
    const placeholder = await createEscalationProof(
      reconciliation,
      input.evaluationOrdinal,
      0,
      baseLevel(reconciliation.classification),
      false,
      null,
    );
    return escalationResult(
      'failed_closed',
      'reconciliation_escalation_before_reconciliation',
      placeholder,
      null,
      store,
    );
  }

  const ageOrdinals = input.evaluationOrdinal - reconciliation.reconciliationOrdinal;
  const level = escalationLevel(policy, reconciliation.classification, ageOrdinals);
  const safeToFinalize = reconciliation.classification === 'acknowledged_confirmed'
    && reconciliation.safeToFinalize;
  let finalizationCheckpoint: HandoffFinalizationCheckpoint | null = null;
  if (safeToFinalize) {
    if (reconciliation.acknowledgementId === null) {
      throw new TypeError('Acknowledged reconciliation requires acknowledgementId');
    }
    const finalizationValue: JsonValue = {
      acknowledgementId: reconciliation.acknowledgementId,
      contractVersion: CHECKPOINT_ESCALATION_VERSION,
      envelopeDigest: reconciliation.envelopeDigest,
      evaluationOrdinal: input.evaluationOrdinal,
      handoffId: reconciliation.handoffId,
      reconciliationProofDigest: reconciliation.proofDigest,
    };
    const checkpointDigest = await digest(finalizationValue);
    finalizationCheckpoint = Object.freeze({
      contractVersion: CHECKPOINT_ESCALATION_VERSION,
      handoffId: reconciliation.handoffId,
      envelopeDigest: reconciliation.envelopeDigest,
      acknowledgementId: reconciliation.acknowledgementId,
      reconciliationProofDigest: reconciliation.proofDigest,
      evaluationOrdinal: input.evaluationOrdinal,
      checkpointDigest,
    });
  }

  const proof = await createEscalationProof(
    reconciliation,
    input.evaluationOrdinal,
    ageOrdinals,
    level,
    safeToFinalize,
    finalizationCheckpoint?.checkpointDigest ?? null,
  );

  let appendStatus: ReconciliationEscalationAppendStatus;
  try {
    appendStatus = store.append(proof, finalizationCheckpoint);
  } catch {
    return escalationResult(
      'failed_closed',
      'reconciliation_escalation_store_failed',
      proof,
      finalizationCheckpoint,
      store,
    );
  }
  if (appendStatus === 'duplicate_rejected') {
    return escalationResult(
      'duplicate',
      'reconciliation_escalation_duplicate_proof',
      proof,
      finalizationCheckpoint,
      store,
    );
  }
  if (appendStatus === 'capacity_rejected') {
    return escalationResult(
      'capacity_rejected',
      'reconciliation_escalation_store_capacity',
      proof,
      finalizationCheckpoint,
      store,
    );
  }
  if (appendStatus === 'ordinal_rejected') {
    return escalationResult(
      'failed_closed',
      'reconciliation_escalation_ordinal_regression',
      proof,
      finalizationCheckpoint,
      store,
    );
  }
  if (appendStatus === 'level_regression_rejected') {
    return escalationResult(
      'failed_closed',
      'reconciliation_escalation_level_regression',
      proof,
      finalizationCheckpoint,
      store,
    );
  }
  if (appendStatus === 'finalization_conflict_rejected') {
    return escalationResult(
      'failed_closed',
      'reconciliation_finalization_conflict',
      proof,
      finalizationCheckpoint,
      store,
    );
  }

  const reasonCode = level === 'resolved'
    ? 'reconciliation_finalization_checkpoint_recorded'
    : `reconciliation_escalation_${level}`;
  return escalationResult('recorded', reasonCode, proof, finalizationCheckpoint, store);
}

export function handoffFinalizationCheckpointMap(
  value: HandoffFinalizationCheckpoint,
): Record<string, JsonValue> {
  return {
    acknowledgementId: value.acknowledgementId,
    checkpointDigest: value.checkpointDigest,
    contractVersion: value.contractVersion,
    envelopeDigest: value.envelopeDigest,
    evaluationOrdinal: value.evaluationOrdinal,
    handoffId: value.handoffId,
    reconciliationProofDigest: value.reconciliationProofDigest,
  };
}

export function reconciliationEscalationProofMap(
  value: ReconciliationEscalationProof,
): Record<string, JsonValue> {
  return {
    ageOrdinals: value.ageOrdinals,
    classification: value.classification,
    contractVersion: value.contractVersion,
    escalationLevel: value.escalationLevel,
    evaluationOrdinal: value.evaluationOrdinal,
    finalizationCheckpointDigest: value.finalizationCheckpointDigest,
    handoffId: value.handoffId,
    proofDigest: value.proofDigest,
    reconciliationOrdinal: value.reconciliationOrdinal,
    reconciliationProofDigest: value.reconciliationProofDigest,
    requiresManualAction: value.requiresManualAction,
    safeToFinalize: value.safeToFinalize,
  };
}

export function reconciliationEscalationResultMap(
  value: ReconciliationEscalationResult,
): Record<string, JsonValue> {
  return {
    contractVersion: value.contractVersion,
    finalizationCheckpoint: value.finalizationCheckpoint === null
      ? null
      : handoffFinalizationCheckpointMap(value.finalizationCheckpoint),
    proof: reconciliationEscalationProofMap(value.proof),
    reasonCode: value.reasonCode,
    status: value.status,
    storedFinalizationCount: value.storedFinalizationCount,
    storedProofCount: value.storedProofCount,
  };
}

async function createEscalationProof(
  reconciliation: AuditHandoffReconciliationProof,
  evaluationOrdinal: number,
  ageOrdinals: number,
  escalationLevel: ReconciliationEscalationLevel,
  safeToFinalize: boolean,
  finalizationCheckpointDigest: string | null,
): Promise<ReconciliationEscalationProof> {
  const requiresManualAction = escalationLevel === 'investigate' || escalationLevel === 'block';
  const proofValue: JsonValue = {
    ageOrdinals,
    classification: reconciliation.classification,
    contractVersion: CHECKPOINT_ESCALATION_VERSION,
    escalationLevel,
    evaluationOrdinal,
    finalizationCheckpointDigest,
    handoffId: reconciliation.handoffId,
    reconciliationOrdinal: reconciliation.reconciliationOrdinal,
    reconciliationProofDigest: reconciliation.proofDigest,
    requiresManualAction,
    safeToFinalize,
  };
  const proofDigest = await digest(proofValue);
  return Object.freeze({
    contractVersion: CHECKPOINT_ESCALATION_VERSION,
    handoffId: reconciliation.handoffId,
    reconciliationProofDigest: reconciliation.proofDigest,
    classification: reconciliation.classification,
    reconciliationOrdinal: reconciliation.reconciliationOrdinal,
    evaluationOrdinal,
    ageOrdinals,
    escalationLevel,
    requiresManualAction,
    safeToFinalize,
    finalizationCheckpointDigest,
    proofDigest,
  });
}

function validateReconciliationProof(
  value: AuditHandoffReconciliationProof,
): AuditHandoffReconciliationProof {
  requireVersion(value.contractVersion);
  requireString(value.handoffId, 'reconciliationProof.handoffId');
  requireString(value.envelopeDigest, 'reconciliationProof.envelopeDigest');
  requireString(value.evidenceDigest, 'reconciliationProof.evidenceDigest');
  requireString(value.proofDigest, 'reconciliationProof.proofDigest');
  requireNonNegativeInteger(value.reconciliationOrdinal, 'reconciliationProof.reconciliationOrdinal');
  const acknowledged = value.classification === 'acknowledged_confirmed';
  if (value.safeToFinalize !== acknowledged) {
    throw new TypeError('safeToFinalize must match acknowledged confirmation');
  }
  if (acknowledged) {
    requireString(value.acknowledgementId, 'reconciliationProof.acknowledgementId');
    requireString(
      value.acknowledgementEnvelopeDigest,
      'reconciliationProof.acknowledgementEnvelopeDigest',
    );
  }
  return value;
}

function baseLevel(
  classification: HandoffReconciliationClassification,
): ReconciliationEscalationLevel {
  if (classification === 'acknowledged_confirmed') return 'resolved';
  if (classification === 'conflicting_evidence') return 'investigate';
  if (classification === 'acknowledgement_missing'
    || classification === 'missing_no_evidence') return 'observe';
  return 'none';
}

function escalationLevel(
  policy: ReconciliationEscalationPolicy,
  classification: HandoffReconciliationClassification,
  ageOrdinals: number,
): ReconciliationEscalationLevel {
  const base = baseLevel(classification);
  if (base === 'resolved') return 'resolved';
  let level: ReconciliationEscalationLevel = base;
  if (ageOrdinals >= policy.blockAfterOrdinals) level = maxLevel(level, 'block');
  else if (ageOrdinals >= policy.investigateAfterOrdinals) level = maxLevel(level, 'investigate');
  else if (ageOrdinals >= policy.observeAfterOrdinals) level = maxLevel(level, 'observe');
  return level;
}

function maxLevel(
  left: ReconciliationEscalationLevel,
  right: ReconciliationEscalationLevel,
): ReconciliationEscalationLevel {
  return levelRank(left) >= levelRank(right) ? left : right;
}
function allowsTransition(
  previous: ReconciliationEscalationLevel,
  next: ReconciliationEscalationLevel,
): boolean {
  if (previous === 'resolved') return next === 'resolved';
  if (next === 'resolved') return true;
  return levelRank(next) >= levelRank(previous);
}
function levelRank(value: ReconciliationEscalationLevel): number {
  return value === 'none' ? 0
    : value === 'observe' ? 1
      : value === 'investigate' ? 2
        : value === 'block' ? 3
          : 4;
}

async function digest(value: JsonValue): Promise<string> {
  return sha256Hex(encoder.encode(canonicalJson(value)));
}

function escalationResult(
  status: ReconciliationEscalationStatus,
  reasonCode: string,
  proof: ReconciliationEscalationProof,
  finalizationCheckpoint: HandoffFinalizationCheckpoint | null,
  store: ScriptedReconciliationEscalationStore,
): ReconciliationEscalationResult {
  return Object.freeze({
    contractVersion: CHECKPOINT_ESCALATION_VERSION,
    status,
    reasonCode,
    proof,
    finalizationCheckpoint,
    storedProofCount: store.size,
    storedFinalizationCount: store.finalizationCount,
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
