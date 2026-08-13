import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { reconcileScannerFindingIdentities } from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const contractPath = path.join(root, 'docs/m6/m6-pr-e-e3-r2b-scanner-identity-reconciliation.json');
const verifierPath = path.join(root, 'scripts/security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs');
const acceptedVerifierPath = path.join(root, 'scripts/security/m6-pr-e-e3-verify-workflow-supply-chain-remediation-accepted.mjs');
const acceptedTestPath = path.join(root, 'scripts/tests/m6-pr-e-e3-r2b-workflow-supply-chain-remediation-boundary-accepted.test.mjs');
const contract = JSON.parse(readFileSync(contractPath, 'utf8'));
const stable = (value) => Array.isArray(value)
  ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
    : value;
const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const gitBlobSha = (value) => createHash('sha1')
  .update(`blob ${Buffer.byteLength(value)}\0`)
  .update(value)
  .digest('hex');
const id = (label) => sha256(label);
const finding = (sourceClass, findingId, extra = {}) => ({ sourceClass, findingId, ...extra });

function invalidCurrentE4() {
  const additions = contract.reviewedAddedOsvFindings.map((expected) => finding('E4_OSV_SCANNER', expected.findingId, {
    upstreamFindingId: expected.upstreamFindingId,
    aliases: [...expected.requiredAliases],
    package: { ...expected.package },
    componentRefs: [`fixture:${expected.package.name}:${expected.package.version}`],
    scopes: [...expected.requiredScopes],
    upstreamSeverity: [],
    fixedVersions: [],
  }));
  const osv = [
    ...Array.from({ length: 115 }, (_, index) => finding('E4_OSV_SCANNER', id(`unreviewed-osv-${index}`))),
    ...additions,
  ];
  const gitleaks = Array.from({ length: 27 }, (_, index) => finding('E4_GITLEAKS', id(`unreviewed-gitleaks-${index}`)));
  const semgrep = Array.from({ length: 3 }, (_, index) => finding('E4_SEMGREP', id(`unreviewed-semgrep-${index}`)));
  return {
    repository: contract.repository,
    commitSha: '7'.repeat(40),
    contentSha256: '8'.repeat(64),
    totalFindingCount: 147,
    scanners: {
      osv: { scanCompleted: true, rawReportRetained: false, findingCount: osv.length, findings: osv },
      gitleaks: { scanCompleted: true, rawReportRetained: false, findingCount: gitleaks.length, findings: gitleaks },
      semgrep: { scanCompleted: true, rawReportRetained: false, findingCount: semgrep.length, findings: semgrep },
      zizmor: { scanCompleted: true, rawReportRetained: false, findingCount: 0, findings: [] },
    },
  };
}

test('R2B scanner identity contract is canonical, exact and non-authorizing', () => {
  const { contentSha256, ...payload } = contract;
  assert.equal(sha256(JSON.stringify(stable(payload))), contentSha256);
  assert.equal(contentSha256, 'f317b8f6568100c5da132a46c9cd4162851c60dfc36dbc9cd00916e2303832f5');
  assert.deepEqual(contract.acceptedEvidence, {
    runId: 31556788011,
    runNumber: 1431,
    head: '05f422b4cdab397fc1126e6dc10f571b01cec8c5',
    e4CanonicalSha256: '4e86049fd18fbfebd7397d0c131563e849064eb698baa66bc4f9bd2d9cffbf58',
  });
  assert.equal(contract.acceptedIdentitySets.osv.findingCount, 115);
  assert.equal(contract.acceptedIdentitySets.osv.findingSetSha256, '7340d8246d377669fc309031cb3557bfa40d71fbf37411b964639a256003fa72');
  assert.equal(contract.expectedCurrentIdentitySets.osv.findingCount, 117);
  assert.equal(contract.expectedCurrentIdentitySets.osv.findingSetSha256, '42d4ce93ce58eb76e07faa556d32c6b8f7feb1e9a3f3f600eab9c971c3fe5da6');
  assert.equal(contract.expectedCurrentIdentitySets.totalFindingCount, 147);
  assert.deepEqual(contract.reviewedAddedOsvFindings.map((item) => [
    item.findingId,
    item.upstreamFindingId,
    item.requiredAliases[0],
    item.package.name,
    item.package.version,
    item.requiredScopes[0],
    item.graphClass,
    item.disposition,
  ]), [
    ['e6addd49c1ea8eddec10d654318f6cb52f458e139900b8e612c59fbc690b0b79', 'GHSA-x4m4-345f-5h5g', 'CVE-2026-34487', 'org.apache.tomcat.embed:tomcat-embed-core', '11.0.15', 'compile', 'COMPILE_RUNTIME', 'UNRESOLVED'],
    ['35fb6917af4dcd633580647f3c9ffc2d2bd5e97f296634c1d8f3515a40b8413e', 'GHSA-hf6x-8p5f-cgmf', 'CVE-2026-54399', 'org.apache.httpcomponents.core5:httpcore5', '5.3.6', 'build-plugin', 'BUILD_PLUGIN', 'UNRESOLVED'],
  ]);
  for (const key of ['suppressionAdded', 'exceptionAdded', 'severityDowngradeAdded', 'findingDeletionClaimed', 'readyAuthorized', 'mergeAuthorized', 'deploymentAuthorized']) {
    assert.equal(contract.invariants[key], false, key);
  }
});

test('R2B preserves accepted boundary coverage while removing tainted dynamic RegExp construction', () => {
  assert.equal(existsSync(acceptedVerifierPath), true);
  assert.equal(existsSync(acceptedTestPath), true);
  assert.equal(gitBlobSha(readFileSync(acceptedTestPath)), '34e46091ab35540cfc57fe3e8c00e6692e93e56d');
  const acceptedVerifier = readFileSync(acceptedVerifierPath, 'utf8');
  assert.doesNotMatch(acceptedVerifier, /new RegExp\(/);
  assert.ok(acceptedVerifier.includes('candidate.match(/^(\\s*)([A-Za-z0-9_-]+):\\s*(.*)$/)'));
  assert.ok(acceptedVerifier.includes('field[1].length === stepIndent + 2'));
  assert.ok(acceptedVerifier.includes('fields.set(field[2], field[3])'));
  const verifier = readFileSync(verifierPath, 'utf8');
  for (const marker of [
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
  ]) assert.ok(verifier.includes(marker), marker);
  assert.doesNotMatch(verifier, /suppressionAdded:\s*true|exceptionAdded:\s*true|severityDowngradeAdded:\s*true|findingDeletionClaimed:\s*true/);
});

test('R2B rejects a count-correct scanner result whose identities are not the accepted historical set', () => {
  assert.throws(() => reconcileScannerFindingIdentities(invalidCurrentE4()), /current OSV identity-set drift/);
});

test('R2B rejects duplicate scanner identities before any count-based compatibility delegation', () => {
  const e4 = invalidCurrentE4();
  e4.scanners.osv.findings[1].findingId = e4.scanners.osv.findings[0].findingId;
  assert.throws(() => reconcileScannerFindingIdentities(e4), /duplicate finding identity/);
});
