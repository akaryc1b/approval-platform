import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { fileURLToPath } from 'node:url';
import { spawnSync as historicalGit } from 'node:child_process';
import { buildGitleaksCaptureHashReviewSnapshot, verifyGitleaksCaptureHashReview, gitleaksCaptureReviewFindings } from '../security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
import { buildGitleaksPublicCommitReviewSnapshot } from '../security/m6-pr-e-e3-review-gitleaks-public-commit.mjs';
import { readGitleaksTestExpressionReviewPlan, applyGitleaksTestExpressionReview }
  from '../security/m6-pr-e-e3-review-gitleaks-test-expression.mjs';
import { BASE_GRAPH, SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH, readCompilerPluginCandidate, verifyObservabilityGraph, requirePreservedGraph }
  from '../security/observability-dependency-graph.mjs';
import test from 'node:test';
import { syntheticCompilerPluginOsv } from './fixtures/compiler-plugin-fixture.mjs';
import { requireOsvReport, requireGitleaksReport, requireZizmorReport } from '../security/scanner-report-structure.mjs';
import { normalizeSemgrepReport, DESIGNER_PROTOTYPE_TARGET } from '../security/semgrep-scan-coverage.mjs';
import { verifyScannerCheckout, requireScannerCheckoutUnchanged } from '../security/m6-pr-e-e4-scan.mjs';
import { canonicalPrototypeEvidence as canonical, readDesignerPrototypeRemediationPlan,
  verifyDesignerPrototypeRemediation, requireDesignerPrototypeRemediationReceipt }
  from '../security/m6-pr-e-e3-verify-designer-prototype-remediation.mjs';
import { buildScannerFindingIntake } from '../security/m6-pr-e-e3-ingest-e4.mjs';
import { applyReviewedFindingsWithIdentityTransitions } from '../security/m6-pr-e-e3-apply-reviewed-findings-with-identity-transitions.mjs';
import { applyRuntimeDeploymentReviews } from '../security/m6-pr-e-e3-apply-runtime-deployment-reviews.mjs';
import { applyWorkflowSupplyChainReviews } from '../security/m6-pr-e-e3-apply-workflow-supply-chain-reviews.mjs';
import { verifyPgjdbcRemediation } from '../security/m6-pr-e-e3-verify-pgjdbc-remediation.mjs';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { readServerDependencyRemediationPlan, verifyServerDependencyRemediation }
  from '../security/m6-pr-e-e3-verify-server-dependency-remediation.mjs';
import { verifyDependabotCooldownRemediation } from '../security/m6-pr-e-e3-verify-dependabot-cooldown-remediation.mjs';
import { verifyWorkflowSupplyChainRemediation, reconcileScannerFindingIdentities }
  from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs';

