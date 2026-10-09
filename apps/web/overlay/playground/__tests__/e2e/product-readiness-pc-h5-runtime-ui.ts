import type { Locator, Page, Response } from '@playwright/test';
import { expect } from '@playwright/test';
import type { CaptureBudget } from './product-readiness-capture-budget';
import { readySurface } from './product-readiness-capture';

import {
  businessKey,
  exactApprovalApiPath,
  pcUrl,
  tenantId,
} from './product-readiness-pc-h5-runtime-api';

export interface ApprovalActionExpectation {
  actorId: string;
  businessKey: string;
  processInstanceId: string;
  taskId: string;
}

function responsePath(response: Response) {
  try {
    return new URL(response.url()).pathname;
  } catch {
    return '';
  }
}

async function exactApprovalResponse(
  response: Response,
  expectation: ApprovalActionExpectation,
) {
  const request = response.request();
  const headers = request.headers();
  if (request.method() !== 'POST'
    || !exactApprovalApiPath(
      response.url(),
      `/api/approval/tasks/${expectation.taskId}/approve`,
    )
    || response.status() !== 200
    || headers['x-tenant-id'] !== tenantId
    || headers['x-operator-id'] !== expectation.actorId) {
    return false;
  }

  try {
    const body = await response.json() as {
      completedTaskId?: string;
      instanceId?: string;
    };
    return body.completedTaskId === expectation.taskId
      && body.instanceId === expectation.processInstanceId;
  } catch {
    return false;
  }
}

function exactLoginResponse(response: Response) {
  return response.request().method() === 'POST'
    && responsePath(response) === '/api/auth/login';
}

async function triggerApproval(
  page: Page,
  confirmation: Locator,
  expectation: ApprovalActionExpectation,
  budget: CaptureBudget,
  acknowledged: (budget: CaptureBudget, assertCurrent: () => Promise<void>) => Promise<void>,
) {
  await expect(confirmation).toBeVisible({ timeout: budget.remaining(5_000) });
  // Arm the exact positive feedback before the actual click. Capture it while
  // the drawer may be closing / H5 may still be on its old detail route.
  const success = page.locator('.el-message--success, uni-toast')
    .getByText('审批已同意', { exact: true });
  if (await budget.run(() => success.isVisible())) throw new Error('A prior approval acknowledgment is still visible');
  let active = true;
  const assertCurrent = async () => {
    budget.remaining();
    if (!active) throw new Error('Approval acknowledgment is no longer current');
    const visible = await budget.run(() => success.isVisible());
    if (!active || !visible) throw new Error('Approval acknowledgment is no longer current');
  };
  try {
    const acknowledgedCapture = expect(success).toBeVisible({ timeout: budget.remaining(30_000) })
      .then(async () => {
        await assertCurrent();
        await acknowledged(budget, assertCurrent);
      });
    const [response] = await Promise.all([
      page.waitForResponse(
        candidate => exactApprovalResponse(candidate, expectation),
        { timeout: budget.remaining(30_000) },
      ),
      confirmation.click({ timeout: budget.remaining(10_000) }),
      acknowledgedCapture,
    ]);
    return response;
  } finally {
    // A failed sibling must not later write an acknowledged success capture.
    active = false;
  }
}

export async function ensurePcLogin(page: Page) {
  await page.goto(pcUrl, { waitUntil: 'domcontentloaded' });

  const username = page.locator("input[name='username']");
  const password = page.locator("input[name='password']");
  const slider = page.locator("div[name='captcha']");
  const action = page.locator("div[name='captcha-action']");
  const login = page.getByRole('button', {
    name: 'login',
    exact: true,
  });

  await expect(username).toBeVisible({ timeout: 15_000 });
  await expect(password).toBeVisible();
  await expect(slider).toBeVisible();
  await expect(action).toBeVisible();
  await expect(login).toBeVisible();

  await username.fill('vben');
  await password.fill('123456');

  const sliderBox = await slider.boundingBox();
  const actionBox = await action.boundingBox();
  if (!sliderBox || !actionBox) {
    throw new Error('PC login captcha is not measurable');
  }
  const startX = actionBox.x + actionBox.width / 2;
  const startY = actionBox.y + actionBox.height / 2;
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  await page.mouse.move(
    sliderBox.x + sliderBox.width - actionBox.width / 2,
    startY,
    { steps: 24 },
  );
  await page.mouse.up();

  const movedActionBox = await action.boundingBox();
  if (!movedActionBox || movedActionBox.x <= actionBox.x) {
    throw new Error('PC login captcha did not move to a verified state');
  }

  const [loginResponse] = await Promise.all([
    page.waitForResponse(exactLoginResponse, { timeout: 30_000 }),
    login.click(),
  ]);
  expect(loginResponse.status()).toBe(200);
  await expect(username).toBeHidden({ timeout: 30_000 });
}

