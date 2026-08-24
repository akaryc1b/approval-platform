import type { Page, Response } from '@playwright/test';
import { expect } from '@playwright/test';

import { businessKey, pcUrl } from './product-readiness-pc-h5-runtime-api';

function responsePath(response: Response) {
  try {
    return new URL(response.url()).pathname;
  } catch {
    return '';
  }
}

function exactApprovalResponse(response: Response, taskId: string) {
  return response.request().method() === 'POST'
    && responsePath(response) === `/api/approval/tasks/${taskId}/approve`
    && response.status() === 200;
}

function exactLoginResponse(response: Response) {
  return response.request().method() === 'POST'
    && responsePath(response) === '/api/auth/login';
}

export async function ensurePcLogin(page: Page) {
  await page.goto(pcUrl, { waitUntil: 'domcontentloaded' });

  const username = page.locator("input[name='username']");
  const password = page.locator("input[name='password']");
  const slider = page.locator("div[name='captcha']");
  const action = page.locator("div[name='captcha-action']");
  const login = page.getByRole('button', {
    name: /login|登录/iu,
  }).last();

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
  taskId: string,
): Promise<Response> {
  const responsePromise = page.waitForResponse(
    response => exactApprovalResponse(response, taskId),
    { timeout: 30_000 },
  );

  const card = page.locator('.task-item')
    .filter({ hasText: businessKey })
    .first();
  await expect(card).toBeVisible();
  await card.getByRole('button', { name: '处理', exact: true }).click();
  await expect(page.getByText('审批详情', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '同意', exact: true }).last().click();
  await page.getByRole('button', {
    name: '确认同意',
    exact: true,
  }).click();

  return responsePromise;
}

export async function clickH5Approval(
  page: Page,
  taskId: string,
  stageLabel: '财务会签' | '财务审核',
): Promise<Response> {
  const responsePromise = page.waitForResponse(
    response => exactApprovalResponse(response, taskId),
    { timeout: 30_000 },
  );

  const card = page.locator('.task-card')
    .filter({ hasText: businessKey })
    .first();
  await expect(card).toBeVisible();
  await card.click();
  await expect(page.getByText(stageLabel, { exact: true }).first())
    .toBeVisible();
  await page.locator('.action-bar')
    .getByText('同意', { exact: true })
    .click();

  const modalPrimary = page.locator(
    'uni-modal .uni-modal__btn_primary, .uni-modal .uni-modal__btn_primary',
  ).last();
  if (await modalPrimary.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await modalPrimary.click();
  } else {
    await page.getByText('确认同意', { exact: true }).last().click();
  }

  return responsePromise;
}
