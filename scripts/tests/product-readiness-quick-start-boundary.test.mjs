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

function runQuickStart(...args) {
  return spawnSync(
    process.execPath,
    [resolve(root, 'scripts/product-readiness/demo-quickstart.mjs'), ...args],
    {
      cwd: root,
      encoding: 'utf8',
      env: { ...process.env, GITHUB_ACTIONS: 'false' },
      shell: false,
    },
  );
}

const launcher = text('scripts/product-readiness/demo-quickstart.mjs');
const contract = text('scripts/product-readiness/quick-start/contract.mjs');
const runtime = text('scripts/product-readiness/quick-start/runtime.mjs');
const evidence = text('scripts/product-readiness/quick-start/evidence.mjs');
const ciScope = text('scripts/product-readiness/pc-h5-runtime/ci-scope.mjs');
const browserSpec = text(
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-quick-start-ready.spec.ts',
);
const browserContract = text(
  'scripts/product-readiness/pc-h5-runtime/contract.mjs',
);
const quickStart = JSON.parse(text('config/demo/quick-start.json'));
const crossClient = JSON.parse(text('config/demo/cross-client-local-demo.json'));
const packageJson = JSON.parse(text('package.json'));
const aggregate = text('scripts/tests/m3-repository-hygiene.test.mjs');

