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

function runE2e(...args) {
  return spawnSync(
    process.execPath,
    [resolve(root, 'scripts/product-readiness/purchase-payment-e2e.mjs'), ...args],
    {
      cwd: root,
      encoding: 'utf8',
      env: { ...process.env, GITHUB_ACTIONS: 'false' },
      shell: false,
    },
  );
}

const acceptance = JSON.parse(text(
  'config/demo/purchase-payment-alpha-acceptance.json',
));
const scenario = JSON.parse(text(
  'config/demo/purchase-payment-golden-path.json',
));
const orchestrator = [
  text('scripts/product-readiness/purchase-payment-e2e.mjs'),
  text('scripts/product-readiness/purchase-payment-e2e/contract.mjs'),
  text('scripts/product-readiness/purchase-payment-e2e/evidence.mjs'),
  text('scripts/product-readiness/purchase-payment-e2e/lifecycle.mjs'),
  text('scripts/product-readiness/purchase-payment-e2e/payment.mjs'),
  text('scripts/product-readiness/purchase-payment-e2e/runtime.mjs'),
].join('\n');
const paymentSpec = text(
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-h5-payment-runtime.spec.ts',
);
const uiSupport = text(
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-runtime-ui.ts',
);
const ciScope = text(
  'scripts/product-readiness/pc-h5-runtime/ci-scope.mjs',
);
const packageJson = JSON.parse(text('package.json'));
const gitignore = text('.gitignore');
const aggregate = text('scripts/tests/m3-repository-hygiene.test.mjs');

test('governed acceptance keeps WeChat as target and H5 as explicit surrogate', () => {
  const policy = acceptance.paymentConfirmationAcceptance;
  const authoritative = scenario.expectedWorkflow.find(stage =>
    stage.taskDefinitionKey === policy.taskDefinitionKey);
  assert.ok(authoritative);
  assert.equal(policy.taskDefinitionKey, 'paymentConfirmation');
  assert.equal(policy.actorId, 'demo-employee');
  assert.equal(policy.targetClient, 'wechat');
  assert.equal(policy.acceptanceClient, 'h5');
  assert.equal(policy.acceptanceMode, 'H5_MOBILE_SURROGATE');
  assert.deepEqual(authoritative.actorIds, [policy.actorId]);
  assert.equal(authoritative.client, policy.targetClient);
  assert.equal(
    acceptance.nonClaims.includes('WECHAT_DEVTOOLS_RUNTIME_NOT_VERIFIED'),
    true,
  );
  assert.equal(
    acceptance.claimsAfterTwoConsecutiveCleanRuns.includes(
      'H5_PAYMENT_CONFIRMATION_PASSED',
    ),
    true,
  );
  assert.equal(
    acceptance.claimsAfterTwoConsecutiveCleanRuns.includes(
      'WECHAT_DEVTOOLS_PAYMENT_CONFIRMATION_PASSED',
    ),
    false,
  );
});

test('one-command plan is read-only and exposes truthful claims', () => {
  const result = runE2e('plan', '--json');
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const plan = JSON.parse(result.stdout);
  assert.equal(plan.entrypoint, 'pnpm demo:runtime:purchase-payment:e2e');
  assert.equal(plan.targetClient, 'wechat');
  assert.equal(plan.acceptanceClient, 'h5');
  assert.equal(plan.acceptanceMode, 'H5_MOBILE_SURROGATE');
  assert.equal(
    plan.stages.some(stage => stage.includes('pc-h5-runtime-smoke.mjs')),
    true,
  );
  assert.equal(
    plan.stages.some(stage =>
      stage.includes('GenericRestBusinessCallbackConnector')),
    true,
  );
  assert.deepEqual(
    plan.claimsAfterTwoConsecutiveCleanRuns,
    acceptance.claimsAfterTwoConsecutiveCleanRuns,
  );
  assert.deepEqual(plan.nonClaims, acceptance.nonClaims);

  const ci = runE2e('ci');
  assert.equal(ci.status, 0, ci.stderr || ci.stdout);
  assert.match(ci.stdout, /PC_H5_RUNTIME_SMOKE_SKIPPED_NON_CI/u);
});

