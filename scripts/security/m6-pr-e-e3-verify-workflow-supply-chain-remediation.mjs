#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

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
const findingSetSha256 = (ids) => sha256(`${[...ids].sort().join('\n')}\n`);
const contract = JSON.parse(readFileSync(
  new URL('../../docs/m6/m6-pr-e-e3-r2b-scanner-identity-reconciliation.json', import.meta.url),
  'utf8',
));

function requireContract() {
  if (contract.schemaVersion !== 'M6_PR_E_E3_R2B_SCANNER_IDENTITY_RECONCILIATION_V1') {
    throw new Error('R2B scanner identity reconciliation schema mismatch');
  }
  const { contentSha256, ...payload } = contract;
  if (contentSha256 !== CONTRACT_SHA256 || sha256(canonical(payload)) !== contentSha256) {
    throw new Error('R2B scanner identity reconciliation contract mismatch');
  }
  if (contract.repository !== 'akaryc1b/approval-platform') throw new Error('R2B scanner identity repository mismatch');
  const accepted = contract.acceptedEvidence || {};
  if (accepted.runId !== 31556788011
    || accepted.runNumber !== 1431
    || accepted.head !== '05f422b4cdab397fc1126e6dc10f571b01cec8c5'
    || accepted.e4CanonicalSha256 !== '4e86049fd18fbfebd7397d0c131563e849064eb698baa66bc4f9bd2d9cffbf58') {
    throw new Error('R2B accepted scanner identity evidence drift');
  }
  const observed = contract.databaseDriftObservation || {};
  if (observed.runId !== 31658751966
    || observed.runNumber !== 1436
    || observed.head !== '869d49cb7e9ef45109d3ac79f804908e5c58451a'
    || observed.scanner !== 'OSV-Scanner'
    || observed.scannerVersion !== '2.5.0'
    || canonical(observed.classification) !== canonical(['DATABASE_DRIFT', 'NEW_FINDING', 'EVIDENCE_BUG'])) {
    throw new Error('R2B scanner database drift observation mismatch');
  }
  const invariants = contract.invariants || {};
  for (const key of [
    'allHistoricalOsvIdentitiesRetained',
    'onlyReviewedOsvIdentitiesMayBeAdded',
    'gitleaksIdentitySetMustRemainExact',
    'semgrepIdentityTransitionMustRemainExact',
    'zizmorMustRemainEmpty',
  ]) if (invariants[key] !== true) throw new Error(`R2B scanner identity invariant missing ${key}`);
  for (const key of [
    'suppressionAdded',
    'exceptionAdded',
    'severityDowngradeAdded',
    'findingDeletionClaimed',
    'readyAuthorized',
    'mergeAuthorized',
    'deploymentAuthorized',
  ]) if (invariants[key] !== false) throw new Error(`R2B scanner identity invariant violated ${key}`);
  return contract;
}

function scannerIds(scannerName, scanner, sourceClass) {
  if (!scanner
    || scanner.scanCompleted !== true
    || scanner.rawReportRetained !== false
    || !Array.isArray(scanner.findings)
    || scanner.findings.length !== scanner.findingCount) {
    throw new Error(`R2B complete current ${scannerName} identity evidence required`);
  }
  const ids = scanner.findings.map((finding) => {
    if (finding.sourceClass !== sourceClass || !SHA64.test(finding.findingId || '')) {
      throw new Error(`R2B ${scannerName} finding identity drift`);
    }
    return finding.findingId;
  });
  if (new Set(ids).size !== ids.length) throw new Error(`R2B ${scannerName} duplicate finding identity`);
  return ids.sort();
}

function requireIdentitySet(label, ids, expected) {
  if (!expected
    || !Number.isInteger(expected.findingCount)
    || !SHA64.test(expected.findingSetSha256 || '')
    || ids.length !== expected.findingCount
    || findingSetSha256(ids) !== expected.findingSetSha256) {
    throw new Error(`R2B ${label} identity-set drift`);
  }
}

