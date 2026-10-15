import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { syntheticE4, snapshot as prototypeSnapshot, i2Inputs, runSyntheticCiCallback }
  from './m6-pr-e-e3-designer-prototype-remediation.test.mjs';
import { modeledJarDigestFixture, acceptedJarDigestLegacyFindings, normalizedDigestFinding,
  resealJarDigestEvidence, ACCEPTED_JAR_DIGEST_BASE, ACCEPTED_GITLEAKS_70_SHA256, JAR_DIGEST_DOCUMENT_PATHS,
  JAR_DIGEST_FIXTURE_PATH, ORIGINAL_JAR_DIGEST_FIXTURE_SHA256, originalJarDigestFixtureBytes, JAR_VERIFIER_PATH, publishedVerifierIntroductionBytes }
  from './fixtures/jar-verifier-review-fixture.mjs';
import { canonical, seal } from './fixtures/public-commit-review-fixture.mjs';
import { gitleaksJarExecutionDigestFields, requireGitleaksCaptureInventory,
  readGitleaksCaptureHashReviewPlan, buildGitleaksCaptureHashReviewSnapshot,
  verifyGitleaksCaptureHashReview, requireGitleaksCaptureHashReviewReceipt,
  gitleaksCaptureReviewFindings, applyGitleaksCaptureHashDecisions }
  from '../security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
import { buildGitleaksPublicCommitReviewSnapshot, requireGitleaksPublicCommitReviewReceipt }
  from '../security/m6-pr-e-e3-review-gitleaks-public-commit.mjs';
import { readGitleaksTestExpressionReviewPlan, applyGitleaksTestExpressionReview,
  requireGitleaksTestExpressionTriage, requireGitleaksTestExpressionReviewReceipt }
  from '../security/m6-pr-e-e3-review-gitleaks-test-expression.mjs';
import { buildScannerFindingIntake } from '../security/m6-pr-e-e3-ingest-e4.mjs';
import { applyReviewedFindingsWithIdentityTransitions }
  from '../security/m6-pr-e-e3-apply-reviewed-findings-with-identity-transitions.mjs';
import { reconcileScannerFindingIdentities }
  from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs';
import { applyRuntimeDeploymentReviews } from '../security/m6-pr-e-e3-apply-runtime-deployment-reviews.mjs';
import { applyWorkflowSupplyChainReviews } from '../security/m6-pr-e-e3-apply-workflow-supply-chain-reviews.mjs';
import { JAR_PLUGIN_GRAPH } from '../security/jar-plugin-graph-transition.mjs';
import { objectHash } from '../security/gitleaks-git-source-proof.mjs';
import { ASSEMBLY_PLEXUS_GRAPH } from '../security/assembly-plexus-graph-transition.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const hash = value => createHash('sha256').update(value).digest('hex');
const read = path => readFileSync(`${root}${path}`, 'utf8');
const json = suffix => JSON.parse(read(`docs/m6/m6-pr-e-e3-${suffix}.json`));
const plan = readGitleaksCaptureHashReviewPlan();
const expression = readGitleaksTestExpressionReviewPlan();
const snapshotFor = (e4, proof) => ({ repository: e4.repository, commitSha: e4.commitSha,
  currentE4CanonicalSha256: e4.contentSha256, proof });
const currentI2 = e4 => applyReviewedFindingsWithIdentityTransitions(
  buildScannerFindingIntake(e4, { snapshotTime: '2026-10-15T00:00:00Z' }),
  json('i2-reviewed-findings'), json('r2b-semgrep-finding-identity-transition'), i2Inputs(e4));
let retainedCase;
function baseCase() {
  if (!retainedCase) {
    const model = modeledJarDigestFixture(), e4 = model.prepareEvidence(syntheticE4());
    const source = fullSnapshot(e4, model);
    const receipt = verifyGitleaksCaptureHashReview(e4, source.captureHashReviewSnapshot);
    retainedCase = { model, e4, source, receipt };
  }
  return retainedCase;
}
function fullSnapshot(e4, model) {
  const captureHashReviewSnapshot = model.snapshot(e4);
  const captureHashReview = verifyGitleaksCaptureHashReview(e4, captureHashReviewSnapshot);
  const historical = spawnSync('git', ['show', `${expression.historicalSource.commitSha}:${expression.historicalSource.path}`],
    { cwd: root, encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 });
  assert.equal(historical.status, 0, historical.stderr);
  return { repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
    historicalSource: { ...expression.historicalSource, content: historical.stdout }, captureHashReviewSnapshot,
    publicCommitReviewSnapshot: buildGitleaksPublicCommitReviewSnapshot(e4, { root, git: model.git, captureHashReview }) };
}
function mutatedEvidence(mutate) {
  const e4 = structuredClone(baseCase().e4); mutate(e4); return resealJarDigestEvidence(e4);
}
const digestRows = e4 => e4.scanners.gitleaks.findings.filter(row => JAR_DIGEST_DOCUMENT_PATHS.includes(row.path));
const fixtureRow = e4 => e4.scanners.gitleaks.findings.find(row => row.path === JAR_DIGEST_FIXTURE_PATH);
const CURRENT_FIXTURE_SHA256 = 'd86e33849a97cee6765fb2e96a38eb86c234f502b59bdbba97989f71e712b412';
const CURRENT_FIXTURE_BLOB = objectHash('blob', readFileSync(`${root}${JAR_DIGEST_FIXTURE_PATH}`));
const ORIGINAL_JAR_DIGEST_FIXTURE_BLOB = objectHash('blob', originalJarDigestFixtureBytes());
const expectedDocuments = [
  { path: JAR_DIGEST_DOCUMENT_PATHS[0], hash: 'd3d8fa07357c919f8999c2c1d043d05305f8daa3eb82a0ad1d5b5c6f62e17f5d',
    lines: [9, 10, 92, 93, 110, 111, 125, 127, 155, 156, 160] },
  { path: JAR_DIGEST_DOCUMENT_PATHS[1], hash: '7036dff106172b82af4ebe16427cc16fe1e2251d492ba11441926f3434e69973',
    lines: [168, 185, 186, 324, 562, 691, 739, 740, 831, 845, 846, 883, 884, 1050, 1051, 1151, 1152,
      1169, 1170, 1184, 1186, 1214, 1215, 1219, 1251, 1271, 1272, 1306, 1308, 1426, 1466, 1467, 1468,
      1470, 1471, 1472, 1473, 1474, 1475, 1478, 1479, 1480, 1481, 1482, 1623, 1656, 1657, 1658, 1659,
      1660, 1661, 1677, 1678, 1711, 1761, 1762, 1769, 1770, 1771, 1772, 1781, 1782, 1783, 1784, 1785,
      1786, 1787, 1801, 1802, 1868, 1869, 1872, 1873, 1876, 1877, 1881, 1882, 1918, 1967, 1968, 1992,
      1993, 1994, 1995, 1996, 2096, 2100, 2102, 2104, 2108, 2110, 2112, 2119, 2120, 2124, 2125, 2130,
      2144, 2146, 2225, 2281, 2282, 2283, 2284, 2285, 2286, 2287, 2302, 2303, 2418] },
];

