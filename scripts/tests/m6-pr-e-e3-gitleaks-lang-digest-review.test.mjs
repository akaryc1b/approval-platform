import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { modeledLangDigestFixture, acceptedLangDigestLegacyFindings, normalizedDigestFinding,
  resealLangDigestEvidence } from './fixtures/lang-digest-review-fixture.mjs';
import { canonical, seal } from './fixtures/public-commit-review-fixture.mjs';
import { readGitleaksCaptureHashReviewPlan, requireGitleaksCaptureInventory,
  buildGitleaksCaptureHashReviewSnapshot, verifyGitleaksCaptureHashReview,
  requireGitleaksCaptureHashReviewReceipt, gitleaksCaptureReviewFindings,
  applyGitleaksCaptureHashDecisions, requireGitleaksCaptureHashDecisions }
  from '../security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
import { ASSEMBLY_ENFORCER_LANG_GRAPH, gitleaksLangExecutionDigestFields, langExecutionDocuments,
  ACCEPTED_LANG_GITLEAKS_194_SHA256 } from '../security/gitleaks-lang-execution-digests.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const hash = value => createHash('sha256').update(value).digest('hex');
const plan = readGitleaksCaptureHashReviewPlan();
const initial = () => ({ schemaVersion: 'M6_PR_E_E4_SCANNER_EVIDENCE_V1', repository: plan.repository,
  e2CurrentContentSha256: hash('modeled current E2'), allScannersCompleted: true, rawScannerReportsRetained: false,
  candidateSecretMaterialRetained: false, authoritativeGitHubInventoryStillUnavailable: true,
  workstreamReleaseBlocked: true, scanners: Object.fromEntries(['osv', 'gitleaks', 'zizmor', 'semgrep'].map(name =>
    [name, { scanCompleted: true, rawReportRetained: false, findings: [], findingCount: 0,
      ...(name === 'gitleaks' ? { candidateSecretMaterialRetained: false } : {}),
      ...(name === 'semgrep' ? { sourceSnippetRetained: false } : {}) }])) });
let retained;
function baseCase() {
  if (!retained) {
    const model = modeledLangDigestFixture(), e4 = model.prepareEvidence(initial());
    const snapshot = model.snapshot(e4), receipt = verifyGitleaksCaptureHashReview(e4, snapshot);
    retained = { model, e4, snapshot, receipt };
  }
  return retained;
}
const snapshotFor = (e4, proof) => ({ repository: e4.repository, commitSha: e4.commitSha,
  currentE4CanonicalSha256: e4.contentSha256, proof });
const changedEvidence = mutate => { const e4 = structuredClone(baseCase().e4); mutate(e4); return resealLangDigestEvidence(e4); };
const langRows = e4 => e4.scanners.gitleaks.findings.filter(row => row.path.includes('/assembly-enforcer-lang-evidence/'));

test('Lang digest fields bind precisely two complete maps and 180 typed scalars', () => {
  const context = langExecutionDocuments(), fields = gitleaksLangExecutionDigestFields();
  assert.equal(context.documents.length, 2); assert.equal(context.provenance.length, 3);
  assert.equal(fields.length, 180); assert.equal(new Set(fields.map(row => `${row.path}:${row.jsonPointer}`)).size, 180);
  assert.deepEqual(context.documents.map(row => row.fields.length), [11, 169]);
  assert.deepEqual(context.documents.map(row => new Set(row.fields.map(field => field.role)).size), [1, 1]);
  assert.equal(context.documents[0].fields[0].role, 'LANG_CAPTURE_RUNTIME_FILE_DIGEST');
  assert.equal(context.documents[1].fields[0].role, 'LANG_CAPTURE_CACHE_FILE_DIGEST');
  for (const { source, fields } of context.documents) {
    const bytes = readFileSync(`${root}${source.path}`), value = JSON.parse(bytes), lines = bytes.toString('utf8').split('\n');
    assert.equal(hash(bytes), source.sha256);
    for (const field of fields) {
      const key = field.jsonPointer.slice(1).replaceAll('~1', '/').replaceAll('~0', '~');
      const line = lines[field.line - 1], columns = field.derivedJsonValueColumns;
      assert.equal(hash(line), field.lineSha256); assert.match(value[key], /^[a-f0-9]{64}$/);
      assert.equal(line.slice(columns.startOneBased - 1, columns.endOneBased), value[key]);
      assert.equal(field.scannerColumnsRetained, false);
    }
  }
  assert.equal(hash(canonical(acceptedLangDigestLegacyFindings())), ACCEPTED_LANG_GITLEAKS_194_SHA256);
});

