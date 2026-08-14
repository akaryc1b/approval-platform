import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test from 'node:test';

import {
  reconcileOsvIdentityDeltaWithContract,
} from '../security/m6-pr-e-e3-reconcile-current-osv-drift.mjs';

const contract = JSON.parse(readFileSync(new URL(
  '../../docs/m6/m6-pr-e-e3-r3c-osv-identity-reconciliation.json',
  import.meta.url,
), 'utf8'));
const source = readFileSync(new URL(
  '../security/m6-pr-e-e3-reconcile-current-osv-drift.mjs',
  import.meta.url,
), 'utf8');
const wrapper = readFileSync(new URL(
  '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs',
  import.meta.url,
), 'utf8');
const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const findingSetSha256 = (ids) => sha256(`${[...ids].sort().join('\n')}\n`);

function exactSyntheticFixture() {
  const retainedIds = Array.from({ length: 5 }, (_, index) => sha256(`retained-${index}`));
  const additions = contract.identityDelta.addedFindings.map((finding) => ({
    sourceClass: 'E4_OSV_SCANNER',
    findingId: finding.findingId,
    upstreamFindingId: finding.upstreamFindingId,
    aliases: [...finding.requiredAliases],
    package: { ...finding.package },
    componentRefs: [...finding.requiredComponentRefs],
    scopes: [...finding.requiredScopes],
    fixedVersions: [...finding.fixedVersionsObserved],
    upstreamSeverity: [...finding.upstreamSeverityObserved],
  }));
  const currentIds = [...retainedIds, ...additions.map((finding) => finding.findingId)].sort();
  const identityContract = {
    previousReviewedBoundary: {
      findingCount: retainedIds.length,
      findingSetSha256: findingSetSha256(retainedIds),
    },
    databaseDriftObservation: {
      scannerVersion: contract.databaseDriftObservation.scannerVersion,
      scannerSourceCommit: contract.databaseDriftObservation.scannerSourceCommit,
      scannerBinarySha256: contract.databaseDriftObservation.scannerBinarySha256,
    },
    identityDelta: {
      retainedFindingCount: retainedIds.length,
      retainedFindingSetSha256: findingSetSha256(retainedIds),
      addedFindings: contract.identityDelta.addedFindings,
      addedFindingCount: additions.length,
      removedFindingIds: [],
      removedFindingCount: 0,
    },
    currentOsvIdentitySet: {
      sourceClass: 'E4_OSV_SCANNER',
      findingCount: currentIds.length,
      findingSetSha256: findingSetSha256(currentIds),
    },
  };
  const retained = retainedIds.map((findingId) => ({
    sourceClass: 'E4_OSV_SCANNER',
    findingId,
  }));
  const scanner = {
    scanCompleted: true,
    rawReportRetained: false,
    findingCount: currentIds.length,
    findings: [...retained, ...additions],
    version: identityContract.databaseDriftObservation.scannerVersion,
    sourceCommit: identityContract.databaseDriftObservation.scannerSourceCommit,
    binarySha256: identityContract.databaseDriftObservation.scannerBinarySha256,
  };
  return { identityContract, scanner };
}

test('R3C contract retains 117 identities, adds exactly three and removes none', () => {
  assert.equal(contract.previousReviewedBoundary.findingCount, 117);
  assert.equal(contract.previousReviewedBoundary.findingSetSha256, '42d4ce93ce58eb76e07faa556d32c6b8f7feb1e9a3f3f600eab9c971c3fe5da6');
  assert.equal(contract.identityDelta.retainedFindingCount, 117);
  assert.equal(contract.identityDelta.addedFindingCount, 3);
  assert.equal(contract.identityDelta.removedFindingCount, 0);
  assert.equal(contract.currentOsvIdentitySet.findingCount, 120);
  assert.equal(contract.currentOsvIdentitySet.findingSetSha256, 'c1f9b73ce713bc09035ce34e7ad7d0b14329933f51dbd466aa0843e5066d1142');
  assert.equal(contract.expectedCurrentScannerCounts.total, 150);
  assert.deepEqual(contract.identityDelta.addedFindings.map((finding) => [
    finding.upstreamFindingId,
    finding.requiredAliases[0],
    finding.package.name,
    finding.package.version,
    finding.graphClass,
    finding.disposition,
  ]), [
    ['GHSA-v3jc-474w-2wm6', 'CVE-2026-54428', 'org.apache.httpcomponents.core5:httpcore5-h2', '5.3.6', 'BUILD_PLUGIN', 'UNRESOLVED'],
    ['GHSA-hjcp-jmpx-g3qm', 'CVE-2026-64607', 'org.apache.httpcomponents.client5:httpclient5', '5.5.2', 'BUILD_PLUGIN', 'UNRESOLVED'],
    ['GHSA-qv9r-c865-cp47', 'CVE-2026-49844', 'org.apache.logging.log4j:log4j-api', '2.25.3', 'COMPILE_RUNTIME', 'UNRESOLVED'],
  ]);
});

test('R3C exact OSV delta reconciliation is identity-based and fail closed', () => {
  const { identityContract, scanner } = exactSyntheticFixture();
  const evidence = reconcileOsvIdentityDeltaWithContract(scanner, identityContract);
  assert.equal(evidence.retainedFindingCount, 5);
  assert.equal(evidence.addedFindingCount, 3);
  assert.equal(evidence.removedFindingCount, 0);
  assert.equal(evidence.currentFindingCount, 8);
  assert.ok(evidence.addedFindings.every((finding) => finding.disposition === 'UNRESOLVED'));

  const missing = structuredClone(scanner);
  missing.findings.pop();
  missing.findingCount -= 1;
  assert.throws(
    () => reconcileOsvIdentityDeltaWithContract(missing, identityContract),
    /current OSV identity-set drift/,
  );

  const substituted = structuredClone(scanner);
  substituted.findings[0].findingId = '0'.repeat(64);
  assert.throws(
    () => reconcileOsvIdentityDeltaWithContract(substituted, identityContract),
    /current OSV identity-set drift/,
  );

  const scannerDrift = structuredClone(scanner);
  scannerDrift.sourceCommit = '0'.repeat(40);
  assert.throws(
    () => reconcileOsvIdentityDeltaWithContract(scannerDrift, identityContract),
    /scanner identity drift/,
  );
});

test('R3C preserves release blocking and contains no closure or suppression path', () => {
  for (const marker of [
    'M6_PR_E_E3_R3C_OSV_IDENTITY_RECONCILIATION_EVIDENCE_V1',
    'previousReviewedIdentitiesRetained',
    'onlyReviewedAdditionsPresent',
    'removedFindingIdentityAccepted',
    'releaseBlocked: true',
    'suppressionAdded: false',
    'exceptionAdded: false',
    'severityDowngradeAdded: false',
    'findingDeletionClaimed: false',
  ]) assert.ok(source.includes(marker), marker);
  assert.doesNotMatch(source, /suppressionAdded:\s*true|exceptionAdded:\s*true|severityDowngradeAdded:\s*true|findingDeletionClaimed:\s*true/);
  assert.equal(contract.decision.securityClosureAccepted, false);
  assert.equal(contract.decision.prb16Closed, false);
  assert.equal(contract.decision.prb17Closed, false);
  for (const marker of [
    'reconcileCurrentOsvIdentityDrift',
    'M6_PR_E_E3_R3C_OSV_IDENTITY_RECONCILIATION_EVIDENCE_V1',
    'CURRENT_REVIEWED_OSV_ADDITIONS_RETAINED_UNRESOLVED',
  ]) assert.ok(wrapper.includes(marker), marker);
});
