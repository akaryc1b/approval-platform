import { lstatSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import type { JSHandle, Page } from '@playwright/test';
import type { CaptureBudget } from './product-readiness-capture-budget';

export type CapturePhase = 'READY_BEFORE_ACTION' | 'IMMEDIATE_ACKNOWLEDGED_RESULT' | 'SETTLED_DESTINATION';
export interface SurfaceExpectation {
  client: 'pc' | 'h5';
  kind: 'list' | 'detail' | 'confirmation';
  url: string;
  businessKey?: string;
  taskId?: string;
  instanceId?: string;
  minimumRefresh?: number;
  pendingTotal?: number;
  processedTotal?: number;
  absentTaskId?: string;
}

/** Observe failures without filtering or changing the native console/network record. */
export function observeCaptureFailures(page: Page) {
  let moduleFailures = 0;
  let unresolvedComponents = 0;
  let pageErrors = 0;
  const response = (value: import('@playwright/test').Response) => {
    if (value.status() >= 400 && ['script', 'stylesheet'].includes(value.request().resourceType())) moduleFailures += 1;
  };
  const failed = (value: import('@playwright/test').Request) => {
    if (['script', 'stylesheet'].includes(value.resourceType())) moduleFailures += 1;
  };
  const consoleMessage = (value: import('@playwright/test').ConsoleMessage) => {
    if (/Failed to resolve component:\s*wd-/u.test(value.text())) unresolvedComponents += 1;
  };
  const pageError = () => { pageErrors += 1; };
  page.on('pageerror', pageError);
  page.on('response', response);
  page.on('requestfailed', failed);
  page.on('console', consoleMessage);
  return {
    assert() {
      if (moduleFailures || unresolvedComponents || pageErrors) {
        throw new Error(`Capture has failed script/style loads (${moduleFailures}) or unresolved Wot components (${unresolvedComponents}) or page errors (${pageErrors})`);
      }
    },
    dispose() {
      page.off('pageerror', pageError);
      page.off('response', response);
      page.off('requestfailed', failed);
      page.off('console', consoleMessage);
    },
  };
}

// This function is deliberately self-contained: Playwright serializes it into the
// existing page, and deferred/fake-frame tests execute the same function.
export function observeSurface(input: SurfaceExpectation & { timeout: number }) {
  if (!Number.isFinite(input.timeout) || input.timeout <= 0) throw new Error('Invalid surface deadline');
  return new Promise<{
    surface: string;
    url: string;
    refresh: number;
    taskId: string | null;
    instanceId: string | null;
    businessKey: string | null;
    pendingTotal: number | null;
    processedTotal: number | null;
    absentTaskId: string | null;
    activeTab: string | null;
    documentWidth: number;
    viewportWidth: number;
    bounds: { x: number; y: number; width: number; height: number };
    witness: {
      root: Element;
      surface: Element;
      task: Element | undefined;
      url: string;
      attributes: string;
      taskAttributes: string;
      assistance: Element | null;
      assistanceAttributes: string;
      geometry: string;
      images: Array<{ element: HTMLImageElement; source: string }>;
      absentTaskId?: string;
    };
  }>((resolve, reject) => {
    const deadline = performance.now() + input.timeout;
    let frame = 0;
    let finished = false;
    let matchedRoute = false;
    let observedRoot: Element | undefined;
    let observedTask: string | undefined;
    let observedGeneration: string | undefined;
    let observedDetailGeneration: number | undefined;
    let observedAssistance: Element | undefined;
    let observedAssistanceGeneration: string | undefined;
    let previousGeometry = '';
    let stableFrames = 0;
    let fontsReady = false;
    let lastState = 'route';
    const images = new Map<HTMLImageElement, { source: string; decoded: boolean }>();
    const timeout = setTimeout(() => finish(new Error(`Surface readiness deadline expired (${lastState})`)), input.timeout);
    const finish = (error?: Error, value?: Parameters<typeof resolve>[0]) => {
      if (finished) return;
      finished = true;
      clearTimeout(timeout);
      cancelAnimationFrame(frame);
      if (error) reject(error);
      else if (value) resolve(value);
    };
    document.fonts.ready.then(() => { fontsReady = true; }, () => finish(new Error('Required fonts failed')));
    const number = (element: Element, name: string) => {
      const raw = element.getAttribute(name);
      return raw !== null && raw !== '' && Number.isFinite(Number(raw)) ? Number(raw) : null;
    };
    const visible = (element: Element) => {
      const box = element.getBoundingClientRect();
      if (box.width <= 0 || box.height <= 0) return false;
      for (let node: Element | null = element; node; node = node.parentElement) {
        const style = getComputedStyle(node);
        if (style.display === 'none' || style.visibility !== 'visible') return false;
      }
      return true;
    };
    const queryVisible = (selector: string) => [...document.querySelectorAll(selector)].find(visible);
    const required = (condition: boolean, state: string) => {
      if (!condition) lastState = state;
      return condition;
    };
    const sample = () => {
      if (finished) return;
      try {
        const expected = new URL(input.url);
        const actual = new URL(location.href);
        const routeMatches = actual.origin === expected.origin
          && actual.pathname === expected.pathname
          && actual.search === expected.search && actual.hash === expected.hash;
        if (matchedRoute && !routeMatches) throw new Error('Capture cancelled by navigation');
        if (!required(routeMatches, 'route')) return false;
        matchedRoute = true;
        const selector = input.kind === 'confirmation'
          ? '.el-message-box'
          : `[data-testid="approval-task-${input.kind}"]`;
        const root = queryVisible(selector);
        if (observedRoot && (!root || root !== observedRoot)) throw new Error('Capture cancelled by surface replacement or dismissal');
        if (!required(!!root, 'surface')) return false;
        if (!root) return false;
        observedRoot = root;
        const generation = number(root, 'data-refresh-generation');
        if (input.kind === 'list' && generation !== null && generation >= (input.minimumRefresh ?? 1)) {
          const current = `${generation}|${root.getAttribute('data-list-generation')}|${root.getAttribute('data-counts-generation')}`;
          if (observedGeneration !== undefined && current !== observedGeneration) throw new Error('Capture cancelled by refresh change');
          observedGeneration = current;
        }
        const task = input.kind === 'list'
          ? [...root.querySelectorAll('[data-task-id]')].find(element =>
            visible(element) && (!input.taskId || element.getAttribute('data-task-id') === input.taskId)
            && element.getAttribute('data-business-key') === input.businessKey)
          : input.kind === 'detail' ? root : queryVisible('[data-testid="approval-task-detail"]');
        if (input.kind === 'detail' || input.kind === 'confirmation') {
          const detail = input.kind === 'detail' ? root : task;
          const detailGeneration = detail && number(detail, 'data-detail-generation');
          if (detailGeneration && detailGeneration > 0) {
            if (observedDetailGeneration !== undefined && detailGeneration !== observedDetailGeneration) throw new Error('Capture cancelled by detail reload');
            observedDetailGeneration = detailGeneration;
          }
          const assistance = detail?.querySelector('[data-testid="approval-assistance"]');
          if (observedAssistance && assistance !== observedAssistance) throw new Error('Capture cancelled by assistance replacement');
          if (assistance) {
            observedAssistance = assistance;
            const generation = number(assistance, 'data-assistance-generation');
            if (generation && generation > 0) {
              const current = `${generation}|${assistance.getAttribute('data-selected-use-case')}`;
              if (observedAssistanceGeneration !== undefined && current !== observedAssistanceGeneration) throw new Error('Capture cancelled by assistance reload');
              observedAssistanceGeneration = current;
            }
          }
        }
        const currentTask = (input.kind === 'detail' ? root : task)?.getAttribute('data-task-id');
        if (observedTask !== undefined && currentTask !== observedTask) throw new Error('Capture cancelled by task change');
        if (currentTask) observedTask = currentTask;
        if (input.kind !== 'confirmation' || input.taskId) {
          if (input.absentTaskId) {
            if (!required(![...root.querySelectorAll('[data-task-id]')].some(element =>
              visible(element) && element.getAttribute('data-task-id') === input.absentTaskId), 'completed task still present')) return false;
          } else if (!required(!!task && (!input.taskId || task.getAttribute('data-task-id') === input.taskId)
            && (!input.instanceId || task.getAttribute('data-instance-id') === input.instanceId)
            && task.getAttribute('data-business-key') === input.businessKey
            && (task.textContent || '').includes(input.businessKey || ''), 'task identity')) return false;
        }
        if (input.kind === 'list') {
          const current = number(root, 'data-refresh-generation');
          if (!required(root.getAttribute('data-active-tab') === 'pending'
            && root.getAttribute('data-list-loading') === 'false'
            && root.getAttribute('data-counts-loading') === 'false'
            && root.getAttribute('data-list-error') === ''
            && root.getAttribute('data-counts-error') === ''
            && root.getAttribute('data-list-context-valid') === 'true'
            && Number(number(root, 'data-list-generation')) > 0
            && number(root, 'data-list-generation') === number(root, 'data-list-loaded-generation')
            && Number(number(root, 'data-counts-generation')) > 0
            && number(root, 'data-counts-generation') === number(root, 'data-counts-loaded-generation')
            && current !== null && current >= (input.minimumRefresh ?? 1)
            && current === number(root, 'data-refresh-completed-generation'), 'list refresh')) return false;
          if (!required((input.pendingTotal === undefined || number(root, 'data-pending-total') === input.pendingTotal)
            && (input.processedTotal === undefined || number(root, 'data-processed-total') === input.processedTotal), 'list totals')) return false;
          if (!required(!!root.querySelector(input.client === 'pc' ? '.el-input__inner' : '.wd-search__field')
            && !!root.querySelector(input.client === 'pc' ? '.el-tabs__item.is-active' : '.mode-row .wd-button')
            && (!input.absentTaskId || (root.textContent || '').includes(input.client === 'pc' ? '当前没有相关审批记录' : '当前没有相关审批事项')), 'list controls or empty state')) return false;
          if (input.client === 'pc' && !required(!document.querySelector('#__app-loading__'), 'bootstrap overlay')) return false;
          if (input.absentTaskId && !required(!queryVisible('.el-drawer, .el-overlay, .el-message-box, uni-modal'), 'blocking overlay')) return false;
        }
        if (input.kind === 'detail') {
          if (!required(root.getAttribute('data-detail-loading') === 'false'
            && root.getAttribute('data-detail-error') === ''
            && root.getAttribute('data-form-loaded') === 'true'
            && root.getAttribute('data-timeline-loaded') === 'true'
            && Number(number(root, 'data-detail-generation')) > 0
            && number(root, 'data-detail-generation') === number(root, 'data-detail-loaded-generation')
            && !!root.querySelector(input.client === 'pc' ? '.el-timeline-item' : '.timeline-item')
            && !!root.querySelector(input.client === 'pc' ? '.renderer' : '.form-card'), 'detail data')) return false;
          const assistance = root.querySelector('[data-testid="approval-assistance"]');
          if (!required(!!assistance && assistance.getAttribute('data-loading') === 'false'
            && assistance.getAttribute('data-error') === ''
            && assistance.getAttribute('data-snapshot-task-id') === task?.getAttribute('data-task-id')
            && assistance.getAttribute('data-snapshot-use-case') === assistance.getAttribute('data-selected-use-case')
            && Number(number(assistance, 'data-assistance-generation')) > 0
            && number(assistance, 'data-assistance-generation') === number(assistance, 'data-assistance-loaded-generation')
            && ['AVAILABLE', 'PROVIDER_NOT_CONFIGURED'].includes(assistance.getAttribute('data-availability') || '')
            && !!assistance.querySelector(input.client === 'pc' ? '.snapshot' : '.snapshot-grid'), 'task assistance snapshot')) return false;
        }
        if (input.client === 'h5') {
          if (!required(!root.querySelector('wd-button, wd-search, wd-tag, wd-input, wd-textarea, wd-icon'), 'unresolved Wot component')) return false;
          const buttons = [...root.querySelectorAll('uni-button.wd-button')].filter(visible);
          const contents = buttons.map(button => button.querySelector('.wd-button__content'));
          if (!required(contents.length >= (input.kind === 'list' ? 4 : 1)
            && contents.every(content => !!content && visible(content) && getComputedStyle(content).display === 'flex'
              && getComputedStyle(content).alignItems === 'center'), 'Wot button styles')) return false;
          if (input.kind === 'list') {
            const search = root.querySelector('.wd-search');
            const field = search?.querySelector('.wd-search__field');
            if (!required(!!search && visible(search) && getComputedStyle(search).display === 'flex'
              && !!field && visible(field) && getComputedStyle(field).position === 'relative'
              && [...field.querySelectorAll('.wd-search__cover .wd-icon, input')].some(visible), 'Wot search styles')) return false;
          }
          if (task) {
            const tag = task.querySelector('.wd-tag');
            const text = tag?.querySelector('.wd-tag__text');
            const style = tag && getComputedStyle(tag);
            const outlined = tag?.classList.contains('is-plain') || tag?.classList.contains('is-round');
            // Both shipped headers are flex containers. CSS blockifies the
            // Wot root's declared inline-block display when it is a flex item.
            const display = style?.display === 'inline-block'
              || (style?.display === 'block' && !!tag?.parentElement
                && !['absolute', 'fixed'].includes(style.position)
                && ['flex', 'inline-flex'].includes(getComputedStyle(tag.parentElement).display));
            if (!required(!!tag && visible(tag) && !!text && visible(text) && !!style && display
              && getComputedStyle(text).display === 'inline-block'
              && (outlined ? style.borderTopStyle === 'solid' && Number.parseFloat(style.borderTopWidth) > 0
                : style.backgroundColor !== 'transparent' && !/rgba\([^)]*,\s*0\)$/u.test(style.backgroundColor)), 'Wot tag styles')) return false;
          }
        }
        if (!required(fontsReady && document.fonts.status === 'loaded', 'fonts')) return false;
        for (const image of [...document.images].filter(visible)) {
          const source = image.currentSrc || image.src;
          if (!required(image.complete, 'image load')) return false;
          if (!image.naturalWidth || !image.naturalHeight) throw new Error('Required visible image failed');
          if (images.get(image)?.source !== source) {
            const state = { source, decoded: false };
            images.set(image, state);
            image.decode().then(() => { state.decoded = true; }, () => finish(new Error('Required visible image decode failed')));
          }
          if (!required(images.get(image)?.decoded === true, 'image decode')) return false;
        }
        const surface = input.kind === 'detail' && input.client === 'pc' ? root.closest('.el-drawer') || root : root;
        const animated = new Set<Element>([surface]);
        for (let node = surface.parentElement; node; node = node.parentElement) animated.add(node);
        const animations = [...surface.getAnimations({ subtree: true }), ...[...animated].flatMap(node => node.getAnimations())];
        if (!required(!animations.some(animation => {
          const end = animation.effect?.getComputedTiming().endTime;
          return typeof end === 'number' && Number.isFinite(end)
            && (animation.pending || animation.playState !== 'finished');
        }), 'finite transition')) return false;
        if (!required([...animated].every(node => Number(getComputedStyle(node).opacity) >= 0.999), 'surface opacity')) return false;
        const box = surface.getBoundingClientRect();
        if (!required(box.width > 0 && box.height > 0 && box.right > 0 && box.bottom > 0
          && box.x < innerWidth && box.y < innerHeight, 'viewport intersection')) return false;
        const geometry = JSON.stringify([box.x, box.y, box.width, box.height]);
        stableFrames = geometry === previousGeometry ? stableFrames + 1 : 0;
        previousGeometry = geometry;
        if (!required(stableFrames >= 2, 'natural geometry')) return false;
        if (performance.now() >= deadline) throw new Error('Surface readiness deadline expired');
        finish(undefined, {
          surface: `${input.client}-${input.kind}`,
          url: location.href,
          refresh: number(root, 'data-refresh-generation') || 0,
          taskId: task?.getAttribute('data-task-id') || null,
          instanceId: task?.getAttribute('data-instance-id') || null,
          businessKey: task?.getAttribute('data-business-key') || null,
          pendingTotal: number(root, 'data-pending-total'),
          processedTotal: number(root, 'data-processed-total'),
          absentTaskId: input.absentTaskId || null,
          activeTab: root.getAttribute('data-active-tab'),
          documentWidth: document.documentElement.scrollWidth,
          viewportWidth: innerWidth,
          bounds: { x: box.x, y: box.y, width: box.width, height: box.height },
          witness: {
            root, surface, task, url: location.href,
            attributes: JSON.stringify([...root.attributes].filter(item => item.name.startsWith('data-')).map(item => [item.name, item.value])),
            taskAttributes: task ? JSON.stringify([...task.attributes].filter(item => item.name.startsWith('data-')).map(item => [item.name, item.value])) : '',
            assistance: (input.kind === 'confirmation' ? task : root)?.querySelector('[data-testid="approval-assistance"]') || null,
            assistanceAttributes: JSON.stringify([...((input.kind === 'confirmation' ? task : root)?.querySelector('[data-testid="approval-assistance"]')?.attributes || [])].filter(item => item.name.startsWith('data-')).map(item => [item.name, item.value])),
            geometry,
            images: [...images].map(([element, state]) => ({ element, source: state.source })),
            absentTaskId: input.absentTaskId,
          },
        });
        return true;
      } catch (error) {
        finish(error instanceof Error ? error : new Error('Surface observation failed'));
        return false;
      }
    };
    const tick = () => {
      if (!sample()) {
        // Any unsettled frame invalidates earlier geometric stability.
        if (lastState !== 'natural geometry') stableFrames = 0;
        if (!finished) frame = requestAnimationFrame(tick);
      }
    };
    frame = requestAnimationFrame(tick);
  });
}

