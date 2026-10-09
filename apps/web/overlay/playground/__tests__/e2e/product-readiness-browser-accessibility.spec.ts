import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import type { Locator, Page } from '@playwright/test';
import { expect, test } from '@playwright/test';

import {
  businessKey,
  pcUrl,
  tenantId,
} from './product-readiness-pc-h5-runtime-api';
import { testCaptureBudget } from './product-readiness-capture-budget';
import { captureFailurePhase, capturePageErrorContext } from './product-readiness-capture-diagnostics';
import type { CaptureBudget } from './product-readiness-capture-budget';
import { captureScreenshot, observeCaptureFailures, publishCaptureReceipt, readySurface } from './product-readiness-capture';
import { ensurePcLogin } from './product-readiness-pc-h5-runtime-ui';
import { detailLayoutEvidence, detailLayoutViolations, footerLayoutEvidence, footerLayoutViolations } from './product-readiness-detail-layout';

const captureStartedAt = performance.now();

function requiredValue(label: string, value: string | undefined) {
  const normalized = value?.trim();
  if (!normalized) throw new Error(`${label} is required`);
  return normalized;
}

const repositoryRoot = requiredValue(
  'APPROVAL_DEMO_REPOSITORY_ROOT',
  process.env.APPROVAL_DEMO_REPOSITORY_ROOT,
);
const evidenceDirectory = requiredValue(
  'APPROVAL_BROWSER_ACCESSIBILITY_EVIDENCE_DIR',
  process.env.APPROVAL_BROWSER_ACCESSIBILITY_EVIDENCE_DIR,
);
const exactHeadSha = requiredValue(
  'APPROVAL_DEMO_EXACT_HEAD_SHA',
  process.env.APPROVAL_DEMO_EXACT_HEAD_SHA,
);
const exactTreeSha = requiredValue(
  'APPROVAL_DEMO_EXACT_TREE_SHA',
  process.env.APPROVAL_DEMO_EXACT_TREE_SHA,
);
const h5Url = requiredValue(
  'APPROVAL_DEMO_H5_URL',
  process.env.APPROVAL_DEMO_H5_URL,
);
const configuredBusinessKey = requiredValue(
  'APPROVAL_DEMO_QUICK_START_BUSINESS_KEY',
  process.env.APPROVAL_DEMO_QUICK_START_BUSINESS_KEY,
);
const h5ActorId = requiredValue(
  'APPROVAL_DEMO_QUICK_START_H5_ACTOR',
  process.env.APPROVAL_DEMO_QUICK_START_H5_ACTOR,
);
const pcActorId = requiredValue(
  'APPROVAL_DEMO_QUICK_START_PC_ACTOR',
  process.env.APPROVAL_DEMO_QUICK_START_PC_ACTOR,
);
const configuredTenant = requiredValue(
  'APPROVAL_DEMO_QUICK_START_TENANT',
  process.env.APPROVAL_DEMO_QUICK_START_TENANT,
);

const matrix = JSON.parse(readFileSync(resolve(
  repositoryRoot,
  'config/demo/browser-accessibility-matrix.json',
), 'utf8')) as {
  locale: string;
  projects: Array<{
    engine: 'chromium' | 'firefox' | 'webkit';
    id: 'bundled-firefox' | 'bundled-webkit' | 'system-chromium';
    runtime: string;
    scope: string;
  }>;
  thresholds: {
    criticalViolations: number;
    minimumContrastRatio: number;
    minimumInkPixels: number;
    minimumUniqueCjkGlyphHashes: number;
    seriousViolations: number;
  };
  viewports: {
    h5: { height: number; width: number };
    pc: { height: number; width: number };
  };
};

if (configuredBusinessKey !== businessKey || configuredTenant !== tenantId) {
  throw new Error('browser matrix identity does not match governed scenario');
}
if (pcActorId !== h5ActorId) {
  throw new Error('browser matrix PC and H5 must expose the same task');
}

interface Violation {
  detail: string;
  rule: string;
  selector: string;
  severity: 'critical' | 'serious';
}

const cjkProbeText = '审批任务采购付款工作台';
const projectIds = new Set([
  'system-chromium',
  'bundled-firefox',
  'bundled-webkit',
]);
const evidenceNames = new Set([
  'h5-task-detail.png',
  'h5-task-list.png',
  'matrix-evidence.json',
  'pc-confirmation-dialog.png',
  'pc-task-detail.png',
  'pc-task-list.png',
]);