const read = path => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8');
const json = path => JSON.parse(read(`docs/m6/${path}.json`));
const hash = value => createHash('sha256').update(value).digest('hex');
const blob = value => createHash('sha1').update(`blob ${Buffer.byteLength(value)}\0`).update(value).digest('hex');
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: hash(canonical(payload)) }; };
const plan = readDesignerPrototypeRemediationPlan();
const review = json('m6-pr-e-e3-i2-reviewed-findings');
const transition = json('m6-pr-e-e3-r2b-semgrep-finding-identity-transition');
const runtimeReview = json('m6-pr-e-e3-i3-reviewed-findings');
const r1Plan = json('m6-pr-e-e3-r1-pgjdbc-remediation');
const r2aPlan = json('m6-pr-e-e3-r2a-dependabot-cooldown-remediation');
const r2bPlan = json('m6-pr-e-e3-r2b-workflow-supply-chain-remediation');
const workflowReview = json('m6-pr-e-e3-i4-reviewed-findings');
// Synthetic envelopes only. Retained historical identities exercise the strict
// set verifier; these fixtures do not claim a scanner execution or result.
const historicalGitleaksIds = [
  "04d58ffd3accfc0cc945cdaa34e1b9b92678d3a060e9e0f75f33c222b90a904f",
  "0985995f4e3a85d8ec212e8c796fdec4ba193f78df8da9790b84a7c2ea9657bd",
  "35546688230e94e02dd1dbba590bab1f463fd43d77659d5caf58a0592a7da81e",
  "36f2e4fdf9f6c7410d767c66eb140235bb95a78fd14678ac3e57e8007a97b979",
  "3e609f81d333ded79799301476c2fbde346cf4078d7871e9d3d52173abb6db9a",
  "49853b232eeba9aee28a5149bee62ad64b2b3bbdb39b4294ffaea4eeb30730fa",
  "4aae6fb5730a0f8fbcced14a699280ec681211c3ccc04d925a9b342b04cb489c",
  "5fb13ba6d950d034667e4de83992e03880d08657381792c78227415b01f3dc40",
  "637786f50111c46bbe0b6b23dc966b6bf24d658a1fe2bf35087896fddfb2a28b",
  "72973a87b8819fd9134a994fca0a83327509162b18ac619f980117d1885532f3",
  "734b1602caf6451237000f3c46eb4a0a6dd1eb2223754ca19b29480a3f567c8a",
  "7bbe54fc2cee00f199b9dedbc1eb081178221804b9c2352841e30106f81ea1f8",
  "86b5d6f438ccb24510cd71b2a8b2b827b8cbccf1d8a9c50f478afcfd94b77d88",
  "8bb6dc41061f8b36fb8a6583b84aa1088061758f5428bc138bbd26637703fcef",
  "8d50372c70864aec48f072adc53ab2bcddd68499286f2fcf1aeb08d77d5b10c7",
  "a2a6e026423dfb0dc4bf5fe01a59b8fbfdb3daecdc5adc296bf43796588c4795",
  "a2c56fac19aca49f1a8c94af6a8ed77735bee8c2977d10899bd2729361890a47",
  "a753581c267b30c7d0af6939fa893031c9d0dfabb90cef5c57685c4445d0684a",
  "ab87670ff8c48d0cc1cea8261abf39492589fbd8f2fc1af21fbfa40418e0ed96",
  "bbfccade1028572131367011f9d5c54538a1fb8f97d1414208c2d645a678d254",
  "bc06c52dd704f3c706d16d4ba6002d973726f5f8bacf9a49550e3d9fdebaf775",
  "bdf58828e510359641ac81816e5a9d33826e4d8179d2c3a1d897bc57b82e30ba",
  "c02016c68b36664c6cb9520df274c8ceb2f66fa088859bb8dfba66dec5f93c83",
  "cd7abdc70b67ea2dabf1485c62887b40e041997ede8b7f78ff62f41b7b44031a",
  "e9140ea559c07ca7548bc0ffab463fef6428f5f9e714ff5ea357dc472d7c4d48",
  "e984cdc4d07b1d2a742a650e6a98b00fa69164352eca6d33d844537ee96ce04f",
  "eda6c6277ddf6f5e2d49df048e0c481b951919760765963bff6305e02b262906"
];
const retainedSemgrep = [
  {
    "category": "security",
    "cwe": [
      "CWE-1333: Inefficient Regular Expression Complexity"
    ],
    "endColumn": 437,
    "endLine": 73,
    "findingId": "3d0897409d6b051f33f283afea8e0760c95336b44c58622cf938c3a43b14267f",
    "owasp": [
      "A02:2025 - Security Misconfiguration",
      "A05:2021 - Security Misconfiguration",
      "A06:2017 - Security Misconfiguration"
    ],
    "path": "/src/scripts/security/m6-pr-e-e2-generate-sbom.mjs",
    "ruleId": "rules.javascript.lang.security.audit.detect-non-literal-regexp",
    "sourceClass": "E4_SEMGREP",
    "startColumn": 394,
    "startLine": 73,
    "upstreamSeverity": "WARNING"
  },
  {
    "category": "security",
    "cwe": [
      "CWE-319: Cleartext Transmission of Sensitive Information"
    ],
    "endColumn": 36,
    "endLine": 105,
    "findingId": "d90e0567afb2502b6f6d8b34a30fbc4ec211b835a95e101200548b94f9425bd7",
    "owasp": [
      "A02:2021 - Cryptographic Failures",
      "A03:2017 - Sensitive Data Exposure",
      "A04:2025 - Cryptographic Failures"
    ],
    "path": "/src/server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesJdkSecureNetwork.java",
    "ruleId": "rules.java.lang.security.audit.crypto.unencrypted-socket",
    "sourceClass": "E4_SEMGREP",
    "startColumn": 24,
    "startLine": 105,
    "upstreamSeverity": "WARNING"
  }
];
function syntheticRawReport(findings = retainedSemgrep) {
  return { version: plan.semgrepExecutionIdentity.version, errors: [],
    paths: { scanned: [...new Set([...findings.map(f => f.path), DESIGNER_PROTOTYPE_TARGET])], skipped: [] },
    results: findings.map(f => ({ check_id: f.ruleId, path: f.path,
      start: { line: f.startLine, col: f.startColumn }, end: { line: f.endLine, col: f.endColumn },
      extra: { severity: f.upstreamSeverity, metadata: { cwe: f.cwe, owasp: f.owasp, category: f.category } } })) };
}
export function syntheticE4(includePrototype = false) {
  const removed = new Set(r1Plan.remediatedFindings.map(f => f.findingId));
  const osv = runtimeReview.reviewedFindings.filter(f => !removed.has(f.findingId)).map(f => ({
    sourceClass: 'E4_OSV_SCANNER', findingId: f.findingId, upstreamFindingId: f.upstreamFindingId,
    aliases: f.aliases, fixedVersions: f.fixedVersions, package: { ecosystem: 'Maven', name: 'fixture:reviewed', version: '1' },
    componentRefs: [f.componentRef], scopes: ['compile'], upstreamSeverity: [],
  }));
  for (let index = osv.length; index < 143; index++) osv.push({ sourceClass: 'E4_OSV_SCANNER',
    findingId: hash(`synthetic-osv-${index}`), upstreamFindingId: `SYNTHETIC-${index}`, aliases: [],
    fixedVersions: [], package: { ecosystem: 'Maven', name: `fixture:component-${index}`, version: '1' },
    componentRefs: [`pkg:maven/fixture/component-${index}@1?type=jar`], scopes: ['compile'], upstreamSeverity: [] });
  const semgrep = structuredClone(retainedSemgrep);
  if (includePrototype) semgrep.push(structuredClone(plan.historicalFinding));
  const scanner = findings => ({ scanCompleted: true, rawReportRetained: false, findingCount: findings.length, findings });
  return seal({ schemaVersion: 'M6_PR_E_E4_SCANNER_EVIDENCE_V1', repository: plan.repository,
    commitSha: 'a'.repeat(40), e2GraphDigest: r1Plan.targetE2GraphDigest, e2CurrentContentSha256: 'b'.repeat(64),
    checkout: { checkedOutSha: 'c'.repeat(40), checkedOutTreeSha: 'd'.repeat(40), expectedHeadSha: 'a'.repeat(40),
      expectedHeadTreeSha: 'd'.repeat(40), exactTreeMatches: true, trackedWorktreeClean: true },
    allScannersCompleted: true, rawScannerReportsRetained: false, candidateSecretMaterialRetained: false,
    authoritativeGitHubInventoryStillUnavailable: true, workstreamReleaseBlocked: true,
    scanners: {
      osv: scanner(osv), zizmor: scanner([]),
      gitleaks: { ...scanner(historicalGitleaksIds.map(findingId => ({ sourceClass: 'E4_GITLEAKS', findingId,
        ruleId: 'fixture', path: 'fixture.txt', commit: 'b'.repeat(40), fingerprint: 'redacted-fixture', startLine: 1 }))), candidateSecretMaterialRetained: false },
      semgrep: { ...scanner(semgrep), ...plan.semgrepExecutionIdentity, sourceSnippetRetained: false,
        coverage: normalizeSemgrepReport(syntheticRawReport(semgrep), plan.semgrepExecutionIdentity.version).coverage },
    }, totalFindingCount: 170 + semgrep.length });
}
export function snapshot(e4) {
  return { repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
    scannerExecutionCount: 1, suppressionPathsPresent: [], currentSources: Object.fromEntries(
      Object.entries(plan.reviewedArtifacts).map(([path, identity]) => [path, { commitSha: e4.commitSha,
        headBlobSha: identity.blobSha, blobSha: identity.blobSha, content: read(path) }])) };
}
export function i2Inputs(e4, withRemediation = true) {
  const sourcePath = transition.transitions[0].sourcePath, content = read(sourcePath);
  return { currentSources: { [sourcePath]: { blobSha: blob(content), content } },
    ...(withRemediation ? { currentE4: e4, prototypeRemediationSnapshot: snapshot(e4) } : {}) };
}
function chain(e4 = syntheticE4(), withRemediation = true) {
  const intake = buildScannerFindingIntake(e4, { snapshotTime: '2026-10-08T00:00:00Z' });
  const i2 = applyReviewedFindingsWithIdentityTransitions(intake, review, transition, i2Inputs(e4, withRemediation));
  const r1 = verifyPgjdbcRemediation(e4, r1Plan);
  const i3 = applyRuntimeDeploymentReviews(i2, e4, runtimeReview, r1);
  const dependabotBlobSha = blob(read('.github/dependabot.yml'));
  const r2a = verifyDependabotCooldownRemediation(e4, r2aPlan, dependabotBlobSha, { subsequentWorkflowRemediationPlan: r2bPlan });
  const workflows = Object.fromEntries(readdirSync(new URL('../../.github/workflows/', import.meta.url))
    .filter(name => /\.ya?ml$/.test(name)).sort().map(name => {
      const path = `.github/workflows/${name}`, content = read(path); return [path, { content, blobSha: blob(content) }];
    }));
  const r2b = verifyWorkflowSupplyChainRemediation(e4, r2bPlan, { repository: e4.repository,
    commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256, scannerExecutionCount: 1,
    suppressionPathsPresent: [], dependabotBlobSha, workflows,
    ...(withRemediation ? { prototypeRemediationSnapshot: snapshot(e4) } : {}) });
  const i4 = applyWorkflowSupplyChainReviews(i3, e4, workflowReview, r2a, r2b);
  return { e4, intake, i2, r1, i3, r2a, r2b, i4 };
}

