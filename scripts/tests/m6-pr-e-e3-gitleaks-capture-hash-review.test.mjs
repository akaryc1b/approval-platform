import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { syntheticE4, snapshot as prototypeSnapshot, i2Inputs, runSyntheticCiCallback }
  from './m6-pr-e-e3-designer-prototype-remediation.test.mjs';
import { modeledPublicCommitFixture, canonical, seal } from './fixtures/public-commit-review-fixture.mjs';
import { modeledCaptureHashFixture } from './fixtures/capture-hash-review-fixture.mjs';
import { readGitleaksCaptureHashReviewPlan, buildGitleaksCaptureHashReviewSnapshot, verifyGitleaksCaptureHashReview,
  requireGitleaksCaptureHashReviewReceipt, applyGitleaksCaptureHashDecisions }
  from '../security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
import { buildGitleaksPublicCommitReviewSnapshot, verifyGitleaksPublicCommitReview, requireGitleaksPublicCommitReviewReceipt }
  from '../security/m6-pr-e-e3-review-gitleaks-public-commit.mjs';
import { readGitleaksTestExpressionReviewPlan, verifyGitleaksTestExpressionReview, applyGitleaksTestExpressionReview,
  requireGitleaksTestExpressionTriage } from '../security/m6-pr-e-e3-review-gitleaks-test-expression.mjs';
