import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import type { APIRequestContext, Browser, Page, Response } from '@playwright/test';
import { expect } from '@playwright/test';

import type { CaptureBudget } from './product-readiness-capture-budget';
import { observeCaptureFailures } from './product-readiness-capture';
import {
  assignmentSource,
  authoritativeActors,
  authoritativeTaskKeys,
  backendOrigin,
  businessKey,
  exactApprovalApiPath,
  h5UrlForActor,
  pcUrl,
  selectedHeaders,
  tenantId,
} from './product-readiness-pc-h5-runtime-api';
import { ensurePcLogin } from './product-readiness-pc-h5-runtime-ui';

const observerId = authoritativeActors.financeReview;
const deciderId = authoritativeActors.financeCountersign[0];
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/u;
type EventType = 'TASK_COLLABORATION_ASSIGNED' | 'TASK_COLLABORATION_RESULT';
interface Policy {
  instanceId: string;
  taskId: string;
  taskName: string;
  mode: string;
  status: 'ACTIVE' | 'SATISFIED';
  reason: string;
  participants: Array<{ participantId: string; participantUserId: string; status: string; decidedAt?: string }>;
  progress: { approvedCount: number; pendingCount: number };
}
interface Notification {
  intentId: string;
  instanceId: string;
  taskId: string;
  eventType: EventType;
  channel: string;
  title: string;
  metadata: { route: string };
}

