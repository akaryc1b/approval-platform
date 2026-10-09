import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { syntheticE4, snapshot, i2Inputs } from './m6-pr-e-e3-designer-prototype-remediation.test.mjs';
import { readGitleaksTestExpressionReviewPlan, verifyGitleaksTestExpressionReview,
  applyGitleaksTestExpressionReview, requireGitleaksTestExpressionReviewReceipt, requireGitleaksTestExpressionTriage }
  from '../security/m6-pr-e-e3-review-gitleaks-test-expression.mjs';
import { buildScannerFindingIntake } from '../security/m6-pr-e-e3-ingest-e4.mjs';
import { applyReviewedFindingsWithIdentityTransitions } from '../security/m6-pr-e-e3-apply-reviewed-findings-with-identity-transitions.mjs';
import { reconcileScannerFindingIdentities } from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs';
import { applyRuntimeDeploymentReviews } from '../security/m6-pr-e-e3-apply-runtime-deployment-reviews.mjs';
import { verifyPgjdbcRemediation } from '../security/m6-pr-e-e3-verify-pgjdbc-remediation.mjs';

const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: hash(canonical(payload)) }; };
const plan = readGitleaksTestExpressionReviewPlan();
const root = fileURLToPath(new URL('../../', import.meta.url));
const read = path => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8');
const json = suffix => JSON.parse(read(`docs/m6/m6-pr-e-e3-${suffix}.json`));
const sourceResult = spawnSync('git', ['show', `${plan.historicalSource.commitSha}:${plan.historicalSource.path}`], { cwd: root, encoding: 'utf8' });
assert.equal(sourceResult.status, 0, sourceResult.stderr);
const historicalContent = sourceResult.stdout;

function currentE4() {
  const e4 = syntheticE4();
  e4.scanners.gitleaks = { ...e4.scanners.gitleaks, ...plan.scannerIdentity,
    findings: [...e4.scanners.gitleaks.findings, structuredClone(plan.historicalFinding)], findingCount: 28 };
  e4.totalFindingCount++;
  return seal(e4);
}
function reviewSnapshot(e4) {
  return { repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
    historicalSource: { ...plan.historicalSource, content: historicalContent } };
}
function currentI2(e4) {
  const intake = buildScannerFindingIntake(e4, { snapshotTime: '2026-10-08T00:00:00Z' });
  return applyReviewedFindingsWithIdentityTransitions(intake, json('i2-reviewed-findings'),
    json('r2b-semgrep-finding-identity-transition'), i2Inputs(e4));
}

test('append-only Gitleaks plan binds the observed finding and exact historical fixed-expression source', () => {
  assert.equal(plan.contentSha256, '20158102576d7ba5931d0605bdd1aab548ec94db571b31a2bc5a20032f673c5d');
  assert.equal(plan.reviewBasisHead, 'a3271c7f60f6afe259f521bc27fe64caf87338a5');
  assert.equal(plan.reviewBasisE4CanonicalSha256, '61409f8a66f2201c91cc054c7b6ebd84ee6043267675d56d02b14f46a1abcbd8');
  assert.equal(hash(historicalContent), plan.historicalSource.sha256);
  assert.equal(hash(historicalContent.split('\n')[244]), plan.historicalSource.lineSha256);
  const tokenLikeMember = [plan.reviewedExpression.receiverIdentifier, plan.reviewedExpression.propertyIdentifier].join('.');
  assert.ok(historicalContent.split('\n')[244].includes(tokenLikeMember));
  assert.equal(read(plan.historicalSource.path).includes(tokenLikeMember), false);
  assert.equal(read('docs/m6/m6-pr-e-e3-gitleaks-test-expression-review.json').includes(tokenLikeMember), false);
  assert.equal(plan.decision.disposition, 'NOT_APPLICABLE');
  assert.equal(plan.decision.findingRetained, true);
});

