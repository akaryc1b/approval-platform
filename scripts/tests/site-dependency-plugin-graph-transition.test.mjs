import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { BASE_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH,
  canonicalGraph, graphHash, readSiteDependencyPluginCandidate, readSiteDependencyPluginManifest,
  readSiteDependencyPluginReport, verifySiteDependencyPluginDelta, verifyBuildPluginJacksonDelta,
  verifyObservabilityGraph, requirePreservedGraph, SERVER_DEPENDENCY_GRAPH }
  from '../security/observability-dependency-graph.mjs';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { parseResolvedPluginReport } from '../security/m6-pr-e-e3-r3a-review-osv-drift.mjs';
import { osvInputFromE2, verifyOsvCoverage } from '../security/osv-scan-coverage.mjs';
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: graphHash(payload) }; };
const fixture = () => readSiteDependencyPluginCandidate();
const admit = e => verifyObservabilityGraph(e, acceptedE2GraphProjection(e), BASE_GRAPH, e.commitSha);
const envelope = () => { const e2 = seal({ ...fixture(), commitSha: 'b'.repeat(40) }); return {
  repository: e2.repository, commitSha: e2.commitSha, e2GraphDigest: SITE_DEPENDENCY_PLUGIN_GRAPH,
  e2CurrentContentSha256: e2.contentSha256, e2GraphTransition: admit(e2) }; };
const sha = value => createHash('sha256').update(value).digest('hex');
const owners = raw => {
  const values = new Map();
  for (const group of parseResolvedPluginReport(raw)) {
    const key = `${group.plugin.groupId}:${group.plugin.artifactId}`;
    const row = { owner: group.plugin.coordinate, dependencies: group.dependencies.map(x => x.coordinate).sort() };
    if (values.has(key)) assert.deepEqual(values.get(key), row); else values.set(key, row);
  }
  return values;
};

test('actual Site/Dependency capture reverses only 69 additions, 116 removals and report hash', () => {
  const e2 = fixture(), before = canonicalGraph(e2), manifest = readSiteDependencyPluginManifest();
  const current = acceptedE2GraphProjection(e2), previous = verifySiteDependencyPluginDelta(current);
  assert.equal(graphHash(previous), BUILD_PLUGIN_JACKSON_GRAPH);
  assert.equal(graphHash(verifyBuildPluginJacksonDelta(previous)), SERVER_DEPENDENCY_GRAPH);
  for (const key of Object.keys(current)) if (key !== 'maven') assert.deepEqual(previous[key], current[key]);
  for (const key of Object.keys(current.maven)) if (!['resolvedPluginCoordinates', 'pluginResolutionSha256'].includes(key))
    assert.deepEqual(previous.maven[key], current.maven[key]);
  assert.equal(manifest.addedPluginCoordinateCount, 69); assert.equal(manifest.removedPluginCoordinateCount, 116);
  assert.equal(current.maven.resolvedPluginCoordinates.length, 282); assert.equal(previous.maven.resolvedPluginCoordinates.length, 329);
  assert.equal(manifest.capture.kind, 'LOCAL_PRECOMMIT_POM_CANDIDATE');
  assert.equal(manifest.capture.exactHeadEvidence, false); assert.equal(manifest.capture.sourceWorktreeClean, false);
  assert.equal(e2.commitSha, manifest.capture.sourceBaseHead); assert.equal(canonicalGraph(e2), before);
  for (const [label, graph] of [['prior', previous], ['current', current]]) {
    const input = osvInputFromE2(graph);
    assert.equal(input.packageCount, manifest.osvInput[label].packageCount);
    assert.equal(sha(JSON.stringify(input.scannerInput)), manifest.osvInput[label].inputBytesSha256);
  }
});

test('actual owner reports preserve all 15 other realms and do not force Jetty12 or dismiss advisories', () => {
  const manifest = readSiteDependencyPluginManifest();
  const old = JSON.parse(readFileSync(new URL('./fixtures/r3a-build-plugin-report.json', import.meta.url)));
  const priorRaw = old.segmentOrder.map(index => old.segments[index]).join(old.delimiter);
  assert.equal(sha(priorRaw), manifest.pluginResolutionHashChange.from);
  const prior = owners(priorRaw), current = owners(readSiteDependencyPluginReport());
  assert.deepEqual([...current.keys()].sort(), [...prior.keys()].sort());
  const changes = [...prior].filter(([name, value]) => canonicalGraph(value) !== canonicalGraph(current.get(name)))
    .map(([plugin, before]) => ({ plugin, before, after: current.get(plugin) }));
  assert.deepEqual(changes, manifest.pluginOwnerChanges);
  assert.deepEqual(changes.map(x => x.plugin).sort(), ['org.apache.maven.plugins:maven-dependency-plugin', 'org.apache.maven.plugins:maven-site-plugin']);
  assert.equal(prior.size - changes.length, 15); assert.equal(manifest.unchangedOtherPluginOwnerCount, 15);
  const site = current.get('org.apache.maven.plugins:maven-site-plugin');
  assert.equal(site.owner, 'org.apache.maven.plugins:maven-site-plugin:jar:3.22.0');
  assert.ok(site.dependencies.includes('org.jsoup:jsoup:jar:1.23.2'));
  assert.ok(site.dependencies.includes('org.eclipse.jetty:jetty-server:jar:9.4.58.v20250814'));
  assert.ok(!site.dependencies.some(x => /^org\.eclipse\.jetty:.*:12\./.test(x)));
  assert.deepEqual(manifest.suppressions, []); assert.deepEqual(manifest.exceptions, []);
  assert.equal(manifest.findingReviewRequired, true); assert.equal(manifest.releaseBlocked, true);
});

