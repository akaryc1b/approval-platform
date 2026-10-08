import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import test from 'node:test';
import { BASE_GRAPH, OBSERVABILITY_GRAPH, OBSERVABILITY_OTEL_GRAPH, SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, graphHash, verifyObservabilityGraph, requirePreservedGraph }
  from '../security/observability-dependency-graph.mjs';

// Synthetic scan envelope only. The production verifier checks both pinned manifests.
function lineageFixture() {
  const e2 = JSON.parse(readFileSync(new URL('../../docs/operations/site-dependency-plugin-evidence/precommit-candidate-E2.json', import.meta.url)));
  e2.commitSha = 'a'.repeat(40); const { contentSha256, ...payload } = e2; e2.contentSha256 = graphHash(payload);
  const projection = { maven: e2.maven, pnpm: e2.pnpm,
    githubActions: e2.githubActions.acceptedDependencyGraph.githubActions,
    limitations: e2.githubActions.acceptedDependencyGraph.limitations };
  return { e4: { repository: e2.repository, commitSha: e2.commitSha,
    e2CurrentContentSha256: e2.contentSha256, e2GraphDigest: SITE_DEPENDENCY_PLUGIN_GRAPH,
    e2GraphTransition: verifyObservabilityGraph(e2, projection, BASE_GRAPH, e2.commitSha) } };
}

const scannerBoundary = readFileSync(new URL('./m6-pr-e-e4-scanner-boundary.test.mjs', import.meta.url), 'utf8');
// Exercise the actual CI callback up to its first downstream git read. Only the
// scanner subprocess is a fixture; the graph/lineage verifier and manifests are real.
// Reaching that boundary is NOT a completed scanner or downstream triage result.
function assertScannerBinding(alter = () => {}, scannerStatus = 0) {
  const { e4 } = lineageFixture();
  Object.assign(e4, { allScannersCompleted: true, rawScannerReportsRetained: false,
    candidateSecretMaterialRetained: false });
  alter(e4);
  const stdout = 'M6_PR_E_E4_SCANNER_EVIDENCE_BEGIN\n' + JSON.stringify(e4) + '\nM6_PR_E_E4_SCANNER_EVIDENCE_END';
  const logs = []; const downstream = new Error('DOWNSTREAM_BOUNDARY_REACHED'); let error; let calls = 0;
  const start = scannerBoundary.indexOf("test('E4 full scanner emits");
  assert.ok(start >= 0);
  const context = { assert, expectedScannerHead: () => 'a'.repeat(40), OBSERVABILITY_GRAPH, OBSERVABILITY_OTEL_GRAPH, SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, requirePreservedGraph, NG: BASE_GRAPH,
    process: { env: { GITHUB_ACTIONS: 'true' }, execPath: process.execPath }, S: 'scanner.mjs', root: '/fixture',
    console: { log: text => logs.push(text) },
    spawnSync: (command, args) => {
      calls++;
      if (calls === 1) {
        assert.equal(command, process.execPath); assert.equal(args[0], 'scanner.mjs');
        return { status: scannerStatus, stdout, stderr: '' };
      }
      assert.equal(command, 'git'); assert.equal(args[0], 'show'); throw downstream;
    },
    test: (name, options, callback) => { try { callback(); } catch (failure) { error = failure; } },
  };
  runInNewContext(scannerBoundary.slice(start), context);
  return { error, downstream, calls, logs, stdout };
}

test('the full-scanner callback binds the current Site/Dependency plugin graph instead of historical predecessors', () => {
  const r = assertScannerBinding(); assert.equal(r.error, r.downstream); assert.equal(r.calls, 2);
  assert.ok(scannerBoundary.includes("import { SITE_DEPENDENCY_PLUGIN_GRAPH, requirePreservedGraph } from '../security/observability-dependency-graph.mjs';"));
  assert.deepEqual(r.logs, [r.stdout]);
});
for (const [name, alter] of [
  ['pre-Site Jackson graph', e => { e.e2GraphDigest = BUILD_PLUGIN_JACKSON_GRAPH; }],
  ['pre-plugin-remediation server graph', e => { e.e2GraphDigest = SERVER_DEPENDENCY_GRAPH; }],
  ['pre-upgrade OTel graph', e => { e.e2GraphDigest = OBSERVABILITY_OTEL_GRAPH; }],
  ['pre-upgrade graph', e => { e.e2GraphDigest = OBSERVABILITY_GRAPH; }],
  ['foundation graph', e => { e.e2GraphDigest = BASE_GRAPH; }],
  ['unknown graph', e => { e.e2GraphDigest = '0'.repeat(64); }],
  ['missing lineage', e => { delete e.e2GraphTransition; }],
  ['foreign repository', e => { e.repository = 'foreign/repository'; }],
  ['wrong Head', e => { e.commitSha = 'd'.repeat(40); }],
  ['wrong E2 identity', e => { e.e2CurrentContentSha256 = 'd'.repeat(64); }],
  ['waived release block', e => { e.e2GraphTransition.releaseBlocked = false; }],
  ['incomplete scan', e => { e.allScannersCompleted = false; }],
  ['raw report retained', e => { e.rawScannerReportsRetained = true; }],
  ['candidate secret retained', e => { e.candidateSecretMaterialRetained = true; }],
]) test(`the full-scanner callback rejects ${name} before downstream review and retains output`, () => {
  const r = assertScannerBinding(alter);
  assert.ok(r.error); assert.notEqual(r.error, r.downstream); assert.equal(r.calls, 1);
  assert.deepEqual(r.logs, [r.stdout]);
});
test('a failed scanner cannot reach downstream review even with a well-formed output fixture', () => {
  const r = assertScannerBinding(() => {}, 1);
  assert.ok(r.error); assert.notEqual(r.error, r.downstream); assert.equal(r.calls, 1);
});