test('source-bound review retains all 28 current Gitleaks findings and adds only the exact NOT_APPLICABLE decision', () => {
  const e4 = currentE4(), before = currentI2(e4), after = applyGitleaksTestExpressionReview(before, e4, reviewSnapshot(e4));
  const finding = after.decisions.find(item => item.findingId === plan.historicalFinding.findingId);
  assert.equal(finding.disposition, 'NOT_APPLICABLE');
  assert.equal(finding.severityBand, 'UNKNOWN');
  assert.deepEqual(after.decisions.map(item => item.findingId), before.decisions.map(item => item.findingId));
  assert.equal(after.currentReviewedFindingCount, 3);
  assert.equal(after.historicalReviewedFindingCount, 3);
  assert.equal(after.appendOnlyReviewedFindingCount, 1);
  assert.equal(after.summary.dispositionCounts.NOT_APPLICABLE, 3);
  assert.equal(after.summary.unresolvedCount, 170);
  const reconciliation = reconcileScannerFindingIdentities(e4, snapshot(e4), reviewSnapshot(e4));
  assert.equal(reconciliation.currentScannerCounts.gitleaks, 28);
  assert.equal(reconciliation.retainedHistoricalGitleaksFindingCount, 27);
  assert.equal(reconciliation.reviewedAddedGitleaksFindingCount, 1);
  assert.equal(reconciliation.totalFindingCount, 173);
  const i3 = applyRuntimeDeploymentReviews(after, e4, json('i3-reviewed-findings'), verifyPgjdbcRemediation(e4, json('r1-pgjdbc-remediation')));
  assert.equal(i3.cumulativeReviewedFindingCount, 8);
  assert.equal(i3.summary.notApplicableCount, 4);
  assert.equal(i3.summary.unresolvedCount, 169);
  assert.deepEqual(i3.gitleaksTestExpressionReview, after.gitleaksTestExpressionReview);
  assert.equal(i3.historicallyRemediatedFindingCount, 3);
});

test('the new historical finding cannot bypass strict reconciliation without its source proof', () => {
  const e4 = currentE4();
  assert.throws(() => reconcileScannerFindingIdentities(e4, snapshot(e4)), /Gitleaks identity-set drift/);
  assert.throws(() => verifyGitleaksTestExpressionReview(e4, {}), /exact current E4 binding/);
});
for (const field of ['commitSha', 'path', 'blobSha', 'content']) test(`Gitleaks source review rejects historical ${field} drift`, () => {
  const e4 = currentE4(), source = reviewSnapshot(e4);
  Object.defineProperty(source.historicalSource, field, { value: `${source.historicalSource[field]}changed` });
  assert.throws(() => verifyGitleaksTestExpressionReview(e4, source), /historical source blob mismatch/);
});
for (const field of ['commit', 'path', 'ruleId', 'fingerprint', 'startLine', 'endLine', 'findingId']) {
  test(`Gitleaks review rejects rehashed ${field} drift on the same count`, () => {
    let e4 = currentE4();
    Object.defineProperty(e4.scanners.gitleaks.findings.at(-1), field, { value: `unreviewed-${field}` });
    e4 = seal(e4);
    assert.throws(() => verifyGitleaksTestExpressionReview(e4, reviewSnapshot(e4)), /Gitleaks/);
  });
}
for (const field of Object.keys(plan.scannerIdentity)) test(`Gitleaks review rejects unreviewed scanner ${field}`, () => {
  let e4 = currentE4();
  Object.defineProperty(e4.scanners.gitleaks, field, { value: 'unreviewed' }); e4 = seal(e4);
  assert.throws(() => verifyGitleaksTestExpressionReview(e4, reviewSnapshot(e4)), /scanner identity drift|redaction required|scanner evidence required/);
});
for (const mode of ['additional', 'removed historical', 'duplicate']) test(`Gitleaks review rejects ${mode} identity without dropping it`, () => {
  let e4 = currentE4();
  if (mode === 'additional') e4.scanners.gitleaks.findings.push({ ...plan.historicalFinding, findingId: hash('unreviewed finding') });
  if (mode === 'removed historical') e4.scanners.gitleaks.findings.shift();
  if (mode === 'duplicate') e4.scanners.gitleaks.findings.push(plan.historicalFinding);
  e4.scanners.gitleaks.findingCount = e4.scanners.gitleaks.findings.length;
  e4.totalFindingCount = 145 + e4.scanners.gitleaks.findingCount;
  e4 = seal(e4);
  assert.throws(() => verifyGitleaksTestExpressionReview(e4, reviewSnapshot(e4)), /Gitleaks/);
});

test('a copied expression at any other commit/path/line is not covered by this review', () => {
  let e4 = currentE4();
  e4.scanners.gitleaks.findings.push({ ...plan.historicalFinding, findingId: hash('copy'), commit: 'f'.repeat(40), path: 'copy.mjs', startLine: 1 });
  e4.scanners.gitleaks.findingCount++; e4.totalFindingCount++; e4 = seal(e4);
  assert.throws(() => verifyGitleaksTestExpressionReview(e4, reviewSnapshot(e4)), /unreviewed addition/);
});

test('receipt/disposition tampering and repeat classification fail closed', () => {
  const e4 = currentE4(), source = reviewSnapshot(e4), i2 = applyGitleaksTestExpressionReview(currentI2(e4), e4, source);
  const receipt = structuredClone(i2.gitleaksTestExpressionReview); receipt.decision.disposition = 'UNRESOLVED';
  assert.throws(() => requireGitleaksTestExpressionReviewReceipt(e4, seal(receipt)), /receipt drift/);
  assert.throws(() => applyGitleaksTestExpressionReview(i2, e4, source), /only review the exact unresolved/);
  const changed = structuredClone(i2); changed.decisions.find(item => item.findingId === plan.historicalFinding.findingId).disposition = 'UNRESOLVED';
  assert.throws(() => applyRuntimeDeploymentReviews(seal(changed), e4, json('i3-reviewed-findings'),
    verifyPgjdbcRemediation(e4, json('r1-pgjdbc-remediation'))), /current disposition drift|summary drift/);
});

