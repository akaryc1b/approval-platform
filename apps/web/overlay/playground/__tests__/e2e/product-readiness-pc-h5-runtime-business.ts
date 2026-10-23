/** Real local-demo UI commands, independently read back from persisted backend views.
 * No route mocks, API-created instances, injected page state or production identity claim.
 * The existing parent process owns the disposable database and five-minute deadline.
 */
import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { APIRequestContext, Browser, Locator, Page, Response } from '@playwright/test';
import { expect, test } from '@playwright/test';
import type { CaptureBudget } from './product-readiness-capture-budget';
import { observeCaptureFailures } from './product-readiness-capture';
import { authoritativeActors, backendOrigin, exactApprovalApiPath, selectedHeaders, tenantId } from './product-readiness-pc-h5-runtime-api';

type Task = { taskId: string; instanceId: string; taskDefinitionKey: string; businessKey: string };
type Event = { eventId: string; action: string; requestId: string; operatorId: string };
const actors = [...new Set([authoritativeActors.initiator, authoritativeActors.managerApproval,
  authoritativeActors.financeReview, ...authoritativeActors.financeCountersign])];
const unwrap = (value: any) => value?.data ?? value;
const rows = (value: any): any[] => Array.isArray(unwrap(value)) ? unwrap(value) : unwrap(value).items;