/** Real API fixture, isolated from the seeded purchase and its evidence receipt. */
export async function createCollaborationNotificationFixture(
  request: APIRequestContext,
  budget: CaptureBudget,
) {
  const token = randomUUID();
  const fixtureBusinessKey = `COLLAB-NOTIFICATION-${token}`;
  const reason = `Notification regression ${token}`;
  const scenario = JSON.parse(readFileSync(resolve(
    process.env.APPROVAL_DEMO_REPOSITORY_ROOT!, assignmentSource,
  ), 'utf8'));
  let instanceId: string | undefined;
  let taskId = '';
  let policy: Policy;
  const headers = (actorId: string) => {
    const requestId = `collaboration-browser-${randomUUID()}`;
    return {
      'X-Tenant-Id': tenantId,
      'X-Operator-Id': actorId,
      'X-Request-Id': requestId,
      'X-Trace-Id': requestId,
      'Idempotency-Key': requestId,
    };
  };
  const get = async <T>(path: string, actorId: string, status = 200): Promise<T> => {
    const response = await request.get(`${backendOrigin}${path}`, {
      headers: headers(actorId), timeout: budget.remaining(15_000),
    });
    expect(response.status(), path).toBe(status);
    return await response.json() as T;
  };
  const post = async <T>(path: string, actorId: string, data: unknown, status = 200): Promise<T> => {
    const response = await request.post(`${backendOrigin}${path}`, {
      headers: headers(actorId), data, timeout: budget.remaining(15_000),
    });
    expect(response.status(), path).toBe(status);
    return await response.json() as T;
  };
  const cleanup = async () => {
    if (!instanceId) return;
    const result = await post<{ instanceId: string; instanceStatus: string }>(
      `/api/approval/instances/${instanceId}/withdraw`, authoritativeActors.initiator,
      { comment: 'Remove the isolated collaboration notification fixture' },
    );
    expect(result).toMatchObject({ instanceId, instanceStatus: 'WITHDRAWN' });
  };
  const readPolicy = () => get<Policy>(`/api/approval/tasks/${taskId}/collaboration`, observerId);
  const participant = (actorId: string) => {
    const matches = policy.participants.filter(item => item.participantUserId === actorId);
    expect(matches).toHaveLength(1);
    return matches[0];
  };

  try {
    // Fail before writes if this disposable demo could emit external messages.
    for (const actorId of [authoritativeActors.initiator, authoritativeActors.managerApproval, observerId, deciderId]) {
      const preferences = await get<{ preferences: Array<{ channel: string; enabled: boolean }> }>(
        '/api/approval/notifications/preferences', actorId,
      );
      expect(preferences.preferences.filter(item => item.channel !== 'IN_APP' && item.enabled)).toEqual([]);
    }
    expect(fixtureBusinessKey).not.toBe(businessKey);
    const upload = await request.post(`${backendOrigin}/api/approval/attachments`, {
      headers: headers(authoritativeActors.initiator),
      multipart: { file: { name: `${fixtureBusinessKey}.txt`, mimeType: 'text/plain', buffer: Buffer.from(reason) } },
      timeout: budget.remaining(15_000),
    });
    expect(upload.status()).toBe(200);
    const { attachmentId } = await upload.json() as { attachmentId: string };
    expect(attachmentId).toMatch(uuid);
    const started = await post<{
      instanceId: string; status: string;
      activeTasks: Array<{ taskId: string; taskDefinitionKey: string; assigneeId: string }>;
    }>('/api/approval/instances/purchase-payment', authoritativeActors.initiator, {
      ...scenario.request,
      businessKey: fixtureBusinessKey,
      purchaseOrderReference: `PO-COLLAB-${token}`,
      attachmentIds: [attachmentId],
      assigneeRules: scenario.assigneeRules,
    });
    expect(started.instanceId).toMatch(uuid);
    instanceId = started.instanceId;
    expect(started.status).toBe('RUNNING');
    expect(started.activeTasks).toEqual([expect.objectContaining({
      taskDefinitionKey: authoritativeTaskKeys.managerApproval,
      assigneeId: authoritativeActors.managerApproval,
    })]);
    taskId = started.activeTasks[0].taskId;
    expect(taskId).toMatch(uuid);
    policy = await post<Policy>(`/api/approval/tasks/${taskId}/collaboration`, authoritativeActors.managerApproval, {
      connectorKey: scenario.assigneeRules.connectorKey,
      mode: 'ANY', reason,
      participants: [observerId, deciderId].map(value => ({ source: 'demo-directory', objectType: 'user', value, weight: 1 })),
    });
    expect(policy).toMatchObject({ taskId, instanceId, mode: 'ANY', status: 'ACTIVE', reason });
    expect(participant(observerId).status).toBe('PENDING');
    expect(participant(deciderId).status).toBe('PENDING');
  } catch (error) {
    await cleanup();
    throw error;
  }

  return {
    instanceId, taskId, observerId, reason,
    readPolicy,
    cleanup,
    async notification(eventType: EventType) {
      let matches: Notification[] = [];
      await expect.poll(async () => {
        const history = await get<{ items: Notification[] }>('/api/approval/notifications?limit=20&offset=0', observerId);
        matches = history.items.filter(item => item.taskId === taskId && item.eventType === eventType && item.channel === 'IN_APP');
        return matches.length;
      }, { timeout: budget.remaining(30_000), intervals: [500] }).toBe(1);
      expect(matches[0]).toMatchObject({
        instanceId, taskId, eventType,
        metadata: { route: `/approval/collaboration?taskId=${taskId}` },
      });
      return matches[0];
    },
    async satisfy() {
      policy = await post<Policy>(
        `/api/approval/collaboration/participants/${participant(deciderId).participantId}/decide`, deciderId,
        { decision: 'APPROVED', comment: reason },
      );
      expect(policy).toMatchObject({ taskId, instanceId, mode: 'ANY', status: 'SATISFIED' });
      expect(participant(deciderId).status).toBe('APPROVED');
      expect(participant(observerId)).toMatchObject({ status: 'CANCELED', decidedAt: null });
      return policy;
    },
    async assertReadOnlyBoundaries() {
      const ownerTasks = await get<{ items: Array<{ taskId: string; instanceId: string; businessKey: string }> }>(
        `/api/approval/tasks/pending?keyword=${encodeURIComponent(fixtureBusinessKey)}&limit=20&offset=0`, authoritativeActors.managerApproval,
      );
      expect(ownerTasks.items).toEqual([expect.objectContaining({ taskId, instanceId, businessKey: fixtureBusinessKey })]);
      const before = await readPolicy();
      // Pure collaborators gain neither raw purchase/form data nor owner writes.
      for (const path of [
        `/api/approval/instances/${instanceId}`,
        `/api/approval/instances/${instanceId}/form-snapshot`,
        `/api/approval/tasks/${taskId}/form-runtime`,
      ]) {
        expect(await get(path, observerId, 404)).toMatchObject({ code: 'APPROVAL_RESOURCE_NOT_FOUND', retryable: false });
      }
      expect(await post(`/api/approval/tasks/${taskId}/collaboration/participants`, observerId, {
        connectorKey: 'demo-directory', reason,
        participants: [{ source: 'demo-directory', objectType: 'user', value: authoritativeActors.financeCountersign[1], weight: 1 }],
      }, 403)).toMatchObject({ code: 'APPROVAL_TASK_COLLABORATION_UNAUTHORIZED' });
      if (before.status === 'SATISFIED') {
        expect(await post(`/api/approval/collaboration/participants/${participant(observerId).participantId}/decide`, observerId, {
          decision: 'APPROVED', comment: reason,
        }, 409)).toMatchObject({ code: 'APPROVAL_TASK_COLLABORATION_STATE_CONFLICT' });
      }
      expect(await readPolicy()).toEqual(before);
    },
  };
}

