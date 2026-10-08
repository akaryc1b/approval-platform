import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { BASE_GRAPH, OBSERVABILITY_GRAPH, OBSERVABILITY_OTEL_GRAPH, SERVER_DEPENDENCY_GRAPH,
  canonicalGraph, graphHash, readObservabilityManifest, readOtelUpgradeManifest,
  readServerDependencyManifest, readServerDependencySourceWitness, verifyDependencyDelta,
  verifyOtelUpgradeDelta, verifyServerDependencyDelta, verifyObservabilityGraph, requirePreservedGraph }
  from '../security/observability-dependency-graph.mjs';
import { verifyPgjdbcRemediation } from '../security/m6-pr-e-e3-verify-pgjdbc-remediation.mjs';

const repository = 'akaryc1b/approval-platform';
const archivedPath = 'docs/operations/server-dependency-evidence-921d5ec5/M6_PR_E_E2_SBOM.json';
const archived = JSON.parse(readFileSync(new URL(`../../${archivedPath}`, import.meta.url)));
const projection = e2 => ({ maven: e2.maven, pnpm: e2.pnpm,
  githubActions: e2.githubActions.acceptedDependencyGraph.githubActions,
  limitations: e2.githubActions.acceptedDependencyGraph.limitations });
const resign = value => { const { contentSha256, ...payload } = value; value.contentSha256 = graphHash(payload); return value; };
const current = () => structuredClone(archived);
function admit(e2 = current(), head = e2.commitSha) {
  return verifyObservabilityGraph(e2, projection(e2), BASE_GRAPH, head);
}
function scannerFixture() {
  // Synthetic current envelope tests binding only; it is not an E4 scan result.
  const e2 = current(); e2.commitSha = 'b'.repeat(40); resign(e2);
  return { repository, commitSha: e2.commitSha, e2GraphDigest: SERVER_DEPENDENCY_GRAPH,
    e2CurrentContentSha256: e2.contentSha256, e2GraphTransition: admit(e2) };
}

test('authentic captured E2 reverses every declared type into the unchanged historical chain', () => {
  const e2 = current(), manifest = readServerDependencyManifest(), input = canonicalGraph({ e2, manifest });
  const previous = verifyServerDependencyDelta(projection(e2), manifest);
  assert.equal(graphHash(previous), OBSERVABILITY_OTEL_GRAPH);
  const foundation = verifyOtelUpgradeDelta(previous, readOtelUpgradeManifest());
  assert.equal(graphHash(foundation), OBSERVABILITY_GRAPH);
  assert.equal(graphHash(verifyDependencyDelta(foundation, readObservabilityManifest())), BASE_GRAPH);
  assert.equal(canonicalGraph({ e2, manifest }), input);
  assert.equal(manifest.componentVersionChanges.length, 90);
  assert.equal(manifest.edgeRewrites.length, 180);
  assert.equal(manifest.pluginCoordinateVersionChanges.length, 13);
  assert.equal(manifest.scopeChangeCount, 0); assert.equal(manifest.licenseChangeCount, 0);
  assert.equal(manifest.inventory.unknownLicenseComponentCount, 78);
  assert.equal(e2.maven.components.find(c => c.group === 'tools.jackson.core' && c.name === 'jackson-core').version, '3.1.7');
  assert.ok(e2.maven.resolvedPluginCoordinates.includes('tools.jackson.core:jackson-core:jar:3.1.5'));
  assert.equal(previous.maven.pluginResolutionSha256, '4accac2e7bdf0765b2fea4a4b43b437e0f4a536ffa3047183107dc77c1dd14f1');
  assert.equal(e2.maven.pluginResolutionSha256, 'e9a095fee4fdbf3293833511950288c1932fbafc492799ffd3fa7468bd02b072');
});

test('the pinned source witness binds exact archival source and clean completed captures', () => {
  const witness = readServerDependencySourceWitness();
  assert.equal(witness.sourceTransition.candidateHead, archived.commitSha);
  assert.equal(witness.archivalE2.contentSha256, archived.contentSha256);
  assert.equal(witness.sourceTransition.files.length, 7);
  assert.equal(witness.captureInvocations.length, 3);
  assert.equal(witness.releaseBlocked, true); assert.equal(witness.findingReviewRequired, true);
});

