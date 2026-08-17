#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const SHA40 = /^[0-9a-f]{40}$/;
const SHA64 = /^[0-9a-f]{64}$/;
const CONTRACT_SHA256 = '5b4ed3731f05dd1ff134b83a2e9f724646fdb29ff6f799f824d55c8cb66d8cfd';
const stable = (value) => Array.isArray(value)
  ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
    : value;
const canonical = (value) => JSON.stringify(stable(value));
const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const setHash = (ids) => sha256(`${[...ids].sort().join('\n')}\n`);
const CONTRACT = JSON.parse(readFileSync(new URL(
  '../../docs/m6/m6-pr-e-e3-r3c-osv-identity-reconciliation.json',
  import.meta.url,
), 'utf8'));

function assertSet(label, ids, expected) {
  if (!expected
    || !Number.isInteger(expected.findingCount)
    || !SHA64.test(expected.findingSetSha256 || '')
    || ids.length !== expected.findingCount
    || setHash(ids) !== expected.findingSetSha256) {
    throw new Error(`R3C ${label} identity-set drift`);
  }
}

function scannerIds(label, scanner, sourceClass) {
  if (!scanner
    || scanner.scanCompleted !== true
    || scanner.rawReportRetained !== false
    || !Array.isArray(scanner.findings)
    || scanner.findings.length !== scanner.findingCount) {
    throw new Error(`R3C complete current ${label} identity evidence required`);
  }
  const ids = scanner.findings.map((finding) => {
    if (finding.sourceClass !== sourceClass || !SHA64.test(finding.findingId || '')) {
      throw new Error(`R3C ${label} finding identity drift`);
    }
    return finding.findingId;
  }).sort();
  if (new Set(ids).size !== ids.length) {
    throw new Error(`R3C ${label} duplicate finding identity`);
  }
  return ids;
}

function validateContract() {
  const { contentSha256, ...payload } = CONTRACT;
  if (CONTRACT.schemaVersion !== 'M6_PR_E_E3_R3C_OSV_IDENTITY_RECONCILIATION_V1'
    || CONTRACT.repository !== 'akaryc1b/approval-platform'
    || contentSha256 !== CONTRACT_SHA256
    || sha256(canonical(payload)) !== contentSha256) {
    throw new Error('R3C OSV identity reconciliation contract mismatch');
  }
  const prior = CONTRACT.previousReviewedBoundary;
  const observation = CONTRACT.databaseDriftObservation;
  if (prior.findingCount !== 117
    || prior.findingSetSha256 !== '42d4ce93ce58eb76e07faa556d32c6b8f7feb1e9a3f3f600eab9c971c3fe5da6'
    || observation.runId !== 31789303684
    || observation.runNumber !== 1462
    || observation.head !== 'fb50903f7d23a4f1e1c7cb184d6839e9d83e1669'
    || observation.jobId !== 94732360791
    || observation.artifactId !== 9214869318
    || observation.artifactSha256 !== '5b39de65a736afc285d3aa899882c6492fc2da74e50e8f67bee1fc4a05f87790'
    || observation.scannerVersion !== '2.5.0'
    || observation.generatedAt !== null
    || observation.databaseSnapshotIdentity !== null) {
    throw new Error('R3C OSV database drift observation mismatch');
  }
  for (const key of ['previousReviewedIdentitiesRetained', 'onlyReviewedAdditionsPresent']) {
    if (CONTRACT.invariants[key] !== true) throw new Error(`R3C invariant missing ${key}`);
  }
  for (const key of [
    'removedFindingIdentityAccepted',
    'suppressionAdded',
    'exceptionAdded',
    'severityDowngradeAdded',
    'findingDeletionClaimed',
    'readyAuthorized',
    'mergeAuthorized',
    'deploymentAuthorized',
    'productionPromotionAuthorized',
  ]) {
    if (CONTRACT.invariants[key] !== false) throw new Error(`R3C invariant violated ${key}`);
  }
  if (CONTRACT.decision.addedFindingsDisposition !== 'UNRESOLVED'
    || CONTRACT.decision.releaseBlocked !== true
    || CONTRACT.decision.securityClosureAccepted !== false
    || CONTRACT.decision.prb16Closed !== false
    || CONTRACT.decision.prb17Closed !== false) {
    throw new Error('R3C decision boundary mismatch');
  }
  return CONTRACT;
}

function reviewAdded(actual, expected) {
  if (!actual || actual.sourceClass !== 'E4_OSV_SCANNER') {
    throw new Error(`R3C reviewed OSV finding absent ${expected.findingId}`);
  }
  if (actual.upstreamFindingId !== expected.upstreamFindingId) {
    throw new Error(`R3C reviewed OSV upstream identity drift ${expected.findingId}`);
  }
  for (const key of ['ecosystem', 'name', 'version']) {
    if (actual.package?.[key] !== expected.package?.[key]) {
      throw new Error(`R3C reviewed OSV package drift ${expected.findingId} ${key}`);
    }
  }
  for (const [label, actualValues, expectedValues] of [
    ['alias', actual.aliases, expected.requiredAliases],
    ['scope', actual.scopes, expected.requiredScopes],
    ['component reference', actual.componentRefs, expected.requiredComponentRefs],
  ]) {
    const values = new Set(actualValues || []);
    for (const expectedValue of expectedValues || []) {
      if (!values.has(expectedValue)) {
        throw new Error(`R3C reviewed OSV ${label} drift ${expected.findingId}`);
      }
    }
  }
  if (!['COMPILE_RUNTIME', 'BUILD_PLUGIN'].includes(expected.graphClass)
    || expected.disposition !== 'UNRESOLVED'
    || expected.releaseBlocking !== true) {
    throw new Error(`R3C reviewed OSV disposition contract drift ${expected.findingId}`);
  }
  return stable({
    findingId: actual.findingId,
    upstreamFindingId: actual.upstreamFindingId,
    aliases: [...(actual.aliases || [])].sort(),
    package: actual.package,
    componentRefs: [...(actual.componentRefs || [])].sort(),
    scopes: [...(actual.scopes || [])].sort(),
    graphClass: expected.graphClass,
    disposition: 'UNRESOLVED',
    releaseBlocking: true,
  });
}