type Fixture = Awaited<ReturnType<typeof createCollaborationNotificationFixture>>;
function notificationUrl(client: 'pc' | 'h5') {
  const url = new URL(client === 'pc' ? pcUrl : h5UrlForActor(observerId));
  url.searchParams.set('demoOperator', observerId);
  if (client === 'pc') url.pathname = '/approval/notifications';
  else url.hash = '/pages/notification/index';
  return url.toString();
}
function actorResponse(response: Response, path: string) {
  return response.request().method() === 'GET'
    && exactApprovalApiPath(response.url(), path)
    && response.status() === 200
    && selectedHeaders(response).operatorId === observerId
    && selectedHeaders(response).tenantId === tenantId;
}

async function clickNotification(
  page: Page, client: 'pc' | 'h5', fixture: Fixture, eventType: EventType, budget: CaptureBudget,
) {
  const notification = await fixture.notification(eventType);
  const [historyResponse] = await Promise.all([
    page.waitForResponse(response => actorResponse(response, '/api/approval/notifications'), { timeout: budget.remaining(15_000) }),
    page.goto(notificationUrl(client), { waitUntil: 'domcontentloaded', timeout: budget.remaining(15_000) }),
  ]);
  const history = await historyResponse.json() as { items: Notification[] };
  const index = history.items.findIndex(item => item.intentId === notification.intentId);
  expect(index).toBeGreaterThanOrEqual(0);
  const rows = page.locator(client === 'pc' ? '.notification-item' : '.notification-card');
  await expect(rows).toHaveCount(history.items.length, { timeout: budget.remaining(15_000) });
  const row = rows.nth(index);
  await expect(row).toContainText(notification.title, { timeout: budget.remaining(15_000) });
  const [policyResponse, pendingResponse] = await Promise.all([
    page.waitForResponse(response => actorResponse(response, `/api/approval/tasks/${fixture.taskId}/collaboration`), { timeout: budget.remaining(15_000) }),
    page.waitForResponse(response => actorResponse(response, '/api/approval/collaboration/tasks/pending'), { timeout: budget.remaining(15_000) }),
    row.locator(client === 'pc' ? '.notification-main' : '.card-main').click({ timeout: budget.remaining(10_000) }),
  ]);
  const result = await policyResponse.json() as Policy;
  const satisfied = eventType === 'TASK_COLLABORATION_RESULT';
  expect(result).toMatchObject({ taskId: fixture.taskId, instanceId: fixture.instanceId, reason: fixture.reason, mode: 'ANY', status: satisfied ? 'SATISFIED' : 'ACTIVE' });
  expect(result.participants.find(item => item.participantUserId === observerId)?.status).toBe(satisfied ? 'CANCELED' : 'PENDING');
  await expect(page).toHaveURL(url => {
    const route = client === 'pc' ? url : new URL(url.hash.slice(1), url.origin);
    return route.pathname === (client === 'pc' ? '/approval/collaboration' : '/pages/collaboration/index')
      && route.searchParams.get('taskId') === fixture.taskId;
  }, { timeout: budget.remaining(15_000) });
  const summary = page.locator('.notification-summary');
  for (const value of ['通知协作摘要（只读）', result.taskName, fixture.reason, '任一通过', satisfied ? '已满足' : '协作中', satisfied ? '已取消' : '待处理']) {
    await expect(summary).toContainText(value, { timeout: budget.remaining(15_000) });
  }
  await expect(summary).toContainText(`${result.progress.approvedCount} 同意 / ${result.progress.pendingCount} 待处理`, { timeout: budget.remaining(15_000) });
  await expect(summary.locator('button, [role="button"], input, textarea, select, uni-button, wd-button')).toHaveCount(0, { timeout: budget.remaining(15_000) });
  await expect(page.locator('.task-button.active, .task-chip.active, .form-panel')).toHaveCount(0, { timeout: budget.remaining(15_000) });
  for (const label of ['发起协作', '追加协作人员', '减签']) {
    await expect(page.getByRole('button', { name: label, exact: true })).toHaveCount(0, { timeout: budget.remaining(15_000) });
  }
  const pending = await pendingResponse.json() as Array<{ taskId: string }>;
  expect(pending.filter(item => item.taskId === fixture.taskId)).toHaveLength(satisfied ? 0 : 1);
  if (satisfied) {
    await expect(page.getByText('暂无待处理的协作事项', { exact: true })).toBeVisible({ timeout: budget.remaining(15_000) });
    await expect(page.getByText('同意', { exact: true })).toHaveCount(0, { timeout: budget.remaining(15_000) });
    await expect(page.getByText('拒绝', { exact: true })).toHaveCount(0, { timeout: budget.remaining(15_000) });
  } else {
    // Existing pending DTO controls remain available, outside the read-only card.
    const pendingRow = page.locator(client === 'pc' ? '.el-table__row' : '.item-card').filter({ hasText: fixture.reason });
    for (const label of ['同意', '拒绝']) {
      const button = client === 'pc' ? pendingRow.getByRole('button', { name: label, exact: true })
        : pendingRow.locator('.wd-button').filter({ hasText: new RegExp(`^${label}$`, 'u') });
      await expect(button).toBeVisible({ timeout: budget.remaining(15_000) });
      await expect(button).toBeEnabled({ timeout: budget.remaining(15_000) });
    }
  }
}