function requireAddedOsvFinding(actual, expected) {
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
    if (!aliases.has(alias)) throw new Error(`R2B reviewed OSV alias drift ${expected.findingId} ${alias}`);
  }
  const scopes = new Set(actual.scopes || []);
  for (const scope of expected.requiredScopes || []) {
    if (!scopes.has(scope)) throw new Error(`R2B reviewed OSV scope drift ${expected.findingId} ${scope}`);
  }
  if (!Array.isArray(actual.componentRefs) || actual.componentRefs.length === 0) {
    throw new Error(`R2B reviewed OSV component reference absent ${expected.findingId}`);
  }
  if (!['COMPILE_RUNTIME', 'BUILD_PLUGIN'].includes(expected.graphClass) || expected.disposition !== 'UNRESOLVED') {
    throw new Error(`R2B reviewed OSV disposition contract drift ${expected.findingId}`);
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
  const identityContract = requireContract();
  if (!e4 || e4.repository !== identityContract.repository) throw new Error('R2B current E4 identity evidence required');

  const accepted = identityContract.acceptedIdentitySets;
  const expectedCurrent = identityContract.expectedCurrentIdentitySets;
  const osvIds = scannerIds('osv', e4.scanners?.osv, accepted.osv.sourceClass);
  const gitleaksIds = scannerIds('gitleaks', e4.scanners?.gitleaks, accepted.gitleaks.sourceClass);
  const semgrepIds = scannerIds('semgrep', e4.scanners?.semgrep, accepted.semgrepCurrentAfterAcceptedIdentityTransition.sourceClass);
  const zizmorIds = scannerIds('zizmor', e4.scanners?.zizmor, accepted.zizmorCurrent.sourceClass);

  requireIdentitySet('current OSV', osvIds, expectedCurrent.osv);
  requireIdentitySet('current Gitleaks', gitleaksIds, expectedCurrent.gitleaks);
  requireIdentitySet('current Semgrep', semgrepIds, expectedCurrent.semgrep);
  requireIdentitySet('current zizmor', zizmorIds, expectedCurrent.zizmor);
  requireIdentitySet('accepted Gitleaks', gitleaksIds, accepted.gitleaks);
  requireIdentitySet('accepted current Semgrep transition', semgrepIds, accepted.semgrepCurrentAfterAcceptedIdentityTransition);
  requireIdentitySet('accepted current zizmor', zizmorIds, accepted.zizmorCurrent);

  const additions = identityContract.reviewedAddedOsvFindings || [];
  if (additions.length !== 2 || new Set(additions.map((finding) => finding.findingId)).size !== 2) {
    throw new Error('R2B exactly two reviewed OSV additions required');
  }
  const currentOsvById = new Map(e4.scanners.osv.findings.map((finding) => [finding.findingId, finding]));
  const reviewedAddedOsvFindings = additions.map((expected) => {
    if (!SHA64.test(expected.findingId || '')) throw new Error('R2B reviewed OSV finding identity invalid');
    return requireAddedOsvFinding(currentOsvById.get(expected.findingId), expected);
  });
  const additionIds = new Set(additions.map((finding) => finding.findingId));
  const retainedHistoricalOsvIds = osvIds.filter((findingId) => !additionIds.has(findingId));
  requireIdentitySet('retained historical OSV', retainedHistoricalOsvIds, accepted.osv);
  if (retainedHistoricalOsvIds.length + additions.length !== osvIds.length) {
    throw new Error('R2B unreviewed OSV identity addition detected');
  }

  const currentScannerCounts = stable({
    gitleaks: gitleaksIds.length,
    osv: osvIds.length,
    semgrep: semgrepIds.length,
    zizmor: zizmorIds.length,
  });
  const totalFindingCount = Object.values(currentScannerCounts).reduce((sum, count) => sum + count, 0);
  if (totalFindingCount !== expectedCurrent.totalFindingCount || totalFindingCount !== e4.totalFindingCount) {
    throw new Error('R2B current scanner identity total drift');
  }

  return stable({
    schemaVersion: 'M6_PR_E_E3_R2B_SCANNER_IDENTITY_RECONCILIATION_EVIDENCE_V1',
    contractContentSha256: identityContract.contentSha256,
    acceptedE4CanonicalSha256: identityContract.acceptedEvidence.e4CanonicalSha256,
    databaseDriftRunId: identityContract.databaseDriftObservation.runId,
    databaseSnapshotIdentity: identityContract.databaseDriftObservation.databaseSnapshotIdentity,
    databaseSnapshotIdentityAvailability: identityContract.databaseDriftObservation.databaseSnapshotIdentityAvailability,
    retainedHistoricalOsvFindingCount: retainedHistoricalOsvIds.length,
    retainedHistoricalOsvFindingSetSha256: findingSetSha256(retainedHistoricalOsvIds),
    addedOsvFindingCount: reviewedAddedOsvFindings.length,
    addedOsvFindings: reviewedAddedOsvFindings,
    currentFindingSetSha256: {
      gitleaks: findingSetSha256(gitleaksIds),
      osv: findingSetSha256(osvIds),
      semgrep: findingSetSha256(semgrepIds),
      zizmor: findingSetSha256(zizmorIds),
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
  const acceptedOsvCount = plan?.priorNonZizmorFindingCounts?.osv ?? 115;
  if (e4?.scanners?.osv?.findingCount === acceptedOsvCount) {
    return verifyAcceptedR2B(e4, plan, snapshot);
  }

  const scannerIdentityReconciliation = reconcileScannerFindingIdentities(e4);
  const reconciledPlan = structuredClone(plan);
  reconciledPlan.priorNonZizmorFindingCounts = {
    gitleaks: scannerIdentityReconciliation.currentScannerCounts.gitleaks,
    osv: scannerIdentityReconciliation.currentScannerCounts.osv,
    semgrep: scannerIdentityReconciliation.currentScannerCounts.semgrep,
  };
  const acceptedEvidence = verifyAcceptedR2B(e4, reconciledPlan, snapshot);
  const { contentSha256: ignored, ...acceptedPayload } = acceptedEvidence;
  const payload = stable({
    ...acceptedPayload,
    scannerIdentityReconciliation,
    reasonCodes: [
      ...new Set([
        ...(acceptedPayload.reasonCodes || []),
        'OSV_DATABASE_DRIFT_RECONCILED_BY_EXACT_IDENTITY_SET',
        'TWO_NEW_OSV_FINDINGS_RETAINED_UNRESOLVED',
      ]),
    ],
  });
  return stable({ ...payload, contentSha256: sha256(canonical(payload)) });
}
