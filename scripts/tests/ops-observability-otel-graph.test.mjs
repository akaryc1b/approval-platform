import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { runInNewContext } from 'node:vm';
import test from 'node:test';
import { BASE_GRAPH, OBSERVABILITY_GRAPH, OBSERVABILITY_OTEL_GRAPH, canonicalGraph, graphHash,
  readOtelUpgradeManifest, verifyOtelUpgradeDelta, verifyObservabilityGraph, requirePreservedGraph }
  from '../security/observability-dependency-graph.mjs';
import { verifyPgjdbcRemediation } from '../security/m6-pr-e-e3-verify-pgjdbc-remediation.mjs';

// Synthetic dependency/scan fixtures test validation, not a new Maven or vulnerability scan.
const coordinate = version => `pkg:maven/io.opentelemetry/opentelemetry-api@${version}?type=jar`;
const component = version => ({ group: 'io.opentelemetry', name: 'opentelemetry-api', version,
  type: 'jar', bomRef: coordinate(version), source: `maven:io.opentelemetry:opentelemetry-api:${version}`,
  scope: 'compile', licenses: ['Apache-2.0'] });
const addedBom = { group: 'io.opentelemetry', name: 'opentelemetry-bom', scope: 'import', version: '1.62.0' };
function fixture() {
  const base = { maven: { components: [component('1.55.0'), { bomRef: 'other', scope: 'runtime', version: '1' }],
    edges: [{ from: coordinate('1.55.0'), to: 'other' }], importedBoms: ['existing'],
    resolvedPluginCoordinates: ['unchanged'], reactorRoots: ['same'] },
    pnpm: { version: 'fixed' }, githubActions: { pinned: true }, limitations: ['retained'] };
  const current = structuredClone(base); current.maven.components[0] = component('1.62.0');
  current.maven.edges[0].from = coordinate('1.62.0'); current.maven.importedBoms.push(addedBom);
  const manifest = { base: { graphDigest: graphHash(base) }, observed: { graphDigest: graphHash(current) },
    versionChanges: [{ group: 'io.opentelemetry', name: 'opentelemetry-api', fromVersion: '1.55.0', toVersion: '1.62.0' }],
    addedImportedBom: { index: 1, value: addedBom }, rewrittenEdgeCount: 1 };
  return { base, current, manifest };
}

