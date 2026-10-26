/** Real isolated form authoring; success responses are never fabricated. */
import { randomUUID } from 'node:crypto';
import type { APIRequestContext, Browser, Locator, Page, TestInfo } from '@playwright/test';
import { expect } from '@playwright/test';
import type { CaptureBudget } from './product-readiness-capture-budget';
import { backendOrigin, exactApprovalApiPath, pcUrl, tenantId } from './product-readiness-pc-h5-runtime-api';
import { ensurePcLogin } from './product-readiness-pc-h5-runtime-ui';

type FormDraft = { draftId: string; formKey: string; revision: number; status: string; formDefinition: { fields: Array<{ key: string; label: string }> } };
const button = (scope: Locator | Page, name: string) => scope.getByRole('button', { name, exact: true });
const item = (scope: Locator | Page, name: string) => scope.locator('.el-form-item').filter({ has: scope.getByText(name, { exact: true }).and(scope.locator('.el-form-item__label')) });
export async function runFormPackageLifecycle(browser: Browser, request: APIRequestContext, budget: CaptureBudget, evidence: Pick<TestInfo, 'attach'>) {
  const key = `browser-form-${randomUUID()}`, changedLabel = `Acceptance amount ${randomUUID()}`;
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1600, height: 1000 } });
  const page = await context.newPage(); page.setDefaultTimeout(budget.remaining(15_000));
  const commands: string[] = [];
  page.on('request', value => {
    if (['POST', 'PUT'].includes(value.method()) && new URL(value.url()).pathname.includes('/approval/form-design-drafts')) commands.push(new URL(value.url()).pathname);
  });
  const get = async <T>(path: string, actor = 'demo-admin'): Promise<T> => {
    const id = randomUUID(); const response = await request.get(`${backendOrigin}/api/approval${path}`, {
      headers: { 'X-Tenant-Id': tenantId, 'X-Operator-Id': actor, 'X-Request-Id': id, 'X-Trace-Id': id }, timeout: budget.remaining(10_000),
    });
    expect(response.status()).toBe(200); return response.json();
  };
  const command = async <T>(method: string, path: string, trigger: () => Promise<unknown>): Promise<T> => {
    const [response] = await Promise.all([page.waitForResponse(value => value.request().method() === method && exactApprovalApiPath(value.url(), `/api/approval${path}`), { timeout: budget.remaining(20_000) }), trigger()]);
    expect(response.status(), await response.text()).toBe(200); return response.json();
  };
  try {
    await ensurePcLogin(page);
    const url = new URL(pcUrl); url.pathname = '/approval/forms'; url.searchParams.set('demoOperator', 'demo-admin');
    await page.goto(url.toString(), { waitUntil: 'domcontentloaded', timeout: budget.remaining(20_000) });
    await button(page, '新建').click();
    const create = page.getByRole('dialog', { name: '创建表单设计草稿', exact: true });
    await item(create, '表单名称').getByRole('textbox').fill('');
    await button(create, '创建并打开').click();
    await expect(page.getByText('请填写表单名称和唯一 Key', { exact: true })).toBeVisible();
    expect(commands.filter(path => path.endsWith('/form-design-drafts'))).toHaveLength(0);
    await button(create, '取消').click(); await expect(create).toBeHidden();
    await button(page, '新建').click();
    await item(create, '创建方式').locator('.el-select').click();
    await page.getByRole('option', { name: '采购付款模板', exact: true }).click();
    await item(create, '表单名称').getByRole('textbox').fill(key);
    await item(create, '唯一 Key').getByRole('textbox').fill(key);
    const created = await command<FormDraft>('POST', '/form-design-drafts', () => button(create, '创建并打开').click());
    expect(created).toMatchObject({ formKey: key, status: 'DRAFT' });
    await expect(create).toBeHidden();
    const draftPath = `/form-design-drafts/${created.draftId}`;
    await page.locator('.field-node').filter({ has: page.locator('small').filter({ hasText: /^amount$/ }) }).click();
    await item(page, '字段标题').getByRole('textbox').fill(changedLabel);
    await command<FormDraft>('PUT', draftPath, () => button(page.locator('.designer-toolbar'), '保存').click());
    await expect(page.locator('.designer-toolbar')).toContainText('已保存');
    expect((await get<FormDraft>(draftPath)).formDefinition.fields.find(field => field.key === 'amount')?.label).toBe(changedLabel);
    const previewData = await command<{ contextKey: string; fieldPermissions: Record<string, string> }>('POST', `${draftPath}/preview`, () => button(page.locator('.designer-toolbar'), '节点预览').click());
    expect(previewData.contextKey).toBe('$start');
    const preview = page.getByRole('dialog', { name: '服务端节点预览', exact: true });
    await expect(preview).toContainText(changedLabel);
    await evidence.attach('form-preview', { body: await page.screenshot({ timeout: budget.remaining(10_000) }), contentType: 'image/png' });
    await preview.locator('.el-dialog__headerbtn').click(); await expect(preview).toBeHidden();
    await button(page.locator('.designer-toolbar'), '发布 Package').click();
    const publish = page.getByRole('dialog', { name: '发布不可变 Form Package', exact: true });
    await expect(publish).toBeVisible(); await button(publish, '取消').click(); await expect(publish).toBeHidden();
    expect(commands.filter(path => path.endsWith('/publish'))).toHaveLength(0);
    // Direct malformed request verifies the existing server gate before the corrected UI command.
    const before = await get<FormDraft>(draftPath);
    const probeId = randomUUID();
    const rejected = await request.post(`${backendOrigin}/api/approval${draftPath}/publish`, {
      headers: { 'X-Tenant-Id': tenantId, 'X-Operator-Id': 'demo-admin', 'X-Request-Id': probeId, 'X-Trace-Id': probeId, 'Idempotency-Key': probeId },
      data: { expectedRevision: before.revision, packageVersion: 1 }, timeout: budget.remaining(10_000),
    });
    expect(rejected.status()).toBe(400);
    expect(await rejected.json()).toMatchObject({ code: 'APPROVAL_OPERATION_REASON_REQUIRED' });
    expect((await get<FormDraft>(draftPath)).revision).toBe(before.revision);
    await button(page.locator('.designer-toolbar'), '发布 Package').click();
    await item(publish, '发布原因').getByRole('textbox').fill('short');
    await button(publish, '确认发布').click();
    await expect(page.getByText('发布原因必须包含 8–512 个字符', { exact: true })).toBeVisible();
    expect(commands.filter(path => path.endsWith('/publish'))).toHaveLength(0);
    await item(publish, '发布原因').getByRole('textbox').fill('Isolated form package acceptance');
    const result = await command<{ formKey: string; packageVersion: number; formHash: string; uiSchemaHash: string; packageHash: string }>('POST', `${draftPath}/publish`, () => button(publish, '确认发布').click());
    expect(result).toMatchObject({ formKey: key, packageVersion: 1 });
    expect(result.packageHash).toMatch(/^[a-f0-9]{64}$/);
    await expect(page.getByRole('dialog', { name: 'Form Package 发布完成', exact: true })).toContainText(result.packageHash);
    await evidence.attach('form-published', { body: await page.screenshot({ timeout: budget.remaining(10_000) }), contentType: 'image/png' });
    const snapshot = await get<FormDraft>(draftPath); expect(snapshot.status).toBe('PUBLISHED');
    const pkg = await get<Record<string, unknown>>(`/form-packages/${key}/versions/1`);
    expect(pkg).toMatchObject({ formKey: key, packageVersion: 1, packageHash: result.packageHash, formHash: result.formHash, uiSchemaHash: result.uiSchemaHash });
    const runtime = await get<{ contextKey: string; definition: { fields: Array<{ key: string; label: string }> }; fieldPermissions: Record<string, string> }>(`/forms/${key}/versions/1/runtime`, 'demo-employee');
    expect(runtime.contextKey).toBe('$start');
    expect(runtime.definition.fields.find(field => field.key === 'amount')?.label).toBe(changedLabel);
    expect(runtime.fieldPermissions).toEqual(previewData.fieldPermissions);
    expect(commands.filter(path => path.endsWith('/publish'))).toHaveLength(1);
    return { key, draftId: created.draftId, result, pkg, runtime, commands };
  } finally { await context.close(); }
}