test('future exact-head evidence is accepted without relabelling the immutable source witness', () => {
  const e4 = scannerFixture(), receipt = requirePreservedGraph(e4, BASE_GRAPH, e4.commitSha);
  assert.equal(receipt.commitSha, 'b'.repeat(40));
  assert.equal(receipt.sourceE2ContentSha256, e4.e2CurrentContentSha256);
  assert.notEqual(receipt.sourceE2ContentSha256, archived.contentSha256);
  assert.equal(receipt.archivalSourceHead, archived.commitSha);
  assert.equal(receipt.archivalE2ContentSha256, archived.contentSha256);
  assert.equal(receipt.findingReviewRequired, true); assert.equal(receipt.releaseBlocked, true);
  assert.equal(receipt.inventory.componentCount, 237);
  assert.equal(receipt.inventory.edgeCount, 345);
  assert.equal(receipt.inventory.reactorRootCount, 26);
  assert.equal(receipt.inventory.resolvedPluginCoordinateCount, 329);
  assert.equal(receipt.inventory.importedBomCount, 6);
});

test('historical graphs retain three-argument E2 and old receipt compatibility', () => {
  let p = verifyServerDependencyDelta(projection(current()));
  for (const expected of [OBSERVABILITY_OTEL_GRAPH, OBSERVABILITY_GRAPH, BASE_GRAPH]) {
    const e2 = current(); e2.maven = p.maven; e2.pnpm = p.pnpm;
    e2.githubActions.acceptedDependencyGraph.githubActions = p.githubActions;
    e2.githubActions.acceptedDependencyGraph.limitations = p.limitations; resign(e2);
    const receipt = verifyObservabilityGraph(e2, p, BASE_GRAPH);
    if (expected === BASE_GRAPH) assert.equal(receipt, null);
    else {
      assert.equal(receipt.currentE2GraphDigest, expected);
      assert.equal(requirePreservedGraph({ repository, commitSha: e2.commitSha,
        e2CurrentContentSha256: e2.contentSha256, e2GraphDigest: expected, e2GraphTransition: receipt }, BASE_GRAPH).currentE2GraphDigest, expected);
    }
    if (expected === OBSERVABILITY_OTEL_GRAPH) p = verifyOtelUpgradeDelta(p, readOtelUpgradeManifest());
    else if (expected === OBSERVABILITY_GRAPH) p = verifyDependencyDelta(p, readObservabilityManifest());
  }
});

