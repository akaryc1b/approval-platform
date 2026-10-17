import type { Page } from '@playwright/test';
import type { CaptureBudget } from './product-readiness-capture-budget';

const cjkFamilies = /^(Noto Sans CJK|Noto Sans SC|Source Han Sans|PingFang SC|Hiragino Sans GB|Microsoft YaHei|WenQuanYi Micro Hei)/u;
export type CjkSurface = 'list' | 'acknowledged';
interface PlatformFont { familyName: string; glyphCount: number }
type SettlementVisibility = 'visible' | 'hidden' | 'prerender' | 'unknown';
interface SettlementFrame {
  elapsedMs: number; intervalMs: number; visibility: SettlementVisibility;
  pendingAnimations: number; runningAnimations: number; pausedAnimations: number; otherActiveAnimations: number;
  visible: boolean; inViewport: boolean; stableFrames: number;
}
interface SettlementReport {
  version: 1; elapsedMs: number; frameCount: number; maxFrameIntervalMs: number;
  initialVisibility: SettlementVisibility; frames: SettlementFrame[];
}

/** Wait only for the current toast's entrance, never the destination or drawer. */
export function settleCjkAcknowledgment(input: { timeout: number }) {
  if (!Number.isFinite(input.timeout) || input.timeout <= 0) throw new Error('CJK_SURFACE_SETTLEMENT_EXPIRED');
  const label = '审批已同意';
  let text: Node | undefined;
  for (const root of document.querySelectorAll('.el-message--success, uni-toast')) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let node; (node = walker.nextNode());) {
      if (node.textContent?.replace(/\s/gu, '') === label) {
        if (text) throw new Error('CJK_SURFACE_LABEL_REQUIRED');
        text = node;
      }
    }
  }
  const target = text?.parentElement;
  if (!text || !target) throw new Error('CJK_SURFACE_LABEL_REQUIRED');
  const originalText = text;
  const range = document.createRange();
  range.selectNodeContents(originalText);
  const started = performance.now();
  const deadline = started + input.timeout;
  const visibility = (): SettlementVisibility => {
    const value: string = document.visibilityState;
    return value === 'visible' || value === 'hidden' || value === 'prerender' ? value : 'unknown';
  };
  const initialVisibility = visibility();
  return new Promise<SettlementReport>((resolve, reject) => {
    let frame = 0, stableFrames = 0, previousGeometry = '', finished = false;
    let frameCount = 0, previousFrameTime = started, maxFrameIntervalMs = 0;
    const frames: SettlementFrame[] = [];
    // Diagnostic-only primitives, retained by the existing evaluate trace. No
    // report is returned if settlement rejects; callers ignore successful reports.
    const recordFrame = (now: number, animations: Animation[], visible: boolean, inViewport: boolean) => {
      const intervalMs = now - previousFrameTime;
      previousFrameTime = now;
      frameCount += 1;
      maxFrameIntervalMs = Math.max(maxFrameIntervalMs, intervalMs);
      frames.push({
        elapsedMs: now - started, intervalMs, visibility: visibility(),
        pendingAnimations: animations.filter(animation => animation.pending).length,
        runningAnimations: animations.filter(animation => animation.playState === 'running').length,
        pausedAnimations: animations.filter(animation => animation.playState === 'paused').length,
        otherActiveAnimations: animations.filter(animation => !['finished', 'running', 'paused'].includes(animation.playState)).length,
        visible, inViewport, stableFrames,
      });
      if (frames.length > 16) frames.shift();
    };
    const finish = (error?: Error) => {
      if (finished) return;
      finished = true;
      cancelAnimationFrame(frame);
      clearTimeout(timer);
      removeEventListener('pagehide', navigated);
      if (error) reject(error); else resolve({
        version: 1, elapsedMs: previousFrameTime - started, frameCount, maxFrameIntervalMs, initialVisibility, frames,
      });
    };
    const navigated = () => finish(new Error('CJK_SURFACE_CHANGED'));
    const timer = setTimeout(() => finish(new Error('CJK_SURFACE_SETTLEMENT_EXPIRED')), input.timeout);
    addEventListener('pagehide', navigated, { once: true });
    const tick = () => {
      try {
        const now = performance.now();
        if (now >= deadline) throw new Error('CJK_SURFACE_SETTLEMENT_EXPIRED');
        if (!originalText.isConnected || originalText.parentElement !== target
          || originalText.textContent?.replace(/\s/gu, '') !== label) throw new Error('CJK_SURFACE_CHANGED');
        const ancestors: Element[] = [];
        for (let node: Element | null = target; node; node = node.parentElement) ancestors.push(node);
        // An endless or unknown active animation is not a settled acknowledgment.
        const animations = ancestors.flatMap(node => node.getAnimations());
        const pending = animations.some(animation => animation.pending || animation.playState !== 'finished');
        const visible = ancestors.every(node => {
          const style = getComputedStyle(node);
          return style.display !== 'none' && style.visibility === 'visible' && Number(style.opacity) >= 0.999;
        });
        const bounds = range.getBoundingClientRect();
        const inside = bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.top >= 0
          && bounds.right <= innerWidth && bounds.bottom <= innerHeight;
        const geometry = JSON.stringify([bounds.x, bounds.y, bounds.width, bounds.height]);
        if (!pending && visible && inside) {
          stableFrames = geometry === previousGeometry ? stableFrames + 1 : 0;
          previousGeometry = geometry;
          recordFrame(now, animations, visible, inside);
          if (stableFrames >= 2) { finish(); return; }
        } else {
          stableFrames = 0;
          previousGeometry = '';
          recordFrame(now, animations, visible, inside);
        }
        frame = requestAnimationFrame(tick);
      } catch (error) {
        finish(error instanceof Error ? error : new Error('CJK_SURFACE_CHANGED'));
      }
    };
    frame = requestAnimationFrame(tick);
  });
}