export async function clickPcApproval(
  page: Page,
  expectation: ApprovalActionExpectation,
  owner: CaptureBudget,
  acknowledged: (budget: CaptureBudget, assertCurrent: () => Promise<void>) => Promise<void>,
): Promise<Response> {
  const budget = owner.limit(30_000);
  const card = page.locator('.task-item')
    .filter({ hasText: expectation.businessKey })
    .first();
  await expect(card).toBeVisible({ timeout: budget.remaining(15_000) });
  await card.getByRole('button', { name: '处理', exact: true }).click({ timeout: budget.remaining(10_000) });
  await expect(page.getByText('审批详情', { exact: true })).toBeVisible({ timeout: budget.remaining(15_000) });
  await readySurface(page, budget, { client: 'pc', kind: 'detail', url: pcUrl, ...expectation, instanceId: expectation.processInstanceId });
  await expect(page.getByRole('button', { name: '同意', exact: true }).last()).toBeEnabled({ timeout: budget.remaining(10_000) });
  await page.getByRole('button', { name: '同意', exact: true }).last().click({
    timeout: budget.remaining(10_000),
  });

  await readySurface(page, budget.limit(5_000), { client: 'pc', kind: 'confirmation', url: pcUrl, ...expectation, instanceId: expectation.processInstanceId });
  return triggerApproval(
    page,
    page.getByRole('button', {
      name: '确认同意',
      exact: true,
    }),
    expectation,
    budget,
    acknowledged,
  );
}

export async function clickH5Approval(
  page: Page,
  expectation: ApprovalActionExpectation,
  stageLabel: '财务会签' | '财务审核' | '付款确认',
  owner: CaptureBudget,
  acknowledged: (budget: CaptureBudget, assertCurrent: () => Promise<void>) => Promise<void>,
): Promise<Response> {
  const budget = owner.limit(30_000);
  const detailUrl = new URL(page.url());
  detailUrl.hash = `/pages/task/detail?id=${encodeURIComponent(expectation.taskId)}`;
  const card = page.locator('.task-card')
    .filter({ hasText: expectation.businessKey })
    .first();
  await expect(card).toBeVisible({ timeout: budget.remaining(15_000) });
  await card.click({ timeout: budget.remaining(10_000) });
  await expect(page.getByText(stageLabel, { exact: true }).first())
    .toBeVisible({ timeout: budget.remaining(15_000) });

  await readySurface(page, budget, { client: 'h5', kind: 'detail', url: detailUrl.toString(), ...expectation, instanceId: expectation.processInstanceId });
  const actionBar = page.locator('.action-bar');
  await expect(actionBar).toBeVisible({ timeout: budget.remaining(10_000) });
  const wotButton = actionBar.locator('.wd-button.is-primary')
    .filter({ hasText: /^同意$/u })
    .last();
  const customElement = actionBar.locator('wd-button')
    .filter({ hasText: /^同意$/u })
    .last();
  const renderedButton = actionBar.getByRole('button', {
    name: '同意',
    exact: true,
  }).last();
  const uniButton = page.locator('.action-bar uni-button')
    .filter({ hasText: /^同意$/u })
    .last();
  const exactTextControl = actionBar.getByText('同意', {
    exact: true,
  }).last();
  const approvalButton = await wotButton.count() > 0
    ? wotButton
    : await customElement.count() > 0
      ? customElement
      : await renderedButton.count() > 0
        ? renderedButton
        : await uniButton.count() > 0
          ? uniButton
          : exactTextControl;
  await expect(approvalButton).toBeVisible({ timeout: budget.remaining(10_000) });
  await expect(approvalButton).toBeEnabled({ timeout: budget.remaining(10_000) });
  await approvalButton.click({ timeout: budget.remaining(10_000) });

  const modalPrimary = page.locator(
    'uni-modal .uni-modal__btn_primary',
  ).last();
  const confirmation = await modalPrimary.isVisible({ timeout: budget.remaining(3_000) })
    ? modalPrimary
    : page.getByText('确认同意', { exact: true }).last();

  return triggerApproval(page, confirmation, expectation, budget, acknowledged);
}
