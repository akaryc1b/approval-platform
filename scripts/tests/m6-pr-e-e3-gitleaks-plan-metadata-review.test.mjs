import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { syntheticE4, snapshot as prototypeSnapshot, i2Inputs, runSyntheticCiCallback }
  from './m6-pr-e-e3-designer-prototype-remediation.test.mjs';
import { modeledCaptureHashFixture } from './fixtures/capture-hash-review-fixture.mjs';
import { canonical, seal } from './fixtures/public-commit-review-fixture.mjs';
import { readGitleaksCaptureHashReviewPlan, verifyGitleaksCaptureHashReview, requireGitleaksCaptureHashReviewReceipt,
  gitleaksPlanMetadataFields, gitleaksCaptureReviewFindings, applyGitleaksCaptureHashDecisions }
  from '../security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
import { buildGitleaksPublicCommitReviewSnapshot, verifyGitleaksPublicCommitReview, requireGitleaksPublicCommitReviewReceipt } from '../security/m6-pr-e-e3-review-gitleaks-public-commit.mjs';
import { readGitleaksTestExpressionReviewPlan, verifyGitleaksTestExpressionReview, requireGitleaksTestExpressionReviewReceipt, applyGitleaksTestExpressionReview, requireGitleaksTestExpressionTriage }
  from '../security/m6-pr-e-e3-review-gitleaks-test-expression.mjs';
import { buildScannerFindingIntake } from '../security/m6-pr-e-e3-ingest-e4.mjs';
import { applyReviewedFindingsWithIdentityTransitions } from '../security/m6-pr-e-e3-apply-reviewed-findings-with-identity-transitions.mjs';
import { reconcileScannerFindingIdentities } from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url)), plan = readGitleaksCaptureHashReviewPlan();
const planPath = 'docs/m6/m6-pr-e-e3-gitleaks-capture-hash-review.json';
const model = modeledCaptureHashFixture('typed plan metadata publication', { planMetadata: true });
const hash = value => createHash('sha256').update(value).digest('hex');
const read = path => readFileSync(`${root}${path}`, 'utf8');
const json = suffix => JSON.parse(read(`docs/m6/m6-pr-e-e3-${suffix}.json`));
const expression = readGitleaksTestExpressionReviewPlan();
const historical = spawnSync('git', ['show', `${expression.historicalSource.commitSha}:${expression.historicalSource.path}`], { cwd: root, encoding: 'utf8' });
assert.equal(historical.status, 0, historical.stderr);
const e4Fixture = () => model.prepareEvidence(syntheticE4());
const reseal = e4 => {
  e4.scanners.gitleaks.findingCount = e4.scanners.gitleaks.findings.length;
  e4.totalFindingCount = Object.values(e4.scanners).reduce((sum, scanner) => sum + scanner.findingCount, 0); return seal(e4);
};
const snapshotFor = (e4, proof) => ({ repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256, proof });
const fullSnapshot = (e4, fixture = model) => {
  const captureHashReviewSnapshot = fixture.snapshot(e4), captureHashReview = verifyGitleaksCaptureHashReview(e4, captureHashReviewSnapshot);
  return { repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
    historicalSource: { ...expression.historicalSource, content: historical.stdout }, captureHashReviewSnapshot,
    publicCommitReviewSnapshot: buildGitleaksPublicCommitReviewSnapshot(e4, { root, git: fixture.git, captureHashReview }) };
};
const currentI2 = e4 => applyReviewedFindingsWithIdentityTransitions(buildScannerFindingIntake(e4, { snapshotTime: '2026-10-10T00:00:00Z' }),
  json('i2-reviewed-findings'), json('r2b-semgrep-finding-identity-transition'), i2Inputs(e4));
const reidentify = finding => {
  finding.fingerprint = `${finding.commit}:${finding.path}:${finding.ruleId}:${finding.startLine}`;
  finding.findingId = hash(['GITLEAKS', finding.fingerprint, finding.ruleId, finding.path, String(finding.startLine)].join('\0'));
};

