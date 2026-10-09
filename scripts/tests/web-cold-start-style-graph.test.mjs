import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import test from 'node:test';

const root = resolve(import.meta.dirname, '../..');
const upstream = join(root, '.upstream/vben');
const app = join(upstream, 'apps/web-ele');
const requireApp = createRequire(join(app, 'package.json'));
const requireRoot = createRequire(join(upstream, 'package.json'));
const requireVue = createRequire(requireApp.resolve('vue/package.json'));
const { parse, compileScript } = requireVue('@vue/compiler-sfc');
const { default: ElementPlus } = await import(pathToFileURL(requireApp.resolve('unplugin-element-plus/vite')));
const vite = await import(pathToFileURL(requireRoot.resolve('vite')));
const sourceConfig = join(root, 'apps/web/overlay/apps/web-ele/vite.config.ts');
const generatedConfig = join(app, 'vite.config.ts');
function retain(name, value) {
  const directory = process.env.APPROVAL_CAPTURE_GRAPH_EVIDENCE_DIR;
  if (!directory) return;
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  writeFileSync(join(directory, name), `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
}

// A stale bootstrap must not turn this into a test of different configuration.
assert.equal(readFileSync(generatedConfig, 'utf8'), readFileSync(sourceConfig, 'utf8'));
const governed = [
  'views/approval/workbench/index.vue',
  'components/approval/ApprovalFormRenderer.vue',
  'components/approval/ApprovalAssistancePanel.vue',
  'components/approval/ControlledAutomationConfirmationBoundary.vue',
];
const entries = [...readFileSync(sourceConfig, 'utf8').matchAll(/'(?<entry>element-plus\/es\/components\/[^']+\/style\/css)'/gu)].map(match => match.groups.entry);
const transform = ElementPlus({ format: 'esm' }).transform;
const applyTransform = typeof transform === 'function' ? transform : transform.handler;
async function emitted(source, filename) {
  const { descriptor, errors } = parse(source, { filename });
  assert.deepEqual(errors, []);
  const compiled = compileScript(descriptor, { id: filename, inlineTemplate: true });
  const result = await applyTransform.call({}, compiled.content, `${filename}?vue&type=script&setup=true&lang.ts`);
  return [...(result?.code ?? compiled.content).matchAll(/["'](element-plus\/es\/components\/[^"']+\/style\/css)["']/gu)].map(match => match[1]);
}
function assertCovered(imports, includes) {
  for (const entry of imports) assert.ok(includes.includes(entry), `missing transformed style: ${entry}`);
}
function cacheSnapshot() {
  const result = {};
  const walk = path => {
    if (!existsSync(path)) return;
    for (const name of readdirSync(path).sort()) {
      const file = join(path, name);
      const stat = lstatSync(file);
      if (stat.isDirectory()) walk(file);
      else if (stat.isFile()) result[relative(upstream, file)] = createHash('sha256').update(readFileSync(file)).digest('hex');
    }
  };
  for (const path of [join(upstream, 'node_modules/.vite'), join(app, 'node_modules/.vite'), join(app, '.vite')]) walk(path);
  return result;
}

await test('pinned post-transform workbench style imports exactly match production includes', async () => {
  assert.equal(JSON.parse(readFileSync(requireApp.resolve('element-plus/package.json'))).version, '2.14.0');
  // Resolve package.json from the transform entry because its exports do not expose package.json.
  const pluginPackage = join(dirname(dirname(requireApp.resolve('unplugin-element-plus/vite'))), 'package.json');
  assert.equal(JSON.parse(readFileSync(pluginPackage)).version, '0.11.2');
  assert.equal(vite.version, '8.0.13');
  const imports = [...new Set((await Promise.all(governed.map(file => {
    const path = join(root, 'apps/web/overlay/apps/web-ele/src', file);
    return emitted(readFileSync(path, 'utf8'), path);
  }))).flat())].sort();
  assert.equal(entries.length, 29);
  assert.deepEqual([...entries].sort(), imports);
  const elementPackagePath = requireApp.resolve('element-plus/package.json');
  const elementPackage = JSON.parse(readFileSync(elementPackagePath));
  // The pinned package deliberately exports these only under the ESM import
  // condition; CommonJS require.resolve is not the browser/Vite resolver.
  assert.equal(elementPackage.exports['./es/*'].import, './es/*.mjs');
  for (const entry of entries) {
    const suffix = entry.slice('element-plus/es/'.length);
    const exported = elementPackage.exports['./es/*'].import.replace('*', suffix);
    assert.ok(existsSync(join(dirname(elementPackagePath), exported)), entry);
  }
  assert.throws(() => assertCovered(imports, entries.slice(1)), /missing transformed style/u);
  const negative = `<script setup lang="ts">import { ElBadge } from 'element-plus';</script><template><ElBadge :value="1" /></template>`;
  const added = await emitted(negative, 'new-nested-component.vue');
  assert.ok(added.includes('element-plus/es/components/badge/style/css'));
  assert.throws(() => assertCovered([...imports, ...added], entries), /missing transformed style/u);
  retain('transform-graph.json', {
    vite: vite.version, elementPlus: elementPackage.version, unpluginElementPlus: '0.11.2',
    compiler: requireVue('@vue/compiler-sfc').version,
    configSha256: createHash('sha256').update(readFileSync(sourceConfig)).digest('hex'),
    entries: imports, negativeMissingEntryRejected: true, negativeAddedComponentRejected: true,
    sources: governed.map(file => ({ file, sha256: createHash('sha256').update(readFileSync(join(root, 'apps/web/overlay/apps/web-ele/src', file))).digest('hex') })),
  });
});

await test('actual merged config retains graph and isolated pinned optimizer leaves acceptance caches unchanged', async () => {
  const before = cacheSnapshot();
  const fixture = mkdtempSync(join(tmpdir(), 'approval-pc-style-optimizer-'));
  const previousCwd = process.cwd();
  let optimizerEvidence;
  try {
    process.chdir(app);
    const { default: configFactory } = await import(pathToFileURL(generatedConfig));
    const applicationConfig = await configFactory({ command: 'serve', mode: 'development' });
    assertCovered(entries, applicationConfig.optimizeDeps.include);
    symlinkSync(join(app, 'node_modules'), join(fixture, 'node_modules'), 'dir');
    writeFileSync(join(fixture, 'package.json'), '{"private":true,"type":"module"}\n');
    writeFileSync(join(fixture, 'index.html'), '<script type="module" src="/entry.js"></script>');
    writeFileSync(join(fixture, 'entry.js'), entries.map(entry => `import '${entry}';`).join('\n'));
    const config = await vite.resolveConfig({
      configFile: false, root: fixture, cacheDir: join(fixture, 'optimizer-cache'),
      optimizeDeps: { include: applicationConfig.optimizeDeps.include },
      logLevel: 'error',
    }, 'serve');
    const metadata = await vite.optimizeDeps(config);
    for (const entry of entries) {
      assert.ok(metadata.optimized[entry], `optimizer omitted ${entry}`);
      assert.ok(existsSync(metadata.optimized[entry].file));
      assert.ok(readFileSync(metadata.optimized[entry].file).length > 0);
    }
    const after = cacheSnapshot();
    assert.deepEqual(after, before, 'isolated optimizer modified an acceptance cache');
    optimizerEvidence = {
      vite: vite.version, fixtureRoot: fixture,
      configLoader: 'native import of generated config through normal postinstall-built Vben packages',
      vbenConfig: {
        resolvedPath: requireApp.resolve('@vben/vite-config'),
        sha256: createHash('sha256').update(readFileSync(requireApp.resolve('@vben/vite-config'))).digest('hex'),
      },
      acceptanceCacheBefore: before, acceptanceCacheAfter: after,
      entries: entries.map(id => ({
        id, source: relative(upstream, metadata.optimized[id].src),
        outputSha256: createHash('sha256').update(readFileSync(metadata.optimized[id].file)).digest('hex'),
      })),
    };
  } finally {
    process.chdir(previousCwd);
    rmSync(fixture, { recursive: true, force: true });
    assert.deepEqual(cacheSnapshot(), before, 'optimizer cleanup changed acceptance cache');
  }
  assert.equal(existsSync(fixture), false);
  retain('optimizer-fixture.json', { ...optimizerEvidence, fixtureDeleted: true });
});

await test('pinned shell icons register synchronously from exact local data without remote lookup', async () => {
  const beforeCache = cacheSnapshot();
  const source = join(root, 'apps/web/overlay/apps/web-ele/src/platform/approval/local-icons.ts');
  const generated = join(app, 'src/platform/approval/local-icons.ts');
  assert.equal(readFileSync(generated, 'utf8'), readFileSync(source, 'utf8'));
  const { approvalShellIcons, registerApprovalShellIcons } = await import(pathToFileURL(source));
  const requireDesign = createRequire(join(upstream, 'packages/@core/base/design/package.json'));
  const requireIcons = createRequire(join(upstream, 'packages/@core/base/icons/package.json'));
  const iconDataPackage = requireDesign.resolve('@iconify/json/package.json');
  const iconDataRoot = dirname(iconDataPackage);
  const runtimePackage = join(dirname(dirname(requireIcons.resolve('@iconify/vue'))), 'package.json');
  assert.equal(JSON.parse(readFileSync(iconDataPackage)).version, '2.2.476');
  assert.equal(JSON.parse(readFileSync(runtimePackage)).version, '5.0.1');
  const expectedNames = ['ep:expand', 'ep:fold', 'fluent-mdl2:world-clock', 'lucide:inbox', 'lucide:workflow'];
  const defaults = { left: 0, top: 0, width: 16, height: 16, rotate: 0, hFlip: false, vFlip: false };
  const pinned = {}; const dataFiles = new Map();
  for (const name of expectedNames) {
    const [prefix, icon] = name.split(':');
    const path = join(iconDataRoot, 'json', `${prefix}.json`);
    const data = readFileSync(path); const collection = JSON.parse(data);
    assert.ok(collection.icons[icon], `pinned icon missing: ${name}`);
    const inherited = Object.fromEntries(Object.keys(defaults).filter(key => key in collection).map(key => [key, collection[key]]));
    pinned[name] = { ...defaults, ...inherited, ...collection.icons[icon] };
    dataFiles.set(prefix, { file: relative(upstream, path), sha256: createHash('sha256').update(data).digest('hex'), license: collection.info.license });
  }
  const verify = definitions => assert.deepEqual(definitions, pinned);
  verify(approvalShellIcons);
  const missing = structuredClone(approvalShellIcons); delete missing['ep:fold'];
  assert.throws(() => verify(missing));
  const wrong = structuredClone(approvalShellIcons); wrong['lucide:workflow'].body = '<path d="M0 0"/>';
  assert.throws(() => verify(wrong));
  let fetchCalls = 0; const previousFetch = globalThis.fetch;
  let runtimeEvidence;
  try {
    globalThis.fetch = () => { fetchCalls += 1; throw new Error('Unexpected remote icon lookup'); };
    const runtimePath = join(dirname(runtimePackage), 'dist/iconify.mjs');
    const runtime = await import(pathToFileURL(runtimePath));
    assert.ok(expectedNames.every(name => !runtime.iconLoaded(name)), 'registration proof requires a fresh runtime');
    const apiBefore = JSON.stringify(runtime._api.getAPIConfig(''));
    const custom = { body: '<path d="M1 1"/>' };
    assert.equal(runtime.addIcon('approval-test:custom', custom), true);
    const originalCustom = runtime.getIcon('approval-test:custom');
    registerApprovalShellIcons(runtime.addIcon);
    for (const name of expectedNames) assert.deepEqual(runtime.getIcon(name), pinned[name]);
    assert.deepEqual(runtime.getIcon('approval-test:custom'), originalCustom);
    assert.equal(runtime.iconLoaded('approval-test:unknown'), false);
    assert.equal(JSON.stringify(runtime._api.getAPIConfig('')), apiBefore);
    const result = await new Promise((resolveLoad, reject) => {
      const timer = setTimeout(() => reject(new Error('Local icon callback did not complete')), 1000);
      runtime.loadIcons(expectedNames, (loaded, missing, pending) => {
        clearTimeout(timer); resolveLoad({ loaded: loaded.map(icon => `${icon.prefix}:${icon.name}`).sort(), missing, pending });
      });
    });
    assert.deepEqual(result, { loaded: expectedNames, missing: [], pending: [] });
    assert.equal(fetchCalls, 0);
    runtimeEvidence = { runtimeSha256: createHash('sha256').update(readFileSync(runtimePath)).digest('hex'), result, fetchCalls, apiConfigurationUnchanged: true, customIconUnchanged: true };
  } finally {
    globalThis.fetch = previousFetch;
    assert.deepEqual(cacheSnapshot(), beforeCache, 'icon proof modified acceptance caches');
  }
  retain('local-icons-registration.json', {
    dataVersion: '2.2.476', runtimeVersion: '5.0.1', names: expectedNames,
    sourceSha256: createHash('sha256').update(readFileSync(source)).digest('hex'),
    dataPackageSha256: createHash('sha256').update(readFileSync(iconDataPackage)).digest('hex'),
    dataFiles: Object.fromEntries(dataFiles), missingAndWrongDataRejected: true,
    ...runtimeEvidence, browserLaunched: false, acceptanceCacheBefore: beforeCache, acceptanceCacheAfter: cacheSnapshot(),
  });
});
