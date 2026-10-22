import assert from 'node:assert/strict';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
function replaceOnce(text, anchor, replacement) {
  assert.equal(text.split(anchor).length, 2, anchor);
  return text.replace(anchor, replacement);
}

test('legacy router reuse keeps one local manifest and a fresh receipt reread for all three branches', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'legacy-graph-manifest-reuse-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  cpSync(root, directory, { recursive: true, filter: path => path !== join(root, '.git') });
  const cases = [
    ['BootUtilities', 'boot-utilities', 'bootUtilitiesReceipt', 'BOOT_UTILITIES_GRAPH'],
    ['BootHttp', 'boot-http', 'bootHttpReceipt', 'BOOT_HTTP_GRAPH'],
    ['CheckstyleGuava', 'checkstyle-guava', 'checkstyleGuavaReceipt', 'CHECKSTYLE_GUAVA_GRAPH'],
  ];
  const graphFile = join(directory, 'scripts/security/observability-dependency-graph.mjs');
  let graphSource = readFileSync(graphFile, 'utf8');
  for (const [name, file, receipt] of cases) {
    const moduleFile = join(directory, `scripts/security/${file}-graph-transition.mjs`);
    const signature = `export function read${name}Manifest() {`;
    writeFileSync(moduleFile, replaceOnce(readFileSync(moduleFile, 'utf8'), signature,
      `export let manifestLoads = 0;\n${signature}\n  manifestLoads++;`));
    const call = `    return ${receipt}(e2);`;
    graphSource = replaceOnce(graphSource, call,
      `    const hook = beforeLegacyReceipt.get('${name}');\n`
      + `    if (hook) { beforeLegacyReceipt.delete('${name}'); hook(); }\n${call}`);
  }
  writeFileSync(graphFile, graphSource + '\nconst beforeLegacyReceipt = new Map();\n'
    + 'export function beforeNextLegacyReceipt(name, hook) { beforeLegacyReceipt.set(name, hook); }\n');
  const graph = await import(pathToFileURL(graphFile));
  const pom = join(directory, 'pom.xml'), original = readFileSync(pom);
  for (const [name, file, , digest] of cases) {
    const api = await import(pathToFileURL(join(directory, `scripts/security/${file}-graph-transition.mjs`)));
    const manifest = JSON.parse(readFileSync(join(directory,
      `docs/operations/${file}${file === 'checkstyle-guava' ? '' : '-plugin'}-transition.json`)));
    const e2 = JSON.parse(readFileSync(join(directory, manifest.capture.e2.path)));
    const projection = { maven: e2.maven, pnpm: e2.pnpm,
      githubActions: e2.githubActions.acceptedDependencyGraph.githubActions,
      limitations: e2.githubActions.acceptedDependencyGraph.limitations };
    const admit = () => graph.verifyObservabilityGraph(e2, projection, graph.BASE_GRAPH, e2.commitSha);
    const loads = api.manifestLoads; let hooks = 0, returned;
    graph.beforeNextLegacyReceipt(name, () => {
      hooks++; assert.equal(api.manifestLoads - loads, 1, `${name} local manifest reuse`);
      writeFileSync(pom, Buffer.concat([original, Buffer.from('\n')]));
    });
    try {
      assert.throws(() => { returned = admit(); }, /byte digest mismatch|historical source byte digest mismatch/, name);
      assert.equal(returned, undefined); assert.equal(hooks, 1);
      assert.equal(api.manifestLoads - loads, 2, `${name} fresh receipt recheck`);
    } finally { writeFileSync(pom, original); }
    const receipt = admit();
    assert.equal(api.manifestLoads - loads, 4, `${name} new admission cannot retain the prior manifest`);
    assert.equal(receipt.currentE2GraphDigest, graph[digest]);
    assert.equal(receipt.commitSha, e2.commitSha); assert.equal(receipt.sourceE2ContentSha256, e2.contentSha256);
    assert.equal(receipt.releaseBlocked, true);
    const { contentSha256, ...payload } = receipt;
    assert.equal(contentSha256, graph.graphHash(payload));
  }
});
