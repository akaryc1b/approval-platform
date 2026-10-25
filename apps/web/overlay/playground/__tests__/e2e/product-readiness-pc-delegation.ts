/** Real delegation commands through PC UI, independently read from disposable backend. */
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import type { APIRequestContext, Browser, Response } from '@playwright/test';
import { expect } from '@playwright/test';
import type { CaptureBudget } from './product-readiness-capture-budget';
import { backendOrigin, pcUrl, tenantId, exactApprovalApiPath, evidenceDirectory } from './product-readiness-pc-h5-runtime-api';
import { ensurePcLogin } from './product-readiness-pc-h5-runtime-ui';

type Rule = { ruleId: string; principalId: string; delegateId: string; status: string; reason: string };
export async function runDelegationLifecycle(browser: Browser, request: APIRequestContext, budget: CaptureBudget) {
  const context = await browser.newContext();
  const page = await context.newPage();
  page.setDefaultTimeout(budget.remaining(15_000));
  const reason = `UI-DELEGATION-${randomUUID()}`;
  const actor = 'demo-manager';
  let ruleId = '';
  let revoked = false;
  const mutations: string[] = [];
  page.on('request', value => {
    if (value.method() === 'POST' && new URL(value.url()).pathname.includes('/approval/delegations')) mutations.push(value.url());
  });
  const headers = () => { const id = randomUUID(); return { 'X-Tenant-Id': tenantId, 'X-Operator-Id': actor, 'X-Request-Id': id, 'X-Trace-Id': id, 'Idempotency-Key': id }; };
  const read = async (includeRevoked: boolean, operator = actor): Promise<Rule[]> => {
    const response = await request.get(`${backendOrigin}/api/approval/delegations?includeRevoked=${includeRevoked}`, {
      headers: { ...headers(), 'X-Operator-Id': operator }, timeout: budget.remaining(10_000),
    });
    expect(response.status()).toBe(200); return response.json();
  };
  const command = async (path: string, action: () => Promise<unknown>): Promise<Response> => {
    const [response] = await Promise.all([page.waitForResponse(value => value.request().method() === 'POST'
      && exactApprovalApiPath(value.url(), path), { timeout: budget.remaining(20_000) }), action()]);
    expect(response.status()).toBe(200); return response;
  };
  try {
    await ensurePcLogin(page);
    const url = new URL(pcUrl); url.pathname = '/approval/delegations'; url.searchParams.set('demoOperator', actor);
    await page.goto(url.toString(), { waitUntil: 'domcontentloaded', timeout: budget.remaining(20_000) });
    await expect(page.getByText('我的代理规则', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '新建代理', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '新建代理规则' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: '创建代理', exact: true }).click();
    await expect(page.getByText('请从组织身份目录选择代理人', { exact: true })).toBeVisible();
    expect(mutations).toHaveLength(0);
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await expect(dialog).toBeHidden();
    expect(mutations).toHaveLength(0);
    await page.getByRole('button', { name: '新建代理', exact: true }).click();
    const candidateField = dialog.locator('.el-form-item').filter({ hasText: '代理人' }).first();
    await candidateField.getByRole('combobox').click();
    await page.getByRole('option', { name: 'Demo Finance Reviewer（demo-finance-reviewer）', exact: true }).click();
    await dialog.getByRole('button', { name: '创建代理', exact: true }).click();
    await expect(page.getByText('请输入代理原因', { exact: true })).toBeVisible();
    expect(mutations).toHaveLength(0);
    await dialog.getByPlaceholder('说明请假、出差或临时职责安排').fill(reason);
    const created = await command('/api/approval/delegations', () => dialog.getByRole('button', { name: '创建代理', exact: true }).click());
    const rule = await created.json() as Rule; ruleId = rule.ruleId;
    expect(rule).toMatchObject({ principalId: actor, delegateId: 'demo-finance-reviewer', status: 'ACTIVE', reason });
    await expect(dialog).toBeHidden();
    const row = page.getByRole('row').filter({ hasText: reason });
    await expect(row).toContainText('生效中');
    expect((await read(false)).filter(item => item.ruleId === ruleId)).toHaveLength(1);
    expect((await read(true, 'demo-employee')).filter(item => item.ruleId === ruleId)).toHaveLength(0);
    await row.getByRole('button', { name: '撤销', exact: true }).click();
    const prompt = page.getByRole('dialog', { name: '撤销代理规则' });
    await prompt.getByRole('button', { name: '取消', exact: true }).click();
    await expect(prompt).toBeHidden(); expect(mutations).toHaveLength(1);
    expect((await read(false)).filter(item => item.ruleId === ruleId)).toHaveLength(1);
    await row.getByRole('button', { name: '撤销', exact: true }).click();
    await prompt.getByPlaceholder('请输入撤销原因').fill(`${reason}-done`);
    const result = await command(`/api/approval/delegations/${ruleId}/revoke`, () => prompt.getByRole('button', { name: '确认撤销', exact: true }).click());
    expect(await result.json()).toMatchObject({ ruleId, status: 'REVOKED' }); revoked = true;
    await expect(row).toHaveCount(0);
    await page.getByRole('switch').click();
    await expect(row).toContainText('已撤销');
    await expect(row.getByRole('button', { name: '撤销', exact: true })).toHaveCount(0);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByText('我的代理规则', { exact: true })).toBeVisible();
    expect((await read(false)).filter(item => item.ruleId === ruleId)).toHaveLength(0);
    expect((await read(true)).filter(item => item.ruleId === ruleId)).toEqual([expect.objectContaining({ status: 'REVOKED' })]);
    expect(mutations).toHaveLength(2);
    await page.screenshot({ path: resolve(evidenceDirectory, 'pc-delegation-lifecycle.png'), fullPage: true, timeout: budget.remaining(10_000) });
    writeFileSync(resolve(evidenceDirectory, 'pc-delegation-lifecycle.json'), JSON.stringify({ status: 'passed', ruleId, reason, principal: actor, delegate: 'demo-finance-reviewer', checks: ['required-candidate', 'required-reason', 'cancel-create-no-command', 'create-ui-read-back', 'other-actor-isolation', 'cancel-revoke-no-command', 'revoke-ui-read-back', 'include-revoked-filter', 'reload'] }, null, 2));
  } finally {
    try {
      if (ruleId && !revoked) {
      const cleanup = await request.post(`${backendOrigin}/api/approval/delegations/${ruleId}/revoke`, {
        headers: headers(), data: { reason: `${reason}-test-cleanup` }, timeout: 10_000,
      });
        expect(cleanup.status(), 'failed-test delegation cleanup').toBe(200);
      }
    } finally {
      await context.close();
    }
  }
}
