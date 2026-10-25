/** Real disposable-local-demo admin UI lifecycle; no success response mocks. */
import { randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { APIRequestContext, Browser, Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';
import type { CaptureBudget } from './product-readiness-capture-budget';
import { backendOrigin, evidenceDirectory, exactApprovalApiPath, pcUrl, selectedHeaders, tenantId } from './product-readiness-pc-h5-runtime-api';
import { ensurePcLogin } from './product-readiness-pc-h5-runtime-ui';

type Identity = { calendarId?: string; policyId?: string; version: number; activeVersion?: number; status: string };
type Version = { immutable: boolean; status: string; calendarVersion?: number; policyVersion?: number; calendarId?: string; durationMode?: string; automaticAction?: string; snapshot?: { zoneId: string } };
const reason = 'Isolated browser acceptance lifecycle';
const button = (scope: Page | Locator, name: string) => scope.getByRole('button', { name, exact: true });
// Element Plus wraps its controls in real HTML labels on these two maintained pages.
const input = (scope: Locator, label: string) => scope.locator('label').filter({ hasText: new RegExp(`^${label}`) }).locator('input, textarea').first();

export async function runAdminCalendarLifecycle(browser: Browser, request: APIRequestContext, budget: CaptureBudget) {
  const scenario = JSON.parse(readFileSync(resolve(process.env.APPROVAL_DEMO_REPOSITORY_ROOT!,
    'config/demo/purchase-payment-golden-path.json'), 'utf8')) as { directory: { users: Array<{ id: string; roleCodes: string[] }> } };
  const administrators = scenario.directory.users.filter(user => user.roleCodes.includes('APPROVAL_ADMIN'));
  expect(administrators).toHaveLength(1);
  const administrator = administrators[0].id;
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  page.setDefaultTimeout(budget.remaining(15_000));
  const key = `browser-sla-${randomUUID()}`;
  const commands: Array<{ path: string; requestId: string; status: number }> = [];
  const errors: string[] = [];
  const browserAuthorityHeaders: string[] = [];
  page.on('request', value => {
    const authority = value.headers()['x-approval-trusted-permissions'];
    if (authority) browserAuthorityHeaders.push(authority);
  });
  page.on('pageerror', error => errors.push(error.message));
  const read = async <T>(path: string): Promise<T> => {
    const id = `admin-read-${randomUUID()}`;
    const response = await request.get(`${backendOrigin}/api/approval/management/sla${path}`, {
      headers: { 'X-Tenant-Id': tenantId, 'X-Operator-Id': administrator, 'X-Request-Id': id, 'X-Trace-Id': id },
      timeout: budget.remaining(10_000),
    });
    expect(response.status()).toBe(200);
    return await response.json() as T;
  };
  const go = async (route: string) => {
    const url = new URL(pcUrl);
    expect(['127.0.0.1', 'localhost']).toContain(url.hostname);
    url.pathname = route; url.searchParams.set('demoOperator', administrator);
    await page.goto(url.toString(), { waitUntil: 'domcontentloaded', timeout: budget.remaining(20_000) });
  };
  const responseFor = async (method: string, path: string, action: () => Promise<unknown>, status = 200) => {
    const [response] = await Promise.all([
      page.waitForResponse(value => value.request().method() === method
        && exactApprovalApiPath(value.url(), `/api/approval/management/sla${path}`), { timeout: budget.remaining(20_000) }),
      action(),
    ]);
    expect(response.status(), `${method} ${path}: ${await response.text()}`).toBe(status);
    const headers = selectedHeaders(response);
    expect(headers).toMatchObject({ operatorId: administrator, tenantId, requestId: expect.any(String) });
    if (method !== 'GET') commands.push({ path, requestId: headers.requestId, status });
    return response;
  };
  const mutation = async <T>(method: string, path: string, action: () => Promise<unknown>) =>
    await (await responseFor(method, path, action)).json() as T;
  const transition = async (row: Locator, path: string, action: '发布' | '激活', invalidReason = false) => {
    await button(row, action).click();
    const prompt = page.locator('.el-message-box:visible');
    await expect(prompt).toBeVisible();
    const confirm = prompt.locator('.el-message-box__btns button').last();
    if (invalidReason) {
      await prompt.locator('.el-message-box__btns button').first().click();
      await expect(prompt).toBeHidden();
      await button(row, action).click();
      await expect(prompt).toBeVisible();
      await prompt.locator('input').fill('short');
      await confirm.click();
      await expect(prompt).toContainText('原因至少 8 个字符');
      await expect(prompt).toBeVisible();
    }
    await prompt.locator('input').fill(reason);
    const listPath = path.startsWith('/calendars/') ? '/calendars' : '/policies';
    const listReload = page.waitForResponse(value => value.request().method() === 'GET'
      && exactApprovalApiPath(value.url(), `/api/approval/management/sla${listPath}`), { timeout: budget.remaining(15_000) });
    const [result, reloaded] = await Promise.all([
      mutation<Identity | Version>('POST', path, () => confirm.click()), listReload,
    ]);
    expect(reloaded.status()).toBe(200);
    await expect(prompt).toBeHidden();
    return result;
  };
  try {
    for (const [negativeActor, negativeTenant] of [['demo-employee', tenantId], [administrator, 'other-isolated-tenant']]) {
      const id = randomUUID();
      const denied = await request.get(`${backendOrigin}/api/approval/management/sla/calendars?limit=50&offset=0`, {
        headers: { 'X-Tenant-Id': negativeTenant, 'X-Operator-Id': negativeActor, 'X-Request-Id': id,
          'X-Trace-Id': id, 'X-Approval-Trusted-Permissions': 'approval.management.sla.read,approval.management.sla.activate' },
        timeout: budget.remaining(10_000),
      });
      expect(denied.status(), 'seeded demo mapping must reject wrong actor/tenant and ignore forged privileges').toBe(403);
    }
    await ensurePcLogin(page);
    await go('/approval/sla/calendars');
    await expect(button(page, '新建日历')).toBeVisible();
    const initial = await read<{ items: Identity[]; total: number }>('/calendars?limit=50&offset=0');
    if (initial.total === 0) await expect(page.getByText('暂无工作日历', { exact: true })).toBeVisible();
    await button(page, '新建日历').click();
    let dialog = page.getByRole('dialog', { name: '新建工作日历', exact: true });
    await input(dialog, '日历 Key').fill(`${key}-cancelled`);
    await button(dialog, '取消').click();
    await expect(dialog).toBeHidden();
    expect((await read<{ total: number }>('/calendars?limit=50&offset=0')).total).toBe(initial.total);
    await button(page, '新建日历').click();
    dialog = page.getByRole('dialog', { name: '新建工作日历', exact: true });
    await input(dialog, '日历 Key').fill(key);
    await input(dialog, '显示名称').fill(key);
    await input(dialog, '时区').fill('Asia/Shanghai');
    await input(dialog, '操作原因').fill(reason);
    const calendar = await mutation<Identity>('POST', '/calendars', () => button(dialog, '创建').click());
    expect(calendar.calendarId).toBeTruthy();
    const calendarPath = `/calendars/${calendar.calendarId}`;
    await expect(dialog).toBeHidden();
    let row = page.locator('.el-table__body-wrapper tr').filter({ hasText: key });
    await expect(row).toHaveCount(1);
    await button(row, '版本配置').click();
    let editor = page.getByRole('dialog', { name: `日历版本配置 · ${key}`, exact: true });
    await expect(editor.getByText('暂无日期覆盖', { exact: true })).toBeVisible();
    // Missing version is a genuine backend error, not a synthetic route response.
    await responseFor('GET', `${calendarPath}/versions/1`, () => button(editor, '读取版本').click(), 404);
    await expect(page.locator('.el-message--error')).toBeVisible();
    await expect(button(editor, '保存草稿')).toBeEnabled();
    await input(editor, '操作原因').fill(reason);
    const draft = await mutation<Version>('PUT', `${calendarPath}/versions/1`, () => button(editor, '保存草稿').click());
    expect(draft).toMatchObject({ calendarVersion: 1, immutable: false, status: 'DRAFT' });
    expect((await read<Version>(`${calendarPath}/versions/1`)).snapshot?.zoneId).toBe('Asia/Shanghai');
    await button(editor, '关闭').click();
    await expect(editor).toBeHidden();
    await transition(row, `${calendarPath}/versions/1/publish`, '发布', true);
    expect(await read<Version>(`${calendarPath}/versions/1`)).toMatchObject({ immutable: true, status: 'PUBLISHED' });
    await transition(row, `${calendarPath}/versions/1/activate`, '激活');
    expect(await read<Identity>(calendarPath)).toMatchObject({ activeVersion: 1, status: 'ACTIVE' });
    await button(row, '版本配置').click();
    editor = page.getByRole('dialog', { name: `日历版本配置 · ${key}`, exact: true });
    await editor.locator('.toolbar').getByRole('spinbutton').fill('1');
    await responseFor('GET', `${calendarPath}/versions/1`, () => button(editor, '读取版本').click());
    await expect(button(editor, '保存草稿')).toBeDisabled();
    await editor.locator('.toolbar').getByRole('spinbutton').fill('2');
    await expect(button(editor, '保存草稿')).toBeEnabled();
    await editor.getByPlaceholder('IANA 时区，如 Asia/Shanghai').fill('UTC');
    await input(editor, '操作原因').fill(reason);
    await mutation<Version>('PUT', `${calendarPath}/versions/2`, () => button(editor, '保存草稿').click());
    // Repeated draft save must refresh identity CAS rather than conflict on its own preceding save.
    await mutation<Version>('PUT', `${calendarPath}/versions/2`, () => button(editor, '保存草稿').click());
    await button(editor, '关闭').click();
    await transition(row, `${calendarPath}/versions/2/publish`, '发布');
    await transition(row, `${calendarPath}/versions/2/activate`, '激活');
    expect(await read<Identity>(calendarPath)).toMatchObject({ activeVersion: 2, status: 'ACTIVE' });
    expect((await read<Version>(`${calendarPath}/versions/1`)).snapshot?.zoneId).toBe('Asia/Shanghai');
    expect((await read<Version>(`${calendarPath}/versions/2`)).snapshot?.zoneId).toBe('UTC');

    // SLA targets an isolated definition key, not the payment fixture used by sibling tests.
    await go('/approval/sla/policies');
    await expect(button(page, '新建策略')).toBeVisible();
    await button(page, '新建策略').click();
    dialog = page.getByRole('dialog', { name: '新建 SLA 策略', exact: true });
    await input(dialog, '策略 Key').fill(key);
    await input(dialog, '显示名称').fill(key);
    await input(dialog, '操作原因').fill(reason);
    const policy = await mutation<Identity>('POST', '/policies', () => button(dialog, '创建').click());
    expect(policy.policyId).toBeTruthy();
    const policyPath = `/policies/${policy.policyId}`;
    await expect(dialog).toBeHidden();
    row = page.locator('.el-table__body-wrapper tr').filter({ hasText: key });
    await expect(row).toHaveCount(1);
    await button(row, '版本配置').click();
    editor = page.getByRole('dialog', { name: `策略版本配置 · ${key}`, exact: true });
    await input(editor, '流程定义 Key').fill(key);
    await input(editor, '任务定义 Key').fill('isolated-approval');
    await input(editor, '持续时间').fill('PT8H');
    await input(editor, '操作原因').fill(reason);
    expect(await mutation<Version>('PUT', `${policyPath}/versions/1`, () => button(editor, '保存草稿').click()))
      .toMatchObject({ policyVersion: 1, immutable: false, status: 'DRAFT', automaticAction: 'NONE' });
    await button(editor, '关闭').click();
    await transition(row, `${policyPath}/versions/1/publish`, '发布', true);
    expect(await read<Version>(`${policyPath}/versions/1`)).toMatchObject({ immutable: true, status: 'PUBLISHED', durationMode: 'NATURAL_TIME', automaticAction: 'NONE' });
    await transition(row, `${policyPath}/versions/1/activate`, '激活');
    expect(await read<Identity>(policyPath)).toMatchObject({ activeVersion: 1, status: 'ACTIVE' });
    await button(row, '版本配置').click();
    editor = page.getByRole('dialog', { name: `策略版本配置 · ${key}`, exact: true });
    await editor.locator('.toolbar').getByRole('spinbutton').fill('1');
    await responseFor('GET', `${policyPath}/versions/1`, () => button(editor, '读取版本').click());
    await expect(button(editor, '保存草稿')).toBeDisabled();
    await editor.locator('.toolbar').getByRole('spinbutton').fill('2');
    await expect(button(editor, '保存草稿')).toBeEnabled();
    // Read-back must preserve editable ISO durations, including servers returning numeric seconds.
    await expect(input(editor, '持续时间')).toHaveValue(/^P/);
    await expect(input(editor, '首次提醒偏移')).toHaveValue(/^P/);
    await expect(input(editor, '逾期偏移')).toHaveValue(/^P/);
    await input(editor, '持续时间').fill('PT9H');
    await input(editor, '操作原因').fill(reason);
    await mutation<Version>('PUT', `${policyPath}/versions/2`, () => button(editor, '保存草稿').click());
    await mutation<Version>('PUT', `${policyPath}/versions/2`, () => button(editor, '保存草稿').click());
    await button(editor, '关闭').click();
    await transition(row, `${policyPath}/versions/2/publish`, '发布');
    await transition(row, `${policyPath}/versions/2/activate`, '激活');
    expect(await read<Identity>(policyPath)).toMatchObject({ activeVersion: 2, status: 'ACTIVE' });
    expect(await read<Version>(`${policyPath}/versions/1`)).toMatchObject({ immutable: true });
    await button(page, '刷新').click();
    await expect(row).toContainText('ACTIVE');
    await go('/approval/sla/calendars');
    await expect(page.locator('.el-table__body-wrapper tr').filter({ hasText: key })).toContainText('ACTIVE');
    await page.goBack({ waitUntil: 'domcontentloaded', timeout: budget.remaining(15_000) });
    await expect(page.locator('.el-table__body-wrapper tr').filter({ hasText: key })).toContainText('ACTIVE');
    expect(new Set(commands.map(command => command.requestId)).size).toBe(commands.length);
    expect(commands).toHaveLength(16);
    expect(errors).toEqual([]);
    expect(browserAuthorityHeaders).toEqual([]);
    await page.screenshot({ path: resolve(evidenceDirectory, `pc-admin-calendar-${key}.png`), fullPage: true, timeout: budget.remaining(10_000) });
    const receipt = { schemaVersion: 1, status: 'PASSED', testedAt: new Date().toISOString(),
      tenantId, administrator, key, calendarId: calendar.calendarId, policyId: policy.policyId, commands, checks: [
      'calendar-create-cancel-no-write', 'calendar-missing-version-real-error', 'calendar-draft-publish-activate',
      'policy-draft-publish-activate', 'short-governance-reason-rejected', 'published-versions-read-only',
      'independent-api-readback', 'wrong-actor-and-tenant-denied', 'forged-permission-header-denied', 'browser-never-sends-permissions', 'refresh-navigation-back', 'calendar-and-policy-v1-to-v2', 'repeat-save-cas',
    ] };
    writeFileSync(resolve(evidenceDirectory, `pc-admin-calendar-${key}.json`), `${JSON.stringify(receipt, null, 2)}\n`);
    return receipt;
  } finally {
    await context.close();
  }
}