async function collectCjkEvidence(page: Page, budget: CaptureBudget) {
  await budget.run(() => page.evaluate(async () => {
    await document.fonts.ready;
  }));
  const evidence = await budget.run(() => page.evaluate((sample) => {
    const root = getComputedStyle(document.documentElement);
    const body = getComputedStyle(document.body);
    const canvas = document.createElement('canvas');
    canvas.width = 96;
    canvas.height = 96;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('Canvas 2D context is unavailable');
    const glyphs = [...sample].map((character) => {
      context.clearRect(0, 0, canvas.width, canvas.height);
      context.fillStyle = '#000';
      context.font = `48px ${body.fontFamily}`;
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
      computedFontFamily: body.fontFamily,
      cssVariable: root.getPropertyValue('--font-family').trim(),
      glyphs,
      minimumInkPixels: Math.min(...glyphs.map(value => value.inkPixels)),
      uniqueGlyphHashes: new Set(glyphs.map(value => value.hash)).size,
    };
  }, cjkProbeText));
  expect(evidence.minimumInkPixels).toBeGreaterThanOrEqual(
    matrix.thresholds.minimumInkPixels,
  );
  expect(evidence.uniqueGlyphHashes).toBeGreaterThanOrEqual(
    matrix.thresholds.minimumUniqueCjkGlyphHashes,
  );
  return { ...evidence, cjkGlyphsRendered: true };
}

async function controlEvidence(control: Locator, selector: string, budget: CaptureBudget) {
  await expect(control).toBeVisible({ timeout: budget.remaining(20_000) });
  await expect(control).toBeEnabled({ timeout: budget.remaining(20_000) });
  return budget.run(() => control.evaluate((element, label) => {
    function channels(value: string) {
      const match = value.match(
        /rgba?\(\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)(?:\s*,\s*(\d+(?:\.\d+)?))?\s*\)/u,
      );
      return match
        ? {
            a: match[4] === undefined ? 1 : Number(match[4]),
            b: Number(match[3]),
            g: Number(match[2]),
            r: Number(match[1]),
          }
        : null;
    }
    function background(start: Element) {
      let current: Element | null = start;
      while (current) {
        const color = channels(getComputedStyle(current).backgroundColor);
        if (color && color.a > 0.01) return color;
        current = current.parentElement;
      }
      return { a: 1, b: 255, g: 255, r: 255 };
    }
    function luminance(color: { b: number; g: number; r: number }) {
      const channel = (raw: number) => {
        const value = raw / 255;
        return value <= 0.03928
          ? value / 12.92
          : ((value + 0.055) / 1.055) ** 2.4;
      };
      return 0.2126 * channel(color.r)
        + 0.7152 * channel(color.g)
        + 0.0722 * channel(color.b);
    }
    const labelledBy = element.getAttribute('aria-labelledby')
      ?.split(/\s+/u)
      .map(id => document.getElementById(id)?.textContent?.trim() ?? '')
      .filter(Boolean)
      .join(' ');
    const labels = 'labels' in element
      ? [...((element as HTMLInputElement).labels ?? [])]
          .map(value => value.textContent?.trim() ?? '')
          .filter(Boolean)
          .join(' ')
      : '';
    const accessibleName = [
      element.getAttribute('aria-label'),
      labelledBy,
      labels,
      element.getAttribute('alt'),
      element.textContent?.replace(/\s+/gu, ' ').trim(),
      element.getAttribute('title'),
      element.getAttribute('placeholder'),
    ].find(value => value?.trim())?.trim() ?? '';
    const style = getComputedStyle(element);
    const foreground = channels(style.color);
    const backdrop = background(element);
    const ratio = foreground
      ? (Math.max(luminance(foreground), luminance(backdrop)) + 0.05)
        / (Math.min(luminance(foreground), luminance(backdrop)) + 0.05)
      : 0;
    return {
      accessibleName,
      contrastRatio: Number(ratio.toFixed(3)),
      focusStyle: {
        boxShadow: style.boxShadow,
        outlineStyle: style.outlineStyle,
        outlineWidth: style.outlineWidth,
      },
      role: element.getAttribute('role') || element.tagName.toLowerCase(),
      selector: label,
      tabIndex: (element as HTMLElement).tabIndex,
    };
  }, selector));
}

