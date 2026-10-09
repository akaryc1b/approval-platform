import type { FullResult, Reporter, TestCase, TestResult } from '@playwright/test/reporter';
import type { TestInfo } from '@playwright/test';

// Closed diagnostic vocabulary, never receipt fields or acceptance markers.
export const captureFailurePhases = Object.freeze([
  'UNAVAILABLE', 'TEST_SETUP', 'PC_AUTHENTICATION', 'PC_NAVIGATION',
  'PC_TASK_VISIBILITY', 'PC_SURFACE_READINESS', 'PC_FONT', 'PC_SCREENSHOT',
  'H5_NAVIGATION', 'H5_TASK_VISIBILITY', 'H5_SURFACE_READINESS',
  'H5_COMPONENTS', 'H5_FONT', 'H5_SCREENSHOT', 'RECEIPT_PUBLICATION', 'CLEANUP',
] as const);
export type CaptureFailurePhase = typeof captureFailurePhases[number];
export const browserFailureCategories = Object.freeze(['FAILED', 'TIMED_OUT', 'INTERRUPTED', 'RUNNER_ERROR', 'UNAVAILABLE'] as const);
const readinessReasons = Object.freeze({
  route: 'ROUTE', surface: 'SURFACE', 'completed task still present': 'COMPLETED_TASK_PRESENT',
  'task identity': 'TASK_IDENTITY', 'list refresh': 'LIST_REFRESH', 'list totals': 'LIST_TOTALS',
  'list controls or empty state': 'LIST_CONTROLS', 'bootstrap overlay': 'BOOTSTRAP_OVERLAY',
  'blocking overlay': 'BLOCKING_OVERLAY', 'detail data': 'DETAIL_DATA',
  'task assistance snapshot': 'ASSISTANCE', 'unresolved Wot component': 'COMPONENT_RESOLUTION',
  'Wot button styles': 'COMPONENT_STYLES', 'Wot search styles': 'COMPONENT_STYLES',
  'Wot tag styles': 'COMPONENT_STYLES', fonts: 'FONT', 'image load': 'IMAGE', 'image decode': 'IMAGE',
  'finite transition': 'TRANSITION', 'surface opacity': 'OPACITY',
  'viewport intersection': 'VIEWPORT', 'natural geometry': 'GEOMETRY',
} as const);
const exactReasons = Object.freeze({
  'Capture deadline/cap must have a finite positive remainder': 'INVALID_DEADLINE',
  'Capture deadline expired': 'DEADLINE', 'Capture operation exceeded its deadline': 'DEADLINE',
  'Invalid surface deadline': 'INVALID_DEADLINE', 'Surface readiness deadline expired': 'DEADLINE',
  'Required fonts failed': 'FONT', 'Required visible image failed': 'IMAGE',
  'Required visible image decode failed': 'IMAGE',
  'Capture cancelled by navigation': 'NAVIGATION_CHANGED',
  'Capture cancelled by surface replacement or dismissal': 'SURFACE_CHANGED',
  'Capture cancelled by refresh change': 'REFRESH_CHANGED',
  'Capture cancelled by detail reload': 'DETAIL_CHANGED',
  'Capture cancelled by assistance replacement': 'ASSISTANCE_CHANGED',
  'Capture cancelled by assistance reload': 'ASSISTANCE_CHANGED',
  'Capture cancelled by task change': 'TASK_CHANGED',
  'Capture witness changed before publication': 'WITNESS',
  'Completed task returned before publication': 'COMPLETED_TASK_PRESENT',
  'Capture geometry changed before publication': 'GEOMETRY',
  'Capture surface became unsettled before publication': 'TRANSITION',
  'Capture assets changed before publication': 'ASSET_CHANGED',
  'Screenshot requires a current surface or acknowledged action': 'MISSING_WITNESS',
  'Capture receipt path already exists': 'RECEIPT_PUBLICATION',
  'Capture receipt writer must finish synchronously without a return value': 'RECEIPT_PUBLICATION',
  'Capture receipt was not published': 'RECEIPT_PUBLICATION',
} as const);
export const browserFailureReasons = Object.freeze([
  'UNKNOWN', ...new Set([...Object.values(readinessReasons), ...Object.values(exactReasons)]),
  'REQUIRED_ASSET', 'PAGE_ERROR', 'RUNTIME_FAILURES',
]);
const annotationType = 'capture-failure-phase-v1';