export async function runCollaborationNotificationRegression(
  browser: Browser, request: APIRequestContext, budget: CaptureBudget,
) {
  const matrix = JSON.parse(readFileSync(resolve(
    process.env.APPROVAL_DEMO_REPOSITORY_ROOT!, 'config/demo/browser-accessibility-matrix.json',
  ), 'utf8'));
  const pcContext = await browser.newContext({ locale: matrix.locale, viewport: matrix.viewports.pc });
  const h5Context = await browser.newContext({ locale: matrix.locale, viewport: matrix.viewports.h5 });
  const pc = await pcContext.newPage();
  const h5 = await h5Context.newPage();
  const failures = [pc, h5].map(observeCaptureFailures);
  let fixture: Fixture | undefined;
  try {
    await ensurePcLogin(pc);
    fixture = await createCollaborationNotificationFixture(request, budget);
    await fixture.assertReadOnlyBoundaries();
    for (const eventType of ['TASK_COLLABORATION_ASSIGNED', 'TASK_COLLABORATION_RESULT'] as const) {
      if (eventType === 'TASK_COLLABORATION_RESULT') await fixture.satisfy();
      const beforeClicks = await fixture.readPolicy();
      await clickNotification(pc, 'pc', fixture, eventType, budget);
      await clickNotification(h5, 'h5', fixture, eventType, budget);
      expect(await fixture.readPolicy()).toEqual(beforeClicks);
      failures.forEach(observer => observer.assert());
      await fixture.assertReadOnlyBoundaries();
    }
  } finally {
    failures.forEach(observer => observer.dispose());
    try { if (fixture) await fixture.cleanup(); }
    finally { await Promise.all([pcContext.close(), h5Context.close()]); }
  }
}
