import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const preflightPath = resolve(root, 'scripts/product-readiness/demo-preflight.mjs');

function text(relativePath) {
  const path = resolve(root, relativePath);
  assert.equal(existsSync(path), true, `missing ${relativePath}`);
  return readFileSync(path, 'utf8');
}

const quickStart = text('docs/product-readiness/QUICK_START.md');
const status = text('docs/product-readiness/README.md');
const userGuide = text('docs/product-readiness/USER_GUIDE.md');
const adminGuide = text('docs/product-readiness/ADMIN_GUIDE.md');
const operatorGuide = text('docs/product-readiness/OPERATOR_GUIDE.md');
const rootReadme = text('README.md');
const docsIndex = text('docs/README.md');
const packageJson = JSON.parse(text('package.json'));
const aggregate = text('scripts/tests/m3-repository-hygiene.test.mjs');

test('repository-only preflight remains read-only and withholds timed acceptance', () => {
  const execution = spawnSync(
    process.execPath,
    [preflightPath, '--repository-only', '--json'],
    { cwd: root, encoding: 'utf8', shell: false },
  );
  assert.equal(execution.status, 0, execution.stderr || execution.stdout);
  const report = JSON.parse(execution.stdout);
  assert.equal(report.claim, 'DEMO_REPOSITORY_CONTRACT_PASSED');
  assert.equal(report.quickStartAcceptance, 'QUICK_START_10_MINUTES_NOT_EXECUTED');
  assert.equal(report.mode, 'repository-only');
});

test('Quick Start documents the executable measured path without self-declaring acceptance', () => {
  for (const marker of [
    'QUICK_START_COMMAND_STATUS=IMPLEMENTED',
    'QUICK_START_ACCEPTANCE_SOURCE=EXACT_HEAD_RUNTIME_EVIDENCE',
    'QUICK_START_ACCEPTANCE_STATUS=EXACT_HEAD_EVIDENCE_GATED',
    'pnpm demo:quickstart',
    'pnpm demo:quickstart:plan',
    '.runtime/quick-start/<run-id>/',
    'QUICK_START_READY_SECONDS',
    'A run over 600 seconds fails',
    'quick-start-pc.png',
    'quick-start-h5.png',
    'cleanup-evidence.json',
    'release ports 5432, 5777, 6379, 8080 and 9000',
    'demo-manager',
    'DEMO-PP-0001',
  ]) {
    assert.equal(quickStart.includes(marker), true, `Quick Start missing ${marker}`);
  }
  assert.doesNotMatch(quickStart, /^QUICK_START_STATUS=PASSED$/mu);
  assert.doesNotMatch(quickStart, /^PRODUCTION_DEPLOYMENT_STATUS=VERIFIED$/mu);
  assert.doesNotMatch(quickStart, /\b[0-9a-f]{40}\b/u);
});

test('user, administrator and operator guides preserve the governed local boundary', () => {
  assert.match(userGuide, /DEMO-PP-0001/u);
  assert.match(userGuide, /demo-manager/u);
  assert.match(userGuide, /username: vben/u);
  assert.match(userGuide, /password: 123456/u);
  assert.match(userGuide, /does not approve the task/u);

  assert.match(adminGuide, /purchase-payment-golden-path\.json/u);
  assert.match(adminGuide, /cross-client-local-demo\.json/u);
  assert.match(adminGuide, /quick-start\.json/u);
  assert.match(adminGuide, /Do not edit PostgreSQL rows or Flowable `ACT_\*` tables/u);
  assert.match(adminGuide, /local-header identity/u);

  assert.match(operatorGuide, /approval-platform-demo/u);
  assert.match(
    operatorGuide,
    /ports 5432, 5777, 6379, 8080 and 9000/u,
  );
  assert.match(operatorGuide, /Ctrl-C/u);
  assert.match(operatorGuide, /reset --confirm-local-data-loss/u);
  assert.match(operatorGuide, /No additional automatic Workflow/u);
});

test('product-readiness index distinguishes implemented paths from production claims', () => {
  for (const marker of [
    'MERGED_LOCAL_ALPHA_H5_SURROGATE_ACCEPTED',
    'IMPLEMENTED_EXACT_HEAD_EVIDENCE_GATED',
    'QUICK_START_10_MINUTES_PASSED',
    'PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED',
    'PRODUCTION_PAYMENT_INTEGRATION_VERIFIED',
    'A marker name appearing in documentation does not release the marker',
  ]) {
    assert.equal(status.includes(marker), true, `status missing ${marker}`);
  }
  assert.doesNotMatch(status, /\b[0-9a-f]{40}\b/u);
});

test('root and documentation indexes expose the bounded Quick Start entry', () => {
  assert.match(rootReadme, /pnpm demo:quickstart/u);
  assert.match(rootReadme, /docs\/product-readiness\/QUICK_START\.md/u);
  assert.match(docsIndex, /product-readiness\/QUICK_START\.md/u);
  assert.match(docsIndex, /product-readiness\/USER_GUIDE\.md/u);
  assert.match(docsIndex, /product-readiness\/ADMIN_GUIDE\.md/u);
  assert.match(docsIndex, /product-readiness\/OPERATOR_GUIDE\.md/u);
});

test('package and permanent hygiene aggregate retain the Quick Start documentation contract', () => {
  for (const scriptName of [
    'demo:quickstart:plan',
    'demo:quickstart',
    'demo:quickstart:check',
    'demo:quickstart:ci',
  ]) {
    assert.equal(
      typeof packageJson.scripts?.[scriptName],
      'string',
      `missing package script ${scriptName}`,
    );
  }
  assert.match(
    aggregate,
    /import '\.\/product-readiness-demo-guides-boundary\.test\.mjs';/u,
  );
  assert.match(
    aggregate,
    /import '\.\/product-readiness-quick-start-boundary\.test\.mjs';/u,
  );
});
