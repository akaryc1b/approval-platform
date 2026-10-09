import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { BASE_GRAPH, SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH,
  SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, canonicalGraph, graphHash,
  readReleasePluginCandidate, readReleasePluginManifest, readReleasePluginReport, verifyReleasePluginDelta,
  readSiteDependencyPluginCandidate, readSiteDependencyPluginReport, readSiteDependencyPluginManifest,
  verifySiteDependencyPluginDelta, verifyBuildPluginJacksonDelta,
  verifyObservabilityGraph, requirePreservedGraph } from '../security/observability-dependency-graph.mjs';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { parseResolvedPluginReport } from '../security/m6-pr-e-e3-r3a-review-osv-drift.mjs';
import { osvInputFromE2, verifyOsvCoverage } from '../security/osv-scan-coverage.mjs';
import { syntheticReleasePluginOsv } from './fixtures/release-plugin-fixture.mjs';
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: graphHash(payload) }; };
const fixture = () => readReleasePluginCandidate();
const admit = e2 => verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
// Synthetic current-head envelope, never an actual scan or exact-source capture.
const envelope = () => { const e2 = seal({ ...fixture(), commitSha: 'b'.repeat(40) }); return {
  repository: e2.repository, commitSha: e2.commitSha, e2GraphDigest: RELEASE_PLUGIN_GRAPH,
  e2CurrentContentSha256: e2.contentSha256, e2GraphTransition: admit(e2) }; };
const sha = value => createHash('sha256').update(value).digest('hex');
const owners = raw => {
  const values = new Map();
  for (const group of parseResolvedPluginReport(raw)) {
    const name = `${group.plugin.groupId}:${group.plugin.artifactId}`;
    const row = { owner: group.plugin.coordinate, dependencies: group.dependencies.map(value => value.coordinate).sort() };
    if (values.has(name)) assert.deepEqual(values.get(name), row); else values.set(name, row);
  }
  return values;
};

test('retained precommit Release capture reverses only declared plugin coordinates and report hash', () => {
  const e2 = fixture(), original = canonicalGraph(e2), manifest = readReleasePluginManifest();
  const current = acceptedE2GraphProjection(e2), previous = verifyReleasePluginDelta(current);
  assert.equal(graphHash(current), RELEASE_PLUGIN_GRAPH);
  assert.equal(graphHash(previous), SITE_DEPENDENCY_PLUGIN_GRAPH);
  assert.deepEqual(previous, acceptedE2GraphProjection(readSiteDependencyPluginCandidate()));
  const beforeSite = verifySiteDependencyPluginDelta(previous);
  assert.equal(graphHash(beforeSite), BUILD_PLUGIN_JACKSON_GRAPH);
  assert.equal(graphHash(verifyBuildPluginJacksonDelta(beforeSite)), SERVER_DEPENDENCY_GRAPH);
  for (const key of Object.keys(current)) if (key !== 'maven') assert.deepEqual(previous[key], current[key]);
  for (const key of Object.keys(current.maven)) if (!['resolvedPluginCoordinates', 'pluginResolutionSha256'].includes(key))
    assert.deepEqual(previous.maven[key], current.maven[key]);
  assert.deepEqual(current.maven.resolvedPluginCoordinates.filter(value => !previous.maven.resolvedPluginCoordinates.includes(value)), manifest.addedPluginCoordinates);
  assert.deepEqual(previous.maven.resolvedPluginCoordinates.filter(value => !current.maven.resolvedPluginCoordinates.includes(value)), manifest.removedPluginCoordinates);
  assert.equal(manifest.addedPluginCoordinateCount, 26);
  assert.equal(manifest.addedPluginCoordinateCount, manifest.addedPluginCoordinates.length);
  assert.equal(manifest.removedPluginCoordinateCount, 36);
  assert.equal(manifest.removedPluginCoordinateCount, manifest.removedPluginCoordinates.length);
  assert.equal(previous.maven.resolvedPluginCoordinates.length, 282);
  assert.equal(current.maven.resolvedPluginCoordinates.length, 272);
  assert.equal(manifest.capture.kind, 'LOCAL_PRECOMMIT_POM_CANDIDATE');
  assert.equal(manifest.capture.exactHeadEvidence, false); assert.equal(manifest.capture.sourceWorktreeClean, false);
  assert.equal(e2.commitSha, manifest.capture.sourceBaseHead); assert.equal(canonicalGraph(e2), original);
  for (const [label, graph] of [['prior', previous], ['current', current]]) {
    const input = osvInputFromE2(graph);
    assert.equal(input.packageCount, manifest.osvInput[label].packageCount);
    assert.equal(sha(JSON.stringify(input.scannerInput)), manifest.osvInput[label].inputBytesSha256);
  }
});

