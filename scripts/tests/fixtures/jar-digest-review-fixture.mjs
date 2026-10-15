// Pure, hash-correct modeled Git publication. Only retained historical Git
// objects are read; no objects, scans, captures or release evidence are written.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { treeEntries, objectHash } from '../../security/gitleaks-git-source-proof.mjs';
import { readGitleaksCaptureHashReviewPlan, buildGitleaksCaptureHashReviewSnapshot,
  gitleaksPlanMetadataFields, gitleaksJarExecutionDigestFields }
  from '../../security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
import { JAR_PLUGIN_GRAPH } from '../../security/jar-plugin-graph-transition.mjs';
import { canonical, seal } from './public-commit-review-fixture.mjs';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const plan = readGitleaksCaptureHashReviewPlan();
const hash = value => createHash('sha256').update(value).digest('hex');
export const ACCEPTED_JAR_DIGEST_BASE = 'f1c99eaa998239044b164d32437a9f3c0765b321';
export const ACCEPTED_GITLEAKS_70_SHA256 = 'afa27a6b74ed970e0618df2a537975dcebc355e65067e1cea262519a7eb60c7c';
export const JAR_DIGEST_DOCUMENT_PATHS = [
  'docs/operations/jar-plugin-evidence/model-runtime-inputs.json',
  'docs/operations/jar-plugin-evidence/compatibility-local-seal.json',
];
// JAR_FIXTURE_V4_ADDITION_BEGIN
export const JAR_DIGEST_FIXTURE_PATH = 'scripts/tests/fixtures/jar-digest-review-fixture.mjs';
export const ORIGINAL_JAR_DIGEST_FIXTURE_SHA256 = '5ee1a65d8587d82ca85b4e47bf91f8c818430693ea1c5a996bac411be5d7a83d';
// JAR_FIXTURE_V4_ADDITION_END
const metadataPath = 'docs/m6/m6-pr-e-e3-gitleaks-capture-hash-review.json';
const historicalObjects = new Map();
let retainedFindings;

function readHistoricalObject(type, sha) {
  const key = `${type}:${sha}`;
  if (!historicalObjects.has(key)) {
    const result = spawnSync('git', ['cat-file', type, sha], { cwd: root, maxBuffer: 32 * 1024 * 1024 });
    assert.equal(result.status, 0, `retained ${type} object unavailable: ${result.stderr}`);
    assert.equal(objectHash(type, result.stdout), sha);
    historicalObjects.set(key, Buffer.from(result.stdout));
  }
  return Buffer.from(historicalObjects.get(key));
}
// JAR_FIXTURE_V4_ADDITION_BEGIN
export function originalJarDigestFixtureBytes() {
  // Recover only the approved prior fixture, without unpublished Git objects.
  // Every bounded change below is reversed, then the entire result is pinned.
  let source = readFileSync(`${root}${JAR_DIGEST_FIXTURE_PATH}`, 'utf8');
  const replacements = [
    ["HEAD", "  const tree = overlay(introductionTree, [['zz-jar-digest-review-fixture.txt', Buffer.from('modeled later review commit\\n')]]);\n" +
      "  const branchHead = commit(tree, [intro], 'modeled JAR review refinement', 1792060002);\n" +
      "  const head = mergeHead ? commit(tree, [ACCEPTED_JAR_DIGEST_BASE, branchHead], 'modeled JAR merge', 1792060003) : branchHead;\n"],
    ["FINDINGS", "      findings: [...acceptedJarDigestLegacyFindings(), ...structuredClone(findings)].sort((a, b) => a.findingId.localeCompare(b.findingId)) };\n"],
    ["RETURN", "    findings, fields, commands, git, prepareEvidence,\n"],
  ];
  for (const [name, original] of replacements) {
    const begin = `// JAR_FIXTURE_V4_${name}_BEGIN\n`, end = `// JAR_FIXTURE_V4_${name}_END\n`;
    const start = source.indexOf(begin), finish = source.indexOf(end, start);
    assert.ok(start >= 0 && finish > start && source.indexOf(begin, start + begin.length) === -1);
    source = source.slice(0, start) + original + source.slice(finish + end.length);
  }
  let additions = 0;
  source = source.replace(/^\/\/ JAR_FIXTURE_V4_ADDITION_BEGIN\n[\s\S]*?^\/\/ JAR_FIXTURE_V4_ADDITION_END\n/gm,
    () => { additions++; return ''; });
  assert.equal(additions, 5);
  const bytes = Buffer.from(source);
  assert.equal(hash(bytes), ORIGINAL_JAR_DIGEST_FIXTURE_SHA256);
  assert.equal(bytes.length, 8436);
  return bytes;
}
// JAR_FIXTURE_V4_ADDITION_END
function actualIntroduction(path) {
  // Derive the real old introductions from the accepted history. The complete
  // retained inventory hash below independently prevents selecting other ones.
  const result = spawnSync('git', ['log', ACCEPTED_JAR_DIGEST_BASE, '--diff-filter=A', '--format=%H', '--', path],
    { cwd: root, encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 });
  assert.equal(result.status, 0, result.stderr);
  const commits = result.stdout.trim().split('\n');
  assert.equal(commits.length, 1, 'exact retained document introduction required');
  assert.match(commits[0], /^[0-9a-f]{40}$/);
  return commits[0];
}
export function normalizedDigestFinding(commit, path, line, policy = plan.finding) {
  const { ruleId, description, sourceClass } = policy;
  const fingerprint = `${commit}:${path}:${ruleId}:${line}`;
  return { sourceClass, ruleId, path, startLine: line, endLine: line, description, commit, fingerprint,
    findingId: hash(['GITLEAKS', fingerprint, ruleId, path, String(line)].join('\0')) };
}
export function acceptedJarDigestLegacyFindings() {
  if (!retainedFindings) {
    const captureIntroduction = actualIntroduction(plan.source.path), metadataIntroduction = actualIntroduction(metadataPath);
    const metadataPolicy = plan.retainedFindings.find(row => row.ruleId === 'sourcegraph-access-token');
    retainedFindings = [
      ...structuredClone(plan.retainedFindings),
      ...plan.observations.map(({ line }) => normalizedDigestFinding(captureIntroduction, plan.source.path, line)),
      ...gitleaksPlanMetadataFields().map(({ line }) => normalizedDigestFinding(metadataIntroduction, metadataPath, line, metadataPolicy)),
    ].sort((a, b) => a.findingId.localeCompare(b.findingId));
    assert.equal(retainedFindings.length, 70);
    assert.equal(hash(canonical(retainedFindings)), ACCEPTED_GITLEAKS_70_SHA256);
  }
  return structuredClone(retainedFindings);
}
export function resealJarDigestEvidence(e4) {
  e4.scanners.gitleaks.findingCount = e4.scanners.gitleaks.findings.length;
  e4.totalFindingCount = Object.values(e4.scanners).reduce((sum, scanner) => sum + scanner.findingCount, 0);
  return seal(e4);
}

