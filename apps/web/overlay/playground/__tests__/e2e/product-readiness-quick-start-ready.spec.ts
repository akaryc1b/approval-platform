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
import type { CaptureBudget } from './product-readiness-capture-budget';
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

const cjkProbeText = '审批任务采购付款工作台';
const cjkFontFamilies = [
  'Noto Sans CJK SC',
  'Noto Sans SC',
  'Source Han Sans SC',
  'PingFang SC',
  'Hiragino Sans GB',
  'Microsoft YaHei',
  'WenQuanYi Micro Hei',
];

if (configuredBusinessKey !== businessKey || configuredTenant !== tenantId) {
  throw new Error('Quick Start browser identity does not match governed scenario');
}
if (pcActorId !== h5ActorId) {
  throw new Error('Quick Start PC and H5 must expose the same governed task');
}

async function collectCjkFontEvidence(
  page: import('@playwright/test').Page,
  requireExplicitStack: boolean,
  budget: CaptureBudget,
) {
  await budget.run(() => page.evaluate(async () => {
    await document.fonts.ready;
  }));
  const evidence = await budget.run(() => page.evaluate((sample) => {
    const rootStyle = getComputedStyle(document.documentElement);
    const bodyStyle = getComputedStyle(document.body);
    const canvas = document.createElement('canvas');
    canvas.width = 96;
    canvas.height = 96;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('Canvas 2D context is unavailable');

    const glyphs = [...sample].map((character) => {
      context.clearRect(0, 0, canvas.width, canvas.height);
      context.fillStyle = '#000';
      context.font = `48px ${bodyStyle.fontFamily}`;
      context.textBaseline = 'top';
      context.fillText(character, 8, 8);
      const pixels = context.getImageData(
        0,
        0,
        canvas.width,
        canvas.height,
      ).data;
      let hash = 2_166_136_261;
      let inkPixels = 0;
      for (let index = 3; index < pixels.length; index += 4) {
        const alpha = pixels[index] ?? 0;
        if (alpha > 0) inkPixels += 1;
        hash ^= alpha;
        hash = Math.imul(hash, 16_777_619);
      }
      return {
        character,
        hash: (hash >>> 0).toString(16).padStart(8, '0'),
        inkPixels,
      };
    });

    return {
      cssVariable: rootStyle.getPropertyValue('--font-family').trim(),
      computedFontFamily: bodyStyle.fontFamily,
      glyphs,
      minimumInkPixels: Math.min(...glyphs.map(glyph => glyph.inkPixels)),
      uniqueGlyphHashes: new Set(glyphs.map(glyph => glyph.hash)).size,
    };
  }, cjkProbeText));

  expect(evidence.minimumInkPixels).toBeGreaterThan(20);
  expect(evidence.uniqueGlyphHashes).toBeGreaterThanOrEqual(6);
  if (requireExplicitStack) {
    expect(
      cjkFontFamilies.some(family =>
        `${evidence.cssVariable},${evidence.computedFontFamily}`
          .includes(family)),
    ).toBe(true);
  }

  return {
    ...evidence,
    cjkGlyphsRendered: true,
  };
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
    const pcFont = await collectCjkFontEvidence(pc, true, pcBudget);
    phase.enter('PC_SCREENSHOT');
    pcFailures.assert();
    await captureScreenshot(pc, pcBudget, resolve(evidenceDirectory, 'quick-start-pc.png'), { readiness: pcReadiness, assertCurrent: async () => pcFailures.assert() });

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
    const h5Font = await collectCjkFontEvidence(h5, false, h5Budget);
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
    await captureScreenshot(h5, h5Budget, resolve(evidenceDirectory, 'quick-start-h5.png'), { readiness: h5Readiness, assertCurrent: async () => h5Failures.assert() });

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
            font: pcFont,
            screenshot: 'quick-start-pc.png',
            capturePhase: 'READY_BEFORE_ACTION',
            readiness: pcReadiness,
          },
          h5: {
            actorId: h5ActorId,
            url: h5.url(),
            businessKeyVisible: true,
            cjkGlyphsRendered: true,
            font: h5Font,
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