type ObservedSurface = Awaited<ReturnType<typeof observeSurface>>;
export type SurfaceReadiness = Omit<ObservedSurface, 'witness'>;
const witnesses = new WeakMap<SurfaceReadiness, JSHandle<ObservedSurface>>();

/** Revalidate the exact observed nodes, not a newer matching route/task. */
export function validateSurfaceWitness(value: ObservedSurface) {
  const witness = value.witness;
  const attributes = (node: Element) => JSON.stringify([...node.attributes]
    .filter(item => item.name.startsWith('data-')).map(item => [item.name, item.value]));
  if (location.href !== witness.url || !witness.root.isConnected || !witness.surface.isConnected
    || attributes(witness.root) !== witness.attributes
    || (witness.assistance && (!witness.assistance.isConnected || attributes(witness.assistance) !== witness.assistanceAttributes))
    || (witness.task && (!witness.task.isConnected || attributes(witness.task) !== witness.taskAttributes))) {
    throw new Error('Capture witness changed before publication');
  }
  if (witness.absentTaskId && [...witness.root.querySelectorAll('[data-task-id]')]
    .some(node => node.getAttribute('data-task-id') === witness.absentTaskId)) {
    throw new Error('Completed task returned before publication');
  }
  const box = witness.surface.getBoundingClientRect();
  if (JSON.stringify([box.x, box.y, box.width, box.height]) !== witness.geometry) {
    throw new Error('Capture geometry changed before publication');
  }
  const nodes: Element[] = [witness.surface];
  for (let node = witness.surface.parentElement; node; node = node.parentElement) nodes.push(node);
  if (nodes.some(node => {
    const style = getComputedStyle(node);
    return style.display === 'none' || style.visibility !== 'visible' || Number(style.opacity) < 0.999;
  }) || [...witness.surface.getAnimations({ subtree: true }), ...nodes.flatMap(node => node.getAnimations())]
    .some(animation => {
      const end = animation.effect?.getComputedTiming().endTime;
      return typeof end === 'number' && Number.isFinite(end) && (animation.pending || animation.playState !== 'finished');
    })) throw new Error('Capture surface became unsettled before publication');
  const visibleImages = [...document.images].filter(element => {
    const box = element.getBoundingClientRect();
    if (box.width <= 0 || box.height <= 0) return false;
    for (let node: Element | null = element; node; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (style.display === 'none' || style.visibility !== 'visible') return false;
    }
    return true;
  });
  if (visibleImages.length !== witness.images.length
    || visibleImages.some(element => !witness.images.some(value => value.element === element))
    || document.fonts.status !== 'loaded' || witness.images.some(({ element, source }) =>
    !element.isConnected || (element.currentSrc || element.src) !== source
    || !element.complete || !element.naturalWidth || !element.naturalHeight)) {
    throw new Error('Capture assets changed before publication');
  }
}

