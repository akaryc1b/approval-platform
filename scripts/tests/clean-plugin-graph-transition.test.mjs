import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { BASE_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, canonicalGraph, graphHash,
  readCleanPluginManifest, readCleanPluginCandidate, readCleanPluginReport, readCleanPluginBaseline,
  readCleanPluginSourceWitness, readCleanPluginPreservedReleaseLineage, verifyCleanPluginDelta,
  verifyCleanPluginOwnerDelta, readReleasePluginReport, verifyReleasePluginDelta,
  verifyObservabilityGraph, requirePreservedGraph } from '../security/observability-dependency-graph.mjs';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { osvInputFromE2, verifyOsvCoverage } from '../security/osv-scan-coverage.mjs';
import { syntheticCleanPluginOsv } from './fixtures/clean-plugin-fixture.mjs';

const sha = value => createHash('sha256').update(value).digest('hex');
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: graphHash(payload) }; };
const fixture = () => readCleanPluginCandidate();
const admit = e2 => verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
// Synthetic verified-head envelope only; retained evidence remains diagnostic/precommit.
const envelope = () => { const e2 = seal({ ...fixture(), commitSha: 'b'.repeat(40) }); return {
  repository: e2.repository, commitSha: e2.commitSha, e2GraphDigest: CLEAN_PLUGIN_GRAPH,
  e2CurrentContentSha256: e2.contentSha256, e2GraphTransition: admit(e2) }; };

test('actual retained Clean graph reverses only IO removal and report hash to exact main 1834', () => {
  const e2 = fixture(), original = canonicalGraph(e2), manifest = readCleanPluginManifest();
  const projection = acceptedE2GraphProjection(e2), previous = verifyCleanPluginDelta(projection);
  assert.equal(graphHash(projection), CLEAN_PLUGIN_GRAPH); assert.equal(graphHash(previous), RELEASE_PLUGIN_GRAPH);
  assert.deepEqual(previous, acceptedE2GraphProjection(readCleanPluginBaseline()));
  verifyReleasePluginDelta(previous);
  for (const key of Object.keys(projection)) if (key !== 'maven') assert.deepEqual(previous[key], projection[key]);
  for (const key of Object.keys(projection.maven)) if (!['resolvedPluginCoordinates', 'pluginResolutionSha256'].includes(key))
    assert.deepEqual(previous.maven[key], projection.maven[key]);
  assert.deepEqual(manifest.addedPluginCoordinates, []);
  assert.deepEqual(manifest.removedPluginCoordinates, ['commons-io:commons-io:jar:2.6']);
  assert.equal(projection.maven.resolvedPluginCoordinates.length, 271);
  assert.equal(previous.maven.resolvedPluginCoordinates.length, 272);
  assert.ok(projection.maven.resolvedPluginCoordinates.includes('commons-io:commons-io:jar:2.20.0'));
  for (const [name, graph] of [['prior', previous], ['current', projection]]) {
    const input = osvInputFromE2(graph);
    assert.equal(input.packageCount, manifest.osvInput[name].packageCount);
    assert.equal(sha(JSON.stringify(input.scannerInput)), manifest.osvInput[name].inputBytesSha256);
  }
  assert.equal(canonicalGraph(e2), original);
});

test('Clean production reader validates 17 owners, all 16 unaffected inventories and the actual source witness', () => {
  const manifest = readCleanPluginManifest(), source = readCleanPluginSourceWitness();
  const owners = verifyCleanPluginOwnerDelta(readReleasePluginReport(), readCleanPluginReport());
  assert.equal(owners.ownerCount, 17); assert.equal(owners.unchangedOtherPluginOwnerCount, 16);
  assert.deepEqual(owners.changes, manifest.pluginOwnerChanges);
  assert.equal(sha(readCleanPluginReport()), fixture().maven.pluginResolutionSha256);
  assert.equal(source.trackedPomCount, 28); assert.equal(source.reactorPaths.length, 26);
  assert.deepEqual(source.sourcePomFiles.filter(row => row.changed).map(row => row.repoPath), ['pom.xml']);
  assert.equal(source.rootPom.rawSha256, 'e5637b8d5752474f9d1940f7b295ef0d3046bc6460391992f1936d4b306f40f2');
  assert.equal(source.captureKind, 'LOCAL_PRECOMMIT_POM_CANDIDATE');
  for (const key of ['sourceWorktreeClean', 'exactHeadEvidence', 'compatibilityAcceptanceClaimed', 'scannerDispositionClaimed', 'reviewerAcceptanceClaimed']) assert.equal(source[key], false);
  assert.equal(source.effectiveModelCapture.reviewedModelAcceptanceClaimed, false);
  assert.equal(manifest.capture.exactHeadEvidence, false); assert.equal(manifest.capture.sourceWorktreeClean, false);
});

