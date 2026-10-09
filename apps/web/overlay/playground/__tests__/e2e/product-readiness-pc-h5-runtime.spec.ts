import { resolve } from 'node:path';

import { expect, test } from '@playwright/test';
import { testCaptureBudget } from './product-readiness-capture-budget';
import { captureScreenshot, observeCaptureFailures, publishCaptureReceipt, readySurface } from './product-readiness-capture';
import type { CapturePhase } from './product-readiness-capture';

import type {
  PendingTask,
  TaskActionResult,
  TimelineItem,
} from './product-readiness-pc-h5-runtime-api';
import {
  assignmentSource,
  authoritativeActors,
  authoritativeTaskKeys,
  businessKey,
  evidenceDirectory,
  h5UrlForActor,
  pendingAssignments,
  pendingResponse,
  pcUrl,
  screenshotEvidence,
  selectedHeaders,
  startedInstance,
  taskActionResult,
  tenantId,
  timeline,
  waitForPendingForActor,
  waitForPendingTaskToDisappear,
  waitForStartedInstance,
  writeEvidence,
} from './product-readiness-pc-h5-runtime-api';
import {
  attachPageRuntimeDiagnostics,
  writeRuntimeFailureDiagnostics,
} from './product-readiness-pc-h5-runtime-diagnostics';
import {
  clickH5Approval,
  clickPcApproval,
  ensurePcLogin,
} from './product-readiness-pc-h5-runtime-ui';

const captureStartedAt = performance.now();

const allSeedActors = [
  authoritativeActors.managerApproval,
  authoritativeActors.financeReview,
  ...authoritativeActors.financeCountersign,
  authoritativeActors.paymentConfirmation,
] as const;

type AssignmentMap = Record<string, PendingTask[]>;

function expectOnlyActorTask(
  assignments: AssignmentMap,
  actorId: string,
  taskId: string,
  taskDefinitionKey: string,
  processInstanceId: string,
) {
  for (const seedActor of allSeedActors) {
    if (seedActor === actorId) {
      expect(assignments[seedActor]).toEqual([
        expect.objectContaining({
          instanceId: processInstanceId,
          taskDefinitionKey,
          taskId,
        }),
      ]);
    } else {
      expect(assignments[seedActor]).toHaveLength(0);
    }
  }
}

function expectPendingTaskIdentity(actual: PendingTask, expected: PendingTask) {
  expect(actual).toEqual(expect.objectContaining({
    businessKey: expected.businessKey,
    instanceId: expected.instanceId,
    taskDefinitionKey: expected.taskDefinitionKey,
    taskId: expected.taskId,
  }));
}

function expectResponseIdentity(response: Parameters<typeof selectedHeaders>[0], actorId: string) {
  const headers = selectedHeaders(response);
  expect(headers).toEqual({
    operatorId: actorId,
    requestId: expect.any(String),
    tenantId,
    traceId: expect.any(String),
  });
  return headers;
}

function expectActionResult(
  result: TaskActionResult,
  taskId: string,
  processInstanceId: string,
  status: TaskActionResult['instanceStatus'],
) {
  expect(result).toEqual(expect.objectContaining({
    completedAt: expect.any(String),
    completedTaskId: taskId,
    instanceId: processInstanceId,
    instanceStatus: status,
  }));
}

function expectActiveTaskIds(result: TaskActionResult, tasks: PendingTask[]) {
  expect(result.activeTasks.map(task => task.taskId).sort())
    .toEqual(tasks.map(task => task.taskId).sort());
}

function approvalEvent(
  progress: TimelineItem[],
  actorId: string,
  requestId: string | undefined,
) {
  const matches = progress.filter(item =>
    item.action === 'TASK_APPROVED' && item.operatorId === actorId);
  expect(matches).toHaveLength(1);
  expect(matches[0].requestId).toBe(requestId);
  return matches[0];
}

async function expectH5BusinessCard(
  page: Parameters<typeof attachPageRuntimeDiagnostics>[0],
  budget: import('./product-readiness-capture-budget').CaptureBudget,
) {
  await expect(page.locator('.task-card').filter({ hasText: businessKey }).first())
    .toBeVisible({ timeout: budget.remaining(15_000) });
}

