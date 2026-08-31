import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { expect, test } from '@playwright/test';

import { ensurePcLogin } from './product-readiness-pc-h5-runtime-ui';

function requiredEnvironment(name: string) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

const evidenceDirectory = requiredEnvironment('APPROVAL_DEMO_EVIDENCE_DIR');
const exactHeadSha = requiredEnvironment('APPROVAL_DEMO_EXACT_HEAD_SHA');
const tenantId = requiredEnvironment('APPROVAL_QUICK_START_TENANT_ID');
const businessKey = requiredEnvironment('APPROVAL_QUICK_START_BUSINESS_KEY');
const actorId = requiredEnvironment('APPROVAL_QUICK_START_ACTOR_ID');
const pcUrl = requiredEnvironment('APPROVAL_DEMO_PC_URL');
const h5Url = requiredEnvironment('APPROVAL_DEMO_H5_URL');

test('governed PC and H5 Quick Start pages are visibly ready', async ({
  browser,
}) => {
  const startedAt = new Date().toISOString();
  const context = await browser.newContext();
  const pc = await context.newPage();
  const h5 = await context.newPage();
  const requests: Array<{
    client: 'pc' | 'h5';
    method: string;
    operatorId: string | undefined;
    tenantId: string | undefined;
    url: string;
  }> = [];

  const observe = (client: 'pc' | 'h5', page: typeof pc) => {
    page.on('request', (request) => {
      const headers = request.headers();
      let pathname = '';
      try {
        pathname = new URL(request.url()).pathname;
      } catch {
        return;
      }
      if (!pathname.startsWith('/api/approval/')) return;
      requests.push({
        client,
        method: request.method(),
        operatorId: headers['x-operator-id'],
        tenantId: headers['x-tenant-id'],
        url: request.url(),
      });
    });
  };
  observe('pc', pc);
  observe('h5', h5);

  try {
    await ensurePcLogin(pc);
    await pc.goto(pcUrl, { waitUntil: 'domcontentloaded' });
    const pcCard = pc.locator('.task-item')
      .filter({ hasText: businessKey })
      .first();
    await expect(pcCard).toBeVisible({ timeout: 30_000 });
    await pc.screenshot({
      fullPage: true,
      path: resolve(evidenceDirectory, 'quick-start-pc-ready.png'),
    });

    await h5.goto(h5Url, { waitUntil: 'domcontentloaded' });
    const h5Card = h5.locator('.task-card')
      .filter({ hasText: businessKey })
      .first();
    await expect(h5Card).toBeVisible({ timeout: 30_000 });
    await h5.screenshot({
      fullPage: true,
      path: resolve(evidenceDirectory, 'quick-start-h5-ready.png'),
    });

    const approvalRequests = requests.filter(request =>
      request.url.includes('/api/approval/'));
    expect(approvalRequests.length).toBeGreaterThan(0);
    for (const request of approvalRequests) {
      expect(request.method).toBe('GET');
      expect(request.tenantId).toBe(tenantId);
      expect(request.operatorId).toBe(actorId);
    }

    writeFileSync(
      resolve(evidenceDirectory, 'quick-start-browser-evidence.json'),
      `${JSON.stringify({
        schemaVersion: 1,
        evidenceKind: 'QUICK_START_BROWSER_READY_V1',
        status: 'PASSED',
        exactHeadSha,
        tenantId,
        businessKey,
        actorId,
        pcUrl,
        h5Url,
        requests: approvalRequests,
        startedAt,
        completedAt: new Date().toISOString(),
      }, null, 2)}\n`,
      { encoding: 'utf8', mode: 0o600 },
    );
  } finally {
    await context.close();
  }
});
