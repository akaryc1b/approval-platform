import { resolve } from 'node:path';

import { expect, test } from '@playwright/test';

import type {
  PendingTask,
  TaskActionResult,
  TimelineItem,
} from './product-readiness-pc-h5-runtime-api';
import {
  assignmentSource,
  authoritativeActors,
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
  waitForCompletedInstance,
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

const allSeedActors = [
  authoritativeActors.managerApproval,
  authoritativeActors.financeReview,
  ...authoritativeActors.financeCountersign,
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

function expectNoActorTasks(assignments: AssignmentMap) {
  for (const seedActor of allSeedActors) {
    expect(assignments[seedActor]).toHaveLength(0);
  }
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

async function capture(page: Parameters<typeof attachPageRuntimeDiagnostics>[0], file: string) {
  await page.screenshot({
    fullPage: true,
    path: resolve(evidenceDirectory, file),
  });
}

async function expectH5BusinessCard(
  page: Parameters<typeof attachPageRuntimeDiagnostics>[0],
) {
  await expect(page.locator('.task-card').filter({ hasText: businessKey }).first())
    .toBeVisible();
}

test('PC manager and H5 finance actors complete the same seeded approval instance', async ({
  browser,
  request,
}) => {
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
  let processInstanceId: string | undefined;

  try {
    await ensurePcLogin(pc);
    const [pcPending] = await Promise.all([
      pendingResponse(pc, {
        actorId: authoritativeActors.managerApproval,
        taskDefinitionKey: 'managerApproval',
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
      taskDefinitionKey: 'managerApproval',
    }));

    const managerState = await startedInstance(request);
    expect(managerState).toEqual(expect.objectContaining({
      currentTaskDefinitionKey: 'managerApproval',
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
      'managerApproval',
      processInstanceId,
    );

    await expect(pc.getByText(businessKey, { exact: true }).first())
      .toBeVisible();
    await capture(pc, 'pc-manager-before.png');

    const pcApproval = await clickPcApproval(pc, {
      actorId: authoritativeActors.managerApproval,
      businessKey,
      processInstanceId,
      taskId: pcPending.task.taskId,
    });
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
        taskDefinitionKey: 'financeReview',
      }),
      waitForStartedInstance(
        request,
        processInstanceId,
        'financeReview',
      ),
    ]);
    expectActiveTaskIds(pcApprovalResult, [financeTask]);
    await capture(pc, 'pc-manager-after.png');

    const financeAssignments = await pendingAssignments(
      request,
      allSeedActors,
    );
    expectOnlyActorTask(
      financeAssignments,
      authoritativeActors.financeReview,
      financeTask.taskId,
      'financeReview',
      processInstanceId,
    );

    const [h5Pending] = await Promise.all([
      pendingResponse(h5Reviewer, {
        actorId: authoritativeActors.financeReview,
        processInstanceId,
        taskDefinitionKey: 'financeReview',
      }),
      h5Reviewer.goto(
        h5UrlForActor(authoritativeActors.financeReview),
        { waitUntil: 'domcontentloaded' },
      ),
    ]);
    expect(h5Pending.task).toEqual(financeTask);
    expectResponseIdentity(
      h5Pending.response,
      authoritativeActors.financeReview,
    );
    await expectH5BusinessCard(h5Reviewer);
    await capture(h5Reviewer, 'h5-finance-before.png');

    const h5Approval = await clickH5Approval(
      h5Reviewer,
      {
        actorId: authoritativeActors.financeReview,
        businessKey,
        processInstanceId,
        taskId: h5Pending.task.taskId,
      },
      '财务审核',
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
        taskDefinitionKey: 'financeCountersign',
      }),
      waitForPendingForActor(request, {
        actorId: authoritativeActors.financeCountersign[1],
        processInstanceId,
        taskDefinitionKey: 'financeCountersign',
      }),
      waitForStartedInstance(
        request,
        processInstanceId,
        'financeCountersign',
      ),
    ]);
    await capture(h5Reviewer, 'h5-finance-after.png');

    expect(countersignA).toEqual(expect.objectContaining({
      instanceId: processInstanceId,
      taskDefinitionKey: 'financeCountersign',
    }));
    expect(countersignB).toEqual(expect.objectContaining({
      instanceId: processInstanceId,
      taskDefinitionKey: 'financeCountersign',
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
        taskDefinitionKey: 'financeCountersign',
        taskId: countersignA.taskId,
      }),
    ]);
    expect(
      countersignAssignments[authoritativeActors.financeCountersign[1]],
    ).toEqual([
      expect.objectContaining({
        instanceId: processInstanceId,
        taskDefinitionKey: 'financeCountersign',
        taskId: countersignB.taskId,
      }),
    ]);

    const [h5CountersignAPending] = await Promise.all([
      pendingResponse(h5CountersignA, {
        actorId: authoritativeActors.financeCountersign[0],
        processInstanceId,
        taskDefinitionKey: 'financeCountersign',
      }),
      h5CountersignA.goto(
        h5UrlForActor(authoritativeActors.financeCountersign[0]),
        { waitUntil: 'domcontentloaded' },
      ),
    ]);
    expect(h5CountersignAPending.task).toEqual(countersignA);
    expectResponseIdentity(
      h5CountersignAPending.response,
      authoritativeActors.financeCountersign[0],
    );
    await expectH5BusinessCard(h5CountersignA);
    await capture(h5CountersignA, 'h5-countersign-a-before.png');

    const countersignAApproval = await clickH5Approval(
      h5CountersignA,
      {
        actorId: authoritativeActors.financeCountersign[0],
        businessKey,
        processInstanceId,
        taskId: countersignA.taskId,
      },
      '财务会签',
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
      currentTaskDefinitionKey: 'financeCountersign',
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
      'financeCountersign',
      processInstanceId,
    );
    await capture(h5CountersignA, 'h5-countersign-a-after.png');

    const [h5CountersignBPending] = await Promise.all([
      pendingResponse(h5CountersignB, {
        actorId: authoritativeActors.financeCountersign[1],
        processInstanceId,
        taskDefinitionKey: 'financeCountersign',
      }),
      h5CountersignB.goto(
        h5UrlForActor(authoritativeActors.financeCountersign[1]),
        { waitUntil: 'domcontentloaded' },
      ),
    ]);
    expect(h5CountersignBPending.task).toEqual(countersignB);
    expectResponseIdentity(
      h5CountersignBPending.response,
      authoritativeActors.financeCountersign[1],
    );
    await expectH5BusinessCard(h5CountersignB);
    await capture(h5CountersignB, 'h5-countersign-b-before.png');

    const countersignBApproval = await clickH5Approval(
      h5CountersignB,
      {
        actorId: authoritativeActors.financeCountersign[1],
        businessKey,
        processInstanceId,
        taskId: countersignB.taskId,
      },
      '财务会签',
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
      'COMPLETED',
    );
    expectActiveTaskIds(countersignBResult, []);

    const [, completedState] = await Promise.all([
      waitForPendingTaskToDisappear(
        request,
        authoritativeActors.financeCountersign[1],
        countersignB.taskId,
      ),
      waitForCompletedInstance(request, processInstanceId),
    ]);
    const completedAssignments = await pendingAssignments(
      request,
      allSeedActors,
    );
    expectNoActorTasks(completedAssignments);
    await capture(h5CountersignB, 'h5-countersign-b-after.png');

    const taskIds = [
      pcPending.task.taskId,
      h5Pending.task.taskId,
      countersignA.taskId,
      countersignB.taskId,
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
        managerStage: managerAssignments,
        financeReviewStage: financeAssignments,
        financeCountersignStage: countersignAssignments,
        afterCountersignA: afterCountersignAAssignments,
        completed: completedAssignments,
      },
      processStates: {
        beforeManagerApproval: managerState,
        afterManagerApproval: financeState,
        afterFinanceReview: countersignState,
        afterCountersignA: afterCountersignAState,
        afterCountersignB: completedState,
      },
      steps: [
        {
          client: 'pc',
          actorId: authoritativeActors.managerApproval,
          taskDefinitionKey: 'managerApproval',
          taskId: pcPending.task.taskId,
          request: pcApprovalHeaders,
          result: pcApprovalResult,
          auditEventId: managerEvent.eventId,
          auditRequestId: managerEvent.requestId,
        },
        {
          client: 'h5',
          actorId: authoritativeActors.financeReview,
          taskDefinitionKey: 'financeReview',
          taskId: h5Pending.task.taskId,
          request: h5ApprovalHeaders,
          result: h5ApprovalResult,
          auditEventId: financeReviewEvent.eventId,
          auditRequestId: financeReviewEvent.requestId,
        },
        {
          client: 'h5',
          actorId: authoritativeActors.financeCountersign[0],
          taskDefinitionKey: 'financeCountersign',
          taskId: countersignA.taskId,
          request: countersignAHeaders,
          result: countersignAResult,
          auditEventId: countersignAEvent.eventId,
          auditRequestId: countersignAEvent.requestId,
        },
        {
          client: 'h5',
          actorId: authoritativeActors.financeCountersign[1],
          taskDefinitionKey: 'financeCountersign',
          taskId: countersignB.taskId,
          request: countersignBHeaders,
          result: countersignBResult,
          auditEventId: countersignBEvent.eventId,
          auditRequestId: countersignBEvent.requestId,
        },
      ],
      countersignStage: {
        taskDefinitionKey: 'financeCountersign',
        actorIds: [...authoritativeActors.financeCountersign],
        taskIds: [countersignA.taskId, countersignB.taskId].sort(),
      },
      finalState: completedState,
      screenshots: [
        screenshotEvidence('pc-manager-before.png'),
        screenshotEvidence('pc-manager-after.png'),
        screenshotEvidence('h5-finance-before.png'),
        screenshotEvidence('h5-finance-after.png'),
        screenshotEvidence('h5-countersign-a-before.png'),
        screenshotEvidence('h5-countersign-a-after.png'),
        screenshotEvidence('h5-countersign-b-before.png'),
        screenshotEvidence('h5-countersign-b-after.png'),
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
    await context.close();
  }
});
