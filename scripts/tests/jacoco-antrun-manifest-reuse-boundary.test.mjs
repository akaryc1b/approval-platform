import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { runInNewContext } from 'node:vm';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const graphPath = 'scripts/security/observability-dependency-graph.mjs';
const pairPath = 'scripts/security/jacoco-antrun-graph-transition.mjs';
const source = readFileSync(join(root, graphPath), 'utf8');
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha = value => createHash('sha256').update(value).digest('hex');
function uniqueSlice(text, first, next) {
  assert.equal(text.split(first).length, 2, first); assert.equal(text.split(next).length, 2, next);
  const start = text.indexOf(first), end = text.indexOf(next); assert.ok(end > start);
  return text.slice(start, end);
}
function replaceOnce(text, anchor, replacement) {
  assert.equal(text.split(anchor).length, 2, anchor); return text.replace(anchor, replacement);
}
const pairBranch = uniqueSlice(source, '  if (digest === JACOCO_ANTRUN_GRAPH) {',
  '  if (digest === BOOT_UTILITIES_GRAPH) {');
const receiptBody = uniqueSlice(source, 'function jacocoAntrunReceipt(identity) {',
  '/** Graph admission is not finding disposition or production authorization. */');

// Run the exact branch and complete receipt body with tiny pinned collaborators.
// This tests reuse lifetime/control flow, not the real validators' data policy.
// Existing graph/callback gates and the real late-POM case below retain that role.
function smallFixture() {
  const manifest = { base: { sourceHead: 'b'.repeat(40), e2: { contentSha256: 'c'.repeat(64) } },
    capture: { sourceWitness: { rawSha256: 'd'.repeat(64) } }, inventory: { ownerCount: 17 },
    addedPluginCoordinateCount: 0, removedPluginCoordinateCount: 2 };
  const original = canonical(manifest), pin = sha(original);
  const e2 = { schemaVersion: 'M6_PR_E_E2_SBOM_V1', commitSha: 'a'.repeat(40), contentSha256: 'e'.repeat(64) };
  const projection = { graph: 'tiny-current' }, previous = { graph: 'tiny-previous' };
  const baseline = { commitSha: manifest.base.sourceHead }, preserved = { lineage: 'tiny-accepted' };
  const events = [], manifests = [], failure = new Error('synthetic helper refusal');
  let bytes = original, loadAttempts = 0, firstManifest, failAt, afterAncestor;
  const helper = (name, body) => (...args) => {
    events.push(name); if (failAt === name) throw failure; return body(...args);
  };
  const context = {
    REPOSITORY: 'akaryc1b/approval-platform', BASE_GRAPH: '0'.repeat(64),
    JACOCO_ANTRUN_GRAPH: '1'.repeat(64), BOOT_UTILITIES_GRAPH: '2'.repeat(64),
    JACOCO_ANTRUN_MANIFEST_SHA256: pin, canonicalGraph: canonical, graphHash: value => sha(canonical(value)),
    requireValue: (value, message) => { if (!value) throw new Error(message); },
    readJacocoAntrunManifest: helper('manifest', () => {
      loadAttempts++; assert.equal(sha(bytes), pin, 'tiny manifest byte pin');
      const loaded = JSON.parse(bytes); manifests.push(loaded); return loaded;
    }),
    verifyJacocoAntrunCurrentEvidence: helper('current', (value, loaded) => {
      assert.equal(value, e2); assert.equal(loaded, manifests.at(-1)); firstManifest = loaded;
    }),
    verifyJacocoAntrunDelta: helper('delta', (value, loaded) => {
      assert.equal(value, projection); assert.equal(loaded, firstManifest); return previous;
    }),
    readJacocoAntrunBaseline: helper('baseline', loaded => {
      assert.equal(loaded, firstManifest); return baseline;
    }),
    verifyObservabilityGraph: helper('ancestor', (value, prior, base, head) => {
      assert.equal(value, baseline); assert.equal(prior, previous);
      assert.equal(base, context.BASE_GRAPH); assert.equal(head, baseline.commitSha);
      if (afterAncestor) { const hook = afterAncestor; afterAncestor = undefined; hook(); }
    }),
    readJacocoAntrunPreservedBootUtilitiesLineage: helper('lineage', loaded => {
      assert.equal(loaded, manifests.at(-1)); assert.notEqual(loaded, firstManifest); return preserved;
    }),
    bootUtilitiesReceipt: helper('ancestor receipt', identity => {
      assert.equal(identity.commitSha, manifest.base.sourceHead);
      assert.equal(identity.contentSha256, manifest.base.e2.contentSha256); return preserved;
    }),
  };
  const invoke = runInNewContext(`${receiptBody}\n(e2, projection, expectedBaseDigest, expectedCommitSha) => {
    const digest = JACOCO_ANTRUN_GRAPH;
${pairBranch}\n}`, context);
  return { events, manifests, failure, e2, get loadAttempts() { return loadAttempts; },
    admit: () => invoke(e2, projection, context.BASE_GRAPH, e2.commitSha),
    mutate: () => { bytes += '\n'; }, restore: () => { bytes = original; },
    fail: name => { failAt = name; }, afterAncestor: hook => { afterAncestor = hook; } };
}

