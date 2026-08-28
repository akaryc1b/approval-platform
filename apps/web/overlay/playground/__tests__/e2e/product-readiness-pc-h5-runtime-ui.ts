import type { Locator, Page, Response } from '@playwright/test';
import { expect } from '@playwright/test';

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
) {
  await expect(confirmation).toBeVisible({ timeout: 5_000 });
  const [response] = await Promise.all([
    page.waitForResponse(
      candidate => exactApprovalResponse(candidate, expectation),
      { timeout: 30_000 },
    ),
    confirmation.click({ timeout: 10_000 }),
  ]);
  return response;
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
): Promise<Response> {
  const card = page.locator('.task-item')
    .filter({ hasText: expectation.businessKey })
    .first();
  await expect(card).toBeVisible();
  await card.getByRole('button', { name: '处理', exact: true }).click();
  await expect(page.getByText('审批详情', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '同意', exact: true }).last().click({
    timeout: 10_000,
  });

  return triggerApproval(
    page,
    page.getByRole('button', {
      name: '确认同意',
      exact: true,
    }),
    expectation,
  );
}

export async function clickH5Approval(
  page: Page,
  expectation: ApprovalActionExpectation,
  stageLabel: '财务会签' | '财务审核' | '付款确认',
): Promise<Response> {
  const card = page.locator('.task-card')
    .filter({ hasText: expectation.businessKey })
    .first();
  await expect(card).toBeVisible();
  await card.click({ timeout: 10_000 });
  await expect(page.getByText(stageLabel, { exact: true }).first())
    .toBeVisible();

  const actionBar = page.locator('.action-bar');
  await expect(actionBar).toBeVisible({ timeout: 10_000 });
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
  const uniButton = actionBar.locator('uni-button')
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
  await expect(approvalButton).toBeVisible({ timeout: 10_000 });
  await approvalButton.click({ timeout: 10_000 });

  const modalPrimary = page.locator(
    'uni-modal .uni-modal__btn_primary',
  ).last();
  const confirmation = await modalPrimary.isVisible({ timeout: 3_000 })
    ? modalPrimary
    : page.getByText('确认同意', { exact: true }).last();

  return triggerApproval(page, confirmation, expectation);
}