function withFinding(e4, finding) {
  e4.scanners.semgrep.findings.push(finding);
  e4.scanners.semgrep.findingCount++;
  e4.totalFindingCount++;
  return seal(e4);
}

test('prototype remediation plan pins the historical review and all three exact reviewed artifact blobs', () => {
  assert.equal(plan.contentSha256, '53143fa727b6eafaae41319e6a2a4d159d14ee2557cd46cb650b895bbb7ee3ac');
  assert.equal(hash(canonical(review)), plan.historicalReviewCanonicalSha256);
  assert.equal(plan.historicalFinding.priorDisposition, 'UNRESOLVED');
  assert.equal(plan.historicalFinding.sourceBlobSha, 'fa3c224ac31df08cb3e2f130f7e4bef1fc750659');
  assert.equal(plan.historicalFinding.findingId, 'e6b83b7719d2bef5092127dd47f2a34c17d9c92ce3b9581b4381dd6b25432445');
  assert.equal(Object.keys(plan.reviewedArtifacts).length, 3);
  for (const [path, expected] of Object.entries(plan.reviewedArtifacts)) {
    assert.equal(blob(read(path)), expected.blobSha);
    assert.equal(hash(read(path)), expected.sha256);
  }
});

test('synthetic I2→I3→I4 keeps the historical unresolved review and all current findings without scanner reconstruction', () => {
  const r = chain(), old = chain(syntheticE4(true), false);
  assert.equal(old.i4.summary.unresolvedCount, 170);
  assert.equal(old.i4.historicallyRemediatedFindingCount, 63);
  assert.equal(r.e4.totalFindingCount, 172);
  assert.equal(r.i2.historicalReviewedFindingCount, 3);
  assert.equal(r.i2.currentReviewedFindingCount, 2);
  assert.equal(r.i2.remediatedHistoricalFindingCount, 1);
  assert.equal(r.i2.designerPrototypeRemediation.remediatedFindings[0].priorDisposition, 'UNRESOLVED');
  assert.equal(r.i3.cumulativeReviewedFindingCount, 7);
  assert.equal(r.i3.historicallyRemediatedFindingCount, 3);
  assert.equal(r.i4.cumulativeReviewedFindingCount, 68);
  assert.equal(r.i4.historicallyRemediatedFindingCount, 64);
  assert.equal(r.i4.historicallyRemediatedFindings.length, 64);
  assert.equal(r.i4.summary.unresolvedCount, 169);
  assert.equal(r.i4.summary.notApplicableCount, 3);
  assert.equal(r.i4.summary.releaseBlocked, true);
  assert.deepEqual(r.i4.designerPrototypeRemediation, r.i2.designerPrototypeRemediation);
  assert.deepEqual(r.i4.decisions.map(f => f.findingId), r.intake.decisions.map(f => f.findingId));
  assert.deepEqual(r.r2b.scannerIdentityReconciliation.currentScannerCounts, { gitleaks: 27, osv: 143, semgrep: 2, zizmor: 0 });
  assert.equal(r.r2b.scannerIdentityReconciliation.totalFindingCount, 172);
  assert.equal(r.r2b.scannerIdentityReconciliation.unreviewedCurrentOsvFindingCount, 143);
  assert.deepEqual(r.r2b.scannerIdentityReconciliation.designerPrototypeRemediation, r.i2.designerPrototypeRemediation);
});

