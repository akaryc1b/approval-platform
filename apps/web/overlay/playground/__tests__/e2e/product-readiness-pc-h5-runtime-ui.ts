import type { Page, Response } from '@playwright/test';
import { expect } from '@playwright/test';

import { businessKey, pcUrl } from './product-readiness-pc-h5-runtime-api';

function exactApprovalResponse(response: Response, taskId: string) {
  let pathname = '';
  try {
    pathname = new URL(response.url()).pathname;
  } catch {
    return false;
  }
  return response.request().method() === 'POST'
    && pathname === `/api/approval/tasks/${taskId}/approve`
    && response.status() === 200;
}

export async function ensurePcLogin(page: Page) {
  await page.goto(pcUrl, { waitUntil: 'domcontentloaded' });
  const username = page.locator("input[name='username']");
  const loginVisible = await username
    .isVisible({ timeout: 5_000 })
    .catch(() => false);
  if (!loginVisible) return;

  await username.fill('vben');
  await page.locator("input[name='password']").fill('123456');

  const slider = page.locator("div[name='captcha']");
  const action = page.locator("div[name='captcha-action']");
  if (await slider.isVisible({ timeout: 2_000 }).catch(() => false)) {
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
  }

  const namedLogin = page.getByRole('button', {
    name: /login|登录/iu,
  }).last();
  if (await namedLogin.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await namedLogin.click();
  } else {
    await page.locator("button[type='submit']").first().click();
  }
  await expect(username).toBeHidden({ timeout: 15_000 });
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