/** Serialized into the existing page. Inspect real control text, not a synthetic DOM probe. */
export function inspectCjkSurface(surface: CjkSurface) {
  const label = surface === 'list' ? '待我处理' : '审批已同意';
  const selector = surface === 'list' ? '[data-testid="approval-task-list"]' : '.el-message--success, uni-toast';
  if (document.fonts.status !== 'loaded') throw new Error('CJK_FONT_UNRESOLVED');
  const visible = (element: Element) => {
    const bounds = element.getBoundingClientRect();
    if (bounds.width <= 0 || bounds.height <= 0) return false;
    for (let node: Element | null = element; node; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (style.display === 'none' || style.visibility !== 'visible' || Number(style.opacity) === 0) return false;
    }
    return true;
  };
  let target: Element | undefined;
  let labelBounds: { x: number; y: number; width: number; height: number } | undefined;
  for (const root of document.querySelectorAll(selector)) {
    if (!visible(root)) continue;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let node; (node = walker.nextNode());) {
      const element = node.parentElement;
      if (node.textContent?.replace(/[\s\d]/gu, '') !== label || !element || !visible(element)) continue;
      const range = document.createRange();
      range.selectNodeContents(node);
      const bounds = range.getBoundingClientRect();
      if (bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.bottom > 0
        && bounds.left < innerWidth && bounds.top < innerHeight) {
        target = element;
        labelBounds = { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height };
        break;
      }
    }
    if (target) break;
  }
  if (!target) throw new Error('CJK_SURFACE_LABEL_REQUIRED');
  const style = getComputedStyle(target);
  const transparent = (color: string) => color === 'transparent' || /rgba\([^)]*,\s*0(?:\.0+)?\s*\)$/u.test(color);
  if (!(Number.parseFloat(style.fontSize) > 0) || transparent(style.color)
    || transparent(style.getPropertyValue('-webkit-text-fill-color'))) throw new Error('CJK_SURFACE_TEXT_INVISIBLE');
  // A structural selector identifies the exact rendered text parent for CDP.
  // It neither mutates the product DOM nor exposes business text or task IDs.
  const path: string[] = [];
  for (let node: Element | null = target; node; node = node.parentElement) {
    const index = node.parentElement ? [...node.parentElement.children].indexOf(node) + 1 : 1;
    path.unshift(`${node.tagName.toLowerCase()}:nth-child(${index})`);
  }
  const canvas = document.createElement('canvas');
  canvas.width = 96; canvas.height = 96;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('CJK_CANVAS_REQUIRED');
  const glyphs = [...new Set(label + '审批任务采购付款工作台')].map(character => {
    context.clearRect(0, 0, 96, 96);
    context.fillStyle = '#000';
    context.font = `${style.fontStyle} ${style.fontWeight} 48px ${style.fontFamily}`;
    context.textBaseline = 'top';
    context.fillText(character, 8, 8);
    const pixels = context.getImageData(0, 0, 96, 96).data;
    let hash = 2_166_136_261, inkPixels = 0;
    for (let index = 3; index < pixels.length; index += 4) {
      const alpha = pixels[index] ?? 0;
      if (alpha > 0) inkPixels += 1;
      hash = Math.imul(hash ^ alpha, 16_777_619);
    }
    return { character, hash: (hash >>> 0).toString(16).padStart(8, '0'), inkPixels };
  });
  return {
    surface, label, labelBounds, selector: path.join(' > '),
    cssVariable: getComputedStyle(document.documentElement).getPropertyValue('--font-family').trim(),
    computedFontFamily: style.fontFamily,
    computedFontStyle: style.fontStyle, computedFontWeight: style.fontWeight,
    glyphs, minimumInkPixels: Math.min(...glyphs.map(glyph => glyph.inkPixels)),
    uniqueGlyphHashes: new Set(glyphs.map(glyph => glyph.hash)).size,
  };
}

