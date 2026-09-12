import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import test from 'node:test';
import { configureUnibestWot } from '../upstream/unibest-wot.mjs';

// Exact relevant stanza of pinned Unibest f05992eb. Plugin calls are substituted;
// this tests the configuration amendment, not a complete UniApp compilation.
const componentImport = "import UniComponents from '@uni-helper/vite-plugin-uni-components'";
const resolverImport = "import { WotResolver } from '@uni-helper/vite-plugin-uni-components/resolvers'";
const original = `${componentImport}
export const config = {
    plugins: [
      UniComponents({
        extensions: ['vue'],
        deep: true,
        directoryAsNamespace: false,
        dts: 'src/types/components.d.ts',
      }),
      Uni(),
    ],
};
`;

test('pinned component pipeline registers the existing Wot resolver before Uni', () => {
  const amended = configureUnibestWot(original);
  const calls = [];
  const resolver = { type: 'component', marker: 'existing-wot-resolver' };
  runInNewContext(amended.replace(componentImport, '').replace(resolverImport, '')
    .replace('export const config =', 'globalThis.config ='), {
    WotResolver: () => resolver,
    UniComponents: options => { calls.push({ kind: 'components', options }); },
    Uni: () => { calls.push({ kind: 'uni' }); },
  });
  assert.deepEqual(calls.map(call => call.kind), ['components', 'uni']);
  assert.equal(calls[0].options.resolvers.length, 1);
  assert.equal(calls[0].options.resolvers[0], resolver);
  assert.equal(calls[0].options.deep, true);
  assert.equal(calls[0].options.directoryAsNamespace, false);
  assert.equal(calls[0].options.dts, 'src/types/components.d.ts');
  assert.equal(amended.replace('\n' + resolverImport, '')
    .replace('        resolvers: [WotResolver()],\n', ''), original);
});

test('upstream drift and repeated amendments fail instead of silently dropping components', () => {
  for (const source of [null, '', original.replace(componentImport, ''),
    original.replace('UniComponents({', 'OtherComponents({'),
    original + componentImport, original + '      UniComponents({\n',
    original.replace('      Uni(),', ''),
    original.replace('      UniComponents({', '      Uni(),\n      UniComponents({'),
    configureUnibestWot(original)]) {
    assert.throws(() => configureUnibestWot(source), /PINNED_UNIBEST_COMPONENT_PIPELINE_CHANGED/u);
  }
});

test('normal and evaluation bootstraps both receive compiled components without dependency changes', () => {
  const bootstrap = readFileSync(new URL('../upstream/bootstrap-unibest.mjs', import.meta.url), 'utf8');
  assert.match(bootstrap, /import \{ configureUnibestWot \} from '\.\/unibest-wot\.mjs'/u);
  const amendedAt = bootstrap.indexOf('viteConfig = configureUnibestWot(viteConfig);');
  assert.ok(amendedAt > bootstrap.indexOf('viteConfig = viteConfig.replace(upstreamErudaBoundary, governedErudaBoundary);'));
  assert.ok(amendedAt < bootstrap.indexOf("await writeFile(viteConfigPath, viteConfig, 'utf8');"));
  assert.match(bootstrap, /'wot-design-uni': config\.wotDesignUniVersion/u);
  assert.match(bootstrap, /'reset', '--hard', config\.commit/u);
  assert.equal((bootstrap.match(/configureUnibestWot\(viteConfig\)/gu) || []).length, 1);
});
