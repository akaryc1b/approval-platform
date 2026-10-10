import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { syntheticE4, snapshot as prototypeSnapshot, i2Inputs, runSyntheticCiCallback }
  from './m6-pr-e-e3-designer-prototype-remediation.test.mjs';
import { modeledPublicCommitFixture, canonical, seal } from './fixtures/public-commit-review-fixture.mjs';
import { readGitleaksPublicCommitReviewPlan, verifyGitleaksPublicCommitReview,
  requireGitleaksPublicCommitReviewReceipt, buildGitleaksPublicCommitReviewSnapshot }
  from '../security/m6-pr-e-e3-review-gitleaks-public-commit.mjs';
import { readGitleaksTestExpressionReviewPlan, verifyGitleaksTestExpressionReview,
  applyGitleaksTestExpressionReview, requireGitleaksTestExpressionTriage }
  from '../security/m6-pr-e-e3-review-gitleaks-test-expression.mjs';
import { buildScannerFindingIntake } from '../security/m6-pr-e-e3-ingest-e4.mjs';
import { applyReviewedFindingsWithIdentityTransitions } from '../security/m6-pr-e-e3-apply-reviewed-findings-with-identity-transitions.mjs';
import { applyRuntimeDeploymentReviews } from '../security/m6-pr-e-e3-apply-runtime-deployment-reviews.mjs';
import { verifyPgjdbcRemediation } from '../security/m6-pr-e-e3-verify-pgjdbc-remediation.mjs';
import { reconcileScannerFindingIdentities } from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url)), plan = readGitleaksPublicCommitReviewPlan();
const oldPlan = readGitleaksTestExpressionReviewPlan();
const hash = value => createHash('sha256').update(value).digest('hex');
const read = path => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8');
const json = suffix => JSON.parse(read(`docs/m6/m6-pr-e-e3-${suffix}.json`));
const gitText = args => {
  const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr); return result.stdout;
};
const historicalContent = gitText(['show', `${oldPlan.historicalSource.commitSha}:${oldPlan.historicalSource.path}`]);
const fixture = modeledPublicCommitFixture();
const currentE4 = () => fixture.prepareEvidence(syntheticE4());
const fullSnapshot = e4 => ({ repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
  historicalSource: { ...oldPlan.historicalSource, content: historicalContent }, publicCommitReviewSnapshot: fixture.snapshot(e4) });
const currentI2 = e4 => applyReviewedFindingsWithIdentityTransitions(
  buildScannerFindingIntake(e4, { snapshotTime: '2026-10-09T00:00:00Z' }), json('i2-reviewed-findings'),
  json('r2b-semgrep-finding-identity-transition'), i2Inputs(e4));
const reseal = e4 => {
  e4.scanners.gitleaks.findingCount = e4.scanners.gitleaks.findings.length;
  e4.totalFindingCount = Object.values(e4.scanners).reduce((sum, scanner) => sum + scanner.findingCount, 0);
  return seal(e4);
};
const snapshotFor = (e4, proof) => ({ repository: e4.repository, commitSha: e4.commitSha,
  currentE4CanonicalSha256: e4.contentSha256, proof });
const reidentify = finding => {
  finding.fingerprint = `${finding.commit}:${finding.path}:${finding.ruleId}:${finding.startLine}`;
  finding.findingId = hash(['GITLEAKS', finding.fingerprint, finding.ruleId, finding.path, String(finding.startLine)].join('\0'));
};

test('modeled alternate public introduction and additive current tree have replayable exact source proof', () => {
  const e4 = currentE4(), snapshot = fixture.snapshot(e4), before = canonical(e4);
  const receipt = verifyGitleaksPublicCommitReview(e4, snapshot);
  assert.equal(receipt.finding.commit, fixture.intro);
  assert.notEqual(receipt.finding.commit, fixture.head);
  assert.notEqual(fixture.tree, plan.introductionTreeSha);
  assert.equal(receipt.currentFindingCount, 29);
  assert.equal(receipt.retainedFindingCount, 28);
  assert.equal(receipt.decision.disposition, 'NOT_APPLICABLE');
  assert.equal(receipt.releaseBlocked, true);
  assert.deepEqual(requireGitleaksPublicCommitReviewReceipt(e4, receipt), receipt);
  assert.equal(canonical(e4), before);
  const other = modeledPublicCommitFixture('another authenticated publication has different Git metadata');
  const otherE4 = other.prepareEvidence(syntheticE4());
  const otherReceipt = verifyGitleaksPublicCommitReview(otherE4, other.snapshot(otherE4));
  assert.notEqual(otherReceipt.finding.commit, receipt.finding.commit);
  assert.notEqual(otherReceipt.finding.findingId, receipt.finding.findingId);
  assert.equal(otherReceipt.introductionTreeSha, receipt.introductionTreeSha);
});

