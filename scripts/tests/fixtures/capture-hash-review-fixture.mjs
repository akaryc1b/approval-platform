// Hash-correct in-memory Git objects. No Git writes, real scan or release claim.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { treeEntries, objectHash } from '../../security/gitleaks-git-source-proof.mjs';
import { readGitleaksCaptureHashReviewPlan, buildGitleaksCaptureHashReviewSnapshot }
  from '../../security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
import { seal } from './public-commit-review-fixture.mjs';

const root = fileURLToPath(new URL('../../../', import.meta.url)), plan = readGitleaksCaptureHashReviewPlan();
const hash = value => createHash('sha256').update(value).digest('hex');
export function modeledCaptureHashFixture(label = 'source-equivalent capture publication', { mergeHead = false, mergeIntroduction = false, bundledIntroduction = false } = {}) {
  const objects = new Map(), commands = [];
  const git = (command, args) => {
    commands.push([command, args]);
    if (command !== 'git' || args[0] !== 'cat-file' || !['commit', 'tree', 'blob'].includes(args[1])
      || !/^[0-9a-f]{40}$/.test(args[2]) || args.length !== 3) throw new Error('fixture permits only Git object reads');
    const cached = objects.get(`${args[1]}:${args[2]}`);
    if (cached) return { status: 0, stdout: Buffer.from(cached), stderr: Buffer.alloc(0) };
    const result = spawnSync(command, args, { cwd: root });
    if (result.status !== 0) throw new Error('fixture historical object unavailable');
    objects.set(`${args[1]}:${args[2]}`, result.stdout); return result;
  };
  const add = (type, value) => {
    const bytes = Buffer.from(value), sha = objectHash(type, bytes); objects.set(`${type}:${sha}`, bytes); return sha;
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
    entries.sort((a, b) => Buffer.compare(Buffer.from(a.name + (a.mode === '40000' ? '/' : '')), Buffer.from(b.name + (b.mode === '40000' ? '/' : ''))));
    return add('tree', Buffer.concat(entries.map(entry => Buffer.concat([Buffer.from(`${entry.mode} ${entry.name}\0`), Buffer.from(entry.sha, 'hex')]))));
  };
  const commit = (tree, parents, message, time) => add('commit', `tree ${tree}\n${parents.map(parent => `parent ${parent}\n`).join('')}author Review Fixture <review@example.invalid> ${time} +0000\ncommitter Review Fixture <review@example.invalid> ${time} +0000\n\n${message}\n`);
  const files = [plan.source.path, plan.collector.path].map(path => [path, readFileSync(`${root}${path}`)]);
  if (bundledIntroduction) files.push(['zz-capture-review-fixture.txt', Buffer.from('modeled bundled publication\n')]);
  const introductionTree = overlay(plan.acceptedPublicBaseTreeSha, files);
  const introductionParents = [plan.acceptedPublicBaseCommit];
  if (mergeIntroduction) introductionParents.push(commit(plan.acceptedPublicBaseTreeSha, [plan.acceptedPublicBaseCommit], 'modeled independent branch without capture', 1791600000));
  const intro = commit(introductionTree, introductionParents, label, 1791600001);
  const tree = overlay(introductionTree, [['zz-capture-review-refinement.txt', Buffer.from('modeled additive review\n')]]);
  const branchHead = commit(tree, [intro], 'modeled classification refinement', 1791600002);
  const head = mergeHead ? commit(tree, [plan.acceptedPublicBaseCommit, branchHead], 'modeled normal main merge', 1791600003) : branchHead;
  const findings = plan.observations.map(({ line }) => {
    const { ruleId, description, sourceClass } = plan.finding, path = plan.source.path, fingerprint = `${intro}:${path}:${ruleId}:${line}`;
    return { sourceClass, ruleId, path, startLine: line, endLine: line, description, commit: intro, fingerprint,
      findingId: hash(['GITLEAKS', fingerprint, ruleId, path, String(line)].join('\0')) };
  });
  const prepareEvidence = evidence => {
    const e4 = structuredClone(evidence); e4.commitSha = head;
    e4.checkout = { checkedOutSha: head, expectedHeadSha: head, checkedOutTreeSha: tree,
      expectedHeadTreeSha: tree, exactTreeMatches: true, trackedWorktreeClean: true };
    e4.scanners.gitleaks = { ...e4.scanners.gitleaks, ...plan.scannerIdentity, findingCount: 32,
      findings: [...structuredClone(plan.retainedFindings), ...findings].sort((a, b) => a.findingId.localeCompare(b.findingId)) };
    e4.totalFindingCount = Object.values(e4.scanners).reduce((sum, scanner) => sum + scanner.findingCount, 0);
    return seal(e4);
  };
  return { head, intro, introductionTree, introductionParents, tree, branchHead, findings, git, commands, prepareEvidence,
    snapshot: e4 => buildGitleaksCaptureHashReviewSnapshot(e4, { root, git }) };
}
