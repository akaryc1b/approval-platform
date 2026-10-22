// Modeled Resources/Flatten publication over the actual accepted history. No Git writes or scans.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { objectHash, treeEntries, commitObject } from '../../security/gitleaks-git-source-proof.mjs';
import { readGitleaksCaptureHashReviewPlan,
  buildGitleaksCaptureHashReviewSnapshot } from '../../security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
import { RESOURCES_FLATTEN_GRAPH, resourcesFlattenExecutionDocuments, ACCEPTED_RESOURCES_FLATTEN_GITLEAKS_374_SHA256 }
  from '../../security/gitleaks-resources-flatten-execution-digests.mjs';
import { normalizedDigestFinding, resealJarDigestEvidence } from './jar-digest-review-fixture.mjs';
import { canonical } from './public-commit-review-fixture.mjs';
import { acceptedLangDigestLegacyFindings } from './lang-digest-review-fixture.mjs';
import { langExecutionDocuments } from '../../security/gitleaks-lang-execution-digests.mjs';

export { normalizedDigestFinding };
export const resealResourcesFlattenDigestEvidence = resealJarDigestEvidence;
const root = fileURLToPath(new URL('../../../', import.meta.url));
const verifierPath = 'scripts/security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
const plan = readGitleaksCaptureHashReviewPlan();
const hash = value => createHash('sha256').update(value).digest('hex');
const historical = new Map();
let acceptedFindings;
function readObject(type, sha) {
  const key = `${type}:${sha}`;
  if (!historical.has(key)) {
    const result = spawnSync('git', ['cat-file', type, sha], { cwd: root, timeout: 5000, maxBuffer: 32 * 1024 * 1024 });
    assert.equal(result.status, 0, `retained ${type} object unavailable`);
    assert.equal(objectHash(type, result.stdout), sha); historical.set(key, Buffer.from(result.stdout));
  }
  return Buffer.from(historical.get(key));
}
export function acceptedResourcesFlattenDigestLegacyFindings() {
  if (!acceptedFindings) {
    const base = resourcesFlattenExecutionDocuments().base.sourceHead;
    const context = langExecutionDocuments();
    const result = spawnSync('git', ['log', base, '--format=%H', '--diff-filter=A', '--', context.documents[0].source.path],
      { cwd: root, encoding: 'utf8', timeout: 5000, maxBuffer: 64 * 1024 });
    assert.equal(result.status, 0); const commits = result.stdout.trim().split('\n');
    assert.equal(commits.length, 1); assert.match(commits[0], /^[a-f0-9]{40}$/);
    acceptedFindings = [...acceptedLangDigestLegacyFindings(), ...context.documents.flatMap(row => row.fields)
      .map(field => normalizedDigestFinding(commits[0], field.path, field.line))]
      .sort((a, b) => a.findingId.localeCompare(b.findingId));
    assert.equal(acceptedFindings.length, 374);
    assert.equal(hash(canonical(acceptedFindings)), ACCEPTED_RESOURCES_FLATTEN_GITLEAKS_374_SHA256);
  }
  return structuredClone(acceptedFindings);
}