test('PC manager and H5 finance actors hand off the seeded instance to WeChat payment confirmation', async ({
  browser,
  request,
}, testInfo) => {
  const budget = testCaptureBudget(testInfo, captureStartedAt);
  const startedAt = new Date().toISOString();
  const context = await browser.newContext();
  const pc = await context.newPage();
  const h5Reviewer = await context.newPage();
  const h5CountersignA = await context.newPage();
  const h5CountersignB = await context.newPage();
  const pageDiagnostics = [
    attachPageRuntimeDiagnostics(
      pc,
      'pc',
      authoritativeActors.managerApproval,
    ),
    attachPageRuntimeDiagnostics(
      h5Reviewer,
      'h5',
      authoritativeActors.financeReview,
    ),
    attachPageRuntimeDiagnostics(
      h5CountersignA,
      'h5',
      authoritativeActors.financeCountersign[0],
    ),
    attachPageRuntimeDiagnostics(
      h5CountersignB,
      'h5',
      authoritativeActors.financeCountersign[1],
    ),
  ];
  const failures = [pc, h5Reviewer, h5CountersignA, h5CountersignB].map(observeCaptureFailures);
  const screenshots: unknown[] = [];
  const capture = async (page: typeof pc, file: string, phase: CapturePhase, readiness?: Awaited<ReturnType<typeof readySurface>>, captureBudget = budget, assertCurrent?: () => Promise<void>) => {
    failures.forEach(observer => observer.assert());
    await captureScreenshot(page, captureBudget, resolve(evidenceDirectory, file), { readiness, assertCurrent: async () => {
      failures.forEach(observer => observer.assert());
      if (assertCurrent) await assertCurrent();
    } });
    screenshots.push({ ...screenshotEvidence(file), phase, ...(readiness ? { readiness } : { successMessage: '审批已同意' }) });
  };
  let processInstanceId: string | undefined;

  try {
    await ensurePcLogin(pc);
    const [pcPending] = await Promise.all([
      pendingResponse(pc, {
        actorId: authoritativeActors.managerApproval,
        taskDefinitionKey: authoritativeTaskKeys.managerApproval,
      }),
      pc.goto(pcUrl, { waitUntil: 'domcontentloaded' }),
    ]);
    processInstanceId = pcPending.task.instanceId;
    expectResponseIdentity(
      pcPending.response,
      authoritativeActors.managerApproval,
    );
    expect(pcPending.task).toEqual(expect.objectContaining({
      businessKey,
      instanceId: processInstanceId,
      taskDefinitionKey: authoritativeTaskKeys.managerApproval,
    }));

    const managerState = await startedInstance(request);
    expect(managerState).toEqual(expect.objectContaining({
      currentTaskDefinitionKey: authoritativeTaskKeys.managerApproval,
      instanceId: processInstanceId,
      status: 'RUNNING',
    }));
    const managerAssignments = await pendingAssignments(
      request,
      allSeedActors,
    );
    expectOnlyActorTask(
      managerAssignments,
      authoritativeActors.managerApproval,
      pcPending.task.taskId,
      authoritativeTaskKeys.managerApproval,
      processInstanceId,
    );

    const surfaceBudget0 = budget.limit(15_000);
    await expect(pc.getByText(businessKey, { exact: true }).first())
      .toBeVisible({ timeout: surfaceBudget0.remaining(15_000) });
    const ready0 = await readySurface(pc, surfaceBudget0, { client: 'pc', kind: 'list', url: pcUrl, businessKey, taskId: pcPending.task.taskId, instanceId: processInstanceId, pendingTotal: 1, processedTotal: 0 });
    await capture(pc, 'pc-manager-before.png', 'READY_BEFORE_ACTION', ready0, surfaceBudget0);

    const pcApproval = await clickPcApproval(pc, {
      actorId: authoritativeActors.managerApproval,
      businessKey,
      processInstanceId,
      taskId: pcPending.task.taskId,
    }, budget, (actionBudget, assertCurrent) => capture(pc, 'pc-manager-after.png', 'IMMEDIATE_ACKNOWLEDGED_RESULT', undefined, actionBudget, assertCurrent));
    const pcApprovalHeaders = expectResponseIdentity(
      pcApproval,
      authoritativeActors.managerApproval,
    );
    const pcApprovalResult = await taskActionResult(pcApproval);
    expectActionResult(
      pcApprovalResult,
      pcPending.task.taskId,
      processInstanceId,
      'RUNNING',
    );

    const [, financeTask, financeState] = await Promise.all([
      waitForPendingTaskToDisappear(
        request,
        authoritativeActors.managerApproval,
        pcPending.task.taskId,
      ),
      waitForPendingForActor(request, {
        actorId: authoritativeActors.financeReview,
        processInstanceId,
        taskDefinitionKey: authoritativeTaskKeys.financeReview,
      }),
      waitForStartedInstance(
        request,
        processInstanceId,
        authoritativeTaskKeys.financeReview,
      ),
    ]);
    expectActiveTaskIds(pcApprovalResult, [financeTask]);
    const settledBudget0 = budget.limit(30_000);
    const settled0 = await readySurface(pc, settledBudget0, { client: 'pc', kind: 'list', url: pcUrl, absentTaskId: pcPending.task.taskId, minimumRefresh: ready0.refresh + 1, pendingTotal: 0, processedTotal: 1 });
    await capture(pc, 'pc-manager-settled.png', 'SETTLED_DESTINATION', settled0, settledBudget0);

    const financeAssignments = await pendingAssignments(
      request,
      allSeedActors,
    );
    expectOnlyActorTask(
      financeAssignments,
      authoritativeActors.financeReview,
      financeTask.taskId,
      authoritativeTaskKeys.financeReview,
      processInstanceId,
    );

    const [h5Pending] = await Promise.all([
      pendingResponse(h5Reviewer, {
        actorId: authoritativeActors.financeReview,
        processInstanceId,
        taskDefinitionKey: authoritativeTaskKeys.financeReview,
      }),
      h5Reviewer.goto(
        h5UrlForActor(authoritativeActors.financeReview),
        { waitUntil: 'domcontentloaded' },
      ),
    ]);
    expectPendingTaskIdentity(h5Pending.task, financeTask);
    expectResponseIdentity(
      h5Pending.response,
      authoritativeActors.financeReview,
    );
    const surfaceBudget1 = budget.limit(15_000);
    await expectH5BusinessCard(h5Reviewer, surfaceBudget1);
    const ready1 = await readySurface(h5Reviewer, surfaceBudget1, { client: 'h5', kind: 'list', url: h5UrlForActor(authoritativeActors.financeReview), businessKey, taskId: h5Pending.task.taskId, instanceId: processInstanceId, pendingTotal: 1, processedTotal: 0 });
    await capture(h5Reviewer, 'h5-finance-before.png', 'READY_BEFORE_ACTION', ready1, surfaceBudget1);

    const h5Approval = await clickH5Approval(
      h5Reviewer,
      {
        actorId: authoritativeActors.financeReview,
        businessKey,
        processInstanceId,
        taskId: h5Pending.task.taskId,
      },
      '财务审核',
      budget,
      (actionBudget, assertCurrent) => capture(h5Reviewer, 'h5-finance-after.png', 'IMMEDIATE_ACKNOWLEDGED_RESULT', undefined, actionBudget, assertCurrent),
    );
    const h5ApprovalHeaders = expectResponseIdentity(
      h5Approval,
      authoritativeActors.financeReview,
    );
    const h5ApprovalResult = await taskActionResult(h5Approval);
    expectActionResult(
      h5ApprovalResult,
      h5Pending.task.taskId,
      processInstanceId,
      'RUNNING',
    );

    const [, countersignA, countersignB, countersignState] = await Promise.all([
      waitForPendingTaskToDisappear(
        request,
        authoritativeActors.financeReview,
        h5Pending.task.taskId,
      ),
      waitForPendingForActor(request, {
        actorId: authoritativeActors.financeCountersign[0],
        processInstanceId,
        taskDefinitionKey: authoritativeTaskKeys.financeCountersign,
      }),
      waitForPendingForActor(request, {
        actorId: authoritativeActors.financeCountersign[1],
        processInstanceId,
        taskDefinitionKey: authoritativeTaskKeys.financeCountersign,
      }),
      waitForStartedInstance(
        request,
        processInstanceId,
        authoritativeTaskKeys.financeCountersign,
      ),
    ]);
    const settledBudget1 = budget.limit(30_000);
    const settled1 = await readySurface(h5Reviewer, settledBudget1, { client: 'h5', kind: 'list', url: h5UrlForActor(authoritativeActors.financeReview), absentTaskId: h5Pending.task.taskId, minimumRefresh: ready1.refresh + 1, pendingTotal: 0, processedTotal: 1 });
    await capture(h5Reviewer, 'h5-finance-settled.png', 'SETTLED_DESTINATION', settled1, settledBudget1);

    expect(countersignA).toEqual(expect.objectContaining({
      instanceId: processInstanceId,
      taskDefinitionKey: authoritativeTaskKeys.financeCountersign,
    }));
    expect(countersignB).toEqual(expect.objectContaining({
      instanceId: processInstanceId,
      taskDefinitionKey: authoritativeTaskKeys.financeCountersign,
    }));
    expect(countersignA.taskId).not.toBe(countersignB.taskId);
    expectActiveTaskIds(h5ApprovalResult, [countersignA, countersignB]);

    const countersignAssignments = await pendingAssignments(
      request,
      allSeedActors,
    );
    expect(countersignAssignments[authoritativeActors.managerApproval])
      .toHaveLength(0);
    expect(countersignAssignments[authoritativeActors.financeReview])
      .toHaveLength(0);
    expect(
      countersignAssignments[authoritativeActors.financeCountersign[0]],
    ).toEqual([
      expect.objectContaining({
        instanceId: processInstanceId,
        taskDefinitionKey: authoritativeTaskKeys.financeCountersign,
        taskId: countersignA.taskId,
      }),
    ]);
    expect(
      countersignAssignments[authoritativeActors.financeCountersign[1]],
    ).toEqual([
      expect.objectContaining({
        instanceId: processInstanceId,
        taskDefinitionKey: authoritativeTaskKeys.financeCountersign,
        taskId: countersignB.taskId,
      }),
    ]);
    expect(countersignAssignments[authoritativeActors.paymentConfirmation])
      .toHaveLength(0);

    const [h5CountersignAPending] = await Promise.all([
      pendingResponse(h5CountersignA, {
        actorId: authoritativeActors.financeCountersign[0],
        processInstanceId,
        taskDefinitionKey: authoritativeTaskKeys.financeCountersign,
      }),
      h5CountersignA.goto(
        h5UrlForActor(authoritativeActors.financeCountersign[0]),
        { waitUntil: 'domcontentloaded' },
      ),
    ]);
    expectPendingTaskIdentity(h5CountersignAPending.task, countersignA);
    expectResponseIdentity(
      h5CountersignAPending.response,
      authoritativeActors.financeCountersign[0],
    );
    const surfaceBudget2 = budget.limit(15_000);
    await expectH5BusinessCard(h5CountersignA, surfaceBudget2);
    const ready2 = await readySurface(h5CountersignA, surfaceBudget2, { client: 'h5', kind: 'list', url: h5UrlForActor(authoritativeActors.financeCountersign[0]), businessKey, taskId: countersignA.taskId, instanceId: processInstanceId, pendingTotal: 1, processedTotal: 0 });
    await capture(h5CountersignA, 'h5-countersign-a-before.png', 'READY_BEFORE_ACTION', ready2, surfaceBudget2);

    const countersignAApproval = await clickH5Approval(
      h5CountersignA,
      {
        actorId: authoritativeActors.financeCountersign[0],
        businessKey,
        processInstanceId,
        taskId: countersignA.taskId,
      },
      '财务会签',
      budget,
      (actionBudget, assertCurrent) => capture(h5CountersignA, 'h5-countersign-a-after.png', 'IMMEDIATE_ACKNOWLEDGED_RESULT', undefined, actionBudget, assertCurrent),
    );
    const countersignAHeaders = expectResponseIdentity(
      countersignAApproval,
      authoritativeActors.financeCountersign[0],
    );
    const countersignAResult = await taskActionResult(countersignAApproval);
    expectActionResult(
      countersignAResult,
      countersignA.taskId,
      processInstanceId,
      'RUNNING',
    );
    expectActiveTaskIds(countersignAResult, [countersignB]);
    await waitForPendingTaskToDisappear(
      request,
      authoritativeActors.financeCountersign[0],
      countersignA.taskId,
    );
    const afterCountersignAState = await startedInstance(request);
    expect(afterCountersignAState).toEqual(expect.objectContaining({
      currentTaskDefinitionKey: authoritativeTaskKeys.financeCountersign,
      instanceId: processInstanceId,
      status: 'RUNNING',
    }));
    const afterCountersignAAssignments = await pendingAssignments(
      request,
      allSeedActors,
    );
    expectOnlyActorTask(
      afterCountersignAAssignments,
      authoritativeActors.financeCountersign[1],
      countersignB.taskId,
      authoritativeTaskKeys.financeCountersign,
      processInstanceId,
    );
    const settledBudget2 = budget.limit(30_000);
    const settled2 = await readySurface(h5CountersignA, settledBudget2, { client: 'h5', kind: 'list', url: h5UrlForActor(authoritativeActors.financeCountersign[0]), absentTaskId: countersignA.taskId, minimumRefresh: ready2.refresh + 1, pendingTotal: 0, processedTotal: 1 });
    await capture(h5CountersignA, 'h5-countersign-a-settled.png', 'SETTLED_DESTINATION', settled2, settledBudget2);

    const [h5CountersignBPending] = await Promise.all([
      pendingResponse(h5CountersignB, {
        actorId: authoritativeActors.financeCountersign[1],
        processInstanceId,
        taskDefinitionKey: authoritativeTaskKeys.financeCountersign,
      }),
      h5CountersignB.goto(
        h5UrlForActor(authoritativeActors.financeCountersign[1]),
        { waitUntil: 'domcontentloaded' },
      ),
    ]);
    expectPendingTaskIdentity(h5CountersignBPending.task, countersignB);
    expectResponseIdentity(
      h5CountersignBPending.response,
      authoritativeActors.financeCountersign[1],
    );
    const surfaceBudget3 = budget.limit(15_000);
    await expectH5BusinessCard(h5CountersignB, surfaceBudget3);
    const ready3 = await readySurface(h5CountersignB, surfaceBudget3, { client: 'h5', kind: 'list', url: h5UrlForActor(authoritativeActors.financeCountersign[1]), businessKey, taskId: countersignB.taskId, instanceId: processInstanceId, pendingTotal: 1, processedTotal: 0 });
    await capture(h5CountersignB, 'h5-countersign-b-before.png', 'READY_BEFORE_ACTION', ready3, surfaceBudget3);

    const countersignBApproval = await clickH5Approval(
      h5CountersignB,
      {
        actorId: authoritativeActors.financeCountersign[1],
        businessKey,
        processInstanceId,
        taskId: countersignB.taskId,
      },
      '财务会签',
      budget,
      (actionBudget, assertCurrent) => capture(h5CountersignB, 'h5-countersign-b-after.png', 'IMMEDIATE_ACKNOWLEDGED_RESULT', undefined, actionBudget, assertCurrent),
    );
    const countersignBHeaders = expectResponseIdentity(
      countersignBApproval,
      authoritativeActors.financeCountersign[1],
    );
    const countersignBResult = await taskActionResult(countersignBApproval);
    expectActionResult(
      countersignBResult,
      countersignB.taskId,
      processInstanceId,
      'RUNNING',
    );

    const [, paymentTask, awaitingPaymentState] = await Promise.all([
      waitForPendingTaskToDisappear(
        request,
        authoritativeActors.financeCountersign[1],
        countersignB.taskId,
      ),
      waitForPendingForActor(request, {
        actorId: authoritativeActors.paymentConfirmation,
        processInstanceId,
        taskDefinitionKey: authoritativeTaskKeys.paymentConfirmation,
      }),
      waitForStartedInstance(
        request,
        processInstanceId,
        authoritativeTaskKeys.paymentConfirmation,
      ),
    ]);
    expectActiveTaskIds(countersignBResult, [paymentTask]);
    const awaitingPaymentAssignments = await pendingAssignments(
      request,
      allSeedActors,
    );
    expectOnlyActorTask(
      awaitingPaymentAssignments,
      authoritativeActors.paymentConfirmation,
      paymentTask.taskId,
      authoritativeTaskKeys.paymentConfirmation,
      processInstanceId,
    );
    const settledBudget3 = budget.limit(30_000);
    const settled3 = await readySurface(h5CountersignB, settledBudget3, { client: 'h5', kind: 'list', url: h5UrlForActor(authoritativeActors.financeCountersign[1]), absentTaskId: countersignB.taskId, minimumRefresh: ready3.refresh + 1, pendingTotal: 0, processedTotal: 1 });
    await capture(h5CountersignB, 'h5-countersign-b-settled.png', 'SETTLED_DESTINATION', settled3, settledBudget3);

    const taskIds = [
      pcPending.task.taskId,
      h5Pending.task.taskId,
      countersignA.taskId,
      countersignB.taskId,
      paymentTask.taskId,
    ];
    expect(new Set(taskIds).size).toBe(taskIds.length);

    const progress = await timeline(request, processInstanceId);
    const managerEvent = approvalEvent(
      progress,
      authoritativeActors.managerApproval,
      pcApprovalHeaders.requestId,
    );
    const financeReviewEvent = approvalEvent(
      progress,
      authoritativeActors.financeReview,
      h5ApprovalHeaders.requestId,
    );
    const countersignAEvent = approvalEvent(
      progress,
      authoritativeActors.financeCountersign[0],
      countersignAHeaders.requestId,
    );
    const countersignBEvent = approvalEvent(
      progress,
      authoritativeActors.financeCountersign[1],
      countersignBHeaders.requestId,
    );

    budget.remaining();
    failures.forEach(observer => observer.assert());
    const receiptPath = resolve(evidenceDirectory, 'pc-h5-runtime-evidence.json');
    publishCaptureReceipt(budget, receiptPath, () => {
      writeEvidence({
        schemaVersion: 1,
        evidenceKind: 'PC_H5_BROWSER_APPROVAL_HANDOFF_V1',
        claim: 'PC_H5_APPROVAL_HANDOFF_PASSED',
        commitSha: process.env.APPROVAL_DEMO_EXACT_HEAD_SHA
          || process.env.GITHUB_SHA
          || null,
        githubRunId: process.env.GITHUB_RUN_ID || null,
        startedAt,
        completedAt: new Date().toISOString(),
        tenantId,
        businessKey,
        instanceId: processInstanceId,
        instanceOrigin: 'DETERMINISTIC_BACKEND_SEED',
        assignmentEvidence: {
          source: assignmentSource,
          semantics: 'operator-scoped real pending task visibility',
          authoritativeActors,
          authoritativeTaskKeys,
          managerStage: managerAssignments,
          financeReviewStage: financeAssignments,
          financeCountersignStage: countersignAssignments,
          afterCountersignA: afterCountersignAAssignments,
          awaitingPayment: awaitingPaymentAssignments,
        },
        processStates: {
          beforeManagerApproval: managerState,
          afterManagerApproval: financeState,
          afterFinanceReview: countersignState,
          afterCountersignA: afterCountersignAState,
          afterCountersignB: awaitingPaymentState,
        },
        steps: [
          {
            client: 'pc',
            actorId: authoritativeActors.managerApproval,
            taskDefinitionKey: authoritativeTaskKeys.managerApproval,
            taskId: pcPending.task.taskId,
            request: pcApprovalHeaders,
            result: pcApprovalResult,
            auditEventId: managerEvent.eventId,
            auditRequestId: managerEvent.requestId,
          },
          {
            client: 'h5',
            actorId: authoritativeActors.financeReview,
            taskDefinitionKey: authoritativeTaskKeys.financeReview,
            taskId: h5Pending.task.taskId,
            request: h5ApprovalHeaders,
            result: h5ApprovalResult,
            auditEventId: financeReviewEvent.eventId,
            auditRequestId: financeReviewEvent.requestId,
          },
          {
            client: 'h5',
            actorId: authoritativeActors.financeCountersign[0],
            taskDefinitionKey: authoritativeTaskKeys.financeCountersign,
            taskId: countersignA.taskId,
            request: countersignAHeaders,
            result: countersignAResult,
            auditEventId: countersignAEvent.eventId,
            auditRequestId: countersignAEvent.requestId,
          },
          {
            client: 'h5',
            actorId: authoritativeActors.financeCountersign[1],
            taskDefinitionKey: authoritativeTaskKeys.financeCountersign,
            taskId: countersignB.taskId,
            request: countersignBHeaders,
            result: countersignBResult,
            auditEventId: countersignBEvent.eventId,
            auditRequestId: countersignBEvent.requestId,
          },
        ],
        countersignStage: {
          taskDefinitionKey: authoritativeTaskKeys.financeCountersign,
          actorIds: [...authoritativeActors.financeCountersign],
          taskIds: [countersignA.taskId, countersignB.taskId].sort(),
        },
        paymentHandoff: {
          client: 'wechat',
          actorId: authoritativeActors.paymentConfirmation,
          taskDefinitionKey: authoritativeTaskKeys.paymentConfirmation,
          taskId: paymentTask.taskId,
        },
        finalState: awaitingPaymentState,
        screenshots,
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
    }, `${receiptPath}.tmp`);
  } catch (error) {
    try {
      await writeRuntimeFailureDiagnostics({
        error,
        pages: pageDiagnostics,
        processInstanceId,
        request,
      });
    } catch (diagnosticError) {
      const detail = diagnosticError instanceof Error
        ? diagnosticError.message
        : String(diagnosticError);
      throw new Error(
        `${error instanceof Error ? error.message : String(error)}; `
          + `runtime diagnostics failed: ${detail}`,
        { cause: error },
      );
    }
    throw error;
  } finally {
    failures.forEach(observer => observer.dispose());
    await context.close();
  }
});