const rewriteOwner = (raw, selected, change) => {
  let owner;
  return raw.split('\n').flatMap(line => {
    if (/^   \S/.test(line)) owner = line.trim();
    return owner === selected && /^      \S/.test(line) ? change(line) : [line];
  }).join('\n');
};
for (const [name, change] of [
  ['unrelated owner with unchanged global union', raw => rewriteOwner(raw, 'org.apache.maven.plugins:maven-release-plugin:jar:3.3.1', line =>
    [line.replace('commons-io:commons-io:jar:2.20.0', 'commons-io:commons-io:jar:2.21.0')])],
  ['unknown owner with unchanged coordinates', raw => raw.replaceAll('   org.apache.maven.plugins:maven-clean-plugin:jar:3.2.0\n', '   foreign:plugin:jar:1\n')],
  ['duplicate dependency', raw => rewriteOwner(raw, 'org.apache.maven.plugins:maven-clean-plugin:jar:3.2.0', line =>
    line.includes('commons-io') ? [line, line] : [line])],
  ['missing Clean IO', raw => rewriteOwner(raw, 'org.apache.maven.plugins:maven-clean-plugin:jar:3.2.0', line => line.includes('commons-io') ? [] : [line])],
  ['old Clean IO survived', raw => rewriteOwner(raw, 'org.apache.maven.plugins:maven-clean-plugin:jar:3.2.0', line => [line.replace(':2.20.0', ':2.6')])],
  ['inconsistent repeated owner', raw => raw.replace('      commons-io:commons-io:jar:2.20.0', '      commons-io:commons-io:jar:2.21.0')],
  ['unrecognized report line', raw => raw + '\nunknown evidence\n'],
  ['changed report bytes only', raw => raw + '\n'],
]) test(`Clean production owner verifier rejects ${name}`, () => {
  assert.throws(() => verifyCleanPluginOwnerDelta(readReleasePluginReport(), change(readCleanPluginReport())));
});

test('Clean lineage preserves exact main 1834 Release receipt and every older receipt without relabeling', () => {
  const e4 = envelope(), receipt = requirePreservedGraph(e4, BASE_GRAPH, e4.commitSha);
  assert.equal(receipt.schemaVersion, 'APPROVAL_CLEAN_PLUGIN_GRAPH_LINEAGE_V1');
  assert.equal(receipt.priorE2GraphDigest, RELEASE_PLUGIN_GRAPH);
  assert.deepEqual(receipt.preservedReleasePluginLineage, readCleanPluginPreservedReleaseLineage());
  const release = receipt.preservedReleasePluginLineage;
  assert.equal(release.contentSha256, 'b99400f63544b6832406a7394148c0b794e6b53c92f6010cf4bfeff53ce8db69');
  assert.equal(release.commitSha, '757fe355b2d868916e112f2fcbbe093b865e0669');
  assert.equal(release.sourceE2ContentSha256, '04738e5be3a900c7993c2a7700dc932ce666eb76089fcc7d35d25da0efa623e2');
  assert.deepEqual(release, requirePreservedGraph({ repository: e4.repository, commitSha: release.commitSha,
    e2GraphDigest: RELEASE_PLUGIN_GRAPH, e2CurrentContentSha256: release.sourceE2ContentSha256,
    e2GraphTransition: release }, BASE_GRAPH, release.commitSha));
  assert.equal(receipt.releaseBlocked, true); assert.equal(receipt.findingReviewRequired, true);
  assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH), /verified head/);
  assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH, 'f'.repeat(40)), /verified head/);
  const e2 = fixture();
  assert.throws(() => verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH), /verified head/);
});