export function assertCjkFontEvidence(evidence: ReturnType<typeof inspectCjkSurface>, fonts: PlatformFont[], requireExplicitStack = false) {
  const actual = [...new Set(evidence.label)].map(character => evidence.glyphs.find(glyph => glyph.character === character));
  if (actual.length < 4 || actual.some(glyph => !glyph || glyph.inkPixels <= 20)
    || evidence.glyphs.some(glyph => glyph.inkPixels <= 20)) throw new Error('CJK_NONEMPTY_GLYPHS_REQUIRED');
  if (new Set(actual.map(glyph => glyph?.hash)).size !== actual.length
    || new Set(evidence.glyphs.map(glyph => glyph.hash)).size < 6) throw new Error('CJK_DISTINCT_GLYPHS_REQUIRED');
  if (!fonts.some(font => cjkFamilies.test(font.familyName) && font.glyphCount >= actual.length)) {
    throw new Error('CJK_PLATFORM_FONT_REQUIRED');
  }
  if (requireExplicitStack && !`${evidence.cssVariable},${evidence.computedFontFamily}`
    .split(',').some(family => cjkFamilies.test(family.trim().replace(/^['"]|['"]$/gu, '')))) {
    throw new Error('CJK_EXPLICIT_FONT_STACK_REQUIRED');
  }
}

/** Selected system-Chromium routes only; other browser matrix contracts stay independent. */
export async function collectCjkFontEvidence(page: Page, surface: CjkSurface, budget: CaptureBudget, requireExplicitStack = false) {
  await budget.run(() => page.evaluate(async () => { await document.fonts.ready; }));
  if (surface === 'acknowledged') {
    await budget.run(timeout => page.evaluate(settleCjkAcknowledgment, { timeout }));
  }
  const observed = await budget.run(() => page.evaluate(inspectCjkSurface, surface));
  const session = await budget.run(() => page.context().newCDPSession(page));
  let fonts: PlatformFont[];
  let failed = false;
  try {
    await budget.run(() => session.send('DOM.enable'));
    await budget.run(() => session.send('CSS.enable'));
    const { root } = await budget.run(() => session.send('DOM.getDocument'));
    const { nodeId } = await budget.run(() => session.send('DOM.querySelector', { nodeId: root.nodeId, selector: observed.selector }));
    if (!(nodeId > 0)) throw new Error('CJK_PLATFORM_NODE_REQUIRED');
    fonts = (await budget.run(() => session.send('CSS.getPlatformFontsForNode', { nodeId }))).fonts;
    assertCjkFontEvidence(observed, fonts, requireExplicitStack);
  } catch (error) {
    failed = true;
    throw error;
  } finally {
    try {
      await budget.run(() => session.detach());
    } catch (error) {
      // Context cleanup still owns the session if its detach does not finish.
      // Never let cleanup hide the original font/protocol failure.
      if (!failed) throw error;
    }
  }
  const assertCurrent = async () => {
    const current = await budget.run(() => page.evaluate(inspectCjkSurface, surface));
    if (JSON.stringify(current) !== JSON.stringify(observed)) throw new Error('CJK_SURFACE_CHANGED');
  };
  await assertCurrent();
  return {
    evidence: { ...observed, platformFonts: fonts.map(({ familyName, glyphCount }) => ({ familyName, glyphCount })), cjkGlyphsRendered: true },
    assertCurrent,
  };
}
