import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync as historicalGit } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { resolveGitHubActionsEvidence } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { requireGitleaksCaptureInventory } from '../security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
import { modeledLangDigestFixture } from './fixtures/lang-digest-review-fixture.mjs';
import { RESOURCES_FLATTEN_GRAPH, readResourcesFlattenCandidate, readResourcesFlattenAcceptedLineage }
  from '../security/resources-flatten-graph-transition.mjs';
import { BASE_GRAPH, OBSERVABILITY_GRAPH, OBSERVABILITY_OTEL_GRAPH, SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH, CHECKSTYLE_PLUGIN_GRAPH, CHECKSTYLE_GUAVA_GRAPH, JACOCO_ANTRUN_GRAPH, ASSEMBLY_PLEXUS_GRAPH, JAR_PLUGIN_GRAPH, ASSEMBLY_ENFORCER_LANG_GRAPH, graphHash, verifyObservabilityGraph, requirePreservedGraph }
  from '../security/observability-dependency-graph.mjs';

// Synthetic scan envelope only. The production verifier checks all pinned lineage manifests.
// Each mutation receives an isolated copy of a fully validated synthetic input.
// The extracted callback still invokes the real preserved-graph gate every time.
const retainedLineageFixtures = new Map();
const resourcesFlattenTestHead = 'a'.repeat(40);
let currentLangPublication;
function lineageFixture(plugin = 'assembly-enforcer-lang') {
  if (!retainedLineageFixtures.has(plugin)) retainedLineageFixtures.set(plugin, buildLineageFixture(plugin));
  return structuredClone(retainedLineageFixtures.get(plugin));
}
function buildLineageFixture(plugin) {
  if (plugin === 'resources-flatten') {
    const { contentSha256, ...captured } = readResourcesFlattenCandidate();
    const payload = { ...captured, commitSha: resourcesFlattenTestHead };
    const e2 = { ...payload, contentSha256: graphHash(payload) };
    const projection = { maven: e2.maven, pnpm: e2.pnpm,
      githubActions: e2.githubActions.acceptedDependencyGraph.githubActions,
      limitations: e2.githubActions.acceptedDependencyGraph.limitations };
    // Partial SYNTHETIC envelope for the first callback boundary only. No OSV
    // result, absence, actual finding total or completed triage is supplied.
    return { e4: { repository: e2.repository, commitSha: resourcesFlattenTestHead,
      e2CurrentContentSha256: e2.contentSha256, e2GraphDigest: RESOURCES_FLATTEN_GRAPH,
      e2GraphTransition: verifyObservabilityGraph(e2, projection, BASE_GRAPH, resourcesFlattenTestHead),
      scanners: { gitleaks: { findingCount: 1, findings: [{ findingId: 'synthetic-unit-only-unreviewed' }] } } } };
  }
  currentLangPublication ||= modeledLangDigestFixture('Lang scanner binding publication');
  const evidence = ['checkstyle-guava', 'assembly-plexus', 'assembly-enforcer-lang'].includes(plugin) ? plugin : plugin + '-plugin';
  const e2 = JSON.parse(readFileSync(new URL(`../../docs/operations/${evidence}-evidence/candidate-E2.json`, import.meta.url)));
  if (plugin === 'assembly-enforcer-lang') e2.githubActions = resolveGitHubActionsEvidence(fileURLToPath(new URL('../../', import.meta.url)));
  e2.commitSha = currentLangPublication.head; const { contentSha256, ...payload } = e2; e2.contentSha256 = graphHash(payload);
  const projection = { maven: e2.maven, pnpm: e2.pnpm,
    githubActions: e2.githubActions.acceptedDependencyGraph.githubActions,
    limitations: e2.githubActions.acceptedDependencyGraph.limitations };
  let e4 = { repository: e2.repository, commitSha: e2.commitSha,
    e2CurrentContentSha256: e2.contentSha256, e2GraphDigest: graphHash(projection),
    scanners: { gitleaks: { findingCount: plugin === 'jar' ? 194 : 70 } },
    e2GraphTransition: verifyObservabilityGraph(e2, projection, BASE_GRAPH, e2.commitSha) };
  if (plugin === 'assembly-enforcer-lang') e4 = currentLangPublication.prepareEvidence(e4);
  return { e4 };
}