test('new receipt preserves actual #1816 Jackson lineage and all original server receipts', () => {
  const e4 = envelope(), receipt = requirePreservedGraph(e4, BASE_GRAPH, e4.commitSha), manifest = readSiteDependencyPluginManifest();
  assert.equal(receipt.currentE2GraphDigest, SITE_DEPENDENCY_PLUGIN_GRAPH);
  assert.equal(receipt.priorE2GraphDigest, BUILD_PLUGIN_JACKSON_GRAPH);
  assert.equal(receipt.preservedBuildPluginJacksonLineage.commitSha, manifest.base.sourceHead);
  assert.equal(receipt.preservedBuildPluginJacksonLineage.sourceE2ContentSha256, manifest.base.e2ContentSha256);
  assert.equal(receipt.preservedBuildPluginJacksonLineage.currentE2GraphDigest, BUILD_PLUGIN_JACKSON_GRAPH);
  assert.equal(receipt.preservedBuildPluginJacksonLineage.preservedServerGraphLineage.archivalSourceHead, '921d5ec5a770cccdfa24f9cf809b0a3a609f9cbe');
  assert.equal(receipt.releaseBlocked, true); assert.equal(receipt.findingReviewRequired, true);
  assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH), /requires current verified head/);
  assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH, 'c'.repeat(40)), /verified head mismatch/);
  assert.throws(() => verifyObservabilityGraph(fixture(), acceptedE2GraphProjection(fixture()), BASE_GRAPH), /requires current verified head/);
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
  ['old Site mixed target', e => { e.maven.resolvedPluginCoordinates.push('org.apache.maven.plugins:maven-site-plugin:jar:3.12.1'); }],
  ['plugin duplicate', e => { e.maven.resolvedPluginCoordinates.push(e.maven.resolvedPluginCoordinates[0]); }],
  ['plugin order', e => { e.maven.resolvedPluginCoordinates.reverse(); }],
  ['pnpm', e => { e.pnpm.workspaceProjectCount++; }],
  ['Actions', e => { e.githubActions.acceptedDependencyGraph.githubActions.workflowCount++; }],
  ['unknown field', e => { e.maven.unreviewed = true; }],
]) test(`Site/Dependency transition rejects undeclared ${name} after E2 rehashing`, () => {
  const e2 = fixture(); mutate(e2); assert.throws(() => admit(seal(e2)), /graph drift|accepted Action graph canonical mismatch/);
});
for (const field of Object.keys(readSiteDependencyPluginManifest()).filter(key => key !== 'contentSha256')) {
  test(`Site/Dependency transition rejects rehashed ${field} manifest`, () => {
    const manifest = readSiteDependencyPluginManifest(); manifest[field] = 'forged';
    assert.throws(() => verifySiteDependencyPluginDelta(acceptedE2GraphProjection(fixture()), seal(manifest)), /content digest mismatch/);
  });
}
for (const field of Object.keys(envelope().e2GraphTransition).filter(key => key !== 'contentSha256')) {
  test(`Site/Dependency receipt rejects rehashed ${field}`, () => {
    const e4 = envelope(); e4.e2GraphTransition[field] = 'forged'; e4.e2GraphTransition = seal(e4.e2GraphTransition);
    assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH, e4.commitSha), /lineage mismatch/);
  });
}

test('Site/Dependency manifest, E2 and report byte pins fail closed through the production reader', t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'site-plugin-pins-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const files = ['scripts/security/site-dependency-plugin-graph-transition.mjs',
    'scripts/security/build-plugin-jackson-graph-transition.mjs', 'scripts/security/server-dependency-graph-transition.mjs',
    'docs/operations/site-dependency-plugin-transition.json',
    'docs/operations/site-dependency-plugin-evidence/precommit-candidate-E2.json',
    'docs/operations/site-dependency-plugin-evidence/plugin-report-segments.json'];
  for (const file of files) { mkdirSync(dirname(resolve(directory, file)), { recursive: true });
    writeFileSync(resolve(directory, file), readFileSync(new URL(`../../${file}`, import.meta.url))); }
  writeFileSync(resolve(directory, 'run.mjs'), "import {readSiteDependencyPluginManifest} from './scripts/security/site-dependency-plugin-graph-transition.mjs';readSiteDependencyPluginManifest();");
  const run = () => spawnSync(process.execPath, [resolve(directory, 'run.mjs')], { encoding: 'utf8' });
  assert.equal(run().status, 0);
  for (const file of files.filter(f => f.endsWith('.json'))) {
    const path = resolve(directory, file), raw = readFileSync(path);
    writeFileSync(path, Buffer.concat([raw, Buffer.from('\n')]));
    assert.match(run().stderr, /byte digest mismatch/); writeFileSync(path, raw);
  }
});

