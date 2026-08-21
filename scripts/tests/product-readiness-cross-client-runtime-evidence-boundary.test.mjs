import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { summarizeTaskHistory } from '../product-readiness/runtime-task-history.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const observerPath = resolve(
  root,
  'scripts/product-readiness/demo-runtime-evidence.mjs',
);

function text(path) {
  const absolute = resolve(root, path);
  assert.equal(existsSync(absolute), true, `missing ${path}`);
  return readFileSync(absolute, 'utf8');
}

function runObserver(...args) {
  return spawnSync(process.execPath, [observerPath, ...args], {
    cwd: root,
    encoding: 'utf8',
    shell: false,
  });
}

const scenario = JSON.parse(text('config/demo/purchase-payment-golden-path.json'));
const crossClient = JSON.parse(text('config/demo/cross-client-local-demo.json'));
const observer = text('scripts/product-readiness/demo-runtime-evidence.mjs');
const packageJson = JSON.parse(text('package.json'));
const aggregate = text('scripts/tests/m3-repository-hygiene.test.mjs');
const guide = text('docs/product-readiness/CROSS_CLIENT_RUNTIME_EVIDENCE.md');

test('interactive runtime plan matches the governed cross-client handoff', () => {
  const execution = runObserver('plan', '--json');
  assert.equal(execution.status, 0, execution.stderr || execution.stdout);
  const plan = JSON.parse(execution.stdout);

  assert.equal(plan.schemaVersion, 1);
  assert.equal(plan.evidenceKind, 'CROSS_CLIENT_INTERACTIVE_RUNTIME_OBSERVER_V1');
  assert.equal(plan.tenantId, scenario.tenant.id);
  assert.equal(plan.businessKey, scenario.request.businessKey);
  assert.equal(plan.backendCommand, 'pnpm demo:backend:start');

  const expected = crossClient.expectedHandoff.map((handoff, index) => ({
    actorId: handoff.actorId,
    client: handoff.client,
    sequence: index + 1,
    taskDefinitionKey: handoff.taskDefinitionKey,
  }));
  assert.deepEqual(
    plan.handoffs.map(({ actorId, client, sequence, taskDefinitionKey }) => ({
      actorId,
      client,
      sequence,
      taskDefinitionKey,
    })),
    expected,
  );
  assert.equal(plan.finalRead.actorId, 'demo-admin');
  assert.equal(plan.finalRead.client, 'pc');
  assert.deepEqual(plan.claimsAvailableAfterSuccessfulObservation, [
    'CROSS_CLIENT_SHARED_INSTANCE_OBSERVED',
    'CROSS_CLIENT_ROLE_HANDOFFS_OBSERVED',
    'PURCHASE_APPROVAL_RUNTIME_COMPLETED',
  ]);
});

test('observer fails closed before any runtime polling', () => {
  const missingConfirmation = runObserver('observe');
  assert.equal(missingConfirmation.status, 2);
  assert.match(missingConfirmation.stderr, /requires --confirm-interactive-run/u);

  const publicBackend = runObserver(
    'observe',
    '--confirm-interactive-run',
    '--backend-origin',
    'https://example.com',
  );
  assert.equal(publicBackend.status, 2);
  assert.match(publicBackend.stderr, /loopback or RFC1918 HTTP origin/u);

  const escapedOutput = runObserver(
    'observe',
    '--confirm-interactive-run',
    '--output',
    '../cross-client-evidence.json',
  );
  assert.equal(escapedOutput.status, 2);
  assert.match(escapedOutput.stderr, /below build\/product-readiness/u);

  const invalidTimeout = runObserver(
    'observe',
    '--confirm-interactive-run',
    '--timeout-seconds',
    '5',
  );
  assert.equal(invalidTimeout.status, 2);
  assert.match(invalidTimeout.stderr, /between 30 and 1800/u);
});