test('retained owner reports change only Release and preserve all 16 other realms exactly', () => {
  const manifest = readReleasePluginManifest(), priorRaw = readSiteDependencyPluginReport(), currentRaw = readReleasePluginReport();
  assert.equal(sha(priorRaw), manifest.pluginResolutionHashChange.from);
  assert.equal(sha(currentRaw), manifest.pluginResolutionHashChange.to);
  const prior = owners(priorRaw), current = owners(currentRaw);
  assert.deepEqual([...current.keys()].sort(), [...prior.keys()].sort());
  const changes = [...prior].filter(([name, value]) => canonicalGraph(value) !== canonicalGraph(current.get(name)))
    .map(([plugin, before]) => ({ plugin, before, after: current.get(plugin) }));
  assert.deepEqual(changes, manifest.pluginOwnerChanges);
  assert.deepEqual(changes.map(value => value.plugin), ['org.apache.maven.plugins:maven-release-plugin']);
  assert.equal(prior.size - changes.length, 16); assert.equal(manifest.unchangedOtherPluginOwnerCount, 16);
  assert.equal(changes[0].before.owner, 'org.apache.maven.plugins:maven-release-plugin:jar:3.0.1');
  const release = changes[0].after;
  assert.equal(release.owner, 'org.apache.maven.plugins:maven-release-plugin:jar:3.3.1');
  for (const coordinate of ['org.eclipse.jgit:org.eclipse.jgit:jar:5.13.5.202508271544-r',
    'org.eclipse.jgit:org.eclipse.jgit.ssh.apache:jar:5.13.5.202508271544-r',
    ...['sshd-osgi', 'sshd-sftp', 'sshd-core', 'sshd-common'].map(name => `org.apache.sshd:${name}:jar:2.16.0`),
    'commons-io:commons-io:jar:2.20.0', 'org.codehaus.plexus:plexus-utils:jar:4.0.3']) {
    assert.ok(release.dependencies.includes(coordinate), coordinate);
    const identity = coordinate.split(':').slice(0, 2).join(':') + ':';
    assert.deepEqual(release.dependencies.filter(value => value.startsWith(identity)), [coordinate]);
  }
  assert.deepEqual(manifest.suppressions, []); assert.deepEqual(manifest.exceptions, []);
  assert.equal(manifest.findingReviewRequired, true); assert.equal(manifest.releaseBlocked, true);
});

test('Release lineage preserves the complete Site, Jackson, server and original receipts byte-for-byte', () => {
  const e4 = envelope(), receipt = requirePreservedGraph(e4, BASE_GRAPH, e4.commitSha), manifest = readReleasePluginManifest();
  assert.equal(receipt.schemaVersion, 'APPROVAL_RELEASE_PLUGIN_GRAPH_LINEAGE_V1');
  assert.equal(receipt.currentE2GraphDigest, RELEASE_PLUGIN_GRAPH);
  assert.equal(receipt.priorE2GraphDigest, SITE_DEPENDENCY_PLUGIN_GRAPH);
  const site = receipt.preservedSiteDependencyPluginLineage;
  assert.equal(site.commitSha, manifest.base.sourceHead); assert.equal(site.sourceE2ContentSha256, manifest.base.e2ContentSha256);
  const oldEnvelope = { repository: e4.repository, commitSha: site.commitSha,
    e2CurrentContentSha256: site.sourceE2ContentSha256, e2GraphDigest: SITE_DEPENDENCY_PLUGIN_GRAPH, e2GraphTransition: site };
  assert.deepEqual(site, requirePreservedGraph(oldEnvelope, BASE_GRAPH, site.commitSha));
  assert.equal(site.currentE2GraphDigest, SITE_DEPENDENCY_PLUGIN_GRAPH);
  assert.deepEqual(site.archivalCandidate, readSiteDependencyPluginManifest().capture);
  assert.equal(site.preservedBuildPluginJacksonLineage.currentE2GraphDigest, BUILD_PLUGIN_JACKSON_GRAPH);
  assert.equal(site.preservedBuildPluginJacksonLineage.preservedServerGraphLineage.archivalSourceHead, '921d5ec5a770cccdfa24f9cf809b0a3a609f9cbe');
  assert.equal(receipt.releaseBlocked, true); assert.equal(receipt.findingReviewRequired, true);
  assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH), /requires current verified head/);
  assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH, 'c'.repeat(40)), /verified head mismatch/);
  assert.throws(() => verifyObservabilityGraph(fixture(), acceptedE2GraphProjection(fixture()), BASE_GRAPH), /requires current verified head/);
  assert.throws(() => verifyObservabilityGraph(fixture(), acceptedE2GraphProjection(fixture()), BASE_GRAPH, 'c'.repeat(40)), /verified head mismatch/);
});

