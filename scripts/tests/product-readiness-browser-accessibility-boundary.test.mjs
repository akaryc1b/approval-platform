import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

const root = resolve(import.meta.dirname, '../..');

function text(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

const manifest = JSON.parse(
  text('config/demo/browser-accessibility-matrix.json'),
);
const launcher = text(
  'scripts/product-readiness/browser-accessibility.mjs',
);
const contract = text(
  'scripts/product-readiness/browser-accessibility/contract.mjs',
);
const runtime = text(
  'scripts/product-readiness/browser-accessibility/runtime.mjs',
);
const evidence = text(
  'scripts/product-readiness/browser-accessibility/evidence.mjs',
);
const config = text(
  'apps/web/overlay/playground/browser-accessibility.playwright.config.ts',
);
const spec = text(
  'apps/web/overlay/playground/__tests__/e2e/'
  + 'product-readiness-browser-accessibility.spec.ts',
);
const mobileIndex = text('apps/mobile/overlay/index.html');
const mobileMain = text('apps/mobile/overlay/src/main.ts');
const h5Accessibility = text(
  'apps/mobile/overlay/src/platform/h5-accessibility.ts',
);
const packageJson = JSON.parse(text('package.json'));
const ciScope = text(
  'scripts/product-readiness/pc-h5-runtime/ci-scope.mjs',
);
const aggregate = text('scripts/tests/m3-repository-hygiene.test.mjs');

test('browser/accessibility plan is governed by the accepted demo manifests', () => {
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.locale, 'zh-CN');
  assert.equal(manifest.scenarioSource, 'config/demo/quick-start.json');
  assert.deepEqual(
    manifest.projects.map(project => [project.id, project.engine]),
    [
      ['system-chromium', 'chromium'],
      ['bundled-firefox', 'firefox'],
      ['bundled-webkit', 'webkit'],
    ],
  );
  assert.equal(manifest.projects[2].runtime, 'PLAYWRIGHT_WEBKIT');
  assert.equal(
    manifest.nonClaims.includes('SAFARI_BROWSER_NOT_VERIFIED'),
    true,
  );
  assert.equal(
    manifest.nonClaims.includes('FULL_WCAG_CONFORMANCE_NOT_VERIFIED'),
    true,
  );
  assert.match(contract, /resolveGovernedScenario/u);
  assert.match(contract, /quickStart\.scenarioManifest/u);
});

test('plan command is read-only and machine-readable', () => {
  const result = spawnSync(
    process.execPath,
    [
      resolve(root, 'scripts/product-readiness/browser-accessibility.mjs'),
      'plan',
      '--json',
    ],
    {
      cwd: root,
      encoding: 'utf8',
      env: { ...process.env, GITHUB_ACTIONS: 'false' },
      shell: false,
    },
  );
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const plan = JSON.parse(result.stdout);
  assert.equal(
    plan.entrypoint,
    'pnpm demo:runtime:browser-accessibility',
  );
  assert.equal(plan.scenario.tenantId, 'demo-purchase-payment');
  assert.equal(plan.scenario.businessKey, 'DEMO-PP-0001');
  assert.equal(plan.projects.length, 3);
  assert.equal(plan.evidenceRoot, '.runtime/browser-accessibility/<run-id>/');
});

test('orchestrator reuses Quick Start and fails closed around cleanup', () => {
  for (const marker of [
    "'scripts/product-readiness/demo-quickstart.mjs', 'start'",
    'QUICK_START_EVIDENCE=',
    'playwright',
    "'install'",
    "'firefox'",
    "'webkit'",
    'validateQuickStartCleanup',
    'deleted:approval-platform-demo-volume',
    'released-port:9000',
    'appendCiEvidenceEnvelope',
    'finally',
  ]) {
    assert.equal(runtime.includes(marker), true, `runtime missing ${marker}`);
  }
  assert.match(runtime, /terminateManaged\(quickStart\)/u);
  assert.match(runtime, /waitForExit/u);
  assert.doesNotMatch(runtime, /\bACT_[A-Z0-9_]+\b/u);
  assert.doesNotMatch(runtime, /\b(?:insert into|update ap_|delete from)\b/iu);
  assert.doesNotMatch(runtime, /\/api\/approval\/tasks\/[^\s]*\/approve/u);
  assert.doesNotMatch(runtime, /setTimeout\([^,]+,\s*\d{4,}/u);
  assert.doesNotMatch(runtime, /catch\s*\([^)]*\)\s*\{\s*\}/u);
  assert.match(launcher, /shouldRunInCi/u);
  assert.match(launcher, /BROWSER_ACCESSIBILITY_MATRIX_SCOPE/u);
  assert.match(evidence, /BROWSER_ACCESSIBILITY_CI_ARTIFACT_ENVELOPE_V1/u);
  assert.match(evidence, /root-install\.log/u);
  assert.match(evidence, /trace\.zip/u);
  assert.match(evidence, /maximumTotalBytes/u);
});

