// Extend the fixed digest fixture with the two observed verifier declarations.
// Only bounded historical Git reads occur; modeled objects remain in memory.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { objectHash, treeEntries } from '../../security/gitleaks-git-source-proof.mjs';
import { buildGitleaksCaptureHashReviewSnapshot, readGitleaksCaptureHashReviewPlan }
  from '../../security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
import { modeledJarDigestFixture as digestFixture, normalizedDigestFinding, resealJarDigestEvidence,
  ACCEPTED_JAR_DIGEST_BASE, JAR_DIGEST_DOCUMENT_PATHS, JAR_DIGEST_FIXTURE_PATH } from './jar-digest-review-fixture.mjs';
export * from './jar-digest-review-fixture.mjs';

const root = fileURLToPath(new URL('../../../', import.meta.url));
export const JAR_VERIFIER_PATH = 'scripts/security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
const introductionSha256 = 'bdd6e49a49db78da02980d24e30741b6802174f398b9800fbcc9a93f73db9e98';
let introductionBytes;
export function publishedVerifierIntroductionBytes() {
  if (!introductionBytes) {
    const history = spawnSync('git', ['log', '-16', '--format=%H', 'HEAD', '--', JAR_VERIFIER_PATH],
      { cwd: root, encoding: 'utf8', timeout: 5000, maxBuffer: 64 * 1024 });
    assert.equal(history.status, 0, history.stderr);
    const revisions = history.stdout.trim().split('\n'); assert.ok(revisions.length <= 16);
    for (const revision of revisions) {
      assert.match(revision, /^[0-9a-f]{40}$/);
      const result = spawnSync('git', ['show', `${revision}:${JAR_VERIFIER_PATH}`],
        { cwd: root, timeout: 5000, maxBuffer: 256 * 1024 });
      assert.equal(result.status, 0, String(result.stderr));
      if (createHash('sha256').update(result.stdout).digest('hex') === introductionSha256) {
        introductionBytes = Buffer.from(result.stdout); break;
      }
    }
    assert.ok(introductionBytes, 'actual public verifier introduction absent from bounded current ancestry');
    assert.equal(introductionBytes.length, 49553);
  }
  return Buffer.from(introductionBytes);
}
export function modeledJarDigestFixture(label, options = {}) {
  const model = digestFixture(label, options), objects = new Map();
  const git = (command, args) => {
    assert.equal(command, 'git'); assert.equal(args.length, 3); assert.equal(args[0], 'cat-file');
    const bytes = objects.get(`${args[1]}:${args[2]}`);
    if (!bytes) return model.git(command, args);
    model.commands.push([command, [...args]]); return { status: 0, stdout: Buffer.from(bytes), stderr: Buffer.alloc(0) };
  };
  const add = (type, value) => {
    const bytes = Buffer.from(value), sha = objectHash(type, bytes); objects.set(`${type}:${sha}`, bytes); return sha;
  };
  const overlay = (tree, path, bytes) => {
    const entries = treeEntries(git('git', ['cat-file', 'tree', tree]).stdout), [name, ...rest] = path.split('/');
    const previous = entries.find(entry => entry.name === name); assert.ok(previous, `existing verifier path ${name}`);
    previous.sha = rest.length ? overlay(previous.sha, rest.join('/'), bytes) : add('blob', bytes);
    return add('tree', Buffer.concat(entries.map(entry => Buffer.concat([
      Buffer.from(`${entry.mode} ${entry.name}\0`), Buffer.from(entry.sha, 'hex'),
    ]))));
  };
  const commit = (tree, parents, suffix, time) => add('commit', `tree ${tree}\n${parents.map(parent => `parent ${parent}\n`).join('')}author Review Fixture <review@example.invalid> ${time} +0000\ncommitter Review Fixture <review@example.invalid> ${time} +0000\n\n${label || 'modeled verifier publication'}: ${suffix}\n`);
  const currentBytes = readFileSync(`${root}${JAR_VERIFIER_PATH}`), oldBytes = publishedVerifierIntroductionBytes();
  const verifierCurrentBytes = options.transformVerifierCurrent ? Buffer.from(options.transformVerifierCurrent(currentBytes)) : currentBytes;
  const verifierIntroductionBytes = options.transformVerifierIntroduction ? Buffer.from(options.transformVerifierIntroduction(oldBytes)) : oldBytes;
  const combined = options.combinedVerifierIntroduction === true;
  assert.ok(!combined || !options.separateFixtureIntroduction, 'combined model requires a common fixture introduction');
  let parents = combined ? [...model.introductionParents] : [model.head];
  if (options.verifierParentContainsFields) {
    const tree = overlay(model.tree, JAR_VERIFIER_PATH, oldBytes);
    parents.push(commit(tree, [model.head], 'parent already contains verifier fields', 1792060100));
  } else if (options.mergeVerifierIntroduction) {
    parents.push(commit(model.baseTree, [ACCEPTED_JAR_DIGEST_BASE], 'second verifier parent without fields', 1792060100));
  }
  const verifierIntroductionTree = overlay(combined ? model.introductionTree : model.tree, JAR_VERIFIER_PATH, verifierIntroductionBytes);
  const verifierIntro = commit(verifierIntroductionTree, parents, 'verifier introduction', 1792060101);
  const tree = overlay(model.tree, JAR_VERIFIER_PATH, verifierCurrentBytes);
  const head = commit(tree, options.mergeHead ? [verifierIntro, ACCEPTED_JAR_DIGEST_BASE] : [verifierIntro], 'current verifier', 1792060102);
  const policy = readGitleaksCaptureHashReviewPlan().retainedFindings.find(row => row.ruleId === 'sourcegraph-access-token');
  const verifierFindings = [22, 23].map(line => normalizedDigestFinding(verifierIntro, JAR_VERIFIER_PATH, line, policy));
  const prepareEvidence = evidence => {
    const e4 = model.prepareEvidence(evidence); e4.commitSha = head;
    e4.checkout = { checkedOutSha: head, expectedHeadSha: head, checkedOutTreeSha: tree,
      expectedHeadTreeSha: tree, exactTreeMatches: true, trackedWorktreeClean: true };
    if (combined) e4.scanners.gitleaks.findings = e4.scanners.gitleaks.findings.map(row =>
      JAR_DIGEST_DOCUMENT_PATHS.includes(row.path) || row.path === JAR_DIGEST_FIXTURE_PATH
        ? normalizedDigestFinding(verifierIntro, row.path, row.startLine, row) : row);
    e4.scanners.gitleaks.findings.push(...structuredClone(verifierFindings));
    e4.scanners.gitleaks.findings.sort((a,b) => a.findingId.localeCompare(b.findingId));
    return resealJarDigestEvidence(e4);
  };
  return { ...model, head, tree, git, prepareEvidence, verifierIntro, verifierIntroductionTree, verifierIntroductionParents: parents,
    verifierCurrentBytes, verifierIntroductionBytes, verifierFindings,
    snapshot: e4 => buildGitleaksCaptureHashReviewSnapshot(e4, { root, git }) };
}