for (const [name, alter] of [
  ['component scope', e => { e.maven.components[0].scope = 'test'; }],
  ['component license', e => { e.maven.components[0].licenses = ['Apache-2.0']; }],
  ['component source', e => { e.maven.components[0].source = 'forged'; }],
  ['unknown component field', e => { e.maven.components[0].unknown = true; }],
  ['unknown component addition', e => { e.maven.components.push({ ...e.maven.components[0], bomRef: 'unknown' }); }],
  ['missing component', e => { e.maven.components.pop(); }],
  ['duplicate component', e => { e.maven.components.push({ ...e.maven.components[0] }); }],
  ['component order', e => { e.maven.components.reverse(); }],
  ['edge endpoint', e => { e.maven.edges[0].to += '-unknown'; }],
  ['extra edge', e => { e.maven.edges.push({ from: 'unknown', to: 'unknown' }); }],
  ['duplicate edge', e => { e.maven.edges.push({ ...e.maven.edges[0] }); }],
  ['missing edge', e => { e.maven.edges.pop(); }],
  ['edge order', e => { e.maven.edges.reverse(); }],
  ['BOM precedence', e => { e.maven.importedBoms.reverse(); }],
  ['BOM addition', e => { e.maven.importedBoms.push({ ...e.maven.importedBoms[0], name: 'unknown' }); }],
  ['duplicate BOM', e => { e.maven.importedBoms.push({ ...e.maven.importedBoms[0] }); }],
  ['BOM scope', e => { e.maven.importedBoms[1].scope = 'compile'; }],
  ['missing BOM', e => { e.maven.importedBoms.splice(1, 1); }],
  ['plugin raw hash', e => { e.maven.pluginResolutionSha256 = '0'.repeat(64); }],
  ['plugin Jackson conflation', e => { e.maven.resolvedPluginCoordinates = e.maven.resolvedPluginCoordinates.map(v => v.replace('tools.jackson.core:jackson-core:jar:3.1.5', 'tools.jackson.core:jackson-core:jar:3.1.7')); }],
  ['plugin order', e => { e.maven.resolvedPluginCoordinates.reverse(); }],
  ['plugin addition', e => { e.maven.resolvedPluginCoordinates.push('unknown:unknown:jar:1'); }],
  ['missing plugin', e => { e.maven.resolvedPluginCoordinates.pop(); }],
  ['reactor root', e => { e.maven.reactorRoots.pop(); }],
  ['reactor count', e => { e.maven.reactorProjectCount = 25; }],
  ['pnpm', e => { e.pnpm.workspaceProjectCount = 7; }],
  ['accepted Actions', e => { e.githubActions.acceptedDependencyGraph.githubActions.workflowCount++; }],
  ['limitation', e => { e.githubActions.acceptedDependencyGraph.limitations.pop(); }],
  ['unknown Maven field', e => { e.maven.unknown = true; }],
]) test(`public graph admission rejects ${name} after canonical E2 rehashing`, () => {
  const e2 = current(); alter(e2); resign(e2);
  assert.throws(() => admit(e2), /graph drift/);
});

for (const [name, alter] of [
  ['schema', m => { m.schemaVersion = 'FORGED'; }],
  ['repository', m => { m.repository = 'foreign/repository'; }],
  ['base graph', m => { m.base.graphDigest = SERVER_DEPENDENCY_GRAPH; }],
  ['observed graph', m => { m.observed.graphDigest = BASE_GRAPH; }],
  ['source head', m => { m.observed.sourceHead = 'b'.repeat(40); }],
  ['source tree', m => { m.observed.sourceTree = 'b'.repeat(40); }],
  ['archival E2 identity', m => { m.observed.e2ContentSha256 = 'b'.repeat(64); }],
  ['source witness', m => { m.sourceWitness.rawSha256 = 'b'.repeat(64); }],
  ['duplicate component declaration', m => { m.componentVersionChanges.push(m.componentVersionChanges[0]); }],
  ['missing component declaration', m => { m.componentVersionChanges.pop(); }],
  ['duplicate edge declaration', m => { m.edgeRewrites.push(m.edgeRewrites[0]); }],
  ['missing edge declaration', m => { m.edgeRewrites.pop(); }],
  ['duplicate plugin declaration', m => { m.pluginCoordinateVersionChanges.push(m.pluginCoordinateVersionChanges[0]); }],
  ['missing plugin declaration', m => { m.pluginCoordinateVersionChanges.pop(); }],
  ['duplicate BOM declaration', m => { m.addedImportedBoms.push(m.addedImportedBoms[0]); }],
  ['missing BOM declaration', m => { m.addedImportedBoms.pop(); }],
  ['BOM order declaration', m => { m.addedImportedBoms.reverse(); }],
  ['BOM version declaration', m => { m.importedBomVersionChanges[0].fromVersion = '4.0.1'; }],
  ['component count', m => { m.componentVersionChangeCount = 89; }],
  ['edge count', m => { m.rewrittenEdgeCount = 179; }],
  ['plugin count', m => { m.pluginCoordinateVersionChangeCount = 12; }],
  ['BOM count', m => { m.addedImportedBomCount = 1; }],
  ['inventory count', m => { m.inventory.unknownLicenseComponentCount = 0; }],
  ['scope change', m => { m.scopeChangeCount = 1; }],
  ['license change', m => { m.licenseChangeCount = 1; }],
  ['release block', m => { m.releaseBlocked = false; }],
  ['finding review', m => { m.findingReviewRequired = false; }],
  ['unknown field', m => { m.genericReplacement = current().maven; }],
]) test(`public typed verifier rejects a forged ${name} manifest even after rehashing`, () => {
  const m = readServerDependencyManifest(); alter(m); resign(m);
  assert.throws(() => verifyServerDependencyDelta(projection(current()), m), /manifest content digest mismatch/);
});

