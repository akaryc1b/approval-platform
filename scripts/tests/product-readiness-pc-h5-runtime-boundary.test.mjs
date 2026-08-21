import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

function text(path) {
  const absolute = resolve(root, path);
  assert.equal(existsSync(absolute), true, `missing ${path}`);
  return readFileSync(absolute, 'utf8');
}

function runSmoke(...args) {
  return spawnSync(
    process.execPath,
    [resolve(root, 'scripts/product-readiness/pc-h5-runtime-smoke.mjs'), ...args],
    {
      cwd: root,
      encoding: 'utf8',
      env: { ...process.env, GITHUB_ACTIONS: 'false' },
      shell: false,
    },
  );
}

const smoke = text('scripts/product-readiness/pc-h5-runtime-smoke.mjs');
const runtimeContract = text(
  'scripts/product-readiness/pc-h5-runtime/contract.mjs',
);
const processSupport = [
  text('scripts/product-readiness/pc-h5-runtime/processes.mjs'),
  text('scripts/product-readiness/pc-h5-runtime/ci-scope.mjs'),
].join('\n');
const playwrightConfig = text(
  'apps/web/overlay/playground/product-readiness.playwright.config.ts',
);
const playwrightSpec = text(
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-runtime.spec.ts',
);
const playwrightApi = text(
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-runtime-api.ts',
);
const playwrightUi = text(
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-runtime-ui.ts',
);
const packageJson = JSON.parse(text('package.json'));
const guide = text('docs/product-readiness/PC_H5_RUNTIME_SMOKE.md');
const aggregate = text('scripts/tests/m3-repository-hygiene.test.mjs');

test('runtime smoke exposes a read-only plan and skips CI outside GitHub Actions', () => {
  const planned = runSmoke('plan', '--json');
  assert.equal(planned.status, 0, planned.stderr || planned.stdout);
  const plan = JSON.parse(planned.stdout);
  assert.equal(plan.claim, 'PC_H5_APPROVAL_HANDOFF_PASSED');
  assert.equal(plan.evidenceKind, 'PC_H5_BROWSER_APPROVAL_HANDOFF_V1');
  assert.deepEqual(plan.nonClaims, [
    'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
    'WECHAT_MINI_PROGRAM_RUNTIME_NOT_EXECUTED',
    'PC_H5_WECHAT_RUNTIME_NOT_EXECUTED',
    'BROWSER_COMPATIBILITY_NOT_VERIFIED',
    'ACCESSIBILITY_NOT_VERIFIED',
    'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
    'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
    'QUICK_START_10_MINUTES_NOT_EXECUTED',
  ]);

  const ci = runSmoke('ci');
  assert.equal(ci.status, 0, ci.stderr || ci.stdout);
  assert.match(ci.stdout, /PC_H5_RUNTIME_SMOKE_SKIPPED_NON_CI/u);
});

test('orchestrator uses fixed executables, local services and path-gated CI', () => {
  assert.match(processSupport, /spawnSync\(pnpmExecutable\(\), args/u);
  assert.match(processSupport, /spawn\(process\.execPath, args/u);
  assert.match(processSupport, /spawnSync\(gitExecutable\(\), args/u);
  assert.match(processSupport, /shell: false/gu);
  assert.match(processSupport, /GITHUB_EVENT_PATH/u);
  assert.match(processSupport, /PC_H5_RUNTIME_SCOPE=/u);
  assert.match(smoke, /http:\/\/127\.0\.0\.1:8080/u);
  assert.match(runtimeContract, /JAVA_HOME_21_X64/u);
  assert.match(runtimeContract, /PATH: `\$\{javaBin\}\$\{delimiter\}/u);
  assert.doesNotMatch(processSupport, /spawn(?:Sync)?\(command/u);
  assert.doesNotMatch(processSupport, /shell:\s*true/u);
  assert.doesNotMatch(processSupport, /\bexec\s*\(/u);
  assert.doesNotMatch(
    `${smoke}\n${processSupport}`,
    /X-Approval-Trusted-Permissions/u,
  );
  assert.doesNotMatch(
    `${smoke}\n${processSupport}`,
    /X-Approval-Worker-Id/u,
  );
});

test('browser test approves through visible PC and H5 controls', () => {
  assert.match(playwrightSpec, /clickPcApproval/u);
  assert.match(playwrightUi, /getByRole\('button', \{ name: '同意'/u);
  assert.match(playwrightSpec, /clickH5Approval/u);
  assert.match(playwrightUi, /locator\('\.action-bar'\)/u);
  assert.match(playwrightUi, /getByText\('同意'/u);
  assert.match(playwrightUi, /uni-modal__btn_primary/u);
  assert.match(playwrightSpec, /PC_H5_APPROVAL_HANDOFF_PASSED/u);
  assert.match(playwrightSpec, /financeCountersign/u);
  assert.match(playwrightSpec, /screenshot/u);
  assert.match(playwrightApi, /X-Operator-Id/u);
  assert.doesNotMatch(
    `${playwrightSpec}\n${playwrightApi}`,
    /request\.post\(/u,
  );
  assert.doesNotMatch(
    `${playwrightSpec}\n${playwrightUi}`,
    /X-Approval-Trusted-Permissions/u,
  );
  assert.doesNotMatch(
    playwrightSpec,
    /PRODUCTION_PAYMENT_INTEGRATION_VERIFIED/u,
  );
});

test('system Chromium configuration is explicit and downloads no browser', () => {
  assert.match(playwrightConfig, /APPROVAL_DEMO_CHROME_PATH/u);
  assert.match(playwrightConfig, /executablePath/u);
  assert.match(playwrightConfig, /--no-sandbox/u);
  assert.match(playwrightConfig, /workers: 1/u);
  assert.doesNotMatch(playwrightConfig, /install-deps/u);
  assert.doesNotMatch(playwrightConfig, /playwright install/u);
});

test('package, guide and permanent Hygiene expose the bounded smoke', () => {
  assert.equal(
    packageJson.scripts?.['demo:runtime:pc-h5:plan'],
    'node scripts/product-readiness/pc-h5-runtime-smoke.mjs plan --json',
  );
  assert.equal(
    packageJson.scripts?.['demo:runtime:pc-h5'],
    'node scripts/product-readiness/pc-h5-runtime-smoke.mjs run',
  );
  assert.equal(
    packageJson.scripts?.['demo:runtime:pc-h5:check'],
    'node --test scripts/tests/product-readiness-pc-h5-runtime-boundary.test.mjs',
  );
  assert.match(
    packageJson.scripts?.['web:test:client-boundary'] || '',
    /pc-h5-runtime-smoke\.mjs ci/u,
  );

  for (const marker of [
    'PC_H5_APPROVAL_HANDOFF_PASSED',
    'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
    'WECHAT_MINI_PROGRAM_RUNTIME_NOT_EXECUTED',
    'PC_H5_WECHAT_RUNTIME_NOT_EXECUTED',
  ]) {
    assert.equal(guide.includes(marker), true, `guide missing ${marker}`);
  }
  assert.doesNotMatch(guide, /^PURCHASE_APPROVAL_E2E_PASSED$/mu);
  assert.doesNotMatch(guide, /^PC_H5_WECHAT_RUNTIME_PASSED$/mu);
  assert.match(
    aggregate,
    /import '\.\/product-readiness-pc-h5-runtime-boundary\.test\.mjs';/u,
  );
});
