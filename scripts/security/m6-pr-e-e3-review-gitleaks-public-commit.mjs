import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { bytesFromBase64, commitObject, treeEntries, verifyPath, objectHash } from './gitleaks-git-source-proof.mjs';
import { requireGitleaksCaptureHashReviewReceipt, gitleaksCaptureReviewFindings } from './m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
import { requireCompleteCurrentE4 } from './scanner-evidence-provenance.mjs';

const PLAN_SHA256 = '3ba1b2a520fb29a3adcc81b7c26b5f0e54da7fd3f411769fb6c6a81ac43ba590';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const seal = value => { const { contentSha256, ...payload } = value; return stable({ ...payload, contentSha256: hash(canonical(payload)) }); };
const requireValue = (condition, message) => { if (!condition) throw new Error(`Gitleaks public commit ${message}`); };
const SHA40 = /^[0-9a-f]{40}$/;
const key = finding => `${finding.sourceClass}:${finding.findingId}`;

export function readGitleaksPublicCommitReviewPlan() {
  const plan = JSON.parse(readFileSync(new URL('../../docs/m6/m6-pr-e-e3-gitleaks-public-commit-review.json', import.meta.url), 'utf8'));
  requireValue(plan.contentSha256 === PLAN_SHA256 && seal(plan).contentSha256 === PLAN_SHA256, 'plan mismatch');
  return plan;
}

function expectedFinding(plan, commit) {
  const ruleId = plan.finding.ruleParts.join(''), path = plan.source.path, line = plan.source.line;
  const fingerprint = `${commit}:${path}:${ruleId}:${line}`;
  return { commit, description: plan.finding.descriptionParts.join(''), endLine: line,
    findingId: hash(['GITLEAKS', fingerprint, ruleId, path, String(line)].join('\0')),
    fingerprint, path, ruleId, sourceClass: plan.finding.sourceClass, startLine: line };
}

/** Every old record and its order is pinned; exactly one derived identity may be added. */
function requireCurrentFinding(e4, plan, captureHashReview = null) {
  if (captureHashReview) requireGitleaksCaptureHashReviewReceipt(e4, captureHashReview);
  requireCompleteCurrentE4(e4, plan.repository);
  const scanner = e4.scanners.gitleaks;
  for (const [field, value] of Object.entries(plan.scannerIdentity)) {
    requireValue(scanner[field] === value, `scanner identity drift ${field}`);
  }
  requireValue(scanner.findingCount === plan.retainedFindingCount + 1 + (captureHashReview ? gitleaksCaptureReviewFindings(captureHashReview).length : 0), 'requires exactly one addition');
  const ids = new Set(plan.retainedFindings.map(finding => finding.findingId));
  const retained = scanner.findings.filter(finding => ids.has(finding.findingId));
  requireValue(canonical(retained) === canonical(plan.retainedFindings), 'retained finding metadata/order drift');
  requireValue(canonical(scanner.findings) === canonical([...scanner.findings].sort((a, b) => a.findingId.localeCompare(b.findingId))),
    'current finding order drift');
  const captureIds = new Set(captureHashReview ? gitleaksCaptureReviewFindings(captureHashReview).map(finding => finding.findingId) : []);
  const added = scanner.findings.filter(finding => !ids.has(finding.findingId) && !captureIds.has(finding.findingId));
  requireValue(added.length === 1 && SHA40.test(added[0].commit || '')
    && canonical(added[0]) === canonical(expectedFinding(plan, added[0].commit)), 'unreviewed addition or identity drift');
  return added[0];
}