test('Lang digest V6 retains all 374 observations and every prior proof group', () => {
  const { model, e4, receipt } = baseCase(), before = canonical(e4);
  assert.equal(requireGitleaksCaptureInventory(e4), 374); assert.equal(e4.e2GraphDigest, ASSEMBLY_ENFORCER_LANG_GRAPH);
  assert.equal(receipt.schemaVersion, 'M6_PR_E_E3_GITLEAKS_CAPTURE_HASH_REVIEW_EVIDENCE_V6');
  assert.equal(receipt.currentFindingCount, 374); assert.equal(receipt.executionDigestReview.findings.length, 121);
  assert.equal(receipt.planMetadataReview.findings.length, 38); assert.equal(receipt.verifierPublicGitReview.findings.length, 2);
  assert.ok(receipt.fixturePublicCommitReview.finding); assert.equal(receipt.findings.length, 3);
  const review = receipt.langExecutionDigestReview;
  assert.equal(review.findings.length, 180); assert.equal(review.documents.length, 2); assert.equal(review.captureProvenance.length, 3);
  assert.deepEqual(review.fields, gitleaksLangExecutionDigestFields());
  assert.equal(review.acceptedGitleaksInventorySha256, ACCEPTED_LANG_GITLEAKS_194_SHA256);
  assert.equal(review.localExecutionOnly, true); assert.equal(review.canonicalCiEvidence, false); assert.equal(review.releaseBlocked, true);
  assert.equal(review.scannerColumnsRetained, false); assert.equal(gitleaksCaptureReviewFindings(receipt).length, 345);
  assert.deepEqual(e4.scanners.gitleaks.findings.filter(row => !langRows(e4).includes(row)), acceptedLangDigestLegacyFindings());
  assert.deepEqual(requireGitleaksCaptureHashReviewReceipt(e4, receipt), receipt); assert.equal(canonical(e4), before);
  assert.ok(model.commands.every(([command, args]) => command === 'git' && args[0] === 'cat-file'));
});

for (const options of [{ mergeHead: true }, { mergeIntroduction: true }]) {
  test(`Lang digest accepts exact alternate introduction ancestry ${JSON.stringify(options)}`, () => {
    const model = modeledLangDigestFixture('alternate Lang publication', options), e4 = model.prepareEvidence(initial());
    const receipt = verifyGitleaksCaptureHashReview(e4, model.snapshot(e4));
    assert.notEqual(model.intro, baseCase().model.intro);
    assert.ok(receipt.langExecutionDigestReview.findings.every(row => row.commit === model.intro));
    if (options.mergeIntroduction) assert.equal(receipt.proof.langExecutionDigests.introductionParents.length, 2);
  });
}

test('Lang digest refuses missing extra reordered or changed findings before Git reads', () => {
  for (const mutate of [
    e4 => { e4.scanners.gitleaks.findings.pop(); },
    e4 => { e4.scanners.gitleaks.findings.push(langRows(e4)[0]); },
    e4 => { e4.scanners.gitleaks.findings.reverse(); },
    e4 => { langRows(e4)[0].description = 'unreviewed'; },
    e4 => { e4.scanners.gitleaks.findings.find(row => !langRows(e4).includes(row)).description = 'unreviewed'; },
    e4 => { e4.e2GraphDigest = hash('unknown graph'); },
    e4 => { e4.e2GraphDigest = '73825d80bd2a55d0507742bac096e73309100911d69692cd34dc753674bdb9c5'; },
    e4 => { e4.workstreamReleaseBlocked = false; },
  ]) {
    const e4 = changedEvidence(mutate); let calls = 0;
    assert.throws(() => buildGitleaksCaptureHashReviewSnapshot(e4, { root, git: () => { calls++; throw new Error('unexpected Git'); } }), /Gitleaks|current E4/);
    assert.equal(calls, 0);
  }
});