test('append-only I2 review preserves every finding and unrelated decision and requires separate proof', () => {
  const e4 = currentE4(), source = fullSnapshot(e4), before = currentI2(e4);
  const after = applyGitleaksTestExpressionReview(before, e4, source);
  const reviewed = new Set([oldPlan.historicalFinding.findingId, fixture.finding.findingId]);
  assert.deepEqual(after.decisions.map(item => item.findingId), before.decisions.map(item => item.findingId));
  for (const item of before.decisions.filter(item => !reviewed.has(item.findingId))) {
    assert.deepEqual(after.decisions.find(current => current.findingId === item.findingId), item);
  }
  assert.equal(after.appendOnlyReviewedFindingCount, 2);
  assert.equal(after.currentReviewedFindingCount, 4);
  assert.equal(after.summary.releaseBlocked, true);
  assert.equal(after.summary.unresolvedCount, before.summary.unresolvedCount - 2);
  requireGitleaksTestExpressionTriage(e4, after);
  const reconciled = reconcileScannerFindingIdentities(e4, prototypeSnapshot(e4), source);
  assert.equal(reconciled.retainedHistoricalGitleaksFindingCount, 27);
  assert.equal(reconciled.reviewedAddedGitleaksFindingCount, 2);
  assert.equal(reconciled.currentScannerCounts.gitleaks, 29);
  assert.deepEqual(reconciled.gitleaksPublicCommitReview, after.gitleaksPublicCommitReview);
  const r1 = verifyPgjdbcRemediation(e4, json('r1-pgjdbc-remediation'));
  const i3 = applyRuntimeDeploymentReviews(after, e4, json('i3-reviewed-findings'), r1);
  assert.deepEqual(i3.gitleaksPublicCommitReview, after.gitleaksPublicCommitReview);
  assert.equal(i3.appendOnlyReviewedFindingCount, 2);
  requireGitleaksTestExpressionTriage(e4, i3);
  const missing = structuredClone(source); delete missing.publicCommitReviewSnapshot;
  assert.throws(() => verifyGitleaksTestExpressionReview(e4, missing), /unreviewed addition/);
  assert.throws(() => reconcileScannerFindingIdentities(e4, prototypeSnapshot(e4), missing), /unreviewed addition/);
  const noReceipts = structuredClone(after); delete noReceipts.gitleaksPublicCommitReview; delete noReceipts.gitleaksTestExpressionReview;
  assert.throws(() => applyRuntimeDeploymentReviews(seal(noReceipts), e4, json('i3-reviewed-findings'), r1), /both source review receipts/);
});

