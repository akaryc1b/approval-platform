import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { requireCompleteCurrentE4 } from './scanner-evidence-provenance.mjs';
import { bytesFromBase64, commitObject, treeEntries, verifyPath, objectHash } from './gitleaks-git-source-proof.mjs';

const PLAN_SHA256 = '888890064d4d2342053a94c01447dd46bea257b445abb07af4f5527d6913c914';
const PLAN_FILE_SHA256 = '233be0fe354400bc0420cd951335f97a68ebc2709a958fc065b1af8bdbbae572';
const PLAN_PATH = 'docs/m6/m6-pr-e-e3-gitleaks-capture-hash-review.json';
const SCANNER_BASELINE_PATH = 'docs/m6/m6-pr-e-e4-scanner-baseline.json';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const seal = value => { const { contentSha256, ...payload } = value; return stable({ ...payload, contentSha256: hash(canonical(payload)) }); };
const requireValue = (condition, message) => { if (!condition) throw new Error(`Gitleaks capture hash ${message}`); };
const shape = (value, keys, label) => requireValue(value && canonical(Object.keys(value).sort()) === canonical([...keys].sort()), `${label} shape drift`);
const SHA40 = /^[0-9a-f]{40}$/;
const key = finding => `${finding.sourceClass}:${finding.findingId}`;