test('the reviewed current finding keeps its original source metadata and honest current-review counters', () => {
  const e4 = currentE4(), source = reviewSnapshot(e4);
  const identityDrift = currentI2(e4);
  identityDrift.decisions.find(item => item.findingId === plan.historicalFinding.findingId).sourceIdentity.path = 'other.mjs';
  assert.throws(() => applyGitleaksTestExpressionReview(seal(identityDrift), e4, source), /source identity drift/);
  const countDrift = seal({ ...currentI2(e4), reviewedFindingCount: 12, currentReviewedFindingCount: 12 });
  assert.throws(() => applyGitleaksTestExpressionReview(countDrift, e4, source), /prior current review count drift/);
});

for (const [name, alter] of [
  ['missing E4 schema', e => { delete e.schemaVersion; }],
  ['missing checkout', e => { delete e.checkout; }],
  ['mismatched checkout Head', e => { e.checkout.expectedHeadSha = 'f'.repeat(40); }],
  ['mismatched checkout tree', e => { e.checkout.checkedOutTreeSha = 'f'.repeat(40); }],
  ['dirty tracked source', e => { e.checkout.trackedWorktreeClean = false; }],
  ['incomplete Semgrep', e => { e.scanners.semgrep.scanCompleted = false; }],
  ['missing OSV', e => { delete e.scanners.osv; }],
  ['malformed zizmor count', e => { e.scanners.zizmor.findingCount++; }],
  ['wrong total', e => { e.totalFindingCount++; }],
  ['retained Semgrep source', e => { e.scanners.semgrep.sourceSnippetRetained = true; }],
]) test(`standalone Gitleaks verifier and receipt reject ${name} despite a rehashed E4`, () => {
  let e4 = currentE4(); const receipt = verifyGitleaksTestExpressionReview(e4, reviewSnapshot(e4));
  alter(e4); e4 = seal(e4);
  assert.throws(() => verifyGitleaksTestExpressionReview(e4, reviewSnapshot(e4)), /Gitleaks/);
  assert.throws(() => requireGitleaksTestExpressionReviewReceipt(e4, receipt), /Gitleaks/);
  assert.throws(() => reconcileScannerFindingIdentities(e4, null, reviewSnapshot(e4)), /Gitleaks|R2B/);
});

test('serialized Gitleaks reviewed triage cannot inflate current or cumulative review counters', () => {
  const e4 = currentE4(), i2 = applyGitleaksTestExpressionReview(currentI2(e4), e4, reviewSnapshot(e4));
  const r1 = verifyPgjdbcRemediation(e4, json('r1-pgjdbc-remediation'));
  for (const changed of [
    { ...i2, appendOnlyReviewedFindingCount: 2 },
    { ...i2, currentReviewedFindingCount: 5 },
    { ...i2, reviewedFindingCount: 5 },
  ]) assert.throws(() => applyRuntimeDeploymentReviews(seal(changed), e4, json('i3-reviewed-findings'), r1), /review count drift/);
  const i3 = applyRuntimeDeploymentReviews(i2, e4, json('i3-reviewed-findings'), r1);
  // The same public validator is used by I4 before it consumes the I3 counters.
  assert.throws(() => requireGitleaksTestExpressionTriage(e4, seal({ ...i3, cumulativeReviewedFindingCount: 100 })), /cumulative review count drift/);
});

test('serialized Gitleaks triage rejects wrong Head, missing current findings and duplicated remediation credit', () => {
  const e4 = currentE4(), i2 = applyGitleaksTestExpressionReview(currentI2(e4), e4, reviewSnapshot(e4));
  const i3 = applyRuntimeDeploymentReviews(i2, e4, json('i3-reviewed-findings'), verifyPgjdbcRemediation(e4, json('r1-pgjdbc-remediation')));
  assert.throws(() => requireGitleaksTestExpressionTriage(e4, seal({ ...i3, commitSha: 'f'.repeat(40) })), /exact Head mismatch/);
  assert.throws(() => requireGitleaksTestExpressionTriage(e4, seal({ ...i3, decisions: i3.decisions.slice(1) })), /retain every current identity/);
  const duplicated = [...i3.historicallyRemediatedFindings, i3.historicallyRemediatedFindings[0]];
  assert.throws(() => requireGitleaksTestExpressionTriage(e4, seal({ ...i3, historicallyRemediatedFindings: duplicated,
    historicallyRemediatedFindingCount: 4, cumulativeReviewedFindingCount: 9 })), /cumulative review count drift/);
});
