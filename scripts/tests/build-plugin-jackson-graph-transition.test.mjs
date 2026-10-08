import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { readPluginOsvDiagnostic } from './fixtures/build-plugin-jackson-fixture.mjs';
import { BASE_GRAPH, SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, canonicalGraph, graphHash,
  readBuildPluginJacksonCandidate, readBuildPluginJacksonManifest, verifyBuildPluginJacksonDelta,
  verifyObservabilityGraph, requirePreservedGraph, verifyServerDependencyDelta, OBSERVABILITY_OTEL_GRAPH }
  from '../security/observability-dependency-graph.mjs';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { osvInputFromE2 } from '../security/osv-scan-coverage.mjs';
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: graphHash(payload) }; };
const fixture = () => readBuildPluginJacksonCandidate();
const admit = e => verifyObservabilityGraph(e, acceptedE2GraphProjection(e), BASE_GRAPH, e.commitSha);
const envelope = () => { const e2 = seal({ ...fixture(), commitSha: 'b'.repeat(40) }); return {
  repository: e2.repository, commitSha: e2.commitSha, e2GraphDigest: BUILD_PLUGIN_JACKSON_GRAPH,
  e2CurrentContentSha256: e2.contentSha256, e2GraphTransition: admit(e2) }; };

test('actual modified-POM capture reverses only two plugin coordinates and raw report hash into the exact previous graph', () => {
  const e2 = fixture(), before = canonicalGraph(e2), manifest = readBuildPluginJacksonManifest();
  const projection = acceptedE2GraphProjection(e2), previous = verifyBuildPluginJacksonDelta(projection);
  assert.equal(graphHash(previous), SERVER_DEPENDENCY_GRAPH);
  assert.equal(graphHash(verifyServerDependencyDelta(previous)), OBSERVABILITY_OTEL_GRAPH);
  for (const key of Object.keys(projection)) if (key !== 'maven') assert.deepEqual(previous[key], projection[key]);
  for (const key of Object.keys(projection.maven)) if (!['resolvedPluginCoordinates', 'pluginResolutionSha256'].includes(key))
    assert.deepEqual(previous.maven[key], projection.maven[key]);
  assert.equal(previous.maven.resolvedPluginCoordinates.length, 329);
  for (const name of ['jackson-core', 'jackson-databind']) {
    assert.ok(previous.maven.resolvedPluginCoordinates.includes(`tools.jackson.core:${name}:jar:3.1.5`));
    assert.ok(e2.maven.resolvedPluginCoordinates.includes(`tools.jackson.core:${name}:jar:3.1.7`));
    assert.ok(!e2.maven.resolvedPluginCoordinates.includes(`tools.jackson.core:${name}:jar:3.1.5`));
  }
  assert.equal(manifest.capture.kind, 'LOCAL_PRECOMMIT_POM_CANDIDATE');
  assert.equal(manifest.capture.exactHeadEvidence, false);
  assert.equal(manifest.capture.sourceWorktreeClean, false);
  assert.equal(manifest.capture.sourceBaseHead, e2.commitSha);
  assert.equal(canonicalGraph(e2), before);
  for (const [label, graph] of [['prior', previous], ['current', projection]]) {
    const input = osvInputFromE2(graph);
    assert.equal(input.packageCount, manifest.osvInput[label].packageCount);
    assert.equal(createHash('sha256').update(JSON.stringify(input.scannerInput)).digest('hex'), manifest.osvInput[label].inputBytesSha256);
  }
});