test('missing historical prototype finding still fails without explicit current proof', () => {
  const e4 = syntheticE4(), intake = buildScannerFindingIntake(e4, { snapshotTime: '2026-10-08T00:00:00Z' });
  assert.throws(() => applyReviewedFindingsWithIdentityTransitions(intake, review, transition, i2Inputs(e4, false)), /reviewed finding absent/);
  assert.throws(() => reconcileScannerFindingIdentities(e4), /current Semgrep identity-set drift/);
});

test('prototype remediation rejects the historical match and a relocated same-rule/path match', () => {
  const unchanged = syntheticE4(true);
  assert.throws(() => verifyDesignerPrototypeRemediation(unchanged, snapshot(unchanged)), /historical prototype finding still present/);
  for (const path of [plan.historicalFinding.path, plan.historicalFinding.sourcePath, plan.historicalFinding.path.replace('/src/', '/src/./')]) {
    const e4 = withFinding(syntheticE4(), { ...plan.historicalFinding, path, startLine: 999, findingId: hash(`relocated:${path}`) });
    assert.throws(() => verifyDesignerPrototypeRemediation(e4, snapshot(e4)), /rule\/path still present/);
  }
});

for (const path of Object.keys(plan.reviewedArtifacts)) {
  for (const mutation of ['content', 'blobSha', 'headBlobSha', 'commitSha']) test(`prototype proof rejects ${path} ${mutation} drift`, () => {
    const e4 = syntheticE4(), source = snapshot(e4);
    Object.defineProperty(source.currentSources[path], mutation, { value: `${source.currentSources[path][mutation]}unreviewed` });
    assert.throws(() => verifyDesignerPrototypeRemediation(e4, source), /exact source\/test blob required/);
  });
}
for (const [name, alter] of [
  ['foreign repository', s => { s.repository = 'other/repository'; }],
  ['wrong Head', s => { s.commitSha = 'b'.repeat(40); }],
  ['wrong E4 binding', current => { current.currentE4CanonicalSha256 = 'c'.repeat(64); }],
  ['no scanner execution', s => { s.scannerExecutionCount = 0; }],
  ['duplicate scanner execution', s => { s.scannerExecutionCount = 2; }],
  ['suppression file', s => { s.suppressionPathsPresent = ['.semgrepignore']; }],
  ['missing suppression inventory', s => { delete s.suppressionPathsPresent; }],
]) test(`prototype proof rejects ${name}`, () => {
  const e4 = syntheticE4(), source = snapshot(e4); alter(source);
  assert.throws(() => verifyDesignerPrototypeRemediation(e4, source), /binding required|suppression paths prohibited/);
});
for (const [name, alter] of [
  ['incomplete scan', e => { e.allScannersCompleted = false; }],
  ['failed Semgrep', e => { e.scanners.semgrep.scanCompleted = false; }],
  ['failed OSV', e => { e.scanners.osv.scanCompleted = false; }],
  ['retained source', e => { e.scanners.semgrep.sourceSnippetRetained = true; }],
  ['wrong count', e => { e.scanners.semgrep.findingCount++; }],
  ['wrong total', e => { e.totalFindingCount++; }],
  ['duplicate identity', e => { e.scanners.semgrep.findings[1] = e.scanners.semgrep.findings[0]; }],
  ['release unblocked', e => { e.workstreamReleaseBlocked = false; }],
  ['unverified authoritative closure', e => { e.authoritativeGitHubInventoryStillUnavailable = false; }],
  ...Object.keys(plan.semgrepExecutionIdentity).map(field => [`scanner ${field}`, e => { Object.defineProperty(e.scanners.semgrep, field, { value: 'unreviewed', enumerable: true }); }]),
]) test(`prototype proof rejects canonically rehashed ${name}`, () => {
  let e4 = syntheticE4(); alter(e4); e4 = seal(e4);
  assert.throws(() => verifyDesignerPrototypeRemediation(e4, snapshot(e4)), /prototype remediation/);
});

test('canonical digest tampering cannot enable prototype remediation', () => {
  const e4 = syntheticE4(); e4.contentSha256 = '0'.repeat(64);
  assert.throws(() => verifyDesignerPrototypeRemediation(e4, snapshot(e4)), /current E4 canonical digest mismatch/);
});

test('a newly present finding remains unresolved and R2B fails rather than discarding it', () => {
  const newFinding = { ...retainedSemgrep[0], findingId: hash('unreviewed finding'), path: '/src/new.ts' };
  const e4 = withFinding(syntheticE4(), newFinding);
  const intake = buildScannerFindingIntake(e4, { snapshotTime: '2026-10-08T00:00:00Z' });
  const i2 = applyReviewedFindingsWithIdentityTransitions(intake, review, transition, i2Inputs(e4));
  assert.equal(i2.decisions.find(f => f.findingId === newFinding.findingId).disposition, 'UNRESOLVED');
  assert.equal(i2.decisions.length, e4.totalFindingCount);
  assert.throws(() => reconcileScannerFindingIdentities(e4, snapshot(e4)), /current Semgrep identity-set drift/);
});

test('I2 requires the exact current E4 intake and frozen historical review', () => {
  const e4 = syntheticE4(), intake = buildScannerFindingIntake(e4, { snapshotTime: '2026-10-08T00:00:00Z' });
  const changed = structuredClone(intake); changed.decisions.pop();
  assert.throws(() => applyReviewedFindingsWithIdentityTransitions(seal(changed), review, transition, i2Inputs(e4)), /exact current intake binding/);
  const changedReview = structuredClone(review); changedReview.reviewedFindings[2].disposition = 'NOT_APPLICABLE';
  assert.throws(() => applyReviewedFindingsWithIdentityTransitions(intake, changedReview, transition, i2Inputs(e4)), /historical I2 input drift/);
});

