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
) {
  await page.evaluate(async () => {
    await document.fonts.ready;
  });
  const evidence = await page.evaluate((sample) => {
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
  }, cjkProbeText);

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
    const pcFont = await collectCjkFontEvidence(pc, true);
    await pc.screenshot({
      fullPage: true,
      path: resolve(evidenceDirectory, 'quick-start-pc.png'),
    });

    await h5.goto(h5Url, { waitUntil: 'domcontentloaded' });
    const h5Task = h5.locator('.task-card')
      .filter({ hasText: businessKey })
      .first();
    await expect(h5Task).toBeVisible({ timeout: 30_000 });
    const h5Font = await collectCjkFontEvidence(h5, false);
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
          cjkGlyphsRendered: true,
          font: pcFont,
          screenshot: 'quick-start-pc.png',
        },
        h5: {
          actorId: h5ActorId,
          url: h5.url(),
          businessKeyVisible: true,
          cjkGlyphsRendered: true,
          font: h5Font,
          screenshot: 'quick-start-h5.png',
        },
      }, null, 2)}\n`,
      { encoding: 'utf8', mode: 0o600 },
    );
  } finally {
    await context.close();
  }
});
