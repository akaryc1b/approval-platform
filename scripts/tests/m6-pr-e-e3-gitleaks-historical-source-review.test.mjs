import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { modeledHistoricalSourceReviewFixture } from './fixtures/historical-noncredential-review-fixture.mjs';
import { resealResourcesFlattenDigestEvidence } from './fixtures/resources-flatten-digest-review-fixture.mjs';
import { seal } from './fixtures/public-commit-review-fixture.mjs';
import { historicalNonCredentialSources, verifyGitleaksHistoricalSourceReview,
  requireGitleaksHistoricalSourceReviewReceipt, historicalSourceDecisions } from '../security/gitleaks-historical-noncredential-sources.mjs';
import { requireGitleaksTestExpressionTriage } from '../security/m6-pr-e-e3-review-gitleaks-test-expression.mjs';
import { runSyntheticCiCallback } from './m6-pr-e-e3-designer-prototype-remediation.test.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const hash = value => createHash('sha256').update(value).digest('hex');
const initial = () => ({ schemaVersion: 'M6_PR_E_E4_SCANNER_EVIDENCE_V1', repository: 'akaryc1b/approval-platform',
  e2CurrentContentSha256: hash('modeled current E2'), allScannersCompleted: true, rawScannerReportsRetained: false,
  candidateSecretMaterialRetained: false, authoritativeGitHubInventoryStillUnavailable: true, workstreamReleaseBlocked: true,
  scanners: Object.fromEntries(['osv', 'gitleaks', 'zizmor', 'semgrep'].map(name => [name,
    { scanCompleted: true, rawReportRetained: false, findings: [], findingCount: 0,
      ...(name === 'gitleaks' ? { candidateSecretMaterialRetained: false } : {}),
      ...(name === 'semgrep' ? { sourceSnippetRetained: false } : {}) }])) });
let retained;
function baseCase() {
  if (!retained) {
    const model = modeledHistoricalSourceReviewFixture(), e4 = model.prepareEvidence(initial()), snapshot = model.snapshot(e4);
    retained = { model, e4, snapshot, receipt: verifyGitleaksHistoricalSourceReview(e4, snapshot) };
  }
  return retained;
}
const snapshotFor = (e4, proof) => ({ repository: e4.repository, commitSha: e4.commitSha,
  currentE4CanonicalSha256: e4.contentSha256, proof });

test('historical source review proves exactly three non-credential roles and retains all 554 observations', () => {
  const { e4, snapshot, receipt, model } = baseCase();
  assert.deepEqual(requireGitleaksHistoricalSourceReviewReceipt(e4, receipt), receipt);
  assert.equal(receipt.retainedFindingCount, 554); assert.equal(receipt.findings.length, 3);
  assert.deepEqual(receipt.sources.map(row => row.role), ['SQL_QUERY_PREDICATE_TEST_FIXTURE', 'SDK_LEASE_REFERENCE_FIXTURE_METADATA', 'DOCUMENTED_ANCESTRAL_GIT_COMMIT']);
  assert.equal(receipt.releaseBlocked, true); assert.equal(receipt.rawCandidateMaterialRetained, false);
  assert.equal(receipt.rawSourceBlobsRetained, false); assert.equal(JSON.stringify(snapshot).includes('sourceBase64'), false);
  const start = model.commands.length; model.snapshot(e4);
  assert.ok(model.commands.slice(start).every(([command, args]) => command === 'git' && args[0] === 'cat-file' && ['commit', 'tree'].includes(args[1])));
});