test('new current receipt preserves the actual prior #1812 server evidence and all original source witnesses', () => {
  const e4 = envelope(), r = requirePreservedGraph(e4, BASE_GRAPH, e4.commitSha), m = readBuildPluginJacksonManifest();
  assert.equal(r.commitSha, 'b'.repeat(40));
  assert.equal(r.currentE2GraphDigest, BUILD_PLUGIN_JACKSON_GRAPH);
  assert.equal(r.priorE2GraphDigest, SERVER_DEPENDENCY_GRAPH);
  assert.equal(r.preservedServerGraphLineage.commitSha, m.base.sourceHead);
  assert.equal(r.preservedServerGraphLineage.sourceE2ContentSha256, m.base.e2ContentSha256);
  assert.equal(r.preservedServerGraphLineage.currentE2GraphDigest, SERVER_DEPENDENCY_GRAPH);
  assert.notEqual(r.sourceE2ContentSha256, r.preservedServerGraphLineage.sourceE2ContentSha256);
  assert.equal(r.preservedServerGraphLineage.archivalSourceHead, '921d5ec5a770cccdfa24f9cf809b0a3a609f9cbe');
  assert.equal(r.releaseBlocked, true); assert.equal(r.findingReviewRequired, true);
  assert.equal(r.runtimeComponentChangeCount, 0); assert.equal(r.runtimeEdgeChangeCount, 0);
  assert.equal(r.pluginCoordinateVersionChangeCount, 2);
  assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH), /requires current verified head/);
});

for (const [name, mutate] of [
  ['runtime component', e => { e.maven.components[0].version = 'forged'; }],
  ['runtime edge', e => { e.maven.edges.pop(); }],
  ['imported BOM', e => { e.maven.importedBoms.reverse(); }],
  ['reactor root', e => { e.maven.reactorRoots.pop(); }],
  ['scope', e => { e.maven.components[0].scope = 'forged'; }],
  ['license', e => { e.maven.components[0].licenses = []; }],
  ['plugin raw report', e => { e.maven.pluginResolutionSha256 = '0'.repeat(64); }],
  ['old plugin coordinate', e => { e.maven.resolvedPluginCoordinates = e.maven.resolvedPluginCoordinates.map(c => c.replace('jackson-core:jar:3.1.7','jackson-core:jar:3.1.5')); }],
  ['mixed plugin coordinate', e => { e.maven.resolvedPluginCoordinates.push('tools.jackson.core:jackson-core:jar:3.1.5'); }],
  ['plugin owner', e => { e.maven.resolvedPluginCoordinates = e.maven.resolvedPluginCoordinates.map(c => c.replace('spring-boot-maven-plugin:jar:4.0.8','spring-boot-maven-plugin:jar:4.0.9')); }],
  ['plugin duplicate', e => { e.maven.resolvedPluginCoordinates.push(e.maven.resolvedPluginCoordinates[0]); }],
  ['plugin order', e => { e.maven.resolvedPluginCoordinates.reverse(); }],
  ['pnpm', e => { e.pnpm.workspaceProjectCount++; }],
  ['accepted Actions', e => { e.githubActions.acceptedDependencyGraph.githubActions.workflowCount++; }],
  ['unknown Maven field', e => { e.maven.unreviewed = true; }],
]) test(`plugin descendant rejects undeclared ${name} after E2 rehashing`, () => {
  const e = fixture(), original = canonicalGraph(e); mutate(e); assert.notEqual(canonicalGraph(e), original);
  assert.throws(() => admit(seal(e)), /graph drift|accepted Action graph canonical mismatch/);
});
for (const field of ['schemaVersion','repository','base','observed','capture','pluginOwner','pluginCoordinateVersionChanges',
  'pluginCoordinateVersionChangeCount','pluginResolutionHashChange','runtimeComponentChangeCount','runtimeEdgeChangeCount',
  'importedBomChangeCount','scopeChangeCount','licenseChangeCount','inventory','osvInput','findingReviewRequired','releaseBlocked','suppressions','exceptions']) {
  test(`plugin verifier rejects rehashed ${field} manifest`, () => {
    const m = readBuildPluginJacksonManifest(); m[field] = 'forged';
    assert.throws(() => verifyBuildPluginJacksonDelta(acceptedE2GraphProjection(fixture()), seal(m)), /content digest mismatch/);
  });
}
for (const field of ['schemaVersion','repository','commitSha','sourceE2ContentSha256','baseE2GraphDigest','priorE2GraphDigest',
  'currentE2GraphDigest','manifestSha256','preservedServerGraphLineage','archivalCandidate','pluginCoordinateVersionChangeCount',
  'runtimeComponentChangeCount','runtimeEdgeChangeCount','inventory','findingReviewRequired','releaseBlocked']) {
  test(`plugin receipt rejects rehashed ${field}`, () => {
    const e = envelope(); e.e2GraphTransition[field] = 'forged'; e.e2GraphTransition = seal(e.e2GraphTransition);
    assert.throws(() => requirePreservedGraph(e, BASE_GRAPH, e.commitSha), /lineage mismatch/);
  });
}

