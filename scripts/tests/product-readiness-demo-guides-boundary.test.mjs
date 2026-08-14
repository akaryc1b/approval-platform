import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const quickStartPath = resolve(root, 'docs/product-readiness/QUICK_START.md');
const statusPath = resolve(root, 'docs/product-readiness/README.md');
const preflightPath = resolve(root, 'scripts/product-readiness/demo-preflight.mjs');
const packageJsonPath = resolve(root, 'package.json');
const hygieneAggregatePath = resolve(root, 'scripts/tests/m3-repository-hygiene.test.mjs');

function text(path) {
  assert.equal(existsSync(path), true, `missing ${path}`);
  return readFileSync(path, 'utf8');
}

test('repository-only preflight passes and never claims workstation or timed acceptance', () => {
  const execution = spawnSync(process.execPath, [preflightPath, '--repository-only', '--json'], {
    cwd: root,
    encoding: 'utf8',
  });
  assert.equal(execution.status, 0, execution.stderr || execution.stdout);
  const report = JSON.parse(execution.stdout);
  assert.equal(report.claim, 'DEMO_REPOSITORY_CONTRACT_PASSED');
  assert.equal(report.quickStartAcceptance, 'QUICK_START_10_MINUTES_NOT_EXECUTED');
  assert.equal(report.mode, 'repository-only');
});

test('Quick Start remains an honest executable candidate path', () => {
  const quickStart = text(quickStartPath);
  const packageJson = JSON.parse(text(packageJsonPath));
  assert.match(quickStart, /QUICK_START_STATUS=BASELINE_NOT_YET_10_MINUTE_VERIFIED/u);
  assert.match(quickStart, /PURCHASE_PAYMENT_GOLDEN_PATH_STATUS=NOT_YET_VERIFIED/u);
  assert.match(quickStart, /DEMO_REPOSITORY_CONTRACT_PASSED/u);
  assert.doesNotMatch(quickStart, /QUICK_START_10_MINUTES_PASSED/u);
  assert.doesNotMatch(quickStart, /PRODUCTION_PAYMENT_INTEGRATION_VERIFIED/u);
  assert.doesNotMatch(quickStart, /pnpm install --frozen-lockfile/u);
  for (const scriptName of ['web:install', 'web:dev', 'mobile:install', 'mobile:dev:h5', 'mobile:build:weixin']) {
    assert.equal(typeof packageJson.scripts?.[scriptName], 'string', `missing package script ${scriptName}`);
    assert.equal(quickStart.includes(`pnpm ${scriptName}`), true, `Quick Start missing pnpm ${scriptName}`);
  }
});

test('product-readiness status distinguishes repository, workstation and product evidence', () => {
  const status = text(statusPath);
  assert.match(status, /DEMO_REPOSITORY_CONTRACT_PASSED/u);
  assert.match(status, /DEMO_PREFLIGHT_PASSED/u);
  assert.match(status, /BACKEND_LOCAL_START_VERIFIED/u);
  assert.match(status, /PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED/u);
  assert.match(status, /A successful preflight is not product acceptance/u);
});

test('the permanent hygiene aggregate loads the product-readiness boundary', () => {
  const aggregate = text(hygieneAggregatePath);
  assert.match(aggregate, /import '\.\/product-readiness-demo-guides-boundary\.test\.mjs';/u);
});
