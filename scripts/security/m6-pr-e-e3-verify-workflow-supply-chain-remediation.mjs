#!/usr/bin/env node
import { createHash } from 'node:crypto';

import {
  classifyCurrentOsvIdentitySet,
  reconcileScannerFindingIdentities as reconcileGenericScannerFindingIdentities,
  verifyWorkflowSupplyChainRemediation as verifyGenericWorkflowSupplyChainRemediation,
} from './m6-pr-e-e3-verify-workflow-supply-chain-remediation-generic.mjs';
import { reviewCurrentOsvIdentitySetR3c } from './m6-pr-e-e3-review-current-osv-r3c.mjs';
import {
  canonicalH5OsvIdentityExtension,
  reconcileH5OsvFindings as reconcileH5OsvFindingsUnchecked,
  reconcileH5OsvIdentityExtension as reconcileH5OsvIdentityExtensionUnchecked,
  retainedH5OsvIdentityIds,
} from './m6-pr-e-e3-verify-workflow-supply-chain-remediation-h5.mjs';

const stable = (value) => Array.isArray(value)
  ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
    : value;
const canonical = (value) => JSON.stringify(stable(value));
const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const canonicalOsvFindingId = (finding) => sha256([
  'OSV',
  finding.upstreamFindingId || '',
  finding.package?.ecosystem || '',
  finding.package?.name || '',
  finding.package?.version || '',
].join('\0'));

const GENERIC_BOUNDARY_MARKERS = Object.freeze([
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
]);
void GENERIC_BOUNDARY_MARKERS;

function requireCanonicalH5Additions(osv, reconciliation) {
  const currentById = new Map((osv?.findings || []).map((finding) => [finding.findingId, finding]));
  for (const addition of reconciliation.addedOsvFindings || []) {
    const finding = currentById.get(addition.findingId);
    if (!finding) throw new Error(`H5 OSV canonical addition missing ${addition.findingId}`);
    for (const key of ['aliases', 'componentRefs', 'scopes', 'upstreamSeverity', 'fixedVersions']) {
      if (!Array.isArray(finding[key])) throw new Error(`H5 OSV canonical addition metadata incomplete ${key}`);
    }
    if (!finding.componentRefs.length || !finding.scopes.length) {
      throw new Error(`H5 OSV canonical addition graph evidence incomplete ${addition.findingId}`);
    }
    if (finding.findingId !== canonicalOsvFindingId(finding)) {
      throw new Error(`H5 OSV canonical finding identity mismatch ${finding.findingId}`);
    }
  }
  return reconciliation;
}

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

export {
  canonicalH5OsvIdentityExtension,
  classifyCurrentOsvIdentitySet,
  retainedH5OsvIdentityIds,
};
export const canonicalH5OsvFindingId = canonicalOsvFindingId;
export const HISTORICAL_R2B_SOURCE_COMPATIBILITY_MARKERS = GENERIC_BOUNDARY_MARKERS;

export function reconcileH5OsvFindings(osv) {
  return requireCanonicalH5Additions(osv, reconcileH5OsvFindingsUnchecked(osv));
}

export function reconcileH5OsvIdentityExtension(e4) {
  return requireCanonicalH5Additions(
    e4?.scanners?.osv,
    reconcileH5OsvIdentityExtensionUnchecked(e4),
  );
}

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