export async function runBusinessLifecycle(
  browser: Browser, request: APIRequestContext, budget: CaptureBudget, highAmount: boolean,
) {
  const employee = authoritativeActors.initiator, manager = authoritativeActors.managerApproval;
  const key = `UI-LIFECYCLE-${randomUUID()}`, amount = highAmount ? '10000.00' : '9999.99';
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  page.setDefaultTimeout(budget.remaining(15_000));
  const failures = observeCaptureFailures(page);
  const commands: Array<{ path: string; actor: string; requestId: string }> = [];
  let instanceId = '';
  let attachmentIds: string[] = [];
  page.on('request', value => {
    const url = new URL(value.url());
    if (value.method() === 'POST' && url.pathname.includes('/approval/')) {
      const headers = value.headers();
      commands.push({ path: url.pathname, actor: headers['x-operator-id'], requestId: headers['x-request-id'] });
    }
  });
  const get = async (path: string, actor = employee) => {
    const id = `business-read-${randomUUID()}`;
    const response = await request.get(`${backendOrigin}/api/approval${path}`, {
      headers: { 'X-Tenant-Id': tenantId, 'X-Operator-Id': actor, 'X-Request-Id': id, 'X-Trace-Id': id },
      timeout: budget.remaining(10_000),
    });
    expect(response.status()).toBe(200);
    return unwrap(await response.json());
  };
  const pending = async (actor: string): Promise<Task[]> => rows(await get('/tasks/pending?limit=100&offset=0', actor))
    .filter((item: Task) => item.instanceId === instanceId);
  const started = async () => rows(await get(`/instances/started?keyword=${encodeURIComponent(key)}&limit=100&offset=0`))
    .filter(item => item.businessKey === key);
  const events = async (): Promise<Event[]> => rows(await get(`/instances/${instanceId}/timeline`));
  const audit = async (response: Response, actor: string, action: string) => {
    const headers = selectedHeaders(response);
    expect(headers).toMatchObject({ operatorId: actor, tenantId, requestId: expect.any(String), traceId: expect.any(String) });
    expect(headers.requestId.length).toBeGreaterThan(0);
    await expect(async () => {
      const matches = (await events()).filter(item => item.requestId === headers.requestId && item.action === action);
      expect(matches).toHaveLength(1); expect(matches[0].operatorId).toBe(actor);
    }).toPass({ timeout: budget.remaining(10_000) });
  };
  const go = async (actor: string, route: string) => {
    expect(actors).toContain(actor);
    const url = new URL(process.env.APPROVAL_DEMO_H5_URL!);
    expect(url.origin).toBe('http://127.0.0.1:9000');
    url.searchParams.set('demoOperator', actor); url.hash = route;
    await page.goto(url.toString(), { waitUntil: 'domcontentloaded', timeout: budget.remaining(20_000) });
  };
  const button = (scope: Locator | Page, label: string) => scope.getByRole('button', { name: label, exact: true });
  const confirm = () => page.locator('uni-modal .uni-modal__btn_primary').last();
  const cancel = () => page.locator('uni-modal .uni-modal__btn_default').last();
  const field = (label: string) => page.locator('.field-card:visible').filter({ has: page.locator('.field-label').filter({ hasText: label }) });
  const mutation = async (path: string, trigger: () => Promise<unknown>) => {
    const waiting = page.waitForResponse(value => value.request().method() === 'POST'
      && exactApprovalApiPath(value.url(), `/api/approval${path}`), { timeout: budget.remaining(20_000) });
    const [response] = await Promise.all([waiting, trigger()]);
    expect(response.status()).toBe(200); return response;
  };
  const taskFor = async (actor: string, definition: string) => {
    let result: Task | undefined;
    await expect(async () => {
      const found = (await pending(actor)).filter(item => item.taskDefinitionKey === definition);
      expect(found).toHaveLength(1); result = found[0];
    }).toPass({ timeout: budget.remaining(10_000) });
    return result!;
  };
  const open = async (actor: string, task: Task) => {
    await go(actor, '/pages/task/list');
    const card = page.locator(`.task-card[data-task-id="${task.taskId}"]`);
    await expect(card).toContainText(key); await card.click();
    await expect(page.locator('.action-bar:visible')).toBeVisible();
    await expect(page.locator('.timeline-card')).toBeVisible();
  };
  const act = async (actor: string, task: Task, action: 'approve' | 'reject' | 'resubmit') => {
    const label = { approve: '同意', reject: '驳回', resubmit: '重新提交' }[action];
    await button(page.locator('.action-bar:visible'), label).click();
    const response = await mutation(`/tasks/${task.taskId}/${action}`, () => confirm().click());
    const result = unwrap(await response.json());
    expect(result).toMatchObject({ instanceId, completedTaskId: task.taskId });
    await audit(response, actor, { approve: 'TASK_APPROVED', reject: 'TASK_REJECTED', resubmit: 'TASK_RESUBMITTED' }[action]);
    expect((await pending(actor)).some(item => item.taskId === task.taskId)).toBe(false);
    await expect(page).toHaveURL(url => url.hash.startsWith('#/pages/task/list'), { timeout: budget.remaining(10_000) });
    return result;
  };
  try {
    await test.step('Employee creates a real form submission and uploads a real local file through H5', async () => {
      await go(employee, '/pages/initiate/index');
      const template = page.locator('.template-card').filter({ hasText: 'purchase-payment' });
      await expect(template).toHaveCount(1); await template.click();
      await page.locator('.business-card:visible .wd-input')
        .filter({ has: page.locator('.wd-input__label').filter({ hasText: /^业务编号$/u }) })
        .locator('input').fill(key);
      await field('Payment amount').locator('input').fill(amount);
      await field('Supplier').locator('input').fill(`UI supplier ${key}`);
      await field('Purchase order reference').locator('input').fill(`PO-${key}`);
      const chooser = page.waitForEvent('filechooser', { timeout: budget.remaining(10_000) });
      await button(field('Payment documents'), '选择并上传文件').click();
      const upload = page.waitForResponse(value => value.request().method() === 'POST'
        && exactApprovalApiPath(value.url(), '/api/approval/attachments'), { timeout: budget.remaining(15_000) });
      await (await chooser).setFiles({ name: 'business-flow.txt', mimeType: 'text/plain', buffer: Buffer.from('Isolated business flow test attachment\n') });
      expect((await upload).status()).toBe(200);
      await expect(field('Payment documents').locator('.file-name')).toHaveCount(1);
      // Cancel is a real UI action; no submission may have reached the server.
      await button(page.locator('.action-bar:visible'), '发起审批').click(); await cancel().click();
      expect(await started()).toHaveLength(0);
      expect(commands.filter(item => item.path.endsWith('/submissions'))).toHaveLength(0);
      await button(page.locator('.action-bar:visible'), '发起审批').click();
      const response = await mutation('/forms/purchase-payment/versions/1/submissions', () => confirm().click());
      const submission = unwrap(await response.json()); instanceId = submission.instanceId;
      expect(submission).toMatchObject({ businessKey: key, submittedBy: employee, replayedExistingSubmission: false });
      expect(instanceId).toMatch(/^[a-f0-9-]{36}$/u);
      await audit(response, employee, 'INSTANCE_STARTED');
      await expect(page.locator('uni-modal .uni-modal__title')).toHaveText('审批已发起');
      await confirm().click();
      await expect(page.locator('[data-testid="approval-task-list"]:visible')).toHaveAttribute('data-active-tab', 'started');
      expect(await started()).toHaveLength(1);
      expect(commands.filter(item => item.path.endsWith('/submissions'))).toHaveLength(1);
      const snapshot = await get(`/instances/${instanceId}/form-snapshot`);
      expect(snapshot.submission).toMatchObject({ instanceId, businessKey: key, submittedBy: employee });
      expect(Number(snapshot.submission.values.amount)).toBe(Number(amount));
      expect(snapshot.submission.values.attachments).toHaveLength(1);
      attachmentIds = snapshot.submission.values.attachments;
    });
    let managerTask = await taskFor(manager, 'managerApproval');
    if (!highAmount) await test.step('Reject requires a reason; cancel preserves task; employee edits and resubmits', async () => {
      await open(manager, managerTask);
      const before = await events(), commandCount = commands.length;
      await button(page.locator('.action-bar:visible'), '驳回').click();
      await expect(page.getByText('驳回时必须填写原因', { exact: true })).toBeVisible();
      expect(await events()).toEqual(before); expect(commands).toHaveLength(commandCount);
      await page.locator('.action-card:visible')
        .filter({ has: page.locator('.section-title').filter({ hasText: /^审批意见$/u }) })
        .locator('textarea').fill('Please correct supplier');
      await button(page.locator('.action-bar:visible'), '驳回').click(); await cancel().click();
      expect(await events()).toEqual(before); expect((await pending(manager)).map(item => item.taskId)).toContain(managerTask.taskId);
      await act(manager, managerTask, 'reject');
      const revision = await taskFor(employee, 'initiatorRevision'); await open(employee, revision);
      await expect(field('Payment amount').locator('input')).toBeDisabled();
      await field('Supplier').locator('input').fill(`Corrected ${key}`);
      await field('Purchase order reference').locator('input').fill(`REVISED-${key}`);
      await act(employee, revision, 'resubmit');
      managerTask = await taskFor(manager, 'managerApproval');
      const details = await get(`/tasks/pending/${managerTask.taskId}`, manager);
      expect(details.supplier).toBe(`Corrected ${key}`); expect(details.purchaseOrderReference).toBe(`REVISED-${key}`);
      expect(Number(details.amount)).toBe(Number(amount));
      expect(details.attachmentIds).toEqual(attachmentIds);
      const snapshot = await get(`/instances/${instanceId}/form-snapshot`);
      // The immutable original submission must not be rewritten by a revision.
      expect(snapshot.submission.values).toMatchObject({ supplier: `UI supplier ${key}`, purchaseOrderReference: `PO-${key}`, attachments: attachmentIds });
      const runtime = await get(`/tasks/${managerTask.taskId}/form-runtime`, manager);
      expect(runtime.values).toMatchObject({ supplier: `Corrected ${key}`, purchaseOrderReference: `REVISED-${key}` });
      expect(Number(snapshot.submission.values.amount)).toBe(Number(amount));
    });
    await test.step(highAmount ? 'Threshold amount routes to one reviewer and supports manager retrieve' : 'Below threshold skips reviewer and creates two distinct countersign tasks', async () => {
      await open(manager, managerTask); await act(manager, managerTask, 'approve');
      if (highAmount) {
        const downstream = await taskFor(authoritativeActors.financeReview, 'financeReview');
        await go(manager, '/pages/task/list');
        await page.locator('.mode-row:visible').getByRole('button', { name: /^我已处理\s*\d+$/u }).click();
        await expect(page.locator('[data-testid="approval-task-list"]:visible')).toHaveAttribute('data-active-tab', 'processed');
        const card = page.locator(`.task-card[data-task-id="${managerTask.taskId}"]`);
        await button(card, '拿回').click();
        const response = await mutation(`/tasks/${managerTask.taskId}/retrieve`, () => confirm().click());
        await audit(response, manager, 'TASK_RETRIEVED');
        expect((await pending(authoritativeActors.financeReview)).some(item => item.taskId === downstream.taskId)).toBe(false);
        const restored = await taskFor(manager, 'managerApproval');
        await open(manager, restored); await act(manager, restored, 'approve');
        expect((await taskFor(authoritativeActors.financeReview, 'financeReview')).taskId).not.toBe(downstream.taskId);
      } else {
        expect(await pending(authoritativeActors.financeReview)).toHaveLength(0);
        const a = await taskFor(authoritativeActors.financeCountersign[0], 'financeCountersign');
        const b = await taskFor(authoritativeActors.financeCountersign[1], 'financeCountersign');
        expect(a.taskId).not.toBe(b.taskId);
      }
    });
    await test.step('Employee withdraws through started list; durable state/history and every actor pending list agree', async () => {
      await go(employee, '/pages/task/list');
      await page.locator('.mode-row:visible').getByRole('button', { name: /^我发起的\s*\d+$/u }).click();
      await expect(page.locator('[data-testid="approval-task-list"]:visible')).toHaveAttribute('data-active-tab', 'started');
      const card = page.locator(`.task-card[data-instance-id="${instanceId}"]`);
      await expect(card).toContainText(key); await button(card, '撤回').click();
      const response = await mutation(`/instances/${instanceId}/withdraw`, () => confirm().click());
      await audit(response, employee, 'INSTANCE_WITHDRAWN');
      const state = await started(); expect(state).toHaveLength(1); expect(state[0].status).toBe('WITHDRAWN');
      for (const actor of actors) expect(await pending(actor)).toHaveLength(0);
      const durable = await get(`/instances/${instanceId}`);
      expect(durable.instance.status).toBe('WITHDRAWN');
      expect(durable.tasks.length).toBeGreaterThan(0);
      expect(durable.tasks.every((item: { status: string }) => ['COMPLETED', 'CANCELED'].includes(item.status))).toBe(true);
      await expect(card.getByRole('button', { name: '撤回', exact: true, includeHidden: true })).toHaveCount(0);
      const history = await events(); expect(new Set(history.map(item => item.eventId)).size).toBe(history.length);
      expect(history.filter(item => item.action === 'INSTANCE_WITHDRAWN')).toHaveLength(1);
    });
    failures.assert(); budget.remaining();
    const history = await events();
    writeFileSync(resolve(process.env.APPROVAL_DEMO_EVIDENCE_DIR!, `business-lifecycle-${highAmount ? 'threshold' : 'revision'}.json`), JSON.stringify({
      schemaVersion: 1, evidenceKind: 'LOCAL_DEMO_BROWSER_BUSINESS_LIFECYCLE_V1',
      commitSha: process.env.APPROVAL_DEMO_EXACT_HEAD_SHA, businessKey: key, instanceId,
      roleMode: 'GOVERNED_LOCAL_DEMO_OPERATOR_NOT_PRODUCTION_IDENTITY',
      scenario: highAmount ? 'THRESHOLD_RETRIEVE_WITHDRAW' : 'LOW_AMOUNT_REJECT_EDIT_RESUBMIT_WITHDRAW',
      finalStatus: 'WITHDRAWN', attachmentIds, commands,
      timeline: history.map(({ eventId, action, requestId, operatorId }) => ({ eventId, action, requestId, operatorId })),
      noProductionPayment: true,
    }, null, 2) + '\n', { mode: 0o600 });
  } finally {
    failures.dispose(); await context.close();
    // No API cleanup can disguise a failed UI command. The outer harness always
    // removes its own disposable demo database/processes, including failure cases.
  }
}