export function modeledJarDigestFixture(label = 'modeled JAR digest publication', {
  mergeHead = false, mergeIntroduction = false, transformDocument = null,
// JAR_FIXTURE_V4_ADDITION_BEGIN
  separateFixtureIntroduction = false, fixtureIntroductionVersion = 'original',
  transformFixtureCurrent = null, transformFixtureIntroduction = null,
// JAR_FIXTURE_V4_ADDITION_END
} = {}) {
  const objects = new Map(), commands = [];
  const git = (command, args) => {
    commands.push([command, [...args]]);
    assert.equal(command, 'git'); assert.equal(args.length, 3); assert.equal(args[0], 'cat-file');
    assert.ok(['commit', 'tree', 'blob'].includes(args[1])); assert.match(args[2], /^[0-9a-f]{40}$/);
    const bytes = objects.get(`${args[1]}:${args[2]}`) || readHistoricalObject(args[1], args[2]);
    return { status: 0, stdout: Buffer.from(bytes), stderr: Buffer.alloc(0) };
  };
  const add = (type, value) => {
    const bytes = Buffer.from(value), sha = objectHash(type, bytes);
    objects.set(`${type}:${sha}`, bytes); return sha;
  };
  const overlay = (original, files) => {
    const entries = original ? treeEntries(git('git', ['cat-file', 'tree', original]).stdout) : [];
    const groups = new Map();
    for (const [path, bytes] of files) {
      const [first, ...rest] = path.split('/');
      if (!groups.has(first)) groups.set(first, []);
      groups.get(first).push([rest.join('/'), bytes]);
    }
    for (const [name, children] of groups) {
      const index = entries.findIndex(entry => entry.name === name), previous = entries[index];
      const entry = children[0][0] === '' ? { name, mode: '100644', sha: add('blob', children[0][1]) }
        : { name, mode: '40000', sha: overlay(previous?.sha, children) };
      if (index < 0) entries.push(entry); else entries[index] = entry;
    }
    entries.sort((a, b) => Buffer.compare(Buffer.from(a.name + (a.mode === '40000' ? '/' : '')),
      Buffer.from(b.name + (b.mode === '40000' ? '/' : ''))));
    return add('tree', Buffer.concat(entries.map(entry => Buffer.concat([
      Buffer.from(`${entry.mode} ${entry.name}\0`), Buffer.from(entry.sha, 'hex'),
    ]))));
  };
  const commit = (tree, parents, message, time) => add('commit', `tree ${tree}\n${parents.map(parent => `parent ${parent}\n`).join('')}author Review Fixture <review@example.invalid> ${time} +0000\ncommitter Review Fixture <review@example.invalid> ${time} +0000\n\n${message}\n`);
  const baseTree = readHistoricalObject('commit', ACCEPTED_JAR_DIGEST_BASE).toString('utf8').match(/^tree ([0-9a-f]{40})\n/)[1];
  const files = JAR_DIGEST_DOCUMENT_PATHS.map(path => {
    const bytes = readFileSync(`${root}${path}`);
    return [path, transformDocument ? Buffer.from(transformDocument(path, Buffer.from(bytes))) : bytes];
  });
// JAR_FIXTURE_V4_ADDITION_BEGIN
  const currentBytes = readFileSync(`${root}${JAR_DIGEST_FIXTURE_PATH}`);
  assert.ok(['original', 'current'].includes(fixtureIntroductionVersion));
  const introducedBytes = fixtureIntroductionVersion === 'original' ? originalJarDigestFixtureBytes() : currentBytes;
  const fixtureCurrentBytes = transformFixtureCurrent ? Buffer.from(transformFixtureCurrent(Buffer.from(currentBytes))) : currentBytes;
  const fixtureIntroductionBytes = transformFixtureIntroduction
    ? Buffer.from(transformFixtureIntroduction(Buffer.from(introducedBytes))) : introducedBytes;
  if (!separateFixtureIntroduction) files.push([JAR_DIGEST_FIXTURE_PATH, fixtureIntroductionBytes]);
// JAR_FIXTURE_V4_ADDITION_END
  const introductionTree = overlay(baseTree, files), introductionParents = [ACCEPTED_JAR_DIGEST_BASE];
  if (mergeIntroduction) introductionParents.push(commit(baseTree, [ACCEPTED_JAR_DIGEST_BASE], 'modeled branch before JAR documents', 1792060000));
  const intro = commit(introductionTree, introductionParents, label, 1792060001);
// JAR_FIXTURE_V4_HEAD_BEGIN
  const fixtureIntroductionTree = separateFixtureIntroduction
    ? overlay(introductionTree, [[JAR_DIGEST_FIXTURE_PATH, fixtureIntroductionBytes]]) : introductionTree;
  const fixtureIntroductionParents = separateFixtureIntroduction ? [intro] : introductionParents;
  const fixtureIntro = separateFixtureIntroduction
    ? commit(fixtureIntroductionTree, fixtureIntroductionParents, `${label}: later fixture publication`, 1792060002) : intro;
  const tree = overlay(fixtureIntroductionTree, [[JAR_DIGEST_FIXTURE_PATH, fixtureCurrentBytes],
    ['zz-jar-digest-review-fixture.txt', Buffer.from('modeled later review commit\n')]]);
  const branchHead = commit(tree, [fixtureIntro], 'modeled JAR review refinement', 1792060003);
  const head = mergeHead ? commit(tree, [ACCEPTED_JAR_DIGEST_BASE, branchHead], 'modeled JAR merge', 1792060004) : branchHead;
// JAR_FIXTURE_V4_HEAD_END
  const fields = gitleaksJarExecutionDigestFields();
  const findings = fields.map(({ path, line }) => normalizedDigestFinding(intro, path, line))
    .sort((a, b) => a.findingId.localeCompare(b.findingId));
// JAR_FIXTURE_V4_ADDITION_BEGIN
  const fixtureFinding = normalizedDigestFinding(fixtureIntro, JAR_DIGEST_FIXTURE_PATH, 18,
    plan.retainedFindings.find(row => row.ruleId === 'sourcegraph-access-token'));
// JAR_FIXTURE_V4_ADDITION_END
  const prepareEvidence = evidence => {
    const e4 = structuredClone(evidence); e4.commitSha = head; e4.e2GraphDigest = JAR_PLUGIN_GRAPH;
    e4.checkout = { checkedOutSha: head, expectedHeadSha: head, checkedOutTreeSha: tree,
      expectedHeadTreeSha: tree, exactTreeMatches: true, trackedWorktreeClean: true };
    e4.scanners.gitleaks = { ...e4.scanners.gitleaks, ...plan.scannerIdentity,
// JAR_FIXTURE_V4_FINDINGS_BEGIN
      findings: [...acceptedJarDigestLegacyFindings(), ...structuredClone(findings), structuredClone(fixtureFinding)]
        .sort((a, b) => a.findingId.localeCompare(b.findingId)) };
// JAR_FIXTURE_V4_FINDINGS_END
    return resealJarDigestEvidence(e4);
  };
  return { head, intro, tree, branchHead, baseTree, introductionTree, introductionParents,
// JAR_FIXTURE_V4_RETURN_BEGIN
    fixtureIntro, fixtureIntroductionTree, fixtureIntroductionParents, fixtureFinding,
    fixtureCurrentBytes, fixtureIntroductionBytes, findings, fields, commands, git, prepareEvidence,
// JAR_FIXTURE_V4_RETURN_END
    snapshot: e4 => buildGitleaksCaptureHashReviewSnapshot(e4, { root, git }) };
}
