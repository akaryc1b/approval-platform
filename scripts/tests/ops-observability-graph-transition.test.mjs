import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { BASE_GRAPH, OBSERVABILITY_GRAPH, canonicalGraph, graphHash, readObservabilityManifest,
  verifyDependencyDelta, verifyObservabilityGraph, requirePreservedGraph }
  from '../security/observability-dependency-graph.mjs';

function fixture() {
  const base = { maven: { components: [{ bomRef: 'old', scope: 'test', version: '1' }],
    edges: [], importedBoms: ['unchanged'], resolvedPluginCoordinates: ['fixed'] },
  pnpm: { version: 'fixed' }, githubActions: { pinned: true }, limitations: ['retained'] };
  const current = structuredClone(base);
  current.maven.components[0].scope = 'runtime';
  current.maven.components.push({ bomRef: 'new', scope: 'runtime', version: '2' });
  current.maven.edges.push({ from: 'old', to: 'new' });
  const manifest = { base: { graphDigest: graphHash(base) }, observed: { graphDigest: graphHash(current) },
    addedComponents: ['new'], addedEdges: [{ from: 'old', to: 'new' }],
    scopeChanges: [{ bomRef: 'old', from: 'test', to: 'runtime' }] };
  return { base, current, manifest };
}

test('pinned real delta is 35 additions, 39 edges and one explicit scope change', () => {
  const manifest = readObservabilityManifest();
  assert.equal(manifest.base.graphDigest, BASE_GRAPH);
  assert.equal(manifest.observed.graphDigest, OBSERVABILITY_GRAPH);
  assert.equal(manifest.addedComponents.length, 35);
  assert.equal(manifest.addedEdges.length, 39);
  assert.deepEqual(manifest.scopeChanges, [{ bomRef: 'pkg:maven/org.jetbrains/annotations@17.0.0?type=jar',
    from: 'test', to: 'runtime' }]);
  assert.equal(manifest.releaseBlocked, true);
  assert.equal(manifest.findingDisposition, 'UNRESOLVED_UNTIL_EXPLICIT_FINDING_REVIEW');
  assert.ok(manifest.addedComponents.every(value => !value.includes('/postgresql@')));
});

test('delta reversal restores the entire prior graph without mutating inputs', () => {
  const { base, current, manifest } = fixture(); const before = canonicalGraph(current);
  assert.deepEqual(verifyDependencyDelta(current, manifest), base);
  assert.equal(canonicalGraph(current), before);
});

for (const [name, change] of [
  ['component version', v => { v.maven.components[0].version = 'different'; }],
  ['extra component', v => { v.maven.components.push({ bomRef: 'hidden', scope: 'runtime' }); }],
  ['deleted component', v => { v.maven.components.shift(); }],
  ['extra edge', v => { v.maven.edges.push({ from: 'old', to: 'old' }); }],
  ['BOM', v => { v.maven.importedBoms = ['changed']; }],
  ['build plugin', v => { v.maven.resolvedPluginCoordinates = ['changed']; }],
  ['pnpm', v => { v.pnpm.version = 'changed'; }],
  ['Actions', v => { v.githubActions.pinned = false; }],
  ['limitation', v => { v.limitations = []; }],
]) test(`undeclared ${name} change cannot be hidden by replacing only the target digest`, () => {
  const { current, manifest } = fixture(); change(current);
  manifest.observed.graphDigest = graphHash(current);
  assert.throws(() => verifyDependencyDelta(current, manifest));
});

for (const [name, change] of [
  ['component', v => { v.maven.components.push({ ...v.maven.components[0] }); }],
  ['edge', v => { v.maven.edges.push({ ...v.maven.edges[0] }); }],
]) test(`duplicate ${name} is rejected`, () => {
  const { current, manifest } = fixture(); change(current);
  manifest.observed.graphDigest = graphHash(current);
  assert.throws(() => verifyDependencyDelta(current, manifest), /unique/);
});

test('missing declared additions and wrong scope are rejected', () => {
  for (const mutate of [m => m.addedComponents.push('missing'),
    m => m.addedEdges.push({ from: 'missing', to: 'new' }), m => { m.scopeChanges[0].to = 'compile'; }]) {
    const { current, manifest } = fixture(); mutate(manifest);
    assert.throws(() => verifyDependencyDelta(current, manifest));
  }
});

test('historical pgjdbc graph behavior stays strict', () => {
  assert.equal(requirePreservedGraph({ e2GraphDigest: BASE_GRAPH }, BASE_GRAPH), null);
  assert.throws(() => requirePreservedGraph({ e2GraphDigest: 'a'.repeat(64) }, BASE_GRAPH), /graph mismatch/);
  assert.throws(() => requirePreservedGraph({ e2GraphDigest: BASE_GRAPH, e2GraphTransition: {} }, BASE_GRAPH));
});

test('target digest alone, foreign repository and unbound lineage do not preserve remediation', () => {
  const current = { repository: 'akaryc1b/approval-platform', commitSha: 'a'.repeat(40),
    e2CurrentContentSha256: 'b'.repeat(64), e2GraphDigest: OBSERVABILITY_GRAPH };
  assert.throws(() => requirePreservedGraph(current, BASE_GRAPH), /lineage mismatch/);
  assert.throws(() => requirePreservedGraph({ ...current, repository: 'foreign/repository' }, BASE_GRAPH));
  assert.throws(() => requirePreservedGraph({ ...current, e2GraphTransition: { releaseBlocked: false } }, BASE_GRAPH));
});