test('Lang digest refuses recomputed wrong field rule path and introduction identities', () => {
  for (const change of [
    row => normalizedDigestFinding(row.commit, row.path, 1),
    row => normalizedDigestFinding(row.commit, 'src/unrelated-secret.txt', row.startLine),
    row => normalizedDigestFinding(row.commit, row.path, row.startLine, { ...plan.finding, ruleId: 'unreviewed-rule' }),
    row => normalizedDigestFinding(baseCase().model.head, row.path, row.startLine),
  ]) {
    const e4 = changedEvidence(value => {
      const row = langRows(value)[0]; value.scanners.gitleaks.findings[value.scanners.gitleaks.findings.indexOf(row)] = change(row);
      value.scanners.gitleaks.findings.sort((a,b) => a.findingId.localeCompare(b.findingId));
    });
    assert.throws(() => verifyGitleaksCaptureHashReview(e4, snapshotFor(e4, baseCase().receipt.proof)), /Gitleaks/);
  }
});

test('Lang digest refuses resealed map capture and collector replacements in valid Git objects', () => {
  const context = langExecutionDocuments();
  for (const phase of ['transformIntroduction', 'transformCurrent'])
  for (const path of [...context.documents.map(row => row.source.path), ...context.provenance.map(row => row.path)]) {
    const model = modeledLangDigestFixture('unapproved source replacement', { [phase]: (name, bytes) => {
      if (name !== path) return bytes;
      if (!name.endsWith('.json')) return Buffer.concat([bytes, Buffer.from('\n')]);
      const value = JSON.parse(bytes);
      if (name.endsWith('-inputs.json')) value[Object.keys(value)[0]] = hash('unapproved hash-shaped value');
      else value.runtimeInputsStable = false;
      return Buffer.from(`${JSON.stringify(seal(value), null, 2)}\n`);
    } });
    assert.throws(() => model.snapshot(model.prepareEvidence(initial())), /Gitleaks source proof Git source path\/blob mismatch/);
  }
});

test('Lang digest requires every parent absence and exact current introduction and accepted paths', () => {
  const model = modeledLangDigestFixture('two-parent Lang introduction', { mergeIntroduction: true });
  const e4 = model.prepareEvidence(initial()), original = model.snapshot(e4);
  for (const mutate of [
    proof => { proof.introductionParents.pop(); },
    proof => { proof.introductionParents.reverse(); },
    proof => { proof.introductionParents[0].sourcePaths.pop(); },
    proof => { proof.documents[0].currentPath = proof.documents[0].basePath; },
    proof => { proof.documents[0].introductionPath = proof.documents[0].basePath; },
    proof => { proof.documents[0].basePath = proof.documents[0].currentPath; },
    proof => { proof.provenance.pop(); },
    proof => { proof.provenance[0].currentPath = proof.provenance[0].basePath; },
    proof => { proof.commits.pop(); },
    proof => { proof.commits.reverse(); },
  ]) {
    const snapshot = structuredClone(original); mutate(snapshot.proof.langExecutionDigests);
    assert.throws(() => verifyGitleaksCaptureHashReview(e4, snapshot), /Gitleaks/);
  }
  const populated = modeledLangDigestFixture('parent already has maps', { parentContainsDocument: true });
  assert.throws(() => populated.snapshot(populated.prepareEvidence(initial())), /Gitleaks source proof Git source path\/blob mismatch/);
});

