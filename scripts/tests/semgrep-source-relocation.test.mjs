import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import {
  applyReviewedFindingsWithIdentityTransitions,
  reconcileReviewedSemgrepIdentities,
  resolveReviewedSemgrepTransition,
} from '../security/m6-pr-e-e3-apply-reviewed-findings-with-identity-transitions.mjs';

const load = name => readFileSync(new URL(`../../${name}`, import.meta.url), 'utf8');
const planPath = 'docs/m6/m6-pr-e-e3-r2b-semgrep-finding-identity-transition.json';
const reviewPath = 'docs/m6/m6-pr-e-e3-i2-reviewed-findings.json';
const plan = JSON.parse(load(planPath));
const review = JSON.parse(load(reviewPath));
const prior = plan.transitions[0];
const content = load(prior.sourcePath);
const hash = value => createHash('sha256').update(value).digest('hex');
const blob = value => createHash('sha1').update(`blob ${Buffer.byteLength(value)}\0`).update(value).digest('hex');
const currentId = '92fd98f9f362f0924d7e6074ac636e73a23e672172c451273ad85f200b8460c5';
const sources = () => ({ [prior.sourcePath]: { content, blobSha: blob(content) } });
const expected = { findingCount: 3, findingSetSha256: 'ffb20a767ea90349941607465a3954309dcac285ae7199b08febfb2e7894813b' };
function scanner() {
  return { scanCompleted: true, rawReportRetained: false, sourceSnippetRetained: false,
    findingCount: 3, findings: review.reviewedFindings.map(item => item.findingId === prior.historicalFindingId
      ? { sourceClass: 'E4_SEMGREP', findingId: currentId, ruleId: prior.ruleId,
        path: prior.scannerPath, startLine: 83, startColumn: 394, endLine: 83, endColumn: 437,
        upstreamSeverity: 'WARNING', cwe: [], owasp: [], category: 'security' }
      : { sourceClass: item.sourceClass, findingId: item.findingId }) };
}
function intake(value = scanner()) {
  return { repository: review.repository, commitSha: 'a'.repeat(40), contentSha256: 'b'.repeat(64),
    decisions: value.findings.map(finding => ({
      sourceClass: finding.sourceClass, findingId: finding.findingId,
      severityBand: 'UNKNOWN', disposition: 'UNRESOLVED',
      sourceIdentity: { ...finding, path: finding.path?.replace(/^\/src\//u, '') },
    })) };
}
const apply = (value = intake(), currentSources = sources(), inputReview = review, inputPlan = plan) =>
  applyReviewedFindingsWithIdentityTransitions(value, inputReview, inputPlan, { currentSources });
const reconcile = (value = scanner(), currentSources = sources()) =>
  reconcileReviewedSemgrepIdentities(value, expected, review, plan, currentSources);

test('reviewed relocation binds the full real source and unchanged BOM function, not just a matching expression', () => {
  assert.equal(blob(content), 'e91064aac9a4d62115ec5a2bce864289a5d394e0');
  assert.equal(blob(load(planPath)), '015a876d95ee4a09094a8d39ba7cb84d5107c2a8');
  assert.equal(blob(load(reviewPath)), 'b81ad05f890e4ebabf6d3f669b51fb19af66189e');
  const result = resolveReviewedSemgrepTransition(plan, sources());
  assert.equal(result.transition.currentFindingId, currentId);
  assert.equal(result.transition.currentLocation.startLine, 83);
  assert.equal(hash(content.split('\n')[82]), prior.currentSourceLineSha256);
  assert.equal(result.sourceEvolution.priorFindingId, prior.currentFindingId);
  assert.equal(result.sourceEvolution.priorSourceBlobSha, prior.currentSourceBlobSha);
  const { contentSha256, ...payload } = result.sourceEvolution;
  // Canonicalization of nested objects is checked by a second independent sort.
  const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
  assert.equal(hash(JSON.stringify(stable(payload))), contentSha256);
});

test('I2 keeps actual current findings, semantic decisions, original plan identity and the additional lineage', () => {
  const value = intake(); const before = structuredClone(value);
  const result = apply(value);
  assert.deepEqual(value, before);
  assert.equal(result.identityTransitionPlanCanonicalSha256, plan.contentSha256);
  assert.equal(result.identityTransitions[0].historicalFindingId, prior.historicalFindingId);
  assert.equal(result.identityTransitions[0].currentFindingId, currentId);
  assert.equal(result.reviewedSourceRelocations.length, 1);
  assert.equal(result.reviewedSourceRelocations[0].priorFindingId, prior.currentFindingId);
  assert.equal(result.decisions.find(item => item.findingId === currentId).disposition, 'NOT_APPLICABLE');
  assert.deepEqual(result.summary.dispositionCounts, { NOT_APPLICABLE: 2, UNRESOLVED: 1 });
  assert.equal(result.summary.releaseBlocked, true);
  assert.deepEqual(result.decisions.map(item => item.findingId).sort(), before.decisions.map(item => item.findingId).sort());
});

test('scanner identity reconciliation maps only for historical comparison and never changes the actual scan', () => {
  const value = scanner(); const before = structuredClone(value);
  const result = reconcile(value);
  assert.deepEqual(value, before);
  assert.equal(hash(`${result.historicalIds.sort().join('\n')}\n`), expected.findingSetSha256);
  assert.notEqual(hash(`${value.findings.map(item => item.findingId).sort().join('\n')}\n`), expected.findingSetSha256);
  assert.equal(result.sourceEvolution.currentFindingId, currentId);
  assert.deepEqual(result.sourceEvolution, apply().reviewedSourceRelocations[0]);
});

test('historical scanner identities still use the unchanged contract without being relabelled current', () => {
  const value = scanner(); value.findings[0].findingId = prior.currentFindingId;
  const before = structuredClone(value);
  const result = reconcileReviewedSemgrepIdentities(value, expected, review, plan, {});
  assert.equal(result.sourceEvolution, null);
  assert.deepEqual(value, before);
  assert.deepEqual(result.historicalIds.sort(), value.findings.map(item => item.findingId).sort());
});

for (const [label, mutate] of [
  ['missing source', value => { delete value[prior.sourcePath]; }],
  ['claimed blob', value => { value[prior.sourcePath].blobSha = '0'.repeat(40); }],
  ['unreviewed comment with matching expression', value => { value[prior.sourcePath].content += '\n// new revision\n'; }],
  ['unreviewed input boundary', value => { value[prior.sourcePath].content = content.replace("v('type')", 'v(process.env.UNTRUSTED)'); }],
]) {
  test(`unknown source fails closed: ${label}`, () => {
    const value = sources(); mutate(value);
    assert.throws(() => apply(intake(), value), /source blob drift/u);
    if (value[prior.sourcePath]?.content) {
      value[prior.sourcePath].blobSha = blob(value[prior.sourcePath].content);
      if (label !== 'claimed blob') assert.throws(() => reconcile(scanner(), value), /source blob drift/u);
    }
  });
}

for (const [field, value] of [['ruleId', 'different-rule'], ['path', '/src/other.mjs'],
  ['startLine', 73], ['startColumn', 393], ['endLine', 84], ['endColumn', 438]]) {
  test(`same ID cannot hide changed scanner ${field}`, () => {
    const scanned = scanner(); scanned.findings[0][field] = value;
    assert.throws(() => apply(intake(scanned)), /intake identity drift/u);
    assert.throws(() => reconcile(scanned), /intake identity drift/u);
  });
}
for (const [label, mutate] of [
  ['missing finding', value => { value.findings.shift(); value.findingCount--; }],
  ['extra finding', value => { value.findings.push({ sourceClass: 'E4_SEMGREP', findingId: 'e'.repeat(64) }); value.findingCount++; }],
  ['duplicate finding', value => { value.findings[2] = value.findings[0]; }],
  ['replaced other finding', value => { value.findings[1].findingId = 'f'.repeat(64); }],
  ['stale count', value => { value.findingCount = 4; }],
  ['incomplete scanner', value => { value.scanCompleted = false; }],
  ['raw report', value => { value.rawReportRetained = true; }],
  ['source snippets', value => { value.sourceSnippetRetained = true; }],
  ['wrong class', value => { value.findings[0].sourceClass = 'E4_OSV_SCANNER'; }],
]) {
  test(`no successful reconciliation for ${label}`, () => {
    const value = scanner(); mutate(value); assert.throws(() => reconcile(value));
  });
}

test('duplicate I2 decisions cannot receive a reviewed disposition', () => {
  const value = intake(); value.decisions.push(value.decisions[0]);
  assert.throws(() => apply(value), /duplicate current intake identity/u);
});
for (const [key, value] of [['sourceBlobSha', 'e'.repeat(40)], ['evidenceCode', 'unreviewed'],
  ['disposition', 'UNRESOLVED'], ['exploitPreconditionsSatisfied', true], ['exception', 'approved']]) {
  test(`source relocation cannot rewrite historical review ${key}`, () => {
    const changed = structuredClone(review); changed.reviewedFindings[0][key] = value;
    assert.throws(() => apply(intake(), sources(), changed), /historical reviewed finding evidence drift/u);
  });
}

test('a forged or rewritten historical plan is rejected even with a recomputed digest', () => {
  const changed = structuredClone(plan); changed.transitions[0].currentLocation.startLine = 83;
  assert.throws(() => apply(intake(), sources(), review, changed), /plan canonical mismatch/u);
  changed.contentSha256 = hash(JSON.stringify(changed));
  assert.throws(() => apply(intake(), sources(), review, changed), /unexpected identity transition plan/u);
});

test('returned lineage and transitions do not mutate subsequent validation', () => {
  const first = resolveReviewedSemgrepTransition(plan, sources());
  first.transition.currentLocation.startLine = 1;
  first.sourceEvolution.priorLocation.startLine = 1;
  const second = resolveReviewedSemgrepTransition(plan, sources());
  assert.equal(second.transition.currentLocation.startLine, 83);
  assert.equal(second.sourceEvolution.priorLocation.startLine, 73);
});

test('live chain retains redacted E4 before triage and checks current inventories and both identity consumers', () => {
  const source = load('scripts/tests/m6-pr-e-e4-scanner-boundary.test.mjs');
  const chain = source.slice(source.indexOf("test('E4 full scanner emits"));
  assert.equal((chain.match(/\[S,`--root=/gu) || []).length, 1);
  assert.ok(chain.indexOf('M6_PR_E_E4_BEGIN') < chain.indexOf('const intake=buildScannerFindingIntake'));
  assert.match(chain, /actionUseCount,46/u); assert.match(chain, /checkoutCredentialBoundaryCount,15/u);
  assert.match(chain, /physicalJobCount,10/u);
  assert.match(chain, /reviewedSemgrepSourceRelocations,i2\.reviewedSourceRelocations/u);
  assert.doesNotMatch(chain, /\[\['E4',e\]/u);
  const generic = load('scripts/security/m6-pr-e-e3-verify-workflow-supply-chain-remediation-generic.mjs');
  assert.match(generic, /reconcileReviewedSemgrepIdentities\(e4\.scanners\.semgrep/u);
  assert.match(generic, /semgrep: findingSetSha256\(semgrepIds\)/u);
  assert.match(generic, /verifyAcceptedR2B\(e4, reconciledPlan, snapshot\)/u);
  assert.doesNotMatch(generic, /e4\.scanners\.semgrep\s*=/u);
  assert.match(source, /import '\.\/semgrep-source-relocation\.test\.mjs'/u);
});
