import { createHash } from 'node:crypto';
import { readFileSync, renameSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { expect, test } from '@playwright/test';

import {
  authoritativeActors,
  authoritativeTaskKeys,
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
}) => {
  const startedAt = new Date().toISOString();
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
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

    await expect(page.locator('.task-card').filter({ hasText: businessKey }).first())
      .toBeVisible();
    await page.screenshot({
      fullPage: true,
      path: resolve(evidenceDirectory, 'h5-payment-before.png'),
    });

    const approvalResponse = await clickH5Approval(
      page,
      {
        actorId: policy.actorId,
        businessKey,
        processInstanceId: expectedInstanceId,
        taskId: expectedPaymentTaskId,
      },
      '付款确认',
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

    await page.screenshot({
      fullPage: true,
      path: resolve(evidenceDirectory, 'h5-payment-after.png'),
    });
    const screenshots = [
      screenshotEvidence('h5-payment-before.png'),
      screenshotEvidence('h5-payment-after.png'),
    ];
    for (const screenshot of screenshots) {
      expect(sha256(resolve(evidenceDirectory, screenshot.file)))
        .toBe(screenshot.sha256);
    }

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
      screenshots,
      nonClaims: acceptance.nonClaims,
    });
  } finally {
    await context.close();
  }
});
