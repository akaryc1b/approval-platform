import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { modeledHistoricalSourceReviewFixture } from './fixtures/historical-noncredential-review-fixture.mjs';
import { resealResourcesFlattenDigestEvidence } from './fixtures/resources-flatten-digest-review-fixture.mjs';
import { seal } from './fixtures/public-commit-review-fixture.mjs';
import { objectHash } from '../security/gitleaks-git-source-proof.mjs';
import { HMAC_TEST_REVIEW_BASE, historicalHmacTestSources, hmacTestCurrentScope, buildGitleaksHmacTestReviewSnapshot,
  verifyGitleaksHmacTestReview, requireGitleaksHmacTestReviewReceipt, hmacTestDecisions } from '../security/gitleaks-hmac-test-fixtures.mjs';
import { requireGitleaksTestExpressionTriage } from '../security/m6-pr-e-e3-review-gitleaks-test-expression.mjs';
import { runSyntheticCiCallback } from './m6-pr-e-e3-designer-prototype-remediation.test.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const hash = value => createHash('sha256').update(value).digest('hex');
const modelFor = options => modeledHistoricalSourceReviewFixture({ baseCommit: HMAC_TEST_REVIEW_BASE, parent: HMAC_TEST_REVIEW_BASE, ...options });
test('HMAC fixture documentation warns that public demo keys remain functional credentials', () => {
  const readme = readFileSync(new URL('../../examples/generic-spring-host/README.md', import.meta.url), 'utf8');
  assert.ok(readme.includes('It can generate valid signatures: never reuse it for production or shared deployments.'));
  assert.ok(readme.includes('Generate a unique random key and supply it through your secret manager; do not commit real signing keys.'));
});
const initial = () => ({ schemaVersion: 'M6_PR_E_E4_SCANNER_EVIDENCE_V1', repository: 'akaryc1b/approval-platform',
  e2CurrentContentSha256: hash('modeled current E2'), allScannersCompleted: true, rawScannerReportsRetained: false,
  candidateSecretMaterialRetained: false, authoritativeGitHubInventoryStillUnavailable: true, workstreamReleaseBlocked: true,
  scanners: Object.fromEntries(['osv', 'gitleaks', 'zizmor', 'semgrep'].map(name => [name,
    { scanCompleted: true, rawReportRetained: false, findings: [], findingCount: 0,
      ...(name === 'gitleaks' ? { candidateSecretMaterialRetained: false } : {}),
      ...(name === 'semgrep' ? { sourceSnippetRetained: false } : {}) }])) });
const snapshotFor = (e4, proof) => ({ repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256, proof });
let retained;
function baseCase() {
  if (!retained) {
    const model = modelFor(), e4 = model.prepareEvidence(initial()), snapshot = buildGitleaksHmacTestReviewSnapshot(e4, { root, git: model.git });
    retained = { model, e4, snapshot, receipt: verifyGitleaksHmacTestReview(e4, snapshot) };
  }
  return retained;
}

test('HMAC fixture review retains all observations and proves exactly twelve functional public test keys', () => {
  const { e4, receipt, model } = baseCase();
  assert.deepEqual(requireGitleaksHmacTestReviewReceipt(e4, receipt), receipt);
  assert.equal(receipt.retainedFindingCount, 554); assert.equal(receipt.findings.length, 12);
  assert.equal(receipt.sources.filter(row => row.ancestorOfBase).length, 6);
  assert.equal(new Set(receipt.sources.filter(row => !row.ancestorOfBase).map(row => row.commit)).size, 6);
  assert.equal(receipt.decision.credentialMaterialPresent, true); assert.equal(receipt.decision.intentionallyPublicTestFixture, true);
  assert.equal(receipt.decision.externalDeploymentUseAssessed, false); assert.equal(receipt.releaseBlocked, true);
  assert.equal(receipt.rawCandidateMaterialRetained, false); assert.equal(receipt.rawSourceBlobsRetained, false);
  assert.ok(receipt.proof.objects.every(row => ['commit', 'tree'].includes(row.type)));
  assert.equal(new Set(receipt.proof.objects.map(row => row.sha)).size, receipt.proof.objects.length);
  assert.ok(model.commands.every(([command, args]) => command === 'git' && args[0] === 'cat-file' && ['commit', 'tree'].includes(args[1])));
});