// Actual standalone query; source remains explicitly precommit and not full E4.
test('retained actual OSV diagnostic covers all486 targets and retains four version-rebound Jetty advisories', () => {
  const pins = {"normalized-osv-findings.json": "4c23b8671e17375fb0b86e79159022b3aef14d173a76417493dc9db2f169c8a5", "diagnostic-target-coverage.json": "0aff0d6cf867dcd89a730034186548641119c353ce41b3d8525290e9a05d7aa7", "verified-osv-summary.json": "9a3b13065a5cdd060a7e831e5f087fd53f82fb2b6ac6d63bb6b6a5b61aeb86a3", "comparison-to-accepted-E4.json": "76738f14d5992b0057461559c79f1cac6ed95cbd73f96cb0ed9ce7bfd83c1860"};
  const read = name => {
    const raw = readFileSync(new URL(`../../docs/operations/site-dependency-plugin-evidence/osv-diagnostic/${name}`, import.meta.url));
    assert.equal(sha(raw), pins[name]); return JSON.parse(raw);
  };
  const summary = read('verified-osv-summary.json'), wrapper = read('diagnostic-target-coverage.json');
  const findings = read('normalized-osv-findings.json'), comparison = read('comparison-to-accepted-E4.json');
  const e2 = fixture();
  assert.equal(summary.classification, 'STANDALONE_OSV_ONLY_DIAGNOSTIC_UNCOMMITTED_CANDIDATE_NOT_E4');
  assert.equal(summary.allScannersCompleted, false); assert.equal(summary.graphAdmitted, false);
  assert.equal(summary.releaseBlocked, true); assert.equal(wrapper.notE4Evidence, true);
  assert.equal(summary.sourceE2ContentSha256, e2.contentSha256);
  assert.equal(summary.coverageContentSha256, wrapper.coverage.contentSha256);
  assert.equal(summary.normalizedFindingsSha256, graphHash(findings));
  assert.equal(seal(summary).contentSha256, summary.contentSha256);
  verifyOsvCoverage(wrapper.coverage, findings, e2, { head: e2.commitSha, graphDigest: SITE_DEPENDENCY_PLUGIN_GRAPH });
  assert.equal(wrapper.coverage.inputPackageCount, 486); assert.equal(wrapper.coverage.reportedPackageCount, 486);
  assert.equal(findings.length, 33); assert.equal(comparison.acceptedFindingCount, 63);
  assert.equal(comparison.removedFindings.length, 34); assert.equal(comparison.addedFindings.length, 4);
  assert.equal(comparison.changedRetainedFindings.length, 0); assert.equal(comparison.unchangedRetainedFindingCount, 29);
  const prior = JSON.parse(readFileSync(new URL('../../docs/operations/build-plugin-jackson-evidence/osv-diagnostic/normalized-osv-findings.json', import.meta.url)));
  const priorIds = new Map(prior.map(f => [f.findingId, f]));
  const additions = new Set(comparison.addedFindings.map(f => f.findingId));
  for (const f of findings) if (!additions.has(f.findingId)) assert.deepEqual(f, priorIds.get(f.findingId));
  for (const f of comparison.addedFindings) {
    assert.ok(f.package.name.startsWith('org.eclipse.jetty:')); assert.equal(f.package.version, '9.4.58.v20250814');
    const old = comparison.removedFindings.find(x => x.upstreamFindingId === f.upstreamFindingId && x.package.name === f.package.name);
    assert.ok(old); assert.equal(old.package.version, '9.4.46.v20220331');
    for (const key of ['aliases', 'upstreamSeverity', 'fixedVersions', 'scopes']) assert.deepEqual(f[key], old[key]);
  }
  const jsoup = wrapper.coverage.targets.find(x => x.package.name === 'org.jsoup:jsoup' && x.package.version === '1.23.2');
  assert.ok(jsoup); assert.deepEqual(jsoup.advisoryIds, []);
  const diff = JSON.parse(readFileSync(new URL('../../docs/operations/site-dependency-plugin-evidence/osv-target-diff.json', import.meta.url)));
  assert.equal(diff.priorPackageCount, 533); assert.equal(diff.currentPackageCount, 486);
  assert.equal(diff.added.length, 69); assert.equal(diff.removed.length, 116);
});
