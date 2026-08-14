import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  classifyCurrentOsvIdentitySet,
  reconcileScannerFindingIdentities,
} from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const contractPath = path.join(root, 'docs/m6/m6-pr-e-e3-r2b-scanner-identity-reconciliation.json');
const finalAcceptancePath = path.join(root, 'docs/m6/M6_PR_E_E3_R2B_FINAL_ACCEPTANCE.md');
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

function osvFinding(label, overrides = {}) {
  return finding('E4_OSV_SCANNER', overrides.findingId ?? id(label), {
    upstreamFindingId: overrides.upstreamFindingId ?? `GHSA-fixture-${label}`,
    aliases: overrides.aliases ?? [`CVE-2099-${label}`],
    package: overrides.package ?? {
      ecosystem: 'Maven',
      name: `fixture:${label}`,
      version: '1.0.0',
    },
    componentRefs: overrides.componentRefs ?? [`pkg:maven/fixture/${label}@1.0.0?type=jar`],
    scopes: overrides.scopes ?? ['compile'],
    upstreamSeverity: overrides.upstreamSeverity ?? [],
    fixedVersions: overrides.fixedVersions ?? [],
  });
}

function unreviewedCurrentE4(osvCount = 117) {
  const osv = Array.from({ length: osvCount }, (_, index) => osvFinding(`unreviewed-osv-${index}`));
  const gitleaks = Array.from({ length: 27 }, (_, index) => finding('E4_GITLEAKS', id(`unreviewed-gitleaks-${index}`)));
  const semgrep = Array.from({ length: 3 }, (_, index) => finding('E4_SEMGREP', id(`unreviewed-semgrep-${index}`)));
  return {
    repository: contract.repository,
    commitSha: '7'.repeat(40),
    contentSha256: '8'.repeat(64),
    totalFindingCount: osv.length + gitleaks.length + semgrep.length,
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

test('R2B preserves accepted boundary coverage while retaining unreviewed OSV database drift fail closed', () => {
  assert.equal(existsSync(acceptedVerifierPath), true);
  assert.equal(existsSync(acceptedTestPath), true);
  const acceptedTest = readFileSync(acceptedTestPath, 'utf8');
  assert.equal(gitBlobSha(acceptedTest), '2c6218eda079c0a73ba7cc9c02757e709f7fd0b5');
  assert.ok(acceptedTest.includes("from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation-accepted.mjs';"));
  assert.equal(acceptedTest.includes("from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs';"), false);
  const acceptedVerifier = readFileSync(acceptedVerifierPath, 'utf8');
  assert.doesNotMatch(acceptedVerifier, /new RegExp\(/);
  assert.ok(acceptedVerifier.includes('candidate.match(/^(\\s*)([A-Za-z0-9_-]+):\\s*(.*)$/)'));
  assert.ok(acceptedVerifier.includes('field[1].length === stepIndent + 2'));
  assert.ok(acceptedVerifier.includes('fields.set(field[2], field[3])'));
  const verifier = readFileSync(verifierPath, 'utf8');
  for (const marker of [
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
  ]) assert.ok(verifier.includes(marker), marker);
  assert.doesNotMatch(verifier, /suppressionAdded:\s*true|exceptionAdded:\s*true|severityDowngradeAdded:\s*true|findingDeletionClaimed:\s*true/);
});

test('R2B Final Acceptance binds the exact successful implementation evidence without claiming closure', () => {
  assert.equal(existsSync(finalAcceptancePath), true);
  const acceptance = readFileSync(finalAcceptancePath, 'utf8');
  for (const marker of [
    'c07295e38d6bb9c3717ad727f873ab7112a6e752',
    '31688917633',
    '#1439',
    '9 / 9 success',
    'M6_PR_E_E3_R2B_REMEDIATION_CANONICAL_SHA256',
    '6406e786410fd7e8ad48fa5a79adfcb238fc981219df65cc9a23a85e5c75f399',
    'OSV:       117',
    'Gitleaks:   27',
    'zizmor:      0',
    'Semgrep:     3',
    'UNRESOLVED:     144',
    'M6_PR_E_R2B_INFRASTRUCTURE_ACCEPTED',
    'M6_PR_E_SECURITY_CLOSURE_NOT_ACCEPTED',
    'PRB_16_REMAINS_OPEN',
    'PRB_17_REMAINS_OPEN',
    'ISSUE_97_REMAINS_OPEN',
    'AI_IS_NOT_AN_OPERATOR',
  ]) assert.ok(acceptance.includes(marker), marker);
  for (const digest of [
    '9fd8729ab18bf289135788f8ef72b556314f8a1c0856661eb967ed0da10c168c',
    '895bf7f152bc2566ece9531302b2bb5865bed859f6d1c052d4688dac3b7a4816',
    '6aa74ae77be16a9655913d22d57cccf5aa0b537e55e4dbe9ad62653e858781b6',
    '1f10bac696633d8e0c942b929c9608850eb986c79ab7377677ced0674694332a',
  ]) assert.ok(acceptance.includes(digest), digest);
  assert.doesNotMatch(acceptance, /PRB_16_(PASS|CLOSED)|PRB_17_(PASS|CLOSED)|ISSUE_97_(CLOSED|COMPLETED)|M6_PRODUCTION_READINESS_PASSED/);
  assert.doesNotMatch(acceptance, /NO_SUPPRESSION\s*:\s*false|NO_EXCEPTION\s*:\s*false|NO_SEVERITY_DOWNGRADE\s*:\s*false/);
});

test('R2B retains count-correct but identity-different OSV results as unresolved current evidence', () => {
  const e4 = unreviewedCurrentE4(117);
  const result = classifyCurrentOsvIdentitySet(e4.scanners.osv, contract);
  assert.equal(result.osvIdentityMode, 'UNREVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET');
  assert.equal(result.acceptedOsvIdentitySetMatched, false);
  assert.equal(result.reviewedCurrentOsvIdentitySetMatched, false);
  assert.equal(result.acceptedOsvIdentitySetUnchanged, true);
  assert.equal(result.currentOsvFindingReviewRequired, true);
  assert.equal(result.currentOsvFindingCount, 117);
  assert.equal(result.unreviewedCurrentOsvFindingCount, 117);
  assert.ok(result.unreviewedCurrentOsvFindings.every((item) => item.disposition === 'UNRESOLVED'));
  assert.ok(result.unreviewedCurrentOsvFindings.every((item) => item.componentRefs.length > 0 && item.scopes.length > 0));
});

test('R2B removes the old count-only accepted OSV compatibility bypass', () => {
  const e4 = unreviewedCurrentE4(115);
  const result = classifyCurrentOsvIdentitySet(e4.scanners.osv, contract);
  assert.equal(result.currentOsvFindingCount, contract.acceptedIdentitySets.osv.findingCount);
  assert.equal(result.acceptedOsvIdentitySetMatched, false);
  assert.equal(result.osvIdentityMode, 'UNREVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET');
  assert.equal(result.currentOsvFindingReviewRequired, true);
});

test('R2B keeps Gitleaks, Semgrep and zizmor identities exact when OSV database drift is unreviewed', () => {
  assert.throws(() => reconcileScannerFindingIdentities(unreviewedCurrentE4()), /current Gitleaks identity-set drift/);
});

test('R2B rejects duplicate or incomplete current OSV triage evidence', () => {
  const duplicate = osvFinding('duplicate');
  const duplicateScanner = {
    scanCompleted: true,
    rawReportRetained: false,
    findingCount: 2,
    findings: [duplicate, duplicate],
  };
  assert.throws(() => classifyCurrentOsvIdentitySet(duplicateScanner, contract), /duplicate finding identity/);

  const incomplete = osvFinding('incomplete', { componentRefs: [] });
  const incompleteScanner = {
    scanCompleted: true,
    rawReportRetained: false,
    findingCount: 1,
    findings: [incomplete],
  };
  assert.throws(() => classifyCurrentOsvIdentitySet(incompleteScanner, contract), /current OSV triage evidence incomplete/);
});