for (const [label, mutate] of [
  ['extra finding', e => e.scanners.gitleaks.findings.push({ ...e.scanners.gitleaks.findings[0], findingId: 'f'.repeat(64) })],
  ['missing off-main finding', e => { e.scanners.gitleaks.findings = e.scanners.gitleaks.findings.filter(row => row.findingId !== historicalHmacTestSources().find(row => !row.ancestorOfBase).findingId); }],
  ['duplicate finding', e => { e.scanners.gitleaks.findings[1] = e.scanners.gitleaks.findings[0]; }],
  ['different historical commit', e => { e.scanners.gitleaks.findings.find(row => row.findingId === historicalHmacTestSources()[0].findingId).commit = 'f'.repeat(40); }],
  ['different path', e => { e.scanners.gitleaks.findings.find(row => row.findingId === historicalHmacTestSources()[0].findingId).path = 'unrelated-credentials.json'; }],
  ['different line', e => { e.scanners.gitleaks.findings.find(row => row.findingId === historicalHmacTestSources()[0].findingId).startLine++; }],
  ['different rule', e => { e.scanners.gitleaks.findings[0].ruleId = 'other-credential-rule'; }],
  ['different fingerprint', e => { e.scanners.gitleaks.findings[0].fingerprint += ':changed'; }],
  ['unreviewed description change', e => { e.scanners.gitleaks.findings[0].description += ' changed'; }],
  ['reordered inventory', e => e.scanners.gitleaks.findings.reverse()],
  ['different scanner version', e => { e.scanners.gitleaks.version = 'unreviewed'; }],
  ['different scanner binary pin', e => { e.scanners.gitleaks.assetSha256 = 'f'.repeat(64); }],
  ['non-history scanner mode', e => { e.scanners.gitleaks.mode = 'CURRENT_TREE_ONLY'; }],
  ['unredacted report', e => { e.scanners.gitleaks.redactionPercent = 0; }],
  ['dirty checkout', e => { e.checkout.trackedWorktreeClean = false; }],
  ['wrong tree', e => { e.checkout.expectedHeadTreeSha = e.checkout.checkedOutTreeSha = 'f'.repeat(40); }],
  ['wrong repository', e => { e.repository = 'other/repository'; }],
]) test(`HMAC fixture review rejects ${label}`, () => {
  const { e4, snapshot } = baseCase(), changed = structuredClone(e4); mutate(changed);
  const sealed = resealResourcesFlattenDigestEvidence(changed);
  assert.throws(() => verifyGitleaksHmacTestReview(sealed, snapshotFor(sealed, snapshot.proof)));
});