for (const [name, mutate] of [
  ['runtime component', e => { e.maven.components[0].version = 'forged'; }],
  ['runtime edge', e => { e.maven.edges.pop(); }],
  ['BOM order', e => { e.maven.importedBoms.reverse(); }],
  ['reactor root', e => { e.maven.reactorRoots.pop(); }],
  ['scope', e => { e.maven.components[0].scope = 'forged'; }],
  ['license', e => { e.maven.components[0].licenses = []; }],
  ['report hash', e => { e.maven.pluginResolutionSha256 = '0'.repeat(64); }],
  ['old IO', e => { e.maven.resolvedPluginCoordinates.push('commons-io:commons-io:jar:2.6'); }],
  ['missing current IO', e => { e.maven.resolvedPluginCoordinates = e.maven.resolvedPluginCoordinates.filter(v => v !== 'commons-io:commons-io:jar:2.20.0'); }],
  ['unknown coordinate', e => { e.maven.resolvedPluginCoordinates.push('foreign:plugin:jar:1'); }],
  ['duplicate coordinate', e => { e.maven.resolvedPluginCoordinates.push(e.maven.resolvedPluginCoordinates[0]); }],
  ['coordinate order', e => { e.maven.resolvedPluginCoordinates.reverse(); }],
  ['pnpm', e => { e.pnpm.workspaceProjectCount++; }],
  ['accepted Actions', e => { e.githubActions.acceptedDependencyGraph.githubActions.workflowCount++; }],
  ['current Actions outside projection', e => { e.githubActions.workflows[0].blobSha = 'f'.repeat(40); }],
  ['current limitations outside projection', e => { e.limitations.push('FORGED'); }],
  ['toolchain outside projection', e => { e.toolchain.forged = true; }],
  ['unknown Maven field', e => { e.maven.forged = true; }],
  ['unknown E2 field outside projection', e => { e.forged = true; }],
]) test(`Clean rejects rehashed ${name}`, () => {
  const e2 = fixture(); mutate(e2); assert.throws(() => admit(seal(e2)));
});
for (const field of [...Object.keys(readCleanPluginManifest()).filter(k => k !== 'contentSha256'), 'unknown']) {
  test(`Clean rejects rehashed manifest ${field}`, () => {
    const manifest = readCleanPluginManifest(); manifest[field] = 'forged';
    assert.throws(() => verifyCleanPluginDelta(acceptedE2GraphProjection(fixture()), seal(manifest)), /content digest mismatch/);
  });
}
for (const [name, mutate] of [
  ['source base head', m => { m.capture.sourceBaseHead = 'f'.repeat(40); }],
  ['source base tree', m => { m.capture.sourceBaseTree = 'f'.repeat(40); }],
  ['source POM', m => { m.capture.sourcePom.rawSha256 = 'f'.repeat(64); }],
  ['source witness', m => { m.capture.sourceWitness.contentSha256 = 'f'.repeat(64); }],
  ['capture unknown field', m => { m.capture.unknown = true; }],
  ['exact-head claim', m => { m.capture.exactHeadEvidence = true; }],
  ['clean-source claim', m => { m.capture.sourceWorktreeClean = true; }],
]) test(`Clean refuses rehashed ${name}`, () => {
  const manifest = readCleanPluginManifest(); mutate(manifest);
  assert.throws(() => readCleanPluginCandidate(seal(manifest)), /content digest mismatch/);
});
for (const field of [...Object.keys(envelope().e2GraphTransition).filter(k => k !== 'contentSha256'), 'unknown']) {
  test(`Clean rejects rehashed receipt ${field}`, () => {
    const e4 = envelope(); e4.e2GraphTransition[field] = 'forged'; e4.e2GraphTransition = seal(e4.e2GraphTransition);
    assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH, e4.commitSha), /lineage mismatch/);
  });
}
test('Clean rejects rehashed nested historical receipt edits', () => {
  const e4 = envelope(), release = e4.e2GraphTransition.preservedReleasePluginLineage;
  release.preservedSiteDependencyPluginLineage.archivalCandidate.exactHeadEvidence = true;
  release.preservedSiteDependencyPluginLineage = seal(release.preservedSiteDependencyPluginLineage);
  e4.e2GraphTransition.preservedReleasePluginLineage = seal(release); e4.e2GraphTransition = seal(e4.e2GraphTransition);
  assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH, e4.commitSha), /lineage mismatch/);
});