for (const [name, mutate] of [
  ['missing', e => { e.scanners.gitleaks.findings.shift(); }],
  ['duplicate', e => { e.scanners.gitleaks.findings.push(e.scanners.gitleaks.findings[0]); }],
  ['reordered', e => { e.scanners.gitleaks.findings.reverse(); }],
  ['extra', e => { e.scanners.gitleaks.findings.push({ ...fixture.finding, findingId: hash('unrelated identity') }); }],
  ['unknown metadata', e => { e.scanners.gitleaks.findings[0].extra = true; }],
  ['changed historical metadata', e => { e.scanners.gitleaks.findings.find(f => f.findingId === plan.retainedFindings[0].findingId).description = 'changed'; }],
]) test(`public commit proof rejects ${name} findings with a rehashed envelope`, () => {
  let e4 = currentE4(); const proof = fixture.snapshot(e4).proof;
  mutate(e4); e4 = reseal(e4);
  assert.throws(() => verifyGitleaksPublicCommitReview(e4, snapshotFor(e4, proof)), /Gitleaks|E4/);
});
for (const field of ['commit', 'path', 'ruleId', 'description', 'startLine', 'endLine', 'fingerprint', 'findingId', 'sourceClass']) {
  test(`public commit proof rejects exact new finding ${field} drift`, () => {
    let e4 = currentE4(); const proof = fixture.snapshot(e4).proof;
    const finding = e4.scanners.gitleaks.findings.find(item => item.findingId === fixture.finding.findingId);
    finding[field] = typeof finding[field] === 'number' ? finding[field] + 1 : 'unreviewed';
    e4 = reseal(e4);
    assert.throws(() => verifyGitleaksPublicCommitReview(e4, snapshotFor(e4, proof)), /Gitleaks|E4/);
  });
}
for (const [name, mutate] of [
  ['arbitrary hexadecimal reference', value => { value[plan.source.field] = 'f'.repeat(40); }],
  ['credential-shaped reference', value => { value[plan.source.field] = ['fixture', hash('not public Git provenance')].join('_'); }],
  ['different field', value => { value.otherHead = value[plan.source.field]; delete value[plan.source.field]; }],
]) test(`public commit proof rejects ${name}`, () => {
  const e4 = currentE4(), source = fixture.snapshot(e4);
  const value = JSON.parse(Buffer.from(source.proof.sourceBase64, 'base64').toString('utf8'));
  mutate(value); source.proof.sourceBase64 = Buffer.from(`${JSON.stringify(value, null, 2)}\n`).toString('base64');
  assert.throws(() => verifyGitleaksPublicCommitReview(e4, source), /source blob mismatch/);
});
for (const [name, mutate] of [
  ['wrong Git commit bytes', proof => { proof.commits[0].contentBase64 = Buffer.from('forged').toString('base64'); }],
  ['missing introduction', proof => { proof.commits.splice(1, 1); }],
  ['reordered ancestry', proof => { proof.commits.reverse(); }],
  ['duplicated ancestry', proof => { proof.commits.splice(1, 0, proof.commits[0]); }],
  ['current source path omitted', proof => { proof.currentPath = []; }],
  ['introduction source path omitted', proof => { proof.introductionPath = []; }],
  ['base absence proof omitted', proof => { proof.basePath = []; }],
  ['wrong tree object bytes', proof => { proof.currentPath[0].contentBase64 = Buffer.from('forged').toString('base64'); }],
  ['wrong path tree identity', proof => { proof.currentPath[0].sha = plan.source.blobSha; }],
  ['extra tree proof', proof => { proof.currentPath.push(proof.currentPath[0]); }],
  ['self-asserted proof Boolean', proof => { proof.verified = true; }],
]) test(`public commit receipt replay rejects ${name} even when resealed`, () => {
  const e4 = currentE4(), evidence = verifyGitleaksPublicCommitReview(e4, fixture.snapshot(e4));
  mutate(evidence.proof);
  assert.throws(() => requireGitleaksPublicCommitReviewReceipt(e4, seal(evidence)), /Gitleaks/);
});

test('a copied later finding or wrong exact-head tree cannot claim the original introduction', () => {
  let e4 = currentE4(); const proof = fixture.snapshot(e4).proof;
  const finding = e4.scanners.gitleaks.findings.find(item => item.findingId === fixture.finding.findingId);
  finding.commit = fixture.head; reidentify(finding);
  e4.scanners.gitleaks.findings.sort((a, b) => a.findingId.localeCompare(b.findingId)); e4 = reseal(e4);
  assert.throws(() => verifyGitleaksPublicCommitReview(e4, snapshotFor(e4, proof)), /introduction\/base provenance/);
  e4 = currentE4(); e4.checkout.expectedHeadTreeSha = e4.checkout.checkedOutTreeSha = plan.introductionTreeSha; e4 = seal(e4);
  assert.throws(() => verifyGitleaksPublicCommitReview(e4, snapshotFor(e4, proof)), /current Git Head\/tree mismatch/);
});
for (const field of ['decision', 'releaseBlocked', 'finding', 'source', 'planCanonicalSha256']) test(`public commit receipt rejects resealed ${field} changes`, () => {
  const e4 = currentE4(), evidence = verifyGitleaksPublicCommitReview(e4, fixture.snapshot(e4));
  evidence[field] = null;
  assert.throws(() => requireGitleaksPublicCommitReviewReceipt(e4, seal(evidence)), /receipt drift/);
});