for (const [label, mutate] of [
  ['missing historical member', p => p.sources.pop()], ['duplicate historical member', p => { p.sources[1] = p.sources[0]; }],
  ['extra historical member', p => p.sources.push(p.sources[0])], ['unknown historical member', p => { p.sources[0].findingId = 'f'.repeat(64); }],
  ['swapped historical order', p => p.sources.reverse()], ['extra raw source field', p => { p.sources[0].rawSource = 'not allowed'; }],
  ['invented main-ancestor assertion', p => { p.sources[0].ancestorOfBase = true; }],
  ['missing current scope', p => p.currentScope.pop()], ['duplicate current scope', p => { p.currentScope[1] = p.currentScope[0]; }],
  ['wrong current path', p => { p.currentScope[0].currentPath = p.currentScope[1].currentPath; }],
  ['wrong accepted path', p => { p.currentScope[0].acceptedPath = p.currentScope[1].acceptedPath; }],
  ['missing original build closure', p => p.sources[0].paths.pop()],
  ['wrong original path', p => { p.sources[0].paths[0].trees = p.sources[0].paths[1].trees; }],
  ['wrong support identity', p => { p.sources[0].paths[1].input++; }],
  ['dangling object reference', p => { p.currentCommits[0] = 'f'.repeat(40); }],
  ['duplicate object row', p => p.objects.push(p.objects[0])], ['missing object row', p => p.objects.pop()],
  ['wrong object type', p => { p.objects[0].type = 'blob'; }],
  ['extra object payload', p => { p.objects[0].rawBlob = 'not allowed'; }],
  ['changed object bytes', p => { p.objects[0].contentBase64 = Buffer.from('changed object').toString('base64'); }],
  ['extra top-level payload', p => { p.sourceContent = 'not allowed'; }],
  ['omitted current ancestry', p => { p.currentCommits = []; }],
  ['duplicate current ancestor', p => p.currentCommits.push(p.currentCommits[0])],
  ['off-main source misrepresented as current ancestry', p => { p.sources[0].commits = p.currentCommits; }],
  ['omitted source ancestry', p => { p.sources[0].commits = []; }],
  ['unused authenticated object', p => {
    const bytes = Buffer.from(`tree ${'0'.repeat(40)}\nauthor Test <test@example.invalid> 1 +0000\ncommitter Test <test@example.invalid> 1 +0000\n\nUnused test object\n`);
    p.objects.push({ type: 'commit', sha: objectHash('commit', bytes), contentBase64: bytes.toString('base64') });
  }],
]) test(`HMAC fixture proof rejects ${label}`, () => {
  const { e4, snapshot } = baseCase(), proof = structuredClone(snapshot.proof); mutate(proof);
  assert.throws(() => verifyGitleaksHmacTestReview(e4, snapshotFor(e4, proof)));
});

test('HMAC fixture proof accepts a merge descendant but rejects an older non-descendant', () => {
  const model = modelFor({ mergeHead: true }), e4 = model.prepareEvidence(initial());
  assert.equal(verifyGitleaksHmacTestReview(e4, buildGitleaksHmacTestReviewSnapshot(e4, { root, git: model.git })).findings.length, 12);
  const prior = modelFor({ parent: '89b91b703cb6e51edae489bd0aeaad0dd8a907d8' }), old = prior.prepareEvidence(initial());
  assert.throws(() => buildGitleaksHmacTestReviewSnapshot(old, { root, git: prior.git }), /required reviewed ancestry absent/);
});
for (const pin of hmacTestCurrentScope()) test(`HMAC fixture proof rejects changed current source or build input ${pin.path}`, () => {
  const model = modelFor({ changedFiles: [pin.path] }), e4 = model.prepareEvidence(initial());
  assert.throws(() => verifyGitleaksHmacTestReview(e4, buildGitleaksHmacTestReviewSnapshot(e4, { root, git: model.git })), /source path\/blob mismatch/);
});