function verifyProof(e4, finding, proof, plan) {
  requireValue(proof && canonical(Object.keys(proof).sort()) === canonical(
    ['basePath', 'commits', 'currentPath', 'introductionPath', 'sourceBase64']), 'source proof shape drift');
  requireValue(Array.isArray(proof.commits) && proof.commits.length >= 2 && proof.commits.length <= 4096,
    'Git ancestry proof required');
  const commits = proof.commits.map(commitObject);
  requireValue(new Set(commits.map(item => item.sha)).size === commits.length, 'duplicate ancestry commit');
  requireValue(commits[0].sha === e4.commitSha && commits[0].tree === e4.checkout.expectedHeadTreeSha,
    'exact current Git Head/tree mismatch');
  for (let index = 0; index < commits.length - 1; index++) {
    requireValue(commits[index].parents.includes(commits[index + 1].sha), 'Git parent ancestry mismatch');
  }
  const base = commits.at(-1), introduced = commits.at(-2);
  requireValue(base.sha === plan.acceptedPublicBaseCommit && introduced.sha === finding.commit
    && introduced.tree === plan.introductionTreeSha
    && canonical(introduced.parents) === canonical([base.sha]), 'Git introduction/base provenance mismatch');
  const bytes = bytesFromBase64(proof.sourceBase64), source = plan.source;
  requireValue(objectHash('blob', bytes) === source.blobSha && hash(bytes) === source.sha256, 'source blob mismatch');
  const text = bytes.toString('utf8'), line = text.split('\n')[source.line - 1];
  requireValue(hash(line || '') === source.lineSha256
    && line === `  "${source.field}": "${plan.acceptedPublicBaseCommit}",`
    && JSON.parse(text)[source.field] === plan.acceptedPublicBaseCommit, 'exact public reference field/line mismatch');
  verifyPath(proof.currentPath, commits[0].tree, source.path, source.blobSha);
  verifyPath(proof.introductionPath, introduced.tree, source.path, source.blobSha);
  verifyPath(proof.basePath, base.tree, source.path, null);
}
function receipt(e4, finding, proof, plan, captureHashReview = null) {
  return seal({ schemaVersion: 'M6_PR_E_E3_GITLEAKS_PUBLIC_COMMIT_REVIEW_EVIDENCE_V1',
    repository: e4.repository, commitSha: e4.commitSha, sourceE4CanonicalSha256: e4.contentSha256,
    planCanonicalSha256: plan.contentSha256, finding, source: plan.source,
    ...(captureHashReview ? { sourceCaptureHashReviewCanonicalSha256: captureHashReview.contentSha256, separatelyReviewedCaptureFindingCount: 3,
      ...(captureHashReview.planMetadataReview ? { separatelyReviewedPlanMetadataFindingCount: 38 } : {}) } : {}),
    acceptedPublicBaseCommit: plan.acceptedPublicBaseCommit, introductionTreeSha: plan.introductionTreeSha,
    retainedFindingCount: plan.retainedFindingCount, currentFindingCount: e4.scanners.gitleaks.findingCount,
    retainedFindingsSha256: hash(canonical(plan.retainedFindings)), proof,
    decision: plan.decision, releaseBlocked: true, rawCandidateMaterialRetained: false });
}

export function verifyGitleaksPublicCommitReview(e4, snapshot, captureHashReview = null) {
  const plan = readGitleaksPublicCommitReviewPlan(), finding = requireCurrentFinding(e4, plan, captureHashReview);
  requireValue(snapshot?.repository === e4.repository && snapshot.commitSha === e4.commitSha
    && snapshot.currentE4CanonicalSha256 === e4.contentSha256, 'exact current E4 binding required');
  verifyProof(e4, finding, snapshot.proof, plan);
  return receipt(e4, finding, snapshot.proof, plan, captureHashReview);
}
export function requireGitleaksPublicCommitReviewReceipt(e4, evidence, captureHashReview = null) {
  const verified = verifyGitleaksPublicCommitReview(e4, { repository: evidence?.repository,
    commitSha: evidence?.commitSha, currentE4CanonicalSha256: evidence?.sourceE4CanonicalSha256, proof: evidence?.proof }, captureHashReview);
  requireValue(canonical(evidence) === canonical(verified), 'receipt drift');
  return verified;
}