test('38 fixed pointer roles derive from the unchanged plan without copied finding records', () => {
  const fields = gitleaksPlanMetadataFields(); assert.equal(fields.length, 38);
  const counts = {}; for (const field of fields) counts[field.role] = (counts[field.role] || 0) + 1;
  assert.deepEqual(counts, { ACCEPTED_BASE_COMMIT: 1, ACCEPTED_BASE_TREE: 1, ACCEPTED_BASE_COMMIT_URL: 1,
    ACCEPTED_EXTERNAL_SCANNER_SOURCE_METADATA: 1, ACCEPTED_INVENTORY_COMMIT_METADATA: 29, CAPTURE_SOURCE_BLOB: 1,
    CAPTURE_COLLECTOR_BLOB: 1, ACCEPTED_INPUT_BLOB: 3 });
  assert.deepEqual(fields.map(field => field.line), [4, 5, 6, 17, ...Array.from({ length: 29 }, (_, index) => 22 + index * 11), 343, 349, 366, 377, 388]);
  assert.equal(hash(read(planPath)), '233be0fe354400bc0420cd951335f97a68ebc2709a958fc065b1af8bdbbae572');
});

test('full 70 evidence composes one source-bound receipt with distinct 3 and 38 finding groups', () => {
  const e4 = e4Fixture(), before = canonical(e4), source = fullSnapshot(e4), receipt = verifyGitleaksCaptureHashReview(e4, source.captureHashReviewSnapshot);
  assert.equal(receipt.schemaVersion, 'M6_PR_E_E3_GITLEAKS_CAPTURE_HASH_REVIEW_EVIDENCE_V2');
  assert.equal(receipt.currentFindingCount, 70); assert.equal(receipt.findings.length, 3);
  assert.equal(receipt.planMetadataReview.findings.length, 38); assert.equal(gitleaksCaptureReviewFindings(receipt).length, 41);
  assert.equal(receipt.planMetadataReview.externalScannerProvenance, 'ACCEPTED_BASELINE_METADATA_ONLY_NO_UPSTREAM_OBJECT_TAG_OR_REPRODUCIBLE_BUILD_CLAIM');
  assert.deepEqual(receipt.planMetadataReview.acceptedInventoryProvenance, { acceptedE4CanonicalSha256: plan.acceptedE4CanonicalSha256,
    retainedFindingCount: 29, retainedFindingsSha256: hash(canonical(plan.retainedFindings)),
    basis: 'EXACT_ACCEPTED_INVENTORY_METADATA_NO_INDEPENDENT_COMMIT_OBJECT_REVALIDATION' });
  assert.equal(Object.hasOwn(receipt.proof.planMetadata, 'retainedCommitObjects'), false);
  assert.deepEqual(requireGitleaksCaptureHashReviewReceipt(e4, receipt), receipt); assert.equal(canonical(e4), before);
  const i2 = applyGitleaksTestExpressionReview(currentI2(e4), e4, source); requireGitleaksTestExpressionTriage(e4, i2);
  assert.equal(i2.currentReviewedFindingCount, 45); assert.equal(i2.appendOnlyReviewedFindingCount, 43);
  assert.equal(i2.decisions.filter(item => item.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-PLAN-METADATA').length, 38);
  const reconciliation = reconcileScannerFindingIdentities(e4, prototypeSnapshot(e4), source);
  assert.equal(reconciliation.currentScannerCounts.gitleaks, 70); assert.equal(reconciliation.reviewedAddedGitleaksFindingCount, 43);
  assert.equal(reconciliation.retainedHistoricalGitleaksFindingCount, 27); assert.equal(reconciliation.releaseBlocked, true);
});

test('32 to 70 transition preserves all original 32 decisions and rationale except E4 receipt rebinding', () => {
  const e4 = e4Fixture(), current = applyGitleaksTestExpressionReview(currentI2(e4), e4, fullSnapshot(e4));
  const prior = structuredClone(e4); prior.scanners.gitleaks.findings = prior.scanners.gitleaks.findings.filter(finding => finding.path !== planPath);
  const e32 = reseal(prior), before = applyGitleaksTestExpressionReview(currentI2(e32), e32, fullSnapshot(e32));
  const withoutReceipt = value => { value = structuredClone(value); if (value.reviewEvidence) delete value.reviewEvidence.reviewCanonicalSha256; return value; };
  const retained = before.decisions.filter(item => item.sourceClass === 'E4_GITLEAKS'); assert.equal(retained.length, 32);
  for (const row of retained) assert.deepEqual(withoutReceipt(current.decisions.find(item => item.findingId === row.findingId)), withoutReceipt(row));
  assert.equal(current.summary.unresolvedCount, before.summary.unresolvedCount);
});

test('legacy 32 capture, public, expression, I2 and reconciliation outputs preserve accepted canonical bytes', () => {
  // Hashes were produced from the byte-verified accepted implementation and its
  // complete modeled import/data closure. No finding table is copied here.
  const expected = JSON.parse(read('scripts/tests/fixtures/gitleaks-capture-32-canonical.json'));
  const fixture = modeledCaptureHashFixture(), e4 = fixture.prepareEvidence(syntheticE4()), source = fullSnapshot(e4, fixture);
  const captureReceipt = verifyGitleaksCaptureHashReview(e4, source.captureHashReviewSnapshot);
  const outputs = { captureReceipt,
    publicReceipt: verifyGitleaksPublicCommitReview(e4, source.publicCommitReviewSnapshot, captureReceipt),
    expressionReceipt: verifyGitleaksTestExpressionReview(e4, source),
    i2: applyGitleaksTestExpressionReview(currentI2(e4), e4, source),
    reconciliation: reconcileScannerFindingIdentities(e4, prototypeSnapshot(e4), source) };
  assert.deepEqual(Object.fromEntries(Object.entries(outputs).map(([name, value]) => [name, hash(canonical(value))])), expected);
});

for (const [label, mutate] of [
  ['missing prior finding', e4 => { e4.scanners.gitleaks.findings = e4.scanners.gitleaks.findings.filter(f => f.findingId !== plan.retainedFindings[0].findingId); }],
  ['prior metadata drift', e4 => { e4.scanners.gitleaks.findings.find(f => f.findingId === plan.retainedFindings[0].findingId).description = 'unreviewed'; }],
  ['missing metadata finding', e4 => { e4.scanners.gitleaks.findings = e4.scanners.gitleaks.findings.filter(f => f.findingId !== model.planMetadataFindings[0].findingId); }],
  ['extra metadata finding', e4 => { e4.scanners.gitleaks.findings.push({ ...model.planMetadataFindings[0], findingId: hash('unreviewed extra') }); }],
  ['duplicate metadata finding', e4 => { e4.scanners.gitleaks.findings.push(model.planMetadataFindings[0]); }],
  ['reordered inventory', e4 => { e4.scanners.gitleaks.findings.reverse(); }],
]) test(`metadata proof rejects ${label} with resealed full inventory`, () => {
  let e4 = e4Fixture(); const proof = model.snapshot(e4).proof; mutate(e4); e4 = reseal(e4);
  assert.throws(() => verifyGitleaksCaptureHashReview(e4, snapshotFor(e4, proof)), /Gitleaks|E4/);
});
for (const field of ['commit', 'path', 'ruleId', 'description', 'startLine', 'endLine', 'fingerprint', 'findingId', 'sourceClass']) test(`metadata proof rejects exact new record ${field} drift`, () => {
  let e4 = e4Fixture(); const proof = model.snapshot(e4).proof, finding = e4.scanners.gitleaks.findings.find(row => row.findingId === model.planMetadataFindings[0].findingId);
  finding[field] = typeof finding[field] === 'number' ? finding[field] + 1 : 'unreviewed'; e4 = reseal(e4);
  assert.throws(() => verifyGitleaksCaptureHashReview(e4, snapshotFor(e4, proof)), /Gitleaks|E4/);
});
for (const [label, mutate] of [
  ['missing metadata proof', proof => { delete proof.planMetadata; }],
  ['missing current plan path', proof => { proof.planMetadata.source.currentPath = []; }],
  ['missing introduced plan path', proof => { proof.planMetadata.source.introductionPath = []; }],
  ['missing base absence', proof => { proof.planMetadata.baseSourcePath = []; }],
  ['missing parent absence', proof => { proof.planMetadata.introductionParents = []; }],
  ['wrong parent', proof => { proof.planMetadata.introductionParents[0].commit = proof.planMetadata.commits[0]; }],
  ['missing plan introduction', proof => { proof.planMetadata.commits.splice(1, 1); }],
  ['reordered plan ancestry', proof => { proof.planMetadata.commits.reverse(); }],
  ['unapproved historical commit preimages', proof => { proof.planMetadata.retainedCommitObjects = proof.planMetadata.commits; }],
  ['missing accepted scanner baseline', proof => { delete proof.planMetadata.scannerBaseline; }],
  ['missing accepted baseline path', proof => { proof.planMetadata.scannerBaseline.basePath = []; }],
  ['unanchored vendor source metadata', proof => { const value = JSON.parse(Buffer.from(proof.planMetadata.scannerBaseline.contentBase64, 'base64')); value.scanners.gitleaks.sourceCommit = 'a'.repeat(40); proof.planMetadata.scannerBaseline.contentBase64 = Buffer.from(JSON.stringify(value)).toString('base64'); }],
  ['unrelated key with an authentic Git identity', proof => { const value = JSON.parse(Buffer.from(proof.planMetadata.source.contentBase64, 'base64')); value.unrelated = value.acceptedPublicBaseCommit; proof.planMetadata.source.contentBase64 = Buffer.from(`${JSON.stringify(value, null, 2)}\n`).toString('base64'); }],
  ['changed plan and recomputed self digest', proof => { const value = JSON.parse(Buffer.from(proof.planMetadata.source.contentBase64, 'base64')); value.retainedFindings[0].commit = value.acceptedPublicBaseCommit; proof.planMetadata.source.contentBase64 = Buffer.from(`${JSON.stringify(seal(value), null, 2)}\n`).toString('base64'); }],
  ['extra proof assertion', proof => { proof.planMetadata.verified = true; }],
]) test(`metadata receipt rejects ${label} even when resealed`, () => {
  const e4 = e4Fixture(), receipt = verifyGitleaksCaptureHashReview(e4, model.snapshot(e4)); mutate(receipt.proof);
  assert.throws(() => requireGitleaksCaptureHashReviewReceipt(e4, seal(receipt)), /Gitleaks/);
});
for (const [label, mutate] of [
  ['wrong pointer', value => { value.fields[0].jsonPointer = '/unrelated'; }],
  ['wrong role', value => { value.fields[0].role = 'ANY_HASH'; }],
  ['wrong line', value => { value.fields[0].line++; }],
  ['duplicated role', value => { value.fields[1] = value.fields[0]; }],
  ['wrong retained inventory digest', value => { value.acceptedInventoryProvenance.retainedFindingsSha256 = 'a'.repeat(64); }],
  ['wrong retained inventory count', value => { value.acceptedInventoryProvenance.retainedFindingCount++; }],
  ['wrong accepted E4 binding', value => { value.acceptedInventoryProvenance.acceptedE4CanonicalSha256 = 'a'.repeat(64); }],
  ['unproved retained object claim', value => { value.acceptedInventoryProvenance.basis = 'REPLAYED_COMMIT_OBJECTS'; }],
  ['wrong vendor provenance claim', value => { value.externalScannerProvenance = 'UPSTREAM_BUILD_VERIFIED'; }],
  ['missing findings', value => { value.findings.pop(); }],
  ['wrong disposition', value => { value.decision.disposition = 'MITIGATED'; }],
]) test(`nested metadata receipt rejects ${label}`, () => {
  const e4 = e4Fixture(), receipt = verifyGitleaksCaptureHashReview(e4, model.snapshot(e4)); mutate(receipt.planMetadataReview);
  receipt.planMetadataReview = seal(receipt.planMetadataReview);
  assert.throws(() => requireGitleaksCaptureHashReviewReceipt(e4, seal(receipt)), /receipt drift/);
});

test('later-copy and nonexistent metadata introducers cannot reuse the pinned plan', () => {
  for (const commit of [model.head, 'e'.repeat(40)]) {
    let e4 = e4Fixture(); const proof = model.snapshot(e4).proof;
    for (const finding of e4.scanners.gitleaks.findings.filter(row => row.path === planPath)) { finding.commit = commit; reidentify(finding); }
    e4.scanners.gitleaks.findings.sort((a, b) => a.findingId.localeCompare(b.findingId)); e4 = reseal(e4);
    assert.throws(() => verifyGitleaksCaptureHashReview(e4, snapshotFor(e4, proof)), /Gitleaks/);
  }
});

test('metadata decision application and serialized triage reject duplicate, missing or altered reviews', () => {
  const e4 = e4Fixture(), source = fullSnapshot(e4), receipt = verifyGitleaksCaptureHashReview(e4, source.captureHashReviewSnapshot);
  const decisions = currentI2(e4).decisions.filter(row => gitleaksCaptureReviewFindings(receipt).some(finding => finding.findingId === row.findingId));
  assert.throws(() => applyGitleaksCaptureHashDecisions([...decisions.slice(1), decisions[1]], receipt), /duplicate/);
  assert.throws(() => applyGitleaksCaptureHashDecisions(decisions.slice(1), receipt), /current decisions/);
  const triage = applyGitleaksTestExpressionReview(currentI2(e4), e4, source);
  for (const mutate of [
    value => { delete value.gitleaksCaptureHashReview.planMetadataReview; },
    value => { value.appendOnlyReviewedFindingCount = 5; },
    value => { value.decisions.find(row => row.findingId === model.planMetadataFindings[0].findingId).reviewEvidence.metadataRole = 'ANY_HASH'; },
    value => { value.decisions.find(row => row.findingId === model.planMetadataFindings[0].findingId).reviewEvidence.reviewCanonicalSha256 = 'a'.repeat(64); },
    value => { value.decisions.find(row => row.findingId === model.planMetadataFindings[0].findingId).disposition = 'UNRESOLVED'; },
    value => { value.decisions.find(row => row.findingId === plan.retainedFindings[0].findingId).sourceIdentity.path = 'unrelated'; },
  ]) { const changed = structuredClone(triage); mutate(changed); assert.throws(() => requireGitleaksTestExpressionTriage(e4, seal(changed)), /Gitleaks/); }
});

test('modeled full70 CI retains exactly13 outputs and unchanged historical remediation credit', () => {
  const fixture = modeledCaptureHashFixture('metadata main merge', { planMetadata: true, mergeHead: true });
  const result = runSyntheticCiCallback({ publicCommitFixture: fixture }); assert.equal(result.error, undefined);
  const blocks = result.logs.filter(text => /^M6_PR_E_.*_BEGIN\n/.test(text)); assert.equal(blocks.length, 14); // One scanner subprocess envelope plus13 chain outputs.
  const output = stage => JSON.parse(result.logs.find(text => text.startsWith(`M6_PR_E_${stage}_BEGIN\n`)).split('\n')[1]);
  for (const stage of ['E3_I2_TRIAGE', 'E3_I3_TRIAGE', 'E3_I4_TRIAGE']) {
    const triage = output(stage); assert.equal(triage.decisions.filter(row => row.sourceClass === 'E4_GITLEAKS').length, 70);
    requireGitleaksTestExpressionTriage(result.e4, triage); assert.equal(triage.appendOnlyReviewedFindingCount, 43); assert.equal(triage.summary.releaseBlocked, true);
  }
  assert.equal(output('E3_I4_TRIAGE').summary.notApplicableCount, 45);
  assert.equal(output('E3_I4_TRIAGE').cumulativeReviewedFindingCount, 111);
  assert.equal(output('E3_I4_TRIAGE').historicallyRemediatedFindingCount, 66);
  assert.equal(output('E3_R2B_REMEDIATION').scannerIdentityReconciliation.currentScannerCounts.gitleaks, 70);
  const replay = value => {
    const capture = requireGitleaksCaptureHashReviewReceipt(result.e4, value.gitleaksCaptureHashReview);
    const publicReview = requireGitleaksPublicCommitReviewReceipt(result.e4, value.gitleaksPublicCommitReview, capture);
    return requireGitleaksTestExpressionReviewReceipt(result.e4, value.gitleaksTestExpressionReview, publicReview, capture);
  };
  for (const value of [output('E3_I2_TRIAGE'), output('E3_I3_TRIAGE'), output('E3_I4_TRIAGE'),
    output('E3_R2B_REMEDIATION').scannerIdentityReconciliation]) {
    replay(value);
    for (const mutate of [
      changed => { changed.gitleaksCaptureHashReview.planMetadataReview.fields[0].role = 'ANY_HASH'; changed.gitleaksCaptureHashReview.planMetadataReview = seal(changed.gitleaksCaptureHashReview.planMetadataReview); changed.gitleaksCaptureHashReview = seal(changed.gitleaksCaptureHashReview); },
      changed => { changed.gitleaksPublicCommitReview.sourceCaptureHashReviewCanonicalSha256 = 'a'.repeat(64); changed.gitleaksPublicCommitReview = seal(changed.gitleaksPublicCommitReview); },
      changed => { changed.gitleaksTestExpressionReview.sourceE4CanonicalSha256 = 'a'.repeat(64); changed.gitleaksTestExpressionReview = seal(changed.gitleaksTestExpressionReview); },
      changed => { changed.gitleaksCaptureHashReview.planMetadataReview.sourceProofCanonicalSha256 = 'a'.repeat(64); changed.gitleaksCaptureHashReview.planMetadataReview = seal(changed.gitleaksCaptureHashReview.planMetadataReview); changed.gitleaksCaptureHashReview = seal(changed.gitleaksCaptureHashReview); },
    ]) { const changed = structuredClone(value); mutate(changed); assert.throws(() => replay(changed), /Gitleaks|receipt/); }
  }
});