test('I3 and I4 fail closed on historical receipt and count tampering', () => {
  const r = chain();
  const badReceipt = structuredClone(r.i2.designerPrototypeRemediation); badReceipt.remediatedFindings[0].priorDisposition = 'NOT_APPLICABLE';
  assert.throws(() => requireDesignerPrototypeRemediationReceipt(r.e4, seal(badReceipt)), /receipt drift/);
  assert.throws(() => applyRuntimeDeploymentReviews(seal({ ...r.i2, remediatedHistoricalFindingCount: 2 }), r.e4, runtimeReview, r.r1), /I2 prototype remediation history drift/);
  assert.throws(() => applyWorkflowSupplyChainReviews(seal({ ...r.i3, historicallyRemediatedFindingCount: 4 }), r.e4, workflowReview, r.r2a, r.r2b), /I3 prototype remediation history drift/);
});

// Execute the actual CI callback with synthetic subprocess output. Only the
// archived E2 is an authentic precommit observation, not final exact-head evidence.
// OSV and all other scanners, including their finding/absence assertions, and
// subprocess/checkout claims are SYNTHETIC UNIT FIXTURES. Nothing is persisted
// or presented as a current all-scanner E4 execution or admission result.
export function runSyntheticCiCallback({ wrongHeadBlob = false, includePrototype = false, staleHead = false,
  mutateEvidence = null, staleR4Receipt = false, publicCommitFixture = null } = {}) {
  const preparation = json('m6-pr-e-e3-r4-osv-preparation');
  const baseline = json('m6-pr-e-e4-scanner-baseline');
  const expectedHead = publicCommitFixture?.head || readCompilerPluginCandidate().commitSha;
  let e4 = syntheticE4(includePrototype);
  e4.commitSha = staleHead ? 'f'.repeat(40) : expectedHead;
  e4.e2CurrentEvidence = readCompilerPluginCandidate();
  if (publicCommitFixture) e4.e2CurrentEvidence = seal({ ...e4.e2CurrentEvidence, commitSha: expectedHead });
  e4.scanners.osv = syntheticCompilerPluginOsv(e4.e2CurrentEvidence, preparation.osv);
  if (staleHead) {
    e4.e2CurrentEvidence = seal({ ...e4.e2CurrentEvidence, commitSha: e4.commitSha });
    e4.scanners.osv.coverage = seal({ ...e4.scanners.osv.coverage, commitSha: e4.commitSha,
      e2CurrentContentSha256: e4.e2CurrentEvidence.contentSha256 });
  }
  e4.e2CurrentContentSha256 = e4.e2CurrentEvidence.contentSha256;
  e4.e2GraphDigest = COMPILER_PLUGIN_GRAPH;
  e4.e2GraphTransition = verifyObservabilityGraph(e4.e2CurrentEvidence,
    acceptedE2GraphProjection(e4.e2CurrentEvidence), BASE_GRAPH, e4.commitSha);
  e4.checkout = { checkedOutSha: e4.commitSha, expectedHeadSha: e4.commitSha,
    checkedOutTreeSha: preparation.treeSha, expectedHeadTreeSha: preparation.treeSha,
    exactTreeMatches: true, trackedWorktreeClean: true };
  e4.scannerBaselineSourceHead = baseline.sourceHead;
  for (const [name, scanner] of Object.entries(baseline.scanners)) {
    Object.assign(e4.scanners[name], { version: scanner.version, sourceCommit: scanner.sourceCommit });
  }
  const gitleaksPlan = readGitleaksTestExpressionReviewPlan();
  e4.scanners.gitleaks = { ...e4.scanners.gitleaks, ...gitleaksPlan.scannerIdentity,
    findingCount: 28, findings: [...e4.scanners.gitleaks.findings, gitleaksPlan.historicalFinding] };
  e4.totalFindingCount = Object.values(e4.scanners).reduce((count, scanner) => count + scanner.findingCount, 0);
  const sourceRef = `${gitleaksPlan.historicalSource.commitSha}:${gitleaksPlan.historicalSource.path}`;
  const sourceResult = historicalGit('git', ['show', sourceRef], { encoding: 'utf8', cwd: fileURLToPath(new URL('../../', import.meta.url)) });
  assert.equal(sourceResult.status, 0, sourceResult.stderr);
  if (publicCommitFixture) e4 = publicCommitFixture.prepareEvidence(e4);
  if (mutateEvidence) mutateEvidence(e4);
  e4 = seal(e4);
  const source = read('scripts/tests/m6-pr-e-e4-scanner-boundary.test.mjs');
  const logs = [], commands = [];
  const fixtureOutput = `M6_PR_E_E4_SCANNER_EVIDENCE_BEGIN\n${JSON.stringify(e4)}\nM6_PR_E_E4_SCANNER_EVIDENCE_END`;
  let error;
  const files = Object.fromEntries(Object.entries({
    I2: 'i2-reviewed-findings', I2T: 'r2b-semgrep-finding-identity-transition', I3: 'i3-reviewed-findings',
    I4: 'i4-reviewed-findings', R1: 'r1-pgjdbc-remediation', R2A: 'r2a-dependabot-cooldown-remediation',
    R2B: 'r2b-workflow-supply-chain-remediation',
  }).map(([key, suffix]) => [key, `docs/m6/m6-pr-e-e3-${suffix}.json`]));
  runInNewContext(source.slice(source.indexOf("test('E4 full scanner emits")), {
    assert, createHash, Buffer, readdirSync, existsSync, readDesignerPrototypeRemediationPlan,
    readGitleaksTestExpressionReviewPlan, applyGitleaksTestExpressionReview, buildGitleaksPublicCommitReviewSnapshot,
    buildGitleaksCaptureHashReviewSnapshot, verifyGitleaksCaptureHashReview, gitleaksCaptureReviewFindings,
    expectedScannerHead: () => expectedHead,
    SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH, requirePreservedGraph, NG: BASE_GRAPH,
    readServerDependencyRemediationPlan, verifyServerDependencyRemediation,
    buildScannerFindingIntake, applyReviewedFindingsWithIdentityTransitions,
    // Inject a stale handoff only after the genuine R4 verifier has produced it.
    // I3 and I4 retain their real receipt validators and refusal behavior.
    applyRuntimeDeploymentReviews: (...args) => {
      if (staleR4Receipt) args[4] = seal({ ...args[4], sourceE4CanonicalSha256: '0'.repeat(64) });
      return applyRuntimeDeploymentReviews(...args);
    },
    verifyPgjdbcRemediation, verifyDependabotCooldownRemediation,
    verifyWorkflowSupplyChainRemediation, applyWorkflowSupplyChainReviews, ...files,
    REGEXP_OLD: transition.transitions[0].historicalFindingId, REGEXP_CURRENT: transition.transitions[0].currentFindingId,
    T: read, P: path => fileURLToPath(new URL(`../../${path}`, import.meta.url)),
    process: { env: { GITHUB_ACTIONS: 'true' }, execPath: process.execPath }, S: '/synthetic-scanner.mjs', root: '/synthetic-root',
    console: { log: value => logs.push(value) },
    spawnSync: (command, args, options) => {
      commands.push([command, args]);
      if (command === process.execPath) {
        assert.equal(options.timeout, 2300000);
        assert.deepEqual(Array.from(args), ['/synthetic-scanner.mjs', '--root=/synthetic-root']);
        return { status: 0, stdout: fixtureOutput, stderr: '' };
      }
      assert.equal(command, 'git');
      if (args[0] === 'cat-file' && publicCommitFixture) return publicCommitFixture.git(command, args, options);
      if (args[0] === 'show') return { status: 0, stdout: args[1] === '-s' ? '2026-10-08T00:00:00Z' : sourceResult.stdout, stderr: '' };
      if (args[0] === 'rev-parse') {
        if (args[1] === sourceRef) return { status: 0, stdout: gitleaksPlan.historicalSource.blobSha, stderr: '' };
        const [head, path] = args[1].split(':'); assert.equal(head, e4.commitSha);
        return { status: 0, stdout: wrongHeadBlob ? '0'.repeat(40) : blob(read(path)), stderr: '' };
      }
      assert.equal(args[0], 'hash-object');
      return { status: 0, stdout: blob(read(args[1])), stderr: '' };
    },
    test: (name, options, callback) => {
      assert.equal(options.timeout, 2400000);
      try { callback(); } catch (failure) { error = failure; }
    },
  });
  return { e4, logs, commands, error, fixtureOutput };
}