test('pinned OTel upgrade records the two retained inputs, sixteen versions and seventeen edges', () => {
  const m = readOtelUpgradeManifest();
  assert.equal(m.base.graphDigest, OBSERVABILITY_GRAPH); assert.equal(m.observed.graphDigest, OBSERVABILITY_OTEL_GRAPH);
  assert.equal(m.base.runId, 34805063807); assert.equal(m.observed.runId, 34816893697);
  assert.equal(m.versionChanges.length, 16); assert.equal(m.rewrittenEdgeCount, 17);
  assert.equal(m.versionChanges.filter(c => c.group === 'io.opentelemetry' && c.fromVersion === '1.55.0' && c.toVersion === '1.62.0').length, 14);
  assert.deepEqual(m.versionChanges.filter(c => c.group !== 'io.opentelemetry'), [
    { group: 'com.squareup.okhttp3', name: 'okhttp-jvm', fromVersion: '5.2.1', toVersion: '5.3.2' },
    { group: 'com.squareup.okio', name: 'okio-jvm', fromVersion: '3.16.1', toVersion: '3.16.4' },
  ]);
  assert.deepEqual(m.addedImportedBom, { index: 1, value: addedBom });
  assert.equal(m.releaseBlocked, true);
  assert.equal(m.findingDisposition, 'UNRESOLVED_UNTIL_EXPLICIT_FINDING_REVIEW');
});
test('only the declared upgrade is reversed and neither the scanner input nor manifest is mutated', () => {
  const { base, current, manifest } = fixture(); const saved = canonicalGraph({ current, manifest });
  assert.deepEqual(verifyOtelUpgradeDelta(current, manifest), base);
  assert.equal(canonicalGraph({ current, manifest }), saved);
});
for (const [name, alter] of [
  ['unrelated version', p => { p.maven.components[1].version = '2'; }],
  ['scope', p => { p.maven.components[0].scope = 'test'; }],
  ['license', p => { p.maven.components[0].licenses = []; }],
  ['source', p => { p.maven.components[0].source = 'untrusted'; }],
  ['component', p => { p.maven.components.push({ bomRef: 'hidden' }); }],
  ['removed component', p => { p.maven.components.pop(); }],
  ['extra edge', p => { p.maven.edges.push({ from: 'other', to: 'other' }); }],
  ['edge endpoint', p => { p.maven.edges[0].to = 'untracked'; }],
  ['extra BOM', p => { p.maven.importedBoms.push('hidden'); }],
  ['BOM precedence', p => { p.maven.importedBoms.reverse(); }],
  ['plugin', p => { p.maven.resolvedPluginCoordinates = ['changed']; }],
  ['reactor', p => { p.maven.reactorRoots = ['changed']; }],
  ['pnpm', p => { p.pnpm.version = 'changed'; }],
  ['Actions', p => { p.githubActions.pinned = false; }],
  ['limitations', p => { p.limitations = []; }],
]) test(`an undeclared ${name} change is rejected even when the target hash is recomputed`, () => {
  const { current, manifest } = fixture(); alter(current); manifest.observed.graphDigest = graphHash(current);
  assert.throws(() => verifyOtelUpgradeDelta(current, manifest));
});
for (const [name, alter] of [
  ['component', p => p.maven.components.push({ ...p.maven.components[0] })],
  ['edge', p => p.maven.edges.push({ ...p.maven.edges[0] })],
  ['BOM', p => p.maven.importedBoms.push({ ...addedBom })],
]) test(`duplicate ${name} is rejected before reversal`, () => {
  const { current, manifest } = fixture(); alter(current); manifest.observed.graphDigest = graphHash(current);
  assert.throws(() => verifyOtelUpgradeDelta(current, manifest), /unique/);
});
for (const [name, alter] of [
  ['duplicate version mapping', m => m.versionChanges.push({ ...m.versionChanges[0] })],
  ['missing version mapping', m => { m.versionChanges = []; }],
  ['wrong old version', m => { m.versionChanges[0].fromVersion = '1.54.0'; }],
  ['wrong target version', m => { m.versionChanges[0].toVersion = '1.63.0'; }],
  ['unchanged version', m => { m.versionChanges[0].fromVersion = '1.62.0'; }],
  ['unexpected edge count', m => { m.rewrittenEdgeCount = 2; }],
  ['negative BOM index', m => { m.addedImportedBom.index = -1; }],
  ['fractional BOM index', m => { m.addedImportedBom.index = 1.5; }],
  ['wrong BOM identity', m => { m.addedImportedBom.value = { ...addedBom, version: '1.63.0' }; }],
]) test(`${name} cannot reconstruct the accepted graph`, () => {
  const { current, manifest } = fixture(); alter(manifest);
  assert.throws(() => verifyOtelUpgradeDelta(current, manifest));
});
test('the real upgrade manifest cannot be edited or supplemented without failing its byte pin', t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'otel-graph-manifest-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  mkdirSync(resolve(directory, 'scripts/security'), { recursive: true });
  mkdirSync(resolve(directory, 'docs/operations'), { recursive: true });
  const module = readFileSync(new URL('../security/observability-dependency-graph.mjs', import.meta.url), 'utf8');
  writeFileSync(resolve(directory, 'scripts/security/observability-dependency-graph.mjs'), module);
  writeFileSync(resolve(directory, 'run.mjs'), "import {readOtelUpgradeManifest} from './scripts/security/observability-dependency-graph.mjs'; readOtelUpgradeManifest();");
  const path = resolve(directory, 'docs/operations/observability-otel-upgrade.json');
  const raw = readFileSync(new URL('../../docs/operations/observability-otel-upgrade.json', import.meta.url), 'utf8');
  for (const text of [raw, raw + '\n', raw.replace('1.62.0', '1.63.0')]) {
    writeFileSync(path, text);
    const run = spawnSync(process.execPath, [resolve(directory, 'run.mjs')], { encoding: 'utf8', timeout: 3000 });
    if (text === raw) assert.equal(run.status, 0, run.stderr);
    else { assert.notEqual(run.status, 0); assert.match(run.stderr, /OTel dependency manifest drift/); }
  }
});

