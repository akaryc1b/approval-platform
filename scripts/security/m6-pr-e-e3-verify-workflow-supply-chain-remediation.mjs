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

function identitySetMatches(ids, expected) {
  return Boolean(expected
    && Number.isInteger(expected.findingCount)
    && SHA64.test(expected.findingSetSha256 || '')
    && ids.length === expected.findingCount
    && findingSetSha256(ids) === expected.findingSetSha256);
}

function requireIdentitySet(label, ids, expected) {
  if (!identitySetMatches(ids, expected)) throw new Error(`R2B ${label} identity-set drift`);
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

function currentOsvFindingProjection(finding) {
  if (!finding.upstreamFindingId
    || !finding.package?.ecosystem
    || !finding.package?.name
    || !finding.package?.version
    || !Array.isArray(finding.aliases)
    || !Array.isArray(finding.componentRefs)
    || finding.componentRefs.length === 0
    || !Array.isArray(finding.scopes)
    || finding.scopes.length === 0
    || !Array.isArray(finding.upstreamSeverity)
    || !Array.isArray(finding.fixedVersions)) {
    throw new Error(`R2B current OSV triage evidence incomplete ${finding.findingId}`);
  }
  const upstreamSeverity = finding.upstreamSeverity
    .map((item) => stable(item))
    .sort((left, right) => canonical(left).localeCompare(canonical(right)));
  return stable({
    findingId: finding.findingId,
    upstreamFindingId: finding.upstreamFindingId,
    aliases: [...new Set(finding.aliases)].sort(),
    package: finding.package,
    componentRefs: [...new Set(finding.componentRefs)].sort(),
    scopes: [...new Set(finding.scopes)].sort(),
    upstreamSeverity,
    fixedVersions: [...new Set(finding.fixedVersions)].sort(),
    disposition: 'UNRESOLVED',
  });
}

export function classifyCurrentOsvIdentitySet(scanner, identityContract = contract) {
  const accepted = identityContract.acceptedIdentitySets;
  const expectedCurrent = identityContract.expectedCurrentIdentitySets;
  const osvIds = scannerIds('osv', scanner, accepted.osv.sourceClass);
  const acceptedOsvIdentitySetMatched = identitySetMatches(osvIds, accepted.osv);
  const reviewedCurrentOsvIdentitySetMatched = identitySetMatches(osvIds, expectedCurrent.osv);
  let osvIdentityMode = 'UNREVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET';
  let retainedHistoricalOsvIds = [];
  let reviewedAddedOsvFindings = [];

  if (acceptedOsvIdentitySetMatched) {
    osvIdentityMode = 'ACCEPTED_HISTORICAL_OSV_IDENTITY_SET';
    retainedHistoricalOsvIds = osvIds;
  } else if (reviewedCurrentOsvIdentitySetMatched) {
    osvIdentityMode = 'REVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET';
    const additions = identityContract.reviewedAddedOsvFindings || [];
    if (additions.length !== 2 || new Set(additions.map((finding) => finding.findingId)).size !== 2) {
      throw new Error('R2B exactly two reviewed OSV additions required');
    }
    const currentOsvById = new Map(scanner.findings.map((finding) => [finding.findingId, finding]));
    reviewedAddedOsvFindings = additions.map((expected) => {
      if (!SHA64.test(expected.findingId || '')) throw new Error('R2B reviewed OSV finding identity invalid');
      return requireAddedOsvFinding(currentOsvById.get(expected.findingId), expected);
    });
    const additionIds = new Set(additions.map((finding) => finding.findingId));
    retainedHistoricalOsvIds = osvIds.filter((findingId) => !additionIds.has(findingId));
    requireIdentitySet('retained historical OSV', retainedHistoricalOsvIds, accepted.osv);
    if (retainedHistoricalOsvIds.length + additions.length !== osvIds.length) {
      throw new Error('R2B unreviewed OSV identity addition detected');
    }
  }

  const unreviewedCurrentOsvFindings = osvIdentityMode === 'UNREVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET'
    ? scanner.findings
      .map(currentOsvFindingProjection)
      .sort((left, right) => left.findingId.localeCompare(right.findingId))
    : [];
  return stable({
    osvIdentityMode,
    acceptedOsvIdentitySetUnchanged: true,
    acceptedOsvIdentitySetMatched,
    reviewedCurrentOsvIdentitySetMatched,
    currentOsvFindingReviewRequired: osvIdentityMode === 'UNREVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET',
    currentOsvFindingCount: osvIds.length,
    currentOsvFindingSetSha256: findingSetSha256(osvIds),
    retainedHistoricalOsvFindingCount: retainedHistoricalOsvIds.length,
    retainedHistoricalOsvFindingSetSha256: retainedHistoricalOsvIds.length
      ? findingSetSha256(retainedHistoricalOsvIds)
      : null,
    addedOsvFindingCount: reviewedAddedOsvFindings.length,
    addedOsvFindings: reviewedAddedOsvFindings,
    unreviewedCurrentOsvFindingCount: unreviewedCurrentOsvFindings.length,
    unreviewedCurrentOsvFindings,
  });
}

export function reconcileScannerFindingIdentities(e4) {
  const identityContract = requireContract();
  if (!e4 || e4.repository !== identityContract.repository) throw new Error('R2B current E4 identity evidence required');

  const accepted = identityContract.acceptedIdentitySets;
  const expectedCurrent = identityContract.expectedCurrentIdentitySets;
  const osvReconciliation = classifyCurrentOsvIdentitySet(e4.scanners?.osv, identityContract);
  const gitleaksIds = scannerIds('gitleaks', e4.scanners?.gitleaks, accepted.gitleaks.sourceClass);
  const semgrepIds = scannerIds('semgrep', e4.scanners?.semgrep, accepted.semgrepCurrentAfterAcceptedIdentityTransition.sourceClass);
  const zizmorIds = scannerIds('zizmor', e4.scanners?.zizmor, accepted.zizmorCurrent.sourceClass);

  requireIdentitySet('current Gitleaks', gitleaksIds, expectedCurrent.gitleaks);
  requireIdentitySet('current Semgrep', semgrepIds, expectedCurrent.semgrep);
  requireIdentitySet('current zizmor', zizmorIds, expectedCurrent.zizmor);
  requireIdentitySet('accepted Gitleaks', gitleaksIds, accepted.gitleaks);
  requireIdentitySet('accepted current Semgrep transition', semgrepIds, accepted.semgrepCurrentAfterAcceptedIdentityTransition);
  requireIdentitySet('accepted current zizmor', zizmorIds, accepted.zizmorCurrent);

  const currentScannerCounts = stable({
    gitleaks: gitleaksIds.length,
    osv: osvReconciliation.currentOsvFindingCount,
    semgrep: semgrepIds.length,
    zizmor: zizmorIds.length,
  });
  const totalFindingCount = Object.values(currentScannerCounts).reduce((sum, count) => sum + count, 0);
  if (totalFindingCount !== e4.totalFindingCount) throw new Error('R2B current scanner identity total drift');

  return stable({
    schemaVersion: 'M6_PR_E_E3_R2B_SCANNER_IDENTITY_RECONCILIATION_EVIDENCE_V2',
    contractContentSha256: identityContract.contentSha256,
    acceptedE4CanonicalSha256: identityContract.acceptedEvidence.e4CanonicalSha256,
    databaseDriftRunId: identityContract.databaseDriftObservation.runId,
    databaseSnapshotIdentity: identityContract.databaseDriftObservation.databaseSnapshotIdentity,
    databaseSnapshotIdentityAvailability: identityContract.databaseDriftObservation.databaseSnapshotIdentityAvailability,
    ...osvReconciliation,
    currentFindingSetSha256: {
      gitleaks: findingSetSha256(gitleaksIds),
      osv: osvReconciliation.currentOsvFindingSetSha256,
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
  const scannerIdentityReconciliation = reconcileScannerFindingIdentities(e4);
  if (scannerIdentityReconciliation.osvIdentityMode === 'ACCEPTED_HISTORICAL_OSV_IDENTITY_SET') {
    return verifyAcceptedR2B(e4, plan, snapshot);
  }

  const reconciledPlan = structuredClone(plan);
  reconciledPlan.priorNonZizmorFindingCounts = {
    gitleaks: scannerIdentityReconciliation.currentScannerCounts.gitleaks,
    osv: scannerIdentityReconciliation.currentScannerCounts.osv,
    semgrep: scannerIdentityReconciliation.currentScannerCounts.semgrep,
  };
  const acceptedEvidence = verifyAcceptedR2B(e4, reconciledPlan, snapshot);
  const { contentSha256: ignored, ...acceptedPayload } = acceptedEvidence;
  const reviewedIdentitySet = scannerIdentityReconciliation.osvIdentityMode === 'REVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET';
  const payload = stable({
    ...acceptedPayload,
    scannerIdentityReconciliation,
    reasonCodes: [
      ...new Set([
        ...(acceptedPayload.reasonCodes || []),
        reviewedIdentitySet
          ? 'OSV_DATABASE_DRIFT_RECONCILED_BY_EXACT_IDENTITY_SET'
          : 'OSV_DATABASE_DRIFT_RETAINED_WITHOUT_ACCEPTANCE',
        reviewedIdentitySet
          ? 'TWO_NEW_OSV_FINDINGS_RETAINED_UNRESOLVED'
          : 'CURRENT_OSV_IDENTITY_SET_REQUIRES_E3_REVIEW',
        ...(!reviewedIdentitySet ? ['OSV_DATABASE_SNAPSHOT_IDENTITY_UNAVAILABLE'] : []),
      ]),
    ],
  });
  return stable({ ...payload, contentSha256: sha256(canonical(payload)) });
}