async function auditControls(
  controls: Array<{ label: string; locator: Locator }>,
  budget: CaptureBudget,
) {
  const serious: Violation[] = [];
  const evidence = [];
  for (const item of controls) {
    const value = await controlEvidence(item.locator, item.label, budget);
    evidence.push(value);
    if (!value.accessibleName) {
      serious.push({
        detail: 'critical control has no programmatic name',
        rule: 'control-name',
        selector: item.label,
        severity: 'serious',
      });
    }
    if (value.tabIndex < 0) {
      serious.push({
        detail: 'named control is not keyboard focusable',
        rule: 'keyboard-focusable',
        selector: item.label,
        severity: 'serious',
      });
    }
    if (value.contrastRatio < matrix.thresholds.minimumContrastRatio) {
      serious.push({
        detail: `contrast ${value.contrastRatio} is below ${matrix.thresholds.minimumContrastRatio}`,
        rule: 'targeted-text-contrast',
        selector: item.label,
        severity: 'serious',
      });
    }
  }
  return { evidence, serious };
}

async function documentEvidence(page: Page, surface: string, budget: CaptureBudget) {
  return budget.run(() => page.evaluate((name) => {
    const serious: Violation[] = [];
    const lang = document.documentElement.lang.trim();
    if (!lang) {
      serious.push({
        detail: 'document language is missing',
        rule: 'html-lang',
        selector: name,
        severity: 'serious',
      });
    }
    const duplicateIds = Array.from(document.querySelectorAll('[id]'))
      .map(element => element.id)
      .filter(Boolean)
      .filter((id, index, values) => values.indexOf(id) !== index);
    for (const id of [...new Set(duplicateIds)]) {
      serious.push({
        detail: `duplicate id ${id}`,
        rule: 'duplicate-id',
        selector: name,
        severity: 'serious',
      });
    }
    return { lang, serious };
  }, surface));
}

async function tabTo(
  page: Page,
  target: Locator,
  label: string,
  budget: CaptureBudget,
  maximumTabs = 120,
) {
  const sequence = [];
  for (let index = 0; index < maximumTabs; index += 1) {
    await budget.run(() => page.keyboard.press('Tab'));
    const active = await budget.run(() => page.evaluate(() => {
      const element = document.activeElement as HTMLElement | null;
      if (!element) return null;
      const style = getComputedStyle(element);
      return {
        ariaLabel: element.getAttribute('aria-label'),
        boxShadow: style.boxShadow,
        outlineStyle: style.outlineStyle,
        outlineWidth: style.outlineWidth,
        role: element.getAttribute('role'),
        tag: element.tagName.toLowerCase(),
        text: element.textContent?.replace(/\s+/gu, ' ').trim().slice(0, 120),
      };
    }));
    sequence.push(active);
    if (await budget.run(() => target.evaluate(element => element === document.activeElement))) {
      const visibleFocus = active
        && ((active.outlineStyle !== 'none'
          && active.outlineWidth !== '0px')
          || (active.boxShadow !== 'none' && active.boxShadow !== ''));
      expect(visibleFocus, `${label} must expose visible focus`).toBe(true);
      return sequence;
    }
  }
  throw new Error(`${label} was not reached by keyboard`);
}

async function exactTextButton(container: Locator, label: '同意' | '驳回', budget: CaptureBudget) {
  const roleButton = container.getByRole('button', {
    name: label,
    exact: true,
  }).last();
  if (await budget.run(() => roleButton.count()) > 0) return roleButton;
  const candidates = container.locator('button, [role="button"], .wd-button');
  const count = await budget.run(() => candidates.count());
  for (let index = 0; index < count; index += 1) {
    const candidate = candidates.nth(index);
    const text = (await budget.run(timeout => candidate.textContent({ timeout })))?.replace(/\s+/gu, ' ').trim();
    if (text === label) return candidate;
  }
  throw new Error(`H5 ${label} action does not expose a button`);
}

function evidencePath(
  projectId: 'bundled-firefox' | 'bundled-webkit' | 'system-chromium',
  name: string,
) {
  if (!projectIds.has(projectId) || !evidenceNames.has(name)) {
    throw new Error('browser evidence path is not governed');
  }
  const directory = resolve(evidenceDirectory, projectId);
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  return resolve(directory, name);
}

