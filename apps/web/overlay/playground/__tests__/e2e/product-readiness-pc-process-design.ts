/** Exercises reactive designer state with delayed delivery of a REAL backend save. */
import { randomUUID } from 'node:crypto';
import type { APIRequestContext, Browser, Locator, Page, TestInfo } from '@playwright/test';
import { expect } from '@playwright/test';
import type { CaptureBudget } from './product-readiness-capture-budget';
import { backendOrigin, exactApprovalApiPath, pcUrl, tenantId } from './product-readiness-pc-h5-runtime-api';
import { ensurePcLogin } from './product-readiness-pc-h5-runtime-ui';
const button = (scope: Locator | Page, name: string) => scope.getByRole('button', { name, exact: true });
const item = (scope: Locator | Page, name: string) => scope.locator('.el-form-item').filter({ has: scope.getByText(name, { exact: true }).and(scope.locator('.el-form-item__label')) });
type Draft = { draftId: string; revision: number; name: string; status: string; definitionKey: string };
export async function runProcessDesignLifecycle(browser: Browser, request: APIRequestContext, budget: CaptureBudget, key: string, evidence: Pick<TestInfo, 'attach'>) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1600, height: 1000 } });
  const page = await context.newPage(); page.setDefaultTimeout(budget.remaining(15_000));
  let releaseSave: (() => void) | undefined;
  const headers = (actor = 'demo-admin') => ({ 'X-Tenant-Id': tenantId, 'X-Operator-Id': actor, 'X-Request-Id': randomUUID() });
  const command = async <T>(method: string, path: string, trigger: () => Promise<unknown>): Promise<T> => {
    const [response] = await Promise.all([page.waitForResponse(value => value.request().method() === method && exactApprovalApiPath(value.url(), `/api/approval${path}`), { timeout: budget.remaining(20_000) }), trigger()]);
    expect(response.status(), await response.text()).toBe(200); return response.json();
  };
  try {
    await ensurePcLogin(page);
    const url = new URL(pcUrl); url.pathname = '/approval/designer'; url.searchParams.set('demoOperator', 'demo-admin');
    await page.goto(url.toString(), { waitUntil: 'domcontentloaded', timeout: budget.remaining(20_000) });
    await button(page, '新建').click();
    const create = page.getByRole('dialog', { name: '新建流程草稿', exact: true });
    // Own a second draft instead of assuming the demo seeds a designer document.
    const otherKey = `${key}-other`;
    await item(create, '流程标识').getByRole('textbox').fill(key);
    await item(create, '流程名称').getByRole('textbox').fill(otherKey);
    await command<Draft>('POST', '/process-design-drafts', () => button(create, '创建').click());
    await expect(create).toBeHidden();
    await button(page, '新建').click();
    await item(create, '创建方式').locator('.el-select').click();
    await page.getByRole('option', { name: '采购付款模板', exact: true }).click();
    await item(create, '流程标识').getByRole('textbox').fill(key);
    await item(create, '流程名称').getByRole('textbox').fill(key);
    const draft = await command<Draft>('POST', '/process-design-drafts', () => button(create, '创建').click());
    await expect(create).toBeHidden();
    expect(draft.definitionKey).toBe(key);
    const path = `/process-design-drafts/${draft.draftId}`;
    const read = async () => {
      const response = await request.get(`${backendOrigin}/api/approval${path}`, { headers: headers(), timeout: budget.remaining(10_000) });
      expect(response.status()).toBe(200); return await response.json() as Draft;
    };
    const denied = await request.get(`${backendOrigin}/api/approval${path}`, { headers: headers('demo-employee'), timeout: budget.remaining(10_000) });
    expect(denied.status()).toBe(403);
    const name = item(page.locator('.right-panel'), '流程名称').getByRole('textbox');
    const firstName = `${key}-first`, newerName = `${key}-newer`;
    let saves = 0;
    let fetchedSave!: () => void;
    const savedOnServer = new Promise<void>(resolve => { fetchedSave = resolve; });
    const delivery = new Promise<void>(resolve => { releaseSave = resolve; });
    await page.route(`**/api/approval${path}`, async route => {
      if (route.request().method() !== 'PUT') { await route.continue(); return; }
      saves += 1;
      if (saves !== 1) { await route.continue(); return; }
      const response = await route.fetch({ timeout: budget.remaining(10_000) });
      expect(response.status()).toBe(200);
      fetchedSave();
      await budget.run(() => delivery, 15_000);
      await route.fulfill({ response });
    });
    await name.fill(firstName);
    await button(page.locator('.toolbar'), '保存').click();
    await budget.run(() => savedOnServer, 15_000);
    expect((await read()).name).toBe(firstName);
    await name.fill(newerName);
    await button(page.locator('.toolbar'), '保存').click();
    expect(saves).toBe(1);
    await page.locator('.draft-item').filter({ has: page.locator('span').filter({ hasText: otherKey }) }).first().click();
    const discard = page.getByRole('dialog', { name: '未保存修改', exact: true });
    await button(discard, '继续编辑').click();
    await expect(name).toHaveValue(newerName);
    releaseSave();
    await expect.poll(async () => (await read()).name, { timeout: budget.remaining(15_000) }).toBe(newerName);
    await expect(name).toHaveValue(newerName);
    await expect(page.locator('.toolbar')).toContainText('saved');
    expect(saves).toBe(2);
    await evidence.attach('designer-newer-edit-saved', { body: await page.screenshot({ timeout: budget.remaining(10_000) }), contentType: 'image/png' });
    await page.unroute(`**/api/approval${path}`);
    const missingReason = await request.post(`${backendOrigin}/api/approval/preflight/publication`, {
      headers: { ...headers(), 'Idempotency-Key': randomUUID() },
      data: { definitionKey: key, draftId: draft.draftId, expectedRevision: (await read()).revision, deploymentTarget: 'default', targetDefinitionVersion: 1, targetReleaseVersion: 1, scenario: { formValues: { amount: 100 }, decisions: {}, maxTransitions: 200 } },
      timeout: budget.remaining(10_000),
    });
    expect(missingReason.status()).toBe(400);
    expect(await missingReason.json()).toMatchObject({ code: 'APPROVAL_OPERATION_REASON_REQUIRED' });
    // Cancel the actual publication confirmation, then repeat with explicit approval.
    page.once('dialog', dialog => dialog.accept('Isolated designer publication acceptance'));
    const firstPreflight = await command<{ publishable: boolean; errors: unknown[] }>('POST', '/preflight/publication', () => button(page.locator('.toolbar'), '发布').click());
    expect(firstPreflight, JSON.stringify(firstPreflight)).toMatchObject({ publishable: true, errors: [] });
    const confirm = page.getByRole('dialog', { name: '发布前综合检查', exact: true });
    await expect(confirm).toBeVisible(); await button(confirm, '取消').click();
    expect((await read()).status).not.toBe('PUBLISHED');
    page.once('dialog', dialog => dialog.accept('Isolated designer publication acceptance'));
    const secondPreflight = await command<{ publishable: boolean; errors: unknown[] }>('POST', '/preflight/publication', () => button(page.locator('.toolbar'), '发布').click());
    expect(secondPreflight, JSON.stringify(secondPreflight)).toMatchObject({ publishable: true, errors: [] });
    await expect(confirm).toBeVisible();
    const published = await command('POST', `${path}/publish`, () => confirm.getByRole('button', { name: /^(确认发布|确认警告并发布)$/ }).click());
    await expect.poll(async () => (await read()).status, { timeout: budget.remaining(10_000) }).toBe('PUBLISHED');
    await evidence.attach('designer-published', { body: await page.screenshot({ timeout: budget.remaining(10_000) }), contentType: 'image/png' });
    return { draftId: draft.draftId, definitionKey: key, saves, newerName, published };
  } finally { releaseSave?.(); await context.close(); }
}