for (const [label, mutate] of [
  ['extra observation', e => e.scanners.gitleaks.findings.push({ ...e.scanners.gitleaks.findings[0], findingId: 'f'.repeat(64) })],
  ['missing observation', e => e.scanners.gitleaks.findings.pop()],
  ['duplicate observation', e => { e.scanners.gitleaks.findings[1] = e.scanners.gitleaks.findings[0]; }],
  ['changed historical commit', e => { e.scanners.gitleaks.findings.find(f => f.findingId === historicalNonCredentialSources()[0].findingId).commit = 'f'.repeat(40); }],
  ['changed historical path', e => { e.scanners.gitleaks.findings.find(f => f.findingId === historicalNonCredentialSources()[0].findingId).path = 'unrelated-credentials.json'; }],
  ['changed historical line', e => { e.scanners.gitleaks.findings.find(f => f.findingId === historicalNonCredentialSources()[0].findingId).startLine++; }],
  ['changed historical rule', e => { e.scanners.gitleaks.findings[0].ruleId = 'unrelated-credential-rule'; }],
  ['changed historical fingerprint', e => { e.scanners.gitleaks.findings[0].fingerprint += ':changed'; }],
  ['changed unreviewed description', e => { e.scanners.gitleaks.findings[0].description += ' changed'; }],
  ['changed unreviewed identity', e => { e.scanners.gitleaks.findings[0].findingId = 'f'.repeat(64); }],
  ['reordered inventory', e => e.scanners.gitleaks.findings.reverse()],
  ['dirty checkout', e => { e.checkout.trackedWorktreeClean = false; }],
  ['wrong tree', e => { e.checkout.expectedHeadTreeSha = e.checkout.checkedOutTreeSha = 'f'.repeat(40); }],
  ['wrong repository', e => { e.repository = 'unrelated/repository'; }],
]) test(`historical source review rejects ${label}`, () => {
  const { e4, snapshot } = baseCase(), changed = structuredClone(e4); mutate(changed);
  const sealed = resealResourcesFlattenDigestEvidence(changed);
  assert.throws(() => verifyGitleaksHistoricalSourceReview(sealed, snapshotFor(sealed, snapshot.proof)));
});

for (const [label, mutate] of [
  ['missing member', p => p.sources.pop()], ['duplicate member', p => { p.sources[1] = p.sources[0]; }],
  ['extra member', p => p.sources.push(p.sources[0])], ['unknown member', p => { p.sources[0].findingId = 'f'.repeat(64); }],
  ['swapped member order', p => p.sources.reverse()], ['extra raw source field', p => { p.sources[0].rawSource = 'not accepted'; }],
  ['unknown proof field', p => { p.extra = true; }], ['missing contract', p => { delete p.contract; }],
  ['wrong contract path', p => { p.contract.currentPath = p.sources[1].paths.currentPath; }],
  ['wrong original source path', p => { p.sources[0].paths.historicalPath = p.sources[1].paths.historicalPath; }],
  ['wrong accepted source path', p => { p.sources[0].paths.acceptedPath = p.sources[1].paths.acceptedPath; }],
  ['wrong current source path', p => { p.sources[0].paths.currentPath = p.sources[1].paths.currentPath; }],
  ['changed Git commit bytes', p => { p.sources[0].commits.at(-1).contentBase64 = Buffer.from('changed commit').toString('base64'); }],
  ['changed Git tree bytes', p => { p.sources[0].paths.currentPath[0].contentBase64 = Buffer.from('changed tree').toString('base64'); }],
  ['omitted current ancestry', p => { p.currentCommits = []; }],
  ['false current ancestry endpoint', p => { p.currentCommits = p.sources[0].commits; }],
  ['omitted source ancestry', p => { p.sources[0].commits = []; }],
  ['broken source parent chain', p => { p.sources[0].commits.splice(1, 1); }],
  ['duplicate source ancestor', p => { p.sources[0].commits.splice(1, 0, p.sources[0].commits[0]); }],
  ['unproved documented commit', p => { p.referenceCommits = [p.referenceCommits[0]]; }],
  ['replaced documented commit ancestry', p => { p.referenceCommits = p.sources[0].commits; }],
]) test(`historical source proof rejects ${label}`, () => {
  const { e4, snapshot } = baseCase(), proof = structuredClone(snapshot.proof); mutate(proof);
  assert.throws(() => verifyGitleaksHistoricalSourceReview(e4, snapshotFor(e4, proof)));
});

