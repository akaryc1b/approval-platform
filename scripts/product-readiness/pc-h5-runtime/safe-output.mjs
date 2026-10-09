// Subprocess output is untrusted, including output from install hooks and failed
// commands. Do not attempt to discover every possible password/token with regexes.
// Only project fixed protocol markers and narrowly typed numeric diagnostics.
export const maximumProcessLineBytes = 8_192;
export const maximumCheckedOutputBytes = 8 * 1_024 * 1_024;
const maximumNumericSummaries = 64;
const maximumContextSummaries = 64;

const browserFailurePhases = new Set([
  'UNAVAILABLE', 'TEST_SETUP', 'PC_AUTHENTICATION', 'PC_NAVIGATION',
  'PC_TASK_VISIBILITY', 'PC_SURFACE_READINESS', 'PC_FONT', 'PC_SCREENSHOT',
  'H5_NAVIGATION', 'H5_TASK_VISIBILITY', 'H5_SURFACE_READINESS',
  'H5_COMPONENTS', 'H5_FONT', 'H5_SCREENSHOT', 'RECEIPT_PUBLICATION', 'CLEANUP',
  'PC_AUDIT', 'PC_DETAIL_ACTION', 'PC_DETAIL_READINESS', 'PC_DETAIL_AUDIT',
  'PC_CONFIRMATION', 'PC_DETAIL_SCREENSHOT', 'H5_AUDIT', 'H5_DETAIL_ACTION',
  'H5_DETAIL_READINESS', 'H5_DETAIL_AUDIT', 'H5_DETAIL_SCREENSHOT', 'MATRIX_ASSERTIONS',
]);
const browserFailureCategories = new Set(['FAILED', 'TIMED_OUT', 'INTERRUPTED', 'RUNNER_ERROR', 'UNAVAILABLE']);
const browserFailureReasons = new Set([
  'UNKNOWN', 'ROUTE', 'SURFACE', 'COMPLETED_TASK_PRESENT', 'TASK_IDENTITY',
  'LIST_REFRESH', 'LIST_TOTALS', 'LIST_CONTROLS', 'BOOTSTRAP_OVERLAY',
  'BLOCKING_OVERLAY', 'DETAIL_DATA', 'ASSISTANCE', 'COMPONENT_RESOLUTION',
  'COMPONENT_STYLES', 'FONT', 'IMAGE', 'TRANSITION', 'OPACITY', 'VIEWPORT',
  'GEOMETRY', 'INVALID_DEADLINE', 'DEADLINE', 'NAVIGATION_CHANGED',
  'SURFACE_CHANGED', 'REFRESH_CHANGED', 'DETAIL_CHANGED', 'ASSISTANCE_CHANGED',
  'TASK_CHANGED', 'WITNESS', 'ASSET_CHANGED', 'MISSING_WITNESS',
  'RECEIPT_PUBLICATION', 'REQUIRED_ASSET', 'PAGE_ERROR', 'RUNTIME_FAILURES',
]);
const browserPageErrorKinds = new Set([
  'NONE', 'UNKNOWN', 'MULTIPLE', 'ERROR', 'TYPE_ERROR', 'REFERENCE_ERROR',
  'SYNTAX_ERROR', 'RANGE_ERROR', 'EVAL_ERROR', 'URI_ERROR', 'AGGREGATE_ERROR',
]);
function validPageError(count, kind) {
  return browserPageErrorKinds.has(kind) && (count === 'UNKNOWN' ? kind === 'UNKNOWN'
    : count === '0' ? kind === 'NONE' : kind !== 'NONE' && (kind !== 'MULTIPLE' || Number(count) >= 2));
}
const publicationRejectionCategories = new Set([
  'REQUEST_INVALID', 'INPUT_READ_FAILED', 'ARCHIVE_INVALID', 'CONTENT_INVALID',
  'BINARY_INVALID', 'REFERENCE_INVALID', 'DISCOVERY_REJECTED', 'TRANSFORM_REJECTED',
  'IDENTITY_CHANGE', 'ASSERTION_CHANGE', 'RESOURCE_LIMIT', 'SANITIZER_INTERNAL',
  'SANITIZER_PROCESS_FAILED', 'SANITIZER_TERMINATED', 'SANITIZER_PROTOCOL_INVALID',
  'SANITIZER_RESPONSE_INVALID',
]);