test('JAR digest fields select exactly 121 typed scalars from two fixed documents', () => {
  const fields = gitleaksJarExecutionDigestFields(); assert.equal(fields.length, 121);
  assert.deepEqual(Object.fromEntries(['MODEL_RUNTIME_FILE_DIGEST', 'COMPATIBILITY_RUNTIME_FILE_DIGEST', 'COMPATIBILITY_CACHE_FILE_DIGEST']
    .map(role => [role, fields.filter(row => row.role === role).length])),
  { MODEL_RUNTIME_FILE_DIGEST: 11, COMPATIBILITY_RUNTIME_FILE_DIGEST: 24, COMPATIBILITY_CACHE_FILE_DIGEST: 86 });
  assert.equal(new Set(fields.map(row => `${row.path}:${row.jsonPointer}`)).size, 121);
  for (const expected of expectedDocuments) {
    const text = read(expected.path), value = JSON.parse(text), selected = fields.filter(field => field.path === expected.path);
    assert.equal(hash(text), expected.hash); assert.equal(text, `${JSON.stringify(value, null, 2)}\n`);
    assert.deepEqual(selected.map(row => row.line), expected.lines);
    if (expected.path === JAR_DIGEST_DOCUMENT_PATHS[0]) assert.equal(Object.keys(value).length, 159);
    else {
      assert.equal(value.schemaVersion, 1);
      assert.deepEqual(Object.keys(value).sort(), ['schemaVersion', 'plan', 'config', 'sourceHashes', 'runtimeHashes', 'cacheHashes', 'pins', 'linkage'].sort());
      assert.deepEqual(Object.keys(value.config).sort(), ['source', 'pins', 'maven', 'jdk', 'repository', 'output'].sort());
      assert.deepEqual(['sourceHashes', 'runtimeHashes', 'cacheHashes'].map(name => Object.keys(value[name]).length), [21, 1117, 1211]);
    }
    for (const field of selected) {
      const parts = field.jsonPointer.slice(1).split('/').map(part => part.replaceAll('~1', '/').replaceAll('~0', '~'));
      const scalar = parts.reduce((node, part) => node[part], value), line = text.split('\n')[field.line - 1];
      assert.match(scalar, /^[0-9a-f]{64}$/); assert.equal(hash(line), field.lineSha256);
      assert.match(line, /^\s+"(?:[^"\\]|\\.)+": "[0-9a-f]{64}",?$/);
      const { startOneBased, endOneBased } = field.derivedJsonValueColumns;
      assert.equal(endOneBased - startOneBased + 1, 64);
      assert.equal(line.slice(startOneBased - 1, endOneBased), scalar);
      assert.equal(field.scannerColumnsRetained, false);
      assert.equal(line.slice(0, startOneBased - 1).trim(), `${JSON.stringify(parts.at(-1))}: "`);
    }
  }
});

test('JAR digest accepted 70 is derived from retained roles and real history without a copied finding table', () => {
  const findings = acceptedJarDigestLegacyFindings();
  assert.equal(findings.length, 70); assert.equal(hash(canonical(findings)), ACCEPTED_GITLEAKS_70_SHA256);
  assert.deepEqual(findings.filter(row => plan.retainedFindings.some(old => old.findingId === row.findingId)), plan.retainedFindings);
});

test('JAR digest fixture retains the exact public commit declaration in independently pinned original and current bytes', () => {
  const original = originalJarDigestFixtureBytes(), current = readFileSync(`${root}${JAR_DIGEST_FIXTURE_PATH}`);
  assert.equal(hash(original), ORIGINAL_JAR_DIGEST_FIXTURE_SHA256);
  assert.equal(objectHash('blob', original), ORIGINAL_JAR_DIGEST_FIXTURE_BLOB); assert.equal(original.length, 8436);
  assert.equal(hash(current), CURRENT_FIXTURE_SHA256);
  assert.equal(objectHash('blob', current), CURRENT_FIXTURE_BLOB); assert.equal(current.length, 12536);
  assert.notDeepEqual(original, current);
  for (const bytes of [original, current]) assert.equal(bytes.toString('utf8').split('\n')[17],
    `export const ACCEPTED_JAR_DIGEST_BASE = '${ACCEPTED_JAR_DIGEST_BASE}';`);
});

test('JAR digest V5 receipt replays all 194 identities and preserves the legacy proof groups', () => {
  const { e4, receipt, model } = baseCase(), before = canonical(e4);
  assert.equal(e4.e2GraphDigest, JAR_PLUGIN_GRAPH); requireGitleaksCaptureInventory(e4);
  assert.equal(receipt.schemaVersion, 'M6_PR_E_E3_GITLEAKS_CAPTURE_HASH_REVIEW_EVIDENCE_V5');
  assert.equal(receipt.currentFindingCount, 194); assert.equal(receipt.findings.length, 3);
  assert.equal(receipt.planMetadataReview.findings.length, 38);
  assert.equal(receipt.executionDigestReview.findings.length, 121);
  assert.deepEqual(receipt.executionDigestReview.fields, gitleaksJarExecutionDigestFields());
  assert.equal(receipt.executionDigestReview.documents.length, 2);
  assert.equal(receipt.executionDigestReview.acceptedMainCommit, ACCEPTED_JAR_DIGEST_BASE);
  assert.equal(receipt.executionDigestReview.acceptedGitleaksInventorySha256, ACCEPTED_GITLEAKS_70_SHA256);
  assert.equal(receipt.executionDigestReview.inputDigestProvenance, 'INDEPENDENTLY_REHASHED_EXECUTED_LOCAL_INPUTS_NOT_GIT_OBJECTS');
  assert.equal(receipt.executionDigestReview.scannerColumnsRetained, false);
  assert.equal(receipt.executionDigestReview.localExecutionOnly, true);
  assert.equal(receipt.executionDigestReview.canonicalCiEvidence, false);
  assert.equal(receipt.executionDigestReview.releaseBlocked, true);
  assert.equal(receipt.proof.executionDigests.documents.length, 2);
  assert.equal(receipt.proof.executionDigests.documents.some(row => Object.hasOwn(row, 'contentBase64')), false);
  const fixture = receipt.fixturePublicCommitReview;
  assert.deepEqual(fixture.finding, fixtureRow(e4));
  assert.deepEqual(fixture.source, { path: JAR_DIGEST_FIXTURE_PATH, sha256: CURRENT_FIXTURE_SHA256,
    blobSha: CURRENT_FIXTURE_BLOB, byteLength: 12536, line: 18, field: 'ACCEPTED_JAR_DIGEST_BASE',
    lineSha256: hash(read(JAR_DIGEST_FIXTURE_PATH).split('\n')[17]), role: 'ACCEPTED_MAIN_GIT_COMMIT_IDENTITY',
    scannerColumnsRetained: false, derivedLiteralColumns: { startOneBased: 42, endOneBased: 81 } });
  assert.deepEqual(fixture.introductionSource, { sha256: ORIGINAL_JAR_DIGEST_FIXTURE_SHA256,
    blobSha: ORIGINAL_JAR_DIGEST_FIXTURE_BLOB, byteLength: 8436 });
  assert.equal(fixture.acceptedMainCommit, ACCEPTED_JAR_DIGEST_BASE); assert.equal(fixture.acceptedMainTree, model.baseTree);
  assert.equal(fixture.sourceProofCanonicalSha256, hash(canonical(receipt.proof.fixturePublicCommit)));
  assert.equal(fixture.decision.disposition, 'NOT_APPLICABLE');
  assert.equal(fixture.decision.exploitPreconditionsSatisfied, false);
  assert.notEqual(fixture.decision.evidenceCode, receipt.executionDigestReview.decision.evidenceCode);
  assert.equal(fixture.inputProvenance, 'ACTUAL_ACCEPTED_GIT_COMMIT_OBJECT_AND_EXACT_FIXED_SOURCE_FIELD');
  assert.equal(fixture.scannerColumnsRetained, false); assert.equal(fixture.rawCandidateMaterialRetained, false);
  assert.equal(fixture.releaseBlocked, true);
  assert.deepEqual(Buffer.from(receipt.proof.fixturePublicCommit.introductionContentBase64, 'base64'), model.fixtureIntroductionBytes);
  assert.equal(gitleaksCaptureReviewFindings(receipt).length, 165);
  assert.ok(receipt.proof.inputs.length === 3 && receipt.proof.planMetadata.scannerBaseline);
  assert.equal(receipt.releaseBlocked, true);
  assert.deepEqual(requireGitleaksCaptureHashReviewReceipt(e4, receipt), receipt);
  assert.equal(canonical(e4), before);
  assert.ok(model.commands.every(([command, args]) => command === 'git' && args[0] === 'cat-file'));
});