function lineageFixture() {
  const upgrade = readFileSync(new URL('../../docs/operations/observability-otel-upgrade.json', import.meta.url));
  // The pinned digest is checked by the production verifier, independently of this fixture.
  const payload = { schemaVersion: 'APPROVAL_OBSERVABILITY_GRAPH_LINEAGE_V2', repository: 'akaryc1b/approval-platform',
    commitSha: 'a'.repeat(40), sourceE2ContentSha256: 'b'.repeat(64), baseE2GraphDigest: BASE_GRAPH,
    intermediateE2GraphDigest: OBSERVABILITY_GRAPH, currentE2GraphDigest: OBSERVABILITY_OTEL_GRAPH,
    foundationManifestSha256: '8aa64be5a9db6a6c7988226e5386592b94b91b034c10c6ccb914c2a171a26993',
    manifestSha256: '20e7bf496628c36b5afb66d593b57424bb092d80666c41b7097849380a98dc86',
    versionChangeCount: 16, rewrittenEdgeCount: 17, addedImportedBomCount: 1, findingReviewRequired: true, releaseBlocked: true };
  assert.ok(upgrade.length > 0);
  const e4 = { repository: payload.repository, commitSha: payload.commitSha, contentSha256: 'c'.repeat(64),
    e2CurrentContentSha256: payload.sourceE2ContentSha256, e2GraphDigest: OBSERVABILITY_OTEL_GRAPH,
    e2GraphTransition: { ...payload, contentSha256: graphHash(payload) }, scanners: { osv: { scanCompleted: true, findings: [] } } };
  const plan = { repository: payload.repository, targetE2GraphDigest: BASE_GRAPH, dependencyOverride: { toVersion: '42.7.13' },
    remediatedFindings: [{ sourceClass: 'E4_OSV_SCANNER', findingId: 'fixed-id', upstreamFindingId: 'fixed-upstream',
      aliases: ['fixed-alias'], requiredAbsentFromCurrentScanner: true }] };
  return { e4, plan };
}
test('the unchanged pgjdbc proof accepts fully bound new graph lineage, without clearing release blockers', () => {
  const { e4, plan } = lineageFixture(); const result = verifyPgjdbcRemediation(e4, plan);
  assert.equal(result.currentE2GraphDigest, OBSERVABILITY_OTEL_GRAPH); assert.equal(result.releaseBlocked, true);
  assert.equal(result.subsequentGraphTransition.findingReviewRequired, true);
  assert.equal(result.schemaVersion, 'M6_PR_E_E3_R1_PGJDBC_REMEDIATION_EVIDENCE_V2');
  assert.equal(requirePreservedGraph({ e2GraphDigest: BASE_GRAPH }, BASE_GRAPH), null);
});
for (const key of ['commitSha', 'sourceE2ContentSha256', 'manifestSha256', 'foundationManifestSha256',
  'baseE2GraphDigest', 'intermediateE2GraphDigest', 'currentE2GraphDigest', 'versionChangeCount', 'releaseBlocked']) {
  test(`new lineage cannot substitute ${key} even after rehashing`, () => {
    const { e4, plan } = lineageFixture(); e4.e2GraphTransition[key] = 'tampered';
    const { contentSha256, ...payload } = e4.e2GraphTransition;
    e4.e2GraphTransition.contentSha256 = graphHash(payload);
    assert.throws(() => verifyPgjdbcRemediation(e4, plan), /lineage mismatch/);
  });
}
for (const [name, finding] of [['id', { sourceClass: 'E4_OSV_SCANNER', findingId: 'fixed-id' }],
  ['upstream', { upstreamFindingId: 'fixed-upstream' }], ['alias', { aliases: ['fixed-alias'] }]]) {
  test(`new OTel graph cannot conceal a reappearing pgjdbc ${name}`, () => {
    const { e4, plan } = lineageFixture(); e4.scanners.osv.findings.push(finding);
    assert.throws(() => verifyPgjdbcRemediation(e4, plan), /still present/);
  });
}
test('absent scanner, graph-only evidence and a foreign repository remain rejected', () => {
  const { e4, plan } = lineageFixture();
  assert.throws(() => verifyPgjdbcRemediation({ ...e4, scanners: {} }, plan), /must complete/);
  assert.throws(() => requirePreservedGraph({ ...e4, e2GraphTransition: undefined }, BASE_GRAPH), /lineage mismatch/);
  assert.throws(() => verifyPgjdbcRemediation({ ...e4, repository: 'another/repository' }, plan), /repository mismatch/);
});
test('E2 content and its supplied projection must agree before graph admission', () => {
  const { current } = fixture();
  const payload = { ...current, repository: 'akaryc1b/approval-platform', commitSha: 'a'.repeat(40) };
  const e2 = { ...payload, contentSha256: graphHash(payload) };
  assert.throws(() => verifyObservabilityGraph({ ...e2, contentSha256: '0'.repeat(64) }, current, BASE_GRAPH), /content digest/);
  assert.throws(() => verifyObservabilityGraph(e2, {}, BASE_GRAPH), /projection mismatch/);
  assert.throws(() => verifyObservabilityGraph(e2, current, BASE_GRAPH), /graph drift/);
});