test('actual CI callback consumes one full scan, binds three exact-head blobs, and emits synthetic complete lineage', () => {
  const r = runSyntheticCiCallback();
  assert.equal(r.error, undefined);
  assert.equal(r.logs[0], r.fixtureOutput);
  assert.equal(r.commands.filter(([command]) => command === process.execPath).length, 1);
  assert.equal(r.commands.filter(([command, args]) => command === 'git' && args[0] === 'rev-parse').length, 4);
  const preparation = json('m6-pr-e-e3-r4-osv-preparation');
  assert.deepEqual(r.e4.e2CurrentEvidence, readCompilerPluginCandidate());
  assert.notDeepEqual(r.e4.scanners.osv, preparation.osv);
  assert.equal(preparation.allScannersCompleted, false);
  assert.equal(r.e4.scanners.osv.coverage.inputPackageCount, 473);
  assert.equal(r.e4.scanners.osv.coverage.reportedPackageCount, 473);
  assert.equal(r.e4.scanners.osv.findingCount, 1);
  assert.equal(r.e4.totalFindingCount, 31);
  const outputs = new Map();
  for (const stage of ['E3_I2_TRIAGE', 'E3_DESIGNER_PROTOTYPE_REMEDIATION',
    'E3_R4_SERVER_DEPENDENCY_REMEDIATION', 'E3_I3_TRIAGE', 'E3_I4_TRIAGE']) {
    const output = r.logs.find(text => text.startsWith(`M6_PR_E_${stage}_BEGIN\n`));
    assert.ok(output, stage);
    const evidence = JSON.parse(output.split('\n')[1]);
    assert.equal(evidence.commitSha, r.e4.commitSha);
    assert.equal(seal(evidence).contentSha256, evidence.contentSha256);
    outputs.set(stage, evidence);
  }
  const r4 = outputs.get('E3_R4_SERVER_DEPENDENCY_REMEDIATION');
  const i3 = outputs.get('E3_I3_TRIAGE'), i4 = outputs.get('E3_I4_TRIAGE');
  assert.equal(r4.remediatedFindings.length, 2);
  assert.equal(r4.currentOsvCoverageContentSha256, r.e4.scanners.osv.coverage.contentSha256);
  assert.deepEqual(i3.serverDependencyRemediation, r4);
  assert.deepEqual(i4.serverDependencyRemediation, r4);
  assert.equal(i3.historicallyRemediatedFindingCount, 5);
  assert.equal(i4.historicallyRemediatedFindingCount, 66);
  assert.equal(i4.summary.notApplicableCount, 3);
  assert.equal(i4.summary.unresolvedCount, 28);
  assert.equal(i4.summary.releaseBlocked, true);
  assert.deepEqual(i4.decisions.map(f => f.findingId), outputs.get('E3_I2_TRIAGE').decisions.map(f => f.findingId));
  const pluginJackson = r.e4.scanners.osv.findings.filter(f => f.package.name.startsWith('tools.jackson.core:')
    && f.package.version === '3.1.5');
  assert.equal(pluginJackson.length, 0);
  assert.equal(r4.schemaVersion, 'APPROVAL_SERVER_DEPENDENCY_REMEDIATION_EVIDENCE_V2');
  assert.equal(r4.subsequentGraphTransition.priorE2GraphDigest, CLEAN_PLUGIN_GRAPH);
  for (const finding of r.e4.scanners.osv.findings) {
    assert.equal(i4.decisions.find(f => f.findingId === finding.findingId).disposition, 'UNRESOLVED');
  }
});
for (const [name, options, expected] of [
  ['stale internally consistent scan Head', { staleHead: true }, /AssertionError/],
  ['wrong Git-tree blob', { wrongHeadBlob: true }, /exact source\/test blob required/],
  ['still-present prototype rule', { includePrototype: true }, /historical prototype finding still present/],
  ['missing current E2', { mutateEvidence: e => { delete e.e2CurrentEvidence; } }, /current E2\/source mismatch/],
  ['incomplete candidate-target coverage', { mutateEvidence: e => {
    const coverage = e.scanners.osv.coverage;
    coverage.targets.splice(coverage.targets.findIndex(target => !target.advisoryIds.length), 1);
    e.scanners.osv.coverage = seal(coverage);
  } }, /OSV/],
  ['removed build-plugin targets', { mutateEvidence: e => {
    const coverage = e.scanners.osv.coverage;
    coverage.targets = coverage.targets.filter(target => !target.scopes.includes('build-plugin'));
    e.scanners.osv.coverage = seal(coverage);
  } }, /OSV/],
  ['stale rehashed R4 receipt', { staleR4Receipt: true }, /server dependency remediation receipt mismatch/],
]) test(`actual CI callback retains the synthetic scan and rejects ${name}`, () => {
  const r = runSyntheticCiCallback(options);
  assert.match(String(r.error), expected);
  assert.deepEqual(r.logs, [r.fixtureOutput]);
});