export async function readySurface(page: Page, budget: CaptureBudget, expected: SurfaceExpectation): Promise<SurfaceReadiness> {
  const handle = await budget.run(timeout => page.evaluateHandle(observeSurface, { ...expected, timeout }), 30_000);
  try {
    const receipt = await budget.run(() => handle.evaluate(({ witness: _witness, ...value }) => value));
    witnesses.set(receipt, handle);
    return receipt;
  } catch (error) {
    await handle.dispose();
    throw error;
  }
}

export async function captureScreenshot(
  page: Page,
  budget: CaptureBudget,
  path: string,
  options: { readiness?: SurfaceReadiness; assertCurrent?: () => Promise<void> },
) {
  const handle = options.readiness ? witnesses.get(options.readiness) : undefined;
  if (!handle && !options.assertCurrent) throw new Error('Screenshot requires a current surface or acknowledged action');
  const validate = async () => {
    budget.remaining();
    if (handle) await budget.run(() => handle.evaluate(validateSurfaceWitness));
    if (options.assertCurrent) await budget.run(options.assertCurrent);
  };
  await validate();
  // A timeout/cancellation may leave protocol work resolving later. Buffer-only
  // capture cannot publish a success file after its owner has failed.
  const bytes = await budget.run(timeout => page.screenshot({ fullPage: true, timeout }));
  await validate();
  const temporary = `${path}.capture.tmp`;
  let published = false;
  try {
    writeFileSync(temporary, bytes, { mode: 0o600 });
    budget.remaining();
    renameSync(temporary, path);
    published = true;
    budget.remaining();
  } catch (error) {
    if (published) rmSync(path, { force: true });
    throw error;
  } finally {
    rmSync(temporary, { force: true });
  }
}