test('HMAC fixture receipt rereads historical bytes, current bytes, regular files and retained refs', async () => {
  const path = 'scripts/security/gitleaks-hmac-test-fixtures.mjs';
  const fsInjection = `import { lstatSync as originalStat, readFileSync as originalRead } from 'node:fs';
export const control = { changed: null, symlink: null, missingBlob: null, replacementBlob: null, hideRefs: false };
const lstatSync = path => control.symlink && String(path).endsWith(control.symlink) ? { isFile: () => true, isSymbolicLink: () => true } : originalStat(path);
const readFileSync = (path, ...options) => { const bytes = originalRead(path, ...options);
  return control.changed && String(path).endsWith(control.changed) ? Buffer.concat([Buffer.from(bytes), Buffer.from('\\n')]) : bytes; };`;
  const gitInjection = `import { spawnSync as originalSpawn } from 'node:child_process';
const spawnSync = (command, args, options) => {
  if (args[0] === 'cat-file' && args[1] === 'blob' && args[2] === control.missingBlob) {
    return control.replacementBlob ? originalSpawn(command, ['cat-file', 'blob', control.replacementBlob], options) : { status: 1, stdout: Buffer.alloc(0) };
  }
  if (args[0] === 'for-each-ref' && control.hideRefs) return { status: 0, stdout: Buffer.from('${HMAC_TEST_REVIEW_BASE}\\n') };
  return originalSpawn(command, args, options);
};`;
  const source = readFileSync(`${root}${path}`, 'utf8').replace("import { lstatSync, readFileSync } from 'node:fs';", fsInjection)
    .replace("import { spawnSync } from 'node:child_process';", gitInjection)
    .replace(/from '\.\/([^']+)'/g, (_, relative) => `from '${pathToFileURL(`${root}scripts/security/${relative}`).href}'`)
    .replaceAll('import.meta.url', JSON.stringify(pathToFileURL(`${root}${path}`).href));
  const isolated = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
  const { e4, receipt } = baseCase(); assert.deepEqual(isolated.requireGitleaksHmacTestReviewReceipt(e4, receipt), receipt);
  for (const pin of hmacTestCurrentScope()) {
    isolated.control.changed = pin.path; assert.throws(() => isolated.requireGitleaksHmacTestReviewReceipt(e4, receipt), /pinned input bytes changed/);
    isolated.control.changed = null; isolated.control.symlink = pin.path;
    assert.throws(() => isolated.requireGitleaksHmacTestReviewReceipt(e4, receipt), /regular file/); isolated.control.symlink = null;
  }
  for (const pin of receipt.inputPins) {
    isolated.control.missingBlob = pin.blobSha;
    assert.throws(() => isolated.requireGitleaksHmacTestReviewReceipt(e4, receipt), /required local Git input unavailable/);
  }
  for (const original of historicalHmacTestSources().filter(row => row.source !== row.currentSource)) {
    isolated.control.missingBlob = original.blobSha; isolated.control.replacementBlob = receipt.inputPins[original.currentSource].blobSha;
    assert.throws(() => isolated.requireGitleaksHmacTestReviewReceipt(e4, receipt), /pinned input bytes changed/);
  }
  isolated.control.missingBlob = isolated.control.replacementBlob = null; isolated.control.hideRefs = true;
  assert.throws(() => isolated.requireGitleaksHmacTestReviewReceipt(e4, receipt), /pinned off-main retained-ref ancestry required/);
  isolated.control.hideRefs = false; assert.deepEqual(isolated.requireGitleaksHmacTestReviewReceipt(e4, receipt), receipt);
});