test('admission rejects malformed E2 evidence and unreviewed graph hashes', () => {
  const payload = { repository: 'akaryc1b/approval-platform', commitSha: 'a'.repeat(40) };
  const e2 = { ...payload, contentSha256: graphHash(payload) };
  assert.throws(() => verifyObservabilityGraph({ ...e2, contentSha256: '0'.repeat(64) }, {}, BASE_GRAPH), /content digest/);
  assert.throws(() => verifyObservabilityGraph(e2, {}, BASE_GRAPH), /E2 graph/);
});

test('scanner validates before tool installation and scans the full unmodified E2 input', () => {
  const scanner = readFileSync(new URL('../security/m6-pr-e-e4-scan.mjs', import.meta.url), 'utf8');
  assert.ok(scanner.indexOf('const graphTransition=verifyObservabilityGraph(') < scanner.indexOf("const goTar="));
  assert.ok(scanner.includes('const graphTransition=verifyObservabilityGraph('));
  assert.match(scanner, /osvInputFromE2\(e2\)/);
  assert.match(scanner, /e2GraphDigest:graphDigest/);
  assert.match(scanner, /e2GraphTransition:graphTransition/);
  assert.match(scanner, /workstreamReleaseBlocked:true/);
});

import { verifyPgjdbcRemediation } from '../security/m6-pr-e-e3-verify-pgjdbc-remediation.mjs';

// This fixture tests lineage consumption only; it does not represent a live vulnerability scan.
function remediationFixture() {
  const payload = { schemaVersion: 'APPROVAL_OBSERVABILITY_GRAPH_LINEAGE_V1',
    repository: 'akaryc1b/approval-platform', commitSha: 'a'.repeat(40), sourceE2ContentSha256: 'b'.repeat(64),
    baseE2GraphDigest: BASE_GRAPH, currentE2GraphDigest: OBSERVABILITY_GRAPH,
    manifestSha256: '8aa64be5a9db6a6c7988226e5386592b94b91b034c10c6ccb914c2a171a26993',
    addedComponentCount: 35, addedEdgeCount: 39, scopeChangeCount: 1, findingReviewRequired: true, releaseBlocked: true };
  const e4 = { repository: payload.repository, commitSha: payload.commitSha, contentSha256: 'c'.repeat(64),
    e2CurrentContentSha256: payload.sourceE2ContentSha256, e2GraphDigest: OBSERVABILITY_GRAPH,
    e2GraphTransition: { ...payload, contentSha256: graphHash(payload) },
    scanners: { osv: { scanCompleted: true, findings: [] } } };
  const plan = { repository: payload.repository, targetE2GraphDigest: BASE_GRAPH,
    dependencyOverride: { toVersion: '42.7.13' }, remediatedFindings: [{
      sourceClass: 'E4_OSV_SCANNER', findingId: 'fixed-id', upstreamFindingId: 'fixed-upstream',
      aliases: ['fixed-alias'], requiredAbsentFromCurrentScanner: true }] };
  return { e4, plan };
}

test('pgjdbc proof records both graph generations and remains release-blocked', () => {
  const { e4, plan } = remediationFixture(); const result = verifyPgjdbcRemediation(e4, plan);
  assert.equal(result.schemaVersion, 'M6_PR_E_E3_R1_PGJDBC_REMEDIATION_EVIDENCE_V2');
  assert.equal(result.currentE2GraphDigest, OBSERVABILITY_GRAPH);
  assert.equal(result.subsequentGraphTransition.baseE2GraphDigest, BASE_GRAPH);
  assert.equal(result.releaseBlocked, true);
  assert.equal(result.remediatedFindings.length, 1);
  const { contentSha256, ...payload } = result;
  assert.equal(graphHash(payload), contentSha256);
});

for (const key of ['commitSha', 'sourceE2ContentSha256', 'manifestSha256', 'baseE2GraphDigest', 'releaseBlocked']) {
  test(`lineage ${key} cannot be substituted even with a recomputed receipt hash`, () => {
    const { e4, plan } = remediationFixture(); e4.e2GraphTransition[key] = 'substituted';
    const { contentSha256, ...payload } = e4.e2GraphTransition;
    e4.e2GraphTransition.contentSha256 = graphHash(payload);
    assert.throws(() => verifyPgjdbcRemediation(e4, plan), /lineage mismatch/);
  });
}

for (const [name, finding] of [['id', { sourceClass: 'E4_OSV_SCANNER', findingId: 'fixed-id' }],
  ['upstream', { upstreamFindingId: 'fixed-upstream' }], ['alias', { aliases: ['fixed-alias'] }]]) {
  test(`graph extension cannot hide reappearing pgjdbc ${name}`, () => {
    const { e4, plan } = remediationFixture(); e4.scanners.osv.findings = [finding];
    assert.throws(() => verifyPgjdbcRemediation(e4, plan), /still present/);
  });
}
