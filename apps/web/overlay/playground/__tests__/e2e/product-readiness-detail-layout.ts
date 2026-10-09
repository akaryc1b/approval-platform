import type { Page } from '@playwright/test';
import type { CaptureBudget } from './product-readiness-capture-budget';

interface HorizontalBoundary {
  category: string;
  index: number;
  left: number;
  right: number;
  clientWidth: number;
  scrollWidth: number;
  textFragments: number;
  textOverflow: number;
}

interface DetailLayout {
  viewportWidth: number;
  documentWidth: number;
  contentLeft: number;
  contentRight: number;
  boundaries: HorizontalBoundary[];
}

// Check text fragments as well as boxes: a constrained card can still paint a
// long status outside its column, and a drawer can clip it without widening body.
export function detailLayoutViolations(layout: DetailLayout) {
  const violations: string[] = [];
  if (layout.documentWidth > layout.viewportWidth + 1) violations.push('document-width');
  if (!layout.boundaries.length) violations.push('missing-boundaries');
  if (layout.boundaries.reduce((sum, value) => sum + value.textFragments, 0) < 20) {
    violations.push('missing-text-fragments');
  }
  for (const boundary of layout.boundaries) {
    const label = `${boundary.category}[${boundary.index}]`;
    if (boundary.left < layout.contentLeft - 1 || boundary.right > layout.contentRight + 1) {
      violations.push(`${label}:box`);
    }
    if (boundary.scrollWidth > boundary.clientWidth + 1) violations.push(`${label}:scroll`);
    if (boundary.textOverflow > 1) violations.push(`${label}:text`);
  }
  return violations;
}

export async function detailLayoutEvidence(page: Page, client: 'h5' | 'pc', budget: CaptureBudget) {
  return budget.run(() => page.evaluate((kind): DetailLayout => {
    const detail = document.querySelector('[data-testid="approval-task-detail"]');
    const host = kind === 'pc' ? detail?.closest('.el-drawer__body') : detail;
    if (!(host instanceof HTMLElement)) throw new Error('detail layout host is missing');
    const hostRect = host.getBoundingClientRect();
    const hostStyle = getComputedStyle(host);
    const contentLeft = hostRect.left + parseFloat(hostStyle.paddingLeft);
    const contentRight = hostRect.left + host.clientWidth - parseFloat(hostStyle.paddingRight);
    const selectors = kind === 'pc'
      ? ['.detail-content', '.application-snapshot', '.assistance-panel', '.snapshot', '.confirmation-boundary', '.el-descriptions__table', '.el-descriptions__cell', '.limitations li', '.assistance-header', '.boundary-heading']
      : ['.assistance-card', '.snapshot-grid > uni-view', '.confirmation-boundary', '.boundary-grid > uni-view', '.limitations > uni-view', '.assistance-header', '.boundary-heading'];
    const boundaries = selectors.flatMap(category => Array.from(host.querySelectorAll<HTMLElement>(category)).map((element, index) => {
      const rect = element.getBoundingClientRect();
      const style = getComputedStyle(element);
      const left = rect.left + parseFloat(style.borderLeftWidth) + parseFloat(style.paddingLeft);
      const right = rect.right - parseFloat(style.borderRightWidth) - parseFloat(style.paddingRight);
      const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
      let textFragments = 0;
      let textOverflow = 0;
      while (walker.nextNode()) {
        if (!walker.currentNode.textContent?.trim()) continue;
        const range = document.createRange();
        range.selectNodeContents(walker.currentNode);
        for (const fragment of Array.from(range.getClientRects())) {
          if (!fragment.width || !fragment.height) continue;
          textFragments += 1;
          textOverflow = Math.max(textOverflow, left - fragment.left, fragment.right - right);
        }
      }
      return { category, index, left: rect.left, right: rect.right, clientWidth: element.clientWidth, scrollWidth: element.scrollWidth, textFragments, textOverflow };
    }));
    return { viewportWidth: innerWidth, documentWidth: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth), contentLeft, contentRight, boundaries };
  }, client));
}

export interface FooterLayout {
  viewportWidth: number;
  viewportHeight: number;
  footer: { left: number; right: number; top: number; bottom: number; height: number };
  buttons: Array<{ left: number; right: number; top: number; bottom: number; width: number; height: number }>;
  bottomPadding: number;
  lastContentBottom: number;
}

export function footerLayoutViolations(layout: FooterLayout) {
  const violations: string[] = [];
  const { footer, buttons } = layout;
  if (footer.left < -1 || footer.right > layout.viewportWidth + 1 || footer.bottom > layout.viewportHeight + 1 || footer.top < 0) violations.push('footer-viewport');
  if (buttons.length < 3) violations.push('missing-actions');
  if (layout.bottomPadding < footer.height) violations.push('footer-reserved-space');
  if (layout.lastContentBottom > footer.top + 1) violations.push('last-content-obscured');
  buttons.forEach((button, index) => {
    if (button.left < footer.left - 1 || button.right > footer.right + 1 || button.top < footer.top - 1 || button.bottom > footer.bottom + 1 || button.width < 44 || button.height < 44) violations.push(`action[${index}]:bounds`);
    for (const other of buttons.slice(index + 1)) {
      if (Math.min(button.right, other.right) - Math.max(button.left, other.left) > 1 && Math.min(button.bottom, other.bottom) - Math.max(button.top, other.top) > 1) violations.push(`action[${index}]:overlap`);
    }
  });
  return violations;
}

// This extra regression interaction runs after the original captures. Scroll to
// the real document end and check the opinion card clears the still-fixed bar.
export async function footerLayoutEvidence(page: Page, budget: CaptureBudget) {
  return budget.run(() => page.evaluate(async (): Promise<FooterLayout> => {
    const detail = document.querySelector<HTMLElement>('[data-testid="approval-task-detail"]');
    const footer = detail?.querySelector<HTMLElement>('.action-bar');
    const cards = detail?.querySelectorAll<HTMLElement>('.action-card');
    const content = cards?.[cards.length - 1];
    if (!detail || !footer || !content) throw new Error('footer layout surface is missing');
    const previous = { left: scrollX, top: scrollY };
    try {
      window.scrollTo(0, Math.max(document.documentElement.scrollHeight, document.body.scrollHeight));
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      const box = (element: Element) => {
        const rect = element.getBoundingClientRect();
        return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, width: rect.width, height: rect.height };
      };
      return { viewportWidth: innerWidth, viewportHeight: innerHeight, footer: box(footer), buttons: Array.from(footer.querySelectorAll('.wd-button')).map(box), bottomPadding: parseFloat(getComputedStyle(detail).paddingBottom), lastContentBottom: content.getBoundingClientRect().bottom };
    } finally {
      window.scrollTo(previous);
    }
  }));
}