/** Private, exact known-helper lookup. Only a closed reason ever leaves here. */
export function captureFailureReason(errors: unknown): string {
  try {
    if (!Array.isArray(errors) || errors.length !== 1 || typeof errors[0]?.message !== 'string') return 'UNKNOWN';
    const message = errors[0].message as string;
    if (message.length > 32_768 || /[\x00-\x09\x0b-\x1f\x7f]/u.test(message)) return 'UNKNOWN';
    const [first, ...stack] = message.split('\n');
    if (stack.length > 64 || stack.some(line => !/^    at [^\r\n]{1,2048}$/u.test(line))) return 'UNKNOWN';
    // Pinned Playwright 1.60 filterStackTrace prepends the Error name to its
    // private TestError.message; evaluate errors retain their API prefix.
    let text = first.startsWith('Error: ') ? first.slice('Error: '.length) : first;
    for (const prefix of ['page.evaluateHandle: Error: ', 'page.evaluate: Error: ', 'jsHandle.evaluate: Error: ']) {
      if (text.startsWith(prefix)) { text = text.slice(prefix.length); break; }
    }
    const exact = Object.entries(exactReasons).find(([source]) => source === text);
    if (exact) return exact[1];
    const readiness = Object.entries(readinessReasons).find(([state]) => text === `Surface readiness deadline expired (${state})`);
    if (readiness) return readiness[1];
    const counts = text.match(/^Capture has failed script\/style loads \(((?:0|[1-9][0-9]{0,5}))\) or unresolved Wot components \(((?:0|[1-9][0-9]{0,5}))\) or page errors \(((?:0|[1-9][0-9]{0,5}))\)$/u);
    if (counts) {
      const present = counts.slice(1).map(value => Number(value) > 0);
      if (present.filter(Boolean).length > 1) return 'RUNTIME_FAILURES';
      if (present[0]) return 'REQUIRED_ASSET';
      if (present[1]) return 'COMPONENT_RESOLUTION';
      if (present[2]) return 'PAGE_ERROR';
    }
  } catch { /* Unknown/malformed diagnostics do not alter the original failure. */ }
  return 'UNKNOWN';
}

export function captureFailurePhase(info: Pick<TestInfo, 'annotations'>) {
  let failed = false;
  return {
    enter(phase: CaptureFailurePhase) {
      if (failed) return;
      const description = captureFailurePhases.includes(phase) ? phase : 'UNAVAILABLE';
      info.annotations = info.annotations.filter(item => item.type !== annotationType);
      info.annotations.push({ type: annotationType, description });
    },
    preserveFailure() { failed = true; },
  };
}

/** Runs alongside the original reporter, before failure-artifact publication. */
export default class CaptureFailureReporter implements Reporter {
  private reported = false;

  private emit(phase: string, category: typeof browserFailureCategories[number], reason = 'UNKNOWN') {
    console.log(`BROWSER_FAILURE_V1 phase=${phase} category=${category} reason=${reason}`);
    this.reported = true;
  }

  onTestEnd(test: TestCase, result: TestResult) {
    if (result.status === 'passed' || result.status === 'skipped') return;
    const phases = Array.isArray(test.annotations)
      ? test.annotations.filter(item => item?.type === annotationType) : [];
    const value = phases.length === 1 ? phases[0]?.description : undefined;
    const phase = captureFailurePhases.find(item => item === value) ?? 'UNAVAILABLE';
    // Status is the runner's structured result. Private helper-message lookup
    // emits no message/stack, test title, URL, path or identifier.
    const category = result.status === 'failed' ? 'FAILED'
      : result.status === 'timedOut' ? 'TIMED_OUT'
        : result.status === 'interrupted' ? 'INTERRUPTED' : 'UNAVAILABLE';
    this.emit(phase, category, captureFailureReason(result.errors));
  }

  onError() { this.emit('UNAVAILABLE', 'RUNNER_ERROR'); }

  onEnd(result: FullResult) {
    if (!this.reported && result.status !== 'passed') this.emit('UNAVAILABLE', 'UNAVAILABLE');
  }
}