test('Lang digest receipt and 345 decisions reject resealed field count disposition and identity drift', () => {
  const { e4, receipt } = baseCase();
  for (const mutate of [
    value => { delete value.langExecutionDigestReview; },
    value => { value.langExecutionDigestReview.fields[0].role = 'ANY_HASH'; },
    value => { value.langExecutionDigestReview.findings.pop(); },
    value => { value.langExecutionDigestReview.captureProvenance.pop(); },
    value => { value.langExecutionDigestReview.canonicalCiEvidence = true; },
  ]) {
    const changed = structuredClone(receipt); mutate(changed);
    if (changed.langExecutionDigestReview) changed.langExecutionDigestReview = seal(changed.langExecutionDigestReview);
    assert.throws(() => requireGitleaksCaptureHashReviewReceipt(e4, seal(changed)), /receipt drift/);
  }
  const decisions = gitleaksCaptureReviewFindings(receipt).map(finding => ({ sourceClass: finding.sourceClass,
    findingId: finding.findingId, disposition: 'UNRESOLVED', severityBand: 'UNKNOWN', sourceIdentity: {
      ...Object.fromEntries(['ruleId', 'commit', 'fingerprint', 'path', 'startLine', 'endLine'].map(name => [name, finding[name]])) } }));
  const reviewed = applyGitleaksCaptureHashDecisions(decisions, receipt);
  assert.equal(reviewed.length, 345); requireGitleaksCaptureHashDecisions({ decisions: reviewed }, receipt);
  assert.equal(reviewed.filter(row => row.reviewEvidence.reviewLayer.endsWith('-LANG-EXECUTION-DIGEST')).length, 180);
  assert.throws(() => applyGitleaksCaptureHashDecisions(decisions.slice(1), receipt), /current decisions required/);
  assert.throws(() => applyGitleaksCaptureHashDecisions([...decisions.slice(1), decisions[1]], receipt), /duplicate current decision/);
  assert.throws(() => applyGitleaksCaptureHashDecisions(reviewed, receipt), /unresolved/);
  reviewed.at(-1).reviewEvidence.metadataRole = 'ANY_HASH';
  assert.throws(() => requireGitleaksCaptureHashDecisions({ decisions: reviewed }, receipt), /current disposition drift/);
});

test('Lang digest rereads pinned maps and capture sources after prior successful receipt replay', async () => {
  const helperPath = 'scripts/security/gitleaks-lang-execution-digests.mjs';
  const classifierPath = 'scripts/security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
  const helper = readFileSync(`${root}${helperPath}`, 'utf8');
  const injected = `import { readFileSync as originalRead } from 'node:fs';
export const readControl = { changedPath: null };
const readFileSync = (path, ...options) => {
  const value = originalRead(path, ...options);
  return readControl.changedPath && String(path).endsWith(readControl.changedPath)
    ? Buffer.concat([Buffer.from(value), Buffer.from('\\n')]) : value;
};`;
  const moduleSource = (source, path) => source.replace(/from '\.\/([^']+)'/g,
    (_, relative) => `from '${pathToFileURL(`${root}scripts/security/${relative}`).href}'`)
    .replaceAll('import.meta.url', JSON.stringify(pathToFileURL(`${root}${path}`).href));
  const helperSource = moduleSource(helper.replace("import { readFileSync } from 'node:fs';", injected), helperPath);
  const helperUrl = `data:text/javascript;base64,${Buffer.from(helperSource).toString('base64')}`;
  const isolatedHelper = await import(helperUrl);
  const classifierSource = moduleSource(readFileSync(`${root}${classifierPath}`, 'utf8')
    .replaceAll("from './gitleaks-lang-execution-digests.mjs'", `from '${helperUrl}'`), classifierPath);
  const isolated = await import(`data:text/javascript;base64,${Buffer.from(classifierSource).toString('base64')}`);
  const { e4, receipt } = baseCase();
  assert.deepEqual(isolated.requireGitleaksCaptureHashReviewReceipt(e4, receipt), receipt);
  const context = langExecutionDocuments();
  for (const path of [...context.documents.map(row => row.source.path), ...context.provenance.map(row => row.path)]) {
    isolatedHelper.readControl.changedPath = path;
    assert.throws(() => isolated.requireGitleaksCaptureHashReviewReceipt(e4, receipt), /fixed source bytes changed/);
    isolatedHelper.readControl.changedPath = null;
  }
  assert.deepEqual(isolated.requireGitleaksCaptureHashReviewReceipt(e4, receipt), receipt);
});