test('PC and H5 expose the bounded browser/accessibility matrix', async ({
  browser,
}, testInfo) => {
  const phase = captureFailurePhase(testInfo);
  phase.enter('TEST_SETUP');
  const budget = testCaptureBudget(testInfo, captureStartedAt);
  const captures: unknown[] = [];
  const project = matrix.projects.find(
    value => value.id === testInfo.project.name,
  );
  if (!project) throw new Error(`unknown project ${testInfo.project.name}`);
  const pcContext = await browser.newContext({
    locale: matrix.locale,
    viewport: matrix.viewports.pc,
  });
  const h5Context = await browser.newContext({
    locale: matrix.locale,
    viewport: matrix.viewports.h5,
  });
  const pc = await pcContext.newPage();
  const h5 = await h5Context.newPage();
  const startedAt = new Date().toISOString();
  const keyboardSequence: unknown[] = [];
  const pcFailures = observeCaptureFailures(pc);
  const h5Failures = observeCaptureFailures(h5);
  try {
    phase.enter('PC_AUTHENTICATION');
    await ensurePcLogin(pc);
    phase.enter('PC_NAVIGATION');
    const pcBudget = budget.limit(30_000);
    await pc.goto(pcUrl, { waitUntil: 'domcontentloaded', timeout: pcBudget.remaining() });
    const pcTask = pc.locator('.task-item')
      .filter({ hasText: businessKey }).first();
    phase.enter('PC_TASK_VISIBILITY');
    await expect(pcTask).toBeVisible({ timeout: pcBudget.remaining(30_000) });
    phase.enter('PC_SURFACE_READINESS');
    const pcReady = await readySurface(pc, pcBudget, { client: 'pc', kind: 'list', url: pcUrl, businessKey, pendingTotal: 1, processedTotal: 0 });
    captures.push({ file: 'pc-task-list.png', phase: 'READY_BEFORE_ACTION', readiness: pcReady });
    const pcHandle = pcTask.getByRole('button', {
      name: '处理',
      exact: true,
    });
    phase.enter('PC_AUDIT');
    const pcList = await auditControls([
      { label: 'pc-task-handle', locator: pcHandle },
    ], pcBudget);
    const pcDocument = await documentEvidence(pc, 'pc-task-list', pcBudget);
    phase.enter('PC_FONT');
    const pcCjk = await collectCjkEvidence(pc, pcBudget);
    phase.enter('PC_SCREENSHOT');
    pcFailures.assert();
    await captureScreenshot(pc, pcBudget, evidencePath(project.id, 'pc-task-list.png'), { readiness: pcReady, assertCurrent: async () => pcFailures.assert() });

    phase.enter('PC_DETAIL_ACTION');
    const detailBudget = budget.limit(20_000);
    if (project.id === 'system-chromium') {
      keyboardSequence.push(...await tabTo(pc, pcHandle, 'PC task handle', detailBudget));
      await detailBudget.run(() => pc.keyboard.press('Enter'));
    } else {
      await pcHandle.click({ timeout: detailBudget.remaining(20_000) });
    }
    await expect(pc.getByText('审批详情', { exact: true }).first())
      .toBeVisible({ timeout: detailBudget.remaining(20_000) });
    const pcDetailExpected = { client: 'pc' as const, kind: 'detail' as const, url: pcUrl, businessKey, taskId: pcReady.taskId || undefined, instanceId: pcReady.instanceId || undefined };
    phase.enter('PC_DETAIL_READINESS');
    let pcDetailReady = await readySurface(pc, detailBudget, pcDetailExpected);
    const agree = pc.getByRole('button', {
      name: '同意',
      exact: true,
    }).last();
    phase.enter('PC_DETAIL_AUDIT');
    const pcDetail = await auditControls([
      { label: 'pc-agree', locator: agree },
    ], detailBudget);
    let authenticatedPcTaskFlow = false;
    if (project.id === 'system-chromium') {
      phase.enter('PC_CONFIRMATION');
      keyboardSequence.push(...await tabTo(pc, agree, 'PC agree', detailBudget));
      await detailBudget.run(() => pc.keyboard.press('Enter'));
      const confirmation = pc.getByRole('button', {
        name: '确认同意',
        exact: true,
      });
      await expect(confirmation).toBeVisible({ timeout: detailBudget.remaining(10_000) });
      keyboardSequence.push(...await tabTo(
        pc,
        confirmation,
        'PC confirmation',
        detailBudget,
      ));
      const confirmationBudget = detailBudget.limit(10_000);
      const confirmationReady = await readySurface(pc, confirmationBudget, { ...pcDetailExpected, kind: 'confirmation' });
      captures.push({ file: 'pc-confirmation-dialog.png', phase: 'READY_BEFORE_ACTION', readiness: confirmationReady });
      pcFailures.assert();
      await captureScreenshot(pc, confirmationBudget, evidencePath(project.id, 'pc-confirmation-dialog.png'), { readiness: confirmationReady, assertCurrent: async () => pcFailures.assert() });
      await detailBudget.run(() => pc.keyboard.press('Escape'));
      await expect(confirmation).toBeHidden({ timeout: detailBudget.remaining(10_000) });
      await expect(pc.locator('.el-message-box__wrapper:visible, .el-overlay.is-message-box:visible')).toHaveCount(0, { timeout: detailBudget.remaining(10_000) });
      await expect(agree).toBeFocused({ timeout: detailBudget.remaining(10_000) });
      pcDetailReady = await readySurface(pc, detailBudget, pcDetailExpected);
      authenticatedPcTaskFlow = true;
    }
    phase.enter('PC_DETAIL_SCREENSHOT');
    captures.push({ file: 'pc-task-detail.png', phase: 'READY_BEFORE_ACTION', readiness: pcDetailReady });
    pcFailures.assert();
    await captureScreenshot(pc, detailBudget, evidencePath(project.id, 'pc-task-detail.png'), { readiness: pcDetailReady, assertCurrent: async () => pcFailures.assert() });

    phase.enter('H5_NAVIGATION');
    const h5Budget = budget.limit(30_000);
    await h5.goto(h5Url, { waitUntil: 'domcontentloaded', timeout: h5Budget.remaining() });
    const h5Task = h5.locator('.task-card')
      .filter({ hasText: businessKey }).first();
    phase.enter('H5_TASK_VISIBILITY');
    await expect(h5Task).toBeVisible({ timeout: h5Budget.remaining(30_000) });
    phase.enter('H5_SURFACE_READINESS');
    const h5Ready = await readySurface(h5, h5Budget, { client: 'h5', kind: 'list', url: h5Url, businessKey, pendingTotal: 1, processedTotal: 0 });
    captures.push({ file: 'h5-task-list.png', phase: 'READY_BEFORE_ACTION', readiness: h5Ready });
    phase.enter('H5_AUDIT');
    const h5Document = await documentEvidence(h5, 'h5-task-list', h5Budget);
    phase.enter('H5_FONT');
    const h5Cjk = await collectCjkEvidence(h5, h5Budget);
    phase.enter('H5_SCREENSHOT');
    h5Failures.assert();
    await captureScreenshot(h5, h5Budget, evidencePath(project.id, 'h5-task-list.png'), { readiness: h5Ready, assertCurrent: async () => h5Failures.assert() });
    phase.enter('H5_DETAIL_ACTION');
    const h5DetailBudget = budget.limit(20_000);
    await h5Task.click({ timeout: h5DetailBudget.remaining(20_000) });
    // The action bar is visible while detail requests are still in flight.
    // Audit the loaded task, not its intentionally disabled loading controls.
    await expect(h5.locator('.summary-card').filter({ hasText: businessKey }))
      .toBeVisible({ timeout: h5DetailBudget.remaining(20_000) });
    const detailUrl = new URL(h5Url);
    detailUrl.hash = `/pages/task/detail?id=${encodeURIComponent(h5Ready.taskId || '')}`;
    phase.enter('H5_DETAIL_READINESS');
    const h5DetailReady = await readySurface(h5, h5DetailBudget, { client: 'h5', kind: 'detail', url: detailUrl.toString(), businessKey, taskId: h5Ready.taskId || undefined, instanceId: h5Ready.instanceId || undefined });
    captures.push({ file: 'h5-task-detail.png', phase: 'READY_BEFORE_ACTION', readiness: h5DetailReady });
    phase.enter('H5_DETAIL_AUDIT');
    const actionBar = h5.locator('.action-bar');
    await expect(actionBar).toBeVisible({ timeout: h5DetailBudget.remaining(20_000) });
    const h5Agree = await exactTextButton(actionBar, '同意', h5DetailBudget);
    const h5Reject = await exactTextButton(actionBar, '驳回', h5DetailBudget);
    const h5Detail = await auditControls([
      { label: 'h5-agree', locator: h5Agree },
      { label: 'h5-reject', locator: h5Reject },
    ], h5DetailBudget);
    phase.enter('H5_DETAIL_SCREENSHOT');
    h5Failures.assert();
    await captureScreenshot(h5, h5DetailBudget, evidencePath(project.id, 'h5-task-detail.png'), { readiness: h5DetailReady, assertCurrent: async () => h5Failures.assert() });

    phase.enter('MATRIX_ASSERTIONS');
    const pcLayout = await detailLayoutEvidence(pc, 'pc', budget);
    const h5Layout = await detailLayoutEvidence(h5, 'h5', budget);
    const h5Footer = await footerLayoutEvidence(h5, budget);
    expect(detailLayoutViolations(pcLayout), 'PC drawer text and tables stay inside their containers').toEqual([]);
    expect(detailLayoutViolations(h5Layout), 'narrow H5 long assistance values stay inside their columns').toEqual([]);
    expect(footerLayoutViolations(h5Footer), 'H5 actions and final content remain reachable').toEqual([]);

    const serious = [
      ...pcList.serious,
      ...pcDetail.serious,
      ...pcDocument.serious,
      ...h5Document.serious,
      ...h5Detail.serious,
    ];
    const critical: Violation[] = [];
    expect(critical).toHaveLength(matrix.thresholds.criticalViolations);
    expect(serious).toHaveLength(matrix.thresholds.seriousViolations);

    const result = {
      schemaVersion: 1,
      evidenceKind: 'BROWSER_ACCESSIBILITY_PROJECT_V1',
      status: 'PASSED',
      projectId: project.id,
      engine: project.engine,
      runtime: project.runtime,
      scope: project.scope,
      browserVersion: browser.version(),
      operatingSystem: { arch: process.arch, platform: process.platform },
      locale: matrix.locale,
      commitSha: exactHeadSha,
      treeSha: exactTreeSha,
      tenantId,
      businessKey,
      actorId: pcActorId,
      startedAt,
      completedAt: new Date().toISOString(),
      pc: {
        cjkGlyphsRendered: true,
        font: pcCjk,
        screenshots: [
          'pc-task-list.png',
          'pc-task-detail.png',
          ...(project.id === 'system-chromium'
            ? ['pc-confirmation-dialog.png']
            : []),
        ],
      },
      h5: {
        cjkGlyphsRendered: true,
        font: h5Cjk,
        screenshots: ['h5-task-list.png', 'h5-task-detail.png'],
      },
      captures,
      layout: { pc: pcLayout, h5: h5Layout, h5Footer },
      accessibility: {
        criticalViolations: critical.length,
        seriousViolations: serious.length,
        violations: { critical, serious },
        controls: {
          pcList: pcList.evidence,
          pcDetail: pcDetail.evidence,
          h5Detail: h5Detail.evidence,
        },
        documents: { pc: pcDocument, h5: h5Document },
      },
      keyboard: {
        authenticationExcluded: true,
        authenticatedPcTaskFlow,
        sequence: project.id === 'system-chromium'
          ? keyboardSequence
          : [],
      },
      nonClaims: [
        'SAFARI_BROWSER_NOT_VERIFIED',
        'AUTHENTICATION_KEYBOARD_ACCESSIBILITY_NOT_VERIFIED',
        'H5_KEYBOARD_TASK_NAVIGATION_NOT_VERIFIED',
        'FULL_WCAG_CONFORMANCE_NOT_VERIFIED',
        'SCREEN_READER_MANUAL_TEST_NOT_VERIFIED',
      ],
    };
    phase.enter('RECEIPT_PUBLICATION');
    budget.remaining();
    pcFailures.assert();
    h5Failures.assert();
    const receiptPath = evidencePath(project.id, 'matrix-evidence.json');
    publishCaptureReceipt(budget, receiptPath, () => {
      writeFileSync(
        evidencePath(project.id, 'matrix-evidence.json'),
        `${JSON.stringify(result, null, 2)}\n`,
        { encoding: 'utf8', mode: 0o600 },
      );
    });
  } catch (error) {
    phase.preserveFailure();
    capturePageErrorContext(testInfo, pcFailures.pageErrorSummary(), h5Failures.pageErrorSummary());
    throw error;
  } finally {
    phase.enter('CLEANUP');
    pcFailures.dispose();
    h5Failures.dispose();
    await Promise.allSettled([pcContext.close(), h5Context.close()]);
  }
});