export function readGitleaksCaptureHashReviewPlan() {
  const plan = JSON.parse(readFileSync(new URL('../../docs/m6/m6-pr-e-e3-gitleaks-capture-hash-review.json', import.meta.url), 'utf8'));
  requireValue(plan.contentSha256 === PLAN_SHA256 && seal(plan).contentSha256 === PLAN_SHA256, 'plan mismatch');
  return plan;
}
/** Only the fixed schema's reviewed roles, never a search for hash-shaped strings. */
export function gitleaksPlanMetadataFields() {
  const plan = readGitleaksCaptureHashReviewPlan();
  const bytes = readFileSync(new URL(`../../${PLAN_PATH}`, import.meta.url));
  requireValue(hash(bytes) === PLAN_FILE_SHA256 && bytes.toString('utf8') === `${JSON.stringify(plan, null, 2)}\n`, 'frozen plan bytes mismatch');
  const positions = new Map();
  const walk = (value, pointer, line) => {
    positions.set(pointer, { value, line });
    if (!value || typeof value !== 'object') return;
    let next = line + 1;
    for (const [name, child] of Object.entries(value)) {
      walk(child, `${pointer}/${name.replaceAll('~', '~0').replaceAll('/', '~1')}`, next);
      next += JSON.stringify(child, null, 2).split('\n').length;
    }
  };
  walk(plan, '', 1);
  const selectors = [
    ['/acceptedPublicBaseCommit', 'ACCEPTED_BASE_COMMIT'], ['/acceptedPublicBaseTreeSha', 'ACCEPTED_BASE_TREE'],
    ['/acceptedPublicBaseUrl', 'ACCEPTED_BASE_COMMIT_URL'], ['/scannerIdentity/sourceCommit', 'ACCEPTED_EXTERNAL_SCANNER_SOURCE_METADATA'],
    ...plan.retainedFindings.map((_, index) => [`/retainedFindings/${index}/commit`, 'ACCEPTED_INVENTORY_COMMIT_METADATA']),
    ['/source/blobSha', 'CAPTURE_SOURCE_BLOB'], ['/collector/blobSha', 'CAPTURE_COLLECTOR_BLOB'],
    ...plan.observations.map((_, index) => [`/observations/${index}/input/blobSha`, 'ACCEPTED_INPUT_BLOB']),
  ];
  requireValue(plan.retainedFindings.length === 29 && plan.observations.length === 3 && selectors.length === 38, 'fixed metadata role cardinality drift');
  const lines = bytes.toString('utf8').split('\n');
  return selectors.map(([jsonPointer, role]) => {
    const position = positions.get(jsonPointer);
    requireValue(position && typeof position.value === 'string', 'fixed metadata pointer absent');
    requireValue(role === 'ACCEPTED_BASE_COMMIT_URL'
      ? position.value === `https://github.com/${plan.repository}/commit/${plan.acceptedPublicBaseCommit}`
      : SHA40.test(position.value), 'fixed metadata field type/value drift');
    return { jsonPointer, role, line: position.line, lineSha256: hash(lines[position.line - 1]) };
  });
}
function planMetadataSource() {
  const bytes = readFileSync(new URL(`../../${PLAN_PATH}`, import.meta.url));
  requireValue(hash(bytes) === PLAN_FILE_SHA256, 'frozen plan bytes mismatch');
  return { path: PLAN_PATH, blobSha: objectHash('blob', bytes), sha256: PLAN_FILE_SHA256, byteLength: bytes.length };
}
function expectedPlanMetadataFinding(plan, commit, field) {
  const policy = plan.retainedFindings.find(finding => finding.ruleId === 'sourcegraph-access-token');
  requireValue(policy && policy.sourceClass === 'E4_GITLEAKS', 'accepted metadata scanner policy absent');
  return expectedFinding({ finding: policy, source: { path: PLAN_PATH } }, commit, field);
}
function expectedFinding(plan, commit, observation) {
  const { ruleId, description, sourceClass } = plan.finding, path = plan.source.path, line = observation.line;
  const fingerprint = `${commit}:${path}:${ruleId}:${line}`;
  return { commit, description, endLine: line, findingId: hash(['GITLEAKS', fingerprint, ruleId, path, String(line)].join('\0')),
    fingerprint, path, ruleId, sourceClass, startLine: line };
}
function requireCurrentFindings(e4, plan) {
  requireCompleteCurrentE4(e4, plan.repository);
  const scanner = e4.scanners.gitleaks;
  for (const [field, value] of Object.entries(plan.scannerIdentity)) requireValue(scanner[field] === value, `scanner identity drift ${field}`);
  requireValue([32, 70].includes(scanner.findingCount), 'requires all 29 retained records and exactly three additions, with only the optional 38 proven plan fields');
  const ids = new Set(plan.retainedFindings.map(finding => finding.findingId));
  requireValue(canonical(scanner.findings.filter(finding => ids.has(finding.findingId))) === canonical(plan.retainedFindings),
    'retained finding metadata/order drift');
  requireValue(canonical(scanner.findings) === canonical([...scanner.findings].sort((a, b) => a.findingId.localeCompare(b.findingId))), 'current finding order drift');
  const unretained = scanner.findings.filter(finding => !ids.has(finding.findingId));
  const added = unretained.filter(finding => finding.path === plan.source.path);
  requireValue(added.length === 3 && SHA40.test(added[0].commit || '') && added.every(finding => finding.commit === added[0].commit),
    'exact common introduction required');
  const expected = plan.observations.map(observation => expectedFinding(plan, added[0].commit, observation))
    .sort((a, b) => a.findingId.localeCompare(b.findingId));
  requireValue(canonical(added) === canonical(expected), 'unreviewed addition or exact finding identity drift');
  const metadata = unretained.filter(finding => finding.path === PLAN_PATH);
  if (scanner.findingCount === 70) {
    requireValue(metadata.length === 38 && SHA40.test(metadata[0].commit || '')
      && metadata.every(finding => finding.commit === metadata[0].commit), 'exact plan metadata introduction required');
    const expectedMetadata = gitleaksPlanMetadataFields().map(field => expectedPlanMetadataFinding(plan, metadata[0].commit, field))
      .sort((a, b) => a.findingId.localeCompare(b.findingId));
    requireValue(canonical(metadata) === canonical(expectedMetadata), 'unreviewed plan metadata identity drift');
  } else requireValue(metadata.length === 0, 'unexpected plan metadata records');
  requireValue(unretained.length === added.length + metadata.length, 'unreviewed finding outside exact source paths');
  return added;
}
function verifyBlob(contentBase64, expected) {
  const bytes = bytesFromBase64(contentBase64);
  requireValue(bytes.length === expected.byteLength && objectHash('blob', bytes) === expected.blobSha
    && hash(bytes) === expected.sha256, 'complete source blob/hash mismatch');
  return bytes;
}
function verifyProof(e4, findings, proof, plan) {
  shape(proof, ['commits', 'introductionParents', 'baseSourcePath', 'source', 'collector', 'inputs',
    ...(e4.scanners.gitleaks.findingCount === 70 ? ['planMetadata'] : [])], 'proof');
  requireValue(Array.isArray(proof.commits) && proof.commits.length >= 2 && proof.commits.length <= 4096, 'Git ancestry proof required');
  const commits = proof.commits.map(commitObject);
  requireValue(new Set(commits.map(commit => commit.sha)).size === commits.length, 'duplicate ancestry commit');
  requireValue(commits[0].sha === e4.commitSha && commits[0].tree === e4.checkout.expectedHeadTreeSha, 'exact current Git Head/tree mismatch');
  for (let index = 0; index < commits.length - 1; index++) requireValue(commits[index].parents.includes(commits[index + 1].sha), 'Git parent ancestry mismatch');
  const base = commits.at(-1), introduced = commits.find(commit => commit.sha === findings[0].commit);
  requireValue(base.sha === plan.acceptedPublicBaseCommit && base.tree === plan.acceptedPublicBaseTreeSha
    && introduced && introduced.sha !== base.sha && introduced.parents.length > 0, 'introduction/accepted public base provenance mismatch');
  requireValue(Array.isArray(proof.introductionParents) && proof.introductionParents.length === introduced.parents.length,
    'every introduction parent absence proof required');
  for (let index = 0; index < introduced.parents.length; index++) {
    const row = proof.introductionParents[index]; shape(row, ['commit', 'sourcePath'], 'introduction parent');
    const parent = commitObject(row.commit);
    requireValue(parent.sha === introduced.parents[index], 'introduction parent identity/order mismatch');
    verifyPath(row.sourcePath, parent.tree, plan.source.path, null);
  }
  verifyPath(proof.baseSourcePath, base.tree, plan.source.path, null);
  for (const name of ['source', 'collector']) {
    const row = proof[name], expected = plan[name];
    shape(row, ['contentBase64', 'currentPath', 'introductionPath'], name);
    verifyBlob(row.contentBase64, expected);
    verifyPath(row.currentPath, commits[0].tree, expected.path, expected.blobSha);
    verifyPath(row.introductionPath, introduced.tree, expected.path, expected.blobSha);
  }
  const text = bytesFromBase64(proof.source.contentBase64).toString('utf8'), capture = JSON.parse(text);
  requireValue(capture && !Array.isArray(capture) && capture.status === 'PASSED_CAPTURE_ONLY'
    && capture.sourceHead === base.sha && capture.sourceTree === base.tree && capture.sourceDirty === true
    && capture.canonicalCiEvidence === false && capture.captureInputsStable === true
    && capture.collectorSha256 === plan.collector.sha256 && capture.collectorSnapshot === 'capture.py'
    && capture.captureInputs && typeof capture.captureInputs === 'object' && !Array.isArray(capture.captureInputs),
  'exact capture object/producer binding mismatch');
  requireValue(Array.isArray(proof.inputs) && proof.inputs.length === plan.observations.length, 'all three source input proofs required');
  for (let index = 0; index < plan.observations.length; index++) {
    const observation = plan.observations[index], input = observation.input, row = proof.inputs[index];
    shape(row, ['path', 'contentBase64', 'basePath', 'introductionPath', 'currentPath'], 'source input');
    requireValue(row.path === input.path && observation.jsonPointer === `/captureInputs/${input.path.replaceAll('~', '~0').replaceAll('/', '~1')}`,
      'exact input path/JSON pointer mismatch');
    const bytes = verifyBlob(row.contentBase64, input), digest = hash(bytes);
    requireValue(Object.hasOwn(capture.captureInputs, input.path) && capture.captureInputs[input.path] === digest,
      'capture scalar is not SHA256 of accepted complete source bytes');
    const line = text.split('\n')[observation.line - 1];
    requireValue(line === `    "${input.path}": "${digest}",` && hash(line) === observation.lineSha256,
      'exact capture input field/line mismatch');
    verifyPath(row.basePath, base.tree, input.path, input.blobSha);
    verifyPath(row.introductionPath, introduced.tree, input.path, input.blobSha);
    verifyPath(row.currentPath, commits[0].tree, input.path, input.blobSha);
  }
}
function verifyPlanMetadataProof(e4, proof, plan) {
  shape(proof, ['commits', 'introductionParents', 'baseSourcePath', 'source', 'scannerBaseline'], 'plan metadata proof');
  const findings = e4.scanners.gitleaks.findings.filter(finding => finding.path === PLAN_PATH);
  requireValue(findings.length === 38 && Array.isArray(proof.commits) && proof.commits.length >= 2
    && proof.commits.length <= 4096, 'plan metadata ancestry required');
  const commits = proof.commits.map(commitObject), current = commits[0], base = commits.at(-1);
  requireValue(new Set(commits.map(row => row.sha)).size === commits.length, 'duplicate plan metadata ancestry');
  requireValue(current.sha === e4.commitSha && current.tree === e4.checkout.expectedHeadTreeSha,
    'plan metadata exact current Git Head/tree mismatch');
  for (let index = 0; index < commits.length - 1; index++) requireValue(commits[index].parents.includes(commits[index + 1].sha), 'plan metadata parent ancestry mismatch');
  const introduced = commits.find(row => row.sha === findings[0].commit);
  requireValue(base.sha === plan.acceptedPublicBaseCommit && base.tree === plan.acceptedPublicBaseTreeSha
    && introduced && introduced.sha !== base.sha && introduced.parents.length > 0, 'plan metadata introduction/base mismatch');
  requireValue(Array.isArray(proof.introductionParents) && proof.introductionParents.length === introduced.parents.length,
    'every plan metadata introduction parent required');
  for (let index = 0; index < introduced.parents.length; index++) {
    const row = proof.introductionParents[index]; shape(row, ['commit', 'sourcePath'], 'plan metadata parent');
    const parent = commitObject(row.commit); requireValue(parent.sha === introduced.parents[index], 'plan metadata parent identity/order mismatch');
    verifyPath(row.sourcePath, parent.tree, PLAN_PATH, null);
  }
  verifyPath(proof.baseSourcePath, base.tree, PLAN_PATH, null);
  const source = planMetadataSource(); shape(proof.source, ['contentBase64', 'currentPath', 'introductionPath'], 'plan metadata source');
  const bytes = verifyBlob(proof.source.contentBase64, source);
  requireValue(canonical(JSON.parse(bytes.toString('utf8'))) === canonical(plan), 'plan metadata source/schema mismatch');
  verifyPath(proof.source.currentPath, current.tree, PLAN_PATH, source.blobSha);
  verifyPath(proof.source.introductionPath, introduced.tree, PLAN_PATH, source.blobSha);
  // The external source commit is documentary metadata in the accepted Git blob.
  // No upstream object, tag-to-commit or source-to-binary reproducibility claim.
  shape(proof.scannerBaseline, ['contentBase64', 'basePath'], 'accepted scanner baseline');
  const baselineBytes = bytesFromBase64(proof.scannerBaseline.contentBase64);
  verifyPath(proof.scannerBaseline.basePath, base.tree, SCANNER_BASELINE_PATH, objectHash('blob', baselineBytes));
  const baseline = JSON.parse(baselineBytes.toString('utf8')), scanner = baseline.scanners?.gitleaks;
  requireValue(baseline.schemaVersion === 'M6_PR_E_E4_SCANNER_BASELINE_V1' && baseline.repository === plan.repository
    && scanner?.sourceRepository === 'gitleaks/gitleaks' && scanner.version === plan.scannerIdentity.version
    && scanner.sourceCommit === plan.scannerIdentity.sourceCommit
    && scanner.installation?.sha256 === plan.scannerIdentity.assetSha256, 'accepted external scanner metadata tuple mismatch');
  const fields = gitleaksPlanMetadataFields();
  // The enclosing capture proof already verifies all five actual blob preimages,
  // including accepted-base paths for the three input documents.
  return seal({ schemaVersion: 'M6_PR_E_E3_GITLEAKS_PLAN_METADATA_REVIEW_V1', source, findings, fields,
    sourceProofCanonicalSha256: hash(canonical(proof)), acceptedPublicBaseCommit: base.sha,
    acceptedInventoryProvenance: { acceptedE4CanonicalSha256: plan.acceptedE4CanonicalSha256,
      retainedFindingCount: plan.retainedFindingCount, retainedFindingsSha256: hash(canonical(plan.retainedFindings)),
      basis: 'EXACT_ACCEPTED_INVENTORY_METADATA_NO_INDEPENDENT_COMMIT_OBJECT_REVALIDATION' },
    scannerBaselinePath: SCANNER_BASELINE_PATH, scannerBaselineBlobSha: objectHash('blob', baselineBytes),
    externalScannerProvenance: 'ACCEPTED_BASELINE_METADATA_ONLY_NO_UPSTREAM_OBJECT_TAG_OR_REPRODUCIBLE_BUILD_CLAIM',
    decision: { disposition: 'NOT_APPLICABLE', evidenceCode: 'EXACT_PINNED_PLAN_GIT_IDENTITY_METADATA', exploitPreconditionsSatisfied: false } });
}
function receipt(e4, findings, proof, plan) {
  const planMetadataReview = e4.scanners.gitleaks.findingCount === 70 ? verifyPlanMetadataProof(e4, proof.planMetadata, plan) : null;
  return seal({ schemaVersion: planMetadataReview ? 'M6_PR_E_E3_GITLEAKS_CAPTURE_HASH_REVIEW_EVIDENCE_V2' : 'M6_PR_E_E3_GITLEAKS_CAPTURE_HASH_REVIEW_EVIDENCE_V1', repository: e4.repository,
    commitSha: e4.commitSha, sourceE4CanonicalSha256: e4.contentSha256, planCanonicalSha256: plan.contentSha256,
    acceptedPublicBaseCommit: plan.acceptedPublicBaseCommit, acceptedE4CanonicalSha256: plan.acceptedE4CanonicalSha256,
    retainedFindingCount: plan.retainedFindingCount, currentFindingCount: e4.scanners.gitleaks.findingCount,
    retainedFindingsSha256: hash(canonical(plan.retainedFindings)), findings, source: plan.source,
    collector: plan.collector, observations: plan.observations, proof, decision: plan.decision, ...plan.limits,
    ...(planMetadataReview ? { planMetadataReview } : {}) });
}
export function verifyGitleaksCaptureHashReview(e4, snapshot) {
  const plan = readGitleaksCaptureHashReviewPlan(), findings = requireCurrentFindings(e4, plan);
  requireValue(snapshot?.repository === e4.repository && snapshot.commitSha === e4.commitSha
    && snapshot.currentE4CanonicalSha256 === e4.contentSha256, 'exact current E4 binding required');
  verifyProof(e4, findings, snapshot.proof, plan);
  return receipt(e4, findings, snapshot.proof, plan);
}
export function requireGitleaksCaptureHashReviewReceipt(e4, evidence) {
  const verified = verifyGitleaksCaptureHashReview(e4, { repository: evidence?.repository, commitSha: evidence?.commitSha,
    currentE4CanonicalSha256: evidence?.sourceE4CanonicalSha256, proof: evidence?.proof });
  requireValue(canonical(evidence) === canonical(verified), 'receipt drift');
  return verified;
}
function decisionReview(evidence, finding) {
  if (finding.path === PLAN_PATH) {
    const metadata = evidence.planMetadataReview, field = metadata.fields.find(item => item.line === finding.startLine);
    return { reviewLayer: 'E3-I2-APPEND_ONLY-GITLEAKS-PLAN-METADATA', reviewCanonicalSha256: evidence.contentSha256,
      sourceCommitSha: finding.commit, sourceBlobSha: metadata.source.blobSha, sourcePath: metadata.source.path,
      sourceLineSha256: field.lineSha256, jsonPointer: field.jsonPointer, metadataRole: field.role,
      acceptedPublicBaseCommit: evidence.acceptedPublicBaseCommit, evidenceCode: metadata.decision.evidenceCode,
      ...(field.role === 'ACCEPTED_EXTERNAL_SCANNER_SOURCE_METADATA' ? { externalScannerProvenance: metadata.externalScannerProvenance } : {}),
      ...(field.role === 'ACCEPTED_INVENTORY_COMMIT_METADATA' ? { acceptedInventoryProvenance: metadata.acceptedInventoryProvenance } : {}),
      exploitPreconditionsSatisfied: false };
  }
  const observation = evidence.observations.find(item => item.line === finding.startLine);
  return { reviewLayer: 'E3-I2-APPEND_ONLY-GITLEAKS-CAPTURE-HASH', reviewCanonicalSha256: evidence.contentSha256,
    sourceCommitSha: finding.commit, sourceBlobSha: evidence.source.blobSha, sourcePath: evidence.source.path,
    sourceLineSha256: observation.lineSha256, jsonPointer: observation.jsonPointer, input: observation.input,
    acceptedPublicBaseCommit: evidence.acceptedPublicBaseCommit, evidenceCode: evidence.decision.evidenceCode,
    exploitPreconditionsSatisfied: false };
}
export function gitleaksCaptureReviewFindings(evidence) {
  return [...evidence.findings, ...(evidence.planMetadataReview?.findings || [])];
}
function requireDecisionIdentity(decision, finding) {
  requireValue(decision && ['ruleId', 'commit', 'fingerprint', 'path', 'startLine', 'endLine']
    .every(field => decision.sourceIdentity?.[field] === finding[field]), 'decision source identity drift');
}
export function applyGitleaksCaptureHashDecisions(decisions, evidence) {
  const findings = gitleaksCaptureReviewFindings(evidence), expectedCount = evidence.planMetadataReview ? 41 : 3;
  const byKey = new Map(findings.map(finding => [key(finding), finding]));
  requireValue(byKey.size === expectedCount && findings.length === expectedCount, 'distinct exact reviewed identities required');
  const applied = new Set();
  const result = decisions.map(decision => {
    const finding = byKey.get(key(decision)); if (!finding) return decision;
    requireDecisionIdentity(decision, finding);
    requireValue(decision.disposition === 'UNRESOLVED' && decision.severityBand === 'UNKNOWN' && !decision.reviewEvidence,
      'may only review exact unresolved findings');
    requireValue(!applied.has(key(finding)), 'duplicate current decision');
    applied.add(key(finding));
    return stable({ ...decision, disposition: 'NOT_APPLICABLE', exploitPreconditions: ['ACTUAL_CREDENTIAL_MATERIAL_PRESENT'],
      reviewEvidence: decisionReview(evidence, finding) });
  });
  requireValue(applied.size === expectedCount, 'exactly three capture and any proven 38 metadata current decisions required');
  return result;
}
export function requireGitleaksCaptureHashDecisions(triage, evidence) {
  for (const finding of gitleaksCaptureReviewFindings(evidence)) {
    const decision = triage.decisions.find(item => key(item) === key(finding));
    requireDecisionIdentity(decision, finding);
    requireValue(decision.disposition === 'NOT_APPLICABLE' && decision.severityBand === 'UNKNOWN'
      && canonical(decision.exploitPreconditions) === canonical(['ACTUAL_CREDENTIAL_MATERIAL_PRESENT'])
      && canonical(decision.reviewEvidence) === canonical(decisionReview(evidence, finding)), 'current disposition drift');
  }
  requireValue(triage.decisions.filter(item => item.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-CAPTURE-HASH').length === 3,
    'current review count drift');
  requireValue(triage.decisions.filter(item => item.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-PLAN-METADATA').length === (evidence.planMetadataReview ? 38 : 0),
    'current metadata review count drift');
}

/** Read only Git objects; no source, index, ref or scanner mutation. */
export function buildGitleaksCaptureHashReviewSnapshot(e4, { root, git = spawnSync } = {}) {
  const plan = readGitleaksCaptureHashReviewPlan(), findings = requireCurrentFindings(e4, plan), cache = new Map();
  const readObject = (type, sha) => {
    requireValue(SHA40.test(sha || ''), 'Git object identity required');
    const id = `${type}:${sha}`;
    if (!cache.has(id)) {
      const result = git('git', ['cat-file', type, sha], { cwd: root, maxBuffer: 32 * 1024 * 1024 });
      requireValue(result.status === 0, `Git ${type} read failed`);
      cache.set(id, { sha, contentBase64: Buffer.from(result.stdout).toString('base64') });
    }
    return cache.get(id);
  };
  const ancestry = (head, target) => {
    const previous = new Map([[head, null]]), queue = [head]; let found = false;
    while (queue.length && previous.size <= 4096) {
      const next = queue.shift(), parsed = commitObject(readObject('commit', next));
      if (next === target) { found = true; break; }
      for (const parent of parsed.parents) if (!previous.has(parent)) { previous.set(parent, next); queue.push(parent); }
    }
    requireValue(found, 'required introduction/base absent from bounded Git ancestry');
    const rows = [];
    for (let next = target; next !== null; next = previous.get(next)) rows.unshift(readObject('commit', next));
    return rows;
  };
  const commits = [...ancestry(e4.commitSha, findings[0].commit), ...ancestry(findings[0].commit, plan.acceptedPublicBaseCommit).slice(1)];
  const parsed = commits.map(commitObject), current = parsed[0], base = parsed.at(-1), intro = parsed.find(row => row.sha === findings[0].commit);
  const pathProof = (tree, path) => {
    const rows = []; let nextTree = tree;
    for (const part of path.split('/')) {
      const row = readObject('tree', nextTree); rows.push(row);
      const entry = treeEntries(bytesFromBase64(row.contentBase64)).find(item => item.name === part);
      if (!entry || entry.mode !== '40000') break;
      nextTree = entry.sha;
    }
    return rows;
  };
  const sourceProof = expected => ({ contentBase64: readObject('blob', expected.blobSha).contentBase64,
    currentPath: pathProof(current.tree, expected.path), introductionPath: pathProof(intro.tree, expected.path) });
  const snapshot = { repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
    proof: { commits, introductionParents: intro.parents.map(sha => {
      const commit = readObject('commit', sha); return { commit, sourcePath: pathProof(commitObject(commit).tree, plan.source.path) };
    }), baseSourcePath: pathProof(base.tree, plan.source.path), source: sourceProof(plan.source), collector: sourceProof(plan.collector),
    inputs: plan.observations.map(({ input }) => ({ path: input.path, ...sourceProof(input), basePath: pathProof(base.tree, input.path) })) } };
  if (e4.scanners.gitleaks.findingCount === 70) {
    const finding = e4.scanners.gitleaks.findings.find(item => item.path === PLAN_PATH), source = planMetadataSource();
    const metadataCommits = [...ancestry(e4.commitSha, finding.commit), ...ancestry(finding.commit, plan.acceptedPublicBaseCommit).slice(1)];
    const metadataIntro = commitObject(readObject('commit', finding.commit));
    const baselinePath = pathProof(base.tree, SCANNER_BASELINE_PATH);
    const baselineEntry = treeEntries(bytesFromBase64(baselinePath.at(-1).contentBase64))
      .find(entry => entry.name === SCANNER_BASELINE_PATH.split('/').at(-1));
    requireValue(baselineEntry?.mode === '100644', 'accepted scanner baseline file absent');
    snapshot.proof.planMetadata = { commits: metadataCommits,
      introductionParents: metadataIntro.parents.map(sha => {
        const commit = readObject('commit', sha); return { commit, sourcePath: pathProof(commitObject(commit).tree, PLAN_PATH) };
      }), baseSourcePath: pathProof(base.tree, PLAN_PATH), source: {
        contentBase64: readObject('blob', source.blobSha).contentBase64,
        currentPath: pathProof(current.tree, PLAN_PATH), introductionPath: pathProof(metadataIntro.tree, PLAN_PATH) },
      scannerBaseline: { contentBase64: readObject('blob', baselineEntry.sha).contentBase64, basePath: baselinePath } };
  }
  verifyGitleaksCaptureHashReview(e4, snapshot);
  return snapshot;
}
