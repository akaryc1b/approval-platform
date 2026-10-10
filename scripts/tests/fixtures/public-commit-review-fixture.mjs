// Hash-correct modeled Git objects. These are never written to Git and do not
// claim a scan, a published commit, or exact-head release evidence.
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readGitleaksPublicCommitReviewPlan, buildGitleaksPublicCommitReviewSnapshot }
  from '../../security/m6-pr-e-e3-review-gitleaks-public-commit.mjs';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const plan = readGitleaksPublicCommitReviewPlan();
const hash = value => createHash('sha256').update(value).digest('hex');
const objectHash = (type, bytes) => createHash('sha1').update(`${type} ${bytes.length}\0`).update(bytes).digest('hex');
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
export const canonical = value => JSON.stringify(stable(value));
export const seal = value => { const { contentSha256, ...payload } = value; return stable({ ...payload, contentSha256: hash(canonical(payload)) }); };

export function modeledPublicCommitFixture(label = 'public source-equivalent introduction', { mergeHead = false } = {}) {
  const objects = new Map(), commands = [];
  const git = (command, args) => {
    commands.push([command, args]);
    if (command !== 'git' || args[0] !== 'cat-file' || !['commit', 'tree', 'blob'].includes(args[1])
      || !/^[0-9a-f]{40}$/.test(args[2]) || args.length !== 3) throw new Error('fixture permits only Git object reads');
    const cached = objects.get(`${args[1]}:${args[2]}`);
    if (cached) return { status: 0, stdout: Buffer.from(cached), stderr: Buffer.alloc(0) };
    const result = spawnSync(command, args, { cwd: root });
    if (result.status !== 0) throw new Error('fixture historical object unavailable');
    objects.set(`${args[1]}:${args[2]}`, result.stdout);
    return result;
  };
  const add = (type, content) => {
    const bytes = Buffer.from(content), sha = objectHash(type, bytes);
    objects.set(`${type}:${sha}`, bytes); return sha;
  };
  const intro = add('commit', `tree ${plan.introductionTreeSha}\nparent ${plan.acceptedPublicBaseCommit}\nauthor Review Fixture <review@example.invalid> 1791564933 +0000\ncommitter Review Fixture <review@example.invalid> 1791564933 +0000\n\n${label}\n`);
  // A different current tree containing an additional harmless file models the
  // second additive commit while retaining the exact diagnostic subtree.
  const originalTree = git('git', ['cat-file', 'tree', plan.introductionTreeSha]).stdout;
  const marker = add('blob', 'synthetic review refinement\n');
  const tree = add('tree', Buffer.concat([originalTree,
    Buffer.from('100644 zz-review-fixture.txt\0'), Buffer.from(marker, 'hex')]));
  let head = add('commit', `tree ${tree}\nparent ${intro}\nauthor Review Fixture <review@example.invalid> 1791564934 +0000\ncommitter Review Fixture <review@example.invalid> 1791564934 +0000\n\nsynthetic additive review commit\n`);
  const branchHead = head;
  if (mergeHead) head = add('commit', `tree ${tree}\nparent ${plan.acceptedPublicBaseCommit}\nparent ${branchHead}\nauthor Review Fixture <review@example.invalid> 1791564935 +0000\ncommitter Review Fixture <review@example.invalid> 1791564935 +0000\n\nsynthetic normal main merge\n`);
  const ruleId = plan.finding.ruleParts.join(''), path = plan.source.path, line = plan.source.line;
  const fingerprint = `${intro}:${path}:${ruleId}:${line}`;
  const finding = { sourceClass: plan.finding.sourceClass, ruleId, path, startLine: line, endLine: line,
    description: plan.finding.descriptionParts.join(''), commit: intro, fingerprint,
    findingId: hash(['GITLEAKS', fingerprint, ruleId, path, String(line)].join('\0')) };
  const prepareEvidence = evidence => {
    const e4 = structuredClone(evidence);
    e4.commitSha = head;
    e4.checkout = { checkedOutSha: head, expectedHeadSha: head, checkedOutTreeSha: tree,
      expectedHeadTreeSha: tree, exactTreeMatches: true, trackedWorktreeClean: true };
    e4.scanners.gitleaks = { ...e4.scanners.gitleaks, ...plan.scannerIdentity, findingCount: 29,
      findings: [...structuredClone(plan.retainedFindings), finding].sort((a, b) => a.findingId.localeCompare(b.findingId)) };
    e4.totalFindingCount = Object.values(e4.scanners).reduce((sum, scanner) => sum + scanner.findingCount, 0);
    return seal(e4);
  };
  return { head, branchHead, intro, tree, finding, objects, commands, git, prepareEvidence,
    snapshot: e4 => buildGitleaksPublicCommitReviewSnapshot(e4, { root, git }) };
}