const boundary = readFileSync(new URL('./m6-pr-e-e2-dependency-sbom-boundary.test.mjs', import.meta.url), 'utf8');
// Execute the actual CI-only assertion callback with controlled generator output, not Maven.
function assertGeneratedEvidence(alter = () => {}) {
  const boms = [
    { group: 'org.flowable', name: 'flowable-bom', scope: 'import', version: '8.0.0' }, addedBom,
    { group: 'org.springframework.boot', name: 'spring-boot-dependencies', scope: 'import', version: '4.0.2' },
    { group: 'org.testcontainers', name: 'testcontainers-bom', scope: 'import', version: '2.0.5' },
  ];
  const evidence = { commitSha: 'a'.repeat(40), contentSha256: 'b'.repeat(64),
    maven: { reactorProjectCount: 26, importedBoms: structuredClone(boms), components: Array.from({ length: 27 }, () => component('1.62.0')),
      edges: ['test'], resolvedPluginCoordinates: ['test'] },
    pnpm: { workspaceProjectCount: 6, external: [{ name: 'typescript', version: '5.9.3', license: 'Apache-2.0' }] },
    githubActions: { workflowCount: 9, automaticWorkflowCount: 1, maintenancePullRequests: [1, 2, 3, 4, 5] } };
  alter(evidence);
  const stdout = 'M6_PR_E_E2_SBOM_BEGIN\n' + JSON.stringify(evidence) + '\nM6_PR_E_E2_SBOM_END';
  const logs = []; let error;
  const section = boundary.slice(boundary.indexOf("test('E2 full generator"), boundary.lastIndexOf('\nimport '));
  const context = { assert, process: { env: { GITHUB_ACTIONS: 'true' }, execPath: process.execPath }, generator: 'generator.mjs', root: '/fixture',
    console: { log: text => logs.push(text) }, spawnSync: () => ({ status: 0, stdout, stderr: '' }),
    test: (name, options, callback) => { try { callback(); } catch (failure) { error = failure; } } };
  runInNewContext(section, context);
  return { error, logs, stdout };
}
test('the real E2 assertion accepts exact generator BOM order and retains output before assertions', () => {
  const r = assertGeneratedEvidence(); assert.equal(r.error, undefined); assert.equal(r.logs[1], r.stdout);
});
for (const [name, alter] of [
  ['missing BOM', e => e.maven.importedBoms.splice(1, 1)],
  ['additional BOM', e => e.maven.importedBoms.push({ ...addedBom, name: 'other-bom' })],
  ['duplicate BOM', e => e.maven.importedBoms.push({ ...addedBom })],
  ['wrong version', e => { e.maven.importedBoms[1].version = '1.55.0'; }],
  ['wrong scope', e => { e.maven.importedBoms[1].scope = 'compile'; }],
  ['wrong order', e => e.maven.importedBoms.reverse()],
  ['later count failure', e => { e.pnpm.workspaceProjectCount = 5; }],
]) test(`E2 still rejects ${name} and retains the generated evidence`, () => {
  const r = assertGeneratedEvidence(alter); assert.ok(r.error); assert.equal(r.logs[1], r.stdout);
});
