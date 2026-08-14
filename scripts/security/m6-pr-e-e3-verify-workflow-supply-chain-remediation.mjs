#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

import { reconcileCurrentOsvIdentityDrift } from './m6-pr-e-e3-reconcile-current-osv-drift.mjs';
import { verifyWorkflowSupplyChainRemediation as verifyAcceptedR2B } from './m6-pr-e-e3-verify-workflow-supply-chain-remediation-accepted.mjs';

const SHA64 = /^[0-9a-f]{64}$/;
const CONTRACT_SHA256 = 'f317b8f6568100c5da132a46c9cd4162851c60dfc36dbc9cd00916e2303832f5';
const stable = (value) => Array.isArray(value)
  ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
    : value;
const canonical = (value) => JSON.stringify(stable(value));
const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const setHash = (ids) => sha256(`${[...ids].sort().join('\n')}\n`);
const CONTRACT = JSON.parse(readFileSync(new URL(
  '../../docs/m6/m6-pr-e-e3-r2b-scanner-identity-reconciliation.json',
  import.meta.url,
), 'utf8'));

function assertSet(label, ids, expected) {
  if (!expected
    || ids.length !== expected.findingCount
    || setHash(ids) !== expected.findingSetSha256) {
    throw new Error(`R2B ${label} identity-set drift`);
  }
}

function scannerIds(label, scanner, sourceClass) {
  if (!scanner
    || scanner.scanCompleted !== true
    || scanner.rawReportRetained !== false
    || !Array.isArray(scanner.findings)
    || scanner.findings.length !== scanner.findingCount) {
    throw new Error(`R2B complete current ${label} identity evidence required`);
  }
  const ids = scanner.findings.map((finding) => {
    if (finding.sourceClass !== sourceClass || !SHA64.test(finding.findingId || '')) {
      throw new Error(`R2B ${label} finding identity drift`);
    }
    return finding.findingId;
  }).sort();
  if (new Set(ids).size !== ids.length) {
    throw new Error(`R2B ${label} duplicate finding identity`);
  }
  return ids;
}

function validateContract() {
  const { contentSha256, ...payload } = CONTRACT;
  if (CONTRACT.schemaVersion !== 'M6_PR_E_E3_R2B_SCANNER_IDENTITY_RECONCILIATION_V1'
    || CONTRACT.repository !== 'akaryc1b/approval-platform'
    || contentSha256 !== CONTRACT_SHA256
    || sha256(canonical(payload)) !== contentSha256) {
    throw new Error('R2B scanner identity reconciliation contract mismatch');
  }
  return CONTRACT;
}

function reviewAdded(actual, expected) {
  if (!actual || actual.sourceClass !== 'E4_OSV_SCANNER') {
    throw new Error(`R2B reviewed OSV finding absent ${expected.findingId}`);
  }
  if (actual.upstreamFindingId !== expected.upstreamFindingId) {
    throw new Error(`R2B reviewed OSV upstream identity drift ${expected.findingId}`);
  }
  for (const key of ['ecosystem', 'name', 'version']) {
    if (actual.package?.[key] !== expected.package?.[key]) {
      throw new Error(`R2B reviewed OSV package drift ${expected.findingId} ${key}`);
    }
  }
  const aliases = new Set(actual.aliases || []);
  for (const alias of expected.requiredAliases || []) {
    if (!aliases.has(alias)) throw new Error(`R2B reviewed OSV alias drift ${expected.findingId}`);
  }
  const scopes = new Set(actual.scopes || []);
  for (const scope of expected.requiredScopes || []) {
    if (!scopes.has(scope)) throw new Error(`R2B reviewed OSV scope drift ${expected.findingId}`);
  }
  if (!Array.isArray(actual.componentRefs) || actual.componentRefs.length === 0
    || !['COMPILE_RUNTIME', 'BUILD_PLUGIN'].includes(expected.graphClass)
    || expected.disposition !== 'UNRESOLVED') {
    throw new Error(`R2B reviewed OSV disposition drift ${expected.findingId}`);
  }
  return stable({
    findingId: actual.findingId,
    upstreamFindingId: actual.upstreamFindingId,
    aliases: [...aliases].sort(),
    package: actual.package,
    componentRefs: [...actual.componentRefs].sort(),
    scopes: [...scopes].sort(),
    graphClass: expected.graphClass,
    disposition: 'UNRESOLVED',
  });
}