test('Playwright matrix keeps WebKit distinct from Safari', () => {
  for (const marker of [
    "name: 'system-chromium'",
    "name: 'bundled-firefox'",
    "name: 'bundled-webkit'",
    "browserName: 'chromium'",
    "browserName: 'firefox'",
    "browserName: 'webkit'",
  ]) {
    assert.equal(config.includes(marker), true, `config missing ${marker}`);
  }
  assert.doesNotMatch(config, /Safari/u);
  assert.match(config, /workers:\s*1/u);
  assert.match(config, /retries:\s*0/u);
});

test('real browser evidence covers names, contrast, CJK glyphs and keyboard focus', () => {
  for (const marker of [
    '审批任务采购付款工作台',
    'document.fonts.ready',
    'getImageData',
    'uniqueGlyphHashes',
    'minimumInkPixels',
    'control-name',
    'targeted-text-contrast',
    'tabTo',
    "page.keyboard.press('Tab')",
    "pc.keyboard.press('Enter')",
    "pc.keyboard.press('Escape')",
    'authenticatedPcTaskFlow',
    'BROWSER_ACCESSIBILITY_PROJECT_V1',
  ]) {
    assert.equal(spec.includes(marker), true, `spec missing ${marker}`);
  }
  assert.match(spec, /ensurePcLogin/u);
  assert.match(spec, /getByRole\('button',[\s\S]*?name: '处理'/u);
  assert.match(spec, /getByRole\('button',[\s\S]*?name: '同意'/u);
  assert.doesNotMatch(spec, /request\.post\(/u);
  assert.doesNotMatch(spec, /waitForTimeout/u);
  assert.doesNotMatch(spec, /clickPcApproval|clickH5Approval/u);
});

test('H5 shell exposes language, focus and keyboard activation semantics', () => {
  assert.match(mobileIndex, /<html lang="zh-CN"/u);
  assert.match(mobileIndex, /uni-button\.wd-button:focus-visible/u);
  assert.match(mobileMain, /installH5Accessibility/u);
  for (const marker of [
    "const buttonSelector = 'uni-button.wd-button'",
    "button.setAttribute('role', 'button')",
    "button.setAttribute('tabindex', isDisabled ? '-1' : '0')",
    "button.setAttribute('aria-disabled', String(isDisabled))",
    "document.documentElement.lang = 'zh-CN'",
    'MutationObserver',
    "event.key !== 'Enter'",
    "event.key !== ' '",
    'button.click()',
  ]) {
    assert.equal(
      h5Accessibility.includes(marker),
      true,
      `H5 accessibility bridge missing ${marker}`,
    );
  }
  assert.doesNotMatch(h5Accessibility, /setTimeout/u);
  assert.doesNotMatch(h5Accessibility, /catch\s*\([^)]*\)\s*\{\s*\}/u);
});

test('package scripts and existing CI scope expose one bounded runtime', () => {
  assert.equal(
    packageJson.scripts['demo:runtime:browser-accessibility'],
    'node scripts/product-readiness/browser-accessibility.mjs run',
  );
  assert.equal(
    packageJson.scripts['demo:runtime:browser-accessibility:plan'],
    'node scripts/product-readiness/browser-accessibility.mjs plan --json',
  );
  assert.equal(
    packageJson.scripts['demo:runtime:browser-accessibility:check'],
    'node --test scripts/tests/'
    + 'product-readiness-browser-accessibility-boundary.test.mjs',
  );
  assert.equal(
    packageJson.scripts['demo:runtime:browser-accessibility:ci'],
    'node scripts/product-readiness/browser-accessibility.mjs ci',
  );
  assert.match(
    packageJson.scripts['web:test:client-boundary'],
    /product-readiness-browser-accessibility-boundary/u,
  );
  assert.match(
    packageJson.scripts['web:test:client-boundary'],
    /browser-accessibility\.mjs ci/u,
  );
  assert.match(ciScope, /browser-accessibility/u);
  assert.match(
    aggregate,
    /product-readiness-browser-accessibility-boundary\.test\.mjs/u,
  );
});