test('plugin capture and manifest byte pins fail closed through the production reader', t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'plugin-jackson-pins-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const files = ['scripts/security/build-plugin-jackson-graph-transition.mjs', 'scripts/security/server-dependency-graph-transition.mjs',
    'docs/operations/build-plugin-jackson-transition.json', 'docs/operations/build-plugin-jackson-evidence/precommit-candidate-E2.json'];
  for (const file of files) { mkdirSync(dirname(resolve(directory,file)),{recursive:true});
    writeFileSync(resolve(directory,file),readFileSync(new URL(`../../${file}`,import.meta.url))); }
  writeFileSync(resolve(directory,'run.mjs'), "import {readBuildPluginJacksonManifest} from './scripts/security/build-plugin-jackson-graph-transition.mjs';readBuildPluginJacksonManifest();");
  const run = () => spawnSync(process.execPath,[resolve(directory,'run.mjs')],{encoding:'utf8'});
  assert.equal(run().status,0);
  for (const file of files.filter(f => f.endsWith('.json'))) {
    const path = resolve(directory,file),raw=readFileSync(path);
    writeFileSync(path,Buffer.concat([raw,Buffer.from('\n')]));
    const result = run(); assert.notEqual(result.status,0); assert.match(result.stderr,/byte digest mismatch/);
    writeFileSync(path,raw);
  }
});


test('retained actual OSV-only diagnostic covers all 533 current targets and preserves all 63 remaining records unchanged', () => {
  const { summary, coverage, findings } = readPluginOsvDiagnostic(fixture());
  const old = JSON.parse(readFileSync(new URL('../../docs/m6/m6-pr-e-e3-r4-osv-preparation.json', import.meta.url))).osv.findings;
  const previous = new Map(old.map(f => [f.findingId,f]));
  assert.equal(coverage.inputPackageCount, 533); assert.equal(coverage.reportedPackageCount, 533);
  assert.equal(findings.length, 63); assert.equal(old.length,70);
  for (const finding of findings) assert.deepEqual(finding, previous.get(finding.findingId));
  const current = new Set(findings.map(f => f.findingId));
  const removed = old.filter(f => !current.has(f.findingId));
  assert.equal(removed.length,7);
  assert.ok(removed.every(f => f.package.name.startsWith('tools.jackson.core:') && f.package.version === '3.1.5'
    && canonicalGraph(f.scopes) === '["build-plugin"]'));
  for (const name of ['jackson-core','jackson-databind']) {
    const target = coverage.targets.find(t => t.package.name === `tools.jackson.core:${name}` && t.package.version === '3.1.7');
    assert.deepEqual(target.advisoryIds,[]);
    assert.deepEqual(target.scopes,['build-plugin','compile']);
    assert.ok(target.componentRefs.includes(`maven-plugin:tools.jackson.core:${name}:jar:3.1.7`));
  }
  assert.equal(summary.allScannersCompleted,false); assert.equal(summary.graphAdmitted,false);
  assert.equal(summary.releaseBlocked,true); assert.equal(summary.newNaturalCiRequiredAfterFinalSourceFreeze,true);
});