export function reconcileScannerFindingIdentities(e4) {
  const contract = validateContract();
  if (!e4 || e4.repository !== contract.repository) {
    throw new Error('R2B current E4 identity evidence required');
  }
  const accepted = contract.acceptedIdentitySets;
  const expected = contract.expectedCurrentIdentitySets;
  const ids = {
    osv: scannerIds('OSV', e4.scanners?.osv, accepted.osv.sourceClass),
    gitleaks: scannerIds('Gitleaks', e4.scanners?.gitleaks, accepted.gitleaks.sourceClass),
    semgrep: scannerIds('Semgrep', e4.scanners?.semgrep, accepted.semgrepCurrentAfterAcceptedIdentityTransition.sourceClass),
    zizmor: scannerIds('zizmor', e4.scanners?.zizmor, accepted.zizmorCurrent.sourceClass),
  };
  assertSet('current OSV', ids.osv, expected.osv);
  assertSet('current Gitleaks', ids.gitleaks, expected.gitleaks);
  assertSet('current Semgrep', ids.semgrep, expected.semgrep);
  assertSet('current zizmor', ids.zizmor, expected.zizmor);

  const byId = new Map(e4.scanners.osv.findings.map((finding) => [finding.findingId, finding]));
  const addedOsvFindings = contract.reviewedAddedOsvFindings
    .map((addition) => reviewAdded(byId.get(addition.findingId), addition));
  const addedIds = new Set(addedOsvFindings.map((finding) => finding.findingId));
  const retainedIds = ids.osv.filter((findingId) => !addedIds.has(findingId));
  assertSet('retained historical OSV', retainedIds, accepted.osv);
  if (retainedIds.length + addedOsvFindings.length !== ids.osv.length) {
    throw new Error('R2B unreviewed OSV identity addition detected');
  }
  const currentScannerCounts = stable({
    gitleaks: ids.gitleaks.length,
    osv: ids.osv.length,
    semgrep: ids.semgrep.length,
    zizmor: ids.zizmor.length,
  });
  const totalFindingCount = Object.values(currentScannerCounts).reduce((sum, count) => sum + count, 0);
  if (totalFindingCount !== expected.totalFindingCount || totalFindingCount !== e4.totalFindingCount) {
    throw new Error('R2B current scanner identity total drift');
  }
  return stable({
    schemaVersion: 'M6_PR_E_E3_R2B_SCANNER_IDENTITY_RECONCILIATION_EVIDENCE_V1',
    retainedHistoricalOsvFindingCount: retainedIds.length,
    retainedHistoricalOsvFindingSetSha256: setHash(retainedIds),
    addedOsvFindingCount: addedOsvFindings.length,
    addedOsvFindings,
    currentFindingSetSha256: {
      gitleaks: setHash(ids.gitleaks),
      osv: setHash(ids.osv),
      semgrep: setHash(ids.semgrep),
      zizmor: setHash(ids.zizmor),
    },
    currentScannerCounts,
    totalFindingCount,
    suppressionAdded: false,
    exceptionAdded: false,
    severityDowngradeAdded: false,
    findingDeletionClaimed: false,
    releaseBlocked: true,
  });
}

export function verifyWorkflowSupplyChainRemediation(e4, plan, snapshot) {
  const count = e4?.scanners?.osv?.findingCount;
  if (count === (plan?.priorNonZizmorFindingCounts?.osv ?? 115)) {
    return verifyAcceptedR2B(e4, plan, snapshot);
  }

  const r2bCount = CONTRACT.expectedCurrentIdentitySets.osv.findingCount;
  const scannerIdentityReconciliation = count === r2bCount
    ? reconcileScannerFindingIdentities(e4)
    : reconcileCurrentOsvIdentityDrift(e4);
  const reconciledPlan = structuredClone(plan);
  reconciledPlan.priorNonZizmorFindingCounts = {
    gitleaks: scannerIdentityReconciliation.currentScannerCounts.gitleaks,
    osv: scannerIdentityReconciliation.currentScannerCounts.osv,
    semgrep: scannerIdentityReconciliation.currentScannerCounts.semgrep,
  };
  const acceptedEvidence = verifyAcceptedR2B(e4, reconciledPlan, snapshot);
  const { contentSha256: ignored, ...acceptedPayload } = acceptedEvidence;
  const currentDrift = scannerIdentityReconciliation.schemaVersion
    === 'M6_PR_E_E3_R3C_OSV_IDENTITY_RECONCILIATION_EVIDENCE_V1';
  const payload = stable({
    ...acceptedPayload,
    scannerIdentityReconciliation,
    reasonCodes: [...new Set([
      ...(acceptedPayload.reasonCodes || []),
      'OSV_DATABASE_DRIFT_RECONCILED_BY_EXACT_IDENTITY_SET',
      currentDrift
        ? 'CURRENT_REVIEWED_OSV_ADDITIONS_RETAINED_UNRESOLVED'
        : 'TWO_NEW_OSV_FINDINGS_RETAINED_UNRESOLVED',
    ])],
  });
  return stable({ ...payload, contentSha256: sha256(canonical(payload)) });
}