test('Quick Start exposes a measured local-only plan and skips non-CI execution', () => {
  const planned = runQuickStart('plan', '--json');
  assert.equal(planned.status, 0, planned.stderr || planned.stdout);
  const plan = JSON.parse(planned.stdout);
  assert.equal(plan.entrypoint, 'pnpm demo:quickstart');
  assert.equal(plan.maximumReadySeconds, 600);
  assert.equal(plan.tenantId, 'demo-purchase-payment');
  assert.equal(plan.businessKey, 'DEMO-PP-0001');
  assert.match(plan.pcUrl, /demoOperator=demo-manager/u);
  assert.match(plan.h5Url, /demoOperator=demo-manager#\/pages\/task\/list/u);
  assert.equal(
    plan.stages.some(stage => stage.includes('reuse demo-backend.mjs')),
    true,
  );
  assert.equal(
    plan.stages.some(stage => stage.includes('reuse demo-client.mjs')),
    true,
  );
  assert.deepEqual(
    plan.claimsAfterTwoConsecutiveCleanRuns,
    quickStart.claimsAfterTwoConsecutiveCleanRuns,
  );
  assert.deepEqual(plan.nonClaims, quickStart.nonClaims);

  const ci = runQuickStart('ci');
  assert.equal(ci.status, 0, ci.stderr || ci.stdout);
  assert.match(ci.stdout, /PC_H5_RUNTIME_SMOKE_SKIPPED_NON_CI/u);
  assert.doesNotMatch(ci.stdout, /QUICK_START_10_MINUTES_PASSED/u);
});

test('governed Quick Start identity and deadline are explicit', () => {
  assert.equal(quickStart.maximumReadySeconds, 600);
  assert.equal(quickStart.clients.pc.actorId, 'demo-manager');
  assert.equal(quickStart.clients.h5.actorId, 'demo-manager');
  assert.equal(
    crossClient.clients.pc.allowedActors.includes(quickStart.clients.pc.actorId),
    true,
  );
  assert.equal(
    crossClient.clients.h5.allowedActors.includes(quickStart.clients.h5.actorId),
    true,
  );
  assert.equal(quickStart.clients.pc.port, crossClient.clients.pc.defaultPort);
  assert.equal(quickStart.clients.h5.port, crossClient.clients.h5.defaultPort);
  assert.match(contract, /maximumReadySeconds must be between 60 and 600/u);
  assert.match(contract, /Quick Start PC and H5 must show the same governed pending task/u);
});

test('orchestrator reuses existing backend, clients and generated workspaces', () => {
  for (const marker of [
    "'scripts/product-readiness/demo-backend.mjs'",
    "'scripts/product-readiness/demo-client.mjs'",
    "['web:install']",
    "['mobile:install']",
    'startManagedNode',
    'waitForMarker',
    'waitForHttp',
    'BACKEND_LOCAL_START_VERIFIED',
    'PURCHASE_PAYMENT_DEMO_SEED_APPLIED',
    'product-readiness-quick-start-ready.spec.ts',
  ]) {
    assert.equal(runtime.includes(marker), true, `runtime missing ${marker}`);
  }
  assert.match(runtime, /remainingMilliseconds\(deadline/u);
  assert.match(runtime, /AbortSignal\.timeout/u);
  assert.match(runtime, /finally/u);
  assert.match(runtime, /cleanup\(managed, environment, runDirectory\)/u);
  assert.doesNotMatch(runtime, /\bACT_[A-Z0-9_]+\b/u);
  assert.doesNotMatch(runtime, /\b(?:insert into|update ap_|delete from)\b/iu);
  assert.doesNotMatch(runtime, /\/api\/approval\/tasks\/[^\s]*\/approve/u);
  assert.doesNotMatch(runtime, /setTimeout\([^,]+,\s*\d{4,}/u);
  assert.doesNotMatch(runtime, /catch\s*\([^)]*\)\s*\{\s*\}/u);
});

test('browser evidence proves the seeded request is visible without approving it', () => {
  assert.match(browserSpec, /ensurePcLogin/u);
  assert.match(browserSpec, /locator\('\.task-item'\)/u);
  assert.match(browserSpec, /locator\('\.task-card'\)/u);
  assert.match(browserSpec, /quick-start-pc\.png/u);
  assert.match(browserSpec, /quick-start-h5\.png/u);
  assert.match(browserSpec, /QUICK_START_BROWSER_READY_V1/u);
  assert.doesNotMatch(browserSpec, /clickPcApproval/u);
  assert.doesNotMatch(browserSpec, /clickH5Approval/u);
  assert.doesNotMatch(browserSpec, /request\.post\(/u);
  assert.doesNotMatch(browserSpec, /waitForTimeout/u);
});

test('evidence binds source, environment, timing, screenshots, cleanup and two runs', () => {
  for (const marker of [
    'QUICK_START_SOURCE_IDENTITY_V1',
    'environment.json',
    'backend-health.json',
    'startup-summary.json',
    'cleanup-evidence.json',
    'runtime-summary.json',
    'successfulRunIds',
    'claimsDeclared',
    'appendCiEvidenceEnvelope',
  ]) {
    assert.equal(runtime.includes(marker), true, `runtime evidence missing ${marker}`);
  }
  for (const marker of [
    'QUICK_START_CONSECUTIVE_CLEAN_RUNS_V1',
    'QUICK_START_CI_ARTIFACT_ENVELOPE_V1',
    'quick-start-pc.png',
    'quick-start-h5.png',
    'trace.zip',
    'sha256',
  ]) {
    assert.equal(evidence.includes(marker), true, `evidence support missing ${marker}`);
  }
  assert.equal(
    (launcher.match(/await execute\(\{ keepAlive: false \}\)/gu) || []).length,
    2,
  );
  assert.match(launcher, /QUICK_START_SECOND_CLEAN_RUN_STARTING/u);
});

test('Chrome discovery supports explicit, Linux, macOS and Windows paths', () => {
  assert.match(browserContract, /APPROVAL_DEMO_CHROME_PATH/u);
  assert.match(browserContract, /\/Applications\/Google Chrome\.app/u);
  assert.match(browserContract, /Microsoft\/Edge\/Application\/msedge\.exe/u);
  assert.match(browserContract, /\/usr\/bin\/chromium/u);
});

test('package and path-scoped CI expose the Quick Start without a second workflow', () => {
  assert.equal(
    packageJson.scripts['demo:quickstart'],
    'node scripts/product-readiness/demo-quickstart.mjs start',
  );
  assert.equal(
    packageJson.scripts['demo:quickstart:plan'],
    'node scripts/product-readiness/demo-quickstart.mjs plan --json',
  );
  assert.equal(
    packageJson.scripts['demo:quickstart:check'],
    'node --test scripts/tests/product-readiness-quick-start-boundary.test.mjs',
  );
  assert.match(packageJson.scripts['web:test:client-boundary'], /demo-quickstart\.mjs ci/u);
  const normalizedCiScope = ciScope.replaceAll(String.raw`\/`, '/');
  assert.match(normalizedCiScope, /scripts\/product-readiness\/quick-start\//u);
  assert.match(ciScope, /product-readiness-quick-start-ready/u);
  assert.match(
    aggregate,
    /import '\.\/product-readiness-quick-start-boundary\.test\.mjs';/u,
  );
});