export function reconcileOsvIdentityDeltaWithContract(scanner, identityContract) {
  const current = identityContract.currentOsvIdentitySet;
  const ids = scannerIds('OSV', scanner, current.sourceClass);
  assertSet('current OSV', ids, current);
  const observation = identityContract.databaseDriftObservation;
  if (scanner.version !== observation.scannerVersion
    || scanner.sourceCommit !== observation.scannerSourceCommit
    || scanner.binarySha256 !== observation.scannerBinarySha256) {
    throw new Error('R3C OSV scanner identity drift');
  }
  const byId = new Map(scanner.findings.map((finding) => [finding.findingId, finding]));
  const addedFindings = identityContract.identityDelta.addedFindings
    .map((expected) => reviewAdded(byId.get(expected.findingId), expected));
  const addedIds = new Set(addedFindings.map((finding) => finding.findingId));
  const retainedIds = ids.filter((findingId) => !addedIds.has(findingId));
  assertSet('retained prior OSV', retainedIds, {
    findingCount: identityContract.identityDelta.retainedFindingCount,
    findingSetSha256: identityContract.identityDelta.retainedFindingSetSha256,
  });
  if (retainedIds.length + addedFindings.length !== ids.length) {
    throw new Error('R3C unreviewed OSV identity addition detected');
  }
  return stable({
    previousReviewedFindingCount: identityContract.previousReviewedBoundary.findingCount,
    previousReviewedFindingSetSha256: identityContract.previousReviewedBoundary.findingSetSha256,
    retainedFindingCount: retainedIds.length,
    retainedFindingSetSha256: setHash(retainedIds),
    addedFindingCount: addedFindings.length,
    addedFindings,
    removedFindingCount: identityContract.identityDelta.removedFindingCount,
    removedFindingIds: identityContract.identityDelta.removedFindingIds,
    currentFindingCount: ids.length,
    currentFindingSetSha256: setHash(ids),
    scanner: {
      binarySha256: scanner.binarySha256,
      sourceCommit: scanner.sourceCommit,
      version: scanner.version,
    },
  });
}

export function reconcileOsvIdentityDelta(scanner) {
  return reconcileOsvIdentityDeltaWithContract(scanner, validateContract());
}

export function reconcileCurrentOsvIdentityDrift(e4) {
  const identityContract = validateContract();
  if (!e4
    || e4.repository !== identityContract.repository
    || !SHA40.test(e4.commitSha || '')) {
    throw new Error('R3C current E4 identity evidence required');
  }
  const osvIdentityDelta = reconcileOsvIdentityDelta(e4.scanners?.osv);
  const unchanged = identityContract.unchangedIdentitySets;
  const currentIds = {
    gitleaks: scannerIds('Gitleaks', e4.scanners?.gitleaks, unchanged.gitleaks.sourceClass),
    semgrep: scannerIds('Semgrep', e4.scanners?.semgrep, unchanged.semgrep.sourceClass),
    zizmor: scannerIds('zizmor', e4.scanners?.zizmor, unchanged.zizmor.sourceClass),
  };
  for (const scanner of Object.keys(currentIds)) {
    assertSet(`current ${scanner}`, currentIds[scanner], unchanged[scanner]);
  }
  const currentScannerCounts = stable({
    gitleaks: currentIds.gitleaks.length,
    osv: osvIdentityDelta.currentFindingCount,
    semgrep: currentIds.semgrep.length,
    zizmor: currentIds.zizmor.length,
  });
  const totalFindingCount = Object.values(currentScannerCounts).reduce((sum, count) => sum + count, 0);
  const expected = identityContract.expectedCurrentScannerCounts;
  for (const [scanner, count] of Object.entries(currentScannerCounts)) {
    if (count !== expected[scanner]) throw new Error(`R3C ${scanner} scanner count drift`);
  }
  if (totalFindingCount !== expected.total || totalFindingCount !== e4.totalFindingCount) {
    throw new Error('R3C current scanner identity total drift');
  }
  return stable({
    schemaVersion: 'M6_PR_E_E3_R3C_OSV_IDENTITY_RECONCILIATION_EVIDENCE_V1',
    contractContentSha256: identityContract.contentSha256,
    databaseDriftObservation: identityContract.databaseDriftObservation,
    osvIdentityDelta,
    currentFindingSetSha256: {
      gitleaks: setHash(currentIds.gitleaks),
      osv: osvIdentityDelta.currentFindingSetSha256,
      semgrep: setHash(currentIds.semgrep),
      zizmor: setHash(currentIds.zizmor),
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