for (const options of [{}, { mergeHead: true }, { mergeIntroduction: true },
  { separateFixtureIntroduction: true }, { fixtureIntroductionVersion: 'current' },
  { separateFixtureIntroduction: true, fixtureIntroductionVersion: 'current', mergeHead: true }]) {
  test(`JAR digest derives an alternate introduction and later ancestry ${JSON.stringify(options)}`, () => {
    const model = modeledJarDigestFixture('alternate JAR evidence publication', options);
    const e4 = model.prepareEvidence(syntheticE4()), receipt = verifyGitleaksCaptureHashReview(e4, model.snapshot(e4));
    assert.notEqual(model.intro, baseCase().model.intro); assert.notEqual(model.head, model.intro);
    assert.equal(receipt.executionDigestReview.findings.every(row => row.commit === model.intro), true);
    assert.equal(receipt.executionDigestReview.findings.length, 121);
    assert.equal(receipt.fixturePublicCommitReview.finding.commit, model.fixtureIntro);
    assert.equal(model.fixtureIntro === model.intro, !options.separateFixtureIntroduction);
    assert.equal(receipt.fixturePublicCommitReview.introductionSource.blobSha,
      options.fixtureIntroductionVersion === 'current' ? CURRENT_FIXTURE_BLOB : ORIGINAL_JAR_DIGEST_FIXTURE_BLOB);
    assert.deepEqual(Buffer.from(receipt.proof.fixturePublicCommit.introductionContentBase64, 'base64'), model.fixtureIntroductionBytes);
    assert.notEqual(model.fixtureIntro, baseCase().model.fixtureIntro);
    assert.deepEqual(requireGitleaksCaptureHashReviewReceipt(e4, receipt), receipt);
    if (options.fixtureIntroductionVersion === 'current') {
      const changed = structuredClone(receipt);
      changed.proof.fixturePublicCommit.introductionContentBase64 = originalJarDigestFixtureBytes().toString('base64');
      assert.throws(() => requireGitleaksCaptureHashReviewReceipt(e4, seal(changed)), /Gitleaks.*(?:blob|path)/);
    }
    if (options.separateFixtureIntroduction) {
      const changed = structuredClone(e4), row = fixtureRow(changed);
      changed.scanners.gitleaks.findings[changed.scanners.gitleaks.findings.indexOf(row)] = normalizedDigestFinding(model.intro,
        row.path, row.startLine, plan.retainedFindings.find(item => item.ruleId === row.ruleId));
      changed.scanners.gitleaks.findings.sort((a, b) => a.findingId.localeCompare(b.findingId));
      const wrongIntroduction = resealJarDigestEvidence(changed);
      assert.throws(() => verifyGitleaksCaptureHashReview(wrongIntroduction, snapshotFor(wrongIntroduction, receipt.proof)), /Gitleaks/);
    }
  });
}

test('JAR digest I2 and R2B retain all observations with 167 append-only reviews and the release block', () => {
  const { e4, source } = baseCase(), before = currentI2(e4);
  const triage = applyGitleaksTestExpressionReview(before, e4, source);
  requireGitleaksTestExpressionTriage(e4, triage);
  assert.deepEqual(triage.decisions.map(row => row.findingId), before.decisions.map(row => row.findingId));
  assert.equal(triage.decisions.filter(row => row.sourceClass === 'E4_GITLEAKS').length, 194);
  assert.equal(triage.appendOnlyReviewedFindingCount, 167); assert.equal(triage.currentReviewedFindingCount, 169);
  assert.equal(triage.summary.releaseBlocked, true);
  const reconciliation = reconcileScannerFindingIdentities(e4, prototypeSnapshot(e4), source);
  assert.equal(reconciliation.currentScannerCounts.gitleaks, 194);
  assert.equal(reconciliation.reviewedAddedGitleaksFindingCount, 167);
  assert.equal(reconciliation.retainedHistoricalGitleaksFindingCount, 27);
  assert.equal(reconciliation.releaseBlocked, true);
});

test('JAR digest extension preserves every accepted 70 disposition and rationale apart from current receipt rebinding', () => {
  const { e4, model, source } = baseCase();
  const historical = structuredClone(e4); historical.e2GraphDigest = ASSEMBLY_PLEXUS_GRAPH;
  historical.scanners.gitleaks.findings = acceptedJarDigestLegacyFindings();
  const e70 = resealJarDigestEvidence(historical);
  const prior = applyGitleaksTestExpressionReview(currentI2(e70), e70, fullSnapshot(e70, model));
  const current = applyGitleaksTestExpressionReview(currentI2(e4), e4, source);
  const unbind = item => { const copy = structuredClone(item); if (copy.reviewEvidence) delete copy.reviewEvidence.reviewCanonicalSha256; return copy; };
  const oldDecisions = prior.decisions.filter(row => row.sourceClass === 'E4_GITLEAKS'); assert.equal(oldDecisions.length, 70);
  for (const row of oldDecisions) assert.deepEqual(unbind(current.decisions.find(now => now.findingId === row.findingId)), unbind(row));
  assert.deepEqual(current.decisions.filter(row => row.disposition === 'UNRESOLVED').map(row => row.findingId),
    prior.decisions.filter(row => row.disposition === 'UNRESOLVED').map(row => row.findingId));
  assert.equal(current.summary.releaseBlocked, true); assert.equal(prior.summary.releaseBlocked, true);
});

for (const [label, mutate] of [
  ['current graph with only the prior 70', e4 => { e4.scanners.gitleaks.findings = acceptedJarDigestLegacyFindings(); }],
  ['current193 omitting the fixture row', e4 => { e4.scanners.gitleaks.findings = e4.scanners.gitleaks.findings.filter(row => row.path !== JAR_DIGEST_FIXTURE_PATH); }],
  ['current192 omitting both verifier rows', e4 => { e4.scanners.gitleaks.findings = e4.scanners.gitleaks.findings.filter(row => row.path !== JAR_VERIFIER_PATH); }],
  ['current191 omitting all public source rows', e4 => { e4.scanners.gitleaks.findings = e4.scanners.gitleaks.findings.filter(row => ![JAR_VERIFIER_PATH, JAR_DIGEST_FIXTURE_PATH].includes(row.path)); }],
  ['missing digest row', e4 => { e4.scanners.gitleaks.findings = e4.scanners.gitleaks.findings.filter(row => row.findingId !== digestRows(e4)[0].findingId); }],
  ['duplicate digest row', e4 => { e4.scanners.gitleaks.findings.push(digestRows(e4)[0]); }],
  ['extra digest row', e4 => { e4.scanners.gitleaks.findings.push({ ...digestRows(e4)[0], findingId: hash('unreviewed extra digest') }); }],
  ['194 on a historical graph', e4 => { e4.e2GraphDigest = ASSEMBLY_PLEXUS_GRAPH; }],
  ['unsupported historical graph', e4 => { e4.e2GraphDigest = '1'.repeat(64); e4.scanners.gitleaks.findings = acceptedJarDigestLegacyFindings(); }],
]) test(`JAR digest admission rejects ${label} before source-proof collection`, () => {
  const e4 = mutatedEvidence(mutate); let calls = 0;
  assert.throws(() => requireGitleaksCaptureInventory(e4), /Gitleaks|E4|graph|release/);
  assert.throws(() => buildGitleaksCaptureHashReviewSnapshot(e4, { root, git: () => { calls++; throw new Error('unexpected source proof collection'); } }), /Gitleaks|E4|graph|release/);
  assert.equal(calls, 0);
});