test('orchestrator reuses the real lifecycle and remains bounded and fail-closed', () => {
  for (const marker of [
    'pc-h5-runtime-smoke.mjs',
    'demo-backend.mjs',
    'demo-client.mjs',
    'mobile:build:weixin',
    'OutboxDispatcher',
    'GenericRestBusinessCallbackConnector',
    'payment-sandbox-status.json',
    'outbox-pending-evidence.json',
    'outbox-delivered-evidence.json',
    'validCompletionOutboxIdentity',
    'paymentRequest.requestId',
    'paymentRequest.traceId',
    'consecutive-clean-runs.json',
    'APPROVAL_PURCHASE_PAYMENT_E2E_ENVELOPE_BEGIN',
    'cleanup-evidence.json',
  ]) {
    assert.equal(orchestrator.includes(marker), true, `missing ${marker}`);
  }
  assert.match(orchestrator, /while \(Date\.now\(\) < deadline\)/u);
  assert.match(orchestrator, /AbortSignal|waitForHttp|waitForState/u);
  assert.match(orchestrator, /select[\s\S]*from ap_outbox/iu);
  assert.doesNotMatch(orchestrator, /\b(?:insert into|update ap_|delete from)\b/iu);
  assert.doesNotMatch(orchestrator, /\bACT_[A-Z0-9_]+\b/u);
  assert.doesNotMatch(orchestrator, /WECHAT_DEVTOOLS_CLI/u);
  assert.doesNotMatch(orchestrator, /miniprogram-automator/u);
  assert.doesNotMatch(
    orchestrator,
    /\/api\/approval\/tasks\/[^\s]*\/approve/u,
  );
  assert.doesNotMatch(orchestrator, /catch\s*\([^)]*\)\s*\{\s*\}/u);
});

test('payment confirmation uses visible H5 controls without direct approval HTTP', () => {
  assert.match(paymentSpec, /pendingResponse\(page/u);
  assert.match(paymentSpec, /clickH5Approval\(/u);
  assert.match(paymentSpec, /'付款确认'/u);
  assert.match(paymentSpec, /waitForPendingTaskToDisappear/u);
  assert.match(paymentSpec, /waitForCompletedInstance/u);
  assert.match(paymentSpec, /H5_PAYMENT_CONFIRMATION_SURROGATE_V1/u);
  assert.match(paymentSpec, /H5_PAYMENT_CONFIRMATION_STAGE_PASSED/u);
  assert.doesNotMatch(paymentSpec, /claim: 'H5_PAYMENT_CONFIRMATION_PASSED'/u);
  assert.match(paymentSpec, /WECHAT_DEVTOOLS_RUNTIME_NOT_VERIFIED|acceptance\.nonClaims/u);
  assert.doesNotMatch(paymentSpec, /request\.post\(/u);
  assert.doesNotMatch(paymentSpec, /fetch\s*\(/u);
  assert.match(
    uiSupport,
    /stageLabel: '财务会签' \| '财务审核' \| '付款确认'/u,
  );
  assert.match(uiSupport, /getByText\('同意', \{ exact: true \}\)/u);
  assert.match(uiSupport, /uni-modal__btn_primary/u);
});

test('package, CI scope and repository hygiene expose the new bounded E2E', () => {
  const expected = {
    'demo:runtime:purchase-payment:e2e:plan':
      'node scripts/product-readiness/purchase-payment-e2e.mjs plan --json',
    'demo:runtime:purchase-payment:e2e':
      'node scripts/product-readiness/purchase-payment-e2e.mjs run',
    'demo:runtime:purchase-payment:e2e:check':
      'node --test scripts/tests/product-readiness-purchase-payment-e2e-boundary.test.mjs',
    'demo:runtime:purchase-payment:e2e:ci':
      'node scripts/product-readiness/pc-h5-runtime-smoke.mjs ci && node scripts/product-readiness/purchase-payment-e2e.mjs ci',
  };
  for (const [name, command] of Object.entries(expected)) {
    assert.equal(packageJson.scripts?.[name], command);
  }
  const clientBoundary = packageJson.scripts?.['web:test:client-boundary'] || '';
  assert.match(clientBoundary, /product-readiness-purchase-payment-e2e-boundary/u);
  assert.match(clientBoundary, /pc-h5-runtime-smoke\.mjs ci/u);
  assert.match(clientBoundary, /purchase-payment-e2e\.mjs ci/u);
  assert.match(ciScope, /purchase-payment-alpha-acceptance/u);
  assert.match(ciScope, /product-readiness-h5-payment-runtime/u);
  assert.match(ciScope, /purchase-payment-e2e/u);
  assert.match(gitignore, /^\.runtime\/$/mu);
  assert.match(
    aggregate,
    /import '\.\/product-readiness-purchase-payment-e2e-boundary\.test\.mjs';/u,
  );
});