/** Keep existing synchronous receipt writers inside the same absolute budget. */
export function publishCaptureReceipt(
  budget: CaptureBudget,
  target: string,
  publish: () => undefined,
  temporary?: string,
) {
  budget.remaining();
  const paths = temporary ? [target, temporary] : [target];
  const inspect = (path: string) => lstatSync(path, { bigint: true, throwIfNoEntry: false });
  // A rerun must never overwrite or remove a historical receipt or temporary
  // artifact, including a dangling symlink. These producers own fresh paths.
  for (const path of paths) {
    if (inspect(path)) throw new Error('Capture receipt path already exists');
  }
  budget.remaining();
  const created = new Map<string, NonNullable<ReturnType<typeof inspect>>>();
  let completed = false;
  try {
    try {
      const result: unknown = publish();
      if (result !== undefined) throw new Error('Capture receipt writer must finish synchronously without a return value');
    } finally {
      // Synchronous writers cannot continue publishing after this observation.
      // Remember only new output from this invocation, even on a write failure.
      for (const path of paths) {
        const identity = inspect(path);
        if (identity) created.set(path, identity);
      }
    }
    if (!created.get(target)?.isFile()) throw new Error('Capture receipt was not published');
    budget.remaining();
    completed = true;
  } finally {
    if (!completed) {
      for (const [path, owned] of created) {
        const current = inspect(path);
        if (current && current.dev === owned.dev && current.ino === owned.ino
          && current.size === owned.size && current.mtimeNs === owned.mtimeNs
          && current.ctimeNs === owned.ctimeNs) rmSync(path);
      }
    }
  }
}