test('later triage cannot drop a current finding even with a rehashed receipt-bearing envelope', () => {
  const r = chain();
  const i2 = seal({ ...r.i2, decisions: r.i2.decisions.slice(1) });
  const i3 = seal({ ...r.i3, decisions: r.i3.decisions.slice(1) });
  assert.throws(() => applyRuntimeDeploymentReviews(i2, r.e4, runtimeReview, r.r1), /retain every current finding identity/);
  assert.throws(() => applyWorkflowSupplyChainReviews(i3, r.e4, workflowReview, r.r2a, r.r2b), /retain every current finding identity/);
});

test('raw Semgrep coverage validates positive target inclusion and retains only hashes, counts and the required path', () => {
  const raw = syntheticRawReport(), result = normalizeSemgrepReport(raw, plan.semgrepExecutionIdentity.version);
  assert.deepEqual(result.findings, retainedSemgrep);
  assert.equal(result.coverage.requiredTarget, DESIGNER_PROTOTYPE_TARGET);
  assert.equal(result.coverage.requiredTargetScanned, true);
  assert.equal(result.coverage.requiredTargetExplicitlySkipped, false);
  assert.equal(result.coverage.scannedPathCount, 3);
  assert.equal(result.coverage.skippedPathInventoryReported, true);
  assert.equal(result.coverage.rawPathsCanonicalSha256, hash(canonical(raw.paths)));
  assert.equal(result.coverage.scannedPathsSha256, hash(canonical([...raw.paths.scanned].sort())));
  assert.doesNotMatch(JSON.stringify(result), /"lines"|"metavars"|"scanned"\s*:/);
  delete raw.paths.skipped; // Semgrep may omit this optional inventory outside verbose mode.
  assert.equal(normalizeSemgrepReport(raw, raw.version).coverage.skippedPathInventoryReported, false);
});

for (const [name, alter] of [
  ['missing results', r => { delete r.results; }],
  ['missing errors', r => { delete r.errors; }],
  ['malformed results', r => { r.results = {}; }],
  ['malformed errors', r => { r.errors = {}; }],
  ['scan error', r => { r.errors = [{ message: 'synthetic parser failure' }]; }],
  ['version mismatch', r => { r.version = 'unknown'; }],
  ['missing paths', r => { delete r.paths; }],
  ['missing scanned paths', r => { delete r.paths.scanned; }],
  ['non-array scanned paths', r => { r.paths.scanned = {}; }],
  ['omitted target despite retained warnings', r => { r.paths.scanned = retainedSemgrep.map(f => f.path); }],
  ['empty scanned paths', r => { r.paths.scanned = []; }],
  ['duplicate scanned paths', r => { r.paths.scanned.push(DESIGNER_PROTOTYPE_TARGET); }],
  ['malformed scanned path', r => { r.paths.scanned.push(null); }],
  ['target both scanned and skipped', r => { r.paths.skipped = [{ path: DESIGNER_PROTOTYPE_TARGET, reason: 'excluded' }]; }],
  ['normalized target both scanned and skipped', r => { r.paths.skipped = [{ path: DESIGNER_PROTOTYPE_TARGET.replace('/src/', '/src/./'), reason: 'excluded' }]; }],
  ['non-array skipped paths', r => { r.paths.skipped = {}; }],
  ['malformed skipped entry', r => { r.paths.skipped = [{}]; }],
  ['finding outside scanned paths', r => { r.results[0].path = '/src/unscanned.ts'; }],
  ['missing rule ID', r => { delete r.results[0].check_id; }],
  ['missing location', r => { delete r.results[0].start; }],
  ['missing severity', r => { delete r.results[0].extra.severity; }],
  ['malformed metadata', r => { r.results[0].extra.metadata = []; }],
]) test(`raw Semgrep normalization fails closed on ${name}`, () => {
  const raw = syntheticRawReport(); alter(raw);
  assert.throws(() => normalizeSemgrepReport(raw, plan.semgrepExecutionIdentity.version), /Semgrep/);
});

