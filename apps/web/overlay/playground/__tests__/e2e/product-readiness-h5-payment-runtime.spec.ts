import { createHash } from 'node:crypto';
import { readFileSync, renameSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import type { APIRequestContext } from '@playwright/test';
import { expect, test } from '@playwright/test';
import type { CaptureBudget } from './product-readiness-capture-budget';
import { testCaptureBudget } from './product-readiness-capture-budget';
import { captureScreenshot, observeCaptureFailures, publishCaptureReceipt, readySurface } from './product-readiness-capture';

import {
  authoritativeActors,
  authoritativeTaskKeys,
  backendOrigin,
  businessKey,
  evidenceDirectory,
  pendingResponse,
  screenshotEvidence,
  selectedHeaders,
  taskActionResult,
  tenantId,
  timeline,
  waitForCompletedInstance,
  waitForPendingTaskToDisappear,
} from './product-readiness-pc-h5-runtime-api';
import { clickH5Approval } from './product-readiness-pc-h5-runtime-ui';

const captureStartedAt = performance.now();

function requiredEnvironment(name: string) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

const repositoryRoot = resolve(
  requiredEnvironment('APPROVAL_DEMO_REPOSITORY_ROOT'),
);
const acceptanceSource =
  'config/demo/purchase-payment-alpha-acceptance.json';
const acceptance = JSON.parse(readFileSync(
  resolve(repositoryRoot, acceptanceSource),
  'utf8',
)) as {
  paymentConfirmationAcceptance: {
    acceptanceClient: string;
    acceptanceMode: string;
    actorId: string;
    targetClient: string;
    taskDefinitionKey: string;
  };
  nonClaims: string[];
};
const policy = acceptance.paymentConfirmationAcceptance;
if (policy.taskDefinitionKey !== authoritativeTaskKeys.paymentConfirmation
  || policy.actorId !== authoritativeActors.paymentConfirmation
  || policy.targetClient !== 'wechat'
  || policy.acceptanceClient !== 'h5'
  || policy.acceptanceMode !== 'H5_MOBILE_SURROGATE') {
  throw new Error('payment confirmation acceptance policy is invalid');
}

const expectedInstanceId = requiredEnvironment('APPROVAL_DEMO_INSTANCE_ID');
const expectedPaymentTaskId = requiredEnvironment(
  'APPROVAL_DEMO_EXPECTED_PAYMENT_TASK_ID',
);
const h5BaseUrl = requiredEnvironment('APPROVAL_DEMO_H5_URL');

type TaskIdentity = { taskId: string; instanceId: string; businessKey: string; taskDefinitionKey: string };

// Read the actor's unfiltered lists, not the UI counters or a keyword-filtered total.
async function actorTaskList(request: APIRequestContext, mode: 'pending' | 'processed', budget: CaptureBudget) {
  const items: TaskIdentity[] = [];
  let total: number | undefined;
  while (true) {
    const offset = items.length;
    const response = await request.get(`${backendOrigin}/api/approval/tasks/${mode}?limit=20&offset=${offset}`, {
      headers: {
        Accept: 'application/json',
        'Cache-Control': 'no-store',
        'X-Operator-Id': policy.actorId,
        'X-Tenant-Id': tenantId,
        'X-Request-Id': `h5-payment-${mode}-${offset}-${Date.now()}`,
        'X-Trace-Id': `h5-payment-${mode}-${offset}`,
      },
      timeout: budget.remaining(15_000),
    });
    expect(response.status()).toBe(200);
    const payload = await response.json();
    const page = (payload.data ?? payload) as { items: TaskIdentity[]; total: number; hasMore: boolean; offset: number };
    expect(Number.isSafeInteger(page.total) && page.total >= 0).toBe(true);
    expect(Array.isArray(page.items)).toBe(true);
    expect(page.offset).toBe(offset);
    total ??= page.total;
    expect(page.total).toBe(total);
    items.push(...page.items);
    expect(items.length).toBeLessThanOrEqual(total);
    expect(new Set(items.map(item => item.taskId)).size).toBe(items.length);
    expect(page.hasMore).toBe(items.length < total);
    if (!page.hasMore) return { total, items };
    expect(page.items.length).toBeGreaterThan(0);
  }
}

function paymentTasks(items: TaskIdentity[]) {
  return items.filter(item => item.taskId === expectedPaymentTaskId);
}

function h5SurrogateUrl() {
  const url = new URL(h5BaseUrl);
  const hashPath = (url.hash || '#/pages/task/list').split('?')[0]
    || '#/pages/task/list';
  url.searchParams.set('demoOperator', policy.actorId);
  url.hash = hashPath;
  return url.toString();
}

function writeEvidence(value: unknown) {
  const target = resolve(
    evidenceDirectory,
    'h5-payment-runtime-evidence.json',
  );
  const temporary = `${target}.tmp`;
  writeFileSync(
    temporary,
    `${JSON.stringify(value, null, 2)}\n`,
    { encoding: 'utf8', mode: 0o600 },
  );
  renameSync(temporary, target);
}

function sha256(path: string) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

test('H5 mobile surrogate completes the governed payment confirmation', async ({
  browser,
  request,
}, testInfo) => {
  const budget = testCaptureBudget(testInfo, captureStartedAt);
  const startedAt = new Date().toISOString();
  const h5Viewport = (JSON.parse(readFileSync(resolve(
    repositoryRoot, 'config/demo/browser-accessibility-matrix.json',
  ), 'utf8')) as { viewports: { h5: { width: number; height: number } } }).viewports.h5;
  expect(h5Viewport).toEqual({ width: 390, height: 844 });
  const context = await browser.newContext({ viewport: h5Viewport });
  const page = await context.newPage();
  const failures = observeCaptureFailures(page);
  try {
    expect(page.viewportSize()).toEqual(h5Viewport);
    const [initialPending, initialProcessed] = await Promise.all([
      actorTaskList(request, 'pending', budget),
      actorTaskList(request, 'processed', budget),
    ]);
    expect(initialPending.total).toBe(1);
    expect(paymentTasks(initialPending.items)).toEqual([expect.objectContaining({
      taskId: expectedPaymentTaskId,
      instanceId: expectedInstanceId,
      businessKey,
      taskDefinitionKey: policy.taskDefinitionKey,
    })]);
    expect(paymentTasks(initialProcessed.items)).toHaveLength(0);
    const taskListBaseline = { pendingTotal: initialPending.total, processedTotal: initialProcessed.total };
    const [pending] = await Promise.all([
      pendingResponse(page, {
        actorId: policy.actorId,
        processInstanceId: expectedInstanceId,
        taskDefinitionKey: policy.taskDefinitionKey,
      }),
      page.goto(h5SurrogateUrl(), { waitUntil: 'domcontentloaded' }),
    ]);
    expect(pending.task).toEqual(expect.objectContaining({
      businessKey,
      instanceId: expectedInstanceId,
      taskDefinitionKey: policy.taskDefinitionKey,
      taskId: expectedPaymentTaskId,
    }));
    expect(selectedHeaders(pending.response)).toEqual({
      operatorId: policy.actorId,
      requestId: expect.any(String),
      tenantId,
      traceId: expect.any(String),
    });

    const beforeBudget = budget.limit(15_000);
    await expect(page.locator('.task-card').filter({ hasText: businessKey }).first())
      .toBeVisible({ timeout: beforeBudget.remaining(15_000) });
    const before = await readySurface(page, beforeBudget, { client: 'h5', kind: 'list', url: h5SurrogateUrl(), businessKey, taskId: expectedPaymentTaskId, instanceId: expectedInstanceId, pendingTotal: 1, processedTotal: taskListBaseline.processedTotal });
    failures.assert();
    await captureScreenshot(page, beforeBudget, resolve(evidenceDirectory, 'h5-payment-before.png'), { readiness: before, assertCurrent: async () => failures.assert() });

    const approvalResponse = await clickH5Approval(
      page,
      {
        actorId: policy.actorId,
        businessKey,
        processInstanceId: expectedInstanceId,
        taskId: expectedPaymentTaskId,
      },
      '付款确认',
      budget,
      async (actionBudget, assertCurrent) => {
        failures.assert();
        await captureScreenshot(page, actionBudget, resolve(evidenceDirectory, 'h5-payment-after.png'), { assertCurrent: async () => { failures.assert(); await assertCurrent(); } });
      },
    );
    const approvalHeaders = selectedHeaders(approvalResponse);
    expect(approvalHeaders).toEqual({
      operatorId: policy.actorId,
      requestId: expect.any(String),
      tenantId,
      traceId: expect.any(String),
    });
    const result = await taskActionResult(approvalResponse);
    expect(result).toEqual(expect.objectContaining({
      activeTasks: [],
      completedTaskId: expectedPaymentTaskId,
      instanceId: expectedInstanceId,
      instanceStatus: 'COMPLETED',
    }));

    const [, finalState] = await Promise.all([
      waitForPendingTaskToDisappear(
        request,
        policy.actorId,
        expectedPaymentTaskId,
      ),
      waitForCompletedInstance(request, expectedInstanceId),
    ]);
    const progress = await timeline(request, expectedInstanceId);
    const approvalEvents = progress.filter(item =>
      item.action === 'TASK_APPROVED'
        && item.operatorId === policy.actorId
        && item.requestId === approvalHeaders.requestId);
    expect(approvalEvents).toHaveLength(1);

    const [finalPending, finalProcessed] = await Promise.all([
      actorTaskList(request, 'pending', budget),
      actorTaskList(request, 'processed', budget),
    ]);
    expect(finalPending.total).toBe(0);
    expect(paymentTasks(finalPending.items)).toHaveLength(0);
    expect(finalProcessed.total).toBe(taskListBaseline.processedTotal + 1);
    expect(finalProcessed.items.map(item => item.taskId).sort()).toEqual(
      [...initialProcessed.items.map(item => item.taskId), expectedPaymentTaskId].sort(),
    );
    expect(paymentTasks(finalProcessed.items)).toEqual([expect.objectContaining({
      taskId: expectedPaymentTaskId,
      instanceId: expectedInstanceId,
      businessKey,
      taskDefinitionKey: policy.taskDefinitionKey,
    })]);

    const settledBudget = budget.limit(30_000);
    const settled = await readySurface(page, settledBudget, { client: 'h5', kind: 'list', url: h5SurrogateUrl(), absentTaskId: expectedPaymentTaskId, minimumRefresh: before.refresh + 1, pendingTotal: 0, processedTotal: taskListBaseline.processedTotal + 1 });
    failures.assert();
    await captureScreenshot(page, settledBudget, resolve(evidenceDirectory, 'h5-payment-settled.png'), { readiness: settled, assertCurrent: async () => failures.assert() });
    const screenshots = [
      { ...screenshotEvidence('h5-payment-before.png'), phase: 'READY_BEFORE_ACTION', readiness: before },
      { ...screenshotEvidence('h5-payment-after.png'), phase: 'IMMEDIATE_ACKNOWLEDGED_RESULT', successMessage: '审批已同意' },
      { ...screenshotEvidence('h5-payment-settled.png'), phase: 'SETTLED_DESTINATION', readiness: settled },
    ];
    for (const screenshot of screenshots) {
      expect(sha256(resolve(evidenceDirectory, screenshot.file)))
        .toBe(screenshot.sha256);
    }

    budget.remaining();
    failures.assert();
    const receiptPath = resolve(evidenceDirectory, 'h5-payment-runtime-evidence.json');
    publishCaptureReceipt(budget, receiptPath, () => {
      writeEvidence({
        schemaVersion: 1,
        evidenceKind: 'H5_PAYMENT_CONFIRMATION_SURROGATE_V1',
        status: 'PASSED',
        stageMarker: 'H5_PAYMENT_CONFIRMATION_STAGE_PASSED',
        commitSha: process.env.APPROVAL_DEMO_EXACT_HEAD_SHA || null,
        githubRunId: process.env.GITHUB_RUN_ID || null,
        startedAt,
        completedAt: new Date().toISOString(),
        acceptanceSource,
        targetClient: policy.targetClient,
        acceptanceClient: policy.acceptanceClient,
        acceptanceMode: policy.acceptanceMode,
        tenantId,
        businessKey,
        actorId: policy.actorId,
        taskDefinitionKey: policy.taskDefinitionKey,
        taskId: expectedPaymentTaskId,
        instanceId: expectedInstanceId,
        request: approvalHeaders,
        result,
        auditEventId: approvalEvents[0].eventId,
        auditRequestId: approvalEvents[0].requestId,
        finalState,
        taskListBaseline,
        screenshots,
        nonClaims: acceptance.nonClaims,
      });
    }, `${receiptPath}.tmp`);
  } finally {
    failures.dispose();
    await context.close();
  }
});