test('current E2 content, projection, schema and independently verified head are mandatory', () => {
  const e2 = current();
  assert.throws(() => verifyObservabilityGraph(e2, projection(e2), BASE_GRAPH), /requires current verified head/);
  assert.throws(() => admit(e2, 'b'.repeat(40)), /current verified head mismatch/);
  assert.throws(() => admit(e2, 'short'), /current verified head mismatch/);
  assert.throws(() => admit({ ...e2, contentSha256: '0'.repeat(64) }), /content digest mismatch/);
  assert.throws(() => admit(resign({ ...e2, repository: 'foreign/repository' })), /identity mismatch/);
  assert.throws(() => admit(resign({ ...e2, schemaVersion: 'FORGED' })), /requires current verified head and schema/);
  assert.throws(() => verifyObservabilityGraph(e2, {}, BASE_GRAPH, e2.commitSha), /projection mismatch/);
  assert.throws(() => verifyObservabilityGraph(e2, projection(e2), SERVER_DEPENDENCY_GRAPH, e2.commitSha), /unrecognized dependency graph baseline/);
  const unknown = current(); unknown.maven.unknown = true; resign(unknown);
  assert.throws(() => verifyObservabilityGraph(unknown, projection(unknown), graphHash(projection(unknown)), unknown.commitSha), /unrecognized dependency graph baseline/);
  assert.throws(() => requirePreservedGraph({ e2GraphDigest: 'f'.repeat(64) }, 'f'.repeat(64)), /unrecognized preserved/);
});

for (const key of ['schemaVersion', 'repository', 'commitSha', 'sourceE2ContentSha256', 'baseE2GraphDigest',
  'intermediateE2GraphDigest', 'priorE2GraphDigest', 'currentE2GraphDigest', 'foundationManifestSha256',
  'otelManifestSha256', 'manifestSha256', 'sourceWitnessSha256', 'archivalSourceHead', 'archivalSourceTree',
  'archivalE2ContentSha256', 'componentVersionChangeCount', 'rewrittenEdgeCount', 'addedImportedBomCount',
  'importedBomVersionChangeCount', 'pluginCoordinateVersionChangeCount', 'scopeChangeCount', 'licenseChangeCount',
  'inventory', 'findingReviewRequired', 'releaseBlocked']) {
  test(`receipt validation rejects forged ${key} despite a recomputed receipt digest`, () => {
    const e4 = scannerFixture(); e4.e2GraphTransition[key] = 'forged'; resign(e4.e2GraphTransition);
    assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH, e4.commitSha), /lineage mismatch/);
  });
}

test('missing, extra, stale-head and hash-invalid receipts cannot preserve historical remediation', () => {
  const e4 = scannerFixture();
  for (const alter of [v => { delete v.e2GraphTransition; },
    v => { v.e2GraphTransition.contentSha256 = '0'.repeat(64); },
    v => { v.e2GraphTransition.unknown = true; resign(v.e2GraphTransition); },
    v => { v.e2CurrentContentSha256 = archived.contentSha256; },
    v => { v.commitSha = archived.commitSha; }]) {
    const altered = structuredClone(e4); alter(altered);
    assert.throws(() => requirePreservedGraph(altered, BASE_GRAPH, e4.commitSha), /lineage mismatch|current verified head mismatch/);
  }
  assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH, archived.commitSha), /current verified head mismatch/);
});