for (const [name, alter] of [
  ['missing coverage', e => { delete e.scanners.semgrep.coverage; }],
  ['target not scanned', e => { e.scanners.semgrep.coverage = seal({ ...e.scanners.semgrep.coverage, requiredTargetScanned: false }); }],
  ['target explicitly skipped', e => { e.scanners.semgrep.coverage = seal({ ...e.scanners.semgrep.coverage, requiredTargetExplicitlySkipped: true }); }],
  ['wrong coverage target', e => { e.scanners.semgrep.coverage = seal({ ...e.scanners.semgrep.coverage, requiredTarget: '/src/other.ts' }); }],
  ['coverage hash drift', e => { e.scanners.semgrep.coverage.scannedPathsSha256 = '0'.repeat(64); }],
  ['missing checkout identity', e => { delete e.checkout; }],
  ['different checkout tree', e => { e.checkout.checkedOutTreeSha = 'f'.repeat(40); }],
  ['tracked worktree dirty', e => { e.checkout.trackedWorktreeClean = false; }],
]) test(`remediation rejects ${name} in a canonically rehashed E4`, () => {
  let e4 = syntheticE4(); alter(e4); e4 = seal(e4);
  assert.throws(() => verifyDesignerPrototypeRemediation(e4, snapshot(e4)), /coverage|scanned.checkout/);
});

function fakeGit({ head = 'c'.repeat(40), checkedOutTree = 'd'.repeat(40), expectedTree = 'd'.repeat(40), status = '' } = {}) {
  return args => {
    if (args[0] === 'status') { assert.deepEqual(args, ['status', '--porcelain', '--untracked-files=no']); return status; }
    if (args[1] === 'HEAD') return head;
    return args[1] === 'HEAD^{tree}' ? checkedOutTree : expectedTree;
  };
}

test('scanner accepts a clean synthetic PR merge checkout only when its tree equals the event Head tree', () => {
  const actual = verifyScannerCheckout('/fixture', 'a'.repeat(40), { git: fakeGit() });
  assert.notEqual(actual.checkedOutSha, actual.expectedHeadSha);
  assert.equal(actual.checkedOutTreeSha, actual.expectedHeadTreeSha);
  assert.equal(actual.trackedWorktreeClean, true);
  assert.deepEqual(requireScannerCheckoutUnchanged('/fixture', 'a'.repeat(40), actual, { git: fakeGit() }), actual);
});
for (const [name, change, pattern] of [
  ['different merge tree', { checkedOutTree: 'e'.repeat(40) }, /checkout tree differs/],
  ['unstaged tracked file', { status: ' M tracked.ts' }, /tracked worktree differs/],
  ['staged tracked file', { status: 'M  tracked.ts' }, /tracked worktree differs/],
  ['missing commit object', { expectedTree: '' }, /checkout tree differs/],
]) test(`scanner rejects ${name} before scanning and at final evidence boundary`, () => {
  const before = verifyScannerCheckout('/fixture', 'a'.repeat(40), { git: fakeGit() });
  assert.throws(() => verifyScannerCheckout('/fixture', 'a'.repeat(40), { git: fakeGit(change) }), pattern);
  assert.throws(() => requireScannerCheckoutUnchanged('/fixture', 'a'.repeat(40), before, { git: fakeGit(change) }), pattern);
});

test('scanner wires raw coverage validation and pre/post checkout checks around unchanged full-scope execution', () => {
  const source = read('scripts/security/m6-pr-e-e4-scan.mjs');
  assert.ok(source.indexOf('const checkout=verifyScannerCheckout(root,head)') < source.indexOf('const e2=generateE2Evidence(root,'));
  assert.ok(source.indexOf('requireScannerCheckoutUnchanged(root,head,checkout);') > source.indexOf('sgReport=normalizeSemgrepReport('));
  assert.match(source, /'--strict','--config','\/rules','\/src'/);
  assert.match(source, /coverage:sgReport.coverage/);
  assert.doesNotMatch(source, /function normalizeSemgrep\(raw\)|sgJson.errors\|\|\[\]/);
});

test('OSV structure distinguishes missing output from supported zero and optional vulnerability slices', () => {
  for (const valid of [{ results: [] }, { results: null }, { results: [{ packages: [] }] },
    { results: [{ packages: null }] }, { results: [{ packages: [{ package: { name: 'fixture', ecosystem: 'Maven', version: '1' } }] }] }]) {
    assert.equal(requireOsvReport(valid), valid);
  }
  for (const invalid of [undefined, {}, { results: {} }, { results: [{}] },
    { results: [{ packages: {} }] }, { results: [{ packages: [null] }] },
    { results: [{ packages: [{ package: {}, vulnerabilities: {} }] }] }]) {
    assert.throws(() => requireOsvReport(invalid), /OSV/);
  }
});
test('Gitleaks structure requires the real array emitted by its initialized findings slice', () => {
  const empty = []; assert.equal(requireGitleaksReport(empty), empty);
  for (const invalid of [undefined, null, {}, [null], ['invalid']]) assert.throws(() => requireGitleaksReport(invalid), /Gitleaks/);
  const source = read('scripts/security/m6-pr-e-e4-scan.mjs');
  assert.match(source, /if\(!existsSync\(glRaw\)\)throw new Error\('Gitleaks report artifact missing'\)/);
  assert.doesNotMatch(source, /writeFileSync\(glRaw,'\[\]'\)/);
});
test('zizmor structure requires SARIF zero results arrays and rejects absent or failed analysis', () => {
  const run = { tool: { driver: { name: 'zizmor' } } };
  for (const valid of [{ version: '2.1.0', runs: [{ ...run, results: [] }] }]) {
    assert.equal(requireZizmorReport(valid), valid);
  }
  for (const invalid of [{}, { version: '2.1.0', runs: [run] }, { version: '2.1.0' }, { version: '2.1.0', runs: [] },
    { version: '2.1.0', runs: [{}] }, { version: '2.1.0', runs: [{ ...run, results: {} }] },
    { version: '2.1.0', runs: [{ ...run, results: null }] },
    { version: '2.1.0', runs: [{ ...run, invocations: [{ executionSuccessful: false }] }] }]) {
    assert.throws(() => requireZizmorReport(invalid), /zizmor/);
  }
  const source = read('scripts/security/m6-pr-e-e4-scan.mjs');
  assert.match(source, /osvJson=requireOsvReport\(J\(osvRaw\)\)/);
  assert.match(source, /zzJson=requireZizmorReport\(J\(zzRaw\)\)/);
});