// Advisory failure diagnostics only. They never enter the readiness/control
// protocol or establish artifact provenance. Validate original bytes first:
// ANSI stripping or trimming must not turn malformed input into a valid record.
export function safeFailureDiagnostic(line) {
  if (typeof line !== 'string' || line.length > 256 || /[^\x20-\x7e]/u.test(line)) return undefined;
  let match = line.match(/^BROWSER_FAILURE_V1 phase=([A-Z0-9_]+) category=([A-Z_]+) reason=([A-Z_]+)$/u);
  if (match && browserFailurePhases.has(match[1]) && browserFailureCategories.has(match[2])
    && browserFailureReasons.has(match[3])) return line;
  match = line.match(/^BROWSER_CONTEXT_V1 engine=(UNKNOWN|CHROMIUM|FIREFOX|WEBKIT) pcCount=(UNKNOWN|0|[1-9][0-9]{0,5}) pcKind=([A-Z_]+) h5Count=(UNKNOWN|0|[1-9][0-9]{0,5}) h5Kind=([A-Z_]+)$/u);
  if (match && validPageError(match[2], match[3]) && validPageError(match[4], match[5])) return line;
  match = line.match(/^EVIDENCE_REJECTION_V1 category=([A-Z_]+)$/u);
  if (match && publicationRejectionCategories.has(match[1])) return line;
  return undefined;
}

const readinessMarkers = [
  'BACKEND_LOCAL_START_VERIFIED',
  'PURCHASE_PAYMENT_DEMO_SEED_APPLIED',
  'PURCHASE_PAYMENT_LOCAL_SANDBOX_STARTED',
];
const fixedLines = new Set([
  ...readinessMarkers,
  '[INFO] BUILD SUCCESS',
  '[INFO] BUILD FAILURE',
  'DEMO_BACKEND_ONE_COMMAND_STARTED',
  'DEMO_BACKEND_PROCESS_STOPPED',
  'QUICK_START_10_MINUTES_NOT_EXECUTED',
  'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
  'QUICK_START_FIRST_CLEAN_RUN_RECORDED',
  'QUICK_START_SECOND_CLEAN_RUN_STARTING',
  'TWO_CONSECUTIVE_CLEAN_QUICK_START_RUNS_REQUIRED',
  'TWO_CONSECUTIVE_CLEAN_QUICK_START_RUNS_PASSED',
  'QUICK_START_10_MINUTES_PASSED',
  'DEMO_BACKEND_READY_PASSED',
  'PC_DEMO_READY_PASSED',
  'H5_DEMO_READY_PASSED',
  "code: 'ERR_ASSERTION'",
  "failureType: 'testCodeFailure'",
  "failureType: 'subtestsFailed'",
  "operator: 'strictEqual'",
  "operator: 'deepStrictEqual'",
  "operator: 'match'",
]);
const controlNames = new Set([
  'QUICK_START_PC_URL',
  'QUICK_START_H5_URL',
  'QUICK_START_EVIDENCE',
  'QUICK_START_TENANT',
  'QUICK_START_BUSINESS_KEY',
  'QUICK_START_PC_ACTOR',
  'QUICK_START_H5_ACTOR',
]);
const processErrorCodes = new Set([
  'ENOENT', 'EACCES', 'EPERM', 'ENOBUFS', 'ETIMEDOUT', 'EINVAL',
  'EMFILE', 'ENFILE', 'E2BIG', 'ENOMEM', 'EIO', 'ENOSPC',
]);

export function safeProcessErrorCode(error) {
  return processErrorCodes.has(error?.code) ? error.code : 'PROCESS_ERROR';
}

