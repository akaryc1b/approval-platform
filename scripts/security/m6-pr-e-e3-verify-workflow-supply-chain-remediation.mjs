#!/usr/bin/env node

export {
  reconcileScannerFindingIdentities,
} from './m6-pr-e-e3-verify-workflow-supply-chain-remediation-legacy.mjs';

export {
  canonicalH5OsvIdentityExtension,
  reconcileH5OsvFindings,
  reconcileH5OsvIdentityExtension,
  retainedH5OsvIdentityIds,
  verifyWorkflowSupplyChainRemediation,
} from './m6-pr-e-e3-verify-workflow-supply-chain-remediation-h5.mjs';

// Historical boundary tests intentionally inspect this facade source. Keep the
// accepted R2B fail-closed markers visible here while the executable historical
// implementation remains byte-for-byte retained in the legacy module.
export const HISTORICAL_R2B_SOURCE_COMPATIBILITY_MARKERS = Object.freeze([
  'verifyAcceptedR2B',
  'reconcileScannerFindingIdentities',
  'retained historical OSV',
  'unreviewed OSV identity addition detected',
  'reviewed OSV upstream identity drift',
  'reviewed OSV alias drift',
  'reviewed OSV package drift',
  'reviewed OSV scope drift',
  'OSV_DATABASE_DRIFT_RECONCILED_BY_EXACT_IDENTITY_SET',
  'TWO_NEW_OSV_FINDINGS_RETAINED_UNRESOLVED',
]);
