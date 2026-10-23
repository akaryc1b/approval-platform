// Modeled future Head over real main89b; no scanner run, Git write or disposition claim.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { objectHash, treeEntries, commitObject } from '../../security/gitleaks-git-source-proof.mjs';
import { HISTORICAL_SOURCE_REVIEW_BASE, buildGitleaksHistoricalSourceReviewSnapshot } from '../../security/gitleaks-historical-noncredential-sources.mjs';
import { RESOURCES_FLATTEN_GRAPH, resourcesFlattenExecutionDocuments } from '../../security/gitleaks-resources-flatten-execution-digests.mjs';
import { acceptedResourcesFlattenDigestLegacyFindings, normalizedDigestFinding, resealResourcesFlattenDigestEvidence } from './resources-flatten-digest-review-fixture.mjs';
import { readGitleaksCaptureHashReviewPlan } from '../../security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
import { canonical } from './public-commit-review-fixture.mjs';
import { osvInputFromE2, buildOsvCoverage } from '../../security/osv-scan-coverage.mjs';

const root = fileURLToPath(new URL('../../../', import.meta.url)), retained = new Map();
const hash = value => createHash('sha256').update(value).digest('hex');
// Exact normalized advisory metadata from accepted cb3aabe E4, replayed only as
// modeled scanner transport. This is neither a new vulnerability query nor a scan.
const ARCHIVED_OSV = [
  ['net.i2p.crypto:eddsa', '0.3.0', 'GHSA-p53j-g8pw-4w5f', ['CVE-2020-36843'], [], [{ score: 'CVSS:3.1/AV:L/AC:L/PR:N/UI:N/S:C/C:N/I:L/A:N', type: 'CVSS_V3' }]],
  ['org.eclipse.jetty:jetty-http', '9.4.58.v20250814', 'GHSA-qh8g-58pp-2wxh', ['CVE-2024-6763'], ['12.0.12'], [{ score: 'CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:L/A:N', type: 'CVSS_V3' }, { score: 'CVSS:4.0/AV:N/AC:L/AT:P/PR:N/UI:N/VC:N/VI:L/VA:N/SC:N/SI:N/SA:N', type: 'CVSS_V4' }]],
  ['org.eclipse.jetty:jetty-security', '9.4.58.v20250814', 'GHSA-2fvj-hgj9-j2gr', ['CVE-2026-10050'], ['10.0.31', '11.0.31', '12.0.36', '12.1.10', '9.4.63'], [{ score: 'CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:N/VA:N/SC:N/SI:N/SA:N', type: 'CVSS_V4' }]],
  ['org.iq80.snappy:snappy', '0.4', 'GHSA-8wh2-6qhj-h7j9', ['CVE-2024-36124'], ['0.5'], [{ score: 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:L', type: 'CVSS_V3' }]],
  ['commons-beanutils:commons-beanutils', '1.9.4', 'GHSA-wxr5-93ph-8wr9', ['CVE-2025-48734'], ['1.11.0'], [{ score: 'CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:H', type: 'CVSS_V3' }]],
  ['org.eclipse.jetty:jetty-http', '9.4.58.v20250814', 'GHSA-355h-qmc2-wpwf', ['CVE-2026-2332'], ['10.0.28', '11.0.29', '12.0.33', '12.1.7', '9.4.60'], [{ score: 'CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:N', type: 'CVSS_V3' }]],
  ['org.eclipse.jetty:jetty-server', '9.4.58.v20250814', 'GHSA-7p3p-8qv8-m2vh', ['CVE-2026-6790'], ['12.0.35', '12.1.9'], [{ score: 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:L/A:N', type: 'CVSS_V3' }]],
  ['org.codehaus.plexus:plexus-utils', '4.0.1', 'GHSA-6fmv-xxpf-w3cw', ['CVE-2025-67030'], ['3.6.1', '4.0.3'], []],
];
function modeledArchivedOsv(e2, source) {
  const input = osvInputFromE2(e2), inputPath = '/synthetic-unit-only-historical-source-input.json';
  const findings = ARCHIVED_OSV.map(([name, version, advisory, aliases, fixedVersions, upstreamSeverity]) => {
    const selected = [...input.lookup.values()].find(row => row.package.name === name && row.package.version === version); assert.ok(selected);
    return { sourceClass: 'E4_OSV_SCANNER', findingId: hash(['OSV', advisory, 'Maven', name, version].join('\0')),
      upstreamFindingId: advisory, aliases, package: selected.package, componentRefs: [...selected.componentRefs].sort(),
      scopes: [...selected.scopes].sort(), upstreamSeverity, fixedVersions };
  }).sort((a, b) => a.findingId.localeCompare(b.findingId));
  assert.equal(hash(canonical(findings)), 'd454c84b4bd2e38c6a1a84452605984f6da683e18281f302bb9a5dbb7978d124');
  const raw = { results: [{ source: { path: inputPath, type: 'lockfile' }, packages: input.scannerInput.results[0].packages.map(({ package: identity }) =>
    ({ package: identity, vulnerabilities: findings.filter(row => row.package.name === identity.name && row.package.version === identity.version).map(row => ({ id: row.upstreamFindingId })) })) }] };
  const coverage = buildOsvCoverage(raw, e2, { head: e2.commitSha, graphDigest: RESOURCES_FLATTEN_GRAPH,
    inputBytes: JSON.stringify(input.scannerInput), inputPath, findings, exitStatus: 1,
    stderr: `Starting filesystem walk for root: /\nScanned ${inputPath} file and found ${input.packageCount} packages\nEnd status: 0 dirs visited, 1 inodes visited, 1 Extract calls, 1ms elapsed, 1ms wall time\n` });
  return { ...structuredClone(source), findings, findingCount: findings.length, coverage, inputPackageCount: input.packageCount };
}
const readObject = (type, sha) => {
  const id = `${type}:${sha}`;
  if (!retained.has(id)) {
    const result = spawnSync('git', ['cat-file', type, sha], { cwd: root, timeout: 5000, maxBuffer: 32 * 1024 * 1024 });
    assert.equal(result.status, 0); assert.equal(objectHash(type, result.stdout), sha); retained.set(id, Buffer.from(result.stdout));
  }
  return Buffer.from(retained.get(id));
};
export function modeledHistoricalSourceReviewFixture({ mergeHead = false, baseCommit = HISTORICAL_SOURCE_REVIEW_BASE, parent = baseCommit, changedFiles = [] } = {}) {
  const objects = new Map(), commands = [], plan = readGitleaksCaptureHashReviewPlan();
  const git = (command, args) => {
    commands.push([command, [...args]]); assert.equal(command, 'git'); assert.equal(args[0], 'cat-file');
    assert.equal(args.length, 3); assert.ok(['commit', 'tree', 'blob'].includes(args[1]));
    return { status: 0, stdout: Buffer.from(objects.get(`${args[1]}:${args[2]}`) || readObject(args[1], args[2])), stderr: Buffer.alloc(0) };
  };
  const add = (type, bytes) => { bytes = Buffer.from(bytes); const sha = objectHash(type, bytes); objects.set(`${type}:${sha}`, bytes); return sha; };
  const overlay = (tree, files) => {
    const entries = treeEntries(git('git', ['cat-file', 'tree', tree]).stdout), groups = new Map();
    for (const [path, bytes] of files) {
      const [first, ...rest] = path.split('/'); if (!groups.has(first)) groups.set(first, []); groups.get(first).push([rest.join('/'), bytes]);
    }
    for (const [name, children] of groups) {
      const row = entries.find(entry => entry.name === name); assert.ok(row);
      row.sha = children[0][0] === '' ? add('blob', children[0][1]) : overlay(row.sha, children);
    }
    return add('tree', Buffer.concat(entries.map(row => Buffer.concat([Buffer.from(`${row.mode} ${row.name}\0`), Buffer.from(row.sha, 'hex')]))));
  };
  const baseTree = commitObject({ sha: baseCommit,
    contentBase64: readObject('commit', baseCommit).toString('base64') }).tree;
  const tree = changedFiles.length ? overlay(baseTree, changedFiles.map(path => [path, Buffer.concat([readFileSync(`${root}${path}`), Buffer.from('\n')])])) : baseTree;
  const commit = parents => add('commit', `tree ${tree}\n${parents.map(sha => `parent ${sha}\n`).join('')}author Source Review Fixture <review@example.invalid> 1792660000 +0000\ncommitter Source Review Fixture <review@example.invalid> 1792660000 +0000\n\nModeled historical-source review descendant\n`);
  const branch = commit([parent]), head = mergeHead ? commit([parent, branch]) : branch;
  const documents = resourcesFlattenExecutionDocuments().documents;
  const introduction = spawnSync('git', ['log', HISTORICAL_SOURCE_REVIEW_BASE, '--format=%H', '--diff-filter=A', '--', documents[0].source.path], { cwd: root, encoding: 'utf8' });
  assert.equal(introduction.status, 0); assert.match(introduction.stdout.trim(), /^[a-f0-9]{40}$/);
  const findings = [...acceptedResourcesFlattenDigestLegacyFindings(), ...documents.flatMap(row => row.fields)
    .map(field => normalizedDigestFinding(introduction.stdout.trim(), field.path, field.line))].sort((a, b) => a.findingId.localeCompare(b.findingId));
  assert.equal(hash(canonical(findings)), '1bca2f9e5eefa0c0883ec6a44b3f5a466550c676335e0087cb188e00b077b8de');
  return { head, tree, git, commands, findings,
    prepareEvidence: evidence => {
      const e4 = structuredClone(evidence); e4.commitSha = head; e4.e2GraphDigest = RESOURCES_FLATTEN_GRAPH;
      e4.checkout = { checkedOutSha: head, expectedHeadSha: head, checkedOutTreeSha: tree, expectedHeadTreeSha: tree, exactTreeMatches: true, trackedWorktreeClean: true };
      e4.scanners.gitleaks = { ...e4.scanners.gitleaks, ...plan.scannerIdentity, findings: structuredClone(findings) };
      if (e4.e2CurrentEvidence) e4.scanners.osv = modeledArchivedOsv(e4.e2CurrentEvidence, e4.scanners.osv);
      return resealResourcesFlattenDigestEvidence(e4);
    }, snapshot: e4 => buildGitleaksHistoricalSourceReviewSnapshot(e4, { root, git }) };
}
