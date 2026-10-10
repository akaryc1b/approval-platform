import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { requireCompleteCurrentE4 } from './scanner-evidence-provenance.mjs';

const PLAN_SHA256 = '3ba1b2a520fb29a3adcc81b7c26b5f0e54da7fd3f411769fb6c6a81ac43ba590';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const objectHash = (type, bytes) => createHash('sha1').update(`${type} ${bytes.length}\0`).update(bytes).digest('hex');
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
function requireCurrentFinding(e4, plan) {
  requireCompleteCurrentE4(e4, plan.repository);
  const scanner = e4.scanners.gitleaks;
  for (const [field, value] of Object.entries(plan.scannerIdentity)) {
    requireValue(scanner[field] === value, `scanner identity drift ${field}`);
  }
  requireValue(scanner.findingCount === plan.retainedFindingCount + 1, 'requires exactly one addition');
  const ids = new Set(plan.retainedFindings.map(finding => finding.findingId));
  const retained = scanner.findings.filter(finding => ids.has(finding.findingId));
  requireValue(canonical(retained) === canonical(plan.retainedFindings), 'retained finding metadata/order drift');
  requireValue(canonical(scanner.findings) === canonical([...scanner.findings].sort((a, b) => a.findingId.localeCompare(b.findingId))),
    'current finding order drift');
  const added = scanner.findings.filter(finding => !ids.has(finding.findingId));
  requireValue(added.length === 1 && SHA40.test(added[0].commit || '')
    && canonical(added[0]) === canonical(expectedFinding(plan, added[0].commit)), 'unreviewed addition or identity drift');
  return added[0];
}

function bytesFromBase64(value) {
  requireValue(typeof value === 'string', 'object bytes required');
  const bytes = Buffer.from(value, 'base64');
  requireValue(bytes.toString('base64') === value, 'noncanonical object bytes');
  return bytes;
}
function commitObject(row) {
  requireValue(row && canonical(Object.keys(row).sort()) === canonical(['contentBase64', 'sha']), 'commit object shape drift');
  const bytes = bytesFromBase64(row.contentBase64);
  requireValue(SHA40.test(row.sha || '') && objectHash('commit', bytes) === row.sha, 'Git commit object hash mismatch');
  const text = bytes.toString('utf8'), header = text.split('\n\n')[0], lines = header.split('\n');
  requireValue(text.includes('\n\n') && /^tree [0-9a-f]{40}$/.test(lines[0])
    && lines.every(line => !/^(tree|parent)(?:\s|$)/.test(line) || /^(tree|parent) [0-9a-f]{40}$/.test(line)),
    'Git commit header syntax mismatch');
  const trees = [...header.matchAll(/^tree ([0-9a-f]{40})$/gm)].map(match => match[1]);
  const parents = [...header.matchAll(/^parent ([0-9a-f]{40})$/gm)].map(match => match[1]);
  requireValue(trees.length === 1 && lines.slice(1, parents.length + 1).every(line => /^parent /.test(line))
    && /^author .+ [0-9]+ [+-][0-9]{4}$/.test(lines[parents.length + 1] || '')
    && /^committer .+ [0-9]+ [+-][0-9]{4}$/.test(lines[parents.length + 2] || ''), 'Git commit tree/parents/identity syntax mismatch');
  return { sha: row.sha, tree: trees[0], parents };
}
function treeEntries(bytes) {
  const entries = [], seen = new Set();
  let offset = 0;
  while (offset < bytes.length) {
    const space = bytes.indexOf(32, offset), end = bytes.indexOf(0, space + 1);
    requireValue(space > offset && end > space && end + 21 <= bytes.length, 'Git tree encoding mismatch');
    const mode = bytes.subarray(offset, space).toString('utf8'), name = bytes.subarray(space + 1, end).toString('utf8');
    requireValue(['40000', '100644', '100755', '120000', '160000'].includes(mode)
      && name && !name.includes('/') && !seen.has(name), 'Git tree entry mismatch');
    seen.add(name);
    entries.push({ mode, name, sha: bytes.subarray(end + 1, end + 21).toString('hex') });
    offset = end + 21;
  }
  return entries;
}
function verifyPath(trees, rootTree, path, expectedBlob) {
  requireValue(Array.isArray(trees), 'Git path proof required');
  const parts = path.split('/');
  let expectedTree = rootTree;
  for (let index = 0; index < parts.length; index++) {
    const row = trees[index];
    requireValue(row && canonical(Object.keys(row).sort()) === canonical(['contentBase64', 'sha']), 'Git path proof shape drift');
    const bytes = bytesFromBase64(row.contentBase64);
    requireValue(row.sha === expectedTree && objectHash('tree', bytes) === expectedTree, 'Git path tree hash mismatch');
    const entry = treeEntries(bytes).find(item => item.name === parts[index]);
    if (!entry) {
      requireValue(expectedBlob === null && trees.length === index + 1, 'Git source path absent');
      return;
    }
    if (index === parts.length - 1) {
      requireValue(expectedBlob !== null && entry.mode === '100644' && entry.sha === expectedBlob
        && trees.length === parts.length, 'Git source path/blob mismatch');
      return;
    }
    requireValue(entry.mode === '40000', 'Git source directory mismatch');
    expectedTree = entry.sha;
  }
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
function receipt(e4, finding, proof, plan) {
  return seal({ schemaVersion: 'M6_PR_E_E3_GITLEAKS_PUBLIC_COMMIT_REVIEW_EVIDENCE_V1',
    repository: e4.repository, commitSha: e4.commitSha, sourceE4CanonicalSha256: e4.contentSha256,
    planCanonicalSha256: plan.contentSha256, finding, source: plan.source,
    acceptedPublicBaseCommit: plan.acceptedPublicBaseCommit, introductionTreeSha: plan.introductionTreeSha,
    retainedFindingCount: plan.retainedFindingCount, currentFindingCount: e4.scanners.gitleaks.findingCount,
    retainedFindingsSha256: hash(canonical(plan.retainedFindings)), proof,
    decision: plan.decision, releaseBlocked: true, rawCandidateMaterialRetained: false });
}

export function verifyGitleaksPublicCommitReview(e4, snapshot) {
  const plan = readGitleaksPublicCommitReviewPlan(), finding = requireCurrentFinding(e4, plan);
  requireValue(snapshot?.repository === e4.repository && snapshot.commitSha === e4.commitSha
    && snapshot.currentE4CanonicalSha256 === e4.contentSha256, 'exact current E4 binding required');
  verifyProof(e4, finding, snapshot.proof, plan);
  return receipt(e4, finding, snapshot.proof, plan);
}
export function requireGitleaksPublicCommitReviewReceipt(e4, evidence) {
  const verified = verifyGitleaksPublicCommitReview(e4, { repository: evidence?.repository,
    commitSha: evidence?.commitSha, currentE4CanonicalSha256: evidence?.sourceE4CanonicalSha256, proof: evidence?.proof });
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
export function buildGitleaksPublicCommitReviewSnapshot(e4, { root, git = spawnSync } = {}) {
  const plan = readGitleaksPublicCommitReviewPlan(), finding = requireCurrentFinding(e4, plan);
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
  const evidence = verifyGitleaksPublicCommitReview(e4, snapshot);
  requireValue(evidence.finding.commit === finding.commit, 'builder identity drift');
  return snapshot;
}