test('historical source proof accepts a real modeled merge descendant but rejects a non-descendant', () => {
  const model = modeledHistoricalSourceReviewFixture({ mergeHead: true }), e4 = model.prepareEvidence(initial());
  assert.equal(verifyGitleaksHistoricalSourceReview(e4, model.snapshot(e4)).findings.length, 3);
  const unrelated = modeledHistoricalSourceReviewFixture({ parent: historicalNonCredentialSources()[0].commit });
  assert.throws(() => unrelated.snapshot(unrelated.prepareEvidence(initial())), /absent from current ancestry/);
});
for (const path of [...historicalNonCredentialSources().map(row => row.path), 'contracts/sdk/v1/ADAPTER_BINDING.md']) {
  test(`historical source proof refuses changed current Git blob ${path}`, () => {
    const model = modeledHistoricalSourceReviewFixture({ changedFiles: [path] }), e4 = model.prepareEvidence(initial());
    assert.throws(() => verifyGitleaksHistoricalSourceReview(e4, model.snapshot(e4)), /source path\/blob mismatch/);
  });
}

test('historical source receipt rechecks all pinned checkout bytes after successful replay', async () => {
  const path = 'scripts/security/gitleaks-historical-noncredential-sources.mjs';
  const injection = `import { lstatSync, readFileSync as originalRead } from 'node:fs';
export const control = { changed: null };
const readFileSync = (path, ...options) => { const bytes = originalRead(path, ...options);
  return control.changed && String(path).endsWith(control.changed) ? Buffer.concat([Buffer.from(bytes), Buffer.from('\\n')]) : bytes; };`;
  const source = readFileSync(`${root}${path}`, 'utf8').replace("import { lstatSync, readFileSync } from 'node:fs';", injection)
    .replace(/from '\.\/([^']+)'/g, (_, relative) => `from '${pathToFileURL(`${root}scripts/security/${relative}`).href}'`)
    .replaceAll('import.meta.url', JSON.stringify(pathToFileURL(`${root}${path}`).href));
  const isolated = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
  const { e4, receipt } = baseCase(); assert.deepEqual(isolated.requireGitleaksHistoricalSourceReviewReceipt(e4, receipt), receipt);
  for (const path of [...historicalNonCredentialSources().map(row => row.path), 'contracts/sdk/v1/ADAPTER_BINDING.md']) {
    isolated.control.changed = path;
    assert.throws(() => isolated.requireGitleaksHistoricalSourceReviewReceipt(e4, receipt), /pinned source bytes changed/);
  }
  isolated.control.changed = null; assert.deepEqual(isolated.requireGitleaksHistoricalSourceReviewReceipt(e4, receipt), receipt);
});

let callback;
function currentCallback() {
  if (!callback) {
    const model = modeledHistoricalSourceReviewFixture({ mergeHead: true });
    callback = runSyntheticCiCallback({ currentResourcesFlattenGraph: true, currentHistoricalSourceReview: true, publicCommitFixture: model });
    assert.equal(callback.error, undefined);
    callback.output = stage => JSON.parse(callback.logs.find(text => text.startsWith(`M6_PR_E_${stage}_BEGIN\n`)).split('\n')[1]);
  }
  return callback;
}
test('current historical-source callback retains every observation through I4 with only three new dispositions', () => {
  const result = currentCallback();
  assert.equal(result.commands.filter(([command]) => command === process.execPath).length, 1);
  for (const stage of ['E3_I2_TRIAGE', 'E3_I3_TRIAGE', 'E3_I4_TRIAGE']) {
    const triage = result.output(stage), gitleaks = triage.decisions.filter(row => row.sourceClass === 'E4_GITLEAKS');
    assert.equal(gitleaks.length, 554); assert.equal(gitleaks.filter(row => row.disposition === 'UNRESOLVED').length, 24);
    assert.equal(triage.decisions.filter(row => row.sourceClass === 'E4_OSV_SCANNER' && row.disposition === 'UNRESOLVED').length, 8);
    assert.equal(triage.summary.unresolvedCount, 32); assert.equal(triage.summary.releaseBlocked, true);
    assert.equal(triage.appendOnlyReviewedFindingCount, 530);
    assert.equal(triage.gitleaksTestExpressionReview.schemaVersion, 'M6_PR_E_E3_GITLEAKS_TEST_EXPRESSION_REVIEW_EVIDENCE_V2');
    assert.equal(triage.gitleaksTestExpressionReview.historicalSourceReview.findings.length, 3);
    requireGitleaksTestExpressionTriage(result.e4, triage);
  }
  assert.equal(result.output('E3_I4_TRIAGE').cumulativeReviewedFindingCount, 598);
  assert.equal(result.output('E3_I4_TRIAGE').historicallyRemediatedFindingCount, 66);
});

