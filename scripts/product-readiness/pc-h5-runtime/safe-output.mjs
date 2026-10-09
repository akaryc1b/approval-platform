// Subprocess output is untrusted, including output from install hooks and failed
// commands. Do not attempt to discover every possible password/token with regexes.
// Only project fixed protocol markers and narrowly typed numeric diagnostics.
export const maximumProcessLineBytes = 8_192;
export const maximumCheckedOutputBytes = 8 * 1_024 * 1_024;
const maximumNumericSummaries = 64;

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
  const emitted = new Set();
  const controls = new Set();

  const once = value => {
    if (emitted.has(value)) return;
    emitted.add(value);
    emit(`${value}\n`);
  };
  const omitted = () => once('[subprocess] unstructured output omitted');
  const line = bytes => {
    const value = bytes.toString('utf8')
      .replace(/\u001b\[[0-9;]*m/gu, '')
      .trim();
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
