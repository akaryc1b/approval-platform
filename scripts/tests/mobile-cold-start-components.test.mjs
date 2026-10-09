import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { applyUnibestCompatibility } from '../upstream/unibest-compatibility.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const upstream = join(root, '.upstream/unibest');
const requireUpstream = createRequire(join(upstream, 'package.json'));
// Resolve the SFC compiler through the actual application Vue package, not a lint dependency.
const requireVue = createRequire(requireUpstream.resolve('vue/package.json'));
const { parse, compileScript } = requireVue('@vue/compiler-sfc');
const governed = {
  'pages/task/list.vue': ['button', 'search', 'tag'],
  'pages/task/detail.vue': ['button', 'tag', 'textarea'],
  'components/approval/ApprovalFormRenderer.vue': ['button', 'input'],
  'components/approval/ApprovalAssistancePanel.vue': ['button', 'tag'],
  'components/approval/ControlledAutomationConfirmationBoundary.vue': ['button', 'tag'],
};
const evidenceDirectory = process.env.APPROVAL_COLD_START_EVIDENCE_DIR;

function compile(source, filename) {
  const parsed = parse(source, { filename });
  assert.deepEqual(parsed.errors, [], filename);
  return compileScript(parsed.descriptor, { id: filename, inlineTemplate: true });
}

