import { resolve } from 'node:path';

import { expect, test } from '@playwright/test';

import {
  businessKey,
  evidenceDirectory,
  h5Url,
  pendingForActor,
  pendingResponse,
  pcUrl,
  screenshotEvidence,
  selectedHeaders,
  startedInstance,
  tenantId,
  timeline,
  writeEvidence,
} from './product-readiness-pc-h5-runtime-api';
import {
  clickH5Approval,
  clickPcApproval,
  ensurePcLogin,
} from './product-readiness-pc-h5-runtime-ui';

test('PC manager and H5 finance reviewer advance the same real instance', async ({
  browser,
  request,
}) => {
  const startedAt = new Date().toISOString();
  const context = await browser.newContext();
  const pc = await context.newPage();
  const h5 = await context.newPage();

  await ensurePcLogin(pc);
  const pcPendingPromise = pendingResponse(pc, 'demo-manager');
  await pc.goto(pcUrl, { waitUntil: 'domcontentloaded' });
  const pcPending = await pcPendingPromise;
  expect(selectedHeaders(pcPending.response)).toMatchObject({
    operatorId: 'demo-manager',
    tenantId,
  });
  expect(pcPending.task.taskDefinitionKey).toBe('managerApproval');
  await expect(pc.getByText(businessKey, { exact: true }).first())
    .toBeVisible();
  await pc.screenshot({
    fullPage: true,
    path: resolve(evidenceDirectory, 'pc-manager-before.png'),
  });

  const pcApproval = await clickPcApproval(pc, pcPending.task.taskId);
  expect(selectedHeaders(pcApproval)).toMatchObject({
    operatorId: 'demo-manager',
    tenantId,
  });
  await pc.screenshot({
    fullPage: true,
    path: resolve(evidenceDirectory, 'pc-manager-after.png'),
  });

  const financeTasks = await pendingForActor(
    request,
    'demo-finance-reviewer',
  );
  expect(financeTasks).toHaveLength(1);
  expect(financeTasks[0]).toMatchObject({
    instanceId: pcPending.task.instanceId,
    taskDefinitionKey: 'financeReview',
  });

  const h5PendingPromise = pendingResponse(
    h5,
    'demo-finance-reviewer',
  );
  await h5.goto(h5Url, { waitUntil: 'domcontentloaded' });
  const h5Pending = await h5PendingPromise;
  expect(h5Pending.task.taskId).toBe(financeTasks[0].taskId);
  expect(h5Pending.task.instanceId).toBe(pcPending.task.instanceId);
  expect(selectedHeaders(h5Pending.response)).toMatchObject({
    operatorId: 'demo-finance-reviewer',
    tenantId,
  });
  await expect(h5.getByText(businessKey, { exact: true }).first())
    .toBeVisible();
  await h5.screenshot({
    fullPage: true,
    path: resolve(evidenceDirectory, 'h5-finance-before.png'),
  });

  const h5Approval = await clickH5Approval(h5, h5Pending.task.taskId);
  expect(selectedHeaders(h5Approval)).toMatchObject({
    operatorId: 'demo-finance-reviewer',
    tenantId,
  });
  await h5.waitForTimeout(750);
  await h5.screenshot({
    fullPage: true,
    path: resolve(evidenceDirectory, 'h5-finance-after.png'),
  });

  const [countersignA, countersignB, progress, instance] = await Promise.all([
    pendingForActor(request, 'demo-finance-approver-a'),
    pendingForActor(request, 'demo-finance-approver-b'),
    timeline(request, pcPending.task.instanceId),
    startedInstance(request),
  ]);
  expect(countersignA).toHaveLength(1);
  expect(countersignB).toHaveLength(1);
  for (const task of [...countersignA, ...countersignB]) {
    expect(task).toMatchObject({
      instanceId: pcPending.task.instanceId,
      taskDefinitionKey: 'financeCountersign',
    });
  }
  expect(new Set([
    countersignA[0].taskId,
    countersignB[0].taskId,
  ]).size).toBe(2);
  expect(instance).toMatchObject({
    currentTaskDefinitionKey: 'financeCountersign',
    instanceId: pcPending.task.instanceId,
    status: 'RUNNING',
  });

  const approvalEvents = progress.filter(item =>
    item.action === 'TASK_APPROVED'
      && [
        'demo-manager',
        'demo-finance-reviewer',
      ].includes(item.operatorId));
  expect(approvalEvents.filter(item =>
    item.operatorId === 'demo-manager')).toHaveLength(1);
  expect(approvalEvents.filter(item =>
    item.operatorId === 'demo-finance-reviewer')).toHaveLength(1);

  writeEvidence({
    schemaVersion: 1,
    evidenceKind: 'PC_H5_BROWSER_APPROVAL_HANDOFF_V1',
    claim: 'PC_H5_APPROVAL_HANDOFF_PASSED',
    commitSha: process.env.GITHUB_SHA || null,
    githubRunId: process.env.GITHUB_RUN_ID || null,
    startedAt,
    completedAt: new Date().toISOString(),
    tenantId,
    businessKey,
    instanceId: pcPending.task.instanceId,
    steps: [
      {
        client: 'pc',
        actorId: 'demo-manager',
        taskDefinitionKey: 'managerApproval',
        taskId: pcPending.task.taskId,
        request: selectedHeaders(pcApproval),
        auditEventId: approvalEvents.find(item =>
          item.operatorId === 'demo-manager')?.eventId,
      },
      {
        client: 'h5',
        actorId: 'demo-finance-reviewer',
        taskDefinitionKey: 'financeReview',
        taskId: h5Pending.task.taskId,
        request: selectedHeaders(h5Approval),
        auditEventId: approvalEvents.find(item =>
          item.operatorId === 'demo-finance-reviewer')?.eventId,
      },
    ],
    nextStage: {
      taskDefinitionKey: 'financeCountersign',
      actorIds: [
        'demo-finance-approver-a',
        'demo-finance-approver-b',
      ],
      taskIds: [
        countersignA[0].taskId,
        countersignB[0].taskId,
      ].sort(),
      instanceStatus: instance.status,
    },
    screenshots: [
      screenshotEvidence('pc-manager-before.png'),
      screenshotEvidence('pc-manager-after.png'),
      screenshotEvidence('h5-finance-before.png'),
      screenshotEvidence('h5-finance-after.png'),
    ],
    nonClaims: [
      'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
      'WECHAT_MINI_PROGRAM_RUNTIME_NOT_EXECUTED',
      'PC_H5_WECHAT_RUNTIME_NOT_EXECUTED',
      'BROWSER_COMPATIBILITY_NOT_VERIFIED',
      'ACCESSIBILITY_NOT_VERIFIED',
      'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
      'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
      'QUICK_START_10_MINUTES_NOT_EXECUTED',
    ],
  });

  await context.close();
});