test('read-only snapshot builder fails closed on missing Git objects and never invokes another command', () => {
  assert.throws(() => buildGitleaksPublicCommitReviewSnapshot(currentE4(), { root, git: () => ({ status: 1 }) }), /Git commit read failed/);
  fixture.snapshot(currentE4());
  assert.ok(fixture.commands.every(([command, args]) => command === 'git' && args[0] === 'cat-file'));
});

test('normal CI callback consumes one modeled scan and emits all 29 identities through separate I2/I3/I4 proof receipts', () => {
  const result = runSyntheticCiCallback({ publicCommitFixture: fixture });
  assert.equal(result.error, undefined);
  assert.equal(result.commands.filter(([command]) => command === process.execPath).length, 1);
  const output = stage => JSON.parse(result.logs.find(text => text.startsWith(`M6_PR_E_${stage}_BEGIN\n`)).split('\n')[1]);
  const i2 = output('E3_I2_TRIAGE'), i3 = output('E3_I3_TRIAGE'), i4 = output('E3_I4_TRIAGE');
  const publicReview = output('E3_GITLEAKS_PUBLIC_COMMIT_REVIEW');
  for (const triage of [i2, i3, i4]) {
    assert.deepEqual(triage.gitleaksPublicCommitReview, publicReview);
    assert.equal(triage.appendOnlyReviewedFindingCount, 2);
    assert.equal(triage.summary.releaseBlocked, true);
    assert.equal(triage.decisions.filter(item => item.sourceClass === 'E4_GITLEAKS').length, 29);
    requireGitleaksTestExpressionTriage(result.e4, triage);
  }
  assert.equal(i4.summary.notApplicableCount, 4);
  assert.equal(i4.cumulativeReviewedFindingCount, 70);
  assert.equal(i4.historicallyRemediatedFindingCount, 66);
  assert.deepEqual(i4.decisions.map(item => item.findingId), i2.decisions.map(item => item.findingId));
});

test('28-only plan, receipt and I2 outputs remain byte-canonical identical to the accepted historical implementation', async () => {
  const sourcePath = 'scripts/security/m6-pr-e-e3-review-gitleaks-test-expression.mjs';
  const historicalModuleSource = gitText(['show', `${plan.acceptedPublicBaseCommit}:${sourcePath}`])
    .replace(/from '\.\/([^']+)'/g, (_, path) => `from '${pathToFileURL(`${root}scripts/security/${path}`).href}'`)
    .replaceAll('import.meta.url', JSON.stringify(pathToFileURL(`${root}${sourcePath}`).href));
  const historical = await import(`data:text/javascript;base64,${Buffer.from(historicalModuleSource).toString('base64')}`);
  assert.deepEqual(readGitleaksTestExpressionReviewPlan(), historical.readGitleaksTestExpressionReviewPlan());
  const planPath = 'docs/m6/m6-pr-e-e3-gitleaks-test-expression-review.json';
  assert.equal(read(planPath), gitText(['show', `${plan.acceptedPublicBaseCommit}:${planPath}`]));
  let e4 = syntheticE4(); e4.scanners.gitleaks = { ...e4.scanners.gitleaks, ...plan.scannerIdentity,
    findings: structuredClone(plan.retainedFindings), findingCount: 28 }; e4 = reseal(e4);
  const source = { repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
    historicalSource: { ...oldPlan.historicalSource, content: historicalContent } };
  assert.equal(canonical(verifyGitleaksTestExpressionReview(e4, source)), canonical(historical.verifyGitleaksTestExpressionReview(e4, source)));
  const intake = currentI2(e4);
  assert.equal(canonical(applyGitleaksTestExpressionReview(intake, e4, source)), canonical(historical.applyGitleaksTestExpressionReview(intake, e4, source)));
});