// A fresh process and the pinned upstream base-file generator reproduce the initial
// pages state. No generated easycom configuration or development server is used.
test('governed H5 components compile with an empty cold-start easycom cache', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'approval-cold-components-'));
  try {
    mkdirSync(join(fixture, 'scripts'));
    copyFileSync(join(upstream, 'scripts/create-base-files.js'), join(fixture, 'scripts/create-base-files.mjs'));
    execFileSync(process.execPath, [join(fixture, 'scripts/create-base-files.mjs')]);
    const inputDir = join(fixture, 'src');
    const pages = JSON.parse(readFileSync(join(inputDir, 'pages.json'), 'utf8'));
    assert.equal(pages.easycom, undefined);
    process.env.UNI_INPUT_DIR = inputDir;
    process.env.UNI_PLATFORM = 'h5';
    process.env.UNI_CLI_CONTEXT = upstream;
    const { initEasycoms, matchEasycom, parsePagesJsonOnce } = requireUpstream('@dcloudio/uni-cli-shared');
    assert.equal(parsePagesJsonOnce(inputDir, 'h5').easycom, undefined);
    initEasycoms(inputDir, { dirs: [], platform: 'h5' });
    const { uniEasycomPlugin } = requireUpstream('@dcloudio/uni-h5-vite/dist/plugins/easycom.js');
    const easycom = uniEasycomPlugin({});
    const transform = (code, file) => easycom.transform(code, file)?.code ?? code;

    for (const [file, names] of Object.entries(governed)) {
      const filename = join(root, 'apps/mobile/overlay/src', file);
      const source = readFileSync(filename, 'utf8');
      const compiled = compile(source, filename);
      const output = transform(compiled.content, filename);
      assert.doesNotMatch(output, /_resolveComponent\(["']wd-/u, file);
      for (const name of names) {
        const binding = `Wd${name[0].toUpperCase()}${name.slice(1)}`;
        const imported = compiled.imports[binding];
        assert.equal(Boolean(matchEasycom(`wd-${name}`)), false, `fixture must not resolve wd-${name}`);
        assert.equal(imported?.source, `wot-design-uni/components/wd-${name}/wd-${name}.vue`, file);
        assert.match(output, new RegExp(`_create(?:VNode|Block)\\(${binding}[,)]`), `${file}: ${binding}`);
        const withoutImport = source.replace(new RegExp(`^import ${binding} from '[^']+'\\r?\\n`, 'mu'), '');
        assert.notEqual(withoutImport, source);
        const negative = transform(compile(withoutImport, filename).content, filename);
        assert.match(negative, new RegExp(`_resolveComponent\\("wd-${name}"\\)`),
          `${file}: removing ${binding} must reproduce unresolved cold-start output`);
      }
      if (evidenceDirectory) {
        const target = join(evidenceDirectory, `${file}.compiled.ts`);
        mkdirSync(dirname(target), { recursive: true });
        writeFileSync(target, output);
      }
    }
    assert.equal(parsePagesJsonOnce(inputDir, 'h5').easycom, undefined);
    assert.equal(JSON.parse(readFileSync(join(inputDir, 'pages.json'), 'utf8')).easycom, undefined);
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('pinned Wot imports bring their nested icons and component styles', () => {
  const config = JSON.parse(readFileSync(join(root, 'apps/mobile/upstream.json'), 'utf8'));
  const wotPackage = requireUpstream.resolve('wot-design-uni/package.json');
  assert.equal(JSON.parse(readFileSync(wotPackage, 'utf8')).version, config.wotDesignUniVersion);
  for (const name of new Set(Object.values(governed).flat())) {
    const file = join(dirname(wotPackage), `components/wd-${name}/wd-${name}.vue`);
    const source = readFileSync(file, 'utf8');
    const compiled = compile(source, file);
    assert.equal(compiled.imports.wdIcon?.source, '../wd-icon/wd-icon.vue', file);
    assert.match(compiled.content, /_create(?:VNode|Block)\(wdIcon[,)]/u, file);
    assert.doesNotMatch(compiled.content, /_resolveComponent\(["']wd-icon/u, file);
    const { descriptor } = parse(source, { filename: file });
    assert.ok(descriptor.styles.some(style => /@import ['"]\.\/index\.scss['"]/u.test(style.content)), file);
    assert.ok(readFileSync(join(dirname(file), 'index.scss'), 'utf8').length > 0);
  }
});

test('task-list tag states use the pinned Wot vocabulary', () => {
  const { runInNewContext } = requireUpstream('node:vm');
  const { stripTypeScriptTypes } = requireUpstream('node:module');
  const wotRoot = dirname(requireUpstream.resolve('wot-design-uni/package.json'));
  const declaration = readFileSync(join(wotRoot, 'components/wd-tag/types.ts'), 'utf8');
  const union = declaration.match(/export type TagType = ([^\n]+)/u)?.[1];
  assert.ok(union);
  const supported = new Set([...union.matchAll(/'([^']+)'/gu)].map(match => match[1]));
  for (const file of Object.keys(governed)) {
    const source = readFileSync(join(root, 'apps/mobile/overlay/src', file), 'utf8');
    for (const match of source.matchAll(/<wd-tag\b[^>]*\stype="([^"]+)"/gu)) {
      assert.ok(supported.has(match[1]), `${file}: unsupported tag type ${match[1]}`);
    }
  }
  const list = readFileSync(join(root, 'apps/mobile/overlay/src/pages/task/list.vue'), 'utf8');
  const source = list.slice(list.indexOf('function statusTone('), list.indexOf('function formatMoney('));
  const statusTone = runInNewContext(`${stripTypeScriptTypes(source)}\nstatusTone`);
  for (const status of ['COMPLETED', 'REJECTED', 'RUNNING', 'WITHDRAWN']) {
    assert.ok(supported.has(statusTone(status)), status);
  }
  assert.equal(statusTone('REJECTED'), 'default');
  assert.equal(statusTone('WITHDRAWN'), 'default');
});

test('the version-bound native-button declaration accepts Wot open types and rejects unknown values', () => {
  const ts = requireUpstream('typescript');
  const packagePath = join(upstream, 'node_modules/@uni-helper/uni-app-types/package.json');
  assert.equal(JSON.parse(readFileSync(packagePath, 'utf8')).version, '1.0.0-alpha.6');
  const declarations = ['index.d.ts', 'index.d.mts', 'index.d.cts'].map(file => {
    const content = readFileSync(join(dirname(packagePath), 'dist', file), 'utf8');
    return content.match(/^type _ButtonOpenType = ([^\n]+)/mu)?.[1];
  });
  assert.ok(declarations[0]?.includes('"getRealtimePhoneNumber"'));
  assert.equal(new Set(declarations).size, 1);
  const fixture = mkdtempSync(join(upstream, '.approval-cold-types-'));
  try {
    const filename = join(fixture, 'button-types.ts');
    const imports = [
      "import type { ButtonOpenType, ButtonProps } from '@uni-helper/uni-app-types';",
      "import type { ButtonOpenType as WotButtonOpenType } from 'wot-design-uni/components/wd-button/types';",
    ].join('\n');
    const options = {
      noEmit: true, target: ts.ScriptTarget.ES2020,
      module: ts.ModuleKind.ESNext, moduleResolution: ts.ModuleResolutionKind.Bundler,
      types: [], allowSyntheticDefaultImports: true,
    };
    const diagnostics = source => {
      writeFileSync(filename, `${imports}\n${source}\n`);
      return ts.getPreEmitDiagnostics(ts.createProgram([filename], options));
    };
    const positive = diagnostics([
      "const native: ButtonOpenType = 'getRealtimePhoneNumber';",
      'const prop: ButtonProps[\'openType\'] = native;',
      'declare const wot: WotButtonOpenType;',
      'const forwarded: ButtonOpenType = wot;',
    ].join('\n'));
    assert.deepEqual(positive.map(item => ts.flattenDiagnosticMessageText(item.messageText, '\n')), []);
    const negative = diagnostics("const invalid: ButtonOpenType = 'notAnOpenType';");
    assert.equal(negative.length, 1);
    assert.equal(negative[0].code, 2322);
    assert.equal(negative[0].file?.fileName, filename);
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});


test('the governed type-check command preserves the pinned check and is idempotent and fail-closed', () => {
  const manifest = { scripts: { 'type-check': 'vue-tsc --noEmit' }, pnpm: { overrides: { unconfig: '7.3.2' } } };
  applyUnibestCompatibility(manifest);
  const first = structuredClone(manifest);
  applyUnibestCompatibility(manifest);
  assert.deepEqual(manifest, first);
  assert.equal(manifest.scripts['type-check'],
    'vue-tsc --noEmit && node --test ../../scripts/tests/mobile-cold-start-components.test.mjs');
  assert.deepEqual(manifest.pnpm.overrides, { unconfig: '7.3.2' });
  assert.equal(manifest.pnpm.patchedDependencies['@uni-helper/uni-app-types@1.0.0-alpha.6'],
    'patches/uni-app-types-1.0.0-alpha.6.txt');
  for (const value of [undefined, '', 'vue-tsc --noEmit --skipLibCheck', 'echo skipped']) {
    const invalid = { scripts: { 'type-check': value } };
    const before = structuredClone(invalid);
    assert.throws(() => applyUnibestCompatibility(invalid), /Pinned Unibest type-check command changed/u);
    assert.deepEqual(invalid, before);
  }
  const generated = JSON.parse(readFileSync(join(upstream, 'package.json'), 'utf8'));
  assert.equal(generated.scripts['type-check'], manifest.scripts['type-check']);
});
