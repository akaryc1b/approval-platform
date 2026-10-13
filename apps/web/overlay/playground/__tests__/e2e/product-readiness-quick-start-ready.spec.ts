import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { expect, test } from '@playwright/test';

import {
  businessKey,
  pcUrl,
  tenantId,
} from './product-readiness-pc-h5-runtime-api';
import { inspectH5TaskComponents } from './product-readiness-h5-components';
import { testCaptureBudget } from './product-readiness-capture-budget';
import { captureFailurePhase } from './product-readiness-capture-diagnostics';
import { captureScreenshot, observeCaptureFailures, publishCaptureReceipt, readySurface } from './product-readiness-capture';
import { collectCjkFontEvidence } from './product-readiness-cjk-fonts';
import { ensurePcLogin } from './product-readiness-pc-h5-runtime-ui';

const captureStartedAt = performance.now();

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
}, testInfo) => {
  const phase = captureFailurePhase(testInfo);
  phase.enter('TEST_SETUP');
  const budget = testCaptureBudget(testInfo, captureStartedAt);
  const startedAt = new Date().toISOString();
  const context = await browser.newContext();
  const pc = await context.newPage();
  const h5 = await context.newPage();
  const pcFailures = observeCaptureFailures(pc);
  const h5Failures = observeCaptureFailures(h5);
  try {
    phase.enter('PC_AUTHENTICATION');
    await ensurePcLogin(pc);
    phase.enter('PC_NAVIGATION');
    const pcBudget = budget.limit(30_000);
    await pc.goto(pcUrl, { waitUntil: 'domcontentloaded', timeout: pcBudget.remaining() });
    const pcTask = pc.locator('.task-item')
      .filter({ hasText: businessKey })
      .first();
    phase.enter('PC_TASK_VISIBILITY');
    await expect(pcTask).toBeVisible({ timeout: pcBudget.remaining(30_000) });
    await expect(pc.getByText(businessKey, { exact: true }).first())
      .toBeVisible({ timeout: pcBudget.remaining(15_000) });
    phase.enter('PC_SURFACE_READINESS');
    const pcReadiness = await readySurface(pc, pcBudget, { client: 'pc', kind: 'list', url: pcUrl, businessKey, pendingTotal: 1, processedTotal: 0 });
    phase.enter('PC_FONT');
    const pcFont = await collectCjkFontEvidence(pc, 'list', pcBudget, true);
    phase.enter('PC_SCREENSHOT');
    pcFailures.assert();
    await captureScreenshot(pc, pcBudget, resolve(evidenceDirectory, 'quick-start-pc.png'), { readiness: pcReadiness, assertCurrent: async () => { pcFailures.assert(); await pcFont.assertCurrent(); } });

    phase.enter('H5_NAVIGATION');
    const h5Budget = budget.limit(30_000);
    await h5.goto(h5Url, { waitUntil: 'domcontentloaded', timeout: h5Budget.remaining() });
    const h5Task = h5.locator('.task-card')
      .filter({ hasText: businessKey })
      .first();
    phase.enter('H5_TASK_VISIBILITY');
    await expect(h5Task).toBeVisible({ timeout: h5Budget.remaining(30_000) });
    phase.enter('H5_SURFACE_READINESS');
    const h5Readiness = await readySurface(h5, h5Budget, { client: 'h5', kind: 'list', url: h5Url, businessKey, pendingTotal: 1, processedTotal: 0 });
    phase.enter('H5_FONT');
    const h5Font = await collectCjkFontEvidence(h5, 'list', h5Budget);
    phase.enter('H5_COMPONENTS');
    const h5Components = await h5Task.evaluate(inspectH5TaskComponents, undefined, {
      timeout: h5Budget.remaining(5_000),
    });
    expect(h5Components).toEqual({
      buttonsRendered: true,
      searchRendered: true,
      taskTagRendered: true,
      stylesApplied: true,
      unresolvedTags: 0,
    });
    phase.enter('H5_SCREENSHOT');
    h5Failures.assert();
    await captureScreenshot(h5, h5Budget, resolve(evidenceDirectory, 'quick-start-h5.png'), { readiness: h5Readiness, assertCurrent: async () => { h5Failures.assert(); await h5Font.assertCurrent(); } });

    phase.enter('RECEIPT_PUBLICATION');
    budget.remaining();
    pcFailures.assert();
    h5Failures.assert();
    const receiptPath = resolve(evidenceDirectory, 'quick-start-browser-evidence.json');
    publishCaptureReceipt(budget, receiptPath, () => {
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
            cjkGlyphsRendered: true,
            font: pcFont.evidence,
            screenshot: 'quick-start-pc.png',
            capturePhase: 'READY_BEFORE_ACTION',
            readiness: pcReadiness,
          },
          h5: {
            actorId: h5ActorId,
            url: h5.url(),
            businessKeyVisible: true,
            cjkGlyphsRendered: true,
            font: h5Font.evidence,
            components: h5Components,
            screenshot: 'quick-start-h5.png',
            capturePhase: 'READY_BEFORE_ACTION',
            readiness: h5Readiness,
          },
        }, null, 2)}\n`,
        { encoding: 'utf8', mode: 0o600 },
      );
    });
  } catch (error) {
    phase.preserveFailure();
    throw error;
  } finally {
    phase.enter('CLEANUP');
    pcFailures.dispose();
    h5Failures.dispose();
    await context.close();
  }
});