let callback;
function currentCallback() {
  if (!callback) {
    callback = runSyntheticCiCallback({ currentHmacTestReview: true, currentHistoricalSourceReview: true, currentResourcesFlattenGraph: true, publicCommitFixture: modelFor({ mergeHead: true }) });
    assert.equal(callback.error, undefined); callback.output = stage => JSON.parse(callback.logs.find(text => text.startsWith(`M6_PR_E_${stage}_BEGIN\n`)).split('\n')[1]);
  }
  return callback;
}
test('HMAC fixture actual current callback retains CI and README findings unresolved through I4', () => {
  const result = currentCallback(); assert.equal(result.commands.filter(([command]) => command === process.execPath).length, 1);
  for (const stage of ['E3_I2_TRIAGE', 'E3_I3_TRIAGE', 'E3_I4_TRIAGE']) {
    const triage = result.output(stage), unresolved = triage.decisions.filter(row => row.sourceClass === 'E4_GITLEAKS' && row.disposition === 'UNRESOLVED');
    assert.equal(triage.decisions.filter(row => row.sourceClass === 'E4_GITLEAKS').length, 554); assert.equal(unresolved.length, 12);
    for (const path of ['.github/workflows/generic-spring-host-ci.yml', 'examples/generic-spring-host/README.md']) assert.equal(unresolved.filter(row => row.sourceIdentity.path === path).length, 6);
    assert.equal(triage.decisions.filter(row => row.sourceClass === 'E4_OSV_SCANNER' && row.disposition === 'UNRESOLVED').length, 8);
    assert.equal(triage.summary.unresolvedCount, 20); assert.equal(triage.summary.releaseBlocked, true); assert.equal(triage.appendOnlyReviewedFindingCount, 542);
    const review = triage.gitleaksTestExpressionReview;
    assert.equal(review.schemaVersion, 'M6_PR_E_E3_GITLEAKS_TEST_EXPRESSION_REVIEW_EVIDENCE_V3'); assert.equal(review.hmacTestReview.findings.length, 12);
    assert.equal(review.hmacTestReview.decision.credentialMaterialPresent, true); requireGitleaksTestExpressionTriage(result.e4, triage);
  }
  assert.equal(result.output('E3_I4_TRIAGE').cumulativeReviewedFindingCount, 610);
  assert.equal(result.output('E3_I4_TRIAGE').historicallyRemediatedFindingCount, 66);
});
test('HMAC fixture triage rejects credential-absence rationale, unrelated credentials and resealed identity changes', () => {
  const { e4, output } = currentCallback(), triage = output('E3_I4_TRIAGE'), id = historicalHmacTestSources()[0].findingId;
  for (const mutate of [
    t => { t.decisions.find(row => row.findingId === id).sourceIdentity.path = 'unrelated-credentials.json'; },
    t => { t.decisions.find(row => row.findingId === id).reviewEvidence.credentialMaterialPresent = false; },
    t => { t.decisions.find(row => row.findingId === id).exploitPreconditions = ['ACTUAL_CREDENTIAL_MATERIAL_PRESENT']; },
    t => { t.decisions.find(row => row.sourceClass === 'E4_GITLEAKS' && row.disposition === 'UNRESOLVED').disposition = 'NOT_APPLICABLE'; },
    t => { t.decisions.find(row => row.sourceClass === 'E4_GITLEAKS' && row.disposition === 'UNRESOLVED').reviewEvidence = structuredClone(t.decisions.find(row => row.findingId === id).reviewEvidence); },
    t => { t.appendOnlyReviewedFindingCount++; },
    t => { t.gitleaksTestExpressionReview.hmacTestReview.decision.credentialMaterialPresent = false; t.gitleaksTestExpressionReview.hmacTestReview = seal(t.gitleaksTestExpressionReview.hmacTestReview); t.gitleaksTestExpressionReview = seal(t.gitleaksTestExpressionReview); },
    t => { t.gitleaksTestExpressionReview.hmacTestReview.sources[0].ancestorOfBase = true; t.gitleaksTestExpressionReview.hmacTestReview = seal(t.gitleaksTestExpressionReview.hmacTestReview); t.gitleaksTestExpressionReview = seal(t.gitleaksTestExpressionReview); },
  ]) { const changed = structuredClone(triage); mutate(changed); assert.throws(() => requireGitleaksTestExpressionTriage(e4, seal(changed))); }
  assert.throws(() => hmacTestDecisions(triage.decisions, triage.gitleaksTestExpressionReview.hmacTestReview, true), /only exact unresolved/);
});
test('HMAC fixture prior V2 callback stays conservative and cannot transfer the twelve dispositions', () => {
  const result = runSyntheticCiCallback({ currentHistoricalSourceReview: true, currentResourcesFlattenGraph: true, publicCommitFixture: modelFor() });
  assert.equal(result.error, undefined);
  const triage = JSON.parse(result.logs.find(text => text.startsWith('M6_PR_E_E3_I4_TRIAGE_BEGIN\n')).split('\n')[1]);
  assert.equal(triage.summary.unresolvedCount, 32); assert.equal(triage.gitleaksTestExpressionReview.hmacTestReview, undefined);
  requireGitleaksTestExpressionTriage(result.e4, triage);
  const changed = structuredClone(triage), id = historicalHmacTestSources()[0].findingId;
  changed.decisions.find(row => row.findingId === id).disposition = 'NOT_APPLICABLE';
  changed.summary.dispositionCounts.UNRESOLVED--; changed.summary.dispositionCounts.NOT_APPLICABLE++;
  changed.summary.unresolvedCount--; changed.summary.resolvedCount++;
  assert.throws(() => requireGitleaksTestExpressionTriage(result.e4, seal(changed)), /unreviewed retained disposition drift/);
});
