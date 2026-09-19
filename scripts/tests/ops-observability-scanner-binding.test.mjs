import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import test from 'node:test';
import { BASE_GRAPH, OBSERVABILITY_GRAPH, OBSERVABILITY_OTEL_GRAPH, graphHash, requirePreservedGraph }
  from '../security/observability-dependency-graph.mjs';

// Synthetic scan envelope only. The production verifier checks both pinned manifests.
function lineageFixture() {
  const payload = { schemaVersion: 'APPROVAL_OBSERVABILITY_GRAPH_LINEAGE_V2', repository: 'akaryc1b/approval-platform',
    commitSha: 'a'.repeat(40), sourceE2ContentSha256: 'b'.repeat(64), baseE2GraphDigest: BASE_GRAPH,
    intermediateE2GraphDigest: OBSERVABILITY_GRAPH, currentE2GraphDigest: OBSERVABILITY_OTEL_GRAPH,
    foundationManifestSha256: '8aa64be5a9db6a6c7988226e5386592b94b91b034c10c6ccb914c2a171a26993',
    manifestSha256: '20e7bf496628c36b5afb66d593b57424bb092d80666c41b7097849380a98dc86',
    versionChangeCount: 16, rewrittenEdgeCount: 17, addedImportedBomCount: 1, findingReviewRequired: true, releaseBlocked: true };
  return { e4: { repository: payload.repository, commitSha: payload.commitSha,
    e2CurrentContentSha256: payload.sourceE2ContentSha256, e2GraphDigest: OBSERVABILITY_OTEL_GRAPH,
    e2GraphTransition: { ...payload, contentSha256: graphHash(payload) } } };
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
  const context = { assert, OBSERVABILITY_GRAPH, OBSERVABILITY_OTEL_GRAPH, requirePreservedGraph, NG: BASE_GRAPH,
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

test('the full-scanner callback binds the current OTel graph instead of its historical predecessor', () => {
  const r = assertScannerBinding(); assert.equal(r.error, r.downstream); assert.equal(r.calls, 2);
  assert.ok(scannerBoundary.includes("import { OBSERVABILITY_OTEL_GRAPH, requirePreservedGraph } from '../security/observability-dependency-graph.mjs';"));
  assert.deepEqual(r.logs, [r.stdout]);
});
for (const [name, alter] of [
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
