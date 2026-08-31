import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { expect, test } from '@playwright/test';

import {
  businessKey,
  pcUrl,
  tenantId,
} from './product-readiness-pc-h5-runtime-api';
import { ensurePcLogin } from './product-readiness-pc-h5-runtime-ui';

function requiredEnvironment(name: string) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

const evidenceDirectory = requiredEnvironment('APPROVAL_DEMO_EVIDENCE_DIR');
const exactHeadSha = requiredEnvironment('APPROVAL_DEMO_EXACT_HEAD_SHA');
const h5Url = requiredEnvironment('APPROVAL_DEMO_H5_URL');
const configuredBusinessKey = requiredEnvironment(
  'APPROVAL_DEMO_QUICK_START_BUSINESS_KEY',
);
const h5ActorId = requiredEnvironment('APPROVAL_DEMO_QUICK_START_H5_ACTOR');
const pcActorId = requiredEnvironment('APPROVAL_DEMO_QUICK_START_PC_ACTOR');
const configuredTenant = requiredEnvironment('APPROVAL_DEMO_QUICK_START_TENANT');

if (configuredBusinessKey !== businessKey || configuredTenant !== tenantId) {
  throw new Error('Quick Start browser identity does not match governed scenario');
}
if (pcActorId !== h5ActorId) {
  throw new Error('Quick Start PC and H5 must expose the same governed task');
}

test('a new user can see the seeded purchase-payment request in PC and H5', async ({
  browser,
}) => {
  const startedAt = new Date().toISOString();
  const context = await browser.newContext();
  const pc = await context.newPage();
  const h5 = await context.newPage();
  try {
    await ensurePcLogin(pc);
    await pc.goto(pcUrl, { waitUntil: 'domcontentloaded' });
    const pcTask = pc.locator('.task-item')
      .filter({ hasText: businessKey })
      .first();
    await expect(pcTask).toBeVisible({ timeout: 30_000 });
    await expect(pc.getByText(businessKey, { exact: true }).first())
      .toBeVisible();
    await pc.screenshot({
      fullPage: true,
      path: resolve(evidenceDirectory, 'quick-start-pc.png'),
    });

    await h5.goto(h5Url, { waitUntil: 'domcontentloaded' });
    const h5Task = h5.locator('.task-card')
      .filter({ hasText: businessKey })
      .first();
    await expect(h5Task).toBeVisible({ timeout: 30_000 });
    await h5.screenshot({
      fullPage: true,
      path: resolve(evidenceDirectory, 'quick-start-h5.png'),
    });

    writeFileSync(
      resolve(evidenceDirectory, 'quick-start-browser-evidence.json'),
      `${JSON.stringify({
        schemaVersion: 1,
        evidenceKind: 'QUICK_START_BROWSER_READY_V1',
        status: 'PASSED',
        commitSha: exactHeadSha,
        tenantId,
        businessKey,
        startedAt,
        completedAt: new Date().toISOString(),
        pc: {
          actorId: pcActorId,
          url: pc.url(),
          businessKeyVisible: true,
          screenshot: 'quick-start-pc.png',
        },
        h5: {
          actorId: h5ActorId,
          url: h5.url(),
          businessKeyVisible: true,
          screenshot: 'quick-start-h5.png',
        },
      }, null, 2)}\n`,
      { encoding: 'utf8', mode: 0o600 },
    );
  } finally {
    await context.close();
  }
});
