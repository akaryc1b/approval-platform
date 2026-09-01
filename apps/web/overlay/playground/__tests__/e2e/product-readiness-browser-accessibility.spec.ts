import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import type { Locator, Page } from '@playwright/test';
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

const repositoryRoot = requiredEnvironment('APPROVAL_DEMO_REPOSITORY_ROOT');
const evidenceDirectory = requiredEnvironment(
  'APPROVAL_BROWSER_ACCESSIBILITY_EVIDENCE_DIR',
);
const exactHeadSha = requiredEnvironment('APPROVAL_DEMO_EXACT_HEAD_SHA');
const exactTreeSha = requiredEnvironment('APPROVAL_DEMO_EXACT_TREE_SHA');
const h5Url = requiredEnvironment('APPROVAL_DEMO_H5_URL');
const configuredBusinessKey = requiredEnvironment(
  'APPROVAL_DEMO_QUICK_START_BUSINESS_KEY',
);
const h5ActorId = requiredEnvironment('APPROVAL_DEMO_QUICK_START_H5_ACTOR');
const pcActorId = requiredEnvironment('APPROVAL_DEMO_QUICK_START_PC_ACTOR');
const configuredTenant = requiredEnvironment(
  'APPROVAL_DEMO_QUICK_START_TENANT',
);
const matrix = JSON.parse(readFileSync(resolve(
  repositoryRoot,
  requiredEnvironment('APPROVAL_BROWSER_ACCESSIBILITY_MANIFEST'),
), 'utf8')) as {
  locale: string;
  projects: Array<{
    engine: 'chromium' | 'firefox' | 'webkit';
    id: string;
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

async function collectCjkEvidence(page: Page) {
  await page.evaluate(async () => {
    await document.fonts.ready;
  });
  const evidence = await page.evaluate((sample) => {
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
  }, cjkProbeText);
  expect(evidence.minimumInkPixels).toBeGreaterThanOrEqual(
    matrix.thresholds.minimumInkPixels,
  );
  expect(evidence.uniqueGlyphHashes).toBeGreaterThanOrEqual(
    matrix.thresholds.minimumUniqueCjkGlyphHashes,
  );
  return { ...evidence, cjkGlyphsRendered: true };
}

async function controlEvidence(control: Locator, label: string) {
  await expect(control).toBeVisible();
  return control.evaluate((element, selector) => {
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
        const value = channels(getComputedStyle(current).backgroundColor);
        if (value && value.a > 0.01) return value;
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
      selector,
      tabIndex: (element as HTMLElement).tabIndex,
    };
  }, label);
}

async function auditControls(
  controls: Array<{ label: string; locator: Locator }>,
) {
  const serious: Violation[] = [];
  const evidence = [];
  for (const item of controls) {
    const value = await controlEvidence(item.locator, item.label);
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

async function documentEvidence(page: Page, surface: string) {
  return page.evaluate((name) => {
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
    const duplicateIds = [..document.querySelectorAll('[id]')]
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
  }, surface);
}

async function tabTo(
  page: Page,
  target: Locator,
  label: string,
  maximumTabs = 120,
) {
  const sequence = [];
  for (let index = 0; index < maximumTabs; index += 1) {
    await page.keyboard.press('Tab');
    const active = await page.evaluate(() => {
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
    });
    sequence.push(active);
    if (await target.evaluate(element => element === document.activeElement)) {
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

async function h5ActionControl(actionBar: Locator, label: '同意' | '驳回') {
  for (const candidate of [
    actionBar.getByRole('button', { name: label, exact: true }).last(),
    actionBar.locator('.wd-button')
      .filter({ hasText: new RegExp(`^${label}$`, 'u') }).last(),
    actionBar.locator('wd-button')
      .filter({ hasText: new RegExp(`^${label}$`, 'u') }).last(),
    actionBar.locator('uni-button')
      .filter({ hasText: new RegExp(`^${label}$`, 'u') }).last(),
  ]) {
    if (await candidate.count() > 0) return candidate;
  }
  throw new Error(`H5 ${label} action does not expose a button`);
}

function evidencePath(projectId: string, name: string) {
  const directory = resolve(evidenceDirectory, projectId);
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  return resolve(directory, name);
}

test('PC and H5 expose the bounded browser/accessibility matrix', async ({
  browser,
}, testInfo) => {
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
  try {
    await ensurePcLogin(pc);
    await pc.goto(pcUrl, { waitUntil: 'domcontentloaded' });
    const pcTask = pc.locator('.task-item')
      .filter({ hasText: businessKey }).first();
    await expect(pcTask).toBeVisible({ timeout: 30_000 });
    const pcHandle = pcTask.getByRole('button', {
      name: '处理',
      exact: true,
    });
    const pcList = await auditControls([
      { label: 'pc-task-handle', locator: pcHandle },
    ]);
    const pcDocument = await documentEvidence(pc, 'pc-task-list');
    const pcCjk = await collectCjkEvidence(pc);
    await pc.screenshot({
      fullPage: true,
      path: evidencePath(project.id, 'pc-task-list.png'),
    });

    if (project.id === 'system-chromium') {
      keyboardSequence.push(...await tabTo(pc, pcHandle, 'PC task handle'));
      await pc.keyboard.press('Enter');
    } else {
      await pcHandle.click();
    }
    await expect(pc.getByText('审批详情', { exact: true }).first())
      .toBeVisible({ timeout: 20_000 });
    const agree = pc.getByRole('button', {
      name: '同意',
      exact: true,
    }).last();
    const pcDetail = await auditControls([
      { label: 'pc-agree', locator: agree },
    ]);
    let authenticatedPcTaskFlow = false;
    if (project.id === 'system-chromium') {
      keyboardSequence.push(...await tabTo(pc, agree, 'PC agree'));
      await pc.keyboard.press('Enter');
      const confirmation = pc.getByRole('button', {
        name: '确认同意',
        exact: true,
      });
      await expect(confirmation).toBeVisible({ timeout: 10_000 });
      keyboardSequence.push(...await tabTo(
        pc,
        confirmation,
        'PC confirmation',
      ));
      await pc.screenshot({
        fullPage: true,
        path: evidencePath(project.id, 'pc-confirmation-dialog.png'),
      });
      await pc.keyboard.press('Escape');
      await expect(confirmation).toBeHidden({ timeout: 10_000 });
      authenticatedPcTaskFlow = true;
    }
    await pc.screenshot({
      fullPage: true,
      path: evidencePath(project.id, 'pc-task-detail.png'),
    });

    await h5.goto(h5Url, { waitUntil: 'domcontentloaded' });
    const h5Task = h5.locator('.task-card')
      .filter({ hasText: businessKey }).first();
    await expect(h5Task).toBeVisible({ timeout: 30_000 });
    const h5Document = await documentEvidence(h5, 'h5-task-list');
    const h5Cjk = await collectCjkEvidence(h5);
    await h5.screenshot({
      fullPage: true,
      path: evidencePath(project.id, 'h5-task-list.png'),
    });
    await h5Task.click();
    const actionBar = h5.locator('.action-bar');
    await expect(actionBar).toBeVisible({ timeout: 20_000 });
    const h5Actions = await auditControls([
      { label: 'h5-agree', locator: await h5ActionControl(actionBar, '同意') },
      { label: 'h5-reject', locator: await h5ActionControl(actionBar, '驳回') },
    ]);
    await h5.screenshot({
      fullPage: true,
      path: evidencePath(project.id, 'h5-task-detail.png'),
    });

    const serious = [
      ...pcList.serious,
      ...pcDetail.serious,
      ...pcDocument.serious,
      ...h5Document.serious,
      ...h5Actions.serious,
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
      accessibility: {
        criticalViolations: critical.length,
        seriousViolations: serious.length,
        violations: { critical, serious },
        controls: {
          pcList: pcList.evidence,
          pcDetail: pcDetail.evidence,
          h5Detail: h5Actions.evidence,
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
    writeFileSync(
      evidencePath(project.id, 'matrix-evidence.json'),
      `${JSON.stringify(result, null, 2)}\n`,
      { encoding: 'utf8', mode: 0o600 },
    );
  } finally {
    await Promise.allSettled([pcContext.close(), h5Context.close()]);
  }
});
