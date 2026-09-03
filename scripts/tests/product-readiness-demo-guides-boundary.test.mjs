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
const onlineDemo = text('docs/product-readiness/ONLINE_DEMO.md');
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

test('Quick Start documents the merged measured path without production claims', () => {
  for (const marker of [
    'QUICK_START_COMMAND_STATUS=IMPLEMENTED',
    'QUICK_START_ACCEPTANCE_SOURCE=RETAINED_EXACT_HEAD_AND_POST_MERGE_EVIDENCE',
    'QUICK_START_ACCEPTANCE_STATUS=MERGED_LOCAL_ALPHA_ACCEPTED',
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
    'QUICK_START_10_MINUTES_PASSED',
    'ONLINE_DEMO_NOT_AVAILABLE',
  ]) {
    assert.equal(quickStart.includes(marker), true, `Quick Start missing ${marker}`);
  }
  assert.doesNotMatch(quickStart, /EXACT_HEAD_EVIDENCE_GATED/u);
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

test('product-readiness index reflects merged Product Alpha outcomes', () => {
  for (const marker of [
    'MERGED_MEASURED_LOCAL_ALPHA_ACCEPTED',
    'MERGED_LOCAL_ALPHA_H5_SURROGATE_ACCEPTED',
    'MERGED_BOUNDED_BASELINE_ACCEPTED',
    'PLANNED_NOT_AVAILABLE',
    'QUICK_START_10_MINUTES_PASSED',
    'PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED',
    'BROWSER_ACCESSIBILITY_MATRIX_PUBLISHED',
    'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
  ]) {
    assert.equal(status.includes(marker), true, `status missing ${marker}`);
  }
  assert.match(status, /命令作用域声明/u);
  assert.doesNotMatch(status, /IMPLEMENTED_EXACT_HEAD_EVIDENCE_GATED/u);
  assert.doesNotMatch(status, /\b[0-9a-f]{40}\b/u);
});

test('root README is a product landing page instead of a development status dump', () => {
  for (const marker of [
    '通用审批与流程协作平台',
    '产品解决什么问题',
    '一条可以直接体验的业务流程',
    'pnpm demo:quickstart',
    'pnpm demo:runtime:purchase-payment:e2e',
    '当前没有公共在线试用地址',
    'docs/product-readiness/ONLINE_DEMO.md',
    'docs/current/capability-status.md',
  ]) {
    assert.equal(rootReadme.includes(marker), true, `root README missing ${marker}`);
  }
  assert.doesNotMatch(rootReadme, /\b[0-9a-f]{40}\b/u);
  assert.doesNotMatch(rootReadme, /M4\s+已通过\s+PR|M5\s+正在|M6\s+规划/iu);
});

test('online demo guide remains explicitly planned, isolated and non-production', () => {
  for (const marker of [
    'ONLINE_DEMO_STATUS=PLANNED_NOT_AVAILABLE',
    'PUBLIC_URL_STATUS=NOT_PUBLISHED',
    'TRACKING_ISSUE=#144',
    'PostgreSQL 16',
    'Redis',
    'HTTPS',
    'rate limiting',
    '会话与数据隔离',
    '自动 reset',
    'signed local payment sandbox',
    'PR #142',
    'ONLINE_DEMO_NOT_AVAILABLE',
  ]) {
    assert.equal(onlineDemo.includes(marker), true, `online demo missing ${marker}`);
  }
  assert.doesNotMatch(onlineDemo, /^ONLINE_DEMO_STATUS=AVAILABLE$/mu);
  assert.doesNotMatch(onlineDemo, /^PUBLIC_URL_STATUS=PUBLISHED$/mu);
  assert.doesNotMatch(onlineDemo, /\b[0-9a-f]{40}\b/u);
});

test('documentation indexes expose product and online-demo entry points', () => {
  assert.match(rootReadme, /docs\/product-readiness\/QUICK_START\.md/u);
  assert.match(rootReadme, /docs\/product-readiness\/PURCHASE_PAYMENT_GOLDEN_PATH\.md/u);
  assert.match(rootReadme, /docs\/product-readiness\/ONLINE_DEMO\.md/u);
  assert.match(docsIndex, /product-readiness\/QUICK_START\.md/u);
  assert.match(docsIndex, /product-readiness\/USER_GUIDE\.md/u);
  assert.match(docsIndex, /product-readiness\/ADMIN_GUIDE\.md/u);
  assert.match(docsIndex, /product-readiness\/OPERATOR_GUIDE\.md/u);
  assert.match(docsIndex, /product-readiness\/PURCHASE_PAYMENT_GOLDEN_PATH\.md/u);
  assert.match(docsIndex, /product-readiness\/BROWSER_ACCESSIBILITY_MATRIX\.md/u);
  assert.match(docsIndex, /product-readiness\/ONLINE_DEMO\.md/u);
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