for (const [name, mutate] of [
  ['runtime component', e => { e.maven.components[0].version = 'forged'; }],
  ['runtime edge', e => { e.maven.edges.pop(); }],
  ['imported BOM', e => { e.maven.importedBoms.reverse(); }],
  ['reactor root', e => { e.maven.reactorRoots.pop(); }],
  ['scope', e => { e.maven.components[0].scope = 'forged'; }],
  ['license', e => { e.maven.components[0].licenses = []; }],
  ['plugin raw report', e => { e.maven.pluginResolutionSha256 = '0'.repeat(64); }],
  ['removed plugin target', e => { e.maven.resolvedPluginCoordinates.pop(); }],
  ['unlisted plugin target', e => { e.maven.resolvedPluginCoordinates.push('forged:plugin:jar:1'); }],
  ['old Release mixed target', e => { e.maven.resolvedPluginCoordinates.push('org.apache.maven.plugins:maven-release-plugin:jar:3.0.1'); }],
  ['plugin duplicate', e => { e.maven.resolvedPluginCoordinates.push(e.maven.resolvedPluginCoordinates[0]); }],
  ['plugin order', e => { e.maven.resolvedPluginCoordinates.reverse(); }],
  ['pnpm', e => { e.pnpm.workspaceProjectCount++; }],
  ['Actions', e => { e.githubActions.acceptedDependencyGraph.githubActions.workflowCount++; }],
  ['unknown field', e => { e.maven.unreviewed = true; }],
]) test(`Release transition rejects undeclared ${name} after E2 rehashing`, () => {
  const e2 = fixture(); mutate(e2); assert.throws(() => admit(seal(e2)), /graph drift|accepted Action graph canonical mismatch/);
});
for (const field of Object.keys(readReleasePluginManifest()).filter(key => key !== 'contentSha256')) {
  test(`Release transition rejects rehashed ${field} manifest`, () => {
    const manifest = readReleasePluginManifest(); manifest[field] = 'forged';
    assert.throws(() => verifyReleasePluginDelta(acceptedE2GraphProjection(fixture()), seal(manifest)), /content digest mismatch/);
  });
}
for (const [name, mutate] of [
  ['exact-head assertion', capture => { capture.exactHeadEvidence = true; }],
  ['clean source assertion', capture => { capture.sourceWorktreeClean = true; }],
  ['source base head', capture => { capture.sourceBaseHead = 'f'.repeat(40); }],
  ['capture classification', capture => { capture.kind = 'EXACT_HEAD_SCANNER_CAPTURE'; }],
  ['candidate content pin', capture => { capture.e2.contentSha256 = 'f'.repeat(64); }],
  ['report byte pin', capture => { capture.pluginReport.rawSha256 = 'f'.repeat(64); }],
]) test(`Release capture rejects rehashed ${name} without promoting a precommit observation`, () => {
  const manifest = readReleasePluginManifest(); mutate(manifest.capture);
  assert.throws(() => readReleasePluginCandidate(seal(manifest)), /content digest mismatch/);
  assert.throws(() => readReleasePluginReport(seal(manifest)), /content digest mismatch/);
});
for (const field of Object.keys(envelope().e2GraphTransition).filter(key => key !== 'contentSha256')) {
  test(`Release receipt rejects rehashed ${field}`, () => {
    const e4 = envelope(); e4.e2GraphTransition[field] = 'forged'; e4.e2GraphTransition = seal(e4.e2GraphTransition);
    assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH, e4.commitSha), /lineage mismatch/);
  });
}
for (const [name, mutate] of [
  ['Site finding review', site => { site.findingReviewRequired = false; }],
  ['Site archival capture', site => { site.archivalCandidate.exactHeadEvidence = true; }],
  ['Jackson historical head', site => { site.preservedBuildPluginJacksonLineage.commitSha = 'f'.repeat(40); }],
  ['server historical receipt', site => { site.preservedBuildPluginJacksonLineage.preservedServerGraphLineage.archivalSourceHead = 'f'.repeat(40); }],
]) test(`Release receipt rejects rehashed nested ${name}`, () => {
  const e4 = envelope(), site = e4.e2GraphTransition.preservedSiteDependencyPluginLineage; mutate(site);
  site.preservedBuildPluginJacksonLineage.preservedServerGraphLineage = seal(site.preservedBuildPluginJacksonLineage.preservedServerGraphLineage);
  site.preservedBuildPluginJacksonLineage = seal(site.preservedBuildPluginJacksonLineage);
  e4.e2GraphTransition.preservedSiteDependencyPluginLineage = seal(site); e4.e2GraphTransition = seal(e4.e2GraphTransition);
  assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH, e4.commitSha), /lineage mismatch/);
});