test('Clean production reader rejects changed archived bytes and current POM bytes', t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'clean-plugin-pins-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const witness = readCleanPluginSourceWitness();
  const files = ['scripts/security/clean-plugin-graph-transition.mjs', 'scripts/security/release-plugin-graph-transition.mjs',
    'scripts/security/site-dependency-plugin-graph-transition.mjs', 'scripts/security/build-plugin-jackson-graph-transition.mjs',
    'scripts/security/server-dependency-graph-transition.mjs', 'scripts/security/m6-pr-e-e2-generate-sbom.mjs',
    'scripts/security/maven-workflow-transition.mjs', 'scripts/security/hygiene-java21-workflow-transition.mjs',
    'scripts/ci/maven-toolchain.mjs',
    'docs/operations/release-plugin-transition.json', 'docs/operations/release-plugin-evidence/precommit-candidate-E2.json',
    'docs/operations/release-plugin-evidence/plugin-report-segments.json', 'docs/operations/clean-plugin-transition.json',
    ...['accepted-main-1834-E2.json', 'accepted-main-1834-release-lineage.json', 'precommit-candidate-E2.json',
      'plugin-owner-report.txt', 'precommit-pom.xml', 'accepted-main-1834-pom.xml', 'capture-execution.json',
      'source-witness.json'].map(name => `docs/operations/clean-plugin-evidence/${name}`),
    ...witness.sourcePomFiles.map(row => row.repoPath)];
  for (const file of files) { mkdirSync(dirname(resolve(directory, file)), { recursive: true });
    writeFileSync(resolve(directory, file), readFileSync(new URL(`../../${file}`, import.meta.url))); }
  writeFileSync(resolve(directory, 'run.mjs'), "import {readCleanPluginManifest} from './scripts/security/clean-plugin-graph-transition.mjs';readCleanPluginManifest();");
  const run = () => spawnSync(process.execPath, [resolve(directory, 'run.mjs')], { encoding: 'utf8', timeout: 5000 });
  const baseline = run(); assert.equal(baseline.status, 0, baseline.stderr);
  for (const file of files.filter(value => value.startsWith('docs/operations/clean-plugin-') || value.endsWith('pom.xml'))) {
    const path = resolve(directory, file), original = readFileSync(path);
    writeFileSync(path, Buffer.concat([original, Buffer.from('\n')]));
    const result = run(); assert.notEqual(result.status, 0, file); assert.match(result.stderr, /byte digest mismatch/);
    writeFileSync(path, original);
  }
});

test('synthetic Clean coverage includes every actual target and retains a new unresolved finding', () => {
  const e2 = fixture(), original = canonicalGraph(e2), scan = syntheticCleanPluginOsv(e2, {}), manifest = readCleanPluginManifest();
  verifyOsvCoverage(scan.coverage, scan.findings, e2, { head: e2.commitSha, graphDigest: CLEAN_PLUGIN_GRAPH });
  assert.equal(scan.coverage.inputPackageCount, 474); assert.equal(scan.coverage.reportedPackageCount, 474);
  assert.equal(scan.coverage.inputBytesSha256, manifest.osvInput.current.inputBytesSha256);
  assert.equal(scan.findings[0].upstreamFindingId, 'GHSA-synthetic-unit-only-clean-plugin');
  assert.equal(canonicalGraph(e2), original);
  for (const mutate of [
    c => { c.targets.splice(c.targets.findIndex(t => !t.advisoryIds.length), 1); },
    c => { c.targets = c.targets.filter(t => !t.scopes.includes('build-plugin')); },
    c => { c.commitSha = 'f'.repeat(40); },
    c => { c.inputBytesSha256 = manifest.osvInput.prior.inputBytesSha256; },
  ]) {
    const coverage = structuredClone(scan.coverage); mutate(coverage);
    assert.throws(() => verifyOsvCoverage(seal(coverage), scan.findings, e2, { head: e2.commitSha, graphDigest: CLEAN_PLUGIN_GRAPH }));
  }
});
