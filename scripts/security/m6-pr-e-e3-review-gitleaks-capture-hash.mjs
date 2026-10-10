import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { requireCompleteCurrentE4 } from './scanner-evidence-provenance.mjs';
import { bytesFromBase64, commitObject, treeEntries, verifyPath, objectHash } from './gitleaks-git-source-proof.mjs';

const PLAN_SHA256 = '888890064d4d2342053a94c01447dd46bea257b445abb07af4f5527d6913c914';
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
  requireValue(scanner.findingCount === plan.retainedFindingCount + plan.addedFindingCount, 'requires all 29 retained records and exactly three additions');
  const ids = new Set(plan.retainedFindings.map(finding => finding.findingId));
  requireValue(canonical(scanner.findings.filter(finding => ids.has(finding.findingId))) === canonical(plan.retainedFindings),
    'retained finding metadata/order drift');
  requireValue(canonical(scanner.findings) === canonical([...scanner.findings].sort((a, b) => a.findingId.localeCompare(b.findingId))), 'current finding order drift');
  const added = scanner.findings.filter(finding => !ids.has(finding.findingId));
  requireValue(added.length === 3 && SHA40.test(added[0].commit || '') && added.every(finding => finding.commit === added[0].commit),
    'exact common introduction required');
  const expected = plan.observations.map(observation => expectedFinding(plan, added[0].commit, observation))
    .sort((a, b) => a.findingId.localeCompare(b.findingId));
  requireValue(canonical(added) === canonical(expected), 'unreviewed addition or exact finding identity drift');
  return added;
}
function verifyBlob(contentBase64, expected) {
  const bytes = bytesFromBase64(contentBase64);
  requireValue(bytes.length === expected.byteLength && objectHash('blob', bytes) === expected.blobSha
    && hash(bytes) === expected.sha256, 'complete source blob/hash mismatch');
  return bytes;
}
function verifyProof(e4, findings, proof, plan) {
  shape(proof, ['commits', 'introductionParents', 'baseSourcePath', 'source', 'collector', 'inputs'], 'proof');
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
function receipt(e4, findings, proof, plan) {
  return seal({ schemaVersion: 'M6_PR_E_E3_GITLEAKS_CAPTURE_HASH_REVIEW_EVIDENCE_V1', repository: e4.repository,
    commitSha: e4.commitSha, sourceE4CanonicalSha256: e4.contentSha256, planCanonicalSha256: plan.contentSha256,
    acceptedPublicBaseCommit: plan.acceptedPublicBaseCommit, acceptedE4CanonicalSha256: plan.acceptedE4CanonicalSha256,
    retainedFindingCount: plan.retainedFindingCount, currentFindingCount: e4.scanners.gitleaks.findingCount,
    retainedFindingsSha256: hash(canonical(plan.retainedFindings)), findings, source: plan.source,
    collector: plan.collector, observations: plan.observations, proof, decision: plan.decision, ...plan.limits });
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
  const observation = evidence.observations.find(item => item.line === finding.startLine);
  return { reviewLayer: 'E3-I2-APPEND_ONLY-GITLEAKS-CAPTURE-HASH', reviewCanonicalSha256: evidence.contentSha256,
    sourceCommitSha: finding.commit, sourceBlobSha: evidence.source.blobSha, sourcePath: evidence.source.path,
    sourceLineSha256: observation.lineSha256, jsonPointer: observation.jsonPointer, input: observation.input,
    acceptedPublicBaseCommit: evidence.acceptedPublicBaseCommit, evidenceCode: evidence.decision.evidenceCode,
    exploitPreconditionsSatisfied: false };
}
function requireDecisionIdentity(decision, finding) {
  requireValue(decision && ['ruleId', 'commit', 'fingerprint', 'path', 'startLine', 'endLine']
    .every(field => decision.sourceIdentity?.[field] === finding[field]), 'decision source identity drift');
}
export function applyGitleaksCaptureHashDecisions(decisions, evidence) {
  const byKey = new Map(evidence.findings.map(finding => [key(finding), finding]));
  requireValue(byKey.size === 3 && evidence.findings.length === 3, 'three distinct reviewed identities required');
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
  requireValue(applied.size === 3, 'exactly three current decisions required');
  return result;
}
export function requireGitleaksCaptureHashDecisions(triage, evidence) {
  for (const finding of evidence.findings) {
    const decision = triage.decisions.find(item => key(item) === key(finding));
    requireDecisionIdentity(decision, finding);
    requireValue(decision.disposition === 'NOT_APPLICABLE' && decision.severityBand === 'UNKNOWN'
      && canonical(decision.exploitPreconditions) === canonical(['ACTUAL_CREDENTIAL_MATERIAL_PRESENT'])
      && canonical(decision.reviewEvidence) === canonical(decisionReview(evidence, finding)), 'current disposition drift');
  }
  requireValue(triage.decisions.filter(item => item.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-CAPTURE-HASH').length === 3,
    'current review count drift');
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
  verifyGitleaksCaptureHashReview(e4, snapshot);
  return snapshot;
}