test('historical source triage rejects resealed decisions for unrelated signing credentials and exact identity mutations', () => {
  const { e4, output } = currentCallback(), triage = output('E3_I4_TRIAGE');
  const id = historicalNonCredentialSources()[0].findingId;
  for (const mutate of [
    t => { t.decisions.find(row => row.findingId === id).sourceIdentity.path = 'unrelated-credentials.json'; },
    t => { t.decisions.find(row => row.findingId === id).disposition = 'UNRESOLVED'; },
    t => { t.decisions.find(row => row.findingId === id).reviewEvidence.metadataRole = 'UNRELATED_CREDENTIAL'; },
    t => { t.decisions.find(row => row.sourceClass === 'E4_GITLEAKS' && row.disposition === 'UNRESOLVED').disposition = 'NOT_APPLICABLE'; },
    t => { t.decisions.find(row => row.sourceClass === 'E4_GITLEAKS' && row.disposition === 'UNRESOLVED').reviewEvidence = structuredClone(t.decisions.find(row => row.findingId === id).reviewEvidence); },
    t => { t.appendOnlyReviewedFindingCount++; },
    t => { t.gitleaksTestExpressionReview.historicalSourceReview.sources[0].role = 'UNRELATED_CREDENTIAL'; t.gitleaksTestExpressionReview.historicalSourceReview = seal(t.gitleaksTestExpressionReview.historicalSourceReview); t.gitleaksTestExpressionReview = seal(t.gitleaksTestExpressionReview); },
  ]) { const changed = structuredClone(triage); mutate(changed); assert.throws(() => requireGitleaksTestExpressionTriage(e4, seal(changed))); }
  assert.throws(() => historicalSourceDecisions(triage.decisions, triage.gitleaksTestExpressionReview.historicalSourceReview, true), /only exact unresolved/);
});

test('historical V1 receipt stays conservative and cannot transfer the three new dispositions', () => {
  const model = modeledHistoricalSourceReviewFixture(), result = runSyntheticCiCallback({ currentResourcesFlattenGraph: true, publicCommitFixture: model });
  assert.equal(result.error, undefined);
  const triage = JSON.parse(result.logs.find(text => text.startsWith('M6_PR_E_E3_I4_TRIAGE_BEGIN\n')).split('\n')[1]);
  assert.equal(triage.summary.unresolvedCount, 35); assert.equal(triage.gitleaksTestExpressionReview.historicalSourceReview, undefined);
  requireGitleaksTestExpressionTriage(result.e4, triage);
  const changed = structuredClone(triage), id = historicalNonCredentialSources()[0].findingId;
  changed.decisions.find(row => row.findingId === id).disposition = 'NOT_APPLICABLE';
  changed.summary.dispositionCounts.UNRESOLVED--; changed.summary.dispositionCounts.NOT_APPLICABLE++;
  changed.summary.unresolvedCount--; changed.summary.resolvedCount++;
  assert.throws(() => requireGitleaksTestExpressionTriage(result.e4, seal(changed)), /unreviewed retained disposition drift/);
});