export function publicCommitDecisionReview(evidence) {
  return { reviewLayer: 'E3-I2-APPEND_ONLY-GITLEAKS-PUBLIC-COMMIT', reviewCanonicalSha256: evidence.contentSha256,
    sourceCommitSha: evidence.finding.commit, sourceBlobSha: evidence.source.blobSha,
    sourcePath: evidence.source.path, sourceLineSha256: evidence.source.lineSha256,
    evidenceCode: evidence.decision.evidenceCode, exploitPreconditionsSatisfied: false };
}
function requireDecisionIdentity(finding, evidence) {
  requireValue(finding && ['ruleId', 'commit', 'fingerprint', 'path', 'startLine', 'endLine']
    .every(field => finding.sourceIdentity?.[field] === evidence.finding[field]), 'decision source identity drift');
}
export function applyGitleaksPublicCommitDecision(decisions, evidence) {
  let applied = 0;
  const result = decisions.map(finding => {
    if (key(finding) !== key(evidence.finding)) return finding;
    requireDecisionIdentity(finding, evidence);
    requireValue(finding.disposition === 'UNRESOLVED' && finding.severityBand === 'UNKNOWN' && !finding.reviewEvidence,
      'may only review the exact unresolved finding');
    applied++;
    return stable({ ...finding, disposition: 'NOT_APPLICABLE', exploitPreconditions: ['ACTUAL_CREDENTIAL_MATERIAL_PRESENT'],
      reviewEvidence: publicCommitDecisionReview(evidence) });
  });
  requireValue(applied === 1, 'exactly one current decision required');
  return result;
}
export function requireGitleaksPublicCommitDecision(triage, evidence) {
  const finding = triage.decisions.find(item => key(item) === key(evidence.finding));
  requireDecisionIdentity(finding, evidence);
  requireValue(finding.disposition === 'NOT_APPLICABLE' && finding.severityBand === 'UNKNOWN'
    && canonical(finding.exploitPreconditions) === canonical(['ACTUAL_CREDENTIAL_MATERIAL_PRESENT'])
    && canonical(finding.reviewEvidence) === canonical(publicCommitDecisionReview(evidence)), 'current disposition drift');
  requireValue(triage.decisions.filter(item => item.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-PUBLIC-COMMIT').length === 1,
    'current review count drift');
}

/** Only read Git objects. Hash/path verification is repeated when consuming the receipt. */
export function buildGitleaksPublicCommitReviewSnapshot(e4, { root, git = spawnSync, captureHashReview = null } = {}) {
  const plan = readGitleaksPublicCommitReviewPlan(), finding = requireCurrentFinding(e4, plan, captureHashReview);
  const readObject = (type, sha) => {
    requireValue(SHA40.test(sha || ''), 'Git object identity required');
    const result = git('git', ['cat-file', type, sha], { cwd: root, maxBuffer: 32 * 1024 * 1024 });
    requireValue(result.status === 0, `Git ${type} read failed`);
    return { sha, contentBase64: Buffer.from(result.stdout).toString('base64') };
  };
  // Search actual parents deterministically, including the second parent of a
  // normal main merge. Retain every edge of the chosen path for receipt replay.
  const rows = new Map(), previous = new Map([[e4.commitSha, null]]), queue = [e4.commitSha];
  let found = false;
  while (queue.length && rows.size < 4096) {
    const next = queue.shift(), row = readObject('commit', next), parsed = commitObject(row);
    rows.set(next, row);
    if (next === finding.commit) { found = true; break; }
    for (const parent of parsed.parents) {
      if (parent === plan.acceptedPublicBaseCommit || previous.has(parent)) continue;
      previous.set(parent, next); queue.push(parent);
    }
  }
  requireValue(found, 'introduction absent from bounded Git ancestry');
  const commits = [];
  for (let next = finding.commit; next !== null; next = previous.get(next)) commits.unshift(rows.get(next));
  commits.push(readObject('commit', plan.acceptedPublicBaseCommit));
  const parsed = commits.map(commitObject);
  const pathProof = tree => {
    const rows = [], parts = plan.source.path.split('/');
    let nextTree = tree;
    for (const part of parts) {
      const row = readObject('tree', nextTree); rows.push(row);
      const entry = treeEntries(bytesFromBase64(row.contentBase64)).find(item => item.name === part);
      if (!entry || entry.mode !== '40000') break;
      nextTree = entry.sha;
    }
    return rows;
  };
  const snapshot = { repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
    proof: { commits, currentPath: pathProof(parsed[0].tree), introductionPath: pathProof(parsed.at(-2).tree),
      basePath: pathProof(parsed.at(-1).tree), sourceBase64: readObject('blob', plan.source.blobSha).contentBase64 } };
  // Do not return a partial or merely asserted proof, including on alternate publication SHAs.
  const evidence = verifyGitleaksPublicCommitReview(e4, snapshot, captureHashReview);
  requireValue(evidence.finding.commit === finding.commit, 'builder identity drift');
  return snapshot;
}