test('normal main merge follows the hash-verified second parent and rejects a nonexistent parent edge', () => {
  const merge = modeledPublicCommitFixture('public introduction for normal merge', { mergeHead: true });
  const e4 = merge.prepareEvidence(syntheticE4()), source = merge.snapshot(e4);
  assert.deepEqual(source.proof.commits.map(row => row.sha),
    [merge.head, merge.branchHead, merge.intro, plan.acceptedPublicBaseCommit]);
  const receipt = verifyGitleaksPublicCommitReview(e4, source);
  requireGitleaksPublicCommitReviewReceipt(e4, receipt);
  const missing = structuredClone(receipt); missing.proof.commits.splice(1, 1);
  assert.throws(() => requireGitleaksPublicCommitReviewReceipt(e4, seal(missing)), /parent ancestry mismatch/);
  const forged = structuredClone(receipt);
  const header = Buffer.from(forged.proof.commits[0].contentBase64, 'base64').toString('utf8')
    .replace(`parent ${merge.branchHead}`, `parent ${merge.intro}`);
  forged.proof.commits[0].contentBase64 = Buffer.from(header).toString('base64');
  assert.throws(() => requireGitleaksPublicCommitReviewReceipt(e4, seal(forged)), /Git commit object hash mismatch/);
  const wrongE4 = structuredClone(e4);
  const changed = wrongE4.scanners.gitleaks.findings.find(item => item.findingId === merge.finding.findingId);
  changed.commit = 'e'.repeat(40); reidentify(changed);
  wrongE4.scanners.gitleaks.findings.sort((a, b) => a.findingId.localeCompare(b.findingId));
  assert.throws(() => buildGitleaksPublicCommitReviewSnapshot(reseal(wrongE4), { root, git: merge.git }), /introduction absent/);
});

test('normal CI callback supports the modeled merge Head and preserves separate public review through I4', () => {
  const merge = modeledPublicCommitFixture('normal CI merge introduction', { mergeHead: true });
  const result = runSyntheticCiCallback({ publicCommitFixture: merge });
  assert.equal(result.error, undefined);
  const output = result.logs.find(text => text.startsWith('M6_PR_E_E3_I4_TRIAGE_BEGIN\n'));
  const i4 = JSON.parse(output.split('\n')[1]);
  assert.equal(i4.commitSha, merge.head);
  assert.equal(i4.gitleaksPublicCommitReview.finding.commit, merge.intro);
  assert.equal(i4.gitleaksPublicCommitReview.proof.commits[1].sha, merge.branchHead);
  assert.equal(i4.summary.notApplicableCount, 4);
  assert.equal(i4.summary.releaseBlocked, true);
  requireGitleaksTestExpressionTriage(result.e4, i4);
});


test('canonical hygiene job imports the public-commit adversarial suite through the existing scanner boundary', () => {
  assert.match(read('.github/workflows/approval-platform-validation.yml'), /node --test scripts\/tests\/m6-ai-transport-review-boundary\.test\.mjs/);
  assert.match(read('scripts/tests/m6-ai-transport-review-boundary.test.mjs'), /import '\.\/m6-pr-e-e4-scanner-boundary\.test\.mjs';/);
  assert.match(read('scripts/tests/m6-pr-e-e4-scanner-boundary.test.mjs'), /import '\.\/m6-pr-e-e3-gitleaks-public-commit-review\.test\.mjs';/);
});

test('serialized I2 public review cannot change dispositions, source metadata, counters or omit its receipt', () => {
  const e4 = currentE4(), i2 = applyGitleaksTestExpressionReview(currentI2(e4), e4, fullSnapshot(e4));
  for (const mutate of [
    triage => { delete triage.gitleaksPublicCommitReview; },
    triage => { triage.appendOnlyReviewedFindingCount = 1; },
    triage => { triage.currentReviewedFindingCount++; },
    triage => { triage.summary.releaseBlocked = false; },
    triage => { triage.decisions.find(item => item.findingId === fixture.finding.findingId).sourceIdentity.path = 'unknown'; },
    triage => { triage.decisions.find(item => item.findingId === fixture.finding.findingId).disposition = 'MITIGATED'; },
    triage => { triage.decisions.find(item => item.findingId === fixture.finding.findingId).reviewEvidence.exploitPreconditionsSatisfied = true; },
  ]) {
    const changed = structuredClone(i2); mutate(changed);
    assert.throws(() => requireGitleaksTestExpressionTriage(e4, seal(changed)), /Gitleaks/);
  }
});