for (const [label, mutate] of [
  ['reordered inventory', e4 => { e4.scanners.gitleaks.findings.reverse(); }],
  ['legacy metadata drift', e4 => { e4.scanners.gitleaks.findings.find(row => row.findingId === plan.retainedFindings[0].findingId).description = 'unreviewed'; }],
  ['cleared release block', e4 => { e4.workstreamReleaseBlocked = false; }],
]) test(`JAR digest collector rejects ${label} before Git access`, () => {
  const e4 = mutatedEvidence(mutate); let calls = 0;
  assert.throws(() => buildGitleaksCaptureHashReviewSnapshot(e4, { root,
    git: () => { calls++; throw new Error('unexpected source proof collection'); } }), /Gitleaks|E4|release/);
  assert.equal(calls, 0);
});

for (const field of ['commit', 'path', 'ruleId', 'description', 'startLine', 'endLine', 'fingerprint', 'findingId', 'sourceClass']) {
  test(`JAR digest review rejects exact finding ${field} drift after resealing`, () => {
    const e4 = mutatedEvidence(value => { const row = digestRows(value)[0]; row[field] = typeof row[field] === 'number' ? row[field] + 1 : 'unreviewed'; });
    assert.throws(() => verifyGitleaksCaptureHashReview(e4, snapshotFor(e4, baseCase().receipt.proof)), /Gitleaks|E4/);
  });
}

for (const [label, mutate] of [
  ['same-count unrelated secret', row => normalizedDigestFinding(row.commit, 'src/unrelated-secret.txt', 1)],
  ['new secret-like field in fixed document', row => normalizedDigestFinding(row.commit, row.path, 1)],
  ['other scanner rule at an eligible field', row => normalizedDigestFinding(row.commit, row.path, row.startLine,
    { ...plan.finding, ruleId: 'unreviewed-credential-rule' })],
  ['later copy posing as the introduction', row => normalizedDigestFinding(baseCase().model.head, row.path, row.startLine)],
  ['unknown introduction', row => normalizedDigestFinding('e'.repeat(40), row.path, row.startLine)],
]) test(`JAR digest review rejects ${label} with internally recomputed finding identity`, () => {
  const e4 = mutatedEvidence(value => {
    const row = digestRows(value)[0], index = value.scanners.gitleaks.findings.findIndex(item => item.findingId === row.findingId);
    value.scanners.gitleaks.findings[index] = mutate(row);
    value.scanners.gitleaks.findings.sort((a, b) => a.findingId.localeCompare(b.findingId));
  });
  assert.throws(() => verifyGitleaksCaptureHashReview(e4, snapshotFor(e4, baseCase().receipt.proof)), /Gitleaks|E4/);
});

