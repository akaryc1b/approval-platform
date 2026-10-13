import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

const root = resolve(import.meta.dirname, '../..');

function text(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

const fontCss = text(
  'apps/web/overlay/apps/web-ele/src/styles/approval-fonts.css',
);
const bootstrap = text('apps/web/overlay/apps/web-ele/src/bootstrap.ts');
const launcher = text('scripts/product-readiness/demo-quickstart.mjs');
const fontRuntime = text(
  'scripts/product-readiness/quick-start/cjk-fonts.mjs',
);
const browserSpec = text(
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-quick-start-ready.spec.ts',
);
const fontEvidence = text('apps/web/overlay/playground/__tests__/e2e/product-readiness-cjk-fonts.ts');

test('Vben and Element Plus use an explicit Simplified Chinese fallback stack', () => {
  for (const marker of [
    '--approval-font-family',
    '--font-family: var(--approval-font-family)',
    '--el-font-family: var(--approval-font-family)',
    'Noto Sans CJK SC',
    'Noto Sans SC',
    'PingFang SC',
    'Microsoft YaHei',
    'WenQuanYi Micro Hei',
  ]) {
    assert.equal(fontCss.includes(marker), true, `font CSS missing ${marker}`);
  }
  assert.doesNotMatch(fontCss, /@font-face|url\s*\(/iu);
});

test('application font overrides load after upstream Vben and Element Plus styles', () => {
  const baseIndex = bootstrap.indexOf("import '@vben/styles';");
  const elementIndex = bootstrap.indexOf("import '@vben/styles/ele';");
  const overrideIndex = bootstrap.indexOf(
    "import './styles/approval-fonts.css';",
  );
  assert.notEqual(baseIndex, -1);
  assert.notEqual(elementIndex, -1);
  assert.notEqual(overrideIndex, -1);
  assert.equal(baseIndex < elementIndex && elementIndex < overrideIndex, true);
});

test('Quick Start prepares a bounded Linux CJK runtime without a font binary or CDN', () => {
  assert.match(launcher, /ensureCjkFontRuntime\(\)/u);
  assert.match(fontRuntime, /fc-list/u);
  assert.match(fontRuntime, /fonts-noto-cjk/u);
  assert.match(fontRuntime, /process\.env\.GITHUB_ACTIONS === 'true'/u);
  assert.match(fontRuntime, /sudo[\s\S]*?-n[\s\S]*?apt-get/u);
  assert.match(fontRuntime, /timeout:/u);
  assert.match(fontRuntime, /No Simplified Chinese font is available/u);
  assert.doesNotMatch(fontRuntime, /https?:\/\//u);
  assert.doesNotMatch(fontRuntime, /shell:\s*true/u);
});

test('real PC and H5 browser evidence rejects identical tofu glyphs', () => {
  assert.match(browserSpec, /collectCjkFontEvidence/u);
  assert.match(fontEvidence, /审批任务采购付款工作台/u);
  assert.match(fontEvidence, /document\.fonts\.ready/u);
  assert.match(fontEvidence, /getImageData/u);
  assert.match(fontEvidence, /uniqueGlyphHashes/u);
  assert.match(fontEvidence, /\.size < 6/u);
  assert.match(fontEvidence, /cjkGlyphsRendered:\s*true/u);
  assert.match(fontEvidence, /computedFontFamily/u);
  assert.match(fontEvidence, /CSS\.getPlatformFontsForNode/u);
  assert.doesNotMatch(browserSpec, /waitForTimeout/u);
});
