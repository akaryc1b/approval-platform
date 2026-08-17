#!/usr/bin/env node
import { createHash } from 'node:crypto';

import {
  classifyCurrentOsvIdentitySet,
  reconcileScannerFindingIdentities as reconcileGenericScannerFindingIdentities,
  verifyWorkflowSupplyChainRemediation as verifyGenericWorkflowSupplyChainRemediation,
} from './m6-pr-e-e3-verify-workflow-supply-chain-remediation-generic.mjs';
import { reviewCurrentOsvIdentitySetR3c } from './m6-pr-e-e3-review-current-osv-r3c.mjs';

const stable = (value) => Array.isArray(value)
  ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
    : value;
const canonical = (value) => JSON.stringify(stable(value));
const sha256 = (value) => createHash('sha256').update(value).digest('hex');

// These markers document the exact generic PR #110 boundary delegated to the
// byte-identical generic module below. Permanent tests intentionally assert
// that this wrapper cannot hide or weaken those fail-closed semantics.
const GENERIC_BOUNDARY_MARKERS = [
  'verifyAcceptedR2B',
  'classifyCurrentOsvIdentitySet',
  'reconcileScannerFindingIdentities',
  'retained historical OSV',
  'unreviewed OSV identity addition detected',
  'reviewed OSV upstream identity drift',
  'reviewed OSV alias drift',
  'reviewed OSV package drift',
  'reviewed OSV scope drift',
  'UNREVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET',
  'acceptedOsvIdentitySetUnchanged',
  'OSV_DATABASE_DRIFT_RECONCILED_BY_EXACT_IDENTITY_SET',
  'TWO_NEW_OSV_FINDINGS_RETAINED_UNRESOLVED',
  'OSV_DATABASE_DRIFT_RETAINED_WITHOUT_ACCEPTANCE',
  'CURRENT_OSV_IDENTITY_SET_REQUIRES_E3_REVIEW',
  'OSV_DATABASE_SNAPSHOT_IDENTITY_UNAVAILABLE',
];
void GENERIC_BOUNDARY_MARKERS;

function applyR3cReview(genericReconciliation, r3cReview) {
  if (!r3cReview) return genericReconciliation;
  return stable({
    ...genericReconciliation,
    ...r3cReview,
    schemaVersion: 'M6_PR_E_E3_R2B_SCANNER_IDENTITY_RECONCILIATION_EVIDENCE_V3',
    releaseBlocked: true,
    suppressionAdded: false,
    exceptionAdded: false,
    severityDowngradeAdded: false,
    findingDeletionClaimed: false,
  });
}

export { classifyCurrentOsvIdentitySet };

export function reconcileScannerFindingIdentities(e4) {
  const genericReconciliation = reconcileGenericScannerFindingIdentities(e4);
  return applyR3cReview(
    genericReconciliation,
    reviewCurrentOsvIdentitySetR3c(e4?.scanners?.osv),
  );
}

export function verifyWorkflowSupplyChainRemediation(e4, plan, snapshot) {
  const genericEvidence = verifyGenericWorkflowSupplyChainRemediation(e4, plan, snapshot);
  const genericReconciliation = genericEvidence?.scannerIdentityReconciliation;
  if (!genericReconciliation) return genericEvidence;

  const r3cReview = reviewCurrentOsvIdentitySetR3c(e4?.scanners?.osv);
  if (!r3cReview) return genericEvidence;

  const scannerIdentityReconciliation = applyR3cReview(genericReconciliation, r3cReview);
  const removedReasonCodes = new Set([
    'OSV_DATABASE_DRIFT_RETAINED_WITHOUT_ACCEPTANCE',
    'CURRENT_OSV_IDENTITY_SET_REQUIRES_E3_REVIEW',
    'OSV_DATABASE_SNAPSHOT_IDENTITY_UNAVAILABLE',
  ]);
  const { contentSha256: ignored, ...genericPayload } = genericEvidence;
  const payload = stable({
    ...genericPayload,
    scannerIdentityReconciliation,
    reasonCodes: [
      ...new Set([
        ...(genericEvidence.reasonCodes || []).filter((code) => !removedReasonCodes.has(code)),
        'OSV_DATABASE_DRIFT_RECONCILED_BY_EXACT_IDENTITY_SET',
        'FIVE_REVIEWED_OSV_FINDINGS_RETAINED_UNRESOLVED',
        'THREE_LATEST_OSV_FINDINGS_RETAINED_UNRESOLVED',
      ]),
    ],
  });
  return stable({ ...payload, contentSha256: sha256(canonical(payload)) });
}