test('server graph admission cannot replace a completed scan or suppress a reappearing pgjdbc finding', () => {
  const e4 = scannerFixture();
  const options = { expectedCommitSha: e4.commitSha };
  const plan = { repository, targetE2GraphDigest: BASE_GRAPH,
    remediatedFindings: [{ sourceClass: 'E4_OSV_SCANNER', findingId: 'fixed-id',
      upstreamFindingId: 'fixed-upstream', aliases: ['fixed-alias'], requiredAbsentFromCurrentScanner: true }] };
  assert.throws(() => requirePreservedGraph(e4, BASE_GRAPH), /receipt requires current verified head/);
  assert.throws(() => verifyPgjdbcRemediation(e4, plan), /receipt requires current verified head/);
  assert.throws(() => verifyPgjdbcRemediation(e4, plan, {}), /receipt requires current verified head/);
  assert.throws(() => verifyPgjdbcRemediation(e4, plan, { expectedCommitSha: archived.commitSha }), /current verified head mismatch/);
  assert.throws(() => verifyPgjdbcRemediation(e4, plan, options), /OSV scanner must complete/);
  // Synthetic scan replies exercise the separate remediation boundary, not actual absence evidence.
  e4.scanners = { osv: { scanCompleted: true, findings: [] } };
  const result = verifyPgjdbcRemediation(e4, plan, options);
  assert.equal(result.releaseBlocked, true);
  assert.equal(result.subsequentGraphTransition.findingReviewRequired, true);
  for (const finding of [{ sourceClass: 'E4_OSV_SCANNER', findingId: 'fixed-id' },
    { upstreamFindingId: 'fixed-upstream' }, { aliases: ['fixed-alias'] }]) {
    e4.scanners.osv.findings = [finding];
    assert.throws(() => verifyPgjdbcRemediation(e4, plan, options), /still present/);
  }
});

test('production entrypoint rejects edited manifest, source witness or archival E2 files', t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'server-dependency-pins-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const files = ['scripts/security/build-plugin-jackson-graph-transition.mjs', 'scripts/security/observability-dependency-graph.mjs', 'scripts/security/server-dependency-graph-transition.mjs',
    'docs/operations/observability-dependency-transition.json', 'docs/operations/observability-otel-upgrade.json',
    'docs/operations/server-dependency-transition.json', 'docs/operations/server-dependency-source-witness.json', archivedPath];
  const original = new Map();
  for (const file of files) {
    const raw = readFileSync(new URL(`../../${file}`, import.meta.url)); original.set(file, raw);
    mkdirSync(dirname(resolve(directory, file)), { recursive: true }); writeFileSync(resolve(directory, file), raw);
  }
  writeFileSync(resolve(directory, 'current-e2.json'), JSON.stringify(current()));
  writeFileSync(resolve(directory, 'run.mjs'), `import fs from 'node:fs';
import { verifyObservabilityGraph, BASE_GRAPH } from './scripts/security/observability-dependency-graph.mjs';
const e = JSON.parse(fs.readFileSync(new URL('./current-e2.json', import.meta.url)));
const p = { maven: e.maven, pnpm: e.pnpm, githubActions: e.githubActions.acceptedDependencyGraph.githubActions, limitations: e.githubActions.acceptedDependencyGraph.limitations };
verifyObservabilityGraph(e, p, BASE_GRAPH, e.commitSha);`);
  const run = () => spawnSync(process.execPath, [resolve(directory, 'run.mjs')], { encoding: 'utf8', timeout: 5000 });
  const baseline = run(); assert.equal(baseline.status, 0, baseline.stderr);
  for (const file of files.filter(file => file.endsWith('.json'))) {
    const path = resolve(directory, file);
    for (const raw of [Buffer.concat([original.get(file), Buffer.from('\n')]),
      Buffer.from(JSON.stringify(resign({ ...JSON.parse(original.get(file)), repository: 'foreign/repository' })))]) {
      writeFileSync(path, raw); const result = run();
      assert.notEqual(result.status, 0, `tampered ${file} accepted`);
      assert.match(result.stderr, /digest mismatch|manifest drift/);
    }
    writeFileSync(path, original.get(file));
  }
});