export function modeledResourcesFlattenDigestFixture(label = 'modeled Resources/Flatten capture publication', {
  mergeHead = false, mergeIntroduction = false, parentContainsDocument = false,
  transformIntroduction = null, transformCurrent = null,
} = {}) {
  const context = resourcesFlattenExecutionDocuments(), objects = new Map(), commands = [];
  const git = (command, args) => {
    commands.push([command, [...args]]);
    assert.equal(command, 'git'); assert.equal(args.length, 3); assert.equal(args[0], 'cat-file');
    assert.ok(['commit', 'tree', 'blob'].includes(args[1])); assert.match(args[2], /^[a-f0-9]{40}$/);
    return { status: 0, stdout: Buffer.from(objects.get(`${args[1]}:${args[2]}`) || readObject(args[1], args[2])), stderr: Buffer.alloc(0) };
  };
  const add = (type, value) => {
    const bytes = Buffer.from(value), sha = objectHash(type, bytes); objects.set(`${type}:${sha}`, bytes); return sha;
  };
  const overlay = (tree, files) => {
    const entries = tree ? treeEntries(git('git', ['cat-file', 'tree', tree]).stdout) : [], groups = new Map();
    for (const [path, bytes] of files) {
      const [first, ...rest] = path.split('/');
      if (!groups.has(first)) groups.set(first, []); groups.get(first).push([rest.join('/'), bytes]);
    }
    for (const [name, children] of groups) {
      const previous = entries.find(entry => entry.name === name);
      const next = children[0][0] === '' ? { name, mode: '100644', sha: add('blob', children[0][1]) }
        : { name, mode: '40000', sha: overlay(previous?.sha, children) };
      if (previous) Object.assign(previous, next); else entries.push(next);
    }
    entries.sort((a, b) => Buffer.compare(Buffer.from(a.name + (a.mode === '40000' ? '/' : '')),
      Buffer.from(b.name + (b.mode === '40000' ? '/' : ''))));
    return add('tree', Buffer.concat(entries.map(entry => Buffer.concat([
      Buffer.from(`${entry.mode} ${entry.name}\0`), Buffer.from(entry.sha, 'hex'),
    ]))));
  };
  const commit = (tree, parents, message, time) => add('commit', `tree ${tree}\n${parents.map(parent => `parent ${parent}\n`).join('')}author Review Fixture <review@example.invalid> ${time} +0000\ncommitter Review Fixture <review@example.invalid> ${time} +0000\n\n${label}: ${message}\n`);
  const base = context.base.sourceHead, baseTree = context.base.sourceTree;
  assert.equal(commitObject({ sha: base, contentBase64: readObject('commit', base).toString('base64') }).tree, baseTree);
  const sources = [...context.documents.map(row => row.source), ...context.provenance];
  const files = sources.map(({ path }) => [path, readFileSync(`${root}${path}`)]);
  const introductionTree = overlay(baseTree, files.map(([path, bytes]) =>
    [path, transformIntroduction ? Buffer.from(transformIntroduction(path, Buffer.from(bytes))) : bytes]));
  const introductionParents = [base];
  if (mergeIntroduction || parentContainsDocument) introductionParents.push(commit(
    parentContainsDocument ? overlay(baseTree, files) : baseTree, [base], 'second introduction parent', 1792110000));
  const intro = commit(introductionTree, introductionParents, 'capture introduction', 1792110001);
  const tree = overlay(introductionTree, [...files.map(([path, bytes]) =>
    [path, transformCurrent ? Buffer.from(transformCurrent(path, Buffer.from(bytes))) : bytes]),
  [verifierPath, readFileSync(`${root}${verifierPath}`)]]);
  const branchHead = commit(tree, [intro], 'current review source', 1792110002);
  const head = mergeHead ? commit(tree, [base, branchHead], 'modeled merge', 1792110003) : branchHead;
  const fields = context.documents.flatMap(row => row.fields);
  const findings = fields.map(field => normalizedDigestFinding(intro, field.path, field.line))
    .sort((a, b) => a.findingId.localeCompare(b.findingId));
  const prepareEvidence = evidence => {
    const e4 = structuredClone(evidence); e4.commitSha = head; e4.e2GraphDigest = RESOURCES_FLATTEN_GRAPH;
    e4.checkout = { checkedOutSha: head, expectedHeadSha: head, checkedOutTreeSha: tree,
      expectedHeadTreeSha: tree, exactTreeMatches: true, trackedWorktreeClean: true };
    e4.scanners.gitleaks = { ...e4.scanners.gitleaks, ...plan.scannerIdentity,
      findings: [...acceptedResourcesFlattenDigestLegacyFindings(), ...structuredClone(findings)].sort((a, b) => a.findingId.localeCompare(b.findingId)) };
    return resealResourcesFlattenDigestEvidence(e4);
  };
  return { head, intro, tree, baseTree, introductionTree, introductionParents, branchHead,
    fields, findings, commands, git, prepareEvidence,
    snapshot: e4 => buildGitleaksCaptureHashReviewSnapshot(e4, { root, git }) };
}