function numericSummary(line) {
  // Anchored grammars deliberately reject free-form test names and suffixes.
  let match = line.match(/^(?:#|ℹ) (tests|suites|pass|fail|cancelled|skipped|todo) ([0-9]{1,9})$/u);
  if (match) return `# ${match[1]} ${Number(match[2])}`;
  match = line.match(/^(?:#|ℹ) duration_ms ([0-9]{1,9}(?:\.[0-9]{1,6})?)$/u);
  if (match) return `# duration_ms ${Number(match[1])}`;
  match = line.match(/^(?:\[INFO\] )?Tests run: ([0-9]{1,9}), Failures: ([0-9]{1,9}), Errors: ([0-9]{1,9}), Skipped: ([0-9]{1,9})(?:, Time elapsed: [0-9]{1,9}(?:\.[0-9]{1,6})? s)?$/u);
  if (match) {
    return `Tests run: ${Number(match[1])}, Failures: ${Number(match[2])}, `
      + `Errors: ${Number(match[3])}, Skipped: ${Number(match[4])}`;
  }
  match = line.match(/^([0-9]{1,9}) (passed|failed|skipped)(?: \([0-9]{1,9}(?:\.[0-9]{1,6})?(?:ms|s|m)\))?$/u);
  if (match) return `${Number(match[1])} ${match[2]}`;
  match = line.match(/^(expected|actual): (-?[0-9]{1,9}|true|false|null)$/u);
  if (match) return `${match[1]}: ${match[2]}`;
  return undefined;
}

/**
 * One recorder per pipe: stderr cannot complete an unfinished stdout line.
 * Incomplete and oversized lines never fall back to raw output. At most one
 * bounded line and a fixed number of diagnostics are retained at a time.
 * onControlLine is an INTERNAL protocol callback, never a logging sink.
 */
export function createSafeProcessOutput({ emit, onControlLine } = {}) {
  if (typeof emit !== 'function') throw new Error('safe process output requires an emitter');
  let pending = Buffer.alloc(0);
  let dropping = false;
  let ended = false;
  let numericSummaries = 0;
  let contextSummaries = 0;
  const emitted = new Set();
  const controls = new Set();

  const once = value => {
    if (emitted.has(value)) return;
    emitted.add(value);
    emit(`${value}\n`);
  };
  const omitted = () => once('[subprocess] unstructured output omitted');
  const line = bytes => {
    const original = bytes.toString('utf8');
    const diagnostic = safeFailureDiagnostic(original);
    if (diagnostic) {
      if (diagnostic.startsWith('BROWSER_CONTEXT_V1 ')) {
        if (emitted.has(diagnostic)) return;
        if (contextSummaries >= maximumContextSummaries) {
          omitted();
          return;
        }
        contextSummaries += 1;
      }
      once(diagnostic);
      return;
    }
    const value = original
      .replace(/\u001b\[[0-9;]*m/gu, '')
      .trim();
    // Invalid diagnostic-looking input is terminally omitted. In particular it
    // cannot smuggle an existing readiness marker in a suffix or another field.
    if (/(?:BROWSER_FAILURE|BROWSER_CONTEXT|EVIDENCE_REJECTION)/u.test(value.replace(/[^\x21-\x7e]/gu, ''))) {
      omitted();
      return;
    }
    if (!value) return;
    if (fixedLines.has(value)) {
      once(value);
      return;
    }
    // Java readiness logs contain a logger prefix and variable fields. Emit
    // only the known marker, never its prefix, suffix, or captured values.
    for (const marker of readinessMarkers) {
      if (new RegExp(`(?:^|\\s)${marker}(?=\\s|$)`, 'u').test(value)) {
        once(marker);
        return;
      }
    }
    const separator = value.indexOf('=');
    const name = value.slice(0, separator);
    if (separator > 0 && controlNames.has(name)) {
      // Existing Quick Start orchestration consumes these values in memory.
      // They are never included in the emitted log projection.
      if (onControlLine && !controls.has(name)) {
        controls.add(name);
        onControlLine(`${value}\n`);
      }
      once(`${name}=[value withheld]`);
      return;
    }
    const summary = numericSummary(value);
    if (summary !== undefined) {
      if (numericSummaries < maximumNumericSummaries) {
        numericSummaries += 1;
        emit(`${summary}\n`);
      } else once('[subprocess] further numeric summaries omitted');
      return;
    }
    const errorType = value.match(/^(Error|TypeError|SyntaxError|ReferenceError|AssertionError|RangeError|URIError|EvalError|AggregateError)(?: \[ERR_ASSERTION\])?:/u);
    if (errorType) {
      once(`[subprocess] ${errorType[1]} reported; unstructured details omitted`);
    } else if (/^(?:\[ERROR\]|ERR_PNPM_|ELIFECYCLE)/u.test(value)) {
      once('[subprocess] error reported; unstructured details omitted');
    } else if (/^(?:\[WARN(?:ING)?\]|WARN(?:\s|$)|Warning:)/u.test(value)) {
      once('[subprocess] warning reported; unstructured details omitted');
    } else omitted();
  };
  const guarded = action => {
    try {
      action();
    } catch {
      // Never forward an exception from formatting, a protocol consumer, or a
      // sink: its message/cause may contain the same sensitive input.
      pending = Buffer.alloc(0);
      ended = true;
      throw new Error('subprocess output sanitization failed');
    }
  };
  const write = chunk => {
    if (ended) throw new Error('safe process output is already closed');
    const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    let offset = 0;
    while (offset < bytes.length) {
      const newline = bytes.indexOf(10, offset);
      const end = newline === -1 ? bytes.length : newline;
      if (!dropping) {
        const length = end - offset;
        if (pending.length + length > maximumProcessLineBytes) {
          pending = Buffer.alloc(0);
          dropping = true;
          omitted();
        } else {
          pending = Buffer.concat([pending, bytes.subarray(offset, end)]);
        }
      }
      if (newline === -1) break;
      if (!dropping) line(pending);
      pending = Buffer.alloc(0);
      dropping = false;
      offset = newline + 1;
    }
  };
  const end = () => {
    if (ended) return;
    ended = true;
    if (!dropping && pending.length) line(pending);
    pending = Buffer.alloc(0);
  };
  return {
    write: chunk => guarded(() => write(chunk)),
    end: () => guarded(end),
  };
}