test('observer reads runtime state and never performs an approval write', () => {
  assert.match(observer, /\/actuator\/health/u);
  assert.match(observer, /\/tasks\/pending/u);
  assert.match(observer, /\/instances\/\$\{encodeURIComponent\(instanceId\)\}\/timeline/u);
  assert.match(observer, /\/instances\/\$\{encodeURIComponent\(instanceId\)\}/u);
  assert.match(observer, /TASK_APPROVED/u);

  assert.doesNotMatch(observer, /\/tasks\/[^\s'"`]+\/approve/u);
  assert.doesNotMatch(observer, /method:\s*['"]POST['"]/u);
  assert.doesNotMatch(observer, /Idempotency-Key/u);
  assert.doesNotMatch(observer, /approvalCommandHeaders/u);
  assert.doesNotMatch(observer, /X-Approval-Trusted-Permissions/u);
  assert.doesNotMatch(observer, /X-Approval-Worker-Id/u);
});

test('retained task history counts only pending and completing tasks as active', () => {
  const completedHistory = summarizeTaskHistory([
    { taskId: 'manager-task', status: 'COMPLETED' },
    { taskId: 'finance-review-task', status: 'COMPLETED' },
    { taskId: 'finance-a-task', status: 'COMPLETED' },
    { taskId: 'finance-b-task', status: 'COMPLETED' },
    { taskId: 'withdrawn-task', status: 'CANCELED' },
  ]);
  assert.equal(completedHistory.activeTaskCount, 0);
  assert.deepEqual(completedHistory.activeTaskIds, []);
  assert.equal(completedHistory.historyTaskCount, 5);
  assert.deepEqual(completedHistory.statusCounts, {
    PENDING: 0,
    COMPLETING: 0,
    COMPLETED: 4,
    CANCELED: 1,
  });

  const activeHistory = summarizeTaskHistory([
    { taskId: 'pending-task', status: 'PENDING' },
    { taskId: 'completing-task', status: 'COMPLETING' },
    { taskId: 'completed-task', status: 'COMPLETED' },
  ]);
  assert.equal(activeHistory.activeTaskCount, 2);
  assert.deepEqual(activeHistory.activeTaskIds, [
    'pending-task',
    'completing-task',
  ]);
  assert.equal(activeHistory.historyTaskCount, 3);

  assert.throws(
    () => summarizeTaskHistory([{ taskId: 'unknown-task', status: 'UNKNOWN' }]),
    /unknown status UNKNOWN/u,
  );
  assert.throws(
    () => summarizeTaskHistory([{ taskId: '', status: 'PENDING' }]),
    /missing taskId/u,
  );
  assert.throws(
    () => summarizeTaskHistory(undefined),
    /must be an array/u,
  );
});

test('retained evidence binds contracts, tasks, audits and final active state', () => {
  assert.match(observer, /createHash\('sha256'\)/u);
  assert.match(observer, /crossClientSha256/u);
  assert.match(observer, /scenarioSha256/u);
  assert.match(observer, /taskId: task\.taskId/u);
  assert.match(observer, /auditEventIds/u);
  assert.match(observer, /cross-client handoff changed the governed instanceId/u);
  assert.match(observer, /finalInstance\.instance\.status !== 'COMPLETED'/u);
  assert.match(observer, /summarizeTaskHistory\(finalInstance\.tasks\)/u);
  assert.match(observer, /finalTaskSummary\.activeTaskCount !== 0/u);
  assert.match(observer, /taskHistoryCount: finalTaskSummary\.historyTaskCount/u);
  assert.match(observer, /taskStatusCounts: finalTaskSummary\.statusCounts/u);
  assert.doesNotMatch(observer, /finalInstance\.tasks\.length !== 0/u);
  assert.match(observer, /status: 'IN_PROGRESS'/u);
  assert.match(observer, /evidence\.status = 'PASSED'/u);
  assert.match(observer, /evidence\.status = 'FAILED'/u);
  assert.match(observer, /mode: 0o600/u);
  assert.match(observer, /renameSync\(temporary, path\)/u);
});

test('package scripts and permanent hygiene load the observer boundary', () => {
  assert.equal(
    packageJson.scripts?.['demo:runtime:plan'],
    'node scripts/product-readiness/demo-runtime-evidence.mjs plan --json',
  );
  assert.equal(
    packageJson.scripts?.['demo:runtime:observe'],
    'node scripts/product-readiness/demo-runtime-evidence.mjs observe',
  );
  assert.equal(
    packageJson.scripts?.['demo:runtime:check'],
    'node --test scripts/tests/product-readiness-cross-client-runtime-evidence-boundary.test.mjs',
  );
  assert.match(
    aggregate,
    /product-readiness-cross-client-runtime-evidence-boundary\.test\.mjs/u,
  );
});

test('guide separates observed backend evidence from browser and device proof', () => {
  for (const command of [
    'pnpm demo:runtime:plan',
    'pnpm demo:runtime:observe -- --confirm-interactive-run',
    'pnpm demo:client:pc -- --actor demo-manager --skip-install',
    'pnpm demo:client:h5 -- --actor demo-finance-reviewer --skip-install',
    'pnpm demo:client:wechat -- --actor demo-finance-approver-a --skip-install',
    'pnpm demo:client:wechat -- --actor demo-finance-approver-b --skip-install',
  ]) {
    assert.equal(guide.includes(command), true, `guide missing ${command}`);
  }
  assert.match(guide, /不会替用户点击/u);
  assert.match(guide, /只观察真实客户端操作之后的服务端状态迁移/u);
  assert.match(guide, /CROSS_CLIENT_SHARED_INSTANCE_OBSERVED/u);
  assert.match(guide, /AUTOMATED_BROWSER_E2E_NOT_EXECUTED/u);
  assert.match(guide, /WECHAT_PHYSICAL_DEVICE_NOT_VERIFIED/u);
  assert.match(guide, /CLIENT_SCREEN_RECORDING_NOT_INCLUDED/u);
  assert.match(guide, /不能单独证明/u);
});