test('JAR digest decisions reject duplicates, omissions and replayed applied decisions', () => {
  const { e4, receipt } = baseCase(), reviewed = new Set(gitleaksCaptureReviewFindings(receipt).map(row => row.findingId));
  const decisions = currentI2(e4).decisions.filter(row => reviewed.has(row.findingId)); assert.equal(decisions.length, 165);
  assert.throws(() => applyGitleaksCaptureHashDecisions([...decisions.slice(1), decisions[1]], receipt), /duplicate/);
  assert.throws(() => applyGitleaksCaptureHashDecisions(decisions.slice(1), receipt), /decisions|identities/);
  const applied = applyGitleaksCaptureHashDecisions(decisions, receipt);
  const fixture = applied.find(row => row.findingId === receipt.fixturePublicCommitReview.finding.findingId);
  assert.equal(fixture.reviewEvidence.reviewLayer, 'E3-I2-APPEND_ONLY-GITLEAKS-FIXTURE-PUBLIC-COMMIT');
  assert.equal(fixture.reviewEvidence.sourceBlobSha, ORIGINAL_JAR_DIGEST_FIXTURE_BLOB);
  assert.equal(fixture.reviewEvidence.currentSourceBlobSha, CURRENT_FIXTURE_BLOB);
  assert.equal(fixture.reviewEvidence.sourceField, 'ACCEPTED_JAR_DIGEST_BASE');
  assert.equal(applied.filter(row => row.reviewEvidence.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-EXECUTION-DIGEST').length, 121);
  assert.throws(() => applyGitleaksCaptureHashDecisions(applied, receipt), /unresolved/);
});

test('JAR digest fixture rejects recomputed wrong field, rule, path and introduction finding identities', () => {
  const policy = plan.retainedFindings.find(row => row.ruleId === 'sourcegraph-access-token');
  for (const [label, change] of [
    ['another field', row => normalizedDigestFinding(row.commit, row.path, 19, policy)],
    ['another rule', row => normalizedDigestFinding(row.commit, row.path, row.startLine, plan.finding)],
    ['another path', row => normalizedDigestFinding(row.commit, 'scripts/tests/fixtures/unreviewed.mjs', row.startLine, policy)],
    ['later copy', row => normalizedDigestFinding(baseCase().model.head, row.path, row.startLine, policy)],
    ['unknown introduction', row => normalizedDigestFinding('e'.repeat(40), row.path, row.startLine, policy)],
  ]) {
    const e4 = mutatedEvidence(value => {
      const row = fixtureRow(value), index = value.scanners.gitleaks.findings.indexOf(row);
      value.scanners.gitleaks.findings[index] = change(row);
      value.scanners.gitleaks.findings.sort((a, b) => a.findingId.localeCompare(b.findingId));
    });
    assert.throws(() => verifyGitleaksCaptureHashReview(e4, snapshotFor(e4, baseCase().receipt.proof)), /Gitleaks|E4/, label);
  }
});

test('JAR digest fixture refuses missing proof and hash-correct wrong current or introduction blobs', () => {
  for (const [label, options] of [
    ['original bytes at current path', { transformFixtureCurrent: () => originalJarDigestFixtureBytes() }],
    ['changed current bytes', { transformFixtureCurrent: bytes => Buffer.concat([bytes, Buffer.from('\n')]) }],
    ['third introduction blob', { transformFixtureIntroduction: bytes => Buffer.concat([bytes, Buffer.from('\n')]) }],
    ['different introduction field', { transformFixtureIntroduction: bytes => bytes.toString('utf8').replace(
      'export const ACCEPTED_JAR_DIGEST_BASE =', 'export const UNREVIEWED_PUBLIC_COMMIT =') }],
  ]) {
    const model = modeledJarDigestFixture(label, options), e4 = model.prepareEvidence(syntheticE4());
    assert.throws(() => model.snapshot(e4), /Gitleaks.*(?:fixture|blob|path|source)/, label);
  }
  for (const [label, mutate] of [
    ['omitted fixture proof', proof => { delete proof.fixturePublicCommit; }],
    ['omitted current path', proof => { proof.fixturePublicCommit.currentPath = []; }],
    ['omitted introduction path', proof => { proof.fixturePublicCommit.introductionPath = []; }],
    ['omitted base absence', proof => { proof.fixturePublicCommit.basePath = []; }],
    ['omitted introduction parents', proof => { proof.fixturePublicCommit.introductionParents = []; }],
    ['omitted parent absence', proof => { proof.fixturePublicCommit.introductionParents[0].sourcePath = []; }],
    ['current bytes substituted for original introduction', proof => { proof.fixturePublicCommit.introductionContentBase64 = readFileSync(`${root}${JAR_DIGEST_FIXTURE_PATH}`).toString('base64'); }],
    ['omitted introduction bytes', proof => { delete proof.fixturePublicCommit.introductionContentBase64; }],
    ['omitted introduction commit', proof => { proof.fixturePublicCommit.commits.splice(1, 1); }],
    ['omitted accepted base', proof => { proof.fixturePublicCommit.commits.pop(); }],
    ['reordered fixture ancestry', proof => { proof.fixturePublicCommit.commits.reverse(); }],
    ['document path used as fixture path', proof => { proof.fixturePublicCommit.currentPath = proof.executionDigests.documents[0].currentPath; }],
  ]) {
    const receipt = structuredClone(baseCase().receipt); mutate(receipt.proof);
    assert.throws(() => requireGitleaksCaptureHashReviewReceipt(baseCase().e4, seal(receipt)), /Gitleaks/, label);
  }
});

test('JAR digest fixture receipt rejects field, source, introduction and public-Git rationale tampering after resealing', () => {
  for (const [label, mutate] of [
    ['wrong field', value => { value.source.field = 'UNREVIEWED_PUBLIC_COMMIT'; }],
    ['wrong path', value => { value.source.path = 'scripts/unreviewed.mjs'; }],
    ['wrong line', value => { value.source.line++; }],
    ['wrong line hash', value => { value.source.lineSha256 = 'a'.repeat(64); }],
    ['wrong literal columns', value => { value.source.derivedLiteralColumns.startOneBased++; }],
    ['wrong current blob', value => { value.source.blobSha = ORIGINAL_JAR_DIGEST_FIXTURE_BLOB; }],
    ['wrong current hash', value => { value.source.sha256 = ORIGINAL_JAR_DIGEST_FIXTURE_SHA256; }],
    ['wrong current byte length', value => { value.source.byteLength++; }],
    ['wrong introduction blob', value => { value.introductionSource.blobSha = CURRENT_FIXTURE_BLOB; }],
    ['wrong introduction hash', value => { value.introductionSource.sha256 = CURRENT_FIXTURE_SHA256; }],
    ['wrong introduction byte length', value => { value.introductionSource.byteLength++; }],
    ['wrong accepted main', value => { value.acceptedMainCommit = 'a'.repeat(40); }],
    ['wrong proof hash', value => { value.sourceProofCanonicalSha256 = 'a'.repeat(64); }],
    ['runtime digest rationale', value => { value.decision = baseCase().receipt.executionDigestReview.decision; }],
    ['cleared release block', value => { value.releaseBlocked = false; }],
    ['fabricated scanner columns', value => { value.scannerColumnsRetained = true; }],
  ]) {
    const receipt = structuredClone(baseCase().receipt); mutate(receipt.fixturePublicCommitReview);
    receipt.fixturePublicCommitReview = seal(receipt.fixturePublicCommitReview);
    assert.throws(() => requireGitleaksCaptureHashReviewReceipt(baseCase().e4, seal(receipt)), /receipt drift/, label);
  }
  for (const change of [value => { delete value.fixturePublicCommitReview; },
    value => { value.schemaVersion = 'M6_PR_E_E3_GITLEAKS_CAPTURE_HASH_REVIEW_EVIDENCE_V3'; }]) {
    const receipt = structuredClone(baseCase().receipt); change(receipt);
    assert.throws(() => requireGitleaksCaptureHashReviewReceipt(baseCase().e4, seal(receipt)), /receipt drift/);
  }
});

for (const [label, mutate] of [
  ['missing document proof', proof => { proof.executionDigests.documents.pop(); }],
  ['wrong document path', proof => { proof.executionDigests.documents[0].path = 'docs/unreviewed.json'; }],
  ['reordered documents', proof => { proof.executionDigests.documents.reverse(); }],
  ['missing current path', proof => { proof.executionDigests.documents[0].currentPath = []; }],
  ['missing introduction path', proof => { proof.executionDigests.documents[0].introductionPath = []; }],
  ['missing accepted base absence', proof => { proof.executionDigests.documents[0].basePath = []; }],
  ['missing introduction', proof => { proof.executionDigests.commits.splice(1, 1); }],
  ['missing accepted base', proof => { proof.executionDigests.commits.pop(); }],
  ['reordered ancestry', proof => { proof.executionDigests.commits.reverse(); }],
  ['duplicate ancestry', proof => { proof.executionDigests.commits.push(proof.executionDigests.commits[0]); }],
  ['changed commit preimage', proof => { proof.executionDigests.commits[0].contentBase64 = Buffer.from('unreviewed commit\n').toString('base64'); }],
  ['missing introduction parents', proof => { proof.executionDigests.introductionParents = []; }],
  ['wrong introduction parent', proof => { proof.executionDigests.introductionParents[0].commit = proof.executionDigests.commits[0]; }],
  ['missing parent document absence', proof => { proof.executionDigests.introductionParents[0].sourcePaths[0].pathProof = []; }],
  ['missing one parent document', proof => { proof.executionDigests.introductionParents[0].sourcePaths.pop(); }],
  ['self-asserted runtime Git proof', proof => { proof.executionDigests.runtimeInputGitProof = []; }],
  ['missing legacy capture input proof', proof => { proof.inputs.pop(); }],
  ['missing legacy plan metadata proof', proof => { delete proof.planMetadata; }],
  ['missing legacy scanner baseline', proof => { delete proof.planMetadata.scannerBaseline; }],
  ['missing all new document proofs', proof => { delete proof.executionDigests; }],
]) test(`JAR digest V5 replay rejects ${label} after resealing`, () => {
  const receipt = structuredClone(baseCase().receipt); mutate(receipt.proof);
  assert.throws(() => requireGitleaksCaptureHashReviewReceipt(baseCase().e4, seal(receipt)), /Gitleaks/);
});

for (const [label, mutate] of [
  ['wrong JSON pointer', value => { value.fields[0].jsonPointer = '/unreviewed'; }],
  ['wrong role', value => { value.fields[0].role = 'ANY_HASH'; }],
  ['wrong line', value => { value.fields[0].line++; }],
  ['wrong line digest', value => { value.fields[0].lineSha256 = 'a'.repeat(64); }],
  ['wrong scalar columns', value => { value.fields[0].derivedJsonValueColumns.startOneBased++; }],
  ['fabricated scanner columns', value => { value.fields[0].scannerColumnsRetained = true; }],
  ['duplicate typed fields', value => { value.fields[1] = value.fields[0]; }],
  ['missing finding', value => { value.findings.pop(); }],
  ['wrong document digest', value => { value.documents[0].sha256 = 'a'.repeat(64); }],
  ['wrong document blob identity', value => { value.documents[0].blobSha = 'a'.repeat(40); }],
  ['wrong document size', value => { value.documents[0].byteLength++; }],
  ['wrong document proof digest', value => { value.sourceProofCanonicalSha256 = 'a'.repeat(64); }],
  ['wrong accepted inventory digest', value => { value.acceptedGitleaksInventorySha256 = 'a'.repeat(64); }],
  ['wrong accepted base', value => { value.acceptedMainCommit = 'a'.repeat(40); }],
  ['false runtime Git-object claim', value => { value.inputDigestProvenance = 'RUNTIME_INPUTS_VERIFIED_AS_GIT_OBJECTS'; }],
  ['false canonical CI claim', value => { value.canonicalCiEvidence = true; }],
  ['false scanner columns claim', value => { value.scannerColumnsRetained = true; }],
  ['cleared local-execution limit', value => { value.localExecutionOnly = false; }],
  ['cleared nested release block', value => { value.releaseBlocked = false; }],
  ['changed disposition', value => { value.decision.disposition = 'MITIGATED'; }],
]) test(`JAR digest nested receipt rejects ${label} after resealing`, () => {
  const receipt = structuredClone(baseCase().receipt); mutate(receipt.executionDigestReview);
  receipt.executionDigestReview = seal(receipt.executionDigestReview);
  assert.throws(() => requireGitleaksCaptureHashReviewReceipt(baseCase().e4, seal(receipt)), /receipt drift/);
});

for (const [label, transform] of [
  ['changed fixed bytes', text => `${text}\n`],
  ['schema change', text => { const value = JSON.parse(text); value.schemaVersion = 2; return `${JSON.stringify(value, null, 2)}\n`; }],
  ['map cardinality change', text => { const value = JSON.parse(text); delete value.runtimeHashes[Object.keys(value.runtimeHashes)[0]]; return `${JSON.stringify(value, null, 2)}\n`; }],
  ['self-consistent resealed document', text => { const value = JSON.parse(text); value.cacheHashes[Object.keys(value.cacheHashes)[0]] = hash('modeled unapproved input'); return `${JSON.stringify(seal(value), null, 2)}\n`; }],
  ['new secret-like field', text => { const value = JSON.parse(text); value.unreviewedCredential = hash('unreviewed fixture value'); return `${JSON.stringify(value, null, 2)}\n`; }],
]) test(`JAR digest collector rejects ${label} even in hash-correct replacement Git objects`, () => {
  const model = modeledJarDigestFixture('unapproved complete-document replacement', {
    transformDocument: (path, bytes) => path === JAR_DIGEST_DOCUMENT_PATHS[1] ? transform(bytes.toString('utf8')) : bytes,
  });
  const e4 = model.prepareEvidence(syntheticE4());
  assert.throws(() => model.snapshot(e4), /Gitleaks.*(?:blob|path|document|source)/);
});

test('JAR digest cannot relabel all 121 rows as a later copy or unknown publication', () => {
  for (const commit of [baseCase().model.head, 'e'.repeat(40)]) {
    const e4 = mutatedEvidence(value => {
      value.scanners.gitleaks.findings = value.scanners.gitleaks.findings.map(row => JAR_DIGEST_DOCUMENT_PATHS.includes(row.path)
        ? normalizedDigestFinding(commit, row.path, row.startLine) : row).sort((a, b) => a.findingId.localeCompare(b.findingId));
    });
    assert.throws(() => baseCase().model.snapshot(e4), /Gitleaks|retained.*unavailable/);
  }
});

test('JAR digest merge introduction requires absence under every parent', () => {
  const model = modeledJarDigestFixture('two-parent digest introduction', { mergeIntroduction: true });
  const e4 = model.prepareEvidence(syntheticE4()), receipt = verifyGitleaksCaptureHashReview(e4, model.snapshot(e4));
  assert.equal(receipt.proof.executionDigests.introductionParents.length, 2);
  const fixtureTamper = structuredClone(receipt);
  assert.equal(fixtureTamper.proof.fixturePublicCommit.introductionParents.length, 2);
  fixtureTamper.proof.fixturePublicCommit.introductionParents.pop();
  assert.throws(() => requireGitleaksCaptureHashReviewReceipt(e4, seal(fixtureTamper)), /every fixture introduction parent/);
  receipt.proof.executionDigests.introductionParents.pop();
  assert.throws(() => requireGitleaksCaptureHashReviewReceipt(e4, seal(receipt)), /every execution introduction parent/);
});

test('JAR digest receipt replay freshly rereads fixed documents, fixture and complete current verifier bytes without mutating the checkout', async () => {
  const path = 'scripts/security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
  // An isolated module loader changes only this import's read result. The real
  // checkout, receipt and Git objects remain untouched throughout the test.
  const original = read(path);
  const injectedRead = `import { readFileSync as retainedReadFileSync } from 'node:fs';
export const fixtureReadControl = { changedPath: null, reads: {} };
const readFileSync = (path, ...options) => {
  const value = retainedReadFileSync(path, ...options);
  const target = ${JSON.stringify([JAR_DIGEST_DOCUMENT_PATHS[0], JAR_DIGEST_FIXTURE_PATH, JAR_VERIFIER_PATH])}.find(target => String(path).endsWith(target));
  if (target) {
    fixtureReadControl.reads[target] = (fixtureReadControl.reads[target] || 0) + 1;
    if (fixtureReadControl.changedPath === target) return typeof value === 'string' ? value + '\\n' : Buffer.concat([value, Buffer.from('\\n')]);
  }
  return value;
};`;
  assert.ok(original.includes("import { readFileSync } from 'node:fs';"));
  const source = original.replace("import { readFileSync } from 'node:fs';", injectedRead)
    .replace(/from '\.\/([^']+)'/g, (_, relative) => `from '${pathToFileURL(`${root}scripts/security/${relative}`).href}'`)
    .replaceAll('import.meta.url', JSON.stringify(pathToFileURL(`${root}${path}`).href));
  const isolated = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
  const { e4, receipt } = baseCase();
  assert.deepEqual(isolated.requireGitleaksCaptureHashReviewReceipt(e4, receipt), receipt);
  for (const target of [JAR_DIGEST_DOCUMENT_PATHS[0], JAR_DIGEST_FIXTURE_PATH, JAR_VERIFIER_PATH]) {
    const readsBefore = isolated.fixtureReadControl.reads[target]; assert.ok(readsBefore > 0);
    isolated.fixtureReadControl.changedPath = target;
    assert.throws(() => isolated.requireGitleaksCaptureHashReviewReceipt(e4, receipt), /fixed (?:execution|fixture) document bytes changed|Git source path\/blob mismatch/);
    assert.ok(isolated.fixtureReadControl.reads[target] > readsBefore);
    isolated.fixtureReadControl.changedPath = null;
  }
  assert.equal(hash(read(JAR_DIGEST_DOCUMENT_PATHS[0])), expectedDocuments[0].hash);
  assert.equal(hash(read(JAR_DIGEST_FIXTURE_PATH)), CURRENT_FIXTURE_SHA256);
});

let retainedCiCase;
function ciCase() {
  if (!retainedCiCase) {
    const model = modeledJarDigestFixture('full194 modeled CI merge', { mergeHead: true });
    const result = runSyntheticCiCallback({ publicCommitFixture: model }); assert.equal(result.error, undefined);
    const output = stage => {
      const block = result.logs.find(text => text.startsWith(`M6_PR_E_${stage}_BEGIN\n`));
      assert.ok(block, `missing ${stage} output`); return JSON.parse(block.split('\n')[1]);
    };
    retainedCiCase = { result, output };
  }
  return retainedCiCase;
}
function replayReviews(e4, value) {
  const capture = requireGitleaksCaptureHashReviewReceipt(e4, value.gitleaksCaptureHashReview);
  const publicReview = requireGitleaksPublicCommitReviewReceipt(e4, value.gitleaksPublicCommitReview, capture);
  requireGitleaksTestExpressionReviewReceipt(e4, value.gitleaksTestExpressionReview, publicReview, capture);
}

test('JAR digest full callback retains 194 findings and replays V5 through I2 I3 I4 and R2B', () => {
  const { result, output } = ciCase();
  assert.equal(result.commands.filter(([command]) => command === process.execPath).length, 1);
  assert.equal(result.logs.filter(text => /^M6_PR_E_.*_BEGIN\n/.test(text)).length, 14);
  const receipt = output('E3_GITLEAKS_CAPTURE_HASH_REVIEW');
  assert.equal(receipt.schemaVersion, 'M6_PR_E_E3_GITLEAKS_CAPTURE_HASH_REVIEW_EVIDENCE_V5');
  assert.equal(receipt.executionDigestReview.findings.length, 121);
  assert.deepEqual(receipt.fixturePublicCommitReview.finding, fixtureRow(result.e4));
  assert.equal(output('E4').scanners.gitleaks.findingCount, 194);
  assert.deepEqual(output('E4').scanners.gitleaks.findings, result.e4.scanners.gitleaks.findings);
  for (const stage of ['E3_I2_TRIAGE', 'E3_I3_TRIAGE', 'E3_I4_TRIAGE']) {
    const triage = output(stage);
    assert.equal(triage.decisions.filter(row => row.sourceClass === 'E4_GITLEAKS').length, 194);
    assert.deepEqual(triage.gitleaksCaptureHashReview, receipt);
    assert.equal(triage.appendOnlyReviewedFindingCount, 167);
    assert.equal(triage.summary.releaseBlocked, true);
    requireGitleaksTestExpressionTriage(result.e4, triage); replayReviews(result.e4, triage);
  }
  const reconciliation = output('E3_R2B_REMEDIATION').scannerIdentityReconciliation;
  assert.equal(reconciliation.currentScannerCounts.gitleaks, 194);
  assert.equal(reconciliation.reviewedAddedGitleaksFindingCount, 167);
  assert.equal(reconciliation.releaseBlocked, true); replayReviews(result.e4, reconciliation);
  assert.equal(output('E3_I4_TRIAGE').summary.notApplicableCount, 169);
  assert.equal(output('E3_I4_TRIAGE').cumulativeReviewedFindingCount, 235);
  assert.equal(output('E3_I4_TRIAGE').historicallyRemediatedFindingCount, 66);
});

test('JAR digest full callback fails current70, current191, current192 and current193 before any source-review collector or triage output', () => {
  for (const count of [70, 191, 192, 193]) {
    const result = runSyntheticCiCallback({ publicCommitFixture: baseCase().model,
      mutateEvidence: e4 => { e4.scanners.gitleaks.findings = count === 70 ? acceptedJarDigestLegacyFindings()
        : e4.scanners.gitleaks.findings.filter(row => count === 191 ? ![JAR_DIGEST_FIXTURE_PATH, JAR_VERIFIER_PATH].includes(row.path)
          : row.path !== (count === 192 ? JAR_VERIFIER_PATH : JAR_DIGEST_FIXTURE_PATH));
      e4.scanners.gitleaks.findingCount = e4.scanners.gitleaks.findings.length;
      e4.totalFindingCount = Object.values(e4.scanners).reduce((sum, scanner) => sum + scanner.findingCount, 0); } });
    assert.match(String(result.error), /current JAR graph requires all 194/);
    assert.equal(result.e4.scanners.gitleaks.findingCount, count);
    assert.equal(result.commands.filter(([command, args]) => command === 'git' && args[0] === 'cat-file').length, 0);
    assert.equal(result.logs.filter(text => /^M6_PR_E_E3_.*_BEGIN\n/.test(text)).length, 0);
    assert.equal(result.logs.some(text => text.startsWith('M6_PR_E_E4_SCANNER_EVIDENCE_BEGIN\n')), true);
  }
});

test('JAR digest serialized I2 I3 I4 reject missing legacy receipts, V5 tampering and a cleared release block', () => {
  const { result, output } = ciCase();
  for (const stage of ['E3_I2_TRIAGE', 'E3_I3_TRIAGE', 'E3_I4_TRIAGE']) {
    const original = output(stage);
    for (const mutate of [
      value => { delete value.gitleaksCaptureHashReview; },
      value => { delete value.gitleaksPublicCommitReview; },
      value => { delete value.gitleaksTestExpressionReview; },
      value => { delete value.gitleaksCaptureHashReview.executionDigestReview; },
      value => { delete value.gitleaksCaptureHashReview.fixturePublicCommitReview; },
      value => { delete value.gitleaksCaptureHashReview.verifierPublicGitReview; },
      value => { value.gitleaksCaptureHashReview.executionDigestReview.fields[0].role = 'ANY_HASH';
        value.gitleaksCaptureHashReview.executionDigestReview = seal(value.gitleaksCaptureHashReview.executionDigestReview);
        value.gitleaksCaptureHashReview = seal(value.gitleaksCaptureHashReview); },
      value => { value.appendOnlyReviewedFindingCount = 43; },
      value => { value.summary.releaseBlocked = false; },
      value => { value.decisions.push(value.decisions[0]); },
      value => { value.decisions.find(row => row.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-EXECUTION-DIGEST').reviewEvidence.jsonPointer = '/unreviewed'; },
      value => { value.decisions.find(row => row.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-EXECUTION-DIGEST').reviewEvidence.scannerColumnsRetained = true; },
      value => { value.decisions.find(row => row.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-FIXTURE-PUBLIC-COMMIT').reviewEvidence.sourceField = 'UNREVIEWED_PUBLIC_COMMIT'; },
      value => { value.decisions.find(row => row.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-VERIFIER-PUBLIC-GIT').reviewEvidence.metadataRole = 'ANY_PUBLIC_HASH'; },
    ]) {
      const value = structuredClone(original); mutate(value);
      assert.throws(() => requireGitleaksTestExpressionTriage(result.e4, seal(value)), /Gitleaks|receipt/);
    }
  }
});

test('JAR digest runtime and workflow consumers require all three source review receipts for 194', () => {
  const { result, output } = ciCase();
  const withoutReviews = value => {
    delete value.gitleaksCaptureHashReview; delete value.gitleaksPublicCommitReview; delete value.gitleaksTestExpressionReview;
    return seal(value);
  };
  assert.throws(() => applyRuntimeDeploymentReviews(withoutReviews(output('E3_I2_TRIAGE')), result.e4,
    json('i3-reviewed-findings'), output('E3_R1_REMEDIATION'), output('E3_R4_SERVER_DEPENDENCY_REMEDIATION')),
  /all three source review receipts/);
  assert.throws(() => applyWorkflowSupplyChainReviews(withoutReviews(output('E3_I3_TRIAGE')), result.e4,
    json('i4-reviewed-findings'), output('E3_R2A_REMEDIATION'), output('E3_R2B_REMEDIATION')),
  /all three source review receipts/);
});

test('JAR digest verifier proves two distinct accepted Git roles and separately binds current and introduction bytes', () => {
  const { e4, receipt, model } = baseCase(), review = receipt.verifierPublicGitReview;
  assert.equal(review.findings.length, 2);
  assert.equal(review.findings.every(row => row.commit === model.verifierIntro), true);
  assert.notEqual(model.verifierIntro, model.intro); assert.notEqual(model.verifierIntro, model.fixtureIntro);
  assert.equal(review.acceptedMainCommit, ACCEPTED_JAR_DIGEST_BASE); assert.equal(review.acceptedMainTree, model.baseTree);
  assert.equal(review.source.sha256, hash(read(JAR_VERIFIER_PATH)));
  assert.equal(review.source.blobSha, objectHash('blob', readFileSync(`${root}${JAR_VERIFIER_PATH}`)));
  assert.equal(review.introductionSource.sha256, hash(publishedVerifierIntroductionBytes()));
  assert.notEqual(review.source.blobSha, review.introductionSource.blobSha);
  assert.equal(review.sourceProofCanonicalSha256, hash(canonical(receipt.proof.verifierPublicGit)));
  assert.deepEqual(review.source.fields.map(row => [row.field, row.line, row.role]), [
    ['JAR_BASE', 22, 'ACCEPTED_MAIN_GIT_COMMIT_IDENTITY'], ['JAR_BASE_TREE', 23, 'ACCEPTED_MAIN_GIT_TREE_IDENTITY'],
  ]);
  for (const field of review.source.fields) {
    assert.equal(field.scannerColumnsRetained, false);
    const line = read(JAR_VERIFIER_PATH).split('\n')[field.line - 1];
    assert.equal(hash(line), field.lineSha256);
    assert.equal(line.slice(field.derivedLiteralColumns.startOneBased - 1, field.derivedLiteralColumns.endOneBased),
      field.field === 'JAR_BASE' ? review.acceptedMainCommit : review.acceptedMainTree);
  }
  const applied = applyGitleaksCaptureHashDecisions(currentI2(e4).decisions, receipt);
  const rows = applied.filter(row => row.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-VERIFIER-PUBLIC-GIT');
  assert.equal(rows.length, 2);
  for (const row of rows) {
    assert.equal(row.disposition, 'NOT_APPLICABLE');
    assert.equal(row.reviewEvidence.sourceBlobSha, review.introductionSource.blobSha);
    assert.equal(row.reviewEvidence.currentSourceBlobSha, review.source.blobSha);
    assert.equal(row.reviewEvidence.scannerColumnsRetained, false);
  }
});

for (const options of [
  { combinedVerifierIntroduction: true, fixtureIntroductionVersion: 'current' },
  { combinedVerifierIntroduction: true, fixtureIntroductionVersion: 'current', mergeIntroduction: true, mergeHead: true },
  { mergeVerifierIntroduction: true },
]) test(`JAR digest verifier supports exact public introduction bytes with modeled ancestry ${JSON.stringify(options)}`, () => {
  const model = modeledJarDigestFixture('alternate verifier metadata', options), e4 = model.prepareEvidence(syntheticE4());
  const receipt = verifyGitleaksCaptureHashReview(e4, model.snapshot(e4));
  assert.notEqual(model.verifierIntro, baseCase().model.verifierIntro);
  assert.equal(receipt.verifierPublicGitReview.findings.every(row => row.commit === model.verifierIntro), true);
  assert.equal(receipt.proof.verifierPublicGit.introductionParents.length, options.mergeIntroduction || options.mergeVerifierIntroduction ? 2 : 1);
  if (options.combinedVerifierIntroduction) {
    assert.equal(receipt.fixturePublicCommitReview.finding.commit, model.verifierIntro);
    assert.equal(receipt.executionDigestReview.findings.every(row => row.commit === model.verifierIntro), true);
  }
  assert.deepEqual(requireGitleaksCaptureHashReviewReceipt(e4, receipt), receipt);
  if (receipt.proof.verifierPublicGit.introductionParents.length === 2) {
    receipt.proof.verifierPublicGit.introductionParents.pop();
    assert.throws(() => requireGitleaksCaptureHashReviewReceipt(e4, seal(receipt)), /every verifier introduction parent/);
  }
});

test('JAR digest verifier rejects recomputed wrong rule, field, path and introduction identities', () => {
  const policy = plan.retainedFindings.find(row => row.ruleId === 'sourcegraph-access-token');
  for (const [label, change] of [
    ['another field', row => normalizedDigestFinding(row.commit, row.path, 24, policy)],
    ['another rule', row => normalizedDigestFinding(row.commit, row.path, row.startLine, plan.finding)],
    ['another path', row => normalizedDigestFinding(row.commit, 'scripts/security/unreviewed.mjs', row.startLine, policy)],
    ['later copy', row => normalizedDigestFinding(baseCase().model.head, row.path, row.startLine, policy)],
    ['unknown introduction', row => normalizedDigestFinding('e'.repeat(40), row.path, row.startLine, policy)],
  ]) {
    const e4 = mutatedEvidence(value => {
      const row = value.scanners.gitleaks.findings.find(row => row.path === JAR_VERIFIER_PATH);
      value.scanners.gitleaks.findings[value.scanners.gitleaks.findings.indexOf(row)] = change(row);
      value.scanners.gitleaks.findings.sort((a,b) => a.findingId.localeCompare(b.findingId));
    });
    assert.throws(() => verifyGitleaksCaptureHashReview(e4, snapshotFor(e4, baseCase().receipt.proof)), /Gitleaks|E4/, label);
  }
});

test('JAR digest verifier refuses wrong complete blobs and any introduction parent already containing the fields', () => {
  for (const [label, options] of [
    ['introduction bytes at current path', { transformVerifierCurrent: () => publishedVerifierIntroductionBytes() }],
    ['changed current bytes', { transformVerifierCurrent: bytes => Buffer.concat([bytes, Buffer.from('\n')]) }],
    ['different current declaration', { transformVerifierCurrent: bytes => bytes.toString().replace('const JAR_BASE =', 'const UNREVIEWED_BASE =') }],
    ['third introduction body', { transformVerifierIntroduction: bytes => Buffer.concat([bytes, Buffer.from('\n')]) }],
    ['different introduction field', { transformVerifierIntroduction: bytes => bytes.toString().replace('const JAR_BASE_TREE =', 'const UNREVIEWED_TREE =') }],
    ['second parent already has fields', { verifierParentContainsFields: true }],
  ]) {
    const model = modeledJarDigestFixture(label, options), e4 = model.prepareEvidence(syntheticE4());
    assert.throws(() => model.snapshot(e4), /Gitleaks.*(?:verifier|blob|path|source)/, label);
  }
});

test('JAR digest verifier replay rejects missing, substituted and reordered source proofs', () => {
  for (const [label, mutate] of [
    ['missing proof', proof => { delete proof.verifierPublicGit; }],
    ['missing current path', proof => { proof.verifierPublicGit.currentPath = []; }],
    ['missing introduction path', proof => { proof.verifierPublicGit.introductionPath = []; }],
    ['missing accepted path', proof => { proof.verifierPublicGit.basePath = []; }],
    ['missing accepted bytes', proof => { delete proof.verifierPublicGit.baseContentBase64; }],
    ['wrong accepted bytes', proof => { proof.verifierPublicGit.baseContentBase64 = Buffer.from('unreviewed\n').toString('base64'); }],
    ['wrong introduction bytes', proof => { proof.verifierPublicGit.introductionContentBase64 = readFileSync(`${root}${JAR_VERIFIER_PATH}`).toString('base64'); }],
    ['missing parents', proof => { proof.verifierPublicGit.introductionParents = []; }],
    ['missing parent path', proof => { proof.verifierPublicGit.introductionParents[0].sourcePath = []; }],
    ['missing parent bytes', proof => { delete proof.verifierPublicGit.introductionParents[0].contentBase64; }],
    ['wrong parent bytes', proof => { proof.verifierPublicGit.introductionParents[0].contentBase64 = Buffer.from('unreviewed\n').toString('base64'); }],
    ['wrong parent commit', proof => { proof.verifierPublicGit.introductionParents[0].commit = proof.verifierPublicGit.commits[0]; }],
    ['missing introduction commit', proof => { proof.verifierPublicGit.commits.splice(1, 1); }],
    ['missing accepted commit', proof => { proof.verifierPublicGit.commits.pop(); }],
    ['reordered ancestry', proof => { proof.verifierPublicGit.commits.reverse(); }],
    ['duplicate ancestry', proof => { proof.verifierPublicGit.commits.push(proof.verifierPublicGit.commits[0]); }],
    ['fixture proof substituted', proof => { proof.verifierPublicGit.currentPath = proof.fixturePublicCommit.currentPath; }],
  ]) {
    const receipt = structuredClone(baseCase().receipt); mutate(receipt.proof);
    assert.throws(() => requireGitleaksCaptureHashReviewReceipt(baseCase().e4, seal(receipt)), /Gitleaks/, label);
  }
});

test('JAR digest verifier replay rejects changed Git roles, derived positions and review claims', () => {
  for (const [label, mutate] of [
    ['field name', value => { value.source.fields[0].field = 'ANY_HASH'; }],
    ['field role', value => { value.source.fields[1].role = value.source.fields[0].role; }],
    ['field line', value => { value.source.fields[0].line++; }],
    ['field line hash', value => { value.source.fields[0].lineSha256 = 'a'.repeat(64); }],
    ['field columns', value => { value.source.fields[0].derivedLiteralColumns.startOneBased++; }],
    ['scanner columns', value => { value.source.fields[0].scannerColumnsRetained = true; }],
    ['missing finding', value => { value.findings.pop(); }],
    ['current blob', value => { value.source.blobSha = value.introductionSource.blobSha; }],
    ['accepted commit', value => { value.acceptedMainCommit = 'a'.repeat(40); }],
    ['accepted tree', value => { value.acceptedMainTree = 'a'.repeat(40); }],
    ['accepted source', value => { value.acceptedSourceSha256 = 'a'.repeat(64); }],
    ['proof hash', value => { value.sourceProofCanonicalSha256 = 'a'.repeat(64); }],
    ['digest rationale', value => { value.decision = baseCase().receipt.executionDigestReview.decision; }],
    ['release block', value => { value.releaseBlocked = false; }],
  ]) {
    const receipt = structuredClone(baseCase().receipt); mutate(receipt.verifierPublicGitReview);
    receipt.verifierPublicGitReview = seal(receipt.verifierPublicGitReview);
    assert.throws(() => requireGitleaksCaptureHashReviewReceipt(baseCase().e4, seal(receipt)), /receipt drift/, label);
  }
});