const scannerBoundary = readFileSync(new URL('./m6-pr-e-e4-scanner-boundary.test.mjs', import.meta.url), 'utf8');
let acceptedLangScannerBoundary;
function historicalLangScannerBoundary() {
  if (!acceptedLangScannerBoundary) {
    const result = historicalGit('git', ['show', '905cd30fde430972da58203a61136d50a28f81e8:scripts/tests/m6-pr-e-e4-scanner-boundary.test.mjs'],
      { cwd: fileURLToPath(new URL('../../', import.meta.url)), encoding: 'utf8', timeout: 10000, maxBuffer: 4 * 1024 * 1024 });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(createHash('sha256').update(result.stdout).digest('hex'),
      '217cadb5a20f547773c1f9e006546f8a53663973dade6d13b20ec19a19383d98');
    acceptedLangScannerBoundary = result.stdout;
  }
  return acceptedLangScannerBoundary;
}
// Exercise the actual CI callback up to its first downstream git read. Only the
// scanner subprocess is a fixture; the graph/lineage verifier and manifests are real.
// Reaching that boundary is NOT a completed scanner or downstream triage result.
function assertScannerBinding(alter = () => {}, scannerStatus = 0, plugin = 'assembly-enforcer-lang') {
  const { e4 } = lineageFixture(plugin);
  const currentResourcesFlatten = plugin === 'resources-flatten';
  const source = currentResourcesFlatten ? scannerBoundary : historicalLangScannerBoundary();
  const expectedHead = currentResourcesFlatten ? resourcesFlattenTestHead : e4.commitSha;
  Object.assign(e4, { allScannersCompleted: true, rawScannerReportsRetained: false,
    candidateSecretMaterialRetained: false });
  alter(e4);
  const stdout = 'M6_PR_E_E4_SCANNER_EVIDENCE_BEGIN\n' + JSON.stringify(e4) + '\nM6_PR_E_E4_SCANNER_EVIDENCE_END';
  const logs = []; const downstream = new Error('DOWNSTREAM_BOUNDARY_REACHED'); let error; let calls = 0;
  const start = source.indexOf("test('E4 full scanner emits");
  assert.ok(start >= 0);
  const context = { assert, requireGitleaksCaptureInventory, expectedScannerHead: () => expectedHead, OBSERVABILITY_GRAPH, OBSERVABILITY_OTEL_GRAPH, SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH, CHECKSTYLE_PLUGIN_GRAPH, CHECKSTYLE_GUAVA_GRAPH, JACOCO_ANTRUN_GRAPH, ASSEMBLY_PLEXUS_GRAPH, JAR_PLUGIN_GRAPH, ASSEMBLY_ENFORCER_LANG_GRAPH, RESOURCES_FLATTEN_GRAPH, requirePreservedGraph, NG: BASE_GRAPH,
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
  runInNewContext(source.slice(start), context);
  return { error, downstream, calls, logs, stdout };
}

test('the accepted main905 callback binds historical Lang lineage and all 374 modeled Gitleaks findings before downstream review', () => {
  const r = assertScannerBinding(); assert.equal(r.error, r.downstream); assert.equal(r.calls, 2);
  const { e4 } = lineageFixture();
  assert.equal(e4.e2GraphDigest, ASSEMBLY_ENFORCER_LANG_GRAPH);
  assert.equal(e4.scanners.gitleaks.findingCount, 374);
  assert.equal(e4.scanners.gitleaks.findings.length, 374);
  assert.equal(new Set(e4.scanners.gitleaks.findings.map(finding => finding.findingId)).size, 374);
  assert.equal(e4.e2GraphTransition.preservedJarLineage.currentE2GraphDigest, JAR_PLUGIN_GRAPH);
  assert.equal(e4.e2GraphTransition.preservedJarLineage.commitSha, '9e3489f2aef3ffb36508367591bc0d77ceb99bd1');
  assert.ok(historicalLangScannerBoundary().includes("import { ASSEMBLY_ENFORCER_LANG_GRAPH, requirePreservedGraph } from '../security/observability-dependency-graph.mjs';"));
  assert.deepEqual(r.logs, [r.stdout]);
});
for (const [name, alter] of [
  ['previous JAR graph with preserved historical lineage', e => { Object.assign(e, lineageFixture('jar').e4); }],
  ['pre-JAR Assembly graph with preserved historical lineage', e => { Object.assign(e, lineageFixture('assembly-plexus').e4); }],
  ['pre-Assembly JaCoCo/AntRun graph with preserved historical lineage', e => { Object.assign(e, lineageFixture('jacoco-antrun').e4); }],
  ['pre-pair Boot utilities graph with preserved historical lineage', e => { Object.assign(e, lineageFixture('boot-utilities').e4); }],
  ['pre-utilities Boot HTTP graph with preserved historical lineage', e => { Object.assign(e, lineageFixture('boot-http').e4); }],
  ['pre-Boot Guava graph with preserved historical lineage', e => { Object.assign(e, lineageFixture('checkstyle-guava').e4); }],
  ['pre-Guava Checkstyle graph with preserved historical lineage', e => { Object.assign(e, lineageFixture('checkstyle').e4); }],
  ['pre-Checkstyle Compiler graph with preserved historical lineage', e => { Object.assign(e, lineageFixture('compiler').e4); }],
  ['pre-Compiler Clean graph', e => { e.e2GraphDigest = CLEAN_PLUGIN_GRAPH; }],
  ['pre-Clean Release graph', e => { e.e2GraphDigest = RELEASE_PLUGIN_GRAPH; }],
  ['pre-Release Site graph', e => { e.e2GraphDigest = SITE_DEPENDENCY_PLUGIN_GRAPH; }],
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
  assert.notEqual(r.error.name, 'ReferenceError', 'missing VM binding must not count as a graph rejection');
  assert.notEqual(r.error.name, 'SyntaxError', 'invalid callback must not count as a graph rejection');
  assert.doesNotMatch(String(r.error), /requires the exact Lang graph and all 374 observations/,
    'inventory rejection must not hide a failed upstream mutation check');
  assert.deepEqual(r.logs, [r.stdout]);
});
for (const [name, count] of [['previous JAR inventory', 194], ['missing cache observations', 205],
  ['missing runtime observations', 363], ['one missing observation', 373], ['unknown extra observation', 375]])
test(`the accepted main905 Lang callback rejects ${name} before any downstream review`, () => {
  const r = assertScannerBinding(e => { e.scanners.gitleaks.findingCount = count; });
  assert.match(String(r.error), /requires the exact Lang graph and all 374 observations/);
  assert.equal(r.calls, 1); assert.deepEqual(r.logs, [r.stdout]);
});
for (const [name, count] of [
  ['missing execution digest observations', 70],
  ['missing fixture public-Git observation', 193],
  ['missing verifier public-Git observations', 192],
  ['missing all public source observations', 191],
]) test(`historical JAR inventory rejects ${name} without relying on the accepted main905 Lang graph gate`, () => {
  const { e4 } = lineageFixture('jar');
  assert.equal(requireGitleaksCaptureInventory(e4), 194);
  e4.scanners.gitleaks.findingCount = count;
  assert.throws(() => requireGitleaksCaptureInventory(e4), /current JAR graph requires all 194 Gitleaks observations/);
});
test('a failed scanner cannot reach downstream review even with a well-formed output fixture', () => {
  const r = assertScannerBinding(() => {}, 1);
  assert.ok(r.error); assert.notEqual(r.error, r.downstream); assert.equal(r.calls, 1);
});

test('the current callback binds Resources/Flatten before its first downstream read without claiming scanner results', () => {
  const r = assertScannerBinding(() => {}, 0, 'resources-flatten');
  assert.equal(r.error, r.downstream); assert.equal(r.calls, 2);
  const { e4 } = lineageFixture('resources-flatten');
  assert.equal(e4.e2GraphDigest, RESOURCES_FLATTEN_GRAPH);
  assert.deepEqual(e4.e2GraphTransition.preservedLangLineage, readResourcesFlattenAcceptedLineage());
  assert.equal(e4.e2GraphTransition.releaseBlocked, true);
  assert.equal(e4.scanners.osv, undefined);
  assert.ok(scannerBoundary.includes("import { RESOURCES_FLATTEN_GRAPH, requirePreservedGraph } from '../security/observability-dependency-graph.mjs';"));
  assert.deepEqual(r.logs, [r.stdout]);
});
for (const [name, alter] of [
  ['prior Lang graph', e => { e.e2GraphDigest = ASSEMBLY_ENFORCER_LANG_GRAPH; }],
  ['prior JAR graph', e => { e.e2GraphDigest = JAR_PLUGIN_GRAPH; }],
  ['unknown graph', e => { e.e2GraphDigest = '0'.repeat(64); }],
  ['missing lineage', e => { delete e.e2GraphTransition; }],
  ['foreign repository', e => { e.repository = 'foreign/repository'; }],
  ['wrong independent head', e => { e.commitSha = 'd'.repeat(40); }],
  ['wrong E2 identity', e => { e.e2CurrentContentSha256 = 'd'.repeat(64); }],
  ['rehash of waived release block', e => {
    e.e2GraphTransition.releaseBlocked = false;
    const { contentSha256, ...payload } = e.e2GraphTransition;
    e.e2GraphTransition.contentSha256 = graphHash(payload);
  }],
  ['incomplete scanner envelope', e => { e.allScannersCompleted = false; }],
  ['raw report retained', e => { e.rawScannerReportsRetained = true; }],
  ['candidate secret retained', e => { e.candidateSecretMaterialRetained = true; }],
]) test(`the current Resources/Flatten callback rejects ${name} before downstream review`, () => {
  const r = assertScannerBinding(alter, 0, 'resources-flatten');
  assert.ok(r.error); assert.notEqual(r.error, r.downstream); assert.equal(r.calls, 1);
  assert.notEqual(r.error.name, 'ReferenceError'); assert.notEqual(r.error.name, 'SyntaxError');
  assert.deepEqual(r.logs, [r.stdout]);
});
for (const count of [194, 374]) test(`Resources/Flatten cannot inherit the historical ${count}-finding inventory`, () => {
  const r = assertScannerBinding(e => { e.scanners.gitleaks.findingCount = count; }, 0, 'resources-flatten');
  assert.match(String(r.error), /current execution-digest inventory requires the reviewed JAR graph and all 194 observations/);
  assert.equal(r.calls, 1); assert.deepEqual(r.logs, [r.stdout]);
});
test('a failed current Resources/Flatten scanner cannot reach downstream review', () => {
  const r = assertScannerBinding(() => {}, 1, 'resources-flatten');
  assert.ok(r.error); assert.notEqual(r.error, r.downstream); assert.equal(r.calls, 1);
  assert.deepEqual(r.logs, []);
});

test('natural transport boundary registers each Checkstyle and historical Compiler gate exactly once', () => {
  const source = readFileSync(new URL('./m6-ai-transport-review-boundary.test.mjs', import.meta.url), 'utf8');
  for (const plugin of ['checkstyle', 'compiler']) {
    for (const gate of ['baseline', 'graph-transition', 'osv-diagnostic', 'live-compatibility']) {
      const registration = `import './${plugin}-plugin-${gate}.test.mjs';`;
      assert.equal(source.split(registration).length - 1, 1, registration);
    }
  }
  for (const gate of ['baseline', 'live-compatibility'])
    assert.equal(source.split(`import './clean-plugin-${gate}.test.mjs';`).length - 1, 1);
});


test('natural transport boundary registers each JAR gate exactly once', () => {
  const source = readFileSync(new URL('./m6-ai-transport-review-boundary.test.mjs', import.meta.url), 'utf8');
  for (const gate of ['graph-transition', 'source-continuation', 'osv-diagnostic', 'compatibility-harness', 'live-compatibility', 'receipt-retention']) {
    const registration = `import './jar-plugin-${gate}.test.mjs';`;
    assert.equal(source.split(registration).length - 1, 1, registration);
  }
});