test('small reuse boundary shares one manifest only within a call and reloads on the next', () => {
  const f = smallFixture(), first = f.admit(); assert.equal(f.loadAttempts, 2);
  assert.deepEqual(f.events, ['manifest', 'current', 'delta', 'baseline', 'ancestor', 'manifest', 'lineage', 'ancestor receipt']);
  const second = f.admit(); assert.equal(f.loadAttempts, 4);
  assert.notEqual(f.manifests[0], f.manifests[2]); assert.notEqual(f.manifests[1], f.manifests[3]);
  assert.equal(canonical(first), canonical(second)); assert.equal(second.releaseBlocked, true);
});
test('small reuse boundary rejects between-call manifest edits and recovers after restoration', () => {
  const f = smallFixture(), first = f.admit(); f.mutate();
  assert.throws(f.admit, /tiny manifest byte pin/); assert.equal(f.loadAttempts, 3);
  f.restore(); assert.equal(canonical(f.admit()), canonical(first)); assert.equal(f.loadAttempts, 5);
});
test('small reuse boundary keeps the actual receipt fresh after recursive admission', () => {
  const f = smallFixture(); let hooks = 0, returned;
  f.afterAncestor(() => { hooks++; assert.equal(f.loadAttempts, 1); f.mutate(); });
  assert.throws(() => { returned = f.admit(); }, /tiny manifest byte pin/);
  assert.equal(hooks, 1); assert.equal(returned, undefined); assert.equal(f.loadAttempts, 2);
  assert.equal(f.events.includes('lineage'), false);
  f.restore(); assert.equal(f.admit().releaseBlocked, true); assert.equal(f.loadAttempts, 4); assert.equal(hooks, 1);
});
for (const name of ['current', 'delta', 'baseline']) {
  test(`small reuse boundary propagates ${name} refusal without caching failure or reaching a receipt`, () => {
    const f = smallFixture(); f.fail(name);
    assert.throws(f.admit, error => error === f.failure); assert.equal(f.loadAttempts, 1);
    assert.equal(f.events.includes('ancestor'), false); assert.equal(f.events.includes('lineage'), false);
    f.fail(undefined); assert.equal(f.admit().releaseBlocked, true); assert.equal(f.loadAttempts, 3);
  });
}

test('real reuse boundary rejects late POM mutation and admits freshly restored source', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'jacoco-antrun-compact-boundary-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  cpSync(root, directory, { recursive: true, filter: path => path !== join(root, '.git') });
  const pairFile = join(directory, pairPath), graphFile = join(directory, graphPath);
  writeFileSync(pairFile, replaceOnce(readFileSync(pairFile, 'utf8'),
    'export function readJacocoAntrunManifest() {',
    'export let manifestLoads = 0;\nexport function readJacocoAntrunManifest() {\n  manifestLoads++;'));
  writeFileSync(graphFile, replaceOnce(readFileSync(graphFile, 'utf8'), '    return jacocoAntrunReceipt(e2);',
    '    if (beforePairReceipt) { const hook = beforePairReceipt; beforePairReceipt = undefined; hook(); }\n'
      + '    return jacocoAntrunReceipt(e2);')
    + '\nlet beforePairReceipt;\nexport function beforeNextPairReceipt(hook) { beforePairReceipt = hook; }\n');
  const graph = await import(pathToFileURL(graphFile).href), pair = await import(pathToFileURL(pairFile).href);
  const manifest = JSON.parse(readFileSync(join(directory, 'docs/operations/jacoco-antrun-plugin-transition.json')));
  const e2 = JSON.parse(readFileSync(join(directory, manifest.capture.e2.path)));
  const projection = { maven: e2.maven, pnpm: e2.pnpm,
    githubActions: e2.githubActions.acceptedDependencyGraph.githubActions,
    limitations: e2.githubActions.acceptedDependencyGraph.limitations };
  const admit = () => graph.verifyObservabilityGraph(e2, projection, graph.BASE_GRAPH, e2.commitSha);
  const target = join(directory, 'pom.xml'), original = readFileSync(target);
  let hooks = 0, returned;
  graph.beforeNextPairReceipt(() => {
    hooks++; assert.equal(pair.manifestLoads, 1);
    writeFileSync(target, Buffer.concat([original, Buffer.from('\n')]));
  });
  try {
    assert.throws(() => { returned = admit(); }, /byte digest mismatch|historical source byte digest mismatch/);
    assert.equal(hooks, 1); assert.equal(pair.manifestLoads, 2); assert.equal(returned, undefined);
    assert.notDeepEqual(readFileSync(target), original);
  } finally { writeFileSync(target, original); }
  assert.deepEqual(readFileSync(target), original);
  const receipt = admit(); assert.equal(pair.manifestLoads, 4); assert.equal(hooks, 1);
  assert.equal(receipt.commitSha, e2.commitSha); assert.equal(receipt.sourceE2ContentSha256, e2.contentSha256);
  assert.equal(receipt.currentE2GraphDigest, graph.JACOCO_ANTRUN_GRAPH); assert.equal(receipt.releaseBlocked, true);
  const { contentSha256, ...payload } = receipt; assert.equal(contentSha256, graph.graphHash(payload));
});