import { buildScannerFindingIntake } from '../security/m6-pr-e-e3-ingest-e4.mjs';
import { applyReviewedFindingsWithIdentityTransitions } from '../security/m6-pr-e-e3-apply-reviewed-findings-with-identity-transitions.mjs';
import { applyRuntimeDeploymentReviews } from '../security/m6-pr-e-e3-apply-runtime-deployment-reviews.mjs';
import { verifyPgjdbcRemediation } from '../security/m6-pr-e-e3-verify-pgjdbc-remediation.mjs';
import { reconcileScannerFindingIdentities } from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url)), plan = readGitleaksCaptureHashReviewPlan();
const oldPlan = readGitleaksTestExpressionReviewPlan(), fixture = modeledCaptureHashFixture();
const hash = value => createHash('sha256').update(value).digest('hex');
const read = path => readFileSync(`${root}${path}`, 'utf8');
const json = suffix => JSON.parse(read(`docs/m6/m6-pr-e-e3-${suffix}.json`));
const gitText = args => { const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' }); assert.equal(result.status, 0, result.stderr); return result.stdout; };
const historicalContent = gitText(['show', `${oldPlan.historicalSource.commitSha}:${oldPlan.historicalSource.path}`]);
const currentE4 = () => fixture.prepareEvidence(syntheticE4());
const snapshotFor = (e4, proof) => ({ repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256, proof });
const fullSnapshot = (e4, model = fixture) => {
  const captureHashReviewSnapshot = model.snapshot(e4), captureHashReview = verifyGitleaksCaptureHashReview(e4, captureHashReviewSnapshot);
  return { repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
    historicalSource: { ...oldPlan.historicalSource, content: historicalContent }, captureHashReviewSnapshot,
    publicCommitReviewSnapshot: buildGitleaksPublicCommitReviewSnapshot(e4, { root, git: model.git, captureHashReview }) };
};
const currentI2 = e4 => applyReviewedFindingsWithIdentityTransitions(buildScannerFindingIntake(e4, { snapshotTime: '2026-10-10T00:00:00Z' }),
  json('i2-reviewed-findings'), json('r2b-semgrep-finding-identity-transition'), i2Inputs(e4));
const reseal = e4 => {
  e4.scanners.gitleaks.findingCount = e4.scanners.gitleaks.findings.length;
  e4.totalFindingCount = Object.values(e4.scanners).reduce((sum, scanner) => sum + scanner.findingCount, 0); return seal(e4);
};
const reidentify = finding => {
  finding.fingerprint = `${finding.commit}:${finding.path}:${finding.ruleId}:${finding.startLine}`;
  finding.findingId = hash(['GITLEAKS', finding.fingerprint, finding.ruleId, finding.path, String(finding.startLine)].join('\0'));
};

test('three exact capture fields recompute from accepted public-base source bytes without changing 32 observations', () => {
  const e4 = currentE4(), before = canonical(e4), source = fullSnapshot(e4);
  const capture = verifyGitleaksCaptureHashReview(e4, source.captureHashReviewSnapshot);
  assert.equal(capture.findings.length, 3); assert.equal(capture.currentFindingCount, 32); assert.equal(capture.retainedFindingCount, 29);
  assert.equal(capture.acceptedPublicBaseCommit, plan.acceptedPublicBaseCommit);
  assert.deepEqual(capture.observations.map(item => item.line), [158, 171, 173]);
  assert.equal(capture.releaseBlocked, true); assert.equal(capture.rawReportRetained, false);
  assert.deepEqual(requireGitleaksCaptureHashReviewReceipt(e4, capture), capture);
  const publicReview = verifyGitleaksPublicCommitReview(e4, source.publicCommitReviewSnapshot, capture);
  assert.equal(publicReview.finding.commit, '3dbb851db75ee3ba4e9e7c48a1858a983743fcc4');
  assert.equal(publicReview.sourceCaptureHashReviewCanonicalSha256, capture.contentSha256);
  requireGitleaksPublicCommitReviewReceipt(e4, publicReview, capture);
  assert.equal(canonical(e4), before);
});

for (const options of [{}, { mergeHead: true }, { mergeIntroduction: true }, { bundledIntroduction: true }]) {
  test(`hash-verified alternate publication works with ${JSON.stringify(options)}`, () => {
    const model = modeledCaptureHashFixture('different authenticated Git metadata', options), e4 = model.prepareEvidence(syntheticE4());
    const source = fullSnapshot(e4, model), capture = verifyGitleaksCaptureHashReview(e4, source.captureHashReviewSnapshot);
    assert.notEqual(model.intro, fixture.intro);
    assert.equal(capture.findings[0].commit, model.intro);
    verifyGitleaksTestExpressionReview(e4, source);
    assert.ok(model.commands.every(([command, args]) => command === 'git' && args[0] === 'cat-file'));
  });
}

test('I2 and R2B retain every old record/disposition with separate capture and Clean proofs', () => {
  const e4 = currentE4(), source = fullSnapshot(e4), before = currentI2(e4), after = applyGitleaksTestExpressionReview(before, e4, source);
  const reviewed = new Set([oldPlan.historicalFinding.findingId, after.gitleaksPublicCommitReview.finding.findingId,
    ...after.gitleaksCaptureHashReview.findings.map(finding => finding.findingId)]);
  assert.deepEqual(after.decisions.map(item => item.findingId), before.decisions.map(item => item.findingId));
  for (const item of before.decisions.filter(item => !reviewed.has(item.findingId))) assert.deepEqual(after.decisions.find(current => current.findingId === item.findingId), item);
  assert.equal(after.appendOnlyReviewedFindingCount, 5); assert.equal(after.currentReviewedFindingCount, 7);
  assert.equal(after.summary.unresolvedCount, before.summary.unresolvedCount - 5); requireGitleaksTestExpressionTriage(e4, after);
  const reconciled = reconcileScannerFindingIdentities(e4, prototypeSnapshot(e4), source);
  assert.equal(reconciled.retainedHistoricalGitleaksFindingCount, 27); assert.equal(reconciled.reviewedAddedGitleaksFindingCount, 5);
  assert.equal(reconciled.currentScannerCounts.gitleaks, 32); assert.deepEqual(reconciled.gitleaksCaptureHashReview, after.gitleaksCaptureHashReview);
  const r1 = verifyPgjdbcRemediation(e4, json('r1-pgjdbc-remediation'));
  const i3 = applyRuntimeDeploymentReviews(after, e4, json('i3-reviewed-findings'), r1);
  assert.deepEqual(i3.gitleaksCaptureHashReview, after.gitleaksCaptureHashReview); requireGitleaksTestExpressionTriage(e4, i3);
  for (const field of ['captureHashReviewSnapshot', 'publicCommitReviewSnapshot']) {
    const missing = structuredClone(source); delete missing[field];
    assert.throws(() => verifyGitleaksTestExpressionReview(e4, missing), /Gitleaks/);
    assert.throws(() => reconcileScannerFindingIdentities(e4, prototypeSnapshot(e4), missing), /Gitleaks/);
  }
});

test('29 to 32 transition preserves every prior disposition, source identity and rationale apart from current receipt digest rebinding', () => {
  const current = currentE4(), after = applyGitleaksTestExpressionReview(currentI2(current), current, fullSnapshot(current));
  const prior = reseal({ ...structuredClone(current), scanners: { ...structuredClone(current.scanners),
    gitleaks: { ...structuredClone(current.scanners.gitleaks), findings: structuredClone(plan.retainedFindings) } } });
  const source = { repository: prior.repository, commitSha: prior.commitSha, currentE4CanonicalSha256: prior.contentSha256,
    historicalSource: { ...oldPlan.historicalSource, content: historicalContent },
    publicCommitReviewSnapshot: buildGitleaksPublicCommitReviewSnapshot(prior, { root, git: fixture.git }) };
  const before = applyGitleaksTestExpressionReview(currentI2(prior), prior, source);
  const withoutCurrentReceiptDigest = decision => {
    const value = structuredClone(decision);
    if (value.reviewEvidence) delete value.reviewEvidence.reviewCanonicalSha256;
    return value;
  };
  const old = before.decisions.filter(item => item.sourceClass === 'E4_GITLEAKS'); assert.equal(old.length, 29);
  for (const decision of old) assert.deepEqual(withoutCurrentReceiptDigest(after.decisions.find(item => item.findingId === decision.findingId)), withoutCurrentReceiptDigest(decision));
});

for (const [name, mutate] of [
  ['missing old record', e4 => { e4.scanners.gitleaks.findings.shift(); }],
  ['missing new record', e4 => { e4.scanners.gitleaks.findings = e4.scanners.gitleaks.findings.filter(item => item.findingId !== fixture.findings[0].findingId); }],
  ['duplicate record', e4 => { e4.scanners.gitleaks.findings.push(e4.scanners.gitleaks.findings[0]); }],
  ['extra record', e4 => { e4.scanners.gitleaks.findings.push({ ...fixture.findings[0], findingId: hash('unreviewed fourth observation') }); }],
  ['old metadata drift', e4 => { e4.scanners.gitleaks.findings.find(item => item.findingId === plan.retainedFindings[0].findingId).description = 'changed'; }],
  ['reordered records', e4 => { e4.scanners.gitleaks.findings.reverse(); }],
  ['unreviewed scanner', e4 => { e4.scanners.gitleaks.version = '8.0.0'; }],
]) test(`capture review rejects ${name} after resealing`, () => {
  let e4 = currentE4(); const proof = fixture.snapshot(e4).proof; mutate(e4); e4 = reseal(e4);
  assert.throws(() => verifyGitleaksCaptureHashReview(e4, snapshotFor(e4, proof)), /Gitleaks|E4/);
});
for (const field of ['commit', 'path', 'ruleId', 'description', 'startLine', 'endLine', 'fingerprint', 'findingId', 'sourceClass']) {
  test(`capture review rejects exact observation ${field} drift`, () => {
    let e4 = currentE4(); const proof = fixture.snapshot(e4).proof, finding = e4.scanners.gitleaks.findings.find(item => item.findingId === fixture.findings[0].findingId);
    finding[field] = typeof finding[field] === 'number' ? finding[field] + 1 : 'unreviewed'; e4 = reseal(e4);
    assert.throws(() => verifyGitleaksCaptureHashReview(e4, snapshotFor(e4, proof)), /Gitleaks|E4/);
  });
}
for (const [name, mutate] of [
  ['arbitrary hexadecimal scalar', proof => { const value = JSON.parse(Buffer.from(proof.source.contentBase64, 'base64')); value.captureInputs[plan.observations[0].input.path] = 'a'.repeat(64); proof.source.contentBase64 = Buffer.from(`${JSON.stringify(value, null, 2)}\n`).toString('base64'); }],
  ['wrong JSON object', proof => { proof.source.contentBase64 = Buffer.from('[]\n').toString('base64'); }],
  ['copied hash in another field', proof => { const value = JSON.parse(Buffer.from(proof.source.contentBase64, 'base64')); value.otherInputs = value.captureInputs; delete value.captureInputs; proof.source.contentBase64 = Buffer.from(`${JSON.stringify(value, null, 2)}\n`).toString('base64'); }],
  ['wrong complete document bytes', proof => { proof.inputs[0].contentBase64 = Buffer.from('unreviewed document\n').toString('base64'); }],
  ['wrong input path', proof => { proof.inputs[0].path = 'docs/other.md'; }],
  ['wrong collector bytes', proof => { proof.collector.contentBase64 = Buffer.from('unreviewed collector\n').toString('base64'); }],
  ['wrong commit bytes', proof => { proof.commits[0].contentBase64 = Buffer.from('forged').toString('base64'); }],
  ['missing introduction', proof => { proof.commits.splice(1, 1); }],
  ['missing accepted base', proof => { proof.commits.pop(); }],
  ['duplicate ancestry', proof => { proof.commits.push(proof.commits[0]); }],
  ['wrong parent', proof => { proof.introductionParents[0].commit = proof.commits[0]; }],
  ['missing parent absence', proof => { proof.introductionParents = []; }],
  ['base source absence omitted', proof => { proof.baseSourcePath = []; }],
  ['current source path omitted', proof => { proof.source.currentPath = []; }],
  ['introduction source path omitted', proof => { proof.source.introductionPath = []; }],
  ['collector current path omitted', proof => { proof.collector.currentPath = []; }],
  ['collector introduction path omitted', proof => { proof.collector.introductionPath = []; }],
  ['accepted source path omitted', proof => { proof.inputs[0].basePath = []; }],
  ['input introduction path omitted', proof => { proof.inputs[0].introductionPath = []; }],
  ['input current path omitted', proof => { proof.inputs[0].currentPath = []; }],
  ['input count drift', proof => { proof.inputs.pop(); }],
  ['self-asserted proof', proof => { proof.verified = true; }],
]) test(`capture receipt replay rejects ${name} even after resealing`, () => {
  const e4 = currentE4(), evidence = verifyGitleaksCaptureHashReview(e4, fixture.snapshot(e4)); mutate(evidence.proof);
  assert.throws(() => requireGitleaksCaptureHashReviewReceipt(e4, seal(evidence)), /Gitleaks/);
});

test('a later copy, unknown introduction and wrong scanned tree cannot use the reviewed source blob', () => {
  for (const commit of [fixture.head, 'e'.repeat(40)]) {
    let e4 = currentE4(); const proof = fixture.snapshot(e4).proof;
    for (const finding of e4.scanners.gitleaks.findings.filter(item => fixture.findings.some(expected => expected.findingId === item.findingId))) { finding.commit = commit; reidentify(finding); }
    e4.scanners.gitleaks.findings.sort((a, b) => a.findingId.localeCompare(b.findingId)); e4 = reseal(e4);
    assert.throws(() => verifyGitleaksCaptureHashReview(e4, snapshotFor(e4, proof)), /Gitleaks/);
  }
  let e4 = currentE4(); const proof = fixture.snapshot(e4).proof;
  e4.checkout.checkedOutTreeSha = e4.checkout.expectedHeadTreeSha = plan.acceptedPublicBaseTreeSha; e4 = reseal(e4);
  assert.throws(() => verifyGitleaksCaptureHashReview(e4, snapshotFor(e4, proof)), /current Git Head\/tree/);
});

test('all introduction parents must prove source absence', () => {
  const model = modeledCaptureHashFixture('two parents', { mergeIntroduction: true }), e4 = model.prepareEvidence(syntheticE4());
  const receipt = verifyGitleaksCaptureHashReview(e4, model.snapshot(e4)); assert.equal(receipt.proof.introductionParents.length, 2);
  receipt.proof.introductionParents.pop();
  assert.throws(() => requireGitleaksCaptureHashReviewReceipt(e4, seal(receipt)), /every introduction parent/);
});

test('decision application rejects duplicate and missing identities before returning triage', () => {
  const e4 = currentE4(), receipt = verifyGitleaksCaptureHashReview(e4, fixture.snapshot(e4));
  const decisions = currentI2(e4).decisions.filter(item => receipt.findings.some(finding => finding.findingId === item.findingId));
  assert.throws(() => applyGitleaksCaptureHashDecisions([decisions[0], decisions[0], decisions[0]], receipt), /duplicate/);
  assert.throws(() => applyGitleaksCaptureHashDecisions(decisions.slice(1), receipt), /exactly three/);
});
for (const field of ['decision', 'releaseBlocked', 'findings', 'source', 'observations', 'planCanonicalSha256']) test(`capture receipt rejects resealed ${field} drift`, () => {
  const e4 = currentE4(), evidence = verifyGitleaksCaptureHashReview(e4, fixture.snapshot(e4)); evidence[field] = null;
  assert.throws(() => requireGitleaksCaptureHashReviewReceipt(e4, seal(evidence)), /receipt drift/);
});

test('normal modeled CI callback keeps all 32 observations and separate proofs through I4', () => {
  const model = modeledCaptureHashFixture('normal CI merge', { mergeHead: true });
  const result = runSyntheticCiCallback({ publicCommitFixture: model }); assert.equal(result.error, undefined);
  assert.equal(result.commands.filter(([command]) => command === process.execPath).length, 1);
  const output = stage => JSON.parse(result.logs.find(text => text.startsWith(`M6_PR_E_${stage}_BEGIN\n`)).split('\n')[1]);
  const capture = output('E3_GITLEAKS_CAPTURE_HASH_REVIEW');
  for (const stage of ['E3_I2_TRIAGE', 'E3_I3_TRIAGE', 'E3_I4_TRIAGE']) {
    const triage = output(stage); assert.equal(triage.decisions.filter(item => item.sourceClass === 'E4_GITLEAKS').length, 32);
    assert.deepEqual(triage.gitleaksCaptureHashReview, capture); assert.equal(triage.appendOnlyReviewedFindingCount, 5);
    assert.equal(triage.summary.releaseBlocked, true); requireGitleaksTestExpressionTriage(result.e4, triage);
  }
  assert.equal(output('E3_I4_TRIAGE').summary.notApplicableCount, 7);
  assert.equal(output('E3_I4_TRIAGE').cumulativeReviewedFindingCount, 73);
  assert.equal(output('E3_I4_TRIAGE').historicallyRemediatedFindingCount, 66);
  assert.deepEqual(output('E3_R2B_REMEDIATION').scannerIdentityReconciliation.gitleaksCaptureHashReview, capture);
});

test('serialized I2 and runtime consumer reject capture receipt or disposition tampering', () => {
  const e4 = currentE4(), i2 = applyGitleaksTestExpressionReview(currentI2(e4), e4, fullSnapshot(e4));
  for (const mutate of [
    triage => { delete triage.gitleaksCaptureHashReview; },
    triage => { delete triage.gitleaksPublicCommitReview; },
    triage => { triage.appendOnlyReviewedFindingCount = 2; },
    triage => { triage.currentReviewedFindingCount++; },
    triage => { triage.summary.releaseBlocked = false; },
    triage => { triage.decisions.find(item => item.findingId === fixture.findings[0].findingId).sourceIdentity.path = 'wrong'; },
    triage => { triage.decisions.find(item => item.findingId === fixture.findings[0].findingId).disposition = 'MITIGATED'; },
    triage => { triage.decisions.find(item => item.findingId === fixture.findings[0].findingId).reviewEvidence.jsonPointer = '/other'; },
  ]) { const changed = structuredClone(i2); mutate(changed); assert.throws(() => requireGitleaksTestExpressionTriage(e4, seal(changed)), /Gitleaks/); }
  const missing = structuredClone(i2); delete missing.gitleaksCaptureHashReview; delete missing.gitleaksPublicCommitReview; delete missing.gitleaksTestExpressionReview;
  assert.throws(() => applyRuntimeDeploymentReviews(seal(missing), e4, json('i3-reviewed-findings'), verifyPgjdbcRemediation(e4, json('r1-pgjdbc-remediation'))), /all three source review receipts/);
});

test('accepted 29-only public and expression receipts and I2 remain byte-canonical identical', async () => {
  const modules = {};
  for (const name of ['public-commit', 'test-expression']) {
    const path = `scripts/security/m6-pr-e-e3-review-gitleaks-${name}.mjs`;
    const source = gitText(['show', `${plan.acceptedPublicBaseCommit}:${path}`])
      .replace(/from '\.\/([^']+)'/g, (_, relative) => `from '${pathToFileURL(`${root}scripts/security/${relative}`).href}'`)
      .replaceAll('import.meta.url', JSON.stringify(pathToFileURL(`${root}${path}`).href));
    modules[name] = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
  }
  const model = modeledPublicCommitFixture(), e4 = model.prepareEvidence(syntheticE4()), publicCommitReviewSnapshot = model.snapshot(e4);
  const snapshot = { repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
    historicalSource: { ...oldPlan.historicalSource, content: historicalContent }, publicCommitReviewSnapshot };
  assert.equal(canonical(verifyGitleaksPublicCommitReview(e4, publicCommitReviewSnapshot)), canonical(modules['public-commit'].verifyGitleaksPublicCommitReview(e4, publicCommitReviewSnapshot)));
  assert.equal(canonical(verifyGitleaksTestExpressionReview(e4, snapshot)), canonical(modules['test-expression'].verifyGitleaksTestExpressionReview(e4, snapshot)));
  const before = currentI2(e4);
  assert.equal(canonical(applyGitleaksTestExpressionReview(before, e4, snapshot)), canonical(modules['test-expression'].applyGitleaksTestExpressionReview(before, e4, snapshot)));
  const historical28 = structuredClone(e4);
  historical28.scanners.gitleaks.findings = historical28.scanners.gitleaks.findings.filter(item => item.findingId !== model.finding.findingId);
  const e4With28 = reseal(historical28), sourceWith28 = { repository: e4With28.repository, commitSha: e4With28.commitSha,
    currentE4CanonicalSha256: e4With28.contentSha256, historicalSource: { ...oldPlan.historicalSource, content: historicalContent } };
  assert.equal(canonical(verifyGitleaksTestExpressionReview(e4With28, sourceWith28)), canonical(modules['test-expression'].verifyGitleaksTestExpressionReview(e4With28, sourceWith28)));
  const before28 = currentI2(e4With28);
  assert.equal(canonical(applyGitleaksTestExpressionReview(before28, e4With28, sourceWith28)), canonical(modules['test-expression'].applyGitleaksTestExpressionReview(before28, e4With28, sourceWith28)));
});

test('read-only capture builder fails closed and hygiene imports the focused regressions', () => {
  assert.throws(() => buildGitleaksCaptureHashReviewSnapshot(currentE4(), { root, git: () => ({ status: 1 }) }), /Git commit read failed/);
  assert.match(read('scripts/tests/m6-pr-e-e4-scanner-boundary.test.mjs'), /import '\.\/m6-pr-e-e3-gitleaks-capture-hash-review\.test\.mjs';/);
});