test('Release manifest, candidate E2 and report byte pins fail closed through the production reader', t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'release-plugin-pins-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const files = ['scripts/security/release-plugin-graph-transition.mjs', 'scripts/security/site-dependency-plugin-graph-transition.mjs',
    'scripts/security/build-plugin-jackson-graph-transition.mjs', 'scripts/security/server-dependency-graph-transition.mjs',
    'docs/operations/release-plugin-transition.json',
    'docs/operations/release-plugin-evidence/precommit-candidate-E2.json',
    'docs/operations/release-plugin-evidence/plugin-report-segments.json'];
  for (const file of files) { mkdirSync(dirname(resolve(directory, file)), { recursive: true });
    writeFileSync(resolve(directory, file), readFileSync(new URL(`../../${file}`, import.meta.url))); }
  writeFileSync(resolve(directory, 'run.mjs'), "import {readReleasePluginManifest} from './scripts/security/release-plugin-graph-transition.mjs';readReleasePluginManifest();");
  const run = () => spawnSync(process.execPath, [resolve(directory, 'run.mjs')], { encoding: 'utf8', timeout: 5000 });
  const baseline = run(); assert.equal(baseline.status, 0, baseline.stderr);
  for (const file of files.filter(value => value.endsWith('.json'))) {
    const path = resolve(directory, file), raw = readFileSync(path);
    for (const changed of [Buffer.concat([raw, Buffer.from('\n')]), Buffer.from(JSON.stringify(seal({ ...JSON.parse(raw), repository: 'forged/repository' })))]) {
      writeFileSync(path, changed); const result = run(); assert.notEqual(result.status, 0); assert.match(result.stderr, /byte digest mismatch/);
    }
    writeFileSync(path, raw);
  }
});

test('synthetic unit-only Release OSV coverage binds the entire current input and retains a new finding', () => {
  const e2 = fixture(), scan = syntheticReleasePluginOsv(e2, {}), before = canonicalGraph(e2);
  verifyOsvCoverage(scan.coverage, scan.findings, e2, { head: e2.commitSha, graphDigest: RELEASE_PLUGIN_GRAPH });
  const expected = readReleasePluginManifest().osvInput.current;
  assert.equal(scan.coverage.inputPackageCount, expected.packageCount);
  assert.equal(scan.coverage.reportedPackageCount, expected.packageCount);
  assert.equal(scan.coverage.inputBytesSha256, expected.inputBytesSha256);
  assert.equal(scan.findingCount, 1); assert.equal(scan.findings[0].upstreamFindingId, 'GHSA-synthetic-unit-only-release-plugin');
  assert.equal(canonicalGraph(e2), before);
  for (const mutate of [
    coverage => { coverage.targets.pop(); },
    coverage => { coverage.targets = coverage.targets.filter(target => !target.scopes.includes('build-plugin')); },
    coverage => { coverage.commitSha = 'f'.repeat(40); },
    coverage => { coverage.inputBytesSha256 = readReleasePluginManifest().osvInput.prior.inputBytesSha256; },
  ]) {
    const coverage = structuredClone(scan.coverage); mutate(coverage);
    assert.throws(() => verifyOsvCoverage(seal(coverage), scan.findings, e2, { head: e2.commitSha, graphDigest: RELEASE_PLUGIN_GRAPH }));
  }
});
